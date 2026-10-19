package com.qiqi.li.living.domain.water;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import com.qiqi.li.living.util.DoubleChestPositions;

/**
 * 容器级数据的**形态迁移**：方块 ⇄ 物品（2026-10-19）。
 *
 * <h3>解决什么</h3>
 * <p>真机：「背包里的活流体，在背包变成物体形态后就没有了」。</p>
 *
 * <p>容器级流体数据挂在 <b>BE 附件</b> {@link WaterComponents#CONTAINER_FLUID_DATA} 上；
 * 破坏方块时 BE 被移除 ⇒ 附件随之消失。而精妙背包 / 原版潜影盒的<b>物品内容</b>存在别处
 * （按 contents UUID / {@code minecraft:container}）⇒ 内容保留、流体丢失。</p>
 *
 * <h3>为什么不物品化</h3>
 * <p>用户 2026-10-19 定案：<b>流体保持「容器属性」语义</b>（不占槽位、不是 ItemStack）。
 * 所以要解决的不是「流体是什么」，而是「属性存在哪」——属性不变，只让<b>存储介质跟着形态走</b>。</p>
 *
 * <h3>两个挂点</h3>
 * <ul>
 *   <li><b>破坏</b>：{@link BlockDropsEvent} —— 掉落物已确定、<b>尚未加入世界</b>，
 *       且仍能拿到 {@link BlockEntity}（附件还在）。这是唯一能在「数据还活着」时
 *       把数据交给掉落物的时机。</li>
 *   <li><b>放置</b>：{@link BlockEvent.EntityPlaceEvent} —— 方块已落地、BE 已创建；
 *       从放置者手持物品读组件搬进新 BE。</li>
 * </ul>
 *
 * <h3>⚠️ 双箱守恒</h3>
 * <p>大箱子的流体数据同时挂在两半 BE 上（{@code ContainerFluidWriteback} 写给所有关联 BE）。
 * 破坏一半时若只迁移不清另一半 ⇒ 两次破坏各得一份 ⇒ <b>复制</b>。
 * 因此迁移后把<b>所有关联 BE 的附件清空</b>：水跟着<b>先被破坏的那一半</b>走，总量守恒。</p>
 */
@EventBusSubscriber
public final class ContainerDataMigration {

    private ContainerDataMigration() {}

    /**
     * 破坏方块：BE 附件 → 掉落物组件。
     *
     * <p>只处理「该 BE 上确实有非空流体数据」的情形；绝大多数方块破坏走空返回，
     * 零额外开销。</p>
     */
    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        BlockEntity be = event.getBlockEntity();
        if (be == null) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        ContainerFluidData data = be.getData(WaterComponents.CONTAINER_FLUID_DATA);
        if (data == null || data.isEmpty()) return;

        ItemEntity target = pickDrop(event.getDrops(), be);
        if (target == null) return;   // 没有可承载的物品（如被精准采集以外的破坏）⇒ 数据随 BE 消失

        ItemStack stack = target.getItem();
        stack.set(WaterComponents.CONTAINER_FLUID_DATA_ITEM.get(), data);

        clearAssociated(level, be.getBlockPos());
    }

    /**
     * 放置方块：手持物品的组件 → 新 BE 附件。
     *
     * <p>⚠️ 为什么从<b>放置者手持</b>取而不是从事件里取：{@code EntityPlaceEvent} 不带 ItemStack。
     * 对玩家放置覆盖 100%（{@code BlockItem.place} 的上下文就是手持物品）；
     * 非玩家放置（发射器 / 活塞）取不到 ⇒ 降级为「组件留在物品上、BE 无数据」，
     * 不丢物品、不误伤 —— 而背包方块与潜影盒本来也无法被发射器放置。</p>
     */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity placer = event.getEntity();
        if (!(placer instanceof Player player)) return;

        BlockPos pos = event.getPos();
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;

        // ⚠️ 必须校验「手持物品 == 这次放置的方块对应的物品」：否则玩家手持带数据的背包、
        // 却去放置另一个有 BE 的方块时，会把背包的流体塞进那个方块。
        ContainerFluidData data = fluidDataOfPlacedItem(player, event.getPlacedBlock().getBlock());
        if (data == null) return;

        be.setData(WaterComponents.CONTAINER_FLUID_DATA.value(), data);
    }

    /**
     * 放置者手上**确实是这次放置的那个方块**、且带流体数据的物品（主手优先）。
     *
     * <p>校验物品身份是必须的：只按「手上有数据」匹配，会在玩家手持带数据的背包去放置
     * 另一个有 BE 的方块时把数据错塞过去。</p>
     */
    @Nullable
    private static ContainerFluidData fluidDataOfPlacedItem(Player player, net.minecraft.world.level.block.Block placedBlock) {
        net.minecraft.world.item.Item placedItem = placedBlock.asItem();
        if (placedItem == net.minecraft.world.item.Items.AIR) return null;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty() || !stack.is(placedItem)) continue;
            ContainerFluidData data = stack.get(WaterComponents.CONTAINER_FLUID_DATA_ITEM.get());
            if (data != null && !data.isEmpty()) return data;
        }
        return null;
    }

    /**
     * 从掉落物里挑「代表这个方块本体」的那一件。
     *
     * <p>规则：优先选物品 id 与该方块对应 BlockItem 一致的那件；否则退回第一件。
     * 破坏一个背包方块只会掉一个背包物品，所以绝大多数情况直接命中第一件；
     * 这条判据是给「方块同时掉本体 + 附加物」的模组留的余地。</p>
     */
    @Nullable
    static ItemEntity pickDrop(List<ItemEntity> drops, BlockEntity be) {
        if (drops.isEmpty()) return null;
        // 方块 → 它的 BlockItem 的 item（Block#asItem 对方块恒有返回值，无 BlockItem 时返回 air）
        net.minecraft.world.item.Item blockItem = be.getBlockState().getBlock().asItem();
        if (blockItem != net.minecraft.world.item.Items.AIR) {
            for (ItemEntity drop : drops) {
                ItemStack stack = drop.getItem();
                if (!stack.isEmpty() && stack.is(blockItem)) return drop;
            }
        }
        return drops.get(0);
    }

    /**
     * 清空该容器（含大箱子两半）的 BE 附件 —— 迁移后必须清，否则双箱两次破坏会各得一份。
     */
    private static void clearAssociated(ServerLevel level, BlockPos pos) {
        List<BlockPos> halves = DoubleChestPositions.find(level, pos);
        if (halves.isEmpty()) {
            clearOne(level, pos);
            return;
        }
        for (BlockPos half : halves) {
            clearOne(level, half);
        }
    }

    private static void clearOne(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            be.setData(WaterComponents.CONTAINER_FLUID_DATA.value(), ContainerFluidData.EMPTY);
        }
    }
}
