package com.qiqi.li.network;

import com.qiqi.li.living.domain.chest.LivingChestFunction;
import com.qiqi.li.living.domain.tools.LivingToolMemory;
import com.qiqi.li.living.domain.tools.LivingToolRecorder;
import com.qiqi.li.living.domain.hopper.LivingHopperFunction;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.HasDirection;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.model.SlotMapping;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端自定义数据包处理入口。
 *
 * <p>集中处理各客户端 → 服务端数据包（如活工具记忆清除、活箱子存取、活地图传送等），
 * 调用对应功能完成逻辑或回发响应；各处理方法为静态入口，按数据包类型分发。</p>
 */
public class ServerPacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerPacketHandler.class);

    /**
     * 清除手持活工具 / 活武器的指定记忆（{@code L13}）。
     *
     * <p>来源：客户端 {@code PlayerInteractEvent.LeftClickEmpty}（左键空气）。
     * 服务端感知不到左键空气，故由客户端发 {@link ToolMemoryClearPacket} 告知。</p>
     *
     * <p>⭐ <b>判据按 {@code kind} 分开</b>：清挖掘 / 交互要求手持<b>活工具</b>，
     * 清攻击要求手持<b>活武器</b> —— 两者判据不同（活剑不是活工具）。</p>
     */
    public static void handleToolMemoryClear(ServerPlayer player, ToolMemoryClearPacket payload) {
        if (player == null) {
            return;
        }
        if (LivingToolRecorder.isInRecordGrace(player)) {
            return;   // L44：刚写完记忆，1 秒内的惯性误触不清除
        }

        ItemStack tool = player.getMainHandItem();
        int kind = payload.kind();

        boolean eligible = switch (kind) {
            case ToolMemoryClearPacket.KIND_DIG, ToolMemoryClearPacket.KIND_USE ->
                LivingToolRecorder.isLivingTool(tool);
            case ToolMemoryClearPacket.KIND_ATTACK ->
                LivingToolRecorder.isLivingWeapon(tool);
            default -> false;
        };
        if (!eligible) {
            return;
        }

        LivingToolMemory memory = LivingToolMemory.of(tool);
        LivingToolMemory updated = switch (kind) {
            case ToolMemoryClearPacket.KIND_DIG -> memory.withoutDig();
            case ToolMemoryClearPacket.KIND_USE -> memory.withoutUse();
            case ToolMemoryClearPacket.KIND_ATTACK -> memory.withoutAttack();
            default -> memory;
        };
        if (updated.equals(memory)) {
            return;   // 本来就没有这条记忆 → 不必回同步
        }

        LivingToolMemory.set(tool, updated);
        player.inventoryMenu.broadcastChanges();
    }

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

        boolean success = LivingHopperFunction.updateTransferMapping(carried, newMapping);
        if (!success) {
            LOGGER.warn("Failed to update mapping for player {}", player.getName().getString());
            return;
        }

        int stateId = player.containerMenu.incrementStateId();
        player.connection.send(new ClientboundContainerSetSlotPacket(
            -1, stateId, -1, carried.copy()));

        LOGGER.debug("Updated hopper direction for player {}: {}",
            player.getName().getString(), newMapping.displayName());
    }

    public static void handleSlotDirection(ServerPlayer player, SlotDirectionPacket payload) {
        if (player == null || player.containerMenu == null) return;

        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) {
            LOGGER.warn("Player {} has no item on cursor", player.getName().getString());
            return;
        }

        if (!LivingItemManager.isLivingItem(carried)) {
            LOGGER.warn("Player {} carried item is not living", player.getName().getString());
            return;
        }

        Pos2D direction = new Pos2D(payload.directionX(), payload.directionY());
        boolean success = false;

        for (LivingItemFunction func : LivingItemManager.getApplicableFunctions(carried)) {
            if (func instanceof HasDirection dir) {
                success = dir.updateSlotDirection(carried, payload.slotName(), direction);
                break;
            }
        }

        if (!success) {
            LOGGER.warn("Failed to update slot direction for player {}: slot={} dir={}",
                player.getName().getString(), payload.slotName(), direction);
            return;
        }

        int stateId = player.containerMenu.incrementStateId();
        player.connection.send(new ClientboundContainerSetSlotPacket(
            -1, stateId, -1, carried.copy()));

        LOGGER.debug("Updated slot direction for player {}: {} = {}",
            player.getName().getString(), payload.slotName(), direction.getSymbol());
    }

    public static void handleLivingChestAccess(ServerPlayer player, LivingChestAccessPacket packet) {
        if (player == null || player.containerMenu == null) return;

        int action = packet.action();
        switch (action) {
            case LivingChestAccessPacket.DEPOSIT -> {
                handleDeposit(player, packet);
            }
            case LivingChestAccessPacket.WITHDRAW -> {
                handleWithdraw(player, packet);
            }
            case LivingChestAccessPacket.WITHDRAW_INVENTORY -> {
                handleWithdrawToInventory(player, packet);
            }
            case LivingChestAccessPacket.DEPOSIT_SLOT -> {
                handleDepositFromSlot(player, packet);
            }
        }
    }

    private static void handleDeposit(ServerPlayer player, LivingChestAccessPacket packet) {
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) return;

        int amount = Math.min(packet.amount(), carried.getCount());
        if (amount <= 0) return;

        ItemStack toInsert = carried.split(amount);
        if (toInsert.isEmpty()) return;

        boolean inserted = false;
        for (ItemStack invStack : player.getInventory().items) {
            if (!LivingChestFunction.isLivingChest(invStack)) continue;
            if (!LivingChestFunction.hasStorage(invStack)) continue;
            if (!LivingChestFunction.canInsert(invStack, toInsert, player.registryAccess())) continue;

            LivingChestFunction.insertItem(invStack, toInsert, player.registryAccess());
            if (toInsert.isEmpty()) {
                inserted = true;
                break;
            }
        }

        if (!inserted) {
            carried.grow(toInsert.getCount());
        }

        player.containerMenu.setCarried(carried);
        syncCarriedToClient(player);
        player.containerMenu.broadcastChanges();
    }

    private static void handleWithdraw(ServerPlayer player, LivingChestAccessPacket packet) {
        if (packet.itemTag() == null) return;
        ItemStack carried = player.containerMenu.getCarried();
        ItemStack target = ItemStack.parse(player.registryAccess(), packet.itemTag()).orElse(ItemStack.EMPTY);
        if (target.isEmpty()) return;

        if (!carried.isEmpty() && !ItemStack.isSameItemSameComponents(carried, target)) {
            return;
        }

        int amount = packet.amount();
        if (amount <= 0) amount = target.getMaxStackSize();

        if (!carried.isEmpty()) {
            amount = Math.min(amount, target.getMaxStackSize() - carried.getCount());
            if (amount <= 0) return;
        }

        ItemStack extracted = ItemStack.EMPTY;
        for (ItemStack invStack : player.getInventory().items) {
            if (!LivingChestFunction.isLivingChest(invStack)) continue;
            if (!LivingChestFunction.hasStorage(invStack)) continue;

            extracted = LivingChestFunction.extractItem(invStack, target, amount);
            if (!extracted.isEmpty()) break;
        }

        if (extracted.isEmpty()) return;

        if (carried.isEmpty()) {
            player.containerMenu.setCarried(extracted);
        } else {
            carried.grow(extracted.getCount());
        }

        syncCarriedToClient(player);
        player.containerMenu.broadcastChanges();
    }

    private static void handleWithdrawToInventory(ServerPlayer player, LivingChestAccessPacket packet) {
        if (packet.itemTag() == null) return;
        ItemStack target = ItemStack.parse(player.registryAccess(), packet.itemTag()).orElse(ItemStack.EMPTY);
        if (target.isEmpty()) return;

        int amount = packet.amount();
        if (amount <= 0) amount = target.getMaxStackSize();

        ItemStack extracted = ItemStack.EMPTY;
        for (ItemStack invStack : player.getInventory().items) {
            if (!LivingChestFunction.isLivingChest(invStack)) continue;
            if (!LivingChestFunction.hasStorage(invStack)) continue;

            extracted = LivingChestFunction.extractItem(invStack, target, amount);
            if (!extracted.isEmpty()) break;
        }

        if (extracted.isEmpty()) return;

        addToInventoryOrDrop(player, extracted);
        player.containerMenu.broadcastChanges();
    }

    /**
     * 收进玩家背包;放不下的余量<b>掉落在玩家脚边</b>。
     *
     * <p>⚠️ 原版 {@code Inventory#add} 的余量<b>留在传入栈里</b> —— 直接丢栈等于静默销毁
     * (2026-10-27 审查 P0:配方书 Shift 取出 + 满背包即触发,恰是拿活箱子当仓库的典型场景)。
     * 掉落走原版 {@code Player#drop}(死亡掉落同款官方入口)。包级可见供单测钉住「满背包不丢物」。</p>
     */
    static void addToInventoryOrDrop(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        player.getInventory().add(stack);
        if (!stack.isEmpty()) {
            // 原版 drop 只把栈<b>拷贝</b>进掉落实体、不消耗传入栈 ⇒ 落地后清零,
            // 代表所有权移交完成(调用方不得再对余量做二次处理)。
            // 传副本:drop 内部还会再拷一次,无害;但保证「掉出去的」与「调用方手里清零的」各是各的
            player.drop(stack.copy(), false);
            stack.setCount(0);
        }
    }

    private static void handleDepositFromSlot(ServerPlayer player, LivingChestAccessPacket packet) {
        if (packet.itemTag() == null) return;

        ItemStack target = ItemStack.parse(player.registryAccess(), packet.itemTag()).orElse(ItemStack.EMPTY);
        if (target.isEmpty()) return;

        int amount = Math.min(packet.amount(), target.getMaxStackSize());
        if (amount <= 0) return;

        ItemStack toInsert = ItemStack.EMPTY;
        for (ItemStack invStack : player.getInventory().items) {
            if (invStack.isEmpty() || !ItemStack.isSameItemSameComponents(invStack, target)) continue;

            int transfer = Math.min(amount, invStack.getCount());
            toInsert = invStack.copyWithCount(transfer);
            invStack.shrink(transfer);
            break;
        }

        if (toInsert.isEmpty()) return;

        boolean inserted = false;
        for (ItemStack invStack : player.getInventory().items) {
            if (!LivingChestFunction.isLivingChest(invStack)) continue;
            if (invStack.getCount() > 1) continue;
            if (!LivingChestFunction.hasStorage(invStack)) continue;
            if (LivingChestFunction.isStorageFull(invStack)) continue;
            if (!LivingChestFunction.canInsert(invStack, toInsert, player.registryAccess())) continue;

            LivingChestFunction.insertItem(invStack, toInsert, player.registryAccess());
            if (toInsert.isEmpty()) {
                inserted = true;
                break;
            }
        }

        if (!inserted) {
            addToInventoryOrDrop(player, toInsert);
        }

        player.containerMenu.broadcastChanges();
    }

    private static void syncCarriedToClient(ServerPlayer player) {
        int stateId = player.containerMenu.incrementStateId();
        player.connection.send(new ClientboundContainerSetSlotPacket(
            -1, stateId, -1, player.containerMenu.getCarried().copy()));
    }
}