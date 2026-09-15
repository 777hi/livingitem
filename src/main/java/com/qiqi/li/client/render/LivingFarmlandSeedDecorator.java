package com.qiqi.li.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.farmland.CropClassifier;
import com.qiqi.li.living.domain.farmland.FarmlandPlantComponent;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.IItemDecorator;

/**
 * 活耕地种子图标装饰器 —— 在耕地槽叠加所种作物的**种子物品图标**（一眼区分作物类型）。
 *
 * <h3>为什么用 IItemDecorator（2026-09-16 从容器 Mixin 迁入）</h3>
 * <p>原先画在 {@code AbstractContainerScreenMixin.render @TAIL} 里，那段代码硬依赖
 * {@code leftPos}/{@code topPos}——只有 {@code AbstractContainerScreen} 才有，于是
 * <b>HUD 快捷栏里永远不画</b>（快捷栏走 {@code Gui.renderHotbar} → {@code renderSlot}
 * → {@code GuiGraphics.renderItemDecorations}，从不经过 {@code AbstractContainerScreen}）。</p>
 *
 * <p>而 {@code GuiGraphics.renderItemDecorations} 的<b>最后一行</b>就是
 * {@code ItemDecoratorHandler.of(stack).render(...)}，且容器 GUI 的
 * {@code renderSlotContents} 也调它 ⇒ 一份装饰器代码同时覆盖
 * <b>快捷栏 / 容器 GUI / 创造物品栏 / 副手槽</b>，只画一次、无重复。</p>
 *
 * <h3>渲染状态由 ItemDecoratorHandler 代管</h3>
 * <p>{@code ItemDecoratorHandler} 每次调用前执行 {@code resetRenderState()}
 * （开深度测试 + 开 blend + defaultBlendFunc），返回后再 {@code restoreGlState}。
 * 故本类<b>不做任何 RenderSystem 调用</b>，只需自抬 z（同 {@link LivingHopperDecorator} 先例）。</p>
 *
 * <h3>z 语义（本值是相对槽位 pose 的抬升量，不是绝对 z）</h3>
 * <ul>
 *   <li><b>容器路径</b>：{@code AbstractContainerScreen.renderSlot} 有
 *       {@code translate(0, 0, 100)} 基准 ⇒ 净 z = 100 + 200 = <b>300</b>
 *       （正是旧 Mixin 注释记载「z=300 才可见」的那个值）</li>
 *   <li><b>快捷栏路径</b>：pose 为 identity ⇒ 净 z = <b>200</b> &gt; 物品模型 150</li>
 * </ul>
 * <p>计数文字同在名义 z=200（{@code renderItemDecorations} 内平移，随后 popPose 才分发装饰器），
 * 故全尺寸图标会盖住堆叠数数字——<b>既有已知取舍</b>（2026-09-13 定稿），与迁入前观感一致。</p>
 *
 * <h3>不做生长槽大图</h3>
 * <p>「生长槽大图」（{@code renderSingleBlock} 画作物阶段方块模型）仍留在
 * {@code AbstractContainerScreenMixin}：它需要「同容器正上方一格槽位」的邻居关系，
 * 而装饰器只拿得到 {@code xOffset/yOffset}、拿不到容器槽表，快捷栏也没有该结构。</p>
 */
public class LivingFarmlandSeedDecorator implements IItemDecorator {

    /** 相对槽位 pose 的抬升量，对齐既有装饰器约定（容器净 300 / 快捷栏净 200） */
    private static final int SEED_Z = 200;

    /** 全尺寸覆盖槽位（与迁入前一致） */
    private static final int ICON_SIZE = 16;

    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack,
                          int xOffset, int yOffset) {
        if (!shouldRenderSeed(stack)) return false;

        // cropSeed 非空由 shouldRenderSeed 保证
        ItemStack seed = new ItemStack(LivingItemManager.getFarmlandPlant(stack).cropSeed());
        TextureAtlasSprite sprite = CropTextureResolver.getItemSprite(seed);
        if (sprite == null) return false;   // 纹理缺失 → 跳过

        // pushPose/popPose 必需：translate 改的是共享 pose
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(xOffset, yOffset, SEED_Z);
        guiGraphics.blit(0, 0, 0, ICON_SIZE, ICON_SIZE, sprite);
        pose.popPose();
        return true;
    }

    /**
     * 纯逻辑守卫（包级可见，供单测）：非耕地 / 非活物品 / 未种植 / 种子无法归类 → 不画。
     *
     * <p>装饰器按 {@code Item} 注册，会对<b>所有</b> {@code Items.FARMLAND}
     * （含普通非活耕地）调用，守卫必须完整。</p>
     */
    static boolean shouldRenderSeed(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.FARMLAND)) return false;
        if (!LivingItemManager.isLivingItem(stack)) return false;
        FarmlandPlantComponent plant = LivingItemManager.getFarmlandPlant(stack);
        if (!plant.isPlanted() || plant.cropSeed() == null) return false;
        // 种子指向的方块不可解析（模组卸载）→ 不画，与迁入前的容器行为一致
        return CropClassifier.getBlockFromSeed(plant.cropSeed()) != null;
    }
}
