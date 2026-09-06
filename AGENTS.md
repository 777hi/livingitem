# Living Item (活物品)

**Minecraft 1.21.1 + NeoForge 21.1.x**
*最后更新: 2026-09-05*
*状态: Alpha 测试阶段 - v8.1 接口化重构完成 + 红电相位解读三元件（v19.1）*

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
> 📄 框架重构总结详见 [framework-refactoring.md](docs/framework-refactoring.md)

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
| **活红石** | 红石信号传播 + BFS 算法 + 反相器 + 堆叠数影响 | [living-redstone-tech.md](docs/tech/living-redstone-tech.md) |
| **活涂蜡铜块（红电发电）** | 双因子感应发电 + 事件驱动记账 + RE/FE 单位制 | [living-power-tech.md](docs/tech/living-power-tech.md) |
| **活打火石** | 交互触发器，无 tick 逻辑 | [living-flint-and-steel-tech.md](docs/tech/living-flint-and-steel-tech.md) |
| **GUI交互** | 声明式规则 + 统一拦截 + 创造模式兼容 | [gui-interaction-system.md](docs/system-design/gui-interaction-system.md) |
| **图标系统** | 三层架构 + 声明式配置 + 上下文切换 | [icon-system.md](docs/system-design/icon-system.md) |
| **基础设施** | 容器抽象 + 发现缓存 + SlotAccessor + 性能监控 | [living-item-infrastructure.md](docs/system-design/living-item-infrastructure.md) |
| **数据模型** | DataComponent 体系 + 新旧架构对比 + 设计决策 | [data-model.md](docs/system-design/data-model.md) |
| **单元测试** | FML 测试环境配置 + 测试替身 + 可测性边界 | [unit-testing.md](docs/guides/unit-testing.md) |
| **框架重构** | HasDirection + HasContainerData 接口化设计 | [framework-refactoring.md](docs/framework-refactoring.md) |

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
│   │   ├── HasDirection.java                # WASD 朝向配置接口
│   │   ├── HasContainerData.java            # 容器级数据计算接口
│   │   └── LivingItemManager.java           # 核心管理器：DataComponent 注册、数据读写、功能注册
│   │
│   ├── container/                           # 容器抽象层（跨活物品共享基础设施）
│   │   ├── ContainerContext.java            #   组合接口
│   │   ├── TickContext.java                 #   Tick 级临时状态（每 tick 新建 + 脏槽位集合）
│   │   ├── SimpleContainerContext.java      #   容器上下文实现（脏槽位批量同步）
│   │   ├── ContainerLivingItemHandler.java  #   容器扫描、分组调度、容器级数据缓存（含位置反向索引）
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
│   │       ├── LivingMapFunction.java        #     活空地图
│   │       ├── MapTeleportExecutor.java      #     传送执行器
│   │       ├── TeleportHelper.java           #     传送工具类
│   │       ├── MapCoordHelper.java           #     坐标转换工具类
│   │       ├── LivingMapEventHandler.java    #     手持传送 + 元数据同步
│   │       ├── ItemFrameMapTeleportHandler.java #  展示框传送
│   │       ├── LivingMapClientCache.java     #     客户端地图元数据缓存
│   │       └── StructureMapDecorator.java    #     结构地图装饰器
│   │
│   ├── domain/redstone/                     #   活红石领域
│   │   ├── LivingRedstoneFunction.java       #     活红石粉功能入口
│   │   ├── LivingRedstoneTorchFunction.java  #     活红石火把功能入口
│   │   ├── LivingButtonFunction.java         #     活按钮
│   │   ├── LivingLeverFunction.java          #     活拉杆
│   │   ├── LivingRedstoneLampFunction.java   #     活红石灯
│   │   ├── LivingRepeaterFunction.java       #     活中继器
│   │   ├── LivingComparatorFunction.java     #     活比较器
│   │   ├── LivingRedstoneBlockFunction.java  #     活红石块
│   │   ├── LivingCopperFunction.java         #     活铜块（锈蚀等级即导通性）
│   │   ├── RedstonePropagation.java          #     传播算法核心（BFS，逐 tick 全量重算）
│   │   ├── RedstoneSnapshotProvider.java     #     红石槽位快照提供器
│   │   ├── LivingRedstoneData.java           #     活红石粉数据
│   │   ├── LivingRedstoneTorchData.java      #     活红石火把数据
│   │   ├── LivingButtonData.java             #     活按钮数据
│   │   ├── LivingLeverData.java              #     活拉杆数据
│   │   ├── LivingRedstoneLampData.java       #     活红石灯数据
│   │   ├── LivingRepeaterData.java           #     活中继器数据
│   │   ├── LivingComparatorData.java         #     活比较器数据
│   │   ├── LivingCopperBulbData.java         #     活铜灯数据
│   │   ├── LivingCopperSignalData.java       #     活铜块信号数据
│   │   ├── LivingCutCopperData.java          #     活切制铜块数据
│   │   ├── LivingGrateData.java              #     活铜格栅数据
│   │   └── ContainerRedstoneData.java        #     容器级红石信号数据（EdgeGrid + 边界信号）
│   │
│   ├── domain/power/                        #   红电发电领域（活涂蜡铜块）
│   │   ├── LivingWaxedCopperFunction.java    #     涂蜡发电机功能入口（priority=3；BFS 采样 + 相位解读 pass + 跳变门控记账）
│   │   ├── PowerMath.java                   #     发电数学（合因子/调谐效率/存活窗口/RE→FE）
│   │   ├── ChannelState.java                #     相位域分组计 n + 跳变门控（bestActiveDomain）
│   │   ├── GeneratorState.java              #     单台发电机状态（单通道 + per-generator EMA）
│   │   ├── DerivedPhase.java                #     派生相位 record（v19.1 相位解读驻波：周期/偏移/幅度/形态）
│   │   ├── LivingWaxedCutData.java          #     涂蜡切制组件（遗留兼容字段，逻辑不读取）
│   │   ├── LivingWaxedChiseledData.java     #     涂蜡雕文组件（inputDir=移相读取方向；outputDir 遗留兼容）
│   │   ├── LivingWaxedBulbData.java         #     涂蜡铜灯组件（按盏电量，1/1000 FE 定点）
│   │   ├── LivingWaxedGeneratorData.java    #     发电机仪表盘组件（检测值快照，纯展示）
│   │   ├── LivingWaxedCopperTooltipComponent.java # Tooltip 组件（仪表盘渲染）
│   │   ├── PhaseEvent.java                  #     相位事件 record（周期 + 偏移量）
│   │   ├── BulbItemEnergyStorage.java       #     铜灯物品能量（通用电池：双向，每盏等量充放）
│   │   ├── ContainerEnergyStorage.java      #     对外 IEnergyStorage（原版容器 BE 显式注册+让位；取电逐堆扣灯/充电比例分配）
│   │   └── ContainerPowerData.java          #     容器级红电账本（RE 事件 + EMA 功率，无池）
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
│   │   ├── ItemStackMixin.java              #   活箱子堆叠操作拦截
│   │   ├── MapItemMixin.java                #   活空地图扩展
│   │   ├── ServerPlaceRecipeMixin.java      #   配方放置拦截
│   │   ├── BlockStateBaseMixin.java         #   容器边界红石信号输出
│   │   ├── RedStoneWireBlockMixin.java      #   红石线连接到活容器
│   │   └── create/                          #   Create Mixin（条件加载）
│   │
│   ├── debug/                               # 调试工具（默认关闭，命令启用）
│   │   ├── ContainerMonitor.java            #   容器物品复制/丢失检测
│   │   └── ContainerMonitorCommand.java     #   /living_monitor 命令
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
│   │   ├── LivingItemTooltip.java           #   Tooltip 渲染
│   │   ├── LivingDefaultDecorator.java      #   默认图标叠加层
│   │   ├── LivingHopperDecorator.java       #   活漏斗箭头叠加层
│   │   ├── LivingChiseledCopperDecorator.java # 活雕文铜块箭头叠加层
│   │   ├── LivingRedstoneDecorator.java     #   活红石粉连线叠加层
│   │   ├── LivingChestTooltipRenderer.java  #   活箱子 Tooltip 渲染
│   │   └── LivingWaxedCopperTooltipRenderer.java # 红电仪表盘 Tooltip 渲染
│   ├── util/                                # 客户端工具
│   │   ├── PinyinHelper.java                #   中文拼音检索（搜索框用）
│   │   └── LivingChestTabState.java         #   活箱子页签状态
│   └── mixin/                               # 客户端 Mixin
│       ├── AbstractContainerScreenMixin.java #  容器界面
│       ├── InventoryScreenMixin.java         #  生存模式背包
│       ├── CreativeModeInventoryScreenMixin.java # 创造模式背包
│       ├── RecipeBookComponentMixin.java     #  配方书自定义布局（全项目最大文件）
│       └── MapRendererMixin.java            #  展示框十字光标
│
└── network/                                 # 网络包
    ├── GuiInteractionPacket.java            #   GUI交互包
    ├── CarriedUpdatePacket.java             #   光标更新包
    ├── LivingTagPacket.java                 #   活物品标签切换包
    ├── HopperDirectionPacket.java           #   漏斗方向配置包
    ├── SlotDirectionPacket.java             #   通用槽位方向配置包
    ├── EnderChannelSyncPacket.java          #   末影箱频道同步包
    ├── LivingChestAccessPacket.java         #   活箱子访问包
    ├── LivingMapGuiTeleportPacket.java      #   活地图 GUI 传送包
    ├── LivingMapMetadataPacket.java         #   活地图元数据包
    └── ServerPacketHandler.java             #   服务端包处理
