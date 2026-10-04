# 活水源（Living Water Source）设计草稿

> **草稿纸**：探讨中，未定案，勿当现状读。定案内容沉淀到 `living-water-bucket-tech.md` 后从此处清除。
> 探讨周期：2026-10-02 ~ 10-03，基于 `domain/water/` 现有实现（BFS 重算架构）。
>
> ⚠️ **框架契约见 [buffer/infrastructure-refactoring-plan.md](buffer/infrastructure-refactoring-plan.md) §3 1b**：
> 容器级流体的**基础设施**（`ContainerFluidData` 泛化 / 流动引擎 / 行为分档接口 / tick 归属与排序 / 落盘）
> 由**框架侧**负责；本文件只管**流体本身的行为**（接哪些流体、流速、交互、转化表、汲/倒规则）。
> **框架先行** —— 框架就位，本文件的机制才能推进。

---

## 〇、框架就位对齐（2026-10-03 晚，1a/1b 完成后）

框架侧（1a 地基 4/4 + 1b 通用流体框架）已落地，**413 测试全绿**。流体侧（本文件）据此刻对齐：

**框架已交付**（详见 infrastructure-refactoring-plan.md §3 1b B.8）：

- 自维持通道：`shouldTickWithoutOwnItems` + 注册期静态清单 ⇒ `LivingFluidFunction`（prio 0）
  驱动容器级流体 BFS，**纯源容器照样 tick**（§四.5 的目标已由框架以更优方式实现）
- `ContainerFluidData` 泛化：FlowEntry 带 `FluidType`（单 map 一槽一流体，同槽换流体整条覆盖、
  已占格不被异种流体覆盖）；行为分档接缝 `FluidFlowBehavior`（canFlow/maxLevel/flowSpeed，
  未注册 = 静止；水已注册 flowing(7,0)）
- 源查询 API：`isSource(slot)` / `sourceFluid(slot)` / `hasAnySource()` —— 汲/倒处理器直接可用
- 红石归零解耦（与 grouped 空否无关）；Q5 渲染轨已定向：**复用 `ContainerRuntimeCache.flushToClients`
  + `LivingItemSyncPacket`**（此前「attachment 同步语义待验证」作废，不需验证）

**对本文件机制设计的修正与新约束**：

1. **活空桶/活水桶 = 同一物品 + `FluidStack` 内容组件**（框架定案：与 NeoForge 桶同构）。
   机制一/四的实现形态随之简化：汲 = 向桶的 FluidStack 灌入（空→满），倒 = 放出（满→空），
   活标记与宿主数据全程自然保留，不再需要「换功能组件」。
2. **`isLivingBucketOf` 泛化归流体侧**：源存活判定改读桶的 FluidStack 类型匹配
   （活岩浆桶养岩浆源），替换现硬编码水桶。
3. **`generatedSources` 数据模型（流体侧定义，框架等它接 CODEC 落盘，B.5⑧）**——提案：
   `Map<Integer, FluidType>`（槽位 → 源流体类型）。派生源必须记住自己的流体，
   否则 `recalculate` 重播种时类型信息丢失。生命周期：晋升写入 / 挤没移除 / 汲走移除 /
   容器销毁随 attachment 湮灭。
4. **晋升与转化需要引擎接缝（向框架侧提出，流体侧填行为）**：
   - 晋升 hook：`recalculate` 收敛循环里问行为「该格是否升源」（水：≥2 邻源；岩浆：永不）
   - 转化 hook：每流体拍在源格问行为「格上物品是否转化」（转化表 JSON 归流体侧）
   - 二者作为 `FluidFlowBehavior` 可选 default 方法（默认 no-op），「流体侧只填行为」的分工不破
5. **跨流体交互影响倒水语义**：往岩浆源格倒水，原版是石头/黑曜石，不是「整条覆盖」。
   一期水单体无所谓；接岩浆时倒水处理器须先问跨流体交互 hook（B.4 可选钩子）。

**流体侧任务队列**（2026-10-03 晚批次一执行后更新）：

