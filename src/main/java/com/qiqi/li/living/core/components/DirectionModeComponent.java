package com.qiqi.li.living.core.components;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.ContainerContext;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.SlotResolver;
import com.qiqi.li.living.core.model.Pos2D;
import com.qiqi.li.living.core.model.SlotMapping;

/**
 * 方向模式组件 —— 统一管理活物品的槽位方向和传输方向配置。
 *
 * 支持两种模式：
 *
 * 1. SLOTS 模式（多槽位方向配置）：
 *    用于活熔炉等需要多个命名槽位的场景。
 *    通过 Map<String, Pos2D> 定义每个命名槽位的默认方向，
 *    如 input→LEFT, fuel→DOWN, output→RIGHT。
 *    方向数据存储在 ComponentState 中（slot_input_x, slot_input_y 等）。
 *    运行时方向不可变（由代码配置决定）。
 *
 * 2. TRANSFER 模式（传输方向配置）：
 *    用于活漏斗等需要动态传输方向的场景。
 *    通过 SlotMapping 定义源→目标的偏移方向。
 *    方向数据存储在 ComponentState 中（src_x, src_y, tgt_x, tgt_y）。
 *    运行时方向可通过 WASD 键入动态修改。
 *
 * 输入解析（仅 TRANSFER 模式）：
 *   - KeyParser 接口：自定义按键序列到 SlotMapping 的解析逻辑
 *   - WASDSequenceParser：默认实现，将 WASD 按键序列解析为方向
 *     例如："WD" → UP→DOWN（上传下），"AD" → LEFT→RIGHT（左传右）
 *   - validKeys：允许的按键集合（默认 W/A/S/D）
 *   - minInputs/maxInputs：最少/最多按键次数（默认 2/2）
 *
 * 构造器：
 *   - DirectionModeComponent()：默认 TRANSFER 模式 + WASD
 *   - DirectionModeComponent(Map<String, Pos2D>)：SLOTS 模式
 *   - DirectionModeComponent(Set, KeyParser, SlotMapping, int, int)：自定义 TRANSFER 模式
 *
 * 使用示例：
 * <pre>
 * // 活熔炉：SLOTS 模式
 * new DirectionModeComponent(Map.of("input", LEFT, "fuel", DOWN, "output", RIGHT))
 *
 * // 活漏斗：TRANSFER 模式（默认 WASD）
 * new DirectionModeComponent()
 * </pre>
 */
public class DirectionModeComponent implements ILivingComponent {

    /** 组件 ID，用于在 ComponentState 和 NBT 中标识此组件 */
    public static final String ID = "direction_mode";

    /**
     * 槽位解析结果 —— 方向偏移转换为绝对槽位索引后的统一返回结构。
     *
     * SLOTS 模式（活熔炉）：inputSlot / fuelSlot / outputSlot 有效
     * TRANSFER 模式（活漏斗）：sourceSlot / targetSlot 有效
     * 无效槽位统一为 -1
     */
    public record ResolvedSlots(
        int inputSlot,
        int fuelSlot,
        int outputSlot,
        int sourceSlot,
        int targetSlot
    ) {
        /** 创建全无效的空结果 */
        public static ResolvedSlots empty() {
            return new ResolvedSlots(-1, -1, -1, -1, -1);
        }

        /** 创建 SLOTS 模式的结果 */
        public static ResolvedSlots ofSlots(int inputSlot, int fuelSlot, int outputSlot) {
            return new ResolvedSlots(inputSlot, fuelSlot, outputSlot, -1, -1);
        }

        /** 创建 TRANSFER 模式的结果 */
        public static ResolvedSlots ofTransfer(int sourceSlot, int targetSlot) {
            return new ResolvedSlots(-1, -1, -1, sourceSlot, targetSlot);
        }
    }

    /**
     * 方向模式枚举。
     *
     * SLOTS：多命名槽位方向配置（活熔炉的 input/fuel/output）
     * TRANSFER：单传输方向映射（活漏斗的 source→target）
     */
    public enum DirectionMode {
        SLOTS,
        TRANSFER
    }

    /**
     * 按键解析器接口。
     *
     * 将原始按键序列（如 "WD"）解析为 SlotMapping。
     * 实现此接口可支持自定义按键方案（如 IJKL、方向键等）。
     */
    @FunctionalInterface
    public interface KeyParser {
        Optional<SlotMapping> parse(String rawInput);
    }

    private final DirectionMode mode;
    private final Map<String, Pos2D> defaultSlots;
    private final SlotMapping defaultMapping;
    private final Set<Character> validKeys;
    private final KeyParser keyParser;
    private final int minInputs;
    private final int maxInputs;

