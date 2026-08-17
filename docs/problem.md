好，让我追踪 `ContainerFluidData` 的完整生命周期。先看相关源码
The user wants me to trace the lifecycle of `ContainerFluidData` and identify potential hidden issues. Let me read the relevant source code files to understand the full lifecycle.

Let me read all the key files to trace the lifecycle.

Now let me read the remaining part of ContainerLivingItemHandler to see the cleanup logic and how processContext ends.

Now let me also read `ContainerSnapshot` to see how `fluidData` is used there, and `SimpleContainerContext` to see `getOrCreateFluidData`.

Now let me look at the ContainerSnapshot.capture method to see how fluidData is passed through.

Now let me look at the LivingWaterBucketFunction to see how fluidData is used there.

Now let me also check the LivingWaterWheelFunction to see how it uses fluidData.

Now I have a comprehensive picture of the entire ContainerFluidData lifecycle. Let me trace it:

## Lifecycle of ContainerFluidData

### 1. Creation
- `ContainerLivingItemHandler.FLUID_DATA_CACHE` — a `LinkedHashMap<String, ContainerFluidData>` static cache
- Created via `getFluidData(containerKey)` → `FLUID_DATA_CACHE.computeIfAbsent(containerKey, k -> new ContainerFluidData())`
- This is a **static** cache, meaning it persists across ticks

### 2. Retrieval per tick
- `TickContext.reset(ctx)` → if ctx is `SimpleContainerContext`, calls `simpleCtx.getOrCreateFluidData()`
- `SimpleContainerContext.getOrCreateFluidData()` → `ContainerLivingItemHandler.getFluidData(containerKey)` → gets from static cache
- This means `fluidData` is **shared across ticks** for the same container

### 3. Usage in tick
- `ContainerSnapshot.capture(ctx, fluidData)` — stores a **reference** to the same `fluidData` instance
- `LivingWaterBucketFunction.tick()` — reads `tick.fluidData`, calls `registerSource()` and `removeSource()`
- `LivingWaterBucketFunction.tickContainerData()` — calls `fluidData.setLastTickTime()` and `fluidData.tick(ctx)`
- `LivingWaterWheelFunction.tickContainerData()` — reads `tick.fluidData` for stress calculation

### 4. Cleanup
- After all functions tick, in `processContext()`:
  ```java
  ContainerFluidData fluidData = tick.fluidData;
  String containerKey = context.getContainerKey();
  if (fluidData != null && fluidData.isEmpty() && containerKey != null) {
      FLUID_DATA_CACHE.remove(containerKey);
  }
  ```
- Periodic cleanup: every 1200 ticks, `cleanupStaleFluidData()` removes entries not accessed in 120 seconds
- Explicit cleanup: `removeFluidData(containerKey)` when a container is destroyed

### 5. Release
- `TickContext.clear()` → `fluidData = ContainerFluidData.EMPTY` — the reference is cleared, but the actual object lives in the static cache

Now let me think about potential hidden issues:

### Issue 1: `lastTickTime` measurement unit mismatch
In `ContainerFluidData`:
- `setLastTickTime(long time)` is called with `System.currentTimeMillis()` (from `tickContainerData`)
- `getLastTickTime()` is used in `cleanupStaleFluidData()` comparing with `currentTimeMs` (also `System.currentTimeMillis()`)
- This is fine — both use `System.currentTimeMillis()`

BUT wait, let me look at `ContainerFluidData`:
```java
private long lastTickTime;
```

And in `tickContainerData`:
```java
fluidData.setLastTickTime(System.currentTimeMillis());
```

And in `cleanupStaleFluidData`:
```java
return currentTimeMs - data.getLastTickTime() > 120_000;
```

This is consistent. OK.

### Issue 2: Race condition / stale data in static cache
The `FLUID_DATA_CACHE` is a static `LinkedHashMap` — not thread-safe. But since Minecraft server ticks are single-threaded, this should be fine.

### Issue 3: Container key collision
Different container types might have the same key? Let me check the key generation:
- Player inventory: `"player_" + player.getUUID()`
- Player ender chest: `"player_" + player.getUUID() + "_ender_chest"`
- Block containers: `"chest_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ()`

These should be unique. OK.

