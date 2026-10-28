package com.qiqi.li.living.domain.tnt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemFunction.SlotEntry;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.RedstoneSensor;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * 红石信号 → 活 TNT 点燃（{@code LivingTntFunction} 的「未点燃 + 信号 > 0」分支，此前零测试）。
 *
 * <p>只钉 **TNT 侧的分支**：信号从端口读进来后是否点燃并写回。红石传播本身由
 * `ContainerRedstoneDataTest`（30 项）覆盖 —— 这里把端口换成 mock 是为了让失败点唯一：
 * 若将来把「读信号」写成别的东西，这条会红。</p>
 *
 * <p>⚠️ 别在这里复刻真实传播：需要同时喂 {@code functionSlots}（哪些槽是红石元件），
 * 否则红石块会被当普通物品、恒无信号（试过，踩坑）。</p>
 */
class TntRedstoneIgniteTest {

    private static final int SIZE = 27;
    private static final int WIDTH = 9;
    private static final int TNT_SLOT = 12;

    private static ItemStack livingTnt() {
        ItemStack stack = new ItemStack(Items.TNT);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static boolean ignited(ItemStack stack) {
        return LivingTntData.of(stack).explosion().ignited();
    }

    private static TickContext tickWithSignal(int signal) {
        RedstoneSensor sensor = mock(RedstoneSensor.class);
        when(sensor.maxSensedSignal(TNT_SLOT)).thenReturn(signal);
        TickContext tick = mock(TickContext.class);
        when(tick.getSensor(org.mockito.ArgumentMatchers.any())).thenReturn(sensor);
        return tick;
    }

    private static void tickTnt(FakeContainerContext ctx, TickContext tick) {
        new LivingTntFunction().tick(
            List.of(new SlotEntry(TNT_SLOT, ctx.getItem(TNT_SLOT))), ctx, tick, mock(Level.class));
    }

    @Test
    @DisplayName("槽位有红石信号 ⇒ 点燃并写回")
    void signal_ignitesTnt() {
        FakeContainerContext ctx = new FakeContainerContext(SIZE, WIDTH);
        ctx.set(TNT_SLOT, livingTnt());

        tickTnt(ctx, tickWithSignal(15));

        assertTrue(ignited(ctx.getItem(TNT_SLOT)), "有信号时活 TNT 必须被点燃");
    }

    @Test
    @DisplayName("无信号 ⇒ 不点燃")
    void noSignal_doesNotIgnite() {
        FakeContainerContext ctx = new FakeContainerContext(SIZE, WIDTH);
        ctx.set(TNT_SLOT, livingTnt());

        tickTnt(ctx, tickWithSignal(0));

        assertFalse(ignited(ctx.getItem(TNT_SLOT)));
    }
}
