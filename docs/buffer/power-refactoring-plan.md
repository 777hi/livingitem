# 红电包收口方案（power refactoring plan）

*创建: 2026-09-11 · 状态: **进行中** —— 步骤 1 ✅ · **步骤 4 第 1~5 刀 ✅** ·
**步骤 2 第 1~3 步 ✅**（③ 的放电与 ④ 经评估**不做**，理由见 §3，2026-10-09）；
步骤 3（单位值类型）待做*
>
> 📉 **主类行数**：1265 → **476**（步骤 4 第 1~5 刀；Tooltip / 谓词 / 拓扑 / 相位解读 / 记账与遥测）。
> **`ContainerEnergyStorage`**：312 → **210**（步骤 2 第 1 步）。
>
> 📌 已登记到 `AGENTS.md` 子系统索引「红电 · 电力」族（2026-10-09）。

---

## 0. 为什么会有这份文档

2026-09-11 排查「通量网络给潜影箱里的活涂蜡铜灯充电时服务端冻结」，
根因是 `receive()` 里 `accept * remaining[i] / totalRemaining` 的 **long 溢出**
（mFE 定点制下乘积达 1.1e23 ≫ Long.MAX，溢出后分配量≈0，零头回收循环退化成约 10 亿轮）。

修完之后发现：**同一个溢出 bug 在 `LivingWaxedCopperFunction.distributeToBulbs` 里又存在一份**。
不是抄错，而是「按剩余容量比例分配」这个操作**没有单一归属地**。

这份文档记录收口路线，供并行工作结束后按序执行。

## 1. 现状量化

### 1.1 `LivingWaxedCopperFunction` = 1174 行的杂物间

| 段落 | 行号 | 行数 | 是不是这个类的职责 |
|---|---|---|---|
| 接口实现 + `tickContainerData` 编排 | 59–227 | ~169 | ✅ 是 |
| BFS 网络拓扑（`runBfs` / `repOf` / `edgeKey`） | 228–471 | ~244 | ❌ 容器铜块连通性 |
| 相位解读（`phaseInterpretation` / `interpretShifter` / `interpretSplitter` / `interpretAdder`） | 472–624 | ~153 | ❌ 红石相位语义 |
| 能量记账（`accountEnergy`） | 625–721 | ~97 | ❌ 能量层 |
| 遥测 + 分配（`buildTelemetry` / `formatMilliFe` / `distributeToBulbs`） | 722–851 | ~130 | ❌ 展示与分配 |
| **Tooltip 渲染**（`addToTooltip` 单个方法就 205 行） | 852–1138 | **~287** | ❌ 纯客户端展示 |
| 物品判定（`isWaxedBulb` / `getOxidationLevel` / `isWaxed*`） | 1139–1236 | ~98 | ❌ 纯谓词 |

**只有 14% 是「活涂蜡铜块」这个功能本身的逻辑。** 其余 86% 是恰好住在这里的基础设施。

### 1.2 三份「按剩余容量比例分配」，三种口径

| 类 | 场景 | 量化 | 报账 |
|---|---|---|---|
| `BulbItemEnergyStorage.receiveEnergy` | 物品栏单堆 | 无 | `min(toReceive, ceil(实充/1000))` |
| `ContainerEnergyStorage.receive` | 容器多堆充电 | `accept -= accept % 1000`（floor） | 声明值 `accept` |
| `LivingWaxedCopperFunction.distributeToBulbs` | 容器多堆直存 | 无 | `boolean` |

抽取侧同样是两份：`BulbItemEnergyStorage.extractEnergy` 与 `ContainerEnergyStorage.extract`
（后者有「余数跨 FE 边界向上取整」策略，前者没有）。

**口径差异是真实的设计选择**（不同调用方对「宁损勿造」的边界要求不同），
不该被无脑抹平 —— 收口的目标是**把机制收成一处、把差异变成显式参数**，不是消灭差异。

### 1.3 沉积层分布（为什么只有这个包看着乱）

带日期的修复注释：`domain/power` 14 处 / 15 文件，`domain/redstone` 3 / 24，
`domain/hopper`、`chest`、`ender` **均为 0**。日期全挤在 09-08 / 09-09 / 09-11。
即 v17.5→v18→v19 连续迭代的产物，**不是全库性问题**。

## 2. 步骤 1 —— `PowerMath.mulDivFloor`（✅ 已完成 2026-09-11）

