spark

777_hi @ 下午05:26 2026/8/26, interval 10ms
TPS
20.00
1m
20.00
5m
20.00
15m
MSPT
0.75
min
11.9
med
16.6
95%ile
117
max
CPU(process)
15.67%
1m
21.06%
15m
Memory(process)
3.1 GB
/
5 GB
62.37%
CPU(system)
24.89%
1m
30.58%
15m
 Profiler - Mods View
This view shows a filtered representation of the profile broken down by mod.

 Label: Percentage
The value displayed against each frame is the time divided by the total time as a percentage.

 Merge Mode: Merge
Method calls with the same signature will be merged together, even though they may not have been invoked by the same calling method.

neoforge (v21.1.235)
Server thread16.28%
net.neoforged.neoforge.event.EventHooks.fireServerTickPre()14.72%
net.neoforged.bus.EventBus.post()14.72%
net.neoforged.bus.EventBus.post()14.70%
net.neoforged.bus.EventListenerFactory$onServerTick/0x00000240ea05c400.invoke()12.51%
java.lang.invoke.LambdaForm$MH/0x00000240e8028000.invokeExact_MT()12.51%
java.lang.invoke.LambdaForm$MH/0x00000240eb659000.invoke()12.51%
java.lang.invoke.DirectMethodHandle$Holder.invokeVirtual()12.51%
com.qiqi.li.LivingItem.onServerTick()12.51%
com.qiqi.li.LivingItem.processLevelContainers()11.88%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContainerAt()6.93%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContext()5.17%
com.qiqi.li.living.container.SimpleContainerContext.getItem()2.34%
net.neoforged.neoforge.items.wrapper.InvWrapper.getStackInSlot()1.68%
net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity.getItem()0.69%
net.minecraft.world.level.block.entity.BaseContainerBlockEntity.getItem()0.69%
com.progwml6.ironchest.common.block.regular.entity.CrystalChestBlockEntity.getItem()0.01%
net.neoforged.neoforge.items.wrapper.InvWrapper.getSlots()0.64%
net.neoforged.neoforge.items.wrapper.SidedInvWrapper.getSlots()0.01%
net.neoforged.neoforge.items.wrapper.CombinedInvWrapper.getStackInSlot()0.00%
net.p3pp3rf1y.sophisticatedcore.inventory.CachedFailedInsertInventoryHandler.getStackInSlot()0.00%
com.qiqi.li.living.container.SimpleContainerContext.getSize()1.15%
com.qiqi.li.living.api.LivingItemManager.isLivingItem()0.50%
com.qiqi.li.living.perf.PerfMetrics.recordPhase()0.20%
com.qiqi.li.living.domain.redstone.LivingCopperFunction.tickContainerData()0.18%
com.qiqi.li.living.container.ContainerLivingItemHandler.updateStressOutput()0.06%
com.qiqi.li.living.domain.redstone.LivingRedstoneLampFunction.tickContainerData()0.05%
com.qiqi.li.living.domain.water.LivingWaterBucketFunction.tick()0.05%
com.qiqi.li.living.domain.hopper.LivingHopperFunction.tick()0.05%
java.util.LinkedHashMap.get()0.03%
com.qiqi.li.living.domain.redstone.LivingRedstoneTorchFunction.tickContainerData()0.03%
net.minecraft.world.level.block.entity.BlockEntity.setData()0.02%
com.qiqi.li.living.domain.water.LivingWaterBucketFunction.tickContainerData()0.02%
java.util.Collections$UnmodifiableCollection$1.hasNext()0.01%
com.qiqi.li.living.domain.furnace.LivingFurnaceFunction.tick()0.01%
java.util.HashMap.computeIfAbsent()0.01%
java.util.ArrayList.sort()0.01%
com.qiqi.li.living.perf.PerfMetrics.addLivingItem()0.00%
com.qiqi.li.living.domain.redstone.LivingRedstoneBlockFunction.tickContainerData()0.00%
com.qiqi.li.living.domain.redstone.LivingLeverFunction.tickContainerData()0.00%
java.util.HashMap.put()0.00%
com.qiqi.li.living.domain.water.LivingWaterWheelFunction.tickContainerData()0.00%
java.util.HashMap.remove()0.00%
com.qiqi.li.living.container.SimpleContainerContext.flushDirtySlots()0.00%
com.qiqi.li.living.container.SimpleContainerContext.<init>()0.84%
java.util.IdentityHashMap.put()0.26%
com.qiqi.li.living.container.ContainerLivingItemHandler.findDoubleChestPositions()0.25%
net.minecraft.world.level.Level.getBlockEntity()0.10%
java.util.HashSet.add()0.02%
net.neoforged.neoforge.common.extensions.ILevelExtension.getCapability()2.61%
java.util.ArrayList.<init>()0.74%
java.util.HashMap$KeyIterator.next()0.05%
net.minecraft.server.level.ServerChunkCache.getChunkNow()0.04%
com.qiqi.li.living.container.ContainerChunkCache.getCachedChunks()0.01%
com.qiqi.li.living.container.ContainerChunkCache.getCacheSize()0.01%
com.qiqi.li.living.container.ContainerChunkCache.flushPendingRescans()0.00%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContainer()0.44%
java.util.Collections$UnmodifiableCollection$1.hasNext()0.03%
java.util.Collections$UnmodifiableCollection$1.next()0.00%
net.neoforged.bus.ConsumerEventHandler.invoke()1.16%
net.neoforged.bus.EventListenerFactory$preServerTick/0x00000240e909c800.invoke()0.40%
net.neoforged.bus.EventListenerFactory$event/0x00000240e9780800.invoke()0.18%
net.neoforged.bus.EventListenerFactory$onTickStart/0x00000240eb068000.invoke()0.03%
net.neoforged.neoforge.event.EventHooks.checkMobDespawn()0.41%
net.neoforged.neoforge.event.EventHooks.fireEntityTickPost()0.33%
net.neoforged.neoforge.event.EventHooks.fireServerTickPost()0.14%
net.neoforged.neoforge.event.EventHooks.fireLevelTickPost()0.12%
net.neoforged.neoforge.event.EventHooks.fireEntityTickPre()0.11%
net.neoforged.neoforge.event.EventHooks.fireLevelTickPre()0.06%
net.neoforged.neoforge.event.EventHooks.firePlayerTickPre()0.05%
net.neoforged.neoforge.event.EventHooks.firePlayerTickPost()0.04%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.getFriction()0.04%
net.neoforged.neoforge.common.CommonHooks.onLivingBreathe()0.03%
net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingChunk()0.03%
net.neoforged.neoforge.common.extensions.IFluidStateExtension.getFluidType()0.02%
net.neoforged.neoforge.event.EventHooks.firePlayerLoggedIn()0.01%
net.neoforged.neoforge.server.ServerLifecycleHooks.handleServerStarted()0.01%
net.neoforged.neoforge.common.extensions.IServerCommonPacketListenerExtension.send()0.01%
net.neoforged.neoforge.common.extensions.IBlockStateExtension.collisionExtendsVertically()0.01%
net.neoforged.neoforge.common.extensions.IEntityExtension.isPushedByFluid()0.01%
net.neoforged.neoforge.registries.BaseMappedRegistry.resolve()0.01%
net.neoforged.neoforge.server.command.CommandHelper.mergeCommandNode()0.01%
net.neoforged.neoforge.attachment.LevelAttachmentsSavedData.init()0.01%
net.neoforged.neoforge.common.extensions.ICommonPacketListener.hasChannel()0.01%
net.neoforged.neoforge.common.util.DataComponentUtil.wrapEncodingExceptions()0.01%
net.neoforged.neoforge.common.extensions.IEntityExtension.isInFluidType()0.01%
net.neoforged.neoforge.common.extensions.IItemExtension.getMaxStackSize()0.01%
net.neoforged.neoforge.event.EventHooks.fireChunkWatch()0.00%
net.neoforged.neoforge.network.handling.MainThreadPayloadHandler$$Lambda/0x00000240eb1db810.run()0.00%
net.neoforged.neoforge.common.CommonHooks.onItemStackedOn()0.00%
net.neoforged.neoforge.common.CommonHooks.onLivingJump()0.00%
net.neoforged.neoforge.server.ServerLifecycleHooks.handleServerAboutToStart()0.00%
net.neoforged.neoforge.common.extensions.IItemStackExtension.getAllEnchantments()0.00%
net.neoforged.neoforge.common.extensions.IDataComponentHolderExtension.has()0.00%
net.neoforged.neoforge.event.EventHooks.onStatAward()0.00%
net.neoforged.neoforge.common.world.chunk.ForcedChunkManager.hasForcedChunks()0.00%
net.neoforged.neoforge.event.EventHooks.getPotentialSpawns()0.00%
net.neoforged.neoforge.event.EventHooks.onLivingHeal()0.00%
net.neoforged.neoforge.common.extensions.IEntityExtension.isInFluidType()0.00%
net.neoforged.neoforge.common.CommonHooks.getEntityVisibilityMultiplier()0.00%
net.neoforged.neoforge.common.CommonHooks.onEntityEnterSection()0.00%
living_item (v1.3.1)
Server thread12.66%
com.qiqi.li.LivingItem.onServerTick()12.51%
com.qiqi.li.LivingItem.processLevelContainers()11.88%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContainerAt()6.93%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContext()5.17%
com.qiqi.li.living.container.SimpleContainerContext.getItem()2.34%
net.neoforged.neoforge.items.wrapper.InvWrapper.getStackInSlot()1.68%
net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity.getItem()0.69%
net.minecraft.world.level.block.entity.BaseContainerBlockEntity.getItem()0.69%
com.progwml6.ironchest.common.block.regular.entity.CrystalChestBlockEntity.getItem()0.01%
net.neoforged.neoforge.items.wrapper.InvWrapper.getSlots()0.64%
net.neoforged.neoforge.items.wrapper.SidedInvWrapper.getSlots()0.01%
net.neoforged.neoforge.items.wrapper.CombinedInvWrapper.getStackInSlot()0.00%
net.p3pp3rf1y.sophisticatedcore.inventory.CachedFailedInsertInventoryHandler.getStackInSlot()0.00%
com.qiqi.li.living.container.SimpleContainerContext.getSize()1.15%
com.qiqi.li.living.api.LivingItemManager.isLivingItem()0.50%
com.qiqi.li.living.perf.PerfMetrics.recordPhase()0.20%
com.qiqi.li.living.domain.redstone.LivingCopperFunction.tickContainerData()0.18%
com.qiqi.li.living.container.ContainerLivingItemHandler.updateStressOutput()0.06%
com.qiqi.li.living.domain.redstone.LivingRedstoneLampFunction.tickContainerData()0.05%
com.qiqi.li.living.domain.water.LivingWaterBucketFunction.tick()0.05%
com.qiqi.li.living.domain.hopper.LivingHopperFunction.tick()0.05%
java.util.LinkedHashMap.get()0.03%
com.qiqi.li.living.domain.redstone.LivingRedstoneTorchFunction.tickContainerData()0.03%
net.minecraft.world.level.block.entity.BlockEntity.setData()0.02%
com.qiqi.li.living.domain.water.LivingWaterBucketFunction.tickContainerData()0.02%
java.util.Collections$UnmodifiableCollection$1.hasNext()0.01%
com.qiqi.li.living.domain.furnace.LivingFurnaceFunction.tick()0.01%
java.util.HashMap.computeIfAbsent()0.01%
java.util.ArrayList.sort()0.01%
com.qiqi.li.living.perf.PerfMetrics.addLivingItem()0.00%
com.qiqi.li.living.domain.redstone.LivingRedstoneBlockFunction.tickContainerData()0.00%
com.qiqi.li.living.domain.redstone.LivingLeverFunction.tickContainerData()0.00%
java.util.HashMap.put()0.00%
com.qiqi.li.living.domain.water.LivingWaterWheelFunction.tickContainerData()0.00%
java.util.HashMap.remove()0.00%
com.qiqi.li.living.container.SimpleContainerContext.flushDirtySlots()0.00%
com.qiqi.li.living.container.SimpleContainerContext.<init>()0.84%
java.util.IdentityHashMap.put()0.26%
com.qiqi.li.living.container.ContainerLivingItemHandler.findDoubleChestPositions()0.25%
net.minecraft.world.level.Level.getBlockEntity()0.10%
java.util.HashSet.add()0.02%
net.neoforged.neoforge.common.extensions.ILevelExtension.getCapability()2.61%
java.util.ArrayList.<init>()0.74%
java.util.HashMap$KeyIterator.next()0.05%
net.minecraft.server.level.ServerChunkCache.getChunkNow()0.04%
com.qiqi.li.living.container.ContainerChunkCache.getCachedChunks()0.01%
com.qiqi.li.living.container.ContainerChunkCache.getCacheSize()0.01%
com.qiqi.li.living.container.ContainerChunkCache.flushPendingRescans()0.00%
com.qiqi.li.living.container.ContainerLivingItemHandler.processContainer()0.44%
java.util.Collections$UnmodifiableCollection$1.hasNext()0.03%
java.util.Collections$UnmodifiableCollection$1.next()0.00%
com.qiqi.li.living.api.LivingItemManager.getIgnoredComponentTypes()0.11%
com.qiqi.li.living.api.LivingItemManager.isLivingItem()0.04%
sable (v2.0.3)
Server thread1.70%
sable_rapier
Server thread0.99%
ae2 (v19.2.17)
Server thread0.81%
twilightforest (v4.8.3345)
Server thread0.48%
refinedstorage (v2.0.9)
Server thread0.40%
xaeroworldmap (v1.44.2)
Server thread0.27%
balm (v21.0.63)
Server thread0.26%
ironchest (v1.21-neoforge-16.0.7)
Server thread0.24%
architectury (v13.0.8)
Server thread0.23%
jdk.proxy3
Server thread0.21%
ars_nouveau (v5.12.1)
Server thread0.19%
create (v6.0.10)
Server thread0.17%
simulated (v1.3.0)
Server thread0.08%
curios (v9.5.1+1.21.1)
Server thread0.06%
spark (v1.10.124)
Server thread0.06%
sophisticatedcore (v1.4.72)
Server thread0.06%
create_aeronautics_toolgun (v0.3.4)
Server thread0.04%
observable (v5.4.4)
Server thread0.03%
avaritia (v1.3.1)
Server thread0.03%
sophisticatedbackpacks (v3.25.69)
Server thread0.03%
xaerominimap (v26.4.2)
Server thread0.02%
flywheel (v1.0.6)
Server thread0.02%
aeronautics (v1.3.0)
Server thread0.01%
offroad (v1.3.0)
Server thread0.01%
fxntstorage (v1.3.3)
Server thread0.01%
pipez (v1.21.1-1.2.31)
Server thread0.01%
cataclysm (v3.32)
Server thread0.00%
sablecompanion (v1.6.0)
Server thread0.00%
ponder (v1.0.82+mc1.21.1)
Server thread0.00%
ftblibrary (v2101.1.33)
Server thread0.00%
jade (v15.10.5+neoforge)
Server thread0.00%
ftbteams (v2101.1.10)
Server thread0.00%
infiniverse (v2.0.1.0)
Server thread0.00%
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
waystones (v21.1.38)
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
ftbchunks (v2101.1.20)
transition (v1.0.21)
fabric_rendering_data_attachment_v1 (v0.3.48+73761d2e19)
spark and spark-viewer are free & open source on GitHub.
Copyright © 2018-2026 lucko & other spark contributors.













