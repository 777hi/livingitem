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
    //  RING_BACK_OFFSET         0.75 格         环心沿【身体后方向】偏移多远。
    //                                            调小 = 某些视角下工具会穿透脑袋、挡视线。
    //  RING_HEIGHT              1.7 格          环心沿【身体上方向】偏移多远（脑袋高度）。
    //                                            调低 = 半径大时下半环会埋进地面。
    //  RING_ALPHA                0.0            ★脸的取向：0 = 切向（插在环上、最立体、
    //                                            斧刃整圈统一、但正视只看到【一条线】）；
    //                                            1 = 环法线（躺在环平面、面朝你、
    //                                            正视看到【完整形状】、但斧刃各朝各的）。
    //                                            ⚠️ 想"又立体又能看清"就试 0.3~0.5。
    //  RING_START_ANGLE         +90°            工具的起始角（π/2 = 从【身体正上方】起步）。
    //                                            改回 0 则单工具会横躺在玩家右侧。
    //  RING_RADIUS_*            0.28/0.055/1.10 环半径 = BASE + STEP×工具数，clamp 到 MAX。
    //  （工具姿态不再有旋钮 —— 三根轴以「圆平面」为参考系一次钉死，
    //    斧刃自动整圈统一。⚠️ 别再引入"按世界方向统一符号"那种开关。）
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

    // ══════════════════════════════════════════════════════════════════════════
    //  辅助环（无记忆的活工具）—— 只做三件事
    //
    //   ① 位置：环心 = 玩家背后 + 头部高度
    //   ② 朝向：环法线（唯一决定"转视角时整圈会不会扭"）
    //   ③ 环内布局 + 工具姿态
    // ══════════════════════════════════════════════════════════════════════════

    // ── ① 位置 ───────────────────────────────────────────────────────────────

    /**
     * 待机环：环心沿<b>身体后方向</b>偏移多远（格）。
     *
     * <p>⚠️ 方向是<b>身体的"后"</b>（含俯仰）—— 低头时它斜向上，环跟着身体一起走，
     * 而不是沿世界竖直方向。</p>
     *
     * <p>⚠️ 别调太小：{@code 0.45} 时某些视角下<b>活工具会穿透玩家脑袋、挡住视线</b>
     * （用户实测）。{@code 0.75} 起可避开。</p>
     */
    private static final double RING_BACK_OFFSET = 0.75;

    /**
     * 待机环：环心沿<b>身体上方向</b>偏移多远（格）—— 这就是"脑袋"的高度。
     *
     * <p>⭐ 取<b>头部高度</b>（≈{@code 1.7}），而不是胸口：环半径最大
     * {@code RING_RADIUS_MAX = 1.10}，放在胸口时环最低点会低于地面
     * （{@code 1.0 − 1.10 < 0}），<b>下半部分埋进地底</b>（用户实测）。</p>
     *
     * <p>⚠️ 方向是<b>身体的"上"</b>（含俯仰），不是世界竖直。</p>
     */
    private static final double RING_HEIGHT = 1.7;

    // ── ② 参考系：玩家身体（不是世界！）──────────────────────────────────────

    /*
     * ⭐ 2026-09-21 用户规格原文：
     *   「以玩家身体朝向为基准，在玩家脑袋后面确立一个点。这个点的参考系是玩家身体。
     *     以该点为圆心画个圆，圆平面平行玩家背面。参考系依旧是玩家身体。」
     *
     * 于是【环心 / 圆平面 / 柄 / 斧刃】四件事全部只在【身体坐标系】里定义：
     *
     *   forward  身体前（含俯仰）   → 圆平面法线
     *   right    身体的右           ┐ 张成圆平面
     *   bodyUp   身体的上（含俯仰） ┘
     *
     *   +Y 柄   → 径向（在圆平面内、延长线过圆心）⇒ 放射状
     *   +X 斧刃 → 圆平面法线 ⇒ ⊥ 圆平面，整圈同一方向
     *
     * ⚠️ 曾有一个 {@code RING_FOLLOW_VIEW}（0~1）旋钮，在"跟随视角"和"钉死世界北"
     *    之间插值 —— 那是【没抓住参考系】时的产物，已删除。参考系只有一个，没有强度。
     *
     * ⚠️ "跟随俯仰"被打回过一次，但那版是【半吊子】：位置按【世界水平】偏移、
     *    只有朝向跟随 ⇒ 两者不匹配，看着像环在"仰倒"。现在位置也走身体坐标，才自洽。
     */

    /**
     * 环上工具「脸」（板面法线）的取向 —— 在【切向 ↔ 环法线】之间插值（{@code 0 ~ 1}）。
     *
     * <p>⭐ 这是「工具插在环上有多深」的<b>总旋钮</b>，决定<b>正视环面时你看到什么</b>：</p>
     * <ul>
     *   <li>{@code 0} = 脸 = <b>切向</b> ⇒ 板面 <b>⊥ 圆平面</b>，工具"插"在环上、最立体；
     *       斧刃 = ±环法线 ⇒ <b>整圈朝同一方向</b> ✅（= 用户选定的「甲」）。
     *       ⚠️ 代价：<b>正对圆平面时视线落在板面内 ⇒ 只看到一条线</b>（截图 164822 的样子）。</li>
     *   <li>{@code 1} = 脸 = <b>环法线</b> ⇒ 板面<b>躺在圆平面内、面朝你</b>
     *       ⇒ 正对时看到工具的<b>完整形状</b> ✅（= 截图 164850 / 164913 的样子）。
     *       ⚠️ 代价：斧刃 = 切向 ⇒ <b>绕一圈各朝各的</b>，不再"整圈同一方向"。</li>
     * </ul>
     *
     * <p>📌 <b>中间值</b>（{@code 0.3 ~ 0.5}）= 两者之间：既保留立体感，又能看清工具形状。
     * 这是一个<b>连动量</b>，不是开关 —— 建议用二分法试。</p>
     *
     * <p>📌 <b>历史</b>（2026-09-21 翻提交找回）：它原先叫 {@code RING_ALPHA_BASE}，
     * 和「随时间漂移」的 {@code ringPsi} 一起被当成"让图案活起来的花活"<b>删掉了</b>。
     * <b>那是误删</b> —— {@code ringPsi} 确实是花活（已死不复生），
     * 但 {@code RING_ALPHA} <b>不是</b>：它决定环的<b>基本观感</b>，是刚需旋钮。</p>
     */
    private static final float RING_ALPHA = 0.0F;

    // ── ③ 环内布局 ───────────────────────────────────────────────────────────

    /**
     * 环上工具的<b>起始角</b>（弧度）—— 从环心<b>正上方</b>起步，再均匀铺开。
     *
     * <p>⚠️ <b>别从 {@code 0}（= 玩家右手边）起步</b>：那样只有一个工具时会<b>横躺在右侧、
     * 头朝右</b>，看着不像"待命"（用户实测指正）。从正上方起步 ⇒
     * 单工具<b>竖直立于头顶</b>，双工具一上一下，多工具才铺满整圈。</p>
     */
    private static final double RING_START_ANGLE = Math.PI / 2.0;

    /**
     * 环半径 = {@code BASE + STEP × 工具数}，再 clamp 到上限。
     *
     * <p><b>自适应</b>：工具越多环越大，让它们在圆周上不至于挤成一坨
     * （弧长 {@code 2πr/n} 大致恒定）。</p>
     */
    private static final double RING_RADIUS_BASE = 0.28;
    private static final double RING_RADIUS_STEP = 0.055;
    private static final double RING_RADIUS_MAX = 1.10;

    // ── ④ 工具姿态（⭐ 参考系 = 圆平面，不是世界）────────────────────────────

    /*
     * ⭐ 2026-09-21 用户规格：<b>「以圆平面为参考系」</b>。
     *
     * 这个参考系决定了下面三根轴的取值 —— 也是本项目反复踩坑的根源：
     * 「世界参考系下的同一个方向」在圆平面参考系里<b>不是</b>同一个方向，反之亦然。
     *
     *   +Y 柄   → 径向 r̂            ⇒ 柄在圆平面内、延长线过圆心 ⇒ 放射状
     *   +Z 脸   → 切向 t̂ = n̂ × r̂    ⇒ 板面 ⊥ 圆平面
     *   +X 斧刃 → 法线 n̂（<b>恒定</b>）⇒ 整圈朝向同一方向 ✅
     *
     * ⚠️ <b>不要对 faceZ 做「符号统一」</b>（初版用世界竖直 faceZ.y 当判据翻转它）。
     * 因为 faceX = dir × faceZ，而 dir 到对面反号 ⇒ <b>翻转 faceZ 会连带翻转 faceX</b>，
     * 斧刃于是变成"半圈朝前、半圈朝后" —— 正是用户报的「螺旋感 / 右半朝前左半朝后」。
     *
     * 📌 faceZ 在对面反向<b>不是镜像</b>，而是绕柄转 180°。接受它，faceX 才自动恒定。
     */

    /**
     * 一次「工作」的记忆，用于<b>飞回</b>。
     *
     * <p>只需要「最后一次工作在哪、什么时候」，不需要完整状态机 ——
     * 因为「是否还在工作」能从 {@code LIVING_TOOL_PROGRESS} 现成读到。</p>
     */
    private static final Map<String, WorkMemo> WORK_MEMO = new HashMap<>();

    /**
     * 上一次有效的「身体的右」方向（水平单位向量）。
     *
     * <p>用途：视线接近<b>正上 / 正下</b>时 {@code forward × worldUp} 退化为零向量，
     * 此时<b>沿用它</b>而不是退回一个写死的方向 —— 否则环会"啪"地钉到固定一侧
     * （2026-09-20 用户实测）。</p>
     */
    private static Vec3 lastBodyRight = new Vec3(1.0, 0.0, 0.0);

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
     * <p>⭐ <b>参考系 = 玩家身体</b>（不是世界）—— 环心、圆平面、工具姿态全部以身体为准。</p>
     *
     * <table>
     *   <tr><th>状态</th><th>环心</th><th>圆平面</th></tr>
     *   <tr><td>待机</td><td><b>脑袋后面</b>（沿身体的上 {@value #RING_HEIGHT} 格、
     *       沿身体的后 {@value #RING_BACK_OFFSET} 格）</td>
     *       <td>法线 = <b>身体的前</b>（含俯仰）⇒ 平行玩家背面</td></tr>
     *   <tr><td>出力中</td><td><b>正在挖的那一格</b>（这层是世界参考系）</td>
     *       <td>竖直，法线指向玩家</td></tr>
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
        Vec3 worldUp = new Vec3(0.0, 1.0, 0.0);

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

        // ── ① 参考系：玩家身体 ───────────────────────────────────────────────
        // ⭐ 三根身体轴（定义见类顶部「参考系」说明块）。forward 含俯仰 ⇒ 圆平面也跟着俯仰。
        // ⚠️ 必须用 getPosition(partialTick)（帧间插值）而不是 position()（tick 级）：
        //    渲染是每帧跑的，tick 级位置会让环跟着玩家【一顿一顿】地跳（用户实测）。
        Vec3 forward = mc.player.getViewVector(partialTick).normalize();
        Vec3 right = forward.cross(worldUp);
        if (right.lengthSqr() < 1.0E-6) {
            // 视线正上 / 正下时 forward ∥ worldUp ⇒ 叉积退化。
            // ⚠️ 沿用上一次的有效方向，不能退回写死方向 —— 那会让环"啪"地钉在固定一侧。
            right = lastBodyRight;
        } else {
            right = right.normalize();
            lastBodyRight = right;
        }
        // 身体的上：⊥ forward 且 ⊥ right ⇒ 含俯仰（低头时它前倾）。这是"身体参考系"的关键。
        Vec3 bodyUp = right.cross(forward).normalize();

        // ── ② 位置：从脑袋出发，沿【身体】向后 ───────────────────────────────
        // ⚠️ 高度必须用【世界竖直】，不能用 bodyUp —— 2026-09-21 揪出，这是"工具会转"的元凶之一：
        //    相机的旋转中心是「玩家 + 世界竖直×眼高」，而 bodyUp 会随俯仰倾斜。
        //    若环心也用 bodyUp，两者就【不同轴】⇒ 抬头/低头时环心偏离相机轴线（偏角可达 5~7°）
        //    ⇒ 你与环的相对方向在变 ⇒ 工具看起来在转。
        //    📌 注意：这里改的只是【环心在哪】；环【内】的基仍用 bodyUp，
        //       所以"柄以身体为参考系"不受影响。
        Vec3 center = mc.player.getPosition(partialTick)
            .add(worldUp.scale(RING_HEIGHT))            // 头部高度（世界竖直 —— 与相机同轴）
            .subtract(forward.scale(RING_BACK_OFFSET)); // 沿身体向后

        renderRing(mc, poseStack, buffers, level, cameraPos, center,
            forward, bodyUp, idling, 0.0F);

        // ── ③ 挖掘环：目标方块处 ────────────────────────────────────────────
        // ⚠️ 法线【也只由 forward 决定】：挖掘的方块总在玩家【前方】，
        //    所以"指向玩家" = 身体的"后" = −forward（取水平分量，好与 worldUp 正交）。
        //    审计 2026-09-21：原实现用【cameraPos】算这个方向 —— 第三人称下相机离玩家好几格，
        //    于是环法线会随相机漂移。相机 ≠ 玩家，那是污染，已改成只用 forward。
        if (targetState != null && !working.isEmpty()) {
            Vec3 digCenter = Vec3.atCenterOf(targetPos);
            Vec3 digNormal = new Vec3(-forward.x, 0.0, -forward.z);
            // 视线正上 / 正下时水平分量退化，沿用身体的右（水平、与 worldUp 正交）。
            digNormal = digNormal.lengthSqr() < 1.0E-6 ? right : digNormal.normalize();
            renderRing(mc, poseStack, buffers, level, cameraPos, digCenter,
                digNormal, worldUp, working,
                spinAngle(now, partialTick, ringSpinPeriod(level, targetPos, targetState)));
        }
    }

    /**
     * 把一个列表里的工具均匀铺在环上。位置在圆周上，长轴<b>沿半径朝外</b>
     * ⇒ 工具<b>头朝外、柄朝圆心</b>，像光芒一样呈放射状。
     *
     * <p>⚠️ 前提是模型已由 {@link #MODEL_UPRIGHT_FIX} <b>立正</b> ——
     * 否则贴图那 45° 的倾斜会盖过这里设的朝向，看起来"怎么摆都不对"。</p>
     *
     * @param normal 环平面的法线（⊥ 环面）—— 待机环传<b>身体的"前"</b>（含俯仰）
     * @param up     环平面内的"上"方向 —— 待机环传<b>身体的"上"</b>（含俯仰）。
     *               ⚠️ 必须与 {@code normal} <b>正交</b>，否则圆周会被压成椭圆
     */
    private static void renderRing(Minecraft mc, PoseStack poseStack, MultiBufferSource buffers,
                                   ClientLevel level, Vec3 cameraPos, Vec3 center,
                                   Vec3 normal, Vec3 up,
                                   List<ItemStack> tools, float spinRad) {
        int n = tools.size();
        if (n == 0) {
            return;
        }
        // 环平面内的一组正交基。调用方保证 normal ⊥ up（待机环传的是身体三轴，天然正交），
        // 于是 `normal × up` 给出平面内的第三根轴，且不会退化。
        Vec3 right = normal.cross(up).normalize();
        double radius = Math.min(RING_RADIUS_BASE + RING_RADIUS_STEP * n, RING_RADIUS_MAX);
        for (int i = 0; i < n; i++) {
            double theta = RING_START_ANGLE + Math.PI * 2.0 * i / n;
            // 位置：均匀分布在圆周上；朝向：沿半径【朝外】——"放射"指的就是这个
            Vec3 radial = right.scale(Math.cos(theta)).add(up.scale(Math.sin(theta)));
            drawModel(mc, poseStack, buffers, level, cameraPos,
                center.add(radial.scale(radius)), radial, normal,
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
     * @param ringNormal 仅<b>辅助环</b>用：环平面法线。非 {@code null} 时改用
     *                   <b>正交基</b>定向（见方法内注释）；<b>射线模式</b>（自主）传 {@code null}。
     */
    private static void drawModel(Minecraft mc, PoseStack poseStack, MultiBufferSource buffers,
                                  ClientLevel level, Vec3 cameraPos, Vec3 pos, Vec3 dir,
                                  @Nullable Vec3 ringNormal,
                                  float rollRad, float spinRad, float scale, ItemStack stack) {
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
            // ── 环模式：用【正交基】一次钉死三根轴（★参考系 = 圆平面）───────────
            //   +Y（柄）  = dir（径向）      ⇒ 头朝外、柄朝圆心
            //   +Z（脸）  = 切向 ↔ 环法线    ⇒ 由 RING_ALPHA 插值（见常量说明）
            //   +X（斧刃）= dir × faceZ
            //
            // ⚠️ 为什么不能用 yaw/pitch：那套解只保证"把 +Y 转到 dir"，
            //    剩下的滚转自由度是【任意】的 ⇒ 斧刃会随位置乱翻、不统一。
            Vec3 tangent = ringNormal.cross(dir);    // 切向：沿着圆走的方向
            // ⭐ 脸的取向 = 切向 ↔ 环法线 的插值（RING_ALPHA）。
            //    这两者【都已经 ⊥ 柄】且【互相正交】⇒ 插值永远安全，不会退化。
            Vec3 faceZ = tangent.scale(1.0F - RING_ALPHA).add(ringNormal.scale(RING_ALPHA));
            faceZ = faceZ.lengthSqr() < 1.0E-6 ? tangent : faceZ.normalize();
            // ⚠️ 这里【故意不】统一 faceZ 的符号 —— 详见上方「④ 工具姿态」说明块。
            Vec3 faceX = dir.cross(faceZ);           // 斧刃：⊥柄 且 ⊥脸
            // 三根轴都是单位且相互正交（normal ⊥ dir、dir ⊥ faceZ），直接拼成旋转矩阵。
            poseStack.mulPose(new Matrix4f(
                (float) faceX.x, (float) dir.x, (float) faceZ.x, 0.0F,
                (float) faceX.y, (float) dir.y, (float) faceZ.y, 0.0F,
                (float) faceX.z, (float) dir.z, (float) faceZ.z, 0.0F,
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
