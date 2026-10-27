package com.qiqi.li.living.domain.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qiqi.li.living.compat.sable.ModSable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 传送落点 Y 的<b>站立面语义</b>（2026-10-27 审查 P1-1，v50 简化回归）。
 *
 * <p><b>被守的行为</b>：{@code ChunkAccess.getHeight} 返回「最高阻挡方块自身的 Y」，
 * 原版 {@code Level.getHeight} 专门 +1 换算成站立 Y。v50 把 {@code getSafeYFromNoise}
 * 换回 {@code chunk.getHeight} 时丢了 +1 ⇒ 玩家脚坐标嵌进地表方块 1 格
 * （下界 MOTION_BLOCKING = 基岩天花板 ⇒ 嵌进基岩）。修复：destY = safeY + 1.0。</p>
 */
class TeleportHelperYTest {

    @Test
    @DisplayName("落点 Y = 高度图值 + 1（站上地表，而非嵌进地表方块）")
    void destY_isHeightPlusOne() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel sourceLevel = mock(ServerLevel.class);
        ServerLevel targetLevel = mock(ServerLevel.class);
        when(player.isCreative()).thenReturn(true);   // consumePearl 走创造免耗
        when(player.getName()).thenReturn(
            net.minecraft.network.chat.Component.literal("tester"));   // 日志用
        when(player.level()).thenReturn(sourceLevel);
        when(player.serverLevel()).thenReturn(sourceLevel);   // MapUpdateSkipHelper 用
        net.minecraft.server.MinecraftServer server = org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class);
        org.mockito.Mockito.when(sourceLevel.getServer()).thenReturn(server);
        org.mockito.Mockito.when(server.getTickCount()).thenReturn(1);
        when(player.getVehicle()).thenReturn(null);
        when(player.damageSources()).thenReturn(mock(DamageSources.class));
        when(player.getCooldowns()).thenReturn(mock(ItemCooldowns.class));
        when(sourceLevel.dimension()).thenReturn(Level.OVERWORLD);
        when(targetLevel.dimension()).thenReturn(Level.OVERWORLD);   // 同维度 ⇒ 走 teleportTo 分支
        when(targetLevel.getMinBuildHeight()).thenReturn(-64);
        LevelChunk chunk = mock(LevelChunk.class);
        when(targetLevel.getChunk(anyInt(), anyInt())).thenReturn(chunk);
        when(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, 10, 20)).thenReturn(70);

        boolean ok = TeleportHelper.teleportToMapPosition(
            player, sourceLevel, targetLevel, 10.7, 20.3, ItemStack.EMPTY, false);

        assertTrue(ok, "传送应成功（Sable 未加载 ⇒ 无子位面分支；创造 ⇒ 无珍珠结算）");
        ArgumentCaptor<Double> xs = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> ys = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> zs = ArgumentCaptor.forClass(Double.class);
        verify(player).teleportTo(xs.capture(), ys.capture(), zs.capture());
        assertEquals(11.2, xs.getValue(), 0.0001, "X 取格中心");
        assertEquals(71.0, ys.getValue(), 0.0001,
            "高度图 70 = 最高阻挡方块自身 Y ⇒ 站立面是 71（旧实现 70 = 嵌进方块）");
        assertEquals(20.8, zs.getValue(), 0.0001, "Z 取格中心");
    }

    @Test
    @DisplayName("目标柱全空（高度图低于世界下限）⇒ 照传不拒（A3 指哪传哪，落虚空属玩家自选）")
    void voidColumn_teleportsInsteadOfRefusing() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel sourceLevel = mock(ServerLevel.class);
        ServerLevel targetLevel = mock(ServerLevel.class);
        when(player.isCreative()).thenReturn(true);
        when(player.getName()).thenReturn(
            net.minecraft.network.chat.Component.literal("tester"));
        when(player.level()).thenReturn(sourceLevel);
        when(player.serverLevel()).thenReturn(sourceLevel);
        net.minecraft.server.MinecraftServer server = org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class);
        org.mockito.Mockito.when(sourceLevel.getServer()).thenReturn(server);
        org.mockito.Mockito.when(server.getTickCount()).thenReturn(1);
        when(player.getVehicle()).thenReturn(null);
        when(player.damageSources()).thenReturn(mock(DamageSources.class));
        when(player.getCooldowns()).thenReturn(mock(ItemCooldowns.class));
        when(sourceLevel.dimension()).thenReturn(Level.OVERWORLD);
        when(targetLevel.dimension()).thenReturn(Level.OVERWORLD);
        // 模拟虚空柱：高度图返回世界下限（原版对无方块柱的语义），脚下无方块
        when(targetLevel.getMinBuildHeight()).thenReturn(-64);
        LevelChunk chunk = mock(LevelChunk.class);
        when(targetLevel.getChunk(anyInt(), anyInt())).thenReturn(chunk);
        when(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, 10, 20)).thenReturn(-64);

        boolean ok = TeleportHelper.teleportToMapPosition(
            player, sourceLevel, targetLevel, 10.7, 20.3, ItemStack.EMPTY, false);

        assertTrue(ok, "A3 指哪传哪：全空柱不得因「无地面」拒绝传送");
        ArgumentCaptor<Double> ys = ArgumentCaptor.forClass(Double.class);
        verify(player).teleportTo(org.mockito.ArgumentMatchers.anyDouble(), ys.capture(),
            org.mockito.ArgumentMatchers.anyDouble());
        assertEquals(-63.0, ys.getValue(), 0.0001,
            "脚下 = minBuild + 1（虚空柱原版高度图语义），玩家自担后续下落");
    }
}
