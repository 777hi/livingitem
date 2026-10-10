# 多方块容器身份解析（边界带）

> **本文是「多方块容器边界带身份解析」的契约与归宿**（Q6 收敛，2026-10-04）。
> 把原先散落在 hopper §6.4/§10.25、tooltip-system §3.2/§7、infrastructure §2.5/§8.5、
> 活桶汲/倒里的同类教训**集中到一处**。
>
> **状态**：批次 A（服务端 `resolve` / `isViewing`）、批次 B（`ownsContainer` + `isSameSlotSpace`）、
> 批次 C（客户端槽位解析）**均已落地**。
> 收编方案与拍板记录在
> [archive/infrastructure-refactoring-plan.md §2.1-Q6](../archive/infrastructure-refactoring-plan.md)。

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
| `resolve(ServerPlayer, Slot) → TickableContainerContext` | 菜单槽位 → tick 上下文（容器键与 `processContainerAt` 同源 ⇒ 同一容器同一键）| `LivingBucketInteractSupport.compoundContext` |
| `isViewing(ServerPlayer, Collection<Container>)` | 玩家菜单里是否含该容器（大箱 `CompoundContainer.contains` 特判）| `ContainerRuntimeCache` / `FluidFlowServerSync` 两份同构 |
| `ownsContainer(Container, Collection<Container>)` | 单个菜单槽位的容器是否属于给定实例集合（大箱特判）| `SimpleContainerContext.slotBelongsTo` |
| `isSameSlotSpace(Container, IItemHandler, int)` | Container ↔ handler 是否共用同一套槽位编号（两级判据）| `SimpleContainerContext.isSameSlotSpaceAsHandler` |

`resolve` 的分支（镜像 `ContainerLivingItemHandler.processContainerAt` 的构建规则）：

- **玩家背包**：`slot.container == player.getInventory()`
- **单 BE 容器**：`slot.container instanceof BlockEntity`
- **大箱子**：`CompoundContainer` → 反射掏两半 → `DoubleChestPositions.find` 规范化为 LEFT 在前 →
  与 tick 构建同源

**两条不变量（违反即 bug）**：

1. **任何「玩家菜单 ↔ 容器实例」匹配必须兼容 `CompoundContainer.contains(be)`** ——
   否则大箱子永远匹配失败（v19.1 遥测链路、2026-09-09 组件同步链路**两次踩同一个坑**）。
2. **`resolve` 的容器键必须与 `ContainerLivingItemHandler.processContainerAt` 的构建规则一致** ——
   否则拿到的不是 tick 循环里那份活实例，改副本会被下一 tick 重算覆盖。

**客户端对偶**（批次 C，已落地）：`client/util/ClientSlotResolve.resolveContainerSlot(Slot)` ——
收编 `GuiInteractionHelper.resolveContainerSlot` 与 `AbstractContainerScreenMixin.living_item$resolveContainerSlot`
（后者多一层反射兜底，统一保留）。

> ⚠️ **为何不在 `ContainerContexts` 内**：创造模式 `SlotWrapper` 是原版**客户端**内部类，靠客户端 Mixin
> `SlotWrapperAccessor` 访问 —— 若 common 包的 `ContainerContexts` 引用它，**专用服务端**加载时会崩。
> 故客户端解析被迫分居 `client/util/`，逻辑同属「槽位 ↔ 容器身份」收敛。

---

## 3. 消费者清单（6 + 客户端 1）

| # | 消费者 | 边界带需求 | 现状 |
|---|---|---|---|
| 1 | `DoubleChestPositions.java` | 位置级找伙伴（LEFT/RIGHT 规范序）| 保留（`resolve` 复用）|
| 2 | `ContainerRuntimeCache.java` `isViewingContainer` | 查看者匹配（遥测）| ✅ 改薄委托 → `ContainerContexts.isViewing` |
| 3 | `FluidFlowServerSync.java` `isViewingContainer` | 查看者匹配（流体渲染）| ✅ 改薄委托 → `ContainerContexts.isViewing` |
| 4 | `LivingBucketInteractSupport.java` `compoundContext` | 汲/倒反查活流体数据 | ✅ 改薄委托 → `ContainerContexts.resolve` |
| 5 | `SimpleContainerContext.java` `slotBelongsTo` | 容器归属匹配（组件同步）| ✅ 改薄委托 → `ContainerContexts.ownsContainer` |
| 6 | `CrossContainerTransfer.java` `getBasePosCandidates` | 跨容器面选取（基准块候选）| 不迁移（非重复项，属传输面选取；通用教训见 §4）|
| 7 | `SimpleContainerContext.java` `isSameSlotSpaceAsHandler` | 槽位体系一致性仲裁 | ✅ 改薄委托 → `ContainerContexts.isSameSlotSpace` |
| C | `GuiInteractionHelper.java` + `AbstractContainerScreenMixin.java` | 客户端槽位 → 容器（创造模式 `SlotWrapper`）| ✅ 改薄委托 → `ClientSlotResolve.resolveContainerSlot`（`client/util/`）|

> **判据（可复算）**：`grep -rn CompoundContainer src/main/java` 的**特判**应收敛到 `ContainerContexts` 一处。

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

- **「有物品能力、但不是原版 `Container`」的模组方块实体**（2026-10-10 踩坑，crash + 修复）：
  扫描入口 `processContainerAt` 按 `IItemHandler.BLOCK` **能力面**把任何有物品能力的方块实体当成活物品容器
  （分配 `chest_<x>_<y>_<z>` 键、每 tick 处理）。而运行时同步的 viewer 匹配（`runtimeContainerInstances`
  → `isViewing`/`ownsContainer`）依赖**原版 `Container` 实例**。两套口径不一致 ⇒
  - 这类 BE（如 ProjectE 炼金箱 `AlchBlockEntityChest`）**会被正常 tick**（活物品照常运转）；
  - 但其 `Container` 实例取不到 ⇒ 运行时 tooltip 同步**永远不发包**（viewer 匹配不上）。
  - **曾因单 BE 分支无条件 `(Container) be` 强转在此崩**（ClassCastException，crash-2026-10-10，已修为
    `instanceof` 守卫，非 `Container` ⇒ 返回空集合 = 不发包）。
  - **待决**：是否在扫描入口加「容器能力白/黑名单」排除此类容器，尚未拍板（见 TODO）。
    `runtimeContainerInstances` 现已拆为静态包级私有单 level 分支，便于单测该边界。

---

## 6. 相关文档

- **跨 tick 容器身份（containerKey 生成规则）**：[living-item-infrastructure.md §2.5](living-item-infrastructure.md)
- **大箱子去重 / 容器归属验证**：[living-item-infrastructure.md §3.3 / §8.5](living-item-infrastructure.md)
- **Tooltip 大箱匹配（CompoundContainer）**：[tooltip-system.md §3.2 / §7](tooltip-system.md)
- **活漏斗槽位体系探针 / 跨容器面选取**：[living-hopper-tech.md §10.25 / §6.4](../tech/living-hopper-tech.md)
- **收编方案与实施记录（批次 A/B/C）**：[archive/infrastructure-refactoring-plan.md §2.1-Q6](../archive/infrastructure-refactoring-plan.md)
