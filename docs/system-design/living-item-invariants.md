<!-- markdownlint-disable -->

# 活物品全局纪律（不变量 · 违反即 bug）

> **性质**：契约层（`system-design/`）—— 记录**跨 ≥2 域、又不属于任一域**的全项目纪律。
> 子系统 tech 与 guides 只留一句结论 + 指向本文；机制正文只在本文一处，避免副本漂移
> （[README §3.1](../README.md#31-主域负责制跨域行为只写一次机制2026-10-28-定)）。
>
> **收录判据**：一条纪律被 ≥2 个活物品域引用、且不是某个域独有时，正文归本文；
> 域特有的例子 / 论证留在该域 tech 或 buffer，本文只留结论 + 指针（铁律 7：理由懒加载）。
> 域内自己的不变量（如容器层 I-C1~14、红电 I-*）仍在各自契约文档，不迁来。

## 目录

- [1. 活物品隔离 —— 活物品不作耗材](#1-活物品隔离--活物品不作耗材)
- [2. 数据层 per-item，视觉聚合放渲染期](#2-数据层-per-item视觉聚合放渲染期)
- [3. 跨维度同步 —— flush 逐维度，状态必须按维度隔离](#3-跨维度同步--flush-逐维度状态必须按维度隔离)

---

## 1. 活物品隔离 —— 活物品不作耗材

**不变量（违反即 bug）**：活物品是「正在运行的设备」，**不得**被其它活物品当作普通物品处理 ——
不传输、不熔炼、不作燃料、不作材料、不作瓶子。违反会丢掉该物品内部的状态（DataComponent）。

**唯一定义点**：`SlotInteractions.isEligibleCargo(stack) = !isLivingItem(stack) || 活箱子 || 活末影箱`。
传输层（`TransferPipeline.isTransferableSource`）与交互层（`canInteract` / `tryInteract`）共用此谓词，
任何调用点顺序变更都绕不过它。守卫：`SlotInteractionCargoGateTest`
（真值表：普通物品 / 活箱子 / 活末影箱合法，活骨粉 / 活熔炉非法）。

**受约束的域**（≥2，故归本文）：活漏斗（传输）· 活熔炉（熔炼 / 燃料）· 活酿造台（材料 / 瓶子）·
活信标（喂食）· 活耕地（施肥）· 活流体（推动 / 装桶）· 活活塞（压送）· 活工具 / 活武器（辅助判定）。

**例外的语义**：活箱子 / 活末影箱被放行，**不是**「箱子物品可被搬走」，而是**它们代表的槽位可展开为
虚拟存储**——作源取内部（`CONTAINER` 组件）物品、作目标写内部，箱体本体自始至终不移动。
措辞写成「本身可搬运」与代码语义相反。

**域特有无正文**：漏斗的骨粉施肥真值表、入口为什么收在交互层（调用点顺序 bug）、拉取方向独立入口，
见 [living-hopper-tech §6.2.1](../tech/living-hopper-tech.md#621-槽位交互分发注册式2026-09-15)「货物准入」段；
术语速查见 [glossary](../glossary.md)「活物品隔离」。

## 2. 数据层 per-item，视觉聚合放渲染期

**不变量（违反即 bug）**：同一个容器里有两个该活物品时，状态**各存各的**（per-item，按物品键 =
容器 + 槽位），**不得**在数据层合并成一份（per-container）。在数据层合并信息 = **不可逆丢失**，
后续想拆开（如从「一个容器一条光束」改成「每枚各一条」）必须重构键控。

**判据表**（新域建模前逐行问「这个状态按什么键」）：

| 维度 | 键 | 说明 |
|---|---|---|
| 可见物（光束 / 模型 / 面板） | **物品**（容器 + 槽位） | 数据层保留每枚完整信息 |
| 副作用（开盖 / 红石 / 方块事件） | **容器** | 与可视化分开建模 |
| 客户端「最近一次」提示状态 | **避免**（改分区 / 显式键） | last-writer 跨槽覆盖会串台 |

「一个容器只显示一条光束」这类**视觉聚合放渲染期做**，不进数据层。

**域特有论证**：信标多枚合并踩坑与重构、流体 `chestLikeTarget` 串台，见
[buffer/framework-evolution-plan §4](../buffer/framework-evolution-plan.md#4-提案-d数据模型-per-item视觉聚合在渲染期)；
设计填表入口见 [living-item-design-template §1.1-b](../guides/living-item-design-template.md#11-b-状态粒度--每个容器级可见物问一句per-item-还是-per-container)。

## 3. 跨维度同步 —— flush 逐维度，状态必须按维度隔离

**不变量（违反即 bug）**：任何 `flush(ServerLevel)` 由 `LivingItem.onServerTick` 的维度循环
**逐维度**调用的同步器，跨 tick 状态必须同时满足下列四条。少一条，在 ≥2 个维度时就会出现
「先 flush 的维度抽走别的维度的登记」或「拿 A 维度的状态在 B 维度里按坐标执行动作」。

**四问（设计 / 改同步器前逐条答，答不出就不写状态表）**：

1. `flush` 是逐维度还是全服一次？
2. 跨 tick 状态的键含维度、表按维度分桶，或 `flush` 时按宿主实时过滤吗？
3. `flush` 里会 `clear` / `remove` 全服表吗？（只允许动本维度那一桶）
4. 状态动作（`blockEvent` / 音效 / 方块写）会落到别的维度吗？（按坐标发送前先 `hasChunkAt`）

**三种合法形态**：

- **全局登记表 + 条目自带维度 + `beginTick()` 清**：登记条目带 `ResourceLocation dimension`，
  `flush` 只读本维度条目、绝不抽干全表；`beginTick` 必须在玩家循环之前调用。代表
  `LivingToolHostSync`。
- **全局登记表 + `flush` 实时按本维度过滤 + `beginTick()` 清**：条目不带维度，`flush` 读全表，
  按宿主所在维度现查过滤（玩家 `owner.level() == level`，实体 `level.getEntity(...)`）；
  `beginTick` 同样在玩家循环之前调用。代表 `BeaconPlayerHostSync` / `BeaconEntityHostSync`。
- **跨 tick 动作状态按维度分桶**：用 `living/util/PerLevelCache`（服务端同时持有所有维度，
  只提供按维度访问）。⚠️ 客户端的 `DimensionScopedCache` 是「单一当前维度 + 整表替换」，
  语义不同，**不可**拿来当服务端容器。

**发包去重**：走 `BroadcastDedup.merge`，`online` 必须是**全服**玩家（用本维度玩家会误删
其它维度的记录 ⇒ 每 tick 重发）。去重表可以全服一张，但它属于「发包去重」，不属于本节的
「跨 tick 动作状态」。

**守卫**：`BeaconChestHostDimensionTest` / `LivingToolHostSyncDimensionTest` /
`BeaconPlayerHostSyncTest` 都用两个 `ServerLevel` 替身 + 共用坐标驱动两个维度；新增或改
逐维度同步器必须补同型用例（写法见 [unit-testing「跨维度同步测试」](../guides/unit-testing.md)）。

**事故记录**：K2（活工具宿主，2026-10-27）、信标玩家 / 实体宿主（2026-10-18）、
信标箱子开盖表（2026-11）都踩过这条；最后一次的直接修法是三张表迁到 `PerLevelCache`。
