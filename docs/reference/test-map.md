# 测试文件树（每个测试类守什么）

> **性质**：`src/test` 的**名录 + 每类一句话意图**，由
> [`tools/gen_test_map.py`](../../tools/gen_test_map.py) **从源码生成**（2026-11-18 转生成制）。
> **生成区不可手改**：增删测试类 / 改类 javadoc 后跑 `python tools/gen_test_map.py` 重生成；
> `doc_check` 第 3 项校验生成区与源码逐字一致，过期即 FAIL。
>
> **机器 / 人分工**（遵循「机器只印它真能推导的」）：
> - 名录（目录 + 全部 `.java`）100% 来自源码扫描；
> - 「守什么」那句只**搬运源码已有的类 javadoc 首句**——机器不判断对错、不浓缩 `@DisplayName`；
>   无类 javadoc 的类标 `⚠️ 源码无类注释`（这是事实，不是缺陷判断）。想看逐条契约读测试源码；
> - 用例数**不在此维护**——由 `python tools/test_inventory.py` 生成到 `build/test-inventory.md`。
>
> **为什么从手维护转为生成制**：原表格里「守什么」是对 `@DisplayName` 的人工转述，会随测试改动腐烂且无症状
> （第 3 项旧校验只比对文件名集合，**不管每行写了什么**），实测漏列过 2 个新增测试类。
> 转生成制后，名录这一层不可能出自人手，过期复述从结构上被消除；代价是描述退化为 javadoc 首句，
> 深度不足时回源码读测试（与 `gen_rosters.py` 对 `shared-state.md` 的处置同构）。

> 2026-10-10 从 `file-map.md` 拆出（原文件删除）：`src/main` 主文件树是**清单型**（无法人工同步，实测漂移 16%+），
> 改用 `find src/main -name "*.java"` / `python tools/gen_code_map.py --query <类名>`；
> 关键类职责指针已下沉到各 `docs/tech/living-*-tech.md` §0「实现入口」。
> 测试树当初以「叙事型」留下；2026-11-18 进一步把名录交给机器，只保留 javadoc 首句的搬运。

