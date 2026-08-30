<!-- markdownlint-disable -->

# Living Power (活涂蜡铜块 · 红电发电) 技术文档

> **文档版本**: v3
> **最后更新**: 2026-08-30
> **适用版本**: Minecraft 1.21.1
> **规划文档**: [红电系统.md](../红电系统.md)（v17.4，双因子模型）

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

活涂蜡铜块是电力层的载体：涂蜡 = 绝缘 = 不参与信号层，通过感应邻居红电信号的
**变化**发电。设计哲学是「构造几条规则，剩下的交给涌现」——
公式里唯一的常数是边界汇率 `K = 1/16`，其余全部由建造涌现。

### 1.1 双因子模型（v17.4）

```
单次跳变能量 = |Δsignal| × n^(1 + 解锁度) × (P / 16)     [RE]
解锁度       = 调谐效率 × 波形规律度
调谐效率     = (1 + cos θ) / 2，θ = (tick误差 / 偏好周期) × 2π
偏好周期     = 发电机堆叠数（2 ~ 64 tick，堆 1 叠 = 宽带，效率恒 0）
```

| 要素 | 来源 | 涌现方式 |
|---|---|---|
| \|Δsignal\| | 邻居边信号的 tick 间变化 | 信号源堆叠数² |
| n（相数） | 多路输入按周期域去重 | 布线（同相合并，不跨域累加） |
| 调谐效率 | 输入周期 vs 偏好周期 | 堆叠数 = 调谐旋钮 |
| 规律度 r | 通道上升沿间隔模式一致度 | 时钟质量 |
| 拓扑（形态） | 线圈分组（阶段三） | 听什么、不听什么 |

### 1.2 关键类与职责

| 类名 | 位置 | 职责 |
|------|------|------|
| `LivingWaxedCopperFunction` | `domain/power/` | 功能入口：`canApply`（涂蜡全家族 20 件）、`getPriority()=3`、逐方向事件采样 |
| `PowerMath` | `domain/power/` | 纯函数：合因子、调谐效率、耦合管径、RE 记账、K 换算 |
| `PathState` | `domain/power/` | 单路波形状态：值、上升沿、周期 EMA、相位资格判定 |
| `ChannelState` | `domain/power/` | 线圈通道：相位域分组计 n、规律度（best-shift）、合因子 |
| `GeneratorState` | `domain/power/` | 单台发电机状态：偏好周期（= 堆叠数）、线圈分组与方向映射 |
| `LivingWaxedCutData` | `domain/power/` | 涂蜡切制 DataComponent：单线圈感应方向（WASD 1 键配置） |
| `ContainerPowerData` | `domain/power/` | 容器级账本：RE 事件累加、EMA 功率、tick 计数 |

除 `LivingWaxedCopperFunction` 外全部为**纯 Java 类**（零 MC 依赖），可直接 JUnit 驱动。

### 1.3 调度与数据流

```
processContext() 每 game tick：
  ├─ priority 2：红石 calculate()（edgeGrid 双缓冲刷新）
  └─ priority 3：LivingWaxedCopperFunction.tickContainerData()
       ├─ 逐发电机逐方向采样 edgeGrid 值 → ChannelState.onPathValue()
       ├─ 跳变发生 → 合因子 → RE = |Δ| × 合因子 × P → onEventEnergy()
       └─ endTick()（EMA 更新 + tick 计数推进）
```

> ⚠️ **priority 必须保持 3**：电力采样依赖红石（priority 2）已算完的 edgeGrid。
> 当前占用：水桶 0、水车 1、红石 2、**电力 3**。

---

## 2. 数据结构

### 2.1 PathState —— 单路波形状态

一条物理信号路径（v1 = 发电机的一个邻居方向；阶段三加入共享转发路径）。

| 字段 | 说明 |
|---|---|
| `lastValue` / `lastEventTick` | 上次值与时间（首次喂值仅建基线，不计跳变） |
| `lastRisingTick` / `prevRisingTick` | 最近两次上升沿（周期与相位的锚点） |
| `periodTicks` | 周期估计 = 上升沿间隔 EMA（α = 0.5）；0 = 未知 |
| `domainN` | 所属周期域的去重相数（0 = 未入域） |

关键判定 `hasUsablePhase()`：≥2 个上升沿间隔，且最近一个间隔与周期估计一致
（±1 tick）——**抖动 / 乱按的路被挡在相位域之外**，只贡献幅度、不抬升 n。

