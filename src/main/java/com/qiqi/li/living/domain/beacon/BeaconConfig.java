package com.qiqi.li.living.domain.beacon;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * 活信标效果配置（设计稿 §2.5）—— 固定 5 个矿石效果槽，存 DataComponent。
 *
 * <ul>
 *   <li><b>消耗制</b>：光标拿矿石锭右键活信标 ⇒ 不在配置里 ⇒ 消耗 1 锭追加 I 级槽；
 *       已在配置里 ⇒ 消耗 1 同种锭升 II 级（<b>仅等级 4</b>，2026-10-16 定）。</li>
 *   <li><b>同种锭升级占同一个槽</b>（不新增）⇒ 最多 5 槽 = 5 种效果。</li>
 *   <li><b>跟物品走</b>：移出 / 换槽 / 容器卸载都保留；取消活化 ⇒ 清空（铁律，
 *       {@code getOwnedComponentTypes} 声明）。</li>
 *   <li><b>整摞共享同一配置</b>（写的是同一个 ItemStack）⇒ 永远可堆叠。</li>
 * </ul>
 */
public record BeaconConfig(List<Entry> entries) {

    /** 固定矿石槽上限（原版池除再生外 5 个）。 */
    public static final int MAX_SLOTS = 5;

    public static final BeaconConfig EMPTY = new BeaconConfig(List.of());

    /** 一个矿石槽：矿石种类 + 是否加倍到 II。 */
    public record Entry(OreType ore, boolean amplified) {

        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("ore").forGetter(e -> e.ore().id()),
            Codec.BOOL.fieldOf("amplified").forGetter(Entry::amplified)
        ).apply(instance, (ore, amplified) ->
            new Entry(OreType.byId(ore), amplified)));

        /** ⚠️ 越界 / 非法值回落 {@link OreType#IRON}（脏存档与坏包不许抛，见 {@link OreType#byOrdinal}）。 */
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(OreType::byOrdinal, OreType::ordinal), Entry::ore,
            ByteBufCodecs.BOOL, Entry::amplified,
            Entry::new);
    }

    public static final Codec<BeaconConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Entry.CODEC.listOf().fieldOf("entries").forGetter(BeaconConfig::entries)
    ).apply(instance, BeaconConfig::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, BeaconConfig> STREAM_CODEC = StreamCodec.composite(
        Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), BeaconConfig::entries,
        BeaconConfig::new);

    // ── 查询 ────────────────────────────────────────────────

    public int size() {
        return entries.size();
    }

    public boolean isFull() {
        return entries.size() >= MAX_SLOTS;
    }

    public boolean contains(OreType ore) {
        return entries.stream().anyMatch(e -> e.ore() == ore);
    }

    public boolean isAmplified(OreType ore) {
        return entries.stream().anyMatch(e -> e.ore() == ore && e.amplified());
    }

    // ── 变更（返回新实例）────────────────────────────────────

    /** 追加一个 I 级槽（调用方保证未满且不含该矿石）。 */
    public BeaconConfig add(OreType ore) {
        return new BeaconConfig(
            java.util.stream.Stream.concat(entries.stream(),
                    java.util.stream.Stream.of(new Entry(ore, false)))
                .toList());
    }

    /** 把某矿石槽升到 II 级。 */
    public BeaconConfig amplify(OreType ore) {
        return new BeaconConfig(entries.stream()
            .map(e -> e.ore() == ore ? new Entry(ore, true) : e)
            .toList());
    }

    // ── 组件读写 ─────────────────────────────────────────────

    public static BeaconConfig of(ItemStack stack) {
        BeaconConfig config = stack.get(BeaconComponents.BEACON_CONFIG.value());
        return config != null ? config : EMPTY;
    }

    public static void set(ItemStack stack, BeaconConfig config) {
        if (config == null || config.entries().isEmpty()) {
            stack.remove(BeaconComponents.BEACON_CONFIG.value());
        } else {
            stack.set(BeaconComponents.BEACON_CONFIG.value(), config);
        }
    }
}
