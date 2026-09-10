spark

777_hi @ 11:44PM 9/10/2026, interval 4ms, ticks >= 100ms
TPS
18.91
1m
19.77
5m
19.92
15m
MSPT
8.29
min
12.4
med
18.3
95%ile
1360
max
CPU(process)
8.01%
1m
8.1%
15m
Memory(process)
3.3 GB
/
4.1 GB
80.82%
CPU(system)
34.83%
1m
35.69%
15m
 Profiler - Mods View
This view shows a filtered representation of the profile broken down by mod.

 Label: Percentage
The value displayed against each frame is the time divided by the total time as a percentage.

 Merge Mode: Merge
Method calls with the same signature will be merged together, even though they may not have been invoked by the same calling method.

neoforge (v21.1.249)
Server thread99.17%
net.neoforged.neoforge.event.EventHooks.fireServerTickPost()98.81%
net.neoforged.bus.EventBus.post()98.81%
net.neoforged.bus.EventBus.post()98.81%
net.neoforged.bus.EventListenerFactory$onServerTick/0x0000000010960400.invoke()98.81%
java.lang.invoke.LambdaForm$MH/0x000000000e610c00.invokeExact_MT()98.81%
java.lang.invoke.LambdaForm$MH/0x0000000016223c00.invoke()98.81%
java.lang.invoke.LambdaForm$DMH/0x000000000e08c800.invokeStatic()98.81%
sonar.fluxnetworks.register.EventHandler.onServerTick()98.81%
it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap$1.forEach()98.81%
sonar.fluxnetworks.register.EventHandler$$Lambda/0x0000000015843400.accept()98.81%
sonar.fluxnetworks.common.connection.ServerFluxNetwork.onEndServerTick()98.81%
sonar.fluxnetworks.common.device.FluxPointHandler.onCycleStart()98.81%
sonar.fluxnetworks.common.device.FluxPointHandler.sendToConsumers()98.81%
sonar.fluxnetworks.common.device.SideTransfer.send()98.81%
sonar.fluxnetworks.common.integration.energy.ForgeEnergyConnector.sendTo()98.79%
com.qiqi.li.living.domain.power.ContainerEnergyStorage.receiveEnergy()98.79%
com.qiqi.li.living.domain.power.ContainerEnergyStorage.receive()98.79%
net.neoforged.neoforge.items.wrapper.SidedInvWrapper.getStackInSlot()81.02%
net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity.getItem()81.02%
net.minecraft.world.level.block.entity.BaseContainerBlockEntity.getItem()81.02%
net.minecraft.core.NonNullList.get()81.02%
com.qiqi.li.living.domain.power.ContainerEnergyStorage.isBulb()14.96%
com.qiqi.li.living.api.LivingItemManager.isLivingItem()14.71%
net.minecraft.core.component.DataComponentHolder.has()13.27%
net.minecraft.core.component.DataComponentMap.has()13.27%
net.minecraft.core.component.PatchedDataComponentMap.get()13.27%
it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap.get()7.56%
com.qiqi.li.living.api.LivingItemManager.getWaxedBulbData()1.13%
sonar.fluxnetworks.common.integration.energy.ForgeEnergyConnector.canSendTo()0.02%
net.neoforged.neoforge.event.EventHooks.fireServerTickPre()0.25%
net.neoforged.neoforge.event.EventHooks.firePlayerTickPost()0.02%
net.neoforged.neoforge.common.extensions.IFluidStateExtension.getFluidType()0.02%
net.neoforged.neoforge.event.EventHooks.fireEntityTickPre()0.02%
net.neoforged.neoforge.event.EventHooks.fireEntityTickPost()0.02%
net.neoforged.neoforge.items.wrapper.InvWrapper.getSlots()0.02%
living_item (v1.3.2)
Server thread99.06%
com.qiqi.li.living.domain.power.ContainerEnergyStorage.receiveEnergy()98.79%18968ms (living_item)
com.qiqi.li.living.domain.power.ContainerEnergyStorage.receive()98.79%
net.neoforged.neoforge.items.wrapper.SidedInvWrapper.getStackInSlot()81.02%
net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity.getItem()81.02%
net.minecraft.world.level.block.entity.BaseContainerBlockEntity.getItem()81.02%
net.minecraft.core.NonNullList.get()81.02%
com.qiqi.li.living.domain.power.ContainerEnergyStorage.isBulb()14.96%
com.qiqi.li.living.api.LivingItemManager.isLivingItem()14.71%
net.minecraft.core.component.DataComponentHolder.has()13.27%
net.minecraft.core.component.DataComponentMap.has()13.27%
net.minecraft.core.component.PatchedDataComponentMap.get()13.27%
it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap.get()7.56%
com.qiqi.li.living.api.LivingItemManager.getWaxedBulbData()1.13%
com.qiqi.li.living.api.LivingItemManager.getData()0.85%
net.minecraft.core.component.DataComponentHolder.get()0.85%
net.minecraft.core.component.PatchedDataComponentMap.get()0.85%
it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap.get()0.85%
com.qiqi.li.LivingItem.onServerTick()0.25%
com.qiqi.li.living.domain.power.ContainerEnergyStorage.extractEnergy()0.02%
fluxnetworks (v8.0.0)
Server thread98.81%
sonar.fluxnetworks.register.EventHandler.onServerTick()98.81%
it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap$1.forEach()98.81%
sonar.fluxnetworks.register.EventHandler$$Lambda/0x0000000015843400.accept()98.81%
sonar.fluxnetworks.common.connection.ServerFluxNetwork.onEndServerTick()98.81%
sonar.fluxnetworks.common.device.FluxPointHandler.onCycleStart()98.81%
sonar.fluxnetworks.common.device.FluxPointHandler.sendToConsumers()98.81%
sonar.fluxnetworks.common.device.SideTransfer.send()98.81%
sonar.fluxnetworks.common.integration.energy.ForgeEnergyConnector.sendTo()98.79%
com.qiqi.li.living.domain.power.ContainerEnergyStorage.receiveEnergy()98.79%
com.qiqi.li.living.domain.power.ContainerEnergyStorage.receive()98.79%
net.neoforged.neoforge.items.wrapper.SidedInvWrapper.getStackInSlot()81.02%
net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity.getItem()81.02%
net.minecraft.world.level.block.entity.BaseContainerBlockEntity.getItem()81.02%
net.minecraft.core.NonNullList.get()81.02%
com.qiqi.li.living.domain.power.ContainerEnergyStorage.isBulb()14.96%
com.qiqi.li.living.api.LivingItemManager.isLivingItem()14.71%
net.minecraft.core.component.DataComponentHolder.has()13.27%
net.minecraft.core.component.DataComponentMap.has()13.27%
net.minecraft.core.component.PatchedDataComponentMap.get()13.27%
it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap.get()7.56%
com.qiqi.li.living.api.LivingItemManager.getWaxedBulbData()1.13%
sonar.fluxnetworks.common.integration.energy.ForgeEnergyConnector.canSendTo()0.02%
goety (v3.1.4)
Server thread0.06%
sable (v2.0.3)
Server thread0.06%
ars_nouveau (v5.12.1)
Server thread0.06%
mekanism (v10.7.19)
Server thread0.04%
sable_rapier
Server thread0.04%
anvilcraft (v1.6.0+snapshot.2179)
Server thread0.02%
ironchest (v1.21-neoforge-16.0.7)
Server thread0.02%
curios (v9.5.1+1.21.1)
Server thread0.02%
ae2 (v19.2.17)
Server thread0.02%
ftbchunks (v2101.1.20)
Server thread0.02%
create (v6.0.10)
Server thread0.02%
Other
The following other mods are installed, but didn't show up in this profile. Yay!

