package com.qiqi.li.living.domain.brewing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qiqi.li.living.api.LivingItemFunction.SlotEntry;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.LivingItemRegistrationScope;
import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.container.TickableContainerContext;
import com.qiqi.li.living.domain.brewing.BrewingStandSegment.BrewingRuntime;
import com.qiqi.li.living.domain.furnace.FurnaceSegment;
import com.qiqi.li.living.domain.furnace.LivingFurnaceFunction;
import com.qiqi.li.living.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.runtime.RuntimeSegments;
import com.qiqi.li.network.LivingItemRuntimeSync;
import com.qiqi.li.network.LivingItemSyncPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/** 活酿造台批次状态机及完成音效测试。 */
class BrewingBatchLogicTest {

    private static final Item WART = Items.NETHER_WART;
    private static final Item GLOWSTONE = Items.GLOWSTONE_DUST;
    private static final int FULL = BrewingBatchLogic.BREW_TIME;
    private static final int FUEL = BrewingBatchLogic.FUEL_USES;

    private static final Optional<Item> NO_BATCH = Optional.empty();
    private static final Optional<Item> WART_BATCH = Optional.of(WART);

    private static final BlockPos CHEST_POS = new BlockPos(24, 64, -16);
    private static final int STAND_SLOT = 13;
    private static final int BOTTLE_SLOT = 4;
    private static final int INGREDIENT_SLOT = 14;

    @Test
    @DisplayName("世界容器酿造完成：同坐标箱子只给本维度的查看者播放原版音效")
    void completedWorldBatch_playsSoundOnlyInItsDimension() {
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(playerList);
        ServerLevel overworld = brewingLevel(server, Level.OVERWORLD);
        ServerLevel nether = brewingLevel(server, Level.NETHER);
        ServerPlayer viewer = soundPlayer(overworld);
        ServerPlayer netherViewer = soundPlayer(nether);
        ChestMenu menu = openChest(viewer, overworld);
        openChest(netherViewer, nether);
        when(playerList.getPlayers()).thenReturn(List.of(viewer, netherViewer));
        var context = ContainerContexts.resolve(viewer, menu.slots.getFirst());

        finishBatch(context, overworld);

        assertBrewSound(viewer, Vec3.atCenterOf(CHEST_POS));
        verifyNoInteractions(netherViewer.connection);
    }

    @Test
    @DisplayName("实体容器酿造完成：同位箱子矿车只给实际宿主的查看者播放音效")
    void completedEntityBatch_playsSoundOnlyForItsHostViewer() {
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(playerList);
        ServerLevel level = brewingLevel(server, Level.OVERWORLD);
        ServerPlayer viewer = soundPlayer(level);
        ServerPlayer otherViewer = soundPlayer(level);
        Vec3 position = Vec3.atCenterOf(CHEST_POS);
        when(viewer.position()).thenReturn(position);
        when(otherViewer.position()).thenReturn(position);
        var menu = openChestMinecart(viewer, level, position);
        openChestMinecart(otherViewer, level, position);
        when(playerList.getPlayers()).thenReturn(List.of(viewer, otherViewer));
        var context = ContainerContexts.resolve(viewer, menu.slots.getFirst());

        finishBatch(context, level);

        assertBrewSound(viewer, position);
        verifyNoInteractions(otherViewer.connection);
    }

    @Test
    @DisplayName("末影箱酿造完成：主人未开末影箱也直接收到音效，旁人不收")
    void completedOwnedBatch_playsSoundForOwnerWithoutOpenMenu() {
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(playerList);
        ServerLevel level = brewingLevel(server, Level.OVERWORLD);
        ServerPlayer owner = soundPlayer(level);
        ServerPlayer bystander = soundPlayer(level);
        when(playerList.getPlayers()).thenReturn(List.of(owner, bystander));
        Vec3 ownerPosition = new Vec3(8.25, 64.5, -3.75);
        when(owner.position()).thenReturn(ownerPosition);
        PlayerEnderChestContainer enderChest = new PlayerEnderChestContainer();
        when(owner.getEnderChestInventory()).thenReturn(enderChest);
        var context = ContainerContexts.resolve(owner, new Slot(enderChest, 0, 0, 0));

        finishBatch(context, level);

        assertBrewSound(owner, ownerPosition);
        verifyNoInteractions(bystander.connection);
    }

