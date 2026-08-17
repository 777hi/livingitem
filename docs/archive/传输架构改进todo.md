The user is asking if I have any additional suggestions or insights about the hopper transfer system. Let me think about what I've seen in the codebase and what could be improved beyond the unification discussion we just had.

Let me think about potential issues and improvements:

1. **The SlotAccessor abstraction is already good** - it unifies different storage backends. The factory pattern is clean.

2. **The FilteredSlotAccessor decorator pattern** is elegant - it wraps the access check transparently.

3. **Areas for improvement**:

   a. **CrossContainerTransfer.pushToNeighbor** has a duplicate inline loop instead of using `tryPushToNeighbor` - let me check if that's true.

   b. **The `transferBetweenNeighbors` method** has a double loop that could be optimized or at least use the helper methods.

   c. **The `executeInContainer` has two separate Container checks** (canTakeItem and canPlaceItem) that could be unified.

   d. **The `EnderRouteManager` resolution** is different between the two paths - cross-container only handles it for pull to living ender chest, while in-container handles it for all cases.

   e. **Performance**: The neighbor handler is obtained via `level.getCapability()` which is a relatively expensive operation. In `transferBetweenNeighbors`, both source and target handlers are obtained.

   f. **The `tryPushToNeighbor` accepts `filterItem` parameter but the push method in `pushToNeighbor` doesn't use it - it has its own inline loop instead.

   g. **Error handling**: Both paths silently return false on various failure conditions, which is fine for a tick-based system but makes debugging hard.

   h. **The double chest logic** is complex and could benefit from being extracted to a separate utility class.

   i. **The `ResolvedSlots` record** is used in CrossContainerTransfer but not in the TransferPipeline - there's an inconsistency.

   j. **The simulation approach**: `executeInContainer` does `simulateExtract` before `canPlaceItem`, then SlotAccessor.transfer also does simulation internally. This means double simulation in some cases.

   k. **The `getNeighborContainer` method** is package-visible in CrossContainerTransfer and used by TransferPipeline, which creates a dependency from TransferPipeline → CrossContainerTransfer. This is a bit backwards.

   l. **The `pushFromLivingStorageToNeighbor` and `pushToNeighbor` overlap** - both push to neighbor but one handles living storage and the other handles normal items. They could share more code.

   m. **The `tryPullFromNeighbor` and `tryPushToNeighbor` are helper methods** but the `transferBetweenNeighbors` method doesn't use them - it has its own inline double loop, which is a missed opportunity for reuse.

4. **Architectural suggestions**:
   - A `TransferEndpoint` abstraction as discussed
   - Move `getNeighborContainer` to a utility class
   - Consider using a pipeline/builder pattern for the transfer flow
   - Consider adding debug logging for transfer failures

Let me also think about what's good about the current design:
- The SlotAccessor + factory pattern is very clean
- The FilteredSlotAccessor decorator is elegant
- The tryPull/tryPush helper methods are good abstractions
- The Container.canTakeItem/canPlaceItem integration is properly done
- The EnderRouteManager decision enum is clean

Let me give a thoughtful, practical response with suggestions ranked by impact.
基于我对这个代码库的深入阅读，有几个建议：

---

## 建议 1：`transferBetweenNeighbors` 没用上已有的 helper ⚠️

这是最明显的代码冗余。`tryPullFromNeighbor` 和 `tryPushToNeighbor` 已经封装好了源单槽位 → 遍历目标，但 `transferBetweenNeighbors` 自己写了一套双重循环：

```java
// 当前 — 自己写双重循环
for (int i = 0; i < sourceHandler.getSlots(); i++) {
    // canTakeItem 检查...
    SlotAccessor source = createForNeighbor(...);
    for (int j = 0; j < targetHandler.getSlots(); j++) {
        // canPlaceItem 检查...
        SlotAccessor target = createForNeighbor(...);
        if (SlotAccessor.transfer(source, target, amount)) return true;
    }
}
```

但语义上就是：**对源的每个槽位，`tryPushToNeighbor` 一次**。可以简化为：

