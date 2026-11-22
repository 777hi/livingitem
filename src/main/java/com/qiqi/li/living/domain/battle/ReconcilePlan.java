package com.qiqi.li.living.domain.battle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.ItemStack;

/**
 * 桥名册 ↔ 槽位快照的纯函数 diff —— 「槽位 → 桥实体」映射本体的可单测部分。
 *
 * <p>只产动作表，不碰实体/世界：spawn（新棋子部署）/ discard（撤子）/
 * move（同物品换槽 = 同一枚棋子换位，不是撤下重铸）。count 变更在应用侧直接对桥同步
 * （名册不存层数，桥的 SynchedEntityData 才是层数真相）。</p>
 */
public final class ReconcilePlan {

    /** 名册条目：桥 UUID + 物品身份指纹（item + 组件，数量恒 1）。 */
    public record BridgeRef(java.util.UUID uuid, ItemStack identity) {
        public BridgeRef {
            identity = identity.copyWithCount(1);
        }
    }

    /** 应用计划：三个动作表互不重叠（同一槽位只出现在一个表里）。 */
    public record Plan(List<Integer> spawn, List<Integer> discard, List<int[]> moves) {}

    private ReconcilePlan() {}

    /**
     * @param roster 当前桥名册（槽位 → 桥引用，含身份指纹）
     * @param living 槽位快照：非空活物品栈（槽位 → 栈；只读，不修改）
     * @return 动作表；无变化时三表皆空
     */
    public static Plan diff(Map<Integer, BridgeRef> roster, Map<Integer, ItemStack> living) {
        List<Integer> spawn = new ArrayList<>();
        List<Integer> discard = new ArrayList<>();
        // 名册槽位：消失 / 身份改变 ⇒ discard（同槽换物是「撤子 + 部署」，不是移动）
        for (Map.Entry<Integer, BridgeRef> entry : roster.entrySet()) {
            ItemStack current = living.get(entry.getKey());
            if (current == null
                || !ItemStack.matches(entry.getValue().identity(), current.copyWithCount(1))) {
                discard.add(entry.getKey());
            }
        }
        // 槽位快照：名册没有 / 身份不匹配 ⇒ spawn 候选（与 discard 配成 move 或独立部署）
        for (Map.Entry<Integer, ItemStack> entry : living.entrySet()) {
            BridgeRef ref = roster.get(entry.getKey());
            if (ref == null
                || !ItemStack.matches(ref.identity(), entry.getValue().copyWithCount(1))) {
                spawn.add(entry.getKey());
            }
        }
        // move 识别：一个「消失」+ 一个「出现」且物品身份相同（item + 组件，忽略数量）
        // ⇒ 同一枚棋子被拖动 / 换位（换位动词、活塞搬运、魂符走位都是这个形状）。
        // 排序保证配对确定性（roster/living 是 HashMap，迭代序不定）。
        discard.sort(Integer::compareTo);
        spawn.sort(Integer::compareTo);
        List<int[]> moves = new ArrayList<>();
        for (int i = discard.size() - 1; i >= 0; i--) {
            int from = discard.get(i);
            BridgeRef ref = roster.get(from);
            for (int j = 0; j < spawn.size(); j++) {
                int to = spawn.get(j);
                if (ItemStack.matches(ref.identity(), living.get(to).copyWithCount(1))) {
                    moves.add(new int[] {from, to});
                    discard.remove(i);
                    spawn.remove(j);
                    break;
                }
            }
        }
        return new Plan(spawn, discard, moves);
    }
}