| 期 | 内容 | 状态 |
|---|---|---|
| F1 | `generatedSources: Map<Integer,FluidType>` 数据模型 + 引擎播种② + 挤没判定 | ✅ 完成（6 测试；框架可接 CODEC = B.5⑧） |
| F2 | 水晋升行为（任意 2/4）+ 挤没自愈回归 | ✅ 完成（接缝已由框架落地） |
| F3 | 活桶 FluidStack 化 + 汲/倒交互（客户端精确拦截 + 交互包管道）+ 处理器 + **桶源退役同批** | ✅ 完成（汲/倒交互不走规则 JSON，走 GuiInteractionHelper 活桶分支）。Q6 收编后解析走 `ContainerContexts.resolve`；背包/末影箱流体已随 B.5 第三项落 Player attachment。**仅剩缺口**：末影箱汲/倒 —— `ContainerContexts.resolve` 无末影箱分支（`EnderChestContainerContext` 是 ContainerLivingItemHandler 私有类，流体侧无法自行构建；基建已就绪，等框架侧一个小分支）。⚠️ **另：右键仍走原版逻辑（未解决）→ 见 §〇.7** |
| F4 | 转化表 JSON（含流体维度键）+ 转化 hook + 漏斗自动化实测 | ✅ 代码完成（`FluidTransformTable` + `/livingitem transforms`，443 测试全绿）；漏斗自动化实测待游戏内 |
| F5 | 渲染轨：`FluidFlowSyncPacket` 容器级同步 + `IClientFluidTypeExtensions` 自适应 + 旧桶轨降级过渡回退 | ✅ 完成（同批次一） |

**批次修正（执行留痕）**：原定「桶源退役与 F5 同批」不成立 —— 桶源拆掉而汲/倒（F3）未落地，
中间态**没有任何造源手段**，水从游戏里消失。修正为：桶源退役与 **F3 同批**（先有倒水，再拆桶源）；
F5 主轨先行（容器级快照本就覆盖桶源数据，切换无损），旧桶轨降级为过渡回退。

---

## 〇.5、桶源存废：已拍板取消（2026-10-03）

**问题**：活水桶要不要保留「放在容器里本身就是源」（桶源）？取消则水流逻辑全部归活水源。

**建议：取消。** 理由按分量排：

1. **经济上是重复计账**。桶源 = 水既在桶里、又同时是世界里的源——一把满桶就是携带式
   无限源。取消后守恒律从「软」变「硬」：水要么在桶里（FluidStack），要么在世界里（源），
   桶 ⇄ 源只能经倒/汲转换。A 方案下「2 桶放对位置 → 白得第 3 源且桶还是满的」的免费午餐消失
   （晋升增长保留，那是机制不是漏洞）。
2. **引擎最后一个流体特判消失**。`isLivingBucketOf` / `Items.WATER_BUCKET` 是引擎里仅剩的
   水硬编码（框架 TODO 交给流体侧泛化的正是它）——取消桶源 = **删掉这个工作项而非完成它**。
   源存活判定只剩「挤没」一条统一规则（活物品在场即毁）。
3. **多流体统一心智**（用户点名的关键）：B 下每种流体的玩法 = 倒/汲（FluidStack）+
   行为分档（流/静止）+ 源资产，一套模型接所有流体；A 下每种流体都要定义「桶即源」耦合。
   原版本身就是 B：桶是物品、源是方块，从不存在「既是桶又是水块」的东西。
4. **源生命周期单一化**：桶源/派生源双记账合并为一种源。汲水「仅限派生源」特例作废
   （§一 row 6），机制二无豁免口径变得更彻底（活桶压源格 = 纯粹的挤没）。
5. **渲染双轨收敛为单轨**：桶不再携带 flow 字符串 ⇒ `postTickSync` + 桶轨渲染退役，
   Q5 容器轨（`ContainerRuntimeCache.flushToClients` + `LivingItemSyncPacket`）成唯一轨道。

**代价（诚实列出）**：

- 多一步交互：放桶 ≠ 有水，要倒一下。原版直觉（手持桶右键）让这步很自然，可接受。
- 失去「临时水」：A 下桶拿走水就没了（自清洁）；B 下每次倒水留下永久资产，清理要汲走
  （一击）。需写进玩家预期。
- 活化后「立刻见效」的演示感没了；alpha 无旧存档兼容，现存活水桶变惰性载体。

**时序硬约束**：渲染轨（F5/Q5）必须与取消桶源**同批或更早**落地——桶不再携带 flow 字符串、
容器轨未就位 ⇒ 水不可见。

**对 F 队列的修订**：F1 缩水（无桶源记账，播种只读 `generatedSources`）；F3 改为
「`LivingWaterBucketFunction` 瘦身为交互型（类比活打火石：无 tick）+ FluidStack 化」；
F5 提前为与桶源取消同批。

