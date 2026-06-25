package com.qiqi.li.living;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.HashMap;
import java.util.Map;

public class LivingDataManager {
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, CompoundTag> tempDataMap = new HashMap<>();

    private static String getContainerKey(Container container) {
        if (container instanceof Inventory inv) {
            return "player_" + inv.player.getStringUUID();
        }
        if (container instanceof BlockEntity be) {
            Level level = be.getLevel();
            BlockPos pos = be.getBlockPos();
            String dimKey = level != null ? level.dimension().location().toString() : "unknown";
            return "blockentity_" + dimKey + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
        }
        return "container_" + Integer.toHexString(container.hashCode());
    }

    public static String getSlotKey(Container container, int slot, String functionId) {
        return getContainerKey(container) + "_slot_" + slot + "_func_" + functionId;
    }

    public static CompoundTag getContainerItemData(Container container, int slotIndex, String functionId, Level level) {
        String key = getSlotKey(container, slotIndex, functionId);
        return tempDataMap.getOrDefault(key, new CompoundTag());
    }

    public static CompoundTag getOrCreateContainerItemData(Container container, int slotIndex, String functionId, Level level) {
        String key = getSlotKey(container, slotIndex, functionId);
        CompoundTag data = tempDataMap.get(key);
        if (data == null) {
            data = new CompoundTag();
            tempDataMap.put(key, data);
        }
        return data;
    }

    public static void setContainerItemData(Container container, int slotIndex, String functionId, CompoundTag data, Level level) {
        String key = getSlotKey(container, slotIndex, functionId);
        if (data.isEmpty()) {
            tempDataMap.remove(key);
        } else {
            tempDataMap.put(key, data);
        }
    }

    public static void clearContainerItemData(Container container, int slotIndex, String functionId, Level level) {
        setContainerItemData(container, slotIndex, functionId, new CompoundTag(), level);
    }
}