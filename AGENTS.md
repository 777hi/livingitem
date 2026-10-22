# Living Item (活物品)

**Minecraft 1.21.1 + NeoForge 21.1.x**
*最后更新: 2026-09-20*
*状态: Alpha 测试阶段 - 活信标 Phase 4 完成（tooltip 重设计 + 9 柱面板）；v8.1 接口化重构 / 红电 v19.1 等更早变更见 [changelog](docs/archive/changelog.md)*

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
容器级数据计算（HasContainerData 接口，按优先级排序；**全库仅 4 个实现者**）
    ├── LivingFluidFunction        (prio 0) — 容器级流体 BFS（**自维持**，与桶解耦）
    ├── LivingWaterWheelFunction   (prio 1) — 应力计算 + postTickSync
    ├── LivingRedstoneFunction     (prio 2) — 红石信号传播（**自维持，唯一驱动点**）
    └── LivingWaxedCopperFunction  (prio 3) — 电力记账（依赖红石已算完）
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
> 逐项能力清单见各 tech 文档 §0（**能力口径与不变量以各子系统文档为准**，本文只作路由）。
>
> ⭐ **读子系统先读它 tech 文档最前面的「§0 玩法定义」**（是什么 / 怎么玩 / 规则 / 能力清单）
> —— 它是**功能需求的来源**与**行为对错的判据**（规约见 [docs/README.md](docs/README.md)）。

| 族 | 子系统（点击直达文档） |
|----|----------------------|
| **活物品功能（16）** | [活TNT](docs/tech/living-tnt-tech.md) · [活流体](docs/tech/living-fluid-tech.md) · [活熔炉](docs/tech/living-furnace-tech.md) · [活酿造台](docs/tech/living-brewing-stand-tech.md) · [活漏斗](docs/tech/living-hopper-tech.md) · [活箱子](docs/tech/living-chest-tech.md) · [活末影箱](docs/tech/living-ender-chest-tech.md) · [活水车](docs/tech/living-water-wheel-tech.md) · [活地图传送](docs/tech/living-map-ender-pearl-tech.md) · [活耕地](docs/tech/living-farmland-tech.md) · [活工具](docs/tech/living-tool-tech.md) · [活武器](docs/tech/living-weapon-tech.md) · [活红石](docs/tech/living-redstone-tech.md)（信号层含 [活铜](docs/tech/living-copper-tech.md)） · [活涂蜡铜块（红电发电）](docs/tech/living-power-tech.md) · [活打火石](docs/tech/living-flint-and-steel-tech.md) · [活信标](docs/tech/living-beacon-tech.md) |
| **容器基础设施（5）** | [基础设施](docs/system-design/living-item-infrastructure.md) · [多方块容器身份解析](docs/system-design/container-identity.md) · [容器边界口径](docs/system-design/container-boundary.md) · [数据模型](docs/system-design/data-model.md) · [超大堆叠审计](docs/system-design/oversized-stack-audit.md) |
| **红电 · 电力（3）** | [红电架构演进](docs/buffer/redstone-evolution-roadmap.md) · [红电包收口方案](docs/buffer/power-refactoring-plan.md) · [红电不变量测试](docs/system-design/power-invariants.md) |
| **跨域机制（3）** | [GUI交互](docs/system-design/gui-interaction-system.md) · [图标系统](docs/system-design/icon-system.md) · [Tooltip 系统](docs/system-design/tooltip-system.md) |
| **工程实践（3）** | [单元测试](docs/guides/unit-testing.md) · [活TNT测试说明](docs/guides/living-tnt-testing.md) · [活物品设计模板](docs/guides/living-item-design-template.md) |
| **未定案（2）** | [框架层对标（未定案）](docs/buffer/framework-benchmark.md) · [分层优化计划（未定案）](docs/buffer/architecture-layering-plan.md) |

---

## 模块地图

> 只列**目录级职责**（根级文件与关键子目录随附）。**完整文件树用 `find src/main -name "*.java"`**
> （人工文件树快照已于 2026-10-10 删除 —— 清单型内容必然漂移，不再维护）。

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

