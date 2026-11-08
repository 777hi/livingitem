# 事件处理器与线程（参考）

> **性质**：横切视图 —— 回答「有哪些 `@SubscribeEvent` 处理器」（§1 名单，机器生成）和
> 「哪几条线程约束曾经把人带错过」（§0 硬规则 + §2 判据链，手写）。
> **形态（2026-11-01 重划）**：原先是 58 行 ×「处理器 / 事件 / 触碰的共享状态 / 约束」手写表格；
> 后两列是对方法体的复述，会被删 —— 理由与口径见
> [docs/README.md](../README.md)「该不该写：一条判据」。
> 🔴 **本表不回答**：某个处理器具体能不能碰某张表 —— 读方法体，30 秒成本，且不会腐烂。
> **关联**：[shared-state.md](shared-state.md) ·
> [living-item-infrastructure.md](../system-design/living-item-infrastructure.md) §3.1.1 / §3.2。

---

## 0. 三条硬规则（各带判据）

1. **`ChunkEvent.Load` 里不查世界 / 能力。** 该事件在区块 FULL 任务内部触发，
   查能力会等自己正在完成的那个 future ⇒ **自等自死锁**。
   判据：`ContainerChunkCache#onChunkLoad` 的红线注释与「只写内存队列」实现。
2. **`ChunkEvent.Unload` 在服务器主线程 ⇒ 可以直接改 tick 线程的表；但仍然不得查世界 / 能力。**
   不查世界的原因换了：此刻区块已 `setLoaded(false)`、能力已失效，而 `level.unload(chunk)`
   与方块实体清空发生在 post **之后** ⇒ 查询会**再入**触发加载。这与线程无关。
   判据：完整推导链见 §2。
3. **卸载事件要判侧。** 全库只有两处 post `ChunkEvent.Unload`：服务端（§2 那条链）与
   客户端 `ClientChunkCache#drop`，各自都在自己那侧的主线程 ⇒ 用
   `event.getLevel() instanceof ServerLevel` 分流，否则会漏掉客户端。

> 🔴 **别再写「Unload 是异步线程」** —— 这是 `thenRunAsync(action, executor)` 的经典误读：
> 它读起来像「异步执行 action」，实际语义是「把 action **提交给** executor」，
> 而这里的 executor 是 `unloadQueue::add`（一个入队动作）。
> **推广**：任何 `*Async` API，判线程要看**第二个参数是谁、谁在 drain 它**，不是看方法名。

---

## 1. 处理器名单（生成区）

<!-- rosters:begin（本区由 tools/gen_rosters.py 从源码扫描生成；名单变化 = 改代码后重跑本脚本，勿手改本区） -->
| 类（按所在包分桶，**不代表线程**） | `@SubscribeEvent` 处理器（事件类型） |
|---|---|
| ▸ **服务端 / 领域包** | |
| `ContainerChunkCache` | `onBlockBreak`(`BreakEvent`) `onBlockPlace`(`EntityPlaceEvent`) `onChunkLoad`(`Load`) `onChunkUnload`(`Unload`) |
| `ContainerDataMigration` | `onBlockDrops`(`BlockDropsEvent`) `onBlockPlace`(`EntityPlaceEvent`) |
| `ContainerMonitorCommand` | `onRegisterCommands`(`RegisterCommandsEvent`) |
| `FluidFlowServerSync` | `onContainerClose`(`Close`) |
| `ItemFrameMapTeleportHandler` | `onEntityInteractSpecific`(`EntityInteractSpecific`) |
| `LivingItem` | `onChunkLoad`(`Load`) `onChunkUnload`(`Unload`) `onContainerOpen`(`Open`) `onServerStarting`(`ServerStartingEvent`) `onServerStopped`(`ServerStoppedEvent`) `onServerTick`(`Pre`) |
| `LivingItemActivationCommand` | `onRegisterCommands`(`RegisterCommandsEvent`) |
| `LivingItemClient` | `onClientLogin`(`LoggingIn`) `onClientSetup`(`FMLClientSetupEvent`) `onGatherTooltipComponents`(`GatherComponents`) `onModifyBakingResult`(`ModifyBakingResult`) `onRegisterAdditionalModels`(`RegisterAdditional`) `onRegisterItemDecorations`(`RegisterItemDecorationsEvent`) `onRegisterTooltipComponentFactories`(`RegisterClientTooltipComponentFactoriesEvent`) `onRenderLevelStage`(`RenderLevelStageEvent`) |
| `LivingItemContainerCommand` | `onRegisterCommands`(`RegisterCommandsEvent`) |
| `LivingItemInteractionCommand` | `onRegisterCommands`(`RegisterCommandsEvent`) |
| `LivingItemTransformCommand` | `onRegisterCommands`(`RegisterCommandsEvent`) |
| `LivingMapEventHandler` | `onContainerOpen`(`Open`) `onPlayerLoggedOut`(`PlayerLoggedOutEvent`) `onPlayerTick`(`Post`) `onRightClickItem`(`RightClickItem`) `onServerStopped`(`ServerStoppedEvent`) |
| `LivingToolAssist` | `onBlockDrops`(`BlockDropsEvent`) `onBreakSpeed`(`BreakSpeed`) `onHarvestCheck`(`HarvestCheck`) |
| `LivingToolRecorder` | `onBlockBreak`(`BreakEvent`) `onLeftClickBlock`(`LeftClickBlock`) `onLivingDamage`(`Post`) `onPlayerLoggedOut`(`PlayerLoggedOutEvent`) `onRightClickItem`(`RightClickItem`) `onServerStopped`(`ServerStoppedEvent`) `onToolModify`(`BlockToolModificationEvent`) |
| `LivingWeaponAssist` | `onIncomingDamage`(`LivingIncomingDamageEvent`) `onKnockback`(`LivingKnockBackEvent`) `onPlayerDamage`(`Post`) |
| `PhaseDataMigration` | `onBlockDrops`(`BlockDropsEvent`) `onBlockPlace`(`EntityPlaceEvent`) |
| ▸ **客户端包 `com/qiqi/li/client/`（客户端侧，不在服务器 tick 线程）** | |
| `ContainerFluidOverlaySubscriber` | `onRenderForeground`(`Foreground`) |
| `FluidClientCacheCleanup` | `onLoggingOut`(`LoggingOut`) |
| `LivingContainerOverlaySubscriber` | `onRenderForeground`(`Foreground`) |
| `LivingItemInputHandler` | `onCharTyped`(`Pre`) `onClientLoggingOut`(`LoggingOut`) `onKeyPressed`(`Pre`) `onLeftClickEmpty`(`LeftClickEmpty`) `onLevelTick`(`Post`) `onMouseDragged`(`Pre`) `onScreenClosing`(`Closing`) |
| `LivingItemTooltip` | `onItemTooltip`(`ItemTooltipEvent`) |
| `LivingSoulSlabSlotPreview` | `onClientSetup`(`FMLClientSetupEvent`) `onRegisterReloadListeners`(`RegisterClientReloadListenersEvent`) |

