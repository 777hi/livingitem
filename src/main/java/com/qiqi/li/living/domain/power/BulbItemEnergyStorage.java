package com.qiqi.li.living.domain.power;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.energy.IEnergyStorage;
import com.qiqi.li.living.api.LivingItemManager;

/**
 * 涂蜡铜灯物品能量接口 —— 通用电池（双向，§3.6 v17.5）。
 *
 * <p>每盏等量充/放（q ± Δ/count，向下取整），拆分/合并/搬运天然守恒。
 * 充电受每盏容量 C 限制；无出身论——外部充的电与红电发的电混存不分来源。</p>
 */
public class BulbItemEnergyStorage implements IEnergyStorage {

    private final ItemStack stack;

    public BulbItemEnergyStorage(ItemStack stack) {
        this.stack = stack;
    }

    private long chargeMilliFe() {
        return LivingItemManager.getWaxedBulbData(stack).chargeMilliFe();
    }

    private void setChargeMilliFe(long chargeMilliFe) {
        LivingItemManager.setWaxedBulbData(stack,
            LivingItemManager.getWaxedBulbData(stack).withChargeMilliFe(chargeMilliFe));
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        if (toExtract <= 0) return 0;
        long want = (long) toExtract * 1000L;
        long take = Math.min(chargeMilliFe() * stack.getCount(), want);
        long perLamp = take / stack.getCount();
        if (perLamp <= 0) return 0;
        if (!simulate) {
            setChargeMilliFe(chargeMilliFe() - perLamp);
        }
        return (int) (perLamp * stack.getCount() / 1000L);
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (toReceive <= 0) return 0;
        long want = (long) toReceive * 1000L;
        long space = PowerMath.BULB_UNIT_CAPACITY_MFE * stack.getCount() - chargeMilliFe() * stack.getCount();
        long accept = Math.min(want, space);
        long perLamp = accept / stack.getCount();
        if (perLamp <= 0) return 0;
        if (!simulate) {
            setChargeMilliFe(chargeMilliFe() + perLamp);
        }
        return (int) (perLamp * stack.getCount() / 1000L);
    }

    @Override
    public int getEnergyStored() {
        return (int) (chargeMilliFe() * stack.getCount() / 1000L);
    }

    @Override
    public int getMaxEnergyStored() {
        return (int) (PowerMath.BULB_UNIT_CAPACITY_MFE * stack.getCount() / 1000L);
    }

    @Override
    public boolean canExtract() {
        return true;
    }

    @Override
    public boolean canReceive() {
        return true;   // 通用电池：双向开放（§3.6 v17.5）
    }
}
