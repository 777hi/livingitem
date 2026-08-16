# Living Item (活物品)

**Minecraft 1.21.1 + NeoForge 21.1.x**
*最后更新: 2026-08-16*
*状态: Alpha 测试阶段 - v8 架构重构完成*

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
    └── LivingEnderPearlFunction → (纯工具类，无 DataComponent)
    ↓
ContainerContext (组合接口) → TickContext (tick 级临时状态 + 脏槽位批量同步)
    ↓
SlotAccessor (模拟优先传输 + FilteredSlotAccessor 过滤)
```

> 📄 基础设施详见 [living-item-infrastructure.md](docs/system-design/living-item-infrastructure.md)
> 📄 数据模型与设计决策详见 [data-model.md](docs/system-design/data-model.md)

---

## 子系统索引

| 子系统 | 概述 | 详细文档 |
|--------|------|----------|
| **活TNT** | 引信倒计时 + 爆炸，威力随数量缩放，双模式（普通/大当量） | [living-tnt-tech.md](docs/tech/living-tnt-tech.md) |
| **活水桶** | 水源注册 + BFS 蔓延 + 水流推动物品 | [living-water-bucket-tech.md](docs/tech/living-water-bucket-tech.md) |
| **活熔炉** | 配方匹配 + 燃料消耗 + 方向槽位配置 | [living-furnace-tech.md](docs/tech/living-furnace-tech.md) |
| **活漏斗** | TransferPipeline 统一传输 + 黑白名单 + 跨容器 + WASD 配置 | [living-hopper-tech.md](docs/tech/living-hopper-tech.md) |
| **活箱子** | 堆叠倍增 + UUID 映射 + LRU 缓存 + 磁盘持久化 | [living-chest-tech.md](docs/tech/living-chest-tech.md) |
| **活末影箱** | 路由模式（共享黑板）+ 直连模式（绑定玩家末影箱） | [living-ender-chest-tech.md](docs/tech/living-ender-chest-tech.md) |
| **活水车** | 力矩计算 + 应力叠加/抵消 + Create 软依赖 | [living-water-wheel-tech.md](docs/tech/living-water-wheel-tech.md) |
| **活地图传送** | 三种场景 + UV 精确传送 + 跨维度 + 载具 + Sable 飞艇 | [living-map-ender-pearl-tech.md](docs/tech/living-map-ender-pearl-tech.md) |
| **活打火石** | 交互触发器，无 tick 逻辑 | [living-flint-and-steel-tech.md](docs/tech/living-flint-and-steel-tech.md) |
| **GUI交互** | 声明式规则 + 统一拦截 + 创造模式兼容 | [gui-interaction-system.md](docs/system-design/gui-interaction-system.md) |
| **图标系统** | 三层架构 + 声明式配置 + 上下文切换 | [icon-system.md](docs/system-design/icon-system.md) |
| **基础设施** | 容器抽象 + 发现缓存 + SlotAccessor + 性能监控 | [living-item-infrastructure.md](docs/system-design/living-item-infrastructure.md) |
| **数据模型** | DataComponent 体系 + 新旧架构对比 + 设计决策 | [data-model.md](docs/system-design/data-model.md) |

---

## 核心文件索引

```
src/main/java/com/qiqi/li/
├── LivingItem.java                          # Mod 主类：tick 入口、网络包注册
├── LivingItemClient.java                    # 客户端入口
├── Config.java                              # NeoForge 配置
│
├── living/
│   ├── api/                                 # 公开接口 + 管理器
│   │   ├── LivingItemFunction.java          # 功能接口（tick + tooltip + canApply + getFunctionId）
│   │   └── LivingItemManager.java           # 核心管理器：DataComponent 注册、数据读写、功能注册
│   │
│   ├── container/                           # 容器抽象层（跨活物品共享基础设施）
│   │   ├── ContainerContext.java            #   组合接口
│   │   ├── TickContext.java                 #   Tick 级临时状态（对象池复用 + 脏槽位集合）
│   │   ├── SimpleContainerContext.java      #   容器上下文实现（脏槽位批量同步）
│   │   ├── ContainerLivingItemHandler.java  #   容器扫描、分组调度（TickContext 生命周期管理）
│   │   ├── ContainerChunkCache.java         #   区块级容器缓存
│   │   ├── ContainerSnapshot.java           #   容器快照（过滤构建委托 HopperFilterBuilder）
│   │   ├── ContainerSync.java               #   容器同步
│   │   ├── SlotInfoProvider.java            #   槽位信息提供
│   │   ├── ContainerIdentity.java           #   容器标识
│   │   └── LivingContainer.java             #   活容器
│   │
│   ├── domain/                              # 领域模块（每个活物品内聚到此）
│   │   ├── hopper/                           #   活漏斗领域
│   │   │   ├── LivingHopperFunction.java     #     活漏斗功能入口
│   │   │   ├── TransferPipeline.java         #     统一传输入口（v8 新增）
│   │   │   ├── CrossContainerTransfer.java   #     跨容器传输（从 container/ 迁入）
│   │   │   ├── HopperFilterBuilder.java      #     过滤链构建（v8 新增）
│   │   │   ├── TransferStrategy.java         #     传输策略（从 transfer/ 迁入）
│   │   │   ├── LivingHopperData.java         #     活漏斗数据（从 data/ 迁入）
│   │   │   ├── DirectionTransferData.java    #     方向传输数据（从 data/ 迁入）
│   │   │   └── TransferData.java             #     传输数据（从 data/ 迁入）
│   │   │
│   │   ├── chest/                            #   活箱子领域
│   │   │   ├── LivingChestFunction.java      #     活箱子功能入口（从 function/ 迁入）
│   │   │   ├── LivingChestAccessor.java      #     活箱子 SlotAccessor（从 domain/ender/ 迁入）
│   │   │   ├── LivingChestItemHandler.java   #     活箱子 ItemHandler（从 domain/ender/ 迁入）
│   │   │   └── LivingChestTooltipComponent.java  # Tooltip 组件
│   │   │
│   │   ├── ender/                            #   活末影箱领域
│   │   │   ├── LivingEnderChestFunction.java #     活末影箱功能入口（从 function/ 迁入）
│   │   │   ├── LivingEnderChestAccessor.java #     活末影箱 SlotAccessor（精简，路由委托 EnderRouteManager）
│   │   │   ├── LivingEnderChestItemHandler.java  # ItemHandler
│   │   │   ├── EnderRouteManager.java        #     路由逻辑集中管理（v8 新增）
│   │   │   ├── EnderChannelRegistry.java     #     全局路由表
│   │   │   ├── EnderChannelEntry.java        #     路由条目
│   │   │   ├── EnderChannelClientCache.java  #     客户端缓存
│   │   │   ├── EnderChannelData.java         #     频道数据（从 data/ 迁入）
│   │   │   └── LivingEnderChestData.java     #     活末影箱数据（从 data/ 迁入）
│   │   │
│   │   ├── furnace/                          #   活熔炉领域
│   │   │   ├── LivingFurnaceFunction.java    #     活熔炉功能入口（从 function/ 迁入，改用 SlotAccessor）
│   │   │   ├── LivingFurnaceData.java        #     活熔炉数据（从 data/ 迁入）
│   │   │   ├── ProgressData.java             #     进度数据（从 data/ 迁入）
│   │   │   ├── FuelData.java                 #     燃料数据（从 data/ 迁入）
│   │   │   ├── TransformData.java            #     转换数据（从 data/water/ 迁入）
│   │   │   └── DirectionSlotsData.java       #     方向槽位数据（从 data/ 迁入）
│   │   │
│   │   ├── water/                            #   活水领域
│   │   │   ├── LivingWaterBucketFunction.java #    活水桶功能入口（从 function/ 迁入）
│   │   │   ├── LivingWaterWheelFunction.java #     活水车功能入口（从 function/ 迁入）
│   │   │   ├── ContainerFluidData.java       #     容器级流体数据
│   │   │   ├── ContainerStressData.java      #     容器级应力累加器
│   │   │   ├── LivingWaterBucketData.java    #     活水桶数据（从 data/ 迁入）
│   │   │   ├── WaterData.java                #     水源数据（从 data/ 迁入）
│   │   │   ├── LivingWaterWheelData.java     #     活水车数据（从 data/ 迁入）
│   │   │   └── WaterWheelData.java           #     水车应力数据（从 data/ 迁入）
│   │   │
│   │   ├── tnt/                              #   活TNT领域
│   │   │   ├── LivingTntFunction.java        #     活TNT功能入口（从 function/ 迁入）
│   │   │   ├── LivingTntData.java            #     活TNT数据（从 data/ 迁入）
│   │   │   └── ExplosionData.java            #     爆炸数据（从 data/ 迁入）
│   │   │
│   │   └── map/                              #   活地图传送领域
│   │       ├── LivingEnderPearlFunction.java #     活末影珍珠（纯工具类）
│   │       ├── MapTeleportExecutor.java      #     传送执行器
│   │       ├── TeleportHelper.java           #     传送工具类
│   │       ├── MapCoordHelper.java           #     坐标转换工具类
│   │       ├── LivingMapEventHandler.java    #     手持传送 + 元数据同步
│   │       ├── ItemFrameMapTeleportHandler.java #  展示框传送
│   │       ├── LivingMapClientCache.java     #     客户端地图元数据缓存
│   │       └── StructureMapDecorator.java    #     结构地图装饰器
│   │
│   ├── function/                             # 简单活物品功能（无需领域模块）
│   │   └── LivingFlintAndSteelFunction.java  #   活打火石（交互触发器）
│   │
│   ├── compat/                              # 第三方模组兼容层
│   │   ├── create/                          #   Create 兼容（软依赖）
│   │   └── sable/                           #   Sable 飞艇兼容（软依赖）
│   │
│   ├── components/                          # 无状态工具组件
│   │   ├── ExplosionComponent.java          #   爆炸工具类
│   │   └── ItemFilterComponent.java         #   黑白名单过滤
│   │
│   ├── transfer/                            # 传输基础设施
│   │   ├── SlotAccessor.java                #   接口：模拟优先传输
│   │   ├── PlainSlotAccessor.java           #   普通槽位
│   │   ├── FilteredSlotAccessor.java        #   过滤装饰器
│   │   ├── NeighborSlotAccessor.java        #   邻居容器
│   │   ├── SlotAccessorFactory.java         #   注册式工厂
│   │   ├── SlotResolver.java                #   槽位解析
│   │   ├── ContainerCompatibilityConfig.java #  容器兼容性配置
│   │   └── FilterData.java                  #   过滤数据（从 data/ 迁入，跨领域共享）
│   │
│   ├── interaction/                         # GUI交互
│   │   ├── InteractionEntry.java            #   交互规则 record
│   │   ├── InteractionRegistry.java         #   交互注册表
│   │   └── InteractionHandler.java          #   处理器接口
│   │
│   ├── model/                               # 配置/方向模型
│   │   ├── Pos2D.java                       #   不可变 2D 坐标
│   │   └── SlotMapping.java                 #   不可变槽位映射
│   │
│   ├── mixin/                               # 服务端 Mixin
│   │   ├── AbstractContainerScreenMixin.java #  容器界面（活按钮+交互+活地图渲染）
│   │   ├── ItemStackMixin.java              #   活箱子堆叠操作拦截
│   │   ├── MapItemMixin.java                #   活空地图扩展
│   │   └── create/                          #   Create Mixin（条件加载）
│   │
│   └── perf/                                # 性能监控
│       └── PerfMetrics.java                 #   Tick 耗时/活物品数量/对象池命中率
│
├── client/
│   ├── gui/LivingButton.java                # 活按钮
│   ├── icon/                                # 图标系统（声明式配置）
│   │   ├── LivingIconSpec.java              #   图标声明式配置
│   │   ├── LivingIconRegistry.java          #   图标注册中心
│   │   ├── GenericLivingModelWrapper.java   #   通用模型包装器
│   │   ├── GenericContextAwareModel.java    #   上下文切换模型
│   │   └── GenericLivingItemOverrides.java  #   覆盖解析器
│   ├── input/                               # 输入处理
│   │   ├── GuiInteractionHelper.java        #   GUI交互统一工具
│   │   └── LivingItemInputHandler.java      #   WASD 方向配置
│   ├── render/                              # 渲染
│   │   ├── LivingMapLayout.java             #   地图布局扫描
│   │   ├── LivingMapTargetRenderer.java     #   十字光标渲染器
│   │   ├── LivingMapIconDecorator.java      #   活地图 IItemDecorator
│   │   ├── ExpandedMapTexture.java          #   扩展地图动态纹理
│   │   ├── LivingHopperDecorator.java       #   活漏斗箭头叠加层
│   │   └── LivingItemTooltip.java           #   Tooltip 渲染
│   └── mixin/                               # 客户端 Mixin
│       ├── AbstractContainerScreenMixin.java #  容器界面
│       ├── InventoryScreenMixin.java         #  生存模式背包
│       ├── CreativeModeInventoryScreenMixin.java # 创造模式背包
│       └── MapRendererMixin.java            #  展示框十字光标
│
└── network/                                 # 网络包
    ├── GuiInteractionPacket.java            #   GUI交互包
    ├── CarriedUpdatePacket.java             #   光标更新包
    ├── LivingTagPacket.java                 #   活物品标签切换包
    ├── HopperDirectionPacket.java           #   漏斗方向配置包
    ├── EnderChannelSyncPacket.java          #   末影箱频道同步包
    ├── LivingMapGuiTeleportPacket.java      #   活地图 GUI 传送包
    ├── LivingMapMetadataPacket.java         #   活地图元数据包
    └── ServerPacketHandler.java             #   服务端包处理
