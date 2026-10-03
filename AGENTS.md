# Living Item (活物品)

**Minecraft 1.21.1 + NeoForge 21.1.x**
*最后更新: 2026-09-20*
*状态: Alpha 测试阶段 - v8.1 接口化重构完成 + 红电相位解读三元件（v19.1）+ 注册式槽位交互扩展点（`SlotInteractions`，2026-09-15）+ 活耕地放置回世界（`BlockItemMixin`，2026-09-16）+ Create 跨区块传送带死锁修复（2026-09-18）+ 容器规则增量语义与开发期导出通道（2026-09-20，含 IronChests 数据勘误）*

---

## 项目概述

将世界中的方块功能（熔炉、漏斗等）**活化到物品层面**。活物品在容器（箱子、背包等）内自动运行，状态通过 DataComponent 持久化，跟随物品跨容器迁移。

### 核心特性

- **活按钮 UI**：在容器界面点击按钮，将手持物品转化为活物品
- **容器内自动执行**：含活物品的被加载容器会自动 tick
- **DataComponent 直接管理**：每个功能类直接管理其类型化的 DataComponent，替代旧的 `ComponentState` + `LivingFunctionData` 中转层
- **功能内聚**：每个功能类自行实现 tick 逻辑和数据管理，不再依赖编排器调度
- **不可变数据模型**：使用 Java Record 实现不可变数据结构，通过 `withXxx()` 方法创建新实例
- **增量同步**：功能数据拆分为独立的 DataComponent，只在数据变化时更新并同步到客户端
- **活物品隔离**：活物品不会被其他活物品当作普通物品处理（不传输、不熔炼、不作为燃料）
- **GUI交互系统**：声明式规则 + 统一拦截 + 服务端处理
- **活物品图标系统**：组件化声明式配置，新增活物品图标无需编写 Java 类
- **活箱子**：堆叠数 × 27 槽虚拟箱子，UUID 映射 + LRU 缓存 + 磁盘持久化
- **活末影箱**：无线传输路由器，路由模式（共享黑板）+ 直连模式（绑定玩家末影箱）
- **活水车**：应力产生类活物品，软依赖 Create，3D 旋转渲染
- **活地图传送**：活末影珍珠 + 活地图，三种场景 + UV 精确传送 + 跨维度 + 载具 + Sable 飞艇兼容
- **活耕地**：GUI 交互获取/种植/骨粉催熟 + 世界轴节拍生长 + round-robin 逐项产出 + 双槽渲染
- **接口化扩展**：`HasDirection`（WASD 朝向配置）+ `HasContainerData`（容器级数据计算），新增活物品无需修改核心文件
- **活红石系统**：活红石粉（信号传播）+ 活红石火把（反相器）+ 活按钮/活拉杆/活红石灯 + 活中继器/活比较器/活红石块，支持与世界红石双向互通
- **活工具 / 活武器**：记忆玩家的操作（挖掘 / 交互 / 攻击）→ 借 FakePlayer 回放；无记忆的在背后环上辅助玩家（挖掘 / 攻击），有记忆的自主沿记忆射线干活；两类状态**联机可见**（主动模式的记忆射线需 F3+B）

---

## 架构概览

```
服务端 tick (LivingItem.onServerTick)
    ↓
ContainerChunkCache (容器位置缓存，拉取模型)
    ↓
ContainerLivingItemHandler (扫描容器、按功能分组)
    ↓
LivingItemFunction.tick() (各功能类自行实现 tick 逻辑)
    ↓
功能类直接管理 DataComponent，无中间层
    ├── LivingTntFunction        → LivingTntData
    ├── LivingWaterBucketFunction→ LivingWaterBucketData
    ├── LivingFurnaceFunction    → LivingFurnaceData
    ├── LivingHopperFunction     → TransferPipeline (统一传输入口)
    ├── LivingEnderChestFunction → LivingEnderChestData
    ├── LivingWaterWheelFunction → LivingWaterWheelData
    ├── LivingChestFunction      → InternalStorageComponent (旧架构)
    ├── LivingEnderPearlFunction → (纯工具类，无 DataComponent)
    ├── LivingRedstoneFunction   → LivingRedstoneData
    ├── LivingRedstoneTorchFunction → LivingRedstoneTorchData
    ├── LivingButtonFunction / LivingLeverFunction / LivingRedstoneLampFunction
    │   LivingRepeaterFunction / LivingComparatorFunction / LivingRedstoneBlockFunction
    └── LivingToolFunction      → LivingToolMemory / LivingToolProgress
                                  LivingToolAction / LivingToolOwner / LivingToolDigTicks
                                  （活工具 + 活武器：记忆玩家操作 → FakePlayer 回放；
                                   渲染见 client/render/LivingToolModelRenderer + RayRenderer）
    ↓
容器级数据计算（HasContainerData 接口，按优先级排序）
    ├── LivingFluidFunction        (prio 0) — 容器级流体 BFS（**自维持**，与桶解耦）
    ├── LivingWaterBucketFunction  (prio 1) — postTickSync（把 flow 同步回桶物品）
    ├── LivingWaterWheelFunction   (prio 1) — 应力计算 + postTickSync
    ├── LivingRedstoneFunction     (prio 2) — 红石信号传播
    └── LivingRedstoneTorchFunction(prio 2) — 红石信号传播（火把独立时）
    ↓
ContainerContext (组合接口) → TickContext (tick 级临时状态 + 脏槽位批量同步)
    ↓
SlotAccessor (模拟优先传输 + FilteredSlotAccessor 过滤)
```

