package com.qiqi.li.living.domain.tools;

import javax.annotation.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * 活工具记忆录制器 —— 把玩家的操作行为录进 {@link LivingToolMemory}。
 *
 * <p>两条录制通道（均<b>无需 mixin</b>，见 {@code L6/B1} 的技术路线简化）：
 * <ul>
 *   <li><b>挖掘记忆</b>（左键）：{@link BlockEvent.BreakEvent} —— 方块真正被破坏时触发一次</li>
 *   <li><b>交互记忆</b>（右键）：{@link BlockEvent.BlockToolModificationEvent} ——
 *       去皮 / 耕地 / 铺路等 {@code ItemAbility} 触发时</li>
 * </ul>
 *
 * <p>关键约束：
 * <ul>
 *   <li><b>必须过滤 {@code isSimulated()}</b>（{@code L6 详解}）—— 原版会先跑一趟 simulate，
 *       不过滤会重复录制。</li>
 *   <li><b>录制「纯净射线」</b>（{@code L15}）—— 存的是「眼睛 → 命中点（方块表面）」的原始偏移，
 *       不做归一化、不加余量、不改用方块中心，保证与玩家 F3 看到的射线逐位一致。</li>
 *   <li><b>只在蹲下时记录方块类型</b>（{@code L4}）。</li>
 * </ul>
 */
public final class LivingToolRecorder {

    /**
     * 记忆写入后的「保护期」（tick）。1 秒 = 20 tick（{@code L44}）。
     *
     * <p>玩家挖完一个方块后往往来不及松开左键，准星会顺势落到下一个方块上；
     * 这点「惯性误触」不该把刚录好的记忆清掉，故写入记忆后 1 秒内的清除操作一律跳过。</p>
     */
    private static final long RECORD_GRACE_TICKS = 20L;

    /**
     * 录制射线长度上限（格）—— ⚠️ 与 {@code LivingToolReplay#MAX_SCAN_LENGTH} <b>必须一致</b>。
     *
     * <p>录制端就截断，避免录出"超长射线"（回放时逐格扫描是热路径，
     * 且 {@code getBlockState} 会强制加载区块 —— 见 {@code docs/idea.md} §2.7）。
     * 正常玩法只有 ~4.5 格，本上限只用于防模组放大 {@code blockInteractionRange}。</p>
     */
    private static final double MAX_RAY_LENGTH = 32.0;

    /** 玩家最近一次写入记忆的世界轴 tick（服务端内存态，登出 / 停服清理）。 */
    private static final Map<UUID, Long> LAST_RECORD_TICK = new ConcurrentHashMap<>();

    private LivingToolRecorder() {
    }

    /**
     * 判定是否为「活工具」—— 活物品 + 具备任一挖掘能力。
     *
     * <p>沿用项目的语义判定惯例（参见 {@code Tillables#isHoeLike}）：<b>不枚举物品清单</b>，
     * 只认 {@code ItemAbility}，这样模组工具（含多工具合一型）自动兼容。</p>
     */
    public static boolean isLivingTool(ItemStack stack) {
        if (stack.isEmpty() || !LivingItemManager.isLivingItem(stack)) {
            return false;
        }
        return stack.canPerformAction(ItemAbilities.PICKAXE_DIG)
            || stack.canPerformAction(ItemAbilities.AXE_DIG)
            || stack.canPerformAction(ItemAbilities.SHOVEL_DIG)
            || stack.canPerformAction(ItemAbilities.HOE_DIG);
    }

    // ------------------------------------------------------------------
    // 挖掘记忆（左键）
    // ------------------------------------------------------------------