**拍板后续（口径更新）**：§一的「桶源/派生源」二分作废——**源只有一种**（容器资产）；
汲水对一切源生效（row 6 特例废）；守恒律硬化（水在桶里 = FluidStack，在世界 = 源，
二者互斥，经倒/汲转换）；机制一/四措辞 = 「活桶（空）汲入 / 活桶（满）放出」。

---

## 〇.6、流体渲染自适应性评估（2026-10-03）

**结论：当前不是自适应的——三处水硬编码**（`AbstractContainerScreenMixin` 渲染段）：

1. **数据源**：`collectWaterBuckets` 只认活水桶物品组件里的 flow 字符串（桶轨；
   桶源取消后本就要退役，见 §〇.5）。
2. **贴图**：`block/water_still` / `block/water_flow` 写死（block atlas）。
3. **颜色 / alpha**：`setShaderColor(0.25, 0.5, 1.0, …)` 蓝色调写死；alpha 归一写死
   `level / 7`（水的 maxLevel）——岩浆（maxLevel 3）接入即错。

**但渲染机制本身是流体无关的**：槽位→格子映射、方向旋转、blit 全部通用；
**动画是白拿的**——atlas sprite 随原版动画 mcmeta 每 tick 上传新帧，blit 即动
（水如此，岩浆/模组流体同样如此）。

**自适应化路径（并入 F5）**：

- 贴图/颜色：NeoForge 标准客户端接口 `IClientFluidTypeExtensions.of(fluid)` →
  `getStillTexture()` / `getFlowingTexture()` / `getTintColor()`——原版水/岩浆与正确注册
  client 扩展的模组流体**全免费**；alpha 用 `FluidFlowBehavior.maxLevel()` 归一（分档注册表现成）。
- 数据格式：现 flow 字符串 `slot:level:fromSlot` **无流体维度** ⇒ Q5 轨 payload 每 cell
  须带 fluidId（`exportFlowData()` 一并扩）。同容器多流体（水+岩浆同屏）天然支持。
- 触发源：`collectWaterBuckets`（扫桶物品）→ 按屏幕容器查 Q5 缓存；
  模组流体缺 client 扩展时回退灰色半透明（安全默认，类比行为分档的「未注册 = 静止」）。

---

**活水源** = 容器级永久资产。存放在 `ContainerFluidData.generatedSources: Set<Integer>`（槽位索引），
与桶源（`isSource` 由活水桶注册）并列作为 BFS 播种源。

| # | 口径 | 结论 |
|---|------|------|
| 1 | 派生源独立存在 | 源一旦形成即容器资产，不随水桶离开消失（原版语义） |
| 2 | 挤没无豁免 | **任何**活物品进源格 = 挤没（含活水桶覆盖派生源：挤旧源、注册桶源，槽位仍 level 0 无感）。无叠加态，实现单一判定 |
| 3 | ≥2 邻居判定 | **任意 2/4 方向**（原版语义；实现成本与直线-only 相同） |
| 4 | 缩容转化 | 整槽转化，且 `数量 ≤ 目标最大堆叠` 才执行，否则等待（空桶×16 永不转化，拆分后立即转化） |
| 5 | 倒水目标 | 槽位**无物品**即可（有派生源无妨——原版也能往水源里倒水：桶照空、源不变）；有非活物品不允许；有活物品不允许（那是挤没） |
| 6 | 汲水目标 | 仅限派生源；桶源不可汲（想要水直接拿桶） |
| 7 | 转化节拍 | **无独立节拍**，随流体 tick 即时转化（理由见 §三） |
| 8 | 落盘 | ✅ **已完成**（框架侧 B.5，2026-10-04）—— BE 容器：`CONTAINER_FLUID_DATA` 附件 `.serialize(CODEC)`；玩家背包 / 末影箱：**Player attachment** `CONTAINER_FLUID_DATA_PLAYER`。⚠️ 实装值类型**不是**本行的 `Set<slot>`，而是 `ContainerFluidData`（只存槽位会丢流体类型） |

**自愈性**（挤没的天然保险）：挤没/汲走后若邻域仍 ≥2 源，晋升规则自动重新派生。
水网核心拆不掉，孤立源一挤就没。

**守恒律**：倒水/汲水不改变「活水桶+活水源」总数。1 桶=1 可移动源；2 桶夹 1 格 bootstrap
出 3 永久源且桶完好归还。增长只来自晋升规则。量产通道（机制三）产出非活水桶，
不参与倒/汲循环，可逐把活化后加入活经济。

