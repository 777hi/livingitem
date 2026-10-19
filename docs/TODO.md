# 待办 / 已知问题

> **性质**：随手记的待办池，**不是**规格文档 —— 也是**全项目待定计划的唯一登记处**
> （2026-10-10 拍板 `D-doc-06`：tech 文档 §0 不再维护「未实现」清单，计划统一到这里）。
> 已完成项**保留删除线 + 完成日期**（留痕「做过什么、为何算完成」，不整段删）；
> 未定案设计稿的**正文**放 `docs/buffer/`，这里只放一行 + 指针。
> 上次复核：2026-10-10。

## 文档

1. `docs/tech/common/recipe-book-tech.md` **需按现架构重写**（2026-10-03 发现）：
   全文以**已删除**的 `LivingChestContentsCache`（全局缓存 + `dirty` 脏标记）为前提，
   现为 `RecipeBookComponentMixin.collectLivingChestItems()` **每帧扫描玩家背包**。
   已加勘误横幅，**正文未改写** —— 重写前需先完整梳理现实现。

## 框架演进（2026-10-18 提案，未定案）

1. **遥测客户端推导 / 统一宿主模型 / 协议判据 / per-item 数据纪律** —— 正文
   [framework-evolution-plan.md](buffer/framework-evolution-plan.md)。触发于活信标复盘。
   现状判断：**A（客户端推导）等「第二个内容派生型域」出现才值得做**（今天只有信标一个用户）；
   **B（统一宿主）⏸ 暂缓**（2026-10-18 用户定：等之后的新活物品需要时再做；届时先抽传输内核）；
   **C ✅ 已落地**（2026-10-18：宿主协议化 + 列宽双源对齐 + 守卫测试）；
   **D ✅ 已落地**（2026-10-18 写进设计模板 §1.1-b）。

## 活信标 · 游玩实测（2026-10-18 收尾时挂起）

1. **§6.1 验证清单待游玩对照** —— 用户定「细节问题实际游玩的时候再慢慢反馈」，
   **不设专门测试轮**。清单在 [living-beacon-tech.md](tech/living-beacon-tech.md) §6.1
   （19 条）；2026-10-18 当天已通过实测反馈修掉 11 项（见 changelog 2026-10-18 节），
   未勾项 = 游玩中尚未遇到或尚未反馈的。游玩中发现的问题按惯例落 changelog。

## 性能（2026-10-03 盘点）

> 前提：设计目标是**大型整合包跑图** —— 容器数量按**上万**估算。
> 「每 tick 每单位一次分配」这类开销在 100 容器时无所谓，在 10000 容器时会累积成主要开销。

1. ⭐ **战利品箱子「半忽略」**（一行改动，**零风险**）——
   发现阶段（`ContainerChunkCache.scanChunkForContainers`）**不检查战利品表**，
   处理阶段（`LivingItem.processLevelContainers`）才检查。
   后果：纯战利品箱子区块会经历一次「进缓存 → 全 BE 能力扫描 → 移出」的往返
   （处理阶段那个循环**没有 break**，要把所有 BlockEntity 查一遍 capability）。
   修法：发现阶段加同一行检查（`RandomizableContainer` + `getLootTable() != null` ⇒ 跳过）。
   **行为完全一致** —— 处理阶段本来就忽略（里面的活物品现在也不会被 tick）。
   ⚠️ 箱子被打开后 `getLootTable()` 变 null ⇒ 按普通容器处理（现有行为，不受影响）。

2. **`getProcessableChunks` 每 tick 新建 Set**（大小 = 缓存区块数）——
   10000 区块时每 tick 一个万级 `ObjectOpenHashSet`。修法：双缓冲或复用。

3. **`processLevelContainers` 每区块 `new ArrayList<>(chunk.getBlockEntities().values())`** ——
   10000 区块时每 tick 一万个 ArrayList（防御性拷贝）。

4. **`scanAndGroupLivingItems` 每容器 `new LinkedHashMap<>()`** —— 同上量级。

5. **（收益最大）合并两次全槽位遍历** —— `scanAndGroupLivingItems` 与
   `computeContentSignature` 各遍历一遍全部槽位 ⇒ 合并可**省 50% 扫描**。
   详见 [infrastructure-refactoring-plan.md](archive/infrastructure-refactoring-plan.md) §1.7 / §6.3-B。

## 活流体（流体侧挂起项 —— 原 idea.md 任务队列 2026-10-08 已蒸馏进 tech/archive，本节为现行台账）

