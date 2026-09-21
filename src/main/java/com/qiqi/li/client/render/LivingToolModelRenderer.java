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
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.LightTexture;
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
    //  RAY_ROLL_FIX             +270°           挂在【射线】上的工具额外绕长轴的滚转（只调朝向）。
    //                                            环上的不用（脸天然垂直于环面）。
    //  RING_START_ANGLE         +90°            环上工具的起始角（π/2 = 正上方起步）。
    //                                            改回 0 则单工具会横躺在玩家右侧。
    //  RING_HEIGHT              1.7 格          待机环环心高度（玩家脚下往上）。
    //                                            调低 = 半径大时下半环会埋进地面。
    //  RING_BACK_OFFSET         0.75 格         待机环离玩家背部多远。
    //                                            调小 = 某些视角下工具会穿透脑袋、挡视线。
    //  RING_FOLLOW_VIEW          0.0            环法线跟随视角的强度：1 = 始终正对你（但
    //                                            转视角时整圈会扭）；0 = 固定不扭（★当前）。
    //  ── 以下三个【已关闭】（2026-09-21 用户要求先回到最简状态观察）────────────
    //  RING_PSI_SWEEP           360°            环「漂移锚点」单向行程（转满一圈再倒回）。
    //  RING_PSI_OMEGA             0 rad/tick    ★ 关闭漂移。恢复到 0.0025 即重新启用。
    //  RING_PSI_VIEW_WEIGHT       0             朝向补偿强度：1 = 工具世界姿态不随转头变。
    //  RING_ALPHA_AMP              0            ★ 关闭呼吸。α 固定为 BASE。
    //  RING_ALPHA_OMEGA         0.004 rad/tick  （AMP=0 时无意义）
    //  ── 当前【唯一在起作用】的环参数 ──────────────────────────────────────────
    //  RING_ALPHA_BASE           0.0            脸的取向：0 = 切向（插在环上，最立体，
    //                                            但两端脸相反）；1 = 环法线（所有工具
    //                                            脸同向，但贴在环面上）。中间是渐变。
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
     *   <li><b>射线上</b>：还需再滚 270°（{@code 90° + 180°}）才正常（2026-09-20 用户实测）</li>
     * </ul>
     *
     * <p>⚠️ 它<b>只</b>管"脸朝哪"，<b>不</b>影响自转轴 —— 自转写在它的内侧，轴恒为板面法线
     * （见 {@link #drawModel}）。曾误把它当自转轴的旋钮，导致自转变成"横着翻滚"。</p>
     */
    private static final float RAY_ROLL_FIX = (float) (Math.PI * 1.5);

    // ── 辅助环（无记忆的活工具）────────────────────────────────────────────

    /**
     * 环的「漂移锚点」<b>单向行程</b>（弧度）—— T 平面绕<b>柄轴</b>朝一个方向最多转多少。
     *
     * <p>⭐ <b>它解决的是感知问题，不是几何问题</b>（2026-09-21 用户设计）：
     * 图案原本完全由玩家朝向决定 ⇒ 你会形成"朝北长这样、朝东长那样"的固定印象，
     * 觉得四个方向"不对称"。让锚点自己缓慢变化后，图案始终在变，
     * <b>不存在"固定的 A 样子和 B 样子"</b>，四个朝向也就都有机会呈现相同相位。</p>
     *
     * <p>{@code 2π} = 转满<b>一整个圈</b>再倒回来（2026-09-21 用户要"转完一圈再反转"）。</p>
     */
    private static final float RING_PSI_SWEEP = (float) (Math.PI * 2.0);

    /**
     * 环上工具「脸」的取向 —— 在【切向 ↔ 环法线】之间插值（{@code 0 ~ 1}）。
     *
     * <p>{@code 0} = 脸取<b>切向</b>（沿圆走）：工具<b>"插"在环上</b>，<b>最立体</b>；
     *    但切向沿环绕圈，<b>直径两端的脸必然相反</b>。</p>
     *
     * <p>{@code 1} = 脸取<b>环法线</b>：<b>所有工具的脸朝同一个方向</b>；
     *    但板面平行于环面，工具变成"贴"在环上。</p>
     *
     * <p>⭐ <b>这两条路相差 90°，不是 180°</b> —— 绕柄转 180° 只在切向里翻个面
     *    （切向 → 反切向），出不了环平面，所以改不了这个取舍。</p>
     *
     * <p>⚠️ 2026-09-21：用户要求先关掉时间 / 朝向函数、只留这一个观察基础行为，
     * 所以它现在是<b>唯一在起作用的环参数</b>。</p>
     */
    private static final float RING_ALPHA_BASE = 0.0F;

    /** 环「立体程度」的呼吸幅度。{@code 0} = 固定为 {@code RING_ALPHA_BASE}（关掉呼吸）。 */
    private static final float RING_ALPHA_AMP = 0.0F;

    /** 环「立体程度」的呼吸频率（弧度 / tick）。{@code AMP=0} 时无意义。 */
    private static final float RING_ALPHA_OMEGA = 0.004F;

    /**
     * 环「漂移锚点」中，用多少比例去<b>反向补偿玩家朝向的方位角</b>。
     * {@code 0} = 不补偿（漂移量与朝向无关）；{@code 1} = 完全补偿。
     *
     * <p>⭐ <b>为什么需要它</b>（2026-09-21 用户提出"以玩家朝向为锚点"）：
     * 让 {@code ψ = f(t) − w·φ}（φ = 朝向方位角），则工具姿态变成<b>相对朝向</b>的量，
     * 于是<b>它的世界姿态不再随你转头而变</b> —— 环跟着你转，工具却"钉"在世界里，
     * 四个朝向看起来就一致了。</p>
     *
     * <p>⚠️ <b>它只对一部分工具完全成立</b>：柄<b>竖直</b>的（顶部/底部）工具，
     * 绕柄轴转 ≡ 绕竖直轴转 ⇒ 方位角恰好抵消为 {@code f(t)}，<b>完全与朝向无关</b>；
     * 柄<b>水平</b>的（侧面）工具，⊥柄平面是竖直的（`span{朝向, 下}`），
     * 不同朝向下这两个平面<b>正交</b>，无法完全抵消 —— 只能大幅缩小偏差。</p>
     *
     * <p>⚠️ 代价：转头时工具会<b>反向自转</b>一下（因为它的柄必须沿径向，而径向在转）。
     * 调小 {@code w} 可以减弱这个效果。</p>
     */
    private static final float RING_PSI_VIEW_WEIGHT = 0.0F;

    /**
     * 环「漂移锚点」的角频率（弧度 / tick）—— {@code (1−cos)} 的完整周期 = {@code 2π / 本值}。
     *
     * <p>⭐ <b>函数形式为 {@code ψ = SWEEP · (1 − cos(ωt)) / 2}</b>，而不是 {@code A·sin(ωt)}：
     * {@code sin} 是<b>往返</b>的（`0→+A→0→−A→0`），每个四分之一周期就换向 ⇒
     * 光看单个工具，它一直在"左右晃"，<b>永远转不满一圈</b>。
     * {@code (1−cos)} 则从 0 <b>单调</b>升到 1、再<b>单调</b>降回 0（全程只在两端各换一次向）⇒
     * 配上 {@code SWEEP = 2π} 就是"<b>转完一整圈，再反转回来</b>"。</p>
     *
     * <p>{@code 0.0025} ⇒ 正转 ≈ 63 秒、反转 ≈ 63 秒，一个来回 ≈ 126 秒。
     * 设 {@code 0} = 关闭摆动，回到静止图案（<b>2026-09-21 用户要求先关掉观察</b>）。</p>
     */
    private static final float RING_PSI_OMEGA = 0.0F;

    /**
     * 待机环：背在玩家身后多远（格）。
     *
     * <p>⚠️ 别调太小：{@code 0.45} 时某些视角下<b>活工具会穿透玩家脑袋、挡住视线</b>
     * （2026-09-20 用户实测）。{@code 0.75} 起可避开。</p>
     */
    private static final double RING_BACK_OFFSET = 0.75;

    /**
     * 环上工具的<b>起始角</b>（弧度）—— 从环心<b>正上方</b>起步，再均匀铺开。
     *
     * <p>⚠️ <b>别从 {@code 0}（= 玩家右手边）起步</b>：那样只有一个工具时会<b>横躺在右侧、
     * 头朝右</b>，看着不像"待命"（2026-09-20 用户实测指正）。从正上方起步 ⇒
     * 单工具<b>竖直立于头顶</b>，双工具一上一下，多工具才铺满整圈。</p>
     */
    private static final double RING_START_ANGLE = Math.PI / 2.0;

    /**
     * 待机环：环心高度（玩家脚下的高度 + 本值）。
     *
     * <p>⭐ 取<b>玩家头部高度</b>（≈{@code 1.7}），而不是胸口：环半径最大
     * {@code RING_RADIUS_MAX = 1.10}，放在胸口时环最低点会低于地面
     * （{@code 1.0 − 1.10 < 0}），<b>下半部分埋进地底</b>（2026-09-21 用户实测）。</p>
     */
    private static final double RING_HEIGHT = 1.7;

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
     * 待机环法线<b>跟随玩家视角</b>的强度（{@code 0 ~ 1}）。
     *
     * <p>环的朝向、位置、环内基向量<b>全部</b>由法线导出，所以这个开关直接决定
     * "转视角时整圈工具会不会跟着扭"：</p>
     *
     * <ul>
     *   <li>{@code 1} = 完全跟随 —— 环<b>始终正对着你</b>，但转视角时整圈跟着扭。</li>
     *   <li>{@code 0} = 完全固定 —— 环<b>不再随视角扭</b>，但也不再正对着你（会变斜）。</li>
     * </ul>
     *
     * <p>⭐ <b>只影响朝向，不影响位置</b>：环心始终在玩家背后（那里用的是视角方向，
     * 只是平移）。所以 {@code 0} 时得到的是「<b>在背后 + 不扭</b>」，
     * 而不是「钉在世界某个方位」。</p>
     *
     * <p>⚠️ 2026-09-21：用户要求关掉扭转，故当前为 {@code 0}。</p>
     */
    private static final float RING_FOLLOW_VIEW = 0.0F;

    /** 不跟随视角时，环法线固定取这个方向（世界北 {@code (0,0,-1)}）。 */
    private static final Vec3 RING_FIXED_NORMAL = new Vec3(0.0, 0.0, -1.0);

    /**
     * 一次「工作」的记忆，用于<b>飞回</b>。
     *
     * <p>只需要「最后一次工作在哪、什么时候」，不需要完整状态机 ——
     * 因为「是否还在工作」能从 {@code LIVING_TOOL_PROGRESS} 现成读到。</p>
     */
    private static final Map<String, WorkMemo> WORK_MEMO = new HashMap<>();

    /**
     * 上一次有效的待机环法线（水平单位向量）。
     *
     * <p>用途：视线接近<b>正上 / 正下</b>时，"视线方向的水平分量"退化为零向量，
     * 此时<b>沿用它</b>而不是退回一个写死的方向 —— 否则环会"啪"地钉到固定一侧
     * （2026-09-20 用户实测）。</p>
     */
    private static Vec3 lastBackNormal = new Vec3(0.0, 0.0, 1.0);

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
        // ⚠️ 必须先确认【真的在挖】，不能只看 LivingToolAssistState 有没有目标：
        //    它由 `BreakSpeed` 事件刷新，而该事件【只要准心对着方块就每 tick 触发】
        //    （不需要按住左键）—— 只看它会导致"指着哪就在哪转圈"（2026-09-20 用户报的 bug）。
        //    `MultiPlayerGameMode#isDestroying` 才是"玩家确实按着左键在挖"的权威判据。
        boolean digging = mc.gameMode != null && mc.gameMode.isDestroying();
        BlockPos targetPos = digging ? LivingToolAssistState.target(now) : null;
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

        // 待机环：玩家背部，法线取玩家朝向的【水平分量】（不含俯仰）⇒ 抬头低头环不晃。
        // ⚠️ 正因为法线【恒为水平】，下面才能直接把世界 up 当环平面内的第二根轴
        //    （`normal × up` 不会退化）。若哪天改成跟随俯仰，必须重新构造正交基并处理退化
        //    —— 2026-09-20 试过一版"跟随俯仰"，观感不好被打回，别重复走。
        Vec3 look = mc.player.getViewVector(partialTick);
        Vec3 flat = new Vec3(look.x, 0.0, look.z);
        // 视线的水平分量（抬头低头不晃）。退化时沿用上一次的有效方向 ——
        // ⚠️ 不能退回一个写死的方向，那会让环"啪"地钉在固定一侧（2026-09-20 用户实测）。
        if (flat.lengthSqr() < 1.0E-6) {
            flat = lastBackNormal.scale(1.0);
        } else {
            flat = flat.normalize();
            lastBackNormal = flat;
        }
        // ⭐ 跟随视角的强度（2026-09-21 用户要求"关掉随视角扭转"）：
        //   1 = 完全跟随（环始终正对着你，但转视角时整圈工具跟着扭）
        //   0 = 完全固定（环不再随视角扭，但也不再正对着你）
        //   中间值 = 部分跟随
        //   两者都是水平单位向量，线性插值后归一化即可；退化时回退到跟随。
        Vec3 backNormal = RING_FIXED_NORMAL.scale(1.0F - RING_FOLLOW_VIEW)
            .add(flat.scale(RING_FOLLOW_VIEW));
        if (backNormal.lengthSqr() < 1.0E-6) {
            backNormal = flat;
        }
        backNormal = backNormal.normalize();
        // ⚠️ 必须用 getPosition(partialTick)（帧间插值），而不是 position()（tick 级）。
        //    渲染是每帧跑的，用未插值的位置会让环跟着玩家【一顿一顿】地跳（2026-09-20 用户实测）。
        // 环心：始终在【玩家背后】—— 用【视角方向】算位置。
        // ⭐ 位置与朝向必须分开：位置跟随视角只是【平移】，不会造成"扭转"；
        //    扭转来自【法线】是否跟随（见 RING_FOLLOW_VIEW）。
        //    ⚠️ 这里若误用 backNormal，法线一固定环心就跟着钉死，环会跑到身前/侧面。
        Vec3 backCenter = mc.player.getPosition(partialTick)
            .add(0.0, RING_HEIGHT, 0.0)
            .subtract(flat.scale(RING_BACK_OFFSET));
        // ⭐ 脸的取向 α（见 RING_ALPHA_BASE）。AMP = 0 时即为固定值。
        float ringAlpha = RING_ALPHA_BASE
            + RING_ALPHA_AMP * (float) Math.sin(now * (double) RING_ALPHA_OMEGA);
        renderRing(mc, poseStack, buffers, level, cameraPos, backCenter,
            backNormal.cross(up).normalize(), up, idling,
            new RingPose(backNormal, ringPsi(now, backNormal), ringAlpha), 0.0F);

        // 挖掘环：目标方块处，法线指向玩家（环面始终朝着玩家）
        if (targetState != null && !working.isEmpty()) {
            Vec3 center = Vec3.atCenterOf(targetPos);
            Vec3 toPlayer = new Vec3(cameraPos.x - center.x, 0.0, cameraPos.z - center.z);
            Vec3 digNormal = toPlayer.lengthSqr() < 1.0E-6 ? backNormal : toPlayer.normalize();
            renderRing(mc, poseStack, buffers, level, cameraPos, center,
                digNormal.cross(up).normalize(), up, working,
                new RingPose(digNormal, ringPsi(now, digNormal), ringAlpha),
                spinAngle(now, partialTick, ringSpinPeriod(level, targetPos, targetState)));
        }
    }

    /**
     * 环的漂移锚点 = <b>时间行程</b> − <b>朝向补偿</b>。
     *
     * @param normal 该环的法线 —— 它的方位角就是要被补偿掉的 {@code φ}
     * @see #RING_PSI_SWEEP        时间行程（单向转满一圈再倒回）
     * @see #RING_PSI_VIEW_WEIGHT  朝向补偿强度（{@code 1} = 完全补偿）
     */
    private static float ringPsi(long now, Vec3 normal) {
        float sweep = RING_PSI_SWEEP
            * (float) ((1.0 - Math.cos(now * (double) RING_PSI_OMEGA)) * 0.5);
        float azimuth = (float) Math.atan2(normal.x, normal.z);
        return sweep - RING_PSI_VIEW_WEIGHT * azimuth;
    }

    /**
     * 辅助环上一把工具的<b>姿态参数</b>（打包成一个记录）。
     *
     * <p>为什么打包：环的参数已经不止一个（法线 / 漂移角 / 脸的取向），
     * 散在参数列表里既难读，以后<b>每加一个就要改所有调用处</b>。
     * 打包后加参数只改本记录 + 一处解包即可。</p>
     *
     * @param normal 环平面的法线（也就是"垂直于环面"的那个方向）
     * @param psi    T 平面绕<b>柄轴</b>的漂移角（{@code 0} = 不漂移）
     * @param alpha  脸的取向：{@code 0} = 切向（沿圆走，最立体）；{@code 1} = 环法线（脸全统一）
     */
    private record RingPose(Vec3 normal, float psi, float alpha) {
    }

    /**
     * 把一个列表里的工具均匀铺在环上：位置在圆周上，长轴<b>沿半径朝外</b>
     * ⇒ 工具<b>头朝外、柄朝圆心</b>，像光芒一样呈放射状。
     *
     * <p>⚠️ 前提是模型已由 {@link #MODEL_UPRIGHT_FIX} <b>立正</b> —— 否则贴图那 45° 的
     * 倾斜会盖过这里设的朝向，看起来"怎么摆都不对"（2026-09-20 踩过）。</p>
     *
     * @param right / @param up 环平面内的一组正交基向量
     * @param pose              环的姿态（法线 / 漂移角 / 脸的取向），见 {@link RingPose}
     */
    private static void renderRing(Minecraft mc, PoseStack poseStack, MultiBufferSource buffers,
                                   ClientLevel level, Vec3 cameraPos, Vec3 center,
                                   Vec3 right, Vec3 up,
                                   List<ItemStack> tools, RingPose pose, float spinRad) {
        int n = tools.size();
        if (n == 0) {
            return;
        }
        double radius = Math.min(RING_RADIUS_BASE + RING_RADIUS_STEP * n, RING_RADIUS_MAX);
        for (int i = 0; i < n; i++) {
            double theta = RING_START_ANGLE + Math.PI * 2.0 * i / n;
            // 位置：均匀分布在圆周上；朝向：沿半径【朝外】——"放射"指的就是这个
            Vec3 radial = right.scale(Math.cos(theta)).add(up.scale(Math.sin(theta)));
            // 环上用【正交基】定向（传 pose），不再需要 rollRad —— 三根轴已一次钉死
            drawModel(mc, poseStack, buffers, level, cameraPos,
                center.add(radial.scale(radius)), radial, pose,
                0.0F, spinRad, 1.0F, tools.get(i));
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
     * @param ring 仅<b>辅助环</b>用：环的姿态（法线 / 漂移角 / 脸的取向）。
     *             非 {@code null} 时改用 <b>正交基</b>定向，而不是 {@code yaw/pitch}
     *             （原因见方法内注释、以及 {@link RingPose}）。
     *             <b>射线模式</b>（自主）传 {@code null}。
     */
    private static void drawModel(Minecraft mc, PoseStack poseStack, MultiBufferSource buffers,
                                  ClientLevel level, Vec3 cameraPos, Vec3 pos, Vec3 dir,
                                  @Nullable RingPose ring,
                                  float rollRad, float spinRad, float scale, ItemStack stack) {
        // 朝向：把局部 +Y 转到目标方向。
        //   yaw   绕 Y（转到目标水平角）
        //   pitch 绕 X（把 +Y 倾斜下来）
        float yaw = (float) Mth.atan2(dir.x, dir.z);
        double horizontal = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float pitch = (float) Math.atan2(horizontal, dir.y);

        // ⭐ 全局光照（满亮）—— 用户 2026-09-20 定。
        //    原先是采样世界光照（`LevelRenderer.getLightColor`），但**模型中心一旦落在不透明方块内部**
        //    （挖掘时瞬现到方块表面，模型有一半埋进去），采样值就是 0 ⇒ **整个模型变黑**。
        //    悬浮模型是"表现层"，不该被所在格的遮挡光照吃掉，故统一满亮。
        int light = LightTexture.FULL_BRIGHT;

        poseStack.pushPose();
        // 事件给的 PoseStack 已是【相机相对】坐标
        poseStack.translate(pos.x - cameraPos.x, pos.y - cameraPos.y, pos.z - cameraPos.z);
        if (ring != null) {
            // 解包：内部继续用原来的三个名字，下面的逻辑一行不用改
            Vec3 ringNormal = ring.normal();
            float ringPsi = ring.psi();
            float ringAlpha = ring.alpha();
            // ⭐ 环上改用【正交基】定向（2026-09-20 用户实测定）。
            //   为什么不能用 yaw/pitch：那套解只保证"把 +Y 转到 dir"，
            //   剩下的滚转自由度是【任意】的 —— 结果 +Z（板面法线）会随工具在环上的位置
            //   翻来翻去：右半边朝"切向"、左半边朝"切向的反方向"，看着左右两半不一致。
            //   正交基把三根轴一次钉死：
            //       +X（横线）    → 见下（默认 = ringNormal，⊥ 环面）
            //       +Y（柄）      → dir（= 径向）      ⇒ 头朝外、柄朝圆心
            //       +Z（板面法线）→ ringNormal × dir   ⇒ 【切向】
            // ① 脸（板面法线）的方向 = 【切向 ↔ 环法线】的插值。
            //    ⭐ 这两者【都已经垂直于柄】且【互相正交】，所以插值永远安全，无需投影/退化处理。
            //
            //    ringAlpha = 0 → 脸 = 切向（沿圆走）
            //                    ⇒ 工具【"插"在环上】，最立体；但切向沿环绕圈，
            //                      直径两端的脸必然相反（见文档）。
            //    ringAlpha = 1 → 脸 = 环法线
            //                    ⇒ 【所有工具的脸朝同一个方向】；但板面平行于环面，
            //                      工具变成"贴"在环上。
            //
            //    📌 关键点：这两条路之间差【90°，不是 180°】。绕柄转 180° 只会在切向里
            //       翻个面（切向 → 反切向），仍然出不了环平面 —— 这就是"转 180° 救不了"的原因。
            Vec3 tangent = ringNormal.cross(dir);      // 切向：沿着圆走的方向
            Vec3 faceZ = tangent.scale(1.0F - ringAlpha).add(ringNormal.scale(ringAlpha));
            if (faceZ.lengthSqr() < 1.0E-6) {
                faceZ = tangent;                        // 理论不会退化，留个兜底
            }
            faceZ = faceZ.normalize();
            // ⭐ 符号统一（"取绝对值"）—— 2026-09-21 用户定。
            //    问题：切向沿环绕圈，到对面就反号 ⇒ 板的【另一面】朝你 ⇒ 贴图镜像
            //    ⇒ 斧刃换边 ⇒ 右半圈朝前、左半圈朝后（用户："很不好看"）。
            //    解法：把符号统一到"朝上"那一侧。判据就是 faceZ 在【世界竖直】上的投影
            //    （up = (0,1,0)，所以点积恰好等于 faceZ.y，不用真的算点积）。
            //    效果：左右两半完全一致 ✅。
            //    代价：正上方 / 正下方那两把各自为政 —— 用户已确认"没关系"。
            if (faceZ.y < 0.0) {
                faceZ = faceZ.scale(-1.0);
            }
            Vec3 faceX = dir.cross(faceZ);              // 横线：⊥柄 且 ⊥脸
            // ② 再让整个 T 平面绕【柄轴】漂移 ringPsi（见 RING_PSI_SWEEP）。
            Vec3 ax = faceX.scale(Math.cos(ringPsi)).add(faceZ.scale(Math.sin(ringPsi)));
            Vec3 ay = dir;
            Vec3 az = faceZ.scale(Math.cos(ringPsi)).subtract(faceX.scale(Math.sin(ringPsi)));
            poseStack.mulPose(new Matrix4f(
                (float) ax.x, (float) ay.x, (float) az.x, 0.0F,
                (float) ax.y, (float) ay.y, (float) az.y, 0.0F,
                (float) ax.z, (float) ay.z, (float) az.z, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F));
        } else {
            poseStack.mulPose(Axis.YP.rotation(yaw));
            poseStack.mulPose(Axis.XP.rotation(pitch));
        }
        // 以【工具柄】为轴滚转 rollRad —— 只为调整"脸朝哪"，是【相对射线】的修正。
        if (rollRad != 0.0F) {
            poseStack.mulPose(Axis.YP.rotation(rollRad));
        }
        // 风车自转：绕【立正后的 Z】= 薄板（T 平面）的【法线】。
        // ⭐ 为什么是 Z 而不是 X（2026-09-20 用户实测定）：镐子是一块【平面】。
        //    绕 T 平面【内】的轴转 ⇒ 工具"横着翻滚"，看着别扭；
        //    绕【垂直于 T 平面】的轴转 ⇒ 薄板在自己平面里旋转，任何视角都一眼看出在转。
        // ⚠️ 位置必须写在 rollRad【内侧】、立正【紧外侧】：这样自转轴就是"立正后的 Z"
        //    = 板面法线，而且【相对模型自身恒定】—— 不随 rollRad / 射线方向改变。
        //    （2026-09-20 用户澄清："自转是相对于模型本身的，射线不是参考系"。）
        //    曾写在 rollRad 外侧，导致自转轴被 rollRad 带到"模型 X"上，变成横着翻滚。
        if (spinRad != 0.0F) {
            // ⚠️ 取负：让工具"尖"朝前转（2026-09-20 用户实测定；正号是斧背朝前、反了）。
            poseStack.mulPose(Axis.ZP.rotation(-spinRad));
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
