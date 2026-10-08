package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.domain.water.ContainerFluidHandler;
import com.qiqi.li.living.components.LivingComponents;

/**
 * 玩家路径的流体落盘守卫（2026-10-07）——
 * 覆盖 {@code CONTAINER_FLUID_DATA_PLAYER}（<b>Map&lt;容器键, 数据&gt;</b>）的
 * 「有源写回 / 变空移除 / 重进不复活」三段。
 *
 * <p><b>为什么必须有</b>：玩家容器（背包 / 末影箱）没有 BE 可挂，只能落 Player attachment；
 * 而「源被汲走 / 挤没 ⇒ 数据变空」后若不把条目从 map 里去掉，<b>重进存档会从附件回填把源复活</b>
 * —— 同层已出过一次真实事故（2026-10-04，BE 侧）。玩家侧此前<b>零用例</b>：
 * 2026-10-07 用户实测确认 happy path（源都在）正常，但<b>反方向</b>（不该在的别复活）
 * 一直没有守卫。</p>
 *
 * <p>放在 {@code container} 包：{@code EnderChestContainerContext} 的构造器是包级私有。</p>
 */
class PlayerFluidDataPersistenceTest {

    /** 测试替身：模拟 Player attachment（可读可写的 map）。 */
    private Map<String, ContainerFluidData> attachment;
    private Player player;
    private Level level;   // 共享：writeback 的「玩家脚下应力」路径会读 player.level()

