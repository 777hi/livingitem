package com.qiqi.li.client.icon;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 方向感知模型，在 GUI 中对内部模型施加 Z 轴旋转。
 *
 * <p>与漏斗装饰器相同的旋转模式，只是作用于模型层而非叠加层。
 */
public class DirectionalLivingModel implements BakedModel {

    private final BakedModel inner;

    public DirectionalLivingModel(BakedModel inner) {
        this.inner = inner;
    }

    @Override
    public BakedModel applyTransform(ItemDisplayContext context, PoseStack poseStack, boolean applyLeftHandTransform) {
        inner.applyTransform(context, poseStack, applyLeftHandTransform);
        if (context == ItemDisplayContext.GUI) {
            int rotation = TorchRenderState.getRotation();
            if (rotation != 0) {
                poseStack.mulPose(Axis.ZP.rotationDegrees(rotation));
            }
        }
        return this;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random) {
        return inner.getQuads(state, direction, random);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random, ModelData data, @Nullable net.minecraft.client.renderer.RenderType renderType) {
        return inner.getQuads(state, direction, random, data, renderType);
    }

    @Override public boolean useAmbientOcclusion() { return inner.useAmbientOcclusion(); }
    @Override public boolean isGui3d() { return inner.isGui3d(); }
    @Override public boolean usesBlockLight() { return inner.usesBlockLight(); }
    @Override public boolean isCustomRenderer() { return inner.isCustomRenderer(); }
    @Override public TextureAtlasSprite getParticleIcon() { return inner.getParticleIcon(); }
    @Override public TextureAtlasSprite getParticleIcon(ModelData data) { return inner.getParticleIcon(data); }
    @Override public ItemTransforms getTransforms() { return inner.getTransforms(); }
    @Override public ItemOverrides getOverrides() { return ItemOverrides.EMPTY; }
}