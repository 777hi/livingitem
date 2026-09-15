<!-- markdownlint-disable -->

# 红电系统不变量清单与四层测试方案

> **文档版本**: v1.0（2026-09-07）
> **适用范围**: 电力层（`domain/power`）+ 信号层集成路径
> **状态**: 定稿待实施 —— 本文是**可执行断言的正式清单**，代码尚未落地
> **工具选型**: 第一层属性测试采用 **jqwik**（JVM 属性测试标准库，自带 shrink 收缩）

---

## 0. 为什么是不变量，而不是场景

涌现式设计的根本测试困境：**场景空间 B = R(S) 是无限的**——我们无法枚举玩家能造出什么。
但 **R 是我们自己写的**：R 的性质（守恒、有界、单调、对称）就是不变量。

> **设计者定义不变量，测试验证不变量，玩家负责探索边界。**

本会话修过的每一个历史 bug 都是佐证：频率中性化反转、停机虚能量、偏移活性高估、
EMA 不归零毁共振、雕文方向引用比较……**没有一个是靠「多测一个场景」能拦截的**——
它们全是「跨场景不变的性质」被破坏（映射表见 §3）。因此测试姿态从
「枚举期望输出」切换为「断言无论输入如何、哪些性质必须成立」。

四层方案（§4）按杠杆排序：第一层（纯函数属性测试）与第四层（运行时监控）
是高杠杆起点；第二、三层（场景生成 / 蜕变测试）等前两层跑出价值再扩。

---

## 1. 不变量总表

35 条，七组。层归属：**L1** = 纯函数属性测试、**L2** = 场景生成断言、
**L3** = 蜕变测试、**L4** = 运行时监控（§4 详述各层）。

> **2026-09-16 增补 G 组（算术安全）**：原表遗漏了一类**不是语义问题、而是 JVM 算术问题**的
> 不变量 —— 它曾导致**服务端冻结**与**凭空造电**两次真实事故，却因为「看着像性能问题」而长期
> 只活在项目记忆里。G 组补上，共 **35 条 / 七组**。

