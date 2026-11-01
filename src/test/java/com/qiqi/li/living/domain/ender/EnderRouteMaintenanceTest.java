package com.qiqi.li.living.domain.ender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.container.TickableContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * 路由回收的注册方自查（{@code EnderRouteMaintenance} →
 * {@code EnderChannelRegistry.validateRoutes} 按 {@code RouteRegistrar} 能力发现）：
 *
 * <p>注册方离场与末影箱离场两类失效都由本领域自查——注册方槽位按能力发现、
 * 末影箱键表自给（引自己的 ID），不再依赖漏斗域互传槽位集合。</p>
 */
class EnderRouteMaintenanceTest {

    private static final BlockPos POS = new BlockPos(5, 64, 5);
    private static final String KEY = "chest_5_64_5";
    private static final EnderChannelKey CHANNEL = EnderChannelKey.publicChannel(2);

    private EnderChannelRegistry registry;
    private final EnderRouteMaintenance maintenance = new EnderRouteMaintenance();

    @BeforeEach
    void setUp() {
        registry = EnderChannelRegistry.getInstance();
        registry.clearAll();
    }

    @AfterEach
    void tearDown() {
        registry.clearAll();
    }

    /** 条目：源槽 0（钻石）、注册方槽 1、目标槽 3（公共频道 2 的活末影箱）。 */
    private static EnderChannelEntry entry() {
        return new EnderChannelEntry("minecraft:diamond", Level.OVERWORLD, POS, 0, 1, KEY, 3, KEY, null);
    }

    private static ItemStack livingHopper() {
        ItemStack stack = new ItemStack(Items.HOPPER);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static ItemStack livingEnderChest(int count) {
        ItemStack stack = new ItemStack(Items.ENDER_CHEST, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    /** 槽 0 = 钻石（源，isSource 比对面），槽 1 = 注册方，槽 3 = 末影箱。 */
    private static TickableContainerContext ctx(ItemStack registrar, ItemStack enderChest) {
        Level level = mock(Level.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        TickableContainerContext ctx = mock(TickableContainerContext.class);
        when(ctx.getLevel()).thenReturn(level);
        when(ctx.getBlockPos()).thenReturn(POS);
        when(ctx.getContainerKey()).thenReturn(KEY);
        when(ctx.getSize()).thenReturn(27);
        when(ctx.getItem(anyInt())).thenAnswer(inv -> {
            int slot = inv.getArgument(0, Integer.class);
            if (slot == 0) return new ItemStack(Items.DIAMOND);
            if (slot == 1) return registrar;
            if (slot == 3) return enderChest;
            return ItemStack.EMPTY;
        });
        return ctx;
    }

    @Test
    @DisplayName("注册方（活漏斗）与末影箱都在场：条目保留")
    void bothPresent_kept() {
        TickableContainerContext ctx = ctx(livingHopper(), livingEnderChest(2));
        registry.offer(CHANNEL, entry());

        Map<Integer, EnderChannelKey> keys = EnderChannelKey.ofSlots(ctx, Set.of(3));
        assertEquals(0, registry.validateRoutes(ctx, keys), "两侧都在场不得误清");
        assertTrue(registry.contains(CHANNEL, entry()));
    }

    @Test
    @DisplayName("注册方槽位换成普通物品：按 RouteRegistrar 能力发现判离场，条目清理")
    void registrarGone_removed() {
        TickableContainerContext ctx = ctx(new ItemStack(Items.DIAMOND), livingEnderChest(2));
        registry.offer(CHANNEL, entry());

        Map<Integer, EnderChannelKey> keys = EnderChannelKey.ofSlots(ctx, Set.of(3));
        assertEquals(1, registry.validateRoutes(ctx, keys), "注册方不在 ⇒ 路由失效");
        assertFalse(registry.contains(CHANNEL, entry()));
    }

    @Test
    @DisplayName("末影箱全部离场：空键表 ⇒ 目标检查判死，条目清理")
    void enderChestGone_removed() {
        TickableContainerContext ctx = ctx(livingHopper(), ItemStack.EMPTY);
        registry.offer(CHANNEL, entry());

        Map<Integer, EnderChannelKey> keys = EnderChannelKey.ofSlots(ctx, Set.of());
        assertEquals(1, registry.validateRoutes(ctx, keys), "目标不在 ⇒ 路由失效");
        assertFalse(registry.contains(CHANNEL, entry()));
    }

    @Test
    @DisplayName("写回钩子端到端：从 TickContext 取自己的槽位集合驱动校验")
    void writebackHook_endToEnd() {
        TickableContainerContext ctx = ctx(livingHopper(), livingEnderChest(2));
        registry.offer(CHANNEL, entry());

        TickContext tick = new TickContext(ctx);
        tick.setFunctionSlots(Map.of(LivingEnderChestFunction.ID, Set.of(3)));
        maintenance.onWriteback(ctx, tick);
        assertTrue(registry.contains(CHANNEL, entry()), "两侧都在场 ⇒ 钩子不误清");

        // 末影箱槽位清空（离场）⇒ 钩子下一轮清掉路由
        tick.setFunctionSlots(Map.of());
        maintenance.onWriteback(ctx, tick);
        assertFalse(registry.contains(CHANNEL, entry()), "键表为空 ⇒ 目标全失效");
    }
}
