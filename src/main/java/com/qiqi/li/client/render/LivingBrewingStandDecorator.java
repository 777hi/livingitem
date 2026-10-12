package com.qiqi.li.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.brewing.BrewingBatchLogic;
import com.qiqi.li.living.domain.brewing.LivingBrewingStandData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

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
 * <h3>数据流：组件存「起始时刻」，客户端自算宽度</h3>
 * <ul>
 *   <li>服务端只在批次<b>开始 / 中止 / 完成</b>三个事件写 {@code brewStartTick}
 *       （= {@code level.getGameTime()}，-1 = 不在酿造）—— 稳态零写入、无每 tick 同步；</li>
 *   <li>客户端读组件 + 本地 gameTime 算剩余比例。gameTime 由 {@code ClientboundSetTimePacket}
 *       每 20 tick 校正一次、其余 tick 本地 +1（{@code ClientLevel#tick}）⇒ 平滑，误差 ≤1 秒；</li>
 *   <li>跨维度安全：原版非主世界的 {@code ServerLevel} 用 {@code DerivedLevelData} 包同一个
 *       {@code WorldData} ⇒ gameTime 全维度同源，物品换维度不跳。</li>
 * </ul>
 *
 * <h3>为什么不用原版冷却（addCooldown）白嫖</h3>
 * <p>服务端 {@code player.getCooldowns().addCooldown(Items.BREWING_STAND, …)} 确实能零代码
 * 蹭到原版渲染与同步，但三条语义债：按<b>物品类型 × 玩家</b>而非按实例（所有酿造台一起糊）、
 * 冷却期内该物品类型<b>右键使用被丢包</b>（{@code MultiPlayerGameMode} 源码实证，酿造台会被
 * 放不出来）、只有「见证开始」的批次才显示。⇒ 自绘同款视觉、数据按实例走。</p>
 *
 * <h3>z 语义（同活耕地种子装饰器先例）</h3>
 * <p>相对槽位 pose 抬 200：容器路径净 z = 100 + 200 = 300、快捷栏净 200 > 物品模型 150
 * ⇒ 白幕盖在图标之上（{@code fill} 默认 z 会被深度测试裁掉）。</p>
 */
public class LivingBrewingStandDecorator implements IItemDecorator {

    /** 相对槽位 pose 的抬升量，对齐既有装饰器约定（容器净 300 / 快捷栏净 200） */
    private static final int WIPE_Z = 200;

    /** 一个批组的 时长（与 BrewingBatchLogic.BREW_TIME 同源，400 tick = 20 秒） */
    private static final int BREW_TICKS = BrewingBatchLogic.BREW_TIME;

    /** 原版同款颜色：纯白不透明（Integer.MAX_VALUE = 0xFFFFFFFF，见 GuiGraphics.renderItem） */
    private static final int WIPE_COLOR = 0xFFFFFFFF;

    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        if (!hasActiveBrew(stack)) return false;

        float f = wipeFraction(LivingBrewingStandData.of(stack).brewStartTick(),
            currentGameTickWithPartial());
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

    /**
     * 是否有「进行中的批次」可画（纯判据，包级可见供单测）：活酿造台 + 组件记着起始时刻。
     *
     * <p>刻意<b>不</b>在这里判时间——那需要客户端 gameTime（见 {@link #wipeFraction}）；
     * 分开后守卫可脱离 Minecraft 实例测试。</p>
     */
    static boolean hasActiveBrew(ItemStack stack) {
        return LivingItemManager.isLivingItem(stack)
            && LivingBrewingStandData.of(stack).brewStartTick() >= 0;
    }

    /**
     * 白幕覆盖比例（0..1，1 = 刚开始整面白，0 = 不画）。纯函数供单测。
     *
     * @param brewStartTick 批次起始时刻（服务端 gameTime；{@code -1} = 不在酿造）
     * @param nowTick       当前时刻（客户端 gameTime，可带 partial tick 小数）
     */
    static float wipeFraction(long brewStartTick, float nowTick) {
        if (brewStartTick < 0) return 0.0F;
        float remaining = brewStartTick + BREW_TICKS - nowTick;
        return Mth.clamp(remaining / (float) BREW_TICKS, 0.0F, 1.0F);
    }

    /** 客户端当前 gameTime（带 partial tick）；无世界返回 0（不画）。 */
    private static float currentGameTickWithPartial() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return 0.0F;
        return mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(true);
    }
}