```java
for (int i = 0; i < sourceHandler.getSlots(); i++) {
    ItemStack stack = sourceHandler.getStackInSlot(i);
    if (stack.isEmpty() || LivingItemManager.isLivingItem(stack)) continue;
    if (sourceContainer != null && !sourceContainer.canTakeItem(sourceContainer, i, stack)) continue;
    SlotAccessor source = createForNeighbor(sourceHandler, i, filterData, level, sourceNeighborPos);
    if (tryPushToNeighbor(targetHandler, targetNeighborPos, level, source, amount, stack)) return true;
}
```

直接复用 `tryPushToNeighbor`，少一层循环，减少约 10 行。

---

## 建议 2：`pushToNeighbor` 也没用 `tryPushToNeighbor` ⚠️

`pushToNeighbor` 中普通物品推送部分自己写了遍历循环，但 `tryPushToNeighbor` 就是干这个的：

```java
// 当前 — pushToNeighbor 普通物品分支
for (int i = 0; i < neighborHandler.getSlots(); i++) {
    ItemStack neighborStack = neighborHandler.getStackInSlot(i);
    if (!neighborStack.isEmpty() && !neighborStack.is(sourceStack.getItem())
        && neighborStack.getCount() >= neighborHandler.getSlotLimit(i)) continue;
    if (neighborContainer != null && !neighborContainer.canPlaceItem(i, sourceStack)) continue;
    SlotAccessor target = SlotAccessorFactory.createForNeighbor(...);
    if (SlotAccessor.transfer(source, target, amount)) return true;
}
```

可以直接替换为：

```java
return tryPushToNeighbor(neighborHandler, neighborPos, level, source, amount, sourceStack);
```

但注意：`tryPushToNeighbor` 没有 `neighborStack` 的满堆检查（`getCount() >= getSlotLimit`），这个逻辑要去掉还是补到 `tryPushToNeighbor` 里——建议补进去，因为满堆跳过是通用的优化。

---

## 建议 3：`getNeighborContainer` 放错位置了

当前它在 `CrossContainerTransfer` 里，`TransferPipeline` 调用它。这意味着 `TransferPipeline` 依赖 `CrossContainerTransfer`——但语义上应该是反过来的：管道层是上层，跨容器是下层实现。

建议提取到 `ContainerContext` 或一个独立的工具方法：

```java
// ContainerContext 或 ContainerUtils
default Container getHostContainer(Level level) {
    BlockPos pos = getBlockPos();
    if (pos == null) return null;
    if (level.getBlockEntity(pos) instanceof Container c) return c;
    return null;
}
```

这样 `TransferPipeline` 不需要依赖 `CrossContainerTransfer`。

---

## 建议 4：活箱子/末影箱的跨容器推送可以更统一

当前 `pushToNeighbor` 中活箱子/末影箱走 `pushFromLivingStorageToNeighbor`，普通物品走内联循环。但 `pushFromLivingStorageToNeighbor` 内部也是 `simulateExtract` + `tryPushToNeighbor`。

其实普通物品也可以走同样的流程——先 `simulateExtract` 获取物品类型，再 `tryPushToNeighbor`。这样 `pushToNeighbor` 就统一了：

```java
// 统一后
SlotAccessor source = create(server, containerCtx, sourceSlot, filterData, ...);
if (source == null || source.isEmpty()) return false;
ItemStack simulated = source.simulateExtract(amount);
if (simulated.isEmpty()) return false;
return tryPushToNeighbor(neighborHandler, neighborPos, level, source, amount, simulated);
```

活箱子/末影箱和普通物品的推送路径完全一致，不需要分支。

---

## 建议 5：`executeInContainer` 中存在双重模拟

```java
// 第一次模拟 — 用于 canPlaceItem 检查
ItemStack simulated = source.simulateExtract(transferAmount);
if (!simulated.isEmpty() && !hostContainer.canPlaceItem(targetSlot, simulated)) {
    return false;
}

// 第二次模拟 — SlotAccessor.transfer 内部也会 simulateExtract + simulateInsert
return SlotAccessor.transfer(source, target, transferAmount);
```

`SlotAccessor.transfer()` 内部已经做了模拟优先的验证，外层的 `canPlaceItem` 检查可以合并进 `SlotAccessor` 的 `simulateInsert` 阶段。不过这个改动影响面较大，属于远期优化。

---

## 建议 6：端点抽象的可行性评估

你提出的"容器内也是跨容器"的思路，核心收益是**核心传输层统一**。但实际落地时，我之前说的"端点解析"和"槽位遍历"两层仍然是不同的，统一后代码量不一定减少。它的最大价值是：

