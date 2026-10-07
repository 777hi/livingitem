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

> **下方全部子系统均已实现**（没有列在这里的就是没做）。**按族列出，族内每个子系统直链其文档**；
> 每个子系统「做什么」的完整概述见 [subsystem-index.md](docs/reference/subsystem-index.md)，
> 逐项能力清单见 [completed-features.md](docs/reference/completed-features.md)。
> **能力口径与不变量以各子系统文档为准**，本文只作路由。

| 族 | 子系统（点击直达文档） |
|----|----------------------|
| **活物品功能（14）** | [活TNT](docs/tech/living-tnt-tech.md) · [活流体](docs/tech/living-fluid-tech.md) · [活熔炉](docs/tech/living-furnace-tech.md) · [活漏斗](docs/tech/living-hopper-tech.md) · [活箱子](docs/tech/living-chest-tech.md) · [活末影箱](docs/tech/living-ender-chest-tech.md) · [活水车](docs/tech/living-water-wheel-tech.md) · [活地图传送](docs/tech/living-map-ender-pearl-tech.md) · [活耕地](docs/tech/living-farmland-tech.md) · [活工具](docs/tech/living-tool-tech.md) · [活武器](docs/tech/living-weapon-tech.md) · [活红石](docs/tech/living-redstone-tech.md) · [活涂蜡铜块（红电发电）](docs/tech/living-power-tech.md) · [活打火石](docs/tech/living-flint-and-steel-tech.md) |
| **容器基础设施（4）** | [基础设施](docs/system-design/living-item-infrastructure.md) · [多方块容器身份解析](docs/system-design/container-identity.md) · [数据模型](docs/system-design/data-model.md) · [超大堆叠审计](docs/system-design/oversized-stack-audit.md) |
| **红电 · 电力（2）** | [红电架构演进](docs/buffer/redstone-evolution-roadmap.md) · [红电不变量测试](docs/system-design/power-invariants.md) |
| **跨域机制（3）** | [GUI交互](docs/system-design/gui-interaction-system.md) · [图标系统](docs/system-design/icon-system.md) · [Tooltip 系统](docs/system-design/tooltip-system.md) |
| **工程实践（2）** | [单元测试](docs/guides/unit-testing.md) · [活TNT测试说明](docs/guides/living-tnt-testing.md) |
| **未定案（1）** | [框架层对标（未定案）](docs/buffer/framework-benchmark.md) |

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

**合计测试用例 534 个**（含参数化展开与 `SimpleContainerContextTest` 的 `@Nested` 内部类）；
全绿基线：`533 passed / 0 failed / 1 skipped`。**逐次新增明细见 [changelog.md](docs/archive/changelog.md)。**

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
| 2026-10-08 | 🧹 **入口清淤**：模块地图尾部 62 行测试流水搬离（**补录 2026-09-27 防丢历史**）+ 开发进展回归「一行结论」体例 —— 入口 **18171 → 11935 字符（余量 8065）** | `docs/README.md` §1/§4 |
| 2026-10-07 | 🧹 **活桶 tooltip 定稿：删内容行、保留标题** —— 根因是 lang 占位符数与代码传参不符（**534 全绿**） | `living-fluid-tech.md` §5 |
| 2026-10-07 | ✅ **玩家路径落盘守卫补齐**：玩家容器此前零用例 ⇒ 补「变空移除 / 重进不复活 / 两键隔离 / 回填」（**528 全绿**） | `living-fluid-tech.md` §8 |
| 2026-10-07 | ✅ **性能收尾**：四处零行为变化短路（含 `fillNeighbors` 每相位复用缓冲）+ 量测基线（**524 全绿**） | `living-fluid-tech.md` §11 |
| 2026-10-07 | 🔧 **收尾审查批次**：🔴 修**晋升邻源计数不分流体**（岩浆源被算进水邻源 ⇒ 错误晋升）+ 三处健壮性（**519 全绿**） | `living-fluid-tech.md` §3.2 |
| 2026-10-07 | 🔧 **目标层抢占改「到达时间」**：消掉刷石机的「看不见的墙」；**两源曼哈顿 ≲6 则自毁**（**514 全绿**） | `living-fluid-tech.md` §3.1 |
| 2026-10-07 | 🔧 **消除「异种流体被当拍驱逐 ⇒ 接触前空档」**：目标层 BFS 跳过实际层已被异种占据的格（**513 全绿**） | `living-fluid-tech.md` §3.7 |
| 2026-10-06 | 🔧 **倒桶口径对称化（A 档）**：查实上一批**用错了函数**（`canBeReplacedWith` 属蔓延路径，倒桶不查它）（**511 全绿**） | `living-fluid-tech.md` §5 |
| 2026-10-06 | ✅ **倒桶路径对齐原版（B 档，后被 A 档取代）**：契约加 `canBeReplacedBy`，`pour` 改三态（**510 全绿**） | `living-fluid-tech.md` §5 |
| 2026-10-06 | ↩️ **回退「砍掉原版末影箱当流体容器」**（超出范围、破坏硬边界）+ 修第 ③ 次渲染泄漏（**506 全绿**） | `living-fluid-tech.md` §7 |

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
| **梳理上下游 / 估改动爆炸半径 / 判断模块是否真解耦 / 查新增子系统的扩展点** | [code-map.md](docs/guides/code-map.md) —— 人看 HTML，**AI 直接调 `python tools/gen_code_map.py --query <类名>` / `--extend`**（文本输出、直接解析源码、**不会读到过期图**）；**改完代码跑 `python tools/check_layers.py`**（分层违规即退出 1，棘轮式「只减不增」） |
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
