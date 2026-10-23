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

## 容器边界（2026-10-10 记 · 2026-10-18/19 立项并完成主体）

> **契约**：[system-design/container-boundary.md](system-design/container-boundary.md)（不变量 I-C1~I-C8，已生效）
> **实施记录**：[buffer/container-compat-topic.md](buffer/container-compat-topic.md)
>
> 本条目只留「还没做完的」，不再承载设计正文。

1. ✅ **「白/黑名单」已否**（`D-container-05`）—— 排除 = 放弃兼容且是永久适配债 ⇒ 改为口径收敛。
2. ✅ **S1~S4 + S3/S3b 全部完成，真机全通过**（2026-10-19）：
   tooltip 进度行 · 活熔炉图标实时 · 写回不错槽/不复制 · 列数 108/120 正确算 12 ·
   背包槽位倒汲生效 · 背包 GUI 活流体渲染。
3. ✅ **S5 已取消（`D-container-08`）** —— 「容器内物品被 tick」是**活潜影箱的专属逻辑**，
   不是框架的通用能力。框架职责收敛为：tick「方块形态容器」+「实体形态容器」+「玩家背包」
   （实体形态 2026-10-21 加入，见 `D-container-10`）。
   ⇒ 想做「物品形态容器内部运转」，去做 [活潜影箱](buffer/活潜影箱实现细节.md)，不在此专题。

   **已完成的相邻项（与本条无关，保留记录）**：
   - ✅ **跨形态数据保全**（`ContainerDataMigration`）：流体保持「容器属性」语义不变，
     只换存储介质 —— 破坏时 `BlockDropsEvent` 把 BE 附件搬进掉落物组件，
     放置时 `EntityPlaceEvent` 搬回新 BE。覆盖精妙背包 / 原版潜影盒。
     ⚠️ 双箱：迁移后必须清两半附件，否则两次破坏各得一份 = **复制**。
     ⚠️ 放置侧必须校验「手持物品 == 本次放置的方块」，否则手持带数据的背包去放别的方块会错塞。
   - ✅ **精妙的「拾取 + 放置」双向已修（2026-10-21，真机日志驱动）** —— 精妙**两个事件都不给**：
     - 拾取 `putInPlayersHandAndRemove`（`setItemInHand` + `removeBlock`）不产生掉落物
       ⇒ 无 `BlockDropsEvent`；
     - 放置 `BackpackItem.tryPlace`（**不是 `BlockItem`**，自己 `setBlockAndUpdate`）
       ⇒ 无 `EntityPlaceEvent`；且方块物品与 `diamond_backpack` 是两个不同 item，
       `placedItemInHand` 的 `asItem()` 判据也认不出。
     修法：**双向交接**（`ContainerHandoff`）+ 联动模块两个 Mixin
     （`BackpackBlockMixin` 挂 `putInPlayersHandAndRemove`、`BackpackItemMixin` 挂 `tryPlace`，
     各 HEAD/RETURN），搬运逻辑复用事件路径那份 `moveOut`/`moveIn`。
     ⚠️ **暂存必须 copy**：`tryPlace` 末尾 `shrink(1)` 会就地改手持栈。
     ⚠️ 原计划的「`RightClickBlock` HEAD 暂存 → 同 tick 末检查手上出现同类物品」**未采用**：
     它需要「认领」判定，而错认领（手上本来就有同款背包）比丢失更难查；Mixin 直接落在
     具体方法上，零认领。代价是软依赖第三方类（`require = 0` + 资源探测门控 +
     方法缺失时 ERROR 日志，见 `D-container-12`）。
     🔴 **真凶（第 4 轮才找到）**：注册写成 `registerMover(ContainerDataMigration::moveOut)`，
     而 `Mover.moveIn` 是 **default 空实现** ⇒ 方法引用只覆盖 `moveOut`，**放置方向静默失效**。
     修法：去掉 default（漏实现编译期报错）+ 反射守卫 `mover_hasNoDefaultMethods`。
     **通用教训**：注册点用方法引用时，接口里不能留「可选实现」。
     ✅ **真机通过（2026-10-21 深夜，用户）**：蹲下拾取 / 左键破坏成掉落物 / 放置 —— 三条路径流体都在。
     ⚠️ **方法论教训**：`libs/` 里没有精妙 jar（只有 `libs/src` 源码快照）⇒ 注入点从未在
     运行环境验证过；本次是靠真机日志（三处埋点）才定位到「放置侧不走标准路径」。
     以后接第三方钩子，先要日志确认「注入成功 + 数据走到了哪一步」，再谈修法。
   - ✅ **「只写附件无效」—— 容器级数据有两个副本（2026-10-21 真机定位，比 mixin 更根本）**：
     内存表 `CONTAINER_DATA`（按 cacheKey）是**唯一真相源**，`ContainerFluidWriteback` /
     `ContainerPhaseWriteback` **每 tick 用内存表覆盖附件** ⇒ 放置时只写附件 = 下一 tick 被空对象盖掉。
     ⚠️ 以前「破坏再放回」偶尔能成，是因为 `getOrCreateFluidData` 只在内存表**没有该键**时才走
     「附件回填」：换位置放置（新 cacheKey）能成，**同位置**破坏再放置（旧空对象还在）就丢。
     **这个 bug 与精妙无关，是所有容器放置路径共有的**。
     修法：`moveIn` 用**同一个对象**同时写内存表 + 附件；相位额外 `PhaseSnapshot.restoreInto` 还原进账本。
   - ❌ **已否：隐形占位物（让槽位不空）** —— 会砸掉转化机制：`transformSourceItems` 依赖
     「**源格 + 物品同格**」（水+某物品→产物），`incinerateFlowItems` 同理。槽被占住 ⇒
     转化 / 焚毁 / 源诞生全线失效。代码注释已明写「派生源与物品同格，供后续机制三转化」是刻意设计。
     ⇒ 「空槽能不能存东西」这个方向：**不能，也不该**——源格必须保持可放物品。

