# 待办 / 已知问题

> **性质**：随手记的待办池，**不是**规格文档。已完成项**直接删掉**（别留「已完成」清单 ——
> 那是 changelog 的职责）；本文件只放**还没做的事**。
> 上次复核：2026-10-04。

## 文档

1. `docs/tech/common/recipe-book-tech.md` **需按现架构重写**（2026-10-03 发现）：
   全文以**已删除**的 `LivingChestContentsCache`（全局缓存 + `dirty` 脏标记）为前提，
   现为 `RecipeBookComponentMixin.collectLivingChestItems()` **每帧扫描玩家背包**。
   已加勘误横幅，**正文未改写** —— 重写前需先完整梳理现实现。

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

## 活流体（流体侧挂起项，细节以 [idea.md](idea.md) 任务队列为准）

1. **末影箱汲/倒** —— 框架侧 F-1 **已完成**（2026-10-05：`ContainerContexts.resolve` 加末影箱分支，
   `EnderChestContainerContext` 迁出为独立类）；**待流体侧补 `FluidFlowServerSync.flushAfterTick`
   第三条派发**（末影箱 `player` 直发）即通。
2. **岩浆/模组流体接入** —— 一行行为注册 + 转化表岩浆条目（`consumeSource` 视语义）+
   跨流体反应表。原版规则与架构范本已存档
   （[living-fluid-tech.md](tech/living-fluid-tech.md) §3.4/§3.5：反应注册表化，
   两条路径共用一表，**不碰引擎**）。
   **待拍板**：① 产物形态（黑曜石物品 / 圆石 / 纯湮灭）② 覆盖范围（仅倒水路径 / 全接触面）。
3. **游戏实测清单** —— 漏斗自动化活锁、多流体同屏渲染、创造模式汲/倒。
   （活桶世界侧已定稿零拦截：取/放水为原版原生行为，swap 丢活标记属预期，
   重新活化即可——无需专项测试。）
3. **慢蔓延**（flowSpeed）—— 挂起：原版参数已存档（tickDelay 30 / dropOff 2 /
   晋升仅下界，`living-fluid-tech.md` §3.4）；引擎需从纯函数重算转带记忆模拟
   （1b-1 以来最大架构变更），**仅当实测证明岩浆瞬时手感违和时立项**。
4. **岩浆烧毁物品** —— 独立风味项（岩浆源格非活物品周期性摧毁，与水浸泡转化对偶），挂起。
5. **管道抽取活水源** —— 设计已存档（[living-fluid-tech.md](tech/living-fluid-tech.md) §10，
   红电 `ContainerEnergyStorage` 为范本：消耗源 + 晋升再生 = 无限水工业化），待实现。

## 活水车

1. 在航空学载具上，玩家应力无法传输到脚下。（2026-09-22 复核：**仍未解决**）

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

