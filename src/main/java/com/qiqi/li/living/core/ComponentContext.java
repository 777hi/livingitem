package com.qiqi.li.living.core;

import java.util.Map;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.ContainerContext;

public record ComponentContext(
    ContainerContext containerCtx,
    int inputSlot,
    int fuelSlot,
    int outputSlot,
    Level level,
    Map<String, ComponentState> allComponentStates
) {
    public boolean hasValidInput() {
        return inputSlot >= 0 && !containerCtx.getItem(inputSlot).isEmpty();
    }

    public boolean hasValidFuel() {
        return fuelSlot >= 0 && !containerCtx.getItem(fuelSlot).isEmpty();
    }

    public boolean hasValidOutput() {
        return outputSlot >= 0;
    }
    
    /**
     * 获取指定组件的状态（用于跨组件数据访问）
     */
    public ComponentState getComponentState(String componentId) {
        return allComponentStates != null ? allComponentStates.get(componentId) : null;
    }
}