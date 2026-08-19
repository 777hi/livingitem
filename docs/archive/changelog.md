# 开发历史更新记录

> 从 `AGENTS.md` 迁出的历史更新记录，保留完整变更细节供参考。

---

## 2026-08-19

- ✅ **优化：传送区块加载从 ChunkStatus.FULL 降为 LIGHT**（远距离传送到未探索区域时 MSPT 峰值 16200ms → 大幅降低）
  - **根因**：`ensureChunkLoaded` 使用 `level.getChunk(chunkX, chunkZ)`（默认 `ChunkStatus.FULL`），要求区块经历完整 11 个生成阶段。`FULL` 的 `ChunkPyramid` 依赖链中 `STRUCTURE_STARTS` 半径 8，导致 `ChunkGenerationTask` 需覆盖 17×17 = 289 个区块。主线程通过 `managedBlock()` → `LockSupport.parkNanos()` 阻塞等待（spark 报告 205.84%），同时 `FULL` 阶段的 `runPostLoad()` 在新生成区块上触发大量 mod 事件（`twilightforest` 22.82%、`sable` 66.17%）。
  - **修复**：`findSafeY` 仅需 `MOTION_BLOCKING` 高度图，该数据在 `LIGHT` 阶段即已就绪。将 `ensureChunkLoaded` 改为 `level.getChunk(chunkX, chunkZ, ChunkStatus.LIGHT, true)`，跳过 `SPAWN`（出生点生成）和 `FULL`（ProtoChunk→LevelChunk 转换 + `runPostLoad()`）两个阶段。`ChunkLoadResult` 和 `findSafeY` 参数类型从 `LevelChunk` 改为 `ChunkAccess`（`LIGHT` 返回 `ImposterProtoChunk`）。
  - **修改文件**：`TeleportHelper.java`

- ✅ **优化：零区块加载传送——ChunkGenerator.getBaseHeight 替代 ChunkStatus.LIGHT**
  - **根因**：v47 的 `ChunkStatus.LIGHT` 仍依赖 `STRUCTURE_STARTS` 半径 8（`LIGHT` → `FEATURES` → ... → `STRUCTURE_STARTS`），仍需 289 个区块的生成任务。spark 报告 `parkNanos` 3.89% self = 3760ms 纯阻塞等待。`ChunkStatus` 体系中任何能提供高度图的状态都必须经过 `LIGHT`，无法绕过 289 区块依赖。
  - **修复**：完全绕过区块加载，使用 `ChunkGenerator.getBaseHeight(x, z, MOTION_BLOCKING, level, randomState)` 从噪声密度函数直接计算地表高度。`NoiseBasedChunkGenerator.getBaseHeight()` 内部调用 `iterateNoiseColumn()` 遍历噪声柱，在第一个不透明方块处停止并返回 Y+1，耗时 < 1ms。移除 `ensureChunkLoaded`、`findSafeY`、`ChunkLoadResult` 方法和相关 import。`teleportToBanner` 也移除 `ensureChunkLoaded`（`changeDimension`/`teleportTo` 内部通过 `POST_TELEPORT` ticket 异步加载）。子位面传送保留 `ensureChunkForSubLevel`。
  - **修改文件**：`TeleportHelper.java`

- ✅ **修复：未打开战利品容器触发战利品表生成导致 processLevelContainers 耗时过高**
  - **根因**：`processLevelContainers` 遍历所有世界容器时，`RandomizableContainerBlockEntity.getItem()` 内部调用 `unpackLootTable()`，触发战利品生成。战利品表（如 `minecraft:chests/shipwreck_map`）中的 `ExplorationMapFunction` 搜索结构，在未探索区域耗时极高。
  - **修复**：
    - `LivingItem.processLevelContainers()`：遍历时跳过 `RandomizableContainer` 且 `lootTable != null`（未打开）的容器
    - `ContainerLivingItemHandler.processContainerAt()`：同上，添加战利品容器过滤逻辑
  - **修改文件**：`LivingItem.java`、`ContainerLivingItemHandler.java`

