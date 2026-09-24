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
import com.qiqi.li.living.domain.tools.LivingToolPlayerClientCache;
import com.qiqi.li.living.domain.tools.LivingToolProgress;
import com.qiqi.li.living.domain.tools.LivingToolRecorder;
import com.qiqi.li.network.LivingToolHostPacket;
import com.qiqi.li.network.LivingToolPlayerPacket;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
 * <h3>自转：绕【薄板法线】</h3>
 * 镐子本质上是一块<b>平面</b>（T 形薄板）。绕什么轴转，决定了看不看得出在转：
 * <ul>
 *   <li>绕<b>柄轴</b>转 —— 对称图形绕对称轴，<b>从侧面几乎看不出来</b> ❌</li>
 *   <li>绕<b>T 平面内的横轴</b>转 —— 工具"<b>横着翻滚</b>"，姿势别扭 ❌</li>
 *   <li>绕<b>垂直于 T 平面的轴</b>（= 薄板法线）—— 薄板在自己的平面里旋转，
 *       <b>任何视角都一眼看出在转</b> ✅ <b>（采用）</b></li>
 * </ul>
 * 模型空间里柄是 {@code +Y}、薄板躺在 {@code XY} 平面 ⇒ <b>法线恰好是 {@code Z}</b>，
 * 于是自转就是一个 {@code Axis.ZP.rotation(spin)}。它写在 {@code rollRad} 的
 * <b>内侧</b> ⇒ 自转轴恒为板面法线，且<b>相对模型自身恒定</b>
 * （不随朝向修正 / 射线方向改变 —— "射线不是自转的参考系"）。
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
    //  ⭐ 手感调参指引 —— 全部手感都收在下面这些常量里，改完直接生效，无需动别处。
    //
    //  ── 模型（所有形态共用）──────────────────────────────────────────────────
    //  MODEL_UPRIGHT_FIX        -45°            模型【立正】角（贴图斜 45° 的校正）。
    //                                            ⚠️ 换用别的物品模型后朝向不对，只改这一个。
    //
    //  ── 射线模式（有记忆的工具）──────────────────────────────────────────────
    //  RAY_ROLL_FIX             +270°           绕长轴的滚转。只调"脸朝哪"，不影响自转轴。
    //  IDLE_MAX_OFFSET          1.5 格          待机位离射线起点的【上限】。
    //                                            调大 = 平时飘得离宿主更远。
    //  IDLE_HALF_SATURATION     3.0 格          走到"上限一半"所需的射线长度。
    //
    //  ── 辅助环（无记忆的工具）────────────────────────────────────────────────
    //  RING_BACK_OFFSET         0.75 格         环心沿【水平后方】偏移多远。
    //                                            调小 = 某些视角下穿透脑袋、挡视线。
    //  RING_HEIGHT              1.7 格          环心高度（【世界竖直】）。
    //                                            调低 = 半径大时下半环会埋进地面。
    //  RING_START_ANGLE         +90°            起始角（π/2 = 从正上方起步）。
    //  RING_RADIUS_*            0.28/0.055/1.10 半径 = BASE + STEP×工具数，clamp 到 MAX。
    //
    //  ⚠️ 环【没有朝向旋钮】—— 法线恒为"水平前方"，斧刃恒朝前。详见常量区说明块。
    //
    //  ── 动画（所有形态共用）──────────────────────────────────────────────────
    //  SPIN_PERIOD_MIN          4 tick/圈       转圈【最快】档。
    //                                            ⚠️ 别低于 4 —— 60fps 下会走样成倒转/抖动。
    //  SPIN_PERIOD_MAX          20 tick/圈      转圈【最慢】档（挖得很慢时）。
    //  SPIN_PERIOD_DIVISOR      10              挖掘预计 tick ÷ 本值 = 转圈周期。
    //  RETURN_TICKS             8 tick          干完飞回待机位耗时（恒定 ⇒ 越远飞得越快）。
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

    /** 缩放脉冲的最大放大比例（0.30 = 放大到 1.30 倍；2026-09-24 用户调，原 0.15）。 */
    private static final float PULSE_SCALE = 0.30F;

    /**
     * 攻击环的<b>存活窗口</b>（tick）—— 与 {@link #PULSE_TICKS} 相同（2026-09-24 用户定）：
     * 每次出手 ⇒ 飞到目标处脉冲一下 ⇒ 立刻收回背后，「一下一下扑上去咬」的节奏。
     *
     * <p>⚠️ 窗口 &lt; 武器攻击冷却（剑 12.5 tick）⇒ 连续攻击时环会【飞出去→收回】反复 ——
     * 这是<b>有意的效果</b>（原先取 20 留在原地反复脉冲，用户看效果后改成了扑咬式）。</p>
     */
    private static final long ATTACK_RING_TICKS = PULSE_TICKS;

    /**
     * 模型【立正】修正角 —— 绕 Z 转 {@code -45°}，把「手持斜 45°」建模的贴图长轴立正成 +Y。
     *
     * <p>⚠️ 2026-09-24 结论（实测三轮）：本旧方案（配合 FIXED 自带的 Y180）对
     * 原版工具 / 守约定的模组<b>正确</b>；两次重构（「手持 transform + 运行时 rotationTo」）
     * 都让原版物品平躺 —— 根因未查明（怀疑 sodium/iris 等渲染管线介入导致实际应用链
     * 与源码阅读不一致）⇒ <b>路线 B（普通模型物品）回退本方案</b>。</p>
     *
     * <p>不守约定的物品（BEWLR，如灾变）走路线 A（fixed 姿态 + thirdperson/fixed 缩放比），
     * 见 {@link #drawModel}。</p>
     */
    private static final float MODEL_UPRIGHT_FIX = (float) (-Math.PI / 4.0);

    /**
     * 挂在<b>射线上</b>的工具（自主模式）额外绕<b>长轴</b>的滚转修正角。
     *
     * <p><b>为什么只有它需要</b>：立正只保证"长轴竖直"，但<b>板面朝向</b>（脸朝哪）
     * 还留着一个绕长轴的自由度。两条路径对这个自由度的期望不同：</p>
     * <ul>
     *   <li><b>射线上</b>：还需再滚 270°（{@code 90° + 180°}）才正常（2026-09-20 用户实测）</li>
     *   <li><b>辅助环</b>：不用它 —— 环的滚转由 {@code ringRoll} 单独给出（见 {@link #drawModel}）</li>
     * </ul>
     *
     * <p>⚠️ 它<b>只</b>管"脸朝哪"，<b>不</b>影响自转轴 —— 自转写在它的内侧，轴恒为板面法线
     * （见 {@link #drawModel}）。曾误把它当自转轴的旋钮，导致自转变成"横着翻滚"。</p>
     */
    private static final float RAY_ROLL_FIX = (float) (Math.PI * 1.5);

    // ══════════════════════════════════════════════════════════════════════════
    //  辅助环（无记忆的活工具）—— 脑袋后面一圈"放射"
    //
    //   ⭐ 2026-09-21 用户规格（原话）：
    //     「以玩家身体朝向为基准，在玩家脑袋后面确立一个点。
    //       以该点为圆心画个圆，圆平面平行玩家背面，参考系依旧是玩家身体。
    //       柄处在圆平面内、延长线过圆心 ⇒ 多个模型呈放射状。
    //       鼓出的方向应当垂直圆平面。
    //       这个环应当显示在玩家背后，而非屏幕上。」
    //
    //   ⇒ 参考系 = 玩家【水平】朝向 + 世界竖直 —— 它是【世界里的物体】，不是屏幕贴纸：
    //        法线 = 水平前方（只跟【转身】，不跟【抬头低头】）
    //        环内上 = 世界竖直；环心 = 玩家位置 + 世界竖直×1.7 − 水平前方×0.75
    //
    //   ⚠️ 为什么法线【不能】用含俯仰的视线方向：那会让环永远正对相机 ⇒ 看起来像
    //      贴在屏幕上（怎么转都一样），失去"背在背后的物体"的感觉（用户 2026-09-21 指出）。
    //      代价是抬头低头时会斜看环 ⇒ 环在屏幕上变成椭圆（这正是"真物体"的表现）。
    //
    //   ⇒ 三根轴（工具姿态，参考系 = 圆平面）：
    //        +Y 柄       = 径向（在圆平面内）  ⇒ 柄的延长线过圆心
    //        +X 鼓出方向 = 环法线（⊥ 圆平面）  ⇒ 斧头朝【你】鼓出
    //        +Z 板法线   = 切向（在圆平面内）  ⇒ 板面 ⊥ 圆平面（斧子【立着】）
    //
    //   ⚠️ 几何代价（用户已知并接受）：正对环时视线【平行于板面】⇒ 只看到斧子的
    //      【侧边】（一条线），斜看才看到完整形状。这是"鼓出方向 ⊥ 圆平面"的必然结果。

    /** 环心沿【身体后方向】偏移多远（格）。⚠️ 别调小：0.45 时某些视角下会穿透脑袋、挡视线。 */
    private static final double RING_BACK_OFFSET = 0.75;

    /** 环心沿【身体上方向】偏移多远（格）—— "脑袋"高度。⚠️ 别调低：半径最大 1.10，放胸口会埋进地底。 */
    private static final double RING_HEIGHT = 1.7;

    /** 环上第一把的起始角（π/2 = 从【身体正上方】起步 ⇒ 单工具立在头顶）。 */
    private static final double RING_START_ANGLE = Math.PI / 2.0;

    /**
     * 环半径 = BASE + STEP × 数量，再 clamp 到上限（越多环越大）。
     *
     * <p>⭐ 2026-09-24 用户调大（0.28 → 0.35）：背后环现在<b>混编工具与武器</b>，
     * 剑的模型长轴长，小半径会穿模；挖掘环 / 攻击环共用同组基准，跟着稍大无碍。</p>
     */
    private static final double RING_RADIUS_BASE = 0.35;
    private static final double RING_RADIUS_STEP = 0.055;
    private static final double RING_RADIUS_MAX = 1.20;

    /**
     * 攻击环的<b>起始半径</b>（2026-09-24 用户调大）—— 剑的模型长轴比镐/铲长，
     * 沿用工具环的 0.28 会在剑与剑之间穿模 ⇒ 攻击环用<b>独立值</b>，工具环保持不变。
     *
     * <p>⭐ 只拆 BASE：STEP / MAX 共用（武器环通常只有一两把 ⇒ BASE 起主导，
     * 上限 1.10 对攻击环同样够用）。</p>
     */
    private static final double ATTACK_RING_RADIUS_BASE = 0.42;

    /** 世界竖直 —— 环平面【竖直】的基准（环内"上"也取它）。 */
    private static final Vec3 WORLD_UP = new Vec3(0.0, 1.0, 0.0);

    /**
     * 上一次有效的「水平前方」（单位向量，只看 yaw、不看 pitch）。
     *
     * <p>⚠️ 视线接近<b>正上 / 正下</b>时水平分量退化为零向量 ⇒ 归一化出 NaN ⇒
     * <b>整圈工具消失</b>。此时<b>沿用它</b>，而不是退回写死的方向
     * （那会让环"啪"地钉到固定一侧）。</p>
     */
    private static Vec3 lastRingFlat = new Vec3(0.0, 0.0, 1.0);
    // ══════════════════════════════════════════════════════════════════════════


    // ── ④ 工具姿态（辅助环）—— ⚠️ 只用 Axis 旋转，绝不自造矩阵 ───────────────

    /*
     * ⭐ 2026-09-21 用户规格：柄在圆平面内、延长线过圆心（放射状）；
     *    斧刃（鼓出面）⊥ 圆平面，且<b>朝前</b>（背离玩家 / 背离相机）。
     *
     * 实现路径（见 {@link #drawModel} 的环分支）—— 与【射线模式逐字同构】：
     *   ① {@code yaw   = atan2(dir.x, dir.z)}  绕 Y
     *   ② {@code pitch = atan2(h, dir.y)}      绕 X（把 +Y 转到 dir = 径向）
     *   ③ {@code ringRoll}                     绕 Y，让 +X（斧刃）⊥ 圆平面且朝前
     *
     * 🔴🔴 <b>血的教训（2026-09-21，排查了整整一天）</b>：
     *   最初用【自造的正交基矩阵】{@code new Matrix4f(faceX, dir, faceZ)} 定向。
     *   症状：<b>柄不指向圆心</b> + <b>模型姿态随视角乱转</b>（看起来像"每个模型自己
     *   在转"，而不是相对环面固定）—— 症状伪装成"参考系/跟随"问题，极难定位。
     *   换成 MC 原生的 {@link Axis} 旋转后<b>立刻正常</b>。
     *
     *   ⇒ <b>不要自造矩阵去定向模型。</b> Axis 旋转用的是 MC 自己的约定
     *     （手性、坐标系、与 display transform 的配合），自造矩阵只要有一处不符，
     *     整块基就废了。
     *
     * 📌 {@code ringRoll} 的相位：yaw/pitch 之后局部 +X 落在 {@code sign(cosθ)·flat}
     *   ⇒ 目标是 +flat（斧刃朝前）⇒ cosθ > 0 那半圈要再转 180° 抵消符号。
     *   ⚠️ 正上 / 正下两把压在分界线上，会各自镜像（用户已确认"没关系"）。
     */

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
        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        MultiBufferSource buffers = mc.renderBuffers().bufferSource();
        long now = level.getGameTime();

        Set<String> seen = new HashSet<>();
        // ⭐ 无记忆的两类分开收集 —— 它们【去不同的环】：活工具 → 挖掘/背后环；活武器 → 攻击环。
        //   （活斧子两者都满足 ⇒ 靠下面 if / else if 的顺序判为【工具】，不会画两遍。）
        List<ItemStack> assistTools = new ArrayList<>();
        List<ItemStack> assistWeapons = new ArrayList<>();

        // ① 玩家背包（排除手持 —— 玩家手里已经拿着了，再飘一个是重复）
        //    ⭐ 按【有没有记忆】分两条路：无记忆 → 辅助环（围成一圈）；有记忆 → 记忆射线上。
        Inventory inventory = mc.player.getInventory();
        ItemStack mainHand = mc.player.getMainHandItem();
        ItemStack offHand = mc.player.getOffhandItem();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == mainHand || stack == offHand) {
                continue;
            }
            // ⭐ 先判工具、再判武器（活斧子两者都满足 ⇒ 算工具，进挖掘/背后环）
            if (LivingToolRecorder.isAssistTool(stack)) {
                assistTools.add(stack);
                continue;
            }
            if (LivingToolRecorder.isAssistWeapon(stack)) {
                assistWeapons.add(stack);
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

        // ④/④′ 挖掘环 + 攻击环 —— 各自只画「正在干活」的那部分；
        //        待机的（工具 + 武器）汇进【同一圈背后环】（2026-09-24 用户定：不区分混编）。
        List<ItemStack> idleRing = new ArrayList<>();
        if (!assistTools.isEmpty()) {
            idleRing.addAll(renderAssistRing(mc, poseStack, buffers, level, cameraPos, partialTick, now, assistTools));
        }
        if (!assistWeapons.isEmpty()) {
            idleRing.addAll(renderAttackRing(mc, poseStack, buffers, level, cameraPos, partialTick, now, assistWeapons));
        }
        if (!idleRing.isEmpty()) {
            renderBackRing(mc, poseStack, buffers, level, cameraPos, partialTick, mc.player, idleRing);
        }

        // ⑤ 其它玩家背包里的活工具（联机可见性 · 最小版：只画背后的待机环）
        //    数据来自服务端 S2C 广播（LivingToolPlayerSync）；位置 / 朝向读原版实体同步。
        //    ⚠️ 挖掘环不在最小版范围内（"他正在挖哪一格"没有同步）。
        for (LivingToolPlayerPacket.Entry entry :
                LivingToolPlayerClientCache.get(level.dimension().location())) {
            if (entry.tools().isEmpty()) {
                continue;
            }
            var owner = level.getPlayerByUUID(entry.playerId());
            if (owner == null || owner == mc.player) {
                continue;   // 自己那份由上面的本机渲染负责
            }
            if (owner.distanceToSqr(cameraPos) > MAX_DISTANCE * MAX_DISTANCE) {
                continue;
            }
            renderBackRing(mc, poseStack, buffers, level, cameraPos, partialTick, owner, entry.tools());
        }

        // 清理：本帧没见到的 key 直接丢（工具被取走 / 走出范围）。
        // 条目只在"工作过"时产生，且随帧清理，不会堆积。
        WORK_MEMO.keySet().removeIf(k -> !seen.contains(k));
    }


    /**
     * 辅助环：无记忆的活工具围成一圈 —— 它是<b>世界里的物体</b>（会被地形遮挡、有透视），
     * 不是贴在屏幕上的东西。
     *
     * <p>⭐ <b>按「能不能对当前方块出力」分成两批，<u>两个环可以同时存在</u></b>
     * （2026-09-21 按历史版本恢复）：</p>
     *
     * <table>
     *   <tr><th>批次</th><th>环心</th><th>法线</th><th>动画</th></tr>
     *   <tr><td><b>出力</b><br>（对该方块有效的）</td>
     *       <td><b>目标方块中心</b></td>
     *       <td><b>指向玩家</b> ⇒ 环面朝着玩家</td>
     *       <td>风车自转（挖得越快转得越快）</td></tr>
     *   <tr><td><b>其余</b><br>（对该方块没用的）</td>
     *       <td>玩家<b>脑袋后面</b></td>
     *       <td><b>水平前方</b> ⇒ 环平面竖直</td>
     *       <td>静止</td></tr>
     * </table>
     *
     * <p>⭐ 待机环的参考系 = 玩家<b>水平</b>朝向 + 世界竖直。<b>只跟转身，不跟抬头低头</b>：
     * 法线取水平前方（只看 yaw）⇒ 环平面竖直；环内"上"取世界竖直 ⇒ 正上方那把永远在头顶。</p>
     *
     * <p>⚠️ <b>法线【不能】用含俯仰的视线方向</b>：那会让环永远正对相机 ⇒ 看起来像贴在屏幕上
     * （怎么转都一样），失去"背在背后的物体"的感觉（用户 2026-09-21 指出）。
     * 代价是抬头低头时会斜看环 ⇒ 环在屏幕上变成椭圆 —— 这正是"真物体"的表现。</p>
     *
     * <p>工具姿态：柄指向圆心（放射状）；斧刃 ⊥ 圆平面且朝前。</p>
     *
     * @return <b>待机</b>（不在挖掘的）工具 —— 调用方要与攻击环的待机武器<b>合并成同一圈背后环</b>
     *         （2026-09-24 用户定：背后环不区分工具与武器）
     */
    private static List<ItemStack> renderAssistRing(Minecraft mc, PoseStack poseStack, MultiBufferSource buffers,
                                                    ClientLevel level, Vec3 cameraPos, float partialTick, long now,
                                                    List<ItemStack> tools) {
        // ── 参考系：水平前方 + 世界竖直（环是"背在背上的竖直环"）────────────────
        Vec3 look = mc.player.getViewVector(partialTick);
        Vec3 flat = new Vec3(look.x, 0.0, look.z);
        if (flat.lengthSqr() < 1.0E-6) {
            // 视线正上 / 正下 ⇒ 水平分量退化为零向量。沿用上一次的有效值
            // ⚠️ 不能退回写死的方向 —— 那会让环"啪"地钉到固定一侧。
            flat = lastRingFlat;
        } else {
            flat = flat.normalize();
            lastRingFlat = flat;
        }

        // ── ① 分组：能对当前方块出力的 → 去方块；其余 → 留背后 ─────────────────
        // 🔴 判据必须用 gameMode.isDestroying()（"确实按着左键"），**不能**用 BreakSpeed 事件 ——
        //    那个【只要准心对着方块就每 tick 触发】（不需要按住），会导致"指着哪就在哪转圈"
        //    （2026-09-20 用户报过的 bug）。
        BlockPos digTarget = mc.gameMode != null && mc.gameMode.isDestroying()
            ? LivingToolAssistState.target(now) : null;
        BlockState digState = digTarget != null ? level.getBlockState(digTarget) : null;

        List<ItemStack> working = new ArrayList<>();
        List<ItemStack> idling = new ArrayList<>();
        for (ItemStack stack : tools) {
            // getDestroySpeed > 1.0 ⇒ 这把工具对【这个方块】有效（1.0 = 徒手速度）⇒ 它能出力。
            if (digState != null && stack.getDestroySpeed(digState) > 1.0F) {
                working.add(stack);
            } else {
                idling.add(stack);
            }
        }

        // ── ③ 挖掘环：目标方块处，法线指向玩家（环面始终朝着玩家）──────────────
        if (!working.isEmpty()) {
            Vec3 center = Vec3.atCenterOf(digTarget);
            Vec3 toPlayer = new Vec3(cameraPos.x - center.x, 0.0, cameraPos.z - center.z);
            Vec3 normal = toPlayer.lengthSqr() < 1.0E-6 ? flat : toPlayer.normalize();
            // 风车自转：转速由"含辅助贡献的破坏速度"换算（复用射线模式那套换算）
            float speed = Math.max(LivingToolAssistState.digSpeed(), 1.0E-4F);
            float spinRad = spinAngle(now, partialTick, spinPeriod((int) Math.ceil(1.0 / speed)));
            drawRing(mc, poseStack, buffers, level, cameraPos, center, normal, working, spinRad,
                1.0F, false, RING_RADIUS_BASE);
        }

        // ⭐ 背后环【不再在这里画】—— 待机物品交给调用方，与攻击环的待机武器
        //    合并成同一圈背后环（各画各的话两圈同心同半径 ⇒ 完全重叠）。
        return idling;
    }

    /**
     * 无记忆的活武器 → <b>攻击环</b>：辅助攻击时围在【目标生物】处。
     *
     * <p>⭐ <b>与挖掘环逐条对称</b>（见 {@link #renderAssistRing} 的 ③）：</p>
     * <pre>
     *   挖掘环：按住左键 ⇒ 飞到【方块】处 … 松手 ⇒ 回背后
     *   攻击环：正在打   ⇒ 飞到【生物】处 … 停手 ⇒ 回背后
     * </pre>
     *
     * <p>差别只有两条（用户 2026-09-24 定）：</p>
     * <ul>
     *   <li><b>不转圈</b> —— 挖掘用风车自转表现"持续出力"，攻击是<b>一下一下</b>的
     *       ⇒ 改用<b>缩放脉冲</b>（每次出手胀一下再回落）</li>
     *   <li><b>剑尖朝圆心</b> —— 与工具环的"柄朝圆心"相反 ⇒ 一圈剑指向中心（{@code inward}）</li>
     * </ul>
     *
     * <p>📌 <b>目标位置从哪来</b>：服务端每次出手都会往武器上写
     * {@code LivingToolAction(now, 目标所在格)}（见 {@code LivingToolReplay#replayAttack}），
     * 该组件<b>只走网络同步、不落盘</b> ⇒ 客户端直接读即可，<b>无需新增同步通道</b>。</p>
     *
     * @return <b>不在攻击中</b>（没打过 / 停手超时）的武器 —— 调用方要与待机工具
     *         <b>合并成同一圈背后环</b>（2026-09-24 用户定：背后环不区分工具与武器）
     */
    private static List<ItemStack> renderAttackRing(Minecraft mc, PoseStack poseStack, MultiBufferSource buffers,
                                                    ClientLevel level, Vec3 cameraPos, float partialTick, long now,
                                                    List<ItemStack> weapons) {
        // ── ① 目标：取本组【最近一次出手】打在哪一格 ────────────────────────────
        // 辅助攻击是"全部朝同一目标各打一次"（docs/idea.md §1.7）⇒ 一把的位置即可代表全组。
        BlockPos target = null;
        long latest = Long.MIN_VALUE;
        for (ItemStack stack : weapons) {
            LivingToolAction action = LivingItemManager.getToolLastAction(stack);
            if (action != null && action.target() != null && action.tick() > latest) {
                latest = action.tick();
                target = action.target();
            }
        }

        // ── ② 没打过 / 停手超时 ⇒ 交还给调用方，与待机工具【合并进同一圈背后环】────
        long age = target == null ? Long.MAX_VALUE : now - latest;
        if (target == null || age >= ATTACK_RING_TICKS) {
            return weapons;
        }

        // ── ③ 攻击环：目标生物处，环面朝玩家（与挖掘环同款取法）───────────────────
        Vec3 center = Vec3.atCenterOf(target);
        Vec3 toPlayer = new Vec3(cameraPos.x - center.x, 0.0, cameraPos.z - center.z);
        Vec3 normal = toPlayer.lengthSqr() < 1.0E-6 ? lastRingFlat : toPlayer.normalize();

        // ── ④ 缩放脉冲：出手瞬间胀一下再回落（复用交互脉冲的时长与力度）─────────────
        //    ⚠️ 冷却(~12 tick) &lt; 存活窗口(20 tick) ⇒ 连续攻击时环【留在原地反复脉冲】，
        //       不会"飞出去又收回"地闪。
        float scale = 1.0F;
        if (age < PULSE_TICKS) {
            float t = (float) (age + partialTick) / PULSE_TICKS;
            scale = 1.0F + PULSE_SCALE * (float) Math.sin(Math.PI * Mth.clamp(t, 0.0F, 1.0F));
        }

        drawRing(mc, poseStack, buffers, level, cameraPos, center, normal, weapons,
            0.0F, scale, true, ATTACK_RING_RADIUS_BASE);
        return List.of();
    }

    /**
     * 在某个玩家<b>背后</b>画待机环（本机玩家与其它玩家<b>共用</b>）。
     *
     * <p>参考系 = 该玩家的<b>水平</b>朝向 + 世界竖直（<b>只跟转身，不跟抬头低头</b>）——
     * 位置与朝向都读 {@code player}（带帧间插值），所以本机 / 远程一视同仁。</p>
     *
     * @param player 参考系来源（本机传 {@code mc.player}，联机时传对应的 {@code Player}）
     */
    private static void renderBackRing(Minecraft mc, PoseStack poseStack, MultiBufferSource buffers,
                                       ClientLevel level, Vec3 cameraPos, float partialTick,
                                       net.minecraft.world.entity.player.Player player,
                                       List<ItemStack> tools) {
        Vec3 look = player.getViewVector(partialTick);
        Vec3 flat = new Vec3(look.x, 0.0, look.z);
        if (flat.lengthSqr() < 1.0E-6) {
            // 视线正上 / 正下 ⇒ 水平分量退化为零向量。
            // 本机玩家沿用上一次的有效值（否则环会"啪"地钉到固定一侧）；
            // 其它玩家没有历史可选，退回世界北即可（他们的视角本来就不该影响你的画面）。
            flat = player == mc.player ? lastRingFlat : new Vec3(0.0, 0.0, -1.0);
        } else {
            flat = flat.normalize();
            if (player == mc.player) {
                lastRingFlat = flat;
            }
        }
        Vec3 center = player.getPosition(partialTick)
            .add(0.0, RING_HEIGHT, 0.0)                 // 世界竖直 ⇒ 高度不随俯仰变
            .subtract(flat.scale(RING_BACK_OFFSET));    // 水平向后 ⇒ 始终在【背后】
        drawRing(mc, poseStack, buffers, level, cameraPos, center, flat, tools, 0.0F, 1.0F, false,
            RING_RADIUS_BASE);
    }

    /**
     * 在指定位置画一圈物品。
     *
     * @param center   环心
     * @param normal   环平面法线（<b>必须水平</b>：环内"上"取世界竖直，靠它保证正交）
     * @param spinRad  风车自转角（0 = 不转）
     * @param scale    整体缩放（{@code 1.0} = 原大小；&gt;1 用于脉冲）
     * @param inward   ⭐ {@code true} = <b>尖端朝圆心</b>（攻击环：剑尖指向中心）；
     *                 {@code false} = <b>柄朝圆心</b>（工具环：镐头朝外）
     * @param radiusBase 起始半径（工具环 {@code RING_RADIUS_BASE} / 攻击环 {@code ATTACK_RING_RADIUS_BASE}
     *                   —— 剑长 ⇒ 攻击环要更大才能不穿模）
     */
    private static void drawRing(Minecraft mc, PoseStack poseStack, MultiBufferSource buffers,
                                 ClientLevel level, Vec3 cameraPos, Vec3 center, Vec3 normal,
                                 List<ItemStack> tools, float spinRad, float scale, boolean inward,
                                 double radiusBase) {
        // normal 恒为水平 ⇒ ⊥ WORLD_UP ⇒ 叉积不退化
        Vec3 right = normal.cross(WORLD_UP).normalize();
        int n = tools.size();
        double radius = Math.min(radiusBase + RING_RADIUS_STEP * n, RING_RADIUS_MAX);
        for (int i = 0; i < n; i++) {
            double angle = RING_START_ANGLE + Math.PI * 2.0 * i / n;
            // 径向（圆心 → 物品）：只决定【位置】
            Vec3 radial = right.scale(Math.cos(angle)).add(WORLD_UP.scale(Math.sin(angle))).normalize();
            Vec3 pos = center.add(radial.scale(radius));

            // ⭐ 朝向：模型局部 <b>+Y</b>（对镐是头、对剑是尖）对齐到哪个方向。
            //   outward = +radial ⇒ 头/尖朝外、柄朝圆心（工具环）
            //   inward  = −radial ⇒ 剑尖朝圆心、柄朝外（攻击环，用户 2026-09-24 定）
            Vec3 dir = inward ? radial.scale(-1.0) : radial;

            // 绕柄滚转量：让"鼓出方向"（+X）⊥ 圆平面，且朝【前方】（背离玩家、背离相机）。
            // 推导：yaw/pitch 之后局部 +X 落在 sign(cosθ)·flat，目标是 +flat
            // ⇒ cosθ > 0 那半圈要再转 180° 抵消符号。只依赖 θ ⇒ 【刚性】，与视角无关。
            //   （2026-09-21 用户实测定：原先取 −flat 是反的 —— 斧刃朝后了。）
            //
            // ⚠️ inward 时 dir 取反 ⇒ yaw 偏移 π ⇒ +X 跟着反向 ⇒ 再补 π 才能仍朝前。
            //   （推导值；若实测剑面反了，去掉下面这行 {@code + Math.PI} 即可。）
            float base = Math.cos(angle) >= 0.0 ? 0.0F : (float) Math.PI;
            float ringRoll = inward ? base + (float) Math.PI : base;

            drawModel(mc, poseStack, buffers, level, cameraPos, pos, dir, normal, ringRoll, spinRad,
                scale, tools.get(i));
        }
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
        // ⭐ 活武器的【攻击记忆】同样是一条射线（只是目标从方块换成生物）⇒ 桥接成 RayMemory，
        //    直接复用下面这一整套渲染（动画暂缓期的做法，见 {@code docs/idea.md} §1.7）。
        //    ⚠️ 少了这个桥接，只带攻击记忆的活剑会在这里 NPE（dig / use 都是 null）。
        LivingToolMemory.RayMemory ray = memory.dig() != null ? memory.dig()
            : memory.use() != null ? memory.use()
            : memory.attack() != null
                ? new LivingToolMemory.RayMemory(memory.attack().offset(), null)
                : null;
        if (ray == null) {
            return;
        }
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
        } else if (action != null && action.target() != null
            && now - action.tick() < PULSE_TICKS) {
            // 交互 / 攻击：瞬现到目标位 + 缩放脉冲（不转圈）。
            //   交互（右键）由 replayUse 写 target；活武器攻击由 replayAttack 写【生物所在格】
            //   —— 两者动画同款（用户 2026-09-24 定："有记忆的，像活工具的交互动画一样"）。
            //
            // ⚠️ target == null（右键空气 / 物品施法）【不能走这条】⇒ 跳过脉冲，模型留在原位。
            //    （surfacePoint 本身永不返回 null —— clip 未命中就退回格心 ⇒ 空中目标也安全。）
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

        drawModel(mc, poseStack, buffers, level, cameraPos, pos, dir, null, 0.0F, spinRad, scale, stack);
    }

    /**
     * 画一个物品模型：位置 + 朝向（{@code +Y} 对齐目标方向）+ 滚转 + 风车自转 + 缩放。
     *
     * @param ringNormal {@code null} = <b>射线模式</b>（yaw/pitch 对齐 + 绕柄滚转）；
     *                   非 {@code null} = <b>辅助环</b>，值取<b>环平面法线</b>，改用正交基定向
     */
    private static void drawModel(Minecraft mc, PoseStack poseStack, MultiBufferSource buffers,
                                  ClientLevel level, Vec3 cameraPos, Vec3 pos, Vec3 dir,
                                  @Nullable Vec3 ringNormal, float ringRoll,
                                  float spinRad, float scale, ItemStack stack) {
        // 射线模式的对齐角（环模式不用）——把局部 +Y 转到目标方向：
        //   yaw   绕 Y（转到目标水平角）
        //   pitch 绕 X（把 +Y 倾斜下来）
        float yaw = (float) Mth.atan2(dir.x, dir.z);
        double horizontal = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float pitch = (float) Math.atan2(horizontal, dir.y);

        // ⭐ 全局光照（满亮）。
        //    采样世界光照时，<b>模型中心一旦落在不透明方块内部</b>（挖掘时瞬现到方块表面，
        //    模型有一半埋进去），采样值就是 0 ⇒ <b>整个模型变黑</b>。
        //    悬浮模型是"表现层"，不该被所在格的遮挡光照吃掉，故统一满亮。
        int light = LightTexture.FULL_BRIGHT;

        poseStack.pushPose();
        // 事件给的 PoseStack 已是【相机相对】坐标
        poseStack.translate(pos.x - cameraPos.x, pos.y - cameraPos.y, pos.z - cameraPos.z);
        if (ringNormal != null) {
            // ── 辅助环：借【射线模式同一套】的 Axis 三步旋转（2026-09-21 用户建议）──
            //   ① yaw/pitch：把 +Y（柄）转到 dir（径向）—— 与 renderOne 完全同构
            //   ② 绕柄滚转：让"鼓出方向"（+X）⊥ 圆平面
            //
            // ⚠️ 原先这里用【自造的正交基矩阵】，实测"柄不指向圆心、姿态随视角乱转"
            //    （用户反馈）⇒ 换成与射线模式完全同构的 Axis 路径，不再自造矩阵。
            //
            // 作用顺序（后写的先作用于模型）：ringRoll → pitch → yaw
            //   ⇒ 先在"柄 = +Y"的坐标系里滚转，再整体对齐到 dir ✅
            poseStack.mulPose(Axis.YP.rotation(yaw));
            poseStack.mulPose(Axis.XP.rotation(pitch));
            if (ringRoll != 0.0F) {
                poseStack.mulPose(Axis.YP.rotation(ringRoll));
            }
        } else {
            // ── 射线模式：yaw/pitch 对齐 + 绕柄滚转 ─────────────────────────
            poseStack.mulPose(Axis.YP.rotation(yaw));
            poseStack.mulPose(Axis.XP.rotation(pitch));
            // 以【工具柄】为轴滚转，只为调整"脸朝哪"。
            poseStack.mulPose(Axis.YP.rotation(RAY_ROLL_FIX));
        }
        BakedModel baked = mc.getItemRenderer().getModel(stack, level, null, 0);
        final boolean customRenderer = baked.isCustomRenderer();
        ItemTransform fixedPose = null;
        ItemTransform thirdPose = null;
        if (customRenderer) {
            fixedPose = baked.getTransforms().getTransform(ItemDisplayContext.FIXED);
            thirdPose = baked.getTransforms()
                .getTransform(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND);
        }

        // 风车自转：绕【立正后的板面法线】= 薄板（T 平面）的【法线】。
        // ⭐ 为什么绕板面法线（2026-09-20 用户实测定）：镐子是一块【平面】。
        //    绕 T 平面【内】的轴转 ⇒ 工具"横着翻滚"，看着别扭；
        //    绕【垂直于 T 平面】的轴转 ⇒ 薄板在自己平面里旋转，任何视角都一眼看出在转。
        // ⚠️ 路线 B 回退旧链 ⇒ 轴回到【立正后的 Z】（绕 Z 转不改变 Z 轴自身 ⇒ 恒为板面法线）；
        //    路线 A（BEWLR）无「板面」约定 ⇒ 兜底绕 X（罕见场景）。
        if (spinRad != 0.0F) {
            if (customRenderer) {
                poseStack.mulPose(Axis.XP.rotation(-spinRad));
            } else {
                // ⚠️ 取负：让工具"尖"朝前转（2026-09-20 用户实测定；方向反了翻此符号）。
                poseStack.mulPose(Axis.ZP.rotation(-spinRad));
            }
        }
        // 写在最后 = 最先作用于模型。
        // ⭐ 2026-09-24 最终结论（两次重构实测失败后，按用户提示「看看之前的代码」回退）：
        //
        // ── 路线 B｜普通模型物品（原版 + 守约定的模组）＝【旧实测方案原样恢复】────────
        //    模型尖（模型空间）→ FIXED 自带的 Y180（renderStatic 内部自然应用，不碰）
        //    → MODEL_UPRIGHT_FIX 绕 Z -45° ⇒ +Y。
        //    两次重构（「应用手持 transform + 抵消 FIXED + 运行时/手算立正」）都让原版平躺，
        //    根因未查明（怀疑 sodium/iris 等渲染管线介入，实际应用链与源码阅读不一致）
        //    ⇒ 先回退恢复正确行为。守约定的模组与原版建模约定相同 ⇒ 旧方案同样准确。
        //
        // ── 路线 A｜BEWLR 物品（灾变等）＝保留用户实测正确的新方案 ─────────────────
        //    fixed display 交给 renderStatic(FIXED) 全权应用（模组为展示框调好的姿态：
        //    剑柄朝圆心、剑身⊥圆平面 ✓），只补缩放比 thirdperson/fixed（对齐第三人称手持）。
        //    🔴 不能走路线 B：会与 fixed display 叠加成双重变换（scale 0.8 × 0.35 = 0.28）。
        if (customRenderer) {
            if (fixedPose != ItemTransform.NO_TRANSFORM && thirdPose != ItemTransform.NO_TRANSFORM) {
                poseStack.scale(
                    thirdPose.scale.x() / fixedPose.scale.x(),
                    thirdPose.scale.y() / fixedPose.scale.y(),
                    thirdPose.scale.z() / fixedPose.scale.z());
            }
        } else {
            poseStack.mulPose(Axis.ZP.rotation(MODEL_UPRIGHT_FIX));
        }
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