**合计测试用例 829 个**（含参数化展开与 `SimpleContainerContextTest` 的 `@Nested` 内部类）；
**全绿 = `doc_check` 第 2 项机械核验**（XML 实测 failures/errors 恒 0）—— 取代手维护的
passed 快照数（它随每次加测试必漂：2026-10-10 写下 `554 passed`，两天后实测已 588）。
已知 @Disabled 为环境性（FML 单测不加载 item tags，已游戏内验证兜住），
说明与回归入口住 [test-map.md](docs/reference/test-map.md)。
**逐次新增明细见 [changelog.md](docs/archive/changelog.md)。**

> 📄 测试环境配置与编写约定见 [unit-testing.md](docs/guides/unit-testing.md)；
> 测试文件树（每个测试类守什么）见 [test-map.md](docs/reference/test-map.md)。

## 开发进展

> 📄 **完整变更正文**：[changelog.md](docs/archive/changelog.md)（按日期倒序，含全部历史细节）
>
> **条目体例（2026-09-22 修订）**：本表只放**最近 10 条**「一行结论 + 指针」——
> **完整正文直接写 changelog**，不在此铺细节。超出 10 条时**删掉最底部一行**
> （正文早已在 changelog ⇒ **无需迁移、不存在归档操作**）。
> 能沉淀成**约束**的写进对应的 `docs/tech/*.md` / `docs/system-design/*.md`
> （changelog 是流水，不是约束）。详见 [docs/README.md](docs/README.md) §4。
> 判据可复算：`python tools/doc_check.py` 第 4 项（条数 ≤ 10，且最新日期与 changelog 顶部一致）。

### 当前版本: 以 `gradle.properties` 的 `mod_version` 为准（**不放快照数字** —— 快照必漂，2026-10-11 实证漏同步一次；alpha 阶段不做旧存档兼容）