```

```
src/test/java/com/qiqi/li/
├── testutil/
│   └── FakeContainerContext.java              # ContainerContext 测试替身（内存数组实现）
├── living/container/
│   └── SimpleContainerContextTest.java        # 容器上下文脏槽同步（25 项）
├── living/domain/redstone/
│   └── ContainerRedstoneDataTest.java         # 红石信号传播（24 项）
├── living/domain/power/
│   ├── PowerMathTest.java                     # 发电数学（5 项）
│   ├── ContainerPowerDataTest.java            # 相位质量状态机（6 项）
│   ├── AccountingGateTest.java                # 跳变门控记账（4 项：零产出/冻结/去重/中性化）
│   ├── PhaseInterpretationTest.java           # 相位解读三元件（8 项：移相/链/环自熄/死源/裂相/加法）
│   ├── ChannelStateTest.java                  # 相位域偏移活性（5 项）
│   ├── CoilGroupingTest.java                  # 形态识别 + 单通道（5 项）
│   ├── NetworkResonanceTest.java              # 铜块网络共振（18 项）
│   ├── NetworkTraversalTest.java              # 铜块网络遍历（6 项，含 E2E 真实传播）
│   ├── WaxedCopperStorageTest.java            # 储能（16 项，含 telemetry 2 项）
│   ├── BulbItemEnergyStorageTest.java         # 铜灯通用电池（6 项）
│   ├── WaxedCopperOscillatorIT.java           # 振荡器→发电全链路集成（5 项）
│   └── WaxedCopperCouplingIT.java             # 耦合链集成：多跳中继+防回环（3 项）
├── living/domain/map/
│   └── MapCoordHelperTest.java                # 地图坐标换算（16 项）
└── living/transfer/
    └── ContainerCompatibilityConfigTest.java  # 容器布局推断（9 项）
