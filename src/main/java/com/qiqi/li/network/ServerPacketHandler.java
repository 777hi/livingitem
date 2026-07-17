package com.qiqi.li.network;

import com.qiqi.li.living.LivingChestFunction;
import com.qiqi.li.living.LivingHopperFunction;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.model.SlotMapping;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端网络包处理器 —— 处理客户端发送的活物品配置请求。
 *
 * 职责：
 * 1. 验证请求的合法性（玩家状态、物品类型、活物品标识）
 * 2. 委托 LivingHopperFunction 更新活漏斗的传输方向 NBT 数据
 * 3. 同步更新后的数据到客户端
 *
 * 安全验证流程：
 *   1. 解析网络包中的 SlotMapping 数据
 *   2. 验证玩家光标上是否持有活漏斗（containerMenu.getCarried()）
 *   3. 验证光标物品是否为活物品
 *   4. 调用 LivingHopperFunction.updateTransferMapping() 更新方向
 *   5. 通过 ClientboundContainerSetSlotPacket(-1, -1) 同步光标物品到客户端
 *
 * 为什么使用 containerId = -1：
 *   -1 表示光标物品（carried），这是 Minecraft 官方协议中
 *   同步光标物品的标准方式。客户端收到后会更新
 *   containerMenu.getCarried() 的数据。
 */
