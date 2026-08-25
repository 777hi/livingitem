好的，让我先全面了解文档体系和活水车的所有相关代码
The user wants me to:
1. First, thoroughly understand the documentation system and all the living water wheel (活水车) related logic
2. Review the current implementation (which they think has become messy/"屎山")
3. Propose a better redesign

Let me start by finding all the documentation and then reading all the relevant source files.

Let me start by reading the key documents to understand the system architecture and the living water wheel tech doc.

Let me continue reading the tech doc.

Let me continue reading the rest of the tech doc, then move to reading the actual source code.

The tech doc is very comprehensive. Let me now read the actual source code files to understand the current implementation. Let me read the key files.

Now let me read all the key source files


Good, I've read the full tech doc. Now let me read the actual source code files to understand the current implementation. Let me start with the key files.

Let me read all the water-related source files, plus the Create integration files and the container handler.

Let me now read all the other key files.

Now let me read the ContainerFluidData, ContainerLivingItemHandler, and the Create integration files.

Now let me read the ContainerLivingItemHandler.java to understand how the processContext pipeline works.

Let me read more of the ContainerLivingItemHandler, especially the processContext method.

Continue reading...

Now let me read the KineticBlockEntityMixin (the full current version) and the ModCreate class.

Now let me look at the remaining key files - ModCreate, LivingItemStressOutput, and the TickContext.

Now let me also read the LivingItemStressOutput interface, and the KineticNetworkMixin I just created.

Good, now let me also read the framework documentation and the data model documentation to understand the full picture.

Now I have a comprehensive understanding of the entire living water wheel system. Let me now analyze the current implementation and think about what redesign would look like.

Let me first read the remaining document files to understand the framework evolution and data model documents.

Now let me look at the remaining key files to complete the picture
Now I have a comprehensive understanding of the entire system. Let me now analyze the current implementation and think about what a redesign would look like.

Let me summarize the current architecture and identify the "shi shan" (messy) parts:

## Current Architecture Analysis

### The Flow Chain:
1. **ContainerLivingItemHandler.processContext()** - main tick entry point
2. For each tick, creates a **TickContext** which holds temporary `fluidData` and `stressData` 
3. **LivingWaterBucketFunction** manages water sources (BFS flow)
4. **LivingWaterWheelFunction** has `tickContainerData()` which:
   - Gets `ContainerFluidData` from `TickContext`
   - Gets `ContainerStressData` from `TickContext`
   - Calls `stressData.calculate(fluidData, ctx, waterWheelSlots)`
   - Then calls `postTickSync()` to update individual item DataComponents
5. **ContainerStressData.calculate()** iterates all water wheels, for each wheel iterates neighbors, computes torque
6. After all container data processing, `processContext()` writes stress data to BlockEntity attachment and calls `ModCreate.updateStressOutput()`
7. **ModCreate** → **CreateIntegration** → finds BE below container, checks whitelist, sets RPM via `LivingItemStressOutput` interface
8. **KineticBlockEntityMixin** implements `LivingItemStressOutput`, manages `generatedRPM`, `stressCapacity`, `refreshedThisTick`, `pendingReattach`, `needsSync`

### Key Problems (the "shi shan" aspects):

1. **Split responsibility across too many layers**: The stress data flows through `TickContext` → `ContainerStressData` → BlockEntity Attachment → `ModCreate` → `CreateIntegration` → `KineticBlockEntityMixin` → `KineticNetwork`. Each layer does a small piece, making the full flow hard to trace.

2. **KineticBlockEntityMixin is an overgrown god class**: It manages:
   - RPM setting with network attach/detach
   - Stress capacity with network update
   - Self-expiry mechanism (refreshedThisTick)
   - Chunk unload cleanup
   - Deferred client sync
   - Whitelist checking
   - Complex state transitions (prev=0→rpm!=0, prev!=0→rpm=0, prev!=0→rpm!=0)
   All in one class with lots of try-catch blocks.

3. **The call order is fragile**: `setGeneratedRPM` must be called before `setStressCapacity` because the latter checks `generatedRPM != 0`. But there's no enforced ordering - it's implicit.

4. **The self-expiry mechanism is fragile**: It relies on `refreshedThisTick` being set to true by `setGeneratedRPM` (which is called each tick) and cleared at the end of tick. If the water wheel is removed, `setGeneratedRPM` won't be called next tick, so `refreshedThisTick` stays false, triggering cleanup. But this depends on the exact tick ordering.

