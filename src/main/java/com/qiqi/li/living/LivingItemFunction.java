package com.qiqi.li.living;

import java.util.List;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

public interface LivingItemFunction {

    record SlotEntry(int slotIndex, ItemStack stack) {}

    boolean canApply(ItemStack stack);

    String getFunctionId();

    /**
     * 执行功能 tick 逻辑。
     *
     * @param entries 拥有此功能的活物品列表（槽位+物品）
     * @param container 容器上下文（提供容器能力）
     * @param tick Tick 级上下文（提供临时状态）
     * @param level 世界
     */
    void tick(List<SlotEntry> entries, ContainerContext container, TickContext tick, Level level);

    default void addToTooltip(net.minecraft.world.item.Item.TooltipContext context,
                              java.util.function.Consumer<net.minecraft.network.chat.Component> tooltipAdder,
                              net.minecraft.world.item.TooltipFlag flag,
                              ItemStack stack) {
    }

    default Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }
}