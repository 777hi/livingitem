package com.qiqi.li.client.render;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ItemEntityContainerContext;
import com.qiqi.li.living.domain.tools.LivingToolAction;
import com.qiqi.li.living.domain.tools.LivingToolHostClientCache;
import com.qiqi.li.living.domain.tools.LivingToolMemory;
import com.qiqi.li.living.domain.tools.LivingToolProgress;
import com.qiqi.li.network.LivingToolHostPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 活工具<b>悬浮模型 + 动画</b>（{@code K} 组）—— 让活工具在世界里"活起来"。
 *
 * <h3>模型从哪来</h3>
 * 直接用<b>活工具物品本身的 3D 模型</b>（镐就是镐、斧就是斧），
 * 经 {@code ItemRenderer#renderStatic} + {@link ItemDisplayContext#FIXED} 渲染。
 * 用 {@code FIXED} 而非 {@code GUI} —— 后者是扁平的 2D 图标。
 *
 * <h3>位置：一切挂在射线上</h3>
 * <ul>
 *   <li><b>待机位</b> = {@code origin + dir × d}，其中
 *       {@code d = D_max · L / (L + k)} —— 双曲饱和：
 *       <b>射线越长待机位越远，但增长越来越慢且严格有上界</b>，
 *       于是射线很长时待机位也不会跑到天边。</li>
 *   <li><b>交互位</b> = 记忆射线的<b>命中处</b>（{@code clip} 的那个表面点）。</li>
 * </ul>
 *
 * <h3>动画</h3>
 * <table>
 *   <tr><th>状态</th><th>位置</th><th>动作</th></tr>
 *   <tr><td>待机</td><td>待机位</td><td><b>不动</b>，朝向射线方向</td></tr>
 *   <tr><td>挖掘中</td><td><b>瞬现</b>到交互位</td><td><b>风车式转圈</b>（挖越快转越快）</td></tr>
 *   <tr><td>交互</td><td><b>瞬现</b>到交互位</td><td><b>缩放脉冲</b>，不转圈</td></tr>
 *   <tr><td>干完</td><td>飞回待机位</td><td>缓出，恒定 8 tick ⇒ 越远飞得越快</td></tr>
 * </table>
 *
 * <h3>⭐ 动画是纯表现层，零逻辑耦合</h3>
 * 所有"要不要干活、挖哪一格、多快挖完"<b>都由服务端决定</b>（{@code L48}），
 * 本类只读结果、只负责画。就算动画播错、漏播、慢半拍，<b>核心功能完全不受影响</b>。
 *
 * <h3>为什么转圈绕【局部 X 轴】</h3>
 * T 形（镐）绕自己的柄轴自转 = 对称图形绕对称轴转，<b>从侧面看几乎看不出来</b>。
 * 绕<b>垂直于射线、且水平</b>的轴转（风车式），镐头划出圆，<b>任何视角都一眼看出在转</b>。
 * 由于先把 {@code +Y} 对齐到射线方向，局部 X 轴正好就是那个轴。
 *
 * <h3>覆盖范围</h3>
 * <table>
 *   <tr><th>形态</th><th>是否画</th><th>说明</th></tr>
 *   <tr><td>玩家背包（非手持）</td><td>✅ 画</td><td>—</td></tr>
 *   <tr><td>掉落物</td><td>✅ 画</td><td>⚠️ <b>额外</b>渲染 —— 原版 {@code ItemEntityRenderer} 仍会画一个，
 *       两者视觉重叠（用户 2026-09-20 明确要求）</td></tr>
 *   <tr><td>方块容器</td><td>✅ 画</td><td>—</td></tr>
 *   <tr><td>手持</td><td>❌ 不画</td><td>玩家手里已经拿着了，再飘一个是重复</td></tr>
 * </table>
 */
public final class LivingToolModelRenderer {

    // ══════════════════════════════════════════════════════════════════════════════
    //  ⭐ 手感调参指引 —— 全部手感都收在下面这几个常量里，改完直接生效，无需动别处。
    //
    //  常量                      现值            调大 / 调小会怎样
    //  ──────────────────────────────────────────────────────────────────────────────
    //  MODEL_ROLL_FIX           -90°            模型绕射线方向的滚转角。
    //                                            ⚠️ 换用别的物品模型后朝向不对，只改这一个。
    //  IDLE_MAX_OFFSET          1.5 格          待机位离射线起点的【上限】。
    //                                            调大 = 平时飘得离宿主更远。
    //  IDLE_HALF_SATURATION     3.0 格          走到"上限一半"所需的射线长度。
    //                                            调小 = 很快就贴近上限（近处就飘得远）。
    //  SPIN_PERIOD_MIN          4 tick/圈       转圈【最快】档（挖得很快时）。
    //                                            ⚠️ 别低于 4 —— 60fps 下会走样成倒转/抖动。
    //  SPIN_PERIOD_MAX          20 tick/圈      转圈【最慢】档（挖得很慢时）。
    //  SPIN_PERIOD_DIVISOR      10              挖掘预计 tick ÷ 本值 = 转圈周期。
    //                                            调小 = 整体转得更快（更快贴到最快档）。
    //  RETURN_TICKS             8 tick          干完飞回待机位的耗时。
    //                                            调大 = 更悠哉；调小 = 更利落。
    //  PULSE_TICKS              6 tick          交互脉冲的时长。
    //  PULSE_SCALE              0.15            交互脉冲的力度（+15%）。
    //  MAX_DISTANCE             32 格           ⚠️ 别改！必须与 K2 同步半径、原版裂纹广播半径一致。
    // ══════════════════════════════════════════════════════════════════════════════

    /** 渲染距离上限（格）—— 与射线渲染器 / 同步半径 / 原版裂纹广播半径一致（32）。 */
    private static final double MAX_DISTANCE = 32.0;

    /** 待机位距起点的<b>上限</b>（格）。 */
    private static final double IDLE_MAX_OFFSET = 1.5;

    /** 待机位的半饱和长度（格）：射线长等于此值时，待机距离 = 上限的一半。 */
    private static final double IDLE_HALF_SATURATION = 3.0;

    /** 飞回耗时（tick）。<b>恒定</b> ⇒ 距离越远回得越快（自动满足，无需速度函数）。 */
    private static final int RETURN_TICKS = 8;

    /**
     * 转圈周期下限（tick / 圈）—— 最快。
     *
     * <p>⚠️ 不能低于 4：60fps 下 1 tick 只有 3 帧，1 tick/圈 = 每帧 120°，
     * 远超 Nyquist 极限 ⇒ 会看成<b>倒转或抖动</b>（车轮效应）。4 tick/圈 = 每帧 30°，清晰可辨。</p>
     */
    private static final int SPIN_PERIOD_MIN = 4;

    /** 转圈周期上限（tick / 圈）—— 最慢。 */
    private static final int SPIN_PERIOD_MAX = 20;

    /** 「挖掘预计 tick」→ 转圈周期的除数（digTicks / 本值 = 周期，再 clamp）。 */
    private static final double SPIN_PERIOD_DIVISOR = 10.0;

    /** 缩放脉冲时长（tick）。 */
    private static final int PULSE_TICKS = 6;

    /** 缩放脉冲的最大放大比例（0.15 = 放大到 1.15 倍）。 */
    private static final float PULSE_SCALE = 0.15F;

    /**
     * 模型绕自身长轴（它最终会被对齐到<b>射线方向</b>）的滚转修正角。
     *
     * <p>物品模型默认不是"立正"的 —— 把 {@code +Y} 对齐到射线后模型整体<b>侧着</b>，
     * 需绕射线转 90°；转完又发现<b>头朝下</b>，再补 180°。
     * 合计 {@code 90° + 180° = 270° ≡ -90°}（2026-09-20 用户分两次实测确认）。</p>
     *
     * <p>⚠️ 换用别的物品模型时若朝向不对，只调这一个常量即可。</p>
     */
    private static final float MODEL_ROLL_FIX = (float) (-Math.PI / 2.0);

    /**
     * 一次「工作」的记忆，用于<b>飞回</b>。
     *
     * <p>只需要「最后一次工作在哪、什么时候」，不需要完整状态机 ——
     * 因为「是否还在工作」能从 {@code LIVING_TOOL_PROGRESS} 现成读到。</p>
     */
    private static final Map<String, WorkMemo> WORK_MEMO = new HashMap<>();

    private LivingToolModelRenderer() {
    }

    /**
     * 渲染入口 —— 由 {@code LivingItemClient} 转发 {@link RenderLevelStageEvent}。
     *
     * <p>与射线渲染器同挂 {@code AFTER_ENTITIES}：地形与实体深度已写入，模型会被正确遮挡。</p>
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
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        MultiBufferSource buffers = mc.renderBuffers().bufferSource();
        long now = level.getGameTime();

        Set<String> seen = new HashSet<>();

        // ① 玩家背包（排除手持 —— 玩家手里已经拿着了，再飘一个是重复）
        Inventory inventory = mc.player.getInventory();
        ItemStack mainHand = mc.player.getMainHandItem();
        ItemStack offHand = mc.player.getOffhandItem();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == mainHand || stack == offHand) {
                continue;
            }
            renderOne(mc, poseStack, buffers, level, cameraPos, partialTick, now,
                mc.player.getEyePosition(partialTick), stack, "p" + slot, seen);
        }

        // ② 掉落物
        //    ⚠️ 原版 ItemEntityRenderer 也会画一个（位置更低、带旋转浮动），两者会【视觉重叠】。
        //    用户明确要求"额外渲染"，故保留原版。若嫌重叠，可用 mixin 对活工具掉落物隐藏原版那一个。
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof ItemEntity itemEntity) {
                renderOne(mc, poseStack, buffers, level, cameraPos, partialTick, now,
                    ItemEntityContainerContext.rayOrigin(itemEntity), itemEntity.getItem(),
                    "e" + itemEntity.getId(), seen);
            }
        }

        // ③ 方块容器（数据来自 K2 的 S2C 包）
        for (LivingToolHostPacket.Entry entry :
                LivingToolHostClientCache.get(level.dimension().location())) {
            Vec3 origin = Vec3.atCenterOf(entry.pos());
            var tools = entry.tools();
            for (int i = 0; i < tools.size(); i++) {
                renderOne(mc, poseStack, buffers, level, cameraPos, partialTick, now,
                    origin, tools.get(i).stack(), "b" + entry.pos().asLong() + "_" + i, seen);
            }
        }

        // 清理：本帧没见到的 key 直接丢（工具被取走 / 走出范围）。
        // 条目只在"工作过"时产生，且随帧清理，不会堆积。
        WORK_MEMO.keySet().removeIf(k -> !seen.contains(k));
    }

    /** 渲染一个活工具的悬浮模型（含动画）。 */
    private static void renderOne(Minecraft mc, PoseStack poseStack, MultiBufferSource buffers,
                                  ClientLevel level, Vec3 cameraPos, float partialTick, long now,
                                  Vec3 origin, ItemStack stack, String key, Set<String> seen) {
        if (stack.isEmpty() || !LivingItemManager.isLivingItem(stack)) {
            return;
        }
        LivingToolMemory memory = LivingItemManager.getToolMemory(stack);
        if (memory.isEmpty()) {
            return;
        }
        if (origin.distanceToSqr(cameraPos) > MAX_DISTANCE * MAX_DISTANCE) {
            return;
        }
        seen.add(key);

        // 射线方向 —— 取【记忆本身】，恒定不变（L48：终点不跟随服务端的目标）
        LivingToolMemory.RayMemory ray = memory.dig() != null ? memory.dig() : memory.use();
        Vec3 end = ray.endpointFrom(origin);
        double length = origin.distanceTo(end);
        if (length < 1.0E-6) {
            return;
        }
        Vec3 dir = end.subtract(origin).scale(1.0 / length);

        // 待机位：双曲饱和 —— 越远越远，但增长越来越慢且有上界
        double idleDist = IDLE_MAX_OFFSET * length / (length + IDLE_HALF_SATURATION);
        Vec3 idlePos = origin.add(dir.scale(idleDist));

        LivingToolProgress progress = LivingItemManager.getToolProgress(stack);
        LivingToolAction action = LivingItemManager.getToolLastAction(stack);

        Vec3 pos;
        float spinRad = 0.0F;
        float scale = 1.0F;

        if (progress != null) {
            // 挖掘中：瞬现到交互位 + 风车式转圈（转速由服务端给的预计 tick 决定）
            pos = surfacePoint(level, origin, progress.target());
            int period = spinPeriod(LivingItemManager.getToolDigTicks(stack));
            spinRad = spinAngle(now, partialTick, period);
            remember(key, now, pos);
        } else if (action != null && now - action.tick() < PULSE_TICKS) {
            // 交互：瞬现到交互位 + 缩放脉冲（不转圈）
            pos = surfacePoint(level, origin, action.target());
            float t = (float) (now - action.tick() + partialTick) / PULSE_TICKS;
            scale = 1.0F + PULSE_SCALE * (float) Math.sin(Math.PI * Mth.clamp(t, 0.0F, 1.0F));
            remember(key, now, pos);
        } else {
            WorkMemo memo = WORK_MEMO.get(key);
            if (memo != null && now - memo.lastWorkTick < RETURN_TICKS) {
                // 干完了，飞回待机位（缓出）
                double t = (now - memo.lastWorkTick + partialTick) / RETURN_TICKS;
                pos = memo.workPos.lerp(idlePos, easeOut(t));
            } else {
                pos = idlePos;   // 待机：不动
            }
        }

        drawModel(mc, poseStack, buffers, level, cameraPos, pos, dir, spinRad, scale, stack);
    }

    /** 画一个物品模型：位置 + 朝向（+Y 对齐射线）+ 风车自转 + 缩放。 */
    private static void drawModel(Minecraft mc, PoseStack poseStack, MultiBufferSource buffers,
                                  ClientLevel level, Vec3 cameraPos, Vec3 pos, Vec3 dir,
                                  float spinRad, float scale, ItemStack stack) {
        // 朝向：把局部 +Y 转到射线方向。
        //   yaw   绕 Y（转到目标水平角）
        //   pitch 绕 X（把 +Y 倾斜下来），与自转【同轴】故合并成一次旋转
        float yaw = (float) Mth.atan2(dir.x, dir.z);
        double horizontal = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float pitch = (float) Math.atan2(horizontal, dir.y);

        // 世界光照（用户定：显示在世界里就遵循世界光照）
        int light = LevelRenderer.getLightColor(level, BlockPos.containing(pos));

        poseStack.pushPose();
        // 事件给的 PoseStack 已是【相机相对】坐标
        poseStack.translate(pos.x - cameraPos.x, pos.y - cameraPos.y, pos.z - cameraPos.z);
        poseStack.mulPose(Axis.YP.rotation(yaw));
        poseStack.mulPose(Axis.XP.rotation(pitch + spinRad));
        // 写在最后 = 最先作用于模型：绕【模型自身长轴】滚转 90°（该长轴随后会被对齐到射线方向）
        poseStack.mulPose(Axis.YP.rotation(MODEL_ROLL_FIX));
        if (scale != 1.0F) {
            poseStack.scale(scale, scale, scale);
        }
        mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light,
            OverlayTexture.NO_OVERLAY, poseStack, buffers, level, 0);
        poseStack.popPose();
    }

    /**
     * 射线命中方块<b>表面</b>的那个点（模型瞬现到这里）。
     *
     * <p>用方块自身的 {@link VoxelShape} 求交 —— 只做「格子 → 表面点」的几何细化，
     * <b>不涉及命中判定</b>（判定是服务端 {@code scanForTarget} 的事，见 {@code L48}）。</p>
     */
    private static Vec3 surfacePoint(ClientLevel level, Vec3 origin, BlockPos target) {
        Vec3 center = Vec3.atCenterOf(target);
        VoxelShape shape = level.getBlockState(target).getShape(level, target);
        BlockHitResult hit = shape.clip(origin, center, target);
        return hit != null ? hit.getLocation() : center;
    }

    /**
     * 风车式自转的角度。
     *
     * <p><b>无状态</b>：直接由世界时间取模得出，不做累加 ——
     * 挖掘期间周期恒定，故不会跳变；换目标时模型本来就是瞬现，跳变也看不出来。</p>
     */
    private static float spinAngle(long now, float partialTick, int period) {
        double t = (now + partialTick) % period;
        return (float) (t / period * Math.PI * 2.0);
    }

    /** 挖掘预计 tick → 转圈周期（挖得越快转得越快）。 */
    private static int spinPeriod(@Nullable Integer digTicks) {
        if (digTicks == null) {
            return SPIN_PERIOD_MAX;
        }
        return Mth.clamp((int) Math.ceil(digTicks / SPIN_PERIOD_DIVISOR),
            SPIN_PERIOD_MIN, SPIN_PERIOD_MAX);
    }

    /** 缓出：起步快、落位慢，有"落位感"。 */
    private static double easeOut(double t) {
        double c = Mth.clamp(t, 0.0, 1.0);
        return 1.0 - (1.0 - c) * (1.0 - c);
    }

    private static void remember(String key, long now, Vec3 workPos) {
        WorkMemo memo = WORK_MEMO.computeIfAbsent(key, k -> new WorkMemo());
        memo.lastWorkTick = now;
        memo.workPos = workPos;
    }

    private static final class WorkMemo {
        private long lastWorkTick;
        private Vec3 workPos;
    }
}