3.1 🔴 **大箱子的相位可能各得一份**（2026-10-20 发现，未修）——
   `ContainerPhaseWriteback` 每 tick 把**同一个** snapshot 写给所有关联 BE，
   所以大箱子两半的附件指向同一对象。破坏一半时只能清「那一个 BE」的引用
   （`BlockDropsEvent` 拿不到另一半的 BE），另一半被破坏时会再搬一次 ⇒ **两半各得一份**。
   流体那边靠 `data.clearAll()` 就地清**内存共享对象**守恒，相位没有这个载体
   （`PhaseSnapshot` 是不可变 record）。
   两个候选修法：① 让 writeback 只写给一个 BE（另一半读时回退到内存账本）；
   ② 破坏时用「另一半是否还在」判先手（先破的搬、后破的不搬）—— 与流体的语义对齐。
   ⚠️ 极端边缘场景（大箱子 + 多相电力 + 分两次破坏两半），故排在其它项之后。

3.2 🔮 **客户端入口下沉**（2026-10-20 记）—— `LivingItemClient` 在 `com/qiqi/li/` 根目录，
   引用 7 个 `client/render` 渲染器 ⇒ `check_layers.py` R1 违规（已 `--update-baseline` 显式接受）。
   彻底消除的办法是把入口类移进 `client/` 包（项目里 `client/` 下的
   `@EventBusSubscriber` 本就能被事件总线发现），但那要动 mod 入口组织、
   **只能真机验证** ⇒ 先记录。

3.3 ⚠️ **`ContainerFluidData` 当数据组件值违反 NeoForge 约定**（2026-10-21 写单测时暴露）——
   `ItemStack.set(CONTAINER_FLUID_DATA_ITEM, data)` 在 NeoForge 的数据组件校验下抛
   `IllegalArgumentException: Data components must implement equals and hashCode`。
   校验由 `CommonHooks.validateComponent` 执行、**只在开发开关下启用** ⇒ 生产环境「能跑」，
   但属于**未定义行为**：组件被当作不可变值参与 `ItemStack.matches` / 网络同步 / 缓存判等，
   而 `ContainerFluidData` 每 tick 重算 flows（可变对象）。
   影响面：破坏/放置迁移、GUI 拖拽时的物品比较、创造模式连续放置。
   修法方向：给组件值做**不可变快照类型**（与 `PhaseSnapshot` 同构的 record，CODEC 同源复用），
   或把 `ContainerFluidData` 改成不可变 + 每 tick 换新对象（成本更高）。
   ⇒ 未定案。真机尚未观察到异常（校验未开），但这是「不该存在的状态」。

