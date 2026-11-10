package com.qiqi.li.living.domain.maid;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.compat.tlm.ModTlm;
import com.qiqi.li.living.compat.tlm.TlmSoulData;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.MoveResult;
import com.qiqi.li.living.container.TickContext;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 活魂符自适应走位：每秒按 1/I 抽签，成功加快、正常失败放慢。 */
public final class LivingSoulSlabFunction implements LivingItemFunction {
    public static final String ID = "living_soul_slab";
    private static final int TICKS_PER_SECOND = 20;
    private static final ResourceLocation SOUL_SLAB = ResourceLocation.fromNamespaceAndPath(ModTlm.MOD_ID, "smart_slab_has_maid");

    enum AttemptOutcome { SUCCESS, FAILURE, NEUTRAL }

    private final Predicate<ItemStack> itemPredicate;

    public LivingSoulSlabFunction() {
        this(stack -> ModTlm.isLoaded() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(SOUL_SLAB));
    }

    LivingSoulSlabFunction(Predicate<ItemStack> itemPredicate) {
        this.itemPredicate = itemPredicate;
    }

    @Override public boolean canApply(ItemStack stack) {
        return LivingItemManager.isLivingItem(stack) && itemPredicate.test(stack);
    }

    @Override public String getFunctionId() { return ID; }

    @Override public Set<DataComponentType<?>> getOwnedComponentTypes() {
        return Set.of(MaidComponents.CLOCK.get());
    }

    @Override public void tick(List<SlotEntry> entries, ContainerContext container, TickContext tick, Level level) {
        if (level.isClientSide) return;
        long now = level.getGameTime();
        for (SlotEntry entry : entries) {
            int source = entry.slotIndex();
            if (tick.movedSlots.contains(source) || !container.isValidSlot(source)) continue;
            ItemStack current = container.getItem(source);
            if (!canApply(current) || current.getCount() != 1 || !ItemStack.matches(current, entry.stack())) continue;
            if (ModTlm.isLoaded() && !TlmSoulData.hasStoredMaid(current)) {
                clearClock(container, source, current);
                continue;
            }

            LivingSoulSlabClock clock = current.get(MaidComponents.CLOCK.get());
            if (clock == null || !clock.valid()) {
                commitClock(container, source, current, LivingSoulSlabClock.initial());
                continue;
            }
            if (Math.floorMod(now, TICKS_PER_SECOND) != 0) continue;
            RandomSource random = level.getRandom();
            if (random.nextInt(clock.intervalSeconds()) != 0) continue;

            int direction = chooseDirection(random);
            int target = ContainerContext.resolveNeighbor(source, direction, container.getSize(), container.getWidth());
            AttemptOutcome precheck = precheck(container, source, target, current);
            if (precheck == AttemptOutcome.NEUTRAL) continue;
            if (precheck == AttemptOutcome.FAILURE) {
                adjustClock(container, source, false);
                continue;
            }

            MoveResult result = container.move(source, target, 1);
            if (result instanceof MoveResult.Success) {
                adjustClock(container, target, true);
            } else if (classify(result) == AttemptOutcome.FAILURE) {
                adjustClock(container, source, false);
            }
        }
    }

    static AttemptOutcome precheck(ContainerContext container, int source, int target, ItemStack stack) {
        if (!container.isValidSlot(source) || target < 0 || !container.isValidSlot(target)) return AttemptOutcome.FAILURE;
        if (!container.getItem(target).isEmpty()) return AttemptOutcome.FAILURE;
        if (container.simulateExtractItem(source, 1) < 1) return AttemptOutcome.FAILURE;
        if (container.simulateInsertItem(target, stack) < 1) return AttemptOutcome.FAILURE;
        return AttemptOutcome.SUCCESS;
    }

    static AttemptOutcome classify(MoveResult result) {
        if (result instanceof MoveResult.Success) return AttemptOutcome.SUCCESS;
        if (result instanceof MoveResult.Rejected rejected) {
            return rejected.reason() == MoveResult.Reason.SOURCE_EMPTY ? AttemptOutcome.NEUTRAL : AttemptOutcome.FAILURE;
        }
        return AttemptOutcome.NEUTRAL;
    }

    static int chooseDirection(RandomSource random) {
        return random.nextInt(4);
    }

    private static void clearClock(ContainerContext container, int slot, ItemStack stack) {
        if (!stack.has(MaidComponents.CLOCK.get())) return;
        ItemStack before = stack.copy();
        ItemStack after = before.copy();
        after.remove(MaidComponents.CLOCK.get());
        container.commitItemComponents(slot, before, after);
    }

    private static boolean commitClock(ContainerContext container, int slot, ItemStack stack,
                                       LivingSoulSlabClock clock) {
        ItemStack before = stack.copy();
        ItemStack after = before.copy();
        after.set(MaidComponents.CLOCK.get(), clock);
        return container.commitItemComponents(slot, before, after);
    }

    private static void adjustClock(ContainerContext container, int slot, boolean success) {
        if (!container.isValidSlot(slot)) return;
        ItemStack stack = container.getItem(slot);
        LivingSoulSlabClock clock = stack.get(MaidComponents.CLOCK.get());
        if (clock == null || !clock.valid()) return;
        commitClock(container, slot, stack, success ? clock.afterSuccess() : clock.afterFailure());
    }
}
