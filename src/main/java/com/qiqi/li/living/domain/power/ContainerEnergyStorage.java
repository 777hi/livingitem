package com.qiqi.li.living.domain.power;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerLivingItemHandler;

import javax.annotation.Nullable;

/**
 * 容器红电对外能量接口（§3.6 v17.5）。
 *
 * <p>取电顺序：先扣容器池现值，缺口按各铜灯堆存量逐堆扣（槽位顺序，每盏等量）。
 * 只出不进（canReceive = false）——发电是唯一能量来源，
 * 限流职责归用电侧（电池无限流）。</p>
 *
 * <p>池常态为空（每容器 tick 末结算入铜灯），因此实际取电几乎全部来自铜灯——
 * 这正是「铜灯 = 唯一储存」的设计语义。</p>
 */
public class ContainerEnergyStorage implements IEnergyStorage {

    private final BlockEntity be;

    public ContainerEnergyStorage(BlockEntity be) {
        this.be = be;
    }

    /**
     * 宽注册 provider 判定链（§3.6 v17.5，全部缓存安全）：
     * <ol>
     *   <li>非 Container BE → null（类型固定属性，方块替换自动失效）</li>
     *   <li>直接实现 IEnergyStorage 的 BE → null（让位，零重入成本）</li>
     *   <li>重入保护下查询 EnergyStorage.BLOCK：已有主人（模组机器自身储能）→
     *       null（让位——注册期定死的稳定属性）</li>
     *   <li>返回实例（内部自适应：无灯无池 → 电量 0，永不 null 切换 → 零失效隐患）</li>
     * </ol>
     */
    public static IEnergyStorage resolveProvider(BlockEntity be, @Nullable Direction side) {
        if (!(be instanceof Container)) return null;              // 非容器，类型稳定
        if (be instanceof IEnergyStorage) return null;            // 直接实现者让位
        if (be.getLevel() == null) return null;
        if (DEFER_QUERY.get()) return null;                       // 重入保护：内层查询摘除自己
        DEFER_QUERY.set(true);
        try {
            var existing = be.getLevel().getCapability(
                Capabilities.EnergyStorage.BLOCK, be.getBlockPos(), side);
            if (existing != null) return null;                    // 已有主人 → 让位
        } finally {
            DEFER_QUERY.set(false);
        }
        return new ContainerEnergyStorage(be);
    }

    private static final ThreadLocal<Boolean> DEFER_QUERY = ThreadLocal.withInitial(() -> false);

    private record Storage(ContainerPowerData power, List<ItemStack> bulbs) {}

    private Storage resolve() {
        if (be.getLevel() == null) return null;
        ContainerPowerData power =
            ContainerLivingItemHandler.getPowerDataByPos(be.getLevel(), be.getBlockPos());
        if (power == null) return null;
        List<ItemStack> bulbs = new ArrayList<>();
        if (be instanceof Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty() && LivingWaxedCopperFunction.isWaxedBulb(stack.getItem())) {
                    bulbs.add(stack);
                }
            }
        }
        return new Storage(power, bulbs);
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        if (toExtract <= 0) return 0;
        Storage storage = resolve();
        if (storage == null) return 0;
        return (int) (extract(storage.power(), storage.bulbs(),
            (long) toExtract * 1000L, simulate) / 1000L);
    }

    /**
     * 取电核心（mFE）：池优先 → 铜灯逐堆（槽位顺序，每盏等量，向下取整，零头保守丢弃）。
     *
     * @return 实际取出的 mFE
     */
    static long extract(ContainerPowerData power, List<ItemStack> bulbs, long wantMilliFe, boolean simulate) {
        long got = 0;

        long pool = power.getPoolMilliFe();
        long takePool = Math.min(pool, wantMilliFe);
        if (takePool > 0 && !simulate) {
            power.setPoolMilliFe(pool - takePool);
        }
        got += takePool;

        long remaining = wantMilliFe - got;
        for (ItemStack stack : bulbs) {
            if (remaining <= 0) break;
            int count = stack.getCount();
            long q = LivingItemManager.getWaxedBulbData(stack).chargeMilliFe();
            long takeTotal = Math.min(q * count, remaining);
            long perLamp = takeTotal / count;
            if (perLamp <= 0) continue;
            if (!simulate) {
                LivingItemManager.setWaxedBulbData(stack,
                    LivingItemManager.getWaxedBulbData(stack).withChargeMilliFe(q - perLamp));
            }
            got += perLamp * count;
            remaining -= perLamp * count;
        }
        return got;
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        return 0;   // 只出不进：发电是唯一能量来源
    }

    @Override
    public boolean canExtract() {
        return true;
    }

    @Override
    public boolean canReceive() {
        return false;
    }

    @Override
    public int getEnergyStored() {
        Storage storage = resolve();
        if (storage == null) return 0;
        long m = storage.power().getPoolMilliFe();
        for (ItemStack stack : storage.bulbs()) {
            m += LivingItemManager.getWaxedBulbData(stack).totalChargeMilliFe(stack.getCount());
        }
        return (int) (m / 1000L);
    }

    @Override
    public int getMaxEnergyStored() {
        Storage storage = resolve();
        if (storage == null) return 0;
        long m = storage.power().getPoolMilliFe();
        for (ItemStack stack : storage.bulbs()) {
            m += LivingWaxedBulbData.totalCapacityMilliFe(stack.getCount());
        }
        return (int) (m / 1000L);
    }
}
