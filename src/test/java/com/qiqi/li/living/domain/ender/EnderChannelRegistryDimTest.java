package com.qiqi.li.living.domain.ender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.container.ContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * 活末影箱路由表的**维度隔离**回归（2026-10-27 审查待拍板 #2，2026-10-28 修）。
 *
 * <p>缺陷形态：容器键是裸稳定键（{@code chest_x_y_z}），跨维度同坐标同值 ⇒
 * 去重键、位置索引、按位置清理、区块卸载四处都会把「另一个维度的同一位置」当成自己。
 * 后果按入口不同：路由**注册不进去**（contains 误判重复）或**被误删**（清理/卸载）。</p>
 *
 * <p>修复口径：维度进入去重键与位置索引（外层按维度分桶）；玩家背包键
 * （{@code player_<uuid>}）跨维度同一身份，<b>不</b>加维度。</p>
 */
class EnderChannelRegistryDimTest {

    private static final BlockPos POS = new BlockPos(5, 64, 5);
    private static final String KEY = "chest_5_64_5";
    private static final EnderChannelKey CHANNEL = EnderChannelKey.publicChannel(2);

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

    /** 同坐标、同槽位、同物品、同容器键 —— 只有维度不同。 */
    private static EnderChannelEntry entry(ResourceKey<Level> dim) {
        return new EnderChannelEntry("minecraft:diamond", dim, POS, 0, 1, KEY, 3, KEY);
    }

    private static Level levelOf(ResourceKey<Level> dim) {
        Level level = mock(Level.class);
        when(level.dimension()).thenReturn(dim);
        return level;
    }

    @Test
    @DisplayName("跨维度同坐标都能注册（去重键含维度，不会把下界那条当成重复）")
    void samePositionDifferentDimension_bothRegistered() {
        EnderChannelEntry overworld = entry(Level.OVERWORLD);
        EnderChannelEntry nether = entry(Level.NETHER);

        assertTrue(registry.offer(CHANNEL, overworld));
        assertFalse(registry.contains(CHANNEL, nether),
            "下界条目与主世界条目是两条路由，不能被判成重复");
        assertTrue(registry.offer(CHANNEL, nether));
        assertTrue(registry.contains(CHANNEL, nether));
    }

    @Test
    @DisplayName("按位置+槽位清理只删指定维度（不误删另一维度的同坐标路由）")
    void removeByPosition_onlyTargetDimension() {
        EnderChannelEntry overworld = entry(Level.OVERWORLD);
        EnderChannelEntry nether = entry(Level.NETHER);
        registry.offer(CHANNEL, overworld);
        registry.offer(CHANNEL, nether);

        registry.removeByPositionAndSlotFromAllChannels(Level.OVERWORLD, POS, 0);

        assertFalse(registry.contains(CHANNEL, overworld), "主世界那条应被清掉");
        assertTrue(registry.contains(CHANNEL, nether), "下界同坐标那条必须保留");
    }

    @Test
    @DisplayName("区块卸载按维度隔离（主世界卸块不删下界路由）")
    void chunkUnload_doesNotTouchOtherDimension() {
        EnderChannelEntry overworld = entry(Level.OVERWORLD);
        EnderChannelEntry nether = entry(Level.NETHER);
        registry.offer(CHANNEL, overworld);
        registry.offer(CHANNEL, nether);

        registry.onChunkUnload(levelOf(Level.OVERWORLD), new ChunkPos(POS));

        assertFalse(registry.contains(CHANNEL, overworld), "同维度同区块的路由应被卸载清掉");
        assertTrue(registry.contains(CHANNEL, nether), "卸载主世界区块不得删下界路由");
    }

    @Test
    @DisplayName("validateRoutes 按维度隔离（主世界容器校验不误删下界同键路由）")
    void validateRoutes_doesNotRemoveOtherDimension() {
        EnderChannelEntry nether = entry(Level.NETHER);
        registry.offer(CHANNEL, nether);

        Level overworld = levelOf(Level.OVERWORLD);
        ContainerContext ctx = mock(ContainerContext.class);
        when(ctx.getLevel()).thenReturn(overworld);
        when(ctx.getBlockPos()).thenReturn(POS);
        when(ctx.getContainerKey()).thenReturn(KEY);
        when(ctx.getItem(anyInt())).thenReturn(ItemStack.EMPTY);

        int removed = registry.validateRoutes(ctx, null, null);

        assertEquals(0, removed, "下界条目不应被主世界的校验删掉");
        assertTrue(registry.contains(CHANNEL, nether));
    }
}
