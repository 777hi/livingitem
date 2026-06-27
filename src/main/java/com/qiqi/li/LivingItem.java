package com.qiqi.li;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.IdentityHashMap;
import com.qiqi.li.network.LivingTagPacket;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.LivingFurnaceFunction;
import com.qiqi.li.living.ContainerLivingItemHandler;
import com.qiqi.li.living.ContainerChunkCache;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

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

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock("example_block", BlockBehaviour.Properties.of().mapColor(MapColor.STONE));
    public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);
    public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerSimpleItem("example_item", new Item.Properties());
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.living_item"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(EXAMPLE_ITEM.get());
            }).build());

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public LivingItem(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        LivingItemManager.DATA_COMPONENT_TYPES.register(modEventBus);
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(ContainerChunkCache.getInstance());
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::onRegisterPayloadHandler);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach((item) -> LOGGER.info("ITEM >> {}", item));

        LivingItemManager.registerFunction(new LivingFurnaceFunction());
        LOGGER.info("已注册活熔炉功能");
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
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();

        for (var player : server.getPlayerList().getPlayers()) {
            ContainerLivingItemHandler.processContainer(player.getInventory(), player.level());
        }

        for (var level : server.getAllLevels()) {
            processLevelContainers(level);
        }
    }

    /**
     * 处理指定世界中所有容器内的活物品。
     *
     * 通过 ContainerChunkCache 获取含容器的区块列表，
     * 只遍历这些区块中的方块实体，避免全量扫描。
     *
     * 去重机制：
     *   - processedContainers：非箱子容器去重（IdentityHashMap，按对象引用）
     *   - processedChests：箱子方块实体去重（大箱子左右两半共享同一组数据，
     *     只需处理一次，否则活物品逻辑会执行两次导致速度翻倍）
     *
     * @param level 服务端世界
     */
    private void processLevelContainers(ServerLevel level) {
        var cachedChunks = ContainerChunkCache.getInstance().getCachedChunks(level.dimension());
        IdentityHashMap<Container, Boolean> processedContainers = new IdentityHashMap<>();
        IdentityHashMap<ChestBlockEntity, Boolean> processedChests = new IdentityHashMap<>();

        for (var cPos : cachedChunks) {
            if (!level.hasChunk(cPos.x, cPos.z)) continue;
            LevelChunk chunk = level.getChunk(cPos.x, cPos.z);
            ContainerLivingItemHandler.processBlockEntities(
                    chunk.getBlockEntities().values(), level, processedContainers, processedChests);
        }
    }

    private void onRegisterPayloadHandler(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(LivingTagPacket.ID.getNamespace()).versioned("1.0.0");
        registrar.playToServer(LivingTagPacket.TYPE, LivingTagPacket.STREAM_CODEC, LivingTagPacket::handle);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(EXAMPLE_BLOCK_ITEM);
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("HELLO from server starting");
    }
}