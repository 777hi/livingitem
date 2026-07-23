package com.qiqi.li.living.command;

import java.util.Set;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import com.qiqi.li.living.core.components.InternalStorageComponent;
import com.qiqi.li.living.function.LivingChestFunction;

public class LivingChestCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("livingchest")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("orphaned")
                .executes(LivingChestCommand::listOrphaned)
            )
            .then(Commands.literal("recover")
                .then(Commands.argument("uuid", StringArgumentType.string())
                    .executes(LivingChestCommand::recoverUuid)
                )
            )
            .then(Commands.literal("recoverall")
                .executes(LivingChestCommand::recoverAll)
            )
            .then(Commands.literal("list")
                .executes(LivingChestCommand::listAllOnDisk)
            )
        );
    }

    private static int listOrphaned(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        Set<UUID> orphaned = LivingChestFunction.findOrphanedUuids(server);

        if (orphaned.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No orphaned UUIDs found on disk"), false);
        } else {
            int total = orphaned.size();
            source.sendSuccess(() -> Component.literal("Found " + total + " orphaned UUID(s) on disk:"), false);
            int shown = 0;
            for (UUID uuid : orphaned) {
                if (shown >= 20) {
                    final int remaining = total - shown;
                    source.sendSuccess(() -> Component.literal("  ... and " + remaining + " more"), false);
                    break;
                }
                source.sendSuccess(() -> Component.literal("  " + uuid), false);
                shown++;
            }
        }

        return orphaned.size();
    }

    private static int recoverUuid(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String uuidStr = StringArgumentType.getString(context, "uuid");

        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Invalid UUID format: " + uuidStr));
            return 0;
        }

        MinecraftServer server = source.getServer();
        InternalStorageComponent.WorldStorage storage = InternalStorageComponent.WorldStorage.get(server);

        if (!storage.hasFileOnDisk(uuid)) {
            source.sendFailure(Component.literal("UUID " + uuid + " does not exist on disk"));
            return 0;
        }

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be executed by a player"));
            return 0;
        }

        ItemStack chest = LivingChestFunction.createRecoveryChest(uuid);
        if (!player.getInventory().add(chest)) {
            player.drop(chest, false);
        }

        source.sendSuccess(() -> Component.literal("Recovered living chest with UUID: " + uuid), true);
        return 1;
    }

    private static int recoverAll(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        Set<UUID> orphaned = LivingChestFunction.findOrphanedUuids(server);

        if (orphaned.isEmpty()) {
            source.sendFailure(Component.literal("No orphaned UUIDs found on disk"));
            return 0;
        }

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be executed by a player"));
            return 0;
        }

        int recovered = 0;
        for (UUID uuid : orphaned) {
            ItemStack chest = LivingChestFunction.createRecoveryChest(uuid);
            if (!player.getInventory().add(chest)) {
                player.drop(chest, false);
            }
            recovered++;
        }

        final int count = recovered;
        source.sendSuccess(() -> Component.literal("Recovered " + count + " orphaned UUID(s)"), true);
        return recovered;
    }

    private static int listAllOnDisk(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        InternalStorageComponent.WorldStorage storage = InternalStorageComponent.WorldStorage.get(server);
        Set<UUID> allOnDisk = storage.getAllUuidsOnDisk();
        Set<UUID> referenced = LivingChestFunction.collectReferencedUuids(server);

        int orphanedCount = 0;
        for (UUID uuid : allOnDisk) {
            if (!referenced.contains(uuid)) {
                orphanedCount++;
            }
        }

        final int totalOnDisk = allOnDisk.size();
        final int totalReferenced = referenced.size();
        final int totalOrphaned = orphanedCount;

        source.sendSuccess(() -> Component.literal(
            "Total UUIDs on disk: " + totalOnDisk +
            ", Referenced: " + totalReferenced +
            ", Orphaned: " + totalOrphaned), false);

        return totalOnDisk;
    }
}