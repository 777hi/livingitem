package com.qiqi.li.living.api;

import com.qiqi.li.living.components.LivingComponents;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import java.util.Set;

import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.mojang.serialization.Codec;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * 活物品管理器。
 * 负责：
 * 1. 注册自定义 DataComponent 类型（IS_LIVING 及各功能 DataComponent）
 * 2. 管理已注册的活物品功能（LivingItemFunction 列表）
 * 3. 提供泛型数据访问方法（getData/setData）
 */
public class LivingItemManager {
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final List<LivingItemFunction> FUNCTIONS = new ArrayList<>();
    private static final List<LivingItemFunction> FUNCTIONS_VIEW = Collections.unmodifiableList(FUNCTIONS);
    private static final Map<Item, List<LivingItemFunction>> APPLICABLE_CACHE = new ConcurrentHashMap<>();

    /**
     * 自维持函数清单（{@link LivingItemFunction#shouldTickWithoutOwnItems} 为真的函数）。
     *
     * <p>在<b>注册期</b>一次性算出，每 tick 的 {@code processContext} 只遍历这张静态清单，
     * 避免逐函数调用判定（22 个函数 × 成千上万个容器 = 不可接受的每 tick 开销）。
     * 详见 {@link LivingItemFunction#shouldTickWithoutOwnItems} 的注册期约定。</p>
     */
    private static volatile List<LivingItemFunction> SELF_SUSTAINING_FUNCTIONS = List.of();

    public static void registerFunction(LivingItemFunction function) {
        LOGGER.info("Registering living item function: {}", function.getFunctionId());
        FUNCTIONS.add(function);
        APPLICABLE_CACHE.clear();
        recomputeSelfSustaining();
    }

    /**
     * 按 {@link LivingItemFunction#shouldTickWithoutOwnItems} 重算自维持函数清单。
     * 注册 / 排序时各调用一次；用 null 上下文（覆盖方须当作静态声明处理）。
     */
    private static void recomputeSelfSustaining() {
        List<LivingItemFunction> selfSustaining = new ArrayList<>();
        for (LivingItemFunction f : FUNCTIONS) {
            if (f.shouldTickWithoutOwnItems(null)) {
                selfSustaining.add(f);
            }
        }
        SELF_SUSTAINING_FUNCTIONS = Collections.unmodifiableList(selfSustaining);
    }

    /** 自维持函数清单（注册期算好的静态视图，每 tick 只读遍历）。 */
    public static List<LivingItemFunction> getSelfSustainingFunctions() {
        return SELF_SUSTAINING_FUNCTIONS;
    }

    /**
     * 按 {@link LivingItemFunction#getTickPriority()} <b>稳定排序</b>功能列表。
     * 由 {@code LivingItem#commonSetup} 在所有域注册完毕后调用（幂等）。
     *
     * <p>⭐ tick 次序从此由 {@code getTickPriority()} <b>声明</b>，而不是散落在
     * 11 个 Registration 文件里的书写顺序（A1 下放后没有任何一处能读出完整顺序）。</p>
     *
     * <p>⚠️ 默认优先级全为 0 + 排序稳定 ⇒ <b>本方法不改变改造前的 tick 顺序</b>。
     * 它是「把隐式契约显式化」，不是行为变更（由 {@code TickOrderTest} 断言）。</p>
     *
     * <p><b>为什么只排序、不冻结注册</b>：曾计划同时「冻结列表、此后注册抛异常」，
     * 实测发现这会打断 {@code ComponentOwnershipTest} 等在测试中注册 mock 功能的既有模式 ——
     * 而「防 tick 期间注册」防的是一个<b>并不存在</b>的问题（现实中无人这么做）。
     * 按项目原则：不为想象中的问题付出真实代价。注册保持现状（随时可注册 + 清缓存）。</p>
     */
    public static void sortFunctionsByPriority() {
        FUNCTIONS.sort(Comparator.comparingInt(LivingItemFunction::getTickPriority));
        recomputeSelfSustaining();
        LOGGER.info("Living item functions sorted by getTickPriority: {} 个（稳定排序）", FUNCTIONS.size());
    }

