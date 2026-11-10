package com.qiqi.li.living.domain.maid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/SoulSlab");
    private static final int TICKS_PER_SECOND = 20;
    private static final ResourceLocation SOUL_SLAB = ResourceLocation.fromNamespaceAndPath(ModTlm.MOD_ID, "smart_slab_has_maid");

    enum AttemptOutcome { SUCCESS, FAILURE, NEUTRAL }

    interface FavorLedger {
        boolean canAdd(ItemStack stack);
        ItemStack add(ItemStack stack, int amount);
    }

    private static final FavorLedger TLM_FAVOR = new FavorLedger() {
        @Override public boolean canAdd(ItemStack stack) { return TlmSoulData.canAddPendingFavor(stack); }
        @Override public ItemStack add(ItemStack stack, int amount) { return TlmSoulData.withPendingFavor(stack, amount); }
    };

    private final Predicate<ItemStack> itemPredicate;
    private final Predicate<ItemStack> foodPredicate;
    private final FavorLedger favorLedger;

    public LivingSoulSlabFunction() {
        this(stack -> ModTlm.isLoaded() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(SOUL_SLAB),
                TlmSoulData::isHomeMeal, TLM_FAVOR);
    }

    LivingSoulSlabFunction(Predicate<ItemStack> itemPredicate) {
        this(itemPredicate, TlmSoulData::isHomeMeal, TLM_FAVOR);
    }

    LivingSoulSlabFunction(Predicate<ItemStack> itemPredicate, Predicate<ItemStack> foodPredicate) {
        this(itemPredicate, foodPredicate, TLM_FAVOR);
    }

    LivingSoulSlabFunction(Predicate<ItemStack> itemPredicate, Predicate<ItemStack> foodPredicate,
                           FavorLedger favorLedger) {
        this.itemPredicate = itemPredicate;
        this.foodPredicate = foodPredicate;
        this.favorLedger = favorLedger;
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
            ItemStack current = container.getItem(source).copy();
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
            if (target < 0 || !container.isValidSlot(target)) {
                adjustClock(container, source, false);
                continue;
            }
            ItemStack targetStack = container.getItem(target).copy();
            if (!targetStack.isEmpty()) {
                AttemptOutcome feeding = tryFeed(container, source, target, current, targetStack, clock);
                if (feeding == AttemptOutcome.FAILURE) adjustClock(container, source, false);
                continue;
            }

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

    AttemptOutcome tryFeed(ContainerContext container, int source, int target,
                           ItemStack soul, ItemStack food, LivingSoulSlabClock clock) {
        if (LivingItemManager.isLivingItem(food) || !foodPredicate.test(food)
                || !favorLedger.canAdd(soul)) return AttemptOutcome.FAILURE;
        if (!container.supportsItemComponentWrite(source)) return AttemptOutcome.NEUTRAL;
        if (container.simulateExtractItem(target, 1) < 1) return AttemptOutcome.FAILURE;
        if (!ItemStack.matches(container.getItem(source), soul)
                || !ItemStack.matches(container.getItem(target), food)) return AttemptOutcome.NEUTRAL;

        ItemStack after = favorLedger.add(soul, 1);
        if (after.isEmpty()) return AttemptOutcome.NEUTRAL;
        after.set(MaidComponents.CLOCK.get(), clock.afterSuccess());
        if (!container.commitItemComponents(source, soul, after)) return AttemptOutcome.NEUTRAL;
        if (!ItemStack.matches(container.getItem(target), food)) {
            compensateFeed(container, source, target, after, soul, "目标食物在源提交后变化");
            return AttemptOutcome.NEUTRAL;
        }
        if (container.consume(target, 1) == 1) {
            if (ItemStack.matches(container.getItem(source), after)) return AttemptOutcome.SUCCESS;
            restoreConsumedFood(container, target, food);
            LOGGER.error("投喂后魂符被同步监听改写：容器 {}，魂符槽 {}，食物槽 {}，实际魂符 {}",
                    container.getCacheKey(), source, target, container.getItem(source));
            return AttemptOutcome.NEUTRAL;
        }

        compensateFeed(container, source, target, after, soul, "食物扣除未确认");
        return AttemptOutcome.NEUTRAL;
    }

    private static void restoreConsumedFood(ContainerContext container, int target, ItemStack before) {
        ItemStack expected = before.getCount() == 1 ? ItemStack.EMPTY : before.copyWithCount(before.getCount() - 1);
        ItemStack actual = container.getItem(target).copy();
        if (!ItemStack.matches(actual, expected) || !container.trySetItemExact(target, before)) {
            LOGGER.error("投喂食物恢复失败：容器 {}，食物槽 {}，期望原状 {}，实际 {}",
                    container.getCacheKey(), target, before, container.getItem(target));
            return;
        }
        ItemStack restored = container.getItem(target).copy();
        container.syncSlotToClients(target, restored);
        if (!ItemStack.matches(restored, before)) {
            LOGGER.error("投喂食物恢复回读不符：容器 {}，食物槽 {}，期望 {}，实际 {}",
                    container.getCacheKey(), target, before, restored);
        }
    }

    private static void compensateFeed(ContainerContext container, int source, int target,
                                       ItemStack after, ItemStack before, String reason) {
        if (!container.commitItemComponents(source, after, before)) {
            LOGGER.error("投喂补偿失败（{}）：容器 {}，魂符槽 {}，食物槽 {}，实际魂符 {}",
                    reason, container.getCacheKey(), source, target, container.getItem(source));
        }
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
