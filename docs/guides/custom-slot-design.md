# 自定义输入/输出/燃料槽位实现方案

## 一、现状分析

### 当前实现方式（固定相对位置）

```java
// LivingFurnaceFunction.java 第108-110行
int inputIndex = getRelativeIndex(slotIndex, CONTAINER_WIDTH, -1, 0);  // 左边
int fuelIndex = getRelativeIndex(slotIndex, CONTAINER_WIDTH, 0, 1);    // 下边
int outputIndex = getRelativeIndex(slotIndex, CONTAINER_WIDTH, 1, 0);  // 右边

// 计算公式：newSlot = (row + dy) * width + (col + dx)
```

**局限性**：
- ❌ 只能上下左右四个方向
- ❌ 无法支持斜向、跨行、多槽位等复杂场景
- ❌ 不同活物品的槽位模式无法复用配置

### 需求场景举例

| 活物品类型 | 输入槽位 | 燃料槽位 | 输出槽位 |
|-----------|---------|---------|---------|
| 活熔炉 | 左边1格 | 下边1格 | 右边1格 |
| 活大熔炉（假设） | 左边3格 | 下边2格 | 右边3格 |
| 活漏斗 | 上边或左边（可切换） | 无 | 下边或右边（可切换） |
| 活工作台 | 3x3九宫格 | 无 | 结果槽 |
| 活耕地 | 自身（种子） | 上方水桶 | 自身（作物） |

## 二、6种自定义槽位实现方案

---

## 方案1：增强型方向枚举（最简单，推荐起步用）

### 核心思想
扩展现有 `Direction2D` 枚举，支持更多预设模式。

```java
// living/core/SlotPattern.java (替代Direction2D)
public enum SlotPattern {
    // ===== 基础方向（兼容原有逻辑）=====
    LEFT(-1, 0),
    RIGHT(1, 0),
    UP(0, -1),
    DOWN(0, 1),

    // ===== 扩展方向 =====
    UP_LEFT(-1, -1),
    UP_RIGHT(1, -1),
    DOWN_LEFT(-1, 1),
    DOWN_RIGHT(1, 1),

    // ===== 特殊位置 =====
    SELF(0, 0),           // 自身槽位（用于活耕地消耗种子）
    NONE(9999, 9999);     // 无效/不需要该槽位

    public final int dx, dy;

    SlotPattern(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }

    public boolean isValid() {
        return this != NONE;
    }
}
```

### 使用示例：活漏斗（可切换方向）

```java
// living/LivingHopperFunction.java
public class LivingHopperFunction implements LivingItemFunction {

    private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
        .withInput(SlotPattern.UP)       // 默认从上方抽取
        .withOutput(SlotPattern.DOWN)    // 默认向下方输出
        .addComponent(StateMachineComponent.class,
            ComponentConfig.of("states", List.of(
                SlotPattern.UP, SlotPattern.DOWN,     // 状态0: 上→下
                SlotPattern.LEFT, SlotPattern.RIGHT,   // 状态1: 左→右
                SlotPattern.DOWN, SlotPattern.UP       // 状态2: 下→上
            ))
        )
        .addComponent(ItemMoveComponent.class, ComponentConfig.empty());

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, Level level) {
        // ... 轮询逻辑 ...
        FunctionExecutor.INSTANCE.tick(context, entry.slot(), entry.stack(), CONFIG, level);
    }
}
```

**优点**：
- ✅ 最小改动，完全向后兼容
- ✅ 代码清晰，易于理解
- ✅ 支持8个方向 + 自身 + 无效

**缺点**：
- ❌ 只能指定单个槽位，不支持多槽位
- ❌ 不支持复杂的条件选择逻辑

**适用场景**：80%的简单活物品（熔炉、高炉、烟熏炉、基础漏斗等）

---

## 方案2：偏移量列表（支持多槽位）

### 核心思想
允许一个槽位类型对应多个相对位置的槽位。

