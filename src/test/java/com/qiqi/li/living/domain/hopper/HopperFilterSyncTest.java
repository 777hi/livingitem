package com.qiqi.li.living.domain.hopper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.testutil.FakeContainerContext;

/**
 * 漏斗过滤链回写回归测试（黑白名单 tooltip 展示数据源）。
 *
 * <p>回归背景：「tooltip优化，nbt数据简化」（b064865）删除了 tick 里
 * {@code data.withFilter(filter)} 的 DataComponent 写回，但 tooltip 仍读
 * {@code LivingHopperData.filter()} → 恒 EMPTY，黑白名单链在 tooltip 上
 * 永远显示无规则。修复：过滤链迁至独立组件 {@code LIVING_HOPPER_FILTER}，
 * tick 在快照重建结果变化时回写（稳态零写入），组件进堆叠忽略清单。</p>
 *
 * <p>过滤语义（living-hopper-tech.md §2.4.2）：
 * 邻居 target 指向我 → 邻居的 source 物品 = 我的黑名单；
 * 邻居 source 指向我 → 邻居的 target 物品 = 我的白名单。</p>
 */
class HopperFilterSyncTest {

    private static final int HOPPER_A = 4;   // 被观察漏斗
    private static final int HOPPER_B = 13;  // A 正下方
    private static final int B_SOURCE = 22;  // B 的下方

    private FakeContainerContext ctx;

    @AfterEach
    void cleanRuntimeCache() {
        if (ctx != null) {
            ContainerRuntimeCache.removeContainer(ctx.getContainerKey());
        }
    }

    private static ItemStack livingHopper(Pos2D source, Pos2D target) {
        ItemStack stack = new ItemStack(Items.HOPPER);
        LivingItemManager.setLiving(stack, true);
        LivingItemManager.setHopperData(stack, LivingItemManager.getHopperData(stack)
            .withDirection(new DirectionTransferData(source, target)));
        return stack;
    }

    private static Level mockLevel() {
        // isClientSide 是 public final 字段，mock 默认 false（= 服务端），无需打桩
        return org.mockito.Mockito.mock(Level.class);
    }

    private void tick() {
        List<LivingItemFunction.SlotEntry> entries = List.of(
            new LivingItemFunction.SlotEntry(HOPPER_A, ctx.getItem(HOPPER_A)),
            new LivingItemFunction.SlotEntry(HOPPER_B, ctx.getItem(HOPPER_B)));
        new LivingHopperFunction().tick(entries, ctx, new TickContext(ctx), mockLevel());
    }

    @Test
    @DisplayName("黑名单链：邻居 target 指向我 → 邻居 source 物品写入我的过滤组件 + 槽位同步")
    void blacklist_writtenFromNeighborSource() {
        ctx = new FakeContainerContext(27, 9);
        ctx.set(HOPPER_A, livingHopper(Pos2D.LEFT, Pos2D.RIGHT));
        ctx.set(HOPPER_B, livingHopper(Pos2D.DOWN, Pos2D.UP)); // B: source=22, target=4(A)
        ctx.set(B_SOURCE, new ItemStack(Items.DIRT));

        tick();

        var filter = LivingItemManager.getHopperFilter(ctx.getItem(HOPPER_A));
        assertTrue(filter.blacklist().contains("minecraft:dirt"),
            "B 的 source 物品应成为 A 的黑名单，实际: " + filter);
        assertEquals(List.of(B_SOURCE), filter.blacklistSlots());
        assertTrue(filter.whitelist().isEmpty());
        // B 自己的过滤为空（A 的 source/target 都不指向 B）
        assertTrue(LivingItemManager.getHopperFilter(ctx.getItem(HOPPER_B)).blacklist().isEmpty());
        assertTrue(ctx.syncedSlots.contains(HOPPER_A), "规则变化应触发槽位同步");
    }

    @Test
    @DisplayName("白名单链：邻居 source 指向我 → 邻居 target 物品写入我的白名单")
    void whitelist_writtenFromNeighborTarget() {
        ctx = new FakeContainerContext(27, 9);
        ctx.set(HOPPER_A, livingHopper(Pos2D.LEFT, Pos2D.RIGHT));
        // C 在 A 正下方：source=UP(=A)，target=DOWN(22 放金锭)
        ctx.set(HOPPER_B, livingHopper(Pos2D.UP, Pos2D.DOWN));
        ctx.set(B_SOURCE, new ItemStack(Items.GOLD_INGOT));

        tick();

        var filter = LivingItemManager.getHopperFilter(ctx.getItem(HOPPER_A));
        assertTrue(filter.whitelist().contains("minecraft:gold_ingot"),
            "邻居的 target 物品应成为 A 的白名单，实际: " + filter);
        assertEquals(List.of(B_SOURCE), filter.whitelistSlots());
        assertTrue(filter.blacklist().isEmpty());
    }

    @Test
    @DisplayName("稳态：规则无变化 → 不重复写组件、不重复同步")
    void steadyState_noExtraSync() {
        ctx = new FakeContainerContext(27, 9);
        ctx.set(HOPPER_A, livingHopper(Pos2D.LEFT, Pos2D.RIGHT));
        ctx.set(HOPPER_B, livingHopper(Pos2D.DOWN, Pos2D.UP));
        ctx.set(B_SOURCE, new ItemStack(Items.DIRT));

        tick();
        ctx.syncedSlots.clear();
        tick();

        assertTrue(ctx.syncedSlots.isEmpty(), "规则未变时不应有任何槽位同步");
    }

    @Test
    @DisplayName("自愈：邻居货物移走 → 过期黑名单被清除（组件移除 + 同步）")
    void selfHeals_whenCargoLeaves() {
        ctx = new FakeContainerContext(27, 9);
        ctx.set(HOPPER_A, livingHopper(Pos2D.LEFT, Pos2D.RIGHT));
        ctx.set(HOPPER_B, livingHopper(Pos2D.DOWN, Pos2D.UP));
        ctx.set(B_SOURCE, new ItemStack(Items.DIRT));

        tick();
        assertTrue(LivingItemManager.getHopperFilter(ctx.getItem(HOPPER_A))
            .blacklist().contains("minecraft:dirt"));

        // 货物移走 → 内容签名变化 → 快照重建 → A 的规则应为空
        // 货物移走 → 内容签名变化 → 快照重建 → A 的规则应为空
        ctx.setItem(B_SOURCE, ItemStack.EMPTY);
        ctx.syncedSlots.clear();
        tick();

        assertTrue(LivingItemManager.getHopperFilter(ctx.getItem(HOPPER_A)).equals(
                com.qiqi.li.living.transfer.FilterData.EMPTY),
            "货物移走后黑名单应清空（EMPTY 时组件应被移除）");
        assertTrue(ctx.syncedSlots.contains(HOPPER_A), "规则清空应触发槽位同步");
    }

    @Test
    @DisplayName("堆叠兼容：过滤链在 getIgnoredComponentTypes 中，不同规则的漏斗可堆叠")
    void filter_ignoredForStacking() {
        ItemStack withFilter = livingHopper(Pos2D.LEFT, Pos2D.RIGHT);
        LivingItemManager.setHopperFilter(withFilter,
            new com.qiqi.li.living.transfer.FilterData(
                List.of("minecraft:dirt"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        ItemStack noFilter = livingHopper(Pos2D.LEFT, Pos2D.RIGHT);

        assertTrue(ItemStack.isSameItemSameComponents(withFilter, noFilter),
            "不同过滤规则的活漏斗应可堆叠（规则是环境派生数据）");
    }
}
