package com.qiqi.li.client.render;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.beacon.BeaconBaseBlocks;
import com.qiqi.li.living.domain.beacon.BeaconHostClientCache;
import com.qiqi.li.living.domain.beacon.BeaconHostPacket;
import com.qiqi.li.living.domain.beacon.BeaconPlayerHostClientCache;
import com.qiqi.li.living.domain.beacon.BeaconPlayerHostPacket;
import com.qiqi.li.living.domain.beacon.BeaconPyramid;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.transfer.SlotResolver;

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
 * 活信标光束渲染（Phase 1/2）—— 设计稿 {@code docs/buffer/living-beacon-design.md}
 * §2.1 / §2.4 / §6。
 *
 * <p><b>两种宿主</b>（规则 1：仅玩家 / 箱子）：</p>
 * <ul>
 *   <li><b>玩家宿主</b>：本机背包（含快捷栏 / 副手）里的活信标，从眼睛沿视线射出。
 *       <b>本地判级门控</b>：客户端自己读下方 9 列柱高（本机背包可见）→ {@link BeaconPyramid}
 *       判级 → level ≥ 1 才亮（无条件亮是 Phase 1 临时口径，Phase 2 接入）。</li>
 *   <li><b>箱子宿主</b>：{@link BeaconHostClientCache}（S2C 同步的位置 + 等级），
 *       从箱口顶部垂直向上射出，本地 clip 截断。开盖由服务端 blockEvent 驱动。</li>
 * </ul>
 *
 * <h3>复用的原版函数</h3>
 * {@link BeaconRenderer#renderBeaconBeam} 是 public static，直接调。⚠️ 它内部
 * {@code translate(0.5, 0, 0.5)}（给方块坐标补中心用）⇒ 调用前必须做 −0.5 XZ 预补偿，
 * 否则光束轴偏离起点（设计稿 §2.1 已知坑）。
 *
 * <h3>方向对齐</h3>
 * 玩家宿主与 {@link LivingToolModelRenderer#drawModel} 同款公式：
 * {@code yaw = atan2(dir.x, dir.z)} 绕 Y + {@code pitch = atan2(h, dir.y)} 绕 X，
 * 把局部 +Y 对齐到视线方向；箱子宿主恒为垂直向上（局部 +Y 即向上，无需旋转）。
 */
public final class LivingBeamRenderer {

    /** 无遮挡时光束长度上限（格）—— U4 定值 256。 */
    public static final int MAX_BEAM_LENGTH = 256;

    /** 光束颜色 —— U3 未定，用原版信标白。 */
    private static final int BEAM_COLOR = 0xFFFFFF;

    /** 光束半径 / 光晕半径 —— 与原版信标一致。 */
    private static final float BEAM_RADIUS = 0.2F;
    private static final float GLOW_RADIUS = 0.25F;

    private LivingBeamRenderer() {
    }

    /**
     * 渲染入口 —— 由 {@code LivingItemClient} 转发 {@link RenderLevelStageEvent}。
     *
     * <p>挂在 {@link RenderLevelStageEvent.Stage#AFTER_ENTITIES}：地形与实体的深度已写入，
     * 光束被正确遮挡（与本地 clip 形成双保险）。</p>
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

        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        boolean drew = renderPlayerHost(mc, level, poseStack, buffers, cameraPos, partialTick);
        drew |= renderRemotePlayerHosts(mc, level, poseStack, buffers, cameraPos, partialTick);
        drew |= renderChestHosts(level, poseStack, buffers, cameraPos, partialTick);

        if (drew) {
            // 必须显式 flush，否则本帧不保证会画出来
            buffers.endBatch(RenderType.beaconBeam(BeaconRenderer.BEAM_LOCATION, false));
        }
    }

    /**
     * 玩家宿主：本机背包里第一个<b>已激活</b>（level ≥ 1）的活信标 ⇒ 头顶沿视线光束。
     *
     * <p>多个信标同时存在时只画一条（同起点同方向，几何重合；第一个激活的优先）——
     * 若第一个信标未激活而后面有激活的，会继续找。</p>
     */
    private static boolean renderPlayerHost(Minecraft mc, ClientLevel level, PoseStack poseStack,
                                            MultiBufferSource.BufferSource buffers, Vec3 cameraPos,
                                            float partialTick) {
        Inventory inventory = mc.player.getInventory();
        int size = inventory.getContainerSize();

        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.is(Items.BEACON) || !LivingItemManager.isLivingItem(stack)) {
                continue;
            }
            if (BeaconPyramid.resolveLevel(localColumnHeights(inventory, slot)) < 1) {
                continue;   // 未激活 ⇒ 不亮（Phase 2 门控）
            }
            renderBeamAlong(poseStack, buffers, cameraPos, partialTick, level,
                mc.player.getEyePosition(partialTick), mc.player.getViewVector(partialTick));
            return true;
        }
        return false;
    }

    /**
     * 其它玩家宿主（联机可见 · 设计稿规则 6）：S2C 的 UUID + 等级 ⇒ 查实体取
     * <b>他的</b>眼睛与视线（实体旋转本来就同步给客户端）⇒ 与自己的光束同款几何。
     *
     * <p>排除自己（本机渲染更实时）与等级 0（服务端只发 ≥ 1，双保险）。</p>
     */
    private static boolean renderRemotePlayerHosts(Minecraft mc, ClientLevel level, PoseStack poseStack,
                                                   MultiBufferSource.BufferSource buffers, Vec3 cameraPos,
                                                   float partialTick) {
        List<BeaconPlayerHostPacket.Entry> hosts =
            BeaconPlayerHostClientCache.get(level.dimension().location());
        boolean drew = false;
        for (BeaconPlayerHostPacket.Entry entry : hosts) {
            Player owner = level.getPlayerByUUID(entry.playerId());
            if (owner == null || owner == mc.player || entry.level() < 1) {
                continue;
            }
            drew |= renderBeamAlong(poseStack, buffers, cameraPos, partialTick, level,
                owner.getEyePosition(partialTick), owner.getViewVector(partialTick));
        }
        return drew;
    }

    /** 箱子宿主：S2C 位置集，垂直向上光束（本地 clip 截断）。 */
    private static boolean renderChestHosts(ClientLevel level, PoseStack poseStack,
                                            MultiBufferSource.BufferSource buffers, Vec3 cameraPos,
                                            float partialTick) {
        List<BeaconHostPacket.Entry> hosts =
            BeaconHostClientCache.get(level.dimension().location());
        boolean drew = false;
        for (BeaconHostPacket.Entry entry : hosts) {
            // 起点 = 箱口顶部中心（方块顶面；0.875 的盖面差在视觉上不可见）
            Vec3 start = Vec3.atBottomCenterOf(entry.pos()).add(0.0, 1.0, 0.0);
            drew |= renderBeamAlong(poseStack, buffers, cameraPos, partialTick, level,
                start, new Vec3(0.0, 1.0, 0.0));
        }
        return drew;
    }

    /** 通用光束：起点 + 方向（单位向量），本地 clip 求截断高度后调原版函数。 */
    private static boolean renderBeamAlong(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                           Vec3 cameraPos, float partialTick, ClientLevel level,
                                           Vec3 start, Vec3 look) {
        Vec3 maxEnd = start.add(look.scale(MAX_BEAM_LENGTH));
        BlockHitResult hit = level.clip(new ClipContext(
            start, maxEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        Vec3 end = hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : maxEnd;
        int height = (int) Math.min(Math.round(end.distanceTo(start)), MAX_BEAM_LENGTH);
        if (height <= 0) {
            return false;
        }

        // yaw / pitch：把局部 +Y 对齐到方向（LivingToolModelRenderer.drawModel 同款）
        float yaw = (float) Mth.atan2(look.x, look.z);
        double horizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        float pitch = (float) Math.atan2(horizontal, look.y);

        poseStack.pushPose();
        // 事件的 PoseStack 已是相机相对坐标
        poseStack.translate(start.x - cameraPos.x, start.y - cameraPos.y, start.z - cameraPos.z);
        poseStack.mulPose(Axis.YP.rotation(yaw));
        poseStack.mulPose(Axis.XP.rotation(pitch));
        // ⚠️ 预补偿 renderBeaconBeam 内部的 translate(0.5, 0, 0.5)（设计稿 §2.1 已知坑）
        poseStack.translate(-0.5F, 0.0F, -0.5F);

        BeaconRenderer.renderBeaconBeam(
            poseStack, buffers, BeaconRenderer.BEAM_LOCATION, partialTick, 1.0F,
            level.getGameTime(), 0, height, BEAM_COLOR, BEAM_RADIUS, GLOW_RADIUS);

        poseStack.popPose();
        return true;
    }

    /**
     * 本地（客户端）读下方 9 列柱高 —— 与 {@code BeaconFunction.readColumnHeights} 同口径
     * （9 列网格；背包 hotbar 第 0 行、主区 1~3 行、offhand 第 4 行）。
     */
    static long[] localColumnHeights(Inventory inventory, int slot) {
        long[] heights = new long[BeaconPyramid.COLUMNS];
        int size = inventory.getContainerSize();
        for (int offset = -BeaconPyramid.CENTER; offset <= BeaconPyramid.CENTER; offset++) {
            int target = SlotResolver.resolve(slot, new Pos2D(offset, 1), size, 9);
            if (target >= 0) {
                heights[offset + BeaconPyramid.CENTER] =
                    BeaconBaseBlocks.columnHeight(inventory.getItem(target));
            }
        }
        return heights;
    }
}
