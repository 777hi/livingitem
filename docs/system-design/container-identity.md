# 多方块容器身份解析（边界带）

> **本文是「多方块容器边界带身份解析」的契约与归宿**（Q6 收敛，2026-10-04）。
> 把原先散落在 hopper §6.4/§10.25、tooltip-system §3.2/§7、infrastructure §2.5/§8.5、
> 活桶汲/倒里的同类教训**集中到一处**。
>
> **状态**：批次 A（服务端 `resolve` / `isViewing`）、批次 B（`ownsContainer` + `isSameSlotSpace`）、
> 批次 C（客户端槽位解析）**均已落地**。
> **2026-10-19 / 10-21 演进（本文已按现状改写）**：
> ① viewer 匹配从「`Container` 实例集合」整体切到**位置口径** ⇒ `isViewing(Collection<Container>)` /
>    `ownsContainer` **已删除**，位置解析收敛到 `MenuSlotOwners`；
> ② 解析入口从 `ContainerContexts` 迁到 **`MenuSlotOwners`**（两级：结构化 + 注册口）；
> ③ 新增**实体宿主**（船 / 矿车 / 带箱驴骡）—— 身份是 **UUID** 不是位置，见 §2.1。
>
> 收编方案与拍板记录在
> [archive/infrastructure-refactoring-plan.md §2.1-Q6](../archive/infrastructure-refactoring-plan.md)。
> 契约口径以 [container-boundary.md](container-boundary.md) 的 I-C5 为准。

---

## 1. 问题：多方块容器没有统一身份句柄

原版 / NeoForge 的多方块容器（大箱子、模组多方块箱子）在三个视图里各有一个身份，**互不可达**：

| 视图 | 拿到什么 | 谁在用 |
|---|---|---|
| `Container` 实例 | 单个 BE 的容器，或大箱菜单里的 `CompoundContainer` 包装对象 | 菜单槽位 `slot.container` |
| `BlockEntity` + `BlockPos` | 世界里那一格（大箱子 = 单个半箱） | tick 主链路（`processContainerAt`）|
| 合并 `IItemHandler` | 能力面给出的**合并**容器（大箱子 = 54 槽）| 传输 / 流体引擎 |

- 大箱菜单槽位的 `slot.container` 是 `new CompoundContainer(左半BE, 右半BE)`，**不是 BE 本体** ⇒
  `containerInstances.contains(slot.container)` 对大箱**永远不匹配**。
- `CompoundContainer` **不提供**两半的公开访问器 ⇒ 想拿位置只能反射。
- 菜单不暴露位置 ⇒ 「槽位 → 坐标」没有直达链路。

⇒ 每个需要「菜单槽位 / 同步 / 世界 ↔ 容器状态」的消费者都被迫**各解一遍**。

**tick 主链路不乱**（上下文以 positions/BEs/合并 handler 单一事实构建，见
[living-item-infrastructure.md §8](living-item-infrastructure.md)）；乱的是**边界带** ——
每个 GUI 级新功能都重新踩一遍。

---

## 2. 共享内核：`ContainerContexts`

`container/ContainerContexts.java` —— 边界带的**单一入口**。
每个方法都是**已验证的现成实现**的迁移（**搬家不是重写**，含各自坑位注释）。

| 方法 | 契约 | 底稿 |
|---|---|---|
| `resolve(ServerPlayer, Slot) → TickableContainerContext` | 菜单槽位 → tick 上下文（容器键与 `processContainerAt` 同源 ⇒ 同一容器同一键）。**2026-10-19 起先按菜单解析位置、再按位置建上下文**（否则模组容器恒 null）| `LivingBucketInteractSupport.compoundContext` |
| `isViewingPositions(ServerPlayer, Collection<BlockPos>)` | 玩家菜单里是否含这些**位置**（取代已删除的 `isViewing(Collection<Container>)`）| `ContainerRuntimeCache` / `FluidFlowServerSync` 两份同构 |
| `isViewingEnderChest(Player)` | 玩家是否开着末影箱（末影箱容器键 = 玩家键，无位置）| 同上 |
| `isSameSlotSpace(Container, IItemHandler, int)` | Container ↔ handler 是否共用同一套槽位编号（两级判据）| `SimpleContainerContext.isSameSlotSpaceAsHandler` |

`resolve` 的分支（镜像 `ContainerLivingItemHandler.processContainerAt` 的构建规则）：

