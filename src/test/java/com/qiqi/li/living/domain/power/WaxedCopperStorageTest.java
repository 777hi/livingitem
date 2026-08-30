package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;

/**
 * 阶段四储能测试 —— 铜灯 = 唯一储存（无容器池）+ 对外取电（模组兼容面）。
 *
 * <p>模型语义见 docs/红电系统.md §3.6（v17.5）：
 * 发电直存（按剩余容量比例分配入各铜灯堆）→ 无铜灯则电凭空消失；
 * 用电侧直接从铜灯取电。电量按「每盏」存（q），拆分/合并/搬运天然守恒。</p>
 */
class WaxedCopperStorageTest {

    /** 测试用物品访问器——模拟「只暴露 IItemHandler 的模组容器」（不实现 Container） */
    private static class FakeHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;

        FakeHandler(ItemStack... slots) {
            this.slots = slots;
        }

        @Override
        public int getSlots() {
            return slots.length;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return slots[slot];
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            slots[slot] = stack;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;   // 不支持插入（测试不涉及）
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return true;
        }
    }

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
    @DisplayName("发电直存：1024 RE → 64000 mFE 按剩余容量比例存入 16 盏堆（每盏 4000）")
    void distribute_intoBulbs() {
        ItemStack stack = bulb(16);   // 容量 16 × 100_000 = 1_600_000 mFE

        boolean changed = LivingWaxedCopperFunction.distributeToBulbs(1024,
            List.of(new LivingItemFunction.SlotEntry(0, stack),
                new LivingItemFunction.SlotEntry(1, generator(4))));

        assertTrue(changed);
        // mfe = 1024 × 62.5 = 64000，share = 64000（唯一堆），perLamp = 64000/16 = 4000
        assertEquals(4000, LivingItemManager.getWaxedBulbData(stack).chargeMilliFe());
    }

    @Test
    @DisplayName("无铜灯：发电弃（电凭空消失——发电必须被消费或储存）")
    void distribute_noBulbs_discards() {
        boolean changed = LivingWaxedCopperFunction.distributeToBulbs(1024,
            List.of(new LivingItemFunction.SlotEntry(0, generator(4))));

        assertTrue(!changed);
    }

    @Test
    @DisplayName("铜灯已满：弃（显性浪费，tooltip 显示已满）")
    void distribute_fullBulb_discards() {
        ItemStack stack = bulb(16);
        LivingItemManager.setWaxedBulbData(stack,
            new LivingWaxedBulbData(PowerMath.BULB_UNIT_CAPACITY_MFE));   // 每盏已满

        boolean changed = LivingWaxedCopperFunction.distributeToBulbs(1024,
            List.of(new LivingItemFunction.SlotEntry(0, stack),
                new LivingItemFunction.SlotEntry(1, generator(4))));

        assertTrue(!changed);
        assertEquals(PowerMath.BULB_UNIT_CAPACITY_MFE,
            LivingItemManager.getWaxedBulbData(stack).chargeMilliFe());
    }

    @Test
    @DisplayName("取电（模组容器场景）：纯 IItemHandler 访问器，逐堆扣铜灯")
    void extract_moddedContainer_viaItemHandler() {
        ItemStack b1 = bulb(16);
        LivingItemManager.setWaxedBulbData(b1, new LivingWaxedBulbData(4_000));   // 堆总量 64000
        ItemStack b2 = bulb(8);
        LivingItemManager.setWaxedBulbData(b2, new LivingWaxedBulbData(1_000));   // 堆总量 8000
        IItemHandler handler = new FakeHandler(generator(4), b1, b2);   // 非容器的模组容器

        // 请求 30_000 mFE：堆1 顺序扣 30_000（每盏 1875）→ 恰好
        long got1 = ContainerEnergyStorage.extract(handler, 30_000, false, null);
        assertEquals(30_000, got1);
        assertEquals(4_000 - 1_875, LivingItemManager.getWaxedBulbData(b1).chargeMilliFe());
        assertEquals(1_000, LivingItemManager.getWaxedBulbData(b2).chargeMilliFe());

        // 请求 50_000 mFE：堆1 余 3375×16 = 54000 → 扣 3375/16 → 每盏 1687（floor）+ b2 补
        long got2 = ContainerEnergyStorage.extract(handler, 50_000, false, null);
        assertTrue(got2 > 0);
        assertEquals(0, LivingItemManager.getWaxedBulbData(b1).chargeMilliFe());
        assertEquals(0, LivingItemManager.getWaxedBulbData(b2).chargeMilliFe());
        assertTrue(got2 <= 50_000);
    }

    @Test
    @DisplayName("超取：请求超过总储能 → 只拿到现有量，各处不为负")
    void extract_clampedToAvailable() {
        ItemStack b1 = bulb(8);
        LivingItemManager.setWaxedBulbData(b1, new LivingWaxedBulbData(2_000));   // 堆总量 16000
        IItemHandler handler = new FakeHandler(b1);

        long got = ContainerEnergyStorage.extract(handler, 100_000, false, null);
        assertEquals(16_000, got);
        assertEquals(0, LivingItemManager.getWaxedBulbData(b1).chargeMilliFe());
    }

    @Test
    @DisplayName("模拟抽取：不修改任何状态")
    void extract_simulateLeavesStateIntact() {
        ItemStack b1 = bulb(16);
        LivingItemManager.setWaxedBulbData(b1, new LivingWaxedBulbData(4_000));
        IItemHandler handler = new FakeHandler(b1);

        long got = ContainerEnergyStorage.extract(handler, 60_000, true, null);
        assertEquals(60_000, got);   // 受请求上限约束（可用量 64_000 > 60_000）
        assertEquals(4_000, LivingItemManager.getWaxedBulbData(b1).chargeMilliFe());
    }

    @Test
    @DisplayName("onChanged 回调：有实际扣减才触发（落盘信号）")
    void extract_onChangedCallback() {
        ItemStack b1 = bulb(16);
        LivingItemManager.setWaxedBulbData(b1, new LivingWaxedBulbData(4_000));
        IItemHandler handler = new FakeHandler(b1);

        final int[] calls = {0};
        ContainerEnergyStorage.extract(handler, 40, false, () -> calls[0]++);
        assertEquals(1, calls[0]);

        ContainerEnergyStorage.extract(handler, 40, true, () -> calls[0]++);   // simulate 不触发
        assertEquals(1, calls[0]);
    }

    @Test
    @DisplayName("充电：外部电按剩余容量比例充入各灯堆（受每盏容量 C 上限）")
    void receive_distributesByRemainingCapacity() {
        ItemStack b1 = bulb(16);   // 空，剩余 1_600_000
        ItemStack b2 = bulb(16);
        LivingItemManager.setWaxedBulbData(b2, new LivingWaxedBulbData(50_000));   // 剩余 800_000
        IItemHandler handler = new FakeHandler(b1, b2);

        // 剩余容量比例 b1:b2 = 2:1 → 30_000 mFE 中 b1 收 20_000（每盏 1250）、b2 收 10_000（每盏 625）
        long got = ContainerEnergyStorage.receive(handler, 30_000, false, null);
        assertEquals(30_000, got);
        assertEquals(1_250, LivingItemManager.getWaxedBulbData(b1).chargeMilliFe());
        assertEquals(50_000 + 625, LivingItemManager.getWaxedBulbData(b2).chargeMilliFe());
    }

    @Test
    @DisplayName("充电 clamp：全满 → 接受 0")
    void receive_fullBulbs_acceptZero() {
        ItemStack b1 = bulb(16);
        LivingItemManager.setWaxedBulbData(b1,
            new LivingWaxedBulbData(PowerMath.BULB_UNIT_CAPACITY_MFE));
        IItemHandler handler = new FakeHandler(b1);

        assertEquals(0, ContainerEnergyStorage.receive(handler, 50_000, false, null));
    }

    @Test
    @DisplayName("充电 simulate：不改状态")
    void receive_simulateLeavesStateIntact() {
        ItemStack b1 = bulb(16);
        IItemHandler handler = new FakeHandler(b1);

        assertEquals(30_000, ContainerEnergyStorage.receive(handler, 30_000, true, null));
        assertEquals(0, LivingItemManager.getWaxedBulbData(b1).chargeMilliFe());
    }

    @Test
    @DisplayName("充电量化：剩余容量非整 FE 的零头不接收（杜绝凭空造电）")
    void receive_quantizedToWholeFe() {
        ItemStack b1 = bulb(16);
        // 每盏剩余 1 mFE → 总剩余 16 mFE < 1 FE → 整 FE 量化后接收 0
        LivingItemManager.setWaxedBulbData(b1,
            new LivingWaxedBulbData(PowerMath.BULB_UNIT_CAPACITY_MFE - 1));
        IItemHandler handler = new FakeHandler(b1);

        assertEquals(0, ContainerEnergyStorage.receive(handler, 1000_000, false, null));
        assertEquals(PowerMath.BULB_UNIT_CAPACITY_MFE - 1,
            LivingItemManager.getWaxedBulbData(b1).chargeMilliFe());
    }

    @Test
    @DisplayName("取消活化的铜灯不参与能源系统（电量保留但不进出）")
    void deactivatedBulb_excluded() {
        ItemStack stack = new ItemStack(Items.WAXED_COPPER_BULB, 16);   // 未打 IS_LIVING
        LivingItemManager.setWaxedBulbData(stack, new LivingWaxedBulbData(4_000));
        IItemHandler handler = new FakeHandler(stack);

        long got = ContainerEnergyStorage.extract(handler, 100_000, false, null);
        assertEquals(0, got);
        assertEquals(4_000, LivingItemManager.getWaxedBulbData(stack).chargeMilliFe());
    }

    @Test
    @DisplayName("发电量累计：RE 事件累加 + drain 清零")
    void generatedRe_accumulateAndDrain() {
        ContainerPowerData power = new ContainerPowerData();
        power.onEventEnergy(512_000);
        power.onEventEnergy(48_000);
        assertEquals(560_000, power.drainGeneratedRe());
        assertEquals(0, power.drainGeneratedRe());   // 已清空
    }

    @Test
    @DisplayName("EMA 功率：本 tick 发电量驱动，K=1/16 换算")
    void ema_powerTracking() {
        ContainerPowerData power = new ContainerPowerData();
        power.onEventEnergy(512_000);
        power.endTick(power.drainGeneratedRe());
        assertEquals(4000, power.getEmaPowerFe());   // 512000 × 0.125 = 64000 RE/t → 4000 FE/t

        power.endTick(power.drainGeneratedRe());     // 本 tick 无发电 → EMA 衰减
        assertEquals(3500, power.getEmaPowerFe());   // 64000 × 0.875 = 56000 RE/t → 3500 FE/t
    }
}