### 2.2 ChannelState —— 线圈通道

| 成员 | 说明 |
|---|---|
| `paths` | 通道内的路（v1 固定 4 条 = 四个邻居方向） |
| `risingTicks` | 通道合并上升沿序列（≤9 条，强制单调，乱序防御） |
| `regularity` | 规律度 r ∈ [0,1]，初始 0（起振观测期，线性保底） |

### 2.3 GeneratorState / ContainerPowerData

| 成员 | 说明 |
|---|---|
| `preferredPeriod` | = 堆叠数 clamp [0,64]；<2 即宽带态 |
| `channels` | 线圈通道列表（v1 单通道；阶段三按形态拆分） |
| `onEventEnergy(re)` | 跳变即能量事件：RE 直接累加，无功率流中间态 |
| `endTick()` | 每 tick 末尾：EMA 更新（α=0.125）+ tick 计数 |
| `getEmaPowerFe()` | EMA 功率 × K 换算为 FE/t |

---

## 3. 核心算法

### 3.1 周期估计（PathState）

上升沿间隔的 EMA（α = 0.5）。任意稳定上升沿序列的相邻间隔恒等于周期，
**与占空比无关**。`roundedPeriod()`：周期 < 1.5 tick 视为未知
（1 tick「周期」即直流，不参与域）。

### 3.2 相位域分组与 n（ChannelState.recountPhaseDomains）

```
① 过滤：仅 hasUsablePhase 的路参与
② 分域：按 roundedPeriod 分组（整数 tick 量子化）
③ 域内去重：offset = (lastRising − base) mod period，base = 域内最早上升沿
   n = 不同偏移数（同相合并，不奖励）
④ 跨域不累加：每路的 n 只属于自己的周期域，事件用本域的 n
```

### 3.3 规律度 r（ChannelState.recomputeRegularity）

对通道**合并上升沿序列**（≤9 条 → 8 个间隔）做 best-shift 自一致度：

```
rel(L) = mean(|iv[i] − iv[i+L]|) / mean(iv)，L ∈ {1..min(4, k−1)}
r      = max(0, 1 − min rel(L))
```

- 每个 shift 至少 2 个比较样本（L=1 例外）——防小样本过拟合抬高 r；
- 完美时钟（任意多脉冲复合）→ 1；抖动 → 部分损失；乱按 → 0；
- **v1 局限**：只看时间不看幅度，占空比渐变暂不可见
  （§3.8 规划的值级双周期窗口比对留待 v2）。

### 3.4 合因子（ChannelState.factorFor）

```
不可用路（!hasUsablePhase）        → 0（杂讯不产出）
n = max(1, domainN)
eff    = tuningEfficiency(|period − pref|, pref)    （pref < 2 → 0，宽带）
unlock = eff × regularity
合因子  = n^(1 + unlock)
```

单路 n=1 恒为 ×1（1 的任何次方）——**调谐的奖励与多相绑定**。

### 3.5 线圈分组（GeneratorState.configureCoilsIfChanged）

形态决定通道划分与方向映射，每方向贡献直连 + 感应两条路径：

| 形态 | 通道划分 | 方向映射 |
|---|---|---|
| 涂蜡铜块 / 格栅 | 1 通道 | 4 向全入 |
| 涂蜡雕文 | 2 通道 | V={UP,DOWN}、H={LEFT,RIGHT} 轴间隔离 |
| 涂蜡切制 | 1 通道 | 仅 `senseDir` 方向（`LivingWaxedCutData`，默认 UP） |

配置指纹（形状 + 切制方向）变化时才重建通道（波形状态重置，重新起振）。

### 3.6 感应耦合（LivingWaxedCopperFunction.couple，§3.5）

相邻发电机按管径 c 分配直连振荡，**分层辐射 + 不回传 + 加权守恒**：

```
层 0：直连复合值非零的发电机成为辐射源，relay = directValue
层 h：节点 S 辐射 relay[S] 给相邻发电机 T（排除 sources[S]，不回传）
      贡献 v = relay[S] × c_T / Σc_下游（分叉守恒）
      T 首次接收 → 入队 relay[T] = directValue[T] + 已接收（可继续中继）
深度 ≤ 3 层；每 tick 从直连值重算整个 DAG，无跨 tick 累积 → 结构性无环
```