5. **The `setNetwork(null)` / `sources.remove()` ordering issue**: The code carefully manages the order of `generatedRPM` vs `setNetwork(null)` to ensure `isSource()` returns true so `sources.remove(be)` works. This is a very fragile dependency on Create's internal implementation.

6. **Two separate whitelist checks**: Both `CreateIntegration` and `KineticBlockEntityMixin` have their own `isSafeKineticBE()` method. If one is updated but not the other, bugs could occur.

7. **The `getGeneratedSpeed` Mixin was changed to pure getter**: Originally it had side effects (resetting `generatedRPM` and `stressCapacity`), which was removed to fix the network capacity issue. But the very fact that a getter had side effects shows the fragility.

8. **The `pendingReattach` mechanism**: When stress is removed, the BE detaches from kinetics, sets speed to 0, sets network to null, then schedules a reattach. This is a workaround for Create's behavior where `detachKinetics` needs to propagate removal to neighbors.

9. **The `sleep` issue**: When `setStressCapacity` is called but there's no network, the capacity is stored but the network update is skipped. Later when the network is created, the capacity might not be applied.

10. **The stress output at player feet**: When there's no associated block entity (player inventory), stress is output to the player's feet position. This is a separate code path with its own logic.

### Documentation system:
The project has excellent documentation:
- `docs/tech/` - per-feature tech docs (living-water-wheel-tech.md, living-copper-tech.md, etc.)
- `docs/system-design/` - system architecture docs (living-item-infrastructure.md, data-model.md, etc.)
- `docs/guides/` - guides (unit-testing.md, framework-evolution.md, etc.)
- `docs/archive/` - historical docs (refactoring-report.md, dev-issues.md, etc.)
- `docs/reference/` - reference docs

Now let me think about a redesign...

## Redesign Proposal

### Core Insight: The Problem is the "Push" Model

The current design is a **push** model: the container tick handler computes stress data, then pushes it to the BlockEntity via AttachmentType, then calls `ModCreate.updateStressOutput()` which pushes it to the KineticBlockEntity below.

The KineticBlockEntityMixin then has to handle all the complexity of connecting to the network, managing expiry, tearing down on removal, etc.

### Better Approach: "Pull" Model with a StressSource Provider

Instead of pushing stress data to the BE, we could have the BE **pull** stress data from the container above it. This would:

1. **Eliminate the need for AttachmentType storage**: The BE just reads the stress data from the container above when Create asks for it.
2. **Eliminate the self-expiry mechanism**: If the BE is ticked and there's no container/stress above, it naturally returns 0 RPM.
3. **Eliminate the setNetwork/setSpeed dance**: The Mixin simply overrides `getGeneratedSpeed()` and `calculateAddedStressCapacity()` to read from the container above.

But wait, this has a problem: the container tick handler runs on a different schedule than the KineticBlockEntity tick. The container tick is event-driven (when a player opens the container, etc.), while the KineticBlockEntity tick is every game tick.

Actually, looking at the code more carefully, the container tick handler runs on every server tick for all containers in loaded chunks. So it is effectively every tick.

Let me think about this differently...

### Alternative: StressSource BlockEntity

What if we create a custom BlockEntity that acts as a proxy between the container and the Create network? This BlockEntity would:
- Be placed below the container block
- Pull stress data from the container above
- Integrate with Create's network naturally (as a `GeneratingKineticBlockEntity` or similar)

But this requires creating a new BlockEntity type, which is a big change.

### Alternative: Clean Architecture with Clear Boundaries

Actually, the current architecture isn't fundamentally wrong. The main issues are:
1. Too much logic in the Mixin
2. Fragile state management
3. Implicit ordering dependencies

A cleaner redesign would:

1. **Extract a `StressOutputManager`** class that handles all the KineticBlockEntity state management:
   - Takes the stress data
   - Manages the network attach/detach
   - Handles the state transitions
   - The Mixin just delegates to it

2. **Use a clear state machine** instead of the implicit `refreshedThisTick` / `pendingReattach` flags:
   ```java
   enum StressState {
       INACTIVE,    // No stress, no network
       ACTIVE,      // Active stress, connected to network
       EXPIRING,    // Stress source removed, needs cleanup
       REATTACHING  // Cleaned up, awaiting reattach
   }
   ```

