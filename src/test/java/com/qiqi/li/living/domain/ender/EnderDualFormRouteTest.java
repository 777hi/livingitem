package com.qiqi.li.living.domain.ender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.container.ContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * 活末影箱**双形态往返**的不变量测试（2026-10-29 刷物品根因的补强）。
 *
 * <p>已有 {@link EnderChannelRegistryDimTest} 钉住两个单步方向：
 * 物品 ctx 清旧方块条目 / 方块 ctx 清旧物品条目。这里补的是真实事故的形态——
 * 玩家反复拾取/放置（方块 ↔ 物品交替），每一步都必须保证
 * <b>同一容器 UUID 至多一条路由</b>；否则两条路由会抽同一份内容的两种视图 ⇒ 刷物品。</p>
 *
 * <p>另补两个防误删：旧形态条目在<b>别的频道</b>也要被清；<b>别的 UUID</b> 的合法条目不得被牵连。</p>
 */
class EnderDualFormRouteTest {

    private static final BlockPos POS = new BlockPos(5, 64, 5);
    private static final String KEY = "chest_5_64_5";
    private static final String ITEM = "minecraft:diamond";
    private static final EnderChannelKey CHANNEL = EnderChannelKey.publicChannel(2);
    private static final EnderChannelKey OTHER_CHANNEL = EnderChannelKey.publicChannel(3);

    private EnderChannelRegistry registry;

    @BeforeEach
    void setUp() {
        registry = EnderChannelRegistry.getInstance();
        registry.clearAll();
    }

    @AfterEach
    void tearDown() {
        registry.clearAll();
    }

    private static EnderChannelEntry blockForm(UUID uuid) {
        return new EnderChannelEntry(ITEM, Level.OVERWORLD, POS, 0, 1, KEY, 3, KEY, uuid);
    }

    private static EnderChannelEntry blockFormAt(UUID uuid, BlockPos pos, String key) {
        return new EnderChannelEntry(ITEM, Level.OVERWORLD, pos, 0, 1, key, 3, key, uuid);
    }

    private static EnderChannelEntry itemForm(UUID uuid) {
        String key = "cuuid_" + uuid;
        return new EnderChannelEntry(ITEM, Level.OVERWORLD, null, 0, 1, key, 3, key, uuid);
    }

    /** 非空源 + 正确形态/键，保证 valid 条目不会被 isSource 分支误删。 */
    private static ContainerContext ctx(UUID uuid, boolean blockForm) {
        Level level = mock(Level.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        ContainerContext ctx = mock(ContainerContext.class);
        when(ctx.getLevel()).thenReturn(level);
        when(ctx.getContainerUuid()).thenReturn(uuid);
        when(ctx.getBlockPos()).thenReturn(blockForm ? POS : null);
        when(ctx.getContainerKey()).thenReturn(blockForm ? KEY : "cuuid_" + uuid);
        when(ctx.getItem(anyInt())).thenReturn(new ItemStack(Items.DIAMOND));
        return ctx;
    }

    @Test
    @DisplayName("方块↔物品交替 20 次:每一步都只剩当前形态一条路由")
    void alternatingForms_neverLeaveTwoRoutes() {
        UUID uuid = UUID.randomUUID();
        registry.offer(CHANNEL, blockForm(uuid));

        for (int i = 0; i < 20; i++) {
            boolean blockForm = i % 2 == 0;
            EnderChannelEntry current = blockForm ? blockForm(uuid) : itemForm(uuid);
            EnderChannelEntry stale = blockForm ? itemForm(uuid) : blockForm(uuid);

            registry.validateRoutes(ctx(uuid, blockForm), null, null);
            registry.offer(CHANNEL, current);

            assertTrue(registry.contains(CHANNEL, current),
                "第 " + i + " 步：当前形态必须可用");
            assertFalse(registry.contains(CHANNEL, stale),
                "第 " + i + " 步：旧形态条目必须已清（双路由 = 双抽 = 刷物品）");
        }
    }

    @Test
    @DisplayName("旧方块形态在别的频道:同一 UUID 校验时也要清掉")
    void staleForm_inOtherChannel_isRemoved() {
        UUID uuid = UUID.randomUUID();
        registry.offer(CHANNEL, blockForm(uuid));
        registry.offer(OTHER_CHANNEL, itemForm(uuid));

        int removed = registry.validateRoutes(ctx(uuid, false), null, null);

        assertEquals(1, removed, "旧方块条目被清；当前物品条目保留");
        assertFalse(registry.contains(CHANNEL, blockForm(uuid)));
        assertTrue(registry.contains(OTHER_CHANNEL, itemForm(uuid)));
    }

    @Test
    @DisplayName("别的容器 UUID:校验本容器不得误删它")
    void otherUuid_untouched() {
        UUID mine = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        BlockPos otherPos = POS.offset(10, 0, 0);
        String otherKey = "chest_15_64_5";
        registry.offer(CHANNEL, blockForm(mine));
        registry.offer(CHANNEL, blockFormAt(other, otherPos, otherKey));

        registry.validateRoutes(ctx(mine, false), null, null);

        assertFalse(registry.contains(CHANNEL, blockForm(mine)));
        assertTrue(registry.contains(CHANNEL, blockFormAt(other, otherPos, otherKey)),
            "别的 UUID 的合法条目不得被牵连");
    }
}
