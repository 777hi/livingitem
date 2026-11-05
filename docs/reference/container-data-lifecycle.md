# 容器形态 × 数据生命周期（参考）

> **性质**：横切参考索引与统一回收契约；形态矩阵是快照，以 `ContainerIdentity`、各 ctx、
> `ContainerLivingItemHandler` 与验证入口为准。**2026-11-05 统一修复已拍板**，验证状态见 §5。
> **为什么这一张值得存在**：它的答案**不在任何单个文件里** —— 要同时看身份键、数据宿主、
> `trackLivingSlots` 与 `removeDataBy*` 的调用条件；口径见 [docs/README.md](../README.md)。
> ⚠️ `doc_check` 第 15 项只校验形态名出现、**不校验单元格内容** ⇒ 引用清理结论按判据回读源码。
> **关联**：[container-overview.md](../system-design/container-overview.md) §0 #9 ·
> [living-item-infrastructure.md](../system-design/living-item-infrastructure.md) §3.2 / §3.5 ·
> `D-container-20`（瞬态可丢）· `D-container-22`（卸载在主线程）· `D-container-23`（统一回收）。

---

## 1. 四张表为什么存在

| 表 | 键 | 为什么不换一种存法 | 谁写 | 谁读 |
|---|---|---|---|---|
| `ContainerLivingItemHandler#CONTAINER_DATA` | `cacheKey` | 容器级数据不属于物品；跨 tick 聚合，还承载修订计数 / 内容签名 / 快照，不要求 store 有子数据 | 领域读写、`entry(ctx)`、修订与快照入口 | 容器 tick、领域查询、mixin 热路径 |
| `ContainerLivingItemHandler#POS_TO_CACHE_KEY` | `(dim, pos)` | 方块被破坏时只有坐标，需要 O(1) 反查 cacheKey；mixin 热路径也用它 | `indexPositions(ctx, key)` | `removeDataByPos`、`peekContainerDataByPos` |
| `ContainerLivingItemHandler#LIVING_SLOT_ITEMS` | `cacheKey` | 运行数据键里没有物品身份；要判断「槽位里的机器换没换」只能记住上一 tick 的物品类型 | `trackLivingSlots` | `trackLivingSlots` 自己（下一 tick 对比） |
| `ContainerRuntimeCache#store` | `cacheKey` + 物理槽 | 进度/冷却每 tick 变，不能放 DataComponent（破坏堆叠 + 每 tick 组件同步）；也不能放上下文（每 tick 新建） | 运行状态所属领域 | 同域 tick、`LivingItemRuntimeSync`（tooltip） |

**共同生命周期结论（`D-container-20`）**：后两张表是**瞬态、可重建、可随时丢弃**的；需要跨卸载/重启
保留的数据**不得**放这里，必须走 DataComponent 或带持久化声明的 `ContainerDataKey` / 宿主存储。
`ContainerDataKey.persistent` 只表示**跨 tick 内存态**，不等于落盘（例如海绵能力通告 `EDGE`）。

---

## 2. 统一回收契约与六种形态

**唯一自动回收判据 = 缓存键未再被 tick 看到满阈值**，不是数据类型有无心跳，也不是 store 是否为空。

1. **新建登记与 tick 续期分开**：`entry(ctx)` **仅在新建聚合条目时**用
   `LAST_SEEN.putIfAbsent(key, nowMs)` 登记墙钟时间；不得覆盖该键已有的 tick 盖章。
   即使条目只因查询、修订计数或快照而建、从未进入 tick，也必须进入 sweep 的候选集合。
2. **正常续期只在 `trackLivingSlots`**：每个进入 `processContext` 的容器每 tick 刷新 `LAST_SEEN`，
   在有无活物品的判定 / 早退之前完成。**已无活物品 ≠ 容器消失**。
3. **查询不续期**：已有条目的 peek / 查询不得刷新时间；新建登记不是查询续期。
   对从未 tick 的条目反复查询、写修订计数或取快照，不能把它永久保活。
4. **回收入口统一**：`sweepUnseenContainers` 由 L4 每 tick 调、内部 **1 分钟**节拍，
   `nowMs - lastSeen >= 5 分钟` 时走 `removeDataByKey`。
   该入口删除聚合条目、全部关联位置索引、槽位追踪、**`LAST_SEEN` 自身**，并登记整容器运行时作废；
   显式破坏 / 取消活化与 sweep 共用它，不依赖调用方补删时间记录。
