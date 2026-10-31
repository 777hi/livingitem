# 容器形态 × 数据生命周期（参考）

> **性质**：参考层索引 —— 回答「六种容器形态下，四张表什么时候建、什么时候清、断了会怎样」。
> 语义真相仍在各 tech / decisions；本页只做横切视图 + 指针。
> **最后核对**：2026-11-01（`doc_check` 第 15 项校验 `*ContainerContext` 实现类全覆盖）。
> **关联**：[container-overview.md](../system-design/container-overview.md) §0 #9 ·
> [living-item-infrastructure.md](../system-design/living-item-infrastructure.md) §3.2 / §3.5 ·
> 整改计划 R-06 · `D-container-20/21`。

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
> 所有依赖它的清理都会失效。

| 形态 | 身份键 | tick 宿主 / 停止条件 | 数据宿主 / 持久化 | `CONTAINER_DATA` + `POS_TO_CACHE_KEY` | `LIVING_SLOT_ITEMS` / `ContainerRuntimeCache` | 显式清理 | 停止 tick 后的现状 |
|---|---|---|---|---|---|---|---|
| **方块容器**（`SimpleContainerContext`） | `chest_x_y_z`（双箱两段坐标） | 区块缓存发现 + `isPositionTicking`；区块卸载/不 tick 即停止 | 关联 BE 的 attachment（双箱两半都写） | 创建：`entry(ctx)`；清理：`removeDataByKey` / `cleanupStaleData`（120s）/ 停服 | 创建：`trackLivingSlots` / `update`；清理：空 tick / 玩家破坏（索引命中）/ 停服 | `BlockEvent.BreakEvent` → `removeDataByPos` | **不清**（R-06 主目标） |
| **玩家背包**（`SimpleContainerContext`） | `player_<uuid>` | 玩家在线，每 tick | **无数据宿主**（`getDataHosts()` 为空；当前不支持 attachment） | 创建：`entry(ctx)`；清理：TTL / 停服（无位置索引） | 空 tick 会清；下线后**不清** | 无（没有方块可破坏） | **不清**（R-06 sweep） |
| **末影箱**（`EnderChestContainerContext`） | `player_<uuid>_ender_chest` | 玩家在线，每 tick | **无数据宿主**（同上） | 同上 | 空 tick 会清；下线后**不清** | 无 | **不清**（R-06 sweep） |
| **实体容器**（`EntityContainerContext`） | `entity_<uuid>` | 实体已加载，每 tick；实体卸载/死亡/移除即停止 | 实体 attachment（`getDataHosts()` 覆写为实体） | 创建：`entry(ctx)`；清理：TTL / 停服 | 空 tick 会清；实体消失后**不清** | 无 | **不清**（R-06 sweep） |
| **物品形态**（`ItemFormContainerContext`） | `cuuid_<uuid>` | 物品在可 tick 的宿主里；物品被销毁/丢失即停止 | 无宿主；持久化走 `ContainerDataSavedData`（按容器 UUID，`savedWith`） | 创建：`entry(ctx)`；清理：TTL / 停服 | 空 tick 会清；**取消活化**会清；物品销毁/丢失后**不清** | `ContainerUuids.discard` → `removeDataByKey` | **不清**（R-06 sweep） |
| **掉落物**（`ItemEntityContainerContext`） | `item_entity_<uuid>`（**每次掉落新键**） | `ItemEntity` 已加载且功能声明 `supportsDroppedItem`（当前活工具 / 活 TNT）；拾取/消失即停止 | 无 | 创建：`entry(ctx)`；清理：TTL / 停服 | `trackLivingSlots` 会建；拾取/消失后**不清** | 无 | **不清**（R-06 sweep；运行时片段当前不写） |

---

## 3. 触发 → 影响

| 触发 | 线程 | 对四张表的影响 |
|---|---|---|
| `ChunkEvent.Load` / 方块放置 / 玩家破坏 | 主线程 | 登记 pending rescan；破坏还会 `removeDataByPos` |
| `ChunkEvent.Unload` | **主线程**（`ChunkMap.tick` drain `unloadQueue`；见 `D-container-22`） | 撤销 pending rescan；清 `EnderChannelRegistry` 路由；**不清**四张表 |
| 容器继续 tick 且已无活物品 | 主线程 | 删 `LIVING_SLOT_ITEMS[cacheKey]`；作废该容器运行时槽位 |
| 槽位活物品类型变化 | 主线程 | 作废该槽位运行时数据（`removeSlot`） |
| 玩家破坏方块（位置索引命中） | 主线程 | `removeDataByKey`：四张表 + 整容器运行时作废 |
| 物品形态取消活化 | 主线程 | `removeDataByKey`（同上） |
| 玩家下线 / 实体消失 / 物品销毁 / 掉落物拾取 / 爆炸 / 活塞 / `/setblock` | — | **四张表都不清**（R-06 缺口） |
| 停服 / 切存档 | 主线程 | 各缓存类 `StaticCacheRegistry.onServerStop` → 全清 |

---

## 4. 当前缺口与 R-06 设计

**缺口**（详见整改计划 §6.1）：

1. `POS_TO_CACHE_KEY` 只在 `entry(ctx)` 时建立 ⇒ 只装活物品、没有红石/流体/电力的容器没有索引，
   **玩家破坏也清不掉**；这是“同类物品原地交换继承进度”的放大版。
2. `LIVING_SLOT_ITEMS` / `ContainerRuntimeCache` 没有 TTL / 卸载路径；停止 tick 后残留到停服。
3. `ContainerRuntimeCache.removeSlot` 删空内层槽位后不删外层键 ⇒ 空壳残留。
4. `ItemFormLiveViews#VIEWS` 只在**有人 `find()` 且发现过期**时才删（惰性过期，见
   `ItemFormLiveViews#find`）⇒ 背包停止 tick **且再无人按该 UUID 取货**时条目常驻到停服，
   并**强引用第三方 `IItemHandler`**。同类「惰性回收」缺陷，可并入 R-06 的 sweep。

> ✅ **原缺口 4「Unload 处理器跨线程改表」已排除**（2026-11-01，`D-container-21` → `D-container-22`）：
> `ChunkEvent.Unload` 实际在**主线程**触发，现有两处直接改表**不是竞态**，无需改成入队。
> 该处仍受「卸载中不查世界」约束（再入风险），见 [event-threading.md](event-threading.md) §2。

**设计（待实现，见整改计划 R-06）**：

- `lastSeen` + 周期 sweep（tick 线程）覆盖所有形态的“停止 tick 后”回收（含 `ItemFormLiveViews`）；
- `trackLivingSlots` 补 `indexPositions`；
- `removeSlot` 删空外层键；
- 运行时数据**不依赖**卸载事件（sweep 覆盖），卸载处理器维持现状即可；
- 双箱任一半/未被看到即清整个 key（数据可重建）。

---

## 5. 验证入口

- `doc_check` 第 15 项：扫描 `src/main/java/com/qiqi/li/living/container/*ContainerContext.java`
  的具体实现类，本页必须覆盖每一个（当前：`SimpleContainerContext` / `EnderChestContainerContext` /
  `EntityContainerContext` / `ItemFormContainerContext` / `ItemEntityContainerContext`）。
- 现有单测：`ContainerRuntimeCacheLifecycleTest`、`ContainerChunkCacheChunkLoadTest`、
  `EnderDualFormRouteTest`、`ItemFormLiveViewsTest`、`ContainerTickIsolationTest`。
- R-06 将补：lastSeen sweep、位置索引、空壳、双箱、异步队列。
