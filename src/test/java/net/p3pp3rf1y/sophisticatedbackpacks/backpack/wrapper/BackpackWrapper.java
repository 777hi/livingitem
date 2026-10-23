package net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 测试替身：精妙背包包装器（2026-10-23）。
 *
 * <p>两个同形方法：静态工厂 {@link #fromStack}（反射按名字 + 单参 ItemStack 找）与
 * {@link #getInventoryHandler()}（返回值恰好是标准 {@link IItemHandler}，
 * 所以核心不需要懂精妙的存储格式）。</p>
 *
 * <p>⚠️ <b>handler 按栈缓存</b>（{@code IdentityHashMap}）：真实实现也是「一个物品栈一份」，
 * 而测试要断言「菜单反查拿到的内容视图与 tick 路径是同一个」—— 两边都走 fromStack，
 * 不缓存就无法比同一性。</p>
 */
public class BackpackWrapper implements IBackpackWrapper {

    /**
     * 测试可调的「背包规格」：存储槽数与行数（真实实现由背包容量 + 升级决定）。
     *
     * <p>{@code SophisticatedBackpackSource#columns} 靠 {@code 槽数 ÷ 行数} 算权威列数，
     * 所以替身必须能让测试摆出各种规格。</p>
     */
    public static int testSlots = 27;
    public static int testRows = 3;

    private static final Map<ItemStack, IItemHandler> HANDLERS = new IdentityHashMap<>();

    private final ItemStack backpack;
    private final IItemHandler handler;

    private BackpackWrapper(ItemStack backpack, IItemHandler handler) {
        this.backpack = backpack;
        this.handler = handler;
    }

    public static BackpackWrapper fromStack(ItemStack stack) {
        return new BackpackWrapper(stack, HANDLERS.computeIfAbsent(stack, s -> new ItemStackHandler(testSlots)));
    }

    public IItemHandler getInventoryHandler() {
        return handler;
    }

    /** 真实 {@code BackpackWrapper#getNumberOfSlotRows()} 的同形方法。 */
    public int getNumberOfSlotRows() {
        return testRows;
    }

    @Override
    public ItemStack getBackpack() {
        return backpack;
    }
}
