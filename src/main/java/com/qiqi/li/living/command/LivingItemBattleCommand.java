package com.qiqi.li.living.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.TickableContainerContext;
import com.qiqi.li.living.domain.battle.ArenaPlots;
import com.qiqi.li.living.domain.battle.BattleArenaData;
import com.qiqi.li.living.domain.battle.BattleEnemies;
import com.qiqi.li.living.domain.battle.BattleEventFeed;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 战局指令（spike）—— {@code /livingitem battle} 子树（build/arena/wave/clear）。
 *
 * <p>spike 用命令替代瓶驱动器与波次机；战场目标 = 视线 6 格内的方块容器
 * （与 {@code container} 子树同款射线口径）。</p>
 */
@EventBusSubscriber
public class LivingItemBattleCommand {

    private static final SimpleCommandExceptionType NOT_LOOKING_AT_CONTAINER =
        new SimpleCommandExceptionType(Component.translatable("command.livingitem.not_looking_at_container"));
    private static final SimpleCommandExceptionType NO_BATTLE =
        new SimpleCommandExceptionType(Component.translatable("command.livingitem.battle.no_battle"));

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
            Commands.literal("livingitem")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("battle")
                    .then(Commands.literal("build")
                        .executes(ctx -> buildBattle(ctx, false))
                        .then(Commands.literal("near").executes(ctx -> buildBattle(ctx, true))))
                    .then(Commands.literal("arena").executes(LivingItemBattleCommand::teleportToArena))
                    .then(Commands.literal("wave")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 32))
                            .executes(LivingItemBattleCommand::spawnWave)))
                    .then(Commands.literal("clear").executes(LivingItemBattleCommand::clearBattle))
                )
        );
    }

    /** 建场：视线容器 → 定址 → fill 平台 → 写战局数据（重复 build = 重摆并重置名册）。 */
    private static int buildBattle(CommandContext<CommandSourceStack> ctx, boolean near)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        TickableContainerContext container = resolveContext(source);
        if (!(source.getLevel() instanceof ServerLevel arena)) return 0;
        String key = container.getCacheKey();
        if (key == null) throw NOT_LOOKING_AT_CONTAINER.create();
        int width = Math.max(1, container.getWidth());
        int lanes = (container.getSize() + width - 1) / width;
        BlockPos origin = near ? ArenaPlots.nearFor(player.position()) : ArenaPlots.plotFor(key);
        ArenaPlots.fillPlatform(arena, origin, lanes, width);
        BattleArenaData data = container.getOrCreateContainerData(BattleArenaData.KEY);
        data.configure(key, origin, lanes, width);
        BattleEventFeed.clear(key);
        source.sendSuccess(() -> Component.translatable("command.livingitem.battle.built",
            origin.getX(), origin.getY(), origin.getZ(), lanes, width,
            near ? "（near：透过容器界面可见）" : "（/livingitem battle arena 前往）"), true);
        return 1;
    }

    /** 观战：tp 到战局南侧高空（面向北 = GUI 视角）。 */
    private static int teleportToArena(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        TickableContainerContext container = resolveContext(source);
        BattleArenaData data = container.peekContainerData(BattleArenaData.KEY);
        if (data == null || data.isEmpty()) throw NO_BATTLE.create();
        Vec3 view = new Vec3(
            data.plotPos().getX() + data.depth() * ArenaPlots.DEPTH_STRIDE / 2.0,
            data.plotPos().getY() + 8.0,
            data.plotPos().getZ() + data.lanes() * ArenaPlots.LANE_STRIDE + 6.0);
        player.teleportTo(player.serverLevel(), view.x, view.y, view.z, 180.0F, -15.0F);
        source.sendSuccess(() -> Component.translatable("command.livingitem.battle.arena"), true);
        return 1;
    }

    /** 召怪：每车道 count 只，车道远端生成。 */
    private static int spawnWave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        TickableContainerContext container = resolveContext(source);
        if (!(source.getLevel() instanceof ServerLevel arena)) return 0;
        BattleArenaData data = container.peekContainerData(BattleArenaData.KEY);
        if (data == null || data.isEmpty()) throw NO_BATTLE.create();
        int count = IntegerArgumentType.getInteger(ctx, "count");
        int spawned = BattleEnemies.spawnWave(arena, data, count);
        int total = spawned;
        source.sendSuccess(() -> Component.translatable(
            "command.livingitem.battle.wave", total, count), true);
        return spawned;
    }

    /** 清场：战区实体 discard + 平台方块移除 + 战局数据重置。 */
    private static int clearBattle(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        TickableContainerContext container = resolveContext(source);
        if (!(source.getLevel() instanceof ServerLevel arena)) return 0;
        BattleArenaData data = container.peekContainerData(BattleArenaData.KEY);
        if (data == null || data.isEmpty()) throw NO_BATTLE.create();
        int removed = BattleEnemies.clear(arena, data);
        ArenaPlots.clearPlatform(arena, data.plotPos(), data.lanes(), data.depth());
        BattleEventFeed.clear(data.battleKey());
        data.reset();
        int total = removed;
        source.sendSuccess(() -> Component.translatable(
            "command.livingitem.battle.cleared", total), true);
        return 1;
    }

    /** 视线容器 → 上下文（与 container 子树同款射线与能力面口径）。 */
    private static TickableContainerContext resolveContext(CommandSourceStack source)
            throws CommandSyntaxException {
        BlockHitResult hit = getPlayerPOVHitResult(source);
        BlockPos pos = hit.getBlockPos();
        Level level = source.getLevel();
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) throw NOT_LOOKING_AT_CONTAINER.create();
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null) throw NOT_LOOKING_AT_CONTAINER.create();
        TickableContainerContext context = ContainerLivingItemHandler.resolveContextAt(level, pos, handler);
        if (context == null) throw NOT_LOOKING_AT_CONTAINER.create();
        return context;
    }

    private static BlockHitResult getPlayerPOVHitResult(CommandSourceStack source)
            throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        var level = source.getLevel();
        double reach = 5.0;
        var lookVec = player.getLookAngle();
        var eyePos = player.getEyePosition();
        var endPos = eyePos.add(lookVec.x * reach, lookVec.y * reach, lookVec.z * reach);
        BlockHitResult hit = level.clip(new ClipContext(eyePos, endPos,
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) throw NOT_LOOKING_AT_CONTAINER.create();
        return hit;
    }
}