    @BeforeEach
    void setUp() {
        // 静态注册表必须显式重置（项目红线）；给水/岩浆注册生产口径，避免落到默认档
        com.qiqi.li.living.domain.water.FluidFlowBehaviors.clear();
        com.qiqi.li.living.domain.water.FluidFlowBehaviors.register(
            net.minecraft.world.level.material.Fluids.WATER.getFluidType(),
            com.qiqi.li.living.domain.water.FluidFlowBehavior.flowing(
                com.qiqi.li.living.domain.water.ContainerFluidData.MAX_FLOW_LEVEL, 0));
        com.qiqi.li.living.domain.water.FluidFlowBehaviors.register(
            net.minecraft.world.level.material.Fluids.LAVA.getFluidType(),
            new com.qiqi.li.living.domain.water.FluidFlowBehavior() {
                @Override public boolean canFlow() { return true; }
                @Override public int maxLevel() { return 3; }
                @Override public int flowSpeed() { return 0; }
            });

        attachment = new HashMap<>();
        level = level0();
        player = mock(Player.class);
        when(player.getStringUUID()).thenReturn("uuid-p");
        // 写回阶段会走「玩家脚下应力」路径（updatePlayerFeetStressOutput 读 player.level() 与脚下方块位置）
        when(player.level()).thenReturn(level);
        when(player.blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
        when(player.getData(LivingComponents.CONTAINER_FLUID_DATA_PLAYER)).thenReturn(attachment);
        doAnswer(inv -> {
            Map<String, ContainerFluidData> v = inv.getArgument(1);
            attachment.clear();
            if (v != null) attachment.putAll(v);
            return null;
        }).when(player).setData(any(AttachmentType.class), any());
    }

    private static Level level0() {
        Level lv = mock(Level.class);
        when(lv.isClientSide()).thenReturn(false);
        // 默认行为档的 flowSpeed = -1 ⇒ 引擎会问 level.dimensionType().ultraWarm()（派生前不得为 null）
        when(lv.dimensionType()).thenReturn(mock(net.minecraft.world.level.dimension.DimensionType.class));
        return lv;
    }

    private Level level() {
        return level;
    }

    /** 玩家背包上下文（带 level；2 参构造器会把 level 传成 null）。 */
    private static SimpleContainerContext playerInvCtx(Inventory inv, Level level) {
        return new SimpleContainerContext(new ItemStackHandler(9), inv,
            new java.util.ArrayList<>(), new java.util.ArrayList<>(), level);
    }

    /** 模拟「重进存档」：静态缓存已清（等价于停服清理 / LRU 回收），只能从附件回填。 */
    private static void simulateReload() {
        ContainerLivingItemHandler.clearAllCaches();
    }

    @Test
    @DisplayName("玩家背包：有源 ⇒ 写回附件（键 player_<uuid>）；源清空 ⇒ 条目移除，重进不复活")
    void playerInventory_persistsAndClears() {
        var inv = new Inventory(player);
        var ctx = playerInvCtx(inv, level());
        assertEquals("player_uuid-p", ctx.getContainerKey(), "背包容器键");

        // ① 有源 ⇒ 落盘
        ContainerFluidHandler.getOrCreateFluidData(ctx)
            .registerGeneratedSource(0, Fluids.WATER.getFluidType());
        ContainerLivingItemHandler.processContext(ctx, level());
        assertTrue(attachment.containsKey("player_uuid-p"), "有源 ⇒ 附件里应有该容器键");
        assertFalse(attachment.get("player_uuid-p").isEmpty(), "落盘内容非空");

        // ② 源被汲走 ⇒ 数据变空 ⇒ 条目必须被移除
        ContainerFluidHandler.getOrCreateFluidData(ctx).removeGeneratedSource(0);
        ContainerLivingItemHandler.processContext(ctx, level());
        assertFalse(attachment.containsKey("player_uuid-p"),
            "数据变空 ⇒ 附件条目必须被移除（否则重进存档从附件回填复活源）");

        // ③ 重进存档：静态缓存已清 ⇒ 只能从附件回填 ⇒ 必须拿不到源
        simulateReload();
        var reloaded = playerInvCtx(inv, level());
        assertTrue(ContainerFluidHandler.getOrCreateFluidData(reloaded).isEmpty(),
            "重进后不得复活（跨存档残留守卫）");
    }

    @Test
    @DisplayName("末影箱：同样落 Player attachment（键 player_<uuid>_ender_chest），清空即移除")
    void enderChest_persistsAndClears() {
        var ctx = new EnderChestContainerContext(
            new InvWrapper(new PlayerEnderChestContainer()), player, level());
        assertEquals("player_uuid-p_ender_chest", ctx.getContainerKey(), "末影箱容器键");

        ContainerFluidHandler.getOrCreateFluidData(ctx)
            .registerGeneratedSource(0, Fluids.LAVA.getFluidType());
        ContainerLivingItemHandler.processContext(ctx, level());
        assertTrue(attachment.containsKey("player_uuid-p_ender_chest"),
            "有源 ⇒ 末影箱的容器键也落进同一份 map（一个玩家两个容器键）");

        ContainerFluidHandler.getOrCreateFluidData(ctx).removeGeneratedSource(0);
        ContainerLivingItemHandler.processContext(ctx, level());
        assertFalse(attachment.containsKey("player_uuid-p_ender_chest"), "清空 ⇒ 该键被移除");

        simulateReload();
        var reloadedCtx = new EnderChestContainerContext(
            new InvWrapper(new PlayerEnderChestContainer()), player, level());
        assertTrue(ContainerFluidHandler.getOrCreateFluidData(reloadedCtx).isEmpty(), "重进后末影箱不复活");
    }

    @Test
    @DisplayName("一个玩家两个容器键互不干扰：清掉末影箱不碰背包条目")
    void twoContainerKeys_areIndependent() {
        var inv = new Inventory(player);
        var invCtx = playerInvCtx(inv, level());
        var enderCtx = new EnderChestContainerContext(
            new InvWrapper(new PlayerEnderChestContainer()), player, level());

        ContainerFluidHandler.getOrCreateFluidData(invCtx)
            .registerGeneratedSource(0, Fluids.WATER.getFluidType());
        ContainerFluidHandler.getOrCreateFluidData(enderCtx)
            .registerGeneratedSource(1, Fluids.LAVA.getFluidType());
        ContainerLivingItemHandler.processContext(invCtx, level());
        ContainerLivingItemHandler.processContext(enderCtx, level());
        assertEquals(2, attachment.size(), "两个容器键都在");

        // 只清末影箱 ⇒ 背包条目必须还在
        ContainerFluidHandler.getOrCreateFluidData(enderCtx).removeGeneratedSource(1);
        ContainerLivingItemHandler.processContext(enderCtx, level());
        assertFalse(attachment.containsKey("player_uuid-p_ender_chest"), "末影箱条目被移除");
        assertTrue(attachment.containsKey("player_uuid-p"), "背包条目不受影响（键隔离）");
    }

    @Test
    @DisplayName("重进回填：附件里**非空**的条目会被回填（正向路径，确保持久化真的有效）")
    void reload_backfillsNonEmptyEntry() {
        var inv = new Inventory(player);
        var ctx = playerInvCtx(inv, level());
        ContainerFluidHandler.getOrCreateFluidData(ctx)
            .registerGeneratedSource(0, Fluids.WATER.getFluidType());
        ContainerLivingItemHandler.processContext(ctx, level());

        simulateReload();   // 静态缓存清空 ⇒ 下一句只能从附件回填

        var reloaded = playerInvCtx(inv, level());
        var data = ContainerFluidHandler.getOrCreateFluidData(reloaded);
        assertTrue(data.isGeneratedSource(0), "重进后源从附件回填（正向持久化生效）");
    }
}