```java
// living/core/MultiSlotResolver.java
public final class MultiSlotResolver {

    /**
     * 解析多个相对偏移对应的槽位。
     *
     * @param baseSlot 基准槽位
     * @param offsets 偏移量列表 [(dx1,dy1), (dx2,dy2), ...]
     * @return 有效槽位数组（已过滤越界的）
     */
    public static int[] resolveMultiple(int baseSlot, int[][] offsets) {
        List<Integer> validSlots = new ArrayList<>();

        for (int[] offset : offsets) {
            int slot = resolveSingle(baseSlot, offset[0], offset[1]);
            if (slot >= 0) validSlots.add(slot);
        }

        return validSlots.stream().mapToInt(i -> i).toArray();
    }

    private static int resolveSingle(int baseSlot, int dx, int dy) {
        int row = baseSlot / 9;
        int col = baseSlot % 9;
        int newCol = col + dx;
        int newRow = row + dy;

        if (newCol < 0 || newCol >= 9 || newRow < 0) return -1;
        return newRow * 9 + newCol;
    }

    // ===== 预定义常用模式 =====

    /** 左边3格 */
    public static int[] leftRow(int baseSlot) {
        return resolveMultiple(baseSlot, new int[][]{{-1,0}, {-2,0}, {-3,0}});
    }

    /** 右边3格 */
    public static int[] rightRow(int baseSlot) {
        return resolveMultiple(baseSlot, new int[][]{{1,0}, {2,0}, {3,0}});
    }

    /** 下方2格 */
    public static int[] downTwo(int baseSlot) {
        return resolveMultiple(baseSlot, new int[][]{{0,1}, {0,2}});
    }

    /** 3x3范围（不包括自身） */
    public static int[] surrounding3x3(int baseSlot) {
        return resolveMultiple(baseSlot, new int[][]{
            {-1,-1}, {0,-1}, {1,-1},
            {-1,0},          {1,0},
            {-1,1},  {0,1},  {1,1}
        });
    }
}
```

### 使用示例：活大熔炉（多槽位输入输出）

```java
// living/LivingBigFurnaceFunction.java
public class LivingBigFurnaceFunction implements LivingItemFunction {

    private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
        .withCustomInputResolver((baseSlot) -> MultiSlotResolver.leftRow(baseSlot))      // 左边3格输入
        .withCustomFuelResolver((baseSlot) -> MultiSlotResolver.downTwo(baseSlot))       // 下方2格燃料
        .withCustomOutputResolver((baseSlot) -> MultiSlotResolver.rightRow(baseSlot))     // 右边3格输出
        .addComponent(FuelConsumeComponent.class, ComponentConfig.of("recipe_type", RecipeType.SMELTING))
        .addComponent(ProgressComponent.class, ComponentConfig.of("total_ticks", 200))
        .addComponent(ItemTransformComponent.class, ComponentConfig.of(
            "recipe_type", RecipeType.SMELTING,
            "multi_slot", true  // 启用多槽位批量处理
        ));

    // ... 其余代码 ...
}
```

**优点**：
- ✅ 支持多槽位输入/输出
- ✅ 预定义常用模式，减少重复代码
- ✅ 可以组合出任意形状的槽位区域

**缺点**：
- ❌ 仍然是静态配置，运行时不能动态改变
- ❌ 复杂的多槽位逻辑需要组件配合

**适用场景**：需要多槽位的活物品（大熔炉、批量处理器、区域耕作等）

---

## 方案3：槽位选择器接口（高度可定制）

### 核心思想
使用函数式接口/策略模式，让每个活物品可以自定义槽位选择逻辑。

```java
// living/core/SlotSelector.java
@FunctionalInterface
public interface SlotSelector {

    /**
     * 选择符合条件的槽位。
     *
     * @param context 容器上下文
     * @param baseSlot 活物品所在槽位
     * @return 符合条件的槽位数组（可能为空数组）
     */
    int[] select(ContainerContext context, int baseSlot);

    // ===== 工厂方法：创建常用选择器 =====

    /** 固定相对位置 */
    static SlotSelector relative(int dx, int dy) {
        return (ctx, slot) -> {
            int result = SlotResolver.resolve(slot, Direction2D.fromOffset(dx, dy));
            return result >= 0 ? new int[]{result} : new int[0];
        };
    }

    /** 多个固定相对位置 */
    static SlotSelector multiRelative(int[][] offsets) {
        return (ctx, slot) -> MultiSlotResolver.resolveMultiple(slot, offsets);
    }

    /** 条件选择：第一个满足条件的相邻槽位 */
    static SlotSelector firstMatching(Predicate<ItemStack> condition, Direction2D... directions) {
        return (ctx, slot) -> {
            for (Direction2D dir : directions) {
                int targetSlot = SlotResolver.resolve(slot, dir);
                if (targetSlot >= 0) {
                    ItemStack stack = ctx.getItem(targetSlot);
                    if (condition.test(stack)) {
                        return new int[]{targetSlot};
                    }
                }
            }
            return new int[0];
        };
    }

    /** 所有满足条件的相邻槽位 */
    static SlotSelector allMatching(Predicate<ItemStack> condition, Direction2D... directions) {
        return (ctx, slot) -> {
            List<Integer> matches = new ArrayList<>();
            for (Direction2D dir : directions) {
                int targetSlot = SlotResolver.resolve(slot, dir);
                if (targetSlot >= 0 && condition.test(ctx.getItem(targetSlot))) {
                    matches.add(targetSlot);
                }
            }
            return matches.stream().mapToInt(i -> i).toArray();
        };
    }

    /** 绝对索引（不推荐，除非特殊需求） */
    static SlotSelector absolute(int... slots) {
        return (ctx, slot) -> slots;
    }
}
```

