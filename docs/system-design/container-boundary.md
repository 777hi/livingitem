# 容器边界 —— 能力面、写回与身份口径

> **状态：生效（2026-10-18）** —— 不变量已登记 `docs/decisions.md` 的 `D-container-01..06`。
> ⚠️ **口径生效 ≠ 实现落地**：每条不变量下方都有「现状」标记，`⏳` 表示代码尚未满足
> （实施顺序见 [buffer/container-compat-topic.md](../buffer/container-compat-topic.md) §2）。
>
> **本文是契约层**：只写不变量与判据，不写实现清单、不写「哪些模组已适配」（那类清单必然漂，见
> [docs/README.md](../README.md) 铁律 3）。
>
> 关联文档：
> [container-identity.md](container-identity.md)（多方块容器的**身份句柄**）·
> [living-item-infrastructure.md](living-item-infrastructure.md)（容器抽象层实现）·
> [guides/container-compatibility.md](../guides/container-compatibility.md)（列数规则的**操作指南**）·
> 专题计划 [buffer/container-compat-topic.md](../buffer/container-compat-topic.md)

---

## 0. 本文防什么

本模组的世界模型是「**槽位是精确地址**」：活熔炉知道自己在槽 17，它的产物写回槽 17，它的邻居是槽 16/18。
而 `IItemHandler` 的契约允许「**槽位只是建议位置**」：`insertItem(slot, stack, false)` 的返回值只承诺
「物品被接受了一部分」，**不承诺落进那个槽**。

两套模型相遇时，产生的不是「兼容性瑕疵」，而是**数据损坏**：写回错槽、物品被吞、物品被复制。

⇒ 因此本文不定义「如何适配某个模组」，而定义「**本模组对任意符合 `IItemHandler` 契约的容器都必须成立的性质**」。
兼容性测试矩阵永远装不完所有模组；能装完的只有不变量。

---

## 1. 两条模型的对撞（实证）

| 本模组的假设 | `IItemHandler` 契约允许的 | 实证 |
|---|---|---|
| `getStackInSlot(i)` 读到的就是槽 i 的东西 | 成立 | —— |
| `extractItem(i, n, false)` 只动槽 i | 成立 | —— |
| `insertItem(i, s, false)` 落进槽 i | **不成立** | 精妙背包（SophisticatedCore）的 `InventoryHandlerSlotTracker` 在 `simulate=false` 分支**丢弃 slot 参数**，改走整库插入：同款物品部分满槽 → memory 槽 → 过滤器槽 → 编号最小的空槽 |
| `extractItem` 一定把物品取出来 | **不成立** | 带 IO 过滤器升级时 `FilteredItemHandler.extractItem` 会返回 `EMPTY`（输出过滤器不匹配） |
| `getSlots()` / `getSlotLimit(i)` 可信 | 成立 | 精妙背包堆叠升级后 `getSlotLimit` 可达数百 |

> ⚠️ **这不是精妙背包的 bug**。它的整库插入语义对「管道往容器里塞东西」是**正确且更好**的
> （物品自然堆叠、遵守记忆槽位）。错的是本模组把 `insertItem` 当成了「写回指定槽位」。

---

## 2. 不变量（违反即 bug）

每条标注**现状**：`✅` 已满足 · `⏳` 待收敛（专题内修） · `—` 不存在该能力。

### I-C1 容器发现只认能力面

容器 = 某位置能取到 `Capabilities.ItemHandler.BLOCK`。`instanceof Container` **不得**用作
「这里是不是容器」的判据。

- **违反后果**：有物品能力但不是原版 `Container` 的模组容器（精妙背包方块、ProjectE 炼金箱）
  被判定为「不是容器」，于是列数推断、指令校准、viewer 匹配全线失效。
- **现状**：`LivingItemContainerCommand` 曾犯（2026-10-18 已改为能力面）；其余消费点见 I-C5。
- **判据**：全库 `grep "instanceof Container"`，每一处都必须能回答「我为什么需要原版语义」（见 I-C2）。

### I-C2 原版 `Container` 只用于原版语义

允许的用途仅三类：`canPlaceItem`、`getMaxStackSize`、随机战利品表判定（`getLootTable`）。

- **禁止**用它当「容器存在」的判据（I-C1）。
- **禁止**直接用它的槽位编号读写：多方块容器的 `Container` 与合并后的 `IItemHandler`
  可能整体错位 ⇒ 必须先过槽位体系探针（[living-item-infrastructure.md](living-item-infrastructure.md) §10.25）。
