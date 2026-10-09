package com.qiqi.li.living.domain.power;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import com.qiqi.li.living.runtime.LivingItemClientCache;
import com.qiqi.li.living.runtime.RuntimeSegments;
import com.qiqi.li.living.util.WaxedCopperFamily;

/**
 * 活涂蜡铜块的 tooltip 渲染 —— 2026-10-09 从 {@link LivingWaxedCopperFunction} 抽出
 * （power 收口方案 步骤 4 第 1 刀：纯搬迁，零逻辑变更）。
 *
 * <p>原为 {@code LivingWaxedCopperFunction.addToTooltip}（202 行）+ 若干私有 helper，
 * 合计约 283 行，**无状态、无 tick 依赖**，是纯客户端展示。抽离后
 * {@code LivingWaxedCopperFunction#addToTooltip} 只剩一行委托。</p>
 *
 * <p>顺带清理两处死代码（抽离时发现，均已确认全库零调用）：</p>
 * <ul>
 *   <li>原 {@code renderResonanceTooltip} / {@code renderResonanceFormula} —— 共振信息
 *       已改由仪表盘组件（{@code LivingWaxedCopperTooltipComponent}）呈现；</li>
 *   <li>原 {@code addToTooltip} 开头未使用的局部变量 {@code oxidation}。</li>
 * </ul>
 *
 * <p>谓词（{@code isWaxed*} / {@code getOxidationLevel}）已随「收口 步骤 4 第 2 刀」
 * 迁到 L1 {@link WaxedCopperFamily}；{@code formatMilliFe} 与遥测快照构建已随
 *「步骤 4 第 5 刀」归入 {@link PowerTelemetry}。</p>
 */
final class LivingWaxedCopperTooltip {

    private LivingWaxedCopperTooltip() {}

