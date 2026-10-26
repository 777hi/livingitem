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
 * 方向感知模型，在 GUI 中对内部模型施加 Z 轴旋转和缩放。
 *
 * <p>与漏斗装饰器相同的旋转模式，只是作用于模型层而非叠加层。
 */
public class DirectionalLivingModel implements BakedModel {

    private final BakedModel inner;
    private final float guiScale;

    public DirectionalLivingModel(BakedModel inner) {
        this(inner, 1.0f);
    }

    public DirectionalLivingModel(BakedModel inner, float guiScale) {
        this.inner = inner;
        this.guiScale = guiScale;
    }

    @Override
    public BakedModel applyTransform(ItemDisplayContext context, PoseStack poseStack, boolean applyLeftHandTransform) {
        if (context == ItemDisplayContext.GUI) {
            if (guiScale != 1.0f) {
                poseStack.scale(guiScale, guiScale, 1.0f);
            }
            int rotation = TorchRenderState.getRotation();
            if (rotation != 0) {
                poseStack.mulPose(Axis.ZP.rotationDegrees(rotation));
            }
        }
        BakedModel result = inner.applyTransform(context, poseStack, applyLeftHandTransform);
        // ⚠️ 仅 GUI 返回 this（附加 GUI 缩放/旋转）；非 GUI 必须返回 inner 的结果 ——
        //    NeoForge 的 handleCameraTransforms 用【返回模型】决定后续 quads/isCustomRenderer，
        //    非 GUI 也 return this 会让掉落物/手持/展示框用变体 3D 方块几何
        //    （中继器/比较器变体是 block/xxx 3D 模型，原版是 item/generated 平面 sprite）
        //    ⇒ 外观与原版明显不同（2026-10-27 审查 P2-1，与 GenericContextAwareModel 对齐）。
        return context == ItemDisplayContext.GUI ? this : result;
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
    @Override public boolean usesBlockLight() { return false; }   // GUI 图标统一平铺光照（见 icon-system.md「GUI 图标光照约定」）
    @Override public boolean isCustomRenderer() { return inner.isCustomRenderer(); }
    @Override public TextureAtlasSprite getParticleIcon() { return inner.getParticleIcon(); }
    @Override public TextureAtlasSprite getParticleIcon(ModelData data) { return inner.getParticleIcon(data); }
    @Override public ItemTransforms getTransforms() { return inner.getTransforms(); }
    @Override public ItemOverrides getOverrides() { return ItemOverrides.EMPTY; }
}