---

## 〇.7 ✅ 已结案：活水桶右键「仍走原版逻辑」—— 真因是取水闭环断链（2026-10-04）

**症状**（用户游戏内实测）：手持活桶在容器界面**右键**，执行的是**原版**行为，汲/倒**没有拦截**。
⇒ 客户端 `GuiInteractionHelper.tryInteract` 的活桶分支**没生效**（若生效会 `cir.setReturnValue(true)` 取消原版）。

**已核实的（框架侧 2026-10-04）**：

- **拦截链代码上是通的**：`AbstractContainerScreenMixin` / `InventoryScreenMixin` /
  `CreativeModeInventoryScreenMixin` 的 `mouseClicked`(HEAD, cancellable) 都调
  `GuiInteractionHelper.tryInteract(hoveredSlot, button, false, menu)`，返回 `true` 即取消原版；
  Mixin 已在 `living_item.client.mixins.json` 注册。
- 服务端侧确实存在并**已修**一个会丢包的点：`GuiInteractionPacket.resolveSlot` 只认「持活物品」的槽，
  而汲/倒的目标是**空槽** ⇒ 包被丢（判据已放宽为「活物品 **或** 空槽」+ 回归测试 `GuiInteractionPacketTest`）。
- ⚠️ **但修完用户实测仍走原版** ⇒ **客户端根本没拦到**（`tryInteract` 返回 false）——
  问题在**客户端侧**，且不（只）是 `resolveSlot`。

**真因（流体侧 2026-10-04 结案）：拦截链完好，是「满活桶不可能存在」的取水闭环断链** ——
桶源退役后：容器内源唯一入口 = 倒水（需满活桶）；满活桶唯一入口 = 汲水（需已有源）；
活化 `WATER_BUCKET` 是惰性物品（`canApply` 只认 `Items.BUCKET`）；活空桶对世界取水走原版
`BucketItem.use` 会 **new ItemStack 换掉整个物品**（丢活标记）。⇒ 玩家手里永远是**空活桶**：
倒水条件不满足、汲水没有源 ⇒ `matchBucketInteract` 正确返回 null ⇒ 原版行为照常。
§〇.7 当初的四个假设（错物品/carried/快照/mixin）全部不成立。

**修复**：`LivingBucketWorldUse`（`PlayerInteractEvent.RightClickItem`，两端取消）——
空活桶对准世界流体源（含岩浆）右键 → **灌入一桶**（不换物品，只改内容组件，活标记保留）；
满活桶对世界一律取消（原版放水同样 swap 丢活标记，「往世界放水」后补）。
设计含义：**priming = 活化空桶 → 世界水源取一桶 → 进容器倒水**，此后进桶⇄源守恒循环。
回退测试路径：活化空桶 → 对世界水取水 → 容器空格倒水 → 汲回 → 倒回（守恒）。

> 相关：F3（上方任务队列）；框架侧修复提交 `8dae695`（`resolveSlot` 空槽判据——仍是有效修复，
> 倒水目标空槽会经过它）。

**补记（同日，模型修正——用户拍板「空桶⇄水桶物品形态变换」）**：批次二的「同一物品 + 内容组件」
模型有 UX 缺陷——满桶和空桶**长得一样**（都是空桶贴图），且用户的测试物品（活水桶，`WATER_BUCKET`
宿主）不被 `isLivingBucket` 认领。修正为**换宿主模型**：内容组件仍是权威数据，宿主物品跟随内容
变换（空 → `bucket`、水 → `water_bucket`、岩浆 → `lava_bucket`；组件全量保留；数量超宿主堆叠
保持原宿主）；另加**宿主隐含内容**：活化水桶/岩浆桶（无组件）直接视为满桶——旧活水桶与
「活化水桶直取」路径全部兼容。实现：`LivingBucketFunction.withContent`（换宿主唯一入口）+
`getContent` 隐含推导；汲/倒/世界取水三处调用点改写回。

---

## 二、四机制

```
机制一 汲水：光标手持活空桶 右键 派生源格 → 源消失，活空桶→活水桶
机制二 挤没：活物品出现在源格（手放/未来活塞推/任何途径）→ 派生源销毁
机制三 转化：源格上的非活物品按转化表即时转化（空桶→水桶；干海绵→湿海绵暂缓等活海绵；
        混凝土粉末→混凝土逐色 JSON 条目可选）
机制四 倒水：光标手持活水桶 右键 无物品槽位 → 该格诞生派生源，活水桶→活空桶
        （可能触发邻格连锁晋升：凑齐 2 源的格自动补源）
```