| ID | 一句话陈述 | 层 | 可拦截的历史 bug |
|----|----------|:--:|----------------|
| I-A1 | 频率中性化：同发电机，输入换成 k×pref 整数倍谐波 → 平均功率不变 | L1/L3 | 频率中性化反转（×P 超发） |
| I-A2 | 跳变门控：本 tick 无跳变 → 产出 0；停机 → 总产出冻结 | L1 | 停机虚能量（域存活窗口内白拿） |
| I-A3 | 跳变按 offset 去重：jumpCount(t) ≤ n(t)，同偏移多边 = 1 跳 | L1 | 同振荡器多边重复计跳 |
| I-A4 | 非负有限：全部 RE / EMA / FE 读数 ≥ 0 且无 NaN / Inf | L1/L4 | （卫生底线） |
| I-A5 | K 换算单调：reToFe 单调不减、reToFe(0)=0、误差 ≤ 1/2 FE | L1 | （边界换算回归） |
| I-B1 | n ≤ P：域内去重后的相数永不超过周期 | L1/L4 | 派生偏移跑满（n 虚增） |
| I-B2 | 偏移有界：域内全部 offset ∈ [0, P) | L1/L4 | 同上（I-B1 的前提） |
| I-B3 | 同相合并不虚增不归零：同 (P, offset) 多事件 → n=1、Σ√\|Δ\| 取 max | L1 | 两相同振荡器并排零产出误判 |
| I-B4 | 偏移活性：拆掉某路振荡器 → 超时窗口内 n 回落 | L1 | 拆振荡器 n 永久高估 → 白拿 |
| I-B5 | 锁相收敛：恒定间隔 P 的上升沿流，2 个沿后 period()==P 精确成立 | L1 | 周期估计漂移 |
| I-B6 | 派生相位界：派生 offset ∈ [0,P)、驻波死于存活窗口、无种子环自熄 | L1/L4 | 移相环互读偏移跑满 |
| I-C1 | 共振有界：s∈[0,1]、R∈[1, min(N,4)]、gain=R²∈[1,16] | L1/L4 | 共振失控（结构性防御回归） |
| I-C2 | 单遍前馈禁回代：gain 只由共振前基础值计算，voiceRe=round(base×gain) | L4 | 回代环永动机 |
| I-C3 | EMA 有界自愈：停发锈级在有限 tick 内 EMA 精确归 0 | L1/L4 | EMA 不归零毁共振且无法自愈 |
| I-C4 | 平衡度尺度无关：balanceFactor(c·v) == balanceFactor(v) | L1 | （GM/AM 实现回归） |
| I-C5 | 同锈级直加：同锈级多个连通块出力直接相加，无 √N 衰减 | L2 | 拆簇刷倍率（结构性封顶 R≤4） |
| I-D1 | 账本平衡：入灯 mFE ≤ 发电 mFE（量化/满溢只减不增） | L1/L4 | 双花 / 凭空造电 |
| I-D2 | 无同色灯即弃：k 锈级无同色灯 → 电量不落任何池、不渗入他锈级 | L1 | 锈级隔离泄漏 |
| I-D3 | 按盏守恒：拆分两半各带同一 q；同 q 才可合并 → 总电量不变 | L1 | 拆分刷电（每堆存总量的老方案） |
| I-D4 | 容量界：任意操作序列后 0 ≤ q ≤ C（每盏电量） | L1/L4 | 平方容量拆分坍缩 / 超容 |
| I-D5 | 双向等量：外部充入 X FE ↔ 红电侧可取出 X FE，往返无损无套利 | L1 | 跨系统能量套利 |
| I-E1 | E2E 守恒：铜灯充电增量 ≤ Σ round(base[k]×gain)×K（含共振口径） | L2/L4 | 全链路记账漏损 |
| I-E2 | 活性：持续振荡 + 调谐发电机 → 预热后每个显示窗口必有产出 | L2 | 稳态跳过冻结振荡（信号层同型） |
| I-E3 | 感应可达：邻居朝本槽出边 > 0 → 传感器读数 > 0 | L2 | 漏斗锁定 / TNT 点燃读不到信号 |
| I-E4 | 几何不变：同构布局平移一格 / 换容器形状（27/36/54/96）→ 行为不变 | L2/L3 | 大箱 / 背包 / 96 槽形状差异 bug |
| I-E5 | 复制可加：两个互不连通的同锈级网络 → 总功率 = 各自之和 | L2/L3 | （可加性回归） |
| I-E6 | 幅度缩放：单路信号源堆叠 k→k′ → 功率 ×(k′/k)^(1+u)（u 不变时精确） | L3 | 幅度因子回归（stackCount² / √ 压缩） |
| I-E7 | 序列化安全：全部组件 codec 往返 → 行为不变 | L1/L2 | 雕文方向恒 UP（引用比较） |
| I-F1 | 量化幂等：quantize(quantize(x)) == quantize(x)，相对误差 ≤ 10^(1-sig) | L1 | 稳态脏写泛滥 |
| I-F2 | 显示零纹波：稳态振荡下显示窗口均值跨结算边界恒定 | L2 | 高频 EMA 闪烁 / 小功率不显示 |
| I-F3 | 遥测必达：任一查看路径（BE / 大箱 / 背包 / 创造）→ 客户端缓存必被填充 | L2 | 大箱 / 背包 / 创造 tooltip 全 0 |
| I-G1 | 份额不溢出：`a * b / c` 形式的份额 / 比例计算先转 double 算比例再夹上界；任意输入下无 long 溢出 | L1 | **零头回收退化成 ~10 亿轮 → 服务端冻结**；发电直存份额为负 → 静默丢弃 |
| I-G2 | 分配总额守恒：`Σ distributed + leftover == accept`，且零头回收在**有界轮数**内结束 | L1/L4 | 份额算错 → 回收循环失控（同上） |
| I-G3 | 宁损勿造：份额异常时**宁可少充绝不超充**（share 过大必须夹到 `remaining`） | L1 | **凭空造电**（`newQ` 被拉到 CAP） |
| I-G4 | 请求量上界：入口必须安全吃下外部传入的 `Integer.MAX_VALUE`，不得假设请求量小 | L1/L4 | 外部 mod（Flux Networks「绕过限制」）大额请求触发溢出 |

---

## 2. 分组详述

每条给出：**来源**（红电系统.md 的设计原则）→ **数学陈述** → **断言入口**
（真实类与方法）→ **断言草图**。方法签名以当前代码为准（v19.1）。

### 2.A 记账（Accounting）—— 「跳变即能量事件」的全部推论

> 来源：§3.4 频率中性化 / §3.10 记账门控 / living-power-tech.md §3.6

**I-A1 频率中性化**
同发电机（pref 固定、n 相同），输入周期换成 P′ = k·pref（整数倍谐波，
`tuningEfficiency(|P′−pref|, pref) = (1+cos 2π(k−1))/2 = 1` 恰为满值），
稳态平均功率不变：`(F·P′)·(1/P′) == (F·P)·(1/P)`。宽波段（pref<2）同理成立。
- 入口：`LivingWaxedCopperFunction.accountEnergy` + `GeneratorState.drainAndEndTick`
  （`AccountingGateTest.runOscillator` 已示范驱动方式：注入 `PhaseEvent` 逐 tick 记账）
- 断言：随机 pref∈[2,64]、k∈[1,8]，窗口取 lcm(pref, k·pref) 的整倍数，
  `avgPower(P) == avgPower(k·P)`；谐波不得带来任何收益（旧实现慢钟按 P 线性碾压）。

**I-A2 跳变门控（停机不虚发）**
域存活但本 tick 无上升沿 → 产出 0；振荡器停机后总产出严格冻结。
- 入口：`ChannelState.bestActiveDomain(pref, tick)`（只选 `jumpTick == tick` 的域）
  + `accountEnergy`
- 断言：无跳变 tick `drainAndEndTick() == 0`；停机后任意延长运行，累计值不变。

