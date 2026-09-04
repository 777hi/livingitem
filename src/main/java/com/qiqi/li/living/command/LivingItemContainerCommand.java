package com.qiqi.li.living.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.qiqi.li.living.transfer.ContainerCompatibilityConfig;
import com.qiqi.li.living.transfer.ContainerRuleConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 容器注册指令 —— 让玩家在游戏中注册/管理容器布局规则。
 *
 * <p>指令树：</p>
 * <ul>
 *   <li>{@code /livingitem container register <columns>}
 *     — 自动检测槽位数和容器 ID，注册玩家准心指向的容器</li>
 *   <li>{@code /livingitem container register <columns> <size>}
 *     — 手动指定槽位数，容器 ID 自动检测</li>
 *   <li>{@code /livingitem container register <columns> <containerId>}
 *     — 手动指定容器 ID，槽位数自动检测</li>
 *   <li>{@code /livingitem container register <columns> <size> <containerId>}
 *     — 全手动指定：列数 + 槽位数 + 容器 ID</li>
 *   <li>{@code /livingitem container inspect}
 *     — 查看当前准心指向容器的规则信息</li>
 *   <li>{@code /livingitem container list}
 *     — 列出所有已注册的容器规则</li>
 *   <li>{@code /livingitem container remove <containerId>}
 *     — 移除指定容器规则</li>
 *   <li>{@code /livingitem container reload}
 *     — 从配置文件重新加载</li>
 * </ul>
 * <p>
 * <b>核心设计：</b>去掉了 <code>height</code> 参数，无需关心容器是否为矩形。
 * 玩家只需指定列数 {@code columns}，槽位数可选。
 * </p>
 */
@EventBusSubscriber
public class LivingItemContainerCommand {

