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

> 📄 **更早的记录**：超出保留批次的条目已迁至 [changelog.md](docs/archive/changelog.md)
> （**截至 2026-09-18**；按日期倒序，保留完整变更细节供参考；含红电阶段一~四落地、1 game tick 传播、v8/v8.1 重构周期等全部历史条目）。
>
> **条目体例**：一条 = **一行结论 + 指针**。结论写「做了什么 + 关键约束/坑」，细节写进对应的
> `docs/tech/*.md` 或 `docs/system-design/*.md` —— **这里是入口，不是档案**，别在此铺正文。
>
> **归档规则**：只保留最近 **3 个**更新批次，其余迁入 changelog。校验三件事：
> ① **从最旧批次开始连续迁出**（不跳批次）② **守恒校验**（迁出正文原样搬全）
> ③ **边界单调**（changelog 内容全部早于本节）。
> ⚠️ **不校验日期间隔** —— 开发是间歇性的，「隔了几天」不含信息；
> 旧规则「空档 ≤1 天」会误报且与「保留 3 批次」互相矛盾（详见 [docs/README.md](docs/README.md) §4）。
> ⚠️ **同一天的多个批次必须先合并**（2026-09-22 实测）：边界判据要求
> 「changelog 最新日期 **严格早于** AGENTS 最早日期」，而 AGENTS 的批次日期**只取日期部分**
> （`2026-09-20 · 后半` 这类后缀不参与比较）。若保留一个 `2026-09-20` 却迁出另一个 `2026-09-20`，
> changelog 会出现同日期标题 ⇒ **gap = 0，永远 FAIL**。判据可复算：`tools/doc_check.py` 第 4 项。
> ⚠️ `2026-09-01 ~ 09-04` 曾因漏做归档而**断档**（只在 `.workbuddy-ai/memory/` 日工作日志里，
> 而日志会定期删除），已于 2026-09-16 补录 —— **别再漏**。

### 当前版本: v0.9-alpha

**最近更新** (2026-09-22):
- ✅ 修复：**大当量/超级爆炸后光照不刷新（坑里一片漆黑）**。根因：这两条路径为性能**直接调
  `LevelChunkSection.setBlockState()`**，绕过 `LevelChunk.setBlockState()` —— 原版把光照更新写在那里
  ⇒ 光照引擎不知道方块没了（`NORMAL` 走 `level.setBlock()`，不受影响）。
  ⇒ 新增 `ExplosionComponent.refreshLightAfterBulkEdit()`：重建天光柱高图 → 逐 section 上报空态
  → `propagateLightSources()` → 被炸掉的发光方块逐个 `checkBlock()`。
  **两条红线**：柱高图重建**必须早于**重算（反了 = 没修）；增光与减光是两条路径（后者只管**现存**光源）。
  新增 `ExplosionComponentLightTest`（5 项）。详见 `living-tnt-tech.md` §4.3「⚠️ 光照刷新」。
- ✅ 修复：**大当量爆炸坑不圆、边缘残留整块区块地形**。根因 `ExplosionParams.affects()` 用「区块**中心**
  在半径内」当判据 —— 区块 16×16，"中心在外、边缘在球内"的区块**建档时就被标记完成、永不处理**
  ⇒ 每场爆炸整块跳过 **32~86 个区块**。改为「**区块 AABB ∩ 球体**」（该判据在**三处**生效 = 单点根因）。
  ⚠️ 它曾被 `ExplosionParamsTest` 按"与旧口径一致"**钉住** —— **把既有行为当规格前，先确认它是对的**。
  守卫：边界回归 + **球体全覆盖** `affects_coversEveryBlockInsideSphere`
  （断言"半径内每个方块所在区块必命中"，**已验证在旧判据下会 FAIL**）。
  详见 `living-tnt-tech.md` §4.3「⚠️『哪些区块受影响』的判据」（含漏块量化表）。