3. **Unify the flow**: Instead of `setGeneratedRPM` then `setStressCapacity` as two separate calls, have a single `applyStress(float rpm, float capacity)` call that atomically applies both.

4. **Remove the AttachmentType dependency**: The stress data is already computed in `ContainerStressData` and passed to `CreateIntegration`. We don't need to also store it in the BlockEntity. The AttachmentType was for persistence across chunk unload/reload, but the stress data is recomputed every tick from the container contents anyway.

5. **Simplify the Mixin** to just:
   - Override `getGeneratedSpeed()` to return `livingItem$rpm`
   - Override `calculateAddedStressCapacity()` to return `livingItem$capacity`
   - Have a simple `applyStress(rpm, cap)` method
   - Have a tick hook that checks if stress was refreshed this tick

Actually, let me think about this more carefully. The current issues that led to the complex code:

1. **Chunk unload/reload**: When a chunk is unloaded, the KineticBlockEntity's network is destroyed. On reload, the BE needs to rejoin the network. The current code handles this with `onChunkUnloaded` cleanup.

2. **Stress source removal**: When the water bucket/wheel is removed from the container, the stress needs to go away. The current code handles this with `refreshedThisTick`.

3. **Direction compatibility**: The stress direction must match the existing rotation direction of the network. The current code checks this in `CreateIntegration`.

4. **Whitelist**: Only certain BE types can receive stress. The current code checks this in two places.

The fundamental issue is that **we're trying to make a KineticBlockEntity behave like a GeneratingKineticBlockEntity, but without actually being one**. This is inherently fragile because we're working around Create's assumptions.

---

Let me think about what a truly clean redesign would be:

### Option A: Keep the Mixin, but extract a State Machine

```java
// In KineticBlockEntityMixin:
private final StressStateMachine stressState = new StressStateMachine();

@Inject(method = "getGeneratedSpeed", ...)
private void getGeneratedSpeed(...) {
    if (stressState.isActive()) {
        cir.setReturnValue(stressState.getRPM());
    }
}

@Inject(method = "calculateAddedStressCapacity", ...)
private void calculateAddedStressCapacity(...) {
    if (stressState.isActive()) {
        cir.setReturnValue(stressState.getCapacity());
    }
}

@Inject(method = "tick", at = @At("HEAD"), ...)
private void tickHead(...) {
    stressState.tick((KineticBlockEntity)(Object)this);
}

// External API:
public void livingItem$applyStress(float rpm, float capacity) {
    stressState.applyStress((KineticBlockEntity)(Object)this, rpm, capacity);
}
```

The `StressStateMachine` would handle all the complexity:
- State transitions
- Network attach/detach
- Chunk unload cleanup
- Deferred reattach
- Client sync

### Option B: Create a custom BlockEntity

Create a new BlockEntity that the user places below the container. This BE would be a proper Create kinetic source, eliminating all the Mixin complexity.

But this changes the user experience and requires a new block/item.

### Option C: Register as a proper KineticBlockEntity source

Use Create's API (if available) to register the container as a kinetic source. But Create doesn't have a simple API for this - it's designed around block-based sources.

---

I think Option A is the most practical. Let me flesh out the design.

Actually, let me reconsider. The user asked me to:
1. Understand the documentation system
2. Understand all the living water wheel logic
3. Review if the current implementation is a "shi shan" (spaghetti)
4. Propose a better redesign

Let me provide a thorough analysis and redesign proposal.

Let me also look at the remaining files to make sure I have a complete picture.

Now I have a thorough understanding of the entire system. Let me now compose my analysis and redesign proposal.

Let me also read the framework-evolution doc to understand the design patterns.

Good, I've now read enough. Let me compose a comprehensive analysis and redesign proposal.

Let me organize my thoughts:

1. **Current Architecture Summary** - flow from container tick to Create network
2. **"Shi Shan" (Spaghetti) Analysis** - what makes the current code messy
3. **Redesign Proposal** - how to clean it up

Let me write this out.
现在我已经完整理解了整个体系。下面是我的分析：

---

## 一、当前活水车架构全链路

### 数据流