### 使用示例：智能活熔炉（自动寻找最近的非空输入槽）

```java
// living/LivingSmartFurnaceFunction.java
public class LivingSmartFurnaceFunction implements LivingItemFunction {

    private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
        // 自动选择左边第一个有物品的槽位作为输入
        .withInputSelector(SlotSelector.firstMatching(
            stack -> !stack.isEmpty(),
            Direction2D.LEFT, Direction2D.RIGHT, Direction2D.DOWN  // 优先级：左>右>下
        ))
        // 从下方任意有燃料的槽位取燃料
        .withFuelSelector(SlotSelector.firstMatching(
            stack -> !stack.isEmpty() && ((IItemExtension)stack.getItem()).getBurnTime(stack, RecipeType.SMELTING) > 0,
            Direction2D.DOWN
        ))
        // 向右边第一个有空位的槽位输出
        .withOutputSelector(SlotSelector.firstMatching(
            stack -> stack.isEmpty() || stack.getCount() < stack.getMaxStackSize(),
            Direction2D.RIGHT
        ))
        .addComponent(FuelConsumeComponent.class, ...)
        .addComponent(ProgressComponent.class, ...)
        .addComponent(ItemTransformComponent.class, ...);

    // ... 其余代码 ...
}
```

**优点**：
- ✅ 极度灵活，可以实现任意复杂的槽位选择逻辑
- ✅ 支持条件判断（物品是否为空、是否有燃料价值等）
- ✅ 支持优先级排序
- ✅ 函数式编程风格，代码简洁优雅

**缺点**：
- ❌ 复杂度较高，需要理解函数式接口
- ❌ 调试难度稍大（lambda表达式堆栈信息不够直观）

**适用场景**：需要智能选择的活物品（智能熔炉、自适应漏斗、高级工作台等）

---

## 方案4：NBT持久化配置（运行时可修改）

### 核心思想
将槽位配置存储在活物品自身的NBT中，允许玩家通过GUI或命令在游戏内修改。

#### 4.1 数据结构设计

```java
// NBT 数据结构示例（活漏斗的自定义配置）
{
  "living_hopper": {
    "components": { ... },
    "slot_config": {                          ← 新增：槽位配置块
      "version": 1,                           ← 配置版本号（用于迁移）
      "mode": "directional",                  ← 配置模式
      "input": {                              ← 输入槽配置
        "type": "single_direction",
        "direction": "up"                     ← 可选: up/down/left/right/self/none
      },
      "output": {                             ← 输出槽配置
        "type": "single_direction",
        "direction": "down"
      },
      "fuel": {                               ← 燃料槽配置（可选）
        "type": "none"
      },
      "state_index": 0                        ← 当前状态索引（用于状态机）
    }
  }
}
```

#### 4.2 配置管理器

```java
// living/core/SlotConfigManager.java
public final class SlotConfigManager {

    private static final String KEY_SLOT_CONFIG = "slot_config";
    private static final String KEY_MODE = "mode";
    private static final String KEY_INPUT = "input";
    private static final String KEY_OUTPUT = "output";
    private static final String KEY_FUEL = "fuel";
    private static final String KEY_TYPE = "type";
    private static final String KEY_DIRECTION = "direction";
    private static final String KEY_STATE_INDEX = "state_index";

    /**
     * 从物品NBT加载槽位配置。
     *
     * @param stack 活物品
     * @param functionId 功能ID
     * @return 解析后的槽位配置对象；如果NBT中无配置则返回默认值
     */
    public static SlotConfiguration loadConfig(ItemStack stack, String functionId) {
        CompoundTag funcData = LivingItemManager.getFunctionData(stack, functionId);

        if (!funcData.contains(KEY_SLOT_CONFIG)) {
            return getDefaultConfig(functionId);  // 返回默认配置
        }

        CompoundTag configTag = funcData.getCompound(KEY_SLOT_CONFIG);
        return deserialize(configTag);
    }

    /**
     * 将槽位配置保存到物品NBT。
     */
    public static void saveConfig(ItemStack stack, String functionId, SlotConfiguration config) {
        CompoundTag funcData = LivingItemManager.getFunctionData(stack, functionId).copy();
        CompoundTag configTag = serialize(config);
        funcData.put(KEY_SLOT_CONFIG, configTag);
        LivingItemManager.setFunctionData(stack, functionId, funcData);
    }

    /**
     * 解析配置为实际的SlotSelector（供FunctionExecutor使用）。
     */
    public static SlotSelector resolveSelector(SlotConfiguration.SlotDef def) {
        switch (def.type) {
            case "single_direction":
                Direction2D dir = Direction2D.valueOf(def.direction.toUpperCase());
                return SlotSelector.relative(dir.dx, dir.dy);

            case "multi_direction":
                int[][] offsets = parseOffsets(def.offsets);
                return SlotSelector.multiRelative(offsets);

            case "conditional":
                return buildConditionalSelector(def.condition);

            case "absolute":
                return SlotSelector.absolute(def.slots);

            case "none":
            default:
                return (ctx, slot) -> new int[0];
        }
    }

    // ... 序列化/反序列化辅助方法 ...
}
```

