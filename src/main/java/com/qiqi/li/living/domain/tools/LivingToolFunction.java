package com.qiqi.li.living.domain.tools;
import com.qiqi.li.living.container.HostRef;
import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.domain.tools.LivingToolProgress;
import com.qiqi.li.living.domain.tools.LivingToolMemory;
import com.qiqi.li.living.domain.tools.LivingToolAction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.qiqi.li.living.api.LivingItemActivation;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.components.OwnerNameResolver;
import com.qiqi.li.living.config.GameplayConfig;
import com.qiqi.li.living.container.ContainerAnchor;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ItemEntityContainerContext;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.container.TickContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 活工具功能 —— 把记忆回放挂进现有的容器 tick 管线。
 *
 * <p>只要活工具处在被扫描的容器里（箱子 / 玩家背包 …），
 * {@code ContainerLivingItemHandler} 每 tick 就会把活工具分组后调到这里，
 * 无需新增任何扫描通道（掉落物形态另需 {@code L-f} 的通道）。</p>
 *
 * <p>射线起点（{@code L14}）：
 * <ul>
 *   <li>方块容器 → <b>容器方块中心</b>（{@code L14=a}）</li>
 *   <li>玩家背包 → <b>玩家眼睛</b>（{@code L3=a}，与录制端一致）</li>
 * </ul>
 * 记忆存的是"这条线本身"，宿主迁移后换个起点重放，语义不变。</p>
 */
public class LivingToolFunction implements LivingItemFunction {

    public static final String ID = "living_tool";

    @Override
    public boolean canApply(ItemStack stack) {
        // ⭐ 活【工具】与活【武器】共用这一个 function（两者行为差异在 tick 里按记忆类型分派）。
        //    判据取 LivingToolRecorder#isLivingToolOrWeapon —— 单一来源，别在这里另写一遍。
        return LivingToolRecorder.isLivingToolOrWeapon(stack);
    }

