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
| `onRisingEdge(tick, delta)` | 记录上升沿时间和幅度，更新间隔 EMA |
| `period()` | 返回当前估计周期（≥2 个间隔后有效） |
| `offset()` | 返回相对于首次上升沿的偏移量 |

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

```
上升沿间隔 EMA（α = 0.5）：
  间隔 = 本次上升沿 tick - 上次上升沿 tick
  periodEMA = periodEMA + (间隔 - periodEMA) * 0.5
  需要 ≥2 个间隔才开始输出周期（防止单次误判）
```

### 3.3 相位域分组与 n（PhaseDomain）

```
① 按 period 整数分域（不同周期各自独立）
② 域内 offset 去重：
   offset = (event.tick − baseTick) mod period
   baseTick = 域内第一个事件 tick
③ n = 不同偏移量个数（同偏移合并不算多次）
④ eff_δ_sum = Σ√|Δ_i|（各偏移的 |Δ| 分别 √ 后求和）
```

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