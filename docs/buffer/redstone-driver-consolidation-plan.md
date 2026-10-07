# 红石重算收归单点（redstone driver consolidation）

*创建: 2026-10-08 · 状态: **提案（未实现）** —— 本文只描述「改完长什么样」，**未动任何代码**。拍板后按 §5 实施。*

> ⚠️ **本文件是设计稿（未实现）**。文中「改后」段落描述的是**目标状态**，不是现状。
> 现状请以 `src/` 为准。

---

## 0. 一句话

红石层的「重算」现在被 **10 个类各写一遍**（9 个红石元件 + 活 TNT），运行时靠一个幂等标志
兜底才没出错。方案是把驱动权**收归一处**（活红石粉），其余 9 处删干净 ——
照抄流体侧（1b-2b）已经做完的同一件事。

---

## 1. 现状：10 处逐字相同的样板

| # | 类 | 所在包 | `getPriority()` | `tickContainerData` 内容 |
|---|---|---|---|---|
| 1 | `LivingRedstoneFunction`（活红石粉） | `domain/redstone` | 2 | `getOrCreateRedstoneData` + `calculate` |
| 2 | `LivingButtonFunction` | `domain/redstone` | 2 | 同上 |
| 3 | `LivingLeverFunction` | `domain/redstone` | 2 | 同上 |
| 4 | `LivingRepeaterFunction` | `domain/redstone` | 2 | 同上 |
| 5 | `LivingComparatorFunction` | `domain/redstone` | 2 | 同上 |
| 6 | `LivingRedstoneTorchFunction` | `domain/redstone` | 2 | 同上 |
| 7 | `LivingRedstoneLampFunction` | `domain/redstone` | 2 | 同上 |
| 8 | `LivingRedstoneBlockFunction` | `domain/redstone` | 2 | 同上 |
| 9 | `LivingCopperFunction`（涂蜡铜块感知） | `domain/redstone` | 2 | 同上 |
| 10 | `LivingTntFunction` | `domain/tnt` | **1** | 同上（**跨域抄样板**） |

**对照 —— 真正拥有容器级数据的另外 3 个**（`HasContainerData` 全库共 13 个实现者）：

| 类 | prio | `tickContainerData` |
|---|---|---|
| `LivingFluidFunction` | 0 | **真逻辑**：驱动流体 BFS + flush 快照 |
| `LivingWaterWheelFunction` | 1 | **真逻辑**：读流体算应力 + postTickSync |
| `LivingWaxedCopperFunction` | 3 | **真逻辑**：发电记账 + 共振 EMA |

⇒ 13 个实现者里，**10 个是同一段样板**，只有 3 个名副其实。

---

## 2. 为什么现在「没坏」

`ContainerRedstoneData.calculate` 开头有幂等短路：

```java
public void calculate(ContainerContext context, TickContext tick) {
    if (processedThisTick) return;     // ← 第 2~10 个调用者到这里直接返回
    processedThisTick = true;
    ...
}
```

`processedThisTick` 由 `SimpleContainerContext.setTickContext()` 每 tick 重置一次
⇒ **每 tick 恰好算一次，谁先调谁赢**。

⇒ 所以：**功能是对的。代价不在运行时，在别处。**

---

## 3. 真正的代价：错误样板的产地

### ① 活 TNT 就是抄来的，且注释从诞生起就是错的

2026-08-22 加「TNT 红石点燃」时，`LivingTntFunction` 照抄了周围红石元件的写法，
**连注释一起抄**：

> `getPriority()` 返回 1，确保红石数据在 TNT tick 之前计算完毕

⚠️ **这句话不成立**。`ContainerLivingItemHandler.processContext` 的实际顺序是：

```
阶段 2：runFunctionTicks(...)        // 所有 Function.tick()，不分 prio
阶段 4：runContainerDataTicks(...)   // 才轮到 tickContainerData，按 prio 排序
```