    /**
     * 方块被玩家破坏 → 记录挖掘记忆。
     *
     * <p>挖掘只认<b>主手</b>（原版 {@code ServerPlayerGameMode#destroyBlock} 用的也是
     * {@code player.getMainHandItem()}），副手不参与。</p>
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (!isRecordablePlayer(player)) {
            return;
        }
        ItemStack tool = player.getMainHandItem();
        if (!isLivingTool(tool)) {
            return;
        }

        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 hitLocation = raycastSurface(player, level, eye, event.getPos());

        // 蹲下时额外记住方块类型（L4）
        Block target = player.isShiftKeyDown() ? level.getBlockState(event.getPos()).getBlock() : null;

        recordDig(tool, eye, hitLocation, target);
        markRecorded(player);
    }

    // ------------------------------------------------------------------
    // 交互记忆（右键）
    // ------------------------------------------------------------------

    /**
     * 工具对方块执行 ItemAbility（去皮 / 耕地 / 铺路 …）→ 记录交互记忆。
     */
    @SubscribeEvent
    public static void onToolModify(BlockEvent.BlockToolModificationEvent event) {
        // 原版会先跑一趟 simulate，必须过滤，否则重复录制
        if (event.isSimulated()) {
            return;
        }
        Player player = event.getPlayer();
        if (!isRecordablePlayer(player)) {
            return;
        }
        ItemStack tool = event.getHeldItemStack();
        if (!isLivingTool(tool)) {
            return;
        }

        Vec3 eye = player.getEyePosition();
        // UseOnContext 直接给出了精确点击位置（方块表面），无需自行 clip
        Vec3 hitLocation = event.getContext().getClickLocation();

        Block target = player.isShiftKeyDown() ? event.getState().getBlock() : null;

        recordUse(tool, eye, hitLocation, target);
        markRecorded(player);
    }

    // ------------------------------------------------------------------
    // 清除记忆（L13 / L17 / L18）
    // ------------------------------------------------------------------

