# 活物品图标系统设计

> **文档版本**: 2026.08 v2
> **最后更新**: 2026-08-20
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
│  Layer 3: IItemDecorator（可选）                             │  ← 箭头叠加层（仅物品栏）
│  例: LivingHopperDecorator                                  │
│      hopper_arrow_in.png / hopper_arrow_out.png             │
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
    .guiScale(1.15f)   // GUI 中放大 15%
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
| 活红石灯 | `on` / `off` | `redstone_lamp_on.png` / `redstone_lamp.png` | 信号点亮切换 |
| 活红石火把 | `on` / `off` | `redstone_torch.png` / `redstone_torch_off.png` | 方向旋转 + 点亮切换 |
| 活拉杆 | `on` / `off` | `lever.png` / `lever_on.png` | 拉下/弹起状态切换 |
| 活中继器 | `1tick` ~ `4tick_on`（8种） | `repeater_Xtick.png` | 方向旋转 + 延迟档位 + 供电状态 |
| 活比较器 | `compare` / `compare_on` / `subtract` / `subtract_on` | `comparator_*.png` | 方向旋转 + 模式切换 + 供电状态 |
| 活末影箱 | `base` | `ender.png` | 无 |
| 活地图 | `base` | `living_map.png` | 地图缩略图装饰器 |
| 活水车 | `base` | `water_wheel.png` | 3D 旋转动画（Create 兼容） |

### 5.1 Builder 可用选项

| 方法 | 说明 | 默认值 |
|------|------|--------|
| `.addVariant(name, path, predicate)` | 添加图标变体（可多次调用） | 必选 |
| `.decorator(IItemDecorator)` | 设置物品栏叠加层装饰器 | 无 |
| `.directional()` | 启用方向感知旋转（根据物品数据旋转图标） | 关闭 |
| `.guiScale(float)` | 设置 GUI 中的缩放比例 | 1.0 |
| `.rotating()` | 启用 3D 旋转渲染（用于活水车等） | 关闭 |

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