```

**合计测试用例 183 个**（含参数化展开与 `SimpleContainerContextTest` 的 `@Nested` 内部类）。
全绿基线：`183 passed / 0 failed / 0 skipped`（2026-09-05 验证）。

> 📄 测试环境配置与编写约定详见 [unit-testing.md](docs/guides/unit-testing.md)

---

## 已完成功能

### 基础设施
- [x] 活按钮 UI 与物品活化机制
- [x] DataComponent 数据持久化系统
- [x] 容器自动扫描与 tick 分发（拉取模型 + 自清洁缓存）
- [x] 多活物品并行处理（按功能分组）
- [x] 不可变数据模型（Java Record + `withXxx()`）
- [x] 活物品隔离（不传输/不熔炼/不作为燃料）
- [x] 脏槽位批量同步机制（`TickContext.dirtySlots`）
- [x] 性能监控指标系统（`PerfMetrics`，纳秒精度）
- [x] 容器级数据位置反向索引（`POS_TO_CACHE_KEY`，mixin 热路径 O(1) 查询）
- [x] 容器数据缓存键含维度（跨维度同坐标容器隔离）
- [x] 服务端关闭统一清理静态缓存（跨存档隔离）
- [x] 单元测试基建（MDG unitTest + FML 环境，146 项测试）
- [x] 包结构领域内聚（`domain/` 替代 `data/` + `function/`）
- [x] `TransferPipeline` 统一传输入口
- [x] `EnderRouteManager` 路由逻辑集中
- [x] `SlotAccessor` 架构统一所有传输路径
- [x] 接口化重构：`HasDirection`（WASD 朝向）+ `HasContainerData`（容器级数据计算）

### GUI交互系统
- [x] 声明式交互规则（`InteractionEntry` + `InteractionRegistry`）
- [x] 客户端统一拦截（`GuiInteractionHelper.tryInteract()`）
- [x] 创造模式光标物品同步（`CarriedUpdatePacket`）
- [x] 创造模式 SlotWrapper 兼容

### 活熔炉 / 活漏斗 / 活TNT / 活水桶 / 活打火石
- [x] 各功能完整实现（详见对应 tech 文档）

### 活红石
- [x] 活红石粉：信号传播（BFS，每 game tick）+ 堆叠数影响信号上限
- [x] 活红石火把：反相器 + 四方向朝向配置（WASD）
- [x] 活按钮：触发型信号源
- [x] 活拉杆：持续型信号源
- [x] 活红石灯：信号可视化输出
- [x] 活中继器：延迟 + 单向导通
- [x] 活比较器：读取活物品状态（`LivingItemFunction.getComparatorOutput`）
- [x] 活红石块：恒定信号源
- [x] 容器边界信号双向互通（`BlockStateBaseMixin` + `RedStoneWireBlockMixin`）

### 红电发电（活涂蜡铜块 · 阶段一~四）
- [x] 双因子模型落地：合因子 = n^(1+解锁度)，解锁度 = 调谐效率 × 规律度
- [x] RE 自然单位记账（跳变即能量事件 + EMA 功率），K=1/16 边界换算
- [x] 事件驱动采样：`priority=3` 晚于红石，edgeGrid 逐方向喂值
- [x] 相位域分组计 n（同周期+同偏移合并，杂讯路不进域）
- [x] 规律度：上升沿间隔 best-shift 一致度（事件驱动，O(1) 增量）
- [x] 相位解读三元件（v19.1）：雕文移相器（派生 φ+1，可链式 = 任意偏移延迟线）/
  切制裂相器（下降沿反相解读，一个方波 2 个反相相位）/
  格栅相位加法器（同周期多路 Σφᵢ mod P 求和）+ 派生相位注册表（两阶段提交 + 活性修剪 + 环自熄）
- [x] 记账跳变门控（v19.1）：入账只看「本 tick 有跳变」的最佳域，能量 = 合因子 × P × 跳变路数——
  修复频率中性化反转（慢时钟按 P 线性碾压）与停机虚能量
- [x] 感应耦合：相邻发电机管径加权分配 + 不回传防环 + 多跳中继（分层重算）
- [x] 绝缘修复：涂蜡铜块排除出红石「充能导体」（杜绝信号泄漏绕过绝缘）
- [x] 储能：铜灯 = 唯一储存（发电直存、无容器池），容量 = count×C 线性涌现
- [x] 对外能量接口：**显式注册**（10 种原版容器 BE）+ **让位**（直接实现者/已有主人 → 退位，重入保护查询）
- [x] 铜灯物品 = 通用电池（双向：电池槽放电 + 充能槽充电，无出身论）
- [x] 集成测试：拉杆振荡器（4t）+ 耦合链（A 直连 → B 一跳 → C 两跳）
- [x] Tooltip 仪表盘：检测值写回组件 + 槽位同步 + 客户端渲染（双语）

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

**最近更新** (2026-09-05):
- ✅ 新增：**记账跳变门控**——入账只看「本 tick 有跳变」的最佳域，能量 = 合因子 × P × 跳变路数
  （按 offset 去重）。修复旧「每 tick 无条件入账 ×P」的频率中性化反转
  （慢时钟按 P 线性碾压快时钟，整数倍谐波可无限放大）与停机虚能量（域存活窗口内白拿）
- ✅ 新增：**相位解读三元件**（形态从「听什么」到「造什么」，普通铜块退役为纯基准）——
  雕文 = 移相器（派生 φ+1，可链式 = 任意偏移延迟线，解锁奇数偏移制造）/
  切制 = 裂相器（下降沿反相解读，一个方波 2 个反相相位，P=2 满相可达）/
  格栅 = 相位加法器（同周期多路 Σφᵢ mod P 求和，加法子群涌现）
- ✅ 新增：派生相位注册表（`DerivedPhase` + 两阶段提交 + 存活窗口修剪 +
  移相环无种子自熄——结构性防环，无检测代码）
- ✅ 重构：拓扑统一至锈级单维度（TopoKey 的 axis/inEdge/outEdge 退役，
  切制 H/V 双通道拆除，`channelSecondary` 删除）
- ✅ 新增：AccountingGateTest（4 项）+ PhaseInterpretationTest（8 项），
  全量 183 用例全绿
- ✅ 移除：红石稳态跳过优化（v19.1）——三闸门模型吞掉火把振荡器的「翻转后果 tick」
  后靠 rev/sig/timer 不变自维持死锁，游戏内振荡线路集体静止（SteadyState 删除，
  calculate 恢复逐 tick 全量重算；回归守卫 torchNotRing_oscillates）
- 📄 技术文档：living-power-tech.md §3.8 / 红电系统.md v19.1 修订

**最近更新** (2026-08-30):
- ✅ 新增：红电发电阶段一~三 —— `domain/power` 包
  （`PowerMath` / `PathState` / `ChannelState` / `GeneratorState` / `ContainerPowerData`），
  双因子模型落地：合因子 = n^(1+解锁度)，解锁度 = 调谐效率 × 规律度
- ✅ 新增：`LivingWaxedCopperFunction`（priority=3，晚于红石）——涂蜡全家族 20 件活化，
  逐方向采样 edgeGrid 事件，跳变即能量事件入账（RE 自然单位，K=1/16 边界换算）
- ✅ 新增：阶段三+四 —— 感应拓扑（线圈分组：铜块全向/雕文 V+H/切制单方向）
  + 感应耦合（管径加权守恒、不回传防环、多跳中继）
  + 绝缘修复（涂蜡排除出充能导体，杜绝信号泄漏）
  + 储能：铜灯 = 唯一储存（发电直存、无容器池），容量 = count×C 线性涌现
  + 对外能量：**显式注册+让位**（10 种原版容器 BE，三层判定不劫持已有能源；方块级双向 canReceive=true）
  + 铜灯物品 = 通用电池（双向：放电 + 外部充电，跨系统能量等量转换）
- ✅ 新增：表现层 —— Tooltip 仪表盘（检测值写回组件 → 槽位同步 → 客户端渲染，双语 key）
- ✅ 新增：39 项电力层测试（数学 5 + 状态机 6 + 线圈分组 4 + 储能 16（含仪表 2）+ 电池 6 + 集成 2），
  全量 146 项测试通过
- 📄 技术文档：[living-power-tech.md](docs/tech/living-power-tech.md)

**最近更新** (2026-08-25):
- ✅ 提升：红石传播时间分辨率从 2 tick 改为 **1 game tick**，取消跳帧。
  容器内最快振荡周期从 4 tick 降至 2 tick，为红电系统的高频档位提供基础。
  中继器/按钮延迟统一以 game tick 计数（中继器档位 N = 2N tick，保持原版红石刻语义）
- ✅ 优化：传播热路径改用槽位类型位图（`slotMask`），替换 18 处 `Set<Integer>.contains`，
  消除装箱与每 tick 的临时 `HashSet`。实测单次传播开销降 40~76%
  （满载 54 格 9.4μs → 4.9μs），1 tick 传播总成本与原 2 tick 持平

**此前更新** (2026-08-22):
- ✅ 修复：红石传播节拍改为对齐全局游戏时钟，
  消除因容器加载时机不同导致的跨容器信号错位半拍问题
- ✅ 新增：单元测试基建 — MDG `unitTest` 配置，测试可在 FML 环境引用 Minecraft 类
- ✅ 新增：64 项单元测试（红石传播 21 + 容器兼容性 14 + 地图坐标 29）
- ✅ 修复：客户端 Mixin 从双端 `mixins` 移至 `client` 数组（专用服务器启动崩溃）
- ✅ 修复：`EdgeGrid.get/set` 缺少边界检查，容器尺寸变化时会越界崩溃
- ✅ 修复：容器级数据缓存键补齐维度，消除跨维度同坐标容器串数据
- ✅ 修复：`grouped.isEmpty()` 分支门禁失效（`getSize()` 恒为 0），残留边界红石信号现可正确归零
- ✅ 优化：新增位置→缓存键反向索引，mixin 热路径（`getSignal` / `getConnectingSide`）从正则全表扫描降为 O(1)
- ✅ 修复：`APPLICABLE_CACHE` 改用 `ConcurrentHashMap`，消除单人游戏双线程并发写风险
- ✅ 新增：`ServerStoppedEvent` 统一清理静态缓存，避免跨存档状态残留
- ✅ 优化：`PerfMetrics` 全面改用纳秒累计，修复亚毫秒耗时被整数除法归零的问题
- ✅ 清理：删除 `TickContext` 未使用的泛型扩展点

**最近更新** (2026-08-17):
- ✅ 重构：接口化设计 — `HasDirection` 接口统一 WASD 朝向配置，`HasContainerData` 接口统一容器级数据计算
- ✅ 重构：`TickContext` 每 tick 新建（对象小、生命周期短，JVM 年轻代可高效回收）
- ✅ 优化：新增活物品从修改 6 个文件减少到 2 个文件
- ✅ 更新：全部文档同步至 v8.1 架构

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
├── framework-refactoring.md          # 框架重构总结（HasDirection + HasContainerData 接口化设计）
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
│   ├── unit-testing.md               #   单元测试指南（FML 测试环境 + 测试替身 + 可测性边界）
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