- ✅ **修复：活中继器延迟计数器不减少**（活红石中继器的延迟计数器每 tick 被重置，导致永远无法输出信号）
  - **根因**：`SimpleContainerContext` 实例每 tick 重建，其内部的 `redstoneData` 字段始终为 null，`getOrCreateRedstoneData()` 每 tick 创建新实例。这导致 `edgeGrid` / `prevEdgeGrid`（边信号状态）和 `tickCounter`（延迟计数器）每 tick 丢失，中继器功能无法正常工作。
  - **修复**：
    - `ContainerLivingItemHandler`：新增 `REDSTONE_DATA_CACHE` 静态缓存（`LinkedHashMap`），通过 `containerKey` 关联，确保 `edgeGrid` / `prevEdgeGrid` / `tickCounter` 跨 tick 持久化
    - `SimpleContainerContext.getOrCreateRedstoneData()`：改为从 `ContainerLivingItemHandler.getRedstoneData()` 获取数据，而非新建实例
    - `ContainerRedstoneData`：新增 `lastTickTime` 字段，在 `calculate()` 中更新，支持过期清理
    - `ContainerChunkCache.onBlockBreak`：新增 `removeRedstoneDataByPos()` 调用，事件驱动清理
    - `ContainerLivingItemHandler.cleanupStaleRedstoneData()`：每 120 秒清理一次 120 秒内未访问的红石数据
  - **清理机制**：事件驱动清理（`removeRedstoneDataByPos`）→ 定期过期清理（`cleanupStaleRedstoneData`）→ 容器销毁清理（`removeRedstoneData`）

- ✅ **修复：区块重新加载后活物品不工作（残留问题）**（2026-08-17 的修复未完全解决，离开区块一定时间后返回容器中活物品仍停止 tick）
  - **残留根因**：`ChunkEvent.Load` 在 `MinecraftServer.waitUntilNextTick()` 的 `runAllTasks()` 中触发，而 `processLevelContainers` 在 `ServerTickEvent.Post` 中执行（早于 `runAllTasks`）。`onChunkUnload` 立即从缓存中移除区块 → `ChunkEvent.Load` 来不及在同一 tick 加回缓存 → `processLevelContainers` 找不到该区块。
  - **修复**：
    - `ContainerChunkCache.onChunkUnload`：不再从缓存中移除区块，改为由 `cleanupStaleEntries` 统一清理
    - `ContainerChunkCache.cleanupStaleEntries`：首次调用时仅初始化 `lastCleanup` 时间戳并返回，避免立即清理刚卸载、即将重新加载的区块
  - **修复后流程**：卸载 → 缓存保留 → 重新加载 → 下一 tick 正常处理（最多延迟 1 tick）

- ✅ **修复：活中继器输入端有信号但显示无信号**（红石火把等信号源正常输出，但中继器始终显示"unpowered"，无法检测到输入信号）
  - **根因**：`SimpleContainerContext` 实例每 tick 重新创建，`setTickContext()` 中 `resetProcessedFlag()` 的调用被 `redstoneData != null` 条件守卫。由于新实例的 `redstoneData` 字段始终为 null，`resetProcessedFlag()` 永远不会被调用。`ContainerRedstoneData.processedThisTick` 在第一 tick 后被设为 true 后永不重置，导致 `calculate()` 从第二 tick 起直接返回，完全跳过红石计算。
  - **修复**：
    - `SimpleContainerContext.setTickContext()`：改为主动调用 `getOrCreateRedstoneData()` 从静态缓存获取已持久化的 `ContainerRedstoneData` 实例，再调用 `resetProcessedFlag()`，确保每一 tick 开始时 `processedThisTick` 被正确重置为 false
  - **相关知识**：`calculate()` 是一次性处理所有红石类型（火把、中继器、比较器、红石粉、灯、按钮、拉杆）的综合方法，`processedThisTick` 标志的作用是防止多个 `HasContainerData` 函数在同一 tick 重复调用 `calculate()`，而非区分不同红石类型的处理顺序。因此调整各功能 `getPriority()` 的方案并不对症——真正的问题在于该标志从未被重置。

