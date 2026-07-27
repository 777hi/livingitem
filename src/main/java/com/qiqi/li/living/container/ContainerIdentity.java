package com.qiqi.li.living.container;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * 容器身份标识 —— 提供容器唯一标识和世界信息。
 *
 * <p>适用于需要跨容器操作或访问世界坐标的功能：
 * <ul>
 *   <li>活末影箱 — 路由表需要容器唯一标识</li>
 *   <li>活TNT — 爆炸需要方块坐标</li>
 *   <li>活漏斗 — 跨容器传输需要访问相邻容器</li>
 * </ul>
 * </p>
 */
public interface ContainerIdentity {

    /**
     * 获取容器的唯一标识 key。
     *
     * <p>用于活末影箱路由表等需要跨容器标识的场景。
     * 对于方块容器，返回基于位置的 key（如 "chest_0_64_0"）；
     * 对于玩家背包，返回 "player_&lt;uuid&gt;"；
     * 对于未知容器，返回基于 hashCode 的 key。</p>
     *
     * @return 容器唯一标识 key，如果不支持返回 null
     */
    String getContainerKey();

    /**
     * 生成槽位的稳定 key，用于缓存和状态关联。
     *
     * @param logicalSlot 槽位索引
     * @param functionId 功能 ID
     * @return 稳定的槽位 key
     */
    String getStableKey(int logicalSlot, String functionId);

    /**
     * 获取容器的方块位置。
     *
     * <p>用于爆炸等需要世界坐标的功能。对于世界容器（箱子等），
     * 返回容器方块的坐标；对于玩家背包等非方块容器，返回 null。
     * 如果容器是大箱子，返回第一个关联方块的坐标。</p>
     *
     * @return 容器的方块位置，如果不是方块容器返回 null
     */
    default BlockPos getBlockPos() {
        return null;
    }

    /**
     * 获取容器所在的世界。
     *
     * <p>用于跨容器传输等需要访问相邻容器的功能。
     * 对于世界容器（箱子等），返回容器方块所在的 Level；
     * 对于玩家背包等非方块容器，返回 null。</p>
     *
     * @return 容器所在的世界，如果不是方块容器返回 null
     */
    default Level getLevel() {
        return null;
    }
}