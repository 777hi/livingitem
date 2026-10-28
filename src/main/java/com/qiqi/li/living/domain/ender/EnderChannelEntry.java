package com.qiqi.li.living.domain.ender;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 活末影箱路由条目 —— 描述一个可用于无线传输的源物品位置。
 *
 * <p>不存储物品本身，只存储"指针"：物品类型 + 所在的维度/位置/槽位。
 * 活末影箱本质是路由器，物品始终留在源容器中。</p>
 *
 * <p>支持两种容器类型：</p>
 * <ul>
 *   <li><strong>方块容器</strong>：sourcePos 非 null，containerKey 为基于位置的 key</li>
 *   <li><strong>玩家背包</strong>：sourcePos 为 null，containerKey 为 "player_&lt;uuid&gt;"</li>
 * </ul>
 *
 * @param itemType 物品注册名（如 "minecraft:diamond"）
 * @param sourceDim 源容器所在维度
 * @param sourcePos 源容器方块位置（玩家背包时为 null）
 * @param sourceSlot 源物品在容器中的槽位索引
 * @param registrarSlot 注册此路由的活漏斗所在槽位，用于漏斗移走时清理路由
 * @param containerKey 容器唯一标识 key（来自 ContainerContext.getContainerKey()）
 * @param targetSlot 活末影箱所在槽位，用于末影箱移走时清理路由（-1 表示无关联末影箱）
 * @param registrarContainerKey 注册者（活漏斗/活末影箱）所在容器的唯一标识 key，
 *                               用于 removeStaleEnderChestRoutes 按容器隔离清理
 */
public record EnderChannelEntry(
    String itemType,
    ResourceKey<Level> sourceDim,
    BlockPos sourcePos,
    int sourceSlot,
    int registrarSlot,
    String containerKey,
    int targetSlot,
    String registrarContainerKey,
    /**
     * 源容器的<b>跨形态稳定身份</b>（容器 UUID）；{@code null} = 该容器没有身份。
     *
     * <p>方块形态从 BE 附件读、物品形态从物品组件读，两边是<b>同一个值</b>
     * （{@code ContainerHandoff} 搬的就是它）⇒ 它是唯一能把「同一容器的两种形态」
     * 关联起来的键。{@link EnderChannelRegistry#validateRoutes} 靠它看见
     * 「按位置/按键都查不到」的旧形态条目（2026-10-29：两种形态并存 ⇒ 双抽 ⇒ 刷物品）。</p>
     */
    java.util.UUID sourceContainerUuid
) {}