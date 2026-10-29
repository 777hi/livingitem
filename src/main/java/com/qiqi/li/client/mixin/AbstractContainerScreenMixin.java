package com.qiqi.li.client.mixin;

import com.qiqi.li.client.gui.LivingButton;
import com.qiqi.li.client.input.GuiInteractionHelper;
import com.qiqi.li.client.render.LivingMapGroups;
import com.qiqi.li.client.render.LivingMapLayout;
import com.qiqi.li.client.render.LivingMapOverlay;
import com.qiqi.li.client.util.LivingChestTabState;
import com.qiqi.li.living.domain.chest.LivingChestFunction;
import com.qiqi.li.living.domain.map.LivingEnderPearlFunction;
import com.qiqi.li.network.LivingChestAccessPacket;
import com.qiqi.li.network.LivingMapGuiTeleportPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 活物品容器界面的客户端<b>交互</b>增强：活地图右键传送、手持活末影珍珠的传送命中判定、
 * 活箱子标签页的收纳存取、活物品手持交互拦截，以及界面开关时的缓存生命周期管理。
 *
 * <p>⚠️ <b>渲染不在这里</b>（2026-10-29 迁出）：展开地图 / 方向配置 HUD / 种植提示 / 生长槽大图
 * 原先都注入本类的 {@code render} TAIL，但「照抄一份 {@code super.render}」的第三方界面
 * （如精妙背包 {@code StorageScreenBase.render → renderSuper}）从不调用父实现
 * ⇒ 注入字节码永远不执行 ⇒ 那类 GUI 里这些叠加层全都不渲染。
 * 现已统一挂到 {@code ContainerScreenEvent.Render.Foreground}
 * （见 {@link com.qiqi.li.client.render.LivingContainerOverlay}）。</p>
 */