> 容器级流体/红石数据由 `ContainerLivingItemHandler` 以「维度+containerKey」为键跨 tick 持久化，
> 并维护「位置 → 缓存键」反向索引供 mixin 热路径 O(1) 查询。

> 📄 基础设施详见 [living-item-infrastructure.md](docs/system-design/living-item-infrastructure.md)
> 📄 数据模型与设计决策详见 [data-model.md](docs/system-design/data-model.md)
> 📄 框架重构总结详见 [framework-refactoring.md](docs/archive/framework-refactoring.md)

---

## 子系统索引

> **下方全部子系统均已实现**（没有列在这里的就是没做）。逐项能力清单 + 实现细节快照见
> [completed-features.md](docs/reference/completed-features.md)；
> **能力口径与不变量以各子系统文档为准**，本文只作路由。

| 子系统 | 概述 | 详细文档 |
|--------|------|----------|
| **活TNT** | 引信倒计时 + 爆炸，威力随数量缩放，三模式（普通/大当量/超级爆炸）；破坏按区块分帧 + 待炸账本 | [living-tnt-tech.md](docs/tech/living-tnt-tech.md) |
| **活水桶 / 通用流体框架** | 水源注册 + 流体蔓延（`LivingFluidFunction` 驱动）+ 水流推动物品；**通用流体框架**（流体类型 / 行为分档 / 驱动 / 源查询 API）见 §2.2，流体侧分工见 [buffer/infrastructure-refactoring-plan.md](docs/buffer/infrastructure-refactoring-plan.md) §3 1b | [living-water-bucket-tech.md](docs/tech/living-water-bucket-tech.md) |
| **活熔炉** | 配方匹配 + 燃料消耗 + 方向槽位配置 | [living-furnace-tech.md](docs/tech/living-furnace-tech.md) |
| **活漏斗** | TransferPipeline 统一传输 + 黑白名单 + 跨容器 + WASD 配置 | [living-hopper-tech.md](docs/tech/living-hopper-tech.md) |
| **活箱子** | 堆叠倍增 + UUID 映射 + LRU 缓存 + 磁盘持久化 | [living-chest-tech.md](docs/tech/living-chest-tech.md) |
| **活末影箱** | 路由模式（共享黑板）+ 直连模式（绑定玩家末影箱） | [living-ender-chest-tech.md](docs/tech/living-ender-chest-tech.md) |
| **活水车** | 力矩计算 + 应力叠加/抵消 + Create 软依赖 | [living-water-wheel-tech.md](docs/tech/living-water-wheel-tech.md) |
| **活地图传送** | 三种场景 + UV 精确传送 + 跨维度 + 载具 + Sable 飞艇 | [living-map-ender-pearl-tech.md](docs/tech/living-map-ender-pearl-tech.md) |
| **活耕地** | GUI 交互获取/种植/骨粉 + **放置回世界模拟右键种植** + 世界轴节拍生长 + round-robin 逐项产出 + 双槽渲染 | [living-farmland-tech.md](docs/tech/living-farmland-tech.md) §3.5 / §8 |
| **活工具**（镐/斧/铲/锄） | **记忆玩家操作行为**（左键挖掘 / 右键交互；**活斧子还含攻击记忆 —— 三类记忆齐全**）→ 以宿主为原点沿射线回放；FakePlayer 模拟完整操作 + 逐格扫描黑名单 | [living-tool-tech.md](docs/tech/living-tool-tech.md)（设计池见 [living-tool-design.md](docs/buffer/living-tool-design.md) §3.12） |
| **活武器**（剑/斧/重锤） | **不实现攻击逻辑，只「代玩家出手」**：攻击记忆（射线）+ `fake.attack()` 走原版管线；攻击缩放 per-weapon 注入（受控 tick 驱动）。**仅近战** | [living-weapon-tech.md](docs/tech/living-weapon-tech.md)（设计池见 [living-weapon-design.md](docs/buffer/living-weapon-design.md)，含蓄力型设计稿指引） |
| **活红石** | 红石信号传播 + BFS 算法 + 反相器 + 堆叠数影响 | [living-redstone-tech.md](docs/tech/living-redstone-tech.md) |
| **活涂蜡铜块（红电发电）** | 双因子感应发电 + 事件驱动记账 + RE/FE 单位制 | [living-power-tech.md](docs/tech/living-power-tech.md) |
| **活打火石** | 交互触发器，无 tick 逻辑 | [living-flint-and-steel-tech.md](docs/tech/living-flint-and-steel-tech.md) |
| **GUI交互** | 声明式规则 + 统一拦截 + 创造模式兼容 | [gui-interaction-system.md](docs/system-design/gui-interaction-system.md) |
| **图标系统** | 三层架构 + 声明式配置 + 上下文切换 | [icon-system.md](docs/system-design/icon-system.md) |
| **Tooltip 系统** | 双层渲染架构 + 运行时缓存同步 + 显示口径（窗口均值/mFE） | [tooltip-system.md](docs/system-design/tooltip-system.md) |
| **红电架构演进** | SensorPort 感知端口 + 事件流/元件接口化路线（信号层⇄电力层解耦） | [redstone-evolution-roadmap.md](docs/buffer/redstone-evolution-roadmap.md) |
| **红电不变量测试** | 35 条可执行不变量（七组） + 四层测试方案（属性测试/场景生成/蜕变/运行时监控） | [power-invariants.md](docs/system-design/power-invariants.md) |
| **超大堆叠审计** | 模组容器堆叠上限 > 64 场景下全部活物品的表现评级（202 处调用点） | [oversized-stack-audit.md](docs/system-design/oversized-stack-audit.md) |
| **基础设施** | 容器抽象 + 发现缓存 + SlotAccessor + 性能监控 | [living-item-infrastructure.md](docs/system-design/living-item-infrastructure.md) |
| **数据模型** | DataComponent 体系 + 新旧架构对比 + 设计决策 | [data-model.md](docs/system-design/data-model.md) |
| **单元测试** | FML 测试环境配置 + 测试替身 + 可测性边界 | [unit-testing.md](docs/guides/unit-testing.md) |
| **活TNT测试说明** | **分两区**：群友版（`T-01`~`T-13`，肉眼观察引爆现象，含**铁箱子触发的超级爆炸**）+ 作者自测（`A-01`~`A-12`，需日志/TPS/跑图） | [living-tnt-testing.md](docs/guides/living-tnt-testing.md) |
| **框架重构** | HasDirection + HasContainerData 接口化设计 | [framework-refactoring.md](docs/archive/framework-refactoring.md) |

