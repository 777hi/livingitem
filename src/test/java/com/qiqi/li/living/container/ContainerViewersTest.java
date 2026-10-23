package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.Slot;

/**
 * 「谁正在看这个容器」的<b>唯一</b>判定入口的守卫（2026-10-23）。
 *
 * <h3>为什么值得单测</h3>
 * <p>这个判定原本散在两条轨里各一份 if-chain（{@code FluidFlowServerSync.viewersOf} 与
 * {@code LivingItemRuntimeSync.flush}），加新形态时漏改一条就静默失效。
 * 2026-10-23 一天内连踩两次：修了流体那条（#7 viewer 匹配），漏了运行时那条
 * ⇒ 真机表现「流体层正常渲染，但活漏斗高级 tooltip 不显示容器信息」。</p>
 *
 * <p>⇒ 收编成 {@link ContainerViewers#of} 后，本测试就是「新形态必须在这里加分支持」的
 * 唯一检查点 —— 两条轨同时生效，不存在只修一条。</p>
 *
 * <h3>ported from</h3>
 * <p>末影箱门控两条用例分别来自 {@code FluidFlowServerSyncTest}（2026-10-06 第 ③ 次泄漏修复）
 * 与 {@code LivingItemRuntimeSyncTest}（2026-10-18 同病因换轨重现）——
 * 两条轨的门控语义一致，故合并到一处钉。</p>
 */
class ContainerViewersTest {

    private static final UUID OWNER = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        ItemFormContainers.clearForTest();
    }

    // ── 玩家态：只发主人 + 末影箱门控 ──────────────────────────────────────

    @Test
    @DisplayName("背包（PLAYER_INV）：只发主人本人，不门控（隐私红线）")
    void playerInv_sendsOnlyOwner_notGated() {
        ServerPlayer owner = player(OWNER);
        ServerPlayer stranger = player(UUID.randomUUID());
        String key = "player_" + OWNER;

        List<ServerPlayer> viewers = ContainerViewers.of(List.of(owner, stranger), key, List.of());

        assertEquals(List.of(owner), viewers,
            "背包内容属私密信息 ⇒ 必须按 UUID 精确匹配，且不因为「没在看」就不发");
    }

    @Test
    @DisplayName("末影箱：开着末影箱 GUI 才发（门控是根治点）")
    void enderChest_gatedByViewing() {
        ServerPlayer owner = player(OWNER);
        String key = "player_" + OWNER + "_ender_chest";

        // 正在看自己的末影箱 ⇒ 发
        ChestMenu enderMenu = mock(ChestMenu.class);
        when(enderMenu.getContainer()).thenReturn(new PlayerEnderChestContainer());
        owner.containerMenu = enderMenu;
        assertTrue(ContainerViewers.of(List.of(owner), key, List.of()).contains(owner),
            "看的是末影箱 ⇒ 下发");

        // 关掉末影箱、打开普通箱子 ⇒ 不发（第 ③ 次泄漏的根因就在这条）
        ChestMenu chestMenu = mock(ChestMenu.class);
        when(chestMenu.getContainer()).thenReturn(new SimpleContainer(27));
        owner.containerMenu = chestMenu;
        assertFalse(ContainerViewers.of(List.of(owner), key, List.of()).contains(owner),
            "看的是普通箱子 ⇒ **不**下发（否则末影箱的水渲染到箱子界面上）");
    }

    // ── 物品形态：没有位置，问「谁正开着它」────────────────────────────────

    @Test
    @DisplayName("物品形态：按菜单锚点认观众（没有位置可匹配）")
    void itemForm_matchesByMenuAnchor() {
        UUID containerUuid = UUID.randomUUID();
        String key = ContainerKeys.cuuidKey(containerUuid);

        ServerPlayer viewer = player(UUID.randomUUID());
        viewer.containerMenu = menuOf(containerUuid);
        ServerPlayer stranger = player(UUID.randomUUID());
        stranger.containerMenu = menuOf(UUID.randomUUID());   // 开着另一个活背包

        List<ServerPlayer> viewers = ContainerViewers.of(List.of(viewer, stranger), key, List.of());

        assertEquals(List.of(viewer), viewers,
            "物品形态没有世界位置 ⇒ 只能问「谁正开着它」；开着别的容器的不能算");
    }

    @Test
    @DisplayName("物品形态：位置参数为空也不能崩（它本来就不用位置）")
    void itemForm_emptyPositionsIsFine() {
        UUID containerUuid = UUID.randomUUID();
        ServerPlayer viewer = player(UUID.randomUUID());
        viewer.containerMenu = menuOf(containerUuid);

        assertEquals(1, ContainerViewers.of(
            List.of(viewer), ContainerKeys.cuuidKey(containerUuid), null).size());
    }

    // ── 位置匹配（世界方块容器）───────────────────────────────────────────

    @Test
    @DisplayName("世界容器：位置为空 ⇒ 没有观众（宁可不发，也不发错人）")
    void worldBlock_emptyPositions_noViewers() {
        ServerPlayer any = player(UUID.randomUUID());
        any.containerMenu = mock(AbstractContainerMenu.class);

        assertTrue(ContainerViewers.of(
            List.of(any), "minecraft:overworld|chest_1_2_3", List.of()).isEmpty());
        assertTrue(ContainerViewers.of(
            List.of(any), "minecraft:overworld|chest_1_2_3", null).isEmpty());
    }

    // ── 退化输入 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("退化输入：空玩家表 / null 键 / 未知键形 ⇒ 空列表，不抛")
    void degenerateInputs_safe() {
        assertEquals(0, ContainerViewers.of(List.of(), "player_x", List.of()).size());
        assertEquals(0, ContainerViewers.of(List.of(player(UUID.randomUUID())), null, List.of()).size());
        assertEquals(0, ContainerViewers.of(List.of(player(UUID.randomUUID())), "乱键", List.of()).size());
    }

    // ── 替身 ──────────────────────────────────────────────────────────────

    private static ServerPlayer player(UUID uuid) {
        ServerPlayer p = mock(ServerPlayer.class);
        when(p.getUUID()).thenReturn(uuid);
        return p;
    }

    /**
     * 一个「锚定该容器 UUID」的背包菜单。
     *
     * <p>⚠️ 来源必须<b>只认自己的那个菜单</b>（{@code m == menu}）—— 否则先注册的来源会把
     * 任何菜单都认成它的容器，两个玩家就都成了观众（踩过）。</p>
     */
    private static AbstractContainerMenu menuOf(UUID containerUuid) {
        AbstractContainerMenu menu = mock(AbstractContainerMenu.class);
        ItemFormContainers.register(new ItemFormContainers.Source() {
            @Override public boolean isContainerItem(net.minecraft.world.item.ItemStack stack) { return true; }
            @Override public net.neoforged.neoforge.items.IItemHandler contentHandler(
                    net.minecraft.world.item.ItemStack stack) {
                return new net.neoforged.neoforge.items.ItemStackHandler(9);
            }
            @Override public ItemFormContainers.Handle handleOfMenu(
                    AbstractContainerMenu m, net.minecraft.world.entity.player.Player p) {
                return m == menu
                    ? new ItemFormContainers.Handle(
                        new net.neoforged.neoforge.items.ItemStackHandler(9), containerUuid, 9)
                    : null;
            }
        });
        return menu;
    }
}
