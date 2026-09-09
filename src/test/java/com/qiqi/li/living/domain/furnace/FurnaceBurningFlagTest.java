package com.qiqi.li.living.domain.furnace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.domain.runtime.LivingItemRuntimeData;
import com.qiqi.li.testutil.FakeContainerContext;

/**
 * 燃烧标志组件回归测试（图标 active/idle 切换数据源）。
 *
 * <p>回归背景：burnTime 迁运行时缓存（b064865）后，客户端 ItemStack 上
 * 不再有燃烧状态，图标谓词 {@code LivingFurnaceFunction.isBurning(stack)}
 * 读组件恒 false，熔炉图标永远停在 furnace_idle.png。修复：tick 在燃烧状态
 * 翻转时写轻量布尔组件 {@code LIVING_FURNACE_BURNING} 并同步槽位。</p>
 */
class FurnaceBurningFlagTest {

    private static final int FURNACE_SLOT = 4;

    private static ItemStack livingFurnace() {
        ItemStack stack = new ItemStack(Items.FURNACE);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    /**
     * tick 入口要求非客户端 Level。isClientSide 是 public final 字段而非方法，
     * mock 实例上该字段默认 false（= 服务端），无需也无法用 when() 打桩；
     * 配方查询在无输入物品时不会触达 RecipeManager（tickTransform 短路返回）。
     */
    private static Level mockLevel() {
        return org.mockito.Mockito.mock(Level.class);
    }

    private static void tickOnce(FakeContainerContext ctx, int slot) {
        new LivingFurnaceFunction().tick(
            List.of(new LivingItemFunction.SlotEntry(slot, ctx.getItem(slot))),
            ctx, new TickContext(ctx), mockLevel());
    }

    @Test
    @DisplayName("运行时缓存显示燃烧中 → 标志组件写入 true + 槽位同步（图标切 active）")
    void burningFlag_writtenOnIgnite() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.set(FURNACE_SLOT, livingFurnace());
        // 预置运行时缓存：燃烧中（burnTime > 0）。无输入物品 → pauseTick 分支
        // tick(1) 扣 1 点余热，仍 > 0，本 tick 结束时仍判定为燃烧中
        ContainerRuntimeCache.update(ctx.getContainerKey(), FURNACE_SLOT,
            LivingItemRuntimeData.forFurnace(0, 200, 1600, null));

        tickOnce(ctx, FURNACE_SLOT);

        assertTrue(LivingItemManager.isFurnaceBurning(ctx.getItem(FURNACE_SLOT)),
            "燃烧中应写入标志组件，图标才能切到 furnace_active");
        assertTrue(ctx.syncedSlots.contains(FURNACE_SLOT), "标志翻转应触发槽位同步");
    }

    @Test
    @DisplayName("运行时缓存无燃烧（熄灭）→ 标志组件移除 + 槽位同步（图标切 idle）")
    void burningFlag_clearedOnExtinguish() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ItemStack furnace = livingFurnace();
        LivingItemManager.setFurnaceBurning(furnace, true); // 模拟先前燃烧中
        ctx.set(FURNACE_SLOT, furnace);
        // 运行时缓存：burnTime=0（熄灭）
        ContainerRuntimeCache.update(ctx.getContainerKey(), FURNACE_SLOT,
            LivingItemRuntimeData.forFurnace(0, 200, 0, null));

        tickOnce(ctx, FURNACE_SLOT);

        assertFalse(LivingItemManager.isFurnaceBurning(ctx.getItem(FURNACE_SLOT)),
            "熄灭应移除标志组件，图标切回 furnace_idle");
        assertTrue(ctx.syncedSlots.contains(FURNACE_SLOT), "标志翻转应触发槽位同步");
    }

    @Test
    @DisplayName("稳态（标志与实际一致）→ 不写组件、零槽位同步")
    void burningFlag_stable_noExtraSync() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.set(FURNACE_SLOT, livingFurnace());
        ContainerRuntimeCache.removeContainer(ctx.getContainerKey());
        ctx.syncedSlots.clear();

        tickOnce(ctx, FURNACE_SLOT);

        assertFalse(LivingItemManager.isFurnaceBurning(ctx.getItem(FURNACE_SLOT)));
        assertEquals(0, ctx.syncedSlots.size(), "稳态下不应有任何槽位同步");
    }

    @Test
    @DisplayName("自愈：跨容器搬运后缓存清零，过期 true 标志被修正回 idle")
    void burningFlag_selfHeals_staleTrueFlag() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ItemStack furnace = livingFurnace();
        LivingItemManager.setFurnaceBurning(furnace, true); // 搬运前燃烧中，标志过期
        ctx.set(FURNACE_SLOT, furnace);
        ContainerRuntimeCache.removeContainer(ctx.getContainerKey()); // 新容器无缓存

        tickOnce(ctx, FURNACE_SLOT);

        assertFalse(LivingItemManager.isFurnaceBurning(ctx.getItem(FURNACE_SLOT)),
            "无燃料无缓存时，过期 true 标志应被修正（自愈）");
    }

    @Test
    @DisplayName("堆叠兼容：燃烧标志在 getIgnoredComponentTypes 中，不同燃烧状态可堆叠")
    void burningFlag_ignoredForStacking() {
        ItemStack burning = livingFurnace();
        LivingItemManager.setFurnaceBurning(burning, true);
        ItemStack idle = livingFurnace();

        assertTrue(ItemStack.isSameItemSameComponents(burning, idle),
            "燃烧状态不同的两个活熔炉应可堆叠（标志被忽略）");
    }
}
