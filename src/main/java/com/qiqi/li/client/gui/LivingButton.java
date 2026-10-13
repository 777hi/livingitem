package com.qiqi.li.client.gui;

import com.mojang.logging.LogUtils;
import com.qiqi.li.LivingItem;
import com.qiqi.li.client.mixinsupport.MutableSpriteSpriteIconButton;
import com.qiqi.li.network.LivingTagPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import com.qiqi.li.living.api.HasDirection;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import org.slf4j.Logger;

/**
 * 活物品切换按钮 —— 在容器界面中显示，点击后切换手持物品的 IS_LIVING 标记。
 *
 * 显示位置：箱子界面底部中央（slot 9 上方）
 * 交互方式：鼠标悬停时切换高亮贴图，点击发送 {@link LivingTagPacket}
 *
 * 技术细节：
 *   继承自 SpriteIconButton.CenteredIcon，使用贴图精灵而非文字渲染按钮外观。
 *   通过 {@link MutableSpriteSpriteIconButton} Mixin 实现贴图动态切换（悬停/非悬停），
 *   因为 SpriteIconButton 的 sprite 字段是 final 的，需要 Mixin 修改。
 */
public class LivingButton extends SpriteIconButton.CenteredIcon {
    /** 按钮尺寸（16×8 精灵）—— 默认位计算（LivingButtonLayout）与射线微调避让共用。 */
    public static final int WIDTH = 16;
    public static final int HEIGHT = 8;
    private static final int SPRITE_WIDTH = 16;
    private static final int SPRITE_HEIGHT = 8;
    private static final Component ANNOTATION = Component.translatable("text.livingitem.living");

    private static final ResourceLocation SPRITE = LivingItem.id("living_button");
    private static final ResourceLocation HOVERED_SPRITE = LivingItem.id("living_button_hovered");

    private final AbstractContainerMenu menu;
    private final Slot slot;

    // ==================== 拖动改位置（2026-10-10，第二步） ====================
    // 规则：**光标空手**时按住左键拖动 ⇒ 移动按钮，松手写盘（按 Screen 类名分别记住）。
    // 手持物品时点击 = 活化（走 onPress），两者互不干扰。
    //
    // ⚠️ 拖动事件**不由原版分发**：AbstractContainerScreen 重写了 mouseDragged 且不调 super，
    // 子控件收不到（详见 LivingItemInputHandler#onMouseDragged 的说明）。转发由该类经
    // ScreenEvent.MouseDragged.Pre 完成；松手则走原版路径（Forge 补丁调了 super）。

    /** 左键按下在按钮上（光标空手）—— 拖动手势的起点。 */
    private boolean dragPressed = false;
    /** 已越过启动阈值，真正进入拖动（避免纯点击的像素抖动被误判为拖动）。 */
    private boolean dragging = false;
    private double pressX;
    private double pressY;
    private double dragOffsetX;
    private double dragOffsetY;

    /** 启动拖动所需的最小位移（像素，曼哈顿距离）—— 低于此值视为「点击」。 */
    private static final double DRAG_THRESHOLD = 2.0;

    public static final Logger LOGGER = LogUtils.getLogger();

