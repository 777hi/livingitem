# 待办 / 已知问题

> **性质**：随手记的待办池，**不是**规格文档 —— 也是**全项目待定计划的唯一登记处**
> （2026-10-10 拍板 `D-doc-06`：tech 文档 §0 不再维护「未实现」清单，计划统一到这里）。
> 已完成项**保留删除线 + 完成日期**（留痕「做过什么、为何算完成」，不整段删）；
> 未定案设计稿的**正文**放 `docs/buffer/`，这里只放一行 + 指针。
> 上次复核：2026-10-10。

## 审查整改（2026-10-29）

1. 全项目审查（四路代码审查 + 门禁/构建体检）的问题台账与分批实施步骤见
   [2026-10-29-全项目审查整改计划.md](buffer/2026-10-29-全项目审查整改计划.md)。
   **进度以该文档 §8 为准**，本页不重复列条目；需要拍板的 5 项在该文档 §7。
2. 测试域专项梳理（测试分布 / 覆盖矩阵 / 接缝与不变量缺口 / 分层建议）见
   [2026-10-29-测试域梳理.md](buffer/2026-10-29-测试域梳理.md)。
   **测试相关的取舍以该文档 §8 为准**（test-map 计数、known-gap、IT 入口、tag 依赖、性能测试）。

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

## 配方书活箱子标签页 · 游玩实测（2026-10-29 重构收尾挂起）

1. **拆出 mixin 后的游戏内烟测** —— 渲染 / 点击路径单测不可覆盖，重构虽为纯搬移
   （行为零变化，三处历史怪癖原样保留），仍需过一遍：
   打开配方书 → 切活箱子标签（默认仍停原版标签页）→ 网格显示与悬浮提示 →
   搜索（拼音全拼 / 首字母缩写 / 英文 / 空）→ 翻页（页码与首末页禁用态）→
   左键空手取出一组 / Shift 全部取出入背包 → 右键取半组 / Shift 取 1 →
   手持物品左键全部存入 / 右键存 1 → 点击原版标签 / 搜索框外的原版区域自动退出 →
   活箱子物品在合成表显示可制作（材料统计）。
   架构与约束见 [recipe-book-tech.md](tech/common/recipe-book-tech.md)；发现问题按惯例落 changelog。

## 性能（2026-10-03 盘点）

> 前提：设计目标是**大型整合包跑图** —— 容器数量按**上万**估算。
> 「每 tick 每单位一次分配」这类开销在 100 容器时无所谓，在 10000 容器时会累积成主要开销。

1. ~~**战利品箱子「半忽略」**~~ ✅ 已修 2026-10-30（R-04）——
   发现阶段（`ContainerChunkCache.scanChunkForContainers`）不检查战利品表，
   处理阶段（`LivingItem.processChunkContainers`）才检查。
   现行行为：未开箱的战利品容器**不处理但保留区块**（`hasContainer = true`），
   开箱后 `getLootTable()` 变 null ⇒ 下一 tick 自然按普通容器处理。
   代价：纯战利品箱子区块常驻缓存，每 tick 每个战利品 BE 做一次 `getLootTable()` 检查。
   ⚠️ 不能改回「发现阶段加同一行检查」：那样区块永远不进缓存，开箱后更收不回来。
   剩余可选优化：开箱触发重扫（需新机制，未定案）。

2. **`getProcessableChunks` 每 tick 新建 Set**（大小 = 缓存区块数）——
   10000 区块时每 tick 一个万级 `ObjectOpenHashSet`。修法：双缓冲或复用。

3. **`processLevelContainers` 每区块 `new ArrayList<>(chunk.getBlockEntities().values())`** ——
   10000 区块时每 tick 一万个 ArrayList（防御性拷贝）。

4. **`scanAndGroupLivingItems` 每容器 `new LinkedHashMap<>()`** —— 同上量级。

5. **（收益最大）合并两次全槽位遍历** —— `scanAndGroupLivingItems` 与
   `computeContentSignature` 各遍历一遍全部槽位 ⇒ 合并可**省 50% 扫描**。
   详见 [infrastructure-refactoring-plan.md](archive/infrastructure-refactoring-plan.md) §1.7 / §6.3-B。

