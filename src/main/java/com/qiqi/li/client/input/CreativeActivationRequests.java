package com.qiqi.li.client.input;

import com.qiqi.li.LivingItem;
import com.qiqi.li.client.gui.CreativeActivationSession;
import com.qiqi.li.network.CarriedUpdatePacket;
import com.qiqi.li.network.LivingTagPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** 创造活化往返；等待时只门控发起界面，强制退出不阻挡、不恢复旧栈。 */
@EventBusSubscriber(modid = LivingItem.MOD_ID, value = Dist.CLIENT)
public final class CreativeActivationRequests {
    private static CreativeModeInventoryScreen owner;
    private static LocalPlayer player;
    private static CreativeActivationSession session;

    private CreativeActivationRequests() {}

    public static LivingTagPacket begin(CreativeModeInventoryScreen screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.isCreative() || !screen.isInventoryOpen()) return null;
        if (owner != screen || player != mc.player) {
            clear();
            owner = screen;
            player = mc.player;
            session = new CreativeActivationSession(player.inventoryMenu.containerId);
        }
        return session.begin(screen.getMenu().getCarried(), GuiInteractionHelper.resolveCarriedTag(screen.getMenu()));
    }

    public static boolean pending(Screen screen) {
        return screen == owner && session != null && session.pending();
    }

    public static void accept(CarriedUpdatePacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { clear(); return; }
        if (packet.requestId() != 0) {
            if (mc.screen != owner || mc.player != player || session == null) return;
            if (session.accept(packet.menuId(), packet.requestId(), owner.getMenu().getCarried())) {
                ItemStack result = ItemStack.parse(mc.player.registryAccess(), packet.carriedTag()).orElse(ItemStack.EMPTY);
                owner.getMenu().setCarried(result);
            }
            return;
        }
        if (pending(mc.screen)) return;
        ItemStack carried = ItemStack.parse(mc.player.registryAccess(), packet.carriedTag()).orElse(ItemStack.EMPTY);
        if (mc.screen instanceof CreativeModeInventoryScreen screen) screen.getMenu().setCarried(carried);
        else mc.player.containerMenu.setCarried(carried);
    }

    private static void clear() {
        if (session != null) session.clear();
        session = null;
        owner = null;
        player = null;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (pending(event.getScreen())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (pending(event.getScreen())) {
            ((com.qiqi.li.client.mixin.AbstractContainerScreenAccessor) event.getScreen()).living_item$setSkipNextRelease(false);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (pending(event.getScreen())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (pending(event.getScreen())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (pending(event.getScreen())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (pending(event.getScreen())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClosing(ScreenEvent.Closing event) {
        if (event.getScreen() == owner) {
            if (session.discardExpected(owner.getMenu().getCarried())) owner.getMenu().setCarried(ItemStack.EMPTY);
            clear();
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
}
