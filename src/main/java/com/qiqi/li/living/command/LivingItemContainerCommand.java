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
 *   <li>{@code /livingitem container export}
 *     — 把玩家注册的增量规则导出为与模组自带资源同格式的 JSON
 *       （{@code config/living_item/exported_rules.json}，开发期用于合并进随包资源）</li>
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
                    .then(Commands.literal("export")
                        .executes(LivingItemContainerCommand::exportRules)
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

        // 注册并保存（已存在则覆盖——内置数据可能有误，玩家实测后应能修正）
        var rule = buildRule(containerId, size, columns);
        boolean overwrote = ContainerRuleConfig.addAndSave(containerId, rule);

        int rows = size / columns;
        if (overwrote) {
            boolean wasBundled = ContainerRuleConfig.isBundledRule(containerId);
            source.sendSuccess(() -> Component.translatable(
                wasBundled
                    ? "command.livingitem.container_overrode_bundled"
                    : "command.livingitem.container_overrode",
                containerId.toString(), size, columns, rows), true);
        } else {
            source.sendSuccess(() -> Component.translatable(
                "command.livingitem.container_registered",
                containerId.toString(), size, columns, rows), true);
        }
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
                String.format("  §7来源: §f%s§r", describeRuleSource(containerId))), false);
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
            if (ContainerRuleConfig.isRemovedByUser(containerId)) {
                source.sendSuccess(() -> Component.literal(
                    "  §7备注: §c已被玩家从内置规则中移除§r"), false);
            }

            // 显示自动推断的列数
            int inferredColumns = ContainerCompatibilityConfig.resolveColumns(actualSize, container);
            source.sendSuccess(() -> Component.literal(
                String.format("  §7推断列数: §f%d§r  §8(输入 /livingitem container register %d 注册)§r",
                    inferredColumns, inferredColumns)), false);
        }
        return 1;
    }

    /** 描述一条规则的来源：内置 / 玩家覆盖内置 / 玩家新增 */
    private static String describeRuleSource(ResourceLocation containerId) {
        boolean bundled = ContainerRuleConfig.isBundledRule(containerId);
        boolean userModified = ContainerRuleConfig.isUserModified(containerId);
        if (bundled && userModified) return "§6玩家覆盖（原为内置）§r";
        if (bundled) return "§7内置§r";
        return "§a玩家注册§r";
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
            boolean bundled = ContainerRuleConfig.isBundledRule(id);
            boolean userModified = ContainerRuleConfig.isUserModified(id);
            // 标记来源：* = 玩家动过（新增/覆盖），无标记 = 纯内置
            String mark = userModified ? (bundled ? "§6*§r" : "§a+§r") : " ";
            source.sendSuccess(() -> Component.literal(
                String.format(" %s§e%s§r: %d slots (%d×%d, cols=%d) — %s",
                    mark, id, rule.containerSize(), rule.columns(), rows,
                    rule.columns(), rule.description())), false);
        }
        source.sendSuccess(() -> Component.literal(
            "§8  §a+§8 = 玩家注册   §6*§8 = 玩家覆盖内置§r"), false);
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
     * 导出当前全部生效规则，供玩家提交给模组作者以扩展容器兼容性。
     *
     * <p>导出 = 内存中生效的规则全集（内置 + 玩家新增 − 玩家删除），是一份
     * <b>完整快照</b>，与 {@code assets/living_item/container_rules.json} 同格式，
     * 作者可<b>直接用它对内置资源做文件级覆盖</b>，无需逐条摘录或手工合并。</p>
     *
     * <p>典型用途：玩家装了一个模组作者没适配的容器 → 游戏里 register 校准 →
     * export → 把文件发给作者 → 作者覆盖内置资源重新打包 → 兼容性随包发布给所有人。</p>
     */
    static int exportRules(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int count = ContainerRuleConfig.exportBundledFormat();

        if (count < 0) {
            source.sendFailure(Component.translatable("command.livingitem.export_failed"));
            return 0;
        }

        if (count == 0) {
            source.sendSuccess(() -> Component.translatable("command.livingitem.export_empty"), false);
            return 0;
        }

        int bundled = ContainerRuleConfig.countBundled();
        int user = ContainerRuleConfig.countUser();
        String fileName = ContainerRuleConfig.getExportFile().toString();
        source.sendSuccess(() -> Component.translatable(
            "command.livingitem.export_done", count, fileName, bundled, user), false);
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