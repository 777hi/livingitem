package com.qiqi.li.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.qiqi.li.client.gui.LivingButton;
import com.qiqi.li.living.LivingHopperFunction;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.components.DirectionModeComponent;
import com.qiqi.li.living.core.model.SlotMapping;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.qiqi.li.LivingItem;
import com.qiqi.li.network.HopperDirectionPacket;

@EventBusSubscriber(modid = LivingItem.MOD_ID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public class LivingItemInputHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LivingItemInputHandler.class);

    private static InputSession currentSession = null;

    @SubscribeEvent
    public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (!(mc.screen instanceof AbstractContainerScreen)) return;

        if (!isLivingButtonHovered()) return;

        ItemStack carried = mc.player.containerMenu.getCarried();
        if (carried.isEmpty()) return;

        if (!LivingItemManager.isLivingItem(carried)) return;

        if (!isHopperItem(carried)) return;

        char typedChar = event.getCodePoint();
        char upperChar = Character.toUpperCase(typedChar);

        DirectionModeComponent tempComp = new DirectionModeComponent();
        if (!tempComp.getValidKeys().contains(upperChar)) return;

        event.setCanceled(true);

        if (currentSession == null) {
            currentSession = new InputSession();
        }

        currentSession.appendKey(upperChar);

        if (currentSession.isComplete()) {
            processInput(currentSession.getRawInput(), carried);
            currentSession = null;
        }
    }

    private static boolean isLivingButtonHovered() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) return false;

        for (GuiEventListener child : mc.screen.children()) {
            if (child instanceof LivingButton button && button.isHovered()) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel)) return;
        if (currentSession != null && currentSession.isTimedOut()) {
            LOGGER.debug("Input session timed out: {}", currentSession.getRawInput());
            currentSession = null;
        }
    }

    private static void processInput(String rawInput, ItemStack hopperStack) {
        DirectionModeComponent dirComp = new DirectionModeComponent();

        ComponentState dirState;
        CompoundTag functionTag = LivingItemManager.getFunctionData(hopperStack, LivingHopperFunction.ID);

        if (functionTag.contains(DirectionModeComponent.ID)) {
            dirState = ComponentState.fromNBT(functionTag.getCompound(DirectionModeComponent.ID));
        } else {
            dirState = dirComp.createDefaultState();
        }

        boolean success = dirComp.updateFromInput(dirState, rawInput);
        if (!success) {
            LOGGER.debug("Failed to parse input: {}", rawInput);
            return;
        }

        SlotMapping mapping = dirComp.getCurrentMapping(dirState);
        if (mapping == null) {
            LOGGER.debug("No mapping resolved from input: {}", rawInput);
            return;
        }

        sendDirectionPacket(mapping);

        LOGGER.debug("Updated hopper direction: {} -> {}", rawInput, mapping.displayName());
    }

    private static void sendDirectionPacket(SlotMapping mapping) {
        PacketDistributor.sendToServer(new HopperDirectionPacket(mapping.toNBT()));
    }

    private static boolean isHopperItem(ItemStack stack) {
        return stack.is(net.minecraft.world.item.Items.HOPPER) &&
               LivingItemManager.isLivingItem(stack);
    }

    private static class InputSession {
        private final StringBuilder keys = new StringBuilder();
        private final long startTime;
        private static final long TIMEOUT_MS = 2000;
        private static final int MAX_KEYS = 2;

        InputSession() {
            this.startTime = System.currentTimeMillis();
        }

        void appendKey(char key) {
            if (keys.length() < MAX_KEYS) {
                keys.append(key);
            }
        }

        boolean isComplete() {
            return keys.length() >= MAX_KEYS;
        }

        boolean isTimedOut() {
            return System.currentTimeMillis() - startTime > TIMEOUT_MS;
        }

        String getRawInput() {
            return keys.toString();
        }
    }
}