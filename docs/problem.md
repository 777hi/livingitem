spark

777_hi @ 下午11:31 2026/8/19, interval 10ms
TPS
15.32
1m
18.07
5m
19.31
15m
MSPT
2.01
min
8.93
med
31.9
95%ile
17800
max
CPU(process)
39.67%
1m
7.23%
15m
Memory(process)
3.5 GB
/
6.5 GB
54.14%
CPU(system)
47.01%
1m
12.77%
15m
Mods View
This view shows a filtered representation of the profile broken down by mod.

 Label: Percentage
The value displayed against each frame is the time divided by the total time as a percentage.

 Merge Mode: Merge
Method calls with the same signature will be merged together, even though they may not have been invoked by the same calling method.

neoforge (v21.1.235)
Server thread8.14%
net.neoforged.neoforge.common.CommonHooks.onItemRightClick()4.56%
net.neoforged.bus.EventBus.post()4.56%
net.neoforged.bus.EventBus.post()4.56%
net.neoforged.bus.SubscribeEventListener.invoke()4.56%
net.neoforged.bus.EventListenerFactory$onRightClickItem/0x0000025c05888800.invoke()4.56%
java.lang.invoke.LambdaForm$MH/0x0000025c03430c00.invokeExact_MT()4.56%
java.lang.invoke.LambdaForm$MH/0x0000025c03014800.invoke()4.56%
java.lang.invoke.LambdaForm$DMH/0x0000025c03008000.invokeStatic()4.56%
com.qiqi.li.living.domain.map.LivingMapEventHandler.onRightClickItem()4.56%
com.qiqi.li.living.domain.map.MapTeleportExecutor.execute()4.10%
com.qiqi.li.living.domain.map.TeleportHelper.teleportToMapPosition()4.10%
com.qiqi.li.living.domain.map.TeleportHelper.executeTeleport()4.07%
net.minecraft.server.level.ServerPlayer.teleportTo()4.06%
net.minecraft.server.level.ServerPlayer.wrapMethod$cfb000$sable$teleportTo()4.06%
net.minecraft.server.level.ServerPlayer$$Lambda/0x0000025c066cc670.call()4.06%
net.minecraft.server.level.ServerPlayer.mixinextras$bridge$teleportTo$mixinextras$wrapped$213$214()4.06%
net.minecraft.server.level.ServerPlayer.teleportTo$mixinextras$wrapped$213()4.06%
net.minecraft.server.network.ServerGamePacketListenerImpl.teleport()4.06%
net.minecraft.world.entity.Entity.absMoveTo()4.06%
net.minecraft.world.entity.Entity.absMoveTo()4.06%
net.minecraft.world.entity.Entity.setPos()4.06%
net.minecraft.world.entity.Entity.setPosRaw()4.06%
net.minecraft.world.level.Level.getChunk()4.06%
net.minecraft.world.level.LevelReader.getChunk()4.06%
net.minecraft.world.level.Level.getChunk()4.06%
net.minecraft.server.level.ServerChunkCache.getChunk()4.06%
net.minecraft.server.level.ServerChunkCache$MainThreadExecutor.managedBlock()4.06%
net.minecraft.util.thread.BlockableEventLoop.managedBlock()4.06%
net.minecraft.util.thread.BlockableEventLoop.waitForTasks()3.93%
java.util.concurrent.locks.LockSupport.parkNanos()3.82%
jdk.internal.misc.Unsafe.park()3.82%
java.lang.Thread.yield()0.11%
net.minecraft.server.level.ServerChunkCache$MainThreadExecutor.pollTask()0.13%
com.qiqi.li.logging.ModLog.<clinit>()0.01%
com.qiqi.li.living.domain.map.TeleportHelper.getSafeYFromNoise()0.04%
com.qiqi.li.living.domain.map.LivingMapEventHandler.handleLivingMapCreation()0.45%
net.neoforged.bus.EventListenerFactory$eventPlayerInteractEvent/0x0000025c047ac800.invoke()0.01%
net.neoforged.neoforge.event.EventHooks.canCreateFluidSource()0.90%
net.neoforged.neoforge.event.EventHooks.fireServerTickPost()0.60%
net.neoforged.neoforge.fluids.FluidInteractionRegistry.canInteract()0.48%
net.neoforged.neoforge.event.EventHooks.fireEntityTickPost()0.29%
net.neoforged.neoforge.event.EventHooks.fireLevelTickPost()0.25%
net.neoforged.neoforge.event.EventHooks.fireServerTickPre()0.11%
net.neoforged.neoforge.event.EventHooks.firePlayerLoggedIn()0.09%
net.neoforged.neoforge.server.ServerLifecycleHooks.handleServerStarted()0.08%
net.neoforged.neoforge.event.EventHooks.firePlayerTickPost()0.08%
net.neoforged.neoforge.common.CommonHooks.onChunkUnload()0.07%
net.neoforged.neoforge.event.EventHooks.firePlayerTickPre()0.07%
net.neoforged.neoforge.event.EventHooks.fireEntityTickPre()0.06%
net.neoforged.neoforge.event.EventHooks.fireLevelTickPre()0.05%
net.neoforged.neoforge.event.EventHooks.checkMobDespawn()0.05%
net.neoforged.neoforge.common.extensions.IEntityExtension.isInFluidType()0.03%
net.neoforged.neoforge.registries.BaseMappedRegistry.resolve()0.02%
net.neoforged.neoforge.capabilities.EntityCapability.getCapability()0.02%
net.neoforged.neoforge.server.command.CommandHelper.mergeCommandNode()0.02%
net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager.sendLightDataTo()0.02%
net.neoforged.neoforge.common.NeoForgeEventHandler.onChunkUnload()0.02%
net.neoforged.neoforge.event.EventHooks.getPotentialSpawns()0.02%
net.neoforged.neoforge.event.EventHooks.onLivingHeal()0.02%
net.neoforged.neoforge.common.CommonHooks.getEntityVisibilityMultiplier()0.02%
net.neoforged.neoforge.common.CommonHooks.isLivingOnLadder()0.02%
net.neoforged.neoforge.common.NeoForgeMod$$Lambda/0x0000025c040c59a0.accept()0.01%
net.neoforged.neoforge.network.handling.MainThreadPayloadHandler$$Lambda/0x0000025c062197b8.run()0.01%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.getLightEmission()0.01%
net.neoforged.neoforge.event.EventHooks.fireChunkTicketLevelUpdated()0.01%
net.neoforged.neoforge.network.configuration.ICustomConfigurationTask.start()0.01%
net.neoforged.neoforge.attachment.AttachmentSync.syncInitialPlayerAttachments()0.01%
net.neoforged.neoforge.attachment.AttachmentHolder.deserializeAttachments()0.01%
net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingChunk()0.01%
net.neoforged.neoforge.event.EventHooks.getEntitySizeForge()0.01%
net.neoforged.neoforge.server.ServerLifecycleHooks.handleServerStarting()0.01%
net.neoforged.neoforge.attachment.LevelAttachmentsSavedData.init()0.01%
net.neoforged.neoforge.server.ServerLifecycleHooks.handleServerAboutToStart()0.01%
net.neoforged.neoforge.event.EventHooks.fireChunkSent()0.01%
net.neoforged.neoforge.common.world.chunk.ForcedChunkManager.hasForcedChunks()0.01%
net.neoforged.neoforge.common.extensions.IBlockEntityExtension.onLoad()0.01%
net.neoforged.neoforge.common.util.DataComponentUtil.wrapEncodingExceptions()0.01%
net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn()0.01%
net.neoforged.neoforge.event.EventHooks.checkSpawnPosition()0.01%
net.neoforged.neoforge.event.EventHooks.checkSpawnPlacements()0.01%
net.neoforged.neoforge.common.CommonHooks.onDamageBlock()0.01%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.getAdjacentBlockPathType()0.01%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.getBlockPathType()0.01%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.collisionExtendsVertically()0.01%
net.neoforged.neoforge.event.EventHooks.onPlaySoundAtPosition()0.01%
net.neoforged.neoforge.common.CommonHooks.onLivingBreathe()0.01%
net.neoforged.neoforge.common.extensions.IEntityExtension.isPushedByFluid()0.01%
net.neoforged.neoforge.common.extensions.IFluidStateExtension.getFluidType()0.01%
net.neoforged.neoforge.fluids.FluidType.isVanilla()0.01%
net.neoforged.neoforge.common.extensions.IDataComponentHolderExtension.has()0.01%
living_item (v1.3.0)
Server thread5.02%
com.qiqi.li.living.domain.map.LivingMapEventHandler.onRightClickItem()4.56%
com.qiqi.li.living.domain.map.MapTeleportExecutor.execute()4.10%
com.qiqi.li.living.domain.map.TeleportHelper.teleportToMapPosition()4.10%
com.qiqi.li.living.domain.map.TeleportHelper.executeTeleport()4.07%
net.minecraft.server.level.ServerPlayer.teleportTo()4.06%
net.minecraft.server.level.ServerPlayer.wrapMethod$cfb000$sable$teleportTo()4.06%
net.minecraft.server.level.ServerPlayer$$Lambda/0x0000025c066cc670.call()4.06%
net.minecraft.server.level.ServerPlayer.mixinextras$bridge$teleportTo$mixinextras$wrapped$213$214()4.06%
net.minecraft.server.level.ServerPlayer.teleportTo$mixinextras$wrapped$213()4.06%
net.minecraft.server.network.ServerGamePacketListenerImpl.teleport()4.06%
net.minecraft.world.entity.Entity.absMoveTo()4.06%
net.minecraft.world.entity.Entity.absMoveTo()4.06%
net.minecraft.world.entity.Entity.setPos()4.06%5370ms
net.minecraft.world.entity.Entity.setPosRaw()4.06%
net.minecraft.world.level.Level.getChunk()4.06%
net.minecraft.world.level.LevelReader.getChunk()4.06%
net.minecraft.world.level.Level.getChunk()4.06%
net.minecraft.server.level.ServerChunkCache.getChunk()4.06%
net.minecraft.server.level.ServerChunkCache$MainThreadExecutor.managedBlock()4.06%
net.minecraft.util.thread.BlockableEventLoop.managedBlock()4.06%
net.minecraft.util.thread.BlockableEventLoop.waitForTasks()3.93%
java.util.concurrent.locks.LockSupport.parkNanos()3.82%
jdk.internal.misc.Unsafe.park()3.82%
java.lang.Thread.yield()0.11%
net.minecraft.server.level.ServerChunkCache$MainThreadExecutor.pollTask()0.13%
com.qiqi.li.logging.ModLog.<clinit>()0.01%
com.qiqi.li.living.domain.map.TeleportHelper.getSafeYFromNoise()0.04%
com.qiqi.li.living.domain.map.LivingMapEventHandler.handleLivingMapCreation()0.45%
com.qiqi.li.LivingItem.onServerTick()0.40%
com.qiqi.li.living.domain.map.LivingMapEventHandler.onPlayerTick()0.02%
com.qiqi.li.LivingItem.onChunkUnload()0.02%
com.qiqi.li.living.domain.redstone.LivingComparatorData.equals()0.01%
com.qiqi.li.living.domain.hopper.LivingHopperData.equals()0.01%
com.qiqi.li.living.domain.redstone.LivingRedstoneLampData.equals()0.01%
com.qiqi.li.living.container.ContainerChunkCache.onChunkLoad()0.01%
sable (v2.0.3)
Server thread2.73%
dev.ryanhcode.sable.Sable.defaultSubLevelContainerInitializer()0.99%
dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertiesDefinitionLoader.applyAll()0.88%
dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertiesDefinitionLoader.applyToBlocks()0.88%
net.minecraft.world.level.block.state.BlockState.sable$loadProperties()0.79%
dev.ryanhcode.sable.physics.config.block_properties.BlockStateConditionSet.matches()0.18%
net.minecraft.world.level.block.state.BlockState.sable$applyPropertySet()0.11%
com.google.common.collect.ImmutableMap.entrySet()0.02%
com.google.common.collect.AbstractIndexedListIterator.next()0.03%
net.minecraft.tags.TagKey.create()0.01%
dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem.initialize()0.09%
dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem.<init>()0.02%
dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer.tick()0.88%
dev.ryanhcode.sable.api.sublevel.SubLevelContainer.tick()0.85%
it.unimi.dsi.fastutil.objects.ReferenceArrayList.forEach()0.85%
dev.ryanhcode.sable.api.sublevel.SubLevelContainer$$Lambda/0x0000025c06182458.accept()0.85%
dev.ryanhcode.sable.api.sublevel.SubLevelContainer.lambda$tick$0()0.85%
dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem.tick()0.77%
dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem.tickPipelinePhysics()0.76%
dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline.physicsTick()0.69%
dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.step()0.69%
dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline.postPhysicsTicks()0.05%
dev.ryanhcode.sable.neoforge.platform.SableEventPublishPlatformImpl.prePhysicsTick()0.02%
dev.ryanhcode.sable.neoforge.platform.SableEventPublishPlatformImpl.postPhysicsTick()0.01%
dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager.update()0.02%
dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem.tick()0.06%
dev.simulated_team.simulated.content.physics_staff.PhysicsStaffSubLevelObserver.tick()0.02%
dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap.processChanges()0.02%
dev.ryanhcode.sable.SableCommonEvents.handleBlockChange()0.54%
dev.ryanhcode.sable.util.SubLevelInclusiveLevelEntityGetter.get()0.10%
dev.ryanhcode.sable.util.SubLevelInclusiveLevelEntityGetter.get()0.05%
dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer()0.02%
dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision.collide()0.02%
dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer.initialize()0.02%
dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionContainer.getContainer()0.02%
dev.ryanhcode.sable.ActiveSableCompanion.getFeetPos()0.02%
dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap.updateChunkStatus()0.01%
dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer.<init>()0.01%
dev.ryanhcode.sable.sublevel.tracking_points.SubLevelTrackingPointSavedData.getOrLoad()0.01%
dev.ryanhcode.sable.ActiveSableCompanion.getContaining()0.01%
dev.ryanhcode.sable.mixinhelpers.entity.entity_collision.TheFasterEntityCollisionContext.canStandOnFluid()0.01%
dev.ryanhcode.sable.ActiveSableCompanion.getAllIntersecting()0.01%
dev.ryanhcode.sable.mixinhelpers.entity.entity_collision.TheFasterEntityCollisionContext.isAbove()0.01%
dev.ryanhcode.sable.ActiveSableCompanion.getContaining()0.01%
dev.ryanhcode.sable.ActiveSableCompanion.projectOutOfSubLevel()0.01%
dev.ryanhcode.sable.ActiveSableCompanion.distanceSquaredWithSubLevels()0.01%
dev.ryanhcode.sable.mixinhelpers.CanFallAtleastHelper.canFallAtleastWithSubLevels()0.01%
sable_rapier
Server thread1.35%
xaeroworldmap (v1.44.2)
Server thread0.70%
cataclysm (v3.32)
Server thread0.66%
ars_nouveau (v5.12.1)
Server thread0.59%
twilightforest (v4.8.3345)
Server thread0.37%
create (v6.0.10)
Server thread0.21%
architectury (v13.0.8)
Server thread0.14%
create_aeronautics_toolgun (v0.3.4)
Server thread0.11%
jdk.proxy3
Server thread0.10%
simulated (v1.3.0)
Server thread0.08%
sophisticatedbackpacks (v3.25.69)
Server thread0.07%
balm (v21.0.63)
Server thread0.07%
sophisticatedcore (v1.4.72)
Server thread0.07%
ae2 (v19.2.17)
Server thread0.05%
spark (v1.10.124)
Server thread0.05%
ponder (v1.0.82+mc1.21.1)
Server thread0.05%
flywheel (v1.0.6)
Server thread0.05%
waystones (v21.1.38)
Server thread0.05%
avaritia (v1.3.1)
Server thread0.05%
curios (v9.5.1+1.21.1)
Server thread0.04%
ftbteams (v2101.1.10)
Server thread0.04%
offroad (v1.3.0)
Server thread0.03%
fxntstorage (v1.3.3)
Server thread0.03%
ftbchunks (v2101.1.20)
Server thread0.02%
aeronautics (v1.3.0)
Server thread0.02%
ftblibrary (v2101.1.33)
Server thread0.02%
mixinextras.neoforge
Server thread0.02%
sablecompanion (v1.6.0)
Server thread0.01%
xaerominimap (v26.4.2)
Server thread0.01%
Registrate.MC1._21._67
Server thread0.01%
Other
The following other mods are installed, but didn't show up in this profile. Yay!

