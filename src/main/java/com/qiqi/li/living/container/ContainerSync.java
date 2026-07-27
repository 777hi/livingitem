package com.qiqi.li.living.container;

import net.minecraft.world.item.ItemStack;

/**
 * 容器同步能力 —— 提供客户端数据同步。
 *
 * <p>活物品 tick 修改 ItemStack 的 DataComponent 后，需要主动同步到客户端。
 * 因为原版 broadcastChanges() 依赖 ItemStack.matches() 检测变化，
 * 而 PatchedDataComponentMap.equals() 无法检测到自定义组件的变化，
 * 所以必须手动发送 ClientboundContainerSetSlotPacket。</p>
 *
 * <p>典型使用者：所有需要实时更新 tooltip 的功能类</p>
 */
public interface ContainerSync extends LivingContainer {

    /**
     * 将指定槽位的物品数据同步到所有正在查看该容器的客户端。
     *
     * @param logicalSlot 需要同步的槽位索引
     * @param stack 该槽位当前的 ItemStack（已包含最新的 DataComponent 数据）
     */
    void syncSlotToClients(int logicalSlot, ItemStack stack);
}