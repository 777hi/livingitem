package com.qiqi.li.living.domain.power;

import java.util.ArrayList;
import java.util.List;

import com.mojang.logging.LogUtils;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.util.PerItemLedger;
import org.slf4j.Logger;

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
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.TickableContainerContext;

/**
 * 容器红电对外能量接口（§3.6 v17.5）。
 *
 * <p>取电：从容器内的铜灯逐堆均分扣账，整 FE 精确交付，取整差额只作损耗——
 * 无容器池，铜灯是唯一储存。双向：外部电源也能对容器充电（{@link #canReceive()} = true，
 * 充入各铜灯堆）；发电仍是本系统唯一的<b>来源</b>，限流职责归用电侧。</p>
 *
 * <p>物品访问走 {@code ItemHandler.BLOCK} 兼容面；外部充放要求槽位支持组件精确写回，
 * 写回确认后才报账，不支持的槽只提供存量读数。随机战利品容器（未开箱）跳过。</p>
 *
 * <p>⚠️ <b>能力存在性 = 容器里真有活涂蜡铜灯</b>（{@link #resolveProvider}）：线缆类模组
 * 按「能力是否非 null」判邻居是不是储能，恒发实例会把任何 BE 认成储能。</p>
 */
public class ContainerEnergyStorage implements IEnergyStorage {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final BlockEntity be;

    public ContainerEnergyStorage(BlockEntity be) {
        this.be = be;
    }

