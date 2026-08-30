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
 * 阶段二集成测试：真实红石传播 → 涂蜡发电机事件采样 → RE 记账 全链路。
 *
 * <p>场景：拉杆每 2 tick 翻转（4t 方波）放在涂蜡铜块左侧，
 * 发电机应锁相到 4t 周期并持续产出 RE。</p>
 */
class WaxedCopperOscillatorIT {

    @Test
    @DisplayName("拉杆振荡器（4t）→ 涂蜡发电机：锁相 4t、n=1、EMA 功率收敛")
    void leverOscillator_chargesGenerator() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ItemStack lever = living(Items.LEVER, 1);
        ItemStack generator = living(Items.WAXED_COPPER_BLOCK, 4);   // 堆 4 叠 → 偏好周期 4t
        ctx.set(0, lever);
        ctx.set(1, generator);

        ContainerRedstoneData redstone = new ContainerRedstoneData();
        ContainerPowerData power = new ContainerPowerData();
        LivingWaxedCopperFunction fn = new LivingWaxedCopperFunction();

        TickContext tick = new TickContext(ctx);
        tick.setFunctionSlots(Map.of(
            LivingLeverFunction.ID, Set.of(0),
            LivingWaxedCopperFunction.ID, Set.of(1)));
        // 预置容器级数据（生产环境由 SimpleContainerContext 持久化，测试直接注入）
        tick.redstoneData = redstone;
        tick.powerData = power;

        List<LivingItemFunction.SlotEntry> entries =
            List.of(new LivingItemFunction.SlotEntry(1, generator));

        for (int t = 0; t < 40; t++) {
            // 每 2 tick 翻转拉杆 → 4t 周期方波
            LivingItemManager.setLeverData(lever, new LivingLeverData((t / 2) % 2 == 0));
            redstone.resetProcessedFlag();
            redstone.calculate(ctx, tick);                    // priority 2：红石传播
            fn.tickContainerData(entries, ctx, tick);         // priority 3：电力采样
        }

        GeneratorState gen = power.getGenerator(1);
        ChannelState channel = gen.primaryChannel();
        PathState left = channel.path(gen.dirDirectPath(2));   // 拉杆在发电机左侧 → E_LEFT 直连路

        assertEquals(4.0, left.periodTicks(), 0.1);
        assertTrue(left.hasUsablePhase());
        assertEquals(1, left.domainN());
        assertTrue(channel.regularity() > 0.95);
        // n=1 → 合因子恒 ×1（调谐无感），但锁相与规律度必须成立
        assertEquals(1.0, channel.factorFor(gen.dirDirectPath(2), gen.preferredPeriod()), 1e-9);
        // 稳态 30 RE/t（每 4t 两次跳变、每次 15×1×4），EMA 40 tick 已收敛
        assertTrue(power.getEmaPowerRe() > 25);
        assertTrue(power.getEmaPowerFe() >= 1);
    }

    private static ItemStack living(net.minecraft.world.item.Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }
}