- **现状**：✅ 探针已存在；`getWidth` 的 Tier 2 类型表以 `Container` 为门槛属**合法用法**
  （该表只描述原版容器类型，非 `Container` 容器本来就不在表内）⇒ 走规则表或启发式。
  2026-10-18（S4）起精妙背包六档容量已进内置规则表（27/45/54/81 = 9 列，108/120 = 12 列）。

### I-C3 写回 ≠ 插入

- **改「自己槽位里已有的那件物品」** ⇒ `IItemHandlerModifiable.setStackInSlot`。
- **`insertItem` 只表达「把物品放进这个容器」**，不表达「放进这个槽位」。跨容器传输（活漏斗往邻居塞）
  用它是对的；写回自己的槽位用它**必错**。
- **现状**：✅ 已满足（S2，2026-10-18）—— `SimpleContainerContext.setItem` 优先
  `setStackInSlot`，回退分支校验抽取完整性 + 写后校验；本项目的 `LivingChestItemHandler` /
  `LivingEnderChestItemHandler` 也补了 `setStackInSlot`（它们的 `insertItem` 同样是整库语义）。
- **判据**：`setItem` 的实现里出现 `insertItem` 即违规（回退分支除外，且必须校验抽取结果）。

### I-C4 模拟与真实必须同源，且模拟只是上界

- 同槽位、同 handler、同判据。
- 模拟结果**只能当「最多能放多少」的上界**，不得当作「真实写入必然成功」的承诺——真实写入可能被
  整库重定向（I-C3 的实证）。
- **违反后果**：活漏斗「模拟说能放 → 实际塞到别处 / 塞不进」，配合 I-C3 的吞物品分支即**静默丢物品**。
- **现状**：✅ 已满足（S2，2026-10-18）—— 写回不再经过 `insertItem`，模拟与真实走同一槽位；
  守卫 `ContainerBoundaryContractTest#simulateAndRealAgreeOnTargetSlot`。
  ⚠️ 仅对**可修改** handler 成立；不可修改时回退路径仍受对方 `insertItem` 语义影响，
  靠 I-C7 的写后校验兜底。

### I-C5 身份与 viewer 匹配按位置键，不按 `Container` 实例

- 容器身份 = `ContainerIdentity.getCacheKey()`；viewer 匹配 = 「这个菜单槽属于哪个**容器位置**」。
- **禁止**用「菜单槽的 `slot.container` 是否等于某个 `Container` 实例」来判断玩家是否在看该容器。
- **违反后果**：非 `Container` 容器永远匹配不到 viewer ⇒ 运行时 tooltip 遥测、活流体渲染、
  酿造台快照、活信标开盖**全部静默不发**（`LivingItemRuntimeSync` 对空集合直接 `continue`）。
- **现状**：✅ 已满足（S3，2026-10-18/19）—— 新增 `MenuSlotOwners` 负责「菜单槽 → 容器位置」
  解析，**三级**（可靠 → 脆弱）：
  ① 结构化（`slot.container instanceof BlockEntity` / `CompoundContainer`，无反射、永不失效）→
  ② 注册的 compat（`registerMenuResolver`，编译期安全，**模组容器的正解**）→
  ③ 反射兜底（菜单的 `getBlockPosition()` / `getBlockPos()`，按类缓存）。
  `ContainerContexts.isViewingPositions` + `LivingItem.runtimeContainerPositions` 走位置口径；
  `SimpleContainerContext.syncWorldContainer`（图标刷新）与 `LivingItemRuntimeSync`（tooltip 遥测）
  已切换。
- ✅ **S3b 已完成（同日）**：`FluidFlowServerSync`（活流体渲染）、`LivingBrewingStandFunction`
  （酿造台音效目标）也切到位置口径 ⇒ **viewer 匹配里 `Container` 已彻底退场**：
  `ContainerContexts.isViewing(Collection<Container>)` / `ownsContainer` /
  `ContainerRuntimeCache.findViewers / isViewing / menuContains` 整条删除
  （其中 `menuContains` 在 S3 后就已是死代码）。
- ✅ **`Container` 的剩余用途全部落在 I-C2 允许的三类内**（`ContainerContext.getContainer` 的
  7 个调用点：槽位体系探针 / 跨容器传输 / 邻居交互），**不参与 viewer 匹配**。
