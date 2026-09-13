package com.qiqi.li.living.domain.farmland;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.neoforged.neoforge.common.Tags;

/**
 * 活耕地作物分类器 —— 种植准入、类型判定、maxAge 读取的唯一定义点。
 *
 * <p>种植准入三层（白名单 + 标签 + 手动映射），详见 docs/idea.md。
 * 白名单只认 Block 类型：1.21.1 中 FlowerBlock/SaplingBlock/DeadBushBlock/TallGrassBlock
 * 全部继承 BushBlock，直接 {@code instanceof BushBlock} 会把花/树苗/草全判成作物，
 * 故第 2 层不用 BushBlock 兜底。模组作物依赖 c:seeds 标签（c:crops 混有仙人掌/
 * 可可豆等非耕地作物，不用）。</p>
 */
public final class CropClassifier {

    private CropClassifier() {}

    /** 模组种子标签（覆盖遵循 NeoForge 通用标签约定的模组作物） */
    private static final TagKey<Item> SEEDS_TAG = Tags.Items.SEEDS;

    /**
     * 手动映射表：非 BlockItem 的原版种子 → 作物方块。
     * 甜浆果（SWEET_BERRIES）在原版没有方块物品形态，三层判定全漏，靠这里兜住。
     */
    private static final Map<Item, Block> MANUAL_SEEDS = Map.of(
        Items.SWEET_BERRIES, Blocks.SWEET_BERRY_BUSH
    );

    /** 已知不符合耕地语义的 c:seeds 成员（预留给需要收紧时使用） */
    private static final Map<Item, Block> DYNAMIC_MANUAL_SEEDS = new HashMap<>();

    /** 可种植性判定：给 PlantCropHandler 调用 */
    public static boolean isSeedPlantableOnFarmland(ItemStack stack) {
        Block block = getBlockFromSeed(stack);
        if (block != null && isKnownCropBlock(block)) {
            return true;
        }
        return stack.is(SEEDS_TAG);
    }

    /** 种子 → 作物方块（BlockItem 直接取，手动映射兜底非 BlockItem 种子） */
    @Nullable
    public static Block getBlockFromSeed(ItemStack seedStack) {
        return getBlockFromSeed(seedStack.getItem());
    }

    @Nullable
    public static Block getBlockFromSeed(Item seed) {
        if (MANUAL_SEEDS.containsKey(seed)) return MANUAL_SEEDS.get(seed);
        if (DYNAMIC_MANUAL_SEEDS.containsKey(seed)) return DYNAMIC_MANUAL_SEEDS.get(seed);
        if (seed instanceof BlockItem bi) return bi.getBlock();
        return null;
    }

    /** Block 层白名单：原版全部作物类型（不含 BushBlock 兜底，理由见类 javadoc） */
    public static boolean isKnownCropBlock(Block block) {
        return block instanceof CropBlock
            || block instanceof StemBlock
            || block instanceof NetherWartBlock
            || block instanceof SweetBerryBushBlock;
    }

    /** 茎作物判定（西瓜/南瓜） */
    public static boolean isStemCrop(Block block) {
        return block instanceof StemBlock;
    }

    /**
     * 采后回退点（产完重置到的 age）：
     * 浆果丛对齐原版采摘语义（SweetBerryBushBlock 采后回 age=1，保留 2/3 进度，
     * 随机刻/概率 tick 从 1 长回 3 再产）；其余作物回 0 重新长。
     */
    public static int getHarvestResetAge(Block block) {
        return block instanceof SweetBerryBushBlock ? 1 : 0;
    }

    /**
     * 作物最大生长阶段（种植时读取一次冻结进组件）。
     * 勘误：StemBlock/NetherWartBlock/SweetBerryBushBlock 的最大阶段是公开常量 MAX_AGE，
     * 没有 getMaxAge() 方法；只有 CropBlock 有。
     */
    public static int getMaxAge(Block block) {
        if (block instanceof CropBlock crop) return crop.getMaxAge();
        if (block instanceof StemBlock) return StemBlock.MAX_AGE;
        if (block instanceof NetherWartBlock) return NetherWartBlock.MAX_AGE;
        if (block instanceof SweetBerryBushBlock) return SweetBerryBushBlock.MAX_AGE;
        return CropBlock.MAX_AGE;
    }

    /**
     * 茎作物的果实方块（产出来源 + 成熟渲染），需 AT：StemBlock.fruit。
     * 非 StemBlock 或果实未注册（模组方块卸载）返回 null。
     */
    @Nullable
    public static Block getStemFruit(StemBlock stem) {
        ResourceKey<Block> fruitKey = stem.fruit;
        return BuiltInRegistries.BLOCK.get(fruitKey);
    }

    /** 手动注册扩展点（未来公开 API 的雏形） */
    public static void registerManualSeed(Item seed, Block cropBlock) {
        DYNAMIC_MANUAL_SEEDS.put(seed, cropBlock);
    }
}
