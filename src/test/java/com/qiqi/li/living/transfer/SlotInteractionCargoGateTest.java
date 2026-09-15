package com.qiqi.li.living.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.farmland.FarmlandPlantComponent;
import com.qiqi.li.testutil.FakeContainerContext;

/**
 * 货物准入规则回归守卫（2026-09-15 用户定案：漏斗只认普通骨粉）。
 *
 * <p><b>语义</b>：施肥是「活漏斗用<b>传输能力</b>把骨粉送进活耕地」——属传输语义，
 * 因此必须受漏斗自己的货物规则约束：<b>活物品不作货物</b>（活箱子/活末影箱除外）。
 * 活骨粉是活物品 ⇒ 不是合法货物 ⇒ 漏斗不给它施肥；活骨粉的手动用途在 GUI 右键
 * （那里本就要求活化）。</p>
 *
 * <p><b>唯一定义点</b>：{@link SlotInteractions#isEligibleCargo}——传输层
 * （{@code TransferPipeline.isTransferableSource}）与交互层（两个入口）共用，
 * 避免隔离规则两处漂移。本测试同时钉住：工厂对活物品返回 null（规则底层依据）、
 * 准入谓词的真值表、以及端到端「活骨粉不施肥 / 普通骨粉照常施肥」。</p>
 */
class SlotInteractionCargoGateTest {

    private static final ServerLevel LEVEL = Mockito.mock(ServerLevel.class);
    private static final MinecraftServer SERVER = Mockito.mock(MinecraftServer.class);
    private static final BlockPos POS = BlockPos.ZERO;

    /** 内存 IItemHandler（邻居槽访问器用，不查活物品） */
    private static class FakeHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;

        FakeHandler(ItemStack... initial) { this.slots = initial.clone(); }

        @Override public void setStackInSlot(int slot, ItemStack stack) { slots[slot] = stack; }
        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) { return slots[slot]; }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;   // 本测试只用提取路径，插入恒失败
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack current = slots[slot];
            if (current.isEmpty()) return ItemStack.EMPTY;
            int taken = Math.min(amount, current.getCount());
            ItemStack result = current.copyWithCount(taken);
            if (!simulate) {
                slots[slot] = current.getCount() == taken
                    ? ItemStack.EMPTY : current.copyWithCount(current.getCount() - taken);
            }
            return result;
        }
    }

    private static ItemStack living(ItemStack stack) {
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static ItemStack livingFarmland(int age, int maxAge) {
        ItemStack stack = living(new ItemStack(Items.FARMLAND, 1));
        LivingItemManager.setFarmlandPlant(stack,
            new FarmlandPlantComponent(Items.WHEAT_SEEDS, age, maxAge, 0L, -1, List.of()));
        return stack;
    }

    private static SlotAccessor neighborAccessor(ItemStack cargo) {
        return SlotAccessorFactory.createForNeighbor(new FakeHandler(cargo), 0, null, LEVEL, POS);
    }

    @Test
    @DisplayName("准入真值表：普通物品与活箱子/活末影箱合法；活骨粉、活熔炉非法")
    void eligibleCargo_truthTable() {
        assertTrue(SlotInteractions.isEligibleCargo(new ItemStack(Items.BONE_MEAL, 1)), "普通骨粉合法");
        assertTrue(SlotInteractions.isEligibleCargo(new ItemStack(Items.WHEAT, 1)), "普通物品合法");
        assertTrue(SlotInteractions.isEligibleCargo(living(new ItemStack(Items.CHEST, 1))),
            "活箱子是存储容器，本身可被搬运 → 合法");
        assertTrue(SlotInteractions.isEligibleCargo(living(new ItemStack(Items.ENDER_CHEST, 1))),
            "活末影箱同理");
        assertFalse(SlotInteractions.isEligibleCargo(living(new ItemStack(Items.BONE_MEAL, 1))),
            "活骨粉是活物品 → 非法货物");
        assertFalse(SlotInteractions.isEligibleCargo(living(new ItemStack(Items.FURNACE, 1))),
            "活熔炉非法");
    }

    @Test
    @DisplayName("规则底层依据：工厂对非箱类活物品返回 null（准入与访问器工厂同口径）")
    void factory_rejectsLivingNonStorageItem() {
        FakeContainerContext ctx = new FakeContainerContext(4, 2);
        ctx.set(0, living(new ItemStack(Items.BONE_MEAL, 16)));

        assertNull(SlotAccessorFactory.create(SERVER, ctx, 0, null, null, null),
            "活物品隔离：工厂对非箱类活物品返回 null");
    }

    @Test
    @DisplayName("核心：活骨粉 + 活耕地 → 交互不接管、不扣货（漏斗只认普通骨粉）")
    void gate_livingBonemealDoesNotFertilize() {
        ItemStack farmland = livingFarmland(3, 7);
        ItemStack livingBonemeal = living(new ItemStack(Items.BONE_MEAL, 16));
        SlotAccessor source = neighborAccessor(livingBonemeal);

        assertFalse(SlotInteractions.canInteract(livingBonemeal, farmland), "准入谓词即拒");
        assertFalse(SlotInteractions.tryInteract(source, livingBonemeal, farmland, LEVEL),
            "活骨粉不是合法货物 → 不施肥");
        assertEquals(3, LivingItemManager.getFarmlandPlant(farmland).age(), "耕地未被催熟");
        assertEquals(16, source.simulateExtract(16).getCount(), "一滴活骨粉都没烧（源槽未动）");
    }

    @Test
    @DisplayName("端到端：普通骨粉 + 活耕地 → 照常施肥、扣 1（基础路径无回归）")
    void gate_plainBonemealFertilizes() {
        ItemStack farmland = livingFarmland(3, 7);
        ItemStack bonemeal = new ItemStack(Items.BONE_MEAL, 16);
        SlotAccessor source = neighborAccessor(bonemeal);

        assertTrue(SlotInteractions.canInteract(bonemeal, farmland));
        assertTrue(SlotInteractions.tryInteract(source, bonemeal, farmland, LEVEL));
        assertEquals(4, LivingItemManager.getFarmlandPlant(farmland).age());
        assertEquals(15, source.simulateExtract(16).getCount(), "生效后真扣 1 粉");
    }

    @Test
    @DisplayName("端到端：容器内路径同样只认普通骨粉（活骨粉不施肥、不扣货）")
    void containerInternal_livingBonemealRejected() {
        FakeContainerContext ctx = new FakeContainerContext(4, 2);
        ctx.set(0, living(new ItemStack(Items.BONE_MEAL, 16)));
        ctx.set(1, livingFarmland(3, 7));

        assertFalse(SlotInteractions.canInteract(ctx.getItem(0), ctx.getItem(1)));
        assertEquals(16, ctx.getItem(0).getCount(), "活骨粉原封不动");
        assertEquals(3, LivingItemManager.getFarmlandPlant(ctx.getItem(1)).age());
    }
}
