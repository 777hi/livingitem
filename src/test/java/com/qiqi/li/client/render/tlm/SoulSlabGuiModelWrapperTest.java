package com.qiqi.li.client.render.tlm;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.EmptyModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 不启动渲染器的GUI模型上下文守卫。 */
class SoulSlabGuiModelWrapperTest {
    @AfterEach
    void clearFrame() {
        SoulSlabGuiFrame.clear();
    }

    @Test
    void identityRegistrationDoesNotMatchCopy() {
        ItemStack stack = new ItemStack(Items.PAPER);
        var token = SoulSlabGuiFrame.begin();
        SoulSlabGuiFrame.register(stack, token, itemPose(0, 0));
        assertSame(token, SoulSlabGuiFrame.tokenFor(stack));
        assertNull(SoulSlabGuiFrame.tokenFor(stack.copy()));
    }

    @Test
    void beginningAnotherFrameInvalidatesAllOldRegistrationsAndClaims() {
        ItemStack stack = new ItemStack(Items.PAPER);
        PoseStack pose = itemPose(0, 0);
        var first = SoulSlabGuiFrame.begin();
        SoulSlabGuiFrame.register(stack, first, pose);
        assertTrue(SoulSlabGuiFrame.claim(stack, first, pose));

        var second = SoulSlabGuiFrame.begin();
        assertFalse(SoulSlabGuiFrame.isCurrent(first));
        assertNull(SoulSlabGuiFrame.tokenFor(stack));
        assertFalse(SoulSlabGuiFrame.takeClaim(stack, first, pose));
        assertTrue(SoulSlabGuiFrame.isCurrent(second));
    }

    @Test
    void emptyNullAndMissingPosesAreNeverRegistered() {
        var token = SoulSlabGuiFrame.begin();
        SoulSlabGuiFrame.register(null, token, itemPose(0, 0));
        SoulSlabGuiFrame.register(ItemStack.EMPTY, token, itemPose(0, 0));
        SoulSlabGuiFrame.register(new ItemStack(Items.PAPER), token, null);
        assertNull(SoulSlabGuiFrame.tokenFor(null));
        assertNull(SoulSlabGuiFrame.tokenFor(ItemStack.EMPTY));
    }

    @Test
    void originalOverrideResolvesBeforeMatchingGuiContext() {
        ProbeModel resolved = new ProbeModel();
        ProbeModel original = resolvingTo(resolved);
        var wrapper = new SoulSlabGuiModelWrapper(original);
        ItemStack stack = new ItemStack(Items.PAPER);
        PoseStack pose = itemPose(7, 11);
        var token = SoulSlabGuiFrame.begin();
        SoulSlabGuiFrame.register(stack, token, pose);

        BakedModel contextual = wrapper.getOverrides().resolve(wrapper, stack, null, null, 0);
        assertNotNull(contextual);
        assertSame(EmptyModel.BAKED,
                contextual.applyTransform(ItemDisplayContext.GUI, copy(pose), false));
        assertTrue(SoulSlabGuiFrame.takeClaim(stack, token, pose));
        assertFalse(SoulSlabGuiFrame.takeClaim(stack, token, pose));
    }

    @Test
    void sameReferenceAtAnotherGuiPositionKeepsOriginalModel() {
        ProbeModel resolved = new ProbeModel();
        var wrapper = new SoulSlabGuiModelWrapper(resolvingTo(resolved));
        ItemStack stack = new ItemStack(Items.PAPER);
        PoseStack slotPose = itemPose(7, 11);
        var token = SoulSlabGuiFrame.begin();
        SoulSlabGuiFrame.register(stack, token, slotPose);

        BakedModel contextual = wrapper.getOverrides().resolve(wrapper, stack, null, null, 0);
        assertSame(resolved,
                contextual.applyTransform(ItemDisplayContext.GUI, itemPose(25, 11), false));
        assertFalse(SoulSlabGuiFrame.takeClaim(stack, token, slotPose));
        assertSame(token, SoulSlabGuiFrame.tokenFor(stack));
    }

