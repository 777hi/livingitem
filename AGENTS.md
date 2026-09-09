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
| **Tooltip 系统** | 双层渲染架构 + 运行时缓存同步 + 显示口径（窗口均值/mFE） | [tooltip-system.md](docs/system-design/tooltip-system.md) |
| **红电架构演进** | SensorPort 感知端口 + 事件流/元件接口化路线（信号层⇄电力层解耦） | [redstone-evolution-roadmap.md](docs/system-design/redstone-evolution-roadmap.md) |
| **红电不变量测试** | 31 条可执行不变量 + 四层测试方案（属性测试/场景生成/蜕变/运行时监控） | [power-invariants.md](docs/system-design/power-invariants.md) |
| **超大堆叠审计** | 模组容器堆叠上限 > 64 场景下全部活物品的表现评级（202 处调用点） | [oversized-stack-audit.md](docs/system-design/oversized-stack-audit.md) |
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
│   │   ├── RedstoneSensor.java               #     感知端口接口（电力层/漏斗/TNT 读信号的唯一入口，v19.1）
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
│   │   ├── LivingWaxedChiseledDecorator.java  #   活涂蜡雕文输入方向箭头（电力层移相器，v19.1）
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
├── living/domain/chest/
│   └── LivingChestFunctionTest.java           # 取消活化堆叠倍数返还（6 项）
├── living/domain/furnace/
│   ├── LivingFurnaceFunctionTest.java         # 燃料消耗合成残留物语义（4 项）
│   └── FurnaceBurningFlagTest.java            # 燃烧标志组件·图标切换回归（5 项）
├── living/domain/map/
│   └── MapCoordHelperTest.java                # 地图坐标换算（16 项）
└── living/transfer/
    └── ContainerCompatibilityConfigTest.java  # 容器布局推断（9 项）
