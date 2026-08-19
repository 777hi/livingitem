The user is asking a deep architectural question: as more living items with increasing domain complexity are added, will the current framework's advantages diminish, reducing it to just a "neat folder organizer"? And what should a good framework look like?

This is a pure discussion/analysis question - no code changes needed. Let me think deeply about this.

Let me analyze the current framework's value proposition and its limits:

**Current framework provides:**
1. Container scanning & tick dispatching (ContainerLivingItemHandler)
2. DataComponent management (LivingItemManager)
3. Container abstraction (ContainerContext + 4 interfaces)
4. Tick-level state (TickContext)
5. Transfer infrastructure (SlotAccessor hierarchy)
6. Create soft-dependency (CreateCompat → ModCreate → CreateIntegration)
7. Fluid data (ContainerFluidData)
8. Stress data (ContainerStressData)
9. Client sync (syncSlotToClients)
10. GUI interaction (InteractionRegistry)

**The user's concern is valid:** As domain complexity grows, the "framework" part (items 1-5, 9-10) becomes a smaller fraction of the total code. Each new living item brings more domain-specific logic that the framework can't help with.

For example:
- Living Water Bucket: BFS water flow algorithm is 100% domain
- Living Water Wheel: Torque calculation + Create integration is 90% domain
- A hypothetical "Living Redstone" item: Redstone signal propagation is 100% domain
- A hypothetical "Living Enchanting Table": Enchantment logic is 100% domain

The framework helps with the "plumbing" (how to read/write data, how to sync, how to scan containers), but not with the "brain" (what the living item actually does).

**The real question is: what makes a framework valuable as domain complexity grows?**

A good framework should:
1. **Minimize boilerplate** - but this has diminishing returns
2. **Provide composable primitives** - so domain logic can be assembled from reusable pieces
3. **Enable cross-cutting features** - features that benefit ALL living items without each one knowing about it
4. **Make the common case easy and the rare case possible** - but not force everything into the same mold

Let me think about what the current framework DOESN'T do well:

1. **No composable domain primitives**: Each living item solves its own domain problem from scratch. Water flow BFS, torque calculation, recipe matching - none of these compose with each other or with future living items.

2. **ContainerContext is still somewhat rigid**: The 4-interface split is good, but the actual data flow pattern (scan → group → tick → sync) is hardcoded in ContainerLivingItemHandler. If a new living item needs a different tick pattern (e.g., multi-phase tick, deferred processing), it has to fight the framework.

3. **No domain-level abstraction**: The framework operates at the "infrastructure" level (containers, slots, sync). It doesn't provide any abstractions at the "living item behavior" level. For example:
   - No "I observe my surroundings" abstraction (used by water bucket, water wheel, and potentially future items)
   - No "I produce/consume a resource" abstraction (used by water wheel's stress, furnace's fuel, and potentially future items)
   - No "I react to neighbors" abstraction (used by hopper's transfer, water bucket's flow, and potentially future items)

4. **The domain/ folder is just grouping, not abstraction**: Currently `domain/ender/` and `domain/water/` are just folders that group related files. They don't define any domain-level contracts or protocols.

So the user's concern is valid: as more living items are added, the framework becomes more of a "project template" than a "force multiplier."

**What would make the framework more valuable as domain complexity grows?**

1. **Domain primitives that compose**:
   - `NeighborObserver` - "I observe my neighbors" (water bucket, water wheel, future redstone)
   - `ResourceProducer<T>` - "I produce a resource" (water wheel produces stress, future items might produce redstone signal, mana, etc.)
   - `ResourceConsumer<T>` - "I consume a resource" (furnace consumes fuel, future items might consume mana)
   - `FlowSimulator` - "I simulate flow in a grid" (water BFS, future lava BFS, future redstone signal propagation)

2. **Cross-cutting features via the framework**:
   - Performance monitoring (already have PerfMetrics)
   - Debug visualization (show water flow, show stress, show transfer paths)
   - Configuration (per-item-type settings)
   - These should work for ALL living items without each one implementing them

3. **Protocol-based integration**:
   - Instead of each living item knowing about Create specifically, define a "StressOutput" protocol that any mod can implement
   - Instead of hardcoding Create integration, make it a "capability" that mods register

But I also need to be honest: there's a risk of over-abstracting. The "framework" should emerge from actual needs, not be designed upfront. The current approach of "write the living item first, then extract common patterns" is actually the right approach.

Let me also think about the counter-argument: maybe the framework IS already good enough, and the user is worrying unnecessarily. After all:
- Minecraft modding is inherently domain-specific
- Each living item IS different, and forcing them into a common mold would be counterproductive
- The framework's job is to handle the plumbing, not the brain
- As long as adding a new living item doesn't require modifying the framework, the framework is doing its job

