# 活流体（Living Fluid）技术文档

> **文档版本**: 2026-10 v5（原 `living-water-bucket-tech.md`，更名重组）
> **最后更新**: 2026-10-04
> **适用版本**: Minecraft 1.21.1 + NeoForge 21.1.x
> **命名沿革**：原名「活水桶」——桶中心视角。桶源退役 + 换宿主模型后，活桶降级为
> 流体系统的**激活器/操作界面**（类比红电系统的活铜块们），子系统本体是**容器级流体数据**，故更名。

## 目录
1. [子系统关系（红电类比）](#1-子系统关系红电类比)
2. [容器级流体数据（引擎本体）](#2-容器级流体数据引擎本体)
3. [蔓延、晋升与推动](#3-蔓延晋升与推动)
4. [活水源生命周期（唯一源形态）](#4-活水源生命周期唯一源形态)
5. [活桶（载体/激活器）](#5-活桶载体激活器)
6. [流体转化表](#6-流体转化表)
7. [渲染与同步](#7-渲染与同步)
8. [落盘](#8-落盘)
9. [已知缺口与挂起项](#9-已知缺口与挂起项)

---

## 1. 子系统关系（红电类比）

活流体是**容器级子系统**，与红电系统（活涂蜡铜块）同构。三层关系：

| 层 | 活流体 | 红电系统（对照） |
|---|---|---|
| **容器级数据（子系统本体）** | `ContainerFluidData`：派生源 / 蔓延 / 晋升 / 挤没 / 转化 / 推动 | `ContainerPowerData`：相位 / 发电 |
| **行为/数据层** | `FluidFlowBehavior` 分档 + `FluidTransformTable` 转化表（JSON） | 相位域 / 信号元件 |
| **物品层（激活器/载体）** | **活桶**（空桶/水桶/岩浆桶 × 活标记，`instanceof BucketItem` 通吃） | **活铜块们**（活涂蜡铜块 × 活标记） |

**与红电的关键差异——解耦更彻底**：

- 红电：活铜块**必须在场**（发电持续、载体绑定，铜块被拿走发电停止）；
- 活流体：活桶**只在倒/汲的瞬间参与**——水倒进容器，源就是**容器的资产**
  （`generatedSources`），桶可以立刻拿走（守恒律：水在桶里 ⇄ 在世界里，互斥）。
  这是桶源退役（2026-10-03 拍板）达到的形态：**物品层与容器层彻底解耦**，
  桶源（「桶在场即源」）曾是两者之间最后的耦合。

> 一句话：**活桶之于活流体 ≈ 活铜块之于红电——是系统的激活器与操作界面，不是系统本身。**
> 活流体的重量全在容器级引擎上（自维持驱动：连激活器不在场，纯源容器照样 tick）。

### 1.1 关键类和职责

| 类名 | 位置 | 职责 |
|------|------|------|
| `ContainerFluidData` | `domain/water/` | **引擎本体**：flow 表 / 派生源 / BFS / 晋升 / 转化 / 推动 / CODEC |
| `LivingFluidFunction` | `domain/water/` | 自维持驱动（prio 0）+ 快照下发触发 |
| `FluidFlowBehavior` / `FluidFlowBehaviors` | `domain/water/` | 每流体行为契约（canFlow/maxLevel/晋升/转化），未注册 = 静止 |
| `FluidTransformTable` | `domain/water/` | 转化表 JSON 加载与查询（F4） |
| `LivingBucketFunction` | `domain/water/` | **活桶**（载体，交互型无 tick）：判定 / 内容读取 / 形态变换 |
| `LivingBucketInteractSupport` / `Handlers` | `domain/water/` | GUI 汲/倒：活流体数据反查 + 服务端权威执行 |
| `LivingBucketWorldUse` | `domain/water/` | 世界取水 priming（`BucketPickup`） |
| `FluidFlowSyncPacket` / `ServerSync` / `ClientCache` | `network/` + `domain/water/` | 渲染轨：容器级快照下发 + 客户端缓存 |

---

## 2. 容器级流体数据（引擎本体）

### 2.1 核心映射

```
ContainerFluidData = 一个区块的流体状态
槽位              = 方块位置
FlowEntry(level=0, isSource=true)  = 流体源方块
FlowEntry(level=1~N, isSource=false) = 流动流体方块
无 FlowEntry = 空气
活物品   = 阻挡水流的方块（进源格 = 挤没）
非活物品 = 水中的实体（不阻挡，被推动，可在源格共存被转化）
```

**流体类型维度（1b-1）**：每条 `FlowEntry` 带 `FluidType`——**单张 map 带类型**
（一槽只装一种流体，类比一个方块位置）；同槽异种整条覆盖、已占格不被异种覆盖（流体不混色）。

**派生源 `generatedSources: Map<Integer, FluidType>`**——**唯一的源形态**（容器级永久资产，
必须记住流体类型，否则重播种时岩浆源退化成水）。`isEmpty()` 计入派生源
（⚠️ 否则纯源容器被驱动的 `!isEmpty()` 门挡在 tick 外——2026-10-04 修过的真 bug）。

### 2.2 引擎结构（每 tick，`LivingFluidFunction` 驱动）

```
tick(ctx)
  ├─ recalculate：晋升收敛循环
  │    ├─ spread()：播种 generatedSources（挤没判定在此：活物品进源格 ⇒ 永久销毁）
  │    │            + BFS 扩散（活物品阻挡，行为分档：canFlow/maxLevel）
  │    └─ tryPromote()：问行为 shouldPromote(slot, 邻源数)，真则写入派生源并重跑 BFS
  ├─ transformSourceItems：每流体拍在源格问行为 transformItem（机制三）
  └─ pushItems（每 4 tick）：沿水流树下游推动物品
```

**生命周期要点**：数据由 `TickContext` 构造时经 `getFluidData(ctx)` 创建/回填
（1a-4 回归教训：漏创建 ⇒ 水流整体失效）；驱动是**自维持**的
（`LivingFluidFunction.shouldTickWithoutOwnItems` 恒真）——**纯源容器照样 tick**。

### 2.3 查询 API（汲/倒处理器与渲染用）

- **查**：`isSource(slot)` / `sourceFluid(slot)`（非源 null）/ `hasAnySource()` /
  `isGeneratedSource(slot)` / `hasGeneratedSources()`
- **改**：`registerGeneratedSource(slot, fluid)`（倒水）/ `removeGeneratedSource(slot)`（汲走/挤没）
- 桶源 API（`registerSource`/`removeSource`）已随桶源退役删除。

### 2.4 行为分档接缝（`FluidFlowBehavior`）

引擎不认具体流体，扩散参数问 `FluidFlowBehaviors.of(FluidType)`：
`canFlow()`（静止流体只做源）、`maxLevel()`（水 7 / 岩浆 3）、`flowSpeed()`（⚠️ 预留未消费）；
**未注册 = 静止**（安全默认）。晋升 `shouldPromote` / 转化 `transformItem` 为 default no-op。
⇒ 新增流体**零改引擎**：注册一个行为 + （可选）转化表 JSON 行。

---

## 3. 蔓延、晋升与推动

### 3.1 BFS 重算

每 tick 从所有派生源 BFS 重算流动状态（`fromSlot` 记录 BFS 父节点）：
多源取最近 level；活物品阻挡；**已占格不被异种流体覆盖**。
水源移除后不可达的流动立即消失（每 tick 全量重算，无残留）。

### 3.2 晋升（原版无限水）

收敛循环：流动格问行为「该格是否升源」（水：≥2 邻源，任意 2/4 方向）→
真则写入 `generatedSources`（永久资产）→ 重跑 BFS 直到不动点。
岩浆等其它流体吃默认 no-op（永不晋升）。**挤没自愈**依赖此机制：
夹缝源被挤没后，物品移走且邻域仍 ≥2 源 ⇒ 自动重新派生。

### 3.3 物品推动（pushItems，每 4 tick）

物品沿水流树**下游子节点**推动（自然拐弯绕障碍），level **升序**处理；
堆叠合并；`moved` 集合防级联双推；`ctx.setItem()` 统一走 IItemHandler（大箱子读写一致）。
安全约束：**不推入源格**（机制三依赖物品停留浸泡；源格只接受显式投放）——
结构上也不可能：源是 BFS 树根，不在任何下游映射里。

---

### 3.4 原版蔓延机制参考（1.21.1 源码，2026-10-05 存档）

原版流体蔓延的关键参数与机制（`FlowingFluid` / `WaterFluid` / `LavaFluid`）：

| 机制 | 原版实现 | 容器版对应 |
|------|---------|-----------|
| **每格节拍** | 每格计划刻自驱：水 `getTickDelay=5`、岩浆 `=30`（下界 10）——生长天然渐进（前沿一格一格爬） | 引擎为每 tick 全量重推（瞬时），「慢蔓延」需加每格推进记忆——**引擎最大架构变更，挂起**（见 §9） |
| **横向距离** | `getDropOff`：水 1（7 格）、岩浆 2（约 3 格）——流量逐格衰减到 0 停 | `maxLevel 7/3` 已抽象覆盖 ✓ |
| **源形成** | `getNewLiquid`：水平相邻**同流体源 ≥2** 且**下方是固体或同流体源** ⇒ 本格升源 | 晋升（§3.2）：≥2 邻源（2D 无「下方」条件） |
| **可否繁殖** | `canConvertToSource`：水=gamerule；**岩浆=仅下界**（`ultraWarm`） | 水行为 shouldPromote；岩浆吃默认永不 ✓ |
| **DOWN 特例** | 向下蔓延优先于横向；向下成功且 `源邻居 ≥3` 才同时横流 | 2D 网格无 DOWN，不映射 |

> ⚠️ 若未来做慢蔓延：原版参数即规格（tickDelay 30、dropOff 2、晋升仅下界）。
> 代价：引擎从「纯函数全量重推」转向「带每格推进记忆的模拟」——1b-1 以来最大架构变更，
> 晋升/挤没/转化/推动全部改读「实际 level」。**仅当实测证明岩浆瞬时手感违和时立项。**

### 3.5 跨流体反应——原版规则与架构范本（岩浆接入参考，2026-10-05 存档）

**原版规则（两个反应点）**：

1. **岩浆侧被动触发**（`LiquidBlock.shouldSpreadLiquid`）：岩浆方块收到邻居更新时，扫**自身四横邻**——
   有水 ⇒ **自身转化：岩浆源→黑曜石 / 流动岩浆→圆石**（levelEvent 1501 熄灭音效），
   **返回 false（蔓延中止），水一格不动**——是岩浆把自己转化掉，不是「水灭岩浆」；
   （soul soil + blue ice → basalt 变种，跳过）
2. **DOWN 特例**（`LavaFluid.spreadTo`）：岩浆向下蔓延遇水 ⇒ 目标格变**石头**，岩浆不下落
   （2D 网格无 DOWN，不映射）。

**架构范本：NeoForge `FluidInteractionRegistry`**——`addInteraction(源流体, 反应)`，
每流动方向逐一检查、**首中执行**、反应即中止蔓延；**原版水岩浆反应已迁入此机制**
（`LiquidBlock.neighborChanged` 调 `canInteract`）。⇒ 「流体×流体→反应」在生态里
本就是**注册表化**的，容器版用同构设计（行为接口 default 方法或 JSON 反应表）语义不违和。

**容器映射（v1 提案，待拍板产物形态与覆盖范围）**：

| 场景 | 原版规则 | 容器映射 | 实现落点 |
|---|---|---|---|
| 倒水进岩浆源格 | 黑曜石 | 岩浆源湮灭 + 产物（替换现在的「整条覆盖」） | `pour` 处理器（流体侧独立可做） |
| 水蔓延撞岩浆源格 | 岩浆源→黑曜石，水继续 | 岩浆源湮灭 + 产物 + 水占据 | `recalculate` 扩散遇异种占格时查反应表（**fluid 侧领地**，不碰引擎） |
| 岩浆蔓延撞水源格 | 圆石，岩浆中止、水保留 | 产物 + 岩浆不进格 | 同上 |
| 反应注册表 | `FluidInteractionRegistry` | 行为接口 default 或 JSON 表 | 两条路径（倒水/蔓延）**共用一张表**，规则单源 |

> ⚠️ 注意：蔓延路径的反应**不需要引擎钩子**——`recalculate` 在 `ContainerFluidData`
> （fluid 侧领地）内，只有选择改 `FluidFlowBehavior` 契约形态时才需要框架。

## 4. 活水源生命周期（唯一源形态）

```
诞生：倒水（活桶排出）/ 晋升（≥2 邻源）/ 落盘回填
死亡：汲走（活空桶）/ 挤没（任何活物品进源格）/ 容器销毁（attachment 湮灭，不返还）
```

**守恒律**：倒水/汲水不改变「桶里的水 + 世界（容器）里的源」总量——水要么在桶里
（`BucketItem.content`），要么在容器里（源），互斥，经倒/汲转换。
增长只来自晋升（2 桶夹 1 格 bootstrap 出第 3 源）。量产通道（转化表）产出**非活**水桶，
不参与循环，可逐把活化。

**跨容器口径**：物品过境、水不过境。每容器封闭水盆；桶 = 源的手提箱；
vanilla 双箱 = CompoundContainer 单容器天然一网；末影箱路由模式共享 containerKey ⇒ 共享水网。
容器破坏 → `removeDataByPos` + BE 消亡 → 源湮灭。

---

## 5. 活桶（载体/激活器）

**桶只是载体，零私有状态**（2026-10-04 逻辑纠偏定稿——曾发明私有内容组件 +
宿主映射 + 隐含推导，全数拆除）。桶侧逻辑只有「右键切换物品」一下。

### 5.1 判定与内容

- 「活桶」= 任意 `BucketItem`（`instanceof`，原版 + 模组桶通吃）× `IS_LIVING`；
- 内容状态 = 原版 `BucketItem.content`（public 字段）：活化水桶天然装水、空桶天然空；
- 形态变换 `withFluid(stack, fluid)`：汲入 X → `X.getBucket()`（模组流体自动兼容），
  排空 → `Items.BUCKET`；组件全量保留；形态不变零新对象。

### 5.2 世界侧：零拦截（2026-10-04 最终定稿）

活桶在世界里就是**普通桶**——原版取/放水原生行为，不做任何拦截或重放：

- 活水桶对世界放水 → 原版放置 → 换成普通空桶（活标记随 swap 丢失）；
- 活空桶对世界取水 → 原版拾取 → 换成普通水桶（同上）。

**想要活标记就再点活按钮**（活化无门槛、无消耗），不做任何兜底。
由此 priming 塌缩为一步：**活化水桶直取**——原版水桶本身就是满水
（`BucketItem.content`），活化即得满活水桶，倒进容器即开工；
活空桶的世界汲水路径因此失去意义（汲完还得重新活化，不如直取）。

> 沿革：取水闭环断链 ⇒ `RightClickItem` 拦截（事件路径错误）⇒ 补 `RightClickBlock`
> （use/useOn 双路径）⇒ 交换点 Mixin 提案 ⇒ 最终裁定：**为一个标记做这么多兜底不值得，
> 活化按钮就是兜底**。世界侧拦截层（`LivingBucketWorldUse`）整体删除。

### 5.3 GUI 汲/倒

**链路**：客户端 `GuiInteractionHelper.tryInteract` 活桶分支（按 `FluidFlowClientCache`
快照**精确判定**，不命中不拦截——目标条件是「空槽位 + 容器级源状态」，物品中心规则
表达不了）→ `GuiInteractionPacket` → 服务端 `LivingBucketInteractHandlers`（**权威重验**）→
`LivingBucketInteractSupport`（`ContainerContexts.resolve` 反查活流体数据 + 内容/源增减，
`menu.setCarried` 写回）。

- 倒水语义：无源格诞生派生源；**已有源「源不变」仅排空**（原版往水源里倒水）；
- 汲水语义：源消失（桶源退役后一切源可汲）+ 桶灌入；
- ⚠️ 踩坑：目标槽是空槽而 `resolveSlot` 曾只认活物品 ⇒ 包被丢（已放宽「活物品或空槽」）；
- 已知缺口：末影箱汲/倒（`ContainerContexts` 无末影箱分支，见 TODO.md）。

---

## 6. 流体转化表（F4）

源格上的非活物品按表转化（机制三），数据驱动：

- 条目 `{id, fluid, input, output}`（流体维度键——水/岩浆各走各的）；
- 三层来源：内置 `assets/living_item/fluid_transforms.json` + 玩家差异
  `config/living_item/fluid_transforms.json`（追加/按 id 覆盖/`removed[]` 删除），
  坏条目 WARN 跳过（沉默即缺陷）；
- 口径：**整槽等量替换** 且 `数量 ≤ 产物最大堆叠`（缩容等待：空桶×16 不转化）；
  转化即时（≤1t，无节拍——`interval` 不解析，不为假想需求扩接缝）；
- 内置 17 条：水-空桶→水桶 + 16 色混凝土粉末→混凝土；
- 指令：`/livingitem transforms reload|list`。

---

## 7. 渲染与同步

**容器轨（唯一轨道）**：`LivingFluidFunction.tickContainerData` 尾部 →
`FluidFlowServerSync.flushAfterTick` → `FluidFlowSyncPacket`（**流体调色板** +
slot/level/fromSlot）→ 客户端 `FluidFlowClientCache` → `AbstractContainerScreenMixin`
自适应渲染（`IClientFluidTypeExtensions` 贴图/染色，alpha 按 `maxLevel` 归一；动画白拿）。

- **不依赖任何活桶物品**——纯源容器的水也能画；
- 玩家背包直发本人 / BE 容器菜单匹配（大箱子 `CompoundContainer` 特判）；
- **边沿清屏**：数据从有变无（汲走最后一个源）⇒ 一次性下发空快照
  （`CLIENT_ACTIVE` 状态机），否则客户端旧水永不清除；
- **跨存档**：`CLIENT_ACTIVE` 挂 `onServerStopped`；客户端缓存挂
  `LoggingOut` 兜底清（`FluidClientCacheCleanup`——`removed()` 清缓存与服务端
  最后一拍发包有竞态窗口，残留会跨存档存活）。

---

## 8. 落盘

| 容器 | 载体 | 内容 |
|---|---|---|
| BE 容器 | `CONTAINER_FLUID_DATA` attachment（`.serialize(CODEC)`） | 只存 `generatedSources`（流动每 tick 重算，不落） |
| 玩家背包 / 末影箱 | **Player attachment** `CONTAINER_FLUID_DATA_PLAYER`（`KEYED_CODEC`，一个玩家两个容器键） | 同上 |

⚠️ 数据变空时**必须写回 EMPTY**（BE 与玩家两条路径都要）——不清则重进存档
从附件回填**源复活**（跨存档残留，2026-10-04 修）。

---

## 9. 已知缺口与挂起项

- 末影箱汲/倒：`ContainerContexts` 无末影箱分支（`EnderChestContainerContext` 私有，等框架）；
- 岩浆/模组流体接入（一行注册 + 转化条目）；跨流体交互策略待拍板（水+岩浆相遇当前=互不侵犯）；
- 满活桶对世界放水（复用 `emptyContents`）+ 满桶对世界无反馈 UX；
- 慢蔓延：挂起（§3.4 原版参数已存档——tickDelay 30/dropOff 2；引擎需带记忆模拟，仅实测违和后立项）；
- 岩浆烧毁物品：独立风味项（岩浆源格非活物品周期性摧毁——与水浸泡转化对偶），挂起；
- 游戏实测：漏斗自动化活锁、多流体同屏渲染、创造模式。
- 挂起项台账：[TODO.md](../TODO.md)。

---

## 附录：容器水流示例

```
9列容器中的水流蔓延：
┌───┬───┬───┬───┬───┬───┬───┬───┬───┐
│   │   │L2 │   │   │   │   │   │   │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│   │L1 │L2 │L3 │   │   │   │   │   │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│   │源 │L1 │L2 │   │   │   │   │   │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│   │L1 │L2 │L3 │   │   │   │   │   │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│   │   │L2 │   │   │   │   │   │   │
└───┴───┴───┴───┴───┴───┴───┴───┴───┘
源 = 派生源（generatedSources），向 4 方向蔓延（按行为 maxLevel）
```

## 附录：验证清单（重构后必查）

### 基础
- [ ] 纯源容器（无活物品）照样 tick / 蔓延 / 渲染
- [ ] 倒水诞生源；已有源「源不变」仅排空
- [ ] 晋升：两源夹一格补第三源（任意 2/4）；相邻不繁殖
- [ ] 挤没：活物品进源格销毁；邻域 ≥2 源自愈
- [ ] 汲走：源消失 + 桶形态变换；守恒往返
- [ ] 同槽异种覆盖；已占格不被异种覆盖

### 同步与渲染
- [ ] 渲染不依赖活桶物品（纯源容器可见）
- [ ] 汲走最后一个源 ⇒ 渲染当拍清除（边沿清屏）
- [ ] 跨存档：换存档后首次打开不残留（LoggingOut 兜底）
- [ ] 大箱子 / 背包 / 末影箱（除汲/倒缺口）渲染正常

### 落盘
- [ ] BE attachment 与 Player attachment 往返
- [ ] 数据变空 ⇒ 附件写回 EMPTY（跨存档复活守卫）
- [ ] 缩容转化等待；等比转化整槽通过

## 附录：变更记录（历史）

> v2/v3 时代的详细变更（桶源时代）保留如下作参考；2026-10 的架构演进
> （1a/1b 框架、桶源退役、逻辑纠偏）见 [changelog.md](../archive/changelog.md) 与
> [idea.md](../idea.md) §〇。

### v2（2026-07-28）：数据迁独立 DataComponent；ContainerFluidData 归属 ContainerSnapshot
### v3（2026-07-29）：BFS 重算重构；四方向贴图；创造模式复制修复；沿水流树推动
### v4（2026-08-16）：汲/倒交互链（F3）；通用流体框架（1b）；桶源退役与换宿主（详见 changelog 2026-10-03/04）
