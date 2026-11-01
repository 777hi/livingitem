package com.qiqi.li.living.domain.sponge;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.logging.ModLog;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 活海绵所存流体（设计稿 docs/buffer/活海绵.md §2.1）。
 *
 * <p>按「每枚」定点存：{@code mmB = 1/1000 mB}（均分守恒需要细于外部接口的量子，
 * 灯泡 mFE 同款机制）。同栈共享同一份组件，堆总量 = mmB × count；
 * 拆分时组件被复制、每枚各带 mmB，总量守恒是恒等式（任意拆法零损益）。</p>
 *
 * <p>干/湿不变量：{@code mmB > 0 ⇔ fluid != null}（湿态），否则归一为干态
 * —— 存量等于默认值时组件被移除，干海绵正常堆叠（灯泡同款语义）。
 * 不换物品 id：湿/干只是组件派生态，图标层负责湿海绵纹理变体（设计稿 §7）。</p>
 *
 * <p>容量 = 每枚 64,000 mB = 64 源当量（储量锚，设计稿 §1 / ①）。</p>
 */
public record LivingSpongeData(
    FluidType fluid,
    long mmB
) {

    /** 每枚容量（mmB）= 64,000 mB。 */
    public static final long MAX_MM_B = 64_000L * 1_000L;

    public static final LivingSpongeData DEFAULT = new LivingSpongeData(null, 0);

    /** 干/湿不变量：无流体或非正存量 ⇒ 归一为干态（record 紧凑构造器归一）。 */
    public LivingSpongeData {
        if (fluid == null || mmB <= 0) {
            fluid = null;
            mmB = 0;
        }
    }

    public static final Codec<LivingSpongeData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.STRING.fieldOf("fluid").forGetter(LivingSpongeData::fluidIdOrEmpty),
            Codec.LONG.fieldOf("mm_b").forGetter(LivingSpongeData::mmB)
        ).apply(instance, LivingSpongeData::decode)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingSpongeData> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, LivingSpongeData::fluidIdOrEmpty,
            ByteBufCodecs.VAR_LONG, LivingSpongeData::mmB,
            LivingSpongeData::decode
        );

    /** 是否湿态（组件在位且存量 > 0）。 */
    public boolean isWet() {
        return mmB > 0;
    }

    public LivingSpongeData withFluid(FluidType fluid) {
        return new LivingSpongeData(fluid, mmB);
    }

    public LivingSpongeData withMmB(long mmB) {
        return new LivingSpongeData(fluid, mmB);
    }

    /** 该堆总量（mmB）= 每枚 × 数量（账本 long：6.4e7 × 64 ≈ 4.1e9 > int，§6）。 */
    public long totalMmB(int count) {
        return mmB * (long) count;
    }

    /** 该堆总容量（mmB）= 每枚容量 × 数量（线性，设计稿 §3）。 */
    public static long totalCapacityMmB(int count) {
        return MAX_MM_B * (long) count;
    }

    private String fluidIdOrEmpty() {
        return fluidId(fluid);
    }

    private static LivingSpongeData decode(String fluidId, long mmB) {
        FluidType fluid = fluidId.isEmpty() ? null : resolveFluidType(fluidId);
        if (fluid == null && mmB > 0) {
            // ⚠️ 降级留痕（I-C6 同款）：未知流体 id（提供该流体的模组被移除后读旧档 / 坏包）
            //    静默带湿 = 幽灵湿海绵；宁损勿鬼 —— 归干态（损耗向）。
            ModLog.CONTAINER.warn("[活海绵] 落盘/同步读到未知流体 id，归干态：id={} mmB={}", fluidId, mmB);
            return DEFAULT;
        }
        return new LivingSpongeData(fluid, mmB);
    }

    private static String fluidId(FluidType fluid) {
        if (fluid == null) return "";
        ResourceLocation key = NeoForgeRegistries.FLUID_TYPES.getKey(fluid);
        return key == null ? "" : key.toString();
    }

    /** 流体类型注册 id → {@link FluidType}；未知 id 返回 {@code null}（ContainerFluidData 同款）。 */
    private static FluidType resolveFluidType(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : NeoForgeRegistries.FLUID_TYPES.getOptional(rl).orElse(null);
    }

    /** 读取：缺失返回默认值（干态）。 */
    public static LivingSpongeData of(ItemStack stack) {
        return LivingItemManager.getData(stack, SpongeComponents.LIVING_SPONGE_DATA.value(), DEFAULT);
    }

    /** 写入：等于默认值时移除组件（干海绵正常堆叠）。 */
    public static void set(ItemStack stack, LivingSpongeData data) {
        LivingItemManager.setData(stack, SpongeComponents.LIVING_SPONGE_DATA.value(), data, DEFAULT);
    }
}
