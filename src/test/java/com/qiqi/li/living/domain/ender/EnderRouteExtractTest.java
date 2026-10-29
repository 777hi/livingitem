package com.qiqi.li.living.domain.ender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.container.ItemFormLiveViews;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 活末影箱路由的**端到端提取**（注册 → 提取 → 消耗）。
 *
 * <p>2026-10-28 路由改了三处（维度隔离 / 活视图寻址 / 注册闸门），但只有碎片测试。
 * 本类按「源形态」各钉一条主线：方块位置 / 玩家背包 / 物品形态（新能力）/ 源不可用 ⇒ 丢弃。</p>
 *
 * <p>⚠️ 最后一条是 2026-10-29 的口径变更：源解析不到一律丢弃，<b>没有「暂时不可用」的
 * 保留态</b>（源不在跑就不产出，恢复跑时漏斗会再推一次、路由自己重新注册）。</p>
 *
 * <p>⚠️ 全部走真实的 {@link EnderChannelRegistry} 与真实的 {@link LivingEnderChestAccessor}，
 * 只 mock 世界/玩家能力 —— 断言的是「物品真的从源移位到提取结果」。</p>
 */
class EnderRouteExtractTest {

    private static final EnderChannelKey CHANNEL = EnderChannelKey.publicChannel(2);
    private static final String ITEM_ID = "minecraft:diamond";

    private EnderChannelRegistry registry;
    private MinecraftServer server;

    @BeforeEach
    void setUp() {
        registry = EnderChannelRegistry.getInstance();
        registry.clearAll();
        ItemFormLiveViews.clear();
        server = mock(MinecraftServer.class);
    }

    @AfterEach
    void tearDown() {
        registry.clearAll();
        ItemFormLiveViews.clear();
    }

    private static EnderChannelEntry entry(net.minecraft.resources.ResourceKey<Level> dim,
                                           BlockPos pos, String containerKey, int slot) {
        return new EnderChannelEntry(ITEM_ID, dim, pos, slot, 1, containerKey, 3, containerKey, null);
    }

    private LivingEnderChestAccessor accessor() {
        return new LivingEnderChestAccessor(server, CHANNEL, Set.of());
    }

    @Test
    @DisplayName("方块容器 → 末影箱：按位置取能力并提取（回归）")
    void extract_fromBlockContainer() {
        BlockPos pos = new BlockPos(3, 64, 3);
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.isLoaded(pos)).thenReturn(true);

        ItemStackHandler handler = new ItemStackHandler(27);
        handler.setStackInSlot(4, new ItemStack(Items.DIAMOND, 2));
        doReturn(handler).when(level).getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        when(server.getLevel(Level.OVERWORLD)).thenReturn(level);

        registry.offer(CHANNEL, entry(Level.OVERWORLD, pos, "chest_3_64_3", 4));

        ItemStack got = accessor().extract(64, ItemStack.EMPTY);

