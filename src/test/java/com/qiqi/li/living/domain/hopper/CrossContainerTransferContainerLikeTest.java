package com.qiqi.li.living.domain.hopper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.model.ResolvedSlots;
import com.qiqi.li.living.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.transfer.FilterData;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
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

/**
 * 容器类活物品走通用跨容器路径的回归（2026-11-01 删除 pullFromNeighborToLivingChest 特支后）：
 *
 * <ul>
 *   <li>拉取到容器内活箱子：与通用路径逐行等价（同为 SlotAccessorFactory + tryPullFromNeighbor），
 *       特支删除后必须仍工作 —— 物品进箱子的 {@code CONTAINER} 组件，箱子本体不被移动；</li>
 *   <li>推送隔离门：活非容器物品不作货物（isEligibleCargo 唯一定义点），活箱子放行
 *       （作源取其内部）。</li>
 * </ul>
 */
class CrossContainerTransferContainerLikeTest {

    private static final BlockPos ORIGIN = new BlockPos(3, 64, 3);
    private static final Direction FACE_DIR = Direction.NORTH;
    private static final BlockPos FRONT = ORIGIN.relative(FACE_DIR);
    private static final Direction APPROACH_SIDE = FACE_DIR.getOpposite();
    /** GUI「上」对应的世界方向（facing NORTH ⇒ SOUTH，见 GridDirections）。 */
    private static final BlockPos BACK = ORIGIN.relative(Direction.SOUTH);

    private FakeContainerContext ctx;

    @AfterEach
    void cleanRuntimeCache() {
        if (ctx != null) {
            ContainerRuntimeCache.removeContainer(ctx.getContainerKey());
        }
    }

    private static ItemStack livingChest() {
        ItemStack stack = new ItemStack(Items.CHEST);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static ItemStack livingBoneMeal() {
        ItemStack stack = new ItemStack(Items.BONE_MEAL);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    /** 造一个「原点是单箱、面朝北」的 mock Level（邻居容器挂在正面）。 */
    private static Level mockSingleChestLevel() {
        Level level = Mockito.mock(Level.class);
        BlockState state = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.TYPE, ChestType.SINGLE)
            .setValue(ChestBlock.FACING, FACE_DIR);
        Mockito.when(level.getBlockState(ORIGIN)).thenReturn(state);
        Mockito.when(level.getServer()).thenReturn(Mockito.mock(MinecraftServer.class));
        return level;
    }

    private static void stubNeighbor(Level level, IItemHandler neighbor) {
        Mockito.<IItemHandler>when(level.getCapability(
            eq(Capabilities.ItemHandler.BLOCK), eq(FRONT), eq(APPROACH_SIDE)))
            .thenReturn(neighbor);
    }

    /** 邻居挂在容器南侧（GUI「上」方向，facing NORTH ⇒ 世界 SOUTH）。 */
    private static void stubNeighborBehind(Level level, IItemHandler neighbor) {
        Mockito.<IItemHandler>when(level.getCapability(
            eq(Capabilities.ItemHandler.BLOCK), eq(BACK), eq(Direction.NORTH)))
            .thenReturn(neighbor);
    }

    /** CONTAINER 组件在抽空后可能变成 0 槽的 EMPTY，逐槽判断才稳。 */
    private static boolean containerEmpty(ItemStack chest) {
        var contents = chest.get(DataComponents.CONTAINER);
        if (contents == null) return true;
        for (int i = 0; i < contents.getSlots(); i++) {
            if (!contents.getStackInSlot(i).isEmpty()) return false;
        }
        return true;
    }

    @Test
    @DisplayName("拉取到容器内活箱子：走通用路径，物品进 CONTAINER 组件，箱子本体不动")
    void pull_intoLivingChest_viaGenericPath() {
        ctx = new FakeContainerContext(27, 9).withBlockPos(ORIGIN);
        ItemStack chest = livingChest();
        ctx.set(3, chest);

        Level level = mockSingleChestLevel();
        ItemStackHandler neighbor = new ItemStackHandler(1);
        neighbor.setStackInSlot(0, new ItemStack(Items.APPLE, 5));
        stubNeighborBehind(level, neighbor);

        // 源槽越界（邻居）+ GUI 上方（facing NORTH ⇒ 世界 SOUTH）⇒ 邻居挂在容器南侧
        ResolvedSlots resolved = ResolvedSlots.ofTransfer(-1, 3, Pos2D.UP, Pos2D.DOWN);
        boolean pulled = CrossContainerTransfer.execute(ctx, resolved, level, FilterData.EMPTY,
            5, 64, 0, new TickContext(ctx));

        assertTrue(pulled, "邻居有货、箱子未满 ⇒ 拉取成功");
        assertTrue(neighbor.getStackInSlot(0).isEmpty(), "邻居应被扣货");

        assertFalse(containerEmpty(chest), "苹果应进入活箱子内部");
        ItemStack fromChest = chest.get(DataComponents.CONTAINER).getStackInSlot(0);
        assertTrue(fromChest.is(Items.APPLE), "进入箱子内部的是苹果");
        assertEquals(1, chest.getCount(), "箱子本体不被移动");
    }

    @Test
    @DisplayName("隔离门：活非容器物品（活骨粉）作推送源被拒，邻居不受影响")
    void push_livingNonContainer_rejected() {
        ctx = new FakeContainerContext(27, 9).withBlockPos(ORIGIN);
        ctx.set(2, livingBoneMeal());

        Level level = mockSingleChestLevel();
        ItemStackHandler neighbor = new ItemStackHandler(1);
        stubNeighbor(level, neighbor);

        ResolvedSlots resolved = ResolvedSlots.ofTransfer(2, -1, Pos2D.UP, Pos2D.DOWN);
        assertFalse(CrossContainerTransfer.execute(ctx, resolved, level, FilterData.EMPTY,
            64, 64, 0, new TickContext(ctx)), "活物品非容器类 ⇒ 不是合法货物");
        assertTrue(neighbor.getStackInSlot(0).isEmpty(), "被拒后不得写入邻居");
    }

    @Test
    @DisplayName("隔离门：活箱子作推送源放行，推送的是箱子内部物品")
    void push_livingChest_extractsInternals() {
        ctx = new FakeContainerContext(27, 9).withBlockPos(ORIGIN);
        ItemStack chest = livingChest();
        chest.set(DataComponents.CONTAINER,
            net.minecraft.world.item.component.ItemContainerContents.fromItems(
                List.of(new ItemStack(Items.APPLE, 3))));
        ctx.set(2, chest);

        Level level = mockSingleChestLevel();
        ItemStackHandler neighbor = new ItemStackHandler(1);
        stubNeighbor(level, neighbor);

        ResolvedSlots resolved = ResolvedSlots.ofTransfer(2, -1, Pos2D.UP, Pos2D.DOWN);
        boolean pushed = CrossContainerTransfer.execute(ctx, resolved, level, FilterData.EMPTY,
            64, 64, 0, new TickContext(ctx));

        assertTrue(pushed, "容器类活物品是合法货物 ⇒ 推送其内部物品");
        assertEquals(3, neighbor.getStackInSlot(0).getCount(), "箱子内部的 3 个苹果应被推出");
        assertTrue(containerEmpty(chest), "推完箱子内部应清空");
        assertEquals(1, chest.getCount(), "箱子本体不被移动");
    }
}
