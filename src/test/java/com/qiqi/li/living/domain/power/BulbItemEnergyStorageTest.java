package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 铜灯通用电池测试（§3.6 v17.5：双向 + 无出身论）。
 *
 * <p>每盏等量充/放 → 拆分/合并/搬运守恒；充电受每盏容量 C 限制。</p>
 */
class BulbItemEnergyStorageTest {

    private static ItemStack bulb(int count) {
        ItemStack stack = new ItemStack(Items.WAXED_COPPER_BULB, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    @Test
    @DisplayName("放电：16 盏堆抽 40 FE → 每盏扣 2500 mFE")
    void discharge_perLamp() {
        ItemStack stack = bulb(16);
        LivingItemManager.setWaxedBulbData(stack, new LivingWaxedBulbData(4_000));
        BulbItemEnergyStorage storage = new BulbItemEnergyStorage(stack);

        assertEquals(40, storage.extractEnergy(40, false));
        assertEquals(4_000 - 2_500, LivingItemManager.getWaxedBulbData(stack).chargeMilliFe());
        assertEquals(24, storage.getEnergyStored());   // 64 FE − 40 FE = 24 FE
    }

    @Test
    @DisplayName("充电：外部电源充 32 FE → 每盏加 2000 mFE")
    void charge_fromExternalSource() {
        ItemStack stack = bulb(16);
        BulbItemEnergyStorage storage = new BulbItemEnergyStorage(stack);

        assertEquals(32, storage.receiveEnergy(32, false));
        assertEquals(2_000, LivingItemManager.getWaxedBulbData(stack).chargeMilliFe());
    }

    @Test
    @DisplayName("充电 clamp：超过每盏容量 C 只收到剩余空间")
    void charge_clampedToCapacity() {
        ItemStack stack = bulb(16);
        LivingItemManager.setWaxedBulbData(stack,
            new LivingWaxedBulbData(PowerMath.BULB_UNIT_CAPACITY_MFE - 8_000));
        BulbItemEnergyStorage storage = new BulbItemEnergyStorage(stack);

        // 每盏剩余 8000 mFE = 8 FE → 16 盏共 128 FE 可收
        assertEquals(128, storage.receiveEnergy(1_000, false));
        assertEquals(0, storage.receiveEnergy(1, false));   // 已满
    }

    @Test
    @DisplayName("simulate：不改状态")
    void simulate_leavesStateIntact() {
        ItemStack stack = bulb(16);
        LivingItemManager.setWaxedBulbData(stack, new LivingWaxedBulbData(4_000));
        BulbItemEnergyStorage storage = new BulbItemEnergyStorage(stack);

        assertEquals(40, storage.extractEnergy(40, true));
        assertEquals(32, storage.receiveEnergy(32, true));
        assertEquals(4_000, LivingItemManager.getWaxedBulbData(stack).chargeMilliFe());
    }

    @Test
    @DisplayName("拆分守恒：64×q 拆成 54+10 → 每盏仍 q，总电量不变")
    void split_conservesCharge() {
        ItemStack full = bulb(64);
        LivingItemManager.setWaxedBulbData(full, new LivingWaxedBulbData(2_000));

        // 模拟原版拆分：组件被复制到两堆
        ItemStack halfA = full.copyWithCount(54);
        ItemStack halfB = full.copyWithCount(10);

        long total = LivingItemManager.getWaxedBulbData(halfA).totalChargeMilliFe(halfA.getCount())
            + LivingItemManager.getWaxedBulbData(halfB).totalChargeMilliFe(halfB.getCount());
        assertEquals(64L * 2_000, total);   // 128_000 = 拆分前总量 ✓
    }

    @Test
    @DisplayName("容量与读数：getEnergyStored / getMaxEnergyStored 随 count 线性")
    void storedAndMax_linearInCount() {
        ItemStack stack = bulb(16);
        LivingItemManager.setWaxedBulbData(stack, new LivingWaxedBulbData(50_000));

        BulbItemEnergyStorage storage = new BulbItemEnergyStorage(stack);
        assertEquals(800, storage.getEnergyStored());        // 50_000 × 16 / 1000
        assertEquals(1_600, storage.getMaxEnergyStored());   // 100_000 × 16 / 1000
        assertTrue(storage.canExtract());
        assertTrue(storage.canReceive());
    }
}