- ✅ 定性：玩家报的「方形区域还画着旧方块、**过一会自己消失**」= **客户端重建排队，不是数据没同步**。
  整区块包会让客户端对**每个 section** 调 `setSectionDirtyWithNeighbors`（每次标 3×3×3=27 个），
  一次爆炸改动**上万个 section** ⇒ 排队期间仍画旧几何，排完即恢复（数据其实**立刻**就对了）。
  **判据：会自己消失 = 正常；一直都在 = bug（= 上面 `affects` 那条）。**
  同时把方块同步改到光照刷新**之前**发（方块是用户直接看得见的）。
  ⇒ `living-tnt-tech.md` §4.3 + `living-tnt-testing.md` §5「两种要分清」表（免群友误报）。
- ✅ 文档：**修掉归档规则里的「空档 ≤1 天」**（`docs/README.md` §4/§7 + `changelog.md`/本文件节首）。
  该规则**假设"每天都有开发"** ⇒ 间歇性开发必然误报，且与「保留 3 批次」**互相矛盾**（永远无法归档）。
  改为只校验**边界单调**（`changelog 最新` 必须早于 `AGENTS 最早`）；防丢历史交给**连续迁出 + 守恒校验**；
  相隔 >30 天仅提示。`doc_check.py`：`CONTINUITY_MAX_GAP_DAYS=1` → `ARCHIVE_WARN_GAP_DAYS=30`。
  并借此归档 `2026-09-16` 批次。
- ✅ 资源：**纹理去原版化（56 张）** —— 与原版字节/像素相同的纹理改为**直接引用 `minecraft:` 路径**
  并从包内删除，不再随模组再分发原版资源。**附带收益：这些图标会跟随玩家自己的材质包。**
  涂蜡铜块家族（24 张）规律实测 24/24 零例外 = **原版纹理 + 外圈 60 px 改 `(232,160,62)`**
  ⇒ 改为**模型双层**（`layer0` 引用原版 + `layer1` = 一张自绘 `wax_ring.png` 覆盖全部 24 张）。
  ⚠️ **涂蜡不能用 `IItemDecorator`** —— 装饰器只在 GUI 绘制，而涂蜡是物品身份、必须到处可见。
  `living_item` 纹理数 72 → **17**。详见 `icon-system.md`「纹理约定」+ §5.2「③ 多层叠加」。
- ✅ 文档：**图标「渲染上下文覆盖范围」写成显式约束**（原只在 §2 架构图里暗示）。
  两条机制均为**有意设计**：① `GenericContextAwareModel` 非 GUI 返回 `vanillaModel`
  ⇒ 掉落物/手持/展示框**显示原版外观**（影响**所有**走变体路径的活物品）；
  ② `IItemDecorator` **只在 GUI 内被调用** ⇒ 依赖叠加层的物品（漏斗箭头 · 雕文箭头 · 地图缩略图 ·
  耕地种子 · 红石连线）**在 GUI 之外叠加层消失**。
  ⇒ 组合后果：可能出现「只有基础层」（如活漏斗只剩中心圆）。
  「全形态一致」的 A/B/C 三方案已记录但**暂不实施**（B/C 需游戏内验证图层混合能否复现 `blit` 叠加）。
  详见 `icon-system.md`「渲染上下文覆盖范围（重要约束）」。
- ✅ **箱子/末影箱图标定稿**（2026-09-22 晚，三轮：实验 `20d6ab9` → 误判回退 → 用户拍板）：
  用 `item/chest_3d` / `item/ender_3d`（`parent: builtin/entity`，`gui rotation [0,0,0] scale [1,1,1]`）
  ⇒ GUI 显示**正面视角 14px** 3D 箱子（`ChestRenderer` 物品分支强制 FACING=SOUTH、锁扣在 +Z、
  箱体宽 14 单位）；spec **不挂装饰器**（3D 自身足以辨识「活」）；旧平面图标资源**已删**。
  ⚠️ 1.21.1 判据是 **`isCustomRenderer()`** 而非 `usesBlockEntity()`（后者此版本不存在）。
  详见 `icon-system.md`「已完成的实验」。
