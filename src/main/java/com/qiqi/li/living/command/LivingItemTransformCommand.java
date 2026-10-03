package com.qiqi.li.living.command;

import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import com.qiqi.li.living.domain.water.FluidTransformTable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /livingitem transforms} —— 流体转化表的 reload 与查看（F4，2026-10-03）。
 *
 * <p>与 {@code /livingitem interaction} 同构：转化的增删改走 JSON 文件
 * （{@code config/living_item/fluid_transforms.json}）—— 本子树只提供验证闭环：
 * {@code reload}（重载磁盘，幂等）+ {@code list}（列出生效转化并标注来源）。</p>
 */
@EventBusSubscriber
public class LivingItemTransformCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
            Commands.literal("livingitem")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("transforms")
                    .then(Commands.literal("reload")
                        .executes(LivingItemTransformCommand::reloadTransforms))
                    .then(Commands.literal("list")
                        .executes(LivingItemTransformCommand::listTransforms))
                )
        );
    }

    /** 重新加载内置 + 玩家转化表（load 幂等：整表重建）。 */
    static int reloadTransforms(CommandContext<CommandSourceStack> ctx) {
        FluidTransformTable.load();
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "command.livingitem.transforms_reloaded",
            FluidTransformTable.entryCount(), FluidTransformTable.bundledCount()), true);
        return 1;
    }

    /** 逐条列出当前生效转化（标注 bundled / user）。 */
    static int listTransforms(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<String> lines = FluidTransformTable.describeEntries();
        source.sendSuccess(() -> Component.translatable(
            "command.livingitem.transforms_list",
            FluidTransformTable.entryCount(), FluidTransformTable.bundledCount()), true);
        if (lines.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(
                "command.livingitem.transforms_list_empty"), false);
            return 1;
        }
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal("  " + line.toLowerCase(Locale.ROOT)), false);
        }
        return 1;
    }
}