溢出安全的 `floor(a × b / c)`，两个调用点已收敛到它，各缩成一行。
`PowerMathTest` 4 例（小量级逐位一致 + 性质扫描 / 非正入参 / 定点量级不溢出 / `a > c` 夹取）。

**已做的关键验证**：把 `mulDivFloor` 退回朴素乘法，恰好挂 3 个测试、横跨 2 个类
（`PowerMathTest` + `WaxedCopperStorageTest` 的两个调用点），证明调用点真的经过原语。

**后续规则**：`domain/power` 里**任何** `a * b / c` 形式的份额/比例计算，一律走
`mulDivFloor`。新增时自检命令：

```bash
grep -rn "[a-zA-Z0-9_)] \* [a-zA-Z0-9_(][a-zA-Z0-9_()]* */ *[a-zA-Z0-9_(]" \
     src/main/java/com/qiqi/li/living/domain/power --include=*.java
```

## 3. 步骤 2 —— 抽 `BulbBank`（收益最高，建议第二个做）

> **进度：第 1+2 步 ✅、第 3 步（充电）✅（2026-10-09，`e23586c` / `3578601` / `e7200bd`）** ——
> 新建 `BulbBank` + 三个调用方改为委托：`ContainerEnergyStorage.receive`（312 → 210 行）、
> `LivingWaxedCopperFunction.distributeToBulbs`（845 → 824 行）、`BulbItemEnergyStorage.receiveEnergy`。
> ⚠️ **已接两种口径**：`FLOOR_WHOLE_FE`（容器对外接口）/ `ANY_MOVEMENT`（发电直存 + 物品接口）。
> **第 3 步的放电、第 4 步（`extract`）经评估决定不做** —— 理由见下方迁移顺序。
> ✅ 破坏性验证各做一次：① 移除整 FE 量化 ⇒ **1 挂**；② `scanEntries` 返回空 ⇒ **5 挂**；
> ③ `of()` 返回空 ⇒ **3 挂**（均证明对应调用点真经过 `BulbBank`）。

把「扫铜灯堆 → 收集 (stack, count, remaining) → 比例分配 → 每盏取整写入 →
零头回收」收成一处。

### 设计草图

```java
/** 一组铜灯堆的统一视图：扫描一次，之后所有分配/抽取走内存数组 */
final class BulbBank {
    static BulbBank scan(IItemHandler items, @Nullable IntPredicate slotFilter);
    static BulbBank of(ItemStack single);                 // 物品栏单堆

    long totalRemaining();                                 // mFE，全满时为 0
    long totalCharge();                                    // mFE

    /** 按剩余容量比例充入。返回「声明收下」的 mFE。 */
    long deposit(long wantMilliFe, boolean simulate, FePolicy policy);

    /** 逐堆等量抽取（含跨 FE 边界的余数策略）。返回实取 mFE。 */
    long withdraw(long wantMilliFe, boolean simulate, FePolicy policy);

    boolean isDirty();                                     // 是否需要 setChanged
}

enum FePolicy {
    /** 整 FE 向下量化（容器对外接口：声明 = accept） */
    FLOOR_WHOLE_FE,
    /** 不量化，报账取 ceil（物品接口：声明 ≥ 实充） */
    CEIL_DECLARED,
    /** 不量化，只关心有没有动（发电直存） */
    ANY_MOVEMENT
}
```

### 迁移顺序（每步独立可验证）

1. `ContainerEnergyStorage.receive` → `BulbBank.scan(...).deposit(..., FLOOR_WHOLE_FE)`
   —— 已有 20 个 `WaxedCopperStorageTest` 用例 + `RoundTripConservationIT` 守着，**先动这里最安全**。
2. `LivingWaxedCopperFunction.distributeToBulbs` → `BulbBank.scanEntries(entries, 锈级过滤).deposit(..., ANY_MOVEMENT)`
   —— ✅ 已做（`3578601`）。锈级专属通道改由 **`Predicate<ItemStack>` itemFilter** 表达
   （原方案写的 `slotFilter` 是 `IntPredicate`，表达不了「按物品判锈级」）；
   返回 `boolean` 改看 `isDirty()`。⚠️ 另发现一处**原方案的过判**：旧代码里那次
   `Math.min(CAP, ...)` 其实**永不生效**（`mulDivFloor` 内部已夹 `a≤c` 且结果夹 `r≤b`
   ⇒ 份额恒 ≤ 该堆剩余）⇒ 去掉后行为等价。
