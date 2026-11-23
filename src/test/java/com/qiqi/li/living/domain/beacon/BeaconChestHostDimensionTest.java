package com.qiqi.li.living.domain.beacon;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code BeaconChestHost} 的<b>跨维度隔离</b>回归（2026-11 审查修复）。
 *
 * <h3>修的是什么</h3>
 * <p>{@code flush(level)} 是逐维度调用的（{@code LivingItem.onServerTick} 的维度循环），
 * 但 {@code current} / {@code activeLids} / {@code lidLastTick} 曾是<b>全服一张</b>静态表。
 * A 维度 flush 时会拿 B 维度的盖子状态在本维度按坐标发 {@code blockEvent(1, 0)}：
 * ① 其它维度同坐标的箱子被反复强制关盖；
 * ② {@code sendLid} 里的 {@code getBlockState} 会把对方维度的区块同步拉起来；
 * ③ 清掉 B 的开盖节流 ⇒ B 每 tick 重发开盖（应为 20 tick 一次）。</p>
 *
 * <h3>为什么单维度测试抓不住</h3>
 * <p>{@code BeaconChestHostTest} 只用一个 {@code ServerLevel} 替身 ⇒ 跨维度互清不可见
 * （与 K2「单维度测不出」同型）。本类用两个维度替身交替驱动 flush。</p>
 *
 * <p>⚠️ 两维度的坐标<b>故意相同</b>（原版所有维度共用坐标系，同坐标是常态）
 * —— 若状态表串维，关盖事件会原地命中「另一个维度的同坐标」。</p>
 */
class BeaconChestHostDimensionTest {

    /** 两维度共用同一坐标（原版维度共用坐标系）。 */
    private static final BlockPos SHARED_POS = new BlockPos(10, 64, -20);
    private static final String OVER_KEY = "minecraft:overworld|chest_10_64_-20";
    private static final String NETHER_KEY = "minecraft:nether|chest_10_64_-20";

    private ServerLevel overworld;
    private ServerLevel nether;
    private long overworldTime;
    private long netherTime;

    @BeforeEach
    void setUp() {
        BeaconChestHost.clear();
        overworldTime = 1000L;
        netherTime = 1000L;
        overworld = level(Level.OVERWORLD, () -> overworldTime);
        nether = level(Level.NETHER, () -> netherTime);
    }

    @AfterEach
    void tearDown() {
        BeaconChestHost.clear();
    }

    private ServerLevel level(ResourceKey<Level> key, LongSupplier clock) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.isClientSide()).thenReturn(false);
        when(level.dimension()).thenReturn(key);
        when(level.getGameTime()).thenAnswer(inv -> clock.getAsLong());
        when(level.getBlockState(any(BlockPos.class))).thenReturn(mock(BlockState.class));
        when(level.hasChunkAt(any(BlockPos.class))).thenReturn(true);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        when(playerList.getPlayers()).thenReturn(List.of());
        when(server.getPlayerList()).thenReturn(playerList);
        when(level.getServer()).thenReturn(server);
        return level;
    }

    private void report(ServerLevel level, String cacheKey, int slot) {
        BeaconChestHost.report(level.dimension().location(), cacheKey, slot, SHARED_POS,
            List.of(SHARED_POS), 2, List.of());
    }

    @Test
    @DisplayName("关盖绝不落到其它维度的坐标上（含没开过盖的维度）")
    void closeNeverLandsInOtherDimension() {
        report(overworld, OVER_KEY, 3);
        BeaconChestHost.flush(overworld);
        verify(overworld, times(1)).blockEvent(eq(SHARED_POS), any(), eq(1), eq(1));

        // 下个维度 flush：本维度没有任何登记 ⇒ 旧实现会在这里对「主世界的坐标」发关盖
        BeaconChestHost.flush(nether);
        verify(nether, never()).blockEvent(any(BlockPos.class), any(), anyInt(), anyInt());
        verify(nether, never()).getBlockState(any(BlockPos.class));
    }

    @Test
    @DisplayName("20 tick 开盖节流不被其它维度重置（旧实现每 tick 重发开盖）")
    void throttleNotResetByOtherDimension() {
        report(overworld, OVER_KEY, 3);
        BeaconChestHost.flush(overworld);                 // t=1000 首次开盖
        BeaconChestHost.flush(nether);                    // 旧实现会 remove 主世界的节流记录

        overworldTime = 1001;
        report(overworld, OVER_KEY, 3);
        BeaconChestHost.flush(overworld);
        verify(overworld, times(1)).blockEvent(eq(SHARED_POS), any(), eq(1), eq(1));

        overworldTime = 1020;
        report(overworld, OVER_KEY, 3);
        BeaconChestHost.flush(overworld);
        verify(overworld, times(2)).blockEvent(eq(SHARED_POS), any(), eq(1), eq(1));
    }

    @Test
    @DisplayName("登记表按维度分桶：一个维度 flush 不会抽走另一个维度的登记")
    void registrationNotStolenAcrossDimensions() {
        report(overworld, OVER_KEY, 3);
        report(nether, NETHER_KEY, 3);

        BeaconChestHost.flush(overworld);
        BeaconChestHost.flush(nether);

        verify(overworld, times(1)).blockEvent(eq(SHARED_POS), any(), eq(1), eq(1));
        verify(nether, times(1)).blockEvent(eq(SHARED_POS), any(), eq(1), eq(1));
    }

    @Test
    @DisplayName("区块未加载 ⇒ 不发关盖、也不 getBlockState（不把区块拉起来）")
    void unloadedChunk_isNotTouched() {
        report(overworld, OVER_KEY, 3);
        BeaconChestHost.flush(overworld);                 // 首次开盖（区块已加载）

        clearInvocations(overworld);
        when(overworld.hasChunkAt(any(BlockPos.class))).thenReturn(false);
        BeaconChestHost.flush(overworld);                 // 无登记 ⇒ 走关盖分支

        verify(overworld, never()).getBlockState(any(BlockPos.class));
        verify(overworld, never()).blockEvent(any(BlockPos.class), any(), anyInt(), eq(0));
    }
}
