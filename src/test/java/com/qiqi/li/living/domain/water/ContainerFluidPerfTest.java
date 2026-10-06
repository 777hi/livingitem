package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.items.ItemStackHandler;

import com.qiqi.li.living.container.SimpleContainerContext;

/**
 * 流体引擎的**量测**用例（2026-10-07 性能收尾）—— 目的是拿到「每容器每拍耗时」基线，
 * 而不是卡 CI 时间。
 *
 * <p>为什么要有它：我这几轮在性能上判断错两次（先高估 {@code isViewing}、后低估它）
 * ⇒ 之后任何性能结论都要有数字支撑，不再靠估算。
 * 「1 万容器 × 100 玩家」是<b>探针</b>：用它找"与规模成正比"的地方，
 * 再用这里的基线按真实容器数外推。</p>
 *
 * <p>⚠️ 只测<b>引擎 tick</b>；渲染轨（{@code buildPacket} / {@code viewersOf}）依赖真实
 * {@code Level} 与玩家列表，单测量不了 ⇒ 其收益按"跳过的调用次数"论证，不编数字。</p>
 *
 * <p>断言只防<b>病态回归</b>（阈值取得很松），正常波动不会挂。</p>
 */
class ContainerFluidPerfTest {

    private static final int CONTAINERS = 200;
    private static final int SLOTS = 54;      // 大箱子（最坏形态）
    private static final int TICKS = 200;

    @BeforeEach
    void behaviors() {
        FluidFlowBehaviors.clear();
        // 生产口径：水 5t/格、岩浆 30t/格
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(),
            FluidFlowBehavior.flowing(ContainerFluidData.MAX_FLOW_LEVEL, 5));
        FluidFlowBehaviors.register(Fluids.LAVA.getFluidType(), new FluidFlowBehavior() {
            @Override public boolean canFlow() { return true; }
            @Override public int maxLevel() { return 3; }
            @Override public int flowSpeed() { return 30; }
        });
    }

    /** 量测结果落盘（build/ 已 gitignore）—— Gradle 会吞掉 stdout，落盘才拿得到数。 */
    private static void write(String line) {
        try {
            java.nio.file.Files.writeString(
                java.nio.file.Path.of("g:/777hi/mc/mymods/livingitem-template-1.21.1/build/_perf.txt"),
                line,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 量测落盘失败不影响测试
        }
    }

    @Test
    @DisplayName("量测：N 容器 × M 拍的引擎 tick 耗时（打印基线，只防病态回归）")
    void measureEngineTick() {
        var fluids = new ContainerFluidData[CONTAINERS];
        var ctxs = new SimpleContainerContext[CONTAINERS];
        for (int i = 0; i < CONTAINERS; i++) {
            var handler = new ItemStackHandler(SLOTS);
            ctxs[i] = new SimpleContainerContext(handler);
            fluids[i] = new ContainerFluidData();
            // 每容器 1 个水源 + 1 个岩浆源（有反应、有多流体竞争）
            fluids[i].registerGeneratedSource(2, Fluids.WATER.getFluidType());
            fluids[i].registerGeneratedSource(SLOTS - 3, Fluids.LAVA.getFluidType());
        }

        // 预热（JIT）
        for (int t = 0; t < 20; t++) {
            for (int i = 0; i < CONTAINERS; i++) fluids[i].tick(ctxs[i]);
        }

        long start = System.nanoTime();
        for (int t = 0; t < TICKS; t++) {
            for (int i = 0; i < CONTAINERS; i++) fluids[i].tick(ctxs[i]);
        }
        long elapsedNanos = System.nanoTime() - start;

        double perTickMicros = elapsedNanos / 1000.0 / TICKS;                  // 每拍（全批容器）
        double perContainerTickMicros = perTickMicros / CONTAINERS;            // 每容器每拍
        String line = String.format(
            "[流体引擎量测] 容器=%d 槽位=%d 拍数=%d ⇒ 每拍 %.1f ms，" +
            "每容器每拍 %.2f µs（外推 1 万容器 ≈ %.1f ms/拍）%n",
            CONTAINERS, SLOTS, TICKS, perTickMicros / 1000.0,
            perContainerTickMicros, perContainerTickMicros * 10_000 / 1000.0);
        System.out.print(line);
        write(line);   // 同时落盘（Gradle 会吞 stdout，落盘才拿得到数）

        // 只防病态回归：单容器单拍超过 1ms 就是事故级别（正常应在几十 µs 量级）
        assertTrue(perContainerTickMicros < 1_000,
            "单容器单拍耗时应远低于 1ms（实测 " + String.format("%.2f", perContainerTickMicros) + " µs）");
    }

    @Test
    @DisplayName("空容器（无流体）应是廉价 no-op —— 门在 LivingFluidFunction，这里量它的实际开销")
    void measureEmptyContainers() {
        var fluids = new ContainerFluidData[CONTAINERS];
        var ctxs = new SimpleContainerContext[CONTAINERS];
        for (int i = 0; i < CONTAINERS; i++) {
            ctxs[i] = new SimpleContainerContext(new ItemStackHandler(SLOTS));
            fluids[i] = new ContainerFluidData();   // 空：无源无流动
        }

        long start = System.nanoTime();
        for (int t = 0; t < TICKS; t++) {
            for (int i = 0; i < CONTAINERS; i++) {
                if (!fluids[i].isEmpty()) fluids[i].tick(ctxs[i]);   // 与生产同款门
            }
        }
        long elapsedNanos = System.nanoTime() - start;
        double perContainerTickNanos = elapsedNanos / (double) TICKS / CONTAINERS;
        String line = String.format("[空容器量测] 每容器每拍 %.0f ns（应接近零——门在 tick 之前）%n",
            perContainerTickNanos);
        System.out.print(line);
        write(line);

        assertTrue(perContainerTickNanos < 5_000, "空容器每拍开销应在微秒以下");
    }
}
