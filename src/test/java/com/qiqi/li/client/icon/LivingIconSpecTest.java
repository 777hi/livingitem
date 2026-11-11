package com.qiqi.li.client.icon;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.piston.LivingPistonData;
import com.qiqi.li.living.domain.redstone.LivingComparatorData;
import com.qiqi.li.living.domain.redstone.LivingObserverData;
import com.qiqi.li.living.domain.redstone.LivingRedstoneTorchData;
import com.qiqi.li.living.domain.redstone.LivingRepeaterData;
import com.qiqi.li.living.model.Pos2D;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class LivingIconSpecTest {
    @AfterEach
    void clearModelStore() {
        LivingIconRegistry.VariantModelStore.clear();
        TorchRenderState.clear();
    }

    private static ItemStack living(net.minecraft.world.item.Item item) {
        ItemStack stack = new ItemStack(item);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    @Test
    void arbitraryItemUsesDeclaredDirectionNotTorchData() {
        ItemStack stack = living(Items.STONE);
        LivingRedstoneTorchData.set(stack, LivingRedstoneTorchData.DEFAULT.withDirection(Pos2D.LEFT));
        var spec = LivingIconSpec.builder(Items.STONE)
            .addLocalVariant("base", "item/test", s -> true)
            .directional(s -> Pos2D.RIGHT).build();
        var overrides = new GenericLivingItemOverrides(mock(BakedModel.class), spec);
        assertEquals(-90, overrides.resolveDirectionRotation(stack));
    }

    @Test
    void everyBuiltInDirectionGetterReadsItsOwnComponent() {
        for (LivingIconSpec spec : LivingIconRegistry.DirectionalSpecs.create()) {
            for (Pos2D direction : new Pos2D[]{Pos2D.UP, Pos2D.RIGHT, Pos2D.DOWN, Pos2D.LEFT}) {
                ItemStack stack = living(spec.getItem());
                if (stack.is(Items.PISTON) || stack.is(Items.STICKY_PISTON)) {
                    LivingPistonData.set(stack, new LivingPistonData(direction));
                } else if (stack.is(Items.REPEATER)) {
                    LivingRepeaterData.set(stack, LivingRepeaterData.DEFAULT.withDirection(direction));
                } else if (stack.is(Items.COMPARATOR)) {
                    LivingComparatorData.set(stack, LivingComparatorData.DEFAULT.withDirection(direction));
                } else if (stack.is(Items.OBSERVER)) {
                    LivingObserverData.set(stack, new LivingObserverData(direction));
                } else {
                    assertTrue(stack.is(Items.REDSTONE_TORCH));
                    LivingRedstoneTorchData.set(stack, LivingRedstoneTorchData.DEFAULT.withDirection(direction));
                }
                var overrides = new GenericLivingItemOverrides(mock(BakedModel.class), spec);
                assertEquals(-DirectionalLivingModel.rotationOf(direction),
                    overrides.resolveDirectionRotation(stack), spec.getItem() + " " + direction);
            }
        }
    }

    @Test
    void plainItemDoesNotInvokeDirectionGetterAndReturnsVanilla() {
        AtomicInteger calls = new AtomicInteger();
        var spec = LivingIconSpec.builder(Items.STONE)
            .addLocalVariant("base", "item/test", stack -> true)
            .directional(stack -> { calls.incrementAndGet(); return Pos2D.RIGHT; }).build();
        BakedModel vanilla = mock(BakedModel.class);
        var overrides = new GenericLivingItemOverrides(vanilla, spec);
        assertSame(vanilla, overrides.resolve(vanilla, new ItemStack(Items.STONE), null, null, 0));
        assertEquals(0, calls.get());
    }

    @Test
    void nullGetterAndConflictingRotationModesAreRejected() {
        assertThrows(NullPointerException.class,
            () -> LivingIconSpec.builder(Items.STONE).directional(null));
        assertThrows(IllegalStateException.class,
            () -> LivingIconSpec.builder(Items.STONE).addLocalVariant("base", "item/test", s -> true)
                .rotating().directional(s -> Pos2D.UP).build());
        assertThrows(IllegalStateException.class,
            () -> LivingIconSpec.builder(Items.STONE).addLocalVariant("base", "item/test", s -> true)
                .directional(s -> Pos2D.UP).rotating().build());
    }

    @Test
    void modelIdsRemainNamespacedAndDoNotCollideInEitherCache() {
        ResourceLocation localId = ResourceLocation.fromNamespaceAndPath("living_item", "item/shared");
        ResourceLocation foreignId = ResourceLocation.fromNamespaceAndPath("foreign", "item/shared");
        var spec = LivingIconSpec.builder(Items.STONE)
            .addLocalVariant("same", "item/shared", s -> s.getCount() == 1)
            .addVariant("same", foreignId, s -> true).build();
        var local = spec.getVariants().get(0);
        var foreign = spec.getVariants().get(1);
        assertEquals(localId, local.getModelId());
        assertEquals(foreignId, foreign.getModelId());
        assertThrows(IllegalArgumentException.class,
            () -> LivingIconSpec.builder(Items.STONE).addLocalVariant("bad", "minecraft:item/stone", s -> true));
        BakedModel localModel = mock(BakedModel.class), foreignModel = mock(BakedModel.class);
        LivingIconRegistry.VariantModelStore.put(local, localModel);
        LivingIconRegistry.VariantModelStore.put(foreign, foreignModel);
        assertSame(localModel, LivingIconRegistry.VariantModelStore.get(local));
        assertSame(foreignModel, LivingIconRegistry.VariantModelStore.get(foreign));
        BakedModel vanilla = mock(BakedModel.class);
        var overrides = new GenericLivingItemOverrides(vanilla, spec);
        ItemStack stack = living(Items.STONE);
        BakedModel first = overrides.resolve(vanilla, stack, null, null, 0);
        stack.setCount(2);
        BakedModel second = overrides.resolve(vanilla, stack, null, null, 0);
        assertNotSame(first, second);
        first.getTransforms();
        verify(localModel).getTransforms();
        second.getTransforms();
        verify(foreignModel).getTransforms();
    }

    @Test
    void directionalWrapperOnlyTransformsGuiAndNonGuiReturnsVanillaResult() {
        BakedModel livingModel = mock(BakedModel.class), vanilla = mock(BakedModel.class);
        BakedModel vanillaResult = mock(BakedModel.class);
        when(vanilla.applyTransform(any(), any(), anyBoolean())).thenReturn(vanillaResult);
        var context = new GenericContextAwareModel(livingModel, vanilla);
        var directional = new DirectionalLivingModel(context, 1.5f);
        TorchRenderState.setRotation(-90);
        PoseStack gui = new PoseStack();
        assertSame(directional, directional.applyTransform(ItemDisplayContext.GUI, gui, false));
        verify(livingModel).applyTransform(eq(ItemDisplayContext.GUI), same(gui), eq(false));
        assertNotEquals(new org.joml.Matrix4f(), gui.last().pose());
        for (ItemDisplayContext display : ItemDisplayContext.values()) {
            if (display == ItemDisplayContext.GUI) continue;
            PoseStack pose = new PoseStack();
            assertSame(vanillaResult, directional.applyTransform(display, pose, false));
            assertEquals(new org.joml.Matrix4f(), pose.last().pose(), display.toString());
        }
    }
}
