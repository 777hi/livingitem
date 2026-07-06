# 中间层数据持久化与客户端渲染扩展方案

## 一、现状分析

### 你已有的基础设施（非常完善！）

```
┌─────────────────────────────────────────────────────────────┐
│                    现有数据流                                │
│                                                             │
│  服务端tick修改 → LivingFunctionData(DataComponent)          │
│       ↓                                                       │
│  StreamCodec自动序列化 → 网络同步到客户端                      │
│       ↓                                                       │
│  客户端ItemTooltipEvent → LivingFunctionData.addToTooltip()   │
│       ↓                                                       │
│  渲染tooltip文字（燃烧时间、烹饪进度等）                       │
└─────────────────────────────────────────────────────────────┘
```

**优点**：
- ✅ 类型安全的DataComponent系统
- ✅ 自动网络同步（StreamCodec）
- ✅ TooltipProvider集成
- ✅ 不可变设计（线程安全）

**需要扩展的点**：
- ❌ 目前只支持tooltip文字渲染
- ❌ 不支持自定义图形/动画渲染
- ❌ 组件状态如何与LivingFunctionData无缝集成？

## 二、设计方案：三层分离架构

### 核心思想

> **数据层（NBT） ↔ 同步层（网络） ↔ 表现层（渲染）**
>
> 三者解耦，每层可独立演进

```
┌─────────────────────────────────────────────────────────────┐
│  表现层（Rendering Layer）- 客户端专属                        │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────────┐  │
│  │ Tooltip渲染  │  │ 物品图标动画  │  │ 自定义GUI覆盖层     │  │
│  │ (已有)      │  │ (新增)       │  │ (未来扩展)         │  │
│  └─────────────┘  └──────────────┘  └────────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│  同步层（Sync Layer）- 自动化                                │
│  LivingFunctionData.STREAM_CODEC (已有，无需改动)              │
├─────────────────────────────────────────────────────────────┤
│  数据层（Data Layer）- 中间层集成                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ FunctionExecutor 统一管理组件状态的读写                 │    │
│  │ ↓                                                     │    │
│  │ LivingFunctionData (以functionId.componentId为key)     │    │
│  │ ↓                                                     │    │
│  │ ItemStack.DataComponent (自动持久化到NBT)               │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

## 三、详细实现方案

### 3.1 数据层：组件状态与LivingFunctionData的无缝集成

#### 问题：组件状态如何存储到现有的LivingFunctionData中？

**解决方案：使用嵌套的CompoundTag结构**

```java
// NBT 数据结构示例（活熔炉）
{
  "living_furnace": {                          ← functionId (现有)
    "progress": {                              ← componentId (新增)
      "progress": 150,                        ← ProgressComponent的状态
      "total": 200
    },
    "fuel": {                                 ← componentId (新增)
      "burn_time": 180                        ← FuelConsumeComponent的状态
    },
    "transform": {}                           ← ItemTransformComponent无状态
  }
}
```

#### 修改后的FunctionExecutor（状态管理核心）

```java
// living/core/FunctionExecutor.java (扩展版)
public final class FunctionExecutor {

    // ... 其他代码保持不变 ...

    /**
     * 从物品的LivingFunctionData中加载所有组件状态。
     *
     * 数据路径：stack -> LIVING_FUNCTION_DATA -> functionId -> componentId -> ComponentState
     */
    private Map<String, ComponentState> loadOrCreateStates(ItemStack stack, LivingFunctionConfig config) {
        Map<String, ComponentState> states = new HashMap<>();

        CompoundTag functionTag = LivingItemManager.getFunctionData(stack, config.getFunctionId());

        for (var entry : config.getComponents()) {
            ILivingComponent component = getComponent(entry.componentClass());
            String compId = component.getComponentId();

            if (functionTag.contains(compId)) {
                states.put(compId, ComponentState.fromNBT(functionTag.getCompound(compId)));
            } else {
                states.put(compId, component.createDefaultState());
            }
        }

        return states;
    }

