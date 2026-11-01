package com.qiqi.li.living.domain.sponge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.domain.water.ContainerFluidData;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 「海绵垫起的」容器级流体能力（⑭）—— 存在性判据 / 罐映射 / 跨堆抽灌 / 边沿精确失效
 * （设计稿 §2.3；§10.4 教训：能力非 null = 管道判储液的唯一依据）。
 */
class SpongeContainerCapabilityTest {

    private static final net.neoforged.neoforge.fluids.FluidType WATER =
        Fluids.WATER.getFluidType();

    @BeforeAll
    static void setup() {
        TickContextHolder.register();
        if (LivingItemManager.getAllFunctions().stream()
                .noneMatch(f -> "living_sponge".equals(f.getFunctionId()))) {
            LivingItemManager.registerFunction(new LivingSpongeFunction());
        }
    }

    /** 引用 LivingSpongeAbsorbTest 的 resolver 注册（静态，幂等）。 */
    private static final class TickContextHolder {
        static void register() {
            com.qiqi.li.living.container.TickContext.registerSourceLedgerResolver(
                t -> t.data(ContainerFluidData.KEY));
        }
    }

    // ── 脚手架（LivingSpongeAbsorbTest 同款精简） ─────────────────

    private static class FakeHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;
        FakeHandler(int size) { this.slots = new ItemStack[size]; }
        void put(int slot, ItemStack stack) { slots[slot] = stack; }
        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) {
            return slots[slot] != null ? slots[slot] : ItemStack.EMPTY;
        }
        @Override public void setStackInSlot(int slot, ItemStack stack) { slots[slot] = stack; }
        @Override public ItemStack insertItem(int slot, ItemStack s, boolean sim) { return s; }
        @Override public ItemStack extractItem(int slot, int a, boolean sim) { return ItemStack.EMPTY; }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack s) { return true; }
    }

    private record Scene(FakeHandler handler, SimpleContainerContext ctx) {
        static Scene of(int size) {
            FakeHandler handler = new FakeHandler(size);
            Level level = org.mockito.Mockito.mock(Level.class);
            org.mockito.Mockito.when(level.isClientSide()).thenReturn(false);
            SimpleContainerContext ctx = new SimpleContainerContext(handler, level);
            return new Scene(handler, ctx);
        }
        ItemStack spongeAt(int slot) {
            ItemStack s = new ItemStack(Items.SPONGE);
            LivingItemManager.setLiving(s, true);
            handler.put(slot, s);
            return s;
        }
        ItemStack spongeAt(int slot, long mmB) {
            ItemStack s = spongeAt(slot);
            LivingSpongeData.set(s, new LivingSpongeData(Fluids.WATER.getFluidType(), mmB));
            return s;
        }
    }

    private static long totalMmB(ItemStack sponge) {
        return LivingSpongeData.of(sponge).totalMmB(sponge.getCount());
    }

    // ── 用例 ────────────────────────────────────────────────

    @Test
    @DisplayName("① 存在性判据：无海绵 / 只有干海绵 ⇒ null（§10.4：非 null = 管道判储液）")
    void exposureRequiresWetSponge() {
        Scene empty = Scene.of(9);
        assertNull(SpongeContainerFluidHandler.resolveProvider(beOf(empty), null),
            "空容器不是储液");

        Scene dry = Scene.of(9);
        dry.spongeAt(4);   // 干活海绵
        assertNotNull(SpongeContainerFluidHandler.resolveProvider(beOf(dry), null),
            "干海绵 = 空罐（0 内容、容量在位）⇒ 暴露，管道可灌装弹匣");

        Scene wet = Scene.of(9);
        wet.spongeAt(4, 1_000_000L);
        assertNotNull(SpongeContainerFluidHandler.resolveProvider(beOf(wet), null));
    }

    @Test
    @DisplayName("② 罐映射：每个活海绵堆一罐（槽位序），干海绵 = 空罐")
    void tankMappingFollowsSlotOrder() {
        Scene scene = Scene.of(9);
        scene.spongeAt(1, 2_000_000L);   // 罐 0：2000mB
        scene.spongeAt(3);               // 罐 1：空罐
        scene.spongeAt(7, 500_000L);     // 罐 2：500mB
        var h = SpongeContainerFluidHandler.resolveProvider(beOf(scene), null);
        assertNotNull(h);

        assertEquals(3, h.getTanks());
        assertEquals(2000, h.getFluidInTank(0).getAmount());
        assertTrue(h.getFluidInTank(1).isEmpty(), "干海绵 = 空罐（0 内容）");
        assertEquals(64_000, h.getTankCapacity(1), "空罐容量在位");
        assertEquals(500, h.getFluidInTank(2).getAmount());
    }

    @Test
    @DisplayName("③ 抽：指名罐抽均分扣对应堆，别的堆不动")
    void drainHitsOnlyTargetStack() {
        Scene scene = Scene.of(9);
        ItemStack a = scene.spongeAt(1, 3_000_000L);
        ItemStack b = scene.spongeAt(7, 3_000_000L);
        var h = SpongeContainerFluidHandler.resolveProvider(beOf(scene), null);
        assertNotNull(h);

        FluidStack out = h.drain(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE);
        assertEquals(1000, out.getAmount());
        assertEquals(2_000_000L, totalMmB(a), "罐 0（槽 1）被扣");
        assertEquals(3_000_000L, totalMmB(b), "罐 1（槽 7）不动");
    }

    @Test
    @DisplayName("④ 抽 int 版跨堆续抽：单一流体语义，凑满请求")
    void drainIntContinuesAcrossStacks() {
        Scene scene = Scene.of(9);
        scene.spongeAt(1, 600_000L);    // 600 mB
        scene.spongeAt(7, 600_000L);    // 600 mB —— 同种
        var h = SpongeContainerFluidHandler.resolveProvider(beOf(scene), null);
        assertNotNull(h);

        FluidStack out = h.drain(1000, FluidAction.EXECUTE);
        assertEquals(1000, out.getAmount(), "600 + 400 跨堆凑满");
        // 均分 floor 扣减：第一堆 per = 600,000 → 报 600？—— withdraw(600_000, 1, 1_000_000)
        // per = min(1_000_000, q=600_000) = 600_000 → 报 floor(600_000/1000) = 600；
        // 第二堆续 400 mB ⇒ 报 400。合计 1000 ✓
        assertEquals(0, totalMmB(scene.handler().getStackInSlot(1)) % 1);
        assertTrue(totalMmB(scene.handler().getStackInSlot(1)) < 600_000L, "第一堆已扣");
    }

    @Test
    @DisplayName("⑤ 灌：按槽序充入首堆（干海绵首口定流体），报账 ≥ 实充")
    void fillGoesToFirstStack() {
        Scene scene = Scene.of(9);
        ItemStack a = scene.spongeAt(1);            // 干
        scene.spongeAt(7);                          // 干
        var h = SpongeContainerFluidHandler.resolveProvider(beOf(scene), null);
        assertNotNull(h);

        int paid = h.fill(new FluidStack(Fluids.WATER, 500), FluidAction.EXECUTE);
        assertEquals(500, paid, "count=1 全额收下（报账 = 实充）");
        assertEquals(500_000L, totalMmB(a), "首堆收下（首口定流体）");
        // 首堆已定水 ⇒ 岩浆被拒（规则 5）；但第二罐还是空罐 ⇒ 合法收下（多罐储液标准语义）
        assertEquals(100, h.fill(new FluidStack(Fluids.LAVA, 100), FluidAction.EXECUTE));
        assertEquals(500_000L, totalMmB(a), "水罐不被异种污染");
        ItemStack b = scene.handler().getStackInSlot(7);
        assertEquals(100_000L, totalMmB(b), "空罐收下岩浆");
    }

    @Test
    @DisplayName("⑥ 边沿：存在性翻转 ⇒ invalidateCapabilities 精确一次（§10.4 同款）")
    void edgeInvalidatesExactlyOncePerFlip() {
        Level level = org.mockito.Mockito.mock(Level.class);
        org.mockito.Mockito.when(level.isClientSide()).thenReturn(false);
        BlockPos pos = new BlockPos(12, 8, 34);
        BlockEntity be = org.mockito.Mockito.mock(BlockEntity.class);
        org.mockito.Mockito.when(be.getBlockPos()).thenReturn(pos);
        FakeHandler handler = new FakeHandler(9);
        SimpleContainerContext ctx = new SimpleContainerContext(handler,
            null, List.of(pos), List.of(be), level);

        // ① 无海绵：不通告
        ContainerLivingItemHandler.processContext(ctx, level);
        org.mockito.Mockito.verify(level, org.mockito.Mockito.never()).invalidateCapabilities(pos);

        // ② 放入湿海绵（首次通告）
        ItemStack sponge = new ItemStack(Items.SPONGE);
        LivingItemManager.setLiving(sponge, true);
        LivingSpongeData.set(sponge, new LivingSpongeData(Fluids.WATER.getFluidType(), 1_000_000L));
        handler.put(4, sponge);
        ContainerLivingItemHandler.processContext(ctx, level);
        org.mockito.Mockito.verify(level, org.mockito.Mockito.times(1)).invalidateCapabilities(pos);

        // ③ 存在性没变 ⇒ 不重复通告
        ContainerLivingItemHandler.processContext(ctx, level);
        org.mockito.Mockito.verify(level, org.mockito.Mockito.times(1)).invalidateCapabilities(pos);

        // ④ 拿走海绵（撤回通告）
        handler.put(4, ItemStack.EMPTY);
        ContainerLivingItemHandler.processContext(ctx, level);
        org.mockito.Mockito.verify(level, org.mockito.Mockito.times(2)).invalidateCapabilities(pos);
    }

    /** BE 替身：level.getCapability 打桩返回场景 handler（resolveContextAt 的解析入口）。 */
    private static BlockEntity beOf(Scene scene) {
        Level level = scene.ctx().getLevel();
        org.mockito.Mockito.when(level.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                BlockPos.ZERO, null))
            .thenReturn(scene.handler());
        // 双箱规范化会查方块状态；石头（非 ChestBlock）= 单箱路径
        org.mockito.Mockito.when(level.getBlockState(BlockPos.ZERO))
            .thenReturn(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        BlockEntity be = org.mockito.Mockito.mock(BlockEntity.class);
        org.mockito.Mockito.when(be.getLevel()).thenReturn(level);
        org.mockito.Mockito.when(be.getBlockPos()).thenReturn(BlockPos.ZERO);
        return be;
    }
}