4. ♻️ **「客户端列数探测」前提已错，已被设计稿取代**（2026-10-21 更正）——
   见 [buffer/container-menu-introspection-topic.md](buffer/container-menu-introspection-topic.md) §1.1/1.2：
   `Slot.x/y` 是**服务端就有**的信息（`addSlot` 双端跑，写进 `Slot` 对象），
   两端各自构造同一套布局 ⇒ 坐标天然一致、不走网络。
   ⇒ 正确做法是「**服务端读自己的菜单**」，不需要客户端上报，信任问题从根上消失。
   下面的原记录保留作废过程。

   <details>
   <summary>已作废的原记录（2026-10-20）</summary>


   B 类活物品（熔炉 / 漏斗 / 酿造 / 信标 / 耕地 / 红石全家桶 / 蜡镀铜电力 / 流体，共 8 个域）
   的兼容性**完全**取决于规则表的列数覆盖；未收录的模组容器只能靠启发式
   （整除 + 常见宽度排序），猜错时这 8 个域一起失效。症状是分裂的：
   熔炉「不烧」（可见）、漏斗「搬错格子但仍在搬」（隐形）——同一偏差，两种可见度。

   **为什么服务端探测不可行**：列数是**客户端属性**。`Container` 接口只有一维
   `getContainerSize()`，BE 眼里没有行列；布局只以 `Slot.x/y` 像素坐标的形式存在于
   GUI 层（原版靠菜单类里的硬编码常量，如 `ChestMenu` 的 `k + j * 9`）。
   服务端能观察到的（能力方向性、`canPlaceItem` 分区）都是**语义**分区，不按行分，
   与行宽没有稳定对应 ⇒ 纯服务端猜不可靠。

   **三层，从可靠到兜底**：① 规则表（显式数据，要人录入）→ ② **客户端探测**
   （按 `Slot.y` 分组取众数——这不是猜，是读数）→ ③ 启发式（无客户端信息时兜底）。

   **实施分两步**
   - **第一步（先做，小）**：`/livingitem container probe` —— 玩家打开目标容器后敲命令，
     客户端算列数 → 玩家确认 → 写入玩家配置（`USER_RULES`）。
     复用现有命令框架与 `USER_RULES` 持久化，**不新增网络协议、无信任问题**。
   - **第二步（大）**：打开容器时静默自动上报 + 服务端缓存。要处理新协议、缓存清理时机、
     恶意值校验（至少 `size % columns == 0`，否则拒收）；且「首次打开前」仍靠启发式
     ⇒ 那一 tick 不烧、之后自愈。

   **已知边界（所以 ② 永远不能取代 ①）**
   - 不规则布局（L 形、格子大小不一、中间空行）⇒ 分组众数可能不是行宽
   - 从没打开过的容器 ⇒ 探测不到，回落启发式
   - 熔炉这类**非矩形**容器（`AbstractFurnaceMenu` 的三个槽是语义常量 `INGREDIENT/FUEL/RESULT`
     而非坐标）证明「行列」不是通用概念 ⇒ 我们的 `RECTANGULAR_STANDARD` 本来就是近似

   验证手感建议：先用炼金箱子（104 格 13 列，启发式碰巧对）与铁箱子大箱（144 格 18 列），
   一个「对但不知为何对」、一个「明确错」，最容易看出探测的成色。

   </details>

