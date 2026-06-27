package com.qiqi.li.living;

import net.minecraft.world.item.ItemStack;

/**
 * 容器上下文 —— 活物品功能与容器之间的交互接口。
 *
 * 提供以下能力：
 * 1. 查询容器大小和槽位最大堆叠数
 * 2. 读取/修改容器中的物品
 * 3. 为槽位生成稳定 key（用于缓存和状态关联）
 * 4. 同步数据到客户端（tooltip 实时更新）
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
     * 将指定槽位的物品数据同步到所有正在查看该容器的客户端。
     *
     * @param logicalSlot 需要同步的槽位索引
     * @param stack 该槽位当前的 ItemStack（已包含最新的 DataComponent 数据）
     */
    void syncSlotToClients(int logicalSlot, ItemStack stack);
}