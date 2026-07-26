package com.qiqi.li.network;

import com.qiqi.li.living.function.LivingChestFunction;
import com.qiqi.li.living.function.LivingFurnaceFunction;
import com.qiqi.li.living.function.LivingHopperFunction;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.model.Pos2D;
import com.qiqi.li.living.core.model.SlotMapping;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerPacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerPacketHandler.class);

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

    public static void handleSlotDirection(ServerPlayer player, SlotDirectionPacket payload) {
        if (player == null || player.containerMenu == null) return;

        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty() || !carried.is(net.minecraft.world.item.Items.FURNACE)) {
            LOGGER.warn("Player {} has no furnace on cursor", player.getName().getString());
            return;
        }

        if (!LivingItemManager.isLivingItem(carried)) {
            LOGGER.warn("Player {} carried item is not living", player.getName().getString());
            return;
        }

        Pos2D direction = new Pos2D(payload.directionX(), payload.directionY());
        boolean success = LivingFurnaceFunction.updateSlotDirection(
            carried, payload.slotName(), direction);

        if (!success) {
            LOGGER.warn("Failed to update slot direction for player {}: slot={} dir={}",
                player.getName().getString(), payload.slotName(), direction);
            return;
        }

        int stateId = player.containerMenu.incrementStateId();
        player.connection.send(new ClientboundContainerSetSlotPacket(
            -1, stateId, -1, carried.copy()));

        LOGGER.debug("Updated furnace slot direction for player {}: {} = {}",
            player.getName().getString(), payload.slotName(), direction.getSymbol());
    }

    public static void handleLivingChestAccess(ServerPlayer player, LivingChestAccessPacket packet) {
        if (player == null || player.containerMenu == null) return;

        int action = packet.action();
        switch (action) {
            case LivingChestAccessPacket.DEPOSIT -> {
                handleDeposit(player, packet);
            }
            case LivingChestAccessPacket.WITHDRAW -> {
                handleWithdraw(player, packet);
            }
            case LivingChestAccessPacket.WITHDRAW_INVENTORY -> {
                handleWithdrawToInventory(player, packet);
            }
            case LivingChestAccessPacket.DEPOSIT_SLOT -> {
                handleDepositFromSlot(player, packet);
            }
        }
    }

    private static void handleDeposit(ServerPlayer player, LivingChestAccessPacket packet) {
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
            if (!LivingChestFunction.canInsert(invStack, toInsert, player.registryAccess())) continue;

            LivingChestFunction.insertItem(invStack, toInsert, player.registryAccess());
            if (toInsert.isEmpty()) {
                inserted = true;
                break;
            }
        }

        if (!inserted) {
            carried.grow(toInsert.getCount());
        }

        player.containerMenu.setCarried(carried);
        syncCarriedToClient(player);
        player.containerMenu.broadcastChanges();
    }

    private static void handleWithdraw(ServerPlayer player, LivingChestAccessPacket packet) {
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

            extracted = LivingChestFunction.extractItem(invStack, target, amount);
            if (!extracted.isEmpty()) break;
        }

        if (extracted.isEmpty()) return;

        if (carried.isEmpty()) {
            player.containerMenu.setCarried(extracted);
        } else {
            carried.grow(extracted.getCount());
        }

        syncCarriedToClient(player);
        player.containerMenu.broadcastChanges();
    }

    private static void handleWithdrawToInventory(ServerPlayer player, LivingChestAccessPacket packet) {
        if (packet.itemTag() == null) return;
        ItemStack target = ItemStack.parse(player.registryAccess(), packet.itemTag()).orElse(ItemStack.EMPTY);
        if (target.isEmpty()) return;

        int amount = packet.amount();
        if (amount <= 0) amount = target.getMaxStackSize();

        ItemStack extracted = ItemStack.EMPTY;
        for (ItemStack invStack : player.getInventory().items) {
            if (!LivingChestFunction.isLivingChest(invStack)) continue;
            if (!LivingChestFunction.hasStorage(invStack)) continue;

            extracted = LivingChestFunction.extractItem(invStack, target, amount);
            if (!extracted.isEmpty()) break;
        }

        if (extracted.isEmpty()) return;

        player.getInventory().add(extracted);
        player.containerMenu.broadcastChanges();
    }

    private static void handleDepositFromSlot(ServerPlayer player, LivingChestAccessPacket packet) {
        if (packet.itemTag() == null) return;

        ItemStack target = ItemStack.parse(player.registryAccess(), packet.itemTag()).orElse(ItemStack.EMPTY);
        if (target.isEmpty()) return;

        int amount = Math.min(packet.amount(), target.getMaxStackSize());
        if (amount <= 0) return;

        ItemStack toInsert = ItemStack.EMPTY;
        for (ItemStack invStack : player.getInventory().items) {
            if (invStack.isEmpty() || !ItemStack.isSameItemSameComponents(invStack, target)) continue;

            int transfer = Math.min(amount, invStack.getCount());
            toInsert = invStack.copyWithCount(transfer);
            invStack.shrink(transfer);
            break;
        }

        if (toInsert.isEmpty()) return;

        boolean inserted = false;
        for (ItemStack invStack : player.getInventory().items) {
            if (!LivingChestFunction.isLivingChest(invStack)) continue;
            if (invStack.getCount() > 1) continue;
            if (!LivingChestFunction.hasStorage(invStack)) continue;
            if (LivingChestFunction.isStorageFull(invStack)) continue;
            if (!LivingChestFunction.canInsert(invStack, toInsert, player.registryAccess())) continue;

            LivingChestFunction.insertItem(invStack, toInsert, player.registryAccess());
            if (toInsert.isEmpty()) {
                inserted = true;
                break;
            }
        }

        if (!inserted) {
            player.getInventory().add(toInsert);
        }

        player.containerMenu.broadcastChanges();
    }

    private static void syncCarriedToClient(ServerPlayer player) {
        int stateId = player.containerMenu.incrementStateId();
        player.connection.send(new ClientboundContainerSetSlotPacket(
            -1, stateId, -1, player.containerMenu.getCarried().copy()));
    }
}