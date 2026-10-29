package com.qiqi.li.living.domain.ender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * {@link LivingEnderChestAccessor} 的接缝测试（直连模式为主）。
 *
 * <p>路由模式的提取已有 {@link EnderRouteExtractTest} 端到端覆盖；本类补它的空白：
 * 直连模式的插入改栈契约、抽取轮询与活物品隔离、回滚、离线降级、isEmpty/isFull，
 * 以及路由模式下 {@code insert} 的真实行为（返回 0，注册在 {@link EnderRouteManager}）。</p>
 */
class LivingEnderChestAccessorTest {

    private static final int SLOT = 3;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private MinecraftServer server;
    private PlayerList playerList;
    private ServerPlayer player;
    private PlayerEnderChestContainer enderChest;
    private Set<Integer> transferred;

    @BeforeEach
    void setUp() {
        server = mock(MinecraftServer.class);
        playerList = mock(PlayerList.class);
        player = mock(ServerPlayer.class);
        enderChest = new PlayerEnderChestContainer();
        transferred = new HashSet<>();
        when(server.getPlayerList()).thenReturn(playerList);
        when(playerList.getPlayer(PLAYER)).thenReturn(player);
        when(player.getEnderChestInventory()).thenReturn(enderChest);
    }

    private LivingEnderChestAccessor direct() {
        return new LivingEnderChestAccessor(server, EnderChannelKey.playerChannel(PLAYER, 1),
            null, transferred, PLAYER);
    }

    private LivingEnderChestAccessor route(int count) {
        return new LivingEnderChestAccessor(server, EnderChannelKey.publicChannel(count), transferred);
    }

    private static ItemStack livingEnderChest(int count) {
        ItemStack stack = new ItemStack(Items.ENDER_CHEST, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static ItemStack livingDiamond() {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    @Test
    @DisplayName("tryCreate:非活末影箱返回 null,活末影箱按频道键创建(路由模式)")
    void tryCreate_onlyAcceptsLivingEnderChest() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.set(SLOT, new ItemStack(Items.ENDER_CHEST));
        assertNull(LivingEnderChestAccessor.tryCreate(
            null, ctx, SLOT, null, new HashSet<>(), ContainerSnapshot.EMPTY));

        ctx.set(SLOT, livingEnderChest(2));
        LivingEnderChestAccessor accessor = (LivingEnderChestAccessor) LivingEnderChestAccessor.tryCreate(
            null, ctx, SLOT, null, new HashSet<>(), ContainerSnapshot.EMPTY);
        assertNotNull(accessor);
        assertFalse(accessor.isDirectMode(), "未绑定玩家 ⇒ 路由模式");
        assertEquals(2, accessor.getChannelKey().count());
    }

    @Test
    @DisplayName("直连插入:写玩家末影箱,来栈按插入量扣减")
    void directInsert_mutatesInputAndChest() {
        LivingEnderChestAccessor accessor = direct();
        ItemStack input = new ItemStack(Items.DIAMOND, 2);

        int inserted = accessor.insert(input);

        assertEquals(2, inserted);
        assertTrue(input.isEmpty(), "SlotAccessor 契约：insert 后来栈为余量");
        assertEquals(2, enderChest.getItem(0).getCount());
    }

    @Test
    @DisplayName("直连模拟插入:返回可接受量,不写玩家末影箱")
    void directSimulateInsert_doesNotWrite() {
        LivingEnderChestAccessor accessor = direct();
        ItemStack input = new ItemStack(Items.DIAMOND, 2);

        assertEquals(2, accessor.simulateInsert(input));
        assertEquals(2, input.getCount(), "模拟不得修改来栈");
        assertTrue(enderChest.getItem(0).isEmpty(), "模拟不得写内容");
    }

    @Test
    @DisplayName("直连抽取后回滚:物品回到原槽,总量守恒")
    void directExtractThenRollback_restores() {
        enderChest.setItem(0, new ItemStack(Items.DIAMOND, 3));
        LivingEnderChestAccessor accessor = direct();

        ItemStack extracted = accessor.extract(2, null);
        assertEquals(2, extracted.getCount());
        assertEquals(1, enderChest.getItem(0).getCount());

        accessor.rollback(extracted);

        assertEquals(3, enderChest.getItem(0).getCount(), "回滚必须回到原槽");
    }

    @Test
    @DisplayName("直连回滚:原槽被异物品占据 ⇒ 回落到其它槽,不丢")
    void directRollback_fallsBackToAnotherSlot() {
        enderChest.setItem(0, new ItemStack(Items.DIAMOND, 2));
        LivingEnderChestAccessor accessor = direct();
        ItemStack extracted = accessor.extract(2, null);
        assertEquals(2, extracted.getCount());
        enderChest.setItem(0, new ItemStack(Items.STONE));

        accessor.rollback(extracted);

        assertEquals(1, enderChest.getItem(0).getCount(), "原槽的异物品不动");
        assertEquals(2, enderChest.getItem(1).getCount(), "钻石必须回落到其它空槽");
    }

    @Test
    @DisplayName("直连抽取:跳过玩家末影箱里的活物品")
    void directExtract_skipsLivingItems() {
        enderChest.setItem(0, livingDiamond());
        enderChest.setItem(1, new ItemStack(Items.DIAMOND));
        LivingEnderChestAccessor accessor = direct();

        ItemStack extracted = accessor.extract(1, null);

        assertEquals(Items.DIAMOND, extracted.getItem());
        assertFalse(LivingItemManager.isLivingItem(extracted), "不得抽出活物品");
        assertTrue(LivingItemManager.isLivingItem(enderChest.getItem(0)), "活物品留在原位");
        assertTrue(enderChest.getItem(1).isEmpty());
    }

    @Test
    @DisplayName("直连 isEmpty/isFull:空箱与 27 槽全满")
    void directEmptyFullFlags() {
        LivingEnderChestAccessor accessor = direct();
        assertTrue(accessor.isEmpty());
        assertFalse(accessor.isFull());

        for (int i = 0; i < 27; i++) {
            enderChest.setItem(i, new ItemStack(Items.DIAMOND, 64));
        }
        assertFalse(accessor.isEmpty());
        assertTrue(accessor.isFull());
    }

    @Test
    @DisplayName("直连离线:插入/模拟/抽取安全降级,isEmpty/isFull 保守返回")
    void directOffline_degradesSafely() {
        when(playerList.getPlayer(PLAYER)).thenReturn(null);
        LivingEnderChestAccessor accessor = direct();
        ItemStack input = new ItemStack(Items.DIAMOND, 2);

        assertEquals(0, accessor.insert(input));
        assertEquals(2, input.getCount(), "离线不得消耗来栈");
        assertEquals(0, accessor.simulateInsert(input));
        assertTrue(accessor.extract(1, null).isEmpty());
        assertTrue(accessor.isEmpty());
        assertTrue(accessor.isFull());
    }

    @Test
    @DisplayName("路由模式:insert/simulateInsert 返回 0,markTransferred 不写集合")
    void routeMode_insertZeroAndNoop() {
        LivingEnderChestAccessor accessor = route(2);
        ItemStack input = new ItemStack(Items.DIAMOND, 2);

        assertEquals(0, accessor.insert(input), "路由模式的 push 注册在 EnderRouteManager，不在 accessor");
        assertEquals(2, input.getCount());
        assertEquals(0, accessor.simulateInsert(input));
        assertFalse(accessor.isEmpty());
        assertFalse(accessor.isFull());

        accessor.markTransferred();
        assertTrue(transferred.isEmpty(), "末影箱不是任何容器的落点，不得登记");
        accessor.sync();
    }
}
