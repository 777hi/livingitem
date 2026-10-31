# 事件处理器与线程（参考）

> **性质**：参考层索引 —— 回答「每个 `@SubscribeEvent` 在哪个线程、能碰什么、碰了哪些共享状态」。
> 语义真相仍在各 tech / decisions；本页只做横切视图 + 指针。
> **最后核对**：2026-11-01（`doc_check` 第 14 项校验全部 `@SubscribeEvent` 覆盖）。
> 🔴 **门禁的边界（必读）**：第 14 项只校验**处理器名是否出现在本页**，**不校验线程列**。
> 线程是本页最容易写错、又不会变红的一列 —— `D-container-21` 就是这么错了一天。
> ⇒ 涉及线程的结论，按 §2 的证据格式去 `libs/src/neoforge-21.1.219-merged/` 复核**调用方**，
> 不要只看事件名或 javadoc（NeoForge 的 `ChunkEvent.Unload` javadoc 不提线程，且写错了触发点）。
> **关联**：[shared-state.md](shared-state.md) ·
> [living-item-infrastructure.md](../system-design/living-item-infrastructure.md) §3.1.1 / §3.2 ·
> `D-container-21`（**已作废** —— 该决策的前提是本页 §2 记载的经典误读）。

> ⭐ **三条硬规则**
> 1. **事件里不查世界**：`ChunkEvent.Load` 在区块 FULL 任务内部，查能力会自等自死锁
>    （见 `ContainerChunkCache#onChunkLoad` 红线）。
> 2. **卸载事件不查世界，但线程是主线程**：`ChunkEvent.Unload` 在**服务端主线程**触发
>    （证据见 §2）。它不能查世界/能力的原因是**区块正处于卸载中**（已 `setLoaded(false)`、
>    能力已失效），不是为了线程安全。
>    🔴 **别再写「Unload 是异步线程」** —— 这是 `thenRunAsync` 的第二个参数（**executor**，
>    本例是 `unloadQueue::add`）造成的经典误读，2026-11-01 曾据此立过 `D-container-21`，已作废。
> 3. **卸载事件要判侧**：客户端也有一处 post（`ClientChunkCache#drop`），两侧都在各自主线程，
>    但只有服务端那处能拿到 `ServerLevel` ⇒ 用 `event.getLevel() instanceof ServerLevel` 分流。

---

## 1. 服务端主线程（tick / 玩家事件 / 方块事件）