- **保证逻辑一致性**：新增检查项时不会漏掉某条路径
- **降低维护成本**：bug 修复只需改一处

但需要权衡引入 `TransferEndpoint` 接口带来的抽象成本。建议先做前 5 个低成本优化，端点抽象作为远期目标。

---

## 优先级建议

| 优先级 | 建议 | 收益 | 风险 |
|--------|------|------|------|
| 🔴 高 | 建议 1：`transferBetweenNeighbors` 复用 `tryPushToNeighbor` | 减少重复代码 | 低 |
| 🔴 高 | 建议 2：`pushToNeighbor` 复用 `tryPushToNeighbor` | 减少重复代码 | 低（需补满堆检查） |
| 🟡 中 | 建议 3：`getNeighborContainer` 移出 `CrossContainerTransfer` | 解耦依赖 | 低 |
| 🟡 中 | 建议 4：统一活箱子/普通物品的推送路径 | 减少分支 | 中 |
| 🟢 低 | 建议 5：消除双重模拟 | 性能微优化 | 高（影响面大） |
| 🔵 远期 | 建议 6：`TransferEndpoint` 抽象 | 架构统一 | 高（抽象成本） |

---

要我先把建议 1 和 2 做了吗？这两个改动最小但冗余最明显。
















The user wants to discuss suggestions 5 and 6 from the 7777.md document:

- **建议 5**: 消除双重模拟 (performance micro-optimization, high risk)
- **建议 6**: `TransferEndpoint` 抽象 (architecture unification, high abstraction cost)

Let me think deeply about both of these and provide a thorough analysis.

For suggestion 5, I need to re-read the relevant code in TransferPipeline and SlotAccessor to understand the double simulation issue.

For suggestion 6, I need to think about the TransferEndpoint abstraction more carefully.

Let me read the SlotAccessor.transfer() method to understand the simulation flow.

Now let me read the `SlotAccessor.transfer()` static method to see the simulation flow:

Now I have a complete picture. Let me analyze both suggestions 5 and 6 in depth.

## Suggestion 5: 消除双重模拟

In `TransferPipeline.executeInContainer()`, the flow is:

```
1. source.simulateExtract(transferAmount)  -- 第一次模拟提取（用于 canPlaceItem 检查）
2. canPlaceItem check
3. EnderRouteManager.resolveTarget() 
4. SlotAccessor.transfer(source, target, transferAmount)
   ├─ source.simulateExtract(amount)       -- 第二次模拟提取
   ├─ target.simulateInsert(simulated)
   ├─ source.extract(toExtract, null)      -- 真实提取
   └─ target.insert(extracted)             -- 真实插入
```

The first `simulateExtract` (step 1) is purely for the `canPlaceItem` check. The second `simulateExtract` (inside `SlotAccessor.transfer`) is for the actual transfer validation.

For plain items, `simulateExtract` just returns a copy of the stack - it's cheap. For living chests, it might need to look inside the chest. So the cost varies.

But the real question is: can we eliminate the first one? 

The `canPlaceItem` check is important because it's the Container interface check that simulates player behavior. Without it, we might try to insert into a slot that the container doesn't allow.

The issue is that `SlotAccessor.transfer()` doesn't know about `Container.canPlaceItem()`. It only knows about `simulateInsert()`, which is about storage capacity, not about player interaction rules.

So to eliminate the first simulation, we'd need to either:
1. Move `canPlaceItem` check into `SlotAccessor.transfer()` somehow
2. Or move `canPlaceItem` check into the target's `simulateInsert()`

Option 2 is actually possible! We could add a `Container` reference to the `SlotAccessor` and have `simulateInsert` check `canPlaceItem` internally. But this couples the SlotAccessor abstraction to the Container interface, which currently it doesn't depend on.

Option 1: We could add a `canPlaceItem` capability to SlotAccessor. But that's adding API surface.

Actually, the real question is: is this even a problem worth solving? The double simulation is:
- For plain items: a trivial copy operation
- For living chests: potentially more expensive, but still just a lookup

The performance impact is negligible. The code clarity impact is also minimal - the two simulations serve different purposes (Container check vs transfer validation).