- 汲水/倒水走 GUI 交互系统声明式规则 JSON（D2 模式），服务端处理器实现。
- 机制二判定位置：`recalculate()` 播种阶段按槽位内容检查，不关心物品怎么来的——
  未来活活塞零对接成本。
- 水流推动（pushItems）**结构上推不进源格**（源是 BFS 树根，fromSlot=-1，不在任何下游映射里）
  且不应改：机制三依赖物品停留浸泡；源格只接受显式投放。
- 新活物品「活空桶」：`canApply = BUCKET + isLivingItem`，活化规则 JSON 注册，
  转化时保留活标记与宿主数据。

---

## 三、转化节拍为什么不需要（2026-10-03 讨论）

原设想 20t 独立节拍营造「浸泡感」。推翻，理由：

1. **误转化不可能发生**：水流推不进源格，物品只会被玩家/漏斗/活塞显式放上源格——
   每一次转化都是蓄意的。延迟防误伤没有标的。
2. **独立节拍制造活锁**：漏斗节拍（约 8t）快于 20t 转化节拍时，刚插入的空桶会被抽走
   再插入反复横跳，转化永远完不成。即时转化（≤1t 延迟）下漏斗自动链路丝滑：
   插空桶 → 下拍变水桶 → 抽走 → 插下一个。
3. **无进度 UI，延迟纯是等待**：源格没有熔炉式的进度条可看，「浸泡」不可观测。
   视觉手感以后用渲染（涟漪）补，不动逻辑。

兜底：转化表 JSON 条目预留可选 `interval` 字段（默认 0=即时），将来某条目要节拍零成本加上。

---

## 四、跨容器探讨（2026-10-03）

原则：**物品过境，水不过境**。每容器 = 封闭水盆；桶 = 源的手提箱。

### 天然跨容器（零设计）

- **倒水/汲水**：光标操作在任何打开的容器界面都可用，源可随桶在容器间搬运。
- **活末影箱路由模式**：全部路由箱共享 `player_<uuid>_ender_chest` 一个 containerKey
  （`ContainerLivingItemHandler.java:817` 已验证）→ 共享同一份流体数据 → **共享同一张水源网**。
  往任意一个路由箱倒水，所有路由箱里都出现这个源。无线水网，涌现特性，白拿。
- ** vanilla 双箱**：原版大箱子 = CompoundContainer 单容器 54 格 → 天然一张完整水网，
  「两个箱子挨着为什么水不流过去」最常见的诉求已被覆盖。

### 需要现有能力拼装的自动化

- **活漏斗跨容器**（living-hopper-tech 已有跨容器传输）：A 箱的漏斗往 B 箱源格插空桶、
  抽水桶——机制三的跨容器工厂。待实现时验证跨容器方向选取能否精确命中源格。
- **未来活活塞**：跨容器推活物品 = 挤没的机器化入口（破坏性自动化）。

### 明确不做：水流跨容器蔓延

理由：
1. 渲染割裂——每容器屏幕只显示自己的格子，水在屏幕边缘「流出画面」需要对齐、朝向、
   邻接检测和跨容器同步，复杂度爆炸且观感存疑；
2. 源迁移需求已被桶覆盖（守恒律：倒哪汲哪，玩家就是水管）；
3. 物流需求已被漏斗/活塞覆盖；
4. 「容器=区块」的类比（`ContainerFluidData` 类注释）到容器边界为止是合理截断——
   原版水也不流进方块内部。

### 生命周期补充

- 容器被破坏 → `removeDataByPos` 清理 + BE 数据随方块消亡 → **永久源随之湮灭，不返还**。
  想搬家先用桶汲走。原版类比：挖掉水源方块水就没了。
- BE 容器 120s 内存驱逐无害：attachment 落盘后，驱逐后下次访问从 BE 恢复。

---

## 四.5、架构升格：活水源 = 第一种「容器级自主逻辑」（2026-10-03）

> ✅ **已被 1a/1b 取代，留痕**——目标由框架以更优方式实现（`shouldTickWithoutOwnItems`
> 自维持通道，把「容器级逻辑」收敛为「没有物品载体的活物品」，概念数不增）；
> 见 §〇。下文当时的技术判断（发现层全覆盖、门在 grouped 短路）仍然成立。

