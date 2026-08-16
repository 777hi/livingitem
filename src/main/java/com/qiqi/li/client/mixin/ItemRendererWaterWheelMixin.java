package com.qiqi.li.client.mixin;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.compat.create.CreateCompat;
import com.qiqi.li.living.compat.create.ModCreate;
import com.qiqi.li.living.domain.water.LivingWaterWheelData;
import com.qiqi.li.living.domain.water.WaterWheelData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemRenderer.class)
public class ItemRendererWaterWheelMixin {

    private ItemStack livingItem$renderingItem = null;
    private boolean livingItem$needRestoreLighting = false;

    private static final Vector3f FRONT_LIGHT_0 = new Vector3f(0.0f, 0.0f, 1.0f);
    private static final Vector3f FRONT_LIGHT_1 = new Vector3f(-1.0f, 0.0f, 0.0f);

    @Inject(method = "render", at = @At("HEAD"))
    private void captureItem(ItemStack itemStack, ItemDisplayContext displayContext, boolean leftHand,
                             PoseStack poseStack, MultiBufferSource bufferSource, int combinedLight,
                             int combinedOverlay, BakedModel p_model, CallbackInfo ci) {
        livingItem$renderingItem = itemStack;
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void clearItem(CallbackInfo ci) {
        if (livingItem$needRestoreLighting) {
            Lighting.setupForFlatItems();
            livingItem$needRestoreLighting = false;
        }
        livingItem$renderingItem = null;
    }

    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/neoforged/neoforge/client/ClientHooks;handleCameraTransforms(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/item/ItemDisplayContext;Z)Lnet/minecraft/client/resources/model/BakedModel;"
        )
    )
    private BakedModel redirectHandleCameraTransforms(PoseStack poseStack, BakedModel model,
                                                      ItemDisplayContext context, boolean applyLeftHandTransform) {
        ItemStack itemStack = livingItem$renderingItem;
        if (CreateCompat.isLoaded() && isWaterWheel(itemStack) && isGuiContext(context)) {
            if (!LivingItemManager.isLivingItem(itemStack)) {
                return net.neoforged.neoforge.client.ClientHooks.handleCameraTransforms(
                    poseStack, model, context, applyLeftHandTransform);
            }

            RenderSystem.setShaderLights(FRONT_LIGHT_0, FRONT_LIGHT_1);
            livingItem$needRestoreLighting = true;

            LivingWaterWheelData wheelData = LivingItemManager.getWaterWheelData(itemStack);
            WaterWheelData wd = wheelData.wheel();
            int netStress = wd.netStress();

            if (netStress == 0) {
                poseStack.mulPose(new Quaternionf().rotateX((float) Math.PI / 2f));
                poseStack.scale(0.7f, 0.7f, 0.7f);
                return model;
            }

            float rpm = Math.signum(netStress) * ModCreate.BASE_RPM;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return model;
            }

            float time = mc.level.getGameTime()
                + mc.getTimer().getGameTimeDeltaPartialTick(true);
            float angle = (time * rpm * 3f / 10f) % 360f;
            float radians = -angle / 180f * (float) Math.PI;

            poseStack.mulPose(new Quaternionf().rotateZ(radians));
            poseStack.mulPose(new Quaternionf().rotateX((float) Math.PI / 2f));
            poseStack.scale(0.625f, 0.625f, 0.625f);
            return model;
        }
        return net.neoforged.neoforge.client.ClientHooks.handleCameraTransforms(
            poseStack, model, context, applyLeftHandTransform);
    }

    private static boolean isGuiContext(ItemDisplayContext context) {
        return context == ItemDisplayContext.GUI
            || context == ItemDisplayContext.GROUND
            || context == ItemDisplayContext.FIXED;
    }

    private static boolean isWaterWheel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id.getNamespace().equals("create") && id.getPath().equals("water_wheel");
    }
}