<!-- test-map:begin（本区由 tools/gen_test_map.py 从 src/test 扫描生成；测试类增删 = 改代码后重跑本脚本，勿手改本区） -->
```
src/test/java/com/qiqi/li/
├── LivingItemChunkContainerScanTest.java  # 区块容器扫描回归：战利品容器保留、真实附件重载后的发现与流体渲染恢复、重扫延后到 BE 权威（pending 窗口不误踢条目）。
├── LivingItemMenuRuntimeTest.java  # ⚠️ 源码无类注释
├── LivingItemRuntimeContainerPositionsTest.java  # 运行时遥测发包的「容器键 → 位置」反查守卫。
├── client/
│   ├── gui/
│   │   ├── ContainerInfoSessionTest.java  # ⚠️ 源码无类注释
│   │   ├── ContainerInfoTooltipTest.java  # ⚠️ 源码无类注释
│   │   ├── CreativeActivationSessionTest.java  # ⚠️ 源码无类注释
│   │   └── LivingButtonLayoutTest.java  # 活按钮位置布局的纯逻辑守卫（2026-10-12）—— 覆盖「应用位置时的屏幕夹取」与「默认位分流」。
│   ├── icon/
│   │   ├── DirectionalLivingModelTest.java  # ⚠️ 源码无类注释
│   │   ├── LivingIconSpecTest.java  # ⚠️ 源码无类注释
│   │   ├── PistonRenderStateTest.java  # ⚠️ 源码无类注释
│   │   └── WaterWheelRenderStateTest.java  # 水车图标旋转时钟回归（2026-10-29 换源：gameTime → Util.getMillis()）。
│   ├── input/
│   │   ├── AxeStripHintTest.java  # ⚠️ 源码无类注释
│   │   ├── FarmlandPlantHintTest.java  # 活耕地种植悬停提示守卫（2026-10-12）—— 光标持活种子悬停活耕地时的客户端反馈。
│   │   ├── LivingChestDepositHelperTest.java  # 活箱子「Shift+左键 存放」判定（批次 3，2026-11-01）。
│   │   └── LivingItemInputKeyMapTest.java  # 方向键码表守卫（2026-10-29）—— 方向输入主通道的「键码 → 方向字符」映射与字符归一化。
│   ├── render/
│   │   ├── BeamColorSectionsTest.java  # 光束颜色分段与原版渲染顶点的 ARGB 契约守卫。
│   │   ├── LivingBrewingStandDecoratorTest.java  # 酿造白幕装饰器的纯判据单测（2026-10-13 改为「本地动画」后）。
│   │   ├── LivingFarmlandSeedDecoratorTest.java  # 活耕地种子图标装饰器的守卫回归（2026-09-16）。
│   │   ├── LivingItemTooltipTest.java  # 活物品 tooltip 段的 标题恒在 守卫（2026-10-07）。
│   │   ├── LivingPistonOverlayTest.java  # ⚠️ 源码无类注释
│   │   ├── LivingToolModelRendererAnimTest.java  # 活工具动画时钟回归（2026-10-29 换源：gameTime → 本地连续时钟）。
│   │   ├── LivingToolModelRendererSplitTest.java  # 攻击环分流的唯一判据（splitByAction）—— 三条环路径 （本机 renderAttackRing / 远程玩家 / 实体宿主）共用同一份。
│   │   ├── ProgressBarDecoratorTest.java  # 通用进度条的纯判据单测。
│   │   └── tlm/
│   │       ├── SoulSlabAmbientScheduleTest.java  # ⚠️ 源码无类注释
│   │       ├── SoulSlabBooleanFeedbackTest.java  # ⚠️ 源码无类注释
│   │       ├── SoulSlabEnvironmentSoundTest.java  # ⚠️ 源码无类注释
│   │       ├── SoulSlabFeedFeedbackTest.java  # ⚠️ 源码无类注释
│   │       ├── SoulSlabFeedPulseTest.java  # ⚠️ 源码无类注释
│   │       ├── SoulSlabGuiModelWrapperTest.java  # 不启动渲染器的GUI模型上下文守卫。
│   │       ├── SoulSlabGuiPoseTest.java  # ⚠️ 源码无类注释
│   │       ├── SoulSlabGuiSlotsTest.java  # ⚠️ 源码无类注释
│   │       ├── SoulSlabHotbarSlotsTest.java  # ⚠️ 源码无类注释
│   │       ├── SoulSlabMaidSettingsTest.java  # ⚠️ 源码无类注释
│   │       ├── SoulSlabPreviewBubblesTest.java  # 我方图片分支判定，不执行第三方生成/绘制。
│   │       ├── SoulSlabPreviewFacingTest.java  # 只测我方展示角度；不创建第三方女仆，不代替实机渲染验收。
│   │       ├── SoulSlabPreviewMovesTest.java  # 不创建第三方实体的预览匹配守卫。
│   │       ├── SoulSlabPreviewScaleTest.java  # ⚠️ 源码无类注释
│   │       ├── SoulSlabPreviewSurfaceTest.java  # ⚠️ 源码无类注释
│   │       ├── SoulSlabPreviewVisibilityTest.java  # ⚠️ 源码无类注释
│   │       └── SoulSlabVoiceArbiterTest.java  # ⚠️ 源码无类注释
│   └── util/
│       ├── ClientMenuSlotsTest.java  # 纯映射接缝；lambda 提供已知显示槽映射，不验证客户端 Mixin 或屏幕解包。
│       └── LivingChestTabSearchTest.java  # 活箱子标签页搜索过滤的纯函数单测（LivingChestTabSearch）。
├── living/
│   ├── api/
│   │   ├── ActivationHookTest.java  # ⚠️ 源码无类注释
│   │   ├── ActivationRuleConfigTest.java  # ⚠️ 源码无类注释
│   │   ├── ComponentOwnershipTest.java  # ⚠️ 源码无类注释
│   │   ├── DroppedItemSupportTest.java  # ⚠️ 源码无类注释
│   │   ├── LivingItemActivationTest.java  # ⚠️ 源码无类注释
│   │   ├── LivingItemRegistrationScope.java  # 【辅助类·非测试】测试源注册作用域；安装与恢复只影响 Function 表，不重复注册领域副作用。
│   │   ├── LivingItemRegistrationTest.java  # ⚠️ 源码无类注释
│   │   └── TickOrderTest.java  # 功能列表顺序守卫（A1 配套，api-contract.md §1.3）。
│   ├── command/
│   │   ├── ActivationTargetParsingTest.java  # 活化指令「目标」参数的解析守卫。
│   │   └── LivingItemContainerCommandTest.java  # ⚠️ 源码无类注释
│   ├── compat/
│   │   ├── avaritia/
│   │   │   └── AvaritiaMenuResolverTest.java  # AvaritiaMenuResolver 的判据守卫（`D-container-09`）。
│   │   ├── create/
│   │   │   └── StressStateMachineTest.java  # ⚠️ 源码无类注释
│   │   ├── iris/
│   │   │   └── ModIrisTest.java  # Iris 光影兼容门面守卫（2026-10-30）—— 无 Iris 环境的降级路径。
│   │   ├── jade/
│   │   │   └── LivingItemJadePluginTest.java  # Jade 请求与公共能力存在性分离；存量数据必须继续由默认服务端扩展提供。
│   │   ├── sophisticated/
│   │   │   ├── MixinInjectionSignatureCheckTest.java  # ⚠️ 源码无类注释
│   │   │   ├── SophisticatedBackpackSourceTest.java  # 精妙背包来源的守卫（2026-10-23）—— 重点是 SophisticatedBackpackSource#handleOfMenu 的 菜单锚定 覆写，以及它的 降级阶梯 。
│   │   │   ├── SophisticatedMenuResolverTest.java  # 精妙背包联动解析器的守卫（`D-container-09`）。
│   │   │   └── SophisticatedMixinPluginTest.java  # ⚠️ 源码无类注释
│   │   └── tlm/
│   │       ├── IdentityRedemptionQueueTest.java  # ⚠️ 源码无类注释
│   │       └── PendingMaidFavorTest.java  # ⚠️ 源码无类注释
│   ├── config/
│   │   ├── GameplayConfigTest.java  # 宿主位置开关的默认值与加载/热重载接线守卫。
│   │   └── TlmClientConfigTest.java  # ⚠️ 源码无类注释
│   ├── container/
│   │   ├── ContainerAnchorTest.java  # 容器世界锚点的守卫（2026-10-23）。
│   │   ├── ContainerBoundaryContractTest.java  # 容器边界守卫测试 —— 把 container-boundary.md 的 I-C3 / I-C4 / I-C7 / I-C11 钉成可执行断言。
│   │   ├── ContainerCacheSweepTest.java  # R-06 缓存生命周期回收（2026-11-01）—— 守「容器不再被 tick 看到 ⇒ 数据要被收」这条不变量。
│   │   ├── ContainerChunkCacheChunkLoadTest.java  # 区块加载死锁回归守卫（2026-09-18）+ 重扫 BE 权威门（2026-11-21，未 ticking 不判定、升 ticking 补发现）。
│   │   ├── ContainerComponentCommitTest.java  # 原数量组件提交的接缝契约：旧值重验、独立快照、精确写入、恢复与最终状态通知。
│   │   ├── ContainerContentSignatureTest.java  # Q2 回归：内容签名必须检出「同 ID 同数量、组件不同」的原版途径变化。
│   │   ├── ContainerContextsTest.java  # ContainerContexts 边界带共享内核的单元测试（Q6 批次 B，2026-10-04）。
│   │   ├── ContainerDataKeyTest.java  # 容器级数据 key 的落盘判据 + ContainerContext#persist 的默认路径 （2026-10-22，`D-container-13`）。
│   │   ├── ContainerDataSavedDataTest.java  # ContainerDataSavedData 的守卫（2026-10-22，活背包地基）。
│   │   ├── ContainerDiagnoseTest.java  # 单容器只读体检（/livingitem debug container）回归 —— 守「诊断不得改变被诊断的状态」与「报告覆盖管道各层状态」两条不变量。
│   │   ├── ContainerHandoffTest.java  # 容器级数据「第三方交接」通道的守卫（2026-10-21）。
│   │   ├── ContainerIdleSuffixTest.java  # 静息快路径（Q4a）—— 守「稳定无活物品、无持续流体工作的容器在收口拍后跳过后缀」这条不变量。
│   │   ├── ContainerKeysTest.java  # 容器键解析唯一入口的守卫（2026-10-13）。
│   │   ├── ContainerLayoutResolverTest.java  # ⚠️ 源码无类注释
│   │   ├── ContainerMenuInfosTest.java  # ⚠️ 源码无类注释
│   │   ├── ContainerMoveStackTest.java  # 一步整栈移动（I-C12）与安全撤销。
│   │   ├── ContainerNeighborsTest.java  # 四邻取数的守卫（2026-10-07 性能收尾）—— 新增 ContainerContext#fillNeighbors（写入调用方缓冲，避免内层循环每格分配） 后，必须保证它与原 getNeighb…
│   │   ├── ContainerPersistRoutingTest.java  # 容器级数据的 持久层二分 （2026-10-29 口径）。
│   │   ├── ContainerPipelinePerfTest.java  # ⚠️ 源码无类注释
│   │   ├── ContainerRuntimeCacheLifecycleTest.java  # 运行时缓存生命周期（方案 A，2026-10-11）单测 —— 槽位追踪与作废登记的判定。
│   │   ├── ContainerSlotOpsTest.java  # 槽位数量操作原语（move / produce / consume / transform） 的守恒与违约守卫 —— TODO「物品传输」1-②③ 的判据面。
│   │   ├── ContainerTickHooksTest.java  # ContainerTickHooks 的接缝测试。
│   │   ├── ContainerTickIsolationTest.java  # ⚠️ 源码无类注释
│   │   ├── ContainerUuidsDiscardTest.java  # 取消活化的彻底清理（2026-10-28 修）。
│   │   ├── ContainerUuidsTest.java  # ContainerUuids 的守卫（2026-10-22，活背包地基）。
│   │   ├── ContainerViewersTest.java  # 「谁正在看这个容器」的 唯一 判定入口的守卫（2026-10-23）。
│   │   ├── EnderChestContextTest.java  # 末影箱锚点口径（2026-10-27 拍板：存放处）。
│   │   ├── EntityContainerProviderTest.java  # 我方 Provider、范围写与混合槽契约；不模拟第三方实体行为。
│   │   ├── EntityContainersTest.java  # 实体容器（船 / 矿车 / 带箱驴骡）的上下文与菜单解析守卫（2026-10-21）。
│   │   ├── ItemFormContainerContextTest.java  # 物品形态容器的两块地基（2026-10-22，活背包）： ItemFormContainers（来源注册口）与 ItemFormContainerContext（ctx 的 SavedData 覆写）…
│   │   ├── ItemFormLiveViewsTest.java  # 物品形态容器的「活视图」登记表（2026-10-28）。
│   │   ├── ItemFormSlotSyncTest.java  # 物品形态容器（活背包）的槽位直发通道（2026-10-31，活水车转向不刷新的根因修复）。
│   │   ├── MenuSlotOwnersTest.java  # 「菜单槽 → 容器位置」解析守卫（契约 I-C5 / 专题 S3 / `D-container-09`）。
│   │   ├── PlayerFluidDataPersistenceTest.java  # 玩家路径的流体落盘守卫（2026-10-07）—— 覆盖 CONTAINER_FLUID_DATA_PLAYER（ Map&lt;容器键, 数据&gt; ）的 「有源写回 / 变空移除 / 重进不复活…
│   │   └── SimpleContainerContextTest.java  # SimpleContainerContext 的单元测试。
│   ├── domain/
│   │   ├── backpack/
│   │   │   ├── LivingBackpackFunctionTest.java  # LivingBackpackFunction 的守卫（2026-10-22）。
│   │   │   ├── LivingBackpackTickTest.java  # ⚠️ 源码无类注释
│   │   │   └── LivingBackpackTooltipTest.java  # 活背包 tooltip 的 容器 ID 行 （2026-10-26，用户定）。
│   │   ├── beacon/
│   │   │   ├── BeaconChestHostTest.java  # BeaconChestHost 测试 —— 2026-10-18 「同容器多枚信标每枚一条光束」的回归守卫。
│   │   │   ├── BeaconColumnSlotsTest.java  # 下方 9 列槽位解析（纯槽位数学）测试 —— 与 BeaconPyramidTest（柱高判定）互补。
│   │   │   ├── BeaconConfigTest.java  # BeaconConfig 组件读写与编解码测试。
│   │   │   ├── BeaconEffectsTest.java  # BeaconEffects 纯逻辑测试（设计稿 §2.5）。
│   │   │   ├── BeaconEntityHostSyncTest.java  # BeaconEntityHostSync 维度隔离 / 半径 / 去重回归（2026-11-12，D-container-25）。
│   │   │   ├── BeaconFeedHandlerTest.java  # 喂锭 handler 的可测部分：规则谓词 + 矿石映射 + 列宽来源 。
│   │   │   ├── BeaconFunctionTest.java  # 宿主判据（协议级 LidBlockEntity）测试 —— 2026-10-18 从「类型白名单」重构为 「协议判据」后的覆盖矩阵守卫。
│   │   │   ├── BeaconGlassColorTest.java  # 容器玻璃取色与同列扫描测试（设计稿 §2.2 U3-A 容器侧延伸）。
│   │   │   ├── BeaconHostDispatchTest.java  # 信标宿主分派与取点 同源 （2026-10-25，TODO 6.2）。
│   │   │   ├── BeaconHostPacketTest.java  # BeaconHostPacket 编解码往返测试（S2C：箱子宿主位置 + 等级 + 容器玻璃色）。
│   │   │   ├── BeaconPanelGeometryTest.java  # 9 柱小金字塔面板几何纯函数测试（设计稿 §2.6.2）——「像素对不上」类 bug 在此层拦掉， 不靠启动游戏截图。
│   │   │   ├── BeaconPlayerHostPacketTest.java  # BeaconPlayerHostPacket 编解码往返测试（S2C：玩家宿主 UUID + 等级 + 容器玻璃色）。
│   │   │   ├── BeaconPlayerHostSyncTest.java  # BeaconPlayerHostSync 跨维度回归（2026-10-18 审查发现的阻断 bug）。
│   │   │   ├── BeaconPyramidTest.java  # BeaconPyramid 纯逻辑测试（设计稿 §2.4：1D 金字塔判定）。
│   │   │   ├── BeaconSegmentTest.java  # BeaconSegment 编解码与相等性测试。
│   │   │   ├── BeaconTooltipComponentTest.java  # BeaconTooltipComponent 构建测试（9 柱面板的数据载体）。
│   │   │   └── BeaconTooltipTest.java  # 活信标 tooltip 文本层守卫（2026-11-08 用户定：条目三态配色 + 满级再生改「账本末尾授予行」）。
│   │   ├── brewing/
│   │   │   ├── BrewingBatchLogicTest.java  # 活酿造台批次状态机的纯逻辑测试。
│   │   │   ├── BrewingMaterialConsumeTest.java  # 材料消耗（consumeOne）守卫 —— 2026-10-13 用户实测 bug 的回归。
│   │   │   ├── BrewingSlotEligibilityTest.java  # 活酿造台的槽位准入（隔离规则）单测。
│   │   │   └── LivingBrewingStandDataTest.java  # 活酿造台数据组件的 codec 守卫（2026-10-12 反馈层新增 brewStartTick）。
│   │   ├── chest/
│   │   │   ├── LivingChestAccessorTest.java  # 活箱子 LivingChestAccessor 的接缝测试。
│   │   │   ├── LivingChestFunctionTest.java  # 取消活化返还清单的堆叠倍数语义测试。
│   │   │   ├── LivingChestInsertByteGateTest.java  # 活箱子字节上限的 插入点精确把关 (2026-10-27 审查 P1；2026-10-29 收口估算路径)。
│   │   │   └── LivingChestItemHandlerTest.java  # 活箱子 对外能力面 （LivingChestItemHandler）的接缝测试。
│   │   ├── ender/
│   │   │   ├── EnderChannelRegistryDimTest.java  # 活末影箱路由表的维度隔离回归（2026-10-27 审查待拍板 #2，2026-10-28 修）。
│   │   │   ├── EnderDualFormRouteTest.java  # 活末影箱双形态往返的不变量测试（2026-10-29 刷物品根因的补强）。
│   │   │   ├── EnderRouteExtractTest.java  # 活末影箱路由的端到端提取（注册 → 提取 → 消耗）。
│   │   │   ├── EnderRouteMaintenanceTest.java  # 路由回收的注册方自查（EnderRouteMaintenance → EnderChannelRegistry.validateRoutes 按 RouteRegistrar 能力发现）：
│   │   │   ├── EnderRouteManagerNeighborUuidTest.java  # 邻居路由的源身份（R-07）。
│   │   │   ├── EnderRouteManagerSourceTest.java  # 路由条目的「源是否可寻址」判据（2026-10-28）。
│   │   │   └── LivingEnderChestAccessorTest.java  # LivingEnderChestAccessor 的接缝测试（直连模式为主）。
│   │   ├── farmland/
│   │   │   ├── BonemealHandlerTest.java  # 活骨粉 GUI 交互的 服务端信任边界 （2026-10-27 审查 P3）。
│   │   │   ├── CropClassifierTest.java  # 作物分类器回归守卫 —— 第八轮实测踩坑（2026-09-14）双根因：
│   │   │   ├── FertilizeTransferTest.java  # tryFertilize 自动施肥回归守卫 —— 活漏斗把骨粉推向活耕地槽位的传输特殊分支 （2026-09-15 新增）。
│   │   │   ├── LivingFarmlandFunctionTest.java  # 活耕地功能测试 —— 输出合并回归守卫 + 堆叠判定守卫。
│   │   │   ├── LivingFarmlandMoistureTest.java  # 活耕地湿润 BFS 与生长边沿门（2026-10-28 补，审查测试缺口 #4）。
│   │   │   ├── LivingFarmlandPlacementTest.java  # 活耕地「放置回世界」回归守卫（2026-09-16）。
│   │   │   └── TillablesTest.java  # Tillables 回归守卫 —— 锄头判定（只认 HOE_TILL）+ 可耕映射（对齐原版 IBlockExtension#getToolModifiedState 的 HOE_TILL 分支）。
│   │   ├── furnace/
│   │   │   ├── FurnaceBurningFlagTest.java  # 燃烧标志组件回归测试（图标 active/idle 切换数据源）。
│   │   │   ├── FurnaceOutputGateTest.java  # 熔炉输出槽判据与配方缓存回归（2026-10-27 审查 P1-2 / P2-3）。
│   │   │   └── LivingFurnaceFunctionTest.java  # 燃料消耗的合成残留物语义测试（对齐原版 AbstractFurnaceBlockEntity#serverTick）。
│   │   ├── hopper/
│   │   │   ├── CrossContainerTransferContainerLikeTest.java  # 容器类活物品走通用跨容器路径的回归（2026-11-01 删除 pullFromNeighborToLivingChest 特支后）： 拉取到容器内活箱子：与通用路径逐行等价（同为 SlotAcces…
│   │   │   ├── CrossContainerTransferFaceSelectionTest.java  # 多方块容器「跨容器传输的面选取」回归测试（living-hopper-tech.md §6.4）。
│   │   │   ├── CrossContainerTransferFertilizeTest.java  # 槽位交互分发的回归守卫 —— 两个入口各覆盖一遍： SlotInteractions#tryInteractFromNeighbor（ 拉取方向 ，货物在邻居容器） SlotInteractions#…
│   │   │   ├── HopperFilterSyncTest.java  # 漏斗过滤链回写回归测试（黑白名单 tooltip 展示数据源）。
│   │   │   └── HopperMechanicalReceiverTest.java  # ⚠️ 源码无类注释
│   │   ├── maid/
│   │   │   ├── LivingSoulSlabClockTest.java  # ⚠️ 源码无类注释
│   │   │   ├── LivingSoulSlabFunctionTest.java  # ⚠️ 源码无类注释
│   │   │   ├── LivingSoulSlabSittingTest.java  # ⚠️ 源码无类注释
│   │   │   ├── LivingSoulSlabTooltipTest.java  # ⚠️ 源码无类注释
│   │   │   └── SoulSlabSitHandlerTest.java  # ⚠️ 源码无类注释
│   │   ├── map/
│   │   │   ├── MapCoordHelperMatchTest.java  # 组件装饰条目与命中装饰的 同一处匹配 （2026-10-27 审查 P2-2）。
│   │   │   ├── MapCoordHelperTest.java  # MapCoordHelper 坐标换算测试。
│   │   │   ├── MapUpdateSkipHelperTest.java  # 地图更新节流器的 跨存档 不变量（2026-11-01）。
│   │   │   └── TeleportHelperYTest.java  # 传送落点 Y 的 站立面语义 （2026-10-27 审查 P1-1，v50 简化回归）。
│   │   ├── piston/
│   │   │   ├── LivingPistonDataTest.java  # ⚠️ 源码无类注释
│   │   │   ├── LivingPistonFunctionTest.java  # ⚠️ 源码无类注释
│   │   │   ├── LivingPistonIntegrationTest.java  # ⚠️ 源码无类注释
│   │   │   ├── LivingPistonTooltipTest.java  # ⚠️ 源码无类注释
│   │   │   ├── PistonActionsTest.java  # ⚠️ 源码无类注释
│   │   │   ├── PistonChainResolverTest.java  # ⚠️ 源码无类注释
│   │   │   ├── PistonControllerTest.java  # ⚠️ 源码无类注释
│   │   │   └── PistonSegmentTest.java  # ⚠️ 源码无类注释
│   │   ├── portal/
│   │   │   ├── ContainerPortalDataTest.java  # 不可变区域、快照和门域持久编码守卫。
│   │   │   ├── LivingPortalFunctionTest.java  # 门主控的整区熄灭、流体两层存在性与持久清理后不复燃守卫。
│   │   │   ├── PortalClientCacheTest.java  # 门面快照的菜单隔离、逻辑映射和不可变包载荷守卫。
│   │   │   ├── PortalDataAccessTest.java  # 门域热表、Player/UUID/附件回填与仅清自身 key 守卫。
│   │   │   ├── PortalDataMigrationTest.java  # 保留内容的门域交接、销毁与旧热数据替换守卫。
│   │   │   ├── PortalInteractHandlersTest.java  # 真实门交互入口、整区授权、光标耐久与槽内货物守卫；世界传送和网络发送隔离。
│   │   │   ├── PortalItemTransferTest.java  # 槽到源世界的真实交接与受限恢复；不声称覆盖双世界实体生命周期。
│   │   │   ├── PortalPlayerTeleportTest.java  # 玩家过门的公开出口计算、前置权限与一次真实旅行事件取消守卫。
│   │   │   ├── PortalPositionResolverTest.java  # 来源锚点的自身优先、借位门控与真实世界/坐标成对约束。
│   │   │   ├── PortalServerSyncTest.java  # 门快照持续发送、真实菜单映射与空快照清理守卫。
│   │   │   └── PortalStructureTest.java  # 四邻围闭、固定区域及门面内容准入守卫。
│   │   ├── power/
│   │   │   ├── AccountingGateTest.java  # 记账跳变门控测试（v19）。
│   │   │   ├── BulbItemEnergyStorageTest.java  # 铜灯通用电池测试（§3.6 v17.5：双向 + 无出身论）。
│   │   │   ├── ChannelStateTest.java  # ChannelState 相位域 / 偏移活性测试。
│   │   │   ├── CoilGroupingTest.java  # 线圈形态检测测试（v3 —— 相位事件总线）。
│   │   │   ├── ContainerEnergyCapabilityTest.java  # 对外能量能力（Capabilities.EnergyStorage.BLOCK）的 存在性 守卫 （§3.6 对外接口，2026-10-31 对齐流体 §10.4）。
│   │   │   ├── ContainerPowerDataTest.java  # 红电发电状态机测试（v3 —— 相位事件总线）。
│   │   │   ├── NetworkResonanceTest.java  # 网络级共振测试（§3.7）——不同锈蚀级之间的「和声」。
│   │   │   ├── NetworkTraversalTest.java  # 电力层「按网络组件遍历」重构回归测试（v3 重构：逐发电机 BFS → 按组件 BFS + copyFrom）。
│   │   │   ├── PhaseDataMigrationChestSplitTest.java  # A6（2026-10-27 定案）：双箱拆半 ⇒ 红电相位不分摊不复制、按新布局重新识别信号。
│   │   │   ├── PhaseDataMigrationTest.java  # PhaseDataMigration 的守卫（2026-10-21 补 —— 此前该文件零测试，而它的搬运逻辑 比流体更复杂：快照要 PhaseSnapshot#restoreInto 还原进账本，且…
│   │   │   ├── PhaseInterpretationTest.java  # 相位解读三元件测试（v19）：雕文移相器 / 切制裂相器 / 格栅相位加法器。
│   │   │   ├── PhaseSnapshotWarmupTest.java  # 账本重生的相位连续性测试（2026-09-09 相位快照落盘 + 首拍无沿宽限）。
│   │   │   ├── PowerMathTest.java  # 红电数学工具测试 —— 公式 v3：Σ√|Δ_i|，合因子 = (eff_δ_sum)^(1+u)。
│   │   │   ├── RoundTripConservationIT.java  # 往返守恒集成测试（2026-09-09）：容器(灯A) → 电缆 → 容器(灯B) 原样往返， 断言总能量不增（可微降——取整损耗方向）。
│   │   │   ├── SignalTrackerRestartTest.java  # A5（2026-10-27 定案）：振荡信号停机后恢复，不把停机时长当周期，不补发停机期间的电。
│   │   │   ├── WaxedCopperCouplingIT.java  # 阶段三集成测试（v3 —— 相位事件总线）：多路振荡器 → 多发电机域独立计算。
│   │   │   ├── WaxedCopperOscillatorIT.java  # 阶段二集成测试（v3 —— 相位事件总线）：单振荡器 → 发电机锁相 → RE 记账。
│   │   │   ├── WaxedCopperStorageTest.java  # 阶段四储能测试 —— 铜灯 = 唯一储存（无容器池）+ 对外取电（模组兼容面）。
│   │   │   └── WaxedGeneratorFeedChainTest.java  # 涂蜡发电机「喂电链」分段回归：振荡信号从信号源出发，经过中间红石元件， 每一跳都要能到达涂蜡槽的入边并驱动发电。
│   │   ├── redstone/
│   │   │   ├── ContainerRedstoneBoundaryTest.java  # 边界外部输入采样的 自反馈守卫 （2026-10-27 审查 P1）。
│   │   │   ├── ContainerRedstoneDataTest.java  # ContainerRedstoneData 信号传播测试。
│   │   │   ├── ContainerRedstoneIntegrationTest.java  # 红石驱动链路的端到端测试 —— 走真实 ContainerLivingItemHandler.processContext。
│   │   │   ├── LivingObserverTest.java  # 活侦测器最终玩法：正面相邻槽语义快照 + 结算后采样 + 原版忙碌窗口。
│   │   │   └── RedstoneHandlerSetChangedTest.java  # 红石交互 handler 的 就地改写标脏 （2026-10-27 审查 P2，与 IgniteHandler P3-4 同类）： LivingXxxData.set 是就地组件写，NBT 背书 ha…
│   │   ├── sponge/
│   │   │   ├── LivingSpongeAbsorbTest.java  # 活海绵吸收（第二段）——四邻直吸 + 逐源原子的行为契约 （设计稿 活海绵 §2.2 / §9.4 规则 1~5、8、10、11）。
│   │   │   ├── LivingSpongeDataTest.java  # LivingSpongeData 的干/湿不变量与落盘 CODEC（设计稿 活海绵 §2.1）。
│   │   │   ├── LivingSpongeTooltipTest.java  # 活海绵 tooltip 的「堆存量 / 堆容量」口径守卫（2026-11-06）。
│   │   │   ├── SpongeContainerCapabilityTest.java  # 「海绵垫起的」容器级流体能力（⑭）—— 存在性判据 / 罐映射 / 跨堆抽灌 / 边沿精确失效 （设计稿 §2.3；§10.4 教训：能力非 null = 管道判储液的唯一依据）。
│   │   │   └── SpongeFluidRoundTripTest.java  # 活海绵 ⇄ 模组流体世界的 守恒主战场 （设计稿 §8.3，规则 12 红线）： 充入 → 排出往返（部分充 / 部分排 / 溢出拒收 / 空排 / 异种拒绝 / 零负请求）， 每步断言 净迁移无凭空…
│   │   ├── tnt/
│   │   │   ├── ExplosionBlockEntityCleanupTest.java  # 批量爆炸清除 live 与 packed 方块实体数据，且不得为清理触发实例化。
│   │   │   ├── ExplosionComponentCountTest.java  # 活 TNT 计数/清空的 守恒 （2026-10-27 审查 P2-1，TODO 挂账 S1 的回归）。
│   │   │   ├── ExplosionComponentDamageTest.java  # 即时实体伤害只遍历当前已加载实体，并在 float 边界保持有限。
│   │   │   ├── ExplosionComponentHeightmapTest.java  # 批量破坏的真实高度图守卫；高度查询、packed 保存与发包数据必须一致。
│   │   │   ├── ExplosionComponentLightTest.java  # 爆炸后光照刷新守卫（2026-09-22）。
│   │   │   ├── ExplosionLedgerTest.java  # 待炸账本守卫（2026-09-18）—— 「未观测的地形变更推迟到观测时」（原版 TNT 引信模型）。
│   │   │   ├── ExplosionParamsTest.java  # ExplosionParams 的几何与位图映射守卫（2026-09-18）。
│   │   │   ├── ExplosionPoiCleanupTest.java  # HIGH/SUPER raw section 写必须复用原版 POI 状态变更入口。
│   │   │   ├── ExplosionSuperLedgerTest.java  # SUPER 参数账本、区块纪元水印与仅已加载降级队列的守卫。
│   │   │   ├── IgniteCarriedHandlerTest.java  # 光标持活 TNT → 右键槽内活打火石（IgniteCarriedHandler，此前零测试）。
│   │   │   ├── LivingTntConsumeTest.java  # 爆炸后必须消耗活 TNT（2026-10-23 真机实录）。
│   │   │   ├── TntIgniteHandlerTest.java  # 活打火石 → 活 TNT 的点燃交互（IgniteHandler，此前零测试）。
│   │   │   └── TntRedstoneIgniteTest.java  # 红石信号 → 活 TNT 点燃（LivingTntFunction 的「未点燃 + 信号 > 0」分支，此前零测试）。
│   │   ├── tools/
│   │   │   ├── AxeStripHandlerTest.java  # ⚠️ 源码无类注释
│   │   │   ├── CarriedLivingItemsTest.java  # 「随身携带」判据的守卫（2026-10-27）。
│   │   │   ├── LivingToolAssistTest.java  # 辅助挖掘的 速度分层 （2026-10-27 用户定）：物品栏顶层相加 + 背包里只取最高。
│   │   │   ├── LivingToolHostBlockTest.java  # 活工具「别挖自己容器」黑名单与射线起点 同源 （2026-10-25，TODO 6.2）。
│   │   │   ├── LivingToolHostSyncDedupTest.java  # K2 去重键的 口径 ：只比「渲染真正依赖的字段」。
│   │   │   ├── LivingToolHostSyncDimensionTest.java  # K2 跨维度回归（2026-10-27 审查发现的阻断 bug）。
│   │   │   ├── LivingToolMemoryTest.java  # LivingToolMemory 的接缝测试。
│   │   │   ├── LivingToolReplaySweepTest.java  # 假玩家出手「不做横扫」守卫（LivingToolReplay#onSweepAttack）。
│   │   │   └── LivingWeaponAssistTest.java  # 活武器辅助攻击的 实体宿主触发判据 （2026-11-12，D-container-25）： 准入实体容器（船 / 矿车 / 生物背包）的近战命中触发补刀；玩家与 FakePlayer 走另一条路，…
│   │   └── water/
│   │       ├── BucketInteractRealMenuTest.java  # 活桶倒/汲的真实原版菜单路径（2026-10-28 补，审查测试缺口 #6）。
│   │       ├── ContainerDataMigrationChestSplitTest.java  # A6（2026-10-27 定案）：双箱拆半 ⇒ 物品照原版分家，容器级状态不分摊不复制、重新开始。
│   │       ├── ContainerDataMigrationTest.java  # ContainerDataMigration 的纯逻辑守卫（2026-10-19）。
│   │       ├── ContainerFluidConservationTest.java  # 流体搬运的 物品守恒 回归测试（2026-11-01）。
│   │       ├── ContainerFluidDataTest.java  # ContainerFluidData 行为快照（golden master，2026-10-03）。
│   │       ├── ContainerFluidIntegrationTest.java  # 容器流体端到端测试 —— 走真实 ContainerLivingItemHandler.processContext。
│   │       ├── ContainerFluidPerfTest.java  # ⚠️ 源码无类注释
│   │       ├── ContainerStressDataTest.java  # ContainerStressData#wheelTorque 的行为（2026-10-31 抽出纯函数后补测）。
│   │       ├── ContainerStressWritebackTest.java  # 活水车「无 BE、无玩家背包」时的注入锚点双路径（2026-11-12 实体宿主行为兼容）： 实体容器用自己的位置 —— 不设门（与方块容器同判）；物品形态借宿主锚点 —— host_anchor.w…
│   │       ├── FluidFlowClientCacheTest.java  # 客户端流体快照 分桶与目标提示 的不变量（2026-10-06，第 ③ 次泄漏修复配套）。
│   │       ├── FluidFlowServerSyncTest.java  # 流体快照的清空边沿、真实单箱每拍发包与关闭/重开生命周期守卫。
│   │       ├── FluidSourcesSnapshotTest.java  # FluidSourcesSnapshot（Q6）的守卫：物品侧流体组件值的不可变语义。
│   │       ├── FluidTransformTableTest.java  # 流体转化表 JSON 加载语义守卫（F4，2026-10-03）—— 三层来源 / 玩家覆盖与删除 / 坏值跳过 / 未知字段不崩 / 转化口径（等量替换 + 缩容等待 + 非活过滤）。
│   │       ├── LivingBucketFunctionTest.java  # 活桶（2026-10-04 逻辑纠偏定稿）—— 桶只是载体，零私有状态 。
│   │       └── LivingBucketInteractSupportTest.java  # 倒桶时的 源格替换 判定（2026-10-06 A 档对齐原版）—— LivingBucketInteractSupport#replacesResidentSource。
│   ├── interaction/
│   │   ├── AxeStripInteractionTest.java  # 活斧去皮的规则匹配边界：能力工具可命中，不合条件的点击必须交回原版。
│   │   ├── InteractionActionTest.java  # 动作授权范围；权限查询不执行处理器、不修改光标或真实存储。
│   │   ├── InteractionRegistryTest.java  # 交互注册表匹配测试 —— 两趟优先级（精确触发器 > 通配自交互）回归守卫。
│   │   ├── InteractionRuleConfigTest.java  # 交互规则 JSON 加载语义守卫（D2）—— 三层来源 / 玩家覆盖与删除 / 坏值跳过 / 未知字段不崩。
│   │   ├── MenuActionAccessTest.java  # 菜单动作访问边界；真实物品存取结果不替代状态授权。
│   │   └── TillToFarmlandCompatTest.java  # 活锄头跨模组兼容回归守卫 —— 规则层（客户端拦截）+ 处理器层（服务端写入）两层。
│   ├── perf/
│   │   └── PerfMetricsTntTest.java  # ⚠️ 源码无类注释
│   ├── runtime/
│   │   └── ContainerRuntimeCacheSlotMappingTest.java  # 运行时缓存的 读写槽位键对称性 （2026-10-27 审查 P1，熔炉/酿造台/漏斗域）。
│   ├── transfer/
│   │   ├── ContainerCompatibilityConfigTest.java  # ContainerCompatibilityConfig 布局推断测试。
│   │   ├── ContainerLayoutLearnerTest.java  # 容器布局自动学的守卫（2026-10-25，Tier 1.5）。
│   │   ├── ContainerRuleConfigTest.java  # ContainerRuleConfig 玩家差异持久化测试。
│   │   ├── FilteredSlotAccessorTest.java  # 共享过滤判定与槽位访问器回归。
│   │   ├── MechanicalReceiversTest.java  # ⚠️ 源码无类注释
│   │   ├── NeighborSlotAccessorTest.java  # NeighborSlotAccessor 的接缝测试。
│   │   ├── PlainSlotAccessorTest.java  # PlainSlotAccessor 的回滚契约：只回原槽；装不下/被占就报告，不找其它槽、不落地。
│   │   ├── SlotAccessorTransferConservationTest.java  # SlotAccessor#transfer 的守恒不变量测试。
│   │   ├── SlotInteractionCargoGateTest.java  # 货物准入规则回归守卫（2026-09-15 用户定案：漏斗只认普通骨粉）。
│   │   └── TransferTargetPolicyDispatchTest.java  # TransferTargetPolicies 分发语义（纯桩策略，不涉及具体领域）： 目标无策略接管 → 源侧探测触发 + CONTINUE（回落通用传输） 目标被策略 REJECT → 探测不触发（…
│   └── util/
│       ├── BroadcastDedupTest.java  # 定向广播去重表的跨维度合并不变量（2026-10-23 定，2026-10-26 抽成共享工具）。
│       ├── FailureThrottleTest.java  # ⚠️ 源码无类注释
│       └── PerItemLedgerTest.java  # PerItemLedger 均分记账内核 —— 灯泡 / 海绵两域共享的算术契约 （设计稿 活海绵 §3.1；灯泡行为不变由 RoundTripConservationIT 全量回归守）。
├── network/
│   ├── ContainerMenuInfoPacketTest.java  # ⚠️ 源码无类注释
│   ├── GuiInteractionDispatchTest.java  # 真实服务端分发与桶源账本；世界门面维护在静态边界隔离。
│   ├── GuiInteractionPacketTest.java  # ⚠️ 源码无类注释
│   ├── GuiInteractionTargetGuardTest.java  # GUI 交互包的 服务端目标身份复验 （2026-10-27 审查 P3-2）。
│   ├── LivingItemRuntimeSyncTest.java  # 运行时数据发包的守卫（2026-10-18 起；门控判定 2026-10-23 迁至 ContainerViewersTest）。
│   ├── LivingItemSyncPacketTest.java  # LivingItemSyncPacket 编解码往返测试（2026-10-08，档 2 步骤 B）。
│   ├── LivingTagPacketTest.java  # ⚠️ 源码无类注释
│   └── ServerPacketHandlerInventorySafetyTest.java  # 「满背包不丢物」守卫(2026-10-27 审查 P0)。
└── testutil/
    ├── FakeContainerContext.java  # 【辅助类·非测试】内存容器上下文 —— 单元测试用的 ContainerContext 替身。
    └── FakeHoe.java  # 【辅助类·非测试】模组锄头替身 —— 不继承 HoeItem，只声明 HOE_TILL 能力。
```
<!-- test-map:end -->