- ✅ **修复：活中继器不能延迟熄灭**（输入信号消失后应延迟对应时间再停止输出，而非立即熄灭）
  - **根因**：`phase0CountdownDelays` 中 `!hasInput` 分支立即将 `powered` 设为 false 且 `delayTimer` 归零，`phase3RecheckInputs` 中 `!hasInput && data.powered()` 分支同理。中继器只有"上升沿延迟"（ON_DELAY），没有"下降沿延迟"（OFF_DELAY）。
  - **修复**：利用 `delayTimer` 的正负号区分两种延迟方向，无需修改 `LivingRepeaterData` 记录结构：
    - `delayTimer > 0`：ON_DELAY（等待开启，不输出信号）
    - `delayTimer = 0`：ON（正常输出）
    - `delayTimer < 0`：OFF_DELAY（等待关闭，**继续输出信号**）
  - **具体变更**：
    - `phase0CountdownDelays`：`delayTimer > 0` 时递减（ON_DELAY 倒计时），`delayTimer < 0` 时递增趋近于 0（OFF_DELAY 倒计时）。ON_DELAY 期间若输入消失则取消（`powered=false`）；OFF_DELAY 归零时转为 OFF（`powered=false`）
    - `phase1CollectSources` 中继器输出条件：`delayTimer() != 0` → `delayTimer() > 0`，确保 OFF_DELAY（`delayTimer < 0`）期间继续输出信号
    - `phase3RecheckInputs`：新增三种过渡——`hasInput && powered && delayTimer < 0`（OFF_DELAY 期间输入恢复，取消延迟回到 ON）；`!hasInput && powered && delayTimer == 0`（ON 状态下输入消失，启动 OFF_DELAY，`delayTimer = -delay`）
    - `LivingRepeaterFunction.addToTooltip`：OFF_DELAY 期间用 `Math.abs()` 显示正数

- ✅ **新增：活红石块**（`Items.REDSTONE_BLOCK`）
  - 常亮信号源，向四个方向输出信号强度 15（受堆叠数衰减），无状态数据
  - 新增 `LivingRedstoneBlockFunction`：实现 `LivingItemFunction` + `HasContainerData`，`canApply` 匹配 `Items.REDSTONE_BLOCK`
  - `ContainerRedstoneData.phase1CollectSources`：新增 `redstoneBlockSlots` 参数，红石块始终向 4 方向写边信号
  - 无 DataComponent：红石块无状态，无需持久化数据

---

## 2026-08-17

- ✅ **修复：区块重新加载后活物品不工作**（离开区块一定时间后返回，容器中活物品停止 tick；重新进入游戏恢复正常）
  - **根因**：`processLevelContainers` 使用 `getChunkNow()` 获取区块，当 `FULL` 状态的 `CompletableFuture` 尚未完成时返回 null，导致区块被 `toRemove` 从缓存中移除。区块从磁盘完整重新加载时存在 `ChunkEvent.Load`（触发缓存加入）与 `ServerTickEvent.Post`（触发缓存遍历）之间的时序窗口，加载后同一 tick 内被意外清除。
  - **为什么短时间内回来正常**：短时间内区块未完全卸载，`getChunkNow` 仍返回有效值，不会被移除。
  - **修复**：
    - `LivingItem.processLevelContainers`：`getChunkNow` 返回 null 时不再立即移除，只跳过本次处理，保留到下次 tick
    - `ContainerChunkCache.cleanupStaleEntries`：新增定期清理方法，每 6000 ticks（约 5 分钟）清理一次真正已卸载的区块，作为兜底机制防止内存泄漏
    - `ContainerChunkCache.clear()`：同步清理 `lastCleanupTick` 时间戳
  - **清理机制三层保障**：`onChunkUnload`（即时移除）→ `!hasContainer` 检查（自清洁）→ `cleanupStaleEntries`（兜底清理）
  - **注意**：此修复在 2026-08-19 发现残留问题——`onChunkUnload` 即时移除与 `ChunkEvent.Load` 晚触发之间存在时序窗口，见 2026-08-19 记录。

