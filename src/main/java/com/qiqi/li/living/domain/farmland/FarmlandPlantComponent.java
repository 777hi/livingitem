package com.qiqi.li.living.domain.farmland;

import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;

/**
 * 活耕地种植数据 —— 作物生长状态 + round-robin 逐项产出的持久化数据源。
 *
 * <p>字段语义（详见 docs/idea.md 活耕地设计）：
 * <ul>
 *   <li>{@code cropSeed}：种植的作物种子，类型标记（V1 语义：种子不消耗，null = 未种植）</li>
 *   <li>{@code age}/{@code maxAge}：生长阶段；maxAge 种植时从作物方块读取并冻结</li>
 *   <li>{@code lastGrowthAttemptTick}：世界轴时间戳（level.getGameTime()），冷却周期判定用——
 *       稳态（未到冷却周期）零组件写入零同步，替代每 tick 累加计数器</li>
 *   <li>{@code outputIndex}/{@code pendingDrops}：成熟后逐项产出。战利品表首次成熟时评估一次
 *       并冻结（避免每周期重掷导致 round-robin 取不稳），全部产出后清空归 -1</li>
 * </ul>
 *
 * <p>注意：本组件<b>不可</b>加入 getIgnoredComponentTypes——age/pendingDrops 是客户端
 * 作物渲染的数据源，进忽略集合会导致组件变化不触发容器同步（大箱快照坑，见
 * tooltip-system.md 坑清单第 3 条）。代价是仅同作物同 age 才能堆叠（一堆 = 一片同步生长的田）。</p>
 */
public record FarmlandPlantComponent(
    @Nullable Item cropSeed,
    int age,
    int maxAge,
    long lastGrowthAttemptTick,
    int outputIndex,
    List<ItemStack> pendingDrops
) implements TooltipProvider {

    public static final FarmlandPlantComponent DEFAULT = new FarmlandPlantComponent(
        null, 0, 0, 0L, -1, List.of());

    public static final Codec<FarmlandPlantComponent> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            BuiltInRegistries.ITEM.byNameCodec().fieldOf("crop_seed")
                .forGetter(FarmlandPlantComponent::cropSeed),
            Codec.INT.fieldOf("age").forGetter(FarmlandPlantComponent::age),
            Codec.INT.fieldOf("max_age").forGetter(FarmlandPlantComponent::maxAge),
            Codec.LONG.fieldOf("last_growth_attempt_tick").forGetter(FarmlandPlantComponent::lastGrowthAttemptTick),
            Codec.INT.fieldOf("output_index").forGetter(FarmlandPlantComponent::outputIndex),
            ItemStack.OPTIONAL_CODEC.listOf().fieldOf("pending_drops")
                .forGetter(FarmlandPlantComponent::pendingDrops)
        ).apply(instance, FarmlandPlantComponent::new)
    );

    /** @Nullable Item 的网络编码（存在性标志位 + 网络 id；Optional 桥接 null 语义） */
    private static final StreamCodec<RegistryFriendlyByteBuf, Item> CROP_SEED_CODEC =
        ByteBufCodecs.optional(ByteBufCodecs.registry(net.minecraft.core.registries.Registries.ITEM))
            .map(FarmlandPlantComponent::fromOptional, FarmlandPlantComponent::toOptional);

    public static final StreamCodec<RegistryFriendlyByteBuf, FarmlandPlantComponent> STREAM_CODEC =
        StreamCodec.composite(
            CROP_SEED_CODEC, FarmlandPlantComponent::cropSeed,
            ByteBufCodecs.VAR_INT, FarmlandPlantComponent::age,
            ByteBufCodecs.VAR_INT, FarmlandPlantComponent::maxAge,
            ByteBufCodecs.VAR_LONG, FarmlandPlantComponent::lastGrowthAttemptTick,
            ByteBufCodecs.VAR_INT, FarmlandPlantComponent::outputIndex,
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()), FarmlandPlantComponent::pendingDrops,
            FarmlandPlantComponent::new
        );

    private static java.util.Optional<Item> toOptional(@Nullable Item item) {
        return java.util.Optional.ofNullable(item);
    }

    @Nullable
    private static Item fromOptional(java.util.Optional<Item> optional) {
        return optional.orElse(null);
    }

    public boolean isPlanted() {
        return cropSeed != null;
    }

    public boolean isMature() {
        return isPlanted() && age >= maxAge;
    }

    public FarmlandPlantComponent withCropSeed(@Nullable Item cropSeed, int maxAge) {
        return new FarmlandPlantComponent(cropSeed, 0, maxAge, 0L, -1, List.of());
    }

    public FarmlandPlantComponent withAge(int age) {
        return new FarmlandPlantComponent(cropSeed, age, maxAge, lastGrowthAttemptTick, outputIndex, pendingDrops);
    }

    /** maxAge 重冻结 + age 钳制（存量数据自愈：注册表修正后旧组件里的过期 maxAge 痊愈） */
    public FarmlandPlantComponent withMaxAge(int newMaxAge) {
        return new FarmlandPlantComponent(cropSeed, Math.min(age, newMaxAge), newMaxAge,
            lastGrowthAttemptTick, outputIndex, pendingDrops);
    }

    public FarmlandPlantComponent withLastGrowthAttemptTick(long tick) {
        return new FarmlandPlantComponent(cropSeed, age, maxAge, tick, outputIndex, pendingDrops);
    }

    public FarmlandPlantComponent withOutput(int outputIndex, List<ItemStack> pendingDrops) {
        return new FarmlandPlantComponent(cropSeed, age, maxAge, lastGrowthAttemptTick, outputIndex, pendingDrops);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }
}