inventorysorter (v24.0.24)
kuma_api (v21.0.8)
fabric_renderer_api_v1 (v3.4.1+9125b6dc19)
sodium_extra (v0.9.3+mc1.21.1)
geckolib (v4.9.2)
customskinloader (v15.0.1)
jei (v19.38.0.366)
xaerolib (v1.7.1)
aeronautics_bundled (v1.3.0)
trender (v1.0.15)
naturescompass (v1.21.1-3.4.0-neoforge)
fabric_block_view_api_v2 (v1.0.10+9afaaf8c19)
iris (v1.8.14-beta.1+mc1.21.1)
veil (v4.1.4)
midnightlib (v1.9.3)
guideme (v21.1.17)
explorerscompass (v1.21.1-3.4.0-neoforge)
waveycapes (v1.10.2)
nuggets (v1.1.0.48)
resourcify (v1.8.5)
sodium (v0.8.12+mc1.21.1)
cloth_config (v15.0.140)
refinedstorage (v2.0.9)
ironchest (v1.21-neoforge-16.0.7)
dungeons_arise (v2.1.68)
minecraft (v1.21.1)
fabric_api_base (v0.4.42+d1308ded19)
mousetweaks (v2.26.1)
blur (v6.3.1)
jade (v15.10.5+neoforge)
lionfishapi (v3.1)
kotlinforforge (v5.12.0)
pipez (v1.21.1-1.2.31)
wip (v21.1.2)
storagedrawers (v13.11.4)
lambdynlights_api (v4.5.1+1.21.1)
componentviewer (v1.3.3+1.21.1)
transition (v1.0.21)
fabric_rendering_data_attachment_v1 (v0.3.48+73761d2e19)
infiniverse (v2.0.1.0)
observable (v5.4.4)
spark and spark-viewer are free & open source on GitHub.
Copyright © 2018-2026 lucko & other spark contributors.