3. `BulbItemEnergyStorage` **充电** → `BulbBank.of(stack).deposit(..., ANY_MOVEMENT)`
   —— ✅ 已做（`e7200bd`）。
   ⚠️ **返回值口径**：本接口返回的是「**实充的 ceil**」（`ceil(perLamp×count/1000)`），
   不是声明值 ⇒ 取 `distributedMilliFe()` 而非 `deposit` 的返回值
   （原方案写的 `CEIL_DECLARED` 描述不准，故**未新增该枚举值**）。
   ⚠️ **`of()` 刻意不过滤 `isBulb`**：该 capability 在 `LivingItem` 里按**原版物品**注册
   （`Items.WAXED_COPPER_BULB` 等 4 个，**含未活化的灯**），而 `setData`/`getData` 无
   `isLivingItem` 守卫 ⇒ 未活化灯**本来就能被充**；套 `isBulb` 会静默改变该行为。
   「仅已活化」是**容器路径**（`scan`）的判据 —— 两条路径口径本就不同（见 §3 下方「遗留观察」）。
   ⚠️ **放电（`extractEnergy`）不收**：它是**单堆、无循环**，口径也独特
   （**无**余数跨 FE 边界取整 + **有**「不足 1 FE 清空零头」副作用）⇒ 硬统一需再加 2 个参数，
   收益（消除 ~20 行）不抵复杂度。
4. `ContainerEnergyStorage.extract` → `withdraw(...)` —— ❌ **决定不做**，理由三条：
   ① 它是**唯一**调用方 ⇒ **没有重复可消**（`withdraw` 只是把代码从 A 搬到 B）；
   ② 原实现「抽够即停」**提前退出**，而 `scan` 必须**全扫**所有槽位 ⇒ 热路径**性能退化**；
   ③ 口径独特（余数跨 FE 边界向上取整）。
   ⇒ **判据沉淀**：统一应针对「**复杂 + 有 bug 史 + 有多份拷贝**」的部分，
   **不为形式上的「全收」而搬** —— 单调用方且逻辑简单的实现，搬进新类只是换了位置、不产生价值。

### 硬约束（迁移时不许破坏）

- **性能**：`deposit` 在外部电力 mod 的热路径上，必须保持「只扫一遍 `getStackInSlot`」。
  不要为了统一而改成「每堆重新取物品」。`BulbBank` 内部用数组（非铜灯槽位留 null）。
- **无跨调用状态**：不要引入 ThreadLocal 暂存池。曾在配方书 Mixin 上因重入风险否决过同类方案。
- **语义红线**：整 FE 量化、完整步进保护（`count > leftover` 跳过）、
  「宁损勿造」（实充 ≤ 记账）、零头回收的 `MAX_LEFTOVER_PASSES` 防御上限，全部保留。
- `RoundTripConservationIT` 是这条红线的守护者，**不许为了让它过而改它**。

### 遗留观察 → ✅ 已收紧（2026-10-09 当日闭环）

**发现**：「未活化涂蜡铜灯」在两条路径上判据不一致 ——

| 路径 | 判据 | 未活化灯 |
|---|---|---|
| 容器（`ContainerEnergyStorage` → `BulbBank.isBulb`） | 要求 `isLivingItem` | **不**参与 ✓ |
| 物品 capability（`BulbItemEnergyStorage`） | **无**（按原版物品注册 + provider 未判） | **能**被充/放 ✗ |

**根因（决定性对比）**：`LivingItem.onRegisterCapabilities` 里**同一个方法内**，
活箱子 / 活末影箱的 provider **都判了** `if (!isLivingItem(stack)) return null;`，
**铜灯那一处漏了** ⇒ provider 无条件返回实例。**是漏写，不是 API 限制** ——
provider 拿到的是**运行时 stack**，完全可以在里面判、返回 `null` 表示「无此能力」。

**⚠️ 一处被推翻的推理**：曾以为「取消活化后电量保留」（依据 `WaxedCopperStorageTest`
那条 DisplayName 写着「电量保留但不进出」）。**探针实测推翻了它**：

```
[PROBE] 活化后    : living=true  charge=4000  hasComponent=true
[PROBE] 取消活化后: living=false charge=0     hasComponent=false
```