**I-A3 跳变去重**
`PhaseDomain.jumpCount(t) ≤ n()`；同一振荡器被两条边看到（不同 sourceId、
同 offset）→ jumpCount 恰为 1。
- 入口：`ChannelState.PhaseDomain.jumpCount` / `onPhaseEvent`
- 断言：注入 N 条同 (P, offset) 事件 → `jumpCount == 1`、`n == 1`；
  N 条互异 offset 事件 → `jumpCount == n`。

**I-A4 非负有限**
全部数值通道恒满足：RE ≥ 0、EMA ≥ 0、FE ≥ 0、无 NaN / Inf。
- 入口：`GeneratorState.getEmaPowerRe` / `ContainerPowerData.getEmaPowerByOxidation`
  / 各换算 getter（L4 逐 tick 检查，L1 随机参数下检查 `PowerMath` 全部输出）

**I-A5 K 换算单调**
`reToFe` 单调不减、零点固定、`|reToFe(r) − r/16| ≤ 1/2`（round 语义）。
- 入口：`PowerMath.reToFe`

### 2.B 相位（Phase）—— 相位域分组的结构性质

> 来源：§3.4 因子二「错开的精确定义」/ §3.8 相位解读三元件 / ChannelState 类注释

**I-B1 n ≤ P**
域内互异偏移数（Map 键去重）不超过周期——「相数上限 ≤ P」是整数 tick 分辨率
的自然推论，不由任何上限常数压住。
- 入口：`PhaseDomain.n()` / `period()`
- 断言：随机事件流 + `tickCleanup` 后遍历 `ChannelState.domains()`，
  恒有 `d.n() <= d.period()`。

**I-B2 偏移有界**
域内全部 offset ∈ [0, period)。真实采样来自 `SignalTracker.offset() =
lastRisingTick % period`，派生来自 `Math.floorMod`——两条来源都保证。
**这是 I-B1 的前提**（offset 互异且落在 [0,P) 区间 → 至多 P 个）。
- 入口：`PhaseDomain.deltaByOffset().keySet()`（只读视图即偏移集合）
- 断言：每个 key ∈ [0, period)。任何把裸 offset 写入域的改动都会在此被抓。

**I-B3 同相合并不虚增不归零**
同 (P, offset) 的多条事件合并为 1 组（n 不增），|Δ| 取 max——
玩家最直觉的「两个相同振荡器并排」应有产出，n=1 而非 0。
- 入口：`PhaseDomain.addOffset`（OffsetState.maxDelta 语义）
- 断言：同 offset 两条不同 |Δ| 事件 → n==1 且 `effDeltaSum == √max(|Δ|₁,|Δ|₂)`。

**I-B4 偏移活性（拆掉那路要回落）**
某偏移超过超时窗口（`clamp(max(32, 2·max(pref, P)), 32, 1200)`，与
`tickCleanup` 同口径）未再出现 → 被剪除；域内清空 → 整域移除。
- 入口：`ChannelState.tickCleanup` + `PhaseDomain.pruneStaleOffsets`
- 断言：注入两路异相事件后停止其中一路 → 超时窗口内 n 从 2 回落到 1；
  全停 → 域消失（`ChannelStateTest` 已有场景版，此为参数化推广）。

**I-B5 锁相收敛**
恒定间隔 P（≥2）的上升沿流：第 2 个沿后 `SignalTracker.period() == P` **精确**成立
（首间隔直接赋值、后续 α=0.5 平滑对恒定序列是恒等），offset 逐周期稳定。
- 入口：`SignalTracker.onRisingEdge / period() / offset()`
- 断言：随机 P∈[2,64] 的等间隔沿序列，2 沿后 period()==P；再注入间隔扰动，
  收敛不发散（period 始终 ∈ [2, 2P]）。

**I-B6 派生相位界（三元件结构安全）**
派生注册表条目：(a) offset ∈ [0, P)（floorMod 保证）；(b) 输入源停跳超过
`PowerMath.aliveWindow(P) ∈ [32,1200]` 后驻波被 `pruneRegistry` 剪除；
(c) **移相环无种子自熄**——环上成员输入边全是蜡-蜡死边，注册表恒空，
结构性无「互读偏移每 tick 自增跑满」的通路。
- 入口：`DerivedPhase` / `ContainerPowerData.pruneRegistry` / `phaseInterpretation`
- 断言（L1 静态 + L2 场景）：注册表全表 offset 有界；死源后 aliveWindow 内剪除；
  纯移相环场景跑任意长 → 注册表恒空、n 恒 0（`PhaseInterpretationTest` 场景版的推广）。

### 2.C 共振（Resonance）—— 三条铁律的可观测形式

> 来源：§3.6.1 网络级共振 / living-power-tech.md §3.7

**I-C1 有界**
`s = GM/AM ∈ [0,1]`；`R = 1+(N−1)·s ∈ [1, min(N,4)]`；`gain = R² ∈ [1,16]`。
N 被锈级数（4）结构性封顶——拆簇不可刷（I-C5 的对偶）。
- 入口：`PowerMath.balanceFactor / resonanceFactor / resonanceGain`
- 断言：随机非负向量（长度任意、含零/单元素/全零）下三界恒成立。

