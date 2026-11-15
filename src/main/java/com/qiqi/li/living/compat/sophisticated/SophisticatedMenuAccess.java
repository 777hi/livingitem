package com.qiqi.li.living.compat.sophisticated;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.qiqi.li.living.interaction.MenuActionAccess;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import com.qiqi.li.logging.ModLog;

/** 精妙菜单的真实存储区访问；公开 API 失效时不回落通用槽许可。 */
public final class SophisticatedMenuAccess implements MenuActionAccess.Resolver {
    private static final String MENU_CLASS = "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer";
    private static final Map<Class<?>, Optional<Methods>> METHODS = new ConcurrentHashMap<>();

    private record Methods(Method storage, Method inaccessible, Method count) {}

    @Override
    public boolean supports(AbstractContainerMenu menu) {
        for (Class<?> type = menu.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals(MENU_CLASS)) return true;
        }
        return false;
    }

    @Override
    public MenuActionAccess.Decision check(MenuActionAccess.Request request) {
        AbstractContainerMenu menu = request.menu();
        Slot target = request.target();
        if (target.container == request.player().getInventory()) {
            if (!playerSlot(request, target)) return MenuActionAccess.Decision.UNKNOWN;
            for (int logical : request.logicalSlots()) {
                Slot slot = MenuActionAccess.scopeSlot(request, logical);
                if (slot == null || !playerSlot(request, slot)) return MenuActionAccess.Decision.DENY;
            }
            return MenuActionAccess.Decision.ALLOW;
        }
        var methods = METHODS.computeIfAbsent(menu.getClass(), SophisticatedMenuAccess::findMethods);
        if (methods.isEmpty()) return MenuActionAccess.Decision.UNKNOWN;
        try {
            Methods api = methods.get();
            int count = (int) api.count().invoke(menu);
            if (!accessible(menu, target, api, count)) return MenuActionAccess.Decision.DENY;
            if (!request.logicalSlots().isEmpty()) {
                if (request.context() == null) return MenuActionAccess.Decision.UNKNOWN;
                for (int logical : request.logicalSlots()) {
                    int physical = request.context().toPhysicalSlot(logical);
                    if (physical < 0 || physical >= count || physical >= menu.slots.size()) {
                        return MenuActionAccess.Decision.DENY;
                    }
                    Slot slot = menu.getSlot(physical);
                    if (slot.container == request.player().getInventory()
                            || !accessible(menu, slot, api, count)) return MenuActionAccess.Decision.DENY;
                }
            }
            return MenuActionAccess.Decision.ALLOW;
        } catch (ReflectiveOperationException | RuntimeException e) {
            ModLog.CONTAINER.debug("[Sophisticated] 菜单动作访问 API 失败：{}", e.toString());
            return MenuActionAccess.Decision.UNKNOWN;
        }
    }

    private static boolean playerSlot(MenuActionAccess.Request request, Slot slot) {
        return slot.container == request.player().getInventory()
            && MenuActionAccess.standardSlot(request.player(), slot);
    }

    private static boolean accessible(AbstractContainerMenu menu, Slot slot, Methods api, int count)
            throws ReflectiveOperationException {
        int index = slot.index;
        return slot.isActive() && index >= 0 && index < count && index < menu.slots.size()
            && menu.getSlot(index) == slot && slot.getContainerSlot() == index
            && (boolean) api.storage().invoke(menu, index)
            && !(boolean) api.inaccessible().invoke(menu, index);
    }

    private static Optional<Methods> findMethods(Class<?> type) {
        try {
            return Optional.of(new Methods(type.getMethod("isStorageInventorySlot", int.class),
                type.getMethod("isInaccessibleSlot", int.class),
                type.getMethod("getNumberOfStorageInventorySlots")));
        } catch (ReflectiveOperationException | RuntimeException e) {
            ModLog.CONTAINER.debug("[Sophisticated] 菜单动作访问 API 不可用：{}", e.toString());
            return Optional.empty();
        }
    }
}
