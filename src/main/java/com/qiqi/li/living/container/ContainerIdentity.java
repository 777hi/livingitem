package com.qiqi.li.living.container;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import java.util.Collections;
import java.util.List;

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
     * 获取容器的所有关联方块位置。
     *
     * <p>对于大箱子等由多个方块组成的容器，返回所有关联方块的坐标；
     * 对于单方块容器，返回只包含一个位置的列表；
     * 对于非方块容器，返回空列表。</p>
     *
     * @return 所有关联方块位置，不会为 null
     */
    default List<BlockPos> getAssociatedBlockPositions() {
        BlockPos pos = getBlockPos();
        return pos != null ? Collections.singletonList(pos) : Collections.emptyList();
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

    /**
     * 容器**缓存键** —— 容器级数据在各静态表里的行号：
     * {@code ContainerRuntimeCache}（燃料 / 进度 / 冷却 / 遥测）、
     * {@code ContainerLivingItemHandler} 的 {@code CONTAINER_DATA}（流体 / 红石 / 电力）、
     * {@code LIVING_SLOT_ITEMS}（槽位追踪）、{@code POS_TO_CACHE_KEY}（位置索引）。
     *
     * <p><b>规则</b>：身份锚在<b>世界方块</b>上的容器 ⇒ {@code <维度>|<稳定键>}
     * （如 {@code minecraft:overworld|chest_12_64_34}，大箱子带两段坐标）；
     * 身份锚在<b>玩家 / 实体 UUID</b> 上的（背包 / 末影箱 / 掉落物）⇒ 原样返回稳定键。</p>
     *
     * <p><b>为什么世界容器必须带维度</b>：主世界与下界允许存在<b>同坐标</b>的两个容器
     * （如都在 (0,64,0) 放一个箱子）⇒ 裸坐标键会让两个容器共用一条缓存数据：
     * 燃料 / 进度互相顶，{@code trackLivingSlots} 每 tick 互相覆盖历史记录 ⇒
     * 只要两边槽位物品不同，每 tick 都互相登记作废 ⇒ 进度永远攒不起来。
     * 2026-08-22 已给 {@code CONTAINER_DATA} 补过前缀（changelog「容器级数据缓存键
     * 补齐维度」）；2026-10-12 发现运行时缓存与槽位追踪一直用裸键，那时留下的
     * 一半范式在此补全。</p>
     *
     * <p><b>为什么玩家容器不能带</b>：一个玩家同一时刻只在一个维度，背包数据
     * <b>必须跟着人走</b>（过传送门不该清零身上活熔炉的燃料）；UUID 本身已全局唯一。
     * （反例：{@code cacheKey} 曾给背包键也加维度 ⇒ 换维度即丢容器级数据，
     * 因流体有 Player attachment 自愈而没人踩到；本规则顺手修掉它。）</p>
     *
     * <p>⚠️ 与 {@link #getContainerKey()} 的分工：稳定键是<b>身份</b>（格式简单、可解析 ——
     * 末影箱显式键、{@code LivingItemRuntimeSync} 的 {@code player_} 前缀分支、
     * {@code LivingItem.parsePosFromContainerKey} 都依赖它）；缓存键是<b>表内行号</b>
     * （只进静态 Map 与网络包载荷，不带出模组）。<b>别把缓存键当身份用</b>。</p>
     *
     * <p>拿不到维度（{@code getLevel() == null} 或 {@code dimension() == null}，
     * 如单元测试替身）⇒ 原样返回稳定键（退化为加前缀前的行为，测试零改动）。</p>
     */
    default String getCacheKey() {
        return cacheKeyOf(this);
    }

    /**
     * {@link #getCacheKey()} 的计算内核（抽出来供 {@code SimpleContainerContext} 做
     * memo 覆写时复用，避免它去调 {@code Xxx.super.getCacheKey()} 这种别扭形式）。
     */
    static String cacheKeyOf(ContainerIdentity ctx) {
        String key = ctx.getContainerKey();
        if (key == null) return null;
        // 世界锚定判据 = 有关联方块位置。生产路径里方块容器必有 positions
        // （resolveContextAt / ContainerContexts.beContext 都填），
        // 背包 / 末影箱 / 掉落物三者为空 ⇒ 走 UUID 裸键。
        if (ctx.getAssociatedBlockPositions().isEmpty()) return key;
        Level level = ctx.getLevel();
        if (level == null) return key;
        ResourceKey<Level> dim = level.dimension();
        if (dim == null) return key;   // mock / 测试替身：无维度信息
        return dim.location() + "|" + key;
    }
}