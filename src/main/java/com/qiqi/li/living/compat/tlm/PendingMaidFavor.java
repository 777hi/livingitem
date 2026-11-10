package com.qiqi.li.living.compat.tlm;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** 活魂符快照内尚未兑现的好感账。 */
public final class PendingMaidFavor {
    public static final String KEY = "living_item:pending_favor";
    public static final String FAVOR_KEY = "MaidFavorability";
    public static final int MAX_FAVOR = 384;

    private PendingMaidFavor() {}

    public static int read(CompoundTag tag) {
        if (tag == null || !tag.contains(KEY, Tag.TAG_INT)) return 0;
        return Math.clamp(tag.getInt(KEY), 0, MAX_FAVOR);
    }

    public static boolean canAdd(CompoundTag tag, int amount) {
        int current = currentFavor(tag);
        int pending = validPending(tag);
        return amount > 0 && current >= 0 && pending >= 0
                && (long) current + pending < MAX_FAVOR;
    }

    public static boolean add(CompoundTag tag, int amount) {
        int current = currentFavor(tag);
        int pending = validPending(tag);
        if (tag == null || amount <= 0 || current < 0 || pending < 0) return false;
        long room = MAX_FAVOR - (long) current - pending;
        if (room <= 0) return false;
        tag.putInt(KEY, pending + (int) Math.min(room, amount));
        return true;
    }

    public static int take(CompoundTag tag) {
        int value = read(tag);
        if (tag != null) tag.remove(KEY);
        return value;
    }

    public static void strip(CompoundTag tag) {
        if (tag != null) tag.remove(KEY);
    }

    private static int validPending(CompoundTag tag) {
        if (tag == null || !tag.contains(KEY)) return 0;
        if (!tag.contains(KEY, Tag.TAG_INT)) return -1;
        int value = tag.getInt(KEY);
        return value >= 0 && value <= MAX_FAVOR ? value : -1;
    }

    private static int currentFavor(CompoundTag tag) {
        if (tag == null || !tag.contains(FAVOR_KEY, Tag.TAG_INT)) return -1;
        int value = tag.getInt(FAVOR_KEY);
        return value >= 0 && value <= MAX_FAVOR ? value : -1;
    }
}
