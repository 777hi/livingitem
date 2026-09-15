# Living Item (活物品)

**Minecraft 1.21.1 + NeoForge 21.1.x**
*最后更新: 2026-09-16*
*状态: Alpha 测试阶段 - v8.1 接口化重构完成 + 红电相位解读三元件（v19.1）+ 注册式槽位交互扩展点（`SlotInteractions`，2026-09-15）+ 活耕地放置回世界（`BlockItemMixin`，2026-09-16）*

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
| **活耕地** | GUI 交互获取/种植/骨粉 + 世界轴节拍生长 + round-robin 逐项产出 + 双槽渲染 | [living-farmland-tech.md](docs/tech/living-farmland-tech.md) |
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
│   │   ├── farmland/                         #   活耕地领域
│   │   │   ├── LivingFarmlandFunction.java   #     tick 功能入口（生长/产出状态机 + tooltip）
│   │   │   ├── FarmlandPlantComponent.java   #     种植数据组件（作物标记+age+round-robin 产出）
│   │   │   ├── CropClassifier.java           #     作物分类器（准入三层/maxAge/茎果实 AT/收获形态/上部件注册表）
│   │   │   ├── FarmlandBonemealInteraction.java #  槽位交互条目：普通骨粉 × 活耕地 → 施肥（注册进 SlotInteractions）
│   │   │   └── LivingFarmlandPlacement.java  #     放置回世界：模拟玩家右键种一次（软逻辑，异常自吞）
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
│   │   ├── SlotInteraction.java             #   接口：槽位交互（货物 × 目标槽的替代语义）
│   │   ├── SlotInteractions.java            #   槽位交互注册表 + 分发器（三处传输分支唯一入口）
│   │   ├── SlotResolver.java                #   槽位解析
│   │   ├── ContainerCompatibilityConfig.java #  容器兼容性配置
│   │   └── FilterData.java                  #   过滤数据（从 data/ 迁入，跨领域共享）
│   │
│   ├── interaction/                         # GUI交互
│   │   ├── InteractionEntry.java            #   交互规则 record
│   │   ├── InteractionRegistry.java         #   交互注册表（两趟优先级匹配：精确触发器 > 通配）
│   │   ├── InteractionHandler.java          #   处理器接口
│   │   ├── IgniteHandler.java               #   活打火石点燃活TNT
│   │   ├── IgniteCarriedHandler.java        #   反向点燃（TNT→打火石）
│   │   ├── ButtonPressHandler.java          #   活按钮按压
│   │   ├── LeverToggleHandler.java          #   活拉杆切换
│   │   ├── RepeaterCycleHandler.java        #   活中继器延迟循环
│   │   ├── ComparatorToggleHandler.java     #   活比较器模式切换
│   │   ├── TillToFarmlandHandler.java       #   活锄头耕活泥土→活耕地（物品转换型）
│   │   ├── PlantCropHandler.java            #   活种子种植（通配条目+handler 校验）
│   │   └── BonemealHandler.java             #   活骨粉催熟（精确触发器条目）
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
│   │   ├── BlockItemMixin.java              #   放置活耕地后自动种下自带作物（consume 前注入）
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
│   │   ├── CropTextureResolver.java         #   作物阶段纹理解析（blockstate→模型→粒子图标）
│   │   ├── LivingItemTooltip.java           #   Tooltip 渲染
│   │   ├── LivingDefaultDecorator.java      #   默认图标叠加层
│   │   ├── LivingHopperDecorator.java       #   活漏斗箭头叠加层
│   │   ├── LivingChiseledCopperDecorator.java # 活雕文铜块箭头叠加层
│   │   ├── LivingWaxedChiseledDecorator.java  #   活涂蜡雕文输入方向箭头（电力层移相器，v19.1）
│   │   ├── LivingRedstoneDecorator.java     #   活红石粉连线叠加层
│   │   ├── LivingFarmlandSeedDecorator.java #   活耕地种子图标叠加层（装饰器路径，快捷栏也生效）
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
    ├── LivingItemSyncPacket.java           #   活物品遥测同步包（运行时缓存→客户端 tooltip）
    ├── LivingChestAccessPacket.java         #   活箱子访问包
    ├── LivingMapGuiTeleportPacket.java      #   活地图 GUI 传送包
    ├── LivingMapMetadataPacket.java         #   活地图元数据包
    └── ServerPacketHandler.java             #   服务端包处理