---

## 2026-08-16

- ✅ **优化：Mixin 兼容性重构**（移除高风险 Mixin，改用 NeoForge API 或低风险替代方案）
  - 删除 `MapItemMixin`（`@Inject` 到 `EmptyMapItem.use`）→ 改用 `PlayerInteractEvent.RightClickItem` 事件（`LivingMapEventHandler.handleLivingMapCreation`）
  - 删除 `MapItemUpdateMixin`（2个 `@Redirect` 替换 `Level.getChunk`）→ 依赖原版 `MapItem.update`（玩家附近区块通常已加载；原 `@Redirect` 与暮色森林魔法地图冲突导致服务端卡死）
  - 删除 `BlockEntityMixin`（接口注入 + 字段注入 `livingItem$stressData`）→ 改用 NeoForge `AttachmentType`（`LivingItemManager.CONTAINER_STRESS_DATA`，框架级支持、自动序列化、类型安全）
  - 删除 `StressDataProvider` 接口（不再需要，`be.getData()`/`be.setData()` 替代）
  - `RecipeBookComponentMixin` 移除 3个 `@Redirect` → 新增 `RecipeBookPageMixin`（3个 `@Inject HEAD cancellable`，低风险，允许多模组链式共存）
  - `ContainerLivingItemHandler` 应力写入从 `StressDataProvider.livingItem$setStressData()` → `be.setData(CONTAINER_STRESS_DATA, stressData)`
  - 更新 `living_item.mixins.json`（移除 `MapItemMixin`、`MapItemUpdateMixin`、`BlockEntityMixin`）
  - 更新 `living_item.client.mixins.json`（新增 `RecipeBookPageMixin`）

---

## 2026-07-30

- ✅ **重构：包结构按领域聚合**（消灭 `core/` 万能垃圾桶，消除 `capability/` 专属小包，`create/` 提升为 `compat/create/`）
  - `api/` — `LivingItemFunction` + `LivingItemManager` 从 `living/` 根提升
  - `domain/ender/` — 末影箱+活箱子领域聚合（`EnderChannelRegistry`、`EnderChannelEntry`、`LivingChestAccessor`、`LivingEnderChestAccessor` 等 8 个文件）
  - `domain/water/` — 活水领域聚合（`ContainerFluidData`、`ContainerStressData` 从 `container/` 移出）
  - `domain/map/` — 活地图传送领域聚合（`MapTeleportExecutor`、`TeleportHelper`、`MapCoordHelper`、`LivingMapEventHandler`、`ItemFrameMapTeleportHandler`、`LivingMapClientCache`）
  - `compat/create/` — Create 兼容层从 `create/` 提升（含 `CreateMixinPlugin`，已更新 mixin JSON 路径）
  - `compat/sable/` — Sable 飞艇兼容层（`SableCompat` + `ModSable` + `SableIntegration`，三层软依赖）
  - `transfer/` — 传输基础设施（`SlotAccessor` 体系 + `SlotResolver` + `ContainerCompatibilityConfig`，从 `core/accessor/` + `core/config/` 合并）
  - `interaction/` — GUI交互从 `core/interaction/` 提升
  - `model/` — 配置模型从 `core/model/` 提升
  - `components/` — 无状态工具组件从 `core/components/` 提升（`LivingChestTooltipComponent` 归入 `domain/ender/`）
  - `container/` — 纯容器抽象（移除业务数据 `ContainerFluidData`/`ContainerStressData`）
  - `client/render/` — 渲染类从 `client/` 根 + `client/tooltip/` 合并
  - `client/input/` — 输入处理从 `client/` 根移出
- ✅ **优化：活水桶和活水车代码审查**
  - 修复 `ContainerLivingItemHandler` 中 `stressData.calculate()` 缺少第三个参数的编译错误
  - 移除 `LivingWaterBucketFunction.tick()` 中冗余的 `syncSlotToClients` 调用（`postTickSync` 统一同步）
  - `LivingWaterBucketFunction.postTickSync()` 添加 flow 变化检测，避免无变化时冗余网络同步
  - `ContainerLivingItemHandler` 合并两次 `grouped.entrySet()` 遍历为一次
