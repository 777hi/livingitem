package com.qiqi.li.living.domain.power;
import com.qiqi.li.living.domain.power.LivingWaxedBulbData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        LivingWaxedBulbData.set(stack, new LivingWaxedBulbData(4_000));
        BulbItemEnergyStorage storage = new BulbItemEnergyStorage(stack);

        assertEquals(40, storage.extractEnergy(40, false));
        assertEquals(4_000 - 2_500, LivingWaxedBulbData.of(stack).chargeMilliFe());
        assertEquals(24, storage.getEnergyStored());   // 64 FE − 40 FE = 24 FE
    }

    @Test
    @DisplayName("充电：外部电源充 32 FE → 每盏加 2000 mFE")
    void charge_fromExternalSource() {
        ItemStack stack = bulb(16);
        BulbItemEnergyStorage storage = new BulbItemEnergyStorage(stack);

        assertEquals(32, storage.receiveEnergy(32, false));
        assertEquals(2_000, LivingWaxedBulbData.of(stack).chargeMilliFe());
    }

    @Test
    @DisplayName("⚠️ 未活化灯拿不到电池实例（2026-10-09 收紧）—— capability provider 返回 null")
    void notLiving_yieldsNoStorage() {
        // 收紧前：EnergyStorage.ITEM 按【原版物品】注册、provider 又不判活化
        // ⇒ 未活化灯也能被外部 mod 充放电，绕过「活化 = 进入能量系统」的门槛，
        // 且与容器路径（BulbBank.isBulb 要求 isLivingItem）不一致。
        // 收紧后：准入判据收在 BulbItemEnergyStorage.of()，LivingItem 只透传。
        ItemStack notLiving = new ItemStack(Items.WAXED_COPPER_BULB, 16);   // 刻意不 setLiving
        assertFalse(LivingItemManager.isLivingItem(notLiving));
        assertNull(BulbItemEnergyStorage.of(notLiving),
            "未活化灯不该提供能量能力 —— 否则外部 mod 能给「原版方块」充放电");

        assertNotNull(BulbItemEnergyStorage.of(bulb(16)), "活化灯照常提供能量能力");
    }

    @Test
    @DisplayName("充电 clamp：超过每盏容量 C 只收到剩余空间")
    void charge_clampedToCapacity() {
        ItemStack stack = bulb(16);
        LivingWaxedBulbData.set(stack,
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
        LivingWaxedBulbData.set(stack, new LivingWaxedBulbData(4_000));
        BulbItemEnergyStorage storage = new BulbItemEnergyStorage(stack);

        assertEquals(40, storage.extractEnergy(40, true));
        assertEquals(32, storage.receiveEnergy(32, true));
        assertEquals(4_000, LivingWaxedBulbData.of(stack).chargeMilliFe());
    }

    @Test
    @DisplayName("拆分守恒：64×q 拆成 54+10 → 每盏仍 q，总电量不变")
    void split_conservesCharge() {
        ItemStack full = bulb(64);
        LivingWaxedBulbData.set(full, new LivingWaxedBulbData(2_000));

        // 模拟原版拆分：组件被复制到两堆
        ItemStack halfA = full.copyWithCount(54);
        ItemStack halfB = full.copyWithCount(10);

        long total = LivingWaxedBulbData.of(halfA).totalChargeMilliFe(halfA.getCount())
            + LivingWaxedBulbData.of(halfB).totalChargeMilliFe(halfB.getCount());
        assertEquals(64L * 2_000, total);   // 128_000 = 拆分前总量 ✓
    }

    @Test
    @DisplayName("容量与读数：getEnergyStored / getMaxEnergyStored 随 count 线性")
    void storedAndMax_linearInCount() {
        ItemStack stack = bulb(16);
        LivingWaxedBulbData.set(stack, new LivingWaxedBulbData(500_000));

        BulbItemEnergyStorage storage = new BulbItemEnergyStorage(stack);
        assertEquals(8000, storage.getEnergyStored());          // 500_000 × 16 / 1000
        assertEquals(16000000, storage.getMaxEnergyStored());  // 1_000_000_000 × 16 / 1000（每盏 C=1M FE）
        assertTrue(storage.canExtract());
        assertTrue(storage.canReceive());
    }

    @Test
    @DisplayName("int 读数 clamp：2,148 盏满堆（2.148G FE > int 上限）→ 读数钳 Integer.MAX_VALUE 不回绕")
    void oversizedStack_readsClampToIntMax() {
        // 2,148 × 1M FE = 2,148,000,000 > 2,147,483,647（int 公约上限）——超大堆叠容器的可达场景
        ItemStack stack = bulb(2_148);
        LivingWaxedBulbData.set(stack,
            new LivingWaxedBulbData(PowerMath.BULB_UNIT_CAPACITY_MFE));   // 满堆

        BulbItemEnergyStorage storage = new BulbItemEnergyStorage(stack);
        // clamp 而非回绕：修复前 (int) 强转得 ≈ -2,146,967,296（符号位顶 1 → 负数，
        // 外部 mod 的容量缺口判定错乱）；修复后诚实声明「至少 21.4 亿 FE」
        assertEquals(Integer.MAX_VALUE, storage.getEnergyStored());
        assertEquals(Integer.MAX_VALUE, storage.getMaxEnergyStored());
    }
}