package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** 非Container后端；组件写回只允许直接setter，不允许数量抽插。 */
    private static class FakeHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;
        int writes;
        int changes;

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
            writes++;
            slots[slot] = stack.copy();
            changes++;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            throw new AssertionError("组件充放电不得插入物品");
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            throw new AssertionError("组件充放电不得抽取物品");
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

    private static ItemStack chargedBulb(int count, long q) {
        ItemStack stack = bulb(count);
        LivingWaxedBulbData.set(stack, new LivingWaxedBulbData(q));
        return stack;
    }

    private static long charge(ItemStack stack) {
        return LivingWaxedBulbData.of(stack).totalChargeMilliFe(stack.getCount());
    }

    @Test
    @DisplayName("超大堆叠请求1FE仅交付1FE，均分多扣不报给调用方")
    void extract_oversizedStackReturnsChosenDelivery() {
        ItemStack stack = chargedBulb(2048, 1);
        FakeHandler handler = new FakeHandler(stack);
        int[] changed = {0};

        assertEquals(1000L, ContainerEnergyStorage.extract(handler, 1000, true, () -> changed[0]++));
        assertEquals(2048L, charge(handler.slots[0]));
        assertEquals(0, changed[0]);
        assertEquals(1000L, ContainerEnergyStorage.extract(handler, 1000, false, () -> changed[0]++));
        assertEquals(0L, charge(handler.slots[0]));
        assertEquals(1, changed[0]);
    }

    @Test
    @DisplayName("37盏取3FE：交付3000mFE，实际扣3034mFE")
    void extract_nonDivisibleChargeHasBoundedLoss() {
        ItemStack stack = chargedBulb(37, 12_345);
        FakeHandler handler = new FakeHandler(stack);
        long before = charge(handler.slots[0]);

        assertEquals(3000L, ContainerEnergyStorage.extract(handler, 3000, true, null));
        assertEquals(before, charge(handler.slots[0]));
        assertEquals(3000L, ContainerEnergyStorage.extract(handler, 3000, false, null));
        assertEquals(3034L, before - charge(handler.slots[0]));
    }

    @Test
    @DisplayName("跨堆预算按交付量减少，第一堆取整损耗不挤占第二堆交付")
    void extract_multipleStacksUseDeliveryBudget() {
        ItemStack a = chargedBulb(37, 28);
        ItemStack b = chargedBulb(37, 100);
        FakeHandler handler = new FakeHandler(a, b);
        long before = charge(handler.slots[0]) + charge(handler.slots[1]);

        assertEquals(2000L, ContainerEnergyStorage.extract(handler, 2000, true, null));
        assertEquals(before, charge(handler.slots[0]) + charge(handler.slots[1]));
        assertEquals(2000L, ContainerEnergyStorage.extract(handler, 2000, false, null));
        assertEquals(0L, charge(handler.slots[0]));
        assertEquals(37L * 73, charge(handler.slots[1]));
        assertEquals(2035L, before - charge(handler.slots[0]) - charge(handler.slots[1]));
    }

    @Test
    @DisplayName("多堆不足1FE零头可合计交付整FE，均分差额不算交付")
    void extract_poolsFractionalStacksIntoWholeFe() {
        ItemStack[] stacks = new ItemStack[16];
        for (int i = 0; i < stacks.length; i++) stacks[i] = chargedBulb(64, 1);
        FakeHandler handler = new FakeHandler(stacks);

        assertEquals(1000L, ContainerEnergyStorage.extract(handler, 1000, true, null));
        assertEquals(1024L, java.util.Arrays.stream(handler.slots).mapToLong(WaxedCopperStorageTest::charge).sum());
        assertEquals(1000L, ContainerEnergyStorage.extract(handler, 1000, false, null));
        // 最后一堆均分向上取整可能吞掉24mFE，不得算作交付。
        assertEquals(0L, java.util.Arrays.stream(handler.slots).mapToLong(WaxedCopperStorageTest::charge).sum());
    }

    @Test
    @DisplayName("容器总存量不足1FE不扣零头，不标脏")
    void extract_totalBelowOneFeDoesNotMutate() {
        ItemStack a = chargedBulb(7, 142);
        FakeHandler handler = new FakeHandler(a);
        int[] changed = {0};

        assertEquals(0L, ContainerEnergyStorage.extract(handler, 1000, true, () -> changed[0]++));
        assertEquals(0L, ContainerEnergyStorage.extract(handler, 1000, false, () -> changed[0]++));
        assertEquals(994L, charge(handler.slots[0]));
        assertEquals(0, changed[0]);
        assertEquals(0, handler.writes);
    }

    @Test
    @DisplayName("可用量量化到整FE，剩余可表示零头留账")
    void extract_availableChargeIsQuantizedBeforeDeduction() {
        ItemStack a = chargedBulb(1, 1499);
        FakeHandler handler = new FakeHandler(a);

        assertEquals(1000L, ContainerEnergyStorage.extract(handler, 2000, false, null));
        assertEquals(499L, charge(handler.slots[0]));
    }

    @Test
    @DisplayName("非正或不足1FE请求完全不动")
    void extract_subFeAndNonPositiveRequestsAreNoOp() {
        ItemStack a = chargedBulb(16, 4000);
        FakeHandler handler = new FakeHandler(a);
        int[] changed = {0};
        for (long request : new long[] {-1, 0, 40, 999}) {
            assertEquals(0L, ContainerEnergyStorage.extract(handler, request, false, () -> changed[0]++));
        }
        assertEquals(64_000L, charge(handler.slots[0]));
        assertEquals(0, changed[0]);
        assertEquals(0, handler.writes);
    }

    @Test
    @DisplayName("大额请求不回绕：模拟读一次，执行只加提交重验与回读，首堆够即停")
    void extract_largeRequestScansOnlyRequiredSlots() {
        ItemStack a = chargedBulb(Integer.MAX_VALUE, PowerMath.BULB_UNIT_CAPACITY_MFE);
        ItemStack b = chargedBulb(1, 5000);
        int[] reads = {0};
        FakeHandler handler = new FakeHandler(a, b) {
            @Override public ItemStack getStackInSlot(int slot) {
                reads[0]++;
                if (slot != 0) throw new AssertionError("首堆已够请求，不得读取下一槽");
                return super.getStackInSlot(slot);
            }
        };
        long request = (long) Integer.MAX_VALUE * 1000;
        assertEquals(request, ContainerEnergyStorage.extract(handler, request, true, null));
        assertEquals(1, reads[0]);
        assertEquals(0, handler.writes);
        reads[0] = 0;
        long beforeQ = LivingWaxedBulbData.of(handler.slots[0]).chargeMilliFe();
        assertEquals(request, ContainerEnergyStorage.extract(handler, request, false, null));
        assertTrue(reads[0] >= 1 && reads[0] <= 3, "只允许规划N次 + 提交重验/回读2K次");
        assertEquals(beforeQ - 1000, LivingWaxedBulbData.of(handler.slots[0]).chargeMilliFe());
        assertEquals(5000L, charge(handler.slots[1]));
    }

    @Test
    @DisplayName("跨堆均分：单遍规划加每提交槽两次核验，实际变更只回调一次")
    void extract_readsEachStackOnceAndChangesOnce() {
        ItemStack a = chargedBulb(1, 600);
        ItemStack b = chargedBulb(1, 600);
        int[] reads = new int[2];
        FakeHandler handler = new FakeHandler(a, b) {
            @Override public ItemStack getStackInSlot(int slot) {
                assertTrue(++reads[slot] <= 3, "每槽只允许规划、旧值重验、写后回读");
                return super.getStackInSlot(slot);
            }
        };
        int[] changed = {0};
        assertEquals(1000L, ContainerEnergyStorage.extract(handler, 1000, true, () -> changed[0]++));
        assertEquals(1, reads[0]);
        assertEquals(1, reads[1]);
        assertEquals(0, handler.writes);
        assertEquals(0, changed[0]);
        assertEquals(600L, charge(handler.slots[0]));
        assertEquals(600L, charge(handler.slots[1]));
        java.util.Arrays.fill(reads, 0);

        assertEquals(1000L, ContainerEnergyStorage.extract(handler, 1000, false, () -> changed[0]++));
        assertEquals(0L, charge(handler.slots[0]));
        assertEquals(200L, charge(handler.slots[1]));
        assertEquals(1, changed[0]);
    }

    @Test
    @DisplayName("异常负电量忽略，超大正值乘法不溢出且交付不超请求")
    void extract_corruptChargeDoesNotBreakBudget() {
        ItemStack negative = chargedBulb(2, -1);
        ItemStack positive = chargedBulb(2, Long.MAX_VALUE);
        FakeHandler handler = new FakeHandler(negative, positive);
        assertEquals(1000L, ContainerEnergyStorage.extract(handler, 1000, false, null));
        assertEquals(-1L, LivingWaxedBulbData.of(handler.slots[0]).chargeMilliFe());
        assertEquals(Long.MAX_VALUE - 500, LivingWaxedBulbData.of(handler.slots[1]).chargeMilliFe());
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
        assertEquals(4000, LivingWaxedBulbData.of(stack).chargeMilliFe());
    }

    @Test
    @DisplayName("锈级专属通道（v18）：错色灯不收，只有同锈级灯充电")
    void distribute_channelIsolation_wrongColorBulbSkipped() {
        ItemStack freshBulb = bulb(16);                                        // 锈级 0（新鲜）
        ItemStack oxidizedBulb = bulbOxidized(16);                             // 锈级 3（氧化）
        LivingWaxedBulbData.set(oxidizedBulb,
            new LivingWaxedBulbData(PowerMath.BULB_UNIT_CAPACITY_MFE));        // 氧化灯已满

        // 锈级 0 发电 1024 RE：氧化灯（满 + 错色）都不该收，新鲜灯收全部
        boolean changed = LivingWaxedCopperFunction.distributeToBulbs(1024, 0,
            List.of(new LivingItemFunction.SlotEntry(0, oxidizedBulb),
                new LivingItemFunction.SlotEntry(1, freshBulb)));

        assertTrue(changed);
        assertEquals(4000, LivingWaxedBulbData.of(freshBulb).chargeMilliFe());
        assertEquals(PowerMath.BULB_UNIT_CAPACITY_MFE,
            LivingWaxedBulbData.of(oxidizedBulb).chargeMilliFe());
    }

    @Test
    @DisplayName("锈级专属通道（v18）：本锈级无同色灯 → 整锈级弃（电凭空消失）")
    void distribute_channelIsolation_noMatchingBulbDiscards() {
        ItemStack freshBulb = bulb(16);   // 锈级 0

        // 锈级 3（氧化）发电：容器里只有新鲜灯（错色）→ 弃
        boolean changed = LivingWaxedCopperFunction.distributeToBulbs(1024, 3,
            List.of(new LivingItemFunction.SlotEntry(0, freshBulb)));

        assertTrue(!changed);
        assertEquals(0, LivingWaxedBulbData.of(freshBulb).chargeMilliFe());
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

        assertEquals(2000, LivingWaxedBulbData.of(fresh).chargeMilliFe());
        assertEquals(4000, LivingWaxedBulbData.of(oxidized).chargeMilliFe());
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
        LivingWaxedBulbData.set(stack,
            new LivingWaxedBulbData(PowerMath.BULB_UNIT_CAPACITY_MFE));   // 每盏已满

        boolean changed = LivingWaxedCopperFunction.distributeToBulbs(1024, 0,
            List.of(new LivingItemFunction.SlotEntry(0, stack),
                new LivingItemFunction.SlotEntry(1, generator(4))));

        assertTrue(!changed);
        assertEquals(PowerMath.BULB_UNIT_CAPACITY_MFE,
            LivingWaxedBulbData.of(stack).chargeMilliFe());
    }

    @Test
    @DisplayName("取电（模组容器场景）：IItemHandlerModifiable逐堆写回扣账")
    void extract_moddedContainer_viaItemHandler() {
        ItemStack b1 = bulb(16);
        LivingWaxedBulbData.set(b1, new LivingWaxedBulbData(4_000));   // 堆总量 64000
        ItemStack b2 = bulb(8);
        LivingWaxedBulbData.set(b2, new LivingWaxedBulbData(1_000));   // 堆总量 8000
        FakeHandler handler = new FakeHandler(generator(4), b1, b2);

        // 请求 30_000 mFE：堆1 顺序扣 30_000（每盏 1875）→ 恰好
        long got1 = ContainerEnergyStorage.extract(handler, 30_000, false, null);
        assertEquals(30_000, got1);
        assertEquals(4_000 - 1_875, LivingWaxedBulbData.of(handler.slots[1]).chargeMilliFe());
        assertEquals(1_000, LivingWaxedBulbData.of(handler.slots[2]).chargeMilliFe());

        // 请求50FE：首堆剩34FE，次堆8FE，共交付42FE。
        long got2 = ContainerEnergyStorage.extract(handler, 50_000, false, null);
        assertEquals(42_000L, got2);
        assertEquals(0, LivingWaxedBulbData.of(handler.slots[1]).chargeMilliFe());
        assertEquals(0, LivingWaxedBulbData.of(handler.slots[2]).chargeMilliFe());
        assertTrue(got2 <= 50_000);
    }

    @Test
    @DisplayName("超取：请求超过总储能 → 只拿到现有量，各处不为负")
    void extract_clampedToAvailable() {
        ItemStack b1 = bulb(8);
        LivingWaxedBulbData.set(b1, new LivingWaxedBulbData(2_000));   // 堆总量 16000
        FakeHandler handler = new FakeHandler(b1);

        long got = ContainerEnergyStorage.extract(handler, 100_000, false, null);
        assertEquals(16_000, got);
        assertEquals(0, LivingWaxedBulbData.of(handler.slots[0]).chargeMilliFe());
    }

    @Test
    @DisplayName("模拟抽取：不修改任何状态")
    void extract_simulateLeavesStateIntact() {
        ItemStack b1 = bulb(16);
        LivingWaxedBulbData.set(b1, new LivingWaxedBulbData(4_000));
        FakeHandler handler = new FakeHandler(b1);

        long got = ContainerEnergyStorage.extract(handler, 60_000, true, null);
        assertEquals(60_000, got);   // 受请求上限约束（可用量 64_000 > 60_000）
        assertEquals(4_000, LivingWaxedBulbData.of(handler.slots[0]).chargeMilliFe());
        assertEquals(0, handler.writes);
    }

    @Test
    @DisplayName("onChanged 回调：有实际扣减才触发（落盘信号）")
    void extract_onChangedCallback() {
        ItemStack b1 = bulb(16);
        LivingWaxedBulbData.set(b1, new LivingWaxedBulbData(4_000));
        IItemHandler handler = new FakeHandler(b1);

        final int[] calls = {0};
        ContainerEnergyStorage.extract(handler, 1000, false, () -> calls[0]++);
        assertEquals(1, calls[0]);

        ContainerEnergyStorage.extract(handler, 1000, true, () -> calls[0]++);   // simulate 不触发
        assertEquals(1, calls[0]);
    }

    @Test
    @DisplayName("充电：外部电按剩余容量比例充入各灯堆（受每盏容量 C 上限）")
    void receive_distributesByRemainingCapacity() {
        ItemStack b1 = bulb(16);   // 空，剩余 16_000_000_000
        ItemStack b2 = bulb(16);
        LivingWaxedBulbData.set(b2, new LivingWaxedBulbData(500_000_000));   // 半满，剩余 8_000_000_000
        FakeHandler handler = new FakeHandler(b1, b2);

        // 剩余容量比例 b1:b2 = 2:1 → 30_000 mFE 中 b1 收 20_000（每盏 1250）、b2 收 10_000（每盏 625）
        long got = ContainerEnergyStorage.receive(handler, 30_000, false, null);
        assertEquals(30_000, got);
        assertEquals(1_250, LivingWaxedBulbData.of(handler.slots[0]).chargeMilliFe());
        assertEquals(500_000_000 + 625, LivingWaxedBulbData.of(handler.slots[1]).chargeMilliFe());
    }

    @Test
    @DisplayName("充电 clamp：全满 → 接受 0")
    void receive_fullBulbs_acceptZero() {
        ItemStack b1 = bulb(16);
        LivingWaxedBulbData.set(b1,
            new LivingWaxedBulbData(PowerMath.BULB_UNIT_CAPACITY_MFE));
        IItemHandler handler = new FakeHandler(b1);

        assertEquals(0, ContainerEnergyStorage.receive(handler, 50_000, false, null));
    }

    @Test
    @DisplayName("充电 simulate：不改状态")
    void receive_simulateLeavesStateIntact() {
        ItemStack b1 = bulb(16);
        FakeHandler handler = new FakeHandler(b1);

        assertEquals(30_000, ContainerEnergyStorage.receive(handler, 30_000, true, null));
        assertEquals(0, LivingWaxedBulbData.of(handler.slots[0]).chargeMilliFe());
        assertEquals(0, handler.writes);
    }

    @Test
    @DisplayName("充电量化：剩余容量非整 FE 的零头不接收（杜绝凭空造电）")
    void receive_quantizedToWholeFe() {
        ItemStack b1 = bulb(16);
        // 每盏剩余 1 mFE → 总剩余 16 mFE < 1 FE → 整 FE 量化后接收 0
        LivingWaxedBulbData.set(b1,
            new LivingWaxedBulbData(PowerMath.BULB_UNIT_CAPACITY_MFE - 1));
        FakeHandler handler = new FakeHandler(b1);

        assertEquals(0, ContainerEnergyStorage.receive(handler, 1000_000, false, null));
        assertEquals(PowerMath.BULB_UNIT_CAPACITY_MFE - 1,
            LivingWaxedBulbData.of(handler.slots[0]).chargeMilliFe());
    }

    @Test
    @DisplayName("未活化的铜灯不参与容器能源系统 —— 手动构造「未活化 + 带电」栈，仍不进出")
    void deactivatedBulb_excluded() {
        // ⚠️ 本用例【手动构造】带电的未活化栈。真实流程下未活化灯恒为空 ——
        //    setLiving(false) → clearLivingData 会按各功能的 getOwnedComponentTypes()
        //    逐个移除组件，其中就含 LIVING_WAXED_BULB_DATA（2026-10-09 探针实测：charge 4000 → 0）。
        //    这里刻意造出「万一带电」的边界，验证容器路径的排除判据（BulbBank.isBulb 要求
        //    isLivingItem）依然成立。
        //    ⚠️ 原 DisplayName 写「电量保留但不进出」是误导 —— 取消活化【不】保留电量。
        ItemStack stack = new ItemStack(Items.WAXED_COPPER_BULB, 16);   // 未打 IS_LIVING
        LivingWaxedBulbData.set(stack, new LivingWaxedBulbData(4_000));
        FakeHandler handler = new FakeHandler(stack);

        long got = ContainerEnergyStorage.extract(handler, 100_000, false, null);
        assertEquals(0, got);
        assertEquals(4_000, LivingWaxedBulbData.of(handler.slots[0]).chargeMilliFe());
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

        var telemetry = PowerTelemetry.buildTelemetry(gen, 4,
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
        var telemetry = PowerTelemetry.buildTelemetry(gen, 4,
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

    /** 计数版访问器：断言热路径的取物品次数（防回归到多遍扫描） */
    private static final class CountingHandler extends FakeHandler {
        int gets;

        CountingHandler(ItemStack... slots) {
            super(slots);
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            gets++;
            return super.getStackInSlot(slot);
        }
    }

    @Test
    @DisplayName("防 long 溢出（2026-09-11）：外部一次请求 2^31-1 FE，份额计算不得退化成零头回收的伪死循环")
    void receive_hugeExternalRequest_fillsAllBulbs() {
        ItemStack[] slots = new ItemStack[27];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = bulb(64);
        }
        CountingHandler handler = new CountingHandler(slots);
        long capacity = PowerMath.BULB_UNIT_CAPACITY_MFE * 64L * 27L;

        assertEquals(capacity, ContainerEnergyStorage.receive(handler, Integer.MAX_VALUE * 1000L, true, null));
        assertEquals(slots.length, handler.gets, "模拟只能规划扫描N次");
        assertEquals(0, handler.writes);
        assertEquals(0L, java.util.Arrays.stream(handler.slots).mapToLong(WaxedCopperStorageTest::charge).sum());
        handler.gets = 0;
        long got = ContainerEnergyStorage.receive(handler, Integer.MAX_VALUE * 1000L, false, null);

        // 整 FE 量化：容器全空 → 应收满全部容量
        assertEquals(capacity, got);
        // 每一盏都必须充满。修复前 accept×remaining 越过 Long.MAX → share 变乱值
        // → 本轮只分出去约 6e7 mFE（占比 0.003%），灯几乎没充上，
        // 而 leftover 被顶到 accept，零头回收循环每轮只扣 count mFE → ~10 亿轮。
        for (ItemStack stack : handler.slots) {
            assertEquals(PowerMath.BULB_UNIT_CAPACITY_MFE,
                LivingWaxedBulbData.of(stack).chargeMilliFe());
        }
        // 单遍规划N次；每个组件提交只增加旧值重验与写后回读2K次。
        assertTrue(handler.gets >= slots.length && handler.gets <= slots.length * 3,
            "取物品次数不得超过N+2K，实际 " + handler.gets);
    }

    @Test
    @DisplayName("静态充电核心多槽提交：模拟无写，成功只回调一次")
    void receive_multipleCommitsNotifyOnce() {
        FakeHandler handler = new FakeHandler(chargedBulb(1, 0), chargedBulb(1, 0));
        int[] callbacks = {0};
        assertEquals(3000L, ContainerEnergyStorage.receive(handler, 3000, true, () -> callbacks[0]++));
        assertEquals(0L, charge(handler.slots[0]) + charge(handler.slots[1]));
        assertEquals(0, handler.writes);
        assertEquals(0, callbacks[0]);

        assertEquals(3000L, ContainerEnergyStorage.receive(handler, 3000, false, () -> callbacks[0]++));
        assertAll(
            () -> assertEquals(1500L, charge(handler.slots[0])),
            () -> assertEquals(1500L, charge(handler.slots[1])),
            () -> assertEquals(2, handler.writes),
            () -> assertEquals(2, handler.changes),
            () -> assertEquals(1, callbacks[0]));
    }

    @Test
    @DisplayName("静态取电核心前缀600mFE已扣但返回0FE，仍只回调一次")
    void extract_zeroDeliveryAfterPartialCommitStillNotifiesOnce() {
        FakeHandler handler = new FakeHandler(chargedBulb(1, 600), chargedBulb(1, 600)) {
            @Override public ItemStack getStackInSlot(int slot) {
                return super.getStackInSlot(slot).copy();
            }
            @Override public void setStackInSlot(int slot, ItemStack stack) {
                if (slot == 1) writes++;
                else super.setStackInSlot(slot, stack);
            }
        };
        int[] callbacks = {0};
        assertEquals(1000L, ContainerEnergyStorage.extract(handler, 1000, true, () -> callbacks[0]++));
        assertEquals(1200L, charge(handler.slots[0]) + charge(handler.slots[1]));
        assertEquals(0, handler.writes);
        assertEquals(0, callbacks[0]);

        long reported = ContainerEnergyStorage.extract(handler, 1000, false, () -> callbacks[0]++);
        assertAll(
            () -> assertEquals(0L, reported),
            () -> assertEquals(0L, charge(handler.slots[0])),
            () -> assertEquals(600L, charge(handler.slots[1])),
            () -> assertEquals(2, handler.writes),
            () -> assertEquals(1, handler.changes),
            () -> assertEquals(1, callbacks[0]));
    }

    @Test
    @DisplayName("防 long 溢出（2026-09-11）：大发电量按剩余容量分配，不得凭空造电或静默丢弃")
    void distributeToBulbs_hugeGeneration_noOverflow() {
        ItemStack bulbs = bulb(64);   // 64 盏空灯，剩余容量 64 × 1e9 = 6.4e10 mFE
        List<LivingItemFunction.SlotEntry> entries =
            List.of(new LivingItemFunction.SlotEntry(0, bulbs));

        // 1e7 RE ⇒ mfe = round(1e7 × 1/16 × 1000) = 6.25e8 mFE，远小于 64 盏的总容量。
        // 修复前 mfe × remaining = 4e19 越过 Long.MAX → share 变乱值：
        // 变成负数则本 tick 发电被静默丢弃，变成巨大正数则 newQ 被拉到容量上限（凭空造电）。
        long voiceRe = 10_000_000L;
        long expectMfe = Math.round(voiceRe * (1.0 / 16.0) * 1000.0);

        assertTrue(LivingWaxedCopperFunction.distributeToBulbs(voiceRe, 0, entries));

        long expectPerLamp = expectMfe / 64;
        assertEquals(expectPerLamp, LivingWaxedBulbData.of(bulbs).chargeMilliFe());
        // 实充恒 ≤ 应充（宁损勿造）
        assertTrue(LivingWaxedBulbData.of(bulbs).totalChargeMilliFe(64) <= expectMfe);
    }
}