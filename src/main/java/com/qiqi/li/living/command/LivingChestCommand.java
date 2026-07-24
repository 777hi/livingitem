package com.qiqi.li.living.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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

    private static final int MAX_CHEST_STACK = 64;

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
                .then(Commands.literal("list")
                    .executes(LivingChestCommand::recoverList)
                )
                .then(Commands.literal("index")
                    .then(Commands.argument("index", IntegerArgumentType.integer(1))
                        .executes(LivingChestCommand::recoverByIndex)
                    )
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

    private static List<UUID> getSortedOrphaned(MinecraftServer server) {
        List<UUID> list = new ArrayList<>(LivingChestFunction.findOrphanedUuids(server));
        list.sort(UUID::compareTo);
        return list;
    }

    private static int listOrphaned(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        List<UUID> orphaned = getSortedOrphaned(server);

        if (orphaned.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No orphaned UUIDs found on disk"), false);
            return 0;
        }

        int total = orphaned.size();
        source.sendSuccess(() -> Component.literal("Found " + total + " orphaned UUID(s) on disk:"), false);
        for (int i = 0; i < total; i++) {
            if (i >= 20) {
                final int remaining = total - i;
                source.sendSuccess(() -> Component.literal("  ... and " + remaining + " more"), false);
                break;
            }
            final int idx = i + 1;
            final UUID uuid = orphaned.get(i);
            int itemCount = LivingChestFunction.countItemsOnDisk(server, uuid);
            source.sendSuccess(() -> Component.literal(
                "  [" + idx + "] " + uuid + " (" + itemCount + " items)"), false);
        }
        source.sendSuccess(() -> Component.literal(
            "Use /livingchest recover index <n> to recover by number"), false);

        return total;
    }

    private static int recoverList(CommandContext<CommandSourceStack> context) {
        return listOrphaned(context);
    }

    private static int recoverByIndex(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int index = IntegerArgumentType.getInteger(context, "index");

        MinecraftServer server = source.getServer();
        List<UUID> orphaned = getSortedOrphaned(server);

        if (index < 1 || index > orphaned.size()) {
            source.sendFailure(Component.literal(
                "Invalid index: " + index + " (valid range: 1-" + orphaned.size() + ")"));
            return 0;
        }

        UUID uuid = orphaned.get(index - 1);
        return recoverSingleUuid(source, server, uuid);
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

        return recoverSingleUuid(source, source.getServer(), uuid);
    }

    private static int recoverSingleUuid(CommandSourceStack source, MinecraftServer server, UUID uuid) {
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

        int itemCount = LivingChestFunction.countItemsOnDisk(server, uuid);
        ItemStack chest = LivingChestFunction.createRecoveryChest(uuid);
        boolean added = player.getInventory().add(chest);
        if (!added) {
            player.drop(chest, false);
        }

        final int count = itemCount;
        source.sendSuccess(() -> Component.literal(
            "Recovered living chest with UUID: " + uuid + " (" + count + " items)"), true);
        return 1;
    }

    private static int recoverAll(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        List<UUID> orphaned = getSortedOrphaned(server);

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

        int totalItems = 0;
        int recovered = 0;
        int dropped = 0;

        for (UUID uuid : orphaned) {
            totalItems += LivingChestFunction.countItemsOnDisk(server, uuid);
        }

        List<List<UUID>> batches = new ArrayList<>();
        for (int i = 0; i < orphaned.size(); i += MAX_CHEST_STACK) {
            int end = Math.min(i + MAX_CHEST_STACK, orphaned.size());
            batches.add(orphaned.subList(i, end));
        }

        for (List<UUID> batch : batches) {
            ItemStack stack = LivingChestFunction.createRecoveryChestBatch(batch);
            boolean added = player.getInventory().add(stack);
            if (added) {
                recovered += stack.getCount();
            } else {
                player.drop(stack, false);
                dropped += stack.getCount();
            }
        }

        final int rec = recovered;
        final int drop = dropped;
        final int items = totalItems;
        if (drop > 0) {
            source.sendSuccess(() -> Component.literal(
                "Recovered " + rec + " chest(s) (" + items + " items total), " +
                drop + " dropped at feet (inventory full)"), true);
        } else {
            source.sendSuccess(() -> Component.literal(
                "Recovered " + rec + " chest(s) (" + items + " items total)"), true);
        }
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