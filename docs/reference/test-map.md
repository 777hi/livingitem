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
│   ├── ContainerInfoSessionTest.java           # 菜单信息会话·初次查询/悬停限频重试/菜单与请求双关联/晚包拒绝/ID复用新会话/未请求不接收（6 项）
│   ├── ContainerInfoTooltipTest.java           # 容器提示·普通几何与末行/启发式普通警示/未知与非运行分开/高级来源和类型 ID（5 项）
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
│   ├── DroppedItemSupportTest.java            # 掉落物形态准入判据（真机「丢在地上的活 TNT 不 tick、不炸」的回归）·活 TNT ⇒ 支持/活工具 ⇒ 支持（既有形态不能回退）/未声明功能（活箱子）⇒ 不支持/非活物品 ⇒ 不支持（4 项；⚠️ registerFunction 不去重，统一 @BeforeAll 注册）
│   ├── LivingItemActivationTest.java          # 活化门面守卫·零配置全放行/显式开启才拒绝/取消总允许/探针不改原物品（5 项）
│   ├── ActivationRuleConfigTest.java          # 活化规则 JSON 加载语义·via缺省=player/activate与deactivate独立/先命中先赢/白名单/坏值跳过（14 项，含指令侧增删改与持久化往返；tag 路径 @Disabled —— 单测环境不加载 item tags，已游戏内验证通过）
│   └── TickOrderTest.java                     # tick 顺序契约守卫·默认优先级全为0（零行为变化的证据）/稳定排序/优先级生效/冻结后不可变且非降序（4 项）
├── living/command/
│   ├── ActivationTargetParsingTest.java       # 活化目标参数解析守卫·物品ID/标签/modid 回写契约 + 反向守卫「StringArgumentType 读不进 : # @」（4 项）
│   └── LivingItemContainerCommandTest.java     # inspect 实际入口·学习值替代独立推断 / 活对象列数与注册规则分列 / 末行向上取整 / 未开战利品明确不处理 / 大箱另一半同源规范化（5 项）
├── living/components/
│   └── ContainerUuidsTest.java                # 容器 UUID 读写门面（2026-10-22，活背包）·懒创建幂等（跨 GUI 开合进度不重置）/ofItem 无副作用/复制 remove 范式给副本新身份/strip 空栈与 null 安全/宿主侧 null 安全/keyOf 与 containerUuidOf 互逆且 kindOf=ITEM_FORM/cuuid_ 不被误认世界方块 + **discard 剥 UUID 且删数据/退化输入安全** + **哨兵（全零 UUID）翻译成 null/懒创建写真身份可回读**（11 项）
├── living/container/
│   ├── EnderChestContextTest.java              # 末影箱锚点口径（2026-10-27 定案「存放处」D-container-18）·getAnchor 必须显式为 null（默认实现经 getOwnerPlayer 退化成 AtPlayer ⇒ K2 当玩家宿主上报 ⇒ 两端不同源）/ 非物品形态 ⇒ hostAnchor 恒 null、hostSlot=-1（1 项）
│   ├── ContainerLayoutResolverTest.java        # 共同布局链·子类禁止替换流程/实例先于内建/规则学习家族启发式顺序/多宿主规则优先/首容器回落/家族子类/现取与结果复用/掉落物同链（8 项）
│   ├── ContainerMenuInfosTest.java             # 当前菜单宿主信息·空容器/背包/状态分离/来源同源/真实无MenuType驴菜单反查/无类型方块与未知菜单分开/末影箱固定布局及改容量不误套（14 项）
│   ├── ItemFormContainerContextTest.java       # 物品形态容器地基（2026-10-22，活背包）·来源注册口（无来源不认领/注册后可认领/find 返回同一来源/空栈与普通物品不认领）+ ctx 身份（UUID 与 cuuid_ 键互逆/hosts 空/无世界位置）+ **persist 写 SavedData（不是空转的 default）/空值删条目/非 isSaved 不写** + **菜单反查（无活容器 null/唯一一件认它/两件不猜/未注册 null）**（10 项）
│   ├── ContainerDataKeyTest.java               # 落盘判据 + persist 默认路径（2026-10-22，D-container-13）·savedWith 的 attachment+codec 都有⇒isSaved/persistent 无附件⇒不落盘/tick 级两者都不/persist 写所有宿主/非 isSaved 一个都不写/宿主空不抛（6 项）
│   ├── ContainerDataSavedDataTest.java         # SavedData 持久层（2026-10-22，活背包）·put→get 往返/save→load 跨重启/空条目不写盘/isSaved=false 拒绝写入/remove(UUID) 只删一个/同 key 后写覆盖/未知 UUID 返回 null/组件注册（8 项）
│   ├── SimpleContainerContextTest.java        # 容器上下文脏槽同步 + 末影箱稳定键构造器（4 项回归）+ 缓存键推导（世界容器带维度前缀/跨维度同坐标键不同/大箱子两段坐标/背包末影箱裸键/无维度退化）+ 列数推断（启发式/质数退化/未注册类型不借用同 namespace 规则/**Tier 0 来源权威值赢启发式（放置态精妙背包 30 槽→5 列，2026-10-24）/来源不认领⇒照旧回落启发式**）（38 项）
│   ├── ContainerContextsTest.java             # 边界带共享内核·isSameSlotSpace(槽位数不一致/越界/同空/同物品/一空一非空/异物品) + resolve 末影箱分支(F-1) + isViewingEnderChest 判据 6 项（16 项）
│   ├── EntityContainersTest.java              # 实体宿主白名单/UUID身份/数据宿主/槽位与鞍偏移/遥测物理键/菜单实体反查/原版车船类型+容量固定布局/非原版或扩容不借固定值/羊驼实例列数/摘箱空存储固定1列（24 项；真实驴菜单入口另由 ContainerMenuInfosTest 守卫，移除旧accessor恒真占位）·**三通道过槽位映射**（2026-10-27 审查 P2-1，I-C4/I-C10）：getSlotLimit/isItemValid/simulateInsertItem 曾直接当物理槽 ⇒ 鞍了鞍的驴第一箱格永远写不进（判别测试：鞍位禁插+首槽限流的自定义 handler）
│   ├── ContainerBoundaryContractTest.java     # 容器边界契约 I-C3/I-C4/I-C7/**I-C11** 守卫：SlotIgnoringHandler(insertItem 丢 slot) + ExtractBlockedHandler(extract 被拦) ⇒ 写回落对槽/物品守恒/模拟真实同源（3 项，S2 后转绿 **+ I-C11：四种容器形态的列数都 ≥ 1（掉落物形态 = 1，它原先白嫖接口默认 9）**