1. ~~**末影箱汲/倒**~~ —— **已完成**（2026-10-05 F-1：`ContainerContexts.resolve` 末影箱分支 +
   `EnderChestContainerContext` 独立类；同日补 `FluidFlowServerSync` 第三条派发）。
   🔴 **渲染侧踩过三次泄漏**（① 泄漏到玩家物品栏 ② 一点不渲染 ③ 末影箱的水渲染到别的箱子界面），
   前两次靠「服务端下发 `RenderTarget` 权威告知容器身份」解决，第三次靠**派发判 viewer**
   （2026-10-06 修）。⚠️ 同日一度「砍掉末影箱流体兼容」，**当日回退**（误伤了
   `processEnderChest` 这条 tick 入口 ⇒ 末影箱里全部活物品机制失效）。
   方案留痕：`docs/archive/changelog.md（2026-10-06 条目）`。
2. ~~**岩浆/模组流体接入**~~ —— **活熔岩已接入**（2026-10-06，`living-fluid-tech.md` §3.7）：
   一行行为注册（引擎零改动），产物形态已拍板 —— **流动格遇水 ⇒ 圆石、源格遇水 ⇒ 黑曜石**
   （原版 `shouldSpreadLiquid`，与「谁撞谁」无关）。仍挂起：① **倒水进岩浆源 ⇒ 黑曜石**
   （`pour` 处理器路径）② **模组流体接入**（同一行注册，等有具体流体再说）
   ③ **活熔岩 DOWN 特例（落下的岩浆着地变石头）**、**soul soil + blue ice ⇒ 玄武岩**
   （原版有此规则，未复刻）④ **反应音效**（2026-10-10 自 fluid §9 迁入）
3. 满活桶对世界放水（复用 `emptyContents`）**无反馈 UX**（2026-10-10 自 fluid §9 迁入）。
4. **游戏实测清单** —— 漏斗自动化活锁、多流体同屏渲染、创造模式汲/倒、**黑曜石存活窗口
   （≈1.5s）是否够抽走**。
   （活桶世界侧已定稿零拦截：取/放水为原版原生行为，swap 丢活标记属预期，
   重新活化即可——无需专项测试。）
5. ~~**慢蔓延**（flowSpeed）~~ —— **已实施**（2026-10-05 时序化，§3.6：目标/实际双层 +
   派生原版 `getTickDelay`，水 5t / 岩浆 30t）。
6. ~~**岩浆烧毁物品**~~ —— **已实施**为机制一·焚毁（§3.7，含满组石头系喂养 ⇒ 诞生岩浆源的
   「新配方」；源格同样焚毁）。
7. ~~**管道抽取活水源**~~ —— **已实施**（2026-10-06，`living-fluid-tech.md` §10）：新
   `ContainerFluidHandler`（NeoForge `IFluidHandler`，宽注册全部 BE + 四段让位判定），
   **整源单位**（请求 ≥1000 才给满并删源）、只出不进；**Create 6 / Mekanism / Pipez 通用**。
   ⚠️ 已知代价：单次请求 <1000mB 的慢管道抽不到（**明确接受**）。
8. **专用流体活物品**（未来方向，用户 2026-10-06 拍板时的原话：「后面直接出专门的流体相关的
   活物品再说，就像活涂蜡铜灯存电一样的逻辑」）—— 需要"细水长流"的自动化时做**专用物品**：
   自带缓冲与规则，范本 = 红电 `ContainerEnergyStorage`（对外一个能力、内部一个池、
   无缓冲时按请求量扣），**不在通用源能力上开后门**（分数源会污染挤没/晋升/汲走/刷石机/落盘/渲染）。

## 活水车

1. 在航空学载具上，玩家应力无法传输到脚下。（2026-09-22 复核：**仍未解决**）

## 红电（2026-10-10 自 `living-power-tech.md` §0 迁入，需求池统一 D-doc-06）

1. ⏳ **活避雷针**（Step 13：**供需分配** —— 让雷电给容器充电；排期在阶段四后）
   —— 详见 `living-power-tech.md` §8。

## 活红石 / 活铜（2026-10-10 自 tech §0 迁入，D-doc-06）

1. ⏳ **活活塞**（P3：推 / 拉相邻物品）—— `living-redstone-tech.md`
2. ⏳ **活门**（P3：开 / 关可视化）—— `living-redstone-tech.md`
3. ⏳ 铜块物品栏装饰器渲染（纯视觉）—— `living-copper-tech.md`
4. 🔮 活潜影箱集成（信号穿透边界）—— 设计探讨见 `buffer/活潜影箱实现细节.md`

