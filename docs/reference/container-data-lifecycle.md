# 容器形态 × 数据生命周期（参考）

> **性质**：参考层索引 —— 回答「六种容器形态下，四张表什么时候建、什么时候清、断了会怎样」。
> **为什么这一张值得存在**：它的答案**不在任何单个文件里** —— 要同时看 `ContainerIdentity` 的键形、
> 五个 ctx 的宿主与覆写、`trackLivingSlots` 与 `removeDataBy*` 的调用条件（跨 6 个类 ≈2000 行；
> 2026-11-01 A/B 实验实测：禁读文档组为这一问题读了 16 文件 / ≈2100 行）。
> 它做的是**任何一处都读不到的横切**，不是复述（口径见
> [docs/README.md](../README.md)「该不该写：一条判据」）。
> ⚠️ `doc_check` 第 15 项只校验形态名出现、**不校验单元格内容** ⇒ 引用清理结论按判据回读源码。
> **关联**：[container-overview.md](../system-design/container-overview.md) §0 #9 ·
> [living-item-infrastructure.md](../system-design/living-item-infrastructure.md) §3.2 / §3.5 ·
> 整改计划 R-06 · `D-container-20`（瞬态可丢）· `D-container-22`（卸载在主线程）。

---

## 1. 四张表为什么存在

| 表 | 键 | 为什么不换一种存法 | 谁写 | 谁读 |
|---|---|---|---|---|
| `ContainerLivingItemHandler#CONTAINER_DATA` | `cacheKey` | 容器级数据（流体/红石/电力）不属于物品，不能放 ItemStack；跨 tick、按容器聚合 | 红石/流体/电力域、`entry(ctx)` | 红石/流体/电力 tick、mixin 热路径 |
| `ContainerLivingItemHandler#POS_TO_CACHE_KEY` | `(dim, pos)` | 方块被破坏时只有坐标，需要 O(1) 反查 cacheKey；mixin 热路径也用它 | `indexPositions(ctx, key)` | `removeDataByPos`、`peekContainerDataByPos` |
| `ContainerLivingItemHandler#LIVING_SLOT_ITEMS` | `cacheKey` | 运行数据键里没有物品身份；要判断「槽位里的机器换没换」只能记住上一 tick 的物品类型 | `trackLivingSlots` | `trackLivingSlots` 自己（下一 tick 对比） |
| `ContainerRuntimeCache#store` | `cacheKey` + 物理槽 | 进度/冷却每 tick 变，不能放 DataComponent（破坏堆叠 + 每 tick 组件同步）；也不能放上下文（每 tick 新建） | 熔炉/漏斗/酿造/信标/发电 | 同域 tick、`LivingItemRuntimeSync`（tooltip） |

**共同生命周期结论（`D-container-20`）**：后两张表是**瞬态、可重建、可随时丢弃**的；需要跨卸载/重启
保留的数据**不得**放这里，必须走 `ContainerDataKey` / DataComponent / 宿主附件。

---

## 2. 六种形态 × 生命周期

> 「tick 停止条件」= 这个形态不再进入 `processContext` 的原因；停止后没有“空容器 tick”，
> 所有依赖它的清理都会失效 —— 这一列就是 R-06 的成因，靠最后一列的 **lastSeen sweep** 兜住。
>
> **lastSeen 盖章点** = `trackLivingSlots`（每个进入 `processContext` 的容器每 tick 盖一次，
> 含已无活物品的容器）；**回收口** = `ContainerLivingItemHandler.sweepUnseenContainers()`
> 由 L4 每 tick 调、内部 1 分钟节拍，消失满 5 分钟即走 `removeDataByKey`（与「玩家破坏」同一条入口）。
> 判据是「**没再被看到**」这个共同结果，不是逐形态挂六种事件 —— 后者要六条路径且必漏其一。

