package com.qiqi.li.living.domain.portal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.container.ContainerTickHook;
import com.qiqi.li.living.container.ContainerViewers;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.container.TickableContainerContext;
import com.qiqi.li.living.util.StaticCacheRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

/** 门面按真实容器和接收者菜单映射同步；空快照清除已显示的门。 */
@EventBusSubscriber
public final class PortalServerSync implements ContainerTickHook {
    private record ViewerKey(java.util.UUID player, int menu, String container) {}
    private static final Map<ViewerKey, PortalSnapshotPacket> SENT = new HashMap<>();

    static {
        StaticCacheRegistry.onServerStop(server -> SENT.clear());
    }

    @Override
    public void onTickStart(ContainerContext ctx, TickContext tick) {
        PortalDataAccess.getOrLoad(ctx);
    }

    @Override
    public void onWriteback(TickableContainerContext ctx, TickContext tick) {
        ContainerPortalData data = PortalDataAccess.getOrLoad(ctx);
        if (!data.isEmpty()) PortalDataAccess.persist(ctx, data);
        flush(ctx, tick);
    }

    public static void flush(ContainerContext ctx, TickContext tick) {
        String key = ctx.getCacheKey();
        if (key == null || ctx.getLevel() != null && ctx.getLevel().isClientSide) return;
        ContainerPortalData data = PortalDataAccess.getOrLoad(ctx);
        if (data.isEmpty() && SENT.keySet().stream().noneMatch(viewer -> viewer.container().equals(key))) return;
        var server = ctx.getLevel() == null
            ? net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer()
            : ctx.getLevel().getServer();
        if (server == null || server.getPlayerList() == null) return;
        List<ServerPlayer> viewers = ContainerViewers.of(server.getPlayerList().getPlayers(),
            key, ctx.getAssociatedBlockPositions());
        for (ServerPlayer player : viewers) send(ctx, data, player, false);
        SENT.entrySet().removeIf(entry -> {
            ViewerKey viewer = entry.getKey();
            if (!viewer.container().equals(key)) return false;
            boolean active = viewers.stream().anyMatch(player -> player.getUUID().equals(viewer.player())
                && player.containerMenu.containerId == viewer.menu());
            if (active) return false;
            ServerPlayer player = server.getPlayerList().getPlayer(viewer.player());
            if (player != null) clearPacket(player, entry.getValue());
            return true;
        });
    }

    public static PortalSnapshotPacket buildPacket(ContainerContext ctx, ContainerPortalData data,
                                                   ServerPlayer player) {
        Map<Integer, Integer> mapping = new HashMap<>();
        String key = ctx.getCacheKey();
        if (key == null) return null;
        var menu = player.containerMenu;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!slot.isActive()) continue;
            ContainerContext resolved = ContainerContexts.resolve(player, slot);
            if (resolved == null || !key.equals(resolved.getCacheKey())) continue;
            int logical = resolved.toLogicalSlot(slot.getContainerSlot());
            if (resolved.isValidSlot(logical)) mapping.put(i, logical);
        }
        if (mapping.isEmpty()) return null;
        return new PortalSnapshotPacket(menu.containerId, key, ctx.getWidth(), ctx.getSize(),
            data.portalSlots(), mapping);
    }

    private static void send(ContainerContext ctx, ContainerPortalData data, ServerPlayer player,
                             boolean force) {
        String containerKey = ctx.getCacheKey();
        ViewerKey key = new ViewerKey(player.getUUID(), player.containerMenu.containerId, containerKey);
        PortalSnapshotPacket previous = SENT.get(key);
        PortalSnapshotPacket packet = buildPacket(ctx, data, player);
        if (packet == null) {
            if (previous != null) {
                clearPacket(player, previous);
                SENT.remove(key);
            }
            return;
        }
        if (data.isEmpty() && previous == null && !force) return;
        player.connection.send(packet);
        if (data.isEmpty()) SENT.remove(key);
        else SENT.put(key, packet);
    }

    private static void clearPacket(ServerPlayer player, PortalSnapshotPacket before) {
        player.connection.send(new PortalSnapshotPacket(before.menuId(), before.containerKey(),
            before.width(), before.size(), java.util.Set.of(), Map.of()));
    }

    @SubscribeEvent
    public static void onOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Slot slot : event.getContainer().slots) {
            ContainerContext ctx = ContainerContexts.resolve(player, slot);
            if (ctx == null || ctx.getCacheKey() == null || !keys.add(ctx.getCacheKey())) continue;
            ContainerPortalData data = PortalDataAccess.getOrLoad(ctx);
            // GUI 反查缺宿主证明时只拒绝使用，不据此删除权威门面。
            if (PortalPositionResolver.resolve(ctx).isPresent()) {
                data = LivingPortalFunction.maintain(ctx, new TickContext(ctx));
            } else {
                data = ContainerPortalData.EMPTY;
            }
            send(ctx, data, player, true);
        }
    }

    @SubscribeEvent
    public static void onClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        int menu = event.getContainer().containerId;
        SENT.entrySet().removeIf(entry -> {
            ViewerKey key = entry.getKey();
            if (!key.player().equals(player.getUUID()) || key.menu() != menu) return false;
            clearPacket(player, entry.getValue());
            return true;
        });
    }
}