6. **配方书标签页每帧扫描玩家背包**（2026-10-03 起）——
   `LivingChestRecipeTab.collectLivingChestItems()`（原 `RecipeBookComponentMixin` 内联）
   在内容网格渲染帧与材料统计路径各扫一遍背包全部槽位。
   只在标签激活时才渲染 ⇒ 未开标签零开销；激活时的量级 = 背包 36 槽 × 判活检查。
   修法（若将来要省）：按内容签名缓存或仅背包变更时重扫。

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
   ⚠️ **2026-10-29 用户拍板：随活海绵落地退役**（无海绵时代的探索实现）——流体能力
   **收口到活海绵物品**（唯一能力面），容器水网不再暴露流体能力，模组管道改经
   「海绵 → 储罐 → 管道」取水；「整源单位」口径保留（网络源永远整源）。
   见 [活海绵.md §2.3](buffer/活海绵.md) · fluid tech §10 退役横幅。
   🔴 **2026-10-31 勘误**：「Create 6 通用」当时只核了 API 方言，**没核 Create 的连接判定**
   —— 宽注册让管道把相邻**管道自身**判成储液，整张网络断在假 tank 上（水完全不流，
   且任意 BE 长排液接口）。现已改为「**能力存在性 = 派生源非空**」+ 驱动侧边沿失效
   （fluid tech §10.4）；整源口径与退役计划不变。
8. **专用流体活物品**（未来方向，用户 2026-10-06 拍板时的原话：「后面直接出专门的流体相关的
   活物品再说，就像活涂蜡铜灯存电一样的逻辑」）—— 需要"细水长流"的自动化时做**专用物品**：
   自带缓冲与规则，范本 = 红电 `ContainerEnergyStorage`（对外一个能力、内部一个池、
   无缓冲时按请求量扣），**不在通用源能力上开后门**（分数源会污染挤没/晋升/汲走/刷石机/落盘/渲染）。
   📄 **设计稿（2026-10-29 立稿，探讨中）**：**活海绵**（流体存储，照抄灯泡存电模型）与
   **活滴水石锥**（流体运输，方向阀）——两个**独立**活物品（相互无搭配依赖），
   见 [活海绵.md](buffer/活海绵.md) / [活滴水石锥.md](buffer/活滴水石锥.md)。
9. **推动与细节待梳理清单**（2026-10-30 建；2026-10-31 力场模型已消化「分叉 / 末端 / 鞍部」
   四条）—— 剩余 10 条（写入失败丢物品 / 合并上限 / 准入 / 源格不推 / 同拍互斥 /
   文档与测试债…），**逐条拍板后再动代码**：
   [buffer/living-fluid-review.md](buffer/living-fluid-review.md)。
10. **流体推动 ×「本拍已写入槽」的互斥（C1，2026-10-31 记录，暂不实施）** ——
    现状：`TickContext.transferredTargetSlots` 由漏斗 `markTransferred()` 写入，
    **唯一消费者是漏斗自己**（`TransferPipeline` 跳过「本 tick 已被写入过」的来源槽，
    防 A→B→C 同 tick 级联）；流体推动完全不看它 ⇒ 漏斗本拍落料，**同 tick 就被水冲走**。
    接入方案：`ContainerFluidData.tick` 增加 `Set<Integer> frozenSlots` 参数，
    `LivingFluidFunction` 传 `tick.transferredTargetSlots`；`pushFluidItems` 循环里
    `if (frozen.contains(slot)) continue`（保留 `tick(ctx)` 重载给测试）。
    已知边界：该集合是「本 tick 被写入过」而非「新物品」⇒ 漏斗**每 tick 连续供料**的槽
    会**永远**被跳过（物品堆着不动）；若要「只保护第一拍」，需按槽记录上次写入 tick。
    顺带：`TickContext.occupiedSlots` 全项目**零引用**（死字段，注释却写「防止多个活熔炉
    处理同一输入槽位」），要么删、要么补使用。
    判据：漏斗落料当拍不被水流移动；连续供料时行为需拍板。

## 活水车

1. 在航空学载具上，玩家应力无法传输到脚下。（2026-09-22 复核：**仍未解决**）

## 红电（2026-10-10 自 `living-power-tech.md` §0 迁入，需求池统一 D-doc-06）

1. ⏳ **活避雷针**（Step 13：**供需分配** —— 让雷电给容器充电；排期在阶段四后）
   —— 详见 `living-power-tech.md` §8。
2. ⚠️ **对外能量能力同款「宽注册 + 恒发实例」（2026-10-31 记，未修）** ——
   `ContainerEnergyStorage.resolveProvider` 给**所有** BE 返回实例（无灯 ⇒ 0 电量），
   而线缆类模组按「能力是否非 null」判储能 ⇒ 会把箱子/机器都当储能接口
   （本模组没有 Create 能量面，故无人报；流体那边是同款缺陷，已修）。
   修法照抄 fluid tech §10.4：存在性 = 容器当前真有活铜灯（`BulbBank.isBulb`），
   并在红电 tick 做存在性边沿 `invalidateCapabilities`。触发条件：出现线缆误连报告。

