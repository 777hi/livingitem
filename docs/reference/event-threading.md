# 事件处理器与线程（参考）

> **性质**：参考层索引 —— 回答「每个 `@SubscribeEvent` 在哪个线程、能碰什么、碰了哪些共享状态」。
> 语义真相仍在各 tech / decisions；本页只做横切视图 + 指针。
> **最后核对**：2026-11-01（`doc_check` 第 14 项校验全部 `@SubscribeEvent` 覆盖）。
> **关联**：[shared-state.md](shared-state.md) ·
> [living-item-infrastructure.md](../system-design/living-item-infrastructure.md) §3.1.1 / §3.2 ·
> `D-container-21`。

> ⭐ **两条硬规则**
> 1. **事件里不查世界**：`ChunkEvent.Load` 在区块 FULL 任务内部，查能力会自等自死锁
>    （见 `ContainerChunkCache#onChunkLoad` 红线）。
> 2. **卸载事件是异步的**：`ChunkEvent.Unload` 由原版 `ChunkMap.scheduleUnload` 的
>    `thenRunAsync` 触发，**不在 tick 线程**；处理器只能入队，不得直接改 tick 线程的表。

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

## 2. 异步保存线程（⚠️ 只能入队）

| 处理器 | 事件 | 约束 |
|---|---|---|
| `LivingItem#onChunkUnload` | `ChunkEvent.Unload` | 当前直接清 `EnderChannelRegistry` 路由（**已知竞态**）；R-06 改为只入队 |
| `ContainerChunkCache#onChunkUnload` | `ChunkEvent.Unload` | 当前直接改 `pendingRescans`（**已知竞态**）；R-06 改为只入队 |

> 原版证据：`ChunkMap.scheduleUnload` → `chunkHolder.getSaveSyncFuture().thenRunAsync(...)`
> → `NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(...))`。`thenRunAsync` 走异步池，不在主线程。

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