机制：`setLiving(false)` → `clearLivingData` → 遍历各功能的 `getOwnedComponentTypes()`
逐个 `remove`，而 `LivingWaxedCopperFunction` 的清单**包含** `LIVING_WAXED_BULB_DATA`。
⇒ 那条测试用**手动构造**（直接 set 电量 + 不打 `IS_LIVING`），根本没走取消活化流程，
DisplayName 的「电量保留」**与真实行为相反**（已修正措辞）。

**⇒ 收紧零风险**：正常流程下未活化灯**恒为空**（取消活化清电 + 容器路径不充），
「带电的未活化灯」只可能是该漏洞的产物 ⇒ 收紧**不丢任何数据**。

**实施**：准入判据收在 `BulbItemEnergyStorage.of(ItemStack)`（未活化返回 `null`），
`LivingItem` 只透传（L4 只接线，判据归领域）；补测试锁住「未活化 ⇒ 拿不到电池」；
修正那条误导的 DisplayName。✅ 反向验证：去掉守卫 ⇒ 1 个测试挂。
✅ **2026-10-09 游戏内实测通过**：未活化的原版涂蜡铜灯放进外部充能槽 ⇒ **充不进**；
活化后照常能充（用户实测）。

### 另一处观察 → ✅ 已确认（2026-10-09 用户拍板：**正常行为，不改**）

**取消活化会清空电量**（上表实测）：玩家取消活化一个满电铜灯 ⇒ 电归零。
这是**有意**的设计（`getOwnedComponentTypes` 的 javadoc：「取消活化 = 清掉活物品数据，
避免孤儿」）—— 用户确认符合预期，**保持现状**。

> 📌 **语义**：「活化 = 进入能量系统」的完整表述是「**进入时带数据、离开时清数据**」。
> 铜灯的电量组件登记在 `LivingWaxedCopperFunction.getOwnedComponentTypes()` 里，
> 所以取消活化即清空 —— 与活箱子「取消活化掉内容」**同一族语义**（离开即结算）。

## 4. 步骤 3 —— 单位值类型（成本最高，可延后或不做）

```java
record MilliFe(long value) { ... }   // 1 FE = 1000 mFE
record Re(long value) { ... }        // 1 RE = 1/16 FE
```

**收益**：`mfe + re` 直接编译不过；`mulDivFloor` 的调用方被迫想清楚单位。

**成本**：power 包几乎每个签名都要改，且 `LivingWaxedBulbData` 是 DataComponent
（要序列化），改类型会牵动存档兼容。

**我的建议：暂不做。** 现在有了 `mulDivFloor` 这个单一入口，溢出面已经收窄；
值类型是「让错误编译不过」的终极手段，但对一个仍在快速迭代的模组来说，
改动面大、收益边际。**等 power 的迭代节奏慢下来再考虑。**

## 5. 步骤 4 —— 拆 `LivingWaxedCopperFunction`（纯搬迁，零风险）

按依赖从外到内拆，**每一刀都是"把代码搬到新文件 + 改 import"**，不改逻辑。