4.1 ✅ **实体容器已入门**（2026-10-21 落地，设计稿见上）—— 箱子船 / 箱子矿车 /
   漏斗矿车 / 带箱驴骡现在都 tick：键 `entity_<uuid>`（每 tick 从 `ServerLevel#getEntity`
   反查**实时**位置 ⇒ GUI 开着船在漂也刷新）、容器级数据（流体/相位）挂实体附件
   （NeoForge 附件持久化 ⇒ 区块卸载再回来数据还在）、白名单
   `ContainerEntity` + `AbstractChestedHorse#hasChest()`。
   ⚠️ **不走位置广播**：活信标光束在实体容器里本就不开光（`LidBlockEntity` 判据，
   三实现全是 BE）⇒ 唯一候选需求证伪，设计稿 §0.1。
   ⚠️ **无人区块停 tick 但不丢数据**：超出 simulation distance 停（与 BE 卸载 / 掉落物 /
   玩家下线三条既有路径一致），回来接着烧。
   ✅ **真机已验证并修正驴的槽位布局**（2026-10-21 下午）：原版 `AbstractHorse` inventory 是
   **16 槽 = 索引 0 马鞍 + 索引 1..15 箱子格（5×3）**（`INV_SLOT_SADDLE = 0`，
   `HorseInventoryMenu` 的箱子格从 `1 + l + k*columns` 起）。不排除鞍槽的后果：
   启发式 `16 % 8 = 0` ⇒ **8 列**（真机「8×2」）+ 往鞍槽倒水得到玩家取不出的幽灵物品。
   修法：`EntityContainerContext` 的 `SADDLE_OFFSET`（size 减 1、getItem/setItem 加偏移、
   `getWidth` 问原版 `getInventoryColumns()`）。船/矿车无装备槽 ⇒ offset 0 直通。
   ⚠️ 驴的菜单解析最终走**入口层反射**（Mixin accessor 被包隔离否掉，见 changelog）。
   ✅ **第二处真机修正**：加偏移后「渲染和逻辑差了一格」⇒ 引入 `toPhysicalSlot`/`toLogicalSlot`
   一对互逆映射，并在 5 条跨边界通道上强制过它（槽位同步 / 遥测键 / 作废键 / 流体渲染包 /
   GUI 倒汲与信标喂食）。**新增前置非存储槽时必须一次性枚举所有跨边界点**。
   ✅ **真机复验闭环**（2026-10-21 晚）：驴 GUI 5×3 对齐、tooltip 逐格对应、倒水落进所点格子。
   实体容器这条线（`entity_<uuid>` 键 → 菜单自省 → 鞍槽偏移 → 双口径贯通）不再有未验项。

5. 🔮 **池端点（长期，未定案）** —— 给 `SlotAccessor` 加「物品池」实现（按类型 extract/insert），
   让活漏斗能对 AE2 ME 接口 / RS 网络推拉。**不做**池宿主（`D-container-07` / I-C8）。
   前置：复用 `MenuSlotOwners.registerResolver` 同一套注册。

6. ✅ **活背包：精妙背包作为活物品功能（2026-10-22 立项 → 10-23 实施并真机验收）** ——
   现象是「精妙背包打开 GUI 里面流体层不渲染」，但**根因不是数据丢失**（数据在物品组件上，
   双向交接已修好），而是**这东西根本不活**：没人在 tick 它 ⇒ 没有 ctx、没有容器级数据、
   渲染/tooltip/倒汲/演化全线无输入。这不是精妙独有（潜影盒 / mod 背包同缺）。
   **正确定位**：活背包 = **一个活物品功能**（`LivingBackpackFunction`：`canApply` 精妙背包+IS_LIVING，
   `tick` 里对每个背包构建内部 ctx 并 `processContext` 递归）⇒ **框架零改动**，
   `scanAndGroupLivingItems` 已经会把它当活物品扫出来。活化门槛顺带解决性能与判定。
   设计稿：[buffer/living-backpack.md](buffer/living-backpack.md)（含四轮纠偏留档）。

   **已落地**（8 个 commit，`d452997` → `6bcd5a2`）：地基四步（`persist` 钩子 + key CODEC /
   UUID 组件 + `cuuid_` 键形 + `ContainerDataSavedData` / 身份优先的形态迁移 /
   `LivingBackpackFunction` + 物品形态 ctx + 精妙联动）+ #6 菜单解析 + #7 viewer 匹配。
   接入清单 11 条线接通 9 条：✅1 ✅2 ⚠️3 ✅4 ✅5 ✅6 ✅7 ⚠️8 ✅9 ❌10 ✅11。

   **✅ 真机验收（2026-10-23 用户实测）**：两个活背包倒汲 / 关界面不再刷第三方 ERROR /
   储罐插件动态列数（各尺寸含方块形态）/ 活漏斗高级 tooltip 容器信息 —— 四项全通。

   **剩余缺口**：⚠️3 列数（`getWidth()` 走启发式，27/45/54 得 9 正确，含升级槽的 30 会得 10；
   要彻底解决需让 `ItemFormContainers.Source` 提供权威列数）· ⚠️8 客户端同步（部分）·
   ❌10 世界语义（已定不做，归活潜影箱）。
   **未定案 2 项**：嵌套上限放开时机（写死 1，等活潜影箱立项）· handler 获取开销（要实测）。

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