- **玩家背包**：`slot.container == player.getInventory()`
- **单 BE 容器**：`slot.container instanceof BlockEntity`
- **大箱子**：`CompoundContainer` → 反射掏两半 → `DoubleChestPositions.find` 规范化为 LEFT 在前 →
  与 tick 构建同源
- **模组容器（无 BE 可达）**：**先走 `MenuSlotOwners.resolve` 拿位置**，再按位置建上下文
  （2026-10-19 补；此前只认 `Container` 实例 ⇒ 精妙背包背包槽汲/倒静默无效）
- ⚠️ **分支顺序是 hazard**：位置分支必须在 `instanceof BlockEntity` 之后、`CompoundContainer` 特判
  之前落位，改顺序会让大箱子拿到单半箱。

**菜单槽 → 容器** 的解析本体已迁到 `container/MenuSlotOwners.java`（`D-container-09`，两级）：

| 级 | 判据 | 失效风险 |
|---|---|---|
| ① **结构化** | `slot.container instanceof BlockEntity` / `CompoundContainer` / `Entity`（`ContainerEntity` 等）| 永不失效（纯类型判据）|
| ② **注册口** | `registerMenuResolver`（方块位置）/ `registerEntityResolver`（实体）—— 由**联动模块**注册 | 只在该模块内失效 |

> ⚠️ **核心不含任何「反射第三方方法名」的兜底**：精妙背包的 `getBlockPosition()` 反射住在
> `living/compat/sophisticated/SophisticatedMenuResolver`（`ModList` 门控 + 包名前缀判据）。
> 核心 `MenuSlotOwners` 只有两级。

**两条不变量（违反即 bug）**：

1. **任何「玩家菜单 ↔ 容器」匹配必须兼容 `CompoundContainer.contains(be)`** ——
   否则大箱子永远匹配失败（v19.1 遥测链路、2026-09-09 组件同步链路**两次踩同一个坑**）。
2. **`resolve` 的容器键必须与 `ContainerLivingItemHandler.processContainerAt` 的构建规则一致** ——
   否则拿到的不是 tick 循环里那份活实例，改副本会被下一 tick 重算覆盖。

**客户端对偶**（批次 C，已落地）：`client/util/ClientSlotResolve.resolveContainerSlot(Slot)` ——
收编 `GuiInteractionHelper.resolveContainerSlot` 与 `AbstractContainerScreenMixin.living_item$resolveContainerSlot`
（后者多一层反射兜底，统一保留）。

> ⚠️ **为何不在 `ContainerContexts` 内**：创造模式 `SlotWrapper` 是原版**客户端**内部类，靠客户端 Mixin
> `LivingItemSlotWrapperAccessor` 访问 —— 若 common 包的 `ContainerContexts` 引用它，**专用服务端**加载时会崩。
> 故客户端解析被迫分居 `client/util/`，逻辑同属「槽位 ↔ 容器身份」收敛。

### 2.1 实体宿主：身份是 UUID，菜单解析天然结构化

方块容器的身份 = **位置**；实体容器的身份 = **`entity_<uuid>`**（`D-container-10`）。

| | 方块宿主 | 实体宿主 |
|---|---|---|
| 身份键 | `chest_x_y_z` / `container_<dim>_x_y_z` | **`entity_<uuid>`** |
| 为什么不能用位置 | 位置稳定 | **实体每 tick 在动** ⇒ 位置键会漂 ⇒ 进度被洗掉 |
| 位置怎么来 | 键里带 | 每 tick 由快照**现取**（ctx 每 tick 新建 ⇒ 不存在陈旧）|
| `getBlockPos()` | 真实坐标 | **恒 `null`**（不是方块容器；且防「船停在箱子上 ⇒ 信标把光束漂到下面的箱子」）|
| 菜单解析 | ① 结构化 / ② 注册口 | **① 结构化直接命中**：`ContainerEntity extends Container` ⇒ `slot.container` 就是船本身，**零反射** |

⇒ 实体容器**不需要**「viewer 降级」：曾预设的降级方案随之作废（`MenuSlotOwners` 无缓存 +
每 tick 从 UUID 反查实时位置 ⇒ GUI 开着船在漂，tooltip 与流体渲染照样跟得上）。

⚠️ **驴 / 骡的菜单类是共用的**（`HorseInventoryMenu` 同时服务马、驴、骡）⇒ 解析器必须再过一道
`hasChest()` 白名单，否则**普通马**也会被当容器。这道白名单在 `EntityContainers`（不在菜单类上）。