- 每方向收到的感应值喂入该方向的**感应路**（GeneratorState.dirVirtualPath），
  与直连路同待遇：锁相、进相位域、参与规律度；
- 每节点最多辐射两次（root 一次 + 中继一次），超出部分只计能量不转发（保守）；
- **分频（周期 ×2）转发未实现**：v1 为同频转发，谐振链 2:1 分频配方待 v2。

### 3.7 绝缘修复（isConductiveBlock 排除涂蜡）

红石层原有的「充能导体」机制会把涂蜡铜块当导电方块充能并向所有边发射信号，
**绕过涂蜡绝缘**直接把信号泄给邻居。修复：`isConductiveBlock` 排除涂蜡家族——
涂蜡方块既不被充能也不发射，电力层信号只走感应耦合。

### 3.8 储能实现（阶段四，§3.6 v17.5）

**数据**：`LivingWaxedBulbData(chargeMilliFe)`——每盏电量，1/1000 FE 定点
（充电分配的零头精度），随物品 NBT 持久化。**无容器池**——
发电量在 `ContainerPowerData` 上按 tick 累计（RE），tick 末直存入铜灯。

**发电直存**（`distributeToBulbs`，每容器 tick 末）：
```
本 tick 发电量 × K → mFE
无铜灯堆 / 铜灯全满 → 弃（显性浪费 / 电池已满）
否则：按各堆剩余容量比例分配，每盏 q += share/count（向下取整，零头丢弃）
```

**对外取电 / 充电**（`ContainerEnergyStorage`，实现 NeoForge `IEnergyStorage`）：
```
取电：请求 X mFE → 直接逐堆扣铜灯（槽位顺序，每盏等量，向下取整）
充电：外部电源推电 → 按剩余容量比例充入各铜灯堆（受每盏容量 C 上限）
   —— 跨系统能量等量转换（外部 100 FE 进灯 ↔ 红电侧取 100 FE），守恒无套利
```

**物品访问（模组兼容的关键修复）**：
取电的铜灯扫描统一走 `ItemHandler.BLOCK` 兼容面（原版容器由 NeoForge 自动注册、
模组容器自行注册）——与容器内 tick 机制**同一条兼容面**。曾用
`be instanceof Container` 单腿扫描，导致模组容器（不实现 Container）的灯
够不到对外取电路径——「发电/存储走兼容层、对外取电没走」的裂缝已修复。
取电扣减灯电量后调 `be.setChanged()` 落盘（结算路径同样补上）。
双箱合并 handler 由能力自动覆盖（旧「双箱另半限制」随之解除）；
随机战利品容器（未开箱）按 tick 机制同款规则跳过。

**能力注册（宽注册 + 让位 + 物品双向）**：

```
① BE 宽注册：遍历 BuiltInRegistries.BLOCK_ENTITY_TYPE 全部注册
   provider 判定链（全部缓存安全，零 invalidateCapabilities）：
     a. be instanceof Container？           否 → null（非容器，类型稳定）
     b. be instanceof IEnergyStorage？      是 → null（直接实现者让位，零成本）
     c. 重入保护下查询 EnergyStorage.BLOCK：
        已有主人（模组机器自身储能）→ null（让位，注册期定死的稳定属性）
     d. 返回 ContainerEnergyStorage 实例（内部自适应：无灯无池 → 电量 0）
② 铜灯物品注册：EnergyStorage.ITEM × 4 个涂蜡铜灯
   （BulbItemEnergyStorage：双向通用电池，见下）
```

- 能力链语义：同方块多方注册为**列表**，查询按序取**首个非 null**——
  让位机制保证「无主容器才由红电接管」，不劫持模组机器自身储能；
- 让位查询需**重入保护**（ThreadLocal）：内层查询会再次遇到我们的 provider，
  保护使其返回 null 被链跳过；
- 为什么不做「有灯才返回实例」的 null 切换：`BlockEntity.setChanged()` 不触发
  能力缓存失效，玩家/漏斗放入第一盏灯的时机无法集中收集失效调用——
  实例内自适应（`getEnergyStored()=0` 表达空）则零失效隐患。

**铜灯物品 = 通用电池（双向，`BulbItemEnergyStorage`）**：