| 形态 | 身份键 | tick 宿主 / 停止条件 | 数据宿主 / 持久化 | `CONTAINER_DATA` + `POS_TO_CACHE_KEY` | `LIVING_SLOT_ITEMS` / `ContainerRuntimeCache` | 显式清理 | 停止 tick 后 |
|---|---|---|---|---|---|---|---|
| **方块容器**（`SimpleContainerContext`） | `chest_x_y_z`（双箱两段坐标） | 区块缓存发现 + `isPositionTicking`；区块卸载/不 tick 即停止 | 关联 BE 的 attachment（双箱两半都写） | 创建：`entry(ctx)`；清理：`removeDataByKey` / `cleanupStaleData`（120s）/ 停服 | 创建：`trackLivingSlots` / `update`；清理：空 tick / 玩家破坏（索引命中）/ 停服 | `BlockEvent.BreakEvent` → `removeDataByPos` | sweep 回收（2026-11-01） |
| **玩家背包**（`SimpleContainerContext`） | `player_<uuid>` | 玩家在线，每 tick | **无数据宿主**（`getDataHosts()` 为空；当前不支持 attachment） | 创建：`entry(ctx)`；清理：TTL / 停服（无位置索引） | 空 tick 会清；下线后靠 sweep | 无（没有方块可破坏） | sweep 回收 |
| **末影箱**（`EnderChestContainerContext`） | `player_<uuid>_ender_chest` | 玩家在线，每 tick | **无数据宿主**（同上） | 同上 | 空 tick 会清；下线后靠 sweep | 无 | sweep 回收 |
| **实体容器**（`EntityContainerContext`） | `entity_<uuid>` | 实体已加载，每 tick；实体卸载/死亡/移除即停止 | 实体 attachment（`getDataHosts()` 覆写为实体） | 创建：`entry(ctx)`；清理：TTL / 停服 | 空 tick 会清；实体消失后靠 sweep | 无 | sweep 回收 |
| **物品形态**（`ItemFormContainerContext`） | `cuuid_<uuid>` | 物品在可 tick 的宿主里；物品被销毁/丢失即停止 | 无宿主；持久化走 `ContainerDataSavedData`（按容器 UUID，`savedWith`）；活视图另存 `ItemFormLiveViews` | 创建：`entry(ctx)`；清理：TTL / 停服 | 空 tick 会清；**取消活化**会清；物品销毁/丢失后靠 sweep | `ContainerUuids.discard` → `removeDataByKey` | sweep 回收（`ItemFormLiveViews` 同节拍主动淘汰） |
| **掉落物**（`ItemEntityContainerContext`） | `item_entity_<uuid>`（**每次掉落新键**） | `ItemEntity` 已加载且功能声明 `supportsDroppedItem`（当前活工具 / 活 TNT）；拾取/消失即停止 | 无 | 创建：`entry(ctx)`；清理：TTL / 停服 | `trackLivingSlots` 会建；拾取/消失后靠 sweep | 无 | sweep 回收（运行时片段当前不写） |

---

## 3. 触发 → 影响

> **线程只说一句**：下列触发**全部在服务器主线程**（含 `ChunkEvent.Unload`，判据见
> [event-threading.md](event-threading.md) §2）。⇒ 四张表的清理**不存在并发问题**，
> 不需要为它们设计锁或入队 —— 这条否定性结论比逐行标「主线程」有用。

| 触发 | 对四张表的影响 |
|---|---|
| `ChunkEvent.Load` / 方块放置 / 玩家破坏 | 登记 pending rescan；破坏还会 `removeDataByPos` |
| `ChunkEvent.Unload` | 撤销 pending rescan；清 `EnderChannelRegistry` 路由；**不清**四张表 |
| 容器继续 tick 且已无活物品 | 删 `LIVING_SLOT_ITEMS[cacheKey]`；作废该容器运行时槽位 |
| 槽位活物品类型变化 | 作废该槽位运行时数据（`removeSlot`） |
| 玩家破坏方块（位置索引命中） | `removeDataByKey`：四张表 + 整容器运行时作废 |
| 物品形态取消活化 | `removeDataByKey`（同上） |
| 容器停止被看到（玩家下线 / 实体消失 / 物品销毁 / 掉落物拾取 / 爆炸 / 活塞 / `/setblock`） | 满 5 分钟后由 **sweep** 回收（2026-11-01；此前是「四张表都不清」的 R-06 缺口） |
| `sweepUnseenContainers`（L4 每 tick 调，内部 1 分钟节拍） | `removeDataByKey` 过期键：四张表 + 整容器运行时作废；顺带 `ItemFormLiveViews.evictExpired` |
| 停服 / 切存档 | 各缓存类 `StaticCacheRegistry.onServerStop` → 全清 |

