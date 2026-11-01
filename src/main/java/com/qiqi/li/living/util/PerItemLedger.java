package com.qiqi.li.living.util;

/**
 * 「按枚定点均分记账」内核（设计稿 docs/buffer/活海绵.md §3.1，power / sponge 两域共享）。
 *
 * <p>纯函数、无 IO 无扫描。同栈共享同一份组件 ⇒ 任何 ±D 只能 q ± D/count
 * （均分是算术必然，不是语义选择）；整数域除法必有残余 ⇒ 量子必须细于外部接口
 * （mFE / mmB 定点），残余按「宁损勿造」丢弃 —— 损耗有界（≤ (count−1) × 量子）。</p>
 *
 * <p>🔴 两条灯泡血泪不变量（2026-09-09 教训，{@code RoundTripConservationIT} 实测
 * 1000t +2043 FE 凭空造电）：</p>
 * <ul>
 *   <li><b>记账与写入同源</b>：每枚写入 per，账面必须同样按 per × count 计
 *       —— 按堆写入、按 1 计账 = 每 tick 凭空造 count 倍；</li>
 *   <li><b>实收 ≤ 记账</b>：残余只许损耗向丢弃，永不补回（补回即拆堆不安全，§3.3 表示法定理）。</li>
 * </ul>
 *
 * <p>跨堆零头回收（回收优先 / {@code MAX_LEFTOVER_PASSES} 防御）是「发电全局灌」特有的
 * 多堆机制，留在 {@code BulbBank}；单栈场景余量恒 &lt; count，恒走保守丢弃（设计稿 §3）。
 * 排出侧（ceil 口径）随第三段物品能力一起落，不在本内核 v1。</p>
 */
public final class PerItemLedger {

    /** 均分结果：{@code newQ} = 写入后的每枚值；{@code accepted} = 实收总量（= per × count，记账口径）。 */
    public record Distribution(long newQ, long accepted) {}

    /**
     * 充入均分：{@code delta} 按 {@code count} 均分（floor），每枚不越 {@code capPerItem} 上限。
     *
     * @param q          当前每枚值
     * @param count      堆叠数（≤ 0 视为不动）
     * @param capPerItem 每枚容量上限
     * @param delta      本次充入总量（内部定点量子，如 mFE / mmB；非正视为不动）
     * @return 均分结果；无可写入时 {@code accepted} = 0 且 {@code newQ} 原样返回
     */
    public static Distribution distribute(long q, int count, long capPerItem, long delta) {
        if (count <= 0 || delta <= 0) return new Distribution(q, 0);
        long per = delta / count;                       // 均分 floor —— 算术必然
        if (per <= 0) return new Distribution(q, 0);    // 完整步进保护：不足一整步不写（一次写入不得越过 delta）
        long room = capPerItem - q;
        if (per > room) per = room;                     // 容量防御（调用方已保证份额 ≤ 剩余时为 no-op）
        if (per <= 0) return new Distribution(q, 0);    // 已满 / 满溢防护
        return new Distribution(q + per, per * (long) count);  // 记账 = 写入 × count（同源）
    }

    /**
     * 排出均分（<b>ceil 口径</b>，排出侧唯一口径 —— 2026-11-02 实测教训：floor 版会让
     * 「交付量」随请求量逐次缩水（999 请求实交 998、997…），SIMULATE 与 EXECUTE 分叉
     * （§8.2#2），尾差沉成「罐里显示却抽不出」的卡死尘）。
     *
     * <p>语义 = <b>整单位交付</b>：调用方决定交付 {@code delta}（内部量子），每枚扣
     * ⌈delta / count⌉ ⇒ 实扣 ≥ 承诺，差额损耗向销毁（floor 版会让交付方「凭空」得到
     * 差额，违反规则 12）。**同请求 simulate / execute 必同结果**（算术只依赖 delta 与
     * 当前 q，§8.2#2 闭合）。调用方须先保证堆总量 ≥ delta。</p>
     */
    public static Distribution withdrawCeil(long q, int count, long delta) {
        if (count <= 0 || delta <= 0) return new Distribution(q, 0);
        long per = (delta + count - 1) / count;         // ceil —— 整单位交付语义
        if (per > q) per = q;                           // 不透支防御（前置校验后不应触发）
        if (per <= 0) return new Distribution(q, 0);
        return new Distribution(q - per, per * (long) count);
    }

    private PerItemLedger() {}
}
