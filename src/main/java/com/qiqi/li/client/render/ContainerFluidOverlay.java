package com.qiqi.li.client.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidType;

import com.qiqi.li.client.util.ClientSlotResolve;
import com.qiqi.li.living.domain.water.FluidFlowBehaviors;
import com.qiqi.li.living.domain.water.FluidFlowClientCache;

/**
 * 容器级活流体的**槽位覆盖渲染**（2026-10-19 从 {@code AbstractContainerScreenMixin} 抽出）。
 *
 * <h3>为什么不挂在 {@code render} TAIL 的 Mixin 上</h3>
 * <p>原先挂在 {@link AbstractContainerScreen#render} 的 TAIL：对原版界面没问题，
 * 但 **精妙背包完全不渲染活流体**。根因：它的 {@code StorageScreenBase.render} 把自己
 * <b>抄了一份 {@code super.render}（{@code renderSuper}）而不调用父实现</b>
 * ⇒ 我们注入在父类 {@code render} 里的字节码永远不执行。</p>
 *
 * <p>形似的问题不止流体：任何「只在 {@code AbstractContainerScreen.render} 里做事」的 Mixin
 * 对这类模组界面都是死的。tooltip 之所以没事，是因为它挂在 {@code renderTooltip}
 * ——精妙的 {@code render} 确实会调 {@code renderTooltip}。</p>
 *
 * <h3>现在的挂点：{@code ContainerScreenEvent.Render.Foreground}</h3>
 * <p>NeoForge 的容器界面渲染事件，原版与精妙（它照抄了这段）<b>都会发</b> ⇒ 一个挂点两边通吃，
 * 也不再需要「为每个模组补一个 Mixin」。</p>
 *
 * <p>⚠️ **坐标**：该事件在<span>已按 (leftPos, topPos) 平移</span>的位姿里触发
 * （原版与精妙皆然）⇒ 这里<b>先把位姿重置为 identity</b>，再按
 * {@code leftPos + slot.x} / {@code topPos + slot.y} 画，与原先 render TAIL 的坐标空间完全一致。
 * 不这么做会多乘一次 leftPos/topPos。</p>
 */
public final class ContainerFluidOverlay {

    private static final int DIR_DOWN = 0;
    private static final int DIR_RIGHT = 1;
    private static final int DIR_UP = 2;
    private static final int DIR_LEFT = 3;

    /** 每流体的渲染参数缓存（贴图位置 + 染色 + level 上限）。贴图 sprite 每帧取，资源重载安全。 */
    private static final Map<FluidType, FluidVisual> FLUID_VISUAL_CACHE = new java.util.HashMap<>();

    private ContainerFluidOverlay() {}

    /** 事件入口：给当前容器界面画活流体覆盖层。 */
    public static void render(GuiGraphics guiGraphics, AbstractContainerScreen<?> screen) {
        List<FluidCellRender> cells = collect(screen);
        if (cells.isEmpty()) return;

        final int leftPos = screen.getGuiLeft();
        final int topPos = screen.getGuiTop();

        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        // 事件在 (leftPos, topPos) 平移位姿里触发 ⇒ 重置，坐标统一用屏幕绝对坐标
        pose.setIdentity();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);

