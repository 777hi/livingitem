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
    └── LivingButtonFunction / LivingLeverFunction / LivingRedstoneLampFunction
        LivingRepeaterFunction / LivingComparatorFunction / LivingRedstoneBlockFunction
    ↓
容器级数据计算（HasContainerData 接口，按优先级排序）
    ├── LivingWaterBucketFunction  (prio 0) — 流体蔓延 + postTickSync
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
| **活水桶** | 水源注册 + BFS 蔓延 + 水流推动物品 | [living-water-bucket-tech.md](docs/tech/living-water-bucket-tech.md) |
| **活熔炉** | 配方匹配 + 燃料消耗 + 方向槽位配置 | [living-furnace-tech.md](docs/tech/living-furnace-tech.md) |
| **活漏斗** | TransferPipeline 统一传输 + 黑白名单 + 跨容器 + WASD 配置 | [living-hopper-tech.md](docs/tech/living-hopper-tech.md) |
| **活箱子** | 堆叠倍增 + UUID 映射 + LRU 缓存 + 磁盘持久化 | [living-chest-tech.md](docs/tech/living-chest-tech.md) |
| **活末影箱** | 路由模式（共享黑板）+ 直连模式（绑定玩家末影箱） | [living-ender-chest-tech.md](docs/tech/living-ender-chest-tech.md) |
| **活水车** | 力矩计算 + 应力叠加/抵消 + Create 软依赖 | [living-water-wheel-tech.md](docs/tech/living-water-wheel-tech.md) |
| **活地图传送** | 三种场景 + UV 精确传送 + 跨维度 + 载具 + Sable 飞艇 | [living-map-ender-pearl-tech.md](docs/tech/living-map-ender-pearl-tech.md) |
| **活耕地** | GUI 交互获取/种植/骨粉 + **放置回世界模拟右键种植** + 世界轴节拍生长 + round-robin 逐项产出 + 双槽渲染 | [living-farmland-tech.md](docs/tech/living-farmland-tech.md) §3.5 / §8 |
| **活工具**（镐/斧/铲/锄） | **记忆玩家操作行为**（左键挖掘 / 右键交互）→ 以宿主为原点沿射线回放；FakePlayer 模拟完整操作 + 逐格扫描黑名单 | [living-tool-tech.md](docs/tech/living-tool-tech.md)（设计池见 [idea.md](docs/idea.md) §3.12） |
| **活红石** | 红石信号传播 + BFS 算法 + 反相器 + 堆叠数影响 | [living-redstone-tech.md](docs/tech/living-redstone-tech.md) |
| **活涂蜡铜块（红电发电）** | 双因子感应发电 + 事件驱动记账 + RE/FE 单位制 | [living-power-tech.md](docs/tech/living-power-tech.md) |
| **活打火石** | 交互触发器，无 tick 逻辑 | [living-flint-and-steel-tech.md](docs/tech/living-flint-and-steel-tech.md) |
| **GUI交互** | 声明式规则 + 统一拦截 + 创造模式兼容 | [gui-interaction-system.md](docs/system-design/gui-interaction-system.md) |
| **图标系统** | 三层架构 + 声明式配置 + 上下文切换 | [icon-system.md](docs/system-design/icon-system.md) |
| **Tooltip 系统** | 双层渲染架构 + 运行时缓存同步 + 显示口径（窗口均值/mFE） | [tooltip-system.md](docs/system-design/tooltip-system.md) |
| **红电架构演进** | SensorPort 感知端口 + 事件流/元件接口化路线（信号层⇄电力层解耦） | [redstone-evolution-roadmap.md](docs/system-design/redstone-evolution-roadmap.md) |
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

**合计测试用例 357 个**（含参数化展开与 `SimpleContainerContextTest` 的 `@Nested` 内部类）。
全绿基线：`357 passed / 0 failed / 0 skipped`（2026-09-22 光照刷新 + 爆炸受影响区块判据修复
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

### 当前版本: v0.9-alpha

| 日期 | 变更（一行结论） | 指针 |
|---|---|---|
| 2026-09-22 | 大箱子跨容器传输面选取：GUI 4 方向→世界 6 面，改候选基准块列表逐个尝试（推错面不报错） | `living-hopper-tech.md` §6.4 |
| 2026-09-22 | 大箱子（多方块）两套槽位体系错位 ⇒ 活漏斗静默不传输；新增槽位一致性探针（不特判合并容器） | `living-hopper-tech.md` §10.25 |
| 2026-09-22 | 活红石粉装饰器叠了缺失纹理（黑紫）；⚠️ 去原版化扫描必须含 `fromNamespaceAndPath(MOD_ID, …)` | `icon-system.md` |
| 2026-09-22 | GUI 图标光照统一：⚠️ 改 light 值没用，真凶是 `usesBlockLight()`；抽出 `LivingIconRenderHelper` | `icon-system.md`「GUI 图标光照约定」 |
| 2026-09-22 | 箱子/末影箱图标定稿：`builtin/entity` 正面视角 14px 3D（判据是 `isCustomRenderer()`） | `icon-system.md`「已完成的实验」 |
| 2026-09-22 | 图标「渲染上下文覆盖范围」写成显式约束（非 GUI 回退原版模型 + 装饰器只在 GUI 调用） | `icon-system.md` |
| 2026-09-22 | 纹理去原版化 56 张（改引 `minecraft:` 路径，跟随材质包）；涂蜡改双层模型（24 张→1 张） | `icon-system.md`「纹理约定」 |
| 2026-09-22 | 爆炸「方形区域过一会自己消失」= 客户端重建排队（判据：会消失=正常，一直在=bug） | `living-tnt-tech.md` §4.3 |
| 2026-09-22 | 大当量爆炸坑不圆：`affects()` 改「区块 AABB ∩ 球体」（原「中心在半径内」漏 32~86 区块） | `living-tnt-tech.md` §4.3 |
| 2026-09-22 | 大当量/超级爆炸后坑里漆黑：批量改 Section 绕过光照更新 ⇒ 新增 `refreshLightAfterBulkEdit()` | `living-tnt-tech.md` §4.3 |

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
| **查原版 / NeoForge / 第三方模组源码** | **直接搜 `libs/src/`，无需解压** —— `libs/src/neoforge-21.1.249-merged/` 是 ⭐ 首选（版本与 `neo_version` 一致）；第三方模组在 `libs/src/<ModName>/`。详见 [idea.md](docs/idea.md) §2.2.1 |
| 查历史变更 | [changelog.md](docs/archive/changelog.md)（按日期倒序） |
| 红电系统总体设计 | [红电系统.md](docs/system-design/红电系统.md) |
| 活潜影箱独立设计 | [活潜影箱实现细节.md](docs/reference/活潜影箱实现细节.md) |
| 自定义槽位 / 配方书 / WASD / GUI 点击拦截 / 容器兼容 / 框架演进 | `docs/guides/` |
| 红电波形分析 / 方块朝向 / Sable 投影 / 拼音搜索 | `docs/reference/` |
