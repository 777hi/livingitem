package com.qiqi.li.client.render;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.qiqi.li.living.domain.map.LivingEnderPearlFunction;
import com.qiqi.li.living.domain.map.MapCoordHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.MapDecorationTextureManager;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.joml.Matrix4f;

/**
 * 活地图的「展开显示」：把容器界面里相邻的一格已展开活地图 + 若干空格活地图
 * 合成一张大图铺满那一片槽位（{@link LivingMapLayout} 负责找布局，本类负责画）。
 *
 * <h3>为什么挂在 {@code ContainerScreenEvent.Render.Foreground} 而不是 {@code render} 的 Mixin</h3>
 * <p>2026-10-29 真机：精妙背包界面里展开地图<b>完全不渲染</b>。根因同活流体那次
 * （2026-10-19，见 {@link ContainerFluidOverlay}）：精妙核心的
 * {@code StorageScreenBase.render} 把 {@code super.render} <b>抄了一份</b>
 * （{@code renderSuper}）而从不调用父实现 ⇒ 注入在 {@code AbstractContainerScreen.render}
 * 里的字节码永远不执行。改挂 NeoForge 的容器界面渲染事件，原版与照抄型第三方界面都会发。</p>
 *
 * <p>⚠️ <b>坐标</b>：该事件在<span>已按 (leftPos, topPos) 平移</span>的位姿里触发（两类界面皆然），
 * 故 {@link LivingContainerOverlay} 先把位姿重置为 identity，本类内部一律用屏幕绝对坐标。</p>
 */
public final class LivingMapOverlay {

    /**
     * 扩展地图纹理缓存（地图 ID → 纹理）。
     *
     * <p>静态且跨界面共享：同一张地图在背包 / 箱子 / 玩家物品栏里是同一份纹理，
     * 关界面时由 {@link #releaseTextures()} 统一释放（原先由 Mixin 的 {@code removed} 注入调）。</p>
     */
    private static final Map<Integer, ExpandedMapTexture> EXPANDED_TEXTURE_CACHE = new HashMap<>();

    /** 扩展地图的垫底颜色，与 ExpandedMapTexture 的未探索区域填充色一致，避免纹理未上传时闪黑块。 */
    private static final int MAP_BACKDROP_COLOR = 0xFFD6BE96;

    /** 扩展地图底图的 z 深度。物品模型 z=150、原版堆叠数文字 z=200，需高于两者。 */
    private static final int MAP_BASE_Z = 300;

    /**
     * 装饰图标相对底图的 z 抬升量。
     * <p>GUI 坐标系 z 越大越靠前，而 RenderType.text 带 LEQUAL 深度测试，
     * 底图已写入 z=300，因此装饰必须整体抬到底图之上，且预留足够余量容纳
     * MapItemSavedData.TRACKED_DECORATION_LIMIT(256) 个装饰的层间步进。
     */
    private static final int DECORATION_Z_OFFSET = 2;

    /** 装饰之间的 z 步进（正向递增，后绘制的更靠前），对齐原版 MapRenderer 的 0.001 量级。 */
    private static final float DECORATION_Z_STEP = 0.001F;

    /** GUI 十字准心的 z 深度，需高于底图与所有装饰图标。 */
    private static final int MARKER_Z = 4;

    private LivingMapOverlay() {}

    /** 画当前界面里所有已成型的地图组。位姿须已重置为 identity（见类注释）。 */
    public static void render(GuiGraphics guiGraphics, AbstractContainerScreen<?> screen,
                              int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        List<LivingMapLayout.MapGroup> groups = LivingMapGroups.of(screen);
        if (groups.isEmpty()) return;

        int leftPos = screen.getGuiLeft();
        int topPos = screen.getGuiTop();

        LivingMapLayout.MapGroup hoveredGroup = LivingMapLayout.findGroupAt(groups, mouseX, mouseY, leftPos, topPos);
        for (LivingMapLayout.MapGroup group : groups) {
            renderGroup(guiGraphics, mc, group, mouseX, mouseY, hoveredGroup, leftPos, topPos);
        }
    }