        assertEquals(2, got.getCount(), "应按位置取到源槽并提取");
        assertTrue(handler.getStackInSlot(4).isEmpty(), "源槽必须被真实扣减");
    }

    @Test
    @DisplayName("玩家背包（player_）→ 末影箱：按 UUID 找玩家并提取")
    void extract_fromPlayerInventory() {
        UUID pid = UUID.randomUUID();
        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.level()).thenReturn(level);

        ItemStackHandler handler = new ItemStackHandler(9);
        handler.setStackInSlot(2, new ItemStack(Items.DIAMOND, 5));
        when(player.getCapability(Capabilities.ItemHandler.ENTITY)).thenReturn(handler);

        PlayerList playerList = mock(PlayerList.class);
        when(playerList.getPlayer(pid)).thenReturn(player);
        when(server.getPlayerList()).thenReturn(playerList);

        registry.offer(CHANNEL, entry(Level.OVERWORLD, null, "player_" + pid, 2));

        ItemStack got = accessor().extract(64, ItemStack.EMPTY);

        assertEquals(5, got.getCount());
        assertTrue(handler.getStackInSlot(2).isEmpty());
    }

    @Test
    @DisplayName("物品形态容器（cuuid_）→ 末影箱：按容器 UUID 取活视图并提取（2026-10-28 新能力）")
    void extract_fromItemFormContainer() {
        UUID containerUuid = UUID.randomUUID();
        ItemStackHandler handler = new ItemStackHandler(27);
        handler.setStackInSlot(0, new ItemStack(Items.DIAMOND, 3));
        ItemFormLiveViews.register(containerUuid, handler, null);

        registry.offer(CHANNEL, entry(Level.OVERWORLD, null, "cuuid_" + containerUuid, 0));

        ItemStack got = accessor().extract(64, ItemStack.EMPTY);

        assertEquals(3, got.getCount(), "背包里的货应能被路由取出（旧实现在此静默丢弃）");
        assertTrue(handler.getStackInSlot(0).isEmpty(), "源槽必须被真实扣减");
    }

    @Test
    @DisplayName("物品形态源回滚：提取后 rollback 回到活视图（不丢物品）")
    void rollback_toItemFormLiveView_restores() {
        UUID containerUuid = UUID.randomUUID();
        ItemStackHandler handler = new ItemStackHandler(27);
        handler.setStackInSlot(0, new ItemStack(Items.DIAMOND, 3));
        ItemFormLiveViews.register(containerUuid, handler, null);
        registry.offer(CHANNEL, entry(Level.OVERWORLD, null, "cuuid_" + containerUuid, 0));

        LivingEnderChestAccessor accessor = accessor();
        ItemStack got = accessor.extract(2, ItemStack.EMPTY);
        assertEquals(2, got.getCount());
        assertEquals(1, handler.getStackInSlot(0).getCount());

        accessor.rollback(got);

        assertEquals(3, handler.getStackInSlot(0).getCount(), "回滚必须放回物品形态活视图");
    }

    @Test
    @DisplayName("源解析不到 ⇒ 条目丢弃（路由只有注册/不注册两态，2026-10-29 口径）")
    void extract_itemFormWithoutLiveView_dropsEntry() {
        UUID containerUuid = UUID.randomUUID();
        EnderChannelEntry e = entry(Level.OVERWORLD, null, "cuuid_" + containerUuid, 0);
        registry.offer(CHANNEL, e);

        ItemStack got = accessor().extract(64, ItemStack.EMPTY);

        assertTrue(got.isEmpty(), "取不到货是预期（活视图过期）");
        assertFalse(registry.contains(CHANNEL, e),
            "条目必须被丢弃 —— 源不在跑就不产出，恢复跑时漏斗会再推一次、路由自己重新注册；"
                + "留着它只会让队列每轮都在死条目上失败一次");
        assertNull(registry.peek(CHANNEL), "频道应已清空，下一轮不再空转");
    }

    @Test
    @DisplayName("方块被搬走（区块已加载但 BE 不在）⇒ 条目丢弃，不反复空转")
    void extract_blockGone_dropsEntry() {
        BlockPos pos = new BlockPos(5, 64, 5);
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.isLoaded(pos)).thenReturn(true);
        // BE 已被搬走 ⇒ 方块位置拿不到物品能力
        when(level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null)).thenReturn(null);
        when(server.getLevel(Level.OVERWORLD)).thenReturn(level);

        EnderChannelEntry e = entry(Level.OVERWORLD, pos, "chest_5_64_5", 4);
        registry.offer(CHANNEL, e);

        LivingEnderChestAccessor accessor = accessor();
        assertTrue(accessor.extract(64, ItemStack.EMPTY).isEmpty(), "源不在了，取不到货");
        assertFalse(registry.contains(CHANNEL, e), "死条目必须被丢弃（旧实现 reoffer 会让它永远在队里）");

        // 再抽一次：不该再在死条目上白跑一轮
        assertTrue(accessor.extract(64, ItemStack.EMPTY).isEmpty());
        assertNull(registry.peek(CHANNEL), "频道应已清空");
    }
}
