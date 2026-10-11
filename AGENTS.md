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
| **活物品功能（15）** | [活TNT](docs/tech/living-tnt-tech.md) · [活流体](docs/tech/living-fluid-tech.md) · [活熔炉](docs/tech/living-furnace-tech.md) · [活酿造台](docs/tech/living-brewing-stand-tech.md) · [活漏斗](docs/tech/living-hopper-tech.md) · [活箱子](docs/tech/living-chest-tech.md) · [活末影箱](docs/tech/living-ender-chest-tech.md) · [活水车](docs/tech/living-water-wheel-tech.md) · [活地图传送](docs/tech/living-map-ender-pearl-tech.md) · [活耕地](docs/tech/living-farmland-tech.md) · [活工具](docs/tech/living-tool-tech.md) · [活武器](docs/tech/living-weapon-tech.md) · [活红石](docs/tech/living-redstone-tech.md)（信号层含 [活铜](docs/tech/living-copper-tech.md)） · [活涂蜡铜块（红电发电）](docs/tech/living-power-tech.md) · [活打火石](docs/tech/living-flint-and-steel-tech.md) |
| **容器基础设施（4）** | [基础设施](docs/system-design/living-item-infrastructure.md) · [多方块容器身份解析](docs/system-design/container-identity.md) · [数据模型](docs/system-design/data-model.md) · [超大堆叠审计](docs/system-design/oversized-stack-audit.md) |
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