```

**合计测试用例 216 个**（含参数化展开与 `SimpleContainerContextTest` 的 `@Nested` 内部类）。
全绿基线：`216 passed / 0 failed / 0 skipped`（2026-09-09 验证）。

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

**最近更新** (2026-09-09):
- ✅ 修复：**活水车/活水桶在大箱子中动画与 tooltip 停留在开箱快照**——应力/
  水流变化后旋转动画不实时播放、改变水流方向后旋转方向不翻转、tooltip 数字不动，
  必须关闭再重开容器界面才恢复；单箱一切正常。根因双通道失效：主动通道
  `SimpleContainerContext.syncWorldContainer` 的容器归属匹配是纯实例比较，而原版
  大箱菜单槽位容器是 `new CompoundContainer(左半BE, 右半BE)` 包装对象——匹配永远
  不命中，`syncSlotToClients` 的组件同步包从不发给大箱查看者；被动通道原版
  `broadcastChanges` 又因 `ItemStackMixin` 忽略 `LIVING_WATER_WHEEL_DATA`（堆叠
  兼容）对这些组件失明。即 v19.1 修 `ContainerRuntimeCache.isViewingContainer`
  （大箱遥测 tooltip）时漏掉的**平行断点**。修复：归属判断抽成 `slotBelongsTo()`
  照既有修法补 CompoundContainer.contains(be) 分支；影响面覆盖所有走
  syncSlotToClients 的活物品数据在大箱中的实时同步（水车应力/水桶水流/熔炉进度等），
  全量 216 用例全绿；踩坑记录收编 living-water-wheel-tech.md §9.23 +
  living-item-infrastructure.md §8.5（大箱匹配补丁）+ tooltip-system.md §7 坑清单
  第 3 条升级为双先例通用规则 + living-water-bucket-tech.md 验证清单补大箱项
- ✅ 修复：**活熔炉图标不切换 active/idle**——熔炼时图标永远停在 furnace_idle.png。
  根因：图标谓词 `LivingFurnaceFunction.isBurning(stack)` 读物品 DataComponent，而
  b064865（09-03「tooltip优化，nbt数据简化」）把 burnTime 迁到了运行时缓存
  （ContainerRuntimeCache + LivingItemSyncPacket），该链路唯一消费方是 tooltip，
  客户端 ItemStack 上燃料恒为默认值 → isBurning() 恒 false（活TNT 图标正常是
  因其数据未迁缓存，反向印证）。修复（方案2，不动运行时缓存架构）：新增轻量布尔
  组件 `LIVING_FURNACE_BURNING`，tick 在燃烧状态**翻转时**写标志 + syncSlotToClients
  （稳态零写入零同步；跨容器搬运后过期标志下一 tick 自愈）；`isBurning()` 改读标志；
  组件进 `getIgnoredComponentTypes()`（该机制第一个真实使用者）——燃烧中/熄灭
  熔炉仍可堆叠。回归测试 FurnaceBurningFlagTest（5 项：点燃写入/熄灭移除/稳态
  零同步/自愈/堆叠兼容），全量 216 用例全绿；修复记录收编 living-furnace-tech.md
  §8.11（§1.4 存储结构、§2.1 tick 示例同步更新为运行时缓存架构现状）

**最近更新** (2026-09-08):
- ⛔ 暂时关闭：**雕文移相链（读邻居注册表派生相位）**——链式组合允许任意频率信号
  堆出任意偏移，单台雕文 + 足够长的链即可凑满相（n = P），满相增益的成本退化为
  纯材料堆叠，绕过「真多相要靠布局与时序理解」的核心设计，超模。关闭点：
  `interpretShifter` 的「输入二：读输入方向邻居的注册表驻波」整段注释停用
  （代码保留，见方法体内注释）；关闭后雕文与切制/格栅同口径——只读真实边信号，
  单级移相（φ+1）保留，「链而非环」的结构性防环论证仍成立（重启无需重审）。
  测试：`shifterChain_twoLinks_n3` 改造为关闭态回归守卫（断言链断：B 无派生、
  n=2；原断言留注释备重启恢复）。重启前置条件：先设计增益约束（δ 衰减 /
  n 折算上限 / 链长入解锁度等）。全量 211 用例全绿；文档标注收编
  living-power-tech.md §3.8（含跨锈级读取小节）+ 红电系统.md（顶部 v19.1 修订、
  形态对照表 ×2、采样拓扑表、跨锈级小节、§3.12 矩阵）
- 🎚️ 标定：**铜灯每盏容量 C 1,000 → 10,000 FE**（`PowerMath.BULB_UNIT_CAPACITY_FE`，
  与 K 并列的第二个硬数，纯储能改动不动 K/发电口径）。依据：① 生态对标——旧值一堆(64)
  64k FE 低于科技生态最低档单格电池（TE 能量格/IE 电容 LV 约 10 万），新值 640k FE
  对齐基础档；② 充电宝物流（充满铜灯拔下搬运）旧值下搬 1M FE 需 16 堆超背包，
  新值 1.6 堆即可用；③ 满溢反馈环保存——典型功率下一堆 0.5~2 小时充满，「已满」
  信号仍能触发（更激进的 100k 档该信号消失，故不取）。q 按 mFE 绝对值存储，
  扩容对存量灯白赚头寸、无迁移；int FE 收窄阈值 214 万 → 21.4 万盏（仍远超容器
  上限，fail-safe）。耦合面：2 处容量相关断言同步修正
  （BulbItemEnergyStorageTest 满容读数 ×10；WaxedCopperStorageTest 充电比例
  分配的半满基准重设 5_000_000 保持 2:1 本意），全量 211 用例全绿；
  标定记录收编 红电系统.md §3.6 / living-power-tech.md §4 /
  oversized-stack-audit.md §2.8
- 🔍 调查：**Flux Networks 取电方块（Flux Plug）不能从铜灯容器取电——上游固有设计，非我方缺陷**。
  该 mod 存电方块（Flux Point，推送方）充电正常；取电方块 Plug 从不主动拉取邻块电量，
  只暴露「可被充入」的电池面等邻块推电（源码验证：拉取 API
  `receiveFrom`/`canReceiveFrom` 在 1.21 源码全库零调用，纯死代码；1.20.1 原版 jar
  字节码交叉验证同样无拉取点 → 非移植丢功能）。我方 `ContainerEnergyStorage`
  是标准被动电池面（`canExtract=true`），对纯被动等待的 Plug 天然不可见；
  Mekanism 电缆双向皆主动故全通。结论收编至 living-power-tech.md §8 已知限制表 +
  红电系统.md §3.11 生态兼容实证；如需兼容 Plug 需让容器主动推电，
  与「限流职责归用电侧」原则相悖，待需求驱动再议
- ✅ 修复：**堆叠活箱子取消活化只返还一份内容**——组件相同才可堆叠 + 堆叠期间存取
  关闭（count>1 拒绝一切操作），故 count=N 的堆叠活箱子语义上是 N 个各含一份
  相同内容的箱子；而 `dropAllItems` 只掉一份 `CONTAINER` 内容，N−1 份凭空蒸发。
  该场景**原版背包即可触达**（两个内容相同的活箱子自动堆叠），并非超大堆叠容器
  专属（超大堆叠审计 §2.4 的「count>1 = 语义开关」结论在掉落路径上的漏网）。
  修复：返还清单抽成纯函数 `collectDeactivationDrops()`（每槽总量 = count × N，
  超物品堆叠上限拆满堆），`dropAllItems` 复用。回归测试 LivingChestFunctionTest
  （6 项），全量 211 用例全绿

**最近更新** (2026-09-07):
- ✅ 审计：**超大堆叠表现探查**（[oversized-stack-audit.md](docs/system-design/oversized-stack-audit.md)）——
  模组容器（抽屉等）堆叠上限 > 64 场景下，全库 202 处堆叠敏感调用点逐领域源码分析 +
  原版语义对照。结论：传输基础设施层干净（统一 min(slotLimit, maxStackSize)，吞吐按
  模组上限走）；14 项设计内缩放（TNT √count / 熔炉 count 倍速 / 红石 count² 信号上限——
  原版消费端 j>=15 早退钳制天然饱和 / 铜灯 mFE long 定点）；1 个真实缺陷（超堆叠岩浆桶
  燃料整槽替换吞 N−1 个——原版同吞但不可达，模组容器使其显形，修复方向待决策）；
  2 个理论 int 溢出（铜灯对外 int FE 收窄 count>214 万盏 / TNT 累加 >21 亿——均 fail-safe
  不崩不刷）；2 个吞吐观察项（漏斗单次固定 64 不随目标容量自适应）

**最近更新** (2026-09-07):
- ✅ 修复：**活熔炉岩浆桶整桶被吞**——`tickFuel` 消耗燃料只 `shrink(1)`，漏掉 crafting
  remainder；燃料消耗收拢到 `consumeFuel()`，对齐原版熔炉点燃语义（带残留物的燃料
  整槽替换为残留物：岩浆桶→空桶；普通燃料扣 1 个、耗尽清空）。回归测试
  LivingFurnaceFunctionTest（4 项），全量 205 用例全绿（基线 183 + 红电工作区未提交用例）

**最近更新** (2026-09-07):
- ✅ 重组：**活潜影箱从红电系统剥离为独立基础设施**——红电系统.md 第 2 章（Phase 2）
  整体迁出（原章节留编号存根，§3.x 与外部锚点稳定）；总览/里程碑/风险表/复用表同步更新；
  idea.md 的活潜影箱创始定义一并合并。新文档 **活潜影箱实现细节.md**（v2.0）：
  基础设定（兼容**所有活物品**而非当前已实现集合——通用活物品基础设施，随新活物品
  逐个补充嵌套实现细节）+「最伟大的活物品」愿景 + 创始定义 + 边界连续性/芯片封装
  （原红电 §2.2~2.7）+ 局部演化+边界交换模型（旧全局视图方案否定）+ 2D↔3D 桥接
  （世界引脚面）+ 与红电的单向协同说明 + **§8 各活物品嵌套细节台账**（活物品实现
  时的补充主线：漏斗/红石/熔炉/水桶/TNT/箱子/末影箱/水车/铜块/避雷针逐格待补）

**最近更新** (2026-09-07):
- ✅ 新增：**红电不变量清单与四层测试方案**（[power-invariants.md](docs/system-design/power-invariants.md)）——
  把红电系统.md 的每条设计原则翻译成 **31 条可执行不变量**（记账 5 / 相位 6 / 共振 5 /
  储能 5 / 集成 7 / 显示 3），每条标注断言入口（真实类与方法）与断言草图；
  附**历史 bug ↔ 不变量映射表**（16 个已修 bug 逐一对应可拦截的不变量，证明
  「没有一个能靠多测一个场景拦住」）+ 四层测试实施方案：
  L1 jqwik 属性测试（工具定选 jqwik，自带 shrink；先过 FML 冒烟门）/
  L2 种子驱动 ScenarioBuilder 场景生成（processContext 生产路径）/
  L3 蜕变测试（频率×k / 平移 / 复制 / 幅度缩放四关系）/
  L4 运行时监控（PowerInvariantMonitor 每 tick 断言 + 违反即 ERROR 快照，
  玩家 = 模糊测试器；前置 distributeToBulbs 返回 boolean→long 小重构）。
  本轮只交付文档（定稿待实施），代码步骤见文档 §6 路线图

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
- ✅ 修复：**大箱子 tooltip 不显示遥测**——原版大箱菜单容器是 CompoundContainer(be1,be2)
  而非 BE 本体，`ContainerRuntimeCache.isViewingContainer` 按实例匹配永远失败 →
  LivingItemSyncPacket 从不发给打开大箱的玩家 → 客户端遥测缓存为空（发电本身正常，
  铜灯正常充电；改用 CompoundContainer.contains(be) 匹配）
- ✅ 修复：**高频振荡下 tooltip FE/t 高频闪烁**——跳变门控记账是脉冲式的，快 EMA
  纹波超过量化精度；显示读数改用「与偏好周期对齐的窗口均值」（GeneratorState 窗口 =
  ceil(32/pref)×pref，ContainerPowerData 32t 固定窗口；稳态零纹波，记账 EMA 不动）
- ✅ 修复：**F3+H 仪器面板（波形图/锈级柱状图）消失**——面板挂载点仍在读
  DataComponent，而发电遥测已改走运行时缓存+网络同步；改按悬停槽位定位运行时缓存
- ✅ 修复：**小功率发电（<1 FE/t）EMA 不显示**——显示均值链路改毫 FE（mFE）定点：
  窗口均值去 long 整除、遥测字段 emaPowerMilliFe/levelEmaPowerMilliFe/levelPowerMilliFe
  （codec 同步改名），tooltip 功率行两位小数显示（如 0.94 FE/t）
- ✅ 修复：**玩家背包里 tooltip 不显示遥测**——背包容器的遥测包被 isViewingContainer
  的空实例匹配/inventoryMenu 排除双重拦截，从不发送；改为「player_」前缀 key 直发本人 +
  客户端独立 player 缓存（与 BE 容器缓存分离，防串台），InventoryScreen/面板挂载点分流读取
- ✅ 修复：**创造模式背包 tooltip 不显示**——CreativeModeInventoryScreen 的菜单槽
  结构特殊（槽位索引与 Inventory 不对齐），悬停定位失败；补「物品引用匹配玩家背包」
  兜底（wrapper 索引 = Inventory 索引，引用必然相等），覆盖创造模式与特殊 mod GUI
- ✅ 修复：**高频下共振平衡度/增益闪烁**——共振读数口径（平衡度 s、增益 R²、活跃锈级数 N）
  从快记账 EMA 切到显示窗口均值（32t，零纹波）；三条铁律结构不变（窗口只吃基础值、
  单遍前馈、结算即精确归零），首个窗口（32t）为共振建立期
- ✅ 修复：**活漏斗锁定/活 TNT 点燃失效**——v15 出边模型下漏斗/TNT（非红石组件）
  槽位的出边恒 0，`getSignal`/`getSlotSignal` 读自身出边永远拿不到信号；
  改读四方向入边（邻居朝本槽的出边，与电力层采样同语义）
- ✅ 修复：**涂蜡雕文感应方向恒为上方**——`pos2dToEdgeDir` 用引用比较（`== Pos2D.X`），
  而 Pos2D 经组件序列化/反序列化后是值相等的新实例 → 所有配置过的方向全部落入
  fallback 恒 UP（只有上方信号被感应）；改值比较（x/y 判断）根治
- ✅ 重构：**RedstoneSensor 感知端口**——电力层采样/漏斗锁定/TNT 点燃的信号读取
  收口到统一接口（依赖收窄到接口，edgeGrid 边模型后续重构只改端口实现）；
  演进路线（事件流/元件接口化）沉淀至 redstone-evolution-roadmap.md
- 📄 技术文档：living-power-tech.md §3.8 / 红电系统.md v19.1 修订

> 📄 2026-08-30 及更早的更新记录已迁至 [changelog.md](docs/archive/changelog.md)
> （红电阶段一~四落地、1 game tick 传播、v8/v8.1 重构周期、活水车/活地图/活箱子/活末影箱等全部历史条目）

---

## 文档导航

```
docs/
├── system-design/                    # 系统设计文档
│   ├── living-item-infrastructure.md #   活物品基础设施（容器抽象+发现缓存+SlotAccessor+性能监控）
│   ├── data-model.md                 #   数据模型与设计决策（DataComponent体系+新旧对比+关键决策）
│   ├── gui-interaction-system.md     #   GUI交互系统（声明式规则+创造模式兼容）
│   ├── icon-system.md                #   图标系统（三层架构+声明式配置）
│   ├── tooltip-system.md             #   Tooltip系统（双层渲染+运行时缓存+显示口径）
│   ├── redstone-evolution-roadmap.md #   红电架构演进路线（SensorPort+事件流+元件接口化）
│   ├── power-invariants.md          #   红电不变量清单与四层测试方案（31条可执行断言）
│   └── oversized-stack-audit.md     #   超大堆叠审计（模组容器上限>64 的活物品表现）
├── framework-refactoring.md          # 框架重构总结（HasDirection + HasContainerData 接口化设计）
├── 红电系统.md                        # 活红石+活铜块+活避雷针规划（v19.2 活潜影箱已剥离）
├── 活潜影箱实现细节.md                # 活潜影箱独立设计（通用嵌套基础设施，随活物品扩展生长）
├── idea.md                           # 原始创意笔记（活铜块/电力部分）
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