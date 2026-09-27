package com.qiqi.li.living.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import com.qiqi.li.living.api.ActivationRuleConfig;
import com.qiqi.li.living.api.LivingItemActivation;
import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /livingitem activation} —— 活化规则的管理与验证（D1）。
 *
 * <pre>
 *   /livingitem activation reload  — 从配置重新加载（改完 JSON 不用重启）
 *   /livingitem activation list    — 列出当前生效规则概况
 *   /livingitem activation test    — ★ 判定手持物品能否被活化，并分途径显示结果
 * </pre>
 *
 * <p>{@code test} 是这个功能的重点：整合包作者改完 JSON 能<b>立刻</b>验证，
 * 而不是靠猜。它把判定结果按三个途径分别列出 —— 因为「玩家能不能点活」
 * 和「任务奖励能不能发」是<b>两件事</b>（见 {@link LivingItemActivation.Via}）。
 */
@EventBusSubscriber
public class LivingItemActivationCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
            Commands.literal("livingitem")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("activation")
                    .then(Commands.literal("reload")
                        .executes(LivingItemActivationCommand::reloadRules))
                    .then(Commands.literal("list")
                        .executes(LivingItemActivationCommand::listRules))
                    .then(Commands.literal("test")
                        .executes(LivingItemActivationCommand::testItem))
                )
        );
    }

    /** 从配置文件重新加载 */
    static int reloadRules(CommandContext<CommandSourceStack> ctx) {
        ActivationRuleConfig.load();
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "command.livingitem.activation_reloaded"), true);
        return 1;
    }

    /** 列出当前生效规则概况 */
    static int listRules(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "command.livingitem.activation_list",
            ActivationRuleConfig.ruleCount(),
            ActivationRuleConfig.getDefaultAction().name().toLowerCase(),
            ActivationRuleConfig.isDenyUnclaimed()), true);
        return 1;
    }

    /**
     * 判定手持物品能否被活化，并分途径显示结果。
     *
     * <p>为什么分途径：整合包作者的典型配置是「禁玩家点活、但用任务奖励发放」，
     * 只给一个总判定会让他无法确认自己的配置是否生效。
     */
    static int testItem(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.livingitem.activation_need_player"));
            return 0;
        }
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) {
            source.sendFailure(Component.translatable("command.livingitem.activation_no_item"));
            return 0;
        }

        ResourceLocation id = BuiltInRegistries.ITEM.getKey(carried.getItem());
        String name = carried.getItem().getName(carried).getString();
        boolean claimed = LivingItemManager.hasAnyFunctionFor(carried);

        source.sendSuccess(() -> Component.translatable(
            "command.livingitem.activation_test_header", name, id), false);
        source.sendSuccess(() -> Component.translatable(
            "command.livingitem.activation_test_claimed", claimed
                ? Component.translatable("command.livingitem.yes")
                : Component.translatable("command.livingitem.no")), false);

        for (LivingItemActivation.Via via : LivingItemActivation.Via.values()) {
            var verdict = LivingItemActivation.evaluate(carried, true, via);
            final var v = via;
            source.sendSuccess(() -> Component.translatable(
                "command.livingitem.activation_test_line",
                v.name().toLowerCase(),
                Component.translatable("livingitem.activation.verdict." + verdict.name().toLowerCase())
            ), false);
        }
        return 1;
    }
}
