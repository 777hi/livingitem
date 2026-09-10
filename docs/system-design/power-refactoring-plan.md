# 红电包收口方案（power refactoring plan）

*创建: 2026-09-11 · 状态: 未执行（待并行工作收口后按序推进）*

> ⚠️ **本文件尚未登记到 `AGENTS.md` 子系统索引** —— 因为 `AGENTS.md` 当时正被另一个
> 并行会话修改，避免冲突故暂缓。收口工作排期时请补上索引条目。

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
2. `LivingWaxedCopperFunction.distributeToBulbs` → `BulbBank.scan(entries 过滤锈级).deposit(..., ANY_MOVEMENT)`
   —— 注意它需要 `slotFilter`（锈级专属通道）。
3. `BulbItemEnergyStorage` 双向 → `BulbBank.of(stack)`，策略 `CEIL_DECLARED`。
4. `ContainerEnergyStorage.extract` → `withdraw(..., 余数向上取整)`。

### 硬约束（迁移时不许破坏）

- **性能**：`deposit` 在外部电力 mod 的热路径上，必须保持「只扫一遍 `getStackInSlot`」。
  不要为了统一而改成「每堆重新取物品」。`BulbBank` 内部用数组（非铜灯槽位留 null）。
- **无跨调用状态**：不要引入 ThreadLocal 暂存池。曾在配方书 Mixin 上因重入风险否决过同类方案。
- **语义红线**：整 FE 量化、完整步进保护（`count > leftover` 跳过）、
  「宁损勿造」（实充 ≤ 记账）、零头回收的 `MAX_LEFTOVER_PASSES` 防御上限，全部保留。
- `RoundTripConservationIT` 是这条红线的守护者，**不许为了让它过而改它**。

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
| 1 | `LivingWaxedCopperTooltip` | ~287 | **完全无 tick 依赖**，纯客户端展示。包里已有 `LivingWaxedCopperTooltipComponent` / `...TooltipRenderer`，命名惯例现成。`addToTooltip` 保留为一行委托（接口要求）。 |
| 2 | `WaxedCopperItems` | ~98 | 纯 `Item` 谓词，无状态。`isWaxedBulb` 被 3 个文件引用（含 `ContainerEnergyStorage`），`buildTelemetry` 被 3 个引用 —— 搬完统一改 import。 |
| 3 | `CopperNetworkTopology` | ~244 | BFS + `repOf` + `edgeKey`。被 `tickContainerData` 调用，无外部引用。 |
| 4 | `PhaseInterpreter` | ~153 | `phaseInterpretation` / `interpretShifter` / `interpretSplitter` / `interpretAdder` / `derivedSourceId`。 |
| 5 | `EnergyAccounting` + `PowerTelemetry` | ~130 | `accountEnergy` / `buildTelemetry` / `formatMilliFe` / `collectDomains`。 |

拆完 `LivingWaxedCopperFunction` 剩 ~350 行，只剩「接口实现 + tick 编排」——
**那才是一个功能类该有的样子**。

**建议只做第 1、2 刀。** 它们无状态、无 tick 依赖、收益立竿见影（文件立减 385 行），
风险几乎为零。第 3~5 刀涉及 tick 内部状态流转，等 `PhaseInterpretationTest` 全绿、
相位相关工作彻底收口之后再说。

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