```

---

## 已完成功能

### 基础设施
- [x] 活按钮 UI 与物品活化机制
- [x] DataComponent 数据持久化系统
- [x] 容器自动扫描与 tick 分发（拉取模型 + 自清洁缓存）
- [x] 多活物品并行处理（按功能分组）
- [x] 不可变数据模型（Java Record + `withXxx()`）
- [x] 活物品隔离（不传输/不熔炼/不作为燃料）
- [x] TickContext 对象池（ThreadLocal，命中率 ~87%）
- [x] 脏槽位批量同步机制（`TickContext.dirtySlots`）
- [x] 性能监控指标系统（`PerfMetrics`）
- [x] 包结构领域内聚（`domain/` 替代 `data/` + `function/`）
- [x] `TransferPipeline` 统一传输入口
- [x] `EnderRouteManager` 路由逻辑集中
- [x] `SlotAccessor` 架构统一所有传输路径

### GUI交互系统
- [x] 声明式交互规则（`InteractionEntry` + `InteractionRegistry`）
- [x] 客户端统一拦截（`GuiInteractionHelper.tryInteract()`）
- [x] 创造模式光标物品同步（`CarriedUpdatePacket`）
- [x] 创造模式 SlotWrapper 兼容

### 活熔炉 / 活漏斗 / 活TNT / 活水桶 / 活打火石
- [x] 各功能完整实现（详见对应 tech 文档）

### 活箱子
- [x] 堆叠倍增模型 + UUID 映射 + LRU 缓存 + 磁盘持久化
- [x] 漏斗自动传输 + 跨容器传输
- [x] GUI 拆分/合并 UUID 自动分配
- [x] 被动孤儿文件清理
- [x] 方块放置自动填充


### 活末影箱
- [x] 路由模式（共享黑板）+ 直连模式（绑定玩家末影箱）
- [x] 频道隔离 + 轮询公平调度
- [x] 反向索引路由清理 + 统一路由验证
- [x] 黑白名单统一过滤（`FilteredSlotAccessor`）
- [x] 跨容器传输架构统一

### 活水车
- [x] 力矩计算 + 应力叠加/抵消 + Create 软依赖集成
- [x] 白名单过滤 + 方向兼容性检查 + 自过期机制
- [x] 物品栏 3D 旋转渲染 + 漫反射光照修正

### 活地图传送
- [x] 三种传送场景（手持/展示框/GUI）
- [x] UV 精确传送 + 传送优先级（旗帜>宝藏>坐标）
- [x] 未探索区域传送（消耗 16 颗珍珠）
- [x] 跨维度传送 + 载具传送 + Sable 飞艇兼容
- [x] GUI 扩展地图渲染 + 十字光标（四色标记）
- [x] 展示框十字光标 + 活地图图标
- [x] 元数据同步 + 客户端缓存

### 容器兼容性
- [x] IItemHandler 统一容器抽象（原版 + 模组容器）
- [x] 自动布局推断（`ContainerCompatibilityConfig.findOrGenerateRule`）
- [x] 大箱子双重去重

---

## 开发进展

### 当前版本: v0.9-alpha

**最近更新** (2026-08-16):
- ✅ 重构：包结构重组 — `data/` 删除，`function/` 精简，所有活物品内聚到 `domain/`
- ✅ 重构：传输管道统一 — `TransferPipeline` 统一传输入口，解耦 `CrossContainerTransfer` 与 `LivingHopperFunction`
- ✅ 重构：活末影箱路由解耦 — `EnderRouteManager` 集中管理路由，`LivingEnderChestAccessor` 精简
- ✅ 重构：活熔炉同步生命周期统一 — 改用 `SlotAccessor`，支持活箱子作为输入/输出
- ✅ 新增：`HopperFilterBuilder` 过滤链构建（从 `ContainerSnapshot` 提取）
- ✅ 新增：脏槽位批量同步机制（`TickContext.dirtySlots` + `SimpleContainerContext.flushDirtySlots()`）
- ✅ 更新：全部技术文档同步至 v8 架构

**最近更新** (2026-08-09):
- ✅ 新增：活地图传送系统（三种场景 + UV 精确传送 + 跨维度 + 载具 + Sable 飞艇兼容）
- ✅ 新增：GUI 扩展地图渲染 + 十字光标 + 展示框十字光标 + 活地图图标
- ✅ 新增：元数据同步包 + 客户端缓存
- ✅ 修复：跨维度提示重复（`MapTeleportExecutor.execute()` 统一发送）
- ✅ 修复：副手活末影珍珠未统计/消耗
- ✅ 优化：渲染性能（`hoveredGroup` 缓存，O(N²)→O(N)）

**最近更新** (2026-07-30):
- ✅ 重构：包结构按领域聚合（`domain/` + `compat/` + `transfer/`）
- ✅ 新增：活水车系统 + Create 软依赖集成
- ✅ 优化：活水桶和活水车代码审查

> 📄 历史更新记录详见 [changelog.md](docs/archive/changelog.md)

---

## 文档导航

```
docs/
├── system-design/                    # 系统设计文档
│   ├── living-item-infrastructure.md #   活物品基础设施（容器抽象+发现缓存+SlotAccessor+性能监控）
│   ├── data-model.md                 #   数据模型与设计决策（DataComponent体系+新旧对比+关键决策）
│   ├── gui-interaction-system.md     #   GUI交互系统（声明式规则+创造模式兼容）
│   └── icon-system.md                #   图标系统（三层架构+声明式配置）
├── tech/                             # 各活物品技术文档
│   ├── living-tnt-tech.md
│   ├── living-water-bucket-tech.md
│   ├── living-furnace-tech.md
│   ├── living-hopper-tech.md
│   ├── living-chest-tech.md
│   ├── living-ender-chest-tech.md
│   ├── living-water-wheel-tech.md
│   ├── living-map-ender-pearl-tech.md
│   └── living-flint-and-steel-tech.md
├── guides/                           # 设计指南
│   ├── container-compatibility.md
│   ├── wasd-direction-input.md
│   ├── custom-slot-design.md
│   ├── recipe-book-style.md
│   └── gui-click-interception.md
├── reference/                        # 参考资料
│   ├── block-facing.md
│   ├── sable-sublevel-projection.md
│   └── pinyin-search.md
└── archive/                          # 存档
    ├── changelog.md                  #   历史更新记录
    ├── refactoring-report.md         #   v8 重构完成报告
    ├── middleware-design.md
    ├── gui-refactoring.md
    ├── creative-mode-sync-issue.md
    ├── search-refresh-fix-v3.1.md
    └── dev-issues.md
```