**I-C2 单遍前馈禁回代**
共振只采基础值：gain 由 `displayEmaByOxidation`（只喂 `baseReByOx[k]`）算出，
`voiceRe[k] = round(baseReByOx[k] × gain)` 一次套用、绝不回灌。
- 入口（L4）：`tickContainerData` 结算段——用 `baseReByOx` 快照重算 gain 与 voiceRe，
  与实际入账比对；单锈级场景恒 `gain == 1.0`（可观测推论：N=1 时永无放大）。

**I-C3 EMA 有界自愈**
指数衰减必须截断归零：停发锈级在有限 tick 内 EMA **精确为 0**（`==` 而非 `<ε`）。
- 快 EMA（`EMA_ALPHA=1/8, EMA_EPSILON=1e-6`）：归零 tick 数 ≤
  `log_{8/7}(V/ε)+1`（V=1000 RE 约 155 tick）；
- 显示窗口（`DISPLAY_WINDOW_TICKS=32`）：喂零 32 tick 后**恰好归零**（结算清零，
  比 ε 截断更干净）。
- 入口：`ContainerPowerData.updateOxidationEma` + `getEmaPowerByOxidation` /
  `getLevelDisplayEmaPowerRe`
- 断言：随机正出力喂 T tick 后转零 → 上界 tick 数内全数组 == 0.0；
  归零后 `activeOxidationLevels` 同步回落。

**I-C4 平衡度尺度无关**
`s(c·v) == s(v)`（c>0）——GM/AM 比值与规模无关，「3 锈级各 100」与
「3 锈级各 10000」平衡度相同（浮点下相对误差 ≤ 1e-9）。
- 入口：`PowerMath.balanceFactor`
- 断言：随机向量 × 随机 c ∈ [1e-3, 1e3] 双跑比对。

**I-C5 同锈级直加**
锈级共振单位是**锈蚀级**而非 BFS 连通块：同锈级的多个互不连通网络出力直接相加，
无 √N / 周长衰减。这是「R ≤ 4」结构性封顶的前提（否则拆簇刷 N）。
- 入口：`baseReByOx[k]` 累加语义（glue 层）
- 断言（L2）：同锈级两个独立簇 vs 合并成一簇 → 总发电相等。

### 2.D 储能（Storage）—— 铜灯 = 唯一储存的全部账目性质

> 来源：§3.6 v17.5→v18 / living-power-tech.md §4

**I-D1 账本平衡**
发电直存：Σ入灯 mFE ≤ Σ发电 mFE；量化截断与满溢 clamp 只会**少不会多**。
单灯容量充足且 count | mfe 时取等。
- 入口：`distributeToBulbs`（L1 用测试侧前后读数差实现，见 §4.1 注）
- 断言：随机 (count, q, generation, 多灯组合) 下 `charged ≤ generated`；
  充足容量 + 整除条件下 `==`。

**I-D2 无同色灯即弃**
k 锈级发电无同色灯 → 电量**不落任何池**（无容器池）、不渗入其他锈级灯堆。
- 入口：`distributeToBulbs(voiceRe, k, entries)` 的锈级过滤
- 断言：仅他色灯在场 → 全部灯 q 不变、返回 false / charged == 0。

**I-D3 按盏守恒**
电量按「每盏 q」存（DataComponent）：拆分时组件复制 → 两半各带同一 q，
总量 = (count₁+count₂)×q 严格不变；合并由原版「组件相同才可堆叠」保护
（同 q 才合并）→ 天然守恒。
- 入口：`LivingWaxedBulbData`（record 语义）+ 原版堆叠规则
- 断言：任意 (count, q) 拆成两半 → 总量不变；异 q 两堆不可合并（原版语义）。

**I-D4 容量界**
任意充/放/拆/合操作序列后，恒 `0 ≤ q ≤ C`（C = `BULB_UNIT_CAPACITY_MFE`）。
线性容量（count×C）是拆分安全的根基——平方容量在此断言下会坍缩。
- 入口：`distributeToBulbs` 的 `Math.min(C, q+perLamp)` / `BulbItemEnergyStorage`
- 断言：随机操作序列（含超量充电、超量抽取）后逐灯检查界。

**I-D5 双向等量**
外部充电 X mFE → 红电侧可取 X mFE；往返（取 X → 充 X）q 复原。
跨系统能量等量转换、无套利。
- 入口：`BulbItemEnergyStorage`（双向 `EnergyStorage.ITEM`）+ `ContainerEnergyStorage`
- 断言：随机 X 的充-取 / 取-充往返后 q 与初值相等（`BulbItemEnergyStorageTest` 的参数化推广）。

### 2.E 集成（Integration）—— 信号层 ⇄ 电力层接口的跨场景性质

> 来源：§1.5 集成 / §3.5 网络传播 / 信号层 v19 修订
> 主场在 L2/L3（需要全链路 `processContext`），I-E7 的纯组件部分在 L1。

**I-E1 E2E 守恒**
全链路（真实振荡 → 红石传播 → BFS 采样 → 记账 → 共振 → 直存）下：
Σ铜灯充电增量(mFE) ≤ Σ round(base[k]×gain)×K×1000。
共振口径必须计入——增益是「合法的放大」而非凭空（I-C2 保证其有界）。
- 入口：`ContainerLivingItemHandler.processContext` + 灯堆前后读数差

