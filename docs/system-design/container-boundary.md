# 容器边界 —— 能力面、写回与身份口径

> **状态：生效（2026-10-18；2026-10-21 扩到实体宿主并新增 I-C10）** —— 不变量已登记
> `docs/decisions.md` 的 `D-container-01..11`。
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

容器 = 某**宿主**能取到物品能力面。`instanceof Container` **不得**用作「这里是不是容器」的判据。

宿主有四类，各对应一个能力面与一套身份键（键形权威清单在 `ContainerKeys` 的类注释，别处不得再解析）：

| 宿主 | 能力面 | 身份键 | 例 |
|---|---|---|---|
| **方块** | `Capabilities.ItemHandler.BLOCK` | `chest_x_y_z`（单块）/ `container_<dim>_x_y_z` | 箱子、精妙背包方块、ProjectE 炼金箱 |
| **实体** | `Capabilities.ItemHandler.ENTITY` | `entity_<uuid>` | 箱子船 / 箱子矿车 / 漏斗矿车 / 带箱驴骡 |
| **掉落物** | `Capabilities.ItemHandler.ITEM` | `item_entity_<uuid>` | 掉在地上的容器物品 |
| **玩家** | `Capabilities.ItemHandler.ENTITY`（玩家也是 `LivingEntity`）| `player_<uuid>` / `player_<uuid>_ender_chest` | 玩家背包 / 末影箱 |

- **违反后果**：有物品能力但不是原版 `Container` 的模组容器（精妙背包方块、ProjectE 炼金箱）
  被判定为「不是容器」，于是列数推断、指令校准、viewer 匹配全线失效。
- ⚠️ **实体宿主的额外门槛：能力面非空不等于「这是容器」**。NeoForge 给**所有** `LivingEntity`
  注册 `ItemHandler.ENTITY` fallback（主手 + 副手 + 盔甲）⇒ 能力面查得到是**常态**，不是判据。
  实体宿主必须再过一道**白名单**（`ContainerEntity` 接口 + `AbstractChestedHorse#hasChest()`，
  见 `EntityContainers`），否则每个僵尸都会被当容器 tick。这是「能力面原则」在实体上的**唯一**
  例外，且方向是**收紧**（宁可漏判不可误判）——误判的代价是给生物的主手槽倒水。
- **现状**：`LivingItemContainerCommand` 曾犯（2026-10-18 已改为能力面）；实体宿主 2026-10-21 落地
  （`EntityContainerContext`，构造时查不到能力面**直接抛** —— 白名单与注册面不一致就是真 bug，
  符合 I-C7「不得静默继续」）；其余消费点见 I-C5。
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

### I-C5 身份与 viewer 匹配按稳定键，不按 `Container` 实例

- 容器身份 = `ContainerIdentity.getCacheKey()`（= 维度前缀 + **稳定键**）；viewer 匹配 =
  「这个菜单槽属于哪个**容器**」。
- **稳定键的选法**（`D-container-04` 的「位置键」只对方块宿主成立）：
  **方块 → 位置**（`chest_x_y_z` / `container_<dim>_x_y_z`）；**实体 → UUID**（`entity_<uuid>`，
  `D-container-10`）—— 位置对**会动**的宿主不稳定，见下方「实体宿主」条目。
- **禁止**用「菜单槽的 `slot.container` 是否等于某个 `Container` 实例」来判断玩家是否在看该容器。
- **违反后果**：非 `Container` 容器永远匹配不到 viewer ⇒ 运行时 tooltip 遥测、活流体渲染、
  酿造台快照、活信标开盖**全部静默不发**（`LivingItemRuntimeSync` 对空集合直接 `continue`）。
- **现状**：✅ 已满足（S3，2026-10-18/19）—— 新增 `MenuSlotOwners` 负责「菜单槽 → 容器位置」
  解析，**两级**（`D-container-09`，2026-10-19 起反射兜底已移除）：
  ① 结构化（`slot.container instanceof BlockEntity` / `CompoundContainer`，无反射、永不失效）→
  ② **联动模块注册**（`registerMenuResolver`）。
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
- ✅ **反射已下沉到联动模块**（`D-container-09`）：核心不再猜第三方 API 形状。精妙背包的
  `getBlockPosition()` 反射现在住在 `living/compat/sophisticated/SophisticatedMenuResolver`，
  受 `ModList.isLoaded` 门控、**判据先卡包名前缀**（反射不扩散到任意第三方）。
  ⇒ 模组改名时失效的只有该联动模块，核心与其它模组不受牵连（守卫：
  `MenuSlotOwnersTest.coreDoesNotReflectThirdPartyMethod` +
  `SophisticatedMenuResolverTest.ignoresForeignPackageWithSameMethod`）。