- ✅ **新增：活水车系统**（`LivingWaterWheelFunction` + `LivingWaterWheelData` + `WaterWheelData` + `ContainerStressData`）
- ✅ **新增：力矩计算模型**（二维叉积：位置向量 × 水流方向向量，CW/CCW 方向判定，水流强度权重）
- ✅ **新增：Create 软依赖集成**（`CreateCompat` 检测 + `CreateMixinPlugin` 条件加载 + `ModCreate` 安全调用 + `try-catch` 双重防护）
- ✅ **新增：应力输出逻辑**（`CreateIntegration`：白名单过滤 + 方向兼容性检查 + RPM/SU 设置）
- ✅ **新增：KineticBlockEntity Mixin**（`KineticBlockEntityMixin`：应力输出 + 自过期机制 + 白名单过滤 + `attachKinetics()` 重连）
- ✅ **新增：容器底部/玩家脚底应力传递**（`ContainerLivingItemHandler` 扩展，背包中活水车从玩家脚底输出）
- ✅ **新增：物品栏 3D 旋转渲染**（`ItemRendererWaterWheelMixin`：BakedModel + PoseStack 旋转变换，仅在有应力时旋转）
- ✅ **新增：漫反射光照修正**（`RenderSystem.setShaderLights()` + `combinedLight` 修改，解决物品贴图过暗）
- ✅ **新增：BlockEntity 应力存储**（NeoForge `AttachmentType`：`CONTAINER_STRESS_DATA`，替代原 `BlockEntityMixin` + `StressDataProvider` 接口注入，框架级支持、自动序列化、类型安全）
- ✅ **修复：旋转方向与物品栏不一致**（RPM 公式添加负号 `-sign(netStress)`）
- ✅ **修复：复杂组件崩溃**（白名单策略，仅允许 `SimpleKineticBlockEntity` 和 `BracketedKineticBlockEntity`）
- ✅ **修复：方向冲突导致方块销毁**（`isDirectionCompatible()` 软侵入检查，方向相反不注入）
- ✅ **修复：取消应力后齿轮不恢复**（`attachKinetics()` 重连邻居网络，自动被原发电机接管）
- ✅ **修复：应力源消失后残留**（`refreshedThisTick` 布尔标记替代时间戳，1 tick 响应 + 客户端同步）
- ✅ **修复：`validateKinetics()` 60 tick 后应力消失**（移除 `source` 字段依赖，注入 BE 作为旋转源）

## 2026-07-29

- ✅ **新增：贪心提取策略**（`preferredItemType` 偏好提取，输出槽有铁锭时优先继续提取铁锭可堆叠，而非轮询到金锭导致传输停止，回退正常轮询）
- ✅ **重构：统一路由验证**（`validateRoutes()` 替代 5 个独立清理方法，一次遍历完成注册者/目标/源物品三项检查，新增 `registrarKeyIndex` 反向索引）
- ✅ **修复：活末影箱被拿走后路由不清理**（`registerRoute()` 接收 `targetSlot` 参数，路由条目正确记录活末影箱所在槽位）
- ✅ **修复：活漏斗被拿起到鼠标后路由不清理**（`LivingEnderChestFunction.tick()` 现在也扫描活漏斗槽位，空集触发清理）
- ✅ **新增：TickContext.functionSlots 缓存**（`processContext()` 分组时一次性填充各功能活跃槽位集合，功能类 O(1) 读取，无需遍历容器）

## 2026-07-28

