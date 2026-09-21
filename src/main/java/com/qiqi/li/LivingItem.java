package com.qiqi.li;

import com.qiqi.li.living.domain.tnt.ExplosionLedger;
import com.qiqi.li.living.domain.map.LivingMapEventHandler;
import com.qiqi.li.living.domain.map.ItemFrameMapTeleportHandler;
import com.qiqi.li.living.domain.ender.EnderChannelRegistry;
import com.qiqi.li.living.interaction.InteractionEntry;
import com.qiqi.li.living.transfer.ContainerRuleConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
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
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.capabilities.ICapabilityProvider;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.living.domain.chest.ChestSnapshotProvider;
import com.qiqi.li.living.domain.chest.LivingChestFunction;
import com.qiqi.li.living.domain.ender.LivingEnderChestFunction;
import com.qiqi.li.living.domain.hopper.HopperSnapshotProvider;
import com.qiqi.li.living.domain.redstone.RedstoneSnapshotProvider;
import com.qiqi.li.living.domain.water.LivingWaterBucketFunction;
import com.qiqi.li.living.domain.water.LivingWaterWheelFunction;
import com.qiqi.li.living.domain.map.LivingEnderPearlFunction;
import com.qiqi.li.living.domain.map.LivingMapFunction;
import com.qiqi.li.logging.ModLog;
import com.qiqi.li.living.perf.PerfMetrics;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import com.qiqi.li.living.container.ContainerChunkCache;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.ItemEntityContainerContext;
import com.qiqi.li.network.LivingTagPacket;
import com.qiqi.li.network.HopperDirectionPacket;
import com.qiqi.li.network.SlotDirectionPacket;
import com.qiqi.li.network.GuiInteractionPacket;
import com.qiqi.li.network.CarriedUpdatePacket;
import com.qiqi.li.network.EnderChannelSyncPacket;
import com.qiqi.li.network.LivingChestAccessPacket;
import com.qiqi.li.network.LivingMapMetadataPacket;
import com.qiqi.li.network.LivingMapGuiTeleportPacket;
import com.qiqi.li.network.LivingItemSyncPacket;
import com.qiqi.li.network.ToolMemoryClearPacket;
import com.qiqi.li.network.LivingToolHostPacket;
import com.qiqi.li.network.LivingToolPlayerPacket;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.compat.create.ModCreate;
import com.qiqi.li.living.domain.furnace.LivingFurnaceFunction;
import com.qiqi.li.living.domain.hopper.LivingHopperFunction;
import com.qiqi.li.living.domain.tnt.LivingTntFunction;
import com.qiqi.li.living.domain.redstone.LivingRedstoneFunction;
import com.qiqi.li.living.domain.redstone.LivingRedstoneTorchFunction;
import com.qiqi.li.living.domain.redstone.LivingButtonFunction;
import com.qiqi.li.living.domain.redstone.LivingLeverFunction;
import com.qiqi.li.living.domain.redstone.LivingRedstoneLampFunction;
import com.qiqi.li.living.domain.redstone.LivingRepeaterFunction;
import com.qiqi.li.living.domain.redstone.LivingComparatorFunction;
import com.qiqi.li.living.domain.redstone.LivingRedstoneBlockFunction;
import com.qiqi.li.living.domain.redstone.LivingCopperFunction;
import com.qiqi.li.living.domain.power.LivingWaxedCopperFunction;
import com.qiqi.li.living.function.LivingFlintAndSteelFunction;
import com.qiqi.li.living.interaction.InteractionRegistry;
import com.qiqi.li.living.interaction.IgniteHandler;
import com.qiqi.li.living.interaction.IgniteCarriedHandler;
import com.qiqi.li.living.interaction.ButtonPressHandler;
import com.qiqi.li.living.interaction.LeverToggleHandler;
import com.qiqi.li.living.interaction.RepeaterCycleHandler;
import com.qiqi.li.living.interaction.ComparatorToggleHandler;
import com.qiqi.li.living.interaction.TillToFarmlandHandler;
import com.qiqi.li.living.interaction.PlantCropHandler;
import com.qiqi.li.living.interaction.BonemealHandler;
import com.qiqi.li.living.domain.farmland.CropClassifier;
import com.qiqi.li.living.domain.tools.LivingToolFakePlayerCache;
import com.qiqi.li.living.domain.tools.LivingToolHostSync;
import com.qiqi.li.living.domain.tools.LivingToolFunction;
import com.qiqi.li.living.domain.tools.LivingToolAssist;
import com.qiqi.li.living.domain.tools.LivingToolRecorder;
import com.qiqi.li.living.domain.farmland.Tillables;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import com.qiqi.li.living.domain.chest.LivingChestItemHandler;
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
        NeoForge.EVENT_BUS.register(LivingToolRecorder.class);
        NeoForge.EVENT_BUS.register(LivingToolAssist.class);
        modEventBus.addListener(this::onRegisterPayloadHandler);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // 初始化容器规则配置目录
        ContainerRuleConfig.init(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());

        // 加载容器规则：模组自带（项目级）→ 玩家本地（配置目录）
        ContainerRuleConfig.load();

        ModCreate.init();
        LivingItemManager.registerFunction(new LivingFurnaceFunction());
        LOGGER.info("Registered living furnace function");

        LivingItemManager.registerFunction(new LivingHopperFunction());
        LOGGER.info("Registered living hopper function");

        LivingItemManager.registerFunction(new LivingTntFunction());
        LOGGER.info("Registered living TNT function");

        LivingItemManager.registerFunction(new LivingFlintAndSteelFunction());
        LOGGER.info("Registered living flint & steel function");

        LivingItemManager.registerFunction(new LivingToolFunction());
        LOGGER.info("Registered living tool function");

        LivingItemManager.registerFunction(new LivingChestFunction());
        LOGGER.info("Registered living chest function");

        LivingItemManager.registerFunction(new LivingEnderChestFunction());
        LOGGER.info("Registered living ender chest function");

        LivingItemManager.registerFunction(new LivingWaterBucketFunction());
        LOGGER.info("Registered living water bucket function");

        LivingItemManager.registerFunction(new LivingWaterWheelFunction());
        LOGGER.info("Registered living water wheel function");

        LivingItemManager.registerFunction(new LivingRedstoneFunction());
        LOGGER.info("Registered living redstone function");

        LivingItemManager.registerFunction(new LivingRedstoneTorchFunction());
        LOGGER.info("Registered living redstone torch function");

        LivingItemManager.registerFunction(new LivingButtonFunction());
        LOGGER.info("Registered living button function");

        LivingItemManager.registerFunction(new LivingLeverFunction());
        LOGGER.info("Registered living lever function");

        LivingItemManager.registerFunction(new LivingRedstoneLampFunction());
        LOGGER.info("Registered living redstone lamp function");

        LivingItemManager.registerFunction(new LivingRepeaterFunction());
        LOGGER.info("Registered living repeater function");

        LivingItemManager.registerFunction(new LivingComparatorFunction());
        LOGGER.info("Registered living comparator function");

        LivingItemManager.registerFunction(new LivingRedstoneBlockFunction());
        LOGGER.info("Registered living redstone block function");

        LivingItemManager.registerFunction(new LivingCopperFunction());
        LOGGER.info("Registered living copper function");

        LivingItemManager.registerFunction(new LivingWaxedCopperFunction());
        LOGGER.info("Registered living waxed copper function");

        LivingItemManager.registerFunction(new LivingEnderPearlFunction());
        LOGGER.info("Registered living ender pearl function");

        LivingItemManager.registerFunction(new com.qiqi.li.living.domain.farmland.LivingFarmlandFunction());
        LOGGER.info("Registered living farmland function");

        LivingItemManager.registerFunction(new LivingMapFunction());
        LOGGER.info("Registered living map function");

        // 注册容器快照贡献者（注册驱动，解除 container 包对 domain 类的依赖）
        ContainerSnapshot.registerProvider(new HopperSnapshotProvider());
        ContainerSnapshot.registerProvider(new ChestSnapshotProvider());
        ContainerSnapshot.registerProvider(new RedstoneSnapshotProvider());

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

        // 注册按钮按压处理器和交互规则
        InteractionRegistry.registerHandler("button_press", new ButtonPressHandler());
        InteractionRegistry.register(new InteractionEntry(Items.STONE_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.POLISHED_BLACKSTONE_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.OAK_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.SPRUCE_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.BIRCH_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.JUNGLE_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.ACACIA_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.CHERRY_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.DARK_OAK_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.MANGROVE_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.BAMBOO_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.CRIMSON_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.WARPED_BUTTON, null, 1, "button_press"));
        LOGGER.info("Registered button press interaction rules");

        // 注册拉杆切换处理器和交互规则
        InteractionRegistry.registerHandler("lever_toggle", new LeverToggleHandler());
        InteractionRegistry.register(new InteractionEntry(Items.LEVER, null, 1, "lever_toggle"));
        LOGGER.info("Registered lever toggle interaction rule");

        InteractionRegistry.registerHandler("repeater_cycle", new RepeaterCycleHandler());
        InteractionRegistry.register(new InteractionEntry(Items.REPEATER, null, 1, "repeater_cycle"));
        LOGGER.info("Registered repeater cycle interaction rule");

        InteractionRegistry.registerHandler("comparator_toggle", new ComparatorToggleHandler());
        InteractionRegistry.register(new InteractionEntry(Items.COMPARATOR, null, 1, "comparator_toggle"));
        LOGGER.info("Registered comparator toggle interaction rule");

        // ── 活耕地：活锄头耕活土 + 种植 + 骨粉（docs/idea.md 活耕地设计） ──
        // 锄头不枚举：通配条目 + 谓词按 HOE_TILL 能力识别，模组锄头自动兼容。
        // 可耕目标逐条注册（映射见 Tillables）；谓词把拦截面收窄到「活着的锄头」，
        // 其余光标一律不拦截，原版拿起/分堆操作不受影响。
        InteractionRegistry.registerHandler("till_to_farmland", new TillToFarmlandHandler());
        for (Item tillable : Tillables.tillableTargets()) {
            InteractionRegistry.register(new InteractionEntry(tillable, null, 1, "till_to_farmland",
                false, Tillables::canTillWith));
        }
        LOGGER.info("Registered till_to_farmland interaction rules");

        // 种植：通配条目 + triggerFilter 收窄到「可种植种子」——只拦种子光标，
        // 空手/其他物品右键不拦截（原版拿起/分堆操作不受影响；服务端 handler 仍双重校验）
        // 骨粉：精确触发器（活骨粉）——两趟优先级匹配分流（见 InteractionRegistry javadoc）
        InteractionRegistry.registerHandler("plant_crop", new PlantCropHandler());
        InteractionRegistry.register(new InteractionEntry(Items.FARMLAND, null, 1, "plant_crop",
            false, PlantCropHandler::canPlantWith));
        InteractionRegistry.registerHandler("bonemeal", new BonemealHandler());
        InteractionRegistry.register(new InteractionEntry(Items.FARMLAND, Items.BONE_MEAL, 1, "bonemeal"));
        LOGGER.info("Registered farmland plant/bonemeal interaction rules");
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
    public void onServerTick(ServerTickEvent.Pre event) {
        var server = event.getServer();

        for (var player : server.getPlayerList().getPlayers()) {
            ContainerLivingItemHandler.processContainer(player.getInventory(), player.level());
        }

        reusableHandlerMap.clear();
        reusableKeySet.clear();

        for (var level : server.getAllLevels()) {
            processLevelContainers(level);
            processItemEntityContainers(level);
            // K2：必须在容器处理【之后】flush，才能收齐本 tick 的登记
            LivingToolHostSync.flush(level);
            // 联机可见性（最小版）：把各玩家背包里的无记忆活工具广播给附近客户端
            com.qiqi.li.living.domain.tools.LivingToolPlayerSync.flush(level);
        }

        // 待炸账本：按区块分帧推进爆炸破坏（已加载的按预算处理，未加载的等自然加载）
        ExplosionLedger.flushAll(server);
    }

    /**
     * 掉落物形态（{@code L-f}）—— 遍历世界中「装着活工具」的掉落物并 tick。
     *
     * <p>复用现成的容器 tick 管线（{@link ItemEntityContainerContext} 把掉落物包装成<b>单栈容器</b>），
     * 因此回放逻辑与容器形态<b>完全同源</b>，没有第二套平行实现。</p>
     *
     * <p><b>为什么直接遍历实体、不建索引</b>：{@link ContainerChunkCache} 那套区块级缓存
     * 依赖"方块容器位置稳定"；而掉落物会移动、会被合并、会被卸载，
     * 维护索引的失效成本高于收益。{@code getAllEntities()} 是 O(实体数) 的浅遍历，
     * 绝大多数在 {@code instanceof} 处短路，开销可忽略。</p>
     *
     * <p>⚠️ <b>只处理活工具</b>：其它活物品的功能类都假定自己有方块坐标
     * （从 {@code getBlockPos()} 取），放进掉落物上下文会拿到 {@code null}。
     * 要让更多活物品支持掉落物形态，得先给 {@code LivingItemFunction} 加宿主能力声明 ——
     * 属于另一个话题，不在 {@code L-f} 范围内。</p>
     */
    private void processItemEntityContainers(ServerLevel level) {
        for (var entity : level.getAllEntities()) {
            if (entity instanceof ItemEntity itemEntity
                    && LivingToolRecorder.isLivingTool(itemEntity.getItem())) {
                ContainerLivingItemHandler.processContext(
                    new ItemEntityContainerContext(itemEntity, level), level);
            }
        }
    }

    private void processLevelContainers(ServerLevel level) {
        var cache = ContainerChunkCache.getInstance();

        // 先消费上一 tick 累积的重扫请求，确保刚放置的容器（含延迟初始化的模组容器）
        // 与新加载区块里的容器已入缓存。
        // ⚠️ 这里（ServerTickEvent.Pre）也是唯一允许做能力查询的重扫入口 ——
        // 区块加载事件里绝不能扫，见 ContainerChunkCache.onChunkLoad 的红线说明。
        cache.flushPendingRescans(level);

        // ⚠️ getProcessableChunks（而非原始缓存）会过滤掉"已加载但不 tick"的最外一圈（33 圈）：
        // 各活物品逻辑会读相邻一格的方块（红石 getSignal / 活漏斗邻居容器 / 大箱子另一半 /
        // 活水车下方一格），33 圈的邻居可能落在未加载的生成余量圈 ⇒ 读邻居会强制加载区块。
        // ticking 区块则**可证明安全**（ChunkMap.prepareTickingChunk 保证其 3×3 邻域已是 FULL）。
        // 详见 ContainerChunkCache.getProcessableChunks 的说明。
        var chunkSet = cache.getProcessableChunks(level);
        if (chunkSet.isEmpty()) return;

        var toRemove = new java.util.ArrayList<ChunkPos>();

        long startNanos = System.nanoTime();
        int processedCount = 0;

        for (var chunkPos : chunkSet) {
            var chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
            if (chunk == null) {
                // 不立即移除：区块可能正在加载中，FULL future 尚未完成
                // 由 onChunkUnload 事件 + 定期清理负责移除
                continue;
            }

            boolean hasContainer = false;
            for (var be : new java.util.ArrayList<>(chunk.getBlockEntities().values())) {
                var pos = be.getBlockPos();

                if (be instanceof RandomizableContainer rc && rc.getLootTable() != null) {
                    continue;
                }

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

        // 定期清理：每 6000 ticks（约 5 分钟）清理一次真正不再加载的区块
        cache.cleanupStaleEntries(level, 6000);
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
        // 活工具：左键空气清除挖掘记忆（服务端感知不到左键空气，需客户端告知）
        registrar.playToServer(ToolMemoryClearPacket.TYPE, ToolMemoryClearPacket.STREAM_CODEC, ToolMemoryClearPacket::handle);
        // 服务端 → 客户端：下发容器运行时数据（由 ContainerRuntimeCache.flushToClients 发送）
        registrar.playToClient(LivingItemSyncPacket.TYPE, LivingItemSyncPacket.STREAM_CODEC, LivingItemSyncPacket::handle);
        // 活工具（K2）：近处方块容器里的活工具清单（位置 + ItemStack），
        // 供客户端渲染记忆射线与（未来的）悬浮模型 —— 不开 GUI 时客户端拿不到箱子内容
        registrar.playToClient(LivingToolHostPacket.TYPE, LivingToolHostPacket.STREAM_CODEC, LivingToolHostPacket::handle);
        // 联机可见性（最小版）：近处玩家背包里的无记忆活工具清单（UUID + ItemStack），
        // 供客户端给【其它玩家】画背后的待机环（自己那份不发 —— 本机渲染更实时，且能走挖掘环）
        registrar.playToClient(LivingToolPlayerPacket.TYPE, LivingToolPlayerPacket.STREAM_CODEC, LivingToolPlayerPacket::handle);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        EnderChannelRegistry.getInstance()
            .setServer(event.getServer());
        LOGGER.info("HELLO from server starting");
    }

    /**
     * 服务端关闭 —— 清空全部进程级静态缓存。
     *
     * <p>单人游戏中「退出存档 → 进入另一个存档」不会重启 JVM，
     * 若不清理会把上一个世界的路由表、区块缓存与容器级数据带入新世界。</p>
     */
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        EnderChannelRegistry.getInstance().clearAll();
        ContainerChunkCache.getInstance().clear();
        ContainerLivingItemHandler.clearAllCaches();
        // 待炸账本的**内存调度表**要清（条目本身随存档走，不需要清）。
        // ⚠️ 单人游戏「退出存档 → 进另一个存档」不重启 JVM：不清会把上一个维度的调度表带过来。
        ExplosionLedger.clearAllRuntimeState(event.getServer());
        // 活工具的 FakePlayer 缓存（L26）：维度+主人 keyed，跨存档必须清
        LivingToolFakePlayerCache.clear();
        // 活工具容器同步（K2）：每个玩家的"上次发出内容"，跨存档必须清
        LivingToolHostSync.clear();
        LOGGER.info("Cleared living item caches on server stop");
    }

    /**
     * 区块加载事件 —— <b>只登记坐标，绝不碰世界</b>。
     *
     * <p>⚠️ 本事件在区块 FULL 任务<b>内部</b>触发，做任何世界交互（能力查询 / 读方块 /
     * 查方块实体）都可能触发跨模组同步区块加载 ⇒ 主线程自等自。红线说明见
     * {@link ContainerChunkCache#onChunkLoad}。</p>
     *
     * <p>用途：把「待炸账本」里落在该区块的爆炸补上 —— 这是原版 TNT 引信模型
     * （世界只在被观测的地方演化）在爆炸范围上的落地。真正的破坏在
     * {@code ServerTickEvent.Pre} 由 {@link ExplosionLedger#flushAll} 执行。</p>
     */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ExplosionLedger.onChunkLoaded(level, event.getChunk().getPos());
        }
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

        // ── 红电对外能量接口（§3.6 v17.5）：宽注册 + 让位 + 铜灯通用电池 ──
        // 宽注册：全部 BlockEntityType（provider 内部三层判定：非容器/直接实现者/已有主人 → 让位）
        net.neoforged.neoforge.capabilities.ICapabilityProvider<BlockEntity, Direction, IEnergyStorage> blockEnergyProvider =
            (be, side) -> com.qiqi.li.living.domain.power.ContainerEnergyStorage.resolveProvider(be, side);
        for (var beType : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, beType, blockEnergyProvider);
        }

        // ── 铜灯物品 = 通用电池（双向：电池槽放电 + 充能槽充电，§3.6） ──
        event.registerItem(Capabilities.EnergyStorage.ITEM,
            (stack, context) -> new com.qiqi.li.living.domain.power.BulbItemEnergyStorage(stack),
            Items.WAXED_COPPER_BULB, Items.WAXED_EXPOSED_COPPER_BULB,
            Items.WAXED_WEATHERED_COPPER_BULB, Items.WAXED_OXIDIZED_COPPER_BULB);

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