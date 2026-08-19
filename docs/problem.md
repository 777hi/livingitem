spark

777_hi @ 下午07:47 2026/8/19, interval 10ms
TPS
15.08
1m
18.78
5m
19.57
15m
MSPT
0.3
min
13.4
med
17.4
95%ile
12800
max
CPU(process)
37.49%
1m
17.22%
15m
Memory(process)
4.3 GB
/
6.6 GB
64.6%
CPU(system)
53.46%
1m
31.93%
15m
Refine
The graph below shows some key metrics over the course of the profile. You can drag + select with your cursor to refine the profile to a specific time period.

Mods View
This view shows a filtered representation of the profile broken down by mod.

 Label: Percentage
The value displayed against each frame is the time divided by the total time as a percentage.

 Merge Mode: Merge
Method calls with the same signature will be merged together, even though they may not have been invoked by the same calling method.

neoforge (v21.1.235)
Server thread44.87%
net.neoforged.neoforge.event.EventHooks.fireServerTickPost()15.36%
net.neoforged.bus.EventBus.post()15.36%
net.neoforged.bus.EventBus.post()15.36%
net.neoforged.bus.EventListenerFactory$onServerTick/0x000001fa83088000.invoke()14.98%
java.lang.invoke.LambdaForm$MH/0x000001fa81028400.invokeExact_MT()14.98%
java.lang.invoke.LambdaForm$MH/0x000001fa8454c800.invoke()14.96%
java.lang.invoke.DirectMethodHandle$Holder.invokeVirtual()14.96%
com.qiqi.li.LivingItem.onServerTick()19.80%
com.qiqi.li.LivingItem.processLevelContainers()24.16%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContainerAt()22.99%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContext()22.04%
com.qiqi.li.living.container.SimpleContainerContext.getItem()18.88%
net.neoforged.neoforge.items.wrapper.InvWrapper.getStackInSlot()18.20%
net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity.getItem()20.22%
net.minecraft.world.RandomizableContainer.unpackLootTable()19.44%
net.minecraft.world.level.storage.loot.LootTable.fill()22.21%
net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity.setItem()13.72%
net.minecraft.world.level.block.entity.BaseContainerBlockEntity.setItem()13.72%
net.minecraft.world.level.block.entity.BlockEntity.setChanged()13.72%
net.minecraft.world.level.block.entity.BlockEntity.setChanged()13.72%
net.minecraft.world.level.Level.updateNeighbourForOutputSignal()15.31%
net.minecraft.world.level.Level.getBlockState()17.02%
net.minecraft.world.level.Level.getChunk()17.01%
net.minecraft.world.level.LevelReader.getChunk()17.01%
net.minecraft.world.level.Level.getChunk()17.01%
net.minecraft.server.level.ServerChunkCache.getChunk()17.01%
net.minecraft.server.level.ServerChunkCache$MainThreadExecutor.managedBlock()17.01%
net.minecraft.util.thread.BlockableEventLoop.managedBlock()18.47%
net.minecraft.util.thread.BlockableEventLoop.waitForTasks()17.44%
java.util.concurrent.locks.LockSupport.parkNanos()16.95%
jdk.internal.misc.Unsafe.park()16.95%
java.lang.Thread.yield()0.49%
net.minecraft.server.level.ServerChunkCache$MainThreadExecutor.pollTask()2.69%
net.minecraft.world.level.chunk.LevelChunk.getBlockState()0.01%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.onNeighborChange()0.01%
net.minecraft.world.level.storage.loot.LootTable.getRandomItems()8.48%
net.minecraft.world.level.storage.loot.LootTable.getRandomItemsRaw()8.45%
net.minecraft.world.level.storage.loot.LootPool.addRandomItems()8.45%
net.minecraft.world.level.storage.loot.LootPool.addRandomItem()9.26%
net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer$1.createItemStack()10.08%
net.minecraft.world.level.storage.loot.entries.LootItem.createItemStack()10.07%
net.minecraft.world.level.storage.loot.functions.LootItemFunction$$Lambda/0x000001fa84149408.accept()10.06%
net.minecraft.world.level.storage.loot.functions.LootItemFunction.lambda$decorate$0()10.06%
net.minecraft.world.level.storage.loot.functions.LootItemFunctions$$Lambda/0x000001fa83ecd378.apply()6.40%
net.minecraft.world.level.storage.loot.functions.LootItemFunctions.lambda$compose$2()6.40%
net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction.apply()6.40%
net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction.apply()6.40%
net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction.run()6.78%
net.minecraft.world.item.MapItem.renderBiomePreviewMap()4.34%
net.minecraft.world.level.LevelReader.getBiome()4.31%
net.minecraft.world.level.biome.BiomeManager.getBiome()4.31%
net.minecraft.world.level.LevelReader.getNoiseBiome()4.33%
net.minecraft.server.level.ServerLevel.getUncachedNoiseBiome()4.18%
net.minecraft.world.level.biome.MultiNoiseBiomeSource.getNoiseBiome()4.18%
net.minecraft.world.level.biome.Climate$Sampler.sample()3.23%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.compute()1.79%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.compute()1.79%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.compute()1.93%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()1.90%
net.minecraft.world.level.levelgen.DensityFunctions$Spline.compute()1.89%
net.minecraft.util.CubicSpline$Multipoint.apply()1.98%
net.minecraft.util.CubicSpline$Multipoint.apply()1.38%
net.minecraft.util.CubicSpline$Multipoint.apply()0.99%
net.minecraft.world.level.levelgen.DensityFunctions$Spline$Coordinate.apply()0.97%
net.minecraft.world.level.levelgen.DensityFunctions$Spline$Coordinate.apply()0.97%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()0.97%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()0.96%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()0.95%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()0.92%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()0.92%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftedNoise.compute()0.97%
net.minecraft.world.level.levelgen.DensityFunction$NoiseHolder.getValue()0.39%
net.minecraft.world.level.levelgen.synth.NormalNoise.getValue()0.39%
net.minecraft.world.level.levelgen.synth.PerlinNoise.getValue()0.39%
net.minecraft.world.level.levelgen.synth.PerlinNoise.getValue()0.41%
net.minecraft.world.level.levelgen.synth.ImprovedNoise.noise()0.36%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftA.compute()0.30%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftNoise.compute()0.30%
net.minecraft.world.level.levelgen.DensityFunction$NoiseHolder.getValue()0.30%
net.minecraft.world.level.levelgen.synth.NormalNoise.getValue()0.30%
net.minecraft.world.level.levelgen.synth.PerlinNoise.getValue()0.30%
net.minecraft.world.level.levelgen.synth.PerlinNoise.getValue()0.32%
net.minecraft.world.level.levelgen.synth.ImprovedNoise.noise()0.24%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftB.compute()0.28%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftNoise.compute()0.28%
net.minecraft.world.level.levelgen.DensityFunction$NoiseHolder.getValue()0.26%
net.minecraft.world.level.levelgen.synth.NormalNoise.getValue()0.26%
net.minecraft.world.level.levelgen.synth.PerlinNoise.getValue()0.26%
net.minecraft.world.level.levelgen.synth.PerlinNoise.getValue()0.28%
net.minecraft.world.level.levelgen.synth.ImprovedNoise.noise()0.24%
net.minecraft.util.CubicSpline$Multipoint.findIntervalStart()0.02%
net.minecraft.world.level.levelgen.DensityFunctions$Spline$Coordinate.apply()0.38%
net.minecraft.util.CubicSpline$Multipoint.findIntervalStart()0.02%
net.minecraft.world.level.levelgen.DensityFunctions$Spline$Coordinate.apply()0.66%
net.minecraft.util.CubicSpline$Multipoint.findIntervalStart()0.02%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftedNoise.compute()1.55%
net.minecraft.world.level.levelgen.DensityFunction$NoiseHolder.getValue()0.64%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftB.compute()0.47%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftA.compute()0.43%
net.minecraft.world.level.biome.MultiNoiseBiomeSource.getNoiseBiome()1.18%
net.minecraft.world.level.Level.getChunk()0.13%
net.minecraft.world.level.chunk.ImposterProtoChunk.getNoiseBiome()0.02%
net.minecraft.core.Holder$Reference.is()0.02%
net.minecraft.server.level.ServerLevel.findNearestMapStructure()2.82%
net.minecraft.world.level.chunk.ChunkGenerator.findNearestMapStructure()2.82%
net.minecraft.world.level.chunk.ChunkGenerator.getNearestGeneratedStructure()3.12%
net.minecraft.world.level.chunk.ChunkGenerator.getStructureGeneratingAt()3.13%
net.minecraft.world.level.StructureManager.checkStructurePresence()2.88%
net.minecraft.world.level.levelgen.structure.StructureCheck.checkStart()2.97%
net.minecraft.world.level.levelgen.structure.StructureCheck.tryLoadFromStorage()1.78%
java.util.concurrent.CompletableFuture.join()1.67%
java.util.concurrent.CompletableFuture.waitingGet()1.67%
java.util.concurrent.ForkJoinPool.managedBlock()1.67%
java.util.concurrent.ForkJoinPool.unmanagedBlock()1.67%
java.util.concurrent.CompletableFuture$Signaller.block()1.67%
java.util.concurrent.locks.LockSupport.park()1.67%
jdk.internal.misc.Unsafe.park()1.67%
net.minecraft.nbt.visitors.CollectFields.<init>()0.07%
net.minecraft.world.level.chunk.storage.IOWorker.scanChunk()0.03%
it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap.computeIfAbsent()1.16%
net.minecraft.world.level.levelgen.structure.StructureCheck$$Lambda/0x000001fa846fa950.get()1.16%
net.minecraft.world.level.levelgen.structure.StructureCheck.lambda$checkStart$1()1.16%
net.minecraft.world.level.levelgen.structure.StructureCheck.canCreateStructure()1.16%
net.minecraft.world.level.levelgen.structure.Structure.findValidGenerationPoint()1.16%
net.minecraft.world.level.levelgen.structure.structures.BuriedTreasureStructure.findGenerationPoint()1.15%
net.minecraft.world.level.levelgen.structure.Structure.onTopOfChunkCenter()1.15%
net.minecraft.world.level.chunk.ChunkGenerator.getFirstOccupiedHeight()1.15%
net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.getBaseHeight()1.15%
net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.iterateNoiseColumn()1.19%
net.minecraft.world.level.levelgen.NoiseChunk.<init>()0.71%
net.minecraft.world.level.levelgen.NoiseRouter.mapAll()0.59%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.65%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.64%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.64%
net.minecraft.world.level.levelgen.DensityFunctions$Mapped.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$Mapped.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$MarkerOrMarked.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$BlendDensity.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$RangeChoice.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.39%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.38%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.38%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.37%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.37%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.28%
net.minecraft.world.level.levelgen.DensityFunctions$Mapped.mapAll()0.09%
net.minecraft.world.level.levelgen.DensityFunctions$MarkerOrMarked.mapAll()0.01%
net.minecraft.world.level.levelgen.DensityFunctions$RangeChoice.mapAll()0.01%
net.minecraft.world.level.levelgen.NoiseChunk$$Lambda/0x000001fa8463d800.apply()0.01%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.12%
net.minecraft.world.level.levelgen.DensityFunctions$MarkerOrMarked.mapAll()0.11%
net.minecraft.world.level.levelgen.NoiseChunk$$Lambda/0x000001fa8463d800.apply()0.01%
net.minecraft.world.level.levelgen.DensityFunctions$RangeChoice.mapAll()0.01%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftedNoise.mapAll()0.01%
net.minecraft.world.level.levelgen.DensityFunctions$MarkerOrMarked.mapAll()0.16%
net.minecraft.world.level.levelgen.NoiseChunk.getInterpolatedState()0.16%
net.minecraft.world.level.levelgen.NoiseChunk.initializeForFirstCellX()0.15%
net.minecraft.world.level.levelgen.NoiseChunk.advanceCellX()0.13%
net.minecraft.world.level.levelgen.NoiseChunk.selectCellYZ()0.05%
net.minecraft.world.level.levelgen.NoiseChunk.updateForX()0.01%
java.util.Optional.filter()0.01%
java.util.HashMap.computeIfAbsent()0.02%
it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap.get()0.02%
net.minecraft.world.level.LevelReader.getChunk()0.25%
net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement.getPotentialStructureChunk()0.01%
net.minecraft.world.item.MapItem.create()0.01%
net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction.apply()3.66%
net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction.apply()3.66%
net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction.run()3.68%
net.minecraft.server.level.ServerLevel.findNearestMapStructure()3.52%
net.minecraft.world.level.chunk.ChunkGenerator.findNearestMapStructure()3.52%
net.minecraft.world.level.chunk.ChunkGenerator.getNearestGeneratedStructure()3.87%
net.minecraft.world.level.chunk.ChunkGenerator.getStructureGeneratingAt()3.99%
net.minecraft.world.level.StructureManager.checkStructurePresence()2.47%
net.minecraft.world.level.levelgen.structure.StructureCheck.checkStart()2.55%
it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap.computeIfAbsent()1.59%
net.minecraft.world.level.levelgen.structure.StructureCheck$$Lambda/0x000001fa846fa950.get()1.59%
net.minecraft.world.level.levelgen.structure.StructureCheck.lambda$checkStart$1()1.59%
net.minecraft.world.level.levelgen.structure.StructureCheck.canCreateStructure()1.59%
net.minecraft.world.level.levelgen.structure.Structure.findValidGenerationPoint()1.59%
net.minecraft.world.level.levelgen.structure.structures.JigsawStructure.findGenerationPoint()1.57%
net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement.addPieces()1.57%
net.minecraft.world.level.chunk.ChunkGenerator.getFirstFreeHeight()1.44%
net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.getBaseHeight()1.44%
net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.iterateNoiseColumn()1.49%
net.minecraft.world.level.levelgen.NoiseChunk.<init>()0.84%
net.minecraft.world.level.levelgen.NoiseRouter.mapAll()0.71%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.76%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.76%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.76%
net.minecraft.world.level.levelgen.DensityFunctions$Mapped.mapAll()0.54%
net.minecraft.world.level.levelgen.DensityFunctions$Mapped.mapAll()0.54%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.54%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.54%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.54%
net.minecraft.world.level.levelgen.DensityFunctions$MarkerOrMarked.mapAll()0.54%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.54%
net.minecraft.world.level.levelgen.DensityFunctions$BlendDensity.mapAll()0.54%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.53%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.53%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.53%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.53%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.53%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.53%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.53%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.53%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.53%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.53%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.53%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.53%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.53%
net.minecraft.world.level.levelgen.DensityFunctions$RangeChoice.mapAll()0.53%
net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder.mapAll()0.52%
net.minecraft.world.level.levelgen.NoiseChunk$$Lambda/0x000001fa8463d800.apply()0.01%
net.minecraft.world.level.levelgen.NoiseChunk$$Lambda/0x000001fa8463d800.apply()0.01%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.mapAll()0.13%
net.minecraft.world.level.levelgen.DensityFunctions$MarkerOrMarked.mapAll()0.08%
net.minecraft.world.level.levelgen.DensityFunctions$RangeChoice.mapAll()0.01%
net.minecraft.world.level.levelgen.DensityFunctions$MarkerOrMarked.mapAll()0.17%
net.minecraft.world.level.levelgen.DensityFunctions.add()0.01%
net.minecraft.world.level.levelgen.NoiseChunk.getInterpolatedState()0.21%
net.minecraft.world.level.levelgen.NoiseChunk.advanceCellX()0.20%
net.minecraft.world.level.levelgen.NoiseChunk.initializeForFirstCellX()0.19%
net.minecraft.world.level.levelgen.NoiseChunk.selectCellYZ()0.04%
net.minecraft.world.level.levelgen.NoiseChunk.updateForY()0.02%
net.minecraft.world.level.levelgen.NoiseChunk.updateForX()0.01%
net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement.getBoundingBox()0.13%
net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement.addPieces()0.01%
java.lang.invoke.Invokers$Holder.linkToTargetMethod()0.01%
java.util.Optional.filter()0.01%
net.minecraft.world.level.levelgen.structure.StructureCheck.tryLoadFromStorage()0.96%
net.minecraft.world.level.LevelReader.getChunk()1.50%
net.minecraft.world.item.MapItem.renderBiomePreviewMap()0.16%
net.minecraft.world.item.ItemStack.<init>()0.01%
net.neoforged.neoforge.common.CommonHooks.modifyLoot()0.04%
net.minecraft.world.level.storage.loot.LootTable.shuffleAndSplitItems()0.01%
net.minecraft.world.level.storage.loot.LootContext$Builder.withOptionalRandomSeed()0.01%
net.minecraft.server.ReloadableServerRegistries$Holder.getLootTable()0.01%
net.minecraft.world.level.block.entity.BaseContainerBlockEntity.getItem()0.78%
net.minecraft.world.ticks.ContainerSingleItem.getItem()0.01%
com.progwml6.ironchest.common.block.regular.entity.CrystalChestBlockEntity.getItem()0.00%
net.neoforged.neoforge.items.wrapper.InvWrapper.getSlots()0.62%
net.fxnt.fxntstorage.reserve_storage.ReserveStorageBoxAutomationHandler.getStackInSlot()0.01%
net.neoforged.neoforge.items.wrapper.SidedInvWrapper.getSlots()0.01%
net.p3pp3rf1y.sophisticatedcore.inventory.CachedFailedInsertInventoryHandler.getSlots()0.01%
net.neoforged.neoforge.items.wrapper.SidedInvWrapper.getStackInSlot()0.00%
net.neoforged.neoforge.items.wrapper.CombinedInvWrapper.getStackInSlot()0.00%
com.qiqi.li.living.container.SimpleContainerContext.getSize()1.60%
com.qiqi.li.living.api.LivingItemManager.isLivingItem()0.70%
com.qiqi.li.living.perf.PerfMetrics.recordPhase()0.30%
com.qiqi.li.living.domain.hopper.LivingHopperFunction.tick()0.13%
com.qiqi.li.living.domain.water.LivingWaterBucketFunction.tickContainerData()0.09%
com.qiqi.li.living.domain.redstone.LivingRedstoneLampFunction.tickContainerData()0.09%
com.qiqi.li.living.domain.redstone.LivingRedstoneTorchFunction.tickContainerData()0.09%
com.qiqi.li.living.domain.furnace.LivingFurnaceFunction.tick()0.07%
java.util.ArrayList.sort()0.05%
com.qiqi.li.living.domain.water.LivingWaterBucketFunction.tick()0.05%
com.qiqi.li.living.container.SimpleContainerContext.flushDirtySlots()0.04%
com.qiqi.li.living.domain.water.LivingWaterWheelFunction.tickContainerData()0.03%
net.minecraft.world.level.block.entity.BlockEntity.setData()0.03%
com.qiqi.li.living.container.TickContext.<init>()0.03%
java.util.HashMap.computeIfAbsent()0.02%
java.util.HashMap.remove()0.01%
java.util.HashMap.put()0.01%
com.qiqi.li.living.domain.redstone.LivingRepeaterFunction.tickContainerData()0.01%
com.qiqi.li.living.domain.redstone.LivingRepeaterFunction.tick()0.01%
com.qiqi.li.living.container.ContainerLivingItemHandler.updateStressOutput()0.01%
com.qiqi.li.living.api.LivingItemManager.getApplicableFunctions()0.01%
com.qiqi.li.living.perf.PerfMetrics.printReport()0.01%
com.qiqi.li.living.container.SimpleContainerContext.<init>()1.00%
com.qiqi.li.living.container.ContainerLivingItemHandler.findDoubleChestPositions()0.26%
java.util.IdentityHashMap.put()0.16%
net.minecraft.world.level.Level.getBlockEntity()0.08%
java.util.HashSet.add()0.02%
net.neoforged.neoforge.common.extensions.ILevelExtension.getCapability()2.47%
com.qiqi.li.living.container.ContainerChunkCache.getCachedChunks()0.54%
it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap$ValueIterator.next()0.42%
net.minecraft.server.level.ServerChunkCache.getChunkNow()0.19%
java.util.HashMap$KeyIterator.next()0.09%
com.qiqi.li.living.container.ContainerChunkCache.getCacheSize()0.01%
org.apache.logging.slf4j.Log4jLogger.warn()0.01%
java.util.HashSet.iterator()0.01%
org.apache.logging.slf4j.Log4jLogger.debug()0.00%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContainer()0.57%
java.util.IdentityHashMap.clear()0.02%
java.util.Collections$UnmodifiableCollection$1.hasNext()0.01%
java.util.HashSet.clear()0.01%
java.lang.invoke.LambdaForm$MH/0x000001fa81005400.invoke()0.03%
net.neoforged.bus.EventListenerFactory$onServerTick/0x000001fa823e9400.invoke()0.10%
net.neoforged.bus.EventListenerFactory$onServerTick/0x000001fa82ffd000.invoke()0.05%
net.neoforged.bus.EventListenerFactory$postServerTick/0x000001fa81fe3800.invoke()0.04%
net.neoforged.bus.EventListenerFactory$postServerTick/0x000001fa82009400.invoke()0.04%
net.neoforged.bus.ConsumerEventHandler.invoke()0.03%
net.neoforged.bus.EventListenerFactory$onTickEnd/0x000001fa84078800.invoke()0.03%
net.neoforged.bus.EventListenerFactory$event/0x000001fa82764400.invoke()0.02%
net.neoforged.bus.EventListenerFactory$postServerTick/0x000001fa820ae400.invoke()0.01%
net.neoforged.bus.EventListenerFactory$serverTick/0x000001fa82e78000.invoke()0.01%
net.neoforged.bus.EventListenerFactory$onServerTickPost/0x000001fa830a4c00.invoke()0.01%
net.neoforged.bus.EventListenerFactory$onServerTickPost/0x000001fa830aa400.invoke()0.01%
net.neoforged.bus.EventListenerFactory$onServerTick/0x000001fa8274d400.invoke()0.01%
net.neoforged.bus.EventListenerFactory$onServerTickPost/0x000001fa830a5400.invoke()0.00%
net.neoforged.bus.EventListenerFactory$onServerTickPost/0x000001fa830a4000.invoke()0.00%
net.neoforged.bus.EventListenerFactory$onServerTickPost/0x000001fa83099400.invoke()0.00%
net.neoforged.bus.EventListenerFactory$onServerTickPost/0x000001fa830a4400.invoke()0.00%
net.neoforged.bus.EventBus.getListenerList()0.00%
net.neoforged.neoforge.event.EventHooks.canCreateFluidSource()10.27%
net.neoforged.neoforge.common.CommonHooks.onItemRightClick()3.60%
net.neoforged.neoforge.event.EventHooks.checkMobDespawn()2.33%
net.neoforged.neoforge.event.EventHooks.fireEntityTickPost()2.20%
net.neoforged.neoforge.common.CommonHooks.onInteractEntityAt()1.76%
net.neoforged.neoforge.registries.DeferredHolder.get()1.35%
net.neoforged.neoforge.network.handling.MainThreadPayloadHandler$$Lambda/0x000001fa8420c740.run()1.14%
net.neoforged.neoforge.event.EventHooks.fireEntityTickPre()1.00%
net.neoforged.neoforge.fluids.FluidInteractionRegistry.canInteract()0.92%
net.neoforged.neoforge.registries.DeferredHolder.value()0.51%
net.neoforged.neoforge.event.EventHooks.fireLevelTickPost()0.44%
net.neoforged.neoforge.event.EventHooks.firePlayerTickPre()0.29%
net.neoforged.neoforge.common.CommonHooks.onLivingBreathe()0.28%
net.neoforged.neoforge.event.EventHooks.firePlayerTickPost()0.24%
net.neoforged.neoforge.event.EventHooks.fireServerTickPre()0.22%
net.neoforged.neoforge.event.EventHooks.fireLevelTickPre()0.20%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.collisionExtendsVertically()0.19%
net.neoforged.neoforge.common.extensions.IFluidStateExtension.getFluidType()0.17%
net.neoforged.neoforge.common.util.DataComponentUtil.wrapEncodingExceptions()0.16%
net.neoforged.neoforge.common.extensions.IEntityExtension.isInFluidType()0.15%
net.neoforged.neoforge.registries.BaseMappedRegistry.resolve()0.15%
net.neoforged.neoforge.event.EventHooks.firePlayerLoggedIn()0.15%
net.neoforged.neoforge.event.EventHooks.getPotentialSpawns()0.14%
net.neoforged.neoforge.common.CommonHooks.onChunkUnload()0.14%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.getFriction()0.14%
net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingChunk()0.12%
net.neoforged.neoforge.common.extensions.IEntityExtension.isPushedByFluid()0.12%
net.neoforged.neoforge.common.extensions.IEntityExtension.getClassification()0.10%
net.neoforged.neoforge.event.EventHooks.canEntityGrief()0.07%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.getAdjacentBlockPathType()0.06%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.getLightEmission()0.06%
net.neoforged.neoforge.common.CommonHooks.isLivingOnLadder()0.06%
net.neoforged.neoforge.common.extensions.IEntityExtension.isInFluidType()0.04%
net.neoforged.neoforge.registries.BaseMappedRegistry.resolve()0.03%
net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn()0.03%
net.neoforged.neoforge.fluids.FluidType.isAir()0.03%
net.neoforged.neoforge.common.CommonHooks.onLivingFall()0.03%
net.neoforged.neoforge.server.command.CommandHelper.mergeCommandNode()0.03%
net.neoforged.neoforge.event.EventHooks.onStatAward()0.03%
net.neoforged.neoforge.event.EventHooks.onLivingHeal()0.03%
net.neoforged.neoforge.common.extensions.IItemStackExtension.onEntityItemUpdate()0.02%
net.neoforged.neoforge.common.extensions.IAbstractMinecartExtension.getCurrentRailPosition()0.02%
net.neoforged.neoforge.common.extensions.IItemStackExtension.getAllEnchantments()0.02%
net.neoforged.neoforge.event.EventHooks.onPlaySoundAtPosition()0.02%
net.neoforged.neoforge.common.extensions.IDataComponentHolderExtension.has()0.02%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.onNeighborChange()0.02%
net.neoforged.neoforge.common.extensions.IDataComponentHolderExtension.get()0.02%
net.neoforged.neoforge.common.extensions.ILevelReaderExtension.isAreaLoaded()0.02%
net.neoforged.neoforge.common.CommonHooks.handleBlockDrops()0.02%
net.neoforged.neoforge.common.extensions.IEntityExtension.getFluidMotionScale()0.02%
net.neoforged.neoforge.common.extensions.ILevelExtension.getCapability()0.02%
net.neoforged.neoforge.event.EventHooks.onStartEntityTracking()0.02%
net.neoforged.neoforge.common.CommonHooks.modifyLoot()0.02%
net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent$MergedSpawnPredicate$$Lambda/0x000001fa83541f68.test()0.02%
net.neoforged.neoforge.event.EventHooks.fireChunkTicketLevelUpdated()0.01%
net.neoforged.neoforge.client.model.data.ModelDataManager.onChunkUnload()0.01%
net.neoforged.neoforge.event.EventHooks.fireChunkWatch()0.01%
net.neoforged.neoforge.event.EventHooks.onNeighborNotify()0.01%
net.neoforged.neoforge.capabilities.EntityCapability.getCapability()0.01%
net.neoforged.neoforge.event.EventHooks.getEntitySizeForge()0.01%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.canSustainPlant()0.01%
net.neoforged.neoforge.server.ServerLifecycleHooks.handleServerStarted()0.01%
net.neoforged.neoforge.common.CommonHooks.fireCropGrowPost()0.01%
net.neoforged.neoforge.common.extensions.IFluidStateExtension.getAdjacentBlockPathType()0.01%
net.neoforged.neoforge.common.CommonHooks.onEntityEnterSection()0.01%
net.neoforged.neoforge.common.extensions.IServerCommonPacketListenerExtension.send()0.01%
net.neoforged.neoforge.fluids.FluidType.isVanilla()0.01%
net.neoforged.neoforge.common.CommonHooks.getEntityVisibilityMultiplier()0.01%
net.neoforged.neoforge.attachment.AttachmentInternals.copyChunkAttachmentsOnPromotion()0.01%
net.neoforged.neoforge.common.world.chunk.ForcedChunkManager.hasForcedChunks()0.01%
net.neoforged.neoforge.common.CommonHooks.onEntityIncomingDamage()0.01%
net.neoforged.neoforge.event.EventHooks.checkSpawnPlacements()0.01%
net.neoforged.neoforge.common.extensions.IItemExtension.getMaxStackSize()0.01%
net.neoforged.neoforge.common.util.DataComponentUtil.wrapEncodingExceptions()0.01%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.getFlammability()0.01%
net.neoforged.neoforge.network.configuration.ICustomConfigurationTask.start()0.01%
net.neoforged.neoforge.items.wrapper.InvWrapper.getStackInSlot()0.01%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.getBubbleColumnDirection()0.01%
net.neoforged.neoforge.common.CommonHooks.onPlaceItemIntoWorld()0.00%
net.neoforged.neoforge.common.advancements.critereon.PiglinCurrencyItemPredicate.matches()0.00%
net.neoforged.neoforge.common.CommonHooks.onLivingJump()0.00%
net.neoforged.neoforge.attachment.AttachmentHolder.serializeAttachments()0.00%
net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager.sendLightDataTo()0.00%
net.neoforged.neoforge.common.extensions.ICommonPacketListener.hasChannel()0.00%
net.neoforged.neoforge.common.LenientUnboundedMapCodec.encode()0.00%
net.neoforged.neoforge.event.EventHooks.fireChunkSent()0.00%
net.neoforged.neoforge.server.ServerLifecycleHooks.handleServerAboutToStart()0.00%
net.neoforged.neoforge.server.ServerLifecycleHooks.handleServerStarting()0.00%
living_item (v1.3.0)
Server thread27.15%
com.qiqi.li.LivingItem.onServerTick()19.83%
com.qiqi.li.LivingItem.processLevelContainers()24.18%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContainerAt()23.00%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContext()22.05%
com.qiqi.li.living.container.SimpleContainerContext.getItem()18.89%
net.neoforged.neoforge.items.wrapper.InvWrapper.getStackInSlot()18.21%
net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity.getItem()20.22%
net.minecraft.world.RandomizableContainer.unpackLootTable()19.44%
net.minecraft.world.level.storage.loot.LootTable.fill()22.21%
net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity.setItem()13.72%
net.minecraft.world.level.block.entity.BaseContainerBlockEntity.setItem()13.72%
net.minecraft.world.level.block.entity.BlockEntity.setChanged()13.72%
net.minecraft.world.level.block.entity.BlockEntity.setChanged()13.72%
net.minecraft.world.level.Level.updateNeighbourForOutputSignal()15.31%200250ms
net.minecraft.world.level.Level.getBlockState()17.02%
net.minecraft.world.level.Level.getChunk()17.01%
net.minecraft.world.level.LevelReader.getChunk()17.01%
net.minecraft.world.level.Level.getChunk()17.01%
net.minecraft.server.level.ServerChunkCache.getChunk()17.01%
net.minecraft.server.level.ServerChunkCache$MainThreadExecutor.managedBlock()17.01%
net.minecraft.util.thread.BlockableEventLoop.managedBlock()18.47%
net.minecraft.util.thread.BlockableEventLoop.waitForTasks()17.44%
java.util.concurrent.locks.LockSupport.parkNanos()16.95%
jdk.internal.misc.Unsafe.park()16.95%
java.lang.Thread.yield()0.49%
net.minecraft.server.level.ServerChunkCache$MainThreadExecutor.pollTask()2.69%
net.minecraft.world.level.chunk.LevelChunk.getBlockState()0.01%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.onNeighborChange()0.01%
net.minecraft.world.level.storage.loot.LootTable.getRandomItems()8.48%
net.minecraft.world.level.storage.loot.LootTable.shuffleAndSplitItems()0.01%
net.minecraft.world.level.storage.loot.LootContext$Builder.withOptionalRandomSeed()0.01%
net.minecraft.server.ReloadableServerRegistries$Holder.getLootTable()0.01%
net.minecraft.world.level.block.entity.BaseContainerBlockEntity.getItem()0.78%
net.minecraft.world.ticks.ContainerSingleItem.getItem()0.01%
com.progwml6.ironchest.common.block.regular.entity.CrystalChestBlockEntity.getItem()0.00%
net.neoforged.neoforge.items.wrapper.InvWrapper.getSlots()0.62%
net.fxnt.fxntstorage.reserve_storage.ReserveStorageBoxAutomationHandler.getStackInSlot()0.01%
net.neoforged.neoforge.items.wrapper.SidedInvWrapper.getSlots()0.01%
net.p3pp3rf1y.sophisticatedcore.inventory.CachedFailedInsertInventoryHandler.getSlots()0.01%
net.neoforged.neoforge.items.wrapper.SidedInvWrapper.getStackInSlot()0.00%
net.neoforged.neoforge.items.wrapper.CombinedInvWrapper.getStackInSlot()0.00%
com.qiqi.li.living.container.SimpleContainerContext.getSize()1.60%
com.qiqi.li.living.api.LivingItemManager.isLivingItem()0.70%
com.qiqi.li.living.perf.PerfMetrics.recordPhase()0.30%
com.qiqi.li.living.domain.hopper.LivingHopperFunction.tick()0.13%
com.qiqi.li.living.domain.water.LivingWaterBucketFunction.tickContainerData()0.09%
com.qiqi.li.living.domain.redstone.LivingRedstoneLampFunction.tickContainerData()0.09%
com.qiqi.li.living.domain.redstone.LivingRedstoneTorchFunction.tickContainerData()0.09%
com.qiqi.li.living.domain.furnace.LivingFurnaceFunction.tick()0.07%
java.util.ArrayList.sort()0.05%
com.qiqi.li.living.domain.water.LivingWaterBucketFunction.tick()0.05%
com.qiqi.li.living.container.SimpleContainerContext.flushDirtySlots()0.04%
com.qiqi.li.living.domain.water.LivingWaterWheelFunction.tickContainerData()0.03%
net.minecraft.world.level.block.entity.BlockEntity.setData()0.03%
com.qiqi.li.living.container.TickContext.<init>()0.03%
java.util.HashMap.computeIfAbsent()0.02%
java.util.HashMap.remove()0.01%
java.util.HashMap.put()0.01%
com.qiqi.li.living.domain.redstone.LivingRepeaterFunction.tickContainerData()0.01%
com.qiqi.li.living.domain.redstone.LivingRepeaterFunction.tick()0.01%
com.qiqi.li.living.container.ContainerLivingItemHandler.updateStressOutput()0.01%
com.qiqi.li.living.api.LivingItemManager.getApplicableFunctions()0.01%
com.qiqi.li.living.perf.PerfMetrics.printReport()0.01%
com.qiqi.li.living.container.SimpleContainerContext.<init>()1.00%
com.qiqi.li.living.container.ContainerLivingItemHandler.findDoubleChestPositions()0.26%
java.util.IdentityHashMap.put()0.16%
net.minecraft.world.level.Level.getBlockEntity()0.08%
java.util.HashSet.add()0.02%
net.neoforged.neoforge.common.extensions.ILevelExtension.getCapability()2.47%
com.qiqi.li.living.container.ContainerChunkCache.getCachedChunks()0.54%
it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap$ValueIterator.next()0.42%
net.minecraft.server.level.ServerChunkCache.getChunkNow()0.19%
java.util.HashMap$KeyIterator.next()0.09%
com.qiqi.li.living.container.ContainerChunkCache.getCacheSize()0.01%
org.apache.logging.slf4j.Log4jLogger.warn()0.01%
java.util.HashSet.iterator()0.01%
org.apache.logging.slf4j.Log4jLogger.debug()0.00%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContainer()0.58%
java.util.IdentityHashMap.clear()0.02%
java.util.Collections$UnmodifiableCollection$1.hasNext()0.01%
java.util.HashSet.clear()0.01%
com.qiqi.li.living.domain.map.LivingMapEventHandler.onRightClickItem()3.74%
com.qiqi.li.living.domain.map.ItemFrameMapTeleportHandler.onEntityInteractSpecific()1.76%
com.qiqi.li.LivingItem$$Lambda/0x000001fa83d194e8.handle()1.12%
com.qiqi.li.living.api.LivingItemManager.getIgnoredComponentTypes()0.33%
com.qiqi.li.living.api.LivingItemManager.isLivingItem()0.18%
com.qiqi.li.living.container.ContainerChunkCache.onChunkLoad()0.07%
com.qiqi.li.living.domain.hopper.LivingHopperData.equals()0.06%
com.qiqi.li.LivingItem.onChunkUnload()0.02%
com.qiqi.li.living.domain.map.LivingMapEventHandler.onPlayerTick()0.02%
com.qiqi.li.living.domain.redstone.LivingComparatorData.equals()0.01%
sable (v2.0.3)
Server thread10.23%
sable_rapier
Server thread5.19%
ars_nouveau (v5.12.1)
Server thread5.16%
twilightforest (v4.8.3345)
Server thread4.04%
Registrate.MC1._21._67
Server thread1.14%
create (v6.0.10)
Server thread0.93%
curios (v9.5.1+1.21.1)
Server thread0.55%
ironchest (v1.21-neoforge-16.0.7)
Server thread0.41%
balm (v21.0.63)
Server thread0.37%
xaeroworldmap (v1.44.2)
Server thread0.36%
simulated (v1.3.0)
Server thread0.35%
ponder (v1.0.82+mc1.21.1)
Server thread0.30%
sophisticatedbackpacks (v3.25.69)
Server thread0.26%
ae2 (v19.2.17)
Server thread0.21%
sophisticatedcore (v1.4.72)
Server thread0.20%
architectury (v13.0.8)
Server thread0.19%
avaritia (v1.3.1)
Server thread0.17%
cataclysm (v3.32)
Server thread0.16%
jdk.proxy3
Server thread0.16%
sablecompanion (v1.6.0)
Server thread0.15%
waystones (v21.1.38)
Server thread0.15%
veil (v4.1.4)
Server thread0.12%
aeronautics (v1.3.0)
Server thread0.12%
spark (v1.10.124)
Server thread0.10%
ftbchunks (v2101.1.20)
Server thread0.07%
ftbteams (v2101.1.10)
Server thread0.07%
create_aeronautics_toolgun (v0.3.4)
Server thread0.07%
ftblibrary (v2101.1.33)
Server thread0.06%
flywheel (v1.0.6)
Server thread0.05%
fxntstorage (v1.3.3)
Server thread0.05%
xaerominimap (v26.4.2)
Server thread0.04%
offroad (v1.3.0)
Server thread0.03%
pipez (v1.21.1-1.2.31)
Server thread0.03%
xaerolib (v1.7.1)
Server thread0.02%
geckolib (v4.9.2)
Server thread0.01%
refinedstorage (v2.0.9)
Server thread0.01%
jade (v15.10.5+neoforge)
Server thread0.01%
infiniverse (v2.0.1.0)
Server thread0.01%
nuggets (v1.1.0.48)
Server thread0.00%
Other
The following other mods are installed, but didn't show up in this profile. Yay!

