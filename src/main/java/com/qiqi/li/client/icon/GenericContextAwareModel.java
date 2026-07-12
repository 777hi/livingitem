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

import javax.annotation.Nullable;
import java.util.List;

/**
 * 通用的上下文感知模型，根据显示场景切换不同的渲染模型。
 *
 * <p>替代之前每种活物品一个 ContextAwareXxxModel 的模式。
 * 所有活物品共用同一个类，只是构造参数不同。
 *
 * <p>核心逻辑在 applyTransform() 中：
 * <ul>
 *   <li>GUI（物品栏/快捷栏）→ 委托给 livingModel，显示自定义图标</li>
 *   <li>其他场景（手持、地面等）→ 委托给 vanillaModel，显示原版图标</li>
 * </ul>
 */
public class GenericContextAwareModel implements BakedModel {

    private final BakedModel livingModel;
    private final BakedModel vanillaModel;

    public GenericContextAwareModel(BakedModel livingModel, BakedModel vanillaModel) {
        this.livingModel = livingModel;
        this.vanillaModel = vanillaModel;
    }

    @Override
    public BakedModel applyTransform(ItemDisplayContext context, PoseStack poseStack, boolean applyLeftHandTransform) {
        if (context == ItemDisplayContext.GUI) {
            return livingModel.applyTransform(context, poseStack, applyLeftHandTransform);
        }
        return vanillaModel.applyTransform(context, poseStack, applyLeftHandTransform);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random) {
        return livingModel.getQuads(state, direction, random);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random, ModelData data, @Nullable net.minecraft.client.renderer.RenderType renderType) {
        return livingModel.getQuads(state, direction, random, data, renderType);
    }

    @Override
    public boolean useAmbientOcclusion() { return livingModel.useAmbientOcclusion(); }

    @Override
    public boolean isGui3d() { return livingModel.isGui3d(); }

    @Override
    public boolean usesBlockLight() { return livingModel.usesBlockLight(); }

    @Override
    public boolean isCustomRenderer() { return livingModel.isCustomRenderer(); }

    @Override
    public TextureAtlasSprite getParticleIcon() { return livingModel.getParticleIcon(); }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) { return livingModel.getParticleIcon(data); }

    @Override
    public ItemTransforms getTransforms() { return livingModel.getTransforms(); }

    @Override
    public ItemOverrides getOverrides() { return ItemOverrides.EMPTY; }
}