- ✅ **优化：双重扫描合并**（`processContext` 内部扫描后 `grouped` 为空时提前 return，`processEnderChest` 和 `processContainerAt` 不再做预扫描，每容器每 tick 省一次全量槽位扫描）
- ✅ **优化：Snapshot 懒加载**（`TickContext.getSnapshot()` 按需构建，闲置容器和只有活熔炉的容器零开销，`reset()` 不再预构建 snapshot）
- ✅ **优化：容器缓存自清洁**（`ContainerChunkCache.removeChunk()` + `processLevelContainers` 自动清理已卸载区块和无容器区块，零额外扫描开销，缓存自动收敛）
- ✅ **优化：直连模式 InvWrapper 缓存**（`LivingEnderChestAccessor` 构造时缓存 `cachedInvWrapper`，所有操作复用同一实例，避免每 tick 重复创建 `InvWrapper`）
- ✅ **优化：直连模式轮询提取**（`nextExtractSlot` 指针记录上次提取槽位，末影箱所有槽位公平轮询，解决特定槽位物品无法被提取的问题）
- ✅ **重构：EnderChannelRegistry Deque 替代 List+nextIndex**（`ArrayDeque` + `poll()`/`reoffer()` 天然实现轮询调度，消除手动指针管理，`peekEntry()`/`advancePointer()`/`peek()` 三个方法合并为 `poll()` + 条件 `reoffer()`）
- ✅ **新增：RouteKey 内部类 + 反向索引**（`routeKeys` 集合 O(1) 路由去重检查，`entryToChannel` 反向索引 O(1) 查找条目所属频道）
- ✅ **重构：removeIf() 收集后删除模式**（先收集符合条件的条目再批量删除，避免在遍历中混用 `poll()`/`offer()` 导致的 `ConcurrentModificationException`）

## 2026-07-27

- ✅ 重构：DataComponent 直接管理架构迁移（功能类直接管理类型化 DataComponent，替代 `ComponentState` + `LivingFunctionData` 中转层）
- ✅ 重构：组件无状态化改造（`ProgressComponent`、`FuelConsumeComponent` 等从有状态组件变为无状态工具类，接收类型化数据返回新数据）
- ✅ 新增：`data/` 包 — 不可变 Record 数据模型（`LivingTntData`、`LivingWaterBucketData`、`LivingFurnaceData`、`LivingHopperData`、`LivingEnderChestData` 及其子数据）
- ✅ 新增：`DirectionSlotsData` / `DirectionTransferData` 不可变方向数据模型，替代 `DirectionModeComponent` + `ComponentState`
- ✅ 重构：`LivingItemFunction.addToTooltip()` 移除 `CompoundTag` 参数，功能类直接从 `ItemStack` 读取 DataComponent
- ✅ 重构：`LivingTntFunction`、`LivingWaterBucketFunction`、`LivingFurnaceFunction`、`LivingHopperFunction`、`LivingEnderChestFunction` 迁移到新架构
- ✅ 重构：客户端渲染层迁移
- ✅ 重构：`LivingItemTooltip` 简化
- ✅ 重构：`ItemFilterComponent.inheritFilter()` 从 `getFunctionData` 迁移到 `LivingItemManager.getHopperData()`
- ✅ 新增：`SlotMapping.fromDirections(Pos2D, Pos2D)` 工厂方法
- ✅ 修复：`LivingWaterBucketData.DEFAULT` → `LivingWaterBucketData.EMPTY`
- ✅ 修复：`ExplosionData.ignite()` 无参重载方法（默认 80 刻引信）
- ✅ 兼容：`ProgressComponent` 实现 `ILivingComponent` 接口 + `ComponentState` 适配器方法，保持旧编排器编译兼容
- ✅ **优化：TickContext 对象池**（`TickContextPool` 复用 tick 实例——已废弃，大负载场景下池化收益为负，改为每次 tick 创建新实例，由 JVM 年轻代 GC 回收）
- ✅ **优化：SlotAccessor 注册式工厂**（`SlotAccessorFactory.registerProvider()` 开放扩展，第三方模组可注册自定义 Accessor）
- ✅ **优化：LivingItemFunction 接口职责拆分**（5 个逻辑模块：匹配/标识/Tick/Tooltip/组件过滤，可选方法默认空实现）
- ✅ **优化：活箱子精确字节计算**（`LivingChestFunction.calculateExactByteUsage()` 替代粗糙估算，NBT 序列化获取真实大小，Tooltip 显示百分比）
- ✅ **新增：性能监控指标系统**（`PerfMetrics` 收集 Tick 耗时/活物品数量/功能调用/传输成功率，每 60 秒自动打印报告）

