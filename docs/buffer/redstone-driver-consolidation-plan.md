# 红石重算收归单点（redstone driver consolidation）

*创建: 2026-10-08 · 状态: **提案（未实现）** —— 本文只描述「改完长什么样」，**未动任何代码**。拍板后按 §5 实施。*

> ⚠️ **本文件是设计稿（未实现）**。文中「改后」段落描述的是**目标状态**，不是现状。
> 现状请以 `src/` 为准。

---

## 目录

- **0–3 为什么改**：一句话 · 现状 10 处样板 · 为什么现在没坏 · 真正的代价（错误样板产地）
- **4–8 怎么改**：流体侧先例 · 逐项改动 · 不能只删域侧 · 代价与风险 · 验证方式
- **9–12 周边**：与演进路线图的关系 · 文档同步点 · 待拍板 · **终点形态（本次不做）**

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

**对照 —— 另外 3 个 `HasContainerData` 实现者**（**全库共 13 个**，其中 10 个就是上表）：

| 类 | prio | `tickContainerData` |
|---|---|---|
| `LivingFluidFunction` | 0 | **真逻辑**：驱动流体 BFS + flush 快照 |
| `LivingWaterWheelFunction` | 1 | **真逻辑**：读流体算应力 + postTickSync |
| `LivingWaxedCopperFunction` | 3 | **真逻辑**：发电记账 + 共振 EMA |

⇒ **13 = 10 个样板 + 3 个真逻辑**。删掉 9 处后剩 **4 个**
（1 个唯一驱动 `LivingRedstoneFunction` + 上面 3 个真逻辑）。

### 1.1 红石层的「消费者」是 3 个，不是 1 个

⚠️ **这一点决定了方案的影响面**（初稿曾误记为"只有 TNT"，复核时更正）。
`RedstoneSensor` 端口的消费者（见 `docs/buffer/redstone-evolution-roadmap.md` §1）：

| 消费者 | 读取方式 | 是否驱动 `calculate` |
|---|---|---|
| `LivingTntFunction` | `getSensor(ctx).maxSensedSignal(slot)`（`tick()` 内） | ✗（**现在靠抄来的样板**驱动） |
| `LivingHopperFunction` | `getSensor(ctx).maxSensedSignal(slot)`（`tick()` 内） | ✗（**从不驱动**） |
| `LivingWaxedCopperFunction` | `getSensor(ctx)`（`tickContainerData` prio 3） | ✗（**从不驱动**，只依赖 prio 2 已算完） |

⇒ **活漏斗和电力层从来不驱动红石重算** —— 它们依赖「别处有人算」。
这是「驱动权分散」的第二个证据：**驱动者与消费者完全错位**（驱动的是 9 个元件，消费的是另外 3 个）。

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

**而且 prio 1 本身毫无作用**：prio 只决定 `runContainerDataTicks` 内部的顺序，
而 tnt 是**唯一** prio 1 的红石调用者，其余 9 个都是 prio 2 —— 加上幂等短路，
「谁先调」对结果没有任何影响。**这行 prio 和它的注释一样，是抄来的装饰品。**

⚠️ 这条错注释**同时污染了两份文档**：`living-tnt-tech.md` §3.3 原样写着
「*优先级 1：在红石数据计算（优先级 2）之前执行*」。
（准确说法：**prio 1 的 `tickContainerData` 确实在 prio 2 之前跑**，
但 `tick()` 在阶段 2、`tickContainerData` 在阶段 4 ⇒ 这个"之前"对 TNT 的读取时机毫无帮助。）

**另外**：tnt 用的是 `maxSensedSignal`，它只读 `edgeGrid`（`sensedSignal` 邻居越界返 0）
⇒ **不含容器外输入**。changelog 里那句「`getSlotSignal` 同时检查 `faceInput`，确保跨容器信号也能点燃 TNT」
描述的是**旧实现** —— `getSlotSignal` 方法**现已不在代码里**（但仍有 3 处文档在引用它，见 §9）。

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

