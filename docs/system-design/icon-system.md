# 活物品图标系统设计

> **文档版本**: 2026.09 v6
> **最后更新**: 2026-09-22
> **适用版本**: Minecraft 1.21.1 + NeoForge 21.1.x

## 目录

- [活物品图标系统设计](#活物品图标系统设计)
  - [目录](#目录)
  - [1. 概述](#1-概述)
  - [2. 三层架构](#2-三层架构)
  - [3. 声明式配置](#3-声明式配置)
  - [4. 新增活物品图标](#4-新增活物品图标)
  - [5. 当前支持的活物品图标](#5-当前支持的活物品图标)
    - [5.1 Builder 可用选项](#51-builder-可用选项)
    - [5.2 模型文件策略](#52-模型文件策略)
    - [5.3 DirectionalLivingModel 变换顺序](#53-directionallivingmodel-变换顺序)
  - [渲染上下文覆盖范围（重要约束）](#渲染上下文覆盖范围重要约束)
  - [GUI 图标光照约定（⚠️ 新加活物品图标必读）](#gui-图标光照约定-️-新加活物品图标必读)
  - [槽位叠加层渲染层级（z 层与深度测试窗口）](#槽位叠加层渲染层级z-层与深度测试窗口)
  - [已完成的实验（2026-09-22）](#已完成的实验2026-09-22)
    - [活箱子 / 活末影箱走原版 `builtin/entity` 3D 渲染 → ✅ 定稿：正面视角 3D + 默认标记](#活箱子--活末影箱走原版-builtinentity-3d-渲染--定稿正面视角-3d--默认标记)
    - [活红石粉的「模型层 + 装饰器」染色方案（同期发现 / 验证）](#活红石粉的模型层--装饰器染色方案同期发现--验证)
  - [关键文件](#关键文件)
  - [纹理约定（涂蜡铜灯图标）](#纹理约定涂蜡铜灯图标)

---

## 1. 概述

活物品图标采用三层架构，通过声明式配置（`LivingIconSpec`）驱动，新增活物品图标无需编写任何 Java 类。

**之前的问题**：每加一种活物品图标需要新建 3-4 个 Java 类（ContextAwareXxxModel、LivingXxxModelWrapper、LivingXxxItemOverrides），代码高度重复。

**解决方案**：
- `LivingIconSpec` — 声明式配置（建造者模式），描述变体列表和判断谓词
- 三个通用组件替代所有物品特定的类：`GenericLivingModelWrapper`、`GenericContextAwareModel`、`GenericLivingItemOverrides`
- `LivingIconRegistry` — 注册中心，统一处理模型注册、注入和叠加层

---

## 2. 三层架构

```
┌─────────────────────────────────────────────────────────────┐
│  Layer 3: IItemDecorator（可选）                             │  ← 叠加层（快捷栏 + 容器 GUI）
│  例: LivingHopperDecorator（方向箭头）                        │
│      LivingFarmlandSeedDecorator（活耕地种子图标）            │
├─────────────────────────────────────────────────────────────┤
│  Layer 2: GenericContextAwareModel                           │  ← 上下文切换（GUI vs 手持）
│  ┌──────────────────┬──────────────────────┐                │
│  │ GUI: 活物品图标    │ 手持/地面: 原版图标   │                │
│  └──────────────────┴──────────────────────┘                │
├─────────────────────────────────────────────────────────────┤
│  Layer 1: GenericLivingModelWrapper                          │  ← 模型注入（区分活/原版）
│  └→ GenericLivingItemOverrides.resolve()                     │
│     ├─ 不是活物品 → 返回原版模型                               │
│     └─ 是活物品 → 遍历 Variant.predicate 匹配变体             │
└─────────────────────────────────────────────────────────────┘
```

**核心原理：**

1. `ModelEvent.ModifyBakingResult` 在模型烘焙后注入 `GenericLivingModelWrapper`，替换原版物品模型
2. `GenericLivingItemOverrides.resolve()` 在渲染时根据 `Variant.predicate` 匹配当前变体
3. `GenericContextAwareModel.applyTransform()` 根据 `ItemDisplayContext` 切换：GUI 显示自定义图标，手持显示原版图标
4. `VariantModelStore` 桥接烘焙阶段和渲染阶段，存储变体模型的 `BakedModel` 引用

---

## 3. 声明式配置

```java
// 活熔炉：两种状态
register(LivingIconSpec.builder(Items.FURNACE)
    .addVariant("idle", "item/furnace_idle", stack -> !isBurning(stack))
    .addVariant("active", "item/furnace_active", stack -> isBurning(stack))
    .build());

// 活TNT：闪烁动画（引信倒计时 % 10 == 0 时切换图标）
register(LivingIconSpec.builder(Items.TNT)
    .addVariant("lit", "item/tnt_lit", stack -> getFuseTimer(stack) > 0 && getFuseTimer(stack) % 10 == 0)
    .addVariant("idle", "item/tnt_idle", stack -> true)  // 兜底
    .build());

// 活漏斗：基础图标 + 箭头叠加层
register(LivingIconSpec.builder(Items.HOPPER)
    .addVariant("base", "item/hopper_living", stack -> true)
    .decorator(new LivingHopperDecorator())
    .build());

// 活中继器：方向感知 + GUI 缩放
register(LivingIconSpec.builder(Items.REPEATER)
    .addVariant("1tick", "item/repeater_1tick", stack -> getDelay(stack) == 1 && !isPowered(stack))
    // ... 更多变体
    .directional()     // 根据物品方向数据旋转图标
    .guiScale(1.0f)    // GUI 缩放比例（1.0 = 原始大小）
    .build());

// 活红石火把：方向感知（默认大小）
register(LivingIconSpec.builder(Items.REDSTONE_TORCH)
    .addVariant("on", "item/redstone_torch", stack -> isLit(stack))
    .addVariant("off", "item/redstone_torch_off", stack -> true)
    .directional()
    .build());
```

---

## 4. 新增活物品图标

新增活物品图标只需两步：

1. 在 `LivingIconRegistry.registerAll()` 中添加一个 `LivingIconSpec` 声明
2. 准备对应的纹理 PNG 和模型 JSON 文件

无需编写任何 Java 类。

---

## 5. 当前支持的活物品图标

| 活物品 | 变体 | 纹理 | 特效 |
|--------|------|------|------|
| 活漏斗 | `base` | `hopper_base.png` | 箭头叠加层（方向旋转） |
| 活熔炉 | `idle` / `active` | 模型 `item/furnace_idle` / `item/furnace_active`（`layer0` 引**原版** `minecraft:block/furnace_front` / `furnace_front_on`） | 燃烧状态切换 |
| 活TNT | `idle` / `lit` | 模型 `item/tnt_idle`（引**原版** `minecraft:block/tnt_side`）/ `item/tnt_lit`（自绘 `tnt_lit.png`） | 引信闪烁动画（每10 tick切换） |
| 活箱子 | `base` | `item/chest_3d`（`builtin/entity` 3D，**GUI 正面视角 14px**） | 无 |
| 活红石粉 | `base` | 直接引用 `minecraft:block/redstone_dust_dot` | 连接纹理装饰器（`LivingRedstoneDecorator`） |
| 活红石火把 | `on` / `off` | `redstone_torch.png` / `redstone_torch_off.png`（自绘） | 方向旋转 + 点亮切换 |
| 活拉杆 | `on` / `off` | 复用原版 `minecraft:block/lever` / `minecraft:block/lever_on` 模型 | 拉下/弹起状态切换 |
| 活中继器 | `1tick` ~ `4tick_on`（8种） | 复用原版 `minecraft:block/repeater_Xtick` / `repeater_Xtick_on` 模型 | 方向旋转 + 延迟档位 + 供电状态 |
| 活比较器 | `compare` / `compare_on` / `subtract` / `subtract_on` | 复用原版 `minecraft:block/comparator` / `comparator_on` / `comparator_subtract` / `comparator_on_subtract` 模型 | 方向旋转 + 模式切换（subtract 前端火把常亮） + 供电状态 |
| 活末影箱 | `base` | `item/ender_3d`（`builtin/entity` 3D，**GUI 正面视角 14px**） | 无 |
| 活地图 | `base` | `living_map.png` | 地图缩略图装饰器 |
| 活水车 | `base` | ⚠️ **无自有纹理** —— 变体路径 `item/water_wheel` 对应的模型文件**不存在**，实际复用 Create 的水车模型 | 3D 旋转动画（Create 兼容） |
| 活耕地 | `moist` / `dry` | `item/farmland_living_moist` / `item/farmland_living`（复用原版耕地顶面纹理） | 湿润切换 + 种子图标装饰器（`LivingFarmlandSeedDecorator`，已种植时叠加所种作物的种子图标） |

> ⚠️ **表中「纹理」列若写 `.png` 但该文件已不存在，以代码为准。**
> 2026-09-22 起，**与原版同源的纹理已改为直接引用 `minecraft:` 路径**（铜家族 · 熔炉 · 漏斗 ·
> 红石块/灯 · TNT 等，共 56 张已删），目的是**不再随包分发原版资源**，附带收益是
> **这些图标会跟随玩家自己的材质包**。判据：`ls src/main/resources/assets/living_item/textures/item/`。

### 5.1 Builder 可用选项

| 方法 | 说明 | 默认值 |
|------|------|--------|
| `.addVariant(name, path, predicate)` | 添加图标变体（可多次调用） | 必选 |
| `.decorator(IItemDecorator)` | 设置物品栏叠加层装饰器 | 无 |
| `.directional()` | 启用方向感知旋转（根据物品数据旋转图标） | 关闭 |
| `.guiScale(float)` | 设置 GUI 中的缩放比例 | 1.0 |
| `.rotating()` | 启用 3D 旋转渲染（用于活水车等） | 关闭 |

### 5.2 模型文件策略

模型 JSON 文件可引用**三种**父模型：

**① 2D 平面图标**（用于扁平物品）：
```json
{
  "parent": "item/generated",
  "textures": {
    "layer0": "living_item:item/hopper_living"
  }
}
```

**② 3D 方块模型**（用于方块实体物品，如中继器、比较器）：
```json
{
  "parent": "minecraft:block/comparator_subtract",
  "display": {
    "gui": {
      "rotation": [90, 0, 0]
    }
  }
}
```

**③ 多层叠加**（在原版纹理上叠加自绘部分；`item/generated` 支持 `layer0`~`layer4`）：
```json
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "minecraft:block/copper_block",
    "layer1": "living_item:item/wax_ring"
  }
}
```
原版用例可参照 `minecraft:item/leather_chestplate`（`layer0` + `layer1` 染色叠加）、
`item/potion`、`item/tipped_arrow` —— **已确认 `item/generated` 支持多层**。
⇒ 用「引用原版 layer0 + 自绘 layer1」可**既不再分发原版资源、又保留自定义外观**（涂蜡铜块即此方案）。

直接引用原版方块模型，无需自绘纹理。缩放和方向旋转由 `guiScale()` 和 `directional()` 在代码层统一处理，不在 JSON 中硬编码。

> ⚠️ **层叠加只解决「静态叠加」**。需要**按物品数据旋转某一层**（如漏斗箭头）时，
> 模型层做不到 —— 见「渲染上下文覆盖范围」的方案 B/C。

### 5.3 DirectionalLivingModel 变换顺序

`DirectionalLivingModel` 在 **JSON transform 之前** 应用缩放和 Z 轴旋转：

```java
// 正确顺序：先缩放/旋转，再应用 JSON 的 display 变换
poseStack.scale(guiScale, guiScale, 1.0f);       // 1. 缩放
poseStack.mulPose(Axis.ZP.rotationDegrees(r));    // 2. 方向旋转
inner.applyTransform(context, poseStack, ...);     // 3. JSON display 变换
```

**为什么是这个顺序**：方块模型的 JSON 中通常有 `"rotation": [90, 0, 0]`（X 轴旋转 90°），这会改变 PoseStack 坐标系。如果在 JSON transform 之后再旋转，`Axis.ZP` 就不再是屏幕垂直轴。先旋转再应用 JSON transform 确保方向旋转始终在屏幕空间中正确执行。**这对 2D 平面模型无影响（无 JSON 旋转），对 3D 方块模型至关重要。**

---

## 渲染上下文覆盖范围（重要约束）

> ⚠️ **活物品的「自定义图标」只覆盖部分渲染场景，且三层架构各自的覆盖范围不同。**
> 排查「同一物品在不同地方长得不一样」时**先读本节**，别急着改渲染代码。

### 三层各自的生效范围

| 层 | 生效 | **不生效** |
|---|---|---|
| **Layer 1 + 2**（模型注入 / `GenericContextAwareModel`） | **仅 `ItemDisplayContext.GUI`** | **手持 · 掉落物 · 展示框 · 第三人称** ⇒ 一律**回退原版模型** |
| **Layer 3**（`IItemDecorator`） | 走 `GuiGraphics.renderItemDecorations` 的 GUI：快捷栏 / 容器 GUI / 创造物品栏 / 副手槽 | **全部世界渲染**（掉落物 · 展示框 · 手持）；**不跑该管线的第三方 GUI**（如 JEI） |

### 两条机制（均为**有意设计**，不是 bug）

**① `GenericContextAwareModel.applyTransform()` 非 GUI 直接返回 `vanillaModel`**

```java
if (context == ItemDisplayContext.GUI) { livingModel.applyTransform(...); return this; }
return vanillaModel.applyTransform(context, poseStack, applyLeftHandTransform);   // ← 手持/地面 = 原版外观
```

⇒ **所有走变体路径的活物品**（熔炉 · 红石灯 · 拉杆 · 中继器 · 比较器 · 雕文铜块 …）
在**掉落物 / 手持 / 展示框**里都显示**原版外观**。

**② `IItemDecorator` 只在 GUI 内被调用**

`LivingHopperDecorator` 类注释已声明：*「IItemDecorator 只在物品栏/快捷栏中生效，不影响手持和地面渲染。」*
⇒ 依赖叠加层表达状态的物品，其**叠加层在 GUI 之外一律消失**。

### 组合后果：可能出现「只有基础层」

以**活漏斗**为例（三层 = `hopper_base` 模型 + 输入箭头 + 输出箭头两个装饰器）：

| 场景 | 实际显示 |
|---|---|
| 物品栏 / 快捷栏 / 容器 GUI | 中心圆 + 两个箭头 ✓ |
| 掉落物 / 手持 / 展示框 | **原版漏斗**（模型层也回退了） |
| 不跑 `renderItemDecorations` 的第三方 GUI | **只有中心圆**（模型层生效、叠加层缺失） |

**同源影响的装饰器**（`grep -rln "implements IItemDecorator" src/main/java/` = 7 个，注册 6 个）：
`LivingHopperDecorator`（漏斗方向箭头）· `LivingChiseledCopperDecorator` / `LivingWaxedChiseledDecorator`
（雕文箭头）· `LivingMapIconDecorator`（地图缩略图）· `LivingFarmlandSeedDecorator`（耕地种子）·
`LivingRedstoneDecorator`（红石连接线）· `LivingDefaultDecorator`

### 若要求「全形态一致」需要做什么（**未实施，备查**）

| 方案 | 做法 | 代价 / 前提 |
|---|---|---|
| **A** | 让 `GenericContextAwareModel` 在**所有上下文**返回 livingModel | 改动最小（几行）；**但叠加层仍只在 GUI** ⇒ 只能消除「显示原版外观」，箭头依旧缺 |
| **B** | 叠加层内容**搬进模型层**，用 `BakedModel` 包装器**旋转 quad** 表达方向 | ⚠️ **必须旋转 quad，不能用 `applyTransform`** —— 后者只在 GUI 生效，做不到全上下文一致。方向角来自 stack，而 `getQuads()` **无 stack 参数** ⇒ 须在 `resolve()` 里按组合生成并缓存模型（漏斗 4×4 = **16 个**） |
| **C** | 预生成旋转后的纹理 + 变体模型 JSON，用 predicate 选 | 文件多（漏斗需 8 张旋转纹理 + 16 个 JSON），但**零渲染钩子**、最直白 |

**决策（2026-09-22）**：**暂不实施**。理由：现状是**有意的取舍**（世界渲染显示原版外观可接受）；
且方案 B/C 都需**游戏内验证**「`item/generated` 的图层混合能否复现装饰器 `blit` 的叠加效果」。

---

## GUI 图标光照约定（⚠️ 新加活物品图标必读）

**背景**：在 GUI 里渲染 3D 模型当图标时，**光照不会自动正确** —— 历史上每加一个这类图标
（作物方块模型、箱子/末影箱 BEWLR、方块模型类图标）都要手工处理一次，容易漏
（2026-09-22 活箱子就漏了 ⇒ 正面视角的箱子明显比周围图标暗）。

**先说结论：现在有两条路径，都已统一，新加图标不必再手改光照。**

### 路径 A：`LivingIconSpec`（item 模型图标）—— 自动全亮

**适用**：图标能表达成 item 模型 —— 2D（`item/generated`）、方块模型（`parent: "minecraft:block/xxx"`）、
乃至 3D 方块实体（`parent: "builtin/entity"`，走 BEWLR）。

**机制**：`GenericContextAwareModel.usesBlockLight()` **恒返回 `false`**。依据是
`GuiGraphics.renderItem`（1.21.1 里 `usesBlockLight()` 的**唯一**使用点）：

```java
this.pose.scale(16.0F, -16.0F, 16.0F);
boolean flag = !bakedmodel.usesBlockLight();
if (flag) Lighting.setupForFlatItems();          // 平铺光照：各面同亮（无方向性漫反射）
itemRenderer.render(..., 15728880 /* = FULL_BRIGHT */, ...);
if (flag) Lighting.setupFor3DItems();            // 3D 光照：按法线做明暗
```

> ⚠️ **关键认知（反直觉）**：传进去的 `packedLight` **本来就是 `FULL_BRIGHT`(15728880)** ——
> 所以「图标发暗」**与光照等级无关**，改 light 参数**没用**。
> 真凶是 `setupFor3DItems()` 的 `DIFFUSE_LIGHT_0/1`（`Lighting` 里的两个固定光向量，
> 做 `minecraft_mix_light` 法线漫反射）：正面朝相机的 3D 图标法线点乘后亮度只剩 ~0.7
> 甚至更低 ⇒ 明显比 2D 图标暗。
> ⇒ **要「全局光照」就改 `usesBlockLight()`，不是改 light 值。**

⚠️ 恒 `false` 的安全性：`usesBlockLight()` 在 1.21.1 **只被 `GuiGraphics` 读**；
掉落物 / 手持 / 世界渲染走各自的光照路径，不读该属性 ⇒ 无副作用。

#### ⚠️ 强制约定：**每个 `BakedModel` 包装类都必须 `usesBlockLight() → false`**

这条规则**曾在三个类里各手写一遍**（2026-09-22 排查时发现水车漏了）⇒ 新加包装类**必须照做**，
否则那个图标在 GUI 里会发暗（且只有游戏内肉眼能发现）。当前实现清单：

| 类 | `usesBlockLight()` | 说明 |
|---|---|---|
| `GenericContextAwareModel` | **`false`** | 普通图标（2D / 方块模型 / `builtin/entity`） |
| `DirectionalLivingModel` | **`false`** | 方向图标（中继器 / 比较器 / 拉杆 / 红石火把） |
| `RotatingWaterWheelModel` | **`false`** | 旋转图标（活水车）；原先委托 `baseModel` ⇒ 3D 水车发暗，已修 |
| `GenericLivingModelWrapper` | 委托 `vanillaModel` | **例外且有意** —— 它是注入 bake 表的那层，`getOverrides()` 返回自定义 overrides ⇒ ItemRenderer 用的是 **resolve 后的模型**，本类自身不参与渲染 ⇒ 委托 vanilla 只为「非活物品」保持原版行为 |

⚠️ 判断某个包装类「要不要改」的方法：**看它会不会成为 `resolve()` 的返回值**。
`resolve()` 返回 `GenericContextAwareModel` / `DirectionalLivingModel` / `RotatingWaterWheelModel`
或原版模型 —— 只有**会被返回的**才需要恒 `false`。

### 路径 B：手绘方块模型（容器内 `renderSingleBlock`）—— 用统一入口

**适用**：图标是**世界方块外观**且需要逐格/多格布局（如活耕地的作物：下部件 + 上部件 +
柱状多段），无法用单个 item 模型表达。

**入口**：`LivingIconRenderHelper.renderBlockIcon(guiGraphics, state, x, y)`（`client/render/`）。

它内部已经处理好三件事（**别再手抄**）：

| 处理 | 原因 |
|---|---|
| `LightTexture.FULL_BRIGHT` | 不传则光照图为 0（世界坐标在 GUI 里无意义）⇒ **全黑** |
| **强制 `RenderType.cutout()`**（7 参重载） | 默认会转实体渲染变体，其着色器带**双光源漫反射** —— 十字/平面模型法线朝水平方向，亮度被吃掉大半 ⇒ 发暗 |
| `try/catch` 异常隔离 | 第三方方块的 `BlockColors` 处理器拿 `null` level/pos 可能 NPE ⇒ 单个图标失败不终止整帧 |

几何契约（与调用点约定，改动前先看注释）：块底锚定槽位格底、按 **18px** 渲染
（16px 会有格缝）、左偏 1px 对齐；`x`/`y` 传**绝对**坐标（调用方自己加 `leftPos/topPos`）。

### 两条路径怎么选

| 需求 | 用 |
|---|---|
| 单图标、静态或随数据换图 | **路径 A**（声明式，写 JSON + 注册 spec） |
| 需要「方块世界外观」且多格布局 / 逐格生长 | **路径 B**（`LivingIconRenderHelper`） |

---

## 槽位叠加层渲染层级（z 层与深度测试窗口）

容器 GUI 里给物品图标叠加自定义内容（装饰器/覆盖层）时，必须分清**两个渲染窗口**
与各自的层级语义——活耕地种子图标曾在三个 z 值上表现出三种结果，根因即此
（2026-09-13，详见 living-farmland-tech.md §8.2/§11.7）。

> **2026-09-16 更新**：种子图标已从 render TAIL **迁到装饰器路径**（槽位渲染窗口）——
> 原实现依赖 `leftPos/topPos`，而 **HUD 快捷栏不经过 `AbstractContainerScreen`**，
> 导致快捷栏里画不出来。见下方「种子图标改走装饰器路径」。

### 两个窗口

| 窗口 | 深度测试 | 内容 | 时机 |
|------|---------|------|------|
| **槽位渲染窗口** | **关闭**（纯绘制顺序，恒可见） | 槽位背景 → 物品模型（名义 z=150）→ IItemDecorator（NeoForge 在 renderItemDecorations 末尾调用，`resetRenderState` 会**重开深度测试**）→ 堆叠数文字（名义 z=200，pose translate）→ 耐久条/冷却覆盖（guiOverlay） | `AbstractContainerScreen.renderSlot` 循环内 |
| **render TAIL** | **开启**（与世界深度缓冲竞争） | 各类 @TAIL 注入（活水流、活耕地作物贴图、扩展地图等） | render 方法末尾 |

### 名义层级表（GuiGraphics pose z）

| z | 内容 |
|---|------|
| 0 | 槽位背景 / 装饰器默认层（`ItemDecoratorHandler.render` 后装饰器自管状态） |
| 100 | 活水流着色（LivingHopperDecorator 自抬 200 画箭头） |
| 150 | 物品模型（`renderItem` 固定 translate z=150，flat 模型 `isGui3d=false` 无 guiOffset） |
| 200 | 堆叠数文字（`renderItemDecorations` 内 pose translate z=200） |
| 300 | 扩展地图底图（「需高于物品 150 与堆叠数 200」——map 渲染经验值） |
| 400 | tooltip |

### TAIL 阶段的深度陷阱（种子图标事件复盘）

TAIL 注入时原版已重开深度测试，绘制要**与世界深度缓冲竞争**——玩家正盯着箱子看时，
箱面离相机很近、深度值很小，名义 z=150/175 的 TAIL 绘制深度判定**输给箱面 → 被吞**；
z=300 才赢过近处箱面 → 可见。这就是「同一段叠加代码在 z=150/175 不显示、z=300 显示」
的完整解释（与世界深度竞争，而非与槽位内物品竞争——物品在槽位窗口内画的，从不写深度）。

### 种子图标改走装饰器路径（2026-09-16）

**问题**：种子图标原先画在 `AbstractContainerScreenMixin.render @TAIL`，硬依赖
`leftPos` / `topPos`——这两个字段**只有 `AbstractContainerScreen` 有**。而 **HUD 快捷栏**
走 `Gui.renderHotbar` → `Gui.renderSlot` → `GuiGraphics.renderItemDecorations`
→ `ItemDecoratorHandler`，**从不经过 `AbstractContainerScreen`** ⇒ 快捷栏里永远不画。

**修复**：迁到 `IItemDecorator`（`LivingFarmlandSeedDecorator`）。关键依据：
`GuiGraphics.renderItemDecorations` 的**最后一行**就是
`ItemDecoratorHandler.of(stack).render(...)`，且容器 GUI 的 `renderSlotContents` 也调它
⇒ **一份装饰器代码同时覆盖快捷栏 / 容器 GUI / 创造物品栏 / 副手槽**，只画一次、无重复。

**装饰器的坐标与 z 契约**（容易搞错，务必记住）：

| 维度 | 契约 |
|------|------|
| 坐标 | **「在当前 pose 内 `translate(xOffset, yOffset, z)`」**。容器传的是 `slot.x/slot.y`（**未加** `leftPos`——`renderSlot` 已 `translate(leftPos, topPos, 0)`）；快捷栏传绝对坐标（pose 为 identity）。**装饰器绝不能自己再加 `leftPos`** |
| z 基准 | 容器：`renderSlot` 有 `translate(0,0,100)` ⇒ 装饰器 z=200 → **净 300**；快捷栏：pose 为 identity ⇒ **净 200** |
| 渲染状态 | `ItemDecoratorHandler` 每次调用前 `resetRenderState()`（**开**深度测试 + 开 blend），返回后 `restoreGlState` ⇒ **装饰器里不要写 `RenderSystem` 调用**，也不能照搬 TAIL 版的 `disableDepthTest()` |

**结论**：装饰器自抬 **z=200** 两侧都可见（容器净 300 —— 正是本页记载「才可见」的值；
快捷栏净 200 > 物品 150；4 个既有装饰器同款）。全尺寸图标会盖住堆叠数数字——既有取舍。

> **生长槽大图无法同样迁移**：它需要「同容器正上方一格槽位」的邻居关系
> （`living_item$findSlotAbove`），而装饰器只拿得到 `xOffset/yOffset`、拿不到容器槽表；
> 快捷栏也没有该结构。故它留在 `AbstractContainerScreenMixin`。

**方块级叠加：renderSingleBlock（世界级管线直绘）**——若叠加的是「方块在世界里的样子」而非贴图，用 `BlockRenderDispatcher.renderSingleBlock(state, pose,
buffer, FULL_BRIGHT, NO_OVERLAY, ModelData.EMPTY, RenderType.cutout())` 走完整
世界渲染管线（模型几何/BlockColors 染色/RenderType 路由全自动，任意模组方块零
特判），pose 配方 `translate(x, y+16, 层)`（角落原点模型，块底锚定槽位底部！
物品中心原点的 x+8/y+8 会错位半格）+ `scale(16, -16, 16)`（1 方块=16px、Y 翻转
对齐 GUI），画完 `bufferSource.endBatch()`。**RenderType 必须强制 cutout 系**：
默认会转实体渲染变体、其着色器带双光源漫反射（按法线着色），十字模型法线朝
水平方向会被漫反射吃掉大半亮度 → 发暗
立即物化。活耕地生长槽的作物模型即此路线（两格高作物上下两段各画一次）。边界：
不含 BlockEntity 渲染器（BEWLR 类需走 BE dispatcher）。实体同理：
`InventoryScreen.renderEntityInInventory*`（原版背包玩家预览同款）+ 客户端假实体。

**TAIL 叠加层的正确姿势**（活耕地种子图标定案）：

```java
RenderSystem.enableBlend();
RenderSystem.defaultBlendFunc();
RenderSystem.disableDepthTest();      // 回到槽位窗口同款语义，不与世界深度竞争
guiGraphics.blit(x, y, 175, 16, 16, sprite);   // 立即模式（drawWithShader 同步 flush）
RenderSystem.enableDepthTest();
RenderSystem.disableBlend();
```

要点：
- **立即模式 blit**（innerBlit → drawWithShader 同步 flush，状态在绘制时确定），
  不要用 `renderItem`（BufferSource 缓冲绘制，flush 时机/状态与 TAIL 不确定）；
  物品图标纹理取物品模型粒子图标（`getItemRenderer().getModel(stack).getParticleIcon()`
  = item/generated 的 layer0），见 `CropTextureResolver.getItemSprite`
- `disableDepthTest` 临时窗口 + nominal z 保留层级语义（175 = 高于物品 150）
- blend 保纹理透明像素（水桶渲染同款先例）；装饰器场景 NeoForge 已代管状态
  （`resetRenderState`/`restoreGlState`），自抬 z 即可（LivingHopperDecorator 先例）

**与槽位窗口内内容的相对层级**：立即模式绘制顺序晚于槽位窗口的全部内容
（物品/堆叠数文字都是缓冲绘制，帧末才 flush）→ TAIL 立即叠加层天然盖住它们。
要避免盖住堆叠数数字，有两条路：**缩小叠加层/居中避让数字角**、或**按原版同位
同式重绘该层**（blit 后用 drawString 重绘堆叠数文字）——两者都有视觉代价（半尺寸
改变构图 / 多一次重绘）。活耕地种子图标最终**接受覆盖数字**（全尺寸叠加，类型辨识
优先，见 living-farmland-tech.md §8.2 三轮迭代记录）。

### 交叉引用

- 活耕地双槽渲染实操：[living-farmland-tech.md §8.2](../tech/living-farmland-tech.md)
- 深度测试窗口的另一个坑（按下拦截/释放阶段）：[gui-click-interception.md](../guides/gui-click-interception.md)

---

## 已完成的实验（2026-09-22）

### 活箱子 / 活末影箱走原版 `builtin/entity` 3D 渲染 → ✅ 定稿：正面视角 3D + 默认标记

**背景**：这两张图标（`chest.png` / `ender.png`）是**把原版拆开的纹理合成成一张**的产物
⇒ 属原版衍生物。用户提出：**有方块实体的方块本来就有办法直接渲染在物品栏里**，
参考活拉杆/活红石元件 —— 那样**连图标都不需要**。

**实验经过（三轮，2026-09-22 一天内）**：

1. **实验**（提交 `20d6ab9`）：注释掉 `Items.CHEST` / `Items.ENDER_CHEST` 的 `register(...)`
   ⇒ 不注入 wrapper ⇒ 完全原版：`builtin/entity` → `ChestRenderer`（BEWLR）3D 箱子
   + `LivingDefaultDecorator` 自动叠加 `living.png`（无 spec 的物品走排除法默认挂标记）。
2. **误判回退**：用户初报「图标没有变化」，AI 误判为「失去活标识」⇒ 取消注释回退平面图标。
   实际上**活标识一直在**（`living.png` 叠加），「图标和原版一样」本来就是该方案的预期行为。
3. **定稿**（用户拍板）：恢复 3D 方案，但要求 **GUI 里显示正面视角、边长约 14px**
   （与 16×16 图标内容观感一致），而不是原版的 `[30,45,0]` 等距角。

**最终实现（三条改动）**：

1. **`chest_3d.json` / `ender_3d.json`**（新）：
   `parent: "builtin/entity"` + `display.gui { rotation [0,0,0], translation [0,1,0], scale [1,1,1] }`。
   bake 成 `BuiltInModel`（保留自定义 transforms，quads 为空，`isCustomRenderer()=true`）。
2. **`LivingIconRegistry`**：两个 spec 指向 `item/chest_3d` / `item/ender_3d`，
   并显式 `.decorator(DEFAULT_DECORATOR)` —— 专属 spec 会使物品进入 `dedicatedItems`
   而失去默认标记，必须手动挂回。旧 `chest.png` / `ender.png` / 平面 JSON 已删。
3. **无需改 `GenericContextAwareModel`**：其 `isCustomRenderer()` 委托 `livingModel`
   ⇒ BuiltInModel 时返回 true ⇒ ItemRenderer 走 BEWLR 而非 quads
   （⚠️ 走 quads 会画不出东西 —— BuiltInModel.getQuads() 为空）。

**几何依据（读原版 `ChestRenderer` 源码）**：

- 物品渲染（无 level）时 `FACING` **强制 SOUTH**、`Y 旋转 -0°` ⇒ 箱子无旋转；
  锁扣几何在 **+Z 面**（`lock: addBox(7,-2,14, 2,4,1)`，z 伸到 16）⇒ **正面天然朝相机**
  ⇒ `rotation [0,0,0]` 就是正面视图（末影箱复用同一渲染器，同结论）
- 箱体 `addBox(1,0,1,14,10,14)` ⇒ 宽 14 单位 ⇒ `scale 1.0` 时 GUI 里恰好 **14px**
- 箱体高 14（y 0~14，中心 y=7≠8）⇒ `translation [0,1,0]` 上移 1px 垂直居中

**渲染链路（三层，逐层验证）**：

```
minecraft:item/chest (原版 BuiltInModel [30,45,0])
  └ GenericLivingModelWrapper（getOverrides → GenericLivingItemOverrides）
      └ resolve: 活物品 → GenericContextAwareModel(livingModel=chest_3d BuiltInModel, vanillaModel=原版)
            ├ isCustomRenderer() = livingModel.isCustomRenderer() = true ⇒ BEWLR（ChestRenderer）
            ├ applyTransform(GUI)  → livingModel（正面 [0,0,0] scale 1）⇒ 正面 14px 箱子
            └ applyTransform(其它) → vanillaModel（原版 [30,45,0] 0.625）⇒ 掉落物/手持保持等距 3D
        resolve: 非活物品 → vanillaModel（原版箱子，等距角）
```

⚠️ **注意 1.21.1 的判据是 `isCustomRenderer()`**（NeoForge `ItemRenderer.render` 里
`!p_model.isCustomRenderer()` ⇒ quads 分支 / else ⇒ `IClientItemExtensions.getCustomRenderer()`
= BEWLR 分支）—— 老版本/memory 里说的 `usesBlockEntity()` 在 1.21.1 **不存在**（编译即报错）。

⚠️ **教训（实验流程）**：第 2 步误判的根因是**没查 `LivingDefaultDecorator` 的排除法注册逻辑**
就下结论「没有活标识」。判断某物品「有没有 X」之前，先 grep 挂载机制。

### 活红石粉的「模型层 + 装饰器」染色方案（同期发现 / 验证）

**问题**：原版 `block/redstone_dust_dot.png` 是**纯白/灰度**（254,254,254 等）—— 原版物品
`item/redstone` 是已上色的不同纹理（红）。但本模组**去原版化**（2026-09-22）后不再随包分发
任何 `block/redstone_dust_*` 副本，模型 `item/redstone_dust.json` 改为引用
`minecraft:block/redstone_dust_dot` 作为 `layer0`（白），由装饰器
`LivingRedstoneDecorator` 通过 `GuiGraphics.setColor(red)` + blit 同一张白点实现染色。

**回退发现（2026-09-22 用户游戏内测）**：早期实现里装饰器引用的是**模组自己那张已被删的**
`living_item:item/redstone_dust_dot.png` ⇒ 缺失纹理（黑紫色），叠加在原版红点上面。
**修复**：装饰器 `DOT_TEXTURE` 改为 `minecraft:block/redstone_dust_dot.png`（白点），
与模型 `layer0` 引用同一张纹理 ⇒ 模型层画白点 + 装饰器叠染色点 = 可见红点。
> **教训（去原版化扫描的红线）**：Java 里 `fromNamespaceAndPath(MOD_ID, "textures/...")`
> 的字符串**不带 `living_item:` 前缀**，按 `living_item:textures/...` 扫是扫不到的。
> 以后删纹理时必须同时扫 `fromNamespaceAndPath(MOD_ID, ...)` 的所有调用点。

**保留 `redstone_dust_line0.png`**：md5 与原版不同（d02bdd94aa26 vs ad4c7fb1610b，体积 140B vs 125B），
是去底色优化版（装饰器用 `setColor` 染色时不会混进原版的灰度背景），**保留**。

---

## 关键文件

| 文件 | 职责 |
|------|------|
| `LivingIconSpec` | 图标声明式配置（建造者模式：变体+谓词+叠加层+方向+缩放） |
| `LivingIconRegistry` | 图标注册中心（统一管理所有活物品图标配置和模型注入） |
| `GenericLivingModelWrapper` | 通用模型包装器（注入自定义 ItemOverrides） |
| `GenericContextAwareModel` | 通用上下文切换模型（GUI 显示自定义图标，手持显示原版图标） |
| `GenericLivingItemOverrides` | 通用覆盖解析器（根据 Variant.predicate 匹配变体模型） |
| `LivingIconRenderHelper` | **GUI 图标渲染统一光照入口**（FULL_BRIGHT + 强制 cutout + 异常隔离），供「容器内手绘方块模型」类图标使用；见「GUI 图标光照约定」 |
| `DirectionalLivingModel` | 方向感知模型包装器（在 JSON transform 前应用 Z 轴旋转和缩放，确保屏幕空间正确） |
| `TorchRenderState` | 方向旋转状态（ThreadLocal，存储当前渲染的方向角度） |
| `RotatingWaterWheelModel` | 活水车物品栏 3D 旋转渲染模型 |
| `WaterWheelRenderState` | 活水车渲染状态数据（ThreadLocal，存储 RPM） |
| `LivingRedstoneDecorator` | 活红石粉连接纹理装饰器（根据 connections 位掩码绘制 4 方向红色连接线 + 动态着色） |
| `LivingFarmlandSeedDecorator` | 活耕地种子图标装饰器（已种植时在耕地槽叠加所种作物的种子图标；走装饰器路径故快捷栏也生效） |

## 纹理约定（涂蜡铜灯图标）

- **涂蜡 vs 未涂蜡的唯一区别 = 外圈 60 像素黄框**，颜色 `(232,160,62,255)`。
  **实测 24/24 零例外**：4 个锈蚀等级 × 6 种形状（block / cut / chiseled / grate / bulb / bulb_lit）
  的**掩码完全一致**，且外圈 60 像素**全部**是该色。判据（可复算）：
  外圈 = `x∈{0,15}` 或 `y∈{0,15}`，共 `16*4-4 = 60` 像素。
- ⚠️ **2026-09-22 起，涂蜡不再使用 24 张独立纹理**，改为**模型双层**：
  ```json
  { "parent": "minecraft:item/generated",
    "textures": { "layer0": "minecraft:block/<原版铜纹理>",
                  "layer1": "living_item:item/wax_ring" } }
  ```
  `wax_ring.png` = **外圈 60 px 黄框 + 内部 196 px 全透明**，**一张 mask 覆盖全部 24 张**。
  ⇒ 24 张 `waxed_*.png` 已删；**原版纹理不再随包分发**，且自动跟随玩家材质包。
  验证方式：模拟「原版 + 外圈黄框」与原图逐像素比对（当时 24/24 字节一致）。
- ⚠️ **涂蜡不能用 `IItemDecorator` 实现** —— 装饰器只在 GUI 绘制（见「渲染上下文覆盖范围」），
  而涂蜡是**物品身份**，必须到处可见 ⇒ **必须走模型层**。
- item 纹理均为 16×16 PNG（P 调色板与 RGBA 两种都有效）。
- ⚠️ 若日后重新引入涂蜡独立纹理：**只改内部、保留外圈掩码** —— 四种锈蚀级掩码不一致
  是已发生过的历史 bug（曾出现 4 个 `waxed_*_copper_bulb_lit.png` 黄框丢失 + 内部乱码，
  逐像素比对才修好）。
- 图片处理用**隔离 venv + Pillow**（别污染系统 Python）。
- **判定纹理是否为原版副本的正确方法**（本次踩过假阳性，务必照做）：
  ① 先比 **raw MD5**（字节完全相同）② 再比 **RGBA 像素 MD5**（仅重编码）
  ③ 疑似改色时**必须比 alpha 通道**：`a.tobytes()[3::4] == b.tobytes()[3::4]`
  （**别写成 `a_alpha[3::4]` —— `a_alpha` 已是 alpha 通道，再切一次就错**）。
  ⚠️ **纯色/大面积透明图会产生大量巧合匹配**：`tnt_lit`（纯白）与原版 `lightning_rod_on`
  字节相同纯属巧合；`redstone_dust_overlay` 与任何「小面积不透明」纹理都会"近似"。