5. **活跃的空 store 保留索引与聚合条目**：删掉某个 `ContainerDataKey` 的值不等于删容器；
   只装活物品、只有修订 / 快照的容器仍须能按位置清理。
6. **不再有第二套数据 TTL**：旧 `cleanupStaleData` / `cleanupStalePosIndex` 与全局 1200 次处理计数器退役；
   旧 `ContainerDataLifecycle` 与水 / 红石 / 电力仅为它服务的墙钟 `lastTickTime` 一并删除。
   **游戏时钟不受影响**：流体节拍、红石延迟、红电相位与领域内部计时仍按各自契约运行。

判据是「**没再被看到**」这个共同结果，不是逐形态挂六种事件 —— 后者要六条路径且必漏其一。
「tick 停止条件」= 不再进入 `processContext` 的原因；停止后不能指望一次“空容器 tick”来清理。
下表的「统一回收」均指上面的契约，不另设形态专属 TTL。

| 形态 | 身份键 | tick 宿主 / 停止条件 | 数据宿主 / 持久化 | 聚合条目 / 位置索引 | 槽位追踪 / 运行时 | 显式清理 | 停止 tick 后 |
|---|---|---|---|---|---|---|---|
| **方块容器**（`SimpleContainerContext`） | `chest_x_y_z`（双箱两段坐标） | 区块缓存发现 + `isPositionTicking`；区块卸载/不 tick 即停止 | 有 UUID 且 key 声明 saved → SavedData；否则按 key 声明写关联 BE attachment（双箱两半都写） | `entry(ctx)` 建；追踪首次出现也补位置索引；统一回收 / 停服 | 空 tick 清槽位状态，不删活跃聚合条目与索引 | `BlockEvent.BreakEvent` → `removeDataByPos` | sweep 回收 |
| **玩家背包**（`SimpleContainerContext`） | `player_<uuid>` | 玩家在线，每 tick | 通用 `getDataHosts()` 为空；流体域另按容器键写 Player attachment（`ContainerFluidWriteback`） | `entry(ctx)` 建，无位置索引；统一回收 / 停服 | 空 tick 清槽位状态；下线后靠 sweep | 无（没有方块可破坏） | sweep 回收 |
| **末影箱**（`EnderChestContainerContext`） | `player_<uuid>_ender_chest` | 玩家在线，每 tick | 同玩家背包，流体 attachment 以末影箱键区分 | 同玩家背包 | 空 tick 清槽位状态；下线后靠 sweep | 无 | sweep 回收 |
| **实体容器**（`EntityContainerContext`） | `entity_<uuid>` | 实体已加载，每 tick；实体卸载/死亡/移除即停止 | 实体 attachment（`getDataHosts()` 覆写为实体） | `entry(ctx)` 建；上下文提供实体位置快照，现行索引入口会登记，非稳定方块位置；统一回收 / 停服 | 空 tick 清槽位状态；实体消失后靠 sweep | 无 | sweep 回收 |
| **物品形态**（`ItemFormContainerContext`） | `cuuid_<uuid>` | 物品在可 tick 的宿主里；物品被销毁/丢失即停止 | 无宿主；持久化走 `ContainerDataSavedData`（按容器 UUID，`savedWith`）；活视图另存 `ItemFormLiveViews` | `entry(ctx)` 建，无位置索引；统一回收 / 停服 | 空 tick 清槽位状态；取消活化 / sweep 作废整容器 | `ContainerUuids.discard` → `removeDataByKey` | sweep 回收（活视图同节拍主动淘汰） |
| **掉落物**（`ItemEntityContainerContext`） | `item_entity_<uuid>`（**每次掉落新键**） | `ItemEntity` 已加载且功能声明 `supportsDroppedItem`（当前活工具 / 活 TNT）；拾取/消失即停止 | 无 | 查询 / 修订可由 `entry(ctx)` 建；统一回收 / 停服 | `trackLivingSlots` 会建；运行时片段当前不写 | 无 | sweep 回收 |

---

## 3. 触发 → 影响

> **线程只说一句**：下列触发**全部在服务器主线程**（含 `ChunkEvent.Unload`，判据见
> [event-threading.md](event-threading.md) §2）。⇒ 四张表的清理**不存在并发问题**，
> 不需要为它们设计锁或入队；运行时作废队列是分层接线，不是线程补丁。