## 活红石 / 活铜（2026-10-10 自 tech §0 迁入，D-doc-06）

1. ⏳ **活活塞**（P3：推 / 拉相邻物品）—— `living-redstone-tech.md`；📄 设计稿（2026-10-31
   v1.3）：[活活塞.md](buffer/活活塞.md)（头格纯视觉 / 推的距离 = 堆叠数 / 推拉永不合并 / 活物品可推 +
   漏斗联动 = 涌现（隔离无例外） / 跨容器推 §2.7——v1 范围待拍板 ⑥）
2. ⏳ **活门**（P3：开 / 关可视化）—— `living-redstone-tech.md`
3. ⏳ 铜块物品栏装饰器渲染（纯视觉）—— `living-copper-tech.md`
4. 🔮 活潜影箱集成（信号穿透边界）—— 设计探讨见 `buffer/活潜影箱实现细节.md`

## 物品传输（跨子系统，2026-10-31 记 · 未排期）

1. **「移动 = 写目标 + 清源」没有事务语义** —— 同族表现：
   **A1**（流体推动写入失败仍清源槽 ⇒ 物品消失）· **A2**（合并按物品上限而非槽位上限
   ⇒ 丢数量）· **R-03**（末影箱回滚余量丢失，已修：只回原槽 + ERROR 日志）·
   **R-13**（活箱子 `insertItem` 余量从原栈算 ⇒ 复制，已修）。
   共同根因：**先写、再无条件扣源**；`ContainerContext.setItem` 是 void，
   无法表达「写失败 / 部分写」。
   根治三步：
   ① **止血**：A1 写后**回读目标槽**（内容/数量不符 ⇒ 不清源槽 + WARN）；
   A2 合并改用**槽位上限**（对齐 R-13 口径）。
   ② **收敛**：抽唯一移动原语 `move(from, to, amount)` —— `insertActual` 取实际写入量，
   **只扣实际写入量**；把流体推动 / 漏斗 / 末影箱 rollback / 活箱子 insert /
   熔炉与酿造输入输出全部改为调它（约 8~12 处调用点）。
   ③ **契约化**：给 `ContainerContext` 加 `ItemStack insertItem(slot, stack, simulate)`，
   让「实际写入量」成为 API 契约，而不是靠回读猜。
   判据：**守恒不变量** —— 任意移动前后 `源数量 + 目标数量` 不变；参数化覆盖
   「目标空 / 满 / 同类 / 异类 / 拒绝写入」，跑所有容器实现。
   边界：第三方容器违约（`insertItem` 撒谎、`getSlotLimit` 撒谎、`setStackInSlot`
   静默失败）**无法根治** ⇒ 目标是「可控路径不丢 + 丢必留痕」，不是「永不丢」。
   📄 待梳理条目：[buffer/living-fluid-review.md](buffer/living-fluid-review.md) A1/A2。

## 容器边界（2026-10-10 记 · 2026-10-18/19 立项并完成主体）

> **契约**：[system-design/container-boundary.md](system-design/container-boundary.md)（不变量 I-C1~I-C11，已生效）
> **专题结项总览**：[system-design/container-overview.md](system-design/container-overview.md)（七问 + 还剩什么）
> **阶段实施记录**：[archive/container-compat-stages.md](archive/container-compat-stages.md)（S1~S9 验收判据）
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

3.4 🔮 **实体容器的列数校准入口没做**（2026-10-26 记）—— `/livingitem container register` 与
   `inspect` 都按**准心方块**解析，船 / 矿车 / 带箱驴骡无法校准。原版四类已有明确布局
   （`getInventoryColumns()` / 固定 9 列 / 5 列），**特殊第三方容器优先专门联动**，
   不因界面有按钮就承诺支持。触发条件：出现「原版布局被模组改动、且启发式算错」的实体容器。

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

