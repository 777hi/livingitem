package com.qiqi.li.living.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import com.qiqi.li.living.api.ActivationRuleConfig;
import com.qiqi.li.living.api.LivingItemActivation;
import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /livingitem activation} —— 活化规则的查看、验证与增删改（D1）。
 *
 * <p>挂载点：{@code /livingitem activation}。子命令不在此罗列 ——
 * 语法以代码为准，导航见 {@code docs/reference/commands.md}，
 * 完整用法见 {@code docs/buffer/activation-rule-design.md}。</p>
 *
 * <p><b>目标语法</b>（三种颗粒度，指令会自动补全）：</p>
 * <ul>
 *   <li>{@code minecraft:chest} —— 单个物品</li>
 *   <li>{@code #minecraft:swords} —— 物品标签（跨模组）</li>
 *   <li>{@code @somemod} —— 整个命名空间</li>
 * </ul>
 * 匹配采用<b>特异性优先</b>（item &gt; tag &gt; namespace），
 * 因此后加的精确 allow 能覆盖先前的宽泛 deny，无需关心顺序。
 *
 * <p><b>为什么 {@code test} 要分途径显示</b>（代码读不出这个意图）：
 * 配置者改完规则需要<b>立刻</b>验证而不是靠猜；而「玩家能不能点活」与
 * 「任务奖励能不能发」是<b>两件事</b>（见 {@link LivingItemActivation.Via}），
 * 只给一个总判定无法确认配置是否按预期生效。</p>
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
                    // ── 增删改：目标是「物品ID / #标签 / @命名空间」，带 Tab 补全 ──
                    .then(Commands.literal("deny")
                        .then(Commands.argument("target", StringArgumentType.string())
                            .suggests(LivingItemActivationCommand::suggestTargets)
                            .executes(ctx -> setRule(ctx, true, false))
                            .then(Commands.argument("via", StringArgumentType.greedyString())
                                .executes(ctx -> setRule(ctx, true, false)))))
                    .then(Commands.literal("allow")
                        .then(Commands.argument("target", StringArgumentType.string())
                            .suggests(LivingItemActivationCommand::suggestTargets)
                            .executes(ctx -> setRule(ctx, true, true))
                            .then(Commands.argument("via", StringArgumentType.greedyString())
                                .executes(ctx -> setRule(ctx, true, true)))))
                    .then(Commands.literal("remove")
                        .then(Commands.argument("target", StringArgumentType.string())
                            .suggests(LivingItemActivationCommand::suggestTargets)
                            .executes(LivingItemActivationCommand::removeRule)))
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

    /** 逐条列出当前生效规则 */
    static int listRules(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<String> lines = ActivationRuleConfig.describeRules();
        source.sendSuccess(() -> Component.translatable(
            "command.livingitem.activation_list",
            ActivationRuleConfig.ruleCount(),
            ActivationRuleConfig.getDefaultAction().name().toLowerCase(Locale.ROOT),
            ActivationRuleConfig.isDenyUnclaimed()), true);
        if (lines.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(
                "command.livingitem.activation_list_empty"), false);
            return 1;
        }
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal("  " + line), false);
        }
        return 1;
    }

    /**
     * 判定【手持】物品能否被活化，并按途径分行显示结果。
     *
     * <p>⭐ 只取主手 → 副手，<b>不要</b>取 {@code containerMenu.getCarried()}：
     * 能输入指令 ⇒ 玩家必然【不在】容器界面（容器界面里打不开聊天输入），
     * 此时 carried 恒为空 ⇒ 用它永远报「没拿物品」。
     * carried 只适用于活化按钮（它只存在于容器界面内）—— 两个入口场景不同。</p>
     */
    static int testItem(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.livingitem.activation_need_player"));
            return 0;
        }
        ItemStack target = player.getMainHandItem();
        if (target.isEmpty()) {
            target = player.getOffhandItem();
        }
        if (target.isEmpty()) {
            source.sendFailure(Component.translatable("command.livingitem.activation_no_item"));
            return 0;
        }

        ResourceLocation id = BuiltInRegistries.ITEM.getKey(target.getItem());
        String name = target.getItem().getName(target).getString();
        boolean claimed = LivingItemManager.hasAnyFunctionFor(target);

        source.sendSuccess(() -> Component.translatable(
            "command.livingitem.activation_test_header", name, id), false);
        source.sendSuccess(() -> Component.translatable(
            "command.livingitem.activation_test_claimed", claimed
                ? Component.translatable("command.livingitem.yes")
                : Component.translatable("command.livingitem.no")), false);

        for (LivingItemActivation.Via via : LivingItemActivation.Via.values()) {
            var verdict = LivingItemActivation.evaluate(target, true, via);
            final var v = via;
            source.sendSuccess(() -> Component.translatable(
                "command.livingitem.activation_test_line",
                v.name().toLowerCase(Locale.ROOT),
                Component.translatable("livingitem.activation.verdict." + verdict.name().toLowerCase(Locale.ROOT))
            ), false);
        }
        return 1;
    }

    /** 新增 / 更新一条规则（deny / allow 共用）。 */
    private static int setRule(CommandContext<CommandSourceStack> ctx, boolean activate, boolean allow) {
        CommandSourceStack source = ctx.getSource();
        String target = StringArgumentType.getString(ctx, "target");
        if (!ActivationRuleConfig.isValidSelector(target)) {
            source.sendFailure(Component.translatable("command.livingitem.activation_bad_selector"));
            return 0;
        }
        List<LivingItemActivation.Via> via = parseVia(ctx);
        boolean isNew = ActivationRuleConfig.put(target, activate, allow, via);
        ActivationRuleConfig.save();

        final String t = target;
        final boolean nw = isNew;
        source.sendSuccess(() -> Component.translatable(
            nw ? "command.livingitem.activation_added" : "command.livingitem.activation_overrode",
            t, activate ? "activate" : "deactivate", allow ? "allow" : "deny"), true);
        return 1;
    }

    /** 移除一条规则 */
    private static int removeRule(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String target = StringArgumentType.getString(ctx, "target");
        boolean changed = ActivationRuleConfig.remove(target);
        ActivationRuleConfig.save();
        final String t = target;
        if (changed) {
            source.sendSuccess(() -> Component.translatable(
                "command.livingitem.activation_removed", t), true);
            return 1;
        }
        source.sendFailure(Component.translatable("command.livingitem.activation_remove_none", t));
        return 0;
    }

    /** 解析可选的 via 参数（逗号分隔；空 = 不指定，取缺省仅 player）。 */
    private static List<LivingItemActivation.Via> parseVia(CommandContext<CommandSourceStack> ctx) {
        String raw;
        try {
            raw = StringArgumentType.getString(ctx, "via");
        } catch (IllegalArgumentException e) {
            return null;                                   // 未传入 via
        }
        if (raw == null || raw.isBlank()) return null;
        List<LivingItemActivation.Via> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                out.add(LivingItemActivation.Via.valueOf(part.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // 忽略非法值 —— 与 JSON 侧的处理一致（WARN 而非崩）
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * {@code target} 参数的 Tab 补全：按输入前缀提供不同候选 ——
     * {@code #} 补标签、{@code @} 补命名空间、其余补物品 ID（并提示两种前缀）。
     */
    private static CompletableFuture<Suggestions> suggestTargets(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String input = builder.getRemaining();
        String lower = input.toLowerCase(Locale.ROOT);

        if (lower.startsWith("#")) {
            for (String s : tagIds()) {
                if (s.toLowerCase(Locale.ROOT).startsWith(lower)) builder.suggest(s);
            }
        } else if (lower.startsWith("@")) {
            for (String ns : namespaces()) {
                String full = "@" + ns;
                if (full.toLowerCase(Locale.ROOT).startsWith(lower)) builder.suggest(full);
            }
        } else {
            SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), builder);
            // 提示两种特殊前缀，避免玩家不知道有 tag / namespace 可用
            builder.suggest("#");
            builder.suggest("@");
        }
        return builder.buildFuture();
    }

    /** 全部物品标签（带 {@code #} 前缀）。 */
    private static List<String> tagIds() {
        List<String> out = new ArrayList<>();
        BuiltInRegistries.ITEM.getTagNames().forEach(t -> out.add("#" + t.location()));
        Collections.sort(out);
        return out;
    }

    /** 全部命名空间（去重排序）。 */
    private static List<String> namespaces() {
        List<String> out = new ArrayList<>();
        BuiltInRegistries.ITEM.keySet().forEach(id -> {
            if (!out.contains(id.getNamespace())) out.add(id.getNamespace());
        });
        Collections.sort(out);
        return out;
    }
}
