package com.qiqi.li.client.icon;

import com.mojang.blaze3d.vertex.PoseStack;
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
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.util.List;

public class RotatingWaterWheelModel implements BakedModel {

    private final BakedModel baseModel;

    public RotatingWaterWheelModel(BakedModel baseModel) {
        this.baseModel = baseModel;
    }

    @Override
    public BakedModel applyTransform(ItemDisplayContext context, PoseStack poseStack, boolean applyLeftHandTransform) {
        if (context == ItemDisplayContext.GUI || context == ItemDisplayContext.GROUND
            || context == ItemDisplayContext.FIXED) {
            float rpm = WaterWheelRenderState.getRpm();
            if (rpm != 0) {
                float time = net.minecraft.client.Minecraft.getInstance().level.getGameTime()
                    + net.minecraft.client.Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
                float direction = rpm > 0 ? -1f : 1f;
                float angle = (time * 8f * 3f / 10) % 360;
                float radians = direction * angle / 180f * (float) Math.PI;
                poseStack.translate(0.5f, 0.5f, 0.5f);
                poseStack.mulPose(new Quaternionf().rotateX(radians));
                poseStack.translate(-0.5f, -0.5f, -0.5f);
            }
        }

        baseModel.applyTransform(context, poseStack, applyLeftHandTransform);
        return this;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random) {
        return baseModel.getQuads(state, direction, random);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random, ModelData data, @Nullable net.minecraft.client.renderer.RenderType renderType) {
        return baseModel.getQuads(state, direction, random, data, renderType);
    }

    @Override
    public boolean useAmbientOcclusion() { return baseModel.useAmbientOcclusion(); }

    @Override
    public boolean isGui3d() { return baseModel.isGui3d(); }

    @Override
    public boolean usesBlockLight() { return baseModel.usesBlockLight(); }

    @Override
    public boolean isCustomRenderer() { return baseModel.isCustomRenderer(); }

    @Override
    public TextureAtlasSprite getParticleIcon() { return baseModel.getParticleIcon(); }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) { return baseModel.getParticleIcon(data); }

    @Override
    public ItemTransforms getTransforms() { return baseModel.getTransforms(); }

    @Override
    public ItemOverrides getOverrides() { return ItemOverrides.EMPTY; }
}