| 处理器 | 事件 | 触碰的共享状态 / 约束 |
|---|---|---|
| `LivingItem#onServerTick` | `ServerTickEvent.Pre` | 容器处理、运行时作废 drain、同步 flush；持有 tick 线程的写权限 |
| `LivingItem#onContainerOpen` | `PlayerContainerEvent.Open` | 容器打开时的登记；不查世界 |
| `LivingItem#onServerStarting` | `ServerStartingEvent` | 启动装载 |
| `LivingItem#onServerStopped` | `ServerStoppedEvent` | 清静态缓存（登记在 `StaticCacheRegistry`） |
| `LivingItem#onChunkLoad` | `ChunkEvent.Load` | 只登记 pending rescan；**不得查世界/能力** |
| `ContainerChunkCache#onChunkLoad` | `ChunkEvent.Load` | 只写内存队列（`pendingRescans`） |
| `ContainerChunkCache#onBlockPlace` | `BlockEvent.EntityPlaceEvent` | 登记 pending rescan |
| `ContainerChunkCache#onBlockBreak` | `BlockEvent.BreakEvent` | `removeDataByPos`（位置索引）+ pending rescan |
| `LivingItemActivationCommand#onRegisterCommands` | `RegisterCommandsEvent` | 启动期注册 |
| `LivingItemContainerCommand#onRegisterCommands` | `RegisterCommandsEvent` | 启动期注册 |
| `LivingItemInteractionCommand#onRegisterCommands` | `RegisterCommandsEvent` | 启动期注册 |
| `LivingItemTransformCommand#onRegisterCommands` | `RegisterCommandsEvent` | 启动期注册 |
| `ContainerMonitorCommand#onRegisterCommands` | `RegisterCommandsEvent` | 启动期注册 |
| `ItemFrameMapTeleportHandler#onEntityInteractSpecific` | `PlayerInteractEvent.EntityInteractSpecific` | 地图传送；主线程 |
| `LivingMapEventHandler#onServerStopped` | `ServerStoppedEvent` | `StructureMapDecorator.reset()` |
| `LivingMapEventHandler#onPlayerLoggedOut` | `PlayerEvent.PlayerLoggedOutEvent` | 清 `lastSentMetadata` |
| `LivingMapEventHandler#onContainerOpen` | `PlayerContainerEvent.Open` | 地图 GUI 状态 |
| `LivingMapEventHandler#onPlayerTick` | `PlayerTickEvent.Post` | `MapUpdateSkipHelper`、`lastSentMetadata` |
| `LivingMapEventHandler#onRightClickItem` | `PlayerInteractEvent.RightClickItem` | 地图交互 |
| `PhaseDataMigration#onBlockDrops` | `BlockDropsEvent` | 相位数据迁移（掉落） |
| `PhaseDataMigration#onBlockPlace` | `BlockEvent.EntityPlaceEvent` | 相位数据迁移（放置） |
| `LivingToolAssist#onBreakSpeed` | `PlayerEvent.BreakSpeed` | 辅助挖掘速度；读容器/工具状态 |
| `LivingToolAssist#onHarvestCheck` | `PlayerEvent.HarvestCheck` | 同上 |
| `LivingToolAssist#onBlockDrops` | `BlockDropsEvent` | 同上 |
| `LivingToolRecorder#onBlockBreak` | `BlockEvent.BreakEvent` | 记录挖掘记忆 |
| `LivingToolRecorder#onToolModify` | `BlockEvent.BlockToolModificationEvent` | 记录工具交互 |
| `LivingToolRecorder#onRightClickItem` | `PlayerInteractEvent.RightClickItem` | 记录使用 |
| `LivingToolRecorder#onLeftClickBlock` | `PlayerInteractEvent.LeftClickBlock` | 记录挖掘 |
| `LivingToolRecorder#onLivingDamage` | `LivingDamageEvent.Post` | 记录攻击 |
| `LivingToolRecorder#onPlayerLoggedOut` | `PlayerEvent.PlayerLoggedOutEvent` | 清 `LAST_RECORD_TICK` 等 |
| `LivingToolRecorder#onServerStopped` | `ServerStoppedEvent` | 停服清理 |
| `LivingWeaponAssist#onPlayerDamage` | `LivingDamageEvent.Post` | 武器辅助 |
| `LivingWeaponAssist#onIncomingDamage` | `LivingIncomingDamageEvent` | 武器辅助（受伤） |
| `LivingWeaponAssist#onKnockback` | `LivingKnockBackEvent` | 武器辅助（击退） |
| `ContainerDataMigration#onBlockDrops` | `BlockDropsEvent` | 容器级数据迁移（掉落） |
| `ContainerDataMigration#onBlockPlace` | `BlockEvent.EntityPlaceEvent` | 容器级数据迁移（放置） |
| `FluidFlowServerSync#onContainerClose` | `PlayerContainerEvent.Close` | 流体快照停止；主线程 |

## 2. 区块卸载（`ChunkEvent.Unload`）—— ⚠️ 主线程，但正处于卸载过程中

| 处理器 | 事件 | 约束 |
|---|---|---|
| `LivingItem#onChunkUnload` | `ChunkEvent.Unload` | 直接清 `EnderChannelRegistry` 路由。**线程安全**（主线程），无需入队；但不得查世界 |
| `ContainerChunkCache#onChunkUnload` | `ChunkEvent.Unload` | 直接改 `pendingRescans`。**线程安全**（主线程），无需入队；但不得查世界 |

