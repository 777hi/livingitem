package com.qiqi.li.living.domain.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

/**
 * 活工具<b>辅助玩家挖掘</b>（{@code A3} 支线）—— 背包里的活工具给玩家的挖掘"搭把手"。
 *
 * <h3>边界：记忆是「分工开关」（用户定）</h3>
 * <ul>
 *   <li><b>有记忆</b> → 它<b>自己</b>干活（{@code L} 组记忆回放），<b>不</b>帮忙</li>
 *   <li><b>没记忆</b> → 它<b>帮玩家</b>挖（本类）</li>
 * </ul>
 *
 * <h3>⭐ 零 mixin —— 全靠 NeoForge 官方钩子</h3>
 * 原设计里标着"唯一技术难点"的 {@code B1}（Mixin {@code ServerPlayerGameMode} 拿破坏进度）
 * <b>整个不需要</b>：原版每 tick 调 {@code getDigSpeed} / {@code hasCorrectToolForDrops}，
 * 这两个位置正好都有钩子，"玩家正在挖哪一格"它自己会告诉我们，中断 / 换目标天然被处理。
 *
 * <table>
 *   <tr><th>维度</th><th>钩子</th><th>做法</th></tr>
 *   <tr><td><b>加速</b></td><td>{@link PlayerEvent.BreakSpeed}</td>
 *       <td>{@code setNewSpeed(原速 + Σ 各活工具速度)}</td></tr>
 *   <tr><td><b>材质门槛</b></td><td>{@link PlayerEvent.HarvestCheck}</td>
 *       <td>任意一把活工具挖得动 → {@code setCanHarvest(true)}</td></tr>
 *   <tr><td><b>附魔归属</b></td><td>{@link BlockDropsEvent}</td>
 *       <td>用<b>槽位最靠前</b>那把活工具重算掉落与经验（{@code E6}，原始需求原文）</td></tr>
 *   <tr><td><b>耐久</b></td><td>同上</td>
 *       <td>每把出过力的都扣 1 点，耐久附魔由 {@code hurtAndBreak} 内部生效（{@code F}）</td></tr>
 * </table>
 *
 * <h3>定案（2026-09-20 用户拍板）</h3>
 * <ul>
 *   <li>仅在<b>玩家背包</b>生效；手持普通工具时<b>也帮</b>；旁观<b>跳过</b>
 *       （创造 2026-09-25 放开 —— 与辅助攻击对齐）</li>
 *   <li>多把速度<b>直接相加、不设上限</b> —— 用户口径：
 *       <i>"玩家背包槽位数量就已经是上限了"</i></li>
 *   <li>不需要距离限制（只作用于玩家当前目标，天然受限）</li>
 * </ul>
 *
 * <h3>⚠️ 不与玩家"抢手"</h3>
 * 主手那把会被跳过（它已经在原版的 {@code getDigSpeed} 里算过一次了，重复计算会翻倍）；
 * 掉落重算也只在<b>玩家自己的工具挖不动</b>时才接管 —— 玩家能挖就完全走原版，不干预。
 */
public final class LivingToolAssist {

    private LivingToolAssist() {
    }

    // ── ① 加速 ──────────────────────────────────────────────────────────────

    /**
     * 玩家破坏速度计算时触发（原版每 tick 一次）—— 把背包里活工具的速度<b>累加</b>上去。
     *
     * <p>速度用 {@link LivingToolFakePlayer} 精算，于是
     * <b>效率附魔自动生效</b>（它是 {@code MINING_EFFICIENCY} 属性，见 {@code L46}）——
     * 不需要自己查附魔等级、也不会再次踩"效率不生效"的坑。</p>
     */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (!isAssistable(event.getEntity())) {
            return;
        }
        Player player = event.getEntity();
        BlockState state = event.getState();

        List<ItemStack> helpers = assistTools(player, s -> s.getDestroySpeed(state) > 1.0F);
        float bonus = 0.0F;
        for (ItemStack stack : helpers) {
            bonus += digSpeed(player, stack, state, event.getPosition().orElse(null));
        }
        if (bonus > 0.0F) {
            event.setNewSpeed(event.getNewSpeed() + bonus);
        }