**共 60 个处理器**。本名单不含线程判断 —— 想知道某个处理器在哪个线程、能不能碰共享状态，看上方三条硬规则与 §2 的例外清单；名单里查不到的约束就是**没写过的约束**，不等于「无约束」。
<!-- rosters:end -->

---

## 2. 判据链：`ChunkEvent.Unload` 到底在哪个线程

> 这一节是本页唯一值得展开的推导，因为它**推翻过一次口径**（`D-container-21` → `D-container-22`），
> 而推翻的理由不在代码里、也不在 NeoForge 的 javadoc 里（javadoc 不提线程，且把触发点写错成
> `Chunk.onChunkUnload()`）。留在这里，是为了下一个人不必重走这条弯路。

源码版本：`libs/src/neoforge-21.1.219-merged/`（与 `neo_version` 一致）。全库只有两处 post：

**服务端** `net/minecraft/server/level/ChunkMap.java`：

1. `:532-562` `scheduleUnload`：
   `chunkHolder.getSaveSyncFuture().thenRunAsync(body, this.unloadQueue::add)`
   —— 第二参数是 **executor**，`unloadQueue::add` 只是把 body **入队**；
2. `:153` `unloadQueue` 声明为 `Queue<Runnable>`；
3. `:492` `processUnloads(BooleanSupplier)` 在 `:517-519` 处 `poll()` 后直接 `runnable.run()`；
4. `:469` `processUnloads` 由 `ChunkMap.tick(BooleanSupplier)` 调用；
5. `ServerChunkCache.java:329` `this.chunkMap.tick(hasTimeLeft)` 是唯一调用方 —— **服务器主线程**；
6. ⇒ `ChunkMap.java:543` 的 `NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(chunkaccess))`
   **在主服务器线程执行**，只是相对 `getSaveSyncFuture` 完成时刻**延迟到下一次 drain**。

**客户端** `net/minecraft/client/multiplayer/ClientChunkCache.java:66` —— 客户端主线程。

**顺序事实**（对写处理器有用）：post 时已 `setLoaded(false)`（`:541-542`）、NeoForge 已失效该区块能力；
而 `this.level.unload(levelchunk1)`（`:547-549`）**在 post 之后** ⇒ 卸载事件里方块实体仍在，
但别去查能力（会再入）。

---

## 3. 少数需要单独记的处理器

| 处理器 | 约束 | 判据 |
|---|---|---|
| `LivingItem#onServerTick` | 容器层唯一的 tick 入口；持有 tick 线程对四张表的写权限 | `LivingItem#onServerTick` |
| `LivingItem#onServerStopped` | 静态缓存清理经 `StaticCacheRegistry` 分发给各缓存类自己登记，不在这里逐个清 | `StaticCacheRegistry` 类头约定 · [shared-state.md](shared-state.md) J10 |
| `ContainerChunkCache#onChunkUnload` | 撤销 pending rescan（直接改表，安全）；**不查世界**；**不清**四张表 | §2 · [container-data-lifecycle.md](container-data-lifecycle.md) §3 |
| `LivingItem#onChunkUnload` | 清 `EnderChannelRegistry` 路由（同上，无需入队） | §2 |
| `LivingItemActivationCommand` 等 5 个 `RegisterCommandsEvent` | 启动期，无运行期状态 | 方法体 |

> 名单里 50+ 个处理器没有出现在这张表上，**不代表它们没有约束** —— 代表还没有人
> 为它们踩过坑、写下判据。要知道约束，读方法体。
