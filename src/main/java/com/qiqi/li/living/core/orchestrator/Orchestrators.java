package com.qiqi.li.living.core.orchestrator;

/**
 * 编排器工厂 —— 提供内置编排器的便捷访问。
 *
 * 使用方式：
 * <pre>
 * new LivingFunctionConfig()
 *     .withOrchestrator(Orchestrators.SIMPLE)
 *     // 或
 *     .withOrchestrator(Orchestrators.FUEL_PROGRESS)
 * </pre>
 *
 * 内置编排器：
 * - SIMPLE：直接遍历组件 tick（适用于活漏斗等简单功能）
 * - PROGRESS：检查输入 → tick/pauseTick → 完成时转化（适用于活磨石等无燃料功能）
 * - FUEL_PROGRESS：检查燃料+输入 → tick/pauseTick → 完成时转化（适用于活熔炉等完整功能）
 */
public final class Orchestrators {

    private Orchestrators() {}

    /** 简单编排器：直接遍历组件 tick */
    public static final LivingOrchestrator SIMPLE = SimpleOrchestrator.INSTANCE;

    /** 进度编排器：检查输入 → tick/pauseTick → 完成时转化 */
    public static final LivingOrchestrator PROGRESS = ProgressOrchestrator.INSTANCE;

    /** 燃料+进度编排器：检查燃料+输入 → tick/pauseTick → 完成时转化 */
    public static final LivingOrchestrator FUEL_PROGRESS = FuelProgressOrchestrator.INSTANCE;
}