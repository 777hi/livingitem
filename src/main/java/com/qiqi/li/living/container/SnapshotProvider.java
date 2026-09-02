package com.qiqi.li.living.container;

import java.util.Set;

/**
 * 容器快照贡献者 —— 由各 domain 注册，向 {@link MutableSnapshot} 填入自己关心的槽位级信息。
 *
 * <p>设计动机：原先 {@link ContainerSnapshot#capture} 直接 import 了
 * {@code domain.hopper}/{@code domain.chest} 等 domain 类（分层违规），
 * 且每次 tick 都重建快照。改为注册驱动后：
 * <ul>
 *   <li>{@code container} 包不再依赖任何 domain 类，仅认识本接口；</li>
 *   <li>每个 domain 在自己的包内实现贡献逻辑，注册到 {@link ContainerSnapshot}；</li>
 *   <li>快照由框架层按"容器修订计数"跨 tick 缓存（见 {@link ContainerLivingItemHandler}），
 *       物品未变更时直接复用，所有 domain 共享"编译一次"的结果。</li>
 * </ul>
 *
 * <p>贡献者通过 {@link TickContext#getFunctionSlots} 读取本 tick 的活跃槽位集合，
 * 与各自 domain 原先在 tick 内扫描物品得到的信息完全一致，保证行为等价。</p>
 */
public interface SnapshotProvider {

    /**
     * 向快照构造器填入本 domain 关心的槽位级数据。
     *
     * @param ctx    容器上下文（只读物品）
     * @param tick   本 tick 上下文（含已分组的活跃功能槽位）
     * @param containerSize 容器槽位数
     * @param width  容器列数（GUI 宽度）
     * @param builder 共享的快照构造器，多个贡献者依次填入不同字段
     */
    void contribute(ContainerContext ctx, TickContext tick, int containerSize, int width, MutableSnapshot builder);
}
