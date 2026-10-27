package com.qiqi.li.living.container;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * 容器级数据跨形态迁移的<b>共享小工具</b>（2026-10-20 从 {@code ContainerDataMigration} 抽出）。
 *
 * <h3>为什么抽到 L1</h3>
 * <p>原本这些助手住在 {@code living/domain/water/ContainerDataMigration}，等电力相位也要跨形态时，
 * 那个类就得 import power 域，触发 R3「领域互依赖」违规（今天真被
 * {@code tools/check_layers.py} 抓到过）。</p>
 *
 * <p>而这两个方法本身<b>不碰任何领域类型</b>（只涉及原版 Item / ItemStack / BlockEntity）。
 * 下沉到 L1 后，各领域的迁移类（流体、相位、将来的第三种）都能合法复用，
 * 各自只管自己的数据。</p>
 *
 * <h3>语义</h3>
 * <ul>
 *   <li>{@link #pickDrop}：破坏方块时，从掉落物里挑「代表这个方块本体」的那一件；</li>
 *   <li>{@link #placedItemInHand}：放置方块时，取放置者手上<b>确实是这个方块</b>的物品。</li>
 * </ul>
 * <p>两者的判据是同一个：<b>物品身份必须匹配</b>。别只按「手上有数据」「列表第一项」——
 * 前者会把数据错塞进别的方块，后者会把数据交给方块掉落的附加物。</p>
 */
public final class ContainerDrops {

    private ContainerDrops() {}

    /**
     * 从掉落物里挑「代表这个方块本体」的那一件。
     *
     * <p>规则：优先选物品 id 与该方块对应 BlockItem 一致的那件；否则退回第一件。
     * 破坏一个背包方块只会掉一个背包物品，所以绝大多数情况直接命中第一件；
     * 这条判据是给「方块同时掉本体 + 附加物」的模组留的余地。</p>
     */
    @Nullable
    public static ItemEntity pickDrop(List<ItemEntity> drops, BlockEntity be) {
        if (drops.isEmpty()) return null;
        // 方块 -> 它的 BlockItem（Block#asItem 对方块恒有返回值，无 BlockItem 时返回 air）
        Item blockItem = be.getBlockState().getBlock().asItem();
        if (blockItem != Items.AIR) {
            for (ItemEntity drop : drops) {
                ItemStack stack = drop.getItem();
                if (!stack.isEmpty() && stack.is(blockItem)) return drop;
            }
        }
        return drops.get(0);
    }

    /**
     * 放置者手上<b>确实是这次放置的那个方块</b>的物品（主手优先）。
     *
     * <p>校验物品身份是必须的：只按「手上有数据」匹配，会在玩家手持带数据的背包去放置
     * 另一个有 BE 的方块时把数据错塞过去。</p>
     *
     * <p>返回的是<b>物品本身</b>（而不是其中的数据），调用方各自从这件物品上取自己那份。</p>
     */
    @Nullable
    public static ItemStack placedItemInHand(Player player, Block placedBlock) {
        Item placedItem = placedBlock.asItem();
        if (placedItem == Items.AIR) return null;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty() || !stack.is(placedItem)) continue;
            return stack;
        }
        return null;
    }

    /**
     * 双箱拆半判定：被破坏的这半是双箱之一 ⇒ 返回<b>存活另一半</b>的位置；单箱 / 非箱子返回 {@code null}。
     *
     * <p>供各领域的迁移类在 {@code BlockDropsEvent} 时清存活半的容器级状态
     * （A6 定案 2026-10-27：拆合双箱，物品照原版分家，容器级状态不分摊不复制、重新开始）。
     * 判定下沉到 L1，因为箱子是原版方块、不碰任何领域类型。</p>
     */
    @Nullable
    public static BlockPos otherChestHalf(BlockEntity broken) {
        BlockState state = broken.getBlockState();
        if (!(state.getBlock() instanceof ChestBlock)) return null;
        if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null;
        Direction connected = ChestBlock.getConnectedDirection(state);
        return broken.getBlockPos().relative(connected);
    }
}