#### 4.3 配置数据类

```java
// living/core/SlotConfiguration.java
public class SlotConfiguration {

    public int version = 1;
    public String mode = "directional";  // directional / custom / advanced
    public SlotDef input;
    public SlotDef output;
    public SlotDef fuel;
    public int stateIndex = 0;  // 用于状态机

    public record SlotDef(
        String type,              // single_direction / multi_direction / conditional / absolute / none
        String direction,         // for single_direction: up/down/left/right/self
        int[][] offsets,          // for multi_direction: [[dx,dy], ...]
        String condition,         // for conditional: predicate expression
        int[] slots,              // for absolute: [slotIndex, ...]
        Map<String, Object> params  // additional parameters
    ) {}

    public static SlotConfiguration defaultFurnace() {
        return new SlotConfiguration()
            .withInput(new SlotDef("single_direction", "left", null, null, null, null))
            .withOutput(new SlotDef("single_direction", "right", null, null, null, null))
            .withFuel(new SlotDef("single_direction", "down", null, null, null, null));
    }

    public static SlotConfiguration defaultHopper() {
        return new SlotConfiguration()
            .withInput(new SlotDef("single_direction", "up", null, null, null, null))
            .withOutput(new SlotDef("single_direction", "down", null, null, null, null))
            .withFuel(new SlotDef("none", null, null, null, null, null));
    }

    // Builder methods...
}
```

#### 4.4 GUI编辑器（客户端）

```java
// client/gui/SlotConfigScreen.java
public class SlotConfigScreen extends Screen {

    private final ItemStack targetItem;
    private final String functionId;
    private SlotConfiguration currentConfig;

    private DirectionButton inputDirButton;
    private DirectionButton outputDirButton;
    private Button saveButton;
    private Button resetButton;

    public SlotConfigScreen(ItemStack stack, String functionId) {
        super(Component.translatable("gui.livingitem.slot_config.title"));
        this.targetItem = stack;
        this.functionId = functionId;
        this.currentConfig = SlotConfigManager.loadConfig(stack, functionId);
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // 输入方向选择按钮
        this.addRenderableWidget(inputDirButton = new DirectionButton(
            centerX - 80, centerY - 40,
            currentConfig.input.direction,
            (dir) -> updateInputDirection(dir)
        ));

        // 输出方向选择按钮
        this.addRenderableWidget(outputDirButton = new DirectionButton(
            centerX + 40, centerY - 40,
            currentConfig.output.direction,
            (dir) -> updateOutputDirection(dir)
        ));

        // 保存按钮
        this.addRenderableWidget(saveButton = Button.builder(
            Component.translatable("gui.livingitem.save"),
            (btn) -> saveConfig()
        ).bounds(centerX - 50, centerY + 40, 100, 20).build());

        // 重置按钮
        this.addRenderableWidget(resetButton = Button.builder(
            Component.translatable("gui.livingitem.reset"),
            (btn) -> resetToDefault()
        ).bounds(centerX - 50, centerY + 65, 100, 20).build());
    }

    private void updateInputDirection(String direction) {
        currentConfig = new SlotConfiguration(
            currentConfig.version,
            currentConfig.mode,
            new SlotDef("single_direction", direction, null, null, null, null),
            currentConfig.output,
            currentConfig.fuel,
            currentConfig.stateIndex
        );
        inputDirButton.setDirection(direction);
    }

    private void saveConfig() {
        PacketDistributor.sendToServer(new SlotConfigUpdatePacket(targetItem, functionId, currentConfig));
        this.minecraft.setScreen(null);  // 关闭界面
    }

    private void resetToDefault() {
        currentConfig = SlotConfigManager.getDefaultConfig(functionId);
        inputDirButton.setDirection(currentConfig.input.direction);
        outputDirButton.setDirection(currentConfig.output.direction);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);

        guiGraphics.drawString(this.font, "Input:", centerX - 80, centerY - 55, 0xAAFFFF);
        guiGraphics.drawString(this.font, "Output:", centerX + 40, centerY - 55, 0xAAFFFF);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
```