- ⚠️ **反射兜底的已知风险**（用户 2026-10-19 指出）：模组改名即**静默失效**；泛名字可能**误命中**
  语义不同的方法（把包发错人）。缓解：① 只认 `getBlockPosition` / `getBlockPos`，**不认 `getPos`**；
  ② 返回类型必须是 `BlockPos` / `Optional<BlockPos>`；③ 失效时打 `ModLog.CONTAINER` debug（每类一次，
  满足 I-C6）。**但反射终究是兜底 —— 要长期可靠必须走 ② 的注册口。**
- ⚠️ **不要试图用 handler 实例反查位置**：第三方容器的能力面 handler 与 GUI 槽的 handler
  可能隔了多层包装（精妙背包隔三层），而 NeoForge 的 `SlotItemHandler` 内部 `container`
  是**每槽一个匿名实例**，与任何东西都比不上。⇒ 必须走注册式解析器。

### I-C6 降级必须可观测

「能 tick、不能同步」只允许作为**临时**降级，且必须在日志或调试命令里看得见。

- **禁止**用 `if (集合为空) continue;` 静默吞掉整条轨。
- **现状**：⏳ `LivingItemRuntimeSync` 空集合分支无日志。

### I-C7 写后校验（对未知模组的兜底）

任何写回操作，完成后必须校验「目标槽内容 == 期望」。不成立时：回滚或告警，**不得静默继续**。

- 这条是 I-C3/I-C4 的兜底：即使将来遇到一个既不能 `setStackInSlot`、`insertItem` 又不老实的
  handler，也不会静默丢物品。
- **现状**：✅ 已满足（S2，2026-10-18）—— 回退路径做「抽取完整性校验 + 写后 `ItemStack.matches`
  核对」，不成立即告警。超量写入按**槽位上限**截断并告警（不按物品 `maxStackSize`，
  否则会砍掉超大堆叠容器的合法堆叠）。

### I-C8 tick 宿主必须是「格容器」，物品池只能当传输端点

两类东西都实现了 `IItemHandler`，但能力完全不同：

| | **格容器（grid）** | **物品池（pool）** |
|---|---|---|
| 例子 | 箱子、精妙背包、潜影盒、活箱子 | AE2 ME 网络、RS 网络、存储元件 |
| 物品的存在形态 | 占据一个**被寻址的格子** | 按**类型**聚合的计数 |
| 能否回答「槽 i 的右邻居是谁」 | 能（`i+1`） | **不能**（没有「旁边」） |
| 能否回答「我是第几格」 | 能 | **不能** |

- **判据（可操作）**：能回答「槽 i 的右邻居是谁」⇒ 格容器；只能回答「有没有 64 个铁锭」⇒ 池。
- **池不得作为 tick 宿主**。本模组没有任何一个功能能在无槽位的宿主里表达自己：
  依赖几何的（熔炉 / 酿造 / 耕地 / 漏斗 / 红石 / 电力 / 流体 / 应力 / 信标）直接调
  `getWidth()`；依赖「我在第几格」的（箱子 / 末影箱 / 工具 / 地图）至少要一个稳定槽位。
- **池可以作为传输端点**（长期）：`SlotAccessor` 只需要 extract/insert，不需要几何
  ⇒ 活漏斗向 ME 接口推/拉是成立的。方向是**接口而非宿主**。
- **现状**：— 未判定。扫描入口目前只要 `ItemHandler.BLOCK` 非空就当格容器（与 I-C1 同一处）。
  ⚠️ 当前扫描器大概率碰不到 ME 网络（它不作为方块 `IItemHandler` 暴露），所以这条**不是当前 bug**；
  它是 S5 展开时必须有的判据——否则会把「存储元件的内部 handler」当格容器算列数。
- **推论（兼容方向）**：若将来要接 AE/RS，正确做法是给 `SlotAccessor` 加一个池实现
  （按类型 extract/insert），**不是**给 `ContainerContext` 加池实现。
  后者等于给「没有几何的东西」编造几何——那正是 I-C7 说的「不得静默继续」的反面。


---

## 3. 两种「嵌套」必须分开（2026-10-18 澄清）

「嵌套」在本项目里指两件**机制完全不同**的事。混为一谈会得出「不做嵌套」或「必须支持任意深度」
两种错误结论。

