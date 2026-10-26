package com.qiqi.li.living.container;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.attachment.IAttachmentHolder;

import com.qiqi.li.logging.ModLog;

/**
 * 容器级数据的「<b>第三方交接</b>」通道（2026-10-21）—— **双向**。
 *
 * <h3>解决什么</h3>
 * <p>容器级持久数据（流体 / 电力相位）的迁移原本只有两个挂点：
 * {@code BlockDropsEvent}（破坏）与 {@code EntityPlaceEvent}（放置）。
 * 但有些模组<b>自己搬走容器、自己放置容器</b>，两个事件都不给 —— 已知第一例是精妙背包：
 * 拾取走 {@code putInPlayersHandAndRemove}（{@code setItemInHand} + {@code removeBlock}，
 * 不产生掉落物）；放置走 {@code BackpackItem.tryPlace}（**不是 {@code BlockItem}**，
 * 自己 {@code setBlockAndUpdate}，绕开 {@code BlockItem.place}）⇒ 数据随 BE 蒸发。</p>
 *
 * <p>⇒ 本类提供<b>第三个挂点</b>：让「发现时机」与「搬什么」解耦，且**两个方向对称**。</p>
 *
 * <h3>两对时机 + 一个注册口</h3>
 * <pre>{@code
 *   宿主 → 物品（第三方把方块变成物品）：
 *     capture(host)      —— 宿主还在、数据还活着（第三方移除方块之前）
 *     handoff(stack)     —— 目标物品已存在（第三方把物品塞进玩家手里之后）
 *
 *   物品 → 宿主（第三方把物品变成方块）：
 *     stageRestore(stack) —— 物品即将被放置（**必须在它被 shrink 之前**）
 *     restoreTo(host)     —— 方块已落地、BE 已创建
 *
 *   registerMover        —— 各领域声明「怎么搬」（两个方向都要实现，见 Mover 的 javadoc）
 * }</pre>
 *
 * <p>捕获到的是<b>宿主引用</b>而不是数据快照：宿主对象在第三方移除方块后仍然存活
 * （只是从 level 的 BE 表里摘掉），由本类的 ThreadLocal 持有强引用 ⇒ 交接阶段照样能读附件。
 * 这样「搬」的逻辑只需写一份，且必须与 {@code BlockDropsEvent} 路径<b>共用同一份</b>
 * （否则两条路径的语义会漂）。</p>
 *
 * <h3>为什么用 ThreadLocal</h3>
 * <p>两个时机在<b>同一线程、同一调用栈</b>里（mixin 的 HEAD → 目标方法 → RETURN），
 * 中间不跨 tick、不跨线程 ⇒ ThreadLocal 是零分配的正确载体。
 * 每次 {@link #capture} / {@link #stageRestore} 先清空 ⇒ 即使上一次的 RETURN 没执行
 * （目标方法抛异常），也不会串味。</p>
 *
 * <h3>⚠️ 边界</h3>
 * <ul>
 *   <li><b>不判断该不该搬</b>：{@code contentRetaining} 判据在各领域 Mover 内部（与事件路径同一处），
 *       本类只负责「把时机接上」。</li>
 *   <li><b>不保证搬成功</b>：目标物品可能是空栈（第三方没给物品）⇒ 直接丢弃捕获，返回 false。</li>
 *   <li><b>单个 Mover 抛异常不影响其它 Mover</b>：逐个 try/catch 并记日志
 *       （I-C7「不得静默继续」——但也不能因为一个领域出错就让别的领域跟着丢数据）。</li>
 *   <li><b>重复注册会重复调用</b>：按设计每个领域注册一次（在 {@code *Registration.java}，R4）；
 *       没有按「领域身份」去重 —— 那是过度设计，重复注册的后果也只是第二个 Mover 读到已清空的数据。</li>
 * </ul>
 */
public final class ContainerHandoff {

    /**
     * 领域声明「怎么搬」。**两个方向都必须实现**。
     *
     * <p>🔴 <b>这里曾经是 {@code default void moveIn(...) {}}</b>（2026-10-21 真机四轮定位的真凶）：
     * 注册处写的是方法引用 {@code registerMover(ContainerDataMigration::moveOut)} ——
     * 方法引用<b>只覆盖它指向的那个方法</b>，另一个方法自动落到 default 空实现
     * ⇒ 编译通过、启动正常、`handoff` 打日志说 `moved=true`，但<b>放置方向永远什么都不做</b>。
     * 现象是「拾取后数据确实进了物品，放回去却没了」，且所有日志都显示成功。</p>
     *
     * <p>⇒ 教训：<b>注册点用方法引用时，接口里不能留「可选实现」</b>。
     * 去掉 default 后，漏实现会在<b>编译期</b>报错（匿名类必须实现全部抽象方法），
     * 而不是在真机上表现为静默丢数据。</p>
     */
    public interface Mover {
        /**
         * 把 {@code host} 上的容器级持久数据搬到 {@code target} 物品上，并清空 {@code host}。
         *
         * <p>语义与 {@code BlockDropsEvent} 路径一致：只对 {@code contentRetaining} 声明的容器搬；
         * 可变对象必须给快照（否则物品与宿主共享引用）。</p>
         */
        void moveOut(IAttachmentHolder host, ItemStack target);

