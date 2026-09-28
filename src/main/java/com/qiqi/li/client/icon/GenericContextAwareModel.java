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
    private final float guiScale;

    public GenericContextAwareModel(BakedModel livingModel, BakedModel vanillaModel) {
        this(livingModel, vanillaModel, 1.0f);
    }

    public GenericContextAwareModel(BakedModel livingModel, BakedModel vanillaModel, float guiScale) {
        this.livingModel = livingModel;
        this.vanillaModel = vanillaModel;
        this.guiScale = guiScale;
    }

    @Override
    public BakedModel applyTransform(ItemDisplayContext context, PoseStack poseStack, boolean applyLeftHandTransform) {
        if (context == ItemDisplayContext.GUI) {
            livingModel.applyTransform(context, poseStack, applyLeftHandTransform);
            if (guiScale != 1.0f) {
                poseStack.scale(guiScale, guiScale, 1.0f);
            }
            return this;
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
    public boolean usesBlockLight() {
        // ⚠️ 恒 false —— 让**所有活物品图标在 GUI 里走「平铺光照」（全亮、无方向性漫反射）**。
        //
        // 机制（1.21.1 `GuiGraphics.renderItem`，唯一的 usesBlockLight() 使用点）：
        //   boolean flag = !bakedmodel.usesBlockLight();
        //   if (flag) Lighting.setupForFlatItems();    // 平铺光照 → 各面同亮
        //   itemRenderer.render(..., 15728880 /* FULL_BRIGHT */, ...);
        //   if (flag) Lighting.setupFor3DItems();      // 3D 光照 → 按法线做明暗
        //
        // ⚠️ 注意：传进去的 packedLight **本来就是 FULL_BRIGHT**（15728880），
        // 所以「图标发暗」与光照等级无关 —— 真凶是 setupFor3DItems() 的
        // DIFFUSE_LIGHT_0/1 漫反射：正面朝相机的 3D 图标（BEWLR 箱子、方块模型中继器）
        // 法线点乘光向量后亮度只剩 ~0.7 甚至更低 ⇒ 明显比 2D 图标暗。
        //
        // 恒 false 的影响面：`usesBlockLight()` 在 1.21.1 只被 GuiGraphics 用，
        // 掉落物/手持/世界渲染走各自的光照路径，不读此属性 ⇒ 无副作用。
        // ⇒ 新加活物品图标**不必再手改光照**（无论 2D、方块模型还是 builtin/entity）。
        return false;
    }

    @Override
    public boolean isCustomRenderer() {
        // ⚠️ 箱子/末影箱方案（2026-09-22）依赖此委托：它们的变体模型是
        // parent: "builtin/entity"（bake 成 BuiltInModel，isCustomRenderer()=true），
        // ItemRenderer 据此走 BEWLR（ChestRenderer）画 3D 箱子而不是 quads
        // （BuiltInModel.getQuads() 为空，走 quads 会画不出东西）。
        // 对其它普通物品 livingModel 委托返回 false ⇒ 行为不变。
        return livingModel.isCustomRenderer();
    }

    @Override
    public TextureAtlasSprite getParticleIcon() { return livingModel.getParticleIcon(); }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) { return livingModel.getParticleIcon(data); }

    @Override
    public ItemTransforms getTransforms() { return livingModel.getTransforms(); }

    @Override
    public ItemOverrides getOverrides() { return ItemOverrides.EMPTY; }
}