    /**
     * 将所有组件状态写回物品的LivingFunctionData。
     *
     * 写回路径：ComponentState -> CompoundTag(componentId) -> CompoundTag(functionId) -> LivingFunctionData
     */
    private void saveStatesToStack(ItemStack stack, LivingFunctionConfig config,
                                    Map<String, ComponentState> states) {
        CompoundTag functionTag = new CompoundTag();

        for (var entry : states.entrySet()) {
            functionTag.put(entry.getKey(), entry.getValue().toNBT());
        }

        LivingItemManager.setFunctionData(stack, config.getFunctionId(), functionTag);
    }
}
```

**关键点**：
- **完全兼容现有API**：仍然使用 `LivingItemManager.get/setFunctionData()`
- **自动嵌套**：组件状态自动按 `componentId` 分组存储
- **零侵入**：不需要修改 `LivingFunctionData` 的实现

### 3.2 扩展ComponentState支持复杂数据类型

为了支持动画等复杂场景，ComponentState需要支持更多数据类型：

```java
// living/core/ComponentState.java (增强版)
public class ComponentState {
    private final CompoundTag data;

    public ComponentState() {
        this.data = new CompoundTag();
    }

    public ComponentState(CompoundTag data) {
        this.data = data != null ? data : new CompoundTag();
    }

    // ===== 基本类型 =====
    public int getInt(String key, int defaultValue) {
        return data.contains(key) ? data.getInt(key) : defaultValue;
    }

    public void setInt(String key, int value) { data.putInt(key, value); }

    public float getFloat(String key, float defaultValue) {
        return data.contains(key) ? data.getFloat(key) : defaultValue;
    }

    public void setFloat(String key, float value) { data.putFloat(key, value); }

    public boolean getBoolean(String key, boolean defaultValue) {
        return data.contains(key) ? data.getBoolean(key) : defaultValue;
    }

    public void setBoolean(String key, boolean value) { data.putBoolean(key, value); }

    public String getString(String key, String defaultValue) {
        return data.contains(key) ? data.getString(key) : defaultValue;
    }

    public void setString(String key, String value) { data.putString(key, value); }

    // ===== 复合类型（用于动画等场景）=====
    public ListTag getList(String key, int type) {
        return data.getList(key, type);
    }

    public void putList(String key, ListTag list) { data.put(key, list); }

    /**
     * 存储三维向量（用于位置、颜色等动画参数）。
     * 格式: [x, y, z]
     */
    public void setVec3(String key, float x, float y, float z) {
        ListTag list = new ListTag();
        list.add(FloatTag.valueOf(x));
        list.add(FloatTag.valueOf(y));
        list.add(FloatTag.valueOf(z));
        data.put(key, list);
    }

    public float[] getVec3(String key, float[] defaultValue) {
        if (!data.contains(key)) return defaultValue;
        ListTag list = data.getList(key, Tag.TAG_FLOAT);
        if (list.size() != 3) return defaultValue;
        return new float[] {
            list.getFloat(0),
            list.getFloat(1),
            list.getFloat(2)
        };
    }

    // ===== 序列化 =====
    public CompoundTag toNBT() { return data.copy(); }

    public static ComponentState fromNBT(CompoundTag tag) {
        return new ComponentState(tag);
    }
}
```

**使用示例（假设未来有动画组件）**：

```java
// 存储火焰动画状态
state.setFloat("flame_intensity", 0.8f);
state.setVec3("particle_offset", 1.5f, -0.3f, 0.0f);
state.setInt("animation_frame", 12);

// 读取时
float intensity = state.getFloat("flame_intensity", 0);
float[] offset = state.getVec3("particle_offset", new float[3]);
int frame = state.getInt("animation_frame", 0);
```

### 3.3 同步层：利用现有机制，零额外工作

**好消息：你不需要写任何网络同步代码！**

现有的 `LivingFunctionData.STREAM_CODEC` 已经能够：
- ✅ 自动将整个 `LivingFunctionData`（包含所有组件状态）同步到客户端
- ✅ 在服务端调用 `context.syncSlotToClients()` 时触发同步
- ✅ 客户端通过 `stack.get(LIVING_FUNCTION_DATA.value())` 获取最新数据

**唯一需要注意的**：确保在 `FunctionExecutor.tick()` 最后调用同步：

```java
public void tick(ContainerContext context, int slot, ItemStack stack,
                 LivingFunctionConfig config, Level level) {
    // ... 执行组件逻辑 ...

    // 8. 状态写回NBT（这一步会更新DataComponent）
    saveStatesToStack(stack, config, states);

    // 9. 触发网络同步（这一步会把最新的DataComponent推送到客户端）
    context.syncSlotToClients(slot, stack);
}
```

**同步时序图**：

```
服务端Tick:
  FunctionExecutor.tick()
    → 组件修改ComponentState
    → saveStatesToStack() 更新 LivingFunctionData DataComponent
    → context.syncSlotToClients() 发送 ClientboundContainerSetSlotPacket
      ↓