| 日期 | 变更（一行结论） | 指针 |
|---|---|---|
| 2026-10-21 | 🔗 **精妙背包容器级数据的双向交接（`D-container-12`）** —— 真机日志证明精妙**两个事件都不给**：拾取走 `removeBlock`（无掉落物）、放置走 `BackpackItem.tryPlace`（**不是 `BlockItem`**，绕开 `BlockItem.place`）⇒ 新增**双向挂点**：L1 `ContainerHandoff`（`capture`/`handoff` 宿主→物品、`stageRestore`/`restoreTo` 物品→宿主；ThreadLocal 暂存且**必须 copy** —— 第三方会 `shrink` 就地改栈）+ 联动模块两个 Mixin（`putInPlayersHandAndRemove` / `tryPlace` 各 HEAD+RETURN），搬运复用事件路径同一份 `moveOut`/`moveIn`。原「按手上同种物品认领」方案**被否**（错认领比丢失更难查）。`require=0` + 方法缺失 ERROR ⇒ 失效不崩服且可见。⚠️ 顺带暴露 `ContainerFluidData` 当组件值违反 NeoForge 不可变约定（记 TODO §3.3）。🔴 **真凶是注册写成 `::moveOut` 方法引用而 `Mover.moveIn` 是 default 空实现 ⇒ 放置方向静默失效**；去掉 default + 反射守卫钉住「注册点用方法引用时接口不得留可选实现」。单测 802 → 823 | `container-boundary.md` I-C9 · `living/container/ContainerHandoff.java` · `living/mixin/sophisticated/` |
| 2026-10-21 | 🔻 **NeoForge 兼容下限 21.1.249 → 21.1.219（撤回解耦）** —— 门槛**从来不是代码需要**（改到 21.1.1 实测编译全过）；⚠️ 但**拆两个版本号本身是错的**：编译版本与承诺下限共用产物缓存 ⇒ 串了不报错、只悄悄产出错产物（本地假绿 + CI 假红）⇒ **收口成一个 `neo_version`**（编译=下限=开发），CI 的 build 作业即核验承诺 +「版本变了清产物缓存」自愈 | `docs/guides/unit-testing.md`「兼容下限核验」 · `docs/archive/changelog.md` |
| 2026-10-21 | 🚢 **实体容器入门（船/矿车/带箱驴骡）** —— 用户拍板 B+B 后查证翻案：①「位置广播」证伪（活信标在实体容器不开光——`LidBlockEntity` 全是 BE）；②「viewer 降级」翻案为**天然正确**：`ContainerEntity extends Container` ⇒ 菜单槽 container 就是船本身（零反射），`MenuSlotOwners` 无缓存 + 每 tick 从 UUID 反查**实时**位置 ⇒ GUI 开着船在漂也刷新。落地：`entity_<uuid>` 键、`EntityContainerContext`（继承 SimpleContainerContext，getBlockPos 恒 null 防船停箱子上信标误判）、白名单 `ContainerEntity`+`AbstractChestedHorse#hasChest()`、容器级数据宿主抽象 `getDataHosts()`（BE/实体同接口 ⇒ 流体/相位附件跟实体走，区块卸载再回来还在）、掉落物与实体容器合并为一次 `getAllEntities()`。列数：船/矿车启发式（27→9、5→5）+ **驴问原版 `getInventoryColumns()`**。**真机修正**：驴 inventory 是 16 槽（索引 0 = 马鞍、1..15 = 箱子格）⇒ 不排除鞍会算成 8 列（16%8=0，实测「8×2」）、且往鞍槽倒水得到幽灵物品 ⇒ 新增鞍槽偏移映射（size 15 / 槽位 +1 / 列数问原版）。**真机再修正**：加偏移后「渲染和逻辑差一格」⇒ 补 `toPhysicalSlot`/`toLogicalSlot` 互逆映射贯通全部跨边界通道，`update` 改收 ctx 防漏。单测 782 → 802 | `docs/buffer/container-menu-introspection-topic.md` · `living/container/EntityContainerContext.java` · `living/container/EntityContainers.java` |
| 2026-10-20 | 🔋 **相位快照也跨形态保全（顺 I-C9）** —— 审计 24 个活物品的容器能力依赖后发现：BE 附件里只有流体和电力相位**带 `serialize`**（= 存档级持久），而 `ContainerDataMigration` 只搬了流体 ⇒ 精妙背包/潜影盒里调好的多路满相布局，方块→物品后<b>相对相位被洗牌</b>、要重调。新增 `CONTAINER_PHASE_SNAPSHOT_ITEM` 组件复用现成 `PhaseSnapshot.CODEC`，破坏/放置两侧各搬一次；**不需要 snapshot()**（record 不可变，与流体的可变对象相反）。契约 I-C9 从「流体」扩为「容器级持久数据」，并立下判据：<b>只有带 `serialize` 的才搬</b>（应力/红石是运行时数据，丢了下一 tick 自愈）。单测 780 → 782 | `docs/system-design/container-boundary.md` I-C9 · `living/domain/water/ContainerDataMigration.java` |
| 2026-10-20 | 🧩 **跨形态保全收窄为「内容随物品走」的容器（I-C9）** —— 用户：「代价太重，原版潜影箱 / 模组背包这种撸掉后还保有物品的容器，才做流体保留才有意义」。新增 `container_rules.json` 的 `contentRetaining`（**id 级数据声明**，默认不迁移）：潜影盒 / 精妙背包 / 无尽箱子声明保留；原版箱子 / 铁箱子 / 炼金箱子破坏时流体随方块消失，与内容行为一致 —— **不再出现「空箱子但有水」**。不做自动判断：没有任何通用 API 能回答「内容是否随掉落物走」（四种容器各走各的组件），只能认组件 ⇒ 违反 I-C1。守卫 2 项（内置声明 / 玩家可追加但不可取消），先红后绿验证过。单测 778 → 780 | `docs/system-design/container-boundary.md` I-C9 · `living/domain/water/ContainerDataMigration.java` |
| 2026-10-20 | 🔌 **联动无尽箱子（AvaritiaNeo）** —— 新增 `living/compat/avaritia/`（`ModAvaritia` 门控 + `AvaritiaMenuResolver`）：它的 `TileInfinityChest` 不实现 `Container`、菜单也**没有** `getBlockPosition()` ⇒ viewer 匹配三条路全断（图标/tooltip/流体不刷新、GUI 倒汲无效）。判据是「**返回 `BlockEntity` 的 getter**」（`getTile`/`getBlockEntity`），拿到 BE 后走公开 `getBlockPos()`；包名前缀 `net.byAqua3.avaritia.` 限定，反射不扩散。内置规则补 `avaritia:infinity_chest` 300 格 / **25 列**（启发式会算成 10，25 不在任何候选宽度里）。核心零改动。单测 773 → 778 | `living/compat/avaritia/` · `container_rules.json` · `guides/container-compatibility.md` |
| 2026-10-19 | 🧭 **核心不做第三方 API 猜测（`D-container-09`）** —— 用户定调：「精妙背包只是泛化容器兼容性的**一个例子**，不能把模组弄成精妙背包的模样；难兼容的部分要当作**联动模组**去做，就像活水车那样，而不是将就本模组的一致性」。据此把 `MenuSlotOwners` 里为精妙内置的 **`getBlockPosition` 反射兜底整体移除** ⇒ 核心只剩「结构化 + 注册口」两级；反射下沉到 `living/compat/sophisticated/`（`ModList` 门控 + **包名前缀**判据，反射不扩散）。另记 `D-container-08`：**容器内物品 tick 属活潜影箱专属**，S5 取消。单测 768 → 772 | `docs/system-design/container-boundary.md` I-C5 · `docs/decisions.md` · `living/compat/sophisticated/` |
| 2026-10-19 | 🔧 **容器级数据跨形态保全** —— 真机「背包变物体形态后活流体没了」。根因：数据挂 BE 附件，破坏即随 BE 消失；而背包/潜影盒的**物品内容**存在别处 ⇒ 内容在、流体丢。用户定案「**流体保持容器属性，不物品化**」⇒ 只换存储介质：新增 `CONTAINER_FLUID_DATA_ITEM` 组件，`BlockDropsEvent`（掉落物已定、未入世、BE 尚在）搬进掉落物，`EntityPlaceEvent` 搬回新 BE。⚠️ 双箱必须迁移后清两半附件，否则两次破坏各得一份 = 复制；⚠️ 放置侧要校验「手持物品 == 本次放置的方块」。单测 762 → 769 | `living/domain/water/ContainerDataMigration.java` · `docs/TODO.md` |
| 2026-10-19 | 🔧 **容器兼容性专题收尾（真机驱动，4 连修）** —— ① `resolve` 也按位置解析模组容器：修「往背包倒水，源落进玩家物品栏 / 背包 GUI 不渲染流体」（`ContainerContexts.resolve` 只认 `Container` 实例，对精妙恒 null ⇒ 背包槽位汲倒静默无效，改点 GUI 下方玩家物品栏才成）；② 活流体绘制从 `AbstractContainerScreen.render` TAIL 迁到 `ContainerScreenEvent.Render.Foreground`——精妙的 `StorageScreenBase.render` **自己抄了一份 super.render 且不调父实现** ⇒ 父类注入永不执行（同类受害的还有任何只挂 render 的 Mixin；tooltip 没事因挂 `renderTooltip`）；③ `USER_RULES` 多档容量；④ 反射在 `MenuSlotOwners` 里降为兜底 + 注册口。tooltip / 图标 / 倒汲 / 流体渲染 / 列数**全部真机通过**。单测 761 → 768 | `docs/system-design/container-boundary.md` · `docs/buffer/container-compat-topic.md` · `living/container/ContainerContexts.java` · `client/render/ContainerFluidOverlay.java` |