### Issue 4: `EMPTY` singleton mutation danger
`ContainerFluidData.EMPTY` is a static final singleton. If any code mutates it, it would corrupt the empty state globally. Let me check...

In `TickContext.clear()`:
```java
fluidData = ContainerFluidData.EMPTY;
```

But `EMPTY` is only used as a reference placeholder. The actual mutations happen on the real instances from the cache. Let me check if any code path could mutate `EMPTY`...

In `LivingWaterBucketFunction.tick()`:
```java
ContainerFluidData fluidData = tick.fluidData;
if (needsReset && fluidData != null && water.hostSlot() >= 0) {
    fluidData.removeSource(water.hostSlot());
}
```

If `tick.fluidData` is `EMPTY` (which happens when `getOrCreateFluidData()` returns null, but wait — `getOrCreateFluidData()` actually calls `getFluidData(containerKey)` which calls `computeIfAbsent`, so it always returns a non-null instance).

Actually wait, let me look at `TickContext.reset()`:
```java
ContainerFluidData fluidData = ContainerFluidData.EMPTY;
if (ctx instanceof SimpleContainerContext simpleCtx) {
    fluidData = simpleCtx.getOrCreateFluidData();
}
this.fluidData = fluidData;
```

And `SimpleContainerContext.getOrCreateFluidData()`:
```java
ContainerFluidData getOrCreateFluidData() {
    if (fluidData == null) {
        fluidData = ContainerLivingItemHandler.getFluidData(containerKey);
    }
    return fluidData;
}
```

And `getFluidData`:
```java
public static ContainerFluidData getFluidData(String containerKey) {
    if (containerKey == null) return null;
    return FLUID_DATA_CACHE.computeIfAbsent(containerKey, k -> new ContainerFluidData());
}
```

So if `containerKey` is null, `getFluidData` returns null, `getOrCreateFluidData` returns null, and `tick.fluidData` stays as `ContainerFluidData.EMPTY`.

Then in `LivingWaterBucketFunction.tick()`:
```java
ContainerFluidData fluidData = tick.fluidData;
if (needsReset && fluidData != null && water.hostSlot() >= 0) {
    fluidData.removeSource(water.hostSlot());  // Mutating EMPTY!
}
```

**THIS IS A BUG!** If `containerKey` is null (which can happen for some edge case containers), `tick.fluidData` is `EMPTY`, and `removeSource()` is called on the `EMPTY` singleton!

Wait, let me check `removeSource`:
```java
public void removeSource(int slot) {
    FlowEntry entry = flows.get(slot);
    if (entry != null) {
        entry.isSource = false;
    }
}
```

If `flows` is empty (as in `EMPTY`), `flows.get(slot)` returns null, so `entry != null` is false, and nothing happens. But this is still a fragile situation — if someone later adds code that mutates `flows` directly, it would corrupt `EMPTY`.

Actually wait, the `EMPTY` object has `flows = new LinkedHashMap<>()` (empty but mutable). If `removeSource` is called, it does `flows.get(slot)` which returns null, so no mutation. But `registerSource` would actually mutate it:

```java
public void registerSource(int slot) {
    FlowEntry existing = flows.get(slot);
    if (existing != null) {
        existing.isSource = true;
        existing.level = SOURCE_LEVEL;
        existing.fromSlot = -1;
    } else {
        flows.put(slot, new FlowEntry(SOURCE_LEVEL, true, -1));  // This would mutate EMPTY!
    }
}
```

