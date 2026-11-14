package com.qiqi.li.living.container;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/**
 * 「<b>谁正在看这个容器</b>」的<b>唯一</b>判定入口（2026-10-23）。
 *
 * <h3>为什么抽它</h3>
 * <p>这个判定原本散在三条轨里，各自按 {@link ContainerKind} if-chain 分发：</p>
 * <ul>
 *   <li>{@code FluidFlowServerSync.viewersOf}（流体快照）</li>
 *   <li>{@code LivingItemRuntimeSync.flush}（运行时数据：冷却 / slotInfo / 过滤链）</li>
 *   <li>{@code LivingBrewingStandFunction} 里还内联一份位置匹配</li>
 * </ul>
 * <p>⇒ <b>加一种新容器形态时，每条轨都要各改一遍，漏一条就静默失效</b>。
 * 2026-10-23 一天内连踩两次：先修了流体那条（#7），漏了运行时那条，
 * 真机表现是「流体层正常渲染，但活漏斗高级 tooltip 不显示容器信息」——
 * 服务端攒了脏数据，只是没有观众可发。</p>
 *
 * <p>⇒ 收成一个方法后，「漏一条」这个错误类别<b>在结构上</b>消失：
 * 新形态只要在这里加一个分支，两条轨同时生效。</p>
 *
 * <h3>分派按「缓存键」而非 ctx 对象</h3>
 * <p>两条轨的输入原本不同（一条拿 {@code ctx}、一条拿 {@code cacheKey}），
 * 所以它们看起来毫不相干 —— 这正是能漏一条的原因。缓存键是两条轨共有的通货
 * （{@code ctx.getCacheKey()} 就是它），故按它分派；位置由调用方供给
 * （两条轨拿到位置的途径不同：ctx 带着，键要反查）。</p>
 *
 * <h3>⚠️ 隐私红线</h3>
 * <p>玩家态容器（背包 / 末影箱）<b>只发主人本人</b>，按 UUID 精确匹配。
 * 背包内容属于玩家私密信息，改动时必须保持。</p>
 */
public final class ContainerViewers {

    private ContainerViewers() {}

    /**
     * 收集该容器的查看者。
     *
     * @param players   在线玩家（两条轨都是从 {@code getPlayerList().getPlayers()} 拿的同一份）
     * @param cacheKey  **缓存键**（世界容器带 {@code <维度>|} 前缀；本方法内部会剥）
     * @param positions 该容器的位置集合；玩家态 / 实体 / 物品形态传空集合即可（用不上，
     *                  只有世界方块容器按位置认观众）
     * @return 查看者列表（可能为空 —— 空是正常答案：没人看就不发包）
     */
    public static List<ServerPlayer> of(java.util.Collection<ServerPlayer> players, String cacheKey,
                                       @Nullable Collection<BlockPos> positions) {
        List<ServerPlayer> out = new ArrayList<>(2);
        if (players == null || players.isEmpty() || cacheKey == null) return out;

        ContainerKind kind = ContainerKeys.kindOf(cacheKey);

        // ① 玩家态（背包 / 末影箱）：按 UUID 直发主人本人。
        //    末影箱另加 viewer 门控 —— 它每 tick 被 tick、不开 GUI 也直发，
        //    曾把客户端通用缓存里「当前打开的世界容器」冲掉（流体轨连修三次的同族 bug）。
        if (kind != null && kind.isOwnedByPlayer()) {
            UUID owner = ContainerKeys.ownerOf(cacheKey);
            if (owner == null) return out;   // 键形态异常 ⇒ 宁可不发
            for (ServerPlayer player : players) {
                if (!player.getUUID().equals(owner)) continue;
                if (kind == ContainerKind.ENDER_CHEST && !ContainerContexts.isViewingEnderChest(player)) {
                    continue;
                }
                out.add(player);
            }
            return out;
        }

        // ② 实体容器（船 / 矿车 / 带箱驴骡 / 女仆背包）：按**宿主实体身份**认观众，
        //    不按位置。实体每 tick 移动，且多个实体可以共格（女仆坐在箱船上）⇒
        //    位置匹配会把 A 的流体快照发给正在看 B 的玩家（2026-11-13 真机：
        //    女仆背包的水渲染到箱船界面上，而女仆自己的界面不渲染）。
        //    与 SimpleContainerContext.syncWorldContainer 的实体分支同源：问菜单里
        //    有没有真正属于该实体的**存储槽**，而不是问「它站在哪一格」。
        if (kind == ContainerKind.ENTITY) {
            UUID entityUuid = ContainerKeys.entityOf(cacheKey);
            if (entityUuid == null) return out;   // 键形态异常 ⇒ 宁可不发
            for (ServerPlayer player : players) {
                if (isViewingEntity(player, entityUuid)) out.add(player);
            }
            return out;
        }

        // ③ 物品形态容器（活背包）：没有世界位置 ⇒ 按位置匹配恒为空 ⇒ 永不发包。
        //    ⇒ 问「谁正开着它」：逐个在线玩家反查他菜单对应的容器 UUID（联动模块提供）。
        if (kind == ContainerKind.ITEM_FORM) {
            UUID containerUuid = ContainerKeys.containerUuidOf(cacheKey);
            if (containerUuid == null) return out;
            for (ServerPlayer player : players) {
                ItemFormContainers.Handle handle =
                    ItemFormContainers.handleOf(player.containerMenu, player);
                if (handle != null && containerUuid.equals(handle.containerUuid())) {
                    out.add(player);
                }
            }
            return out;
        }

        // ④ 世界方块 / 掉落物：按位置匹配（I-C5）。
        //    不要求 BE 是原版 Container —— 否则第三方方块容器永远匹配不上（真机：看不到水）。
        if (positions == null || positions.isEmpty()) return out;
        for (ServerPlayer player : players) {
            if (ContainerContexts.isViewingPositions(player, positions)) {
                out.add(player);
            }
        }
        return out;
    }

    /**
     * 该玩家是否正打开「以这个实体为宿主」的菜单。
     *
     * <p>宿主实体直接按<b>键里的 UUID</b> 从玩家所在世界取（与 {@code syncWorldContainer}
     * 拿 {@code ctx.getEntity()} 同源），不经过「菜单 → 实体」解析器 ⇒ 少一条会漏的接线。
     * 再用 {@link EntityContainers#menuPhysicalSlot} 逐槽确认「这个槽真的是它的存储格」
     * ⇒ 装备 / 饰品 / 隐藏格 / 别的实体槽都不会把玩家误算成观众。
     *
     * <p>实体 UUID 是唯一权威身份，不掺位置 ⇒ 本分支不读 {@code positions}
     * （与 {@link ContainerKind#ITEM_FORM} 同），实体移动、共格或换维度都不影响派发；
     * 实体不在玩家当前世界 ⇒ 取不到宿主 ⇒ 不发（换维度后旧世界的水不会串过来）。</p>
     */
    private static boolean isViewingEntity(ServerPlayer player, UUID entityUuid) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu) return false;
        if (!(player.level() instanceof ServerLevel level)) return false;
        Entity host = level.getEntity(entityUuid);
        if (host == null) return false;
        for (Slot slot : menu.slots) {
            if (EntityContainers.menuPhysicalSlot(host, menu, slot) >= 0) return true;
        }
        return false;
    }
}