---

## 3. 消费者清单（8 + 客户端 1）

| # | 消费者 | 边界带需求 | 现状 |
|---|---|---|---|
| 1 | `DoubleChestPositions.java` | 位置级找伙伴（LEFT/RIGHT 规范序）| 保留（`resolve` 复用）|
| 2 | `ContainerRuntimeCache.java` `isViewingContainer` | 查看者匹配（遥测）| ✅ **位置口径** → `ContainerContexts.isViewingPositions` |
| 3 | `FluidFlowServerSync.java` `isViewingContainer` | 查看者匹配（流体渲染）| ✅ **位置口径** → `ContainerContexts.isViewingPositions` |
| 4 | `LivingBucketInteractSupport.java` `compoundContext` | 汲/倒反查活流体数据 | ✅ 改薄委托 → `ContainerContexts.resolve` |
| 5 | `SimpleContainerContext.java` `slotBelongsToPositions` | 容器归属匹配（槽位同步）| ✅ **位置口径**（私有，不再经 `ContainerContexts` —— `ownsContainer` 已随实例口径一并删除）|
| 6 | `CrossContainerTransfer.java` `getBasePosCandidates` | 跨容器面选取（基准块候选）| 不迁移（非重复项，属传输面选取；通用教训见 §4）|
| 7 | `SimpleContainerContext.java` `isSameSlotSpaceAsHandler` | 槽位体系一致性仲裁 | ✅ 改薄委托 → `ContainerContexts.isSameSlotSpace` |
| 8 | `LivingItem.runtimeContainerPositions` | 运行时**容器键 → 位置**反查（tooltip / 流体渲染发包）| ✅ 支持 `entity_<uuid>`（每 tick 从 `ServerLevel#getEntity` 实时反查 ⇒ 实体移动也跟得上）|
| C | `GuiInteractionHelper.java` + `AbstractContainerScreenMixin.java` | 客户端槽位 → 容器（创造模式 `SlotWrapper`）| ✅ 改薄委托 → `ClientSlotResolve.resolveContainerSlot`（`client/util/`）|

> **判据（可复算）**：`grep -rn CompoundContainer src/main/java` 的**特判代码**应收敛到两处 ——
> `ContainerContexts`（建上下文）与 `MenuSlotOwners`（解析位置）；其余命中只能是注释。

> ⚠️ **残留（2026-10-04 代码审查，未收编）**：`AbstractContainerScreenMixin.living_item$resolveServerSlotIndex`
> 仍自己解包 `SlotWrapper`（取 **`.index`**）—— 与 Q6 的「→ 容器槽位」是**不同映射**，故不在本批范围。
> 若要彻底统一「SlotWrapper 解包」，可抽 `ClientSlotResolve.unwrap(Slot)` 原语（代价：该处也会获得反射兜底，
> 行为超集）。

---

## 4. 收编的通用教训（写别的消费者之前先读）

1. **两套槽位体系可能错位**（hopper §10.25）：`Container` 给单个半箱（27 槽），`IItemHandler` 给合并（54 槽），
   槽号错位 27。按逻辑槽位读写前**先验证两套体系一致** —— `isSameSlotSpace` 两级判据
   （① 槽位数一致挡住「单体 vs 合并」；② 单槽交叉校验挡住「槽位数相同但合并顺序相反」），不过则回退 handler。
2. **模拟与真实写入必须同源**：§10.25 的温床正是「模拟走 `Container`、真实走 handler」。
   `handler.insertItem(slot, …, true)` 内部本就转调容器的 `canPlaceItem`/`isItemValid`，语义不丢。
3. **跨容器面选取不做结构假设**（hopper §6.4）：合并顺序由各模组 `IItemHandler` 决定，
   `getBasePosCandidates()` 返回**按优先级排序的候选基准块**，调用方逐个尝试 ——
   猜错只影响优先级，功能仍成立。三方块 / 四块由 `containerSize / 块数` 天然通用。
4. **大箱子槽位天然对齐**：大箱的 `IItemHandler` 与菜单 `CompoundContainer` 都经
   `ChestBlock.combine → DoubleBlockCombiner` 生成，拼接顺序由 `ChestBlock.TYPE`（LEFT/RIGHT）归一化 ⇒
   槽位索引天然一致，同步不会左右对调。

---

## 5. 已知缺口

