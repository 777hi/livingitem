package com.qiqi.li.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.tools.LivingToolMemory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 活工具<b>记忆射线可视化</b>（{@code L19} / {@code L20}）—— 把"活工具打算挖/交互哪里"画给玩家看。
 *
 * <h3>为什么需要它</h3>
 * 记忆存的是一条「纯净射线」（{@code L15}）。若这条线恰巧擦着两格方块之间的缝隙过去，
 * 回放时会 {@code MISS}。我们<b>不做浮点余量兜底</b>，而是把它<b>画出来</b> ——
 * 玩家一眼看出偏了，重录一条即可（设计原则：给玩家「信息与手段」，而不是在代码里兜底）。
 *
 * <h3>关键设计</h3>
 * <ul>
 *   <li><b>纯客户端</b>：记忆组件已 {@code networkSynchronized}，客户端<b>本地重算射线</b>即可，
 *       服务端不需要同步"命中了哪个方块"。</li>
 *   <li><b>只在 F3+B 时显示</b>（{@code L20=f}）：读 {@code shouldRenderHitBoxes()}，
 *       与原版调试碰撞箱同一个开关，纯客户端判断、无需同步。</li>
 *   <li><b>正在挖的时候不用画</b>：{@code ServerLevel#destroyBlockProgress()} 会让客户端
 *       自动显示破坏裂纹（原版能力，零渲染代码）。本渲染器只管"待机 / 未开始挖"时的目标线。</li>
 *   <li><b>不节流、不广播</b>：每帧现算。命中的方块由本地 {@code clip} 近似
 *       （服务端用逐格扫描 + 黑名单，这里只求"给你看一眼"，不必逐位一致）。</li>
 * </ul>
 *
 * <h3>宿主覆盖范围（v1）</h3>
 * <table>
 *   <tr><th>宿主</th><th>射线起点</th><th>能否渲染</th></tr>
 *   <tr><td>玩家（背包 / 主手 / 副手）</td><td>眼睛（{@code L3=a}）</td><td>✅</td></tr>
 *   <tr><td>掉落物</td><td>实体位置（{@code L14=d}）</td><td>✅</td></tr>
 *   <tr><td>方块容器</td><td>容器方块中心（{@code L14=a}）</td><td>❌ <b>待 {@code K2} 的 S2C 包</b> ——
 *       不开 GUI 时客户端拿不到箱子内容</td></tr>
 * </table>
 */
public final class LivingToolRayRenderer {

    /** 渲染距离上限（格）。超出就不画，避免远处的箱子刷屏。 */
    private static final double MAX_DISTANCE = 32.0;

    /** 挖掘记忆（左键）—— 橙红。 */
    private static final float DIG_R = 1.00F;
    private static final float DIG_G = 0.40F;
    private static final float DIG_B = 0.30F;

    /** 交互记忆（右键）—— 青蓝。 */
    private static final float USE_R = 0.35F;
    private static final float USE_G = 0.85F;
    private static final float USE_B = 1.00F;

    /** 命中方块时的不透明度。 */
    private static final float ALPHA_HIT = 1.0F;

    /** 整条线都没打到方块时的不透明度（变暗，提示"这条线落空了"）。 */
    private static final float ALPHA_MISS = 0.25F;

    private LivingToolRayRenderer() {
    }

    /**
     * 渲染入口 —— 由 {@code LivingItemClient} 转发 {@link RenderLevelStageEvent}。
     *
     * <p>挂在 {@link RenderLevelStageEvent.Stage#AFTER_ENTITIES}：此时地形与实体的深度已写入，
     * 射线会被箱子/地形<b>正确遮挡</b>。</p>
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
        // L20=f：复用原版 F3+B 开关（与调试碰撞箱同时显示），纯客户端、无需同步
        if (!mc.getEntityRenderDispatcher().shouldRenderHitBoxes()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();
        Frustum frustum = event.getFrustum();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        boolean drew = false;
        drew |= renderPlayerHost(mc, poseStack, lines, level, cameraPos, frustum, partialTick);
        drew |= renderItemEntityHosts(poseStack, lines, level, cameraPos, frustum);

        if (drew) {
            // 必须显式 flush，否则本帧不保证会画出来（原版调试碰撞箱同样如此）
            buffers.endBatch(RenderType.lines());
        }
    }

    /**
     * 玩家形态：主手 / 副手 / 背包 —— 射线起点统一为<b>眼睛</b>（{@code L3=a}，与录制端一致）。
     *
     * <p>{@code Inventory} 的 {@code getContainerSize()} 已含快捷栏与副手（41 格），
     * 遍历一遍就够了，无需单独处理手持。</p>
     */
    private static boolean renderPlayerHost(Minecraft mc, PoseStack poseStack, VertexConsumer lines,
                                            ClientLevel level, Vec3 cameraPos, Frustum frustum, float partialTick) {
        Vec3 eye = mc.player.getEyePosition(partialTick);
        Inventory inventory = mc.player.getInventory();

        boolean drew = false;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            drew |= renderStack(poseStack, lines, level, cameraPos, frustum, eye, inventory.getItem(slot));
        }
        return drew;
    }

    /** 掉落物形态：起点 = 实体位置（{@code L14=d}）。 */
    private static boolean renderItemEntityHosts(PoseStack poseStack, VertexConsumer lines,
                                                 ClientLevel level, Vec3 cameraPos, Frustum frustum) {
        boolean drew = false;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof ItemEntity itemEntity) {
                drew |= renderStack(poseStack, lines, level, cameraPos, frustum,
                    itemEntity.position(), itemEntity.getItem());
            }
        }
        return drew;
    }

    /**
     * 画一个物品堆叠的全部记忆射线（挖掘 + 交互各一条）。
     *
     * <p>用「活物品 + 有记忆」当判据，而不是查 {@code ItemAbility} ——
     * 只有活工具会被录制记忆，所以这等价且不需要跨包依赖。</p>
     *
     * @return 是否真的画了东西
     */
    private static boolean renderStack(PoseStack poseStack, VertexConsumer lines, ClientLevel level,
                                       Vec3 cameraPos, Frustum frustum, Vec3 origin, ItemStack stack) {
        if (stack.isEmpty() || !LivingItemManager.isLivingItem(stack)) {
            return false;
        }
        LivingToolMemory memory = LivingItemManager.getToolMemory(stack);
        if (memory.isEmpty()) {
            return false;
        }
        if (origin.distanceToSqr(cameraPos) > MAX_DISTANCE * MAX_DISTANCE) {
            return false;   // 距离裁剪
        }

        boolean drew = false;
        if (memory.dig() != null) {
            drew |= renderRay(poseStack, lines, level, cameraPos, frustum, origin, memory.dig(),
                DIG_R, DIG_G, DIG_B);
        }
        if (memory.use() != null) {
            drew |= renderRay(poseStack, lines, level, cameraPos, frustum, origin, memory.use(),
                USE_R, USE_G, USE_B);
        }
        return drew;
    }

    /**
     * 画一条记忆射线。
     *
     * <p>本地 {@code clip} 一次求命中点：<b>命中 → 画到命中点（亮）</b>；
     * <b>落空 → 画到终点（暗）</b> —— 落空变暗正是"这条线擦着缝过去了"的信号。</p>
     */
    private static boolean renderRay(PoseStack poseStack, VertexConsumer lines, ClientLevel level,
                                     Vec3 cameraPos, Frustum frustum, Vec3 origin,
                                     LivingToolMemory.RayMemory ray,
                                     float red, float green, float blue) {
        Vec3 end = ray.endpointFrom(origin);
        BlockHitResult hit = level.clip(new ClipContext(
            origin, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        boolean landed = hit.getType() == HitResult.Type.BLOCK;
        Vec3 tip = landed ? hit.getLocation() : end;

        // 视锥裁剪（用线段包围盒近似）
        if (!frustum.isVisible(new AABB(origin, tip))) {
            return false;
        }

        Vec3 delta = tip.subtract(origin);
        double length = delta.length();
        float nx = 0.0F;
        float ny = 0.0F;
        float nz = 1.0F;
        if (length > 1.0E-6) {
            // 原版 renderLineBox 同款：用边方向当法线（线宽计算依赖它）
            nx = (float) (delta.x / length);
            ny = (float) (delta.y / length);
            nz = (float) (delta.z / length);
        }

        float alpha = landed ? ALPHA_HIT : ALPHA_MISS;

        poseStack.pushPose();
        // 事件给的 PoseStack 已是【相机相对】坐标，故只需平移到线的起点
        poseStack.translate(origin.x - cameraPos.x, origin.y - cameraPos.y, origin.z - cameraPos.z);
        PoseStack.Pose pose = poseStack.last();
        lines.addVertex(pose, 0.0F, 0.0F, 0.0F)
            .setColor(red, green, blue, alpha)
            .setNormal(pose, nx, ny, nz);
        lines.addVertex(pose, (float) delta.x, (float) delta.y, (float) delta.z)
            .setColor(red, green, blue, alpha)
            .setNormal(pose, nx, ny, nz);
        poseStack.popPose();

        return true;
    }
}
