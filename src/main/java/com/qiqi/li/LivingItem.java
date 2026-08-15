package com.qiqi.li;

import com.qiqi.li.living.components.ExplosionComponent;
import com.qiqi.li.living.domain.map.LivingMapEventHandler;
import com.qiqi.li.living.domain.map.ItemFrameMapTeleportHandler;
import com.qiqi.li.living.domain.ender.EnderChannelRegistry;
import com.qiqi.li.living.interaction.InteractionEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import com.qiqi.li.living.function.LivingChestFunction;
import com.qiqi.li.living.function.LivingEnderChestFunction;
import com.qiqi.li.living.function.LivingWaterBucketFunction;
import com.qiqi.li.living.function.LivingWaterWheelFunction;
import com.qiqi.li.living.function.LivingEnderPearlFunction;
import com.qiqi.li.logging.ModLog;
import com.qiqi.li.living.perf.PerfMetrics;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import com.qiqi.li.living.container.ContainerChunkCache;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.network.LivingTagPacket;
import com.qiqi.li.network.HopperDirectionPacket;
import com.qiqi.li.network.SlotDirectionPacket;
import com.qiqi.li.network.GuiInteractionPacket;
import com.qiqi.li.network.CarriedUpdatePacket;
import com.qiqi.li.network.EnderChannelSyncPacket;
import com.qiqi.li.network.LivingChestAccessPacket;
import com.qiqi.li.network.LivingMapMetadataPacket;
import com.qiqi.li.network.LivingMapGuiTeleportPacket;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.compat.create.ModCreate;
import com.qiqi.li.living.function.LivingFurnaceFunction;
import com.qiqi.li.living.function.LivingHopperFunction;
import com.qiqi.li.living.function.LivingTntFunction;
import com.qiqi.li.living.function.LivingFlintAndSteelFunction;
import com.qiqi.li.living.interaction.InteractionRegistry;
import com.qiqi.li.living.interaction.IgniteHandler;
import com.qiqi.li.living.interaction.IgniteCarriedHandler;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import com.qiqi.li.living.domain.ender.LivingChestItemHandler;
import com.qiqi.li.living.domain.ender.LivingEnderChestItemHandler;

/**
 * 活物品 Mod 主类。
 *
 * 活物品（Living Item）是一种可以自主执行逻辑的物品。
 * 当物品被标记为"活物品"后，它会在每 tick 自动执行已注册的功能
 * （如活熔炉的熔炼逻辑），无需玩家手动操作。
 *
 * 核心架构：
 *   服务端 tick → 遍历所有容器（玩家背包 + 世界容器）→
 *   对活物品执行功能 tick → 修改 DataComponent → 手动同步到客户端
 *
 * 模块划分：
 *   - living/    核心逻辑（数据组件、容器上下文、功能接口与实现、同步机制）
 *   - network/   网络通信（活物品标签切换包）
 *   - client/    客户端逻辑（GUI 按钮、Mixin、tooltip）
 */
@Mod(LivingItem.MOD_ID)
public class LivingItem {
    public static final String MOD_ID = "living_item";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public LivingItem(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::onRegisterCapabilities);
        ITEMS.register(modEventBus);
        LivingItemManager.DATA_COMPONENT_TYPES.register(modEventBus);
        LivingItemManager.ATTACHMENT_TYPES.register(modEventBus);
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(ContainerChunkCache.getInstance());
        modEventBus.addListener(this::onRegisterPayloadHandler);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        ModCreate.init();
        LivingItemManager.registerFunction(new LivingFurnaceFunction());
        LOGGER.info("Registered living furnace function");

        LivingItemManager.registerFunction(new LivingHopperFunction());
        LOGGER.info("Registered living hopper function");

        LivingItemManager.registerFunction(new LivingTntFunction());
        LOGGER.info("Registered living TNT function");

        LivingItemManager.registerFunction(new LivingFlintAndSteelFunction());
        LOGGER.info("Registered living flint & steel function");

        LivingItemManager.registerFunction(new LivingChestFunction());
        LOGGER.info("Registered living chest function");

        LivingItemManager.registerFunction(new LivingEnderChestFunction());
        LOGGER.info("Registered living ender chest function");

        LivingItemManager.registerFunction(new LivingWaterBucketFunction());
        LOGGER.info("Registered living water bucket function");

        LivingItemManager.registerFunction(new LivingWaterWheelFunction());
        LOGGER.info("Registered living water wheel function");

        LivingItemManager.registerFunction(new LivingEnderPearlFunction());
        LOGGER.info("Registered living ender pearl function");

        LivingMapEventHandler.register();
        LOGGER.info("Registered living map event handler");

        ItemFrameMapTeleportHandler.register();
        LOGGER.info("Registered item frame map teleport handler");

        InteractionRegistry.registerHandler("ignite", new IgniteHandler());
        LOGGER.info("Registered ignite interaction handler");

        InteractionRegistry.registerHandler("ignite_carried", new IgniteCarriedHandler());
        LOGGER.info("Registered ignite_carried interaction handler");

        // 注册交互规则：活打火石右键活TNT点燃
        InteractionRegistry.register(new InteractionEntry(
            Items.TNT, Items.FLINT_AND_STEEL, 1, "ignite"));
        LOGGER.info("Registered ignite interaction rule");