    /** 默认构造器：TRANSFER 模式 + WASD 输入 + 上传下默认方向 */
    public DirectionModeComponent() {
        this(DirectionMode.TRANSFER, Map.of(), SlotMapping.UP_TO_DOWN,
             Set.of('W', 'A', 'S', 'D'), new WASDSequenceParser(), 2, 2);
    }

    /** SLOTS 模式构造器：指定命名槽位的默认方向 */
    public DirectionModeComponent(Map<String, Pos2D> defaultSlots) {
        this(DirectionMode.SLOTS, defaultSlots, null, Set.of(), null, 0, 0);
    }

    /** 自定义 TRANSFER 模式构造器：指定按键方案和默认映射 */
    public DirectionModeComponent(Set<Character> validKeys, KeyParser parser,
                                  SlotMapping defaultMapping, int minInputs, int maxInputs) {
        this(DirectionMode.TRANSFER, Map.of(), defaultMapping, validKeys, parser, minInputs, maxInputs);
    }

    private DirectionModeComponent(DirectionMode mode, Map<String, Pos2D> defaultSlots,
                                   SlotMapping defaultMapping, Set<Character> validKeys,
                                   KeyParser keyParser, int minInputs, int maxInputs) {
        this.mode = mode;
        this.defaultSlots = Collections.unmodifiableMap(new HashMap<>(defaultSlots));
        this.defaultMapping = defaultMapping;
        this.validKeys = Collections.unmodifiableSet(new HashSet<>(validKeys));
        this.keyParser = keyParser;
        this.minInputs = minInputs;
        this.maxInputs = maxInputs;
    }

    @Override
    public String getComponentId() { return ID; }

    @Override
    public void tick(ComponentContext ctx, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {
    }

    @Override
    public ComponentState createDefaultState() {
        ComponentState state = new ComponentState();
        if (mode == DirectionMode.SLOTS) {
            for (var entry : defaultSlots.entrySet()) {
                String key = "slot_" + entry.getKey();
                state.setInt(key + "_x", entry.getValue().x());
                state.setInt(key + "_y", entry.getValue().y());
            }
        } else {
            if (defaultMapping != null) {
                state.setInt("src_x", defaultMapping.sourceOffset().x());
                state.setInt("src_y", defaultMapping.sourceOffset().y());
                state.setInt("tgt_x", defaultMapping.targetOffset().x());
                state.setInt("tgt_y", defaultMapping.targetOffset().y());
            }
        }
        return state;
    }

    @Override
    public void appendTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        if (mode == DirectionMode.SLOTS) {
            appendSlotsTooltip(state, tooltipAdder);
        } else {
            appendTransferTooltip(state, tooltipAdder);
        }
    }

