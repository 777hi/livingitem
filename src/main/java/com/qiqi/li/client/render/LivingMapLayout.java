package com.qiqi.li.client.render;

import com.qiqi.li.living.api.LivingItemManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LivingMapLayout {

    public static final int SLOT_SIZE = 18;
    public static final int SLOT_BORDER_OFFSET = 1;

    private LivingMapLayout() {}

    public static List<MapGroup> scan(List<Slot> slots) {
        List<SlotInfo> openedMaps = new ArrayList<>();
        Map<Long, SlotInfo> emptyMapGrid = new HashMap<>();

        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            ItemStack stack = slot.getItem();
            if (!LivingItemManager.isLivingItem(stack)) continue;

            if (isOpenedLivingMap(stack)) {
                openedMaps.add(new SlotInfo(i, slot.x, slot.y, stack));
            } else if (isEmptyLivingMap(stack)) {
                long key = gridKey(slot.x, slot.y);
                emptyMapGrid.put(key, new SlotInfo(i, slot.x, slot.y, stack));
            }
        }

        List<MapGroup> groups = new ArrayList<>();
        for (SlotInfo opened : openedMaps) {
            MapGroup group = tryExpand(opened, emptyMapGrid);
            if (group != null) {
                groups.add(group);
            }
        }
        return groups;
    }

    @Nullable
    private static MapGroup tryExpand(SlotInfo topLeft, Map<Long, SlotInfo> emptyGrid) {
        int rightCount = 0;
        for (int c = 1; ; c++) {
            long key = gridKey(topLeft.x + c * SLOT_SIZE, topLeft.y);
            if (!emptyGrid.containsKey(key)) break;
            rightCount = c;
        }

        int downCount = 0;
        for (int r = 1; ; r++) {
            long key = gridKey(topLeft.x, topLeft.y + r * SLOT_SIZE);
            if (!emptyGrid.containsKey(key)) break;
            downCount = r;
        }

        int n = Math.min(rightCount, downCount) + 1;
        if (n < 2) return null;

        for (int r = 1; r < n; r++) {
            for (int c = 1; c < n; c++) {
                long key = gridKey(topLeft.x + c * SLOT_SIZE, topLeft.y + r * SLOT_SIZE);
                if (!emptyGrid.containsKey(key)) return null;
            }
        }

        int[] slotIndices = new int[n * n];
        slotIndices[0] = topLeft.slotIndex;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (r == 0 && c == 0) continue;
                long key = gridKey(topLeft.x + c * SLOT_SIZE, topLeft.y + r * SLOT_SIZE);
                SlotInfo info = emptyGrid.get(key);
                slotIndices[r * n + c] = info.slotIndex;
            }
        }

        MapId mapId = topLeft.stack.get(DataComponents.MAP_ID);
        return new MapGroup(topLeft.slotIndex, n, topLeft.x, topLeft.y, mapId, slotIndices);
    }

    @Nullable
    public static MapGroup findGroupAt(List<MapGroup> groups, double mouseX, double mouseY, int leftPos, int topPos) {
        for (MapGroup group : groups) {
            double areaLeft = leftPos + group.x() - SLOT_BORDER_OFFSET;
            double areaTop = topPos + group.y() - SLOT_BORDER_OFFSET;
            double areaSize = group.n() * SLOT_SIZE;

            if (mouseX >= areaLeft && mouseX < areaLeft + areaSize
                && mouseY >= areaTop && mouseY < areaTop + areaSize) {
                return group;
            }
        }
        return null;
    }

    public static float[] computeUV(MapGroup group, double mouseX, double mouseY, int leftPos, int topPos) {
        double areaLeft = leftPos + group.x() - SLOT_BORDER_OFFSET;
        double areaTop = topPos + group.y() - SLOT_BORDER_OFFSET;
        double areaSize = group.n() * SLOT_SIZE;
        float u = (float) ((mouseX - areaLeft) / areaSize);
        float v = (float) ((mouseY - areaTop) / areaSize);
        u = Math.max(0f, Math.min(1f, u));
        v = Math.max(0f, Math.min(1f, v));
        return new float[]{u, v};
    }

    public static float[] computeSingleSlotUV(Slot slot, double mouseX, double mouseY, int leftPos, int topPos) {
        double areaLeft = leftPos + slot.x - SLOT_BORDER_OFFSET;
        double areaTop = topPos + slot.y - SLOT_BORDER_OFFSET;
        double areaSize = SLOT_SIZE;
        float u = (float) ((mouseX - areaLeft) / areaSize);
        float v = (float) ((mouseY - areaTop) / areaSize);
        u = Math.max(0f, Math.min(1f, u));
        v = Math.max(0f, Math.min(1f, v));
        return new float[]{u, v};
    }

    public static boolean isSingleLivingMapSlot(Slot slot) {
        ItemStack stack = slot.getItem();
        return isOpenedLivingMap(stack);
    }

    private static boolean isOpenedLivingMap(ItemStack stack) {
        return stack.is(Items.FILLED_MAP) && LivingItemManager.isLivingItem(stack) && stack.get(DataComponents.MAP_ID) != null;
    }

    private static boolean isEmptyLivingMap(ItemStack stack) {
        return stack.is(Items.MAP) && LivingItemManager.isLivingItem(stack);
    }

    private static long gridKey(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    public record MapGroup(
        int topLeftSlotIndex,
        int n,
        int x,
        int y,
        @Nullable MapId mapId,
        int[] slotIndices
    ) {
        public boolean containsSlot(int slotIndex) {
            for (int idx : slotIndices) {
                if (idx == slotIndex) return true;
            }
            return false;
        }
    }

    private record SlotInfo(int slotIndex, int x, int y, ItemStack stack) {}
}