## 容器边界（2026-10-10 记 · 2026-10-18 立项为专题）

> **契约**：[system-design/container-boundary.md](system-design/container-boundary.md)（不变量 I-C1~I-C7，已生效）
> **实施计划**：[buffer/container-compat-topic.md](buffer/container-compat-topic.md)（S1~S5 + 验收判据）
>
> 本条目只留「还没做完的」，不再承载设计正文。

1. ✅ **「白/黑名单」已否**（`D-container-05`）—— 原待决「是否在扫描入口排除『有物品能力但非原版
   `Container`』的模组容器」。结论：**排除 = 放弃兼容**，且黑名单是永久适配债 ⇒ 改为口径收敛
   （`D-container-01` / `D-container-04`）。理由见契约 §0 / §2。
   ⚠️ 不加名单意味着「非 `Container` 容器必须能 tick **且**能同步显示」——后半句当前未实现（I-C5 ⏳）。
2. ⏳ **专题 S2~S5 未开始**（S1 契约 + 守卫测试已完成，守卫 3 项 `@Disabled` 待 S2 解除）。
   范围边界见契约 §4：扫描展开只做深度 1；活潜影箱的功能内部递归独立立项。
3. ⏳ **真机基线待用户测**（用户 2026-10-18 自领）—— 清单见专题计划 §6；
   当前预期 B1/B3/E 组为红（S2 未做），测完把观察填进专题 §5 台账。
4. 🔮 **池端点（长期，未定案）** —— 给 `SlotAccessor` 加「物品池」实现（按类型 extract/insert），
   让活漏斗能对 AE2 ME 接口 / RS 网络推拉。**不做**池宿主（`D-container-07` / I-C8）。
   前置：S3 的 Provider 注册机制落地后可复用同一套注册。

## 活水车 · Create 接触面收窄（2026-10-19 记）

> 背景：`StressStateMachine` 的 javadoc 写着「纯逻辑类，可独立测试」，但**实际无法单测** ——
> 它直接吃 `KineticBlockEntity` 类型，而 Create 只有 `compileOnly`（不在 test classpath 上），
> 所以 `src/test` 里对它零覆盖。这是「编译依赖」真正的代价，比"要不要用反射"更值得处理。

1. ⏳ **抽出 `KineticOps` 接口**（`livingItem$` 前缀，与 `LivingItemStressOutput` 同风格）：
   `getLevel` / `hasNetwork` / `detachFromNetwork`（把 `getOrCreateNetwork().remove(self)` 收成一个操作）/
   `setSpeed` / `setNetwork(null)` / `setChanged` / `sendData` / `getBlockPos`。
   Mixin 侧实现它（那里本来就有 Create 类型），`StressStateMachine` 只依赖接口。
2. ⏳ 收益：状态机变成**纯 Java**，可用 `FakeKineticOps` 单测覆盖
   INACTIVE↔ACTIVE / 自过期 / 区块卸载清理 四条迁移；Create 引用面收敛到 Mixin 一处。
3. ⚠️ **不采用反射**：理由见 [living-water-wheel-tech.md §5.1.1](tech/living-water-wheel-tech.md)。





## 活武器（2026-10-10 自 `living-weapon-tech.md` §0/§9 迁入，D-doc-06）

1. ⏳ **蓄力型**（弓 / 三叉戟）—— **设计已定稿**（免录制方案，2026-09-30），
   设计稿全文见 `buffer/living-weapon-design.md`；**待拍板 4 项**（弹药经济学 /
   回充触发门槛 / 回充时长可配 / Crossbow 一期已定排除）见设计稿
2. ⏳ **PVP** —— 需先定义「敌对关系」判据（现为硬排除所有 `Player`）—— `living-weapon-tech.md` §9

## 活TNT（2026-10-13 记）

1. ⏳ **爆炸规模化重构（未定案设计稿）** —— 堆叠上限 21 亿的多槽容器（抽屉类）塞满活TNT 是
   真实可达的极限场景；现状四道墙（实体伤害巨型 AABB / pendingChecks 突发注入 / schedule 双循环 /
   `chunkCount` int 溢出，阈值 N = 8,588,840,976）+ 求和溢出吞物品。定稿设计 = 惰性谓词 +
   纪元水印（每区块 1 个 long，多重爆炸用持久 epoch 区分，成本与 N 无关）。
   设计稿全文与修单顺序见 [buffer/living-tnt-explosion-design.md](buffer/living-tnt-explosion-design.md)
