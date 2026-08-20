spark

777_hi @ 02:26PM 8/20/2026, interval 4ms
Mods View
This view shows a filtered representation of the profile broken down by mod.

 Label: Percentage
The value displayed against each frame is the time divided by the total time as a percentage.

 Merge Mode: Merge
Method calls with the same signature will be merged together, even though they may not have been invoked by the same calling method.

neoforge (v21.1.230)
Server thread3.76%
net.neoforged.neoforge.event.EventHooks.canCreateFluidSource()1.17%
net.neoforged.neoforge.event.level.block.CreateFluidSourceEvent.<init>()0.91%
net.neoforged.neoforge.common.extensions.IFluidStateExtension.canConvertToSource()0.91%
net.minecraft.world.level.material.FlowingFluid.canConvertToSource()0.91%
net.minecraft.world.level.material.WaterFluid.canConvertToSource()0.91%
net.minecraft.world.level.GameRules.getBoolean()0.91%
net.minecraft.world.level.GameRules.getRule()0.91%
com.google.common.collect.RegularImmutableMap.get()0.91%
com.google.common.collect.RegularImmutableMap.get()0.91%
net.minecraft.world.level.material.LavaFluid.canConvertToSource()0.01%
net.neoforged.bus.EventBus.post()0.25%
net.neoforged.neoforge.common.CommonHooks.onItemRightClick()1.11%
net.neoforged.bus.EventBus.post()1.11%
net.neoforged.bus.EventBus.post()1.11%
net.neoforged.bus.SubscribeEventListener.invoke()1.11%
net.neoforged.bus.EventListenerFactory$onRightClickItem/0x0000020c1c665400.invoke()1.11%
java.lang.invoke.LambdaForm$MH/0x0000020c1b3b4000.invokeExact_MT()1.11%
java.lang.invoke.LambdaForm$MH/0x0000020c1b014c00.invoke()1.11%
java.lang.invoke.LambdaForm$DMH/0x0000020c1b003000.invokeStatic()1.11%
com.qiqi.li.living.domain.map.LivingMapEventHandler.onRightClickItem()1.11%
com.qiqi.li.living.domain.map.LivingMapEventHandler.handleLivingMapCreation()1.10%
net.minecraft.world.item.MapItem.renderBiomePreviewMap()1.10%
net.minecraft.world.level.LevelReader.getBiome()1.09%
net.minecraft.world.level.biome.BiomeManager.getBiome()1.09%
net.minecraft.world.level.LevelReader.getNoiseBiome()1.09%
net.minecraft.server.level.ServerLevel.getUncachedNoiseBiome()1.08%
net.minecraft.world.level.biome.MultiNoiseBiomeSource.getNoiseBiome()1.08%
net.minecraft.world.level.biome.Climate$Sampler.sample()0.87%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.compute()0.47%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.compute()0.47%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.compute()0.47%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()0.47%
net.minecraft.world.level.levelgen.DensityFunctions$Spline.compute()0.47%
net.minecraft.util.CubicSpline$Multipoint.apply()0.47%
net.minecraft.util.CubicSpline$Multipoint.apply()0.34%
net.minecraft.util.CubicSpline$Multipoint.apply()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$Spline$Coordinate.apply()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$Spline$Coordinate.apply()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftedNoise.compute()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftA.compute()0.07%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftNoise.compute()0.07%
net.minecraft.world.level.levelgen.DensityFunction$NoiseHolder.getValue()0.07%
net.minecraft.world.level.levelgen.synth.NormalNoise.getValue()0.07%
net.minecraft.world.level.levelgen.synth.PerlinNoise.getValue()0.07%
net.minecraft.world.level.levelgen.synth.PerlinNoise.getValue()0.07%
net.minecraft.world.level.levelgen.synth.ImprovedNoise.noise()0.07%
net.minecraft.world.level.levelgen.DensityFunction$NoiseHolder.getValue()0.07%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftB.compute()0.06%
net.minecraft.world.level.levelgen.DensityFunctions$Spline$Coordinate.apply()0.11%
net.minecraft.world.level.levelgen.DensityFunctions$Spline$Coordinate.apply()0.13%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftedNoise.compute()0.40%
net.minecraft.world.level.biome.MultiNoiseBiomeSource.getNoiseBiome()0.21%
net.minecraft.world.level.Level.getChunk()0.02%
net.minecraft.core.Holder$Reference.is()0.00%
net.minecraft.world.entity.player.Inventory.add()0.00%
net.minecraft.world.item.MapItem.create()0.00%
com.qiqi.li.living.domain.map.MapTeleportExecutor.execute()0.01%
net.neoforged.neoforge.event.EventHooks.fireServerTickPre()0.45%
net.neoforged.neoforge.registries.DeferredHolder.get()0.29%
net.neoforged.neoforge.event.EventHooks.fireServerTickPost()0.23%
net.neoforged.neoforge.event.EventHooks.fireEntityTickPre()0.05%
net.neoforged.neoforge.event.EventHooks.fireLevelTickPost()0.05%
net.neoforged.neoforge.event.EventHooks.checkMobDespawn()0.05%
net.neoforged.neoforge.event.EventHooks.fireLevelTickPre()0.04%
net.neoforged.neoforge.registries.DeferredHolder.value()0.02%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.getLightEmission()0.02%
net.neoforged.neoforge.event.EventHooks.firePlayerTickPost()0.02%
net.neoforged.neoforge.common.CommonHooks.onChunkUnload()0.02%
net.neoforged.neoforge.event.EventHooks.fireChunkTicketLevelUpdated()0.01%
net.neoforged.neoforge.common.CommonHooks.onLivingBreathe()0.01%
net.neoforged.neoforge.common.extensions.IEntityExtension.getClassification()0.01%
net.neoforged.neoforge.event.EventHooks.firePlayerTickPre()0.01%
net.neoforged.neoforge.common.CommonHooks.getEntityVisibilityMultiplier()0.01%
net.neoforged.neoforge.fluids.FluidInteractionRegistry.canInteract()0.01%
net.neoforged.neoforge.event.EventHooks.getEntitySizeForge()0.01%
net.neoforged.neoforge.common.NeoForgeEventHandler.onEntityJoinWorld()0.01%
net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent$MergedSpawnPredicate$$Lambda/0x0000020c1c4cbbb8.test()0.01%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.getAdjacentBlockPathType()0.01%
net.neoforged.neoforge.common.extensions.IEntityExtension.isInFluidType()0.01%
net.neoforged.neoforge.registries.BaseMappedRegistry.resolve()0.01%
net.neoforged.neoforge.common.NeoForgeEventHandler.onChunkUnload()0.01%
net.neoforged.neoforge.attachment.AttachmentInternals.copyChunkAttachmentsOnPromotion()0.01%
net.neoforged.neoforge.event.EventHooks.onStatAward()0.01%
net.neoforged.neoforge.common.extensions.IItemStackExtension.getAllEnchantments()0.01%
net.neoforged.neoforge.common.extensions.IItemStackExtension.onEntityItemUpdate()0.01%
net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn()0.01%
net.neoforged.neoforge.network.handling.MainThreadPayloadHandler$$Lambda/0x0000020c1cb00b38.run()0.01%
net.neoforged.neoforge.common.extensions.ILevelExtension.getCapability()0.00%
net.neoforged.neoforge.common.extensions.IServerCommonPacketListenerExtension.send()0.00%
net.neoforged.neoforge.common.world.chunk.ForcedChunkManager.hasForcedChunks()0.00%
net.neoforged.neoforge.event.EventHooks.fireEntityTickPost()0.00%
net.neoforged.neoforge.event.EventHooks.canEntityGrief()0.00%
net.neoforged.neoforge.event.EventHooks.onPlaySoundAtPosition()0.00%
net.neoforged.neoforge.common.CommonHooks.onEntityEnterSection()0.00%
net.neoforged.neoforge.fluids.FluidType.isAir()0.00%
net.neoforged.neoforge.common.extensions.IFluidStateExtension.getFluidType()0.00%
net.neoforged.neoforge.event.EventHooks.checkSpawnPlacements()0.00%
net.neoforged.neoforge.event.EventHooks.onStartEntityTracking()0.00%
net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager.sendLightDataTo()0.00%
net.neoforged.neoforge.common.CommonHooks.isLivingOnLadder()0.00%
net.neoforged.neoforge.event.EventHooks.fireChunkWatch()0.00%
net.neoforged.neoforge.common.extensions.ICommonPacketListener.hasChannel()0.00%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.getBlockPathType()0.00%
net.neoforged.neoforge.fluids.FluidType.isVanilla()0.00%
net.neoforged.neoforge.common.damagesource.DamageContainer.<init>()0.00%
net.neoforged.neoforge.common.CommonHooks.onLivingDamagePost()0.00%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.getFriction()0.00%
net.neoforged.neoforge.common.CommonHooks.onVanillaGameEvent()0.00%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.collisionExtendsVertically()0.00%
net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager.serializeNBT()0.00%
net.neoforged.neoforge.common.extensions.IBlockEntityExtension.invalidateCapabilities()0.00%
net.neoforged.neoforge.common.NeoForgeMod$$Lambda/0x0000020c1bf25560.accept()0.00%
net.neoforged.neoforge.common.CommonHooks.canCropGrow()0.00%
net.neoforged.neoforge.common.extensions.ILevelReaderExtension.isAreaLoaded()0.00%
net.neoforged.neoforge.common.CommonHooks.handleBlockDrops()0.00%
net.neoforged.neoforge.event.EventHooks.fireChunkSent()0.00%
net.neoforged.neoforge.event.level.ChunkDataEvent$Load.<init>()0.00%
net.neoforged.neoforge.common.CommonHooks.onInteractEntityAt()0.00%
net.neoforged.neoforge.common.advancements.critereon.PiglinCurrencyItemPredicate.matches()0.00%
entity-deserializer5.00%
net.neoforged.neoforge.event.EventHooks.fireServerTickPost()5.00%
net.neoforged.bus.EventBus.post()5.00%
net.neoforged.bus.EventBus.post()5.00%
net.neoforged.bus.EventListenerFactory$onServerTick/0x0000020c1c3da000.invoke()5.00%
java.lang.invoke.LambdaForm$MH/0x0000020c1b028000.invokeExact_MT()5.00%
java.lang.invoke.LambdaForm$MH/0x0000020c1cd66000.invoke()5.00%
java.lang.invoke.DirectMethodHandle$Holder.invokeVirtual()5.00%
com.qiqi.li.LivingItem.onServerTick()5.00%
com.qiqi.li.LivingItem.processLevelContainers()5.00%
living_item (v1.3.0)
Server thread1.31%
com.qiqi.li.living.domain.map.LivingMapEventHandler.onRightClickItem()1.11%
com.qiqi.li.living.domain.map.LivingMapEventHandler.handleLivingMapCreation()1.10%
net.minecraft.world.item.MapItem.renderBiomePreviewMap()1.10%
net.minecraft.world.level.LevelReader.getBiome()1.09%
net.minecraft.world.level.biome.BiomeManager.getBiome()1.09%
net.minecraft.world.level.LevelReader.getNoiseBiome()1.09%
net.minecraft.server.level.ServerLevel.getUncachedNoiseBiome()1.08%
net.minecraft.world.level.biome.MultiNoiseBiomeSource.getNoiseBiome()1.08%
net.minecraft.world.level.biome.Climate$Sampler.sample()0.87%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.compute()0.47%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.compute()0.47%
net.minecraft.world.level.levelgen.DensityFunctions$Ap2.compute()0.47%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()0.47%
net.minecraft.world.level.levelgen.DensityFunctions$Spline.compute()0.47%
net.minecraft.util.CubicSpline$Multipoint.apply()0.47%
net.minecraft.util.CubicSpline$Multipoint.apply()0.34%
net.minecraft.util.CubicSpline$Multipoint.apply()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$Spline$Coordinate.apply()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$Spline$Coordinate.apply()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$PureTransformer.compute()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftedNoise.compute()0.21%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftA.compute()0.07%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftNoise.compute()0.07%
net.minecraft.world.level.levelgen.DensityFunction$NoiseHolder.getValue()0.07%
net.minecraft.world.level.levelgen.synth.NormalNoise.getValue()0.07%
net.minecraft.world.level.levelgen.synth.PerlinNoise.getValue()0.07%
net.minecraft.world.level.levelgen.synth.PerlinNoise.getValue()0.07%
net.minecraft.world.level.levelgen.synth.ImprovedNoise.noise()0.07%
net.minecraft.world.level.levelgen.DensityFunction$NoiseHolder.getValue()0.07%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftB.compute()0.06%
net.minecraft.world.level.levelgen.DensityFunctions$Spline$Coordinate.apply()0.11%
net.minecraft.world.level.levelgen.DensityFunctions$Spline$Coordinate.apply()0.13%
net.minecraft.world.level.levelgen.DensityFunctions$ShiftedNoise.compute()0.40%
net.minecraft.world.level.biome.MultiNoiseBiomeSource.getNoiseBiome()0.21%
net.minecraft.world.level.Level.getChunk()0.02%
net.minecraft.core.Holder$Reference.is()0.00%
net.minecraft.world.entity.player.Inventory.add()0.00%
net.minecraft.world.item.MapItem.create()0.00%
com.qiqi.li.living.domain.map.MapTeleportExecutor.execute()0.01%
com.qiqi.li.LivingItem.onServerTick()0.16%
com.qiqi.li.living.container.ContainerChunkCache.onChunkLoad()0.02%36ms (living_item)
com.qiqi.li.living.api.LivingItemManager.isLivingItem()0.01%
com.qiqi.li.LivingItem.onChunkUnload()0.01%
com.qiqi.li.living.api.LivingItemManager.getIgnoredComponentTypes()0.01%
com.qiqi.li.LivingItem$$Lambda/0x0000020c1c7c33e8.handle()0.01%
com.qiqi.li.living.domain.map.ItemFrameMapTeleportHandler.onEntityInteractSpecific()0.00%
entity-deserializer5.00%
com.qiqi.li.LivingItem.onServerTick()5.00%
com.qiqi.li.LivingItem.processLevelContainers()5.00%
Registrate.MC1._21._67
Server thread0.30%
spark (v1.10.124)
Server thread0.25%
jdk.proxy3
Server thread0.13%
architectury (v13.0.8)
Server thread0.13%
create (v6.0.10)
Server thread0.10%
ponder (v1.0.82+mc1.21.1)
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
observable (v5.4.4)
flywheel (v1.0.4)
ironchest (v1.21-neoforge-16.0.7)
fabric_block_view_api_v2 (v1.0.10+9afaaf8c19)
spark and spark-viewer are free & open source on GitHub.
Copyright © 2018-2026 lucko & other spark contributors.