- ⚠️ **不要试图用 handler 实例反查位置**：第三方容器的能力面 handler 与 GUI 槽的 handler
  可能隔了多层包装（精妙背包隔三层），而 NeoForge 的 `SlotItemHandler` 内部 `container`
  是**每槽一个匿名实例**，与任何东西都比不上。⇒ 必须走注册式解析器。
- ✅ **实体宿主：键是 UUID，位置每 tick 现取**（`D-container-10`，2026-10-21）——
  实体每 tick 在动 ⇒ 位置键会漂 ⇒ 熔炉进度被洗掉。位置不进键，而是每 tick 由快照提供
  （实体 ctx **每 tick 新建**，与掉落物 ctx 同范式 ⇒ 快照即当前 tick，不存在陈旧）。
  两处相关设计：
  ① **`getBlockPos()` 对实体容器恒返回 `null`** —— 它不是方块容器；且这防了一个真 bug：
  船恰好停在箱子上方时，`getBlockEntity(船位置)` 会拿到**地形里的箱子** ⇒ 活信标误判
  「有盖容器宿主」而把光束漂在下面的箱子上（返回 null 后走「非方块宿主 ⇒ 实体无 owner ⇒
  不开光」的空路径，与 `LidBlockEntity` 判据的本意一致）；
  ② **实体宿主的菜单解析天然结构化** —— `ContainerEntity extends Container` ⇒ 菜单槽的
  `slot.container` 就是船本身，`MenuSlotOwners` 第一级直接命中、**零反射**。所以「实体容器能不能
  做 viewer 匹配」的答案是**能，且不需要任何降级**（曾经预设的「viewer 降级」方案随之作废）。

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
- ✅ **实体宿主是格容器**（2026-10-21）：船 / 矿车 / 驴骡的槽位有编号、有列，满足「能回答槽 i 的
  右邻居是谁」。但「格」的**几何来源不同**：方块宿主靠坐标 + 规则表/启发式，实体宿主靠
  **实体自己的布局 API**（马/驴/骡直接问原版 `getInventoryColumns()`）或启发式，**与
  `getBlockPos()` 无关**（它恒 null，见 I-C5）。⇒ 新增实体宿主时，「列数从哪来」必须能回答，
  答案不能是「它站的那一格」。

### I-C9 容器级持久数据跨形态，只对「内容随物品走」的容器

- **要求**：容器级**持久**数据在「方块 → 物品 → 方块」的转换中迁移，**仅当**该容器破坏后
  物品内容仍然保留。内容随方块消失的容器，数据也随方块消失。
- **为什么**：否则会得到「**空箱子但有水**」——箱子的 27 格物品没了，数据却还在。
  半保留状态比丢数据更让人困惑，而且它把「容器属性」和「容器内容」拆成了两套语义。
- **哪些数据算「持久」**：判据是**存档里有没有它**（附件带 `serialize`）。

  | 容器级数据 | 附件挂在 | 持久化 | 跨形态迁移 |
  |---|---|---|---|
  | 流体 `container_fluid_data` | 数据宿主（见下）| ✅ | ✅ |
  | 电力相位 `container_phase_snapshot` | 数据宿主（见下）| ✅ | ✅ |
  | 应力 `container_stress_data` | 数据宿主（见下）| ❌ 纯运行时 | 不适用（下一 tick 从容器重算） |
  | 红石账本 `ContainerRedstoneData` | 内存表 | ❌ 注释明说「不落盘」 | 不适用（同上） |

  ⇒ **只有「玩家下线重进还能续接」的那类才需要搬**；纯运行时数据丢了会自愈，
  搬它反而可能把「磁盘账本说谎」面做大（与 `PhaseSnapshot` 只存慢变量同一个考量）。
- ⚠️ **「数据宿主」不是一个固定对象，而是 `ContainerContext#getDataHosts()` 返回的列表**
  （2026-10-21）—— 方块容器 = 它的 BE（大箱子两半各一）；**实体容器 = 实体本身**（NeoForge 附件
  对 `IAttachmentHolder` 通用 ⇒ 船/矿车/驴的容器级数据跟实体走，区块卸载再回来还在）。
  读写与回填一律走宿主列表，**不得**假设「有 BE」。这是 I-C9 从方块形态扩到实体形态的唯一改动点。
