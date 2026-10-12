package com.qiqi.li.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.brewing.BrewingBatchLogic;
import com.qiqi.li.living.domain.brewing.LivingBrewingStandData;
import com.qiqi.li.living.util.StaticCacheRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活酿造台的酿造白幕装饰器 —— 复刻原版物品冷却的视觉语言（末影珍珠 / 盾牌同款）。
 *
 * <h3>为什么是「白幕」而不是别的</h3>
 * <p>原版容器 GUI 的酿造反馈三件套是：进度箭头 + 燃料格 + 冒泡循环。活酿造台是<b>物品</b>，
 * 没有专属 GUI 与方块形态；其中唯一能「一图带走、全场景生效、玩家已形成条件反射」的是
 * 物品图标上的冷却白幕（{@code GuiGraphics.renderItem} 原版三行：底部往上铺纯白矩形，
 * 高度 = 16 × 剩余比例）。悬浮 tooltip 里另有数字版进度（百分比 + 剩余秒数），二者互补：
 * 白幕管「扫一眼就知道在酿、大约还要多久」，tooltip 管精确值。</p>
 *
 * <h3>⭐ 数据流（2026-10-13 简化）：本地动画，不做跨机器时钟对齐</h3>
 * <p>白幕是<b>反馈效果</b>而非精密进度 —— 不需要「和服务器时间严格对齐」，
 * 只需要「在酿的时候有一条在消退的幕、新批次就重新播」。于是：</p>
 * <ul>
 *   <li>组件里的 {@code brewStartTick} 降级为<b>批次标记</b>（新值 = 新批次）；
 *       服务端仍只在批次开始 / 中止 / 完成三个事件写它（稳态零写入）；</li>
 *   <li>客户端第一次见到某个标记时，记下<b>本地</b>时刻作为动画起点；之后每帧
 *       {@code f = 1 − (本地now − 动画起点) / 时长}。起点与 now <b>同为客户端时钟 ⇒ 不漂</b>，
 *       不需要 {@code ClientboundSetTimePacket} 校正、不存在跨机器偏差；</li>
 *   <li>新批次 ⇒ 新标记 ⇒ 自动重新播放（哪怕上一段动画还没播完）——「重播」不是额外逻辑，
 *       是「新 key 新起点」的自然结果；</li>
 *   <li>条目<b>播完不删</b>（留着当「这个批次播过了」的戳）⇒ 不会出现「播完又被重建、反复重播」
 *       的竞态；map 只增（一次游戏几十个 long 条目），跨存档清理时统一清空。</li>
 * </ul>
 * <p>📜 历史：初版走「组件存权威起始时刻 + 客户端与服务端 gameTime 对齐」，
 * 引入了 SetTimePacket 校正链路与三个偏差源（堆叠偏慢 / 校正滞后 / 接缝闪断）。
 * 2026-10-13 用户判定「锦上添花不必精确」⇒ 改为本地动画，三个偏差源中两个消失、
 * 第三个本就是「重播」的语义。</p>
 *
 * <h3>堆叠加速：时长按堆叠数折算（零新数据）</h3>
 * <p>批次时长 = {@code BREW_TIME / 堆叠数} —— 与服务端 {@code stepFor(count)} 的加速口径严格同源，
 * 而堆叠数就在栈上（{@code stack.getCount()}）⇒ 不需要任何新组件字段。</p>
 *
 * <h3>为什么不用原版冷却（addCooldown）白嫖</h3>
 * <p>服务端 {@code player.getCooldowns().addCooldown(Items.BREWING_STAND, …)} 确实能零代码
 * 蹭到原版渲染与同步，但三条语义债：按<b>物品类型 × 玩家</b>而非按实例（所有酿造台一起糊）、
 * 冷却期内该物品类型<b>右键使用被丢包</b>（{@code MultiPlayerGameMode} 源码实证，酿造台会被
 * 放不出来）、只有「见证开始」的批次才显示。⇒ 自绘同款视觉、数据按实例走。</p>
 *
 * <h3>z 语义（同活耕地种子装饰器先例）</h3>
 * <p>相对槽位 pose 抬 200：容器路径净 z = 100 + 200 = 300、快捷栏净 200 > 物品模型 150
 * ⇒ 白幕盖在图标之上（{@code fill} 默认 z 会被深度测试裁掉），也盖在进度条（190）之上。</p>
 */
public class LivingBrewingStandDecorator implements IItemDecorator {

    /** 相对槽位 pose 的抬升量，对齐既有装饰器约定（容器净 300 / 快捷栏净 200） */
    private static final int WIPE_Z = 200;

