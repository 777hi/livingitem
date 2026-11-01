package com.qiqi.li.living.domain.map;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 地图更新节流器的<b>跨存档</b>不变量（2026-11-01）。
 *
 * <p><b>病</b>：两张表存的是 {@code MinecraftServer#getTickCount()}，而它从 0 起算；
 * 单机「退出存档 → 进另一个存档」<b>不重启 JVM</b> ⇒ static 表跨存档存活。
 * 旧存档留下的 {@code cooldowns = 500040} / {@code lastUpdateTick = 500000}
 * 跟新存档的 {@code currentTick = 10} 一比：{@code currentTick < expireTick} 恒真、
 * {@code currentTick - lastTick < 20} 恒真 ⇒ <b>新存档里地图刷新被永久跳过</b>
 * （活地图图标 / 装饰不再更新，且毫无报错）。</p>
 *
 * <p>{@code lastUpdateTick} 那条路径要 {@code LivingItemManager.isLivingMap} ⇒ 需要功能注册表，
 * 单测里伪造的代价大于价值；它与 {@code cooldowns} 由<b>同一个 {@code clear()}</b> 回收，
 * 所以本类用 cooldown 路径钉住「停服必须清」这条不变量，两条路径同进同退。</p>
 */
class MapUpdateSkipHelperTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        MapUpdateSkipHelper.clear();
    }

    /** 造一个只服务到 {@code serverLevel().getServer().getTickCount()} 这条链的玩家替身。 */
    private static ServerPlayer playerAtTick(int tickCount) {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        MinecraftServer server = mock(MinecraftServer.class);
        when(player.getUUID()).thenReturn(PLAYER);
        when(player.serverLevel()).thenReturn(level);
        when(level.getServer()).thenReturn(server);
        when(server.getTickCount()).thenReturn(tickCount);
        return player;
    }

    @Test
    @DisplayName("传送后 SKIP_TICKS 内确实跳过（基线行为，防下面的用例假绿）")
    void withinCooldown_skips() {
        ServerPlayer player = playerAtTick(1_000);
        MapUpdateSkipHelper.markTeleported(player);

        assertTrue(MapUpdateSkipHelper.shouldSkip(player), "刚传送完应处于跳过窗口内");
        assertFalse(MapUpdateSkipHelper.shouldSkip(playerAtTick(1_000 + 40)),
            "窗口满后应自动放行，并顺手删掉该玩家记录");
    }

    @Test
    @DisplayName("切存档后 tick 计数从 0 重来：清过表的节流器不再把新存档永久锁死")
    void crossSave_afterClear_doesNotSkipForever() {
        ServerPlayer oldWorld = playerAtTick(500_000);
        MapUpdateSkipHelper.markTeleported(oldWorld);
        assertTrue(MapUpdateSkipHelper.shouldSkip(oldWorld), "旧存档里处于冷却");

        // 模拟「退出存档 → 进另一个存档」：JVM 不重启，只有停服清理会跑
        MapUpdateSkipHelper.clear();
        ServerPlayer newWorld = playerAtTick(10);

        assertFalse(MapUpdateSkipHelper.shouldSkip(newWorld),
            "未清表时 10 < 500040 恒真 ⇒ 新存档的地图刷新会被永久跳过（这就是回归点）");
        assertFalse(MapUpdateSkipHelper.shouldSkipUpdate(newWorld, new ItemStack(Items.FILLED_MAP)),
            "shouldSkipUpdate 的同一判据链也要放行");
    }

    @Test
    @DisplayName("停服清理已登记：StaticCacheRegistry 的名单里能看到本类")
    void serverStopCleanup_isRegistered() {
        // 「写了 clear() 却没人调」是本项目的既有失败模式（2026-10-12 一次扫出 3 处）。
        // describe() 列出各登记项所属类 ⇒ 把「是否接线」变成可断言的事实。
        String registered = String.join("\n",
            com.qiqi.li.living.util.StaticCacheRegistry.describe());
        assertTrue(registered.contains("MapUpdateSkipHelper"),
            "本类必须在停服 / 客户端清理名单里，否则 clear() 就是死代码。当前名单：\n" + registered);
    }

    @Test
    @DisplayName("下线玩家的记录被丢掉（没有自然过期路径覆盖「再也不上线的人」）")
    void loggedOutPlayer_entriesDropped() {
        MapUpdateSkipHelper.markTeleported(playerAtTick(1_000));
        assertTrue(MapUpdateSkipHelper.shouldSkip(playerAtTick(1_001)), "预置：记录存在且生效");

        MapUpdateSkipHelper.onPlayerLoggedOut(PLAYER);

        assertFalse(MapUpdateSkipHelper.shouldSkip(playerAtTick(1_001)),
            "下线后该玩家的冷却记录应已消失");
    }
}
