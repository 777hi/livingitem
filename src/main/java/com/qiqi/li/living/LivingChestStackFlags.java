package com.qiqi.li.living;

/**
 * 活箱子堆叠上下文标志 —— 在玩家 GUI 操作期间标记允许跨 UUID 堆叠。
 * 由 AbstractContainerMenuMixin 在 clicked() 前后设置/清除，
 * 由 ItemStackMixin 在 isSameItemSameComponents 中检查。
 */
public final class LivingChestStackFlags {

    /** 玩家 GUI 堆叠标志：仅在玩家鼠标/键盘操作时允许活箱子跨 UUID 堆叠 */
    public static final ThreadLocal<Boolean> ALLOW_STACK = new ThreadLocal<>();

    private LivingChestStackFlags() {}
}