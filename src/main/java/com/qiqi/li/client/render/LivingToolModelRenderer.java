package com.qiqi.li.client.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ItemEntityContainerContext;
import com.qiqi.li.living.domain.tools.LivingToolAction;
import com.qiqi.li.living.domain.tools.LivingToolAssistState;
import com.qiqi.li.living.domain.tools.LivingToolHostClientCache;
import com.qiqi.li.living.domain.tools.LivingToolMemory;
import com.qiqi.li.living.domain.tools.LivingToolProgress;
import com.qiqi.li.living.domain.tools.LivingToolRecorder;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import org.joml.Matrix4f;

/**
 * 活工具<b>悬浮模型 + 动画</b>（{@code K} 组）—— 让活工具在世界里"活起来"。
 *
 * <h3>模型从哪来</h3>
 * 直接用<b>活工具物品本身的 3D 模型</b>（镐就是镐、斧就是斧），
 * 经 {@code ItemRenderer#renderStatic} + {@link ItemDisplayContext#FIXED} 渲染。
 * 用 {@code FIXED} 而非 {@code GUI} —— 后者是扁平的 2D 图标。
 *
 * <p>⚠️ 但 {@code FIXED} 的 display 变换<b>只有"绕 Y 转 180°"</b>这一下（见模型 json 的
 * {@code "fixed"}），不含任何"立正"旋转 ⇒ 渲染出来仍是<b>贴图原生朝向</b>。
 * 而 MC 的工具贴图是<b>斜 45° 对角</b>画的（为了在物品栏图标里好看），
 * 所以模型天生是斜的（掉落物同理）—— 需要 {@link #MODEL_UPRIGHT_FIX} 手动"立正"。</p>
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
 * <h3>为什么自转绕【薄板法线】</h3>
 * 镐子本质上是一块<b>平面</b>（T 形薄板）。绕什么轴转，决定了看不看得出在转：
 * <ul>
 *   <li>绕<b>柄轴</b>转 —— 对称图形绕对称轴，<b>从侧面几乎看不出来</b> ❌</li>
 *   <li>绕<b>T 平面内的横轴</b>转 —— 工具"<b>横着翻滚</b>"，姿势别扭 ❌（2026-09-20 实测踩过）</li>
 *   <li>绕<b>垂直于 T 平面的轴</b>（= 薄板法线）—— 薄板在自己的平面里旋转，
 *       <b>任何视角都一眼看出在转</b> ✅</li>
 * </ul>
 * 模型空间里柄是 {@code +Y}、薄板躺在 {@code XY} 平面 ⇒ <b>法线恰好是 {@code Z}</b>，
 * 于是自转就是一个 {@code Axis.ZP.rotation(spin)}。
 * 更妙的是它写在 {@code rollRad} 的<b>外侧</b>：自转轴会跟着 rollRad 一起滚 ——
 * 于是环上（{@code rollRad=0}）用板面法线、射线上（{@code rollRad=90°}）自然回落到原来的局部 X，
 * <b>两条路径不用分叉</b>。
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
    //  MODEL_UPRIGHT_FIX        -45°            模型【立正】修正角（绕贴图法线 Z）。
    //                                            ⚠️ 换用别的物品模型后朝向不对，只改这一个。
    //  RAY_ROLL_FIX             +90°            挂在【射线】上的工具额外绕长轴的滚转。
    //                                            环上的不用（脸天然垂直于环面）。
    //  RING_START_ANGLE         +90°            环上工具的起始角（π/2 = 正上方起步）。
    //                                            改回 0 则单工具会横躺在玩家右侧。
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
     * 模型【立正】修正角 —— 绕<b>贴图平面的法线（Z）</b>在平面内旋转。
     *
     * <p>⚠️ <b>为什么需要它</b>：MC 的 {@code item/handheld} 贴图是<b>斜 45° 对角</b>画的
     * （镐头在右上、柄在左下），为的是在物品栏图标里好看。模型本身<b>没有任何元数据</b>
     * 能说明"哪边是上" ⇒ 直接渲染出来就是<b>斜的</b>（掉落物同理）。
     * 实测症状：工具"45° 斜朝下"，怎么调朝向都不对。</p>
     *
     * <p><b>完整链路</b>（注意 {@code FIXED} 自己也会转一下）：</p>
     * <ol>
     *   <li>模型原始空间：贴图 {@code (0,16) → 模型 (0,0)}、{@code (16,0) → 模型 (1,1)}（Y 翻转）
     *       ⇒ 长轴指向 {@code (+1,+1)}</li>
     *   <li>{@code FIXED} 的 display 变换：<b>绕 Y 转 180°</b>（模型 json 里的 {@code "fixed"}）
     *       ⇒ {@code (x,y,z) → (-x,y,-z)} ⇒ 长轴变 {@code (-1,+1)}</li>
     *   <li><b>本常量</b>：绕 Z 转 {@code -45°} ⇒
     *       {@code x' = -cos(-45) - sin(-45) = 0}、{@code y' = -sin(-45) + cos(-45) = √2}
     *       ⇒ 立成 {@code +Y}，头朝上、柄朝下</li>
     *   <li>再由 {@link #drawModel} 把 {@code +Y} 对齐到目标方向</li>
     * </ol>
     *
     * <p>📌 <b>跨项目印证</b>：同工作区的「御剑」模组（{@code libs/src/YujianCraft-main}）
     * 用<b>完全相同的方案</b>（{@code renderStatic(FIXED)} + 绕 Z 校正），
     * 并在源码里留下了同样的结论：
     * <i>"FIXED rotates the vanilla item 180 degrees around Y. -45 aligns the real blade axis."</i>
     * —— 第 2 步的 Y180 就是被这句点出来的，我最初漏了它，所以把符号写反了。</p>
     *
     * <p>⚠️ 换用别的物品模型时若朝向不对，只调这一个常量即可（±45 / ±90 / ±180 都是常见值）。</p>
     */
    private static final float MODEL_UPRIGHT_FIX = (float) (-Math.PI / 4.0);

    /**
     * 挂在<b>射线上</b>的工具（自主模式）额外绕<b>长轴</b>的滚转修正角。
     *
     * <p><b>为什么只有它需要</b>：立正只保证"长轴竖直"，但<b>板面朝向</b>（脸朝哪）
     * 还留着一个绕长轴的自由度。两条路径对这个自由度的期望不同：</p>
     * <ul>
     *   <li><b>辅助环</b>：脸<b>垂直于环面</b> —— 立正后天然就满足，补 0（用户实测确认 ✅）</li>
     *   <li><b>射线上</b>：还需再转 90° 才正常（2026-09-20 用户实测）</li>
     * </ul>
     *
     * <p>⚠️ 若符号反了就把 π/2 取负。若将来发现"环上也要补"，说明该把两者彻底拆成两套参数。</p>
     */
    private static final float RAY_ROLL_FIX = (float) (Math.PI / 2.0);

    // ── 辅助环（无记忆的活工具）────────────────────────────────────────────

    /** 待机环：背在玩家身后多远（格）。 */
    private static final double RING_BACK_OFFSET = 0.45;

    /**
     * 环上工具的<b>起始角</b>（弧度）—— 从环心<b>正上方</b>起步，再均匀铺开。
     *
     * <p>⚠️ <b>别从 {@code 0}（= 玩家右手边）起步</b>：那样只有一个工具时会<b>横躺在右侧、
     * 头朝右</b>，看着不像"待命"（2026-09-20 用户实测指正）。从正上方起步 ⇒
     * 单工具<b>竖直立于头顶</b>，双工具一上一下，多工具才铺满整圈。</p>
     */
    private static final double RING_START_ANGLE = Math.PI / 2.0;

    /** 待机环：环心高度（玩家脚下的高度 + 本值，约在胸口）。 */
    private static final double RING_HEIGHT = 1.0;

    /**
     * 环半径 = {@code BASE + STEP × 工具数}，再 clamp 到上限。
     *
     * <p><b>自适应</b>：工具越多环越大，让它们在圆周上不至于挤成一坨
     * （弧长 {@code 2πr/n} 大致恒定）。</p>
     */
    private static final double RING_RADIUS_BASE = 0.28;
    private static final double RING_RADIUS_STEP = 0.055;
    private static final double RING_RADIUS_MAX = 1.10;

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
        //    ⭐ 这里按【有没有记忆】分成两套完全不同的渲染（见类注释「两种模式」）：
        //       有记忆 → 自主模式：挂在记忆射线上（renderOne）
        //       无记忆 → 辅助模式：围成「环」放射（renderAssistRing）
        Inventory inventory = mc.player.getInventory();
        ItemStack mainHand = mc.player.getMainHandItem();
        ItemStack offHand = mc.player.getOffhandItem();
        List<ItemStack> assist = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == mainHand || stack == offHand) {
                continue;
            }
            if (isAssistTool(stack)) {
                assist.add(stack);   // 按槽位号升序收集 ⇒ 环上的顺序稳定、不跳
                continue;
            }
            renderOne(mc, poseStack, buffers, level, cameraPos, partialTick, now,
                mc.player.getEyePosition(partialTick), stack, "p" + slot, seen);
        }
        renderAssistRing(mc, poseStack, buffers, level, cameraPos, partialTick, now, assist);

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

    /**
     * 辅助模式（无记忆）的模型：围成「环」放射状排布。
     *
     * <table>
     *   <tr><th>状态</th><th>环心</th><th>环平面</th></tr>
     *   <tr><td>待机</td><td><b>玩家背部</b>（胸口高度、身后 {@value #RING_BACK_OFFSET} 格）</td>
     *       <td>竖直，法线 = 玩家朝向（<b>不含俯仰</b> ⇒ 抬头低头环不晃）</td></tr>
     *   <tr><td>出力中</td><td><b>正在挖的那一格</b></td><td>竖直，法线指向玩家</td></tr>
     * </table>
     *
     * <p>能对当前目标出力的飞到挖掘环，其余留在背部环 ——
     * <b>"环上少了几把"本身就是"它们去帮忙了"的表达</b>。</p>
     *
     * <p>位置切换用<b>瞬现</b>（与自主模式一致）；半径按数量自适应（见 {@code RING_RADIUS_*}）。</p>
     */
    private static void renderAssistRing(Minecraft mc, PoseStack poseStack, MultiBufferSource buffers,
                                         ClientLevel level, Vec3 cameraPos, float partialTick, long now,
                                         List<ItemStack> assist) {
        if (assist.isEmpty()) {
            return;
        }
        Vec3 up = new Vec3(0.0, 1.0, 0.0);

        // 分流：能对当前目标出力的去挖掘环
        BlockPos targetPos = LivingToolAssistState.target(now);
        BlockState targetState = targetPos != null ? level.getBlockState(targetPos) : null;
        List<ItemStack> working = new ArrayList<>();
        List<ItemStack> idling = new ArrayList<>();
        for (ItemStack stack : assist) {
            if (targetState != null && stack.getDestroySpeed(targetState) > 1.0F) {
                working.add(stack);
            } else {
                idling.add(stack);
            }
        }

        // 待机环：玩家背部。
        // ⭐ 法线取【完整视线方向】（含俯仰）⇒ 抬头低头环也跟着晃（2026-09-20 用户要）。
        //    原实现只取水平分量（"抬头低头环不晃"），现在改成完全跟随视角。
        Vec3 look = mc.player.getViewVector(partialTick);
        Vec3 normal = look.normalize();
        // ⚠️ 环心【仍按水平背向】偏移：若也用含俯仰的方向，抬头时环会掉到脚底、低头时顶到头顶。
        Vec3 back = new Vec3(look.x, 0.0, look.z);
        back = back.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : back.normalize();
        Vec3 backCenter = mc.player.position()
            .add(0.0, RING_HEIGHT, 0.0)
            .subtract(back.scale(RING_BACK_OFFSET));
        // ⚠️ 环平面内的一组正交基。原先直接借世界 up 当第二根轴，那是**因为法线恒为水平**；
        //    现在法线会随俯仰倾斜，必须重新构造 —— 且正对上下时 `normal × up` 会退化，改用世界 Z 兜底。
        Vec3 ref = Math.abs(normal.y) > 0.99 ? new Vec3(0.0, 0.0, 1.0) : up;
        Vec3 right = normal.cross(ref).normalize();
        Vec3 ringUp = right.cross(normal).normalize();
        renderRing(mc, poseStack, buffers, level, cameraPos, backCenter,
            normal, right, ringUp, idling, 0.0F);

        // 挖掘环：目标方块处，法线指向玩家（环面始终朝着玩家）
        if (targetState != null && !working.isEmpty()) {
            Vec3 center = Vec3.atCenterOf(targetPos);
            Vec3 toPlayer = new Vec3(cameraPos.x - center.x, 0.0, cameraPos.z - center.z);
            Vec3 digNormal = toPlayer.lengthSqr() < 1.0E-6 ? back : toPlayer.normalize();
            renderRing(mc, poseStack, buffers, level, cameraPos, center,
                digNormal, digNormal.cross(up).normalize(), up, working,
                spinAngle(now, partialTick, ringSpinPeriod(level, targetPos, targetState)));
        }
    }

    /**
     * 把一个列表里的工具均匀铺在环上：位置在圆周上，长轴<b>沿半径朝外</b>
     * ⇒ 工具<b>头朝外、柄朝圆心</b>，像光芒一样呈放射状。
     *
     * <p>⚠️ 前提是模型已由 {@link #MODEL_UPRIGHT_FIX} <b>立正</b> —— 否则贴图那 45° 的
     * 倾斜会盖过这里设的朝向，看起来"怎么摆都不对"（2026-09-20 踩过）。</p>
     *
     * @param right / @param up 环平面内的一组正交基向量
     */
    private static void renderRing(Minecraft mc, PoseStack poseStack, MultiBufferSource buffers,
                                   ClientLevel level, Vec3 cameraPos, Vec3 center,
                                   Vec3 normal, Vec3 right, Vec3 up,
                                   List<ItemStack> tools, float spinRad) {
        int n = tools.size();
        if (n == 0) {
            return;
        }
        double radius = Math.min(RING_RADIUS_BASE + RING_RADIUS_STEP * n, RING_RADIUS_MAX);
        for (int i = 0; i < n; i++) {
            double theta = RING_START_ANGLE + Math.PI * 2.0 * i / n;
            // 位置：均匀分布在圆周上；朝向：沿半径【朝外】——"放射"指的就是这个
            Vec3 radial = right.scale(Math.cos(theta)).add(up.scale(Math.sin(theta)));
            // 环上用【正交基】定向（传 normal），不再需要 rollRad —— 三根轴已一次钉死
            drawModel(mc, poseStack, buffers, level, cameraPos,
                center.add(radial.scale(radius)), radial, normal, 0.0F, spinRad, 1.0F, tools.get(i));
        }
    }

    /** 挖掘环的转圈周期 —— 挖得越快转得越快（与自主模式同一套换算）。 */
    private static int ringSpinPeriod(ClientLevel level, BlockPos pos, BlockState state) {
        float speed = LivingToolAssistState.digSpeed();
        float hardness = state.getDestroySpeed(level, pos);
        if (speed <= 0.0F || hardness <= 0.0F) {
            return SPIN_PERIOD_MAX;
        }
        // 近似：辅助会放行材质门槛，故用 30 而非 100（纯视觉，无需精确）
        float perTick = speed / hardness / 30.0F;
        return Mth.clamp((int) Math.ceil(1.0F / perTick / SPIN_PERIOD_DIVISOR),
            SPIN_PERIOD_MIN, SPIN_PERIOD_MAX);
    }

    /** 无记忆的活工具 —— 走辅助环那套渲染（口径与 {@code LivingToolAssist} 一致）。 */
    private static boolean isAssistTool(ItemStack stack) {
        return !stack.isEmpty()
            && LivingItemManager.isLivingItem(stack)
            && LivingToolRecorder.isLivingTool(stack)
            && LivingItemManager.getToolMemory(stack).isEmpty();
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

        drawModel(mc, poseStack, buffers, level, cameraPos, pos, dir, null,
            RAY_ROLL_FIX, spinRad, scale, stack);
    }

    /**
     * 画一个物品模型：位置 + 朝向（{@code +Y} 对齐目标方向）+ 滚转 + 风车自转 + 缩放。
     *
     * @param ringNormal 仅<b>辅助环</b>用：环平面的法线。非 {@code null} 时改用
     *                   <b>正交基</b>定向，而不是 {@code yaw/pitch}（原因见方法内注释）。
     */
    private static void drawModel(Minecraft mc, PoseStack poseStack, MultiBufferSource buffers,
                                  ClientLevel level, Vec3 cameraPos, Vec3 pos, Vec3 dir,
                                  @Nullable Vec3 ringNormal,
                                  float rollRad, float spinRad, float scale, ItemStack stack) {
        // 朝向：把局部 +Y 转到目标方向。
        //   yaw   绕 Y（转到目标水平角）
        //   pitch 绕 X（把 +Y 倾斜下来）
        float yaw = (float) Mth.atan2(dir.x, dir.z);
        double horizontal = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float pitch = (float) Math.atan2(horizontal, dir.y);

        // 世界光照（用户定：显示在世界里就遵循世界光照）
        int light = LevelRenderer.getLightColor(level, BlockPos.containing(pos));

        poseStack.pushPose();
        // 事件给的 PoseStack 已是【相机相对】坐标
        poseStack.translate(pos.x - cameraPos.x, pos.y - cameraPos.y, pos.z - cameraPos.z);
        if (ringNormal != null) {
            // ⭐ 环上改用【正交基】定向（2026-09-20 用户实测定）。
            //   为什么不能用 yaw/pitch：那套解只保证"把 +Y 转到 dir"，
            //   剩下的滚转自由度是【任意】的 —— 结果 +Z（板面法线）会随工具在环上的位置
            //   翻来翻去：右半边朝"切向"、左半边朝"切向的反方向"，看着左右两半不一致。
            //   正交基则把三根轴一次钉死：
            //       +X（横线）→ ringNormal          ⇒ T 平面包含环法线，横线垂直于环面（用户要的）
            //       +Y（柄）  → dir（= 径向）        ⇒ 头朝外、柄朝圆心
            //       +Z（板面法线）→ ringNormal × dir ⇒ 统一取【切向】，左右两半同规则
            Vec3 ax = ringNormal;
            Vec3 ay = dir;
            Vec3 az = ringNormal.cross(dir);
            poseStack.mulPose(new Matrix4f(
                (float) ax.x, (float) ay.x, (float) az.x, 0.0F,
                (float) ax.y, (float) ay.y, (float) az.y, 0.0F,
                (float) ax.z, (float) ay.z, (float) az.z, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F));
        } else {
            poseStack.mulPose(Axis.YP.rotation(yaw));
            poseStack.mulPose(Axis.XP.rotation(pitch));
        }
        poseStack.mulPose(Axis.XP.rotation(pitch));
        // 风车自转：绕【当前局部 Z】= 薄板（T 平面）的【法线】。
        // ⭐ 为什么是 Z 而不是 X（2026-09-20 用户实测定）：镐子是一块【平面】。
        //    绕 T 平面【内】的轴转 ⇒ 工具"横着翻滚"，看着别扭；
        //    绕【垂直于 T 平面】的轴转 ⇒ 薄板在自己平面里旋转，任何视角都一眼看出在转。
        //    ⚠️ 必须写在 rollRad 的【外侧】：自转轴才会跟着 rollRad 一起滚，
        //       于是两条路径自动各自正确（见下面 rollRad 的说明）——
        //       rollRad = 0   → 自转轴 = 局部 Z（板面法线）← 环上
        //       rollRad = 90° → 自转轴 = 局部 X（= 原来的行为）← 射线上
        if (spinRad != 0.0F) {
            poseStack.mulPose(Axis.ZP.rotation(spinRad));
        }
        // 以【工具柄】为轴滚转 rollRad —— 位置很关键，两件事同时成立才对：
        //   · 写在立正【外侧】：立正之后 +Y 才等于柄轴，此时"绕 Y 转"才是绕柄轴；
        //   · 写在自转【内侧】：转的是【工具自身的柄轴】，而不是"射线方向"。
        //     柄轴是工具自身的属性、会跟着自转走；射线方向是空间中的固定轴。
        //     等价关系：Q·R_Y(θ) ≡ R_dir(θ)·Q（Q 把 +Y 对齐 dir）。
        //   ⭐ 副产物：它同时也"转动了自转轴"（上面那步的 Z）—— 正是靠这一点，
        //     rollRad=0 的环上用板面法线自转、rollRad=90° 的射线上仍用局部 X 自转。
        if (rollRad != 0.0F) {
            poseStack.mulPose(Axis.YP.rotation(rollRad));
        }
        // 写在最后 = 最先作用于模型：把【斜 45° 的贴图长轴】立正成 +Y（见 MODEL_UPRIGHT_FIX）。
        // 立正后 +Y 就是工具长轴，上面几步的 pitch/yaw 才能把它正确对齐到目标方向。
        poseStack.mulPose(Axis.ZP.rotation(MODEL_UPRIGHT_FIX));
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