│   ├── GameplayConfigTest.java                 # 玩法开关的默认值（2026-10-24）·spec 能构建/**四个宿主锚点开关默认全关**（哑弹/不发电/不挖矿/不开光）（2 项；⚠️ 只测未加载配置时的静态字段，不测 ModConfigSpec 本身）
│   ├── ContainerAnchorTest.java                # 容器世界锚点（2026-10-23 收编 4 份重复解析）·方块⇒AtBlock/玩家⇒AtPlayer(靠 getOwnerPlayer)/**实体容器⇒AtEntity（必须覆写，否则退化成没锚点）**/**掉落物⇒AtEntity（有实体，不是没锚点）**/物品形态⇒null/**getHostAnchor 与 getAnchor 刻意分开（物品形态有宿主值、非物品形态恒 null）**/roughPosition 三分支/退化不抛（9 项）
│   ├── ContainerViewersTest.java               # 「谁在看这个容器」唯一判定入口（2026-10-23 收编）·背包只发主人不门控(隐私红线)/末影箱开着 GUI 才发(第③次泄漏修复)/物品形态按菜单锚点认观众(开着别的容器不算)/位置为空⇒无观众/退化输入不抛（6 项；末影箱门控两条用例从流体轨与运行时轨合并迁入）
│   ├── ContainerHandoffTest.java              # I-C9 **第三挂点「双向交接」**（2026-10-21，D-container-12）·宿主→物品：捕获→交接搬到目标/无捕获 no-op/空栈不搬且丢捕获/capture(null) 清残留/一个 Mover 抛异常不连累其它/discard + **物品→宿主：暂存→恢复调 moveIn / 暂存是副本（源栈 shrink 后仍能恢复）/无暂存或宿主空 no-op/stageRestore(EMPTY) 清残留** + **反射守卫：Mover 不得有 default 方法**（真机三连失败的真凶）（11 项）
│   ├── MenuSlotOwnersTest.java                # I-C5 守卫：菜单槽→容器位置解析·**核心不反射第三方方法**(菜单自带 getBlockPosition 也不认,D-container-09)/注册联动解析器后生效/无解析器返回 null/模组容器槽回落菜单位置/原版 BE 槽直取/大箱 CompoundContainer 两半位置(防跨容器虚影)/空位置集恒 false（6 项）
│   ├── SophisticatedMenuResolverTest.java        # D-container-09 守卫：包名匹配+getBlockPosition → 解出位置；包名不匹配**即使有同名方法也不认**（反射不扩散）（2 项；替身 FakeBackpackMenu 故意放在对方包名下）
│   ├── SophisticatedBackpackSourceTest.java      # 活背包 #6 菜单解析·**两个活背包也认菜单锚定的那个**（真机「倒不了」的回归）/锚定物未活化⇒null/非背包菜单⇒null/**锚点读不到⇒退回默认扫描（单背包仍认得出）**/反查与 tick 拿同一内容视图/**权威列数 30槽÷6行=5（启发式给10，错）/27÷3=9/除不尽⇒0**/**放置态同一套算法（BackpackBlockEntity 同形替身）/别人的 BE 不认领⇒0**（9 项；替身 BackpackContainer/BackpackContext/IBackpackWrapper/BackpackWrapper/BackpackBlockEntity 均在对方包名下，BackpackItem 用 mock 无构造实例化 + asItem/components 双桩）
│   ├── AvaritiaMenuResolverTest.java             # D-container-09 守卫（无尽箱子）：包名匹配+getTile()返回方块实体 → 解出位置；包名不匹配**即使有同名方法也不认**；返回类型非 BE 不认；无 getter 返回 null（4 项；替身 FakeInfinityChestMenu 放在对方包名下）
│   ├── ContainerNeighborsTest.java            # 四邻取数·fillNeighbors 与 getNeighbors 逐格一致（含边界）/ 单行容器 / 复用缓冲不污染（3 项）
│   ├── PlayerFluidDataPersistenceTest.java    # 玩家路径落盘（CONTAINER_FLUID_DATA_PLAYER）·背包与末影箱各有源写回 / 变空条目移除 / 重进不复活 / 两容器键互不干扰 / 重进从附件回填（4 项）
│   ├── ContainerChunkCacheChunkLoadTest.java  # 区块加载守卫·事件不碰世界/延后重扫不丢/限量/不主动加载/只处理ticking区/可观测性（6 项）
│   ├── ContainerKeysTest.java                 # 容器键解析唯一入口(2026-10-13)·5 种键形各自归位(背包/末影箱前缀相同必须按后缀分开)/缓存键剥维度前缀/未知形态返回 null 不抛异常/ownerOf 末影箱回归(旧实现抛异常被吞⇒从不发包)/isOwnedByPlayer(null 安全)/positionsOf(大箱两段·维度 id 含下划线取末三段)/维度前缀（7 项）
│   ├── ContainerRuntimeCacheSlotMappingTest.java  # 读写槽位键对称（2026-10-27 审查 P1，熔炉/酿造台/漏斗域）·update 过 toPhysicalSlot、get 不过 ⇒ 马/驴容器（storageOffset=1）读永远 miss ⇒ 熔炉每 tick 烧 1 燃料零产出、酿造台每 tick 空耗烈焰粉 / get(ctx, logical) 对称重载：带偏移上下文读回自己写的值 / 裸 get(key,3) miss·物理槽 4 命中 / 恒等映射两版等价（3 项）
│   └── ContainerRuntimeCacheLifecycleTest.java # 运行时缓存生命周期(方案A)·物品离开槽位/换物品⇒登记作废·同类持续⇒不作废·drain 取走即空·null 键忽略·整容器作废键==运行时存储键（6 项）
├── living/domain/backpack/
│   ├── LivingBackpackFunctionTest.java        # 活背包（2026-10-22）·认领（无来源不认领=未装模组零影响/来源注册后只认它说是容器的物品）+ 功能 ID 稳定 + **取消活化连 SavedData 一起清**/无 UUID 不抛（5 项）
│   ├── LivingBackpackTickTest.java            # 活背包 tick 的三道闸（2026-10-24 终审补，此前 tick 零测试）·**宿主锚点传进内层 ctx**（AtBlock 同坐标 / 宿主无锚点⇒null）/深度闸（背包里的背包不再处理）/**逐条目隔离**（一个背包的来源抛异常不带走同批）（4 项；⚠️ 环检测在 MAX_DEPTH=1 下不可达，故意不测；探针功能只认 SHULKER_SHELL，避免与既有「无功能」断言串味）
│   └── LivingBackpackTooltipTest.java         # 活背包 tooltip 的容器 ID 行（2026-10-26）·短码固定 8 位（**前导零不能丢，否则 substring 越界**）/未活化不输出空 ID 行/高级提示才给完整 UUID + 重新活化提示/**复制品短码必须相同**/剥掉后重新生成换新身份（5 项）
├── living/domain/tools/
│   ├── LivingToolHostBlockTest.java              # 活工具「别挖自己容器」黑名单与射线起点**同源**（2026-10-25，TODO 6.2）·背包在箱子里+开关开⇒黑名单含宿主那一格（旧实现位置列表恒空⇒射线第一个穿到的格子就是那只箱子：记过类型⇒停住没反应 / 没记⇒挖自己的箱子）/ 开关关⇒不借锚点⇒黑名单空 / 方块容器保留自己的两格（回归）/ 玩家背包与背包在玩家身上⇒无方块可跳过 / 方块容器不受开关影响（5 项；⚠️ Inventory.player 是 public final 字段，Mockito 桩不了⇒反射设值，同 Level.random）
│   ├── LivingToolHostSyncDedupTest.java      # K2 去重键口径（2026-10-26）·**动画状态必须算变化**（只有 progress / 只有 action / 只有 digTicks 不同 ⇒ 判有变化，否则方块容器「有模型、不转圈」）/ 内容全同⇒无变化 / **耐久不进键**（多比一个就刷包）（5 项；两个方向都钉）
│   ├── LivingToolHostSyncDimensionTest.java  # K2 跨维度铁律（2026-10-27 审查）·PLAYER 宿主上报在玩家循环（维度循环之前）⇒ **登记表不得在 flush 里抽干**：两维度背包宿主各收各的（旧实现首维度抽干全表 ⇒ 地狱/末地条目全丢）/ 方块宿主**显式按上报维度隔离**（不再依赖抽干顺序）/ beginTick 清残留（取走背包⇒发空包清客户端）/ 稳态零发包（flush 只读去重仍生效）（4 项；替身结构同 BeaconPlayerHostSyncTest）
│   ├── LivingToolAssistTest.java             # 辅助挖掘**速度分层**（2026-10-27 用户定，真机翻车那层的回归）·顶层相加+背包只取最高（3+4+9=16，背包另一把 5 不加）/ 空列表⇒0 / 只有背包条目⇒取最高（含客户端 S2C 补的条目）（3 项）
│   └── CarriedLivingItemsTest.java              # 「随身携带」判据（2026-10-27，物品形态）·顶层工具收（innerSlot=-1）/ 已活化容器展开一层（outerSlot=宿主格、innerSlot=内容格）/ **未活化容器不展开**（活化门槛即性能闸门）/ skipMainHand（主手工具不收）/**skipMainHand 仍展开主手背包内容**（原版计入的是手持工具不是手持容器的内容；连展开一起跳 ⇒ 手持背包两端不同源）/ **槽位顺序**（容器条目紧跟宿主槽位，E6 掉落归属取最靠前）/ **写回容器走 setStackInSlot**（否则背包里的工具白干活不掉耐久）/**客户端 S2C 补的条目仍算容器内**（否则两端分层不一致 ⇒ 方块破坏又恢复）/ 写回守卫（目标格被换走⇒放弃不覆盖 / 槽位越界⇒不抛）（10 项）
├── living/domain/tnt/
│   ├── ExplosionComponentCountTest.java       # TNT 计数/清空守恒（2026-10-27 审查 P2-1，TODO 挂账 S1 的回归）·int 求和曾回绕成负 ⇒ 早退但物品已清空（TNT 凭空消失零爆炸零日志）/ 两满槽 int 极值 ⇒ long 求和不回绕且统计阶段不消耗 / 清空只清活 TNT（普通 TNT 与非 TNT 保留）/ 无活 TNT ⇒ 计数 0（3 项）
│   ├── LivingTntConsumeTest.java              # 爆炸后**必须消耗**（真机「炸了但 TNT 还在」的回归）·单颗炸完槽位空/多颗一起炸全部消耗（ignite 一次清空，快照写回会复活没轮到的）/掉落物形态炸在原地并消耗/**宿主锚点默认关⇒哑弹 / 打开⇒炸在宿主并消耗**（5 项；⚠️ ServerLevel 替身需反射注入 Level.random 字段，账本用真 ExplosionLedger）
│   ├── ExplosionParamsTest.java               # 爆炸参数·位图映射可逆/网格外返回-1/affects=区块AABB∩球体/边界回归(中心在半径外但边缘在球内)/球体全覆盖(半径内每方块所在区块必命中)/网格规模（7 项）
│   ├── ExplosionLedgerTest.java               # 待炸账本·相交才进队列/不相交立即标记/未加载丢弃不轮询/自然加载补炸闭环/分帧预算/多场叠加不覆盖新登记/满额降级/存档往返（10 项）
│   └── ExplosionComponentLightTest.java       # 爆炸后光照刷新·四入口顺序/天光柱高图先于重算/section 空态/sectionY≠索引/发光方块减光（5 项）
├── living/domain/redstone/
│   ├── RedstoneHandlerSetChangedTest.java     # 交互 handler 就地改写标脏（2026-10-27 审查 P2，与 IgniteHandler P3-4 同类）·拉杆切换成功 ⇒ setChanged 被调，否则开关态在「改完→下次落盘」窗口丢失（1 项；四 handler 同构逐一接线）
│   ├── ContainerRedstoneBoundaryTest.java     # 边界采样自反馈守卫（2026-10-27 审查 P1）·双箱两半共用账本 ⇒ 朝另一半查到 this 曾把自己对面 face 输出读回对侧输入（东边拉杆隔空压灭西边火把）/ 邻居是自己 ⇒ 保持世界信号 / 无账本·无朝向邻居 ⇒ 原样（3 项；mockStatic 邻居查询）
│   ├── ContainerRedstoneDataTest.java         # 红石信号传播引擎·直接驱动 calculate/**空容器(存储格 0)⇒ 直接跳过，不拿 0 当除数**（30 项）
│   └── ContainerRedstoneIntegrationTest.java  # 红石**驱动链路**端到端（走真实 processContext）·自维持注册/守卫放行消费者/守卫拦截无关容器(零开销)/残留归零/prio 顺序（5 项）
├── living/domain/power/
│   ├── ChannelStateAmplitudeRatchetTest 附录（在 ChannelStateTest 内）# 幅度棘轮（2026-10-27 审查 P1-1，I-E6 下行缩放）·maxDelta 曾只升不降 ⇒ 先高堆叠后降堆叠时冻结历史峰值、发电量长期虚高（「一次高堆叠永久收割」套利）/ 跨 tick 改 latest 覆盖：幅度下行 effDeltaSum 回落 / 同 tick 多事件仍取 max（I-B3 不变）（2 项）
│   ├── PhaseDataMigrationTest.java            # 相位形态迁移（2026-10-21 补，此前零测试）·moveOut 只对 contentRetaining 搬/非 retaining 不搬/EMPTY 不搬 + moveIn 无组件 noop/**无 ctx 降级也写附件**（5 项）
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
│   ├── LivingChestFunctionTest.java           # 取消活化堆叠倍数返还（6 项）
│   └── LivingChestInsertByteGateTest.java     # 16KB 字节上限插入点精确把关（2026-10-27 审查 P1）·三参 insert 曾直接丢弃 registries 转调估算（每槽 64B ⇒ 27 槽 1728B 永远够不到上限）⇒ 漏斗/能力面可无限塞高 NBT 物品 / 精确把关：20 本成书(≈1KB/本)+空槽 ⇒ 三参拒绝且内容不动 / 未超限正常收 / **估算路径缺口被钉住**（null registries 仍放行 —— 能力面拿不到 level 的已知残留，转红即已收口）（3 项）
├── living/domain/beacon/
│   ├── BeaconFunctionTest.java                 # 宿主判据(协议级 LidBlockEntity)·原版箱/末影箱⇒true/木桶/潜影盒⇒false/null 安全（3 项）
│   ├── BeaconPyramidTest.java                 # 1D 金字塔判级·各级精确目标/全0/null不足长/差一块掉档/多堆不亏/嵌套单调/L3 缺一列仍 L2/边界钳制封顶/单列无法激活/距下一级差额/范围公式/合计 9·34·83·164（19 项）
│   ├── BeaconHostDispatchTest.java               # 信标宿主分派与取点**同源**（2026-10-25，TODO 6.2）·背包在箱子里+开关开⇒分派拿到宿主箱子锚点（旧写法问 getBlockPos/getOwnerPlayer 双 null⇒有 buff 没光）/ 背包在玩家身上⇒玩家锚点 / 开关关⇒无宿主 / 方块容器与玩家背包各用自己的锚点（回归）（5 项；⚠️ 只测 effectiveAnchor 决策点 —— tick 全链路的金字塔等级靠 BEACON_BASE_BLOCKS 标签，单测环境不加载⇒等级恒 0⇒宿主分支进不去，由 BeaconBaseBlocks 注释与游戏内验证兜住）
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
│   ├── BonemealHandlerTest.java               # 活骨粉 GUI 交互信任边界（2026-10-27 审查 P3）·创造模式曾连「光标是不是活骨粉」都不验 ⇒ 改包客户端空手刷生长（同域口径=照验免扣）/ 创造+骨粉放行免扣 / 生存扣 1 / 普通骨粉拒绝（活化门槛）（4 项；pin 手法：未成熟植株 forceGrowthTick 不碰 level ⇒ age+1 即通过判据）
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
│   ├── FurnaceOutputGateTest.java             # 输出槽判据同源 + 配方缓存（2026-10-27 审查 P1-2/P2-3）·预检曾只比物品 ID、写入走 isSameItemSameComponents ⇒ 输出槽放改名铁锭/活铁锭时进度卡 isComplete、燃料无限烧零产出（原版 canBurn 组件级不点燃）/ 预置燃烧态端到端：组件不同 ⇒ 进度回退不混写；输出空 ⇒ 完成即产出 / 无配方也进缓存（曾只认 cachedResult==1 ⇒ 非熔炼物品每 tick 重查）（3 项；transform 真身=运行时缓存，别读组件）
│   ├── LivingFurnaceFunctionTest.java         # 燃料消耗合成残留物语义（4 项）
│   └── FurnaceBurningFlagTest.java            # 燃烧标志组件·图标切换回归（5 项）
├── living/domain/hopper/
│   ├── HopperFilterSyncTest.java              # 漏斗过滤链回写·黑白名单展示回归（5 项）
│   ├── CrossContainerTransferFertilizeTest.java # 跨容器施肥·推送/拉取双入口+活骨粉三入口全拒（15 项）
│   └── CrossContainerTransferFaceSelectionTest.java # 多方块容器跨容器面选取·候选排序+推送首选/回退（5 项）
├── living/compat/sable/
│   └── SableTeleportMathTest.java             # 飞艇艇底对齐目标Y（A4定案）·当前艇底在原点上/下两方向均可升降 / 平移后世界投影包围盒底面严格等于目标高度（3项；纯坐标函数，不依赖Sable运行环境）
├── living/domain/map/
│   ├── TeleportHelperYTest.java               # 落点站立面语义 + 指哪传哪（2026-10-27 审查 P1-1 + A3 定案）·ChunkAccess.getHeight 返回最高阻挡方块自身 Y，destY 曾少 +1 ⇒ 玩家脚落进最高阻挡方块 / 端到端 mock：teleportTo 捕获 (格中心, 71.0, 格中心) / **虚空柱照传不拒**：全空柱高度图返回世界下限，脚下 = 下限+1，不因无地面拒绝（2 项）
│   ├── MapCoordHelperMatchTest.java           # 组件条目与命中装饰的同一处匹配（2026-10-27 审查 P2-2）·Entry.x() 是 double 世界坐标、Decoration.x() 是 byte 像素，曾直接相比恒 false ⇒ 兜底恒传组件里第一条（多条目地图点哪个都被劫持）/ 类型+换算像素一致才用精确值 / 无匹配回退像素反推 / 换算互逆（4 项）
│   └── MapCoordHelperTest.java                # 地图坐标换算（29 项）
├── living/domain/water/
│   ├── ContainerFluidDataTest.java            # 流体引擎行为快照·单源扩散/上限7/活物阻挡/非活物穿过/源移除/水流推动/二维扩散 + 行为接缝·按maxLevel/静止不扩散 + TickContext 建流体数据回归 + 通用驱动·自维持/驱动BFS + 源查询API + 派生源·独立存活/挤没/共存/同格无豁免/异种覆盖/EMPTY noop + 落盘CODEC往返 + 晋升接缝 + 转化接缝（26 项）
│   ├── ContainerFluidHandlerTest.java          # 管道抽取能力（§10）·tank 枚举与稳定序 / SIMULATE 不消耗 vs EXECUTE 删源 / 源全有或全无 / 异种 EMPTY / fill 恒 0 + isFluidValid / 非容器无害 / provider 四段让位（6 项）
│   ├── ContainerDataMigrationTest.java          # 形态迁移·破坏时数据交给「本体那件」掉落物(**守卫已下沉测 ContainerDrops**)/无本体退回首项/无掉落返回 null + 组件 CODEC 往返保住派生源/空数据不产条目 + snapshot 独立副本/clearAll 一次清干净 + **相位快照 CODEC 往返保住 (P,φ,d)/EMPTY 不进物品** + **moveOut 共用入口**（contentRetaining 搬+清空 / 非 retaining 不搬 / 退化输入安全）+ **端到端 moveOut 写物品组件（非空快照）/ moveIn 写回宿主附件** + **身份优先：有 UUID 只搬身份、不写数据组件（moveOut / moveIn 各一）**（17 项）
│   ├── ContainerFluidIntegrationTest.java     # 流体端到端（走真实 processContext）·预置派生源+驱动跑BFS / BE 写回 EMPTY / 驱动进自维持清单（3 项）
│   ├── FluidTransformTableTest.java           # 流体转化表 JSON 语义·内置装载/玩家差异(覆盖/removed)/坏文件跳过/坏条目跳过/缩容等待/活物品过滤/output_potion（含 output 必须为药水类物品）（8 项）
│   ├── FluidFlowClientCacheTest.java           # 客户端快照分桶不变量（2026-10-06 第 ③ 次泄漏修复）·ENDER/BLOCK 目标路由 / PLAYER_INV 不翻转非背包组提示 / clear() 三桶全空并复位（4 项）
│   ├── FluidFlowServerSyncTest.java           # 流体快照同步边沿·数据清空恰好一次清屏/从未激活不发/反复汲倒重新武装（3 项）+ **关界面时物品形态容器只问一次、不进逐槽循环**（真机 60 条第三方 ERROR 的回归，4 项）
│   ├── LivingBucketFunctionTest.java          # 活桶零私有状态·判定(BucketItem家族通吃)/内容=原版content/形态变换 getBucket 映射/同形态零新对象（5 项）
│   ├── LivingBucketInteractSupportTest.java   # 倒桶源格替换（A 档对齐原版）·契约 default false（未覆写流体保守拒绝）/ 水⇄岩浆对称替换 + 同种不构成 / 倒桶判定四例（3 项）
│   └── ContainerFluidPerfTest.java            # 性能量测（200 容器×54 格×200 拍，打印基线 + 病态回归宽松阈值 + 落盘 build/_perf.txt）·引擎 tick / 空容器（2 项）
├── living/interaction/
│   ├── InteractionRegistryTest.java           # 两趟优先级匹配·通配遮蔽+triggerFilter 回归守卫（7 项）
│   ├── InteractionRuleConfigTest.java         # 交互规则 JSON 加载语义·内置全有效/玩家覆盖与removed/坏值跳过/未知字段忽略/version守卫/reload幂等（9 项；自带 mock handler 不依赖测试执行顺序）
│   └── TillToFarmlandCompatTest.java          # 活锄头跨模组兼容·模组锄头命中+处理器产物回归（8 项）
├── living/transfer/
│   ├── ContainerCompatibilityConfigTest.java  # 容器布局推断 + 类型表与规则文件一致（双源漂移守卫）+ 多档容量共存（精妙背包 27/81=9 列、108/120=12 列）（16 项）
│   ├── ContainerLayoutLearnerTest.java        # 布局学习形状/身份/玩家槽过滤 + 菜单入口四门禁独立守卫（归属混合、重复/负数/越界/断号、局部视图、真实熔炉非行优先）+ 规则阻止学习 + 菜单冲突/歧义跨重启 + 普通与歧义保存失败重试 + 矛盾磁盘输入以歧义为准 + 学习优先家族且规则仍优先学习（31 项；事件用 ServerPlayer，准入用真 BE，单测静态态清理）

