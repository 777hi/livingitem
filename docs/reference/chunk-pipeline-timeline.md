# 原版区块加载时序备忘

> **性质**：原版机制的外部资料快照（查表用）。排查跨原版边界的容器 / 区块问题时，先来这里
> 对齐事实，再回系统设计页 —— 本项目排查过的教训：**读自己的代码推不出这类问题，缺环在原版**。
> 事实以反编译源码核对（`libs/src/neoforge-21.1.219-merged`，可选、不入库）——
> 本文只记**类名 / 方法名**不记行号，MC / NeoForge 升级后按同名方法重核。
> 消费方：[基础设施 §延后重扫队列](../system-design/living-item-infrastructure.md)（2026-11-21 BE 权威门）。

---

## 1. 铁律：区块可见 ≠ 方块实体就绪

`ServerChunkCache.getChunkNow` 返回非 null 只表示区块到了 **FULL**；
此时 `chunk.getBlockEntities()` **可能为空** —— 磁盘加载的 BE 还躺在
`LevelChunk.pendingBlockEntities`（私有，无公开枚举入口）。

批量晋升（pending → 正式表）只发生在
`ChunkMap.prepareTickingChunk` → `LevelChunk.postProcessGeneration()`，
即区块升到 **BLOCK_TICKING** 时，**且这一步没有事件**。

## 2. 磁盘加载区块的完整时序

```
ChunkSerializer.read            BE NBT → ProtoChunk.blockEntityNbts（不建 BE 对象）
ChunkStatusTasks.full           LevelChunk 构造：BE NBT → pendingBlockEntities
  ├─ runPostLoad()              只加载【实体】（postLoadProtoChunk），BE 仍 pending
  ├─ ChunkEvent.Load 发布       ← 此刻 blockEntities 可能是空的
  └─ getChunkNow 开始返回非 null（可见）
…（票位等待）
玩家票升到 ≤32 → prepareTickingChunk
  ├─ postProcessGeneration()    批量晋升全部 pending BE（无事件）
  ├─ startTickingChunk()
  └─ tickingChunkFuture 完成    → isPositionTicking 从此 == true
```

## 3. `isPositionTicking` 的确切语义

`ServerChunkCache.isPositionTicking(pos)` =
`shouldTickBlocksAt(pos)` **且** `tickingChunkFuture` 完成。
后者是 `prepareTickingChunk` 返回的 future 链，`postProcessGeneration` 是链内**最后一步**
⇒ **isPositionTicking == true 可证明 BE 已晋升**。本模组两处消费同一判据：

- `ContainerChunkCache.getProcessableChunks`（处理过滤 —— 原有）；
- 重扫 ready 门（`flushPendingRescans` 对「可见但未 ticking」请求移到队尾不判定 —— 2026-11-21）。

## 4. spawn 区块（1.20.5+）：加载但不 tick

`spawnChunkRadius` 默认 2，spawn 区块保持 **FULL 但不 tick**（`shouldTickBlocksAt` 为假）。
推论：**spawn 区块里的箱子，其中的活物品不会运行** —— 原版行为，不是本模组 bug
（处理过滤与 BE 权威门都不会放行它）。玩家反馈「重生点区块的箱子不动了」先对齐这条，
再考虑其它成因（默认半径只有 2，多数基地其实不在这 5×5 里）。

## 5. 对本模组的接线约束

- 发现（重扫）必须在 BE 权威后判定，否则「无容器」是误判 ⇒ 误踢发现缓存 ⇒ 永不再发现
  （`ChunkEvent.Load` 不保证重发，升 ticking 无事件）—— 事故与修复见
  [基础设施 §延后重扫队列](../system-design/living-item-infrastructure.md)。
- 处理路径经 `getProcessableChunks` 过滤 ⇒ 天然安全，不需要额外门。
- 观测：曾发现的容器区块被移出发现缓存时打 `[容器发现]` WARN
  （`ContainerChunkCache.logContainerChunkEvicted`）—— 合法成因仅「区块内已无容器」。