I think the answer is nuanced: the current framework is good at infrastructure, but could be better at domain-level composition. The key insight is that **the framework should grow WITH the living items, not ahead of them**. When you notice the 3rd living item reimplementing something the 1st and 2nd already did, THAT's when you extract it into the framework.

Let me write a thoughtful response.
这个问题问到了框架设计的核心矛盾喵。让我认真拆解一下。

## 当前框架的真实价值曲线

```
框架价值
  ↑
  │     ╱ ← 理想：新活物品越多，框架价值越高（网络效应）
  │    ╱
  │   ╱
  │  ╱
  │ ╱  ← 当前：新活物品越多，框架价值增速放缓（只省了管道代码）
  │╱
  └──────────────────→ 活物品数量
     1   3   5   7   9
```

你的担忧是对的：当前框架解决的是**管道问题**（容器扫描、DataComponent 读写、客户端同步），而每个新活物品的核心复杂度是**领域问题**（BFS 水流、力矩计算、配方匹配）。管道代码是固定成本，领域代码是增长成本。当领域代码远超管道代码时，框架确实变成了"整齐的文件夹"。

## 问题的本质：缺少领域原语

当前框架只有**基础设施原语**，没有**领域原语**：

```
当前框架提供的（基础设施层）：
  ContainerContext  — 怎么读写容器
  TickContext       — 怎么管理 tick 状态
  SlotAccessor      — 怎么传输物品
  LivingItemManager — 怎么注册/读写数据
  CreateCompat      — 怎么安全调用 Create

当前框架没有的（领域层）：
  ??? — 怎么观察邻居
  ??? — 怎么模拟网格中的流动
  ??? — 怎么产生/消耗资源
  ??? — 怎么响应环境变化
```

结果就是每个活物品都在**从零解决领域问题**：

| 领域模式 | 活水桶的实现 | 活水车的实现 | 未来活红石？ |
|---------|------------|------------|------------|
| 观察邻居 | `ContainerContext.getNeighbors()` + BFS | `ContainerContext.getNeighbors()` + 叉积 | `getNeighbors()` + 信号衰减 |
| 网格流动 | `ContainerFluidData` BFS 蔓延 | 复用 `ContainerFluidData` 的流信息 | 信号传播（也是 BFS 变体） |
| 资源产出 | 水源注册 | 应力累加 | 红石信号强度 |
| 资源输出 | 阻挡水流 | `CreateIntegration` 输出应力 | 输出红石信号到方块 |

**三个活物品都在做"观察邻居→计算→产出资源→输出到外部"这件事**，但每个都是独立实现的。

## 好框架应该怎样？

好的框架不是"提供更多基础设施"，而是**让领域模式可组合**。

### 原则 1：从重复中提取，不从想象中设计

```
❌ 错误做法：先设计一个通用的 IFlowSimulator<T> 接口，
   然后让活水桶、活水车、活红石都实现它
   → 过度抽象，接口设计会偏离实际需求

✅ 正确做法：等第 3 个活物品也写了 BFS 遍历逻辑后，
   再提取共同的 FlowSimulator
   → 从真实需求中提炼，接口自然贴合
```

### 原则 2：领域原语应该是可组合的积木，不是强制继承的基类

```
❌ 错误做法：
   abstract class LivingItem {
       abstract void observeNeighbors();  // 强制每个活物品都实现
       abstract void produceResource();
       abstract void outputToWorld();
   }

✅ 正确做法：
   // 活物品按需组合领域原语
   LivingWaterBucket = NeighborObserver + FlowSimulator + FluidBlocker
   LivingWaterWheel  = NeighborObserver + TorqueCalculator + StressOutput
   LivingHopper      = TransferEngine + FilterSystem
   LivingFurnace     = RecipeMatcher + FuelConsumer + ProgressTracker
   LivingRedstone    = NeighborObserver + FlowSimulator + SignalOutput
                       ↑ 复用！         ↑ 复用！        新原语
```

### 原则 3：框架应该让跨活物品的特性"免费获得"

```
当前：每个活物品自己写 Tooltip、自己写同步、自己写性能监控
理想：活物品只声明"我产出应力"，框架自动生成：
      - Tooltip（应力状态显示）
      - 同步（应力数据变化时自动同步）
      - 性能监控（自动统计应力计算耗时）
      - 调试可视化（F3 面板显示应力流向）
```

## 具体到当前项目：下一步该怎么演进？

我认为当前阶段不需要大重构，而是**在写新活物品时留意重复模式**：

### 第一步：识别重复（现在可以做）

观察活水桶和活水车的共同模式：

```
活水桶的 tick 流程：
  1. 注册水源到 ContainerFluidData
  2. ContainerFluidData.tick() → BFS 蔓延
  3. 读取蔓延结果 → 更新 WaterData
  4. 同步到客户端

活水车的 tick 流程：
  1. ContainerFluidData 已经蔓延好了
  2. 读取蔓延结果 → 计算力矩 → 累加应力
  3. 更新 WaterWheelData
  4. 输出应力到 Create
  5. 同步到客户端
```