```

```
src/test/java/com/qiqi/li/
├── testutil/
│   └── FakeContainerContext.java              # ContainerContext 测试替身（内存数组实现）
├── client/render/
│   └── LivingFarmlandSeedDecoratorTest.java   # 种子图标装饰器守卫·普通/未种植/已种植/非耕地（4 项）
├── living/container/
│   └── SimpleContainerContextTest.java        # 容器上下文脏槽同步（25 项）
├── living/domain/redstone/
│   └── ContainerRedstoneDataTest.java         # 红石信号传播（29 项）
├── living/domain/power/
│   ├── PowerMathTest.java                     # 发电数学（8 项）
│   ├── ContainerPowerDataTest.java            # 相位质量状态机（6 项）
│   ├── AccountingGateTest.java                # 跳变门控记账（4 项：零产出/冻结/去重/中性化）
│   ├── PhaseInterpretationTest.java           # 相位解读三元件（8 项：移相/链/环自熄/死源/裂相/加法）
│   ├── ChannelStateTest.java                  # 相位域偏移活性（10 项）
│   ├── CoilGroupingTest.java                  # 形态识别 + 单通道（5 项）
│   ├── NetworkResonanceTest.java              # 铜块网络共振（18 项）
│   ├── NetworkTraversalTest.java              # 铜块网络遍历（5 项，含 E2E 真实传播）
│   ├── WaxedCopperStorageTest.java            # 储能（20 项，含 telemetry）
│   ├── BulbItemEnergyStorageTest.java         # 铜灯通用电池（7 项）
│   ├── PhaseSnapshotWarmupTest.java           # 相位快照热身（8 项）
│   ├── RoundTripConservationIT.java           # Mekanism 往返守恒（4 项）
│   ├── WaxedGeneratorFeedChainTest.java      # 发电机喂链（12 项）
│   ├── WaxedCopperOscillatorIT.java           # 振荡器→发电全链路集成（5 项）
│   └── WaxedCopperCouplingIT.java             # 耦合链集成：多跳中继+防回环（3 项）
├── living/domain/chest/
│   └── LivingChestFunctionTest.java           # 取消活化堆叠倍数返还（6 项）
├── living/domain/farmland/
│   ├── CropClassifierTest.java               # 作物分类器·火把花/瓶子草/柱状段回归（10 项）
│   ├── LivingFarmlandFunctionTest.java      # 输出合并回归·部分合并丢物品守卫（5 项）
│   ├── FertilizeTransferTest.java           # 自动施肥·骨粉→活耕地零空转守卫（6 项）
│   └── LivingFarmlandPlacementTest.java     # 放置回世界·落点/幼苗/客户端/异常不冒泡+端到端接线（5 项）
├── living/domain/furnace/
│   ├── LivingFurnaceFunctionTest.java         # 燃料消耗合成残留物语义（4 项）
│   └── FurnaceBurningFlagTest.java            # 燃烧标志组件·图标切换回归（5 项）
├── living/domain/hopper/
│   ├── HopperFilterSyncTest.java              # 漏斗过滤链回写·黑白名单展示回归（5 项）
│   └── CrossContainerTransferFertilizeTest.java # 跨容器施肥·推送/拉取双入口+活骨粉三入口全拒（15 项）
├── living/domain/map/
│   └── MapCoordHelperTest.java                # 地图坐标换算（29 项）
├── living/interaction/
│   └── InteractionRegistryTest.java           # 两趟优先级匹配·通配遮蔽+triggerFilter 回归守卫（7 项）
└── living/transfer/
    ├── ContainerCompatibilityConfigTest.java  # 容器布局推断（14 项）
    └── SlotInteractionCargoGateTest.java      # 槽位交互货物准入真值表·活骨粉不施肥（5 项）
