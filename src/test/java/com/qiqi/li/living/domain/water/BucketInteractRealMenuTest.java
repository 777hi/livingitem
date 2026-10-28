package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 活桶倒/汲的**真实原版菜单**路径（2026-10-28 补，审查测试缺口 #6）。
 *
 * <p>此前测试用的是 {@code mock(AbstractContainerMenu.class)} —— 假菜单没有真实槽位，
 * 永远走「索引直查成功」这一条路，测不到「菜单 → 容器反查」与物理槽/逻辑槽映射。
 * 这里用真实 `BrewingStandMenu` + 真实 `BrewingStandBlockEntity` 走完整链路。</p>
 *
 * <p>⚠️ 脚手架要点：菜单槽位的 `container` 是 BE 本身 ⇒ 解析走
 * `container instanceof BlockEntity` 分支，要求 `level.getCapability(ItemHandler.BLOCK)` 非空
 * （测试里给一个 5 槽 handler 替身）。</p>
 */
class BucketInteractRealMenuTest {

    private static final BlockPos POS = new BlockPos(4, 64, 4);
    private static final int SLOT = 0;

    private ServerPlayer player;
    private BrewingStandMenu menu;
    private BrewingStandBlockEntity be;

    private static ItemStack living(ItemStack stack) {
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    @BeforeEach
    void setUp() {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);

        be = new BrewingStandBlockEntity(POS, Blocks.BREWING_STAND.defaultBlockState());
        be.setLevel(level);
        when(level.getBlockEntity(POS)).thenReturn(be);
        doReturn(new ItemStackHandler(5)).when(level)
            .getCapability(Capabilities.ItemHandler.BLOCK, POS, null);

        player = mock(ServerPlayer.class);
        when(player.level()).thenReturn(level);
        Inventory inventory = new Inventory(player);
        when(player.getInventory()).thenReturn(inventory);

        menu = new BrewingStandMenu(0, inventory, be, new net.minecraft.world.inventory.SimpleContainerData(2));
        player.containerMenu = menu;
    }

    private Slot slot() {
        return menu.slots.get(SLOT);
    }

    @Test
    @DisplayName("倒水：真实菜单能解析出容器、桶被排空（派生源由汲水用例端到端证明）")
    void pour_realMenu_createsSourceAndDrainsBucket() {
        menu.setCarried(living(new ItemStack(Items.WATER_BUCKET)));

        LivingBucketInteractHandlers.POUR.handle(player, slot());

        ContainerFluidData data = LivingBucketInteractSupport.resolveFluidData(player, slot());
        assertNotNull(data, "真实原版菜单必须能解析出容器上下文（假菜单测不到这一步）");
        assertEquals(Items.BUCKET, menu.getCarried().getItem(), "倒完应变成空桶（活标记保留）");
        assertEquals(1, menu.getCarried().getCount());
        // 注：派生源写进 generatedSources 后要等容器 tick 的 BFS 播种才进实际层（flows），
        //     「源真的立起来了」由下方 scoop 用例端到端证明（先 pour → tick → scoop 成功）。
    }

    @Test
    @DisplayName("汲水：真实菜单从源格取走一桶，桶变水桶")
    void scoop_realMenu_removesSourceAndFillsBucket() {
        menu.setCarried(living(new ItemStack(Items.WATER_BUCKET)));
        LivingBucketInteractHandlers.POUR.handle(player, slot());   // 先造一个派生源

        // 让 BFS 把派生源播种进实际层（flows）—— 汲水读的是 sourceFluid（实际层）
        ContainerContext ctx = LivingBucketInteractSupport.resolveContext(player, slot());
        ContainerFluidData data = LivingBucketInteractSupport.resolveFluidData(player, slot());
        assertNotNull(data);
        data.tick(ctx);

        menu.setCarried(living(new ItemStack(Items.BUCKET)));
        LivingBucketInteractHandlers.SCOOP.handle(player, slot());

        assertEquals(Items.WATER_BUCKET, menu.getCarried().getItem(), "汲完应变成水桶");
        assertNull(LivingBucketInteractSupport.resolveFluidData(player, slot()).getGeneratedSources().get(SLOT),
            "源应被取走");
    }
}
