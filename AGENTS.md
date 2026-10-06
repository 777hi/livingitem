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

**合计测试用例 519 个**（含参数化展开与 `SimpleContainerContextTest` 的 `@Nested` 内部类）。
全绿基线：`518 passed / 0 failed / 1 skipped`（2026-10-07 收尾审查：`ContainerFluidDataTest`
新增 2 项（晋升只数同流体邻源 / 到达时间竞争慢者让位）、`FluidFlowServerSyncTest` 新增 3 项
（末影箱派发必须判 viewer / renderTargetOf 两类 / 非末影箱不受 viewer 门影响）；
2026-10-07 到达时间抢占：新增「两源太近 ⇒
岩浆源变黑曜石」守卫 1 项，㉝/㊶ 改为生产节拍下的可用几何；
2026-10-07 消除异种驱逐空档：
`ContainerFluidDataTest` 新增 2 项 —— ㊾ 不驱逐 + 接触面圆石（水全程不动）、
㊿ 倒进异种流动格 ⇒ 黑曜石 + 源湮灭 + 水灌回；
2026-10-06 倒桶口径对称化 A 档：
`ContainerFluidDataTest` 新增 ㊼ 反向覆盖（水源被岩浆覆盖 + 下游水退走），
`LivingBucketInteractSupportTest` 改写为对称口径 3 项；
2026-10-06 倒桶对齐原版 B 档：
新增 `LivingBucketInteractSupportTest` 3 项（契约默认 false / 熔岩三条分支 / 倒桶判定四例）+
`ContainerFluidDataTest` ㊻ 覆盖语义；
2026-10-06 回退+末影箱派发判 viewer：
`ContainerContextsTest` 新增 `IsViewingEnderChest` 6 项（末影箱菜单 true / `ChestMenu` 但普通箱容器
false / 非 `ChestMenu` false / null false / 玩家侧两条）+ 重建 `FluidFlowClientCacheTest` 4 项
（三份快照互不串 / `PLAYER_INV` 不翻转「非背包组」提示 / `clear()` 复位）；
2026-10-06 实测三修：新增
`ContainerFluidHandlerTest` ㊾⁺（慢管道抽不到 / 整源逐个抽）、
`GuiInteractionPacketTest` 2 项索引错位回退；
2026-10-06 管道抽取：新增
`ContainerFluidHandlerTest` 6 项（tank 枚举与稳定序 / SIMULATE 不消耗 vs EXECUTE 删源 /
全有或全无 / 异种 EMPTY / fill 恒 0 + isFluidValid / 非容器无害 + provider 四段让位）；
2026-10-06 统一时钟：`ContainerFluidDataTest`
新增 3 项 —— ㊸ 晋升只在推进拍 / ㊹ 等价性（晋升只变慢、终态同瞬时基线）/ ㊺ 推动按流体节拍，
⑥ 改钉「随蔓延同拍推动」；
2026-10-06 黑曜石循环 + 转化表收窄：
`ContainerFluidDataTest` 新增 4 项 —— ㊴ 源格遇水⇒黑曜石 / ㊵ 黑曜石被焚毁 /
㊶ 端到端循环 / ㊷ 契约默认回退，另 3 项随口径改写、缩容守卫移至 `FluidTransformTableTest`；
2026-10-06 活熔岩口径更正：`ContainerFluidDataTest` 新增 2 项 —— ㊲ 源格也焚毁 / ㊳ 岩浆不转化；
2026-10-05 F-1：`ContainerContextsTest` 新增 2 项 ——
末影箱菜单槽位可解析 / 非容器槽位仍返回 null；
2026-10-04 活化时机钩子收编：
`ActivationHookTest` 新增 7 项 —— 派发参数原样送达 / 顺序不变量 / 未认领物品不派发 /
数据安全否决 / 箱子无玩家拒绝且内容保住 / 末影箱不绑定且照常解绑 / owner 只由活工具钩子写；
2026-10-04 活桶汲/倒包修复：`GuiInteractionPacketTest` 新增 3 项；
2026-10-04 末影箱崩溃修复：`SimpleContainerContextTest` 新增 4 项；
2026-10-04 流体侧批次三 F4：`FluidTransformTableTest` 新增 6 项；
2026-10-04 B.5 第三项：`ContainerFluidDataTest` 新增 1 项 ——
玩家背包/末影箱落盘 `KEYED_CODEC` 往返；2026-10-04 Q6 批次 B：`ContainerContextsTest` 新增 10 项 ——
边界带共享内核 `ownsContainer`（大箱 `CompoundContainer` 特判）/ `isSameSlotSpace`（槽位体系探针）；
此前 2026-10-03 流体侧批次二 F2/F3 + 批次一 F1：`ContainerFluidDataTest`
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
| 2026-10-07 | 🔧 **收尾审查批次（六维度审查后修复）**：① 🔴 **晋升邻源计数不分流体**（真 bug）⇒ 岩浆源会被算进水的「≥2邻源」⇒ 流动水夹在两个岩浆源之间错误晋升；② 删零调用的 `exportFlowData()`；③ `onContainerClose` 两处 `return` ⇒ `continue`（多容器菜单漏清）；④ `representativeFluid` 重复实现 + ConcurrentHashMap `put(null)` 潜在 NPE ⇒ 统一委托；⑤ `isFluidValid` 改按 tank（NeoForge 契约）；⑥ 客户端 javadoc。⚠️ 口径更正：晋升/焚毁 SPAWN_SOURCE **直接写实际层（当拍）**，倒桶/汲走/管道**只写 generatedSources（下一拍）**。测试 +5：同流体邻源、慢者让位、**末影箱派发判 viewer 的接线守卫**（此前只有判据用例）、renderTargetOf。**519 测试全绿** | `living-fluid-tech.md` §3.2；`buffer/living-fluid-review-2026-10-07.md` |
| 2026-10-07 | 🔧 **目标层抢占：距离 ⇒ 到达时间**（消掉刷石机的"看不见的墙"）：那格在活水源流域内、水到得更快却永不进水 —— 根因是分配用**距离+入队顺序**、与"实际多久流到"无关（统一时钟只管蔓延、不管分配）。定档：多源 Dijkstra 按到达时间抢占（每格成本=该流体节拍，源格永不被蔓延抢占）。🔴 **实测**几何结论：岩浆守得住 ≈ 两源间距的 1/7 ⇒ 太近（曼哈顿 ≲6）则水贴到岩浆源 ⇒ 黑曜石+源湮灭（刷石机自毁），**D≥7 则源存活 + 圆石照常累加**（27 格 D=7、9 格单行 D=8 实测圆石累加到 x7）。⚠️ 我先前" A 会毁掉刷石机 / 必须配产物挡路"两处断言**都是错的**（以偏概全 + 归因错误），已留痕。**514 测试全绿** | `living-fluid-tech.md` §3.1；`buffer/living-fluid-arrival-time-claim.md` |
| 2026-10-07 | 🔧 **消除「异种流体被当拍驱逐 ⇒ 接触前空档」**：现象= 活熔岩倒在活水流旁 ⇒ 中间空一格约 1.5s（岩浆 30t/格）才变圆石。根因三条叠加：目标层混合 BFS **按距离抢占** + `pruneActual` **当拍删** + 非源格蔓延**按流体节拍**。定档：**目标层 BFS 跳过「实际层已被异种流体占据」的格** ⇒ 熔岩前沿停在接触面、逐格凝固，**从不驱逐**（对齐原版接触面反应）；播种仍覆盖（倒进异种**流动**格 ⇒ 覆盖 ⇒ 成岩浆源 ⇒ 紧邻水 ⇒ 源格反应出**黑曜石** + 源湮灭）。⚠️ 代价：目标层不再是纯 BFS 纯函数（读实际层）—— 换来 `pruneActual` 只剩「目标消失」一个职责，**概念变轻**。🔍 测试踩坑：基线水是**瞬时**（speed 0、上限 7）⇒ 一拍铺满 0..7，别用「跑 N 拍」假设它只流了 N 格。**513 测试全绿** | `living-fluid-tech.md` §3.7；`buffer/living-fluid-no-displace-fix.md` |
| 2026-10-06 | 🔧 **倒桶口径对称化（A 档）**：追问「为什么异种源格语义要不同」后查实 —— 上一批**用错了函数**：`canBeReplacedWith`（`getHeight() >= 0.444`）属**蔓延**路径（能否流进），`BucketItem` 不查它；倒桶只问 `canBeReplaced(f) = ... \|\| !isSolid()` ⇒ 液体块一律可替换（`LiquidBlock` 也未实现 `LiquidBlockContainer`）⇒ **原版对水/岩浆、源/流动一律替换**。⇒ 契约去掉 `selfIsSource`、**水与岩浆对称覆写**（岩浆桶倒进水格从「拒绝」改为「变岩浆源」）、default `false` 重新定位为「未覆写流体保守拒绝」；`pour` 简化为三态。**511 测试全绿** | `living-fluid-tech.md` §5 / §3.5；`buffer/living-pour-source-replace.md` §0 |
| 2026-10-06 | ✅ **倒桶路径对齐原版（B 档，后被 A 档取代）**：契约加 default `canBeReplacedBy(incoming, selfIsSource)`（未覆写 ⇒永不被替换），熔岩覆写 `源格+水`、**水不覆写**。`pour` 改为三态：空格/流动格 ⇒ 诞生源（**异种流动格仍覆盖** = B 档保留的宽松口子）、同种源 ⇒ 源不变、**异种源格 ⇒ 该格变成水源**（活水桶浇灭活熔岩源，同键覆盖 + 下游自然退走）；活岩浆桶倒进水格**被拒**（对齐 `WaterFluid`）。🔴 2D 投影结论：原版 0.444 高度门槛在 `maxLevel 3` 下恒不达标 ⇒ 严格对齐 ≡「只有源格可替换」。⚠️ 认知更正：原版「倒水进岩浆源 ⇒ 黑曜石」**不存在**（黑曜石只来自熔岩蔓延到水格 = 机制五），此前记成待做是错的。**510 测试全绿** | `living-fluid-tech.md` §5 / §3.5；`buffer/living-pour-source-replace.md` |
| 2026-10-06 | ↩️ **回退「砍掉原版末影箱当流体容器」+ 🔧 修第 ③ 次渲染泄漏**（同日第五批次）：砍掉那次把 `processEnderChest` 一并删了，而它是**第 4 条完整 tick 入口**（与玩家背包同一条 `processContext`）⇒ 末影箱里**全部**活物品机制失效（打火石/耕地/漏斗/锁/活末影箱物品路由 + 红电），**超出「砍流体」范围、破坏硬边界「不动活末影箱物品」** ⇒ 用户改判为「回退 + 修 bug」。修法：末影箱派发**判 viewer**（`ContainerContexts.isViewingEnderChest`，唯一判据实现点，活化绑定改为委托）—— 此前无条件每 tick 直发 ⇒ 关末影箱开普通箱子后包仍在来 ⇒ 客户端 `chestLikeTarget` 提示翻转 ⇒ **末影箱的水渲染到别的箱子界面**（三次泄漏里唯一没修过的那个）。**506 测试全绿** | `living-fluid-tech.md` §7/§9；`buffer/living-ender-viewer-dispatch-fix.md` |
| 2026-10-06 | 🏗 **架构分层第一步**：`interaction` 的 9 个领域专用 handler 归位（farmland×3 / redstone×4 / tnt×2）⇒ 该包只剩通用机制，**`InteractionRegistry` 一行未改**。**R1 分层违规 113 → 101**，基线 42→39 对。**484 全绿**（纯重构） | `buffer/architecture-layering-plan.md` ① |
| 2026-10-06 | 🔧 **实测三修**：① **末影箱流体不渲染** —— 客户端无法推断界面容器身份（末影箱 GUI 在客户端是 `GENERIC_9x3` + `SimpleContainer` 替身，与普通箱子同形）⇒ `instanceof PlayerEnderChestContainer` 是**死分支**；修：包加 **`RenderTarget`（服务端权威告知）**，客户端按它路由。🔴 **约束成文：客户端不得靠槽位容器类型推断容器身份**（两次踩坑同源：键前缀猜 ⇒ 泄漏；类型判 ⇒ 不画）。② **创造模式背包倒不进活流体** —— 客户端 `ItemPickerMenu` vs 服务端 `InventoryMenu` 索引错位，索引直查落到无关空槽（合成结果槽）⇒ 静默失败；修：空槽目标加「必须能解析出活容器」的门 + 回退 `containerSlot`。③ **管道瞬间抽空** —— v1「任意请求吞整源只返请求量」；定稿（用户拍板）**整源单位**：drain 请求 ≥1000 才给满 1000 并删源，<1000 **一分不给**。曾短暂上「源余额账本」（部分抽取）并**撤回** —— 源到处是二进制语义（挤没/晋升/汲走/刷石机/黑曜石/渲染/落盘），分数源污染每条路径；「无限源」也撤回。细水长流留给**专用流体活物品**（照抄活涂蜡铜灯存电）。**496 测试全绿** | `living-fluid-tech.md` §7 / §10.3；`changelog.md`（2026-10-06） |
| 2026-10-06 | ✅ **管道抽取（活水源对外流体能力）**：新 `ContainerFluidHandler`（NeoForge `IFluidHandler`）—— **tank 数 = 派生源数、drain 直接消耗源、fill 恒 0（只出不进）、isFluidValid 如实答「是否持有」**；宽注册全部 BE + provider 四段让位（与红电同款）。活数据反查把 `processContainerAt` 的上下文构建抽成 `resolveContextAt`（**与 tick 路径同源同键**）+ `peekContainerData` 只读不创建。🔍 **Create 6 / Mekanism / Pipez 通用、无需兼容代码**（Create 6 内部 tank 就是 NeoForge `FluidTank` 模板，管道只拉不推）。⚠️ 速率模型修正：单源再生间隔 = `flowSpeed`（水 ≤5t）。**493 测试全绿** | `living-fluid-tech.md` §10 |
| 2026-10-06 | ✅ **统一时钟：生长类逻辑一律走该流体自己的节拍**（同日第四批次）：此前引擎有**三个时钟**（目标层每 tick / 实际层每流体节拍 / 推动固定 4t）⇒ 错配：岩浆 30t 爬一格却 4t 推物品、水 5t 长一格却 4t 推。定稿「**流动的事按流体节拍走；外界引起的事当拍生效**」：**晋升搬到实际层 + 该流体推进拍**（判定改读实际层邻源，要求本格真有流体；收敛循环删除，目标层退化为纯 BFS）、**物品推动按流体分组随蔓延同拍**（水 5t / 岩浆 30t，`FLOW_STEP_TICKS` 删除）。焚毁/前沿反应/转化/挤没**故意不上时钟**（外部输入等 30t 手感说不过去）。⚠️ 三连源再生 1t ⇒ ≤5t。**487 测试全绿** | `living-fluid-tech.md` §3.2/§3.3/§3.6；`buffer/living-fluid-single-clock-plan.md` |









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
