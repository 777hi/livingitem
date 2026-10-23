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

    private static final Map<ItemStack, IItemHandler> HANDLERS = new IdentityHashMap<>();

    private final ItemStack backpack;
    private final IItemHandler handler;

    private BackpackWrapper(ItemStack backpack, IItemHandler handler) {
        this.backpack = backpack;
        this.handler = handler;
    }

    public static BackpackWrapper fromStack(ItemStack stack) {
        return new BackpackWrapper(stack, HANDLERS.computeIfAbsent(stack, s -> new ItemStackHandler(9)));
    }

    public IItemHandler getInventoryHandler() {
        return handler;
    }

    @Override
    public ItemStack getBackpack() {
        return backpack;
    }
}
