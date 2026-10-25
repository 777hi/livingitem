package com.qiqi.li.living.transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.qiqi.li.logging.ModLog;
import com.qiqi.li.living.container.LearnedLayouts;


import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

/**
 * <b>容器布局自动学</b>（2026-10-25，Tier 1.5 的数据来源）。
 *
 * <h3>机制</h3>
 * <p>玩家打开容器 GUI 时（{@link PlayerContainerEvent.Open}，服务端），读那个菜单的
 * <b>容器槽坐标</b>，按 {@code y} 分组求行列，通过「干净矩形」安全阀后才按
 * {@code (容器 id, 槽数)} 记进 {@link LearnedLayouts}。</p>
 *
 * <h3>安全阀：只收干净矩形</h3>
 * <ul>
 *   <li>按 {@code y} 分组，每组一行</li>
 *   <li>要求每行格数相同（= 列数）</li>
 *   <li>要求每行内 {@code x} 等间距</li>
 *   <li>任一条不满足 ⇒ <b>不学</b>（熔炉的 L 形 / 酿造台的 2-2-1 / 精妙背包的升级槽污染都在此列）</li>
 * </ul>
 *
 * <h3>⚠️ 两个已证实的局限（写在这里，别忘）</h3>
 * <ol>
 *   <li><b>菜单坐标可能不是玩家看到的那份。</b>坐标是 {@code Slot} 的 public 字段，
 *       <b>客户端 Screen 可以随便重排它</b>。实例：fxntstorage 的
 *       {@code AbstractStorageBoxScreen#initializeSlots()} 在客户端把槽位重排成
 *       {@code SLOTS_PER_ROW = 12} 列并竖向滚动，而服务端菜单里仍是
 *       {@code x = 序号×18, y = 0} 的一横排。⇒ 对这类模组，学到的是<b>服务端原始坐标</b>
 *       的列数，未必等于玩家所见。治不了（没有通用 API 问“你看到的布局是什么”），
 *       只能接受：多数模组不重排，那时服务端坐标就是真相。</li>
 *   <li><b>拿不到容器身份就学不了。</b>键需要 {@code (BE id, 槽数)}，而菜单槽的
 *       {@code container} 只有是<b>方块实体</b>时才给得出 id。用 NeoForge 官方
 *       {@code SlotItemHandler} 的模组（它的 container 是共享静态空容器）⇒ 学不了，
 *       除非给它注册菜单解析器（{@code MenuSlotOwners}）。实例：fxntstorage 全部容器。</li>
 * </ol>
 *
 * <h3>与规则表的关系</h3>
 * <ul>
 *   <li><b>规则表已经答得上 ⇒ 不学</b> ⇒ 副作用：<b>「注册一条规则」天然就是「锁定该容器」</b>，
 *       不必另做锁机制；</li>
 *   <li>学到的值与已有规则<b>不同 ⇒ 只打日志，不覆盖</b>（模组改版导致规则过期时信号可见，
 *       然后 deliberate 地改规则）；</li>
 *   <li>同一个键先后学到两个不同值 ⇒ 标记歧义，此后不再学它。</li>
 * </ul>
 */
public final class ContainerLayoutLearner {

    private ContainerLayoutLearner() {}

