package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * BeaconChestHost 测试 —— 2026-10-18 「同容器多枚信标每枚一条光束」的回归守卫。
 *
 * <p>两个不变式：<b>光束每枚一条</b>（键 = 容器 + 槽位，各自的等级/颜色不互相覆盖）与
 * <b>开盖按容器</b>（一箱两枚取走一枚不能关盖——这是本次重构最容易写错的边界）。
 * 开盖逻辑用 Mockito 的 ServerLevel 替身驱动（参照 {@code LivingItemRuntimeContainerInstancesTest}
 * 的替身模式）；S2C 条目组装与键格式是纯函数，直接断言。</p>
 */
class BeaconChestHostTest {

    private static final String KEY = "minecraft:overworld|chest_10_64_-20";
    private static final BlockPos CHEST = new BlockPos(10, 64, -20);

    private ServerLevel level;
    private long gameTime;

    @BeforeEach
    void setUp() {
        BeaconChestHost.clear();
        level = mock(ServerLevel.class);
        gameTime = 1000L;
        when(level.isClientSide()).thenReturn(false);
        when(level.getGameTime()).thenAnswer(inv -> gameTime);
        when(level.getBlockState(any())).thenReturn(mock(BlockState.class));
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        when(playerList.getPlayers()).thenReturn(List.of());
        when(server.getPlayerList()).thenReturn(playerList);
        when(level.getServer()).thenReturn(server);
    }

    // ── 键格式 ────────────────────────────────────────────────

    @Test
    @DisplayName("宿主键 = 容器键#槽位：同箱不同槽区分，同槽稳定")
    void hostKey_perSlot() {
        assertEquals(KEY + "#3", BeaconChestHost.hostKey(KEY, 3));
        assertNotEquals(BeaconChestHost.hostKey(KEY, 3), BeaconChestHost.hostKey(KEY, 5),
            "丢槽位区分就退化成「按容器 merge」——两枚信标会互相覆盖");
        assertEquals(BeaconChestHost.hostKey(KEY, 3), BeaconChestHost.hostKey(KEY, 3));
    }

    // ── 条目组装（纯函数）────────────────────────────────────

    @Test
    @DisplayName("全同条目去重（画两遍只会双重混合变亮）；颜色不同各保留")
    void assembleEntries_dedupesIdentical() {
        var red = new BeaconChestHost.Host(KEY, CHEST, List.of(CHEST), 2, List.of(0xFFFF0000));
        var redTwin = new BeaconChestHost.Host(KEY, CHEST, List.of(CHEST), 2, List.of(0xFFFF0000));
        var green = new BeaconChestHost.Host(KEY, CHEST, List.of(CHEST), 2, List.of(0xFF00FF00));

        var entries = BeaconChestHost.assembleEntries(List.of(red, redTwin, green));

        assertEquals(2, entries.size(), "全同的去重，不同色的保留");
        assertEquals(List.of(0xFFFF0000), entries.get(0).glassColors());
        assertEquals(List.of(0xFF00FF00), entries.get(1).glassColors());
    }

    // ── 开盖：按容器，不按信标 ────────────────────────────────

    private void report(int slot, List<Integer> colors) {
        BeaconChestHost.report(KEY, slot, CHEST, List.of(CHEST), 2, colors);
    }

    @Test
    @DisplayName("一箱两枚信标 ⇒ 开盖只发一次（按容器，不按信标翻倍）")
    void twoBeaconsOneChest_openOnce() {
        report(3, List.of());
        report(5, List.of());

        BeaconChestHost.flush(level);

        verify(level, times(1)).blockEvent(eq(CHEST), any(), eq(1), eq(1));
    }

    @Test
    @DisplayName("两枚取走一枚 ⇒ 盖保持开着（不关、不重发）")
    void oneOfTwoLeaves_lidStaysOpen() {
        report(3, List.of());
        report(5, List.of());
        BeaconChestHost.flush(level);

        report(3, List.of());   // 只剩一枚
        BeaconChestHost.flush(level);

        verify(level, never()).blockEvent(eq(CHEST), any(), eq(1), eq(0));
        verify(level, times(1)).blockEvent(eq(CHEST), any(), eq(1), eq(1));
    }

