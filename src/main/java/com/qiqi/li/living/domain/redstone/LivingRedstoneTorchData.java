package com.qiqi.li.living.domain.redstone;import net.minecraft.world.item.ItemStack;


import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.transfer.LivingComponents;
import java.util.function.Consumer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;
import com.qiqi.li.living.model.Pos2D;

public record LivingRedstoneTorchData(
    Pos2D direction,
    boolean isLit
) implements TooltipProvider {

    public static final LivingRedstoneTorchData DEFAULT = new LivingRedstoneTorchData(Pos2D.UP, true);

    public static final Codec<LivingRedstoneTorchData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Pos2D.CODEC.fieldOf("direction").forGetter(LivingRedstoneTorchData::direction),
            Codec.BOOL.fieldOf("is_lit").forGetter(LivingRedstoneTorchData::isLit)
        ).apply(instance, LivingRedstoneTorchData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingRedstoneTorchData> STREAM_CODEC = StreamCodec.composite(
        Pos2D.STREAM_CODEC, LivingRedstoneTorchData::direction,
        ByteBufCodecs.BOOL, LivingRedstoneTorchData::isLit,
        LivingRedstoneTorchData::new
    );

    public LivingRedstoneTorchData withDirection(Pos2D dir) {
        return new LivingRedstoneTorchData(dir, isLit);
    }

    public LivingRedstoneTorchData withLit(boolean lit) {
        return new LivingRedstoneTorchData(direction, lit);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }

    /** 读取：缺失返回默认值。（A1 迁移：原 LivingItemManager.getRedstoneTorchData） */
    public static LivingRedstoneTorchData of(ItemStack stack) {
        return LivingItemManager.getData(stack, LivingComponents.LIVING_REDSTONE_TORCH_DATA.value(), LivingRedstoneTorchData.DEFAULT);
    }

    /** 写入：等于默认值时移除组件。（A1 迁移：原 LivingItemManager.setRedstoneTorchData） */
    public static void set(ItemStack stack, LivingRedstoneTorchData data) {
        LivingItemManager.setData(stack, LivingComponents.LIVING_REDSTONE_TORCH_DATA.value(), data, LivingRedstoneTorchData.DEFAULT);
    }
}
