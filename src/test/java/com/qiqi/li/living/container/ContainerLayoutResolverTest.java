package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;

import com.qiqi.li.living.transfer.ContainerCompatibilityConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContainerLayoutResolverTest {
    private static final ResourceLocation FURNACE = ResourceLocation.withDefaultNamespace("furnace");
    private static final ResourceLocation CRAFTER = ResourceLocation.withDefaultNamespace("crafter");
    private List<java.util.Map.Entry<ResourceLocation, ContainerCompatibilityConfig.ContainerRule>> savedRules;

    private static class Facts extends SimpleContainerContext {
        int liveColumns;
        Entity entity;
        List<BlockEntity> blocks = List.of();
        Facts(int size) { super(new ItemStackHandler(size)); }
        @Override public int getLiveColumns() { return liveColumns; }
        @Override public Entity getEntity() { return entity; }
        @Override public List<BlockEntity> getAssociatedBlockEntities() { return blocks; }
    }

    @BeforeEach void before() {
        savedRules = ContainerCompatibilityConfig.getAllRules().stream()
            .filter(e -> e.getKey().equals(FURNACE) || e.getKey().equals(CRAFTER)).toList();
        ContainerCompatibilityConfig.remove(FURNACE);
        ContainerCompatibilityConfig.remove(CRAFTER);
        LearnedLayouts.clearForTest();
    }
    @AfterEach void after() {
        ContainerCompatibilityConfig.remove(FURNACE);
        ContainerCompatibilityConfig.remove(CRAFTER);
        for (var entry : savedRules) ContainerCompatibilityConfig.register(entry.getKey(), entry.getValue());
        LearnedLayouts.clearForTest();
        ItemFormContainers.clearForTest();
    }

    private void rule(ResourceLocation id, int size, int columns) {
        ContainerCompatibilityConfig.register(id,
            ContainerCompatibilityConfig.ContainerRule.builder().containerSize(size).columns(columns).build());
    }

    private FurnaceBlockEntity furnace() {
        var be = new FurnaceBlockEntity(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
        var level = mock(Level.class);
        be.setLevel(level);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(be);
        return be;
    }

    @Test void specializedContexts_cannotReplaceSelectionOrder() throws Exception {
        assertTrue(Modifier.isFinal(SimpleContainerContext.class.getDeclaredMethod("getLayout").getModifiers()));
        assertTrue(Modifier.isFinal(SimpleContainerContext.class.getDeclaredMethod("getWidth").getModifiers()));
        for (var type : List.of(EntityContainerContext.class, EnderChestContainerContext.class, ItemFormContainerContext.class)) {
            assertEquals(SimpleContainerContext.class, type.getMethod("getLayout").getDeclaringClass());
            assertEquals(SimpleContainerContext.class, type.getMethod("getWidth").getDeclaringClass());
        }
    }

    @Test void liveFact_precedesBuiltInVehicleConvention() {
        var facts = new Facts(27);
        facts.entity = mock(Entity.class);
        doReturn(EntityType.CHEST_MINECART).when(facts.entity).getType();
        facts.liveColumns = 3;
        assertEquals(new ContainerLayout(9, ContainerLayout.Source.FIXED), BuiltInContainerLayouts.find(facts));
        assertEquals(new ContainerLayout(3, ContainerLayout.Source.LIVE), facts.getLayout());
        facts.liveColumns = 0;
        assertEquals(new ContainerLayout(9, ContainerLayout.Source.FIXED), facts.getLayout());
    }

    @Test void blockStages_keepLiveRuleLearnedBuiltInHeuristicOrder() {
        var facts = new Facts(3);
        facts.blocks = List.of(furnace());
        assertEquals(new ContainerLayout(1, ContainerLayout.Source.FAMILY), facts.getLayout());
        LearnedLayouts.learn(FURNACE, 3, 3);
        assertEquals(new ContainerLayout(3, ContainerLayout.Source.LEARNED), facts.getLayout());
        rule(FURNACE, 3, 1);
        assertEquals(new ContainerLayout(1, ContainerLayout.Source.RULE), facts.getLayout());
        facts.liveColumns = 3;
        assertEquals(new ContainerLayout(3, ContainerLayout.Source.LIVE), facts.getLayout());
        facts.liveColumns = 0;
        facts.blocks = List.of();
        assertEquals(new ContainerLayout(3, ContainerLayout.Source.HEURISTIC), facts.getLayout());
    }

    @Test void rulesAcrossHosts_precedeAnyLearnedRecord() {
        var facts = new Facts(3);
        facts.blocks = List.of(furnace(), new CrafterBlockEntity(new BlockPos(1, 0, 0), Blocks.CRAFTER.defaultBlockState()));
        LearnedLayouts.learn(FURNACE, 3, 3);
        rule(CRAFTER, 3, 1);
        assertEquals(new ContainerLayout(1, ContainerLayout.Source.RULE), facts.getLayout());
    }

    @Test void unknownFirstContainer_doesNotBorrowLaterFamily() {
        var first = new CrafterBlockEntity(BlockPos.ZERO, Blocks.CRAFTER.defaultBlockState());
        var level = mock(Level.class);
        first.setLevel(level);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(first);
        var facts = new Facts(3);
        facts.blocks = List.of(first, furnace());
        assertEquals(new ContainerLayout(3, ContainerLayout.Source.HEURISTIC), facts.getLayout());
    }

    @Test void familyDefaults_keepSubclassSemantics() {
        class FurnaceFamily extends FurnaceBlockEntity {
            FurnaceFamily() { super(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState()); }
        }
        var be = new FurnaceFamily();
        var level = mock(Level.class);
        be.setLevel(level);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(be);
        var facts = new Facts(3);
        facts.blocks = List.of(be);
        assertEquals(new ContainerLayout(1, ContainerLayout.Source.FAMILY), facts.getLayout());
    }

    @Test void unchangedAnswers_reuseResultButLiveChangesAreObserved() {
        var facts = new Facts(30);
        facts.liveColumns = 5;
        var previous = facts.getLayout();
        assertSame(previous, facts.getLayout());
        facts.liveColumns = 6;
        assertEquals(6, facts.getWidth());
        assertEquals(new ContainerLayout(6, ContainerLayout.Source.LIVE), facts.getLayout());
        assertNotSame(previous, facts.getLayout());
    }

    @Test void droppedItem_usesCommonBuiltInStage() {
        var entity = mock(ItemEntity.class);
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        var ctx = new ItemEntityContainerContext(entity, mock(net.minecraft.server.level.ServerLevel.class));
        assertEquals(new ContainerLayout(1, ContainerLayout.Source.FIXED), ctx.getLayout());
        assertEquals(ctx.getLayout(), ContainerLayoutResolver.resolve(ctx, null));
        assertEquals(1, ctx.getWidth());
    }
}
