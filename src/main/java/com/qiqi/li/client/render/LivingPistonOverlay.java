package com.qiqi.li.client.render;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
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
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;

/**
 * 活活塞三维伸缩叠加层——本体 + 头杆沿 GUI 方向伸到邻槽并插值缩回。
 *
 * <h3>数据源</h3>
 * 朝向读物品组件 {@link LivingPistonData}（随同步包下发）；伸出态读
 * {@link LivingItemClientCache} 的 runtime 片段（{@link PistonSegment}）。
 *
 * <h3>位姿</h3>
 * iso 采原版 gui block display 旋转常数（rotX 30°、rotY 225°），push 轴 = 模型 +X；
 * 四向由 {@link PistonRenderState#rotationDegrees} 抵消 +X 的 iso 投影偏角。
 * Y/Z 保持 0.625 × 槽距，X 单独校正，使全伸头板中心恰好跨越一个槽距。
 *
 * <h3>已知原型风险（均留实机验证，不在本批修）</h3>
 * 第三方滚动容器的裁剪状态不可知；创造包装槽的 {@code getContainerSlot()} 可能返回菜单号
 * （{@code ClientSlotResolve} 同族已知边界）；HUD / 快捷栏不绘制（前景事件只覆盖容器界面）。
 */
public final class LivingPistonOverlay {

    /** 原物品 z=150、数量文字 z=200；活塞叠加层固定夹在二者之间。 */
    private static final float OVERLAY_Z = 175f;

    /** 原版 GUI iso 的 Y/Z 尺寸：0.625 个槽距。 */
    private static final float SCALE_YZ = LivingIconRenderHelper.SLOT_PITCH * 0.625f;

    /** iso 后 +X 屏面投影长度 sqrt(10)/4；含头板局部 3/8 后全伸中心跨 18px。 */
    private static final float SCALE_X = (float) (LivingIconRenderHelper.SLOT_PITCH
        / (Math.sqrt(10.0) / 4.0 * (1.0 + 3.0 / 8.0)));

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

            float progress = PistonRenderState.advance(key, state.extended(), direction, Util.getMillis());
            if (!state.extended() && progress <= 0f) continue;   // 收回完成 ⇒ 原版图标即完整外观

            draw(guiGraphics, slot, leftPos, topPos, direction, progress, isSticky(slot.getItem()));
        }
        PistonRenderState.prune(active);
    }

    private static void draw(GuiGraphics guiGraphics, Slot slot, int leftPos, int topPos,
                             Pos2D direction, float progress, boolean sticky) {
        BlockState base = (sticky ? Blocks.STICKY_PISTON : Blocks.PISTON).defaultBlockState()
            .setValue(PistonBaseBlock.FACING, Direction.EAST)
            .setValue(PistonBaseBlock.EXTENDED, true);
        BlockState head = Blocks.PISTON_HEAD.defaultBlockState()
            .setValue(DirectionalBlock.FACING, Direction.EAST)
            .setValue(PistonHeadBlock.TYPE, sticky ? PistonType.STICKY : PistonType.DEFAULT)
            .setValue(PistonHeadBlock.SHORT, PistonRenderState.headShort(progress));

        float cx = leftPos + slot.x + 8f;
        float cy = topPos + slot.y + 8f;

        PoseStack pose = guiGraphics.pose();
        var buffer = Minecraft.getInstance().renderBuffers().bufferSource();
        pose.pushPose();
        try {
            pose.translate(cx, cy, OVERLAY_Z);
            pose.mulPose(Axis.ZP.rotationDegrees(PistonRenderState.rotationDegrees(direction)));
            pose.mulPose(Axis.XP.rotationDegrees(30f));
            pose.mulPose(Axis.YP.rotationDegrees(225f));
            pose.scale(SCALE_X, SCALE_YZ, SCALE_YZ);
            pose.translate(-0.5f, -0.5f, -0.5f);   // 居中 [0,1] 立方

            LivingIconRenderHelper.renderBlockModel(base, pose, buffer);
            pose.translate(PistonRenderState.headOffsetUnits(progress), 0f, 0f);
            LivingIconRenderHelper.renderBlockModel(head, pose, buffer);
            buffer.endBatch();
        } finally {
            pose.popPose();
        }
    }

    private static boolean isSticky(ItemStack stack) {
        return stack.is(Items.STICKY_PISTON);
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
