package com.qiqi.li.living.compat.jade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.WailaPlugin;

/** Jade 请求与公共能力存在性分离；存量数据必须继续由默认服务端扩展提供。 */
class LivingItemJadePluginTest {

    @Test
    @DisplayName("Jade 自发现插件：两种请求扩展分别注册到方块实体，不接管显示")
    void registersBothStorageRequests() {
        assertTrue(LivingItemJadePlugin.class.isAnnotationPresent(WailaPlugin.class));
        IWailaCommonRegistration registration = mock(IWailaCommonRegistration.class);
        new LivingItemJadePlugin().register(registration);
        verify(registration).registerFluidStorage(LivingItemJadePlugin.StorageRequest.SPONGE, BlockEntity.class);
        verify(registration).registerEnergyStorage(LivingItemJadePlugin.StorageRequest.BULB, BlockEntity.class);
        verifyNoMoreInteractions(registration);
        assertEquals(ResourceLocation.parse("living_item:sponge_storage_request"),
            LivingItemJadePlugin.StorageRequest.SPONGE.getUid());
        assertEquals(ResourceLocation.parse("living_item:bulb_storage_request"),
            LivingItemJadePlugin.StorageRequest.BULB.getUid());
        assertNotEquals(LivingItemJadePlugin.StorageRequest.SPONGE.getUid(),
            LivingItemJadePlugin.StorageRequest.BULB.getUid());
    }

    @ParameterizedTest
    @EnumSource(LivingItemJadePlugin.StorageRequest.class)
    @DisplayName("请求只认当前方块实体：不读取客户端能力，也不猜容器库存")
    void requestsBlockEntityWithoutCapabilityProbe(LivingItemJadePlugin.StorageRequest request) {
        BlockAccessor accessor = mock(BlockAccessor.class);
        BlockEntity be = mock(BlockEntity.class);
        when(accessor.getBlockEntity()).thenReturn(be);
        assertTrue(request.shouldRequestData(accessor));
        verify(accessor).getBlockEntity();
        verifyNoMoreInteractions(accessor, be);
    }

    @ParameterizedTest
    @EnumSource(LivingItemJadePlugin.StorageRequest.class)
    @DisplayName("无方块实体及非方块目标不触发这两类存量请求")
    void ignoresOtherTargets(LivingItemJadePlugin.StorageRequest request) {
        assertFalse(request.shouldRequestData(mock(BlockAccessor.class)));
        assertFalse(request.shouldRequestData(mock(Accessor.class)));
    }

    @ParameterizedTest
    @EnumSource(LivingItemJadePlugin.StorageRequest.class)
    @DisplayName("请求扩展始终返回 null：默认存量扩展继续取数，不覆盖原生 UI")
    void doesNotClaimStorageData(LivingItemJadePlugin.StorageRequest request) {
        BlockAccessor accessor = mock(BlockAccessor.class);
        assertNull(request.getGroups(accessor), "空列表会截断后续扩展，必须返回 null");
        verifyNoMoreInteractions(accessor);
    }

    @Test
    @DisplayName("真实 Jade 请求链：默认客户端能力不存在时，两类查询仍可触发")
    void realLookupRequestsWithoutPublicCapabilities() {
        BlockAccessor accessor = blockAccessor();
        var registration = snownee.jade.impl.WailaCommonRegistration.instance();
        assertTrue(registration.fluidStorageProviders.wrappedGet(accessor)
            .contains(LivingItemJadePlugin.StorageRequest.SPONGE));
        assertTrue(registration.energyStorageProviders.wrappedGet(accessor)
            .contains(LivingItemJadePlugin.StorageRequest.BULB));
        assertFalse(snownee.jade.util.CommonProxy.hasDefaultFluidStorage(accessor));
        assertFalse(snownee.jade.util.CommonProxy.hasDefaultEnergyStorage(accessor));
        assertTrue(registration.fluidStorageProviders.hitsAny(accessor,
            snownee.jade.api.view.IServerExtensionProvider::shouldRequestData));
        assertTrue(registration.energyStorageProviders.hitsAny(accessor,
            snownee.jade.api.view.IServerExtensionProvider::shouldRequestData));
    }