- **判据（数据声明）**：`container_rules.json` 的 `contentRetaining` 列出「内容随物品走」的
  容器 ID。**默认不在表里 ⇒ 不迁移**（保守：让流体跟内容一起消失）。
- **为什么不做自动判断**：没有任何通用 API 能回答「这个容器的内容会不会跟着掉落物走」——

  | 容器 | 内容怎么跟着物品走 |
  |---|---|
  | 潜影盒 | `minecraft:container` 组件 |
  | 精妙背包 | 自己的 SavedData（按 contents UUID） |
  | 无尽箱子 | `CLUSTER_CONTAINER` 隐式组件 |
  | 原版箱子 / 铁箱子 / 炼金箱子 | **什么都不带** |

  自动判断只能逐个认组件 ⇒ 那是 I-C1「只认能力面」的反面。所以做成**数据**，
  与规则表同源，玩家也可在配置里追加。
- **现状**：✅ 已实现，**三条挂点**（破坏 `BlockDropsEvent` / 放置 `EntityPlaceEvent` /
  第三方交接 `ContainerHandoff`，最后一挂 2026-10-21 真机验证通过）。
  判据统一查 `ContainerCompatibilityConfig.retainsContentsOnBreak`。
  守卫：`ContainerRuleConfigTest.contentRetaining_bundledDeclarations`（谁该搬）、
  `ContainerDataMigrationTest`（搬过去不能坏：流体 CODEC / snapshot 独立性、相位 CODEC / EMPTY 不搬）。
- ⚠️ **事件挂点会「整对失效」，第三个挂点是双向交接**（2026-10-21，`D-container-12`）：
  迁移原本只有「破坏 `BlockDropsEvent` / 放置 `EntityPlaceEvent`」两个**事件**挂点。
  但有些模组**两个都不给** —— 真机实证（精妙背包）：

  | 方向 | 为什么不触发事件 |
  |---|---|
  | 方块 → 物品（蹲下右键拾取）| `putInPlayersHandAndRemove` = `setItemInHand` + `removeBlock`，**不产生掉落物** ⇒ 无 `BlockDropsEvent` |
  | 物品 → 方块（放置）| `BackpackItem extends ItemBase`（**不是 `BlockItem`**），放置走自己的 `tryPlace` + `level.setBlockAndUpdate`，**绕开 `BlockItem.place`** ⇒ 无 `EntityPlaceEvent`；且方块物品与 `diamond_backpack` 是两个不同 item，`asItem()` 也认不出 |

  ⇒ 第三个挂点是**交接**（`ContainerHandoff`），**双向**：

  ```
  capture(host) → handoff(stack)    宿主 → 物品   （第三方把方块变物品）
  stageRestore(stack) → restoreTo(host)   物品 → 宿主   （第三方把物品变方块）
  ```

  **搬运逻辑必须与事件路径共用同一份 `moveOut` / `moveIn`** —— 两条路径语义一旦分叉，
  就会出现「破坏搬得对、拾取搬得歪」这种最难查的 bug。
  发现时机住在**联动模块**（Mixin 挂具体调用点），核心只提供这两对方法 + `registerMover`。
  ⚠️ **暂存必须 copy**：第三方常在放置末尾 `shrink(1)` **就地改**手持栈，存引用会在
  RETURN 时读到空栈（`stageRestore` 内部已 copy）。
- ⚠️ **两种数据的可变性不同，所以搬运方式不同**：`ContainerFluidData` 是**可变对象**
  且与内存表共享引用 ⇒ 必须 `snapshot()` 深拷贝后再交给物品（否则后续 tick 的改动漏进物品）；
  `PhaseSnapshot` 是不可变 record、`capture()` 每 tick 产新对象 ⇒ 直接搬，连创造模式
  连续放置共享一份也无害。
