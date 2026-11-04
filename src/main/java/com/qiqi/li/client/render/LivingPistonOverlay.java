package com.qiqi.li.client.render;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.qiqi.li.client.icon.DirectionalLivingModel;
import com.qiqi.li.client.icon.PistonRenderState;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.piston.LivingPistonData;
import com.qiqi.li.living.domain.piston.PistonSegment;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.runtime.LivingItemClientCache;
import com.qiqi.li.living.runtime.RuntimeSegments;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 活活塞二维伸缩叠加层——板 / 杆沿 GUI 方向 2D blit 滑出与插值缩回（全平面方案）。
 *
 * <h3>数据源</h3>
 * 朝向读物品组件 {@link LivingPistonData}（随同步包下发）；伸出态读
 * {@link LivingItemClientCache} 的 runtime 片段（{@link PistonSegment}）。
 *
 * <h3>分工</h3>
 * 图标模型 {@code item/living_piston} = 纯机身剖面（16×12×16，UV [0,4,16,16]，板缘行
 * 不烘进贴图）；本叠加层只画板与杆（piston_side 顶 4 行板缘剖面，原版贴图区域，零新资产）：
 * 静止时板贴机身前缘（= 朝向指示），动画时板滑出、原位只剩机身（推出残影根源已除）。
 *
 * <h3>位姿与行程</h3>
 * pushPose → translate(cx, cy, 175) → Rz({@link DirectionalLivingModel#rotationOf}) →
 * 框架坐标（活塞朝上；机身 [-8,8]×[-4,8]，前缘 y=−4）内 blit。行程 travel = 一个槽距
 * 18px（含边框）：静止板外缘缩进本格边框 1px（−8，格边 −9），全伸缩进邻格远端边框
 * 1px（−26，格边 −27）——「头块原样挪了一格」，复刻原版全伸板顶头块远端的观感。
 *
 * <h3>已知边界</h3>
 * HUD / 快捷栏不绘制（前景事件只覆盖容器界面）⇒ 那里只有纯机身图标（无板无朝向）；
 * 第三方滚动容器的裁剪状态不可知；创造包装槽的 {@code getContainerSlot()} 可能返回菜单号
 * （{@code ClientSlotResolve} 同族已知边界）。
 */
public final class LivingPistonOverlay {

    /** 原物品 z=150、数量文字 z=200；活塞叠加层固定夹在二者之间。 */
    private static final float OVERLAY_Z = 175f;

    /** 板 / 杆的 blit 源：原版 piston_side 顶 4 行 = 板缘剖面（侧视普通 / 粘性同貌）。 */
    private static final ResourceLocation PISTON_SIDE =
        ResourceLocation.withDefaultNamespace("textures/block/piston_side.png");

    private LivingPistonOverlay() {}

    /** 画当前界面里所有活活塞的伸缩态。位姿须已重置为 identity（见 LivingContainerOverlay）。 */
    public static void render(GuiGraphics guiGraphics, AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        Inventory playerInv = mc.player != null ? mc.player.getInventory() : null;
        int leftPos = screen.getGuiLeft();
        int topPos = screen.getGuiTop();

        Set<String> active = new HashSet<>();
        for (Slot slot : screen.getMenu().slots) {
            Pos2D direction = admission(slot.getItem());
            if (direction == null) continue;
            boolean backpack = isBackpackSlot(playerInv, slot);
            String key = keyFor(backpack, slot.getContainerSlot());
            active.add(key);

            PistonSegment segment = PistonSegment.INSTANCE;
            com.qiqi.li.living.domain.piston.PistonRuntime state =
                segmentFor(backpack, slot.getContainerSlot()).get(segment);
            if (state == null) continue;

            // 静止（收回完成）也要画板 = 朝向指示；仅无 runtime 片段（从未伸出过）才不画。
            float progress = PistonRenderState.advance(key, state.extended(), direction, Util.getMillis());

            draw(guiGraphics, slot, leftPos, topPos, direction, progress);
        }
        PistonRenderState.prune(active);
    }

    private static void draw(GuiGraphics guiGraphics, Slot slot, int leftPos, int topPos,
                             Pos2D direction, float progress) {
        float cx = leftPos + slot.x + 8f;
        float cy = topPos + slot.y + 8f;
        // 行程 = 一个槽距 18px（含边框）；单常数，实机审美可一行微调。
        float travel = PistonRenderState.headOffsetUnits(progress) * LivingIconRenderHelper.SLOT_PITCH;

        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        try {
            pose.translate(cx, cy, OVERLAY_Z);
            pose.mulPose(Axis.ZP.rotationDegrees(DirectionalLivingModel.rotationOf(direction)));

            // 杆（travel > 0 才画）：4px 宽居中，从机身前缘长到板内缘；
            // 源区域照抄原版 template_piston_head 杆侧面 UV [16,4,0,0] = piston_side 顶 4 行
            //（原版杆与板缘本就同一块贴图区域）。
            if (travel > 0f) {
                pose.pushPose();
                pose.translate(-2f, -4f - travel, 0f);
                guiGraphics.blit(PISTON_SIDE, 0, 0, 4, (int) travel, 0f, 0f, 16, 4, 16, 16);
                pose.popPose();
            }

            // 板：16×4 板缘剖面，y 占 [-8-t, -4-t]——静止贴机身前缘；滑出后原位只剩机身。
            // ⚠️ blit(x,y) 是四边形【左上角】锚点 ⇒ 16 宽的板必须 x=-8 起笔才居中 [-8,+8]
            //（x=0 会让板右偏 8px，杆+板呈「7」形——2026-11-05 真机反馈根因）。
            // 先杆后板 ⇒ 板盖住杆顶，观感与原版「板顶在杆远端」一致。
            pose.pushPose();
            pose.translate(0f, -8f - travel, 0f);
            guiGraphics.blit(PISTON_SIDE, -8, 0, 16, 4, 0f, 0f, 16, 4, 16, 16);
            pose.popPose();
        } finally {
            pose.popPose();
        }
    }

    /** 准入（包级可见供单测）：非活物品 / 非活塞 ⇒ null；否则返回组件方向（恒四向）。 */
    static @Nullable Pos2D admission(ItemStack stack) {
        if (stack.isEmpty() || !LivingItemManager.isLivingItem(stack)) return null;
        if (!stack.is(Items.PISTON) && !stack.is(Items.STICKY_PISTON)) return null;
        return LivingPistonData.of(stack).direction();
    }

    static boolean isBackpackSlot(@Nullable Inventory playerInv, Slot slot) {
        return playerInv != null && slot.container == playerInv;
    }

    /**
     * 进度键：同菜单里容器槽与背包槽的 {@code getContainerSlot()} 可能同号
     * （箱子 0..26 vs Inventory 0..35）⇒ 前缀区分，防同帧互串。
     */
    static String keyFor(boolean backpack, int keySlot) {
        return (backpack ? "P" : "C") + keySlot;
    }

    static RuntimeSegments segmentFor(boolean backpack, int keySlot) {
        return backpack ? LivingItemClientCache.getPlayer(keySlot)
                        : LivingItemClientCache.get(keySlot);
    }
}
