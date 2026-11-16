package com.qiqi.li.living.compat.tlm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.MaidMainContainer;
import com.qiqi.li.living.interaction.MenuActionAccess;
import com.qiqi.li.living.container.EntityContainerContext;
import com.qiqi.li.living.container.EntityContainers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.items.SlotItemHandler;

/** TLM 当前装备背包的存储前缀；原 handler 准入、黑名单与穿脱保持不变。 */
public final class MaidBackpackHost implements EntityContainers.Provider, MenuActionAccess.Resolver {
    @Override
    public boolean supports(Entity entity) {
        return entity instanceof EntityMaid;
    }

    @Override
    public EntityContainerContext createContext(Entity entity, ServerLevel level) {
        return new MaidContainerContext((EntityMaid) entity, level);
    }

    @Override
    public boolean supports(AbstractContainerMenu menu) {
        return menu instanceof AbstractMaidContainer;
    }

    /** 所有者/距离沿官方菜单准入；仅授权真实开放存储格，不改变物品准入。 */
    @Override
    public MenuActionAccess.Decision check(MenuActionAccess.Request request) {
        if (!(request.menu() instanceof AbstractMaidContainer menu)
                || request.player().containerMenu != menu || !menu.stillValid(request.player())) {
            return MenuActionAccess.Decision.DENY;
        }
        EntityMaid maid = menu.getMaid();
        if (maid == null) return MenuActionAccess.Decision.DENY;
        if (!accessible(request, maid, request.target())) return MenuActionAccess.Decision.UNKNOWN;
        for (int logical : request.logicalSlots()) {
            Slot slot = MenuActionAccess.scopeSlot(request, logical);
            if (slot == null || !accessible(request, maid, slot)) return MenuActionAccess.Decision.UNKNOWN;
        }
        return MenuActionAccess.Decision.ALLOW;
    }

    private boolean accessible(MenuActionAccess.Request request, EntityMaid maid, Slot slot) {
        if (!MenuActionAccess.validTarget(request.player(), slot)) return false;
        if (slot.container == request.player().getInventory()) {
            return MenuActionAccess.standardSlot(request.player(), slot);
        }
        if (!(slot instanceof MaidMainContainer.BackpackSlot)
                || menuPhysicalSlot(maid, request.menu(), slot) < 0) return false;
        try {
            return slot.getClass().getMethod("mayPickup", net.minecraft.world.entity.player.Player.class)
                .getDeclaringClass() == SlotItemHandler.class;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public int menuPhysicalSlot(Entity entity, AbstractContainerMenu menu, Slot slot) {
        if (!(entity instanceof EntityMaid maid)
                || !(menu instanceof AbstractMaidContainer maidMenu) || maidMenu.getMaid() != maid
                || !(slot instanceof SlotItemHandler handlerSlot)
                || handlerSlot.getItemHandler() != maid.getMaidInv()) return -1;
        int physical = slot.getContainerSlot();
        int size = maid.getMaidBackpackType().getAvailableMaxContainerIndex();
        return physical >= 0 && physical < size && physical < maid.getMaidInv().getSlots()
            ? physical : -1;
    }
}
