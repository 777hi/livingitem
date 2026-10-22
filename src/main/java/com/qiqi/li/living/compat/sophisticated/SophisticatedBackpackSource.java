package com.qiqi.li.living.compat.sophisticated;

import javax.annotation.Nullable;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import com.qiqi.li.living.container.ItemFormContainers;

/**
 * 精妙背包作为「物品形态容器」的来源实现（2026-10-22，活背包）。
 *
 * <h3>为什么用反射而不是直接引用</h3>
 * <p>精妙是 {@code compileOnly} 的运行时依赖（{@code libs/} 里没有它的 jar）⇒ 直接写
 * {@code BackpackItem} / {@code BackpackWrapper} 会编译失败。而 mixin 模块已经通过
 * {@code living_item.mixins-sophisticated.json} 在运行期改它的类，这里只需要调两个方法。</p>
 *
 * <p>⚠️ 反射拿不到就返回 {@code null} / {@code false} ⇒ 活背包对这个物品<b>静默不生效</b>
 * （而不是崩服）。方法名在精妙改版时可能变，届时应在此处加日志，不要静默吞。</p>
 *
 * <h3>两个方法对应什么</h3>
 * <ul>
 *   <li>{@code isContainerItem}：{@code BackpackItem} 的实例判断</li>
 *   <li>{@code contentHandler}：{@code BackpackWrapper.fromStack(stack).getInventoryHandler()}
 *       —— 返回值恰好是标准 {@link IItemHandler}，所以核心不需要懂精妙的存储格式</li>
 * </ul>
 */
public final class SophisticatedBackpackSource implements ItemFormContainers.Source {

    /** {@code net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem} */
    private static final String BACKPACK_ITEM = "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem";
    /** {@code net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper} */
    private static final String BACKPACK_WRAPPER = "net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper";

    @Nullable
    private static Class<?> backpackItemClass;

    @Nullable
    private static Class<?> backpackWrapperClass;

    @Nullable
    private static java.lang.reflect.Method fromStack;

    @Nullable
    private static java.lang.reflect.Method getInventoryHandler;

    private static boolean resolved;

    public SophisticatedBackpackSource() {
        resolve();
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            backpackItemClass = Class.forName(BACKPACK_ITEM);
            backpackWrapperClass = Class.forName(BACKPACK_WRAPPER);
            // BackpackWrapper.fromStack(ItemStack) 是静态工厂
            for (var m : backpackWrapperClass.getMethods()) {
                if (m.getName().equals("fromStack") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == ItemStack.class) {
                    fromStack = m;
                    break;
                }
            }
            getInventoryHandler = backpackWrapperClass.getMethod("getInventoryHandler");
        } catch (ReflectiveOperationException | LinkageError e) {
            backpackItemClass = null;
            backpackWrapperClass = null;
            fromStack = null;
            getInventoryHandler = null;
        }
    }

    @Override
    public boolean isContainerItem(ItemStack stack) {
        return backpackItemClass != null && !stack.isEmpty()
            && backpackItemClass.isInstance(stack.getItem());
    }

    @Override
    @Nullable
    public IItemHandler contentHandler(ItemStack stack) {
        if (fromStack == null || getInventoryHandler == null) return null;
        try {
            Object wrapper = fromStack.invoke(null, stack);
            if (wrapper == null) return null;
            Object handler = getInventoryHandler.invoke(wrapper);
            return handler instanceof IItemHandler itemHandler ? itemHandler : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 精妙内部状态异常（未初始化 / 已失效）⇒ 本 tick 跳过，不崩
            return null;
        }
    }
}
