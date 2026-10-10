package com.qiqi.li.client.gui;

import com.mojang.logging.LogUtils;
import com.qiqi.li.LivingItem;
import com.qiqi.li.client.mixinsupport.MutableSpriteSpriteIconButton;
import com.qiqi.li.network.LivingTagPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
    private static final int WIDTH = 16;
    private static final int HEIGHT = 8;
    private static final int SPRITE_WIDTH = 16;
    private static final int SPRITE_HEIGHT = 8;
    private static final Component ANNOTATION = Component.translatable("text.livingitem.living");

    private static final ResourceLocation SPRITE = LivingItem.id("living_button");
    private static final ResourceLocation HOVERED_SPRITE = LivingItem.id("living_button_hovered");

    private final AbstractContainerMenu menu;
    private final Slot slot;

    public static final Logger LOGGER = LogUtils.getLogger();

    public LivingButton(int x, int y, AbstractContainerMenu menu, Slot slot) {
        super(WIDTH, HEIGHT, ANNOTATION, SPRITE_WIDTH, SPRITE_HEIGHT, SPRITE, LivingButton::onPress, null);
        this.menu = menu;
        this.slot = slot;
        this.setX(x);
        this.setY(y);
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