package com.qiqi.li.living.core.accessor;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 活末影箱路由条目 —— 描述一个可用于无线传输的源物品位置。
 *
 * <p>不存储物品本身，只存储"指针"：物品类型 + 所在的维度/位置/槽位。
 * 活末影箱本质是路由器，物品始终留在源容器中。</p>
 *
 * @param itemType 物品注册名（如 "minecraft:diamond"）
 * @param sourceDim 源容器所在维度
 * @param sourcePos 源容器方块位置
 * @param sourceSlot 源物品在容器中的槽位索引
 * @param registrarSlot 注册此路由的活漏斗所在槽位，用于漏斗移走时清理路由
 */
public record EnderChannelEntry(
    String itemType,
    ResourceKey<Level> sourceDim,
    BlockPos sourcePos,
    int sourceSlot,
    int registrarSlot
) {}