> **原版证据**（`libs/src/neoforge-21.1.219-merged/`，全库仅两处 post）：
>
> - 服务端 `ChunkMap.java:532-562` `scheduleUnload`：
>   `chunkHolder.getSaveSyncFuture().thenRunAsync(body, this.unloadQueue::add)`。
>   **第二个参数是 executor**，`unloadQueue::add` 只是把 body **入队**；
>   body 由 `ChunkMap.tick(hasTimeLeft)` 在 `ChunkMap.java:517-519` 处 `poll()` 后
>   直接 `runnable.run()` 执行，而 `tick` 的唯一调用方是 `ServerChunkCache.java:329`
>   （`unloadQueue` 声明为 `Queue<Runnable>`，`ChunkMap.java:153`）。
>   ⇒ `NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(...))`（`ChunkMap.java:543`）**在主服务器线程**。
> - 客户端 `ClientChunkCache.java:66`，在客户端主线程。
>
> **顺序事实（对写处理器有用）**：post 时区块已 `setLoaded(false)`、NeoForge 已失效其能力
> （`CapabilityHooks`），但 `level.unload(chunk)` 与**方块实体的清空发生在 post 之后**
> （`ChunkMap.java:547-549`）⇒ 卸载事件里 BE 仍在，但查能力可能触发重新加载（**再入**，不是竞态）。

## 3. 客户端（渲染 / 输入线程）

| 处理器 | 事件 | 触碰的共享状态 / 约束 |
|---|---|---|
| `LivingItemClient#onClientSetup` | `FMLClientSetupEvent` | 客户端启动 |
| `LivingItemClient#onRegisterAdditionalModels` | `ModelEvent.RegisterAdditional` | 模型注册 |
| `LivingItemClient#onModifyBakingResult` | `ModelEvent.ModifyBakingResult` | 模型烘焙 |
| `LivingItemClient#onRegisterItemDecorations` | `RegisterItemDecorationsEvent` | 物品装饰注册 |
| `LivingItemClient#onRegisterTooltipComponentFactories` | `RegisterClientTooltipComponentFactoriesEvent` | tooltip 组件注册 |
| `LivingItemClient#onGatherTooltipComponents` | `RenderTooltipEvent.GatherComponents` | tooltip；读客户端缓存 |
| `LivingItemClient#onClientLogin` | `ClientPlayerNetworkEvent.LoggingIn` | 登录清缓存 |
| `LivingItemClient#onRenderLevelStage` | `RenderLevelStageEvent` | 渲染线程；读客户端缓存 |
| `LivingItemInputHandler#onClientLoggingOut` | `ClientPlayerNetworkEvent.LoggingOut` | 清客户端输入状态 |
| `LivingItemInputHandler#onLeftClickEmpty` | `PlayerInteractEvent.LeftClickEmpty` | 客户端输入 |
| `LivingItemInputHandler#onCharTyped` | `ScreenEvent.CharacterTyped.Pre` | 方向输入（字符兜底） |
| `LivingItemInputHandler#onKeyPressed` | `ScreenEvent.KeyPressed.Pre` | 方向输入（主通道） |
| `LivingItemInputHandler#onMouseDragged` | `ScreenEvent.MouseDragged.Pre` | 客户端输入 |
| `LivingItemInputHandler#onScreenClosing` | `ScreenEvent.Closing` | 清理会话 |
| `LivingItemInputHandler#onLevelTick` | `LevelTickEvent.Post` | 客户端 tick |
| `ContainerFluidOverlaySubscriber#onRenderForeground` | `ContainerScreenEvent.Render.Foreground` | 渲染线程 |
| `LivingContainerOverlaySubscriber#onRenderForeground` | `ContainerScreenEvent.Render.Foreground` | 渲染线程 |
| `LivingItemTooltip#onItemTooltip` | `ItemTooltipEvent` | 渲染线程 |
| `FluidClientCacheCleanup#onLoggingOut` | `ClientPlayerNetworkEvent.LoggingOut` | 清客户端流体缓存 |

## 4. 边界

- 本页只覆盖 `@SubscribeEvent`。**网络包处理器**（`RegisterPayloadHandlersEvent` 注册的
  `handle(...)`）走网络线程，不在本表；改包处理逻辑前先看 `network/` 的线程约定。
- 事件线程由**原版/NeoForge 的触发点**决定，不由 `@SubscribeEvent` 本身决定；新增事件处理器时
  先在触发点确认线程，再决定能碰哪些表。
- `ClientPlayerNetworkEvent.*` 在客户端网络线程；`RenderLevelStageEvent` / `ContainerScreenEvent`
  在渲染线程；不要在它们里改服务端表。
