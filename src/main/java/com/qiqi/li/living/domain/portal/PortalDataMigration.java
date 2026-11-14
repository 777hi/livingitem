package com.qiqi.li.living.domain.portal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerDataSavedData;
import com.qiqi.li.living.container.ContainerDrops;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.ContainerUuids;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.transfer.ContainerCompatibilityConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.items.ItemStackHandler;

/** 门数据的方块与物品形态交接；复用 ContainerHandoff，销毁仅清门域。 */
@EventBusSubscriber
public final class PortalDataMigration {
    private PortalDataMigration() {}

    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        BlockEntity host = event.getBlockEntity();
        if (host == null || !(event.getLevel() instanceof ServerLevel level)) return;
        clearPortalOnChestSplit(level, host);
        ItemEntity target = retainsContents(host) ? ContainerDrops.pickDrop(event.getDrops(), host) : null;
        if (target != null && moveOut(host, target.getItem())) return;
        clearDestroyed(host);
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Player player)) return;
        BlockEntity host = level.getBlockEntity(event.getPos());
        if (host == null) return;
        ItemStack source = ContainerDrops.placedItemInHand(player, event.getPlacedBlock().getBlock());
        if (source != null) moveIn(host, source);
    }

    /** 保留完整内容的宿主才交接；有 UUID 保留身份与 SavedData，无 UUID 搬独立快照。 */
    public static boolean moveOut(@Nullable IAttachmentHolder host, ItemStack target) {
        if (target == null || target.isEmpty() || !retainsContents(host)) return false;
        ContainerContext ctx = resolveContextOf(host);
        UUID uuid = ContainerUuids.ofHost(host);
        ContainerPortalData current = readCurrent(host, ctx, uuid);
        if (uuid != null) {
            if (current.isEmpty()) ContainerDataSavedData.get().remove(uuid, ContainerPortalData.KEY);
            else ContainerDataSavedData.get().put(uuid, ContainerPortalData.KEY, current.snapshot());
            target.set(LivingComponents.CONTAINER_UUID.get(), uuid);
            target.remove(PortalComponents.CONTAINER_PORTAL_DATA_ITEM.get());
            clearHostCopy(host, ctx);
            return true;
        }
        if (current.isEmpty()) return false;
        ContainerUuids.stripForCopy(target);
        target.set(PortalComponents.CONTAINER_PORTAL_DATA_ITEM.get(), current.snapshot());
        clearHostCopy(host, ctx);
        return true;
    }

    /** 恢复同时替换热数据与持久副本；源物品只读，区域有效性由权威维护重新校验。 */
    public static void moveIn(@Nullable IAttachmentHolder host, ItemStack source) {
        if (source == null || source.isEmpty() || !retainsContents(host)) return;
        UUID uuid = ContainerUuids.ofItem(source);
        if (uuid != null) host.setData(LivingComponents.CONTAINER_UUID_ATTACHMENT.get(), uuid);
        ContainerContext ctx = resolveContextOf(host);
        ContainerPortalData data = uuid != null
            ? ContainerDataSavedData.get().get(uuid, ContainerPortalData.KEY)
            : source.get(PortalComponents.CONTAINER_PORTAL_DATA_ITEM.get());
        ContainerPortalData restored = data == null ? ContainerPortalData.EMPTY : data.snapshot();
        if (ctx != null) {
            if (restored.isEmpty()) PortalDataAccess.clear(ctx);
            else PortalDataAccess.persist(ctx, restored);
        }
        host.setData(PortalComponents.CONTAINER_PORTAL_DATA.value(),
            uuid == null ? restored : ContainerPortalData.EMPTY);
    }

    /** 双箱拆半不复制、不分摊旧门，存活半不能经附件回填复燃。 */
    static void clearPortalOnChestSplit(ServerLevel level, BlockEntity broken) {
        BlockPos otherPos = ContainerDrops.otherChestHalf(broken);
        if (otherPos == null) return;
        BlockEntity other = level.getBlockEntity(otherPos);
        List<BlockPos> oldPositions = broken.getBlockState().getValue(ChestBlock.TYPE) == ChestType.LEFT
            ? List.of(broken.getBlockPos(), otherPos) : List.of(otherPos, broken.getBlockPos());
        List<BlockEntity> oldHosts = other == null ? List.of(broken) : List.of(broken, other);
        ContainerContext oldContext = new SimpleContainerContext(new ItemStackHandler(0), null,
            oldPositions, oldHosts, level);
        PortalDataAccess.clear(oldContext);
        clearDestroyed(broken);
        if (other != null) clearDestroyed(other);
    }

    private static ContainerPortalData readCurrent(IAttachmentHolder host, @Nullable ContainerContext ctx,
                                                   @Nullable UUID uuid) {
        if (ctx != null) {
            ContainerPortalData hot = ContainerLivingItemHandler.peekContainerData(ctx, ContainerPortalData.KEY);
            if (hot != null) return hot;
        }
        if (host instanceof BlockEntity be && be.getLevel() instanceof ServerLevel level && be.getBlockPos() != null) {
            ContainerPortalData hot = ContainerLivingItemHandler.peekContainerDataByPos(level, be.getBlockPos(),
                ContainerPortalData.KEY);
            if (hot != null) return hot;
        }
        if (uuid != null) {
            ContainerPortalData saved = ContainerDataSavedData.get().get(uuid, ContainerPortalData.KEY);
            return saved == null ? ContainerPortalData.EMPTY : saved;
        }
        ContainerPortalData attached = host.getData(PortalComponents.CONTAINER_PORTAL_DATA);
        return attached == null ? ContainerPortalData.EMPTY : attached;
    }

    private static void clearHostCopy(IAttachmentHolder host, @Nullable ContainerContext ctx) {
        host.setData(PortalComponents.CONTAINER_PORTAL_DATA.value(), ContainerPortalData.EMPTY);
        if (ctx != null) {
            for (var related : ctx.getDataHosts()) {
                related.setData(PortalComponents.CONTAINER_PORTAL_DATA.value(), ContainerPortalData.EMPTY);
            }
            ContainerLivingItemHandler.removeContainerData(ctx, ContainerPortalData.KEY);
        }
    }

    private static void clearDestroyed(IAttachmentHolder host) {
        UUID uuid = ContainerUuids.ofHost(host);
        if (uuid != null) ContainerDataSavedData.get().remove(uuid, ContainerPortalData.KEY);
        ContainerContext ctx = resolveContextOf(host);
        if (ctx != null) PortalDataAccess.clear(ctx);
        host.setData(PortalComponents.CONTAINER_PORTAL_DATA.value(), ContainerPortalData.EMPTY);
    }

    private static boolean retainsContents(@Nullable IAttachmentHolder host) {
        if (!(host instanceof BlockEntity be)) return false;
        ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        return id != null && ContainerCompatibilityConfig.retainsContentsOnBreak(id);
    }

    @Nullable
    private static ContainerContext resolveContextOf(@Nullable IAttachmentHolder host) {
        if (!(host instanceof BlockEntity be) || be.getBlockPos() == null
                || !(be.getLevel() instanceof ServerLevel level)) return null;
        ContainerContext resolved = ContainerLivingItemHandler.resolveContextAt(level, be.getBlockPos(), null);
        if (resolved != null) return resolved;

        // 已移除宿主的持久身份上下文；零槽 handler 不参与库存、布局或 tick。
        List<BlockPos> positions;
        if (be.getBlockState() != null && be.getBlockState().getBlock() instanceof ChestBlock
                && be.getBlockState().getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = ContainerDrops.otherChestHalf(be);
            positions = be.getBlockState().getValue(ChestBlock.TYPE) == ChestType.LEFT
                ? List.of(be.getBlockPos(), other) : List.of(other, be.getBlockPos());
        } else {
            positions = List.of(be.getBlockPos());
        }
        List<BlockEntity> hosts = new ArrayList<>();
        for (BlockPos pos : positions) {
            BlockEntity related = pos.equals(be.getBlockPos()) ? be : level.getBlockEntity(pos);
            if (related != null) hosts.add(related);
        }
        return new SimpleContainerContext(new ItemStackHandler(0), null, positions, hosts, level);
    }
}
