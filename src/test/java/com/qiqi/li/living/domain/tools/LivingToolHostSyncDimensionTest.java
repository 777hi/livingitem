package com.qiqi.li.living.domain.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.qiqi.li.living.container.HostRef;
import com.qiqi.li.living.domain.tools.LivingToolHostPacket.Entry;
import com.qiqi.li.living.domain.tools.LivingToolHostPacket.ToolRay;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * K2 跨维度回归（2026-10-27 审查发现的阻断 bug）。
 *
 * <p><b>被守的行为</b>：登记表是<b>全服一张</b> —— PLAYER 宿主（随身背包里的活工具）的上报
 * 来自 {@code onServerTick} 的<b>玩家循环</b>，在维度循环<b>之前</b>；而 {@code flush} 是
 * <b>逐维度</b>调用的。原先在 flush 里抽干登记表 ⇒ 第一个维度（主世界恒先 flush）把全表抽走，
 * 非主世界玩家的 PLAYER 条目又被 {@code resolveHostPos} 按「owner 在本维度」过滤丢弃
 * ⇒ <b>地狱 / 末地玩家的随身背包条目永远发不出去</b>（背包内活工具不渲染 + 客户端辅助缺加成）。</p>
 *
 * <p>修法与 {@code BeaconPlayerHostSync} 同一条铁律：清表在 {@link LivingToolHostSync#beginTick()}
 * （tick 开头），flush 只读；每条登记自带上报维度，flush 只取本维度条目。</p>
 *
 * <p>替身结构与 {@code BeaconPlayerHostSyncTest} 同款（Mockito 的 ServerLevel / ServerPlayer）。</p>
 */
class LivingToolHostSyncDimensionTest {

    private static final BlockPos HOST_OW = new BlockPos(10, 64, 10);
    private static final BlockPos HOST_NETHER = new BlockPos(20, 64, 20);

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
        LivingToolHostSync.clear();
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
        // ⚠️ 宿主自己也会作为观察者收到（K2 不跳自己 —— 物品形态内容客户端读不到）
        //    ⇒ 每个玩家都要有 connection（否则 flush NPE）
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
        when(p.position()).thenReturn(new Vec3(0.5, 64.0, 0.5));
        return p;
    }

    private static ServerGamePacketListenerImpl connOf(ServerPlayer p) {
        ServerGamePacketListenerImpl conn = mock(ServerGamePacketListenerImpl.class);
        p.connection = conn;
        return conn;
    }

    private static List<ToolRay> toolRays() {
        return List.of(new ToolRay(new net.minecraft.world.item.ItemStack(
            net.minecraft.world.item.Items.DIAMOND_PICKAXE), true, false));
    }

    /** 抓取该连接收到的「条目宿主列表」；attempt = 第几次 send（1 起）。 */
    private static List<HostRef> sentHosts(ServerGamePacketListenerImpl conn, int... attempt) {
        int n = attempt.length > 0 ? attempt[0] : 1;
        ArgumentCaptor<LivingToolHostPacket> captor =
            ArgumentCaptor.forClass(LivingToolHostPacket.class);
        verify(conn, times(n)).send(captor.capture());
        LivingToolHostPacket last = captor.getAllValues()
            .get(captor.getAllValues().size() - 1);
        return last.entries().stream().map(Entry::host).toList();
    }

    @Test
    @DisplayName("两个维度各一名背包宿主 ⇒ 各自维度的观察者都能收到（登记表不得被首维度抽干）")
    void twoDimensions_bothReceive() {
        LivingToolHostSync.beginTick();
        LivingToolHostSync.report(HostRef.ofPlayer(owOwner.getUUID(), 3),
            Level.OVERWORLD.location(), toolRays());
        LivingToolHostSync.report(HostRef.ofPlayer(netherOwner.getUUID(), 5),
            Level.NETHER.location(), toolRays());

        LivingToolHostSync.flush(overworld);   // 首维度：不得把 nether 的登记一起吃掉
        LivingToolHostSync.flush(nether);

        assertEquals(List.of(HostRef.ofPlayer(owOwner.getUUID(), 3)), sentHosts(owConn),
            "主世界观察者应收到本维度的背包宿主");
        assertEquals(List.of(HostRef.ofPlayer(netherOwner.getUUID(), 5)), sentHosts(netherConn),
            "下界观察者应收到本维度的背包宿主（抽干式登记表就是丢在这里）");
    }

    @Test
    @DisplayName("方块宿主按上报维度隔离 ⇒ 不再依赖「抽干顺序」这个隐式约定")
    void blockEntries_doNotCrossDimensions() {
        LivingToolHostSync.beginTick();
        LivingToolHostSync.report(HostRef.ofBlock(HOST_OW), Level.OVERWORLD.location(),
            toolRays());
        LivingToolHostSync.report(HostRef.ofBlock(HOST_NETHER), Level.NETHER.location(),
            toolRays());

        LivingToolHostSync.flush(overworld);
        LivingToolHostSync.flush(nether);

        assertEquals(List.of(HostRef.ofBlock(HOST_OW)), sentHosts(owConn));
        assertEquals(List.of(HostRef.ofBlock(HOST_NETHER)), sentHosts(netherConn),
            "主世界的方块宿主不许漏进下界（同坐标方块在两维度都存在，必须显式按维度隔离）");
    }

    @Test
    @DisplayName("beginTick 清表 ⇒ 上一 tick 的登记不会残留（取走背包后条目消失）")
    void beginTick_clearsStaleEntries() {
        LivingToolHostSync.beginTick();
        LivingToolHostSync.report(HostRef.ofPlayer(owOwner.getUUID(), 3),
            Level.OVERWORLD.location(),
            toolRays());
        LivingToolHostSync.flush(overworld);
        assertEquals(1, sentHosts(owConn).size());

        // 下一 tick：不再登记（背包取走）⇒ beginTick 清表后 flush 应发空表（客户端清缓存）
        LivingToolHostSync.beginTick();
        LivingToolHostSync.flush(overworld);

        assertEquals(List.of(), sentHosts(owConn, 2), "清表后应下发空条目（模型 / 射线消失）");
    }

    @Test
    @DisplayName("稳态零发包：内容不变 ⇒ 逐维度 flush 只读也不重发（去重仍生效）")
    void steadyState_noResend() {
        LivingToolHostSync.beginTick();
        LivingToolHostSync.report(HostRef.ofPlayer(owOwner.getUUID(), 3),
            Level.OVERWORLD.location(),
            toolRays());
        LivingToolHostSync.flush(overworld);

        // 下一 tick：内容完全相同 ⇒ 去重命中，不发第二个包
        LivingToolHostSync.beginTick();
        LivingToolHostSync.report(HostRef.ofPlayer(owOwner.getUUID(), 3),
            Level.OVERWORLD.location(),
            toolRays());
        LivingToolHostSync.flush(overworld);

        verify(owConn, times(1)).send(org.mockito.ArgumentMatchers.any(LivingToolHostPacket.class));
    }
}