---

## 4. 缺口与回收（2026-11-01 已实现 R-06）

**原缺口 —— 现已闭合**（判据 = `ContainerCacheSweepTest` + `ContainerRuntimeCacheLifecycleTest`）：

| 缺口 | 闭合方式 | 残留代价 |
|---|---|---|
| 1. `POS_TO_CACHE_KEY` 只在 `entry(ctx)` 建 ⇒ 只装活物品的容器**玩家破坏也清不掉** | `trackLivingSlots` 在追踪条目**首次出现**时补 `indexPositions(ctx, key)`（稳态零成本；区块重载 / 清空再装入会重建） | 位置在两次「首次出现」之间不刷新 ⇒ 被活塞搬走的双箱可能留旧索引（与修复前同源，未新增风险） |
| 2. `LIVING_SLOT_ITEMS` / `ContainerRuntimeCache` 无 TTL / 无卸载路径，停止 tick 后残留到停服 | `LAST_SEEN` 盖章 + `sweepUnseenContainers()`（5 分钟阈值 / 1 分钟节拍）走 `removeDataByKey` 同一条作废入口 | 走远超过 5 分钟 ⇒ 熔炉进度 / 冷却归零（按 `D-container-20` 口径可接受） |
| 3. `removeSlot` 删空内层后外层键残留 | 内层空 ⇒ 连外层键一起删 | 无 |
| 4. `ItemFormLiveViews#VIEWS` 只在 `find()` 里惰性过期 ⇒ 无人查询就常驻（持第三方 handler 强引用） | 并入同一节拍 `evictExpired(nowMs)` 主动淘汰 | 无 |
| 5. 双箱：任一半消失 | **无需代码** —— 双箱本来就是一个缓存键（两段坐标 → 同一 `cacheKey`），sweep / `removeDataByKey` 天然按整键收 | 无（原计划里的「保留另一半」逻辑确认不需要） |

> ✅ **原缺口 6「Unload 处理器跨线程改表」不成立**（`D-container-21` → `D-container-22`）：
> `ChunkEvent.Unload` 在**主线程**触发，两处直接改表不是竞态，「事件只入队」改造项作废。
> 该处仍受「卸载中不查世界」约束（再入风险），见 [event-threading.md](event-threading.md) §2。

**仍然没有回收的（口径，不是遗漏）**：

- **物品形态的磁盘条目**：`ContainerDataSavedData` 里被销毁物品留下的孤儿条目**不自动回收**
  （判错即误删玩家数据；由停服日志计数）。见 `living-backpack-tech.md`。
- **`ContainerChunkCache` 的已发现区块**：自有 `cleanupStaleEntries`（6000 tick）兜底，与本表不同节奏。

**为什么运行时数据可以被激进地丢**：见 `D-container-20` —— 本表按定义是瞬态、可重建的；
需要跨卸载 / 重启保留的数据不该放这里，应走 `ContainerDataKey` / DataComponent / 宿主附件。

---

## 5. 验证入口

- `doc_check` 第 15 项：扫描 `src/main/java/com/qiqi/li/living/container/*ContainerContext.java`
  的具体实现类，本页必须覆盖每一个（当前：`SimpleContainerContext` / `EnderChestContainerContext` /
  `EntityContainerContext` / `ItemFormContainerContext` / `ItemEntityContainerContext`）。
- 现有单测：`ContainerRuntimeCacheLifecycleTest`、`ContainerChunkCacheChunkLoadTest`、
  `EnderDualFormRouteTest`、`ItemFormLiveViewsTest`、`ContainerTickIsolationTest`。
- R-06（2026-11-01 已实现）：`ContainerCacheSweepTest`（sweep 过期 / 阈值内保留 / 每 tick 盖章 /
  位置索引使破坏即清 / 双箱任一半 / 无 ctx 也盖章 / `removeSlot` 空壳）+
  `ItemFormLiveViewsTest.evictExpired_removesStaleEntriesWithoutAnyQuery`（主动淘汰）。