⇒ tnt 的 `tickContainerData`（prio 1）跑在**它自己的 `tick()` 之后**
⇒ tnt 读到的永远是**上一 tick** 的 edgeGrid。所谓「确保在 TNT tick 之前」不成立。
这条误解**至今留在代码注释里**。

### ② 项目当时就承认了这是冗余

修「活中继器不熄灭」时，`changelog.md` 明确记录：

> `processedThisTick` 标志的作用是**防止多个 `HasContainerData` 函数在同一 tick 重复调用
> `calculate()`**，而非区分不同红石类型的处理顺序。**因此调整各功能 `getPriority()` 的方案并不对症**

⇒ 「多个函数重复调用」是**已知既有事实**，只是用标志兜住了，**没根治**。

### ③ 这就是 AI 乱写代码的机制

新 AI（或人）要加一个红石元件时，打开 `domain/redstone/` 看到的示范是：
**7 个类都实现了 `HasContainerData` + 一段 `tickContainerData`**。
它的合理推断是「新元件也得这么写」—— 于是一个**本来不需要**的样板被复制第 8 次。

这正对应「四道防线」里的第 ① 条：**「写不出来」比「写了就红」更可靠** ——
样板在，就一定会被抄。删掉样板 = 提高门槛。

---

## 4. 为什么现在能修：流体侧已经把路走通了

一模一样的病，流体侧在 1b-2b 已经治过：

| | 流体侧（已修） | 红石侧（本方案） |
|---|---|---|
| 病的形态 | 容器级流体 tick 挂在**活水桶**上 ⇒ 桶不在场，流体瘫痪 | 容器级红石重算散在**9 个元件 + TNT** 上 |
| 治法 | 驱动权收归 `LivingFluidFunction`（`shouldTickWithoutOwnItems = true`，prio 0） | 驱动权收归 `LivingRedstoneFunction`（**同机制**，prio 2） |
| 结果 | 纯源容器（一个活物品都没有）也能推进 | 容器里没有活红石粉也能算红石 |

**机制已经就位，不需要发明任何新东西：**

- `LivingItemFunction.shouldTickWithoutOwnItems(ctx)` —— 自维持声明；注册期构建静态清单，
  `processContext` 每 tick 只遍历这张清单（性能约定写在接口 javadoc 里）
- `LivingFluidFunction` 是现成范本（`canApply` 恒 false + 自维持 + `tick()` no-op）
- `LivingRedstoneFunction` 的 `tick()` **本来就是空实现** ⇒ 天生适配这个模式

---

## 5. 改完长什么样（逐项）

### 改动 1｜`ContainerRedstoneData`：抽出元件清单谓词（纯重构）

- **现在**：`calculate` 内部硬编码 9 个 `getFunctionSlots(...)` 查询 + `hasAny` 判定
- **改后**：抽成 `public static boolean hasRedstoneElements(TickContext tick)`，`calculate` 调它
- **行为变化**：零。只是把「本容器有没有红石元件」变成**可复用的公开判据**

### 改动 2｜`LivingRedstoneFunction`：成为唯一驱动

```java
/** 自维持：红石层驱动权收归本类（对称流体侧 LivingFluidFunction）。 */
@Override
public boolean shouldTickWithoutOwnItems(ContainerContext ctx) { return true; }

@Override
public int getPriority() { return 2; }   // 不变

@Override
public void tickContainerData(List<SlotEntry> entries, ContainerContext ctx, TickContext tick) {
    // 廉价守卫：容器既没有红石元件、也没有历史账本 ⇒ 与红石无关，零开销跳过。
    if (ctx.peekContainerData(ContainerDataKeys.REDSTONE) == null
            && !ContainerRedstoneData.hasRedstoneElements(tick)) {
        return;
    }
    tick.getOrCreateRedstoneData(ctx).calculate(ctx, tick);
}
```

⚠️ **守卫必须保留**。否则「自维持」= **每个被 tick 的容器**（哪怕纯活熔炉容器）
都会创建 `ContainerRedstoneData` 并每 tick 跑一次 `calculate`
（其中含 `notifyBoundaryChange` ⇒ `level.updateNeighborsAt`，代价不可忽略）。

