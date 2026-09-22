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
    public boolean usesBlockLight() {
        // ⚠️ 恒 false —— 与 GenericContextAwareModel / DirectionalLivingModel 保持一致的
        //    「GUI 图标统一平铺光照（全亮）」约定（见 icon-system.md「GUI 图标光照约定」）。
        //    原先委托 baseModel：水车是 3D 模型（usesBlockLight=true）⇒ GUI 里走
        //    Lighting.setupFor3DItems() 的法线漫反射 ⇒ 比周围图标暗（2026-09-22 修正）。
        //    新加任何 BakedModel 包装类都必须照此返回 false，否则该图标在 GUI 里发暗。
        return false;
    }

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