```
电池槽（放电）：机器从灯抽取（每盏等量扣）✅
充能槽（充电）：外部电源给灯充能（每盏等量加，受每盏容量 C 限制）✅
   —— 跨系统能量等量转换，守恒无套利
无出身论：外部充的电与红电发的电混为一个 q，不分来源
方块级接口仍只出不进（发电是池的唯一来源）——双向开放的是铜灯物品
```

**能量生态兼容路线（两套能量 API）**：

NeoForge 生态存在**两代能量能力**，类型签名不同 → 互不可见，需分别注册：

| 世代 | 能力 | 类型 | 单位 | 状态 |
|---|---|---|---|---|
| 旧（1.21.1 线，21.1.230） | `EnergyStorage.BLOCK` | `IEnergyStorage` | int | **我们的实现** ✓ 生态主流 |
| 新（1.21.6+ 线，Transfer API） | `Energy.BLOCK` | `EnergyHandler`（`net.neoforged.neoforge.transfer`） | long + 事务模型 | 未注册 → Pipez 等新 API 模组查不到我们 |

- 新 API 的设计动机：`long` 单位、**事务模型**（操作在 `TransactionContext` 内暂存、
  `commit()` 才生效，支持嵌套回滚）、物品/流体/能量三套接口统一；
- Pipez 能量管道（master 线）使用新 API → 查询我们返回 null → 不兼容的根因（已确认）；
- **兼容路线**：短期 FE-only（Mekanism 电缆已验证 ✓）；中期软依赖 Mekanism 能力适配
  （注册 `Capabilities.Energy.BLOCK` 的 EnergyHandler 包装器，Pipez 能量管即通）；
  长期随 NeoForge 升级全面补 EnergyHandler 形态（内部逻辑复用，仅换接口签名）。

**Mekanism = 能量生态的枢纽**：其通用电缆内置 FE / RF（Redstone Flux）/ EU / J 等
几乎所有主流电力单位的转换——**我们对接 FE 一项，就自动接入整个转换网络**。
这是「只实现 FE、其余交给枢纽」路线的实证支撑。

**实测修复（游戏内反馈）**：
1. **充电刷电（NBT 确认）**：充电分配的按盏取整零头 + 返回值 floor——
   每次外部充电最多凭空产生 ~1 FE。修复：接收量**整 FE 量化**
   （`accept -= accept % 1000`，机器付多少、灯收多少，分毫不差），
   分配零头 1 mFE 逐灯回收（不凭空产生也不浪费）；
2. **取消活化的灯依旧被取电**：`isBulb` 补 `isLivingItem` 检查——
   取消活化 = 普通物品，电量保留但不参与能源系统（重新活化即恢复）；
3. **结算/取电落盘**：改灯电量后调 `be.setChanged()`（此前不落盘，重进世界回档）。