cyclopscore (v1.29.3)
kuma_api (v21.0.8)
fabric_renderer_api_v1 (v3.4.1+9125b6dc19)
sodium_extra (v0.9.3+mc1.21.1)
geckolib (v4.9.2)
aeronautics_bundled (v1.3.0)
anvillib_config (v2.0.0+snapshot.513)
sophisticatedcore (v1.4.72)
xaeroworldmap (v1.44.2)
fabric_block_view_api_v2 (v1.0.10+9afaaf8c19)
ageratum (v0.0.1+build.111)
iris (v1.8.14-beta.1+mc1.21.1)
veil (v4.1.4)
sophisticatedbackpacks (v3.25.69)
simulated (v1.3.0)
anvillib_collision (v2.0.0+snapshot.513)
nuggets (v1.1.0.48)
balm (v21.0.63)
anvillib_util (v2.0.0+snapshot.513)
cloth_config (v15.0.140)
twilightforest (v4.8.3345)
refinedstorage (v2.0.9)
industrialforegoing (v1.21-3.6.39)
commoncapabilities (v2.11.5)
blur (v6.3.1)
anvillib_integration (v2.0.0+snapshot.513)
lionfishapi (v3.1)
spark (v1.10.124)
actuallyadditions (v1.3.26)
cataclysm (v3.32)
integratedtunnels (v1.10.0)
integratedtunnelscompat (v1.10.0)
anvillib_registrum (v2.0.0+snapshot.513)
anvillib_explosion (v2.0.0+snapshot.513)
architectury (v13.0.8)
transition (v1.0.21)
fabric_rendering_data_attachment_v1 (v0.3.48+73761d2e19)
infiniverse (v2.0.1.0)
anvillib_recipe (v2.0.0+snapshot.513)
observable (v5.4.4)
anvillib_multiblock (v2.0.0+snapshot.513)
anvillib_rpc (v2.0.0+snapshot.513)
inventorysorter (v24.0.24)
ftblibrary (v2101.1.33)
ftbteams (v2101.1.10)
anvillib_moveable_entity_block (v2.0.0+snapshot.513)
customskinloader (v15.0.1)
jei (v19.38.0.366)
aeronautics (v1.3.0)
xaerolib (v1.7.1)
trender (v1.0.15)
mekanismgenerators (v10.7.19)
anvillib_space_select (v2.0.0+snapshot.513)
waystones (v21.1.38)
anvillib_network (v2.0.0+snapshot.513)
anvillib_sync (v2.0.0+snapshot.513)
naturescompass (v1.21.1-3.4.0-neoforge)
midnightlib (v1.9.3)
mcjtylib (v1.21-9.0.21)
rftoolsbase (v1.21-6.0.11)
xnet (v1.21-7.0.7)
guideme (v21.1.17)
explorerscompass (v1.21.1-3.4.0-neoforge)
waveycapes (v1.10.2)
anvillib (v2.0.0+snapshot.513)
resourcify (v1.8.5)
sodium (v0.8.12+mc1.21.1)
anvillib_codec (v2.0.0+snapshot.513)
offroad (v1.3.0)
dungeons_arise (v2.1.68)
minecraft (v1.21.1)
fabric_api_base (v0.4.42+d1308ded19)
mousetweaks (v2.26.1)
titanium (v4.0.50)
avaritia (v1.3.1)
jade (v15.10.5+neoforge)
kotlinforforge (v5.12.0)
pipez (v1.21.1-1.2.31)
sablecompanion (v1.6.0)
flywheel (v1.0.6)
ponder (v1.0.82+mc1.21.1)
constructionwand (v2.16.12)
fxntstorage (v1.3.3)
create_aeronautics_toolgun (v0.3.4)
integrateddynamics (v1.35.0)
integrateddynamicscompat (v1.0.0)
xaerominimap (v26.4.2)
wip (v21.1.2)
storagedrawers (v13.11.4)
anvillib_font (v2.0.0+snapshot.513)
lambdynlights_api (v4.5.1+1.21.1)
componentviewer (v1.3.3+1.21.1)
anvillib_wheel (v2.0.0+snapshot.513)
createaddition (v1.7.0)
spark and spark-viewer are free & open source on GitHub.
Copyright © 2018-2026 lucko & other spark contributors.
