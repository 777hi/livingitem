# Living Flint and Steel (活打火石) 技术文档

> **文档版本**: 2026.08 v3  
> **最后更新**: 2026-08-16  
> **适用版本**: Minecraft 1.21.1

## 目录
0. [玩法定义](#-0-玩法定义)
1. [架构概览](#1-架构概览)
2. [工作原理](#2-工作原理)
3. [数据结构](#3-数据结构)
4. [Tooltip 显示](#4-tooltip-显示)

---

## §0 玩法定义

> **实现入口**：`living/function/LivingFlintAndSteelFunction`（交互触发器，无 tick 逻辑）——
> GUI 规则在 `assets/living_item/interaction_rules.json`，处理器由 `living/domain/tnt/TntRegistration` 注册：
> `IgniteHandler` / `IgniteCarriedHandler`。

> **性质**：本节「规则」是**契约**（违反即 bug），不是实现细节 —— 它是本子系统
> **功能需求的来源**与**行为对错的判据**。⚠️ **只写规则与语义**；数值与操作步骤归实现层。

**语义**：**活打火石 = 触发类活物品**，自身**不执行任何 tick 逻辑** —— 提供活化标记，
实际点火由 **GUI 右键交互**执行，支持光标与槽位中的活打火石 / 活TNT互换。

**获取**：原版打火石 + 活化。

**核心循环**：GUI 中光标活打火石右键槽位活TNT，或光标活TNT右键槽位活打火石 ⇒ **点火**。

**规则**（违反即 bug）：

| # | 规则 | 为什么 / 后果 |
|---|---|---|
| 1 | 生存模式点火交互**扣 1 点耐久**，创造模式不扣；耐久耗尽可损坏 | 由 GUI 处理器执行 |
| 2 | 点火走 **GUI 交互**（`ignite` / `ignite_carried`），不走活漏斗传输 | 活打火石**自己没有 tick 逻辑** |
| 3 | 点火**不触发活漏斗冷却**；已点燃 TNT 不重置引信 | 点火不是传输成功事件 |

**能力清单**：✅ 点火标记 · ✅ Tooltip
**验证入口**：§2.1「点火触发流程」· 活 TNT 侧见 [living-tnt-tech.md](living-tnt-tech.md) §3.2

---

## 1. 架构概览

### 1.1 什么是活打火石？

活打火石是一种**触发类活物品**，自身不执行任何 tick 逻辑，仅作为 GUI 交互的点火器。其与活TNT在光标 / 槽位间右键交互时，点燃活TNT的引信，无方向配置或活漏斗要求。

活打火石的宿主物品是 `minecraft:flint_and_steel`（打火石），必须同时具备活物品标记。

### 1.2 设计理念

活打火石提供活化标记，自身无 tick 状态；点燃由 GUI 交互处理器执行：

```
光标活打火石 + 右键槽位活TNT → ignite → 点燃槽位中的 TNT
光标活TNT + 右键槽位活打火石 → ignite_carried → 点燃光标上的 TNT
```

点火不依赖槽位邻接、方向配置或活漏斗，不执行物品传输。

### 1.3 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingFlintAndSteelFunction` | `function/LivingFlintAndSteelFunction.java` | 活打火石功能入口，空 tick，仅提供 Tooltip |
| `IgniteHandler` / `IgniteCarriedHandler` | `domain/tnt/` | GUI 双向点火处理器，扣耐久、点燃引信并同步菜单 |

---

## 2. 工作原理

### 2.1 点火触发流程

点火由 `interaction_rules.json` 中的 `ignite` / `ignite_carried` GUI 规则触发：

```
GUI 右键交互
  ├─ 光标活打火石 + 槽位活TNT → IgniteHandler.handle() → 点燃槽位 TNT
  └─ 光标活TNT + 槽位活打火石 → IgniteCarriedHandler.handle() → 点燃光标 TNT
      └─ ExplosionComponent.startFuseOnStack() → 写引信组件 → 同步菜单
```

**约束**：生存模式每次交互扣打火石 1 点耐久，创造模式不扣；已点燃 TNT 不重置引信。
点火不执行活漏斗传输、不触发漏斗冷却。

### 2.2 函数实现

```java
public class LivingFlintAndSteelFunction implements LivingItemFunction {

    public static final String ID = "living_flint_and_steel";

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.FLINT_AND_STEEL) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) return;
        // 空实现 —— 活打火石自身不执行任何 tick 逻辑
    }
}
```

**职责边界**：
- 活打火石只提供活化标记和 Tooltip，无自身运行状态。
- 点火与耐久处理由 GUI 交互处理器承担。

---

## 3. 数据结构

活打火石**不存储任何运行时数据**，没有对应的 Data 类。它唯一的数据是活物品通用标记 `IS_LIVING`。

```
ItemStack
├── IS_LIVING: true                    ← 活物品标记
└── (无额外功能数据)                    ← 无需状态存储
```

---

## 4. Tooltip 显示

### 4.1 显示内容

| 字段 | 说明 |
|------|------|
| 状态标识 | 显示 "活打火石" 状态标题 |

### 4.2 实现

```java
@Override
public void addToTooltip(Item.TooltipContext context,
                         Consumer<Component> tooltipAdder,
                         TooltipFlag flag,
                         ItemStack stack) {
    tooltipAdder.accept(Component.nullToEmpty(""));
    tooltipAdder.accept(Component.translatable("tooltip.livingitem.flint_and_steel.status"));
}
```

---

## 附录：与活 TNT 的协作

活打火石与活TNT通过 GUI 双向右键交互配合，无活漏斗要求：

```
光标活打火石 → 右键槽位活TNT → 点燃槽位 TNT
光标活TNT → 右键槽位活打火石 → 点燃光标 TNT
→ 生存模式扣打火石 1 点耐久；创造模式不扣
→ 引信从 80 ticks 开始倒计时
→ 有可用锚点时爆炸；无锚点不爆、不消耗 TNT，引信归零时熄灭
```

---

## 附录：v2 变更记录 (2026-07-28)

> **历史记录（冻结）**：本节保留当时表述，不作为现状；当前 GUI 点火与耐久约束见 §2.1。

**变更1：Tooltip 国际化**

Tooltip 显示从硬编码字符串改为使用 `Component.translatable()` 国际化键，支持多语言。

**变更2：点火流程描述修正**

补充说明活打火石在点火流程中的"标记"角色——不消耗耐久、不被消耗，仅作为活漏斗传输系统的信号源。

---

## 附录：验证清单

> 重构或架构迁移后，必须逐项验证以下用例。

### 基础点火

- [ ] 光标活打火石右键槽位活TNT，点燃槽位 TNT
- [ ] 光标活TNT右键槽位活打火石，点燃光标 TNT
- [ ] 点火后活TNT开始倒计时；已点燃不重置引信
- [ ] 生存模式每次交互扣打火石 1 点耐久，创造模式不扣

### 标记角色

- [ ] 点火不要求活漏斗、槽位邻接或方向配置
- [ ] GUI 点火不执行物品传输，不触发活漏斗冷却
- [ ] 活打火石自身 tick 不执行点火或耐久消耗

### Tooltip 与同步

- [ ] Tooltip 显示活打火石状态
- [ ] Tooltip 国际化（Component.translatable）

### 数据持久化

- [ ] 活打火石无额外持久化数据（纯标记角色）
- [ ] 物品离开容器后无状态残留