package com.qiqi.li.client;

import com.qiqi.li.client.mixin.SlotWrapperAccessor;
import com.qiqi.li.living.core.interaction.InteractionEntry;
import com.qiqi.li.living.core.interaction.InteractionRegistry;
import com.qiqi.li.network.GuiInteractionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;

/**
 * 客户端GUI交互统一处理工具。
 *
 * 所有 Screen Mixin 的 mouseClicked/mouseReleased 拦截逻辑
 * 统一调用 {@link #tryInteract}，不再硬编码具体的物品判断。
 *
 * 处理流程：
 *   1. 从 InteractionRegistry 查询匹配的交互规则
 *   2. 解析真实容器槽位索引（兼容 SlotWrapper）
 *   3. 创造模式下序列化光标物品数据（解决服务端无法获取光标物品的问题）
 *   4. 发送 GuiInteractionPacket 到服务端
 *
 * 创造模式光标物品：
 *   创造模式使用 ItemPickerMenu，光标物品是客户端虚拟的，
 *   服务端 menu.getCarried() 返回空。
 *   当交互需要服务端访问光标物品时，客户端通过 carriedTag
 *   将光标物品的完整NBT数据发送到服务端。
 */
public final class GuiInteractionHelper {

    private GuiInteractionHelper() {}

    /**
     * 尝试处理GUI交互。
     *
     * @param hoveredSlot 鼠标悬浮的槽位
     * @param button      鼠标按键
     * @param menu        当前菜单
     * @return true 表示匹配到交互规则并已发送网络包，调用方应取消原版行为
     */
    public static boolean tryInteract(Slot hoveredSlot, int button, AbstractContainerMenu menu) {
        if (hoveredSlot == null) return false;

        ItemStack target = hoveredSlot.getItem();
        ItemStack trigger = menu.getCarried();

        InteractionEntry entry = InteractionRegistry.findInteraction(trigger, target, button);
        if (entry == null) return false;

        int containerSlot = resolveContainerSlot(hoveredSlot);
        CompoundTag carriedTag = resolveCarriedTag(menu);

        PacketDistributor.sendToServer(new GuiInteractionPacket(
            hoveredSlot.index, containerSlot, entry.actionId(), carriedTag));
        return true;
    }

    /**
     * 解析槽位的真实容器索引，兼容创造模式 SlotWrapper。
     */
    private static int resolveContainerSlot(Slot slot) {
        if (slot instanceof SlotWrapperAccessor accessor) {
            return accessor.getTarget().getContainerSlot();
        }
        return slot.getContainerSlot();
    }

    /**
     * 序列化光标物品数据，仅在光标为客户端虚拟物品时发送。
     *
     * 创造模式打开玩家背包时使用 CreativeModeInventoryScreen + ItemPickerMenu，
     * 光标物品是客户端虚拟的，服务端 menu.getCarried() 返回空。
     * 此时需要通过 carriedTag 将光标物品数据发送到服务端。
     *
     * 创造模式打开容器（如箱子）时，使用普通容器界面，
     * 光标物品由服务端真实管理，menu.getCarried() 能正常返回，
     * 不需要也不应该发送 carriedTag（否则会干扰服务端的光标管理）。
     *
     * @return 仅在 CreativeModeInventoryScreen 下返回光标物品的NBT，否则返回 null
     */
    @Nullable
    private static CompoundTag resolveCarriedTag(AbstractContainerMenu menu) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof CreativeModeInventoryScreen)) return null;

        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) return null;

        CompoundTag tag = (CompoundTag) carried.saveOptional(mc.player.registryAccess());
        return tag;
    }
}