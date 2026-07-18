package com.qiqi.li.living;

import java.util.List;
import java.util.Map;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.FunctionExecutor;
import com.qiqi.li.living.core.LivingFunctionConfig;
import com.qiqi.li.living.core.orchestrator.LivingOrchestrator;
import com.qiqi.li.living.container.ContainerContext;

/**
 * 活物品功能基类 —— 提供通用的 tick 编排和 Tooltip 实现。
 *
 * 子类只需实现：
 * - {@link #getConfig()}：返回功能配置（包含组件列表和编排器）
 * - {@link #canApply(ItemStack)}：判断物品是否适用此功能
 * - {@link #getFunctionId()}：返回功能 ID
 * - {@link #getTooltipTitleKey()}：返回 Tooltip 标题的翻译键
 *
 * tick 编排流程：
 * 1. 遍历每个活物品条目
 * 2. 加载/创建组件状态（{@link FunctionExecutor#loadOrCreateStates}）
 * 3. 解析槽位并创建上下文（{@link FunctionExecutor#buildContext}）
 * 4. 委托编排器执行组件编排（{@link LivingOrchestrator#orchestrate}）
 * 5. 保存状态回 NBT（{@link FunctionExecutor#saveStatesToStack}）
 * 6. 同步到客户端（{@link ContainerContext#syncSlotToClients}）
 *
 * 编排器选择：
 * - Orchestrators.SIMPLE：直接遍历组件 tick（适用于活漏斗）
 * - Orchestrators.PROGRESS：检查输入 → tick/pauseTick → 完成时转化（适用于活磨石）
 * - Orchestrators.FUEL_PROGRESS：检查燃料+输入 → tick/pauseTick → 完成时转化（适用于活熔炉）
 *
 * 使用示例：
 * <pre>
 * public class LivingHopperFunction extends BaseLivingFunction {
 *     private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
 *         .withFunctionId("living_hopper")
 *         .withOrchestrator(Orchestrators.SIMPLE)
 *         .addComponent(new DirectionModeComponent())
 *         .addComponent(new ItemTransferComponent());
 *
 *     &#64;Override protected LivingFunctionConfig getConfig() { return CONFIG; }
 *     &#64;Override public boolean canApply(ItemStack stack) { ... }
 *     &#64;Override public String getFunctionId() { return "living_hopper"; }
 *     &#64;Override protected String getTooltipTitleKey() { return "tooltip.livingitem.hopper.status"; }
 * }
 * </pre>
 */
public abstract class BaseLivingFunction implements LivingItemFunction {

    /**
     * 获取功能配置（包含组件列表、编排器、功能 ID 等）。
     * 通常返回静态配置实例，所有同类活物品共享同一份配置。
     */
    protected abstract LivingFunctionConfig getConfig();

    /**
     * 获取 Tooltip 标题的翻译键。
     * 例如 "tooltip.livingitem.hopper.status"、"tooltip.livingitem.furnace.status"。
     */
    protected abstract String getTooltipTitleKey();

    /**
     * 执行活物品的单次 tick。
     *
     * 通用编排流程：
     * 1. 遍历每个活物品条目
     * 2. 加载/创建组件状态
     * 3. 解析槽位并创建上下文
     * 4. 委托编排器执行组件编排
     * 5. 保存状态回 NBT
     * 6. 同步到客户端
     *
     * 子类通常不需要重写此方法。如果需要自定义编排逻辑，
     * 应通过 {@link LivingFunctionConfig#withOrchestrator} 选择合适的编排器，
     * 而非重写 tick()。
     */
    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, Level level) {
        if (level.isClientSide || entries.isEmpty()) return;

        FunctionExecutor fe = FunctionExecutor.INSTANCE;
        LivingFunctionConfig config = getConfig();

        for (SlotEntry entry : entries) {
            int slot = entry.slotIndex();
            ItemStack stack = entry.stack();

            if (slot < 0 || slot >= context.getSize()) continue;

            Map<String, ComponentState> states = fe.loadOrCreateStates(stack, config);
            ComponentContext ctx = fe.buildContext(config, states, context, slot, level);

            LivingOrchestrator orchestrator = config.getOrchestrator();
            if (orchestrator != null) {
                orchestrator.orchestrate(ctx, slot, stack, states, config, fe);
            } else {
                LivingOrchestrator.tickAllComponents(ctx, slot, stack, states, config, fe);
            }

            boolean anyDirty = false;
            for (ComponentState state : states.values()) {
                if (state.isDirty()) { anyDirty = true; break; }
            }
            if (anyDirty) {
                fe.saveStatesToStack(stack, config, states);
                states.values().forEach(ComponentState::clearDirty);
            }
            context.syncSlotToClients(slot, stack);
        }
    }

    /**
     * 追加功能状态信息到 Tooltip。
     *
     * 默认实现：
     * 1. 显示翻译标题（由 {@link #getTooltipTitleKey()} 指定）
     * 2. 遍历所有组件，调用各自的 appendTooltip()
     *
     * 子类可以重写此方法以自定义 Tooltip 显示。
     */
    @Override
    public void addToTooltip(net.minecraft.nbt.CompoundTag functionData,
                             net.minecraft.world.item.Item.TooltipContext context,
                             java.util.function.Consumer<net.minecraft.network.chat.Component> tooltipAdder,
                             net.minecraft.world.item.TooltipFlag flag) {
        if (functionData == null || functionData.isEmpty()) return;

        tooltipAdder.accept(net.minecraft.network.chat.Component.nullToEmpty(""));
        tooltipAdder.accept(net.minecraft.network.chat.Component.translatable(getTooltipTitleKey()));

        appendComponentTooltips(functionData, tooltipAdder, getConfig());
    }
}