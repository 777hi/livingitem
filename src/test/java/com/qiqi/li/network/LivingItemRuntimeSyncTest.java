package com.qiqi.li.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qiqi.li.living.container.ContainerKeys;
import com.qiqi.li.living.container.ItemFormContainers;
import com.qiqi.li.living.domain.hopper.HopperSegment;
import com.qiqi.li.living.domain.hopper.HopperSegment.HopperRuntime;
import com.qiqi.li.living.domain.hopper.ResolvedSlotData;
import com.qiqi.li.living.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.runtime.RuntimeSegments;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * 运行时数据发包的守卫（2026-10-18 起；门控判定 2026-10-23 迁至 ContainerViewersTest）。
 *
 * <p>守卫的回归：末影箱包曾无条件每 tick 直发主人（无 viewer 匹配）⇒ 冲掉客户端通用缓存里
 * 「当前打开的世界容器」⇒ 容器 GUI 的 tooltip 遥测全丢。同族 bug 在流体轨连修三次
 * （{@code FluidFlowServerSync}「第 ③ 次泄漏修复」），本次是同一病因换轨重现。
 * 判据本身极简，测试的价值在于把规则钉住：将来谁把门控删了 / 把背包也门控了，这里立刻红。</p>
 */
class LivingItemRuntimeSyncTest {

    private static final BlockPos CHEST_POS = new BlockPos(4, 64, 4);
    private static final String BARE_CHEST_KEY = "chest_4_64_4";

    @BeforeEach
    void setUp() {
        ContainerRuntimeCache.clear();
    }

    @AfterEach
    void tearDown() {
        ContainerRuntimeCache.clear();
        ItemFormContainers.clearForTest();
    }

    @Test
    @DisplayName("真实 runtime flush：同坐标的两个维度各收自己的非空快照")
    void worldBlock_samePositionInTwoDimensions_sendsOnlyOwnSnapshot() {
        ServerPlayer overworldViewer = chestViewer(Level.OVERWORLD);
        ServerPlayer netherViewer = chestViewer(Level.NETHER);
        List<ServerPlayer> online = List.of(overworldViewer, netherViewer);
        String overworldKey = "minecraft:overworld|" + BARE_CHEST_KEY;
        String netherKey = "minecraft:the_nether|" + BARE_CHEST_KEY;
        HopperRuntime overworldRuntime = stageHopper(overworldKey, 1);
        HopperRuntime netherRuntime = stageHopper(netherKey, 2);
        var positions = Map.of(overworldKey, List.of(CHEST_POS), netherKey, List.of(CHEST_POS));

        LivingItemRuntimeSync.flush(online, positions::get);

        assertHopperSnapshot(overworldViewer, overworldKey, overworldRuntime);
        assertHopperSnapshot(netherViewer, netherKey, netherRuntime);
    }

    @Test
    @DisplayName("真实 runtime flush：裸世界键即使能查到坐标也不派发")
    void worldBlock_bareKeyIsNotDispatched() {
        ServerPlayer overworldViewer = chestViewer(Level.OVERWORLD);
        ServerPlayer netherViewer = chestViewer(Level.NETHER);
        stageHopper(BARE_CHEST_KEY, 1);
        var positions = Map.of(BARE_CHEST_KEY, List.of(CHEST_POS));

        LivingItemRuntimeSync.flush(List.of(overworldViewer, netherViewer), positions::get);

        verifyNoInteractions(overworldViewer.connection, netherViewer.connection);
    }