5.1 ⏸ **槽位解析收编（S1b/S2 暂缓，不做）** —— 自动学习已独立落地，不代表恢复合表或 provenance 重排；同键多布局仍单独讨论。历史方案见 [container-layout-resolution.md](buffer/container-layout-resolution.md)，现状口径见 [基础设施 §6](system-design/living-item-infrastructure.md#6-容器大小自动解析)。

5.2 ✅ ~~**容器布局解析审查交接**~~（2026-10-25 两轮已处理）—— 准入端点漏洞 / 歧义失败重试 / 矛盾输入失效、门禁独立回归与现有诊断同源均修复并负向验证；不恢复 S1b/S2、不引入完整 Layout。记录见 [专题 §10.5](buffer/container-layout-resolution.md#105-第二轮审查闭环2026-10-25用户批准实施)。

5.3 ✅ ~~**活按钮显示当前容器基本信息**~~（2026-10-26 已实施，**用户真机总体反馈良好**）—— 已定位并修驴无 MenuType 查询失败，原版末影箱/车船明确布局；2026-10-28 全面实测通过。现状与验收入口见 [GUI 交互 §6.2.2](system-design/gui-interaction-system.md#622-活按钮当前容器基本信息2026-10-26)，设计记录见 [living-button-container-info.md](archive/living-button-container-info.md)。

5.4 ✅ ~~**布局选择共同入口与内建来源收口**~~（2026-10-26 已实施）—— 各形态通过同一实例/规则/学习/内建/启发式顺序，子类只给事实；方块规则范围不扩、家族语义保留，不恢复S1b/S2。现状见 [基础设施 §6.2](system-design/living-item-infrastructure.md#62-simplecontainercontextgetwidth-的宽解析)，过程见 [布局专题 §10.6](buffer/container-layout-resolution.md#106-共同布局选择入口2026-10-26)。

5.5 🔮 **自动学的两条硬局限**（2026-10-25 查证，2026-10-26 记入待办）—— ① 客户端 Screen 可任意重排
   `Slot.x/y`（fxntstorage 服务端一横排 / 客户端 12 列 + 竖向滚动）⇒ 学到的未必是玩家所见；
   ② 拿不到容器身份就学不了（NeoForge `SlotItemHandler` 的 `container` 是共享静态空容器）
   ⇒ fxntstorage 全部 + twilightforest 三件（MasonJar / DryingRack / ChiseledCanopyBookshelf）
   落在启发式。要治得逐模组注册菜单解析器（约 10 行/模组）。
   触发条件：真机出现「规则表没收录 + 启发式算错」的具体容器。口径见 [容器七问 §2](system-design/container-overview.md)。

5.6 ⚠️ **真机台账稀疏**（2026-10-26 记）—— 新形态接入清单 11 条的验收多数只有**替身测试**，
   真机只覆盖了精妙背包一条线（方块 + 物品形态）。替身证明「我对第三方行为的**理解**被守住」，
   真机才证明「理解**是否成立**」。触发条件：接入下一个新形态容器时，至少按清单跑一遍真机。

6. ✅ **活背包：精妙背包作为活物品功能（2026-10-22 立项 → 10-23 实施并真机验收）** ——
   现象是「精妙背包打开 GUI 里面流体层不渲染」，但**根因不是数据丢失**（数据在物品组件上，
   双向交接已修好），而是**这东西根本不活**：没人在 tick 它 ⇒ 没有 ctx、没有容器级数据、
   渲染/tooltip/倒汲/演化全线无输入。这不是精妙独有（潜影盒 / mod 背包同缺）。
   **正确定位**：活背包 = **一个活物品功能**（`LivingBackpackFunction`：`canApply` 精妙背包+IS_LIVING，
   `tick` 里对每个背包构建内部 ctx 并 `processContext` 递归）⇒ **框架零改动**，
   `scanAndGroupLivingItems` 已经会把它当活物品扫出来。活化门槛顺带解决性能与判定。
   设计稿：[archive/living-backpack.md](archive/living-backpack.md)（含四轮纠偏留档）。

   **已落地**（8 个 commit，`d452997` → `6bcd5a2`）：地基四步（`persist` 钩子 + key CODEC /
   UUID 组件 + `cuuid_` 键形 + `ContainerDataSavedData` / 身份优先的形态迁移 /
   `LivingBackpackFunction` + 物品形态 ctx + 精妙联动）+ #6 菜单解析 + #7 viewer 匹配。
   接入清单 11 条线：**✅1~9 + ✅11，仅 ❌10 明确不做**（10 通 + 1 不做）。
   （#3 列数 10-23 改权威值并真机验收；#8 客户端同步 10-24 结案 —— 物品形态的内容一定在
   被原版同步的槽位里，自己的通道 no-op 是对的，见 tech 文档 §6。）

   **✅ 真机验收（2026-10-23 用户实测）**：两个活背包倒汲 / 关界面不再刷第三方 ERROR /
   储罐插件动态列数（各尺寸含方块形态）/ 活漏斗高级 tooltip 容器信息 —— 四项全通。

   **剩余缺口**：无（#3 列数已改 `Source#columns` 权威值；#8 已结案）。
   仅 ❌10 世界语义明确不做（归活潜影箱）。
   **未定案 2 项**：嵌套上限放开时机（写死 1，等活潜影箱立项）· handler 获取开销（要实测）。

6.1 🟡 **客户端渲染没有「物品形态」数据源**（2026-10-24 定位 → **工具通道 2026-10-26 已实施，待真机复验**；信标光束仍未做）——
   四个宿主锚点开关全开后实测：活背包在玩家背包 / 箱子里，活 TNT ✅、活水车 ✅、
   工具主动模式 ✅、信标 buff ✅，但**活工具模型不显示、活信标没有光束**。
   根因是客户端压根不知道背包里有什么：
   - 工具模型（`LivingToolModelRenderer.render`）只枚举四条：① 本机背包逐槽
     ② 掉落物 ③ 方块容器（K2 的 S2C，**按位置**）④ 别人背包（S2C，**按玩家**）
     —— 背包内容不在任何一条里。
   - 信标光束（`LivingBeamRenderer.render`）同：`renderPlayerHost` 只扫
     `mc.player.getInventory()`，另两条按玩家 / 按位置。
   - 服务端也**从没发过对应的包**：`LivingToolHostSync.report` 要 `hostPos != null`
     （方块容器），`BeaconPlayerHostSync.report` 要 `getOwnerPlayer() != null` ——
     物品形态这两个都是 null ⇒ 不是包发错了，是**这条通道整个不存在**。
   - 🔴 **更根本（2026-10-26 查证，推翻本条原判断）**：**客户端根本读不到背包内容**。
     精妙的内容源是 `BackpackWrapper.BackpackStorageContentsSource` ⇒ `BackpackStorage
     extends SavedData`（服务端全局，`Map<UUID, CompoundTag>`），物品上只存
     `STORAGE_UUID` 组件；客户端只有一个 `clientStorageCopy`，由精妙自己的
     `BackpackContentsPayload` 填充（开 GUI 时 `BackpackContainer` 发 / 悬停 tooltip 时
     `RequestBackpackInventoryContentsPayload` 请求）。
     ⇒ 原判断「背包内容存在物品组件里，客户端本地读得到」**是错的** ⇒ **b 档不成立**，
     「自己看得见」也必须走 S2C。
   - 原来记的第二个理由（槽位同步用 `ItemStack.matches`、检测不到组件变化）**仍然成立**，
     但已不是主要障碍 —— 即使组件变化能被检测，背包物品栈本身也没变（内容不在它身上）。
     ⇒ tech §6-#8 的结论要补一条边界：「内容一定处在被同步的槽位里」仍成立，
     **「同步会及时发生」不成立**（组件级变化不触发比对）。
     ⚠️ **2026-10-31 收窄**：这句话只对「外层装背包的槽位」成立；**打开的背包 GUI 里**
     组件翻转的及时性已由 `syncItemFormSlot`（按容器 UUID 直发内层槽位包）补上
     （真机回归：活水车在背包 GUI 转向不刷新）。本条剩余范围（工具模型 / 信标光束
     的 S2C 通道）不受影响 —— 那是「外层槽位标脏 + 渲染数据下发」，不是槽位包。

   **要补的是三件事，缺一不可**：
   - a. **脏消息要能递出物品形态**（地基）：内层 ctx 写回时把外层装背包的槽位标脏
     + bump 修订，否则客户端读到什么都是旧的。
   - b. ~~客户端扫本机背包、本地读内容~~ **不成立**（见上，内容在服务端 SavedData）。
     客户端仍要加数据源，但数据只能来自 S2C；它**不需要扫背包** —— 包里带位置就够
     （渲染器只需要「位置 + 渲染状态」，不需要背包物品本身）。
   - c. **远程可见性**：别人背包里的内容别的客户端无从得知 ⇒ 需要一条 S2C。
     形状照抄 K2，但键用**容器 UUID**（去重与身份），**位置取外层 anchor**
     （`AtBlock` / `AtPlayer` / `AtEntity` —— 内层 ctx 的 `getHostAnchor()` 已经答了）。
   **分档（2026-10-26 修正）**：原「a + b / 再 c」分档**作废**（b 不成立）。
     ⇒ **a 与 c 是同一件事的两面**：a 是触发时机（内层写回标脏），c 是通道
     （按容器 UUID 下发渲染数据）。最小版 = 工具 + 信标共用一条「位置 + 渲染状态」的
     S2C，客户端按位置渲染、不依赖本地能否找到背包物品。

   **✅ 进度（2026-10-26）**：**工具通道已实施** —— `K2` 宿主键 `BlockPos` → `HostRef`
   （方块 / 玩家 / 实体），客户端 `HostOrigin` 一处解析宿主坐标；
   **信标已接（同日）**：`containerUuid` 决定「发不发自己」。**待真机复验**。

   ℹ️ **复制品不防、只给自查**（用户定）：中键复制会连 UUID 一起复制 ⇒ 两个背包共享数据。
   活背包 tooltip 显示容器 ID 短码（高级提示给完整 UUID + 提示），两个 ID 相同就是复制品，
   取消活化再重新活化即换新身份。见 [tech §5.6](tech/living-backpack-tech.md)。

   ✅ **同一宿主多背包的覆盖已修（2026-10-26）**：`HostRef` 加 `hostSlot`，
   来源 `ContainerContext#getHostSlot()`（tick 路径传 `entry.slotIndex()`）。
   原来两个背包在同一宿主上会撞成一条、后写覆盖先写。见 [tech §5.7](tech/living-backpack-tech.md)。
   ⚠️ 同源缺口（不是 bug，是结构）：客户端辅助 / 可视化三条线
   （`LivingToolPlayerSync.collectAssistTools` / `LivingToolAssist.assistTools` /
   `LivingWeaponAssist`）都只扫 `player.getInventory()` ⇒ **无记忆（被动模式）的
   工具 / 武器在背包里注定什么都不做**。要不要让"背包内容算装备"是独立玩法问题。

6.2 ✅ **宿主锚点只接了一半**（2026-10-24 定位 → **10-25 已修**）——
   两处下游都在问容器自己，物品形态两个字段都是 null：
   - **信标**：`BeaconFunction.tick` 按 `getBlockPos()` / `getOwnerPlayer()` 分派 ⇒
     光束 / 开盖 / 玻璃色 / 联机广播全部不执行（只有 `applyEffects` 吃到了 hostPos
     ——「buff 生效但没光」的真因）。
   - **工具**：`hostBlocks`（「别挖自己容器」黑名单）取自
     `getAssociatedBlockPositions()` + `getBlockPos()`，物品形态两者皆空 ⇒
     在箱子里射线起点 = 箱子方块中心，第一个穿到的格子就是那只箱子：
     记过类型 ⇒ 停住（完全没反应）；没记类型 ⇒ **挖自己所在的箱子**。

   **修法（同一原则）**：抽出 `effectiveAnchor(context)`（容器自己的锚点，物品形态
   没有 ⇒ 开关打开时借宿主的），**取点与下游分派共用它** ——「宿主是谁」只问一次，
   不许回头问容器自己的身份。信标分派改成 `anchor instanceof AtBlock/AtPlayer`；
   工具黑名单加一条「`AtBlock` 时把那一格算进去」。

   **已知局限（未修，需先拍板）**：锚点是单个 `BlockPos`，拿不到宿主的完整位置列表
   ⇒ ① 大箱里背包的信标，`beamPos` 只能取锚点那一格，算不出「信标在前半还是后半」；
   ② 大箱里背包的工具，另一半箱子不在黑名单内。要修得加
   `ContainerContext#getHostBlockPositions()`（新增 API，见设计稿 §6-4）。

   ⚠️ **信标在玩家身上的背包里**：修好后**背的人自己看不到光束** —— 客户端本机渲染
   只扫 `mc.player.getInventory()`，扫不到背包内容；只有身边的人看得见（S2C 广播）。
   补自己那条归 TODO 6.1-b。

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
2. ~~🔴 **求和溢出吞物品（今天的真 bug，建议先修）** —— `ExplosionComponent.ignite` 的
   `int totalTntCount`：容器内总和 > 2,147,483,647 即回绕；偶数个满栈槽回绕成负数 ⇒
   早退 `return false`，而物品已在同一循环清空 ⇒ **TNT 凭空消失、零爆炸、零日志**。
   修法：求和改 long + 补「两满栈槽点火不丢物品」测试（详见设计稿 §1.4 / 修单 S1）~~
   ✅ **2026-10-27 完成**（全项目审查修复轮）：`ExplosionComponent.java:192` 已改
   `long totalTntCount` + 计数异常守卫；守卫测试 `ExplosionComponentCountTest`。
   设计稿 §1.4 同步勘误（2026-10-31）。

## GUI 交互（2026-10-31 自 gui-interaction-redesign.md 收口登记，D-doc-06）

1. ⏳ **活指针模式**（步骤 3 = E2a 点击活化 + E2b 悬停配方向）—— 未实现；
   设计与已定稿的 Q2/Q3/Q4/Q6 见 [buffer/gui-interaction-redesign.md](buffer/gui-interaction-redesign.md) §4 表 / §5。
2. ⏳ **框选批量活化**（步骤 4 = E2c）—— 未实现；Q3/Q5 已定案（框选 = 只活化、矩形覆盖即选中），
   见 [buffer/gui-interaction-redesign.md](buffer/gui-interaction-redesign.md) §4.2。
3. ⏳ **E1 增强（可选）**：拖动 + 位置持久化已落地（2026-10-10，存 `config/living_item/button_layout.json`）；
   TrashSlot 式**吸附 / 碰撞避让 / 显示开关**当轮明确不做，要做再按设计稿 §4.1。

## 渲染态广播（2026-10-26 记，未定案）

> 「让客户端看见活物品在世界里的样子」现有 **4 条独立通道**（方块容器工具 / 背包工具 /
> 箱子信标 / 玩家信标），各自抄了一遍「定向 + 去重 + 逐维度 flush + 客户端缓存」，
> 且**去重口径四种**（手写白名单 / 全量组件 / `List.equals` / 判翻转）。
> 形状提案与三步走见 [buffer/render-state-broadcast.md](buffer/render-state-broadcast.md)。

1. ⏳ **去重键口径没有单一来源** —— 渲染器加依赖时无人提醒改键（2026-10-26 事故根因）。
   倾向在 `ToolComponents` 旁声明 `RENDER_STATE` 组件集合，去重键从声明生成
   （**不能从 domain 反向引用 L5 客户端**，故声明不能住在渲染器里）。
2. ⏳ **四通道收口** —— `TargetedBroadcast<T>`（服务端骨架）+ `DimensionScopedCache<T>`
   （客户端缓存）+ 包内「结构段去重 / 状态段每 tick 覆盖」。
   判据：第 5 个世界可见活物品不新增包类、不新增缓存类。

## 2026-10-27 审查后续 · 代码与文档口径对齐

> 当前处理入口：[代码与文档口径对齐台账](buffer/code-doc-alignment.md)。
> 台账分开登记用户已明确边界、待决玩法、旧描述维护、待核验诊断与测试声明。
> 原始发现见 [审查摘要](buffer/2026-10-27-全项目审查报告.md)，其中完成范围、修复数量、
> 已修项目与因果推断有误记，保留历史但不再作为验收依据。
> 用户已明确：**TNT没锚点不炸；熔炉运行过程数据不随搬运保留；地图指哪传哪，不按地形替玩家避险；
> 铜的红电信号不直接作世界红石输出，中继器逻辑对齐原版。** 不再将这些列为待选玩法。
> 本轮只记录规则与意见、不改其他待决代码。A4飞艇传送已获用户同意并实施为“普通坐骑落载具、飞艇底面对齐目标Y”；
> A5失活后重新计时／A6拆合重算／A8玩法核对见台账，其中A8建议尚未全部实施。

## 2026-10-27 审查修复 · 真机复验（2026-10-28 全部闭环）

> 用户 2026-10-28 全面实测（c26b5d0 构建）："全部测试了一遍，都没发现什么问题"；唯坐垫生物随艇于同日另案恢复（见第 4 条）。

1. ✅ **K2 跨维度修复**（2026-10-28 实测通过） —— 多人服（或切维度）：地狱/末地里背包中的活工具应恢复渲染
   （模型/背后环/记忆射线），辅助挖掘加成正常。
2. ✅ **末影箱「存放处」定案**（2026-10-28 实测通过） —— 核验无锚点的具体后果：不参与随身工具辅助、
   不产生宿主工具射线／信标光束，TNT在无爆点时不执行爆炸；活末影箱本体路由照常。
   **不是整个容器停止 tick**；TNT按“没锚点就不炸”验收，不引入独立待爆玩法。
   **（2026-10-28 后续修订）** 空锚点引爆失败时改为**熄灭**（`ignited` / `fuseTimer` 归零），
   撤销原“保持点燃”口径——待爆态会让 TNT 挪到有锚点处不点自爆
   （口径见 [living-tnt-tech](tech/living-tnt-tech.md) §4.1）。
   玩家**物品栏**里的背包与信标行为不变（回归确认）。
3. ✅ **手持活背包挖矿**（2026-10-28 实测通过） —— 手持背包挖方块两端同源、无「破坏又恢复」；
   背包里的工具耐久正常扣（写回守卫不误伤正常写回）。
4. ✅ **Sable 飞艇传送（A4）** —— 传送本体已验收 ✓（客户端到位、贴地降落正常）。
   随艇口径定案：**坐垫生物随艇**（坐垫实体显式平移；站甲板生物 / 空坐椅 / 其它载具不跟）。
   坐垫恢复按用户决定**不再实测**——按验证层级如实记：静态审查 + 单测/门禁绿，无真机反馈。
   口径与勘误见 [living-map-ender-pearl-tech](tech/living-map-ender-pearl-tech.md) §11.2.1。本项闭环。
5. ✅ **红电停机重启（A5）**（2026-10-28 实测通过） —— 振荡器停跳超窗口后恢复：恢复瞬间无产出尖峰、
   第二个沿起按新周期正常发电；正常运行与正常改频不受影响。
6. ✅ **双箱拆分重算（A6）**（2026-10-28 实测通过） —— 拆半后余箱物品保留、流体源清空（重新倒水）、
   红电重新锁相；拆半前调好的相位布局不作数。

## 活工具 / 活背包 · 2026-10-27 全项目审查挂起项（P3，未定先后）

> 2026-10-27 审查修掉了 2×P1（K2 跨维度抽干、末影箱口径）与 1×P2（手持背包），
> 见 changelog 同日节。以下是当时记录、评估后**暂不修**的 P3。

1. ⏳ **工具速度两端乘区待核验** —— 服务端FakePlayer镜像部分属性，客户端有独立速度公式；
   需分别核对急迫／挖掘疲劳的实现、镜像范围与装备增益，不能仅凭效果名推导是否一致。
   **旧“急迫／疲劳都是BLOCK_BREAK_SPEED修饰符”的依据未经证实，暂不采用其修法。**
   背包tech中的相关表述也列入口径台账C2；有复现与原版乘区证据后再改公式。
2. ⏳ **开关关时背包工具仍被渲染**（已知副作用，tech §5.8 有留痕）—— 根治需协议区分
   「为辅助上报」与「为渲染上报」（Entry 加渲染门控位），动协议，等有第二个诉求再说。
3. ⏳ **客户端速度公式与 FakePlayer 的缝隙待盘点** —— 水下 / 离地 `/5`、`DIGGING` 速度
   环境乘区两端不对齐（同 #1 一并复核）。
4. **活武器背包辅助口径已核对** —— 当前逐把回放，实际出手各自承担耐久；
   挖掘速度“只取最高”是独立规则，不再将攻击没有同样限制单列为缺陷。
   建议保留现状，见口径台账A8；第三方容器写回正确性另作验证，不混成攻击限制。
5. ⏳ **`ToolRenderState` 不含射线微调（tuning）** —— 两个渲染器从 K2 副本读 tuning，
   而去重键不含它；今日安全（tuning 只写主手、主手不进 K2），将来若允许对容器内工具
   配置微调即静默失效。建议在 `ToolRenderState` 注释记边界。
6. ⏳ **`HostOrigin` ENTITY 分支每帧 O(实体数) 线性扫** —— 条目个位数时可接受，
   量大后换 `level.getEntity(uuid)` 类查询。
7. ⏳ **自玩家双光束** —— 顶层信标 + 背包信标同时激活时本机玩家看到两根光束同点
   （视觉重复，非功能错误）。
8. ⏳ **同箱多背包信标开盖键形错配** —— `BeaconChestHost` 按 `containerKey` 归组，
   物品形态背包按各自 cuuid ⇒ 同箱两枚不同背包的信标是两组、同一箱口，取走一枚后
   另一枚 20t 节流未到时盖子最多关 1 秒再弹开（视觉闪烁）。
9. ⏳ **嵌套背包是 K2 渲染盲区** —— 背包里的背包不产出 HostRef（与「只展开一层」
   口径一致，属已知设计边界）；建议在 `living-backpack-tech.md` 渲染节标注为静默限制。
10. ⏳ **`LivingToolPlayerSync.same` 用全组件比较（含耐久）** —— 比 K2 的
    `ToolRenderState` 宽 ⇒ 每破坏一个方块多一次重发（方向是多发、量级小），
    与「渲染依赖清单只有一份」的原则不一致，顺手统一。
11. ⏳ **背包在方块容器里的渲染门盲区（2026-10-28）** —— 活背包放进箱子时其内容按
    方块宿主上报 ⇒ `tool_and_weapon` 关闭时这类背包里的工具模型仍渲染
    （[living-tool-tech](tech/living-tool-tech.md) §6.1 已知局限；玩家/实体身上的背包已正确关门）。
    根治需 K2 条目带「功能休眠」标志（协议变更）。

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

