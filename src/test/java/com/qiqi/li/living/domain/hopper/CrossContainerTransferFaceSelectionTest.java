package com.qiqi.li.living.domain.hopper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.model.ResolvedSlots;
import com.qiqi.li.living.transfer.FilterData;
import com.qiqi.li.testutil.FakeContainerContext;

/**
 * 多方块容器「跨容器传输的面选取」回归测试（living-hopper-tech.md §6.4）。
 *
 * <p>背景：大箱子在世界里占 2 个方块，GUI 的 4 个方向映射到<b>6 个</b>外部面 ——
 * 左/右（连接轴）各 1 个，上/下（facing 轴）各 <b>2 个</b>（两个半箱各自的正面/背面，
 * 是两个不同的方块）。旧实现按<b>方向</b>选单个基准块（下/右→第一块，上/左→第二块），
 * 与漏斗所在槽位无关 ⇒ 漏斗在后半箱时向下推送，推出去的是<b>前半箱正面</b>的方块
 * （静默推错面、不报错）。</p>
 *
 * <p>现状：{@code getBasePosCandidates()} 按「发起槽位所属的那块」定首选、其余作备选，
 * 调用方<b>逐个尝试</b>（用户 2026-09-22 拍板"两个都试"）。本测试钉住三件事：
 * ① 首选块 = hostSlot 所属块（含三方块容器）；② 两个面都会被尝试；
 * ③ 首选面无容器时能退到备选面。</p>
 */
class CrossContainerTransferFaceSelectionTest {

    /** 大箱子：LEFT 半箱在原点、面朝北 ⇒ RIGHT 半箱在其东侧（connectedDir = facing.getClockWise()） */
    private static final BlockPos LEFT_HALF = new BlockPos(0, 64, 0);
    private static final BlockPos RIGHT_HALF = LEFT_HALF.east();
    /** GUI 下 → 世界 NORTH（facing=NORTH 时旋转 0 次）⇒ 各半箱正面相邻的方块 */
    private static final BlockPos FRONT_OF_LEFT = LEFT_HALF.relative(Direction.NORTH);
    private static final BlockPos FRONT_OF_RIGHT = RIGHT_HALF.relative(Direction.NORTH);

    private FakeContainerContext ctx;

    @AfterEach
    void cleanRuntimeCache() {
        if (ctx != null) {
            ContainerRuntimeCache.removeContainer(ctx.getContainerKey());
        }
    }

    // ── 候选块排序（纯逻辑，无需 Level） ──────────────────────────────

    @Test
    @DisplayName("单方块容器：候选只有默认位置（与历史行为一致，无回归）")
    void singleBlock_singleCandidate() {
        assertEquals(List.of(LEFT_HALF),
            CrossContainerTransfer.getBasePosCandidates(LEFT_HALF, 13, List.of(), 27));
        assertEquals(List.of(LEFT_HALF),
            CrossContainerTransfer.getBasePosCandidates(LEFT_HALF, 13, List.of(LEFT_HALF), 27));
    }

    @Test
    @DisplayName("大箱子：首选 = 发起槽位所属的半箱，另一半作备选（两个面都试）")
    void doubleChest_primaryIsBlockOfHostSlot() {
        List<BlockPos> halves = List.of(LEFT_HALF, RIGHT_HALF);

        List<BlockPos> front = CrossContainerTransfer.getBasePosCandidates(LEFT_HALF, 13, halves, 54);
        assertEquals(LEFT_HALF, front.get(0), "槽 13 属于前半箱 → 首选应为 LEFT 半箱");
        assertEquals(2, front.size());
        assertTrue(front.contains(RIGHT_HALF), "另一半必须作为备选，否则上下两个面只覆盖一个");

        List<BlockPos> back = CrossContainerTransfer.getBasePosCandidates(LEFT_HALF, 49, halves, 54);
        assertEquals(RIGHT_HALF, back.get(0), "槽 49 属于后半箱 → 首选应为 RIGHT 半箱");
        assertEquals(2, back.size());
        assertTrue(back.contains(LEFT_HALF));
    }