## 提交前必跑（2026-10-20 定）

```bash
python tools/check_layers.py   # 层纪律：R1 依赖方向 / R2 Mixin 位置 / R3 领域互依赖 / R4 注册入口
python tools/doc_check.py      # 文档一致性：进展滚动 / 指针 / 测试条数 / 入口体量 …
```

**任一为红就不要提交。** 两条都是**棘轮**机制（基线只允许收紧、不允许放松），
所以「新引入的违规」一定会被看见。

> ⚠️ 最常见的红法是 `doc_check` 报「测试条数不一致（声明 X / 实测 Y）」——
> 那不是文档写错，是**改了代码但没跑测试**。先 `./gradlew test` 再提交。

> 💡 **本机自动防护**：`.git/hooks/pre-commit` 会在 `git commit` 前自动跑上面两条
> （红的直接拦住）。⚠️ 该目录**不被 git 跟踪** ⇒ 重装环境 / 换机器后要重写一遍。
> 内容见本节末尾的「重建命令」。

<details>
<summary>重建本机 hook（重装环境后用）</summary>

```bash
cat > .git/hooks/pre-commit <<'HOOK'
#!/bin/sh
fail=0
echo "[pre-commit] tools/check_layers.py（层纪律）"
python tools/check_layers.py || fail=1
echo "[pre-commit] tools/doc_check.py（文档一致性）"
python tools/doc_check.py || fail=1
if [ "$fail" != "0" ]; then
  echo ""
  echo "[pre-commit] 拦下了。若 doc_check 报「测试条数不一致」，先跑 ./gradlew test。"
  exit 1
fi
echo "[pre-commit] 全部通过"
HOOK
chmod +x .git/hooks/pre-commit
```

