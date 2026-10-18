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
| **活物品功能（16）** | [活TNT](docs/tech/living-tnt-tech.md) · [活流体](docs/tech/living-fluid-tech.md) · [活熔炉](docs/tech/living-furnace-tech.md) · [活酿造台](docs/tech/living-brewing-stand-tech.md) · [活漏斗](docs/tech/living-hopper-tech.md) · [活箱子](docs/tech/living-chest-tech.md) · [活末影箱](docs/tech/living-ender-chest-tech.md) · [活水车](docs/tech/living-water-wheel-tech.md) · [活地图传送](docs/tech/living-map-ender-pearl-tech.md) · [活耕地](docs/tech/living-farmland-tech.md) · [活工具](docs/tech/living-tool-tech.md) · [活武器](docs/tech/living-weapon-tech.md) · [活红石](docs/tech/living-redstone-tech.md)（信号层含 [活铜](docs/tech/living-copper-tech.md)） · [活涂蜡铜块（红电发电）](docs/tech/living-power-tech.md) · [活打火石](docs/tech/living-flint-and-steel-tech.md) · [活信标](docs/buffer/living-beacon-design.md) |
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

**合计测试用例 746 个**（含参数化展开与 `SimpleContainerContextTest` 的 `@Nested` 内部类）；
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
| 2026-10-18 | 🆕 **活信标：宿主判据升级为协议级 `LidBlockEntity`**（用户「岂不是得专门兼容」促成重构）—— 起因：实测铁箱子没光束，实证其 BE 不继承 `ChestBlockEntity` 被白名单漏掉；先写反射 compat 随即意识到逐个兼容不可扩展 ⇒ 改为原版接口判据 `be instanceof LidBlockEntity`（纯 vanilla、零反射零软依赖，compat 已删）。覆盖矩阵：原版箱/铁箱子全家/ **末影箱（新纳入）** ✅、木桶/潜影盒 ❌、未来第三方带盖容器自动纳入零维护。守卫测试 `BeaconFunctionTest`。单测 730 → 733 绿 | `docs/buffer/living-beacon-design.md` §2.3 · `living/domain/beacon/BeaconFunction.java`（isLiddedChest） |
| 2026-10-16 | 🆕 **活信标：容器槽位玻璃参与光束染色** —— 容器 9 列网格里信标**同列向上**的槽位 = 原版「正上方起可叠玻璃」，新 `BeaconGlassColor`（16 色玻璃+玻璃板，DyeColor 口径）取色并**插在颜色扫描序列最前**，与世界玻璃按原版「第 1/2 块都新建段」规则合成 ⇒ **叠放关系不冲突**；容器色随两个宿主 S2C 包下发（客户端不开 GUI 看不到容器内容），世界玻璃仍本地读；非玻璃物品不遮挡。单测 +7（723 → 730 绿） | `docs/buffer/living-beacon-design.md` §2.2 · `living/domain/beacon/BeaconGlassColor.java` · `BeaconHostPacket` / `BeaconPlayerHostPacket`（glassColors 字段） |
| 2026-10-16 | 🆕 **活信标 Phase 4：tooltip 重设计 + 9 柱小金字塔面板** —— 原则「图形已表达的文本不双写」：文本层删差额行 / 范围 / 未激活三行 + 休眠三因（`需 L{n}` / `信标不足`，旧只有「等级不足」一种）+ 空槽规则提示；满级效果行定 C 方案（用户否掉「免费/赠送」味）⇒ `再生 II（满级）`。图形层 160×76 **始终可见**（U22 升级）：实心柱 = 现状、**9 段阶梯刻度 = 各列自己的目标**（各列高度各异 ⇒ 无水平线，mockup 阶段发现并修正上轮表述）、横纹 = 待填、金帽 = 溢出、竖虚线 = 越界列；**数字从差额改目标值**（行动指令）+ 底部双读数（`to L3: 48 more` / `51/83` 含放错列的料）。**不可达兜底**：`columnExists` 位图 ⇒ 信标贴边缘时「宽度不足，信标内移」而非 deficit。几何走 `BeaconPanelGeometry` 纯函数单测；mockup 两份留 `tools/`。单测 +9（713 → 722 绿） | `docs/buffer/living-beacon-design.md` §2.6 · `living/domain/beacon/` · `client/render/LivingBeaconTooltipRenderer.java` · `tools/_mock_beacon_*.html` |
| 2026-10-16 | 🆕 **活信标 Phase 3：效果配置 + 喂锭 + 范围 buff + tooltip + 联机可见** —— ① `OreType`（铁速度/金急迫 L1、下界合金抗性/绿宝石跳跃 L2、钻石力量 L3）+ `BeaconConfig`（5 槽组件，不可变、跟物品走、取消活化清空）+ `BeaconComponents`；② `BeaconFeedHandler`（消耗制；档位不足 / II 非满级 / 未激活全拒 ⇒ **不消耗**；等级从**菜单容器现读**——handler 拿不到 `ContainerContext`；规则走**通配 trigger + predicate**，因精确条目要求 trigger 也是活物品）；③ `BeaconEffects`（生效数 = `min(堆叠数, 档内槽数)` 取前 N；再生三档：有空槽→II / 满配全 I→I / 满配且加倍→无，**5 案例逐字**）；④ 每 80 刻施加 buff（范围 `等级×10+10` 球体撑到世界高、时长 `(9+等级×2)` 秒、ambient；宿主 = 背包主人 / 箱子中心，**末影箱等方块容器自然排除**）；⑤ tooltip 静态账本（5 槽 + 等级不足标注 + 空槽提示）+ 再生档行；⑥ **联机可见收尾**：玩家宿主 S2C（UUID + 等级，256 格定向去重）+ 客户端画其它玩家光束；⑦ **可见性对齐原版**：半径 256 + 水平判据、长度 1024、未加载区块不画、透明方块不截断；⑧ **光束颜色**（U3-A）：两宿主均扫世界方块（头颅顶面上方 / 箱口上方，原版分段+平均），无需同步；⑨ **起点/方向均随头转**（"像帽子"：起点 = 头颅顶面中心、方向 = 顶面法线）。单测 +40（673 → 713 绿） | `docs/buffer/living-beacon-design.md`（§2.5 / §2.6）· `living/domain/beacon/` · lang 双份 |
| 2026-10-16 | 🆕 **活信标 Phase 2：金字塔等级判定 + 箱子宿主 + tooltip 实时行** —— ① `BeaconPyramid`：下方一行 9 列柱高 → 0~4 级（每列 ≥ 目标取最高 N；合计 9/34/83/164 与原版一致、嵌套单调；`BeaconBaseBlocks` 对齐原版 `BEACON_BASE_BLOCKS` tag）② tick 判级 → `BeaconSegment` 遥测（id=beacon 线格式契约；柱高 long[] ⇒ **手写 equals 防数组字段恒脏**）③ tooltip 实时行：等级 / 范围（等级×10+10）/ 金字塔账本（距下一级差额 + 缺列明细）④ 箱子宿主：`blockEvent(1,1)` 开盖保持（20 tick 重发、大箱双半箱都发、**不动 openersCounter 不误触发陷阱箱红石**）+ `BeaconHostPacket` S2C 定向同步（32 格 + 内容去重，照 K2 模式）⑤ 玩家宿主改**本地判级门控**（无条件亮 → level≥1 才亮）。单测 +33（判定 20 / 编解码 4 / 槽位数学 6 / 包往返 2），640 → 673 绿 | `docs/buffer/living-beacon-design.md`（§2.4 / §2.6）· `living/domain/beacon/`（BeaconPyramid / BeaconSegment / BeaconChestHost / BeaconHostPacket…）· `client/render/LivingBeamRenderer.java` · lang 双份 |
| 2026-10-16 | 🆕 **活信标 Phase 1：域包注册 + 玩家宿主光束（纯渲染）** —— 新活物品开工（设计稿同日定稿：1D 金字塔底座 / 等级门控矿石池 / II 仅 L4 / 免费再生三档 / 图标极简 / 两路 tooltip / 复用图谱 §2.7）。本段只落玩家宿主光束：`BeaconFunction` + `BeaconRegistration`（照 `TntRegistration` 范式）+ `LivingBeamRenderer`（挂 `RenderLevelStageEvent.AFTER_ENTITIES`，直调原版 `BeaconRenderer.renderBeaconBeam` 并做 −0.5 XZ 预补偿——该函数内部 `translate(0.5,0,0.5)` 是给方块坐标补中心用的；yaw/pitch 对齐视线与 `LivingToolModelRenderer` 同款；`level.clip` 截断；U4=256 白光）。**无条件亮**（等级门槛属 Phase 2）、不开箱子、不接 buff、无同步。无新逻辑 ⇒ 无新测试，全量回归 640 绿 | `docs/buffer/living-beacon-design.md` · `living/domain/beacon/` · `client/render/LivingBeamRenderer.java` |
| 2026-10-13 | 🐛 **修复：龙息作材料时被整栈吃掉（残留物处理写错）**（用户实测）—— 材料消耗 `consumeOne` 遇到「带合成残留物」就**整栈替换为残留物**，而**龙息带合成残留物（玻璃瓶）且可堆叠（默认 64）** ⇒ 「8 个龙息」变「1 个玻璃瓶」，7 个凭空消失。原版 `BrewingStandBlockEntity#doBrew` 的语义是**先扣 1、再看残留物**：扣完为空 ⇒ 槽位变残留物；还有剩余 ⇒ 残留物掉落。修法：照此改写 `consumeOne`；「掉落」按 D9 改为**另寻归宿**（并入容器同物品未满槽 → 任意空槽 → 容器满丢弃 + debug 日志）。⚠️ 判据：原版**可堆叠**的带残留物酿造材料**只有龙息**（水/岩浆/牛奶桶 `stacksTo(1)`，蜂蜜瓶非酿造材料）；活熔炉 `consumeFuel` 同写法但燃料全是 `stacksTo(1)` ⇒ 无此 bug。⭐ 教训：**「原版如此」也是需要实证的断言**（旧注释「都不带合成残留物」是事实错误，`grep craftRemainder Items.java` 一条命令即可证伪）。新增 6 项测试，634 → 640 绿 | `docs/tech/living-brewing-stand-tech.md` §9.3（重写）/ §10.6（新）· `LivingBrewingStandFunction.consumeOne` |
| 2026-10-13 | 🎨 **修正：白幕「没有进度却还在播」**（用户实测）—— 物品被移出容器后运行时缓存按方案 A 作废（`brewTime` 归零）⇒ 状态机走不到 `Complete`/`Abort`（那两支要求 `brewTime > 0`）⇒ 组件批次标记**永久残留**，客户端拿它当「批次在跑」凭据 ⇒ 白幕空播一轮。修法：标记**只在批次进行中有效** —— 新增纯函数 `BrewingBatchLogic.batchMarkAfter`（`Start` 写当下 / `Continue` 保持 / `default -> -1L` 即 `Complete`·`Abort`·**`Idle`** 全收起），Function 改调它并按「标记变化」判脏 ⇒ **Idle 也收起 = 自愈**；顺带零成本修好旧边界「重启后材料不足空播」。剩余：拿在手上（不被 tick）最多播完当前轮（≤20s）。测试 633 → 634 绿；✅ 游戏内实测通过（2026-10-13：离槽即停 / 材料不足即停）| `docs/tech/living-brewing-stand-tech.md` §8.1（数据流 + 边界重写）· `BrewingBatchLogic.batchMarkAfter` |
| 2026-10-13 | 🐛 **修复：容器键形反推的 3 处同源 bug（背包 / 末影箱 tooltip 遥测）+ 解析收敛唯一入口** —— 用户报「活酿造台 tooltip 进度在只开背包 GUI 时不显示」。根因同源：容器类型在产生处（各 `*ContainerContext` 构造时）本明确，压扁成字符串 key 后各消费方各自反推 ⇒ ① `isJustInventoryMenu` 把「开着背包界面」误判为「没开界面」（原版按 E 开背包**不换菜单**，服务端根本区分不了）⇒ 背包 tooltip 不刷新；② `sendToOwner` UUID 解析漏剥 `_ender_chest` 后缀 ⇒ 抛异常被吞 ⇒ **末影箱从不发包**（其运行时行从未显示过）；③ 客户端按「是不是 InventoryScreen」推断种类 ⇒ 与服务端键前缀判断**相反** ⇒ 读写错位（即使修好②也读不到）。修法：**种类成为一等公民**（`ContainerKind` 随包下发 ordinal + 越界回退，照 `FluidFlowSyncPacket`；发包分派 `kind.isOwnedByPlayer()`；删界面守卫，与 fluid 轨一致）+ **解析收敛**（新增 `ContainerKeys` 唯一入口，6 处调用点改调，原 5 份实现 → 1 份）+ 脚本 `check_container_keys.py`（窄而准零误报）。测试 625 → 633 绿；✅ 游戏内实测通过（2026-10-13：背包与末影箱 tooltip 运行时行恢复）| `docs/system-design/living-item-infrastructure.md` §2.5（清单重写 + 教训升级）· `living/container/ContainerKeys.java` |
| 2026-10-13 | 🆕 **生存背包界面启用活按钮（小人框底居中 42,70）** —— 排除自 37bde65 的背包侧启用。⚠️ 与射线微调同矩形 → **按钮优先**（`InventoryScreenMixin` 避让判定——其 HEAD 拦截先于控件分发，不避让则门禁命中时按钮永远点不到）；⚠️ init 时序坑：`InventoryScreen.init` 在 `super.init()` 后才应用配方书 leftPos ⇒ 书展开时错位一帧，init TAIL 重摆解决。配方书三形态核过（窄屏展开 = 原版书独占，随 GUI 不可点，非缺陷）。**创造界面维持排除 + 原因补考证：活化链路不通**（LivingTagPacket 服务端读 containerMenu.getCarried()，创造 carried 客户端虚拟 ⇒ 恒空静默无效），待定稿单独开工。`LivingButtonLayoutTest` +2，测试 623 → 625 绿。✅ 游戏内实测通过（2026-10-13） | `docs/system-design/gui-interaction-system.md` §6.2.1 |
| 2026-10-13 | 🎨 **活酿造台反馈层简化 + 燃料条 + 通用进度条框架** —— ① 白幕改为**纯客户端本地动画**：组件 `brewStartTick` 降级为批次标记，客户端首见即记本地起点、`f = 1 − (本地now − 起点)/时长` ⇒ **起点与 now 同为客户端时钟、不漂**，删掉 SetTimePacket 校正链路与两个偏差源；**堆叠时长零新数据**（`400/stack.getCount()`，与 `stepFor` 同源）；重播 = 新标记新起点；播完不删条目（防「播完被重建」竞态）。② 新增**燃料条**（原版耐久条同款几何 13×2 + `#F8A820` 烈焰棒提亮色，`fuel>0` 才画）。③ 进度条做成**框架级 `ProgressBarDecorator`**（横/竖、几何纯函数；⭐ 装饰器接口无槽位索引 ⇒ 只能读组件量 ⇒ 天生全场景）。④ `LivingIconSpec.decorator` 可多次调用（NeoForge 原生一物品多装饰器；酿造台 = 条 + 白幕，z 190 < 200）。📌 **零兼容代码**（用户判定：不为没发生过的场景写守卫）。测试 612 → 623 绿；✅ 白幕 / 燃料条画面已游戏内实测通过（2026-10-13）| `docs/tech/living-brewing-stand-tech.md` §8（重写）· `docs/system-design/icon-system.md` §5.1 / 装饰器清单 |
| 2026-10-12 | 🆕 **活酿造台反馈层：酿造白幕 + 完成音效**（用户提出「原版末影珍珠那种图标冷却」）—— ① **白幕**：批次进行中图标底部往上消退的纯白覆盖层，几何/颜色/方向照抄原版物品冷却（`GuiGraphics.renderItem` 源码实证），玩家零学习成本。数据 = 新组件字段 `brewStartTick`（服务端 `level.getGameTime()`，**只在批次开始/中止/完成三个事件写**，稳态零写入）；客户端读组件 + 本地 gameTime 自算宽度（`ClientLevel` 每 tick +1、SetTimePacket 每 20 tick 校正 ⇒ 平滑 ≤1s；非主世界 `DerivedLevelData` 同源 ⇒ 跨维度不跳）。② **音效**：批次完成给「照看者」播原版 `BREWING_STAND_BREW`（世界容器 ⇒ `isViewing` 的开箱玩家；背包 ⇒ 主人本人；假玩家 connection 守卫）——项目第一个活物品音效。③ ⭐ **否决「直接驱动原版冷却」**（`addCooldown` 零代码蹭渲染）：三条语义债源码实证——按物品类型×玩家不按实例 / 冷却期该物品类型右键被丢包（酿造台放不出来）/ 只有见证开始的批次才显示。新增 7 项测试，605 → 612 绿。✅ 白幕与音效已游戏内实测通过（2026-10-13）| `docs/tech/living-brewing-stand-tech.md` §8（新节）· `client/render/LivingBrewingStandDecorator.java` |
| 2026-10-12 | 🐛 **修复回归：世界容器 tooltip 遥测全停（缓存键前缀漏改解析方）** —— 用户实测「活熔炉 tooltip 进度不滚动、熔炼功能正常」。根因：`8ea9aad` 给缓存键加 `<维度>|` 前缀后，发包链路的 `LivingItem.parsePosFromContainerKey` 仍只认裸 `chest_` ⇒ 前缀键解析为 null ⇒ `runtimeContainerInstances` 恒空 ⇒ **运行时同步包停发**；功能逻辑读写同用一把新键所以不受影响（只冻遥测）。修：坐标解析剥前缀 + **有前缀 ⇒ 定向该维度**（dim 即权威，不越维度找补；无前缀裸键保持旧的扫全部世界兜底）。`LivingItemRuntimeContainerInstancesTest` 3 → 9 项。⭐ 教训：**改键形 = 改协议**，必须审计所有按格式解析的消费方（提交信息里点名「坐标解析依赖它」却恰恰漏改它） | `LivingItem.java`（`runtimeContainerInstances` / `parsePosFromContainerKey` / `dimPrefixOf`）· `docs/system-design/living-item-infrastructure.md` §2.5 |

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