```
ContainerLivingItemHandler.processContext()
  │
  ├─ 1. TickContext 创建 → 内嵌 new ContainerStressData()
  │
  ├─ 2. LivingWaterBucketFunction.tick() → BFS 水流蔓延 → ContainerFluidData
  │
  ├─ 3. LivingWaterWheelFunction.tickContainerData()
  │      └─ stressData.calculate(fluidData, ctx, waterWheelSlots)
  │           └─ 遍历每个水车 → 遍历四邻 → 叉积力矩 → 累加 CW/CCW
  │      └─ postTickSync() → 同步每个水车 ItemStack 的 DataComponent
  │
  ├─ 4. stressData → BlockEntity.setData(ATTACHMENT_TYPE)   ← 持久化
  │
  ├─ 5. ModCreate.updateStressOutput(level, containerPos, stressData)
  │      └─ CreateIntegration.updateStressOutput()
  │           ├─ 找容器下方 BlockEntity
  │           ├─ instanceof LivingItemStressOutput 检查
  │           ├─ isSafeKineticBE() 白名单
  │           ├─ isDirectionCompatible() 方向检查
  │           └─ stressOutput.setGeneratedRPM(rpm)  ← 进入 Mixin
  │           └─ stressOutput.setStressCapacity(suCapacity)
  │
  └─ 6. KineticBlockEntityMixin
         ├─ setGeneratedRPM() → 状态机: prev=0→attach / prev!=0→detach+reattach
         ├─ setStressCapacity() → network.updateCapacityFor() + updateStress()
         ├─ tick() HEAD → checkExpiry (refreshedThisTick)
         ├─ tick() TAIL → deferredSync (sendData)
         ├─ onChunkUnloaded() → network.remove() + detachKinetics()
         ├─ getGeneratedSpeed() → 返回 livingItem$generatedRPM
         └─ calculateAddedStressCapacity() → 返回 livingItem$stressCapacity
```

### 涉及的文件（共 12 个）

| 层 | 文件 | 职责 |
|----|------|------|
| 框架 | `ContainerLivingItemHandler.java` | Tick 调度、应力输出、缓存管理 |
| 框架 | `TickContext.java` | 临时持有 fluidData + stressData |
| 领域 | `ContainerFluidData.java` | BFS 水流蔓延 |
| 领域 | `ContainerStressData.java` | 力矩计算 |
| 领域 | `LivingWaterWheelFunction.java` | 水车功能入口、Tooltip |
| 领域 | `LivingWaterBucketFunction.java` | 水源注册/移除 |
| 数据 | `WaterWheelData.java` | ItemStack 上的应力 record |
| 数据 | `LivingWaterWheelData.java` | DataComponent 容器 |
| 中间层 | `ModCreate.java` | 软依赖入口 + 双重防护 |
| 中间层 | `CreateIntegration.java` | 应力→RPM/SU 换算 + 白名单 + 方向检查 |
| 接口 | `LivingItemStressOutput.java` | Mixin 接口 |
| Mixin | `KineticBlockEntityMixin.java` | 核心：状态机 + 网络管理 + 自过期 + 区块卸载 |

---

## 二、"屎山"诊断：具体问题

### 1. `KineticBlockEntityMixin` — 上帝类，279 行，职责过多

一个类同时管理了：

- **状态机**：`prev=0→rpm`, `prev→0`, `prev→rpm'` 三种转换
- **网络管理**：`attachKinetics` / `detachKinetics` / `setNetwork` / `updateCapacityFor`
- **自过期**：`refreshedThisTick` 标记 — 每 tick 由外部代码设置，tick 末尾清零
- **区块卸载**：`onChunkUnloaded` 强制清理
- **延迟重连**：`pendingReattach` — 解决 `detachKinetics` 后邻居网络断裂
- **客户端同步**：`deferredSync` + `sendData`
- **白名单过滤**：`isSafeForStressInjection`
- **日志**：大量 `LOGGER.info/debug` 散落在各处

### 2. 隐式调用顺序依赖

`setStressCapacity` 内部检查 `if (livingItem$generatedRPM == 0) return`，意味着必须先调用 `setGeneratedRPM` 再调用 `setStressCapacity`。但调用方 `CreateIntegration` 没有显式强制执行这个顺序，只是碰巧写了先 RPM 后 capacity。

### 3. `setNetwork(null)` 的时序陷阱

这是最脆弱的部分。文档中记录了：

> 在调用 `setNetwork(null)` 之前，必须确保 `getGeneratedSpeed()` 返回非零值，让 `isSource()` 返回 true，否则 `sources.remove(be)` 会被跳过。

当前代码通过 `refreshedThisTick = true` 和 `generatedRPM` 延迟清零来保证这一点。这完全依赖 Create 内部实现细节，Create 升级可能随时打破。