```

**合计测试用例 297 个**（含参数化展开与 `SimpleContainerContextTest` 的 `@Nested` 内部类）。
全绿基线：`297 passed / 0 failed / 0 skipped`（2026-09-16 种子图标装饰器）。

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
- [x] 活漏斗自动施肥（2026-09-15）：骨粉货物 + 活耕地目标 → 施肥消耗 1 粉触发生长 tick（普通骨粉即可，equals 零空转，同容器 + 跨容器推送两路径）

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

### 活耕地
- [x] GUI 交互获取（活锄头耕活泥土，6 锄头变种）+ 种植（**消耗与耕地堆叠数等量的活种子**，数量不足无法种植）+ 骨粉催熟（**强制一次必定成功的生长 tick**：未成熟 +1/成熟触发产出，无变化不消耗）
- [x] 生长状态机：世界轴时间戳节拍（200t，稳态零写入）+ 湿润传播（**4 级 BFS：水源相邻=源 4 级，沿相邻活耕地逐跳 -1，≥1 即湿润**）+ 顶行正常生长/产出挂起
- [x] round-robin 逐项产出：战利品表首轮冻结进组件、每 tick 不限速逐项 ×堆叠数、留种（cropSeed 产出项总量 -1）、标准/浆果双模式、收获形态覆盖（HARVEST_BLOCKS：FD 稻米/番茄/火把花——下部表只掉种子的作物滚覆盖方块表）
- [x] 作物准入三层：Block 白名单（CropBlock/StemBlock/NetherWart/SweetBerry/PitcherCrop）+ c:seeds 标签 + 甜浆果手动映射；茎作物产出 = 果块直取（stem.fruit AT）；maxAge 读 age 属性真实上限（模组作物兜底）+ 存量组件自愈（注册表修正后重冻结）
- [x] 双槽渲染：耕地槽种子物品图标叠加（**IItemDecorator 装饰器路径**，快捷栏/容器 GUI/创造物品栏共用同一份代码）+ 上方空生长槽 renderSingleBlock 世界管线阶段大图 + 三种多格模式（DOUBLE_BLOCK_HALF 半部件 / UPPER_CROPS 注册式 / COLUMN_PARTS 柱状属性分段——FD 稻米/KC 水稻/瓶子草全收齐）+ 同帧生长槽认领防双画 + 渲染异常隔离
- [x] 技术文档 [living-farmland-tech.md](docs/tech/living-farmland-tech.md)（idea.md 内容转化）+ 回归守卫测试（CropClassifierTest 10 项 + LivingFarmlandFunctionTest 5 项 + InteractionRegistryTest 7 项）
- [x] 游戏实测九轮全过（渲染/产出/交互全正常，2026-09-14 用户确认）；终审修复：部分合并丢物品（合并仅当放得下整份）+ 双重认领 + 模式互斥 + 留种总量 -1 + maxAge 自愈，262 用例全绿
- [x] 放置回世界自动种植（2026-09-16）：已种植活耕地物品放置到世界 → 耕地上直接长出作物。**模拟玩家右键**（`useOn`）而非 `setBlock`，不绕过模组种植校验；软逻辑（种不上/抛异常一律静默）；一律幼苗不保留成熟度

### 容器兼容性
- [x] IItemHandler 统一容器抽象（原版 + 模组容器）
- [x] 自动布局推断（`ContainerCompatibilityConfig.findOrGenerateRule`）
- [x] 大箱子双重去重

---

## 开发进展

> 📄 **更早的记录**：`2026-09-04` 及更早已迁至 [changelog.md](docs/archive/changelog.md)
> （按日期倒序；含红电阶段一~四落地、1 game tick 传播、v8/v8.1 重构周期、活水车/活地图/活箱子/活末影箱等全部历史条目）。
>
> **归档规则**：早于最近约两周的条目迁入该文件，归档时**必须校验日期连续性**
> ——「changelog 最新日期」与「本节最早日期」之间**不得有空档**，并同步更新本行指针。
> ⚠️ `2026-09-01 ~ 09-04` 曾因漏做归档而**断档**（只在 `.workbuddy-ai/memory/` 日工作日志里，
> 而日志会定期删除），已于 2026-09-16 补录 —— **别再漏**。

### 当前版本: v0.9-alpha

**最近更新** (2026-09-16):
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

**最近更新** (2026-09-14):
- ✅ 修复：**活漏斗黑白名单链 tooltip 显示为空（同款 b064865 后遗症）**——
  「tooltip优化，nbt数据简化」删除了 tick 里 `data.withFilter(filter)` 的
  DataComponent 写回，但 tooltip 仍读 `LivingHopperData.filter()` → 恒 EMPTY，
  黑白名单永远显示无规则（用户实测「nbt里的数据没了」）。**功能链路经排查完好**
  （过滤本体是快照派生数据：HopperFilterBuilder 每 tick 从容器布局+物品重建，
  经修订计数失效机制保持新鲜，与 DataComponent 无关）。修复（熔炉燃烧标志同款
  方案）：过滤链迁至独立组件 `LIVING_HOPPER_FILTER`（FilterData 自带
  Codec+StreamCodec），tick 在快照重建结果变化时回写 + syncSlotToClients（稳态
  零写入；搬运后旧规则过期下一 tick 自愈）；tooltip 两处改读新组件；组件进
  `getIgnoredComponentTypes()`——过滤链是容器环境派生数据（同一容器里两个漏斗的
  规则必然不同），不忽略会破坏漏斗堆叠；`LivingHopperData.filter` 降级遗留兼容
  字段。回归测试 HopperFilterSyncTest（5 项：黑名单/白名单语义、稳态零同步、
  货物移走自愈、堆叠兼容），全量 247 用例全绿；收编 living-hopper-tech.md §2.4.2
  （存储位置沿革）。**追加（同日）**：`LIVING_HOPPER_FILTER` 与 `LIVING_FURNACE_BURNING`
  改为**不落盘**（只 `networkSynchronized`，vanilla `MAP_POST_PROCESSING` 先例）——
  两者都是每 tick 可从运行时状态重建的派生数据，持久化无正确性价值（玩家打开 GUI 前
  容器必然已 tick），徒增存档体积；组件照常随槽位同步包到客户端，展示链路不变。
  **再追加（同日审计后）**：全活物品审计确认无第三处同类问题（运行时缓存仅
  熔炉/漏斗/红电使用；红石家族/水车/水桶/耕地/箱子/地图全部「变化检测写+同步」配对，
  拉杆/按钮走 broadcastChanges 兜底）；`LIVING_FARMLAND_MOIST` 顺势统一为不落盘

**最近更新** (2026-09-14):
- ✅ 终审（活耕地，用户实测九轮全过后全面代码审查：三路并行审计——服务端逻辑/客户端
  渲染/文档测试一致性）——**唯一确认功能性 bug：部分合并丢物品**：生长槽同种半堆
  空间不足整份产出时旧实现塞部分即推进索引，`outputCount-toAdd` 差额静默消失
  （玩家留半堆产物即触发）。修复：合并仅当 space ≥ outputCount，放不下本 tick
  等待（与「不同种占据」同语义零损失）+ 回归测试 LivingFarmlandFunctionTest
  （5 项：部分合并等待/整份合并/空槽全放/堆叠上限钳制/边界含等号）。
  渲染两缺陷：**同帧生长槽双重认领**（同列双耕地隔空行 → 中间槽双画，per-frame
  认领表先到先得）+ **多格模式无互斥**（上部件与柱状段双注册同槽双画，命中其一
  即跳过其余）。加固四项：renderSingleBlock 异常隔离（第三方作物颜色处理器 NPE
  只跳过该槽不崩屏）+ 留种改总量 -1（多池同种子只扣一份）+ 存量 maxAge 自愈
  （注册表修正后旧组件重冻结+age 钳制，maxAge=7 时代耕地不需铲掉重种）+
  清洁收尾（注释漂移 3→4、agePropertyOf 去重、重复 import、死代码删除）。
  设计取舍明示：「留种后全空原样输出」= 种子-only 作物每周期净产 1 种子
  （防零产出循环的有意行为）；超大堆叠耕地（count>64）因等量种子消耗不可种。
  文档同步：AGENTS 勾选项三处五轮前旧口径修正（种子消耗/骨粉语义/湿润 4 级
  BFS）+ 全绿基线 262 + 测试索引 6 处计数修正 + 补列 4 个缺类 + network/ 补
  LivingItemSyncPacket；技术文档五轮残留清理（§1.1/§1.2/§1.3/§2/§5.2 内部
  自相矛盾六处）。全量 262 用例全绿
- 🐛 修复（活耕地，第九轮实测：KC 水稻三格只渲染一格）——**第三种多格作物形态**。
  KC（KaleidoscopeCookery）水稻既非原版半部件（DOUBLE_BLOCK_HALF 两值）也非 FD 的
  两个独立方块，而是**同方块 + IntegerProperty 分段**：`rice_crop` 带 age(0~7)+
  location(0=下/1=中/2=上)+waterlogged，三格同放同长（updateShape 从下方邻居拷贝
  age）、每段每 age 独立模型。我方 stateForAge 默认态只出第一格（中/上空白），
  两种既有上部件模式全不命中。修复：**COLUMN_PARTS 注册表**（作物注册名 → 各段
  属性覆盖列表，getColumnParts 在 stateForAge 基础上应用覆盖、属性缺失段静默跳过）
  + 渲染循环从生长槽正上方逐格向上画各段（空槽才画、到顶/被占自动截断）；注册名
  延迟解析软依赖安全。产出侧零改动（KC getDrops 只在 location=down 段滚表，我方
  默认态恰好命中——「产出正常」的实测印证）。教训：多格作物实现方式至少三种
  （半部件翻转/独立上部件方块/同方块属性分段），互相不可推导，渲染按「属性结构
  +注册表」双轨解析。CropClassifierTest 补柱状段 3 项（10 项），全量 257 用例
  全绿；收编 living-farmland-tech.md v1.5 §8.2.1 三模式收齐 + §9.1 + §11.13
- 🐛 修复（活耕地，第八轮实测：火把花零产出 + 瓶子草无法种植，原版同型怪癖）——
  ① **火把花**：原版 TorchflowerCropBlock 的 AGE 属性是 AGE_1（值域 0~1）而
  getMaxAge()=2，成熟态 getStateForAge(2) 直接变花方块（作物方块没有 age=2 状态）；
  战利品表任何 age 只掉种子×1（与 FD 下部表同型：真实收获在花方块表）。我方双断点：
  matureStateFor 值域不含 2 → 冻结失败零产出、stateForAge(,2) null → 成熟渲染空白。
  修复：HARVEST_BLOCKS 补 torchflower_crop→torchflower（每轮产火把花×1）+
  **displayStateFor**（渲染专用：age 越界且已成熟 → 回退收获形态方块默认态）。
  ② **瓶子草**：PitcherCropBlock extends DoublePlantBlock 而非 CropBlock，白名单
  四 instanceof 全不中 + pitcher_pod 不在 c:seeds 标签 → 三层准入全漏。修复：
  白名单补 instanceof PitcherCropBlock 精确类（不用 DoublePlantBlock 兜底——
  玫瑰/牡丹/向日葵全是其子类）；其余路径零改动（maxAge 兜底=4、HALF 默认
  LOWER 恰好命中产出池条件、age 0~2 的 top 模型原版就是空几何）。教训：
  「种植入口 = CropBlock 子类」假设对双格作物不成立。新增 CropClassifierTest
  （7 项，纯逻辑），全量 254 用例全绿；收编 living-farmland-tech.md v1.4
  §4.1/§6.1/§9.1/§11.12/§12.2/§10.3
- 🐛 修复（活耕地，第七轮实测：FD 稻米/番茄成熟后零产出）——**下部战利品表只掉
  种子 + 留种扣空死循环**。根因双叠加：① 冻结实³滚的是种植入口方块自身的战利品
  表，而 FD 下部表只掉种子本身（rice.json → 稻谷×1、budding_tomatoes.json →
  番茄种子×1——FD「挖掉作物」的保底就是种子，真实收获在抽穗/结果藤上）；② 留种
  -1 把唯一一叠扣成 0 → 「留种后全空」按回退点重置 → 重长再扣空无限循环。
  修复：**收获形态注册表 HARVEST_BLOCKS**（作物方块 → 收获形态方块，CropClassifier
  新增：稻米→rice_panicles、番茄→tomatoes，注册名延迟解析 FD 缺席回退自身行为
  不变 + registerHarvestBlock 扩展点）——冻结时滚覆盖方块的成熟态战利品表：
  稻米每轮产稻穗×1（空工具原版语义，稻穗≠种子留种不扣）、番茄每轮产番茄×1~2 +
  种子×1（留种恰扣种子）+ 5% 烂番茄；番茄藤 VINE_AGE 属性名就是 "age" +
  ROPELOGGED 默认 false，matureStateFor 现有逻辑直接命中产出池条件零特判。
  守卫加固：「留种后全空」从按回退点重置改为**原样输出未扣减产出**（重置空转
  只会零产出循环）。教训：种植入口/成熟渲染/产出来源是三个独立关注点
  （getBlockFromSeed / getUpperCompanion / HARVEST_BLOCKS 各自承担），
  「自身表 = 收获形态」假设对多阶段作物不成立。全量 247 用例全绿；
  收编 living-farmland-tech.md v1.3 §6.1 收获形态解析 + §11.11 踩坑 +
  §9.1 FD 两行 + §12.2 验证项

**最近更新** (2026-09-13):
- ✅ 优化（活耕地，两格高作物上部件渲染）——**FD 稻米成熟后上方渲染满穗稻穗模型**
  （稻米 = RiceBlock + 上方独立 RicePaniclesBlock 两个方块，上部件无法推导 →
  CropClassifier 新增 UPPER_CROPS 注册表：字符串查找软依赖安全、FD 缺席自动跳过）
  + **原版半部件通用模式**（DOUBLE_BLOCK_HALF，瓶子草等：同方块 HALF=UPPER 同 age
  同步生长常驻）。渲染：主模型画完后解析上部件（getUpperCompanion）→ 生长槽正上方
  空槽 blit 其粒子图标；上部件槽位被占/顶行自动让位，纯视觉不影响 tick/产出。
  配套重构：stateForAge 迁入 CropClassifier（common）、findSlotAbove/blitSprite
  提取复用
- 🎚️ 优化（活耕地，第五轮实测反馈四连）——**种子图标可见**（耕地槽叠加 renderItem
  固定画在物品层 z=150 与耕地图标同层被覆盖，pose 抬升 z+150 到 300——map 渲染
  「需高于物品 150/堆叠数 200」同款经验）+ **骨粉简化**（一切行为由生长 tick 决定：
  骨粉=强制触发一次必定成功的生长 tick，未成熟 +1/成熟触发产出，无变化不消耗；
  +2~5 双语义已删）+ **种植消耗回归**（消耗与耕地堆叠数等量的活种子，数量不足
  无法种植；triggerFilter 升级 BiPredicate<trigger,target> 组合过滤，种子数不足
  不拦截原版交换，canPlantWith 双端共用）+ **湿润传播原版化**（水源相邻活耕地
  =3 级沿相邻活耕地逐跳 -1，≥1 即湿润 f=3.0——一个水源湿润 4 直接相邻+间接
  扩散 2 层，参考活红石粉信号传播 BFS 形态；computeMoisture 每 tick 从 fluidData
  现算派生态；水桶源槽位也算相邻）。技术文档 v1.2 同步
- 🎚️ 优化（活耕地，第四轮定稿：生长/产出节拍重设计）——**输出不限速**（待输出冻结后
  每 tick 推进进生长槽，不再 10 秒一项；生长槽被占只阻塞输出不影响生长，BLOCKED
  age 归零语义删除）+ **概率驱动获取**（成熟不着急滚战利品表，概率 tick 成功才冻结）
  + **留种**（冻结时 cropSeed 产出项数量 -1 变相自动补种，小麦种子 2→1）+
  **浆果丛采后回退 age=1**（查证原版 SweetBerryBushBlock：右键采摘后重置 1 保留
  2/3 进度，产出由概率 tick 模拟自动采摘；isBerryModeCrop → getHarvestResetAge）+
  **骨粉双语义**（未成熟 +2~5 到 maxAge 即时冻结；已成熟直接触发产出=确保冻结，
  输出下 tick 送达；组件无变化不消耗骨粉）。配套：耕地槽叠加改**种子物品图标**
  （renderItem 原版种子纹理，类型一眼区分）+ **湿润图标**（LIVING_FARMLAND_MOIST
  布尔组件每 tick 检测翻转写入+主动同步，图标 moist/dry 双变体切原版
  farmland_moist 深色纹理，零新 PNG；进 ignored 组件湿润/干燥可堆叠——燃烧标志
  同款先例）。状态机/参数表/验证清单全量重写 living-farmland-tech.md v1.1
- 🎚️ 优化（活耕地，第三轮实测反馈）：**裸通配条目吞掉原版右键操作**——plant_crop
  的 trigger=null 让客户端拦截活耕地的**所有**右键（空手分堆/拿起/放置全被吞，
  handler 静默返回时原版点击已取消）；对照活TNT（精确触发器 FLINT_AND_STEEL）
  从无此问题。修复：`InteractionEntry` 新增 **triggerFilter 谓词**
  （`Predicate<ItemStack>`），plant_crop 挂 `CropClassifier::isSeedPlantableOnFarmland`
  ——拦截面从「任意光标」收窄到「可种植种子」，其余光标不匹配不发包，原版操作照常；
  服务端 handler 校验保留为双重防线。规则收编 gui-interaction-system.md §5.1：
  特定物品交互→精确触发器；「一类物品」交互→triggerFilter；真·任意光标自交互
  （按钮/拉杆）才留裸通配。踩坑记录 living-farmland-tech.md §11.7
- 🐛 修复（活耕地，同轮）：**取消活化残留 farmland_plant 组件**——clearLivingData
  是取消活化的统一清理点（javadoc 明确要求新功能补 remove），实现时漏了 FARMLAND_PLANT
  → 取消活化 tooltip 仍显示旧作物、再活化旧状态复活。已补
  `stack.remove(FARMLAND_PLANT.value())`（§11.8）
- ✅ 新增：**活耕地**（设计 idea.md → 技术文档 living-farmland-tech.md v1.0，全量实现 + 三轮实测修复）——
  获取（活锄头耕活泥土，6 锄头变种规则）/ 种植（活种子右键，**种子不消耗**=类型标记，
  数量与堆叠数解耦）/ 骨粉催熟（+2~5，Mth.nextInt 双闭=原版公式）/ 生长（世界轴
  时间戳节拍 200t，湿润左/右/下活水流 f=3.0 vs 干燥 f=1.0，稳态零组件写入）/
  产出（round-robin 逐项：战利品表 Block.getDrops 首轮冻结进组件 pendingDrops，
  每冷却周期一项 ×耕地堆叠数到 E_UP 生长槽，标准模式产完重长/浆果模式持续产出）/
  渲染（双槽：耕地槽 16×16 作物小图 + 上方空槽阶段大图；CropTextureResolver 走
  blockstate→模型→粒子图标查表，覆盖胡萝卜 8-age→4-stage、下界疣共用模型等原版
  映射）+ 茎作物产出来源=果实战利品表（StemBlock.fruit AT）+ 甜浆果手动映射
  （SWEET_BERRIES 非 BlockItem，三层准入全漏）
- 🐛 修复（交互，09-12 第一轮实测）：**按下拦截后释放阶段原版 PICKUP 二次执行**——
  原版对「光标非空」的放置/交换发生在 mouseReleased，按下取消拦不住；三个 Screen
  mixin 拦截成功处置位原版 skipNextRelease 让释放自我跳过。手持触发型交互（锄头/
  种子/骨粉）交互生效的同时不再与槽位交换；ignite 老交互的打火石换位副作用一并治好。
  收编 gui-click-interception.md 坑 10 + 经验总结第 10 条
- 🐛 修复（生长，09-13 第二轮实测）：**活骨粉不能催熟 = 通配条目遮蔽精确条目**——
  plant_crop（trigger=null）与 bonemeal 同 target/button 注册，findInteraction 注册
  顺序首配 → 骨粉永远命中 plant_crop，BonemealHandler 死代码。根修：
  findInteraction 改两趟匹配（精确触发器优先于通配，通配语义=兜底而非抢先）+
  bonemeal 条目改精确触发器 BONE_MEAL；顺修骨粉 +2~4（RandomSource.nextInt 上界
  排除）→ +2~5、催熟到 maxAge 即时冻结战利品表（不等下个冷却周期）、创造模式
  种植跳过可种植性校验（carriedTag 已恢复可校验）。守卫：InteractionRegistryTest
  （5 项，故意按最坏注册顺序断言精确优先）
- 🐛 修复（渲染，同轮）：**作物不生长不渲染**——顶行耕地 growthSlot<0 被误算进
  slotBlocked → 永久 BLOCKED age 恒 0（与规则②矛盾）；修为仅「生长槽存在且被占」
  才 BLOCKED，顶行正常生长、产出挂起（搬到有生长槽位置自动开始产出）。双槽渲染
  补上方生长槽大图（坐标匹配 y-18 同容器空槽即画，自动覆盖生长中/BLOCKED/产出/
  取走全状态）。定界收编 living-farmland-tech.md §11.3/§8.2 +
  gui-interaction-system.md §5.1 通配遮蔽坑
- ✅ 文档：**idea.md 完成使命收口**（内容转化进 living-farmland-tech.md 后保留
  历史指向）；活耕地三交互的实测坑链（释放二次执行/通配遮蔽/顶行 BLOCKED）全部
  收编。全量 240 用例全绿（235 基线 + InteractionRegistryTest 5 项）

**最近更新** (2026-09-11):
- 🐛 修复：**φ 重进漂移第三轮：坐标系换轴（游戏二次实测定位真根因）**——玩家
  反馈前两轮修复后 tooltip 的 φ 仍重进即变（16 周期 φ=14 → φ=12），三元件
  （雕文/切制/格栅）基于 φ 的派生随之漂移。真根因：φ = lastRisingTick mod P
  锚在**容器本地 tickCounter**（重进从 0 起步），而中继器物理相位（delayTimer
  落盘）重进后续跑——两个原点互不相关，首跳落位随机 → φ 重锚。前两轮防的是
  「数据被污染」，没发现**坐标系本身重进就换**；旧回填 `base−sinceRise` 跨轴
  平移在轴差 ΔW 下 φ 平移 (ΔW mod P)。修复（三处）：① 相位时钟换世界轴
  `resolvePhaseClock`（level.getGameTime()，跨会话连续，与振荡器物理相位
  同源；Level 不可达回退本地轴保持单测可驱动），capture/restore 同轴适配；
  ② 回填锚定数学改 **φ 反推锚** `anchor = now − ((now−φ) mod P)`——offset()
  恒等于快照 φ 且首跳 interval 恰为 P（手算双验证；方向踩坑记录：必须减
  now 的余数，减传入锚的余数会推向过去同余点使 interval 变 2P）；sinceRise
  从此只用于活性窗口。③ 测试 mock Level 补递增 getGameTime（未 stub 恒 0
  → 跳变挤 tick 0 → 派生空，新增 mockServerLevel helper）。新增世界轴快照
  往返守卫（capture@世界W → restore@W+1000 → φ 不变，旧轴必失败）；两个
  旧「负锚」断言按新锚定数学重写（负锚是旧平移公式产物，已退役）。全量
  235 用例全绿；教训链（防污染下游→防污染入口→换坐标系）收编
  living-power-tech.md §6.5 第三轮复盘小节。**游戏实测确认（用户验证）**：
  原版红石线路 + 活红石线路（含跨容器）重进相位稳定 ✓；残留边界——第三方
  模组红石元件作跨容器信号源时相位仍可能漂移（该元件相位由其 mod 方块
  内部状态驱动，重进重建时序不受我方控制，无 API 可介入）→ **定界为我方
  契约边界而非缺陷**（我方保证「信号进入活红石网络后跨会话稳定」），已记
  living-power-tech.md §8 已知限制表 + 红电系统.md §3.11 生态兼容实证第 4 条

**最近更新** (2026-09-09):
- 🐛 修复：**相位重进洗牌第一轮修复无效（游戏实测复盘）→ 三 bug 根修**——第一轮
  warmup+PhaseSnapshot 双修复存在覆盖漏洞：① warmup 只拦 PhaseEvent 注入不拦
  `tracker.onRisingEdge`——首拍假沿把回填平移的负锚覆盖成 0，快照恢复的 φ 当场
  被毁，周期 EMA 随后被拉偏、相位域散裂（warmup 结束后注入的全是污染数据）；
  ② `restoreInto` 里 `clearWarmup()` 把剩余防线也拆了；③ `worthSaving` 的「时间轴
  错乱防御」误拒回填负锚（lastRisingTick<0 是回填合法状态）——窗口内二次退出
  即丢相位。根修：`RedstoneSensor.hasEdgeHistory()` 接口 +
  `ContainerRedstoneData` 本会话首次 calculate 置位，`runBfs` 无历史时**整段跳过
  边检测**（跟踪+注入都跳，假沿从源头不进系统；负锚存活到首个真实跳变，
  interval=(P−d)−(−d)=P 精确续接）；测试 seam `setIncomingEdgeForTest` 声明
  历史；`restoreInto` 不再 clearWarmup（宽限与快照互补）；`worthSaving` 只拒
  未来锚。测试影响：E2E 驱动循环补首拍热身（对齐真实时序），PhaseInterpretation
  3 处 φ 断言按热身时间轴 +1 修正（相对关系不变）。全量 234 用例全绿；
  复盘记录收编 living-power-tech.md §6.5（根修复盘小节）
- ✅ 修复：**退出重进后容器内线路相位洗牌（调相布局失效）**——相位账本是纯内存
  缓存，账本死亡（LRU 120s 回收 / 退出重进 / 跨存档搬运）后所有振荡器从零重锁，
  多路相对相位被「重载时刻」重新锚定：玩家调好的满相布局（n=P）不再适配，冗余
  线路只能缓解。双修复：① **首拍无沿宽限（warmup）**——新账本默认前 8 tick
  电力层不注入 PhaseEvent（照常跟踪边信号重建周期估计），防「prevEdgeGrid 空 →
  稳态电平全伪装成上升沿」的假沿风暴；② **PhaseSnapshot 相位快照落盘**——
  `CONTAINER_PHASE_SNAPSHOT` BE 附件（带 Codec serialize 真正写入存档，全 mod
  第一个跨会话容器级附件——流体/应力附件均无 Codec 仅会话内存）每 tick 末冻结
  已锁相边的 (P, φ, sinceRise, δ)，账本重建时平移回填（整数周期下 φ 不变），
  回填成功跳过 warmup。只存慢变量（锁相结果），快变量（EMA/注册表）从真实
  跳变重学——改线后旧快照 2~3 周期自愈，无磁盘说谎面。顺带修
  `SignalTracker.offset()` 负 tick 的 `%` → `floorMod`。测试
  PhaseSnapshotWarmupTest（5 项），全量 226 用例全绿；收编 living-power-tech.md
  §6.5（账本重生的相位连续性）+ §6 测试表
- ✅ 修复：**超大堆叠灯堆对外读数 int 回绕**——C=1M 标定后 count ≥ 2,148 盏
  （2³¹/1M = 2,147.48）的堆总量越 IEnergyStorage 的 int FE 公约（上限 21.4 亿），
  `(int)` 强转回绕成负数 → 外部 mod（如 Mek 电缆 `max−stored` 缺口判定）读到
  巨大正缺口往死里灌的怪行为。修复：两个读数出口（`ContainerEnergyStorage`
  容器面 + `BulbItemEnergyStorage` 物品面）`Math.min(真值, Integer.MAX_VALUE)`
  clamp，语义「至少 21.4 亿 FE」；内部 long 账本/充放电/守恒全程无损（本次
  只治读数出口）。回归测试 BulbItemEnergyStorageTest.oversizedStack_
  readsClampToIntMax（2,148 盏满堆断言双读数 = Integer.MAX_VALUE），全量 221
  用例全绿；oversized-stack-audit §2.8 🟠→🟢（已修复）。顺带修正前日文档
  阈值笔误 2,142 → ≥2,148（四处 + javadoc）
- 🎚️ 标定：**铜灯每盏容量 C 10,000 → 1,000,000 FE**（`PowerMath.BULB_UNIT_CAPACITY_FE`，
  标定史 1k→10k→1M，游戏实测「10k 不太够用」驱动）。一堆(64) = 64M FE，对齐
  科技生态中高档单格电池（Mek 能量立方 / Flux 储存器档位），充电宝物流彻底实用
  （一盏 = 一个满配方块电池）。耦合面：2 处容量断言修正（BulbItemEnergyStorageTest
  满容读数 16M；WaxedCopperStorageTest 半满基准 500_000_000 保持 2:1 本意），
  全量 220 用例全绿。**int 收窄警戒线升级**：对外 IEnergyStorage 是 int FE，
  单堆读数溢出需 count ≥ 2,148 盏（2³¹/1M = 2,147.48）——原版 64 安全，超大堆叠容器（抽屉类）已可
  触达（读数回绕，long 内部账本无损，fail-safe），oversized-stack-audit §2.8
  由「纯理论」升「已可触达」并附修复方向（读数 clamp + 分批，待需求驱动）。
  标定记录收编 红电系统.md §3.6 / living-power-tech.md §4 / oversized-stack-audit.md §2.8
- 🐛 修复：**铜灯往返凭空造电（零头回收记账 count 倍放大）**——两个装满铜灯的原版
  容器用 Mekanism 电缆互传，总电量缓慢上升（实测每 tick 约 2 mFE，1000t ≈ +2043 FE）。
  排查：翻 Mekanism-1.21.x 源码确认电缆侧教科书守恒（SIMULATE 探测→convertFromAndBack
  钳制→EXECUTE、网络按返回值记账、FE↔J 双向防增益），锁定我方
  `ContainerEnergyStorage.receive` 零头回收循环：给灯堆写 `q+1` 实充是
  count mFE（每盏模型），账面只 `distributed++` 记 1 mFE——实充 = 记账 × count，
  差额被电缆按返回值记账后全部变成净增益。复现测试 `RoundTripConservationIT`
  （4 项：Mekanism 传输协议逐条复刻的 1000t 往返仿真——先红后绿；拉侧/推侧
  单侧诊断定位到推侧；extract 记账契约最小复现）。修复：零头回收按 count 记账 +
  完整步进保护（count>leftover 跳过，不越过 accept）+ 残余 ≤63 mFE 保守丢弃
  （宁损勿造）+ `BulbItemEnergyStorage.receive` 声明改 ≥实充（ceil，同族缺陷，
  直接 EXECUTE 的调用方按返回值记账时旧口径可白拿 count−1 mFE）。全量 220
  用例全绿（211 + 4 新增 + 数值巧合）；根因与修复记录收编 living-power-tech.md
  §6 测试表（历史 BUG 注记）
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
│   ├── living-farmland-tech.md
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