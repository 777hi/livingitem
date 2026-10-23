package com.qiqi.li.living.compat.sophisticated;

import java.util.UUID;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import com.qiqi.li.living.container.ContainerUuids;
import com.qiqi.li.living.container.ItemFormContainers;

/**
 * 精妙背包作为「物品形态容器」的来源实现（2026-10-22，活背包）。
 *
 * <h3>为什么用反射而不是直接引用</h3>
 * <p>精妙是 {@code compileOnly} 的运行时依赖（{@code libs/} 里没有它的 jar）⇒ 直接写
 * {@code BackpackItem} / {@code BackpackWrapper} 会编译失败。而 mixin 模块已经通过
 * {@code living_item.mixins-sophisticated.json} 在运行期改它的类，这里只需要调几个方法。</p>
 *
 * <p>⚠️ 反射拿不到就返回 {@code null} / {@code false} ⇒ 活背包对这个物品<b>静默不生效</b>
 * （而不是崩服）。方法名在精妙改版时可能变 ⇒ <b>解析失败必须留一次 WARN</b>
 * （I-C6 降级可观测），否则「菜单解析不到容器」与「菜单反查没接」表现相同、查无可查。</p>
 *
 * <h3>三个方法对应什么</h3>
 * <ul>
 *   <li>{@code isContainerItem}：{@code BackpackItem} 的实例判断</li>
 *   <li>{@code contentHandler}：{@code BackpackWrapper.fromStack(stack).getInventoryHandler()}
 *       —— 返回值恰好是标准 {@link IItemHandler}，所以核心不需要懂精妙的存储格式</li>
 *   <li>{@code handleOfMenu}：从 {@code BackpackContainer.getBackpackContext()} 拿到
 *       <b>GUI 正在看的那一个背包</b>（见下）</li>
 * </ul>
 *
 * <h3>为什么要覆写 handleOfMenu</h3>
 * <p>默认实现是「扫玩家物品栏 + 唯一一件才认」，玩家带<b>两个</b>活背包时必然返回 null
 * ⇒ {@code ContainerContexts.resolve} 解析不出 ⇒ tooltip / 倒汲 / GUI 槽位设置全废
 * （2026-10-23 真机：两个活背包时活流体倒不了）。</p>
 *
 * <p>精妙的菜单对象自带锚点：{@code BackpackContext} 既知道
 * {@code handlerName + backpackSlotIndex}，也能直接给出
 * {@code getBackpackWrapper(player).getBackpack()} —— <b>就是 GUI 正在显示的那个物品栈</b>。
 * 拿它就不需要猜了，带几个背包都认得对，子背包（背包里的背包）也一并覆盖。</p>
 */
public final class SophisticatedBackpackSource implements ItemFormContainers.Source {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/Sophisticated");

    /** {@code net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem} */
    private static final String BACKPACK_ITEM = "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem";
    /** {@code net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper} */
    private static final String BACKPACK_WRAPPER = "net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper";
    /** {@code net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper} */
    private static final String BACKPACK_WRAPPER_IFACE = "net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper";
    /** {@code net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer} */
    private static final String BACKPACK_CONTAINER = "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer";
    /** {@code net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext} */
    private static final String BACKPACK_CONTEXT = "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext";

    @Nullable
    private static Class<?> backpackItemClass;

    @Nullable
    private static Class<?> backpackWrapperClass;

    /** {@code BackpackContainer} 类 —— 只用来判「这个菜单是不是背包 GUI」。 */
    @Nullable
    private static Class<?> backpackContainerClass;

    /** {@code BackpackWrapper.fromStack(ItemStack)} —— 静态工厂。 */
    @Nullable
    private static java.lang.reflect.Method fromStack;

    /** {@code BackpackWrapper#getInventoryHandler()}。 */
    @Nullable
    private static java.lang.reflect.Method getInventoryHandler;

    /** {@code BackpackContainer#getBackpackContext()}。 */
    @Nullable
    private static java.lang.reflect.Method getBackpackContext;

    /** {@code BackpackContext#getBackpackWrapper(Player)}。 */
    @Nullable
    private static java.lang.reflect.Method getBackpackWrapper;

    /** {@code IBackpackWrapper#getBackpack()} —— GUI 正在看的那个物品栈。 */
    @Nullable
    private static java.lang.reflect.Method getBackpack;

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

            backpackContainerClass = Class.forName(BACKPACK_CONTAINER);
            getBackpackContext = backpackContainerClass.getMethod("getBackpackContext");
            Class<?> contextClass = Class.forName(BACKPACK_CONTEXT);
            getBackpackWrapper = contextClass.getMethod("getBackpackWrapper", Player.class);
            getBackpack = Class.forName(BACKPACK_WRAPPER_IFACE).getMethod("getBackpack");
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.warn("[ModSophisticated] 精妙类反射解析失败（{}），活背包的菜单反查 / 内容读写不可用：{}",
                e.getClass().getSimpleName(), e.getMessage());
            backpackItemClass = null;
            backpackWrapperClass = null;
            backpackContainerClass = null;
            fromStack = null;
            getInventoryHandler = null;
            getBackpackContext = null;
            getBackpackWrapper = null;
            getBackpack = null;
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

    /**
     * 从菜单反查「GUI 正在看的那一个背包」。
     *
     * <p>精妙的菜单自带锚点，不需要默认实现那套「扫物品栏 + 唯一性」：
     * {@code BackpackContainer#getBackpackContext() → getBackpackWrapper(player).getBackpack()}
     * 就是正在显示的那个物品栈（子背包时是子背包的那一件）。</p>
     *
     * <p>返回 {@code null} 的情形（都让默认扫描兜底）：
     * 不是背包菜单 / 反射拿不到 / 那个背包<b>未活化</b>（没有容器 UUID ⇒ 没有容器级数据，
     * 解析出来也没有意义）/ 内容视图拿不到。</p>
     */
    @Override
    @Nullable
    public ItemFormContainers.Handle handleOfMenu(AbstractContainerMenu menu, Player player) {
        if (menu == null || player == null) return null;
        if (backpackContainerClass == null || getBackpackContext == null
                || getBackpackWrapper == null || getBackpack == null) return null;
        if (!backpackContainerClass.isInstance(menu)) return null;   // 不是背包 GUI ⇒ 交给默认扫描

        try {
            Object context = getBackpackContext.invoke(menu);
            if (context == null) return null;
            Object wrapper = getBackpackWrapper.invoke(context, player);
            if (wrapper == null) return null;

            ItemStack stack = (ItemStack) getBackpack.invoke(wrapper);
            if (stack == null || stack.isEmpty()) return null;

            UUID containerUuid = ContainerUuids.ofItem(stack);
            if (containerUuid == null) return null;   // 未活化：没有身份，也就没有容器级数据

            // 与 tick 路径共用同一个取视图的方法（唯一一处「怎么读写内容」的定义）
            IItemHandler handler = contentHandler(stack);
            if (handler == null) return null;

            return new ItemFormContainers.Handle(handler, containerUuid);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[ModSophisticated] 菜单反查背包失败（{}）：{}", e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }
}
