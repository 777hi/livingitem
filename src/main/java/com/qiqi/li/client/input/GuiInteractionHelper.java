package com.qiqi.li.client.input;

import com.qiqi.li.client.util.ClientMenuSlotOwners;
import com.qiqi.li.client.util.ClientMenuSlots;
import com.qiqi.li.living.domain.water.FluidFlowClientCache;
import com.qiqi.li.living.domain.water.LivingBucketFunction;
import com.qiqi.li.living.interaction.InteractionEntry;
import com.qiqi.li.living.interaction.InteractionRegistry;
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
 * 客户端 GUI 交互统一入口；仅真实菜单槽可发送交互包。
 *
 * <p>按规则匹配动作，携带服务端菜单 ID、精确菜单槽号与该槽物理号；
 * 创造模式只接入 inventory wrapper 的真实目标，并附带虚拟光标物品数据。
 * 门面与容器级桶动作额外要求存储槽，普通物品动作不受此限制。</p>
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
    public static boolean tryInteract(Slot hoveredSlot, int button, boolean onRelease, AbstractContainerMenu menu) {
        int menuSlot = ClientMenuSlots.serverMenuSlot(menu, hoveredSlot);
        int menuId = ClientMenuSlots.serverMenuId(menu);
        if (menuSlot < 0 || menuId < 0) return false;
        int containerSlot = ClientMenuSlots.serverContainerSlot(menu, hoveredSlot);
        if (containerSlot < 0) return false;

        ItemStack target = hoveredSlot.getItem();
        ItemStack trigger = menu.getCarried();

        if (button == 1 && !onRelease
                && ClientMenuSlots.isStorageSlot(menu, hoveredSlot)) {
            String action = null;
            if (trigger.isEmpty()
                    && com.qiqi.li.living.domain.portal.PortalClientCache.contains(menuId, menuSlot)) {
                action = com.qiqi.li.living.domain.portal.PortalInteractHandlers.USE_ID;
            } else if (target.isEmpty()
                    && com.qiqi.li.living.domain.portal.PortalInteractHandlers.isIgniter(trigger)
                    && ClientMenuSlotOwners.resolveStorageSlot(menu, hoveredSlot) >= 0
                    && hasNearbyPortalFrame(menu, hoveredSlot)) {
                action = com.qiqi.li.living.domain.portal.PortalInteractHandlers.IGNITE_ID;
            }
            if (action != null) {
                PacketDistributor.sendToServer(new GuiInteractionPacket(
                    menuSlot, containerSlot, action, resolveCarriedTag(menu), menuId));
                return true;
            }
        }

        // 活桶汲/倒只接入真实存储槽，依据容器级流体快照判定。
        if (button == 1 && !onRelease && LivingBucketFunction.isLivingBucket(trigger)
                && ClientMenuSlots.isStorageSlot(menu, hoveredSlot)) {
            String bucketAction = matchBucketInteract(hoveredSlot, trigger,
                ClientMenuSlotOwners.resolveStorageSlot(menu, hoveredSlot));
            if (bucketAction != null) {
                CompoundTag carriedTag = resolveCarriedTag(menu);
                PacketDistributor.sendToServer(new GuiInteractionPacket(
                    menuSlot, containerSlot, bucketAction, carriedTag, menuId));
                return true;
            }
        }

        // 活海绵汲出依据同步组件量判定，不要求容器级流体快照。
        if (button == 1 && !onRelease
                && LivingBucketFunction.isLivingBucket(trigger)
                && LivingBucketFunction.isEmptyBucket(trigger)
                && com.qiqi.li.living.domain.sponge.LivingSpongeInteractSupport.drawable(target)) {
            CompoundTag carriedTag = resolveCarriedTag(menu);
            PacketDistributor.sendToServer(new GuiInteractionPacket(
                menuSlot, containerSlot, "living_sponge_draw", carriedTag, menuId));
            return true;
        }

        InteractionEntry entry = InteractionRegistry.findInteraction(trigger, target, button, onRelease);
        if (entry == null) return false;

        CompoundTag carriedTag = resolveCarriedTag(menu);

        PacketDistributor.sendToServer(new GuiInteractionPacket(
            menuSlot, containerSlot, entry.actionId(), carriedTag, menuId));
        return true;
    }

    private static boolean hasNearbyPortalFrame(AbstractContainerMenu menu, Slot target) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        boolean playerGroup = target.container == mc.player.getInventory();
        java.util.Map<Long, Slot> cells = new java.util.HashMap<>();
        for (Slot slot : menu.slots) {
            if (!ClientMenuSlots.isStorageSlot(menu, slot)
                    || (slot.container == mc.player.getInventory()) != playerGroup) continue;
            if (ClientMenuSlotOwners.resolveStorageSlot(menu, slot) < 0) continue;
            cells.put(((long) slot.x << 32) | (slot.y & 0xffffffffL), slot);
        }
        java.util.Set<Slot> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<Slot> queue = new java.util.ArrayDeque<>();
        queue.add(target);
        while (!queue.isEmpty()) {
            Slot slot = queue.removeFirst();
            if (!visited.add(slot)) continue;
            if (!slot.getItem().isEmpty()) return false;
            for (int[] direction : new int[][] {{0, -18}, {0, 18}, {-18, 0}, {18, 0}}) {
                long key = ((long) (slot.x + direction[0]) << 32)
                    | ((slot.y + direction[1]) & 0xffffffffL);
                Slot next = cells.get(key);
                if (next == null) return false;
                if (!com.qiqi.li.living.domain.portal.PortalStructure.isFrame(next.getItem())) queue.add(next);
            }
        }
        return !visited.isEmpty();
    }

    /**
     * 活桶汲/倒的客户端判定（依据 {@link FluidFlowClientCache} 快照 —— 与渲染同源）：
     * <ul>
     *   <li>满桶 + 目标格无物品 → 倒水（目标格已有源也倒：源不变，仅排空 —— 原版语义）；</li>
     *   <li>空桶 + 目标格是源（快照 level==0；桶源退役后一切源皆派生源）→ 汲水。</li>
     * </ul>
     * 其余情况返回 null（不拦截，光标右键的原版拿起/放置/交换照常）。
     */
    @Nullable
    private static String matchBucketInteract(Slot hoveredSlot, ItemStack carried, int storageSlot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;

        FluidFlowClientCache.FlowSnapshot snapshot;
        if (hoveredSlot.container == mc.player.getInventory()) {
            snapshot = FluidFlowClientCache.getPlayer();
        } else {
            // 非背包组：末影箱 / BE 容器在客户端同形 ⇒ 由服务端下发的 RenderTarget 决定
            snapshot = FluidFlowClientCache.getChestLike();
        }

        var cell = snapshot.cells().get(storageSlot);
        boolean isSourceCell = cell != null && cell[0] == 0;

        if (LivingBucketFunction.hasFullBucket(carried)) {
            return hoveredSlot.getItem().isEmpty() ? "living_bucket_pour" : null;
        }
        if (LivingBucketFunction.isEmptyBucket(carried) && isSourceCell) {
            return "living_bucket_scoop";
        }
        return null;
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