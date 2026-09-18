# 完整文件树（快照）

> 这是快照，不是权威：项目 200+ 个文件，本表**会漂移**
> （2026-09-16 复核：主树列 180 / 实际 219，缺 39 个）。
> 需要准确清单时请用 `find src/main -name "*.java"` 或 IDE 目录视图。
> 本文件的价值是「一次性看到整体骨架」，不是逐文件核对。

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
│   │   │   ├── ExplosionData.java            #     爆炸数据（从 data/ 迁入）
│   │   │   ├── ExplosionComponent.java       #     爆炸执行引擎（逐区块破坏；2026-09-18 从 components/ 迁入）
│   │   │   ├── ExplosionParams.java          #     爆炸参数 record + 位图索引映射
│   │   │   └── ExplosionLedger.java          #     待炸账本（世界级 SavedData；未加载区块等自然加载）
│   │   │
│   │   ├── farmland/                         #   活耕地领域
│   │   │   ├── LivingFarmlandFunction.java   #     tick 功能入口（生长/产出状态机 + tooltip）
│   │   │   ├── FarmlandPlantComponent.java   #     种植数据组件（作物标记+age+round-robin 产出）
│   │   │   ├── CropClassifier.java           #     作物分类器（准入三层/maxAge/茎果实 AT/收获形态/上部件注册表）
│   │   │   ├── FarmlandBonemealInteraction.java #  槽位交互条目：普通骨粉 × 活耕地 → 施肥（注册进 SlotInteractions）
│   │   │   ├── LivingFarmlandPlacement.java  #     放置回世界：模拟玩家右键种一次（软逻辑，异常自吞）
│   │   │   └── Tillables.java                #     耕作知识表：锄头判定（HOE_TILL）+ 可耕映射
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
│   │   ├── TillToFarmlandHandler.java       #   活锄头耕活土→活耕地（查 Tillables 映射）
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

## 测试文件树（快照）

```
src/test/java/com/qiqi/li/
├── testutil/
│   └── FakeContainerContext.java              # ContainerContext 测试替身（内存数组实现）
├── client/render/
│   └── LivingFarmlandSeedDecoratorTest.java   # 种子图标装饰器守卫·普通/未种植/已种植/非耕地（4 项）
├── living/container/
│   ├── SimpleContainerContextTest.java        # 容器上下文脏槽同步（25 项）
│   └── ContainerChunkCacheChunkLoadTest.java  # 区块加载守卫·事件不碰世界/延后重扫不丢/限量/不主动加载/只处理ticking区/可观测性（6 项）
├── living/domain/tnt/
│   ├── ExplosionParamsTest.java               # 爆炸参数·位图映射可逆/网格外返回-1/affects 判据/网格规模（4 项）
│   └── ExplosionLedgerTest.java               # 待炸账本·圆外标记/未加载丢弃不轮询/自然加载补炸闭环/分帧预算/多场叠加不覆盖新登记/满额降级/存档往返（9 项）
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
│   ├── TillablesTest.java                   # 耕作知识表·锄头判定+可耕映射+真值表（8 项）
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
│   ├── InteractionRegistryTest.java           # 两趟优先级匹配·通配遮蔽+triggerFilter 回归守卫（7 项）
│   └── TillToFarmlandCompatTest.java          # 活锄头跨模组兼容·模组锄头命中+处理器产物回归（8 项）
└── living/transfer/
    ├── ContainerCompatibilityConfigTest.java  # 容器布局推断（14 项）
    └── SlotInteractionCargoGateTest.java      # 槽位交互货物准入真值表·活骨粉不施肥（5 项）
```