### 4. `getGeneratedSpeed` 的历史包袱

最初这个方法有副作用（当 `refreshedThisTick=false` 时清零 `generatedRPM` 和 `stressCapacity`），导致 `KineticNetwork.calculateCapacity()` 在读取 `getGeneratedSpeed()` 时清零了 `stressCapacity`，网络容量归零。后来修复为纯 getter，但这个修复本身就说明了代码的脆弱性。

### 5. 双重白名单

`CreateIntegration.isSafeKineticBE()` 和 `KineticBlockEntityMixin.isSafeForStressInjection()` 各自维护一份白名单。如果只改一处，另一处不会同步。虽然当前都用 `instanceof` + 类名前缀，但它们是独立维护的。

### 6. 应力输出两路

容器场景（`containerPos.below()`）和玩家背包场景（`player.blockPosition()`）是两个独立路径，但共享同一个 `CreateIntegration.updateStressOutput()`。逻辑上没问题，但如果容器同时有 containerKey 和 player inventory（如打开箱子时），两条路径都可能触发。

### 7. BlockEntity Attachment 冗余

`processContext()` 中先写 `be.setData(CONTAINER_STRESS_DATA, stressData)`，再调 `ModCreate.updateStressOutput()`。Attachment 的初衷是区块卸载后保留数据，但实际上应力数据每 tick 从容器内容重新计算，Attachment 存储是多余的——除非未来需要跨 tick 缓存。

### 8. `ContainerFluidData` 和 `ContainerStressData` 生命周期耦合在 `TickContext` 中

`TickContext` 同时持有 `fluidData` 和 `stressData`，但它们的生命周期并不同步。`fluidData` 从 `FLUID_DATA_CACHE` 获取（长时间存活），`stressData` 每次 new（单 tick 存活）。这导致代码中需要 `if (stressData != null && fluidData != null && !fluidData.isEmpty())` 这样的防御性检查。

---

## 三、重新设计方案

### 核心思路：提取 `StressOutputManager`，让 Mixin 只做薄层

```
┌─────────────────────────────────────────────────────────────┐
│                    ContainerLivingItemHandler                │
│  processContext() → stressData 计算完毕                       │
│       │                                                      │
│       └── StressOutputManager.apply(containerPos, stressData) │
│              │                                                │
│              ├── 找下方 BE                                    │
│              ├── 白名单检查                                    │
│              ├── 方向检查                                      │
│              ├── RPM/SU 换算                                   │
│              └── 调用 KineticBlockEntity 上的接口              │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                KineticBlockEntityMixin (薄层，~50行)          │
│                                                              │
│  getGeneratedSpeed() → return stateMachine.getRPM()          │
│  calculateAddedStressCapacity() → return stateMachine.getCap()│
│  tick() HEAD → stateMachine.tick(self)                       │
│  onChunkUnloaded() → stateMachine.onChunkUnloaded(self)       │
│                                                              │
│  livingItem$applyStress(rpm, cap) → stateMachine.apply(...)  │
│                                                              │
│  livingItem$isSafeForStressInjection() → instance check      │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│             StressStateMachine (纯逻辑，~150行，可测试)        │
│                                                              │
│  enum State { INACTIVE, ACTIVE, DETACHING, REATTACHING }     │
│                                                              │
│  apply(rpm, cap):                                            │
│    INACTIVE + rpm!=0 → setSpeed, setNetwork, attachKinetics  │
│    ACTIVE + rpm!=0   → detach + reattach with new speed      │
│    ACTIVE + rpm=0    → detach + setNetwork(null) + reattach   │
│    DETACHING         → ignore (cleanup in progress)           │
│    REATTACHING       → cancel pending, apply new state        │
│                                                              │
│  tick(self): 检查是否过期，执行状态转换                         │
│  onChunkUnloaded(self): 强制清理                               │
└─────────────────────────────────────────────────────────────┘
```

### 具体改进点

#### 改进 1：单一入口 `applyStress(rpm, cap)` 替代 `setGeneratedRPM` + `setStressCapacity`

```java
// 旧（两个独立调用，有隐式顺序依赖）
stressOutput.livingItem$setGeneratedRPM(rpm);
stressOutput.livingItem$setStressCapacity(suCapacity);

// 新（原子调用，状态机内部保证顺序）
stressOutput.livingItem$applyStress(rpm, suCapacity);
```

#### 改进 2：显式状态机替代 `refreshedThisTick` + `pendingReattach`