**合计测试用例 577 个**（含参数化展开与 `SimpleContainerContextTest` 的 `@Nested` 内部类）；
全绿基线：`554 passed / 0 failed / 1 skipped`。**逐次新增明细见 [changelog.md](docs/archive/changelog.md)。**

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
| 2026-10-11 | 🐛 **修复：活按钮位置在大箱 / 单箱之间互相覆盖** —— key 原本只用 Screen 类名，而**原版单箱与大箱是同一个 `ChestScreen`**（只差 `ChestMenu` 槽位数）⇒ 共享同一份位置，但 GUI 高度差 56px（166/222）⇒ 拖一个坏另一个。修法：key 改 **类名 + GUI 尺寸**；配置 version 1→2（旧文件忽略、不迁移）。📌 mixin 类 `extends Screen` **非目标类** ⇒ 尺寸**显式传参**（`getXSize/getYSize` 只在 `AbstractContainerScreen` 上） | `docs/system-design/gui-interaction-system.md` §6.2 |
| 2026-10-11 | 🐛 **修复：活酿造台把活物品当耗材（隔离规则漏实现）** —— 用户实测「活红石被当材料消耗」。根因：首版**漏了 `isLivingItem` 检查**（材料 / 燃料 / 瓶子三处），而 §0 规则 7 已写「活物品不作材料 / 燃料」（活熔炉有该检查、酿造台没有）。危害：材料被 `consumeOne` 吃掉；瓶子侧更隐蔽 —— 原版 `hasMix` 只看 `POTION_CONTENTS` **不看活标记** ⇒「活药水」会被 `mix` **原地替换**。修法：抽 `isEligibleIngredient` / `isEligibleBottle` / `isBlazePowder` 三个准入判据，`isBrewable` 与 `doBrew` 共用。新增 `BrewingSlotEligibilityTest`（4 项），测试 573 → 577 绿。⭐ 教训：**§0 规则表每条都必须有实现点与验证入口** | `docs/tech/living-brewing-stand-tech.md` §3.5 / §9.5 |
| 2026-10-11 | 🐛 **修复：活熔炉燃料「绑在槽位」+ 刷燃料漏洞（运行时缓存生命周期，方案 A）** —— `ContainerRuntimeCache` 键为 `(容器键, 槽位)` 且 `removeSlot/removeContainer` **零调用者** ⇒ 同槽位换物品会**继承**旧瞬态状态（熔炉燃料/进度 · 漏斗冷却 · 酿造台进度），**可刷燃料**；跨槽位搬运则丢。⭐ 语义定案（用户）：运行时数据属于「**当前槽位里的那台机器**」。修法：L1 每 tick 对比「槽位→物品类型」登记待清、**L4 执行**（拆开是为避开 L1→L2 的 R1 违规）；⚠️ 判据只看 `Item` **不看组件**（燃烧标志会被 tick 改写，纳入即误清）。新增 `ContainerRuntimeCacheLifecycleTest`（5 项），测试 568 → 573 绿，**分层零新增违规** | `docs/system-design/living-item-infrastructure.md` §3.5 |
| 2026-10-11 | 🆕 **活酿造台第二段：酿造逻辑落地（功能完成，游戏内实测通过）** —— `domain/brewing/` 补 3 类：`BrewingRecipes`（`PotionBrewing` 适配层，把形参名陷阱包成自解释签名）· `BrewingBatchLogic`（**纯逻辑批次状态机**，不碰世界 ⇒ 可单测）· `BrewingStandSegment`（运行时片段）；`LivingBrewingStandFunction.tick` 落地 5 步（补燃料 → 算 canBrew → 推进状态机 → 写运行时 → 仅 dirty 写组件）。⭐ **燃料与材料在批次两端扣**（启动扣 1 次燃料 / 完成扣 1 份材料，作废不退不扣）· **作废判据只有材料**（瓶子不参与）· **堆叠 `step = count`**（每批成本固定 ⇒ 等价 N 台并行）。`fuel` 落盘（`optionalFieldOf` 保证旧数据可解码）。新增 `BrewingBatchLogicTest`（13 项），测试 555 → 568 绿。✅ 游戏内实测通过 | `docs/tech/living-brewing-stand-tech.md` |
| 2026-10-11 | 🏗 **新增构建指纹：启动日志打印 `v<版本> commit=<短哈希> built=<时间>`** —— 兜住今天踩坑2（旧 jar 白跑一轮实测）：崩溃日志可自证构建版本，报错符号名与源码对不上时一眼判「旧产物」。注入复用 `generateModMetadata` 模式（`generateBuildProps` + `src/main/build-props/` 模板，裸占位符不进 jar；无 git 环境兜底 unknown 不阻断构建）。⚠️ 坑入档：`filteringCharset='UTF-8'` 必须显式（Windows GBK 写坏中文注释）；模板引擎展开**所有**花括号引用（注释也不能写）；`build.time` 刻意不作任务输入（防秒级时间戳触发全量重跑） | `build.gradle（构建指纹节）` · `LivingItem.logBuildFingerprint` · `docs/archive/changelog.md（2026-10-11 条目）` |
| 2026-10-11 | 🐛 **修复 Mixin 访问器与 inventoryessentials 重名冲突（点创造标签页崩溃）+ 确立「Mixin 铁律」并落成 `tools/check_mixins.py` 自动校验** —— 同名 `SlotWrapperAccessor`+`getTarget` 命中同一 `SlotWrapper` ⇒ `Incompatible @Accessor`；改 `LivingItemSlotWrapperAccessor` + 方法 `living_item$` 前缀。🔴 两坑入档：①裸 `@Accessor`+前缀 ⇒ 推断失效启动即崩（**前缀必须配显式成员名**，已补 `@Accessor("skipNextRelease")`）②第一轮实测跑旧构建（判据：报错条目名=构建注册名，对不上⇒先怀疑产物过期）。⚠️ 更正：`@Shadow` 必须同名**不能**加前缀。R1 前缀/R2 显式成员/R3·R4 配置↔源码双向，违例退出 1，负面样本验证过；✅ 实测通过 | `AGENTS.md「Mixin 铁律」` · `tools/check_mixins.py` · `docs/archive/changelog.md（2026-10-11 条目）` |
| 2026-10-10 | 🆕 **活酿造台第一段：纯框架接入（5 槽位方向，无酿造逻辑）** —— 新建 `living/domain/brewing/`（`BrewingDirectionData` / `LivingBrewingStandData` / `BrewingStandComponents` / `LivingBrewingStandFunction` / `BrewingStandRegistration`），5 槽 = 3 瓶 + 燃料 + 材料，默认布局「瓶-瓶-瓶 / 燃料-台-材料」（`↖ ↑ ↗ / ← · →`）；`supportsDiagonal()` = true ⇒ **九宫格对角键的首个消费者**；图标零改动（不注册 spec ⇒ 自动挂默认活标记）；`Segment` 与燃料字段留第二段（避免死代码）。测试 555 绿。第二段：`PotionBrewing` 适配 + 400t 批次状态机 + 次数制燃料 + 堆叠加速 | `docs/archive/living-brewing-stand-design.md` §6 |
| 2026-10-10 | 🏗 **GUI 交互改进 第一、二步落地：方向配置升级九宫格 + 活按钮可拖动** —— 缘起活酿造台 5 槽位（4 个基本方向放不下「上方 3 格」）。① 键位扩到 `QWE/ASD/ZC`（**`X` 弃用**，与 `S` 冲突）+ `KeyPressed` 阶段拦 `Q`/`E` + tooltip + 输入 HUD（悬停即显示 / 离开即重置 / 高亮）；② 活按钮按 **Screen 类名**存位置到 `config/living_item/button_layout.json`，空手左键拖动。⚠️ 三个坑入档：`AbstractContainerScreen.mouseDragged` **不转发控件**（改走 `ScreenEvent.MouseDragged.Pre`，不碰 mixin）· HUD 自绘 `fill` 默认 z=0 被深度裁掉（改 `renderTooltip`）· 重写 `AbstractWidget` 鼠标事件**命中判定要自己补、不要盲目 `super`**。③ 对角箭头 `↖↗↙↘` **原版点阵字体没有**（落兜底字体、看着小一圈）⇒ 模组自带字体 `living_item:direction`（用户自绘字形 + `reference: minecraft:default`），经 `DirectionSymbols` 挂字体。🔴 **踩坑：bitmap provider 的 `"file"` 字段必须带 `.png` 后缀**（漏了 ⇒ provider 抛错 ⇒ **整条字体集退化成 `missingFontSet` ⇒ 全框**），第一次因此回退、加上后缀后修好。测试 555 绿 | `docs/buffer/gui-interaction-redesign.md` · `docs/system-design/gui-interaction-system.md` §6 · `docs/system-design/icon-system.md` |
| 2026-10-10 | 🐛 **修复：ProjectE 炼金箱导致运行时同步每 tick 崩溃（ClassCastException）** —— `LivingItem.runtimeContainerInstances` 单 BE 分支**无条件 `(Container) be` 强转**；扫描按 `IItemHandler` 能力面把炼金箱 `AlchBlockEntityChest`（暴露能力但**不实现原版 `Container`**）当成活物品容器 ⇒ 强转失败。`flush` 空集合守卫已闭环安全，修法改 `instanceof Container` 守卫（非 Container ⇒ 空集合 = 不发包）；顺手拆出静态包级私有单 level 分支便于单测，行为等价。新增回归测试 `LivingItemRuntimeContainerInstancesTest`（3 例）全绿；其余 `getBlockEntity → Container` 站点经核**全部 `instanceof` 守卫**，无同类强转。⚠️ 边界：炼金箱等「能力容器」仍被 tick（活物品照常运转），但运行时 tooltip 同步不生效（viewer 匹配依赖原版 `Container`）；是否加容器白/黑名单 = 待决（TODO「容器边界」） | `docs/archive/changelog.md（2026-10-10 条目）` · `docs/system-design/container-identity.md` §5 · `docs/TODO.md` |
| 2026-10-10 | 🐛 **修复：辅助模式挖传送石碑「方块消失但零掉落」** —— 根因在 Waystones：`WaystoneBlockBase#playerWillDestroy` 拿 `Player#hasCorrectToolForDrops(BlockState)` 当**掉落闸门**，而该重载被 NeoForge 标 `@Deprecated`（"use position sensitive version below"）且**不发任何事件** ⇒ 辅助模式（玩家**空手**、活工具在背包）判 false ⇒ 手动掉落整段跳过。修法：① 抽出 `LivingToolAssist#hasBackpackToolFor`（材质门槛**单一来源**，事件入口 `onHarvestCheck` 同步改调它）② 新增 `PlayerAssistHarvestMixin` 补「**直读入口**」（`LivingToolAssist` 的**唯一 mixin**）。⚠️ **行为变化**：任何直读该判据的模组都会认为"背包有活工具 = 玩家有工具"（正是辅助模式语义）。**挖掘速度不变**（30/100 走事件、早已放行）、**主动模式不受影响**（假玩家主手本就有工具，这正是"主动能掉、辅助不掉"的唯一差别）。测试 552 全绿（551/0/1）；✅ **游戏内实测通过**（2026-10-10：空手 + 背包活镐子挖传送石碑正常掉落） | `docs/tech/living-tool-tech.md` §11.6 |

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
| **查原版 / NeoForge / 第三方模组源码** | **直接搜 `libs/src/`，无需解压** —— `libs/src/neoforge-21.1.249-merged/` 是 ⭐ 首选（版本与 `neo_version` 一致）；第三方模组在 `libs/src/<ModName>/`。详见 [living-tool-design.md](docs/buffer/living-tool-design.md) §2.2.1 |
| 查历史变更 | [changelog.md](docs/archive/changelog.md)（按日期倒序） |
| 红电系统总体设计 | [红电系统.md](docs/system-design/红电系统.md) |
| 活潜影箱独立设计 | [活潜影箱实现细节.md](docs/buffer/活潜影箱实现细节.md) |
| **给第三方写扩展 / 查对外契约**（**违反即 bug**） | [api-contract.md](docs/system-design/api-contract.md)（对外开放计划与待决问题池见 [open-plan.md](docs/buffer/open-plan.md)） |
| GUI 点击拦截 / 容器兼容 / 单元测试 / 活TNT测试 | `docs/guides/`（**做法**） |
| **配方书 / 拼音搜索**（跨活物品的共享技术） | `docs/tech/common/` |
| **未定案的草稿**（设计稿 / 计划 / 路线图 / 探讨 / 待办） | `docs/buffer/`（**中间层 —— 不稳定，别当现状读**） |
| 红电波形分析 / 方块朝向 / Sable 投影 | `docs/reference/`（**查表：快照 / 外部资料**） |
