# 服务端共享状态总表（参考）

> **性质**：参考层索引 —— 回答「有哪些跨 tick 的 static 状态、谁写谁读、哪个线程、什么时候清」。
> 语义真相仍在各 tech / decisions；本页只做横切视图 + 指针。
> **最后核对**：2026-11-01（`doc_check` 第 13 项校验静态可变集合全覆盖）。
> 🔴 **门禁的边界（必读）**：第 13 项只校验**行是否存在**（字段名出现在本文），
> **不校验行内容**。所以「线程列 / 清理列」写错**不会变红** —— 2026-11-01 就同时漏过
> 「Unload 是异步线程」与「`ItemFormLiveViews` 每 tick 重建」两处错行（后者实为惰性过期）。
> ⇒ 引用本表结论时，凡涉及**线程**或**清理时机**，仍要按「证据」列去读一次源码。
> **范围**：`living/` 与 `network/` 下**服务端**的跨 tick 可变 static 集合。
> 配置/规则表、启动期注册表、反射缓存、纯客户端缓存、诊断指标不在本表（见 §4）。
> **关联**：[event-threading.md](event-threading.md) ·
> [container-data-lifecycle.md](container-data-lifecycle.md) ·
> [living-item-infrastructure.md](../system-design/living-item-infrastructure.md) §3.5。

---

## 1. 容器 / 运行时核心

| 状态 | 内容 / 键 | 线程 | 建立 | 清理 / 失效 | 持久化 | 备注 |
|---|---|---|---|---|---|---|
| `ContainerLivingItemHandler#CONTAINER_DATA` | `cacheKey` → `ContainerEntry`（store + revision/snapshot） | 主线程 | `entry(ctx)` / `getOrCreateContainerData` | `removeDataByKey` · `cleanupStaleData`（每 1200 次容器处理，120s 未更新） · 停服 | 仅声明了 attachment/codec 的 key（`ContainerDataKey.isSaved`） | TTL 只看 `ContainerDataLifecycle`；见 infra §3.5 |
| `ContainerLivingItemHandler#POS_TO_CACHE_KEY` | `(dim,pos)` → `cacheKey` | 主线程 | `entry(ctx)` 时 `indexPositions` | `removeDataByKey` · `cleanupStalePosIndex` · 停服 | 无 | 只装活物品的容器当前**没有索引**（R-06） |
| `ContainerLivingItemHandler#LIVING_SLOT_ITEMS` | `cacheKey` → `slot` → `Item` | 主线程 | `trackLivingSlots` 每 tick | 容器空 tick · `removeDataByKey` · 停服 | 无 | 瞬态、可丢（`D-container-20`）；R-06 缺口 |
| `ContainerLivingItemHandler#PENDING_RUNTIME_INVALIDATIONS` | 待作废记录（整容器 / 槽位） | 主线程 | `trackLivingSlots` / `removeDataByKey` | L4 `onServerTick` 每 tick drain | 无 | tick 内队列，不跨 tick |
| `ContainerRuntimeCache#store` | `cacheKey` → 物理槽 → `RuntimeSegments` | 主线程 | 领域 `update` | `removeSlot` / `removeContainer`（作废队列） · 停服 | 无 | 瞬态、可丢；`removeSlot` 删空后外层键残留（R-06） |
| `ContainerRuntimeCache#dirtyContainers` | `cacheKey` 集合 | 主线程 | `update` / `removeSlot` | `drainDirty` 每 tick | 无 | 驱动 tooltip 同步 |
| `ContainerChunkCache#INSTANCE` | 维度 → 容器区块集合 / pending 重扫 / 清理计时 | 主线程（含卸载事件，见 `D-container-22`） | 事件 / `maintain` | `cleanupStaleEntries` · `clear()` · 停服 | 无 | 卸载事件里**不得查世界**（区块正卸载 ⇒ 再入）；改表本身安全 |
| `ItemFormLiveViews#VIEWS` | 容器 UUID → 物品形态活视图 | 主线程 | 物品形态 tick `register`（覆盖写） | ⚠️ **只在 `find()` 命中过期时删**（惰性） · 停服 `clear()` | 无 | 停止 tick **且再无人查询** ⇒ 条目常驻到停服（持第三方 `IItemHandler` 强引用）；与 R-06 同一族缺口 |
| `LearnedLayouts#LEARNED` | 容器键 → 列数 | 主线程 | 学习 | `clearForTest`；落盘由 load/save 管 | 是（`config/living_item/…json`） | 跨存档共享的学习结果 |
| `LearnedLayouts#AMBIGUOUS` | 歧义容器键集合 | 主线程 | 学习 | 同上 | 是（同上） | 与 `LEARNED` 同生命周期 |
| `LivingItemManager#APPLICABLE_CACHE` | `Item` → 适用功能列表 | 主线程 | 首次查询 | 无显式清理（按物品类型有界） | 无 | 启动后基本只读 |
| `FailureThrottle#FAILURES` | 失败 key → 计数 / 时间 | 主线程 | `report` | 仅测试 `reset()`；**未登记停服清理** | 无 | ⚠️ 跨存档残留（单机切存档不重启 JVM） |

## 2. 领域运行期同步与节流