    /** 开门事件入口（挂在 {@code LivingItem} 的服务端事件上）。 */
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return;   // 只在服务端学
        }
        AbstractContainerMenu menu = event.getContainer();
        // 玩家按 E 看自己背包：全是玩家自己的槽 ⇒ 没有容器槽可学（下面也会算出 0，这里提前退）
        if (menu == null || menu == player.inventoryMenu) {
            return;
        }
        try {
            learnFrom(menu, player);
        } catch (RuntimeException e) {
            // 第三方菜单结构异常不该影响开门（I-C7：留痕但不静默）
            ModLog.CONTAINER.error("[布局学习] 解析菜单 {} 失败，跳过：{}",
                menu.getClass().getName(), e.toString());
        }
    }

    private static void learnFrom(AbstractContainerMenu menu, Player player) {
        List<Slot> containerSlots = containerSlotsOf(menu, player);
        if (containerSlots.isEmpty()) return;

        // 容器身份：只认「槽的 container 是方块实体」（原版式菜单）。
        // ⚠️ 刻意不用反射猜第三方 getter（D-container-09：核心不做第三方形状猜测）——
        //    难兼容的模组走联动模块注册 MenuSlotOwners 解析器。
        ResourceLocation id = containerIdOf(containerSlots.get(0));
        if (id == null) return;

        int size = containerSlots.size();

        // 准入收紧（2026-10-25 审查 §10.3-2）："坐标像矩形"只是必要条件，还要证明
        // ① 归属一致（这一批槽全属同一个 container）
        // ② 槽号不重不漏（正好 0..n-1）
        // ③ 槽数完整（等于 container 自己的容量）
        // ④ 槽号顺序与 (y,x) 顺序一致（即 GUI 真是行优先 —— 我们消费方的映射假设）
        // 四条任一不满足 ⇒ 不学。通过/拒绝案例见设计稿 §10。
        if (!sameContainer(containerSlots)) return;
        if (!indicesAreDense(containerSlots)) return;
        if (!coversWholeContainer(containerSlots)) return;
        if (!rowMajorOrder(containerSlots)) return;

        int columns = columnsFromSlots(containerSlots);
        if (columns <= 0) return;   // 安全阀拒绝

        // 规则表已经答得上 ⇒ 不学（= 注册规则即锁定）；但与学到值不同 ⇒ 告警
        var existing = ContainerCompatibilityConfig.findRule(id, size);
        if (existing.isPresent()) {
            if (existing.get().columns() != columns) {
                ModLog.CONTAINER.warn(
                    "[布局学习] {} ({} 槽) 学到 {} 列，但已注册规则是 {} 列 —— 规则可能已过期，"
                        + "可用 /livingitem container register 修正",
                    id, size, columns, existing.get().columns());
            }
            return;
        }

        // 同键异值 ⇒ 歧义，此后不再学它
        Integer prev = LearnedLayouts.peek(id, size);
        if (prev != null && prev != columns) {
            LearnedLayouts.markAmbiguous(id, size);
            return;
        }

        if (LearnedLayouts.learn(id, size, columns)) {
            ModLog.CONTAINER.debug("[布局学习] {} ({} 槽) ⇒ {} 列", id, size, columns);
        }
    }

    /**
     * 菜单里属于<b>容器</b>的槽（排除玩家自己的物品栏 / 盔甲 / 副手）。
     *
     * <p>判据与 {@code SimpleContainerContext#syncWorldContainer} 同源：按 {@code Container}
     * 实例身份比，不按位置 —— 玩家的槽在菜单里也是槽，只能这么分。</p>
     */
    static List<Slot> containerSlotsOf(AbstractContainerMenu menu, Player player) {
        List<Slot> out = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) continue;
            out.add(slot);
        }
        return out;
    }


    /** ① 归属一致：这一批槽必须全属同一个 {@code Container} 实例。 */
    static boolean sameContainer(List<Slot> containerSlots) {
        Container first = containerSlots.get(0).container;
        for (Slot slot : containerSlots) {
            if (slot.container != first) return false;
        }
        return true;
    }

    /**
     * ② 槽号不重不漏：正好 {@code 0..n-1}。
     *
     * <p>{@code Slot#getContainerSlot()} 是槽在容器内的序号 —— 重复说明同一格被摆多次，
     * 断号说明菜单只暴露了容器的一部分（那种情况下"列数"也不是整份存储的列数）。</p>
     */
    static boolean indicesAreDense(List<Slot> containerSlots) {
        int size = containerSlots.size();
        if (size == 0) return false;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Slot slot : containerSlots) {
            int index = slot.getContainerSlot();
            if (index < 0 || index >= size || !seen.add(index)) return false;
        }
        return true;
    }

    /**
     * ③ 槽数完整：菜单暴露的容器槽数 = {@code Container#getContainerSize()}。
     *
     * <p>只对"container 是 {@link Container}"的情况可判；拿不到容量就保守拒绝
     * （宁可学不到，也不要把"局部视图"的几何当成整份存储的几何）。</p>
     */
    static boolean coversWholeContainer(List<Slot> containerSlots) {
        Container container = containerSlots.get(0).container;
        if (!(container instanceof net.minecraft.world.Container c)) return false;
        return c.getContainerSize() == containerSlots.size();
    }

    /**
     * ④ 行优先：按槽号排序的序列 == 按 {@code (y, x)} 排序的序列。
     *
     * <p>这是消费方映射假设（{@code row = slot / width, col = slot % width}）的直接前提。
     * 通不过就不是"我们复刻了 GUI"，只是"坐标凑巧是个矩形"。
     * <b>实例</b>：原版熔炉三槽按槽号是 input(0)→fuel(1)→output(2)，
     * 按 (y,x) 却是 input→output→fuel ⇒ 顺序不符 ⇒ 拒（它的 1 列由 Tier 2 家族表给）。</p>
     */
    static boolean rowMajorOrder(List<Slot> containerSlots) {
        List<Slot> byIndex = new java.util.ArrayList<>(containerSlots);
        byIndex.sort(java.util.Comparator.comparingInt(Slot::getContainerSlot));
        List<Slot> byPosition = new java.util.ArrayList<>(containerSlots);
        // ⚠️ Slot.x / Slot.y 是 public final 字段，不是 getter
        byPosition.sort(java.util.Comparator
            .comparingInt((Slot sl) -> sl.y).thenComparingInt((Slot sl) -> sl.x));
        for (int i = 0; i < byIndex.size(); i++) {
            if (byIndex.get(i) != byPosition.get(i)) return false;
        }
        return true;
    }

    /**
     * 容器 id —— 槽的 {@code container} 是<b>方块实体</b>时才给得出。
     *
     * <p>覆盖：原版单箱（container = BE）、铁箱（container = BE）、无尽箱子等。
     * 不覆盖：大箱（{@code CompoundContainer} 包装，不是 BE）、用 {@code SlotItemHandler}
     * 的模组（container 是共享静态空容器）、背包 GUI（container 是物品内容视图）。
     * 后三者今天由 Tier 1 / Tier 2 / Tier 0 覆盖，学不到不亏。</p>
     */
    @Nullable
    static ResourceLocation containerIdOf(Slot slot) {
        if (slot.container instanceof BlockEntity be) {
            return net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        }
        return null;
    }

    /**
     * 安全阀：按 {@code y} 分组求列数。{@code 0} = 不该学。
     *
     * <p>通过条件：组非空、每组格数相同、每组内 {@code x} 等间距。
     * 单槽容器 ⇒ 1 组 1 个 ⇒ 1 列（与启发式对 1 槽的答案一致）。</p>
     */
    static int columnsFromSlots(List<Slot> containerSlots) {
        Map<Integer, List<Integer>> rows = new TreeMap<>();
        for (Slot slot : containerSlots) {
            rows.computeIfAbsent(slot.y, k -> new ArrayList<>()).add(slot.x);
        }
        if (rows.isEmpty()) return 0;

        int columns = -1;
        for (List<Integer> xs : rows.values()) {
            if (columns == -1) {
                columns = xs.size();
            } else if (xs.size() != columns) {
                return 0;   // 行数不等 ⇒ 非矩形
            }
            if (!evenlySpaced(xs)) {
                return 0;
            }
        }
        return Math.max(columns, 0);
    }

    /**
     * 一行内的 x 是否等间距且**间距大于 0**。
     *
     * <p>⚠️ 间距必须 &gt; 0（2026-10-25 审查发现）：原来 {@code size < 3} 直接放行，
     * 于是两个槽挤在同一坐标也算"合法一行"；三个同坐标槽更是因为
     * {@code step = 0} 每步差都是 0 而被当成 1×3 网格。</p>
     */
    private static boolean evenlySpaced(List<Integer> xs) {
        if (xs.size() < 2) return true;   // 0 / 1 个元素无从检查间距
        Collections.sort(xs);
        int step = xs.get(1) - xs.get(0);
        if (step <= 0) return false;
        for (int i = 2; i < xs.size(); i++) {
            if (xs.get(i) - xs.get(i - 1) != step) return false;
        }
        return true;
    }
}