        // 客户端记下"现在在挖哪一格"—— 模型的挖掘环据此转场。
        // 不需要网络同步：客户端本来就在跑同一套事件（见 LivingToolAssistState）。
        if (player.level().isClientSide && event.getPosition().isPresent()) {
            LivingToolAssistState.note(event.getPosition().get(),
                player.level().getGameTime(), event.getNewSpeed());
        }
    }

    // ── ② 材质门槛 ──────────────────────────────────────────────────────────

    /**
     * 玩家判定"能否收获"时触发 —— 只要背包里有<b>任意一把</b>活工具挖得动，就放行。
     *
     * <p>注意这里<b>只改"能不能"</b>：掉什么由 ③ 接管。两者分工明确。</p>
     */
    @SubscribeEvent
    public static void onHarvestCheck(PlayerEvent.HarvestCheck event) {
        if (event.canHarvest() || !isAssistable(event.getEntity())) {
            return;
        }
        Player player = event.getEntity();
        BlockState state = event.getTargetBlock();

        if (!assistTools(player, s -> s.isCorrectToolForDrops(state)).isEmpty()) {
            event.setCanHarvest(true);
        }
    }

    // ── ③ 附魔归属 + ④ 耐久 ────────────────────────────────────────────────

    /**
     * 方块被破坏、掉落已算出但<b>还没进世界</b>时触发 —— 让活工具的附魔接管归属。
     *
     * <p><b>玩家优先</b>：玩家自己的工具挖得动就完全走原版、不干预（不抢玩家的手）。</p>
     *
     * <p>接管时按 {@code E6}（原始需求原文）取<b>槽位最靠前</b>的那把活工具，
     * 用它重算掉落与经验 —— 于是时运 / 精准采集自然归属到它。</p>
     */
    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        Entity breaker = event.getBreaker();
        if (!(breaker instanceof ServerPlayer player) || !isAssistable(player)) {
            return;
        }
        BlockState state = event.getState();

        // 玩家自己的工具挖得动 → 尊重原版
        if (player.getMainHandItem().isCorrectToolForDrops(state)) {
            return;
        }

        List<ItemStack> helpers = assistTools(player, s -> s.isCorrectToolForDrops(state));
        if (helpers.isEmpty()) {
            return;
        }

        ServerLevel level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockEntity blockEntity = event.getBlockEntity();

        // 用槽位最靠前的那把重算掉落与经验（E6）
        ItemStack owner = helpers.get(0);
        List<ItemStack> recalc = Block.getDrops(state, level, pos, blockEntity, player, owner);
        event.getDrops().clear();
        for (ItemStack drop : recalc) {
            event.getDrops().add(new ItemEntity(level,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop));
        }
        event.setDroppedExperience(EnchantmentHelper.processBlockExperience(level, owner,
            state.getExpDrop(level, pos, blockEntity, player, owner)));

        // F：每把出过力的都扣耐久。
        // ⭐ 走【原版标准入口】hurtAndBreak(amount, entity, slot)，而不是自己拼一个空 lambda。
        //    它内部依次做三件事，**全都是官方口径** —— 模组只要也守规矩就自动兼容：
        //      ① Item#damageItem(stack, amount, entity, onBroken)  ← NeoForge 钩子
        //         （模组的"不毁 / 永恒"类附魔就是在这儿把 amount 改成 0 的）
        //      ② EnchantmentHelper.processDurabilityChange(...)    ← 原版耐久附魔 + 数据驱动减免
        //      ③ entity.onEquippedItemBroken(item, slot)           ← 破毁时广播（损坏音效）
        //    ⚠️ 2026-09-21 修正：原来传的是空 lambda ⇒ 第 ③ 条被跳过，工具挖坏时没声音。
        //    📌 槽位取 MAINHAND：背包里的工具没有真正的装备位，主手是最接近的语义；
        //       且该回调只会广播实体事件（音效），不会误动属性 —— 已核源码，安全。
        for (ItemStack helper : helpers) {
            helper.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 能不能为这个玩家出力（旁观排除；创造 2026-09-25 放开）。
     *
     * <p>⭐ <b>两端都要放行</b> —— 尤其是<b>客户端</b>：原版
     * {@code ServerPlayerGameMode#tick} 里 {@code incrementDestroyProgress} 的返回值被<b>丢弃</b>，
     * 服务端<b>不会自己破坏方块</b>；真正触发破坏的是客户端算完进度后发来的
     * {@code STOP_DESTROY_BLOCK}。也就是说 ——
     * <b>破坏的节奏由客户端控制</b>。只加速服务端的话，客户端仍按原速等满时长才松手，
     * 表现为「门槛与附魔都生效（服务端判定），唯独加速没感觉」（2026-09-20 实测）。</p>
     */
    private static boolean isAssistable(@Nullable Player player) {
        // ⭐ 创造模式 2026-09-25 放开（用户定）：挖掘侧放开无副作用（加速对瞬间挖掘无影响，
        //    材质门槛 / 掉落归属照样生效），且与辅助攻击的放开对齐；旁观依旧排除。
        if (player == null || player.isSpectator()) {
            return false;
        }
        // FakePlayer 不是真实玩家；且它会再次触发本事件（self 递归）
        return !(player instanceof ServerPlayer serverPlayer) || !serverPlayer.isFakePlayer();
    }

    /**
     * 遍历玩家背包，收集所有「可出力」的活工具（{@code A1}：仅玩家背包）。
     *
     * <p>⚠️ <b>跳过主手那一格</b>：原版 {@code getDigSpeed} 已经把手持工具算进去了，
     * 再算一次会让"自己持有的活工具"速度翻倍。</p>
     *
     * <p>按<b>槽位号升序</b>遍历（{@code E2}），故返回列表的第一项就是"最靠前"的那把。</p>
     */
    private static List<ItemStack> assistTools(Player player, Predicate<ItemStack> accept) {
        Inventory inventory = player.getInventory();
        List<ItemStack> out = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (slot == inventory.selected) {
                continue;   // 主手已由原版计入
            }
            ItemStack stack = inventory.getItem(slot);
            if (LivingToolRecorder.isAssistTool(stack) && accept.test(stack)) {
                out.add(stack);
            }
        }
        return out;
    }

    /**
     * 用 {@link LivingToolFakePlayer} 精算这把活工具对该方块的速度。
     *
     * <p>走 FakePlayer 而不是自己读 {@code ItemStack#getDestroySpeed} ——
     * 后者拿不到<b>效率附魔</b>（那是 {@code MINING_EFFICIENCY} 属性效果，需要走属性表，见 {@code L46}）。</p>
     *
     * <p>⚠️ 事件回调里已过滤 {@code isFakePlayer()}，故 FakePlayer 自己触发的事件不会再进来，无递归。</p>
     */
    private static float digSpeed(Player player, ItemStack stack, BlockState state,
                                  @Nullable BlockPos pos) {
        if (player instanceof ServerPlayer serverPlayer) {
            LivingToolFakePlayer fake =
                LivingToolFakePlayerCache.get(serverPlayer.serverLevel(), serverPlayer.getUUID());
            fake.setPos(player.getX(), player.getY(), player.getZ());
            fake.setOnGround(true);   // L28：不设会被原版判为"离地"→ 速度 /5
            fake.syncOwnerAttributes();   // 镜像主人属性（饰品增益 —— 挖掘速度/效率）
            fake.equipTool(stack.copy());
            return fake.getDigSpeed(state, pos);
        }
        // 客户端：本地算即可 —— 客户端没有 ServerLevel，也就没有 FakePlayer。
        // 这里只影响"客户端什么时候松手"，最终由服务端二次校验（f1 >= 0.7F）兜底。
        return localDigSpeed(player, stack, state);
    }

    /** 客户端侧的活工具速度：基础速度 + 效率附魔（原版 {@code MINING_EFFICIENCY} 同口径）。 */
    private static float localDigSpeed(Player player, ItemStack stack, BlockState state) {
        float speed = stack.getDestroySpeed(state);
        if (speed > 1.0F) {
            // 原版只在 f > 1.0F 时才叠加效率附魔
            speed += efficiencyBonus(player, stack);
        }
        return speed;
    }

    /**
     * 效率附魔的加成值 = <b>等级²</b>。
     *
     * <p>原版把它注册成 {@code Enchantments.EFFICIENCY} → {@code Attributes.MINING_EFFICIENCY} 的
     * <b>属性效果</b>（{@code LevelBasedValue.LevelsSquared}，见 {@code L46}）。
     * 服务端走 FakePlayer 的装备属性自动拿到；客户端没有那条路，故此处按同口径手工等价实现。</p>
     */
    private static float efficiencyBonus(Player player, ItemStack stack) {
        Holder<Enchantment> efficiency =
            player.level().registryAccess().holderOrThrow(Enchantments.EFFICIENCY);
        int level = stack.getEnchantmentLevel(efficiency);
        return (float) (level * level);
    }
}
