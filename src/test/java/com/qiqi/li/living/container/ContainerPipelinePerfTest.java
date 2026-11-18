package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.perf.PerfMetrics;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 容器管道的<b>量测</b>用例（Q3，2026-11-15）—— 目的是给「上万容器」目标拿到
 * 一把可复算的尺子，不是卡 CI 时间，也不是验证某个优化。
 *
 * <p>与 {@code ContainerFluidPerfTest} 的分工：那边只量 {@code ContainerFluidData.tick()}
 * （流体引擎）；这里量完整的 {@link ContainerLivingItemHandler#processContext} 管道，
 * 包含逐容器扫描、功能分组、内容签名、TickContext 分配、自维持空 tick、
 * 容器级数据 tick、写回钩子与脏槽刷新。</p>
 *
 * <p>⚠️ <b>量不到的外层</b>：{@code onServerTick} 的区块缓存遍历、方块实体查询、
 * 玩家背包循环与去重 Set 依赖真实 {@code Level}／BE／玩家列表，单测里没有。
 * 依赖 {@code level.isClientSide} 的领域功能（活漏斗等）同样进不了本基准。
 * 因此这里的数字只用于「同一环境下比较优化前后」，<b>不能宣称服主 MSPT</b>。</p>
 *
 * <p>口径：JIT 预热用独立小批；每个规模先清缓存、冷启动一轮并再稳定两轮（均不计入统计），
 * 之后每轮处理全部容器，取「每轮耗时」的中位数与 P90。分阶段明细沿用
 * {@code PerfMetrics} 既有的 60s {@code [Phases]} 报告行，本类不新造采集口径，
 * 也<b>不修改 src/main 的任何 tick 逻辑</b>。</p>
 *
 * <p>复杂场景放 6 枚活红石粉：功能 tick 为空实现、不读 Level，但容器级数据阶段会走
 * 真实的 {@code ContainerRedstoneData.calculate}（见 {@code [Phases]} 的 container_data 段）
 * —— 同时覆盖「分组 / 快照贡献者」与「容器级领域计算」两条路径。活漏斗 / 铜灯首行即
 * {@code level.isClientSide}，单测无真实世界 ⇒ 仍属量不到的外层。</p>
 *
 * <p>断言只防病态回归（阈值取得很松），正常波动不会挂。</p>
 */
@Tag("perf")
class ContainerPipelinePerfTest {

    private static final int SLOTS = 54;                 // 大箱子（最坏形态）
    private static final int[] SIZES = {1_000, 5_000, 10_000};
    private static final int WARMUP_CONTAINERS = 200;
    private static final int WARMUP_ROUNDS = 30;
    private static final int SETTLE_ROUNDS = 2;
    private static final int MIN_ROUNDS = 8;
    private static final int TARGET_CALLS = 100_000;
    private static final double PATHOLOGICAL_MICROS = 1_000.0;

    private enum Scene { EMPTY, PLAIN, COMPLEX }

    private record Result(int rounds, double medianMs, double p90Ms, double perContainerMicros) {}

    @BeforeEach
    void clearCaches() {
        ContainerLivingItemHandler.clearAllCaches();
        PerfMetrics.resetReportTime();
    }

    @Test
    @DisplayName("量测：processContext 管道在空 / 普通 / 复杂容器 × 1k / 5k / 10k 下的每拍耗时")
    void measurePipeline() {
        resetResultFile();
        write(String.format("[管道量测] 开始｜槽位=%d 规模=%s 预热=%d容器×%d轮 稳定轮=%d%n",
            SLOTS, Arrays.toString(SIZES), WARMUP_CONTAINERS, WARMUP_ROUNDS, SETTLE_ROUNDS));

        for (Scene scene : Scene.values()) {
            warmUp(scene);
            for (int size : SIZES) {
                Result r = measure(scene, size);
                String line = String.format(Locale.ROOT,
                    "[管道量测] 场景=%-7s 容器=%5d 轮数=%3d ⇒ 每拍中位 %8.2f ms / P90 %8.2f ms，"
                        + "每容器每拍 %8.2f µs，线性外推 10k %8.2f ms/拍%n",
                    scene, size, r.rounds(), r.medianMs(), r.p90Ms(), r.perContainerMicros(),
                    r.perContainerMicros() * 10_000 / 1_000.0);
                System.out.print(line);
                write(line);
                assertTrue(r.perContainerMicros() < PATHOLOGICAL_MICROS,
                    "单容器单拍耗时应远低于 1ms（实测 "
                        + String.format(Locale.ROOT, "%.2f", r.perContainerMicros()) + " µs）");
            }
        }

        write("[管道量测] 结束｜分阶段明细见本轮 [Phases] 日志行；数字只用于同环境前后比较"
            + System.lineSeparator());
    }

    /** 独立小批预热，让 JIT 先稳定；预热期数据不计入任何一组基线。 */
    private static void warmUp(Scene scene) {
        List<SimpleContainerContext> warm = build(scene, WARMUP_CONTAINERS);
        for (int r = 0; r < WARMUP_ROUNDS; r++) {
            PerfMetrics.resetReportTime();
            for (SimpleContainerContext ctx : warm) {
                ContainerLivingItemHandler.processContext(ctx, null);
            }
        }
        ContainerLivingItemHandler.clearAllCaches();
        PerfMetrics.printReport();   // 清掉预热期的阶段累计
    }

    private static Result measure(Scene scene, int size) {
        List<SimpleContainerContext> ctxs = build(scene, size);
        int rounds = Math.max(MIN_ROUNDS, TARGET_CALLS / size);

        // 组间清缓存：既避免静态表累积到数万条，也让每组的冷启动条件一致。
        ContainerLivingItemHandler.clearAllCaches();
        PerfMetrics.resetReportTime();
        for (int r = 0; r < 1 + SETTLE_ROUNDS; r++) {      // 冷启动 + 稳定轮，均不计入统计
            for (SimpleContainerContext ctx : ctxs) {
                ContainerLivingItemHandler.processContext(ctx, null);
            }
        }
        PerfMetrics.printReport();                         // 清掉冷启动 / 稳定期的阶段累计

        long[] samples = new long[rounds];
        for (int r = 0; r < rounds; r++) {
            PerfMetrics.resetReportTime();                 // 抑制 60s 自动报告，避免中途 reset
            long start = System.nanoTime();
            for (SimpleContainerContext ctx : ctxs) {
                ContainerLivingItemHandler.processContext(ctx, null);
            }
            samples[r] = System.nanoTime() - start;
        }
        PerfMetrics.printReport();                         // 本组 [Phases] 稳态明细（随后 reset）

        Arrays.sort(samples);
        long median = samples[rounds / 2];
        long p90 = samples[Math.min(rounds - 1, (int) Math.ceil(rounds * 0.9) - 1)];
        double perContainerMicros = median / 1000.0 / size;
        return new Result(rounds, median / 1_000_000.0, p90 / 1_000_000.0, perContainerMicros);
    }

    private static List<SimpleContainerContext> build(Scene scene, int count) {
        List<SimpleContainerContext> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ItemStackHandler handler = new ItemStackHandler(SLOTS);
            switch (scene) {
                case PLAIN -> {
                    handler.setStackInSlot(2, new ItemStack(Items.IRON_INGOT, 16));
                    handler.setStackInSlot(11, new ItemStack(Items.COBBLESTONE, 32));
                    handler.setStackInSlot(29, new ItemStack(Items.REDSTONE, 8));
                }
                case COMPLEX -> {
                    // 6 枚活红石粉：功能 tick 为空实现，但容器级数据阶段走真实红石 calculate。
                    // 活漏斗 / 铜灯首行即 level.isClientSide，单测无真实世界 ⇒ 属于量不到的外层。
                    for (int slot : new int[] {2, 11, 20, 29, 38, 47}) {
                        handler.setStackInSlot(slot, living(Items.REDSTONE, 4));
                    }
                    handler.setStackInSlot(5, new ItemStack(Items.IRON_INGOT, 16));
                    handler.setStackInSlot(23, new ItemStack(Items.COBBLESTONE, 32));
                }
                default -> { }   // EMPTY：全空槽位
            }
            out.add(new SimpleContainerContext(handler));   // 测试构造器自带唯一 test#N 键
        }
        return out;
    }

    private static ItemStack living(net.minecraft.world.item.Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    /** 每轮量测开始前清掉上一轮结果：文件是追加式落盘，不截断会把多轮历史混在一起。 */
    private static void resetResultFile() {
        try {
            java.nio.file.Files.deleteIfExists(
                java.nio.file.Path.of("build", "_perf_pipeline.txt"));
        } catch (Exception ignored) {
            // 量测落盘失败不影响测试
        }
    }

    /** 量测结果落盘（build/ 已 gitignore）—— Gradle 会吞掉 stdout，落盘才拿得到数。 */
    private static void write(String line) {
        try {
            java.nio.file.Path path = java.nio.file.Path.of("build", "_perf_pipeline.txt");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, line,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 量测落盘失败不影响测试
        }
    }
}