Individual
Chunks
Aggregate
Traces
Info
777_hi2026/8/26 17:30:235s
Search entries...
- minecraft:overworld — 155 entries	4902 μs/t	Position
minecraft:player	3379 μs/t	(25249, 67, -340353) Visit
minecraft:cow	200 μs/t	(25136, 98, -340249) Visit
minecraft:chicken	79 μs/t	(25271, 84, -340388) Visit
minecraft:squid	43 μs/t	(25160, 60, -340436) Visit
ironchest:crystal_chest	38 μs/t	(25247, 67, -340354) Visit
minecraft:bee	32 μs/t	(25302, 87, -340398) Visit
ironchest:crystal_chest	31 μs/t	(25245, 67, -340347) Visit
minecraft:bee	27 μs/t	(25299, 98, -340394) Visit
minecraft:wolf	24 μs/t	(25289, 83, -340392) Visit
minecraft:llama	22 μs/t	(25264, 74, -340284) Visit
minecraft:wolf	21 μs/t	(25260, 64, -340432) Visit
minecraft:squid	20 μs/t	(25248, 62, -340473) Visit
minecraft:pig	20 μs/t	(25360, 71, -340465) Visit
minecraft:wolf	20 μs/t	(25312, 79, -340392) Visit
minecraft:sheep	20 μs/t	(25197, 79, -340309) Visit
minecraft:sheep	19 μs/t	(25199, 86, -340279) Visit
minecraft:cow	19 μs/t	(25274, 83, -340395) Visit
minecraft:pig	19 μs/t	(25360, 91, -340320) Visit
minecraft:glow_squid	18 μs/t	(25253, 23, -340313) Visit
minecraft:squid	18 μs/t	(25242, 61, -340478) Visit
minecraft:glow_squid	17 μs/t	(25242, -33, -340350) Visit
minecraft:cow	17 μs/t	(25297, 78, -340360) Visit
minecraft:chicken	17 μs/t	(25243, 67, -340397) Visit
minecraft:glow_squid	16 μs/t	(25253, 19, -340316) Visit
minecraft:sheep	16 μs/t	(25184, 63, -340439) Visit
minecraft:glow_squid	16 μs/t	(25234, 5, -340308) Visit
minecraft:sheep	16 μs/t	(25323, 75, -340322) Visit
minecraft:llama	16 μs/t	(25195, 101, -340249) Visit
minecraft:wolf	16 μs/t	(25180, 66, -340338) Visit
minecraft:pig	16 μs/t	(25368, 92, -340422) Visit
minecraft:bat	15 μs/t	(25249, -13, -340433) Visit
minecraft:pig	15 μs/t	(25323, 80, -340398) Visit
minecraft:chicken	15 μs/t	(25294, 78, -340375) Visit
minecraft:sheep	14 μs/t	(25194, 90, -340269) Visit
minecraft:sheep	14 μs/t	(25374, 75, -340296) Visit
minecraft:cow	14 μs/t	(25288, 82, -340400) Visit
minecraft:sheep	14 μs/t	(25252, 80, -340263) Visit
minecraft:pig	14 μs/t	(25342, 67, -340307) Visit
minecraft:bat	14 μs/t	(25333, 35, -340279) Visit
minecraft:sheep	14 μs/t	(25215, 90, -340262) Visit
minecraft:glow_squid	14 μs/t	(25237, 4, -340313) Visit
minecraft:squid	14 μs/t	(25249, 60, -340479) Visit
minecraft:cow	14 μs/t	(25155, 86, -340247) Visit
minecraft:bat	14 μs/t	(25271, 27, -340463) Visit
minecraft:pig	14 μs/t	(25357, 81, -340349) Visit
minecraft:squid	14 μs/t	(25237, 61, -340474) Visit
minecraft:llama	13 μs/t	(25204, 82, -340294) Visit
minecraft:llama	13 μs/t	(25214, 86, -340278) Visit
minecraft:glow_squid	13 μs/t	(25234, 4, -340305) Visit
minecraft:chicken	13 μs/t	(25224, 65, -340426) Visit
minecraft:bat	12 μs/t	(25218, -26, -340299) Visit
minecraft:glow_squid	12 μs/t	(25254, 23, -340313) Visit
minecraft:chicken	12 μs/t	(25316, 79, -340405) Visit
minecraft:cow	12 μs/t	(25259, 67, -340451) Visit
minecraft:squid	12 μs/t	(25243, 60, -340474) Visit
minecraft:bat	12 μs/t	(25272, 29, -340464) Visit
minecraft:sheep	12 μs/t	(25175, 103, -340250) Visit
minecraft:chicken	12 μs/t	(25182, 104, -340246) Visit
minecraft:bat	12 μs/t	(25289, -16, -340341) Visit
minecraft:chicken	12 μs/t	(25241, 72, -340284) Visit
minecraft:pig	11 μs/t	(25358, 72, -340334) Visit
minecraft:item	11 μs/t	(25232, 65, -340434) Visit
minecraft:cow	11 μs/t	(25147, 99, -340242) Visit
minecraft:bat	11 μs/t	(25340, 37, -340289) Visit
minecraft:bat	11 μs/t	(25270, 26, -340464) Visit
minecraft:sheep	11 μs/t	(25329, 66, -340274) Visit
minecraft:bat	11 μs/t	(25208, -30, -340284) Visit
minecraft:bat	11 μs/t	(25188, -24, -340330) Visit
minecraft:chest_minecart	10 μs/t	(25180, 35, -340424) Visit
minecraft:cow	10 μs/t	(25184, 102, -340247) Visit
minecraft:bat	9 μs/t	(25341, 37, -340286) Visit
minecraft:bat	8 μs/t	(25158, 36, -340417) Visit
minecraft:bat	8 μs/t	(25336, 37, -340287) Visit
minecraft:bat	8 μs/t	(25272, 26, -340468) Visit
minecraft:item	7 μs/t	(25293, 78, -340374) Visit
minecraft:bat	7 μs/t	(25269, 27, -340465) Visit
minecraft:bat	7 μs/t	(25188, -19, -340382) Visit
minecraft:item	7 μs/t	(25248, 66, -340412) Visit
sophisticatedbackpacks:backpack	6 μs/t	(25246, 68, -340346) Visit
minecraft:bat	6 μs/t	(25233, 10, -340455) Visit
minecraft:item	6 μs/t	(25316, 79, -340405) Visit
minecraft:chest_minecart	6 μs/t	(25231, 10, -340454) Visit
minecraft:chest_minecart	5 μs/t	(25198, 6, -340435) Visit
minecraft:item	5 μs/t	(25241, 72, -340283) Visit
minecraft:item	5 μs/t	(25181, 104, -340246) Visit
minecraft:chest_minecart	5 μs/t	(25146, 36, -340360) Visit
create:simple_kinetic	3 μs/t	(25245, 66, -340353) Visit
minecraft:beehive	3 μs/t	(25221, 67, -340399) Visit
pipez:item_pipe	2 μs/t	(25248, 67, -340346) Visit
minecraft:vault	2 μs/t	(25283, 0, -340305) Visit
create:funnel	2 μs/t	(25249, 68, -340346) Visit
create:basin	2 μs/t	(25249, 67, -340346) Visit
minecraft:mob_spawner	2 μs/t	(25254, -1, -340403) Visit
minecraft:trial_spawner	2 μs/t	(25273, 16, -340279) Visit
minecraft:vault	2 μs/t	(25264, -22, -340251) Visit
minecraft:vault	1 μs/t	(25278, -6, -340301) Visit
minecraft:beehive	1 μs/t	(25305, 82, -340405) Visit
minecraft:vault	1 μs/t	(25309, 2, -340271) Visit
minecraft:vault	1 μs/t	(25278, 18, -340266) Visit
minecraft:vault	1 μs/t	(25309, 2, -340242) Visit
