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
import com.qiqi.li.living.container.ContainerLivingItemHandler;

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
        return (int) (m / 1000L);
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
        return (int) (m / 1000L);
    }

    private static boolean isBulb(ItemStack stack) {
        // 仅活化的涂蜡铜灯参与能源系统（取消活化 = 普通物品，电量保留但不进出）
        return !stack.isEmpty() && LivingItemManager.isLivingItem(stack)
            && LivingWaxedCopperFunction.isWaxedBulb(stack.getItem());
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
        long[] remaining = new long[slots];
        long totalRemaining = 0;
        for (int i = 0; i < slots; i++) {
            ItemStack stack = items.getStackInSlot(i);
            if (!isBulb(stack)) continue;
            remaining[i] = PowerMath.BULB_UNIT_CAPACITY_MFE * stack.getCount()
                - LivingItemManager.getWaxedBulbData(stack).totalChargeMilliFe(stack.getCount());
            totalRemaining += remaining[i];
        }
        if (totalRemaining <= 0) return 0;   // 全满

        // 整 FE 量化：机器支付多少 FE，铜灯就收多少 mFE×1000——杜绝取整零头凭空造电
        long accept = Math.min(wantMilliFe, totalRemaining);
        accept -= accept % 1000;
        if (accept <= 0) return 0;

        // 按剩余容量比例分配（两遍式：先算各堆份额，再统一落账）
        long distributed = 0;
        boolean changed = false;
        for (int i = 0; i < slots; i++) {
            if (remaining[i] <= 0) continue;
            long share = accept * remaining[i] / totalRemaining;
            ItemStack stack = items.getStackInSlot(i);
            int count = stack.getCount();
            long perLamp = share / count;
            if (perLamp <= 0) continue;
            if (!simulate) {
                LivingItemManager.setWaxedBulbData(stack,
                    LivingItemManager.getWaxedBulbData(stack).withChargeMilliFe(
                        LivingItemManager.getWaxedBulbData(stack).chargeMilliFe() + perLamp));
                changed = true;
            }
            distributed += perLamp * count;
        }

        // 零头回收：各堆按盏取整的残余，1 mFE 逐灯补入有空间的灯（不凭空产生、不浪费）
        long leftover = accept - distributed;
        while (leftover > 0) {
            boolean progressed = false;
            for (int i = 0; i < slots && leftover > 0; i++) {
                ItemStack stack = items.getStackInSlot(i);
                if (!isBulb(stack)) continue;
                long q = LivingItemManager.getWaxedBulbData(stack).chargeMilliFe();
                if (q >= PowerMath.BULB_UNIT_CAPACITY_MFE) continue;
                if (!simulate) {
                    LivingItemManager.setWaxedBulbData(stack,
                        LivingItemManager.getWaxedBulbData(stack).withChargeMilliFe(q + 1));
                    changed = true;
                }
                distributed++;
                leftover--;
                progressed = true;
            }
            if (!progressed) break;
        }

        if (changed && onChanged != null && !simulate) onChanged.run();
        return distributed;   // = accept（整 FE），与机器支付严格相等
    }

    // ── 取电核心（mFE）──

    /**
     * 取电：逐堆扣铜灯（每盏等量，向下取整，零头保守丢弃）。
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
