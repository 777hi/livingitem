package com.qiqi.li.network;

import com.qiqi.li.living.interaction.InteractionHandler;
import com.qiqi.li.living.interaction.InteractionRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.qiqi.li.living.api.LivingItemManager;

import javax.annotation.Nullable;

/**
 * 通用GUI交互网络包，支持所有活物品的GUI交互。
 *
 * 字段说明：
 *   - slotIndex：客户端菜单槽位索引（用于直接查找）
 *   - containerSlot：容器内实际槽位索引（用于回退查找）
 *   - actionId：交互动作ID（如 "ignite"），服务端通过此ID查找处理器
 *   - carriedTag：光标物品的NBT数据（创造模式下客户端发送，服务端用于恢复光标物品）
 *
 * 创造模式光标物品问题：
 *   创造模式使用 ItemPickerMenu，光标物品是客户端虚拟的，
 *   服务端 menu.getCarried() 返回空。
 *   当交互需要修改光标物品时（如 ignite_carried），
 *   客户端通过 carriedTag 将光标物品数据发送到服务端，
 *   服务端恢复后正常处理，处理完再同步回客户端。
 *
 * 服务端处理流程：
 *   1. 如果 carriedTag 非空且玩家为创造模式，将光标物品设置到服务端菜单
 *   2. 通过 slotIndex 和 containerSlot 定位目标槽位
 *   3. 通过 actionId 查找注册的 InteractionHandler
 *   4. 调用 handler.handle(player, targetSlot)
 *   5. 如果光标物品被修改，同步回客户端
 */
public record GuiInteractionPacket(
    int slotIndex,
    int containerSlot,
    String actionId,
    @Nullable CompoundTag carriedTag
) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("living_item", "gui_interaction");
    public static final CustomPacketPayload.Type<GuiInteractionPacket> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, GuiInteractionPacket> STREAM_CODEC = StreamCodec.of(
        GuiInteractionPacket::encode,
        GuiInteractionPacket::decode
    );

    private static void encode(FriendlyByteBuf buf, GuiInteractionPacket pkt) {
        buf.writeVarInt(pkt.slotIndex);
        buf.writeVarInt(pkt.containerSlot);
        buf.writeUtf(pkt.actionId);
        if (pkt.carriedTag != null) {
            buf.writeBoolean(true);
            buf.writeNbt(pkt.carriedTag);
        } else {
            buf.writeBoolean(false);
        }
    }

    private static GuiInteractionPacket decode(FriendlyByteBuf buf) {
        int slotIndex = buf.readVarInt();
        int containerSlot = buf.readVarInt();
        String actionId = buf.readUtf();
        CompoundTag carriedTag = null;
        if (buf.readBoolean()) {
            carriedTag = buf.readNbt();
        }
        return new GuiInteractionPacket(slotIndex, containerSlot, actionId, carriedTag);
    }

    @Override
    public Type<GuiInteractionPacket> type() {
        return TYPE;
    }

    public static void handle(GuiInteractionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            AbstractContainerMenu menu = player.containerMenu;

            boolean carriedRestored = false;
            if (player.isCreative() && packet.carriedTag() != null) {
                ItemStack carried = ItemStack.parse(player.registryAccess(), packet.carriedTag())
                    .orElse(ItemStack.EMPTY);
                if (!carried.isEmpty()) {
                    menu.setCarried(carried);
                    carriedRestored = true;
                }
            }

            Slot targetSlot = resolveSlot(menu, packet.slotIndex(), packet.containerSlot(),
                slot -> isAcceptable(player, slot));
            if (targetSlot == null) {
                if (carriedRestored) menu.setCarried(ItemStack.EMPTY);
                return;
            }

            InteractionHandler handler = InteractionRegistry.getHandler(packet.actionId());
            if (handler == null) {
                if (carriedRestored) menu.setCarried(ItemStack.EMPTY);
                return;
            }

            handler.handle(player, targetSlot);

            if (carriedRestored) {
                ItemStack modifiedCarried = menu.getCarried().copy();
                menu.setCarried(ItemStack.EMPTY);

                // 使用自定义包同步光标物品，绕过原版对 CreativeModeInventoryScreen 的排除
                // 原版 ClientboundContainerSetSlotPacket(-1, -1) 在创造模式下被忽略
                CompoundTag carriedSyncTag = (CompoundTag) modifiedCarried.saveOptional(player.registryAccess());
                PacketDistributor.sendToPlayer(player, new CarriedUpdatePacket(carriedSyncTag));
            }

            menu.broadcastChanges();
        });
    }

    /**
     * 定位目标槽位，兼容创造模式的索引差异：
     * 优先按 {@code slotIndex} 直查，被谓词否决则按 {@code containerSlot} 遍历回退。
     *
     * <p>包级可见 + 谓词可注入：纯排序逻辑可直接单测（无需 mock 菜单/玩家）。</p>
     */
    static Slot resolveSlot(AbstractContainerMenu menu, int slotIndex, int containerSlot,
                            java.util.function.Predicate<Slot> acceptable) {
        if (slotIndex >= 0 && slotIndex < menu.slots.size()) {
            Slot directSlot = menu.getSlot(slotIndex);
            if (acceptable.test(directSlot)) return directSlot;
        }

        for (Slot s : menu.slots) {
            if (s.getContainerSlot() == containerSlot && acceptable.test(s)) {
                return s;
            }
        }

        return null;
    }

    /**
     * 目标槽是否可接受：持活物品（点火 / 施肥…），或**能解析成活容器的空槽**（活桶汲 / 倒）。
     *
     * <p>⚠️ <b>2026-10-06（创造模式背包修复）</b>：空槽必须落在「能解析出容器上下文」的槽位上。
     * 创造模式的玩家背包界面客户端是 {@code ItemPickerMenu}、服务端仍是 {@code InventoryMenu}，
     * <b>两者槽位索引不同</b> ⇒ 索引直查可能落到「恰好也是空槽」的无关槽位（如合成结果槽），
     * 后果是倒水<b>静默失败</b>（解析不出容器 ⇒ 无处注册源）。加上这道门后，索引直查被否决，
     * 自动回退到按 {@code containerSlot} 精确定位（客户端已解包 {@code SlotWrapper}）。</p>
     */
    private static boolean isAcceptable(ServerPlayer player, Slot slot) {
        if (!isValidTarget(slot.getItem())) return false;
        if (!slot.getItem().isEmpty()) return true;   // 活物品目标：老口径不变
        return com.qiqi.li.living.container.ContainerContexts.resolve(player, slot) != null;
    }

    /**
     * 目标槽是否可解析：<b>持活物品</b>（点火 / 施肥等既有交互），或 <b>空槽</b>。
     *
     * <p>⚠️ <b>空槽必须放行</b>（2026-10-04 修复）：活桶汲/倒的目标就是<b>空槽</b>
     * （倒水要求目标格无物品、汲水的源格也无物品），此前只认活物品 ⇒ 汲/倒包一律被丢，
     * 表现即「活水桶右键没反应」。</p>
     *
     * <p>放行空槽是安全的：非活桶动作只在客户端 {@code InteractionEntry.matchesTarget} 命中时才发包，
     * 而它对空槽恒 {@code false} ⇒ 空槽只会由活桶分支送到这里。</p>
     *
     * <p>包级可见：{@code GuiInteractionPacketTest} 直接驱动（纯判据，无需 mock 菜单）。</p>
     */
    static boolean isValidTarget(ItemStack stack) {
        return stack.isEmpty() || LivingItemManager.isLivingItem(stack);
    }
}