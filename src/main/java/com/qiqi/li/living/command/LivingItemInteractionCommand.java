package com.qiqi.li.living.command;

import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.interaction.InteractionRuleConfig;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * {@code /livingitem interaction} —— 交互规则的 reload 与查看（D2）。
 *
 * <p>规则的<b>增删改走 JSON 文件</b>（设计稿 §3：四元组用指令拼参数比改文件繁琐，
 * 与活化规则不同）—— 本子树只提供改完之后的验证闭环：
 * {@code reload}（重载磁盘）+ {@code list}（列出生效规则并标注来源）。</p>
 */
@EventBusSubscriber
@ApiStatus.Internal
public class LivingItemInteractionCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
            Commands.literal("livingitem")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("interaction")
                    .then(Commands.literal("reload")
                        .executes(LivingItemInteractionCommand::reloadRules))
                    .then(Commands.literal("list")
                        .executes(LivingItemInteractionCommand::listRules))
                )
        );
    }

    /** 重新加载内置 + 玩家交互规则（load 幂等：整表重建，改/删都能反映磁盘现状）。 */
    static int reloadRules(CommandContext<CommandSourceStack> ctx) {
        InteractionRuleConfig.load();
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "command.livingitem.interaction_reloaded",
            InteractionRuleConfig.ruleCount(), InteractionRuleConfig.bundledCount()), true);
        return 1;
    }

    /** 逐条列出当前生效规则（标注 bundled / user）。 */
    static int listRules(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<String> lines = InteractionRuleConfig.describeRules();
        source.sendSuccess(() -> Component.translatable(
            "command.livingitem.interaction_list",
            InteractionRuleConfig.ruleCount(), InteractionRuleConfig.bundledCount()), true);
        if (lines.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(
                "command.livingitem.interaction_list_empty"), false);
            return 1;
        }
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal("  " + line.toLowerCase(Locale.ROOT)), false);
        }
        return 1;
    }
}
