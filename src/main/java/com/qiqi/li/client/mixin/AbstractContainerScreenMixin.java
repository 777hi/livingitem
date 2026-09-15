package com.qiqi.li.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
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
import com.qiqi.li.living.domain.chest.LivingChestFunction;
import com.qiqi.li.living.domain.map.LivingEnderPearlFunction;
import com.qiqi.li.living.domain.water.LivingWaterBucketData;
import com.qiqi.li.living.domain.water.LivingWaterBucketFunction;
import com.qiqi.li.living.domain.water.WaterData;
import com.qiqi.li.network.LivingChestAccessPacket;
import com.qiqi.li.network.LivingMapGuiTeleportPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.MapDecorationTextureManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 原版「按下后跳过释放处理」标志（mouseClicked 拿起物品时置位，
     * mouseReleased 开头检测到即 return true 跳过 PICKUP）。
     *
     * <p>手持触发型活物品交互（活锄头/活种子/活骨粉右键槽位）必须在拦截成功时置位：
     * 我们的拦截发生在按下阶段，但原版对「光标非空」的放置/交换发生在释放阶段——
     * 不置位的话释放阶段不匹配交互条目 → 原版 PICKUP 照常执行 → 交互生效的同时
     * 光标物品与槽位物品被交换/放置（交互未拦截原版逻辑的实感来源）。
     * 置位后原版释放阶段自我跳过一次，交互成为该次点击的唯一效果。</p>
     */
    @Shadow
    private boolean skipNextRelease;

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

    /**
     * 扩展地图的垫底颜色，与 ExpandedMapTexture 的未探索区域填充色保持一致，
     * 避免纹理尚未上传时闪现黑块。
     */
    @Unique
    private static final int living_item$MAP_BACKDROP_COLOR = 0xFFD6BE96;

    /**
     * 扩展地图底图的 z 深度。物品模型 z=150、原版堆叠数文字 z=200，需高于两者。
     */
    @Unique
    private static final int living_item$MAP_BASE_Z = 300;

    /**
     * 装饰图标相对底图的 z 抬升量。
     * <p>GUI 坐标系 z 越大越靠前，而 RenderType.text 带 LEQUAL 深度测试，
     * 底图已写入 z=300，因此装饰必须整体抬到底图之上，且预留足够余量容纳
     * MapItemSavedData.TRACKED_DECORATION_LIMIT(256) 个装饰的层间步进。
     */
    @Unique
    private static final int living_item$DECORATION_Z_OFFSET = 2;

    /**
     * 装饰之间的 z 步进（正向递增，后绘制的更靠前），对齐原版 MapRenderer 的 0.001 量级。
     * 256 个装饰共占 0.256，不会溢出 living_item$DECORATION_Z_OFFSET 的余量。
     */
    @Unique
    private static final float living_item$DECORATION_Z_STEP = 0.001F;

    /**
     * GUI 十字准心的 z 深度，需高于底图与所有装饰图标。
     */
    @Unique
    private static final int living_item$MARKER_Z = 4;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void living_item$interceptMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        living_item$updateMapGroups();

        boolean hasPearl = living_item$hasLivingEnderPearl();

        if (button == 1 && hasPearl) {
            LivingMapLayout.MapGroup group = LivingMapLayout.findGroupAt(
                living_item$mapGroups, mouseX, mouseY, leftPos, topPos);
            if (group == null && this.hoveredSlot != null) {
                group = living_item$findGroupBySlot(this.hoveredSlot);
            }
            if (group != null) {
                float[] uv = LivingMapLayout.computeUV(group, mouseX, mouseY, leftPos, topPos);
                int serverSlotIndex = living_item$resolveServerSlotIndex(group.topLeftSlotIndex());
                PacketDistributor.sendToServer(new LivingMapGuiTeleportPacket(
                    serverSlotIndex, uv[0], uv[1]));
                cir.setReturnValue(true);
                return;
            }
            if (this.hoveredSlot != null) {
                boolean isSingle = LivingMapLayout.isSingleLivingMapSlot(this.hoveredSlot);
                if (isSingle) {
                    float[] uv = LivingMapLayout.computeSingleSlotUV(this.hoveredSlot, mouseX, mouseY, leftPos, topPos);
                    int serverSlotIndex = living_item$resolveServerSlotIndex(this.hoveredSlot);
                    PacketDistributor.sendToServer(new LivingMapGuiTeleportPacket(
                        serverSlotIndex, uv[0], uv[1]));
                    cir.setReturnValue(true);
                    return;
                }
            }
        }

        if (GuiInteractionHelper.tryInteract(this.hoveredSlot, button, false, this.menu)) {
            this.skipNextRelease = true;   // 见字段 javadoc：防释放阶段原版 PICKUP 二次执行
            cir.setReturnValue(true);
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
        if (GuiInteractionHelper.tryInteract(this.hoveredSlot, button, true, this.menu)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true)
    private void living_item$suppressTooltipOnExpandedMap(GuiGraphics guiGraphics, int x, int y, CallbackInfo ci) {
        if (!living_item$hasLivingEnderPearl()) return;

        living_item$updateMapGroups();

        LivingMapLayout.MapGroup group = LivingMapLayout.findGroupAt(
            living_item$mapGroups, x, y, leftPos, topPos);
        if (group != null) {
            ci.cancel();
            return;
        }

        if (this.hoveredSlot != null && LivingMapLayout.isSingleLivingMapSlot(this.hoveredSlot)) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void living_item$renderExpandedMaps(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        living_item$updateMapGroups();

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        LivingMapLayout.MapGroup hoveredGroup = LivingMapLayout.findGroupAt(
            living_item$mapGroups, mouseX, mouseY, leftPos, topPos);

        for (LivingMapLayout.MapGroup group : living_item$mapGroups) {
            living_item$renderExpandedMap(guiGraphics, mc, group, mouseX, mouseY, hoveredGroup);
        }
    }

    @Unique
    private void living_item$renderExpandedMap(GuiGraphics guiGraphics, Minecraft mc, LivingMapLayout.MapGroup group, int mouseX, int mouseY, @Nullable LivingMapLayout.MapGroup hoveredGroup) {
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
        guiGraphics.pose().translate(0, 0, living_item$MAP_BASE_Z);

        guiGraphics.fill(areaX, areaY, areaX + areaSize, areaY + areaSize, living_item$MAP_BACKDROP_COLOR);
        guiGraphics.blit(tex.location, areaX, areaY, 0, 0, areaSize, areaSize, areaSize, areaSize);

        living_item$renderExpandedMapDecorations(guiGraphics, mapData, areaX, areaY, areaSize);

        if (living_item$hasLivingEnderPearl() && hoveredGroup == group) {
            float[] uv = LivingMapLayout.computeUV(group, mouseX, mouseY, leftPos, topPos);
            int mapX = MapCoordHelper.uvToMapX(uv[0]);
            int mapY = MapCoordHelper.uvToMapY(uv[1]);

            if (mapX >= 0 && mapX < MapCoordHelper.MAP_SIZE && mapY >= 0 && mapY < MapCoordHelper.MAP_SIZE) {
                boolean decoHit = MapCoordHelper.isTeleportableDecorationHit(mapData, mapX, mapY);
                boolean explored = MapCoordHelper.isExplored(mapData, mapX, mapY);

                float pixelSize = (float) areaSize / MapCoordHelper.MAP_SIZE;
                float markerX = areaX + (mapX * pixelSize);
                float markerY = areaY + (mapY * pixelSize);

                LivingMapTargetRenderer.renderMarkerGui(guiGraphics, markerX, markerY,
                    explored, decoHit, pixelSize, living_item$MARKER_Z);
            }
        }

        guiGraphics.pose().popPose();
    }

    @Unique
    private void living_item$renderExpandedMapDecorations(GuiGraphics guiGraphics, MapItemSavedData mapData, int areaX, int areaY, int areaSize) {
        MapDecorationTextureManager decoTextures = Minecraft.getInstance().getMapDecorationTextures();
        float scale = (float) areaSize / 128;
        int index = 0;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(areaX, areaY, living_item$DECORATION_Z_OFFSET);

        for (MapDecoration deco : mapData.getDecorations()) {
            float decoX = ((float) deco.x() / 2.0F + 64.0F) * scale;
            float decoY = ((float) deco.y() / 2.0F + 64.0F) * scale;

            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(decoX, decoY, (float) index * living_item$DECORATION_Z_STEP);
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
        return LivingEnderPearlFunction.isLivingEnderPearl(mc.player.getMainHandItem())
            || LivingEnderPearlFunction.isLivingEnderPearl(mc.player.getOffhandItem());
    }

    @Unique
    private int living_item$resolveServerSlotIndex(Slot slot) {
        if (slot instanceof SlotWrapperAccessor accessor) {
            return accessor.getTarget().index;
        }
        return slot.index;
    }

    @Unique
    private int living_item$resolveServerSlotIndex(int clientSlotIndex) {
        if (clientSlotIndex >= 0 && clientSlotIndex < menu.slots.size()) {
            Slot slot = menu.slots.get(clientSlotIndex);
            if (slot instanceof SlotWrapperAccessor accessor) {
                return accessor.getTarget().index;
            }
        }
        return clientSlotIndex;
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

    // ==================== Water Flow Rendering ====================

    private static final Logger WATER_LOGGER = LoggerFactory.getLogger("LivingItem/WaterRender");
    private static final Logger CROP_RENDER_LOGGER = LoggerFactory.getLogger("LivingItem/CropRender");

    private static final ConcurrentHashMap<Class<?>, java.lang.reflect.Field[]> SLOT_WRAPPER_FIELD_CACHE = new ConcurrentHashMap<>();

    private static final int DIR_DOWN = 0;
    private static final int DIR_RIGHT = 1;
    private static final int DIR_UP = 2;
    private static final int DIR_LEFT = 3;

    @Inject(method = "render", at = @At("TAIL"))
    private void living_item$renderWaterFlow(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick,
                                              CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        List<WaterBucketRender> buckets = living_item$collectWaterBuckets(self);
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
                    float angleDeg = living_item$directionToRotation(cell.direction());

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

    @Unique
    private static float living_item$directionToRotation(int direction) {
        return switch (direction) {
            case DIR_DOWN -> 0.0f;
            case DIR_RIGHT -> -90.0f;
            case DIR_UP -> 180.0f;
            case DIR_LEFT -> 90.0f;
            default -> 0.0f;
        };
    }

    @Unique
    private List<WaterBucketRender> living_item$collectWaterBuckets(AbstractContainerScreen<?> screen) {
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

            Map<Integer, int[]> handlerFlow = living_item$parseFlowData(flowStr);
            if (handlerFlow.isEmpty()) continue;

            Container bucketContainer = slot.container;
            Map<Integer, Integer> handlerToMenuIndex = new HashMap<>();
            for (int i = 0; i < screen.getMenu().slots.size(); i++) {
                Slot s = screen.getMenu().slots.get(i);
                if (s.container == bucketContainer) {
                    int containerSlot = living_item$resolveContainerSlot(s);
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
                    direction = living_item$cardinalDirection(dx, dy);
                }

                cells.put(menuIndex, new WaterCell(level, direction));
            }

            if (skippedCount > 0) {
                WATER_LOGGER.debug("[WaterRender] Skipped {} flow entries", skippedCount);
            }

            if (!cells.isEmpty()) {
                buckets.add(new WaterBucketRender(cells, handlerWidth));
            }
        }

        return buckets;
    }

    @Unique
    private static int living_item$resolveContainerSlot(Slot s) {
        if (s instanceof SlotWrapperAccessor accessor) {
            return accessor.getTarget().getContainerSlot();
        }
        if (s.getClass().getSimpleName().contains("SlotWrapper")) {
            Slot target = living_item$resolveSlotWrapperTarget(s);
            if (target != null) {
                return target.getContainerSlot();
            }
            WATER_LOGGER.warn("[WaterRender] SlotWrapper detected but could NOT resolve target! class={}", s.getClass().getName());
        }
        return s.getContainerSlot();
    }

    @Unique
    private static Slot living_item$resolveSlotWrapperTarget(Slot wrapper) {
        try {
            Class<?> wrapperClass = wrapper.getClass();
            java.lang.reflect.Field[] fields = SLOT_WRAPPER_FIELD_CACHE.computeIfAbsent(wrapperClass, clazz -> {
                List<java.lang.reflect.Field> slotFields = new ArrayList<>();
                for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (Slot.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            slotFields.add(f);
                        }
                    }
                }
                return slotFields.toArray(new java.lang.reflect.Field[0]);
            });
            for (java.lang.reflect.Field f : fields) {
                Slot target = (Slot) f.get(wrapper);
                if (target != null && target != wrapper) {
                    return target;
                }
            }
        } catch (Exception e) {
            WATER_LOGGER.error("[WaterRender] Reflection fallback failed for SlotWrapper", e);
        }
        return null;
    }

    @Unique
    private static int living_item$cardinalDirection(int dx, int dy) {
        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx >= 0 ? DIR_RIGHT : DIR_LEFT;
        } else {
            return dy >= 0 ? DIR_DOWN : DIR_UP;
        }
    }

    @Unique
    private static Map<Integer, int[]> living_item$parseFlowData(String data) {
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

    @Unique
    private record WaterCell(int level, int direction) {}
    @Unique
    private record WaterBucketRender(Map<Integer, WaterCell> cells, int handlerWidth) {}

    // ==================== Farmland Crop Rendering ====================

    /**
     * 活耕地的「生长槽大图」渲染（docs/idea.md「槽位渲染」统一口径）：
     * 上方生长槽为空时，用世界级管线绘制作物当前阶段的完整方块模型。
     * 客户端读 FarmlandPlantComponent（networkSynchronized 随物品同步），无服务端参与。
     *
     * <p><b>种子图标叠加不在此处</b>——2026-09-16 迁到
     * {@link com.qiqi.li.client.render.LivingFarmlandSeedDecorator}（IItemDecorator）：
     * 原实现依赖 {@code leftPos}/{@code topPos}，而 HUD 快捷栏走
     * {@code Gui.renderHotbar} → {@code renderItemDecorations}，<b>不经过本类</b>，
     * 导致快捷栏里永远画不出来。生长槽大图无法同样迁移——它需要「同容器正上方一格槽位」
     * 的邻居关系，而装饰器只拿得到 {@code xOffset/yOffset}。</p>
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void living_item$renderFarmlandCrops(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick,
                                                 CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        // 生长槽认领表：同列两块耕地中间恰隔一空行时，两块都把中间空槽当生长槽
        // → 同槽双画重叠（2026-09-14 终审修复）——per-frame 先到先得
        java.util.Set<Slot> claimedGrowthSlots = new java.util.HashSet<>();
        for (Slot slot : self.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !stack.is(Items.FARMLAND)) continue;
            if (!LivingItemManager.isLivingItem(stack)) continue;

            com.qiqi.li.living.domain.farmland.FarmlandPlantComponent plant =
                LivingItemManager.getFarmlandPlant(stack);
            if (!plant.isPlanted()) continue;

            net.minecraft.world.level.block.Block cropBlock =
                com.qiqi.li.living.domain.farmland.CropClassifier.getBlockFromSeed(plant.cropSeed());
            if (cropBlock == null) continue;

            // 上方生长槽为空时，用世界级管线绘制作物当前阶段的
            // 完整方块模型（「土下苗上」的田地感）。按坐标匹配（同容器 + 恰在正上方
            // 一格）而非索引算术——天然适应箱子/背包/创造各布局；「空槽即画」自动
            // 覆盖全部状态：生长中显苗、成熟产出后被真实物品覆盖、取走后显成熟形态。
            // 同容器约束防跨容器错位。
            living_item$renderCropInGrowthSlot(self, guiGraphics, slot, cropBlock, plant.age(), claimedGrowthSlots);
        }
    }

    @Unique
    private Slot living_item$findSlotAbove(AbstractContainerScreen<?> self, Slot lowerSlot) {
        for (Slot other : self.getMenu().slots) {
            if (other == lowerSlot) continue;
            if (other.container != lowerSlot.container) continue;
            if (other.x != lowerSlot.x || other.y != lowerSlot.y - 18) continue;
            return other;
        }
        return null;
    }

    @Unique
    private void living_item$renderCropInGrowthSlot(AbstractContainerScreen<?> self, GuiGraphics guiGraphics,
                                                    Slot farmlandSlot,
                                                    net.minecraft.world.level.block.Block cropBlock, int age,
                                                    java.util.Set<Slot> claimedGrowthSlots) {
        Slot growthSlot = living_item$findSlotAbove(self, farmlandSlot);
        if (growthSlot == null || !growthSlot.getItem().isEmpty()) {
            return;   // 无生长槽（顶行）或被占用（含成熟产出）→ 让位/等待
        }
        // 同帧已被其它耕地认领的生长槽（同列隔空行双耕地场景）→ 本块让位防双画
        if (!claimedGrowthSlots.add(growthSlot)) return;

        // 下部件：作物当前 age 的方块状态，世界级管线原样渲染
        // （茎逐段生长几何 + age 染色、任意模组模型——无每作物特判）。
        // displayStateFor：火把花类「maxAge 超属性值域」怪癖——成熟态越界时
        // 回退收获形态方块默认态（花方块），否则成熟后生长槽空白
        BlockState lower = com.qiqi.li.living.domain.farmland.CropClassifier.displayStateFor(cropBlock, age);
        if (lower != null) {
            living_item$renderBlockState(guiGraphics, leftPos + growthSlot.x, topPos + growthSlot.y, lower);
        }

        // 多格上部件三模式互斥（属性结构本互斥，防御双注册双画）：
        // 命中其一即跳过其余——半部件/注册式上部件 与 柱状段 不会叠加
        // 两格高上部件：生长槽正上方的空槽渲染上半个模型（纯视觉，不影响 tick）
        BlockState upper = com.qiqi.li.living.domain.farmland.CropClassifier.getUpperCompanion(cropBlock, age);
        if (upper != null) {
            Slot upperSlot = living_item$findSlotAbove(self, growthSlot);
            if (upperSlot != null && upperSlot.getItem().isEmpty()) {
                living_item$renderBlockState(guiGraphics, leftPos + upperSlot.x, topPos + upperSlot.y, upper);
            }
            return;
        }

        // 柱状多段作物（第三种多格形态：同方块属性分段，如 KC 水稻 location 三段柱）：
        // 各段从生长槽正上方逐格向上渲染，空槽才画、到顶/被占自动截断
        for (BlockState part : com.qiqi.li.living.domain.farmland.CropClassifier
                .getColumnParts(cropBlock, age)) {
            Slot partSlot = living_item$findSlotAbove(self, growthSlot);
            if (partSlot == null || !partSlot.getItem().isEmpty()) break;
            living_item$renderBlockState(guiGraphics, leftPos + partSlot.x, topPos + partSlot.y, part);
            growthSlot = partSlot;   // 下一段从这段正上方继续
        }
    }

    /**
     * 世界级方块渲染进 GUI（通用机制）：任意 BlockState 走完整世界渲染管线——
     * blockstate→烘焙模型→BlockColors 染色→RenderType 路由，原样呈现世界外观
     * （茎逐段生长几何、age 染色、任意模组模型全自动，无每作物特判）。
     * 立即 endBatch 物化（先于后续立即模式绘制，层级确定）。
     */
    @Unique
    private void living_item$renderBlockState(GuiGraphics guiGraphics, int x, int y,
                                              BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(x - 1, y + 18, 100);     // 块底锚定槽位格底（格距 18px = 16 内容 + 2 边框）；左偏 1px 对齐
        pose.scale(18.0F, -18.0F, 18.0F);         // 按 18px 渲染让堆叠方块无缝相连（16px 会有格缝）
        // 强制 cutout RenderType（7 参重载）：默认会转实体渲染变体，其着色器带双光源
        // 漫反射（按法线着色）——作物十字模型法线朝水平方向，漫反射吃掉大半亮度 → 发暗；
        // cutout 无漫反射，亮度纯由 FULL_BRIGHT 光照图决定 → 与物品图标同级全亮
        try {
            mc.getBlockRenderer().renderSingleBlock(state, pose,
                mc.renderBuffers().bufferSource(), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                net.neoforged.neoforge.client.model.data.ModelData.EMPTY, RenderType.cutout());
        } catch (Exception e) {
            // 异常隔离：第三方作物的 BlockColors 处理器拿 null level/pos 可能 NPE——
            // 单作物渲染失败只跳过该槽（2026-09-14 终审加固），不终止整帧渲染循环
            CROP_RENDER_LOGGER.warn("[CropRender] 方块渲染失败，跳过：{}（{}）",
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()),
                e.toString());
        }
        pose.popPose();
        mc.renderBuffers().bufferSource().endBatch();   // 立即物化
    }
}