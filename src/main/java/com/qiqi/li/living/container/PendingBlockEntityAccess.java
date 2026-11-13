package com.qiqi.li.living.container;

import net.minecraft.core.BlockPos;

/** loaded 区块移除尚未实例化的方块实体数据；实现不得触发实例化。 */
public interface PendingBlockEntityAccess {

    boolean living_item$removePendingBlockEntity(BlockPos pos);
}
