package com.qiqi.li.living.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.mojang.brigadier.context.CommandContext;
import com.qiqi.li.living.container.ItemFormContainers;
import com.qiqi.li.living.container.LearnedLayouts;
import com.qiqi.li.living.transfer.ContainerCompatibilityConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LivingItemContainerCommandTest {
    private static final ResourceLocation ID = ResourceLocation.withDefaultNamespace("crafter");
    private static final BlockPos POS = new BlockPos(0, 64, 0);
    private ServerLevel level;
    private CommandContext<CommandSourceStack> command;
    private final List<String> messages = new ArrayList<>();
    private List<ContainerCompatibilityConfig.ContainerRule> previousRules;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        previousRules = ContainerCompatibilityConfig.getAllRules().stream()
            .filter(e -> e.getKey().equals(ID)).map(java.util.Map.Entry::getValue).toList();
        ContainerCompatibilityConfig.remove(ID);
        LearnedLayouts.clearForTest();
        level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getLookAngle()).thenReturn(new Vec3(0, 0, 1));
        when(player.getEyePosition()).thenReturn(Vec3.ZERO);
        when(level.clip(any(ClipContext.class))).thenReturn(
            new BlockHitResult(Vec3.atCenterOf(POS), Direction.NORTH, POS, false));
        when(level.getBlockState(any(BlockPos.class))).thenReturn(Blocks.CRAFTER.defaultBlockState());
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getPlayerOrException()).thenReturn(player);
        when(source.getLevel()).thenReturn(level);
        doAnswer(invocation -> {
            Supplier<Component> message = invocation.getArgument(0);
            messages.add(message.get().getString());
            return null;
        }).when(source).sendSuccess(any(), anyBoolean());
        command = mock(CommandContext.class);
        when(command.getSource()).thenReturn(source);
    }

    @AfterEach
    void tearDown() {
        ContainerCompatibilityConfig.remove(ID);
        for (var rule : previousRules) ContainerCompatibilityConfig.register(ID, rule);
        LearnedLayouts.clearForTest();
        ItemFormContainers.clearForTest();
    }

    private void target(BlockEntity be, int size) {
        when(level.getBlockEntity(POS)).thenReturn(be);
        when(level.getCapability(Capabilities.ItemHandler.BLOCK, POS, null))
            .thenReturn(new ItemStackHandler(size));
    }

    private String output() { return String.join("\n", messages); }

    @Test
    void inspect_usesLearnedWidth() throws Exception {
        target(new CrafterBlockEntity(POS, Blocks.CRAFTER.defaultBlockState()), 9);
        LearnedLayouts.learn(ID, 9, 3);
        assertEquals(1, LivingItemContainerCommand.inspectContainer(command));
        assertTrue(output().contains("生效列数: §f3 (3行)"), output());
        assertFalse(output().contains("推断列数"), output());
    }

    @Test
    void inspect_keepsRegisteredRuleSeparateFromAuthoritativeWidth() throws Exception {
        var be = new CrafterBlockEntity(POS, Blocks.CRAFTER.defaultBlockState());
        target(be, 9);
        ContainerCompatibilityConfig.register(ID,
            ContainerCompatibilityConfig.ContainerRule.builder().containerSize(9).columns(9).build());
        ItemFormContainers.register(new ItemFormContainers.Source() {
            public boolean isContainerItem(ItemStack stack) { return false; }
            public IItemHandler contentHandler(ItemStack stack) { return null; }
            public int columnsOfBlockEntity(BlockEntity candidate) { return candidate == be ? 3 : 0; }
        });
        LivingItemContainerCommand.inspectContainer(command);
        assertTrue(output().contains("生效列数: §f3 (3行)"), output());
        assertTrue(output().contains("规则列数: §f9 (1行)"), output());
    }

    @Test
    void inspect_roundsUpPartialLastRow() throws Exception {
        target(new CrafterBlockEntity(POS, Blocks.CRAFTER.defaultBlockState()), 10);
        LearnedLayouts.learn(ID, 10, 3);
        LivingItemContainerCommand.inspectContainer(command);
        assertTrue(output().contains("生效列数: §f3 (4行)"), output());
    }

    @Test
    void inspect_doesNotGuessForUnopenedLoot() throws Exception {
        var be = new ChestBlockEntity(POS, Blocks.CHEST.defaultBlockState());
        be.setLootTable(net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.LOOT_TABLE,
            ResourceLocation.withDefaultNamespace("chests/simple_dungeon")));
        target(be, 27);
        when(level.getBlockState(POS)).thenReturn(Blocks.CHEST.defaultBlockState());
        LivingItemContainerCommand.inspectContainer(command);
        assertTrue(output().contains("当前不处理"), output());
        assertFalse(output().contains("生效列数"), output());
        assertFalse(output().contains("推断列数"), output());
    }

    @Test
    void inspect_usesBothDoubleChestHalves() throws Exception {
        var right = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.RIGHT);
        var leftPos = POS.relative(ChestBlock.getConnectedDirection(right));
        var first = new ChestBlockEntity(POS, right);
        var second = new ChestBlockEntity(leftPos,
            Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT));
        target(first, 54);
        when(level.getBlockState(POS)).thenReturn(right);
        when(level.getBlockEntity(leftPos)).thenReturn(second);
        ItemFormContainers.register(new ItemFormContainers.Source() {
            public boolean isContainerItem(ItemStack stack) { return false; }
            public IItemHandler contentHandler(ItemStack stack) { return null; }
            public int columnsOfBlockEntity(BlockEntity candidate) { return candidate == second ? 6 : 0; }
        });
        LivingItemContainerCommand.inspectContainer(command);
        assertTrue(output().contains("生效列数: §f6 (9行)"), output());
        verify(level, atLeastOnce()).getBlockEntity(leftPos);
    }
}
