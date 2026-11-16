package com.qiqi.li.client.gui;

import java.util.concurrent.atomic.AtomicLong;

import com.qiqi.li.network.LivingTagPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** 创造活化单请求会话；编号跨界面唯一，重建按钮不重置等待状态。 */
public final class CreativeActivationSession {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private final int menuId;
    private long requestId;
    private ItemStack expected = ItemStack.EMPTY;

    public CreativeActivationSession(int menuId) { this.menuId = menuId; }

    public LivingTagPacket begin(ItemStack carried, CompoundTag tag) {
        if (pending() || carried.isEmpty() || tag == null) return null;
        expected = carried.copy();
        requestId = SEQUENCE.incrementAndGet();
        return new LivingTagPacket(menuId, tag, requestId);
    }

    public boolean accept(int responseMenuId, long responseRequestId, ItemStack carried) {
        if (!pending() || responseMenuId != menuId || responseRequestId != requestId) return false;
        boolean unchanged = ItemStack.matches(expected, carried);
        clear();
        return unchanged;
    }

    public boolean discardExpected(ItemStack carried) {
        boolean discard = pending() && ItemStack.matches(expected, carried);
        clear();
        return discard;
    }

    public boolean pending() { return requestId != 0; }

    public void clear() {
        requestId = 0;
        expected = ItemStack.EMPTY;
    }
}
