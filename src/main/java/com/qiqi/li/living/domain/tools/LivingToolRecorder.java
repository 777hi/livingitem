package com.qiqi.li.living.domain.tools;

import javax.annotation.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
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

    /**
     * 判定是否为「活武器」—— 活物品 + 命中原版「<b>可附魔类别</b>」武器标签。
     *
     * <p>⭐ <b>用官方标签，不用 {@code instanceof SwordItem}</b>（{@code docs/idea.md} §1.6）：
     * 这是 Mojang 自己的分类口径，模组武器通常也会正确归类 ⇒ <b>自动兼容</b>。
     * ⇒ <b>活石头不在任何武器标签里 ⇒ 不会被误判成武器</b> ✅</p>
     *
     * <p>⚠️ <b>本期（剑类）只认 {@code WEAPON_ENCHANTABLE}</b> ——
     * 弓 / 弩 / 三叉戟属于「<b>蓄力型</b>」（{@code UseAnim != NONE}），回放需要
     * 「开始 → 持续推进 → 释放」三步状态机，而 <b>FakePlayer 的 {@code tick()} 是空实现</b>
     * （{@code L27} 一脉）不会自动推进 ⇒ 留到那一期再开，否则会出现「只拉弓、射不出去」。</p>
     *
     * <p>⚠️ 该标签<b>包含斧</b> ⇒ 活斧子<b>既是工具又是武器</b>，两条路都走（D3 共存）。</p>
     */
    public static boolean isLivingWeapon(ItemStack stack) {
        if (stack.isEmpty() || !LivingItemManager.isLivingItem(stack)) {
            return false;
        }
        return stack.is(ItemTags.WEAPON_ENCHANTABLE);
    }

    /**
     * 是否由 {@code LivingToolFunction} 驱动 —— 活【工具】<b>或</b>活【武器】。
     *
     * <p>⭐ <b>这是「谁来 tick」的单一判据来源</b>：{@code LivingToolFunction#canApply}、
     * 掉落物形态的扫描入口（{@code LivingItem#processItemEntityContainers}）都用它。</p>
     *
     * <p>🔴 <b>事故（2026-09-24）</b>：掉落物形态的入口原先只写 {@code isLivingTool}
     * ⇒ <b>活剑被整个跳过 ⇒ 不 tick ⇒ 不攻击</b>。
     * ⚠️ 别在各处各写一遍 {@code isLivingTool(x) || isLivingWeapon(x)} ——
     * 环成员口径就曾在 3 处重复，加武器时漏改一处即出问题（活剑"隐身"）。</p>
     */
    public static boolean isLivingToolOrWeapon(ItemStack stack) {
        return isLivingTool(stack) || isLivingWeapon(stack);
    }

    // ------------------------------------------------------------------
    // 「帮忙型」成员口径（⭐ 全项目唯一 —— 渲染 / 同步 / 辅助三端共用）
    // ------------------------------------------------------------------

    /* 曾经这段逻辑在三处各写一遍（渲染端 / 同步端 / 辅助端），加武器时必须同步改三处 ⇒ 极易漏。
     * 现在收敛成下面两个方法，语义分工见 {@code docs/idea.md} §1.7 的表格。 */

    /**
     * 环成员：无记忆的（活工具 ∪ 活武器）—— 决定「<b>看不看得见</b>」。
     *
     * <p>⭐ <b>有记忆的不上环</b>（用户定的分工）—— 它自己会去干活，两套机制别混。</p>
     */
    public static boolean isAssistItem(ItemStack stack) {
        return !stack.isEmpty()
            && LivingItemManager.isLivingItem(stack)
            && (isLivingTool(stack) || isLivingWeapon(stack))
            && LivingItemManager.getToolMemory(stack).isEmpty();
    }

    /**
     * 辅助<b>挖掘</b>成员：无记忆的【活工具】—— <b>武器不参与挖掘</b>。
     *
     * <p>与 {@link #isAssistItem} 的差别只在「要不要带武器」：
     * 环是展示位（活剑也该看得见），挖掘是干活位（活剑挖不动方块）。</p>
     */
    public static boolean isAssistTool(ItemStack stack) {
        return isLivingTool(stack)
            && LivingItemManager.getToolMemory(stack).isEmpty();
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
     * 左键<b>落在方块上</b> → 先清除记忆（{@code L43}）——
     * 活工具清<b>挖掘</b>、活武器清<b>攻击</b>（活斧子两条都清）。
     *
     * <p>⭐ <b>活武器为什么也要清</b>：左键挥向方块 = <b>这一刀没打到怪</b>
     * ⇒ 清掉攻击记忆 ⇒ 按 S2 分派自然回到「挖掘模式」。
     * ⇒ 于是「挥一刀空 / 挥向方块」就能在<b>打架 ⇄ 挖矿</b>之间切换，无需额外操作。</p>
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
        boolean asTool = isLivingTool(tool);
        boolean asWeapon = isLivingWeapon(tool);
        if (!asTool && !asWeapon) {
            return;
        }

        LivingToolMemory memory = LivingItemManager.getToolMemory(tool);
        LivingToolMemory updated = memory;
        if (asTool) {
            updated = updated.withoutDig();
        }
        if (asWeapon) {
            updated = updated.withoutAttack();
        }
        if (updated.equals(memory)) {
            return;   // 本来就没有记忆 → 不必写
        }

        LivingItemManager.setToolMemory(tool, updated);
    }

    // ------------------------------------------------------------------
    // 攻击记忆（活武器 —— 见 {@code docs/idea.md} §1.5）
    // ------------------------------------------------------------------

    /**
     * 活武器<b>造成伤害</b> → 记录攻击记忆（{@code R1}）。
     *
     * <p>⭐ <b>不区分左右键</b>（{@code R4}）：不管玩家是左键挥还是右键放，
     * 只要「<b>这把活武器造成了伤害</b>」就录 —— 绕开了"这武器到底用哪只手"的判断。</p>
     *
     * <p>⚠️ 三个坑（{@code docs/idea.md} §1.5）：</p>
     * <ol>
     *   <li><b>远程武器</b>（弓）{@code directEntity} 是箭 ⇒ {@code getWeaponItem()} 返回 null
     *       —— <b>本期只做近战（剑类）</b>，不涉及；将来支持弓需 fallback 到取主手。</li>
     *   <li><b>非攻击伤害</b>（火焰附加 / 摔落 / 中毒 / 药水）同样触发本事件
     *       ⇒ 只认 {@code getWeaponItem()} 是<b>活武器</b>的那些。</li>
     *   <li>🔴 <b>回放时会自我录制</b> —— FakePlayer 造成的伤害照常触发本事件
     *       ⇒ 必须排除，否则活武器会把"自己打的"录成新记忆，越打越偏（同 {@code L39}）。</li>
     * </ol>
     */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Post event) {
        // ⭐ 用 Post 而不是 Pre：伤害已结算完毕。用 Pre 的话，
        //    被取消 / 被减免到 0 的攻击也会被录进去（玩家其实"没打到"）。
        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 坑 3：FakePlayer 是 ServerPlayer 的子类，instanceof 挡不住 ⇒ 显式 isFakePlayer()
        if (!isRecordablePlayer(player)) {
            return;
        }

        // 坑 2：只认"由这把活武器造成的"伤害
        ItemStack weapon = source.getWeaponItem();
        if (weapon == null || !isLivingWeapon(weapon)) {
            return;
        }

        LivingEntity target = event.getEntity();
        if (target == player) {
            return;   // 打到自己不算
        }

        // R5：一次攻击命中多个目标（横扫 / 多重）会对【每个】目标各触发一次本事件。
        // 借 L44 的保护期实现"只记第一个" —— 后续几次落在保护期内被跳过。
        if (isInRecordGrace(player)) {
            return;
        }

        Vec3 eye = player.getEyePosition();
        // ⭐ 朝向取【玩家视线】，不是「眼睛 → 怪物包围盒中心」——
        //    后者在玩家瞄头 / 瞄脚、或怪物高矮不同时会偏出很大角度
        //    （2026-09-24 用户实测："记忆射线朝向不是攻击时的视角朝向"）。
        //    ⇒ 与挖掘侧 {@link #raycastSurface} 保持同构：那边也是【沿视线】取命中点。
        //
        //    长度仍取「到目标的距离」⇒ 回放时射线长度够得着目标（D2：沿射线找实体）。
        Vec3 look = player.getViewVector(1.0F).normalize();
        double distance = eye.distanceTo(target.getBoundingBox().getCenter());
        Vec3 hitLocation = eye.add(look.scale(Math.min(distance, MAX_RAY_LENGTH)));

        // R2：蹲下时额外记住生物类型（完全类比 L4 的"蹲下记方块类型"）
        EntityType<?> type = player.isShiftKeyDown() ? target.getType() : null;

        recordAttack(weapon, eye, hitLocation, type);
        markRecorded(player);
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

    private static void recordAttack(ItemStack weapon, Vec3 eye, Vec3 hitLocation,
                                     @Nullable EntityType<?> type) {
        LivingToolMemory.AttackMemory ray =
            LivingToolMemory.AttackMemory.record(eye, hitLocation, type);
        LivingToolMemory memory = LivingItemManager.getToolMemory(weapon);
        LivingItemManager.setToolMemory(weapon, memory.withAttack(ray));
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
