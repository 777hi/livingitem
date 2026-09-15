# 活物品图标系统设计

> **文档版本**: 2026.08 v4
> **最后更新**: 2026-08-21
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
  - [关键文件](#关键文件)

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
| 活熔炉 | `idle` / `active` | `furnace_idle.png` / `furnace_active.png` | 燃烧状态切换 |
| 活TNT | `idle` / `lit` | `tnt_idle.png` / `tnt_lit.png` | 引信闪烁动画（每10 tick切换） |
| 活箱子 | `base` | `chest_living.png` | 无 |
| 活红石粉 | `base` | `item/redstone_dust`（`item/generated` 平面纹理） | 连接纹理装饰器（`LivingRedstoneDecorator`） |
| 活红石火把 | `on` / `off` | `redstone_torch.png` / `redstone_torch_off.png` | 方向旋转 + 点亮切换 |
| 活拉杆 | `on` / `off` | 复用原版 `minecraft:block/lever` / `minecraft:block/lever_on` 模型 | 拉下/弹起状态切换 |
| 活中继器 | `1tick` ~ `4tick_on`（8种） | 复用原版 `minecraft:block/repeater_Xtick` / `repeater_Xtick_on` 模型 | 方向旋转 + 延迟档位 + 供电状态 |
| 活比较器 | `compare` / `compare_on` / `subtract` / `subtract_on` | 复用原版 `minecraft:block/comparator` / `comparator_on` / `comparator_subtract` / `comparator_on_subtract` 模型 | 方向旋转 + 模式切换（subtract 前端火把常亮） + 供电状态 |
| 活末影箱 | `base` | `ender.png` | 无 |
| 活地图 | `base` | `living_map.png` | 地图缩略图装饰器 |
| 活水车 | `base` | `water_wheel.png` | 3D 旋转动画（Create 兼容） |
| 活耕地 | `moist` / `dry` | `item/farmland_living_moist` / `item/farmland_living`（复用原版耕地顶面纹理） | 湿润切换 + 种子图标装饰器（`LivingFarmlandSeedDecorator`，已种植时叠加所种作物的种子图标） |

### 5.1 Builder 可用选项

| 方法 | 说明 | 默认值 |
|------|------|--------|
| `.addVariant(name, path, predicate)` | 添加图标变体（可多次调用） | 必选 |
| `.decorator(IItemDecorator)` | 设置物品栏叠加层装饰器 | 无 |
| `.directional()` | 启用方向感知旋转（根据物品数据旋转图标） | 关闭 |
| `.guiScale(float)` | 设置 GUI 中的缩放比例 | 1.0 |
| `.rotating()` | 启用 3D 旋转渲染（用于活水车等） | 关闭 |

### 5.2 模型文件策略

模型 JSON 文件可引用两种父模型：

**2D 平面图标**（用于扁平物品）：
```json
{
  "parent": "item/generated",
  "textures": {
    "layer0": "living_item:item/hopper_living"
  }
}
```

**3D 方块模型**（用于方块实体物品，如中继器、比较器）：
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

直接引用原版方块模型，无需自绘纹理。缩放和方向旋转由 `guiScale()` 和 `directional()` 在代码层统一处理，不在 JSON 中硬编码。

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

## 关键文件

| 文件 | 职责 |
|------|------|
| `LivingIconSpec` | 图标声明式配置（建造者模式：变体+谓词+叠加层+方向+缩放） |
| `LivingIconRegistry` | 图标注册中心（统一管理所有活物品图标配置和模型注入） |
| `GenericLivingModelWrapper` | 通用模型包装器（注入自定义 ItemOverrides） |
| `GenericContextAwareModel` | 通用上下文切换模型（GUI 显示自定义图标，手持显示原版图标） |
| `GenericLivingItemOverrides` | 通用覆盖解析器（根据 Variant.predicate 匹配变体模型） |
| `DirectionalLivingModel` | 方向感知模型包装器（在 JSON transform 前应用 Z 轴旋转和缩放，确保屏幕空间正确） |
| `TorchRenderState` | 方向旋转状态（ThreadLocal，存储当前渲染的方向角度） |
| `RotatingWaterWheelModel` | 活水车物品栏 3D 旋转渲染模型 |
| `WaterWheelRenderState` | 活水车渲染状态数据（ThreadLocal，存储 RPM） |
| `LivingRedstoneDecorator` | 活红石粉连接纹理装饰器（根据 connections 位掩码绘制 4 方向红色连接线 + 动态着色） |
| `LivingFarmlandSeedDecorator` | 活耕地种子图标装饰器（已种植时在耕地槽叠加所种作物的种子图标；走装饰器路径故快捷栏也生效） |

## 纹理约定（涂蜡铜灯图标）

- **涂蜡 vs 未涂蜡的唯一区别 = 外圈 60 像素黄框**，颜色 `(232,160,62,255)`；
  四种锈蚀等级（copper / exposed / weathered / oxidized）的**边框掩码完全一致**。
  发光版（lit）同理：内部取未涂蜡发光图，外圈填黄框。
- item 纹理均为 16×16 PNG（P 调色板与 RGBA 两种都有效）。
- ⚠️ 改这批图标时**只改内部、保留外圈掩码** —— 四种锈蚀级掩码不一致是已发生过的历史 bug
  （曾出现 4 个 `waxed_*_copper_bulb_lit.png` 黄框丢失 + 内部乱码，逐像素比对才修好）。
- 图片处理用隔离 venv：
  `C:/Users/AI-777hi/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe`（Pillow 12.3.0）。
