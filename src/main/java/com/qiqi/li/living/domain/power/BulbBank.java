package com.qiqi.li.living.domain.power;

import javax.annotation.Nullable;

import java.util.function.IntPredicate;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.util.WaxedCopperFamily;

/**
 * 一组铜灯堆的统一视图 —— 2026-10-09 power 收口 步骤 2 起。
 *
 * <p><b>为什么要有这个类</b>：原先「按剩余容量比例分配」这个操作<b>有三份实现、三种口径</b>
 * （{@code ContainerEnergyStorage.receive} / {@code LivingWaxedCopperFunction.distributeToBulbs} /
 * {@code BulbItemEnergyStorage}），没有单一归属地 —— 历史上同一个 long 溢出 bug 因此
 * <b>被复制到两处</b>（修完 {@code receive()} 后 {@code distributeToBulbs} 里还有一份）。
 * 本类把「扫铜灯堆 → 收集 → 比例分配 → 取整写入 → 零头回收」收成一处，
 * 把三处<b>故意的口径差异</b>变成<b>显式参数</b>（{@link FePolicy}）——<b>参数化差异，不抹平差异</b>。</p>
 *
 * <p><b>性能契约（硬约束，勿破）</b>：</p>
 * <ul>
 *   <li>{@link #scan} 是热路径（外部电力 mod 每 tick 高频调用）——<b>只扫一遍</b>
 *       {@code getStackInSlot}，把铜灯堆的引用与剩余容量留在数组里（非铜灯槽位为 null），
 *       后续所有分配/回收走数组。<b>不要改成「每堆重新取物品」</b>。</li>
 *   <li><b>无跨调用状态</b>：不引入 ThreadLocal 暂存池 / 指纹缓存（同类方案在配方书
 *       Mixin 上因重入风险被否决过）。</li>
 * </ul>
 *
 * <p><b>语义红线（迁移时不许破坏）</b>：整 FE 量化、完整步进保护（{@code count > leftover} 跳过）、
 * 「宁损勿造」（实充 ≤ 记账）、零头回收的 {@link #MAX_LEFTOVER_PASSES} 防御上限。
 * {@code RoundTripConservationIT} 是这条红线的守护者，<b>不许为了让它过而改它</b>。</p>
 */
final class BulbBank {

    /**
     * 分配口径 —— 三处调用方的<b>故意差异</b>显式化。
     *
     * <p>⚠️ 目前只接了 {@link #FLOOR_WHOLE_FE}（{@code ContainerEnergyStorage.receive}）。
     * 另两种口径待各自的调用方迁移时再加入 —— <b>届时由真实调用点定义语义，
     * 不预先发明</b>（方案 §3 的迁移顺序：① receive → ② distributeToBulbs → ③ BulbItemEnergyStorage → ④ extract）。</p>
     */
    enum FePolicy {
        /** 整 FE 向下量化（容器对外接口：声明 = accept，杜绝取整零头凭空造电） */
        FLOOR_WHOLE_FE
    }

    /**
     * 零头回收循环的防御性轮数上限（原 {@code ContainerEnergyStorage.MAX_LEFTOVER_PASSES}）。
     *
     * <p>合法情形下 leftover = Σ(share mod count) ≤ Σ(count−1)，且每轮至少扣掉一堆的 count，
     * 正常 1~2 轮就回收完（最坏 count=1 的堆参与时约 63 轮）。超过这个数说明份额算错了
     * （历史上是 long 溢出），此时<b>宁可少充也不能让服务端卡死</b>——少充依然满足
     * 「实充 ≤ 记账」的口径。</p>
     */
    static final int MAX_LEFTOVER_PASSES = 256;

    /** 铜灯堆引用（非铜灯槽位 = null）—— 单遍扫描的产物，后续全走本数组。 */
    private final ItemStack[] stacks;
    /** 各堆剩余容量（mFE），与 {@link #stacks} 同下标。 */
    private final long[] remaining;
    private final long totalRemaining;
    private final long totalCharge;

    private boolean dirty;

    private BulbBank(ItemStack[] stacks, long[] remaining, long totalRemaining, long totalCharge) {
        this.stacks = stacks;
        this.remaining = remaining;
        this.totalRemaining = totalRemaining;
        this.totalCharge = totalCharge;
    }

    /**
     * 扫描容器内的铜灯堆（<b>单遍</b> {@code getStackInSlot}）。
     *
     * @param items      物品访问器
     * @param slotFilter 可选的槽位过滤（锈级专属通道用）；null = 全收
     */
    static BulbBank scan(IItemHandler items, @Nullable IntPredicate slotFilter) {
        int slots = items.getSlots();
        ItemStack[] stacks = new ItemStack[slots];
        long[] remaining = new long[slots];
        long totalRemaining = 0;
        long totalCharge = 0;
        for (int i = 0; i < slots; i++) {
            if (slotFilter != null && !slotFilter.test(i)) continue;
            ItemStack stack = items.getStackInSlot(i);
            if (!isBulb(stack)) continue;
            int count = stack.getCount();
            long charge = LivingWaxedBulbData.of(stack).totalChargeMilliFe(count);
            long rem = PowerMath.BULB_UNIT_CAPACITY_MFE * count - charge;
            stacks[i] = stack;
            remaining[i] = rem;
            totalRemaining += rem;
            totalCharge += charge;
        }
        return new BulbBank(stacks, remaining, totalRemaining, totalCharge);
    }

    long totalRemaining() {
        return totalRemaining;
    }

    long totalCharge() {
        return totalCharge;
    }

