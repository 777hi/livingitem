# 开发历史更新记录

> **本文件存放全部开发历史正文**（按日期倒序）。`AGENTS.md`「开发进展」只保留
> 最近 10 条「一行结论 + 指针」，**完整正文写在这里**。
>
> **写入规则（2026-09-22 起，D-doc-04）**：在**顶部**对应日期节下追加正文；
> 同一天多次开发**追加到同一节**（⇒ 不再有「同日多批次」问题）。
> AGENTS 侧只做两件事：顶部插一行摘要、超过 10 条时删最底部一行
> —— **没有「归档」环节，无需守恒校验**（正文从来没离开过本文件）。详见 `docs/README.md` §4。
>
> ⚠️ **2026-09-16 补录**：`2026-09-01 ~ 09-04` 曾**断档** —— 该段工作只写在
> `.workbuddy-ai/memory/` 日工作日志里（而日志按策略 30 天后会被删除），`AGENTS.md`
> 与本文件都无记录。本次已从日工作日志补录，特此留痕。
> **教训仍然有效**：历史必须落进仓库，只写工具侧日志 = 会随日志删除而永久丢失。

---

## 2026-10-24

> 💥 **修复：带箱驴里引爆活 TNT ⇒ 服务端 `/ by zero` 崩服**
>
> 崩溃点：`ContainerRedstoneData.calculate` 的 `(size + width - 1) / width`，`width == 0`。
>
> **完整因果链**（`processContext` 的阶段顺序是关键）：
> 1. **阶段 2（函数 tick）**：驴胸前的活 TNT 引信归零 ⇒ 爆炸 ⇒ `applyExplosionDamage` 打死驴
>    ⇒ `AbstractChestedHorse#dropEquipment()` 把箱子摘掉 ⇒ `setChest(false)`。
> 2. **阶段 3（容器级数据）**：同一个 ctx 继续跑红石 ⇒ `getWidth()` 问原版
>    ⇒ `getInventoryColumns()` 是 `hasChest() ? 5 : 0` ⇒ 答 **0** ⇒ 拿 0 当除数 ⇒ 崩服。
>
> ⇒ 一句话：**「容器活着」不是处理期间的稳定前提。**
>
> 修法两处（互相独立、都必要）：
> - `EntityContainerContext#getWidth()`：原版答 0（无箱/刚掉箱）⇒ 回落到按槽数推，**绝不返回 0**。
>   列数是红石/流体/应力/信标列扫描的除数与模数，0 会从任意一处炸开。
> - `ContainerRedstoneData#calculate`：`size <= 0 || width <= 0` ⇒ 本 tick 不算
>   （空容器没有红石网格）。与 `ContainerStressData#calculate` 早就有的同款守卫对齐 ——
>   **应力域有，红石域漏了**。
>
> 测试 +2：`EntityContainersTest`（原版答 0 ⇒ 列数 ≥1）+ `ContainerRedstoneDataTest`
> （空容器不除零）。单测 897 → 899。不变量写进 `container-boundary.md` **I-C11**。
>
> ⚠️ 顺带发现：`FluidFlowServerSync` 里两处 `Math.max(1, ctx.getWidth())` —— 同一个坑之前
> 被就地绕过（而非修根因），所以只挡住了流体一条轨。现在根因修了，那两处可以退休（未动）。


> 🧨 **修复：掉落物形态的活 TNT 不被 tick（同一处白名单第二次漏改）**
>
> 真机：把点燃的活 TNT 丢在地上，它**永远不爆炸** —— 不是「炸不了」，是**压根没被 tick**。
>
> 根因在 `LivingItem#processEntityContainers` 的准入判据：写死成
> `LivingToolRecorder.isLivingToolOrWeapon(...)`，活 TNT 在 `instanceof` 处就被短路，
> 连容器上下文都不会建。
>
> ⚠️ **这是同一处的第二次**：2026-09-24 同样的形状漏过**活剑**（当时只写了 `isLivingTool`），
> 修法是把判据收敛成 `isLivingToolOrWeapon` —— 但那仍然是**物品白名单**。
> ⇒ 白名单本身就是错的形状：它按「物品是什么」判，而真问题是「功能能不能在这种形态下工作」。
>
> 改成**功能自声明**：
> - `LivingItemFunction#supportsDroppedItem()` 默认 false（绝大多数功能从 `getBlockPos()` 取坐标，
>   进掉落物上下文只会拿到 null 空转）
> - 准入：`LivingItemManager#supportsDroppedItem(stack)` —— 任一功能声明即建上下文
> - 分发：`runFunctionTicks` 按同一声明**逐功能过滤** ⇒ 通过准入 ≠ 所有功能都跑
> - 已声明：活工具/活武器（射线起点 = 碰撞箱中心）、活 TNT（炸在原地，走 `ContainerAnchor.AtEntity`）
>
> 测试 +5（`DroppedItemSupportTest` 4 项：活 TNT/活工具支持、活箱子未声明不支持、非活物品不支持；
> `LivingTntConsumeTest` +1：掉落物形态炸在原地并消耗）。单测 892 → 897。
>
> 教训写进 `living-weapon-tech.md` 的事故段：**管线准入判据不要按物品类型写白名单** ——
> 白名单的漏改表现是「功能看起来在跑、其实永远不生效」，两次都只能靠真机发现。


> 💥 **修复：活 TNT 炸完不消耗（写回把 ignite 清掉的槽位填了回去）**
>
> 真机：宿主锚点接通后能爆炸了，但炸完 TNT 还在（而且是「未点燃」的新状态）。
>
> 根因不在 `ExplosionComponent.ignite`（它确实把容器里所有活 TNT 槽位写成 `EMPTY`），
> 而在 `LivingTntFunction.tick` 的**写回位置**：爆炸分支之后没有结束，于是继续执行
> 「把本地 `stack` 写回槽位」—— 那个 `stack` 是 tick 开始时的快照，里面还是那颗 TNT
> ⇒ 刚清掉的槽位被原样填回。多颗时更糟：`ignite` 一次消耗全部，而 `entries` 里
> 其余条目照样各自写回（等于把没轮到的也复活）。
>
> 修法：爆炸成功后**直接结束**（`return`），消耗权归 `ignite`。
> 顺带给「首次点燃」补上 `setItem` —— 与引信递减走同一条「改了就写回」规则。
>
> ⚠️ 这也修正了 §6 的候选成因判断：之前以为「缺写回」（B），实际是**写回发生在消耗之后**。
> **写回本身没错，错在「谁拥有这次修改」**——`ignite` 的语义是消耗，消耗后的槽位它说了算。
>
> 测试 +3（`LivingTntConsumeTest`：单颗炸完槽位空 / 多颗全部消耗 / 无锚点引信停 0 不重置）。
> 已用「临时改回旧写法 ⇒ 2 条红」验证过它们真的能抓住这个 bug。单测 889 → 892。
> ⚠️ 测试基建：`Level.random` 是**字段**（Mockito 对字段无效）⇒ `ServerLevel` 替身需反射注入，
> 账本用真 `ExplosionLedger`。


> 🎒 **活 TNT 走通宿主锚点（回到原点后的重置，`D-container-15` 修订）**
>
> 上一版用「保持 ignited + 引信冻结」两个补丁处理「无锚点」，结果：引信停在 0 ⇒
> **放置背包 = 瞬爆**（用户追问「把活背包放置出来会瞬爆吗」才发现）。连打三个补丁，
> 每个都引出新问题 —— 于是回到原点重新问：**引信是什么？**
>
> 答案：**引信不是独立计时器，是「即将在某处发生的爆炸」的倒计时**。没有位置就没有那件事，
> 所以「冻结 / 失败 / 熄灭」三个选项都是伪命题 —— 它们都在给一件不存在的事找结局。
>
> **重置为宿主继承**：「物品在箱子里，就在箱子那里」是物理事实。落地形状是两个访问器
> **刻意分开**：
> - `getAnchor()` —— 容器**自己**在哪（物品形态恒 null）
> - `getHostAnchor()` —— 它**借住**在哪（tick 路径由 `LivingBackpackFunction` 传入宿主锚点）
>
> 合成一个的话，工具/水车/信标会在不知情时改变行为 ⇒ **功能要用必须显式拿**。
> 目前**只启用活 TNT**（爆炸点取「自己的锚点，没有就用宿主的」），并补了 `AtEntity`
> 爆炸点（船/掉落物炸在原地）。
>
> 为什么只有 TNT：爆炸点用继承值是**修复**（本来就该炸），
> 工具/水车/信标用继承值是**加强玩法**（自动挖矿 / 随身发电 / 随身光环），必须逐个拍板。
>
> 撤掉：引信冻结补丁。「ignite 失败不重置」保留为**兜底**（真炸不了时不留循环）。
> 效果：背包在玩家身上 ⇒ 4 秒后炸在脚下；在箱子里 ⇒ 炸在箱子处；掉在地上 ⇒ 炸在原地。
>
> 新增测试 2 项（两个访问器刻意分开 / 非物品形态 getHostAnchor 恒 null）。单测 887 → 889。


> 🧭 **实施：容器世界锚点 `ContainerAnchor` + 修活 TNT 的无限倒计时**（`D-container-15`）
>
> 按设计稿 §7 走完前 4 步：
> 1. **`ContainerAnchor`**（`AtBlock` / `AtPlayer` / `AtEntity` / null）+ `ContainerContext#getAnchor()`。
>    ⚠️ 实体容器与掉落物**必须覆写**——它们的 `getBlockPos()` 刻意是 null、也没有主人，
>    走默认实现会退化成「没有锚点」，让船上所有需要锚点的功能静默失效。
> 2. **TNT 写回**：`context.setItem(slot, stack)` —— 不再依赖「getItem 返回的活引用」。
>    工具类早有 `writeBack`，TNT 此前漏了。
> 3. **TNT 显式失败**：`ignite` 返回 false 时**保持 ignited + 留痕一次**，不再无条件重置。
>    这正是真机「重复倒计时」的机制：重置成未点燃 → 下一 tick 又从头倒计时。
> 4. **TNT 爆炸 + 信标光束** 改走 `getAnchor()`（行为完全不变：`AtEntity` 仍返回"不支持"）。
>
> 新增 `ContainerAnchorTest` 7 项 —— 最关键的两条是「实体容器必须覆写」「掉落物有实体不是没锚点」，
> 将来新增实体形态忘了覆写会立刻红。
>
> 单测 880 → 887。剩余步骤见设计稿 §7（水车/工具要拍板取点语义；继承宿主；实体容器水车缺口）。


> 🔎 **普查：容器世界锚点（ContainerAnchor）—— 17 个功能逐个过了一遍**（未定案）
>
> 起因是真机「活背包里的活 TNT 点燃后重复倒计时、不爆炸」。追根因时发现不是孤例：
> **「容器在世界里作用在哪」有 4 份各写一遍的解析**（TNT 爆炸 / 水车应力 / 工具射线 / 信标光束），
> 而且「玩家形态取哪个点」四个都不一样（脚下 / 脚下方块 / 眼睛 / 脚下）——
> 这个差异是**对的**（射线要从眼睛出发），所以**锚点不能定义成坐标**。
>
> 普查结论（详见 [container-anchor-design.md](container-anchor-design.md)）：
> - 需要锚点：活信标 · 活 TNT · 活水车 · 活工具/武器；红石 9 件需要**位置型读取**（邻居信号）
> - 其余 12 个功能无关（纯数据 / 内部槽位 / 方向来自物品组件）
> - ⚠️ **实体容器（船/矿车）有同样的缺口**：`ContainerStressWriteback` 还在用
>   `getAssociatedBlockEntities()`，而 10-21 已把写回口径统一到 `getDataHosts()`
>   ⇒ 船里的活水车从不输出应力，从未审计
> - 活武器是另一条路（扫 `player.getInventory()`）⇒ **活背包里的武器不参与补刀**，
>   属「背包内容算不算装备」的独立玩法问题，不在锚点射程内
>
> 设计提案：`ContainerAnchor` = **参考物**（AtBlock / AtPlayer / AtEntity / null），
> 框架只回答「谁/在哪」，功能自己决定「用哪个点、要不要用」；
> 物品形态可**继承宿主锚点**（背包在玩家身上 ⇒ 水车输出到脚下、工具用眼睛射线），
> 但 TNT 继承后是自爆 ⇒ **「要不要用」仍是功能的决定**。
>
> ⚠️ TNT 现象有两个候选成因（无锚点循环 / 就地修改未落盘），文档里都记了，
> 倾向后者（旁证：工具类有显式 `writeBack`，TNT 没有）。需真机日志区分。
>
> 本文**未定案**，拍板前不动代码。


> 🧩 **「谁在看这个容器」收成唯一入口（`D-container-14`）**
>
> 用户提议：「活背包的联动、以及更多容器兼容专题，似乎可以总结抽象出一个东西来，
> 免得每次都要接这么多根线」。查了成本分布后确认：**新 mod 的成本已经收敛到 3 个方法**
> （`ItemFormContainers.Source` 的 columns / contentHandler / handleOfMenu，无尽箱子那种
> 既有形态框架零改动）；**真正的剩点是「按种类分发」散在多个调用点** ——
> 加一种新形态要各改一遍，漏一条就静默失效。
>
> 抽 `ContainerViewers.of(players, cacheKey, positions)`：按缓存键种类分派
> （玩家态 ⇒ 主人 + 末影箱门控 · 物品形态 ⇒ 菜单锚点 · 其余 ⇒ 位置）。
> `FluidFlowServerSync.viewersOf` 与 `LivingItemRuntimeSync.flush` 都改成只调它，
> 各自那份 if-chain、`sendToOwner`、`shouldSendToOwner`、`shouldDispatch`
> 以及 `ContainerRuntimeCache.findViewersByPositions`（含 L2→L1 桥）全部删除。
>
> **为什么按缓存键而不是 ctx 分派**：两条轨的输入原本不同（一条拿 ctx、一条拿 cacheKey），
> 所以它们看起来毫不相干 —— 这正是能漏一条的原因。缓存键是两条轨共有的通货。
>
> 新增 `ContainerViewersTest` 6 项（末影箱门控两条用例从流体轨与运行时轨合并迁入，
> 从此门控只有一处、只测一处）。单测 880。
>
> ⚠️ **故意不纳入**：`LivingBrewingStandFunction.brewSoundTargets` 问的是同一个问题，
> 但用途是音效不是数据同步，改它会把末影箱音效也纳入门控（行为变化）⇒ 记在 tech 文档里，
> 等真需要时再说。


> ✅ **活背包真机验收通过（四项全通）**
>
> 用户实测：① 带两个活背包的倒汲 / tooltip / 槽位设置 ② 关界面不再刷第三方 ERROR
> ③ 储罐插件动态列数（各尺寸背包 + 方块形态）④ 活漏斗高级 tooltip 显示容器信息。
> 状态标记同步更新：设计稿「已实施并真机验收」· TODO 第 6 条 🔴→✅ ·
> tech 文档 §0 加验收记录 · 入口进展行补「真机验收通过」。
> 11 条接入线：10 通 + ❌10 世界语义（已定不做，归活潜影箱），余 ⚠️8 客户端同步（部分）。


> 🔧 **去掉 K2 调试日志，并修掉它一直在报的真 bug**（6bcd5a2）
>
> `[K2] 发送/收到` 两条 INFO 每 tick 每玩家各一条（9547 行日志里占 7605 行）。
> 但删日志之前先查了为什么每 tick 都发 ——
> `LivingToolHostSync.flush(level)` 是**逐维度**调用的（主世界/地狱/末地恒三个），而去重表
> `lastSent` 是**全服一张**，原实现在每个维度 flush 末尾都 `lastSent.clear()`
> ⇒ 其它维度玩家的记录被抹掉 ⇒ 他们下一 tick 判“有变化”⇒
> **每 tick 重发一个空包**。⇒ 这是浪费带宽的真 bug，不只是嗑。
>
> 修法：`mergeRecords(previous, next, online)`（本维度覆盖 / 其它维度保留 / 离线淘汰），
> 抽成纯函数错上这条不变量。两条 INFO **直接删**（不降 DEBUG）。
> 测试 +3。单测 869 → 872。顺带补上 test-map 缺的 `living/domain/tools/` 整节。

> 🔧 **修复：两个活背包时活流体倒不了（#6 菜单解析默认实现的盲区）**（86cf52e）
>
> 真机复现：带**两个**活背包 ⇒ 倒沼 / tooltip / GUI 槽位设置全废。根因是
> `ItemFormContainers.Source.handleOfMenu` 默认实现「扫玩家物品栅 + 唯一一件才认」——
> 多件必然返回 null ⇒ `ContainerContexts.resolve` 解析不出 ⇒ GUI 交互包被
> `isAcceptable` 静默丢弃。单背包时默认实现足够，所以首测没暴露。
>
> 修法：精巧联动模块**覆写**该方法，用菜单自带的锚点
> `BackpackContainer#getBackpackContext() → getBackpackWrapper(player).getBackpack()`
> 直接定位「GUI 正在看的那一个」（多背包 / 子背包都覆盖）。
> 反射链解析失败 / 调用异常各打一条 WARN（I-C6，此前完全静默）。

> 📡 **修复：活背包里活漏斗高级 tooltip 不显示容器信息（运行时同步缺物品形态分支）**（本次）
>
> 用户反馈：槽位解析全通（物品形态/方块形态/储罐插件动态列数/各尺寸），唯一残留是
> 活漏斗的高级 tooltip 不显示活背包容器信息。
>
> 根因：**viewer 匹配这条轨有两条，上次只修了一条**。
> `LivingItemRuntimeSync.flush` 按位置匹配 viewer —— 物品形态容器没有世界位置
> ⇒ `containerLookup` 恒空 ⇒ **永不发包** ⇒ 客户端拿不到运行时数据（冷却/slotInfo/过滤链）
> ⇒ tooltip 落回 DataComponent（那里只有 EMPTY）⇒ `slotInfo.isValid()` false ⇒ 容器信息不显示。
>
> 修法：加 `ContainerKind.ITEM_FORM` 分支，与 `FluidFlowServerSync.viewersOf` 的 #7 同款：
> 问「谁正开着它」（联动模块从菜单反查到容器 UUID，相同即 viewer）。
>
> 测试 +1（mock 玩家锚定菜单 + captor 断言发的是这个容器的键）。单测 878 → 879。
> 教训已写进 tech 文档：**这条轨有两条，别只修一条**。

> 📐 **活背包列数改权威值（#3 槽位几何）**（本次）
>
> 物品形态容器 `getWidth()` 三层推断全不命中（没有 inventory / BE / 注册规则）
> ⇒ 掉到 Tier 3 启发式。它会猜错：真实列数是精妙 GUI 的「每行格数 − 升级占列数」，
> 54 格背包装 2 个占列升级（电池/流体罐）后存储槽 30 格
> ⇒ 启发式选 **10**，真值 **5** ⇒ 邻居判定 / 流体渲染 / 漏斗传输全线错位。
>
> 修法：`ItemFormContainers.Source#columns(stack)`（默认 0 = 不知道）+
> `Handle` 带上它 + `ItemFormContainerContext#getWidth()` 覆写。
> 精妙的实现是 **存储槽数 ÷ 行数**（handler 大小就是 `行数 × 每行格数`，
> 除一下即真值，**不需要复刻上游的布局公式**）；除不尽/拿不到 ⇒ 0
> ⇒ 回落启发式（不比修之前差）。
>
> 测试 +5（30÷6=5 / 27÷3=9 / 除不尽⇒0 / getWidth 覆盖与回落）。单测 873 → 878。

> 🐛 **修复：关界面时把同一个菜单问 60 遍（真机客户端崩溃的副产物）**（本次）
>
> 用户反馈「存档崩了」。实测后确认：**存档没崩**（日志里
> `All dimensions are saved` + `Cleared living item caches on server stop` 都走完了），
> 崩的是**客户端**。
>
> **崩溃根因是精巧背包自己的 bug**（非我们）：
> `BackpackWrapper.cacheSlotNumbers()` 里 `(BackpackItem) backpackStack.getItem()`
> 不判 instanceof —— GUI 开着时那个槽里的背包变空（air）
> ⇒ 服务端同步空栈 ⇒ ClassCastException ⇒ 客户端 FATAL。
>
> **但我们放大了它**：`FluidFlowServerSync.onContainerClose` 原来在逐槽循环里问
> 物品形态解析 ⇒ 同一个菜单被问 60 遍（60 = 菜单槽位数），每遍都调到精巧的
> `getBackpackWrapper`，锚点失效时它每次都打一条 ERROR
> ⇒ 关一次界面刷 60 条别人家的错误日志，而且那 60 次没有一次能解析出容器。
>
> 修法：物品形态解析提到循环外，**问一次就够**（它是菜单级的，与槽位无关）。
> 回归测试双关断注：来源只被问一次 + 逐槽循环没走（mock 菜单的 slots 是 null，循环一走就 NPE）。
>
> 其它同类风险（`viewersOf` 每 tick 每玩家问一次）本次实测未见，**不预先优化**；真出现再说。

> 🔒 **活背包兼容性：反射两组独立失败；降级阶梯加了又删**（bea8b3f）
>
> 「覆写会不会有兼容性问题」—— 查出一个真的：三个新查找（BackpackContainer /
> BackpackContext / IBackpackWrapper）和两个旧查找在同一个 try 里
> ⇒ 精巧只改 GUI 类名会把 `contentHandler` 也拖下水
> ⇒ 「能运转」退化成「完全不活」。⇒ **拆成两组独立失败**。
>
> 同时**加过又删掉**一套「锚点读不到就退回默认扫描」的降级阶梯：它保护的
> 「精巧改名后单背包场景」至今没发生过 ⇒ 属于为没发生的事铺路，违反「如无必要勿增逻辑」。
> 只保留一条硬规则写进 javadoc：**不是背包菜单时坚决不扫物品栅**。
> 测试基建：`new BackpackItem()` 撞注册表已冻结 ⇒ mock 无构造实例化 + `asItem()`/`components()` 双桩。


> 🗡️ **活武器两个真 bug（群友实测）：打怪吸走掉落物 + 横扫把自己打死**
>
> 群友在刷塔下用活剑砍怪：女巫掉的水瓶 / 红石粉 / 萤石粉「一下就没了」—— 背包是满的、
> 地上没有、小地图也不显示。控制变量定位：**背包满时只有用活剑才吸物品**。
> 另一个方向又复现一个：**打怪时横扫会扇到自己**（有一次直接被自己打死，
> 死亡信息显示"自己打死自己"；按着潜行可稳定复现）。
>
> **两个 bug 同一个根因家族：受控 tick 让假玩家"真的活"（2026-09-30 重构），
> 而它的坐标始终贴在玩家身边** —— 真链里两条"与世界交互"的默认行为于是反噬玩家：
>
> ① **实体接触环**（`Player#aiStep:564-586`）：以包围盒 `inflate(1.0, 0.5, 1.0)` 为范围
> `getEntities` → `playerTouch` —— 掉落物 / 经验球 / 箭各有一条拾取路径
> （`ItemEntity` / `ExperienceOrb` / `AbstractArrow` 的 `playerTouch`），
> 终点都是 `player.getInventory().add(...)` + 物品实体 `discard()`。
> 假玩家不在世界里 ⇒ 物品被吸进这个**不可见的背包**后对玩家即"消失"，
> **玩家自己背包满不满毫无关系**（小偷是假玩家）。
> 坐标来源：回放起点 = 玩家眼睛；且 `driveWielderTick` **先于** `setPos` 跑、
> 用的是**上一次**的位置（辅助挖掘算速度时还会把它摆在玩家**脚上** ⇒ 地面掉落物也够得着）。
> ⭐ 只有活武器触发：`driveWielderTick` 全项目仅 `LivingToolReplay#replayAttack`
> 一处调用（挖掘 / 交互路径不驱动 tick）⇒ 与群友「只有用活剑才吸物品」的控制变量结论精确对应。
>
> ② **横扫 AoE**（`Player#attack:1312-1334`；`MaceItem` 重锤冲击 `:161-194` 同款）：
> 过滤条件是 `!attacker.isAlliedTo(victim)` —— 而 `Entity#isAlliedTo` 是**队伍制**
> （比 scoreboard team），假玩家无队伍 ⇒ 一律不豁免 ⇒ 贴身在怪旁的玩家被横扫打死。
> 死亡信息显示"自己打死自己"：FakePlayer 的 UUID / 名字就是主人的。
>
> **修法（都在 `LivingToolFakePlayer`，两个覆写）**：
> - `aiStep()`：tick 期间把包围盒临时换成**世界底界之外的退化点**（`NO_BODY`）⇒
>   接触环查不到任何实体，一处收口掉落物 / 经验球 / 箭 / 模组 `playerTouch` 全部路径，
>   顺带屏蔽 `pushEntities` 对玩家身边实体的推挤；`try/finally` 恢复真盒。
>   ⭐ 不用事件拦截：掉落物 / 经验球有 NeoForge 事件（`ItemEntityPickupEvent.Pre` /
>   `PlayerXpEvent.PickupXp`），**箭没有**，模组还可能自己实现 `playerTouch` ⇒ 事件路线天然漏。
> - `isAlliedTo(Entity)`：`entity instanceof Player || super.isAlliedTo(entity)`
>   ⇒ 所有 AoE 过滤条件（横扫伤害 / 击退 / 火焰附加点燃）整体跳过玩家。
>   ⭐ 不用伤害事件拦截：伤害 / 击退 / 附魔后效是同一个 if 块里的一个整体，拦伤害拦不住后两者。
>
> 与 `isAttackableByLivingWeapon`「活武器不打任何玩家（无 PVP）」同口径，主人与其他玩家一视同仁。
> ⚠️ 本子系统**暂无独立测试类**（既有约定：以 tech 文档 §9 踩坑记录 + 游戏内手测为准）⇒ 待真机验收。


---

## 2026-10-22

活背包补上 #6 菜单解析 + #7 viewer 匹配**（清单 11 条 → 9 条接通）
>
> 首测缺的两条线：
> - **#6**：`ItemFormContainers.Source.handleOfMenu` 默认实现 —— 扫玩家物品栏找
>   「本来源认领且带容器 UUID」的物品，**唯一一件才认、多件返回 null**。
>   多件不猜的理由：菜单不告诉你对应第几件，猜错会**操作到另一个容器**（比解析不出更糟）。
>   `ContainerContexts.resolve` 末尾加该分支 ⇒ tooltip / 倒汲 / GUI 槽位设置三条一起通。
> - **#7**：`FluidFlowServerSync.viewersOf` 加「按容器 UUID 反查在线玩家」分支
>   （物品形态没有位置，原按 positions 匹配会直接返回空 ⇒ 永不发包）。
>
> 测试 +4（菜单反查：无活容器 / 唯一一件 / 两件不猜 / 未注册来源）。单测 861 → 865。

> 🔧 **活背包真机首测：能 tick，但只接了 6/11 条线** + 两处文档固化
>
> **首测结果**：活背包内部活物品**确实开始 tick 了**，但活漏斗的传输槽位对不上、
> 高级 tooltip 不显示容器信息。
>
> **根因不是槽位算错，是「两条路径分叉」**：tick 走 `LivingBackpackFunction` 自建 ctx
> → `processContext`；而菜单/交互走 `ContainerContexts.resolve(player, slot)`，
> **那条链完全没接物品形态**（搜 `ItemForm`/`cuuid` 零命中）。
> ⇒ 设置槽位时解析不出容器（回落默认），tick 时用的是另一个 ctx ⇒ **两套映射**；
> tooltip 同链（`ContainerRuntimeCache` 按槽位找 ctx，找不到就没信息）。
>
> **固化 1 —— 新容器形态接入清单（11 条线）**：写进
> [guides/container-compatibility.md](../guides/container-compatibility.md)。
> 这是本次最有价值的产出：一个容器形态要接 11 条线才叫「正常工作」，
> 不是「能 tick」就算完。活背包只打了 6 个勾就以为做完了。
> 两条经验：① **#1 tick 与 #6 菜单解析是独立路径**，只接前者会表现为「能 tick 但交互全废」，
> 症状像槽位算错；② **#3 列数不要猜** —— 启发式对非 9 倍数（如含升级槽的 30）会选 10。
>
> **固化 2 —— 否决「persist 保护」**（用户定调）：曾提议「旧值非空→新值变空时拒绝写入」
> 当作调试期安全带，被否 —— **它保护不了任何值得保护的东西**（真问题在槽位，不在写入），
> 且这类守卫的存在理由会随上下文消失，留下一个没人敢删的分支。
> **如无必要，勿增逻辑。**
>
> **待办**：#6 菜单解析（`ContainerContexts.resolve` 加物品形态分支）→ #7 viewer 匹配。
> #10 世界语义明确不做（活漏斗方向传输归活潜影箱）。

> 🎒 **活背包落地（2026-10-22，四步走完，待真机）**
>
> 由「物品形态的背包里流体不渲染」引出，用户连问四轮把方案推回原点：
> **活背包就是一个活物品功能**（`LivingBackpackFunction`），框架零改动（除一处持久化钩子）。
>
> | 步 | 提交 | 交付 |
> |---|---|---|
> | ① | `d452997` | `ContainerContext.persist(key,value)` 钩子 + `ContainerDataKey.savedWith`（attachment+codec 双全才算可落盘） |
> | ② | `7a3724d` | `CONTAINER_UUID` 组件/附件 + `cuuid_<uuid>` 键形 + `ContainerDataSavedData`（按 UUID 索引、Overworld 全局一份、空条目不写盘） |
> | ③ | `38bb544` | **身份优先的形态迁移**（有 UUID 只搬 UUID，数据留在 SavedData）+ SavedData 回填 + `getContainerUuid()` |
> | ④ | 本步 | `LivingBackpackFunction`（tick 里递归 `processContext`，depth 1 + UUID 环检测）+ `ItemFormContainers` 注册口 + `ItemFormContainerContext`（persist 覆写走 SavedData）+ 精妙联动（反射，不碰第三方类型） |
>
> **两个关键语义**（用户定调）：
> - 取消活化 = **清理干净** ⇒ `ContainerUuids.discard` 先删 SavedData 数据再剥 UUID（只剥 UUID 会留孤儿条目）
> - 容器身份**懒创建**（借精妙 `getOrCreateContentsUuid`），老存档无需迁移命令
>
> **一处架构决定**：`container → components` R1 从 1 增到 2，显式 `--update-baseline` 接受
> （容器身份 API 必须访问组件/附件注册表，与既有 `ContainerLivingItemHandler` 同一条边）。
>
> 单测 823 → 859。**待真机**：活化精妙背包 → 里面放活熔炉/活桶 → 关着 GUI 也烧 → 打开看流体层。

---

## 2026-10-21（夜）

> 🔗 **蹲下右键拾取不再丢容器级数据 —— 「交接」成为数据迁移的第三个挂点**（`D-container-12`）
>
> 真机现象：精妙背包**蹲下右键直接拾进手里**，包里的水 / 电力相位全没。
>
> **根因**：精妙的 `putInPlayersHandAndRemove` = `setItemInHand` + `level.removeBlock` ——
> 不产生掉落物、不走破坏流程 ⇒ `BlockDropsEvent` / `EntityPlaceEvent` 两个挂点**全落空**。
> 这是「第三方自己搬走容器、两个事件都不给」的第一类问题。
>
> **原计划被否**：`RightClickBlock` HEAD 暂存 → 同 tick 末按「方块没了 + 手上出现同种物品」认领。
> 否决理由：需要**认领判定**，而手上本来就有同款背包时会错认领 —— 错认领比丢失更难查。
>
> **采用**：联动模块 Mixin 挂在**唯一汇聚方法**上（精妙两条路 —— `useWithoutItem` 的蹲下分支与
> `playerInteract` 的副手分支 —— 都走它），零认领：
> - `HEAD`：`ContainerHandoff.capture(be)` —— 方块还在、附件还活着，**只记引用不读不写**
>   （目标方法中途失败时数据仍留在方块上，玩家还能打开 GUI 取用）；
> - `RETURN`：`ContainerHandoff.handoff(player.getItemInHand(hand))` —— 物品已在手里。
>
> **分层**（照 `D-container-09`，`check_layers.py` 全绿）：
> - **L1** `container/ContainerHandoff`：`capture` / `handoff` / `registerMover` + ThreadLocal 暂存
>   （两个时机同线程同栈帧 ⇒ 零分配；每次 capture 先清残留，上次 RETURN 没执行也不串味）；
> - **L3** 领域：`ContainerDataMigration.moveOut` / `PhaseDataMigration.moveOut` ——
>   **与破坏路径共用同一份搬运逻辑**（两条路径语义分叉 = 「破坏搬得对、拾取搬得歪」）；
> - **L2** `compat/sophisticated/SophisticatedMixinPlugin`（资源探测门控，与 `CreateMixinPlugin` 同构）；
> - **L4** `mixin/sophisticated/BackpackBlockMixin`（R2 要求 `@Mixin` 只能在 L4/L5）。
>
> **失效方向**：`@Inject(require = 0)` + `targets` 字符串（对精妙**无编译期依赖**）⇒ 改版改名
> **不崩服**，退回「数据随方块消失」= 修复前现状；失效可见性由 plugin 的 `postApply`
> 检查目标方法是否还在，缺失即 **ERROR** 日志（I-C6 降级可观测）。
>
> **守卫**：`ContainerHandoffTest` 6 项（捕获→交接 / 无捕获 no-op / 空栈不搬且丢捕获 /
> `capture(null)` 清残留 / 一个 Mover 抛异常不连累其它 / `discard`）+ `ContainerDataMigrationTest`
> 新增 3 项（contentRetaining 搬 + 清空、非 retaining 不搬、退化输入安全）。
>
> ⚠️ **顺带撞出一笔旧账**（写测试时暴露，未修）：`ItemStack.set(CONTAINER_FLUID_DATA_ITEM, …)`
> 在 NeoForge 数据组件校验下抛 `Data components must implement equals and hashCode` ——
> `ContainerFluidData` 是**可变对象**。校验只在开发开关下启用 ⇒ 生产「能跑」但属 UB。
> 已记 `TODO.md` §3.3（修法方向：给组件值做不可变快照 record）。

> 🔁 **真机复验失败 → 日志定位到「放置侧」**（同日第二段）
>
> 换 jar 实测：**水还是没了**。加三处埋点（plugin `shouldApplyMixin` / mixin HEAD+RETURN /
> `ContainerHandoff.handoff`）后一次定位：
>
> ```
> [SophisticatedMixinPlugin] 精妙背包探测（资源检查）: true
> [SophisticatedMixinPlugin] 已注入 BackpackBlock#putInPlayersHandAndRemove
> [精妙联动] HEAD 捕获：be=BackpackBlockEntity@493763f5
> [ContainerHandoff] handoff: movers=2 host=BackpackBlockEntity target=sophisticatedbackpacks:diamond_backpack
> [精妙联动] RETURN 交接：hand=MAIN_HAND moved=true
> ```
>
> ⇒ **拾取侧完全正常**（数据确实写进了手里的钻石背包）。问题在**放置**：
> - 精妙 `BackpackItem extends ItemBase`，**不是 `BlockItem`** ⇒ 放置走自己的
>   `tryPlace` + `level.setBlockAndUpdate`，**绕开 `BlockItem.place`**
>   ⇒ `BlockEvent.EntityPlaceEvent` **根本不触发**（我们唯一的放置挂点）；
> - 即使触发，`ContainerDrops.placedItemInHand` 按 `placedBlock.asItem()` 也认不出：
>   方块物品与 `diamond_backpack` 是**两个不同 item**。
>
> **修法：交接做成双向** —— `ContainerHandoff` 加 `stageRestore` / `restoreTo`，
> `Mover` 加 `moveIn`（**默认空实现**，只搬出的领域不用改）；领域把 `onBlockPlace` 里的恢复
> 逻辑提成 `moveIn`，事件路径与交接路径共用；新增 `BackpackItemMixin` 挂 `tryPlace` 的
> HEAD（暂存手持栈**副本**）/ RETURN（成功才恢复，失败 `discard`）。
>
> ⚠️ **暂存必须 copy**：`tryPlace` 末尾 `getItemInHand().shrink(1)` 是**就地改**同一个栈对象，
> 存引用会在 RETURN 时读到空栈 —— 守卫 `stageRestore_copiesStack_soShrinkDoesNotLoseData`。
> ⚠️ 这条同时覆盖**发射器放置**（`ModItems` 以 `player = null` 调 `tryPlace`），比事件路径更全。
>
> **方法论教训**：`libs/` 里没有精妙 jar（只有 `libs/src` 源码快照）⇒ 注入点从未在运行环境
> 验证过。接第三方钩子应先埋日志确认「注入成功 + 数据走到哪一步」，再谈修法 —— 否则会像本次
> 一样，把「拾取侧已修好」误当成「整条链路修好」。单测 811 → 815。

> 🔴 **真正的根因：容器级数据有两个副本，只写附件会被下一 tick 覆盖**（同日第三段）
>
> 第二轮真机日志：双向 Mixin 都执行了（`放置恢复 restored=true`、`handoff moved=true`），
> 水还是没了。回头看写回钩子才明白 —— **内存表才是唯一真相源**：
>
> | 副本 | 谁写 | 谁读 |
> |---|---|---|
> | 内存表 `CONTAINER_DATA`（按 cacheKey）| 功能 tick | 功能 tick |
> | 宿主附件 | `ContainerFluidWriteback` / `ContainerPhaseWriteback` **每 tick 从内存表覆盖** | 存档加载时的附件回填 |
>
> `moveIn` 只写附件 ⇒ 下一 tick 被内存表里的**空对象**盖掉。
>
> ⚠️ 为什么以前「破坏再放回」偶尔能成：`getOrCreateFluidData` 只在内存表**没有该键**时才走
> 「附件回填」。**换位置**放置 ⇒ 新 cacheKey 无残留 ⇒ 回填生效；**同位置**破坏再放置 ⇒
> 内存表里还留着**空的旧对象**（破坏时只 `clearAll` 清了内容、没删键）⇒ 回填分支不走 ⇒ 水消失。
> 真机复现的正是同位置场景 —— 这个 bug **与精妙无关，是所有容器的放置路径共有的**。
>
> **修法**：`moveIn` 改成「**同一个对象**同时放进内存表 + 附件」（只放一处：放附件会被覆盖，
> 放内存表则容器不 tick 时丢存档）；相位额外 `PhaseSnapshot.restoreInto(ContainerPowerData)`
> 把快照还原进账本（账本才是真相源）。拿不到 ctx（能力面未就绪）时降级为只写附件并 **WARN**（I-C6）。
>
> 顺带修 plugin 误报：第一版对所有 Mixin 都检查 `putInPlayersHandAndRemove`，
> `BackpackItemMixin`（目标是 `tryPlace`）每次启动都打一条假 ERROR ⇒ 改成按 Mixin 分别登记
> 期望方法（`EXPECTED_METHODS`）。单测维持 815。

> 🎯 **真凶：`default` 方法 + 方法引用 = 静默漏实现**（同日第四段，收尾）
>
> 诊断版日志说明一切：`handoff` 打了、`restoreTo` 打了，但**一条 `[交接]` 都没有**
> ⇒ `moveIn` 从未被调用。根因就是注册那一行：
>
> ```java
> ContainerHandoff.registerMover(ContainerDataMigration::moveOut);   // ← 只覆盖 moveOut
> ```
>
> 当时 `Mover.moveIn` 是 `default` 空实现 ⇒ **方法引用只覆盖它指向的那个方法**，
> 另一个方向静默失效：编译通过、启动正常、日志写着 `moved=true`，但放置方向永远什么都不做。
> 拾取侧一直是对的（数据确实进了手里的背包）⇒ 现象正是「拾取后水还在，放回去就没了」。
>
> **修法**：
> - `Mover.moveIn` **去掉 default** ⇒ 漏实现在**编译期**就报错（匿名类必须实现全部抽象方法）；
> - 两个 `*Registration` 改成匿名类，显式转发两个方向；
> - **反射守卫** `mover_hasNoDefaultMethods` —— 编译期挡不住「有人把 default 加回来」，用测试钉住。
>
> **通用教训（已审计全项目，其它注册口 `SlotAccessorFactory` / `ContainerLikeItems` 都没有这个问题）**：
> > **注册点用方法引用时，接口里不能留「可选实现」。**
> > 方法引用是「指向一个方法」而不是「实现这个接口」—— 接口里任何没被覆盖的抽象方法都会落到
> > default，于是「少实现一个方向」从**编译错误**变成**运行期静默失效**。
>
> ✅ **真机验证通过（同日，用户）**：蹲下右键直接拿手上 / 左键撸掉成掉落物 / 放置后 —— **三条路径流体都在**。
> 日志 `[交接] 流体已恢复：inMemory=true bytes-keys=3`（内存表与附件同写，3 个源槽位），
> 且不同位置反复放置均正常。单测 815 → 816 → 818。

> 🔍 **复盘审查（同日，提交后自查）** —— 三处收尾：
> ① **相位迁移此前零测试**（水的有 15 条、相位一条都没有，而它的搬运逻辑更复杂：
> `restoreInto` 还原账本 + `clock` 必须与 writeback 同源）⇒ 补 `PhaseDataMigrationTest` 5 项；
> ② **日志降噪**：正常拾取/放置每次打 5 条 INFO ⇒ 成功路径改 DEBUG，早退/失败保留 INFO/WARN
> （罕见才看得见，正常游玩不刷屏）；
> ③ `ContainerHandoff` 类 javadoc 补上「双向」与重复注册的边界说明。单测 → 823。

---

## 2026-10-21

> 🔻 **NeoForge 兼容下限 21.1.249 → 21.1.219，并撤回「编译版本 / 承诺下限」解耦**（用户报
> 「某些整合包的低版本 NeoForge 用不了本模组」）——
> 先查证再动手：`neoforge.mods.toml` 的 `versionRange` 直接吃 `gradle.properties` 的
> `neo_version`（21.1.249，而 1.21.1 全线有 260+ 个构建 ⇒ 卡在接近最高处）。
> **但门槛从来不是代码需要**：实测把 `neo_version` 改到 21.1.1，主代码与测试代码**一次编译通过**；
> 再清点 mixin 目标——只有原版 Minecraft 类与第三方联动类（后者有 ModList/插件门控），
> 跨 21.1.x 无注入点漂移风险。结论：历史下限是跟随生态版本漂上去的，不是硬约束。
>
> **取值 21.1.219**：用户拍板取**生态下限**——即本模组那条软依赖链自己要求的版本，
> 语义可自解释（「凡是能装那套软依赖的整合包都能装本模组」），而非绝对最低值。
>
> 🔴 **但「拆两个版本号」这一步本身是错的，当天就被 CI 打脸**（值得单独立一条，因为它是
> 「看起来更灵活、实际制造静默错误」的典型）：拆成「编译版本 `neo_version`（开发跑新 loader）
> + 承诺下限 `neo_min_version`（只进 versionRange）」后，两套版本**共用同一份产物缓存**，
> 缓存串了不会立刻炸，而是**悄悄产出错产物** ⇒ 本地一次**假绿**、CI 一次**假红**：
> CI 报 `cannot access TooltipFlagExtension`（`LivingHopperFunction.java:226` 的
> `flag.isAdvanced()`），而 **21.1.219 根本没有 `TooltipFlagExtension` 这个类** ——
> 21.1.249 把原版 `TooltipFlag` 改成了 `extends TooltipFlagExtension`，219 还是原版接口
> （`isAdvanced()` 是它自己的方法）。真因：CI 恢复了上个 commit 的 988 MB Gradle 缓存，
> 里面是 **249 的 patched MC jar**，与 219 的 NeoForge jar 混用 ⇒ 编译器按 249 的
> `TooltipFlag` 去要扩展类 ⇒ 报「找不到」。
>
> ⇒ **收口：只用 `neo_version` 一个值**（同时是编译版本与对外下限），解耦撤回。代价是开发
> 环境也跑 219（那条软依赖链恰好要求 ≥219 ⇒ 兼容）；收益是**结构上不可能「承诺 A、编在 B」**，
> 且 CI 的 `build` 作业编译的就是下限 ⇒ 每次 push 都在核验承诺，不需要第二个作业。
> 另给 CI 加了「NeoForge 版本变了就清一次产物缓存」的自愈步骤（否则换版本那一次仍会混用）。
>
> **教训两条**：① **能编译 ≠ 能跑，下限必须真跑一遍**（`TooltipFlagExtension` 这种「249 才有」
> 的东西靠推断是猜不出来的）；② **别把「同一件事的两个口径」拆成两个配置项** —— 拆开就得靠
> 额外机制保证它们不漂移，而缓存/默认值这类静默分叉最难查。本次未增删用例。

> 🚢 **实体容器入门：箱子船 / 箱子矿车 / 漏斗矿车 / 带箱驴骡**（用户拍板 B+B，设计稿
> `docs/buffer/container-menu-introspection-topic.md`）—— 用户诊断出根本问题「服务端对某些信息
> 完全不知，客户端却又很清楚；从客户端获取则客户端不可预测」，查证后**推翻前提**：
> `Slot` 的 `x/y` 是服务端就有的信息（`addSlot` 双端跑、坐标天然一致）⇒ 不必问客户端，
> 信任问题从根上消失。记「客户端列数探测」作废。
>
> **① 位置广播证伪关闭**：唯一候选需求是「活信标光束要跟着船走」，但信标宿主判据
> `LidBlockEntity` 的三个实现全是 BE（箱子/末影箱）——实体容器里的活信标本就**不开光**
> ⇒ 没有要广播的东西。枚举位置相关视觉（信标光束/流体 GUI 轨/图标 tooltip）后确认
> **无一需要广播**。顺带的认知收获：`LidBlockEntity` 判据其实是「容器有世界存在感」的
> 代理指标——当初以为是偷懒，实际选对了。
>
> **② viewer 降级翻案为天然正确**：`ContainerEntity extends Container` ⇒ 船/矿车菜单槽的
> `slot.container` 就是实体本身（`ChestBoat#createMenu` 传 `this`）⇒ 结构化分支加一条
> `instanceof Entity` 即零反射；`MenuSlotOwners` 无缓存 + 键每 tick 从 `ServerLevel#getEntity(UUID)`
> 反查**实时**位置 ⇒ 开着箱子船 GUI 时船在漂，图标/tooltip 跟真实位置刷新——**固定容器
> 位置不变所以看不出，实体容器是位置口径的最佳展示**。所谓「漂走」是伪问题（只在解析结果
> 被缓存时才存在）。
>
> **实现**：
> - `ContainerKeys` 加 `entity_<uuid>` 键形 + `ContainerKind.ENTITY` + `entityOf` 解析
>   （`tools/check_container_keys.py` 正则同步收紧）
> - `EntityContainerContext`：继承 `SimpleContainerContext`（explicitKey 构造器）；三处不同：
>   键、数据宿主（实体）、`getBlockPos()` 恒 **null**（防船停在箱子上方时被 `isLiddedChest`
>   误判开光）；列数先按实体类型 id 查规则表
> - 白名单 `EntityContainers`：`ContainerEntity` + `AbstractChestedHorse#hasChest()`
>   （**必须白名单**——NeoForge fallback 给所有 LivingEntity 注册了能力 ⇒ 「能力面存在」
>   ≠「该当容器」，I-C1 张力；无箱驴 2 格只是鞍+体甲）
> - 容器级数据宿主抽象 `ContainerContext#getDataHosts()`（BE/Entity 同为 `IAttachmentHolder`）
>   ⇒ 流体/相位 writeback 与回填改走宿主列表；实体附件持久化（"written, and read from disc"）
>   ⇒ 区块卸载再回来数据还在
> - `LivingItem#processEntityContainers`：与掉落物**合并**为一次 `getAllEntities()` 遍历
> - 驴的菜单解析：**先试 Mixin accessor 被否，改入口层反射**（两连坑，都记）：
>   ① accessor 的第一版返回类型写成 `AbstractChestedHorse` 被 Mixin 拒 —— accessor 类型必须匹配
>   字段的**声明类型**（`AbstractHorse`）；
>   ② 改对类型后撞上 **Mixin 包隔离**：「class is in a defined mixin package ... cannot be
>   referenced directly」—— mixin 包里的 accessor 接口被包外任何类引用（new/instanceof）都被
>   FML 拒，而消费方（container 包）与注册点（root 包）都在 mixin 包外 ⇒ accessor 实际不可用。
>   终案：`LivingItem.HORSE_MENU_RESOLVER` 反射 `HorseInventoryMenu.horse`（private final 无
>   getter），注册进 `MenuSlotOwners.registerEntityResolver`。反射只此一处、只对**原版类**
>   （不触 D-container-09 的第三方猜测禁令；项目内有 `ClientSlotResolve` 反射原版 SlotWrapper
>   先例），失败方向是显示不刷新。
> - ⚠️ **层校验抓住两个当天引入的方向违规**（`check_layers.py` 棘轮生效）：
>   `container → transfer`（EntityContainerContext 为查实体规则 import 了 transfer）与
>   `container → mixin`（EntityContainers import accessor）。修法不是加基线而是**重构**：
>   ① 放弃「实体类型规则查询」——没有消费方（register 只认方块容器），预留半截能力正是项目
>   反复踩的坑；② 实体解析改成**注册表**（`MenuSlotOwners.registerEntityResolver`），核心
>   （L1）不认识任何菜单类——反倒比 accessor 方案更贴 D-container-09。
> - ⚠️ 修两个自己引入的问题：`syncWorldContainer` 原从 `associatedBlockEntities` 取 level
>   （实体容器空列表 ⇒ 直接 return ⇒ 图标永不刷新）；`EntityContainers` 的 accessor 分支
>   第一版没过白名单（HorseInventoryMenu 是马/驴/骡共用类 ⇒ 普通马会被当容器）
>
> **列数零规则**：四类实体的启发式全对（27→9、5→5、15→5，靠 `resolveColumns` 的候选宽度顺序）。
>
> **行为定案**：无人区块停 tick（与 BE 卸载/掉落物/玩家下线三条既有路径一致）但不丢数据。
>
> 单测 782 → 796（新增 `EntityContainersTest` 12 项 + 反查测试 2 项，795 passed + 1 skipped）；
> `doc_check` / `check_layers` / `check_container_keys` 全绿。⚠️ 驴的 accessor 路径单测应用不了
> Mixin ⇒「驴能解析/普通马不当容器」待真机。**（当晚已真机确认：驴能解析、普通马不当容器。）**

---

## 2026-10-21（傍晚）

> 🔢 **「渲染和逻辑差了一格」——逻辑槽 / 物理槽两个口径的贯通**（真机驱动，接上午的鞍槽修复）
>
> 上午给马/驴/骡加了「跳过马鞍」的 +1 偏移（`getItem/setItem` 与 `getSize`），但只改了
> **读写**，没改**对外身份** ⇒ 五条通道各偏一格：
>
> | 通道 | 症状 |
> |---|---|
> | `syncWorldContainer` 的槽位比对 | 逻辑槽 0 的内容被推到**马鞍槽**的显示位 ⇒ GUI 整体上移一格 |
> | `ContainerRuntimeCache.update` 的键 | 客户端悬停 GUI 第 1 格（`getContainerSlot()=1`）查到的是第 2 格的数据 ⇒ tooltip 差一格 |
> | `trackLivingSlots` 的作废键 | 作废打空（键与存储键不同口径）⇒ 拿走后 tooltip 残留 |
> | `FluidFlowServerSync.buildPacket` 的 cells 键 + `fromSlot` | 客户端按物理槽分组匹配 ⇒ 整层水画偏一格 |
> | `pour`/`scoop`、`BeaconFeedHandler` | GUI 给物理槽、源键/列扫描要逻辑槽 ⇒ 水倒进上一格、信标列扫描偏一格 |
>
> **修法：一对互逆映射 + 在"跨边界"处强制过它**
> - `ContainerContext#toPhysicalSlot` / `#toLogicalSlot`（默认恒等；实体容器 ± 装备槽偏移）
> - `ContainerRuntimeCache.update` **改签名收 `ContainerContext`**（不再收裸键+槽号）⇒
>   映射在方法内部完成，调用方**不可能漏**（6 个领域调用点跟着改）
> - 作废键、流体包、GUI 交互各自显式过映射
>
> **教训（值得写进设计模板）**：给容器引入「前置非存储槽」这类偏移时，必须一次性枚举
> **所有跨边界点**——读写、对外身份（包/遥测键/渲染）、GUI 反查三个方向。只改读写 = 差一格，
> 而「差一格」在 GUI 上表现为「东西都在、位置全错」，比丢失更难反推。
>
> 守卫 +2：`toPhysicalSlot`/`toLogicalSlot` 互逆（驴 +1/-1、船恒等）、
> 遥测键用物理槽（驴的逻辑槽 0 落在 key 1、key 0 无数据）。单测 800 → 802。
>
> ✅ **真机复验通过**（当晚）：驴 GUI 5×3 对齐、tooltip 逐格对应、倒水落进所点格子。
> 实体容器这条线（`entity_<uuid>` 键 → 菜单自省 → 鞍槽偏移 → 双口径贯通）到此闭环。

---

## 2026-10-21（下午）

> 🐴 **驴/骡的槽位布局修正（真机驱动）** —— 用户实测：「驴的容器实际排列是 5×3，还有个马鞍槽位，
> 被识别成了 8×2」。查原版源码后确认根因：`AbstractHorse` 的 inventory 是
> **16 槽 = 索引 0 马鞍 + 索引 1..15 箱子格**（`INV_SLOT_SADDLE = 0`；
> `HorseInventoryMenu` 的箱子格从 `1 + l + k * columns` 起）。我们把它整个当存储格：
>
> | 症状 | 因果 |
> |---|---|
> | **8×2** | 启发式拿 16 槽：`16 % 9 ≠ 0 … 16 % 8 = 0` ⇒ 8 列 |
> | **幽灵物品**（未报但同源） | 鞍槽被当普通存储格 ⇒ 往那格倒水，玩家取不出（GUI 只渲染鞍） |
>
> 修法（`EntityContainerContext`）：
> - `SADDLE_OFFSET`：马/驴/骡 = 1，船/矿车 = 0（无装备槽）
> - `getSize()` = `super.getSize() - offset`（16 → **15**）
> - `getItem` / `setItem` 逻辑槽 + offset（槽 0 → handler 槽 1），**鞍槽永远碰不到**
> - `getWidth()`：马/驴/骡直接问原版 `getInventoryColumns()`（= **5**），其余走父类三级
>
> ⭐ `getWidth` 这条是「**原版给了 API 就用**」——与上午放弃的「查规则表」不同：那次放弃是因为
> 没有消费方；`getInventoryColumns()` 是原版对马格布局的权威回答，比启发式硬。
>
> 守卫 +2：鞍槽排除与偏移映射（含「写不碰鞍槽」）、船无偏移直通。单测 798 → 800。

> 🔧 **构建配置：`generateModMetadata` 缺 `filteringCharset`**（同日，被上午的 `clean` 暴露）——
> 现象是测试/启动全崩在 FML 解析：
> `ParsingException: Failed to parse data from Reader` / `Caused by: MalformedInputException: Input length = 2`。
>
> 根因：`src/main/templates/META-INF/neoforge.mods.toml` 含中文注释，而 `generateModMetadata`
> 没声明 `filteringCharset` ⇒ Gradle 的 `expand` 跟随**平台默认编码**（Windows 中文 = GBK）读
> UTF-8 模板 ⇒ 无法映射的字节被替换成 `?`（实测 `e7 94 bf` → `e7 94 3f`）⇒ 产物不是合法 UTF-8。
>
> **为什么一直没炸**：任务输出 up-to-date 时不重跑；只有 `clean` 或模板变更才重新渲染 ⇒
> 这个坑潜伏到 2026-10-21 上午 `clean` 第一次触发。同文件里 `generateBuildProps` **有**这一行
> （`80f71a4` 加构建指纹时加的），`generateModMetadata` 被漏掉。
>
> 修：补 `filteringCharset = 'UTF-8'` + 注释写明潜伏条件。
>
> 诊断方法（可复用）：扫全项目的 `.toml/.properties/.json` 严格 UTF-8 解码 + 查坏字节的 hex
> （`e7 94 3f` 这种「UTF-8 首字节 + `3F`」是「按错误编码读过再写回」的指纹）。

---

## 2026-10-20

> 🧭 **精妙形状复审（续 `D-container-09`）** —— 用户：「再检查一遍，还有哪些变得精妙形状了吧」。
> 按「核心不该长成某一个模组的模样」逐类审核心，审计 7 类接触面，找到**最后一处**真问题。
>
> **删掉的：`ContainerCompatibilityConfig.findRuleByNamespaceAndKeyword`**
> —— 按 `namespace` + **分词模糊匹配**查规则（拿规则 id 的任一词去比目标 BE id 的任一词，
> 同 namespace 即命中）。三个问题：
> ① 它在**猜**「这条规则大概适用于这个 BE」，与 `D-container-09` 同源冲突；
> ② **多档时只取第一档**（`list.get(0)`）⇒ 同 id 六档（精妙背包）时永远拿到第一档，
> 容量校验一挡就返回空，行为取决于「哪档先注册」；
> ③ 不可观测——现有 27 条规则全是精确 id，删掉后所有已知容器的列数**不变**
> （未注册类型走 Tier 2 原版类型表 / Tier 3 启发式，语义明确）。
>
> 守卫 `SimpleContainerContextTest.unregisteredType_doesNotBorrowRuleFromSiblingId`：
> 把 `minecraft:chest` 临时改成 3 列，再看 `minecraft:ender_chest`（27 格、path 与 chest 共享
> `chest` 词）的列数——**先红（9 vs 3）后绿**，精确复现了「借用」。
>
> **复核后判定「留」的六类**（都不是精妙形状）：
> | 接触面 | 结论 |
> |---|---|
> | 核心里的精妙代码引用 | 只剩 `ModSophisticated.init()` 一处接线（`compat` 包 + `ModList` 门控） |
> | `container_rules.json` 的精妙六档 | **数据不是形状**——规则表的设计目的就是收录已知容器（铁箱子、原版各一条），本身不猜 API |
> | `resolveColumnsFromContainer` 类型表 | 全是**原版**类（chest/hopper/dispenser/furnace…） |
> | Mixin 目标 | 全是原版类 + Create 联动（`mixin/create/`） |
> | `ClientSlotResolve` 的 `getSimpleName().contains("SlotWrapper")` | 猜的是**原版**内部类，不是第三方；主路径已由 Mixin accessor 覆盖，反射只是兜底 |
> | `LivingItemContainerCommand` 的 `be instanceof Container` | 只用于 Tier 2 推断（非 `Container` 传 null 走启发式），不参与身份判定 |
>
> 顺带修 `container-compat-topic.md` 三处过期表述：S3 仍写「三级含反射兜底」、仍写
> 「精妙背包走菜单级反射」（已下沉到联动模块）、以及「⏳ S3b 未做」（实际已完成）。
>
> 单测 772 → 773（772 passed + 1 skipped，0 fail）；`doc_check` 全绿。

> 🔌 **联动无尽箱子（AvaritiaNeo，同日）** —— 用户：「那就兼容一下这个无尽箱子吧」。
> 先读源码判断（`libs/src/AvaritiaNeo-main`）：`TileInfinityChest extends BlockEntity`，
> **不实现 `Container`**（内部 `SimpleContainer chest = new SimpleContainer(300)`）；
> 菜单槽的 container 就是这个 `SimpleContainer`，且菜单**没有** `getBlockPosition()`。
> ⇒ viewer 匹配三条路全断（图标 / tooltip / 流体渲染不刷新，GUI 倒汲静默无效），
> 和精妙修之前一模一样。
>
> **能用的部分**（不需要改）：`WrapperInfinityChest` 的 `insertItem` **尊重 slot 参数**、
> `setStackInSlot` 直接写槽、`getSlotLimit` = `Integer.MAX_VALUE` ⇒ S2 那批写回修复
> 对它走快路径，不截断超大堆叠。
>
> **改了两处，核心零改动**：
> 1. 内置规则补 `avaritia:infinity_chest` / 300 格 / **25 列**。启发式会算成 10
>    （300%9≠0、300%10=0），而 25 不在任何候选宽度里 ⇒ 必须靠规则，否则熔炉燃料槽
>    `slot + width` 读错（不烧）、漏斗 `slot ± width` 搬偏格子（看着像正常）。
> 2. 新增 `living/compat/avaritia/`：`ModAvaritia`（`ModList` 门控）+ `AvaritiaMenuResolver`。
>    判据是「**返回 `BlockEntity` 的 getter**」（`getTile` / `getBlockEntity`）——
>    拿到 BE 后 `getBlockPos()` 是公开 API，不再反射一层；包名前缀
>    `net.byAqua3.avaritia.` 限定，反射不扩散。
>
> 守卫 `AvaritiaMenuResolverTest` 4 项，其中「包名不匹配即不认」**先红后绿**验证过
> （临时去掉包名判据 ⇒ `expected: <null> but was: <BlockPos{x=11, y=64, z=-7}>`）。
> 替身 `FakeInfinityChestMenu` 故意放在 `net.byAqua3.avaritia.test` 包下，否则只能测到
> 「不认」测不到「认得」。
> 另加 `ContainerRuleConfigTest.bundledRules_containInfinityChest` 守住那条 25 列的规则
> （删掉它 = 熔炉静默不烧）。
> 单测 773 → 778（777 passed + 1 skipped，0 fail）；`doc_check` 全绿。

> 🧩 **跨形态保全收窄：只对「内容随物品走」的容器（I-C9，同日）** —— 用户：
> 「感觉代价太重了，而且没有意义。像原版潜影箱，模组背包这种，撸掉后还保有物品的容器，
> 才做流体数据保留才有意义。」
>
> 原实现是**无条件迁移**：只要有流体数据 + 有掉落物，就写进物品组件。结果原版箱子 /
> 铁箱子 / 炼金箱子破坏后得到「**空箱子但有水**」——内容（27 格物品）随方块消失了，
> 水却跟着物品走。半保留状态比丢水更让人困惑。
>
> 改成**数据声明**：`container_rules.json` 新增 `contentRetaining`（id 级，默认**不**迁移）：
> ```json
> "contentRetaining": ["minecraft:shulker_box", "sophisticatedbackpacks:backpack",
>                      "avaritia:infinity_chest"]
> ```
> - 在表里 ⇒ 流体跟着掉落物走（内容还在，水也在）
> - 不在表里 ⇒ 流体随方块消失（与内容行为一致）
>
> **为什么不做自动判断**：没有任何通用 API 能回答「这个容器的内容会不会跟着掉落物走」——
> 潜影盒用 `minecraft:container`、精妙用 SavedData（按 contents UUID）、无尽箱子用
> `CLUSTER_CONTAINER` 隐式组件、原版箱子什么都不带。自动判断只能逐个认组件 ⇒
> 那是 I-C1「只认能力面」的反面。做成数据后，玩家也能在配置里追加（只增不减）。
>
> 实现：`ContainerCompatibilityConfig.retainsContentsOnBreak(id)` +
> `ContainerRuleConfig` 的 `contentRetaining` 读写（内置与玩家取并集）。
> 放置侧**不判**：物品上已有数据就搬回 —— 旧存档里已带水的物品不会突然失效。
>
> 守卫 2 项，**先红后绿**验证过（清空 JSON 声明 ⇒ 两条同时红）：
> `contentRetaining_bundledDeclarations`（潜影盒/精妙/无尽 true；原版箱子/炼金箱子 false）+
> `contentRetaining_userConfigCanAdd`（玩家可追加、不能取消内置）。
> 单测 778 → 780（779 passed + 1 skipped，0 fail）；`doc_check` 全绿。

> 🔊 **电力相位快照也跨形态保全（同日）** —— 起因是审计 24 个活物品对容器能力的依赖：
> BE 附件里只有流体与电力相位**带 `serialize`**（存档级持久），而 `ContainerDataMigration`
> 只搬了流体 ⇒ 精妙背包 / 潜影盒里调好的多路满相布局（n=P），方块→物品后
> **相对相位被「重载时刻」洗牌**，玩家要重调（`PhaseSnapshot` javadoc 原话：「肉鸽感只留给真正的重新搭建」）。
>
> 实现与流体同构，但**搬法相反**：
> - `ContainerFluidData` 是**可变对象**且与内存表共享引用 ⇒ 必须 `snapshot()` 深拷贝后再交给物品
>   （否则后续 tick 的改动漏进物品；创造模式连续放置还会让多个背包共享同一份水）
> - `PhaseSnapshot` 是不可变 record，`ContainerPhaseWriteback` 每 tick 写入的是
>   `capture()` 产生的**新对象** ⇒ 直接搬即可，创造模式连续放置共享一份也无害
>
> 顺手把 I-C9 从「流体」扩为「**容器级持久数据**」，并立下判据：
> **只有附件带 `serialize` 的才搬** —— 应力（`container_stress_data` 无 serializer）与
> 红石账本（内存表、注释明说「不落盘」）都是运行时数据，下一 tick 从容器里的活物品重算，
> 搬它们只会把「磁盘账本说谎」面做大（与 `PhaseSnapshot` 只存 (P, φ, d) 慢变量同一个考量）。
>
> 守卫 2 项：相位 CODEC 往返保住 (P, φ, d) / EMPTY 快照不进物品。
> 单测 780 → 782（781 passed + 1 skipped，0 fail）；`doc_check` 全绿。

> 🧹 **层纪律修复：`check_layers.py` 抓到我今天引入的 2 条违规（同日）** ——
> 起因是用户让「顺便看看其它活物品的兼容性」，我顺手翻 `tools/` 时才发现这个工具会拦人。
>
> **① `living/domain/water → living/domain/power`（R3，真违规，我的锅）**
> 相位迁移写在 `ContainerDataMigration` 里 ⇒ 它 import 了 power 域。修法不是加基线，
> 而是**按层的语义拆开**：
> - 共享判据 `pickDrop` / `placedItemInHand` 下沉到 **L1** `living/container/ContainerDrops`
>   （这两个方法只碰原版 Item / ItemStack / BlockEntity，不碰任何领域类型）
> - 相位迁移独立成 `living/domain/power/PhaseDataMigration`
> ⇒ R3 从 12 条降到 **10 条**（净减 2）。流体类现在只管流体。
>
> **② `(root) → client/render` 4 → 7（RQ1，有意设计，显式接受）**
> `LivingItemClient` 是客户端入口，必然引用具体渲染器 ⇒ 这是 NeoForge 惯例。
> `--update-baseline` 接受，并在**基线文件里写了理由**：彻底消除要把入口移进 `client/` 包，
> 那要动 mod 入口组织、只能真机验证 ⇒ 记 TODO 3.2。
>
> **③ 顺带发现一个我引入但未修的 bug**：**大箱子的相位可能各得一份**。
> `ContainerPhaseWriteback` 每 tick 把**同一个** snapshot 写给所有关联 BE ⇒ 两半附件指向
> 同一对象；破坏一半时拿不到另一半的 BE ⇒ 清不掉 ⇒ 破坏另一半时再搬一次。
> 流体靠 `data.clearAll()` 就地清**内存共享对象**守恒，而 `PhaseSnapshot` 是
> 不可变 record，没有这个载体。候选修法两个都写在 `PhaseDataMigration` javadoc 与
> TODO 3.1，先记录（极端边缘场景：大箱子 + 多相电力 + 分两次破坏两半）。
>
> 单测维持 782（781 passed + 1 skipped，0 fail）；`check_layers.py` 通过；`doc_check` 全绿。

---

## 2026-10-19

> ✅ **S3 落地：viewer 匹配改位置口径（2026-10-19，真机反馈驱动）** —— 真机报「活熔炉图标要重开
> GUI 才正确」「tooltip 没有进度信息」。两者同因：非原版 `Container` 的方块实体（精妙背包方块）
> 在 viewer 匹配里永远返回空 ⇒ `syncWorldContainer`（图标组件定向同步）与
> `LivingItemRuntimeSync`（运行时遥测）双双静默不发。
> 修：新增 `MenuSlotOwners` —— 「菜单槽 → 容器位置」解析（原版 BE 直取 / 大箱子反射复用
> `ContainerContexts.reflectHalves` / **模组菜单 `getBlockPosition()` 按类缓存反射**，
> 精妙背包无需 compileOnly 依赖）；`ContainerContexts.isViewingPositions`、
> `ContainerRuntimeCache.findViewersByPositions`、`LivingItem.runtimeContainerPositions`
> （取代 `runtimeContainerInstances`，**不再要求 `be instanceof Container`** —— 这是 I-C5 的口径翻转）、
> `SimpleContainerContext.syncWorldContainer`、`LivingItemRuntimeSync.flush` 全部切到位置。
> 单测 756 → 761（760 passed + 1 skipped，0 fail）；新增 `MenuSlotOwnersTest` 5 项，
> 原 `LivingItemRuntimeContainerInstancesTest` 改名并翻转「非 Container → 返回位置」。
> 遗留（S3b）：活流体渲染 / 酿造台快照仍走 `Container` 口径。
>
>
> ✅ **S3b 同日收尾：`Container` 在 viewer 匹配里彻底退场** —— `FluidFlowServerSync`（活流体渲染）、
> `LivingBrewingStandFunction`（酿造台音效目标）改按位置口径。随后删除随口径退场而死的代码：
> `ContainerContexts.isViewing(Collection<Container>)`、`ownsContainer`、
> `ContainerRuntimeCache.findViewers / isViewing / menuContains`（`menuContains` 在 S3 后已是死代码）。
> 大箱子防虚影守卫从 `ContainerContextsTest.OwnsContainer` 迁到
> `MenuSlotOwnersTest.resolve_doubleChest_returnsBothHalves`（改为按位置匹配）。
> 单测 763 → 760（759 passed + 1 skipped，0 fail）。`Container` 剩余调用点全部是 I-C2 允许的原版语义用途。
>
> ✅ **玩家规则持久化也按档（同日）** —— S4 只改了内存注册表，玩家差异 `USER_RULES` 还是
> `Map<id, rule>` ⇒ 用指令校准同一 BE 第二档容量会覆盖第一档，重启后才暴露。修：
> `USER_RULES` → `Map<id, List<rule>>`，删除记录 `REMOVED_KEYS` → 按 `(id, 容量)` 单档
> （旧配置裸 id 按「全档移除」兼容读）。⚠️ 删除记录必须按档：若仍是 id 级，
> `addAndSave` 清移除记录时会把别的档一起复活。新增守卫 `multiTierUserRules_coexist`
> （含「重启后两档都还在」）。单测 760 → 761。
>
> ✅ **resolve 也按位置解析模组容器（同日，真机驱动）** —— 真机：tooltip 已正常；但
> 「往背包里倒水，活水源出现在玩家物品栏而不是背包里」且「打开背包界面不渲染活流体」。
> 同根：`ContainerContexts.resolve`（GUI 边界带的汲/倒/空槽交互入口）只认 `Container` 实例，
> 对精妙背包恒返回 null ⇒ 对背包槽位倒水<b>静默无效</b>；改点背包 GUI 下方的玩家物品栏槽位
> 倒是能成，源就落进玩家背包；背包容器没源 ⇒ 自然也不渲染流体。
> 修：`resolve` 追加一条**与 `MenuSlotOwners` 同源**的菜单位置分支（放在最后、玩家背包槽位
> 已在分支 ① 提前返回 ⇒ 不会把背包 GUI 下方的 36 格误并进方块容器）。
> 新增守卫 2 项（模组容器槽位 → `chest_x_y_z` 同源键 / 玩家背包槽位不落进方块容器）。
> 单测 761 → 762。
>
> ✅ **活流体改挂 NeoForge 渲染事件（同日，真机驱动）** —— 真机「能倒进背包了，但不渲染」。
> 根因不在数据在**挂点**：流体绘制原先注入 {@code AbstractContainerScreen.render} TAIL，
> 而精妙的 {@code StorageScreenBase.render} <b>自己抄了一份 {@code super.render}
> （{@code renderSuper}）且不调用父实现</b> ⇒ 父类里的注入字节码永远不执行。
> 同类受害的还有任何「只在 render 里做事」的 Mixin —— tooltip 没事是因为它挂 {@code renderTooltip}
> （精妙的 render 确实会调它）。
> 修：抽出 {@code ContainerFluidOverlay} + 订阅 {@code ContainerScreenEvent.Render.Foreground}
> —— 原版发、精妙照抄的那段也发，一个挂点通吃，不必给每个模组补 Mixin。
> ⚠️ 坐标：事件在已按 (leftPos, topPos) 平移的位姿里触发 ⇒ 先 {@code pose.setIdentity()}
> 再用 {@code getGuiLeft()/getGuiTop() + slot.x/y}，否则多乘一次偏移。
> 单测不变（762）；`AbstractContainerScreenMixin` 瘦身约 190 行。
>
> ✅ **容器级数据跨形态保全（同日，真机驱动）** —— 真机「背包里的活流体，在背包变成物体形态后就没有了」。根因：容器级流体数据挂在 **BE 附件**上，破坏方块时 BE 被移除 ⇒ 附件随之消失；而精妙背包 / 原版潜影盒的**物品内容**存在别处（contents UUID / `minecraft:container`）⇒ 保留 ⇒ 「内容还在，流体没了」。
> 用户定案：**流体保持「容器属性」语义，不物品化**（不占槽位、不是 ItemStack）⇒ 要解决的不是「流体是什么」，而是「属性存在哪」。
> 修：新增物品组件 `CONTAINER_FLUID_DATA_ITEM`（复用 `ContainerFluidData.CODEC`），`ContainerDataMigration` 订阅两个挂点 —— **破坏**用 `BlockDropsEvent`（掉落物已确定、**尚未入世**、BE 尚在，这是唯一能在数据还活着时交给掉落物的时机；`BreakEvent` 太早、没有掉落物）、**放置**用 `EntityPlaceEvent` 从手持搬回。
> ⚠️ 两个必须守的细节：① **双箱守恒**——迁移后清两半附件，否则两次破坏各得一份 = 复制；② **放置侧校验物品身份**——只按「手上有数据」匹配，会在手持带数据的背包去放置另一个有 BE 的方块时错塞。
> 单测 762 → 768（新增 `ContainerDataMigrationTest` 6 项，含 CODEC 往返保住派生源）。
>
> 🧭 **核心不做第三方 API 猜测（同日，`D-container-09`）** —— 用户定调：「精妙背包只是我们泛化容器兼容性的**一个例子**，不能最后把模组弄成了精妙背包的模样。对于某些难以兼容的部分，要当作**联动模组**去做逻辑，就像活水车那样，而不是将就本模组的一致性。」
> 据此把 `MenuSlotOwners` 里为精妙内置的 **`getBlockPosition` 反射兜底整体移除**：核心只剩「结构化（原版 BE / 大箱）+ 注册口」两级，**不再内置任何反射第三方方法名的兜底**。
> 反射下沉到 `living/compat/sophisticated/`：`ModSophisticated`（`ModList` 门控，与 `ModCreate` / `ModSable` 同构）+ `SophisticatedMenuResolver`（**判据先卡包名前缀**，反射不扩散到任意第三方；只认 `getBlockPosition` 一个方法名、只认 `BlockPos`/`Optional<BlockPos>`）。
> ⇒ 模组改名时失效的只有该联动模块，核心与其它模组不受牵连。
> 守卫：`MenuSlotOwnersTest.coreDoesNotReflectThirdPartyMethod`（菜单自带 `getBlockPosition` 核心也不认）+ `SophisticatedMenuResolverTest.ignoresForeignPackageWithSameMethod`（包名不匹配即不认）。替身 `FakeBackpackMenu` 故意放在对方包名下，才能测到正路。单测 768 → 769。
>
> 🔍 **当日改动全量自审（用户「今天改了这么多的地方，审查一遍吧」）** —— 审出 3 处真问题，全修：
> ① **跨形态迁移存了活对象引用**：`ItemStack#set(component, value)` 存的是**对象引用**，而 `ContainerFluidData` 是可变对象（每 tick 重算 flows）⇒ 掉落物组件与 BE 附件共享同一实例，后续任何 tick 改动都会漏进物品里。修：`snapshot()` 深拷贝后再交给物品。
> ② **双箱可能复制**：`BlockDropsEvent` 触发时方块「可能已被移除、也可能还没」（NeoForge 文档明说时机不定）⇒ 用 `DoubleChestPositions.find` 按方块状态找另一半会时灵时不灵，漏清 ⇒ 破坏另一半时再迁移一次 = 复制。修：改为 `data.clearAll()` 就地清**对象本身**（两半在 tick 期间共享同一实例）。
> ③ **创造模式可复制**：放置不消耗物品 ⇒ 同一 ItemStack 连续放置会让多个 BE 共享同一份可变数据（水在几个背包里同时出现）。修：放置侧也走 `snapshot()`。
> ④ 顺带修 `removeAndSave` 误清旧格式通配删除记录（`sizes` 为空时既不补单档键又抹掉通配记录 ⇒ 删除记录丢失 ⇒ 重启复活）。
> 新增守卫 4 项（snapshot 独立 / clearAll 一次清干净 / 旧格式删除记录不被抹掉 / 已有守卫复核）。单测 769 → 772。
>
> ⚠️ **同日收紧：反射降为兜底（用户「反射的兼容性好像不佳来着」）** —— 把 `MenuSlotOwners`
> 改成**三级**：① 结构化（原版 BE / 大箱子，无反射）→ ② **注册的 compat**
> （`registerMenuResolver`，编译期安全）→ ③ 反射兜底。并收紧反射判据：**只认
> `getBlockPosition` / `getBlockPos`，刻意不认 `getPos`**（泛名字可能命中玩家/合成格坐标 ⇒
> 把包发错人）；失效时打 `ModLog.CONTAINER` debug（每类一次，满足 I-C6）。
> 风险定性：反射失败只退化为「能 tick、显示不刷新」，不损坏数据；但**长期可靠必须走 ②**。
> 新增守卫 2 项（只暴露 `getPos` 不被采用 / 注册解析器优先于反射），单测 761 → 763。

---

## 2026-10-18

> 🐛 **修复：容器列宽的「双源漂移」（漏斗：代码表 1 vs 规则表 5）** —— 框架演进提案 C 的收尾
> （用户「关于 C」）。全仓扫描确认：36 处 `instanceof` 里只剩 `resolveColumnsFromContainer`
> 一处真白名单，而它**与 `container_rules.json` 打架**：Tier 1 规则说漏斗 5 列（GUI 5 槽一排），
> Tier 2 表写 1 ⇒ 实际生效 5（规则优先）但 `/livingitem container` 的诊断命令走表显示 1（误导），
> 规则加载失败时还会静默变回 1。修：表值 `1 → 5` + 表头补「与规则文件重叠的条目必须一致」契约。
> 新增守卫 `typeTable_agreesWithRegisteredRules`：构造箱/漏斗/发射器/投掷器的 BE，
> 断言 Tier 2 表 == Tier 1 规则（熔炉族/酿造台规则未覆盖，表是唯一来源，单独断言宽度 1）。
> 至此 **C 完成**（宿主协议化 `LidBlockEntity` + 列宽双源对齐 + 守卫）。751 → 752 绿。

> 🐛 **修复：喂锭判级的列宽与 tick 分叉（108 槽铁箱子 12 列）** —— 审查发现「柱高算法三处
> 各写一遍」的第三处（喂锭 `readPyramidLevel`）把**列宽写死 9**，而 tick 用
> `context.getWidth()`（`container_rules.json` 里铁箱子族 108 槽的钻石/水晶/黑曜石显式配置
> **12 列**）⇒ **12 列容器里喂锭的等级判定与 tick 不一致** ⇒ 可能错误拒绝（正常操作被拦）
> 或错误放行（配置写入但 tick 时该槽休眠）。修法：喂锭改用
> `ContainerContexts.resolve(player, slot)` 拿与 tick **同一个**容器上下文（同 size/width/getItem），
> 列宽自然一致；`resolve` 返回 null 的容器（战利品箱未开箱等）回退到原 9 列直读，行为不变。
> 抽 `columnSlotsFor(ctx, slot)` 供单测钉住「宽度必须取自上下文」；客户端那份
> （`LivingBeamRenderer.localColumnHeights`）**不动**——它只跑玩家背包（恒 9 列），
> javadoc 已注明「第二份实现、改动需双侧同步」。单测 +2（749 → 751 绿）。

> 🔍 **活信标：最终审查（修 1 个阻断 + 一批一致性清理）** —— 用户「最后审查一遍」。
> **① 阻断：玩家宿主光束在多维度服务器上失效** —— `BeaconPlayerHostSync` 的登记表是
> **全服一张**（玩家背包对所有维度统一处理），却在 `flush`（**逐维度**调用）末尾 `clear()`
> ⇒ 第一个维度抽干全表、后续维度恒拿到空表 ⇒ 多维度服上玩家宿主光束只在第一个维度有效
> （单机 / 单维度测不出来）。修：新增 `beginTick()`，由 `onServerTick` 在玩家循环**之前**清一次；
> flush 不再清。新增 `BeaconPlayerHostSyncTest` 3 例（两维度各自只发本维度 / beginTick 清残留 /
> 自己那份不下发）。
> **② 残留清理**：删 `BeaconChestHost.active`（昨天重构留下的无用中间层，注释所述「去重基线」
> 实为 `lastSent`）；柱高与列存在性共用同一次 `resolveColumnSlots`（原先每信标每 tick 解析两遍）；
> tooltip 生效数算一次（原先状态行与账本各算一遍）；面板缺口合计收敛回
> `BeaconPyramid.deficitsToNext`（消除同一算法两份）。
> **③ 健壮性**：`OreType.byId/byOrdinal` 未知值回落 IRON（原先 `valueOf` 遇脏存档直接抛）；
> 两个宿主包解码容量钳制（负数 varint 炸 ArrayList / 超大 OOM）；`BeaconRuntime.EMPTY` 死代码删除。
> **④ 注释 / 文档口径订正 7 处**：R=32→256（注释落后于常量）、offhand「第 4 行」→ index 40、
> 「球体」→ AABB（原版就是立方范围）、`BeamColorSections` @return「+1」→ 0（与实现 / 类注释对齐）、
> 客户端缓存 volatile 理由（写入已走 enqueueWork）、`BeaconHostPacket` 补 glassColors 字段、
> 「不透明截断」注明实际由调用方做；§4 对齐表白底段「高 1」→ **高 0**。
> 复核后**不成立**（未改）：副手已被本地渲染循环覆盖（`getContainerSize()` = 41）、
> 客户端与服务端玩家背包口径一致（都是 41 格 / 9 列）。单测 746 → 749 绿。

> 🆕 **活信标：同容器多枚信标每枚一条光束**（用户定案）—— 取代原先「按容器 merge
> （等级取 max、颜色取胜出者）」的未定案行为。① **光束每枚一条**：宿主键从容器键改为
> `容器键#槽位`（`BeaconChestHost.hostKey`）⇒ 各自的等级 / 容器玻璃色 / 光束互不覆盖；
> 大箱两半本就分位置，同箱多枚同位置多条、颜色各自不同（各扫各列上方的玻璃）⇒ 一眼看出
> 哪枚配了什么色；**全同条目去重**（等级 + 玻璃色全同 ⇒ 两条重合光束与一条无异，画两遍
> 只会双重混合变亮）。② **开盖仍按容器**（本次最易写错的边界）：一箱两枚取走一枚，
> 盖还得开着 ⇒ `flush` 按容器键归组节流、整箱再无信标才关。③ 玩家宿主不受影响
> （背包多枚同起点同方向、几何重合，维持只画第一条）。④ 客户端与包格式**零改动**
> （渲染器本就按条目循环、Entry 已带颜色）。新增 `BeaconChestHostTest` 9 例
> （键格式 / 去重 / 两枚开一次 / 取一枚不关 / 全走才关 / 20 tick 节流 / 关后重开立即发 /
> S2C 每枚一条 / 内容不变不重发），737 → 746 绿。§6.1 补多信标实测项。

> 🐛 **修复：活信标取消活化不清效果配置**（用户实测）—— 设计稿 §2.5 定的「取消活化清空」
> 一直没真正落地：`BeaconFunction` 漏了 `getOwnedComponentTypes()` 自声明 ⇒ 框架
> `clearLivingData` 只清「IS_LIVING + 各功能声明过的类型」⇒ `BEACON_CONFIG` 无人认领、
> 取消活化后原样幸存（再次活化读到脏旧值——正是 `LivingItemFunction` javadoc 警告的
> 孤儿数据）。修：`BeaconFunction` 声明 `BeaconComponents.BEACON_CONFIG` 归属（方案 A：
> 取消活化 = 白纸，和其余 15 个域一致；配置是 5~15 锭的投入，但「取消即清」心智最简）。
> **守卫补齐**：`ComponentOwnershipTest` 此前有两个盲区让信标漏网——反射扫描只翻
> `LivingComponents`/`LivingItemManager` 两个框架级 holder（`BeaconComponents` 在域级
> 扫不到）、`allFunctions()` 硬编码名单没有 `BeaconFunction`（javadoc 写着「新增功能类
> 同步此处」，信标接入时漏了）。两处都补上，先红后绿机械证明能抓。新增测试 ④
> （活化→喂 2 槽→取消活化⇒配置不在），736 → 737 绿。
> ⚠️ 顺带踩坑记录：`registerFunction` **不去重**（FUNCTIONS 直接 add），自维持函数清单
> 注册期从 FUNCTIONS 算出 ⇒ 测试里注册两遍会让红石类每 tick 跑两遍（实测火把环振荡
> 失规、发电机锁相 2t 变 3t）。ComponentOwnershipTest 的注册已收拢到 `@BeforeAll`
> 全类一次——将来加测试方法别在每个 @Test 里注册。

> 🐛 **修复：末影箱遥测包冲掉容器 GUI 数据（tooltip 文本行 + 信标 9 柱面板 + 发电机面板在容器里全丢）**
> （用户实测「柱状图在背包里显示，容器里不显示；末影箱里有活信标就这样」）—— 根因三层叠加：
> ① 末影箱容器**每 tick 被 tick**（不开 GUI 也跑）⇒ 有活物品即恒脏；② 其运行时包按 UUID
> **无条件每 tick 直发主人**（无 viewer 匹配、无内容去重）⇒ 20 包/秒恒流；③ 客户端通用缓存是
> **单槽**（只存最近一个容器快照）且末影箱 kind 被路由进通用缓存（`handle` 只分 PLAYER_INV/其余
> 两档）⇒ 恒流把「当前打开的世界容器」快照冲掉 ⇒ 悬停读到末影箱槽位（对不上）⇒ EMPTY。
> **同族 bug 流体轨 2026-10-06 连修三次**（`FluidFlowServerSync`「第 ③ 次泄漏修复」原话：
> 末影箱的水渲染到别的箱子界面上），本次照抄其答案：
> - **服务端门控（根治）**：`LivingItemRuntimeSync.shouldSendToOwner`——末影箱包只有玩家真开着
>   末影箱 GUI 才发（`ContainerContexts.isViewingEnderChest`，流体轨同款接线点）。一击两治：
>   不再冲缓存 + 流量与世界容器一致（开着 GUI 才发，告别 24/7 恒流）。同连接 FIFO 保证无在途竞态；
> - **读取端按悬停槽位的容器路由**：`LivingItemTooltip.resolveHoveredRuntimeData`（文本层/信标面板/
>   发电机面板三处共用，消灭三份同款逻辑——修复要同步三处正是原放大因素）——背包槽
>   （`slot.container == player.getInventory()`，任何 GUI 的背包分栏都算）读背包缓存，其余读通用
>   缓存，替掉「按屏幕类型二分」（末影箱 GUI 里同时有末影箱槽与背包槽，旧逻辑把背包槽也送进
>   通用缓存 ⇒ 容器界面里悬停自己背包的信标读到末影箱数据）；
> - 末影箱 vs 普通箱子**故意不分辨**：门控后通用缓存里只有当前打开的那个非背包容器在写；且客户端
>   本就分不清两者（末影箱 GUI = GENERIC_9x3 + SimpleContainer 替身，与单箱同形，
>   `FluidFlowClientCache.RenderTarget` 注释实证——任何 `instanceof PlayerEnderChestContainer`
>   的客户端判据都是死分支）。
> 无包格式变更、域无感。新增 `shouldSendToOwner` 谓词测试 3 例（733 → 736 绿）。
> 已知残留：开关 GUI 后第一个 tick 内通用缓存还是上一个容器的数据（≤50ms 瞬错，自愈）。

> 🔍 **活信标：末影箱发光逻辑实证更正**（用户提问「末影箱的发光逻辑是怎样的呢」）——
> 上一轮协议判据重构时我把末影箱标为「新纳入方块宿主」，**这是误读**。实证（`EnderChestBlock`
> 无 capability 注册 + `EnderChestContainerContext` 空 positions + `processEnderChest` 链路）：
> 末影箱方块**不暴露 `ItemHandler.BLOCK` 能力面** ⇒ 世界容器扫描压根发现不了它 ⇒ 其内容由
> `processEnderChest` 按**玩家锚定容器** tick（键 `player_<uuid>_ender_chest`，
> `ContainerKind.ENDER_CHEST`，`isOwnedByPlayer` 直发本人）⇒ `getBlockPos()` 为 null
> ⇒ 活信标走**玩家宿主**：光束从头颅顶面射出、buff 跟人走、联机广播——**与放背包里完全一样**；
> 末影箱方块本身不 tick / 不开盖 / 不发光。设计稿 §0 宿主定义、§2.2、§2.3 机制表/覆盖矩阵
> 三处旧表述（「末影箱被排除」/「锚在方块上」/「纳入方块宿主」）全部更正；`BeaconFunction`
> javadoc 同步。**语义上这个设计反而更对**：末影箱是 per-player 无线容器，跟着玩家发光符合
> 原版语义，且天然避开「一个方块对应 N 个玩家内容，光束画谁的」的无解问题。
> 无代码行为变更（注释 + 文档勘误），测试维持 733 绿。

> 🐛 **修复：改变光束颜色时第一格不变色**（用户实测）—— 底部那格白是「信标自身
> 那一格」的过度模拟：原版光束从信标方块底面画起、第 1 格就是信标格（信标自身实现
> `BeaconBeamBlock` = 白）⇒ 原版底部 1 格白**有实体依据**；活信标没有信标方块那一格
> （起点就是箱口顶面 / 头颅顶面）⇒ 那格白没有对应物，第一格就该是玻璃色。修：
> `BeamColorSections.build` 白底段高度 **1 → 0**（不占格），玻璃段从底部第一格起画。
> ⚠️ 与 10-16「贴着放玻璃整条消失」的修复同源：那次把 0 高白段当终止条件的渲染 bug 改成
> 「0 高段 continue 跳过」——本修复正是靠那个 continue 兜底才能安全回到 0。白段 0 高 =
> **有意偏离原版**（原版底部 1 格白有实体依据，我们没有）。单测期望同步（4 例）。

> 🎯 **箱子宿主光束起点回归格底（对齐原版几何）**（用户「容器宿主的光束为什么
> 不从底部画起呢」）—— 曾选箱口顶面（y+1）是怕关盖时盖面（0.875~1 不透明模型）把光束切一截
> ⇒「箱内一截+盖上断开+顶上继续」三段观感。复盘：**该顾虑多余**——光束只在已激活时画，而激活时
> 盖必是开的（`BeaconChestHost` 的 blockEvent 开盖保持）⇒ 盖面立起不挡。改回格底起点
> （`atBottomCenterOf(pos)`，与原版信标「从方块底面画起、模型自挡」同几何）⇒ 开盖时
> 「光从箱口里长出」而非「悬在立起的盖子上方」。玩家宿主不动（「像帽子」语义 = 头顶面）。
> §4 对齐清单该行翻案：箱子宿主从 ⚠️ 偏离变 ✅ 对齐。渲染路径无单测 ⇒ 游戏内实测。

> 🆕 **宿主判据升级为协议级 `LidBlockEntity`（删 compat，零维护覆盖所有带盖容器）**
> （用户「某些容器里没有光束」+ 提供 `libs/src/ironchest-1.21.x-neoforge` 源码 ⇒ 实证铁箱子
> **不继承 `ChestBlockEntity`** 被白名单漏掉；随后用户「岂不是得专门兼容」一问促成重构）——
> 逐个兼容不可扩展，而我们真正需要的（能开盖保持 + 标准开盖协议）正是原版接口
> `LidBlockEntity`（`ChestLidController` + `triggerEvent(1,·)`）的**充要条件** ⇒ 判据
> `be instanceof LidBlockEntity`（纯 vanilla 接口，零反射零软依赖，`IronChestCompat` 已删）。
> **覆盖矩阵**（逐个核源码）：原版箱/陷阱箱 ✅ · 铁箱子全家 14 种 ✅（原需 compat，现自动）·
> **末影箱 ✅ 新纳入**（`triggerEvent(1,·)` 逐字同款；原被排除只因不在旧白名单，非语义决定）·
> 木桶 ❌（未实现该接口，与 2026-10-14 旧结论一致）· 潜影盒 ❌ · 未来第三方带盖容器
> **自动纳入零维护**。守卫测试 `BeaconFunctionTest`（箱/末影箱 true、木桶/潜影盒 false、null 安全）。
> 铁箱子边界记 §2.3（泥土箱 1 槽死位 / 水晶箱半透明待实测 / 12 列宽自适应）。

> 📐 **口径：容器边界契约立项（2026-10-18，用户提「框架层面的大内容，需要立文档」）**
> —— 起因是精妙背包兼容性排查：`insertItem(slot, …)` 在第三方容器里**可以忽略 slot**（精妙
> 核心 `InventoryHandlerSlotTracker` 的非 simulate 分支改走整库插入），而本模组把它当「写回指定
> 槽位」用 ⇒ 写回错槽 / 吞物品 / 复制。这不是某模组的 bug，是本模组「槽位 = 精确地址」与
> `IItemHandler` 契约「槽位 = 建议位置」两套模型的对撞。
> 新增契约 [container-boundary.md](../system-design/container-boundary.md)：I-C1 能力面发现 ·
> I-C2 `Container` 只用于原版语义 + 槽位体系探针 · I-C3 写回≠插入（`setStackInSlot`）·
> I-C4 模拟与真实同源且模拟只是上界 · I-C5 身份/viewer 按位置键 · I-C6 降级可观测 ·
> I-C7 写后校验。同时澄清「**扫描展开**（外→内）」与「**活物品内部递归**（内→更深）」是两套
> 机制、共用一条**深度预算**（否则「扫描 1 层 + 活潜影箱 4 层」会叠成 5 层）。
> 专题计划 [container-compat-topic.md](../buffer/container-compat-topic.md)：S1 契约+守卫（替身
> `SlotIgnoringHandler` / `ExtractBlockedHandler`）→ S2 写回收敛 → S3 身份/viewer → S4 列数多容量
> → S5 扫描展开深度 1。范围明确不做：扫描深度 ≥2、活物品内部容器通用递归、GUI 布局适配。
> 同时修 `/livingitem container register` / `inspect` 的判定口径（`be instanceof Container` →
> `ItemHandler.BLOCK` 能力面 + `handler.getSlots()`）——原先对精妙背包方块直接报
> `not_looking_at_container`，**玩家连自助校准列数都做不到**。
> 守卫 `ContainerBoundaryContractTest` 3 项（S2 前 `@Disabled`，实测解除后三条全红：
> 写回错槽 / 物品复制 / 模拟真实不同源）。单测 751 → 755（751 passed + 4 skipped）。
> 旧结论翻转：TODO 的「容器能力白/黑名单」**不采纳**（排除 = 放弃兼容 + 永久适配债）——
> `D-container-05`。同日登记 `D-container-01..07`（能力面发现 / 写回≠插入 / 模拟真实同源 /
> 身份按位置键 / 否黑名单 / 两种嵌套共用深度预算 / 池不作宿主）。
>
> ✅ **S2 落地：写回路径收敛（同日晚）** —— `SimpleContainerContext.setItem` 从
> `extractItem + insertItem` 改为**优先 `setStackInSlot`**，不可修改的 handler 走回退路径
> （校验抽取完整性 → 插入 → `ItemStack.matches` 写后校验，任一步不成立只告警不静默）。
> 超量写入按**槽位上限**截断（**不按物品 `maxStackSize`** —— 否则会把精妙背包堆叠升级 /
> 抽屉的合法超大堆叠砍回 64）。附带修同类「自己的代码」：`LivingChestItemHandler` 的
> `insertItem` 真实写入走 `LivingChestFunction.insertItem`，**同样忽略 slot**（整库语义）⇒
> 补 `setStackInSlot`；`LivingEnderChestItemHandler` 一并实现 `IItemHandlerModifiable`。
> 3 项守卫由红转绿；单测 755（754 passed + 1 skipped，0 fail）。
> ⚠️ 语义边界：`setItem` 现为**无条件精确写**（不再过 `isItemValid`），符合所有调用方意图
> （写自己容器的确定槽位）；跨槽插入必须先 `simulateInsertItem` 把关。
>
> ✅ **S4 落地：列数规则支持多档容量（同日晚，真机反馈驱动）** —— 真机报「活熔炉不烧」，
> 根因是**列数**：熔炉燃料槽在 `slot + width`（默认 DOWN），108 格精妙背包被启发式算成
> **9 列**（实际 12）⇒ 燃料槽读 `slot+9` 而 GUI 燃料在 `slot+12` ⇒ 永远读不到燃料 ⇒ 不烧；
> 活漏斗只用 `slot ± 1` ⇒ 看起来完全正常。修：规则键从 `ResourceLocation` 改为
> **`(id, containerSize)`**（`RULES` 改 `Map<id, List<rule>>`，**JSON 格式不变** ——
> `containerSize` 本来就是键的一部分）；`getWidth` 用 `findRule(id, getSize())` 精确匹配；
> 内置规则补精妙背包六档（27/45/54/81 → 9 列，108/120 → 12 列）。
> 顺带修一处**跨测试类共享静态状态**的顺序性 flaky（`ContainerRuleConfigTest` 删除内置规则后
> 未恢复 ⇒ 若它先跑，后续类会看到 `minecraft:chest` 消失）：加 `@AfterEach` 用空目录重新 `load()`。
> 单测 755 → 756（755 passed + 1 skipped，0 fail）。
> 遗留：`USER_RULES` 仍是 `Map<id, rule>`，玩家侧无法同时保存同一 BE 的两档容量（内置不受影响）。
>


---

## 2026-10-16

> 🆕 **活信标 Phase 1：域包注册 + 玩家宿主光束（纯渲染）** —— 新活物品「活信标」开工。
> 设计稿 `docs/tech/living-beacon-tech.md` 同日定稿（§0 玩法 / §2.1~2.7 技术方案 / §4 对齐清单 /
> §5 待决池 / §6 落地顺序 / §6.1 游戏内验证点）：
> - **宿主光束**：玩家（头顶跟视线）/ 箱子（箱口垂直 + 开盖保持），被不透明方块截断、联机可见；
> - **底座 = 下方一行 9 列「1D 金字塔」**：每列柱高 = 原版方锥该列覆盖各层边宽之和，
>   合计 9/34/83/164 与原版逐字一致且形状保真；判定 = 每列 ≥ 目标、取满足的最高 N（少一块掉档）；
> - **效果**：等级门控矿石池（铁/金 L1、下界合金/绿宝石 L2、钻石 L3、再生满级白送）、
>   II 级仅 L4（仿原版 `主==副`）、免费再生三档、buff 数 = `min(堆叠数, 当前档池)` + 满级免费再生；
> - **图标极简**（无 spec，默认活标记）+ **tooltip 两路**（静态组件账本 + `BeaconSegment` 遥测，
>   二期 F3+H 小金字塔面板）+ **§2.7 复用图谱**（11 项现成基建 / 8 件新写代码 / 3 个钩子）。
>
> 本段只落玩家宿主光束：`living/domain/beacon/`（`BeaconFunction` + `BeaconRegistration`，照
> `TntRegistration` 范式）+ `client/render/LivingBeamRenderer`（挂 `RenderLevelStageEvent.AFTER_ENTITIES`，
> 直调原版 `BeaconRenderer.renderBeaconBeam`——NeoForge 21.1.249 里为 public static 11 参——并做
> −0.5 XZ 预补偿（该函数内部 `translate(0.5,0,0.5)` 是给方块坐标补中心用的），yaw/pitch 对齐视线
> （与 `LivingToolModelRenderer` 同款公式），`level.clip` 求截断点，U4=256、U3 白光临时值）。
> ⚠️ **无条件亮**（底座等级门槛属 Phase 2，接入前任何活信标都发光）；不开箱子、不接 buff、无同步。
>
> 无新逻辑 ⇒ 无新测试；全量回归 640 绿（doc_check 第 2 项机械核验）。游戏内验证待做（§6.1：
> 视线跟随 / 截断 / 手持回退原版）。

> 🆕 **活信标 Phase 2：金字塔等级判定 + 箱子宿主 + tooltip 实时行** ——
> ① `BeaconPyramid`（`living/domain/beacon/`）：下方一行 9 列柱高 → 0~4 级，每列 ≥ 目标取最高 N
> （L1 3-3-3 / L2 5-8-8-8-5 / L3 7-12-15-15-15-12-7 / L4 9-16-21-24-24-24-21-16-9，合计
> 9/34/83/164 与原版一致、嵌套单调）；`BeaconBaseBlocks` 对齐原版 `BEACON_BASE_BLOCKS` tag。
> 配套纯槽位数学 `resolveColumnSlots`（越界 −1 ⇒ 柱高 0，即边界钳制）。
> ② tick 判级 → `BeaconSegment` 遥测（id=`beacon` 线格式契约；level + 9 柱高 + 再生档，
> Phase 2 再生档恒 NONE——三档判定依赖 Phase 3 的效果配置；⚠️ long[] 手写 equals 防稳态恒脏发包）。
> ③ tooltip 实时行：等级 / 范围（等级×10+10）/ 金字塔账本（距下一级差额 + 缺列明细）。
> ④ 箱子宿主：`blockEvent(1,1)` 开盖保持（20 tick 重发、**大箱双半箱都发**、**不动
> openersCounter ⇒ 不误触发陷阱箱红石**；信标取走 ⇒ `blockEvent(1,0)` 恢复）+ `BeaconHostPacket`
> S2C 定向同步（32 格 + 内容去重，照 K2 模式）+ 客户端垂直光束（箱口向上、本地 clip 截断）。
> ⑤ 玩家宿主改**本地判级门控**：客户端自读本机背包下方 9 列（本机可见）⇒ level ≥ 1 才亮。
>
> 单测 +33（柱高判定 20 / 段编解码+equals 4 / 槽位数学 6 / 包往返 2 = 32…实到 673 = 640+33），
> 640 → 673 绿；tag 判定单测环境不加载，留游戏内验证。游戏内验证待做（§6.1：等级升档 /
> 箱盖开合 / 截断 / tooltip 实时行 / 联机）。

> 🆕 **活信标 Phase 3：5 矿石槽效果配置 + 右键喂锭 + 范围 buff + 静态 tooltip** ——
> ① `OreType`（铁=速度 / 金=急迫，档位 1；下界合金=抗性提升 / 绿宝石=跳跃提升，档位 2；
> 钻石=力量，档位 3 —— 绿宝石补到 JUMP 因再生被满级白送接管）+ `BeaconConfig`（固定 5 槽
> DataComponent，不可变 add/amplify、跟物品走、取消活化清空）+ `BeaconComponents`（持久 + 网络同步）。
> ② 喂锭 `BeaconFeedHandler`：光标拿矿石锭右键活信标 ⇒ **消耗制**（新矿石加 I 级槽 / 已有矿石
> 升 II 级）；门控全走 `BeaconEffects.feed`（**档位不足 / II 非满级 / 未激活 ⇒ 拒绝且不消耗**）。
> ⚠️ 两个接口事实：handler **拿不到 `ContainerContext`** ⇒ 等级从**菜单容器现读**（与 tick 同 9 列
> 网格口径）；`InteractionRegistry` 的**精确条目要求 trigger 也是活物品** ⇒ 矿石锭必须走
> **通配 trigger + `predicate: is_beacon_ore`**（照 `can_till` / `can_plant` 先例）。
> ③ `BeaconEffects` 纯逻辑：生效数 = `min(堆叠数, 已配且档位≤等级的槽数)` 取前 N；再生三档
> （有空槽 → II / 满配全 I → I / 满配且加倍 → 无）——**用户 5 案例逐字验证**。
> ④ tick 每 80 刻施加 buff（照原版 `applyEffects` 等价）：范围 `等级×10+10` 球体撑到世界高度、
> 时长 `(9+等级×2)×20` 刻、ambient + 显示粒子；宿主 = 玩家背包主人位置 / 箱子方块中心，
> **末影箱等方块容器自然排除**（规则 1：仅玩家 / 箱子）。
> ⑤ tooltip 静态账本：5 槽（序号 + 效果名 + I/II + 矿石名 + 等级不足标注）+ 空槽喂锭提示 +
> 满级再生档行。
> ⑥ **联机可见收尾**（规则 6）：箱子宿主已随位置包可见；玩家宿主补 S2C 广播
> （UUID + 等级，32 格定向 + 内容去重 + 排除自己，照 `LivingToolPlayerSync`），
> 客户端查实体取**其**视线方向画同款光束（实体旋转本就同步）。
>
> 单测 +31（673 → 704 绿；喂锭门控/生效数/再生三档/时长/配置编解码/谓词与映射/两条包往返）。
> ⚠️ 等级读取路径与 tag 判定依赖游戏内环境（单测不加载 tag）⇒ 留游戏内实测（§6.1）。

> 🎨 **活信标：可见性对齐原版**（用户 2026-10-16「如果想改的和原版差不多呢」）——
> 原版光束"很远都能看见"靠**方块身份 + 三个免剔除开关**（`shouldRenderOffScreen=true` 进全局名单跳过视锥剔除 /
> `getViewDistance=256` 且**只算水平距离** / `getRenderBoundingBox` 包到 `y+1024`；光束高度上限 `MAX_RENDER_Y=1024`），
> 实际可见 ≈ `min(渲染距离, 256)`。活信标是**物品** ⇒ 客户端看不见、做不到零同步 ⇒ **可见性完全由广播半径决定**。
> 改：① 两处同步半径 **32 → 256** 并把判据改成**水平距离**（对齐 `getViewDistance` 口径）；
> ② 光束长度 **256 → 1024**（对齐 `MAX_RENDER_Y`，U4 修订）；③ 客户端箱子光束加 **`hasChunkAt` 守卫**
> （半径超出视距时未加载区块不画，否则悬空光束）。代价可忽略：条目十几字节 + 只在变化时发（稳态零流量）。
> 无新逻辑 ⇒ 测试维持 704 绿。

> 🐛 **活信标：光束穿透透明方块**（用户实测 2026-10-16「光束不能穿透玻璃」）——
> 设计稿规则 5 写的是「被**不透明**方块截断」，但实现直接用了 `level.clip` 的命中点 ⇒
> 玻璃 / 玻璃板 / 铁栏 / 树叶 / 台阶都有完整碰撞形状 ⇒ 被当成"挡住了"。
> 修：新增 `opaqueHitEnd` —— 命中透明方块就从命中点往前挪一小步**继续打**（同一方块只判一次，
> `skipped` 守卫防死循环），判据 `BlockState.canOcclude()`（玻璃 / 染色玻璃 / 树叶 / 铁栏 /
> 台阶都是 `noOcclusion()` ⇒ 穿过；石 / 土 / 木板 / 羊毛等实心方块才截断）。
> ⭐ 视觉上也成立：玻璃属**半透明层**，在 `AFTER_ENTITIES`（我们画光束的时机）**之后**才绘制
> ⇒ 不会把光束挡掉，反而会叠在光束上（隔着玻璃看得见）。
> 常见场景（天空 / 实心地形）仍是单次 clip ⇒ 无性能代价。

> 🎨 **活信标：光束颜色 —— 染色玻璃（U3 方案 A，用户 2026-10-16 定）** ——
> 原版机制核源码：从信标往上扫，每格取 NeoForge 扩展点 `BlockState#getBeaconColorMultiplier`
> （染色玻璃 / 玻璃板 / **信标自身**都实现 `BeaconBeamBlock`）—— 第 1 个有色块是**信标自身**（白底），
> **第 2 个**（紧邻的第一块玻璃）⇒ **纯色**新段（所以"放一块玻璃"拿到玻璃本色），**第 3 个起**
> 同色加高 / **异色 ⇒ 新段 = 前段与新色的平均**；非染色方块加高当前段、不透明方块停止扫描；
> 渲染端"最后一段画到顶"⇒ 感知上"第一块玻璃决定整根颜色"。
>
> 我们（**方案 A：颜色一律取自世界方块，贴合原版**）：**玩家宿主**扫**玩家所在格上方**、
> **箱子宿主**扫**箱口上方**（上限 32 格，避免每帧长扫；超出由最后一段兜住）⇒ 走进染色玻璃
> 走廊会变色。⭐ **颜色无需任何同步**（世界方块本就同步给所有客户端 ⇒ 别人的光束也本地算得出来）
> —— 用户先在 A/B 间选了 B（背包上方格 + 服务端下发颜色），随后改定 A 并**回退** B 的产物
> （删 `BeaconGlassColor`、`BeaconPlayerHostPacket` 去掉 `color` 字段）。
> 新件：`client/render/BeamColorSections`（原版算法纯逻辑，9 项单测）；
> 渲染改为**逐段堆叠**（每段一次 `renderBeaconBeam`，最后一段延伸到终点）。
> 无玻璃 ⇒ 原版白光。单测 +9（704 → 713 绿）。

> 🎯 **起点修正：从「眼睛位置」改为「头颅顶面」**（用户 2026-10-16 定）—— 用户原话
> 「**从玩家的头颅顶面射出**」。原实现取 `getEyePosition()`（站立 y+1.62，头部中心），
> 现取 `getPosition(partialTick)` + `getBbHeight()`（脚底插值位置 + 实体高度 ⇒ 随潜行 /
> 坐骑自动变化），差约 0.2 格。**方向不变**（仍 = 完整视线）；颜色扫描起点随之改为
> 「头颅顶面上方」。
> ⚠️ **措辞教训**：设计稿原写「起点 = 头顶**上方**」——那个"上方"是**方位词**（会被读成
> 方向 = 竖直向上，本轮实际就被误读过一次）⇒ 已改为位置词「**头颅顶面**」。**方位词在
> 涉及会转的实体部件时必须先问是"世界方向"还是"实体局部方向"**。

> 🧭 **方向修正：从「完整视线」改为「头颅顶面法线」**（用户 2026-10-16 定）—— 用户先让把
> 「从头颅顶面射出」的**所有读法**穷举出来（歧义有三层：起点位置 / "顶面"用哪个坐标系 /
> "射出"是否自带方向），然后选定**方向 = 顶面法线**（即那个**会随头俯仰转动的面**的法线）。
> 几何：**平视 = 竖直向上；低头 = 朝前上方；抬头 = 朝后上方**；它与视线方向**垂直**
> （相差 90° —— 低头时视线朝前下、顶面法线朝前上，两者不是一回事）。
> 公式（MC 约定：yaw 0 = +Z、pitch 低头为正）：`N = (−sin y·sin p, cos p, cos y·sin p)`；
> 三个校验点：`p=0 ⇒ (0,1,0)`、`p=+90° ⇒ 水平前方`、`p=−90° ⇒ 水平后方`，且 `N·L ≡ 0`。
> 起点仍为头颅顶面（固定高度，不随头移动 —— 用户只选了方向那一项）。
> 落 `LivingBeamRenderer.headNormal`，玩家宿主（含联机远端）均改用它；颜色分段（竖直格数）
> 与光束方向的对应关系同前，仍不严格。测试维持 713 绿。

> 🎩 **起点也改为随头转动（"像帽子一样"）**（用户 2026-10-16 定）—— 用户看了"所有读法"的组合表后
> 说「**像帽子一样**」：帽子随头转动 ⇒ **起点也要贴在会转的头顶面上**（即组合表的 #5 = 起点 B +
> 方向 ②）。落法：`headTop` = **颈部枢轴 + 顶面法线 × 0.3**（`HEAD_TOP_FROM_PIVOT`）——
> 平视时退化为「脚底 + 实体高度」（与上一版一致），**低头时起点往前、往下移动**；潜行 / 坐骑的
> 高度变化仍保留（枢轴 = `getBbHeight() − 0.3`）。至此**起点与方向都随头转**，光束真正"戴在头上"。
> 测试维持 713 绿。

> 🆕 **活信标 Phase 4：tooltip 重设计 + 9 柱小金字塔面板**（用户逐轮拍板，设计定稿 §2.6 后落地）——
> ① **文本层压缩**（原则：图形已表达的文本不双写）：删底座差额行（`beacon.pyramid`）/ 范围 /
> 未激活独立行（`beacon.inactive`）；休眠三因——旧只有「等级不足」一种，把「配 5 槽但只有 1 个
> 信标」和「等级不够」压成同一句话 ⇒ 拆成 `需 L{n}`（升级底座）与 `信标不足（需 k 个）`（补货）；
> 空槽提示补规则「1 锭 = I，2 锭 = II（仅满级）」（旧文案让玩家学不到 II 仅满级）；满级效果行
> 文案定 **C 方案**（用户否掉「免费/赠送」的超市送鸡蛋味）⇒ `再生 II（满级）` /
> `再生：无（效果位被 II 占用）`。
> ② **图形层 9 柱小金字塔**（160×76，**始终可见不依赖 F3+H**，同发电机仪器面板口径——U22 升级）：
> 实心柱 = 现状（中心列亮青）/ **9 段阶梯刻度 = 各列自己的目标高度**（⚠️ 画 mockup 时发现
> 「水平目标线」不成立——各列目标高度各异，L3 行是 0-7-12-15-15-15-12-7-0）/ 横纹 = 待填 /
> 金帽 + 数字 = 溢出（>24 不伸高，溢出无决策价值）/ 1px 竖虚线 = 越界列。
> ③ **提示逻辑四转变**（「下一级怎么摆」是行动指令不是状态描述）：**数字从差额 −7 改为目标值 15**
> （玩家不心算加法；数字贴刻度线，「目标标记」是线 + 数的整体；达标列无数字 ⇒「干净」=「没问题」）；
> 缺口从实心改横纹（待填纹理 vs 已有柱）；底部双读数 `to L3: 48 more` + `51/83`
> （**已投含放错列的料** ⇒ 多投不动进度，玩家自己发现放错列；分数比百分比信息多）。
> ④ **不可达兜底**：`BeaconRuntime` 新增 `columnExists` 位图（区分「空槽」与「该列不存在」）——
> 信标贴容器边缘 ⇒ 下一级参与列有越界 ⇒ 该级永远达不到，底部显示「宽度不足，信标内移」而
> **不是 deficit**（「补 9 块就能亮」在永远达不到时是谎言）；判定 = 下一级所有 target>0 的列都存在。
> ⑤ 数据链路：`BeaconPanelGeometry` 纯函数（刻度 y / 柱顶 y / 缺口高度 / 数字取舍 / reachable
> 全单测 ⇒「像素对不上」不靠启动游戏截图）；挂载照抄 `generatorDataFor` 悬停定位
> （GatherComponents 时 ThreadLocal 已被 finally 清空，tooltip-system.md §7-2）+ player_ 背包通道。
> ⑥ 死代码清理：`BeaconPyramid.columnLabel`（tooltip 差额行删除后无生产消费者）。
> 单测 +9（713 → 722 绿）；可视化 mockup 两份留 `tools/_mock_beacon_panel.html`（6 场景几何）与
> `tools/_mock_beacon_tooltip.html`（三态完整 tooltip）。Phase 4 游戏内验证项已补 §6.1（4 条）。

> 🐛 **修复：9 柱面板 `fill` 坐标语义误用（整块图形溢出 tooltip）**（用户实测 2026-10-16
> 「黄色的色块溢出了一大块」，此前一条「色块铺满整个屏幕」同源）——
> `GuiGraphics.fill(x1,y1,x2,y2)` 是**坐标语义（右下排他）**，我写柱区绘制时按
> **(x, y, 宽, 高)** 误传了 5 处（刻度线 / 横纹 / 柱体 / 溢出帽 / 越界竖虚线）⇒
> `x2 = BAR_W(13) < x1 = px` ⇒ **每一列都画出一块从面板左上角蔓延到柱位的巨大矩形**：
> 黄色那几处（琥珀刻度 / 金色帽）即用户看到的「溢出一大块」，深色几处（暗青柱体/横纹/虚线）
> 叠加 ⇒ 初版「铺满整个屏幕」。背景与四边框是照抄发电机渲染器的，本就正确 ⇒ **错的全部是
> 新写的部分**。修：5 处全部改为坐标式（`px .. px+BAR_W` / `y+top .. y+BASE_Y`），
> 渲染器加语义警示注释；面板尺寸维持设计值 160×76 **不动**——「太大」的反馈同源于此 bug，
> 并非设计问题（曾讨论的缩小/去底色/渐进披露方案全部作废）。
> ⭐ 教训：几何纯函数单测拦得住「算错的 y」，拦不住「fill 传错参数」——新写绘制代码的 fill
> 一律写成 `px .. px+w` 形式（禁止传裸宽高）；这是本子系统第二个「单测覆盖不到、只能游戏内
> 实测发现」的层（第一个是 GatherComponents 的 ThreadLocal 坑，靠照抄 `generatorDataFor` 绕开）。
> 无逻辑变更 ⇒ 测试维持 722 绿。

> 🎨 **活信标：溢出列去掉绝对高度数字（用户实测反馈）** —— 底座 9 槽堆满 64 时面板顶部出现
> 一排金色「64」：超过目标（最大 24）的部分设计上就是「多堆不亏」，该数字**零决策价值还误导**
> （看着像在说"需要 64"）。改：① 溢出列只留 3px 金帽表意，**不再标数字** ⇒ 面板数字只剩
> 一个语义——「这列还没到位、要堆到这么多」（未达标列的目标值）；② 满级底部右读数从实际
> 堆叠数 `共 N 块` 改为**需求上限** `需 164 块`（非满级的「已投/总需求」保留——它的诊断价值
> 是暴露「多投不动进度、放错列」）。lang：`panel.stacked` → `panel.need_total`。
> 无逻辑变更 ⇒ 测试维持 722 绿；mockup 两份同步更新。

> 🎯 **活信标：U8 光照/音效定案「不做」**（用户 2026-10-16 拍板「算了，这两就不做了」）——
> 实证依据：**光照**——原版信标方块 `lightLevel(15)`（`Blocks.java` 2910 行）但**物品形态不发光**
> ⇒ 活信标是物品，无原版依据；箱子宿主发光需真改世界光照，成本高收益低。**音效**——事件型两枚
> （`BEACON_ACTIVATE` / `BEACON_POWER_SELECTED`）本可低成本对齐（`BeaconFeedHandler` 喂锭前后
> 读等级判上升沿即可），用户选择不做；`BEACON_AMBIENT` 每 4 秒广播（`playSound(null,…)`），
> 在「一摞 N 个 + 多容器同时在线」下必成噪音灾难。设计稿 §4/§5 同步收口 ⇒ **活信标设计项全部
> 闭环**，剩余仅 §6.1 游戏内实测 + 实测后迁 `docs/tech/`。

> 🎨 **活信标：柱高钳到该列目标——面板永远是「在建中的金字塔」**（用户实测 2026-10-16
> 「柱状图为什么都是平行的、而不是金字塔状的」）—— 柱高原按实际堆叠数绘制（clamp 全局满程
> 24）⇒ 玩家把 9 列堆成一样多时就是 9 根平顶柱，「中间高两边低」只存在于 1px 刻度线上，
> 扛不住视觉。改：**柱高 = min(堆叠数, 该列目标)** ⇒ 实心柱成为「金字塔的填充版」——达标列
> 柱顶贴刻度线、超额列贴线 + 金帽（帽位置同步改为贴**各自**刻度线而非面板顶）、欠缺列凹口
> + 横纹；任何时刻看过去都是一座在建中的金字塔，缺哪列凹哪里。非参与列（target=0）不画柱
> （视觉表达「该列当前未启用」；其材料仍计入底部「已投/总需求」诊断 ⇒ 多投照样暴露）。
> 新纯函数 `BeaconPanelGeometry.columnTopY` + 单测（L4 满配 9 列 = 9-16-21-24-24-24-21-16-9
> 剪影逐列断言）。单测 +1（722 → 723 绿）；mockup 两份同步。

> 🐛 **修复：箱子宿主从不打开盖子**（用户实测 2026-10-16「有活信标光束时箱子不会保持盖子开启」）——
> `BeaconChestHost.flush` 用 `Long.MIN_VALUE` 当「从未开盖」哨兵，`gameTime - Long.MIN_VALUE`
> **long 溢出成负数** ⇒ `>= LID_RESEND_TICKS(20)` 永假 ⇒ `blockEvent(1,1)` **一次都没发过**
> （症状是「从未打开」而非「不保持」——20 tick 重发逻辑本身没错）。修：哨兵改 `-1`
> （gameTime 非负 ⇒ 减法无溢出）+ `last == NEVER` 显式立即开盖。⚠️ 教训：**时间差运算的哨兵值
> 不能用 `Long.MIN_VALUE`**（`now − MIN_VALUE` 必溢出回绕成负）；此类常量算术陷阱单测覆盖不到
> （依赖 `ServerLevel.blockEvent`）⇒ 游戏内实测不可省。测试维持 723 绿。

> 🐛 **修复：贴着箱口放染色玻璃 ⇒ 光束整条消失**（用户实测 2026-10-16「箱子上方放玻璃光柱会消失，
> 隔一格放则正常穿过玻璃」）—— 两机制叠加：① `BeamColorSections.build` 的白底段（模拟原版
> 「信标自身那一格」）高度 0；② 渲染循环把 `segHeight <= 0` 当**终止条件**（break）。⇒ 第一格
> 就是染色玻璃时白段 0 高 ⇒ **第一段就 break，整条光束一段不画**；隔一格放时白段被空气 grow
> 到 ≥1 ⇒ 正常（故现象是「贴着消失、隔格正常」）。修：白底段高度 **0 → 1**（对齐原版口径——
> 原版第一段就是信标自身格、1 格高）+ 渲染循环区分「0 高段跳过（continue）」与「高度用尽才停
> （remain ≤ 0 break）」。`glassAdjacent` 测试加**契约断言「首段高度 ≥ 1」**钉住根因
> （0 高首段 = 渲染必炸）。⭐ 同源影响**玩家宿主**（头顶正上方贴玻璃同样消失）——一并修复。
> 穿透机制（`canOcclude`）本身工作正常，无需改动。测试维持 723 绿。

> 🆕 **活信标：容器槽位的染色玻璃也参与光束染色**（用户 2026-10-16 需求「活信标上方槽位的玻璃
> 会影响光束颜色，和世界里的玻璃应该不冲突的吧」）—— 答：不冲突，是**叠放关系**。语义：容器
> 9 列网格里信标**同列向上**的槽位序列 = 原版「信标正上方起可叠放的玻璃」，以新纯函数类
> `BeaconGlassColor` 取色（16 色染色玻璃 + 玻璃板，`DyeColor.getTextureDiffuseColor`，与
> NeoForge 扩展点同口径），**插在颜色扫描序列最前面**（世界扫描接其后）⇒ 走原版「第 1/2 个
> 有色块都新建段」规则合成（红+蓝叠效保留）。⚠️ 容器色**必须随 S2C 下发**——客户端不开 GUI
> 看不到容器内容（§2.3 约束）：`BeaconHostPacket.Entry` / `BeaconPlayerHostPacket.Entry` 各加
> `glassColors` 列表（自下而上；内容去复 用 equals ⇒ 稳态零流量）；世界玻璃仍客户端本地读
> （U3-A 不变）。⚠️ **非玻璃物品不遮挡**（无色、继续往上扫）——容器是染色配置层，不发明
> 「物品透光性」。新件 `BeaconGlassColor`（of + scanColumn 纯函数）+ 单测 7 项，两个宿主包
> 往返测试同步加色列表字段；单测 723 → 730 绿。

> 🐛 **修复：开光影时光束暗淡（挂载阶段差一段 + glow 层漏 flush）**（用户实测 2026-10-16
> 「开光影，原版的光束是发光亮亮的，我们自己的却是暗淡的」）—— 两个问题叠加：
> ① **阶段**：原版信标是 BlockEntityRenderer，在 `AFTER_BLOCK_ENTITIES` 段绘制；我们挂在
> `AFTER_ENTITIES`（早一段）。光影（Oculus/BSL 类延迟渲染）按渲染段分派 G-buffer / emissive
> 通道 ⇒ 原版 BE 发光体所在段才被 bloom 捕获，我们在前一段画 ⇒ 不进后处理 ⇒ 暗。
> ② **漏 flush**：`renderBeaconBeam` 内部画**两趟**（beam 不透明 + glow 半透明外晕，两个
> RenderType），我们只 flush 了不透明那趟 ⇒ glow 残留 Immediate 被主线推迟到错误时机提交
> ⇒ 光晕丢失/错乱（不开光影也在，只是光晕淡没注意）。修：挂载改 `AFTER_BLOCK_ENTITIES`
> （与原版 BE 同段，深度双保险不变——BE 段时地形/实体/BE 深度均已写入）+ 两趟都 flush。
> 渲染路径无单测 ⇒ 靠游戏内实测（开花影 + 不开花影各看一眼）。

> 🎯 **活信标：确认「通天失效」不对齐**（用户 2026-10-16「原版信标上方有不透光方块时会失效，
> 这点不需要对齐」）—— 实证原版被遮光块盖住时是**三重失效**：光束 `clear()` 整根消失 +
> `applyEffects` 门槛带 `!beamSections.isEmpty()` ⇒ **buff 停** + `updateBase` 等级重算也停
> （`BeaconBlockEntity.tick:188-197`）。我们只做「截断到盖底露一截」，**buff 与等级不受影响**
> —— 用户拍板维持：密闭箱子/地下室也生效是玩法增强，不强制玩家搭通天柱。判据对齐（上一条）
> 与表现层不对齐（本条）由此分离记账。

> 🎯 **光束截断判据逐字对齐原版**（用户 2026-10-16「截断判据和原版对齐一下吧」）—— 对照
> `BeaconBlockEntity.tick` 后发现我们用的 `canOcclude()` 与原版口径有两处边缘差异：
> ① **基岩**：原版 `getLightBlock ≥ 15 && !is(BEDROCK)` 特意排除基岩 ⇒ 原版光束
> **能穿过基岩层**，`canOcclude` 会把它挡住；② 遮光等级口径比「能否遮挡邻面」更宽
> （存在 `canOcclude=false` 但遮光 ≥15 的方块）。修：新增 `blocksBeam(level, pos)`
> = `getLightBlock(level, pos) >= 15 && !state.is(Blocks.BEDROCK)`，**光束截断与颜色扫描
> 共用同一判据**（原版 tick 循环里本就是同一支判断）。⚠️ 表现层仍有意偏离：原版被盖住时
> `clear()` ⇒ 整根消失，我们截断到盖底保留一截（视觉差异极小，且「箱口到盖底有一截」
> 在活物品场景更合理）——已记入 §4 对齐清单备书。测试维持 723 绿。

---

## 2026-10-13

> ✅ **游戏内实测通过（用户 2026-10-13 晚，全部正常）** —— 白幕 / 燃料条 / 完成音效（画面与声音）；
> 背包与**末影箱**里的活物品 tooltip 运行时行恢复（末影箱为**首次可用**）；白幕离槽即停、
> 材料不足 / 批次结束即停；开箱场景回归正常。此前各条目挂的「须游戏内验证」全部销项。

> 🎨 **活酿造台反馈层简化 + 燃料条 + 通用进度条框架** —— 白幕从「组件存权威起始时刻 +
> 客户端与服务端时钟对齐」简化为**纯客户端本地动画**（用户判定「锦上添花不必精确」）：
> 删掉 SetTimePacket 校正链路与两个偏差源，堆叠时长按 `400/堆叠数` 折算（零新数据）；
> 新增原版耐久条同款**燃料条**（`#F8A820`）；进度条做成框架级 `ProgressBarDecorator`
> （横/竖几何为纯函数）；`LivingIconSpec.decorator` 支持多次调用（NeoForge 原生一物品多装饰器）。
> 测试 612 → 622 绿。
> 🔧 **修正：白幕颜色**从 `0xFFFFFFFF`（不透明纯白）改为 `Integer.MAX_VALUE`（`0x7FFFFFFF`
> = 约 50% 半透明白），与原版逐像素一致 —— 初版核对源码时误把 `Integer.MAX_VALUE` 当成
> `0xFFFFFFFF`，比原版实一倍（用户实测「白得不对劲」而复查发现）。
> 🔍 **收尾审查**（用户实测通过后复查本轮全部改动）：修 1 处文档笔误（横条方向写反）+
> 1 处冗余（`animationStart` 双重查找）+ 1 处边界（`fillRect` 未 clamp）+ 语义措辞同步
> （「起始时刻」→「批次标记」）；测试 623 绿。
> 📝 **复盘沉淀**（本轮开发历程反馈）—— `docs/guides/living-item-design-template.md` 加三处：
> §1.3 **反馈层三问**（精度需求 / 数据通道 / 覆盖场景；判据「反馈的精度需求决定数据通道」）、
> §2.2 附注 **改语义 / 改几何 ⇒ grep 相关词**（Java 注释 · 文档 · 测试消息三类落点）、
> §三 **框架化时机**（已知多接入方 + 配置型成本 ⇒ 抽；否则过度设计，白幕与 `ProgressBarDecorator` 正反对照）。
> 🆕 **生存背包界面启用活按钮**（小人框底居中 42,70）—— 与射线微调同矩形 ⇒ 按钮优先（避让判定）；
> init 时序坑（配方书 leftPos 后置应用 ⇒ 书展开时错位一帧）用 init TAIL 重摆解决；
> 配方书三形态核过；创造界面维持排除并**补考证原因 = 活化链路不通**。测试 623 → 625 绿。
> 🐛 **修复：容器键形反推导致的 3 处同源 bug（背包 / 末影箱 tooltip 遥测）+ 解析收敛唯一入口** ——
> ① 删 `isJustInventoryMenu` 守卫（把「开着背包界面」误判为「没开界面」）；② 末影箱 UUID 解析
> 补剥 `_ender_chest` 后缀（曾抛异常被吞 ⇒ **从不发包**）；③ 客户端按包里下发的 `ContainerKind`
> 分流（不再按屏幕类型猜）。新增 `ContainerKeys` 唯一解析入口 + `ContainerKind` 枚举 +
> 脚本 `check_container_keys.py`（窄而准）；测试 625 → 633 绿。
> 🎨 **修正：白幕「没有进度却还在播」** —— 物品移出容器后运行时缓存作废（`brewTime` 归零）
> ⇒ 状态机走不到 `Complete`/`Abort` ⇒ 组件批次标记残留 ⇒ 客户端白幕空播一轮。
> 修：`Idle` 也收起标记（`batchMarkAfter` 的 `default -> -1L`，自愈），顺带修好旧边界
> 「重启后材料不足」；测试 633 → 634 绿。
> 🐛 **修复：龙息作材料时被整栈吃掉** —— `consumeOne` 遇到「带合成残留物」就**整栈替换**，
> 而龙息带残留物（玻璃瓶）**且可堆叠** ⇒ 8 个龙息变 1 个玻璃瓶。改为原版语义
> 「先扣 1、再看残留物」+ D9 的「另寻归宿」（并入未满槽 → 空槽 → 满则丢弃）；测试 634 → 640 绿。

- 🎨 **白幕简化为本地动画（纯客户端，不做跨机器时钟对齐）** —— 组件 `brewStartTick` 降级为
  **批次标记**（服务端三写点不变：开始写 gameTime、中止/完成写 -1）；客户端首次见到某标记时
  记下**本地**时刻作为动画起点，之后 `f = 1 − (本地now − 起点)/时长`。起点与 now 同为客户端时钟
  ⇒ 不漂，**不需要 `ClientboundSetTimePacket` 校正**（原版末影珍珠冷却同款做法：`ItemCooldowns`
  用客户端自己的 tickCount）。初版的「堆叠偏慢 / 校正滞后 / 接缝闪断」三个偏差源随之消失两个、
  第三个本就是「重播」语义。**堆叠加速零新数据**：时长 = `BREW_TIME / stepFor(count)`，
  堆叠数直接读 `stack.getCount()`。**重播** = 新标记 ⇒ 新起点（不是额外逻辑）；
  **播完不删**条目（避免「播完被重建、反复重播」的竞态），map 只增、跨存档统一清空。
- 🆕 **燃料条（`ProgressBarDecorator` 的第一个接入方）** —— 原版耐久条同款几何（左下 13×2 黑底
  + 13×1 彩条从左往右），颜色取烈焰棒纹理色相并提亮 ⇒ `#F8A820`（细条画在浅灰槽位上，
  忠实平均色 `#C4861A` 偏暗会糊）。口径：`fuel > 0` 才画（与耐久条「有损才画」互补）。
- 🏗 **框架级通用进度条** —— `ProgressBarDecorator`：注册「栈 → (比例, 颜色, 朝向)」函数；
  横 = 原版耐久条位置、竖 = 左缘液位条（活水源液位、活发电机能量下一个接入，各加一行）；
  几何为纯函数可单测。⭐ **数据来源约束**：装饰器接口只有 `(stack, x, y)`、**拿不到槽位索引**
  ⇒ 只能读「跟着物品走」的组件数据（燃料/能量/流体 ✅；运行时缓存里只发 viewer 的瞬态量 ❌，
  那些归 tooltip 与白幕）—— 这同时是进度条**天生全场景新鲜**的原因。
- 🔧 **`LivingIconSpec.decorator(...)` 改为可多次调用** —— NeoForge 原生支持一物品多装饰器
  （`RegisterItemDecorationsEvent` 内部就是 `Map<Item, List<IItemDecorator>>`）；
  活酿造台 = 燃料条 + 白幕（z 分层：条 190 < 白幕 200，白幕播放时盖在条上）。
- 📌 **零兼容代码（用户判定）** —— 「不要为没发生过的场景写兼容逻辑」。陈旧 `brewStartTick`
  只可能在「重启存档 + 材料恰巧不够」时残留 ⇒ 白幕空播一轮后自己停，不写任何守卫；
  「拿进拿出」由状态机天然覆盖（无瓶 ⇒ `canBrew=false` ⇒ Abort ⇒ 组件写 -1，判据核实过）。
- 🔧 **修正：白幕颜色与原版逐像素对齐** —— 原版 `GuiGraphics.renderItem` 用的是
  `Integer.MAX_VALUE`（= `0x7FFFFFFF`，`A=0x7F` ⇒ **约 50% 半透明白**），初版误写成
  `0xFFFFFFFF`（不透明）⇒ 白幕比原版实一倍、底下图标被完全盖住。用户实测觉得「白得不对劲」
  而复查源码，改为 `Integer.MAX_VALUE`。
  ⚠️ **教训**：`Integer.MAX_VALUE` = `0x7FFFFFFF` ≠ `0xFFFFFFFF` —— 前者 alpha 只有一半；
  「照抄原版」时颜色常量必须逐位核对（原版用 `Integer.MAX_VALUE` 而非 `0xFFFFFFFF` 是刻意的半透明）。
- 🔍 **收尾审查（用户实测通过后复查本轮全部改动）**：① 文档笔误 —— tech §8.2 把横条写成
  「从右往左长」（与同页上文、代码注释、几何实现三处相反）⇒ 改「从左往右」；
  ② 代码冗余 —— `LivingBrewingStandDecorator.animationStart` 的 `get` + `computeIfAbsent`
  双重查找 ⇒ 单次 `computeIfAbsent`；③ 边界健全 —— `ProgressBarDecorator.fillRect` 未 clamp
  `fraction`（render 侧已 clamp，但纯函数自身不该依赖调用方）⇒ 内部 clamp + 补 1 项测试；
  ④ **语义措辞同步** —— 「起始时刻」→「批次标记」：语义降级后旧措辞散在 `LivingBrewingStandData`
  （字段 javadoc ×2）、`LivingBrewingStandFunction`（数据归属 + 写点注释）、
  `LivingBrewingStandDataTest`（断言消息）；历史描述（装饰器类头「📜 历史」段与 tech §8 修订说明）
  刻意保留；⑤ icon-system 装饰器计数改实证口径（9 个实现 / 注册到 7 个专用物品 —— 酿造台挂 2 个
  —— + 默认兜底）。验证：全量 623 绿；doc_check 全过；check_layers 零新增。
- **验证**：白幕测试 3 → 8 项（守卫 / 堆叠时长折算与 `stepFor` 同源 / 比例时间线 /
  动画起点与重播 / 播完不删 / 跨存档清理）、新增 `ProgressBarDecoratorTest` 6 项；
  全量测试 612 → 622 绿；`check_layers` 零新增。✅ 画面本身已游戏内实测通过（2026-10-13 晚：
  白幕消退速度随堆叠数、燃料条刻度、z 层次、连续两批重播均正常）。
- 🆕 **生存背包界面启用活按钮（小人框底居中 42,70）** —— 排除自 37bde65（2026-07-14）的
  背包侧启用；创造侧维持排除（原因补考证见下）。**默认位** = 原版小人渲染矩形
  (26,8)-(75,78) 的框底居中 —— 矩形常量提取到 `LivingButtonLayout.INVENTORY_PREVIEW_*`
  （射线微调调用点同源，消掉一处魔法数重复）；已核对 `InventoryMenu` 全部槽位坐标，
  框内无槽位（盔甲列 x8-24、副手 (77,62)、合成格 x98+ 全在框外）。
  **⚠️ 与射线微调同矩形 → 按钮优先**：`InventoryScreenMixin` 微调判定前加「光标在
  LivingButton 上则跳过」—— 微调的 HEAD 拦截**先于控件分发**，不避让则门禁命中
  （空手 + 主手记忆活工具）时按钮永远点不到；微调只损失框底一条，「躯干底部」锚点
  在按钮上方照常可点。
  **⚠️ init 时序坑**：`InventoryScreen.init` 在 `super.init()`（initButtons 挂载点）
  **之后**才应用配方书 leftPos 偏移（`updateScreenPosition`）⇒ 书展开时打开背包，
  initButtons 拿到偏移前的 leftPos，按钮先错位 ≈77px、下个 containerTick 才自愈
  （闪一帧）；`InventoryScreen.init` TAIL 补重摆注入（同一套 resolve 偏移；resize 走
  同一个 init，同款时序一并覆盖）。
  **配方书三形态核过（原版源码）**：书关 = GUI 居中 ✓；书展开 + 窗口 ≥379 = GUI 右移
  与书**并排**，按钮跟随 leftPos（containerTick 链路靠 `InventoryScreenMixin`
  `living_item$callContainerTick` 补的 `super.containerTick()` 已通）✓；
  书展开 + 窄窗口 <379 = **原版书独占模式**（`super.render` 与 `super.mouseClicked`
  都不跑）⇒ 按钮连同整个 GUI 不可见不可点 —— **与原版槽位行为一致，非缺陷**。
  **创造界面维持排除（原因补考证）**：`LivingTagPacket` 服务端读
  `containerMenu.getCarried()`，而创造的 carried 是客户端虚拟物品（服务端从不打开
  创造界面，拾取只走 `ServerboundSetCreativeModeSlotPacket` 写槽位）⇒ 服务端恒空、
  点按钮静默无效 —— 2026-07-14 无记录排除的真实原因。修复方向（**carriedTag 往返**
  复用 GuiInteractionPacket 先例 / **客户端活化**）+ 标签页维度（`selectedTab` static，
  切页不重跑 init）待  定稿单独开工。`LivingButtonLayoutTest` +2（默认位分流 +
  矩形常量对齐原版），测试 623 → 625 绿。✅ 游戏内实测通过（2026-10-13：按钮可用、观感良好）。

- 🐛 **修复：容器键形反推导致的 3 处同源 bug + 键解析收敛为唯一入口（2026-10-13）** ——
  用户实测发现「活酿造台 tooltip 进度在只打开背包 GUI 时不显示」。排查确认是**同一根因的三个变体**：
  容器类型在产生处（各 `*ContainerContext` 构造时）本来明确，但被压扁成字符串 key 后，
  每个消费方各自 `startsWith` / `substring` 反推 ⇒ 规则不统一：
  - ① `LivingItemRuntimeSync.sendToOwner` 的 `isJustInventoryMenu` 守卫用 `menu == inventoryMenu`
    判「没开界面」，但**原版按 E 开背包不换菜单**（服务端根本区分不了二者）⇒ 背包里活物品的
    tooltip 运行时行不刷新（燃料行是组件数据，故只有进度行缺 —— 与实测现象吻合）；
  - ② 同方法 UUID 解析直接 `UUID.fromString(key.substring(7))`，对末影箱键
    `player_<uuid>_ender_chest` 抛 `IllegalArgumentException` 被 catch 吞掉 ⇒ **末影箱从不发包**
    （其 tooltip 运行时行**从未显示过**，与开不开界面无关）；
  - ③ 客户端 `LivingItemSyncPacket.handle` 按 `startsWith("player_")` 分流 ⇒ 末影箱（若发了）
    会写进背包缓存，而读取端（`LivingItemTooltip` 按「是不是 InventoryScreen」判）对末影箱界面
    走 `get(slot)` ⇒ 读写错位（**即使修好 ② 也读不到**）。
  **修法（两条腿）**：
  - **种类成为一等公民** —— 新增 `ContainerKind`（`PLAYER_INV`/`ENDER_CHEST`/`WORLD_BLOCK`/
    `ITEM_ENTITY`）随 `LivingItemSyncPacket` 下发（ordinal 编解码 + 越界回退，照
    `FluidFlowSyncPacket` 现成做法）⇒ 客户端零推断；发包分派改用 `kind.isOwnedByPlayer()`；
    **删界面守卫**（与流体轨一致 —— `FluidFlowServerSync` 对背包就是直发主人、不判界面）。
  - **解析收敛到唯一入口** —— 新增 `ContainerKeys`（`kindOf` / `ownerOf` / `positionsOf` /
    `isOwnedByPlayer` / `dimPrefixOf`）；`LivingItemRuntimeSync`、`EnderChannelSyncPacket`、
    `LivingEnderChestAccessor`、`LivingEnderChestFunction`、`LivingItem.parsePosFromContainerKey`、
    `EnderChannelRegistry.extractBlockPositions` 全部改调它（**原 5 份各自实现 → 1 份**）；
    新增脚本 `tools/check_container_keys.py` 机械拦散落（C1 `UUID.fromString` / C2 键前缀字面量，
    窄而准 ⇒ 零误报；注释行跳过）。
  **影响面**：共享该链路的活物品（活熔炉 / 活漏斗 / 活酿造台 / 活蜡凝铜）在**背包与末影箱**场景的
  tooltip 运行时行恢复；世界容器（箱子）场景本来就正常。**未做**：`ContainerKey` 结构化 record
  （③ 级根治，编译期强制）—— 留待观察，若再出同类问题再上。
  **验证**：新增 `ContainerKeysTest`（7 项：5 种键形 / 末影箱回归 / 未知形态 / 坐标 / 维度前缀）
  + 包 `kind` 往返（4 种）；全量 625 → **633 绿**；`check_container_keys` 零散落；
  `check_layers` 零新增。文档：infrastructure §2.5 清单重写 + 教训升级。
  ✅ **游戏内实测通过（2026-10-13 晚）**：背包与**末影箱**里的活物品 tooltip 运行时行恢复
  （末影箱为首次可用）；开箱场景回归正常。

- 🎨 **修正：白幕「没有进度却还在播」（2026-10-13，用户实测发现）** ——
  物品被移出容器后，运行时缓存按方案 A 作废（`brewTime` 归零）⇒ 状态机再也不会走到
  `Complete` / `Abort`（那两支要求 `brewTime > 0`）⇒ 组件里的**批次标记永久残留**，
  而客户端拿它当「批次在跑」的凭据 ⇒ 白幕空播完一轮。
  **修法**：让标记**只在批次进行中有效** —— 新增纯函数
  `BrewingBatchLogic.batchMarkAfter(step, current, now)`（`Start` 写当下 / `Continue` 保持 /
  **`default -> -1L`**，即 `Complete` · `Abort` · **`Idle`** 全收起）；
  `LivingBrewingStandFunction` 改调它，并按「标记是否变化」判脏（稳态零写入不变）
  ⇒ **`Idle` 也收起 = 自愈**。顺带把旧边界「重启后材料不足会空播一轮」零成本修好
  （当时判定「接受、不写守卫」—— 现在有了用户可感知的症状，它不再是想象中的场景）。
  **剩余**：物品**被拿在手上**（不在任何容器槽 ⇒ 不被 tick）时，最多播完当前这一轮
  （≤ 20 秒，且玩家通常会很快放回容器）。
  验证：`BrewingBatchLogicTest` +1（`Idle` 自愈回归）；全量 633 → **634 绿**。
  ✅ **游戏内实测通过（2026-10-13 晚）**：酿造中把酿造台**拿出容器** ⇒ 白幕立即停（不再空播）；
  材料不足 / 批次结束同样立即停。

- 🐛 **修复：龙息作材料时被整栈吃掉（2026-10-13，用户实测发现）** ——
  用户报「活酿造台将龙息作为材料时，会把剩下的龙息全部变为玻璃瓶」。
  **根因**：材料消耗 `consumeOne` 遇到「带合成残留物」就**整栈替换为残留物**，而
  **龙息（`Items.DRAGON_BREATH`）带合成残留物（玻璃瓶）且可堆叠（默认 64）**
  ⇒ 「8 个龙息」变「1 个玻璃瓶」，7 个凭空消失。原版 `BrewingStandBlockEntity#doBrew` 的语义是
  **先扣 1、再看残留物**：扣完为空 ⇒ 槽位变残留物；还有剩余 ⇒ 残留物**掉落**。
  **修法**：照此改写 `consumeOne`（先 `shrink(1)`，再处理残留物）；「掉落」按 **D9** 改为
  **另寻归宿** —— 并入容器中同物品的未满槽 → 任意空槽 → 容器满则丢弃（记 debug 日志）。
  **判据（实证）**：原版**可堆叠**的带残留物酿造材料**只有龙息一种**
  （水 / 岩浆 / 牛奶桶都是 `stacksTo(1)`，蜂蜜瓶不是酿造材料）⇒ 实际只此一处触发。
  ⚠️ 活熔炉 `consumeFuel` 是同一写法，但它的带残留物燃料全是 `stacksTo(1)`（岩浆桶）⇒ 无此 bug。
  ⭐ **教训**：**「原版如此」也是需要实证的断言** —— 旧注释「原版全部酿造材料与烈焰粉都不带
  合成残留物」是**事实错误**（设计稿 D9 据此得出「影响面≈0」），
  `grep craftRemainder Items.java` 一条命令即可证伪。与「照抄原版要逐位核对常量」（§8 颜色事故）同族。
  验证：新增 `BrewingMaterialConsumeTest`（6 项，含「不整栈替换」核心回归）；全量 634 → **640 绿**。
  文档：tech §9.3 重写（表格化规则 + 订正）、§10.6 新增（教训）；归档设计稿 D9 加订正指针。
  📌 **同轮附带订正 D9 的理由**（用户追问「坐标应该是能获取的吧」）：原文写「活物品**没有坐标** ⇒
  掉落对背包场景**无解**」也是**错的** —— 坐标**可得**，只是没有统一入口（世界容器 `getBlockPos()`；
  背包 / 末影箱 `getOwnerPlayer()` ⇒ **主人实体坐标**；框架已有范式 `playBrewSound`）。
  ⇒ 不掉落是**选择**（活物品常在**玩家不在场**时运行，掉落物没人捡会 despawn / 被销毁 ⇒ 等同丢失），
  不是「无解」。tech §9.3 / 规则 10 / 归档设计稿 D9 均已订正。

## 2026-10-12

> ✅ **游戏内实测通过（用户 2026-10-12 晚，全部正常）** —— tooltip 进度恢复滚动（`98b14e1`）；
> 缓存键四项（跨维度同坐标两箱不串燃料 / 不同类型不互相作废 / 拆箱原地放回旧燃料不复活 /
> 带熔炉过传送门燃料不清零）；跨存档清理（退存档进新存档，旧燃料不继承）；
> 缩小窗口后活按钮不再跑出屏幕。此前各条目挂的「须游戏内验证」全部销项。
> 🆕 **活酿造台反馈层：酿造白幕 + 完成音效**（用户提「原版末影珍珠那种图标冷却」）——
> 新增组件字段 `brewStartTick` + 白幕装饰器 + 项目第一个活物品音效；新增 7 项测试，612 绿。

> 🐛 修复：缩小窗口后活按钮跑到屏幕外（应用保存位置时未夹取）。
> 🔍 **收尾审查**：修 tooltip 进度语义反了 · 补 `ContainerRuntimeCache` 服务端跨存档清理
> （违反铁律 A1 的遗漏）· 删死代码 `isBottle`；测试 579 绿。
> 🆕 活耕地种植悬停提示（另一路；测试 589 绿）。
> 🔧 **重构：static 缓存清理改为「缓存类自己登记自己」** —— 治「忘接线」（扫出 3 处零调用者的
> `clear()`）；13 项登记迁移 + 删 `RuntimeRegistration` + 新增 `describe()`；校验工具评估后放弃。
> 🏗 doc_check 扩「条数 + 全绿」双核验；AGENTS.md 删手维护 passed 快照数（必漂、无人守）。
> 🐛 **修复：容器级数据跨维度串数据** —— 缓存键统一补维度（`getCacheKey()` 一处定义），
> 四张静态表同构；顺手修掉「拆箱子 ⇒ 整容器作废」登记打空；新增 7 项测试，596 绿。
> 🐛 **修复回归：世界容器 tooltip 遥测全停**（上一条加前缀漏改发包链路的坐标解析）——
> 剥前缀 + 按维度定向；回归测试 3 → 9 项，605 绿。
> 🧹 **收尾两条遗留观察**：隔离规则**燃料侧**补测试守卫（判据在、测试漏）+ `output_potion`
> 校验 `output` 必须是药水类物品；`drainDirty` 维持不改；测试 599 绿。

- 🆕 **活酿造台反馈层：酿造白幕 + 完成音效（项目第一个活物品音效）** —— 缘起：用户提
  「原版用末影珍珠传送后，珍珠图标上有个冷却进度；斧头破盾也有类似的。我认为适用性可能
  好点」。查证原版后落地：
  - **白幕**（`LivingBrewingStandDecorator`）：批次进行中图标**底部往上消退的纯白覆盖层**，
    几何 / 颜色 / 方向全部照抄原版物品冷却（`GuiGraphics.renderItem` 源码实证：底部对齐、
    高度 16×剩余比例、`Integer.MAX_VALUE` = 约 50% 半透明白）——玩家对「末影珍珠 / 盾牌白幕」已有条件反射，零学习成本。
    数据 = 新组件字段 **`brewStartTick`**（服务端 `level.getGameTime()`，`-1` = 不在酿造），
    **只在批次开始 / 中止 / 完成三个事件写**（稳态零写入，与熔炉燃烧标志同量级）；客户端读
    组件 + 本地 gameTime 自算宽度。
  - **时间源三查**（源码实证）：客户端 gameTime 每 tick 本地 +1、`ClientboundSetTimePacket`
    每 20 tick 校正 ⇒ 平滑、误差 ≤1 秒；非主世界 `ServerLevel` 用 `DerivedLevelData` 包同一个
    `WorldData` ⇒ **跨维度同源**，物品换维度不跳；批次不跨存档 ⇒ 陈旧值最坏提前消失（clamp 兜底）。
  - **完成音效**：批次完成给「照看者」播原版 `SoundEvents.BREWING_STAND_BREW`——世界容器 ⇒
    开着该容器 GUI 的玩家（`ContainerContexts#isViewing`）；背包 / 末影箱 ⇒ 主人本人（不管开没开
    GUI）。**只发查看者、不半径广播**：原版玩家站在方块旁 ⇔ 开着 GUI；活酿造台可能在背包 / 远处
    箱子里，广播要么听不到要么扰民。假玩家（活工具回放）无 connection ⇒ 发包前守卫。
  - ⭐ **否决「直接驱动原版冷却」**：`player.getCooldowns().addCooldown(Items.BREWING_STAND, …)`
    能零代码蹭原版渲染 + 同步（`ServerItemCooldowns` 自动发 `ClientboundCooldownPacket`），但三条
    语义债源码实证：按**物品类型 × 玩家**不按实例（所有酿造台一起糊）/ 冷却期内该物品类型
    **右键使用被丢包**（`MultiPlayerGameMode#useItem` 的 `!isOnCooldown` 门槛 ⇒ 酿造台放不出来）/
    只有「见证开始」的批次才显示。⇒ 自绘同款视觉、数据按实例走。
  - **新增测试 7 项**：`LivingBrewingStandDataTest`（4：旧数据解码 / codec 往返 / 字段隔离 /
    默认即移除锚点）+ `LivingBrewingStandDecoratorTest`（3：守卫 / 时间线 clamp / 时长同源）。
    全量 **605 → 612 绿**；`check_layers` 零新增。⚠️ 白幕与音效本身**须游戏内验证**
    （`living-brewing-stand-tech.md` 附录·酿造反馈 6 项 checklist）。
  - 📌 连带：`LivingIconRegistry` 新增 BREWING_STAND spec（living 标记从默认装饰器改为
    **烘进变体模型 layer1**，视觉不变；进 spec 是挂装饰器的唯一路径）。

- 🐛 **修复回归：世界容器 tooltip 遥测全停（缓存键前缀漏改解析方）** —— 用户实测：
  「活熔炉 tooltip 进度不变动，但熔炼功能逻辑正常」。根因：`8ea9aad`（缓存键加维度前缀）
  漏改发包链路的 `LivingItem.parsePosFromContainerKey`——它仍只认裸 `chest_` 前缀 ⇒
  对 `<维度>|chest_…` 返回 null ⇒ `runtimeContainerInstances` 恒为空 ⇒ **所有世界容器的
  运行时同步包停发**。功能逻辑读写同用一把新键 ⇒ 完全正常，所以症状是「只冻遥测、
  不冻功能」。修：① 坐标解析剥掉可选前缀；② **有前缀 ⇒ 定向该维度**（键里的 dim 即权威，
  该世界没 BE 就空集合，绝不越维度找补——跨维度同坐标正是刚修的坑）；③ 无前缀的裸键
  保持旧的「扫全部世界取首个命中」兜底。`LivingItemRuntimeContainerInstancesTest`
  3 → 9 项（前缀命中正确维度 / 不越维度 / 未知维度 / 裸键扫描 / 坏键安全跳过 / dimPrefixOf）。
  ✅ **游戏内实测通过**（2026-10-12 晚，用户确认「都正常」——tooltip 进度恢复滚动）。
  ⭐ **教训：改键形 = 改协议**——必须审计所有按格式解析的消费方。当时提交信息里
  点名「坐标解析依赖它（不改它的格式）」，却恰恰漏改了需要跟着适配的那一处；
  教训已落 `living-item-infrastructure.md` §2.5 的格式敏感点清单。

- 🧹 **收尾两条遗留观察（2026-10-11 活酿造台审查时留下、未改）**：
  - **隔离规则燃料侧补测试守卫** —— 规则 7「活物品不作材料 / 瓶子 / 燃料」三个判据
    （`isEligibleIngredient` / `isEligibleBottle` / `isBlazePowder`）2026-10-11 都补了，
    单测却只覆盖**材料 / 瓶子**两侧：燃料判据是 `private`，测试够不着 ⇒ 放开包级 +
    补 2 项（活烈焰粉被拒 / 普通烈焰粉与空槽判据）。⭐ **教训升级**：「规则 → 实现点 →
    验证入口」三件套，上次复盘只核对到前两件 —— 实现齐了≠有守卫。
  - **`output_potion` 校验 `output` 必须是药水类物品** —— `ItemStack.set` **不校验物品
    支持性**（`PatchedDataComponentMap#set` 直接写 patch，1.21.1 合并源码实证）⇒ 误配
    （`output: minecraft:dirt` + `output_potion: minecraft:water`）会产出「**带药水
    tooltip 的怪栈**」且**不报错**。修法：装载期判 `Item#components().has(POTION_CONTENTS)`，
    不合法整条跳过（与「未知药水跳过」同族的沉默即缺陷口径）。
  - 📌 **`drainDirty` 跳过空快照维持不改** —— 「单槽位容器作废后不下发」的影响趋近零：
    槽位空了本就不渲染 tooltip；换成新活物品时新数据自会覆盖。不为它加每 tick 发包面。
  - **验证**：`BrewingSlotEligibilityTest` 4 → 6 项、`FluidTransformTableTest` 7 → 8 项；
    全量测试 **596 → 599 绿**。

- 🐛 **修复：容器级数据跨维度串数据 + 整容器作废打空（缓存键统一补维度，方案 1 治本）** ——
  缘起：用户问「容器级数据是否与维度绑定」，排查发现项目里「绑维度」这件事
  **2026-08-22 就修过一次，但只修了一半**。
  - **修复前的三套键**：`CONTAINER_DATA`（流体/红石/电力）= `<维度>|<containerKey>` ✅ 带维度
    （提交 `fec1d27`，changelog「容器级数据缓存键补齐维度」）；`POS_TO_CACHE_KEY` =
    `PosKey(dim, pos)` ✅；**`ContainerRuntimeCache` + `LIVING_SLOT_ITEMS` = 裸 `chest_x_y_z` ❌**。
  - **后果 ①（跨维度串数据）**：主世界与下界允许同坐标两个容器（如都在 (0,64,0) 放箱）⇒
    共享一条运行时记录，燃料/进度互相顶；且 `trackLivingSlots` 每 tick 互相覆盖历史记录 ⇒
    两边槽位物品类型不同时**每 tick 互相登记作废 ⇒ 进度永远攒不起来**
    （正是 2026-10-11 方案 A「燃料绑槽位」那类 bug 的跨维度版本）。
  - **后果 ②（作废登记打空）**：`removeDataByPos`（拆箱子 / 区块卸载）从位置索引取出的是
    **前缀键**，却拿去删 `LIVING_SLOT_ITEMS`（裸键表）并登记 `RuntimeCacheInvalidation` ⇒
    「容器整体卸载 ⇒ 作废整个运行时缓存」**登记即打空**（拆掉原地放回，旧燃料/进度复活）。
  - **修法（收敛，不是发明新机制）**：`ContainerIdentity.getCacheKey()` **一处定义**键规则 ——
    **世界容器** = 维度前缀 + 稳定键（`<维度>|chest_…`）；**玩家背包 / 末影箱 / 掉落物** = 裸键
    （UUID 全局唯一；且数据必须**跟人走** —— 过传送门不清零身上活熔炉的燃料，顺手修掉
    `cacheKey` 曾给背包键也加维度的潜伏问题）。四张表（运行时缓存 / 槽位追踪 /
    `CONTAINER_DATA` / 位置索引）同构；域侧 5 处（熔炉/漏斗/酿造台/发电机 +
    `FluidFlowServerSync` 两处）与 `trackLivingSlots` 改传缓存键；
    `RuntimeCacheInvalidation` 字段随键义改名 `cacheKey`；`cacheKey(ctx)` 降为薄委托。
    **裸稳定键格式不动**（末影箱显式键 / 发包 `player_` 分支 / `parsePosFromContainerKey` 都依赖它）。
  - **特意不动**：末影箱路由表（`EnderChannelEntry` 自带 `dimension()` 字段，另一套设计）；
    `ContainerMonitor` 调试开关按裸键（玩家按坐标下达命令）。
  - **验证**：新增 `SimpleContainerContextTest.CacheKey`（6 项：世界容器带前缀 · 跨维度同坐标
    键不同 · 大箱子保留两段坐标 · 背包/末影箱裸键 · 无维度退化）+ 生命周期回归 1 项
    （整容器作废键 == 运行时存储键）；编译 + 全量测试 **596 绿**（589 → 596）；
    `check_layers` 零新增违规。

- 🐛 **修复：缩小窗口后活按钮跑到屏幕外**（用户实测）—— 拖动时的夹取只管**当时那一次**；
  保存的位置是「相对 GUI 左上角」的偏移，玩家之后**缩小窗口 / 调大 GUI scale** ⇒ 逻辑屏幕变小
  ⇒ 换算出的屏幕坐标可能落到屏外（按钮"消失"）。
  **修法**：新增 `LivingButtonLayout.clampToScreen(...)`，**应用位置时再夹一次**
  （放在 `LivingButton` 构造里 —— 覆盖所有创建路径）。**只夹显示位置、不回写配置**
  ⇒ 恢复窗口后按钮回到原位。
  新增 `LivingButtonLayoutTest`（1 项：屏内不动 / 越界夹边缘 / 屏幕比按钮还小取 `max(0,…)`）；
  测试 578 → 579 绿。

- 🔍 **收尾审查（本轮全部改动的复查）—— 修 3 处、留 3 条观察**：
  - 🐛 **tooltip 批次进度百分比语义反了**（本轮 `f9d4d06` 引入）—— `percent` 用的是
    `brewTime`（**剩余**倒计时 400 → 0），而文案是「酿造中: X%（剩 Y 秒）」⇒ 刚启动显示
    「进度 100%（剩 20 秒）」自相矛盾。修：`percent = (BREW_TIME − brewTime) / BREW_TIME`
    （进度 0% → 100%），与「剩 N 秒」一致。
  - 🐛 **`ContainerRuntimeCache` 服务端零清理入口（跨存档继承）** —— 违反
    `StaticCacheRegistry` 铁律 A1：单机「退出存档 → 进另一个存档」**不重启 JVM**，
    而容器键 = 维度 + 坐标 ⇒ **同坐标箱子会继承上个存档的燃料 / 进度 / 相位**。
    此前只登记了**客户端**遥测缓存（`RuntimeRegistration`），服务端 `store` / `dirtyContainers`
    从未清。修：新增 `ContainerRuntimeCache.clear()`，在 `RuntimeRegistration` 登记
    `onServerStop`（该包 L2 的登记点，同层依赖 ⇒ 零分层违规）。
  - 🧹 **删死代码 `BrewingRecipes.isBottle`**（本轮 `a7aa94d` 引入，零调用者）——
    原版 `isInput` 形参名无歧义，本不属「需包装的误导签名」；瓶子准入用
    `isEligibleBottle`（非空 + 非活）已足够（`hasMix` 兜住无效物品，行为与原版一致）。
  - 📌 **留观察（未改）**：① `drainDirty` 跳过空快照 ⇒ 单槽位容器作废后不下发（玩家拿走
    物品后通常也不看该 tooltip，影响小；2026-10-12 复查后**维持不改**）；
    ② 规则 7 的**燃料侧**无单测 ⇒ 2026-10-12 `ce985ea` **已补**（`isBlazePowder` 放开包级
    + 2 项，`BrewingSlotEligibilityTest` 6 项）；③ `FluidTransformTable` 未校验
    `output` 必须为药水类物品 ⇒ 2026-10-12 `ce985ea` **已补**装载期校验 + 1 项测试。
  - **验证**：编译 + 全量测试 **579 绿**（三处修复均未新增测试）；`doc_check` 11 项通过；
    `check_layers` **零新增违规**。✅ 跨存档清理已游戏内实测通过（2026-10-12 晚：
    退出存档 → 进新存档，同坐标箱子内的活熔炉燃料归零）。

- 🆕 **活耕地种植悬停提示（客户端反馈）** —— 光标持活种子悬停活耕地时，鼠标处渲染
  「种植 / 作物 / 消耗」提示；种子不足给差额（需要 N / 持有 M），已种植给「已种植」说明。
  **缘起**：原版 `AbstractContainerScreen#renderTooltip` 对**光标非空**的悬停
  **不渲染任何 tooltip**（判据 `menu.getCarried().isEmpty()`）⇒ 拿活种子悬停活耕地
  零反馈，右键没反应也无从判断（数量不足 / 已种植两种静默失败）。
  **契约**：提示门槛**复用 `PlantCropHandler.canPlantWith`**（活种子 + 可种植 +
  种子数 ≥ 耕地堆叠数），不另写数量比较 —— 提示说「种得成」必须与真实交互零漂移；
  文案四态分流（充足 / 创造免费 / 不足差额 / 已种植）。
  **渲染**：`AbstractContainerScreenMixin` `render` TAIL 走 `GuiGraphics#renderTooltip`
  批量队列（与方向配置 HUD 同款 ⇒ 天然压在物品图标上；原版 tooltip 被 carried 判据
  抑制 ⇒ 两者永不重叠，无需抑制原版）。纯客户端、零发包。
  协议与可复用模式（三件套：纯函数 / 门槛同源 / render TAIL）落
  [gui-interaction-system.md](../system-design/gui-interaction-system.md) §7。
  新增 `FarmlandPlantHintTest`（10 项：门槛 null 分流 + 四态文案键与参数），测试 579 → 589 绿。
  ✅ 游戏内实测通过（悬停提示效果确认）。

- 🔧 **重构：static 缓存清理改为「缓存类自己登记自己」（取代集中登记，治「忘接线」）** ——
  **缘起**：2026-10-12 扫出 **3 处零调用者的 `clear()`**（`LivingToolPlayerClientCache` /
  `LivingToolAssistState` / `EnderChannelClientCache`，javadoc 却都写着「XX 时调用」）——
  根因是**缓存类与登记点在两个文件**，写完 `clear()` 忘接线时**静默失效**
  （`ContainerRuntimeCache` 的漏登记是同一类，只是它连 `clear()` 都还没写）。
  **改法**：登记搬进**缓存类自己的 `static {}` 块**（与 `clear()` 同文件、隔几行）⇒
  「写了 clear 却漏登记」在结构上几乎不可能。分层干净（`StaticCacheRegistry` 在 **L1**
  ⇒ 任何缓存类调它都是**向下依赖**；此前担心的「L1 import 领域」只针对**它自己的静态块**）。
  **注册表类天然不参与**（跨存档无害、本就不写 `clear()`）——「写了 `clear()`」即「需要清」的信号。
  **连带**：① 现有 10 项集中登记（`StaticCacheRegistry` 静态块 2 + 各 `XxxRegistration` 8）
  全部迁到各缓存类，共 **13 项**（8 server + 5 client）；② 各 `XxxRegistration` 的登记行移除
  （留一行注释指向缓存类）；③ **删除迁空的 `RuntimeRegistration`**（`runtime` 是 L2 机制层，
  本就不该有「域注册入口」）；④ `StaticCacheRegistry` 新增 `describe()` 补回「集中登记」丢失的一览性。
  ⚠️ **校验工具已评估并放弃**：曾写好「棘轮基线 + 三规则」脚本（能抓「有 clear 却零调用者」），
  但结构消除后其主要价值（抓「忘接线」）**已不存在** ⇒ 按用户决定不做。
  **验证**：编译 + 全量测试 **589 绿**；`check_layers` 零新增；`doc_check` 通过。
  ✅ **已游戏内实测通过**（2026-10-12 晚）：退出存档 → 进新存档，同坐标箱子的活熔炉燃料归零；
  新存档开局不渲染上个存档的活工具环 / 挖掘环 / 末影箱 tooltip。

- 🏗 **doc_check 第 2 项扩为「条数 + 全绿」双核验；AGENTS.md 删手维护 passed 快照数** ——
  原「全绿基线：`554 passed / 0 failed / 1 skipped`」是**无校验的快照**：2026-10-10 写下，
  两天后实测已 588 passed（total 有 doc_check 守着、passed 没有）⇒ 必漂，且漂了没人知道。
  **修法**：① doc_check 第 2 项在**同一批已解析的 XML** 上追加 `failures/errors` 恒 0 断言
  （零新增 IO）——「全绿」从手维护 claim 变成机械断言；② AGENTS.md 只留**已被核验的总数**
  + 指向 [test-map.md](../reference/test-map.md) 的路由（唯一 1 项 @Disabled 是环境性的：
  FML 单测不加载 item tags，`stack.is(TagKey)` 恒 false，已游戏内验证兜住 —— 事实与回归
  方法住测试注释与 test-map，入口不复述）。
  **否掉的方案**：三数全写进 AGENTS 并全部核对 —— 那等于强制执行一个**每次加测试就要改**
  的数字（churn），而携带不变量的两个数（total / failures）本就该机械核。
  判据同第 7 项「窄而准 > 宽而吵」。

---

## 2026-10-11

> 🐛 修复 Mixin 访问器与 inventoryessentials 重名冲突（点创造标签页崩溃），
> 由此确立「Mixin 铁律」并落成代码侧自动校验 `tools/check_mixins.py`。✅ 游戏内实测通过。
> 🆕 **活酿造台第二段：酿造逻辑落地**（功能完成）—— 批次状态机 + 次数制燃料 + 堆叠加速，
> 测试 555 → 568 绿，✅ 游戏内实测通过。

- 🐛 **修复：Mixin 访问器重名冲突 ⇒ 打开创造模式标签页客户端崩溃（MixinApplyError）** ——
  与 inventoryessentials（背包必备）各自声明同名 `SlotWrapperAccessor`，且都
  `@Mixin(targets="...CreativeModeInventoryScreen$SlotWrapper")` + `@Accessor Slot getTarget()`
  ⇒ 在目标类上生成的合成方法名 `getTarget` 撞车 ⇒ `Incompatible @Accessor getTarget
  ... previously written by ...` ⇒ 加载 `Slot` 类（点创造标签页）时 `MixinApplyError` 崩溃。
  **修法**：改名加 `living_item` 专属前缀 —— 接口 `LivingItemSlotWrapperAccessor` +
  方法 `living_item$getWrappedTarget()`（`living_item.client.mixins.json` 与
  `ClientSlotResolve` / `CreativeModeInventoryScreenMixin` / `AbstractContainerScreenMixin`
  3 处调用点同步）。**冲突判定键 = 在目标类上生成的方法名，与接口名/包名无关**
  ⇒ 方法名前缀才是关键。

- 📌 **由此确立「Mixin 铁律」（AGENTS.md）并落成 `tools/check_mixins.py` 自动校验** ——
  **R1**：`@Accessor`/`@Invoker` 方法名必须带 `living_item$` 前缀（通用名 = 原版字段
  派生/短名默认有全局碰撞风险，「以小见大」：先加前缀再写，别等撞了才改）；
  **R2**：必须显式指定目标成员（`@Accessor("字段名")` / `@Invoker("方法名")`）；
  **R3**：`*mixins*.json` 条目/插件 ↔ 源文件一致（3 份配置全扫，含 `mixins-create`）；
  **R4**：`@Mixin` 源文件 ↔ 配置双向登记一致。`AbstractContainerScreenAccessor` 的
  `getSkipNextRelease/setSkipNextRelease`（原版私有字段 `skipNextRelease` 派生名）
  属同类隐患 ⇒ 一并加前缀。校验器对标 `check_layers.py` 体例（违例即退出码 1，
  可挂 CI / 提交前检查）；负面样本实测会报警（R1/R2/R4 各一条 → 退出 1）。

- 🔴 **踩坑1：裸 `@Accessor` + 带前缀方法名 ⇒ 启动即 FATAL**（clean 重建后实测崩溃）——
  Mixin 靠方法名 `get`/`is`/`set`（Invoker：`call`）前缀**推断**目标字段名，方法名带
  `living_item$` 前缀后推断失效 ⇒ `InvalidAccessorException: Failed to inflect target
  name ... supported prefixes: [get, is]`（`AbstractContainerScreenAccessor` 实测）。
  **「加前缀」与「显式指定成员名」必须成对出现，缺一即崩** ⇒ 已补
  `@Accessor("skipNextRelease")`。（`LivingItemSlotWrapperAccessor` 没踩，
  因为它本来就写的显式 `@Accessor("target")`。）

- 🔴 **踩坑2：第一轮实测跑的是旧构建 ⇒ 报错里还是旧名** —— 崩溃报告写的是
  `living_item.client.mixins.json:SlotWrapperAccessor`（改名前的名字）⇒ 源码早已改对。
  **判据：报错里的配置条目名 = 该构建实际注册的名**，与源码对不上 ⇒ 先怀疑构建产物过期
  （重新 build + 部署），别急着怀疑改法本身。

- 🧹 **更正文档错误说法：`@Shadow` 不能加前缀** —— shadow 是「别名目标已有成员」，
  必须与目标成员**同名**（加前缀 = 解析不到目标而失效）；且 shadow 成员被剥离、
  不生成新成员 ⇒ 无碰撞风险。此前铁律里「建议 @Shadow 也加前缀」是**错误说法**，
  已更正留痕（与 R1 方向相反，别混淆）。

- 🧹 **5 处 active 文档同步旧名**（`gui-interaction-system.md` 正文+组件表 ·
  `container-identity.md` · `gui-click-interception.md` 坑9 代码示例 ·
  `living-map-ender-pearl-tech.md` 解包代码 · `living-water-wheel-tech.md` mixin 清单）
  —— 不改则文档示例还在教旧名，未来 AI 照着学错。`docs/archive/` 历史条目按约定不动。

- ✅ **游戏内实测通过**（2026-10-11）：启动正常 + 打开创造模式物品栏点标签页不再崩
  （与 inventoryessentials 共存）。

- 🏗 **新增构建指纹：启动日志自证「跑的是哪个构建」** —— 缘起本文踩坑2（旧 jar 白跑一轮实测）：
  主类构造器**首行**打印 `living_item 构建: v<版本> commit=<短哈希> built=<时间>`
  （`LivingItem.logBuildFingerprint`，构造器最先执行保证指纹出现在日志最前；读取失败兜底
  `unknown`，**不抛异常** —— 指纹只是观测信息，任何异常都不得影响模组启动）。
  注入复用 `generateModMetadata` 的既有模式：新建 `generateBuildProps` 任务 +
  模板 `src/main/build-props/living_item_build.properties`（**不放** `src/main/resources/` ——
  裸占位符永不进 jar）；commit 经 `providers.exec git rev-parse --short HEAD` 解析，
  无 git 环境（CI 干净克隆）兜底 unknown、不阻断构建。
  🔴 **两个坑入档**：①`filteringCharset = 'UTF-8'` 必须显式声明 —— 不声明跟随平台默认编码，
  Windows GBK 下模板中文注释写进产物即乱码；②Groovy 模板引擎会展开**所有**花括号引用，
  注释里写 `${xxx}` 示例也会被替换（甚至未知引用直接报错）⇒ 模板注释措辞避开花括号。
  ⚠️ **只把 commit 声明为任务输入**（commit 变了才重跑）；`build.time` 刻意不作输入 ——
  否则秒级时间戳每次都变，每次构建（含 runClient）都全量重跑资源处理。
  无新测试（纯观测日志，无业务逻辑 ⇒ 测试计数 555 不变）。

- 🆕 **活酿造台第二段：酿造逻辑落地（功能完成）** —— 第一段（2026-10-10）只做了 5 槽位方向与框架接入，
  本次补上全部酿造逻辑，`living/domain/brewing/` 新增 3 类：
  - `BrewingRecipes` —— `PotionBrewing` 薄适配层：原版 `hasMix` / `mix` 的**形参名与实际语义相反**
    （原版自己注释了 "Parameters are swapped…"）⇒ 包成自解释签名，把坑收在一处；`level.potionBrewing()`
    已聚合原版 + 全部模组配方 ⇒ **零兼容代码**。
  - `BrewingBatchLogic` —— **纯逻辑批次状态机**（`Idle`/`Continue`/`Abort`/`Complete`/`Start`），
    结构与判据顺序**完全照抄原版 `serverTick`**（含「完成那 tick 不再检查材料变化」这个 `else if` 细节），
    只把「−1」换成「−step」；不碰世界 ⇒ 可单测。
  - `BrewingStandSegment` —— 运行时片段（档 2）：批次倒计时 + 本批次材料 `Item`（瞬态 ⇒ 不影响堆叠）。
  `LivingBrewingStandFunction.tick` 落地 5 步：补燃料 → 算 `canBrew` → 推进状态机 → 写运行时缓存
  → **仅 dirty 时写 DataComponent**（燃料 / 首次方向 ⇒ 稳态零写入）。
  ⭐ **三条核心语义**：① **燃料与材料在批次两端扣**（启动扣 1 次燃料、完成扣 1 份材料；作废不退不扣）
  ② **作废判据只有材料**（不可酿造 / 材料 `Item` 变了；**瓶子完全不参与** ⇒ 中途换瓶子不作废）
  ③ **堆叠 `step = count`**（唯一「过原点斜率 1」的线性 ⇒ 单个 = 原版 400t；**每批成本固定** ⇒
  等价「N 台并行酿造台」，堆叠的唯一收益是槽位压缩）。`fuel` 落盘（D2），用 `optionalFieldOf`
  保证第一段写过的旧数据仍可解码。⚠️ 已知：拆堆会复制燃料（组件随 `split` 复制）⇒ **按用户拍板当特性保留**。
  新增 `BrewingBatchLogicTest`（13 项：启动 / 完成 / 作废两判据 / 堆叠 7t / 完成 tick 忽略材料变 /
  成本不随堆叠变）；测试 555 → 568 绿。✅ **游戏内实测通过**（所有功能逻辑正常）。
  现状文档 → [`docs/tech/living-brewing-stand-tech.md`](../tech/living-brewing-stand-tech.md)；
  设计稿归档 → [`docs/archive/living-brewing-stand-design.md`](../archive/living-brewing-stand-design.md)。

- 🐛 **修复：活熔炉燃料「被绑在槽位上」+ 由此产生的刷燃料漏洞（运行时缓存生命周期，方案 A）** ——
  用户实测报告：把活熔炉拿出来再放回去、或取消活化再活化，燃料值「又回来了」。
  **根因**：`ContainerRuntimeCache` 的键是 `(容器键, 槽位索引)`、**不含物品身份**，
  且 `removeSlot` / `removeContainer` **零调用者**（`cleanupStaleData` 只清容器级数据）
  ⇒ 条目永不过期；「跨容器搬运」看起来正常只因换了 `containerKey`、新键没数据。
  ⇒ 同一槽位换物品会**继承**旧物品的瞬态状态（熔炉燃料 / 进度 · 漏斗冷却 · 酿造台进度），
  **可刷燃料**（拿走烧了一半的熔炉、换个新的进同一槽位 ⇒ 白得剩余燃烧时间）；跨槽位搬运则**丢**数据。
  ⭐ **语义定案（用户拍板）**：运行时数据属于「**当前槽位里的那台机器**」—— 机器换了，数据就该作废。
  **修法（方案 A）**：L1 `ContainerLivingItemHandler` 每 tick 对比「活物品槽位 → 物品类型」，
  失效的槽位登记进待清队列（容器卸载登记整容器）；L4 `LivingItem.onServerTick` 取走队列并调
  `ContainerRuntimeCache.removeSlot / removeContainer`（放在 flush **之前** ⇒ 作废也在本 tick 下发）。
  ⚠️ **拆成「L1 登记 / L4 执行」是为了不新增 R1 分层违规**（container 在 L1、runtime 在 L2）。
  ⚠️ **失效判据只看 `Item`、不看组件** —— 燃烧标志会被 tick 改写，纳入判据会每次状态翻转就误清缓存。
  新增 `ContainerRuntimeCacheLifecycleTest`（5 项）；测试 568 → 573 绿；分层校验**零新增违规**。
  已知边界：**同类物品原地交换**（手持新熔炉直接点旧熔炉）判据看不出变化 ⇒ 接受
  （彻底解决需给物品加实例 id，与活酿造台 D2「拆堆复制当特性」同族）。

- 🐛 **修复：活酿造台把活物品当耗材消耗（隔离规则漏实现）** —— 用户实测：「会交互活物品，
  比如会将活红石当作材料消耗」。根因：首版实现**漏了 `LivingItemManager.isLivingItem` 检查**
  （材料 / 燃料 / 瓶子三处都没有），而 §0 规则 7 与设计稿 §2 都写了「活物品不作材料 / 燃料」——
  同族对照：活熔炉的输入槽 / 燃料槽**都有**该检查。危害：材料侧被 `consumeOne` **吃掉**；
  瓶子侧更隐蔽 —— 原版 `hasMix` 只看 `POTION_CONTENTS`、**不看活标记** ⇒「活药水」会被 `mix`
  **原地替换**（丢掉活标记与内部状态）。修法：抽出三个准入判据 `isEligibleIngredient`（材料）/
  `isEligibleBottle`（瓶子）/ `isBlazePowder`（燃料），`isBrewable` 与 `doBrew` 共用。
  新增 `BrewingSlotEligibilityTest`（4 项）；测试 573 → 577 绿。
  ⭐ **教训（入档 tech §9.5）**：**§0 规则表里每一条都必须有对应的实现点与验证入口** ——
  只写在规则表里不构成实现，规则表反而会让人以为已经做了。

- 🧹 **新增指南：[guides/living-item-design-template.md](../guides/living-item-design-template.md)
  「新活物品设计稿模板与检查表」**（**代码未动**）—— 立文依据 = 今天暴露的两个**横切维度遗漏**
  （运行时数据生命周期 / 槽位准入）：它们都不是「功能逻辑写错」，而是**设计阶段没覆盖的维度**，
  且**单测覆盖不到**（只有破坏性实测才暴露）。模板固化四项：
  ① **生命周期表**（每个状态：存在哪 / 物品移走 / 取消活化 / 换槽位 / 容器卸载）·
  ② **准入表**（每个槽位：什么能进 / 什么必须挡 / 挡不住会怎样）·
  ③ **原版对齐清单**（逐条对照，偏离要写理由 —— 偏离是有意识的、漏项是无意识的）·
  ④ **规则 → 验证入口对应表**（指不出来的规则 = 它没有验证手段）。
  AGENTS 路由已加（「工程实践」族 + 触发条件式导航）。

- 🐛 **修复：活按钮位置在大箱 / 单箱之间互相覆盖**（用户指出）—— 位置持久化的 key 原本只用
  **Screen 类名**，而**原版单箱与大箱是同一个 `ChestScreen` 类**（只有 `ChestMenu` 的槽位数不同）
  ⇒ 两者**共享同一份位置**，但 GUI 高度差 56px（166 / 222）⇒ 拖过其中一个会破坏另一个的布局。
  **修法**：key 改为 **Screen 类名 + GUI 尺寸**（`LivingButtonLayout.screenId(screen, width, height)`）；
  配置 `version` 1 → 2（旧文件按「版本不识别」忽略，**不做迁移** —— 铁律 6：alpha 阶段不迁就旧数据）。
  📌 **实现注记**：mixin 类 `extends Screen`（**不是**目标类 `AbstractContainerScreen`）
  ⇒ 尺寸必须**显式传参**（`getXSize` / `getYSize` 只在 `AbstractContainerScreen` 上，
  mixin 内拿不到该静态类型）。
  ⚠️ 顺带覆盖同类：`GenericContainerScreen`（模组容器，尺寸可变）此前也受影响，一并修好。

- 🆕 **活水源新增转化：空瓶 → 水瓶（+ 转化表支持产出带内容的药水）** ——
  ① **框架扩展**：`FluidTransformTable` 条目新增可选字段 **`output_potion`**（药水类型）——
  ⚠️ 只写 `output: minecraft:potion` 会得到 `PotionContents.EMPTY`（"**不可合成的药水**"）
  而**不是水瓶** ⇒ 必须走原版工厂 `PotionContents.createItemStack` 指定内容；引用未知药水 ⇒ **整条跳过**。
  ② **内置条目 16 → 17**（`water_empty_bottle`）。
  ③ **玩法边界已论证**（tech §6.2）：判据是「**产物是否成为活化资产的载体**」——
  水桶**能放置水**（载体）⇒ 取消；水瓶**不能放水**（非载体）⇒ 保留，且它只是原版可再生品的
  容器内自动化、**不引入新资源通道**（水瓶的瓶颈是玻璃瓶）。
  ⚠️ 副作用（已知并接受）：**空瓶必须单个**才转化（水瓶 maxStack = 1 ⇒ 缩容等待）⇒ 手动节奏、非自动化。
  测试 577 → 578 绿（`FluidTransformTableTest` 6 → 7 项）。

## 2026-10-10

> 文档系统维护（**代码未动**）—— 三项：修红线单位 · 新增一层 · 新增一项校验。
> 判据：本次**翻转了一个旧结论**（入口体量「OK」实为误判）+ **新增一项校验能力**
> ⇒ 按 `docs/README.md` §4.0 需进 changelog（先例见 2026-10-06「文档系统改动，代码未动」）。

- 🧹 **入口体量红线修单位：`≤20KB` → `≤20,000 字符`**（`docs/README.md` §1/§2）：
  实测 `AGENTS.md` 已 **22,377 字节（21.9 KiB）**，而 `doc_check` 第 5 项一直报 OK（14,402 字符）
  ⇒ **红线形同虚设**（脚本按 `len()` 算字符，规约写的是 KB，中文 UTF-8 一个字符 3 字节）。
  单位**不是新决定，而是回到 `D-doc-01`（2026-09-16）的本意** —— 该决策写的就是
  「≤ 20,000 字符」，理由要点「**新会话加载预算**」。
  🔴 **同一个坑 2026-10-06 已踩过一次**（入口索引瘦身时把 `wc -c` 字节数当字符数，
  5933 vs 真实 3957，**诊断整个反了**）⇒ 本次在 README 与脚本常量旁**各写一行判据**，
  不再只靠人记：**这是 AI 的加载预算 —— AI 按 token 消耗上下文，与文件占多少磁盘字节无关。**

- 🧹 **`docs/README.md` §1 新增「工作层（`docs/*.md` 根）」** ——
  `idea.md`（草稿纸）/ `TODO.md`（待办池）/ `INFO.md`（玩家文案）此前在分层表里**没有归宿**，
  而 §3 同时写着「待办 → `docs/buffer/`」⇒ **规约自相矛盾**（§3 已同步：待办归工作层、
  不归 buffer；另修掉该行重复文字与「决策 → `decisions/`（规划中）」的过期表述）。
  ⚠️ **不搬文件**：`TODO.md` 被 6 处文档引用（含 `api-contract.md`），
  **搬动的断链风险 > 分类整洁的收益** —— 这正是 §5「降级而非删除」的反面：**别为分类纯粹而搬运**。

- 🧹 **`doc_check` 新增第 11 项「buffer 滞留」（警告级）**：
  `docs/buffer/` 里**状态标了「已完成」**的文档会被当未定案读 —— **铁律 0 点名的头号误导**
  （典型：`runtime-mechanization-plan.md` 标着「✅ 已实现并验收」却待在「不稳定」层）。
  **只报警、不自动搬运**：AGENTS 进展指针可能正指向它（第 1 项随后会报断链）⇒ 由人决定。
  **判据是双向的**（完成词 **且不含**未完成词）：`open-plan`「部分落地」、
  `power-refactoring-plan`「进行中」内部都用 ✅ 标已完成步骤，**只匹配完成词会误报**；
  实测精确命中 2 份（`architecture-layering-plan.md` / `runtime-mechanization-plan.md`）。
  为什么是**警告级**：搬不搬需要人判断（入口指针），不是确定性错误 ⇒ 与第 5 项同级。

- 🧹 **方向 1 执行：清单型内容退出文档**（**代码未动**，决策 `D-doc-05`）：

  - **删 `docs/reference/file-map.md`（359 行）** —— 主文件树是**清单型**内容，
    自证漂移：09-16 自查「缺 39 个」后从未补齐，10-10 实测缺 **112+**（`tools/` 整域、
    `BulbBank` / `PhaseInterpreter` 等 10-09 新增类全不在树里）；同文件的**测试树**
    因第 3 项校验不漂 ⇒ **同一份文件的对照实验**证明「人工快照 + 机械校验」只在被守的半边有效。
  - **删 `docs/reference/completed-features.md`（102 行）** —— 首行自述「数量是迁出时的值，
    以 AGENTS 为准」（自认不可信），且与 `subsystem-index.md` 重合
    （`entry-index-slimming.md` Q2 悬置的问题就此关闭）。
  - **测试树保留**，拆为 `docs/reference/test-map.md` —— 它是**叙事型**（每个测试类
    「守什么契约」，`find` 列不出），且有第 3 项守着；`commands.md` 复核后**保留**
    （执行中修正初判：它实为叙事型契约 —— 目标语法 / 匹配优先级 / via 对齐坑，非清单）。
  - **15 份 tech §0 新增「实现入口」**（引用块，紧跟 §0 标题）：承接 file-map 注释里
    真正有价值的部分（每子系统 3~5 个关键类），数据源是**实际目录清单**而非漂移的 file-map。
  - **约 11 处引用改向**（AGENTS 4 · README 3 · `block-facing.md` 1 · 脚本 3），
    第 1 项校验中途抓到 2 处历史叙述里的路径提及（补「已删除」豁免标注后通过）。
  - **净效果**：文档 **-379 行**（两份删除）；校验 **11 项不变**（第 3 项改读 test-map.md，
    **没有新增校验** —— 删掉的是需要被校验的东西本身）。

- 🧹 **方向 2 执行：需求池统一到 `TODO.md`**（**代码未动**，决策 `D-doc-06`；用户拍板，
  **反转**了我此前「收敛到 §0」的推荐 —— 统一一处好查，不翻 15 份）：

  - **15 份 tech §0 的「未实现 / 待定」字段全部移除**：9 份纯占位（「无明确待办」）直接删；
    实义条目迁 TODO 新分区（**红电 / 活红石·活铜 / 活武器**），**每条带 tech 文档指针**
    （缓解「计划离开实现邻近性」的损失）。
  - **⚠️ 分拣是关键**：「未实现」节里混着四类内容，只有一类是待办 ——
    **A 计划**迁 TODO（活避雷针 / 活活塞 / 活门 / 装饰器渲染 / 活潜影箱 / 蓄力型 / PVP /
    流体四条）；**B 明确不做的边界不随迁**（Pipez / Flux Plug 归位 power §0 规则表 9/10 条；
    装桶自动化取消留 fluid §9「边界与留痕」）—— 删了会丢防回归判据；
    **C 待实测**并入 TODO 既有条目（去重）；**D 设计稿正文**（蓄力型 ~30 行，已定稿）
    迁 `buffer/living-weapon-design.md` §5，TODO 只放一行 + 指针。
  - **weapon §9 结构顺带修正**：原「§9 未实现」标题下挂着「辅助攻击（已实现）」等小节
    （历史错位）—— 节名改「§9 蓄力型（待开工）与实现记录」，已实现小节不再挂在未实现节下。
  - **TODO.md 自述规则修正**：原写「已完成项直接删掉」与实际保留的删除线矛盾
    ⇒ 改「保留删除线 + 完成日期」；头部声明其为**全项目唯一需求池**。
  - **fluid §9** 改造为「边界与留痕」（计划迁 TODO 并与既有「活流体」节**合并去重**；
    已完成留痕压缩保留）。
  - 入口补路由：AGENTS 文档导航新增「查某子系统的待定计划 → TODO.md」。

---

### 🐛 修复：辅助模式挖传送石碑「方块消失但零掉落」

**现象**（用户实测）：活镐子挖传送石碑（Waystones 1.21.1）—— 方块消失、**零掉落**；
真人手持同一把挖会掉；空手挖石头 / 铁矿 / 钻石矿照常掉；**主动模式（有记忆、自己挖）也正常**。

**根因**：石碑的物品掉落**不走原版流程** —— `WaystoneBlockBase#playerWillDestroy` 手动调
`dropResources`，并加了一道闸门：

```java
if (!player.getAbilities().instabuild && player.hasCorrectToolForDrops(state)) {
    dropResources(state, ...); dropResources(offsetState, ...);   // ← 石碑物品的唯一来源
}
```

而 `Player#hasCorrectToolForDrops(BlockState)`（1 参数）读的是**主手选中格**
（`!requiresCorrectToolForDrops() || inventory.getSelected().isCorrectToolForDrops(state)`）：

- **辅助模式**：破坏者是**真玩家**、主手**空** ⇒ false ⇒ 闸门不过；紧接着 `playerDestroy`
  又因"双格方块"把状态换成 `Blocks.AIR` ⇒ **净结果零掉落**；
- **主动模式**：破坏者是 `LivingToolFakePlayer`，活工具经 `equipTool` 装进**假玩家主手**
  ⇒ 同一判据自然为 true ⇒ 正常掉落。
  **两条路径的唯一差别就是"执行者主手有没有工具"** —— 这条反证把根因钉死。

**为什么石头会掉**：辅助模式原有三条腿（加速 / 材质门槛 / 掉落重算）全挂在 **NeoForge 事件**上，
事件覆盖的是**原版路径**（`canHarvestBlock` → `doPlayerHarvestCheck` 发事件）。
而石碑读的 1 参数重载被 NeoForge 标
`@Deprecated // Neo: use position sensitive version below`，**不发任何事件** ⇒ 钩子挂不上。

**修法**（两步，各自独立可验）：

1. **纯重构**：抽出 `LivingToolAssist#hasBackpackToolFor(player, state)` 作为材质门槛的
   **单一来源**，`onHarvestCheck` 改调它 ⇒ 行为零变化，靠**全量单测**证明没改坏；
2. **补入口**：新增 `mixin/PlayerAssistHarvestMixin` —— 在
   `Player#hasCorrectToolForDrops(BlockState)` 判 false 时再问一次辅助层
   （`@At("RETURN")`；原版判 true 直接短路 ⇒ 零额外开销）。这是 `LivingToolAssist` 的**唯一 mixin**。

**边界（明确不做）**：不动 `getMainHandItem`（那是"玩家看起来拿着什么"，改它会污染
渲染 / 其他模组 / 拾取）；不做"临时借手"；不做石碑专用 compat（治标，同类模组照踩）。

**不变的东西（判据）**：
- **挖掘速度**不变 —— `getDestroyProgress` 里的 30 / 100 走的是 `doPlayerHarvestCheck`（**发事件**），
  那条早已放行；
- **主动模式 / 假玩家**不受影响 —— `isAssistable` 已排除 FakePlayer；
- **判据口径只有一处** —— `LivingToolRecorder#isAssistTool`（无记忆的活工具）；
  未活化的普通工具、有记忆的活工具都不算。

⚠️ **行为变化（唯一）**：任何**直读** `hasCorrectToolForDrops` 的模组，都会认为
"背包里有活工具 = 玩家有工具"。这是**故意的**（正是辅助模式语义），石碑是第一个受益者。

> ⚠️ **仍有边界**：直接读**主手物品内容**（`getMainHandItem().isCorrectToolForDrops(...)`）
> 的模组覆盖不了 —— 改 `getMainHandItem` 不划算。那类只能靠模组自己改用能力判据。

**验证**：全量单测 **552 全绿（551 passed / 0 failed / 1 skipped）**，与基线一致；
✅ **游戏内实测通过**（2026-10-10：背包放活镐子 + **空手**挖传送石碑 ⇒ 正常掉落 1 个物品）。

**参考源**：`libs/src/Waystones-1.21.1`（用户先给错成 26.3，已换）。

### 🐛 修复：ProjectE 炼金箱导致「运行时同步」每 tick 崩溃（ClassCastException）

**现象**（用户崩溃报告 2026-10-10 19:34）：整合包（含 ProjectE 1.1.0）单人游戏的服务器 tick 循环抛
`ClassCastException`：`moze_intel.projecte.gameObjs.block_entities.AlchBlockEntityChest cannot be cast to
net.minecraft.world.Container`；调用栈 `LivingItem.runtimeContainerInstances` →
`LivingItemRuntimeSync.flush` → `LivingItem.onServerTick`。

**根因**：`LivingItem.runtimeContainerInstances`（运行时数据下发、按容器键反查该容器的 `Container` 实例供
viewer 匹配）在「单 BE」分支**无条件 `(Container) be` 强转**。而扫描阶段 `processContainerAt` 按
`IItemHandler.BLOCK` 能力面把**任何**有物品能力的方块实体当成活物品容器（分配 `chest_<x>_<y>_<z>` 键、每 tick 处理）。
ProjectE 的炼金箱 `AlchBlockEntityChest` 暴露 `IItemHandler` 能力（被 tick），但其方块实体
**不实现原版 `Container` 接口** ⇒ 强转失败。
（双箱分支本就用了 `instanceof Container` 守卫，唯独单 BE 分支漏了。）

**修法**（行为保持，仅修崩溃点）：
1. 单 BE 分支改 `be instanceof Container c ? List.of(c) : List.of()` —— 非 `Container` 的 BE 返回空集合；
2. 空集合语义早已闭环安全：`LivingItemRuntimeSync.flush` 第 59 行
   `if (instances == null || instances.isEmpty()) continue;` ⇒ 空集合 = 不发包（无人查看），不会发错人；
3. 顺手把原私有方法拆出**静态包级私有**单 level 分支 `runtimeContainerInstances(Level, BlockPos)` 便于单测，
   **行为完全等价**（无 BE ⇒ null 继续查下一世界）。

**边界（已知限制，非 bug）**：这类「有物品能力但不是原版 `Container`」的模组容器（ProjectE 炼金箱等）——
- **会正常被 tick**（里面的活物品照常运转）；
- 但**运行时 tooltip 同步不生效**：viewer 匹配依赖原版 `Container` 实例，匹配不上 ⇒ 永远不发包。
这是当前实现的限制，不是崩溃。是否要在扫描入口（`processContainerAt` / `ContainerChunkCache` 登记处）加
「容器能力白/黑名单」排除此类容器，是**待决设计**（见 TODO）。
根子是「扫描按能力面、同步按 `Container` 实例」两套口径不一致，属 `container-identity.md` 边界带契约范畴。

**验证**：新增回归测试 `LivingItemRuntimeContainerInstancesTest`（3 例：非 Container BE → 空集合不抛异常 /
原版 Container BE → 返回自身 / 无 BE → null），全绿；其余 `getBlockEntity → Container` 站点
（`ContainerContext` / `SimpleContainerContext` / `ContainerEnergyStorage` / `FluidFlowServerSync` /
`LivingItemContainerCommand`）经核对**全部 `instanceof` 守卫**，无同类强转。

---

### 🆕 活酿造台（Living Brewing Stand）第一段：纯框架接入

设计稿：[`docs/archive/living-brewing-stand-design.md`](../archive/living-brewing-stand-design.md)（D1~D6 全部定案）。

把原版酿造台虚拟化进容器槽位：5 个功能槽 = **3 个瓶子 + 燃料（烈焰粉）+ 材料**，
默认布局「瓶-瓶-瓶 / 燃料-台-材料」（`↖ ↑ ↗ / ← · →`），方向可自定义（同活熔炉）。

**本段不含任何酿造逻辑** —— 沿用「纯框架接入 → 特有逻辑」两段式，第一段用全量回归证明没改坏别的东西：

- 新建 `living/domain/brewing/`：`BrewingDirectionData`（槽位名→方向映射 + 默认布局）·
  `LivingBrewingStandData`（数据组件，当前只存方向）· `BrewingStandComponents`（组件总线）·
  `LivingBrewingStandFunction`（`tick` 空实现）· `BrewingStandRegistration`（域注册入口）。
- **斜向布局生效**：`supportsDiagonal()` 返回 `true` —— 这是九宫格对角键（`Q/E/Z/C`）的**首个消费者**，
  顺带验证了 GUI 侧「声明式能力」的设计（见下方 GUI 条目）。
- **图标零改动**（D5）：不注册 spec ⇒ 框架排除法自动挂 `LivingDefaultDecorator`（原版图标 + 活标记）。
- **活化规则零改动**：`activation_rules.json` 是 `default: allow` ⇒ 活酿造台默认可活化。
- 顺带：`BrewingDirectionData` 复用 `Pos2D.MAP_STREAM_CODEC`（该 codec 本就为「槽位名 → 方向」而存在）；
  加了方向 tooltip（5 槽位），让玩家不开容器也能核对配置。
- **与草案的差异**：`Segment` 与燃料字段留到第二段（第一段无瞬态数据可展示、无状态机可落盘，
  空注册只会是死代码）。
- **验证**：编译 + 全量测试 555 绿（无新增测试 —— 本段无新逻辑可测）。

**第二段待做**：`PotionBrewing` 适配层（`level.potionBrewing()` 已在启动时聚合原版 + 所有模组配方
⇒ **零兼容代码**）· 400 tick 批次状态机（中途换材料整批作废，D4）· 次数制燃料（烈焰粉 = 20 次，落盘，D2）·
堆叠加速（**只加速酿造过程本身**，燃料与材料消耗不变，D3）· tooltip 状态行 · 运行时片段 `BrewingStandSegment`。

---

### 🏗 GUI 交互改进：方向配置升级九宫格 + 活按钮可拖动（第一、二步落地）

设计稿：[`docs/buffer/gui-interaction-redesign.md`](../buffer/gui-interaction-redesign.md)（Q1~Q6 全部定案）。
契约与坑已沉淀进 [`gui-interaction-system.md`](../system-design/gui-interaction-system.md) §6。

**缘起**：活酿造台需要 5 个方向槽位（3 瓶 + 材料 + 燃料），而原方向配置只认 `W/A/S/D` 四个基本方向
⇒「上方 3 格」要两个对角，**4 个方向放不下 5 个槽位** ⇒ 暴露出一串**框架级**交互问题（影响所有活物品）。

- **第一步（`4892b95` + 4 轮实测迭代）—— 九宫格 + 提示层**：
  - 方向键位扩到九宫格 `Q W E / A S D / Z C`（**`X` 弃用**：与 `S`=下的直觉冲突，用户拍板）。
    键位 = 键盘左上角物理布局 ⇒ 一个键对一个方向，零歧义。
  - **按键阶段拦截**：`Q`（丢弃）/ `E`（关界面）必须在 `ScreenEvent.KeyPressed.Pre` 拦 ——
    GLFW 按键事件**先于**字符事件，等字符阶段原版动作已发生。
  - **活按钮 tooltip** + **方向配置 HUD**（悬停即显示 / 鼠标离开按钮即重置 / 输入高亮「已输入=绿、下一=黄+▶」）。
  - ⚠️ **HUD 渲染踩坑**（`9cdc8bf` → `7a93eba`）：自绘 `fill` 默认 **z=0**（比物品 z≈150 远）⇒ 被深度裁掉
    （表现为「被物品图标挡住」）；`flush()` 只改绘制顺序、**改不了深度**，救不了。最终改为 **`renderTooltip`**
    —— 整条进批量缓冲队列，天然在所有物品之上（机制已订正进 `icon-system.md`）。
  - **对角槽位改为声明式能力**：`HasDirection#supportsDiagonal()` 默认 `false`，需要斜向布局的功能自己声明
    ⇒ 未声明者不屏蔽 `Q`/`E`、HUD 只显示 `W A S D`（「精准拦截」的延续）。
- **第二步（`c0d9087`）—— 活按钮可拖动改位置**：按 **Screen 类名**分别持久化到
  `config/living_item/button_layout.json`（思路参考 TrashSlot，只取骨架；**吸附 / 碰撞 / 显示开关三者不做**）；
  坐标 = **相对 GUI 左上角的偏移**；空手左键按住拖动、屏幕内夹取、松手写盘（未移动不写）。
- ⚠️ **拖动事件必须自己转发（`b788e08`）**：`AbstractContainerScreen.mouseDragged` **整个重写了**
  `ContainerEventHandler` 的默认转发、且**不调 `super`**、恒返回 `true` ⇒ 子控件永远收不到 `mouseDragged`。
  原版凡要拖动自己控件的容器界面（`CreativeModeInventoryScreen` / `LoomScreen` / `BookEditScreen`）
  也都各自重写 `mouseDragged` ⇒ **这是既定设计，不是漏写**。
  解法：用 `ScreenEvent.MouseDragged.Pre`（在 `MouseHandler` 里、`screen.mouseDragged()` **之前**触发，
  取消即跳过原版）⇒ **不碰 mixin、不重写原版方法**。
- ⚠️ **两处命中 / 委托修正（`0a3a10a`）**：① 重写 `mouseClicked` 时**必须自己补** `isMouseOver`
  （基类才做 active/visible/命中检查）—— 漏判会让空手在界面**任意位置**按下都起拖；
  ② **不要盲目委托 `super.mouseDragged`** —— `AbstractWidget.mouseDragged` 对左键**恒返回 `true`**
  ⇒ 会取消所有左键拖动、**打断原版快速合成**。
- **方向符号专用字体（对角箭头自绘）—— 两次落地，第一次踩坑回退、第二次修好**：
  实测反馈「斜向箭头 `↖↗↙↘` 看着小了一圈」⇒ 查明**原版点阵字体只含 4 个基本方向箭头**
  （`nonlatin_european.png` 覆盖 U+2190–U+2194），**不含对角箭头**（U+2196–2199，三张图都没有）
  ⇒ 对角符号落到**兜底字体**。解法：模组自带字体 `living_item:direction`
  （provider 1 = 自绘点阵补 4 个对角字形，**必须放第一位**；provider 2 = `reference: minecraft:default`），
  符号文本经新增的 `DirectionSymbols` 挂字体（HUD + 熔炉/酿造台方向 tooltip）。
  ⚠️ **第一次失败（已回退 `431e28a`）**：游戏内**整条字体退化成缺字方块**（连原版有的基本箭头也变框）。
  ⭐ **根因**：bitmap provider 的 **`"file"` 字段必须带 `.png` 后缀** —— 原版是
  `"minecraft:font/nonlatin_european.png"`，当时漏成 `"living_item:font/direction_arrows"`
  ⇒ 贴图找不到 ⇒ provider 加载抛错 ⇒ **整个字体集退化成 `missingFontSet` ⇒ 全框**。
  加上后缀后重新接入。📌 **判据**：写自定义字体（`assets/<ns>/font/*.json`）**照抄原版字段格式**，
  `file` 不会自动补后缀。点阵规格：每格 8×8、像素只有「纯白不透明 / 全透明」两种
  （颜色靠游戏按字色染色）、推进宽度自动取最右非透明像素、`ascent` 同原版为 7。
  字形由**用户绘制**（对齐指标：墨量 ≈13 / 最右列 ≤6 / 最下行 ≤6）。
- **验证**：每步编译 + 全量测试 555 绿；✅ **游戏内实测通过**（拖动、按容器分别记忆位置、
  空手任意位置按下不再起拖、快速合成不受影响）。

**判据沉淀**：① 容器界面里做「控件级拖动」⇒ 走 `ScreenEvent.MouseDragged.Pre`，别指望默认转发；
② GUI 里做常驻悬浮信息 ⇒ `renderTooltip`（或显式传高 z，`fill` 默认 z=0 会被物品深度裁掉）；
③ 重写 `AbstractWidget` 鼠标事件时**命中判定要自己补、不要盲目 `super`**。

---

## 2026-10-09

> 架构分层收尾（续 10-08 框架侧）—— 反向边清到「只剩低 ROI 的层归属微调」。

- 🏗 **架构分层收尾：`transfer` / `compat` / `(root)` 反向边清零 —— R1 34 → 15**
  （连同 10-08 深夜的 ⑤/④/components，**底座（L1/L2）→ 领域 = 0**）：

  - **`transfer → domain`（A 类「抽象缺失」）**：新增 `transfer/ContainerLikeItems`（容器类活物品**谓词注册表**）；
    `Chest` / `Ender` / `Farmland` 三个 `*Registration` 各自登记 Provider / 谓词 / 交互
    ⇒ `SlotAccessorFactory` 的 `static{}` 不再认识领域实现。⚠️ `defaultProvider` 匹配一切、**必须最后**
    ⇒ 改在 `create()` 的兜底位置调用（领域 Provider 在 `commonSetup` 才注册）。
  - **`compat → water`**：新增 `api/StressSource`（`isEmpty` + `getNetStress`），`ContainerStressData` 实现它。
  - **`domain → (root)` / `components → (root)`**：日志门面统一 `ModLog`；命名空间常量改用 `LivingMod.ID`。
  - **`api → domain`（注释假边）**：`LivingItemManager` 的 `LivingToolAction` import **未被代码使用**、
    仅 javadoc `{@link}` 引用 ⇒ 删 import + 降为纯文字。

  ⚠️ **通用判据**：**注释假边** —— 边判据含注释 tokens，javadoc 里写具体类名
  （哪怕写的是「本类不认识 `XxxData`」）**也会产生一条依赖边** ⇒ 去耦合说明改用**描述性措辞**。
  验收：`551 全绿`、R1 **15**、R3 **10**、`doc_check` 9/9。

- 🧹 **文档卫生**：3 份已完成计划归档进 `docs/archive/`
  （`components-decoupling-plan` / `container-domain-decoupling-plan` / `redstone-driver-consolidation-plan`，
  均加归档指针）；`architecture-layering-plan` / `open-plan` 的**过期状态行**改为实际状态
  （原写「未定案 / 未开工」，实际已完成）。`doc_check` 9/9。

- 🧹 **清理 `LivingItemManager` 的孤儿 javadoc**：A1 迁移残留的 7 个空壳 `/** */` 块
  （底下无对应成员），**361 → 277 行**。`551 全绿`。

- 🐛 **修复：活水车应力输出导致 Create 小齿轮「重进存档后变成掉落物」**（每次都发生）。
  根因是 **Create 主动销毁** —— `RotationPropagator.propagateNewSource` 的
  「不要压制自己所在的网络（cycle）」分支 `world.destroyBlock(pos, true)`：
  重载后本块 `network` 从 NBT 恢复成「自身坐标」、邻居同网，而我们的激活分支
  （`prev==0 → 非0`）**未先 detach** 就 `setNetwork + attachKinetics()`；邻居转速又被
  Create 清 0 ⇒ `|newSpeed| > 0 + ε` 恒真。（实测日志 `hasNet=true net=<自身坐标>
  flicker=0 speed=0` ⇒ 排除 flicker 分支。）
  **两处修复**：① 激活分支改「**先 setSpeed、再 detachKinetics**」—— Create 的
  `handleRemoved` 在 `speed==0` 时直接 return，必须先设速度 detach 才生效；
  ② **rpm 落盘**（`KineticBlockEntityMixin` 注入 Create 的 `write`/`read`，仅磁盘）
  ⇒ 重载后 `getGeneratedSpeed()` 立即非 0 ⇒ 容器 tick 的注入退化为「无变化」
  ⇒ **从根上不触发 `propagateNewSource`**。
  文档：`living-water-wheel-tech.md` §9.25（并修正 §9.22.4 被推翻的风险预测）。`551 全绿`。

- 🐛 **修复（回归）：相位快照「只写不读」—— 回填接线在 1a-4 重构中丢失**。
  `ContainerPowerAccess.getOrCreatePowerData`（账本首次创建时从 BE 附件回填锁相状态）
  **零调用者**：`LivingWaxedCopperFunction.tickContainerData` 直接走
  `ctx.getOrCreateContainerData(ContainerPowerData.KEY)` ⇒ 退出重进 / LRU 回收后
  振荡器相位**不再续接**，而 `ContainerPhaseWriteback` 每 tick 仍在写快照（白费）。
  git 考古确认是**回归**而非未完成的功能：`2c78623` 链路完整
  （`LivingWaxedCopperFunction` → `TickContext.getOrCreatePowerData` →
  `SimpleContainerContext.getOrCreatePowerData()` → `ContainerLivingItemHandler.getPowerData(this)`），
  `a52a3ea`（1a-4「容器级数据统一存储」）把调用**改道**绕过回填。
  修：账本创建处改调 `ContainerPowerAccess.getOrCreatePowerData(ctx)`（1 行）。
  文档：`living-power-tech.md` 相位快照节补记 + `ContainerPowerAccess` javadoc 同步。`551 全绿`。
  ✅ **2026-10-09 游戏内实测通过**：振荡器调相 → 退出重进 → 相对相位保留（用户实测）。

- 🏗 **power 收口 步骤 4 第 1 刀：抽出 `LivingWaxedCopperTooltip`**（纯搬迁，`0769957`）。
  `LivingWaxedCopperFunction.addToTooltip`（202 行）+ `renderSection` / `formDescKey` /
  `formTranslationKey` → 新类 `LivingWaxedCopperTooltip`（260 行），主类只留一行委托
  ⇒ **1265 → 995 行（-270）**。保真验证：翻译键逐键 diff，原 body 的 14 个键一个不少。
  顺带清死代码（抽离时发现、已确认全库零调用）：`renderResonanceTooltip` /
  `renderResonanceFormula` 两个死方法 + `addToTooltip` 开头未使用的 `oxidation` 局部变量。
  方案与后续刀序：`buffer/power-refactoring-plan.md` §5。`551 全绿`。

- 🏗 **power 收口 步骤 4 第 2 刀：谓词收进 L1 `WaxedCopperFamily`**（纯搬迁，`1d16160`）。
  6 个纯 `Item` 谓词（`isWaxedBase` / `isWaxedChiseled` / `isWaxedCut` / `isWaxedGrate` /
  `isWaxedBulb` / `getOxidationLevel`）从 `LivingWaxedCopperFunction` 迁到
  `living/util/WaxedCopperFamily` —— **改去 L1 而非方案原定的 power 包内**
  （该类的 javadoc 早已写明迁入条件，且这些谓词已被 `ContainerEnergyStorage` /
  `LivingItemClient` / `LivingWaxedChiseledDecorator` 等 power 域**之外**的类使用）。
  ⚠️ **`getCoilForm` 刻意不迁** —— 它映射 power 域的 `LivingWaxedGeneratorData.FORM_*`，
  迁到 L1 会造 **L1 → L3 反向依赖**。实际 **995 → 945 行**；`check_layers` R1 15 / R3 10
  **无新增违规**。`551 全绿`。

- 🏗 **power 收口 步骤 4 第 3 刀：抽出 `CopperNetworkTopology`**（纯搬迁，`f7296c4`）。
  抽出「与 tick 流程无关的纯图/键运算」（`DIR_ROW/COL` · `pos2dToEdgeDir` ·
  `traversableNeighbor` · `repOf`+`NetworkKey` · `edgeKey` · `FALLING_BIT` ·
  `chiseledInputEdge`）⇒ 主类 **945 → 845 行**（本轮累计 1265 → 845，**-420**）。
  ⚠️ **边界比方案原表更窄**：`runBfs` **留下** —— 它混合「拓扑遍历 + 边信号检测 + 通道事件注入」，
  属 tick 流程；且依赖 `derivedSourceId`（第 4 刀）与 `SignalTracker`（被 `PhaseSnapshot`
  **外部**引用，搬动会连锁改 3 个文件）⇒ 超出「纯搬迁」边界。理由已写进新类 javadoc。
  `check_layers` R1 15 / R3 10 **无新增违规**。`551 全绿`。

- 🏗 **power 收口 步骤 2 第 1 步：抽出 `BulbBank`**（`e23586c`）—— 治的是**真设计病**。
  「按剩余容量比例分配」原先**三份实现、三种口径、没有单一归属地**（历史上同一个 long
  溢出 bug 因此被复制到两处：修完 `receive()` 后 `distributeToBulbs` 里还有一份）。
  本步把「扫铜灯堆 → 收集 → 比例分配 → 取整写入 → 零头回收」收成一处，把三处**故意的
  口径差异**变成**显式参数**（`FePolicy`）—— **参数化差异，不抹平差异**。
  本次只接 `FLOOR_WHOLE_FE`（`ContainerEnergyStorage.receive`，方案称「先动这里最安全」，
  20 个 `WaxedCopperStorageTest` + `RoundTripConservationIT` 守着）；
  ⚠️ 另两种口径**待各自调用方迁移时再加** —— 由真实调用点定义语义，不预先发明。
  硬约束原样保留（单遍 `getStackInSlot` / 无跨调用状态 / 整 FE 量化 / 完整步进保护 /
  「宁损勿造」/ `MAX_LEFTOVER_PASSES`）。`ContainerEnergyStorage` **312 → 210 行**。
  ✅ **破坏性验证**（方案 §6 协议）：临时移除 `deposit` 的整 FE 量化 ⇒ **1 个测试挂**
  （证明调用点真的经过 `BulbBank`，而非新写的死代码），已还原。`551 全绿`。

- 🏗 **power 收口 步骤 2 第 2 步：`distributeToBulbs` 收归 `BulbBank`**（`3578601`）——
  **溢出 bug 的第二份拷贝绝迹**（第一份在 `receive()` 里，第 1 步已收）。
  - `FePolicy` 新增 **`ANY_MOVEMENT`**（不量化 + 不做零头回收）—— 与 `FLOOR_WHOLE_FE`
    的差异**参数化，不抹平**（`ANY_MOVEMENT` 的语义：「够用即可，只关心有没有充进去」）
  - `BulbBank.scan` 的槽位过滤 `IntPredicate` → **`Predicate<ItemStack>`**（原 `slotFilter`
    表达不了「按物品判锈级」）；新增 `scanEntries(List<SlotEntry>, ...)`，两者共用内核 `collect`
  - `distributeToBulbs` 改为委托：锈级专属通道（v18）改由 `itemFilter` 表达；
    返回 `boolean` 改看 `isDirty()`（与原先 `distributed > 0` 等价）。主类 **845 → 824 行**
  - **零行为变化的依据**（逐条核对）：`mulDivFloor` 内部已夹 `a≤c` 且结果夹 `r≤b`
    ⇒ 份额恒 ≤ 该堆剩余 ⇒ 旧代码那次 `Math.min(CAP, ...)` **纯防御、永不生效**（去掉等价）；
    `totalCapacityMilliFe(count) ≡ BULB_UNIT_CAPACITY_MFE * count`（同一常量）；
    `entries` 由 `canApply`（活物品 + 涂蜡铜块）筛过 ⇒ `isBulb` 与原 `isWaxedBulb` 判据等价
  - ✅ **破坏性验证**：临时让 `scanEntries` 返回空 bank ⇒ **5 个测试挂**（证明发电路径
    真经过 `BulbBank`），已还原。`check_layers` R1 15 / R3 10 无新增违规；`551 全绿`。

- 🏗 **power 收口 步骤 2 第 3 步：`BulbItemEnergyStorage` 充电收归 `BulbBank`**（`e7200bd`）——
  物品能量 capability 的充电也走同一份「比例分配」实现（单堆时退化为「全部」）。
  - `BulbBank` 新增 `of(ItemStack)`（单堆视图）+ `distributedMilliFe()`（本次实充 mFE）；
    `deposit` 的局部 `distributed` 提为字段（提前返回路径也重置）
  - **等价性**：单堆时 `share = mulDivFloor(accept, space, space) = accept` ⇒ `perLamp = accept/count`
    与旧代码逐字一致；`space ≤ 0`（满）⇒ 提前返回 0，与旧 `perLamp ≤ 0` 一致
  - ⚠️ **`of()` 刻意不过滤 `isBulb`**：该 capability 在 `LivingItem` 里按**原版物品**注册
    （`Items.WAXED_COPPER_BULB` 等 4 个，**含未活化的灯**），且 `setData`/`getData` 无
    `isLivingItem` 守卫 ⇒ 未活化灯**本来就能被充**；套 `isBulb` 会静默改变该行为。
    「仅已活化」是**容器路径**（`scan`）的判据 —— 两条路径口径本就不同
    （已记为方案 §3「遗留观察」，收紧属独立决策）
  - ✅ **破坏性验证**：临时让 `of()` 返回空 bank ⇒ **3 个测试挂**，已还原。`551 全绿`
  - ❌ **放电（`extractEnergy`）与步骤 4（`ContainerEnergyStorage.extract`）经评估决定不做**：
    单调用方 ⇒ 无重复可消；`extract` 依赖「抽够即停」的提前退出，而 `scan` 必须全扫
    ⇒ 热路径性能退化。**判据沉淀：统一针对「复杂 + 有 bug 史 + 多份拷贝」，
    不为形式上的「全收」而搬** —— 单调用方且逻辑简单的实现搬进新类只是换位置、不产生价值。

- 🐛 **修复：未活化的原版涂蜡铜灯也能被外部 mod 充放电**（⚠️ **行为变化**，2026-10-09）——
  「活化 = 进入能量系统」的门槛被绕过。
  - **根因**：`LivingItem.onRegisterCapabilities` 里**同一个方法内**，活箱子 / 活末影箱的
    provider 都判了 `if (!isLivingItem(stack)) return null;`，**铜灯那处漏了** ⇒
    provider 无条件返回 `BulbItemEnergyStorage` ⇒ 任何涂蜡铜灯（含原版）都被当成「支持能量」。
    是**漏写**，不是 API 限制（provider 拿到的是运行时 stack，可判、可返回 `null`）
  - **修**：准入判据收在 `BulbItemEnergyStorage.of(ItemStack)`（未活化返回 `null`），
    `LivingItem` 只透传（L4 只接线，判据归领域）；类注释改「通用电池」→「**活**涂蜡铜灯…」
  - ⚠️ **一处被推翻的推理**：曾以为「取消活化后电量保留」（依据某测试 DisplayName
    「电量保留但不进出」）。**探针实测推翻**：取消活化 ⇒ `charge 4000 → 0`、组件被移除
    （`clearLivingData` 按各功能的 `getOwnedComponentTypes()` 逐个 `remove`，
    清单含 `LIVING_WAXED_BULB_DATA`）。那条测试是**手动构造**、没走取消活化流程，
    DisplayName 与真实行为相反 —— 已修正措辞
  - ⇒ **收紧零风险**：正常流程下未活化灯**恒为空**（取消活化清电 + 容器路径不充），
    「带电的未活化灯」只可能是该漏洞的产物 ⇒ **不丢任何数据**
  - ✅ 反向验证：去掉守卫 ⇒ 1 个测试挂；新增「未活化 ⇒ 拿不到电池」护栏。
    测试 **551 → 552**；`check_layers` R1 15 / R3 10 无新增违规
  - ✅ **2026-10-09 游戏内实测通过**：未活化的原版涂蜡铜灯放进外部充能槽 ⇒ **充不进**；
    活化后照常能充（用户实测）
  - 📌 **另一处观察 → ✅ 已确认（用户拍板「正常」）**：取消活化会清空电量
    （玩家取消活化满电铜灯 ⇒ 电归零）—— 这是**有意**设计（避免孤儿数据），
    与活箱子「取消活化掉内容」同族语义（离开即结算），**保持现状**

- 🏗 **power 收口 步骤 4 第 4 刀：相位解读 + `SignalTracker` 提升为顶层类**（纯搬迁，2026-10-09）。
  - `PhaseInterpreter` 新类接管 `phaseInterpretation` / `interpretShifter` /
    `interpretSplitter` / `interpretAdder` / `derivedSourceId`；入口调用改为委托。
    相位解读只读波形 / 注册表、写派生草稿，不碰 tick 内事件注入与物品状态 ⇒ 边界可独立。
  - `SignalTracker` 从 `LivingWaxedCopperFunction` 内部类提升为顶层类：被
    `ContainerPowerData`、`PhaseInterpreter`、tick 编排三方共用；原嵌套位置令账本
    反向依赖功能类。`PhaseSnapshot` 继续用同一数据实现。
  - 主类 **824 → 588 行（-236）**。定向测试（`PhaseInterpretationTest` +
    `PhaseSnapshotWarmupTest`）通过；全量 **552 全绿**；`check_layers` R1 15 / R3 10 无新增；
    `doc_check` 10/10。

- 🏗 **power 收口 步骤 4 第 5 刀：记账与遥测抽为 `EnergyAccounting` / `PowerTelemetry`**（纯搬迁，2026-10-09）。
  - `accountEnergy` → `EnergyAccounting`；`buildTelemetry` / `formatMilliFe` /
    `collectDomains` → `PowerTelemetry`；主类 tick 内调用改委托，Tooltip 与单测改指向新归属。
  - 遥测只是状态投影、不改账本；记账仍按原 tick 顺序逐发电机执行、各自 pref 敏感不变。
  - 主类 **588 → 476 行（-112）**。记账 / 共振 / 网络遍历 / 储能相关定向测试通过；全量 **552 全绿**。
    `check_layers` R1 15 / R3 10 无新增；`doc_check` 10/10。

---

## 2026-10-08

> 框架侧（流体侧已于 10-07 完工，此后不再改动）。

- 🏗 **架构分层收尾（一）：三块反向边清零 —— R1 91 → 34**（10-08 深夜完成 ⑤/④/components）：

  - **⑤ `container` 去领域耦合（-22）**：容器内核不再认识任何领域 —— 4 个容器数据 key 归各领域数据类
    （删 `ContainerDataKeys`）；新增契约接口 `api/ContainerDataLifecycle` / `api/FluidPresence`；
    新增生命周期钩子 `ContainerTickHook`（对标 `SnapshotProvider`）；`TickContext` 的跨领域访问改
    **注册制中继**（⚠️ 直接删会**转成 R3 违规**，见方案 §10）；末影刷脏移到 L4 每 tick 收口。
    细化方案（已归档）：[container-domain-decoupling-plan.md](container-domain-decoupling-plan.md)。
  - **④ `network` 拆包（-8）**：5 个领域专用网络包从 `network/`（L4）搬回各自领域（L3）——
    `EnderChannelSyncPacket` / `FluidFlowSyncPacket` / `LivingMapMetadataPacket` /
    `LivingTool{Host,Player}Packet`；顺带新增 `api/LivingMod.ID`（避免搬包后 L3→L4 新增违规）。
  - **components 拆解（-27）**：`LivingComponents` **321 → 78 行**，只剩 4 个框架级注册项、**零 domain import**。
    9 个领域各建 `XxxComponents`（自带 `DeferredRegister`），`LivingItem` 构造阶段挂总线。
    ⚠️ **关键坑**：组件定义**不能**放 `XxxData` 类 —— `DeferredRegister.register` 必须早于
    `RegisterEvent`，而 Data 类的 `<clinit>` 由首次使用触发（可能已太晚，表现为**测试 JVM 启动失败**）
    ⇒ 放 `XxxComponents`。细化方案（已归档）：[components-decoupling-plan.md](components-decoupling-plan.md)。

  验收：`551 tests / 0 failures`；`check_layers.py` R1 **91 → 34**、R3 10；`doc_check` 9/9。

- 🔧 **可复现构建修复：GitHub Actions 从「每次失败」变为可构建**。根因**两个**：
  ① `compileOnly files("libs/sable-*.jar")` 的 jar 在被 gitignore 的 `libs/` 里 ⇒
  干净克隆没有它，javac 找不到 `dev.ryanhcode.sable.*`（`SableIntegration` 12 处报错）；
  ② `gradle.properties` 写死了本机路径 `org.gradle.java.home=G:/...jdk-17.0.4.1` ⇒ CI 上 Gradle 找不到 JDK。

  修法：**Sable 改走 Modrinth Maven**（`maven.modrinth:sable:2.0.3+mc1.21.1`，
  🔴 **不能把 jar 提交进仓库** —— Sable 是 **PolyForm Shield License 1.0.0**，非 OSI、再分发有许可风险）；
  新增 `extractSableCompanion` 任务**从 sable jar 的 `META-INF/jarjar/` 自动抽出 companion**
  （companion 未单独发布到 Modrinth，但它内嵌在 sable jar 里 ⇒ 版本永远一致；companion 本身是 MIT）；
  10 个开发辅助模组 jar 改**条件化声明**（文件存在才进 classpath —— 测试代码对它们 0 引用，CI 缺失无影响）；
  删除 `org.gradle.java.home`。

  🔴 **新增约束**：**第三方模组 jar 一律不进版本控制** —— 要么 maven 坐标，要么运行时条件化。
  **复验命令**（任何改动 `libs/` 依赖后必跑）：`./gradlew build -Plivingitem.devModsDir=nonexistent_dir_ci_sim --rerun-tasks`
  —— 该开关在本地复现「干净克隆」。已实测 `compileJava` ✅ / `test` 551 项 ✅。
  背景与判据见 [framework-benchmark.md](../buffer/framework-benchmark.md) §9.4。

- 🏗 **`runtime` 机制化（档 2）完成：拆掉 `domain/runtime/` 这个「假领域」**（**551 全绿**；
  R3 **22 → 10**、R1 **93 → 91**）。方案与实施记录见
  [runtime-mechanization-plan.md](../buffer/runtime-mechanization-plan.md) §7（计划）/ §8（实现）。

  **问题**：`living/domain/runtime/` 挂名领域，实际是 4 种性质的东西塞一起 —— 其中
  `LivingItemRuntimeData` 是**聚合三个领域运行时数据的 God record**（硬编码
  `generatorTelemetry` / `hopper` / `furnace`），`ContainerRuntimeCache` 还**主动 `new` 网络包**
  （L2 依赖 L4）。⇒ 领域互依赖 R3 有 12 条由它贡献；单纯搬层只会把违规方向反过来。

  **做法**（`A → B → C → D → E` 五步，**B 必须在 C 前**）：
  - **A 建机制**：`living/runtime/` 三件 —— `RuntimeSegmentType<T>`（契约：`id` + `codec`）、
    `RuntimeSegmentRegistry`（`id → 类型`，带 `clearForTest`）、`RuntimeSegments`（不可变聚合容器）。
    刻意用 **`get(RuntimeSegmentType<T>)` 而非 `get(String)`** ⇒ 编译期杜绝「拿错段」的强转。
  - **B 先补安全网**：`LivingItemSyncPacketTest` 12 例**编解码往返**（此前**零覆盖**）。
    ⚠️ 顺序是刻意的 —— 改完 C 之后**断言一字不改仍须全绿**，即为「线上字节语义未变」的机械证明。
  - **C 泛化**：三个段定义**归各领域**（`power/GeneratorSegment`、`hopper/HopperSegment`、
    `furnace/FurnaceSegment`，各自内嵌 `XxxRuntime` record + `STREAM_CODEC`，**字节布局逐位一致**），
    在各自 `*Registration` 登记；`LivingItemSyncPacket` 改为**遍历注册表**编解码（不再认识任何具体字段，
    未登记 id 显式报错）；`LivingItemRuntimeData` **删除**；改 9 处读写点。
  - **D 反转发包**：`ContainerRuntimeCache` 删掉 `flushToClients`，只暴露 `drainDirty()` / `findViewers()`；
    新增 L4 `network/LivingItemRuntimeSync.flush(...)` 在 `LivingItem.onServerTick` 收口发包；
    删 `ContainerLivingItemHandler` 的阶段 4.5；§7.3.1 的**背包空转发包优化已顺带实现**
    （`player_` 前缀分支加 `isJustInventoryMenu` 守卫）。
  - **E 收尾**：三篇文档同步 + 基线收紧 + 本记录。

  **⚠️ 实施中真正踩到的坑（最值得记住）**：`runtime` **不只是「给客户端看的」** ——
  漏斗把 `cooldown`、熔炉把 `progress` **当作跨 tick 瞬态状态**存在这里，
  下一 tick 会**读回**自己写的值（`LivingHopperFunction:68`、`LivingFurnaceFunction:66`）。
  ⇒ 泛化后**服务端本地回读路径必须保留**，否则冷却丢失、熔炉进度重置。
  （另两个小坑：嵌套 record 在 `implements` 子句里必须写限定名 `FurnaceSegment.FurnaceRuntime`；
  测试替身的键不匹配 `chest_x_y_z` 形态 ⇒ 单测里天然「不漏发」，是安全的。）

  **验收**：`551 tests / 0 failures`；`check_layers.py` R1 91 / R3 10（8 条已消除项已 `--update-baseline`）；
  `doc_check.py` 9/9。
  ✅ **游戏内手测五项已通过**（2026-10-08 用户实测：「五项全过，无异常」）——
  发电机遥测 / 漏斗冷却 / 熔炉进度 / **背包路径**（含 D 步新增的 `isJustInventoryMenu` 守卫，
  已确认首帧无可感知延迟 + 创造模式物品栏）/ 回归（关容器再开、跨容器搬运）。
  ⇒ **本改动状态 = 「代码完成、机械验证全过、真机行为已验」⇒ 已验收**。

- 📝 **`syncSlotToClients` 职责边界入档：它不是冗余，别删**（纯文档，零行为变化）：
  起因是 10-08 早先**误报**「流体焚毁/转化/凝固反应漏 sync」并提交修复（`12ff45b`），
  随后被用户实测否决、`git revert`（`1b8bc3a`）。复盘查清原版机制边界：
  - `ServerPlayer.tick()` **每 tick 无条件**调 `containerMenu.broadcastChanges()` →
    `synchronizeSlotToRemote` 判据 `!ItemStack.matches(...)`；
    探针实测 `matches` 对**置空 / 换 id / 数量变化**全部返回 `false`（**能检测**）；
    且活物品 tick（`ServerTickEvent.Pre`）跑在 `broadcastChanges` **之前** ⇒ 原版同拍可见。
  - ⇒ 焚毁 / 转化 / 作物产出 / 熔炉烧炼这类**改 id/数量**的操作**本不需要手动 sync**。
  - `syncSlotToClients` 真正兜的是原版兜不住的三类：
    ① **只改自定义 DataComponent**（`PatchedDataComponentMap.equals` 坑）；
    ② **缓存失效**（`bumpContainerRevision`，原版完全不知道）；
    ③ 登记 `TickContext.dirtySlots` 延迟批处理。
    （运行时数据下发另有通道：`ContainerRuntimeCache.flushToClients`。）
  - 顺带澄清：`SimpleContainerContext.setItem` **不写** `dirtySlots` ⇒ **不会自动 sync**。
  - 落点：`living-item-infrastructure.md` **§2.4.1**（含机制表 + 时序 + 「是否需要手动 sync」四分行 + 误报复盘）。
  - ⇒ **「消灭手动 sync」这个目标就此撤销**（用户：「这东西当时就是解决相关问题才存在的」）。
  - 教训沉淀：断言「某处漏了 sync」前，必须先回答「**那原版广播为什么没兜住**」——
    否则就是把「静态代码里没看到」误当「运行时不会发生」（当日第三次同类误判）。

- 🧹 **红石重算收归单点**（**534 全绿**，纯重构 + 零行为变化）：
  `ContainerRedstoneData.calculate()` 原先被 **10 个类各写一遍**（9 个红石元件 + 活 TNT），
  靠 `processedThisTick` 幂等短路兜底才没算重。收归为**单点驱动**：
  - `LivingRedstoneFunction` 改**自维持**（`shouldTickWithoutOwnItems` 恒真，对称流体侧 `LivingFluidFunction`）
    + 加**廉价守卫**（`peek(REDSTONE) == null && !hasRedstoneElements(tick)` ⇒ 零开销跳过）——
    守卫不可省：自维持 = 每个被 tick 的容器都会走到这里，无守卫则纯熔炉容器也会每 tick 跑 `calculate`
    （含 `notifyBoundaryChange` → `level.updateNeighborsAt`）。
    ⭐ 守卫**不会漏掉消费者**：`TickContext.getSensor` 就是 `getOrCreateRedstoneData` ⇒ 消费者一读就创建账本。
  - 删 9 处重复样板（8 个 redstone 元件 + `LivingTntFunction`）⇒ **`HasContainerData` 实现者 13 → 4**
    （流体 / 水车 / 红石 / 电力）。接口语义回归「谁真正拥有容器级数据」。
  - 抽 `ContainerRedstoneData.hasRedstoneElements(TickContext)` 供守卫与 `calculate` 共用判据。
  - 删两个**永不执行**的框架兜底：`zeroResidualRedstone`（自维持后守卫恒真）与
    `handleEmptyContainer`（自 1b-2b 起 `grouped` 恒非空 ⇒ 早已死代码）；归零职责由守卫分支接管。
  - **为什么重要**：那 10 处是「**错误样板的产地**」—— 活 TNT 就是照抄周围红石元件来的，
    连注释一起抄错（原注释称「优先级 1 确保红石数据在 TNT tick 之前计算完毕」，
    但 `tick()` 在阶段 2、`tickContainerData()` 在阶段 4 ⇒ 该「之前」对读取时机毫无帮助）。
    方案与终点形态见 [redstone-driver-consolidation-plan.md](redstone-driver-consolidation-plan.md)。
  - 同步文档：`living-redstone-tech.md`（§3.1 触发时机 / §4.4 / §8.1 / §8.2 / §8.4）、
    `living-tnt-tech.md` §3.3、`living-power-tech.md` §1.3、`living-copper-tech.md` §3.1、
    `红电系统.md` 架构图 + prio 说明、`living-item-infrastructure.md` 优先级表、`tooltip-system.md` §3.1。
- ✅ **红石「驱动链路」集成测试（5 项）—— 上线即抓出「零开销」失效的真因**（**538 全绿**）：
  新增 `ContainerRedstoneIntegrationTest`（走真实 `processContext`），覆盖：自维持注册 /
  守卫放行消费者（只有活 TNT 时账本被创建**且** `calculate` 跑过）/ **守卫拦截无关容器** /
  残留归零 / prio 顺序（红石 2 < 电力 3）。
  - ⚠️ **为什么需要**：`ContainerRedstoneDataTest`（754 行 / 20+ 例：传播 / 衰减 / 中继器 /
    比较器 / 火把反相环振荡…）覆盖的是**引擎内部**，它全部**直接调 `calculate()`** ⇒
    **绕过了驱动链路** —— 而本次重构改的恰恰是这一层。
    （📌 **更正**：早先「红石层没有容器内元件互联的集成测试」的说法**是错的**。）
  - ⭐ **测试首次运行失败 1 例**（`unrelatedContainer_neverCreatesRedstoneData`）⇒ 查出：
    `SimpleContainerContext.setTickContext()` **无条件调 `getOrCreateRedstoneData()`**
    ⇒ 红石账本每 tick 都被创建 ⇒ 守卫的 `peek == null` **恒假** ⇒
    「无关容器零开销」**从未生效**（方案 §7 当时那条论断是**假的**）。
    修复：`setTickContext` 改用 `peekContainerData`（**不创建**）。安全性依据：新建账本的
    `processedThisTick` 在构造器里默认 `false` ⇒ 首次无需 reset；此后账本已存在 ⇒ 照常 reset
    （正是历史上「中继器不熄灭」的根因修复点）。
  - ⇒ **实证价值**：补测试把「零开销」从**论证**变成**断言**，并立刻证明原论证有误 ——
    这是「防线②：写了就红」的又一个实例。
  - 「残留红石归零」的端到端测试从 `ContainerFluidIntegrationTest` **搬入**本类
    （它测的是红石，住在流体测试类里属位置错误；且 1b-2c 的 `zeroResidualRedstone` 已删，
    归零职责改由 `LivingRedstoneFunction` 的守卫接管）。
- 🏗 **架构分层第五步：反向边 ×2 下移（`redstone → hopper` / `redstone → power`）**（**538 全绿**，纯重构）：
  `ContainerRedstoneData` 曾同时依赖 hopper 域与 power 域 —— 但依赖的东西**都不是那两个域的实现**，
  而是**放错盒子的共享知识**。两处各建一个公共类：
  - **几何映射** → 新建 `living/model/GridDirections`（**L0 契约层**，与 `Pos2D` 同包）：
    搬入 `gridToWorld` / `worldToGrid` / `getBlockFacing`（纯几何，只依赖 `Pos2D` + 原版）。
    使用者除 redstone 外还有 **`BlockStateBaseMixin`**（L4）⇒ 留在 hopper 域等于制造
    「mixin → hopper」的反向依赖。`CrossContainerTransfer` 内部 5 处调用改为 `GridDirections.*`，
    并删掉随之无用的 `BlockStateProperties` / `DirectionProperty` import。
  - **涂蜡铜块家族谓词** → 新建 `living/util/WaxedCopperFamily`（**L1 基础层**）：
    搬入 `isWaxedCopperBlock`（硬编码 20 个 `Items`，零域依赖）。使用者除 redstone 外还有
    `LivingItemClient`（L5）⇒ 留在 power 域等于制造「redstone → power」。
    ⚠️ **家族分类尚未完全收拢**：`isWaxedBase` / `isWaxedChiseled` / `isWaxedCut` / `isWaxedGrate` /
    `isWaxedBulb` / `getCoilForm` 等**子集判定**仍留在 `LivingWaxedCopperFunction`（目前只被 power 用），
    将来别的域需要时应一并迁入。
  - **结果**：**R3 24 → 22**（两条边各减 1）· R1 93 不变 · 基线已收紧；
    顺带补 `file-map.md` 的两处文件树条目。
- 🧹 **`doc_check` 第 9 项清完 13 处陈旧引用，并升回失败级**（**9/9 全绿、0 警告**）：
  上线首日以 warning 级试运行（报出 13 处、跨 8 个文档，超出当日任务范围）。清理分两类：
  - **真漂移（改成实际名）**：
    `CrossContainerTransfer.getNeighborContainer` → **`ContainerContext.getContainer`**（3 处）；
    `ExplosionComponent.tickAll` + `SuperExplosionTask` → **`ExplosionLedger.flushAll`** + `schedule`/`onChunkLoaded`
    （超级爆炸分帧机制已换；且「每 tick 消除**一个**整区块」实为**每 tick 32 个区块**）；
    `ServerPacketHandler.handleLoad` / `sendLivingChestContents` → **`handleLivingChestAccess`**
    （⚠️ 顺带发现 **LOAD 操作已不存在**、`LivingChestContentsCache` 类亦已删除 ⇒ 该流程图整体过时，已标注）；
    `ContainerLivingItemHandler.cleanupStaleRedstoneData` → `cleanupStaleData`。
  - **旧名 / 规划中（标注豁免）**：`LivingEnderChestAccessor.getChannel`、
    `LivingHopperFunction.buildFilterChain`、`ContainerChunkCache.getCachedChunks`
    （均为迁移对照表里的旧名 ⇒ 标注「已废弃」）；`PinyinHelper.extendDictionary` / `resetToDefault`
    （**规划中、尚未实现** ⇒ 标注）。
  - 顺带删掉 `AGENTS.md` 架构图里的 `LivingWaterBucketFunction → LivingWaterBucketData`
    （类与 Data 均已不存在）。
  - ⚠️ **踩坑记录**：豁免是**行级**的 —— 说明文字必须与 `Class.method` 在**同一行**，否则检查照报。
- 🧹 **`doc_check` 新增第 9 项「方法名真实性」**（首轮为**警告级**）：
  文档里 `ClassName.method(...)` 引用的方法必须存在于源码 —— 堵住第 7 项「只校验类名、不校验方法名」的盲区。
  上线即报出 **13 处陈旧引用**（跨 8 个文档，含拼音搜索 / 配方书等非红电领域）⇒
  清理完后应把 `warnings.append` 改回 `failures.append`。

- 🏗 **架构分层第四步（C 收尾）：`RedstoneSensor` 接口上移到契约层**（**534 全绿**，纯重构）：
  该端口（v19.1 架构演进 ②）**已经是接口**，但**住在 `domain/redstone/`** ⇒
  消费者（`power` / `hopper` / `tnt`）**用端口仍要 import 领域**，模块级依赖并未真正切断。
  改为：**接口迁 `living/api/`** + **方向常量（`EDGE_*` / `DIRECTIONS`）收归契约层**
  （`ContainerRedstoneData` 的公开常量改为**别名**，内部实现不动）。
  ⇒ `power` / `hopper` **已完全不引用** redstone 域：
  `LivingWaxedCopperFunction` 11 处常量引用改走契约层 + 去 import；
  `LivingHopperFunction` 的 `ContainerRedstoneData` **是未使用的 import**（正文零引用，已删）。
  **R3 领域互依赖 28 → 25**，顺带 **R1 94 → 93**（`TickContext.getSensor` 返回类型改契约层 ⇒ 少一条 `container → redstone`）。
  ⚠️ **实测比预想少 3 条**，因为另两条边**不属于本项**（详见 `buffer/architecture-layering-plan.md` §4）：
  ① `tnt → redstone`：`LivingTntFunction` 调的是 `ContainerRedstoneData.calculate` ——
  **信号层内部方法、不在端口上**，且它挂在 `tickContainerData` 上**每 tick 强制重算** ⇒ **设计问题，未动**；
  ② `redstone → hopper` / `redstone → power` 是**反向边**（redstone 调 hopper 的纯几何工具 /
  power 的涂蜡谓词）⇒ 属「共享契约下移」，独立项。

- 🏗 **架构分层第三步：`LivingComponents` 迁出 `living/transfer`**（**534 全绿**，纯重构）：
  `living/transfer/` 的 12 个文件里只有它是异类（其余全是槽位 / 传输相关），
  而 `living/components/` **已存在且名字正好对上** ⇒ `transfer/LivingComponents.java` → `components/`。
  **48 个 import** 一次改完 + 补 `FilterData` 的**同包引用**（它原先在 `transfer` 包里无需 import，
  **正是 ① 踩过的同一个坑**）+ 修 `transfer/package-info.java` 的失效 javadoc 链接
  + 三处文档路径（`framework-benchmark` / `api-contract` / `file-map`）。
  ⚠️ **本步不减违规计数**：`transfer` 与 `components` **同层**（L2）⇒ `components → domain` 仍是 27 条 R1 违规。
  它买的是**归属诚实** —— 原先"`transfer` 认识 11 个领域"是**假象**（真正认识领域的是 `LivingComponents` 一个类）。
  `R1` 仍 **94 条**，但违规**配对数 34 → 37**（同一批边换了模块标签）。

- 🏗 **架构分层第二步：static 缓存登记点归属修正**（**534 全绿**，纯重构）：
  `StaticCacheRegistry` 原先**自己登记了 7 项领域缓存** ⇒ 该类（L1 基础）必须 import
  `ender` / `runtime` / `tnt` / `tools` / `water` 五个领域（L3），**构成 7 条跨层反向依赖**。
  改为 —— **框架自己的 2 项留在注册表**（`ContainerChunkCache` / `ContainerLivingItemHandler`），
  **7 项领域缓存搬进各自的 `XxxRegistration.register()`**：
  `EnderRegistration` 1 / `WaterRegistration` 2（含客户端 1）/ `TntRegistration` 1 /
  `ToolRegistration` 2 / **新建 `RuntimeRegistration`** 1（`runtime` 域原先**没有** Registration）。
  **`StaticCacheRegistry` 现在只认识框架自己**（`grep domain.` = 0）。
  ⇒ **R1 101 → 94**，基线 39 → 34 对。**铁律正文（infra §3.4）按「归属」重写**：
  「框架缓存 → 注册表；**领域缓存 → 该领域的 Registration**」。
  📌 判据可复算：`python tools/check_layers.py`。

- 🧹 **入口清淤：两处「流水」搬离入口**（**文档系统改动，代码未动**）：
  ① **模块地图尾部 62 行测试流水**（2026-09-22 → 10-07 逐次「新增 XxxTest N 项」）——
  按 §4.0「changelog 是流水不是档案」**本不该在入口**。
  ⚠️ 守恒校验发现其中 **`2026-09-27` 是 changelog 的唯一空缺**（changelog 从 09-28 直接跳到 09-25，
  工具侧日志也没有）⇒ **先补录该节再清理**，否则永久丢失（正是本文件头部那条断档教训的又一实例）。
  ② **开发进展 10 条回归「一行结论 + 指针」体例**（原平均 362 字符/条；当年 `D-doc-04` 的起因就是 705）。
  **入口 18171 → 11935 字符（余量 1829 → 8065）**。
  ⚠️ **核心认识：瘦身只「清淤」，不改「排污」** —— 上次清掉的子系统索引**没被回填（+0 ✓）**，
  但流水从模块地图与进展表漏了回来。**只要「流水往入口塞」的习惯在，清完还会满。**

## 2026-10-07

- 🧹 **活桶 tooltip 定稿：删行 + 活物品段标题惰性化**（**533 全绿**）：用户实测发现活水桶 tooltip 里
  漏出字面量 `储存: %1$s × %2$s mB`。根因三层：① lang 键`tooltip.livingitem.bucket.content`
  有**两个**占位符，而 `LivingBucketFunction.addToTooltip` 只传**一个**参数；② 原版
  `TranslatableContents.decompose` 取不到第 2 个参数时抛 `TranslatableFormatException`，
  兜底是 `ImmutableList.of(FormattedText.of(s))` ⇒ **整条模板当纯文本渲染**（所以两个占位符一起漏，
  而不是只漏一个）；③ 来历 = `e2d7aa1`（FluidStack 时代）当时传 2 个参数（名字 × 量），
  `getBucketFluid` 改回返回 `Fluid` 后参数减到 1 个、**lang 没同步** ⇒ 从那批起一直是坏的。
  全仓审计（一次性脚本扫「字面量 key 调用点 × zh/en 占位符数」）：**参数不足仅此 1 处**、
  zh/en 占位符数不一致 0 处、参数多余 145 处**无害**（原版忽略多余参数 —— 这也是只有这一处出问题的原因）。
  定稿（用户拍板「**其实，没有可以显示的信息在tooltip 上的**」）：**删掉该行 + 删 lang 键**
  （形态本身就是信息 —— 水桶 / 岩浆桶 / 空桶；「储存 × mB」对恒 1 个源的桶是废话），
  已否的 B 档（补第二个参数显示 `× 1000 mB`）不做。
  配套：活物品段标题**保留**（用户二次拍板「标题不要删，标题是正常的」——
  「`--- 活物品 ---`」是**「这是活物品」标记**而非内容行，零内容行时照旧输出）。
  🔴 我曾实现「零行 ⇒ 整段不输出」的**惰性标题**（怕留空标题）并**被否**：那会把标记一起丢掉。
  活桶 tooltip 定稿后就是「空行 + `--- 活物品 ---` + 无内容行」。
  保留的改动只有「渲染体抽成可测静态方法 `renderSection(functions, …)`」（行为不变，
  但去掉对全局注册表的依赖，测试可喂 stub）。
  测试 +6：`LivingItemTooltipTest` 5 项（含**真实活桶 ⇒ 只有标题没有内容行**这条合起来的表现）
  + `LivingBucketFunctionTest` 1 项（活桶零内容行）。**534 全绿**。
  方案留痕 `docs/tech/living-fluid-tech.md §5`（含顺手发现的死 lang 键
  `tooltip.livingitem.water_bucket.status` / `.flow`，待实测收口清理）。

- ✅ **玩家路径落盘守卫补齐**（**528 全绿**）：玩家容器（背包 / 末影箱）没有 BE 可挂，只能落
  `CONTAINER_FLUID_DATA_PLAYER`（`Map<容器键, 数据>`，一个玩家两个键）—— 这层此前**零用例**。
  用户 2026-10-07 实测确认 happy path（源重进都在），自动化补的是**反方向**：
  「源被汲走/挤没 ⇒ 数据变空 ⇒ map 条目被移除 ⇒ **重进不复活**」——
  同层历史事故（2026-10-04 BE 侧「源复活」）至今没有玩家侧守卫；
  另加「两容器键互不干扰（清末影箱不碰背包）」与「重进从附件回填（正向持久化生效）」。
  测试：`PlayerFluidDataPersistenceTest` 4 项（放在 `container` 包 —— `EnderChestContainerContext`
  构造器是包级私有；用 `clearAllCaches()` 模拟「重进存档」清空静态缓存）。
  文档：`living-fluid-tech.md` §8 落盘表补守卫说明。
- ✅ **性能收尾：四处零行为变化短路 + 量测基线**（**524 全绿**）：先把"1 万容器 × 100 玩家"当**探针**
  找出与规模成正比的四处（空容器仍走渲染轨 / 无人看也全量建包 / dispatch 遍历全部玩家 /
  `getNeighbors` 每次分配），再**回到真实条件**排序并动手：① `flushAfterTick` 入口对
  "无数据且从未下发过"短路；② 先 `viewersOf(...)` 收集查看者，**空则连包都不建**
  （`dispatch` 改为复用它，末影箱判 viewer 逻辑原样保留）；③ 未开菜单玩家 O(1) 跳过；
  ④ 新增 `ContainerContext.fillNeighbors(int[] out, ...)`，四个热点（扩散 / 生长 / 前沿反应 /
  晋升邻源计数）改为**每相位一个复用缓冲**（每容器每拍 1 次分配而非每格 1 次）——
  ⚠️ 不用全局共享缓冲（晋升会嵌套调用邻源计数 ⇒ 别名 bug）。
  🔴 **量测结果与预估相反（如实记录）**：200 容器 × 54 格 × 200 拍 ⇒ 每容器每拍
  **14.42 µs → 12.36 µs（−14%）**；空容器 28 ns → 26 ns。**分配不是瓶颈**，
  剩余开销的大头是"每拍全量重算"（Dijkstra + 集合分配）——
  **"1 万有流体容器"外推 124 ms/拍确实撑不住，但真实规模（几百个）≈ 2~3 ms/拍 足够** ⇒
  **结构性的脏标记/节流不做**（它也是"移除即时/拓扑即时"的不变量地基）。
  ⚠️ 方法论收获：我曾按"分配是瓶颈"拍板做四处改动，**若不量就会把"~200× 减少分配"当成收益
  写进 changelog** ⇒ 「先量后改」的规矩救了这一批。
  测试：新增 `ContainerFluidPerfTest`（量测 + 病态回归宽松阈值 + 落盘 `build/_perf.txt`）、
  `ContainerNeighborsTest`（`fillNeighbors` 与 `getNeighbors` 逐格一致 / 单行 / 复用不污染）。
  方案留痕 `docs/tech/living-fluid-tech.md §11`。
- 🔧 **目标层抢占从「距离」改为「到达时间」（514 全绿）**：起因是"刷石机接触格像一堵墙" ——
  那格明明在活水源的流域内、水到得更快，却永远不进水。根因：目标层按**距离 + 入队顺序**分配，
  **与该流体实际多久才流到完全无关**（统一时钟只让*蔓延*走节拍，*分配*仍用距离）
  ⇒ 分配与实际到达脱节 ⇒ 出现「看不见的墙」；再加本批次早些时候的「不驱逐」守卫
  （岩浆已铺开的格水永远 claim 不到）把墙固化。
  定档：多源 Dijkstra 按**到达时间**抢占 —— 每格成本 = 该流体自己的节拍（水 5 / 岩浆 30，
  瞬时 0），源格永不被蔓延抢占，实际层被异种占据的格仍不抢 ⇒ **分配与到达用同一个时钟**。
  🔴 **几何结论**（实测，非推演）：岩浆守得住的格数 ≈ 两源间距的 1/7（`d_岩浆 ≤ d_水/6`）
  ⇒ 两源太近（曼哈顿 ≲6）时岩浆守不住前沿 ⇒ 水贴到岩浆源 ⇒ **黑曜石 + 源湮灭**（刷石机自毁）；
  **D ≥ 7 则岩浆源存活、圆石在接触格照常累加**。这与原版是同一条约束（原版刷石机也必须让水
  够不到岩浆源），门槛从「水的 maxLevel」变成「时间竞争」。
  实测数据（27 格容器 / 生产节拍）：D=3 ⇒ 源变黑曜石；D=7 ⇒ 源存活、t=200 时接触格圆石 x7；
  9 格单行 D=8 同样成立（源存活 + 圆石 x7）。
  ⚠️ 我此前的两处错误（一并留痕）：① 断言"A 会毁掉刷石机" —— 只在近距离几何成立，
  我把它当成了普遍结论（只在 9 格单行 D=3~4 一个场景里推演）；② 断言"原版稳定必须靠产物挡路"
  —— 那是解释"挖掉后岩浆回流再凝固"的，与"刷石机为什么不毁"无关。
  **教训：对齐原版要"成组搬"；推演的结论必须实测。**
  测试：㉝ 改为生产节拍 + D=8 可用几何（源存活 + 圆石累加 + 水占满它的流域）、
  ㊶ 改为"先达稳态再喂满组圆石"才出黑曜石、新增「两源太近 ⇒ 岩浆源变黑曜石」守卫；
  新增测试帮手 `registerProductionCadence()`（**基线水是瞬时 ⇒ 到达时间恒 0 ⇒ 会赢走所有争用格**，
  跨流体几何必须用生产节拍，否则看到的是退化几何）。
  方案留痕 `docs/tech/living-fluid-tech.md §3.1`。
- 🔧 **收尾审查批次（519 全绿）** —— 六维度审查（契约接缝 / 相位 / 时钟 / IO / 落盘 / 性能）
  后的修复，方案留痕 `docs/tech/living-fluid-tech.md §3.2`：
  ① 🔴 **晋升邻源计数不分流体**（真 bug）：`countSourceNeighbors` 只看 `isSource` ⇒
  岩浆源会被算进水的「≥2 邻源」⇒ 一格流动水夹在两个岩浆源之间**错误晋升成水源**；
  加 `fe.fluid() == fluid` 过滤。② 删 `exportFlowData()`（全仓库零调用）。
  ③ `FluidFlowServerSync.onContainerClose` 的 `key==null` / `未同步过` 两处 `return` ⇒
  改 `continue`（否则多容器菜单漏清）。④ `LivingBucketInteractSupport.representativeFluid`
  与 `ContainerFluidData` 重复实现且用 ConcurrentHashMap 可能 `put(null)` ⇒ **NPE**；
  统一委托 `representativeFluidOf`。⑤ `ContainerFluidHandler.isFluidValid` 忽略 `tank`
  ⇒ 改为按 tank 回答（NeoForge 契约）。⑥ 客户端 javadoc 修正（已删除的 `get()`）。
  ⚠️ **口径更正**：三种"改源"路径写入层级不同 —— 晋升 / 焚毁 `SPAWN_SOURCE` 直接写实际层
  （**当拍**可见），倒桶 / 汲走 / 管道抽取只写 `generatedSources`（**下一拍**可见）。
  **测试**（+5）：晋升只数同流体邻源、到达时间竞争「慢者让位」、末影箱派发**必须判 viewer**
  （第三次泄漏修复的**接线**守卫，此前只有判据用例没有接线用例）、`renderTargetOf` 两类、
  非末影箱不受 viewer 门影响。
  审查同时确认**无问题**：契约 9 方法全部真被调用、相位读层全对、CODEC 与 `lastAdvance`
  生命周期正确、Dijkstra 无退化输入、"不驱逐"守卫未失效、客户端预判↔服务端重验一致。
- 🔧 **消除「异种流体被当拍驱逐 ⇒ 接触前空档」**（**513 全绿**）：
  现象 = 活熔岩倒在活水流旁 ⇒ 中间突然空一格，约 1.5s 后变圆石。根因是三条规则叠加：
  ① 目标层混合 BFS **按距离抢占**（新源会抢下它到对方水源之间那格）；② `pruneActual`
  **当拍删**被换掉的流体；③ 非源格蔓延**按该流体节拍**（岩浆 30t/格）⇒ 目标层已判给岩浆、
  实际层还没到 ⇒ 空档。**定档：目标层 BFS 跳过「实际层已被异种流体占据」的格** ⇒ 熔岩前沿
  停在接触面、逐格凝固，**从不驱逐对方**（对齐原版 `shouldSpreadLiquid` 的接触面反应）。
  ⚠️ 代价：目标层不再是纯 BFS 纯函数（要读实际层）—— 换来 `pruneActual` 只剩「目标消失」
  一个职责（异种驱逐的决策权挪进 BFS），**概念上变轻**。用户拍板理由：手感优先，
  「水凭空消失一格」像 bug。**播种仍然覆盖**（倒桶是玩家显式行为：倒进异种流动格 ⇒ 覆盖 ⇒
  该格成岩浆源 ⇒ 紧邻水 ⇒ 源格反应出**黑曜石** + 源湮灭）。
  过程中踩到一次**测试自身的设计错误**：先以为「水会停在 slot 4」，实测发现基线水是**瞬时**
  （speed 0）且上限 7 ⇒ 一拍铺满 0..7，倒的 slot 6 其实是**流动水格** ⇒ 走覆盖分支出黑曜石。
  改用「水的 maxLevel 限 4」构造真正的空档场景（记下来：**基线水瞬时，别用「跑 N 拍」假设
  它只流了 N 格**）。
  测试：新增 ㊾ 不驱逐 + 接触面圆石（水全程不动）/ ㊿ 倒进异种流动格 ⇒ 黑曜石 + 源湮灭 + 水灌回。
  方案留痕 `docs/tech/living-fluid-tech.md §3.1`。
## 2026-10-06

- 📉 **入口瘦身：子系统索引改「族级路由 + 子系统直链」**（**文档系统改动，代码未动**）：
  入口 **18116 → 15785 字符**（余量 1884 → **4215**）；索引段 **3957 → 1626 字符**（27 行 → 6 行）。
  27 行的**完整概述**（每个子系统做什么）降级到 `docs/reference/subsystem-index.md`；
  入口只留 6 个族 + 族内「**子系统名 = 直链**」列表。
  🔑 **判据是路由，不是字符数**：实测 **守恒 27/27 · 路由直链 26/26**（每个子系统名仍可一步点到文档）。
  顺手删掉一行指向 `docs/archive/` 的（**归档层不进加载路径**）。
  ⚠️ 实施中被校验抓到两处：① 守恒判据必须先**归一化链接路径**（否则把"必要的适配"误判成"内容丢失"）；
  ② **路由键不能顺手改短**（把 `活涂蜡铜块（红电发电）` 缩成 `活涂蜡铜块` ⇒ 校验直接抓出）。
  规则已并入 `docs/README.md` §5；方案与实施记录见 `docs/archive/entry-index-slimming.md`。
  ⚠️ 初稿曾把 `wc -c` 的**字节数**当字符数（5933 vs 真实 3957），**诊断整个反了** ——
  本项目一切体量口径都是**字符**（`doc_check` 用 `len()`）。

- 🏗 **架构分层第一步：`interaction` 的 9 个领域专用 handler 归位**（**484 测试全绿**，纯重构）：
  把 `living/interaction/` 里 9 个「某领域专用」的 handler 搬进各自领域 ——
  `BonemealHandler` / `PlantCropHandler` / `TillToFarmlandHandler` → `domain/farmland`；
  `ButtonPressHandler` / `LeverToggleHandler` / `ComparatorToggleHandler` / `RepeaterCycleHandler` → `domain/redstone`；
  `IgniteHandler` / `IgniteCarriedHandler` → `domain/tnt`。
  ⇒ **`living/interaction/` 只剩通用机制**（Entry / Handler / Predicates / Registry / RuleConfig）；
  **`InteractionRegistry` 一行未改** —— 注册调用本来就在各 `XxxRegistration` 里（R4 早已满足）。
  **分层违规 R1 113 → 101**（`interaction → farmland/redstone/tnt` 三对清零），
  基线随之收紧 **42 对 → 39 对**。判据可复算：`python tools/check_layers.py`。
  方案与后续批次见 `buffer/architecture-layering-plan.md` ①。
  ⚠️ **踩坑**：按 `import` 搜引用会漏「**同包引用**」—— `TillToFarmlandCompatTest` 与 handler 同包、
  **没有 import**，编译才发现（`InteractionRegistryTest` 同理，靠 `PlantCropHandler.canPlantWith` 静态引用暴露）。

- ✅ **实测三连修 + 转化消耗源**（**475 测试全绿**）：
  ① **末影箱渲染泄漏到玩家物品栏**——客户端缓存只有两槽位，末影箱同步键
  （`player_<uuid>_ender_chest`）与背包键（`player_<uuid>`）同前缀 ⇒ 撞进背包快照。
  修：`FluidFlowClientCache` 三分路由（背包/末影箱/BE 容器），渲染与客户端汲/倒判定
  按容器类型取快照（`PlayerEnderChestContainer` instanceof）；
  ② **创造模式汲水光标不变身**——汲水走了 `createFilledResult` 的创造分支
  （原桶保留 + 水桶进背包，原版桶语义）⇒ 改 GUI 语义：创造模式光标直接变身活水桶、
  倒水不消耗（对齐原版无限桶）；③ **转化消耗源**（用户拍板：否则一格水 = 无限水桶）——
  转化表条目加 `consumeSource` 标记（空桶→水桶 true，混凝土 false 催化剂语义）+
  契约 `consumesSourceOnTransform` default + 引擎转化成功后移除派生源；
  三连源场景晋升再生中间源 ⇒ 自动化水桶农场闭环成立（无限性来自三连源而非免费转化）。
  🔴 **调试中发现的基建坑**：`SimpleContainerContext.setItem` 的实现是
  「extractItem 抽干槽位 → insertItem 插入新栈」——**调用方持有的旧栈活引用会被抽干成空栈**
  ⇒ 转化消耗检查（在 setItem 之后读 item）恒 false。修：消耗判定移到 setItem 之前
  （活引用语义的坑：任何「setItem 后再读旧引用」的代码都中招）。
- ✅ **活熔岩接入（第三条流体，`docs/tech/living-fluid-tech.md` §3.7）**（**479 测试全绿**）：
  继水之后第二条会流动流体，**引擎零改动** —— 全部落流体侧（`WaterRegistration` 注册一个行为 +
  转化表 JSON 一行）。参数全部**派生自原版**：`maxLevel 3`（dropOff 2）、`flowSpeed -1` ⇒ 30t/格（
  下界 10）、`shouldPromote` 默认 false（原版岩浆仅下界可繁殖）。两条新机制：
  ① **焚毁（`incinerateFlowItems`）** —— 流动熔岩格上的非活物品三分支：满组石头系 ⇒
  `SPAWN_SOURCE`（消耗物品、该格诞生活熔岩源，**新配方**：喂养熔岩）、防火物品（`FIRE_RESISTANT`
  组件，下界合金系）⇒ `SURVIVE` 共存、其余 `BURN`；**源格不焚毁**（源格 = 转化台，产物要存活到
  被抽走）；新生源**当拍即可用**（源即时：直接改写实际层流表）。② **前沿反应 / 刷石机
  （`reactFrontiers`）** —— 熔岩格四邻有水 ⇒ 产物圆石落格、该格熔岩退去、记入**凝固墙**
  `solidified`（两侧 BFS 都不再进入，对齐原版「产物是实体方块」）；挖走产物 ⇒ 下一拍解封 ⇒
  重新涌入/反应 = 刷石机再生；产物放不下 ⇒ 不反应不固墙（不丢物品）。
  转化表加 `lava_empty_bucket`（空桶→岩浆桶，`consumeSource: true`）：**岩浆永不晋升 ⇒
  消耗即耗尽**，岩浆是矿脉型一次性资源而非农场（与水的三连源农场闭环对偶）。
  测试新增 ㉝（刷石机前沿 + 固墙）/ ㉞（满组石头→源诞生）/ ㉟（部分堆焚毁不生源）/
  ㊱（防火物品存活）。⚠️ 两个测试自身缺陷（**顺带修**）：㉞ 漏注册熔岩行为（默认静止 ⇒
  不蔓延不焚毁）、㊱ 拿**附魔金苹果**当防火样本（原版它只有 rarity/food/glint 组件，
  **不防火** ⇒ 换下界合金锭）。
- 🔧 **活熔岩口径更正（用户对齐，同日第二批次，481 测试全绿）** —— 上一批按代码现状写的口径有四条是错的：
  ① **源格同样焚毁**（原写「源格 = 转化台不焚毁」：那是**水**的机制三，岩浆不转化，
  所以岩浆源格上的物品只有焚毁一条路；满组石头落在已有源上 = 只消耗石头，源保留）；
  ② **凝固产物格不是墙**（删掉整套 `solidified`：字段 / BFS 阻挡 / 解封检查）——
  岩浆下一轮重新流入同一格再反应 ⇒ **圆石持续累加**，刷石机自动产出，不需要玩家挖走触发；
  ③ **相位顺序改为「反应（机制五）先于焚毁（机制一）」** —— 否则岩浆一流入产物格就先把
  不满组的圆石烧掉，数量永远停在 1（实测踩到才发现的隐藏冲突）；
  ④ **岩浆不转化**：删掉 `lava_empty_bucket` 转化表条目（岩浆行为**没覆写** `transformItem`
  ⇒ 那条 JSON 是死配置，只有单测直接查表才命中）；非活空桶放岩浆源格 = **被焚毁**，
  不是变岩浆桶——活岩浆桶只从世界汲取 / GUI 汲获得。
  ⚠️ 由此浮现的一个后果（**待观察，未拍板**）：产物满组 64 后不再反应、岩浆留在格内 ⇒
  该格走机制一，满组圆石属石头系 ⇒ 被消耗并**诞生岩浆源**（刷石机满仓自动转矿脉）。
  测试新增 ㊲（源格也焚毁）/ ㊳（岩浆不转化），㉝ 改钉「产物格不是墙 + 圆石累加」。
- ✅ **黑曜石循环（活熔岩第三批，484 测试全绿）** —— 补齐上一批浮现后果里缺的一环：
  **源格与流动格的产物分开**。契约新增 default `frontierSourceReaction(neighbor)`
  （**默认回退** `frontierReaction` ⇒ 不区分源/流动的流体零变化），引擎 `reactFrontiers`
  按本格是否源格分别询问；岩浆注册：**源 + 水 ⇒ 黑曜石**（源湮灭），**流动 + 水 ⇒ 圆石**
  （对齐原版 `shouldSpreadLiquid`，与「谁撞谁」无关）。至此循环闭合：
  `圆石累加 → 满 64 → 岩浆源（机制一 SPAWN_SOURCE）→ 黑曜石 → 被岩浆焚毁 → 回到圆石`。
  黑曜石 ∉ 石头系、不防火 ⇒ 走既有 `BURN`，无需特判；**存活窗口 = 岩浆流入该格的节拍
  （30t ≈ 1.5s）**，要拿黑曜石得抢在这 1.5s 内抽走（实测项，未调参）。
  本次**只做蔓延路径**；倒水路径（`pour` ⇒ 黑曜石）另开。
  测试新增 ㊴ 源格 ⇒ 黑曜石 / ㊵ 黑曜石被焚毁 / ㊶ 端到端循环 / ㊷ 契约默认回退。
- 🔧 **取消「非活空桶 → 水桶」（转化表作用域收窄，用户拍板）**：活流体虽不是物品，
  定义上**属活化侧**；一般方向是**活化影响非活化**（混凝土粉末→混凝土，保留），反过来
  **非活化物品消耗活化资产**（空桶泡一下把源拿走）是对称性破缺 ⇒ 删 `water_empty_bucket`
  条目（内置 17 ⇒ **16 条**），并删除随之失去唯一使用者的整套 `consumeSource` 机制
  （表字段 + 解析、契约 `consumesSourceOnTransform`、引擎消耗分支）—— 按「不为假想需求扩接缝」。
  ⇒ **催化剂语义成文：转化永不消耗源**。连带：**自动化水桶农场闭环不再成立**，容器内的装桶
  只能玩家手动 GUI 汲（活桶搬不动 + 非活桶不转化）；想自动化的方向是给**活桶**设计新联动
  或走管道抽取（§10）。晋升再生不变量独立保留（中间源被**汲走**后下一拍补回）。
  测试：㉕ 改钉「空桶不变、源不消耗」/ ㉛ 改钉催化剂语义 / ㉜ 改钉汲走后再生 /
  缩容守卫移到表侧（引擎接缝不重复实现）。
  方案与选项留痕：`docs/tech/living-fluid-tech.md §3.7`。
- ✅ **统一时钟：生长类逻辑一律走该流体自己的节拍**（同日第四批次，**487 测试全绿**，
  用户逐条确认划界与 D1~D5）：此前引擎里有**三个时钟** —— 目标层每 tick、实际层每流体节拍、
  物品推动固定 `FLOW_STEP_TICKS = 4`（不分流体），产生两处真实错配：
  **岩浆 30t 才爬一格却每 4t 把物品推走**、**水 5t 长一格却 4t 推**（推动抢在生长前面）。
  定稿口径：**「流动的事按流体节拍走；外界引起的事当拍生效」**——
  ① **晋升从目标层搬到实际层**，在该流体**推进拍**（蔓延之后）判定，输入改为「实际层四邻中
  已是源的个数」+ 要求本格**真的有流体**（更贴近原版 `getNewLiquid`，不再做「预见式升源」）；
  ② **晋升收敛循环删除**（升格后的扩张由逐拍生长承担，目标层退化为纯 BFS + 挤没）；
  ③ **物品推动按流体分组**、随该流体蔓延**同拍同频**（排在蔓延之后）：水 4t→**5t**、
  岩浆 4t→**30t**；`FLOW_STEP_TICKS` 常量删除；顺带修掉「A 流体物品被 B 流体的流树推动」的
  隐患（下游映射 / `moved` 集合按流体切片）。
  **故意不上时钟**（反应类，用户采纳我的建议）：焚毁 / 前沿反应 / 转化 / 挤没 / 源即时 ——
  外部输入若等最长 30t 才生效手感差且难解释（物品丢进岩浆等 1.5s 才烧说不过去）。
  ⚠️ **代价**：三连源再生 1t ⇒ 下一个**推进拍**（水 ≤5t），管道抽取供水上限随之下降
  （用户确认接受：一致性优先，要速率靠多源并行）。
  测试：⑥ 改钉「随蔓延同拍推动」；新增 ㊸（晋升只在推进拍）/ ㊹（**等价性**：晋升只变慢、
  终态与瞬时基线相同）/ ㊺（推动按流体节拍，慢岩浆不再 4t 一次）。
  方案留痕：`docs/tech/living-fluid-tech.md §3`。
- ✅ **管道抽取（活水源对外流体能力，493 测试全绿）** —— 容器里的活水源可被模组流体管道抽取
  （`living-fluid-tech.md` §10 由「设计存档」转「已实施」）。新 `domain/water/ContainerFluidHandler`
  （`implements` NeoForge `IFluidHandler`）：**tank 数 = 派生源数**、`getFluidInTank` = 源流体 ×1000mB、
  `drain` **直接消耗源**（≡ 自动化汲走；SIMULATE 只算不消耗）、`fill` **恒 0**（只出不进 ——
  守住活桶的种子工具地位，与 §6.1 对称性一致）、`isFluidValid` 如实回答「是否持有该流体」
  （免得按它过滤的消费者把整台机器判死）。`LivingItem#onRegisterCapabilities` **宽注册全部
  BlockEntityType** + provider 四段让位判定（战利品跳过 / `instanceof IFluidHandler` 让位 /
  level null / 已有主人让位；返回实例内部自适应 ⇒ 永不 null 切换），与红电
  `ContainerEnergyStorage.resolveProvider` 同款。**活数据反查**：把 `processContainerAt` 里的
  上下文构建抽成 `ContainerLivingItemHandler.resolveContextAt`（`ItemHandler.BLOCK` 兼容面 +
  `DoubleChestPositions` 双箱规范化 + 战利品跳过 ⇒ 与 tick 路径**同源同键**），
  再 `peekContainerData(ContainerDataKeys.FLUID)` **只读不创建**（查询不该凭空造数据）；
  `ContainerFluidData.representativeFluidOf` 开放给能力层造 `FluidStack`。
  🔍 **Create 6 兼容性已查证**（`libs/src/Create`）：**不需要任何兼容代码** ——
  `SmartFluidTank extends` NeoForge 的 `FluidTank` 模板，储液罐/锅炉/漏斗都注册
  `Capabilities.FluidHandler.BLOCK`，管道与机械泵经 `FlowSource` 拉取；管道**只拉不推**
  ⇒ 与「fill 恒 0」天然一致。
  ⚠️ **速率模型修正**（统一时钟后）：单源再生间隔从 1t 变成 `flowSpeed`（水 ≤5t）⇒
  抽速上限 = 1000mB/5t，要更高速率靠**并排更多源**。
  测试新增 `ContainerFluidHandlerTest` 6 项（tank 枚举/稳定序、SIMULATE vs EXECUTE、
  异种 EMPTY、fill 恒 0 + isFluidValid、非容器无害、provider 四段让位）。
- 🔧 **实测三修（末影箱不渲染 / 创造模式倒不进背包 / 管道瞬间抽空）**（497 测试全绿）：
  ① 🔴 **末影箱流体完全不渲染** —— 根因：**客户端无法推断界面容器身份**。原版末影箱 GUI 在客户端是
  `MenuType.GENERIC_9x3` + 27 格 `SimpleContainer` **替身**（真实容器 `PlayerEnderChestContainer`
  只在服务端），与普通 3 行箱子**同形** ⇒ `slot.container instanceof PlayerEnderChestContainer`
  在客户端是**死分支**（渲染落到 BE 桶 = 空）。修：`FluidFlowSyncPacket` 加
  `FluidFlowClientCache.RenderTarget`（PLAYER_INV / ENDER_CHEST / BLOCK，**服务端权威告知**），
  客户端按 target 路由 + `getChestLike()` 取非背包组快照（`GuiInteractionHelper` 的末影箱汲/倒判定同修）。
  📌 两次踩坑同源：先按键前缀猜（撞车 ⇒ 泄漏到物品栏），再按容器类型判（死分支 ⇒ 一点不画）
  ⇒ **约束成文：客户端不得靠槽位容器类型推断容器身份**。
  ② 🔴 **创造模式在玩家背包里倒不进活流体** —— 根因：创造模式客户端用 `ItemPickerMenu`、
  服务端仍是 `InventoryMenu`，**槽位索引不同** ⇒ `resolveSlot` 的「索引直查」落到「恰好也是空槽」的
  无关槽位（如合成结果槽）⇒ 解析不出容器 ⇒ 倒水**静默失败**。修：空槽目标加一道
  **「必须能解析出活容器上下文」**的门（`isAcceptable`），被否决即回退按 `containerSlot` 精确定位；
  `resolveSlot` 改为可注入谓词（纯逻辑可单测）。
  ③ 🔴 **管道瞬间抽空容器 + 每次只拿到请求量** —— 根因：v1 drain 是「任意 ≥1mB 请求都吞**整源**、
  只返请求量」⇒ 慢管道（100mB/t）每拍蒸发一个源、只换回 100mB。定稿（用户拍板）**整源单位**：
  drain 请求 ≥1000 才给满 1000 并删源，**<1000 一分不给**（源不动）—— 契约不超发、不浪费、源恒为整单位。
  曾短暂实现「**源余额账本**」（部分抽取）并**按用户判断撤回**：源到处是二进制语义
  （挤没/晋升/汲走/刷石机/黑曜石/渲染/落盘），分数源会污染每条路径；「无限源」（舀水不删源）同样撤回。
  细水长流留给**专用流体活物品**（照抄活涂蜡铜灯存电那套逻辑），不在通用源能力上扩展。
  测试：`ContainerFluidHandlerTest` ㊼（拿不满不给 / 给满删源 / tank 恒 1000）、
  ㊾⁺（慢管道 20 次抽不到且源不丢、整源管道逐个抽不连带）；`GuiInteractionPacketTest` 新增 2 项
  （索引错位回退 / 索引优先语义保留）。
- ↩️ **回退「砍掉原版末影箱当流体容器」+ 🔧 修第 ③ 次渲染泄漏**（**506 测试全绿**，同日第四/五批次）：
  当日先做了「砍掉末影箱流体兼容」（三次渲染泄漏 + 复杂度渗进架构层，用户拍板「太费劲、无玩法」），
  **随后用户改判：回退，改修 bug** —— 因为那次把 `processEnderChest` 一并删了，而它是
  **第 4 条完整 tick 入口**（build context + `processContext`，与玩家背包同一条路）⇒
  末影箱里**全部**活物品机制失效（活打火石/耕地/漏斗/锁/活末影箱物品的刷名与 `validateRoutes`
  + 红电/应力），而撤除清单与文档只写了流体那一层 ⇒ **超出范围，破坏用户硬边界「不动活末影箱物品」**。
  ① **回退**（`git revert 1613b65`）：完整恢复末影箱作为流体容器 + 物品 tick 容器。
  ② **修第 ③ 次泄漏（末影箱的水渲染到别的箱子界面 —— 三次里唯一没修过的那个）**：
  根因是**服务端末影箱派发不判 viewer**（`FluidFlowServerSync.dispatch` 无条件每 tick 直发主人）
  ⇒ 玩家关掉末影箱打开普通箱子后包仍在来 ⇒ 客户端 `chestLikeTarget`「非背包组用哪个桶」的
  全局提示被翻成 `ENDER_CHEST` ⇒ 串台。BE 容器那条路径本来就有 `isViewing` 过滤，只有末影箱漏了。
  修：新增 `ContainerContexts.isViewingEnderChest`（唯一判据实现点，`LivingEnderChestFunction`
  的活化绑定改为委托它，消掉重复判据）；末影箱分支判 viewer 后 return（无关联 BE 不能落到
  BE 匹配分支）。方案留痕 `docs/archive/changelog.md（2026-10-06 条目）`（含三次泄漏现状表、
  根因链条、被否的 4 个选项）。测试：`ContainerContextsTest` 新增 Nested 6 项（末影箱菜单 true /
  `ChestMenu` 但普通箱容器 false / 非 `ChestMenu` false / null false / 玩家侧两条）+
  重建 `FluidFlowClientCacheTest` 4 项（三份快照互不串 + `PLAYER_INV` 不翻转提示 + `clear()` 复位）。
  文档：tech §7 / §9 + TODO 第 1 项（改回「已完成」+ 三次泄漏史指针）+ AGENTS。
- ✅ **倒桶路径对齐原版（B 档，510 测试全绿，同日第六批次）**：契约加 default
  `canBeReplacedBy(incoming, selfIsSource)`（default `false` ⇒ 未覆写的流体永不被替换），
  熔岩覆写 `selfIsSource && incoming == WATER`，**水不覆写**。`pour` 改为：空格/任意流动格 ⇒ 诞生源；
  **同种源 ⇒ 源不变**（原版语义）；**异种源格 ⇒ 问该源可不可替换** —— 活水桶倒进活熔岩源格
  ⇒ 该格**变成水源**（同键覆盖源类型，原岩浆源湮灭、下游岩浆下一拍自然退走，**不需要额外清理逻辑**）；
  活岩浆桶倒进活水源格**被拒**（对齐 `WaterFluid.canBeReplacedWith`「仅 DOWN + 非水」= 2D 恒 false）。
  🔴 **一个有用的 2D 投影结论**：原版 `LavaFluid.canBeReplacedWith` 的 `getHeight() >= 0.444` 门槛，
  在我们的 `maxLevel 3` 下（流动 level 1~3 ⇒ 高度 0.111~0.333）**永远达不到** ⇒ 严格对齐等价于
  「**只有源格可被水替换**」；而「倒进异种**流动**格」原版会拒绝，2D 无「高度」概念故 **B 档明确保留
  覆盖**这条口子。客户端**不预判**（拒绝时表现为「右键无反应」= 原版倒不进，规则只留一份）。
  ⚠️ **认知更正**：原版**「倒水进岩浆源 ⇒ 黑曜石」不存在** —— 倒桶只做「替换流体」，黑曜石/圆石只来自
  熔岩**蔓延**到水格（机制五，已落地）。此前 §3.5 把这一格记成「待做」是错的，已更正。
  测试：新增 `LivingBucketInteractSupportTest` 3 项（契约默认 false / 熔岩三条分支 / 倒桶判定四例）+
  `ContainerFluidDataTest` ㊻ 覆盖语义（源被覆盖成水 + 实际层当拍改写 + 下游岩浆退走）。
  方案留痕 `docs/tech/living-fluid-tech.md §5`。
- 🔧 **倒桶口径对称化（A 档，同日第七批次，511 全绿）** —— 起因是「为什么异种源格语义要不同」这个
  追问，查实后确认**上一批的论据用错了函数**：
  - `Fluid.canBeReplacedWith`（含 `LavaFluid` 的 `getHeight() >= 0.444` 门槛）属于**蔓延**路径
    （能否**流进**这一格），`BucketItem` 全程**不查**它；
  - 倒桶只问 `blockstate.canBeReplaced(fluid) = state.canBeReplaced() || !state.isSolid()`
    ⇒ 液体块 `legacySolid=false` ⇒ **一律可替换**；另一条 `LiquidBlockContainer.canPlaceLiquid`
    分支永不命中（`LiquidBlock` 未实现该接口）⇒ **原版对水/岩浆、源/流动一律替换，没有区别对待**。
  ⇒ 修正：契约去掉 `selfIsSource` 参数（源/流动之分原版没有，0.444 门槛在 2D 也恒不达标），
  **水与岩浆对称覆写** ⇒ 活岩浆桶倒进活水源格从「拒绝」改为「该格变岩浆源」；
  default `false` 的职责重新定位为「**未覆写的流体保守拒绝**」（不复刻原版方块规则，
  避免将来接入不可置换流体时被静默冲掉）。`pour` 简化为：空格/任意流动格 ⇒ 诞生源、
  同种源 ⇒ 不变、异种源 ⇒ 问一句再决定。文档 §5 / §3.5 与记录文档 §0 更正了错论据。
  测试：`LivingBucketInteractSupportTest` 改写为对称口径 3 项 + `ContainerFluidDataTest` ㊼
  反向覆盖（水源被岩浆覆盖 + 下游水退走）。
## 2026-10-05

- ✅ **引擎时序化（flowSpeed 消费，跨流体反应的时间地基）**（**473 测试全绿**）：
  目标/实际双层 —— 目标层（原 BFS 重算：播种挤没 + BFS + 晋升收敛，每 tick 全量、移除/挤没/拓扑即时）
  与实际层（`flows` 按流体节拍向目标推进：**元胞自动机单步 `min(目标, min同流体邻居实际+1)`**，
  min = 最近供给源胜出，对齐原版 getNewLiquid 取最优供给）。三相位：`computeTargets` →
  `pruneActual`（目标消失当拍删）→ `ensureSourceCells`（源即时）→ `advanceGrowth`（生长按节拍）。
  **参数派生**（层次二，用户拍板「和原版对齐」）：`flowSpeed` 契约改 default -1 = 派生自
  `Fluid.getTickDelay`（水 5 / 岩浆 30），0 = 瞬时（测试基线用，**既有水测试零迁移**），
  N = 显式节拍；maxLevel 注册值即 dropOff 派生值（7/3）。
  推进/渲染/转化全改读实际层——**跨流体反应（§3.5）的时间地基就位**（反应在实际层前沿相遇点触发）。
  测试：新增 ㉗~㉚（慢流体渐进/移除即时/挤没即时/水派生节拍渐进）+
  @BeforeEach 补注册表清空（静态残留污染修复——JUnit 乱序执行下 ㉗ 的慢岩浆污染 ⑱）。
- ✅ **F-1 末影箱汲/倒解锁**（框架侧，**469 测试全绿**，`ContainerContextsTest` 新增 2 项）：
  `ContainerContexts.resolve` 补 `PlayerEnderChestContainer` 分支 —— 原版末影箱 GUI 的槽位容器**不是 BE**，
  这正是它原先走不进「背包 / 单 BE / CompoundContainer」三分支的原因；`EnderChestContainerContext` 从
  `ContainerLivingItemHandler` 迁出为独立文件（避免共享内核反向依赖 God class）。
  ⚠️ 流体侧配套：`FluidFlowServerSync.flushAfterTick` 需补第三条派发（末影箱 `player` 直发）即通。
- ✅ **F-2 static 缓存清理注册表（治本）**：新 `living/util/StaticCacheRegistry`（服务端 / 客户端两张表，
  首批登记 7 + 2 项）—— 把「自愿挂靠（靠记性）」改为「**登记一行即被生命周期覆盖**」；
  `LivingItem.onServerStopped` / `FluidClientCacheCleanup.onLoggingOut` 改为只调 `runServer` / `runClient`。
  🔴 **A1 铁律成文**（infra §3.4）：新增 static 缓存 ⇒ 必须在注册表登记一行。
- 🛠 **新增开发工具 `tools/gen_code_map.py`（代码关系图）** —— 从源码**派生**可交互关系图，
  回答「**改这个类会牵动谁**」（起因：用户提出「逻辑散落各处、上下游纠缠」）。
  解析 `src/main/java`（包 / 类型 / javadoc 摘要 / `extends|implements|permits` / record 组件 /
  import 与正文标识符 / `@Mixin` 三种写法）⇒ 三类边 `inherit` / `use` / `mixin` + `external` 节点
  （18 个原版注入点）；产出单页离线 HTML（自研 FR 力导向 + **上游/下游高亮** + 深度 1~4 +
  包视图 + URL 直达某类）；CLI 另报「被依赖最多的类」「模块级强连通分量」「双向耦合最强的模块对」。
  🔴 **定位 = 派生视图，不是第二份真相**：产物落 `build/`（已 gitignore），**进版本控制的只有脚本**；
  唯一真相永远是 `src/`。新建 `docs/guides/code-map.md` 记录口径与**可信边界**（静态近似 ——
  看不见反射 / 字符串引用、`use` 边不分强度、Mixin 只到类级、不含 `src/test`）。**未改动 mod 代码**。
  ⚠️ 实测坑（四个）：① 子串匹配造出 **154 条假上游**（`LivingItem` 吃掉 `LivingItemFunction`…）⇒ 须按标识符精确匹配；
  ② `@Mixin` 三种写法（简称 / 全限定名 / `targets="…$Inner"`）只认第一种会**漏 3 个注入点**；
  ③ 自研 1/d² 斥力 + 线性弹簧在 300 节点 / 1100 边上**塌成一个球** ⇒ 换 Fruchterman-Reingold；
  ④ `fitView` 用 min/max 被离群点拉大包围盒 ⇒ 改 **3%~97% 分位数**。
- 📐 **代码关系图增「层次视图」+ 分层违规指标**（同日追加）：脚本顶部新增 **`LAYERS` 表**
  （模块 → 目标层号；**是「期望」不是「现状」**，改它 = 改对架构的期望），
  按它把类排成自下而上的横带（Sugiyama 简化版：层内 barycenter 两趟扫描减交叉），
  **违规边（下层依赖上层）标红** + 层间直接标「违规 N 条」；CLI 增「分层违规」报告。
  **实测 113 条（占跨模块依赖 14%）**，最大头 = `L2 通用机制 → L3 领域` **49 条**。
  ⚠️ 两个反直觉结论：**① 12 个领域之间几乎不互相依赖（仅 13 条）⇒ 病在垂直方向，不在水平方向；
  ② `LivingItemManager` 上游最多不是病**（全局注册表的设计使然，它跨出仅 2 个模块）。
  定位：**违规条数 = 「架构演进」的进度指标，目标压到 0。**

## 2026-10-05

- ✅ **末影箱流体渲染同轨（F-1 配套收口）**（**469 测试全绿**）：`EnderChestContainerContext`
  升 public + 补 `getOwner()`（F-1 已迁出 God class，跨包使用顺理成章）；
  `FluidFlowServerSync.flushAfterTick` 补第三条派发路径（context 持有 player 直发）——
  末影箱汲/倒（F-1 解锁）后的渲染/同步同轨。TODO 对应项清结。
- ✅ **活桶世界侧定稿：零拦截**（用户拍板，**467 测试全绿**）。为保活标记而做的
  世界侧兜底（取水 priming 拦截 + 放水 `emptyContents` 重放 + use/useOn 双路径
  事件补丁）整体删除——**活桶在容器里活，在世界里就是普通桶**：
  swap 丢活标记属预期，重新活化即可（活化无门槛）。
  由此 priming 塌缩为「活化水桶直取」一步（原版水桶 content=水 天然满），
  活空桶的世界汲水路径失去意义。删 `LivingBucketWorldUse` 及测试；
  GUI 汲/倒不受影响（标记在 GUI 内变换时保留）。
  教训：为保一个标记搭了拦截层、双路径补丁、Mixin 提案三层兜底——
  「丢标记的代价 < 兜底的复杂度」时，删兜底才是收敛。
- ✅ **实测两连修**（**469 测试全绿**）：
  ① **活水桶仍不能往世界倒水**——事件路径缺口：倒水必然对着方块面右键，走原版
  `useItemOn → BucketItem.useOn`（放水分支），而修复只订阅了 `RightClickItem`
  （`use()` 路径——空桶汲水恰好走这条所以能用）。补 `RightClickBlock` 拦截：
  对齐原版 filled 分支（`emptyContents` + 位置规则 + 创造不消耗），⚠️ 只拦
  **非交互方块**（`MenuProvider` 方块放行原版开界面——方块 use 消费后原版 useOn
  本就不执行，无 swap 风险）；
  ② **光标堆叠多个活空桶汲水丢桶**——`withFluid` 的
  `min(数量, 目标堆叠)` 截断（16 桶变 1 桶）；汲/倒堆叠语义改为对齐原版
  `ItemUtils.createFilledResult`（只耗一个空桶，装好的进背包/掉落）。
- ✅ **游戏实测四连修**（**470 测试全绿**）：
  ① **满活桶对世界放水**（原暂缓项落地）——对齐原版 `BucketItem.use` filled 分支：
  `emptyContents` + `canBlockContainFluid` 规则（原地可容纳则原地/否则相邻面格）+
  创造模式不消耗（`getEmptySuccessItem` 语义）；排空走 `withFluid` 保活标记；
  ② **修复关闭容器后的在途包竞态渲染残留**（合成槽位渲染旧水 / 破坏重放首开残留，同根因）——
  `PlayerContainerEvent.Close` 时向关闭玩家立即下发空快照（同连接 FIFO 保证最后落地是清屏包，
  其它查看者不受影响）；③ **修复活空桶最大堆叠不随形态**——`applyComponents` 连源物品的
  **默认组件**（1.21 组件系统：`stacksTo` 注册为默认组件）一起复制，水桶的
  `max_stack_size=1` 钉死空桶形态；⚠️ `remove()` 会落到 `Item.java:120` 的**硬编码兜底 1**
  而非物品自身 16 —— 必须**显式 set 目标形态的自然堆叠值**（`withFluid`/`asLivingBucketForm`
  两处）。④「活水桶不能往世界倒水」= ①（原暂缓项，非 bug）。
- ✅ **修复流体渲染跨存档残留**（游戏实测反馈，**460 测试全绿**）：单机「退存档→进另一存档」
  不重启 JVM，界面 `removed()` 清缓存与服务端最后一拍发包之间存在竞态窗口 ——
  快照包在客户端清缓存**之后**送达并被无条件写入 ⇒ 缓存带着存档 A 的水进入存档 B ⇒
  同坐标容器首次打开渲染旧水、重开（removed 清缓存）才消失。修：新
  `FluidClientCacheCleanup`（客户端 `ClientPlayerNetworkEvent.LoggingOut` 兜底清
  `FluidFlowClientCache` + `LivingItemClientCache`，覆盖一切时序）；
  `FluidFlowServerSync.CLIENT_ACTIVE` 挂进 `onServerStopped` 清理清单
  （跨存档残留会误发一次空包，自愈但脏）。
- ✅ **修复汲走的源跨存档复活（渲染跨存档残留的根因）**（游戏实测反馈，**468 测试全绿**）：
  写回逻辑在流体数据变空时只清内存与玩家 attachment map，**BE attachment 从不清** ——
  非空期间最后一次写回把源留在箱子的存档数据里 ⇒ 重进存档 `getFluidData` 从附件回填 ⇒ 源复活。
  修：空分支对关联 BE 写回 `EMPTY` 附件（对齐玩家路径的 `persistedMap.remove`；
  EMPTY 序列化为空表，加载端 `!isEmpty()` 守卫跳过 ⇒ 不复活）。
  端到端守卫（`ContainerFluidIntegrationTest`，mock BE 捕获写回序列）：
  有源 ⇒ 附件非空；清空 ⇒ 附件 EMPTY（**退回即挂**已验证）。
- ✅ **活化时机钩子收编（A3 · 467 测试全绿）**：`LivingTagPacket` 里 5 段内联类型判断
  （活箱子掉物 / 活末影箱绑定与解绑 / 活工具写主人 + 名字缓存）收编为
  `LivingItemFunction#onActivated` / `#onDeactivated`（默认空实现），与 A2 的
  `getOwnedComponentTypes()` **同构** —— 同一件事的两半：组件归属自声明 + 活化时机自声明。
  新门面 `LivingItemActivation.apply(stack, level, player, via, activate)` 是三个活化入口
  （活按钮 / 活耕地 / 活地图）**唯一**的切换通道；`setLiving(stack, living, owner)` 的
  `owner` 参数删除（那本就是「框架不知道该写谁、就让调用方传」的 workaround）。
  - ⭐ **owner 读写归一**：`clearLivingData` 一直把 `LIVING_TOOL_OWNER` 当「活工具的数据」
    清除，写入却散在网络包 ⇒ 收编后**读在功能、写也在功能**。原 A2 描述的「非工具物品带
    16B 冗余 UUID」现象随之消失（非工具不写该组件）。
  - ⭐ **玩家缺席被定义为合法态**（不是错误）：签名 `Level` 必填 / `Player` 可空。
    无主活工具走 `FALLBACK_UUID`、未绑定活末影箱走路由模式 —— 二者本就是既有合法模式。
  - ⭐ **新增「下游自决的否决通道」**：`onDeactivated` 返回 `false` ⇒ 框架**保持活状态、
    不清任何数据**，并如实报告「状态未被切换」。**上游只承诺「否决 ⇒ 零改动」**，
    为什么否决、数据是否安全由功能自己判断。首个落点是**活箱子**：掉落需要一个位置，
    而门面没有位置参数，无玩家时返回 false（本功能内容在原版 `CONTAINER` 里，
    取消活化本就**不会**清空内容 —— 返回 false 是因为「掉不出来」，不是因为「会丢」）。
  - 派发目标为「认领该物品的功能」（`getApplicableFunctions`），**不用**
    `hasAnyFunctionFor` 的 copy 探针（它专为判定设计、刻意不入 `APPLICABLE_CACHE`）。
    两侧调用时物品都处于「活」状态 ⇒ 顺序成硬不变量（活「先标记后派发」、
    取消「先派发后清」），已由守卫测试钉死。
  - `ActivationHookTest` 7 项（派发次数与参数原样送达 / 顺序不变量 / 未认领物品不派发 /
    否决通道 / 箱子无玩家拒绝且内容保住 / 末影箱不绑定且照常解绑 / owner 只由活工具钩子写）。
  - ✅ **游戏内验证通过**（2026-10-04）：单测造不出 `ServerPlayer`，故「有玩家」侧三条分支
    （工具写 owner + 赋灵者 tooltip / 末影箱 GUI 内外绑定差异 / 箱子取消活化掉物）
    逐项确认与收编前一致 —— 属**可测性边界**，不是遗留缺口。
  - 勘误：原待办 A1「活化指令路径缺 owner 写入」**病灶不存在** ——
    `LivingItemActivationCommand` 是 `/livingitem activation` **规则管理**命令，
    全程不调 `setLiving`、根本不活化物品。
  - 方案与「未来场景预演」（活经验瓶 / 活凋零玫瑰 / 活纸）见
    `docs/archive/activation-hook-refactoring-plan.md`；三台未来机器已记入 `docs/TODO.md`。

- ✅ **修复汲走源后渲染残留**（游戏实测反馈，**460 测试全绿**）：汲走最后一个源 ⇒ fluidData
  变空 ⇒ 驱动的 `!isEmpty()` 门把**同步也一起跳过** ⇒ 服务器不再发包，客户端快照里的旧水
  永不清除（非「不及时」，是「永不」）。修：`FluidFlowServerSync` 加**边沿状态机**
  （`CLIENT_ACTIVE` 记录「曾下发过」的容器键）—— 数据从有变无时一次性下发空快照清屏，
  之后不再发；无流体容器每拍只多一次 Set 查询。驱动侧 flush 移出 `!isEmpty()` 门。
  新 `FluidFlowServerSyncTest` 3 项（边沿恰好一次 / 从未激活不清 / 反复汲倒重新武装）。
- ✅ **活桶逻辑纠偏定稿：桶 = 载体，零私有状态**（用户点破方向偏差；**457 测试全绿**）。
  桶的逻辑本来就只有「右键切换物品」一下，重量全在活流体（容器级）——此前给活桶发明的
  私有状态全是负资产，本次拆除：
  - **删 `LIVING_BUCKET_FLUID` 组件**：内容状态 = 原版 `BucketItem.content`（public 字段）——
    活化水桶天然装水，旧活水桶天然正确，「隐含内容推导」与「双真相不变量」两个隐患连根消失；
  - **`canApply`/`isLivingBucket` = `instanceof BucketItem`**：原版 + 模组桶通吃，
    `isBucketFamily` 硬编码枚举删除；
  - **形态变换 = `Fluid.getBucket()` 注册映射**（`withFluid` 唯一入口）：汲入流体 X → X 的桶
    （模组流体自动兼容），排空 → `Items.BUCKET`；组件全量保留，形态不变零新对象；
  - **世界取水改走原版 `BucketPickup.pickupBlock`**（含「拿走世界水源」语义）+
    `ItemUtils.createFilledResult`（数量/创造模式语义对齐）+ 取水音效/FLUID_PICKUP gameEvent；
  - 汲/倒处理器走 `menu.setCarried` 写回。新 `LivingBucketFunctionTest` 5 项（判定/内容/形态变换）
    + 世界取水测试适配（`asLivingBucketForm`）。
- ✅ **活桶改换宿主模型（用户拍板）**：汲/倒必须是「空桶⇄水桶**物品形态**变换」（原版桶心智），
  原「同一物品 + 内容组件」模型满/空外观相同且不认 `WATER_BUCKET` 宿主的旧活水桶。
  **内容组件仍是权威数据**，宿主物品跟随内容变换（`LivingBucketFunction.withContent`：
  空 → BUCKET、水 → WATER_BUCKET、岩浆 → LAVA_BUCKET；组件全量保留；数量超宿主堆叠保持原宿主）；
  另加**宿主隐含内容**（`getContent` 推导）：活化水桶/岩浆桶（无组件）直接视为满桶——
  旧活水桶、活化直取路径全兼容。汲/倒/世界取水三处调用点改写回（pour/scoop 走
  `menu.setCarried`，世界取水走 `setItemInHand`）。新 `LivingBucketFunctionTest` 5 项
  + 世界取水测试适配。**457 测试全绿**。
- ✅ **修复汲/倒「右键仍走原版」—— 真因是取水闭环断链，不是拦截失效**（idea.md §〇.7 结案，**452 测试全绿**）。
  诊断：客户端拦截链完好，但桶源退役后 **满活桶不可能存在**（倒水需要它、汲水依赖已有源、
  活化水桶是惰性物品、活空桶对世界取水被原版 swap 丢活标记）⇒ 玩家手里永远是空活桶，
  `matchBucketInteract` 正确返回 null。**修复**：新 `LivingBucketWorldUse`
  （`PlayerInteractEvent.RightClickItem`，两端取消）—— 空活桶对世界流体源（含岩浆）取水
  **灌入内容组件不换物品**（priming）；满活桶对世界一律取消（防 swap 丢活标记，放世界水后补）。
  设计含义：活化空桶 → 世界取水 → 倒进容器，此后进桶⇄源守恒循环。新增 `LivingBucketWorldUseTest` 2 项。
- 🔧 **修复：活水桶右键「没反应」—— 服务端把汲/倒包丢了**（**450 测试全绿**，新增 `GuiInteractionPacketTest` 3 项）：
  - **根因**：`GuiInteractionPacket.resolveSlot` 只认「持活物品」的槽，而汲/倒的目标槽**就是空槽**（倒水要求目标格无物品、汲水的源格也无物品）⇒ `resolveSlot` 恒返回 null、包被丢弃（客户端其实拦了，是服务端扔的）。
  - **修法**：目标槽判据放宽为「活物品 **或** 空槽」（`GuiInteractionPacket.isValidTarget`）。空槽放行安全：非活桶动作只在 `InteractionEntry.matchesTarget` 命中时才发包，而它对空槽恒 `false`。
  - **回归守卫**：`GuiInteractionPacketTest.emptySlot_isValid`（修复前该断言失败）。
  - ⚠️ **实测未解决（当日更正）**：用户游戏内右键**仍走原版逻辑** ⇒ **客户端根本没拦到**（`tryInteract` 返回 false），
    问题在**客户端侧**；此修只是服务端一个**真实的丢包点**，并非症状根因。排查记录见 [idea.md](../idea.md) §〇.7。
    → **同日已由 `66dfcd2` 结案**（真因：取水闭环断链，新增 `LivingBucketWorldUse` 世界取水 priming）。

- ✅ **B.5 第三项：玩家背包 / 末影箱流体落盘（Player attachment）**（**437 测试全绿**，新增 `KEYED_CODEC` 往返 1 项）：
  - 背包 / 末影箱**无 BE** 可挂 `CONTAINER_FLUID_DATA` ⇒ 新增 **Player attachment** `CONTAINER_FLUID_DATA_PLAYER`（`Map<容器键, ContainerFluidData>`，`ContainerFluidData.KEYED_CODEC`）—— 一个玩家有背包（`player_<uuid>`）+ 末影箱（`player_<uuid>_ender_chest`）两个容器。
  - `ContainerLivingItemHandler.getFluidData` / `writebackBlockEntities` 按 `ownerPlayer(ctx)` 分派读写；末影箱 context 补存 `player` 引用（inventory 为 null 无法反查）。
  - ⚠️ **与原草案偏差**：草案写 `Map<containerKey, Set<slot>>` → 实装 `Map<containerKey, ContainerFluidData>`（只存 slot 集合会丢流体类型）。
  - ⚠️ 测试抓到一处 NPE：`WaxedGeneratorFeedChainTest` 用 mock Player，其 `getData` 返回 null ⇒ 已加判空（对真 Player 无影响）。

- ✅ **Q6 多方块容器边界带「身份解析」收敛 —— 批次 A（服务端入口）**（**426 测试全绿**，纯重构未增删测试）。
  - 新增 `container/ContainerContexts.java` 共享内核：`resolve(player, slot) → TickableContainerContext`（菜单槽位→tick 上下文，底稿 `LivingBucketInteractSupport.compoundContext`：背包/单 BE/大箱子 `CompoundContainer` 反射两半 + `DoubleChestPositions.find` 规范化；末影箱解析不出→null 已知缺口）与 `isViewing(player, containers)`（底稿 `ContainerRuntimeCache.isViewingContainer` 与 `FluidFlowServerSync.isViewingContainer` 两份同构：大箱子 `CompoundContainer.contains` 特判）。
  - 三个消费者改薄委托：`LivingBucketInteractSupport.resolveContext` → `ContainerContexts.resolve`；`ContainerRuntimeCache.isViewingContainer` / `FluidFlowServerSync.isViewingContainer` → `ContainerContexts.isViewing`。
  - 验收（可复算）：`grep CompoundContainer` 特判由 3 处收敛到 1 处（`ContainerContexts` 内部）；全量单测回归绿。
- ✅ **Q6 批次 B（服务端收尾）**（**436 测试全绿**，新增 `ContainerContextsTest` 10 项）：
  - `ContainerContexts.ownsContainer(Container, Collection<Container>)` 收编 `SimpleContainerContext.slotBelongsTo`（组件同步归属验证），并把 `isViewing` 的逐槽判据提取为它（两者同源：`isViewing` = 菜单里任一槽 `ownsContainer`）。
  - `ContainerContexts.isSameSlotSpace(Container, IItemHandler, int)` 收编 `SimpleContainerContext.isSameSlotSpaceAsHandler`（hopper §10.25 两级探针）。
  - 两个消费者改薄委托；**`grep CompoundContainer` 代码特判收敛到 1 处**（`ContainerContexts`，其余全为注释）。
  - ⚠️ `CrossContainerTransfer.getBasePosCandidates` **不迁移**（非重复项，属传输「面选取」；通用教训已收编 `container-identity.md` §4）。
- ✅ **Q6 批次 C（客户端收尾）**（**436 测试全绿**，客户端无自动化测试、靠游戏内验证）：
  - ⚠️ **不能放 `ContainerContexts`**：`SlotWrapperAccessor` 是客户端 Mixin，common 包引用它会让**专用服务端**加载时崩 ⇒ 新建客户端对偶 `client/util/ClientSlotResolve.resolveContainerSlot(Slot)`（**被迫分居两侧**）。
  - 收编 `GuiInteractionHelper.resolveContainerSlot` + `AbstractContainerScreenMixin.living_item$resolveContainerSlot`（后者多一层反射兜底，统一保留）；顺带删除 Mixin 里已死的 `WATER_LOGGER` / `SLOT_WRAPPER_FIELD_CACHE` 字段。
  - 两个消费者改薄委托；**Q6 三个批次至此全部落地**。
- ✅ **新建稳定层文档 `docs/system-design/container-identity.md`「多方块容器身份解析（边界带）」**：Q6 收敛前，该主题的教训散在 hopper §6.4/§10.25、tooltip §3.2/§7、infrastructure §2.5/§8.5、活桶汲/倒四处，且只在 buffer 层有记录（无稳定归宿）。本文把问题（三视图互不可达）、共享内核契约（`ContainerContexts.resolve`/`isViewing` + 两条不变量）、消费者清单（6+客户端 1）、通用教训、已知缺口集中到一处；infrastructure §2.5 与 AGENTS 子系统索引已加指针。

- 🔴 **修复末影箱 tick 崩溃（crash-2026-10-04_16.55.26-server）**：`EnderChestContainerContext`
  传空 positions + null inventory 走生产构造器，撞上 1a-2 删 hashCode 第三档后新加的
  「缺稳定身份」显式抛异常 ⇒ **任何有玩家的服务器 tick 必炸**（`processEnderChest` 每拍
  无条件构建）。根因是 1a-2 的「不可达」静态证明**只枚举了 `processContainerAt` 两条路径，
  漏了这个第三调用者** —— 这不是理论隐患，首跑即炸。
  **修法**：给 `SimpleContainerContext` 加**显式稳定键构造器**（第五参 `explicitContainerKey`，
  null = 走原推导）—— 末影箱有完全合法的稳定身份（`player_<uuid>_ender_chest`，玩家作用域
  UUID，无 BE ⇒ 无键漂移），异常消息「请为它提供稳定键」指的正是这条出路；显式键**不是**
  hashCode 回退（调用方必须给跨会话稳定键，空白键照样抛）。`EnderChestContainerContext`
  改走新构造器 + 改包级私有供测试。
  ⚠️ **教训（第二次同型事故）**：Q3 当时就写过「生产不可达 ≠ 不可达——测试也是调用者」，
  这次是「**证明必须枚举全部调用点**」—— `grep 构造器调用者` 是最小动作。
  回归守卫 4 项（`SimpleContainerContextTest` 新 Nested：末影箱构造+键 / 显式键生效 /
  空白键抛 / 无身份仍抛）。**447 测试全绿**。
  （修复由流体侧会话代笔 —— 崩溃阻塞游戏实测，不等框架侧；`EnderChestContainerContext`
  归框架侧所有， FluidTransformTable 无关。）

## 2026-10-03

- ✅ **流体侧批次三（F4 转化表 JSON 化）**（**443 测试全绿**，437 + 新增 6）。
  - 新 `FluidTransformTable`（domain/water）：源格物品转化的数据层，与 `InteractionRuleConfig`
    同构的三层来源加载器（内置 `assets/living_item/fluid_transforms.json` + 玩家差异
    `config/living_item/fluid_transforms.json`：追加/按 id 覆盖/`removed[]` 删除；
    坏条目 WARN 跳过——沉默即缺陷，含未知字段检测）。条目 = `{id, fluid, input, output}`
    （流体为 FluidType 注册 id，**含流体维度键**——同容器水/岩浆各走各的转化）。
  - 转化口径不变：整槽等量替换 + 缩容堆叠等待（数量 > 产物最大堆叠则不转）+ 活物品过滤。
    转化节拍不做：JSON 暂不解析 interval（引擎接缝无节拍参数，不为假想需求做扩展点）。
  - 内置 17 条：水-空桶→水桶 + 16 色混凝土粉末→混凝土（干海绵暂缓等活海绵）。
    `WaterRegistration` 水行为的 `transformItem` 改为委托查表（删硬编码）。
  - 新指令 `/livingitem transforms reload|list`（`LivingItemTransformCommand`，
    与 interaction 同构）；commonSetup 接 `init`/`load`。
  - 新 `FluidTransformTableTest` 6 项（内置装载/玩家差异/坏文件/坏条目/缩容等待/活物品过滤）。
- ✅ **流体侧批次二（F3 活桶 FluidStack 化 + F2 水晋升）—— 桶源退役落地**（**426 测试全绿**）。
  - **F3 活桶 = 同一物品 + 内容组件**：新 `LivingBucketFunction`（`Items.BUCKET` 宿主 + `LIVING_BUCKET_FLUID`
    内容组件 `SimpleFluidContent`，交互型无 tick）；满 = 活水桶、空 = 活空桶，倒/汲只改内容，
    活标记全程保留。新组件注册于 `LivingComponents`。
  - **汲/倒交互**：目标条件是「空槽位 + 容器级源状态」，物品中心的交互规则表达不了
    （`matchesTarget` 对空槽恒 false）⇒ 客户端拦截放 `GuiInteractionHelper` 活桶分支
    （按流体快照缓存精确判定，不命中不拦截，原版操作不受影响），复用 `GuiInteractionPacket`
    服务端管道（新 actionId `living_bucket_pour` / `living_bucket_scoop` + 权威重验）。
    服务端 `LivingBucketInteractSupport` 从菜单槽位反查**活着的** ContainerFluidData
    （玩家背包 / 单 BE / 大箱子 CompoundContainer 反射两半 + `DoubleChestPositions` 规范化顺序，
    容器键与 tick 循环一致）；末影箱解析不出 → 静默无效（已知缺口）。
    倒水语义：无源格诞生派生源，已有源「源不变」仅排空（原版语义）。
  - **F2 水晋升行为**：`WaterRegistration` 注册水行为（流动 7 + `shouldPromote` ≥2 邻源
    —— 原版无限水，接缝由框架侧 1b-2⑩ 提供）+ **最小转化**（F4 前身）：源格上单个非活空桶
    → 水桶（缩容堆叠等待：数量 >1 不转化）。
  - **桶源退役**：引擎删桶源播种 / `isLivingBucketOf` / `registerSource`/`removeSource`；
    删 `LivingWaterBucketFunction` / `LivingWaterBucketData` / `WaterData` 及组件注册；
    `flushEnderChannels` 的「无桶清 flow」逻辑删除（源唯一形态 = 派生源）；
    渲染旧桶轨（桶组件 flow 字符串 + `postTickSync`）随类删除，渲染单一容器轨。
    ⚠️ 修真 bug：`ContainerFluidData.isEmpty()` 未计 `generatedSources`
    ⇒ 纯源容器被驱动的 `!isEmpty()` 门挡在 tick 外（BFS 永不启动、落盘也会漏）。
  - 测试迁移：桶源语义用例全部改写为派生源口径（①~⑬⑰⑲ + 端到端 + 框架侧 ⑳㉑㉒），
    新增 ㉓~㉖（相邻不繁殖 / 挤没自愈 / 最小转化 / 缩容等待）。
- ✅ **框架侧接缝落地：⑧ 落盘 CODEC + 晋升/转化引擎接缝**（**422 测试全绿**，419 + 新增 3）。
  - **⑧ 落盘**：`ContainerFluidData.CODEC`（只序列化 `generatedSources`，**桶源 / 流动表不落** —— 桶在场时每 tick 由桶重新注册，落盘会造幽灵源；流动每 tick 由 BFS 重算）+ `CONTAINER_FLUID_DATA` 附件 `.serialize`。流体类型按 NeoForge 注册表 key 字符串存（`NeoForgeRegistries.FLUID_TYPES`），未知 id 安全丢弃。
  - **引擎接缝**（流体侧点名要的两条，都是 `FluidFlowBehavior` 的 **default no-op** ⇒ 现有行为零变化）：`shouldPromote(slot, sourceNeighborCount)` → `recalculate` 加**晋升收敛循环**（升格为源后重跑 BFS，直到无新升格）；`transformItem(item)` → `tick` **每流体拍**在源格调用、产物写回。⚠️ 转化产物若是活物品，下一拍会被「挤没」销毁 —— 流体侧定转化表时须留意。
  - 新增 `ContainerFluidDataTest` ⑳~㉒（CODEC 往返 / 晋升 / 转化）。
  - ⚠️ **B.5 第三项「背包 Player attachment」仍未做**（倒水主场景在背包，需与 F3 汲/倒同批）。

- ✅ **流体侧批次一：F1 派生源（活水源）数据模型 + F5 流体渲染轨**（**419 测试全绿**，413 + 新增 6）。设计口径与分工见 [idea.md](../idea.md) §〇（本会话 = 流体侧；桶源退役已拍板但**顺延至批次二**与汲/倒同批 —— 否则中间态没有任何造源手段）。
  - **F1 `generatedSources`**：`ContainerFluidData` 新增 `Map<Integer, FluidType> generatedSources`（派生源必须记住流体类型，否则重播种时岩浆源退化成水）+ 生命周期 API（`registerGeneratedSource` / `removeGeneratedSource` / `isGeneratedSource` / `hasGeneratedSources` / `getGeneratedSources` 只读视图，供框架接 CODEC 落盘 = B.5⑧ 前置就位）。`recalculate()` 播种②：派生源**无条件并入** BFS（不依赖任何物品在场 = 纯源容器可活）；**挤没判定在此进行** —— 任何活物品进派生源格 ⇒ 永久销毁（原版「放方块进水源」语义），不关心物品怎么来（手放/未来活活塞推），非活物品共存（供机制三转化）；同槽异种整条覆盖。EMPTY 单例补 noop 覆写。
  - **F5 渲染轨（Q5）**：新 `FluidFlowSyncPacket`（S2C，流体**调色板**传输 + cell = level/fromSlot/调色板下标）+ `FluidFlowServerSync`（`LivingFluidFunction.tickContainerData` 尾部发送 —— **零框架文件改动**；玩家背包直发本人、BE 容器菜单匹配含大箱子特判）+ `FluidFlowClientCache`（最后快照模型，玩家/BE 分存防串台，界面关闭清空）。
  - **渲染自适应**（`AbstractContainerScreenMixin`）：主轨改读容器级快照；贴图/颜色走 NeoForge `IClientFluidTypeExtensions.of(FluidType)`（still/flow 贴图 + ARGB 染色，水/岩浆/模组流体全免费），alpha 用 `FluidFlowBehavior.maxLevel()` 归一（修掉写死 `/7`）；**动画白拿**（atlas sprite 随原版 mcmeta）。旧桶轨降级为**过渡期回退**（容器轨未覆盖且有带 flow 的活水桶时才画），批次二随桶源退役删除。
  - 新增 `ContainerFluidDataTest` ⑭~⑲（派生源独立存活/挤没/与非活物品共存/桶源同格无豁免/异种覆盖/生命周期与 EMPTY noop）。

- 🔴 **修复 1a-4 引入的回归：容器流体数据不再被创建**（活水桶 `registerSource` 被跳过 ⇒ **水流功能整体失效**）：1a-4「容器级数据统一存储」把 `TickContext.fluidData()` 改成只读 `peek` 时，**丢掉了 `TickContext` 构造器里创建流体数据的调用**（旧版由 `simpleCtx.getOrCreateFluidData()` 在此创建，1a-4 一并删掉了该调用）。恢复：构造器对 `SimpleContainerContext` 调 `ContainerLivingItemHandler.getFluidData(ctx)`（含 BE 附件回填，与 1a-4 之前一致）。**补回归测试** `ContainerFluidDataTest.tickContext_createsFluidData`（**退回旧实现恰好挂掉该测试**，已实测验证）。**406 测试全绿**。

- ✅ **基础设施重构 1a 地基完成（4/4，397 测试全绿，行为不变）**：为「活水源 = 没有物品载体的活物品」铺路。
  - **Q3（1a-2）**：删 `containerKey` 第三档（按 handler hashCode 兜底），改为显式抛 `IllegalStateException`；新增测试专用构造器 `SimpleContainerContext(IItemHandler)` / `(IItemHandler, Level)`，键生成 `test#N`。
  - **Q4（1a-3）**：抽 `TickableContainerContext` 子接口（`setTickContext` / `flushDirtySlots` / `getAssociatedBlockEntities` / `getInventory`）；`ContainerLivingItemHandler` 内 `instanceof SimpleContainerContext` 由 9 → 2；`ItemEntityContainerContext` 实现为无操作。
  - **Q2（1a-4）**：容器级数据（流体/红石/电力）由分散字段并入统一 `ContainerDataStore`，按 `ContainerDataKey<T>` 的数组下标存取（非哈希）；新增一种容器级数据 = 在 `ContainerDataKeys` 加一行。涉及 `ContainerDataKey` / `ContainerDataStore` / `ContainerDataKeys` 三个新类，以及 `TickContext` 改为持 `ContainerDataStore`。
  - **Q1（1a-1）**：`LivingItemFunction` 新增 `default boolean shouldTickWithoutOwnItems(ContainerContext)`（默认 `false`，现有 22 个功能不受影响）；`LivingItemManager` 在**注册期**算出静态「自维持函数」清单（以 null ctx 调用一次），`processContext` 在 `grouped.isEmpty()` 短路前把清单塞进 `grouped`（空 entries）。每 tick 只遍历 0~1 个，不逐函数判定 ⇒ 成千上万个容器下开销可控。**当前无函数覆写（活水源在 1b）**，完全空容器仍走 `handleEmptyContainer` 短路。
  - 设计要点见 `docs/buffer/infrastructure-refactoring-plan.md` §2 / §3。

- ✅ **1b-1 引擎泛化**（纯重构，水行为零变化）：`ContainerFluidData` 条目带**流体类型**（`FlowEntry.fluid`，**单张 map 带类型** —— 一槽只装一种流体）；新增 `registerSource(slot, fluid)`（旧 `registerSource(slot)` 默认水）；`recalculate()` 的播种与 BFS 扩散**按类型**（已占格不被别的流体覆盖）。
  - ⚠️ **关键做法**：本类此前**零单测** ⇒ 先补 6 条「行为快照」（`ContainerFluidDataTest`，golden master）钉住水行为，**再**重构 —— 否则「397 绿」只证明别的层没坏、证明不了引擎行为没变。
  - **403 测试全绿**（397 + 6）。多流体的「行为分档 / 每流体上限 / 桶内容判定 / 跨流体交互」归 **1b-2**（本步按水处理，`isLivingBucketOf` / `MAX_FLOW_LEVEL` 保持水常量）。

- ✅ **1b-2a 行为分档接缝**（框架契约）：新增 `FluidFlowBehavior`（`canFlow` / `maxLevel` / `flowSpeed`）+ `FluidFlowBehaviors` 注册表 —— **未注册流体 = 静止**（安全默认），水在 `WaterRegistration` 注册（上限 7）。`ContainerFluidData` **改用接缝**（不再硬编码上限）。**405 测试全绿**（+2 条接缝测试：按 maxLevel 扩散 / 静止不扩散）。

- ✅ **1b-2b 通用驱动函数**（框架契约）：新增 `LivingFluidFunction`（**自维持** + `HasContainerData` **prio 0**）—— 驱动容器级流体 BFS，**与桶解耦**（桶的 `tickContainerData` 只留 `postTickSync`、prio **0→1**）⇒ **桶不在场时容器级流体照样推进**。**408 测试全绿**（+2 条驱动测试：自维持/prio/不挂物品、驱动 BFS）。

- ✅ **流体测试补强**（2026-10-03）：新增**端到端**测试 `ContainerFluidIntegrationTest`（走真实 `processContext`，验证「桶注册源 + 驱动跑 BFS」在真实流程**接通** —— 撤销 1b-2b 的「仅单元级」保留）+ 引擎**二维扩散**测试（覆盖 `getNeighbors` 的 up/down）。**411 测试全绿**。

- ✅ **1b-2c 红石归零解耦**（框架修复）：抽出 `zeroResidualRedstone(grouped, ctx, tick)` —— 容器有 `REDSTONE` 数据但 `grouped` 里没有 `LivingRedstoneFunction`（残留红石）⇒ 主动归零，**判据与 `grouped` 是否为空无关**。⚠️ **该问题已被 1b-2b 提前触发**（自维持驱动使 `grouped` 恒非空 ⇒ 原 `handleEmptyContainer` 分支**永不执行** ⇒ 残留红石不再归零）。**412 测试全绿**（含 1b-2c 回归守卫，已实测「禁用即挂」）。

- ✅ **1b-2 源查询 API**（框架）：`ContainerFluidData` 补 `isSource(slot)` / `sourceFluid(slot)` / `hasAnySource()`（供汲 / 倒处理器用）。**413 测试全绿**。
  - ⏸️ **1b-2 余下两项明确暂缓**：⑧ **落盘** 待流体侧 `generatedSources` 数据模型（今天唯一的源是桶源、每 tick 由桶重新注册 ⇒ 无物可落，预先写 CODEC 属为假想数据做序列化）；⑨ **同步轨泛化** 属客户端渲染（待 Q5）。详见 `docs/buffer/infrastructure-refactoring-plan.md` §3 1b B.8。

- 🔴 **修复「特定活武器永久白板化」**（高频 0.25s 一刀 / 伤害恒 1 / 常驻拉仇恨）：
  属性镜像竞态 —— 主人换手发生在容器点击（tick 间），属性刷新在其后实体 tick，
  竞态窗口内主人地图的旧武器 bd/bs 被镜像记录，下一轮清理按 id 删除
  （与武器自身修饰符同 id），equipTool 的 changed=false 不补回 ⇒ 永久白板。
  修：equipTool 属性每次摘旧装新（幂等自愈，changed 门控只留位置类附魔）+
  镜像排除假玩家当前武器自身 id。仇恨本身为已知机制（§8 ② 复仇目标 = 假玩家 @ 宿主位置）。
  详见 `living-weapon-tech.md` §8.1。

## 2026-09-30

- ✅ **tooltip 主人显示统一**：活末影箱与活工具/活武器收敛为同一口径 ——
  **UUID 是绑定数据，名字是显示缓存**（`OwnerNameResolver#displayName` 单一显示来源：
  实时解析 → 显示缓存 → 短 UUID；显示短 UUID 的方案被否——「有毛用」）。
  新增 `LIVING_TOOL_OWNER_NAME` 显示缓存组件（活化时记名；`syncOwnerAttributes` 改为
  返回在线主人，回放/辅助四路顺手刷新，改名下次回放即跟上；随 owned-types 取消活化清除）；
  `EnderChannelData.boundPlayerName` 语义改为显示缓存（绑定时记名 + 末影箱 tick 遇到
  在线绑定玩家刷新，`withPlayerNameCache` 只动缓存不动绑定）。
  `living-ender-chest-tech.md` 数据模型与验证清单已同步。

- ✅ **活工具/活武器射线微调**（主动模式 · 玩家形态）：背包界面点击玩家小人 ——
  左键躯干中心/底部 = 起点锚点（身体中轴 1.125/0.75，不随朝向转），左键脑袋 = 重置回眼睛，
  右键 = 切换朝向跟随（绑定身体朝向，与背后环同款；pitch 不跟）。原始 offset 永不修改，
  生效时衍生副本绕 Y 旋转（服务端回放与客户端渲染同公式，L15/L48 不破坏）；
  录制新记忆整体重置；仅玩家形态生效（容器/掉落物不生效）；创造背包同支持。
  实现：`LivingToolRayTuning` 组件 + `ToolRayTuningPacket` + `ToolRayTuningClicks`
  （渲染矩形竖向三分命中，不做 3D 拾取）。详见 `living-tool-tech.md` §2.5。

- ✅ **活武器/活工具「代持 tick」通用兼容**：FakePlayer.tick 为空 ⇒ `inventoryTick`
  （手持 buff/足迹/蓄能）与 `ItemCooldowns`（技能/proc 冷却）在主动模式从不推进。
  三个持续回放入口在 equipTool 后补两句：`held.inventoryTick(level, fake, 0, true)`
  （selected=true = 假玩家正手持）+ `fake.getCooldowns().tick()` —— 模组无关
  （走原版入口即兼容，铁律②；实测 Simply Swords postHit 特效/足迹/技能冷却）。
  边界：玩家形态双 tick 接受；被动模式 main-hand 检查不伪造；瞬时辅助路径不做；
  held 中断修改丢失（挖断蓄能清零）罕见模式接受。详见 `living-tool-tech.md` §5.7。

- 🏗️ **活武器受控 tick 重构（台阶一/二，含一次实测回归修正）**：`FakePlayer.tick()` 被
  刻意掏空导致六类原生机制冻结，现 `LivingToolFakePlayer#driveWielderTick` 按 game tick
  去重驱动真链（**调 doTick() 不调 tick()** —— ServerPlayer.tick 只做簿记，真链在
  doTick；noGravity + deltaMovement 归零钉位，不进世界）⇒ inventoryTick / 物品冷却 /
  药效衰减 / 装备属性刷新 / 蓄力推进全原生（代持 tick 两行与镜像竞态的自愈补丁退役，
  镜像排除保留）。⚠️ 攻击节奏为 per-weapon 语义 —— 原生 ticker 长在共享 fake 上
  （实测回归：被动模式只有最靠前的武器出手），缩放改由回放侧按各武器上次攻击记录
  算好经 `setAttackStrengthScale` 注入。**为蓄力型（活弓箭）铺路**：
  真链的 `updatingUsingItem` 即蓄力状态机。详见 `living-tool-tech.md` §5.7。

- 📐 **蓄力型设计定稿**（活弓箭，未开工）：识别 = getUseDuration > 0 + UseAnim != NONE
  + 可附魔标签；核心设计（用户定）= 常驻满蓄力、触发直发、发射后回充 20t —— 免录制
  （releaseUsing 的蓄力时长是调用参数，合成满蓄力直接调，无需 startUsingItem）。
  方向复用记忆射线 + 朝向跟随；录制复用 R1（箭命中录射线）。待拍板：弹药经济学 /
  回充途中触发 / 回充时长配置。翻转旧结论「只会拉弓射不出去」。
  详见 `living-weapon-tech.md` §9.1 设计稿。

## 2026-09-29

- ✅ **活武器连击修复**：主动模式高速连击（不扣耐久的灾变武器最明显）——「上次攻击 tick」
  (`LivingToolAction`) 持久化两条丢失通道：① `writeBack` 的 `ItemStack.matches` 对只变
  运行时组件的栈误判相等（副本宿主下记录随副本丢弃）；② 记录旧写在 `fake.attack()` 之后
  （模组武器自结伤害/事件取消/异常时 `attack()` 提前返回，记录执行不到）。修复：出手记录
  改写在 `attack()` 之前 + 攻击分支出手必 `setItem`。灾变源码已核（无 durability 注册、
  未拦截左键、攻速走标准修饰符），✅ 已实测：间隔恢复面板冷却、攻速饰品镜像生效。
  细节见 `living-weapon-tech.md` §5 第四坑。
- ✅ **活工具/活武器 tooltip 新增「赋灵者」行**：显示绑定主人（玩家名优先，
  解析不到回退短 UUID；无主不显示）。`LIVING_TOOL_OWNER` 补 `networkSynchronized`
  （此前仅持久化，客户端栈上无此组件）；新增公共侧解析桥 `OwnerNameResolver`
  （客户端 `FMLClientSetup` 注入：tab 列表 → UsernameCache，公共类不引用客户端类）；
  名字解析不到的回退与「负信息不上 tooltip」口径见 `LivingToolFunction#addOwnerLine`。

- ✅ **文档口径**：活箱子不是宿主、内容物不 tick（`LivingChestFunction.tick` 空实现 +
  `LivingChestItemHandler` 死代码，复算判据三条）固化进 `living-chest-tech.md` §3.3；
  weapon/tool 文档宿主表补「三宿主即全部」指针。属性镜像生效条件澄清为「只看主人在线
  + 同维度，与宿主无关」并勘误旧表述（`living-weapon-tech.md` §8.1.1）。

## 2026-09-28

- ✅ **对外开放 C 组 A1：api 面净化** —— 探针实证 `LivingItemManager`（api 包）的公共面
  牵出 30 个 domain 类型（组件常量 + 35 对便捷访问器），api 承诺等于没有边界。
  迁移：28 个内容组件/附件常量 → 注册站 `LivingComponents`（transfer 包，纯注册站 ——
  静态初始化顺序由 JVM 决定无法声明，一个类一个 `<clinit>` 原子完成消灭时序问题）；
  25 个 Data 类自带 `of()/set()`（原样搬方法体，保真语义差异：Memory 走默认值兜底、
  Progress/Action 走 `stack.get` + null 清除）；`LivingItemManager` ~950 → 347 行成纯框架类，
  不再 import 任何 domain 类型。存档安全（registry key 与 Java 类位置无关）。
  ⚠️ **教训**：迁移脚本 `--apply` 的输出被 PowerShell 吞掉 ⇒ 误判"未执行"而重跑，
  二次破坏（注册站空壳覆盖、调用点漏替换、三个 tools 类切割残骸）——
  修复走 git HEAD 提取重建 + 三个类 checkout 手工重做 + 调用点替换拆 main/test 两轮。
  **批处理脚本输出不可见 = 状态未知，必须先确认再重跑。** 85 文件 +953/−958
- ✅ **D2：交互规则 JSON 化** —— 18 处 Java 注册迁 `assets/living_item/interaction_rules.json`
  13 条（红石 13 种按钮被 `#minecraft:buttons` tag 一条覆盖 —— 原注释「原版没有按钮标签」
  经 vanilla jar 实证为错，已更正）；玩家差异 `config/living_item/interaction_rules.json`
  （按 id 覆盖 + removed 防复活）；未知字段 WARN（含 Gson 默认忽略的拼写错误检测）；
  `InteractionPredicates` 谓词 ID 化（can_till / can_plant）；`/livingitem interaction reload|list`。
  安全边界天然成立：action 只能引用已注册 handler，玩家不能创造新行为。
- ✅ **D1 增补：活化指令修复 + 手持分支** —— `target` 从 `StringArgumentType` 改原版
  `ResourceOrTagKeyArgument`（实证其允许字符集不含 `: # @`，**整个 target 参数从未可用**，
  而手写补全不经过解析 ⇒「能列举却注册失败」的骗局）；命名空间改 NeoForge
  `ModIdArgument` 走 `deny-mod/allow-mod/remove-mod`（`@` 是原版选择器保留前缀，不复用）；
  deny/allow/remove 不带参数 = **手持物品**（`heldItem()` 四处共用，carried 踩坑注释随之单源）。
  `ActivationTargetParsingTest` 4 项（含反向守卫防改回）。
- ✅ **许可：LGPL-3.0 附加 classpath 例外**（LICENSE.txt 末尾 GNU LGPL v3 §7 additional
  permission）—— 依赖本库的 mod/addon 可任意授权（含闭源）；修改库本体仍须 LGPL。
  README 双语徽标 + 第三方须知。SPDX 无此例外标准标识符，自动化工具仍用
  `LGPL-3.0-or-later`（已注明，不编造标识符）。
- ✅ **开放计划推进**：`open-plan.md` Q1 拍板（加例外）、Q2 更新（D 组完成）、
  新增 Q6 前置库三步路线图（契约 → 发布物 → 仓库）；`docs/system-design/api-contract.md`
  扩展点盘点补组件注册站行；8 个 tech 文档 + `data-model.md` 的旧便捷方法示例
  同步为 Data 类 `of()/set()` 新形态。测试基线 388 → 397。

---

## 2026-09-27

> ⚠️ **2026-10-08 补录**：本节原**缺失**（changelog 从 09-28 直接跳到 09-25）。
> 该日的测试新增记录此前**只存在于 `AGENTS.md` 的「测试流水」段**，
> 特此补录 —— 否则会随该段清理而**永久丢失**（正是本文件头部那条断档教训的又一实例）。

- 新增活化规则 JSON 加载语义 **14 项**（含指令侧 `put` / `remove` / 校验 / **持久化往返**；
  tag 路径 1 项 `@Disabled` —— FML unit test 不加载 item tags，已游戏内验证通过）
  + **活化门面守卫 5 项**（含「零配置全放行」口径锁定）+ **DataComponent 归属守卫 3 项**。

---

## 2026-09-25

- ✅ 联机：**主动模式（有记忆）联机可见** —— 收集口径扩为 `isAssistItem ∪ 有记忆`
  （记忆/进度/动作全在 DataComponent，随 `ItemStack.STREAM_CODEC` 包内副本天然携带，零新增同步）；
  `renderOtherPlayerItems` 对有记忆物品复用 `renderOne`（悬空工具体 + 挖掘转圈 + 攻击脉冲）；
  `RayRenderer` 加第四条路 `renderRemotePlayerHosts`（F3+B，远程玩家记忆射线）。`living-tool-tech.md` §11.9
- ✅ 联机：**攻击环可见**（`LivingToolAction` 组件随包走，渲染端按组件分组为攻击环/背后环）
- ✅ 攻击环改为**按每把武器自己的 action 分组**（原先「组内任意一把刚出手 ⇒ 全体飞出」，
  冷却中的那把也被带着飞）—— 现在只有刚出手的进攻击环，其余留背后环；
  因窗口(6) < 武器冷却(剑12.5) ⇒ 多把按各自冷却**轮流扑咬**。`LivingToolModelRenderer`
- 🎛️ **待机位调参**：`IDLE_MAX_OFFSET` / `IDLE_HALF_SATURATION`（用户调 —— 悬停距离
  `MAX × L / (L + HALF)`，L = 记忆射线长度）
- 🎛️ 环半径用户调参：背后/挖掘环 0.7 起、每把 +0.007、上限 1.40
- ✅ tooltip：模式措辞简化为**主动/被动**（原"辅助玩家挖掘/自主挖掘"带挖掘字眼，活武器不适用）；
  补**攻击记忆行**（距离 + 蹲下录的生物限定，`EntityType#getDescription`）
- 🎛️ **创造模式放开辅助**（原 2026-09-20 定案"创造/旁观跳过"）—— 辅助攻击与辅助挖掘都对
  创造玩家生效（攻击侧创造有实际意义且创造物品不掉耐久；挖掘侧放开无副作用），旁观依旧排除
- 📝 已知问题记录（暂不修）：工具右键**不可交互方块也会录上 use 记忆** ——
  `BlockToolModificationEvent` 在原版判定前无条件 post（`AxeItem#evaluateNewBlockState`
  对任意方块连发三次 ability 尝试）+ L44 保护期挡住 `RightClickItem` 的自愈清除；
  将来修法：按 ability 重算原版判定。`living-tool-tech.md` §4.3

---

## 2026-09-24

- 🔴 活武器：修「攻击后**玩家被踢出**」（组件可空 `BlockPos` 发包 NPE；表现为"存档崩了、游戏没崩"）—— `living-weapon-tech.md` §5
- ✅ 活武器：新增**记忆清除**（挥空 / 挥向方块 ⇒ 清攻击记忆，套 L44 保护期）—— §9
- ✅ 活武器：修**掉落物形态不攻击**（该宿主入口漏了活武器判据）—— §6
- ✅ 活武器：双记忆共存改为**有怪打怪、没怪挖矿**（原先挖掘记忆完全失效）—— §6
- ✅ 活武器：新增**辅助攻击**（玩家打中怪 ⇒ 背包里无记忆的活武器一起出手）。
  🔴 关键：原版无敌间隔会让**玩家那一刀之后的所有攻击全部失效**（伤害/附魔/击退都不触发），
  故用官方接口 `LivingIncomingDamageEvent#getContainer().setPostAttackInvulnerabilityTicks(0)`
  仅在辅助攻击期间压制无敌（铁魔法同款做法）；另有「死亡即停」「取消击退」两处保护。
- ✅ 活武器：**动画落地**（原先暂缓）—— §7.1
  有记忆：攻击**瞬现到目标位 + 缩放脉冲**（与活工具交互动画同款；`replayAttack` 出手时写
  目标所在格 ⇒ `renderOne` 既有分支自动生效，零新增同步）；
  无记忆：新增**攻击环**（对称挖掘环：攻击时飞到目标生物处，风车自转改**缩放脉冲**，
  **剑尖朝圆心** —— 与工具环的"柄朝圆心"相反）；环成员按工具/武器拆分
  （活斧子归工具环，`if / else if` 互斥防画两遍）。背后环不变。
- 🎛️ 活武器动画**实测微调**（用户看效果后定）—— §7.1：脉冲力度 0.15→**0.30**；
  攻击环存活窗口改为**与脉冲等长**（"扑咬式"：出手⇒飞过去脉冲一下⇒收回，替代原"留原地 20 tick"）；
  攻击环起始半径独立调大（**0.42**，原沿用工具环 0.28 ⇒ 剑长穿模；工具环不变）。
- 🎛️ **背后环改为工具与武器混编一圈**（原先各画一圈会完全重叠）——
  `renderAssistRing`/`renderAttackRing` 返回待机物品，`render` 统一画一次 `renderBackRing`；
  背后环半径 0.28→**0.35**（上限 1.10→1.20，混编含剑防穿模）。
- ✅ 新增**属性镜像**（`LivingToolFakePlayer#syncOwnerAttributes`，2026-09-24 用户需求：
  饰品模组强化玩家本体属性 ⇒ 活工具/活武器借 FakePlayer 出手吃不到增益）——
  把主人白名单属性（攻击伤害/攻速/击退/挖掘效率/挖掘速度）的修饰符复制给 FakePlayer；
  ⭐ 排除主人主手物品贡献（防附魔双倍）；重算式（穿脱饰品自动跟上）；
  挖掘/交互/攻击回放与辅助挖掘四处接入，活工具活武器同时受益。
  边界：只覆盖属性型饰品，事件型（按实例判定）吃不到（§8.1）。
- 🎛️ **环半径饱和常数 K 调大**（2026-10-01）：7 → **21** ⇒ `r(n) = 1.77 · n / (n + 21)`
  （1 把 0.08 / 10 把 0.57 / 36 把 1.12）—— 起步更紧凑、随数量铺开的区间更长。
- 🎛️ **环半径改【双曲饱和】**（2026-10-01 用户定）：`r(n) = 1.77 · n / (n + 7)`
  （先快后慢、渐近上限：1 把 0.22 / 7 把 0.89 / 36 把 1.48），取代线性 `BASE + STEP × n`
  ⇒ 删除 `RING_RADIUS_BASE` / `RING_RADIUS_STEP` 与 `drawRing` 的 `radiusBase` 参数，
  三个环共用同一公式；半饱和常数取 7 —— 与射线 `IDLE_HALF_SATURATION` 同值，口径统一。
- 🎛️ **攻击环起始半径与其它环统一**（2026-09-30 用户定）：删除独立常量
  `ATTACK_RING_RADIUS_BASE`（曾为"剑长穿模"调到 0.7）—— 渲染改走手持 display 后大小已与手上
  一致、穿模自然缓解 ⇒ 三个环（背后/挖掘/攻击）统一用 `RING_RADIUS_*`（0.34/0.07/1.77）。
- 🎛️ **环半径参数调大自适应幅度**（2026-09-30 用户调）：`RING_RADIUS_BASE` 0.7→**0.34**、
  `STEP` 0.007→**0.07**（增长明显 10 倍）、`MAX` 1.40→**1.77** ⇒ 由"接近固定"变成真正的
  自适应（1 把 0.41 / 5 把 0.69 / 10 把 1.04 / 20 把 1.74）。攻击环 BASE 保持 0.7 不变。
- 🔧 **射线起点/朝向改【帧间插值】**（2026-09-30）：`resolveAnchorOrigin` / `transform` 增加
  `partialTick` —— 起点用 `getPosition(partialTick)`、朝向用 `getViewYRot(partialTick)`
  ⇒ 移动/转身时射线平滑跟随，不再一顿一顿；服务端回放传 1.0F ⇒ 行为不变。
- ✅ **BEWLR·射线 立正单独标定 = identity（不转）**（2026-09-30 五候选实测 #0）：
  环·BEWLR 的绕 Y −90° 不能复用 —— 环要求柄水平、射线沿任意 3D 方向 ⇒ 对齐基准不同。
  至此立正常量拆为【场景 × 模型类型】四组（§7.2 表）。
- ✅ **射线模式滚转重标定**（2026-09-30）：旧值 270° 是旧链（FIXED + −45°）下的标定，
  统一链（手持 + 立正绕X+10°）下失效；四候选（以柄为轴，柄不动只拧板面）重定为 **180°**。
  至此三条渲染场景（环·普通 / 环·BEWLR / 射线）全部走「手持 display + 固定整体变换」，
  立正常量仅两组（普通=绕X+10° 环/射线共用；BEWLR=绕Y−90°）。
- ✅ **环上模型姿态【全线统一为「手持 display + 固定整体变换」】**（2026-09-30 定案，§7.2）：
  原版 + 普通模组路线 B 也改走 `THIRD_PERSON_RIGHT_HAND`（与 BEWLR 同源）——
  MC 用物品自己的 transform 摆正每件物品（建模差异全在这里吸收），我们只叠加固定整体变换：
  立正（普通=绕X**+10°**，由 handheld rotation 推导 —— 手持时长轴几乎已竖直，**不是 90° 量级**；
  BEWLR=绕Y−90°）+ 滚转（运行时把常量板面法线送切线 + 标定90° + `ringRoll` 0/π 翻转）
  + 自转固定轴 X。**彻底删除了"贴图对角线 / rotationTo / 板面法线逐物品计算"等旧包袱**。
  📌 踩坑沉淀：滚转不能是常量（板面朝向随位置漂移）；立正是小角度别拿 90° 候选凑；
  `ringRoll` 翻转不能省（否则开锋朝向左右不一致）。
- ✅ **BEWLR 物品（灾变、原子分解机等）改走「第三人称手持」display —— 修掉歪 45°**（2026-09-30）：
  原先 `renderStatic(FIXED)` 用的是模组为【物品展示框】调的姿态 ⇒ 原子分解机歪 45°；
  换成 `THIRD_PERSON_RIGHT_HAND`（与"拿在手上"同源）后立刻"横平竖直"，再叠一个绕 Y **−90°**
  立正（`BEWLR_UPRIGHT_FIX`，四候选实测 #3）⇒ 姿态 / 大小全对；缩放比补偿随之删除。
  不需要"隐形玩家"：姿态由 display transform 决定，与谁拿着无关（§7.4）。
- ⛔ **环上「通用立正」两轮尝试失败、已回退**（§7.3；代码留档在 git 历史 `90e0d84` / `93ae2b1`）：
  ① 手写手持 transform + context 传 NONE + 运行时立正 + 滚转送切线 + 实测标定 −90°
     ⇒ **原版姿态正确**，但原子分解机仍歪；② 顶点 PCA 几何实测长轴/板面法线 ⇒ 姿态怪异。
  📌 换来的硬事实：本环境 `renderStatic` **不应用 display transform**（调试打印证实 API 读得到
     值却不生效）；原版 2D 模型顶点铺满 1×1 方板 ⇒ **几何上无唯一长轴**（PCA 简并）；
     立正 / 滚转 / 自转强耦合（实测差 −90°，与 `RAY_ROLL_FIX = +270°` 同源）。
  ⇒ 维持 §7.2 旧方案（原版 + BEWLR 两路）；个别异种物品无通用解，
     将来若要修按物品配姿态预设表（YujianCraft 的做法）。
- 🎨 环上模型**立正重做**：按 `isCustomRenderer()` 分两条路（§7.2）——
  灾变等 BEWLR 物品（不守"手持斜45°"约定）走 fixed display 全权渲染 + thirdperson/fixed
  缩放比（修"偏45°+大小不对"，实测姿态/大小全对）；原版 + 守约定模组**回退旧实测方案**
  （-45°绕Z）——两次重构尝试（手持transform+运行时立正）都实测平躺、根因未明，已记录勿再走；
  修复重构引入的 NPE（fixedPose 跨分支引用 ⇒ 画背后环首帧即崩）。

> 当天另有若干「只改实现、口径不变」的改动（三个静默失效、射线朝向改取视线、附魔实测、
> 位置类附魔缺口），按 `docs/README.md` §4.0 判据**只记在 `living-weapon-tech.md`**，不进 changelog。

---

## 2026-09-23

- ✅ 新增：**活武器「近战核心链路」**（记忆 → 录制 → 回放 → 调度 → 渲染）。
  判据 `isLivingWeapon` 用原版**「可附魔类别」标签**（`ItemTags.WEAPON_ENCHANTABLE`）——
  官方分类口径，模组武器通常也正确归类 ⇒ 自动兼容；**活石头不在任何武器标签里 ⇒ 不会误判** ✅
  ⚠️ 只认这一个标签：弓 / 弩 / 三叉戟是**蓄力型**，需"开始→持续→释放"状态机，留待后续（FakePlayer 不 tick ⇒ 会只拉弓、射不出去）。
- ✅ 数据模型：**`AttackMemory` 成为第三类记忆**（`LivingToolMemory` 由两字段变三字段）。
  ⭐ **另建 record 而非复用 `RayMemory`**：后者带 `Block` 字段、语义是方块，混用会污染语义且要动
  已稳定的编解码 ⇒ 独立出来**零回归风险**。`Codec` / `StreamCodec` 同步就位。
  ⇒ `isEmpty()` 判三个字段 ⇒ 带攻击记忆的武器**自动不上环**。
- 🔴 踩坑：**`LivingDamageEvent` 在 NeoForge 1.21 已拆成 `Pre` / `Post`**，`getSource()` 只在子类上。
  用 **`Post`**（伤害已结算）⇒ 不会把「被格挡 / 被减免到 0」的攻击录进来。
- 🔴 踩坑：**攻击冷却不推进 = 只有 20% 伤害**。`Player#attack` 里 `f *= 0.2F + f²*0.8F`（f = 冷却比例），
  而 f 读的是 **private** 的 `attackStrengthTicker`，靠 `Player#tick()` 自增 ——
  **`FakePlayer#tick()` 是空实现**（`L27` 一脉）⇒ 恒为 0 ⇒ **永远是两折伤害，且永不触发横扫/暴击**。
  解法：**override `getAttackStrengthScale()`**（`LivingToolFakePlayer`），由回放侧按
  「世界轴 tick 差 ÷ 物品冷却时长」推进（S1-a，与原版同源）。
  不用反射（生产环境因混淆失效）、不用 AT（要新增配置）。
- ✅ 回放 `replayAttack`：官方 `ProjectileUtil.getEntityHitResult` 找实体 → `faceTarget` → `fake.attack()`。
  **不实现任何攻击逻辑**，伤害/附魔/耐久全走原版管线（锋利 / 击退 / 火焰附加 / 横扫 / 暴击自动生效）。
  ⭐ **隔墙检测不能省**：实体检测不看方块 ⇒ 不加就会"隔着墙打死后面的怪"。
- ✅ 目标过滤收窄（`isAttackableByLivingWeapon`）：
  🔴 **`Entity#isAttackable()` 默认返回 `true`**（只有 `ItemEntity` 等极少数 override）⇒ **光靠它几乎挡不住东西**。
  补上 `LivingEntity`（挡船/矿车/掉落物）、`!Player`（挡所有玩家，含主人）、`!ArmorStand`、
  `!OwnableEntity(主人)`（挡自己的宠物）⇒ 否则活剑会去砍**盔甲架和玩家的船**。
  ⚠️ 由此**活武器不支持 PVP**（所有玩家一律不打），要放开须先定义"敌对关系"判据。
- ✅ 调度：`LivingToolFunction.canApply` 改为「工具 ∪ 武器」共用；tick 按 **S2「射线决定」** 分派
  —— **有哪条记忆射线就走哪条路**（攻击优先 ⇒ 对齐 W2"实体优先于方块"）。
- ✅ 修复（**会崩**）：`LivingToolModelRenderer#renderOne` 里 `dig != null ? dig : use` 对
  **只带攻击记忆**的活剑会拿到 null 后调 `endpointFrom` ⇒ **NPE**。改为把 `AttackMemory`
  桥接成 `RayMemory(offset, null)` 复用渲染路径；`LivingToolRayRenderer` 新增**品红**攻击射线。
- ✅ 重构：环成员口径**曾在 3 处各写一遍**（渲染 / 同步 / 辅助）⇒ 加武器时必须同步改 3 处，
  已收敛成 `LivingToolRecorder#isAssistItem`（环成员：工具∪武器）与 `#isAssistTool`（辅助挖掘：只工具）。
- 📄 纠正一条旧结论：**`AttackEntityEvent` 无需手动补**。`idea.md` §2.6 曾标为「❌ 待补」，
  核源码发现 `Player#attack` 第一行就调 `CommonHooks.onPlayerAttackTarget`，它内部已 post 该事件
  **并**调 `Item#onLeftClickEntity` ⇒ 再手动 post 会**重复触发**。
  📌 教训：**判断"事件会不会触发"必须顺着调用链查，不能只看 post 的位置**。
- 📄 文档：新建 `docs/tech/living-weapon-tech.md`（活武器独立成子系统，共享部分用指针不复制）；
  `idea.md` 加「设计探讨层」横幅 + 纠正上述结论；`AGENTS.md` 子系统索引与进展各加一行。

---

## 2026-09-22

- ✅ 修复：**大当量/超级爆炸后光照不刷新（坑里一片漆黑）**。根因：这两条路径为性能**直接调
  `LevelChunkSection.setBlockState()`**，绕过 `LevelChunk.setBlockState()` —— 原版把光照更新写在那里
  ⇒ 光照引擎不知道方块没了（`NORMAL` 走 `level.setBlock()`，不受影响）。
  ⇒ 新增 `ExplosionComponent.refreshLightAfterBulkEdit()`：重建天光柱高图 → 逐 section 上报空态
  → `propagateLightSources()` → 被炸掉的发光方块逐个 `checkBlock()`。
  **两条红线**：柱高图重建**必须早于**重算（反了 = 没修）；增光与减光是两条路径（后者只管**现存**光源）。
  新增 `ExplosionComponentLightTest`（5 项）。详见 `living-tnt-tech.md` §4.3「⚠️ 光照刷新」。
- ✅ 修复：**大当量爆炸坑不圆、边缘残留整块区块地形**。根因 `ExplosionParams.affects()` 用「区块**中心**
  在半径内」当判据 —— 区块 16×16，"中心在外、边缘在球内"的区块**建档时就被标记完成、永不处理**
  ⇒ 每场爆炸整块跳过 **32~86 个区块**。改为「**区块 AABB ∩ 球体**」（该判据在**三处**生效 = 单点根因）。
  ⚠️ 它曾被 `ExplosionParamsTest` 按"与旧口径一致"**钉住** —— **把既有行为当规格前，先确认它是对的**。
  守卫：边界回归 + **球体全覆盖** `affects_coversEveryBlockInsideSphere`
  （断言"半径内每个方块所在区块必命中"，**已验证在旧判据下会 FAIL**）。
  详见 `living-tnt-tech.md` §4.3「⚠️『哪些区块受影响』的判据」（含漏块量化表）。
- ✅ 定性：玩家报的「方形区域还画着旧方块、**过一会自己消失**」= **客户端重建排队，不是数据没同步**。
  整区块包会让客户端对**每个 section** 调 `setSectionDirtyWithNeighbors`（每次标 3×3×3=27 个），
  一次爆炸改动**上万个 section** ⇒ 排队期间仍画旧几何，排完即恢复（数据其实**立刻**就对了）。
  **判据：会自己消失 = 正常；一直都在 = bug（= 上面 `affects` 那条）。**
  同时把方块同步改到光照刷新**之前**发（方块是用户直接看得见的）。
  ⇒ `living-tnt-tech.md` §4.3 + `living-tnt-testing.md` §5「两种要分清」表（免群友误报）。
- ✅ 文档：**修掉归档规则里的「空档 ≤1 天」**（`docs/README.md` §4/§7 + `changelog.md`/本文件节首）。
  该规则**假设"每天都有开发"** ⇒ 间歇性开发必然误报，且与「保留 3 批次」**互相矛盾**（永远无法归档）。
  改为只校验**边界单调**（`changelog 最新` 必须早于 `AGENTS 最早`）；防丢历史交给**连续迁出 + 守恒校验**；
  相隔 >30 天仅提示。`doc_check.py`：`CONTINUITY_MAX_GAP_DAYS=1` → `ARCHIVE_WARN_GAP_DAYS=30`。
  并借此归档 `2026-09-16` 批次。
- ✅ 资源：**纹理去原版化（56 张）** —— 与原版字节/像素相同的纹理改为**直接引用 `minecraft:` 路径**
  并从包内删除，不再随模组再分发原版资源。**附带收益：这些图标会跟随玩家自己的材质包。**
  涂蜡铜块家族（24 张）规律实测 24/24 零例外 = **原版纹理 + 外圈 60 px 改 `(232,160,62)`**
  ⇒ 改为**模型双层**（`layer0` 引用原版 + `layer1` = 一张自绘 `wax_ring.png` 覆盖全部 24 张）。
  ⚠️ **涂蜡不能用 `IItemDecorator`** —— 装饰器只在 GUI 绘制，而涂蜡是物品身份、必须到处可见。
  `living_item` 纹理数 72 → **17**。详见 `icon-system.md`「纹理约定」+ §5.2「③ 多层叠加」。
- ✅ 文档：**图标「渲染上下文覆盖范围」写成显式约束**（原只在 §2 架构图里暗示）。
  两条机制均为**有意设计**：① `GenericContextAwareModel` 非 GUI 返回 `vanillaModel`
  ⇒ 掉落物/手持/展示框**显示原版外观**（影响**所有**走变体路径的活物品）；
  ② `IItemDecorator` **只在 GUI 内被调用** ⇒ 依赖叠加层的物品（漏斗箭头 · 雕文箭头 · 地图缩略图 ·
  耕地种子 · 红石连线）**在 GUI 之外叠加层消失**。
  ⇒ 组合后果：可能出现「只有基础层」（如活漏斗只剩中心圆）。
  「全形态一致」的 A/B/C 三方案已记录但**暂不实施**（B/C 需游戏内验证图层混合能否复现 `blit` 叠加）。
  详见 `icon-system.md`「渲染上下文覆盖范围（重要约束）」。
- ✅ **箱子/末影箱图标定稿**（2026-09-22 晚，三轮：实验 `20d6ab9` → 误判回退 → 用户拍板）：
  用 `item/chest_3d` / `item/ender_3d`（`parent: builtin/entity`，`gui rotation [0,0,0] scale [1,1,1]`）
  ⇒ GUI 显示**正面视角 14px** 3D 箱子（`ChestRenderer` 物品分支强制 FACING=SOUTH、锁扣在 +Z、
  箱体宽 14 单位）；spec **不挂装饰器**（3D 自身足以辨识「活」）；旧平面图标资源**已删**。
  ⚠️ 1.21.1 判据是 **`isCustomRenderer()`** 而非 `usesBlockEntity()`（后者此版本不存在）。
  详见 `icon-system.md`「已完成的实验」。
- ✅ **GUI 图标光照统一**（2026-09-22 晚，起因 3D 箱子在 GUI 里发暗）：
  ⚠️ **改 light 值没用** —— `GuiGraphics.renderItem` 传的本就是 `FULL_BRIGHT(15728880)`；
  真凶是 `!usesBlockLight()` 分支（true ⇒ `setupFor3DItems()` 法线漫反射，3D 图标亮度只剩 ~0.7）。
  ⇒ `GenericContextAwareModel.usesBlockLight()` **恒 false**（该属性 1.21.1 只被 GuiGraphics 读）。
  另抽出 `LivingIconRenderHelper.renderBlockIcon()` 作为「容器内手绘方块模型」图标统一入口
  （FULL_BRIGHT + 强制 cutout + 异常隔离，替换 mixin 私有实现）⇒ **新加图标光照零配置**。
  ⚠️ **约定：每个 `BakedModel` 包装类都必须 `usesBlockLight() → false`**（原先三个类各手写一遍，
  活水车漏了 ⇒ 3D 水车在 GUI 里发暗，已修；唯一例外是 `GenericLivingModelWrapper` ——
  它 resolve 后不参与渲染）。
  详见 `icon-system.md`「GUI 图标光照约定」。
- ⚠️ 回归（2026-09-22）：**活红石粉装饰器叠了缺失纹理** —— 原因：去原版化时删了
  `textures/item/redstone_dust_dot.png`，但 `LivingRedstoneDecorator.DOT_TEXTURE`
  仍 `fromNamespaceAndPath(MOD_ID, "textures/item/redstone_dust_dot.png")`，**渲染时
  变成黑紫占位色**。**修复**：DOT_TEXTURE 改为引用 `minecraft:block/redstone_dust_dot`
  （白点），与 `models/item/redstone_dust.json` 的 `layer0` 用同一张纹理 ⇒ 模型层画白点
  + 装饰器 `setColor` 染色 = 可见红色点。`redstone_dust_line0.png` 是去底色优化版（md5
  与原版不同），保留。
  ⚠️ **教训（已写进 `icon-system.md`）**：去原版化扫描必须包含 `fromNamespaceAndPath(MOD_ID, ...)`
  —— 该写法不带 `living_item:` 前缀，按 `living_item:textures/...` 扫是扫不到的。
- ✅ 修复：**大箱子（多方块容器）两套槽位体系错位 ⇒ 活漏斗静默不传输**。症状：大箱子里上下
  摆两个「上传下」漏斗，上方**完全不传**；拿走下方、或往输出槽放一个物品都能恢复；下方换
  **任何朝向**都照样"锁住"（**决定性线索** —— 与方向/连接关系无关，只与那个格子有没有东西
  有关）；同布局在单方块箱子正常。根因：`ContainerContext.getContainer` 只返回 pos 那一个 BE
  的容器（大箱子 = 单半箱 27 槽），而 handler 是合并的 54 槽 ⇒ **错位 27**（Container 槽 22 =
  GUI 槽 49）；`simulateInsertItem` 用它读"目标槽现有物品" ⇒ 读到另一半箱的漏斗 ⇒ 判不可插入
  ⇒ 不传且不设冷却。`PlainSlotAccessor` 只在**目标槽为空**时调模拟插入（非空走合并分支），
  这解释了"放一个物品就好"以及它为何藏这么久。⇒ 新增**槽位体系一致性探针**
  `isSameSlotSpaceAsHandler`（①槽位数一致 ②单槽交叉校验），不过则回退 handler；
  **不**在 `getContainer` 里特判合并容器 —— 合并顺序由各 mod 的 IItemHandler 决定，猜错就是
  一道**新的**错位；探针不假设容器结构，三方块/四块/任意多方块容器通用。回退不丢语义
  （`handler.insertItem` 内部转调 `canPlaceItem/isItemValid`，且模拟与真实由此**同源**）。
  ⚠️ **教训**：多方块容器下"按逻辑槽位"的读写，**先验证两套体系是否同编号再使用**；
  模拟与真实写入必须同源。详见 [living-hopper-tech.md](docs/tech/living-hopper-tech.md) §10.25。
- ✅ 修复：**大箱子跨容器传输的面选取 —— GUI 4 方向 → 世界 6 面**。本质：两个半箱共 8 个侧面，
  贴合的 2 个是内部面，剩 **6 个外部面**；左/右（连接轴）各只对应 **1 个**外部面（另一端是
  贴合面，被现有防护挡掉），上/下（facing 轴）各对应 **2 个**外部面（两个半箱各一个，是
  **两个不同方块**）。旧实现按**方向**选单个基准块（下/右→第一块，上/左→第二块），与漏斗
  所在槽位无关 ⇒ 漏斗在后半箱向下推送时，推出去的是**前半箱正面**的方块（**静默推错面、
  不报错**；而左右选错会被内部防护挡住，反而"看得见"）。⇒ 改为 `getBasePosCandidates()`
  返回**候选基准块列表**：首选 = 发起槽位所属那块（`hostSlot / (size/块数)`），其余作备选，
  **逐个尝试**（用户拍板"两个都试"，顺带让大箱子上下能覆盖两个面）。**附带收益**：有备选面
  兜底 ⇒ 不必再为「槽位段 ↔ 位置顺序」做内容探测，假设反了最多影响优先级（对比容器内路径
  §10.25 —— 那条路没有备选，必须靠体系探针）。另加"源/目标解析到同一邻居则跳过"防自传。
  详见 [living-hopper-tech.md](docs/tech/living-hopper-tech.md) §6.4。

## 2026-09-20

- ✅ 修复：**容器规则数据勘误 + 玩家差异持久化 + 开发期导出通道**。起因「打包后没有配置文件」
  —— 实为 jar 内 `assets/living_item/container_rules.json` 里 7 条 IronChests 规则**全错**：
  命名空间写成 `ironchests`（实际 `ironchest`，取自 `IronChestsItems.MODID`）、槽位数 45/36/54/63/72
  **全是编造**（真值 54/45/81/108/108）、`silver_chest` **该模组不存在** ⇒ 这 7 条**从未生效**。
  真值取自 `libs/ironchest-1.21-neoforge-16.0.7.jar` 字节码 `IronChestsTypes.<clinit>`。
  ⇒ 三条机制改动：① `save()` 只写**玩家差异**（新增/覆盖/删除），不再全量回写内置副本；
  ② 新增 `removed` 字段——**删内置规则必须落盘**，否则 `load()` 会把它「复活」
  （`load()` 同时改为幂等：先 `resetState()` 再重建）；③ `/livingitem container export`
  把玩家规则导出为内置资源同格式 JSON，供合并进 `src/main/resources/` 随包发布。
  **jar 内文件运行时不可写**是 classpath 硬限制 ⇒ 导出是唯一路径，正式环境不参与写入。
  `register` 同时放开为**允许覆盖**（此前拒绝，导致玩家无法修正错误内置数据），
  `inspect`/`list` 显示规则来源（内置/玩家覆盖/玩家注册）。新增 `ContainerRuleConfigTest`（9 项），
  全量 **341 用例全绿**。详见 [living-item-infrastructure.md](docs/system-design/living-item-infrastructure.md)
  §5.2/§5.2.1/§5.2.2 + [container-compatibility.md](docs/guides/container-compatibility.md)。

- ✅ 文档：**新增《活TNT测试说明》给测试者用** —— [living-tnt-testing.md](docs/guides/living-tnt-testing.md)。
  18 个编号用例（`T-01`~`T-18`）+ 「提测最小集」。**这是面向测试者的操作说明，不是技术文档**：
  不含实现细节，只写"怎么做 / 期望看到什么 / 看着像 bug 其实不是"。
  重点压在三处**只有真机才能验**的行为：① `T-08` 超级爆炸**服务端不冻结**；
  ② `T-09`/`T-10` **未加载区块走近后补炸**（含存档往返）；③ `T-17`
  `/living_monitor cache` 观测 `loaded区块` 站桩不动**不增长**。
  另附「已知的'看着像 bug 其实不是'」表（分帧扩散消失 / 大当量无掉落物 / 声光先于破坏），
  减少无效反馈。口径以 [living-tnt-tech.md](docs/tech/living-tnt-tech.md) §4.3 为准。
- ✅ 文档：**测试说明按技术文档返工定稿** —— 群友区 `T-01`~`T-13`（原 18 条砍到 13 条，
  删掉"不点燃就不炸""不能重复点燃"等**过于简单/可从代码推出**的条目）；作者区 `A-01`~`A-12`。
  **核心修正（三处此前写错）**：
  * **抗爆逐模式不同**：`NORMAL` 硬编码 `≥100` 跳过（**与半径无关**，黑曜石永不没）·
    `HIGH_YIELD` 判据 `威力 = 半径×(1−距离/半径)×2`，**半径越大越能炸穿**（3456 个时中心威力约 470，
    仍 < 黑曜石 1200）· `SUPER` **不看抗爆值、整区块抹除**（基岩也留不下）。
  * **流体参与抗爆判定**：两种非 SUPER 模式取 `max(方块抗爆, 流体抗爆)`；原版水 = **100**
    ⇒ 普通模式炸不掉、大当量半径够大能清掉。
  * **删掉编造内容**：「活箱子能引爆内部活TNT」「>6912 需活箱子超堆叠」——
    代码依据 `LivingChestFunction.tick()` 是**空实现** ⇒ 活箱子里的活TNT**不被 tick、引信不倒数**。
    超级爆炸的**唯一**触发路径是**铁箱子（模组自带 `container_rules.json` 兼容）塞满**：金箱 81 槽 = 5184、
    钻石/水晶/黑曜石 108 槽 = 6912（覆盖率见 §3 容量对照）。

- ✅ 特性：**容器兼容性社区贡献流程打通（文件级覆盖）**。`export` 改为导出**全量生效快照**
  （内置 + 玩家新增 − 玩家删除，按 ID 排序）而非差异 ⇒ 玩家导出的文件**可直接整文件覆盖**
  `assets/living_item/container_rules.json`，作者无需逐条摘录或写合并脚本，
  重新打包即把兼容性发给所有玩家 —— 「玩家共同完善容器兼容性」从设想变为常规机制。
  配套：`countBundled()`/`countUser()` 统计来源；加载时**重复定义冲突检测**（先到先得 + WARN，
  `differs()` **只比 `containerSize`/`columns`**，描述措辞不同不算冲突，否则被假冲突淹没）。
  解析逻辑抽为 `loadRules(Reader, source, label)`（包级可见），使**作者的覆盖加载路径与内置资源加载
  共用同一套解析+冲突检测**，同时让测试能直接喂入模拟快照验证闭环。
  ⚠️ **修掉一个真 bug**：`save()`/`export` 用 `FileWriter`（平台默认编码，中文 Windows = GBK），
  含中文描述的导出文件拿给 UTF-8 环境会 `MalformedInputException` ⇒ **跨平台交换必锁 UTF-8**，
  三处 IO 全改显式 `StandardCharsets.UTF_8`。
  `ContainerRuleConfigTest` 扩到 **11 项**（新增「导出快照可原样当作内置资源重新加载，无丢失」
  与「合并冲突先到先得、描述不同不算冲突」两条闭环守卫），全量 **343 用例全绿**。
  详见 [living-item-infrastructure.md](docs/system-design/living-item-infrastructure.md) §5.2.2。

## 2026-09-18
- ✅ 重构：**爆炸破坏改为「按区块分帧 + 待炸账本」**（承上：死锁与强制加载防护之后的第三块）。
  原实现三种模式各写一套"一次性遍历整个球体"，而半径 `= 4.0 × √TNT数`（64 TNT 跨 5×5、
  3456 TNT 达 **31×31 = 961 区块**）—— 球体一部分可能**未加载**：当场读会强制加载
  （单次 289 区块足迹 + 主线程阻塞 + 票据钉住 + 传染其他模组），直接跳过又让**语义残缺**
  （同一场爆炸，玩家站的位置不同、结果不同）。⇒ 采用**原版 TNT 引信模型**「世界只在被观测的
  地方演化」：三种模式统一为**逐区块**执行，唯一入口
  `ExplosionComponent.applyToChunk(level, params, chunk)`；爆炸瞬间只登记
  `ExplosionParams`（参数 + 位图索引映射）+ 声光 + 实体伤害，破坏交给
  **`ExplosionLedger`（世界级 `SavedData`，参数 + `long[]` 完成位图）** —— 已加载的按预算
  每 tick ≤32 个区块分帧应用，**未加载的等它自然加载时补上**（`ChunkEvent.Load` 只登记坐标，
  走 tick 阶段应用，符合两条红线）。顺带修掉两个既有 bug：① 旧
  `PENDING_SUPER_EXPLOSIONS` 服务端关闭**不清理** ⇒ 单人换存档（不重启 JVM）持有旧
  `ServerLevel` 内存泄漏；② `tickAll` 每 tick 只处理 1 个区块、且跳过未加载时无条件推进
  （静默残缺）。新增 `ExplosionParamsTest`（4 项）+ `ExplosionLedgerTest`（9 项），
  全量 **332 用例全绿**。详见 [living-tnt-tech.md](docs/tech/living-tnt-tech.md) §4.3 +
  [living-item-infrastructure.md](docs/system-design/living-item-infrastructure.md) §3.2.2 +
  decisions.md `D-tnt-01`。
- ✅ 修复：**Create 跨区块传送带导致服务端线程死锁**（1.3.2 + Create 6.0.10，进世界即卡死：
  GUI 能动、不能合成/传送、玩家掉虚空）。根因：`ChunkEvent.Load` 回调里做能力查询 →
  Create 传送带 provider 去查**另一个区块**的 BE → 同步区块加载 → 自等自（同区块不卡，
  因为原版 `currentlyLoading` 旁路只覆盖正在加载的那个区块）。修复：`onChunkLoad` **只登记
  坐标**，扫描推迟到 `ServerTickEvent.Pre`（每 tick 限量 64）。详见 §3.2 + `D-core-04`。
- ✅ 新增：**强制加载防护（处理侧限定 ticking 区）+ 可观测性**。被动路径同样会强制加载：
  活物品读**相邻一格**（红石 `getSignal` / 活漏斗邻居容器 / 大箱子另一半 / 活水车下方一格），
  而框架取 **loaded 区（≤33）** ⇒ 容器在"已加载但不 tick"的**最外一圈**时邻居在**未加载**区
  ⇒ 单次要凑 **289 区块**足迹、且票据续期会把邻居**永久钉住**在视距外。修复：
  `getProcessableChunks(ServerLevel)` 处理前过 `isPositionTicking`（差别**恰好只有危险带那一圈**；
  ticking 区块的 **3×3 邻域必为 FULL** ⇒ 本模组入口**可证明安全**、外扩链条被切断）。
  ⚠️ **扫描不做此过滤**（区块提升到 ticking 没有对应事件）。新增 `describeCacheStats` +
  调试命令 **`/living_monitor cache`**（缓存/可处理/loaded 区块/视距基准）⇒ 钉住与外扩可测量。
  详见 §3.2.1 + `D-core-05`。⏳ 未做：**爆炸路径**（§3.2.2）、**第三方 provider 伸远**（不可控面）。
  顺带修掉 §3.2 两处旧口径与一处错位/重号小节。


> 由实际踩坑沉淀的行为准则 —— **每次会话都适用**，不是某个子系统的细节。

### ⭐ 严谨要保留，但别把简单问题做复杂（2026-09-19 用户反馈）

> 用户原话：*"你的细心很好，就是容易把很简单的问题想得复杂了。"*

**症状**：为了「更精确 / 更一致 / 更健壮」，在**渲染侧**堆出第二套逻辑 ——
而这套逻辑既不影响功能正确性，也从来没人要求过。

**同一个毛病犯了两次**（活工具射线，见 `docs/tech/living-tool-tech.md` §11.3）：

| 轮次 | 我多做的事 | 用户回应 |
|---|---|---|
| 1 | 在客户端**复刻**服务端的三层命中判据（宿主黑名单 / 流体 / 形状求交） | "根本不需要呀" |
| 2 | 拿到结果后又要求**精确交点**，把"格子中心"细化成"表面点" | "客户端渲染根线要算什么交点" |

**根因**：把「渲染」当成「逻辑的第二份实现」，而不是「已有数据的呈现」。

**动手前先自问三句**：

1. 我要用的这几个值，是不是**现成的**？（是 → 直接用，别推导）
2. 我多加的这一层，**错了有没有后果**？（渲染画错**没有**功能后果 → 不值得加）
3. 这个需求是**用户提的**，还是**我自己发明的**？

**分界线**（活工具射线 `L48` 定案，可作同类问题的模板）：

> **判据归服务端（唯一权威），几何归深度测试（免费），客户端只做「两点连一条带子」。**

**另一个要警惕的惯性**：修 bug 时倾向**加固**，而不是**回退到更简单**。
出错后先问「**是不是根本不该做这件事**」，再问「怎么才能做得更像」——
顺序反了就会在错误的前提下越优化越复杂。

---

## 2026-09-16
- ✅ 新增：**活锄头跨模组兼容 + 可耕土扩展**——原先「活锄头」是**写死的 6 种原版锄头**
  （注册侧枚举物品 + 校验侧 `instanceof HoeItem`，两侧口径还不一致），其它模组的锄头
  活化后**耕不了活泥土**（客户端根本不拦截）。改为语义判定：新增
  `domain/farmland/Tillables`（唯一真源）—— 锄头只认
  `canPerformAction(ItemAbilities.HOE_TILL)`（NeoForge 对自定义工具的官方口径：原版
  锄头经 patch 自动满足，模组锄头重写 `canPerformAction` 即自动兼容；**不做**
  instanceof / 标签 / 配置兜底）；可耕映射对齐原版 `IBlockExtension#getToolModifiedState`
  的 HOE_TILL 分支（以 neoforge-21.1.249 源码为准：**泥土/草方块/土径 → 耕地，砂土/
  缠根泥土 → 泥土**，再耕一跳才变耕地；灰化土/菌丝原版不可耕，不纳入；「上方必须是
  空气」与「缠根泥土掉垂根」属世界副作用，物品层 GUI 交互不做）。注册处 6 条精确规则
  → **按可耕目标逐条注册通配条目 + `Tillables::canTillWith` 谓词**（与 plant_crop
  同构）；谓词内**必须**自查 `isLivingItem(trigger)`——通配分支不校验活物品，漏了会吞
  掉原版拿起/分堆。handler 改为查表取产物，且创造模式同样校验光标（只免耐久消耗）。
  新增 `testutil/FakeHoe`（模组锄头替身：⚠️ **测试期物品注册表已冻结，不能 `new Item`**，
  否则静态初始化抛 `Registry is already frozen` 导致整类 17 用例全红且看不出原因——改用
  `Mockito.spy(Items.STICK)` + stub `canPerformAction`）+ `TillablesTest`（8 项）+
  `TillToFarmlandCompatTest`（8 项），全量 **313 用例全绿**。收编
  living-farmland-tech.md §3.1/§10.1/§10.2/§10.3/§11.17/§12.1 +
  gui-interaction-system.md 处理器表 + file-map.md。
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

## 2026-09-15
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

---

## 2026-09-14

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

---

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

## 2026-09-13

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

---

## 2026-09-11

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

---

## 2026-09-09

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
  第 3 条升级为双先例通用规则 + ../tech/living-fluid-tech.md 验证清单补大箱项
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

---

## 2026-09-08

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

---

## 2026-09-07

- ✅ 审计：**超大堆叠表现探查**（[oversized-stack-audit.md](docs/system-design/oversized-stack-audit.md)）——
  模组容器（抽屉等）堆叠上限 > 64 场景下，全库 202 处堆叠敏感调用点逐领域源码分析 +
  原版语义对照。结论：传输基础设施层干净（统一 min(slotLimit, maxStackSize)，吞吐按
  模组上限走）；14 项设计内缩放（TNT √count / 熔炉 count 倍速 / 红石 count² 信号上限——
  原版消费端 j>=15 早退钳制天然饱和 / 铜灯 mFE long 定点）；1 个真实缺陷（超堆叠岩浆桶
  燃料整槽替换吞 N−1 个——原版同吞但不可达，模组容器使其显形，修复方向待决策）；
  2 个理论 int 溢出（铜灯对外 int FE 收窄 count>214 万盏 / TNT 累加 >21 亿——均 fail-safe
  不崩不刷）；2 个吞吐观察项（漏斗单次固定 64 不随目标容量自适应）

- ✅ 修复：**活熔炉岩浆桶整桶被吞**——`tickFuel` 消耗燃料只 `shrink(1)`，漏掉 crafting
  remainder；燃料消耗收拢到 `consumeFuel()`，对齐原版熔炉点燃语义（带残留物的燃料
  整槽替换为残留物：岩浆桶→空桶；普通燃料扣 1 个、耗尽清空）。回归测试
  LivingFurnaceFunctionTest（4 项），全量 205 用例全绿（基线 183 + 红电工作区未提交用例）

- ✅ 重组：**活潜影箱从红电系统剥离为独立基础设施**——红电系统.md 第 2 章（Phase 2）
  整体迁出（原章节留编号存根，§3.x 与外部锚点稳定）；总览/里程碑/风险表/复用表同步更新；
  idea.md 的活潜影箱创始定义一并合并。新文档 **活潜影箱实现细节.md**（v2.0）：
  基础设定（兼容**所有活物品**而非当前已实现集合——通用活物品基础设施，随新活物品
  逐个补充嵌套实现细节）+「最伟大的活物品」愿景 + 创始定义 + 边界连续性/芯片封装
  （原红电 §2.2~2.7）+ 局部演化+边界交换模型（旧全局视图方案否定）+ 2D↔3D 桥接
  （世界引脚面）+ 与红电的单向协同说明 + **§8 各活物品嵌套细节台账**（活物品实现
  时的补充主线：漏斗/红石/熔炉/水桶/TNT/箱子/末影箱/水车/铜块/避雷针逐格待补）

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

---

## 2026-09-05

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

---

## 2026-09-04

- 🐛 **修复：游戏崩溃 —— `living_item_sync` 包方向注册错误** —— 打包 jar 里注册成了 `playToServer`，但它是**服务端 → 客户端**包（`flushToClients`/`sendSnapshotToPlayer` 都在服务端调 `player.connection.send()`，`handle()` 更新客户端缓存 `LivingItemClientCache`），被 NeoForge `NetworkRegistry.checkPacket` 拦截抛 `UnsupportedOperationException: Payload living_item:living_item_sync may not be sent to the client!`。当前源码里该包连注册都没有（调试时被删）。修：`LivingItem` 加 `registrar.playToClient(LivingItemSyncPacket.TYPE, STREAM_CODEC, LivingItemSyncPacket::handle)`（放在 `LivingChestAccessPacket` 之后）
- ✅ **全包方向审计（防同类复发）** —— 10 个包逐个核对发送点与 `handle()` 归属，结论**只有 `living_item_sync` 一个错**：→服务端 6 个（`LivingTag`/`HopperDirection`/`SlotDirection`/`GuiInteraction`/`LivingMapGuiTeleport`/`LivingChestAccess`，均在 `client/**` 经 `PacketDistributor.sendToServer`）、→客户端 4 个（`CarriedUpdate`/`EnderChannelSync`/`LivingMapMetadata`/`LivingItemSync`）。反编译产物验证 playToClient×4 + playToServer×6 = 10 个注册已进 jar
- ✅ **测试基线修正** —— `AGENTS.md` 原写「146 项」是**方法数**（141 `@Test` + 5 `@ParameterizedTest`）；实际执行 **169 用例**（参数化展开 +23）。基线 `169 passed / 0 failed / 0 skipped`
- 📄 排查套路留档：① `grep -n "Exception\|Error\|FATAL" latest.log | tail -30` 直接跳崩溃段；② 崩溃栈**行号要回源码核对**（对得上=jar 与源码结构一致，可放心按源码修）；③ NeoForge 方向报错话术「may not be sent to the client」= 注册成了 `playToServer`；判方向看两点——谁调 `send()`（`player.connection.send` = 服务端发）、`handle()` 更新哪侧缓存

---

## 2026-09-03

- 🐛 **Bug A 修复：比较器链到第 4 个就死** —— 根因三者叠加：`reset()` 每 tick `edgeGrid.zero()`（无跨 tick 累积，链断不自愈）+ `powerConductiveNeighbor` 对元件位（含 `BIT_COMPARATOR`）直接 return（上游比较器不把下游入队）+ `comparatorSlots` 是 `HashSet`（迭代顺序是**哈希桶序而非槽位序**）⇒ 能亮几级 = HashSet 顺序碰巧与链几何吻合的长度，换摆放/方向就变。修：`phase4PowerConductors` 改**迭代到稳定**（`for (iter=0..size+2)`，仅 output 变化才 `edgeGrid.set` + 下游入队，无变化 `break`）。稳态无依赖变化时一轮即停、零额外开销
- 🐛 **Bug B 修复：无外接信号时容器内信号层全死** —— 根因：`bumpContainerRevision` 只被本模组写入路径（`SimpleContainerContext.setItem` / `syncSlotToClients`）调用，**原版玩家点击/漏斗/掉落物拾取直接改底层容器、绕开它们** ⇒ 修订计数停滞 → `calculate` 稳态跳过永不打破 → 快照缓存永不重建 → 信号层集体死。修：`ContainerLivingItemHandler` 新增 `CONTAINER_CONTENT_SIG`（逐槽位 物品 id+数量 的内容签名，**空槽参与混合**保证槽位移动可检出）+ `computeContentSignature` / `syncContentRevision`，在 `processContext` 槽位扫描后调用（复用已有扫描、零新开销）
- ✅ **P1-4 落地：7 张静态 Map 合并** —— 六张同键 Map 合成 `Map<String, ContainerEntry>` + 嵌套 `ContainerEntry`（fluid/redstone/power/revision/contentSig/cachedSnapshotRevision/cachedSnapshot）；`cleanupStale{Fluid,Redstone,Power}Data` 三法合一为 `cleanupStaleData`；`clearAllCaches` 7 次 clear 收敛为 2 次；删约 120 行
- ✅ **P1-5/6 落地** —— `processContext` 主路径 + 空容器分支用 **try/finally** 包住（原先功能 `tick()` 抛异常会跳过 `flushDirtySlots`（客户端脏槽不同步）+ `setTickContext(null)`（stale 上下文泄漏），且 `onServerTick` 无 try/catch 向上抛）；`scanAndGroupLivingItems` / `runContainerDataTicks` 安全提取为私有静态
- ✅ **代码屎山复查（用户以为很脏，实为文档过时）** —— P0 三处中**两处是误报**：`setItem`「Container 优先 / 丢物品」实际自 2026-08-17 起**故意**统一走 `IItemHandler`（大箱子左右两半共享同一 handler 但各是独立 `Container`，走 `Container.setItem` 会**双记账 → 物品复制 bug**）。**⚠️ 千万别照旧文档 §7.2 的伪代码去补 Container 优先写入，会重新引入复制 bug。** 已改文档并加警告。第三处（`syncWorldContainer` 用 `==` 引用比较匹配槽位）是**真问题**但缠双箱子索引映射，需单独正经修
- ✅ **自由巡检（资源完整性 + 文档漂移扫描）** —— 修真 bug：`tooltip.livingitem.transform.no_recipe` 在 `zh_cn.json` 缺失（en_us 有）⇒ 中文客户端直接吐原始 key，已补；修复后 en/zh 均 169 key 零差异。排除 4 处误判：8 个 `*_lit.json` 带 BOM（Gson 2.10.1 无条件消费 BOM，不是 bug）、`getLastTickTime` 每 tick 刷新（非「120 秒重置」）、`ContainerMonitor` 首行短路（零开销）、lang 拼接 key 的正则误报
- ✅ **项目整洁化清理** —— 删 `_lit_broken_backup/`；修 8 个 `*_lit.json` 的 BOM + 混合换行。**关键发现：BOM 从不存在于 git**（`core.autocrlf=true` 把工作树 CRLF 归一为仓库 LF，HEAD 版本头字节无 BOM），只在工作树由某 Windows 工具改写产生且从未 `git add` ⇒ `git diff` 为空是预期、不是改动丢失。同步文档测试索引（146）+ 5 个文档的旧字段名 `*_DATA_CACHE` → `CONTAINER_DATA`。撤销「取消跟踪 `.trae`/`.zcode`」（用户要求保留）
- ✅ **BOM 深挖结论：不是 bug** —— gson `JsonReader.fillQueue()` 无条件消费 BOM 且**与 lenient 开关无关**，任何解析路径都跳过。**更有价值的副产品**：那 8 个文件**换行是混的**（6 个换行里只 1 个 CRLF），与同目录其余文件（规整 CRLF 无 BOM）不同生成路径 ⇒ **只清文件不找生成源会复发**
- 📄 技术文档：`living-power-tech.md` v4.2、`living-redstone-tech.md` v15、`living-item-infrastructure.md` §7.2（加「别加回 Container 写入」警告）

---

## 2026-09-02

- ✅ **红电「网络级共振」设计定稿 + 实现完成** —— 锈蚀级 = 共振「**声部**」，共振发生在**网络之间**而非铜块之上（把块级感应机制原样升维）。`N = |{k : ema[k] > 0}| ∈ [1,4]`、`s = (Π Aₖ)^(1/N) ÷ (Σ Aₖ / N)`（几何平均 ÷ 算术平均）、`R = 1 + (N−1)×s ∈ [1,4]`、总发电 = `(Σ baseRe[k]) × R^exp`（`RESONANCE_EXPONENT = 2`，满共振 ×16）。**铁律：共振只读「共振前」基础值、单遍前馈、绝不回代**（否则形成 `R↑→EMA↑→s变→R变` 回代环）；**每锈蚀级 EMA 只跟踪基础出力**。声部单位 = **锈蚀级**（不是 BFS 连通块）——同锈蚀级多个连通块出力**直接相加**，这是 `R ≤ 4` 封顶的结构性前提（否则拆簇即可刷 N，退化为 N×(N−1) 膨胀）。**测试中发现的关键修复**：EMA 指数衰减永不到 0 ⇒ 停发的锈蚀级会永远顶「活跃声部」把 s 压死、共振无自愈 → 加 `EMA_EPSILON = 1e-6` 归零截断。新增 `NetworkResonanceTest`（16 例：GM/AM 标度无关、R² 场景、凑弱网不划算、500 tick 收敛有界、停发声部 EMA 归零自愈）
- ✅ **活涂蜡铜块 tooltip：共振显示 + 排版分区 + 示波器面板** —— `LivingWaxedGeneratorData` 新增 4 字段（`resonanceGain`/`resonanceBalance`/`activeVoices`/`voicePower`，`buildTelemetry` 从 `ContainerPowerData` **只读** EMA 基础值填充、绝不回灌）；tooltip 分 4 区（`core` 常显 → `resonance` 共鸣概览 → `settlement` F3+H 公式+短板诊断 → `network` 容器级）+ `§8─── 暗紫小标题 ───` 分隔线。新增 `LivingWaxedCopperTooltipComponent`（纯数据 record，零额外同步）+ `LivingWaxedCopperTooltipRenderer`（172×62 深色示波器面板）。**关键发现**：NeoForge `ClientHooks.gatherTooltipComponents` 把 `ItemStack.getTooltipImage()` 返回的组件插在**索引 1**（物品名之后），无法满足「置底」⇒ 改用 `RenderTooltipEvent.GatherComponents` 向 `event.getTooltipElements()` 追加
- 🐛 **示波器相位波形修复（用户反馈「一直满格看不出波形」）** —— 根因：`phaseDeltas` 取的是 `DomainSnapshot.deltaByOffset().values()`，即「偏移 → |Δ|」的**离散映射**（本质是柱状图数据源、不是随时间变化的连续波形）；网络对称时各偏移 |Δ| 相等 ⇒ 按 `maxDelta` 归一化后每根柱满高。修：左半区改画**多相交变正弦波形**（按相数 n 画 n 条相位错开 `2π·ph/n` 的正弦，横轴覆盖 `SCOPE_CYCLES=2` 个周期），字段 `phaseDeltas` → `phaseCount`。`DomainSnapshot.deltas()` 仍保留（F3+H 文本诊断用）
- 🐛 **信号层 bug 修复：比较器/中继器输出只能传一格** —— 根因：`powerConductiveNeighbor` 的早退掩码 `MASK_REDSTONE` 含 `BIT_DUST|BIT_COPPER`，把比较器输出进 dust/copper 网络的路掐掉，且其后 `if (is(n2, BIT_DUST)) secondQueue.add(n2)` 是**死代码**（到不了）⇒ 输出只写到比较器自身共享边（紧邻一格亮），下游不入队不续传。修：放宽掩码为仅挡元件位（TORCH/BUTTON/LEVER/REPEATER/COMPARATOR/BLOCK）+ 把 dust/copper 邻居加进 `secondQueue` 二次传播。**幂等**（只增不强、无死循环），中继器同类 bug 一并修复
- ✅ **信号层边模型重构 v15：共享边 → 每槽自有出边** —— **Step A**（新增 `inputAt`/`maxInputOfSlot`/`anyInputOfSlot`/`oppositeDir` 抽象层，把所有「读某槽某方向受到的信号」调用点全部迁移；`inputAt(slot,dir) = edgeGrid.get(neighbor, oppositeDir(dir))`；迁移后 16 例全绿证明行为等价）；**Step B**（`EdgeGrid` 由 `hEdges[height*(W+1)] + vEdges[(H+1)*W]` 改 `edges[slotCount*4]`，写方无需改动）。配套**删掉 phase3 比较器 `!=` 兜底**（共享边下会误压下游粉的 -1 衰减信号；每槽出边下该边只归比较器自己所有、无污染，且关闭时出边归零 → 下游粉下一 tick 经 `maxInput` 自然衰减，避免「卡在高电平」回归）。`living-redstone-tech.md` 升 v15
- ✅ **信号层稳态跳过优化（steady-state skip）** —— 物品修订计数 + 外部输入签名（`Arrays.hashCode(faceInput)`）+ 无在途倒计时定时器三者都不变时，复用 edgeGrid 跳过整段 `calculate`（含 BFS）。**关键坑（已修）**：`recordSteadyState` 原先记录方法**开头采样**的 rev，但传播中 phase5/phase3 经 `syncSlotToClients` 会 bump 修订计数 ⇒ `lastRev` 落后一拍、下一拍开头采到的 rev 永远不等、**跳过几乎无法触发**。修：改为重采样**重算后**的 `getContainerRevision`，与下一拍开头采样对齐。安全性不变量：物品变更 bump rev（打破）、邻居/原版红石变化改变 externalSig（打破）、唯一不受两者驱动的逐 tick 演化是定时器倒计时（用 `lastHadActiveTimers` 保险，倒计时在跑就强制重算，绝不冻结时序）
- ✅ **电力层「按网络组件遍历」重构：回归测试 + 文档同步** —— 新增 `NetworkTraversalTest`（3 例：`copyFrom` 深拷贝独立性 / 同连通块两机共享历史且总发电 = 单机×2 / 相邻不同氧化级互不共享）。**测试 seam（生产小改动）**：`ContainerRedstoneData.setEdgeForTest`/`setPrevEdgeForTest` + `ensureTestGrid()`，供电力层单测绕过完整传播直接注入上升沿驱动 `tickContainerData`（`tickContainerData` 只读 `getEdgeValue`/`getPrevEdgeValue`，原无写边 API）。`living-power-tech.md` 升 v4.1，全量文档把「逐发电机 BFS」改为「按网络组件遍历（锚点 BFS + 其余 copyFrom 共享）」
- ✅ **活末影箱 tooltip 高级模式增强（F3+H 路由明细）** —— 每条路由显示**维度 + 坐标**；玩家背包注册的路由优先显示玩家名（服务端按 UUID 解析 `getGameProfile().getName()`），离线回退 UUID。约束留档：活物品体系**不 force-load 区块**，路由只在源/目标区块同时加载时完成，源卸载时条目被丢弃、重载后自愈
- ✅ **涂蜡铜灯发光图标像素修复** —— 4 个 `waxed_*_copper_bulb_lit.png` 黄框完全丢失 + 内部乱码。约定（已确认）：**涂蜡 vs 未涂蜡的唯一区别 = 外圈 60 像素黄框 `(232,160,62,255)`，四种锈蚀级的边框掩码完全相同**；修法 = 内部像素取正确的未涂蜡发光图 + 外圈填黄。破损原图备份于 `_lit_broken_backup/`
- 📄 技术文档：`living-power-tech.md` v4.1（§3.6.1 / §3.7 共振公式 + 铁律 + 场景表；§3.2 周期估计扩写「盲测 + EMA α=0.5 + 长周期为何慢」；§3.3.1 网络涌现多相 + §3.3.1.1 相位错开的真实来源）、`红电系统.md`（§3.2 过期「耦合强度 1.0/0.7/0.5/0.35」改「共振声部」+ v17.1 弃用警示）、`living-redstone-tech.md` v15、`living-ender-chest-tech.md`

---

## 2026-09-01

- 🔍 **活末影箱「玩家绑定频道」需求 —— 架构分析（分析完成，尚未实现）** —— 需求：绑定玩家的活末影箱，堆叠数 = 1 时直连玩家末影箱；堆叠数 **≥ 2** 时变为该玩家专属频道（阈值用户已拍板，非 >2）。核心结论：本质是**频道键从 `int` 升维为复合键** `EnderChannelKey(@Nullable UUID owner, int count)`（`owner == null` = 公共频道），牵连约 8 个文件
- ⭐ **用户关键澄清（重大简化）：专属频道是「命名空间隔离」，不是「权限隔离」** —— 原话「公共频道是一块黑板，专属频道就是另一块黑板，其实并不需要防着其它玩家。玩家 a 有玩家 b 的专属活末影箱，也是可以使用的。」⇒ 绑定信息在 DataComponent 里跟着物品走，**谁持有物品谁就能接入那个频道**，不做持有者鉴权。**该澄清直接砍掉了整条同步层改造**：无需定向投递、无需 `PlayerLoggedIn` 全量补偿、无需处理离线黑洞，`flushDirtyChannels` 保持广播即可
- 🔍 **读码确认的坑（留档待实现时处理）** —— ① `LivingEnderChestAccessor.tryCreate()` 绑定分支把 `filterData` 硬设为 null（旧逻辑「绑定=直连」）⇒ 专属频道是路由模式、过滤要靠 `registry.peek(channel, filterData, ...)`，**不修则黑白名单在专属频道完全失效**；② `EnderChannelRegistry.shouldRemoveRoute()` 只问「槽位上还有没有末影箱」不问「是不是同一个频道键」⇒ 末影箱 count 变化（如 `(A,3)→(A,1)`）后旧频道路由三条检查全通过、**永久残留**（修法：`validateRoutes` 第三参数升级为 `Map<Integer, EnderChannelKey> targetKeysBySlot`）；③ 客户端缓存 `EnderChannelClientCache.clear()`/`removeChannel()` **全项目零调用者**；④ `totalRoutes` 语义会被污染（现在是全服所有频道总和，加专属频道后是噪音且泄漏私有频道规模）⇒ 用户定案**砍掉**，Tooltip 只显示本频道条数
- ✅ **顺带发现（有利）** —— `EnderChannelRegistry` 的 `getChannels`/`getActiveChannelCount`/`getChannelSnapshot`/`getEntries`/`getChannelSize` **全项目无外部调用者** ⇒ 改签名零成本，可直接删掉前两个
- ✅ **已验证的边界** —— target 末影箱**永远**在注册者容器内（`CrossContainerTransfer.pullFromNeighbor` 从 `containerCtx` 取 `targetStack`）⇒ 改法覆盖全部路径；拆堆一半去别的容器也被覆盖（反向索引按源/注册者容器索引）
- 📄 已定案：行为跳变允许（Tooltip 明确标注当前是「直连」还是「专属频道 N」），理由是本 mod 一贯哲学「**堆叠数 = 配置旋钮**」；文档 `living-ender-chest-tech.md` 待升 v12

---

## 2026-08-30

- ✅ **新增：红电发电阶段一~三** —— `domain/power` 包（`PowerMath` / `ChannelState` / `GeneratorState` / `ContainerPowerData`），双因子模型落地：合因子 = n^(1+解锁度)，解锁度 = 调谐效率 × 规律度
- ✅ **新增：`LivingWaxedCopperFunction`（priority=3，晚于红石）** —— 涂蜡全家族 20 件活化，逐方向采样 edgeGrid 事件，跳变即能量事件入账（RE 自然单位，K=1/16 边界换算）
- ✅ **新增：阶段三+四** —— 感应拓扑（线圈分组：铜块全向/雕文 V+H/切制单方向）+ 感应耦合（管径加权守恒、不回传防环、多跳中继）+ 绝缘修复（涂蜡排除出充能导体，杜绝信号泄漏）+ 储能：铜灯 = 唯一储存（发电直存、无容器池），容量 = count×C 线性涌现 + 对外能量：**显式注册+让位**（10 种原版容器 BE，三层判定不劫持已有能源；方块级双向 canReceive=true）+ 铜灯物品 = 通用电池（双向：放电 + 外部充电，跨系统能量等量转换）
- ✅ **新增：表现层** —— Tooltip 仪表盘（检测值写回组件 → 槽位同步 → 客户端渲染，双语 key）
- ✅ **新增：39 项电力层测试**（数学 5 + 状态机 6 + 线圈分组 4 + 储能 16（含仪表 2）+ 电池 6 + 集成 2），全量 146 项测试通过
- 📄 技术文档：`docs/tech/living-power-tech.md`

---

## 2026-08-27

- ✅ **重构：活水车应力状态机提取**（`StressStateMachine` 纯 Java 类，从 `KineticBlockEntityMixin` 中提取）
  - 将原 Mixin 内联的 `livingItem$generatedRPM`、`livingItem$stressCapacity`、`livingItem$refreshedThisTick`、`livingItem$pendingReattach` 字段管理迁移到 `StressStateMachine`
  - 状态机可独立测试，不依赖 Mixin 框架
  - `applyStress(rpm, cap)` 统一入口：同时设置 RPM 和 SU 容量，消除 `setGeneratedRPM` 必须在 `setStressCapacity` 之前调用的隐式依赖
  - `getGeneratedSpeed()` 改为纯 getter（移除副作用——原先在 getter 中清零 RPM 和 capacity）
  - **修改文件**：`StressStateMachine.java`（新建）、`KineticBlockEntityMixin.java`（简化）

- ✅ **重构：应力输出入口合并**（`StressOutputManager` 替代 `ModCreate` + `CreateIntegration`）
  - 将常量定义（`BASE_RPM`、`BASE_SU_CAPACITY`）、Create 检测、白名单过滤、方向兼容性检查合并到一个类
  - 直接 import Create 类型（`StressOutputManager` 在 `compat/create` 包中，Create 已确认加载），简化 `instanceof` 检查
  - 调用方从 `ModCreate.updateStressOutput()` → `StressOutputManager.apply()`
  - **修改文件**：`StressOutputManager.java`（新建）、`ContainerLivingItemHandler.java`（更新调用方）

- ✅ **修复：`deferredSync` 延迟同步导致客户端空状态**
  - **根因**：重构引入 `needsSync` 标记 + `deferredSync` TAIL 注入模式，将 `sendData()` 从 `applyStress()` 延迟到 `tick()` TAIL。但 `tick()` HEAD 先执行清理逻辑，`deferredSync` 发送的是可能被清理后的状态
  - `ServerTickEvent.Post` 模式下，`applyStress()` 在 `tick()` 之后执行，`tick()` HEAD 发现 `refreshedThisTick=false` 触发清理，`deferredSync` 发送空状态。接着 `applyStress()` 设置 `needsSync=true`，但 `tick()` 已结束，`needsSync` 永远得不到处理
  - **修复**：在 `applyStress()` 和 `tick()` 中直接调用 `self.sendData()`，移除 `needsSync` 字段、`deferredSync` 方法和 TAIL 注入
  - **修改文件**：`StressStateMachine.java`（移除 `needsSync`、`deferredSync`）、`KineticBlockEntityMixin.java`（移除 TAIL 注入）

- ✅ **修复：`applyStress` 中多余的 `isRemoved()` 检查**
  - 移除 `applyStress()` 中 `self.isRemoved()` 检查，与工作版本对齐，避免生命周期过渡期状态被跳过
  - 移除 `tick()` 中 `self.isRemoved()` 守卫，与工作版本对齐
  - **修改文件**：`StressStateMachine.java`

- ✅ **文档：活水车技术文档更新至 v10**
  - 更新类职责表（新增 `StressStateMachine`、`StressOutputManager`）
  - 更新软依赖架构图（反映新统一入口和即时 `sendData()`）
  - 新增 `StressStateMachine` 和精简后的 `KineticBlockEntityMixin` 代码文档
  - 新增 9.21 踩坑记录：`deferredSync` 延迟同步导致客户端空状态
  - 更新 Tick 时序图（反映 `ServerTickEvent.Pre` 和 `StressOutputManager`）
  - 更新验证清单
  - **修改文件**：`docs/tech/living-water-wheel-tech.md`

---

## 2026-08-25

- ✅ **提升：红石传播时间分辨率从 2 tick 改为 1 game tick**，取消跳帧。容器内最快振荡周期从 4 tick 降至 2 tick，为红电系统的高频档位提供基础。中继器/按钮延迟统一以 game tick 计数（中继器档位 N = 2N tick，保持原版红石刻语义）
- ✅ **优化：传播热路径改用槽位类型位图（`slotMask`）**，替换 18 处 `Set<Integer>.contains`，消除装箱与每 tick 的临时 `HashSet`。实测单次传播开销降 40~76%（满载 54 格 9.4μs → 4.9μs），1 tick 传播总成本与原 2 tick 持平

---

## 2026-08-22

- ✅ **新增：活TNT红石信号点燃**（活TNT收到红石信号自动点燃，无需打火石）
  - **实现**：`LivingTntFunction` 新增 `HasContainerData` 接口实现，`tick()` 中通过 `ContainerRedstoneData.getSlotSignal()` 检测槽位红石信号，信号 > 0 时自动点燃 TNT
  - `getPriority()` 返回 1，确保红石数据在 TNT tick 之前计算完毕
  - `tickContainerData()` 调用 `redstoneData.calculate()` 触发红石信号传播
  - `getSlotSignal(slot, size, width)` 同时检查 `edgeGrid.maxOfSlot()` 和边界 `faceInput`，确保跨容器信号也能点燃 TNT
  - **修改文件**：`LivingTntFunction.java`

- ✅ **新增：ContainerRedstoneData.getSlotSignal() 方法**
  - 查询槽位有效信号，合并内部边信号和外部 `faceInput` 信号
  - 边界槽位额外检查对应面的 `faceInput`，支持跨容器及外部世界信号检测
  - 供 `LivingTntFunction`、漏斗等消费方使用
  - **修改文件**：`ContainerRedstoneData.java`

- ✅ **优化：ContainerRedstoneData 无参构造**（移除 `size` 参数）
  - `edgeGrid` 在首次 `calculate()` 时按需创建，无需构造时预先分配
  - 移除 `slotCount` 字段（不再需要）
  - 所有创建位置同步更新为无参构造
  - **修改文件**：`ContainerRedstoneData.java`、`ContainerLivingItemHandler.java`、`TickContext.java`

- ✅ **修复：容器输出信号不消失**（容器内信号源移除后，容器外仍残留旧信号）
  - **根因**：`reset()` 中 `edgeGrid` 与 `prevEdgeGrid` 交换后，新的 `edgeGrid` 继承旧数据。`edgeGrid.zero()` 被移除后，旧边信号泄漏到当前帧
  - **修复**：`reset()` 中重新加入 `edgeGrid.zero()` 调用，确保每次传播重算前边网格从零开始
  - **修改文件**：`ContainerRedstoneData.java`

- ✅ **修复：processLevelContainers 并发修改崩溃**（TNT 爆炸时修改方块实体导致迭代崩溃）
  - **根因**：`processLevelContainers` 遍历 `chunk.getBlockEntities().values()` 时，TNT 爆炸的 `setBlock()` 修改了同一集合，触发 `ConcurrentModificationException`
  - **修复**：遍历前创建快照 `new ArrayList<>(chunk.getBlockEntities().values())`，避免迭代期间集合被修改
  - **修改文件**：`LivingItem.java`

- ✅ **文档：跨容器红石信号传输数据流详解**
  - 新增完整数据流图：输出路径（容器→世界）和输入路径（世界→容器）的逐步说明
  - 新增 `CrossContainerTransfer.worldToGrid()` 方向映射说明
  - 新增 `BlockStateBaseMixin` 拦截机制说明
  - 新增 `injectExternalInputs` 直读邻居容器 `getBoundarySignal()` 的时序说明
  - 文档版本更新至 v14
  - **修改文件**：`docs/tech/living-redstone-tech.md`

- ✅ **文档：活TNT红石点燃功能文档更新**
  - 新增 3.3 红石信号点火章节
  - 更新数据流总览图，加入红石信号点燃路径
  - 更新关键类表，新增 `ContainerRedstoneData` 引用
  - 文档版本更新至 v5
  - **修改文件**：`docs/tech/living-tnt-tech.md`
- ✅ **修复：红石传播节拍改为对齐全局游戏时钟**，消除因容器加载时机不同导致的跨容器信号错位半拍问题
- ✅ **新增：单元测试基建** — MDG `unitTest` 配置，测试可在 FML 环境引用 Minecraft 类
- ✅ **新增：64 项单元测试**（红石传播 21 + 容器兼容性 14 + 地图坐标 29）
- ✅ **修复：客户端 Mixin 从双端 `mixins` 移至 `client` 数组**（专用服务器启动崩溃）
- ✅ **修复：`EdgeGrid.get/set` 缺少边界检查**，容器尺寸变化时会越界崩溃
- ✅ **修复：容器级数据缓存键补齐维度**，消除跨维度同坐标容器串数据
- ✅ **修复：`grouped.isEmpty()` 分支门禁失效**（`getSize()` 恒为 0），残留边界红石信号现可正确归零
- ✅ **优化：新增位置→缓存键反向索引**，mixin 热路径（`getSignal` / `getConnectingSide`）从正则全表扫描降为 O(1)
- ✅ **修复：`APPLICABLE_CACHE` 改用 `ConcurrentHashMap`**，消除单人游戏双线程并发写风险
- ✅ **新增：`ServerStoppedEvent` 统一清理静态缓存**，避免跨存档状态残留
- ✅ **优化：`PerfMetrics` 全面改用纳秒累计**，修复亚毫秒耗时被整数除法归零的问题
- ✅ **清理：删除 `TickContext` 未使用的泛型扩展点**

---

## 2026-08-19

- ✅ **优化：传送区块加载从 ChunkStatus.FULL 降为 LIGHT**（远距离传送到未探索区域时 MSPT 峰值 16200ms → 大幅降低）
  - **根因**：`ensureChunkLoaded` 使用 `level.getChunk(chunkX, chunkZ)`（默认 `ChunkStatus.FULL`），要求区块经历完整 11 个生成阶段。`FULL` 的 `ChunkPyramid` 依赖链中 `STRUCTURE_STARTS` 半径 8，导致 `ChunkGenerationTask` 需覆盖 17×17 = 289 个区块。主线程通过 `managedBlock()` → `LockSupport.parkNanos()` 阻塞等待（spark 报告 205.84%），同时 `FULL` 阶段的 `runPostLoad()` 在新生成区块上触发大量 mod 事件（`twilightforest` 22.82%、`sable` 66.17%）。
  - **修复**：`findSafeY` 仅需 `MOTION_BLOCKING` 高度图，该数据在 `LIGHT` 阶段即已就绪。将 `ensureChunkLoaded` 改为 `level.getChunk(chunkX, chunkZ, ChunkStatus.LIGHT, true)`，跳过 `SPAWN`（出生点生成）和 `FULL`（ProtoChunk→LevelChunk 转换 + `runPostLoad()`）两个阶段。`ChunkLoadResult` 和 `findSafeY` 参数类型从 `LevelChunk` 改为 `ChunkAccess`（`LIGHT` 返回 `ImposterProtoChunk`）。
  - **修改文件**：`TeleportHelper.java`

- ✅ **优化：零区块加载传送——ChunkGenerator.getBaseHeight 替代 ChunkStatus.LIGHT**
  - **根因**：v47 的 `ChunkStatus.LIGHT` 仍依赖 `STRUCTURE_STARTS` 半径 8（`LIGHT` → `FEATURES` → ... → `STRUCTURE_STARTS`），仍需 289 个区块的生成任务。spark 报告 `parkNanos` 3.89% self = 3760ms 纯阻塞等待。`ChunkStatus` 体系中任何能提供高度图的状态都必须经过 `LIGHT`，无法绕过 289 区块依赖。
  - **修复**：完全绕过区块加载，使用 `ChunkGenerator.getBaseHeight(x, z, MOTION_BLOCKING, level, randomState)` 从噪声密度函数直接计算地表高度。`NoiseBasedChunkGenerator.getBaseHeight()` 内部调用 `iterateNoiseColumn()` 遍历噪声柱，在第一个不透明方块处停止并返回 Y+1，耗时 < 1ms。移除 `ensureChunkLoaded`、`findSafeY`、`ChunkLoadResult` 方法和相关 import。`teleportToBanner` 也移除 `ensureChunkLoaded`（`changeDimension`/`teleportTo` 内部通过 `POST_TELEPORT` ticket 异步加载）。子位面传送保留 `ensureChunkForSubLevel`。
  - **修改文件**：`TeleportHelper.java`

- ✅ **修复：未打开战利品容器触发战利品表生成导致 processLevelContainers 耗时过高**
  - **根因**：`processLevelContainers` 遍历所有世界容器时，`RandomizableContainerBlockEntity.getItem()` 内部调用 `unpackLootTable()`，触发战利品生成。战利品表（如 `minecraft:chests/shipwreck_map`）中的 `ExplorationMapFunction` 搜索结构，在未探索区域耗时极高。
  - **修复**：
    - `LivingItem.processLevelContainers()`：遍历时跳过 `RandomizableContainer` 且 `lootTable != null`（未打开）的容器
    - `ContainerLivingItemHandler.processContainerAt()`：同上，添加战利品容器过滤逻辑
  - **修改文件**：`LivingItem.java`、`ContainerLivingItemHandler.java`

- ✅ **修复：活中继器延迟计数器不减少**（活红石中继器的延迟计数器每 tick 被重置，导致永远无法输出信号）
  - **根因**：`SimpleContainerContext` 实例每 tick 重建，其内部的 `redstoneData` 字段始终为 null，`getOrCreateRedstoneData()` 每 tick 创建新实例。这导致 `edgeGrid` / `prevEdgeGrid`（边信号状态）和 `tickCounter`（延迟计数器）每 tick 丢失，中继器功能无法正常工作。
  - **修复**：
    - `ContainerLivingItemHandler`：新增 `REDSTONE_DATA_CACHE` 静态缓存（`LinkedHashMap`），通过 `containerKey` 关联，确保 `edgeGrid` / `prevEdgeGrid` / `tickCounter` 跨 tick 持久化
    - `SimpleContainerContext.getOrCreateRedstoneData()`：改为从 `ContainerLivingItemHandler.getRedstoneData()` 获取数据，而非新建实例
    - `ContainerRedstoneData`：新增 `lastTickTime` 字段，在 `calculate()` 中更新，支持过期清理
    - `ContainerChunkCache.onBlockBreak`：新增 `removeRedstoneDataByPos()` 调用，事件驱动清理
    - `ContainerLivingItemHandler.cleanupStaleRedstoneData()`：每 120 秒清理一次 120 秒内未访问的红石数据
  - **清理机制**：事件驱动清理（`removeRedstoneDataByPos`）→ 定期过期清理（`cleanupStaleRedstoneData`）→ 容器销毁清理（`removeRedstoneData`）

- ✅ **修复：区块重新加载后活物品不工作（残留问题）**（2026-08-17 的修复未完全解决，离开区块一定时间后返回容器中活物品仍停止 tick）
  - **残留根因**：`ChunkEvent.Load` 在 `MinecraftServer.waitUntilNextTick()` 的 `runAllTasks()` 中触发，而 `processLevelContainers` 在 `ServerTickEvent.Post` 中执行（早于 `runAllTasks`）。`onChunkUnload` 立即从缓存中移除区块 → `ChunkEvent.Load` 来不及在同一 tick 加回缓存 → `processLevelContainers` 找不到该区块。
  - **修复**：
    - `ContainerChunkCache.onChunkUnload`：不再从缓存中移除区块，改为由 `cleanupStaleEntries` 统一清理
    - `ContainerChunkCache.cleanupStaleEntries`：首次调用时仅初始化 `lastCleanup` 时间戳并返回，避免立即清理刚卸载、即将重新加载的区块
  - **修复后流程**：卸载 → 缓存保留 → 重新加载 → 下一 tick 正常处理（最多延迟 1 tick）

- ✅ **修复：活中继器输入端有信号但显示无信号**（红石火把等信号源正常输出，但中继器始终显示"unpowered"，无法检测到输入信号）
  - **根因**：`SimpleContainerContext` 实例每 tick 重新创建，`setTickContext()` 中 `resetProcessedFlag()` 的调用被 `redstoneData != null` 条件守卫。由于新实例的 `redstoneData` 字段始终为 null，`resetProcessedFlag()` 永远不会被调用。`ContainerRedstoneData.processedThisTick` 在第一 tick 后被设为 true 后永不重置，导致 `calculate()` 从第二 tick 起直接返回，完全跳过红石计算。
  - **修复**：
    - `SimpleContainerContext.setTickContext()`：改为主动调用 `getOrCreateRedstoneData()` 从静态缓存获取已持久化的 `ContainerRedstoneData` 实例，再调用 `resetProcessedFlag()`，确保每一 tick 开始时 `processedThisTick` 被正确重置为 false
  - **相关知识**：`calculate()` 是一次性处理所有红石类型（火把、中继器、比较器、红石粉、灯、按钮、拉杆）的综合方法，`processedThisTick` 标志的作用是防止多个 `HasContainerData` 函数在同一 tick 重复调用 `calculate()`，而非区分不同红石类型的处理顺序。因此调整各功能 `getPriority()` 的方案并不对症——真正的问题在于该标志从未被重置。

- ✅ **修复：活中继器不能延迟熄灭**（输入信号消失后应延迟对应时间再停止输出，而非立即熄灭）
  - **根因**：`phase0CountdownDelays` 中 `!hasInput` 分支立即将 `powered` 设为 false 且 `delayTimer` 归零，`phase3RecheckInputs` 中 `!hasInput && data.powered()` 分支同理。中继器只有"上升沿延迟"（ON_DELAY），没有"下降沿延迟"（OFF_DELAY）。
  - **修复**：利用 `delayTimer` 的正负号区分两种延迟方向，无需修改 `LivingRepeaterData` 记录结构：
    - `delayTimer > 0`：ON_DELAY（等待开启，不输出信号）
    - `delayTimer = 0`：ON（正常输出）
    - `delayTimer < 0`：OFF_DELAY（等待关闭，**继续输出信号**）
  - **具体变更**：
    - `phase0CountdownDelays`：`delayTimer > 0` 时递减（ON_DELAY 倒计时），`delayTimer < 0` 时递增趋近于 0（OFF_DELAY 倒计时）。ON_DELAY 期间若输入消失则取消（`powered=false`）；OFF_DELAY 归零时转为 OFF（`powered=false`）
    - `phase1CollectSources` 中继器输出条件：`delayTimer() != 0` → `delayTimer() > 0`，确保 OFF_DELAY（`delayTimer < 0`）期间继续输出信号
    - `phase3RecheckInputs`：新增三种过渡——`hasInput && powered && delayTimer < 0`（OFF_DELAY 期间输入恢复，取消延迟回到 ON）；`!hasInput && powered && delayTimer == 0`（ON 状态下输入消失，启动 OFF_DELAY，`delayTimer = -delay`）
    - `LivingRepeaterFunction.addToTooltip`：OFF_DELAY 期间用 `Math.abs()` 显示正数

- ✅ **新增：活红石块**（`Items.REDSTONE_BLOCK`）
  - 常亮信号源，向四个方向输出信号强度 15（受堆叠数衰减），无状态数据
  - 新增 `LivingRedstoneBlockFunction`：实现 `LivingItemFunction` + `HasContainerData`，`canApply` 匹配 `Items.REDSTONE_BLOCK`
  - `ContainerRedstoneData.phase1CollectSources`：新增 `redstoneBlockSlots` 参数，红石块始终向 4 方向写边信号
  - 无 DataComponent：红石块无状态，无需持久化数据

---

## 2026-08-17

- ✅ **修复：区块重新加载后活物品不工作**（离开区块一定时间后返回，容器中活物品停止 tick；重新进入游戏恢复正常）
  - **根因**：`processLevelContainers` 使用 `getChunkNow()` 获取区块，当 `FULL` 状态的 `CompletableFuture` 尚未完成时返回 null，导致区块被 `toRemove` 从缓存中移除。区块从磁盘完整重新加载时存在 `ChunkEvent.Load`（触发缓存加入）与 `ServerTickEvent.Post`（触发缓存遍历）之间的时序窗口，加载后同一 tick 内被意外清除。
  - **为什么短时间内回来正常**：短时间内区块未完全卸载，`getChunkNow` 仍返回有效值，不会被移除。
  - **修复**：
    - `LivingItem.processLevelContainers`：`getChunkNow` 返回 null 时不再立即移除，只跳过本次处理，保留到下次 tick
    - `ContainerChunkCache.cleanupStaleEntries`：新增定期清理方法，每 6000 ticks（约 5 分钟）清理一次真正已卸载的区块，作为兜底机制防止内存泄漏
    - `ContainerChunkCache.clear()`：同步清理 `lastCleanupTick` 时间戳
  - **清理机制三层保障**：`onChunkUnload`（即时移除）→ `!hasContainer` 检查（自清洁）→ `cleanupStaleEntries`（兜底清理）
  - **注意**：此修复在 2026-08-19 发现残留问题——`onChunkUnload` 即时移除与 `ChunkEvent.Load` 晚触发之间存在时序窗口，见 2026-08-19 记录。
- ✅ **重构：接口化设计** — `HasDirection` 接口统一 WASD 朝向配置，`HasContainerData` 接口统一容器级数据计算
- ✅ **重构：`TickContext` 每 tick 新建**（对象小、生命周期短，JVM 年轻代可高效回收）
- ✅ **优化：新增活物品从修改 6 个文件减少到 2 个文件**
- ✅ **更新：全部文档同步至 v8.1 架构**

---

## 2026-08-16

- ✅ **优化：Mixin 兼容性重构**（移除高风险 Mixin，改用 NeoForge API 或低风险替代方案）
  - 删除 `MapItemMixin`（`@Inject` 到 `EmptyMapItem.use`）→ 改用 `PlayerInteractEvent.RightClickItem` 事件（`LivingMapEventHandler.handleLivingMapCreation`）
  - 删除 `MapItemUpdateMixin`（2个 `@Redirect` 替换 `Level.getChunk`）→ 依赖原版 `MapItem.update`（玩家附近区块通常已加载；原 `@Redirect` 与暮色森林魔法地图冲突导致服务端卡死）
  - 删除 `BlockEntityMixin`（接口注入 + 字段注入 `livingItem$stressData`）→ 改用 NeoForge `AttachmentType`（`LivingItemManager.CONTAINER_STRESS_DATA`，框架级支持、自动序列化、类型安全）
  - 删除 `StressDataProvider` 接口（不再需要，`be.getData()`/`be.setData()` 替代）
  - `RecipeBookComponentMixin` 移除 3个 `@Redirect` → 新增 `RecipeBookPageMixin`（3个 `@Inject HEAD cancellable`，低风险，允许多模组链式共存）
  - `ContainerLivingItemHandler` 应力写入从 `StressDataProvider.livingItem$setStressData()` → `be.setData(CONTAINER_STRESS_DATA, stressData)`
  - 更新 `living_item.mixins.json`（移除 `MapItemMixin`、`MapItemUpdateMixin`、`BlockEntityMixin`）
  - 更新 `living_item.client.mixins.json`（新增 `RecipeBookPageMixin`）
- ✅ **重构：包结构重组** — `data/` 删除，`function/` 精简，所有活物品内聚到 `domain/`
- ✅ **重构：传输管道统一** — `TransferPipeline` 统一传输入口，解耦 `CrossContainerTransfer` 与 `LivingHopperFunction`
- ✅ **重构：活末影箱路由解耦** — `EnderRouteManager` 集中管理路由，`LivingEnderChestAccessor` 精简
- ✅ **重构：活熔炉同步生命周期统一** — 改用 `SlotAccessor`，支持活箱子作为输入/输出
- ✅ **新增：`HopperFilterBuilder` 过滤链构建**（从 `ContainerSnapshot` 提取）
- ✅ **新增：脏槽位批量同步机制**（`TickContext.dirtySlots` + `SimpleContainerContext.flushDirtySlots()`）
- ✅ **更新：全部技术文档同步至 v8 架构**

---

## 2026-08-09

- ✅ **新增：活地图传送系统**（三种场景 + UV 精确传送 + 跨维度 + 载具 + Sable 飞艇兼容）
- ✅ **新增：GUI 扩展地图渲染 + 十字光标 + 展示框十字光标 + 活地图图标**
- ✅ **新增：元数据同步包 + 客户端缓存**
- ✅ **修复：跨维度提示重复**（`MapTeleportExecutor.execute()` 统一发送）
- ✅ **修复：副手活末影珍珠未统计/消耗**
- ✅ **优化：渲染性能**（`hoveredGroup` 缓存，O(N²)→O(N)）

---

## 2026-07-30

- ✅ **重构：包结构按领域聚合**（消灭 `core/` 万能垃圾桶，消除 `capability/` 专属小包，`create/` 提升为 `compat/create/`）
  - `api/` — `LivingItemFunction` + `LivingItemManager` 从 `living/` 根提升
  - `domain/ender/` — 末影箱+活箱子领域聚合（`EnderChannelRegistry`、`EnderChannelEntry`、`LivingChestAccessor`、`LivingEnderChestAccessor` 等 8 个文件）
  - `domain/water/` — 活水领域聚合（`ContainerFluidData`、`ContainerStressData` 从 `container/` 移出）
  - `domain/map/` — 活地图传送领域聚合（`MapTeleportExecutor`、`TeleportHelper`、`MapCoordHelper`、`LivingMapEventHandler`、`ItemFrameMapTeleportHandler`、`LivingMapClientCache`）
  - `compat/create/` — Create 兼容层从 `create/` 提升（含 `CreateMixinPlugin`，已更新 mixin JSON 路径）
  - `compat/sable/` — Sable 飞艇兼容层（`SableCompat` + `ModSable` + `SableIntegration`，三层软依赖）
  - `transfer/` — 传输基础设施（`SlotAccessor` 体系 + `SlotResolver` + `ContainerCompatibilityConfig`，从 `core/accessor/` + `core/config/` 合并）
  - `interaction/` — GUI交互从 `core/interaction/` 提升
  - `model/` — 配置模型从 `core/model/` 提升
  - `components/` — 无状态工具组件从 `core/components/` 提升（`LivingChestTooltipComponent` 归入 `domain/ender/`）
  - `container/` — 纯容器抽象（移除业务数据 `ContainerFluidData`/`ContainerStressData`）
  - `client/render/` — 渲染类从 `client/` 根 + `client/tooltip/` 合并
  - `client/input/` — 输入处理从 `client/` 根移出
- ✅ **优化：活水桶和活水车代码审查**
  - 修复 `ContainerLivingItemHandler` 中 `stressData.calculate()` 缺少第三个参数的编译错误
  - 移除 `LivingWaterBucketFunction.tick()` 中冗余的 `syncSlotToClients` 调用（`postTickSync` 统一同步）
  - `LivingWaterBucketFunction.postTickSync()` 添加 flow 变化检测，避免无变化时冗余网络同步
  - `ContainerLivingItemHandler` 合并两次 `grouped.entrySet()` 遍历为一次
- ✅ **新增：活水车系统**（`LivingWaterWheelFunction` + `LivingWaterWheelData` + `WaterWheelData` + `ContainerStressData`）
- ✅ **新增：力矩计算模型**（二维叉积：位置向量 × 水流方向向量，CW/CCW 方向判定，水流强度权重）
- ✅ **新增：Create 软依赖集成**（`CreateCompat` 检测 + `CreateMixinPlugin` 条件加载 + `ModCreate` 安全调用 + `try-catch` 双重防护）
- ✅ **新增：应力输出逻辑**（`CreateIntegration`：白名单过滤 + 方向兼容性检查 + RPM/SU 设置）
- ✅ **新增：KineticBlockEntity Mixin**（`KineticBlockEntityMixin`：应力输出 + 自过期机制 + 白名单过滤 + `attachKinetics()` 重连）
- ✅ **新增：容器底部/玩家脚底应力传递**（`ContainerLivingItemHandler` 扩展，背包中活水车从玩家脚底输出）
- ✅ **新增：物品栏 3D 旋转渲染**（`ItemRendererWaterWheelMixin`：BakedModel + PoseStack 旋转变换，仅在有应力时旋转）
- ✅ **新增：漫反射光照修正**（`RenderSystem.setShaderLights()` + `combinedLight` 修改，解决物品贴图过暗）
- ✅ **新增：BlockEntity 应力存储**（NeoForge `AttachmentType`：`CONTAINER_STRESS_DATA`，替代原 `BlockEntityMixin` + `StressDataProvider` 接口注入，框架级支持、自动序列化、类型安全）
- ✅ **修复：旋转方向与物品栏不一致**（RPM 公式添加负号 `-sign(netStress)`）
- ✅ **修复：复杂组件崩溃**（白名单策略，仅允许 `SimpleKineticBlockEntity` 和 `BracketedKineticBlockEntity`）
- ✅ **修复：方向冲突导致方块销毁**（`isDirectionCompatible()` 软侵入检查，方向相反不注入）
- ✅ **修复：取消应力后齿轮不恢复**（`attachKinetics()` 重连邻居网络，自动被原发电机接管）
- ✅ **修复：应力源消失后残留**（`refreshedThisTick` 布尔标记替代时间戳，1 tick 响应 + 客户端同步）
- ✅ **修复：`validateKinetics()` 60 tick 后应力消失**（移除 `source` 字段依赖，注入 BE 作为旋转源）

## 2026-07-29

- ✅ **新增：贪心提取策略**（`preferredItemType` 偏好提取，输出槽有铁锭时优先继续提取铁锭可堆叠，而非轮询到金锭导致传输停止，回退正常轮询）
- ✅ **重构：统一路由验证**（`validateRoutes()` 替代 5 个独立清理方法，一次遍历完成注册者/目标/源物品三项检查，新增 `registrarKeyIndex` 反向索引）
- ✅ **修复：活末影箱被拿走后路由不清理**（`registerRoute()` 接收 `targetSlot` 参数，路由条目正确记录活末影箱所在槽位）
- ✅ **修复：活漏斗被拿起到鼠标后路由不清理**（`LivingEnderChestFunction.tick()` 现在也扫描活漏斗槽位，空集触发清理）
- ✅ **新增：TickContext.functionSlots 缓存**（`processContext()` 分组时一次性填充各功能活跃槽位集合，功能类 O(1) 读取，无需遍历容器）

## 2026-07-28

- ✅ **优化：双重扫描合并**（`processContext` 内部扫描后 `grouped` 为空时提前 return，`processEnderChest` 和 `processContainerAt` 不再做预扫描，每容器每 tick 省一次全量槽位扫描）
- ✅ **优化：Snapshot 懒加载**（`TickContext.getSnapshot()` 按需构建，闲置容器和只有活熔炉的容器零开销，`reset()` 不再预构建 snapshot）
- ✅ **优化：容器缓存自清洁**（`ContainerChunkCache.removeChunk()` + `processLevelContainers` 自动清理已卸载区块和无容器区块，零额外扫描开销，缓存自动收敛）
- ✅ **优化：直连模式 InvWrapper 缓存**（`LivingEnderChestAccessor` 构造时缓存 `cachedInvWrapper`，所有操作复用同一实例，避免每 tick 重复创建 `InvWrapper`）
- ✅ **优化：直连模式轮询提取**（`nextExtractSlot` 指针记录上次提取槽位，末影箱所有槽位公平轮询，解决特定槽位物品无法被提取的问题）
- ✅ **重构：EnderChannelRegistry Deque 替代 List+nextIndex**（`ArrayDeque` + `poll()`/`reoffer()` 天然实现轮询调度，消除手动指针管理，`peekEntry()`/`advancePointer()`/`peek()` 三个方法合并为 `poll()` + 条件 `reoffer()`）
- ✅ **新增：RouteKey 内部类 + 反向索引**（`routeKeys` 集合 O(1) 路由去重检查，`entryToChannel` 反向索引 O(1) 查找条目所属频道）
- ✅ **重构：removeIf() 收集后删除模式**（先收集符合条件的条目再批量删除，避免在遍历中混用 `poll()`/`offer()` 导致的 `ConcurrentModificationException`）

## 2026-07-27

- ✅ 重构：DataComponent 直接管理架构迁移（功能类直接管理类型化 DataComponent，替代 `ComponentState` + `LivingFunctionData` 中转层）
- ✅ 重构：组件无状态化改造（`ProgressComponent`、`FuelConsumeComponent` 等从有状态组件变为无状态工具类，接收类型化数据返回新数据）
- ✅ 新增：`data/` 包 — 不可变 Record 数据模型（`LivingTntData`、`LivingWaterBucketData`、`LivingFurnaceData`、`LivingHopperData`、`LivingEnderChestData` 及其子数据）
- ✅ 新增：`DirectionSlotsData` / `DirectionTransferData` 不可变方向数据模型，替代 `DirectionModeComponent` + `ComponentState`
- ✅ 重构：`LivingItemFunction.addToTooltip()` 移除 `CompoundTag` 参数，功能类直接从 `ItemStack` 读取 DataComponent
- ✅ 重构：`LivingTntFunction`、`LivingWaterBucketFunction`、`LivingFurnaceFunction`、`LivingHopperFunction`、`LivingEnderChestFunction` 迁移到新架构
- ✅ 重构：客户端渲染层迁移
- ✅ 重构：`LivingItemTooltip` 简化
- ✅ 重构：`ItemFilterComponent.inheritFilter()` 从 `getFunctionData` 迁移到 `LivingItemManager.getHopperData()`
- ✅ 新增：`SlotMapping.fromDirections(Pos2D, Pos2D)` 工厂方法
- ✅ 修复：`LivingWaterBucketData.DEFAULT` → `LivingWaterBucketData.EMPTY`
- ✅ 修复：`ExplosionData.ignite()` 无参重载方法（默认 80 刻引信）
- ✅ 兼容：`ProgressComponent` 实现 `ILivingComponent` 接口 + `ComponentState` 适配器方法，保持旧编排器编译兼容
- ✅ **优化：TickContext 对象池**（`TickContextPool` 复用 tick 实例——已废弃，大负载场景下池化收益为负，改为每次 tick 创建新实例，由 JVM 年轻代 GC 回收）
- ✅ **优化：SlotAccessor 注册式工厂**（`SlotAccessorFactory.registerProvider()` 开放扩展，第三方模组可注册自定义 Accessor）
- ✅ **优化：LivingItemFunction 接口职责拆分**（5 个逻辑模块：匹配/标识/Tick/Tooltip/组件过滤，可选方法默认空实现）
- ✅ **优化：活箱子精确字节计算**（`LivingChestFunction.calculateExactByteUsage()` 替代粗糙估算，NBT 序列化获取真实大小，Tooltip 显示百分比）
- ✅ **新增：性能监控指标系统**（`PerfMetrics` 收集 Tick 耗时/活物品数量/功能调用/传输成功率，每 60 秒自动打印报告）

## 2026-07-24

- ✅ 重构：SlotAccessor 模拟优先传输模式（`simulateExtract` → `simulateInsert` → `extract` → `insert` → `rollback` 安全兜底 + WARN 日志）
- ✅ 新增：`SlotAccessor` 接口新增 `simulateExtract()` 和 `simulateInsert()` 模拟方法
- ✅ 优化：`SlotAccessor.transfer()` 改为"先模拟确认再真实操作"
- ✅ 优化：`rollback` 修复部分插入场景
- ✅ 优化：`NeighborSlotAccessor.rollback()` 优先放回原槽位
- ✅ 优化：配方缓存（`ItemTransformComponent.resolveRecipe()` 公共方法）
- ✅ 优化：容器位置缓存（拉取模型）替代活跃列表（推送模型）
- ✅ 新增：`BlockEvent` 通用监听兜底
- ✅ 优化：GC 压力降低（`LivingItem` 复用 `IdentityHashMap` 和 `HashSet` 实例字段）
- ✅ 优化：惰性 Tick 去重集合复用

## 2026-07-23

- ✅ 新增：活末影箱系统（`LivingEnderChestFunction` + `LivingEnderChestAccessor` + `EnderChannelRegistry` + `EnderChannelEntry`）
- ✅ 新增：活末影箱双模式（路由模式 + 直连模式）
- ✅ 新增：玩家绑定机制
- ✅ 新增：直连模式预加载
- ✅ 新增：`EnderChannelComponent` 频道组件
- ✅ 新增：`ItemFilterComponent` 黑白名单过滤组件
- ✅ 新增：`FilteredSlotAccessor` 过滤装饰器
- ✅ 新增：`NeighborSlotAccessor` 邻居容器访问器
- ✅ 新增：`SlotAccessor.transfer()` 统一传输方法
- ✅ 新增：`SlotAccessorFactory.createForNeighbor()` 工厂方法
- ✅ 新增：末影箱容器处理
- ✅ 新增：反向索引路由清理
- ✅ 新增：活末影箱 Tooltip
- ✅ 重构：`CrossContainerTransfer` 使用 `NeighborSlotAccessor` + `SlotAccessor.transfer()` 统一传输
- ✅ 重构：活末影箱组件化改造
- ✅ 修复：末影箱中活物品不工作
- ✅ 修复：活漏斗往绑定玩家的活末影箱输入物品不消耗
- ✅ 修复：末影箱中未绑定玩家的活末影箱无法建立路由
- ✅ 修复：水晶箱子（12×9）中活漏斗黑白名单无法正确生效
- ✅ 修复：大箱子中活物品一 tick 内被处理两次
- ✅ 修复：跨容器注册路由不生效
- ✅ 修复：已绑定玩家的活末影箱仍显示频道信息
- ✅ 修复：活末影箱移走后路由未清理
- ✅ 修复：源物品移走后路由未清理
- ✅ 修复：`EnderChannelRegistry.getChannels()` 遍历时 `ConcurrentModificationException`

## 2026-07-22

- ✅ 重构：全面使用 IItemHandler 统一容器抽象
- ✅ 新增：`ContainerContext.getSlotLimit(slot)` 接口方法
- ✅ 优化：`CrossContainerTransfer.tryInsert()` 使用 `ItemHandlerHelper.insertItemStacked()`
- ✅ 优化：`CrossContainerTransfer.hasAnySpace()` 使用 `getSlotLimit(i)`
- ✅ 优化：`PlainSlotAccessor.insert()` / `isFull()` 使用 `getSlotLimit(slot)`
- ✅ 优化：`ItemTransformComponent.calculateOutputSpace()` 使用 `getSlotLimit(outputSlot)`
- ✅ 优化：`ContainerCompatibilityConfig` 新增 `findOrGenerateRule()` 自动推断标准布局
- ✅ 优化：`ContainerLivingItemHandler.buildChestContext()` 统一使用 IItemHandler
- ✅ 优化：`ContainerLivingItemHandler.processBlockEntities()` 使用 `IdentityHashMap<IItemHandler>` 去重
- ✅ 优化：`ContainerChunkCache` 通过 `Capabilities.ItemHandler.BLOCK` 检测容器
- ✅ 修复：抽屉模组容器满时活漏斗仍传输导致物品消失
- ✅ 修复：精妙背包堆叠上限升级后活漏斗误判槽位已满
- ✅ 重构：活漏斗传输引擎引入 SlotAccessor 统一架构
- ✅ 新增：`SlotAccessor` 接口 + `PlainSlotAccessor` + `LivingChestAccessor` + `SlotAccessorFactory`
- ✅ 删除：4 个旧传输方法，~400 行重复代码
- ✅ 修复：混搭黑白名单链传递失效
- ✅ 修复：过滤器拦截时冷却未设置导致无限循环

## 2026-07-20

- ✅ 新增：方块放置自动填充
- ✅ 新增：三层防护体系
- ✅ 新增：铁砧重命名堆叠修复
- ✅ 统一：UUID 操作方向

## 2026-07-17

- ✅ 新增：活箱子系统
- ✅ 新增：UUID 映射管理
- ✅ 新增：堆叠倍增模型
- ✅ 新增：LRU 缓存策略
- ✅ 新增：快速短路判断
- ✅ 新增：漏斗自动传输
- ✅ 新增：跨容器传输
- ✅ 新增：GUI 拆分/合并 UUID 自动分配
- ✅ 新增：跨 UUID 堆叠支持
- ✅ 新增：被动孤儿文件清理
- ✅ 新增：事务包装器
- ✅ 重构：living 文件夹按职责拆分
- ✅ 修复：堆叠活箱子时漏斗只能访问到一个活箱子
- ✅ 修复：UUID 创建时机
- ✅ 修复：跨包访问权限

## 2026-07-11

- ✅ 新增：活TNT功能
- ✅ 新增：活打火石功能
- ✅ 新增：GUI交互系统
- ✅ 新增：客户端统一交互工具
- ✅ 新增：通用交互网络包
- ✅ 新增：创造模式光标同步
- ✅ 新增：创造模式 SlotWrapper 兼容
- ✅ 新增：ExplosionComponent 双模式爆炸
- ✅ 新增：流体防爆机制
- ✅ 修复：创造模式背包中活打火石无法点燃活TNT
- ✅ 修复：创造模式光标物品消失问题
- ✅ 修复：创造模式打开容器时光标物品消失
- ✅ 重构：所有 Screen Mixin 统一使用 `GuiInteractionHelper.tryInteract()`
- ✅ 重构：引入编排器模式
- ✅ 重构：提取 `BaseLivingFunction` 基类
- ✅ 重构：`LivingFurnaceFunction` 从 317 行 → 82 行（-74%）
- ✅ 重构：`LivingHopperFunction` 从 219 行 → 75 行（-66%）
- ✅ 重构：`FunctionExecutor` 从调度器变为纯工具类
- ✅ 重构：`DirectionModeComponent` NBT 自治
- ✅ 重构：`LivingFunctionConfig` 新增 `withOrchestrator()` 方法
- ✅ 重构：`ComponentContext` 使用 `ResolvedSlots` 替代三个独立字段
- ✅ 重构：`ContainerContext` 新增槽位占用机制
- ✅ 简化：`LivingHopperFunction.canApply()` 移除冗余的物品匹配检查
- ✅ 简化：`LivingItemFunction` 接口新增 `appendComponentTooltips()` 默认方法

## v0.3-alpha

- ✅ 重构：将 `TransferDirection`、`Direction2D`、`HopperModeController`、`LivingHopperInputHandler` 合并为 `DirectionModeComponent` + `LivingItemInputHandler`
- ✅ 重构：提取 `Pos2D` 和 `SlotMapping` 为独立 model 类
- ✅ 修复：活漏斗传输方向修改后 NBT/Tooltip/实际传输未同步更新的问题
- ✅ 修复：活漏斗传输功能失效（`SlotResolver` 网格宽度计算错误）