So suggestion 5 is really a "nice to have" that comes with a high risk of introducing bugs (since it touches SlotAccessor, which is the core abstraction). The cost/benefit doesn't justify it.

Now let me think about whether there's a way to do it cleanly.

Actually, wait. Let me think about this differently. The `canPlaceItem` check in `executeInContainer` is:

```java
if (hostContainer != null) {
    ItemStack simulated = source.simulateExtract(transferAmount);
    if (!simulated.isEmpty() && !hostContainer.canPlaceItem(targetSlot, simulated)) {
        return false;
    }
}
```

This is a pre-check: "before we even try to transfer, does the target slot allow this item type?" If not, skip the whole transfer attempt.

The `SlotAccessor.transfer()` does its own simulation as part of the transfer flow. It simulates extract, simulates insert, then does real extract + insert.

These two simulations serve different purposes:
1. Pre-check: "Is this slot even allowed to accept this item?" (Container rule)
2. Transfer validation: "How many items can we actually move?" (Capacity + rules)

The pre-check is necessary because `SlotAccessor.transfer()` doesn't know about `Container.canPlaceItem`. Even if we moved it into `simulateInsert`, we'd still need the Container reference.

One approach: Add a `Container` reference to `SlotAccessor`. But `NeighborSlotAccessor` already has access to the `Container` (it gets the `BlockPos`). `PlainSlotAccessor` doesn't have it directly but could get it from the context.

Actually, looking at NeighborSlotAccessor, it receives `level` and `pos` in its constructor. So it could check `canPlaceItem` internally. But PlainSlotAccessor works with ContainerContext, not directly with Container.

Hmm, this is getting complex. Let me just honestly assess the cost/benefit:

**Cost**: 
- Need to modify SlotAccessor interface or PlainSlotAccessor to expose Container
- Need to modify simulateInsert to check canPlaceItem
- Risk of breaking existing behavior
- Affects all SlotAccessor implementations

**Benefit**: 
- Eliminates one simulateExtract call per transfer attempt
- For normal items, this is essentially free (just a copy)
- For living chests, it saves one internal lookup

**Verdict**: Not worth it. The risk is high, the benefit is negligible. This is a classic case of premature optimization.

---

## Suggestion 6: TransferEndpoint 抽象

This is about unifying the container-internal and cross-container transfer paths by introducing a `TransferEndpoint` abstraction.

Let me think about what this would look like concretely.

Currently we have two paths:

**Container-internal** (TransferPipeline.executeInContainer):
- Source and target are both within the same ContainerContext
- Uses SlotAccessorFactory.create() for PlainSlotAccessor
- Has self-loop and cascade protection
- Has canTakeItem/canPlaceItem checks via hostContainer
- Has EnderRouteManager decision flow

**Cross-container** (CrossContainerTransfer):
- Source/target may be in neighbor containers
- Uses SlotAccessorFactory.createForNeighbor() for NeighborSlotAccessor
- No self-loop/cascade (different containers)
- Has canTakeItem/canPlaceItem via getNeighborContainer
- Has direction mapping, double chest logic
- Has separate living chest/ender chest handling

The key difference is:
1. **Endpoint resolution** (how to find the storage)
2. **Slot traversal** (exact slot vs iterate all slots)
3. **Core transfer logic** (should be the same)

If we extract `TransferEndpoint`:

```java
interface TransferEndpoint {
    IItemHandler itemHandler();      // for reading/writing items
    Container container();           // for canTakeItem/canPlaceItem
    BlockPos blockPos();             // for neighbor lookup
    Level level();                   // for server access
    boolean isSelf();                // for cascade/self-loop protection
}
```

Then the core transfer logic:

```java
static boolean tryTransfer(TransferEndpoint src, int srcSlot,
                            TransferEndpoint dst, int dstSlot,
                            int amount, FilterData filter, TickContext tick) {
    // Self-loop protection (only when same endpoint)
    if (src.isSelf() && dst.isSelf() && srcSlot == dstSlot) return false;
    
    // Cascade protection
    if (tick.transferredTargetSlots != null && tick.transferredTargetSlots.contains(srcSlot)) return false;
    
    // Empty check
    ItemStack stack = src.itemHandler().getStackInSlot(srcSlot);
    if (stack.isEmpty()) return false;
    
    // Living item isolation
    if (!isStorageContainer(stack) && LivingItemManager.isLivingItem(stack)) return false;
    
    // Filter check
    if (!isStorageContainer(stack) && !ItemFilterComponent.allows(filter, stack)) return false;
    
    // canTakeItem check
    Container srcContainer = src.container();
    if (srcContainer != null && !srcContainer.canTakeItem(srcContainer, srcSlot, stack)) return false;
    
    // Create accessors
    SlotAccessor source = createAccessor(src, srcSlot, filter, tick);
    SlotAccessor target = createAccessor(dst, dstSlot, null, tick);
    if (source == null || target == null) return false;
    
    int transferAmount = Math.min(stack.getCount(), amount);
    
    // canPlaceItem check
    Container dstContainer = dst.container();
    if (dstContainer != null) {
        ItemStack simulated = source.simulateExtract(transferAmount);
        if (!simulated.isEmpty() && !dstContainer.canPlaceItem(dstSlot, simulated)) return false;
    }
    
    // Ender chest routing
    // ... this is complex and depends on whether it's container-internal or cross-container
    
    return SlotAccessor.transfer(source, target, transferAmount);
}
```

Then:
- Container-internal: `tryTransfer(selfEndpoint, sourceSlot, selfEndpoint, targetSlot, ...)`
- Cross-container pull: `for (slot : neighborSlots) tryTransfer(neighborEndpoint, slot, selfEndpoint, targetSlot, ...)`
- Cross-container push: `for (slot : neighborSlots) tryTransfer(selfEndpoint, sourceSlot, neighborEndpoint, slot, ...)`

This is elegant! But the complexity is:

1. **EnderRouteManager** is different between the two paths. Container-internal uses `EnderRouteManager.resolveTarget()` with the Decision enum. Cross-container uses `EnderRouteManager.resolveCrossContainerTarget()` for pull to living ender chest only. This asymmetry would need to be resolved.

2. **SlotAccessor creation** is different: `create()` vs `createForNeighbor()`. The factory methods take different parameters.

3. **The `isSelf()` check** only matters for container-internal transfers. For cross-container, it's always false.

4. **The `container()` method** returns different things: for self, it's ContainerContext.getContainer(); for neighbor, it's getNeighborContainer().

5. **The `itemHandler()` method**: for self, it's ContainerContext (which extends LivingContainer - but doesn't directly implement IItemHandler). For neighbor, it's the IItemHandler capability.

Wait, actually ContainerContext doesn't implement IItemHandler. It uses getItem/setItem directly. And NeighborSlotAccessor uses IItemHandler.getStackInSlot/insertItem/extractItem.

The reason for this difference is that ContainerContext provides richer slot semantics (like canTakeItem/canPlaceItem) while IItemHandler is the standard Forge abstraction.

So the `TransferEndpoint` abstraction would need to bridge between these two worlds. This is the main architectural challenge.

**My honest assessment**:

The TransferEndpoint abstraction is architecturally beautiful but has significant practical challenges:

1. **Bridging ContainerContext and IItemHandler**: These are fundamentally different interfaces. ContainerContext has getItem/setItem + canTakeItem/canPlaceItem. IItemHandler has getStackInSlot/insertItem/extractItem. The SlotAccessor layer already bridges this gap.

2. **Ender chest routing asymmetry**: The two paths handle ender chests differently. Unifying would require changes to EnderRouteManager.

3. **Code size**: The unified core would be about 50-60 lines. The adapter code (SelfEndpoint, NeighborEndpoint) would be about 30-40 lines each. The total code might not be much less than what we have now.

4. **Real benefit**: The main benefit is that when adding a new check (like a new filter), you only need to add it in one place. But how often do we add new checks? If it's rare, the abstraction cost outweighs the benefit.

**Verdict**: Keep this as a long-term architectural goal. When the codebase grows to the point where adding new checks becomes a recurring pain point, the TransferEndpoint abstraction will be the right solution. For now, the current architecture (TransferPipeline + CrossContainerTransfer with shared helpers) is clean enough.

Let me now write a thorough analysis for the user.
## 建议 5：消除双重模拟 — 深度分析

### 当前双重模拟的位置

