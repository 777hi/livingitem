package com.qiqi.li.client.render.tlm;

import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.items.StackCopySlot;

/** 模型层握手只接纳每次返回同一栈引用的物理槽。 */
final class SoulSlabGuiSlots {
    private SoulSlabGuiSlots() {}

    static boolean hasStableStackReference(Slot display, Slot physical) {
        return display != null && physical != null && !(physical instanceof StackCopySlot);
    }
}
