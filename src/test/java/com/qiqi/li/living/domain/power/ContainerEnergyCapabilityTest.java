package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.SimpleContainerContext;

/**
 * 对外能量能力（{@code Capabilities.EnergyStorage.BLOCK}）的<b>存在性</b>守卫
 * （§3.6 对外接口，2026-10-31 对齐流体 §10.4）。
 *
 * <p>规则：能力只在「容器里真有活涂蜡铜灯」时存在 —— 线缆类模组按「能力是否非 null」
 * 判邻居是不是储能（Flux {@code ForgeEnergyConnector#hasCapability} / Mekanism
 * {@code acceptorCache}），恒发实例会把任何 BE 认成储能。</p>
 *
 * <p>存在性由电力 tick 通告（{@link ContainerEnergyStorage#announcePresence}），
 * 变化时 {@code Level#invalidateCapabilities} 一次；边界两条：
 * ①「放进第一盏灯」由功能 tick 报名；②「拿走最后一盏灯」时功能已不在分组里，
 * 由写回钩子兜底撤名（本类专门守这条）。</p>
 */
class ContainerEnergyCapabilityTest {

    private static int counter;

    private BlockPos pos;
    private ItemStack[] slots;
    private Level level;
    private BlockEntity be;
    private SimpleContainerContext ctx;

    /** 最小可读写 handler：本类只关心槽位内容与「是不是容器」。 */
    private static final class FakeHandler implements IItemHandlerModifiable {
        private final ItemStack[] slots;

        FakeHandler(ItemStack[] slots) {
            this.slots = slots;
        }

        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) { return slots[slot]; }
        @Override public void setStackInSlot(int slot, ItemStack stack) { slots[slot] = stack; }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }
    }

    private static ItemStack livingBulb(int count) {
        ItemStack stack = new ItemStack(Items.WAXED_COPPER_BULB, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    @BeforeEach
    void setUp() {
        pos = new BlockPos(40_000 + counter++, 9, -5_432);
        slots = new ItemStack[9];
        Arrays.fill(slots, ItemStack.EMPTY);

        level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(level.getGameTime()).thenReturn(100L);
        doReturn(Blocks.AIR.defaultBlockState()).when(level).getBlockState(any());
        doReturn(null).when(level).getCapability(eq(Capabilities.EnergyStorage.BLOCK), any(), any());

        be = mock(BlockEntity.class);
        when(be.getBlockPos()).thenReturn(pos);
        when(be.getLevel()).thenReturn(level);
        doReturn(be).when(level).getBlockEntity(any());
        doReturn(new FakeHandler(slots)).when(level)
            .getCapability(eq(Capabilities.ItemHandler.BLOCK), any(), any());

        ctx = new SimpleContainerContext(new FakeHandler(slots),
            null, List.of(pos), List.of(be), level);
    }

    @Test
    @DisplayName("存在性跟着活铜灯走：无灯不报名 / 放入报名 / 拿走撤名")
    void presence_followsLivingBulb() {
        ContainerLivingItemHandler.processContext(ctx, level);
        assertNull(ContainerEnergyStorage.resolveProvider(be, null),
            "空容器不报名（线缆按「能力非 null」判储能）");

        slots[0] = livingBulb(4);
        ContainerLivingItemHandler.processContext(ctx, level);
        assertNotNull(ContainerEnergyStorage.resolveProvider(be, null), "有活铜灯 ⇒ 报名");

        slots[0] = ItemStack.EMPTY;
        ContainerLivingItemHandler.processContext(ctx, level);
        assertNull(ContainerEnergyStorage.resolveProvider(be, null),
            "最后一个铜块被拿走 ⇒ 撤名（电力功能已不在分组里，靠写回兜底）");
    }

    @Test
    @DisplayName("存在性边沿：精确失效一次（线缆缓存才肯重查）")
    void presenceEdge_invalidatesExactlyOnce() {
        ContainerLivingItemHandler.processContext(ctx, level);
        verify(level, never()).invalidateCapabilities(pos);

        slots[0] = livingBulb(4);
        ContainerLivingItemHandler.processContext(ctx, level);
        verify(level, times(1)).invalidateCapabilities(pos);

        // 存在性没变 ⇒ 不重复通告（每 tick 无效失效会把线缆缓存砸烂）
        ContainerLivingItemHandler.processContext(ctx, level);
        verify(level, times(1)).invalidateCapabilities(pos);

        slots[0] = ItemStack.EMPTY;
        ContainerLivingItemHandler.processContext(ctx, level);
        verify(level, times(2)).invalidateCapabilities(pos);
    }

    @Test
    @DisplayName("客户端恒不报名（容器级数据只存在服务端）")
    void provider_neverRegistersOnClient() {
        slots[0] = livingBulb(4);
        ContainerLivingItemHandler.processContext(ctx, level);
        assertNotNull(ContainerEnergyStorage.resolveProvider(be, null), "服务端已报名（前置）");

        when(level.isClientSide()).thenReturn(true);
        assertNull(ContainerEnergyStorage.resolveProvider(be, null),
            "客户端拿不到容器级数据 ⇒ 不报名");
    }

    @Test
    @DisplayName("provider 让位：直接实现者 / 战利品容器 / level=null / 已有主人")
    void provider_yieldsToRealOwners() {
        // ① 直接实现 IEnergyStorage 的 BE
        BlockEntity selfImpl = mock(BlockEntity.class,
            org.mockito.Mockito.withSettings().extraInterfaces(IEnergyStorage.class));
        when(selfImpl.getBlockPos()).thenReturn(pos);
        when(selfImpl.getLevel()).thenReturn(level);
        assertNull(ContainerEnergyStorage.resolveProvider(selfImpl, null), "自己实现 ⇒ 让位");

        // ② 随机战利品容器（未开箱）
        BlockEntity loot = mock(BlockEntity.class,
            org.mockito.Mockito.withSettings().extraInterfaces(RandomizableContainer.class));
        when(loot.getBlockPos()).thenReturn(pos);
        when(loot.getLevel()).thenReturn(level);
        when(((RandomizableContainer) (Object) loot).getLootTable()).thenReturn(net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.LOOT_TABLE,
            net.minecraft.resources.ResourceLocation.parse("minecraft:chests/simple_dungeon")));
        assertNull(ContainerEnergyStorage.resolveProvider(loot, null), "战利品容器 ⇒ 让位（与 tick 同款）");

        // ③ level == null
        BlockEntity orphan = mock(BlockEntity.class);
        when(orphan.getBlockPos()).thenReturn(pos);
        when(orphan.getLevel()).thenReturn(null);
        assertNull(ContainerEnergyStorage.resolveProvider(orphan, null), "无 level ⇒ null");

        // ④ 已有主人（模组机器自身储能）—— 先报名，否则在「无灯 ⇒ null」就早退了
        slots[0] = livingBulb(4);
        ContainerLivingItemHandler.processContext(ctx, level);
        doReturn(new ContainerEnergyStorage(be)).when(level)
            .getCapability(eq(Capabilities.EnergyStorage.BLOCK), any(), any());
        assertNull(ContainerEnergyStorage.resolveProvider(be, null), "已有主人 ⇒ 让位（不夺主）");
    }
}
