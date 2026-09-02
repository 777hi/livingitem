<!-- markdownlint-disable -->

# Living Power (活涂蜡铜块 · 红电发电) 技术文档

> **文档版本**: v4.0（公式 v3：√|Δ| 求和 + 铜块网络传播）
> **最后更新**: 2026-09-01
> **适用版本**: Minecraft 1.21.1
> **规划文档**: [红电系统.md](../红电系统.md)（v17.7，公式 v3）

## 目录
1. [架构概览](#1-架构概览)
2. [数据结构](#2-数据结构)
3. [核心算法](#3-核心算法)
4. [记账模型（RE / FE）](#4-记账模型re--fe)
5. [与红石系统的集成](#5-与红石系统的集成)
6. [测试](#6-测试)
7. [实施状态](#7-实施状态)
8. [已知限制](#8-已知限制)

---

## 1. 架构概览

活涂蜡铜块是电力层的载体：涂蜡 = 绝缘 = 不参与信号层。
发电机通过**铜块网络**检测同氧化等级铜块传播的相位事件来发电。
设计哲学是「构造几条规则，剩下的交给涌现」——
公式里唯一的常数是边界汇率 `K = 1/16`，其余全部由建造涌现。

### 1.1 公式 v3（铜块网络传播）

```
eff_δ_sum        = Σ√|Δ_i|                        // 各边信号 |Δ| 的 √ 求和
解锁度 u         = 调谐效率 × (n / 偏好周期)       // [0, 1]
调谐效率         = (1 + cos θ) / 2，θ = (tick误差 / 偏好周期) × 2π
合因子           = (eff_δ_sum)^(1+u)               // 不含 P
单次跳变能量(RE) = 合因子 × P
FE               = RE × K，K = 1/16
```

| 要素 | 来源 | 涌现方式 |
|---|---|---|
| √|Δ_i| 求和 | 铜块网络中各边信号的上升沿幅度，经 √ 压缩后求和 | 信号源堆叠数²，√ 压缩后差异缩小 |
| n（相数） | 同周期域内不同偏移量去重 | 布线（同相合并，不跨域累加） |
| 调谐效率 | 输入周期 vs 偏好周期 | 堆叠数 = 调谐旋钮 |
| 解锁度 u | eff × n / 偏好周期 | 相数越多解锁度越高，建造难度也越高 |
| 铜块网络 | 同氧化等级铜块 BFS 遍历 | 不同锈蚀等级形成独立网络，需布线连接 |

### 1.2 关键类与职责

| 类名 | 位置 | 职责 |
|------|------|------|
| `LivingWaxedCopperFunction` | `domain/power/` | 功能入口：`canApply`（涂蜡全家族 20 件）、`getPriority()=3`、铜块网络 BFS + 边信号检测 |
| `PowerMath` | `domain/power/` | 纯函数：合因子、调谐效率、√|Δ| 求和、RE 记账、K 换算 |
| `PhaseEvent` | `domain/power/` | 相位事件记录：sourceId、period、offset、delta、tick |
| `ChannelState` | `domain/power/` | 通道状态：相位域分组计 n、eff_δ_sum 计算、合因子 |
| `PhaseDomain` | `domain/power/`（ChannelState 内部类） | 周期域：按 period 分域，offset 去重，Δ 跟踪 |
| `SignalTracker` | `domain/power/`（LivingWaxedCopperFunction 内部类） | 上升沿跟踪器：间隔 EMA 估计周期、偏移量计算 |
| `GeneratorState` | `domain/power/` | 单台发电机状态：偏好周期（= 堆叠数）、单通道事件接收 |
| `ContainerPowerData` | `domain/power/` | 容器级账本：RE 事件累加、EMA 功率、tick 计数、边信号跟踪器持久化 |

除 `LivingWaxedCopperFunction` 外全部为**纯 Java 类**（零 MC 依赖），可直接 JUnit 驱动。

### 1.3 调度与数据流

```
processContext() 每 game tick：
  ├─ priority 2：红石 calculate()（edgeGrid 双缓冲刷新）
  └─ priority 3：LivingWaxedCopperFunction.tickContainerData()
       ├─ 收集发电机槽位
       ├─ 每台发电机从自身出发 BFS 遍历同氧化等级铜块网络
       ├─ 对每个遍历到的铜块，检查 4 条边的 edgeGrid 信号变化
       ├─ 上升沿 → 持久化 SignalTracker 获取周期 → PhaseEvent → ChannelState
       ├─ 域内去重计 n，eff_δ_sum = Σ√|Δ_i|
       └─ 最佳域合因子 → RE → onEventEnergy() → endTick()
```

> ⚠️ **priority 必须保持 3**：电力采样依赖红石（priority 2）已算完的 edgeGrid。
> 当前占用：水桶 0、水车 1、红石 2、**电力 3**。

---

## 2. 数据结构

### 2.1 PhaseEvent —— 相位事件

一条边信号上升沿触发的事件，由 `SignalTracker` 根据历史上升沿间隔计算周期后生成。

| 字段 | 说明 |
|---|---|
| `sourceId` | 事件源唯一标识（edgeKey = (slot << 2) \| dir） |
| `period` | 上升沿间隔 EMA 估计的周期（tick） |
| `offset` | 相位偏移（相对于域内最早上升沿的 mod 周期） |
| `delta` | 跳变幅度 \|Δ\|（信号值变化量） |
| `tick` | 事件发生的 tick 计数 |

### 2.2 SignalTracker —— 上升沿跟踪器

为每条边信号维护的持久化跟踪器（跨 tick 保存在 `ContainerPowerData` 中）。

| 方法 | 说明 |
|---|---|
| `onRisingEdge(tick, delta)` | 记录上升沿时间与幅度，按间隔 EMA 估计周期（详见 §3.2） |
| `period()` | 返回当前估计周期（≥1 个间隔 / 2 个上升沿后有效，否则 0=检测中） |
| `offset()` | 返回上升沿在周期内的相位：`lastRisingTick mod period`（0~P-1） |

### 2.3 ChannelState —— 通道状态

单台发电机的信号接收通道，管理相位域。

| 成员 | 说明 |
|---|---|
| `domains` | period → PhaseDomain 映射 |
| `onPhaseEvent(event, pref)` | 接收事件，路由到对应域 |
| `tickCleanup(now, pref)` | 清理过期域（>128 tick 无事件） |
| `bestFactor(pref)` | 返回最佳域的合因子 |
| `bestPeriod(pref)` | 返回最佳域的周期 |

### 2.4 PhaseDomain —— 周期域

| 成员 | 说明 |
|---|---|
| `period` | 域周期（tick） |
| `offsets` | 已观察到的不同偏移量集合（去重 = 相数 n） |
| `deltaByOffset` | offset → \|Δ\| 映射（用于 √|Δ| 求和） |
| `lastEventTick` | 最近一次事件 tick |
| `n()` | 返回去重后的相数 |
| `effDeltaSum()` | 返回 Σ√\|Δ_i\|（各不同偏移的 √|Δ| 求和） |

### 2.5 GeneratorState / ContainerPowerData

| 成员 | 说明 |
|---|---|
| `preferredPeriod` | = 堆叠数 clamp [0,64]；<2 即宽带态 |
| `channel()` | 单通道事件接收 |
| `onEventEnergy(re)` | 跳变即能量事件：RE 直接累加，无功率流中间态 |
| `endTick()` | 每 tick 末尾：EMA 更新（α=0.125）+ tick 计数 |
| `getEmaPowerFe()` | EMA 功率 × K 换算为 FE/t |
| `getOrCreateEdgeTracker(edgeKey)` | 持久化边信号跟踪器，跨 tick 跟踪周期 |

---

## 3. 核心算法

### 3.1 铜块网络传播（BFS）

```
每 tick，每台发电机：
  ① 获取自身氧化等级
  ② BFS 从自身出发遍历同氧化等级铜块：
     - 四个方向（上下左右）检查同氧化等级铜块
     - 铜灯（泡）不导电，跳过
     - 非铜块物品跳过
  ③ 对每个遍历到的铜块槽位，检查 4 条边：
     - 读 edgeGrid[slot][dir] vs prevEdgeGrid[slot][dir]
     - 信号无变化 → 跳过
     - 上升沿（delta > 0）→ SignalTracker.onRisingEdge()
     - 周期已知 → PhaseEvent → ChannelState.onPhaseEvent()
  ④ tickCleanup 清理过期域
  ⑤ 取最佳域 → 合因子 → RE 能量入账
```

### 3.2 周期估计（SignalTracker）

周期为**盲测**得到：跟踪器不读取偏好周期 `pref`，而是纯粹测量「相邻两次上升沿之间的间隔」来反推真实周期 P（参见 §1.3 数据流 / `SignalTracker.onRisingEdge`）。

```
上升沿间隔 EMA（α = 0.5）：
  间隔 = 本次上升沿 tick - 上次上升沿 tick
  if 这是第 1 个间隔:  periodEMA = 间隔            // 直接采用，不做平均
  else:                periodEMA += (间隔 - periodEMA) * 0.5
```

`period()` 输出守卫：`intervalsSeen >= 1 && periodEMA >= 1.5` 才返回 `round(periodEMA)`，否则返回 0（即 tooltip 的「检测中」）。`periodEMA < 1.5` 过滤极短抖动，避免把噪声当成周期。

`offset()` = `lastRisingTick % period()`，即最后一次上升沿在周期内的相位（0 ~ P-1）。它是后续各发电机间相位差 Δ 与「相数 n」的源头（见 §3.3）。

#### 为什么长周期会「慢慢」找到
瓶颈不在算法，而在**样本稀疏**：一个周期只产生一次上升沿，所以跟踪器每 P tick 才能拿到一个间隔样本。

- 第 1 个上升沿：只记时间，无间隔 → `period() = 0`
- 第 2 个上升沿（再过 P tick）：产生第 1 个间隔 → `period()` 首次非零
- 之后每个 P tick 更新一次，EMA 逐步收敛

因此即便 P 很大（100+ tick），算法也能找到——它只是「测间隔、做平均」，对周期无上限；代价是样本更稀、收敛更慢。例如 P=130 时，第一个周期值约在 t≈130 tick 出现，再累积 2~3 个样本（每个再等 130 tick）才稳定，体感上即十几秒后 tooltip 才锁定周期。

#### α 的意义
α=0.5 收敛较快（2~3 个样本即贴到真实 P），同时平滑掉信号周期的轻微抖动，使 tooltip 周期读数稳定不跳变。需要更快锁定可略增 α，需要更强抗抖可略减 α（代价是收敛更慢）。

### 3.3 相位域分组与 n（PhaseDomain）

```
① 按 period 整数分域（不同周期各自独立）
② 域内 offset 去重：
   offset = event.tick mod period（= 上升沿在周期内的相位，来自 SignalTracker.offset()）
   同一 offset 的事件合并、不同 offset 各自独立
③ n = 不同偏移量个数（同偏移合并不算多次）
④ eff_δ_sum = Σ√|Δ_i|（各偏移的 |Δ| 分别 √ 后求和）
```

#### 3.3.1 网络涌现多相（n 可突破单块上限）

`runBfs` 从发电机槽位出发扫描**整个同锈蚀级涂蜡铜块网络**，而非仅发电机自身。网络上每个被充能的铜块槽位、其每个方向（共 4 个 dir）都对应一个独立的 `SignalTracker`（`edgeKey = (slot<<2)|dir`）。因此「参与相位的边数」= 网络中被充能的 (slot,dir) 条数，而非「红石信号源数」。

- **单块硬上限 n≤4**：孤立涂蜡铜块只有 4 个 dir，最多 4 个相位源。
- **集群打破上限**：网络规模（铜块数 × 4 dir）越大，可被独立充能并各自记相位的边越多，n 可远大于 4——少数红石输入经网络传播后可涌现为多相。这是红石传播延迟 + 网络拓扑 + 每边独立相位检测三条规则自然组合的产物，无任何规则专门设计「放大输入」。
- **边界**：若网络中多条边**同步**上升（同一 tick 相位相同），其 `offset = tick mod period` 相等，`deltaByOffset.merge` 合并为同一 bucket → n 塌缩为 1，无增益。真实多相增益要求拓扑天然制造不同相位（不同传播距离 / 延迟）。

##### 3.3.1.1 相位错开的真实来源（先澄清一个误区）

> **铜块之间的「逐格传播延迟」并不存在。** `phase2Propagation`
> （`ContainerRedstoneData.java:470`）是标准 BFS，但整个 `while(!queue.isEmpty())`
> 在**同一次 `calculate()` 调用、即同一个 game tick 内走完**：铜块与红石粉的边信号
> 在同 tick 内全部算出，邻居通过 `queue.add` 同 tick 续跑。因此铜块网络自身传播
> **没有跨 tick 的逐格延迟**，所有连通边在同一 tick 被充能。

真正让网络中不同边在 `lastRisingTick`（全局 tick 时钟）上错开、从而产生多个不同
`offset = lastRisingTick mod period` 的，只有以下两类来源：

1. **中继器的档位延迟**（`ContainerRedstoneData.java:30`、`:585`）——
   唯一真实的跨 tick 延迟。中继器收到输入后置
   `delayTimer = delay() × TICKS_PER_REPEATER_STEP`（每红石刻 = 2 game tick），
   每 tick 在 `phase0CountdownDelays` 倒数，数完前输出边不上升。
   信号经过 N 个中继器 → 比直连路径晚 `2N` 个 tick 到达下游铜块 → 下游边
   `lastRisingTick` 整体平移 → offset 改变。
2. **各路输入的固有相位差**——正如 4 路信号「信号间隔 = 0/1/2/3、持续时长 =
   4/3/2/1」，4 路本身就是在全局时钟上错相的周期信号，于不同 tick 上升。

`runBfs`（`LivingWaxedCopperFunction.java:236`）对网络上每条被充能的 (slot,dir) 边
独立记 `lastRisingTick`，`SignalTracker.offset()` 取 `lastRisingTick mod period`。
两类来源叠加（错相输入 ≈ 4 个 offset + 不同中继器数量的路径再分裂出若干错开到达
tick + 网络不同位置边各自采样），`PhaseDomain` 去重后即得 n=7 乃至更大的多相值。

> 推论：若网络是「纯铜块 + 同步输入、无任何中继器」，所有边将在同一 tick 上升
> → offset 全部相等 → n 塌缩为 1。多相涌现依赖**错相输入**或**中继器档位延迟**
> 制造到达时刻差，而非铜块传播本身。

### 3.4 合因子计算（ChannelState.bestFactor）

```
遍历所有域，取合因子最大的域：
  n = domain.n()
  eff_δ_sum = domain.effDeltaSum()
  tuningEff = tuningEfficiency(|period − pref|, pref)
  unlock = tuningEff × n / pref
  合因子 = eff_δ_sum^(1 + unlock)
  能量(RE) = 合因子 × period
```

### 3.5 氧化等级网络隔离

| 氧化等级 | 物品 | 网络 |
|---------|------|------|
| 0（新鲜） | `waxed_copper_block` | 只连通新鲜铜块 |
| 1（暴露） | `waxed_exposed_copper` | 只连通暴露铜块 |
| 2（锈蚀） | `waxed_weathered_copper` | 只连通锈蚀铜块 |
| 3（氧化） | `waxed_oxidized_copper` | 只连通氧化铜块 |

- 不同氧化等级**不互通**，必须用同种铜块搭建网络
- 铜灯（泡）**不参与网络传播**，仅作为储能
- 涂蜡铜块雕刻/切制/格栅按相同氧化等级计入网络

### 3.6 能量入账（每 tick 每发电机一次）

```
每 tick 末，每台发电机从最佳域取合因子：
  factor = channel.bestFactor(pref)
  period = channel.bestPeriod(pref)
  if factor > 0 && period > 0:
    re = PowerMath.eventEnergyRe(factor, period)
    powerData.onEventEnergy(re)
```

- 每 tick 每发电机只入账一次（从最佳域取）
- 最佳域 = 合因子最大的域（自然选择最优周期）
- 能量直接累加到 `ContainerPowerData`，tick 末统一分配

### 3.7 网络级共振（不同锈蚟级之间的「和声」）

块级感应是「铜块采样边信号」，网络级共振是「锈蚟级之间互相感应」——
**同一套机制抬高一个维度**。这也让 §3.5 的「氧化等级网络隔离」从单纯的消极隔离，
变成有积极意义的机制：锈蚟级不再只是「互不连通」，而是**和弦里的声部**。

#### 声部单位是「锈蚟级」，不是 BFS 连通块

同一锈蚟级内的多个互不相连的连通块，其出力**直接相加**后作为一个声部参与共振；
只有不同锈蚟级之间才谈共振。

> ⚠️ 这不只是简化，而是 **`R ≤ 4` 的结构性前提**。若按连通块计声部，玩家把同一
> 锈蚟级拆成若干小簇即可刷高 N，退化为曾导致避雷针共振模型被废弃的 N×(N−1) 膨胀。

#### 公式

设容器内有 **N 个「有出力」的锈蚟级**（N = 1~4），各自基础出力为 `Aₖ`：

```
① 平衡度   s = (Π Aₖ)^(1/N) ÷ (Σ Aₖ / N)     // 几何平均 ÷ 算术平均（AM-GM）
                                              // s ∈ [0,1]，各网出力相等时 = 1
② 共振倍率 R = 1 + (N − 1) × s                // R ∈ [1, N] ⊆ [1, 4]
③ 增  益   容器本 tick 总发电 = (Σ baseRe[k]) × R^exp，exp = 2
```

| 要素 | 说明 |
|---|---|
| 平衡度 s | 各声部出力的接近程度；尺度无关（只关心比例，不关心绝对值） |
| 声部数 N | 有出力的锈蚟级数；零出力的级不计入 |
| exp = 2 | `PowerMath.RESONANCE_EXPONENT`，**唯一的强度标定旋钮** |

#### 边界性质（结构性，非靠常数压住）

| 性质 | 保证 |
|---|---|
| 下限 R ≥ 1 | 共振永不「扣发电量」，最差就是不共振 |
| 孤网 R = 1 | 只建一种锈蚟级拿不到任何加成 |
| 满共振 R = 4 | 4 个锈蚟级出力全相等，增益 ×16 |
| 上界 R ≤ 4 | 声部数被锈蚟级数硬顶死，不可能失控 |
| 无回代 | 单遍前馈（见下方铁律），不存在指数发散 |

#### 三条铁律（安全红线）

1. **共振只读「共振前」的基础值。** 每 tick 只做一次：
   `基础出力 → 更新 EMA → 取增益 → 乘到本 tick 发电量`。
   增益**绝不**回灌基础出力，因此不存在 `A↑ → B↑ → A↑` 的回代环。
2. **每锈蚟级 EMA 只跟踪基础出力。** `ContainerPowerData.updateOxidationEma()`
   的入参必须是 `baseReByOx`（共振前）。若喂入乘过增益的值即形成回代环。
3. **EMA 必须截断归零。** 指数衰减数学上达不到 0；不截断则停止发电的锈蚟级会以
   极小非零值被永久算作活跃声部，把平衡度永久压死且无法自愈。
   `ContainerPowerData.EMA_EPSILON = 1e-6`（浮点卫生常数，非平衡常数），
   典型量级约 155 tick 归零。

#### 每 tick 流程

```
① 各发电机照旧算基础 RE（§3.1~§3.6 完全不变）
   → 按锈蚟级累加：baseRe[k] = Σ 该锈蚟级「所有连通块」内发电机的基础 RE

② powerData.updateOxidationEma(baseRe)      // 只吃基础值
③ gain = powerData.resonanceGain()          // = R^exp，只读 EMA

④ generatedRe = powerData.drainGeneratedRe()
   if (gain > 1 && generatedRe > 0) generatedRe = round(generatedRe × gain)
⑤ 照旧分配入铜灯（§3.6 v17.5）
```

注意 `gain` 由**平滑的 EMA** 算出（避免逐 tick 跳变导致闪烁），
但作用在**本 tick 的实际基础出力**上。

#### 场景对照（总量 400 的几种分配）

| 各锈蚟级出力 | N | s | R | 增益 | 总发电 |
|---|---|---|---|---|---|
| [400] | 1 | 1.000 | 1.00 | 1.00 | 400 |
| [200, 200] | 2 | 1.000 | 2.00 | 4.00 | 1600 |
| [133,133,134] | 3 | 1.000 | 3.00 | 9.00 | 3600 |
| [100,100,100,100] | 4 | 1.000 | 4.00 | 16.00 | **6400** |
| [250, 150] | 2 | 0.968 | 1.97 | 3.87 | 1550 |
| [100,100,100,50] | 4 | 0.961 | 3.88 | 15.08 | 5278 |
| [100,100,100,10] | 4 | 0.726 | 3.18 | 10.09 | 3129 |
| [100,100,100,1] | 4 | 0.420 | 2.26 | 5.11 | **1538** |

**「凑数」不划算**：3 级各 100（总 2700）> 3 级各 100 外挂一个 1 的第 4 级
（总 1538）。硬塞弱声部会拉低总出力，钻空子自动失效，无需额外的失衡惩罚项。

#### 强度校准

满共振 ×16 看似激进，但真正的成本大头是**材料获取**：集齐 4 种锈蚀的涂蜡铜块
需要养蜂取蜜脾 + 逐级风化（随机 tick 驱动、逐级变慢的时间门槛）+ 逐级涂蜡收集，
是**递进式养成门槛**而非重复建造成本。且「各网出力须平衡」意味着
**共振上限被最稀有的氧化级卡住**——这与平衡度 s 的短板效应从两个方向收敛到
同一瓶颈。超线性奖励对应超线性成本，故 exp = 2 成立。

若实测发现材料门槛低于预期，只改 `PowerMath.RESONANCE_EXPONENT`
（2.0 → 1.5 即把满共振降到 ×8），不动任何结构。

#### 实现落点

| 位置 | 内容 |
|---|---|
| `PowerMath.balanceFactor` | 平衡度 s（GM/AM） |
| `PowerMath.resonanceFactor` | 共振倍率 R = 1 + (N−1)s |
| `PowerMath.resonanceGain` | 增益 R^exp |
| `PowerMath.RESONANCE_EXPONENT` | 强度旋钮（默认 2.0） |
| `ContainerPowerData.emaPowerByOxidation` | 4 槽按锈蚟级的基础 EMA |
| `ContainerPowerData.updateOxidationEma` | EMA 推进 + 归零截断 |
| `LivingWaxedCopperFunction.accountEnergy` | 按锈蚟级累加基础出力 |
| `LivingWaxedCopperFunction.tickContainerData` | tick 末单遍套用增益 |
| `NetworkResonanceTest` | 16 项（含回代安全、上界、EMA 衰减） |

---

## 4. 记账模型（RE / FE）

| 单位 | 用途 | 特点 |
|---|---|---|
| **RE**（红电单位） | 内部记账：`每跳 RE = 合因子 × P` | 零硬常数，全部涌现量 |
| **FE**（NeoForge Energy） | 对外：电池、IEnergyStorage（阶段四） | 通用能源货币 |

边界换算：`FE = RE × K`，`K = 1/16`（`PowerMath.RE_TO_FE`，全 mod 唯一标尺常量）。
整体调产量只改这一个数，**严禁**把 K 绑到最大周期等设计旋钮上。

---

## 5. 与红石系统的集成

- 铜块网络传播依赖 `ContainerRedstoneData` 的逐方向访问器 `getEdgeValue(slot, dir)` /
  `getPrevEdgeValue(slot, dir)` 与 `EDGE_COUNT = 4`；
- 信号源写边**无条件**（涂蜡槽位天然可采样），传播层零改动；
- 发电机 BFS 只读边信号，不写任何信号；
- 容器级缓存完整镜像红石协议：`ContainerLivingItemHandler.POWER_DATA_CACHE` +
  过期清理（120s）+ `clearAllCaches`（ServerStoppedEvent）+ 位置反向索引。

---

## 6. 测试

| 测试文件 | 项数 | 覆盖 |
|---|---|---|
| `PowerMathTest` | 5 | 调谐效率曲线、合因子计算、√|Δ| 求和、K 换算 |
| `ContainerPowerDataTest` | 6+ | 单路锁相、三相 6t 部分解锁、五相 5t 满相、杂讯排除、同相合并、EMA 账本 |
| `WaxedCopperStorageTest` | 16 | 发电直存分配、无铜灯弃、满溢、模组容器取电、充电、容量 clamp、超取、取消活化排除、EMA 功率 |
| `BulbItemEnergyStorageTest` | 6 | 双向充放、容量 clamp、simulate、拆分守恒、线性读数 |

用例数值直接取自 [红电波形分析表.md](../红电波形分析表.md) 的手工演算，
实现与文档互为验证。

---

## 7. 实施状态

| 规划步骤 | 状态 | 说明 |
|---|---|---|
| Step 7 记账（事件 + RE + EMA） | ✅ | `ContainerPowerData` |
| Step 8 铜块网络传播 | ✅ | BFS 遍历同氧化等级铜块，边信号检测 |
| Step 9 相位域合因子 | ✅ | √|Δ| 求和、域内去重 n、调谐解锁 |
| Step 10 氧化等级网络隔离 | ✅ | 新鲜/暴露/锈蚀/氧化互不连通 |
| Step 11 铜灯储能 | ✅ | 发电直存 + 按盏电量 DataComponent（无池） |
| Step 12 IEnergyStorage | ✅（技术验证通过） | 原版容器 BE 注册，游戏内待实测 |
| Step 13 活避雷针 | ⏳ 阶段四后 | 供需分配 |
| Tooltip 仪表盘 | ✅ | `LivingWaxedGeneratorData` 检测值写回 + 槽位同步 + 双语渲染 |

---

## 8. 已知限制

| 限制 | 影响 | 计划 |
|---|---|---|
| 非 BE 容器不支持 | 充电宝搬入非 BE 容器时暂不可对外取电（发电本就需要 BE） | 有需求再补 Block 级注册 |
| **Pipez 能量管道不兼容** | Pipez（master 线）使用新 Transfer API 的 `Energy.BLOCK`（EnergyHandler 类型），非 FE 的 `EnergyStorage.BLOCK` | 用 Mekanism 电缆取电；中期软依赖注册 EnergyHandler 适配 |
| 充电量化零头 | 剩余容量 < 1 FE 的部分不接收（整 FE 量化） | 保守方向（杜绝凭空造电），量级 ≤ 1 FE |
| 发电机移除后 EMA 冻结 | 功率读数不清零（数据过期清理兜底） | 观察后再定 |
| 事件 tick 计数随容器活跃度冻结 | 容器卸载期间周期被拉长 → 重锁 | 符合直觉，保留 |
| 取电跨 FE 边界向上取整 | 每次取电最多多拿 count−1 mFE（49 盏时 ≤ 0.048 FE） | 设计取舍，观察后再定 |
| 铜灯不导电 | 铜灯不能作为网络传播中继节点 | 设计如此，铜灯仅作储能 |