package com.qiqi.li.living.compat.sophisticated;

import java.util.UUID;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
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
    /** {@code net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlockEntity} */
    private static final String BACKPACK_BLOCK_ENTITY =
        "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlockEntity";

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

    /** {@code BackpackWrapper#getNumberOfSlotRows()} —— 算权威列数要除它。 */
    @Nullable
    private static java.lang.reflect.Method getNumberOfSlotRows;

    /** {@code BackpackBlockEntity} 类 —— 判「放置态是不是我的容器」。 */
    @Nullable
    private static Class<?> backpackBlockEntityClass;

    /** {@code BackpackBlockEntity#getBackpackWrapper()} —— 放置态拿 wrapper 的唯一入口。 */
    @Nullable
    private static java.lang.reflect.Method getBackpackWrapperOfBe;

    private static boolean resolved;

    public SophisticatedBackpackSource() {
        resolve();
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;

        // 第一组：内容读写（tick 依赖）。与第二组**独立失败** ——
        // 精妙改版时若只炸了 GUI 类的名字，不该把 tick 也一起拖下水
        // （那会让「能运转」退化成「完全不活」，比覆写前差）。
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
            LOGGER.warn("[ModSophisticated] 精妙内容读写反射解析失败（{}），活背包整体不生效：{}",
                e.getClass().getSimpleName(), e.getMessage());
        }

        // 第二组：菜单锚点（#6 菜单解析依赖）。失败只降级菜单反查，不影响上面那组。
        try {
            backpackContainerClass = Class.forName(BACKPACK_CONTAINER);
            getBackpackContext = backpackContainerClass.getMethod("getBackpackContext");
            Class<?> contextClass = Class.forName(BACKPACK_CONTEXT);
            getBackpackWrapper = contextClass.getMethod("getBackpackWrapper", Player.class);
            getBackpack = Class.forName(BACKPACK_WRAPPER_IFACE).getMethod("getBackpack");
            getNumberOfSlotRows = backpackWrapperClass.getMethod("getNumberOfSlotRows");
        } catch (ReflectiveOperationException | LinkageError e) {
            backpackContainerClass = null;
            getBackpackContext = null;
            getBackpackWrapper = null;
            getBackpack = null;
            getNumberOfSlotRows = null;
            LOGGER.warn("[ModSophisticated] 菜单锚点反射解析失败（{}），"
                + "handleOfMenu 退回默认扫描（扫物品栏 + 唯一一件才认）：{}",
                e.getClass().getSimpleName(), e.getMessage());
        }

        // 第三组：放置形态（背包放在地上 = BackpackBlockEntity）。失败只让放置态列数
        // 掉到启发式，物品形态（tick / 菜单反查）与 GUI 反查都不受影响。
        try {
            backpackBlockEntityClass = Class.forName(BACKPACK_BLOCK_ENTITY);
            getBackpackWrapperOfBe = backpackBlockEntityClass.getMethod("getBackpackWrapper");
        } catch (ReflectiveOperationException | LinkageError e) {
            backpackBlockEntityClass = null;
            getBackpackWrapperOfBe = null;
            LOGGER.warn("[ModSophisticated] 放置形态反射解析失败（{}），"
                + "放置态背包的列数回落启发式（物品形态不受影响）：{}",
                e.getClass().getSimpleName(), e.getMessage());
        }
    }

    @Override
    public boolean isContainerItem(ItemStack stack) {
        return backpackItemClass != null && !stack.isEmpty()
            && backpackItemClass.isInstance(stack.getItem());
    }

    /**
     * 「这件物品<b>确定</b>不是 {@code BackpackItem}」—— 给
     * {@code BackpackContainerMixin} 在把栈交给上游之前判断用。
     *
     * <p>上游 {@code BackpackWrapper#cacheSlotNumbers} 不判 instanceof 就硬转，
     * 空栈会直接 {@code ClassCastException} 崩客户端（2026-10-28 真机）。
     * 补判空要能区分三种输入，判据必须<b>宁漏不误</b>：</p>
     * <ul>
     *   <li><b>空栈</b> ⇒ {@code true}（空气不可能是背包，确定）；</li>
     *   <li><b>类已解析且不是</b> ⇒ {@code true}（确定）；</li>
     *   <li><b>类没解析出来</b>（精妙改版/反射失败）⇒ {@code false} —— <b>不动手</b>，
     *       退回上游行为。若这里返回 {@code true}，正常的内容刷新会被全拦掉，
     *       背包 GUI 直接坏掉，比偶尔崩一次更糟。</li>
     * </ul>
     *
     * <p>⚠️ 与 {@link #isContainerItem} 不是反义：后者要求「确定是」（类没解析出来时为
     * {@code false}，功能不认领），本方法要求「确定不是」（类没解析出来时也为
     * {@code false}，不拦）。两者的未知都落到「不做任何事」。</p>
     */
    public static boolean isDefinitelyNotBackpack(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        resolve();   // 幂等；mixin 可能早于联动模块初始化先问到这里
        return backpackItemClass != null && !backpackItemClass.isInstance(stack.getItem());
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
     * 权威列数 = <b>存储槽数 ÷ 行数</b>。
     *
     * <p>精妙的 handler 大小就是 {@code 行数 × 每行格数}（构造式：
     * {@code getNumberOfInventorySlots() - getNumberOfSlotRows() * getColumnsTaken()}），
     * 所以除一下就是真值 —— <b>不需要复刻它的布局公式</b>（{@code slotsOnLine - columnsTaken}）。
     * 装 2 个占列升级的 54 格背包：30 槽 ÷ 6 行 = <b>5</b>，而启发式会给 10。</p>
     *
     * <p>除不尽 / 拿不到 ⇒ 返回 0（⇒ 调用方回落到启发式，不比现在更差）。</p>
     */
    @Override
    public int columns(ItemStack stack) {
        if (fromStack == null || getNumberOfSlotRows == null) return 0;
        try {
            Object wrapper = fromStack.invoke(null, stack);
            if (wrapper == null) return 0;
            Object rowsObj = getNumberOfSlotRows.invoke(wrapper);
            if (!(rowsObj instanceof Integer rows) || rows <= 0) return 0;

            IItemHandler handler = contentHandler(stack);
            int slots = handler == null ? 0 : handler.getSlots();
            return slots > 0 && slots % rows == 0 ? slots / rows : 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    /**
     * <b>放置形态</b>的权威列数 —— 与 {@link #columns(ItemStack)} 同一套算法，
     * 只是 wrapper 从方块实体上取（{@code BackpackBlockEntity#getBackpackWrapper()}）
     * 而不是从物品栈构造。
     *
     * <p>为什么放置态需要单独一条：方块容器走 {@code SimpleContainerContext}，
     * 它的列数推断三层（规则表 / 已知类型表 / 启发式）都不认识第三方 BE
     * ⇒ 精妙背包装储罐后 30 存储槽被启发式猜成 10 列（真值 5）。
     * 2026-10-24 用户实测：物品形态解析正确、放置态错误。</p>
     *
     * <p>⚠️ {@code getBackpackWrapper()} 返回 BE 上的<b>缓存字段</b>（真实实现就是
     * {@code return backpackWrapper;}）⇒ 调用开销是一次字段读 + 两次反射 invoke，
     * 不是构造 wrapper。真正的成本在 {@code getInventoryHandler()}（懒建，之后命中字段）。</p>
     */
    @Override
    public int columnsOfBlockEntity(BlockEntity be) {
        if (backpackBlockEntityClass == null || getBackpackWrapperOfBe == null
                || getNumberOfSlotRows == null || getInventoryHandler == null) {
            return 0;
        }
        if (!backpackBlockEntityClass.isInstance(be)) {
            return 0;   // 不是我的容器（含别的模组的 BE）⇒ 让调用方继续问别的来源
        }
        try {
            Object wrapper = getBackpackWrapperOfBe.invoke(be);
            if (wrapper == null) return 0;
            Object rowsObj = getNumberOfSlotRows.invoke(wrapper);
            if (!(rowsObj instanceof Integer rows) || rows <= 0) return 0;

            Object handler = getInventoryHandler.invoke(wrapper);
            if (!(handler instanceof IItemHandler itemHandler)) return 0;
            int slots = itemHandler.getSlots();
            return slots > 0 && slots % rows == 0 ? slots / rows : 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 精妙内部状态异常（未初始化 / 已失效）⇒ 本调用作废，回落启发式，不崩
            return 0;
        }
    }

    /**
     * 从菜单反查「GUI 正在看的那一个背包」。
     *
     * <p>精妙的菜单自带锚点，不需要默认实现那套「扫物品栏 + 唯一性」：
     * {@code BackpackContainer#getBackpackContext() → getBackpackWrapper(player).getBackpack()}
     * 就是正在显示的那个物品栈（子背包时是子背包的那一件）。</p>
     *
     * <p>返回 {@code null} 的情形：不是背包菜单 / 反射链没解析出来 / 那个背包<b>未活化</b>
     * （没有容器 UUID ⇒ 没有容器级数据，解析出来也没有意义）/ 内容视图拿不到。</p>
     *
     * <p>⚠️ <b>不是背包菜单时坚决不扫物品栏</b>：玩家只带一个活背包时，默认实现会把
     * AE2 / ProjectE 的槽位（既不是 BE 也不是玩家背包）错认成这个背包 ——
     * 错认比解析不出更糟。</p>
     */
    @Override
    @Nullable
    public ItemFormContainers.Handle handleOfMenu(AbstractContainerMenu menu, Player player) {
        if (menu == null || player == null) return null;
        if (backpackContainerClass == null || getBackpackContext == null
                || getBackpackWrapper == null || getBackpack == null) return null;
        if (!backpackContainerClass.isInstance(menu)) return null;   // 不是背包 GUI ⇒ 不猜

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

            return new ItemFormContainers.Handle(handler, containerUuid, columns(stack));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[ModSophisticated] 菜单反查背包失败（{}）：{}",
                e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }
}
