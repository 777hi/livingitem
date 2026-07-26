package com.qiqi.li.living.mixin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.LivingFunctionData;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.components.WaterSpreadComponent;
import com.qiqi.li.living.function.LivingWaterBucketFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick,
                          CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        List<WaterBucketRender> buckets = collectWaterBuckets(self);
        if (buckets.isEmpty()) return;

        TextureAtlasSprite waterStill = Minecraft.getInstance()
            .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
            .apply(ResourceLocation.withDefaultNamespace("block/water_still"));

        TextureAtlasSprite waterFlow = Minecraft.getInstance()
            .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
            .apply(ResourceLocation.withDefaultNamespace("block/water_flow"));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);

        for (WaterBucketRender bucket : buckets) {
            for (Map.Entry<Integer, WaterCell> entry : bucket.cells().entrySet()) {
                int menuSlotIndex = entry.getKey();
                WaterCell cell = entry.getValue();

                Slot slot = self.getMenu().getSlot(menuSlotIndex);
                if (slot == null) continue;

                int x = leftPos + slot.x;
                int y = topPos + slot.y;

                float alpha = cell.level() == 0 ? 0.55f : 0.25f + (1.0f - (float) cell.level() / 7.0f) * 0.3f;
                RenderSystem.setShaderColor(0.25f, 0.5f, 1.0f, alpha);

                if (cell.level() == 0) {
                    guiGraphics.blit(x, y, 0, 16, 16, waterStill);
                } else {
                    float angle = (float) Math.toDegrees(Math.atan2(cell.dy(), cell.dx()));

                    PoseStack pose = guiGraphics.pose();
                    pose.pushPose();
                    pose.translate(x + 8, y + 8, 0);
                    pose.mulPose(new Quaternionf().rotateZ((float) Math.toRadians(angle)));
                    pose.translate(-8, -8, 0);
                    guiGraphics.blit(0, 0, 0, 16, 16, waterFlow);
                    pose.popPose();
                }
            }
        }

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
    }

    private List<WaterBucketRender> collectWaterBuckets(AbstractContainerScreen<?> screen) {
        List<WaterBucketRender> buckets = new ArrayList<>();

        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (!LivingWaterBucketFunction.isLivingWaterBucket(stack)) continue;

            LivingFunctionData funcData = stack.get(LivingItemManager.LIVING_FUNCTION_DATA.value());
            if (funcData == null) continue;

            CompoundTag funcTag = funcData.getFunctionData(LivingWaterBucketFunction.ID);
            if (funcTag.isEmpty()) continue;

            ComponentState state = new ComponentState(funcTag.getCompound(WaterSpreadComponent.ID));
            Map<Integer, Integer> handlerFlow = WaterSpreadComponent.loadFlowData(state);
            if (handlerFlow.isEmpty()) continue;

            int hostSlot = state.getInt(WaterSpreadComponent.KEY_HOST_SLOT, -1);
            int handlerWidth = state.getInt(WaterSpreadComponent.KEY_WIDTH, 9);

            Container bucketContainer = slot.container;
            Map<Integer, Slot> handlerToMenu = new HashMap<>();
            for (Slot s : screen.getMenu().slots) {
                if (s.container == bucketContainer) {
                    handlerToMenu.put(s.getContainerSlot(), s);
                }
            }

            int srcHandlerCol = hostSlot % handlerWidth;
            int srcHandlerRow = hostSlot / handlerWidth;

            Map<Integer, WaterCell> cells = new HashMap<>();
            for (Map.Entry<Integer, Integer> entry : handlerFlow.entrySet()) {
                int handlerSlot = entry.getKey();
                int level = entry.getValue();

                Slot menuSlot = handlerToMenu.get(handlerSlot);
                if (menuSlot == null) continue;

                int handlerCol = handlerSlot % handlerWidth;
                int handlerRow = handlerSlot / handlerWidth;
                int dx = handlerCol - srcHandlerCol;
                int dy = handlerRow - srcHandlerRow;

                cells.put(menuSlot.index, new WaterCell(level, dx, dy));
            }

            if (!cells.isEmpty()) {
                buckets.add(new WaterBucketRender(cells, handlerWidth));
            }
        }

        return buckets;
    }

    private record WaterCell(int level, int dx, int dy) {}
    private record WaterBucketRender(Map<Integer, WaterCell> cells, int handlerWidth) {}
}