## 2026-07-24

- ✅ 重构：SlotAccessor 模拟优先传输模式（`simulateExtract` → `simulateInsert` → `extract` → `insert` → `rollback` 安全兜底 + WARN 日志）
- ✅ 新增：`SlotAccessor` 接口新增 `simulateExtract()` 和 `simulateInsert()` 模拟方法
- ✅ 优化：`SlotAccessor.transfer()` 改为"先模拟确认再真实操作"
- ✅ 优化：`rollback` 修复部分插入场景
- ✅ 优化：`NeighborSlotAccessor.rollback()` 优先放回原槽位
- ✅ 优化：配方缓存（`ItemTransformComponent.resolveRecipe()` 公共方法）
- ✅ 优化：容器位置缓存（拉取模型）替代活跃列表（推送模型）
- ✅ 新增：`BlockEvent` 通用监听兜底
- ✅ 优化：GC 压力降低（`LivingItem` 复用 `IdentityHashMap` 和 `HashSet` 实例字段）
- ✅ 优化：惰性 Tick 去重集合复用

## 2026-07-23

- ✅ 新增：活末影箱系统（`LivingEnderChestFunction` + `LivingEnderChestAccessor` + `EnderChannelRegistry` + `EnderChannelEntry`）
- ✅ 新增：活末影箱双模式（路由模式 + 直连模式）
- ✅ 新增：玩家绑定机制
- ✅ 新增：直连模式预加载
- ✅ 新增：`EnderChannelComponent` 频道组件
- ✅ 新增：`ItemFilterComponent` 黑白名单过滤组件
- ✅ 新增：`FilteredSlotAccessor` 过滤装饰器
- ✅ 新增：`NeighborSlotAccessor` 邻居容器访问器
- ✅ 新增：`SlotAccessor.transfer()` 统一传输方法
- ✅ 新增：`SlotAccessorFactory.createForNeighbor()` 工厂方法
- ✅ 新增：末影箱容器处理
- ✅ 新增：反向索引路由清理
- ✅ 新增：活末影箱 Tooltip
- ✅ 重构：`CrossContainerTransfer` 使用 `NeighborSlotAccessor` + `SlotAccessor.transfer()` 统一传输
- ✅ 重构：活末影箱组件化改造
- ✅ 修复：末影箱中活物品不工作
- ✅ 修复：活漏斗往绑定玩家的活末影箱输入物品不消耗
- ✅ 修复：末影箱中未绑定玩家的活末影箱无法建立路由
- ✅ 修复：水晶箱子（12×9）中活漏斗黑白名单无法正确生效
- ✅ 修复：大箱子中活物品一 tick 内被处理两次
- ✅ 修复：跨容器注册路由不生效
- ✅ 修复：已绑定玩家的活末影箱仍显示频道信息
- ✅ 修复：活末影箱移走后路由未清理
- ✅ 修复：源物品移走后路由未清理
- ✅ 修复：`EnderChannelRegistry.getChannels()` 遍历时 `ConcurrentModificationException`

## 2026-07-22

- ✅ 重构：全面使用 IItemHandler 统一容器抽象
- ✅ 新增：`ContainerContext.getSlotLimit(slot)` 接口方法
- ✅ 优化：`CrossContainerTransfer.tryInsert()` 使用 `ItemHandlerHelper.insertItemStacked()`
- ✅ 优化：`CrossContainerTransfer.hasAnySpace()` 使用 `getSlotLimit(i)`
- ✅ 优化：`PlainSlotAccessor.insert()` / `isFull()` 使用 `getSlotLimit(slot)`
- ✅ 优化：`ItemTransformComponent.calculateOutputSpace()` 使用 `getSlotLimit(outputSlot)`
- ✅ 优化：`ContainerCompatibilityConfig` 新增 `findOrGenerateRule()` 自动推断标准布局
- ✅ 优化：`ContainerLivingItemHandler.buildChestContext()` 统一使用 IItemHandler
- ✅ 优化：`ContainerLivingItemHandler.processBlockEntities()` 使用 `IdentityHashMap<IItemHandler>` 去重
- ✅ 优化：`ContainerChunkCache` 通过 `Capabilities.ItemHandler.BLOCK` 检测容器
- ✅ 修复：抽屉模组容器满时活漏斗仍传输导致物品消失
- ✅ 修复：精妙背包堆叠上限升级后活漏斗误判槽位已满
- ✅ 重构：活漏斗传输引擎引入 SlotAccessor 统一架构
- ✅ 新增：`SlotAccessor` 接口 + `PlainSlotAccessor` + `LivingChestAccessor` + `SlotAccessorFactory`
- ✅ 删除：4 个旧传输方法，~400 行重复代码
- ✅ 修复：混搭黑白名单链传递失效
- ✅ 修复：过滤器拦截时冷却未设置导致无限循环

