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
├── LivingItemRuntimeContainerPositionsTest.java  # 运行时遥测发包「容器键→位置」反查·非 Container BE(精妙背包/ProjectE)返回位置不抛异常(I-C5 翻转)/原版 Container BE/无 BE 返回 null（3 项）+ 缓存键反查·前缀定向该维度且不越维度找补/未知维度/裸键扫描兜底/背包坏键安全跳过/dimPrefixOf（6 项） + 实体容器键→实时位置/实体不存在→空（2026-10-21）
├── testutil/
│   └── FakeContainerContext.java              # ContainerContext 测试替身（内存数组实现）
├── client/gui/
│   └── LivingButtonLayoutTest.java            # 活按钮位置布局·应用位置时的屏幕夹取（屏内不动/越界夹边缘/屏幕小于按钮取 max(0,…)）+ 默认位分流（背包=小人框底居中 42,70 / 其余容器=底部中央）+ 小人框矩形常量对齐原版 (26,8)-(75,78)（4 项）
├── client/input/
│   └── FarmlandPlantHintTest.java             # 活耕地种植悬停提示·门槛与交互同源（非活种子/非活耕地/非耕地物品/不可种植/空栈→null）+ 四态文案键与参数（充足消耗数/创造免费/不足差额/已种植）（10 项）
├── client/render/
│   ├── LivingFarmlandSeedDecoratorTest.java   # 种子图标装饰器守卫·普通/未种植/已种植/非耕地（4 项）
│   ├── LivingBrewingStandDecoratorTest.java  # 酿造白幕·守卫(原版/非活/未酿造不画)+堆叠时长折算(400/N·与 stepFor 同源)+比例时间线(clamp)+动画起点(首次记/播完不删/新批次重播/跨存档清)（8 项）
│   ├── ProgressBarDecoratorTest.java          # 通用进度条·守卫(null/比例≤0 不画)+横竖几何(黑底/彩条·原版 Math.round)+燃料刻度(1 次=1px)+越界 clamp（6 项）
│   ├── LivingItemTooltipTest.java             # 活物品段标题恒在（2026-10-07 定稿）·零内容行仍输出标题/不适用功能同样保留/真实活桶只有标题无内容行/有内容行则空行+标题+行/多功能标题只一次且行序保持（5 项）
│   └── BeamColorSectionsTest.java             # 光束颜色分段（照原版扫描算法）·无玻璃单段白/玻璃紧邻纯色/隔空气白段先长高/同色累计/异色取平均/不透明截断/自定义默认色（9 项）
├── living/api/
│   ├── ComponentOwnershipTest.java            # DataComponent 归属守卫·每组件必有主/不可重复归属/自声明会被清除/信标配置取消活化即清（4 项）
│   ├── ActivationHookTest.java                # 活化时机钩子守卫·派发参数原样送达/顺序不变量(钩子内仍带 IS_LIVING)/未认领物品不派发/数据安全否决/箱子无玩家拒绝且内容保住/末影箱不绑定且照常解绑/owner 只由活工具钩子写（7 项）
│   ├── LivingItemActivationTest.java          # 活化门面守卫·零配置全放行/显式开启才拒绝/取消总允许/探针不改原物品（5 项）
│   ├── ActivationRuleConfigTest.java          # 活化规则 JSON 加载语义·via缺省=player/activate与deactivate独立/先命中先赢/白名单/坏值跳过（14 项，含指令侧增删改与持久化往返；tag 路径 @Disabled —— 单测环境不加载 item tags，已游戏内验证通过）
│   └── TickOrderTest.java                     # tick 顺序契约守卫·默认优先级全为0（零行为变化的证据）/稳定排序/优先级生效/冻结后不可变且非降序（4 项）
├── living/command/
│   └── ActivationTargetParsingTest.java       # 活化目标参数解析守卫·物品ID/标签/modid 回写契约 + 反向守卫「StringArgumentType 读不进 : # @」（4 项）
├── living/container/
│   ├── SimpleContainerContextTest.java        # 容器上下文脏槽同步 + 末影箱稳定键构造器（4 项回归）+ 缓存键推导（世界容器带维度前缀/跨维度同坐标键不同/大箱子两段坐标/背包末影箱裸键/无维度退化）+ 列数推断（启发式/质数退化/未注册类型不借用同 namespace 规则）（36 项）
│   ├── ContainerContextsTest.java             # 边界带共享内核·isSameSlotSpace(槽位数不一致/越界/同空/同物品/一空一非空/异物品) + resolve 末影箱分支(F-1) + isViewingEnderChest 判据 6 项（16 项）
│   ├── EntityContainersTest.java                  # 实体容器（2026-10-21）·白名单(ContainerEntity/带箱驴骡,无箱马与普通实体排除)/键 entity_uuid/列数启发式 27→9·5→5·15→5/getBlockPos 恒 null(防船停箱子上信标误判)/数据宿主=实体/无能力面即抛/menuSlotOwners 位置解析/注册表+白名单过滤/horse 字段反射判据/**鞍槽排除与偏移映射**（16 槽→15 存储格、getItem(0)→handler 槽 1、写不碰鞍槽）/船无偏移直通/**逻辑↔物理映射互逆**/**遥测键用物理槽**（18 项；驴解析走入口层反射，单测只锁字段形状）
│   ├── ContainerBoundaryContractTest.java     # 容器边界契约 I-C3/I-C4/I-C7 守卫：SlotIgnoringHandler(insertItem 丢 slot) + ExtractBlockedHandler(extract 被拦) ⇒ 写回落对槽/物品守恒/模拟真实同源（3 项，S2 后转绿）
│   ├── MenuSlotOwnersTest.java                # I-C5 守卫：菜单槽→容器位置解析·**核心不反射第三方方法**(菜单自带 getBlockPosition 也不认,D-container-09)/注册联动解析器后生效/无解析器返回 null/模组容器槽回落菜单位置/原版 BE 槽直取/大箱 CompoundContainer 两半位置(防跨容器虚影)/空位置集恒 false（6 项）
│   ├── SophisticatedMenuResolverTest.java        # D-container-09 守卫：包名匹配+getBlockPosition → 解出位置；包名不匹配**即使有同名方法也不认**（反射不扩散）（2 项；替身 FakeBackpackMenu 故意放在对方包名下）
│   ├── AvaritiaMenuResolverTest.java             # D-container-09 守卫（无尽箱子）：包名匹配+getTile()返回方块实体 → 解出位置；包名不匹配**即使有同名方法也不认**；返回类型非 BE 不认；无 getter 返回 null（4 项；替身 FakeInfinityChestMenu 放在对方包名下）
│   ├── ContainerNeighborsTest.java            # 四邻取数·fillNeighbors 与 getNeighbors 逐格一致（含边界）/ 单行容器 / 复用缓冲不污染（3 项）
│   ├── PlayerFluidDataPersistenceTest.java    # 玩家路径落盘（CONTAINER_FLUID_DATA_PLAYER）·背包与末影箱各有源写回 / 变空条目移除 / 重进不复活 / 两容器键互不干扰 / 重进从附件回填（4 项）
│   ├── ContainerChunkCacheChunkLoadTest.java  # 区块加载守卫·事件不碰世界/延后重扫不丢/限量/不主动加载/只处理ticking区/可观测性（6 项）
│   ├── ContainerKeysTest.java                 # 容器键解析唯一入口(2026-10-13)·5 种键形各自归位(背包/末影箱前缀相同必须按后缀分开)/缓存键剥维度前缀/未知形态返回 null 不抛异常/ownerOf 末影箱回归(旧实现抛异常被吞⇒从不发包)/isOwnedByPlayer(null 安全)/positionsOf(大箱两段·维度 id 含下划线取末三段)/维度前缀（7 项）
│   └── ContainerRuntimeCacheLifecycleTest.java # 运行时缓存生命周期(方案A)·物品离开槽位/换物品⇒登记作废·同类持续⇒不作废·drain 取走即空·null 键忽略·整容器作废键==运行时存储键（6 项）
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
├── living/domain/beacon/
│   ├── BeaconFunctionTest.java                 # 宿主判据(协议级 LidBlockEntity)·原版箱/末影箱⇒true/木桶/潜影盒⇒false/null 安全（3 项）
│   ├── BeaconPyramidTest.java                 # 1D 金字塔判级·各级精确目标/全0/null不足长/差一块掉档/多堆不亏/嵌套单调/L3 缺一列仍 L2/边界钳制封顶/单列无法激活/距下一级差额/范围公式/合计 9·34·83·164（19 项）
│   ├── BeaconColumnSlotsTest.java             # 下方 9 列槽位解析(纯数学)·居中/右边界钳制/左边界钳制/最底行全 -1/顶行居中/单行容器（6 项）
│   ├── BeaconSegmentTest.java                 # 遥测段编解码往返(等级+9柱高+列存在位图+再生档)/id=beacon 线格式契约/equals 按数组内容比(稳态不脏写前提,仅位图不同也判不等)/再生档越界回退（6 项）
│   ├── BeaconPanelGeometryTest.java           # 9 柱面板几何纯函数·满程 24=L4 中心契约/柱位 x/刻度 y 四档/柱顶钳位/缺口高度/数字取舍(目标值·达标不画·溢出绝对高)/reachable 可达性(含列存在)/columnExistsFrom（8 项）
│   ├── BeaconTooltipComponentTest.java        # 面板数据载体 from(BeaconRuntime) 字段直通+record equals（1 项）
│   ├── BeaconHostPacketTest.java              # S2C 宿主包往返(维度+位置+等级+容器玻璃色列表)/空列表（2 项）
│   ├── BeaconChestHostTest.java               # 多枚每枚一条·键格式(容器#槽位)/全同条目去重/两枚开一次/取一枚不关盖/全走才关/20tick 节流/关后重开即发/S2C 每枚一条/内容不变不重发（9 项）
│   ├── BeaconPlayerHostSyncTest.java          # 跨维度回归·两维度各自只发本维度/beginTick 清残留/只发别人(自己本机渲染)（3 项）
│   ├── BeaconGlassColorTest.java             # 容器玻璃取色·16 色玻璃/玻璃板/DyeColor 口径/非玻璃与空⇒null/同列扫描(自下而上·非玻璃跳过·顶行越界)（7 项）
│   ├── BeaconEffectsTest.java                 # 效果纯逻辑·喂锭门控(档位/II 仅 L4/5 槽满/未激活全拒)+生效数 min(堆叠,档内槽数)取前 N+再生三档(5 案例逐字)+buff 时长(9+等级×2)×20（22 项）
│   ├── BeaconConfigTest.java                  # 配置组件·add/amplify 不可变+5 槽封顶+contains+编解码往返(含空配置)（4 项）
│   ├── BeaconFeedHandlerTest.java             # 喂锭 handler 可测部分·规则谓词(5 矿石锭通过/非矿石拒绝)+OreType 映射与档位+槽位解析列宽取自 context(12 列≠9 列)/size 越界（5 项；⚠️ 柱高数值依赖 tag，单测环境不加载 ⇒ 游戏内验证）
│   └── BeaconPlayerHostPacketTest.java        # 玩家宿主包往返(维度+UUID+等级+容器玻璃色列表)/空列表（2 项）
├── living/domain/farmland/
│   ├── CropClassifierTest.java               # 作物分类器·火把花/瓶子草/柱状段回归（10 项）
│   ├── LivingFarmlandFunctionTest.java      # 输出合并回归·部分合并丢物品守卫（5 项）
│   ├── TillablesTest.java                   # 耕作知识表·锄头判定+可耕映射+真值表（8 项）
│   ├── FertilizeTransferTest.java           # 自动施肥·骨粉→活耕地零空转守卫（6 项）
│   └── LivingFarmlandPlacementTest.java     # 放置回世界·落点/幼苗/客户端/异常不冒泡+端到端接线（5 项）
├── living/domain/brewing/
│   ├── BrewingBatchLogicTest.java             # 批次状态机·启动/完成/作废两判据/堆叠倍率/批次标记生命周期(Start 写·Continue 保持·Complete·Abort·**Idle 自愈**)（14 项）
│   ├── BrewingSlotEligibilityTest.java        # 槽位准入隔离规则·活物品不作瓶子/材料/燃料（6 项）
│   ├── BrewingMaterialConsumeTest.java        # 材料消耗(consumeOne)·⭐龙息不整栈替换回归/空栈变玻璃瓶/并入未满堆/容器满丢弃/空槽 no-op（6 项）
│   └── LivingBrewingStandDataTest.java        # 数据组件 codec·旧数据(只有direction)可解码/往返保留 fuel 与 brew_start_tick/withBrewStart 隔离改字段/默认即移除锚点（4 项）
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
│   ├── ContainerDataMigrationTest.java          # 形态迁移·破坏时数据交给「本体那件」掉落物(**守卫已下沉测 ContainerDrops**)/无本体退回首项/无掉落返回 null + 组件 CODEC 往返保住派生源/空数据不产条目 + snapshot 独立副本/clearAll 一次清干净 + **相位快照 CODEC 往返保住 (P,φ,d)/EMPTY 不进物品**（10 项）
│   ├── ContainerFluidIntegrationTest.java     # 流体端到端（走真实 processContext）·预置派生源+驱动跑BFS / BE 写回 EMPTY / 驱动进自维持清单（3 项）
│   ├── FluidTransformTableTest.java           # 流体转化表 JSON 语义·内置装载/玩家差异(覆盖/removed)/坏文件跳过/坏条目跳过/缩容等待/活物品过滤/output_potion（含 output 必须为药水类物品）（8 项）
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
│   ├── ContainerCompatibilityConfigTest.java  # 容器布局推断 + 类型表与规则文件一致（双源漂移守卫）+ 多档容量共存（精妙背包 27/81=9 列、108/120=12 列）（16 项）
│   ├── ContainerRuleConfigTest.java           # 玩家差异持久化·覆盖内置+删除不复活+导出全量快照+社区闭环+多档共存/旧格式删除记录不被抹掉/内置规则含无尽箱子300格25列/contentRetaining 内置声明与玩家追加（17 项）
│   └── SlotInteractionCargoGateTest.java      # 槽位交互货物准入真值表·活骨粉不施肥（5 项）
└── network/
    ├── GuiInteractionPacketTest.java          # 目标槽判据·空槽可解析（活桶汲/倒包的回归守卫）/活物品可解析/非活物品不可解析（3 项）
    └── LivingItemSyncPacketTest.java          # 运行时同步包编解码往返（档 2 安全网）·三组字段往返/空数据/多槽混合/两种键形态/容器种类 kind 往返(背包·末影箱·世界·掉落物)/子记录哨兵/未登记 id 报错（14 项）
    └── LivingItemRuntimeSyncTest.java         # 末影箱 viewer 门控谓词·背包不门控/末影箱只有开着 GUI 才发/非玩家态恒 true 防御（3 项）
```