    @Override
    public boolean supportsDroppedItem() {
        // resolveOrigin 早有 ItemEntityContainerContext 分支（L14=d：碰撞箱中心作射线起点）
        return true;
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    /**
     * 【活化时机】绑定主人（L25）—— 原先写在网络包里，现由功能自声明。
     *
     * <p>⭐ <b>为什么 owner 只在这里写</b>：它是「活工具 / 活武器」的数据
     * （消费方只有 {@code LivingToolReplay} / {@code LivingToolOwnerName}），
     * 而清理侧一直由本功能的 {@link #getOwnedComponentTypes()} 声明 ——
     * <b>读在功能、写也必须在功能</b>，否则就是「A2 只做了一半」的读写分离。</p>
     *
     * <p>⚠️ <b>{@code player == null} ⇒ 不写 owner</b>，这与活物品「无主」是既有合法态
     * 一致（tooltip 不显示主人行、回放走 {@code FALLBACK_UUID} 通用 FakePlayer）。
     * 内部产出（活耕地 / 活地图）走的正是这条路径。</p>
     */
    @Override
    public void onActivated(ItemStack stack, Level level, @Nullable Player player,
            LivingItemActivation.Via via) {
        if (player == null) return;                 // 缺席即无主（合法态）
        LivingItemManager.setToolOwner(stack, player.getUUID());
        // 顺带记下名字的「显示缓存」—— 主人离线时 tooltip 兜底；
        // 回放遇到在线主人会自动刷新（OwnerNameResolver）。
        LivingToolOwnerName.set(stack, player.getName().getString());
    }

    @Override
    public Set<DataComponentType<?>> getOwnedComponentTypes() {
        return Set.of(
            ToolComponents.LIVING_TOOL_MEMORY.value(),
            ToolComponents.LIVING_TOOL_PROGRESS.value(),
            ToolComponents.LIVING_TOOL_DIG_TICKS.value(),
            ToolComponents.LIVING_TOOL_LAST_ACTION.value(),
            LivingComponents.LIVING_TOOL_OWNER.value(),
            // 主人名字的显示缓存（tooltip 兜底）—— 取消活化时随 owned-types 一起清
            LivingComponents.LIVING_TOOL_OWNER_NAME.value(),
            // 射线微调配置（起点锚点 + 朝向跟随）—— 录制新记忆时重置
            ToolComponents.LIVING_TOOL_RAY_TUNING.value()
        );
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        Vec3 origin = resolveOrigin(context);

        // ⚠️ K2 上报必须在 origin 判断【之前】（2026-10-27）：
        //    客户端辅助挖掘需要知道「背包里有哪些工具」，而辅助**不受 `tool_and_weapon`
        //    开关控制**（那个开关管的是"借宿主位置做射线起点"，与辅助挖矿是两件事）。
        //    origin 为 null（开关关 / 无锚点）时命中布尔传 false —— 那两条射线本来也不显示。
        reportHostTools(context, entries, origin, serverLevel);

        if (origin == null) {
            return;
        }

        // 宿主自身的方块（大箱子两格）——射线检测前要先把起点移出去，否则会挖掉容器自己。
        // ⭐ 2026-10-25：黑名单从【本次实际用的锚点】派生，不再只从容器自己的位置列表拿。
        //    物品形态（活背包）的位置列表恒空、getBlockPos() 恒 null，而射线起点恰恰是
        //    宿主方块中心 ⇒ 不把那一格加进黑名单，扫描第一个穿到的格子就是宿主自己：
        //    蹲着记过方块类型 ⇒ 类型不匹配直接停（完全没反应）；没记类型 ⇒ 挖自己所在的箱子。
        //    方块容器本来就在列表里 ⇒ 对本类零副作用（同一格加两遍而已）。
        //    AtPlayer / AtEntity 无方块可加（掉落物形态刻意不跳过起点所在格，见下方注释）。
        Set<BlockPos> hostBlocks = hostBlocksOf(context);
        // 注：掉落物形态这里刻意【不】把"起点所在格"当宿主跳过 ——
        // 起点可能落在半砖 / 台阶这类非满高方块所在格的上半空气部分，
        // 那是"格子级"的误判，由 scanForTarget 的【形状求交】精确排除，
        // 直接跳过整格反而会漏掉那格里真正的目标。

        long now = level.getGameTime();

        for (SlotEntry entry : entries) {
            ItemStack tool = entry.stack();
            LivingToolMemory memory = LivingToolMemory.of(tool);
            if (memory.isEmpty()) {
                continue;
            }

            // ⭐ 射线微调（2026-09-30，仅「工具在玩家身上」时生效）：起点锚点 + 朝向跟随。
            //    memory 是【衍生副本】—— 原始记忆组件永不被修改（纯净射线 L15 不破坏）。
            //
            // ⚠️ 判据是「**锚点是不是玩家**」，不是「ctx 有没有 inventory」（2026-10-27 改）：
            //    · 玩家背包：anchor = AtPlayer ⇒ 微调 ✓
            //    · 物品形态容器背在身上：hostAnchor = AtPlayer ⇒ 微调 ✓
            //      （原来这里判 `instanceof SimpleContainerContext && getInventory() != null`，
            //       而物品形态的 inventory 恒 null ⇒ 背包里的工具永远从眼睛出、不认配置）
            //    · 末影箱：锚点是 AtBlock ⇒ 不微调（工具不是从身体发射的）
            //    · 方块 / 实体容器 ⇒ 不微调
            //    ⇒ 与 origin 同源（都读 effectiveAnchor），不会出现「起点在胸口、微调按脚下算」。
            Player bearer = effectiveAnchor(context) instanceof ContainerAnchor.AtPlayer at
                ? at.player()
                : null;
            Vec3 entryOrigin = origin;
            if (bearer != null) {
                LivingToolRayTuning tuning = LivingToolRayTuning.of(tool);
                Vec3 anchored = tuning.resolveAnchorOrigin(bearer, 1.0F);
                if (anchored != null) {
                    entryOrigin = anchored;   // 锚点全在身体中轴上 ⇒ 起点不随朝向转
                }
                memory = tuning.transform(memory, bearer, 1.0F);
            }

            // ⭐ 记录本 tick 开始时的"动画状态"（下面用于检测翻转）
            LivingToolProgress progressBefore = LivingToolProgress.of(tool);
            LivingToolAction actionBefore = LivingToolAction.of(tool);

            // ⭐ S2「射线决定」+【双记忆共存】（方案 C，用户 2026-09-24 定）：
            //
            //   有 attack 记忆时，先看射线上有没有怪：
            //     有怪 → 冷却满则打；冷却中则【等待】（不转去挖方块）
            //     没怪 → 转去按 dig / use 记忆干活
            //
            //   ⇒ 于是活斧子（既是工具又是武器）能「**有怪打怪、没怪挖矿**」，
            //     两条记忆都真正有效 —— 而不像早先那样"有 attack 就再也不挖了"。
            boolean handled = false;
            if (memory.hasAttack()) {
                LivingToolReplay.AttackResult result = LivingToolReplay.replayAttack(
                    tool, memory.attack(), entryOrigin, hostBlocks, serverLevel, now);
                if (result.outcome() == LivingToolReplay.Outcome.ATTACKED) {
                    // ⭐ 攻击侧【不走】writeBack 的 matches 短路，按「记录是否翻转」写回：
                    //    matches 对「只变运行时组件」的栈可能误判相等（项目已知坑，见 syncStateFlip），
                    //    而不扣耐久的武器（灾变系自结伤害型）攻击后恰好只变 LIVING_TOOL_LAST_ACTION ——
                    //    在返回副本的宿主 handler（getItem 非实时引用）下，就地写的记录会随副本丢弃
                    //    ⇒ 「上次攻击 tick」丢失 ⇒ 下 tick 判成首刀 ⇒ 高速连击。
                    //    出手每次都要保证冷却计时起点落进容器，这里 matches 救不了，直接写。
                    ItemStack after = result.tool();
                    context.setItem(entry.slotIndex(), after);
                    context.syncSlotToClients(entry.slotIndex(), after);
                    handled = true;
                } else if (result.outcome() == LivingToolReplay.Outcome.COOLING) {
                    handled = true;   // 专心等冷却 —— 别跑去挖方块，否则观感像"三心二意"
                }
                // NO_TARGET ⇒ 落到下面，转去挖掘 / 交互
            }

            if (!handled) {
                // 挖掘记忆（左键行为）
                ItemStack afterDig = LivingToolReplay.replayDig(tool, memory.dig(), entryOrigin, hostBlocks, serverLevel, now);
                if (afterDig != null) {
                    writeBack(context, entry.slotIndex(), tool, afterDig);
                } else {
                    // 交互记忆（右键行为）—— 与挖掘互斥，避免同一 tick 双写
                    ItemStack afterUse = LivingToolReplay.replayUse(tool, memory.use(), entryOrigin, hostBlocks, serverLevel);
                    if (afterUse != null) {
                        writeBack(context, entry.slotIndex(), tool, afterUse);
                    }
                }
            }

            // ⭐ 动画状态（挖掘进度 / 瞬时动作）的【翻转】必须额外同步一次 —— 见 syncStateFlip 的说明
            syncStateFlip(context, entry.slotIndex(), tool, progressBefore, actionBefore);
        }
    }

    /**
     * 活工具 tooltip —— 显示「模式 + 记忆内容」。
     *
     * <p>⭐ <b>为什么这个 tooltip 重要</b>：记忆是<b>隐形</b>的（手持才画射线、容器里还要 F3+B），
     * 而 {@code A3} 之后<b>"有没有记忆"直接决定这把工具是哪种模式</b>：</p>
     * <pre>
     *   无记忆 → 辅助模式（在背包里帮玩家挖）
     *   有记忆 → 自主模式（自己按记忆干活）
     * </pre>
     * <p>不说清楚的话，玩家根本不知道手上这把是「帮手」还是「工人」。</p>
     */
    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        LivingToolMemory memory = LivingToolMemory.of(stack);

        tooltipAdder.accept(Component.empty());
        // ⭐ 模式措辞「主动 / 被动」（2026-09-24 用户定，不带"挖掘"字眼 —— 活武器也适用）
        tooltipAdder.accept(Component.translatable(memory.isEmpty()
            ? "tooltip.livingitem.tool.mode.passive"
            : "tooltip.livingitem.tool.mode.active"));
        addOwnerLine(tooltipAdder, stack, LivingItemManager.getToolOwner(stack));

        addRayLine(tooltipAdder, "tooltip.livingitem.tool.dig", memory.dig());
        addRayLine(tooltipAdder, "tooltip.livingitem.tool.use", memory.use());
        addAttackLine(tooltipAdder, memory.attack());
        addRayTuningLine(tooltipAdder, stack);
    }