    @Test
    @DisplayName("真实 Jade 服务端取数链：请求扩展让位，返回原生液罐及能源显示 UID")
    void realLookupFallsThroughToDefaultStorageData() {
        BlockAccessor accessor = blockAccessor();
        var level = accessor.getLevel();
        var fluid = new net.neoforged.neoforge.fluids.capability.templates.FluidTank(4000);
        fluid.fill(new net.neoforged.neoforge.fluids.FluidStack(
            net.minecraft.world.level.material.Fluids.WATER, 1000),
            net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        var energy = new net.neoforged.neoforge.energy.EnergyStorage(4000);
        energy.receiveEnergy(1000, false);
        when(level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,
            accessor.getPosition(), accessor.getBlockState(), accessor.getBlockEntity(), null)).thenReturn(fluid);
        when(level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
            accessor.getPosition(), accessor.getBlockState(), accessor.getBlockEntity(), null)).thenReturn(energy);

        var registration = snownee.jade.impl.WailaCommonRegistration.instance();
        var fluidData = snownee.jade.util.CommonProxy.getServerExtensionData(accessor,
            registration.fluidStorageProviders);
        var energyData = snownee.jade.util.CommonProxy.getServerExtensionData(accessor,
            registration.energyStorageProviders);
        org.junit.jupiter.api.Assertions.assertNotNull(fluidData);
        org.junit.jupiter.api.Assertions.assertNotNull(energyData);
        assertEquals(snownee.jade.addon.universal.FluidStorageProvider.Extension.INSTANCE.getUid(), fluidData.getKey());
        assertEquals(snownee.jade.addon.universal.EnergyStorageProvider.Extension.INSTANCE.getUid(), energyData.getKey());
        assertFalse(fluidData.getValue().isEmpty());
        assertEquals(1, energyData.getValue().size());
    }

    @Test
    @DisplayName("真实 Jade 服务端取数链：无真实能力时不生成海绵或电池显示数据")
    void realLookupDoesNotInventStorageData() {
        BlockAccessor accessor = blockAccessor();
        var registration = snownee.jade.impl.WailaCommonRegistration.instance();
        assertNull(snownee.jade.util.CommonProxy.getServerExtensionData(accessor,
            registration.fluidStorageProviders));
        assertNull(snownee.jade.util.CommonProxy.getServerExtensionData(accessor,
            registration.energyStorageProviders));
    }

    @Test
    @DisplayName("NeoForge 已注册能力链：客户端箱子和非容器方块实体都没有假存量能力")
    void registeredCapabilitiesDoNotAdvertiseOnClient() {
        var level = mock(net.minecraft.world.level.Level.class);
        when(level.isClientSide()).thenReturn(true);
        var pos = net.minecraft.core.BlockPos.ZERO;
        var states = java.util.List.of(net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(),
            net.minecraft.world.level.block.Blocks.OAK_SIGN.defaultBlockState());
        var types = java.util.List.of(net.minecraft.world.level.block.entity.BlockEntityType.CHEST,
            net.minecraft.world.level.block.entity.BlockEntityType.SIGN);
        for (int i = 0; i < states.size(); i++) {
            var be = mock(BlockEntity.class);
            when(be.getLevel()).thenReturn(level);
            when(be.getBlockPos()).thenReturn(pos);
            org.mockito.Mockito.doReturn(types.get(i)).when(be).getType();
            assertNull(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK.getCapability(
                level, pos, states.get(i), be, null));
            assertNull(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK.getCapability(
                level, pos, states.get(i), be, null));
        }
    }

    private static BlockAccessor blockAccessor() {
        BlockAccessor accessor = mock(BlockAccessor.class);
        BlockEntity be = mock(BlockEntity.class);
        when(accessor.getBlockEntity()).thenReturn(be);
        when(accessor.getTarget()).thenReturn(be);
        when(accessor.getLevel()).thenReturn(mock(net.minecraft.world.level.Level.class));
        when(accessor.getBlock()).thenReturn(net.minecraft.world.level.block.Blocks.CHEST);
        when(accessor.getBlockState()).thenReturn(net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        when(accessor.getPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
        when(accessor.nbtOps()).thenReturn(net.minecraft.nbt.NbtOps.INSTANCE);
        return accessor;
    }
}
