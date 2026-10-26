package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;

/**
 * BeaconPlayerHostSync 跨维度回归（2026-10-18 审查发现的阻断 bug）。
 *
 * <p><b>被守的行为</b>：登记表是<b>全服一张</b>（玩家背包对所有维度统一处理），而
 * {@code flush} 是<b>逐维度</b>调的 ⇒ 清表必须发生在 tick 开头（{@link BeaconPlayerHostSync#beginTick()}），
 * 不能在 flush 里。原先在 flush 末尾清 ⇒ 第一个维度抽干全表、后续维度的 flush 恒拿到空表
 * ⇒ <b>多维度服上玩家宿主光束只在第一个维度有效</b>。</p>
 *
 * <p>替身结构与 {@code LivingItemRuntimeContainerInstancesTest} 同款（Mockito 的
 * ServerLevel / ServerPlayer）。</p>
 */
class BeaconPlayerHostSyncTest {

    private ServerLevel overworld;
    private ServerLevel nether;
    private ServerPlayer owViewer;
    private ServerPlayer owOwner;
    private ServerPlayer netherViewer;
    private ServerPlayer netherOwner;
    private ServerGamePacketListenerImpl owConn;
    private ServerGamePacketListenerImpl netherConn;

    @BeforeEach
    void setUp() {
        BeaconPlayerHostSync.clear();
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(playerList);

        overworld = mock(ServerLevel.class);
        nether = mock(ServerLevel.class);
        when(overworld.isClientSide()).thenReturn(false);
        when(nether.isClientSide()).thenReturn(false);
        when(overworld.getServer()).thenReturn(server);
        when(nether.getServer()).thenReturn(server);
        when(overworld.dimension()).thenReturn(Level.OVERWORLD);
        when(nether.dimension()).thenReturn(Level.NETHER);

        owViewer = player("ow-viewer", overworld);
        owOwner = player("ow-owner", overworld);
        netherViewer = player("nether-viewer", nether);
        netherOwner = player("nether-owner", nether);
        owConn = connOf(owViewer);
        netherConn = connOf(netherViewer);
        // ⚠️ 主人也会作为观察者收到别人的条目 ⇒ 每个玩家都要有 connection（否则 flush NPE）
        connOf(owOwner);
        connOf(netherOwner);

        List<ServerPlayer> all = List.of(owViewer, owOwner, netherViewer, netherOwner);
        when(playerList.getPlayers()).thenReturn(all);
        Map<UUID, ServerPlayer> byId = new HashMap<>();
        for (ServerPlayer p : all) {
            byId.put(p.getUUID(), p);
        }
        when(playerList.getPlayer(any())).thenAnswer(inv -> byId.get(inv.getArgument(0)));
    }

    private static ServerPlayer player(String name, ServerLevel level) {
        ServerPlayer p = mock(ServerPlayer.class);
        when(p.getUUID()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(p.level()).thenReturn(level);
        when(p.getX()).thenReturn(0.5);
        when(p.getZ()).thenReturn(0.5);
        return p;
    }

    private static ServerGamePacketListenerImpl connOf(ServerPlayer p) {
        ServerGamePacketListenerImpl conn = mock(ServerGamePacketListenerImpl.class);
        p.connection = conn;
        return conn;
    }

    @Test
    @DisplayName("两个维度各一名信标主人 ⇒ 各自维度的观察者都能收到（登记表不得被首维度抽干）")
    void twoDimensions_bothReceive() {
        BeaconPlayerHostSync.beginTick();
        BeaconPlayerHostSync.report(owOwner.getUUID(), null, 2, List.of());
        BeaconPlayerHostSync.report(netherOwner.getUUID(), null, 3, List.of());

        BeaconPlayerHostSync.flush(overworld);   // 首维度：不得把 nether 的登记一起吃掉
        BeaconPlayerHostSync.flush(nether);

        assertEquals(List.of(owOwner.getUUID()), sentOwners(owConn), "主世界观察者应收到本维度的信标主人");
        assertEquals(List.of(netherOwner.getUUID()), sentOwners(netherConn), "下界观察者应收到本维度的信标主人");
    }

    @Test
    @DisplayName("beginTick 清表 ⇒ 上一 tick 的登记不会残留（主人离线/取走信标后光束消失）")
    void beginTick_clearsStaleEntries() {
        BeaconPlayerHostSync.beginTick();
        BeaconPlayerHostSync.report(owOwner.getUUID(), null, 2, List.of());
        BeaconPlayerHostSync.flush(overworld);
        assertEquals(List.of(owOwner.getUUID()), sentOwners(owConn));

        // 下一 tick：主人不再登记（信标取走）⇒ beginTick 清表后 flush 应发空表
        BeaconPlayerHostSync.beginTick();
        BeaconPlayerHostSync.flush(overworld);

        assertEquals(List.of(), sentOwners(owConn, 2), "清表后应下发空条目（光束消失）");
    }

    /** 抓取该连接收到的「条目主人 UUID 列表」；attempt = 第几次 send（1 起）。 */
    private static List<UUID> sentOwners(ServerGamePacketListenerImpl conn, int... attempt) {
        int n = attempt.length > 0 ? attempt[0] : 1;
        ArgumentCaptor<BeaconPlayerHostPacket> captor =
            ArgumentCaptor.forClass(BeaconPlayerHostPacket.class);
        verify(conn, times(n)).send(captor.capture());
        BeaconPlayerHostPacket last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        return last.entries().stream().map(BeaconPlayerHostPacket.Entry::playerId).toList();
    }

    @Test
    @DisplayName("同维度观察者才收到；自己那份不发（本机渲染）")
    void onlySameDimensionViewers_excludingSelf() {
        BeaconPlayerHostSync.beginTick();
        BeaconPlayerHostSync.report(owOwner.getUUID(), null, 2, List.of());
        BeaconPlayerHostSync.report(owViewer.getUUID(), null, 1, List.of());   // 观察者本人也有信标

        BeaconPlayerHostSync.flush(overworld);

        List<UUID> owners = sentOwners(owConn);
        assertEquals(List.of(owOwner.getUUID()), owners, "自己的登记不下发（本机渲染），只发别人的");
        assertTrue(!owners.contains(owViewer.getUUID()));
    }

    /**
     * 物品形态容器（活背包）里的信标：<b>连宿主自己也要发</b>。
     *
     * <p>守的是 2026-10-26 真机现象：背包背在身上时活信标**没有光束**，放箱子里却有。
     * 根因是两处都假设「自己那份本机渲染会覆盖」—— 而物品形态的内容在服务端 SavedData，
     * 客户端根本读不到，本机渲染扫不到背包里的信标 ⇒ 两条路都没画。</p>
     */
    @Test
    @DisplayName("物品形态容器里的信标 ⇒ 也要发给宿主自己（客户端读不到背包内容）")
    void itemFormBeacon_isSentToSelf() {
        BeaconPlayerHostSync.beginTick();
        BeaconPlayerHostSync.report(owViewer.getUUID(), UUID.randomUUID(), 2, List.of());

        BeaconPlayerHostSync.flush(overworld);

        assertEquals(List.of(owViewer.getUUID()), sentOwners(owConn),
            "物品形态的内容客户端读不到 ⇒ 必须发给自己，否则背在身上没有光束");
    }
}