⭐ **守卫为什么不会漏掉消费者**：`TickContext.getSensor(ctx)` **就是**
`getOrCreateRedstoneData(ctx)`（`TickContext.java:117-119`）——
⇒ 三个消费者（TNT / 漏斗 / 电力）在 `tick()` 里**一读就创建了账本**
⇒ 等 `runContainerDataTicks` 跑到 prio 2 时，守卫的 `peek != null` **必然成立**
⇒ 该跑的一定跑。守卫只拦住「**连账本都没有、也没有元件**」的容器（纯熔炉/纯箱子）——
那些容器本来就与红石无关。

⇒ 守卫判据 `peek == null && !hasRedstoneElements` 的**真实语义**是
「**这个容器从来与红石无关**」，而不是「有没有人需要红石」。

⚠️ **但这依赖 `getSensor` 有创建副作用 —— 这是一个残留脆弱点**：
若有人把 `getSensor` 改成「只读不创建」（**语义上更干净**），漏斗容器将无人创建账本
⇒ 守卫拦住 ⇒ **漏斗锁定静默失灵**。
本次**不解决**（要解决需引入「消费者声明」这类新机制，而把 tnt/hopper/power 的 ID 写进红石包
会新增 R3 领域互依赖）。
**但必须把这个隐式契约写下来**：在 `getSensor` 的 javadoc 里显式声明
「**本方法有创建副作用，红石层守卫依赖它**」。

### 改动 3｜删掉 9 处样板

- `domain/redstone/`：`LivingButtonFunction` / `LivingLeverFunction` / `LivingRepeaterFunction` /
  `LivingComparatorFunction` / `LivingRedstoneTorchFunction` / `LivingRedstoneLampFunction` /
  `LivingRedstoneBlockFunction` / `LivingCopperFunction`
- `domain/tnt/`：`LivingTntFunction`
- 每个类删：`tickContainerData` 方法、`getPriority()`、`implements HasContainerData`，
  以及随之无用的 import（`HasContainerData` / `ContainerRedstoneData` / 可能 `TickContext`）
- ⚠️ `HasDirection`（`getPriority()` 是 `HasContainerData` 的，别误删 `HasDirection` 相关）

⇒ 删除 **9 个**实现者（8 个元件 + TNT）⇒ **`HasContainerData` 从 13 个 → 4 个**
（1 个唯一驱动 + 3 个真逻辑）。接口语义回归本义：
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

### 6.1 「错位」的真实代价：更合理的写法会弄坏功能

「驱动者与消费者错位」不只是抽象的正确性担忧 —— 它让**看起来更合理的改动**变成事故：

| 如果有人这样「优化」 | 理由（听起来都对） | 后果 |
|---|---|---|
| 删掉 `zeroResidualRedstone` | 「红石元件自己会算，这个方法冗余」 | **漏斗锁定当场失灵** |
| 把 `getSensor` 改成只读不创建 | 「读操作不该有副作用」——**语义上更干净** | **漏斗锁定当场失灵** |
| 挪动 `resetProcessedFlag` 的位置 | 整理 `setTickContext` | 全盘失效（**历史上真发生过**，见 changelog「中继器不熄灭」） |

**判据：把「让它更合理」当成优化去做，会不会弄坏功能。**
这里会 —— 因为「**漏斗 / TNT / 电力需要红石**」这件事**在代码里没有任何地方被声明**，
它只存在于「三处巧合恰好同时成立」之中：

```
漏斗读信号 → 顺手创建了账本 → 「残留归零」方法恰好条件成立 → 去重标志恰好每轮重置
```

其中「残留归零」方法的**本意是清掉旧信号**（注释写的就是这个），给漏斗供数据纯属副产品。
⇒ 这是**职责错位**：一个叫「归零」的方法在承担「供数据」的职责。

**收归单点后**，这条链变成一处显式声明（`shouldTickWithoutOwnItems = true`），
且判据可被**单测直接断言**，不再依赖巧合。

---

## 7. 代价与风险

