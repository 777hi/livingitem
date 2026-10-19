package com.qiqi.li.living.container;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.qiqi.li.logging.ModLog;

import net.minecraft.core.BlockPos;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 「菜单槽 → 它属于哪个容器位置」的解析器。
 *
 * <h3>为什么需要</h3>
 * <p>契约 {@code container-boundary.md} 的 I-C5 要求：<b>viewer 匹配必须按位置键，不能按
 * {@code Container} 实例</b>。原因是「有物品能力但不是原版 {@code Container}」的模组方块实体
 * （精妙背包方块、ProjectE 炼金箱）会被正常 tick，却永远匹配不到 viewer ⇒
 * 运行时 tooltip 遥测、定向槽位同步全部静默不发。</p>
 *
 * <h3>三级解析（可靠 → 脆弱），顺序即优先级</h3>
 * <ol>
 *   <li><b>结构化</b>（{@link #resolveFromSlotContainer}）：{@code slot.container} 是
 *       {@link BlockEntity}（原版单箱）或 {@link CompoundContainer}（大箱子）。
 *       <b>无反射、零维护、永不失效</b>。</li>
 *   <li><b>注册的 compat</b>（{@link #registerResolver}）：模组自己（或我们的 compat 模块）注册
 *       精确解析器。这是<b>模组容器的正解</b> —— 编译期安全，改名会编译失败。</li>
 *   <li><b>反射兜底</b>（{@link #resolveFromMenuPosition}）：问菜单有没有
 *       {@code getBlockPosition()} / {@code getBlockPos()}。
 *       ⚠️ <b>这是最弱的一环</b>：改名即静默失效，且无法在编译期发现。
 *       因此它<b>只作兜底</b>，且失效会打 debug 日志（I-C6 可观测）。</li>
 * </ol>
 *
 * <h3>⚠️ 反射兜底的已知风险（用户 2026-10-19 指出）</h3>
 * <ul>
 *   <li>模组改方法名 / 签名 ⇒ 静默降级为「能 tick、不能同步显示」（不损坏数据）；</li>
 *   <li>模块系统禁止 {@code setAccessible} ⇒ 同上（已 catch，不会崩）；</li>
 *   <li>泛名字误命中语义不同的方法 ⇒ <b>可能把包发给错的人</b>。
 *       ⇒ 因此<b>只认 {@code getBlockPosition} / {@code getBlockPos}</b>，
 *       不再认 {@code getPos}（太泛，风险高于收益）；且返回类型必须是
 *       {@link BlockPos} 或 {@code Optional<BlockPos>}。</li>
 * </ul>
 *
 * <p>⚠️ <b>本类只服务 viewer 匹配</b>（判断「这个玩家是不是在看这个容器」），
 * 不参与 tick 上下文构建 —— 后者仍以能力面 + 位置为唯一事实源。</p>
 */
public final class MenuSlotOwners {

    /** 单个槽的解析器。返回 {@code null}/空 = 不认识，交给下一级。 */
    @FunctionalInterface
    public interface Resolver {
        @Nullable
        List<BlockPos> resolve(AbstractContainerMenu menu, Slot slot);
    }

    /**
     * 菜单级位置解析器 —— 比逐槽更快（整个菜单就是一个方块时不必遍历槽位）。
     *
     * <p>返回 {@code null} = 不认识。</p>
     */
    @FunctionalInterface
    public interface MenuPositionResolver {
        @Nullable
        BlockPos resolve(AbstractContainerMenu menu);
    }

    /** 菜单级方法候选 —— ⚠️ 刻意不含 {@code getPos}（太泛，易误命中）。 */
    private static final String[] MENU_POS_METHODS = {"getBlockPosition", "getBlockPos"};

    /** 菜单类 → 位置方法（{@link Optional#empty()} = 该类没有可用方法，缓存负结果）。 */
    private static final Map<Class<?>, Optional<Method>> MENU_POS_CACHE = new ConcurrentHashMap<>();

    /** 已打过「无位置」日志的菜单类（每类只打一次，避免刷屏）。 */
    private static final Set<Class<?>> LOGGED_MISSES = ConcurrentHashMap.newKeySet();

    /** 注册的菜单级解析器（compat 模块用；先注册先命中）。 */
    private static final List<MenuPositionResolver> MENU_RESOLVERS = new CopyOnWriteArrayList<>();

    /** 注册的逐槽解析器（compat 模块用；先注册先命中）。 */
    private static final List<Resolver> SLOT_RESOLVERS = new CopyOnWriteArrayList<>();

    private MenuSlotOwners() {}

    // ── 注册口（compat 模块 / 其它领域用）────────────────────────

    /**
     * 注册一个<b>菜单级</b>位置解析器 —— 模组容器的推荐接法。
     *
     * <p>比反射可靠：编译期绑定，模组改名会立刻编译失败，而不是静默降级。
     * 例：{@code registerMenuResolver(menu -> menu instanceof BackpackContainer bc
     * ? bc.getBlockPosition().orElse(null) : null)}。</p>
     *
     * <p>注册的解析器<b>优先于反射</b>，但<b>不优先于结构化解析</b>（原版 BE 位置永远最可信）。</p>
     */
    public static void registerMenuResolver(MenuPositionResolver resolver) {
        MENU_RESOLVERS.add(resolver);
    }

    /** 注册一个逐槽解析器（需要按槽区分位置的场景才用；一般用菜单级即可）。 */
    public static void registerResolver(Resolver resolver) {
        SLOT_RESOLVERS.add(resolver);
    }

    // ── 查询 ────────────────────────────────────────────────────

    /**
     * 菜单级位置 —— 该菜单整体对应哪个方块位置。
     *
     * <p>顺序：注册的解析器 → 反射兜底。对精妙背包这类「菜单知道自己是哪个方块」的容器
     * 是 O(1) 快路径：一个菜单所有槽都属于同一个位置，不必逐槽解析。</p>
     *
     * @return 位置；菜单不暴露位置时返回 {@code null}
     */
    @Nullable
    public static BlockPos menuPosition(AbstractContainerMenu menu) {
        if (menu == null) return null;

        for (MenuPositionResolver resolver : MENU_RESOLVERS) {
            try {
                BlockPos pos = resolver.resolve(menu);
                if (pos != null) return pos;
            } catch (RuntimeException e) {
                // 第三方解析器抛异常不能拖垮 tick
                ModLog.CONTAINER.debug("菜单位置解析器 {} 抛异常：{}",
                    resolver.getClass().getName(), e.toString());
            }
        }

        return reflectiveMenuPosition(menu);
    }

    /**
     * 解析该菜单槽所属的容器位置（可能多个：大箱子两半）。
     *
     * @return 位置列表；无法解析时返回空列表（调用方按「匹配不上」处理）
     */
    public static List<BlockPos> resolve(AbstractContainerMenu menu, Slot slot) {
        Container container = slot.container;

        // ① 结构化：原版单箱 / 大箱 —— 无反射，永不失效
        List<BlockPos> structured = resolveFromSlotContainer(container);
        if (!structured.isEmpty()) return structured;

        // ② 注册的 compat（逐槽版）
        for (Resolver resolver : SLOT_RESOLVERS) {
            try {
                List<BlockPos> out = resolver.resolve(menu, slot);
                if (out != null && !out.isEmpty()) return out;
            } catch (RuntimeException e) {
                ModLog.CONTAINER.debug("槽位位置解析器 {} 抛异常：{}",
                    resolver.getClass().getName(), e.toString());
            }
        }

        // ③ 菜单级（注册的 compat → 反射兜底）
        BlockPos menuPos = menuPosition(menu);
        return menuPos == null ? List.of() : List.of(menuPos);
    }

    /** 该菜单里是否有槽属于 {@code positions} 中的任一位置。 */
    public static boolean isViewing(AbstractContainerMenu menu, List<BlockPos> positions) {
        if (menu == null || positions.isEmpty()) return false;

        // 快路径：菜单整体就是一个方块（精妙背包 / 大多数模组容器）
        BlockPos menuPos = menuPosition(menu);
        if (menuPos != null) return positions.contains(menuPos);

        // 慢路径：逐槽（原版单箱 / 大箱 / 玩家背包）
        for (Slot slot : menu.slots) {
            for (BlockPos pos : resolve(menu, slot)) {
                if (positions.contains(pos)) return true;
            }
        }
        return false;
    }

    // ── 结构化解析（无反射）─────────────────────────────────────

    private static List<BlockPos> resolveFromSlotContainer(Container container) {
        if (container instanceof BlockEntity be) {
            return List.of(be.getBlockPos());
        }
        if (container instanceof CompoundContainer) {
            BlockEntity[] halves = ContainerContexts.reflectHalves(container);
            if (halves != null) {
                return List.of(halves[0].getBlockPos(), halves[1].getBlockPos());
            }
        }
        return List.of();
    }

    // ── 反射兜底（最弱一环，带可观测）──────────────────────────

    @Nullable
    private static BlockPos reflectiveMenuPosition(AbstractContainerMenu menu) {
        Optional<Method> cached = MENU_POS_CACHE.computeIfAbsent(menu.getClass(), MenuSlotOwners::findPosMethod);
        if (cached.isEmpty()) {
            logMissOnce(menu.getClass());
            return null;
        }
        try {
            Object value = cached.get().invoke(menu);
            if (value instanceof BlockPos pos) return pos;
            if (value instanceof Optional<?> opt) {
                return opt.filter(BlockPos.class::isInstance).map(BlockPos.class::cast).orElse(null);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 模组菜单的方法可能抛异常（未初始化 / 客户端态）⇒ 当作「不认识」，不影响 tick
            ModLog.CONTAINER.debug("菜单 {} 的 {} 调用失败：{}",
                menu.getClass().getName(), cached.get().getName(), e.toString());
        }
        return null;
    }

    /**
     * 「这个菜单类无法解析位置」只记一次 —— 满足 I-C6（降级必须可观测），
     * 又不至于每 tick 刷屏。想查「为什么这个容器的 tooltip 不刷新」时，
     * 打开 {@code ModLog.CONTAINER} 的 debug 就能看到类名。
     */
    private static void logMissOnce(Class<?> menuClass) {
        if (LOGGED_MISSES.add(menuClass)) {
            ModLog.CONTAINER.debug(
                "菜单 {} 未暴露容器位置（无 getBlockPosition/getBlockPos）⇒ 该容器的运行时同步无法定向 viewer"
                + "（I-C5 降级：功能正常、显示不刷新）。若需支持，注册 MenuSlotOwners.registerMenuResolver",
                menuClass.getName());
        }
    }

    /** 反射查找位置方法（结果按类缓存，含负结果）。 */
    private static Optional<Method> findPosMethod(Class<?> menuClass) {
        for (String name : MENU_POS_METHODS) {
            Method m;
            try {
                m = menuClass.getMethod(name);
            } catch (NoSuchMethodException | RuntimeException e) {
                continue;
            }
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (!isPositionType(m.getReturnType())) continue;
            try {
                m.setAccessible(true);
            } catch (RuntimeException e) {
                // 模块系统 / 安全策略禁止访问 ⇒ 当作没有这个方法（不能让它冒到 tick 里）
                continue;
            }
            return Optional.of(m);
        }
        return Optional.empty();
    }

    private static boolean isPositionType(Class<?> type) {
        return type == BlockPos.class || type == Optional.class;
    }

    /** 供测试清理（生产不需要 —— 按类缓存不会泄漏，类数量有限）。 */
    static void clearCacheForTest() {
        MENU_POS_CACHE.clear();
        LOGGED_MISSES.clear();
    }

    /** 解析结果的可读描述（调试用）。 */
    static String describe(AbstractContainerMenu menu, Slot slot) {
        List<BlockPos> positions = new ArrayList<>(resolve(menu, slot));
        return positions.isEmpty() ? "<unknown>" : positions.toString();
    }
}
