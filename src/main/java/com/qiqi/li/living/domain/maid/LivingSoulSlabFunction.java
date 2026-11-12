package com.qiqi.li.living.domain.maid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.compat.tlm.ModTlm;
import com.qiqi.li.living.compat.tlm.TlmSoulData;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.MoveResult;
import com.qiqi.li.living.container.TickContext;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
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
        int pending(ItemStack stack);
    }

    private static final FavorLedger TLM_FAVOR = new FavorLedger() {
        @Override public boolean canAdd(ItemStack stack) { return TlmSoulData.canAddPendingFavor(stack); }
        @Override public ItemStack add(ItemStack stack, int amount) { return TlmSoulData.withPendingFavor(stack, amount); }
        @Override public int pending(ItemStack stack) { return TlmSoulData.pendingFavor(stack); }
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
        return Set.of(MaidComponents.CLOCK.get(), MaidComponents.SITTING.get());
    }

    /** 空光标右键 toggle 坐下 / 起身；false 规范化为组件缺席。 */
    public static boolean toggleSitting(ItemStack stack) {
        boolean next = !isSitting(stack);
        if (next) stack.set(MaidComponents.SITTING.get(), new LivingSoulSlabSitting(true));
        else stack.remove(MaidComponents.SITTING.get());
        return true;
    }

    /** 坐下的女仆暂停随机走位（被动位移不受影响，被推到新槽保持坐姿）。 */
    public static boolean isSitting(ItemStack stack) {
        LivingSoulSlabSitting sitting = stack.get(MaidComponents.SITTING.get());
        return sitting != null && sitting.value();
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder,
                             TooltipFlag flag, ItemStack stack) {
        if (!canApply(stack) || stack.getCount() != 1) return;
        LivingSoulSlabClock clock = stack.get(MaidComponents.CLOCK.get());
        int interval = clock != null && clock.valid()
                ? clock.intervalSeconds() : LivingSoulSlabClock.INITIAL_INTERVAL_SECONDS;
        String percentage = String.format(Locale.ROOT, "%.1f", 100.0 / interval);

        tooltipAdder.accept(Component.empty());
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.soul_slab.title")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        if (isSitting(stack)) {
            // 坐下 = 行动暂停 ⇒ 概率行会误导，只显示休息状态
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.soul_slab.sitting")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.soul_slab.attempt_chance",
                    Component.literal("1/" + interval).withStyle(ChatFormatting.YELLOW),
                    Component.literal(percentage + "%").withStyle(ChatFormatting.AQUA),
                    Component.literal(Integer.toString(interval)).withStyle(ChatFormatting.YELLOW))
                    .withStyle(ChatFormatting.GRAY));
        }
        int pending = favorLedger.pending(stack);
        if (pending > 0) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.soul_slab.pending_favor",
                    Component.literal("+" + pending).withStyle(ChatFormatting.GREEN))
                    .withStyle(ChatFormatting.GRAY));
        }
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
            if (isSitting(current)) continue;   // 坐下 ⇒ 行动尝试直接跳过，I 不变

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
                AttemptOutcome outcome;
                if (LivingItemManager.isLivingItem(targetStack)) {
                    outcome = AttemptOutcome.FAILURE;   // 活物品是居民/机器，不是货物：挡路
                } else if (foodPredicate.test(targetStack)) {
                    outcome = tryFeed(container, source, target, current, targetStack);
                } else {
                    outcome = trySwap(container, source, target);
                }
                if (outcome == AttemptOutcome.FAILURE) {
                    adjustClock(container, source, false);
                } else if (outcome == AttemptOutcome.SUCCESS) {
                    adjustClock(container, target, true);   // 女仆已在新位置，I−1 统一在新槽提交
                }
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

    /**
     * 换位：与普通非活化物品交换位置（女仆进入目标槽，整栈来到原槽）。
     * 换位是女仆完成的行动 ⇒ 按成功 {@code I−1}；后端不支持 / 违约 ⇒ 技术中性。
     */
    AttemptOutcome trySwap(ContainerContext container, int source, int target) {
        MoveResult result = container.swap(source, target);
        if (result instanceof MoveResult.Success) return AttemptOutcome.SUCCESS;
        if (result instanceof MoveResult.Unsupported
                || result instanceof MoveResult.BackendViolation) return AttemptOutcome.NEUTRAL;
        return AttemptOutcome.FAILURE;
    }

    /**
     * 供品：吃一份居家餐并与其剩余堆换位（2026-11-12 翻转旧"站着吃不移动"口径）。
     *
     * <p>顺序 = swap → consume → commit（swap 是自身逆操作，吃失败换回即整体无净变化）；
     * commit 失败时已换位已吃，事实保留、pending 不加（宁失勿复）。</p>
     */
    AttemptOutcome tryFeed(ContainerContext container, int source, int target,
                           ItemStack soul, ItemStack food) {
        if (!favorLedger.canAdd(soul)) return AttemptOutcome.FAILURE;
        if (!container.supportsItemComponentWrite(target)) return AttemptOutcome.NEUTRAL;
        if (!ItemStack.matches(container.getItem(source), soul)
                || !ItemStack.matches(container.getItem(target), food)) return AttemptOutcome.NEUTRAL;

        MoveResult swapped = container.swap(source, target);
        if (swapped instanceof MoveResult.Unsupported
                || swapped instanceof MoveResult.BackendViolation) return AttemptOutcome.NEUTRAL;
        if (!(swapped instanceof MoveResult.Success)) return AttemptOutcome.FAILURE;

        ItemStack fed = container.getItem(target).copy();
        if (container.consume(source, 1) != 1) {
            MoveResult reverted = container.swap(source, target);
            if (reverted instanceof MoveResult.Success) return AttemptOutcome.FAILURE;
            LOGGER.error("投喂回退失败：容器 {}，魂符槽 {}，食物槽 {}，实际魂符 {} / 食物 {}",
                    container.getCacheKey(), source, target,
                    container.getItem(source), container.getItem(target));
            return AttemptOutcome.NEUTRAL;
        }

        ItemStack after = favorLedger.add(fed, 1);
        if (after.isEmpty()) return AttemptOutcome.NEUTRAL;
        // clock 的 I−1 由 tick 的 SUCCESS 统一在新位置 adjust，此处只提交 pending
        if (container.commitItemComponents(target, fed, after)
                && ItemStack.matches(container.getItem(target), after)) {
            return AttemptOutcome.SUCCESS;
        }
        LOGGER.warn("投喂账本提交失败：容器 {}，槽位 {} —— 已换位已吃，事实保留、pending 不加",
                container.getCacheKey(), target);
        return AttemptOutcome.NEUTRAL;
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