    /** 一个批次的基准时长（与 BrewingBatchLogic.BREW_TIME 同源，400 tick = 20 秒） */
    private static final int BREW_TICKS = BrewingBatchLogic.BREW_TIME;

    /**
     * 原版同款颜色：{@code Integer.MAX_VALUE}（= {@code 0x7FFFFFFF}）—— 拆开是
     * {@code A=0x7F, R=G=B=0xFF}，即 <b>约 50% 半透明白</b>。
     *
     * <p>⚠️ <b>不是</b>不透明白：原版末影珍珠冷却下能隐约看到物品图标，正是这个 alpha 的效果
     * （2026-10-13 核对源码修正 —— 初版误写为 {@code 0xFFFFFFFF}，比原版实一倍，
     * 用户实测观感不对而复查）。</p>
     */
    private static final int WIPE_COLOR = Integer.MAX_VALUE;

    /**
     * 批次标记 → 本地动画起点（客户端 gameTime）。键是组件的 {@code brewStartTick} 值。
     *
     * <p>⚠️ 必须登记跨存档清理：条目不会自动消失（见类注释「播完不删」）⇒
     * 单机退档进新档不重启 JVM，上个存档的标记会留着（虽无害，但语义上属于上个世界）。</p>
     */
    private static final Map<Long, Float> ANIMATION_START = new ConcurrentHashMap<>();

    static {
        StaticCacheRegistry.onClientLogout(LivingBrewingStandDecorator::clearAnimationState);
    }

    /** 清空动画状态（跨存档 / 退出世界时调用）。 */
    public static void clearAnimationState() {
        ANIMATION_START.clear();
    }

    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        long batchMark = activeBatchMark(stack);
        if (batchMark < 0) return false;

        float now = currentGameTickWithPartial();
        float f = wipeFraction(now - animationStart(batchMark, now), durationFor(stack.getCount()));
        if (f <= 0.0F) return false;

        // 几何照抄原版：从底部往上铺，覆盖高度 = 16 × f（f 从 1 缩到 0 ⇒ 白幕自下往上消退）
        int top = Mth.floor(16.0F * (1.0F - f));
        int bottom = top + Mth.ceil(16.0F * f);

        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(xOffset, yOffset, WIPE_Z);
        guiGraphics.fill(RenderType.guiOverlay(), 0, top, 16, bottom, WIPE_COLOR);
        pose.popPose();
        return true;
    }

    // ============================================================
    // 纯函数（包级可见供单测）
    // ============================================================

    /**
     * 该栈此刻是否该画白幕 —— 返回批次标记（≥0），或 {@code -1} 表示不画。
     *
     * <p>纯判据供单测。⚠️ 装饰器按 {@code Item} 注册 ⇒ <b>所有</b> 酿造台（含原版、非活）
     * 都会进 {@link #render}，所以「是活物品」这层守卫是必需的。</p>
     */
    static long activeBatchMark(ItemStack stack) {
        if (!LivingItemManager.isLivingItem(stack)) return -1L;
        long mark = LivingBrewingStandData.of(stack).brewStartTick();
        return mark >= 0 ? mark : -1L;
    }

    /**
     * 批次动画时长（tick）。堆叠加速：N 个 = N 倍速 ⇒ 时长 / N。
     *
     * <p>与服务端 {@link BrewingBatchLogic#stepFor(int)} 同口径 —— 那边每 tick 推进 N，
     * 这边每 N 分之一个时长走完。堆叠数取自栈（零新数据）。</p>
     */
    static float durationFor(int stackCount) {
        return BREW_TICKS / (float) BrewingBatchLogic.stepFor(stackCount);
    }

    /**
     * 白幕覆盖比例（0..1，1 = 刚开始整面白，0 = 不画）。纯函数供单测。
     *
     * @param elapsedTicks 自本地动画起点已过的 tick（可带 partial 小数）
     * @param durationTicks 本批次的动画时长（见 {@link #durationFor}）
     */
    static float wipeFraction(float elapsedTicks, float durationTicks) {
        if (durationTicks <= 0.0F) return 0.0F;
        return Mth.clamp(1.0F - elapsedTicks / durationTicks, 0.0F, 1.0F);
    }

    /**
     * 取该批次的本地动画起点；首次见到就记为当前时刻（⇒ 这一刻白幕满格）。
     *
     * <p>「播完不删」见类注释：条目一旦建立不再移除，直到跨存档清空。</p>
     */
    static float animationStart(long batchMark, float now) {
        return ANIMATION_START.computeIfAbsent(batchMark, k -> now);
    }

    /** 客户端当前 gameTime（带 partial tick）；无世界返回 0（不画）。 */
    private static float currentGameTickWithPartial() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return 0.0F;
        return mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(true);
    }
}
