package com.qiqi.li.living.domain.brewing;

import java.util.function.Consumer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;

/**
 * 活酿造台的数据组件 —— 只保存<b>持久状态</b>。
 *
 * <p>当前只有「槽位方向」。以下两类<b>不进本组件</b>：</p>
 * <ul>
 *   <li><b>瞬态数据</b>（酿造进度、当前材料）→ 运行时缓存（不影响物品堆叠，同活熔炉）；</li>
 *   <li><b>燃料次数</b>（D2 定案：对齐原版「烈焰粉 = 20 次」<b>并落盘</b>）→ 第二段加入本组件。
 *       与熔炉的时间型燃料不同，它 20 秒才变一次，落盘没有负担；但代价是
 *       {@code fuel=20} 与 {@code fuel=19} 的堆叠不能互相合并（忠实反映真实状态，不算缺陷）。</li>
 * </ul>
 */
public record LivingBrewingStandData(BrewingDirectionData direction, int fuel) implements TooltipProvider {

    public static final LivingBrewingStandData DEFAULT =
        new LivingBrewingStandData(BrewingDirectionData.DEFAULT_BREWING, 0);

    /**
     * {@code fuel} 用 {@code optionalFieldOf} ⇒ <b>旧数据（只有 direction 字段）仍可解码</b>
     * （第一段落地过、可能已写进存档）。
     */
    public static final Codec<LivingBrewingStandData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            BrewingDirectionData.CODEC.fieldOf("direction").forGetter(LivingBrewingStandData::direction),
            Codec.INT.optionalFieldOf("fuel", 0).forGetter(LivingBrewingStandData::fuel)
        ).apply(instance, LivingBrewingStandData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingBrewingStandData> STREAM_CODEC =
        StreamCodec.composite(
            BrewingDirectionData.STREAM_CODEC, LivingBrewingStandData::direction,
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT, LivingBrewingStandData::fuel,
            LivingBrewingStandData::new
        );

    public LivingBrewingStandData withDirection(BrewingDirectionData d) {
        return new LivingBrewingStandData(d, fuel);
    }

    /** 燃料次数（0..{@code BrewingBatchLogic.FUEL_USES}）。 */
    public LivingBrewingStandData withFuel(int f) {
        return new LivingBrewingStandData(direction, f);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {}

    /** 读取：缺失返回默认值（默认布局已含 5 个槽位，故未配置时 HUD 直接显示默认方向）。 */
    public static LivingBrewingStandData of(ItemStack stack) {
        return LivingItemManager.getData(stack, BrewingStandComponents.LIVING_BREWING_STAND_DATA.value(), DEFAULT);
    }

    /** 写入：等于默认值时移除组件。 */
    public static void set(ItemStack stack, LivingBrewingStandData data) {
        LivingItemManager.setData(stack, BrewingStandComponents.LIVING_BREWING_STAND_DATA.value(), data, DEFAULT);
    }
}