```
TransferPipeline.executeInContainer()
  │
  ├─ [1] source.simulateExtract(transferAmount)   ← 第一次模拟
  │     └─ 目的：获取物品类型，用于 canPlaceItem 检查
  │     └─ 位置：TransferPipeline L85-L88
  │
  ├─ [2] EnderRouteManager.resolveTarget()
  │
  └─ [3] SlotAccessor.transfer(source, target, amount)
        ├─ source.simulateExtract(amount)          ← 第二次模拟
        ├─ target.simulateInsert(simulated)        ← 第二次模拟
        ├─ source.extract(...)                     ← 真实
        └─ target.insert(...)                      ← 真实
```

### 两次模拟各自的目的不同

| | 第一次模拟 | 第二次模拟 |
|---|---|---|
| **目的** | Container 接口检查：这个槽位允许放入这种物品吗？ | 传输验证：实际能提取/插入多少？ |
| **检查对象** | `hostContainer.canPlaceItem(targetSlot, simulated)` | 源容量 + 目标容量 |
| **语义** | "玩家能往这个槽位放东西吗" | "存储系统能接受多少" |
| **用到的信息** | 物品类型 + 目标槽位索引 | 物品数量 + 堆叠上限 |

### 为什么不能简单合并

模拟插入 `simulateInsert` 返回的是"能接受多少数量"，不关心"能不能接受这个物品类型"。`canPlaceItem` 是"能/不能"的布尔判断，和数量无关。

如果把 `canPlaceItem` 合并进 `simulateInsert`，需要给 `SlotAccessor` 加一个 `Container` 引用：

```java
// 需要改动 SlotAccessor 接口
interface SlotAccessor {
    // 新增：获取关联的 Container（可能为 null）
    Container container();  
    int slotIndex();
}
```

然后改 `SlotAccessor.transfer()` 的模拟插入阶段：

```java
// 在 simulateInsert 之前检查 canPlaceItem
if (target.container() != null 
    && !target.container().canPlaceItem(target.slotIndex(), simulated)) {
    return false;
}
int canAccept = target.simulateInsert(simulated);
```

### 问题

1. **`SlotAccessor` 接口污染**：当前它只关心物品读写，引入 `Container` 和 `slotIndex()` 把原版容器概念泄漏到了抽象层
2. **实现差异**：`PlainSlotAccessor` 的槽位索引是 `ContainerContext` 的逻辑槽位，`NeighborSlotAccessor` 的槽位索引是 `IItemHandler` 的槽位索引——两者是不同的坐标系统，`canPlaceItem` 需要的是 `Container` 的槽位索引
3. **跨容器不能用**：邻居容器没有 `Container` 接口（只有 `IItemHandler`），`canPlaceItem` 对邻居容器本身就是靠 `ContainerContext.getContainer(level, neighborPos)` 曲线救国——如果邻居容器没实现 `Container` 接口，这个检查本身就跳过了

### 结论

**不建议做。** 这不是代码问题，而是两层抽象的本质差异：
- `Container.canPlaceItem` 是原版 Mojang 的玩家交互层
- `SlotAccessor.simulateInsert` 是 Forge 的存储容量层

强行合并会导致抽象泄漏，且改动影响 `SlotAccessor` 接口的所有实现（Plain、Neighbor、Filtered、LivingChest、LivingEnderChest），风险远大于收益。

---

## 建议 6：TransferEndpoint 抽象 — 深度分析

### 目标

把"容器内传输"和"跨容器传输"统一为同一种抽象：

```
传输 = 源端点(TransferEndpoint) × 源槽位 → 目标端点(TransferEndpoint) × 目标槽位
```

### 方案设计

```java
interface TransferEndpoint {
    ItemStack getStackInSlot(int slot);          // 读物品
    void setStackInSlot(int slot, ItemStack s);  // 写物品
    Container container();                       // canTakeItem/canPlaceItem（可为 null）
    BlockPos pos();                              // 位置（日志/路由）
    boolean isSelf();                            // 是否当前容器（触发自环/级联检查）
    int slotCount();                             // 总槽位数
    SlotAccessor createAccessor(int slot, FilterData filter, TickContext tick);
}
```

两种实现：

```
SelfEndpoint                          NeighborEndpoint
  ├─ ContainerContext ctx               ├─ IItemHandler handler
  ├─ container() → ctx.getHost()        ├─ container() → getContainer(level, pos)
  ├─ pos() → ctx.getBlockPos()          ├─ pos() → neighborPos
  ├─ isSelf() → true                    ├─ isSelf() → false
  └─ createAccessor() → Factory.create  └─ createAccessor() → Factory.createForNeighbor
```

