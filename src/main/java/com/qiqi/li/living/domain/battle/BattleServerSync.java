package com.qiqi.li.living.domain.battle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.container.ContainerViewers;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.Vec3;

/**
 * 战局快照下发：实体快照（敌军 lane+progress）+ 事件流 + 菜单槽位映射 → 观众。
 *
 * <p>观众唯一判定入口 {@link ContainerViewers#of}；无人看就不发包
 * （但事件照样 drain 丢弃，保证队列不积压）。每 4 tick 由战局编排调用一次。</p>
 */
public final class BattleServerSync {

    private BattleServerSync() {}

    public static void flush(ContainerContext ctx, BattleArenaData data) {
        if (ctx.getLevel() == null || ctx.getLevel().isClientSide) return;
        String key = ctx.getCacheKey();
        if (key == null || !key.equals(data.battleKey())) return;
        MinecraftServer server = ctx.getLevel().getServer();
        if (server == null || server.getPlayerList() == null) return;
        if (!(ctx.getLevel() instanceof ServerLevel arena)) return;

        List<BattleSnapshotPacket.EnemyState> enemies = collectEnemies(arena, data);
        // 事件先 drain：无观众时丢弃（fire-and-forget，不积压）
        List<BattleEvent> events = BattleEventFeed.drain(key);
        pruneEnemies(arena, data);
        if (enemies.isEmpty() && events.isEmpty()) return;

        List<ServerPlayer> viewers = ContainerViewers.of(
            server.getPlayerList().getPlayers(), key, ctx.getAssociatedBlockPositions());
        if (viewers.isEmpty()) return;

        for (ServerPlayer player : viewers) {
            Map<Integer, Integer> menuSlots = buildMenuMapping(ctx, key, player);
            // 事件定位不了 GUI 格 ⇒ 本拍不带事件（演出可丢，不猜）
            List<BattleEvent> sendEvents = menuSlots.isEmpty() ? List.of() : events;
            player.connection.send(new BattleSnapshotPacket(
                player.containerMenu.containerId, key, data.lanes(), data.depth(),
                enemies, menuSlots, sendEvents));
        }
    }

    private static List<BattleSnapshotPacket.EnemyState> collectEnemies(ServerLevel arena, BattleArenaData data) {
        List<BattleSnapshotPacket.EnemyState> out = new ArrayList<>();
        for (UUID uuid : data.enemies()) {
            var entity = arena.getEntity(uuid);
            if (entity == null || !entity.isAlive()) continue;
            boolean helmet = entity instanceof net.minecraft.world.entity.LivingEntity living
                && !living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty();
            out.add(new BattleSnapshotPacket.EnemyState(
                entity.getUUID().getMostSignificantBits(), entity.getUUID().getLeastSignificantBits(),
                (float) ArenaPlots.rowOf(entity.getZ(), data.plotPos(), data.lanes()),
                (float) ArenaPlots.colOf(entity.getX(), data.plotPos(), data.depth()),
                helmet));
        }
        return out;
    }

    /** 实体已消失（区块卸载 / discard）的敌军出册。 */
    private static void pruneEnemies(ServerLevel arena, BattleArenaData data) {
        data.enemies().removeIf(uuid -> {
            var entity = arena.getEntity(uuid);
            return entity == null || !entity.isAlive();
        });
    }

    /** 菜单槽位 → 逻辑槽位（Portal buildPacket 同构：按真实容器归属过滤）。 */
    private static Map<Integer, Integer> buildMenuMapping(ContainerContext ctx, String key, ServerPlayer player) {
        Map<Integer, Integer> mapping = new HashMap<>();
        var menu = player.containerMenu;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!slot.isActive()) continue;
            ContainerContext resolved = ContainerContexts.resolve(player, slot);
            if (resolved == null || !key.equals(resolved.getCacheKey())) continue;
            int logical = resolved.toLogicalSlot(slot.getContainerSlot());
            if (resolved.isValidSlot(logical)) mapping.put(i, logical);
        }
        return mapping;
    }
}