客户端接收:
  ItemStack 更新（包含最新的 LivingFunctionData）
    → ItemTooltipEvent 触发 → tooltip刷新
    → 渲染事件触发 → 动画帧更新
```

### 3.4 表现层：客户端渲染扩展架构

#### 方案A：基于事件驱动的渲染系统（推荐）

创建一个新的渲染接口，让组件可以声明自己的渲染逻辑：

```java
// living/core/components/ILivingRenderer.java (新增接口)
public interface ILivingRenderer {

    /**
     * 渲染器ID（需全局唯一）。
     */
    String getRendererId();

    /**
     * 在物品图标上叠加渲染自定义效果（如火焰、进度条等）。
     *
     * @param stack 物品实例（包含最新数据）
     * @param guiGraphics 图形上下文
     * @param x 物品图标左上角X坐标
     * @param y 物品图标左上角Y坐标
     * @param partialTick 部分tick时间（用于平滑动画）
     */
    void renderOverlay(ItemStack stack, GuiGraphics guiGraphics,
                       int x, int y, float partialTick);

    /**
     * 是否应该启用此渲染器（可根据条件动态开关）。
     */
    default boolean shouldRender(ItemStack stack) {
        return true;
    }
}
```

#### 组件同时实现ILivingComponent和ILivingRenderer

```java
// living/core/components/FuelConsumeComponent.java (扩展版)
public class FuelConsumeComponent implements ILivingComponent, ILivingRenderer {

    public static final String ID = "fuel";

    @Override
    public String getComponentId() { return ID; }
    @Override
    public String getRendererId() { return "fuel_flame"; }

    // ... tick() 和 createDefaultState() 保持不变 ...

    @Override
    public boolean shouldRender(ItemStack stack) {
        ComponentState state = loadStateFromStack(stack);
        return state.getInt("burn_time", 0) > 0;
    }

    @Override
    public void renderOverlay(ItemStack stack, GuiGraphics guiGraphics,
                               int x, int y, float partialTick) {
        ComponentState state = loadStateFromStack(stack);
        int burnTime = state.getInt("burn_time", 0);

        if (burnTime <= 0) return;

        // 计算火焰强度（基于剩余燃烧时间）
        float intensity = Math.min(1.0f, burnTime / 200.0f);

        // 在物品图标上方绘制火焰粒子效果
        renderFlameEffect(guiGraphics, x, y, intensity, partialTick);
    }

    private void renderFlameEffect(GuiGraphics guiGraphics, int x, int y,
                                   float intensity, float partialTick) {
        // 使用Minecraft的粒子纹理或自定义贴图
        ResourceLocation flameTexture = ResourceLocation.withDefaultNamespace("particle/flame");

        // 动态计算偏移（基于partialTick实现平滑动画）
        float offsetY = (float) Math.sin((System.currentTimeMillis() % 2000) / 200.0 * Math.PI) * 2;

        guiGraphics.blit(flameTexture, x + 2, y - 4 + (int)offsetY, 0, 0,
                        12, 8, 16, 16);

        // 可以根据intensity调整透明度或大小
    }

    private ComponentState loadStateFromStack(ItemStack stack) {
        LivingFunctionData functionData = stack.get(LivingItemManager.LIVING_FUNCTION_DATA.value());
        if (functionData == null) return new ComponentState();

        CompoundTag furnaceData = functionData.getFunctionData("living_furnace");
        if (!furnaceData.contains(ID)) return new ComponentState();

        return ComponentState.fromNBT(furnaceData.getCompound(ID));
    }
}
```

#### 注册渲染器的全局管理器

```java
// client/LivingRenderManager.java (新增)
@EventBusSubscriber(value = Dist.CLIENT, modid = LivingItem.MOD_ID)
public final class LivingRenderManager {

    private static final Map<String, ILivingRenderer> RENDERERS = new HashMap<>();

    private LivingRenderManager() {}

    public static void registerRenderer(ILivingRenderer renderer) {
        RENDERERS.put(renderer.getRendererId(), renderer);
    }

    public static Collection<ILivingRenderer> getAllRenderers() {
        return RENDERERS.values();
    }

