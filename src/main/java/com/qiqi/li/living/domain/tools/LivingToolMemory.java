package com.qiqi.li.living.domain.tools;

import java.util.Optional;
import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * 活工具记忆 —— 玩家操作行为的录制结果（活工具系列的核心持久化数据）。
 *
 * <p>设计要点（详见 {@code docs/idea.md} §3.12 L 组）：
 * <ul>
 *   <li><b>两条独立记忆</b>：{@code dig}（挖掘记忆 = 左键操作）、{@code use}（交互记忆 = 右键操作）。
 *       二者互不干扰，可同时存在，各自只保留<b>最新一条</b>（新记忆覆盖旧记忆）。</li>
 *   <li><b>存的是「操作行为」而非「玩法逻辑」</b>（{@code L36}）：记忆左键/右键这两个动作本身，
 *       而非"挖掘"/"去皮"这类语义。这样回放时走完整的玩家操作链路，
 *       模组工具的自定义效果也能被触发。</li>
 *   <li><b>纯净射线</b>（{@code L15}）：{@code offset} 是「玩家眼睛 → 射线命中点（方块表面）」的
 *       <b>完整偏移向量</b>，不归一化、不加余量、不改用方块中心 ——
 *       保证与玩家 F3 看到的真实射线<b>逐位一致</b>。</li>
 *   <li><b>{@code block} 仅在玩家蹲下时记录</b>（{@code L4}）：null = 不限类型。
 *       记 {@code Block} 而非 {@code BlockState}，好处是去皮/耕地后 Block 改变 →
 *       匹配不上 → <b>天然停止</b>（正好实现 {@code L8} 的终止条件）。</li>
 * </ul>
 *
 * <p>回放时以<b>宿主</b>（容器 / 玩家 / 掉落物）位置为射线起点，加上这条 offset 即得终点（{@code L14}）。
 * 记忆与宿主解耦，所以活工具在不同宿主间迁移后行为一致。</p>
 */
public record LivingToolMemory(
    @Nullable RayMemory dig,
    @Nullable RayMemory use
) {

    public static final LivingToolMemory DEFAULT = new LivingToolMemory(null, null);

    /**
     * 一条射线记忆。
     *
     * @param offset 「录制者眼睛 → 命中点」的偏移向量（含长度与方向，即"这条线本身"）
     * @param block  蹲下时记录的方块类型；null = 不限制类型
     */
    public record RayMemory(Vec3 offset, @Nullable Block block) {

        /** 录制：由眼睛位置与命中点构造（保持原样，不做任何加工）。 */
        public static RayMemory record(Vec3 eyePosition, Vec3 hitLocation, @Nullable Block block) {
            return new RayMemory(hitLocation.subtract(eyePosition), block);
        }

        /** 回放：以任意宿主位置为起点，求射线终点。 */
        public Vec3 endpointFrom(Vec3 origin) {
            return origin.add(offset);
        }

        /** 是否限制目标方块类型。 */
        public boolean restrictsBlock() {
            return block != null;
        }

        /** 目标方块是否匹配（未限制类型时恒真）。 */
        public boolean matches(Block target) {
            return block == null || block == target;
        }
    }

    // ------------------------------------------------------------------
    // Codec（持久化）
    // ------------------------------------------------------------------

    private static final Codec<Block> BLOCK_CODEC = BuiltInRegistries.BLOCK.byNameCodec();

    public static final Codec<RayMemory> RAY_CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Vec3.CODEC.fieldOf("offset").forGetter(RayMemory::offset),
            BLOCK_CODEC.optionalFieldOf("block").forGetter(r -> Optional.ofNullable(r.block()))
        ).apply(instance, (offset, block) -> new RayMemory(offset, block.orElse(null)))
    );

    public static final Codec<LivingToolMemory> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            RAY_CODEC.optionalFieldOf("dig").forGetter(m -> Optional.ofNullable(m.dig)),
            RAY_CODEC.optionalFieldOf("use").forGetter(m -> Optional.ofNullable(m.use))
        ).apply(instance, (dig, use) -> new LivingToolMemory(dig.orElse(null), use.orElse(null)))
    );

    // ------------------------------------------------------------------
    // StreamCodec（网络同步）
    // ------------------------------------------------------------------

    /** Vec3 网络编码：三个 double 原样传输（不归一化，避免精度漂移）。 */
    private static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC3_STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x,
            ByteBufCodecs.DOUBLE, Vec3::y,
            ByteBufCodecs.DOUBLE, Vec3::z,
            Vec3::new
        );

    /** @Nullable Block 的网络编码（存在性标志位 + 注册 id；Optional 桥接 null 语义） */
    private static final StreamCodec<RegistryFriendlyByteBuf, Block> BLOCK_STREAM_CODEC =
        ByteBufCodecs.optional(ByteBufCodecs.registry(Registries.BLOCK))
            .map(LivingToolMemory::blockFromOptional, LivingToolMemory::blockToOptional);

    public static final StreamCodec<RegistryFriendlyByteBuf, RayMemory> RAY_STREAM_CODEC =
        StreamCodec.composite(
            VEC3_STREAM_CODEC, RayMemory::offset,
            BLOCK_STREAM_CODEC, RayMemory::block,
            RayMemory::new
        );

    /** @Nullable RayMemory 的网络编码 */
    private static final StreamCodec<RegistryFriendlyByteBuf, RayMemory> OPTIONAL_RAY_STREAM_CODEC =
        ByteBufCodecs.optional(RAY_STREAM_CODEC)
            .map(LivingToolMemory::rayFromOptional, LivingToolMemory::rayToOptional);

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingToolMemory> STREAM_CODEC =
        StreamCodec.composite(
            OPTIONAL_RAY_STREAM_CODEC, LivingToolMemory::dig,
            OPTIONAL_RAY_STREAM_CODEC, LivingToolMemory::use,
            LivingToolMemory::new
        );

    // ------------------------------------------------------------------
    // Optional <-> @Nullable 桥接
    // ------------------------------------------------------------------

    private static Optional<Block> blockToOptional(@Nullable Block block) {
        return Optional.ofNullable(block);
    }

    @Nullable
    private static Block blockFromOptional(Optional<Block> optional) {
        return optional.orElse(null);
    }

    private static Optional<RayMemory> rayToOptional(@Nullable RayMemory ray) {
        return Optional.ofNullable(ray);
    }

    @Nullable
    private static RayMemory rayFromOptional(Optional<RayMemory> optional) {
        return optional.orElse(null);
    }

    // ------------------------------------------------------------------
    // 访问与不可变更新
    // ------------------------------------------------------------------

    public boolean hasDig() {
        return dig != null;
    }

    public boolean hasUse() {
        return use != null;
    }

    public boolean isEmpty() {
        return dig == null && use == null;
    }

    public LivingToolMemory withDig(@Nullable RayMemory dig) {
        return new LivingToolMemory(dig, use);
    }

    public LivingToolMemory withUse(@Nullable RayMemory use) {
        return new LivingToolMemory(dig, use);
    }

    /** 清除挖掘记忆（左键空气）。 */
    public LivingToolMemory withoutDig() {
        return new LivingToolMemory(null, use);
    }

    /** 清除交互记忆（右键空气）。 */
    public LivingToolMemory withoutUse() {
        return new LivingToolMemory(dig, null);
    }
}
