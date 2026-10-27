package com.qiqi.li.living.domain.water;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerDrops;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.ContainerUuids;
import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.transfer.ContainerCompatibilityConfig;
import com.qiqi.li.logging.ModLog;


/**
 * **流体**容器级数据的形态迁移：方块 ⇄ 物品（2026-10-19）。
 *
 * <p>⚠️ 2026-10-20 拆分：原本这个类同时搬流体与电力相位，结果 import 了 power 域，
 * 触发 {@code check_layers.py} 的 R3「领域互依赖」违规。现在相位迁移独立成
 * {@code living/domain/power/PhaseDataMigration}，共享的掉落物/手持物品判据
 * 下沉到 {@link ContainerDrops}（L1，不碰任何领域类型）。
 * ⇒ 每个领域只管自己的数据，领域之间不再互相认识。</p>
 *
 * <h3>解决什么</h3>
 * <p>真机：「背包里的活流体，在背包变成物体形态后就没有了」。</p>
 *
 * <p>容器级流体数据挂在 <b>BE 附件</b> {@link WaterComponents#CONTAINER_FLUID_DATA} 上；
 * 破坏方块时 BE 被移除 ⇒ 附件随之消失。而某些容器（潜影盒等）的<b>物品内容</b>存在别处
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

        // A6（2026-10-27 定案）：双箱拆半 ⇒ 物品照原版分家，流体源不分摊不复制、重新开始。
        // 原版箱子不是 contentRetaining ⇒ 下面的 moveOut 会早退，存活半的附件残留会被
        // 新键的「附件回填」复活成旧源 ⇒ 拆半时清掉存活半（被破坏半随 BE 移除，无需处理）。
        clearFluidOnChestSplit(level, be);

        if (!retainsContents(be)) return;   // 绝大多数方块破坏在这里零开销早退

        ItemEntity target = ContainerDrops.pickDrop(event.getDrops(), be);
        if (target == null) return;   // 没有可承载的物品（如被精准采集以外的破坏）⇒ 数据随 BE 消失

        if (!moveOut(be, target.getItem())) return;
        clearOne(level, be.getBlockPos());
    }

    /**
     * 双箱拆半 ⇒ 清<b>存活半</b>的流体附件（A6：源是容器级状态，不分摊不复制，重新倒水）。
     * 被破坏半的 BE 随方块移除、附件随之消失，无需处理。单箱 / 非箱子零开销早退。
     */
    static void clearFluidOnChestSplit(ServerLevel level, BlockEntity broken) {
        BlockPos otherPos = ContainerDrops.otherChestHalf(broken);
        if (otherPos == null) return;

        BlockEntity other = level.getBlockEntity(otherPos);
        if (other != null) {
            other.setData(WaterComponents.CONTAINER_FLUID_DATA.value(), ContainerFluidData.EMPTY);
        }
        ModLog.CONTAINER.info("[流体] 双箱拆半：存活半流体源已清空，按新布局重新开始（other={})", otherPos);
    }

    /**
     * 把宿主上的容器级流体搬到 {@code target} 物品上 —— <b>破坏路径与第三方交接路径共用</b>
     * （2026-10-21，见 {@link com.qiqi.li.living.container.ContainerHandoff}）。
     *
     * <p>⚠️ **两条路径必须走同一份实现**：语义一旦分叉，就会出现「破坏搬得对、拾取搬得歪」
     * 这种最难查的 bug。</p>
     *
     * <p>⚠️ 只对「破坏后仍保有物品内容」的容器迁移（2026-10-20，用户定案）。
     * 原版箱子 / 铁箱子 / 炼金箱子破坏后<b>内容本来就消失了</b>（它们不是潜影盒）；
     * 若仍然把流体搬到掉落物上，重新放置就会得到「空箱子但有水」——
     * 半保留状态比丢水更让人困惑。让流体和内容一起消失，语义才一致。
     * 谁保留内容由规则数据声明（{@code container_rules.json} 的 {@code contentRetaining}），
     * 因为没有任何通用 API 能回答这个问题（各容器各走各的组件）。</p>
     *
     * @return 是否真的搬了（无数据 / 不该搬 / 目标为空 ⇒ false）
     */
    public static boolean moveOut(IAttachmentHolder host, ItemStack target) {
        if (target.isEmpty()) {
            ModLog.CONTAINER.info("[交接] moveOut 早退：目标物品为空");
            return false;
        }
        if (!retainsContents(host)) {
            ModLog.CONTAINER.info("[交接] moveOut 早退：宿主未声明 contentRetaining（{}）",
                host == null ? "null" : host.getClass().getSimpleName());
            return false;
        }

        // ① 身份优先（2026-10-22，D-container-13）：有容器 UUID ⇒ 只搬身份，
        //    数据留在 SavedData 按 UUID 存着，**零搬运**。这是活背包的常态路径。
        UUID containerUuid = ContainerUuids.ofHost(host);
        if (containerUuid != null) {
            ContainerUuids.stripForCopy(target);   // 防目标已带旧身份（复制共享的坑）
            target.set(LivingComponents.CONTAINER_UUID.get(), containerUuid);
            ModLog.CONTAINER.info("[交接] 只搬容器 UUID：{}（数据留在 SavedData）", containerUuid);
            return true;
        }

        // ② 兜底：没有身份（老存档 / 未活化）⇒ 搬数据快照，走组件过渡
        ContainerFluidData data = host.getData(WaterComponents.CONTAINER_FLUID_DATA);
        if (data == null || data.isEmpty()) {
            ModLog.CONTAINER.info("[交接] moveOut 早退：宿主无流体数据（{}）",
                host.getClass().getSimpleName());
            return false;
        }

        // ① 交给物品的必须是**快照**：组件存的是对象引用，而 data 是可变对象（每 tick 重算 flows）
        //    ⇒ 直接传 this 会让物品与 BE 附件共享同一对象，后续 tick 的改动漏进物品里。
        target.set(WaterComponents.CONTAINER_FLUID_DATA_ITEM.get(), data.snapshot());

        // ② 就地清空**对象本身**：大箱子两半在 tick 期间共享同一实例 ⇒ 一次清掉两半，
        //    不必按位置去找另一半（BlockDropsEvent 触发时方块可能已被移除 ⇒ 按方块状态找会漏）。
        //    漏清的后果是破坏另一半时再迁移一次 ⇒ 复制。
        data.clearAll();
        return true;
    }

    /** I-C9 判据：该容器破坏/被搬走后是否仍保有物品内容。 */
    private static boolean retainsContents(IAttachmentHolder host) {
        if (!(host instanceof BlockEntity be)) return false;
        ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        return id != null && ContainerCompatibilityConfig.retainsContentsOnBreak(id);
    }

    /**
     * 反方向：把物品上的流体组件恢复进 {@code host} —— <b>放置路径与第三方交接路径共用</b>。
     *
     * <p>⚠️ 必须给**快照**而不是原对象：创造模式下放置不消耗物品，同一个 {@link ItemStack}
     * 可以连续放置多次 ⇒ 多个 BE 会共享同一份可变数据（水在几个背包里同时出现）。</p>
     *
     * <p>⚠️⚠️ <b>只写附件是无效的</b>（2026-10-21 真机日志定位）：容器级数据的**唯一真相源是内存表**
     * （{@code CONTAINER_DATA}，按 cacheKey），{@code ContainerFluidWriteback} 每 tick 用内存表里的
     * 对象<b>覆盖</b>附件。放置后内存表里往往已经有一个**空对象**（破坏时只 {@code clearAll}
     * 清了内容、没删键 ⇒ {@code getOrCreateFluidData} 的「附件回填」分支根本不会走）
     * ⇒ 只写附件 = 下一 tick 被空对象盖掉，水凭空消失。
     * 所以必须<b>同一个对象同时放进内存表和附件</b>。</p>
     */
    public static void moveIn(IAttachmentHolder host, ItemStack source) {
        if (!retainsContents(host)) {
            ModLog.CONTAINER.info("[交接] moveIn 早退：宿主未声明 contentRetaining（{}）",
                host == null ? "null" : host.getClass().getSimpleName());
            return;
        }

        // ① 身份优先（2026-10-22，D-container-13）：物品带容器 UUID ⇒ 写进宿主附件即可，
        //    数据在 SavedData 按 UUID 存着，下一 tick 的 getOrCreateFluidData 会从那里回填
        //    （它先问 ctx.getContainerUuid()）。**零数据搬运**。
        UUID containerUuid = ContainerUuids.ofItem(source);
        if (containerUuid != null) {
            host.setData(LivingComponents.CONTAINER_UUID_ATTACHMENT.get(), containerUuid);
            ModLog.CONTAINER.info("[交接] 恢复容器 UUID：{}（数据留在 SavedData）", containerUuid);
            return;
        }

        // ② 兜底：没有身份 ⇒ 搬组件里的数据快照（老存档）
        ContainerFluidData fluid = source.get(WaterComponents.CONTAINER_FLUID_DATA_ITEM.get());
        if (fluid == null || fluid.isEmpty()) {
            ModLog.CONTAINER.info("[交接] moveIn 早退：物品上没有流体组件（item={}）", source.getItem());
            return;
        }

        ContainerFluidData restored = fluid.snapshot();

        // ① 内存表：tick 路径的唯一数据源（writeback 每 tick 从这里写到附件）
        ContainerContext ctx = resolveContextOf(host);
        if (ctx != null) {
            ContainerLivingItemHandler.putContainerData(ctx, ContainerFluidData.KEY, restored);
        } else {
            ModLog.CONTAINER.warn("[交接] 恢复时拿不到容器上下文（能力面未就绪）⇒ 只写附件，"
                + "下一 tick 可能被内存表覆盖：{}", host.getClass().getSimpleName());
        }

        // ② 附件：存档级持久化（容器没被 tick 时也能留住）
        host.setData(WaterComponents.CONTAINER_FLUID_DATA.value(), restored);

        ModLog.CONTAINER.info("[交接] 流体已恢复：inMemory={} bytes-keys={}",
            ctx != null, restored.getGeneratedSources().size());
    }

    /** 从宿主反查与 tick 路径同源的容器上下文（拿不到返回 null）。 */
    @Nullable
    private static ContainerContext resolveContextOf(IAttachmentHolder host) {
        if (!(host instanceof BlockEntity be)) return null;
        if (!(be.getLevel() instanceof ServerLevel level)) return null;
        return ContainerLivingItemHandler.resolveContextAt(level, be.getBlockPos(), null);
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
        ItemStack source = ContainerDrops.placedItemInHand(player, event.getPlacedBlock().getBlock());
        if (source == null) return;

        // 与第三方交接路径共用同一份恢复逻辑（快照语义见 moveIn 的 javadoc）
        moveIn(be, source);
    }

    private static void clearOne(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            be.setData(WaterComponents.CONTAINER_FLUID_DATA.value(), ContainerFluidData.EMPTY);
        }
    }
}