        /**
         * 反方向：把 {@code source} 物品上的容器级数据恢复进 {@code host}。
         *
         * <p>语义与 {@code EntityPlaceEvent} 路径一致（只读不删源：创造模式下同一个栈可连续放置）。</p>
         */
        void moveIn(IAttachmentHolder host, ItemStack source);
    }

    private static final List<Mover> MOVERS = new CopyOnWriteArrayList<>();

    /** 测试清理口：{@code ContainerHandoffTest} 的替身 Mover 注册进进程级静态表，用例间须清。 */
    static void clearMoversForTest() {
        MOVERS.clear();
    }

    /** 待交接的宿主。{@code null} 与「没有捕获」同义（{@link ThreadLocal} 不允许存 null）。 */
    private static final ThreadLocal<IAttachmentHolder> PENDING = new ThreadLocal<>();

    /**
     * 待恢复的容器物品（放置方向）。
     *
     * <p>⚠️ 存的是 {@link ItemStack#copy()} 而不是原栈：精妙的 {@code tryPlace} 会在
     * {@code shrink(1)} 里<b>就地改</b>手持栈（同一对象），存引用会在 RETURN 时读到空栈。</p>
     */
    private static final ThreadLocal<ItemStack> PENDING_RESTORE = new ThreadLocal<>();

    private ContainerHandoff() {}

    /**
     * 注册一个领域的搬运器。**调用点必须在 {@code *Registration.java}**（R4）。
     */
    public static void registerMover(Mover mover) {
        MOVERS.add(mover);
    }

    /**
     * 捕获时机：记住「这个宿主即将被第三方移除」。
     *
     * <p>{@code null} 等价于「这次没有可交接的宿主」，并会清掉上一次的残留。</p>
     */
    public static void capture(@Nullable IAttachmentHolder host) {
        PENDING.remove();
        if (host != null) {
            PENDING.set(host);
        }
    }

    /**
     * 交接时机：把捕获到的宿主的数据搬进 {@code target}。
     *
     * @return 是否执行了交接（有捕获且目标非空）；各 Mover 是否真的搬了东西由它们自己判断
     */
    public static boolean handoff(@Nullable ItemStack target) {
        IAttachmentHolder host = PENDING.get();
        PENDING.remove();
        if (host == null) {
            ModLog.CONTAINER.info("[ContainerHandoff] handoff 早退：没有捕获到宿主");
            return false;
        }
        if (target == null || target.isEmpty()) {
            ModLog.CONTAINER.info("[ContainerHandoff] handoff 早退：目标物品为空");
            return false;
        }

        // 成功路径只打 DEBUG：正常游玩每次拾取/放置都会走到这里，INFO 会刷屏；
        // 早退/失败走 INFO/WARN（罕见，必须看得见）。
        ModLog.CONTAINER.debug("[ContainerHandoff] handoff: movers={} host={} target={}",
            MOVERS.size(), host.getClass().getSimpleName(), target.getItem());

        for (Mover mover : MOVERS) {
            try {
                mover.moveOut(host, target);
            } catch (RuntimeException e) {
                // 一个领域炸了不该连累别的领域；但必须留下痕迹（I-C6 降级可观测）
                ModLog.CONTAINER.error("[ContainerHandoff] 交接失败，该领域的容器级数据会随方块消失", e);
            }
        }
        return true;
    }

    /** 丢弃捕获（第三方最终没把物品给玩家时用）。 */
    public static void discard() {
        PENDING.remove();
        PENDING_RESTORE.remove();
    }

    /** 当前是否有捕获（供测试与调试）。 */
    public static boolean hasPending() {
        return PENDING.get() != null;
    }

    // ─────────────────────────── 放置方向：物品 → 宿主 ───────────────────────────

    /**
     * 暂存时机：物品即将被第三方放置成方块（**必须在它被消耗之前**）。
     *
     * <p>存副本：精妙 {@code tryPlace} 里的 {@code shrink(1)} 会就地改原栈。</p>
     */
    public static void stageRestore(@Nullable ItemStack source) {
        PENDING_RESTORE.remove();
        if (source != null && !source.isEmpty()) {
            PENDING_RESTORE.set(source.copy());
        }
    }

    /**
     * 恢复时机：方块已落地、BE 已创建。
     *
     * @return 是否执行了恢复（有暂存且宿主非空）；各 Mover 是否真的搬了由它们自己判断
     */
    public static boolean restoreTo(@Nullable IAttachmentHolder host) {
        ItemStack source = PENDING_RESTORE.get();
        PENDING_RESTORE.remove();
        if (host == null) {
            ModLog.CONTAINER.info("[ContainerHandoff] restoreTo 早退：宿主为空（方块没落地？）");
            return false;
        }
        if (source == null || source.isEmpty()) {
            ModLog.CONTAINER.info("[ContainerHandoff] restoreTo 早退：没有暂存到物品");
            return false;
        }

        ModLog.CONTAINER.debug("[ContainerHandoff] restoreTo: movers={} host={} source={}",
            MOVERS.size(), host.getClass().getSimpleName(), source.getItem());

        for (Mover mover : MOVERS) {
            try {
                mover.moveIn(host, source);
            } catch (RuntimeException e) {
                ModLog.CONTAINER.error("[ContainerHandoff] 恢复失败，该领域的容器级数据不会回到方块", e);
            }
        }
        return true;
    }

    /** 当前是否有待恢复（供测试与调试）。 */
    public static boolean hasPendingRestore() {
        return PENDING_RESTORE.get() != null;
    }
}