```java
enum State {
    INACTIVE,       // 无应力，无网络
    ACTIVE,         // 有应力，已连接网络
    EXPIRING,       // 应力源消失，需要清理（下一 tick 执行）
    REATTACHING     // 清理完成，等待重连邻居网络
}
```

状态转换清晰可追踪，不再依赖 `isSource()` 返回 true 的 hack。

#### 改进 3：合并 `CreateIntegration` 和 `ModCreate` 为 `StressOutputManager`

当前 `ModCreate` → `CreateIntegration` 两层分离是为了软依赖安全（`NoClassDefFoundError` 防护）。可以合并为一个类，在 `ModCreate` 类中直接实现，因为 `LivingItemStressOutput` 接口不依赖 Create 类。

#### 改进 4：白名单统一到 `LivingItemStressOutput` 接口

```java
public interface LivingItemStressOutput {
    void livingItem$applyStress(float rpm, float capacity);
    boolean livingItem$isSafeForStressInjection();  // 白名单（唯一入口）
    float livingItem$getTheoreticalSpeed();
}
```

`CreateIntegration` 不再自己维护白名单，只调用 `isSafeForStressInjection()`。

#### 改进 5：移除 BlockEntity Attachment 冗余

`processContext()` 中：
```java
// 旧：写 Attachment + 调 CreateIntegration
be.setData(CONTAINER_STRESS_DATA, stressData);
ModCreate.updateStressOutput(level, containerPos, stressData);

// 新：只调 StressOutputManager
StressOutputManager.apply(level, containerPos, stressData);
```

Attachment 可以保留用于调试/显示，但不作为核心数据流的一环。

#### 改进 6：`TickContext` 不再持有 `ContainerStressData`

```java
// 旧：TickContext 持有 stressData
tick.stressData = new ContainerStressData();
// ... 功能类填充 ...
// ... 最后读取 tick.stressData ...

// 新：ContainerStressData 由 LivingWaterWheelFunction 直接创建和返回
// TickContext 只持有 fluidData（跨功能共享）
```

### 重构后的文件结构

```
compat/create/
  ├── StressOutputManager.java    ← 合并 ModCreate + CreateIntegration
  ├── LivingItemStressOutput.java ← 接口（简化）
  └── CreateCompat.java           ← 不变

mixin/create/
  ├── KineticBlockEntityMixin.java     ← 薄层（~50行）
  ├── StressStateMachine.java          ← 新增：状态机（~150行，可单独测试）
  └── KineticNetworkMixin.java         ← 诊断用（测试完后删除）

domain/water/
  ├── ContainerFluidData.java          ← 不变
  ├── ContainerStressData.java         ← 不变
  ├── LivingWaterWheelFunction.java    ← 简化：不再依赖 TickContext.stressData
  ├── LivingWaterBucketFunction.java   ← 不变
  └── ...

container/
  ├── ContainerLivingItemHandler.java  ← 简化：移除 Attachment 写入
  ├── TickContext.java                 ← 简化：移除 stressData 字段
  └── ...
```

### 重构的优势

1. **可测试性**：`StressStateMachine` 是纯 Java 类，不依赖 Minecraft，可以写单元测试
2. **可读性**：状态机 5 个状态，每个状态的行为明确，不再有隐式依赖
3. **可维护性**：Mixin 只有 ~50 行，出错概率大大降低
4. **向后兼容**：`LivingItemStressOutput` 接口变化很小，只改方法签名
5. **去冗余**：移除 Attachment 写入、移除双重白名单、移除隐式调用顺序

### 重构的风险

1. **工作量**：`KineticBlockEntityMixin` 当前 279 行，重构需要仔细测试每个状态转换
2. **Create 内部 API 依赖**：`setNetwork(null)` / `sources.remove()` 的时序问题仍然存在，但被封装在状态机内部，影响面缩小
3. **回归风险**：当前代码虽然复杂但是经过多轮修复后基本稳定，重构可能引入新 bug

---

## 总结

当前活水车实现的核心问题是 **`KineticBlockEntityMixin` 承担了过多职责**，导致状态管理脆弱、隐式依赖多、难以测试。重构的核心思路是**提取 `StressStateMachine` 纯逻辑类**，让 Mixin 只做薄层代理。

是否需要我按照这个方案开始重构？建议先从 `StressStateMachine` 开始，因为它是纯逻辑，可以先写测试验证正确性。