**I-E2 活性**
持续振荡输入 + 周期匹配的发电机 → 预热（EMA/显示窗口建立期）之后，
每个显示窗口必有非零产出。信号层同型断言：振荡器存在 → 每周期必有信号翻转
（稳态跳过优化曾吞掉翻转后果 tick 造成集体静止，属同类违反）。
- 入口：`GeneratorState.getDisplayEmaPowerMilliFe` 持续为正

**I-E3 感应可达**
邻居朝本槽的出边 > 0 → `RedstoneSensor.sensedSignal(slot, dir) > 0`。
电力层采样、漏斗锁定、TNT 点燃共用同一端口——曾因「非红石组件槽位出边恒 0」
读自身出边而全部失效。
- 入口：`RedstoneSensor`（统一感知端口，v19.1）

**I-E4 几何不变**
同构布局整体平移一格（保持界内）、或换容器形状（27/36/54/96 槽，邻接关系不变）
→ 行为不变。历史 bug 集中区：大箱 CompoundContainer、背包 36 槽、96 槽大容器。
- 入口：`processContext`（`WaxedGeneratorFeedChainTest` 已有 27/36/54 三形状雏形）

**I-E5 复制可加**
两个互不连通的同锈级簇（同容器）→ 总功率 = 各自之和（单锈级 gain 恒 1，
见 I-C2 可观测推论）；分放两容器同理。
- 注意：多锈级场景共振增益是**设计内的放大**，可加性断言只在单锈级 / 分容器口径下成立。

**I-E6 幅度缩放（√ 压缩语义的精确表达）**
单路输入、u 不变（n=1、eff 同）时：信号源堆叠 k→k′（均 ≥2，信号值 = count²）
→ |Δ| ×(k′/k)² → eff_δ_sum ×(k′/k) → 合因子 ×(k′/k)^(1+u) → 平均功率 ×(k′/k)^(1+u)。
- 这是 L3 蜕变测试的主关系之一（§4.3 M4）。

**I-E7 序列化安全**
所有 DataComponent 经 codec 编码→解码（值相等的新实例）后行为不变。
方向映射、灯电量、雕文配置全部适用——雕文「恒 UP」bug 即引用比较
`== Pos2D.RIGHT` 在反序列化实例上失效（已有 `directionMapping_valueNotIdentity` 场景版）。
- 入口：各 record 的 `CODEC` + 行为入口双跑比对

### 2.F 显示与遥测（Display）—— 口径与送达

> 来源：AGENTS.md v19.1 显示口径修复系列 / tooltip-system.md

**I-F1 量化幂等**
`quantize(quantize(x, s), s) == quantize(x, s)`，相对误差 ≤ 10^(1−s)。
幂等性是「稳态钉死值跳过脏写」优化的正确性前提。
- 入口：`PowerMath.quantize`

**I-F2 显示零纹波**
稳态振荡下，显示窗口均值（`GeneratorState` 的 ceil(32/pref)×pref 窗口、
`ContainerPowerData` 的 32t 窗口）跨结算边界恒定——脉冲式记账 + 快 EMA 的纹波
不得进入显示口径；mFE 定点让 <1 FE/t 的功率可见。
- 入口：`getDisplayEmaPowerMilliFe` / `getLevelDisplayEmaPowerMilliFe` 序列稳定性
- 断言（L2）：预热后连续 N 个窗口，读数逐 tick 相等（高频 2t 与低频 64t 双测）。

**I-F3 遥测必达**
有发电机的容器被玩家查看 → 客户端运行时缓存必被填充。三条历史路径各自成断言：
原版 BE、大箱（CompoundContainer 按 `contains(be)` 匹配）、玩家背包
（`player_` 前缀直发）+ 创造模式（引用匹配兜底）。
- 入口：`ContainerRuntimeCache` / `LivingItemClientCache`（既有修复的回归守卫）

### 2.G 算术安全（Arithmetic Safety）—— 定点制下的数值边界

> 来源：2026-09-11 服务端冻结事故 / 2026-09-15 发电直存造电事故

**背景**：mFE 定点制（1 FE = 1000 mFE）把量级抬高 1000 倍，每盏容量
`BULB_UNIT_CAPACITY_MFE = 1e9` ⇒ `accept * remaining[i]` 可到 **1e23 ≫ Long.MAX**。
这类溢出**不是语义问题**，所以 A–F 组的语义不变量**一条都拦不住它** —— 必须单独成组。

**I-G1 份额不溢出**
凡 `a * b / c` 形式的份额 / 比例计算，**先转 double 算比例、再夹到 `remaining`**；
任意输入（含 `Integer.MAX_VALUE` 请求、满盏 `remaining`）下不得发生 long 溢出。
- 入口：`ContainerEnergyStorage.receive` / `LivingWaxedCopperFunction.distributeToBulbs`
- 自检（改这一层必跑）：
  ```bash
  grep -n "[a-zA-Z0-9_)] \* [a-zA-Z0-9_(].* / [a-zA-Z0-9_(]" src/main/java/com/qiqi/li/living/domain/power/*.java
  ```
