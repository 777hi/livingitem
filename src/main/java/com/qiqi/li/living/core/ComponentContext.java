package com.qiqi.li.living.core;

import java.util.Map;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.core.model.Pos2D;
import com.qiqi.li.living.core.components.DirectionModeComponent;

/**
 * 组件执行上下文 —— 为组件提供执行时所需的所有数据和引用。
 *
 * 由 FunctionExecutor 在每次 tick 时创建，包含：
 * - 容器操作接口（ContainerContext）
 * - 解析后的槽位索引（通过 ResolvedSlots 统一管理）
 * - 世界引用（Level，用于配方查询等）
 * - 所有组件的运行时状态（用于跨组件数据访问）
 *
 * 槽位索引说明：
 *   槽位索引由 DirectionModeComponent.resolveSlots() 解析得到，
 *   通过 ResolvedSlots 统一管理两种模式的槽位：
 *   - SLOTS 模式（活熔炉）：inputSlot / fuelSlot / outputSlot
 *   - TRANSFER 模式（活漏斗）：sourceSlot / targetSlot
 *   无效槽位统一为 -1。
 *
 * 跨组件数据访问：
 *   组件可以通过 getComponentState() 读取其他组件的状态。
 *   例如：ItemTransferComponent 读取 DirectionModeComponent 的状态获取传输方向。
 */
public record ComponentContext(
    ContainerContext containerCtx,
    DirectionModeComponent.ResolvedSlots resolvedSlots,
    Level level,
    Map<String, ComponentState> allComponentStates
) {
    /** 输入槽位索引（SLOTS 模式有效，-1 表示无效） */
    public int inputSlot() { return resolvedSlots.inputSlot(); }

    /** 燃料槽位索引（SLOTS 模式有效，-1 表示无效） */
    public int fuelSlot() { return resolvedSlots.fuelSlot(); }

    /** 输出槽位索引（SLOTS 模式有效，-1 表示无效） */
    public int outputSlot() { return resolvedSlots.outputSlot(); }

    /** 源槽位索引（TRANSFER 模式有效，-1 表示无效） */
    public int sourceSlot() { return resolvedSlots.sourceSlot(); }

    /** 目标槽位索引（TRANSFER 模式有效，-1 表示无效） */
    public int targetSlot() { return resolvedSlots.targetSlot(); }

    /** 检查输入槽位是否有效且有物品 */
    public boolean hasValidInput() {
        return inputSlot() >= 0 && !containerCtx.getItem(inputSlot()).isEmpty();
    }

    /** 检查燃料槽位是否有效且有物品 */
    public boolean hasValidFuel() {
        return fuelSlot() >= 0 && !containerCtx.getItem(fuelSlot()).isEmpty();
    }

    /** 检查输出槽位是否有效（不要求有物品） */
    public boolean hasValidOutput() {
        return outputSlot() >= 0;
    }

    /** 检查源槽位是否有效且有物品 */
    public boolean hasValidSource() {
        return sourceSlot() >= 0 && !containerCtx.getItem(sourceSlot()).isEmpty();
    }

    /** 检查目标槽位是否有效 */
    public boolean hasValidTarget() {
        return targetSlot() >= 0;
    }

    /**
     * 获取指定组件的状态（用于跨组件数据访问）。
     *
     * @param componentId 组件 ID（如 DirectionModeComponent.ID）
     * @return 组件状态，如果不存在返回 null
     */
    public ComponentState getComponentState(String componentId) {
        return allComponentStates != null ? allComponentStates.get(componentId) : null;
    }

    /** 源方向偏移（TRANSFER 模式有效，用于跨容器传输判断边界方向） */
    public Pos2D sourceOffset() { return resolvedSlots.sourceOffset(); }

    /** 目标方向偏移（TRANSFER 模式有效，用于跨容器传输判断边界方向） */
    public Pos2D targetOffset() { return resolvedSlots.targetOffset(); }
}