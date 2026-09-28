package com.qiqi.li.client.icon;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 通用的模型包装器，用于注入自定义 ItemOverrides。
 *
 * <p>替代之前每种活物品一个 LivingXxxModelWrapper 的模式。
 * 所有活物品共用同一个类，只是构造参数不同。
 *
 * <p>Wrapper 的大部分方法都委托给原版模型，确保原版物品的渲染完全不变。
 * 唯一的区别是 getOverrides() 返回自定义的 GenericLivingItemOverrides，
 * 它会根据物品的 NBT 数据决定返回原版模型还是活物品模型。
 */
public class GenericLivingModelWrapper implements BakedModel {

    private final BakedModel vanillaModel;
    private final ItemOverrides overrides;

    public GenericLivingModelWrapper(BakedModel vanillaModel, LivingIconSpec spec) {
        this.vanillaModel = vanillaModel;
        this.overrides = new GenericLivingItemOverrides(vanillaModel, spec);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random) {
        return vanillaModel.getQuads(state, direction, random);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random, ModelData data, @Nullable net.minecraft.client.renderer.RenderType renderType) {
        return vanillaModel.getQuads(state, direction, random, data, renderType);
    }

    @Override
    public boolean useAmbientOcclusion() { return vanillaModel.useAmbientOcclusion(); }

    @Override
    public boolean isGui3d() { return vanillaModel.isGui3d(); }

    @Override
    // ⚠️ 此处**有意**委托 vanillaModel（与其它包装类的「恒 false」不同）：
    //    本类是注入 bake 表的那一层，`getOverrides()` 返回自定义 overrides ⇒
    //    ItemRenderer 拿到的是 resolve 后的模型（GenericContextAwareModel / 原版），
    //    本类自身**不作为最终渲染模型** ⇒ 它的 usesBlockLight 不参与 GUI 光照判定。
    //    委托 vanilla 是为了「非活物品」场景保持原版行为。
    public boolean usesBlockLight() { return vanillaModel.usesBlockLight(); }

    @Override
    public boolean isCustomRenderer() { return vanillaModel.isCustomRenderer(); }

    @Override
    public TextureAtlasSprite getParticleIcon() { return vanillaModel.getParticleIcon(); }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) { return vanillaModel.getParticleIcon(data); }

    @Override
    public ItemTransforms getTransforms() { return vanillaModel.getTransforms(); }

    @Override
    public BakedModel applyTransform(net.minecraft.world.item.ItemDisplayContext context, PoseStack poseStack, boolean applyLeftHandTransform) {
        return vanillaModel.applyTransform(context, poseStack, applyLeftHandTransform);
    }

    @Override
    public ItemOverrides getOverrides() { return overrides; }
}