    @Test
    void wrongGuiRenderDoesNotConsumeLaterSlotClaim() {
        ProbeModel resolved = new ProbeModel();
        var wrapper = new SoulSlabGuiModelWrapper(resolvingTo(resolved));
        ItemStack stack = new ItemStack(Items.PAPER);
        PoseStack slotPose = itemPose(7, 11);
        var token = SoulSlabGuiFrame.begin();
        SoulSlabGuiFrame.register(stack, token, slotPose);
        BakedModel wrong = wrapper.getOverrides().resolve(wrapper, stack, null, null, 0);
        assertSame(resolved, wrong.applyTransform(ItemDisplayContext.GUI, itemPose(7, 29), false));

        BakedModel slot = wrapper.getOverrides().resolve(wrapper, stack, null, null, 0);
        assertSame(EmptyModel.BAKED, slot.applyTransform(ItemDisplayContext.GUI, copy(slotPose), false));
        assertTrue(SoulSlabGuiFrame.takeClaim(stack, token, slotPose));
    }

    @Test
    void originalNullOverrideResultRemainsNull() {
        ProbeModel original = new ProbeModel(new ItemOverrides() {
            @Override
            public BakedModel resolve(BakedModel model, ItemStack stack,
                                      @Nullable net.minecraft.client.multiplayer.ClientLevel level,
                                      @Nullable LivingEntity entity, int seed) {
                return null;
            }
        });
        var wrapper = new SoulSlabGuiModelWrapper(original);
        ItemStack stack = new ItemStack(Items.PAPER);
        SoulSlabGuiFrame.register(stack, SoulSlabGuiFrame.begin(), itemPose(0, 0));
        assertNull(wrapper.getOverrides().resolve(wrapper, stack, null, null, 0));
    }

    @Test
    void wrapperPreservesNeoForgeItemRenderExtensions() {
        ProbeModel original = new ProbeModel();
        var wrapper = new SoulSlabGuiModelWrapper(original);
        ItemStack stack = new ItemStack(Items.PAPER);

        assertSame(original.renderPass, wrapper.getRenderPasses(stack, false).getFirst());
        assertEquals(List.of(original.renderType), wrapper.getRenderTypes(stack, false));
    }

    @Test
    void nonGuiTransformDelegatesResolvedModelWithoutClaiming() {
        ProbeModel resolved = new ProbeModel();
        var wrapper = new SoulSlabGuiModelWrapper(resolvingTo(resolved));
        ItemStack stack = new ItemStack(Items.PAPER);
        PoseStack pose = itemPose(0, 0);
        var token = SoulSlabGuiFrame.begin();
        SoulSlabGuiFrame.register(stack, token, pose);

        BakedModel contextual = wrapper.getOverrides().resolve(wrapper, stack, null, null, 0);
        assertSame(resolved,
                contextual.applyTransform(ItemDisplayContext.GROUND, new PoseStack(), false));
        assertEquals(ItemDisplayContext.GROUND, resolved.lastContext);
        assertFalse(SoulSlabGuiFrame.takeClaim(stack, token, pose));
    }

    @Test
    void copiedAndUnregisteredStacksKeepOriginalResolvedModel() {
        ProbeModel resolved = new ProbeModel();
        var wrapper = new SoulSlabGuiModelWrapper(resolvingTo(resolved));
        ItemStack stack = new ItemStack(Items.PAPER);
        SoulSlabGuiFrame.register(stack, SoulSlabGuiFrame.begin(), itemPose(0, 0));

        assertSame(resolved, wrapper.getOverrides().resolve(wrapper, stack.copy(), null, null, 0));
        SoulSlabGuiFrame.clear();
        assertSame(resolved, wrapper.getOverrides().resolve(wrapper, stack, null, null, 0));
    }

    @Test
    void staleContextCannotHideOrClaim() {
        ProbeModel resolved = new ProbeModel();
        var wrapper = new SoulSlabGuiModelWrapper(resolvingTo(resolved));
        ItemStack stack = new ItemStack(Items.PAPER);
        PoseStack pose = itemPose(0, 0);
        var token = SoulSlabGuiFrame.begin();
        SoulSlabGuiFrame.register(stack, token, pose);
        BakedModel contextual = wrapper.getOverrides().resolve(wrapper, stack, null, null, 0);
        SoulSlabGuiFrame.clear();

        assertSame(resolved,
                contextual.applyTransform(ItemDisplayContext.GUI, copy(pose), false));
        assertEquals(ItemDisplayContext.GUI, resolved.lastContext);
        assertFalse(SoulSlabGuiFrame.takeClaim(stack, token, pose));
    }

