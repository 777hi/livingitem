package com.qiqi.li.living.domain.water;

import java.util.List;

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
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import com.qiqi.li.living.container.ContainerDrops;
import com.qiqi.li.living.transfer.ContainerCompatibilityConfig;


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

        // ⚠️ 只对「破坏后仍保有物品内容」的容器迁移（2026-10-20，用户定案）。
        //
        // 原版箱子 / 铁箱子 / 炼金箱子破坏后<b>内容本来就消失了</b>（它们不是潜影盒）；
        // 若仍然把流体搬到掉落物上，重新放置就会得到「空箱子但有水」——
        // 半保留状态比丢水更让人困惑。让流体和内容一起消失，语义才一致。
        //
        // 谁保留内容由规则数据声明（container_rules.json 的 contentRetaining），
        // 因为没有任何通用 API 能回答这个问题（潜影盒 / 精妙 / 无尽箱子各走各的组件）。
        ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        if (id == null || !ContainerCompatibilityConfig.retainsContentsOnBreak(id)) {
            return;
        }

        ItemEntity target = ContainerDrops.pickDrop(event.getDrops(), be);
        if (target == null) return;   // 没有可承载的物品（如被精准采集以外的破坏）⇒ 数据随 BE 消失

        // ① 交给掉落物的必须是**快照**：组件存的是对象引用，而 data 是可变对象（每 tick 重算 flows）
        //    ⇒ 直接传 this 会让物品与 BE 附件共享同一对象，后续 tick 的改动漏进物品里。
        ItemStack stack = target.getItem();
        stack.set(WaterComponents.CONTAINER_FLUID_DATA_ITEM.get(), data.snapshot());

        // ② 就地清空**对象本身**：大箱子两半在 tick 期间共享同一实例 ⇒ 一次清掉两半，
        //    不必按位置去找另一半（BlockDropsEvent 触发时方块可能已被移除 ⇒ 按方块状态找会漏）。
        //    漏清的后果是破坏另一半时再迁移一次 ⇒ 复制。
        data.clearAll();
        clearOne(level, be.getBlockPos());

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

        // ① 流体：必须给**快照**而不是原对象 —— 创造模式下放置不消耗物品，同一个 ItemStack
        //    可以连续放置多次 ⇒ 多个 BE 会共享同一份可变数据（水在几个背包里同时出现）。
        ContainerFluidData fluid = source.get(WaterComponents.CONTAINER_FLUID_DATA_ITEM.get());
        if (fluid != null && !fluid.isEmpty()) {
            be.setData(WaterComponents.CONTAINER_FLUID_DATA.value(), fluid.snapshot());
        }

    }

    private static void clearOne(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            be.setData(WaterComponents.CONTAINER_FLUID_DATA.value(), ContainerFluidData.EMPTY);
        }
    }
}
