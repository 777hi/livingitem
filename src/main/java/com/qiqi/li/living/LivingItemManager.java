package com.qiqi.li.living;

import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;

public class LivingItemManager {
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final List<LivingItemFunction> FUNCTIONS = new ArrayList<>();

    public static void registerFunction(LivingItemFunction function) {
        LOGGER.info("Registering function: {}", function);
        FUNCTIONS.add(function);
    }

    public static boolean isLivingItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return false;
        }
        CompoundTag tag = customData.getUnsafe();
        return tag.getBoolean("living");
    }

    public static List<LivingItemFunction> getApplicableFunctions(ItemStack stack) {
        List<LivingItemFunction> applicable = new ArrayList<>();
        for (LivingItemFunction function : FUNCTIONS) {
            if (function.canApply(stack)) {
                applicable.add(function);
            }
        }
        return applicable;
    }
}