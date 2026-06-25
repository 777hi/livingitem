package com.qiqi.li.living;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;

public class LivingItemManager {
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG_LIVING = "living";
    private static final List<LivingItemFunction> FUNCTIONS = new ArrayList<>();

    public static void registerFunction(LivingItemFunction function) {
        LOGGER.info("Registering function: {}", function.getFunctionId());
        FUNCTIONS.add(function);
    }

    public static boolean isLivingItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        return customData != null && customData.copyTag().getBoolean(TAG_LIVING);
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

    public static CompoundTag getFunctionData(ItemStack stack, String functionId) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            CompoundTag fullTag = customData.copyTag();
            if (fullTag.contains(functionId, 10)) {
                return fullTag.getCompound(functionId);
            }
        }
        return new CompoundTag();
    }

    public static void setFunctionData(ItemStack stack, String functionId, CompoundTag data) {
        CustomData existingData = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag fullTag = existingData != null ? existingData.copyTag() : new CompoundTag();
        if (data.isEmpty()) {
            fullTag.remove(functionId);
        } else {
            fullTag.put(functionId, data);
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(fullTag));
    }

    public static String getContainerStableKey(Container container) {
        if (container instanceof Inventory inv) {
            return "player_" + inv.player.getStringUUID();
        }
        if (container instanceof BlockEntity be) {
            Level level = be.getLevel();
            BlockPos pos = be.getBlockPos();
            String dimKey = level != null ? level.dimension().location().toString() : "unknown";
            return "blockentity_" + dimKey + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
        }
        // 对于 ChestBlock.getContainer() 返回的组合容器，
        // 用它的 toString()/hashCode() 作为 fallback key
        return "container_" + Integer.toHexString(container.hashCode());
    }
}