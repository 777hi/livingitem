package com.qiqi.li.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qiqi.li.living.container.ContainerKeys;
import com.qiqi.li.living.container.ContainerKind;
import com.qiqi.li.living.container.ItemFormContainers;
import com.qiqi.li.living.domain.hopper.HopperSegment;
import com.qiqi.li.living.domain.hopper.HopperSegment.HopperRuntime;
import com.qiqi.li.living.domain.hopper.ResolvedSlotData;
import com.qiqi.li.living.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.runtime.RuntimeSegments;
import com.qiqi.li.testutil.FakeContainerContext;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 末影箱 viewer 门控判定测试（2026-10-18）。
 *
 * <p>守卫的回归：末影箱包曾无条件每 tick 直发主人（无 viewer 匹配）⇒ 冲掉客户端通用缓存里
 * 「当前打开的世界容器」⇒ 容器 GUI 的 tooltip 遥测全丢。同族 bug 在流体轨连修三次
 * （{@code FluidFlowServerSync}「第 ③ 次泄漏修复」），本次是同一病因换轨重现。
 * 判据本身极简，测试的价值在于把规则钉住：将来谁把门控删了 / 把背包也门控了，这里立刻红。</p>
 */
class LivingItemRuntimeSyncTest {

    @Test
    @DisplayName("背包（PLAYER_INV）不门控——写独立背包缓存，不冲任何东西")
    void playerInv_neverGated() {
        assertTrue(LivingItemRuntimeSync.shouldSendToOwner(ContainerKind.PLAYER_INV, false));
        assertTrue(LivingItemRuntimeSync.shouldSendToOwner(ContainerKind.PLAYER_INV, true));
    }

    @Test
    @DisplayName("末影箱：只有真开着 GUI 才发（门控是根治点）")
    void enderChest_gatedByViewing() {
        assertTrue(LivingItemRuntimeSync.shouldSendToOwner(ContainerKind.ENDER_CHEST, true));
        assertFalse(LivingItemRuntimeSync.shouldSendToOwner(ContainerKind.ENDER_CHEST, false));
    }

    @AfterEach
    void tearDown() {
        ContainerRuntimeCache.clear();
        ItemFormContainers.clearForTest();
    }

    // ── 物品形态容器（活背包，2026-10-23 真机实录）────────────────────────────
    //
    // 现场：活漏斗的高级 tooltip 不显示容器信息（源槽/目标槽/容器尺寸）。
    // 根因：物品形态容器没有世界位置 ⇒ flush 的「按 positions 匹配 viewer」恒为空
    // ⇒ 运行时数据永不发客户端 ⇒ tooltip 回落 DataComponent（那里只有 EMPTY）。
    // 与 FluidFlowServerSync.viewersOf 的 #7 同源，上次只修了流体那条轨。

    @Test
    @DisplayName("物品形态容器：按「谁正开着它」发包，不按位置（位置恒空）")
    void itemForm_sendsToViewerByMenuAnchor() {
        UUID uuid = UUID.randomUUID();
        String key = ContainerKeys.cuuidKey(uuid);

        // 服务端确实攒了脏数据（活漏斗每 tick 写 slotInfo）
        FakeContainerContext ctx = new FakeContainerContext(27, 9, key);
        ContainerRuntimeCache.update(ctx, 0, RuntimeSegments.EMPTY.with(
            HopperSegment.INSTANCE,
            new HopperRuntime(0,
                new ResolvedSlotData(0, 1, 2, 27, 9))));

        // 一个玩家，开着锚定该 uuid 的背包菜单
        net.minecraft.server.level.ServerPlayer viewer = mock(net.minecraft.server.level.ServerPlayer.class);
        net.minecraft.world.inventory.AbstractContainerMenu menu =
            mock(net.minecraft.world.inventory.AbstractContainerMenu.class);
        viewer.containerMenu = menu;   // public 字段（Player.containerMenu）
        viewer.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
        ItemFormContainers.register(new ItemFormContainers.Source() {
            @Override public boolean isContainerItem(net.minecraft.world.item.ItemStack stack) { return true; }
            @Override public net.neoforged.neoforge.items.IItemHandler contentHandler(
                    net.minecraft.world.item.ItemStack stack) { return new net.neoforged.neoforge.items.ItemStackHandler(9); }
            @Override public ItemFormContainers.Handle handleOfMenu(
                    net.minecraft.world.inventory.AbstractContainerMenu m,
                    net.minecraft.world.entity.player.Player p) {
                return new ItemFormContainers.Handle(
                    new net.neoforged.neoforge.items.ItemStackHandler(9), uuid, 9);
            }
        });

        LivingItemRuntimeSync.flush(List.of(viewer), k -> List.of());

        org.mockito.ArgumentCaptor<LivingItemSyncPacket> captor =
            org.mockito.ArgumentCaptor.forClass(LivingItemSyncPacket.class);
        verify(viewer.connection).send(captor.capture());
        assertEquals(key, captor.getValue().containerKey(),
            "必须发这个物品形态容器的键 —— 发错键客户端会显示成别的容器的数据");
    }

    @Test
    @DisplayName("世界容器 / 掉落物不走本路径（viewer 匹配另走），恒 true 仅防御")
    void nonOwnedKinds_defensive() {
        assertTrue(LivingItemRuntimeSync.shouldSendToOwner(ContainerKind.WORLD_BLOCK, false));
        assertTrue(LivingItemRuntimeSync.shouldSendToOwner(ContainerKind.ITEM_ENTITY, false));
    }
}
