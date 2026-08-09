package com.qiqi.li.client.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.qiqi.li.client.input.GuiInteractionHelper;
import com.qiqi.li.client.gui.LivingButton;
import com.qiqi.li.client.render.ExpandedMapTexture;
import com.qiqi.li.client.render.LivingMapLayout;
import com.qiqi.li.client.render.LivingMapTargetRenderer;
import com.qiqi.li.client.util.LivingChestTabState;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.map.MapCoordHelper;
import com.qiqi.li.living.function.LivingChestFunction;
import com.qiqi.li.living.function.LivingEnderPearlFunction;
import com.qiqi.li.network.LivingChestAccessPacket;
import com.qiqi.li.network.LivingMapGuiTeleportPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.MapDecorationTextureManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenMixin extends Screen {
    protected AbstractContainerScreenMixin(Component title) {
        super(title);
    }

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Shadow @Final
    protected AbstractContainerMenu menu;

    @Shadow protected int imageHeight;

    @Shadow protected int imageWidth;

    @Shadow
    protected Slot hoveredSlot;

    @Unique
    private int living_item$previousLeftPos = this.leftPos;

    @Unique
    private int living_item$previousTopPos = this.topPos;

    @Unique
    private List<LivingMapLayout.MapGroup> living_item$mapGroups = Collections.emptyList();

    @Unique
    private int living_item$mapContentHash = 0;

    @Unique
    private static final Map<Integer, ExpandedMapTexture> living_item$EXPANDED_TEXTURE_CACHE = new HashMap<>();

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void living_item$interceptMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        living_item$updateMapGroups();

        if (button == 1 && living_item$hasLivingEnderPearl()) {
            LivingMapLayout.MapGroup group = LivingMapLayout.findGroupAt(
                living_item$mapGroups, mouseX, mouseY, leftPos, topPos);
            if (group == null && this.hoveredSlot != null) {
                group = living_item$findGroupBySlot(this.hoveredSlot);
            }
            if (group != null) {
                float[] uv = LivingMapLayout.computeUV(group, mouseX, mouseY, leftPos, topPos);
                CompoundTag carriedTag = living_item$resolveCarriedTag();
                PacketDistributor.sendToServer(new LivingMapGuiTeleportPacket(
                    group.topLeftSlotIndex(), uv[0], uv[1], carriedTag));
                cir.setReturnValue(true);
                return;
            }
            if (this.hoveredSlot != null && LivingMapLayout.isSingleLivingMapSlot(this.hoveredSlot)) {
                float[] uv = LivingMapLayout.computeSingleSlotUV(this.hoveredSlot, mouseX, mouseY, leftPos, topPos);
                CompoundTag carriedTag = living_item$resolveCarriedTag();
                PacketDistributor.sendToServer(new LivingMapGuiTeleportPacket(
                    this.hoveredSlot.index, uv[0], uv[1], carriedTag));
                cir.setReturnValue(true);
                return;
            }
        }

        if (GuiInteractionHelper.tryInteract(this.hoveredSlot, button, this.menu)) {
            cir.setReturnValue(true);
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            boolean hasLivingChest = false;
            if (this.hoveredSlot != null) {
                ItemStack slotStack = this.hoveredSlot.getItem();
                if (LivingChestFunction.isLivingChest(slotStack)) {
                    hasLivingChest = true;
                }
            }
            if (!hasLivingChest) {
                ItemStack cursorStack = this.menu.getCarried();
                if (LivingChestFunction.isLivingChest(cursorStack)) {
                    hasLivingChest = true;
                }
            }
            if (hasLivingChest) {
                cir.setReturnValue(true);
                return;
            }
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_1 && hasShiftDown()
            && LivingChestTabState.isActive()
            && living_item$isRecipeBookVisible()
            && this.hoveredSlot != null && this.hoveredSlot.hasItem()) {
            ItemStack slotStack = this.hoveredSlot.getItem();
            if (!LivingChestFunction.isLivingChest(slotStack)) {
                CompoundTag itemTag = (CompoundTag) slotStack.saveOptional(
                    Minecraft.getInstance().player.registryAccess());
                PacketDistributor.sendToServer(new LivingChestAccessPacket(
                    LivingChestAccessPacket.DEPOSIT_SLOT, itemTag, slotStack.getCount()));
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void living_item$interceptMouseReleased(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (GuiInteractionHelper.tryInteract(this.hoveredSlot, button, this.menu)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void living_item$renderExpandedMaps(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        living_item$updateMapGroups();

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        for (LivingMapLayout.MapGroup group : living_item$mapGroups) {
            living_item$renderExpandedMap(guiGraphics, mc, group, mouseX, mouseY);
        }
    }

    @Unique
    private void living_item$renderExpandedMap(GuiGraphics guiGraphics, Minecraft mc, LivingMapLayout.MapGroup group, int mouseX, int mouseY) {
        if (group.mapId() == null) return;

        MapItemSavedData mapData = MapItem.getSavedData(group.mapId(), mc.level);
        if (mapData == null) return;

        ExpandedMapTexture tex = living_item$getOrCreateExpandedTexture(group.mapId().id(), mapData);
        if (tex == null) return;

        tex.updateIfNeeded(mapData);

        int areaX = leftPos + group.x() - LivingMapLayout.SLOT_BORDER_OFFSET;
        int areaY = topPos + group.y() - LivingMapLayout.SLOT_BORDER_OFFSET;
        int areaSize = group.n() * LivingMapLayout.SLOT_SIZE;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 300);

        guiGraphics.fill(areaX, areaY, areaX + areaSize, areaY + areaSize, 0xFF000000);
        guiGraphics.blit(tex.location, areaX, areaY, 0, 0, areaSize, areaSize, areaSize, areaSize);

        living_item$renderExpandedMapDecorations(guiGraphics, mapData, areaX, areaY, areaSize);

        if (living_item$hasLivingEnderPearl()) {
            float[] uv = LivingMapLayout.computeUV(group, mouseX, mouseY, leftPos, topPos);
            int mapX = MapCoordHelper.uvToMapX(uv[0]);
            int mapY = MapCoordHelper.uvToMapY(uv[1]);

            if (mapX >= 0 && mapX < MapCoordHelper.MAP_SIZE && mapY >= 0 && mapY < MapCoordHelper.MAP_SIZE) {
                boolean bannerHit = MapCoordHelper.isBannerDecorationHit(mapData, mapX, mapY);
                MapDecoration targetPoint = !bannerHit ? MapCoordHelper.findTargetPointHit(mapData, mapX, mapY) : null;
                boolean explored = MapCoordHelper.isExplored(mapData, mapX, mapY);

                float pixelSize = (float) areaSize / MapCoordHelper.MAP_SIZE;
                float markerX = areaX + (mapX * pixelSize);
                float markerY = areaY + (mapY * pixelSize);

                LivingMapTargetRenderer.renderMarkerGui(guiGraphics, markerX, markerY,
                    explored, bannerHit, targetPoint != null, pixelSize);
            }
        }

        guiGraphics.pose().popPose();
    }

    @Inject(method = "renderFloatingItem", at = @At("HEAD"), cancellable = true)
    private void living_item$hideFloatingPearl(GuiGraphics guiGraphics, ItemStack stack, int x, int y, String text, CallbackInfo ci) {
        if (LivingEnderPearlFunction.isLivingEnderPearl(stack)) {
            living_item$updateMapGroups();
            float cursorX = x + 8;
            float cursorY = y + 8;
            for (LivingMapLayout.MapGroup group : living_item$mapGroups) {
                float relAreaX = group.x() - LivingMapLayout.SLOT_BORDER_OFFSET;
                float relAreaY = group.y() - LivingMapLayout.SLOT_BORDER_OFFSET;
                float areaSize = group.n() * LivingMapLayout.SLOT_SIZE;
                if (cursorX >= relAreaX && cursorX < relAreaX + areaSize
                    && cursorY >= relAreaY && cursorY < relAreaY + areaSize) {
                    ci.cancel();
                    return;
                }
            }
            for (Slot slot : this.menu.slots) {
                if (LivingMapLayout.isSingleLivingMapSlot(slot)) {
                    float relSlotX = slot.x - LivingMapLayout.SLOT_BORDER_OFFSET;
                    float relSlotY = slot.y - LivingMapLayout.SLOT_BORDER_OFFSET;
                    float slotSize = LivingMapLayout.SLOT_SIZE;
                    if (cursorX >= relSlotX && cursorX < relSlotX + slotSize
                        && cursorY >= relSlotY && cursorY < relSlotY + slotSize) {
                        ci.cancel();
                        return;
                    }
                }
            }
        }
    }

    @Unique
    private void living_item$renderExpandedMapDecorations(GuiGraphics guiGraphics, MapItemSavedData mapData, int areaX, int areaY, int areaSize) {
        MapDecorationTextureManager decoTextures = Minecraft.getInstance().getMapDecorationTextures();
        float scale = (float) areaSize / 128;
        int index = 0;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(areaX, areaY, 1);

        for (MapDecoration deco : mapData.getDecorations()) {
            float decoX = ((float) deco.x() / 2.0F + 64.0F) * scale;
            float decoY = ((float) deco.y() / 2.0F + 64.0F) * scale;

            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(decoX, decoY, (float) index * -0.01F);
            guiGraphics.pose().mulPose(Axis.ZP.rotationDegrees((float) (deco.rot() * 360) / 16.0F));
            float decoSize = 4.0F * scale;
            guiGraphics.pose().scale(decoSize, decoSize, 1.0F);
            guiGraphics.pose().translate(-0.5F, 0.5F, 0.0F);

            TextureAtlasSprite sprite = decoTextures.get(deco);
            MultiBufferSource.BufferSource bufferSource = guiGraphics.bufferSource();
            VertexConsumer vc = bufferSource.getBuffer(RenderType.text(sprite.atlasLocation()));
            Matrix4f matrix = guiGraphics.pose().last().pose();
            int light = 0xF000F0;

            vc.addVertex(matrix, -1, 1, 0).setColor(-1).setUv(sprite.getU0(), sprite.getV0()).setLight(light);
            vc.addVertex(matrix, 1, 1, 0).setColor(-1).setUv(sprite.getU1(), sprite.getV0()).setLight(light);
            vc.addVertex(matrix, 1, -1, 0).setColor(-1).setUv(sprite.getU1(), sprite.getV1()).setLight(light);
            vc.addVertex(matrix, -1, -1, 0).setColor(-1).setUv(sprite.getU0(), sprite.getV1()).setLight(light);

            guiGraphics.pose().popPose();
            index++;
        }
        guiGraphics.pose().popPose();
    }

    @Unique
    private void living_item$updateMapGroups() {
        int hash = computeContentHash();
        if (hash != living_item$mapContentHash) {
            living_item$mapContentHash = hash;
            living_item$mapGroups = LivingMapLayout.scan(menu.slots);
        }
    }

    @Unique
    private int computeContentHash() {
        int h = 1;
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (LivingItemManager.isLivingItem(stack) && (stack.is(Items.FILLED_MAP) || stack.is(Items.MAP))) {
                h = 31 * h + slot.index;
                h = 31 * h + slot.x;
                h = 31 * h + slot.y;
                h = 31 * h + stack.getItem().hashCode();
            }
        }
        return h;
    }

    @Unique
    private boolean living_item$hasLivingEnderPearl() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        if (LivingEnderPearlFunction.isLivingEnderPearl(mc.player.getMainHandItem())
            || LivingEnderPearlFunction.isLivingEnderPearl(mc.player.getOffhandItem())) {
            return true;
        }
        return LivingEnderPearlFunction.isLivingEnderPearl(this.menu.getCarried());
    }

    @Unique
    private CompoundTag living_item$resolveCarriedTag() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        if (!(mc.screen instanceof CreativeModeInventoryScreen)) return null;
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) return null;
        return (CompoundTag) carried.saveOptional(mc.player.registryAccess());
    }

    @Unique
    private ExpandedMapTexture living_item$getOrCreateExpandedTexture(int mapId, MapItemSavedData mapData) {
        return living_item$EXPANDED_TEXTURE_CACHE.compute(mapId, (id, existing) -> {
            if (existing != null && !existing.isClosed()) {
                return existing;
            }
            ExpandedMapTexture tex = ExpandedMapTexture.create(id, mapData);
            if (tex != null) {
                Minecraft.getInstance().getTextureManager().register(tex.location, tex.texture);
            }
            return tex;
        });
    }

    @Unique
    @Nullable
    private LivingMapLayout.MapGroup living_item$findGroupBySlot(Slot slot) {
        for (LivingMapLayout.MapGroup group : living_item$mapGroups) {
            for (int idx : group.slotIndices()) {
                if (menu.slots.get(idx) == slot) {
                    return group;
                }
            }
        }
        return null;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void living_item$addLivingButton(CallbackInfo ci) {
        this.living_item$initButtons();
        living_item$mapContentHash = 0;
        living_item$mapGroups = Collections.emptyList();
        LivingButton.LOGGER.info("AbstractContainerScreenMixin on loaded");
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void living_item$cleanupExpandedTextures(CallbackInfo ci) {
        for (ExpandedMapTexture tex : living_item$EXPANDED_TEXTURE_CACHE.values()) {
            Minecraft.getInstance().getTextureManager().release(tex.location);
        }
        living_item$EXPANDED_TEXTURE_CACHE.clear();
        living_item$mapContentHash = 0;
        living_item$mapGroups = Collections.emptyList();
    }

    @Inject(method = "containerTick", at = @At("TAIL"))
    private void living_item$checkForLeftOrTopPosChange(CallbackInfo ci) {
        if (this.leftPos != this.living_item$previousLeftPos || this.topPos != this.living_item$previousTopPos) {
            this.living_item$initButtons();
        }
    }

    @Unique
    private void living_item$initButtons() {
        List<LivingButton> existingLivingButtons = new ArrayList<>();
        for (GuiEventListener widget : this.children()) {
            if (widget instanceof LivingButton livingButton) {
                existingLivingButtons.add(livingButton);
            }
        }

        for (LivingButton button : existingLivingButtons) {
            this.removeWidget(button);
        }

        if ((Screen) this instanceof InventoryScreen || (Screen) this instanceof CreativeModeInventoryScreen) {
            this.living_item$previousLeftPos = this.leftPos;
            this.living_item$previousTopPos = this.topPos;
            return;
        }

        if (this.menu.slots.size() > 9) {
            Slot invSlot = this.menu.slots.get(9);
            this.addRenderableWidget(new LivingButton(
                    this.leftPos + (this.imageWidth - 16) / 2, this.topPos + this.imageHeight - 94, this.menu, invSlot));
        }

        this.living_item$previousLeftPos = this.leftPos;
        this.living_item$previousTopPos = this.topPos;
    }

    @Unique
    private boolean living_item$isRecipeBookVisible() {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof InventoryScreen invScreen) {
            return invScreen.getRecipeBookComponent().isVisible();
        }
        return false;
    }
}