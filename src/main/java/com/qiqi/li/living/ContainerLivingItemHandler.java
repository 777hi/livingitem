package com.qiqi.li.living;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

public class ContainerLivingItemHandler {
    public static final Logger LOGGER = LogUtils.getLogger();

    public static void processContainer(Container container, Level level) {
        if (level.isClientSide) {
            return;
        }

        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (LivingItemManager.isLivingItem(stack)) {
                var functions = LivingItemManager.getApplicableFunctions(stack);
                for (var function : functions) {
                    function.tick(stack, i, container, level);
                }
            }
        }
    }
}