- 断言（L1 属性测试）：随机 `(accept, remaining[], count[])` 极值组合下
  `distributed ≥ 0`、`distributed ≤ accept`、无异常、毫秒级返回。
- ⚠️ **溢出后果不是「少充一点」**：`distributed ≈ 0` ⇒ `leftover = accept` ⇒ 零头回收 `while`
  退化成 **~10 亿轮**（服务端冻结）。**27 槽时铜灯总数超过约 16 盏就会触发。**

**I-G2 分配总额守恒**
`Σ distributed[i] + leftover == accept`；且零头回收在**有界轮数**内结束。
`MAX_LEFTOVER_PASSES = 256` 是防御上限 —— 合法 leftover ≤ Σ(count−1)，1~2 轮就完；
超限说明份额算错，**宁可少充也别卡死**（少充仍满足实充 ≤ 记账）。**别删这个上限。**

**I-G3 宁损勿造**
份额计算异常时宁可少充、绝不超充。`LivingWaxedCopperFunction.distributeToBulbs` 的 `mfe`
是全部发电机按锈级累加，`× remaining` 同样会溢出；后果**不卡死但更危险**：
share 为负 → 发电静默丢弃；share 变巨大正数 → `newQ` 被拉到 CAP → **凭空造电**。

**I-G4 请求量上界**
外部 mod 会用极端值调用：Flux Networks「绕过限制」模式下 `getLimit()` 返回 `Long.MAX_VALUE`，
`onCycleStart` 每 tick 按满额跑一遍**模拟**调用 ⇒ `receiveEnergy` 必须能安全吃下
`Integer.MAX_VALUE`，**不能假设请求量很小**。
- 入口：`ContainerEnergyStorage.receiveEnergy`（对外接口，**不在 PerfMetrics 计时区间内**
  —— 性能判读的覆盖盲区，见项目记忆「PerfMetrics 的覆盖盲区」）

**既有守护与缺口**：语义红线由 `RoundTripConservationIT` 覆盖（整 FE 量化
`accept -= accept % 1000`、完整步进保护 `count > leftover` 跳过、「宁损勿造」）；
**G 组的数值边界目前没有属性测试** —— §4.1 第一层 jqwik 是它的归宿。

---

## 3. 历史 bug ↔ 不变量映射

本映射表证明核心论点：**这些 bug 全是「跨场景不变性质」的破坏，
没有一个是「多测一个场景」能拦截的**。左列 = 已修复的真实 bug（AGENTS.md v19.1）。

| 历史 bug | 可拦截的不变量 | 层 |
|---|---|---|
| 频率中性化反转（慢钟 ×P 线性碾压，谐波无限放大） | **I-A1** | L1 |
| 停机虚能量（域存活窗口内照常入账） | **I-A2** | L1 |
| 同振荡器两条边重复计跳 | **I-A3** | L1 |
| 拆振荡器后偏移只增不减 → n 永久高估白拿 | **I-B4** | L1 |
| 派生偏移互读跑满（移相环） | **I-B6** | L1/L2 |
| EMA 不归零 → 停发锈级永久压死平衡度，共振无自愈 | **I-C3** | L1 |
| 红石稳态跳过吞掉翻转后果 tick → 振荡线路集体静止 | **I-E2**（活性，信号层同型） | L2 |
| 漏斗锁定 / TNT 点燃读自身出边恒 0 | **I-E3**（感应可达） | L2 |
| 大箱 tooltip 遥测为 0（CompoundContainer 匹配失败） | **I-F3**（送达）+ **I-E4**（几何） | L2 |
| 玩家背包 tooltip 不显示（空实例匹配拦截） | **I-F3** | L2 |
| 创造模式 tooltip 不显示（槽位索引错位） | **I-F3** | L2 |
| F3+H 仪器面板消失（挂载点读旧组件口径） | **I-F3** | L2 |
| 高频 FE/t 闪烁（脉冲记账 EMA 纹波） | **I-F2**（显示零纹波） | L2 |
| 小功率（<1 FE/t）EMA 不显示（整除归零） | **I-F2**（mFE 精度） | L2 |
| 共振平衡度/增益逐 tick 闪烁 | **I-F2** + **I-C1** | L2 |
| 雕文感应方向恒 UP（Pos2D 引用比较） | **I-E7**（序列化安全） | L1 |
| **零头回收 long 溢出 → 服务端冻结**（27 槽铜灯 >16 盏即触发） | **I-G1** + **I-G2** | L1 |
| **发电直存份额溢出 → 凭空造电**（`newQ` 被拉到 CAP） | **I-G1** + **I-G3** | L1 |
| 外部 mod（Flux Networks）传 `Integer.MAX_VALUE` 请求 | **I-G4** | L1/L4 |

反过来说：若这 19 条断言当时已存在，**每一次游戏内发现都会变成
一次自动失败 + 状态快照**，「游戏里发现 → 描述 → 仿真复现」的第一步被完全自动化。

---

## 4. 四层测试实施方案

### 4.1 第一层：纯函数属性测试（jqwik）

**依赖**（build.gradle 一行，mavenCentral 已配置）：

