package com.qiqi.li.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.qiqi.li.LivingItem;

public record LivingTagPacket() implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("living_item", "living_tag");
    public static final CustomPacketPayload.Type<LivingTagPacket> TYPE = new CustomPacketPayload.Type<>(ID);
    public static final StreamCodec<FriendlyByteBuf, LivingTagPacket> STREAM_CODEC = StreamCodec.unit(new LivingTagPacket());

    @Override
    public Type<LivingTagPacket> type() {
        return TYPE;
    }

    public static void handle(LivingTagPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                // 获取玩家当前拿着的物品（悬浮在鼠标上的物品）
                ItemStack carriedItem = player.containerMenu.getCarried();

                if (!carriedItem.isEmpty()) {
                    // 切换 living tag 状态
                    CustomData existingData = carriedItem.get(DataComponents.CUSTOM_DATA);
                    CompoundTag tag = existingData != null ? existingData.copyTag() : new CompoundTag();

                    boolean currentLiving = tag.getBoolean("living");
                    boolean newLiving = !currentLiving;  // 切换状态
                    tag.putBoolean("living", newLiving);
                    carriedItem.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

                    LivingItem.LOGGER.info("服务端：将物品 {} 的 living 标签从 {} 切换为 {}",
                            carriedItem.getItem().getName(carriedItem).getString(),
                            currentLiving,
                            newLiving);
                } else {
                    LivingItem.LOGGER.info("服务端：玩家没有拿着任何物品");
                }
            }
        });
    }
}