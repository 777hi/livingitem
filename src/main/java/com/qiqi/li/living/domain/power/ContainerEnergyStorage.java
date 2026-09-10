package com.qiqi.li.living.domain.power;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainerHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.RandomizableContainer;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import com.qiqi.li.living.api.LivingItemManager;

/**
 * 容器红电对外能量接口（§3.6 v17.5）。
 *
 * <p>取电：直接从容器内的铜灯逐堆扣（每盏等量，向下取整）——
 * 无容器池，铜灯是唯一储存。只出不进（canReceive = false）：
 * 发电是唯一能量来源，限流职责归用电侧。</p>
 *
 * <p>物品访问统一走 {@code ItemHandler.BLOCK} 兼容面（原版容器由 NeoForge 自动注册、
 * 模组容器自行注册），与容器内 tick 机制同源——模组容器能发电的地方就能取电。
 * 随机战利品容器（未开箱）按 tick 机制同款规则跳过。</p>
 */
public class ContainerEnergyStorage implements IEnergyStorage {

    /**
     * 零头回收循环的防御性轮数上限。
     *
     * <p>合法情形下 leftover = Σ(share mod count) ≤ Σ(count−1)，且每轮至少扣掉
     * 一堆的 count，正常 1~2 轮就回收完（最坏 count=1 的堆参与时约 63 轮）。
     * 超过这个数说明份额算错了（历史上是 long 溢出），此时<b>宁可少充也不能让服务端
     * 卡死</b>——少充依然满足「实充 ≤ 记账」的口径。</p>
     */
    private static final int MAX_LEFTOVER_PASSES = 256;

    private final BlockEntity be;

    public ContainerEnergyStorage(BlockEntity be) {
        this.be = be;
    }

    /**
     * 宽注册 provider 判定链（§3.6 v17.5，全部缓存安全）：
     * <ol>
     *   <li>随机战利品容器（未开箱）→ null（与 tick 机制同款跳过，类型稳定）</li>
     *   <li>直接实现 IEnergyStorage 的 BE → null（让位，零重入成本）</li>
     *   <li>重入保护下查询 EnergyStorage.BLOCK：已有主人（模组机器自身储能）→
     *       null（让位——注册期定死的稳定属性）</li>
     *   <li>返回实例（内部自适应：无灯 → 电量 0，永不 null 切换 → 零失效隐患）</li>
     * </ol>
     */
    public static IEnergyStorage resolveProvider(BlockEntity be, @Nullable Direction side) {
        if (be instanceof RandomizableContainer rc && rc.getLootTable() != null) return null;
        if (be instanceof IEnergyStorage) return null;            // 直接实现者让位
        Level level = be.getLevel();
        if (level == null) return null;
        if (DEFER_QUERY.get()) return null;                       // 重入保护：内层查询摘除自己
        DEFER_QUERY.set(true);
        try {
            var existing = level.getCapability(
                Capabilities.EnergyStorage.BLOCK, be.getBlockPos(), side);
            if (existing != null) return null;                    // 已有主人 → 让位
        } finally {
            DEFER_QUERY.set(false);
        }
        return new ContainerEnergyStorage(be);
    }

    private static final ThreadLocal<Boolean> DEFER_QUERY = ThreadLocal.withInitial(() -> false);

    // ── 物品访问解析（模组兼容面） ──

