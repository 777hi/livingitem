package com.qiqi.li.living.container;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;

/**
 * 容器上下文 —— 活物品功能与容器之间的完整交互接口。
 *
 * <p>这是一个组合接口，继承所有容器能力接口：
 * <ul>
 *   <li>{@link SlotInfoProvider} — 物品读写 + 槽位能力查询</li>
 *   <li>{@link ContainerSync} — 客户端数据同步</li>
 *   <li>{@link ContainerIdentity} — 容器身份标识 + 世界信息</li>
 * </ul>
 *
 * <p>注意：tick 级临时状态（槽位互斥、级联防护、快照、流体数据）
 * 已移至 {@link TickContext}，不再属于此接口。</p>
 *
 * <p>功能类应优先依赖最小接口（如 {@link SlotInfoProvider}），
 * 只有需要完整容器能力时才依赖此接口。</p>
 *
 * @see LivingContainer 基础物品读写
 * @see SlotInfoProvider 槽位能力查询
 * @see ContainerSync 客户端同步
 * @see ContainerIdentity 身份标识
 * @see TickContext Tick 级临时状态
 */
public interface ContainerContext extends SlotInfoProvider, ContainerSync, ContainerIdentity {

    default boolean isValidSlot(int logicalSlot) {
        return logicalSlot >= 0 && logicalSlot < getSize();
    }

    /**
     * 查询容器级持久数据（不创建，不存在返回 {@code null}）。
     *
     * <p>默认返回 {@code null} —— 不支持容器级数据的实现（如掉落物容器）用
     * 「返回 null」表达「我没有」，与 {@link TickableContainerContext} 的空实现哲学一致。</p>
     */
    default <T> T peekContainerData(ContainerDataKey<T> key) {
        return null;
    }

    /**
     * 查询或创建容器级持久数据。
     *
     * <p>默认实现<b>不存储</b>（创建后直接返回）—— 只有支持跨 tick 持久的实现
     * （{@code SimpleContainerContext}）才覆写它。</p>
     */
    default <T> T getOrCreateContainerData(ContainerDataKey<T> key) {
        return key.create();
    }

    /** 方向编码：与红电 EDGE_* 一致（0=上 1=下 2=左 3=右） */
    int E_UP = 0;
    int E_DOWN = 1;
    int E_LEFT = 2;
    int E_RIGHT = 3;

    /**
     * 解析网格相邻槽位（dir 取 {@link #E_UP}/{@link #E_DOWN}/{@link #E_LEFT}/{@link #E_RIGHT}），
     * 越界或换行返回 -1。
     *
     * <p>非分配版本，供热路径（红电逐方向传播、流体逐方向扩散）按方向调用，
     * 替代原先各 domain 自行实现的同义解析器（如红电私有的 resolveSlot），统一到框架层。</p>
     */
    static int resolveNeighbor(int slot, int dir, int size, int width) {
        int col = slot % width;
        int row = slot / width;
        int newCol = col;
        int newRow = row;
        switch (dir) {
            case E_UP:    newRow = row - 1; break;
            case E_DOWN:  newRow = row + 1; break;
            case E_LEFT:  newCol = col - 1; break;
            case E_RIGHT: newCol = col + 1; break;
            default: return -1;
        }
        if (newCol < 0 || newCol >= width || newRow < 0) return -1;
        int result = newRow * width + newCol;
        return result < size ? result : -1;
    }

    static int[] getNeighbors(int slot, int containerSize, int width) {
        int[] result = new int[4];
        int count = fillNeighbors(result, slot, containerSize, width);
        return count == 4 ? result : java.util.Arrays.copyOf(result, count);
    }

    /**
     * 邻居写入调用方提供的缓冲，**返回个数**（2026-10-07 性能收尾）。
     *
     * <p>流体引擎的内层循环（扩散 / 生长 / 前沿反应 / 晋升邻源计数）每格都要取四邻，
     * 原实现每次 {@code new int[count]} ⇒ 万级容器下每拍百万量级的小数组分配。
     * 改为<b>每个相位一个复用缓冲</b>（每容器每拍 1 次分配，而非每格 1 次）⇒ ~200× 减少分配。</p>
     *
     * <p>⚠️ <b>不要用全局/静态共享缓冲</b>：晋升相位会在循环内嵌套调用
     * （{@code countSourceNeighbors}）⇒ 共享缓冲会被覆盖（别名 bug）。
     * 每个相位各自持有一个缓冲即无此问题。</p>
     *
     * @param out 长度 ≥4 的缓冲
     * @return 写入的邻居个数（0..4），有效值在 {@code out[0..count-1]}
     */
    static int fillNeighbors(int[] out, int slot, int containerSize, int width) {
        int i = 0;
        if (slot % width > 0) out[i++] = resolveNeighbor(slot, E_LEFT, containerSize, width);
        if ((slot + 1) % width != 0) out[i++] = resolveNeighbor(slot, E_RIGHT, containerSize, width);
        if (slot >= width) out[i++] = resolveNeighbor(slot, E_UP, containerSize, width);
        if (slot + width < containerSize) out[i++] = resolveNeighbor(slot, E_DOWN, containerSize, width);
        return i;
    }

    /**
     * 取指定位置的原版容器。
     *
     * <p><b>⚠ 大箱子陷阱</b>：本方法返回的是该位置<b>单个方块实体</b>的容器 ——
     * 大箱子（双箱合并）只会拿到<b>一个半箱（27 槽）</b>，而 {@code IItemHandler}
     * 能力返回的是<b>合并后的 54 槽</b>。两者的槽位编号体系<b>错位 27 格</b>
     * （Container 的槽 22 = GUI 的槽 49）。
     *
     * <p>因此任何「用本方法读/写某个<b>逻辑槽位</b>」的调用点，都必须先跑
     * {@code ContainerContexts#isSameSlotSpace} 那样的<b>体系一致性探针</b>
     * （槽位数一致 + 单槽交叉校验）才可安全使用，否则一律回退到 IItemHandler。
     * 典型受害者见 {@code SimpleContainerContext.simulateInsertItem}
     * （活漏斗在大箱子里静默不传输，living-hopper-tech.md §10.25）。</p>
     *
     * <p><b>为什么不在本方法里直接返回合并容器</b>：合并顺序由各 mod 的
     * IItemHandler 决定（未必等于 vanilla 的 {@code ChestBlock.getContainer} 顺序），
     * 猜错会引入<b>新的</b>错位；探针是自适应判据，对三方块 / 四块 / 任意多方块容器
     * 同样成立，无需知道容器结构。若将来确有场景必须拿到「合并后的原版容器」，
     * 建议做成注册式扩展点（内置 Chest 实现 + 第三方可注册），而不是在这里特判。</p>
     */
    static Container getContainer(Level level, BlockPos pos) {
        if (pos == null) return null;
        if (level.getBlockEntity(pos) instanceof Container c) return c;
        return null;
    }
}