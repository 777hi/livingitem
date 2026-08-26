package com.qiqi.li.living.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import com.qiqi.li.living.api.LivingItemManager;

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