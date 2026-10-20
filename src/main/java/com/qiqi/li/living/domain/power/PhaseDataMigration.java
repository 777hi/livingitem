package com.qiqi.li.living.domain.power;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import com.qiqi.li.living.container.ContainerDrops;
import com.qiqi.li.living.transfer.ContainerCompatibilityConfig;

/**
 * **电力相位**容器级数据的形态迁移：方块 ⇄ 物品（2026-10-20）。
 *
 * <h3>解决什么</h3>
 * <p>相位账本（{@link ContainerPowerData} / {@code SignalTracker}）是纯内存缓存，
 * 账本一死所有振荡器<b>从零重锁</b> ⇒ 多路之间的相对相位被「重载时刻」洗牌，
 * 玩家调好的满相布局（n = P）失效。BE 附件 {@link PowerComponents#CONTAINER_PHASE_SNAPSHOT}
 * 本来就解决「退出重进」这一种死法，但破坏方块时 BE 被移除 ⇒ 附件随之消失，
 * 而精妙背包 / 潜影盒的物品内容存在别处 ⇒ 内容保留、相位丢失。</p>
 *
 * <h3>与流体迁移的关系</h3>
 * <p>同一个病、同两个挂点、同一条 {@code contentRetaining} 判据 —— 但<b>搬法相反</b>：
 * {@code ContainerFluidData} 是可变对象且与内存表共享引用 ⇒ 必须 {@code snapshot()}；
 * {@link PhaseSnapshot} 是不可变 record，{@code ContainerPhaseWriteback} 每 tick 写入的是
 * {@link PhaseSnapshot#capture} 产生的新对象 ⇒ 直接搬即可。</p>
 *
 * <p>⚠️ 曾因把这段写在 {@code living/domain/water/ContainerDataMigration} 里而 import 了本域，
 * 触发 {@code check_layers.py} 的 R3「领域互依赖」违规 ⇒ 拆成两个类。
 * 共享的掉落物/手持物品判据在 {@link ContainerDrops}（L1，不碰领域类型）。</p>
 *
 * <h3>⚠️ 未解决：大箱子两半各得一份</h3>
 * <p>{@code ContainerPhaseWriteback} 每 tick 把<b>同一个</b> snapshot 写给所有关联 BE ⇒
 * 两半附件指向同一对象。破坏一半时只能清掉「那一个 BE」的引用（拿不到另一半的 BE），
 * 于是破坏另一半时会再搬一次 ⇒ <b>两半各得一份</b>。</p>
 *
 * <p>流体那边靠 {@code data.clearAll()} 就地清<b>内存共享对象</b>守恒，相位没有这个载体
 * （record 不可变）。已知会，先去修：要么让两半只写一个 BE，要么在
 * {@code BlockDropsEvent} 时用「另一半是否还在」判先手（先破的搬、后破的不搬）。</p>
 */
@EventBusSubscriber
public final class PhaseDataMigration {

    private PhaseDataMigration() {}

    /** 破坏方块：BE 附件 → 掉落物组件。 */
    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        BlockEntity be = event.getBlockEntity();
        if (be == null) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        PhaseSnapshot phase = be.getData(PowerComponents.CONTAINER_PHASE_SNAPSHOT.value());
        if (phase == null || PhaseSnapshot.EMPTY.equals(phase)) return;

        ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        if (id == null || !ContainerCompatibilityConfig.retainsContentsOnBreak(id)) {
            return;
        }

        // 稳态无振荡器时 capture 产 EMPTY ⇒ 这一层挡掉绝大多数方块破坏，零开销
        var target = ContainerDrops.pickDrop(event.getDrops(), be);
        if (target == null) return;

        // 不可变 record ⇒ 直接搬，不需要 snapshot()
        target.getItem().set(PowerComponents.CONTAINER_PHASE_SNAPSHOT_ITEM.get(), phase);
    }

    /** 放置方块：手持物品的组件 → 新 BE 附件。 */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity placer = event.getEntity();
        if (!(placer instanceof Player player)) return;

        BlockEntity be = level.getBlockEntity(event.getPos());
        if (be == null) return;

        ItemStack source = ContainerDrops.placedItemInHand(player, event.getPlacedBlock().getBlock());
        if (source == null) return;

        PhaseSnapshot phase = source.get(PowerComponents.CONTAINER_PHASE_SNAPSHOT_ITEM.get());
        if (phase != null && !PhaseSnapshot.EMPTY.equals(phase)) {
            be.setData(PowerComponents.CONTAINER_PHASE_SNAPSHOT.value(), phase);
        }
    }
}
