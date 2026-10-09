package com.qiqi.li.living.domain.power;
import com.qiqi.li.living.domain.power.LivingWaxedBulbData;

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
        return LivingWaxedBulbData.of(stack).chargeMilliFe();
    }

    private void setChargeMilliFe(long chargeMilliFe) {
        LivingWaxedBulbData.set(stack,
            LivingWaxedBulbData.of(stack).withChargeMilliFe(chargeMilliFe));
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        if (toExtract <= 0) return 0;
        long totalCharge = chargeMilliFe() * stack.getCount();
        if (totalCharge <= 0) return 0;

        // 不足 1 FE 的零头：清空，返回 0
        if (totalCharge < 1000L) {
            if (!simulate) {
                setChargeMilliFe(0);
            }
            return 0;
        }

        long want = (long) toExtract * 1000L;
        long take = Math.min(totalCharge, want);
        long perLamp = take / stack.getCount();
        if (perLamp <= 0) return 0;
        if (!simulate) {
            long newCharge = chargeMilliFe() - perLamp;
            // 清空不足 1 FE 的零头，避免取不干净
            if (newCharge * stack.getCount() < 1000L) {
                newCharge = 0;
            }
            setChargeMilliFe(newCharge);
        }
        return (int) (perLamp * stack.getCount() / 1000L);
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (toReceive <= 0) return 0;
        // 算法收归 BulbBank（2026-10-09 power 收口 步骤 2 第 3 步）：单堆时「按剩余容量比例
        // 分配」退化为「全部」，与容器充电同源 ⇒ 不再各自维护一份 perLamp 计算。
        // ⚠️ 口径 = ANY_MOVEMENT（不量化、不做零头回收）；返回值沿用本接口的
        // 「实充 ceil」口径（见下），故取 distributedMilliFe() 而非 deposit 的声明值。
        // ⚠️ BulbBank.of 刻意不过滤 isBulb —— 本 capability 按原版物品注册（含未活化灯）。
        BulbBank bank = BulbBank.of(stack);
        bank.deposit((long) toReceive * 1000L, simulate, BulbBank.FePolicy.ANY_MOVEMENT);
        // 2026-09-09 口径修正（保留）：返回「实充的 ceil」（= 支付方将扣的账），实充
        // perLamp×count 可能比请求少 count−1 mFE（按盏向下取整残余）——报账 ≥ 实充，
        // 差额损耗向（防往返凭空造电，与 ContainerEnergyStorage.receive 同族修复，
        // 见 RoundTripConservationIT）。
        return (int) Math.min(toReceive, (bank.distributedMilliFe() + 999L) / 1000L);
    }

    @Override
    public int getEnergyStored() {
        // int 收窄 clamp（与 ContainerEnergyStorage 同口径）：超大堆叠（count ≥ 2,148 × 1M FE）
        // 真值越 int 公约 → clamp 语义「至少 21.4 亿 FE」，内部 long 账本无损
        return (int) Math.min(chargeMilliFe() * stack.getCount() / 1000L, Integer.MAX_VALUE);
    }

    @Override
    public int getMaxEnergyStored() {
        return (int) Math.min(PowerMath.BULB_UNIT_CAPACITY_MFE * stack.getCount() / 1000L,
            Integer.MAX_VALUE);
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