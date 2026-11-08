package com.qiqi.li.living.domain.maid;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.compat.tlm.ModTlm;
import com.qiqi.li.living.compat.tlm.TlmSoulData;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 活魂符均匀迈步：所存背包C分钟内安排所存饱食度H次机会，仅方向随机。 */
public final class LivingSoulSlabFunction implements LivingItemFunction {
    public static final String ID = "living_soul_slab";
    private static final ResourceLocation SOUL_SLAB = ResourceLocation.fromNamespaceAndPath(ModTlm.MOD_ID, "smart_slab_has_maid");

    record WalkInputs(double hunger, int capacity) {
        boolean valid() { return Double.isFinite(hunger) && hunger >= 0 && capacity > 0; }
    }

    private final Predicate<ItemStack> itemPredicate;
    private final Function<ItemStack, Optional<WalkInputs>> inputReader;

    public LivingSoulSlabFunction() {
        this(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(SOUL_SLAB), stack -> {
            if (!ModTlm.isLoaded()) return Optional.empty();
            return TlmSoulData.readInputs(stack).map(input -> new WalkInputs(input.hunger(), input.capacity()));
        });
    }

    LivingSoulSlabFunction(Predicate<ItemStack> itemPredicate,
                          Function<ItemStack, Optional<WalkInputs>> inputReader) {
        this.itemPredicate = itemPredicate;
        this.inputReader = inputReader;
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
        ResourceLocation dimension = level.dimension().location();
        for (SlotEntry entry : entries) {
            int source = entry.slotIndex();
            if (tick.movedSlots.contains(source) || !container.isValidSlot(source)) continue;
            ItemStack current = container.getItem(source);
            if (!canApply(current) || current.getCount() != 1 || !ItemStack.matches(current, entry.stack())) continue;
            Optional<WalkInputs> input = inputReader.apply(current);
            if (input.isEmpty() || !input.get().valid() || input.get().hunger() <= 0) {
                clearClock(container, source, current);
                continue;
            }
            WalkInputs values = input.get();
            LivingSoulSlabClock clock = current.get(MaidComponents.CLOCK.get());
            boolean reset = clock == null || !clock.matches(dimension, now, values.hunger(), values.capacity());
            if (!reset && now < clock.deadline()) continue;
            boolean due = !reset && now == clock.deadline();
            LivingSoulSlabClock next = reset || now > clock.deadline()
                    ? LivingSoulSlabClock.start(dimension, now, values.hunger(), values.capacity())
                    : clock.next(now);
            ItemStack before = current.copy();
            ItemStack scheduled = before.copy();
            scheduled.set(MaidComponents.CLOCK.get(), next);
            if (!container.commitItemComponents(source, before, scheduled) || !due) continue;
            ItemStack confirmed = container.getItem(source);
            if (!ItemStack.matches(confirmed, scheduled)) continue;
            int target = chooseTarget(container, source, confirmed, level.getRandom());
            if (target < 0 || !ItemStack.matches(container.getItem(source), scheduled)
                    || !container.getItem(target).isEmpty()) continue;
            container.move(source, target, 1);
        }
    }

    static int chooseTarget(ContainerContext container, int source, ItemStack stack, RandomSource random) {
        if (container.simulateExtractItem(source, 1) < 1) return -1;
        int width = container.getWidth();
        if (width <= 0) return -1;
        int target = -1;
        int eligible = 0;
        for (int neighbor : ContainerContext.getNeighbors(source, container.getSize(), width)) {
            if (!container.isValidSlot(neighbor) || !container.getItem(neighbor).isEmpty()
                    || container.simulateInsertItem(neighbor, stack) < 1) continue;
            if (random.nextInt(++eligible) == 0) target = neighbor;
        }
        return target;
    }

    private static void clearClock(ContainerContext container, int slot, ItemStack stack) {
        if (!stack.has(MaidComponents.CLOCK.get())) return;
        ItemStack before = stack.copy();
        ItemStack after = before.copy();
        after.remove(MaidComponents.CLOCK.get());
        container.commitItemComponents(slot, before, after);
    }
}
