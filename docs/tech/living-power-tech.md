<!-- markdownlint-disable -->

# Living Power (活涂蜡铜块 · 红电发电) 技术文档

> **文档版本**: v5.1（v19：记账跳变门控 + 相位解读三元件——雕文移相 / 切制裂相 / 格栅加法；拓扑统一至锈级单维度；切制 H/V 双通道退役）
> **最后更新**: 2026-09-08
> **适用版本**: Minecraft 1.21.1
> **规划文档**: [红电系统.md](../红电系统.md)（v19，公式 v3）

## 目录
1. [架构概览](#1-架构概览)
2. [数据结构](#2-数据结构)
3. [核心算法](#3-核心算法)
4. [记账模型（RE / FE）](#4-记账模型re--fe)
5. [与红石系统的集成](#5-与红石系统的集成)
6. [测试](#6-测试)
7. [实施状态](#7-实施状态)
8. [已知限制](#8-已知限制)
9. [附录：完整公式链](#9-附录完整公式链)

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
| 铜块网络 | 按网络组件遍历（TopoKey + rep 锚点 BFS + copyFrom 共享） | 不同锈蚀级形成独立网络，同组件多机共享相位历史 |

### 1.2 关键类与职责

| 类名 | 位置 | 职责 |
|------|------|------|
| `LivingWaxedCopperFunction` | `domain/power/` | 功能入口：`canApply`（涂蜡全家族 20 件）、`getPriority()=3`、铜块网络按组件遍历 + 边信号检测 |
| `PowerMath` | `domain/power/` | 纯函数：合因子、调谐效率、√|Δ| 求和、RE 记账、K 换算 |
| `PhaseEvent` | `domain/power/` | 相位事件记录：sourceId、period、offset、delta、tick |
| `ChannelState` | `domain/power/` | 通道状态：相位域分组计 n、eff_δ_sum 计算、合因子 |
| `PhaseDomain` | `domain/power/`（ChannelState 内部类） | 周期域：按 period 分域，offset 去重，Δ 跟踪 |
| `SignalTracker` | `domain/power/`（LivingWaxedCopperFunction 内部类） | 上升沿跟踪器：间隔 EMA 估计周期、偏移量计算 |
| `GeneratorState` | `domain/power/` | 单台发电机状态：偏好周期（= 堆叠数）、单通道事件接收 |
| `ContainerPowerData` | `domain/power/` | 容器级账本：RE 事件累加、EMA 功率、tick 计数、边信号跟踪器持久化、按锈级基础 EMA（共振 + v18 分账） |

除 `LivingWaxedCopperFunction` 外全部为**纯 Java 类**（零 MC 依赖），可直接 JUnit 驱动。

### 1.3 调度与数据流

```
processContext() 每 game tick：
  ├─ priority 2：红石 calculate()（edgeGrid 双缓冲刷新）
  └─ priority 3：LivingWaxedCopperFunction.tickContainerData()
       ├─ 收集发电机槽位（铜灯跳过）
       ├─ 按网络组件遍历：每台发电机按形态归入组件 ComponentId = (TopoKey, rep, channelIdx)
       ├─ 每组件仅锚点（最小槽位 rep）跑一次 BFS，检测边信号 → PhaseEvent → ChannelState
       ├─ 其余发电机 copyFrom 锚点 ChannelState（相位历史深拷贝同步，O(域) 极廉价）
       ├─ 上升沿 → 持久化 SignalTracker 获取周期 → ChannelState.onPhaseEvent()
       └─ 逐发电机 accountEnergy：用各自 pref 从共享/复制域取最佳 → RE → endTick()
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
| `getEmaPowerFe()` | EMA 功率 × K 换算为 FE/t（per-generator / 按锈级两个口径，v18 拆除容器总账） |
| `getOrCreateEdgeTracker(edgeKey)` | 持久化边信号跟踪器，跨 tick 跟踪周期 |
| `emaPowerByOxidation` | 4 槽按锈级的基础 EMA（共振口径 + 锈级功率读数，只吃 `baseReByOx[k]`） |
| `updateOxidationEma` | EMA 推进 + 归零截断（`EMA_EPSILON = 1e-6`） |
| `getLevelEmaPowerFe(oxidation)` | 指定锈级的 EMA 功率读数（v18，取代旧容器总功率） |
| `activeOxidationLevels()` | 活跃锈级数 N（1~4；0 = 无任何发电） |

---

## 3. 核心算法

### 3.1 铜块网络传播（按网络组件遍历）

```
每 tick，铜块网络传播**按「网络组件」遍历**，而非逐发电机各跑一次 BFS：

  ① 收集发电机，每台按形态归入组件 ComponentId = (TopoKey, rep, channelIdx)
       - TopoKey = (氧化级, 线圈形态, 轴, 入边, 出边)：决定 BFS 连通性与边检测方向
       - rep = 组件内最小铜块槽位（稳定锚点，保证跨 tick 同一网络映射到同一 ChannelState）
  ② 每组件仅锚点（rep 槽位）跑一次 BFS 遍历同氧化等级铜块：
     - 四个方向（上下左右）检查同氧化等级铜块
     - 铜灯（泡）不导电，跳过
     - 非铜块物品跳过
  ③ 锚点对遍历到的每个铜块槽位，检查 4 条边（**入边**，见 §5）：
     - 读 getIncomingEdgeValue(slot, dir) vs getPrevIncomingEdgeValue(slot, dir)
     - 信号无变化 → 跳过
     - 上升沿（delta > 0）→ SignalTracker.onRisingEdge()
     - 周期已知 → PhaseEvent → ChannelState.onPhaseEvent()
  ④ 同组件其余发电机 ChannelState.copyFrom(锚点)（相位历史深拷贝同步，O(域) 极廉价）
  ⑤ tickCleanup(maxPref) 清理过期域；逐发电机 accountEnergy 取最佳域 → 合因子 → RE

> **重构要点**：旧实现每台发电机各自 BFS 遍历同一氧化级连通块（同边集被扫 G 次，
> G 倍冗余）。现同 (TopoKey, rep, channelIdx) 组件只让锚点跑一次 BFS，其余 `copyFrom`
> 复用结果。`copyFrom` 安全的前提是 `ChannelState.onPhaseEvent(event, pref)` 中 `pref`
> 完全不参与分域（域仅按 `event.period()` 分桶），故同网络多机共享同一 ChannelState 实例
> 100% 安全；调谐偏好仅在读取时 `bestFactor/bestPeriod(pref)` 各自选型，逐机 pref 敏感保留。
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

#### 虚假周期误读的产生与自愈（EMA 收敛路径）

`SignalTracker` 的周期估计依赖 `lastRisingTick` 记录的「上次上升沿时刻」。当信号中断后恢复时，`lastRisingTick` 保留了中断前的旧时刻，新上升沿会产生一个巨大的间隔，导致 `periodTicks` 从真实值跳到很大的值，再以 α=0.5 的指数衰减逐步收敛回来。

**示例**：信号中断 1000 tick 后恢复（真实周期 P=4）：

```
第0步:  lastRisingTick=200, 信号中断...
第1步:  interval=1200-200=1000 → periodTicks=4+(1000-4)×0.5=502   → period()=502
第2步:  interval=4              → periodTicks=502+(4-502)×0.5=253   → period()=253
第3步:  interval=4              → periodTicks=253+(4-253)×0.5=128.5 → period()=129
第4步:  interval=4              → periodTicks=128.5+(4-128.5)×0.5=66.3→ period()=66
第5步:  interval=4              → periodTicks=66.3+(4-66.3)×0.5=35.1 → period()=35
第6步:  interval=4              → periodTicks=35.1+(4-35.1)×0.5=19.6 → period()=20
第7步:  interval=4              → periodTicks=19.6+(4-19.6)×0.5=11.8 → period()=12
第8步:  interval=4              → periodTicks=11.8+(4-11.8)×0.5=7.9  → period()=8
第9步:  interval=4              → periodTicks=7.9+(4-7.9)×0.5=5.95  → period()=6
第10步: interval=4              → periodTicks=5.95+(4-5.95)×0.5=4.98→ period()=5
第11步: interval=4              → periodTicks=4.98+(4-4.98)×0.5=4.49→ period()=4  ✅ 收敛
```

**每个中间值都创建一个独立的 `PhaseDomain`**（[ChannelState](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/domain/power/ChannelState.java#L113-L121) 按 `event.period()` 分桶），因此单个 `SignalTracker` 就能产生 10 个虚假域。中断时间越长，收敛路径越长，虚假域越多（中断 5000 tick 时收敛需 18 步，从 247502 一路降到 4）。

**BFS 网络遍历放大效应**：`runBfs` 为网络中被充能的每个 `(slot, dir)` 维护独立的 `SignalTracker`。N 个铜块 → 最多 4N 个跟踪器，每个在收敛过程中独立产生自己的虚假域序列 → tooltip 上出现成百上千的周期记录。

**这些虚假域不影响发电**：[`accountEnergy`](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/domain/power/LivingWaxedCopperFunction.java#L597-L617) 的跳变门控只选「本 tick 有跳变」的最佳域（`bestActiveDomain`），虚假域的 `jumpTick` 不与当前 tick 对齐，不会被选中入账。

**自愈机制**：[`tickCleanup`](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/domain/power/ChannelState.java#L190-L220) 每 tick 清理过期域：

```java
long timeout = Math.max(preferredPeriod, d.period) * 2L;
timeout = Math.min(1200, Math.max(32, timeout));
if (currentTick - d.lastEventTick > timeout) it.remove();
```

一旦 `SignalTracker` 收敛到真实周期，虚假域不再收到新事件，超时后自动被移除。超时阈值为 `max(32, 2×P)` 夹 [32, 1200] tick，对周期 4 的信号约 32 tick（~1.6 秒）后虚假域消失，对大周期虚假域最长 1200 tick（~60 秒）后自愈。

> **设计权衡**：EMA 平滑（α=0.5）在稳态下提供优秀的抗抖能力，但每次信号中断→恢复时必然产生一段收敛路径。这是「盲测周期」方案的固有特性——跟踪器不读取偏好周期，仅凭上升沿间隔逆向推断，间隔的剧烈变化需要时间消化。虚假域只影响 tooltip 显示，不影响发电结算，且有超时自愈保证，不会留下后遗症。

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

`runBfs` 从组件锚点（rep 槽位）出发扫描**整个同锈蚀级涂蜡铜块网络**，而非仅单台发电机；同组件其余发电机通过 `copyFrom` 复用该次遍历结果。网络上每个被充能的铜块槽位、其每个方向（共 4 个 dir）都对应一个独立的 `SignalTracker`（`edgeKey = (slot<<2)|dir`）。因此「参与相位的边数」= 网络中被充能的 (slot,dir) 条数，而非「红石信号源数」。

- **单块硬上限 n≤4**：孤立涂蜡铜块只有 4 个 dir，最多 4 个相位源。
- **集群打破上限**：网络规模（铜块数 × 4 dir）越大，可被独立充能并各自记相位的边越多，n 可远大于 4——少数红石输入经网络传播后可涌现为多相。这是红石传播延迟 + 网络拓扑 + 每边独立相位检测三条规则自然组合的产物，无任何规则专门设计「放大输入」。
- **边界**：若网络中多条边**同步**上升（同一 tick 相位相同），其 `offset = tick mod period` 相等，`deltaByOffset.merge` 合并为同一 bucket → n 塌缩为 1，无增益。真实多相增益要求拓扑天然制造不同相位（不同传播距离 / 延迟）。

##### 3.3.1.1 相位错开的真实来源（先澄清一个误区）

> **铜块之间的「逐格传播延迟」并不存在。** `phase2Propagation`
> （`RedstonePropagation.java`）是标准 BFS，但整个 `while(!queue.isEmpty())`
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

`runBfs`（`LivingWaxedCopperFunction.java:233`）对网络上每条被充能的 (slot,dir) 边
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

### 3.6 能量入账（跳变门控，v19）

```
每 tick 末，每台发电机从「本 tick 有跳变」的最佳域取合因子：
  active = channel.bestActiveDomain(pref, now)     // 只在本 tick 有跳变的域中选（n 最大、同 n 周期最近）
  if active == null: 产出 0（域活着但本 tick 无上升沿 → 不入账）
  factor = channel.factorOf(active, pref)
  re = PowerMath.eventEnergyRe(factor, active.period()) × active.jumpCount(now)
  powerData.onEventEnergy(re)
  baseReByOx[发电机锈级] += re                     // 按锈级累加，共振与直存的分账基础
```

- **跳变门控（v19）**：记账回归「跳变即能量事件」——能量 = 合因子 × P × 本 tick
  跳变路数（跳变按 offset 去重，同一振荡器被两条边看到只算 1 跳）。
  修复旧「每 tick 无条件入账合因子×P」的两个问题：
  1. **频率中性化反转**：旧口径下平均功率 = 合因子×P，随周期线性增长——慢时钟
     无代价碾压快时钟（整数倍谐波调谐效率恰为 1.0，可无限放大，4t 与 68t 差 17 倍）；
     门控后 4t 跳 3 次 × (F×4) = 12t 跳 1 次 × (F×12)，中性化恢复。
  2. **停机虚能量**：域存活窗口（max(32, 2×P) tick）内照常白拿——门控后停机即停。
- 域的历史状态（n / eff_δ_sum）仍反映全部存活相位，门控只决定「本 tick 有没有
  真实跳变可入账」，不改变域的质量评估口径。
- 只记上升沿（下降沿由裂相器单独解读，见 §3.9）：文档旧「每周期跳 2 次」的 ×2
  常数按此折半，由 K=1/16 吸收。
- tick 末共振增益按锈级套用后，逐锈级分配入同色铜灯（§3.7 / 锈级专属通道）。
- EMA 读数随门控变为脉冲推进（事件 tick 抬升、间隔衰减）。
- **显示均值与记账分离（v19.1）**：脉冲 EMA 对高频信号有固定峰谷纹波（P=2 时约 1.4×，超过量化步长 → tooltip 数字闪烁）。tooltip / 锈级柱状图 / 共振读数（平衡度 s、增益 R²、活跃锈级数 N）一律使用**显示窗口均值**：per-generator 窗口 = ceil(32/pref)×pref（偏好周期整倍数，稳态零纹波），per-oxidation 窗口 = 32t 固定；记账 EMA（α=1/8）保持快响应不动。共振增益由平滑窗口值算出、仍作用在本 tick 基础出力（v18 语义），三条铁律结构不变。
- **毫 FE（mFE）定点显示（v19.1）**：K=1/16 下整数 FE 粒度会吞掉 <0.5 FE/t 的读数——显示链遥测字段改毫 FE 定点（`emaPowerMilliFe` / `levelEmaPowerMilliFe` / `levelPowerMilliFe`），tooltip 格式化 ≥1 FE 显示整数、不足两位小数（如 0.94 FE/t）。

### 3.7 网络级共振（不同锈蚟级之间的「和声」）

块级感应是「铜块采样边信号」，网络级共振是「锈蚟级之间互相感应」——
**同一套机制抬高一个维度**。这也让 §3.5 的「氧化等级网络隔离」从单纯的消极隔离，
变成有积极意义的机制：锈蚟级不再只是「互不连通」，而是**和弦里的锈级**。

#### 锈级单位是「锈蚟级」，不是 BFS 连通块

同一锈蚟级内的多个互不相连的连通块，其出力**直接相加**后作为一个锈级参与共振；
只有不同锈蚟级之间才谈共振。

> ⚠️ 这不只是简化，而是 **`R ≤ 4` 的结构性前提**。若按连通块计锈级，玩家把同一
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
| 平衡度 s | 各锈级出力的接近程度；尺度无关（只关心比例，不关心绝对值） |
| 锈级数 N | 有出力的锈蚟级数；零出力的级不计入 |
| exp = 2 | `PowerMath.RESONANCE_EXPONENT`，**唯一的强度标定旋钮** |

#### 边界性质（结构性，非靠常数压住）

| 性质 | 保证 |
|---|---|
| 下限 R ≥ 1 | 共振永不「扣发电量」，最差就是不共振 |
| 孤网 R = 1 | 只建一种锈蚟级拿不到任何加成 |
| 满共振 R = 4 | 4 个锈蚟级出力全相等，增益 ×16 |
| 上界 R ≤ 4 | 锈级数被锈蚟级数硬顶死，不可能失控 |
| 无回代 | 单遍前馈（见下方铁律），不存在指数发散 |

#### 三条铁律（安全红线）

1. **共振只读「共振前」的基础值。** 每 tick 只做一次：
   `基础出力 → 更新 EMA → 取增益 → 乘到本 tick 发电量`。
   增益**绝不**回灌基础出力，因此不存在 `A↑ → B↑ → A↑` 的回代环。
2. **每锈蚟级 EMA 只跟踪基础出力。** `ContainerPowerData.updateOxidationEma()`
   的入参必须是 `baseReByOx`（共振前）。若喂入乘过增益的值即形成回代环。
3. **EMA 必须截断归零。** 指数衰减数学上达不到 0；不截断则停止发电的锈蚟级会以
   极小非零值被永久算作活跃锈级，把平衡度永久压死且无法自愈。
   `ContainerPowerData.EMA_EPSILON = 1e-6`（浮点卫生常数，非平衡常数），
   典型量级约 155 tick 归零。

#### 每 tick 流程

```
① 各发电机照旧算基础 RE（§3.1~§3.6 完全不变）
   → 按锈蚟级累加：baseRe[k] = Σ 该锈蚟级「所有连通块」内发电机的基础 RE

② powerData.updateOxidationEma(baseRe)      // 只吃基础值
③ gain = powerData.resonanceGain()          // = R^exp，只读 EMA

④ 各锈级实发（v18 锈级纯度归属）：
   voiceRe[k] = gain > 1 ? round(baseRe[k] × gain) : baseRe[k]
⑤ 逐锈级分配入同锈级铜灯（§3.6 v18 锈级专属通道）：
   voiceRe[k] → 按剩余容量比例直存 getOxidationLevel(bulb) == k 的灯堆
   → k 锈级无同色灯则该锈级弃
```

注意 `gain` 由**平滑的 EMA** 算出（避免逐 tick 跳变导致闪烁），
但作用在**本 tick 的实际基础出力**上。`Σ voiceRe[k]` 与旧口径
`round(Σ baseRe[k] × gain)` 数值上可能差 ±1 RE（逐级舍入 vs 总量舍入），
以逐级舍入为准——保证每个锈级的账目自洽。

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
（总 1538）。硬塞弱锈级会拉低总出力，钻空子自动失效，无需额外的失衡惩罚项。

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
| `LivingWaxedCopperFunction.tickContainerData` | tick 末单遍套用增益（v18：逐锈级 `voiceRe[k] = round(baseReByOx[k] × gain)`） |
| `LivingWaxedCopperFunction.distributeToBulbs` | v18：直存时按 `getOxidationLevel(bulb) == k` 过滤灯堆 |
| `NetworkResonanceTest` | 16 项（含回代安全、上界、EMA 衰减） |

---

### 3.8 相位解读三元件（v19：雕文移相 / 切制裂相 / 格栅加法）

**普通铜块发电机的基准约束：单通道、每 tick 只从「本 tick 有跳变」的最佳域入账。**
三形态各自「解读」输入信号、派生新相位贡献给网络——每个派生相位占一个真实元件
槽位（「每条相线真实建造」演化为「每个派生相位真实占用」），n ≤ P 与解锁度公式
不动，无数值倍率。

| 形态 | 解读的是什么 | 规则 | 玩法 |
|---|---|---|---|
| **雕文 = 移相器** | 信号的「位置」 | 对输入方向上的每路锁相波形 (P, φ, δ)（真实边 ~~+ 输入方向邻居的注册表驻波~~【链式组合已暂时关闭，见下】），派生 (P, (φ+1) mod P, δ) | 单级移相：给一路真实输入延迟 1 tick；~~k 台首尾相连 = 任意偏移延迟线，解锁奇数偏移制造~~（链已关） |
| **切制 = 裂相器** | 信号的「另一半」 | 每条边的下降沿波形（独立跟踪器）直接登记为派生相位 (P, φ_f, \|Δ\|) | 一个方波贡献 2 个反相相位；P=2 时钟 + 1 台切制 → n=2 满相 |
| **格栅 = 相位加法器** | 信号间的「关系」 | 汇集 4 条边的真实锁相波形按周期分桶，对 ≥2 路的桶派生 (P, Σφᵢ mod P, min δᵢ) | 多路相位合并出新相位；去重诚实（和撞已有相位不虚增 n） |

> **⚠️ 移相链暂时关闭（2026-09-08）**：`interpretShifter` 的「输入二：读输入方向
> 邻居的注册表驻波」已注释停用（代码保留）。原因：链式组合允许任意频率信号
> 堆出任意偏移——单台雕文只要有足够长的链就能凑满相（n = P），满相增益的
> 成本退化为纯材料堆叠，绕过「真多相要靠布局与时序理解」的核心设计，超模。
> 关闭后雕文与切制/格栅同口径：只读真实边信号（单级移相 φ+1 保留）。
> 「链而非环」的结构性防环论证仍然成立，重新启用时无需重审防环。
> 重新启用前需要先设计增益约束（如：链式派生的 δ 衰减 / n 折算上限 /
> 链长度入解锁度公式等）。

**基建（派生相位注册表）**：

- `ContainerPowerData.phaseRegistry`：slot → `List<DerivedPhase(period, offset, delta, kind, updatedTick)>`，跨 tick 持久；
- **注入**：BFS 访问到槽位时，`now ≡ offset (mod P)` 的驻波视为上升沿注入通道——与真实采样同权、走同一套跳变门控与 offset 去重；
- **两阶段提交**：解读先写草稿、统一写回——读取的邻居注册表一律是上一 tick 状态，无槽位处理顺序依赖；
- **活性**：输入源停跳超过 `PowerMath.aliveWindow`（= 域超时口径 max(32, 2×P) 夹 [32,1200]）→ 解读停止 → 驻波经 `pruneRegistry` 修剪——死源不发电；
- **防环（结构性，无检测代码）**：组合只经移相链（雕文读输入方向邻居）；加法器只读自己的真实边、裂相器无外部输入。移相环的每个成员的输入边都是蜡-蜡死边（无种子）→ 注册表恒空、环自熄——不存在「互读导致偏移自增跑满」的通路。

#### 三元件采样拓扑对比

三元件各自「读什么、怎么读、读完后构造什么」的差异，直接影响它们在铜块网络中的角色：

| 维度 | 雕文（移相器） | 切制（裂相器） | 格栅（加法器） |
|---|---|---|---|
| **方向限制** | 只 1 向（`inputDir`，玩家配置） | 全向（4 条边） | 全向（4 条边） |
| **读上升/下降沿** | 上升沿 | **下降沿**（独立命名空间 `FALLING_BIT`） | 上升沿 |
| **读注册表？** | ~~是~~（输入方向邻居的 `phaseRegistry`）**【链已暂时关闭，见 §3.8 顶部】** | 否 | 否 |
| **派生条件** | 有输入即有派生 | 有下降沿即有派生 | **同周期 ≥2 路**才派生 |
| **偏移变化** | φ+1 mod P（延迟 1 tick） | 原样 φ_f（下降沿自身位置） | Σφᵢ mod P（相位求和） |
| **幅度** | 沿用源 δ | 沿用源 δ | min δᵢ（各路最小值） |

**BFS 采样侧的额外限制**：在 `runBfs` 中，雕文槽位还会额外过滤边信号采样（`chiseledInputEdge` 检查）——BFS 遍历到雕文时只检查 `inputDir` 方向的边信号变化，其他 3 个方向的边信号被跳过，不参与上升沿采样。这使雕文的感应方向与信号层二极管语义一致（涂蜡槽的发电采样面 = 信号层二极管镜像）。

**切制下降沿跟踪器的命名空间隔离**：下降沿跟踪器与上升沿跟踪器同存于 `edgeTrackers` 哈希表，但通过高位掩码 `FALLING_BIT = 1L << 32` 隔离。下降沿事件驱动的 `SignalTracker` 完全独立于上升沿，其周期/偏移/幅度各自独立估计，互不干扰。

#### 跨锈蚀等级的注册表读取

> **⚠️ 本节描述的链式读取行为已随移相链一起暂时关闭（2026-09-08，见 §3.8 顶部）。
> 下列内容保留为重新启用时的设计依据——`phaseRegistry` 容器级共享（不按锈级
> 分桶）与 `resolveNeighbor` 的无锈级过滤是实现层事实，不因关闭而改变。**

雕文移相器的 `interpretShifter` 在读取邻居注册表时**不检查锈蚀等级**：

```java
int neighbor = ContainerContext.resolveNeighbor(slot, inEdge, size, width);
if (neighbor >= 0) {
    for (DerivedPhase dp : powerData.getRegistry(neighbor)) {  // ← 无锈蚀过滤
        out.add(new DerivedPhase(dp.period(), (dp.offset()+1) mod P, ...));
    }
}
```

`phaseRegistry` 是容器级共享的（`ContainerPowerData` 的字段，不按锈级分桶），`resolveNeighbor` 只按网格坐标算邻居，不检查物品类型或氧化等级。因此：

- **雕文可以读取任意锈蚀等级邻居的注册表驻波**，无论该邻居是基座铜块、雕文、切制还是格栅——只要邻居槽位在上一 tick 的相位解读中产出了派生相位，雕文就能读到；
- **BFS 遍历仍然是锈蚀隔离的**（`runBfs` 的邻接检查 `getOxidationLevel(ns.getItem()) != oxidation` 过滤），因此 BFS 注入的边采样和注册表驻波只影响本锈级的 `ChannelState`；
- **功率（`baseReByOx`）仍然是锈级隔离的**（`accountEnergy` 按锈级累加，`distributeToBulbs` 按锈级分配入灯）。

**数据流示例**：新鲜级雕文 A 的 inputDir 指向暴露级格栅 B

```
上一 tick:
  phaseInterpretation 遍历所有发电机（无锈级过滤）
    → 格栅 B（暴露级）产出派生相位 (P=4, φ=2, δ)
    → 写入 phaseRegistry[槽位B]

本 tick:
  phaseInterpretation 再次遍历所有发电机
    → 雕文 A（新鲜级）调用 interpretShifter
    → 读 powerData.getRegistry(槽位B) → 拿到 (P=4, φ=2, δ)
    → 偏移 +1 → 派生 (P=4, φ=3, δ)
    → 写入 phaseRegistry[槽位A]

下一 tick:
  新鲜级 BFS 访问雕文 A → 读 phaseRegistry[槽位A]
    → now ≡ 3 (mod 4) 时注入新鲜级的 ChannelState
    → 新鲜级发电机获得来自暴露级网络的相位信息
```

**设计含义**：
- 雕文可以充当**跨锈蚀等级的相位信息桥**——相位信息（周期、偏移）可以跨越氧化等级边界传播，但电力（RE 能量）仍然是锈级隔离的；
- 这符合「拓扑统一」的设计原则：网络连通性唯一维度 = 氧化等级（铜块网络本身是锈蚀隔离的），但**相位解读层不在网络拓扑内**，它是信息层面的操作，不是电力层面的路由，因此不锈蚀隔离是合理的；
- 环自熄仍然成立：环上雕文的输入方向邻居仍是环成员，但环成员的注册表需要种子（真实边信号或跨锈级注入的驻波）。如果环上没有任何成员有真实边信号且没有外部注入，仍然自熄。

**拓扑统一（v19）**：网络连通性唯一维度 = 氧化等级（`TopoKey` 的 axis/inEdge/outEdge
全部退役，切制 H/V 双通道拆除）——三形态的个性全部迁移到「解读规则」上，
基座铜块退役为纯基准（只会「读」不会「造」）。

**测试**：`AccountingGateTest`（4 项：无跳变零产出、停机冻结、offset 去重、4t vs 12t
中性化）+ `PhaseInterpretationTest`（8 项：移相/移相链（2026-09-08 起为关闭态
回归守卫）/环自熄/死源熄灭/裂相/加法/去重诚实）。

---

## 4. 记账模型（RE / FE）

| 单位 | 用途 | 特点 |
|---|---|---|
| **RE**（红电单位） | 内部记账：`每跳 RE = 合因子 × P` | 零硬常数，全部涌现量 |
| **FE**（NeoForge Energy） | 对外：电池、IEnergyStorage（阶段四） | 通用能源货币 |

边界换算：`FE = RE × K`，`K = 1/16`（`PowerMath.RE_TO_FE`，全 mod 唯一标尺常量）。
整体调产量只改这一个数，**严禁**把 K 绑到最大周期等设计旋钮上。

储能标定：每盏容量 `C = 1,000,000 FE`（`PowerMath.BULB_UNIT_CAPACITY_FE`，与 K 并列的
第二个硬数；标定史 1,000 → 10,000 → 1,000,000，当前值 2026-09-09 定——决策记录见
红电系统.md §3.6「容量涌现」；一堆(64) = 64M FE，对齐科技生态中高档单格电池
（Mek 能量立方 / Flux 储存器档位），充电宝物流彻底实用（一盏 = 一个满配方块电池）；
q 按 mFE 绝对值存储，扩容无迁移问题。**int 收窄警戒线**：对外 IEnergyStorage 是
int FE，单堆读数溢出需 count ≥ 2,148 盏（2³¹ / 1M = 2,147.48）——超大堆叠容器（抽屉类）已可触达，
fail-safe 不崩不刷（详见 oversized-stack-audit.md §2.8），long 内部全程无损）。

---

## 5. 与红石系统的集成

- 铜块网络传播依赖 `ContainerRedstoneData` 的电力层访问器 `getIncomingEdgeValue(slot, dir)` /
  `getPrevIncomingEdgeValue(slot, dir)` 与 `EDGE_COUNT = 4`；
  **入边语义（v15 适配）**：边归发出方所有，涂蜡槽（绝缘，信号层从不为其写边）读
  「dir 方向邻居朝本槽发出的出边」——与旧共享边模型下 `getEdgeValue(涂蜡槽)` 的可采信号
  精确等价。读自己的出边在新模型下恒为 0（2026-09-05 修复的回归根因）；
- 边界外（涂蜡块贴容器边）入边返回 0，与旧版一致——外部世界信号走 `faceInput`，从不写入
  edgeGrid，涂蜡块不感应容器外红石（保持旧行为）；
- 信号源写边**无条件**（涂蜡槽位天然可采样——源直接写自己的出边朝向涂蜡槽），传播层零改动；
- 稳态跳过路径：`calculate` 跳过时把 `edgeGrid` 按值同步进 `prevEdgeGrid`（跳过承诺
  「结果与上 tick 一致」），否则跳过 tick 会把陈旧 prev 误判为新上升沿、压碎周期估计；
- 发电机按网络组件遍历只读边信号（锚点 BFS + 其余 copyFrom 共享），不写任何信号；
- 遥测写回节流（量化降脏化）：仪表盘 `LivingWaxedGeneratorData` 写回前，对共振增益 `resonanceGain` / 平衡度 `resonanceBalance` / 域快照 `effDeltaSum` 三个 EMA 类 double 量化到 3 位有效数字（`PowerMath.quantize`）。稳态下这些读数被「钉」在固定值 → `equals` 变 true → 跳过脏写，复用现有 `dirtySlots` 批处理，不另造轮子。针对服务器场景（多发电容器同时被打开 × 多玩家）降低每 tick 同步量。
- 容器级缓存完整镜像红石协议：`ContainerLivingItemHandler.CONTAINER_DATA`（`Map<String, ContainerEntry>`，嵌套 `power` 字段）+
  过期清理（120s）+ `clearAllCaches`（ServerStoppedEvent）+ 位置反向索引。

---

## 6. 测试

| 测试文件 | 项数 | 覆盖 |
|---|---|---|
| `PowerMathTest` | 5 | 调谐效率曲线、合因子计算、√|Δ| 求和、K 换算 |
| `ContainerPowerDataTest` | 6+ | 单路锁相、三相 6t 部分解锁、五相 5t 满相、杂讯排除、同相合并、EMA 账本 |
| `AccountingGateTest` | 4 | **v19 跳变门控**：无跳变 tick 零产出、停机冻结、offset 去重、4t vs 12t 谐波中性化 |
| `PhaseInterpretationTest` | 8 | **v19 相位解读三元件**：移相、移相链（奇数偏移）、移相环自熄、死源熄灭、裂相、加法求和、去重诚实 |
| `ChannelStateTest` | 5 | 偏移活性剪枝（拆振荡器 n 回落）、稳态不误剪、长周期跨心跳存活 |
| `CoilGroupingTest` | 5 | 形态识别 + 单通道状态机 |
| `NetworkTraversalTest` | 6 | copyFrom 深拷贝、同组件共享历史、能量可加、锈级隔离、E2E 真实传播 |
| `NetworkResonanceTest` | 18 | 网络级共振（含回代安全、上界、EMA 衰减） |
| `WaxedCopperCouplingIT` | 3 | 多跳耦合链集成 |
| `WaxedCopperOscillatorIT` | 5 | 振荡器→发电全链路 |
| `WaxedCopperStorageTest` | 16 | 发电直存分配、无铜灯弃、满溢、模组容器取电、充电、容量 clamp、超取、取消活化排除、EMA 功率 |
| `BulbItemEnergyStorageTest` | 6 | 双向充放、容量 clamp、simulate、拆分守恒、线性读数 |
| `RoundTripConservationIT` | 4 | **往返守恒（2026-09-09）**：箱A灯→电缆→箱B灯 1000t 原样往返断言总能量不增（精确复刻 Mekanism UniversalCable + ForgeStrictEnergyHandler 传输协议：SIMULATE 探测→convertFromAndBack 钳制→EXECUTE、按返回值记账，feConversionRate=2.5）+ 拉侧/推侧单侧拆解诊断 + extract 记账契约最小复现 |

> **历史 BUG：零头回收记账 count 倍放大（2026-09-09 修复）**——
> `ContainerEnergyStorage.receive` 的零头回收循环给堆写 `q+1`（实充 = 每盏 +1 ×
> count mFE），但账面 `distributed++` 只记 1 mFE——**实充是记账的 count 倍**，
> 每 tick 按「写入次数 × 堆 count」凭空造电（Mekanism 电缆按返回值记账，差额
> 全部变成净增益；实测 1000 tick +2043 FE，与游戏内「两箱铜灯互传总电量缓慢
> 上升」现象一致）。根因是「每盏 q」数据模型下，任何按堆单次写入的实际效果
> 都是 count 倍——记账必须同口径。修复：零头回收按 count 记账 + 完整步进保护
> （`count > leftover` 跳过，不越过 accept）+ 残余 ≤63 mFE 保守丢弃（与整 FE
> 量化同「宁损勿造」方向）+ `BulbItemEnergyStorage.receive` 返回声明值改为
> ≥实充（ceil 口径）。测试即上表 `RoundTripConservationIT`（先复现、后守卫）。

用例数值直接取自 [红电波形分析表.md](../红电波形分析表.md) 的手工演算，
实现与文档互为验证。

---

## 7. 实施状态

| 规划步骤 | 状态 | 说明 |
|---|---|---|
| Step 7 记账（事件 + RE + EMA） | ✅ | `ContainerPowerData`；**v19 跳变门控**（§3.6） |
| Step 8 铜块网络传播 | ✅ | 按网络组件遍历（锚点 BFS + 其余 copyFrom），边信号检测；**v19 拓扑统一至锈级单维度** |
| Step 9 相位域合因子 | ✅ | √|Δ| 求和、域内去重 n、调谐解锁 |
| Step 10 氧化等级网络隔离 | ✅ | 新鲜/暴露/锈蚀/氧化互不连通 |
| Step 11 铜灯储能 | ✅ | 发电直存 + 按盏电量 DataComponent（无池） |
| Step 11.5 锈级专属通道 | 📝 设计定稿（v18） | k 锈级灯只收 k 锈级发电（含增益），实现待做 |
| **v19 相位解读三元件** | ✅ | 雕文移相 / 切制裂相 / 格栅加法 + 派生相位注册表（§3.8） |
| Step 12 IEnergyStorage | ✅（技术验证通过） | 原版容器 BE 注册，游戏内待实测 |
| Step 13 活避雷针 | ⏳ 阶段四后 | 供需分配 |
| Tooltip 仪表盘 | ✅ | `LivingWaxedGeneratorData` 检测值写回 + 槽位同步 + 双语渲染 |

---

## 8. 已知限制

| 限制 | 影响 | 计划 |
|---|---|---|
| 非 BE 容器不支持 | 充电宝搬入非 BE 容器时暂不可对外取电（发电本就需要 BE） | 有需求再补 Block 级注册 |
| **Pipez 能量管道不兼容** | Pipez（master 线）使用新 Transfer API 的 `Energy.BLOCK`（EnergyHandler 类型），非 FE 的 `EnergyStorage.BLOCK` | 用 Mekanism 电缆取电；中期软依赖注册 EnergyHandler 适配 |
| **Flux Networks 取电方块（Flux Plug）不取电** | Flux Plug 从不主动拉取邻块电量——它只暴露「可被充入」的电池面等邻块推电（源码+1.20.1 原版 jar 字节码双重验证：拉取 API `receiveFrom`/`canReceiveFrom` 全源码零调用）。我们的容器是标准被动电池面（`canExtract=true`），对「纯被动等待」的 Plug 不可见。存电方块（Flux Point）是推送方，充电正常 | 设计上不跟（「限流职责归用电侧」）；如需兼容可让容器 tick 主动向邻块 `canReceive` 的推电，待需求驱动 |
| 充电量化零头 | 剩余容量 < 1 FE 的部分不接收（整 FE 量化） | 保守方向（杜绝凭空造电），量级 ≤ 1 FE |
| 发电机移除后 EMA 冻结 | 功率读数不清零（数据过期清理兜底） | 观察后再定 |
| 事件 tick 计数随容器活跃度冻结 | 容器卸载期间周期被拉长 → 重锁 | 符合直觉，保留 |
| 取电跨 FE 边界向上取整 | 每次取电最多多拿 count−1 mFE（49 盏时 ≤ 0.048 FE） | 设计取舍，观察后再定 |
| 铜灯不导电 | 铜灯不能作为网络传播中继节点 | 设计如此，铜灯仅作储能 |

---

## 9. 附录：完整公式链

从原始边信号到最终实发功率的完整计算链路，每一步均标注对应的代码位置与文档章节。

### 9.1 符号表

| 符号 | 含义 | 来源 |
|:---:|------|------|
| P | 检测周期（tick） | 最佳域周期 |
| n | 相数 | 最佳域相位数 |
| Δφ | 最小相位间隔（tick） | 最佳域相邻偏移最小差 |
| |Δᵢ| | 第 i 路相位的信号振幅 | 边信号跳变差值 |
| Σ√\|Δ\| | 合因子底数 | 各相 √\|Δᵢ\| 之和 |
| P_pref | 偏好周期（tick） | 物品堆叠数 |
| eff | 调谐效率 | cos²(θ/2) |
| u | 解锁度 | eff × n / P_pref |
| s | 平衡度 | 几何平均 / 算术平均 |
| N | 活跃锈级数 | 基础出力 > 0 的锈级数 |
| R | 共振倍率 | 1 + (N-1) × s |
| gain | 共振增益 | R² |

### 9.2 计算链路

```
① 边信号 → 相位事件
   ─────────────────────────────────────────────────────
   SignalTracker 检测每条边的上升沿:
   • 周期 Pᵢ = EMA(interval)           [SignalTracker, §3.2]
   • 偏移 φᵢ = 跳变时刻 % Pᵢ           [SignalTracker, §3.2]
   • 振幅 |Δᵢ| = 信号强度跳变差值       [SignalTracker, §3.2]

② 相位事件 → 周期域
   ─────────────────────────────────────────────────────
   ChannelState 按周期分组:
   • 同周期 P 的相位聚合为 PhaseDomain
   • n = 域内相位数                     [PhaseDomain, §2.4]
   • Σ√|Δ| = Σ√|Δᵢ|                   [PhaseDomain, §2.4]
   • Δφ = min(相邻偏移差)               [PhaseDomain, §2.4]

③ 选择最佳域
   ─────────────────────────────────────────────────────
   bestDomain(pref):
   • n 最大 → 同 n 取 |P - P_pref| 最小  [ChannelState, §3.4]
   • 输出: P, n, Σ√|Δ|, Δφ

④ 调谐效率
   ─────────────────────────────────────────────────────
   θ = |P - P_pref| / P_pref × 2π       [PowerMath.tuningEfficiency, §3.4]
   eff = (1 + cos(θ)) / 2               [PowerMath.tuningEfficiency, §3.4]
   • ΔP = 0 → θ = 0  → cosθ = 1   → eff = 1.00 (完美调谐)
   • ΔP = 1t (P_pref=4) → θ = π/2 → cosθ = 0   → eff = 0.50
   • ΔP = 2t (P_pref=4) → θ = π   → cosθ = -1  → eff = 0.00 (完全失谐)

⑤ 解锁度
   ─────────────────────────────────────────────────────
   u = min(1.0, eff × n / P_pref)       [ChannelState.factorOf, §3.4]
   • u ∈ [0, 1]，量化了「n 路相位相对于偏好周期的利用率」
   • 例: eff=1.00, n=4, P_pref=4 → u = 1.00×4/4 = 1.00

⑥ 合因子
   ─────────────────────────────────────────────────────
   combinedFactor = Σ√|Δ| ^ (1+u)       [PowerMath.combinedFactor, §3.4]
   • u=0 → 线性（保底输出）
   • u=1 → 平方（调谐满配时最大化振幅差异的收益）

⑦ 单事件能量
   ─────────────────────────────────────────────────────
   eventEnergy (FE) = combinedFactor × P / 16    [PowerMath.eventEnergyRe, §3.6]
   • 因子 P/16 来自 K = RE_TO_FE = 1/16
   • 每 tick 产出的能量，单位 FE

⑧ 本机功率
   ─────────────────────────────────────────────────────
   • 每 tick 实际入账: 单事件 × jumpCount(tick)     [GeneratorState, §3.6]
   • 窗口均值功率 = 窗口内能量和 / 窗口长度          [GeneratorState, §3.6]
   • 窗口长度 = max(32, P_pref) 对齐到 P_pref 整数倍 [GeneratorState, §3.6]
   • 输出: emaPowerMilliFe → 文本层「功率: X.X FE/t」

⑨ 锈级聚合
   ─────────────────────────────────────────────────────
   • 同锈级所有发电机的基础出力求和                      [ContainerPowerData, §3.5]
   • 输出: levelEmaPowerMilliFe → 文本层「锈级: X.X FE/t」

⑩ 平衡度
   ─────────────────────────────────────────────────────
   • 统计: 4 个锈级的基础出力 P₀, P₁, P₂, P₃          [PowerMath.balanceFactor, §3.7]
   • 算术平均 = (P₀+P₁+P₂+P₃) / N, 仅计活跃锈级
   • 几何平均 = exp((lnP₀+lnP₁+lnP₂+lnP₃) / N)
   • s = 几何平均 / 算术平均 ∈ [0, 1]                 [PowerMath.balanceFactor, §3.7]
   • s=1 → 各锈级出力完全相同（完美均衡）
   • s→0 → 一个锈级独大（其他接近零）

⑪ 共振倍率与增益
   ─────────────────────────────────────────────────────
   N = 活跃锈级数（基础出力 > 0 的等级数）               [PowerMath.resonanceFactor, §3.7]
   R = 1 + (N-1) × s ∈ [1, N]                         [PowerMath.resonanceFactor, §3.7]
   gain = R²                                           [PowerMath.resonanceGain, §3.7]
   • N=1 → R=1, gain=1（无共振）
   • N=4, s=1 → R=4, gain=16（完美共振）
   • N=4, s=0.5 → R=2.5, gain=6.25

⑫ 最终实发
   ─────────────────────────────────────────────────────
   实发功率 = 锈级基础功率 × gain                        [ContainerPowerData, §3.7]
   • 输出: → 文本层「共振: X.X FE/t」

### 9.3 完整示例（4 锈级完美均衡）

```
条件: P=4t, n=4, P_pref=4t, Σ√|Δ|=4.5, 四锈级各 1.0 FE/t

调谐: ΔP=0t → θ=0 → cosθ=1 → eff=1.00
解锁: u = 1.00×4/4 = 1.00
合因子: 4.5^(1+1.00) = 4.5² = 20.3
单事件: 20.3×4/16 = 5.06 FE
本机: 5.06×4/4t = 5.06 FE/t → 窗口均值 5.1 FE/t
锈级: 1.0×4 = 4.0 FE/t
平衡度: 算术平均=1.0, 几何平均=1.0 → s=1.00
共振: R=1+3×1.00=4.00, R²=16.0
实发: 4.0×16.0 = 64.0 FE/t
```