2. 🔴 **求和溢出吞物品（今天的真 bug，建议先修）** —— `ExplosionComponent.ignite` 的
   `int totalTntCount`：容器内总和 > 2,147,483,647 即回绕；偶数个满栈槽回绕成负数 ⇒
   早退 `return false`，而物品已在同一循环清空 ⇒ **TNT 凭空消失、零爆炸、零日志**。
   修法：求和改 long + 补「两满栈槽点火不丢物品」测试（详见设计稿 §1.4 / 修单 S1）

## 未来活物品（2026-10-04 用户提出，功能池）

> ⭐ 三者**都不是**「新增一个功能」，而是**三台不同的机器**：
> ① ② 是**转化型**（消耗自身 → 改写目标物品，方向相反 ⇒ 一套机制两个朝向）；
> ③ 是**快照型**（活化时记录、取消活化时回放）。
> 记在这里是因为它们把「活化/取消活化这一刻能做什么」的边界顶到了极限 ——
> 该边界的现状分析与待决口径见
> [archive/activation-hook-refactoring-plan.md](archive/activation-hook-refactoring-plan.md)。
>
> 📌 **2026-10-04 已铺好的地基**（活化时机钩子已落地，契约见
> [api-contract.md §1.5](system-design/api-contract.md)）：三台机器的活化/还原**统一走门面
> `LivingItemActivation.apply`**；「玩家缺席」已是合法态（`Player` 可空 + 逐功能降级表），
> 且有**数据安全否决**通道（`onDeactivated` 返回 false ⇒ 不清任何数据）——
> 活箱子无玩家时正是靠它保住 27 格内容。
> ⚠️ **F1 / F2 落地时必须回来重评上下文模型**（是否需要 Context 对象 / 槽位参数）——
> 判据与「现在为什么不做」见该方案 §3.5。

| # | 活物品 | 语义 | 已复用 / 待造 |
|---|---|---|---|
| F1 | **活经验瓶** | **消耗自身**，把目标槽位的**非活**物品**活化**（创造方向） | 朝向 = `HasDirection`（WASD 现成）；范围/过滤 = 容器规则 + 漏斗过滤器 |
| F2 | **活凋零玫瑰** | **消耗自身**，把目标槽位的**活**物品去活化（终结方向，与 F1 严格对偶） | 同上 |
| F3 | **活纸**（蓝图） | **活化时**快照当前容器的**布局 + 内容**（活与非活都记）；**取消活化时**用玩家背包里的物品**还原布局**，并把还原出来的非活物品顺便活化 | ⬛ 快照是**唯一真正要新造**的东西（其余全部可复用） |

**F1/F2 共用机制**（⇒ 实现时必须写成一套、两个方向）：

- 「消耗自身」= 减堆叠数 / 销毁，**朝向决定消费方向**（F2 ⇒ 凋零玫瑰原版语义是"凋零"，对得上）
- 目标槽位的圈定：全容器？玩家背包？跨容器？周围 N 格？⇒ 走 `ContainerRuleConfig` 的容器黑白名单 +
  `TransferPipeline` 的槽位过滤，**不要新造第三套过滤**
- ⚠️ **待拍板**：批量 `setLiving` 时**要不要派发各功能的活化钩子**（活末影箱要不要顺手绑定玩家？活纸还原出来的物品没有玩家上下文）
- ⚠️ **待拍板**：F2 去活化时，活箱子**已存物品要不要掉地**（否则数据静默消失）、活末影箱要不要清绑定

**F3 的四个硬问题**（想清楚再动手）：

1. **活按钮激活时物品在鼠标上（`carried`），不在任何槽位** ⇒「当前容器的布局」在活按钮路径上
   **没有定义**。要么改交互模型（槽位旁的按钮），要么明确规定「只在背包/箱子槽位内可激活」
   （与 [archive/activation-hook-refactoring-plan.md](archive/activation-hook-refactoring-plan.md) 同一个根问题）
2. **快照存哪** ⇒ 必须存**物品自身**（组件），才符合「状态跟随物品跨容器迁移」的既定原则；
   但容器 27 槽 × 物品 NBT 的**体积是硬约束**（要定上限与截断策略）
3. **还原的耗损** ⇒ 背包里材料不够时：部分还原？全不还原？「尽量还原 + 报告缺料」需要定
4. **还原时布局已变**（别人在容器里放过东西）⇒ 以快照覆盖，还是只填空位

