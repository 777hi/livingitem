package com.qiqi.li.living.domain.ender;

import java.util.Optional;
import java.util.UUID;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 活末影箱的频道配置 —— 绑定 UUID（配置数据）+ 绑定玩家名（<b>显示缓存</b>）。
 *
 * <p>⚠️ 两个字段的语义<b>不对等</b>：绑定关系只看 UUID；名字是<b>衍生显示数据</b>，
 * 专治「专用服务器上主人离线、客户端实时解析失败」的 tooltip 显示 ——
 * 服务端在能确认名字时（绑定时 / tick 遇到在线绑定时）刷新它，
 * 陈旧只是显示旧名、不会显示错人，故允许陈旧（2026-09-30 定）。</p>
 *
 * <p>📌 演进留痕：曾先后走过「名字快照当绑定数据」（改名陈旧）与「彻底删名字字段」
 * （离线时显示无意义的短 UUID）两个极端，最终定为 UUID + 显示缓存的双层。</p>
 */
public record EnderChannelData(Optional<String> boundPlayerUuid, Optional<String> boundPlayerName) {

    public static final EnderChannelData EMPTY = new EnderChannelData(Optional.empty(), Optional.empty());

    public static final Codec<EnderChannelData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.STRING.optionalFieldOf("bound_player_uuid").forGetter(EnderChannelData::boundPlayerUuid),
            Codec.STRING.optionalFieldOf("bound_player_name").forGetter(EnderChannelData::boundPlayerName)
        ).apply(instance, EnderChannelData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, EnderChannelData> STREAM_CODEC = StreamCodec.composite(
        net.minecraft.network.codec.ByteBufCodecs.optional(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8), EnderChannelData::boundPlayerUuid,
        net.minecraft.network.codec.ByteBufCodecs.optional(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8), EnderChannelData::boundPlayerName,
        EnderChannelData::new
    );

    public EnderChannelData withBoundPlayer(UUID uuid, String nameCache) {
        return new EnderChannelData(Optional.of(uuid.toString()), Optional.of(nameCache));
    }

    /** 只刷新显示缓存（绑定 UUID 不动）—— 给 tick 里「遇到在线绑定玩家」的刷新用。 */
    public EnderChannelData withPlayerNameCache(String nameCache) {
        if (boundPlayerUuid.isEmpty()) {
            return this;   // 未绑定却来了缓存名 —— 数据自相矛盾，拒绝
        }
        return new EnderChannelData(boundPlayerUuid, Optional.of(nameCache));
    }

    public EnderChannelData clearBoundPlayer() {
        return EMPTY;
    }

    public boolean hasBoundPlayer() {
        return boundPlayerUuid.isPresent();
    }

    public Optional<UUID> getPlayerUuid() {
        return boundPlayerUuid.map(UUID::fromString);
    }
}