    @Test
    @DisplayName("整箱再无信标 ⇒ 关盖")
    void allLeave_lidCloses() {
        report(3, List.of());
        BeaconChestHost.flush(level);

        BeaconChestHost.flush(level);   // 无登记

        verify(level, times(1)).blockEvent(eq(CHEST), any(), eq(1), eq(0));
    }

    @Test
    @DisplayName("开盖重发按 20 tick 节流（按容器）")
    void lidResend_throttledPerContainer() {
        report(3, List.of());
        BeaconChestHost.flush(level);         // t=1000 开

        gameTime = 1005;
        report(3, List.of());
        BeaconChestHost.flush(level);         // 未到 20 tick ⇒ 不重发
        verify(level, times(1)).blockEvent(eq(CHEST), any(), eq(1), eq(1));

        gameTime = 1020;
        report(3, List.of());
        BeaconChestHost.flush(level);         // 满 20 tick ⇒ 重发
        verify(level, times(2)).blockEvent(eq(CHEST), any(), eq(1), eq(1));
    }

    @Test
    @DisplayName("关盖后清节流 ⇒ 再次开盖立即发（不等 20 tick）")
    void reopenAfterClose_immediate() {
        report(3, List.of());
        BeaconChestHost.flush(level);
        BeaconChestHost.flush(level);         // 全走 ⇒ 关盖
        verify(level, times(1)).blockEvent(eq(CHEST), any(), eq(1), eq(0));

        gameTime = 1002;                      // 距开盖才 2 tick
        report(7, List.of());
        BeaconChestHost.flush(level);
        verify(level, times(2)).blockEvent(eq(CHEST), any(), eq(1), eq(1));
    }

    // ── S2C：每枚一条 ─────────────────────────────────────────

    @Test
    @DisplayName("同箱两枚信标 ⇒ 包里两条条目（同位置、各自的颜色）")
    void s2c_oneEntryPerBeacon() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerGamePacketListenerImpl conn = mock(ServerGamePacketListenerImpl.class);
        player.connection = conn;
        when(player.level()).thenReturn(level);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.getX()).thenReturn(10.5);
        when(player.getZ()).thenReturn(-19.5);
        MinecraftServer server = level.getServer();
        when(server.getPlayerList().getPlayers()).thenReturn(List.of(player));

        report(3, List.of(0xFFFF0000));
        report(5, List.of(0xFF00FF00));
        BeaconChestHost.flush(level);

        ArgumentCaptor<BeaconHostPacket> captor = ArgumentCaptor.forClass(BeaconHostPacket.class);
        verify(conn).send(captor.capture());
        BeaconHostPacket pkt = captor.getValue();
        assertEquals(2, pkt.entries().size(), "每枚信标一条（未去重——颜色不同）");
        assertEquals(CHEST, pkt.entries().get(0).pos());
        assertEquals(CHEST, pkt.entries().get(1).pos());
        assertEquals(List.of(0xFFFF0000), pkt.entries().get(0).glassColors());
        assertEquals(List.of(0xFF00FF00), pkt.entries().get(1).glassColors());
    }

    @Test
    @DisplayName("内容没变 ⇒ 不重发（内容去重对每枚一条仍然成立）")
    void s2c_contentUnchanged_notResent() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerGamePacketListenerImpl conn = mock(ServerGamePacketListenerImpl.class);
        player.connection = conn;
        when(player.level()).thenReturn(level);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.getX()).thenReturn(10.5);
        when(player.getZ()).thenReturn(-19.5);
        MinecraftServer server = level.getServer();
        when(server.getPlayerList().getPlayers()).thenReturn(List.of(player));

        report(3, List.of(0xFFFF0000));
        BeaconChestHost.flush(level);
        report(3, List.of(0xFFFF0000));
        BeaconChestHost.flush(level);

        ArgumentCaptor<BeaconHostPacket> captor = ArgumentCaptor.forClass(BeaconHostPacket.class);
        verify(conn, times(1)).send(captor.capture());
        assertEquals(1, captor.getAllValues().size(), "内容没变不重发");
    }
}
