package com.qiqi.li.living.container;

/**
 * 容器种类 —— 「这是什么容器」的<b>类型化表示</b>（2026-10-13）。
 *
 * <h3>⭐ 为什么需要它：根治「字符串反推」</h3>
 * <p>容器身份在<b>产生处</b>（各 {@code *ContainerContext} 构造时）本来明确 ——
 * {@code EnderChestContainerContext} 构造时就知道自己是末影箱 —— 但它被压扁成字符串 key
 * （{@code player_<uuid>_ender_chest}），之后每个消费方只能靠 {@code startsWith} /
 * {@code substring} <b>反推</b>。反推规则各写各的 ⇒ 改键形、加键形、或某个消费方忘了跟，就漏。</p>
 *
 * <p>2026-10-13 一次性暴露 3 处（全部同源）：① {@code isJustInventoryMenu} 把「开着背包界面」
 * 误判为「没开界面」⇒ 背包 tooltip 不刷新；② 玩家态直发主人那条路（现 {@code ContainerViewers}
 * 的 {@code isOwnedByPlayer} 分支）的 UUID 解析不剥
 * {@code _ender_chest} 后缀 ⇒ 抛异常被吞 ⇒ 末影箱从不收包；③ 客户端按「是不是
 * InventoryScreen」推断容器类型 ⇒ 与键形判断不一致 ⇒ 读写分流错位。</p>
 *
 * <p>⇒ 本枚举让「种类」成为一等公民：<b>服务端在包里带上它</b>（{@code LivingItemSyncPacket}），
 * 客户端零推断；键形解析收敛到 {@link ContainerKeys} 唯一入口。以后新增容器种类 ⇒
 * 编译器的穷尽性检查会点名所有需要跟进的 {@code switch}。</p>
 *
 * <p>📌 与 {@code FluidFlowClientCache.RenderTarget} 同义（fluid 轨 2026-10-06 已用同款思路
 * 解决过同一个坑，见其类注释）。本枚举粒度更细（多出 {@link #ITEM_ENTITY}），
 * 两轨暂各自持有，未来若需要统一再提升为共用类型。</p>
 */
public enum ContainerKind {

    /** 玩家背包：{@code player_<uuid>}。 */
    PLAYER_INV,

    /** 玩家末影箱：{@code player_<uuid>_ender_chest}。 */
    ENDER_CHEST,

    /** 世界容器（方块 / 方块实体）：{@code chest_…}、{@code container_<dim>_…}（缓存键带 {@code <dim>|} 前缀）。 */
    WORLD_BLOCK,

    /** 掉落物容器：{@code item_entity_<entityUUID>}。 */
    ITEM_ENTITY,

    /**
     * 实体容器：{@code entity_<entityUUID>}（2026-10-21）。
     *
     * <p>覆盖箱子船 / 箱子矿车 / 漏斗矿车 / 带箱驴骡（{@code ContainerEntity} 或
     * {@code AbstractHorse.hasChest()}）。与 {@link #ITEM_ENTITY} 的区别：它有 GUI、
     * 需要 viewer 匹配 ⇒ 同步轨必须支持；掉落物两者都不需要。</p>
     */
    ENTITY,

    /**
     * 物品形态容器：{@code cuuid_<containerUUID>}（2026-10-22，活背包）。
     *
     * <p>覆盖精妙背包 / 潜影盒这类「本身是容器」的物品。与 {@link #ENTITY} 的共同点：
     * 身份是 UUID 而非位置 ⇒ 形态变化不漂移。与它的区别：<b>没有世界位置</b>
     * ⇒ {@code positionsOf} 为空、viewer 匹配走「玩家正看着的菜单」，
     * 持久层是 SavedData（按容器 UUID）而不是 BE 附件。</p>
     */
    ITEM_FORM;

    /**
     * 是否「属于某个玩家本人」⇒ 分派时<b>直发主人</b>（背包 / 末影箱）。
     *
     * <p>这两类没有 BE 实例可做 viewer 匹配（{@code isViewing} 对背包恒 false，
     * 那正是 v19.1「背包 tooltip 全 0」的成因）⇒ 只能按 UUID 直发本人。
     * 世界容器 / 掉落物走 viewer 匹配。</p>
     */
    public boolean isOwnedByPlayer() {
        return this == PLAYER_INV || this == ENDER_CHEST;
    }
}