#### 4.5 触发方式：Shift+右键打开配置界面

```java
// 在LivingItem中监听右键事件
@SubscribeEvent
public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
    Player player = event.getEntity();
    ItemStack heldItem = player.getMainHandItem();

    if (!LivingItemManager.isLivingItem(heldItem)) return;

    // Shift+右键活物品 → 打开配置界面
    if (player.isShiftKeyDown()) {
        event.setCanceled(true);

        if (player.level().isClientSide) {
            Minecraft.getInstance().setScreen(
                new SlotConfigScreen(heldItem, "living_hopper")  // 或自动检测functionId
            );
        }
    }
}
```

**优点**：
- ✅ 运行时完全可定制
- ✅ 配置持久化到NBT，重启后保留
- ✅ 玩家友好（GUI操作）
- ✅ 支持导出/导入配置模板

**缺点**：
- ❌ 开发工作量较大（需要GUI、网络包、事件处理）
- ❌ 需要考虑安全性（防止恶意配置导致崩溃）

**适用场景**：需要高度定制的活物品（高级漏斗、多功能工具、模组包集成等）

---

## 方案5：基于标签的模式系统（资源包友好）

### 核心思想
预定义多种槽位模式，通过字符串标识符引用，支持资源包扩展。

#### 5.1 模式注册表

```java
// living/core/SlotPatternRegistry.java
public final class SlotPatternRegistry {

    private static final Map<String, SlotPatternDefinition> PATTERNS = new HashMap<>();

    static {
        // 注册内置模式
        register("furnace_standard", new SlotPatternDefinition()
            .input(SlotSelector.relative(-1, 0))
            .fuel(SlotSelector.relative(0, 1))
            .output(SlotSelector.relative(1, 0))
        );

        register("hopper_vertical", new SlotPatternDefinition()
            .input(SlotSelector.relative(0, -1))
            .output(SlotSelector.relative(0, 1))
        );

        register("hopper_horizontal", new SlotPatternDefinition()
            .input(SlotSelector.relative(-1, 0))
            .output(SlotSelector.relative(1, 0))
        );

        register("big_furnace_3x", new SlotPatternDefinition()
            .input(MultiSlotResolver::leftRow)
            .fuel(MultiSlotResolver::downTwo)
            .output(MultiSlotResolver::rightRow)
        );

        register("smart_auto", new SlotPatternDefinition()
            .input(SlotSelector.firstMatching(s -> !s.isEmpty(), Direction2D.LEFT, Direction2D.RIGHT, Direction2D.DOWN))
            .fuel(SlotSelector.firstMatching(s -> hasFuelValue(s), Direction2D.DOWN))
            .output(SlotSelector.firstMatching(s -> s.isEmpty() || s.isStackable(), Direction2D.RIGHT))
        );
    }

    public static void register(String id, SlotPatternDefinition pattern) {
        PATTERNS.put(id, pattern);
    }

    public static SlotPatternDefinition get(String id) {
        return PATTERNS.getOrDefault(id, PATTERNS.get("furnace_standard"));
    }

    public static Collection<String> getAllIds() {
        return PATTERNS.keySet();
    }

    // 从JSON文件加载外部模式（支持资源包）
    public static void loadFromJson(ResourceLocation location) {
        // 解析JSON并注册...
    }
}
```

#### 5.2 JSON格式定义（资源包可扩展）

```json
// data/livingitem/slot_patterns/custom_pattern.json
{
  "id": "my_mod:cross_pattern",
  "description": "十字形槽位布局",
  "input": {
    "type": "multi_direction",
    "offsets": [[-1,0], [1,0]]
  },
  "fuel": {
    "type": "single_direction",
    "direction": "up"
  },
  "output": {
    "type": "multi_direction",
    "offsets": [[0,-1], [0,1]]
  }
}
```

#### 5.3 使用方式

```java
// 在LivingFunctionConfig中使用
private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
    .withPattern("big_furnace_3x")  // 直接引用预定义模式
    .addComponent(...);

// 或者运行时切换
SlotConfigManager.saveConfig(stack, "living_furnace", new SlotConfiguration()
    .mode("pattern_based")
    .patternId("hopper_horizontal")
);
```

**优点**：
- ✅ 模组包/资源包可扩展（无需改代码）
- ✅ 配置与代码解耦
- ✅ 易于分享和分发配置
- ✅ 内置大量常用模式，开箱即用

**缺点**：
- ❌ 需要额外的注册表和JSON解析逻辑
- ❌ 复杂的自定义模式仍需写代码

**适用场景**：模组包整合、社区驱动的配置生态、标准化需求

---

## 方案6：可视化拖拽编辑器（终极方案）

### 核心思想
在GUI中以图形化方式展示容器网格，玩家可以直接拖拽设置输入/输出/燃料区域。