    private static final SimpleCommandExceptionType NOT_LOOKING_AT_CONTAINER =
        new SimpleCommandExceptionType(Component.translatable("command.livingitem.not_looking_at_container"));

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // register 子指令树：
        //   register <columns>                    → auto-detect size & id
        //   register <columns> <size>               → given size, auto id
        //   register <columns> <containerId>        → auto size, given id
        //   register <columns> <size> <containerId> → given size & id
        dispatcher.register(
            Commands.literal("livingitem")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("container")
                    .then(Commands.literal("register")
                        .then(Commands.argument("columns", IntegerArgumentType.integer(1, 54))
                            // /livingitem container register <columns>
                            .executes(ctx -> registerTargetContainer(ctx, false, false))
                            // /livingitem container register <columns> <size>
                            .then(Commands.argument("size", IntegerArgumentType.integer(1, 256))
                                .executes(ctx -> registerTargetContainer(ctx, false, true))
                                // /livingitem container register <columns> <size> <containerId>
                                .then(Commands.argument("containerId", ResourceLocationArgument.id())
                                    .executes(ctx -> registerTargetContainer(ctx, true, true))
                                )
                            )
                            // /livingitem container register <columns> <containerId>
                            .then(Commands.argument("containerId", ResourceLocationArgument.id())
                                .executes(ctx -> registerTargetContainer(ctx, true, false))
                            )
                        )
                    )
                    .then(Commands.literal("inspect")
                        .executes(LivingItemContainerCommand::inspectContainer)
                    )
                    .then(Commands.literal("list")
                        .executes(LivingItemContainerCommand::listRules)
                    )
                    .then(Commands.literal("remove")
                        .then(Commands.argument("containerId", ResourceLocationArgument.id())
                            .executes(LivingItemContainerCommand::removeRule)
                        )
                    )
                    .then(Commands.literal("reload")
                        .executes(LivingItemContainerCommand::reloadRules)
                    )
                )
        );
    }

    /**
     * 注册容器规则。
     *
     * @param hasExplicitId 是否手动指定了 containerId
     * @param hasExplicitSize 是否手动指定了 size（槽位数）
     */
    private static int registerTargetContainer(CommandContext<CommandSourceStack> ctx,
                                                boolean hasExplicitId, boolean hasExplicitSize)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        int columns = IntegerArgumentType.getInteger(ctx, "columns");

        // 获取玩家准心指向的方块
        BlockHitResult hitResult = getPlayerPOVHitResult(source);
        BlockPos pos = hitResult.getBlockPos();
        Level level = source.getLevel();
        BlockEntity be = level.getBlockEntity(pos);

        if (!(be instanceof Container container)) {
            throw NOT_LOOKING_AT_CONTAINER.create();
        }

        // 确定槽位数：玩家手动指定 > 自动检测
        int size;
        if (hasExplicitSize) {
            size = IntegerArgumentType.getInteger(ctx, "size");
        } else {
            size = container.getContainerSize();
        }

        // 容器 ID：手动指定 > 自动检测
        ResourceLocation containerId;
        if (hasExplicitId) {
            containerId = ResourceLocationArgument.getId(ctx, "containerId");
        } else {
            containerId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        }

        // 检查是否已注册
        if (ContainerCompatibilityConfig.findRule(containerId).isPresent()) {
            source.sendFailure(Component.translatable(
                "command.livingitem.container_already_registered", containerId.toString()));
            return 0;
        }

        // 注册并保存
        var rule = buildRule(containerId, size, columns);
        ContainerRuleConfig.addAndSave(containerId, rule);

        int rows = size / columns;
        source.sendSuccess(() -> Component.translatable(
            "command.livingitem.container_registered",
            containerId.toString(), size, columns, rows), true);
        return 1;
    }

    /** 查看当前准心指向容器的规则信息 */
    static int inspectContainer(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();

        // 获取玩家准心指向的方块
        BlockHitResult hitResult = getPlayerPOVHitResult(source);
        BlockPos pos = hitResult.getBlockPos();
        Level level = source.getLevel();
        BlockEntity be = level.getBlockEntity(pos);

        if (!(be instanceof Container container)) {
            throw NOT_LOOKING_AT_CONTAINER.create();
        }

        ResourceLocation containerId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        int actualSize = container.getContainerSize();

        source.sendSuccess(() -> Component.literal(
            String.format("§e%s§r", containerId)), false);

        source.sendSuccess(() -> Component.literal(
            String.format("  §7槽位: §f%d§r", actualSize)), false);

        // 查找已注册规则
        var existingRule = ContainerCompatibilityConfig.findRule(containerId);
        if (existingRule.isPresent()) {
            var rule = existingRule.get();
            int rows = rule.containerSize() / rule.columns();
            source.sendSuccess(() -> Component.literal(
                String.format("  §7列数: §f%d (%d行)§r", rule.columns(), rows)), false);
            source.sendSuccess(() -> Component.literal(
                String.format("  §7布局: §f%s§r", rule.layoutType())), false);
            source.sendSuccess(() -> Component.literal(
                String.format("  §7边界: §f%s§r", rule.edgeBehavior())), false);
            source.sendSuccess(() -> Component.literal(
                String.format("  §7跨实体: §f%s§r", rule.crossBlockEntitySupport() ? "是" : "否")), false);
            source.sendSuccess(() -> Component.literal(
                String.format("  §7描述: §f%s§r", rule.description())), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                "  §7状态: §c未注册§r"), false);

            // 显示自动推断的列数
            int inferredColumns = ContainerCompatibilityConfig.resolveColumns(actualSize, container);
            source.sendSuccess(() -> Component.literal(
                String.format("  §7推断列数: §f%d§r  §8(输入 /livingitem container register %d 注册)§r",
                    inferredColumns, inferredColumns)), false);
        }
        return 1;
    }

    /** 列出所有已注册的容器规则 */
    static int listRules(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        var rules = ContainerCompatibilityConfig.getAllRules();

        if (rules.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.livingitem.no_rules"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.translatable(
            "command.livingitem.rules_header", rules.size()), false);

        for (var entry : rules) {
            ResourceLocation id = entry.getKey();
            var rule = entry.getValue();
            int rows = rule.containerSize() / rule.columns();
            source.sendSuccess(() -> Component.literal(
                String.format("  §e%s§r: %d slots (%d×%d, cols=%d) — %s",
                    id, rule.containerSize(), rule.columns(), rows,
                    rule.columns(), rule.description())), false);
        }
        return 1;
    }

    /** 移除指定容器规则 */
    static int removeRule(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ResourceLocation containerId = ResourceLocationArgument.getId(ctx, "containerId");

        boolean removed = ContainerRuleConfig.removeAndSave(containerId);
        if (removed) {
            source.sendSuccess(() -> Component.translatable(
                "command.livingitem.container_removed", containerId.toString()), true);
            return 1;
        } else {
            source.sendFailure(Component.translatable(
                "command.livingitem.container_not_found", containerId.toString()));
            return 0;
        }
    }

    /** 从配置文件重新加载 */
    static int reloadRules(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ContainerRuleConfig.load();
        source.sendSuccess(() -> Component.translatable(
            "command.livingitem.rules_reloaded"), true);
        return 1;
    }

    /**
     * 玩家视线射线检测，获取指向的方块。
     * <p>使用原版的 {@code Player RayTrace}，最大距离 5 格。</p>
     */
    private static BlockHitResult getPlayerPOVHitResult(CommandSourceStack source)
            throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        var level = source.getLevel();

        // 使用原版的射线检测逻辑
        double reach = 5.0;
        var lookVec = player.getLookAngle();
        var eyePos = player.getEyePosition();
        var endPos = eyePos.add(lookVec.x * reach, lookVec.y * reach, lookVec.z * reach);

        BlockHitResult hit = level.clip(
            new ClipContext(
                eyePos, endPos,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
            )
        );

        if (hit.getType() == HitResult.Type.MISS) {
            throw NOT_LOOKING_AT_CONTAINER.create();
        }
        return hit;
    }

    /**
     * 构建标准矩形容器规则。
     * <p>自动生成方向映射、槽位范围等。</p>
     *
     * @param columns 容器列数（即一行有多少个槽位）
     */
    private static com.qiqi.li.living.transfer.ContainerCompatibilityConfig.ContainerRule buildRule(
            ResourceLocation containerId, int size, int columns) {
        return com.qiqi.li.living.transfer.ContainerCompatibilityConfig.ContainerRule.builder()
            .containerSize(size)
            .layoutType(com.qiqi.li.living.transfer.ContainerCompatibilityConfig.ContainerLayoutType.RECTANGULAR_STANDARD)
            .columns(columns)
            .validHostSlots(range(0, size - 1))
            .directionMapping(com.qiqi.li.living.model.Pos2D.LEFT, -1)
            .directionMapping(com.qiqi.li.living.model.Pos2D.RIGHT, 1)
            .directionMapping(com.qiqi.li.living.model.Pos2D.UP, -columns)
            .directionMapping(com.qiqi.li.living.model.Pos2D.DOWN, columns)
            .edgeBehavior(com.qiqi.li.living.transfer.ContainerCompatibilityConfig.EdgeBehavior.INVALIDATE)
            .description("玩家注册 " + containerId + " " + columns + "×" + (size / columns))
            .build();
    }

    private static java.util.List<Integer> range(int start, int end) {
        java.util.List<Integer> list = new java.util.ArrayList<>();
        for (int i = start; i <= end; i++) list.add(i);
        return list;
    }
}