| 状态 | 内容 / 键 | 线程 | 建立 | 清理 / 失效 | 持久化 | 备注 |
|---|---|---|---|---|---|---|
| `BeaconChestHost#current` | 本 tick 信标 → 宿主 | 主线程 | 容器 tick | `flush` 取走 | 无 | tick 内 |
| `BeaconChestHost#lidLastTick` | 容器键 → 上次开盖 tick | 主线程 | 容器 tick | `flush` / `clear()` · 停服 | 无 | 开盖节流 |
| `BeaconChestHost#activeLids` | 容器键 → 盖位置 | 主线程 | 容器 tick | `flush` / `clear()` · 停服 | 无 | 关盖检测 |
| `BeaconPlayerHostSync#current` | 玩家 → 宿主 | 主线程 | 容器 tick | `beginTick` / `clear()` · 停服 | 无 | 跨维度铁律：只在 tick 头清 |
| `LivingToolHostSync#current` | 宿主 → 工具 | 主线程 | 容器 tick | `beginTick` 每 tick | 无 | flush 逐维度只读 |
| `LivingToolFakePlayerCache#CACHE` | 维度 + UUID → FakePlayer | 主线程 | 首次使用 | `clearDimension` / `clear()` · 停服 | 无 | 实体替身 |
| `LivingToolRecorder#LAST_RECORD_TICK` | 玩家 UUID → tick | 主线程 | 记录操作 | 下线 / 停服 | 无 | 操作节流 |
| `LivingMapEventHandler#lastSentMetadata` | 玩家 UUID → 上次包 | 主线程 | 地图 tick | 下线 / 停服 | 无 | 去重 |
| `MapUpdateSkipHelper#cooldowns` | 玩家 UUID → 过期 tick | 主线程 | 传送 | 过期后 `shouldSkip` 删除；**未登记停服清理** | 无 | ⚠️ 下线/跨存档残留 |
| `MapUpdateSkipHelper#lastUpdateTick` | 玩家 UUID → 上次更新 tick | 主线程 | 地图 tick | **永不删除；未登记停服清理** | 无 | ⚠️ 跨存档 tick 重置 ⇒ 旧值可能让新存档误跳过更新 |
| `StructureMapDecorator#SCANNED_CHUNKS` | 地图 id → 已扫描区块 | 主线程 | 扫描结构 | `StructureMapDecorator.reset()`（停服事件调用） | 无 | 停服清 |
| `FluidFlowServerSync#CLIENT_ACTIVE` | `cacheKey` 集合 | 主线程 | 同步登记 | `clearRuntimeState()` · 停服 | 无 | 客户端活跃容器 |
| `ContainerMenuInfoRequestPacket#LAST_REQUEST` | 玩家 UUID → 请求时间 | 服务端网络/主线程 | 菜单信息请求 | 下线 / 停服 | 无 | 请求节流 |

## 3. 生命周期基础设施

| 状态 | 内容 / 键 | 线程 | 建立 | 清理 | 持久化 | 备注 |
|---|---|---|---|---|---|---|
| `StaticCacheRegistry#SERVER` | 停服清理回调 | 启动 | 各缓存类 static 块登记 | 停服执行 | 无 | 登记位置必须在缓存类自己文件 |
| `StaticCacheRegistry#CLIENT` | 客户端清理回调 | 客户端启动 | 同上 | 客户端停 / 断开 | 无 | 同上 |

## 4. 不收录（明确排除）

以下类别**不是运行期玩法状态**，不进入本表；`doc_check` 第 13 项用类名/字段级排除：

- **配置 / 规则表**（启动或 `/reload` 装载）：`ActivationRuleConfig`、`InteractionRuleConfig`、
  `InteractionRegistry`、`InteractionPredicates`、`ContainerRuleConfig`、`ContainerCompatibilityConfig`、
  `FluidTransformTable`、`FluidFlowBehaviors`、`CropClassifier`、`BeaconGlassColor`。
- **启动期注册表**：`ContainerDataKey`、`RuntimeSegmentRegistry`、`ContainerSnapshot`、
  `ContainerTickHooks`、`ItemFormContainers`、`ContainerHandoff`、`ContainerLikeItems`、
  `SlotAccessorFactory`、`SlotInteractions`、`MenuSlotOwners`、`LivingItemManager#FUNCTIONS`。
- **反射 / 类型缓存**（按类有界，无玩法生命周期）：`ContainerContexts`、`AvaritiaMenuResolver`、
  `SophisticatedMenuResolver`。
- **客户端缓存 / 渲染状态**：`*ClientCache`、`LivingMapClientCache`、`IrisBeamTagging`、`LivingButtonLayout`、
  `LivingIconRegistry`、`client/render/*`、`LivingItemInputHandler`。
- **诊断 / 调试**：`PerfMetrics`、`ContainerMonitor`、`MenuSlotOwners#LOGGED_MISSES`。
- **常量 / 编解码 / 不可变查找表**：`Map.of` / `List.of` / `Set.of` / `Collections.unmodifiable*` /
  `Codec` / `StreamCodec` / `TypeToken` / `new SlotMapping` 等（扫描器直接跳过）。

## 5. 已知缺口

- `FailureThrottle#FAILURES`、`MapUpdateSkipHelper#cooldowns` / `#lastUpdateTick` 未登记停服清理
  ⇒ 单机退出存档再进另一个存档时，旧世界的失败 key / 玩家 UUID 节流记录仍在（内存小，但属残留）。
  `lastUpdateTick` 更实际的影响是**跨存档 tick 计数重置**：旧存档的 tick 值可能大于新存档当前
  tick，`currentTick - lastTick < 20` 会把新存档的地图更新误跳过，直到新 tick 追上旧值。
- 容器形态特有的缺口见 [container-data-lifecycle.md](container-data-lifecycle.md) §4。

> ✅ **排除记录（2026-11-01）**：本表曾把 `ContainerChunkCache#INSTANCE` 的 `pendingRescans`
> 在 `ChunkEvent.Unload` 中直接删改列为「与主线程 `flushPendingRescans` 竞态」。
> **不成立** —— Unload 在服务器主线程触发（`D-container-22`），无跨线程问题，也不是缺口。
