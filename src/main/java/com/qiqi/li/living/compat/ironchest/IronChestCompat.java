package com.qiqi.li.living.compat.ironchest;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;

/**
 * 铁箱子（IronChests，modid {@code ironchest}）软依赖判定 —— 活信标宿主白名单扩展。
 *
 * <p><b>由来</b>（2026-10-18 用户实测「活信标在某些容器里没有光束」）：调研 IronChests 源码
 * （{@code libs/src/ironchest-1.21.x-neoforge}）确认——{@code AbstractIronChestBlockEntity}
 * <b>不继承</b> {@code ChestBlockEntity}（它是 {@code RandomizableContainerBlockEntity}
 * + {@code LidBlockEntity}）⇒ 原宿主判据 {@code instanceof ChestBlockEntity} 整个漏掉铁箱子族
 * （铁 / 金 / 钻石 / 铜 / 水晶 / 黑曜石 / 泥土 × 普通 + 陷阱，共 14 种方块）。</p>
 *
 * <h3>为什么可以低成本接入（调研实证，2026-10-18）</h3>
 * <ul>
 *   <li><b>开盖协议与原版逐字同款</b>：{@code triggerEvent(1, type)} ⇒
 *       {@code ChestLidController.shouldBeOpen(type > 0)}，用的就是原版同一个类；
 *        {@code AbstractIronChestBlock} 未 override {@code triggerEvent} ⇒ 走
 *       {@code BaseEntityBlock} 默认转发 ⇒ 我们的 {@code blockEvent(pos, block, 1, 1)}
 *       开盖保持<b>零改动直接生效</b>；</li>
 *   <li><b>没有大箱合并</b>：{@code getMenuProvider} 只返回单个 BE（不像原版 ChestBlock 会合并
 *       CompoundContainer）⇒ 天然单容器，我们的大箱双半箱逻辑不会误触发；</li>
 *   <li><b>不动 openersCounter</b> ⇒ 铁箱子的陷阱箱变体也不会被误触发红石（与原版箱子同纪律）。</li>
 * </ul>
 *
 * <h3>已知边界（记录备书）</h3>
 * <ul>
 *   <li><b>泥土箱 1 槽 1 列</b>：信标放进去下方无行 ⇒ 永远无法激活（死位，与原版小容器同行为，
 *       非 bug）；</li>
 *   <li><b>水晶箱半透明</b>：光束从箱格内部画起会被半透明模型挡一截，观感待实测；</li>
 *   <li>宽 12 列的钻石/水晶/黑曜石箱（108 槽）：底座 9 列网格用 {@code getWidth()}（=12）自适应，
 *       以信标列为中心 ±4 ⇒ 天然成立。</li>
 * </ul>
 *
 * <p>软依赖：不 import 第三方类（编译期零依赖），运行时反射判定；模组不在 / 类改名 /
 * 单测环境（无 ModList）一律降级为「不支持」（{@code isIronChest} 恒 false）。</p>
 */
public final class IronChestCompat {

    private static final Class<?> IRON_CHEST_BE = findClass();

    private IronChestCompat() {
    }

    private static Class<?> findClass() {
        try {
            if (ModList.get().isLoaded("ironchest")) {
                return Class.forName(
                    "com.progwml6.ironchest.common.block.regular.entity.AbstractIronChestBlockEntity");
            }
        } catch (Throwable ignored) {
            // 模组不在 / 类改名 / 单测环境无 ModList ⇒ 降级为「不支持」
        }
        return null;
    }

    /** 是否铁箱子族的方块实体（含陷阱箱变体；模组不在时恒 false）。 */
    public static boolean isIronChest(BlockEntity be) {
        return be != null && IRON_CHEST_BE != null && IRON_CHEST_BE.isInstance(be);
    }
}
