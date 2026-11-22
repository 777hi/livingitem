package com.qiqi.li.living.domain.battle;

/**
 * 战场事件 —— 事件流广播的最小单元（fire-and-forget，无存储无回放）。
 *
 * <p>白名单枚举：不在 {@link Type} 里的动作不进事件流（五重圈定之三）。
 * spike 白名单只有 {@code SWING}；爆炸 / 弹射物 / 死亡 / 波次为后续扩充。</p>
 *
 * @param type 事件类型（白名单）
 * @param slot 目标桥的逻辑槽位；{@code -1} = 无目标（战局级事件）
 */
public record BattleEvent(Type type, int slot) {

    public enum Type {
        /** 敌军啃咬桥（挥臂重演 + 桥红闪） */
        SWING,
        /** 棋子被吃光（count=0 物品消失；GUI 长红闪） */
        PIECE_LOST,
        /** 战败：敌军越过战败线（MVP 轻惩罚 = 清敌军重置波次；GUI 横幅） */
        DEFEAT
    }
}