    /**
     * 解析容器物品访问器：ItemHandler.BLOCK 能力优先（原版自动注册 + 模组自行注册），
     * WorldlyContainerHolder 次之，最后 InvWrapper 兜底（原版 Container）。
     * 统一走兼容面 → 双箱合并 handler、模组容器、sided 语义全部继承。
     */
    private static IItemHandler resolveItems(Level level, BlockPos pos, @Nullable Direction side) {
        var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
        if (handler != null) return handler;
        var state = level.getBlockState(pos);
        if (state.getBlock() instanceof WorldlyContainerHolder holder) {
            return new InvWrapper(holder.getContainer(state, level, pos));
        }
        if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container container) {
            return new InvWrapper(container);
        }
        return null;
    }

    // ── IEnergyStorage ──

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        if (toExtract <= 0) return 0;
        IItemHandler items = resolveItems(be.getLevel(), be.getBlockPos(), null);
        if (items == null) return 0;
        return (int) (extract(items,
            (long) toExtract * 1000L, simulate, be::setChanged) / 1000L);
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (toReceive <= 0) return 0;
        IItemHandler items = resolveItems(be.getLevel(), be.getBlockPos(), null);
        if (items == null) return 0;
        return (int) (receive(items,
            (long) toReceive * 1000L, simulate, be::setChanged) / 1000L);
    }

    @Override
    public boolean canExtract() {
        return true;
    }

    @Override
    public boolean canReceive() {
        return true;   // 外部电源可对容器充电（充入各铜灯堆）
    }

    @Override
    public int getEnergyStored() {
        IItemHandler items = resolveItems(be.getLevel(), be.getBlockPos(), null);
        if (items == null) return 0;
        long m = 0;
        for (int i = 0; i < items.getSlots(); i++) {
            ItemStack stack = items.getStackInSlot(i);
            if (isBulb(stack)) {
                m += LivingItemManager.getWaxedBulbData(stack).totalChargeMilliFe(stack.getCount());
            }
        }
        // int 收窄 clamp：IEnergyStorage 公约是 int FE（上限 ~21.4 亿），超大堆叠容器
        // （count ≥ 2,148 盏 × 1M FE）下真值越界——clamp 而非回绕（回绕负数会让
        // 外部 mod 的容量缺口判定错乱）。语义 = 「至少 21.4 亿 FE」；内部 long 账本无损。
        return (int) Math.min(m / 1000L, Integer.MAX_VALUE);
    }

    @Override
    public int getMaxEnergyStored() {
        IItemHandler items = resolveItems(be.getLevel(), be.getBlockPos(), null);
        if (items == null) return 0;
        long m = 0;
        for (int i = 0; i < items.getSlots(); i++) {
            ItemStack stack = items.getStackInSlot(i);
            if (isBulb(stack)) {
                m += LivingWaxedBulbData.totalCapacityMilliFe(stack.getCount());
            }
        }
        return (int) Math.min(m / 1000L, Integer.MAX_VALUE);   // 同上：clamp 到 int 公约上限
    }

    private static boolean isBulb(ItemStack stack) {
        // 仅活化的涂蜡铜灯参与能源系统（取消活化 = 普通物品，电量保留但不进出）
        //
        // 短路顺序很重要：isWaxedBulb 是 4 次 Item 引用比较（纳秒级、无内存访问），
        // isLivingItem 要查 DataComponentMap（PatchedDataComponentMap →
        // Reference2ObjectArrayMap 线性扫描，且是随机内存访问 = cache miss）。
        // 容器里绝大多数槽位不是铜灯，先做便宜的判断能省掉几乎全部组件查询。
        // 热路径：Flux Networks 等外部电力 mod 每 tick 高频调用 receiveEnergy，
        // 每次都全量扫所有槽位 —— 这里是主要的放大点。
        return !stack.isEmpty() && LivingWaxedCopperFunction.isWaxedBulb(stack.getItem())
            && LivingItemManager.isLivingItem(stack);
    }

    // ── 充电核心（mFE）──

    /**
     * 充电：外部来的电按「剩余容量比例」分配入各铜灯堆
     * （每盏 q += share/count 向下取整，零头保守丢弃），受每盏容量 C 上限。
     * 有实际充入时回调 {@code onChanged}（落盘持久化）。
     */
    static long receive(IItemHandler items,
                        long wantMilliFe, boolean simulate, @Nullable Runnable onChanged) {
        int slots = items.getSlots();
        // 单遍收集：只取一次 getStackInSlot，把铜灯堆的引用与剩余容量留在数组里，
        // 后续「比例分配」与「零头回收」两遍全部走数组（非铜灯槽位保持 null）。
        //
        // 原实现把同一批槽位取了 3 遍（收集 / 分配 / leftover 每轮），每遍都是
        // SidedInvWrapper → Container.getItem → NonNullList.get 的随机内存访问。
        // 外部电力 mod（Flux Networks）每 tick 高频调用 receiveEnergy，spark 实测
        // 60s 窗口内本方法独占 19s（服务端线程 98.79%），其中 81% 落在 getStackInSlot 上。
        //
        // 只多分配 stacks[] 一个数组（比原实现的 remaining[] 多一个），不引入任何
        // 跨调用状态——本方法保持无状态，语义与原实现逐一对应。
        ItemStack[] stacks = new ItemStack[slots];
        long[] remaining = new long[slots];
        long totalRemaining = 0;
        for (int i = 0; i < slots; i++) {
            ItemStack stack = items.getStackInSlot(i);
            if (!isBulb(stack)) continue;
            int count = stack.getCount();
            long rem = PowerMath.BULB_UNIT_CAPACITY_MFE * count
                - LivingItemManager.getWaxedBulbData(stack).totalChargeMilliFe(count);
            stacks[i] = stack;
            remaining[i] = rem;
            totalRemaining += rem;
        }
        if (totalRemaining <= 0) return 0;   // 全满（或无铜灯）

        // 整 FE 量化：机器支付多少 FE，铜灯就收多少 mFE×1000——杜绝取整零头凭空造电
        long accept = Math.min(wantMilliFe, totalRemaining);
        accept -= accept % 1000;
        if (accept <= 0) return 0;

        // 按剩余容量比例分配（两遍式：先算各堆份额，再统一落账）
        long distributed = 0;
        boolean changed = false;
        for (int i = 0; i < slots; i++) {
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
                LivingWaxedBulbData data = LivingItemManager.getWaxedBulbData(stack);
                LivingItemManager.setWaxedBulbData(stack,
                    data.withChargeMilliFe(data.chargeMilliFe() + perLamp));
                changed = true;
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
            for (int i = 0; i < slots && leftover > 0; i++) {
                ItemStack stack = stacks[i];
                if (stack == null) continue;
                int count = stack.getCount();
                if (count > leftover) continue;   // 完整步进保护
                long q = LivingItemManager.getWaxedBulbData(stack).chargeMilliFe();
                if (q >= PowerMath.BULB_UNIT_CAPACITY_MFE) continue;
                if (!simulate) {
                    LivingItemManager.setWaxedBulbData(stack,
                        LivingItemManager.getWaxedBulbData(stack).withChargeMilliFe(q + 1));
                    changed = true;
                }
                distributed += count;             // 记账 = 实充（count mFE）
                leftover -= count;
                progressed = true;
            }
            if (!progressed) break;
        }

        if (changed && onChanged != null && !simulate) onChanged.run();
        // 声明口径 = accept（整 FE）：实充 ≤ accept，残余 ≤ 最小堆 count−1 mFE 保守丢弃，
        // 灯实收恒 ≤ 支付方按返回值记账的量——只许损耗、不许凭空产生。
        return accept;
    }

    // ── 取电核心（mFE）──

    /**
     * 取电：逐堆扣铜灯（每盏等量，余数跨 FE 边界时向上取整以避免灯里有电但抽不出整 FE，
     * 代价为每次取电最多多拿 {@code count−1} mFE）。
     * 有实际扣减时回调 {@code onChanged}（落盘持久化）。
     */
    static long extract(IItemHandler items,
                        long wantMilliFe, boolean simulate, @Nullable Runnable onChanged) {
        long got = 0;
        long remaining = wantMilliFe;

        boolean changed = false;
        for (int i = 0; i < items.getSlots() && remaining > 0; i++) {
            ItemStack stack = items.getStackInSlot(i);
            if (!isBulb(stack)) continue;
            int count = stack.getCount();
            long q = LivingItemManager.getWaxedBulbData(stack).chargeMilliFe();
            long takeTotal = Math.min(q * count, remaining);
            long perLamp = takeTotal / count;
            // 余数跨 FE 边界时向上取整，避免灯里有电但外部抽不出整 FE
            long remainder = takeTotal % count;
            if (remainder != 0 && perLamp < q) {
                long gotFloor = perLamp * count;
                long gotCeil = (perLamp + 1) * count;
                if (gotFloor / 1000 < gotCeil / 1000) {
                    perLamp++;
                }
            }
            if (perLamp <= 0) continue;
            if (!simulate) {
                LivingItemManager.setWaxedBulbData(stack,
                    LivingItemManager.getWaxedBulbData(stack).withChargeMilliFe(q - perLamp));
                changed = true;
            }
            got += perLamp * count;
            remaining -= perLamp * count;
        }

        if (changed && onChanged != null && !simulate) onChanged.run();
        return got;
    }
}