| 项 | 评估 |
|---|---|
| **执行顺序** | **不变**。红石仍在 `runContainerDataTicks` 的 prio 2 位置（`LivingRedstoneFunction` 保留 `HasContainerData`）⇒ 流体(0) → 水车(1) → 红石(2) → 电力(3) |
| **新增开销** | 每个 tick 的容器多一次「`peek` + 最多 9 次 `Set.isEmpty()`」。**无红石容器在守卫处 return ⇒ 不创建数据、不跑 `calculate`** ⇒ 实质零开销 |
| **有红石容器的开销** | **与现在完全相同**（都是每 tick 一次 `calculate`） |
| **TNT 首拍** | **等价**（连首拍都不差）。首拍阶段 2 `tnt.tick()` 已调 `getOrCreateRedstoneData` 创建账本 ⇒ 阶段 4 守卫 `peek != null` 通过 ⇒ `LivingRedstoneFunction` 照常算。差别仅在于「谁在阶段 4 第一个调 `calculate`」——而幂等短路使这个差别无意义 |
| **电力层不变量** | **不破坏**。`living-power-tech.md` / `红电系统.md` 的硬约束是「电力 prio **必须 > 2**，依赖红石已算完的 `edgeGrid`」⇒ 本方案**保持红石在 prio 2**，电力仍在 prio 3 读到本 tick 的新值 ✓ |
| **漏斗** | **行为不变**。漏斗只读不驱动；现在由 `zeroResidualRedstone` 兜底算，改后由守卫分支算 —— 两者都在漏斗 `tick()` **之后**（阶段 4）⇒ 读取时机不变 |
| **对外输出（世界）** | **不破坏**。容器朝外的信号（`faceOutput` → `BlockStateBaseMixin` 注入的 `getSignal` → 世界的红石线）依赖 `calculate` 被跑；本方案保证「**有元件 ∨ 账本存在**就必算」⇒ 输出链路不变。⚠️ 若把判据窄化成「只看容器内消费者声明」，会**漏掉这条链路**（详见 §12.2） |
| **测试** | 9 个类的 `tickContainerData` **没有任何测试调用**（`ComponentOwnershipTest` 只 `new` 它们做组件归属校验）⇒ 删除安全 |
| **幂等标志** | **保留** `processedThisTick` 与 `resetProcessedFlag()`。`ContainerRedstoneDataTest.calculate_isIdempotentWithinSameTick` 依赖它；且它是「未来若又出现第二个调用者」的防御 |
| **跨域依赖** | 本方案**不新增** R3（领域互依赖）：守卫判据全在红石包内 + 框架的 `ContainerDataKeys`（域→框架是正向依赖） |

---

## 8. 验证方式

- **全量单测**（534 例 / 57 个测试类，口径见 `tools/doc_check.py`）
  - `ContainerRedstoneDataTest` 直接驱动 `calculate` ⇒ 不受影响
  - power 系列测试驱动的是 `LivingWaxedCopperFunction.tickContainerData`（prio 3，**不动**）
- **手动清单**（红石层**没有**「容器内元件互联」的集成测试，只能手测）
  —— **务必覆盖 3 个消费者，它们都不驱动重算，最容易成为盲区**：
  1. 活红石粉 + 活按钮 / 拉杆 / 中继器 / 比较器 / 火把 / 灯 / 红石块 各自功能
  2. 与**外部世界**红石双向互通（箱子边上的红石线）
  3. **活 TNT** 被红石信号点燃（消费者 ①）
  4. **活漏斗被红石锁定**（消费者 ②）—— 单独放一个漏斗 + 红石块，确认"锁定/解除"正常
  5. **电力层发电**（消费者 ③，涂蜡铜块 + 红电）
  6. **纯活熔炉容器**（无任何红石、无消费者）—— 确认 TPS 无变化（验证守卫生效）
  7. **残留归零**：容器里放活红石块 → 拿走 → 确认箱子边上世界的红石线熄灭（验证 `zeroResidualRedstone` 的职责确实被接管）

---

## 9. 与 `redstone-evolution-roadmap.md` 的关系（复核时补）

红电已有一条**在册的演进路线**（`docs/buffer/redstone-evolution-roadmap.md`，三个方向）：

