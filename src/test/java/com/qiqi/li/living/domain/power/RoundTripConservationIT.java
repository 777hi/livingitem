package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 往返守恒集成测试（2026-09-09）：容器(灯A) → 电缆 → 容器(灯B) 原样往返，
 * 断言总能量不增（可微降——取整损耗方向）。
 *
 * <p>测试精确复刻 Mekanism UniversalCable + ForgeStrictEnergyHandler 的传输协议
 * （源码逐条对照，单位换算 feConversionRate = 2.5，即 1 J = 2.5 FE）：</p>
 *
 * <p><b>拉侧</b>（{@code UniversalCable.pullFromAcceptors} →
 * {@code ForgeStrictEnergyHandler.extractEnergy}）：</p>
 * <ol>
 *   <li>toExtract = (long)(J_可用 / 2.5)——Joules→FE 向下取整（clampToLong）；</li>
 *   <li>SIMULATE：simulated = storage.extractEnergy(toExtract, true)，
 *       再 convertFromAndBack 钳制 toExtract = convertFrom(convertToAsInt(simulated))
 *       ——防「1.5 J 被当 1 J 抽取而凭空消失/产生」；</li>
 *   <li>EXECUTE：extracted = storage.extractEnergy(toExtract, false)；
 *       电缆网络账本 += convertFrom(extracted) = (long)(extracted × 2.5)。</li>
 * </ol>
 *
 * <p><b>推侧</b>（{@code EnergyAcceptorTarget.acceptAmount} →
 * {@code ForgeStrictEnergyHandler.insertEnergy}）：</p>
 * <ol>
 *   <li>toInsert = (long)(J_分配 / 2.5)；SIMULATE 探测后同样 convertFromAndBack 钳制；</li>
 *   <li>EXECUTE：inserted = storage.receiveEnergy(toInsert, false)；
 *       网络账本 -= convertFrom(inserted)。</li>
 * </ol>
 *
 * <p><b>网络缓冲</b>：电缆内有容量上限的中间账本（Joules），拉侧先进缓冲、
 * 推侧从缓冲出——往返中缓冲余量计入总能量核对。</p>
 */
class RoundTripConservationIT {

    /** Mekanism 默认 feConversionRate：1 Joule = 2.5 FE */
    private static final double FE_RATE = 2.5;

    /** Basic Universal Cable 容量（J）——典型档位 */
    private static final long CABLE_CAPACITY_J = 200_000 / 2; // 缩小缓冲放大观察
    private static final long PULL_LIMIT_J = 1_000 / 2;       // 每 tick 拉取上限

    // ═══════════ Mekanism 协议复刻 ═══════════

    /** IEnergyConversion.convertFrom：other→Joules，clampToLong = 向下取整 */
    private static long jFromFe(long fe) {
        return (long) (fe * FE_RATE);
    }

    /** IEnergyConversion.convertTo：Joules→other，向下取整 */
    private static long feFromJ(long joules) {
        return (long) (joules / FE_RATE);
    }

    /** ForgeStrictEnergyHandler.convertFromAndBack（extract 侧钳制） */
    private static long clampBackExtract(long joules) {
        long fe = feFromJ(joules);
        long result = jFromFe(fe);
        double conversion = 1 / FE_RATE;
        if (conversion >= 1 && result % (long) conversion > 0) {   // 2.5 档 conversion=0.4 <1，不触发
            return jFromFe(fe - 1);
        }
        return result;
    }

