package com.qiqi.li.living.container;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.qiqi.li.logging.ModLog;

import net.minecraft.core.BlockPos;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.entity.Entity;
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
 * 会被正常 tick，却永远匹配不到 viewer ⇒ 运行时 tooltip 遥测、定向槽位同步全部静默不发。</p>
 *
 * <h3>⚠️ 核心不做第三方 API 猜测（2026-10-19，`D-container-09`）</h3>
 * <p>本类<b>只提供机制</b>：结构化解析 + 注册口。<b>不内置任何「反射第三方方法名」的兜底</b> ——
 * 那等于把核心绑在某个模组的 API 形状上（曾为某个第三方容器内置过 {@code getBlockPosition} 反射，已删除）。</p>
 *
 * <p>难兼容的模组走<b>联动模块</b>（{@code living/compat/<mod>/}），由模块自己
 * {@link #registerMenuResolver 注册}，并受 {@code ModList.isLoaded} 门控 —— 与 Create / Sable
 * 的软依赖同构。模组改 API 时只有该联动模块失效，核心与其它模组不受牵连。</p>
 *
 * <h3>两级解析（都是可靠来源）</h3>
 * <ol>
 *   <li><b>结构化</b>（{@link #resolveFromSlotContainer}）：{@code slot.container} 是
 *       {@link BlockEntity}（原版单箱）或 {@link CompoundContainer}（大箱子）。
 *       <b>无反射、零维护、永不失效</b>。</li>
 *   <li><b>注册的联动解析器</b>（{@link #registerMenuResolver} / {@link #registerResolver}）：
 *       由联动模块提供，逻辑与该模组一起演化。</li>
 * </ol>
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

    /**
     * 实体容器解析器（2026-10-21）—— 「这份菜单属于哪个实体」的注册口。
     *
     * <p>核心（L1）不 import 任何 mixin 类 —— 方向由实现侧指向注册口（层校验允许的方向）。
     * 当前唯一注册者是 {@code LivingItem} 的 {@code HORSE_MENU_RESOLVER}（反射
     * {@code HorseInventoryMenu.horse}字段；accessor 方案被 Mixin 包隔离否掉，见其 javadoc）。</p>
     */
    public interface EntityResolver {
        /**
         * @param slot 逐槽调用时为当前槽；菜单级判定传 {@code null}（实现<b>不得</b>依赖它）
         * @return 菜单关联的实体；不认领返回 {@code null}
         */
        @Nullable
        Entity resolve(AbstractContainerMenu menu, @Nullable Slot slot);
    }

    /** 已打过「无解析器」日志的菜单类（每类只打一次，避免刷屏）。 */
    private static final Set<Class<?>> LOGGED_MISSES = ConcurrentHashMap.newKeySet();

    /** 注册的菜单级解析器（联动模块用；先注册先命中）。 */
    private static final List<MenuPositionResolver> MENU_RESOLVERS = new CopyOnWriteArrayList<>();

    /** 实体容器解析器（「这份菜单属于哪个实体」—— 如驴的 HorseInventoryMenu 反射）。 */
    private static final List<EntityResolver> ENTITY_RESOLVERS = new CopyOnWriteArrayList<>();

    /**
     * 注册「菜单 → 容器实体」解析器（2026-10-21 实体容器）。
     *
     * <p>为什么要有注册表：{@code resolve} 的结构化分支只覆盖「slot.container 就是实体」的
     * 情况（船/矿车）；驴的槽位 container 是 NeoForge 的匿名 {@code InvWrapper}，只能问菜单
     * 本身（{@code HorseInventoryMenu.horse} 是 private final 无 getter ⇒ 只能反射）。
     * 反射代码属于接线层，核心（L1）不 import 它 ⇒ 由入口层实现并注册到这里。</p>
     *
     * <p>返回的实体由 {@link EntityContainers#isContainerEntity} 统一过白名单。</p>
     */
    public static void registerEntityResolver(EntityResolver resolver) {
        ENTITY_RESOLVERS.add(resolver);
    }

    /**
     * 菜单 → 容器实体（注册的解析器逐个问；没人认领返回 {@code null}）。
     *
     * @param slot 逐槽调用时为当前槽；菜单级判定传 {@code null}（实现不得依赖它）
     */
    @Nullable
    public static Entity resolveMenuEntity(AbstractContainerMenu menu, @Nullable Slot slot) {
        for (EntityResolver resolver : ENTITY_RESOLVERS) {
            try {
                Entity entity = resolver.resolve(menu, slot);
                if (entity != null) return entity;
            } catch (RuntimeException e) {
                // 解析器抛异常不能拖垮 tick（与位置解析器同款防线）
                ModLog.CONTAINER.debug("实体容器解析器 {} 抛异常：{}",
                    resolver.getClass().getName(), e.toString());
            }
        }
        return null;
    }

    /** 注册的逐槽解析器（联动模块用；先注册先命中）。 */
    private static final List<Resolver> SLOT_RESOLVERS = new CopyOnWriteArrayList<>();

    private MenuSlotOwners() {}

    // ── 注册口（联动模块用）────────────────────────────────────

    /**
     * 注册一个<b>菜单级</b>位置解析器 —— 联动模块的接法。
     *
     * <p>例（compileOnly 依赖）：{@code registerMenuResolver(menu -> menu instanceof BackpackContainer bc
     * ? bc.getBlockPosition().orElse(null) : null)}；无依赖时由模块内部自行反射。</p>
     *
     * <p>⚠️ 结构化解析<b>仍然先跑</b>（原版 BE 位置是最可信的事实）；解析器只在结构化拿不到时兜底。</p>
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
     * <p>只问注册的联动解析器；<b>不猜</b>（见类注释）。</p>
     *
     * @return 位置；无解析器认识该菜单时返回 {@code null}
     */
    @Nullable
    public static BlockPos menuPosition(AbstractContainerMenu menu) {
        if (menu == null) return null;

        // 实体容器（2026-10-21）：菜单级实体解析（注册的 resolver 提供，如驴的 accessor 实现）。
        // ⚠️ 注册表而不是核心 instanceof 菜单类：那会把「认识某个具体菜单类」焊进核心，
        //   正是 D-container-09 禁止的形状。实现在调用方侧注册。
        Entity menuEntity = resolveMenuEntity(menu, null);
        if (menuEntity != null && EntityContainers.isContainerEntity(menuEntity)) {
            return menuEntity.blockPosition();
        }

        for (MenuPositionResolver resolver : MENU_RESOLVERS) {
            try {
                BlockPos pos = resolver.resolve(menu);
                if (pos != null) return pos;
            } catch (RuntimeException e) {
                // 联动解析器抛异常不能拖垮 tick
                ModLog.CONTAINER.debug("菜单位置解析器 {} 抛异常：{}",
                    resolver.getClass().getName(), e.toString());
            }
        }

        logMissOnce(menu.getClass());
        return null;
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

        // ② 注册的联动解析器（逐槽版）
        for (Resolver resolver : SLOT_RESOLVERS) {
            try {
                List<BlockPos> out = resolver.resolve(menu, slot);
                if (out != null && !out.isEmpty()) return out;
            } catch (RuntimeException e) {
                ModLog.CONTAINER.debug("槽位位置解析器 {} 抛异常：{}",
                    resolver.getClass().getName(), e.toString());
            }
        }

        // ③ 菜单级（联动模块注册的）
        BlockPos menuPos = menuPosition(menu);
        return menuPos == null ? List.of() : List.of(menuPos);
    }

    /** 该菜单里是否有槽属于 {@code positions} 中的任一位置。 */
    public static boolean isViewing(AbstractContainerMenu menu, List<BlockPos> positions) {
        if (menu == null || positions.isEmpty()) return false;

        // 快路径：菜单整体就是一个方块（联动模块注册过的容器）
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
        // 实体容器（2026-10-21）：1.21 ContainerEntity extends Container ⇒ 船/矿车菜单槽的
        // container 就是实体本身（ChestBoat#createMenu 传 this）。按 UUID 键 + 实时位置反查，
        // GUI 开着时船在漂，每次解析拿到的都是当前位置 —— 位置口径在移动容器上才对。
        if (container instanceof Entity e) {
            return List.of(e.blockPosition());
        }
        if (container instanceof CompoundContainer) {
            BlockEntity[] halves = ContainerContexts.reflectHalves(container);
            if (halves != null) {
                return List.of(halves[0].getBlockPos(), halves[1].getBlockPos());
            }
        }
        return List.of();
    }

    /**
     * 「这个菜单类没有解析器」只记一次 —— 满足 I-C6（降级必须可观测），又不至于每 tick 刷屏。
     *
     * <p>想查「为什么这个容器的 tooltip 不刷新」时，打开 {@code ModLog.CONTAINER} 的 debug
     * 就能看到类名；要支持它就写一个联动模块调 {@link #registerMenuResolver}。</p>
     */
    private static void logMissOnce(Class<?> menuClass) {
        if (LOGGED_MISSES.add(menuClass)) {
            ModLog.CONTAINER.debug(
                "菜单 {} 没有注册位置解析器 ⇒ 该容器的运行时同步无法定向 viewer"
                + "（I-C5 降级：功能正常、显示不刷新）。若需支持，由联动模块调 MenuSlotOwners.registerMenuResolver",
                menuClass.getName());
        }
    }

    /** 供测试清理（生产不需要 —— 按类缓存不会泄漏，类数量有限）。 */
    static void clearCacheForTest() {
        LOGGED_MISSES.clear();
    }

    /** 解析结果的可读描述（调试用）。 */
    static String describe(AbstractContainerMenu menu, Slot slot) {
        List<BlockPos> positions = new ArrayList<>(resolve(menu, slot));
        return positions.isEmpty() ? "<unknown>" : positions.toString();
    }
}