---

## 模块地图

> 只列**目录级职责**（根级文件与关键子目录随附）。**完整文件树见 [docs/reference/file-map.md](docs/reference/file-map.md)**
> —— 那份是快照、会漂移；需要准确清单时用 `find src/main -name "*.java"`。

```
src/main/java/com/qiqi/li/
├── LivingItem.java                          # Mod 主类：tick 入口、网络包注册
├── LivingItemClient.java                    # 客户端入口
├── living/
│   ├── api/                                 # 公开接口 + 管理器
│   ├── container/                           # 容器抽象层（跨活物品共享基础设施）
│   ├── domain/                              # 领域模块（每个活物品内聚到此）
│   │   ├── hopper/                           #   活漏斗领域
│   │   ├── chest/                            #   活箱子领域
│   │   ├── ender/                            #   活末影箱领域
│   │   ├── furnace/                          #   活熔炉领域
│   │   ├── water/                            #   活水领域
│   │   ├── tnt/                              #   活TNT领域
│   │   ├── farmland/                         #   活耕地领域
│   │   ├── tools/                            #   活工具 / 活武器领域（记忆 + 回放 + 环渲染）
│   │   └── map/                              #   活地图传送领域
│   ├── domain/redstone/                     #   活红石领域
│   ├── domain/power/                        #   红电发电领域（活涂蜡铜块）
│   ├── function/                             # 简单活物品功能（无需领域模块）
│   ├── compat/                              # 第三方模组兼容层
│   │   ├── create/                          #   Create 兼容（软依赖）
│   │   └── sable/                           #   Sable 飞艇兼容（软依赖）
│   ├── components/                          # 无状态工具组件
│   ├── transfer/                            # 传输基础设施
│   ├── interaction/                         # GUI交互
│   ├── model/                               # 配置/方向模型
│   ├── mixin/                               # 服务端 Mixin
│   │   └── create/                          #   Create Mixin（条件加载）
│   ├── debug/                               # 调试工具（默认关闭，命令启用）
│   └── perf/                                # 性能监控
├── client/
│   ├── gui/LivingButton.java                # 活按钮
│   ├── icon/                                # 图标系统（声明式配置）
│   ├── input/                               # 输入处理
│   ├── render/                              # 渲染
│   ├── util/                                # 客户端工具
│   └── mixin/                               # 客户端 Mixin
└── network/                                 # 网络包
```