</details>

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

## Mixin 铁律：@Accessor / @Invoker 方法名必须带 `living_item$` 前缀

Mixin 的 `@Accessor` / `@Invoker` 会在**目标类上生成合成方法，名字直接取自接口里的方法名**。
如果名字是从原版字段推导的通用名（如 `getTarget`、`getSkipNextRelease`），**任何其它模组**
只要也访问同一个原版字段、起了同名方法，就会在加载该目标类时触发
`Incompatible @Accessor ... previously written by ...` ⇒ `MixinApplyError` ⇒ **客户端崩溃**
（本模组是「打开创造模式物品栏并点标签页、加载 `Slot` 时」触发）。

📌 **硬性规则**：所有 `@Accessor` / `@Invoker` 方法名一律加 `living_item$` 前缀，
**且必须同时显式指定目标成员** —— `@Accessor("字段名")` / `@Invoker("方法名")`。
接口名可带 `LivingItem` 前缀，但**方法名前缀才是关键** —— 冲突判定以「在目标类上生成的
方法名」为键，与接口名 / 包名无关。新增访问器 / 调用器前先确认方法名全局唯一。

> 🔴 **前缀的代价（2026-10-11 实测踩坑，启动即 FATAL）**：Mixin 靠方法名的
> `get` / `is` / `set`（Invoker：`call`）前缀**推断目标成员名**，裸 `@Accessor` + 带前缀方法名
> ⇒ 推断失效 ⇒ `InvalidAccessorException: Failed to inflect target name ... supported
> prefixes: [get, is]`。**「加前缀」与「显式指定成员名」必须成对出现，缺一即崩。**

> 🔴 **反面案例（2026-10-11）**：
> 1. `SlotWrapperAccessor.getTarget()` 与 inventoryessentials（背包必备）的同名 `@Accessor`
>    命中同一 `CreativeModeInventoryScreen$SlotWrapper` ⇒ 崩溃。已改名
>    `LivingItemSlotWrapperAccessor` + 方法 `living_item$getWrappedTarget()`。
> 2. `AbstractContainerScreenAccessor.getSkipNextRelease / setSkipNextRelease` 是**原版私有字段
>    `skipNextRelease` 推导出的通用名**，与 `getTarget` **同一类隐患**（只是暂未撞上其它模组）。
>    已全部加 `living_item$` 前缀。
>
> ⚠️ `@Inject` / `@Mixin` 注入器的 handler 方法名任意（惯例 `living_item$xxx`），不在目标类生成
> 同名合成方法 ⇒ 无此碰撞。**`@Shadow` 恰恰相反**：它是「别名目标已有成员」，必须与目标成员
> **同名**、**不能**加前缀（加了 = 解析不到目标而失效）；且 shadow 成员被剥离、不生成新成员
> ⇒ 无碰撞风险。（「建议 @Shadow 也加前缀」是 2026-10-11 更正掉的错误说法。）
>
> **「以小见大」原则**：通用名（原版字段派生 / 短方法名）一律默认有全局碰撞风险，
> 先加前缀再写，别等撞了才改。
>
> **判据可复算**：`python tools/check_mixins.py` —— R1 前缀 / R2 显式成员 /
> R3 mixins.json 条目·插件 ↔ 源文件 / R4 @Mixin 源文件 ↔ 配置双向一致；
> 违例即退出码 1（先例同 `check_layers.py`，可挂 CI / 提交前检查）。

## 设计铁律：新增机制前先找对应物

方案里每出现一层**新机制**（扫描器 / 生命周期 / 新挂点 / 新键形），必须能回答
「**现有抽象为什么不够**」。答不上来就退回上一步 —— 症状驱动 + 增量补丁会系统性地
把人带离核心抽象（2026-10-22 实证：活背包本该是「一个活物品功能」，却绕了三轮
viewer 驱动 / 框架扫描 / 框架判定才回到 `LivingItemFunction`）。

**点醒句**（看到征兆就打一句）：冒出新机制 ⇒「现有架构里有对应物吗？」· 连续补丁 ⇒
「回到原点，本来应该是什么？」· 在治症状 ⇒「这是症状还是病？」· 层数在涨 ⇒
「能不能用现有抽象表达？」· 说不清 ⇒「不建这个机制会怎样？」。安全词：**原点**。

