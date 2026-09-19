# Living Item (活物品)

**Minecraft 1.21.1 + NeoForge 21.1.x**
*最后更新: 2026-09-16*
*状态: Alpha 测试阶段 - v8.1 接口化重构完成 + 红电相位解读三元件（v19.1）+ 注册式槽位交互扩展点（`SlotInteractions`，2026-09-15）+ 活耕地放置回世界（`BlockItemMixin`，2026-09-16）+ Create 跨区块传送带死锁修复（2026-09-18）*

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
| **框架重构** | HasDirection + HasContainerData 接口化设计 | [framework-refactoring.md](docs/archive/framework-refactoring.md) |

---

## 模块地图

> 只列**目录级职责**（根级文件与关键子目录随附）。**完整文件树见 [docs/reference/file-map.md](docs/reference/file-map.md)**
> —— 那份是快照、会漂移；需要准确清单时用 `find src/main -name "*.java"`。

```
src/main/java/com/qiqi/li/
├── LivingItem.java                          # Mod 主类：tick 入口、网络包注册
├── LivingItemClient.java                    # 客户端入口
├── Config.java                              # NeoForge 配置
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

**合计测试用例 332 个**（含参数化展开与 `SimpleContainerContextTest` 的 `@Nested` 内部类）。
全绿基线：`332 passed / 0 failed / 0 skipped`（2026-09-18 爆炸逐区块分帧 + 待炸账本）。
> 📄 测试环境配置与编写约定见 [unit-testing.md](docs/guides/unit-testing.md)；
> 测试文件树见 [file-map.md](docs/reference/file-map.md)「测试文件树」。

## 开发进展

> 📄 **更早的记录**：早于最近 3 个更新批次的条目已迁至 [changelog.md](docs/archive/changelog.md)
> （**截至 2026-09-14**；按日期倒序，保留完整变更细节供参考；含红电阶段一~四落地、1 game tick 传播、v8/v8.1 重构周期等全部历史条目）。
>
> **条目体例**：一条 = **一行结论 + 指针**。结论写「做了什么 + 关键约束/坑」，细节写进对应的
> `docs/tech/*.md` 或 `docs/system-design/*.md` —— **这里是入口，不是档案**，别在此铺正文。
>
> **归档规则**：只保留最近 **3 个**更新批次，其余迁入 changelog；归档时**必须校验日期连续性**
> ——「changelog 最新日期」与「本节最早日期」之间**不得有空档**，并同步更新本行指针。
> ⚠️ `2026-09-01 ~ 09-04` 曾因漏做归档而**断档**（只在 `.workbuddy-ai/memory/` 日工作日志里，
> 而日志会定期删除），已于 2026-09-16 补录 —— **别再漏**。

### 当前版本: v0.9-alpha

**最近更新** (2026-09-18):
- ✅ 重构：**爆炸破坏改为「按区块分帧 + 待炸账本」**（承上：死锁与强制加载防护之后的第三块）。
  原实现三种模式各写一套"一次性遍历整个球体"，而半径 `= 4.0 × √TNT数`（64 TNT 跨 5×5、
  3456 TNT 达 **31×31 = 961 区块**）—— 球体一部分可能**未加载**：当场读会强制加载
  （单次 289 区块足迹 + 主线程阻塞 + 票据钉住 + 传染其他模组），直接跳过又让**语义残缺**
  （同一场爆炸，玩家站的位置不同、结果不同）。⇒ 采用**原版 TNT 引信模型**「世界只在被观测的
  地方演化」：三种模式统一为**逐区块**执行，唯一入口
  `ExplosionComponent.applyToChunk(level, params, chunk)`；爆炸瞬间只登记
  `ExplosionParams`（参数 + 位图索引映射）+ 声光 + 实体伤害，破坏交给
  **`ExplosionLedger`（世界级 `SavedData`，参数 + `long[]` 完成位图）** —— 已加载的按预算
  每 tick ≤32 个区块分帧应用，**未加载的等它自然加载时补上**（`ChunkEvent.Load` 只登记坐标，
  走 tick 阶段应用，符合两条红线）。顺带修掉两个既有 bug：① 旧
  `PENDING_SUPER_EXPLOSIONS` 服务端关闭**不清理** ⇒ 单人换存档（不重启 JVM）持有旧
  `ServerLevel` 内存泄漏；② `tickAll` 每 tick 只处理 1 个区块、且跳过未加载时无条件推进
  （静默残缺）。新增 `ExplosionParamsTest`（4 项）+ `ExplosionLedgerTest`（9 项），
  全量 **332 用例全绿**。详见 [living-tnt-tech.md](docs/tech/living-tnt-tech.md) §4.3 +
  [living-item-infrastructure.md](docs/system-design/living-item-infrastructure.md) §3.2.2 +
  decisions.md `D-tnt-01`。
- ✅ 修复：**Create 跨区块传送带导致服务端线程死锁**（1.3.2 + Create 6.0.10，进世界即卡死：
  GUI 能动、不能合成/传送、玩家掉虚空）。根因：`ChunkEvent.Load` 回调里做能力查询 →
  Create 传送带 provider 去查**另一个区块**的 BE → 同步区块加载 → 自等自（同区块不卡，
  因为原版 `currentlyLoading` 旁路只覆盖正在加载的那个区块）。修复：`onChunkLoad` **只登记
  坐标**，扫描推迟到 `ServerTickEvent.Pre`（每 tick 限量 64）。详见 §3.2 + `D-core-04`。
- ✅ 新增：**强制加载防护（处理侧限定 ticking 区）+ 可观测性**。被动路径同样会强制加载：
  活物品读**相邻一格**（红石 `getSignal` / 活漏斗邻居容器 / 大箱子另一半 / 活水车下方一格），
  而框架取 **loaded 区（≤33）** ⇒ 容器在"已加载但不 tick"的**最外一圈**时邻居在**未加载**区
  ⇒ 单次要凑 **289 区块**足迹、且票据续期会把邻居**永久钉住**在视距外。修复：
  `getProcessableChunks(ServerLevel)` 处理前过 `isPositionTicking`（差别**恰好只有危险带那一圈**；
  ticking 区块的 **3×3 邻域必为 FULL** ⇒ 本模组入口**可证明安全**、外扩链条被切断）。
  ⚠️ **扫描不做此过滤**（区块提升到 ticking 没有对应事件）。新增 `describeCacheStats` +
  调试命令 **`/living_monitor cache`**（缓存/可处理/loaded 区块/视距基准）⇒ 钉住与外扩可测量。
  详见 §3.2.1 + `D-core-05`。⏳ 未做：**爆炸路径**（§3.2.2）、**第三方 provider 伸远**（不可控面）。
  顺带修掉 §3.2 两处旧口径与一处错位/重号小节。

**最近更新** (2026-09-16):
- ✅ 新增：**活锄头跨模组兼容 + 可耕土扩展**——原先「活锄头」是**写死的 6 种原版锄头**
  （注册侧枚举物品 + 校验侧 `instanceof HoeItem`，两侧口径还不一致），其它模组的锄头
  活化后**耕不了活泥土**（客户端根本不拦截）。改为语义判定：新增
  `domain/farmland/Tillables`（唯一真源）—— 锄头只认
  `canPerformAction(ItemAbilities.HOE_TILL)`（NeoForge 对自定义工具的官方口径：原版
  锄头经 patch 自动满足，模组锄头重写 `canPerformAction` 即自动兼容；**不做**
  instanceof / 标签 / 配置兜底）；可耕映射对齐原版 `IBlockExtension#getToolModifiedState`
  的 HOE_TILL 分支（以 neoforge-21.1.249 源码为准：**泥土/草方块/土径 → 耕地，砂土/
  缠根泥土 → 泥土**，再耕一跳才变耕地；灰化土/菌丝原版不可耕，不纳入；「上方必须是
  空气」与「缠根泥土掉垂根」属世界副作用，物品层 GUI 交互不做）。注册处 6 条精确规则
  → **按可耕目标逐条注册通配条目 + `Tillables::canTillWith` 谓词**（与 plant_crop
  同构）；谓词内**必须**自查 `isLivingItem(trigger)`——通配分支不校验活物品，漏了会吞
  掉原版拿起/分堆。handler 改为查表取产物，且创造模式同样校验光标（只免耐久消耗）。
  新增 `testutil/FakeHoe`（模组锄头替身：⚠️ **测试期物品注册表已冻结，不能 `new Item`**，
  否则静态初始化抛 `Registry is already frozen` 导致整类 17 用例全红且看不出原因——改用
  `Mockito.spy(Items.STICK)` + stub `canPerformAction`）+ `TillablesTest`（8 项）+
  `TillToFarmlandCompatTest`（8 项），全量 **313 用例全绿**。收编
  living-farmland-tech.md §3.1/§10.1/§10.2/§10.3/§11.17/§12.1 +
  gui-interaction-system.md 处理器表 + file-map.md。
- ✅ 修复：**活耕地种子图标在 HUD 快捷栏不渲染**——原实现画在
  `AbstractContainerScreenMixin.render @TAIL`，硬依赖 `leftPos`/`topPos`（只有
  `AbstractContainerScreen` 有），而快捷栏走 `Gui.renderHotbar` → `renderSlot` →
  `GuiGraphics.renderItemDecorations` → `ItemDecoratorHandler`，**从不经过该屏幕**。
  迁到 `IItemDecorator`（`client/render/LivingFarmlandSeedDecorator`，自抬 z=200）：
  装饰器在快捷栏/容器 GUI/创造物品栏都会被调用，**一份代码全覆盖、只画一次**；
  容器 Mixin 里那段重复 blit 已删除（生长槽大图保留——它需要「同容器正上方一格槽位」
  的邻居关系，装饰器拿不到容器槽表）。新增 `LivingFarmlandSeedDecoratorTest`（4 项），
  全量 **297 用例全绿**。收编 icon-system.md（三层架构 + §5 表格补活耕地行 + 新增
  「种子图标改走装饰器路径」小节，含坐标/z/渲染状态三处契约）+ living-farmland-tech.md
  v1.11 §8.2/§10.1/§10.2/§10.3/§12.3。
- ✅ 新增：**放置活耕地回世界时自动种下自带作物**——把一块「已种植」的活耕地物品
  放置到世界里，放置出的耕地上直接长出那株作物。**做法：模拟玩家右键**
  （构造 `UseOnContext` 调 `ItemStack.useOn`），**不是**自己 `setBlock`——后者会绕过
  模组在 `canSurvive` / 覆写 `useOn` 里的校验（如「水稻只能在水下种」），种出非法状态。
  改用 `useOn` 后连 `CropClassifier`、`canBeReplaced` 检查、`is(Blocks.FARMLAND)`
  检查都不需要了（种子自己的 `canSurvive` 会校验下方是耕地），代码反而更短。
  入口：`mixin/BlockItemMixin`（`@Mixin(BlockItem.class)`，注入 `place` 的 `consume`
  **之前**——注在 `@At("RETURN")` 会因空栈 `getComponents()` 返回 EMPTY 而
  **只在单块放置时静默失效**）+ `domain/farmland/LivingFarmlandPlacement`（约 30 行）。
  **这是「软逻辑」**（用户定调）：能种上就好，种不上（含抛异常）一律静默、整段
  try/catch，绝不影响原版放置流程。口径：**一律种成幼苗**（不保留成熟度，用户定调）；
  多格作物上部件不放置、交给原版长；`pendingDrops` 掉落不丢。新增
  `LivingFarmlandPlacementTest`（5 项，含「异常不冒泡」红线与**端到端 Mixin 接线**用例），
  全量 **293 用例全绿**。
  收编 living-farmland-tech.md v1.10 §3.5 + §10.1/§10.2/§10.3 + §11.16（三坑：
  `RETURN` 注入 / `isClientSide` 字段不可 mock / 别 `setBlock`）+ §12.1 实测清单。

**最近更新** (2026-09-15):
- ✅ 新增：**活漏斗自动施肥（骨粉 → 活耕地）**——活漏斗按 WASD 方向传输时，
  货物是骨粉且目标槽位是活耕地 → 绕开通用插入（活耕地是活物品非存储容器，
  SlotAccessorFactory 必然 null——通用路径每 tick 空转），改走施肥消耗 1 个
  骨粉触发一次生长 tick（forceGrowthTick：未成熟 +1 / 成熟待输出空 → 冻结
  产出）。**触发物口径有意区分**（用户定稿）：手动 = 活化能力（GUI 右键要活
  骨粉），自动 = 物流集成（**普通骨粉**即可，骨粉生成器/原版漏斗物流可直接
  对接；活骨粉也放行）。equals 零空转：对着已冻结成熟耕地不烧骨粉。节奏 =
  漏斗冷却（8t 随堆叠加速，一次施肥 = 一次传输）。实现三处：
  `LivingFarmlandFunction.tryFertilize`（入口）+ `TransferPipeline.executeInContainer`
  施肥分支（流程图 [3.5]，置于 isTransferableSource 之前放行骨粉）+
  `CrossContainerTransfer.pushToNeighbor` 跨容器版（tryFertilizeToNeighbor
  邻居槽位迭代；getStackInSlot 实时引用改组件即刻生效，无需回写 handler）。
  回归测试 FertilizeTransferTest（6 项），全量 268 用例全绿；收编
  living-hopper-tech.md §2.2 流程图 [3.5] + §6.2.1 跨容器施肥小节 +
  living-farmland-tech.md v1.7 §7.1（口径对照表）+ §12.2 验证项 + §10.3
  ⚠️ 本条的「活骨粉也放行」与实现三处之说已于同日修正/收编，见下方两条（口径修正 + 注册式分发）
- ✅ 修复 + 重构：**跨容器施肥「推送生效、拉取失效」**——施肥分支原先只内嵌在
  推送方向与容器内管道，拉取方向 `pullFromNeighbor` 走通用路径，而
  `SlotAccessorFactory.create` 对非箱类活物品直接 return null（活耕地正是
  「活物品 + 非存储容器」）→ 目标槽必然失败，骨粉送不进去也永远不施肥。
  **修复 + 结构性收编**：方程抽成注册式槽位交互
  `SlotInteraction` / `SlotInteractions`（内置条目 `FarmlandBonemealInteraction`），
  三处传输分支只调分发器（`tryInteract` 已知货物 / `tryInteractFromNeighbor`
  拉取方向）——**今后新增同类交互 = 1 个实现类 + 1 行注册，零传输代码改动**。
  顺带完成跨容器能力全量审计（`living-hopper-tech.md` §6.2.2 覆盖矩阵 +
  结构规则「特殊槽位识别只在 containerCtx 一侧生效」）。回归测试
  `CrossContainerTransferFertilizeTest`（8 项，两个入口各覆盖），全量 276 用例全绿。
  ⚠️ 该文件于同日口径修正轮扩至 **15 项**（补推送方向隔离守卫 + 活骨粉三入口全拒），见下条
- ✅ 自查修复：**重构自引入的「活骨粉施肥失效」**——交互源槽起初用
  `SlotAccessorFactory.create` 拿 Accessor，而它开头就拦非箱类活物品（活骨粉正是），
  导致 `tryInteract(null, ...)` 恒 false（普通骨粉不受影响，故只测普通骨粉看不出来）。
  新增 `SlotAccessorFactory.createForInteraction`（不拦活物品，只读源槽自身物品，
  不展开活箱子虚拟存储），容器内交互改用它；活物品隔离规则本身未动。
  新增 `SlotInteractionFactoryTest`（4 项）钉住该边界，全量 **280 用例全绿**。
  ⚠️ 本条的 `createForInteraction` 与 `SlotInteractionFactoryTest` 已于同日随口径修正移除，见下条
  附带统一：三处交互源槽都带黑白名单过滤（原先容器内路径在过滤检查之前）。
  新增 `SlotInteractions.canInteract` 廉价筛选谓词（分配 Accessor 之前先筛，
  避免每次传输尝试白分配两个小对象；拉取方向每轮最多省 27 次），全量 281 用例全绿。
- ✅ 修正口径（用户定案）：**漏斗只认普通骨粉，活骨粉不施肥**——上一版把「活骨粉也放行」
  统一到四个方向，方向错了：施肥的语义是「活漏斗用**传输能力**把骨粉送进活耕地」，
  属传输语义 ⇒ 必须受漏斗自身的货物规则（**活物品不作货物**）约束，不能因为
  「反正要消耗掉」就开洞。规则收在**唯一定义点** `SlotInteractions.isEligibleCargo`
  （活物品不作货物，活箱子/活末影箱除外），传输层 `isTransferableSource` 直接委托，
  交互层两个入口共用——**调用点顺序变更也绕不过**；`tryPushToNeighbor` 另留循环自守
  （非合法货物绝不进入通用插入/合并）。同时移除已无用途的
  `SlotAccessorFactory.createForInteraction`（那是为「活骨粉放行」加的）。
  口径 = **手动要活化、自动要普通**。新增 `SlotInteractionCargoGateTest`（5 项），
  同期 `CrossContainerTransferFertilizeTest` 由 8 项扩至 15 项、移除
  `SlotInteractionFactoryTest`（4 项），全量 **288 用例全绿**
  （测试树与基线数字已按实测同步：`288 passed / 0 failed / 0 skipped`）。

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
| 找某个源文件 | [file-map.md](docs/reference/file-map.md)（完整文件树，快照） |
| **查原版 / NeoForge / 第三方模组源码** | **直接搜 `libs/src/`，无需解压** —— `libs/src/neoforge-21.1.249-merged/` 是 ⭐ 首选（版本与 `neo_version` 一致）；第三方模组在 `libs/src/<ModName>/`。详见 [idea.md](docs/idea.md) §2.2.1 |
| 查历史变更 | [changelog.md](docs/archive/changelog.md)（按日期倒序） |
| 红电系统总体设计 | [红电系统.md](docs/system-design/红电系统.md) |
| 活潜影箱独立设计 | [活潜影箱实现细节.md](docs/reference/活潜影箱实现细节.md) |
| 自定义槽位 / 配方书 / WASD / GUI 点击拦截 / 容器兼容 / 框架演进 | `docs/guides/` |
| 红电波形分析 / 方块朝向 / Sable 投影 / 拼音搜索 | `docs/reference/` |
