package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * BeaconEntityHostSync 维度隔离 / 半径 / 去重回归（2026-11-12，D-container-25）。
 *
 * <p>替身结构同 {@code BeaconPlayerHostSyncTest}（Mockito 的 ServerLevel / ServerPlayer /
 * Entity）；差异：宿主是<b>实体</b>，本维度的解析走 {@code ServerLevel#getEntity(uuid)}，
 * 且<b>没有「自己那份不发」</b>——信标在宿主实体身上，所有跟踪者含主人都靠包。</p>
 */
class BeaconEntityHostSyncTest {

    private ServerLevel overworld;
    private ServerLevel nether;
    private Entity carrier;
    private UUID carrierId;
    private ServerPlayer owViewer;
    private ServerPlayer netherViewer;
    private ServerPlayer farViewer;
    private ServerGamePacketListenerImpl owConn;
    private ServerGamePacketListenerImpl netherConn;
    private ServerGamePacketListenerImpl farConn;

    @BeforeEach
    void setUp() {
        BeaconEntityHostSync.clear();
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

        carrier = mock(Entity.class);
        carrierId = UUID.randomUUID();
        when(carrier.getX()).thenReturn(0.5);
        when(carrier.getZ()).thenReturn(0.5);
        // 维度隔离的实现机制：本维度 getEntity 找得到、其它维度找不到
        when(overworld.getEntity(carrierId)).thenReturn(carrier);

        owViewer = player("ow-viewer", overworld);
        netherViewer = player("nether-viewer", nether);
        farViewer = player("far-viewer", overworld);
        when(farViewer.getX()).thenReturn(10_000.0);   // 水平距离 > RADIUS
        owConn = connOf(owViewer);
        netherConn = connOf(netherViewer);
        farConn = connOf(farViewer);

        when(playerList.getPlayers()).thenReturn(List.of(owViewer, netherViewer, farViewer));
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

    /** 抓取该连接收到的「条目实体 UUID 列表」；attempt = 第几次 send（1 起）。 */
    private static List<UUID> sentEntityIds(ServerGamePacketListenerImpl conn, int... attempt) {
        int n = attempt.length > 0 ? attempt[0] : 1;
        ArgumentCaptor<BeaconEntityHostPacket> captor =
            ArgumentCaptor.forClass(BeaconEntityHostPacket.class);
        verify(conn, times(n)).send(captor.capture());
        BeaconEntityHostPacket last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        return last.entries().stream().map(BeaconEntityHostPacket.Entry::entityId).toList();
    }

    @Test
    @DisplayName("实体所在维度的观察者才收到；其它维度 getEntity 解析不到 ⇒ 不发")
    void entityDimension_isolatesViewers() {
        BeaconEntityHostSync.beginTick();
        BeaconEntityHostSync.report(carrierId, 2, List.of());

        BeaconEntityHostSync.flush(overworld);
        BeaconEntityHostSync.flush(nether);

        assertEquals(List.of(carrierId), sentEntityIds(owConn), "主世界观察者收到实体宿主条目");
        assertTrue(sentEntityIds(netherConn).isEmpty(), "下界观察者不该收到（实体不在该维度）");
    }

    @Test
    @DisplayName("同实体多枚信标取最高等级（一实体一条光束，与玩家口径一致）")
    void highestLevelWins() {
        BeaconEntityHostSync.beginTick();
        BeaconEntityHostSync.report(carrierId, 1, List.of(0xFF8833));
        BeaconEntityHostSync.report(carrierId, 3, List.of(0xFF55FF));

        BeaconEntityHostSync.flush(overworld);

        ArgumentCaptor<BeaconEntityHostPacket> captor =
            ArgumentCaptor.forClass(BeaconEntityHostPacket.class);
        verify(owConn, times(1)).send(captor.capture());
        BeaconEntityHostPacket.Entry entry = captor.getValue().entries().getFirst();
        assertEquals(3, entry.level(), "等级跟最高那枚走");
        assertEquals(List.of(0xFF55FF), entry.glassColors(), "玻璃色同源跟随");
    }

    @Test
    @DisplayName("beginTick 清表 ⇒ 上一 tick 的登记不残留（信标取走后光束消失）")
    void beginTick_clearsStaleEntries() {
        BeaconEntityHostSync.beginTick();
        BeaconEntityHostSync.report(carrierId, 2, List.of());
        BeaconEntityHostSync.flush(overworld);
        assertEquals(List.of(carrierId), sentEntityIds(owConn));

        BeaconEntityHostSync.beginTick();
        BeaconEntityHostSync.flush(overworld);

        assertEquals(List.of(), sentEntityIds(owConn, 2), "清表后应下发空条目（光束消失）");
    }

    @Test
    @DisplayName("半径外（水平 > 256）的观察者收不到条目（空表包 = 缓存清理协议，与玩家通道同款）")
    void outOfRadiusViewer_doesNotReceive() {
        BeaconEntityHostSync.beginTick();
        BeaconEntityHostSync.report(carrierId, 2, List.of());
        BeaconEntityHostSync.flush(overworld);

        assertEquals(List.of(carrierId), sentEntityIds(owConn), "半径内照收");
        ArgumentCaptor<BeaconEntityHostPacket> captor =
            ArgumentCaptor.forClass(BeaconEntityHostPacket.class);
        verify(farConn, atLeast(0)).send(captor.capture());
        assertTrue(captor.getAllValues().stream().allMatch(p -> p.entries().isEmpty()),
            "半径外的观察者只可能收到空表（首拍缓存清理），不含任何实体条目");
    }
}
