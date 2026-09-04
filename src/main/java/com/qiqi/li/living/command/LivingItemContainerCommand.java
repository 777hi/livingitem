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
 *   <li>{@code /livingitem container register <width> <height>}
 *     — 注册玩家准心指向的容器，槽位数 = width × height</li>
 *   <li>{@code /livingitem container register <width> <height> <size>}
 *     — 手动指定槽位数（自动检测不准时纠错）</li>
 *   <li>{@code /livingitem container register <width> <height> <containerId>}
 *     — 注册指定容器 ID，槽位数 = width × height</li>
 *   <li>{@code /livingitem container register <width> <height> <size> <containerId>}
 *     — 全手动指定：槽位数 + 容器 ID</li>
 *   <li>{@code /livingitem container list}
 *     — 列出所有已注册的容器规则</li>
 *   <li>{@code /livingitem container remove <containerId>}
 *     — 移除指定容器规则</li>
 *   <li>{@code /livingitem container reload}
 *     — 从配置文件重新加载</li>
 * </ul>
 * <p>
 * <b>核心设计：</b>玩家可手动输入槽位数 {@code size} 纠错，不受自动检测结果干扰。
 * 当 {@code size != width × height} 时，系统跳过容器大小验证，适配不规则布局。
 * </p>
 */
@EventBusSubscriber
public class LivingItemContainerCommand {

    private static final SimpleCommandExceptionType NOT_LOOKING_AT_CONTAINER =
        new SimpleCommandExceptionType(Component.translatable("command.livingitem.not_looking_at_container"));

    private static final SimpleCommandExceptionType CONTAINER_SIZE_MISMATCH =
        new SimpleCommandExceptionType(Component.translatable("command.livingitem.container_size_mismatch"));

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // register 子指令树：
        //   register <width> <height>                    → auto-detect, size=w*h
        //   register <width> <height> <size>               → auto-detect, use given size
        //   register <width> <height> <containerId>        → auto-detect, size=w*h
        //   register <width> <height> <size> <containerId> → use given size & containerId
        dispatcher.register(
            Commands.literal("livingitem")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("container")
                    .then(Commands.literal("register")
                        .then(Commands.argument("width", IntegerArgumentType.integer(1, 54))
                            .then(Commands.argument("height", IntegerArgumentType.integer(1, 54))
                                // /livingitem container register <w> <h>
                                .executes(ctx -> registerTargetContainer(ctx, false, false))
                                // /livingitem container register <w> <h> <size>
                                .then(Commands.argument("size", IntegerArgumentType.integer(1, 256))
                                    .executes(ctx -> registerTargetContainer(ctx, false, true))
                                    // /livingitem container register <w> <h> <size> <containerId>
                                    .then(Commands.argument("containerId", ResourceLocationArgument.id())
                                        .executes(ctx -> registerTargetContainer(ctx, true, true))
                                    )
                                )
                                // /livingitem container register <w> <h> <containerId>
                                .then(Commands.argument("containerId", ResourceLocationArgument.id())
                                    .executes(ctx -> registerTargetContainer(ctx, true, false))
                                )
                            )
                        )
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
        int width = IntegerArgumentType.getInteger(ctx, "width");
        int height = IntegerArgumentType.getInteger(ctx, "height");

        // 确定槽位数：玩家手动指定 > 自动计算
        int size;
        int gridSize = width * height;
        if (hasExplicitSize) {
            size = IntegerArgumentType.getInteger(ctx, "size");
        } else {
            size = gridSize;
        }

        // 获取玩家准心指向的方块
        BlockHitResult hitResult = getPlayerPOVHitResult(source);
        BlockPos pos = hitResult.getBlockPos();
        Level level = source.getLevel();
        BlockEntity be = level.getBlockEntity(pos);

        if (!(be instanceof Container container)) {
            throw NOT_LOOKING_AT_CONTAINER.create();
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
                "command.livingitem.container_already_registered", containerId));
            return 0;
        }

        // 仅在 size == gridSize 时验证实际容器大小，否则跳过（玩家手动纠错）
        if (size == gridSize) {
            int actualSize = container.getContainerSize();
            if (actualSize != size) {
                source.sendFailure(Component.translatable(
                    "command.livingitem.container_size_mismatch",
                    actualSize, size));
                return 0;
            }
        } else {
            // 玩家手动指定了不同槽位数，说明自动检测不准，跳过验证
            source.sendSuccess(() -> Component.translatable(
                "command.livingitem.container_size_override",
                size, gridSize), false);
        }

        // 注册并保存
        var rule = buildRule(containerId, size, width);
        ContainerRuleConfig.addAndSave(containerId, rule);

        int rows = size / width;
        source.sendSuccess(() -> Component.translatable(
            "command.livingitem.container_registered",
            containerId, size, width, rows), true);
        return 1;
    }

    /** 列出所有已注册的容器规则 */
    private static int listRules(CommandContext<CommandSourceStack> ctx) {
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
    private static int removeRule(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ResourceLocation containerId = ResourceLocationArgument.getId(ctx, "containerId");

        boolean removed = ContainerRuleConfig.removeAndSave(containerId);
        if (removed) {
            source.sendSuccess(() -> Component.translatable(
                "command.livingitem.container_removed", containerId), true);
            return 1;
        } else {
            source.sendFailure(Component.translatable(
                "command.livingitem.container_not_found", containerId));
            return 0;
        }
    }

    /** 从配置文件重新加载 */
    private static int reloadRules(CommandContext<CommandSourceStack> ctx) {
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