**合计测试用例 422 个**（含参数化展开与 `SimpleContainerContextTest` 的 `@Nested` 内部类）。
全绿基线：`418 passed / 0 failed / 1 skipped`（2026-10-03 流体侧 F1：`ContainerFluidDataTest`
新增派生源 6 项 —— 独立存活/挤没/与非活物品共存/桶源同格无豁免/异种覆盖/生命周期与 EMPTY noop；
此前 2026-10-03 1b 系列至 413（引擎行为快照/驱动/红石归零解耦/源查询 API）；
2026-09-28 新增交互规则 JSON 加载语义 9 项 +
tick 顺序契约守卫 4 项 + 活化目标参数解析守卫 4 项；
2026-09-27 新增活化规则 JSON 加载语义 14 项
（含指令侧 put/remove/校验/**持久化往返**；tag 路径 1 项 @Disabled ——
FML unit test 不加载 item tags，已游戏内验证通过）+
活化门面守卫 5 项（含「零配置全放行」口径锁定）+ DataComponent 归属守卫 3 项；
此前 2026-09-22 光照刷新 + 爆炸受影响区块判据修复
+ 大箱子槽位体系探针 §10.25 / 跨容器面选取 §6.4）。
> 📄 测试环境配置与编写约定见 [unit-testing.md](docs/guides/unit-testing.md)；
> 测试文件树见 [file-map.md](docs/reference/file-map.md)「测试文件树」。

## 开发进展

> 📄 **完整变更正文**：[changelog.md](docs/archive/changelog.md)（按日期倒序，含全部历史细节）
>
> **条目体例（2026-09-22 修订）**：本表只放**最近 10 条**「一行结论 + 指针」——
> **完整正文直接写 changelog**，不在此铺细节。超出 10 条时**删掉最底部一行**
> （正文早已在 changelog ⇒ **无需迁移、不存在归档操作**）。
> 能沉淀成**约束**的写进对应的 `docs/tech/*.md` / `docs/system-design/*.md`
> （changelog 是流水，不是约束）。详见 [docs/README.md](docs/README.md) §4。
> 判据可复算：`python tools/doc_check.py` 第 4 项（条数 ≤ 10，且最新日期与 changelog 顶部一致）。

### 当前版本: 1.5.2-alpha（发布号随 `gradle.properties` 的 `mod_version`；alpha 阶段不做旧存档兼容）

| 日期 | 变更（一行结论） | 指针 |
|---|---|---|
| 2026-10-03 | **框架侧接缝落地（1b-2⑧⑩）**：⑧ **落盘** —— `ContainerFluidData.CODEC`（**只序列化派生源**，桶源/流动表不落）+ `CONTAINER_FLUID_DATA` 附件 `.serialize`；⑩ **引擎接缝** —— `FluidFlowBehavior.shouldPromote(slot, 邻源数)`（`recalculate` 加**晋升收敛循环**）/ `transformItem(item)`（`tick` 每流体拍在源格转化），两条都是 **default no-op ⇒ 现有行为零变化**。⚠️ **背包 Player attachment 仍未做**。**422 测试全绿** | `living-water-bucket-tech.md` §2.2 |
| 2026-10-03 | **流体侧批次一（F1+F5）**：F1 派生源（活水源）—— `generatedSources: Map<Integer,FluidType>` + 播种②无条件并入 BFS + **挤没判定**（活物品进源格即销毁，非活共存）；F5 流体渲染轨 —— `FluidFlowSyncPacket` 容器级同步（玩家背包/BE/大箱子全覆盖）+ `IClientFluidTypeExtensions` 自适应贴图/颜色（alpha 按 maxLevel 归一），旧桶轨降级过渡回退。桶源退役顺延批次二与汲/倒同批。**419 测试全绿** | `idea.md` §〇 |
| 2026-10-03 | 🔴 **修复 1a-4 引入的回归：容器流体数据不再被创建**（活水桶 `registerSource` 被跳过 ⇒ **水流功能整体失效**）：1a-4「容器级数据统一存储」把 `TickContext.fluidData()` 改成只读 peek 时，**丢掉了构造器里创建流体数据的调用**；恢复（含 BE 附件回填）+ 补回归测试（退回旧实现**恰好挂掉该测试**）。**406 测试全绿** | `living-water-bucket-tech.md` §2.2 |
| 2026-10-03 | **1b 通用流体框架（框架侧 ①②③④）**：① **1b-1 引擎泛化** —— `ContainerFluidData` 条目带**流体类型**（单张 map，水行为零变化）；② **1b-2a 行为分档接缝** —— `FluidFlowBehavior`/`FluidFlowBehaviors`（未注册=静止，水注册上限 7）；③ **1b-2b 通用驱动** —— `LivingFluidFunction`（**自维持** + `HasContainerData` prio 0），桶的 BFS 驱动**解耦**（只留 postTickSync）；④ **1b-2c 红石归零解耦** —— 自维持驱动使 `grouped` 恒非空、原寄生在 `grouped.isEmpty()` 的残留红石归零会失效 ⇒ 抽出 `zeroResidualRedstone`。⚠️ 流体引擎原**零单测** ⇒ 先补「行为快照」再重构。**412 测试全绿** | `living-water-bucket-tech.md` §2.2 |
| 2026-10-03 | **基础设施重构 1a 地基完成**（4/4，397 测试全绿，行为不变）：Q3 删 `containerKey` 第三档改抛异常；Q4 抽 `TickableContainerContext` 子接口；Q2 容器级数据并入 `ContainerDataStore`（按 `ContainerDataKey` 数组下标存取，新增一种数据只改 1 行）；Q1 `LivingItemFunction#shouldTickWithoutOwnItems`（默认 false）+ 注册期静态自维持清单（每 tick 只遍历 0~1 个，不逐函数判定），为「活水源 = 没有物品载体的活物品」铺路 | `docs/buffer/infrastructure-refactoring-plan.md` §3 |
| 2026-09-30 | **受控 tick 重构**（台阶一/二）：`driveWielderTick` 按 game tick 去重驱动 FakePlayer 真链（**调 doTick 非 tick**，物理钉住/不进世界）⇒ inventoryTick/冷却/药效衰减/装备刷新/蓄力推进全原生；攻击缩放为 per-weapon 注入（共享 fake 一个时钟的实测回归修正）；蓄力型地基就绪 | `living-tool-tech.md` §5.7 |
| 2026-09-30 | **修复「特定活武器永久白板化」**（0.25s 一刀 / 伤害 1 / 拉仇恨）：属性镜像竞态 —— 主人换手点击与属性刷新差一个 tick，旧武器 bd/bs 被镜像记录后清理时连武器自身的同 id 修饰符一起删，equipTool changed=false 不补回；修：每次摘旧装新自愈 + 镜像排除武器自身 id | `living-weapon-tech.md` §8.1 |
| 2026-09-30 | **代持 tick**（通用兼容）：回放路径补 `held.inventoryTick(selected=true)` + `fake.getCooldowns().tick()` —— 手持类效果与物品冷却在主动模式正常推进，模组无关（Simply Swords 实测）；玩家形态双 tick 接受 | `living-tool-tech.md` §5.7 |
| 2026-09-30 | **射线微调**（主动模式·玩家形态）：背包点小人 —— 左键躯干中心/底部=起点锚点、脑袋=重置，右键=朝向跟随（背后环同款）；衍生副本旋转，原始 offset 不动，两端同公式；录制即重置 | `living-tool-tech.md` §2.5 |
| 2026-09-30 | tooltip 主人显示统一：UUID=绑定数据、名字=显示缓存（新增 `LIVING_TOOL_OWNER_NAME`，`syncOwnerAttributes` 返回主人供四路回放刷新；末影箱 tick 同款），显示走 `OwnerNameResolver#displayName` 实时→缓存→短UUID | `living-ender-chest-tech.md` §绑定 |


## 排查铁律：原版机制挡路时

遇到「原版 / NeoForge 的某个机制限制了我们」时，**按顺序**做，别跳步：

1. **先找官方配置入口。** 该机制通常**留了口子** ——
   看到 getter / container / 事件名，就**顺着追**它的 setter 与调用方。

   > 🔴 **反面案例（2026-09-24）**：需要"无视实体无敌帧打出多段伤害"时，
   > 我 grep 到 `LivingEntity#hurt` 里的 `getPostAttackInvulnerabilityTicks()`，
   > **却没深追**，直接去改 `public int invulnerableTime` 字段。
   > 而正解是 NeoForge 官方接口：
   > `LivingIncomingDamageEvent#getContainer().setPostAttackInvulnerabilityTicks(0)`
   > （铁魔法 `DamageSources#preHitEffects` 正是这么做的）。

2. **确认没有，才考虑改字段 / 反射 / Access Transformer** ——
   并在代码注释里写明**代价**（如"生产环境会因混淆失效"）。

> ⚠️ **跳步的代价**：野路子会在 MC / NeoForge 改内部实现时**静默失效**，
> 且往往比官方接口**影响面更大**（波及本不该波及的场景）。
> 📌 教训：**看到 getter 就该想到有 setter，看到 container 就该想到有事件入口。**

## 文档导航

文档按**层**组织（契约 / 实现 / 指南 / 参考 / 归档）—— 分层定义、体量约束与维护规约见
[docs/README.md](docs/README.md)（**改文档前先读**）。

**该读哪一篇**：见上方「子系统索引」（每个子系统一行，直链到对应文档）。其余常用入口：

| 想做的事 | 读它 |
|---|---|
| **看不懂某个名词**（活物品 / 锈级 / 相位域 / 跳变 / 口径…） | [glossary.md](docs/glossary.md)（**术语表，先扫这一页**） |
| **查「为什么这么定」/ 某口径是否已被取代** | [decisions.md](docs/decisions.md)（决策索引 + 翻转留痕） |
| 改文档 / 归档 / 校验 | [docs/README.md](docs/README.md) + `python tools/doc_check.py` |
| 写测试 / 跑全量 / mock `Level` | [unit-testing.md](docs/guides/unit-testing.md) |
| **发版本给群友测活TNT** | [living-tnt-testing.md](docs/guides/living-tnt-testing.md) §1~§6（**转发时只发这半段**） |
| 找某个源文件 | [file-map.md](docs/reference/file-map.md)（完整文件树，快照） |
| 查指令用法 / 加新指令 | [commands.md](docs/reference/commands.md)（指令清单，快照） |
| **查原版 / NeoForge / 第三方模组源码** | **直接搜 `libs/src/`，无需解压** —— `libs/src/neoforge-21.1.249-merged/` 是 ⭐ 首选（版本与 `neo_version` 一致）；第三方模组在 `libs/src/<ModName>/`。详见 [living-tool-design.md](docs/buffer/living-tool-design.md) §2.2.1 |
| 查历史变更 | [changelog.md](docs/archive/changelog.md)（按日期倒序） |
| 红电系统总体设计 | [红电系统.md](docs/system-design/红电系统.md) |
| 活潜影箱独立设计 | [活潜影箱实现细节.md](docs/buffer/活潜影箱实现细节.md) |
| **给第三方写扩展 / 查对外契约**（**违反即 bug**） | [api-contract.md](docs/system-design/api-contract.md)（对外开放计划与待决问题池见 [open-plan.md](docs/buffer/open-plan.md)） |
| GUI 点击拦截 / 容器兼容 / 单元测试 / 活TNT测试 | `docs/guides/`（**做法**） |
| **配方书 / 拼音搜索**（跨活物品的共享技术） | `docs/tech/common/` |
| **未定案的草稿**（设计稿 / 计划 / 路线图 / 探讨 / 待办） | `docs/buffer/`（**中间层 —— 不稳定，别当现状读**） |
| 红电波形分析 / 方块朝向 / Sable 投影 | `docs/reference/`（**查表：快照 / 外部资料**） |
