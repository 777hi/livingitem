package com.qiqi.li.living.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 「定向广播」去重表的跨维度合并 —— 四个同步器共用的收尾动作。
 *
 * <h3>为什么需要它</h3>
 * <p>这类同步器都是<b>逐维度</b>调用的（{@code for (level : server.getAllLevels())}，
 * 恒有主世界 / 地狱 / 末地三个），而去重表是<b>全服一张</b>（键是玩家 UUID）。
 * 若每个维度收尾都 {@code clear()} 再 {@code putAll(next)}，第一个维度就把
 * <b>其它维度玩家</b>的记录一起抹掉 ⇒ 他们下一 tick 查到 {@code null} ⇒ 判「有变化」
 * ⇒ <b>每 tick 重发一个包</b>。</p>
 *
 * <p>2026-10-23 在 {@code LivingToolHostSync} 实测：单条 INFO 刷了 3803 次
 * （玩家只有一个，包却每 tick 都在发）。2026-10-26 审查发现另外三个同步器
 * （{@code LivingToolPlayerSync} / {@code BeaconChestHost} / {@code BeaconPlayerHostSync}）
 * 仍在照抄旧写法，故抽到此处由四个调用点共用 —— 抄三份就会只修一份。</p>
 *
 * <h3>合并口径</h3>
 * <ul>
 *   <li>本维度本 tick 的结果（{@code next}）<b>覆盖</b>旧值；</li>
 *   <li>其它维度的记录<b>原样保留</b>（由它们各自维度的 flush 覆盖）；</li>
 *   <li>已离线 / 离服的玩家<b>淘汰</b>（否则表会随玩家进出无限累积）。</li>
 * </ul>
 *
 * <p>⚠️ 抽成纯函数是为了让这条不变量能脱离服务端对象单测（{@code BroadcastDedupTest}）。
 * 改回 {@code clear()} 不会报错、也不会有别的测试红 —— 只会让每个玩家每 tick 多收一个包，
 * 属于<b>静默退化</b>，所以必须钉住。</p>
 */
public final class BroadcastDedup {

    private BroadcastDedup() {
    }

    /**
     * @param previous 上一 tick 的全服去重表（可能含<b>其它维度</b>的记录）
     * @param next     本维度本 tick 算出的记录
     * @param online   当前<b>全服</b>在线玩家 —— ⚠️ 不是本维度的：用本维度会误删其它维度玩家的记录
     */
    public static <K, V> Map<K, V> merge(Map<K, V> previous, Map<K, V> next, Set<K> online) {
        Map<K, V> merged = new HashMap<>(previous);
        merged.keySet().retainAll(online);   // 淘汰离线 / 离服玩家
        merged.putAll(next);                 // 本维度本 tick 的结果覆盖旧值
        return merged;
    }
}
