package com.qiqi.li.client.input;

import com.qiqi.li.client.util.ClientSlotResolve;
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
    public static boolean tryInteract(Slot hoveredSlot, int button, boolean onRelease, AbstractContainerMenu menu) {
        if (hoveredSlot == null) return false;

        ItemStack target = hoveredSlot.getItem();
        ItemStack trigger = menu.getCarried();

        // 活桶汲/倒（流体侧批次二，2026-10-03）：目标条件是「空槽位 + 容器级源状态」，
        // 物品中心的规则系统表达不了（matchesTarget 对空槽恒 false）⇒ 客户端按流体快照
        // 缓存精确判定，不命中不拦截（原版操作不受影响）；命中后复用 GuiInteractionPacket
        // 服务端管道（actionId → WaterRegistration 注册的处理器，服务端权威重验）。
        if (button == 1 && !onRelease && LivingBucketFunction.isLivingBucket(trigger)) {
            String bucketAction = matchBucketInteract(hoveredSlot, trigger);
            if (bucketAction != null) {
                int containerSlot = resolveContainerSlot(hoveredSlot);
                CompoundTag carriedTag = resolveCarriedTag(menu);
                PacketDistributor.sendToServer(new GuiInteractionPacket(
                    hoveredSlot.index, containerSlot, bucketAction, carriedTag));
                return true;
            }
        }

        InteractionEntry entry = InteractionRegistry.findInteraction(trigger, target, button, onRelease);
        if (entry == null) return false;

        int containerSlot = resolveContainerSlot(hoveredSlot);
        CompoundTag carriedTag = resolveCarriedTag(menu);

        PacketDistributor.sendToServer(new GuiInteractionPacket(
            hoveredSlot.index, containerSlot, entry.actionId(), carriedTag));
        return true;
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
    private static String matchBucketInteract(Slot hoveredSlot, ItemStack carried) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;

        FluidFlowClientCache.FlowSnapshot snapshot;
        if (hoveredSlot.container == mc.player.getInventory()) {
            snapshot = FluidFlowClientCache.getPlayer();
        } else if (hoveredSlot.container instanceof net.minecraft.world.inventory.PlayerEnderChestContainer) {
            snapshot = FluidFlowClientCache.getEnder();   // 末影箱汲/倒（F-1 配套）
        } else {
            snapshot = FluidFlowClientCache.get();
        }

        var cell = snapshot.cells().get(resolveContainerSlot(hoveredSlot));
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
     * 解析槽位的真实容器索引，兼容创造模式 SlotWrapper。
     *
     * <p>Q6 批次 C（2026-10-04）：实现已迁至 {@link ClientSlotResolve#resolveContainerSlot}
     * （客户端槽位解析共享工具），此处保留薄委托。</p>
     */
    private static int resolveContainerSlot(Slot slot) {
        return ClientSlotResolve.resolveContainerSlot(slot);
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