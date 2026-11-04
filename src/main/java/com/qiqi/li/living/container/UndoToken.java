package com.qiqi.li.living.container;

import net.minecraft.world.item.ItemStack;

/**
 * 一步移动的<b>撤销凭据</b>。
 *
 * <p><b>为什么不用裸 {@code restore(slot, stack)}</b>（2026-11-03 活塞侧指出）：</p>
 * <pre>
 * 移动后：源空，目标有 A
 * 只 restore(源, A)  →  源和目标各有 A  ⇒ 复制
 * </pre>
 * <p>裸恢复只表达「把货放回源」，没有表达「撤销目标的新增」。⇒ 对外只提供
 * 「一步移动的撤销」（{@code ContainerContext#undo}），单槽恢复降为实现内部细节。</p>
 *
 * <p>⚠️ <b>它不是持久事务，也不是物品身份</b>：只记录本次调用内的槽号 / 数量 / 前后状态，
 * <b>只在本次 tick 内有效</b>（跨 tick 后容器内容可能已被玩家或第三方改动，
 * 届时 {@code undo} 的校验会拒绝执行）。</p>
 *
 * @param from         源槽（逻辑槽号）
 * @param to           目标槽（逻辑槽号）
 * @param sourceBefore 移动前源槽内容（副本）
 * @param targetBefore 移动前目标槽内容（副本；纯位移时为空）
 * @param moved        实际移动数量
 */
public record UndoToken(int from, int to, ItemStack sourceBefore, ItemStack targetBefore, int moved) {

    /** 移动后源槽的预期内容。 */
    public ItemStack expectedSourceAfter() {
        int left = sourceBefore.getCount() - moved;
        return left <= 0 ? ItemStack.EMPTY : sourceBefore.copyWithCount(left);
    }

    /** 移动后目标槽的预期内容。 */
    public ItemStack expectedTargetAfter() {
        if (targetBefore.isEmpty()) {
            return sourceBefore.copyWithCount(moved);
        }
        // sum 走 long：本分支当前不可达（moveStack 强制目标为空），防后来者照抄时溢出
        long sum = (long) targetBefore.getCount() + moved;
        return targetBefore.copyWithCount((int) sum);
    }
}