    /**
     * 在容器界面渲染时调用，为每个活物品槽位叠加渲染效果。
     *
     * 通过Mixin注入到AbstractContainerScreen.render()方法中。
     */
    @SubscribeEvent
    public static void onRenderScreen(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;

        GuiGraphics guiGraphics = event.getGuiGraphics();
        Minecraft mc = Minecraft.getInstance();

        // 遍历容器的所有槽位
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem;
            if (!LivingItemManager.isLivingItem(stack)) continue;

            int x = screen.leftPos + slot.x;
            int y = screen.topPos + slot.y;

            // 为每个启用的渲染器调用renderOverlay
            for (ILivingRenderer renderer : RENDERERS.values()) {
                if (renderer.shouldRender(stack)) {
                    renderer.renderOverlay(stack, guiGraphics, x, y, event.getPartialTick());
                }
            }
        }
    }
}
```

#### 方案B：基于Mixin的轻量级渲染（更简单）

如果不想引入复杂的渲染系统，可以用Mixin直接在特定位置渲染：

```java
// client/mixin/SlotRenderMixin.java (新增)
@Mixin(Slot.class)
public class SlotRenderMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void living_item$renderLivingEffects(GuiGraphics guiGraphics,
                                                  int pIndex, int pTop,
                                                  int pLeft, CallbackInfo ci) {
        Slot self = (Slot)(Object)this;
        ItemStack stack = self.getItem();

        if (!LivingItemManager.isLivingItem(stack)) return;

        // 直接在这里根据NBT数据渲染简单效果
        LivingFunctionData functionData = stack.get(LivingItemManager.LIVING_FUNCTION_DATA.value());
        if (functionData == null || functionData.isEmpty()) return;

        CompoundTag furnaceData = functionData.getFunctionData("living_furnace");
        if (furnaceData.isEmpty()) return;

        // 示例：渲染一个简单的进度条
        int cookTime = furnaceData.getCompound("progress").getInt("progress");
        int cookTotal = furnaceData.getCompound("progress").getInt("total");

        if (cookTotal > 0) {
            float progress = (float)cookTime / cookTotal;
            renderProgressBar(guiGraphics, pLeft + self.x, pTop + self.y, progress);
        }
    }

    private void renderProgressBar(GuiGraphics guiGraphics, int x, int y, float progress) {
        // 背景条
        guiGraphics.fill(x, y + 10, x + 16, y + 13, 0xFF000000);
        // 进度条（绿色）
        guiGraphics.fill(x, y + 10, x + (int)(16 * progress), y + 13, 0xFF00FF00);
    }
}
```

**对比两种方案**：

| 维度 | 方案A（事件驱动） | 方案B（Mixin直接渲染） |
|------|------------------|---------------------|
| 复杂度 | 高（需新建多个类） | 低（只需一个Mixin文件） |
| 灵活性 | 极高（组件自定义渲染） | 低（硬编码渲染逻辑） |
| 可维护性 | 好（解耦清晰） | 差（逻辑集中难维护） |
| 适用场景 | 长期维护的大中型模组 | 快速原型或简单效果 |

**建议**：初期用**方案B快速验证**，后续迁移到**方案A**。

## 四、完整数据流示例（以活熔炉为例）

### 4.1 服务端Tick流程

```
ContainerLivingItemHandler.tickAllLivingItems()
  ↓
FunctionExecutor.resetOccupiedSlots()  // 清空占用标记
  ↓
遍历每个活熔炉:
  FunctionExecutor.tick(context, slot, stack, config, level)
    ↓
  1. SlotResolver解析槽位: input=左, fuel=下, output=右
  2. 冲突检测: inputSlot未被占用? → 继续
  3. 加载状态:
      functionTag = LivingItemManager.getFunctionData(stack, "living_furnace")
      progressState = functionTag.getCompound("progress")  → ComponentState{progress:150, total:200}
      fuelState = functionTag.getCompound("fuel")           → ComponentState{burn_time:180}
  4. 执行FuelConsumeComponent.tick():
      burnTime > 0? → burnTime-- → fuelState.set("burn_time", 179)
  5. 执行ProgressComponent.tick():
      progress += stack.getCount() → progressState.set("progress", 153)
  6. 检查完成: 153 >= 200? → 否
  7. 标记inputSlot已占用
  8. 写回NBT:
      saveStatesToStack(stack, config, states)
      → LivingFunctionData{
          "living_furnace":{
            "progress":{progress:153, total:200},
            "fuel":{burn_time:179}
          }
        }
  9. 同步到客户端:
      context.syncSlotToClients(slot, stack)
      → 发送ClientboundContainerSetSlotPacket
