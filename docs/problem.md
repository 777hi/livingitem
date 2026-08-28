spark

777_hi @ 10:01PM 8/27/2026, interval 10ms
 Profiler - Mods View
This view shows a filtered representation of the profile broken down by mod.

 Label: Percentage
The value displayed against each frame is the time divided by the total time as a percentage.

 Merge Mode: Merge
Method calls with the same signature will be merged together, even though they may not have been invoked by the same calling method.

neoforge (v21.1.230)
Server thread1.03%
net.neoforged.neoforge.event.EventHooks.fireServerTickPre()0.47%
net.neoforged.bus.EventBus.post()0.47%
net.neoforged.bus.EventBus.post()0.47%
net.neoforged.bus.EventListenerFactory$onServerTick/0x000001e4b63dc800.invoke()0.46%
java.lang.invoke.LambdaForm$MH/0x000001e4b5028000.invokeExact_MT()0.46%
java.lang.invoke.LambdaForm$MH/0x000001e4b5158400.invoke()0.28%
java.lang.invoke.DirectMethodHandle$Holder.invokeVirtual()0.28%
com.qiqi.li.LivingItem.onServerTick()0.28%
com.qiqi.li.LivingItem.processLevelContainers()0.15%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContainerAt()0.14%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContext()0.14%
com.qiqi.li.living.domain.hopper.LivingHopperFunction.tick()0.04%
com.qiqi.li.living.domain.hopper.LivingHopperFunction.executeTransfer()0.04%
com.qiqi.li.living.domain.hopper.TransferPipeline.execute()0.03%
com.qiqi.li.living.domain.hopper.TransferPipeline.executeInContainer()0.03%
com.qiqi.li.living.transfer.SlotAccessorFactory.create()0.01%
com.qiqi.li.living.components.ItemFilterComponent.allows()0.01%
com.qiqi.li.living.transfer.SlotAccessorFactory.<clinit>()0.01%
java.lang.ClassLoader.loadClass()0.01%
java.lang.ClassLoader.loadClass()0.00%
com.qiqi.li.living.container.TickContext.getSnapshot()0.00%
com.qiqi.li.living.container.ContainerSnapshot.capture()0.00%
com.qiqi.li.living.container.ContainerSnapshot.buildAllChestSnapshots()0.00%
com.qiqi.li.living.domain.chest.LivingChestFunction.isLivingChest()0.00%
com.qiqi.li.living.api.LivingItemManager.isLivingItem()0.00%
net.minecraft.core.component.DataComponentHolder.has()0.00%
net.minecraft.core.component.DataComponentMap.has()0.00%
net.minecraft.core.component.PatchedDataComponentMap.get()0.00%
it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap.get()0.00%
java.lang.ClassLoader.loadClass()0.00%
net.minecraft.world.level.block.entity.BlockEntity.setData()0.03%
com.qiqi.li.living.domain.redstone.LivingRedstoneFunction.tickContainerData()0.02%
com.qiqi.li.living.domain.redstone.LivingCopperFunction.tickContainerData()0.01%
com.qiqi.li.living.container.TickContext.<init>()0.01%
com.qiqi.li.living.domain.redstone.LivingRedstoneBlockFunction.tickContainerData()0.00%
com.qiqi.li.living.container.ContainerLivingItemHandler.cleanupStaleRedstoneData()0.00%
com.qiqi.li.living.container.SimpleContainerContext.getItem()0.00%
net.neoforged.neoforge.common.extensions.ILevelExtension.getCapability()0.01%
java.lang.ClassLoader.loadClass()0.00%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContainer()0.13%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContext()0.12%
com.qiqi.li.living.container.ContainerLivingItemHandler.updatePlayerFeetStressOutput()0.07%
com.qiqi.li.living.compat.create.StressOutputManager.apply()0.07%
net.minecraft.world.level.Level.getBlockEntity()0.07%
net.minecraft.world.level.Level.getChunkAt()0.07%
net.minecraft.world.level.Level.getChunk()0.07%
net.minecraft.world.level.LevelReader.getChunk()0.07%
net.minecraft.world.level.Level.getChunk()0.07%
net.minecraft.server.level.ServerChunkCache.getChunk()0.07%
net.minecraft.server.level.ServerChunkCache$MainThreadExecutor.managedBlock()0.06%
net.minecraft.util.thread.BlockableEventLoop.managedBlock()0.06%
net.minecraft.server.level.ServerChunkCache$MainThreadExecutor.pollTask()0.05%
net.minecraft.util.thread.BlockableEventLoop.pollTask()0.05%
net.minecraft.server.level.ServerChunkCache$MainThreadExecutor.doRunTask()0.05%
net.minecraft.util.thread.BlockableEventLoop.doRunTask()0.05%
net.minecraft.server.level.ChunkTaskPriorityQueueSorter$$Lambda/0x000001e4b6a37618.run()0.04%
net.minecraft.server.level.ChunkTaskPriorityQueueSorter.lambda$message$1()0.04%
java.util.concurrent.CompletableFuture$AsyncSupply.run()0.04%
net.minecraft.world.level.chunk.status.ChunkStatusTasks$$Lambda/0x000001e4b6a54678.get()0.04%
net.minecraft.world.level.chunk.status.ChunkStatusTasks.lambda$full$2()0.04%
net.minecraft.world.level.chunk.LevelChunk.runPostLoad()0.04%
net.minecraft.world.level.chunk.storage.ChunkSerializer$$Lambda/0x000001e4b6a4d0e8.run()0.04%
net.minecraft.world.level.chunk.storage.ChunkSerializer.lambda$postLoadChunk$10()0.04%
net.minecraft.world.level.block.entity.BlockEntity.loadStatic()0.04%
java.util.Optional.map()0.04%
net.minecraft.world.level.block.entity.BlockEntity$$Lambda/0x000001e4b6a55808.apply()0.03%
net.minecraft.world.level.block.entity.BlockEntity.lambda$loadStatic$4()0.03%
net.minecraft.world.level.block.entity.BlockEntityType.create()0.03%
com.tterrag.registrate.builders.BlockEntityBuilder$$Lambda/0x000001e4b6468238.create()0.03%
com.tterrag.registrate.builders.BlockEntityBuilder.lambda$createEntry$2()0.03%
com.simibubi.create.AllBlockEntityTypes$$Lambda/0x000001e4b62a1c18.create()0.01%
com.simibubi.create.AllBlockEntityTypes$$Lambda/0x000001e4b6249bf0.create()0.01%
com.simibubi.create.AllBlockEntityTypes$$Lambda/0x000001e4b624c9d8.create()0.00%
net.minecraft.world.level.block.entity.BlockEntity$$Lambda/0x000001e4b6a55a50.apply()0.01%
java.util.concurrent.CompletableFuture$Completion.run()0.01%
net.minecraft.util.thread.BlockableEventLoop.waitForTasks()0.01%
net.minecraft.server.level.ServerChunkCache.getChunkFutureMainThread()0.01%
net.minecraft.world.level.LevelHeightAccessor.isOutsideBuildHeight()0.00%
com.qiqi.li.living.domain.redstone.LivingRepeaterFunction.tickContainerData()0.02%
com.qiqi.li.living.api.LivingItemManager.isLivingItem()0.00%
com.qiqi.li.living.container.TickContext.<init>()0.00%
java.lang.ClassLoader.loadClass()0.00%
com.qiqi.li.living.container.SimpleContainerContext.getItem()0.00%
com.qiqi.li.living.container.SimpleContainerContext.setTickContext()0.00%
com.qiqi.li.living.container.ContainerLivingItemHandler.processEnderChest()0.01%
net.minecraft.world.entity.Entity.getCapability()0.01%
com.qiqi.li.living.container.ContainerLivingItemHandler.buildContext()0.00%
java.lang.invoke.LambdaForm$MH/0x000001e4b6d8cc00.invoke()0.18%
net.neoforged.bus.EventListenerFactory$onTickStart/0x000001e4b69c4000.invoke()0.01%20ms
net.neoforged.bus.EventListenerFactory$event/0x000001e4b5f1a000.invoke()0.00%
net.neoforged.neoforge.registries.BaseMappedRegistry.resolve()0.08%
net.neoforged.neoforge.event.EventHooks.checkMobDespawn()0.06%
net.neoforged.neoforge.event.EventHooks.fireServerTickPost()0.05%
net.neoforged.neoforge.common.extensions.IEntityExtension.getClassification()0.04%
net.neoforged.neoforge.event.EventHooks.fireLevelTickPost()0.03%
net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingChunk()0.03%
net.neoforged.neoforge.common.util.DataComponentUtil.wrapEncodingExceptions()0.02%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.getFriction()0.01%
net.neoforged.neoforge.common.extensions.ILevelReaderExtension.isAreaLoaded()0.01%
net.neoforged.neoforge.event.EventHooks.fireChunkSent()0.01%
net.neoforged.neoforge.common.CommonHooks.onLivingBreathe()0.01%
net.neoforged.neoforge.event.EventHooks.firePlayerTickPost()0.01%
net.neoforged.neoforge.event.EventHooks.onStartEntityTracking()0.01%
net.neoforged.neoforge.server.timings.TimeTracker.<clinit>()0.01%
net.neoforged.neoforge.event.EventHooks.getMaxSpawnClusterSize()0.01%
net.neoforged.neoforge.event.EventHooks.getPotentialSpawns()0.01%
net.neoforged.neoforge.event.EventHooks.fireEntityTickPost()0.01%
net.neoforged.neoforge.common.CommonHooks.onEntityIncomingDamage()0.01%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.collisionExtendsVertically()0.01%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.getAdjacentBlockPathType()0.01%
net.neoforged.neoforge.event.EventHooks.fireEntityTickPre()0.01%
net.neoforged.neoforge.common.extensions.ILevelExtension.getCapability()0.01%
net.neoforged.neoforge.event.EventHooks.fireChunkTicketLevelUpdated()0.01%
net.neoforged.neoforge.server.command.CommandHelper.mergeCommandNode()0.01%
net.neoforged.neoforge.common.NeoForgeMod$$Lambda/0x000001e4b5f31838.accept()0.01%
net.neoforged.neoforge.event.EventHooks.firePlayerSavingEvent()0.00%
net.neoforged.neoforge.common.util.DataComponentUtil.wrapEncodingExceptions()0.00%
net.neoforged.neoforge.common.LenientUnboundedMapCodec.encode()0.00%
net.neoforged.neoforge.event.EventHooks.firePlayerTickPre()0.00%
net.neoforged.neoforge.common.CommonHooks.isLivingOnLadder()0.00%
net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager.sendLightDataTo()0.00%
net.neoforged.neoforge.event.entity.EntityJoinLevelEvent.getLevel()0.00%
net.neoforged.neoforge.event.level.ChunkDataEvent.getData()0.00%
net.neoforged.neoforge.event.EventHooks.checkSpawnPosition()0.00%
net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn()0.00%
net.neoforged.neoforge.event.EventHooks.checkSpawnPlacements()0.00%
net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent$MergedSpawnPredicate$$Lambda/0x000001e4b64d0cf8.test()0.00%
net.neoforged.neoforge.common.CommonHooks.onLivingDeath()0.00%
net.neoforged.neoforge.common.CommonHooks.onLivingDrops()0.00%
net.neoforged.neoforge.common.CommonHooks.onLivingDamagePost()0.00%
net.neoforged.neoforge.common.extensions.IAbstractMinecartExtension.getCurrentRailPosition()0.00%
net.neoforged.neoforge.server.ServerLifecycleHooks.handleServerStarting()0.00%
net.neoforged.neoforge.server.ServerLifecycleHooks.handleServerAboutToStart()0.00%
net.neoforged.neoforge.server.ServerLifecycleHooks.handleServerStarted()0.00%
net.neoforged.neoforge.common.CommonHooks.onRightClickBlock()0.00%
net.neoforged.neoforge.event.EventHooks.firePlayerLoggedIn()0.00%
net.neoforged.neoforge.event.EventHooks.getPlayerDisplayName()0.00%
net.neoforged.neoforge.network.configuration.ICustomConfigurationTask.start()0.00%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.onNeighborChange()0.00%
net.neoforged.neoforge.registries.BaseMappedRegistry.resolve()0.00%
entity-deserializer2.94%
living_item (v1.3.1)
Server thread0.53%
com.qiqi.li.LivingItem.onServerTick()0.46%
com.qiqi.li.LivingItem.processLevelContainers()0.29%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContainerAt()0.23%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContext()0.22%
com.qiqi.li.living.domain.redstone.LivingRedstoneFunction.tickContainerData()0.06%
com.qiqi.li.living.domain.redstone.ContainerRedstoneData.calculate()0.06%
com.qiqi.li.living.domain.redstone.ContainerRedstoneData.injectExternalInputs()0.02%
net.minecraft.world.level.SignalGetter.getSignal()0.01%
net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase.getSignal()0.01%
net.minecraft.world.level.block.RedStoneWireBlock.getSignal()0.01%
net.minecraft.world.level.block.RedStoneWireBlock.getConnectionState()0.00%
net.minecraft.world.level.block.state.StateHolder.getValue()0.00%
net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase.handler$zei000$living_item$onGetSignal()0.00%
net.minecraft.world.level.Level.getBlockState()0.00%
com.qiqi.li.living.container.SimpleContainerContext.getWidth()0.01%
com.qiqi.li.living.domain.redstone.ContainerRedstoneData.phase1CollectSources()0.01%
com.qiqi.li.living.domain.redstone.ContainerRedstoneData.phase4PowerConductors()0.00%
com.qiqi.li.living.domain.redstone.ContainerRedstoneData.phase5UpdateDisplay()0.00%
com.qiqi.li.living.container.TickContext.getFunctionSlots()0.00%
com.qiqi.li.living.domain.redstone.ContainerRedstoneData.copperSubset()0.00%
com.qiqi.li.living.domain.redstone.ContainerRedstoneData.buildSlotMask()0.00%
com.qiqi.li.living.domain.redstone.ContainerRedstoneData.computeFaceOutput()0.00%
com.qiqi.li.living.domain.redstone.ContainerRedstoneData.phase3RecheckInputs()0.00%
com.qiqi.li.living.domain.hopper.LivingHopperFunction.tick()0.05%
com.qiqi.li.living.domain.hopper.LivingHopperFunction.executeTransfer()0.04%
com.qiqi.li.living.domain.hopper.TransferPipeline.execute()0.03%
com.qiqi.li.living.domain.hopper.TransferPipeline.executeInContainer()0.03%
com.qiqi.li.living.transfer.SlotAccessorFactory.create()0.01%
com.qiqi.li.living.transfer.SlotAccessorFactory$$Lambda/0x000001e4b6c0cde8.create()0.00%
java.lang.ClassLoader.loadClass()0.00%
com.qiqi.li.living.components.ItemFilterComponent.allows()0.01%
com.qiqi.li.living.transfer.FilterData.equals()0.01%
java.lang.invoke.MethodHandleNatives.linkCallSite()0.01%
java.lang.invoke.MethodHandleNatives.linkCallSiteImpl()0.01%
java.lang.invoke.CallSite.makeSite()0.01%
java.lang.invoke.BootstrapMethodInvoker.invoke()0.01%
java.lang.invoke.BootstrapMethodInvoker.invokeWithManyArguments()0.01%
java.lang.invoke.Invokers.spreadInvoker()0.00%
java.lang.invoke.MethodHandle.asType()0.00%
com.qiqi.li.living.transfer.SlotAccessorFactory.<clinit>()0.01%
java.lang.invoke.MethodHandleNatives.linkCallSite()0.00%
java.lang.ClassLoader.loadClass()0.00%
java.lang.ClassLoader.loadClass()0.01%
java.lang.ClassLoader.loadClass()0.00%
com.qiqi.li.living.container.TickContext.getSnapshot()0.00%
java.lang.ClassLoader.loadClass()0.00%
com.qiqi.li.living.container.SimpleContainerContext.syncSlotToClients()0.00%
com.qiqi.li.living.api.LivingItemManager.setHopperData()0.00%
net.minecraft.world.level.block.entity.BlockEntity.setData()0.04%
net.minecraft.world.level.block.entity.BlockEntity.setChanged()0.04%
net.minecraft.world.level.block.entity.BlockEntity.setChanged()0.04%
net.minecraft.world.level.Level.updateNeighbourForOutputSignal()0.03%
net.minecraft.world.level.Level.blockEntityChanged()0.01%
com.qiqi.li.living.domain.redstone.LivingCopperFunction.tickContainerData()0.02%
com.qiqi.li.living.container.SimpleContainerContext.getItem()0.01%
com.qiqi.li.living.container.TickContext.<init>()0.01%
com.qiqi.li.living.domain.redstone.LivingRedstoneBlockFunction.tickContainerData()0.00%
com.qiqi.li.living.container.ContainerLivingItemHandler.cleanupStaleRedstoneData()0.00%
com.qiqi.li.living.container.SimpleContainerContext.getSize()0.00%
java.util.HashSet.add()0.00%
com.qiqi.li.living.perf.PerfMetrics.recordPhase()0.00%
com.qiqi.li.living.domain.redstone.LivingComparatorFunction.tickContainerData()0.00%
com.qiqi.li.living.container.SimpleContainerContext.<init>()0.01%
net.neoforged.neoforge.common.extensions.ILevelExtension.getCapability()0.03%
java.util.ArrayList.<init>()0.01%
net.minecraft.server.level.ServerChunkCache.getChunkNow()0.01%
java.lang.ClassLoader.loadClass()0.00%
com.qiqi.li.living.container.ContainerChunkCache.cleanupStaleEntries()0.00%
com.qiqi.li.living.container.ContainerChunkCache.flushPendingRescans()0.00%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContainer()0.16%
java.util.Collections$UnmodifiableCollection$1.hasNext()0.00%
com.qiqi.li.living.api.LivingItemManager.getIgnoredComponentTypes()0.03%
com.qiqi.li.living.api.LivingItemManager.isLivingItem()0.01%
com.qiqi.li.living.container.ContainerChunkCache.onChunkLoad()0.01%
com.qiqi.li.living.domain.map.MapUpdateSkipHelper.shouldSkip()0.01%
com.qiqi.li.living.domain.redstone.LivingRedstoneData.equals()0.00%
ironchest (v1.21-neoforge-16.0.7)
Server thread0.21%
entity-deserializer0.98%
create (v6.0.10)
Server thread0.15%
entity-deserializer0.98%
architectury (v13.0.8)
Server thread0.06%
Registrate.MC1._21._67
Server thread0.03%
spark (v1.10.124)
Server thread0.03%
ponder (v1.0.82+mc1.21.1)
Server thread0.02%
jdk.proxy3
Server thread0.02%
flywheel (v1.0.4)
Server thread0.01%
observable (v5.4.4)
Server thread0.01%
Other
The following other mods are installed, but didn't show up in this profile. Yay!