    private static ServerPlayer chestViewer(ResourceKey<Level> dimension) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(dimension);
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.level()).thenReturn(level);
        when(player.serverLevel()).thenReturn(level);
        when(player.isSpectator()).thenReturn(true);
        Inventory inventory = new Inventory(player);
        when(player.getInventory()).thenReturn(inventory);
        ChestBlockEntity chest = new ChestBlockEntity(CHEST_POS, Blocks.CHEST.defaultBlockState());
        chest.setLevel(level);
        when(level.getBlockEntity(CHEST_POS)).thenReturn(chest);
        player.containerMenu = ChestMenu.threeRows(1, inventory, chest);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        return player;
    }

    private static HopperRuntime stageHopper(String key, int cooldown) {
        HopperRuntime runtime = new HopperRuntime(cooldown, new ResolvedSlotData(0, 1, 2, 27, 9));
        ContainerRuntimeCache.update(new FakeContainerContext(27, 9, key), 0,
            RuntimeSegments.EMPTY.with(HopperSegment.INSTANCE, runtime));
        return runtime;
    }

    private static void assertHopperSnapshot(ServerPlayer viewer, String key, HopperRuntime runtime) {
        var sent = ArgumentCaptor.forClass(LivingItemSyncPacket.class);
        verify(viewer.connection).send(sent.capture());
        assertEquals(key, sent.getValue().containerKey());
        assertEquals(runtime, sent.getValue().slotData().get(0).get(HopperSegment.INSTANCE));
    }

    // ── 物品形态容器（活背包，2026-10-23 真机实录）────────────────────────────
    //
    // 现场：活漏斗的高级 tooltip 不显示容器信息（源槽/目标槽/容器尺寸）。
    // 根因：物品形态容器没有世界位置 ⇒ flush 的「按 positions 匹配 viewer」恒为空
    // ⇒ 运行时数据永不发客户端 ⇒ tooltip 回落 DataComponent（那里只有 EMPTY）。
    // 与 FluidFlowServerSync.viewersOf 的 #7 同源，上次只修了流体那条轨。

    @Test
    @DisplayName("物品形态容器：按「谁正开着它」发包，不按位置（位置恒空）")
    void itemForm_sendsToViewerByMenuAnchor() {
        UUID uuid = UUID.randomUUID();
        String key = ContainerKeys.cuuidKey(uuid);

        // 服务端确实攒了脏数据（活漏斗每 tick 写 slotInfo）
        FakeContainerContext ctx = new FakeContainerContext(27, 9, key);
        ContainerRuntimeCache.update(ctx, 0, RuntimeSegments.EMPTY.with(
            HopperSegment.INSTANCE,
            new HopperRuntime(0,
                new ResolvedSlotData(0, 1, 2, 27, 9))));

        // 一个玩家，开着锚定该 uuid 的背包菜单
        net.minecraft.server.level.ServerPlayer viewer = mock(net.minecraft.server.level.ServerPlayer.class);
        net.minecraft.world.inventory.AbstractContainerMenu menu =
            mock(net.minecraft.world.inventory.AbstractContainerMenu.class);
        viewer.containerMenu = menu;   // public 字段（Player.containerMenu）
        viewer.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
        ItemFormContainers.register(new ItemFormContainers.Source() {
            @Override public boolean isContainerItem(net.minecraft.world.item.ItemStack stack) { return true; }
            @Override public net.neoforged.neoforge.items.IItemHandler contentHandler(
                    net.minecraft.world.item.ItemStack stack) { return new net.neoforged.neoforge.items.ItemStackHandler(9); }
            @Override public ItemFormContainers.Handle handleOfMenu(
                    net.minecraft.world.inventory.AbstractContainerMenu m,
                    net.minecraft.world.entity.player.Player p) {
                return new ItemFormContainers.Handle(
                    new net.neoforged.neoforge.items.ItemStackHandler(9), uuid, 9);
            }
        });

        LivingItemRuntimeSync.flush(List.of(viewer), k -> List.of());

        ArgumentCaptor<LivingItemSyncPacket> captor = ArgumentCaptor.forClass(LivingItemSyncPacket.class);
        verify(viewer.connection).send(captor.capture());
        assertEquals(key, captor.getValue().containerKey(),
            "必须发这个物品形态容器的键 —— 发错键客户端会显示成别的容器的数据");
    }

}