| 顺序 | 拆出 | 行数 | 为什么先拆它 |
|---|---|---|---|
| 1 | `LivingWaxedCopperTooltip` | ~~~287~~ **✅ 已做（2026-10-09，`0769957`）** | **完全无 tick 依赖**，纯客户端展示。包里已有 `LivingWaxedCopperTooltipComponent` / `...TooltipRenderer`，命名惯例现成。`addToTooltip` 保留为一行委托（接口要求）。实际 **1265 → 995 行（-270）**；顺带删除两个死方法（`renderResonanceTooltip` / `renderResonanceFormula`）+ 一个死局部变量（`oxidation`）。保真验证：用翻译键逐键 diff，原 body 的 14 个键一个不少。 |
| 2 | ~~`WaxedCopperItems`~~ → **`living/util/WaxedCopperFamily`** | ~~~98~~ **✅ 已做（2026-10-09，`1d16160`）** | 纯 `Item` 谓词，无状态。**改去 L1 `WaxedCopperFamily` 而非 power 包内** —— 它的 javadoc 早已写明迁入条件（「若将来其它域也需要，应一并迁到本类」），且 `isWaxedBulb` / `isWaxedChiseled` 确已被 power 域**之外**的类使用（`ContainerEnergyStorage` / `LivingItemClient` / `LivingWaxedChiseledDecorator`）。⚠️ **`getCoilForm` 刻意不迁** —— 它映射 power 域的 `LivingWaxedGeneratorData.FORM_*`，迁到 L1 会造 **L1 → L3 反向依赖**。实际 **995 → 945 行**；R1/R3 无新增违规。 |
| 3 | `CopperNetworkTopology` | ~~~244~~ **✅ 部分（2026-10-09，`f7296c4`，实抽 135 行）** | **边界比原表更窄**：只抽「与 tick 流程无关的纯图/键运算」（`DIR_ROW/COL` · `pos2dToEdgeDir` · `traversableNeighbor` · `repOf`+`NetworkKey` · `edgeKey` · `FALLING_BIT` · `chiseledInputEdge`）。⚠️ **`runBfs` 留下** —— 它混合「拓扑遍历 + 边信号检测 + 通道事件注入」，属 tick 流程；第 4 刀已将纯相位解读拆出，但 BFS 本身仍留在编排类。主类后续行数随第 4 刀更新。 |
| 4 | `PhaseInterpreter` + 顶层 `SignalTracker` | ~153 + 内部类 74 | **✅ 已做（2026-10-09，本轮）**。`phaseInterpretation` / `interpretShifter` / `interpretSplitter` / `interpretAdder` / `derivedSourceId` 搬入 `PhaseInterpreter`；`SignalTracker` 从功能类内部类提升为顶层类，因为被 `ContainerPowerData`（持有两张 tracker 表）、`PhaseInterpreter`、tick 编排三方共用。消除 `ContainerPowerData → LivingWaxedCopperFunction` 的层内倒挂；`PhaseSnapshot` 现在直接用顶层 `SignalTracker`。纯搬迁，不改相位算法。主类 **824 → 588 行（-236）**。 |
| 5 | `EnergyAccounting` + `PowerTelemetry` | ~130 | **✅ 已做（2026-10-09，本轮）**。`accountEnergy` / `buildTelemetry` / `formatMilliFe` / `collectDomains` 搬出功能类，调用点改委托。记账仍在 tick 顺序内逐发电机执行（各自 pref 敏感保留）；遥测构建只投影状态，不改账本。主类 **588 → 476 行（-112）**。 |

拆完 `LivingWaxedCopperFunction` 剩 ~350 行，只剩「接口实现 + tick 编排」——
**那才是一个功能类该有的样子**。

**当前剩余**：仅步骤 3（单位值类型，成本最高，方案建议可延后或不做）。
原「建议只做第 1、2 刀」是早期建议，已由 1~4 刀完成**取代**；此处若保留会误导后续执行者。

## 6. 每一步都必须做的验证协议

从 2026-09-11 的教训里固化下来：

1. **先取全量基线**：`./gradlew test --offline`，记录 `tests / failures` 与**失败清单**。
   不知道基线就无法判断「是不是我搞坏的」。
2. **改动后重跑全量**，对照成表：`基线 228/9` vs `改后 234/1`，且失败集合必须一致。
3. **写测试后必须验证它真能失败**：临时把修复退回，确认报出有意义的数字。
   例：`expected: <1000000000> but was: <34338>`（0.0034%）、
   `expected: <9765625> but was: <758425>`（7.8%）。
4. **断言要语义化**：断言物理结果（每盏充满 / 实充 ≤ 应收），不要断言内部计数器。

## 7. 并行工作约束（重要）

本方案执行时，工作区可能同时有另一个会话在改 `domain/redstone`、
`domain/power/ContainerPowerData`、`LivingItemManager`、`ContainerLivingItemHandler`
及相位相关测试。操作规范：

- 动手前 `git status --short` 看清哪些是别人**已暂存**（`M `）的改动。
- **绝不**对不属于自己的文件执行 `git checkout --` / `git stash`。
  做基线对照应复制自己拥有的文件到 `/tmp`，只还原那几个。
- 自己的改动保持小且内聚；共享文件用
  `git diff --stat -- <f>`（我的）vs `git diff --cached --stat -- <f>`（对方的）区分。
- 共享文件的改动存一份补丁到 `.workbuddy-ai/patches/` 作保险。

## 8. 明确不做的事

- ❌ 不为「统一」而抹平三处**故意的**口径差异（量化/报账策略要变成显式参数，不是删掉）。
- ❌ 不引入 ThreadLocal 暂存池、指纹缓存、轮询等跨调用状态来"优化"热路径
  （配方书 Mixin 与 `receive()` 都否决过同类方案）。
- ❌ 不为了消除重复而破坏 `receive()` 的单遍扫描性能特征。
- ❌ 不在 power 包迭代活跃期做步骤 2~5 的大搬迁。
