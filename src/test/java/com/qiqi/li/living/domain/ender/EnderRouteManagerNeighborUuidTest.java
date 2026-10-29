package com.qiqi.li.living.domain.ender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.container.ContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 邻居路由的源身份（R-07）。
 *
 * <p>条目 {@code sourcePos} 指向邻居，{@code sourceContainerUuid} 就必须是<b>邻居</b>的
 * 跨形态身份；旧实现写的是注册者（漏斗容器）的 UUID ⇒ 双形态守卫会盯着错误的容器，
 * 邻居方块↔物品切换时旧路由可能漏清。</p>
 */
class EnderRouteManagerNeighborUuidTest {

    private static final BlockPos NEIGHBOR_POS = new BlockPos(5, 64, 5);
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

    @Test
    @DisplayName("邻居源 UUID：取邻居 BE 的身份，不是注册者的")
    void neighborRoute_usesNeighborUuid() {
        UUID neighborUuid = UUID.randomUUID();
        UUID registrarUuid = UUID.randomUUID();
        BlockEntity be = mock(BlockEntity.class);
        when(be.getData(LivingComponents.CONTAINER_UUID_ATTACHMENT.value())).thenReturn(neighborUuid);
        Level level = mock(Level.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getBlockEntity(NEIGHBOR_POS)).thenReturn(be);
        ContainerContext registrar = mock(ContainerContext.class);
        when(registrar.getContainerKey()).thenReturn("hopper_container");
        when(registrar.getContainerUuid()).thenReturn(registrarUuid);
        ItemStackHandler neighbor = new ItemStackHandler(1);
        neighbor.setStackInSlot(0, new ItemStack(Items.DIAMOND));

        assertTrue(EnderRouteManager.registerRouteFromNeighbor(CHANNEL, neighbor, NEIGHBOR_POS,
            level, registrar, 3, null, 1));

        EnderChannelEntry entry = registry.peek(CHANNEL, null, null);
        assertNotNull(entry);
        assertEquals(neighborUuid, entry.sourceContainerUuid(), "必须记邻居容器的身份");
        assertNotEquals(registrarUuid, entry.sourceContainerUuid(), "不得再写注册者 UUID");
    }

    @Test
    @DisplayName("邻居没有容器身份：sourceContainerUuid 为 null")
    void neighborRoute_withoutIdentity_leavesUuidNull() {
        BlockEntity be = mock(BlockEntity.class);
        when(be.getData(LivingComponents.CONTAINER_UUID_ATTACHMENT.value())).thenReturn(new UUID(0, 0));
        Level level = mock(Level.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getBlockEntity(NEIGHBOR_POS)).thenReturn(be);
        ContainerContext registrar = mock(ContainerContext.class);
        when(registrar.getContainerKey()).thenReturn("hopper_container");
        ItemStackHandler neighbor = new ItemStackHandler(1);
        neighbor.setStackInSlot(0, new ItemStack(Items.DIAMOND));

        assertTrue(EnderRouteManager.registerRouteFromNeighbor(CHANNEL, neighbor, NEIGHBOR_POS,
            level, registrar, 3, null, 1));

        EnderChannelEntry entry = registry.peek(CHANNEL, null, null);
        assertNotNull(entry);
        assertNull(entry.sourceContainerUuid(), "没有身份 ⇒ null，不能拿注册者 UUID 顶替");
    }
}
