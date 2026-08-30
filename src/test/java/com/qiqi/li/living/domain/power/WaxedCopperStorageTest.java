package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;

/**
 * 阶段四储能测试 —— 容器池（收集器）+ 涂蜡铜灯（纯容器）。
 *
 * <p>模型语义见 docs/红电系统.md §3.6（v17.5）：
 * 发电入池 → 用电侧先取池 → tick 末剩余按剩余容量比例存入铜灯 → 无铜灯清零。
 * 电量按「每盏」存（q），拆分/合并/搬运天然守恒。</p>
 */
class WaxedCopperStorageTest {

    private static ItemStack bulb(int count) {
        ItemStack stack = new ItemStack(Items.WAXED_COPPER_BULB, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static ItemStack generator(int count) {
        ItemStack stack = new ItemStack(Items.WAXED_COPPER_BLOCK, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    @Test
    @DisplayName("池结算：剩余电按剩余容量比例存入铜灯堆，池清零")
    void settle_distributesIntoBulbs() {
        ContainerPowerData power = new ContainerPowerData();
        power.setPoolMilliFe(64_000);
        ItemStack stack = bulb(16);   // 容量 16 × 100_000 = 1_600_000 mFE

        LivingWaxedCopperFunction.settleBulbs(power,
            List.of(new LivingItemFunction.SlotEntry(0, stack),
                new LivingItemFunction.SlotEntry(1, generator(4))));

        // share = 64000（唯一有剩余容量的堆），perLamp = 64000/16 = 4000
        assertEquals(4000, LivingItemManager.getWaxedBulbData(stack).chargeMilliFe());
        assertEquals(0, power.getPoolMilliFe());
    }

    @Test
    @DisplayName("无铜灯：池清零（电凭空消失——发电必须被消费或储存）")
    void settle_noBulbs_discards() {
        ContainerPowerData power = new ContainerPowerData();
        power.setPoolMilliFe(64_000);

        LivingWaxedCopperFunction.settleBulbs(power,
            List.of(new LivingItemFunction.SlotEntry(0, generator(4))));

        assertEquals(0, power.getPoolMilliFe());
        assertTrue(power.getEmaPowerRe() >= 0);
    }

    @Test
    @DisplayName("铜灯已满：池清零（显性浪费，tooltip 显示已满）")
    void settle_fullBulb_discards() {
        ContainerPowerData power = new ContainerPowerData();
        power.setPoolMilliFe(64_000);
        ItemStack stack = bulb(16);
        LivingItemManager.setWaxedBulbData(stack,
            new LivingWaxedBulbData(PowerMath.BULB_UNIT_CAPACITY_MFE));   // 每盏已满

        LivingWaxedCopperFunction.settleBulbs(power,
            List.of(new LivingItemFunction.SlotEntry(0, stack),
                new LivingItemFunction.SlotEntry(1, generator(4))));

        assertEquals(PowerMath.BULB_UNIT_CAPACITY_MFE,
            LivingItemManager.getWaxedBulbData(stack).chargeMilliFe());
        assertEquals(0, power.getPoolMilliFe());
    }

    @Test
    @DisplayName("取电：池优先 → 缺口逐堆扣铜灯（每盏等量），守恒")
    void extract_poolFirstThenBulbs() {
        ContainerPowerData power = new ContainerPowerData();
        power.setPoolMilliFe(50_000);
        ItemStack b1 = bulb(16);
        LivingItemManager.setWaxedBulbData(b1, new LivingWaxedBulbData(4_000));   // 堆总量 64000
        ItemStack b2 = bulb(8);
        LivingItemManager.setWaxedBulbData(b2, new LivingWaxedBulbData(1_000));   // 堆总量 8000

        // 池 50_000 ≥ 请求 30_000 → 只动池
        long got1 = ContainerEnergyStorage.extract(power, List.of(b1, b2), 30_000, false);
        assertEquals(30_000, got1);
        assertEquals(20_000, power.getPoolMilliFe());
        assertEquals(4_000, LivingItemManager.getWaxedBulbData(b1).chargeMilliFe());

        // 请求 60_000：池 20_000 + 堆1 顺序扣 40_000（每盏 2500）→ 恰好满足
        long got2 = ContainerEnergyStorage.extract(power, List.of(b1, b2), 60_000, false);
        assertEquals(60_000, got2);
        assertEquals(0, power.getPoolMilliFe());
        assertEquals(4_000 - 2_500, LivingItemManager.getWaxedBulbData(b1).chargeMilliFe());
    }

    @Test
    @DisplayName("超取：请求超过总储能 → 只拿到现有量，各处不为负")
    void extract_clampedToAvailable() {
        ContainerPowerData power = new ContainerPowerData();
        power.setPoolMilliFe(10_000);
        ItemStack b1 = bulb(8);
        LivingItemManager.setWaxedBulbData(b1, new LivingWaxedBulbData(2_000));   // 堆总量 16000

        long got = ContainerEnergyStorage.extract(power, List.of(b1), 100_000, false);
        assertEquals(10_000 + 16_000, got);
        assertEquals(0, power.getPoolMilliFe());
        assertEquals(0, LivingItemManager.getWaxedBulbData(b1).chargeMilliFe());
    }

    @Test
    @DisplayName("模拟抽取：不修改任何状态")
    void extract_simulateLeavesStateIntact() {
        ContainerPowerData power = new ContainerPowerData();
        power.setPoolMilliFe(50_000);
        ItemStack b1 = bulb(16);
        LivingItemManager.setWaxedBulbData(b1, new LivingWaxedBulbData(4_000));

        long got = ContainerEnergyStorage.extract(power, List.of(b1), 60_000, true);
        assertEquals(60_000, got);   // 受请求上限约束（可用量 114_000 > 60_000）
        assertEquals(50_000, power.getPoolMilliFe());
        assertEquals(4_000, LivingItemManager.getWaxedBulbData(b1).chargeMilliFe());
    }

    @Test
    @DisplayName("发电入池：RE 事件按 K 换算进池（512000 RE → 32000 FE = 32_000_000 mFE）")
    void eventEnergy_flowsIntoPool() {
        ContainerPowerData power = new ContainerPowerData();
        power.onEventEnergy(512_000);
        assertEquals(32_000_000, power.getPoolMilliFe());
    }
}