public class ServerPacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerPacketHandler.class);

    /**
     * 处理活漏斗方向配置请求。
     *
     * 验证流程：
     * 1. 解析 SlotMapping 数据
     * 2. 验证光标物品是否为活漏斗
     * 3. 委托 LivingHopperFunction.updateTransferMapping() 更新 NBT
     * 4. 同步光标物品到客户端
     *
     * @param player 发送请求的服务端玩家
     * @param payload 客户端发送的包数据
     */
    public static void handleHopperDirection(ServerPlayer player, HopperDirectionPacket payload) {
        if (player == null || player.containerMenu == null) return;

        CompoundTag mappingData = payload.mappingData();

        SlotMapping newMapping = SlotMapping.fromNBT(mappingData);
        if (newMapping == null) {
            LOGGER.warn("Invalid mapping data received from player {}", player.getName().getString());
            return;
        }

        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty() || !carried.is(net.minecraft.world.item.Items.HOPPER)) {
            LOGGER.warn("Player {} has no hopper on cursor", player.getName().getString());
            return;
        }

        if (!LivingItemManager.isLivingItem(carried)) {
            LOGGER.warn("Player {} carried item is not living", player.getName().getString());
            return;
        }

        boolean success = LivingHopperFunction.updateTransferMapping(carried, newMapping);
        if (!success) {
            LOGGER.warn("Failed to update mapping for player {}", player.getName().getString());
            return;
        }

        int stateId = player.containerMenu.incrementStateId();
        player.connection.send(new ClientboundContainerSetSlotPacket(
            -1, stateId, -1, carried.copy()));

        LOGGER.debug("Updated hopper direction for player {}: {}",
            player.getName().getString(), newMapping.displayName());
    }

    public static void handleLivingChestAccess(ServerPlayer player, LivingChestAccessPacket packet) {
        if (player == null || player.containerMenu == null) return;

        var server = player.getServer();
        if (server == null) return;

        int action = packet.action();
        switch (action) {
            case LivingChestAccessPacket.LOAD -> {
                sendLivingChestContents(player, server);
            }
            case LivingChestAccessPacket.DEPOSIT -> {
                handleDeposit(player, server, packet);
            }
            case LivingChestAccessPacket.WITHDRAW -> {
                handleWithdraw(player, server, packet);
            }
            case LivingChestAccessPacket.WITHDRAW_INVENTORY -> {
                handleWithdrawToInventory(player, server, packet);
            }
        }
    }

    private static void handleDeposit(ServerPlayer player, MinecraftServer server, LivingChestAccessPacket packet) {
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) return;

        int amount = Math.min(packet.amount(), carried.getCount());
        if (amount <= 0) return;

        ItemStack toInsert = carried.split(amount);
        if (toInsert.isEmpty()) return;

        boolean inserted = false;
        for (ItemStack invStack : player.getInventory().items) {
            if (!LivingChestFunction.isLivingChest(invStack)) continue;
            if (!LivingChestFunction.hasStorage(invStack)) continue;

            if (LivingChestFunction.insertItem(server, invStack, toInsert.copy(), 27)) {
                inserted = true;
                break;
            }
        }

        if (!inserted) {
            carried.grow(toInsert.getCount());
        }

        player.containerMenu.setCarried(carried);
        syncCarriedToClient(player);
        sendLivingChestContents(player, server);
    }

    private static void handleWithdraw(ServerPlayer player, MinecraftServer server, LivingChestAccessPacket packet) {
        if (packet.itemTag() == null) return;
        ItemStack carried = player.containerMenu.getCarried();
        ItemStack target = ItemStack.parse(player.registryAccess(), packet.itemTag()).orElse(ItemStack.EMPTY);
        if (target.isEmpty()) return;

        if (!carried.isEmpty() && !ItemStack.isSameItemSameComponents(carried, target)) {
            return;
        }

        int amount = packet.amount();
        if (amount <= 0) amount = target.getMaxStackSize();

        if (!carried.isEmpty()) {
            amount = Math.min(amount, target.getMaxStackSize() - carried.getCount());
            if (amount <= 0) return;
        }

        ItemStack extracted = ItemStack.EMPTY;
        for (ItemStack invStack : player.getInventory().items) {
            if (!LivingChestFunction.isLivingChest(invStack)) continue;
            if (!LivingChestFunction.hasStorage(invStack)) continue;

            extracted = LivingChestFunction.extractItem(server, invStack, target, amount, 27);
            if (!extracted.isEmpty()) break;
        }

        if (extracted.isEmpty()) return;

        if (carried.isEmpty()) {
            player.containerMenu.setCarried(extracted);
        } else {
            carried.grow(extracted.getCount());
        }

        syncCarriedToClient(player);
        sendLivingChestContents(player, server);
    }

    private static void handleWithdrawToInventory(ServerPlayer player, MinecraftServer server, LivingChestAccessPacket packet) {
        if (packet.itemTag() == null) return;
        ItemStack target = ItemStack.parse(player.registryAccess(), packet.itemTag()).orElse(ItemStack.EMPTY);
        if (target.isEmpty()) return;

        int amount = packet.amount();
        if (amount <= 0) amount = target.getMaxStackSize();

        ItemStack extracted = ItemStack.EMPTY;
        for (ItemStack invStack : player.getInventory().items) {
            if (!LivingChestFunction.isLivingChest(invStack)) continue;
            if (!LivingChestFunction.hasStorage(invStack)) continue;

            extracted = LivingChestFunction.extractItem(server, invStack, target, amount, 27);
            if (!extracted.isEmpty()) break;
        }

        if (extracted.isEmpty()) return;

        player.getInventory().add(extracted);
        player.containerMenu.broadcastChanges();
        sendLivingChestContents(player, server);
    }

    private static void sendLivingChestContents(ServerPlayer player, MinecraftServer server) {
        List<CompoundTag> itemTags = new ArrayList<>();
        for (ItemStack invStack : player.getInventory().items) {
            if (!LivingChestFunction.isLivingChest(invStack)) continue;
            if (!LivingChestFunction.hasStorage(invStack)) continue;

            var merged = LivingChestFunction.getMergedStorage(server, invStack, 27);
            for (ItemStack chestItem : merged) {
                if (!chestItem.isEmpty()) {
                    itemTags.add((CompoundTag) chestItem.save(player.registryAccess()));
                }
            }
        }
        PacketDistributor.sendToPlayer(player, new LivingChestContentsPacket(itemTags));
    }

    private static void syncCarriedToClient(ServerPlayer player) {
        int stateId = player.containerMenu.incrementStateId();
        player.connection.send(new ClientboundContainerSetSlotPacket(
            -1, stateId, -1, player.containerMenu.getCarried().copy()));
    }
}