- 🔴 **容器级数据有两个副本，内存表是唯一真相源**（2026-10-21 真机定位，比 mixin 更根本）：

  | 副本 | 谁写 | 谁读 |
  |---|---|---|
  | 内存表 `CONTAINER_DATA`（按 cacheKey）| 功能 tick | 功能 tick（唯一真相源）|
  | 宿主附件（BE / 实体 / Player）| `ContainerFluidWriteback` / `ContainerPhaseWriteback` **每 tick 从内存表覆盖** | 存档加载时的「附件回填」|

  ⇒ **恢复数据（物品 → 方块）必须写内存表**，只写附件会被下一 tick 的 writeback 用空对象盖掉。
  这条对**所有**放置路径成立（不只是第三方交接），`EntityPlaceEvent` 那条也一样。

  ⚠️ 为什么以前「破坏再放回」偶尔能成：`getOrCreateFluidData` 在内存表**没有该键**时才走
  「附件回填」。**换位置**放置 ⇒ 新 cacheKey 无残留 ⇒ 回填生效；**同位置**破坏再放置
  ⇒ 内存表里还留着**空的旧对象**（破坏时只 `clearAll` 清了内容、没删键）⇒ 回填分支不走
  ⇒ 水消失。真机复现的正是同位置场景。

  ⇒ 实现要点：`moveIn` 必须「同一个对象同时放进内存表 + 附件」—— 只放一处，
  要么被覆盖（只放附件），要么容器不 tick 时丢存档（只放内存表）。相位还要额外
  `PhaseSnapshot.restoreInto(ContainerPowerData)` 把快照还原进账本（账本才是真相源）。
- 🔴 **持久化方式由 ctx 自己声明，领域不得假设有 BE**（`D-container-13`，2026-10-22 立项）：
  `ContainerContext#persist(ContainerDataKey<T> key, T value)` —— default 走
  `getDataHosts()` 写附件（BE / 实体 / Player，现状不变），**物品形态 ctx 覆写为写 SavedData**
  （按容器 UUID 索引）。拿不到 `IAttachmentHolder` **不是异常，是一种正常宿主**。
  ⇒ 领域侧从「遍历 hosts 逐个 setData」变成一次 `ctx.persist(...)` 调用，
  “数据存哪”的知识从三个地方收成 ctx 的一个方法。
  ⇒ 收益不只是活背包：新增任何容器形态（活潜影箱、将来的玩家 UUID 化）**零框架改动**；
  且顺带把 `ContainerDataKey` ↔ 附件类型 ↔ CODEC 三元组统一（SavedData 聚合快照的前提）。
  设计稿 [buffer/living-backpack.md](../buffer/living-backpack.md) §3.4。

### I-C10 逻辑槽 ≠ 物理槽，跨边界必须过映射

- **要求**：`ContainerContext` 对外暴露的槽号是**逻辑槽**（只数「存储格」，0 起连续）；
  宿主 handler 的槽号是**物理槽**（可能含**前置非存储槽**）。二者靠
  `toPhysicalSlot` / `toLogicalSlot` 一对**互逆**映射转换，默认恒等。
- **为什么**：`getItem` / `setItem` / `getSize` 加了偏移，只说明**读写**对了 ——
  「对外身份」若仍用物理槽号，就是**五条通道各偏一格**。而「差一格」在 GUI 上表现为
  「东西都在、位置全错」，比丢失更难反推（真机原话：「感觉渲染和逻辑差了一格」）。
- **跨边界点清单（新增前置槽时必须逐条过一遍，缺一即偏一格）**：

  | 通道 | 口径 | 典型位置 |
  |---|---|---|
  | 读写 / 容量 | 逻辑 → `toPhysicalSlot` | `getItem` / `setItem` / `getSize` |
  | 槽位同步比对 | 逻辑 → `toPhysicalSlot` | `syncWorldContainer`（GUI 图标刷新）|
  | 遥测 / 缓存键 | **物理**槽 | `ContainerRuntimeCache.update`（在方法内部映射，调用方不可能漏）|
  | 作废键 | **物理**槽 | `trackLivingSlots` |
  | 同步包 / 渲染包 | **物理**槽 | `FluidFlowServerSync.buildPacket` 的 cells 键与 `fromSlot` |
  | GUI 反查 | 物理 → `toLogicalSlot` | 倒/汲、信标喂食（`slot.getContainerSlot()`）|

- **为什么映射要下沉到方法内部**：`ContainerRuntimeCache.update` 收的是 `ContainerContext`
  而不是「裸键 + 槽号」⇒ 调用方**拿不到**不映射的写法。参数形状本身就是守卫。
- **现状**：✅ 已满足（2026-10-21）—— 唯一例子是马 / 驴 / 骡（`AbstractHorse` inventory 索引 0
  是马鞍）。守卫：`toPhysicalSlot` / `toLogicalSlot` 互逆（驴 +1/−1、船恒等）、遥测键用物理槽
  （驴的逻辑槽 0 落在 key 1、key 0 无数据）。
- **判据**：`grep -n "getContainerSlot()"` + 所有构造「同步包 / 缓存键 / 作废键」的地方，
  每一处都必须能回答「我这里数的是逻辑槽还是物理槽」。