    /** 本次操作是否真的改动了物品（供调用方决定是否 {@code setChanged()} 落盘）。 */
    boolean isDirty() {
        return dirty;
    }

    /** 铜灯判定：仅<b>已活化</b>的涂蜡铜灯参与能源系统（取消活化 = 普通物品，电量保留但不进出）。 */
    static boolean isBulb(ItemStack stack) {
        // 短路顺序很重要：isWaxedBulb 是 4 次 Item 引用比较（纳秒级、无内存访问），
        // isLivingItem 要查 DataComponentMap（PatchedDataComponentMap →
        // Reference2ObjectArrayMap 线性扫描，且是随机内存访问 = cache miss）。
        // 容器里绝大多数槽位不是铜灯，先做便宜的判断能省掉几乎全部组件查询。
        // 热路径：Flux Networks 等外部电力 mod 每 tick 高频调用 receiveEnergy，
        // 每次都全量扫所有槽位 —— 这里是主要的放大点。
        return !stack.isEmpty() && WaxedCopperFamily.isWaxedBulb(stack.getItem())
            && LivingItemManager.isLivingItem(stack);
    }

    /**
     * 充入：外部来的电按「剩余容量比例」分配入各铜灯堆
     * （每盏 q += share/count 向下取整，零头保守丢弃），受每盏容量上限。
     *
     * <p>原 {@code ContainerEnergyStorage.receive} 的逐字搬迁（步骤 2 第 1 步），
     * 语义逐一对应：整 FE 量化 + 比例分配 + 零头回收 + 完整步进保护。</p>
     *
     * @return 声明收下的 mFE（{@link FePolicy#FLOOR_WHOLE_FE} 下 = 量化后的 accept，
     *         恒满足「实充 ≤ 声明」）
     */
    long deposit(long wantMilliFe, boolean simulate, FePolicy policy) {
        if (totalRemaining <= 0) return 0;   // 全满（或无铜灯）

        // 整 FE 量化：机器支付多少 FE，铜灯就收多少 mFE×1000——杜绝取整零头凭空造电
        long accept = Math.min(wantMilliFe, totalRemaining);
        if (policy == FePolicy.FLOOR_WHOLE_FE) {
            accept -= accept % 1000;
        }
        if (accept <= 0) return 0;

        // 按剩余容量比例分配（两遍式：先算各堆份额，再统一落账）
        long distributed = 0;
        for (int i = 0; i < stacks.length; i++) {
            ItemStack stack = stacks[i];
            if (stack == null || remaining[i] <= 0) continue;
            // 份额统一走 PowerMath.mulDivFloor —— 那里集中了 long 溢出的防护。
            // 本处的量级：accept 可到 1e12、remaining[i] 可到 6.4e10，直接相乘 = 1.1e23
            // ≫ Long.MAX（9.22e18）→ 商变负数/乱值 → 本轮几乎分不出去 → leftover = accept
            // → 零头回收的 while 每轮只扣 count mFE，退化成 ~10 亿轮全槽扫描
            // （实测 27 槽 × 64 盏 + 外部请求 50 万 FE ⇒ 7.8e6 次 getStackInSlot
            // ≈ 1.17s/tick，与 spark 的 1094ms 慢 tick 吻合）。详见 PowerMath#mulDivFloor。
            long share = PowerMath.mulDivFloor(accept, remaining[i], totalRemaining);
            int count = stack.getCount();
            long perLamp = share / count;
            if (perLamp <= 0) continue;
            if (!simulate) {
                LivingWaxedBulbData data = LivingWaxedBulbData.of(stack);
                LivingWaxedBulbData.set(stack,
                    data.withChargeMilliFe(data.chargeMilliFe() + perLamp));
                dirty = true;
            }
            distributed += perLamp * count;
        }

        // 零头回收（2026-09-09 修复记账）：每堆一次写入 = 每盏 q+1 → 实际充入 count mFE，
        // 账面必须同样按 count 计——旧实现按 1 mFE 计账，实充是记账的 count 倍，
        // 每 tick 按「堆数 × count」凭空造电（RoundTripConservationIT 实测 1000t +2043 FE）。
        // 完整步进保护：count > leftover 时跳过（一次写入不得越过 accept）；
        // 不足一个完整步进的残余（< 最小有空间堆的 count，≤63 mFE）保守丢弃——
        // 与整 FE 量化同一「宁损勿造」方向。
        long leftover = accept - distributed;
        int passes = 0;
        while (leftover > 0 && passes++ < MAX_LEFTOVER_PASSES) {
            boolean progressed = false;
            for (int i = 0; i < stacks.length && leftover > 0; i++) {
                ItemStack stack = stacks[i];
                if (stack == null) continue;
                int count = stack.getCount();
                if (count > leftover) continue;   // 完整步进保护
                long q = LivingWaxedBulbData.of(stack).chargeMilliFe();
                if (q >= PowerMath.BULB_UNIT_CAPACITY_MFE) continue;
                if (!simulate) {
                    LivingWaxedBulbData.set(stack,
                        LivingWaxedBulbData.of(stack).withChargeMilliFe(q + 1));
                    dirty = true;
                }
                distributed += count;             // 记账 = 实充（count mFE）
                leftover -= count;
                progressed = true;
            }
            if (!progressed) break;
        }

        // 声明口径 = accept（整 FE）：实充 ≤ accept，残余 ≤ 最小堆 count−1 mFE 保守丢弃，
        // 灯实收恒 ≤ 支付方按返回值记账的量——只许损耗、不许凭空产生。
        return accept;
    }
}
