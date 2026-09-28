package com.qiqi.li.client.render;

import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.item.ItemStack;

/**
 * 物品图标精灵图解析器（客户端）——取 item/generated 模型的粒子图标（layer0 纹理），
 * 供槽位叠加层绘制使用。
 *
 * <p>历史注记：本类曾承载「作物阶段贴图 blockstate→模型查表 + 灰度茎手动 age 染色」
 * 的完整管线（粒子图标法）。作物渲染已切换为世界级管线直绘
 * {@code BlockRenderDispatcher.renderSingleBlock}（染色/逐阶段几何/任意模组模型
 * 全自动，见 living-farmland-tech.md §8），本类只保留物品图标解析这一个职责。</p>
 *
 * <p>调用方：{@link LivingFarmlandSeedDecorator}（活耕地种子图标叠加层）。
 * 2026-09-16 前它由 {@code AbstractContainerScreenMixin.render @TAIL} 以「立即模式
 * blit + disableDepthTest」调用，因依赖 {@code leftPos/topPos} 而**在 HUD 快捷栏失效**；
 * 现走 {@code IItemDecorator} 路径（{@code renderItemDecorations} 末尾分发），
 * 快捷栏/容器 GUI/创造物品栏共用。</p>
 */
public final class CropTextureResolver {

    private CropTextureResolver() {}

    /**
     * 获取物品图标的精灵图（item/generated 模型的粒子图标 = layer0 纹理）。
     *
     * @return 精灵图；解析失败返回 null（调用方跳过渲染）
     */
    @Nullable
    public static TextureAtlasSprite getItemSprite(ItemStack stack) {
        BakedModel model = Minecraft.getInstance().getItemRenderer()
            .getModel(stack, null, null, 0);
        TextureAtlasSprite sprite = model.getParticleIcon();
        // TextureAtlasSprite 无 isMissing()——与缺失精灵的资源位置比较判定
        return sprite.contents().name()
            .equals(net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation())
            ? null : sprite;
    }
}
