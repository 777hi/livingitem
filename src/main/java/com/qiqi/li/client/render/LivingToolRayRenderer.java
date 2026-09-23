package com.qiqi.li.client.render;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ItemEntityContainerContext;
import com.qiqi.li.living.domain.tools.LivingToolHostClientCache;
import com.qiqi.li.living.domain.tools.LivingToolMemory;
import com.qiqi.li.network.LivingToolHostPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.Mth;
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
 * <h3>为什么画「光带」而不是「线」</h3>
 * 候选是 {@code RenderType.lines()} + {@code LineStateShard} 调线宽，但
 * <b>OpenGL core profile 下很多驱动只保证 1.0 像素线宽</b>，调了也可能没效果。
 * 改为画一个<b>垂直于视线的四边形光带</b>（{@link RenderType#debugQuads()}：纯色、无纹理、
 * 支持透明、双面可见）：
 * <ul>
 *   <li>宽度完全由我们控制，<b>跨驱动一致</b></li>
 *   <li>半宽随相机距离线性增长 ⇒ <b>屏幕上的粗细大致恒定</b>（近处不爆粗、远处不消失）</li>
 * </ul>
 *
 * <h3>关键设计</h3>
 * <ul>
 *   <li><b>显示时机</b>（{@code L20}）：<b>手持</b>的活工具<b>始终</b>显示射线；
 *       其余宿主（背包非手持槽 / 掉落物 / 容器）只在 <b>F3+B</b>（原版"显示实体碰撞箱"）时显示。
 *       开关读 {@code shouldRenderHitBoxes()}，纯客户端判断、无需同步。</li>
 *   <li><b>客户端不做命中判定</b>（{@code L48}）：命中判据有「宿主黑名单 / 流体 / 形状求交」三层，
 *       在客户端复刻<b>必然走偏</b>（{@code L47} 已验证）。容器形态的"打没打中"由服务端同步一个
 *       <b>布尔</b>；玩家 / 掉落物形态起点在空气中，本地 {@code clip} 天然正确。</li>
 *   <li><b>终点就是记忆本身</b>：{@code origin → origin + offset}，<b>恒定</b>。
 *       ⚠️ 曾把容器形态的终点画到"目标方块中心"，结果射线<b>跟着目标跳</b>（挖完一格跳下一格），
 *       背离 {@code L19} 初衷 —— <b>一条会跳的线根本看不出偏没偏</b>。</li>
 *   <li><b>命中与否用透明度区分</b>：命中 → 不透明；落空 → 半透明。
 *       ⚠️ 对玩家形态而言"落空"是常态（挪一步眼睛位置就变），故别调太淡。</li>
 * </ul>
 *
 * <h3>宿主覆盖范围</h3>
 * <table>
 *   <tr><th>宿主</th><th>射线起点</th><th>状态</th></tr>
 *   <tr><td>玩家（背包 / 主手 / 副手）</td><td>眼睛（{@code L3=a}）</td><td>✅</td></tr>
 *   <tr><td>掉落物</td><td><b>碰撞箱中心</b>（{@code L14=d}）</td><td>✅</td></tr>
 *   <tr><td>方块容器</td><td>容器方块中心（{@code L14=a}）</td><td>✅ 由 {@code K2} 的 S2C 包提供 ——
 *       不开 GUI 时客户端拿不到箱子内容</td></tr>
 * </table>
 */
public final class LivingToolRayRenderer {

    /** 渲染距离上限（格）。超出就不画，避免远处的箱子刷屏。 */
    private static final double MAX_DISTANCE = 32.0;

    /**
     * 光带半宽系数：<b>半宽 ≈ 相机到线段中点的距离 × 本系数</b>。
     *
     * <p>这样屏幕上的粗细大致恒定：屏幕像素宽 ≈ 半宽 × 2 × (屏高 / (2·tan(FOV/2)·距离))。
     * 取 0.0025 时，1080p / FOV 70 下约为 <b>4 像素</b>。</p>
     */
    private static final double RIBBON_WIDTH_FACTOR = 0.0025;

    /** 半宽下限（格）—— 贴脸时不至于爆粗，同时保证极近距离也看得见。 */
    private static final double RIBBON_MIN_HALF_WIDTH = 0.015;

    /** 半宽上限（格）—— 极远处不至于糊成一片。 */
    private static final double RIBBON_MAX_HALF_WIDTH = 0.15;

    /** 挖掘记忆（左键）—— 橙红。 */
    private static final float DIG_R = 1.00F;
    private static final float DIG_G = 0.40F;
    private static final float DIG_B = 0.30F;

    /** 交互记忆（右键）—— 青蓝。 */
    private static final float USE_R = 0.35F;
    private static final float USE_G = 0.85F;
    private static final float USE_B = 1.00F;

    /** 攻击记忆（活武器）—— 品红（与挖掘的橙红、交互的青蓝区分开）。 */
    private static final float ATTACK_R = 0.95F;
    private static final float ATTACK_G = 0.35F;
    private static final float ATTACK_B = 0.95F;

    /** 命中方块时的不透明度（实心）。 */
    private static final float ALPHA_HIT = 1.0F;

    /**
     * 整条线都没打到方块时的不透明度 —— 比命中略淡，用来提示"这条线落空了"。
     *
     * <p>⚠️ 别调得太低：玩家录完记忆后只要挪动一步，眼睛位置就变了，射线从新位置射出、
     * 终点随之偏移，<b>本来命中的记忆也会变成落空</b>。所以"落空"是常态而非异常，
     * 仍须清晰可辨（初版用了 0.25，实测太淡看不清）。</p>
     */
    private static final float ALPHA_MISS = 0.7F;

    private LivingToolRayRenderer() {
    }

    /**
     * 渲染入口 —— 由 {@code LivingItemClient} 转发 {@link RenderLevelStageEvent}。
     *
     * <p>挂在 {@link RenderLevelStageEvent.Stage#AFTER_ENTITIES}：此时地形与实体的深度已写入，
     * 光带会被箱子/地形<b>正确遮挡</b>。</p>
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

        // L20 修订：F3+B 只作为【非手持】的开关。
        // 手持的活工具始终显示 —— 玩家正在拿着它，最需要确认"我记下的方向对不对"。
        boolean debugHitBoxes = mc.getEntityRenderDispatcher().shouldRenderHitBoxes();

        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();
        Frustum frustum = event.getFrustum();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer ribbon = buffers.getBuffer(RenderType.debugQuads());

        boolean drew = renderPlayerHost(mc, poseStack, ribbon, level, cameraPos, frustum,
            partialTick, debugHitBoxes);

        if (debugHitBoxes) {
            drew |= renderItemEntityHosts(poseStack, ribbon, level, cameraPos, frustum);
            drew |= renderContainerHosts(poseStack, ribbon, level, cameraPos, frustum);
        }

        if (drew) {
            // 必须显式 flush，否则本帧不保证会画出来
            buffers.endBatch(RenderType.debugQuads());
        }
    }

    /**
     * 玩家形态：主手 / 副手 / 背包 —— 射线起点统一为<b>眼睛</b>（{@code L3=a}，与录制端一致）。
     *
     * <p>{@code Inventory} 的 {@code getContainerSize()} 已含快捷栏与副手（41 格），
     * 遍历一遍即可。</p>
     *
     * <p><b>手持 vs 其余</b>（{@code L20} 修订）：主手 / 副手上的活工具<b>始终</b>显示；
     * 其余槽位只在 F3+B 时显示。</p>
     */
    private static boolean renderPlayerHost(Minecraft mc, PoseStack poseStack, VertexConsumer ribbon,
                                            ClientLevel level, Vec3 cameraPos, Frustum frustum,
                                            float partialTick, boolean debugHitBoxes) {
        Vec3 eye = mc.player.getEyePosition(partialTick);
        Inventory inventory = mc.player.getInventory();

        // 用【引用相等】判定手持：Player#getMainHandItem() 最终就是 Inventory#getItem(selected)，
        // 拿到的是同一个 ItemStack 对象 —— 比反查槽位索引更可靠、也省掉访问 selected 的麻烦。
        ItemStack mainHand = mc.player.getMainHandItem();
        ItemStack offHand = mc.player.getOffhandItem();

        boolean drew = false;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            boolean held = stack == mainHand || stack == offHand;
            if (!held && !debugHitBoxes) {
                continue;
            }
            drew |= renderStack(poseStack, ribbon, level, cameraPos, frustum, eye, stack);
        }
        return drew;
    }

    /**
     * 掉落物形态：起点 = <b>碰撞箱中心</b>（{@code L14=d}）。
     *
     * <p>⚠️ 与服务端回放<b>共用</b> {@link ItemEntityContainerContext#rayOrigin} ——
     * 直接写 {@code entity.position()} 会拿到碰撞箱底部（贴着脚下方块），
     * 画出来的线会和实际挖的地方不一致。</p>
     */
    private static boolean renderItemEntityHosts(PoseStack poseStack, VertexConsumer ribbon,
                                                 ClientLevel level, Vec3 cameraPos, Frustum frustum) {
        boolean drew = false;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof ItemEntity itemEntity) {
                drew |= renderStack(poseStack, ribbon, level, cameraPos, frustum,
                    ItemEntityContainerContext.rayOrigin(itemEntity), itemEntity.getItem());
            }
        }
        return drew;
    }

    /**
     * 方块容器形态：起点 = <b>容器方块中心</b>（{@code L14=a}）。
     *
     * <p>数据源是 {@link LivingToolHostClientCache}（由 {@code K2} 的 S2C 包填充）——
     * <b>不开 GUI 时客户端拿不到箱子内容</b>，只能靠服务端同步。</p>
     */
    private static boolean renderContainerHosts(PoseStack poseStack, VertexConsumer ribbon,
                                                ClientLevel level, Vec3 cameraPos, Frustum frustum) {
        boolean drew = false;
        List<LivingToolHostPacket.Entry> hosts =
            LivingToolHostClientCache.get(level.dimension().location());
        for (LivingToolHostPacket.Entry entry : hosts) {
            Vec3 origin = Vec3.atCenterOf(entry.pos());
            for (LivingToolHostPacket.ToolRay tool : entry.tools()) {
                drew |= renderStackFromTarget(poseStack, ribbon, cameraPos, frustum, origin, tool);
            }
        }
        return drew;
    }

    /**
     * 画一个物品堆叠的全部记忆光带（挖掘 + 交互各一条）。
     *
     * <p>用「活物品 + 有记忆」当判据，而不是查 {@code ItemAbility} ——
     * 只有活工具会被录制记忆，所以这等价且不需要跨包依赖。</p>
     *
     * @return 是否真的画了东西
     */
    private static boolean renderStack(PoseStack poseStack, VertexConsumer ribbon, ClientLevel level,
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
            drew |= renderRay(poseStack, ribbon, level, cameraPos, frustum, origin, memory.dig(),
                DIG_R, DIG_G, DIG_B);
        }
        if (memory.use() != null) {
            drew |= renderRay(poseStack, ribbon, level, cameraPos, frustum, origin, memory.use(),
                USE_R, USE_G, USE_B);
        }
        // 攻击记忆（活武器）—— 目标从方块换成生物，故不参与 clip，只画记忆射线本身
        if (memory.attack() != null) {
            drew |= renderRay(poseStack, ribbon, level, cameraPos, frustum, origin,
                new LivingToolMemory.RayMemory(memory.attack().offset(), null),
                ATTACK_R, ATTACK_G, ATTACK_B);
        }
        return drew;
    }

    /**
     * 容器形态：只画<b>记忆射线本身</b>，客户端<b>不做任何几何计算</b>（{@code L48}）。
     *
     * <p>与 {@link #renderStack} 的区别：那里本地 {@code clip} 求命中点（起点在空气中，
     * {@code clip} 天然正确）；这里起点埋在方块里不能 {@code clip}，但<b>也不需要</b> ——
     * 终点就是记忆终点 {@code origin + offset}，<b>恒定</b>。</p>
     *
     * <p>⭐ <b>刻意不截断到目标方块</b>：曾把终点画到目标方块中心，结果射线<b>跟着目标跳</b>
     * （挖完一格跳下一格），背离 {@code L19}「把记忆画出来、让玩家看出偏没偏」的初衷。
     * 射进方块的那一段由<b>深度测试</b>免费挡掉，视觉上照样"停在表面"。</p>
     *
     * <p>命中与否只影响透明度，由服务端的布尔决定 —— 客户端不推演任何东西。</p>
     */
    private static boolean renderStackFromTarget(PoseStack poseStack, VertexConsumer ribbon,
                                                 Vec3 cameraPos, Frustum frustum, Vec3 origin,
                                                 LivingToolHostPacket.ToolRay tool) {
        ItemStack stack = tool.stack();
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

        // ⭐ 只画【记忆射线本身】：origin → origin + offset —— 恒定，不随挖掘目标变化
        boolean drew = false;
        if (memory.dig() != null) {
            drew |= drawRibbon(poseStack, ribbon, cameraPos, frustum, origin,
                memory.dig().endpointFrom(origin), tool.digLanded(), DIG_R, DIG_G, DIG_B);
        }
        if (memory.use() != null) {
            drew |= drawRibbon(poseStack, ribbon, cameraPos, frustum, origin,
                memory.use().endpointFrom(origin), tool.useLanded(), USE_R, USE_G, USE_B);
        }
        // 攻击记忆：容器形态没有同步"有没有生物命中"这个布尔 ⇒ 恒按命中画（实心）
        if (memory.attack() != null) {
            drew |= drawRibbon(poseStack, ribbon, cameraPos, frustum, origin,
                memory.attack().endpointFrom(origin), true, ATTACK_R, ATTACK_G, ATTACK_B);
        }
        return drew;
    }

    /**
     * 玩家 / 掉落物形态：起点在空气中 → 本地 {@code clip} 一次求命中点。
     *
     * <p><b>命中 → 画到命中点（不透明）</b>；<b>落空 → 画到终点（半透明）</b> ——
     * 落空变暗正是"这条线擦着缝过去了"的信号。</p>
     *
     * <p>⚠️ 只适用于<b>起点在空气中</b>的形态。容器形态起点埋在方块里，
     * {@code clip} 会立刻自命中 ⇒ 必须走 {@link #renderStackFromTarget}（{@code L47}）。</p>
     */
    private static boolean renderRay(PoseStack poseStack, VertexConsumer ribbon, ClientLevel level,
                                     Vec3 cameraPos, Frustum frustum, Vec3 origin,
                                     LivingToolMemory.RayMemory ray,
                                     float red, float green, float blue) {
        Vec3 end = ray.endpointFrom(origin);
        BlockHitResult hit = level.clip(new ClipContext(
            origin, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        boolean landed = hit.getType() == HitResult.Type.BLOCK;
        return drawRibbon(poseStack, ribbon, cameraPos, frustum, origin,
            landed ? hit.getLocation() : end, landed, red, green, blue);
    }

    /** 画一条光带（{@code start → tip}）：命中实心、落空半透明。 */
    private static boolean drawRibbon(PoseStack poseStack, VertexConsumer ribbon,
                                      Vec3 cameraPos, Frustum frustum, Vec3 start, Vec3 tip,
                                      boolean landed, float red, float green, float blue) {
        // 视锥裁剪（用线段包围盒近似）
        if (!frustum.isVisible(new AABB(start, tip))) {
            return false;
        }

        Vec3 delta = tip.subtract(start);
        double length = delta.length();
        if (length < 1.0E-6) {
            return false;
        }
        Vec3 dir = delta.scale(1.0 / length);

        // 半宽随距离增长 ⇒ 屏幕粗细大致恒定
        Vec3 mid = start.lerp(tip, 0.5);
        double halfWidth = Mth.clamp(mid.distanceTo(cameraPos) * RIBBON_WIDTH_FACTOR,
            RIBBON_MIN_HALF_WIDTH, RIBBON_MAX_HALF_WIDTH);

        // 光带的横向轴 = 线方向 × 视线方向 ⇒ 光带平面始终"侧对"玩家，任何角度看都是条粗线
        Vec3 toCamera = cameraPos.subtract(mid);
        Vec3 side = dir.cross(toCamera);
        if (side.lengthSqr() < 1.0E-6) {
            // 退化：视线与线几乎平行（此时线在屏幕上本就是个点）
            side = dir.cross(new Vec3(0.0, 1.0, 0.0));
            if (side.lengthSqr() < 1.0E-6) {
                side = dir.cross(new Vec3(1.0, 0.0, 0.0));
            }
        }
        side = side.normalize().scale(halfWidth);

        float alpha = landed ? ALPHA_HIT : ALPHA_MISS;

        poseStack.pushPose();
        // 事件给的 PoseStack 已是【相机相对】坐标，故只需平移到线的起点
        poseStack.translate(start.x - cameraPos.x, start.y - cameraPos.y, start.z - cameraPos.z);
        PoseStack.Pose pose = poseStack.last();

        // 四个角（相对起点）：起点两侧 → 终点两侧
        ribbon.addVertex(pose, (float) -side.x, (float) -side.y, (float) -side.z)
            .setColor(red, green, blue, alpha);
        ribbon.addVertex(pose, (float) side.x, (float) side.y, (float) side.z)
            .setColor(red, green, blue, alpha);
        ribbon.addVertex(pose, (float) (delta.x + side.x), (float) (delta.y + side.y), (float) (delta.z + side.z))
            .setColor(red, green, blue, alpha);
        ribbon.addVertex(pose, (float) (delta.x - side.x), (float) (delta.y - side.y), (float) (delta.z - side.z))
            .setColor(red, green, blue, alpha);

        poseStack.popPose();

        return true;
    }

}