```gradle
// jqwik - 属性测试（自带 shrink：随机失败自动收缩到最小复现）
testImplementation 'net.jqwik:jqwik:1.9.0'
```

**FML 冒烟门（必须最先做）**：MDG `unitTest` 让测试 JVM 运行在 FML 引导下，
需先验证 jqwik 引擎在该 JUnit Platform 上被发现。加一个必过的冒烟用例：

```java
@Property
void jqwik_engine_discovered_under_fml(@ForAll @IntRange(min = 1, max = 64) int n) {
    Assertions.assertTrue(n >= 1);
}
```

`./gradlew test --tests "*JqwikSmokeTest"` 通过 → 全面铺开；不兼容 → 回退
固定种子 harness（保留本清单的断言集，仅损失 shrink）。

**文件约定**：现有 `*Test`（183 项）保留为「场景文档」——每条场景是一个已知
有趣的具体世界；新增 `*PropertyTest` 承载属性断言。两套互补，不互相改写。

| 新文件 | 覆盖 | 示例属性 |
|---|---|---|
| `PowerMathPropertyTest` | I-A4/A5/C1/C4/F1 | 随机向量下 gain∈[1,16]；balanceFactor 尺度不变（×c 双跑）；quantize 幂等 |
| `ChannelStatePropertyTest` | I-B1/B2/B3/A3 | 随机事件流 + cleanup → 全域 n≤P、offset∈[0,P)；同 offset 合并取 max |
| `ContainerPowerDataPropertyTest` | I-C3/C1 | 随机出力→喂零：EMA 上界 tick 内精确归零；32t 窗口恰好归零 |
| `AccountingPropertyTest` | I-A1/A2 | 随机 pref/k 谐波中性化；停机冻结（`runOscillator` 驱动模式的参数化） |
| `StoragePropertyTest` | I-D1/D2/D3/D4/D5 | 随机 (count,q,gen) 下 charged≤generated、q 界、往返等量 |

> 注：`StoragePropertyTest` 的 charged 用**测试侧前后读数差**实现
> （Σ q·count 前 − 后），不依赖生产代码改动；L4 监控需要的返回值重构见 §4.4。
> 断言入口全部是已验证的包级静态方法（`accountEnergy` / `distributeToBulbs` /
> `SignalTracker` 等，`AccountingGateTest` / `WaxedCopperStorageTest` 已示范同包直调）。

**shrink 的价值**：随机场景失败时 jqwik 自动把参数缩到最小复现
（如 `gain 越界` 收缩成 `N=2, A=[1, 0]`），直接产出回归用例种子。

### 4.2 第二层：场景生成 + 不变量断言

**ScenarioBuilder（种子驱动）**：随机组合两类基因跑全链路，然后断言跨场景不变量：

- **布局基因**：容器形状 ∈ {27, 36, 54, 96} × 槽位赋值 ∈ {空, 红石粉, 火把(方向),
  拉杆, 中继器(方向/档位), 比较器, 发电机(形态×锈级×堆叠), 铜灯(锈级×堆叠×q)}；
- **信号基因**：哪些源振荡（周期 / 占空 / 多源相位差 / 堆叠数）——
  一部分用脚本化拉杆方波（可控相位），一部分用火把环自振（真实传播延迟）。

**驱动**：`ContainerLivingItemHandler.processContext(ctx, level)` 生产路径
（`WaxedGeneratorFeedChainTest` 已验证 harness：FakeHandler + SimpleContainerContext
+ 逐 tick processContext），随机放置偏置保证「有效电路」密度（纯随机大概率全空）。

**断言集**：I-E1/E2/E3/E4/E5/F2/F3 + §2 各组的集成面。
**复现**：种子固定，失败打印种子 = bug 报告；跑一千个随机场景的信心
远高于手写一千个——随机会撞进没想到的组合。

### 4.3 第三层：蜕变测试

不知道期望输出，只知道「输入怎么变、输出该怎么跟着变」——涌现系统的特有优势。
全部构建在 ScenarioBuilder 之上（同种子 + 变换 = 最小差异对）：

| 关系 | 变换 | 期望 | 对应不变量 |
|---|---|---|---|
| M1 频率 | 振荡周期 P → k·P（pref 不变，谐波） | 平均功率不变 | I-A1 |
| M2 平移 | 整个布局平移一格（界内） | 行为相同 | I-E4 |
| M3 复制 | 复制出第二个互不连通同锈级簇 | 总功率 = 2×（单锈级 gain=1） | I-E5 + I-C2 |
| M4 幅度 | 信号源堆叠 k → k′（均 ≥2） | 功率 ×(k′/k)^(1+u) | I-E6 |

失败时用 jqwik 的 shrink 缩到最小差异对，直接定位哪条规则被破坏。

### 4.4 第四层：运行时不变量监控（玩家 = 模糊测试器）

**新类 `PowerInvariantMonitor`**（`domain/power`，零 Minecraft 依赖可单测）+
**新 Logger `ModLog.POWER`**（`living_item.power`）：