### 改动 3｜删掉 9 处样板

- `domain/redstone/`：`LivingButtonFunction` / `LivingLeverFunction` / `LivingRepeaterFunction` /
  `LivingComparatorFunction` / `LivingRedstoneTorchFunction` / `LivingRedstoneLampFunction` /
  `LivingRedstoneBlockFunction` / `LivingCopperFunction`
- `domain/tnt/`：`LivingTntFunction`
- 每个类删：`tickContainerData` 方法、`getPriority()`、`implements HasContainerData`，
  以及随之无用的 import（`HasContainerData` / `ContainerRedstoneData` / 可能 `TickContext`）
- ⚠️ `HasDirection`（`getPriority()` 是 `HasContainerData` 的，别误删 `HasDirection` 相关）

⇒ **`HasContainerData` 的实现者从 13 个 → 3 个**。接口语义回归本义：
**「谁真正拥有容器级数据」**，而不是「谁想蹭一下红石」。

### 改动 4｜框架层：删 `zeroResidualRedstone`

- **现在**（`ContainerLivingItemHandler` 行 585-594）：容器有 `REDSTONE` 数据、但 grouped 里
  **没有** `LivingRedstoneFunction` ⇒ 主动跑一次 `calculate` 归零
- **改后**：`LivingRedstoneFunction` 自维持 ⇒ grouped 恒含它 ⇒ 该方法的守卫
  `if (f instanceof LivingRedstoneFunction) return;` **恒真** ⇒ 方法体永不执行 ⇒ 删
- 归零职责由**改动 2 的守卫分支**接管：`peek != null` ⇒ 跑 `calculate` ⇒
  `hasAny == false` ⇒ `edgeGrid.zero()`

### 改动 5｜`handleEmptyContainer` 成死代码（**待拍板**）

自维持使 `grouped` 恒非空 ⇒ `processContext` 的 `if (grouped.isEmpty())` 恒 false
⇒ `handleEmptyContainer` **永不执行**。

⚠️ 这一点 `changelog.md` 早已记录（1b-2c：*「自维持驱动使 `grouped` 恒非空 ⇒ 原
`handleEmptyContainer` 分支永不执行」*）—— 也就是说，**它从 1b-2b 起就已经是死代码**。
本方案只是顺手发现。

⇒ 建议一并删，但这是「顺手多做」，**列为可选项**由用户决定。

---

## 6. 为什么不能只删域侧（「只做改动 3」不成立）

最直觉的改法是「只删样板，别动框架」。**这会导致功能坏掉：**

容器里只有**活按钮**、没有活红石粉时 —— `LivingRedstoneFunction` 不在 grouped
⇒ **没人调 `calculate`**。此时唯一可能兜底的是 `zeroResidualRedstone`，但它：

- 要求 `peekContainerData(REDSTONE) != null`（**只读不创建**）⇒ **首拍必定漏**
- 只在「本 tick 没有 `LivingRedstoneFunction`」时跑 —— 这个条件倒是满足

⇒ 结果是：按钮**首拍不工作**，之后靠残留账本勉强兜住 —— **行为不确定**。

⇒ **必须**有一个「无红石粉也跑」的驱动点。而把它放在框架层（`runContainerDataTicks`
**之前**）会打乱优先级（红石将早于 prio 0 的流体 BFS）。

⇒ **唯一自洽的落点就是 `LivingRedstoneFunction` 自维持。**

---

## 7. 代价与风险