    /**
     * 按 tick 优先级<b>稳定</b>排序 —— 抽出来是为了让测试能直接断言排序语义，
     * 而不必依赖全局注册状态（那样会污染其它测试）。
     */
    static List<LivingItemFunction> sortByPriority(List<LivingItemFunction> input) {
        List<LivingItemFunction> copy = new ArrayList<>(input);
        copy.sort(Comparator.comparingInt(LivingItemFunction::getTickPriority));
        return copy;
    }

    public static List<LivingItemFunction> getAllFunctions() {
        return FUNCTIONS_VIEW;
    }

    public static boolean isLivingItem(ItemStack stack) {
        return !stack.isEmpty() && stack.has(LivingComponents.IS_LIVING.value());
    }

    public static boolean isLivingMap(ItemStack stack) {
        return stack.is(Items.FILLED_MAP) && isLivingItem(stack);
    }

    public static List<LivingItemFunction> getApplicableFunctions(ItemStack stack) {
        if (!isLivingItem(stack)) return List.of();

        Item item = stack.getItem();
        List<LivingItemFunction> cached = APPLICABLE_CACHE.get(item);
        if (cached != null) return cached;

        List<LivingItemFunction> applicable = new ArrayList<>();
        for (LivingItemFunction function : FUNCTIONS) {
            if (function.canApply(stack)) {
                applicable.add(function);
            }
        }
        List<LivingItemFunction> result = Collections.unmodifiableList(applicable);
        APPLICABLE_CACHE.put(item, result);
        return result;
    }

    /**
     * 若这个物品被活化，会不会有任何 {@link LivingItemFunction} 认领它？
     *
     * <p>⚠️ <b>为什么不能直接调 {@link #getApplicableFunctions}</b>：它第一行就是
     * {@code if (!isLivingItem(stack)) return List.of()}，而 21/22 个
     * {@code canApply} 内部<b>也都含</b> {@code isLivingItem(stack)}
     * ⇒ 对未活化的物品，任何功能都不会认领，判定恒为「无功能」。
     *
     * <p>因此这里用 <b>copy 探针</b>：临时给副本打上 {@code IS_LIVING} 再走真实的
     * {@code canApply}。只在玩家点按钮时发生（非热路径），一次 {@code copy()} 可接受。
     *
     * <p>本方法<b>不走</b> {@code APPLICABLE_CACHE} —— 避免把「探针」结果写进按
     * {@code Item} 缓存的表里（该表的契约见 {@link #getApplicableFunctions}）。
     */
    public static boolean hasAnyFunctionFor(ItemStack stack) {
        if (isLivingItem(stack)) {
            return !getApplicableFunctions(stack).isEmpty();
        }
        ItemStack probe = stack.copy();
        probe.set(LivingComponents.IS_LIVING.value(), true);
        for (LivingItemFunction function : FUNCTIONS) {
            if (function.canApply(probe)) {
                return true;
            }
        }
        return false;
    }

    public static Set<DataComponentType<?>> getIgnoredComponentTypes(ItemStack stack) {
        if (!isLivingItem(stack)) return Set.of();

        Set<DataComponentType<?>> types = new HashSet<>();
        for (LivingItemFunction func : getApplicableFunctions(stack)) {
            types.addAll(func.getIgnoredComponentTypes());
        }
        return types;
    }