    /** 释放全部扩展地图纹理（界面关闭时调，避免纹理管理器里堆积）。 */
    public static void releaseTextures() {
        for (ExpandedMapTexture tex : EXPANDED_TEXTURE_CACHE.values()) {
            Minecraft.getInstance().getTextureManager().release(tex.location);
        }
        EXPANDED_TEXTURE_CACHE.clear();
    }

    private static void renderGroup(GuiGraphics guiGraphics, Minecraft mc, LivingMapLayout.MapGroup group,
                                    int mouseX, int mouseY, LivingMapLayout.MapGroup hoveredGroup,
                                    int leftPos, int topPos) {
        if (group.mapId() == null) return;

        MapItemSavedData mapData = MapItem.getSavedData(group.mapId(), mc.level);
        if (mapData == null) return;

        ExpandedMapTexture tex = getOrCreateTexture(group.mapId().id(), mapData);
        if (tex == null) return;

        tex.updateIfNeeded(mapData);

        int areaX = leftPos + group.x() - LivingMapLayout.SLOT_BORDER_OFFSET;
        int areaY = topPos + group.y() - LivingMapLayout.SLOT_BORDER_OFFSET;
        int areaSize = group.n() * LivingMapLayout.SLOT_SIZE;

        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(0, 0, MAP_BASE_Z);

        guiGraphics.fill(areaX, areaY, areaX + areaSize, areaY + areaSize, MAP_BACKDROP_COLOR);
        guiGraphics.blit(tex.location, areaX, areaY, 0, 0, areaSize, areaSize, areaSize, areaSize);

        renderDecorations(guiGraphics, mapData, areaX, areaY, areaSize);

        if (hoveredGroup == group && hasLivingEnderPearl()) {
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
                    explored, decoHit, pixelSize, MARKER_Z);
            }
        }

        pose.popPose();
    }

    private static void renderDecorations(GuiGraphics guiGraphics, MapItemSavedData mapData,
                                          int areaX, int areaY, int areaSize) {
        MapDecorationTextureManager decoTextures = Minecraft.getInstance().getMapDecorationTextures();
        float scale = (float) areaSize / 128;
        int index = 0;

        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(areaX, areaY, DECORATION_Z_OFFSET);

        for (MapDecoration deco : mapData.getDecorations()) {
            float decoX = ((float) deco.x() / 2.0F + 64.0F) * scale;
            float decoY = ((float) deco.y() / 2.0F + 64.0F) * scale;

            pose.pushPose();
            pose.translate(decoX, decoY, (float) index * DECORATION_Z_STEP);
            pose.mulPose(Axis.ZP.rotationDegrees((float) (deco.rot() * 360) / 16.0F));
            float decoSize = 4.0F * scale;
            pose.scale(decoSize, decoSize, 1.0F);
            pose.translate(-0.5F, 0.5F, 0.0F);

            TextureAtlasSprite sprite = decoTextures.get(deco);
            MultiBufferSource.BufferSource bufferSource = guiGraphics.bufferSource();
            VertexConsumer vc = bufferSource.getBuffer(RenderType.text(sprite.atlasLocation()));
            Matrix4f matrix = pose.last().pose();
            int light = 0xF000F0;

            vc.addVertex(matrix, -1, 1, 0).setColor(-1).setUv(sprite.getU0(), sprite.getV0()).setLight(light);
            vc.addVertex(matrix, 1, 1, 0).setColor(-1).setUv(sprite.getU1(), sprite.getV0()).setLight(light);
            vc.addVertex(matrix, 1, -1, 0).setColor(-1).setUv(sprite.getU1(), sprite.getV1()).setLight(light);
            vc.addVertex(matrix, -1, -1, 0).setColor(-1).setUv(sprite.getU0(), sprite.getV1()).setLight(light);

            pose.popPose();
            index++;
        }
        pose.popPose();
    }

    private static ExpandedMapTexture getOrCreateTexture(int mapId, MapItemSavedData mapData) {
        return EXPANDED_TEXTURE_CACHE.compute(mapId, (id, existing) -> {
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

    /** 玩家主手或副手持活末影珍珠时，展开地图上才显示传送准心。 */
    private static boolean hasLivingEnderPearl() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        return LivingEnderPearlFunction.isLivingEnderPearl(mc.player.getMainHandItem())
            || LivingEnderPearlFunction.isLivingEnderPearl(mc.player.getOffhandItem());
    }
}