```

### 4.2 客户端接收与渲染流程

```
客户端收到ClientboundContainerSetSlotPacket
  ↓
ItemStack更新（包含最新的LivingFunctionData）
  ↓
[分支1] Tooltip渲染:
  ItemTooltipEvent触发
    → LivingItemTooltip.onItemTooltip()
      → LivingFunctionData.addToTooltip()
        → 遍历各功能:
          LivingFurnaceFunction.addToTooltip(functionData, ...)
            → 从functionData读取:
              fuelState = functionTag.getCompound("fuel")
              progressState = functionTag.getCompound("progress")
            → 显示:
              "🔥 燃烧时间: 9.0秒"
              "⏱️ 熔炼进度: 76% (7.7秒 / 10.0秒)"

[分支2] 图标动画渲染（方案B）:
  Slot.render()被调用
    → SlotRenderMixin.living_item$renderLivingEffects()
      → 读取functionData中的burn_time
      → burn_time > 0? → 在物品图标上叠加火焰粒子
      → 读取progress/total → 渲染绿色进度条
```

## 五、实际代码示例：带渲染支持的完整组件

### FuelConsumeComponent（完整版：逻辑+Tooltip+渲染）

```java
// living/core/components/FuelConsumeComponent.java
public class FuelConsumeComponent implements ILivingComponent {

    public static final String ID = "fuel";
    private static final String KEY_BURN_TIME = "burn_time";

    @Override
    public String getComponentId() { return ID; }

    @Override
    public void tick(ComponentContext ctx, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {
        RecipeType<?> recipeType = config.get("recipe_type", RecipeType.class, RecipeType.SMELTING);
        int burnTime = state.getInt(KEY_BURN_TIME, 0);

        if (burnTime > 0) {
            burnTime--;
            state.setInt(KEY_BURN_TIME, burnTime);
            return;
        }

        if (ctx.hasValidFuel()) {
            ItemStack fuelStack = ctx.containerCtx().getItem(ctx.fuelSlot());
            int fuelValue = getFuelValue(fuelStack, recipeType);

            if (fuelValue > 0) {
                fuelStack.shrink(1);
                ctx.containerCtx().setItem(ctx.fuelSlot(), fuelStack.copy());
                burnTime = fuelValue;
                state.setInt(KEY_BURN_TIME, burnTime);
            }
        }
    }

    @Override
    public ComponentState createDefaultState() {
        return new ComponentState();
    }

    @Override
    public void appendTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        int burnTime = state.getInt(KEY_BURN_TIME, 0);
        tooltipAdder.accept(Component.translatable(
            "tooltip.livingitem.fuel_burn",
            String.format("%.1f", burnTime / 20.0)
        ));
    }

    public boolean isBurning(ComponentState state) {
        return state.getInt(KEY_BURN_TIME, 0) > 0;
    }

    private int getFuelValue(ItemStack stack, RecipeType<?> recipeType) {
        return ((IItemExtension) stack.getItem()).getBurnTime(stack, recipeType);
    }
}
```

### ProgressComponent（完整版）

```java
// living/core/components/ProgressComponent.java
public class ProgressComponent implements ILivingComponent {

    public static final String ID = "progress";
    private static final String KEY_PROGRESS = "progress";
    private static final String KEY_TOTAL = "total";

    @Override
    public String getComponentId() { return ID; }

