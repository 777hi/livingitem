package com.qiqi.li.living.domain.battle;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.interaction.InteractionHandler;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** 手持活末影珍珠右键槽内活不祥之瓶：传送到瓶棋子旁（观察战场，参照活地图传送 GUI 场景）。 */
public class BattlePearlTeleportHandler implements InteractionHandler {

    private static final int COOLDOWN_TICKS = 40;

    @Override
    public void handle(ServerPlayer player, Slot targetSlot) {
        ItemStack stack = targetSlot.getItem();
        if (!stack.is(Items.OMINOUS_BOTTLE) || !LivingItemManager.isLivingItem(stack)) return;

        ItemStack pearl = pearlInHand(player);
        if (pearl.isEmpty() || player.getCooldowns().isOnCooldown(Items.ENDER_PEARL)) return;

        ContainerContext ctx = ContainerContexts.resolve(player, targetSlot);
        if (ctx == null) return;
        BattleArenaData data = ctx.peekContainerData(BattleArenaData.KEY);
        if (data == null || data.isEmpty()) return;

        int logical = ctx.toLogicalSlot(targetSlot.getContainerSlot());
        if (!ctx.isValidSlot(logical)) return;
        int width = Math.max(1, ctx.getWidth());
        int row = logical / width;
        int col = logical % width;

        // 落点：瓶棋子的邻格（避开瓶棋子 hitbox；幽灵观战——怪不索敌玩家）
        int landCol = col + 1 < data.depth() ? col + 1 : col - 1;
        Vec3 dest = ArenaPlots.cellCenter(data.plotPos(), row, Math.max(0, landCol));

        player.teleportTo(dest.x, dest.y, dest.z);
        player.fallDistance = 0.0F;
        player.level().playSound(null, dest.x, dest.y, dest.z,
            net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,
            net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.0F);

        pearl.shrink(1);
        player.getCooldowns().addCooldown(Items.ENDER_PEARL, COOLDOWN_TICKS);
        player.getInventory().setChanged();
    }

    /** 珍珠在主手或副手（GUI 场景惯例：不在光标）；无则空栈。 */
    private static ItemStack pearlInHand(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.is(Items.ENDER_PEARL)) return main;
        ItemStack off = player.getOffhandItem();
        return off.is(Items.ENDER_PEARL) ? off : ItemStack.EMPTY;
    }
}
