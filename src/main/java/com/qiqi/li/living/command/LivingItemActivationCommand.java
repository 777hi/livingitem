package com.qiqi.li.living.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;

import com.qiqi.li.living.api.ActivationRuleConfig;
import com.qiqi.li.living.api.LivingItemActivation;
import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.server.command.ModIdArgument;

/**
 * {@code /livingitem activation} —— 活化规则的查看、验证与增删改（D1）。
 *
 * <p>挂载点：{@code /livingitem activation}。子命令不在此罗列 ——
 * 语法以代码为准，导航见 {@code docs/reference/commands.md}，
 * 完整用法见 {@code docs/buffer/activation-rule-design.md}。</p>
 *
 * <p><b>目标语法</b>（两种参数类型，都由平台注册好、补全与解析都由原版负责）：</p>
 * <ul>
 *   <li>{@code minecraft:chest} / {@code #minecraft:swords} —— 物品 ID 或标签，
 *       走 {@code deny} / {@code allow} / {@code remove} 的 {@code target} 参数</li>
 *   <li>{@code somemod} —— 整个命名空间（某模组的全部物品），
 *       走 {@code deny-mod} / {@code allow-mod} / {@code remove-mod}</li>
 *   <li><b>不带任何参数</b>（{@code deny} / {@code allow} / {@code remove} 单独执行）——
 *       对<b>手持物品</b>生效，省得配置者手打完整 ID（与 {@code test} 共用 {@link #heldItem}）</li>
 * </ul>
 *
 * <p>⚠️ 手持分支<b>不接受 {@code via}</b>（无参数可带），一律取 {@code via} 的缺省值
 * （仅 {@code player}）；需要精细控制途径就用完整的 {@code deny <target> <via>} 形式。</p>
 *
 * <p>⚠️ <b>为什么 {@code target} 必须用 {@link ResourceOrTagKeyArgument} 而不能用
 * {@code StringArgumentType}</b>（实测结论，勿改回）：</p>
 *
 * <p>{@code StringArgumentType} 走 {@code StringReader.readUnquotedString()}，其允许字符集
 * <b>不含</b> {@code :} {@code #} {@code @}。实测：</p>
 *
 * <pre>
 *   minecraft:chest    -> 只解析出 "minecraft"（卡在 ':'）
 *   #minecraft:swords  -> 空串
 *   @somemod           -> 空串
 * </pre>
 *
 * <p>解析成空串后 Brigadier 抛「Expected whitespace to end one argument, but found trailing data」
 * ⇒ 命令根本执行不到。<br>
 * 更隐蔽的是：<b>手写 {@code .suggests(...)} 补全不经过 Brigadier 解析</b>，所以现象是
 * 「Tab 能列出候选、回车却注册失败」——补全在骗人。<br>
 * 同理 {@code @} 在原版是<b>目标选择器保留前缀</b>（{@code @a} / {@code @p} / {@code @s}…），
 * 语义上也不该复用；命名空间改用 NeoForge 的 {@link ModIdArgument}（{@code modid} 即物品 namespace）。</p>
 *
 * <p>匹配采用<b>特异性优先</b>（item &gt; tag &gt; namespace），
 * 因此后加的精确 allow 能覆盖先前的宽泛 deny，无需关心顺序。
 *
 * <p><b>为什么 {@code test} 要分途径显示</b>（代码读不出这个意图）：
 * 配置者改完规则需要<b>立刻</b>验证而不是靠猜；而「玩家能不能点活」与
 * 「任务奖励能不能发」是<b>两件事</b>（见 {@link LivingItemActivation.Via}），
 * 只给一个总判定无法确认配置是否按预期生效。</p>
 */
@EventBusSubscriber
public class LivingItemActivationCommand {

    /**
     * {@code target} 固定为 ITEM 注册表，{@link ResourceOrTagKeyArgument#getResourceOrTagKey}
     * 的 cast 必然成功 ⇒ 此异常实际不会触发，仅为满足它的签名要求。
     */
    private static final DynamicCommandExceptionType ERROR_BAD_TARGET = new DynamicCommandExceptionType(
        o -> Component.translatable("command.livingitem.activation_bad_selector"));