    /**
     * 模拟一 tick 的电缆传输：从 source（灯容器）拉 PULL_LIMIT_J，
     * 全部推入 sink（灯容器），网络缓冲计入总账。
     *
     * <p>FE↔mFE 与门面的关系：门面 extractEnergy(fe) 内部即
     * extract(items, fe×1000)/1000——本测试直接调用静态核心（mFE 口径），
     * FE 层换算由协议复刻部分承接，与真实链路等价。</p>
     */
    private static void tickCable(IItemHandler source, IItemHandler sink,
            long[] buffer, long[] stats) {
        // ── 拉侧：UniversalCable.pullFromAcceptors ──
        long availablePull = Math.min(PULL_LIMIT_J, CABLE_CAPACITY_J - buffer[0]);
        if (availablePull > 0) {
            // ForgeStrictEnergyHandler.extractEnergy(J)：
            long toExtractFe = feFromJ(availablePull);
            if (toExtractFe > 0) {
                long simulated = ContainerEnergyStorage.extract(source, toExtractFe * 1000, true, null) / 1000;
                // convertFromAndBack 钳制（1.5 J 当 1 J 的防增益）
                long clampedFe = feFromJ(clampBackExtract(jFromFe(simulated)));
                long extractedFe = ContainerEnergyStorage.extract(source, clampedFe * 1000, false, null) / 1000;
                long movedJ = jFromFe(extractedFe);
                buffer[0] += movedJ;                  // 电缆网络按返回值记账
                stats[0] += movedJ;                   // 累计拉取
            }
        }

        // ── 推侧：EmitUtils.sendToAcceptors → EnergyAcceptorTarget.acceptAmount ──
        if (buffer[0] > 0) {
            // ForgeStrictEnergyHandler.insertEnergy(J)：
            long toInsertFe = feFromJ(buffer[0]);
            if (toInsertFe > 0) {
                long simulated = ContainerEnergyStorage.receive(sink, toInsertFe * 1000, true, null) / 1000;
                if (simulated > 0) {
                    long clampedFe = feFromJ(clampBackExtract(jFromFe(simulated)));
                    long insertedFe = ContainerEnergyStorage.receive(sink, clampedFe * 1000, false, null) / 1000;
                    long movedJ = jFromFe(insertedFe);
                    buffer[0] -= movedJ;              // 按实际插入记账
                    stats[1] += movedJ;
                }
            }
        }
    }

    // ═══════════ 测试替身 ═══════════

