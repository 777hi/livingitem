package com.qiqi.li.living.container;

import java.util.Arrays;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.core.SlotResolver;
import com.qiqi.li.living.core.components.DirectionModeComponent;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.model.SlotMapping;
import com.qiqi.li.living.function.LivingHopperFunction;

/**
 * 容器快照 —— 每 tick 构建一次，供所有组件共享的容器扫描结果。
 *
 * 设计理念：
 * 多个活物品组件可能都需要扫描容器获取信息（如 ItemFilterComponent 需要
 * 活漏斗连接图，未来的活熔炉组件可能需要输入/燃料/输出槽位映射）。
 * 与其每个组件各自扫描一次，不如在 {@link ContainerLivingItemHandler#processContext}
 * 中统一扫描一次，构建快照供所有组件复用。
 *
 * 生命周期：
 * - 由 {@link ContainerLivingItemHandler#processContext} 在每次容器 tick 时构建
 * - 存储在 {@link ContainerContext} 中，通过 {@link ContainerContext#getSnapshot()} 访问
 * - 每 tick 自动失效（新 tick 构建新快照）
 *
 * 当前包含的信息：
 * - 活漏斗连接图（sourceOf / targetOf 数组）
 *
 * 扩展方式：
 * - 未来需要新的容器扫描信息时，在此类中添加字段和构建方法
 */
public class ContainerSnapshot {

    private final int containerSize;
    private final int[] sourceOf;
    private final int[] targetOf;

    private ContainerSnapshot(int containerSize, int[] sourceOf, int[] targetOf) {
        this.containerSize = containerSize;
        this.sourceOf = sourceOf;
        this.targetOf = targetOf;
    }

    /**
     * 从容器上下文构建快照。
     *
     * 扫描容器中所有槽位，识别活漏斗并构建连接关系图。
     */
    public static ContainerSnapshot capture(ContainerContext context) {
        int containerSize = context.getSize();
        int[] sourceOf = new int[containerSize];
        int[] targetOf = new int[containerSize];
        Arrays.fill(sourceOf, -1);
        Arrays.fill(targetOf, -1);

        DirectionModeComponent dirComp = new DirectionModeComponent();

        for (int slot = 0; slot < containerSize; slot++) {
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            if (!LivingHopperFunction.isLivingHopper(stack)) continue;

            ComponentState dirState = DirectionModeComponent.readStateFromStack(
                stack, LivingHopperFunction.ID);
            if (dirState == null) continue;

            SlotMapping mapping = dirComp.getCurrentMapping(dirState);
            if (mapping == null) continue;

            sourceOf[slot] = SlotResolver.resolve(slot, mapping.sourceOffset(), containerSize);
            targetOf[slot] = SlotResolver.resolve(slot, mapping.targetOffset(), containerSize);
        }

        return new ContainerSnapshot(containerSize, sourceOf, targetOf);
    }

    public int getContainerSize() {
        return containerSize;
    }

    public int[] getSourceOf() {
        return sourceOf;
    }

    public int[] getTargetOf() {
        return targetOf;
    }

    public int getSourceOf(int slot) {
        return slot >= 0 && slot < containerSize ? sourceOf[slot] : -1;
    }

    public int getTargetOf(int slot) {
        return slot >= 0 && slot < containerSize ? targetOf[slot] : -1;
    }
}