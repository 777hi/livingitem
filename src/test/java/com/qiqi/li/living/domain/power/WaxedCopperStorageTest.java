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
 * <p>模型语义见 docs/红电系统.md §3.6（v18）：
 * 发电直存按锈级专属通道分配（k 锈级发电只入 k 锈级铜灯堆，
 * 无同色灯则该锈级弃）；用电侧直接从铜灯取电。
 * 电量按「每盏」存（q），拆分/合并/搬运天然守恒。</p>
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

    /** 氧化锈级的涂蜡铜灯（锈级 3）——锈级专属通道用例 */
    private static ItemStack bulbOxidized(int count) {
        ItemStack stack = new ItemStack(Items.WAXED_OXIDIZED_COPPER_BULB, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    /** 50% 占空比整周期方波（period 为奇数时高电平 period/2 取整） */
    private static int square(int tick, int phase, int period, int high) {
        return Math.floorMod(tick - phase, period) < period / 2 ? high : 0;
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

        boolean changed = LivingWaxedCopperFunction.distributeToBulbs(1024, 0,
            List.of(new LivingItemFunction.SlotEntry(0, stack),
                new LivingItemFunction.SlotEntry(1, generator(4))));

        assertTrue(changed);
        // mfe = 1024 × 62.5 = 64000，share = 64000（唯一堆），perLamp = 64000/16 = 4000
        assertEquals(4000, LivingItemManager.getWaxedBulbData(stack).chargeMilliFe());
    }

    @Test
    @DisplayName("锈级专属通道（v18）：错色灯不收，只有同锈级灯充电")
    void distribute_channelIsolation_wrongColorBulbSkipped() {
        ItemStack freshBulb = bulb(16);                                        // 锈级 0（新鲜）
        ItemStack oxidizedBulb = bulbOxidized(16);                             // 锈级 3（氧化）
        LivingItemManager.setWaxedBulbData(oxidizedBulb,
            new LivingWaxedBulbData(PowerMath.BULB_UNIT_CAPACITY_MFE));        // 氧化灯已满

        // 锈级 0 发电 1024 RE：氧化灯（满 + 错色）都不该收，新鲜灯收全部
        boolean changed = LivingWaxedCopperFunction.distributeToBulbs(1024, 0,
            List.of(new LivingItemFunction.SlotEntry(0, oxidizedBulb),
                new LivingItemFunction.SlotEntry(1, freshBulb)));

        assertTrue(changed);
        assertEquals(4000, LivingItemManager.getWaxedBulbData(freshBulb).chargeMilliFe());
        assertEquals(PowerMath.BULB_UNIT_CAPACITY_MFE,
            LivingItemManager.getWaxedBulbData(oxidizedBulb).chargeMilliFe());
    }

    @Test
    @DisplayName("锈级专属通道（v18）：本锈级无同色灯 → 整锈级弃（电凭空消失）")
    void distribute_channelIsolation_noMatchingBulbDiscards() {
        ItemStack freshBulb = bulb(16);   // 锈级 0

        // 锈级 3（氧化）发电：容器里只有新鲜灯（错色）→ 弃
        boolean changed = LivingWaxedCopperFunction.distributeToBulbs(1024, 3,
            List.of(new LivingItemFunction.SlotEntry(0, freshBulb)));

        assertTrue(!changed);
        assertEquals(0, LivingItemManager.getWaxedBulbData(freshBulb).chargeMilliFe());
    }

    @Test
    @DisplayName("锈级专属通道（v18）：各锈级电独立分账（同容器四色不串账）")
    void distribute_channelIsolation_perLevelLedger() {
        ItemStack fresh = bulb(16);           // 锈级 0
        ItemStack oxidized = bulbOxidized(16); // 锈级 3
        List<LivingItemFunction.SlotEntry> entries = List.of(
            new LivingItemFunction.SlotEntry(0, fresh),
            new LivingItemFunction.SlotEntry(1, oxidized));

        // 锈级 0 发 512 RE，锈级 3 发 1024 RE：各入各色，互不稀释
        assertTrue(LivingWaxedCopperFunction.distributeToBulbs(512, 0, entries));
        assertTrue(LivingWaxedCopperFunction.distributeToBulbs(1024, 3, entries));

        assertEquals(2000, LivingItemManager.getWaxedBulbData(fresh).chargeMilliFe());
        assertEquals(4000, LivingItemManager.getWaxedBulbData(oxidized).chargeMilliFe());
    }

    @Test
    @DisplayName("无铜灯：发电弃（电凭空消失——发电必须被消费或储存）")
    void distribute_noBulbs_discards() {
        boolean changed = LivingWaxedCopperFunction.distributeToBulbs(1024, 0,
            List.of(new LivingItemFunction.SlotEntry(0, generator(4))));

        assertTrue(!changed);
    }

    @Test
    @DisplayName("铜灯已满：弃（显性浪费，tooltip 显示已满）")
    void distribute_fullBulb_discards() {
        ItemStack stack = bulb(16);
        LivingItemManager.setWaxedBulbData(stack,
            new LivingWaxedBulbData(PowerMath.BULB_UNIT_CAPACITY_MFE));   // 每盏已满

        boolean changed = LivingWaxedCopperFunction.distributeToBulbs(1024, 0,
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
        ItemStack b1 = bulb(16);   // 空，剩余 16_000_000
        ItemStack b2 = bulb(16);
        LivingItemManager.setWaxedBulbData(b2, new LivingWaxedBulbData(500_000));   // 剩余 8_000_000
        IItemHandler handler = new FakeHandler(b1, b2);

        // 剩余容量比例 b1:b2 = 2:1 → 30_000 mFE 中 b1 收 20_000（每盏 1250）、b2 收 10_000（每盏 625）
        long got = ContainerEnergyStorage.receive(handler, 30_000, false, null);
        assertEquals(30_000, got);
        assertEquals(1_250, LivingItemManager.getWaxedBulbData(b1).chargeMilliFe());
        assertEquals(500_000 + 625, LivingItemManager.getWaxedBulbData(b2).chargeMilliFe());
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
    @DisplayName("仪表盘快照：检测周期/相数/解锁度/effDeltaSum（完美调谐）")
    void telemetry_perfectTuning() {
        GeneratorState gen = new GeneratorState();
        gen.setPreferredPeriodFromStack(4);
        ChannelState channel = gen.channel();

        // 模拟 4t 振荡器，|Δ| = 4096
        for (int t = 0; t <= 16; t += 4) {
            channel.onPhaseEvent(new PhaseEvent(0, 4, t % 4, 4096, t), gen.preferredPeriod());
        }

        var telemetry = LivingWaxedCopperFunction.buildTelemetry(gen, 4,
            com.qiqi.li.living.domain.power.LivingWaxedGeneratorData.FORM_BLOCK, 0, null);
        assertEquals(4, telemetry.detectedPeriod());
        assertEquals(1, telemetry.phaseCount());
        assertEquals(250, telemetry.unlockPermille());   // eff=1.0 × n=1 / pref=4 = 0.25 → 250
        assertEquals(64000, telemetry.effDeltaSumPermille());   // √4096 = 64.0 → 64000‰
    }

    @Test
    @DisplayName("仪表盘快照：无信号 → 全零（检测中）")
    void telemetry_noSignal() {
        GeneratorState gen = new GeneratorState();
        gen.setPreferredPeriodFromStack(4);
        var telemetry = LivingWaxedCopperFunction.buildTelemetry(gen, 4,
            com.qiqi.li.living.domain.power.LivingWaxedGeneratorData.FORM_BLOCK, 0, null);
        assertEquals(0, telemetry.detectedPeriod());
        assertEquals(0, telemetry.phaseCount());
        assertEquals(0, telemetry.unlockPermille());
        assertEquals(0, telemetry.effDeltaSumPermille());
    }

    @Test
    @DisplayName("锈级 EMA 功率（v18）：updateOxidationEma 驱动，K=1/16 换算，按锈级读取")
    void ema_levelPowerTracking() {
        ContainerPowerData power = new ContainerPowerData();
        // 锈级 0 发电 512_000 RE，其余锈级 0
        power.updateOxidationEma(new long[]{512_000, 0, 0, 0});
        assertEquals(4000, power.getLevelEmaPowerFe(0));   // 512000 × 0.125 = 64000 RE/t → 4000 FE/t
        assertEquals(0, power.getLevelEmaPowerFe(3));

        power.updateOxidationEma(new long[]{0, 0, 0, 0});   // 本 tick 无发电 → EMA 衰减
        assertEquals(3500, power.getLevelEmaPowerFe(0));    // 64000 × 0.875 = 56000 RE/t → 3500 FE/t
    }
}