But `registerSource` is only called when `fluidData != null` and it's inside `LivingWaterBucketFunction.tick()`. However, there's already a check `if (fluidData != null)` before calling `registerSource`. And `EMPTY` is non-null (it's a valid reference). So if `containerKey` is null, `tick.fluidData` is `EMPTY`, and `registerSource` would be called on `EMPTY`, mutating it!

Wait, but would a water bucket ever be in a container without a `containerKey`? Let me check... The `containerKey` is generated by `ContainerContext.getContainerKey()`. For `SimpleContainerContext`, it's typically set in the constructor. Let me check if there's a case where it could be null.

Actually, looking at `SimpleContainerContext` constructors... I need to check if `containerKey` can be null. But even if it can't be null in practice, the code path `tick.fluidData = ContainerFluidData.EMPTY` when `containerKey` is null means `EMPTY` could be mutated. This is a latent bug.

### Issue 5: `EMPTY` singleton is not immutable
The `EMPTY` singleton has mutable `flows` (LinkedHashMap). Any code that receives `EMPTY` and calls `registerSource()`, `removeSource()`, or `tick()` on it would corrupt the global state. The current code has:
- `tick()` is only called in `tickContainerData()` which checks `!fluidData.isEmpty()` — OK, EMPTY is empty so tick is never called
- `registerSource()` is called in `LivingWaterBucketFunction.tick()` with `if (fluidData != null)` check — but EMPTY is non-null!
- `removeSource()` is called similarly

So the bug path is:
1. Water bucket in a container with null `containerKey`
2. `tick.fluidData` = `EMPTY`
3. `fluidData.registerSource(slot)` is called → mutates EMPTY's flows
4. Next time any container uses EMPTY, it has stale data

Actually, wait. Let me re-check. `containerKey` is null only if `getContainerKey()` returns null. Let me look at that...

Looking at `SimpleContainerContext`, the `containerKey` is set in the constructor. It's typically always non-null. But there could be edge cases.

Actually, the more important issue is: **the `EMPTY` object is used as a fallback and is mutable**. This is a design smell. If `containerKey` is ever null, EMPTY gets mutated and becomes corrupted for all subsequent users.

### Issue 6: `lastTickTime` not updated when `tick()` is called
Wait, `setLastTickTime` is called in `tickContainerData`:
```java
fluidData.setLastTickTime(System.currentTimeMillis());
fluidData.tick(ctx);
```

But the cleanup uses `lastTickTime`:
```java
return currentTimeMs - data.getLastTickTime() > 120_000;
```

This is fine — `setLastTickTime` is called every tick for active containers.

BUT, what about the `EMPTY` singleton? Its `lastTickTime` is never updated. The cleanup code checks `FLUID_DATA_CACHE.entrySet()`, and `EMPTY` is never in the cache (it's created separately), so this is fine.

### Issue 7: `tick()` called in `tickContainerData` but `tick()` also called in `ContainerFluidData.tick()` by `LivingWaterBucketFunction.tickContainerData()`
Wait, let me re-read the flow:

In `ContainerLivingItemHandler.processContext()`:
1. All functions' `tick()` are called
2. Then all `HasContainerData` functions' `tickContainerData()` are called, sorted by priority

`LivingWaterBucketFunction` implements both `LivingItemFunction` and `HasContainerData`. So:
1. `LivingWaterBucketFunction.tick()` is called first — registers sources, removes sources, but does NOT call `fluidData.tick()`
2. `LivingWaterBucketFunction.tickContainerData()` is called later — calls `fluidData.tick()` and `postTickSync()`

This is correct — source registration happens before fluid simulation.

### Issue 8: Multiple water buckets in the same container
`LivingWaterBucketFunction.tick()` iterates all water bucket entries and registers sources. Then `tickContainerData()` is called once (per function, not per entry), and calls `fluidData.tick()` once. This means all water buckets in the same container share the same `fluidData`. This is correct and intentional.

### Issue 9: `pushItems` uses `ctx.setItem()` which now uses `Container.setItem()`
The `pushItems` method in `ContainerFluidData` calls `ctx.setItem(targetSlot, moved)` and `ctx.setItem(slot, ItemStack.EMPTY)`. With our recent fix, `setItem` now uses `Container.setItem()` when available. This should be fine — the water bucket is pushing items within the same container.

### Issue 10: The `EMPTY` singleton is the REAL hidden issue
Let me trace this more carefully:

```java
// TickContext.reset()
ContainerFluidData fluidData = ContainerFluidData.EMPTY;  // default
if (ctx instanceof SimpleContainerContext simpleCtx) {
    fluidData = simpleCtx.getOrCreateFluidData();  // may return null if containerKey is null
}
this.fluidData = fluidData;  // Could be EMPTY or null or real instance
```

`getOrCreateFluidData()`:
```java
ContainerFluidData getOrCreateFluidData() {
    if (fluidData == null) {
        fluidData = ContainerLivingItemHandler.getFluidData(containerKey);
    }
    return fluidData;
}
```

`getFluidData()`:
```java
public static ContainerFluidData getFluidData(String containerKey) {
    if (containerKey == null) return null;
    return FLUID_DATA_CACHE.computeIfAbsent(containerKey, k -> new ContainerFluidData());
}
```

So if `containerKey` is null:
- `getFluidData` returns null
- `getOrCreateFluidData` returns null
- `tick.fluidData` stays as `ContainerFluidData.EMPTY`

Then in `LivingWaterBucketFunction.tick()`:
```java
ContainerFluidData fluidData = tick.fluidData;
// fluidData is EMPTY (non-null)
if (needsReset && fluidData != null && water.hostSlot() >= 0) {
    fluidData.removeSource(water.hostSlot());  // Safe: EMPTY.flows is empty, so get returns null
}
// ...
if (fluidData != null) {
    fluidData.registerSource(slot);  // DANGER: Mutates EMPTY.flows!
}
```

`registerSource(slot)`:
```java
public void registerSource(int slot) {
    FlowEntry existing = flows.get(slot);  // null (EMPTY has empty flows)
    if (existing != null) {
        // not taken
    } else {
        flows.put(slot, new FlowEntry(SOURCE_LEVEL, true, -1));  // MUTATES EMPTY!
    }
}
```

This would add an entry to `EMPTY.flows`, making it non-empty! Subsequent uses of `EMPTY` would see a non-empty `ContainerFluidData`.

Then in `processContext()`:
```java
ContainerFluidData fluidData = tick.fluidData;
if (fluidData != null && fluidData.isEmpty() && containerKey != null) {
    FLUID_DATA_CACHE.remove(containerKey);
}
```

Since `containerKey` is null, we skip the remove. But `EMPTY` is now corrupted!

Also, `tickContainerData()`:
```java
public void tickContainerData(List<SlotEntry> entries, ContainerContext ctx, TickContext tick) {
    ContainerFluidData fluidData = tick.fluidData;
    if (fluidData != null && !fluidData.isEmpty()) {
        fluidData.setLastTickTime(System.currentTimeMillis());
        fluidData.tick(ctx);  // Would tick on EMPTY!
    }
    postTickSync(ctx, fluidData, entries);
}
```

After `registerSource` mutates EMPTY, `isEmpty()` returns false, and `fluidData.tick(ctx)` would be called on EMPTY! This would run BFS, push items, etc. on the EMPTY singleton, affecting all subsequent uses.

### To summarize, the biggest hidden issue is:

**The `ContainerFluidData.EMPTY` singleton is mutable and can be corrupted** when:
1. A water bucket exists in a container with null `containerKey`
2. `tick.fluidData` falls back to `EMPTY`
3. `registerSource()` is called on `EMPTY`, mutating its `flows` map
4. `EMPTY` becomes non-empty, and subsequent ticks would run simulation on the global singleton

### Other issues:
- **`EMPTY` not thread-safe**: While MC is single-threaded, the EMPTY singleton pattern is fragile
- **`containerKey` null edge case**: Not well-handled in the fluid data lifecycle
- **No defensive copy**: `ContainerSnapshot` stores a direct reference to `fluidData`, so changes to `fluidData` are visible through the snapshot

Let me now write up the analysis clearly.

Now I have the full picture. Let me write a comprehensive analysis.

Let me also check if `processContainerAt` calls `removeFluidData` when a container is destroyed.

`removeFluidData` is defined but never called from anywhere (no caller found). Let me verify.

`removeFluidData` is defined but NEVER called from anywhere! The only cleanup path is:
1. `isEmpty()` check in `processContext()` — removes when fluid data becomes empty
2. `cleanupStaleFluidData()` — periodic cleanup every 1200 ticks (60 seconds) for entries not accessed in 120 seconds

But there's no cleanup when a container block is destroyed! The `removeFluidData` method exists but is never called.

Now let me compile my full analysis.
好，我已经完整追踪了 `ContainerFluidData` 的生命周期。以下是分析：

---

## ContainerFluidData 生命周期

```
                        FLUID_DATA_CACHE (static LinkedHashMap)
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
   创建 (首次)           获取 (每tick)           清理 (两路径)
        │                     │                     │
        ▼                     ▼                     ▼
  getFluidData(key)    TickContext.reset()    ① isEmpty → remove
  computeIfAbsent ──→  getOrCreateFluidData()  ② cleanupStale (120s)
        │                     │
        ▼                     ▼
  new ContainerFluidData()  tick.fluidData = 实例
        │                     │
        ├─ LivingWaterBucketFunction.tick()
        │    ├─ registerSource(slot)     ← 注册水源
        │    └─ removeSource(slot)       ← 移除水源
        │
        ├─ LivingWaterBucketFunction.tickContainerData()
        │    ├─ setLastTickTime()         ← 更新访问时间
        │    └─ tick()                    ← BFS + pushItems
        │
        ├─ LivingWaterWheelFunction.tickContainerData()
        │    └─ stressData.calculate(fluidData, ...)
        │
        └─ ContainerSnapshot.capture(ctx, fluidData)
             └─ 存储引用（非拷贝）
```

---

## 发现的隐藏问题

### 🔴 问题 1：`EMPTY` 单例可被污染（严重）

**路径**：`TickContext.reset()` 中，当 `containerKey` 为 null 时，`tick.fluidData` 回退到 `ContainerFluidData.EMPTY`：

```java
// TickContext.reset() — 第 136-140 行
ContainerFluidData fluidData = ContainerFluidData.EMPTY;  // 默认值
if (ctx instanceof SimpleContainerContext simpleCtx) {
    fluidData = simpleCtx.getOrCreateFluidData();  // containerKey=null → 返回 null
}
this.fluidData = fluidData;  // 仍是 EMPTY！
```

然后 `LivingWaterBucketFunction.tick()` 中：

```java
if (fluidData != null) {                     // EMPTY 非 null，通过！
    fluidData.registerSource(slot);           // 直接写入 EMPTY.flows！
}
```

`registerSource` 会调用 `flows.put(slot, new FlowEntry(...))`，**污染全局 EMPTY 单例**。之后 `tickContainerData()` 看到 `!isEmpty()` 为 true，会对 EMPTY 执行 BFS + pushItems，导致：
- 后续使用 EMPTY 的容器看到脏数据
- 对象池复用的 TickContext 残留污染状态

### 🟡 问题 2：`removeFluidData()` 从未被调用

`removeFluidData(containerKey)` 在 [ContainerLivingItemHandler.java#L69](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/container/ContainerLivingItemHandler.java#L69) 定义，但**全代码库没有任何调用点**。容器方块被破坏时，流体数据不会立即清理，只能等 120 秒后的 `cleanupStaleFluidData` 定期清理。

### 🟡 问题 3：`ContainerSnapshot` 持有 `fluidData` 直接引用

`ContainerSnapshot.capture()` 直接存储引用而非拷贝。这意味着 `ContainerSnapshot` 和 `TickContext` 指向同一个 `fluidData` 实例。如果 snapshot 被缓存或延迟使用，其中的 `fluidData` 可能已被后续 tick 修改。

### 🟢 问题 4（轻微）：`lastTickTime` 语义模糊

`setLastTickTime(System.currentTimeMillis())` 在 `tickContainerData()` 中调用，但只有水桶和水车调用 `tickContainerData`。如果没有水桶/水车，`lastTickTime` 不会被更新，120 秒后 fluidData 被清理。但如果容器中有水桶后被移除，fluidData 变成空，走 `isEmpty → remove` 路径清理——这是 OK 的。

---

## 修复建议

**问题 1 最紧急**，修复方案：`EMPTY` 应该真正不可变，且 `TickContext.reset()` 中 containerKey 为 null 时应保持 `EMPTY` 不被后续代码污染。

```java
// 方案 A：让 EMPTY 的所有修改方法安全无操作
// 在 registerSource / removeSource 中加 isEmpty() 检查
public void registerSource(int slot) {
    if (this == EMPTY) return;  // 防御
    // ...
}

// 方案 B：在 LivingWaterBucketFunction.tick() 中加保护
ContainerFluidData fluidData = tick.fluidData;
if (fluidData == ContainerFluidData.EMPTY) return;  // 跳过
```

需要我实施修复吗？