**修正**：此前「只有源的容器不 tick、源休眠」说法有误。核实（`LivingItem.java:243-298`
+ `ContainerLivingItemHandler.java:430-449`）：ticking 区块内**每个容器 BE 每 tick 都被
`processContainerAt` 拜访**；真正的门是 `processContext` 的 `grouped.isEmpty()` 短路——
空容器只做红石残留归零就 return。问题不是「发现不了」，而是**流体 tick 的所有权挂错了地方**。

**现状**：流体 tick 由 `LivingWaterBucketFunction.tickContainerData`（HasContainerData prio 0）
驱动——数据是容器级的，驱动权却是活水桶的。桶不在场，容器级状态就瘫痪。这正是用户指出的
「当前项目缺少的一层」：**不依托活物品的容器级自主形态**。

**方案：流体 tick 升格为 processContext 的一等阶段，与功能分组解耦**

- 混合容器（有活物品）：流体 tick 从 `runContainerDataTicks`（prio 0 隐含位置）提为独立阶段，
  仍在功能 tick 之后、红石（prio 2）之前——保持「桶先 registerSource，BFS 后跑」的现有顺序。
  触发条件从「有活水桶条目」改为「fluidData 非空」。
- 纯源容器（`grouped.isEmpty()`）：`handleEmptyContainer` 旁新增分支——fluidData 的
  generatedSources 非空时跑同一套流体 tick（BFS/挤没/转化/推动）+ 写回，再走红石归零。
- 玩家背包同构：`processContainer(player.getInventory())` 每 tick 无条件执行（`LivingItem.java:192`），
  同一分支天然覆盖。
- `LivingWaterBucketFunction` 瘦身：只留 registerSource/removeSource（tick 阶段）+
  postTickSync（tooltip 同步）；BFS/推动/转化迁出 HasContainerData 通道。
- 性能：扫描本来就全量做（`scanAndGroupLivingItems`），新分支的额外开销只有一次
  「generatedSources 非空」检查，无源容器零增量。

**由升格解锁的语义**：

- 「苏醒」概念消亡——源永远活着，没有休眠态。
- **纯源容器的机制三自动化成立**：箱子里一个活物品都没有，漏斗照样插空桶 →
  下 tick 转化 → 抽走。这是升格最直接的玩法收益。
- 挤没在纯源分支不可能发生（有活物品就不叫纯源了），判定天然一致。

**升格暴露的新问题——渲染双轨**：

客户端水流渲染读的是**活水桶物品的 flow 字符串**（`AbstractContainerScreenMixin`）。
纯源容器没有桶 → 没有数据载体 → 打开箱子看不见水。需要第二条同步轨：
attachment 网络同步（`.networkSynchronized(STREAM_CODEC)`）+ 渲染端从 BE 附件读取。
⚠️ 待验证：NeoForge **BE** attachment 的客户端同步语义（实体附件自动同步，BE 附件
是否随 getUpdateTag 走需要实测/查文档）。一期可先只做桶轨，纯源渲染放二期。

---

## 五、实现分期（已被 §〇 流体侧任务队列取代，留痕）

> 框架侧已把「地基」做完（且方式不同：自维持通道而非 processContext 分支、
> 渲染轨走 ContainerRuntimeCache 而非 attachment 同步）。执行以 §〇 队列为准。

1. **一期·地基**：`generatedSources` + CODEC + attachment `.serialize`；闭包晋升（任意 2/4）；
   挤没判定；**流体 tick 升格**（processContext 独立阶段 + 纯源容器分支，见 §四.5）；
   BE 容器落盘；内存驱逐保护。
2. **二期·交互与渲染**：活空桶注册（活化规则 JSON）+ 汲水/倒水 GUI 交互规则 + 服务端处理器；
   Player attachment 落盘（背包+末影箱）；attachment 网络同步轨（纯源容器渲染，先验证 BE 附件同步语义）。
3. **三期·转化**：转化表 JSON + reload/list 指令 + 即时转化逻辑；漏斗自动化实测。
4. **测试**：晋升闭包（2/4、连锁、稳定）、桶撤源留、挤没+自愈、汲倒往返守恒、
   CODEC 往返、缩容转化等待、末影箱路由共享源。
5. **文档**：`living-water-bucket-tech.md` 新章 + 顺手勘误（flow 字符串三段式、推送 level 升序）。
