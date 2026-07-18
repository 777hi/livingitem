package com.qiqi.li.living.chest;

import java.util.List;
import java.util.UUID;

/**
 * 活箱子堆叠上下文标志 —— 在玩家 GUI 操作期间标记允许跨 UUID 堆叠。
 * 由 AbstractContainerMenuMixin 在 clicked() 前后设置/清除，
 * 由 ItemStackMixin 在 isSameItemSameComponents 中检查。
 */
public final class LivingChestStackFlags {

    /** 玩家 GUI 堆叠标志：仅在玩家鼠标/键盘操作时允许活箱子跨 UUID 堆叠 */
    public static final ThreadLocal<Boolean> ALLOW_STACK = new ThreadLocal<>();

    /**
     * 方块放置标志：当活箱子正在被放置为方块时，存储捕获的 UUID 列表。
     * 非 null 时表示正在放置方块，onShrink/onSetCount 会跳过处理。
     * HEAD 注入中设置，RETURN 注入中消费并清除。
     */
    public static final ThreadLocal<List<UUID>> BLOCK_PLACING_UUIDS = new ThreadLocal<>();

    private LivingChestStackFlags() {}
}