| 方向 | 状态 | 与本方案的关系 |
|---|---|---|
| **② SensorPort 感知端口** | **已完成**（2026-10-08 C 收尾，接口上移 `living/api/`） | **本方案的上游**。端口切断了「消费者 → 信号层实现」的依赖，但**没有动「谁驱动重算」**——本方案补的正是这半边 |
| ① 跳变检测前移到写入点（事件流） | 规划中（等电力层新功能） | **不冲突**。事件流要收口的是 `edgeGrid.set` 的写入口径，本方案不碰 phase 内部 |
| ③ 信号层元件接口化 | 规划中（等铜门立项） | **不冲突，且本方案降低它的风险**：接口化时要搬 phase1-5 的分支，若此时还有 10 处驱动样板，搬迁面会更大 |

⭐ **本方案是该路线图 §5「隐式契约的显式化」的又一次应用** ——
它要显式化的契约是「**谁负责驱动容器级数据重算**」。
目前这个契约是隐式的：靠 10 处复制 + 一个去重标志**涌现**出来，而不是被任何签名声明。
收归单点后，它变成 `shouldTickWithoutOwnItems` 的一行显式声明。

---

## 10. 文档同步点（实施时必须改）

### 10.1 本方案引起的改动

| 文档 | 位置 | 改什么 |
|---|---|---|
| `docs/tech/living-redstone-tech.md` | §3.1 触发时机 / §4.4 火把独立存在 / §8.1 / §8.2 / §8.4 | 驱动权收归单点 + 新守卫 + 删「火把自触发」说明 |
| `docs/tech/living-tnt-tech.md` | §3.3 红石信号点火 | 删 prio 1 段 → 「TNT 是红石**消费者**，不驱动」+ 修正错注释 |
| `docs/tech/living-power-tech.md` | §1.3 调度与数据流（行 68 / 78） | prio 2 的表述（时机不变，但不再是元件驱动） |
| `docs/tech/living-copper-tech.md` | 行 94 / 225 | 同上 |
| `docs/system-design/红电系统.md` | 行 439-442 架构图 · 行 1402-1404 prio 说明 | 去掉元件的 prio 2 标注 |
| `docs/system-design/living-item-infrastructure.md` | 行 1353 | 同上 |
| `docs/system-design/tooltip-system.md` | 行 105 | 同上 |
| `AGENTS.md` | 架构图 `LivingRedstoneFunction(prio 2)` 标注 + 开发进展一行 | 同步 |
| `docs/reference/subsystem-index.md` | 红石子系统行 | 概述同步 |
| `docs/archive/changelog.md` | 新增一条 | 结论 + 指针 |

### 10.2 ⚠️ 复核时发现的**既存陈旧**（与本方案无关，但同属红电文档，建议一并修）

这些不是本方案造成的，是**红电文档漂移**。因为本方案要改的就是这些段落，**顺手修正成本最低**：

| # | 位置 | 陈旧内容 | 实际 |
|---|---|---|---|
| 1 | `living-redstone-tech.md:383,385-386,391` | 「每 **2 tick** 触发一次」「传播 tick（偶数 tick）/ 非传播 tick（奇数 tick）直接返回」 | **跳帧已取消**（v17.2 起 1 tick）；同文档 `:395` 自己写了「每 game tick 一次」⇒ **文档自相矛盾** |
| 2 | `living-redstone-tech.md:389` | 「`calculate()` 由 `LivingRedstoneFunction` 和 `LivingRedstoneTorchFunction` 触发」 | **10 处** |
| 3 | `living-redstone-tech.md:1548` | 「所有红石功能类（红石粉、火把、中继器、比较器、按钮、拉杆、灯）」（7 个） | 8 红石 + 铜 + TNT |
| 4 | `living-redstone-tech.md:1587-1592` | prio 表：`0: LivingWaterBucketFunction` | 0 位是 **`LivingFluidFunction`**（活水源 → 活流体重构后已换）；且表里缺 6 个 prio 2 |
| 5 | `living-redstone-tech.md:169` · `:533` · `living-tnt-tech.md:37,79` | 引用 `getSlotSignal(int, int, int)` | ⚠️ **该方法已不在代码里**（TNT 现用 `maxSensedSignal`）⇒ 文档引用了**不存在的方法**（`doc_check` 第 7 项只校验类名，漏掉方法名） |
| 6 | `红电系统.md:1402-1404` · `living-power-tech.md:78` | 「水桶 0、水车 1、红石 2」 | 0 位同上；「红石与红石火把均为 2」也只提了 2 个 |
| 7 | `living-redstone-tech.md:1606-1617`（§8.4） | 「当 `grouped.isEmpty()`（无任何红石物品）时…直接调用 `calculate()`」 | 该分支**从 1b-2b 起已死**（自维持使 `grouped` 恒非空）；现由 `zeroResidualRedstone` 承担 |

