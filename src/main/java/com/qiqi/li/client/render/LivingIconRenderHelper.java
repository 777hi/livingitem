package com.qiqi.li.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 容器 GUI 内「把方块模型当图标画」的统一入口。
 *
 * <h2>为什么需要它</h2>
 * 在 GUI 里走世界渲染管线画模型时，**光照与渲染类型必须手动指定**，否则图标会发暗：
 * <ul>
 *   <li>不传 {@link LightTexture#FULL_BRIGHT} ⇒ 光照图为 0（世界坐标在 GUI 里无意义）⇒ 全黑；</li>
 *   <li>用默认 RenderType（会转成实体渲染变体）⇒ 其着色器带**双光源漫反射**（按法线明暗），
 *       十字/平面模型法线朝水平方向，亮度被吃掉大半 ⇒ 发暗。</li>
 * </ul>
 * 历史上每加一个这类图标都要复制一遍「FULL_BRIGHT + 强制 cutout + 异常隔离」三件套
 * ⇒ 收敛到此处，新加图标**一行调用**即可（2026-09-22）。
 *
 * <h2>另一条路径：走 {@code LivingIconSpec} 的 item 模型图标</h2>
 * 若图标能表达成 item 模型（含 {@code parent: "builtin/entity"} 的 3D 方块实体），
 * 用 {@code LivingIconSpec} 注册即可 —— 其光照由
 * {@code GenericContextAwareModel.usesBlockLight() = false} 统一处理（GUI 平铺光照、全亮），
 * **不需要**本类。
 *
 * @see com.qiqi.li.client.icon.GenericContextAwareModel#usesBlockLight()
 */
public final class LivingIconRenderHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/IconRender");

    /** 槽位格距：16px 内容 + 2px 边框。 */
    public static final float SLOT_PITCH = 18.0F;

    /** 图标绘制的默认 z 层级（与装饰器 z 基准一致，见 icon-system.md 名义层级表）。 */
    public static final float DEFAULT_Z = 100.0F;

    private LivingIconRenderHelper() {
    }

    /**
     * 在容器 GUI 里把一个 {@link BlockState} 的模型画成图标。
     *
     * <p>几何契约：块底锚定**槽位格底**、按 {@value #SLOT_PITCH}px 渲染
     * （16px 会有格缝，18px 让相邻格子的方块无缝相连），水平左偏 1px 对齐。
     *
     * @param guiGraphics GUI 绘制上下文
     * @param state       要渲染的方块状态（可为任意模组方块，走完整世界渲染管线：
     *                    blockstate → 烘焙模型 → BlockColors 染色 → RenderType 路由）
     * @param x           槽位左上角的**绝对** x（调用方自行加 {@code leftPos}）
     * @param y           槽位左上角的**绝对** y（调用方自行加 {@code topPos}）
     */
    public static void renderBlockIcon(GuiGraphics guiGraphics, BlockState state, int x, int y) {
        renderBlockIcon(guiGraphics, state, x, y, DEFAULT_Z);
    }

    /**
     * 同 {@link #renderBlockIcon(GuiGraphics, BlockState, int, int)}，但可指定 z 层级
     * （需要与其它叠加层区分前后时使用）。
     */
    public static void renderBlockIcon(GuiGraphics guiGraphics, BlockState state,
                                       int x, int y, float z) {
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(x - 1, y + SLOT_PITCH, z);
        pose.scale(SLOT_PITCH, -SLOT_PITCH, SLOT_PITCH);
        renderBlockModel(state, pose, Minecraft.getInstance().renderBuffers().bufferSource());
        pose.popPose();
        // 立即物化：先于后续立即模式绘制，层级确定
        Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
    }

    /**
     * 底层：在**调用方给定的 pose 与 buffer** 内渲染方块模型（全亮 + 无漫反射 + 异常隔离）。
     *
     * <p>调用方负责 pose 的 push/pop 与缩放；本方法只保证光照、渲染类型与异常隔离。
     * 需要非标准层级/缩放时用这个重载。
     */
    public static void renderBlockModel(BlockState state, PoseStack pose, MultiBufferSource buffer) {
        try {
            // 强制 cutout（7 参重载）：默认会转实体渲染变体，其着色器带双光源漫反射
            // ⇒ 十字/平面模型发暗；cutout 无漫反射，亮度纯由 FULL_BRIGHT 光照图决定
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose,
                buffer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                ModelData.EMPTY, RenderType.cutout());
        } catch (Exception e) {
            // 异常隔离：第三方方块的 BlockColors 处理器拿 null level/pos 可能 NPE ——
            // 单个图标渲染失败只跳过该槽，不终止整帧渲染循环
            LOGGER.warn("[IconRender] 方块模型渲染失败，跳过：{}（{}）",
                BuiltInRegistries.BLOCK.getKey(state.getBlock()), e.toString());
        }
    }
}
