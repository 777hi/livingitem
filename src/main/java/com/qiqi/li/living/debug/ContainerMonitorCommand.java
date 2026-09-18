package com.qiqi.li.living.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerChunkCache;
import com.qiqi.li.logging.ModLog;

@EventBusSubscriber
public class ContainerMonitorCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
            Commands.literal("living_monitor")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("on")
                    .executes(ContainerMonitorCommand::onGlobal))
                .then(Commands.literal("off")
                    .executes(ContainerMonitorCommand::offGlobal))
                .then(Commands.literal("status")
                    .executes(ContainerMonitorCommand::status))
                .then(Commands.literal("dump_inventory")
                    .executes(ContainerMonitorCommand::dumpInventory))
                .then(Commands.literal("cache")
                    .executes(ContainerMonitorCommand::cacheStats))
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