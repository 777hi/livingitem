package com.qiqi.li.living.domain.hopper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.farmland.FarmlandPlantComponent;
import com.qiqi.li.living.transfer.SlotAccessor;
import com.qiqi.li.living.transfer.SlotInteractions;

/**
 * 槽位交互分发的回归守卫 —— 两个入口各覆盖一遍：
 *
 * <ul>
 *   <li>{@link SlotInteractions#tryInteractFromNeighbor}（<b>拉取方向</b>，货物在邻居容器）</li>
 *   <li>{@link SlotInteractions#tryInteract}（<b>已知货物</b>：容器内管道 / 跨容器推送）</li>
 * </ul>
 *
 * <p><b>bug 现象（2026-09-15 实测）</b>：活漏斗输入槽（source）在邻居容器、输出槽
 * （target）在本容器且是活耕地时，骨粉不施肥；而反方向（同容器骨粉 → 跨容器活耕地）
 * 正常，把活耕地移走后骨粉又能正常跨容器传输。</p>
 *
 * <p><b>根因</b>：施肥方程硬编码在三处传输分支，拉取方向 {@code pullFromNeighbor}
 * 漏了分支，走通用 {@code tryPullFromNeighbor} + {@code SlotAccessor.transfer}——
 * 而 {@code SlotAccessorFactory.create} 对「非箱类活物品」直接返回 null，
 * 活耕地目标槽必然失败（target == null → false），骨粉永远送不进去也永远不施肥。
 * 结构性修复：方程收编为注册式 {@link SlotInteractions}（内置条目
 * {@code FarmlandBonemealInteraction}），三处调用点只调分发器。</p>
 *
 * <p>纯逻辑测试：只需内存 IItemHandler / 内存 SlotAccessor + mock ServerLevel
 * （未成熟分支不触碰 level，成熟冻结分支由 FertilizeTransferTest 与游戏实测覆盖）。</p>
 *
 * <p><b>货物准入（2026-09-15 用户定案）</b>：施肥是「漏斗用传输能力把骨粉送进活耕地」，
 * 属传输语义 → 受漏斗自己的货物规则约束：<b>活物品不作货物</b>。因此<b>活骨粉（活物品）
 * 不施肥</b>（四方向一致），活骨粉的手动用途在 GUI 右键（那里本就要求活化）。
 * 规则唯一定义点 {@code SlotInteractions.isEligibleCargo}，另有
 * {@code SlotInteractionCargoGateTest} 专测。</p>
 */
class CrossContainerTransferFertilizeTest {

    private static final ServerLevel LEVEL = Mockito.mock(ServerLevel.class);
    private static final BlockPos NEIGHBOR_POS = BlockPos.ZERO.east();

    /** 邻居容器（内存 IItemHandler） */
    private static class FakeHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;

        FakeHandler(ItemStack... initial) {
            this.slots = initial.clone();
        }