## 注释铁律：只写「是什么 + 约束」

注释只写两件事：**这是什么**、**改动它有什么约束**。不写推导过程与背景论证。

**点名其它模组的唯一条件**：这段代码**直接对接**它（联动模块、我们调它的 API、替身类与守卫测试）。
拿它**举例 / 对照 / 论证** ⇒ 泛化成「第三方模组」「某模组」「其它模组的同类实现」。

📌 正例：`// ⚠️ 必须带容量：同一 BE 类型可有多档容量且列数不同`
　反例：`// ⚠️ 必须带容量：某模组 27/45 是 9 列`（点名只为举例）

## 文档导航

文档按**层**组织（契约 / 实现 / 指南 / 参考 / 归档）—— 分层定义、体量约束与维护规约见
[docs/README.md](docs/README.md)（**改文档前先读**）。

**该读哪一篇**：见上方「子系统索引」（每个子系统一行，直链到对应文档）。其余常用入口：

| 想做的事 | 读它 |
|---|---|
| **看不懂某个名词**（活物品 / 锈级 / 相位域 / 跳变 / 口径…） | [glossary.md](docs/glossary.md)（**术语表，先扫这一页**） |
| **查「为什么这么定」/ 某口径是否已被取代** | [decisions.md](docs/decisions.md)（决策索引 + 翻转留痕） |
| 改文档 / 归档 / 校验 | [docs/README.md](docs/README.md) + `python tools/doc_check.py` |
| **设计新活物品 / 写设计稿** | [living-item-design-template.md](docs/guides/living-item-design-template.md) —— **两张横切检查表**（生命周期 / 准入）+ 原版对齐清单 + 规则→验证入口对应表 |
| 写测试 / 跑全量 / mock `Level` | [unit-testing.md](docs/guides/unit-testing.md) |
| **梳理上下游 / 估改动爆炸半径 / 判断模块是否真解耦 / 查新增子系统的扩展点** | [code-map.md](docs/guides/code-map.md) —— 人看 HTML，**AI 直接调 `python tools/gen_code_map.py --query <类名>` / `--extend`**（文本输出、直接解析源码、**不会读到过期图**）；**改完代码跑 `python tools/check_layers.py`**（分层违规即退出 1，棘轮式「只减不增」） |
| **发版本给群友测活TNT** | [living-tnt-testing.md](docs/guides/living-tnt-testing.md) §1~§6（**转发时只发这半段**） |
| 找某个源文件 | `find src/main -name "*.java"` 或 `python tools/gen_code_map.py --query <类名>`（人工文件树快照已删，2026-10-10） |
| 查指令用法 / 加新指令 | [commands.md](docs/reference/commands.md)（指令清单，快照） |
| **查某子系统的待定计划 / 还欠什么** | [TODO.md](docs/TODO.md)（**全项目唯一需求池** —— 2026-10-10 起 tech §0 不再列未实现，D-doc-06） |
| **查原版 / NeoForge / 第三方模组源码** | **直接搜 `libs/src/`，无需解压** —— `libs/src/neoforge-21.1.219-merged/` 是 ⭐ 首选（版本与 `neo_version` 一致）；第三方模组在 `libs/src/<ModName>/`。详见 [living-tool-design.md](docs/buffer/living-tool-design.md) §2.2.1 |
| 查历史变更 | [changelog.md](docs/archive/changelog.md)（按日期倒序） |
| 红电系统总体设计 | [红电系统.md](docs/system-design/红电系统.md) |
| 活潜影箱独立设计 | [活潜影箱实现细节.md](docs/buffer/活潜影箱实现细节.md) |
| **给第三方写扩展 / 查对外契约**（**违反即 bug**） | [api-contract.md](docs/system-design/api-contract.md)（对外开放计划与待决问题池见 [open-plan.md](docs/buffer/open-plan.md)） |
| GUI 点击拦截 / 容器兼容 / 单元测试 / 活TNT测试 | `docs/guides/`（**做法**） |
| **配方书 / 拼音搜索**（跨活物品的共享技术） | `docs/tech/common/` |
| **未定案的草稿**（设计稿 / 计划 / 路线图 / 探讨 / 待办） | `docs/buffer/`（**中间层 —— 不稳定，别当现状读**） |
| 红电波形分析 / 方块朝向 / Sable 投影 | `docs/reference/`（**查表：快照 / 外部资料**） |
