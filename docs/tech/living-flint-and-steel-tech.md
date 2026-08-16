# Living Flint and Steel (活打火石) 技术文档

> **文档版本**: 2026.08 v3  
> **最后更新**: 2026-08-16  
> **适用版本**: Minecraft 1.21.1

## 目录
1. [架构概览](#1-架构概览)
2. [工作原理](#2-工作原理)
3. [数据结构](#3-数据结构)
4. [Tooltip 显示](#4-tooltip-显示)

---

## 1. 架构概览

### 1.1 什么是活打火石？

活打火石是一种**触发类活物品**，自身不执行任何 tick 逻辑，仅作为"点火器"角色存在。它的核心作用是：当活打火石的 source 方向指向活 TNT 时，点燃活 TNT 的引信。

活打火石的宿主物品是 `minecraft:flint_and_steel`（打火石），必须同时具备活物品标记。

### 1.2 设计理念

活打火石采用**极简设计**——它自己不干活，只提供"我是打火石"的标记。实际的点燃逻辑由**活漏斗的传输系统**处理：

```
活漏斗                   活打火石(我)              活TNT
┌──────┐              ┌──────────┐            ┌──────┐
│source│──────────────│  (slot)  │────────────│target│
│      │  source指向我 │          │  target指向 │      │
└──────┘              └──────────┘            └──────┘
```

当活漏斗的 source 是活打火石、target 是活 TNT 时，传输系统会检测到这种组合并触发点火操作。

### 1.3 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingFlintAndSteelFunction` | `domain/tnt/LivingFlintAndSteelFunction.java` | 活打火石功能入口，空 tick，仅提供 Tooltip |
| `LivingHopperFunction` | `domain/hopper/LivingHopperFunction.java` | 活漏斗传输引擎，在 `TransferPipeline.execute()` 中检测活打火石→活TNT 组合并触发点火 |

---

## 2. 工作原理

### 2.1 点火触发流程

点火由活漏斗的传输系统在 `TransferPipeline.execute()` 中检测。当活漏斗的 source 槽位是活打火石、target 槽位是活 TNT 时，传输系统不执行物品传输，而是触发点火操作：

```
TransferPipeline.execute()
  │
  ├─ source 是活打火石 + target 是活 TNT
  │   └─ LivingTntFunction.startFuse(tntStack)  ← 点燃引信
  │       └─ return true（视为传输成功，触发冷却）
  │
  └─ 其他情况 → 正常传输流程
```

**关键点**：活打火石本身不消耗耐久，也不被消耗。它只是一个"标记"角色，告诉活漏斗传输系统"这是一个点火信号"。

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

**极简设计的原因**：
- 活打火石只是"标记"，不需要状态管理
- 点火逻辑由活漏斗的传输引擎统一处理
- 避免了在多个地方重复实现点火判断

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

活打火石必须与活漏斗和活 TNT 配合使用才能发挥作用：

```
容器布局示例：
┌───────┬───────┬───────┐
│       │       │       │
├───────┼───────┼───────┤
│活打火石│活漏斗  │活TNT  │
│(source)│(slot) │(target)│
└───────┴───────┴───────┘

活漏斗方向：source=左, target=右
→ 活漏斗检测到 source=活打火石, target=活TNT
→ 调用 LivingTntFunction.startFuse() 点燃 TNT
→ 80 ticks 后 TNT 爆炸
```

---

## 附录：v2 变更记录 (2026-07-28)

**变更1：Tooltip 国际化**

Tooltip 显示从硬编码字符串改为使用 `Component.translatable()` 国际化键，支持多语言。

**变更2：点火流程描述修正**

补充说明活打火石在点火流程中的"标记"角色——不消耗耐久、不被消耗，仅作为活漏斗传输系统的信号源。

---

## 附录：验证清单

> 重构或架构迁移后，必须逐项验证以下用例。

### 基础点火

- [ ] 活打火石作为活漏斗的 source 时，活漏斗识别为点火信号
- [ ] 活漏斗 target 为活TNT时，触发点火流程
- [ ] 点火后活TNT开始倒计时
- [ ] 活打火石不消耗耐久、不被消耗

### 标记角色

- [ ] 活打火石仅作为信号源，不参与实际物品传输
- [ ] 活打火石在容器中持续存在，不被移走
- [ ] 多个活打火石不影响点火逻辑（一个信号即可）

### Tooltip 与同步

- [ ] Tooltip 显示活打火石状态
- [ ] Tooltip 国际化（Component.translatable）

### 数据持久化

- [ ] 活打火石无额外持久化数据（纯标记角色）
- [ ] 物品离开容器后无状态残留