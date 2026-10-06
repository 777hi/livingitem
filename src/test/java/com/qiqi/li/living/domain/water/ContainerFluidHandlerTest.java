package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import com.qiqi.li.living.container.ContainerLivingItemHandler;

/**
 * {@link ContainerFluidHandler} 行为快照（§10 管道抽取，2026-10-06）。
 *
 * <p>测：能力语义（tank 枚举 / 抽取消耗源 / 返回量 / 只出不进）+ provider 四段让位判定。
 * Level / BlockEntity 走 Mockito（项目已有同款手法）；活数据用
 * {@link ContainerLivingItemHandler#resolveContextAt} + {@code getFluidData} 预置，
 * 与 tick 路径同源同键（同 containerKey ⇒ 同一份活数据实例）。
 * 容器数据表是静态的（项目红线）⇒ 每个用例用不同坐标，避免串数据。</p>
 */
class ContainerFluidHandlerTest {

    private static int counter;
    private BlockPos pos;

    @BeforeEach
    void freshPos() {
        pos = new BlockPos(30_000 + counter++, 7, -12_345);
    }

    /** 最小 IItemHandler：本用例从不读写物品，只需非 null（反查链的入口条件）。 */
    private static IItemHandlerModifiable emptyItems() {
        return new IItemHandlerModifiable() {
            @Override public int getSlots() { return 9; }
            @Override public ItemStack getStackInSlot(int slot) { return ItemStack.EMPTY; }
            @Override public void setStackInSlot(int slot, ItemStack stack) { }
            @Override public int getSlotLimit(int slot) { return 64; }
            @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }
            @Override public ItemStack insertItem(int slot, ItemStack s, boolean sim) { return s; }
            @Override public ItemStack extractItem(int slot, int amount, boolean sim) { return ItemStack.EMPTY; }
        };
    }
    // MARK

    private Level mockLevel(BlockEntity be) {
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(level.dimension()).thenReturn(null);
        doReturn(Blocks.AIR.defaultBlockState()).when(level).getBlockState(any());
        doReturn(be).when(level).getBlockEntity(any());
        doReturn(emptyItems()).when(level).getCapability(eq(Capabilities.ItemHandler.BLOCK), any(), any());
        doReturn(null).when(level).getCapability(eq(Capabilities.FluidHandler.BLOCK), any(), any());
        return level;
    }

    private BlockEntity mockBe(Level level) {
        BlockEntity be = mock(BlockEntity.class);
        when(be.getBlockPos()).thenReturn(pos);
        when(be.getLevel()).thenReturn(level);
        return be;
    }

    /** 预置活数据（与 tick 路径同源：同一 containerKey ⇒ 同一份活数据）。 */
    private ContainerFluidData seed(Level level, int... sourceSlots) {
        var ctx = ContainerLivingItemHandler.resolveContextAt(level, pos, null);
        assertNotNull(ctx, "反查链应产出容器上下文");
        ContainerFluidData data = ContainerLivingItemHandler.getFluidData(ctx);
        for (int slot : sourceSlots) {
            data.registerGeneratedSource(slot, Fluids.WATER.getFluidType());
        }
        return data;
    }

    private IFluidHandler handlerFor(BlockEntity be) {
        IFluidHandler h = ContainerFluidHandler.resolveProvider(be, null);
        assertNotNull(h, "普通容器 BE 应拿到能力实例（自适应，永不 null 切换）");
        return h;
    }
    // MARK

    @Test
    @DisplayName("㊻ 抽取即消耗源：tank 数 = 派生源数；SIMULATE 不消耗，EXECUTE 删源")
    void drain_consumesSource() {
        Level level = mockLevel(mock(BlockEntity.class));
        BlockEntity be = mockBe(level);
        ContainerFluidData data = seed(level, 3);

        IFluidHandler h = handlerFor(be);
        assertEquals(1, h.getTanks(), "一个派生源 = 一个 tank");
        FluidStack inTank = h.getFluidInTank(0);
        assertFalse(inTank.isEmpty(), "tank 0 有活水");
        assertEquals(1000, inTank.getAmount(), "一源 = 1000mB（无缓冲口径）");
        assertEquals(1000, h.getTankCapacity(0), "容量口径 1000mB");

        FluidStack sim = h.drain(1000, IFluidHandler.FluidAction.SIMULATE);
        assertEquals(1000, sim.getAmount(), "SIMULATE 返回满额");
        assertTrue(data.isGeneratedSource(3), "SIMULATE 不消耗源（Create 的探测走这条）");

        FluidStack real = h.drain(1000, IFluidHandler.FluidAction.EXECUTE);
        assertEquals(1000, real.getAmount(), "EXECUTE 返回满额");
        assertFalse(data.isGeneratedSource(3), "抽取 = 消耗源（≡ 汲走）");
        assertEquals(0, h.getTanks(), "源没了 ⇒ 0 tank（自适应，不 null 切换）");
    }

    @Test
    @DisplayName("㊼ 整源语义：拿不满 1000 ⇒ 一分不给（源不丢）；给满 ⇒ 删源")
    void drain_requiresWholeSource() {
        Level level = mockLevel(mock(BlockEntity.class));
        BlockEntity be = mockBe(level);
        ContainerFluidData data = seed(level, 0);
        IFluidHandler h = handlerFor(be);

        assertTrue(h.drain(500, IFluidHandler.FluidAction.EXECUTE).isEmpty(),
            "请求 500 < 整源 1000 ⇒ 不给（否则慢管道会白耗整源）");
        assertTrue(h.drain(999, IFluidHandler.FluidAction.SIMULATE).isEmpty(), "999 也不给（SIMULATE 同口径）");
        assertTrue(data.isGeneratedSource(0), "源纹丝不动");
        assertEquals(1000, h.getFluidInTank(0).getAmount(), "tank 恒报整源 1000mB（无分数源）");

        assertEquals(1000, h.drain(1000, IFluidHandler.FluidAction.EXECUTE).getAmount(), "给满 ⇒ 拿到 1000");
        assertFalse(data.isGeneratedSource(0), "整源消耗 ⇒ 源消失");
        assertEquals(0, h.getTanks());
    }

    @Test
    @DisplayName("㊾⁺ 慢管道（每次 <1000）不消耗任何源；整源管道按源个数逐个抽")
    void slowPipe_consumesNothing_fastPipeDrainsOneByOne() {
        Level level = mockLevel(mock(BlockEntity.class));
        BlockEntity be = mockBe(level);
        ContainerFluidData data = seed(level, 0, 1);   // 两个源
        IFluidHandler h = handlerFor(be);

        for (int i = 0; i < 20; i++) {
            assertTrue(h.drain(100, IFluidHandler.FluidAction.EXECUTE).isEmpty(), "慢管道第 " + (i + 1) + " 次抽不到");
        }
        assertEquals(2, h.getTanks(), "慢管道一个源都拿不到（不是瞬间抽空，也不是白耗）");

        assertEquals(1000, h.drain(1000, IFluidHandler.FluidAction.EXECUTE).getAmount(), "整源管道拿到 1000");
        assertFalse(data.isGeneratedSource(0));
        assertTrue(data.isGeneratedSource(1), "源 1 不受影响（逐个消耗，不连带）");
    }
    // MARK

    @Test
    @DisplayName("㊽ 多源按槽位序稳定枚举；异种流体请求 ⇒ EMPTY")
    void tanks_stableOrderAndForeignFluidEmpty() {
        Level level = mockLevel(mock(BlockEntity.class));
        BlockEntity be = mockBe(level);
        seed(level, 5, 1);

        IFluidHandler h = handlerFor(be);
        assertEquals(2, h.getTanks(), "两个源 = 两个 tank");
        assertFalse(h.getFluidInTank(0).isEmpty(), "tank 0 = 槽位序第一个（slot 1）");
        assertFalse(h.getFluidInTank(1).isEmpty(), "tank 1 = slot 5");
        assertTrue(h.getFluidInTank(2).isEmpty(), "越界 ⇒ EMPTY");
        assertTrue(h.getFluidInTank(-1).isEmpty(), "负索引 ⇒ EMPTY");

        FluidStack lava = new FluidStack(Fluids.LAVA, 1000);
        assertTrue(h.drain(lava, IFluidHandler.FluidAction.EXECUTE).isEmpty(), "只有水源 ⇒ 要岩浆给不了");
        assertEquals(2, h.getTanks(), "异种请求不消耗任何源");
    }

    @Test
    @DisplayName("㊾ 只出不进：fill 恒 0；isFluidValid 如实回答「是否持有该流体」")
    void fillAlwaysZero_isFluidValidReportsHeld() {
        Level level = mockLevel(mock(BlockEntity.class));
        BlockEntity be = mockBe(level);
        ContainerFluidData data = seed(level, 0);

        IFluidHandler h = handlerFor(be);
        assertEquals(0, h.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE),
            "fill 恒 0（活桶仍是源的唯一种子工具）");
        assertTrue(data.isGeneratedSource(0), "注入不产生源");

        assertTrue(h.isFluidValid(0, new FluidStack(Fluids.WATER, 1)), "持有水 ⇒ true");
        assertFalse(h.isFluidValid(0, new FluidStack(Fluids.LAVA, 1)), "不持有岩浆 ⇒ false");
        assertFalse(h.isFluidValid(0, FluidStack.EMPTY), "空栈 ⇒ false");
    }
    // MARK

    @Test
    @DisplayName("㊿ 无 ItemHandler 的 BE（非容器）⇒ 0 tank、drain EMPTY，不抛异常")
    void nonContainer_isHarmless() {
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        doReturn(null).when(level).getCapability(eq(Capabilities.ItemHandler.BLOCK), any(), any());
        doReturn(null).when(level).getCapability(eq(Capabilities.FluidHandler.BLOCK), any(), any());
        BlockEntity be = mockBe(level);

        IFluidHandler h = handlerFor(be);
        assertEquals(0, h.getTanks(), "非容器没有活数据");
        assertTrue(h.getFluidInTank(0).isEmpty(), "无 tank");
        assertTrue(h.drain(1000, IFluidHandler.FluidAction.EXECUTE).isEmpty(), "抽不出");
    }

    @Test
    @DisplayName("㋀ provider 让位：直接实现者 / 战利品容器 / level=null / 已有主人")
    void provider_yieldsToRealOwners() {
        // ① 直接实现 IFluidHandler 的 BE 让位
        BlockEntity selfImpl = mock(BlockEntity.class,
            org.mockito.Mockito.withSettings().extraInterfaces(IFluidHandler.class));
        Level selfLevel = mockLevel(selfImpl);
        when(selfImpl.getBlockPos()).thenReturn(pos);
        when(selfImpl.getLevel()).thenReturn(selfLevel);
        assertNull(ContainerFluidHandler.resolveProvider(selfImpl, null), "自己实现 ⇒ 让位");

        // ② 随机战利品容器（未开箱）让位
        BlockEntity loot = mock(BlockEntity.class,
            org.mockito.Mockito.withSettings().extraInterfaces(net.minecraft.world.RandomizableContainer.class));
        Level lootLevel = mockLevel(loot);
        when(loot.getBlockPos()).thenReturn(pos);
        when(loot.getLevel()).thenReturn(lootLevel);
        when(((net.minecraft.world.RandomizableContainer) (Object) loot).getLootTable())
            .thenReturn(ResourceKey.create(
                net.minecraft.core.registries.Registries.LOOT_TABLE,
                ResourceLocation.parse("minecraft:chests/simple_dungeon")));
        assertNull(ContainerFluidHandler.resolveProvider(loot, null), "战利品容器 ⇒ 让位（与 tick 同款）");

        // ③ level == null ⇒ 不给能力
        BlockEntity orphan = mock(BlockEntity.class);
        when(orphan.getBlockPos()).thenReturn(pos);
        when(orphan.getLevel()).thenReturn(null);
        assertNull(ContainerFluidHandler.resolveProvider(orphan, null), "无 level ⇒ null");

        // ④ 已有主人（模组机器自己的储液）⇒ 让位
        BlockEntity owned = mock(BlockEntity.class);
        Level ownedLevel = mockLevel(owned);
        when(owned.getBlockPos()).thenReturn(pos);
        when(owned.getLevel()).thenReturn(ownedLevel);
        doReturn(new ContainerFluidHandler(owned)).when(ownedLevel)
            .getCapability(eq(Capabilities.FluidHandler.BLOCK), any(), any());
        assertNull(ContainerFluidHandler.resolveProvider(owned, null), "已有主人 ⇒ 让位（不夺主）");
    }
}
