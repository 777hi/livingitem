package com.qiqi.li.living.domain.tools;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * 活工具回放用的假玩家（{@code L24}/{@code L25}）。
 *
 * <p>为什么需要它：原版破坏速度 {@code BlockState#getDestroyProgress(Player, ...)} 依赖
 * {@code Player}（手持物品、效率附魔、药水、着地状态都从 Player 读）。
 * 而容器 / 掉落物形态的活工具没有玩家 —— 故借一个 FakePlayer，把活工具放进它的主手，
 * 让<b>原版自己去算</b>，效率附魔、材质门槛、掉落判定全部自然生效。</p>
 *
 * <p>关键技巧 —— <b>过领地 / 保护插件</b>：
 * 覆写 {@code GameProfile#getId()} 与 {@code getUUID()} 返回<b>主人</b>的真实 UUID，
 * 保护插件按 UUID 判权限时会把它认成那个玩家（Create {@code DeployerGameProfile}、
 * Mekanism 同款做法）。</p>
 *
 * <p>⚠️ 继承 NeoForge 的 {@link FakePlayer}，它已阉割掉一批玩家能力（{@code L25 详解}）：
 * {@code tick()} 空实现、无敌、不收发网络包、不计统计进度、{@code isFakePlayer()==true}。
 * 其中 <b>{@code tick()} 是空的</b>意味着破坏进度不会自动推进 —— 必须我们自己推进（{@code L27}）。</p>
 */
public class LivingToolFakePlayer extends FakePlayer {

    /** 无主人（自动活化等场景）时的回退 UUID，取一个固定的常量以保证可预期。 */
    public static final UUID FALLBACK_UUID = UUID.fromString("1a7e5c30-0d21-4f1a-9c3e-6b8d2f5a7c11");

    @Nullable
    private final UUID owner;

    /** 上一次经 {@link #equipTool} 装配的工具，用于回收它留下的附魔属性修饰符。 */
    private ItemStack lastEquipped = ItemStack.EMPTY;

    public LivingToolFakePlayer(ServerLevel level, @Nullable UUID owner) {
        super(level, new OwnerGameProfile(owner));
        this.owner = owner;
        this.setNoGravity(true);   // 受控 tick：真链里的 travel 不得移动假玩家（每次回放 setPos 钉回原点）
    }

    /**
     * 把活工具装进主手 —— <b>并手动同步它附魔带来的属性修饰符</b>（{@code L46}）。
     *
     * <p>⚠️ <b>为什么不能只调 {@code setItemInHand}：</b>
     * 原版挖掘速度 {@code Player#getDigSpeed} 读的是
     * <pre>
     *   float f = this.inventory.getDestroySpeed(state);   // 工具基础速度（材质决定）
     *   if (f &gt; 1.0F) f += getAttributeValue(Attributes.MINING_EFFICIENCY);   // ← 效率附魔在这
     * </pre>
     * 而 {@code MINING_EFFICIENCY} 属性由 {@code LivingEntity#collectEquipmentChanges()}
     * 在 <b>{@code LivingEntity#tick()} 里</b>刷新。
     * {@link FakePlayer#tick()} 是<b>空实现</b>，于是属性永远不更新。</p>
     *
     * <p><b>症状</b>：效率附魔完全不起作用；同时材质门槛、精准采集却正常
     * （它们直接读 {@code getMainHandItem()} / ItemStack 组件，不走属性表）—— 这个"一半好一半坏"
     * 的现象正是本 bug 的特征。</p>
     *
     * <p>原版方法 {@code detectEquipmentUpdates()} 是 {@code private}，故此处复刻它内部的
     * 摘除 / 装配两段逻辑（同 {@code LivingEntity} 源码，仅限主手槽）。</p>
     */
    public void equipTool(ItemStack stack) {
        // ⭐ 手上的物品【每次都要更新】—— 攻击 / 挖掘会扣耐久，回放读的就是它。
        //
        // 🔴 属性修饰符【每次都摘旧装新】（2026-09-30 修复"特定武器永久白板化"）：
        //    曾用 changed 门控（同物品不重算），但属性可能被【镜像清理】误删 ——
        //    主人换手发生在容器点击（tick 之间处理），而主人的属性刷新在其后的实体 tick，
        //    竞态窗口内主人地图里的旧武器 bd/bs 会被镜像当成"非主手来源"记录；
        //    下一轮镜像清理按 id 删掉它们（与武器自身修饰符同 id！），
        //    changed=false ⇒ equipTool 不补回 ⇒ 武器永久只剩白板（伤害 1 / 攻速 4.0，
        //    表现 = 该武器 0.25s 一刀只打 1 点 + 常驻拉仇恨）。
        //    摘旧装新是幂等的（按 id 先删后加），每 tick 做一次代价可忽略 ⇒ 让它自愈。
        //
        //   【位置类附魔效果】仍只在【换了另一把】时触发：
        //   每 tick 重复触发会叠加（原版只在装备变化时触发），故保留 changed 门控。
        boolean changed = !ItemStack.isSameItem(stack, this.lastEquipped);

        removeEnchantmentModifiers(this.lastEquipped);
        this.setItemInHand(InteractionHand.MAIN_HAND, stack);
        addEnchantmentModifiers(stack);
        if (changed) {
            runLocationChangedEffects(stack);
        }
        this.lastEquipped = stack;
    }

    // ── 主人属性镜像（饰品增益 —— 2026-09-24 用户需求）─────────────────────────

    /**
     * 要镜像的属性白名单 —— 覆盖攻击侧与挖掘侧（饰品模组的主流加成点）。
     */
    private static final List<Holder<Attribute>> MIRRORED_ATTRIBUTES = List.of(
        Attributes.ATTACK_DAMAGE,       // 攻击力
        Attributes.ATTACK_SPEED,        // 攻速（冷却按它换算 ⇒ 攻速饰品直接变快出手）
        Attributes.ATTACK_KNOCKBACK,    // 击退
        Attributes.MINING_EFFICIENCY,   // 挖掘效率（效率附魔同款属性）
        Attributes.BLOCK_BREAK_SPEED    // 挖掘速度
    );

    /** 上一次镜像进来的修饰符 ID —— 每次镜像前先清掉（主人穿脱饰品 ⇒ 必须全量重算）。 */
    private final Set<ResourceLocation> mirroredModifierIds = new HashSet<>();

    /**
     * 把【主人身上】的属性修饰符镜像到本 FakePlayer —— 饰品模组的增益由此生效。
     *
     * <p><b>问题</b>：饰品模组（Curios 等）的加成是往<b>玩家实体</b>的 {@code AttributeMap}
     * 上挂 {@code AttributeModifier}，而 FakePlayer 是<b>另一个实体</b>、另有一本账
     * ⇒ 活工具 / 活武器借它出手时<b>吃不到主人的任何属性增益</b>。</p>
     *
     * <p><b>解法</b>：借力不换人 —— 把主人身上白名单属性的修饰符<b>复制</b>过来（transient）：
     * 「借你的身体，不借你手里的家伙」：</p>
     * <ul>
     *   <li>⭐ <b>排除主人主手物品</b>贡献的修饰符 —— 否则主人手里那把的锋利会和活武器自己的
     *       （{@link #equipTool} 装的）<b>叠加成双倍附魔</b>。主手贡献可精确枚举
     *       （{@code stack.forEachModifier}，物品自带 + 附魔一并覆盖）；</li>
     *   <li>⭐ <b>base 值不动</b> —— 攻击力 / 攻速以活武器自身为准，镜像的只是"额外的"那部分；</li>
     *   <li>⭐ <b>重算式</b>（同 {@link #equipTool}）：每次出手 / 精算前先清后装，
     *       主人穿脱饰品下一刀自动跟上，实例跨工具共享也不残留。</li>
     * </ul>
     *
     * <p>⚠️ <b>边界</b>：只覆盖<b>属性型</b>增益。事件型（监听伤害事件、按实体实例/饰品槽判定的）
     * 吃不到 —— 那类认的是"玩家对象本身"，镜像救不了。按 <b>UUID</b> 判定的事件型本来就生效
     * （FakePlayer 的 UUID = 主人）。</p>
     *
     * @return 找到的<b>在线主人</b>（同维度）；离线 / 跨维度 / 无主人时 {@code null} ——
     *         调用方可顺手用它刷新主人名字的显示缓存（见 {@code LivingToolOwnerName#refresh}）
     */
    @Nullable
    public Player syncOwnerAttributes() {
        // ① 清掉上一轮镜像的修饰符
        for (ResourceLocation id : this.mirroredModifierIds) {
            for (Holder<Attribute> attribute : MIRRORED_ATTRIBUTES) {
                AttributeInstance instance = this.getAttributes().getInstance(attribute);
                if (instance != null) {
                    instance.removeModifier(id);
                }
            }
        }
        this.mirroredModifierIds.clear();

        // ② 找到主人 —— 离线 / 无主人（FALLBACK_UUID）⇒ 无身可借，跳过
        if (this.owner == null || !(this.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        Player owner = serverLevel.getPlayerByUUID(this.owner);
        if (owner == null) {
            return null;
        }

        // ③ 排除主人主手物品贡献的修饰符（物品自带 + 附魔，一个 API 全枚举）
        // ⭐ 再排除【假玩家当前手持物品】自己的修饰符 id（2026-09-30 修复，与 equipTool
        //    的注释同一案例）：addOrUpdate 按 id 替换 —— 主人换手竞态窗口内，主人地图里
        //    的旧武器 bd/bs 与假玩家武器自身的修饰符【同 id】，会被镜像覆盖并记录，
        //    下一轮清理又删掉 ⇒ 属性反复丢失。同名 id 一律不镜像（损失可忽略：
        //    "与武器同 id 的非主手来源"现实中不存在）。
        Set<ResourceLocation> mainHandIds = new HashSet<>();
        owner.getMainHandItem().forEachModifier(EquipmentSlot.MAINHAND,
            (attribute, modifier) -> mainHandIds.add(modifier.id()));
        if (this.lastEquipped != null) {
            this.lastEquipped.forEachModifier(EquipmentSlot.MAINHAND,
                (attribute, modifier) -> mainHandIds.add(modifier.id()));
        }

        // ④ 逐属性复制（addOrUpdate 防同 ID 冲突抛异常）
        for (Holder<Attribute> attribute : MIRRORED_ATTRIBUTES) {
            AttributeInstance from = owner.getAttributes().getInstance(attribute);
            AttributeInstance to = this.getAttributes().getInstance(attribute);
            if (from == null || to == null) {
                continue;
            }
            for (AttributeModifier modifier : from.getModifiers()) {
                if (mainHandIds.contains(modifier.id())) {
                    continue;
                }
                to.addOrUpdateTransientModifier(modifier);
                this.mirroredModifierIds.add(modifier.id());
            }
        }
        return owner;
    }

    /** 摘掉上一把工具留下的修饰符（FakePlayer 实例是跨工具共享的，不摘会叠加）。 */
    private void removeEnchantmentModifiers(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            AttributeInstance instance = this.getAttributes().getInstance(attribute);
            if (instance != null) {
                instance.removeModifier(modifier.id());
            }
        });
        // ⭐ 位置类附魔效果（EnchantmentLocationBasedEffect）—— 原版在同一处调用，我们此前漏了。
        //    ⚠️ 原版内置几乎不用它，主要为模组服务；但既然有官方入口就补上。
        EnchantmentHelper.stopLocationBasedEffects(stack, this, EquipmentSlot.MAINHAND);
    }

    /**
     * 触发<b>位置类附魔效果</b>（{@code EnchantmentLocationBasedEffect}）。
     *
     * <p>⭐ 官方入口：{@code EnchantmentHelper.runLocationChangedEffects(ServerLevel, ItemStack, LivingEntity, EquipmentSlot)}，
     * 原版在 {@code LivingEntity#handleEquipmentChanges} 里调用 —— 我们此前只复刻了属性部分，漏了这一句。</p>
     *
     * <p>⚠️ 必须由 {@code equipTool} 的 {@code changed} 分支调用 ⇒ <b>不能每 tick 触发</b>。</p>
     */
    private void runLocationChangedEffects(ItemStack stack) {
        if (stack.isEmpty() || !(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        EnchantmentHelper.runLocationChangedEffects(serverLevel, stack, this, EquipmentSlot.MAINHAND);
    }

    /** 加上当前工具附魔提供的修饰符（效率 → {@code MINING_EFFICIENCY} 等）。 */
    private void addEnchantmentModifiers(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            AttributeInstance instance = this.getAttributes().getInstance(attribute);
            if (instance != null) {
                instance.removeModifier(modifier.id());
                instance.addTransientModifier(modifier);
            }
        });
    }

    /**
     * 权限判定走这个，必须返回主人 UUID。
     *
     * <p>注意 {@code super.getUUID()}（实体 UUID）与 GameProfile 的 id 不是一回事，
     * 所以这里必须显式覆写 —— Create 的 {@code DeployerFakePlayer} 同样如此。</p>
     */
    @Override
    public UUID getUUID() {
        return owner == null ? super.getUUID() : owner;
    }

    @Override
    public String getScoreboardName() {
        return "LivingTool";
    }

    // ── 受控 tick（2026-09-30 重构核心）─────────────────────────────────────
    //
    // NeoForge 的 FakePlayer 刻意把 tick() 掏空 —— 它的设计前提是"假玩家不该活着"。
    // 但活武器的"代持"语义恰恰需要一个【活着】的持械者：攻击强度计时器、物品冷却、
    // 逐槽 inventoryTick、药效衰减、装备属性刷新、蓄力推进（updatingUsingItem）
    // 全部住在真链里。此前这六样各打了一个补丁（覆写读数 / 代持 tick / 摘旧装新……），
    // 现在让假玩家【真的活一次】：由驱动器（{@link #driveWielderTick}）按 game tick 去重调用。
    //
    // ⚠️ 不 addFreshEntity 进世界 —— 不渲染、无碰撞，只在回放时被驱动；物理用
    //    noGravity + 每 tick 归零 deltaMovement 钉住（见构造器与 {@link #driveWielderTick}）。
    // ⚠️ 事件面：真链会触发 PlayerTickEvent / LivingTickEvent 等 —— 本项目内部处理器
    //    均有 isFakePlayer 或判据防护；第三方 mod 对假玩家的 tick 可见（接受并观察）。

    /**
     * 驱动器：每 game tick 至多推进一次真链（多宿主 / 辅助路径共享同一假玩家，按 game time 去重）。
     *
     * <p>🔴 <b>必须调 {@code doTick()} 而不是 {@code tick()}</b>（2026-09-30 实测踩坑）：
     * {@code ServerPlayer.tick()} 只做 ServerPlayer 侧簿记（gameMode/broadcast/criteria），
     * <b>不含 {@code super.tick()}</b> —— 真链（{@code Player.tick → LivingEntity.tick}：
     * 攻击强度计时器 / inventoryTick / 冷却 / 药效衰减 / 装备属性刷新 / 蓄力推进）
     * 在 {@code ServerPlayer.doTick()} 里，由连接器的 {@code tick() → player.doTick()} 驱动；
     * 而 FakePlayer 把连接器 tick 也掏空了 ⇒ 调 {@code tick()} 等于只跑簿记，
     * 计时器永远 0 ⇒ 攻击冷却门永远 COOLING（表现 = 武器两模式都不攻击）。</p>
     */
    public void driveWielderTick() {
        long now = this.level().getGameTime();
        if (this.lastDrivenTick == now) {
            return;
        }
        this.lastDrivenTick = now;
        this.setDeltaMovement(Vec3.ZERO);   // 钉位：travel 不得位移（noGravity 已关重力）
        this.fallDistance = 0.0F;
        this.doTick();   // 真链入口：super.tick() = Player.tick → LivingEntity.tick（内部 try-catch 兜底）
    }

    private long lastDrivenTick = Long.MIN_VALUE;

    // ── 攻击缩放注入（per-weapon）────────────────────────────────────────
    // ⚠️ 为什么覆写 getAttackStrengthScale（2026-09-30 二次修正）：
    //    原生 attackStrengthTicker 长在【假玩家】身上 —— 共享假玩家服务 N 把武器时，
    //    它是【全体武器共用一个攻击时钟】：被动模式玩家一刀，第一把命中后原生重置，
    //    第二把在同 tick 读到 scale≈0 ⇒ COOLING ⇒ 只有最靠前的武器出手。
    //    而语义要求【每把武器一份节奏】（按各自攻速独立出手）。
    //    ⇒ 缩放由回放侧按武器自身的上次攻击记录算好，经 setAttackStrengthScale 注入；
    //      原生 ticker 仍在走（无害，覆写不读它）。
    //    受控 tick 驱动（driveWielderTick）继续承担 inventoryTick / 冷却 / 药效衰减 /
    //    装备属性刷新 —— 与本注入互不冲突。

    /** 攻击缩放比例（0~1）—— 由回放侧按【该武器自己】的上次攻击记录算好后注入。 */
    private float attackStrengthScale = 1.0F;

    @Override
    public float getAttackStrengthScale(float adjustTicks) {
        return this.attackStrengthScale;   // 傀儡的缩放由武器决定，不由共享时钟决定
    }

    /** 由回放侧注入当前武器的攻击缩放（每次 attack 前调用，调用后立即 attack，无交叉）。 */
    public void setAttackStrengthScale(float scale) {
        this.attackStrengthScale = net.minecraft.util.Mth.clamp(scale, 0.0F, 1.0F);
    }

    /**
     * 攻击冷却<b>总时长</b>（tick）—— 由物品的攻击速度属性换算，原版同源。
     * {@code ATTACK_SPEED} 异常（0 / 负数）时兜底 1，防除法失去意义。
     */
    public float getAttackCooldownTicks() {
        float delay = this.getCurrentItemAttackStrengthDelay();
        if (!Float.isFinite(delay) || delay < 1.0F) {
            return 1.0F;
        }
        return delay;
    }

    /**
     * 把主人 UUID 暴露给 {@code GameProfile#getId()} 的载体。
     *
     * <p>无主人时使用 {@link #FALLBACK_UUID}，保证 {@code getId()} 永不为 null
     * （{@code GameProfile} 语义要求）。</p>
     */
    private static class OwnerGameProfile extends GameProfile {

        @Nullable
        private final UUID owner;

        OwnerGameProfile(@Nullable UUID owner) {
            super(owner == null ? FALLBACK_UUID : owner, "LivingTool");
            this.owner = owner;
        }

        @Override
        public UUID getId() {
            return owner == null ? super.getId() : owner;
        }

        /**
         * 有主人时尽量显示其用户名，便于服主在日志 / 保护插件里辨认。
         */
        @Override
        public String getName() {
            if (owner == null) {
                return super.getName();
            }
            String lastKnown = net.neoforged.neoforge.common.UsernameCache.getLastKnownUsername(owner);
            return lastKnown == null ? super.getName() : lastKnown;
        }

        // GameProfile 的 equals/hashCode 基于 id+name，getId/getName 被覆写后必须同步覆写，
        // 否则缓存 / 集合行为会不一致（Create 同样处理）。
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof GameProfile other)) {
                return false;
            }
            return Objects.equals(getId(), other.getId()) && Objects.equals(getName(), other.getName());
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(getId());
            result = 31 * result + Objects.hashCode(getName());
            return result;
        }
    }
}