    /**
     * 一行射线微调信息（仅已配置时显示 —— 负信息不上 tooltip）。
     *
     * <p>「起点: 躯干底部 / 朝向: 跟随身体」。起点锚点未设但朝向跟随开启时
     * 显示「起点: 眼睛（默认）」，让这一行的两段语义始终完整。</p>
     */
    private static void addRayTuningLine(Consumer<Component> tooltipAdder, ItemStack stack) {
        LivingToolRayTuning tuning = LivingToolRayTuning.of(stack);
        if (tuning.isDefault()) {
            return;
        }
        Component origin = Component.translatable(tuning.anchor() == LivingToolRayTuning.RayAnchor.TORSO_BOTTOM
            ? "tooltip.livingitem.tool.ray.anchor.torso_bottom"
            : tuning.anchor() == LivingToolRayTuning.RayAnchor.TORSO_CENTER
                ? "tooltip.livingitem.tool.ray.anchor.torso_center"
                : "tooltip.livingitem.tool.ray.anchor.default");
        Component line = Component.translatable("tooltip.livingitem.tool.ray.origin", origin);
        if (tuning.followBody()) {
            line = line.copy().append(Component.translatable("tooltip.livingitem.tool.ray.follow"));
        }
        tooltipAdder.accept(line);
    }