- **末影箱**：`EnderChestContainerContext` 把 containerKey 覆写为玩家键，
  `ContainerContexts.resolve` 解析不出 ⇒ 返回 `null`。按菜单槽位反查的功能（如汲/倒）
  对末影箱**静默无效**。

- ✅ **精妙的「拾取 + 放置」双向都已修（2026-10-21，`D-container-12`，真机日志驱动）**：
  精妙**两个事件都不给** —— 拾取走 `putInPlayersHandAndRemove`（`removeBlock`，无掉落物）、
  放置走 `BackpackItem.tryPlace`（**不是 `BlockItem`**，自己 `setBlockAndUpdate`）。
  修法 = **双向交接**（`ContainerHandoff`）：
  `capture`/`handoff`（宿主 → 物品，Mixin 挂 `putInPlayersHandAndRemove` 的 HEAD/RETURN）+
  `stageRestore`/`restoreTo`（物品 → 宿主，Mixin 挂 `tryPlace` 的 HEAD/RETURN）。
  搬运逻辑复用事件路径那份 `moveOut`/`moveIn`。
  ⚠️ 残留边界：其它模组若有**同样不给事件**的搬走/放置方式，仍会丢数据 —— 遇到时照此模式补一个联动模块。

- ✅ **实体容器（2026-10-21 已实现，不再是缺口）** —— 三条**已知限制**（都是刻意的）：
  ① 超出 simulation distance 停 tick（与 BE 卸载 / 掉落物 / 玩家下线三条既有路径一致），回来接着烧；
  ② 不走位置广播（活信标在实体容器里本就不开光，`LidBlockEntity` 判据三实现全是 BE ⇒ 需求证伪）；
  ③ 马 / 驴 / 骡有前置马鞍槽 ⇒ 逻辑槽与物理槽差 1，见 [container-boundary.md](container-boundary.md) I-C10。

- **「有物品能力、但不是原版 `Container`」的模组方块实体**（2026-10-10 踩坑，crash + 修复）：
  扫描入口 `processContainerAt` 按 `IItemHandler.BLOCK` **能力面**把任何有物品能力的方块实体当成活物品容器
  （分配 `chest_<x>_<y>_<z>` 键、每 tick 处理）。而运行时同步的 viewer 匹配（`runtimeContainerInstances`
  → `isViewing`/`ownsContainer`）依赖**原版 `Container` 实例**。两套口径不一致 ⇒
  - 这类 BE（如 ProjectE 炼金箱 `AlchBlockEntityChest`）**会被正常 tick**（活物品照常运转）；
  - 但其 `Container` 实例取不到 ⇒ 运行时 tooltip 同步**永远不发包**（viewer 匹配不上）。
  - **曾因单 BE 分支无条件 `(Container) be` 强转在此崩**（ClassCastException，crash-2026-10-10，已修为
    `instanceof` 守卫，非 `Container` ⇒ 返回空集合 = 不发包）。
  - **口径已收敛（2026-10-18）**：不采用「容器能力白/黑名单」把这类容器排除出扫描——排除等于
    放弃兼容，且黑名单是永久适配债。改为把**扫描口径与 viewer 口径统一到「能力面 + 位置键」**，
    见 [container-boundary.md](container-boundary.md) 的 I-C1 / I-C5（已生效，`D-container-01/04/05`）。
    `runtimeContainerInstances` 的静态包级私有单 level 分支保留，作为该边界的测试入口。

---

## 6. 相关文档

- **跨 tick 容器身份（containerKey / 缓存键 getCacheKey 生成规则）**：
  [living-item-infrastructure.md §2.5](living-item-infrastructure.md)（2026-10-12 补：
  缓存键 = 维度前缀 + 稳定键，世界容器加、玩家容器不加；`chest_<x>_<y>_<z>` 是稳定键不是缓存键）
- **大箱子去重 / 容器归属验证**：[living-item-infrastructure.md §3.3 / §8.5](living-item-infrastructure.md)
- **Tooltip 大箱匹配（CompoundContainer）**：[tooltip-system.md §3.2 / §7](tooltip-system.md)
- **活漏斗槽位体系探针 / 跨容器面选取**：[living-hopper-tech.md §10.25 / §6.4](../tech/living-hopper-tech.md)
- **收编方案与实施记录（批次 A/B/C）**：[archive/infrastructure-refactoring-plan.md §2.1-Q6](../archive/infrastructure-refactoring-plan.md)
