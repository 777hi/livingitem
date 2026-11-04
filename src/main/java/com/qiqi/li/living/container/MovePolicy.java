package com.qiqi.li.living.container;

/**
 * {@link ContainerContext#move} 的容量/权限口径 —— 两档，对应 `I-C12` 的
 * 「容量 / 准入 / 抽取权限三维度必须分别声明」。
 *
 * <p>⚠️ 这不是「松紧开关」，是<b>两个不同的问题域</b>：STANDARD 回答「容器愿不愿收」，
 * PHYSICAL 回答「物理上放不放得下」。流体推动是水流搬运不是漏斗插入
 * （A3 定案：不看准入，容量只问槽位上限；抽取权限读取侧是否跟随输出过滤器
 * 仍未拍板，见 TODO「物品传输」遗留②），所以它走 PHYSICAL ——
 * 这档完全保留流体的现行口径，不是给通用路径开的口子。</p>
 */
public enum MovePolicy {
    /**
     * 常规搬运：容量+准入问 {@code simulateInsertItem}（`I-C14①` 容量权威），
     * 抽取权限问 {@code simulateExtractItem}。
     */
    STANDARD,
    /**
     * 物理搬运（A3 特例，当前唯一用户 = 流体推动）：容量只问 {@code getSlotLimit − 现有}，
     * 不问准入与抽取权限（目标须空或同物品同组件）。
     */
    PHYSICAL
}
