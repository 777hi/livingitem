package com.qiqi.li.living.domain.beacon;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.container.TickableContainerContext;
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
 * <p>等级从<b>与 tick 同源</b>的容器上下文现读（{@link #readPyramidLevel} → {@link ContainerContexts#resolve}）
 * ⇒ 槽位映射与列宽跟 tick 完全一致；好处是<b>不依赖运行时缓存新鲜度</b>。</p>
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

        int level = readPyramidLevel(player, targetSlot);
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
     * 从<b>与 tick 同源</b>的容器上下文读信标下方一行 9 列柱高并判级（越界列视 0）。
     *
     * <p>{@code slot.getContainerSlot()} 是<b>容器内索引</b>（非菜单索引）——背包菜单下即
     * Inventory 索引、箱子菜单下即箱子槽位，与 tick 口径一致。</p>
     *
     * <p>⚠️ <b>2026-10-18 修（审查发现）</b>：此前本方法拿 {@code slot.container} 自己算、
     * 且<b>列宽硬编码 9</b>，而 tick 路径用 {@code context.getWidth()}（铁箱子族 108 槽的
     * 钻石 / 水晶 / 黑曜石在 {@code container_rules.json} 里显式配置为 <b>12 列</b>）
     * ⇒ <b>12 列容器里喂锭的等级判定与 tick 不一致</b> ⇒ 可能错误拒绝（正常操作被拦）或
     * 错误放行（写入的配置在 tick 时休眠）。修法：改用 {@link ContainerContexts#resolve}
     * 拿与 tick <b>同一个</b>上下文（同 size / width / getItem），宽度自然一致。</p>
     */
    static int readPyramidLevel(ServerPlayer player, Slot slot) {
        TickableContainerContext ctx = ContainerContexts.resolve(player, slot);
        if (ctx != null) {
            return BeaconPyramid.resolveLevel(
                // ⚠️ GUI 给的是物理槽；列扫描走逻辑槽（getItem/width）⇒ 必须映射
                // （下方 legacyReadPyramidLevel 是原版 Container 降级路径，物理=逻辑，不用映射）
                BeaconFunction.heightsFrom(ctx, columnSlotsFor(ctx,
                    ctx.toLogicalSlot(slot.getContainerSlot()))));
        }
        return legacyReadPyramidLevel(slot);
    }

    /**
     * 9 列槽位解析 —— <b>列宽取自上下文</b>（与 tick 同源；包级私有供单测）。
     *
     * <p>⚠️ 不许在这里写死 9：108 槽铁箱子是 12 列，写死会让喂锭与 tick 分叉。</p>
     */
    static int[] columnSlotsFor(ContainerContext ctx, int slot) {
        return BeaconFunction.resolveColumnSlots(slot, ctx.getSize(), ctx.getWidth());
    }

    /**
     * 回退：解析不出容器上下文时，按 <b>9 列</b>直读菜单容器（旧行为）。
     *
     * <p>只覆盖世界扫描不认的容器（战利品箱未开箱等）；原版箱 / 背包 / 末影箱 / 大箱
     * 都能被 {@link ContainerContexts#resolve} 解析 ⇒ 走不到这里。</p>
     */
    private static int legacyReadPyramidLevel(Slot slot) {
        Container container = slot.container;
        int index = slot.getContainerSlot();
        int size = container.getContainerSize();
        long[] heights = new long[BeaconPyramid.COLUMNS];
        for (int offset = -BeaconPyramid.CENTER; offset <= BeaconPyramid.CENTER; offset++) {
            int target = SlotResolver.resolve(index, new Pos2D(offset, 1), size, 9);
            if (target >= 0) {
                heights[offset + BeaconPyramid.CENTER] =
                    BeaconBaseBlocks.columnHeight(container.getItem(target));
            }
        }
        return BeaconPyramid.resolveLevel(heights);
    }
}