> 第 5 项是**机械检查的盲区**：`doc_check` 第 7 项只断言「文档提到的 `Living*.java` 文件必须存在」，
> 不校验方法名 ⇒ 删掉的方法会永远留在文档里。**若要堵这个洞，需要给 doc_check 加一条「方法名真实性」检查**
> —— 但那属于工具演进，不在本次范围（**列为待拍板第 4 项**）。

---

## 11. 待拍板

1. **是否一并删 `handleEmptyContainer`**（改动 5，死代码 —— 且从 1b-2b 起就已死）
2. **是否一并删 `zeroResidualRedstone`**（改动 4 —— 不删会留下「永不执行的方法」，比删更糟；
   建议删，但按「只做要求的事」原则列出）
3. **是否收窄 `TickContext.getOrCreateRedstoneData`** —— ⚠️ **建议不做**：
   删掉 9 处后它只剩 2 个调用者（`LivingRedstoneFunction` + `TickContext.getSensor`），
   但两者都在不同包，做不到包私有；真要收窄需要「容器级数据生产者声明」这类**新机制**
   （属 B 类框架演进），**不应塞进本次**。
4. **是否顺手修 §10.2 的 7 项既存陈旧**（尤其第 1、5 项 —— 自相矛盾 + 引用不存在的方法）
5. **是否给 `doc_check` 加「方法名真实性」检查**（堵住 §10.2 第 5 项的盲区）

---

## 12. 终点形态（**本次不做**，记录方向）

> 用户问「正常的关系应该是怎样的」。本节是回答，也是本方案的**方向锚点** ——
> 用来判断本次的中间态**走到了哪**，以及**还差什么**。

### 12.1 一句话：驱动者应服务于**需求方**，而不是服务于自己

判据从「**容器里碰巧有没有红石元件**」变成「**容器里有没有人需要红石**」。

| 形态 | 谁驱动 | 「要不要算」的判据 | 关系 |
|---|---|---|---|
| **现在** | 9 个元件（兼职） | 有没有元件在场 | 无（驱动者与消费者不相干） |
| **本次中间态** | 1 个专职 | **有元件 ∨ 账本已存在** | 供给驱动（有人算就行） |
| **终点** | 框架按需 | **有任一声明**（生产者 ∨ 消费者） | **双向声明**（见 §12.2 / §12.4） |

⚠️ **中间态没有消除 §6.1 的脆弱性** —— 判据仍是「有元件 ∨ 账本存在」，
而「账本存在」依赖 `getSensor` 的创建副作用。它只做到：**消除样板** + **让「谁驱动」可见**。

⭐ **但中间态的判据在逻辑上是完备的**（§12.2 证明「有元件在场」不可省），
这也正是 §7 敢说「行为等价」的原因 —— **终点换的是实现方式，不是判据**。

### 12.2 ⚠️ 「需求」的边界：三个来源，「元件在场」不可省

> 用户追问：「**如果没有需求，红电信号还会计算吗**」。
> 会漏 —— 如果「需求」只算容器内的消费者的话。

| # | 需求来源 | 消费者在哪 | 怎么知道有需求 |
|---|---|---|---|
| 1 | 容器内消费者（TNT / 漏斗 / 电力） | 容器内 | **显式声明**「我需要」 |
| 2 | 元件之间传播（粉 / 中继器 / 比较器…） | 容器内 | **元件在场**（它自己就是网络的一部分） |
| 3 | **对外输出** | ⚠️ **容器外的世界** | **元件在场**（它可能在驱动外面的线） |

