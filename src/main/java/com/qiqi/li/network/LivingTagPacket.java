package com.qiqi.li.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.qiqi.li.LivingItem;
import com.qiqi.li.living.LivingItemManager;

/**
 * 活物品标签切换网络包。
 *
 * 用途：客户端点击"活物品"按钮时，向服务端发送此包，
 * 切换玩家手中物品的 IS_LIVING 标记。
 *
 * 工作流程：
 * 1. 客户端：玩家在容器界面点击 LivingButton
 * 2. 客户端：发送 LivingTagPacket 到服务端
 * 3. 服务端：收到包后，获取玩家当前手持的物品（menu.getCarried()）
 * 4. 服务端：切换该物品的 IS_LIVING 标记（true ↔ false）
 * 5. 服务端：修改直接作用于 carried ItemStack 引用，自动同步到客户端
 *
 * 为什么不在客户端直接修改：
 *   物品数据在服务端是权威的，客户端的修改会被服务端覆盖。
 *   必须通过网络包让服务端执行修改，再由原版同步机制同步回客户端。
 */
public record LivingTagPacket() implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("living_item", "living_tag");
    public static final CustomPacketPayload.Type<LivingTagPacket> TYPE = new CustomPacketPayload.Type<>(ID);

    /** 无状态包的 StreamCodec —— 只有一个实例，不需要读写任何数据 */
    public static final StreamCodec<FriendlyByteBuf, LivingTagPacket> STREAM_CODEC = StreamCodec.unit(new LivingTagPacket());

    @Override
    public Type<LivingTagPacket> type() {
        return TYPE;
    }

    /**
     * 服务端处理收到的标签切换包。
     *
     * 获取玩家当前手持的物品（carried），切换其 IS_LIVING 标记。
     * carried 是玩家用鼠标拿起但尚未放入槽位的物品，
     * 修改它会直接反映在玩家菜单中，由原版同步机制自动同步到客户端。
     *
     * @param packet 收到的包（无数据）
     * @param context 网络上下文，包含玩家信息
     */
    public static void handle(LivingTagPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ItemStack carriedItem = player.containerMenu.getCarried();

                if (!carriedItem.isEmpty()) {
                    boolean currentLiving = LivingItemManager.isLivingItem(carriedItem);
                    boolean newLiving = !currentLiving;

                    LivingItemManager.setLiving(carriedItem, newLiving);

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