    /** 渲染入口（由 {@link LivingWaxedCopperFunction#addToTooltip} 委托）。 */
    static void append(Item.TooltipContext context, Consumer<Component> tooltipAdder,
                       TooltipFlag flag, ItemStack stack) {
        var item = stack.getItem();

        // ── 标题行：形态 + 名称 ──
        tooltipAdder.accept(Component.nullToEmpty(""));
        String formKey = formTranslationKey(item);
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.waxed_copper.title",
                Component.translatable(formKey))
            .withStyle(ChatFormatting.GOLD));

        // ── 形态功能说明（三变体差异）──
        tooltipAdder.accept(Component.translatable(formDescKey(item))
            .withStyle(ChatFormatting.GRAY));

        // ── 雕文感应方向（仅涂蜡雕文；v19.1 只感应输入方向）──
        if (WaxedCopperFamily.isWaxedChiseled(item)) {
            var chiseledData = LivingWaxedChiseledData.of(stack);
            tooltipAdder.accept(Component.translatable(
                    "tooltip.livingitem.waxed_copper.chiseled_dir",
                    chiseledData.inputDir().getSymbol())
                .withStyle(ChatFormatting.GRAY));
        }

        if (!WaxedCopperFamily.isWaxedBulb(item)) {
            // 优先从运行时缓存读取遥测数据（不影响物品堆叠），回退到 DataComponent
            RuntimeSegments runtimeData = LivingItemClientCache.getCurrentTooltipData();
            LivingWaxedGeneratorData t = runtimeData.get(GeneratorSegment.INSTANCE);
            if (t == null) {
                t = LivingWaxedGeneratorData.of(stack);
            }
            int pref = stack.getCount();
            boolean hasSignal = t.detectedPeriod() > 0;

            // ══ 核心（常显）：三行数字 ══
            renderSection(tooltipAdder, "tooltip.livingitem.waxed_copper.section.core");

            // ── 偏好周期 / 宽带 ──
            if (pref >= 2) {
                tooltipAdder.accept(Component.literal("  ")
                    .append(Component.translatable("tooltip.livingitem.waxed_copper.preferred_period"))
                    .append(Component.literal(": " + pref + " tick"))
                    .withStyle(ChatFormatting.AQUA));
            } else {
                tooltipAdder.accept(Component.literal("  ")
                    .append(Component.translatable("tooltip.livingitem.waxed_copper.broadband"))
                    .withStyle(ChatFormatting.DARK_AQUA));
            }

            // ── 功率（本机 EMA）──
            if (t.emaPowerMilliFe() > 0) {
                tooltipAdder.accept(Component.literal("  ")
                    .append(Component.translatable("tooltip.livingitem.waxed_copper.ema_power"))
                    .append(Component.literal(": " + PowerTelemetry.formatMilliFe(t.emaPowerMilliFe()) + " FE/t"))
                    .withStyle(ChatFormatting.YELLOW));
            }

            // ── 锈级（共振前基础）──
            if (t.levelEmaPowerMilliFe() > 0) {
                tooltipAdder.accept(Component.literal("  ")
                    .append(Component.translatable("tooltip.livingitem.waxed_copper.level_power"))
                    .append(Component.literal(": " + PowerTelemetry.formatMilliFe(t.levelEmaPowerMilliFe()) + " FE/t"))
                    .withStyle(ChatFormatting.GREEN));
            }

            // ── 共振（实发）──
            if (t.levelEmaPowerMilliFe() > 0) {
                long actualMilliFe = t.resonanceGain() > 1.0
                    ? Math.round(t.levelEmaPowerMilliFe() * t.resonanceGain())
                    : t.levelEmaPowerMilliFe();
                ChatFormatting fmt = t.resonanceGain() > 1.0 ? ChatFormatting.GOLD : ChatFormatting.GRAY;
                tooltipAdder.accept(Component.literal("  ")
                    .append(Component.translatable("tooltip.livingitem.waxed_copper.resonance").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(": " + PowerTelemetry.formatMilliFe(actualMilliFe) + " FE/t").withStyle(fmt)));
            }

            // ── 状态：无信号时一句「检测中」──
            if (!hasSignal) {
                tooltipAdder.accept(Component.literal("  ")
                    .append(Component.translatable("tooltip.livingitem.waxed_copper.no_signal"))
                    .withStyle(ChatFormatting.DARK_GRAY));
            }

            // ══ 以下为进阶诊断公式，仅 F3+H 高级模式显示 ══
            if (flag.isAdvanced()) {
                renderSection(tooltipAdder, "tooltip.livingitem.waxed_copper.section.settlement");

                if (hasSignal) {
                    // ── 代数解释 ──
                    tooltipAdder.accept(Component.literal("  §8Δ §7振幅  §8φ §7相位  §8u §7解锁度  §8N §7活跃锈级数  §8s §7平衡度")
                        .withStyle(ChatFormatting.GRAY));

                    double effDeltaSum = t.effDeltaSumPermille() / 1000.0;
                    double unlock = t.unlockPermille() / 1000.0;
                    double factor = PowerMath.combinedFactor(effDeltaSum, t.phaseCount(), unlock);
                    double fePerEvent = factor * t.detectedPeriod() / 16.0;
                    double powerPerTick = fePerEvent * t.phaseCount() / t.detectedPeriod();

                    // ── 功率公式: 简化 合因子×n/16，颜色区分（§8骨架 §e值）──
                    tooltipAdder.accept(Component.literal("  §7功率:  §8Σ√|Δ|^(1+u) §e= "
                        + String.format("%.1f", effDeltaSum) + "^(" + String.format("%.2f", 1.0 + unlock) + ")"
                        + "  §7×  §8n/16 §e= " + t.phaseCount() + "/16"
                        + "  §7=  §e" + String.format("%.1f", powerPerTick) + " FE/t")
                        .withStyle(ChatFormatting.GRAY));

                    // ── 共振公式: 简化，颜色区分 ──
                    if (t.activeLevels() >= 2) {
                        int levels = t.activeLevels();
                        double s = t.resonanceBalance();
                        long actualMilliFe = Math.round(t.levelEmaPowerMilliFe() * t.resonanceGain());
                        tooltipAdder.accept(Component.literal("  §7共振:  §8(1+(N-1)×s)² §e= (1+" + (levels - 1) + "×"
                            + String.format("%.2f", s) + ")²"
                            + "  §7×  §e" + PowerTelemetry.formatMilliFe(t.levelEmaPowerMilliFe())
                            + "  §7=  §e" + PowerTelemetry.formatMilliFe(actualMilliFe) + " FE/t")
                            .withStyle(ChatFormatting.GRAY));
                    } else {
                        // 单锈级，无共振——直接显示
                        tooltipAdder.accept(Component.literal("  §7共振:  " + PowerTelemetry.formatMilliFe(t.levelEmaPowerMilliFe()) + " FE/t  §8(单锈级，无共振)")
                            .withStyle(ChatFormatting.GRAY));
                    }
                }
            }

            // ── 网络（容器级）：域快照（仅 F3+H 高级模式）──
            if (flag.isAdvanced()) {
                renderSection(tooltipAdder, "tooltip.livingitem.waxed_copper.section.network");

                // 最佳域
                var best = t.bestDomain();
                if (best != null) {
                    StringBuilder sb = new StringBuilder("  §5最佳域: P=").append(best.period()).append("t")
                        .append("  n=").append(best.n())
                        .append("  Σ√|Δ|=").append(String.format("%.1f", best.effDeltaSum()));
                    tooltipAdder.accept(Component.literal(sb.toString())
                        .withStyle(ChatFormatting.DARK_PURPLE));
                    // 相位偏移配对单独一行 [φ0:Δ3 φ1:Δ2 ...]
                    if (!best.deltas().isEmpty()) {
                        StringBuilder ps = new StringBuilder("  §5[");
                        List<Integer> offs = best.offsets();
                        for (int i = 0; i < best.deltas().size(); i++) {
                            if (i > 0) ps.append(" ");
                            int off = (offs != null && i < offs.size()) ? offs.get(i) : i;
                            ps.append("\u03C6").append(off).append(":Δ").append(best.deltas().get(i));
                        }
                        ps.append("]");
                        tooltipAdder.accept(Component.literal(ps.toString())
                            .withStyle(ChatFormatting.DARK_PURPLE));
                    }
                }

                // 其他域：周期 ≤ 最大堆叠数 = 真实多周期信号，> 最大堆叠数 = 虚假周期
                int maxStack = stack.getMaxStackSize();
                int convergingCount = 0;
                for (var ds : t.domains()) {
                    if (best == null || ds.period() != best.period()) {
                        if (ds.period() <= maxStack) {
                            // 周期在偏好范围内，可能是真实的多周期信号
                            tooltipAdder.accept(Component.literal("  §5P=" + ds.period() + "t  n=" + ds.n()
                                + "  Σ√|Δ|=" + String.format("%.1f", ds.effDeltaSum()))
                                .withStyle(ChatFormatting.DARK_PURPLE));
                        } else {
                            convergingCount++;
                        }
                    }
                }
                if (convergingCount > 0) {
                    tooltipAdder.accept(Component.literal("  §8+ 其他 " + convergingCount + " 域（虚假周期，收敛中）")
                        .withStyle(ChatFormatting.DARK_GRAY));
                }
            }
        }
        // ── 铜灯电量 + 锈级专属通道（v18）──
        if (WaxedCopperFamily.isWaxedBulb(item)) {
            int ox = WaxedCopperFamily.getOxidationLevel(item);
            tooltipAdder.accept(Component.literal("  ")
                .append(Component.translatable("tooltip.livingitem.waxed_copper.bulb_channel",
                    Component.translatable("tooltip.livingitem.waxed_copper.oxidation." + ox)))
                .withStyle(ChatFormatting.GRAY));
            LivingWaxedBulbData data = LivingWaxedBulbData.of(stack);
            long q = data.chargeMilliFe() * stack.getCount();
            long cap = LivingWaxedBulbData.totalCapacityMilliFe(stack.getCount());
            boolean full = q >= cap;
            String qStr = q >= 1000 ? String.valueOf(q / 1000)
                : String.format("%.2f", q / 1000.0);
            String capStr = cap >= 1000 ? String.valueOf(cap / 1000)
                : String.format("%.2f", cap / 1000.0);
            tooltipAdder.accept(Component.literal("  ")
                .append(Component.translatable("tooltip.livingitem.waxed_copper.battery"))
                .append(Component.literal(": " + qStr + " / " + capStr + " FE"
                    + (full ? "（已满）" : "")))
                .withStyle(q > 0 ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY));
        }
    }

    /**
     * 分区分隔线：{@code ─── 小标题 ───────}。
     *
     * <p>用 U+2500 方框绘制横线（已确认在原版字体的位图字形表内，不会渲染成豆腐块）。</p>
     */
    private static void renderSection(Consumer<Component> tooltipAdder, String titleKey) {
        tooltipAdder.accept(Component.literal("  §8─── ")
            .append(Component.translatable(titleKey).withStyle(ChatFormatting.DARK_PURPLE))
            .append(Component.literal(" §8─────────────")));
    }

    /** 从物品取形态功能说明翻译键（三变体差异说明，v19.1） */
    private static String formDescKey(Item item) {
        if (WaxedCopperFamily.isWaxedBulb(item)) return "tooltip.livingitem.waxed_copper.form_desc.bulb";
        if (WaxedCopperFamily.isWaxedChiseled(item)) return "tooltip.livingitem.waxed_copper.form_desc.chiseled";
        if (WaxedCopperFamily.isWaxedCut(item)) return "tooltip.livingitem.waxed_copper.form_desc.cut";
        if (WaxedCopperFamily.isWaxedGrate(item)) return "tooltip.livingitem.waxed_copper.form_desc.grate";
        return "tooltip.livingitem.waxed_copper.form_desc.block";
    }

    /** 从物品取形态翻译键 */
    private static String formTranslationKey(Item item) {
        if (WaxedCopperFamily.isWaxedBulb(item)) return "tooltip.livingitem.waxed_copper.form.bulb";
        if (WaxedCopperFamily.isWaxedChiseled(item)) return "tooltip.livingitem.waxed_copper.form.chiseled";
        if (WaxedCopperFamily.isWaxedCut(item)) return "tooltip.livingitem.waxed_copper.form.cut";
        if (WaxedCopperFamily.isWaxedGrate(item)) return "tooltip.livingitem.waxed_copper.form.grate";
        return "tooltip.livingitem.waxed_copper.form.block";
    }
}