#### 6.1 GUI效果示意

```
┌─────────────────────────────┐
│  槽位配置编辑器              │
│                             │
│  ┌───┬───┬───┬───┬───┬───┐ │
│  │   │   │ I │ F │   │   │ │  I = Input (蓝色)
│  ├───┼───┼───┼───┼───┼───┤ │  O = Output (绿色)
│  │   │   │███│   │   │   │ │  F = Fuel (橙色)
│  ├───┼───┼───┼───┼───┼───┤ │  ███ = 活物品自身
│  │   │   │ O │   │   │   │ │
│  └───┴───┴───┴───┴───┴───┘ │
│                             │
│  [保存] [重置] [导入] [导出] │
└─────────────────────────────┘
```

#### 6.2 核心实现思路

```java
// client/gui/VisualSlotConfigScreen.java
public class VisualSlotConfigScreen extends Screen {

    private final ItemStack targetItem;
    private GridPanel gridPanel;  // 自定义网格面板
    private ToolBar toolBar;      // 工具栏（画笔/橡皮擦/填充）
    private ColorLegend legend;   // 图例说明

    private enum PaintMode {
        INPUT(Color.BLUE),
        OUTPUT(Color.GREEN),
        FUEL(Color.ORANGE),
        ERASE(Color.BLACK),
        NONE(Color.GRAY);

        public final Color color;
        PaintMode(Color color) { this.color = color; }
    }

    private PaintMode currentMode = PaintMode.INPUT;
    private Map<Integer, PaintMode> slotPaintMap = new HashMap<>();  // slotIndex → paintMode

    @Override
    protected void init() {
        super.init();

        int gridWidth = 9 * 18 + 10;  // 9列格子 + 间距
        int gridHeight = 3 * 18 + 10;  // 3行格子 + 间距
        int startX = (this.width - gridWidth) / 2;
        int startY = (this.height - gridHeight) / 2;

        // 创建网格面板
        this.gridPanel = new GridPanel(startX, startY, 9, 3, 18) {
            @Override
            protected void onSlotClicked(int slotIndex, int button) {
                if (button == 0) {  // 左键绘制
                    slotPaintMap.put(slotIndex, currentMode);
                } else if (button == 1) {  // 右键擦除
                    slotPaintMap.remove(slotIndex);
                }
                markSlotsDirty();
            }

            @Override
            protected void renderSlotDecoration(GuiGraphics graphics, int x, int y, int slotIndex) {
                PaintMode mode = slotPaintMap.getOrDefault(slotIndex, PaintMode.NONE);
                if (mode != PaintMode.NONE) {
                    // 绘制半透明颜色覆盖层
                    int color = (mode.color.getAlpha() << 24) |
                                (mode.color.getRed() << 16) |
                                (mode.color.getGreen() << 8) |
                                mode.color.getBlue();
                    graphics.fill(x, y, x+16, y+16, color);

                    // 绘制字母标识
                    String label = mode.name().substring(0, 1);  // I/O/F
                    graphics.drawCenteredString(font, label, x+8, y+5, 0xFFFFFFFF);
                }
            }
        };

        this.addRenderableWidget(gridPanel);

        // 工具栏
        int toolY = startY + gridHeight + 20;
        for (PaintMode mode : PaintMode.values()) {
            if (mode == PaintMode.NONE) continue;
            this.addRenderableWidget(new ToggleButton(
                startX + (mode.ordinal() * 50), toolY, 40, 20,
                Component.translatable("tool.livingitem." + mode.name().toLowerCase()),
                () -> currentMode == mode,
                () -> currentMode = mode
            ));
        }
    }

    private void exportConfig() {
        // 将slotPaintMap转换为SlotConfiguration并保存
        SlotConfiguration config = convertPaintMapToConfig();
        SlotConfigManager.saveConfig(targetItem, functionId, config);

        // 可选：复制到剪贴板或保存为文件
        String json = Gson.toJson(config);
        minecraft.keyboardHandler.setClipboard(json);
        player.displayClientMessage(
            Component.literal("配置已复制到剪贴板！"), false
        );
    }

    private SlotConfiguration convertPaintMapToConfig() {
        List<int[]> inputSlots = new ArrayList<>();
        List<int[]> outputSlots = new ArrayList<>();
        List<int[]> fuelSlots = new ArrayList<>();

        // 找到活物品自身位置（中心点）
        int hostSlot = findHostSlot();

        for (Map.Entry<Integer, PaintMode> entry : slotPaintMap.entrySet()) {
            int slot = entry.getKey();
            int dx = (slot % 9) - (hostSlot % 9);
            int dy = (slot / 9) - (hostSlot / 9);
            int[] offset = new int[]{dx, dy};

            switch (entry.getValue()) {
                case INPUT -> inputSlots.add(offset);
                case OUTPUT -> outputSlots.add(offset);
                case FUEL -> fuelSlots.add(offset);
            }
        }

        return new SlotConfiguration()
            .withInput(new SlotDef("multi_direction", null,
                inputSlots.toArray(new int[0][]), null, null, null))
            .withOutput(new SlotDef("multi_direction", null,
                outputSlots.toArray(new int[0][]), null, null, null))
            .withFuel(fuelSlots.isEmpty() ?
                new SlotDef("none", null, null, null, null, null) :
                new SlotDef("multi_direction", null,
                    fuelSlots.toArray(new int[0][]), null, null, null));
    }
}
```

