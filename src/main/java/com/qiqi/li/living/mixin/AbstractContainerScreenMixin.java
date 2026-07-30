package com.qiqi.li.living.mixin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.client.mixin.SlotWrapperAccessor;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.data.LivingWaterBucketData;
import com.qiqi.li.living.data.WaterData;
import com.qiqi.li.living.function.LivingWaterBucketFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/WaterRender");

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    private static final int DIR_DOWN = 0;
    private static final int DIR_RIGHT = 1;
    private static final int DIR_UP = 2;
    private static final int DIR_LEFT = 3;

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
                    float angleDeg = directionToRotation(cell.direction());

                    PoseStack pose = guiGraphics.pose();
                    pose.pushPose();
                    pose.translate(x + 8, y + 8, 0);
                    pose.mulPose(new Quaternionf().rotateZ((float) Math.toRadians(angleDeg)));
                    pose.translate(-8, -8, 0);
                    guiGraphics.blit(0, 0, 0, 16, 16, waterFlow);
                    pose.popPose();
                }
            }
        }

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
    }

    private static float directionToRotation(int direction) {
        return switch (direction) {
            case DIR_DOWN -> 0.0f;
            case DIR_RIGHT -> -90.0f;
            case DIR_UP -> 180.0f;
            case DIR_LEFT -> 90.0f;
            default -> 0.0f;
        };
    }

    private List<WaterBucketRender> collectWaterBuckets(AbstractContainerScreen<?> screen) {
        List<WaterBucketRender> buckets = new ArrayList<>();

        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (!LivingWaterBucketFunction.isLivingWaterBucket(stack)) continue;

            LivingWaterBucketData bucketData = LivingItemManager.getWaterBucketData(stack);
            WaterData water = bucketData.water();
            if (water.equals(WaterData.EMPTY)) continue;

            String flowStr = water.flow();
            if (flowStr == null || flowStr.isEmpty()) continue;

            int handlerWidth = water.width();

            Map<Integer, int[]> handlerFlow = parseFlowData(flowStr);
            if (handlerFlow.isEmpty()) continue;

            Container bucketContainer = slot.container;
            Map<Integer, Integer> handlerToMenuIndex = new HashMap<>();
            for (int i = 0; i < screen.getMenu().slots.size(); i++) {
                Slot s = screen.getMenu().slots.get(i);
                if (s.container == bucketContainer) {
                    int containerSlot = resolveContainerSlot(s);
                    handlerToMenuIndex.put(containerSlot, i);
                }
            }

            Map<Integer, WaterCell> cells = new HashMap<>();
            int skippedCount = 0;
            for (var entry : handlerFlow.entrySet()) {
                int handlerSlot = entry.getKey();
                int level = entry.getValue()[0];
                int fromSlot = entry.getValue()[1];

                Integer menuIndex = handlerToMenuIndex.get(handlerSlot);
                if (menuIndex == null) {
                    skippedCount++;
                    continue;
                }

                int direction;
                if (level == 0 || fromSlot < 0) {
                    direction = DIR_DOWN;
                } else {
                    int dx = (handlerSlot % handlerWidth) - (fromSlot % handlerWidth);
                    int dy = (handlerSlot / handlerWidth) - (fromSlot / handlerWidth);
                    direction = cardinalDirection(dx, dy);
                }

                cells.put(menuIndex, new WaterCell(level, direction));
            }

            if (skippedCount > 0) {
                LOGGER.debug("[WaterRender] Skipped {} flow entries", skippedCount);
            }

            if (!cells.isEmpty()) {
                buckets.add(new WaterBucketRender(cells, handlerWidth));
            }
        }

        return buckets;
    }

    private static int resolveContainerSlot(Slot s) {
        if (s instanceof SlotWrapperAccessor accessor) {
            return accessor.getTarget().getContainerSlot();
        }
        if (s.getClass().getSimpleName().contains("SlotWrapper")) {
            Slot target = resolveSlotWrapperTarget(s);
            if (target != null) {
                return target.getContainerSlot();
            }
            LOGGER.warn("[WaterRender] SlotWrapper detected but could NOT resolve target! class={}", s.getClass().getName());
        }
        return s.getContainerSlot();
    }

    private static Slot resolveSlotWrapperTarget(Slot wrapper) {
        try {
            for (Class<?> clazz = wrapper.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
                for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                    if (Slot.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Slot target = (Slot) f.get(wrapper);
                        if (target != null && target != wrapper) {
                            return target;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("[WaterRender] Reflection fallback failed for SlotWrapper", e);
        }
        return null;
    }

    private static int cardinalDirection(int dx, int dy) {
        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx >= 0 ? DIR_RIGHT : DIR_LEFT;
        } else {
            return dy >= 0 ? DIR_DOWN : DIR_UP;
        }
    }

    private static Map<Integer, int[]> parseFlowData(String data) {
        Map<Integer, int[]> map = new HashMap<>();
        if (data == null || data.isEmpty()) return map;
        for (String part : data.split(",")) {
            String[] kv = part.split(":");
            if (kv.length >= 2) {
                int slot = Integer.parseInt(kv[0]);
                int level = Integer.parseInt(kv[1]);
                int fromSlot = kv.length >= 3 ? Integer.parseInt(kv[2]) : -1;
                map.put(slot, new int[]{level, fromSlot});
            }
        }
        return map;
    }

    private record WaterCell(int level, int direction) {}
    private record WaterBucketRender(Map<Integer, WaterCell> cells, int handlerWidth) {}
}