    /**
     * {@code target} 参数类型 —— 单独抽出是为了让测试直接断言<b>生产代码用的就是这个类型</b>，
     * 而不是在测试里另写一份同构树（那样会漂移到第二份清单）。
     *
     * <p>原版 {@link ResourceOrTagKeyArgument} 的 {@code listSuggestions} 走
     * {@code SharedSuggestionProvider.suggestRegistryElements(..., ElementSuggestionType.ALL, ...)}，
     * 会同时列举物品 ID 与 {@code #标签} ⇒ 本文件不需要任何手写补全。</p>
     */
    public static ResourceOrTagKeyArgument<Item> targetArgument() {
        return ResourceOrTagKeyArgument.resourceOrTagKey(Registries.ITEM);
    }

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
                    // ── 物品 ID / 标签：解析与补全全部由原版 ResourceOrTagKeyArgument 负责 ──
                    // 每个动词都有【不带参数】的分支：直接对【手持】物品生效，
                    // 省得配置者手打完整 ID（与 test 子命令同一套取物品逻辑，见 heldItem）。
                    .then(Commands.literal("deny")
                        .executes(ctx -> setHeldRule(ctx, true, false))
                        .then(Commands.argument("target", targetArgument())
                            .executes(ctx -> setRule(ctx, true, false))
                            .then(Commands.argument("via", StringArgumentType.greedyString())
                                .executes(ctx -> setRule(ctx, true, false)))))
                    .then(Commands.literal("allow")
                        .executes(ctx -> setHeldRule(ctx, true, true))
                        .then(Commands.argument("target", targetArgument())
                            .executes(ctx -> setRule(ctx, true, true))
                            .then(Commands.argument("via", StringArgumentType.greedyString())
                                .executes(ctx -> setRule(ctx, true, true)))))
                    .then(Commands.literal("remove")
                        .executes(LivingItemActivationCommand::removeHeldRule)
                        .then(Commands.argument("target", targetArgument())
                            .executes(LivingItemActivationCommand::removeRule)))
                    // ── 整个命名空间（modid）：NeoForge 的 ModIdArgument，补全列出已加载模组 ──
                    .then(Commands.literal("deny-mod")
                        .then(Commands.argument("mod", ModIdArgument.modIdArgument())
                            .executes(ctx -> setModRule(ctx, true, false))
                            .then(Commands.argument("via", StringArgumentType.greedyString())
                                .executes(ctx -> setModRule(ctx, true, false)))))
                    .then(Commands.literal("allow-mod")
                        .then(Commands.argument("mod", ModIdArgument.modIdArgument())
                            .executes(ctx -> setModRule(ctx, true, true))
                            .then(Commands.argument("via", StringArgumentType.greedyString())
                                .executes(ctx -> setModRule(ctx, true, true)))))
                    .then(Commands.literal("remove-mod")
                        .then(Commands.argument("mod", ModIdArgument.modIdArgument())
                            .executes(LivingItemActivationCommand::removeModRule)))
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

