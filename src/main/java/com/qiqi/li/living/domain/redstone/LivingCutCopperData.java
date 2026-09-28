package com.qiqi.li.living.domain.redstone;import net.minecraft.world.item.ItemStack;


import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.transfer.LivingComponents;
import java.util.function.Consumer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;
import com.qiqi.li.living.model.Pos2D;

public record LivingCutCopperData(
    Pos2D inputDir,
    Pos2D outputDir
) implements TooltipProvider {

    public static final LivingCutCopperData DEFAULT = new LivingCutCopperData(Pos2D.DOWN, Pos2D.UP);

    public static final Codec<LivingCutCopperData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Pos2D.CODEC.fieldOf("input_dir").forGetter(LivingCutCopperData::inputDir),
            Pos2D.CODEC.fieldOf("output_dir").forGetter(LivingCutCopperData::outputDir)
        ).apply(instance, LivingCutCopperData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingCutCopperData> STREAM_CODEC = StreamCodec.composite(
        Pos2D.STREAM_CODEC, LivingCutCopperData::inputDir,
        Pos2D.STREAM_CODEC, LivingCutCopperData::outputDir,
        LivingCutCopperData::new
    );

    public LivingCutCopperData withInputDir(Pos2D inputDir) {
        return new LivingCutCopperData(inputDir, outputDir);
    }

    public LivingCutCopperData withOutputDir(Pos2D outputDir) {
        return new LivingCutCopperData(inputDir, outputDir);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }

    /** 读取：缺失返回默认值。（A1 迁移：原 LivingItemManager.getCutCopperData） */
    public static LivingCutCopperData of(ItemStack stack) {
        return LivingItemManager.getData(stack, LivingComponents.LIVING_CUT_COPPER_DATA.value(), LivingCutCopperData.DEFAULT);
    }

    /** 写入：等于默认值时移除组件。（A1 迁移：原 LivingItemManager.setCutCopperData） */
    public static void set(ItemStack stack, LivingCutCopperData data) {
        LivingItemManager.setData(stack, LivingComponents.LIVING_CUT_COPPER_DATA.value(), data, LivingCutCopperData.DEFAULT);
    }
}