    private static class FakeHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;
        FakeHandler(ItemStack... slots) { this.slots = slots; }
        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) {
            return slots[slot] != null ? slots[slot] : ItemStack.EMPTY;
        }
        @Override public void setStackInSlot(int slot, ItemStack stack) { slots[slot] = stack; }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }
    }

    private static ItemStack bulb(int count, long qMfe) {
        ItemStack s = new ItemStack(Items.WAXED_COPPER_BULB, count);
        LivingItemManager.setLiving(s, true);
        LivingItemManager.setWaxedBulbData(s, new LivingWaxedBulbData(qMfe));
        return s;
    }

    private static long totalMfe(IItemHandler h) {
        long sum = 0;
        for (int i = 0; i < h.getSlots(); i++) {
            ItemStack s = h.getStackInSlot(i);
            if (!s.isEmpty() && LivingItemManager.isLivingItem(s)
                    && LivingWaxedCopperFunction.isWaxedBulb(s.getItem())) {
                sum += LivingItemManager.getWaxedBulbData(s).chargeMilliFe() * s.getCount();
            }
        }
        return sum;
    }

    // ═══════════ 测试 ═══════════

    @Test
    @DisplayName("往返守恒：满灯容器A → 电缆 → 灯容器B 逐 tick 推送，总能量不得增加")
    void roundTrip_neverCreatesEnergy() {
        // 容器A：两堆满灯（64 + 37 盏，故意不整除制造取整压力）
        FakeHandler boxA = new FakeHandler(
            bulb(64, PowerMath.BULB_UNIT_CAPACITY_MFE),
            bulb(37, PowerMath.BULB_UNIT_CAPACITY_MFE));
        // 容器B：一堆空灯 + 一堆半满（比例分配路径全覆盖）
        FakeHandler boxB = new FakeHandler(
            bulb(64, 0),
            bulb(37, PowerMath.BULB_UNIT_CAPACITY_MFE / 2));

        long beforeMfe = totalMfe(boxA) + totalMfe(boxB);

        long[] buffer = {0};
        long[] stats = {0, 0};
        int TICKS = 1000;
        for (int t = 0; t < TICKS; t++) {
            tickCable(boxA, boxB, buffer, stats);
        }

        long afterMfe = totalMfe(boxA) + totalMfe(boxB);
        long bufferedFe = (long) (buffer[0] / FE_RATE * 1000);   // 缓冲残留计入总账
        long afterTotal = afterMfe + bufferedFe;

        // 核心断言：总能量（灯A + 灯B + 电缆缓冲）不得超过初始
        assertTrue(afterTotal <= beforeMfe,
            "往返凭空造电！初始 " + beforeMfe + " mFE，终态 " + afterTotal
                + " mFE（灯 " + afterMfe + " + 缓冲 " + bufferedFe + "），增益 +"
                + (afterTotal - beforeMfe) + " mFE；累计拉取 J=" + stats[0]
                + "，累计推入 J=" + stats[1]);
        // 副断言：确实发生了实质传输（否则测试空转无意义）
        assertTrue(stats[1] > 0, "应发生过实际传输");
        System.out.println("[RoundTrip] A=" + totalMfe(boxA) + "mFE, B=" + totalMfe(boxB)
            + "mFE, buffer=" + buffer[0] + "J, ΔTotal=" + (afterTotal - beforeMfe) + "mFE");
    }

    // ═══════════ 诊断：单侧拆解 ═══════════

    /**
     * 诊断 A：只拉侧（extract × SIMULATE/EXECUTE 双调用协议），
     * 断言「灯被扣的 mFE ≥ 电缆记的 mFE」（拉侧只许漏不许赚）。
     */
    @Test
    @DisplayName("诊断·拉侧：灯扣减量 ≥ 协议记账量（SIMULATE→EXECUTE 与向上取整）")
    void diag_pullSide_neverOverpays() {
        // 37 盏堆（不整除），q = 12345 mFE（不整 FE）
        FakeHandler box = new FakeHandler(bulb(37, 12_345));
        long before = totalMfe(box);

        long accountedMfe = 0;
        for (int t = 0; t < 500; t++) {
            long toExtractFe = 3;   // 小额制造大量余数
            long simulated = ContainerEnergyStorage.extract(box, toExtractFe * 1000, true, null) / 1000;
            if (simulated <= 0) break;
            long clampedFe = feFromJ(clampBackExtract(jFromFe(simulated)));
            long extractedFe = ContainerEnergyStorage.extract(box, clampedFe * 1000, false, null) / 1000;
            accountedMfe += jFromFe(extractedFe) / FE_RATE >= 0 ? extractedFe * 1000 : 0;
        }

        long lampPaid = before - totalMfe(box);
        assertTrue(lampPaid >= accountedMfe,
            "拉侧净赚！灯实付 " + lampPaid + " mFE < 协议记账 " + accountedMfe + " mFE");
    }

    /**
     * 诊断 B：只推侧（receive × SIMULATE/EXECUTE 双调用协议），
     * 断言「灯收到的 mFE ≤ 电缆付出的 mFE」（推侧只许漏不许赚）。
     */
    @Test
    @DisplayName("诊断·推侧：灯实收 ≤ 协议记账量（整 FE 量化 + 比例分配 + 零头回收）")
    void diag_pushSide_neverOverreceives() {
        FakeHandler box = new FakeHandler(bulb(64, 0), bulb(37, 5_000_000));
        long before = totalMfe(box);

        long paidMfe = 0;
        for (int t = 0; t < 500; t++) {
            long toInsertFe = 7;   // 素数额度制造分配余数
            long simulated = ContainerEnergyStorage.receive(box, toInsertFe * 1000, true, null) / 1000;
            if (simulated <= 0) break;
            long clampedFe = feFromJ(clampBackExtract(jFromFe(simulated)));
            long insertedFe = ContainerEnergyStorage.receive(box, clampedFe * 1000, false, null) / 1000;
            paidMfe += insertedFe * 1000;
        }

        long lampGot = totalMfe(box) - before;
        assertTrue(lampGot <= paidMfe,
            "推侧净赚！灯实收 " + lampGot + " mFE > 协议付出 " + paidMfe + " mFE");
    }

    /**
     * 诊断 C：单堆单次往返的最小复现——找到产生增益的最小调用序列。
     */
    @Test
    @DisplayName("诊断·最小复现：单堆 SIMULATE+EXECUTE 各一次的账目核对")
    void diag_minimal_singleCycle() {
        // 一堆 37 盏、q 满的 33/50（非整 FE），取 1 FE 探一次、扣一次
        ItemStack stack = bulb(37, 6_600_000);
        FakeHandler box = new FakeHandler(stack);
        long before = totalMfe(box);

        long simulated = ContainerEnergyStorage.extract(box, 1_000, true, null);       // mFE 口径
        long extracted = ContainerEnergyStorage.extract(box, 1_000, false, null);
        long lampPaid = before - totalMfe(box);

        System.out.println("[Minimal] simulated=" + simulated + "mFE, extracted=" + extracted
            + "mFE, lampPaid=" + lampPaid + "mFE, diff(灯实付-返回)=" + (lampPaid - extracted));

        // 返回值应与灯实付一致（返回多少 = 扣多少——记账的基本契约）
        assertEquals(lampPaid, extracted, "extract 返回值与灯实际扣减不一致：差额被凭空记到电缆账上");
    }
}