    /** 判定【手持】物品能否被活化，并按途径分行显示结果。 */
    static int testItem(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ItemStack target = heldItem(source);
        if (target.isEmpty()) return 0;                    // 提示已在 heldItem 内发出

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

    /** 新增 / 更新一条规则（deny / allow 共用）—— 目标是物品 ID 或 #标签。 */
    private static int setRule(CommandContext<CommandSourceStack> ctx, boolean activate, boolean allow)
            throws CommandSyntaxException {
        ResourceOrTagKeyArgument.Result<Item> r =
            ResourceOrTagKeyArgument.getResourceOrTagKey(ctx, "target", Registries.ITEM, ERROR_BAD_TARGET);
        // asPrintable() 由原版给出：物品 -> "minecraft:chest"、标签 -> "#minecraft:swords"
        // ⇒ 与 JSON 侧的选择器文本同形，可直接当规则 key 回写，不必自己拼字符串。
        return applyRule(ctx, r.asPrintable(), activate, allow);
    }

    /** 同 {@link #setRule}，但目标是整个命名空间（modid）。 */
    private static int setModRule(CommandContext<CommandSourceStack> ctx, boolean activate, boolean allow) {
        return applyRule(ctx, namespaceSelector(ctx), activate, allow);
    }

    private static int applyRule(CommandContext<CommandSourceStack> ctx, String target,
            boolean activate, boolean allow) {
        CommandSourceStack source = ctx.getSource();
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

    /** 移除一条规则 —— 目标是物品 ID 或 #标签。 */
    private static int removeRule(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ResourceOrTagKeyArgument.Result<Item> r =
            ResourceOrTagKeyArgument.getResourceOrTagKey(ctx, "target", Registries.ITEM, ERROR_BAD_TARGET);
        return removeTarget(ctx, r.asPrintable());
    }

    /** 同 {@link #removeRule}，但目标是整个命名空间（modid）。 */
    private static int removeModRule(CommandContext<CommandSourceStack> ctx) {
        return removeTarget(ctx, namespaceSelector(ctx));
    }

    private static int removeTarget(CommandContext<CommandSourceStack> ctx, String target) {
        CommandSourceStack source = ctx.getSource();
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

    /**
     * 命名空间的选择器文本 —— {@code @} 前缀只作为<b>规则 key 的内部表示</b>
     * （{@code ActivationRuleConfig} 靠它区分三种颗粒度，JSON 侧用的是 {@code namespace} 字段），
     * <b>不是玩家输入语法</b>：玩家不输入 {@code @}，自然也不受
     * {@code StringArgumentType} 读不进 {@code @} 的影响。
     */
    private static String namespaceSelector(CommandContext<CommandSourceStack> ctx) {
        return "@" + ctx.getArgument("mod", String.class);
    }

    /** {@code deny} / {@code allow} 不带参数时：直接对【手持】物品生效。 */
    private static int setHeldRule(CommandContext<CommandSourceStack> ctx, boolean activate, boolean allow) {
        ItemStack held = heldItem(ctx.getSource());
        if (held.isEmpty()) return 0;
        // 不走 asPrintable()：这里没有解析过程，直接问注册表要 ID 文本即可
        return applyRule(ctx, BuiltInRegistries.ITEM.getKey(held.getItem()).toString(), activate, allow);
    }

    /** {@code remove} 不带参数时：移除【手持】物品的规则。 */
    private static int removeHeldRule(CommandContext<CommandSourceStack> ctx) {
        ItemStack held = heldItem(ctx.getSource());
        if (held.isEmpty()) return 0;
        return removeTarget(ctx, BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
    }

    /**
     * 取【手持】物品（主手 → 副手）—— {@code test} 与三个不带参数的动词共用。
     *
     * <p>⭐ <b>只取主手 / 副手，不要取 {@code containerMenu.getCarried()}</b>：
     * 能输入指令 ⇒ 玩家必然【不在】容器界面（容器界面里打不开聊天输入），
     * 此时 carried 恒为空 ⇒ 用它永远报「没拿物品」。
     * carried 只适用于活化按钮（它只存在于容器界面内）—— <b>两个入口场景不同</b>，勿混用。</p>
     *
     * @return 手持物品；{@link ItemStack#EMPTY} = 拿不到（失败提示已发给玩家，调用方直接 return 0）
     */
    private static ItemStack heldItem(CommandSourceStack source) {
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.livingitem.activation_need_player"));
            return ItemStack.EMPTY;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) held = player.getOffhandItem();
        if (held.isEmpty()) {
            source.sendFailure(Component.translatable("command.livingitem.activation_no_item"));
            return ItemStack.EMPTY;
        }
        return held;
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

    // ── 补全不需要自己写：ResourceOrTagKeyArgument / ModIdArgument 都自带 listSuggestions ──
    // （原先这里有一份手写 suggestTargets：它绕过 Brigadier 解析直接塞字符串，
    //   于是「Tab 能列出候选、回车却注册失败」——坑的根源。见类 javadoc。）
}