**优点**：
- ✅ 极其直观，零学习成本
- ✅ 支持任意复杂的槽位布局
- ✅ 可视化反馈，所见即所得
- ✅ 支持导入/导出/分享配置

**缺点**：
- ❌ 开发工作量最大（需要自定义UI组件）
- ❌ 移动端适配困难
- ❌ 需要考虑触摸屏操作

**适用场景**：面向普通玩家的产品级功能、需要极大灵活性的高级用户

---

## 三、方案对比总结

| 维度 | 方案1: 增强枚举 | 方案2: 多槽位 | 方案3: 选择器接口 | 方案4: NBT配置 | 方案5: 模式系统 | 方案6: 可视化编辑器 |
|------|----------------|--------------|------------------|---------------|----------------|-------------------|
| **开发难度** | ⭐ 极简 | ⭐⭐ 简单 | ⭐⭐⭐ 中等 | ⭐⭐⭐⭐ 复杂 | ⭐⭐⭐ 中等 | ⭐⭐⭐⭐⭐ 极复杂 |
| **灵活性** | ★★☆ 低 | ★★★ 中 | ★★★★ 高 | ★★★★★ 极高 | ★★★★ 高 | ★★★★★ 极高 |
| **易用性** | ●●○ 开发者友好 | ●●○ 开发者友好 | ●○○ 需要编程知识 | ●●● 玩家友好 | ●●● 玩家友好 | ●●●● 极致体验 |
| **运行时可改** | ❌ 否 | ❌ 否 | ❌ 否 | ✅ 是 | ✅ 是 | ✅ 是 |
| **向后兼容** | ✅ 完全兼容 | ✅ 兼容 | ✅ 兼容 | ✅ 兼容 | ✅ 兼容 | ✅ 兼容 |
| **适用阶段** | MVP/原型 | 快速迭代 | 产品化初期 | 成熟期 | 社区生态 | 终极目标 |

---

## 四、推荐实施路线图

### Phase 1: 当前（MVP验证）
**采用方案1（增强枚举）**
- 实现 `SlotPattern` 枚举（8方向 + SELF + NONE）
- 重构 `SlotResolver` 支持新枚举
- 用中间层重构活熔炉，验证可行性

**预计时间**：1-2天

### Phase 2: 短期（1-2周）
**升级到方案2（多槽位）+ 方案3（选择器接口）**
- 实现 `MultiSlotResolver`
- 实现 `SlotSelector` 函数式接口
- 实现 `LivingBigFurnaceFunction` 和 `LivingSmartFurnaceFunction` 作为示例
- 为 `LivingFunctionConfig` 添加 `.withInputSelector()` 等 API

**预计时间**：3-5天

### Phase 3: 中期（1个月）
**引入方案4（NBT持久化）**
- 实现 `SlotConfiguration` 数据类
- 实现 `SlotConfigManager`（序列化/反序列化）
- 实现基础的 `SlotConfigScreen`（GUI编辑器）
- 实现 `SlotConfigUpdatePacket`（网络同步）
- 添加 Shift+右键 打开配置界面的交互

**预计时间**：1-2周

### Phase 4: 长期（未来）
**完善方案5（模式库）+ 方案6（可视化）**
- 建立 `SlotPatternRegistry` 注册表
- 编写10-20种常用内置模式
- 支持JSON/资源包加载外部模式
- 开发可视化拖拽编辑器
- 支持配置导入/导出/分享

**预计时间**：持续迭代

---

## 五、混合使用策略（实际推荐）

**最佳实践：方案1-3 作为底层能力，方案4-6 作为上层应用**