- ✅ **GUI 图标光照统一**（2026-09-22 晚，起因 3D 箱子在 GUI 里发暗）：
  ⚠️ **改 light 值没用** —— `GuiGraphics.renderItem` 传的本就是 `FULL_BRIGHT(15728880)`；
  真凶是 `!usesBlockLight()` 分支（true ⇒ `setupFor3DItems()` 法线漫反射，3D 图标亮度只剩 ~0.7）。
  ⇒ `GenericContextAwareModel.usesBlockLight()` **恒 false**（该属性 1.21.1 只被 GuiGraphics 读）。
  另抽出 `LivingIconRenderHelper.renderBlockIcon()` 作为「容器内手绘方块模型」图标统一入口
  （FULL_BRIGHT + 强制 cutout + 异常隔离，替换 mixin 私有实现）⇒ **新加图标光照零配置**。
  ⚠️ **约定：每个 `BakedModel` 包装类都必须 `usesBlockLight() → false`**（原先三个类各手写一遍，
  活水车漏了 ⇒ 3D 水车在 GUI 里发暗，已修；唯一例外是 `GenericLivingModelWrapper` ——
  它 resolve 后不参与渲染）。
  详见 `icon-system.md`「GUI 图标光照约定」。
- ⚠️ 回归（2026-09-22）：**活红石粉装饰器叠了缺失纹理** —— 原因：去原版化时删了
  `textures/item/redstone_dust_dot.png`，但 `LivingRedstoneDecorator.DOT_TEXTURE`
  仍 `fromNamespaceAndPath(MOD_ID, "textures/item/redstone_dust_dot.png")`，**渲染时
  变成黑紫占位色**。**修复**：DOT_TEXTURE 改为引用 `minecraft:block/redstone_dust_dot`
  （白点），与 `models/item/redstone_dust.json` 的 `layer0` 用同一张纹理 ⇒ 模型层画白点
  + 装饰器 `setColor` 染色 = 可见红色点。`redstone_dust_line0.png` 是去底色优化版（md5
  与原版不同），保留。
  ⚠️ **教训（已写进 `icon-system.md`）**：去原版化扫描必须包含 `fromNamespaceAndPath(MOD_ID, ...)`
  —— 该写法不带 `living_item:` 前缀，按 `living_item:textures/...` 扫是扫不到的。
- ✅ 修复：**大箱子（多方块容器）两套槽位体系错位 ⇒ 活漏斗静默不传输**。症状：大箱子里上下
  摆两个「上传下」漏斗，上方**完全不传**；拿走下方、或往输出槽放一个物品都能恢复；下方换
  **任何朝向**都照样"锁住"（**决定性线索** —— 与方向/连接关系无关，只与那个格子有没有东西
  有关）；同布局在单方块箱子正常。根因：`ContainerContext.getContainer` 只返回 pos 那一个 BE
  的容器（大箱子 = 单半箱 27 槽），而 handler 是合并的 54 槽 ⇒ **错位 27**（Container 槽 22 =
  GUI 槽 49）；`simulateInsertItem` 用它读"目标槽现有物品" ⇒ 读到另一半箱的漏斗 ⇒ 判不可插入
  ⇒ 不传且不设冷却。`PlainSlotAccessor` 只在**目标槽为空**时调模拟插入（非空走合并分支），
  这解释了"放一个物品就好"以及它为何藏这么久。⇒ 新增**槽位体系一致性探针**
  `isSameSlotSpaceAsHandler`（①槽位数一致 ②单槽交叉校验），不过则回退 handler；
  **不**在 `getContainer` 里特判合并容器 —— 合并顺序由各 mod 的 IItemHandler 决定，猜错就是
  一道**新的**错位；探针不假设容器结构，三方块/四块/任意多方块容器通用。回退不丢语义
  （`handler.insertItem` 内部转调 `canPlaceItem/isItemValid`，且模拟与真实由此**同源**）。
  ⚠️ **教训**：多方块容器下"按逻辑槽位"的读写，**先验证两套体系是否同编号再使用**；
  模拟与真实写入必须同源。详见 [living-hopper-tech.md](docs/tech/living-hopper-tech.md) §10.25。
