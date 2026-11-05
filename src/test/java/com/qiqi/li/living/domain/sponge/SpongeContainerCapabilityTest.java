package com.qiqi.li.living.domain.sponge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.domain.water.ContainerFluidData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
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
    @DisplayName("① 存在性判据：无海绵不暴露，干活海绵为空罐可灌")
    void exposureRequiresLivingSponge() {
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
        assertEquals(2_000_000L, totalMmB(scene.handler().getStackInSlot(1)), "罐0正式写回");
        assertEquals(3_000_000L, totalMmB(scene.handler().getStackInSlot(7)), "罐1不动");
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
        assertEquals(0L, totalMmB(scene.handler().getStackInSlot(1)), "首堆全部扣除");
        assertEquals(200_000L, totalMmB(scene.handler().getStackInSlot(7)), "次堆只扣剩余400mB");
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
        assertEquals(500_000L, totalMmB(scene.handler().getStackInSlot(1)), "首堆正式写回");
        // 首堆已定水 ⇒ 岩浆被拒（规则 5）；但第二罐还是空罐 ⇒ 合法收下（多罐储液标准语义）
        assertEquals(100, h.fill(new FluidStack(Fluids.LAVA, 100), FluidAction.EXECUTE));
        assertEquals(500_000L, totalMmB(scene.handler().getStackInSlot(1)), "水罐不被异种污染");
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

    @Test
    @DisplayName("指名抽取跨堆只用剩余请求，模拟不写且请求对象不变")
    void typedDrain_usesRemainingRequest() {
        Scene scene = Scene.of(9);
        ItemStack a = scene.spongeAt(1, 600_000L);
        ItemStack b = scene.spongeAt(7, 600_000L);
        var h = SpongeContainerFluidHandler.resolveProvider(beOf(scene), null);
        assertNotNull(h);
        FluidStack request = new FluidStack(Fluids.WATER, 1000);

        assertEquals(1000, h.drain(request, FluidAction.SIMULATE).getAmount());
        assertEquals(600_000L, totalMmB(a));
        assertEquals(600_000L, totalMmB(b));
        assertEquals(1000, h.drain(request, FluidAction.EXECUTE).getAmount());
        assertEquals(0, totalMmB(scene.handler().getStackInSlot(1)));
        assertEquals(200_000L, totalMmB(scene.handler().getStackInSlot(7)));
        assertEquals(1000, request.getAmount());
    }

    @Test
    @DisplayName("不指名抽取：异种不扣，继续抽后面的同种堆")
    void untypedDrain_skipsOtherFluidWithoutMutation() {
        Scene scene = Scene.of(9);
        ItemStack a = scene.spongeAt(1, 600_000L);
        ItemStack lava = scene.spongeAt(3);
        LivingSpongeData.set(lava, new LivingSpongeData(Fluids.LAVA.getFluidType(), 900_000L));
        ItemStack b = scene.spongeAt(7, 600_000L);
        var h = SpongeContainerFluidHandler.resolveProvider(beOf(scene), null);
        assertNotNull(h);

        assertEquals(1000, h.drain(1000, FluidAction.SIMULATE).getAmount());
        assertEquals(600_000L, totalMmB(a));
        assertEquals(900_000L, totalMmB(lava));
        assertEquals(600_000L, totalMmB(b));
        FluidStack out = h.drain(1000, FluidAction.EXECUTE);
        assertEquals(Fluids.WATER, out.getFluid());
        assertEquals(1000, out.getAmount());
        assertEquals(0, totalMmB(scene.handler().getStackInSlot(1)));
        assertEquals(900_000L, totalMmB(scene.handler().getStackInSlot(3)));
        assertEquals(200_000L, totalMmB(scene.handler().getStackInSlot(7)));
    }

    @Test
    @DisplayName("前导干罐与不足1mB的水不选定流体，由第一堆可抽岩浆选定")
    void untypedDrain_selectsFirstDrainableFluid() {
        Scene scene = Scene.of(9);
        ItemStack dry = scene.spongeAt(0);
        ItemStack dust = scene.spongeAt(1, 999L);
        ItemStack lava = scene.spongeAt(2);
        LivingSpongeData.set(lava, new LivingSpongeData(Fluids.LAVA.getFluidType(), 600_000L));
        ItemStack water = scene.spongeAt(3, 900_000L);
        var h = SpongeContainerFluidHandler.resolveProvider(beOf(scene), null);
        assertNotNull(h);

        FluidStack out = h.drain(1000, FluidAction.EXECUTE);
        assertEquals(Fluids.LAVA, out.getFluid());
        assertEquals(600, out.getAmount());
        assertEquals(0, totalMmB(scene.handler().getStackInSlot(0)));
        assertEquals(999L, totalMmB(scene.handler().getStackInSlot(1)));
        assertEquals(0, totalMmB(scene.handler().getStackInSlot(2)));
        assertEquals(900_000L, totalMmB(scene.handler().getStackInSlot(3)));
    }

    @Test
    @DisplayName("指名抽取库存不足只交付同种库存，异种和后续干罐不动")
    void typedDrain_limitsToMatchingStock() {
        Scene scene = Scene.of(9);
        ItemStack a = scene.spongeAt(0, 100_000L);
        ItemStack lava = scene.spongeAt(1);
        LivingSpongeData.set(lava, new LivingSpongeData(Fluids.LAVA.getFluidType(), 900_000L));
        ItemStack b = scene.spongeAt(2, 200_000L);
        scene.spongeAt(3);
        var h = SpongeContainerFluidHandler.resolveProvider(beOf(scene), null);
        assertNotNull(h);

        assertEquals(300, h.drain(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE).getAmount());
        assertEquals(0, totalMmB(scene.handler().getStackInSlot(0)));
        assertEquals(0, totalMmB(scene.handler().getStackInSlot(2)));
        assertEquals(900_000L, totalMmB(scene.handler().getStackInSlot(1)));
    }

    @Test
    @DisplayName("指名抽取返回实际存量的代表流体，不复制请求的流动身份")
    void typedDrain_returnsActualFluidPrototype() {
        Scene scene = Scene.of(9);
        scene.spongeAt(0, 2_000_000L);
        var h = SpongeContainerFluidHandler.resolveProvider(beOf(scene), null);
        assertNotNull(h);
        FluidStack request = new FluidStack(Fluids.FLOWING_WATER, 1000);

        FluidStack out = h.drain(request, FluidAction.EXECUTE);
        assertEquals(1000, out.getAmount());
        assertEquals(Fluids.WATER, out.getFluid());
        assertEquals(Fluids.FLOWING_WATER, request.getFluid());
    }

    @Test
    @DisplayName("无效请求与异种拒绝不改存量")
    void drain_invalidRequestsAreNoOp() {
        Scene scene = Scene.of(9);
        ItemStack a = scene.spongeAt(0, 2_000_000L);
        var h = SpongeContainerFluidHandler.resolveProvider(beOf(scene), null);
        assertNotNull(h);

        assertTrue(h.drain(0, FluidAction.EXECUTE).isEmpty());
        assertTrue(h.drain(-1, FluidAction.EXECUTE).isEmpty());
        assertTrue(h.drain(FluidStack.EMPTY, FluidAction.EXECUTE).isEmpty());
        assertTrue(h.drain(new FluidStack(Fluids.LAVA, 1000), FluidAction.EXECUTE).isEmpty());
        assertEquals(2_000_000L, totalMmB(a));
    }

    @Test
    @DisplayName("多枚海绵均分损耗只在实扣侧，交付精确不超请求")
    void typedDrain_roundingLossIsNotDelivery() {
        Scene scene = Scene.of(9);
        ItemStack a = scene.spongeAt(0, 10_000L);
        a.setCount(37);
        var h = SpongeContainerFluidHandler.resolveProvider(beOf(scene), null);
        assertNotNull(h);
        long before = totalMmB(a);

        assertEquals(3, h.drain(new FluidStack(Fluids.WATER, 3), FluidAction.SIMULATE).getAmount());
        assertEquals(before, totalMmB(a));
        assertEquals(3, h.drain(new FluidStack(Fluids.WATER, 3), FluidAction.EXECUTE).getAmount());
        assertEquals(3034L, before - totalMmB(scene.handler().getStackInSlot(0)));
    }

    private enum WriteBehavior { NORMAL, NO_OP, UNSUPPORTED, REJECT_SECOND }

    private static class CopyHandler extends FakeHandler {
        final boolean copyReads;
        final WriteBehavior behavior;
        int writes;
        int inserts;
        int extracts;
        int changes;

        CopyHandler(boolean copyReads, WriteBehavior behavior) {
            super(9);
            this.copyReads = copyReads;
            this.behavior = behavior;
        }

        ItemStack stored(int slot) { return super.getStackInSlot(slot); }
        @Override public ItemStack getStackInSlot(int slot) {
            ItemStack stack = stored(slot);
            return copyReads ? stack.copy() : stack;
        }
        @Override public void setStackInSlot(int slot, ItemStack stack) {
            writes++;
            if (behavior == WriteBehavior.UNSUPPORTED) throw new UnsupportedOperationException();
            if (behavior == WriteBehavior.NO_OP || behavior == WriteBehavior.REJECT_SECOND && slot == 1) return;
            super.setStackInSlot(slot, stack.copy());
            changes++;
        }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            inserts++;
            return stack;
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            extracts++;
            return ItemStack.EMPTY;
        }
    }

    private record CopyScene(CopyHandler inventory, BlockEntity be, Level level,
                             SpongeContainerFluidHandler fluid) {
        static CopyScene of(boolean copies, WriteBehavior behavior, long... quantities) {
            CopyHandler inventory = new CopyHandler(copies, behavior);
            for (int slot = 0; slot < quantities.length; slot++) {
                ItemStack stack = new ItemStack(Items.SPONGE);
                LivingItemManager.setLiving(stack, true);
                LivingSpongeData.set(stack, new LivingSpongeData(WATER, quantities[slot]));
                inventory.put(slot, stack);
            }
            Level level = org.mockito.Mockito.mock(Level.class);
            BlockEntity be = org.mockito.Mockito.mock(BlockEntity.class);
            org.mockito.Mockito.when(be.getLevel()).thenReturn(level);
            org.mockito.Mockito.when(be.getBlockPos()).thenReturn(BlockPos.ZERO);
            org.mockito.Mockito.when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(be);
            org.mockito.Mockito.when(level.getBlockState(BlockPos.ZERO))
                .thenReturn(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            org.mockito.Mockito.when(level.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, BlockPos.ZERO, null))
                .thenReturn(inventory);
            MinecraftServer server = org.mockito.Mockito.mock(MinecraftServer.class);
            PlayerList players = org.mockito.Mockito.mock(PlayerList.class);
            org.mockito.Mockito.when(level.getServer()).thenReturn(server);
            org.mockito.Mockito.when(server.getPlayerList()).thenReturn(players);
            org.mockito.Mockito.when(players.getPlayers()).thenReturn(List.of());
            var fluid = SpongeContainerFluidHandler.resolveProvider(be, null);
            assertNotNull(fluid);
            return new CopyScene(inventory, be, level, fluid);
        }
        long amount(int slot) { return totalMmB(inventory.stored(slot)); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("原引用与副本后端：灌装正式写回，模拟无写与通知")
    void fill_commitsToActualSlot(boolean copies) {
        CopyScene scene = CopyScene.of(copies, WriteBehavior.NORMAL, 0);
        assertEquals(500, scene.fluid.fill(new FluidStack(Fluids.WATER, 500), FluidAction.SIMULATE));
        assertEquals(0, scene.inventory.writes);
        assertEquals(0L, scene.amount(0));
        org.mockito.Mockito.verify(scene.be, org.mockito.Mockito.never()).setChanged();
        assertEquals(500, scene.fluid.fill(new FluidStack(Fluids.WATER, 500), FluidAction.EXECUTE));
        assertEquals(500_000L, scene.amount(0));
        assertEquals(1, scene.inventory.writes);
        assertEquals(1, scene.inventory.changes);
        org.mockito.Mockito.verify(scene.be).setChanged();
        org.mockito.Mockito.verify(scene.level, org.mockito.Mockito.never()).invalidateCapabilities(BlockPos.ZERO);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    @DisplayName("原引用与副本后端：两个抽取重载都实际扣库存")
    void drain_commitsToActualSlot(boolean copies, boolean typed) {
        CopyScene scene = CopyScene.of(copies, WriteBehavior.NORMAL, 2_000_000L);
        FluidStack request = new FluidStack(Fluids.WATER, 1000);
        FluidStack simulated = typed ? scene.fluid.drain(request, FluidAction.SIMULATE)
            : scene.fluid.drain(1000, FluidAction.SIMULATE);
        assertEquals(1000, simulated.getAmount());
        assertEquals(0, scene.inventory.writes);
        assertEquals(2_000_000L, scene.amount(0));
        FluidStack out = typed ? scene.fluid.drain(request, FluidAction.EXECUTE)
            : scene.fluid.drain(1000, FluidAction.EXECUTE);
        assertEquals(1000, out.getAmount());
        assertEquals(1_000_000L, scene.amount(0));
        assertEquals(1, scene.inventory.changes);
        assertEquals(1000, request.getAmount());
        org.mockito.Mockito.verify(scene.be).setChanged();
    }

    @ParameterizedTest
    @CsvSource({"NO_OP,fill", "NO_OP,typed", "NO_OP,int", "UNSUPPORTED,fill", "UNSUPPORTED,typed", "UNSUPPORTED,int"})
    @DisplayName("空setter与拒写后端：不虚报成功，不走抽插回退")
    void rejectedWrite_doesNotDeliver(WriteBehavior behavior, String operation) {
        CopyScene scene = CopyScene.of(true, behavior, "fill".equals(operation) ? 0L : 2_000_000L);
        long before = scene.amount(0);
        int reported = switch (operation) {
            case "fill" -> scene.fluid.fill(new FluidStack(Fluids.WATER, 500), FluidAction.EXECUTE);
            case "typed" -> scene.fluid.drain(new FluidStack(Fluids.WATER, 500), FluidAction.EXECUTE).getAmount();
            default -> scene.fluid.drain(500, FluidAction.EXECUTE).getAmount();
        };
        assertEquals(0, reported);
        assertEquals(before, scene.amount(0));
        assertEquals(1, scene.inventory.writes);
        assertEquals(0, scene.inventory.inserts);
        assertEquals(0, scene.inventory.extracts);
        org.mockito.Mockito.verify(scene.be, org.mockito.Mockito.never()).setChanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fill", "typed", "int"})
    @DisplayName("第二槽拒写：只结算先前确认的部分，后槽不动")
    void secondSlotRejected_returnsCommittedPart(String operation) {
        CopyScene scene = CopyScene.of(true, WriteBehavior.REJECT_SECOND,
            "fill".equals(operation) ? 63_900_000L : 600_000L,
            "fill".equals(operation) ? 0L : 600_000L);
        int reported = switch (operation) {
            case "fill" -> scene.fluid.fill(new FluidStack(Fluids.WATER, 500), FluidAction.EXECUTE);
            case "typed" -> scene.fluid.drain(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE).getAmount();
            default -> scene.fluid.drain(1000, FluidAction.EXECUTE).getAmount();
        };
        assertEquals("fill".equals(operation) ? 100 : 600, reported);
        assertEquals("fill".equals(operation) ? 64_000_000L : 0L, scene.amount(0));
        assertEquals("fill".equals(operation) ? 0L : 600_000L, scene.amount(1));
        assertEquals(2, scene.inventory.writes);
        assertEquals(1, scene.inventory.changes);
    }

    @Test
    @DisplayName("只读接口保留罐可见性，但模拟和执行均不抽插")
    void readonlyInventory_doesNotMutate() {
        CopyScene scene = CopyScene.of(true, WriteBehavior.NORMAL, 2_000_000L);
        net.neoforged.neoforge.items.IItemHandler readonly = new net.neoforged.neoforge.items.IItemHandler() {
            @Override public int getSlots() { return scene.inventory.getSlots(); }
            @Override public ItemStack getStackInSlot(int slot) { return scene.inventory.getStackInSlot(slot); }
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean sim) {
                return scene.inventory.insertItem(slot, stack, sim);
            }
            @Override public ItemStack extractItem(int slot, int amount, boolean sim) {
                return scene.inventory.extractItem(slot, amount, sim);
            }
            @Override public int getSlotLimit(int slot) { return 64; }
            @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }
        };
        org.mockito.Mockito.when(scene.level.getCapability(
            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, BlockPos.ZERO, null))
            .thenReturn(readonly);
        var fluid = SpongeContainerFluidHandler.resolveProvider(scene.be, null);
        assertNotNull(fluid);
        assertEquals(1, fluid.getTanks());
        assertEquals(2000, fluid.getFluidInTank(0).getAmount());
        for (FluidAction action : FluidAction.values()) {
            assertEquals(0, fluid.fill(new FluidStack(Fluids.WATER, 100), action));
            assertTrue(fluid.drain(100, action).isEmpty());
            assertTrue(fluid.drain(new FluidStack(Fluids.WATER, 100), action).isEmpty());
        }
        assertEquals(0, scene.inventory.writes);
        assertEquals(0, scene.inventory.inserts);
        assertEquals(0, scene.inventory.extracts);
        assertEquals(2_000_000L, scene.amount(0));
    }

    @Test
    @DisplayName("已有超大堆叠仅修改流体组件，不按较小slotLimit截断，保留无关组件")
    void oversizedSponge_componentCommitPreservesCountAndName() {
        CopyScene scene = CopyScene.of(true, WriteBehavior.NORMAL, 0L);
        ItemStack stored = scene.inventory.stored(0);
        stored.setCount(2048);
        stored.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
            net.minecraft.network.chat.Component.literal("storage"));
        assertEquals(500, scene.fluid.fill(new FluidStack(Fluids.WATER, 500), FluidAction.SIMULATE));
        assertEquals(500, scene.fluid.fill(new FluidStack(Fluids.WATER, 500), FluidAction.EXECUTE));
        ItemStack after = scene.inventory.stored(0);
        assertEquals(2048, after.getCount());
        assertEquals(net.minecraft.network.chat.Component.literal("storage"),
            after.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME));
        assertEquals(0, scene.inventory.inserts);
        assertEquals(0, scene.inventory.extracts);
        assertTrue(scene.amount(0) <= 500_000L);
        assertTrue(scene.amount(0) > 0);
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
        org.mockito.Mockito.when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(be);
        MinecraftServer server = org.mockito.Mockito.mock(MinecraftServer.class);
        PlayerList players = org.mockito.Mockito.mock(PlayerList.class);
        org.mockito.Mockito.when(level.getServer()).thenReturn(server);
        org.mockito.Mockito.when(server.getPlayerList()).thenReturn(players);
        org.mockito.Mockito.when(players.getPlayers()).thenReturn(List.of());
        return be;
    }
}