    /**
     * 清除活物品的所有功能数据。
     *
     * <p>每个 {@link LivingItemFunction} 通过 {@link LivingItemFunction#getOwnedComponentTypes()}
     * <b>自声明</b>它挂到物品上的 DataComponent，本方法遍历统一清除。
     * 因此<b>新增活物品不再需要修改此处</b>——第三方 addon 也就不必 fork 本仓库。
     *
     * <p>⚠️ 新增带了 DataComponent 的功能时，<b>必须</b>在自己的 Function 里覆盖
     * {@code getOwnedComponentTypes()} 列全组件；漏列会让该组件在取消活化后
     * 变成<b>孤儿数据</b>（组件还在、但无人认领），再次活化时会读到脏旧值。
     *
     * <p>{@code IS_LIVING} 属于框架本身、不属于任何功能，故单独清除。
     * 清除范围是<b>所有已注册功能</b>（而非仅适用于该物品的功能），
     * 以便连带清掉残留的历史数据。
     */
    public static void clearLivingData(ItemStack stack) {
        stack.remove(LivingComponents.IS_LIVING.value());

        for (LivingItemFunction func : FUNCTIONS) {
            for (DataComponentType<?> type : func.getOwnedComponentTypes()) {
                stack.remove(type);
            }
        }
    }

    /**
     * 切换活化状态（<b>不含任何时机副作用</b>）。
     *
     * <p>⚠️ <b>调用方几乎应该用 {@link LivingItemActivation#apply} 而不是本方法</b> ——
     * 本方法只翻标记，不派发 {@link LivingItemFunction#onActivated} /
     * {@link LivingItemFunction#onDeactivated}（例如活箱子不会掉物、活末影箱不会解绑）。
     * 保留它是因为 {@code apply} 内部与「单元测试造一个已活化的物品」都要用最底层的原语。</p>
     */
    public static void setLiving(ItemStack stack, boolean living) {
        if (living) {
            stack.set(LivingComponents.IS_LIVING.value(), true);
            // ⚠️ 死分支（2026-09-27 探针证实）：1.21.1 的箱子物品默认组件**已含**
            //    CONTAINER=EMPTY（与潜影盒同）⇒ !stack.has(...) 恒 false，此分支从不触发。
            //    「外部途径拿到的活箱子缺 CONTAINER」的场景不存在 —— 任何来源的箱子都自带。
            //    与熔炉那个已删的死代码同类（组件体系引入初期的防御，后来原版补了默认值）。
            if (stack.is(Items.CHEST) && !stack.has(net.minecraft.core.component.DataComponents.CONTAINER)) {
                stack.set(net.minecraft.core.component.DataComponents.CONTAINER,
                    net.minecraft.world.item.component.ItemContainerContents.EMPTY);
            }
        } else {
            clearLivingData(stack);
        }
    }

    /**
     * 泛型 getter —— 替代所有 getXxxData 方法。
     *
     * @param stack 物品
     * @param type DataComponent 类型
     * @param defaultValue 默认值（当物品上没有该组件时返回）
     * @param <T> 数据类型
     * @return 组件数据，或默认值
     */
    public static <T> T getData(ItemStack stack, DataComponentType<T> type, T defaultValue) {
        T data = stack.get(type);
        return data != null ? data : defaultValue;
    }

    /**
     * 泛型 setter —— 替代所有 setXxxData 方法。
     * 当数据等于默认值时自动移除组件，节省 NBT 空间。
     *
     * @param stack 物品
     * @param type DataComponent 类型
     * @param data 要设置的数据
     * @param defaultValue 默认值（用于判断是否移除组件）
     * @param <T> 数据类型
     */
    public static <T> void setData(ItemStack stack, DataComponentType<T> type, T data, T defaultValue) {
        if (Objects.equals(data, defaultValue)) {
            stack.remove(type);
        } else {
            stack.set(type, data);
        }
    }

    /**
     * 活工具主人 UUID（A2 建立绑定；null = 无主）。
     */
    @Nullable
    public static UUID getToolOwner(ItemStack stack) {
        return stack.get(LivingComponents.LIVING_TOOL_OWNER.value());
    }

    /** 便捷方法：写入活工具主人 UUID（null 表示清除）。 */
    public static void setToolOwner(ItemStack stack, @Nullable UUID owner) {
        if (owner == null) {
            stack.remove(LivingComponents.LIVING_TOOL_OWNER.value());
        } else {
            stack.set(LivingComponents.LIVING_TOOL_OWNER.value(), owner);
        }
    }

}