    /**
     * 右键<b>没形成有效交互</b> → 清除活工具的交互记忆（{@code L43}）。
     *
     * <p>设计意图 —— <b>「记忆难得易忘」</b>：只有完整操作才留下记忆，半途而废就忘却。</p>
     *
     * <p>⚠️ <b>不做"是否命中空气"的判断</b>（曾误加过 `hit.getType()==MISS` 过滤）：
     * {@code RightClickItem} 在"命中方块但交互 PASS"时也会触发，而<b>这是正常情况</b> ——
     * 在狭小空间里玩家根本无法对着空气右键，若要求 MISS 就会导致交互记忆<b>永远清不掉</b>。
     * 这里的判据就是「这次右键没产生任何有效交互」。</p>
     *
     * <p>为什么不需要网络包：{@code RightClickItem} 在<b>服务端也会触发</b>
     * （只有 {@code LeftClickEmpty} 是单端的，见 {@code L43}）。</p>
     */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.isFakePlayer()) {
            return;
        }
        if (isInRecordGrace(player)) {
            return;   // L44：刚写完记忆，1 秒内的惯性误触不清除
        }

        ItemStack tool = player.getItemInHand(event.getHand());
        if (!isLivingTool(tool)) {
            return;
        }

        LivingToolMemory memory = LivingItemManager.getToolMemory(tool);
        if (!memory.hasUse()) {
            return;
        }

        LivingItemManager.setToolMemory(tool, memory.withoutUse());
        player.inventoryMenu.broadcastChanges();
    }

    /**
     * 左键<b>落在方块上</b> → 先清除挖掘记忆（{@code L43}）。
     *
     * <p>与 {@link #onBlockBreak} 配合构成「完整操作才形成记忆」：</p>
     * <pre>
     *   左键点方块（START_DESTROY_BLOCK，按住期间每 tick 触发） → 清除旧记忆
     *        ├─ 挖完 → BreakEvent → 记录新记忆 ✅
     *        └─ 没挖完（轻点/中途放弃） → 无 BreakEvent → 记忆保持为空 ✅
     * </pre>
     *
     * <p>于是「轻点一下方块」= 清除记忆、「完整挖完」= 形成记忆，语义天然对称。</p>
     */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        // 服务端只在「开始挖」与「结束挖」时触发本事件（不是每 tick）：
        //   START = 首次左键点在方块上  ← 用这个
        //   STOP  = 方块被挖完
        //   ABORT = 中途松手 / 换了目标（即"没挖完"）
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
            return;
        }
        if (!isRecordablePlayer(event.getEntity())) {
            return;
        }
        if (isInRecordGrace(event.getEntity())) {
            return;   // L44：刚写完记忆，1 秒内的惯性误触不清除
        }

        ItemStack tool = event.getItemStack();
        if (!isLivingTool(tool)) {
            return;
        }

        LivingToolMemory memory = LivingItemManager.getToolMemory(tool);
        if (!memory.hasDig()) {
            return;
        }

        LivingItemManager.setToolMemory(tool, memory.withoutDig());
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 是否为「可以录制记忆的真玩家」。
     *
     * <p>⚠️ <b>必须排除假玩家</b>：活工具回放时用的 {@link LivingToolFakePlayer} 会让
     * {@code BreakEvent} / {@code BlockToolModificationEvent} 照常触发，若不排除，
     * 活工具就会把「自己挖的这一次」当成新记忆录进去，<b>覆盖掉玩家录的记忆</b>，
     * 之后每次回放都自我覆盖一次，方向越飘越偏（典型症状：挖着挖着变成垂直向下）。</p>
     *
     * <p>注意 {@code FakePlayer} <b>是</b> {@code ServerPlayer} 的子类，
     * 所以 {@code instanceof ServerPlayer} 挡不住它，必须显式用 {@code isFakePlayer()}。</p>
     */
    private static boolean isRecordablePlayer(@Nullable Player player) {
        return player instanceof ServerPlayer && !player.isFakePlayer();
    }

    /**
     * 是否处于「刚写完记忆」的保护期内（{@code L44}）。
     *
     * <p>公开给其它清除入口复用 —— 「左键空气」走网络包，
     * 处理逻辑在 {@code ServerPacketHandler#handleToolMemoryClear}，不在本类内。</p>
     */
    public static boolean isInRecordGrace(@Nullable Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        Long last = LAST_RECORD_TICK.get(serverPlayer.getUUID());
        return last != null && serverPlayer.level().getGameTime() - last < RECORD_GRACE_TICKS;
    }

    /** 记下「刚刚写入过记忆」，开启 1 秒保护期。 */
    private static void markRecorded(Player player) {
        LAST_RECORD_TICK.put(player.getUUID(), player.level().getGameTime());
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_RECORD_TICK.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LAST_RECORD_TICK.clear();
    }

    private static void recordDig(ItemStack tool, Vec3 eye, Vec3 hitLocation, @Nullable Block target) {
        LivingToolMemory.RayMemory ray = LivingToolMemory.RayMemory.record(eye, hitLocation, target);
        LivingToolMemory memory = LivingItemManager.getToolMemory(tool);
        LivingItemManager.setToolMemory(tool, memory.withDig(ray));
    }

    private static void recordUse(ItemStack tool, Vec3 eye, Vec3 hitLocation, @Nullable Block target) {
        LivingToolMemory.RayMemory ray = LivingToolMemory.RayMemory.record(eye, hitLocation, target);
        LivingToolMemory memory = LivingItemManager.getToolMemory(tool);
        LivingItemManager.setToolMemory(tool, memory.withUse(ray));
    }

    /**
     * 从玩家眼睛沿视线做一次射线，返回<b>方块表面</b>的命中点。
     *
     * <p>{@code BreakEvent} 只给出 {@code BlockPos}（方块坐标），拿不到表面命中点；
     * 而 {@code L15} 要求录制"到表面就到表面"的纯净射线，故这里补一次 clip。
     * 这与原版 {@code ServerPlayerGameMode} 收到 START_DESTROY_BLOCK 时的判定同源。</p>
     */
    private static Vec3 raycastSurface(Player player, Level level, Vec3 eye, BlockPos fallbackPos) {
        Vec3 look = player.getViewVector(1.0F);
        // ⚠️ 封顶：blockInteractionRange 是属性，模组 / 附魔可能把它放大 ⇒ 会录出超长射线
        double reach = Math.min(player.blockInteractionRange(), MAX_RAY_LENGTH);
        BlockHitResult hit = level.clip(new ClipContext(
            eye,
            eye.add(look.scale(reach)),
            ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE,
            player
        ));
        return hit.getType() == HitResult.Type.BLOCK
            ? hit.getLocation()
            : Vec3.atCenterOf(fallbackPos);
    }
}
