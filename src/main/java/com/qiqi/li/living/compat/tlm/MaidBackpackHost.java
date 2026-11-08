package com.qiqi.li.living.compat.tlm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import com.qiqi.li.living.container.EntityContainerContext;
import com.qiqi.li.living.container.EntityContainers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.items.SlotItemHandler;

/** TLM 当前装备背包的存储前缀；原 handler 准入、黑名单与穿脱保持不变。 */
public final class MaidBackpackHost implements EntityContainers.Provider {
    @Override
    public boolean supports(Entity entity) {
        return entity instanceof EntityMaid;
    }

    @Override
    public EntityContainerContext createContext(Entity entity, ServerLevel level) {
        return new MaidContainerContext((EntityMaid) entity, level);
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