| | **扫描展开** | **功能内部递归** |
|---|---|---|
| 谁发起 | 每 tick 的扫描器（`LivingItem.onServerTick`） | 某个功能的 `tick` 内部 |
| 方向 | 外 → 内（在宿主容器里发现「本身是容器的物品」） | 内 → 更深（自己的虚拟存储里还有活物品） |
| 例子 | 玩家背包里有一个精妙背包，背包里有活熔炉 | 活潜影箱内部有一个活潜影箱 |
| 现状 | **深度 0**（完全不展开） | **不存在**：`ContainerLivingItemHandler.processContext` 在功能侧的唯一调用点是掉落物容器 |
| 设计上限 | 1（专题内） | 活潜影箱设计稿写 4 层（见 [buffer/活潜影箱实现细节.md](../buffer/活潜影箱实现细节.md) §1.4 / §8.1） |
| 主要风险 | 键路径化、每 tick 遍历量、运行时数据作废语义 | 环、账本归属（电力/应力记哪一层）、性能 |

### 3.1 深度预算是**一条**，不是两个上限

一次 tick 只分配**一条深度预算**，每展开一层减 1，归零即停。扫描入口分配总额；功能内部递归
消费**同一条**预算。

- **为什么**：若扫描器给 1、封装给 4，两层上限各自正确，合起来是 5 层 ⇒ 总遍历量失控。
- **推论**：活潜影箱的 4 层是「**没有外层扫描时**」的上限；若它本身是被扫描展开出来的（第 1 层），
  内部只剩 3 层。这个减法必须由预算统一扣，不能各算各的。

### 3.2 环检测依赖实例身份

活箱子 / 活末影箱 / 活潜影箱**本身就是容器类活物品**，因此「A 里有 A」在值语义下无法靠
`ItemStack` 判等识别。环检测必须依赖**实例 id**（活箱子已有 UUID 映射可作基础）。

- 现状：— 不存在。活潜影箱设计稿 §8.2「嵌套 UUID 命名空间」为同一问题的另一面。

---

## 4. 明确不做（专题范围边界）

1. **扫描器不做深度 ≥2 的递归展开**。理由：键路径化 + 遍历量 + 作废语义三者同时失控，
   而玩家不会期待「背包里的背包里的熔炉自己烧东西」。
2. **不做「活物品内部容器」的通用递归**。活潜影箱单独立项；它需要的是
   `BoundaryExchanger` + 深度预算 + 环检测三件套，不是扫描器改一行。
3. **不做模组 GUI 布局/皮肤适配**。只保证能力面与槽位语义正确，不保证在对方 GUI 里好看。
4. **不接管第三方模组自己的 tick**。精妙背包的升级（拾取/磁吸/堆叠）照它自己的 `serverTick` 跑，
   本模组只处理自己写入的数据。
5. **不把物品池（AE2 ME 网络 / RS 网络）当 tick 宿主**。理由见 I-C8：池没有槽位与几何，
   本模组没有任何功能能在其中表达自己。长期方向是**传输端点**（给 `SlotAccessor` 加池实现），
   不是宿主。
   ⚠️ **无法阻止的现实**：活物品被 import bus / 磁吸升级吸进池后**静默停摆**——没有钩子可拦
   （`isItemValid` 是问目标槽位的，池不问你）。只能靠 I-C6「降级可观测」兜住。

---

## 5. 验证入口

| 不变量 | 守卫方式 |
|---|---|
| I-C1 / I-C2 | `grep "instanceof Container"` 全库审计（人工，每次改容器层必跑） |
| I-C3 / I-C4 / I-C7 | 守卫测试：`SlotIgnoringHandler`（`insertItem` 忽略 slot）+ `FilteredExtractHandler`（`extractItem` 被过滤器拦）—— 让「写回错槽」「吞物品」在单测里直接红 |
| I-C5 | 守卫测试：非 `Container` 方块实体的 viewer 匹配（`ContainerContextsTest` 已有同类替身场景） |
| I-C6 | 代码审查：`continue` 前必须有日志或调试计数 |

> ⚠️ **替身测试只能验证「我对第三方行为的理解」**。它的价值是**回归**，不是**证伪**。
> 证伪只能靠真机（`runClient` + 真实模组 jar），且只需做一次——把观察结果记进专题文档当实证。