    public LivingButton(int x, int y, AbstractContainerMenu menu, Slot slot) {
        super(WIDTH, HEIGHT, ANNOTATION, SPRITE_WIDTH, SPRITE_HEIGHT, SPRITE, LivingButton::onPress, null);
        this.menu = menu;
        this.slot = slot;
        // ⚠️ 应用保存的位置时**再夹一次**：拖动时的夹取只管当时那一次；
        // 之后缩小窗口 / 调大 GUI scale ⇒ 逻辑屏幕变小，而保存的偏移是相对 GUI 的
        // ⇒ 换算出的屏幕坐标可能落到屏外（按钮"消失"，2026-10-12 实测反馈）。
        // 只夹显示位置、不回写配置（恢复窗口后回到原位）。
        var window = Minecraft.getInstance().getWindow();
        int[] clamped = LivingButtonLayout.clampToScreen(
            x, y, window.getGuiScaledWidth(), window.getGuiScaledHeight(), WIDTH, HEIGHT);
        this.setX(clamped[0]);
        this.setY(clamped[1]);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // ⚠️ 必须自己判命中：本方法绕过了 AbstractWidget.mouseClicked 的 active/visible/clicked 检查，
        // 漏判会让「空手在界面任意位置按下」都启动拖动手势（2026-10-10 实测踩坑）。
        if (button == 0 && this.isMouseOver(mouseX, mouseY) && menu.getCarried().isEmpty()) {
            // 光标空手且按在按钮上 → 进入「按住拖动」手势（空手点击本来也没有其它语义）
            dragPressed = true;
            dragging = false;
            pressX = mouseX;
            pressY = mouseY;
            dragOffsetX = mouseX - this.getX();
            dragOffsetY = mouseY - this.getY();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 移动按钮。
     *
     * <p>⚠️ 本方法**不会被原版自动调用** —— 容器界面重写 mouseDragged 且不调 super，
     * 子控件收不到拖动事件。实际由 {@link com.qiqi.li.client.input.LivingItemInputHandler#onMouseDragged}
     * 经 {@code ScreenEvent.MouseDragged.Pre} 转发进来。</p>
     *
     * @return true = 本次拖动已被按钮消费（事件随之取消，原版槽位拖动逻辑跳过）；
     *         false = 不在拖动手势中，让事件照常走原版。
     */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // 不在拖动手势 ⇒ 明确返回 false。
        // ⚠️ **不能**委托 super：AbstractWidget.mouseDragged 对左键**恒返回 true**
        // （它只看 isValidClickButton，不看拖动状态），会让事件被误取消 ⇒
        // 手持物品时的原版快速合成拖动失效（2026-10-10 一并修掉）。
        if (!dragPressed || button != 0) {
            return false;
        }
        if (!dragging
            && Math.abs(mouseX - pressX) + Math.abs(mouseY - pressY) >= DRAG_THRESHOLD) {
            dragging = true;   // 越过阈值才真正开始拖动
        }
        if (dragging) {
            Minecraft mc = Minecraft.getInstance();
            int nx = (int) Math.round(mouseX - dragOffsetX);
            int ny = (int) Math.round(mouseY - dragOffsetY);
            // 夹在屏幕内 —— 防止按钮被拖到看不见的地方
            nx = Mth.clamp(nx, 0, Math.max(0, mc.getWindow().getGuiScaledWidth() - this.getWidth()));
            ny = Mth.clamp(ny, 0, Math.max(0, mc.getWindow().getGuiScaledHeight() - this.getHeight()));
            this.setX(nx);
            this.setY(ny);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // 只认左键（与 mouseClicked / mouseDragged 对齐）：否则「左键按下拖动 → 再按右键释放」
        // 会用右键的释放结束拖动手势，语义错位。
        if (dragPressed && button == 0) {
            boolean wasDragging = dragging;
            dragPressed = false;
            dragging = false;
            if (wasDragging) savePosition();   // 没真正移动过则不写盘（避免无谓 IO）
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 把当前屏幕坐标换算成「相对 GUI 左上角」的偏移并写盘（位置没变则不写）。 */
    private void savePosition() {
        if (!(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> screen)) return;
        LivingButtonLayout.setPosition(
            LivingButtonLayout.screenId(screen, screen.getXSize(), screen.getYSize()),
            this.getX() - screen.getGuiLeft(), this.getY() - screen.getGuiTop());
    }

    /**
     * 渲染按钮时，根据悬停状态切换贴图。
     * 通过 {@link MutableSpriteSpriteIconButton} Mixin 修改 final 的 sprite 字段。
     */
    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        ((MutableSpriteSpriteIconButton) this).setSprite(this.isHovered() ? HOVERED_SPRITE : SPRITE);
        // 方向配置 HUD 可见时 → 按钮 tooltip 让位（信息已在 HUD 里，两个 tooltip 会重叠）
        this.setTooltip(com.qiqi.li.client.input.LivingItemInputHandler.isDirectionHudVisible()
            ? null : Tooltip.create(buildTooltip()));
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * 动态 tooltip（2026-10-10）：按钮作用 + 手持活物品的方向配置提示。
     * 每帧重建（内容随光标物品变化），分配量可忽略。
     */
    private Component buildTooltip() {
        MutableComponent tip = Component.empty()
            .append(Component.translatable("text.livingitem.living_button.tooltip"));
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty() && LivingItemManager.isLivingItem(carried)) {
            HasDirection dirFunc = null;
            for (LivingItemFunction func : LivingItemManager.getApplicableFunctions(carried)) {
                if (func instanceof HasDirection d) {
                    dirFunc = d;
                    break;
                }
            }
            if (dirFunc != null) {
                // 键位提示按能力切换：不支持对角的功能不提 Q/E/Z/C（见 HasDirection#supportsDiagonal）
                tip.append("\n").append(Component.translatable(dirFunc.supportsDiagonal()
                    ? "text.livingitem.living_button.direction_hint"
                    : "text.livingitem.living_button.direction_hint_basic"));
            } else if (carried.is(Items.HOPPER)) {
                tip.append("\n").append(Component.translatable("text.livingitem.living_button.hopper_hint"));
            }
        }
        return tip;
    }

    /**
     * 按钮点击回调 —— 发送标签切换包到服务端。
     *
     * 获取玩家当前手持的物品（carried），如果有物品则发送 LivingTagPacket。
     * 服务端收到包后会切换该物品的 IS_LIVING 标记。
     */
    private static void onPress(Button button) {
        if (button instanceof LivingButton livingButton) {
            ItemStack carriedItem = livingButton.menu.getCarried();

            if (!carriedItem.isEmpty()) {
                LivingTagPacket packet = new LivingTagPacket();
                PacketDistributor.sendToServer(packet);
                LOGGER.info("发送 living tag 数据包，物品: {}", carriedItem.getItem().getName(carriedItem).getString());
            } else {
                LOGGER.info("没有拿着任何物品");
            }
        } else {
            throw new IllegalStateException("LivingButton::onPress called with non LivingButton argument");
        }
    }
}