- ✅ 修复：**大箱子跨容器传输的面选取 —— GUI 4 方向 → 世界 6 面**。本质：两个半箱共 8 个侧面，
  贴合的 2 个是内部面，剩 **6 个外部面**；左/右（连接轴）各只对应 **1 个**外部面（另一端是
  贴合面，被现有防护挡掉），上/下（facing 轴）各对应 **2 个**外部面（两个半箱各一个，是
  **两个不同方块**）。旧实现按**方向**选单个基准块（下/右→第一块，上/左→第二块），与漏斗
  所在槽位无关 ⇒ 漏斗在后半箱向下推送时，推出去的是**前半箱正面**的方块（**静默推错面、
  不报错**；而左右选错会被内部防护挡住，反而"看得见"）。⇒ 改为 `getBasePosCandidates()`
  返回**候选基准块列表**：首选 = 发起槽位所属那块（`hostSlot / (size/块数)`），其余作备选，
  **逐个尝试**（用户拍板"两个都试"，顺带让大箱子上下能覆盖两个面）。**附带收益**：有备选面
  兜底 ⇒ 不必再为「槽位段 ↔ 位置顺序」做内容探测，假设反了最多影响优先级（对比容器内路径
  §10.25 —— 那条路没有备选，必须靠体系探针）。另加"源/目标解析到同一邻居则跳过"防自传。
  详见 [living-hopper-tech.md](docs/tech/living-hopper-tech.md) §6.4。

**最近更新** (2026-09-20):
- ✅ 修复：**容器规则数据勘误 + 玩家差异持久化 + 开发期导出通道**。起因「打包后没有配置文件」
  —— 实为 jar 内 `assets/living_item/container_rules.json` 里 7 条 IronChests 规则**全错**：
  命名空间写成 `ironchests`（实际 `ironchest`，取自 `IronChestsItems.MODID`）、槽位数 45/36/54/63/72
  **全是编造**（真值 54/45/81/108/108）、`silver_chest` **该模组不存在** ⇒ 这 7 条**从未生效**。
  真值取自 `libs/ironchest-1.21-neoforge-16.0.7.jar` 字节码 `IronChestsTypes.<clinit>`。
  ⇒ 三条机制改动：① `save()` 只写**玩家差异**（新增/覆盖/删除），不再全量回写内置副本；
  ② 新增 `removed` 字段——**删内置规则必须落盘**，否则 `load()` 会把它「复活」
  （`load()` 同时改为幂等：先 `resetState()` 再重建）；③ `/livingitem container export`
  把玩家规则导出为内置资源同格式 JSON，供合并进 `src/main/resources/` 随包发布。
  **jar 内文件运行时不可写**是 classpath 硬限制 ⇒ 导出是唯一路径，正式环境不参与写入。
  `register` 同时放开为**允许覆盖**（此前拒绝，导致玩家无法修正错误内置数据），
  `inspect`/`list` 显示规则来源（内置/玩家覆盖/玩家注册）。新增 `ContainerRuleConfigTest`（9 项），
  全量 **341 用例全绿**。详见 [living-item-infrastructure.md](docs/system-design/living-item-infrastructure.md)
  §5.2/§5.2.1/§5.2.2 + [container-compatibility.md](docs/guides/container-compatibility.md)。

