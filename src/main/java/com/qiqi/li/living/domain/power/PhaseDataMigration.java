package com.qiqi.li.living.domain.power;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import javax.annotation.Nullable;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerDrops;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.transfer.ContainerCompatibilityConfig;
import com.qiqi.li.logging.ModLog;

/**
 * **电力相位**容器级数据的形态迁移：方块 ⇄ 物品（2026-10-20）。
 *
 * <h3>解决什么</h3>
 * <p>相位账本（{@link ContainerPowerData} / {@code SignalTracker}）是纯内存缓存，
 * 账本一死所有振荡器<b>从零重锁</b> ⇒ 多路之间的相对相位被「重载时刻」洗牌，
 * 玩家调好的满相布局（n = P）失效。BE 附件 {@link PowerComponents#CONTAINER_PHASE_SNAPSHOT}
 * 本来就解决「退出重进」这一种死法，但破坏方块时 BE 被移除 ⇒ 附件随之消失，
 * 而某些容器（潜影盒等）的物品内容存在别处 ⇒ 内容保留、相位丢失。</p>
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
 * <h3>大箱子两半各得一份 —— 已由 A6 拆半清空消解（2026-10-27）</h3>
 * <p>{@code ContainerPhaseWriteback} 每 tick 把<b>同一个</b> snapshot 写给所有关联 BE ⇒
 * 两半附件指向同一对象。破坏一半时只能清掉「那一个 BE」的引用（拿不到另一半的 BE），
 * 于是破坏另一半时会再搬一次 ⇒ <b>两半各得一份</b>。</p>
 *
 * <p>流体那边靠 {@code data.clearAll()} 就地清<b>内存共享对象</b>守恒，相位没有这个载体
 * （record 不可变）。A6 定案后：双箱拆半时 {@link #clearPhaseOnChestSplit} 显式清空两半
 * 的相位附件 ⇒ 后破的另一半无旧快照可搬，问题消解。</p>
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

        // A6（2026-10-27 定案）：双箱拆半 ⇒ 相位不分摊不复制，按新布局重新识别信号。
        // 存活半的旧快照附件若不清，会经「回填」类路径把拆半前的锁相带回新结构。
        clearPhaseOnChestSplit(level, be);

        // 稳态无振荡器时 capture 产 EMPTY ⇒ 这一层挡掉绝大多数方块破坏，零开销
        PhaseSnapshot phase = be.getData(PowerComponents.CONTAINER_PHASE_SNAPSHOT.value());
        if (phase == null || PhaseSnapshot.EMPTY.equals(phase)) return;
        if (!retainsContents(be)) return;

        var target = ContainerDrops.pickDrop(event.getDrops(), be);
        if (target == null) return;

        moveOut(be, target.getItem());
    }

    /** 双箱拆半 ⇒ 清<b>存活半</b>的相位快照附件（A6：红电重新识别信号，不搬旧周期）。
     *  被破坏半的 BE 随方块移除、附件随之消失，无需处理。 */
    static void clearPhaseOnChestSplit(ServerLevel level, BlockEntity broken) {
        BlockPos otherPos = ContainerDrops.otherChestHalf(broken);
        if (otherPos == null) return;

        BlockEntity other = level.getBlockEntity(otherPos);
        if (other != null) {
            other.setData(PowerComponents.CONTAINER_PHASE_SNAPSHOT.value(), PhaseSnapshot.EMPTY);
        }
        ModLog.CONTAINER.info("[相位] 双箱拆半：存活半相位快照已清空，按新布局重新锁相（other={})", otherPos);
    }

    /**
     * 把宿主上的相位快照搬到 {@code target} 物品上 —— <b>破坏路径与第三方交接路径共用</b>
     * （2026-10-21，见 {@link com.qiqi.li.living.container.ContainerHandoff}）。
     *
     * <p>⚠️ 与流体相反：{@link PhaseSnapshot} 是不可变 record，{@code ContainerPhaseWriteback}
     * 每 tick 写入的是 {@code capture()} 产生的新对象 ⇒ <b>直接搬，不需要 snapshot()</b>。</p>
     *
     * @return 是否真的搬了
     */
    public static boolean moveOut(IAttachmentHolder host, ItemStack target) {
        if (target.isEmpty()) return false;
        if (!retainsContents(host)) return false;

        PhaseSnapshot phase = host.getData(PowerComponents.CONTAINER_PHASE_SNAPSHOT.value());
        if (phase == null || PhaseSnapshot.EMPTY.equals(phase)) return false;

        target.set(PowerComponents.CONTAINER_PHASE_SNAPSHOT_ITEM.get(), phase);
        return true;
    }

    /** I-C9 判据：该容器破坏/被搬走后是否仍保有物品内容。 */
    private static boolean retainsContents(IAttachmentHolder host) {
        if (!(host instanceof BlockEntity be)) return false;
        ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        return id != null && ContainerCompatibilityConfig.retainsContentsOnBreak(id);
    }

    /**
     * 反方向：把物品上的相位快照恢复进 {@code host} —— <b>放置路径与第三方交接路径共用</b>。
     *
     * <p>{@link PhaseSnapshot} 是不可变 record ⇒ 直接搬，不需要 {@code snapshot()}。</p>
     *
     * <p>⚠️⚠️ <b>只写附件是无效的</b>（2026-10-21 真机日志定位，与流体同源）：
     * 相位的**唯一真相源是内存账本** {@link ContainerPowerData}（{@code CONTAINER_DATA} 按 cacheKey），
     * {@code ContainerPhaseWriteback} 每 tick 用 {@link PhaseSnapshot#capture} <b>重新生成</b>快照
     * 覆盖附件 ⇒ 只写附件 = 下一 tick 被空账本盖掉。
     * 所以必须先把快照<b>还原进账本</b>（{@link PhaseSnapshot#restoreInto}），附件只是持久化副本。</p>
     */
    public static void moveIn(IAttachmentHolder host, ItemStack source) {
        if (!retainsContents(host)) return;

        PhaseSnapshot phase = source.get(PowerComponents.CONTAINER_PHASE_SNAPSHOT_ITEM.get());
        if (phase == null || PhaseSnapshot.EMPTY.equals(phase)) return;

        ContainerContext ctx = resolveContextOf(host);
        if (ctx != null) {
            ContainerPowerData power = ContainerLivingItemHandler
                .getOrCreateContainerData(ctx, ContainerPowerData.KEY);
            long clock = host instanceof BlockEntity be && be.getLevel() instanceof ServerLevel level
                ? level.getGameTime() : power.currentTick();
            int restored = phase.restoreInto(power, clock);
            ModLog.CONTAINER.info("[交接] 相位已还原进账本：entries={} clock={}", restored, clock);
        } else {
            ModLog.CONTAINER.warn("[交接] 相位恢复时拿不到容器上下文 ⇒ 只写附件，"
                + "下一 tick 可能被空账本覆盖：{}", host.getClass().getSimpleName());
        }

        host.setData(PowerComponents.CONTAINER_PHASE_SNAPSHOT.value(), phase);
    }

    /** 从宿主反查与 tick 路径同源的容器上下文（拿不到返回 null）。 */
    @Nullable
    private static ContainerContext resolveContextOf(IAttachmentHolder host) {
        if (!(host instanceof BlockEntity be)) return null;
        if (!(be.getLevel() instanceof ServerLevel level)) return null;
        return ContainerLivingItemHandler.resolveContextAt(level, be.getBlockPos(), null);
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

        moveIn(be, source);
    }
}
