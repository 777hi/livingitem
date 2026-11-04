package com.qiqi.li.living.transfer;

import java.util.Set;

import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.SlotInfoProvider;
import com.qiqi.li.living.container.ContainerSync;

/**
 * 普通槽位访问器 —— 直接操作容器槽位。
 *
 * <p>适用于非活物品的普通槽位，通过 {@link SlotInfoProvider} 读写物品，
 * 通过 {@link ContainerSync} 同步到客户端。</p>
 */
public class PlainSlotAccessor implements SlotAccessor {

    private final SlotInfoProvider slotInfo;
    private final ContainerSync sync;
    private final int slot;
    private final Set<Integer> transferredTargetSlots;

    PlainSlotAccessor(ContainerContext containerCtx, int slot, Set<Integer> transferredTargetSlots) {
        this.slotInfo = containerCtx;
        this.sync = containerCtx;
        this.slot = slot;
        this.transferredTargetSlots = transferredTargetSlots;
    }

    @Override
    public ItemStack extract(int amount, ItemStack filterType) {
        ItemStack sourceStack = slotInfo.getItem(slot);
        if (sourceStack.isEmpty()) return ItemStack.EMPTY;

        // setItem 是覆盖语义，会抽干活引用 —— 撤销快照与数量必须先捕获
        ItemStack before = sourceStack.copy();
        int beforeCount = before.getCount();

        int transferAmount = Math.min(beforeCount, amount);
        ItemStack extracted = before.copyWithCount(transferAmount);
        ItemStack expected = transferAmount >= beforeCount
            ? ItemStack.EMPTY
            : before.copyWithCount(beforeCount - transferAmount);
        slotInfo.setItem(slot, expected);

        // I-C12 源侧回读：写回静默失败 / 被重定向 ⇒ 整笔还原并返回 EMPTY，
        // 绝不把「没离开源槽」的货交给调用方（会复制）
        ItemStack after = slotInfo.getItem(slot);
        if (!ItemStack.matches(after, expected)) {
            restore(before, "extraction", expected, after);
            return ItemStack.EMPTY;
        }

        return extracted;
    }

    @Override
    public ItemStack simulateExtract(int amount) {
        ItemStack sourceStack = slotInfo.getItem(slot);
        if (sourceStack.isEmpty()) return ItemStack.EMPTY;

        int transferAmount = Math.min(sourceStack.getCount(), amount);
        ItemStack result = sourceStack.copy();
        result.setCount(transferAmount);
        return result;
    }

    @Override
    public int insert(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        // restore 快照：getItem 返回活引用，setItem 可能抽干它
        ItemStack current = slotInfo.getItem(slot).copy();

        // I-C14 ①：容量问容器（含准入 / 物品上限 / 超堆叠口径），预算先算再写
        long room = (long) slotInfo.getSlotLimit(slot) - current.getCount();
        int accepted = (int) Math.min(
            Math.min(stack.getCount(), slotInfo.simulateInsertItem(slot, stack)),
            Math.max(0, room));
        if (accepted <= 0) return 0;

        ItemStack expected = current.isEmpty()
            ? stack.copyWithCount(accepted)
            : current.copyWithCount(current.getCount() + accepted);
        slotInfo.setItem(slot, expected);

        // I-C12 目标侧回读：只认真正写进槽里的量，传入栈只扣实际量
        ItemStack after = slotInfo.getItem(slot);
        if (!ItemStack.matches(after, expected)) {
            restore(current, "insertion", expected, after);
            return 0;
        }
        stack.shrink(accepted);
        return accepted;
    }

    @Override
    public int simulateInsert(ItemStack stack) {
        return stack.isEmpty() ? 0 : Math.max(0, slotInfo.simulateInsertItem(slot, stack));
    }

    @Override
    public void rollback(ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemStack current = slotInfo.getItem(slot).copy();
        int slotLimit = slotInfo.getSlotLimit(slot);

        // 回滚是恢复路径，不做准入，只按槽位上限判能否整份放回（与 BodySlotAccessor 同口径）
        long room = (long) slotLimit - current.getCount();
        boolean sameItem = current.isEmpty() || ItemStack.isSameItemSameComponents(current, stack);
        if (!sameItem || stack.getCount() > Math.max(0, room)) {
            SlotAccessor.ROLLBACK_LOGGER.error(
                "LivingItem rollback lost: {}x {} -> plain slot {} (occupied or no space; exact-slot restore failed)",
                stack.getCount(), stack.getHoverName().getString(), slot);
            return;
        }

        ItemStack expected = current.isEmpty()
            ? stack.copy()
            : current.copyWithCount(current.getCount() + stack.getCount());
        slotInfo.setItem(slot, expected);

        ItemStack after = slotInfo.getItem(slot);
        if (!ItemStack.matches(after, expected)) {
            SlotAccessor.ROLLBACK_LOGGER.error(
                "LivingItem rollback writeback failed: slot {} expected {} but read {}",
                slot, expected, after);
        }
    }

    /** 写回失败时的整笔还原（先恢复再校验，两步都留痕 —— 与 {@link BodySlotAccessor#restore} 同形）。 */
    private void restore(ItemStack before, String operation, ItemStack expected, ItemStack actual) {
        SlotAccessor.ROLLBACK_LOGGER.error(
            "Plain slot {} writeback failed ({}): expected {} but read {}; restoring",
            slot, operation, expected, actual, before);
        slotInfo.setItem(slot, before.copy());
        ItemStack restored = slotInfo.getItem(slot);
        if (!ItemStack.matches(restored, before)) {
            SlotAccessor.ROLLBACK_LOGGER.error(
                "Plain slot {} restore failed ({}): expected {} but read {}",
                slot, operation, before, restored);
        }
    }

    @Override
    public boolean isEmpty() {
        return slotInfo.getItem(slot).isEmpty();
    }

    @Override
    public boolean isFull() {
        ItemStack stack = slotInfo.getItem(slot);
        if (stack.isEmpty()) return false;
        // 判满 = 容器自己答「还能不能塞进一个同类」（I-C14 ①），三个 Accessor 同口径
        return slotInfo.simulateInsertItem(slot, stack.copyWithCount(1)) == 0;
    }

    @Override
    public void markTransferred() {
        if (transferredTargetSlots != null) {
            transferredTargetSlots.add(slot);
        }
    }

    @Override
    public void sync() {
        sync.syncSlotToClients(slot, slotInfo.getItem(slot));
    }
}