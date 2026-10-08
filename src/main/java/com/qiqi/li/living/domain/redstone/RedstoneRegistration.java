package com.qiqi.li.living.domain.redstone;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.living.container.ContainerTickHook;
import com.qiqi.li.living.container.ContainerTickHooks;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.interaction.InteractionRegistry;

/**
 * 红石域注册入口 —— 本领域的 Function / 交互规则 / 交互处理器 / 快照贡献者。
 *
 * <p><b>为什么有这个类</b>（A1，2026-09-27）：原先这些都写在
 * {@code LivingItem#commonSetup} 里（一个 152 行的方法），任何改动都落在同一处 ⇒
 * fork 者与上游的 diff 反复撞车。改为各域自注册后，改动的 diff 落在<b>本域自己的文件</b>里。
 *
 * <p>归属判据：<b>按语义域，不是按 Java 包</b>。
 * {@code ButtonPressHandler} 等类住在 {@code living/interaction/} 包，
 * 但语义上属于红石域 ⇒ 注册跟语义走。
 *
 * <p>由 {@code LivingItem#commonSetup} 调用一次。
 */
public final class RedstoneRegistration {

    private RedstoneRegistration() {}

    public static void register() {
        // ── 功能 ──
        LivingItemManager.registerFunction(new LivingRedstoneFunction());
        LivingItemManager.registerFunction(new LivingRedstoneTorchFunction());
        LivingItemManager.registerFunction(new LivingButtonFunction());
        LivingItemManager.registerFunction(new LivingLeverFunction());
        LivingItemManager.registerFunction(new LivingRedstoneLampFunction());
        LivingItemManager.registerFunction(new LivingRepeaterFunction());
        LivingItemManager.registerFunction(new LivingComparatorFunction());
        LivingItemManager.registerFunction(new LivingRedstoneBlockFunction());
        LivingItemManager.registerFunction(new LivingCopperFunction());

        // ── 容器快照贡献者 ──
        ContainerSnapshot.registerProvider(new RedstoneSnapshotProvider());

        // ── 框架中继：感知端口解析器（2026-10-08 计划 ⑤）──
        // 让 TickContext 只认契约层 RedstoneSensor，不认识 ContainerRedstoneData
        // ⇒ container 包不再 import 红石领域。
        TickContext.registerSensorResolver(
            ctx -> ctx.getOrCreateContainerData(ContainerRedstoneData.KEY));

        // ── 框架中继：tick 开始钩子（2026-10-08 计划 ⑤）──
        // 原「每 tick 归位账本 processed 标志」硬编码在 SimpleContainerContext#setTickContext，
        // 使 container 被迫认识红石账本 ⇒ 移到这里，时机不变。
        // ⚠️ 用 peek（**不创建**）：账本只在「容器与红石有关」时才有必要存在；
        // 若改成 getOrCreate，则每个被 tick 的容器都无条件创建账本，红石驱动守卫的
        // `peek == null` 恒假 ⇒「无关容器零开销」那条路径永不生效。
        // 安全性：新建账本的 processedThisTick 构造时默认 false ⇒ 首次无需 reset。
        ContainerTickHooks.register(new ContainerTickHook() {
            @Override
            public void onTickStart(ContainerContext ctx, TickContext tick) {
                ContainerRedstoneData rd = ctx.peekContainerData(ContainerRedstoneData.KEY);
                if (rd != null) {
                    rd.resetProcessedFlag();
                }
            }
        });

        // ── 交互：按钮按压 ──
        // 十三种按钮曾在此逐条注册；D2 全迁 JSON 后由 #minecraft:buttons tag 一条覆盖
        // （该 tag 实证存在于原版 data/minecraft/tags/item/buttons.json ——
        //   这里旧注释「原版没有统一的按钮标签」是错的，2026-09-28 已验证并更正）。
        InteractionRegistry.registerHandler("button_press", new ButtonPressHandler());

        // ── 交互：拉杆 / 中继器 / 比较器 ──
        InteractionRegistry.registerHandler("lever_toggle", new LeverToggleHandler());
        InteractionRegistry.registerHandler("repeater_cycle", new RepeaterCycleHandler());
        InteractionRegistry.registerHandler("comparator_toggle", new ComparatorToggleHandler());
    }
}