        for (FluidCellRender cell : cells) {
            net.minecraft.world.inventory.Slot slot = screen.getMenu().getSlot(cell.menuIndex());
            if (slot == null) continue;
            drawFluidCell(guiGraphics, leftPos + slot.x, topPos + slot.y,
                cell.level(), cell.direction(), fluidVisual(cell.fluid()));
        }

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        pose.popPose();
    }

    /**
     * 容器轨取数：菜单槽位按 {@code slot.container} 分组，每组选快照
     * （玩家背包 → {@link FluidFlowClientCache#getPlayer()}，其余 → {@link FluidFlowClientCache#getChestLike()}），
     * handler 槽位经 {@link ClientSlotResolve#resolveContainerSlot} 映射回菜单槽位。
     */
    private static List<FluidCellRender> collect(AbstractContainerScreen<?> screen) {
        List<FluidCellRender> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return out;

        Map<net.minecraft.world.Container, Map<Integer, Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            net.minecraft.world.inventory.Slot s = screen.getMenu().slots.get(i);
            groups.computeIfAbsent(s.container, k -> new java.util.HashMap<>())
                  .put(ClientSlotResolve.resolveContainerSlot(s), i);
        }

        net.minecraft.world.entity.player.Inventory playerInv = mc.player.getInventory();
        for (var group : groups.entrySet()) {
            // 两分：玩家背包 → 背包快照；**其余一律非背包组** → 由服务端下发的 RenderTarget 决定
            // （末影箱界面在客户端与普通 3 行箱子同形，靠 instanceof 判末影箱是死分支 —— 2026-10-06）
            FluidFlowClientCache.FlowSnapshot snapshot;
            if (group.getKey() == playerInv) {
                snapshot = FluidFlowClientCache.getPlayer();
            } else {
                snapshot = FluidFlowClientCache.getChestLike();
            }
            if (snapshot.isEmpty()) continue;

            int width = Math.max(1, snapshot.width());
            for (var cellEntry : snapshot.cells().entrySet()) {
                int handlerSlot = cellEntry.getKey();
                int[] v = cellEntry.getValue();
                Integer menuIndex = group.getValue().get(handlerSlot);
                if (menuIndex == null) continue;

                List<FluidType> palette = snapshot.fluids();
                if (v[2] < 0 || v[2] >= palette.size()) continue;
                FluidType type = palette.get(v[2]);
                if (type == null) continue;

                int level = v[0];
                int fromSlot = v[1];
                int direction;
                if (level == 0 || fromSlot < 0) {
                    direction = DIR_DOWN;
                } else {
                    int dx = (handlerSlot % width) - (fromSlot % width);
                    int dy = (handlerSlot / width) - (fromSlot / width);
                    direction = cardinalDirection(dx, dy);
                }
                out.add(new FluidCellRender(menuIndex, level, direction, type));
            }
        }
        return out;
    }

    /** 单格流体绘制（容器轨）：源 = still 贴图，流动 = flow 贴图按方向旋转；颜色/alpha 按流体。 */
    private static void drawFluidCell(GuiGraphics guiGraphics, int x, int y,
                                      int level, int direction, FluidVisual visual) {
        float maxLevel = visual.maxLevel() > 0 ? visual.maxLevel() : 7.0f;
        float alpha = level == 0 ? 0.55f : 0.25f + (1.0f - Math.min(level, (int) maxLevel) / maxLevel) * 0.3f;
        RenderSystem.setShaderColor(visual.r(), visual.g(), visual.b(), alpha);

        TextureAtlasSprite still = blockSprite(visual.stillTexture());
        TextureAtlasSprite flow = blockSprite(visual.flowTexture());

        if (level == 0) {
            guiGraphics.blit(x, y, 0, 16, 16, still);
        } else {
            float angleDeg = directionToRotation(direction);
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate(x + 8, y + 8, 0);
            pose.mulPose(new Quaternionf().rotateZ((float) Math.toRadians(angleDeg)));
            pose.translate(-8, -8, 0);
            guiGraphics.blit(0, 0, 0, 16, 16, flow);
            pose.popPose();
        }
    }

    /** 每流体的渲染参数（贴图位置 + 染色 + level 上限）。 */
    private static FluidVisual fluidVisual(FluidType type) {
        FluidVisual cached = FLUID_VISUAL_CACHE.get(type);
        if (cached != null) return cached;
        if (FLUID_VISUAL_CACHE.containsKey(type)) return FLUID_VISUAL_CACHE.get(type);

        IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(type);
        ResourceLocation stillLoc = ext.getStillTexture();
        ResourceLocation flowLoc = ext.getFlowingTexture();
        if (stillLoc == null) stillLoc = flowLoc;
        if (flowLoc == null) flowLoc = stillLoc;
        if (stillLoc == null) {
            stillLoc = flowLoc = ResourceLocation.withDefaultNamespace("block/water_still");
        }
        int tint = ext.getTintColor();
        float r = 1.0f, g = 1.0f, b = 1.0f;
        if (tint != -1) {
            int a = (tint >>> 24) & 0xFF;
            if (a == 0) a = 255;
            r = ((tint >> 16) & 0xFF) / 255.0f;
            g = ((tint >> 8) & 0xFF) / 255.0f;
            b = (tint & 0xFF) / 255.0f;
        }
        int maxLevel = FluidFlowBehaviors.of(type).maxLevel();
        FluidVisual visual = new FluidVisual(stillLoc, flowLoc, r, g, b, maxLevel);
        FLUID_VISUAL_CACHE.put(type, visual);
        return visual;
    }

    private static TextureAtlasSprite blockSprite(ResourceLocation loc) {
        return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(loc);
    }

    private static float directionToRotation(int direction) {
        return switch (direction) {
            case DIR_RIGHT -> -90.0f;
            case DIR_UP -> 180.0f;
            case DIR_LEFT -> 90.0f;
            default -> 0.0f;
        };
    }

    private static int cardinalDirection(int dx, int dy) {
        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx >= 0 ? DIR_RIGHT : DIR_LEFT;
        }
        return dy >= 0 ? DIR_DOWN : DIR_UP;
    }

    private record FluidCellRender(int menuIndex, int level, int direction, FluidType fluid) {}

    private record FluidVisual(ResourceLocation stillTexture, ResourceLocation flowTexture,
                               float r, float g, float b, int maxLevel) {}
}