        @Override public void setStackInSlot(int slot, ItemStack stack) { slots[slot] = stack; }
        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) { return slots[slot]; }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            ItemStack current = slots[slot];
            if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, stack)) return stack;
            int space = 64 - current.getCount();
            int moved = Math.min(space, stack.getCount());
            if (!simulate) {
                slots[slot] = current.isEmpty()
                    ? stack.copyWithCount(moved)
                    : current.copyWithCount(current.getCount() + moved);
            }
            ItemStack rest = stack.copy();
            rest.shrink(moved);
            return rest;
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

    /** 源槽 Accessor（单槽内存实现）——用于驱动「已知货物」入口，校验模拟优先协议 */
    private static class FakeSource implements SlotAccessor {
        ItemStack stack;

        FakeSource(ItemStack stack) { this.stack = stack; }

        @Override public ItemStack extract(int amount, ItemStack filterType) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            int taken = Math.min(amount, stack.getCount());
            ItemStack result = stack.copyWithCount(taken);
            stack = stack.getCount() == taken ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - taken);
            return result;
        }

        @Override public ItemStack simulateExtract(int amount) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            return stack.copyWithCount(Math.min(amount, stack.getCount()));
        }

        @Override public int insert(ItemStack s) { return 0; }
        @Override public int simulateInsert(ItemStack s) { return 0; }
        @Override public void rollback(ItemStack s) { stack = s.copy(); }
        @Override public boolean isEmpty() { return stack.isEmpty(); }
        @Override public boolean isFull() { return false; }
        @Override public void markTransferred() { }
        @Override public void sync() { }
    }

    private static ItemStack livingFarmland(int age, int maxAge) {
        ItemStack stack = new ItemStack(Items.FARMLAND, 1);
        LivingItemManager.setLiving(stack, true);
        LivingItemManager.setFarmlandPlant(stack,
            new FarmlandPlantComponent(Items.WHEAT_SEEDS, age, maxAge, 0L, -1, List.of()));
        return stack;
    }

    private static ItemStack frozenMatureFarmland() {
        ItemStack stack = new ItemStack(Items.FARMLAND, 1);
        LivingItemManager.setLiving(stack, true);
        LivingItemManager.setFarmlandPlant(stack, new FarmlandPlantComponent(
            Items.WHEAT_SEEDS, 7, 7, 0L, 0, List.of(new ItemStack(Items.WHEAT))));
        return stack;
    }

    // ==================== 拉取方向（货物在邻居容器） ====================

    @Test
    @DisplayName("拉取方向：邻居骨粉 → 本容器未成熟活耕地 = 施肥生效、age+1、邻居粉 -1")
    void pull_fertilizesAndConsumesNeighborBonemeal() {
        FakeHandler neighbor = new FakeHandler(ItemStack.EMPTY, new ItemStack(Items.BONE_MEAL, 16));
        ItemStack farmland = livingFarmland(3, 7);

        boolean ok = SlotInteractions.tryInteractFromNeighbor(
            neighbor, NEIGHBOR_POS, farmland, LEVEL, null);

        assertTrue(ok, "拉取方向必须能施肥（修复前恒 false）");
        assertEquals(4, LivingItemManager.getFarmlandPlant(farmland).age());
        assertTrue(neighbor.getStackInSlot(0).isEmpty(), "空槽位保持为空");
        assertEquals(15, neighbor.getStackInSlot(1).getCount(), "施肥消耗邻居容器 1 个骨粉");
    }

    @Test
    @DisplayName("拉取方向：空槽位跳过，命中后面的骨粉堆（不因首槽空而放弃）")
    void pull_skipsEmptySlotsToFindBonemeal() {
        FakeHandler neighbor = new FakeHandler(
            ItemStack.EMPTY, new ItemStack(Items.WHEAT, 3), new ItemStack(Items.BONE_MEAL, 2));
        ItemStack farmland = livingFarmland(0, 7);

        assertTrue(SlotInteractions.tryInteractFromNeighbor(neighbor, NEIGHBOR_POS, farmland, LEVEL, null));
        assertEquals(1, LivingItemManager.getFarmlandPlant(farmland).age());
        assertEquals(1, neighbor.getStackInSlot(2).getCount());
        assertEquals(3, neighbor.getStackInSlot(1).getCount(), "非骨粉货物不动");
    }

    @Test
    @DisplayName("拉取方向：邻居全是非骨粉 → false、耕地不变、货物不动")
    void pull_noBonemealInNeighbor_noOp() {
        FakeHandler neighbor = new FakeHandler(new ItemStack(Items.WHEAT, 16));
        ItemStack farmland = livingFarmland(3, 7);

        assertFalse(SlotInteractions.tryInteractFromNeighbor(neighbor, NEIGHBOR_POS, farmland, LEVEL, null));
        assertEquals(3, LivingItemManager.getFarmlandPlant(farmland).age());
        assertEquals(16, neighbor.getStackInSlot(0).getCount());
    }

    @Test
    @DisplayName("拉取方向：已冻结成熟耕地 → equals 零空转，邻居骨粉一滴不烧")
    void pull_frozenFarmland_doesNotBurnBonemeal() {
        FakeHandler neighbor = new FakeHandler(new ItemStack(Items.BONE_MEAL, 16));
        ItemStack frozen = frozenMatureFarmland();

        assertFalse(SlotInteractions.tryInteractFromNeighbor(neighbor, NEIGHBOR_POS, frozen, LEVEL, null));
        assertEquals(16, neighbor.getStackInSlot(0).getCount(), "等待输出的耕地不烧骨粉");
    }

    @Test
    @DisplayName("拉取方向：非活耕地 / 未种植活耕地 → false 不消耗")
    void pull_nonLivingOrUnplantedFarmland_rejected() {
        FakeHandler neighbor = new FakeHandler(new ItemStack(Items.BONE_MEAL, 16));

        ItemStack plainFarmland = new ItemStack(Items.FARMLAND, 1);   // 无 IS_LIVING
        assertFalse(SlotInteractions.tryInteractFromNeighbor(neighbor, NEIGHBOR_POS, plainFarmland, LEVEL, null));

        ItemStack unplanted = new ItemStack(Items.FARMLAND, 1);
        LivingItemManager.setLiving(unplanted, true);                // 无 FARMLAND_PLANT
        assertFalse(SlotInteractions.tryInteractFromNeighbor(neighbor, NEIGHBOR_POS, unplanted, LEVEL, null));

        assertEquals(16, neighbor.getStackInSlot(0).getCount(), "两种拒绝路径都不消耗骨粉");
    }

    // ==================== 已知货物入口（容器内管道 / 跨容器推送） ====================

    @Test
    @DisplayName("已知货物入口：骨粉 + 未成熟活耕地 → 生效并真扣 1 粉（模拟优先协议）")
    void knownCargo_fertilizesAndConsumes() {
        FakeSource source = new FakeSource(new ItemStack(Items.BONE_MEAL, 16));
        ItemStack farmland = livingFarmland(3, 7);

        assertTrue(SlotInteractions.tryInteract(source, source.stack, farmland, LEVEL));
        assertEquals(4, LivingItemManager.getFarmlandPlant(farmland).age());
        assertEquals(15, source.stack.getCount(), "生效后真扣 1 粉");
    }

    @Test
    @DisplayName("已知货物入口：已冻结成熟耕地 → 不生效且不真扣（模拟优先：未生效绝不扣货）")
    void knownCargo_frozenFarmland_doesNotConsume() {
        FakeSource source = new FakeSource(new ItemStack(Items.BONE_MEAL, 16));
        ItemStack frozen = frozenMatureFarmland();

        assertFalse(SlotInteractions.tryInteract(source, source.stack, frozen, LEVEL));
        assertEquals(16, source.stack.getCount(), "交互未生效 → 源槽数量分毫不动");
    }

    @Test
    @DisplayName("已知货物入口：目标槽为空 / 货物不匹配 → 不接管（通用插入照旧）")
    void knownCargo_noMatchOrEmptyTarget_returnsFalse() {
        FakeSource wheat = new FakeSource(new ItemStack(Items.WHEAT, 16));
        assertFalse(SlotInteractions.tryInteract(wheat, wheat.stack, livingFarmland(3, 7), LEVEL),
            "非骨粉不接管");

        FakeSource bonemeal = new FakeSource(new ItemStack(Items.BONE_MEAL, 16));
        assertFalse(SlotInteractions.tryInteract(bonemeal, bonemeal.stack, ItemStack.EMPTY, LEVEL),
            "空目标槽不接管");
        assertEquals(16, bonemeal.stack.getCount());
    }

    // ==================== 廉价筛选谓词（分配前的守卫） ====================

    @Test
    @DisplayName("canInteract：只对「骨粉 × 活耕地」返回 true（纯谓词，不分配不扣货）")
    void canInteract_onlyMatchesFertilizePair() {
        ItemStack farmland = livingFarmland(3, 7);

        assertTrue(SlotInteractions.canInteract(new ItemStack(Items.BONE_MEAL, 1), farmland));
        assertFalse(SlotInteractions.canInteract(new ItemStack(Items.WHEAT, 1), farmland),
            "非骨粉货物不匹配");
        assertFalse(SlotInteractions.canInteract(new ItemStack(Items.BONE_MEAL, 1),
            new ItemStack(Items.FARMLAND, 1)), "非活耕地不匹配（无 IS_LIVING）");
        assertFalse(SlotInteractions.canInteract(new ItemStack(Items.BONE_MEAL, 1), ItemStack.EMPTY),
            "空目标槽不匹配");
        assertFalse(SlotInteractions.canInteract(ItemStack.EMPTY, farmland), "空货物不匹配");
        assertEquals(3, LivingItemManager.getFarmlandPlant(farmland).age(), "纯谓词不改状态");
    }

    // ==================== 推送方向（邻居槽作目标）+ 活物品货物 ====================

    private static ItemStack livingItem(net.minecraft.world.item.Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    @Test
    @DisplayName("推送方向：活骨粉不是合法货物 → 不施肥、不入槽、不扣货（漏斗只认普通骨粉）")
    void push_livingBonemealIsNotEligibleCargo() {
        FakeHandler neighbor = new FakeHandler(livingFarmland(3, 7));
        FakeSource source = new FakeSource(livingItem(Items.BONE_MEAL, 16));

        assertFalse(CrossContainerTransfer.tryPushToNeighbor(
            neighbor, NEIGHBOR_POS, LEVEL, source, 1, source.stack),
            "施肥属传输语义 → 受漏斗货物规则约束（活物品不作货物）");
        assertEquals(3, LivingItemManager.getFarmlandPlant(neighbor.getStackInSlot(0)).age(),
            "耕地未被催熟");
        assertEquals(16, source.stack.getCount(), "活骨粉一滴不烧");
    }

    @Test
    @DisplayName("推送方向：普通骨粉 → 邻居空槽 = 照旧普通插入（无回归）")
    void push_plainBonemealStillInsertsNormally() {
        FakeHandler neighbor = new FakeHandler(ItemStack.EMPTY);
        FakeSource source = new FakeSource(new ItemStack(Items.BONE_MEAL, 16));

        assertTrue(CrossContainerTransfer.tryPushToNeighbor(
            neighbor, NEIGHBOR_POS, LEVEL, source, 8, source.stack));
        assertEquals(8, neighbor.getStackInSlot(0).getCount(), "普通货物照常入槽");
        assertEquals(8, source.stack.getCount());
    }

    @Test
    @DisplayName("隔离红线：活骨粉货物 → 邻居空槽 = 拒绝且不入槽（活物品不被当普通货物推送）")
    void push_livingBonemealNeverInsertsIntoEmptyNeighborSlot() {
        FakeHandler neighbor = new FakeHandler(ItemStack.EMPTY);
        FakeSource source = new FakeSource(livingItem(Items.BONE_MEAL, 16));

        assertFalse(CrossContainerTransfer.tryPushToNeighbor(
            neighbor, NEIGHBOR_POS, LEVEL, source, 8, source.stack), "无交互目标 → 拒绝");
        assertTrue(neighbor.getStackInSlot(0).isEmpty(), "活物品绝不作为普通货物入槽");
        assertEquals(16, source.stack.getCount(), "拒绝路径不扣货");
    }

    @Test
    @DisplayName("隔离红线：活骨粉货物 → 邻居已有普通骨粉堆 = 拒绝且不合并（不因同种而放行）")
    void push_livingBonemealNeverMergesIntoNeighborStack() {
        FakeHandler neighbor = new FakeHandler(new ItemStack(Items.BONE_MEAL, 4));
        FakeSource source = new FakeSource(livingItem(Items.BONE_MEAL, 16));

        assertFalse(CrossContainerTransfer.tryPushToNeighbor(
            neighbor, NEIGHBOR_POS, LEVEL, source, 8, source.stack));
        assertEquals(4, neighbor.getStackInSlot(0).getCount(), "同种普通堆也不接受活物品货物");
        assertEquals(16, source.stack.getCount());
    }

    @Test
    @DisplayName("隔离红线：无交互匹配的活物品货物（活熔炉）→ 任何邻居槽都拒绝")
    void push_livingNonInteractableCargoRejected() {
        FakeHandler neighbor = new FakeHandler(ItemStack.EMPTY, livingFarmland(3, 7));
        FakeSource source = new FakeSource(livingItem(Items.FURNACE, 1));

        assertFalse(CrossContainerTransfer.tryPushToNeighbor(
            neighbor, NEIGHBOR_POS, LEVEL, source, 1, source.stack));
        assertTrue(neighbor.getStackInSlot(0).isEmpty(), "空槽不入");
        assertEquals(3, LivingItemManager.getFarmlandPlant(neighbor.getStackInSlot(1)).age(),
            "非骨粉活物品不会误触施肥");
        assertEquals(1, source.stack.getCount());
    }

    // ==================== 货物准入：活骨粉不是合法货物（用户定案 2026-09-15） ====================

    @Test
    @DisplayName("准入：活骨粉在三个入口都被拒（已知货物 / 拉取 / 推送）")
    void livingBonemealRejectedAtEveryEntry() {
        ItemStack farmland = livingFarmland(3, 7);
        ItemStack livingBonemeal = livingItem(Items.BONE_MEAL, 16);

        // 已知货物入口（容器内管道 / 推送）
        FakeSource source = new FakeSource(livingBonemeal);
        assertFalse(SlotInteractions.tryInteract(source, livingBonemeal, farmland, LEVEL));
        assertEquals(16, source.stack.getCount(), "不扣货");

        // 拉取入口（邻居容器里的活骨粉）
        FakeHandler neighbor = new FakeHandler(livingBonemeal.copy());
        assertFalse(SlotInteractions.tryInteractFromNeighbor(
            neighbor, NEIGHBOR_POS, farmland, LEVEL, null));
        assertEquals(16, neighbor.getStackInSlot(0).getCount(), "邻居活骨粉一滴不烧");

        // 推送入口（本容器活骨粉 → 邻居活耕地）
        FakeHandler target = new FakeHandler(livingFarmland(3, 7));
        FakeSource pushSource = new FakeSource(livingItem(Items.BONE_MEAL, 16));
        assertFalse(CrossContainerTransfer.tryPushToNeighbor(
            target, NEIGHBOR_POS, LEVEL, pushSource, 1, pushSource.stack));

        assertEquals(3, LivingItemManager.getFarmlandPlant(farmland).age(), "三处都没催熟");
    }
}
