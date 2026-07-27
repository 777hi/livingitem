package com.qiqi.li.living.data;

import java.util.Optional;
import java.util.UUID;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

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

    public EnderChannelData withBoundPlayer(UUID uuid, String name) {
        return new EnderChannelData(Optional.of(uuid.toString()), Optional.of(name));
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