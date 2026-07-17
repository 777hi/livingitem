package com.qiqi.li.living;

import java.util.*;
import java.util.stream.Collectors;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.components.InternalStorageComponent;

/**
 * 活箱子 UUID 列表工具类。
 *
 * 核心功能：
 * 1. UUID 列表标准化排序（保证相等判定一致）
 * 2. UUID 列表合并（自动去重）
 *
 * 4. 数据一致性校验
 *
 * 拆分/合并的 UUID 分配由 {@code ItemStackMixin.onSplit()} 在 split() 调用时即时处理，
 * 不再依赖下一 tick 的容器扫描。
 */
public class LivingChestStackHandler {

    /**
     * 标准化 UUID 列表：按 UUID 自然顺序排序，保证「集合相同则列表完全相同」
     * 解决原版有序列表比较导致的"元素相同但顺序不同无法堆叠"问题
     */
    public static List<UUID> normalizeUuidList(List<UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) return List.of();
        return uuids.stream()
                .sorted()
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 合并两个 UUID 集合，自动去重兜底，返回标准化后的总列表
     */
    public static List<UUID> mergeUuidLists(List<UUID> listA, List<UUID> listB) {
        Set<UUID> merged = new HashSet<>();
        if (listA != null) merged.addAll(listA);
        if (listB != null) merged.addAll(listB);
        return normalizeUuidList(new ArrayList<>(merged));
    }

    /**
     * 拆分 UUID 列表，按顺序截取。
     * @param sourceUuids 原 UUID 列表
     * @param splitCount  要拆分出的数量
     * @return 拆分结果：remain = 剩余的 UUID（前 N 个），split = 拆分出的 UUID（后 M 个）
     */
    public static SplitResult splitUuidList(List<UUID> sourceUuids, int splitCount) {
        if (sourceUuids == null || sourceUuids.isEmpty()) {
            return new SplitResult(List.of(), List.of());
        }
        if (splitCount <= 0) {
            return new SplitResult(new ArrayList<>(sourceUuids), List.of());
        }
        if (splitCount >= sourceUuids.size()) {
            return new SplitResult(List.of(), new ArrayList<>(sourceUuids));
        }

        int remainCount = sourceUuids.size() - splitCount;
        List<UUID> remainPart = new ArrayList<>(sourceUuids.subList(0, remainCount));
        List<UUID> splitPart = new ArrayList<>(sourceUuids.subList(remainCount, sourceUuids.size()));

        return new SplitResult(remainPart, splitPart);
    }

    /**
     * 数据一致性校验：物品数量必须等于 UUID 数量
     */
    public static boolean isConsistent(ItemStack stack) {
        List<UUID> uuids = getUuids(stack);
        return stack.getCount() == uuids.size();
    }

    /**
     * 获取活箱子的 UUID 列表
     */
    public static List<UUID> getUuids(ItemStack stack) {
        ComponentState state = getStorageState(stack);
        return InternalStorageComponent.getUuids(state);
    }

    /**
     * 写入 UUID 列表到物品（通过 ComponentState）
     */
    public static void setUuids(ItemStack stack, List<UUID> uuids) {
        ComponentState state = getStorageState(stack);
        List<UUID> normalized = normalizeUuidList(uuids);
        InternalStorageComponent.saveUuids(state, normalized);
        saveToStack(stack, state);
    }

    /** 拆分结果记录 */
    public record SplitResult(List<UUID> remain, List<UUID> split) {}

    private static ComponentState getStorageState(ItemStack stack) {
        return LivingChestFunction.getStorageState(stack);
    }

    private static void saveToStack(ItemStack stack, ComponentState state) {
        CompoundTag funcData = LivingItemManager.getFunctionData(stack, LivingChestFunction.ID).copy();
        funcData.put(InternalStorageComponent.ID, state.toNBT());
        LivingItemManager.setFunctionData(stack, LivingChestFunction.ID, funcData);
    }
}