- ✅ 文档：**新增《活TNT测试说明》给测试者用** —— [living-tnt-testing.md](docs/guides/living-tnt-testing.md)。
  18 个编号用例（`T-01`~`T-18`）+ 「提测最小集」。**这是面向测试者的操作说明，不是技术文档**：
  不含实现细节，只写"怎么做 / 期望看到什么 / 看着像 bug 其实不是"。
  重点压在三处**只有真机才能验**的行为：① `T-08` 超级爆炸**服务端不冻结**；
  ② `T-09`/`T-10` **未加载区块走近后补炸**（含存档往返）；③ `T-17`
  `/living_monitor cache` 观测 `loaded区块` 站桩不动**不增长**。
  另附「已知的'看着像 bug 其实不是'」表（分帧扩散消失 / 大当量无掉落物 / 声光先于破坏），
  减少无效反馈。口径以 [living-tnt-tech.md](docs/tech/living-tnt-tech.md) §4.3 为准。
- ✅ 文档：**测试说明按技术文档返工定稿** —— 群友区 `T-01`~`T-13`（原 18 条砍到 13 条，
  删掉"不点燃就不炸""不能重复点燃"等**过于简单/可从代码推出**的条目）；作者区 `A-01`~`A-12`。
  **核心修正（三处此前写错）**：
  * **抗爆逐模式不同**：`NORMAL` 硬编码 `≥100` 跳过（**与半径无关**，黑曜石永不没）·
    `HIGH_YIELD` 判据 `威力 = 半径×(1−距离/半径)×2`，**半径越大越能炸穿**（3456 个时中心威力约 470，
    仍 < 黑曜石 1200）· `SUPER` **不看抗爆值、整区块抹除**（基岩也留不下）。
  * **流体参与抗爆判定**：两种非 SUPER 模式取 `max(方块抗爆, 流体抗爆)`；原版水 = **100**
    ⇒ 普通模式炸不掉、大当量半径够大能清掉。
  * **删掉编造内容**：「活箱子能引爆内部活TNT」「>6912 需活箱子超堆叠」——
    代码依据 `LivingChestFunction.tick()` 是**空实现** ⇒ 活箱子里的活TNT**不被 tick、引信不倒数**。
    超级爆炸的**唯一**触发路径是**铁箱子（模组自带 `container_rules.json` 兼容）塞满**：金箱 81 槽 = 5184、
    钻石/水晶/黑曜石 108 槽 = 6912（覆盖率见 §3 容量对照）。

- ✅ 特性：**容器兼容性社区贡献流程打通（文件级覆盖）**。`export` 改为导出**全量生效快照**
  （内置 + 玩家新增 − 玩家删除，按 ID 排序）而非差异 ⇒ 玩家导出的文件**可直接整文件覆盖**
  `assets/living_item/container_rules.json`，作者无需逐条摘录或写合并脚本，
  重新打包即把兼容性发给所有玩家 —— 「玩家共同完善容器兼容性」从设想变为常规机制。
  配套：`countBundled()`/`countUser()` 统计来源；加载时**重复定义冲突检测**（先到先得 + WARN，
  `differs()` **只比 `containerSize`/`columns`**，描述措辞不同不算冲突，否则被假冲突淹没）。
  解析逻辑抽为 `loadRules(Reader, source, label)`（包级可见），使**作者的覆盖加载路径与内置资源加载
  共用同一套解析+冲突检测**，同时让测试能直接喂入模拟快照验证闭环。
  ⚠️ **修掉一个真 bug**：`save()`/`export` 用 `FileWriter`（平台默认编码，中文 Windows = GBK），
  含中文描述的导出文件拿给 UTF-8 环境会 `MalformedInputException` ⇒ **跨平台交换必锁 UTF-8**，
  三处 IO 全改显式 `StandardCharsets.UTF_8`。
  `ContainerRuleConfigTest` 扩到 **11 项**（新增「导出快照可原样当作内置资源重新加载，无丢失」
  与「合并冲突先到先得、描述不同不算冲突」两条闭环守卫），全量 **343 用例全绿**。
  详见 [living-item-infrastructure.md](docs/system-design/living-item-infrastructure.md) §5.2.2。

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