    @Override
    public void tick(ComponentContext ctx, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {
        int total = config.get("total_ticks", Integer.class, 200);
        int multiplier = Math.max(1, hostStack.getCount());

        state.setInt(KEY_TOTAL, total);
        int current = state.getInt(KEY_PROGRESS, 0);
        current += multiplier;
        state.setInt(KEY_PROGRESS, current);
    }

    @Override
    public ComponentState createDefaultState() {
        return new ComponentState();
    }

    @Override
    public void appendTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        int progress = state.getInt(KEY_PROGRESS, 0);
        int total = state.getInt(KEY_TOTAL, 0);
        if (total > 0) {
            int percent = (int) ((progress * 100.0f) / total);
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.progress",
                percent,
                String.format("%.1f", progress / 20.0),
                String.format("%.1f", total / 20.0)
            ));
        } else if (progress > 0) {
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.progress_wait",
                String.format("%.1f", progress / 20.0)
            ));
        }
    }

    public boolean isComplete(ComponentState state, ComponentConfig config) {
        int progress = state.getInt(KEY_PROGRESS, 0);
        int total = state.getInt(KEY_TOTAL, config.get("total_ticks", Integer.class, 200));
        return progress >= total;
    }

    public void reset(ComponentState state) {
        state.setInt(KEY_PROGRESS, 0);
        state.setInt(KEY_TOTAL, 0);
    }
}
```

## 六、客户端渲染快速启动指南（方案B：Mixin方式）

如果你现在就想看到渲染效果，只需要3步：

### 步骤1：创建渲染Mixin

```java
// client/mixin/LivingItemRenderMixin.java
package com.qiqi.li.client.mixin;

import com.qiqi.li.living.LivingFunctionData;
import com.qiqi.li.living.LivingItemManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Slot.class)
public class LivingItemRenderMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void living_item$renderStatus(GuiGraphics guiGraphics,
                                           int index, int top, int left,
                                           float blitOffset, CallbackInfo ci) {
        Slot self = (Slot)(Object)this;
        ItemStack stack = self.getItem();

        if (!LivingItemManager.isLivingItem(stack)) return;

        LivingFunctionData funcData = stack.get(LivingItemManager.LIVING_FUNCTION_DATA.value());
        if (funcData == null || funcData.isEmpty()) return;

        CompoundTag furnaceData = funcData.getFunctionData("living_furnace");
        if (furnaceData.isEmpty()) return;

        int x = left + self.x;
        int y = top + self.y;

        // 渲染燃烧状态指示器
        CompoundTag fuelData = furnaceData.getCompound("fuel");
        int burnTime = fuelData.getInt("burn_time");
        if (burnTime > 0) {
            renderFlameIndicator(guiGraphics, x, y, burnTime);
        }

        // 渲染进度条
        CompoundTag progressData = furnaceData.getCompound("progress");
        int progress = progressData.getInt("progress");
        int total = progressData.getInt("total");
        if (total > 0) {
            renderProgressBar(guiGraphics, x, y, (float)progress / total);
        }
    }

    private void renderFlameIndicator(GuiGraphics guiGraphics, int x, int y, int burnTime) {
        float alpha = Math.min(1.0f, burnTime / 100.0f);
        int color = ((int)(alpha * 255) << 24) | 0xFF5500; // ARGB: 半透明橙色
        guiGraphics.fill(x + 2, y - 2, x + 6, y + 2, color);
    }

    private void renderProgressBar(GuiGraphics guiGraphics, int x, int y, float progress) {
        int width = (int)(14 * progress);
        guiGraphics.fill(x + 1, y + 11, x + 15, y + 13, 0xFF000000); // 背景
        guiGraphics.fill(x + 1, y + 11, x + 1 + width, y + 13, 0xFF00CC00); // 绿色进度
    }
}
```

### 步骤2：注册到Mixin配置

在你的 `mixins.json` 的 `client` 数组中添加：

```json
{
  "client": [
    "...其他mixin...",
    "com.qiqi.li.client.mixin.LivingItemRenderMixin"
  ]
}
```

### 步骤3：运行测试！

启动游戏，打开箱子放入活熔炉和燃料，你应该能看到：
- 🔥 左上角半透明的橙色方块（表示正在燃烧）
- 📊 底部绿色进度条（实时显示熔炼进度）

## 七、进阶动画效果示例库

### 7.1 火焰粒子动画（基于时间）

```java
private long lastFrameTime = 0;
private int currentFrame = 0;

private void renderAnimatedFlame(GuiGraphics guiGraphics, int x, int y, int burnTime) {
    long now = System.currentTimeMillis();
    if (now - lastFrameTime > 100) {  // 每100ms切换一帧
        currentFrame = (currentFrame + 1) % 4;  // 4帧循环动画
        lastFrameTime = now;
    }

    // 假设有4帧火焰贴图: flame_0.png ~ flame_3.png
    ResourceLocation frameTex = ResourceLocation.withDefaultNamespace(
        "particle/flame_" + currentFrame
    );

    float scale = 0.8f + (burnTime / 200.0f) * 0.4f;  // 燃料越多火焰越大
    int size = (int)(12 * scale);

    guiGraphics.blit(frameTex, x + (12-size)/2, y - size, 0, 0, size, size, 16, 16);
}
```

### 7.2 旋转齿轮动画（用于活工作台等）

```java
private float rotationAngle = 0;