## 2026-07-20

- ✅ 新增：方块放置自动填充
- ✅ 新增：三层防护体系
- ✅ 新增：铁砧重命名堆叠修复
- ✅ 统一：UUID 操作方向

## 2026-07-17

- ✅ 新增：活箱子系统
- ✅ 新增：UUID 映射管理
- ✅ 新增：堆叠倍增模型
- ✅ 新增：LRU 缓存策略
- ✅ 新增：快速短路判断
- ✅ 新增：漏斗自动传输
- ✅ 新增：跨容器传输
- ✅ 新增：GUI 拆分/合并 UUID 自动分配
- ✅ 新增：跨 UUID 堆叠支持
- ✅ 新增：被动孤儿文件清理
- ✅ 新增：事务包装器
- ✅ 重构：living 文件夹按职责拆分
- ✅ 修复：堆叠活箱子时漏斗只能访问到一个活箱子
- ✅ 修复：UUID 创建时机
- ✅ 修复：跨包访问权限

## 2026-07-11

- ✅ 新增：活TNT功能
- ✅ 新增：活打火石功能
- ✅ 新增：GUI交互系统
- ✅ 新增：客户端统一交互工具
- ✅ 新增：通用交互网络包
- ✅ 新增：创造模式光标同步
- ✅ 新增：创造模式 SlotWrapper 兼容
- ✅ 新增：ExplosionComponent 双模式爆炸
- ✅ 新增：流体防爆机制
- ✅ 修复：创造模式背包中活打火石无法点燃活TNT
- ✅ 修复：创造模式光标物品消失问题
- ✅ 修复：创造模式打开容器时光标物品消失
- ✅ 重构：所有 Screen Mixin 统一使用 `GuiInteractionHelper.tryInteract()`
- ✅ 重构：引入编排器模式
- ✅ 重构：提取 `BaseLivingFunction` 基类
- ✅ 重构：`LivingFurnaceFunction` 从 317 行 → 82 行（-74%）
- ✅ 重构：`LivingHopperFunction` 从 219 行 → 75 行（-66%）
- ✅ 重构：`FunctionExecutor` 从调度器变为纯工具类
- ✅ 重构：`DirectionModeComponent` NBT 自治
- ✅ 重构：`LivingFunctionConfig` 新增 `withOrchestrator()` 方法
- ✅ 重构：`ComponentContext` 使用 `ResolvedSlots` 替代三个独立字段
- ✅ 重构：`ContainerContext` 新增槽位占用机制
- ✅ 简化：`LivingHopperFunction.canApply()` 移除冗余的物品匹配检查
- ✅ 简化：`LivingItemFunction` 接口新增 `appendComponentTooltips()` 默认方法

## v0.3-alpha

- ✅ 重构：将 `TransferDirection`、`Direction2D`、`HopperModeController`、`LivingHopperInputHandler` 合并为 `DirectionModeComponent` + `LivingItemInputHandler`
- ✅ 重构：提取 `Pos2D` 和 `SlotMapping` 为独立 model 类
- ✅ 修复：活漏斗传输方向修改后 NBT/Tooltip/实际传输未同步更新的问题
- ✅ 修复：活漏斗传输功能失效（`SlotResolver` 网格宽度计算错误）