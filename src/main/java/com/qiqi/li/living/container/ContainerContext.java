package com.qiqi.li.living.container;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 容器上下文 —— 活物品功能与容器之间的交互接口。
 *
 * 提供以下能力：
 * 1. 查询容器大小和槽位最大堆叠数
 * 2. 读取/修改容器中的物品
 * 3. 为槽位生成稳定 key（用于缓存和状态关联）
 * 4. 同步数据到客户端（tooltip 实时更新）
 * 5. 获取容器的方块位置（用于爆炸等需要世界坐标的功能）
 *
 * 同步策略：
 *   活物品 tick 修改 ItemStack 的 DataComponent 后，需要主动同步到客户端。
 *   因为原版 broadcastChanges() 依赖 ItemStack.matches() 检测变化，
 *   而 PatchedDataComponentMap.equals() 无法检测到自定义组件的变化，
 *   所以必须手动发送 ClientboundContainerSetSlotPacket。
 *
 *   同步时需要遍历所有正在查看该容器的玩家，向每个玩家发送更新包。
 *   使用正确的 stateId（从 containerMenu 获取）确保客户端接受更新。
 */
public interface ContainerContext {

    int getSize();

    ItemStack getItem(int logicalSlot);

    void setItem(int logicalSlot, ItemStack stack);

    int getMaxStackSize();

    String getStableKey(int logicalSlot, String functionId);

    default boolean isValidSlot(int logicalSlot) {
        return logicalSlot >= 0 && logicalSlot < getSize();
    }

    /**
     * 获取本 tick 的已占用槽位集合。
     *
     * 用于跨 Function 的槽位互斥：确保同一个输入槽位不会被多个活熔炉同时处理。
     * 由 ContainerLivingItemHandler 在每次容器 tick 时创建并传入。
     *
     * @return 已占用槽位的 key 集合，如果未初始化返回 null
     */
    default Set<String> getOccupiedSlots() {
        return null;
    }

    /**
     * 获取本 tick 已被传输物品到达的槽位集合。
     *
     * 用于防止同 tick 内级联传输：当多个活漏斗组成链时，
     * 前面的活漏斗把物品放到目标槽位后，后面的活漏斗不应该
     * 在同一 tick 内继续把这个物品往下传。
     *
     * 没有此机制时，上传下方向的漏斗链会瞬间传到底（因为扫描顺序与传输方向一致），
     * 而下传上方向的漏斗链只能一格一格传（因为扫描顺序与传输方向相反），
     * 导致方向性行为不一致。
     *
     * @return 本 tick 已被传输到达的槽位索引集合，如果未初始化返回 null
     */
    default Set<Integer> getTransferredTargetSlots() {
        return null;
    }

    /**
     * 将指定槽位的物品数据同步到所有正在查看该容器的客户端。
     *
     * @param logicalSlot 需要同步的槽位索引
     * @param stack 该槽位当前的 ItemStack（已包含最新的 DataComponent 数据）
     */
    void syncSlotToClients(int logicalSlot, ItemStack stack);

    /**
     * 获取容器的方块位置。
     *
     * 用于爆炸等需要世界坐标的功能。对于世界容器（箱子等），
     * 返回容器方块的坐标；对于玩家背包等非方块容器，返回 null。
     *
     * 如果容器是大箱子，返回第一个关联方块的坐标。
     *
     * @return 容器的方块位置，如果不是方块容器返回 null
     */
    default BlockPos getBlockPos() {
        return null;
    }

    /**
     * 获取容器所在的世界。
     *
     * 用于跨容器传输等需要访问相邻容器的功能。
     * 对于世界容器（箱子等），返回容器方块所在的 Level；
     * 对于玩家背包等非方块容器，返回 null。
     *
     * @return 容器所在的世界，如果不是方块容器返回 null
     */
    default Level getLevel() {
        return null;
    }

    /**
     * 获取底层的 Container 实例。
     *
     * 用于跨容器传输时检查相邻容器是否与当前容器是同一个实例（防止大箱子内部传输）。
     *
     * @return 底层的 Container 对象，如果不可用返回 null
     */
    default net.minecraft.world.Container getContainer() {
        return null;
    }
    /**
     * 获取容器的列数（GUI 宽度）。
     *
     * 用于槽位解析时计算行列位置。标准容器为 9 列，
     * 模组容器可能有不同的列数（如 13 列）。
     *
     * @return 容器的列数，默认 9
     */
    default int getWidth() {
        return 9;
    }

    /**
     * 获取本 tick 的容器快照。
     *
     * 由 {@link ContainerLivingItemHandler#processContext} 在每次容器 tick 时构建，
     * 包含预扫描的容器信息（如活漏斗连接图），供所有组件复用。
     *
     * @return 容器快照，如果尚未构建返回 null
     */
    default ContainerSnapshot getSnapshot() {
        return null;
    }

    /**
     * 设置本 tick 的容器快照。
     *
     * @param snapshot 容器快照
     */
    default void setSnapshot(ContainerSnapshot snapshot) {
    }}