共同点：**都依赖 ContainerFluidData 的邻居流动信息**。活水车不是"自己观察邻居"，而是"复用活水桶已经计算好的流动数据"。

这其实已经是一个领域原语了——只是没有显式地抽象出来。

### 第二步：当第 3 个活物品出现时提取

假设未来加了"活熔岩桶"，它也需要 BFS 流动（只是流体类型不同），这时候就可以提取：

```java
// 从 ContainerFluidData 中提炼
public class GridFlowSimulator {
    // BFS 蔓延算法（与流体类型无关）
    // 活水桶：FlowType.WATER
    // 活熔岩桶：FlowType.LAVA
    // 活红石：FlowType.SIGNAL
}
```

### 第三步：让领域原语成为框架的一部分

```
src/main/java/com/qiqi/li/living/
├── api/           — 基础设施（LivingItemFunction, LivingItemManager）
├── domain/        — 领域模块（按活物品类型聚合）
├── compat/        — 第三方兼容
├── transfer/      — 传输基础设施
├── primitives/    — ⭐ 领域原语（从重复中提取的可组合积木）
│   ├── flow/      — 网格流动模拟（BFS 蔓延、方向计算）
│   ├── observe/   — 邻居观察（位置差、方向感知）
│   ├── resource/  — 资源产出/消耗（应力、红石信号、魔力…）
│   └── react/     — 环境响应（水流阻挡、红石激活…）
├── container/     — 容器抽象
├── model/         — 配置模型
└── client/        — 客户端
```

## 最终回答

> 当前的模组结构会不会变成只是将各个活物品比较整齐的放在一起？

**会，如果框架只停留在基础设施层。** 当前框架的价值是"让你不用重复写容器扫描和同步"，这确实是固定收益，不会随活物品增多而增长。

> 而本身的框架优势会变得微乎其微呢？

**不会，如果框架能从领域重复中提炼原语。** 关键转变是：**框架不应该只是管道，还应该是领域模式的载体**。

但这个转变不应该提前设计——应该**等第 3 个活物品暴露了重复模式后再提取**。现在活水桶和活水车只有 2 个数据点，还不足以确定哪些是真正的共同模式、哪些只是巧合的相似。等活红石或活熔岩桶加入后，重复模式会自然浮现，那时候提取的原语才是真正有用的。

**一句话**：好框架不是设计出来的，是从重复中长出来的。当前的"整齐文件夹"阶段是必要的——它是未来领域原语的素材来源。

---

## 2026.08.18 更新：活红石领域的第一批原语提取

活红石系统 7 个核心物品全部实现后，发现了以下重复模式并完成了提取：

### 已提取的原语

| 原语 | 位置 | 提取前 | 提取后 |
|------|------|--------|--------|
| `Pos2D.opposite()` | `model/Pos2D.java` | 3 个 Function 类各有一份 `getInputDirection()`（8行×3） | 1 个框架方法，调用方写 `data.direction().opposite()` |
| Phase 方法拆分 | `ContainerRedstoneData.java` | `calculate()` 一个方法 200+ 行，每阶段重复 for 循环 | 拆分为 5 个私有方法：`phase0CountdownDelays()` / `phase1CollectSources()` / `phase2IterativePropagation()` / `phase3RecheckInputs()` / `phase4UpdateDisplay()` |
| `prevSignalStrength` | `ContainerRedstoneData.java` | 中继器断电检测无法区分"还没算"和"真的没信号" | 引入上一帧信号缓存，Phase 0 用上一帧判断断电，Phase 2 算新信号 |

### 暂不提取（等更多数据点）

| 候选原语 | 当前重复数 | 暂不提取的原因 |
|---------|-----------|---------------|
| `tickContainerData` 去重 | 7 份 | 49 行重复不值得修改框架调度逻辑 |
| `RedstoneComponent` 接口 | 每 Phase 2-4 个 for 循环 | 当前无新增组件需求，接口设计风险高，先用私有方法分割 |
| `GridSimulator` 通用传播引擎 | 2 个域（活水、活红石） | 语义不同（水流 level vs 信号 strength），等第 3 个域出现再提取 |
| `ContainerData<T>` 通用模式 | 3 个域 | 涉及 TickContext 修改，影响面大，等更多 ContainerData 出现 |

### 关键经验

1. **从重复中提取，不从想象中设计**：`opposite()` 是 3 份重复代码逼出来的，`prevSignalStrength` 是时序 bug 逼出来的
2. **轻量优于重量**：不创建 `RedstoneComponent` 接口，而是用 5 个私有方法。零风险，且为未来接口化提供原型
3. **框架层 vs 领域层**：`Pos2D.opposite()` 是框架层原语（通用），Phase 方法是领域层原语（仅红石域）。分清楚这两层是关键