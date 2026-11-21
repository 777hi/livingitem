package com.qiqi.li.living.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerChunkCache;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.logging.ModLog;

/**
 * 容器监控指令 —— 挂在主命令树 {@code /livingitem debug} 下（2026-09-27 整理，
 * 自 {@code /living_monitor} 迁入；对外只保留一个命令根）。
 *
 * <p>💡 <b>输出刻意保持英文硬编码、不走语言文件</b>：这是开发者排障工具
 * （输出同时进 {@code logs/latest.log} 与聊天栏），日志比对场景英文更稳，
 * 不视为待本地化遗漏。
 */
@EventBusSubscriber
public class ContainerMonitorCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
            Commands.literal("livingitem")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("debug")
                    .then(Commands.literal("monitor")
                        .then(Commands.literal("on")
                            .executes(ContainerMonitorCommand::onGlobal))
                        .then(Commands.literal("off")
                            .executes(ContainerMonitorCommand::offGlobal))
                        .then(Commands.literal("status")
                            .executes(ContainerMonitorCommand::status)))
                    .then(Commands.literal("dump_inventory")
                        .executes(ContainerMonitorCommand::dumpInventory))
                    .then(Commands.literal("container")
                        .executes(ContainerMonitorCommand::containerHealth))
                    .then(Commands.literal("cache")
                        .executes(ContainerMonitorCommand::cacheStats))
            )
        );
    }

    private static int onGlobal(CommandContext<CommandSourceStack> ctx) {
        ContainerMonitor.setServer(ctx.getSource().getServer());
        ContainerMonitor.setGlobalEnabled(true);
        ctx.getSource().sendSuccess(() -> Component.literal("[Monitor] ON. Log: logs/living_item_container_monitor.log | Chat: broadcasts to ops"), false);
        return 1;
    }

    private static int offGlobal(CommandContext<CommandSourceStack> ctx) {
        ContainerMonitor.setGlobalEnabled(false);
        ctx.getSource().sendSuccess(() -> Component.literal("[Monitor] OFF."), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = ContainerMonitor.isGlobalEnabled();
        ctx.getSource().sendSuccess(() -> Component.literal("[Monitor] " + (enabled ? "ON" : "OFF")), false);
        return 1;
    }

    /**
     * {@code /living_monitor cache} —— 输出各维度的「缓存 / 可处理 / loaded 区块」统计。
     *
     * <p>用途：验证"强制加载 / 钉住 / 逐圈外扩"是否真的发生。玩家站着不动反复采样，
     * 若 {@code loaded区块} 持续增长或明显超过视距基准，就说明有视距之外仍被加载的区块。</p>
     *
     * <p>同时写日志（{@code logs/latest.log}，logger 名见 {@code ModLog.CONTAINER}），
     * 方便事后对比多次采样。</p>
     */
    private static int cacheStats(CommandContext<CommandSourceStack> ctx) {
        var cache = ContainerChunkCache.getInstance();
        for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
            String line = cache.describeCacheStats(level);
            ModLog.CONTAINER.info("[Cache] {}", line);
            ctx.getSource().sendSuccess(() -> Component.literal("[Cache] " + line), false);
        }
        // 跨 tick 表的规模（全服一张，与维度无关 ⇒ 只打一次）。用法：走远一圈再回来反复采样，
        // 「盖章键」应回落到稳态而不是单调上涨 ⇒ R-06 sweep 真在回收（整改计划 §6.3 实机第 3 条）。
        String lifecycle = ContainerLivingItemHandler.describeLifecycleStats();
        ModLog.CONTAINER.info("[Cache] 跨tick表 {}", lifecycle);
        ctx.getSource().sendSuccess(() -> Component.literal("[Cache] 跨tick表 " + lifecycle), false);
        return 1;
    }

    /**
     * {@code /livingitem debug container} —— 准心（8 格）对着容器做单点体检（2026-11-21）。
     *
     * <p>回答「这个箱子现在到底在不在管道里」：发现层（区块是否已发现 / 是否 ticking /
     * 是否有待重扫排队）+ 容器管道层（{@link ContainerLivingItemHandler#diagnoseContainer}：
     * 聚合条目 / 静息闩锁 / 盖章年龄 / 槽位追踪 / 位置反查）。全程只读 ——
     * 诊断不得改变被诊断的状态。同时写 {@code logs/latest.log} 方便事后比对。</p>
     */
    private static int containerHealth(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("[Container] Player only"));
            return 0;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("[Container] 非服务端世界"));
            return 0;
        }

        net.minecraft.world.phys.HitResult hit = player.pick(8.0D, 1.0F, false);
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            ctx.getSource().sendFailure(Component.literal("[Container] 准心 8 格内未命中方块"));
            return 0;
        }
        BlockPos pos = ((net.minecraft.world.phys.BlockHitResult) hit).getBlockPos();

        var context = ContainerLivingItemHandler.resolveContextAt(level, pos, null);
        if (context == null) {
            ctx.getSource().sendFailure(Component.literal(
                "[Container] " + pos.toShortString() + " 不是容器（或未开箱的战利品容器）"));
            return 0;
        }

        var cache = ContainerChunkCache.getInstance();
        StringBuilder sb = new StringBuilder("[Container] pos=").append(pos.toShortString());
        for (BlockPos p : context.getAssociatedBlockPositions()) {
            ChunkPos chunkPos = new ChunkPos(p);
            sb.append(" | ").append(p.toShortString())
                .append(" chunk=[").append(chunkPos.x).append(',').append(chunkPos.z).append(']')
                .append(" ticking=").append(level.getChunkSource().isPositionTicking(chunkPos.toLong()))
                .append(" 发现缓存=").append(cache.isChunkDiscovered(level.dimension(), chunkPos))
                .append(" 待重扫=").append(cache.isRescanPending(level.dimension(), chunkPos));
        }
        String discovery = sb.toString();
        String pipeline = "[Container] " + ContainerLivingItemHandler.diagnoseContainer(context);

        ModLog.CONTAINER.info(discovery);
        ModLog.CONTAINER.info(pipeline);
        ctx.getSource().sendSuccess(() -> Component.literal(discovery), false);
        ctx.getSource().sendSuccess(() -> Component.literal(pipeline), false);
        return 1;
    }

    private static int dumpInventory(CommandContext<CommandSourceStack> ctx) {
        Player player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Player only"));
            return 0;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Inventory Dump ===\n");
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                String living = LivingItemManager.isLivingItem(stack) ? " [L]" : "";
                sb.append(String.format("  slot[%d]: %s x%d%s\n",
                    i, stack.getItem(), stack.getCount(), living));
            }
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }
}