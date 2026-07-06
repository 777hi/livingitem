package com.qiqi.li.living.core.components;

import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.ContainerContext;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;

public interface ILivingComponent {

    String getComponentId();

    void tick(ComponentContext context, int hostSlot, ItemStack hostStack,
              ComponentState state, ComponentConfig config);

    ComponentState createDefaultState();

    default void appendTooltip(ComponentState state, Consumer<Component> tooltipAdder) {}
}