统一后的核心传输方法：

```java
static boolean tryTransfer(TransferEndpoint src, int srcSlot,
                            TransferEndpoint dst, int dstSlot,
                            int amount, FilterData filter, TickContext tick) {
    // 自环（仅同端点）
    if (src.isSelf() && dst.isSelf() && srcSlot == dstSlot) return false;
    // 级联
    if (tick.transferredTargetSlots != null && tick.transferredTargetSlots.contains(srcSlot)) return false;
    // 空检查
    ItemStack stack = src.getStackInSlot(srcSlot);
    if (stack.isEmpty()) return false;
    // 活物品隔离
    if (!isStorageContainer(stack) && LivingItemManager.isLivingItem(stack)) return false;
    // 过滤
    if (!isStorageContainer(stack) && !ItemFilterComponent.allows(filter, stack)) return false;
    // canTakeItem
    if (src.container() != null && !src.container().canTakeItem(src.container(), srcSlot, stack)) return false;
    
    SlotAccessor sa = src.createAccessor(srcSlot, filter, tick);
    SlotAccessor ta = dst.createAccessor(dstSlot, null, tick);
    if (sa == null || ta == null) return false;
    
    int amt = Math.min(stack.getCount(), amount);
    // canPlaceItem
    if (dst.container() != null) {
        ItemStack sim = sa.simulateExtract(amt);
        if (!sim.isEmpty() && !dst.container().canPlaceItem(dst.container(), dstSlot, sim)) return false;
    }
    
    return SlotAccessor.transfer(sa, ta, amt);
}
```

三个调用场景：

```java
// 容器内：自端点 × 2
tryTransfer(self, sourceSlot, self, targetSlot, ...);

// 跨容器拉取：邻居端点 → 自端点
for (int slot = 0; slot < neighbor.slotCount(); slot++) {
    if (tryTransfer(neighbor, slot, self, targetSlot, ...)) return true;
}

// 跨容器推送：自端点 → 邻居端点
for (int slot = 0; slot < neighbor.slotCount(); slot++) {
    if (tryTransfer(self, sourceSlot, neighbor, slot, ...)) return true;
}
```

### 收益

| 方面 | 说明 |
|------|------|
| **逻辑一致性** | 新增检查项只需改一处，不会漏掉某条路径 |
| **维护成本** | bug 修复只需改一处 |
| **代码量** | `tryTransfer` 约 40 行，两个 Endpoint 各约 30 行，总计 ~100 行。当前 `executeInContainer` + `tryPull` + `tryPush` + `transferBetween` 约 120 行。代码量差不多，但结构更清晰 |

### 成本

| 方面 | 说明 |
|------|------|
| **两个抽象层** | `SlotAccessor` + `TransferEndpoint` 两层抽象，需要理解两者关系 |
| **Endpoint 桥接** | `SelfEndpoint` 用 `ContainerContext`（`getItem/setItem`），`NeighborEndpoint` 用 `IItemHandler`（`getStackInSlot/setStackInSlot`），两者有微妙差异 |
| **末影箱路由** | 容器内用 `EnderRouteManager.resolveTarget()` 的 Decision 枚举，跨容器用 `resolveCrossContainerTarget()`——不对称，需要在 `tryTransfer` 中特殊处理 |
| **大箱子** | `findDoubleChestPositions` 和 `getBasePosForDirection` 是跨容器独有的，`TransferEndpoint` 需要处理这个 |
| **方向映射** | `gridToWorld` 是跨容器独有的，`NeighborEndpoint` 的构建需要方向信息 |

### 结论

**值得做，但时机未到。** 这是一个架构升级，不是 bug 修复。建议在以下条件之一满足时再做：

1. 需要新增第 3 种传输路径（比如"容器到世界"、"跨维度"）
2. 新增一个检查项时，发现两个路径都需要改，且改漏了一条
3. 有人报告了一个因两条路径逻辑不一致导致的 bug

在那之前，当前架构（`TransferPipeline` + `CrossContainerTransfer` + 共享的 `tryPull/tryPush`）已经足够好。