    @Test
    void consumingOneStackDoesNotInvalidateOtherClaims() {
        ItemStack first = new ItemStack(Items.PAPER);
        ItemStack second = new ItemStack(Items.MAP);
        PoseStack firstPose = itemPose(0, 0);
        PoseStack secondPose = itemPose(18, 0);
        var token = SoulSlabGuiFrame.begin();
        SoulSlabGuiFrame.register(first, token, firstPose);
        SoulSlabGuiFrame.register(second, token, secondPose);
        assertTrue(SoulSlabGuiFrame.claim(first, token, firstPose));
        assertTrue(SoulSlabGuiFrame.claim(second, token, secondPose));

        assertTrue(SoulSlabGuiFrame.takeClaim(first, token, firstPose));
        assertNull(SoulSlabGuiFrame.tokenFor(first.copy()));
        assertTrue(SoulSlabGuiFrame.takeClaim(second, token, secondPose));
        assertTrue(SoulSlabGuiFrame.isCurrent(token));
    }

    @Test
    void sameReferenceRegistrationsStayBoundToTheirOwnPositions() {
        ItemStack stack = new ItemStack(Items.PAPER);
        PoseStack firstPose = itemPose(0, 0);
        PoseStack secondPose = itemPose(18, 0);
        var token = SoulSlabGuiFrame.begin();
        SoulSlabGuiFrame.register(stack, token, firstPose);
        SoulSlabGuiFrame.register(stack, token, secondPose);

        assertTrue(SoulSlabGuiFrame.claim(stack, token, secondPose));
        assertFalse(SoulSlabGuiFrame.takeClaim(stack, token, firstPose));
        assertTrue(SoulSlabGuiFrame.takeClaim(stack, token, secondPose));
        assertSame(token, SoulSlabGuiFrame.tokenFor(stack));
    }

    private static ProbeModel resolvingTo(BakedModel resolved) {
        return new ProbeModel(new ItemOverrides() {
            @Override
            public BakedModel resolve(BakedModel model, ItemStack stack,
                                      @Nullable net.minecraft.client.multiplayer.ClientLevel level,
                                      @Nullable LivingEntity entity, int seed) {
                return resolved;
            }
        });
    }

    private static PoseStack itemPose(int x, int y) {
        return SoulSlabGuiPose.itemModelPose(
                SoulSlabGuiPose.slotOrigin(new PoseStack(), 20, 30), x, y);
    }

    private static PoseStack copy(PoseStack source) {
        return SoulSlabGuiPose.copy(source);
    }

    private static final class BasicModel implements BakedModel {
        @Override public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random) { return List.of(); }
        @Override public boolean useAmbientOcclusion() { return false; }
        @Override public boolean isGui3d() { return false; }
        @Override public boolean usesBlockLight() { return false; }
        @Override public boolean isCustomRenderer() { return false; }
        @Override public TextureAtlasSprite getParticleIcon() { return null; }
        @Override public ItemTransforms getTransforms() { return ItemTransforms.NO_TRANSFORMS; }
        @Override public ItemOverrides getOverrides() { return ItemOverrides.EMPTY; }
    }

    private static final class ProbeModel implements BakedModel {
        private final ItemOverrides overrides;
        private final BakedModel renderPass;
        private final RenderType renderType = RenderType.translucent();
        private ItemDisplayContext lastContext;

        private ProbeModel() {
            this(ItemOverrides.EMPTY);
        }

        private ProbeModel(ItemOverrides overrides) {
            this.overrides = overrides;
            this.renderPass = new BasicModel();
        }

        @Override
        public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
            return List.of(renderPass);
        }

        @Override
        public List<RenderType> getRenderTypes(ItemStack stack, boolean fabulous) {
            return List.of(renderType);
        }

        @Override
        public BakedModel applyTransform(ItemDisplayContext context, PoseStack poseStack, boolean leftHand) {
            lastContext = context;
            return this;
        }

        @Override public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random) { return List.of(); }
        @Override public boolean useAmbientOcclusion() { return false; }
        @Override public boolean isGui3d() { return false; }
        @Override public boolean usesBlockLight() { return false; }
        @Override public boolean isCustomRenderer() { return false; }
        @Override public TextureAtlasSprite getParticleIcon() { return null; }
        @Override public ItemTransforms getTransforms() { return ItemTransforms.NO_TRANSFORMS; }
        @Override public ItemOverrides getOverrides() { return overrides; }
    }
}