        // 注册交互规则：活TNT右键活打火石点燃（反向）
        InteractionRegistry.register(new InteractionEntry(
            Items.FLINT_AND_STEEL, Items.TNT, 1, "ignite_carried"));
        LOGGER.info("Registered ignite_carried interaction rule");
    }

    /**
     * 服务端 tick 事件 —— 活物品逻辑的核心入口。
     *
     * 执行流程：
     * 1. 遍历所有在线玩家的背包，处理背包中的活物品
     * 2. 遍历所有世界，通过 ContainerChunkCache 获取含容器的区块，
     *    只扫描这些区块中的方块实体，处理世界容器中的活物品
     *
     * 性能优化：
     *   - 使用 ContainerChunkCache 避免全量扫描所有区块
     *   - 使用 IdentityHashMap 对容器和箱子方块实体去重，
     *     避免大箱子被重复处理（左右两半各处理一次 = 速度翻倍）
     */
    private final IdentityHashMap<IItemHandler, Boolean> reusableHandlerMap = new IdentityHashMap<>();
    private final Set<String> reusableKeySet = new HashSet<>();

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();

        for (var player : server.getPlayerList().getPlayers()) {
            ContainerLivingItemHandler.processContainer(player.getInventory(), player.level());
        }

        reusableHandlerMap.clear();
        reusableKeySet.clear();

        for (var level : server.getAllLevels()) {
            processLevelContainers(level);
        }

        ExplosionComponent.tickAll();
    }

    private void processLevelContainers(ServerLevel level) {
        var cache = ContainerChunkCache.getInstance();
        var chunkSet = cache.getCachedChunks(level.dimension());
        if (chunkSet.isEmpty()) return;

        var toRemove = new java.util.ArrayList<ChunkPos>();

        long startNanos = System.nanoTime();
        int processedCount = 0;

        for (var chunkPos : chunkSet) {
            var chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
            if (chunk == null) {
                toRemove.add(chunkPos);
                continue;
            }

            boolean hasContainer = false;
            for (var be : chunk.getBlockEntities().values()) {
                var pos = be.getBlockPos();
                IItemHandler handler = level.getCapability(
                    Capabilities.ItemHandler.BLOCK, pos, null);
                if (handler == null) continue;

                hasContainer = true;
                ContainerLivingItemHandler.processContainerAt(
                    level, pos, handler, reusableHandlerMap, reusableKeySet);
                processedCount++;
            }
            if (!hasContainer) {
                toRemove.add(chunkPos);
            }
        }

        // 统一移除不再需要的区块
        for (var chunkPos : toRemove) {
            cache.removeChunk(level.dimension(), chunkPos);
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        PerfMetrics.updateCacheSize(cache.getCacheSize(level.dimension()));
        if (elapsedMs > 50) {
            ModLog.PERF.warn("processLevelContainers dim={} chunks={} containers={} elapsed={}ms removed={}",
                level.dimension(), chunkSet.size(), processedCount, elapsedMs, toRemove.size());
        } else if (elapsedMs > 10) {
            ModLog.PERF.debug("processLevelContainers dim={} chunks={} containers={} elapsed={}ms removed={}",
                level.dimension(), chunkSet.size(), processedCount, elapsedMs, toRemove.size());
        }
    }

    private void onRegisterPayloadHandler(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(LivingTagPacket.ID.getNamespace()).versioned("1.0.0");
        registrar.playToServer(LivingTagPacket.TYPE, LivingTagPacket.STREAM_CODEC, LivingTagPacket::handle);
        registrar.playToServer(HopperDirectionPacket.TYPE, HopperDirectionPacket.STREAM_CODEC, HopperDirectionPacket::handle);
        registrar.playToServer(SlotDirectionPacket.TYPE, SlotDirectionPacket.STREAM_CODEC, SlotDirectionPacket::handle);
        registrar.playToServer(GuiInteractionPacket.TYPE, GuiInteractionPacket.STREAM_CODEC, GuiInteractionPacket::handle);
        registrar.playToClient(CarriedUpdatePacket.TYPE, CarriedUpdatePacket.STREAM_CODEC, CarriedUpdatePacket::handle);
        registrar.playToClient(EnderChannelSyncPacket.TYPE, EnderChannelSyncPacket.STREAM_CODEC, EnderChannelSyncPacket::handle);
        registrar.playToClient(LivingMapMetadataPacket.TYPE, LivingMapMetadataPacket.STREAM_CODEC, LivingMapMetadataPacket::handle);
        registrar.playToServer(LivingMapGuiTeleportPacket.TYPE, LivingMapGuiTeleportPacket.STREAM_CODEC, LivingMapGuiTeleportPacket::handle);
        registrar.playToServer(LivingChestAccessPacket.TYPE, LivingChestAccessPacket.STREAM_CODEC, LivingChestAccessPacket::handle);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        EnderChannelRegistry.getInstance()
            .setServer(event.getServer());
        LOGGER.info("HELLO from server starting");
    }

    private void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(Capabilities.ItemHandler.ITEM,
            (stack, context) -> {
                if (!LivingItemManager.isLivingItem(stack)) return null;
                if (!LivingChestFunction.isLivingChest(stack)) return null;
                return new LivingChestItemHandler(stack);
            },
            Items.CHEST
        );

        event.registerItem(Capabilities.ItemHandler.ITEM,
            (stack, context) -> {
                if (!LivingItemManager.isLivingItem(stack)) return null;
                if (!LivingEnderChestFunction.isLivingEnderChest(stack)) return null;
                return new LivingEnderChestItemHandler(stack);
            },
            Items.ENDER_CHEST
        );

        LOGGER.info("Registered living item capabilities");
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            EnderChannelRegistry.getInstance()
                .onChunkUnload(serverLevel, event.getChunk().getPos());
        }
    }
}