**第 3 条最容易漏。** 反例：容器里只有一个活红石块，无任何容器内消费者 ——
若判据只认「容器内消费者声明」⇒ 无人声明 ⇒ 不算 ⇒ **活红石块不再对外输出**。

**对外输出的链路（已查证）**：

| 环节 | 实现 |
|---|---|
| 算出朝外各方向的输出值 | `ContainerRedstoneData.computeFaceOutput` → `faceOutput[4]` |
| 通知外面的邻居重算 | `notifyBoundaryChange` → `level.updateNeighborsAt` |
| 邻居来问「这箱子给多少信号」 | `BlockStateBaseMixin` 注入 `BlockStateBase.getSignal` → 返回 `getBoundarySignal(internalDir)` |
| 让红石线认这个箱子「可连接」 | `RedStoneWireBlockMixin` → `RedstoneSide.SIDE` |

⇒ **「有元件在场」这个判据不能删**，它表达的是
「**本容器是信号的生产者 / 中继者，因此外面的世界需要它**」。

⇒ 因此终点的正确表述是**双向声明**（生产者声明 ∨ 消费者声明），**不是「需求驱动」**。
（本文初稿曾写成「箭头反过来 = 需求驱动」—— **该表述不准确，已按本节更正**。）

### 12.3 终点机制：把 `HasContainerData` 拆成两件事

```java
// 现在：一个方法同时表示「我需要数据」和「我来算数据」——10 个冒名者就是这么进来的
public interface HasContainerData {
    int getPriority();
    void tickContainerData(...);
}

// 终点：消费者声明需求，生产者声明提供
public interface NeedsContainerData {
    Set<ContainerDataKey> neededData();       // 「我需要 REDSTONE」
}
public interface ProvidesContainerData {
    ContainerDataKey providedData();          // 「我负责算 REDSTONE」
    void tickContainerData(...);
}
```

| 谁 | 声明 | 对应 §12.2 的来源 |
|---|---|---|
| TNT / 漏斗 / 电力 | `NeedsContainerData` → 我要 `REDSTONE` | 来源 1 |
| 9 个红石元件（粉 / 火把 / 中继器 / 比较器 / 按钮 / 拉杆 / 灯 / 红石块 / 涂蜡铜块） | `ProvidesContainerData` → 我提供 `REDSTONE` | 来源 2 **和** 来源 3 |

⚠️ **注意 `ProvidesContainerData` 一个声明同时覆盖了来源 2 和来源 3** ——
因为「元件在场」既意味着「它自己要传播」，也意味着「它可能对外输出」。
⇒ 这正是「**有元件在场**」这个判据**在逻辑上不可省**的原因。
| 框架 | 汇总需求 → **有需求才驱动；无需求零开销** |

### 12.4 新形态的具体写法（各类对照）

| 类 | 现在 | 新形态 |
|---|---|---|
| `LivingRedstoneFunction`（红石粉） | `HasContainerData` + `prio 2` + 3 行样板 | `ProvidesContainerData` → `REDSTONE` + `NeedsContainerData` → `{REDSTONE}`（它自己也参与传播） |
| 8 个红石元件 | `HasContainerData` + `prio 2` + **同样 3 行样板** | `NeedsContainerData` → `{REDSTONE}` —— **一行** |
| `LivingTntFunction` | `HasContainerData` + `prio 1` + 样板 | `NeedsContainerData` → `{REDSTONE}` —— **一行** |
| `LivingHopperFunction` | ⚠️ **什么都不实现**（需求隐式，靠副作用） | `NeedsContainerData` → `{REDSTONE}` —— **一行** |
| `LivingWaxedCopperFunction`（电力层） | `HasContainerData` + `prio 3` | `ProvidesContainerData` → `POWER`（`dependsOn: REDSTONE`） |
| `LivingFluidFunction`（流体驱动） | `HasContainerData` + `prio 0` | `ProvidesContainerData` → `FLUID` |
| `LivingWaterWheelFunction`（水车） | `HasContainerData` + `prio 1` | `NeedsContainerData` → `{FLUID}` + `ProvidesContainerData` → `STRESS`（`dependsOn: FLUID`） |

