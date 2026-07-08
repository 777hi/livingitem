package com.qiqi.li.network;

import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.qiqi.li.living.LivingHopperFunction;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.components.DirectionModeComponent;
import com.qiqi.li.living.core.model.SlotMapping;

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

        CompoundTag functionTag = LivingItemManager.getFunctionData(carried, LivingHopperFunction.ID);

        ComponentState dirState;
        if (functionTag.contains(DirectionModeComponent.ID)) {
            dirState = ComponentState.fromNBT(functionTag.getCompound(DirectionModeComponent.ID));
        } else {
            DirectionModeComponent tempComp = new DirectionModeComponent();
            dirState = tempComp.createDefaultState();
        }

        DirectionModeComponent dirComp = new DirectionModeComponent();
        boolean success = dirComp.updateMapping(dirState, newMapping);

        if (!success) {
            LOGGER.warn("Failed to update mapping for player {}", player.getName().getString());
            return;
        }

        functionTag.put(DirectionModeComponent.ID, dirState.toNBT());
        LivingItemManager.setFunctionData(carried, LivingHopperFunction.ID, functionTag);

        int stateId = player.containerMenu.incrementStateId();
        player.connection.send(new ClientboundContainerSetSlotPacket(
            -1, stateId, -1, carried.copy()));

        LOGGER.debug("Updated hopper direction for player {}: {}",
            player.getName().getString(), newMapping.displayName());
    }
}