| 项 | 评估 |
|---|---|
| **执行顺序** | **不变**。红石仍在 `runContainerDataTicks` 的 prio 2 位置（`LivingRedstoneFunction` 保留 `HasContainerData`）⇒ 流体(0) → 水车(1) → 红石(2) → 电力(3) |
| **新增开销** | 每个 tick 的容器多一次「`peek` + 最多 9 次 `Set.isEmpty()`」。**无红石容器在守卫处 return ⇒ 不创建数据、不跑 `calculate`** ⇒ 实质零开销 |
| **有红石容器的开销** | **与现在完全相同**（都是每 tick 一次 `calculate`） |
| **TNT 首拍** | **等价**。现在首拍由 tnt 的 `tickContainerData` 创建账本并算（此时 `hasHistory=false`，边检测被跳过 ⇒ 读不到信号）；改后首拍 `peek == null` 且无红石元件 ⇒ 跳过（同样读不到信号），第二拍起由 `LivingRedstoneFunction` 正常算 |
| **测试** | 9 个类的 `tickContainerData` **没有任何测试调用**（`ComponentOwnershipTest` 只 `new` 它们做组件归属校验）⇒ 删除安全 |
| **幂等标志** | **保留** `processedThisTick` 与 `resetProcessedFlag()`。`ContainerRedstoneDataTest.calculate_isIdempotentWithinSameTick` 依赖它；且它是「未来若又出现第二个调用者」的防御 |
| **跨域依赖** | 本方案**不新增** R3（领域互依赖）：守卫判据全在红石包内 + 框架的 `ContainerDataKeys`（域→框架是正向依赖） |

---

## 8. 验证方式

- **全量单测**（534 例 / 57 个测试类，口径见 `tools/doc_check.py`）
  - `ContainerRedstoneDataTest` 直接驱动 `calculate` ⇒ 不受影响
  - power 系列测试驱动的是 `LivingWaxedCopperFunction.tickContainerData`（prio 3，**不动**）
- **手动清单**（红石层**没有**「容器内元件互联」的集成测试，只能手测）：
  1. 活红石粉 + 活按钮 / 拉杆 / 中继器 / 比较器 / 火把 / 灯 / 红石块 各自功能
  2. 与**外部世界**红石双向互通（箱子边上的红石线）
  3. 活 TNT 被红石信号点燃
  4. 电力层发电（涂蜡铜块 + 红电）
  5. **纯活熔炉容器**（无任何红石）—— 确认 TPS 无变化（验证守卫生效）

---

## 9. 文档同步点（实施时必须改）

| 文档 | 位置 | 改什么 |
|---|---|---|
| `docs/tech/living-redstone-tech.md` | 驱动权 / prio 小节 | 驱动权收归单点 + 新守卫 |
| `docs/tech/living-tnt-tech.md` | `getPriority()` 段 | 删 prio 1 段落 → 改为「TNT 是红石**消费者**，不驱动」+ 修正那条错注释 |
| `docs/tech/living-power-tech.md` | 行 68 / 78 | prio 2 的表述（时机不变，但不再是元件驱动） |
| `docs/system-design/红电系统.md` | 行 439-442 架构图 | 去掉 7 个元件的 prio 2 标注 |
| `docs/system-design/living-item-infrastructure.md` | 行 1353 | 同上 |
| `docs/system-design/tooltip-system.md` | 行 105 | 同上 |
| `docs/system-design/power-invariants.md` | 若有 prio 断言 | 核对 |
| `docs/tech/living-copper-tech.md` | prio 2 相关 | 核对 |
| `AGENTS.md` | 架构图 `LivingRedstoneFunction(prio 2)` 标注 + 开发进展一行 | 同步 |
| `docs/reference/subsystem-index.md` | 红石子系统行 | 概述同步 |
| `docs/archive/changelog.md` | 新增一条 | 结论 + 指针 |

---

## 10. 待拍板

1. **是否一并删 `handleEmptyContainer`**（改动 5，死代码 —— 且从 1b-2b 起就已死）
2. **是否一并删 `zeroResidualRedstone`**（改动 4 —— 不删会留下「永不执行的方法」，比删更糟；
   建议删，但按「只做要求的事」原则列出）
3. **是否收窄 `TickContext.getOrCreateRedstoneData`** —— ⚠️ **建议不做**：
   删掉 9 处后它只剩 2 个调用者（`LivingRedstoneFunction` + `TickContext.getSensor`），
   但两者都在不同包，做不到包私有；真要收窄需要「容器级数据生产者声明」这类**新机制**
   （属 B 类框架演进），**不应塞进本次**。