**⭐ 本质变化：红石元件从「驱动者」变成「需求者」** —— 这是**纠正了一个身份错误**：

> 红石元件（按钮 / 中继器 / 比较器…）的真实身份是**数据的参与者**（它要读邻居给的信号才能工作），
> 但现在的代码让它们当**数据的主人**（"我来决定要不要算"）。
> 比喻：现在是每个元件都「自己开车」（谁在场谁开），新形态是「一个专职司机 + 所有元件当乘客」。

**提供者在终点形态下连守卫都不需要**（守卫是「把框架的判断塞进提供者」，终点把它还给框架）：

```java
public class LivingRedstoneFunction implements LivingItemFunction, ProvidesContainerData {
    @Override
    public ContainerDataKey providedData() { return ContainerDataKeys.REDSTONE; }

    // 唯一一处 calculate —— 没有守卫
    // 「要不要算」由框架判定（有需求才驱动），不该塞在提供者内部
    @Override
    public void tickContainerData(List<SlotEntry> entries, ContainerContext ctx, TickContext tick) {
        ctx.getOrCreateContainerData(ContainerDataKeys.REDSTONE).calculate(ctx, tick);
    }
}
```

⇒ **「消除样板」是一条三步收敛**：

| | 样板处数 | 硬编码元件清单 | 守卫 |
|---|---|---|---|
| **现在** | 10 处 | 9 个 ID（在 `calculate` 里） | 无 |
| **本次中间态** | **1 处** | 9 个 ID（抽成 `hasRedstoneElements`） | 有（在提供者内部） |
| **终点** | **0 处** | **0**（元件自己声明） | **无**（还给框架） |

⚠️ 本节是**设计推演**，未实施、未验证。「元件从驱动者变需求者」这一重新归类，
实施时需逐个核对语义（尤其 `LivingCopperFunction` 在信号层 vs `LivingWaxedCopperFunction` 在电力层
是两个不同的类，别混）。

### 12.5 四个附带收益（终点顺带解决的）

1. **巧合链彻底消失** —— 生产者和消费者**都显式声明**，不再靠 `getSensor` 的副作用
2. **`getSensor` 可以变回纯读**（无副作用）⇒ §5 标注的残留脆弱点**自然消失**
3. ⭐ **`getPriority()` 这张手工排序表可以整个删掉** —— 「谁先跑」可从「谁需要谁」**推导**出来
   （电力需要红石 ⇒ 红石先跑）。现状是手工维护，且**已经漂移**（文档里的表还是旧的，见 §10.2 第 4 项）
4. ⭐ **「谁是红石元件」从硬编码清单变成自声明** —— 现在这个清单是
   `ContainerRedstoneData.hasRedstoneElements` 里写死的 9 个 ID（本次改动 ① 抽出来的那个）。
   终点后**新增元件只需自己声明**，不用改红石包的清单 ⇒ 这正是「消除样板」的**最终形态**

**同一机制对流体同样适用**：`LivingWaterWheelFunction` 现在是「prio 1 顺手跑一遍」，
本质是「我需要 `FLUID`」⇒ 也该走声明。⇒ 四类容器级数据（流体 / 应力 / 红石 / 电力）可统一到一条机制。

### 12.6 为什么本次不做 + 顺序不能反

1. 它是**新机制**（新接口 + 框架调度改造），不是「消除样板」⇒ 属 B 类框架演进
2. 要同时动 5 个领域（redstone / tnt / hopper / power / water），影响面比本次大一个量级
3. ⚠️ **顺序不能反**：先消除样板（9 → 1），再引入声明（1 个生产者）。
   反过来的话，要在 10 个地方**同时**改声明 —— 改错一个就是**静默失效**（§6.1 那张表）

⇒ **中间态是终点形态的必经台阶，不是替代品。**