minecraft (v1.21.1)
tagtooltips (v1.2.0)
fabric_renderer_api_v1 (v3.4.1+9125b6dc19)
fabric_api_base (v0.4.42+d1308ded19)
componentviewer (v1.3.3+1.21.1)
customskinloader (v14.27)
jei (v19.38.0.366)
fabric_rendering_data_attachment_v1 (v0.3.48+73761d2e19)
kotlinforforge (v5.12.0)
fabric_block_view_api_v2 (v1.0.10+9afaaf8c19)
spark and spark-viewer are free & open source on GitHub.
Copyright © 2018-2026 lucko & other spark contributors.









Individual
Chunks
Aggregate
Traces
Info
unknown8/27/2026, 10:04:22 PM5s
Search entries...
- minecraft:overworld — 73 entries	1286 μs/t	Position
minecraft:player	623 μs/t	(138438, 71, 1477840) Visit
minecraft:bat	59 μs/t	(138428, 44, 1477829) Visit
ironchest:crystal_chest	46 μs/t	(138440, 70, 1477837) Visit
minecraft:rabbit	38 μs/t	(138390, 64, 1477783) Visit
ironchest:crystal_chest	38 μs/t	(138440, 70, 1477841) Visit
minecraft:chest_minecart	32 μs/t	(138538, 36, 1477966) Visit
minecraft:polar_bear	23 μs/t	(138363, 67, 1477950) Visit
minecraft:rabbit	23 μs/t	(138518, 69, 1477855) Visit
ironchest:crystal_chest	22 μs/t	(138439, 70, 1477841) Visit
minecraft:glow_squid	21 μs/t	(138366, 5, 1477896) Visit
minecraft:rabbit	16 μs/t	(138524, 67, 1477900) Visit
minecraft:bat	15 μs/t	(138481, 11, 1477920) Visit
minecraft:rabbit	15 μs/t	(138542, 67, 1477864) Visit
minecraft:bat	15 μs/t	(138414, 45, 1477858) Visit
minecraft:rabbit	14 μs/t	(138363, 64, 1477784) Visit
minecraft:bat	14 μs/t	(138473, 30, 1477833) Visit
minecraft:rabbit	14 μs/t	(138412, 63, 1477935) Visit
minecraft:bat	12 μs/t	(138445, 30, 1477759) Visit
minecraft:bat	11 μs/t	(138429, -7, 1477764) Visit
minecraft:bat	11 μs/t	(138438, 49, 1477834) Visit
minecraft:rabbit	11 μs/t	(138375, 64, 1477761) Visit
ironchest:crystal_chest	11 μs/t	(138440, 70, 1477835) Visit
minecraft:bat	10 μs/t	(138461, 16, 1477930) Visit
minecraft:rabbit	10 μs/t	(138555, 70, 1477873) Visit
minecraft:rabbit	10 μs/t	(138532, 65, 1477860) Visit
minecraft:rabbit	10 μs/t	(138526, 67, 1477869) Visit
minecraft:bat	9 μs/t	(138442, -17, 1477757) Visit
minecraft:bat	9 μs/t	(138392, 29, 1477849) Visit
minecraft:bat	8 μs/t	(138399, 24, 1477854) Visit
minecraft:squid	8 μs/t	(138505, 53, 1477943) Visit
minecraft:bat	8 μs/t	(138443, 28, 1477764) Visit
minecraft:rabbit	8 μs/t	(138530, 67, 1477858) Visit
minecraft:bat	7 μs/t	(138399, -5, 1477767) Visit
minecraft:bat	7 μs/t	(138449, 27, 1477767) Visit
minecraft:squid	7 μs/t	(138495, 54, 1477937) Visit
minecraft:squid	6 μs/t	(138497, 57, 1477932) Visit
create:belt	6 μs/t	(138442, 70, 1477848) Visit
minecraft:glow_squid	6 μs/t	(138349, 6, 1477895) Visit
minecraft:glow_squid	6 μs/t	(138358, 9, 1477901) Visit
ironchest:crystal_chest	6 μs/t	(138440, 70, 1477836) Visit
minecraft:bat	6 μs/t	(138451, 26, 1477770) Visit
minecraft:glow_squid	6 μs/t	(138362, 7, 1477897) Visit
minecraft:glow_squid	5 μs/t	(138364, 9, 1477892) Visit
minecraft:glow_squid	5 μs/t	(138360, 3, 1477901) Visit
ironchest:crystal_chest	5 μs/t	(138440, 70, 1477833) Visit
minecraft:glow_squid	5 μs/t	(138360, 9, 1477890) Visit
ironchest:crystal_chest	4 μs/t	(138450, 70, 1477831) Visit
minecraft:mob_spawner	3 μs/t	(21, -31, 42) Visit
minecraft:bat	3 μs/t	(138427, -4, 1477793) Visit
create:simple_kinetic	3 μs/t	(138444, 70, 1477841) Visit
ironchest:crystal_chest	2 μs/t	(138439, 70, 1477833) Visit
create:depot	2 μs/t	(138438, 70, 1477852) Visit
create:belt	2 μs/t	(138442, 70, 1477850) Visit
create:motor	1 μs/t	(138441, 70, 1477852) Visit
minecraft:glow_item_frame	1 μs/t	(138446, 71, 1477841) Visit
minecraft:mob_spawner	1 μs/t	(138441, -24, 1477722) Visit
minecraft:mob_spawner	1 μs/t	(138345, 39, 1477815) Visit
minecraft:mob_spawner	1 μs/t	(138394, -23, 1477800) Visit
minecraft:mob_spawner	1 μs/t	(138410, -17, 1477843) Visit
minecraft:mob_spawner	1 μs/t	(138523, -9, 1477837) Visit
minecraft:mob_spawner	1 μs/t	(138314, 36, 1477783) Visit
minecraft:mob_spawner	1 μs/t	(138574, 34, 1477895) Visit
minecraft:mob_spawner	1 μs/t	(138482, -26, 1477783) Visit
minecraft:mob_spawner	1 μs/t	(138360, -19, 1477742) Visit
minecraft:mob_spawner	1 μs/t	(138339, 37, 1477776) Visit
minecraft:mob_spawner	1 μs/t	(138348, -29, 1477755) Visit
minecraft:mob_spawner	1 μs/t	(138329, -20, 1477754) Visit
minecraft:mob_spawner	1 μs/t	(138421, -44, 1477879) Visit
create:simple_kinetic	0 μs/t	(138445, 70, 1477841) Visit
create:belt	0 μs/t	(138442, 70, 1477851) Visit
create:belt	0 μs/t	(138442, 70, 1477852) Visit
minecraft:glow_item_frame	0 μs/t	(138441, 70, 1477838) Visit
create:belt	0 μs/t	(138442, 70, 1477849) Visit