    private void appendSlotsTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        for (String slotName : defaultSlots.keySet()) {
            Pos2D dir = getDirection(state, slotName);
            if (dir != null && dir != Pos2D.NONE) {
                tooltipAdder.accept(Component.translatable(
                    "tooltip.livingitem.direction.slot",
                    Component.translatable("slot.livingitem." + slotName),
                    dir.getSymbol()
                ).withStyle(net.minecraft.ChatFormatting.GRAY));
            }
        }
    }

    private void appendTransferTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        SlotMapping mapping = getCurrentMapping(state);
        if (mapping != null) {
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.direction.transfer",
                mapping.sourceOffset().getSymbol(),
                mapping.targetOffset().getSymbol(),
                Component.literal(mapping.displayName())
            ).withStyle(net.minecraft.ChatFormatting.GOLD));
        }
    }

    /** 获取当前模式（SLOTS 或 TRANSFER） */
    public DirectionMode getMode() { return mode; }

    /**
     * 将方向配置解析为容器中的绝对槽位索引。
     *
     * 根据当前模式执行不同的解析逻辑：
     * - SLOTS 模式：读取 input/fuel/output 方向，通过 SlotResolver 转为绝对索引
     * - TRANSFER 模式：读取 source/target 方向，通过 SlotResolver 转为绝对索引
     *
     * 此方法将"方向偏移 → 绝对槽位"的解析职责收归 DirectionModeComponent，
     * 调用方（FunctionExecutor、ItemTransferComponent）无需关心解析细节。
     *
     * @param state 组件状态（包含方向数据）
     * @param hostSlot 活物品所在的槽位索引（解析基准点）
     * @param containerSize 容器大小（用于边界检查）
     * @return 解析结果，SLOTS 模式填充 input/fuel/output，TRANSFER 模式填充 source/target
     */
    public ResolvedSlots resolveSlots(ComponentState state, int hostSlot, int containerSize) {
        if (mode == DirectionMode.SLOTS) {
            Pos2D inputDir = getDirection(state, "input");
            Pos2D fuelDir = getDirection(state, "fuel");
            Pos2D outputDir = getDirection(state, "output");

            return ResolvedSlots.ofSlots(
                SlotResolver.resolve(hostSlot, inputDir, containerSize),
                SlotResolver.resolve(hostSlot, fuelDir, containerSize),
                SlotResolver.resolve(hostSlot, outputDir, containerSize)
            );
        } else {
            SlotMapping mapping = getCurrentMapping(state);
            if (mapping == null) {
                return ResolvedSlots.empty();
            }

            return ResolvedSlots.ofTransfer(
                SlotResolver.resolve(hostSlot, mapping.sourceOffset(), containerSize),
                SlotResolver.resolve(hostSlot, mapping.targetOffset(), containerSize)
            );
        }
    }

    /**
     * 从 ItemStack 中读取 DirectionModeComponent 的状态（不修改 NBT）。
     *
     * 封装了"读取 functionTag → 查找/创建 dirState"的流程，
     * 让调用方无需关心 NBT 存储结构和默认状态创建逻辑。
     *
     * @param stack 目标物品
     * @param functionId 功能 ID（如 LivingHopperFunction.ID）
     * @return 当前方向状态，如果物品无效返回 null
     */
    public static ComponentState readStateFromStack(ItemStack stack, String functionId) {
        if (stack == null || stack.isEmpty()) return null;

        CompoundTag functionTag = LivingItemManager.getFunctionData(stack, functionId);
        if (functionTag.contains(ID)) {
            return ComponentState.fromNBT(functionTag.getCompound(ID));
        }
        return new DirectionModeComponent().createDefaultState();
    }

    /**
     * 在 ItemStack 中更新 DirectionModeComponent 的状态（通用 NBT 读写模板）。
     *
     * 封装了"读取 functionTag → 获取/创建 dirState → 执行更新 → 写回 NBT"的完整流程，
     * 让调用方只需提供更新逻辑，无需关心 NBT 存储结构。
     *
     * 使用示例：
     * <pre>
     * DirectionModeComponent.updateStateInStack(stack, "living_hopper",
     *     (dirComp, dirState) -> dirComp.updateMapping(dirState, newMapping));
     * </pre>
     *
     * @param stack 目标物品
     * @param functionId 功能 ID（如 LivingHopperFunction.ID）
     * @param updater 状态更新函数，接收 (DirectionModeComponent, ComponentState)，
     *                返回是否更新成功
     * @return 如果更新成功返回 true
     */
    public static boolean updateStateInStack(ItemStack stack, String functionId,
                                              BiFunction<DirectionModeComponent, ComponentState, Boolean> updater) {
        CompoundTag functionTag = LivingItemManager.getFunctionData(stack, functionId);

        ComponentState dirState;
        if (functionTag.contains(ID)) {
            dirState = ComponentState.fromNBT(functionTag.getCompound(ID));
        } else {
            dirState = new DirectionModeComponent().createDefaultState();
        }

        DirectionModeComponent dirComp = new DirectionModeComponent();
        boolean success = updater.apply(dirComp, dirState);
        if (!success) return false;

        functionTag.put(ID, dirState.toNBT());
        LivingItemManager.setFunctionData(stack, functionId, functionTag);
        return true;
    }

    /**
     * 获取指定命名槽位的当前方向（SLOTS 模式）。
     *
     * 从 ComponentState 中读取 slot_{name}_x 和 slot_{name}_y，
     * 如果不存在则回退到默认方向。
     *
     * @param state 组件状态
     * @param slotName 槽位名称（如 "input"、"fuel"、"output"）
     * @return 当前方向偏移，如果槽位名不存在返回 Pos2D.NONE
     */
    public Pos2D getDirection(ComponentState state, String slotName) {
        String key = "slot_" + slotName;
        int x = state.getInt(key + "_x", Integer.MIN_VALUE);
        int y = state.getInt(key + "_y", Integer.MIN_VALUE);

        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE) {
            return defaultSlots.getOrDefault(slotName, Pos2D.NONE);
        }

        return new Pos2D(x, y);
    }

    /**
     * 设置指定命名槽位的方向（SLOTS 模式）。
     *
     * @param state 组件状态
     * @param slotName 槽位名称
     * @param direction 新方向
     * @return 是否成功设置（仅 SLOTS 模式允许）
     */
    public boolean setDirection(ComponentState state, String slotName, Pos2D direction) {
        if (mode != DirectionMode.SLOTS) return false;
        String key = "slot_" + slotName;
        state.setInt(key + "_x", direction.x());
        state.setInt(key + "_y", direction.y());
        return true;
    }

    /**
     * 获取当前的传输方向映射（TRANSFER 模式）。
     *
     * 从 ComponentState 中读取 src_x/y 和 tgt_x/y，
     * 优先匹配预设常量（SlotMapping.PRESETS），
     * 未匹配则创建自定义 SlotMapping。
     *
     * @param state 组件状态
     * @return 当前传输映射，SLOTS 模式返回 null
     */
    public SlotMapping getCurrentMapping(ComponentState state) {
        if (mode != DirectionMode.TRANSFER) return null;

        int srcX = state.getInt("src_x", Integer.MIN_VALUE);
        int srcY = state.getInt("src_y", Integer.MIN_VALUE);
        int tgtX = state.getInt("tgt_x", Integer.MIN_VALUE);
        int tgtY = state.getInt("tgt_y", Integer.MIN_VALUE);

        if (srcX == Integer.MIN_VALUE || tgtX == Integer.MIN_VALUE) {
            return defaultMapping;
        }

        Pos2D source = new Pos2D(srcX, srcY);
        Pos2D target = new Pos2D(tgtX, tgtY);

        return findMatchingPreset(source, target)
            .orElseGet(() -> new SlotMapping(source, target,
                source.getSymbol() + "-" + target.getSymbol(),
                "自定义"));
    }

    /**
     * 更新传输方向映射（TRANSFER 模式）。
     *
     * 将新的 SlotMapping 的坐标写入 ComponentState。
     *
     * @param state 组件状态
     * @param newMapping 新的传输映射
     * @return 是否成功更新（仅 TRANSFER 模式允许）
     */
    public boolean updateMapping(ComponentState state, SlotMapping newMapping) {
        if (mode != DirectionMode.TRANSFER || newMapping == null) return false;

        state.setInt("src_x", newMapping.sourceOffset().x());
        state.setInt("src_y", newMapping.sourceOffset().y());
        state.setInt("tgt_x", newMapping.targetOffset().x());
        state.setInt("tgt_y", newMapping.targetOffset().y());
        return true;
    }

    /**
     * 通过按键输入更新传输方向（TRANSFER 模式）。
     *
     * 验证输入合法性后，通过 KeyParser 解析为 SlotMapping，
     * 然后调用 updateMapping() 写入状态。
     *
     * @param state 组件状态
     * @param rawInput 原始按键序列（如 "WD"）
     * @return 是否成功更新
     */
    public boolean updateFromInput(ComponentState state, String rawInput) {
        if (mode != DirectionMode.TRANSFER || keyParser == null || rawInput == null) return false;

        if (!validateInput(rawInput)) return false;

        Optional<SlotMapping> parsed = keyParser.parse(rawInput);
        return parsed.map(mapping -> updateMapping(state, mapping)).orElse(false);
    }

    private boolean validateInput(String rawInput) {
        if (rawInput.isEmpty()) return false;
        if (rawInput.length() < minInputs || rawInput.length() > maxInputs) return false;

        for (char c : rawInput.toUpperCase().toCharArray()) {
            if (!validKeys.contains(c)) return false;
        }
        return true;
    }

    private Optional<SlotMapping> findMatchingPreset(Pos2D source, Pos2D target) {
        for (var preset : SlotMapping.PRESETS) {
            if (preset.sourceOffset().equals(source) && preset.targetOffset().equals(target)) {
                return Optional.of(preset);
            }
        }
        return Optional.empty();
    }

    public Map<String, Pos2D> getDefaultSlots() {
        return defaultSlots;
    }

    public SlotMapping getDefaultMapping() {
        return defaultMapping;
    }

    public Set<Character> getValidKeys() {
        return validKeys;
    }

    /**
     * WASD 按键序列解析器 —— 默认的 KeyParser 实现。
     *
     * 将 WASD 按键序列解析为 SlotMapping：
     * - 第 1 个键 = 源方向（W=↑, A=←, S=↓, D=→）
     * - 第 2 个键 = 目标方向
     *
     * 示例：
     *   "WD" → UP→DOWN（上传下）
     *   "AD" → LEFT→RIGHT（左传右）
     *   "SW" → DOWN→UP（下传上）
     */
    public static class WASDSequenceParser implements KeyParser {
        private static final Map<Character, Pos2D> KEY_MAP = Map.of(
            'W', Pos2D.UP,
            'S', Pos2D.DOWN,
            'A', Pos2D.LEFT,
            'D', Pos2D.RIGHT
        );

        @Override
        public Optional<SlotMapping> parse(String rawInput) {
            if (rawInput == null || rawInput.length() < 2) return Optional.empty();

            String upper = rawInput.toUpperCase();
            Pos2D source = KEY_MAP.get(upper.charAt(0));
            Pos2D target = KEY_MAP.get(upper.charAt(1));

            if (source == null || target == null) return Optional.empty();

            return Optional.of(new SlotMapping(source, target,
                source.getSymbol() + "→" + target.getSymbol(),
                source.getSymbol() + "到" + target.getSymbol()));
        }
    }
}