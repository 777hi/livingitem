package com.qiqi.li.client.render;

import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import com.qiqi.li.living.domain.farmland.CropClassifier;

/**
 * 作物生长阶段纹理解析器（客户端）。
 *
 * <p>主路径：blockstate → 模型 → 粒子图标查表（原版 GUI 同款链路）。
 * 不硬推导纹理路径——胡萝卜 blockstate 是 age 0~7 映射 4 个模型、下界疣
 * age1/2 共用 stage1，路径推导会拿到不存在的纹理；查 blockstate 的
 * 「状态 → 模型」权威映射天然覆盖，且自动覆盖染色（crop 模型 tint）。</p>
 *
 * <p>茎作物成熟态切换为果实方块的图标（stem.fruit AT 读取，与服务端产出同源）。</p>
 */
public final class CropTextureResolver {

    private CropTextureResolver() {}

    /**
     * 获取作物指定生长阶段的精灵图。
     *
     * @param cropBlock 作物方块（种植时冻结的类型，从 cropSeed 推断）
     * @param age       当前生长阶段（作物自身 AGE 值域）
     * @return 精灵图；解析失败返回 null（调用方跳过渲染）
     */
    @Nullable
    public static TextureAtlasSprite getCropSprite(Block cropBlock, int age) {
        // 茎作物成熟 → 渲染果实方块图标（与服务端产出来源一致）
        if (cropBlock instanceof StemBlock stem && age >= StemBlock.MAX_AGE) {
            Block fruit = CropClassifier.getStemFruit(stem);
            if (fruit != null) {
                TextureAtlasSprite fruitSprite = getBlockSprite(fruit.defaultBlockState());
                if (fruitSprite != null) return fruitSprite;
            }
        }

        BlockState state = stateForAge(cropBlock, age);
        if (state != null) {
            TextureAtlasSprite sprite = getBlockSprite(state);
            if (sprite != null) return sprite;
        }

        // 兜底：成熟最终形态的 BlockItem 物品图标（非标准作物、缺 age 变体模型）
        var item = cropBlock.asItem();
        if (item == null || item == net.minecraft.world.item.Items.AIR) return null;
        BakedModel model = Minecraft.getInstance().getItemRenderer().getModel(
            new net.minecraft.world.item.ItemStack(item), null, null, 0);
        TextureAtlasSprite sprite = model.getParticleIcon();
        return isMissing(sprite) ? null : sprite;
    }

    @Nullable
    private static TextureAtlasSprite getBlockSprite(BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        BakedModel model = mc.getBlockRenderer().getBlockModelShaper().getBlockModel(state);
        TextureAtlasSprite sprite = model.getParticleIcon();
        return isMissing(sprite) ? null : sprite;
    }

    /** TextureAtlasSprite 无 isMissing()——与缺失精灵的资源位置比较判定 */
    private static boolean isMissing(TextureAtlasSprite sprite) {
        return sprite.contents().name().equals(net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation());
    }

    /** 构造该 age 的 BlockState（值域越界返回 null） */
    @Nullable
    public static BlockState stateForAge(Block block, int age) {
        IntegerProperty ageProp = agePropertyOf(block);
        if (ageProp == null) return null;
        if (!ageProp.getPossibleValues().contains(age)) return null;
        return block.defaultBlockState().setValue(ageProp, age);
    }

    /**
     * 取作物的 AGE 属性。CropBlock.getAgeProperty() 是 protected——
     * 统一从 defaultBlockState 的属性表里按名字取（属性名就是 "age"），
     * 同时覆盖 CropBlock/NetherWart/SweetBerry/Stem 四类。
     */
    @Nullable
    public static IntegerProperty agePropertyOf(Block block) {
        for (var prop : block.defaultBlockState().getProperties()) {
            if (prop instanceof IntegerProperty intProp && prop.getName().equals("age")) {
                return intProp;
            }
        }
        return null;
    }

    /**
     * 获取作物在指定 age 的原版染色（-1 = 不染色）。
     *
     * <p>南瓜/西瓜的藤蔓纹理是<b>灰度图</b>，原版在世界渲染时靠 BlockColors 按
     * age 染色（age 0 纯绿 → age 7 橙黄，`ARGB32.color(age*32, 255-age*8, age*4)`，
     * 纯 age 驱动、忽略 level/pos——null 传入即可正确取色）；直接 blit 精灵图
     * 不染色会发白（2026-09-13 实测踩坑）。小麦/胡萝卜等是预着色纹理、无注册 → -1。
     * 茎作物的成熟果实图标走果实方块（预着色）→ 不染色。</p>
     */
    public static int getCropTint(Block cropBlock, int age) {
        if (cropBlock instanceof StemBlock && age >= StemBlock.MAX_AGE) return -1;   // 果实图标不染色
        BlockState state = stateForAge(cropBlock, age);
        if (state == null) return -1;
        // 4 参重载：无注册返回 -1；注册了 age 驱动染色器的茎方块忽略 null level/pos
        return Minecraft.getInstance().getBlockColors().getColor(state, null, null, 0);
    }

    /**
     * 获取物品图标的精灵图（item/generated 模型的粒子图标 = layer0 纹理）。
     * 用于槽位叠加层 blit（立即模式，绕开 renderItem 的缓冲排序/深度竞争）。
     */
    @Nullable
    public static TextureAtlasSprite getItemSprite(net.minecraft.world.item.ItemStack stack) {
        BakedModel model = Minecraft.getInstance().getItemRenderer()
            .getModel(stack, null, null, 0);
        TextureAtlasSprite sprite = model.getParticleIcon();
        return isMissing(sprite) ? null : sprite;
    }
}