```
┌─────────────────────────────────────────────┐
│  应用层（用户可见）                          │
│  ┌──────────┐ ┌──────────┐ ┌──────────────┐ │
│  │ NBT配置   │ │ 模式选择  │ │ 可视化编辑器  │ │
│  │ (方案4)   │ │ (方案5)   │ │ (方案6)       │ │
│  └────┬─────┘ └────┬─────┘ └──────┬───────┘ │
│       ↓            ↓               ↓         │
│  ┌─────────────────────────────────────┐    │
│  │  中间层（统一抽象）                   │    │
│  │  SlotSelector 接口 (方案3)           │    │
│  └─────────────────────────────────────┘    │
│       ↓                                    │
│  ┌─────────────────────────────────────┐    │
│  │  基础设施层                          │    │
│  │  SlotPattern枚举 (方案1)             │    │
│  │  MultiSlotResolver (方案2)           │    │
│  └─────────────────────────────────────┘    │
└─────────────────────────────────────────────┘
```

**具体做法**：
1. 底层实现方案1和方案2（基础设施）
2. 中间层提供方案3的 `SlotSelector` 接口（统一抽象）
3. 上层的方案4/5/6 最终都生成 `SlotSelector` 实例
4. `FunctionExecutor` 只依赖 `SlotSelector` 接口，不关心配置来源

这样既保证了架构清晰，又实现了最大灵活性！

---

## 六、立即开始的代码示例

如果你想现在就动手，这是**最小可行的自定义槽位实现**（结合方案1+3）：

```java
// Step 1: 创建增强版方向枚举
// living/core/SlotPattern.java
public enum SlotPattern {
    LEFT(-1, 0), RIGHT(1, 0), UP(0, -1), DOWN(0, 1),
    UP_LEFT(-1, -1), UP_RIGHT(1, -1), DOWN_LEFT(-1, 1), DOWN_RIGHT(1, 1),
    SELF(0, 0), NONE(9999, 9999);

    public final int dx, dy;
    SlotPattern(int dx, int dy) { this.dx = dx; this.dy = dy; }
}

// Step 2: 创建函数式接口
// living/core/SlotSelector.java
@FunctionalInterface
public interface SlotSelector {
    int[] select(ContainerContext ctx, int baseSlot);

    static SlotSelector fromPattern(SlotPattern pattern) {
        if (pattern == SlotPattern.NONE) return (c, s) -> new int[0];
        if (pattern == SlotPattern.SELF) return (c, s) -> new int[]{s};

        return (ctx, slot) -> {
            int result = SlotResolver.resolve(slot, pattern);
            return result >= 0 ? new int[]{result} : new int[0];
        };
    }
}

// Step 3: 修改LivingFunctionConfig支持自定义选择器
// living/core/LivingFunctionConfig.java
public class LivingFunctionConfig {
    private SlotSelector inputSelector;
    private SlotSelector fuelSelector;
    private SlotSelector outputSelector;

    public LivingFunctionConfig withInputPattern(SlotPattern pattern) {
        this.inputSelector = SlotSelector.fromPattern(pattern);
        return this;
    }

    public LivingFunctionConfig withInputSelector(SlotSelector selector) {
        this.inputSelector = selector;
        return this;
    }

    // ... fuel/output 同理 ...
}

// Step 4: 使用示例 - 智能漏斗
// living/LivingHopperFunction.java
public class LivingHopperFunction implements LivingItemFunction {
    private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
        .withInputSelector(SlotSelector.firstMatching(
            stack -> !stack.isEmpty(),
            SlotPattern.UP, SlotPattern.LEFT, SlotPattern.RIGHT  // 优先级
        ))
        .withOutputSelector(SlotSelector.firstMatching(
            stack -> stack.isEmpty() || stack.getCount() < stack.getMaxStackSize(),
            SlotPattern.DOWN, SlotPattern.RIGHT
        ))
        .addComponent(StateMachineComponent.class, ...)
        .addComponent(ItemMoveComponent.class, ...);

    // ...
}
```

**总共只需4个小文件，约150行代码**，就能获得强大的自定义槽位能力！

---

## 七、下一步行动

你现在可以选择：

### 🚀 选项A：我帮你生成完整的基础代码
包括：
- `SlotPattern` 增强枚举
- `SlotSelector` 函数式接口
- 更新版的 `LivingFunctionConfig`（支持自定义选择器）
- `MultiSlotResolver` 多槽位工具
- 2-3个使用示例（标准熔炉、智能漏斗、大熔炉）

### 📘 选项B：先深入讨论某个方案
比如：
- 你更倾向于哪种方案？为什么？
- 你的目标用户是谁？（普通玩家/技术玩家/开发者）
- 是否需要支持模组包/资源包扩展？

### 💡 选项C：直接开始实现某个具体场景
比如："我想先做一个可以Shift+右键切换方向的活漏斗"

**我的建议是选项A**，因为：
✅ 代码已经设计好，质量有保证  
✅ 可以立即看到效果  
✅ 为后续的高级方案打下坚实基础  

**你希望我怎么推进？** 我随时准备开始编码！🛠️