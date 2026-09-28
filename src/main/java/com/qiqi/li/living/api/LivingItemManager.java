package com.qiqi.li.living.api;

import com.qiqi.li.living.transfer.LivingComponents;
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

import com.qiqi.li.living.domain.tools.LivingToolAction;
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



    /**
     * 相位快照（2026-09-09 落盘）：红电相位账本的跨会话持久化。
     * 与流体/应力附件不同，这个带 {@code serialize(Codec)}——真正写入存档，
     * 退出重进 / 区块卸载超时后锁相状态无缝续接（详见 PhaseSnapshot javadoc）。
     */













    /**
     * 活耕地湿润标志（图标 moist/dry 变体切换数据源）。
     * tick 在湿润状态翻转时写标志 + syncSlotToClients（稳态零写入零同步）；
     * 进 {@link LivingItemFunction#getIgnoredComponentTypes}（湿润/干燥耕地可堆叠），
     * 参考熔炉燃烧标志 LIVING_FURNACE_BURNING 先例。
     * 派生数据不落盘（仅网络同步）：湿润度每 tick 从流体邻接重算，持久化无正确性价值。
     */










    /**
     * 活工具记忆（挖掘记忆 + 交互记忆）。
     *
     * <p>必须网络同步：客户端要据此<b>本地重算射线</b>来渲染悬浮模型与动画（{@code K30}），
     * 服务端因此无需同步"命中了哪个方块"。</p>
     */

    /**
     * 活工具挖掘进度（{@code L21}）：当前正在挖的目标 + 世界轴起始 tick。
     *
     * <p>组件缺失 = 没在挖。只在<b>开始挖时写一次</b>，之后每 tick 重算进度，不写组件。</p>
     */

    /**
     * 活工具<b>挖掘预计总 tick</b>（{@code K} 组动画用，<b>仅网络同步、不落盘</b>）。
     *
     * <p>只在<b>开始挖一个新目标</b>时写一次 —— 挖掘期间速度恒定，无需每 tick 更新。
     * 客户端据此决定转圈快慢（挖得越快转得越快）。</p>
     *
     * <p>⭐ <b>由服务端算好给过来</b>，客户端不自算（{@code L48}）：
     * 挖掘速度牵扯 FakePlayer 属性（效率附魔走 {@code MINING_EFFICIENCY}，见 {@code L46}），
     * 客户端复刻必然不准。</p>
     *
     * <p>不 {@code persistent}：纯派生数据，存档重载后会重新算出来。</p>
     */
    /**
     * 活工具<b>最近一次瞬时动作</b>（{@code K} 组动画用，<b>仅网络同步、不落盘</b>）。
     *
     * <p>见 {@link com.qiqi.li.living.domain.tools.LivingToolAction} —— 交互是瞬时的，
     * 客户端无从得知"刚刚发生了交互"，也拿不到"交互在哪一格"（容器形态起点埋在方块里）。</p>
     */

    /**
     * 活工具主人 UUID（{@code L25}）。
     *
     * <p>用途：回放时 FakePlayer 用它伪装成真实玩家，以通过领地 / 保护插件的权限判定
     * （Create 的 {@code DeployerGameProfile}、Mekanism 同款技巧 —— 覆写
     * {@code GameProfile#getId()} 返回主人 UUID）。</p>
     *
     * <ul>
     *   <li>玩家手动活化 → 记录该玩家 UUID</li>
     *   <li>未来「自动活化物品的活物品」→ 可写入自定义 UUID</li>
     *   <li><b>组件缺失（null）= 无主人</b> → 回退到通用 FakePlayer</li>
     * </ul>
     *
     * <p>服务端专用，<b>不需要</b>网络同步（客户端不参与回放）。</p>
     */
    private static final List<LivingItemFunction> FUNCTIONS = new ArrayList<>();
    private static final List<LivingItemFunction> FUNCTIONS_VIEW = Collections.unmodifiableList(FUNCTIONS);
    private static final Map<Item, List<LivingItemFunction>> APPLICABLE_CACHE = new ConcurrentHashMap<>();

    public static void registerFunction(LivingItemFunction function) {
        LOGGER.info("Registering living item function: {}", function.getFunctionId());
        FUNCTIONS.add(function);
        APPLICABLE_CACHE.clear();
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

    /** 切换活化状态（不记录主人）。 */
    public static void setLiving(ItemStack stack, boolean living) {
        setLiving(stack, living, null);
    }

    /**
     * 切换活化状态。
     *
     * @param owner 主人 UUID（活工具用，见 {@link #LIVING_TOOL_OWNER}）；null = 不记录
     */
    public static void setLiving(ItemStack stack, boolean living, @Nullable UUID owner) {
        if (living) {
            stack.set(LivingComponents.IS_LIVING.value(), true);
            if (owner != null) {
                setToolOwner(stack, owner);
            }
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

    /** 活熔炉是否在燃烧（派生标志，仅网络同步；图标 active/idle 变体数据源）。 */
    public static boolean isFurnaceBurning(ItemStack stack) {
        Boolean burning = stack.get(LivingComponents.LIVING_FURNACE_BURNING.value());
        return burning != null && burning;
    }

    /**
     * 便捷方法：写入熔炉燃烧标志（false 时移除组件，节省 NBT）。
     */
    public static void setFurnaceBurning(ItemStack stack, boolean burning) {
        if (burning) {
            stack.set(LivingComponents.LIVING_FURNACE_BURNING.value(), true);
        } else {
            stack.remove(LivingComponents.LIVING_FURNACE_BURNING.value());
        }
    }

    /**
     * 挖掘预计总 tick（{@code K} 组动画用）。
     *
     * @return 预计 tick 数；{@code null} = 未知（用默认转速）
     */
    public static Integer getToolDigTicks(ItemStack stack) {
        return stack.get(LivingComponents.LIVING_TOOL_DIG_TICKS.value());
    }

    /** 便捷方法：写入挖掘预计总 tick（null = 清除）。 */
    public static void setToolDigTicks(ItemStack stack, @Nullable Integer ticks) {
        if (ticks == null) {
            stack.remove(LivingComponents.LIVING_TOOL_DIG_TICKS.value());
        } else {
            stack.set(LivingComponents.LIVING_TOOL_DIG_TICKS.value(), ticks);
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

    /** 活耕地湿润标志（未打标志 = 干燥；图标 moist/dry 变体切换数据源） */
    public static boolean isFarmlandMoist(ItemStack stack) {
        return getData(stack, LivingComponents.LIVING_FARMLAND_MOIST.value(), Boolean.FALSE);
    }

    public static void setFarmlandMoist(ItemStack stack, boolean moist) {
        setData(stack, LivingComponents.LIVING_FARMLAND_MOIST.value(), moist, Boolean.FALSE);
    }

}