---

## 3. 两种「嵌套」必须分开（2026-10-18 澄清）

「嵌套」在本项目里指两件**机制完全不同**的事。混为一谈会得出「不做嵌套」或「必须支持任意深度」
两种错误结论。

| | **扫描展开** | **功能内部递归** |
|---|---|---|
| 谁发起 | 每 tick 的扫描器（`LivingItem.onServerTick`） | 某个功能的 `tick` 内部 |
| 方向 | 外 → 内（在宿主容器里发现「本身是容器的物品」） | 内 → 更深（自己的虚拟存储里还有活物品） |
| 例子 | 玩家背包里有一个精妙背包，背包里有活熔炉 | 活潜影箱内部有一个活潜影箱 |
| 现状 | **❌ 不做**（`D-container-08`，2026-10-19 定案） | 活潜影箱待实现 |
| 设计上限 | —（不做） | 活潜影箱设计稿写 4 层（见 [buffer/活潜影箱实现细节.md](../buffer/活潜影箱实现细节.md) §1.4 / §8.1） |
| 主要风险 | 键路径化、每 tick 遍历量、运行时数据作废语义 | 环、账本归属（电力/应力记哪一层）、性能 |

### 3.0 为什么**不做**扫描展开（`D-container-08`）

**容器内物品被 tick，是活潜影箱的专属逻辑，不是框架的通用能力。**

- **性能**：通用扫描要遍历每个容器的每个物品查 `ItemHandler.ITEM`（108 格 × N 容器），
  必须再加一层「物品类型筛」才敢上线；而活潜影箱自带「已被活化」这一门槛，展开范围天然有界。
- **玩法**： 「物品内部自成世界」本就是活潜影箱的核心卖点（[设计稿 §0.1](../buffer/活潜影箱实现细节.md)）。
  泛化到所有背包物品会把这个卖点稀释成"随手就有"。
- **实现**：活潜影箱走功能内部递归（`LivingShulkerBoxFunction.tick` 里自建 innerCtx 再
  `processContext`），**完全复用**现有 tick 管线，不新增一套枚举。

⇒ 框架的职责边界收敛为：**tick「方块形态容器」+「实体形态容器」+「玩家背包」**
（实体形态 = 船 / 矿车 / 带箱驴骡，2026-10-21 加入，见 I-C1 / `D-container-10`）。
物品形态容器内部要不要 tick，由该物品所属的功能自己决定。

### 3.1 深度预算是**一条**，不是两个上限

一次 tick 只分配**一条深度预算**，每展开一层减 1，归零即停。扫描入口分配总额；功能内部递归
消费**同一条**预算。

- **为什么**：若扫描器给 1、封装给 4，两层上限各自正确，合起来是 5 层 ⇒ 总遍历量失控。
- **⚠️ 2026-10-19 修正（`D-container-08`）**：扫描展开已取消 ⇒ 目前**只有功能内部递归**这一条
  消费方，预算是"功能自己声明 + 逐层递减"。上面「1+4=5」的叠加风险随之消失，
  但**保留统一预算机制**——将来若真有第二个递归消费方，仍须共用同一条。

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
| I-C1（实体宿主）| 守卫测试：`EntityContainersTest` —— 白名单（`ContainerEntity` / 带箱驴骡 **进**；无箱马、普通实体 **出**）+ 无能力面即抛 |
| I-C3 / I-C4 / I-C7 | 守卫测试：`SlotIgnoringHandler`（`insertItem` 忽略 slot）+ `FilteredExtractHandler`（`extractItem` 被过滤器拦）—— 让「写回错槽」「吞物品」在单测里直接红 |
| I-C5 | 守卫测试：非 `Container` 方块实体的 viewer 匹配（`ContainerContextsTest` 已有同类替身场景）；实体宿主的 `getBlockPos()` 恒 null 与「键=UUID」在 `EntityContainersTest` |
| I-C6 | 代码审查：`continue` 前必须有日志或调试计数 |
| I-C10 | 守卫测试：`toPhysicalSlot` / `toLogicalSlot` 互逆 + 遥测键用物理槽（`EntityContainersTest`）；新增前置槽时人工过一遍跨边界点清单 |

> ⚠️ **替身测试只能验证「我对第三方行为的理解」**。它的价值是**回归**，不是**证伪**。
> 证伪只能靠真机（`runClient` + 真实模组 jar），且只需做一次——把观察结果记进专题文档当实证。