    /**
     * 宽注册 provider 判定链（§3.6 v17.5，全部缓存安全）：
     * <ol>
     *   <li>随机战利品容器（未开箱）→ null（与 tick 机制同款跳过，类型稳定）</li>
     *   <li>直接实现 IEnergyStorage 的 BE → null（让位，零重入成本）</li>
     *   <li>客户端 / {@code level == null} → null（容器级数据只在服务端）</li>
     *   <li><b>当前没有活涂蜡铜灯 → null</b>（能力存在性 = 真有电池资产）</li>
     *   <li>重入保护下查询 EnergyStorage.BLOCK：已有主人（模组机器自身储能）→
     *       null（让位——注册期定死的稳定属性）</li>
     *   <li>返回实例</li>
     * </ol>
     *
     * <p>🔴 <b>④ 不是优化</b>（2026-10-31 对齐流体 §10.4）：线缆类模组判「邻居能不能
     * 存/发/收电」只看能力是否非 null（Flux {@code ForgeEnergyConnector#hasCapability}、
     * Mekanism {@code acceptorCache}），非 null 就登记成 acceptor / 连接点。
     * 此前宽注册给<b>所有</b> BE 都发实例（无灯 ⇒ 0 电量但 {@code canReceive=true}）
     * ⇒ 任何箱子 / 机器都被线缆认成储能，每拍白跑一轮存取模拟。</p>
     *
     * <p>存在性由容器的电力 tick 维护（{@link #announcePresence}），变化时
     * {@code Level#invalidateCapabilities} 一次。</p>
     */
    public static IEnergyStorage resolveProvider(BlockEntity be, @Nullable Direction side) {
        if (be instanceof RandomizableContainer rc && rc.getLootTable() != null) return null;
        if (be instanceof IEnergyStorage) return null;            // 直接实现者让位
        Level level = be.getLevel();
        if (level == null || level.isClientSide()) return null;   // 客户端没有容器级数据
        if (!hasExposedBattery(level, be.getBlockPos())) return null;   // 无活铜灯 ⇒ 不报名
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

    // ── 能力存在性（= 容器里真有活涂蜡铜灯）────────────────────

    /**
     * 该位置的容器当前是否对外报储能（读电力账本的通告标志，O(1)，<b>不扫容器</b>）。
     *
     * <p>标志由 {@link #announcePresence} 在边沿处维护，读取方只信任它 ——
     * 能力查询是线缆的热路径，不能每次扫一遍槽位。</p>
     */
    public static boolean hasExposedBattery(Level level, BlockPos pos) {
        TickableContainerContext ctx = ContainerLivingItemHandler.resolveContextAt(level, pos, null);
        if (ctx == null) return false;
        ContainerPowerData power = ctx.peekContainerData(ContainerPowerData.KEY);
        return power != null && power.isCapabilityAdvertised();
    }

    /** 功能槽位视图里是否有活涂蜡铜灯（{@code entries} 已由框架筛成涂蜡铜块家族）。 */
    static boolean hasLivingBulb(List<LivingItemFunction.SlotEntry> entries) {
        for (LivingItemFunction.SlotEntry entry : entries) {
            if (BulbBank.isBulb(entry.stack())) return true;
        }
        return false;
    }

    /** 容器全部槽位里是否有活涂蜡铜灯（负边沿兜底用，仅在正在通告时调用）。 */
    static boolean hasLivingBulb(ContainerContext ctx) {
        int size = ctx.getSize();
        for (int i = 0; i < size; i++) {
            if (BulbBank.isBulb(ctx.getItem(i))) return true;
        }
        return false;
    }

    /**
     * 通告能力存在性（<b>边沿触发</b>）：值没变就直接返回。
     *
     * <p>变化时写标志 + 对容器的所有方块位置 {@code invalidateCapabilities} ——
     * 线缆把能力结果缓进 {@code BlockCapabilityCache}，不通知就不会重查
     * （「先接线、后放灯」会永远停在无能力分支）。</p>
     */
    static void announcePresence(ContainerContext ctx, ContainerPowerData powerData, boolean exposed) {
        if (powerData.isCapabilityAdvertised() == exposed) return;
        powerData.setCapabilityAdvertised(exposed);
        Level level = ctx.getLevel();
        if (level == null) return;   // 纯 Java 单测 / 无世界：只维护标志
        for (BlockPos pos : ctx.getAssociatedBlockPositions()) {
            level.invalidateCapabilities(pos);
        }
    }

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

    @Nullable
    private TickableContainerContext resolveContext() {
        Level level = be.getLevel();
        if (level == null || level.isClientSide()) return null;
        IItemHandler items = resolveItems(level, be.getBlockPos(), null);
        return items == null ? null : ContainerLivingItemHandler.resolveContextAt(level, be.getBlockPos(), items);
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        if (toExtract <= 0) return 0;
        ContainerContext ctx = resolveContext();
        return ctx == null ? 0 : (int) (extract(ctx, (long) toExtract * 1000L, simulate, null) / 1000L);
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (toReceive <= 0) return 0;
        ContainerContext ctx = resolveContext();
        return ctx == null ? 0 : (int) (receive(ctx, (long) toReceive * 1000L, simulate, null) / 1000L);
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
            if (BulbBank.isBulb(stack)) {
                m += LivingWaxedBulbData.of(stack).totalChargeMilliFe(stack.getCount());
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
            if (BulbBank.isBulb(stack)) {
                m += LivingWaxedBulbData.totalCapacityMilliFe(stack.getCount());
            }
        }
        return (int) Math.min(m / 1000L, Integer.MAX_VALUE);   // 同上：clamp 到 int 公约上限
    }


    // ── 充电核心（mFE）──


    /** 无世界测试接缝，仍走与 BLOCK 相同的组件提交路径。 */
    static long receive(IItemHandler items,
                        long wantMilliFe, boolean simulate, @Nullable Runnable onChanged) {
        return receive(new SimpleContainerContext(items), wantMilliFe, simulate, onChanged);
    }

    private record BulbSlot(int slot, ItemStack before, ItemStack working) {}

    /** 在副本上规划比例充电，提交确认后才报账；失败保留已确认前缀。 */
    private static long receive(ContainerContext ctx,
                                long wantMilliFe, boolean simulate, @Nullable Runnable onChanged) {
        if (wantMilliFe < 1000L) return 0;
        int size = ctx.getSize();
        BulbSlot[] slots = new BulbSlot[size];
        BulbBank bank = BulbBank.scan(size, slot -> {
            if (!ctx.supportsItemComponentWrite(slot)) return ItemStack.EMPTY;
            ItemStack before = ctx.getItem(slot).copy();
            if (!BulbBank.isBulb(before)) return ItemStack.EMPTY;
            ItemStack working = before.copy();
            slots[slot] = new BulbSlot(slot, before, working);
            return working;
        });
        long declared = bank.deposit(wantMilliFe, false, BulbBank.FePolicy.FLOOR_WHOLE_FE);
        if (simulate || declared <= 0) return declared;

        long confirmedCharge = 0;
        boolean complete = true;
        for (BulbSlot entry : slots) {
            if (entry == null || ItemStack.matches(entry.before(), entry.working())) continue;
            if (!ctx.commitItemComponents(entry.slot(), entry.before(), entry.working())) {
                complete = false;
                break;
            }
            long delta = LivingWaxedBulbData.of(entry.working()).chargeMilliFe()
                - LivingWaxedBulbData.of(entry.before()).chargeMilliFe();
            confirmedCharge += delta * entry.before().getCount();
        }
        if (confirmedCharge > 0 && onChanged != null) onChanged.run();
        if (complete) return declared;
        // 已确认实充向上量化，支付方不得少付已存入的电。
        long rounded = (confirmedCharge / 1000L + (confirmedCharge % 1000L == 0 ? 0 : 1)) * 1000L;
        return Math.min(declared, rounded);
    }

    // ── 取电核心（mFE）──

    /** 无世界测试接缝，仍走与 BLOCK 相同的组件提交路径。 */
    static long extract(IItemHandler items,
                        long wantMilliFe, boolean simulate, @Nullable Runnable onChanged) {
        return extract(new SimpleContainerContext(items), wantMilliFe, simulate, onChanged);
    }

    /** 聚合选定整 FE 交付，只有成功提交的计划份额才计入交付预算。 */
    private static long extract(ContainerContext ctx,
                                long wantMilliFe, boolean simulate, @Nullable Runnable onChanged) {
        long request = wantMilliFe / 1000L * 1000L;
        if (request <= 0) return 0;

        List<BulbSlot> slots = new ArrayList<>();
        long available = 0;
        int size = ctx.getSize();
        for (int slot = 0; slot < size && available < request; slot++) {
            if (!ctx.supportsItemComponentWrite(slot)) continue;
            ItemStack before = ctx.getItem(slot).copy();
            if (!BulbBank.isBulb(before)) continue;
            long total = availableCharge(before);
            if (total <= 0) continue;
            slots.add(new BulbSlot(slot, before, before.copy()));
            available += Math.min(total, request - available);
        }
        long delivery = available / 1000L * 1000L;
        if (simulate || delivery <= 0) return delivery;

        long confirmed = 0;
        for (BulbSlot entry : slots) {
            long remaining = delivery - confirmed;
            if (remaining <= 0) break;
            long take = Math.min(availableCharge(entry.before()), remaining);
            LivingWaxedBulbData data = LivingWaxedBulbData.of(entry.before());
            var withdrawal = PerItemLedger.withdrawCeil(data.chargeMilliFe(), entry.before().getCount(), take);
            LivingWaxedBulbData.set(entry.working(), data.withChargeMilliFe(withdrawal.newQ()));
            if (!ctx.commitItemComponents(entry.slot(), entry.before(), entry.working())) break;
            confirmed += take;
        }
        if (confirmed > 0 && onChanged != null) onChanged.run();
        long reported = confirmed / 1000L * 1000L;
        if (reported != confirmed) {
            LOGGER.warn("铜灯取电部分提交：已确认 {} mFE，仅交付 {} mFE，异常碎片损耗 {} mFE",
                confirmed, reported, confirmed - reported);
        }
        return reported;
    }

    /** 正存量的饱和乘积；预算仍以调用方请求为上界。 */
    private static long availableCharge(ItemStack stack) {
        int count = stack.getCount();
        long q = LivingWaxedBulbData.of(stack).chargeMilliFe();
        if (count <= 0 || q <= 0) return 0;
        return q > Long.MAX_VALUE / count ? Long.MAX_VALUE : q * count;
    }
}