    @Test
    @DisplayName("同槽熔炉换酿造台：首拍进度不被晚删，取走最后一台后向箱子查看者发送空快照")
    void replacesFurnaceWithoutLosingBrewProgress_thenRemovalFlushesEmptySnapshot() {
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(playerList);
        ServerLevel level = brewingLevel(server, Level.OVERWORLD);
        ServerPlayer viewer = soundPlayer(level);
        ChestMenu menu = openChest(viewer, level);
        when(playerList.getPlayers()).thenReturn(List.of(viewer));
        var context = ContainerContexts.resolve(viewer, menu.slots.getFirst());
        assertNotNull(context);
        String key = context.getCacheKey();
        var positions = Map.of(key, List.of(CHEST_POS));
        ContainerLivingItemHandler.clearAllCaches();
        ContainerRuntimeCache.removeContainer(key);

        try (var functions = LivingItemRegistrationScope.isolated(List.of(
                new LivingFurnaceFunction(), new LivingBrewingStandFunction()))) {
            ItemStack furnace = new ItemStack(Items.FURNACE);
            LivingItemManager.setLiving(furnace, true);
            context.setItem(STAND_SLOT, furnace);
            ContainerLivingItemHandler.processContext(context, level);
            assertNotNull(ContainerRuntimeCache.get(context, STAND_SLOT).get(FurnaceSegment.INSTANCE));

            ItemStack stand = new ItemStack(Items.BREWING_STAND);
            LivingItemManager.setLiving(stand, true);
            LivingBrewingStandData.set(stand, LivingBrewingStandData.DEFAULT.withFuel(10));
            context.setItem(STAND_SLOT, stand);
            context.setItem(BOTTLE_SLOT, PotionContents.createItemStack(Items.POTION, Potions.WATER));
            context.setItem(INGREDIENT_SLOT, new ItemStack(WART));
            assertEquals(10, LivingBrewingStandData.of(context.getItem(STAND_SLOT)).fuel());

            ContainerLivingItemHandler.processContext(context, level);
            var firstTick = ContainerRuntimeCache.get(context, STAND_SLOT);
            assertNull(firstTick.get(FurnaceSegment.INSTANCE));
            assertEquals(new BrewingRuntime(FULL, WART_BATCH), firstTick.get(BrewingStandSegment.INSTANCE));
            assertEquals(9, LivingBrewingStandData.of(context.getItem(STAND_SLOT)).fuel());

            ContainerLivingItemHandler.processContext(context, level);
            assertEquals(new BrewingRuntime(FULL - 1, WART_BATCH),
                ContainerRuntimeCache.get(context, STAND_SLOT).get(BrewingStandSegment.INSTANCE));
            assertEquals(9, LivingBrewingStandData.of(context.getItem(STAND_SLOT)).fuel());
            var activeSnapshot = ContainerRuntimeCache.getSnapshot(key);
            LivingItemRuntimeSync.flush(List.of(viewer), positions::get);
            assertEquals(activeSnapshot, ContainerRuntimeCache.getSnapshot(key), "同步后不能丢失新设备进度");

            context.setItem(STAND_SLOT, ItemStack.EMPTY);
            ContainerLivingItemHandler.processContext(context, level);
            assertFalse(ContainerRuntimeCache.holdsContainer(key), "最后一台设备离槽后必须立即清除运行时状态");
            assertEquals(new PotionContents(Potions.WATER), context.getItem(BOTTLE_SLOT).get(DataComponents.POTION_CONTENTS));
            assertTrue(context.getItem(INGREDIENT_SLOT).is(WART));
            LivingItemRuntimeSync.flush(List.of(viewer), positions::get);

            var sent = ArgumentCaptor.forClass(LivingItemSyncPacket.class);
            verify(viewer.connection, times(2)).send(sent.capture());
            assertEquals(key, sent.getAllValues().getFirst().containerKey());
            assertEquals(activeSnapshot, sent.getAllValues().getFirst().slotData());
            assertEquals(key, sent.getValue().containerKey());
            assertTrue(sent.getValue().slotData().isEmpty(), "必须发同键空快照以清掉客户端旧进度");
        } finally {
            ContainerRuntimeCache.removeContainer(key);
            ContainerLivingItemHandler.clearAllCaches();
        }
    }

