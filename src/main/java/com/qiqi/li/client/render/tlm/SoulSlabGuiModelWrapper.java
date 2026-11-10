package com.qiqi.li.client.render.tlm;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.EmptyModel;

/** 保留TLM原override链，仅对本帧真实容器槽栈返回GUI空模型。 */
public final class SoulSlabGuiModelWrapper extends BakedModelWrapper<BakedModel> {
    private final ItemOverrides overrides;

    public SoulSlabGuiModelWrapper(BakedModel original) {
        super(original);
        this.overrides = new ItemOverrides() {
            @Override
            public BakedModel resolve(BakedModel model, ItemStack stack, @Nullable ClientLevel level,
                                      @Nullable LivingEntity entity, int seed) {
                BakedModel resolved = originalModel.getOverrides()
                        .resolve(originalModel, stack, level, entity, seed);
                if (resolved == null) return null;
                var token = SoulSlabGuiFrame.tokenFor(stack);
                return token == null ? resolved : new ContextModel(resolved, token, stack);
            }
        };
    }

    private static final class ContextModel extends BakedModelWrapper<BakedModel> {
        private final SoulSlabGuiFrame.Token token;
        private final ItemStack stack;

        private ContextModel(BakedModel delegate, SoulSlabGuiFrame.Token token, ItemStack stack) {
            super(delegate);
            this.token = token;
            this.stack = stack;
        }

        @Override
        public BakedModel applyTransform(ItemDisplayContext context, PoseStack poseStack, boolean leftHand) {
            if (context == ItemDisplayContext.GUI && SoulSlabGuiFrame.claim(stack, token, poseStack)) {
                return EmptyModel.BAKED;
            }
            return originalModel.applyTransform(context, poseStack, leftHand);
        }

        @Override
        public ItemOverrides getOverrides() {
            return ItemOverrides.EMPTY;
        }
    }

    @Override
    public ItemOverrides getOverrides() {
        return overrides;
    }
}
