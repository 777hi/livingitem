package com.qiqi.li.living.container;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

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
     * @param positions 该容器的位置集合；物品形态 / 玩家态传空集合即可（用不上）
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

        // ② 物品形态容器（活背包）：没有世界位置 ⇒ 按位置匹配恒为空 ⇒ 永不发包。
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

        // ③ 世界方块 / 实体 / 掉落物：按位置匹配（I-C5）。
        //    不要求 BE 是原版 Container —— 否则第三方方块容器永远匹配不上（真机：看不到水）。
        if (positions == null || positions.isEmpty()) return out;
        for (ServerPlayer player : players) {
            if (ContainerContexts.isViewingPositions(player, positions)) {
                out.add(player);
            }
        }
        return out;
    }
}