| 触发 | 对四张表与时间记录的影响 |
|---|---|
| `ChunkEvent.Load` / 方块放置 / 玩家破坏 | 登记 pending rescan；破坏还会 `removeDataByPos` |
| `ChunkEvent.Unload` | 撤销 pending rescan；清 `EnderChannelRegistry` 路由；**不立即清**四张表，停止 tick 后由 sweep 兜底 |
| 容器继续 tick 且已无活物品 | 继续盖章；删 `LIVING_SLOT_ITEMS[cacheKey]`、作废运行时槽位；保留聚合条目 / 位置索引 |
| 槽位活物品类型变化 | 作废该槽位运行时数据（`removeSlot`），不是删除容器 |
| 玩家破坏方块（位置索引命中） / 物品形态取消活化 | `removeDataByKey`：聚合条目 + 索引 + 追踪 + 时间记录；登记整容器运行时作废 |
| 容器停止被看到（下线 / 消失 / 销毁 / 拾取 / 非玩家移除），或条目从未进入 tick | 满阈值后由 sweep 走同一删除入口；查询不能延后它 |
| `sweepUnseenContainers` | 按 §2 回收过期键；顺带 `ItemFormLiveViews.evictExpired` |
| 停服 / 切存档 | 各缓存类 `StaticCacheRegistry.onServerStop` → 全清 |

---

## 4. 缺口与回收留痕

**2026-11-01 R-06** 补上了槽位追踪 / 运行时 / 活视图停止 tick 后的回收，但旧类型心跳清理仍并存。
**2026-11-06 统一修复**收口到 §2；这不是新增生命周期或改玩法，而是让已有 `D-container-23` 对全部聚合条目成立。

| 缺口 | 收口约束 | 代价 / 边界 |
|---|---|---|
| 旧清理只认流体 / 红石 / 电力类型心跳，Boolean `EDGE` 等条目会在活跃期间被整条删除 | 唯一判据是容器 lastSeen；不按子数据类型决定寿命 | 海绵能力通告丢失的外部后果见 [living-sponge-tech.md](../tech/living-sponge-tech.md) §12.1 |
| 查询 / 修订 / 快照可新建条目却从未 tick，原 sweep 看不到它们 | 新建登记一次，已有条目的查询不续期 | 重新查询已回收的容器可重建条目，仍有有限寿命 |
| 空 store 的活跃容器位置索引被旧清理删除，破坏反查打空 | 空 store 不是消失；只在整键删除时清索引 | 追踪条目首次出现时补索引仍保留；两次「首次出现」之间位置不刷新，搬移双箱可能留旧索引，非本轮新增风险 |
| 显式整键删除漏掉时间记录，sweep 之后重复作废旧键 | `removeDataByKey` 自己删除 `LAST_SEEN` | 不把补删责任留给 sweep 调用方 |
| 运行时内层删空后外层键残留；活视图仅查询时惰性过期 | R-06 已补：空内层连外层一起删；活视图同节拍主动淘汰 | 走远超过回收窗口后进度 / 冷却可归零（`D-container-20`） |
| 双箱任一半消失 | 双箱本来就是一个缓存键；显式删除 / sweep 天然按整键收 | 无需「保留另一半」的新路径 |

> **原缺口「Unload 处理器跨线程改表」不成立**（`D-container-21` → `D-container-22`）：
> `ChunkEvent.Unload` 在主线程触发，「事件只入队」改造项作废；仍受卸载中不查世界的再入约束。

**不自动回收的（口径，不是遗漏）**：物品形态 `ContainerDataSavedData` 的孤儿磁盘条目
不因内存 sweep 删除（判错即误删玩家数据，停服日志计数），见 [living-backpack-tech.md](../tech/living-backpack-tech.md)。
`ContainerChunkCache` 的已发现区块自有 `cleanupStaleEntries`（6000 tick），不是本页的缓存寿命。

---

## 5. 验证入口与状态

- `doc_check` 第 15 项：具体 `*ContainerContext` 必须被本页矩阵覆盖；它不验证生命周期语义。
- `ContainerCacheSweepTest` / `ContainerRuntimeCacheLifecycleTest`：容器续期、过期整键回收、
  显式删除、位置反查与运行时作废；本轮还须守住**活跃 Boolean / 空 store 不丢、查询 / 修订独建条目能回收、
  反复查询不续期、显式删除不留时间记录**。这是回归判据，不是本轮测试通过声明。
- `ItemFormLiveViewsTest.evictExpired_removesStaleEntriesWithoutAnyQuery`：活视图主动淘汰。
- **Create 真机验收尚未完成**：用户已确认加活涂蜡铜灯的对照可正常抽取；
  修复后**不放灯泡**的长期充放与重连仍待验，见 [living-sponge-tech.md](../tech/living-sponge-tech.md) §12.1。
