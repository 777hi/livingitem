package com.qiqi.li.living.domain.portal;

import com.mojang.logging.LogUtils;
import com.qiqi.li.living.container.ContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/** 每槽每次交接一批真实货物；源实体接管后仅由正常实体 tick 处理门户。 */
public final class PortalItemTransfer {

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Result {
        SUCCESS,
        INVALID_ORIGIN,
        UNSUPPORTED_DIMENSION,
        INVALID_SLOT,
        SOURCE_EMPTY,
        EXACT_WRITE_UNSUPPORTED,
        SOURCE_NOT_EXTRACTABLE,
        CONSUME_FAILED,
        SOURCE_CHANGED,
        SPAWN_REJECTED,
        ROLLBACK_FAILED,
        DELIVERY_UNCERTAIN,
        PORTAL_MARK_FAILED
    }

    private PortalItemTransfer() {}

    public static Result transfer(ContainerContext ctx, int slot, PortalPositionResolver.Origin origin) {
        if (!PortalPositionResolver.isUsable(origin)) return Result.INVALID_ORIGIN;
        if (origin.level().dimension() != Level.OVERWORLD && origin.level().dimension() != Level.NETHER) {
            return Result.UNSUPPORTED_DIMENSION;
        }
        if (!ctx.isValidSlot(slot)) return Result.INVALID_SLOT;
        if (!ctx.supportsExactWrite(slot)) return Result.EXACT_WRITE_UNSUPPORTED;
        ItemStack before = ctx.getItem(slot).copy();
        if (before.isEmpty()) return Result.SOURCE_EMPTY;
        int batch = Math.min(99, Math.min(before.getMaxStackSize(), before.getCount()));
        batch = Math.min(batch, ctx.simulateExtractItem(slot, batch));
        if (batch <= 0) return Result.SOURCE_NOT_EXTRACTABLE;
        if (!ItemStack.matches(ctx.getItem(slot), before)) return Result.SOURCE_CHANGED;

        int consumed = ctx.consume(slot, batch);
        if (consumed <= 0) return Result.CONSUME_FAILED;
        if (consumed > batch) {
            LOGGER.error("地狱门货物扣量违约：槽位 {} 预算 {}，确认 {}，停止交付", slot, batch, consumed);
            return Result.CONSUME_FAILED;
        }
        int left = before.getCount() - consumed;
        ItemStack expectedAfter = left == 0 ? ItemStack.EMPTY : before.copyWithCount(left);
        if (!ItemStack.matches(ctx.getItem(slot), expectedAfter)) {
            LOGGER.error("地狱门货物扣后源状态不符：槽位 {} 期望 {}，实际 {}，停止交付",
                slot, expectedAfter, ctx.getItem(slot));
            return Result.SOURCE_CHANGED;
        }

        ItemEntity entity;
        boolean spawned;
        try {
            Vec3 pos = origin.pos();
            entity = new ItemEntity(origin.level(), pos.x, pos.y, pos.z, before.copyWithCount(consumed));
            entity.setDeltaMovement(Vec3.ZERO);
            entity.setDefaultPickUpDelay();
            spawned = origin.level().addFreshEntity(entity);
        } catch (RuntimeException e) {
            // 回调异常不能证明货物未交付，不自动回填源库存。
            LOGGER.error("地狱门源实体交接异常：槽位 {} 已扣 {}，交付状态未知，不回填库存", slot, consumed, e);
            return Result.DELIVERY_UNCERTAIN;
        }
        if (!spawned) {
            // 正常拒绝且源仍为本次扣后值，才允许一次恢复；不检查实体 removed 标志。
            if (!ItemStack.matches(ctx.getItem(slot), expectedAfter)) {
                LOGGER.error("地狱门源出生拒绝但源槽已变：槽位 {}，不覆盖现有内容", slot);
                return Result.SOURCE_CHANGED;
            }
            try {
                ContainerContext.rollbackSlot(ctx, slot, before.copy(), "地狱门源实体出生拒绝");
            } catch (RuntimeException e) {
                LOGGER.error("地狱门源恢复异常：槽位 {}，需人工核查", slot, e);
                return Result.ROLLBACK_FAILED;
            }
            ItemStack actual = ctx.getItem(slot).copy();
            ctx.syncSlotToClients(slot, actual);
            if (!ItemStack.matches(actual, before)) {
                LOGGER.error("地狱门源恢复失败：槽位 {} 期望 {}，实际 {}，需人工核查", slot, before, actual);
                return Result.ROLLBACK_FAILED;
            }
            return Result.SPAWN_REJECTED;
        }

        try {
            entity.setAsInsidePortal((Portal) Blocks.NETHER_PORTAL, BlockPos.containing(origin.pos()));
        } catch (RuntimeException e) {
            LOGGER.error("地狱门门户标记失败：槽位 {} 的 {} 件货物已由源世界接管，不回填库存", slot, consumed, e);
            return Result.PORTAL_MARK_FAILED;
        }
        return Result.SUCCESS;
    }
}