    private static ServerLevel brewingLevel(MinecraftServer server, ResourceKey<Level> dimension) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(dimension);
        when(level.getServer()).thenReturn(server);
        PotionBrewing.Builder recipes = new PotionBrewing.Builder(FeatureFlags.VANILLA_SET);
        recipes.addContainer(Items.POTION);
        recipes.addMix(Potions.WATER, WART, Potions.AWKWARD);
        PotionBrewing brewing = recipes.build();
        when(level.potionBrewing()).thenReturn(brewing);
        when(level.getRandom()).thenReturn(RandomSource.create(42));
        return level;
    }

    private static ServerPlayer soundPlayer(ServerLevel level) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.level()).thenReturn(level);
        when(player.serverLevel()).thenReturn(level);
        when(player.getStringUUID()).thenReturn(UUID.randomUUID().toString());
        when(player.isSpectator()).thenReturn(true);
        Inventory inventory = new Inventory(player);
        when(player.getInventory()).thenReturn(inventory);
        player.containerMenu = new InventoryMenu(inventory, false, player);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        return player;
    }

    private static ChestMenu openChest(ServerPlayer viewer, ServerLevel level) {
        ChestBlockEntity chest = new ChestBlockEntity(CHEST_POS, Blocks.CHEST.defaultBlockState());
        chest.setLevel(level);
        when(level.getBlockEntity(CHEST_POS)).thenReturn(chest);
        when(level.getBlockState(CHEST_POS)).thenReturn(chest.getBlockState());
        doReturn(new InvWrapper(chest)).when(level).getCapability(Capabilities.ItemHandler.BLOCK, CHEST_POS, null);
        ChestMenu menu = ChestMenu.threeRows(1, viewer.getInventory(), chest);
        viewer.containerMenu = menu;
        return menu;
    }

    private static AbstractContainerMenu openChestMinecart(ServerPlayer viewer, ServerLevel level, Vec3 position) {
        MinecartChest cart = spy(new MinecartChest(level, position.x, position.y, position.z));
        doReturn(new InvWrapper(cart)).when(cart).getCapability(Capabilities.ItemHandler.ENTITY);
        when(level.getEntity(cart.getUUID())).thenReturn(cart);
        var menu = cart.createMenu(1, viewer.getInventory());
        viewer.containerMenu = menu;
        return menu;
    }

    private static void finishBatch(TickableContainerContext context, ServerLevel level) {
        assertNotNull(context);
        ItemStack stand = new ItemStack(Items.BREWING_STAND);
        LivingItemManager.setLiving(stand, true);
        ItemStack water = PotionContents.createItemStack(Items.POTION, Potions.WATER);
        TickContext tick = new TickContext(context);
        context.setTickContext(tick);   // 只驱动功能 tick，不另行 flush 槽位同步包。
        String key = context.getCacheKey();
        try {
            context.setItem(STAND_SLOT, stand);
            context.setItem(BOTTLE_SLOT, water);
            context.setItem(INGREDIENT_SLOT, new ItemStack(WART));
            ContainerRuntimeCache.update(context, STAND_SLOT, RuntimeSegments.EMPTY.with(
                BrewingStandSegment.INSTANCE, new BrewingRuntime(1, WART_BATCH)));

            new LivingBrewingStandFunction().tick(
                List.of(new SlotEntry(STAND_SLOT, context.getItem(STAND_SLOT))), context, tick, level);

            assertEquals(new PotionContents(Potions.AWKWARD),
                context.getItem(BOTTLE_SLOT).get(DataComponents.POTION_CONTENTS), "实际酿造必须完成");
            assertTrue(context.getItem(INGREDIENT_SLOT).isEmpty(), "完成时实际扣除材料");
        } finally {
            context.setTickContext(null);
            ContainerRuntimeCache.removeContainer(key);
            ContainerLivingItemHandler.clearAllCaches();
        }
    }

    private static void assertBrewSound(ServerPlayer viewer, Vec3 position) {
        var sent = ArgumentCaptor.forClass(ClientboundSoundPacket.class);
        verify(viewer.connection).send(sent.capture());
        ClientboundSoundPacket packet = sent.getValue();
        assertEquals(SoundEvents.BREWING_STAND_BREW, packet.getSound().value());
        assertEquals(SoundSource.BLOCKS, packet.getSource());
        assertEquals(position, new Vec3(packet.getX(), packet.getY(), packet.getZ()));
    }

    // ---------------- 启动（D7：启动扣 1 次燃料）----------------

    @Test
    @DisplayName("空闲 + 可酿造 + 有燃料 ⇒ 启动 400 tick 批次，并记录材料")
    void idle_startsBatch() {
        var step = BrewingBatchLogic.advance(0, NO_BATCH, true, WART, FUEL, 1);

        var start = assertInstanceOf(BrewingBatchLogic.Step.Start.class, step);
        assertEquals(FULL, start.brewTime());
        assertEquals(WART, start.ingredient());
    }

    @Test
    @DisplayName("有燃料但不可酿造（材料失效 / 无可配瓶子）⇒ 不启动")
    void notBrewable_doesNotStart() {
        assertInstanceOf(BrewingBatchLogic.Step.Idle.class,
            BrewingBatchLogic.advance(0, NO_BATCH, false, WART, FUEL, 1));
    }

    @Test
    @DisplayName("可酿造但燃料为 0 ⇒ 不启动")
    void noFuel_doesNotStart() {
        assertInstanceOf(BrewingBatchLogic.Step.Idle.class,
            BrewingBatchLogic.advance(0, NO_BATCH, true, WART, 0, 1));
    }

    // ---------------- 推进 / 完成 ----------------

    @Test
    @DisplayName("酿造中且一切正常 ⇒ 倒计时按 step 递减")
    void brewing_advancesByStep() {
        var step = BrewingBatchLogic.advance(FULL, WART_BATCH, true, WART, 0, 1);
        assertEquals(FULL - 1, assertInstanceOf(BrewingBatchLogic.Step.Continue.class, step).brewTime());

        var fast = BrewingBatchLogic.advance(FULL, WART_BATCH, true, WART, 0, 8);
        assertEquals(FULL - 8, assertInstanceOf(BrewingBatchLogic.Step.Continue.class, fast).brewTime());
    }

    @Test
    @DisplayName("倒计时归零且仍可酿造 ⇒ 完成（D7：完成扣 1 份材料）")
    void countdownReachesZero_completes() {
        assertInstanceOf(BrewingBatchLogic.Step.Complete.class,
            BrewingBatchLogic.advance(1, WART_BATCH, true, WART, 0, 1));
    }

    @Test
    @DisplayName("堆叠 64：纯推进 7 tick 完成（step=count，D11；含启动那一 tick 实际 8 tick，见 tech §5）")
    void stacked_completesInSevenTicks() {
        int brewTime = FULL;
        int ticks = 0;
        while (brewTime > 0) {
            var step = BrewingBatchLogic.advance(brewTime, WART_BATCH, true, WART, 0, 64);
            ticks++;
            if (step instanceof BrewingBatchLogic.Step.Complete) {
                brewTime = 0;
            } else {
                brewTime = ((BrewingBatchLogic.Step.Continue) step).brewTime();
            }
        }
        assertEquals(7, ticks);
    }

    // ---------------- 作废（D8：只有两条判据）----------------

    @Test
    @DisplayName("酿造中材料失效（不可酿造）⇒ 作废")
    void notBrewable_aborts() {
        assertInstanceOf(BrewingBatchLogic.Step.Abort.class,
            BrewingBatchLogic.advance(FULL, WART_BATCH, false, WART, 0, 1));
    }

    @Test
    @DisplayName("酿造中材料槽被清空（材料 Item 变 null）⇒ 作废")
    void ingredientCleared_aborts() {
        assertInstanceOf(BrewingBatchLogic.Step.Abort.class,
            BrewingBatchLogic.advance(FULL, WART_BATCH, true, null, 0, 1));
    }

    @Test
    @DisplayName("酿造中换成另一种材料 ⇒ 作废")
    void differentIngredient_aborts() {
        assertInstanceOf(BrewingBatchLogic.Step.Abort.class,
            BrewingBatchLogic.advance(FULL, WART_BATCH, true, GLOWSTONE, 0, 1));
    }

    @Test
    @DisplayName("材料【数量】变化不算换（D8：只比 Item）⇒ 继续酿造")
    void ingredientCountChange_continues() {
        // 状态机只拿到 Item，数量变化在调用侧不可见 ⇒ 同 Item 必继续
        var step = BrewingBatchLogic.advance(FULL, WART_BATCH, true, WART, 0, 1);
        assertInstanceOf(BrewingBatchLogic.Step.Continue.class, step);
    }

    @Test
    @DisplayName("完成那一 tick 不再检查材料变化（照抄原版 else-if 语义）⇒ 仍完成")
    void completingTick_ignoresIngredientChange() {
        assertInstanceOf(BrewingBatchLogic.Step.Complete.class,
            BrewingBatchLogic.advance(1, WART_BATCH, true, GLOWSTONE, 0, 1));
    }

    // ---------------- 堆叠加速（D11）----------------

    @Test
    @DisplayName("step = 堆叠数；单个 = 原版 400t；异常堆叠数兜底为 1")
    void stepFor_matchesStackCount() {
        assertEquals(1, BrewingBatchLogic.stepFor(1), "单个 = 原版速度");
        assertEquals(2, BrewingBatchLogic.stepFor(2));
        assertEquals(64, BrewingBatchLogic.stepFor(64));
        assertEquals(1, BrewingBatchLogic.stepFor(0), "空堆叠兜底为 1，避免除零 / 停滞");
    }

    @Test
    @DisplayName("堆叠只加速过程：每批耗时随堆叠缩短，但每批仍只扣 1 次燃料")
    void stacking_onlySpeedsUpProcess() {
        // 启动一次只产生一个 Start（⇒ 调用侧只扣 1 次燃料），与堆叠数无关
        for (int count : new int[] {1, 8, 64}) {
            var step = BrewingBatchLogic.advance(0, NO_BATCH, true, WART, FUEL,
                BrewingBatchLogic.stepFor(count));
            assertTrue(step instanceof BrewingBatchLogic.Step.Start,
                "堆叠 " + count + " 也应正常启动");
        }
    }

    // ---------------- 批次标记（白幕）----------------

    @Test
    @DisplayName("批次标记：Start 写当下 / Continue 保持 / Complete·Abort·Idle 一律收起（Idle = 自愈）")
    void batchMarkAfter_lifecycle() {
        long now = 1000L;

        assertEquals(now,
            BrewingBatchLogic.batchMarkAfter(new BrewingBatchLogic.Step.Start(FULL, WART), -1L, now),
            "Start ⇒ 记下当下（客户端据此起播白幕）");

        assertEquals(777L,
            BrewingBatchLogic.batchMarkAfter(new BrewingBatchLogic.Step.Continue(300), 777L, now),
            "进行中保持原标记 ⇒ 稳态零写入");

        assertEquals(-1L, BrewingBatchLogic.batchMarkAfter(new BrewingBatchLogic.Step.Complete(), 777L, now),
            "完成 ⇒ 收起");
        assertEquals(-1L, BrewingBatchLogic.batchMarkAfter(new BrewingBatchLogic.Step.Abort(), 777L, now),
            "作废 ⇒ 收起");

        assertEquals(-1L, BrewingBatchLogic.batchMarkAfter(new BrewingBatchLogic.Step.Idle(), 777L, now),
            "🔴 自愈回归：无批次却留着标记 ⇒ 必须收起。物品移出容器后运行时缓存作废（brewTime 归零）"
                + "⇒ 走不到 Complete/Abort ⇒ 标记残留 ⇒ 客户端白幕会在「没有进度」时继续播完一轮");

        assertEquals(-1L, BrewingBatchLogic.batchMarkAfter(new BrewingBatchLogic.Step.Idle(), -1L, now),
            "本来就是 -1 ⇒ 保持 -1（调用侧据此判定「无变化」⇒ 不写组件，稳态零写入）");
    }
}
