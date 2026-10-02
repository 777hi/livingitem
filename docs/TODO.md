# 待办 / 已知问题

> **性质**：随手记的待办池，**不是**规格文档。已完成项**直接删掉**（别留「已完成」清单 ——
> 那是 changelog 的职责）；本文件只放**还没做的事**。
> 上次复核：2026-09-22。

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

5. **自持功能的判据循环要预计算**（Q1 设计的一部分）——
   `shouldTickWithoutOwnItems` 每 tick 要遍历全部 **22 个**功能；
   10000 容器时约 **1.1–2.2 ms/tick**（占 tick 预算 2–4%）。
   修法：加不依赖 ctx 的静态声明（如 `requiresOwnItems()`）+ 注册时过滤 ⇒ 22 次降到 1 次。
   ⚠️ **实现 1a-1 时一并做**，别等之后再返工。

6. **（收益最大）合并两次全槽位遍历** —— `scanAndGroupLivingItems` 与
   `computeContentSignature` 各遍历一遍全部槽位 ⇒ 合并可**省 50% 扫描**。
   详见 [infrastructure-refactoring-plan.md](buffer/infrastructure-refactoring-plan.md) §1.7 / §6.3-B。

## 活水车

1. 在航空学载具上，玩家应力无法传输到脚下。（2026-09-22 复核：**仍未解决**）