**Tooltip 仪表盘（阶段五预告）**：检测周期/相数/解锁度/功率等服务端内存态
**不在 NBT 也不在网络同步里**，tooltip 直接读不到——按
[living-item-infrastructure.md §11 Tooltip 渲染机制](../system-design/living-item-infrastructure.md#11-tooltip-渲染机制客户端)
的结论，唯一正路是「检测值写回小型 DataComponent → syncSlotToClients → 客户端读取」，
且 provider 纪律禁止在 tooltip 里反查世界状态。

---

## 4. 记账模型（RE / FE）

| 单位 | 用途 | 特点 |
|---|---|---|
| **RE**（红电单位） | 内部记账：`每跳 RE = \|Δ\| × 合因子 × P` | 零硬常数，全部涌现量 |
| **FE**（NeoForge Energy） | 对外：电池、IEnergyStorage（阶段四） | 通用能源货币 |

边界换算：`FE = RE × K`，`K = 1/16`（`PowerMath.RE_TO_FE`，全 mod 唯一标尺常量）。
K = 1/16 时数值与「单次能量 = |Δ| × 合因子 × (P/16)」的原始设计完全一致；
整体调产量只改这一个数，**严禁**把 K 绑到最大周期等设计旋钮上。

---

## 5. 与红石系统的集成

- `ContainerRedstoneData` 新增逐方向访问器 `getEdgeValue(slot, dir)` /
  `getPrevEdgeValue(slot, dir)` 与 `EDGE_COUNT = 4`；
- 信号源写边**无条件**（只有 BFS 入队才分 dust/copper），涂蜡槽位天然可采样，
  传播层零改动；
- 容器级缓存完整镜像红石协议：`ContainerLivingItemHandler.POWER_DATA_CACHE` +
  过期清理（120s）+ `clearAllCaches`（ServerStoppedEvent）+ 位置反向索引。

---

## 6. 测试

| 测试文件 | 项数 | 覆盖 |
|---|---|---|
| `PowerMathTest` | 5 | 耦合管径、调谐效率曲线、合因子地板/天花板、K 换算 |
| `ContainerPowerDataTest` | 6 | 单路锁相、三相 6t 部分解锁（3^1.5）、五相 5t 满相（×25）、杂讯排除、同相合并、EMA 账本 |
| `CoilGroupingTest` | 4 | 铜块全向、雕文 V/H 双通道、切制单方向、配置变化重建 |
| `WaxedCopperStorageTest` | 9 | 发电直存分配、无铜灯弃、满溢、模组容器取电（IItemHandler）、超取 clamp、模拟不改态、onChanged 落盘信号、发电量累计、EMA 功率 |
| `BulbItemEnergyStorageTest` | 6 | 双向充放、容量 clamp、simulate、拆分守恒、线性读数 |
| `WaxedCopperOscillatorIT` | 1 | 全链路集成：拉杆振荡器（4t）→ 红石传播 → 发电采样 → EMA 收敛 |
| `WaxedCopperCouplingIT` | 1 | 耦合链集成：A 直连 → B 一跳 → C 两跳，中继不回传 |

用例数值直接取自 [红电波形分析表.md](../红电波形分析表.md) 的手工演算，
实现与文档互为验证。集成测试通过 `TickContext` 的公开字段预注入容器级数据
（模拟生产环境 `SimpleContainerContext` 的持久化）。

---

## 7. 实施状态

| 规划步骤 | 状态 | 说明 |
|---|---|---|
| Step 7 记账（事件 + RE + EMA） | ✅ | `ContainerPowerData` |
| Step 8 基础幅度 | ✅ | edgeGrid 逐方向采样 |
| Step 9 相位质量合因子 | ✅ | n 去重 + 调谐×规律解锁平方 |
| Step 10 感应拓扑（线圈分组） | ✅ | 铜块/雕文/切制/格栅通道划分 + 切制方向组件 |
| Step 11 感应耦合与谐振链 | ✅（v1 同频转发） | 加权守恒分配 + 不回传；分频转发待 v2 |
| Step 12 涂蜡铜灯储能 | ✅ | 发电直存 + 按盏电量 DataComponent（无池） |
| Step 13 IEnergyStorage | ✅（技术验证通过） | 原版容器 BE 注册，游戏内待实测 |
| Step 14 活避雷针 | ⏳ 阶段四后 | 供需分配 |
| Tooltip 仪表盘 | ⏳ 阶段五 | telemetry DataComponent + sync |

---

## 8. 已知限制

| 限制 | 影响 | 计划 |
|---|---|---|
| 规律度只看时间不看幅度 | 占空比渐变的波形被误判为完全规律 | v2 值级窗口比对（§3.8） |
| 耦合同频转发 | 谐振链 2:1 分频配方不可用，链上发电机需同周期调谐 | v2 分频转发（周期 ×2 再发射） |
| 耦合每节点最多中继一次 | 多源汇聚节点的后续接收只计能量不转发 | 保守设计，观察后再定 |
| 非 BE 容器不支持 | 充电宝搬入非 BE 容器时暂不可对外取电（发电本就需要 BE） | 有需求再补 Block 级注册 |
| **Pipez 能量管道不兼容** | Pipez（master 线）使用新 Transfer API 的 `Energy.BLOCK`（EnergyHandler 类型），非 FE 的 `EnergyStorage.BLOCK` | 用 Mekanism 电缆取电；中期软依赖注册 EnergyHandler 适配（见能量生态兼容路线） |
| 充电量化零头 | 剩余容量 < 1 FE 的部分不接收（整 FE 量化） | 保守方向（杜绝凭空造电），量级 ≤ 1 FE |
| 发电机移除后 EMA 冻结 | 功率读数不清零（数据过期清理兜底） | 观察后再定 |
| 事件 tick 计数随容器活跃度冻结 | 容器卸载期间周期被拉长 → 重锁 | 符合直觉，保留 |
