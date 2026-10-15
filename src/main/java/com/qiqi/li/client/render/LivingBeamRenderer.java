package com.qiqi.li.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 活信标光束渲染（Phase 1，纯客户端）—— 设计稿 {@code docs/buffer/living-beacon-design.md}
 * §2.1 / §6 第一段。
 *
 * <p><b>范围</b>：只画【本机玩家】宿主 —— 背包 / 主手 / 副手里有活信标 ⇒ 从眼睛沿视线
 * 射出一条信标光束，被不透明方块截断（本地 clip）。不开箱子、不接 buff、无同步
 * （联机可见属 Phase 2）。⚠️ Phase 1 <b>无条件亮</b>：底座金字塔等级判定属 Phase 2，
 * 接入前任何活信标都发光。</p>
 *
 * <h3>复用的原版函数</h3>
 * {@link BeaconRenderer#renderBeaconBeam} 是 public static，直接调（光束纹理 / 流动闪烁
 * 全部白拿）。⚠️ 它内部 {@code translate(0.5, 0, 0.5)}（给方块坐标补中心用）⇒ 调用前
 * 必须做 −0.5 XZ 预补偿，否则光束轴偏离起点（设计稿 §2.1 已知坑）。
 *
 * <h3>方向对齐</h3>
 * 与 {@link LivingToolModelRenderer#drawModel} 同款公式：
 * {@code yaw = atan2(dir.x, dir.z)} 绕 Y + {@code pitch = atan2(h, dir.y)} 绕 X，
 * 把局部 +Y 对齐到视线方向。
 */
public final class LivingBeamRenderer {

    /** 无遮挡时光束长度上限（格）—— U4 定值 256（原版方块是 1024，但那是方块）。 */
    public static final int MAX_BEAM_LENGTH = 256;

    /** 光束颜色 —— U3 未定，Phase 1 用原版信标白。 */
    private static final int BEAM_COLOR = 0xFFFFFF;

    /** 光束半径 / 光晕半径 —— 与原版信标一致（BeaconRenderer 内部同款 0.2F / 0.25F）。 */
    private static final float BEAM_RADIUS = 0.2F;
    private static final float GLOW_RADIUS = 0.25F;

    private LivingBeamRenderer() {
    }

    /**
     * 渲染入口 —— 由 {@code LivingItemClient} 转发 {@link RenderLevelStageEvent}。
     *
     * <p>挂在 {@link RenderLevelStageEvent.Stage#AFTER_ENTITIES}：此时地形与实体的深度
     * 已写入，光束会被正确遮挡（与本地 clip 形成双保险）。</p>
     */
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }

        if (findLivingBeacon(mc.player).isEmpty()) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 start = mc.player.getEyePosition(partialTick);
        Vec3 look = mc.player.getViewVector(partialTick);
        Vec3 cameraPos = event.getCamera().getPosition();

        // 本地 clip 求截断点 —— 起点在空气中，clip 天然正确（同 LivingToolRayRenderer.renderRay）
        Vec3 maxEnd = start.add(look.scale(MAX_BEAM_LENGTH));
        BlockHitResult hit = level.clip(new ClipContext(
            start, maxEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        Vec3 end = hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : maxEnd;
        int height = (int) Math.min(Math.round(end.distanceTo(start)), MAX_BEAM_LENGTH);
        if (height <= 0) {
            return;
        }

        // yaw / pitch：把局部 +Y 对齐到视线（LivingToolModelRenderer.drawModel 同款）
        float yaw = (float) Mth.atan2(look.x, look.z);
        double horizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        float pitch = (float) Math.atan2(horizontal, look.y);

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        // 事件的 PoseStack 已是相机相对坐标（同 LivingToolRayRenderer 的 translate 口径）
        poseStack.translate(start.x - cameraPos.x, start.y - cameraPos.y, start.z - cameraPos.z);
        poseStack.mulPose(Axis.YP.rotation(yaw));
        poseStack.mulPose(Axis.XP.rotation(pitch));
        // ⚠️ 预补偿 renderBeaconBeam 内部的 translate(0.5, 0, 0.5)（设计稿 §2.1 已知坑）
        poseStack.translate(-0.5F, 0.0F, -0.5F);

        BeaconRenderer.renderBeaconBeam(
            poseStack, buffers, BeaconRenderer.BEAM_LOCATION, partialTick, 1.0F,
            level.getGameTime(), 0, height, BEAM_COLOR, BEAM_RADIUS, GLOW_RADIUS);

        poseStack.popPose();

        buffers.endBatch(RenderType.beaconBeam(BeaconRenderer.BEAM_LOCATION, false));
    }

    /**
     * 在玩家背包（含快捷栏 / 副手）里找第一个活信标。
     *
     * <p>{@code Inventory#getContainerSize()} 已含快捷栏与副手（41 格），遍历一遍即可
     * （同 {@link LivingToolRayRenderer} 的玩家宿主遍历）。多摞信标只画一条 ——
     * 同宿主同视线，几何上重合。</p>
     */
    private static ItemStack findLivingBeacon(Player player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(Items.BEACON) && LivingItemManager.isLivingItem(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