inventorysorter (v24.0.24)
kuma_api (v21.0.8)
fabric_renderer_api_v1 (v3.4.1+9125b6dc19)
sodium_extra (v0.9.3+mc1.21.1)
customskinloader (v15.0.1)
jei (v19.38.0.366)
aeronautics_bundled (v1.3.0)
trender (v1.0.15)
naturescompass (v1.21.1-3.4.0-neoforge)
fabric_block_view_api_v2 (v1.0.10+9afaaf8c19)
iris (v1.8.14-beta.1+mc1.21.1)
midnightlib (v1.9.3)
guideme (v21.1.17)
explorerscompass (v1.21.1-3.4.0-neoforge)
waveycapes (v1.10.2)
resourcify (v1.8.5)
sodium (v0.8.12+mc1.21.1)
cloth_config (v15.0.140)
dungeons_arise (v2.1.68)
minecraft (v1.21.1)
fabric_api_base (v0.4.42+d1308ded19)
mousetweaks (v2.26.1)
blur (v6.3.1)
lionfishapi (v3.1)
kotlinforforge (v5.12.0)
wip (v21.1.2)
storagedrawers (v13.11.4)
lambdynlights_api (v4.5.1+1.21.1)
componentviewer (v1.3.3+1.21.1)
transition (v1.0.21)
fabric_rendering_data_attachment_v1 (v0.3.48+73761d2e19)
observable (v5.4.4)
spark and spark-viewer are free & open source on GitHub.
Copyright © 2018-2026 lucko & other spark contributors.