private void renderRotatingGear(GuiGraphics guiGraphics, int x, int y, float partialTick) {
    rotationAngle += partialTick * 2.0f;  // 每秒旋转2弧度
    if (rotationAngle > Math.PI * 2) rotationAngle -= Math.PI * 2;

    PoseStack poseStack = guiGraphics.pose();

    poseStack.pushPose();
    poseStack.translate(x + 8, y + 8, 0);
    poseStack.rotateY(rotationAngle);  // 围绕中心旋转
    poseStack.translate(-8, -8, 0);

    guiGraphics.blit(GEAR_TEXTURE, 0, 0, 0, 0, 16, 16, 16, 16);

    poseStack.popPose();
}
```

### 7.3 呼吸灯效果（用于待机状态指示）

```java
private void renderBreathingIndicator(GuiGraphics guiGraphics, int x, int y) {
    float phase = (System.currentTimeMillis() % 3000) / 3000.0f;  // 3秒一个周期
    float alpha = (float)Math.sin(phase * Math.PI * 2) * 0.5f + 0.5f;  // 0~1之间呼吸
    int color = ((int)(alpha * 255) << 24) | 0x00FF00;  // 呼吸绿光

    guiGraphics.fill(x + 6, y + 6, x + 10, y + 10, color);
}
```

## 八、性能优化建议

### 8.1 减少不必要的NBT读取

```java
// ❌ 错误：每帧都从NBT读取（性能杀手）
private void badRender(ItemStack stack) {
    CompoundTag tag = stack.getOrCreateTag();  // 每帧都读！
    int value = tag.getInt("some_value");
}

// ✅ 正确：缓存数据，只在变化时更新
private ItemStack lastRenderedStack = null;
private CompoundTag cachedTag = null;

private void goodRender(ItemStack stack) {
    if (stack != lastRenderedStack) {
        cachedTag = stack.getTag();  // 只在物品变化时读取
        lastRenderedStack = stack;
    }

    if (cachedTag != null) {
        int value = cachedTag.getInt("some_value");
    }
}
```

### 8.2 条件渲染（跳过不可见槽位）

```java
@Inject(method = "render", at = @At("TAIL"))
private void render(GuiGraphics guiGraphics, ...) {
    // 只有可见槽位才渲染
    if (!self.isActive() || !self.isHighlightVisible()) return;

    // ... 渲染逻辑 ...
}
```

### 8.3 使用缓存池（避免GC压力）

```java
// 预分配常用对象
private static final CompoundTag EMPTY_TAG = new CompoundTag();
private static final int[] DEFAULT_VEC3 = new int[3];

// 复用对象而非频繁创建
private final CompoundTag reusableTag = new CompoundTag();
```

## 九、总结：完整的开发生命周期

```
1️⃣ 定义新组件
   ↓ 实现 ILivingComponent 接口
   ↓ 编写 tick() 逻辑
   ↓ 编写 appendTooltip() （可选）
2️⃣ 配置活物品
   ↓ 创建 LivingFunctionConfig
   ↓ 组合所需组件
   ↓ 设置方向和参数
3️⃣ 数据自动管理
   ↓ FunctionExecutor 自动处理状态读写
   ↓ 自动持久化到 LivingFunctionData
   ↓ StreamCodec 自动同步到客户端
4️⃣ 客户端表现
   ↓ Tooltip自动显示（通过appendTooltip）
   ↓ 图标动画（通过Mixin或ILivingRenderer）
   ↓ 未来可扩展GUI覆盖层
```

## 十、下一步行动

你现在可以：

1. **立即开始编码**：我可以帮你生成所有需要的Java文件（包括渲染Mixin）
2. **先做MVP验证**：只实现ProgressComponent + FuelConsumeComponent + 简单进度条渲染
3. **深入讨论细节**：比如动画的具体需求、性能优化策略等

**你希望我怎么推进？** 我建议先做一个**可视化的Demo**（带进度条和火焰效果的活熔炉），这样能直观地看到整个系统的运作效果！