    @Test
    @DisplayName("三方块容器：按 containerSize/块数 均分（72/3=24），首选落在对应块")
    void tripleContainer_primaryScalesWithBlockCount() {
        BlockPos b = LEFT_HALF.east();
        BlockPos c = b.east();
        List<BlockPos> blocks = List.of(LEFT_HALF, b, c);

        assertEquals(LEFT_HALF, CrossContainerTransfer.getBasePosCandidates(LEFT_HALF, 0, blocks, 72).get(0));
        assertEquals(b, CrossContainerTransfer.getBasePosCandidates(LEFT_HALF, 25, blocks, 72).get(0));
        assertEquals(c, CrossContainerTransfer.getBasePosCandidates(LEFT_HALF, 50, blocks, 72).get(0));
        assertEquals(LEFT_HALF, CrossContainerTransfer.getBasePosCandidates(LEFT_HALF, -1, blocks, 72).get(0),
            "非法槽位应钳到第一块");
    }

    // ── 端到端：推送方向实际落到哪个面 ────────────────────────────────

    @Test
    @DisplayName("推送：漏斗在后半箱 → 首选后半箱正面（旧实现会固定用前半箱正面）")
    void push_prefersFaceOfHostBlock() {
        IItemHandler frontOfLeft = new ItemStackHandler(1);
        IItemHandler frontOfRight = new ItemStackHandler(1);

        assertTrue(pushFromBackHalf(frontOfLeft, frontOfRight), "两个面都有容器时应推送成功");

        assertEquals(3, frontOfRight.getStackInSlot(0).getCount(),
            "漏斗在槽 49（后半箱）→ 物品应进入 RIGHT 半箱正面的容器");
        assertTrue(frontOfLeft.getStackInSlot(0).isEmpty(),
            "首选面成功时不应再写入备选面");
    }

    @Test
    @DisplayName("推送：首选面无容器 → 退到另一个半箱的正面（两个面都试的兜底）")
    void push_fallsBackToOtherFaceWhenPrimaryEmpty() {
        IItemHandler frontOfLeft = new ItemStackHandler(1);

        // 只给「前半箱正面」装容器：RIGHT 半箱正面（首选）无容器
        assertTrue(pushFromBackHalf(frontOfLeft, null), "首选面无容器时应退到备选面并成功");

        assertEquals(3, frontOfLeft.getStackInSlot(0).getCount(),
            "首选面无容器 → 应退到 LEFT 半箱正面的容器");
    }

    /**
     * 驱动一次真实的跨容器推送：活漏斗在槽 49（后半箱），方向「上传下」
     * ⇒ 源槽 40（容器内，放 3 个苹果）、目标槽 58（越界 → 跨容器推送）。
     */
    private boolean pushFromBackHalf(IItemHandler frontOfLeft, IItemHandler frontOfRight) {
        ctx = new FakeContainerContext(54, 9).withBlockPos(LEFT_HALF);
        ctx.set(40, new ItemStack(Items.APPLE, 3));

        Level level = mockChestLevel();
        // 邻居方块的 IItemHandler：approachSide = 邻居朝向容器的那一侧 = NORTH.getOpposite()
        Direction approachSide = Direction.NORTH.getOpposite();
        if (frontOfLeft != null) {
            Mockito.<IItemHandler>when(level.getCapability(
                eq(Capabilities.ItemHandler.BLOCK), eq(FRONT_OF_LEFT), eq(approachSide)))
                .thenReturn(frontOfLeft);
        }
        if (frontOfRight != null) {
            Mockito.<IItemHandler>when(level.getCapability(
                eq(Capabilities.ItemHandler.BLOCK), eq(FRONT_OF_RIGHT), eq(approachSide)))
                .thenReturn(frontOfRight);
        }

        ResolvedSlots resolved = ResolvedSlots.ofTransfer(40, -1, Pos2D.UP, Pos2D.DOWN);
        return CrossContainerTransfer.execute(ctx, resolved, level, FilterData.EMPTY,
            3, 64, 49, new TickContext(ctx));
    }

    /** 造一个「原点是大箱子 LEFT 半箱、面朝北」的 mock Level */
    private static Level mockChestLevel() {
        Level level = Mockito.mock(Level.class);
        BlockState state = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.TYPE, ChestType.LEFT)
            .setValue(ChestBlock.FACING, Direction.NORTH);
        Mockito.when(level.getBlockState(LEFT_HALF)).thenReturn(state);
        Mockito.when(level.getServer()).thenReturn(Mockito.mock(MinecraftServer.class));
        return level;
    }
}
