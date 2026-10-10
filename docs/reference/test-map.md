# 测试文件树（每个测试类守什么）

> **性质**：测试类的**一句话意图说明**（守什么契约）。这是**叙事型**内容 ——
> `find` 能列出测试文件名，列不出「守什么」⇒ 保留在文档里。
> **机械校验**：`doc_check` 第 3 项保证本表与 `src/test` 实际文件一致（多列 / 漏列即 FAIL）
> ⇒ **新增 / 删除测试类必须同步本表**。

> 2026-10-10 从 `file-map.md` 拆出（原文件删除）：
> 其**主文件树**（`src/main`）是**清单型**内容 —— 随代码线性增长、无法人工同步
> （自证漂移：09-16 复核缺 39 个、10-10 实测缺 112+，`tools/` 整个领域包都不在树里），
> 改用 `find src/main -name "*.java"` / `python tools/gen_code_map.py --query <类名>`；
> 关键类的职责指针已下沉到各 `docs/tech/living-*-tech.md` §0「实现入口」。
> 同一份文件里主树漂 16% 而本树不漂（第 3 项守着），即「清单型退出、叙事型保留」的对照证据。

```
src/test/java/com/qiqi/li/
├── LivingItemRuntimeContainerInstancesTest.java  # 运行时同步容器实例反查·非 Container BE(ProjectE 炼金箱)返回空集合不抛异常/原版 Container BE 返回自身/无 BE 返回 null（3 项；crash-2026-10-10 回归）
├── testutil/
│   └── FakeContainerContext.java              # ContainerContext 测试替身（内存数组实现）
├── client/render/
│   ├── LivingFarmlandSeedDecoratorTest.java   # 种子图标装饰器守卫·普通/未种植/已种植/非耕地（4 项）
│   └── LivingItemTooltipTest.java             # 活物品段标题恒在（2026-10-07 定稿）·零内容行仍输出标题/不适用功能同样保留/真实活桶只有标题无内容行/有内容行则空行+标题+行/多功能标题只一次且行序保持（5 项）
├── living/api/
│   ├── ComponentOwnershipTest.java            # DataComponent 归属守卫·每组件必有主/不可重复归属/自声明会被清除（3 项）
│   ├── ActivationHookTest.java                # 活化时机钩子守卫·派发参数原样送达/顺序不变量(钩子内仍带 IS_LIVING)/未认领物品不派发/数据安全否决/箱子无玩家拒绝且内容保住/末影箱不绑定且照常解绑/owner 只由活工具钩子写（7 项）
│   ├── LivingItemActivationTest.java          # 活化门面守卫·零配置全放行/显式开启才拒绝/取消总允许/探针不改原物品（5 项）
│   ├── ActivationRuleConfigTest.java          # 活化规则 JSON 加载语义·via缺省=player/activate与deactivate独立/先命中先赢/白名单/坏值跳过（14 项，含指令侧增删改与持久化往返；tag 路径 @Disabled —— 单测环境不加载 item tags，已游戏内验证通过）
│   └── TickOrderTest.java                     # tick 顺序契约守卫·默认优先级全为0（零行为变化的证据）/稳定排序/优先级生效/冻结后不可变且非降序（4 项）
├── living/command/
│   └── ActivationTargetParsingTest.java       # 活化目标参数解析守卫·物品ID/标签/modid 回写契约 + 反向守卫「StringArgumentType 读不进 : # @」（4 项）
├── living/container/
│   ├── SimpleContainerContextTest.java        # 容器上下文脏槽同步 + 末影箱稳定键构造器（4 项回归）（29 项）
│   ├── ContainerContextsTest.java             # 边界带共享内核·ownsContainer(单箱实例/大箱CompoundContainer/防跨容器虚影/空集) + isSameSlotSpace(槽位数不一致/越界/同空/同物品/一空一非空/异物品) + resolve 末影箱分支(F-1) + isViewingEnderChest 判据 6 项（18 项）
│   ├── ContainerNeighborsTest.java            # 四邻取数·fillNeighbors 与 getNeighbors 逐格一致（含边界）/ 单行容器 / 复用缓冲不污染（3 项）
│   ├── PlayerFluidDataPersistenceTest.java    # 玩家路径落盘（CONTAINER_FLUID_DATA_PLAYER）·背包与末影箱各有源写回 / 变空条目移除 / 重进不复活 / 两容器键互不干扰 / 重进从附件回填（4 项）
│   └── ContainerChunkCacheChunkLoadTest.java  # 区块加载守卫·事件不碰世界/延后重扫不丢/限量/不主动加载/只处理ticking区/可观测性（6 项）
├── living/domain/tnt/
│   ├── ExplosionParamsTest.java               # 爆炸参数·位图映射可逆/网格外返回-1/affects=区块AABB∩球体/边界回归(中心在半径外但边缘在球内)/球体全覆盖(半径内每方块所在区块必命中)/网格规模（7 项）
│   ├── ExplosionLedgerTest.java               # 待炸账本·相交才进队列/不相交立即标记/未加载丢弃不轮询/自然加载补炸闭环/分帧预算/多场叠加不覆盖新登记/满额降级/存档往返（10 项）
│   └── ExplosionComponentLightTest.java       # 爆炸后光照刷新·四入口顺序/天光柱高图先于重算/section 空态/sectionY≠索引/发光方块减光（5 项）
├── living/domain/redstone/
│   ├── ContainerRedstoneDataTest.java         # 红石信号传播引擎·直接驱动 calculate（29 项）
│   └── ContainerRedstoneIntegrationTest.java  # 红石**驱动链路**端到端（走真实 processContext）·自维持注册/守卫放行消费者/守卫拦截无关容器(零开销)/残留归零/prio 顺序（5 项）
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
│   ├── CrossContainerTransferFertilizeTest.java # 跨容器施肥·推送/拉取双入口+活骨粉三入口全拒（15 项）
│   └── CrossContainerTransferFaceSelectionTest.java # 多方块容器跨容器面选取·候选排序+推送首选/回退（5 项）
├── living/domain/map/
│   └── MapCoordHelperTest.java                # 地图坐标换算（29 项）
├── living/domain/water/
│   ├── ContainerFluidDataTest.java            # 流体引擎行为快照·单源扩散/上限7/活物阻挡/非活物穿过/源移除/水流推动/二维扩散 + 行为接缝·按maxLevel/静止不扩散 + TickContext 建流体数据回归 + 通用驱动·自维持/驱动BFS + 源查询API + 派生源·独立存活/挤没/共存/同格无豁免/异种覆盖/EMPTY noop + 落盘CODEC往返 + 晋升接缝 + 转化接缝（26 项）
│   ├── ContainerFluidHandlerTest.java          # 管道抽取能力（§10）·tank 枚举与稳定序 / SIMULATE 不消耗 vs EXECUTE 删源 / 源全有或全无 / 异种 EMPTY / fill 恒 0 + isFluidValid / 非容器无害 / provider 四段让位（6 项）
│   ├── ContainerFluidIntegrationTest.java     # 流体端到端（走真实 processContext）·预置派生源+驱动跑BFS / BE 写回 EMPTY / 驱动进自维持清单（3 项）
│   ├── FluidTransformTableTest.java           # 流体转化表 JSON 语义·内置装载/玩家差异(覆盖/removed)/坏文件跳过/坏条目跳过/缩容等待/活物品过滤（6 项）
│   ├── FluidFlowClientCacheTest.java           # 客户端快照分桶不变量（2026-10-06 第 ③ 次泄漏修复）·ENDER/BLOCK 目标路由 / PLAYER_INV 不翻转非背包组提示 / clear() 三桶全空并复位（4 项）
│   ├── FluidFlowServerSyncTest.java           # 流体快照同步边沿·数据清空恰好一次清屏/从未激活不发/反复汲倒重新武装（3 项）
│   ├── LivingBucketFunctionTest.java          # 活桶零私有状态·判定(BucketItem家族通吃)/内容=原版content/形态变换 getBucket 映射/同形态零新对象（5 项）
│   ├── LivingBucketInteractSupportTest.java   # 倒桶源格替换（A 档对齐原版）·契约 default false（未覆写流体保守拒绝）/ 水⇄岩浆对称替换 + 同种不构成 / 倒桶判定四例（3 项）
│   └── ContainerFluidPerfTest.java            # 性能量测（200 容器×54 格×200 拍，打印基线 + 病态回归宽松阈值 + 落盘 build/_perf.txt）·引擎 tick / 空容器（2 项）
├── living/interaction/
│   ├── InteractionRegistryTest.java           # 两趟优先级匹配·通配遮蔽+triggerFilter 回归守卫（7 项）
│   ├── InteractionRuleConfigTest.java         # 交互规则 JSON 加载语义·内置全有效/玩家覆盖与removed/坏值跳过/未知字段忽略/version守卫/reload幂等（9 项；自带 mock handler 不依赖测试执行顺序）
│   └── TillToFarmlandCompatTest.java          # 活锄头跨模组兼容·模组锄头命中+处理器产物回归（8 项）
├── living/transfer/
│   ├── ContainerCompatibilityConfigTest.java  # 容器布局推断（14 项）
│   ├── ContainerRuleConfigTest.java           # 玩家差异持久化·覆盖内置+删除不复活+导出全量快照+社区闭环（11 项）
│   └── SlotInteractionCargoGateTest.java      # 槽位交互货物准入真值表·活骨粉不施肥（5 项）
└── network/
    ├── GuiInteractionPacketTest.java          # 目标槽判据·空槽可解析（活桶汲/倒包的回归守卫）/活物品可解析/非活物品不可解析（3 项）
    └── LivingItemSyncPacketTest.java          # 运行时同步包编解码往返（档 2 安全网）·三组字段往返/空数据/多槽混合/两种键形态/子记录哨兵/未登记 id 报错（13 项）
```
