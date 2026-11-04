package com.qiqi.li.living.transfer;

import com.qiqi.li.living.container.ContainerContext;
import net.minecraft.world.item.ItemStack;

/**
 * 精确容器槽的原始物品访问器。
 *
 * <p>只读写指定槽里的物品本体，不经过注册式访问器工厂，因此容器型物品不会被展开。</p>
 */
final class BodySlotAccessor implements SlotAccessor {

    private final ContainerContext context;
    private final int slot;

    BodySlotAccessor(ContainerContext context, int slot) {
        this.context = context;
        this.slot = slot;
    }

    @Override
    public ItemStack extract(int amount, ItemStack filterType) {
        ItemStack simulated = simulateExtract(amount);
        if (simulated.isEmpty()) return ItemStack.EMPTY;

        ItemStack before = context.getItem(slot).copy();
        int extractedCount = simulated.getCount();
        ItemStack expected = before.copy();
        expected.shrink(extractedCount);
        context.setItem(slot, expected.isEmpty() ? ItemStack.EMPTY : expected);

        ItemStack actual = context.getItem(slot);
        if (!ItemStack.matches(actual, expected.isEmpty() ? ItemStack.EMPTY : expected)) {
            restore(before, "extraction", expected, actual);
            return ItemStack.EMPTY;
        }
        return before.copyWithCount(extractedCount);
    }

    @Override
    public int insert(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        ItemStack current = context.getItem(slot).copy();
        int accepted = insertableCount(stack);
        if (accepted <= 0) return 0;

        ItemStack expected;
        if (current.isEmpty()) {
            expected = stack.copyWithCount(accepted);
        } else {
            expected = current.copy();
            expected.grow(accepted);
        }
        context.setItem(slot, expected);
        ItemStack actual = context.getItem(slot);
        if (!ItemStack.matches(actual, expected)) {
            restore(current, "insertion", expected, actual);
            return 0;
        }
        stack.shrink(accepted);
        return accepted;
    }

    private void restore(ItemStack before, String operation, ItemStack expected, ItemStack actual) {
        SlotAccessor.ROLLBACK_LOGGER.error(
            "Raw body {} writeback failed: slot {} expected {} but read {}; restoring {}",
            operation, slot, expected, actual, before);
        context.setItem(slot, before.copy());
        ItemStack restored = context.getItem(slot);
        if (!ItemStack.matches(restored, before)) {
            SlotAccessor.ROLLBACK_LOGGER.error(
                "Raw body {} restore failed: slot {} expected {} but read {}",
                operation, slot, before, restored);
        }
    }

    @Override
    public ItemStack simulateExtract(int amount) {
        if (amount <= 0 || !context.supportsExactWrite(slot)) return ItemStack.EMPTY;
        ItemStack source = context.getItem(slot);
        if (source.isEmpty()) return ItemStack.EMPTY;

        int requested = Math.min(amount, source.getCount());
        int allowed = Math.max(0, Math.min(requested,
            context.simulateExtractItem(slot, requested)));
        return allowed > 0 ? source.copyWithCount(allowed) : ItemStack.EMPTY;
    }

    @Override
    public int simulateInsert(ItemStack stack) {
        return stack.isEmpty() ? 0 : insertableCount(stack);
    }

    private int insertableCount(ItemStack stack) {
        if (!context.supportsExactWrite(slot)) return 0;
        // I-C14 ①：容量问容器（含准入 / 物品上限 / 超堆叠口径），空槽与合并同走一个权威
        return Math.max(0, Math.min(stack.getCount(), context.simulateInsertItem(slot, stack)));
    }

    @Override
    public void rollback(ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemStack current = context.getItem(slot);
        int max;
        int room;
        if (!context.supportsExactWrite(slot)) {
            room = 0;
        } else if (current.isEmpty()) {
            max = context.getSlotLimit(slot);
            room = Math.max(0, max);
        } else if (ItemStack.isSameItemSameComponents(current, stack)) {
            max = context.getSlotLimit(slot);
            room = Math.max(0, max - current.getCount());
        } else {
            room = 0;
        }
        if (stack.getCount() > room) {
            SlotAccessor.ROLLBACK_LOGGER.error(
                "Raw body rollback lost: {}x {} -> slot {} (occupied or no space; exact-slot restore failed)",
                stack.getCount(), stack.getHoverName().getString(), slot);
            return;
        }

        ItemStack expected;
        if (current.isEmpty()) {
            expected = stack.copy();
        } else {
            expected = current.copy();
            expected.grow(stack.getCount());
        }
        context.setItem(slot, expected);
        ItemStack actual = context.getItem(slot);
        if (!ItemStack.matches(actual, expected)) {
            SlotAccessor.ROLLBACK_LOGGER.error(
                "Raw body rollback writeback failed: slot {} expected {} but read {}",
                slot, expected, actual);
        }
    }

    @Override
    public boolean isEmpty() {
        return context.getItem(slot).isEmpty();
    }

    @Override
    public boolean isFull() {
        ItemStack stack = context.getItem(slot);
        if (stack.isEmpty()) return false;
        // 判满 = 容器自己答「还能不能塞进一个同类」（I-C14 ①），与 NeighborSlotAccessor.isFull 同口径
        return context.simulateInsertItem(slot, stack.copyWithCount(1)) == 0;
    }

    @Override
    public void markTransferred() {
        // 源槽只做扣减，不登记为传输目标。
    }

    @Override
    public void sync() {
        context.syncSlotToClients(slot, context.getItem(slot));
    }
}