@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenMixin extends Screen {
    protected AbstractContainerScreenMixin(Component title) {
        super(title);
    }

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Shadow @Final
    protected AbstractContainerMenu menu;

    @Shadow protected int imageHeight;

    @Shadow protected int imageWidth;

    @Shadow
    protected Slot hoveredSlot;

    /**
     * 原版「按下后跳过释放处理」标志（mouseClicked 拿起物品时置位，
     * mouseReleased 开头检测到即 return true 跳过 PICKUP）。
     *
     * <p>手持触发型活物品交互（活锄头/活种子/活骨粉右键槽位）必须在拦截成功时置位：
     * 我们的拦截发生在按下阶段，但原版对「光标非空」的放置/交换发生在释放阶段——
     * 不置位的话释放阶段不匹配交互条目 → 原版 PICKUP 照常执行 → 交互生效的同时
     * 光标物品与槽位物品被交换/放置（交互未拦截原版逻辑的实感来源）。
     * 置位后原版释放阶段自我跳过一次，交互成为该次点击的唯一效果。</p>
     */
    @Shadow
    private boolean skipNextRelease;

    @Unique
    private int living_item$previousLeftPos = this.leftPos;

    @Unique
    private int living_item$previousTopPos = this.topPos;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void living_item$interceptMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;

        boolean hasPearl = living_item$hasLivingEnderPearl();

        if (button == 1 && hasPearl) {
            LivingMapLayout.MapGroup group = LivingMapLayout.findGroupAt(
                LivingMapGroups.of(self), mouseX, mouseY, leftPos, topPos);
            if (group == null && this.hoveredSlot != null) {
                group = LivingMapGroups.findGroupBySlot(self, this.hoveredSlot);
            }
            if (group != null) {
                float[] uv = LivingMapLayout.computeUV(group, mouseX, mouseY, leftPos, topPos);
                int serverSlotIndex = LivingMapGroups.resolveServerSlotIndex(
                    self, group.topLeftSlotIndex());
                PacketDistributor.sendToServer(new LivingMapGuiTeleportPacket(
                    serverSlotIndex, uv[0], uv[1]));
                cir.setReturnValue(true);
                return;
            }
            if (this.hoveredSlot != null) {
                boolean isSingle = LivingMapLayout.isSingleLivingMapSlot(this.hoveredSlot);
                if (isSingle) {
                    float[] uv = LivingMapLayout.computeSingleSlotUV(this.hoveredSlot, mouseX, mouseY, leftPos, topPos);
                    int serverSlotIndex = LivingMapGroups.resolveServerSlotIndex(this.hoveredSlot);
                    PacketDistributor.sendToServer(new LivingMapGuiTeleportPacket(
                        serverSlotIndex, uv[0], uv[1]));
                    cir.setReturnValue(true);
                    return;
                }
            }
        }

        if (GuiInteractionHelper.tryInteract(this.hoveredSlot, button, false, this.menu)) {
            this.skipNextRelease = true;   // 见字段 javadoc：防释放阶段原版 PICKUP 二次执行
            cir.setReturnValue(true);
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_1 && hasShiftDown()
            && LivingChestTabState.isActive()
            && living_item$isRecipeBookVisible()
            && this.hoveredSlot != null && this.hoveredSlot.hasItem()) {
            ItemStack slotStack = this.hoveredSlot.getItem();
            if (!LivingChestFunction.isLivingChest(slotStack)) {
                CompoundTag itemTag = (CompoundTag) slotStack.saveOptional(
                    Minecraft.getInstance().player.registryAccess());
                PacketDistributor.sendToServer(new LivingChestAccessPacket(
                    LivingChestAccessPacket.DEPOSIT_SLOT, itemTag, slotStack.getCount()));
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void living_item$interceptMouseReleased(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (GuiInteractionHelper.tryInteract(this.hoveredSlot, button, true, this.menu)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true)
    private void living_item$suppressTooltipOnExpandedMap(GuiGraphics guiGraphics, int x, int y, CallbackInfo ci) {
        if (!living_item$hasLivingEnderPearl()) return;

        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        LivingMapLayout.MapGroup group = LivingMapLayout.findGroupAt(
            LivingMapGroups.of(self), x, y, leftPos, topPos);
        if (group != null) {
            ci.cancel();
            return;
        }

        if (this.hoveredSlot != null && LivingMapLayout.isSingleLivingMapSlot(this.hoveredSlot)) {
            ci.cancel();
        }
    }

    @Unique
    private boolean living_item$hasLivingEnderPearl() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        return LivingEnderPearlFunction.isLivingEnderPearl(mc.player.getMainHandItem())
            || LivingEnderPearlFunction.isLivingEnderPearl(mc.player.getOffhandItem());
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void living_item$addLivingButton(CallbackInfo ci) {
        this.living_item$initButtons();
        LivingMapGroups.invalidate((AbstractContainerScreen<?>) (Object) this);
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void living_item$cleanupExpandedTextures(CallbackInfo ci) {
        LivingMapOverlay.releaseTextures();
        LivingMapGroups.invalidate((AbstractContainerScreen<?>) (Object) this);
        // 流体快照缓存（Q5 渲染轨）：防下次打开别的容器闪现旧水
        com.qiqi.li.living.domain.water.FluidFlowClientCache.clear();
    }

    @Inject(method = "containerTick", at = @At("TAIL"))
    private void living_item$checkForLeftOrTopPosChange(CallbackInfo ci) {
        if (this.leftPos != this.living_item$previousLeftPos || this.topPos != this.living_item$previousTopPos) {
            this.living_item$initButtons();
        }
    }

    @Unique
    private void living_item$initButtons() {
        List<LivingButton> existingLivingButtons = new ArrayList<>();
        for (GuiEventListener widget : this.children()) {
            if (widget instanceof LivingButton livingButton) {
                existingLivingButtons.add(livingButton);
            }
        }

        for (LivingButton button : existingLivingButtons) {
            this.removeWidget(button);
        }

        if ((Screen) this instanceof CreativeModeInventoryScreen) {
            // 创造界面暂不启用（排除自 37bde65 / 2026-07-14，原因 2026-10-13 补考证）：
            // 活化链路不通 —— LivingTagPacket 服务端读 containerMenu.getCarried()，而创造的
            // carried 是客户端虚拟物品（服务端从不打开创造界面，拾取只走 SetCreativeModeSlotPacket
            // 写槽位）⇒ 服务端视角恒空，点按钮必然静默无效。待活化链路定稿（carriedTag 往返 /
            // 客户端活化）再启用；另需处理标签页维度（selectedTab 是 static，切页不重跑 init）。
            // 详见 docs/system-design/gui-interaction-system.md §6.2。
            this.living_item$previousLeftPos = this.leftPos;
            this.living_item$previousTopPos = this.topPos;
            return;
        }

        if (this.menu.slots.size() > 9) {
            Slot invSlot = this.menu.slots.get(9);
            // 位置：玩家拖动过则用保存的「相对 GUI 左上角」偏移；默认位按界面分流
            // （生存背包 = 小人框底居中 (42,70)，其余 = 底部中央；按 Screen 类名 + GUI 尺寸分别存）
            int[] def = com.qiqi.li.client.gui.LivingButtonLayout.defaultOffset(
                (Screen) this instanceof InventoryScreen, this.imageWidth, this.imageHeight);
            int[] pos = com.qiqi.li.client.gui.LivingButtonLayout.resolve(
                (Screen) this, this.imageWidth, this.imageHeight, def[0], def[1]);
            this.addRenderableWidget(new LivingButton(
                    this.leftPos + pos[0], this.topPos + pos[1], this.menu, invSlot));
        }

        this.living_item$previousLeftPos = this.leftPos;
        this.living_item$previousTopPos = this.topPos;
    }

    @Unique
    private boolean living_item$isRecipeBookVisible() {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof InventoryScreen invScreen) {
            return invScreen.getRecipeBookComponent().isVisible();
        }
        return false;
    }
}