    /**
     * 一行主人信息（「赋灵者」）—— 活工具与活武器共用本 function，一处覆盖两类。
     *
     * <p>⭐ 显示口径统一在 {@code OwnerNameResolver#displayName}：
     * 实时解析（tab 列表 / 本地 UsernameCache）→ 服务端维护的显示缓存
     * （{@link LivingToolOwnerName}，专治专用服务器上主人离线）→ 短 UUID。</p>
     *
     * <p>⚠️ 无主（自动活化等，owner 为 null 或 {@link LivingToolFakePlayer#FALLBACK_UUID}）
     * 则<b>不显示</b>这一行 —— 负信息不上 tooltip。</p>
     */
    private static void addOwnerLine(Consumer<Component> tooltipAdder, ItemStack stack, @Nullable UUID owner) {
        if (owner == null || LivingToolFakePlayer.FALLBACK_UUID.equals(owner)) {
            return;
        }
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.tool.owner",
            OwnerNameResolver.displayName(owner, LivingToolOwnerName.of(stack))));
    }

    /**
     * 一行攻击记忆信息（活武器）—— 结构与 {@link #addRayLine} 对称，
     * 但「限定类型」是<b>生物</b>（蹲下录的 {@code EntityType}）而非方块。
     */
    private static void addAttackLine(Consumer<Component> tooltipAdder,
                                      @Nullable LivingToolMemory.AttackMemory attack) {
        if (attack == null) {
            return;
        }
        Component line = Component.translatable("tooltip.livingitem.tool.attack",
            String.format("%.1f", attack.offset().length()));
        if (attack.entityType() != null) {
            line = line.copy().append(Component.translatable(
                "tooltip.livingitem.tool.limited", attack.entityType().getDescription()));
        }
        tooltipAdder.accept(line);
    }

    /** 一行记忆信息：距离恒有，类型约束（蹲下录的）才附加。 */
    private static void addRayLine(Consumer<Component> tooltipAdder, String key,
                                   @Nullable LivingToolMemory.RayMemory ray) {
        if (ray == null) {
            return;
        }
        Component line = Component.translatable(key, String.format("%.1f", ray.offset().length()));
        if (ray.block() != null) {
            line = line.copy().append(
                Component.translatable("tooltip.livingitem.tool.limited", ray.block().getName()));
        }
        tooltipAdder.accept(line);
    }

    /**
     * 解析射线起点 —— 三种宿主各有一条规则（{@code L3} / {@code L14}）。
     *
     * <table>
     *   <tr><th>宿主</th><th>起点</th><th>依据</th></tr>
     *   <tr><td>方块容器（箱子…）</td><td>容器方块中心</td><td>{@code L14=a}</td></tr>
     *   <tr><td>玩家背包</td><td>玩家眼睛</td><td>{@code L3=a}（与录制端一致）</td></tr>
     *   <tr><td>掉落物</td><td>实体位置</td><td>{@code L14=d}</td></tr>
     * </table>
     *
     * <p>记忆存的是"这条线本身"（完整偏移向量），换个起点重放，语义不变。</p>
     *
     * <p>⭐ 起点与黑名单<b>同源</b>（2026-10-25）：都用 {@link #effectiveAnchor}。
     * 起点在宿主方块中心时，那一格就是"我住在这里"，必须进黑名单
     * （见 {@link #hostBlocksOf}）。</p>
     */
    @Nullable
    private static Vec3 resolveOrigin(ContainerContext context) {
        return originOf(effectiveAnchor(context));
    }

    /**
     * K2 上报：登记「这个容器里有活工具」，供客户端渲染（射线 / 悬浮模型）与**辅助挖掘**用。
     *
     * <p>判据要分两问，别混（`D-container-15` 的同一教训）：</p>
     * <ol>
     *   <li><b>方块容器</b> ⇒ 用容器自己的位置（"我是不是方块容器"的身份问题）</li>
     *   <li><b>物品形态容器</b>（活背包）⇒ 用**宿主锚点**。内容在服务端 SavedData、
     *       客户端读不到 ⇒ 必须下发（TODO 6.1）</li>
     * </ol>
     * <p>玩家背包 / 掉落物不上报（客户端本来就知道内容）。</p>
     *
     * <p>⚠️ {@code origin} 为 {@code null} 时仍要上报（见调用点注释）：命中布尔给 {@code false}。</p>
     */
    private static void reportHostTools(ContainerContext context, List<SlotEntry> entries,
                                        @Nullable Vec3 origin, ServerLevel serverLevel) {
        BlockPos hostPos = context.getBlockPos();
        HostRef host = hostPos != null
            ? HostRef.ofBlock(hostPos)
            : hostRefOf(context.getHostAnchor(), context.getHostSlot());
        if (host == null) {
            return;
        }
        Set<BlockPos> hostBlocks = origin != null ? hostBlocksOf(context) : Set.of();
        List<LivingToolHostPacket.ToolRay> tools = new ArrayList<>(entries.size());
        for (SlotEntry entry : entries) {
            // ⭐ 只同步「打没打中」这个布尔（L48）—— 目标坐标不传：客户端画的是
            //    记忆射线本身（恒定），传坐标反而会让射线跟着目标跳。
            LivingToolMemory memory = LivingToolMemory.of(entry.stack());
            boolean digLanded = origin != null
                && LivingToolReplay.resolveDigTarget(memory.dig(), origin, hostBlocks, serverLevel) != null;
            boolean useLanded = origin != null
                && LivingToolReplay.resolveUseTarget(memory.use(), origin, hostBlocks, serverLevel) != null;
            tools.add(new LivingToolHostPacket.ToolRay(entry.stack(), digLanded, useLanded));
        }
        LivingToolHostSync.report(host, tools);
    }

    /**
     * 宿主锚点 → 同步包里的宿主引用；{@code null} = 没有可上报的宿主。
     *
     * <p>⚠️ 只给<b>物品形态容器</b>用（它的 {@code getBlockPos()} 恒 null）。方块容器走
     * 自己的位置，<b>不经过这里</b> —— 两者回答的是不同问题：一个问「我是不是方块容器」，
     * 一个问「我借住在谁那里」（`D-container-15` 的原话：参考物，不是坐标）。</p>
     *
     * <p>⚠️ {@code hostSlot} 必须带过去：一个宿主能装多个背包，只按宿主做键会撞
     * ⇒ 后写覆盖先写 ⇒ 客户端只画一个（见 {@code ContainerContext#getHostSlot()}）。</p>
     */
    @Nullable
    private static HostRef hostRefOf(@Nullable ContainerAnchor anchor, int hostSlot) {
        if (anchor instanceof ContainerAnchor.AtBlock block) {
            // ⚠️ 必须带 slot：背包放在**箱子里**时锚点是 AtBlock，同一个箱子装两个背包
            //    仍然会撞（一个箱子一个 pos）⇒ 后写覆盖先写。
            return HostRef.ofBlock(block.pos(), hostSlot);
        }
        if (anchor instanceof ContainerAnchor.AtPlayer playerAnchor) {
            return HostRef.ofPlayer(playerAnchor.player().getUUID(), hostSlot);
        }
        if (anchor instanceof ContainerAnchor.AtEntity entityAnchor) {
            return HostRef.ofEntity(entityAnchor.entity().getUUID(), hostSlot);
        }
        return null;
    }

    /**
     * 本次要用的锚点 —— 容器自己的；物品形态自己没有 ⇒ 玩法开关打开时借宿主的。
     *
     * <p>与 {@code BeaconFunction#effectiveAnchor} 同形：**「宿主是谁」只问一次**，
     * 取点（{@link #resolveOrigin}）与黑名单（{@link #hostBlocksOf}）共用它。</p>
     */
    // 包级可见：供单测验「取点与黑名单同源」（见 LivingToolHostBlockTest）。
    @Nullable
    static ContainerAnchor effectiveAnchor(ContainerContext context) {
        ContainerAnchor anchor = context.getAnchor();
        if (anchor == null && GameplayConfig.toolAndWeaponUsesHostAnchor()) {
            anchor = context.getHostAnchor();
        }
        return anchor;
    }

    /**
     * “别挖自己容器”黑名单 —— 容器自己的位置列表（大箱两格）+ 【锚点】指向的那一格。
     *
     * <p>为什么要加锚点那一格：物品形态（活背包）没有自己的位置，但射线起点是
     * <b>宿主方块中心</b>。不把它加进黑名单，{@code scanForTarget} 第一个穿到的格子
     * 就是宿主 ⇒ 蹲着记过类型 ⇒ 类型不匹配直接停（完全没反应）；没记类型 ⇒ 挖自己的箱子。</p>
     *
     * <p>对既有形态零副作用：方块容器的锚点那一格本来就在 {@code getAssociatedBlockPositions()}
     * 里（同一格加两遍而已）；玩家 / 实体锚点没有方块可加。</p>
     *
     * <p>⚠️ 大箱两半时只加锚点那一格（锚点是单个 {@code BlockPos}）⇒ 背包在大箱里时，
     * 另一半不在黑名单内。要完整支持需要宿主的完整位置列表（{@code getHostBlockPositions()}，
     * 新增 API，见设计稿 §6-4 与 TODO 6.2 已知局限）。</p>
     */
    // 包级可见：供单测直接验「黑名单含不含宿主那一格」（见 LivingToolHostBlockTest）。
    static Set<BlockPos> hostBlocksOf(ContainerContext context) {
        Set<BlockPos> blocks = new HashSet<>(context.getAssociatedBlockPositions());
        BlockPos hostPos = context.getBlockPos();
        if (hostPos != null) {
            blocks.add(hostPos);
        }
        ContainerAnchor anchor = effectiveAnchor(context);
        if (anchor instanceof ContainerAnchor.AtBlock block) {
            blocks.add(block.pos());
        }
        return blocks;
    }

    /**
     * 锚点 → 射线起点。
     *
     * <p>取点差异是<b>有意的</b>（见 {@code ContainerAnchor} 类注释）：方块取中心、
     * 玩家取<b>眼睛</b>（与录制端一致）、掉落物取<b>碰撞箱中心</b>。</p>
     */
    private static Vec3 originOf(ContainerAnchor anchor) {
        if (anchor instanceof ContainerAnchor.AtBlock block) {
            // L14 = a：容器方块中心
            return Vec3.atCenterOf(block.pos());
        }
        if (anchor instanceof ContainerAnchor.AtPlayer at) {
            // L3 = a：与录制端一致，用玩家眼睛位置
            return at.player().getEyePosition();
        }
        if (anchor instanceof ContainerAnchor.AtEntity entityAnchor) {
            // L14 = d：掉落物【碰撞箱中心】—— 用 position() 会落在脚下方块里（见 rayOrigin 说明）。
            // 船/矿车里的活工具本来就不重放 ⇒ 维持 null（不是遗漏）。
            return entityAnchor.entity() instanceof net.minecraft.world.entity.item.ItemEntity item
                ? ItemEntityContainerContext.rayOrigin(item) : null;
        }
        return null;
    }

    /**
     * 把挖掘后的工具写回容器。
     *
     * <p>破坏会扣耐久，甚至可能因 {@code F3} 损坏为空 —— 必须写回并触发同步，
     * 否则客户端看到的还是旧耐久。</p>
     */
    /**
     * 动画状态的<b>翻转</b>（挖掘进度 / 瞬时动作发生变化）时同步一次。
     *
     * <p>⭐ <b>为什么必须单独补这一步</b>：{@link #writeBack} 开头有
     * {@code ItemStack.matches(before, after)} 短路，而
     * <b>{@code PatchedDataComponentMap.equals()} 检测不到自定义组件的变化</b>（项目已知坑）——
     * 于是「只改了 {@code LIVING_TOOL_PROGRESS} / {@code LIVING_TOOL_LAST_ACTION}」时
     * {@code matches} 返回 {@code true}，直接 {@code return}，<b>永远不同步</b>。</p>
     *
     * <p>各形态表现不同，正是取决于有没有兜底通道：</p>
     * <table>
     *   <tr><th>形态</th><th>兜底通道</th><th>不补这步会怎样</th></tr>
     *   <tr><td>方块容器</td><td>{@code K2} 每 tick 全量同步</td><td>看得到（侥幸）</td></tr>
     *   <tr><td>玩家背包</td><td>原版 {@code broadcastChanges()}</td><td>看得到（侥幸）</td></tr>
     *   <tr><td><b>掉落物</b></td><td><b>无</b></td><td>❌ <b>永远看不到"开始挖 / 停挖 / 刚交互"</b> ⇒ 模型没动画</td></tr>
     * </table>
     *
     * <p>两个组件都是<b>翻转</b>语义（挖掘期间进度内容不变；动作 tick 每次不同），
     * 故不会每 tick 重复发。</p>
     */
    private static void syncStateFlip(ContainerContext context, int slot, ItemStack tool,
                                      @Nullable LivingToolProgress progressBefore,
                                      @Nullable LivingToolAction actionBefore) {
        boolean changed = !Objects.equals(progressBefore, LivingToolProgress.of(tool))
            || !Objects.equals(actionBefore, LivingToolAction.of(tool));
        if (changed) {
            context.syncSlotToClients(slot, tool);
        }
    }

    private static void writeBack(ContainerContext context, int slot, ItemStack before, ItemStack after) {
        if (ItemStack.matches(before, after)) {
            return;
        }
        context.setItem(slot, after);
        context.syncSlotToClients(slot, after);
    }
}
