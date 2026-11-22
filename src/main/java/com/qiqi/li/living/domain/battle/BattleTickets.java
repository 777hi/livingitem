package com.qiqi.li.living.domain.battle;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import com.qiqi.li.living.api.LivingMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;

/**
 * 战场区块加载（生产化，2026-11-22 三源定形：NeoForge 官方门面，CC/FTB 同款）。
 *
 * <p>`ticking=true` = 实体 tick 门槛（底层 BLOCK_TICKING/ENTITY_TICKING TicketType，
 * NeoForge 已封装）⇒ 无人附近时战区照常 tick。**ticket 持久化在
 * ForcedChunksSavedData，重启自动重挂** ⇒ validation 清掉「容器已不在」的残留
 * （运行态 ephemeral 口径，自愈重建交回战局 tick）。</p>
 *
 * <p>⚠️ 注册走 mod 总线（{@code LivingItem} 构造函数 {@code init}）。</p>
 */
public final class BattleTickets {

    public static final TicketController CONTROLLER = new TicketController(
        ResourceLocation.fromNamespaceAndPath(LivingMod.ID, "battle_arena"),
        BattleTickets::validate);

    private BattleTickets() {}

    public static void init(IEventBus modBus) {
        modBus.addListener(RegisterTicketControllersEvent.class,
            e -> e.register(CONTROLLER));
    }

    /** 重启重挂校验：容器方块实体已不在 ⇒ 该 owner 的全部 ticket 清除。 */
    private static void validate(ServerLevel level, TicketHelper ticketHelper) {
        ticketHelper.getBlockTickets().forEach((pos, tickets) -> {
            if (level.getBlockEntity(pos) == null) {
                ticketHelper.removeAllTickets(pos);
            }
        });
    }

    /** 拉起 / 释放整个战区的区块 ticket（ticking 级）。 */
    public static void setArena(ServerLevel level, BlockPos owner,
                                BlockPos origin, int lanes, int depth, boolean add) {
        int minX = (origin.getX() - 2) >> 4;
        int maxX = (origin.getX() + depth * ArenaPlots.CELL_STRIDE + 2) >> 4;
        int minZ = (origin.getZ() - 2) >> 4;
        int maxZ = (origin.getZ() + lanes * ArenaPlots.CELL_STRIDE + 2) >> 4;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                CONTROLLER.forceChunk(level, owner, cx, cz, add, true);
            }
        }
    }
}
