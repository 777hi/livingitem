package com.qiqi.li.living.container;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 「<b>物品形态容器</b>」的注册口（2026-10-22，活背包 / `D-container-13`）。
 *
 * <h3>为什么需要它</h3>
 * <p>物品形态容器（精妙背包 / 潜影盒）的内容不在 {@code ItemHandler.BLOCK} 能力面上
 * —— 它由各模组自己实现（精妙的 {@code BackpackInventoryHandler} 是标准
 * {@link IItemHandler}，但我们拿不到它）。核心不认识这些模组类型（`D-container-09`）
 * ⇒ 由<b>联动模块注册一个来源</b>，活背包功能只问来源要 handler。</p>
 *
 * <h3>两个问题分开答</h3>
 * <ul>
 *   <li>{@link #isContainerItem} —— 「这件物品是容器吗」（决定功能是否认领它）</li>
 *   <li>{@link #contentHandler} —— 「怎么读写它的内容」（决定 tick 时拿什么 handler）</li>
 * </ul>
 *
 * <p>⚠️ 返回的 handler 必须是<b>活视图</b>（每次读到的都是当前内容），不能是快照 ——
 * 活背包的 tick 要能真正改动里面的物品。</p>
 */
public final class ItemFormContainers {

    /** 一个「物品形态容器」来源（每个支持的模组注册一个）。 */
    public interface Source {
        /** 这件物品是不是本来源能处理的容器物品。 */
        boolean isContainerItem(ItemStack stack);

        /**
         * 取内容读写视图；拿不到返回 {@code null}（未初始化 / 结构变了）。
         *
         * <p>⚠️ 调用方每 tick 调一次，实现方应自己缓存（精妙 wrapper 自带缓存）。</p>
         */
        @Nullable
        IItemHandler contentHandler(ItemStack stack);

        /**
         * **权威列数**（每行多少个存储格）；不知道返回 {@code 0}（⇒ ctx 回落到启发式）。
         *
         * <p>为什么需要它：物品形态容器没有 BE / 没有菜单 / 没注册规则 ⇒
         * {@code SimpleContainerContext#getWidth()} 三层推断全不命中，只能靠
         * {@code resolveColumns(slotCount)} 启发式猜。而那个启发式会猜错 ——
         * 精妙背包装 2 个占列升级（电池 / 流体罐，各占 2 列）后存储槽变成
         * {@code 行数 × (9 − 占列数)}，54 格背包是 30 格 ⇒ 启发式选 10，真值 5
         * ⇒ 邻居判定 / 流体渲染 / 漏斗传输全线错位。</p>
         *
         * <p>⇒ 能给出权威值的来源就该给，别让核心猜。</p>
         */
        default int columns(ItemStack stack) {
            return 0;
        }

        /**
         * 从打开的菜单反查「玩家正在看的是哪个容器」（tooltip / 倒汲 / GUI 槽位设置依赖它）。
         *
         * <p><b>默认实现</b>：扫玩家物品栏，找「本来源认领、且已带容器 UUID」的物品，
         * <b>只有唯一一件时才认</b>；0 件或 ≥2 件返回 {@code null}。</p>
         *
         * <p>⚠️ 为什么「多件不猜」：菜单本身不告诉你它对应物品栏的第几件，而
         * 「取手上那件」在玩家切换手持后就会错。猜错的后果是<b>操作到另一个容器</b>
         * （比解析不出更糟）⇒ 宁可返回 null（`D-container-09` 的同一原则：
         * 核心不做第三方形状猜测）。</p>
         *
         * <p>有更可靠来源的实现（比如能从自己的菜单对象直接拿到背包物品栈）应当覆写本方法。</p>
         */
        @Nullable
        default Handle handleOfMenu(AbstractContainerMenu menu, Player player) {
            Handle found = null;
            var inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!isContainerItem(stack)) continue;
                UUID uuid = ContainerUuids.ofItem(stack);   // 只有活化的才有身份
                if (uuid == null) continue;
                if (found != null) return null;             // 多件 ⇒ 不猜
                IItemHandler handler = contentHandler(stack);
                if (handler == null) continue;
                found = new Handle(handler, uuid, columns(stack));
            }
            return found;
        }
    }

    /** 「玩家正在看的那个物品形态容器」：内容视图 + 身份 + 权威列数（0 = 不知道）。 */
    public record Handle(IItemHandler handler, UUID containerUuid, int columns) {}

    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();

    private ItemFormContainers() {}

    /** 注册来源。**调用点必须在联动模块的初始化里**（R4 的同类约束）。 */
    public static void register(Source source) {
        SOURCES.add(source);
    }

    /** 找能处理这件物品的来源；没有返回 {@code null}（⇒ 功能不认领）。 */
    @Nullable
    public static Source find(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        for (Source source : SOURCES) {
            if (source.isContainerItem(stack)) return source;
        }
        return null;
    }

    /** 这件物品是不是物品形态容器。 */
    public static boolean isContainerItem(ItemStack stack) {
        return find(stack) != null;
    }

    /**
     * 从菜单反查「玩家正在看的物品形态容器」（tooltip / 倒汲 / GUI 槽位设置依赖它）。
     *
     * <p>按注册顺序问各来源，第一个给出答案的胜出。</p>
     */
    @Nullable
    public static Handle handleOf(AbstractContainerMenu menu, Player player) {
        if (menu == null || player == null) return null;
        for (Source source : SOURCES) {
            Handle handle = source.handleOfMenu(menu, player);
            if (handle != null) return handle;
        }
        return null;
    }

    /**
     * 测试用：清空注册。
     *
     * <p>public 是因为测试分散在多个包（{@code container} / {@code domain.backpack}），
     * 包级可见会让后者访问不到；与 {@code FluidFlowServerSync.clearForTest} 同款处理。
     * 生产代码不该调。</p>
     */
    public static void clearForTest() {
        SOURCES.clear();
    }
}