```java
public final class PowerInvariantMonitor {
    /** 常开低开销（纯比较）；-Dlivingitem.invariants=false 关闭 */
    public static final boolean ENABLED =
        Boolean.parseBoolean(System.getProperty("livingitem.invariants", "true"));
    /** -Dlivingitem.invariants.throw=true 时违反即抛（集成测试 fail-fast） */
    public static final boolean THROW =
        Boolean.getBoolean("livingitem.invariants.throw");
}
```

**挂载点**：`LivingWaxedCopperFunction.tickContainerData` 末尾、
`powerData.endTick()` 之前——此处 `baseReByOx` / `active` 发电机表 /
`resonanceGain` / `powerData` 全部在作用域内。

**前置重构（唯一的生产代码改动）**：`distributeToBulbs` 返回值
`boolean → long`（实际充入 mFE），主循环改 `charged > 0` 判断、
`WaxedCopperStorageTest` 的 7 处调用点（6 个测试方法）同步改 `> 0`。
没有它，I-D1 只能靠灯堆前后快照（也可行但每 tick 分配）。

**每 tick 断言集**（O(发电机数 + 域数 + 锈级数)，纯比较零分配，快照仅违反时构造）：

```
I-A4  全部 EMA 有限且 ≥ 0，无 NaN
I-B1/B2  遍历 active 发电机 channel.domains()：n ≤ period、offset ∈ [0, period)
I-C1  gain ∈ [1, 16]；voiceRe[k] ≤ 16 × baseReByOx[k]
I-C2  用 baseReByOx 重算 gain 与 voiceRe[k]，与实际值比对（禁回代）
I-C3  pref ∈ [0, 64]；发电机表里无过期僵尸条目（活性由 prune 保证）
I-D1  Σ charged ≤ Σ voiceRe mFE
I-D4  逐灯 q ∈ [0, C]（本次 entries 的灯堆）
I-B6  注册表条目 offset 有界（遍历 phaseRegistry）
```

**违反时**：`ModLog.POWER.error("[不变量违反] {} tick={} 违反={}\n快照={}", …)`
+ 懒构造快照（containerKey / tick / 每台发电机 pref·n·bestP·EMA / baseReByOx /
gain / 灯堆状态），`THROW` 模式抛 `IllegalStateException`。

**效果**：每一次游戏测试自动变成一次不变量验证——你在游戏里随手搭的任何东西
只要违反规则，日志立刻指出**违反了哪条不变量 + 当时的状态快照**。
测试覆盖随游戏时长线性增长；接入 `WaxedGeneratorFeedChainTest` / OscillatorIT
等既有 E2E（`THROW` 模式）即免费获得回归守卫。

---

## 5. 诚实边界

不变量覆盖不了的东西，必须说清楚：

1. **设计级歧义**——「感应方向」箭头的语义玩家如何理解、EMA 口径玩家如何预期、
   锈级配色是否直觉……这类问题不是「性质被破坏」，是「设计本身待验证」，
   只有游戏体验反馈和文档能回答；
2. **数值平衡**——×16 满配增益是否过高、K=1/16 产量是否合理，是经济设计判断，
   不变量只能保证「不失控」（有界），不能保证「好玩」；
3. **性能回归**——本清单全部关于正确性；性能由 `PerfMetrics` 既有体系负责。

所以第四层的日志 + 亲自玩，仍然是不可替代的最后一级。
不变量回答「系统是否仍然是我们设计的那个系统」，不回答「这个系统是否好玩」。

---

## 6. 实施路线图

| 步骤 | 内容 | 规模 | 门槛 |
|---|---|---|---|
| **Step 0** | 基线重测：`./gradlew test` 实跑确认总数（AGENTS.md 记 183，但 `WaxedGeneratorFeedChainTest` 12 项未入册索引，真实基线待实跑修正） | 0.5h | 必做 |
| **Step 1** | **L1**：jqwik 冒烟门 → 5 个 `*PropertyTest`（§4.1 表） | 1~2 天 | 冒烟不过则回退种子 harness |
| **Step 2** | **L4**：`ModLog.POWER` + `distributeToBulbs` 返回 long 重构 + `PowerInvariantMonitor` 接入 + 既有 E2E 挂 `THROW` | 1 天 | 全量测试保持绿 |
| **Step 3** | **L2**：`ScenarioBuilder` + I-E 组断言 | 3~5 天 | **等 L1/L4 跑出价值**（两周游戏测试无违反）再扩 |
| **Step 4** | **L3**：M1~M4 蜕变关系（复用 L2 生成器） | 2~3 天 | L2 稳定后再扩 |

> 与 [redstone-evolution-roadmap.md](redstone-evolution-roadmap.md) 的关系：
> 该文档规划**架构**如何演进（SensorPort → 事件流 / 元件接口化）；
> 本文档规划**正确性**如何被持续验证。edgeGrid 边模型重构时，
> 只改 L4 监控的断言入口（RedstoneSensor 端口已收口），断言集本身不变——
> 这正是「不变量」的设计意图：实现怎么换，性质不换。

---

*本文档由 v19.1 电力层测试策略讨论沉淀。历史 bug 映射表（§3）的每一条
都对应一次真实修复——它们是本清单最有力的合法性证明。*
