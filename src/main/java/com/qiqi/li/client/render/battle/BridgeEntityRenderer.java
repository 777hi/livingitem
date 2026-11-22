package com.qiqi.li.client.render.battle;

import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.living.domain.battle.entity.BridgeEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * 桥实体渲染器：无模型，直接画物品模型皮（§2.5 桥内建 renderer 路线）。
 *
 * <p>count 层堆叠（掉层 = 皮减层）；上限 8 层防渲染预算爆炸（hitbox 高度仍按
 * count 全量，只裁视觉层数）。</p>
 */
public class BridgeEntityRenderer extends EntityRenderer<BridgeEntity> {

    private static final int MAX_LAYERS = 8;

    public BridgeEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(BridgeEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        ItemStack skin = entity.skin();
        if (!skin.isEmpty()) {
            int layers = Math.min(entity.countLayer(), MAX_LAYERS);
            for (int i = 0; i < layers; i++) {
                poseStack.pushPose();
                poseStack.translate(0, i, 0);
                Minecraft.getInstance().getItemRenderer().renderStatic(skin, ItemDisplayContext.GROUND,
                    packedLight, OverlayTexture.NO_OVERLAY, poseStack, buffer, entity.level(), 0);
                poseStack.popPose();
            }
        }
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(BridgeEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
