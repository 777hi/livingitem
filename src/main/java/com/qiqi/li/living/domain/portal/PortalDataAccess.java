package com.qiqi.li.living.domain.portal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerDataSavedData;
import com.qiqi.li.living.container.ContainerKeys;
import com.qiqi.li.living.container.ContainerLivingItemHandler;

import net.minecraft.world.entity.player.Player;

/** 门域的热表、持久回填与写回入口；清门仅操作本域 key。 */
public final class PortalDataAccess {
    private PortalDataAccess() {}

    /** 授权前只读候选，不建立热表或写入持久状态。 */
    public static ContainerPortalData peekSaved(ContainerContext ctx) {
        ContainerPortalData current = ContainerLivingItemHandler.peekExistingContainerData(ctx, ContainerPortalData.KEY);
        if (current != null) return current;
        UUID uuid = ctx.getContainerUuid();
        if (uuid != null) {
            ContainerPortalData saved = ContainerDataSavedData.get().get(uuid, ContainerPortalData.KEY);
            return saved == null ? ContainerPortalData.EMPTY : saved;
        }
        Player owner = playerOwner(ctx);
        if (owner != null && owner.hasData(PortalComponents.CONTAINER_PORTAL_DATA_PLAYER)) {
            var data = owner.getData(PortalComponents.CONTAINER_PORTAL_DATA_PLAYER).get(ctx.getContainerKey());
            return data == null ? ContainerPortalData.EMPTY : data;
        }
        for (var host : ctx.getDataHosts()) {
            if (!host.hasData(PortalComponents.CONTAINER_PORTAL_DATA)) continue;
            var data = host.getData(PortalComponents.CONTAINER_PORTAL_DATA);
            if (data != null && !data.isEmpty()) return data;
        }
        return ContainerPortalData.EMPTY;
    }

    public static ContainerPortalData getOrLoad(ContainerContext ctx) {
        return getOrLoad(ctx, ctx.getContainerUuid());
    }

    /** 显式身份用于已解析 UUID 的交接上下文，不从查看者猜容器身份。 */
    public static ContainerPortalData getOrLoad(ContainerContext ctx, @Nullable UUID uuid) {
        ContainerPortalData current = ContainerLivingItemHandler.peekContainerData(ctx, ContainerPortalData.KEY);
        if (current != null) return current;

        if (uuid != null) {
            ContainerPortalData saved = ContainerDataSavedData.get().get(uuid, ContainerPortalData.KEY);
            ContainerPortalData restored = saved == null ? ContainerPortalData.EMPTY : saved.snapshot();
            ContainerLivingItemHandler.putContainerData(ctx, ContainerPortalData.KEY, restored);
            return restored;
        }

        Player owner = playerOwner(ctx);
        if (uuid == null && owner != null) {
            Map<String, ContainerPortalData> saved = owner.getData(PortalComponents.CONTAINER_PORTAL_DATA_PLAYER);
            ContainerPortalData data = saved == null ? null : saved.get(ctx.getContainerKey());
            if (data != null && !data.isEmpty()) {
                ContainerPortalData restored = data.snapshot();
                ContainerLivingItemHandler.putContainerData(ctx, ContainerPortalData.KEY, restored);
                return restored;
            }
        }

        return ContainerLivingItemHandler.getOrCreateContainerData(ctx, ContainerPortalData.KEY);
    }

    /** 不可变快照同时替换热表与持久副本；传入空值与 clear 同义。 */
    public static void persist(ContainerContext ctx, ContainerPortalData data) {
        if (data == null || data.isEmpty()) {
            clear(ctx);
            return;
        }
        ContainerPortalData snapshot = data.snapshot();
        ContainerLivingItemHandler.putContainerData(ctx, ContainerPortalData.KEY, snapshot);
        ctx.persist(ContainerPortalData.KEY, snapshot);
        writePlayer(ctx, snapshot);
    }

    public static void clear(ContainerContext ctx) {
        ctx.persist(ContainerPortalData.KEY, ContainerPortalData.EMPTY);
        for (var host : ctx.getDataHosts()) {
            host.setData(PortalComponents.CONTAINER_PORTAL_DATA.value(), ContainerPortalData.EMPTY);
        }
        writePlayer(ctx, ContainerPortalData.EMPTY);
        ContainerLivingItemHandler.removeContainerData(ctx, ContainerPortalData.KEY);
    }

    private static void writePlayer(ContainerContext ctx, ContainerPortalData data) {
        Player owner = playerOwner(ctx);
        if (owner == null || ctx.getContainerUuid() != null) return;
        Map<String, ContainerPortalData> current = owner.getData(PortalComponents.CONTAINER_PORTAL_DATA_PLAYER);
        Map<String, ContainerPortalData> updated = current == null ? new HashMap<>() : new HashMap<>(current);
        if (data.isEmpty()) updated.remove(ctx.getContainerKey());
        else updated.put(ctx.getContainerKey(), data.snapshot());
        owner.setData(PortalComponents.CONTAINER_PORTAL_DATA_PLAYER.value(), Map.copyOf(updated));
    }

    @Nullable
    private static Player playerOwner(ContainerContext ctx) {
        return ContainerKeys.isOwnedByPlayer(ctx.getContainerKey()) ? ctx.getOwnerPlayer() : null;
    }
}
