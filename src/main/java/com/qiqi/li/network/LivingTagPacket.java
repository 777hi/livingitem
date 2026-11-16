package com.qiqi.li.network;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.qiqi.li.LivingItem;
import com.qiqi.li.living.api.LivingItemActivation;
import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 活按钮请求；创造模式候选仅在精确有效的玩家背包菜单中临时借入空光标。 */
public record LivingTagPacket(int menuId, @Nullable CompoundTag carriedTag, long requestId)
        implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("living_item", "living_tag");
    public static final CustomPacketPayload.Type<LivingTagPacket> TYPE = new CustomPacketPayload.Type<>(ID);

    public LivingTagPacket() {
        this(-1, null, 0);
    }

    public LivingTagPacket(int menuId, @Nullable CompoundTag carriedTag) {
        this(menuId, carriedTag, 0);
    }

    public static final StreamCodec<FriendlyByteBuf, LivingTagPacket> STREAM_CODEC = StreamCodec.of(
        (buf, packet) -> {
            buf.writeVarInt(packet.menuId());
            buf.writeBoolean(packet.carriedTag() != null);
            if (packet.carriedTag() != null) buf.writeNbt(packet.carriedTag());
            buf.writeLong(packet.requestId());
        },
        buf -> new LivingTagPacket(buf.readVarInt(), buf.readBoolean() ? buf.readNbt() : null, buf.readLong())
    );

    @Override
    public Type<LivingTagPacket> type() {
        return TYPE;
    }

    public static void handle(LivingTagPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                handle(packet, player, context::reply);
            }
        });
    }

    /** 非创造模式只操作真实光标；关联拒绝不修改真实光标，借入后的回包不回滚执行副作用。 */
    static void handle(LivingTagPacket packet, ServerPlayer player, Consumer<CarriedUpdatePacket> reply) {
        AbstractContainerMenu menu = player.containerMenu;
        CompoundTag responseTag = new CompoundTag();
        boolean borrowed = false;
        boolean ordinary = false;
        try {
            boolean creative = player.isCreative();
            if (creative && packet.carriedTag() != null) responseTag = packet.carriedTag().copy();
            if (menu == null || (packet.menuId() != menu.containerId
                    && !(packet.menuId() == -1 && packet.carriedTag() == null
                        && packet.requestId() == 0 && !creative)) || !menu.stillValid(player)) {
                return;
            }

            boolean virtual = creative && packet.carriedTag() != null;
            ItemStack carried;
            if (virtual) {
                if (menu != player.inventoryMenu || packet.menuId() != menu.containerId
                        || packet.requestId() <= 0 || !menu.getCarried().isEmpty()) {
                    return;
                }
                carried = ItemStack.parse(player.registryAccess(), packet.carriedTag()).orElse(ItemStack.EMPTY);
            } else {
                ordinary = true;
                carried = menu.getCarried();
            }
            if (carried.isEmpty()) return;

            boolean currentLiving = LivingItemManager.isLivingItem(carried);
            boolean newLiving = !currentLiving;
            var verdict = LivingItemActivation.evaluate(carried, newLiving, LivingItemActivation.Via.PLAYER);
            if (verdict != LivingItemActivation.Result.ALLOW) {
                player.sendSystemMessage(Component.translatable(
                    "livingitem.activation." + switch (verdict) {
                        case DENY_UNCLAIMED -> "deny_unclaimed";
                        case DENY_RULE -> "deny_rule";
                        case ALLOW -> "";
                    }));
                return;
            }

            if (virtual) {
                borrowed = true;
                menu.setCarried(carried);
            }
            if (!LivingItemActivation.apply(carried, player.level(), player,
                    LivingItemActivation.Via.PLAYER, newLiving)) {
                LivingItem.LOGGER.info("服务端：物品 {} 的 living 标签切换被功能否决",
                    carried.getItem().getName(carried).getString());
                return;
            }
            LivingItem.LOGGER.info("服务端：将物品 {} 的 living 标签从 {} 切换为 {}",
                carried.getItem().getName(carried).getString(), currentLiving, newLiving);
        } catch (Exception exception) {
            LivingItem.LOGGER.error("服务端：活按钮请求处理异常，menuId={}，requestId={}",
                packet.menuId(), packet.requestId(), exception);
        } finally {
            if (borrowed) {
                ItemStack result = ItemStack.EMPTY;
                try {
                    result = menu.getCarried().copy();
                } catch (Exception exception) {
                    LivingItem.LOGGER.error("服务端：读取活按钮借入光标结果失败", exception);
                } finally {
                    try {
                        menu.setCarried(ItemStack.EMPTY);
                    } catch (Exception exception) {
                        LivingItem.LOGGER.error("服务端：清理活按钮借入光标失败", exception);
                    }
                }
                try {
                    responseTag = (CompoundTag) result.saveOptional(player.registryAccess());
                } catch (Exception exception) {
                    // 无法编码的实际结果不能以旧候选替代，避免复制已经结算的内容。
                    responseTag = new CompoundTag();
                    LivingItem.LOGGER.error("服务端：编码活按钮借入光标结果失败", exception);
                }
            } else if (ordinary && packet.requestId() > 0) {
                try {
                    responseTag = (CompoundTag) menu.getCarried().copy().saveOptional(player.registryAccess());
                } catch (Exception exception) {
                    responseTag = new CompoundTag();
                    LivingItem.LOGGER.error("服务端：编码活按钮真实光标结果失败", exception);
                }
            }
            if (packet.requestId() > 0) {
                reply.accept(new CarriedUpdatePacket(responseTag, packet.menuId(), packet.requestId()));
            }
        }
    }
}
