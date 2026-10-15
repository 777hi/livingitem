package com.qiqi.li.living.domain.beacon;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.interaction.InteractionHandler;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.transfer.SlotResolver;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 喂矿石锭：光标拿矿石锭右键活信标 ⇒ 消耗 1 锭写入效果配置（设计稿 §2.5「消耗制」）。
 *
 * <ul>
 *   <li><b>档位门控</b>：矿石解锁档位 &gt; 当前等级 ⇒ 拒绝（不消耗锭）；</li>
 *   <li><b>II 升级仅满级</b>（等级 4）：已在配置的矿石在 L1~3 喂入同样被拒；</li>
 *   <li><b>未激活（等级 0）</b>：全部拒绝（连铁锭也超档位）—— 先搭金字塔再配置；</li>
 *   <li><b>创造模式</b>：不消耗（照 {@code BonemealHandler} 惯例；光标物品由
 *       {@code GuiInteractionPacket} 的 carriedTag 回灌，故也能读到矿石种类）。</li>
 * </ul>
 *
 * <p>⚠️ handler 拿不到 {@code ContainerContext} ⇒ 等级直接<b>从菜单容器现读</b>
 * （{@link #readPyramidLevel}，与 tick 同口径的 9 列网格）；好处是<b>不依赖运行时缓存新鲜度</b>。</p>
 */
public class BeaconFeedHandler implements InteractionHandler {

    @Override
    public void handle(ServerPlayer player, Slot targetSlot) {
        ItemStack beacon = targetSlot.getItem();
        if (beacon.isEmpty() || !beacon.is(Items.BEACON) || !LivingItemManager.isLivingItem(beacon)) {
            return;
        }

        ItemStack carried = player.containerMenu.getCarried();
        OreType ore = OreType.fromIngot(carried);
        if (ore == null) {
            return;
        }

        int level = readPyramidLevel(targetSlot);
        BeaconConfig updated = BeaconEffects.feed(BeaconConfig.of(beacon), ore, level);
        if (updated == null) {
            return;   // 门控拒绝 ⇒ 不消耗（设计稿：低于档位的锭喂了会被拒）
        }

        BeaconConfig.set(beacon, updated);
        targetSlot.set(beacon);

        if (!player.isCreative()) {
            carried.shrink(1);
            if (carried.isEmpty()) {
                player.containerMenu.setCarried(ItemStack.EMPTY);
            }
        }
        player.containerMenu.broadcastChanges();
    }

    /** 规则谓词：触发器是 5 种矿石锭之一（通配 trigger + predicate，见 {@code interaction_rules.json}）。 */
    public static boolean isOreIngot(ItemStack trigger, ItemStack target) {
        return OreType.fromIngot(trigger) != null;
    }

    /**
     * 从菜单容器现读信标下方一行 9 列柱高并判级（9 列网格，越界视 0）。
     *
     * <p>{@code targetSlot.getContainerSlot()} 是<b>容器内索引</b>（非菜单索引）——
     * 背包菜单下即 Inventory 索引、箱子菜单下即箱子槽位，与 tick 口径一致。</p>
     */
    static int readPyramidLevel(Slot targetSlot) {
        Container container = targetSlot.container;
        int slot = targetSlot.getContainerSlot();
        int size = container.getContainerSize();
        long[] heights = new long[BeaconPyramid.COLUMNS];
        for (int offset = -BeaconPyramid.CENTER; offset <= BeaconPyramid.CENTER; offset++) {
            int target = SlotResolver.resolve(slot, new Pos2D(offset, 1), size, 9);
            if (target >= 0) {
                heights[offset + BeaconPyramid.CENTER] =
                    BeaconBaseBlocks.columnHeight(container.getItem(target));
            }
        }
        return BeaconPyramid.resolveLevel(heights);
    }
}
