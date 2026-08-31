package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.redstone.ContainerRedstoneData;
import com.qiqi.li.living.domain.redstone.LivingLeverData;
import com.qiqi.li.living.domain.redstone.LivingLeverFunction;
import com.qiqi.li.testutil.FakeContainerContext;

/**
 * 阶段三集成测试：感应耦合链路（§3.5）。
 *
 * <p>布局（9 宽网格，4t 拉杆振荡器在槽 0）：</p>
 * <pre>
 *   [拉杆][A 堆4][B 堆4][C 堆4]
 * </pre>
 * <p>验证：A 直连发电；B 经 A 耦合（1 跳）发电；C 经 B 中继（2 跳）发电；
 * B 的中继不回传 A（无自激回环，A 的感应路保持静默）。</p>
 */
class WaxedCopperCouplingIT {

    @Test
    @DisplayName("耦合链：A 直连 → B 一跳 → C 两跳，中继不回传")
    void couplingChain_relayWithoutBackEcho() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ItemStack lever = living(Items.LEVER, 1);
        ItemStack a = living(Items.WAXED_COPPER_BLOCK, 4);
        ItemStack b = living(Items.WAXED_COPPER_BLOCK, 4);
        ItemStack c = living(Items.WAXED_COPPER_BLOCK, 4);
        ctx.set(0, lever);
        ctx.set(1, a);
        ctx.set(2, b);
        ctx.set(3, c);

        ContainerRedstoneData redstone = new ContainerRedstoneData();
        ContainerPowerData power = new ContainerPowerData();
        LivingWaxedCopperFunction fn = new LivingWaxedCopperFunction();

        TickContext tick = new TickContext(ctx);
        tick.setFunctionSlots(Map.of(
            LivingLeverFunction.ID, Set.of(0),
            LivingWaxedCopperFunction.ID, Set.of(1, 2, 3)));
        tick.redstoneData = redstone;
        tick.powerData = power;

        List<LivingItemFunction.SlotEntry> entries = List.of(
            new LivingItemFunction.SlotEntry(1, a),
            new LivingItemFunction.SlotEntry(2, b),
            new LivingItemFunction.SlotEntry(3, c));

        for (int t = 0; t < 40; t++) {
            LivingItemManager.setLeverData(lever, new LivingLeverData((t / 2) % 2 == 0));
            redstone.resetProcessedFlag();
            redstone.calculate(ctx, tick);
            fn.tickContainerData(entries, ctx, tick);
        }

        // ── A：直连（拉杆在左侧 → E_LEFT 直连路） ──
        GeneratorState genA = power.getGenerator(1);
        ChannelState chA = genA.primaryChannel();
        assertEquals(4.0, chA.path(genA.dirDirectPath(2)).periodTicks(), 0.1);
        // n=1, unlock=1.0*1/4=0.25, delta=15, log₂(15)≈3.91 → factor=3.91^1.25≈5.5
        assertEquals(Math.pow(Math.log(15)/Math.log(2), 1.25),
            chA.factorFor(genA.dirDirectPath(2), genA.preferredPeriod(), 15), 1e-3);

        // ── B：感应路（来自左侧 A → E_LEFT 感应路）锁相 4t ──
        GeneratorState genB = power.getGenerator(2);
        ChannelState chB = genB.primaryChannel();
        PathState bVirtual = chB.path(genB.dirVirtualPath(2));
        assertEquals(4.0, bVirtual.periodTicks(), 0.1);
        assertTrue(bVirtual.hasUsablePhase());
        assertTrue(power.getEmaPowerRe() > 0 && power.getGenerator(2) != null);
        // B 的直连路（无信号源）保持静默
        assertEquals(0, chB.path(genB.dirDirectPath(0)).domainN());

        // ── C：两跳中继（来自左侧 B → E_LEFT 感应路）同样锁相 ──
        GeneratorState genC = power.getGenerator(3);
        ChannelState chC = genC.primaryChannel();
        PathState cVirtual = chC.path(genC.dirVirtualPath(2));
        assertEquals(4.0, cVirtual.periodTicks(), 0.1);
        assertTrue(cVirtual.hasUsablePhase());

        // ── 防回环：B 的中继不回传 A → A 的右侧感应路从未收到任何跳变（period=0） ──
        assertEquals(0.0, chA.path(genA.dirVirtualPath(3)).periodTicks(), 1e-9);
        assertEquals(0, chA.path(genA.dirVirtualPath(3)).lastValue());
    }

    private static ItemStack living(net.minecraft.world.item.Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }
}