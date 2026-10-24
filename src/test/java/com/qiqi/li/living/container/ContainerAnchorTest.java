package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 容器世界锚点的守卫（2026-10-23）。
 *
 * <h3>守什么</h3>
 * <p>「容器在世界里作用在哪」原本有 4 份各写一遍的解析（TNT 爆炸 / 水车应力 / 工具射线 /
 * 信标光束），5 种容器形态在每份里的处理都不一样 ⇒ 同一功能在不同形态下的行为没人有完整视图。
 * 收成 {@link ContainerAnchor} 后，<b>分类只有一处</b>。</p>
 *
 * <p>本测试最关键的一条是 <b>实体容器 / 掉落物必须覆写 {@code getAnchor()}</b>：
 * 它们的 {@code getBlockPos()} 刻意是 null、也没有主人 ⇒ 走默认实现会退化成「没有锚点」，
 * 让所有需要世界锚点的功能在船上 / 掉落物上静默失效（2026-10-23 普查发现的缺口）。
 * 将来新增实体形态忘了覆写，这里立刻红。</p>
 */
class ContainerAnchorTest {

    private static final ItemStackHandler HANDLER = new ItemStackHandler(9);

    @Test
    @DisplayName("方块容器 ⇒ AtBlock（带位置）")
    void blockContext_returnsAtBlock() {
        BlockPos pos = new BlockPos(3, 64, -7);
        SimpleContainerContext ctx = new SimpleContainerContext(
            HANDLER, null, List.of(pos), List.of(), mock(net.minecraft.world.level.Level.class), null);

        ContainerAnchor.AtBlock anchor = assertInstanceOf(
            ContainerAnchor.AtBlock.class, ctx.getAnchor());
        assertEquals(pos, anchor.pos());
    }

    @Test
    @DisplayName("玩家容器 ⇒ AtPlayer（靠 getOwnerPlayer，不是 instanceof Inventory）")
    void playerContext_returnsAtPlayer() {
        Player player = mock(Player.class);
        // ⚠️ Inventory.player 是 final 字段：既不能赋值也不能被 Mockito 打桩
        // ⇒ 用真构造器（它会写 player 字段）
        Inventory inv = new Inventory(player);

        SimpleContainerContext ctx = new SimpleContainerContext(
            HANDLER, inv, List.of(), List.of(), mock(net.minecraft.world.level.Level.class), null);

        ContainerAnchor.AtPlayer anchor = assertInstanceOf(
            ContainerAnchor.AtPlayer.class, ctx.getAnchor());
        assertEquals(player, anchor.player());
    }

    @Test
    @DisplayName("🔴 实体容器（船/矿车）⇒ AtEntity —— 必须覆写，否则退化成没锚点")
    void entityContainer_returnsAtEntity() {
        Entity entity = mock(Entity.class);
        when(entity.blockPosition()).thenReturn(new BlockPos(0, 64, 0));
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        // 构造期会要 ItemHandler.ENTITY 能力（拿不到直接抛，I-C7 不得静默）
        when(entity.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.ENTITY))
            .thenReturn(new ItemStackHandler(27));

        EntityContainerContext ctx = new EntityContainerContext(entity, mock(ServerLevel.class));

        // 前提：它的 getBlockPos() 刻意是 null（防「船停在箱子上方时炸错方块」）
        assertNull(ctx.getBlockPos(), "实体容器的 getBlockPos 必须是 null（既有语义）");

        ContainerAnchor.AtEntity anchor = assertInstanceOf(
            ContainerAnchor.AtEntity.class, ctx.getAnchor(),
            "实体容器必须覆写 getAnchor —— 走默认实现会返回 null，船上所有需要锚点的功能静默失效");
        assertEquals(entity, anchor.entity());
    }

    @Test
    @DisplayName("🔴 掉落物 ⇒ AtEntity —— 掉落物**有实体**，不是「没有锚点」")
    void itemEntity_returnsAtEntity() {
        ItemEntity entity = mock(ItemEntity.class);
        when(entity.getUUID()).thenReturn(UUID.randomUUID());

        ItemEntityContainerContext ctx = new ItemEntityContainerContext(entity, mock(ServerLevel.class));

        ContainerAnchor.AtEntity anchor = assertInstanceOf(
            ContainerAnchor.AtEntity.class, ctx.getAnchor(),
            "「物品形态没有锚点」缺的是宿主，不是实体 —— 掉落物自带实体");
        assertEquals(entity, anchor.entity());
    }

    @Test
    @DisplayName("物品形态（活背包）⇒ null —— 它自己不在世界里，要靠宿主告诉它")
    void itemForm_returnsNull() {
        ItemFormContainerContext ctx = new ItemFormContainerContext(
            HANDLER, UUID.randomUUID(), mock(net.minecraft.world.level.Level.class), 9, null);

        assertNull(ctx.getAnchor(),
            "物品形态默认没有锚点是对的（继承宿主是后续独立步骤）");
    }

    @Test
    @DisplayName("物品形态：getAnchor 恒 null，但 getHostAnchor 有值 —— 两者刻意分开")
    void itemForm_hostAnchorSeparateFromOwn() {
        Player player = mock(Player.class);
        ContainerAnchor host = new ContainerAnchor.AtPlayer(player);

        ItemFormContainerContext ctx = new ItemFormContainerContext(
            HANDLER, UUID.randomUUID(), mock(net.minecraft.world.level.Level.class), 9, host);

        assertNull(ctx.getAnchor(),
            "getAnchor 答「容器自己在哪」—— 物品形态恒 null，这是刻意的");
        assertEquals(host, ctx.getHostAnchor(),
            "getHostAnchor 答「它借住在哪」—— 功能要用必须显式拿，否则会悄悄加强玩法");
    }

    @Test
    @DisplayName("非物品形态：getHostAnchor 恒 null（宿主继承只对物品形态有意义）")
    void nonItemForm_hostAnchorAlwaysNull() {
        Player player = mock(Player.class);
        SimpleContainerContext ctx = new SimpleContainerContext(
            HANDLER, new Inventory(player), List.of(), List.of(),
            mock(net.minecraft.world.level.Level.class), null);

        assertNull(ctx.getHostAnchor());
        assertInstanceOf(ContainerAnchor.AtPlayer.class, ctx.getAnchor());
    }
}