│   ├── ContainerRuleConfigTest.java           # 玩家差异持久化·覆盖内置+删除不复活+导出全量快照+社区闭环+多档共存/旧格式删除记录不被抹掉/内置规则含无尽箱子300格25列/contentRetaining 内置声明与玩家追加（17 项）
│   └── SlotInteractionCargoGateTest.java      # 槽位交互货物准入真值表·活骨粉不施肥（5 项）
├── living/util/
│   └── BroadcastDedupTest.java                # 定向广播去重表的跨维度合并（2026-10-23 定，10-26 抽成共享工具）·**其它维度玩家的记录必须保留**（逐维度 flush 不能互清，旧写法每玩家每 tick 重发一包）/离线玩家淘汰/本维度新记录覆盖旧值（3 项；四个同步器共用一份实现，故只留这一份测试）
└── network/
    ├── ContainerMenuInfoPacketTest.java         # 菜单信息载荷·全形态/来源codec/状态独立/request关联/服务端限频/错菜单拒绝/实际回包/退出清理/几何有效性（8 项）
    ├── GuiInteractionPacketTest.java          # 目标槽判据·空槽可解析（活桶汲/倒包的回归守卫）/活物品可解析/非活物品不可解析（3 项）
    └── LivingItemSyncPacketTest.java          # 运行时同步包编解码往返（档 2 安全网）·三组字段往返/空数据/多槽混合/两种键形态/容器种类 kind 往返(背包·末影箱·世界·掉落物)/子记录哨兵/未登记 id 报错（14 项）
    └── LivingItemRuntimeSyncTest.java         # （门控判定已迁 ContainerViewersTest）+ **物品形态容器按菜单锚点发包（真机 tooltip 不显示容器信息的回归）**（4 项）
    └── ServerPacketHandlerInventorySafetyTest.java  # 满背包不丢物（2026-10-27 审查 P0）·配方书 Shift 取出活箱子物品曾直接丢栈 ⇒ 背包满时静默销毁 / 满 ⇒ 余量掉落（Player#drop 原版入口，落副本+清零）/ 空 ⇒ 全收不掉 / 部分空间 ⇒ 合并后余量掉落（3 项）
    └── GuiInteractionTargetGuardTest.java      # GUI 交互包服务端目标身份复验（2026-10-27 审查 P3-2）·isAcceptable 只验「是活物品」⇒ 改过的客户端可对任意活物品发 lever_toggle 写 LIVING_LEVER_DATA / 守卫与规则 JSON 同源（走同一份 matchesTarget：条目说什么守卫放行什么）/ 未知 actionId 放行 / 空目标放行（活桶通道在各自 handler 重验）（6 项；⚠️ 判据用 item 型 target——tag 在单测环境不绑）·**同 actionId 多条目=任一命中**（till_to_farmland 5 条 target：曾首条 return 误杀其余 4 条，耕活泥土进阶链断裂——本条是 2026-10-27 审查修复自身引入又当场修掉的回归）
```
