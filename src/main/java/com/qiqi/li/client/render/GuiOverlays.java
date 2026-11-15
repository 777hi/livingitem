package com.qiqi.li.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 容器界面 GUI 自绘叠加层的统一绘制入口（2026-11-14 立法，取代 D-container-27 的「绝对 Z=400」）。
 *
 * <h3>为什么统一：手选 Z 是同类事故的源头</h3>
 * <p>Foreground 触发时深度测试开着（物品渲染类型把 LEQUAL 留在全局状态），Z 高度即可见性；
 * 而事件位姿里累计着<b>界面基座</b>——第三方 {@code renderBg} 的未配对平移会把它抬走
 * （TLM 女仆 +200）⇒ 任何「绝对 Z」跨界面都不稳定。历史事故：Z=0 被 TLM 内容剔除
 * （流体不渲染，D-container-27 起因）；绝对 Z=400 盖住光标物品（光标物品在基座+382）。</p>
 *
 * <h3>原版 Z 梯子（相对 Foreground 事件位姿基座，GuiGraphics/AbstractContainerScreen 核实）</h3>
 * <pre>
 *    0    界面背景（fill/blit，写深度）
 *   +100  renderSlot 基座（空槽图标）
 *   +250  槽位物品模型（100+150）
 *   +290/+300  IItemDecorator 自抬 / 槽位数量文字（100+200）
 *   +350  ← 覆盖层族基线（窗口 (300, 382) 正中）
 *   +382  光标物品模型（232+150）
 *   +400  tooltip
 *   +432  光标物品数量文字
 * </pre>
 * 相对差是原版常数、与界面基座无关 ⇒ 本协议一律「基座 + 相对量」，禁止绝对 Z。
 *
 * <h3>两条不变量</h3>
 * <ul>
 *   <li><b>盖住一切先画的</b>：本协议在 Foreground（一切内容之后）触发 ⇒ 绘制顺序天然在上；
 *       覆盖层族另禁深度测试——即时路径（blit）免疫任何第三方基座深度，且不写深度、不影响后续。</li>
 *   <li><b>被一切后画的盖住</b>：Foreground 之后原版只画光标物品 / 回弹（+382）/ tooltip（+400）；
 *       批量路径（fill/text，深度焊死在 RenderType 里）靠基线 &lt; 382/400 服从。</li>
 * </ul>
 *
 * <p>⚠️ IItemDecorator 族（海绵 / 进度条 / 耕地种子 / 白幕）不走本入口：它们继承<b>槽位位姿</b>，
 * 自抬 ≤200 ⇒ 净 ≤+300，同样是相对位协议，NeoForge 代管状态备份恢复。</p>
 */
public final class GuiOverlays {

    /** 覆盖层族基线：&gt; 一切先画内容（≤+300），&lt; 光标物品（+382）/ tooltip（+400）。 */
    private static final float CONTENT_Z = 350f;

    /** 槽位级族基线：&gt; 槽位物品（+250）、&lt; 数量文字（+300）——「长在槽位里的实体」（活塞板 / 作物大图）。 */
    private static final float SLOT_LEVEL_Z = 275f;

    private GuiOverlays() {}

    /** 覆盖层族：盖住界面已画的一切，被光标物品 / tooltip 盖住（流体 / 门面 / 地图 / HUD 类）。 */
    public static void paintOverContent(GuiGraphics guiGraphics, Runnable body) {
        paint(guiGraphics, CONTENT_Z, body, true);
    }

    /** 槽位级族：夹在槽位物品与数量文字之间，深度测试保持开（服从数量文字的既定层级）。 */
    public static void paintSlotLevel(GuiGraphics guiGraphics, Runnable body) {
        paint(guiGraphics, SLOT_LEVEL_Z, body, false);
    }

    private static void paint(GuiGraphics guiGraphics, float zRel, Runnable body, boolean cover) {
        PoseStack pose = guiGraphics.pose();
        // 界面基座 = 事件位姿累计的 Z 平移（原版 0 / TLM +200 / 任意第三方）
        float baseZ = pose.last().pose().m32();   // JOML 平移 z 分量（getTranslation 同源）
        pose.pushPose();
        // 事件在 (leftPos, topPos) 平移位姿里触发 ⇒ 重置，坐标统一用屏幕绝对坐标
        pose.setIdentity();
        pose.translate(0, 0, baseZ + zRel);

        if (cover) {
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
        }
        try {
            body.run();
        } finally {
            if (cover) {
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                RenderSystem.disableBlend();
                // 与原版 AbstractContainerScreen.render 尾部同款兜底
                RenderSystem.enableDepthTest();
            }
            pose.popPose();
        }
    }
}
