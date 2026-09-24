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

    /**
     * 攻击冷却比例（0~1）—— 由 {@code LivingToolReplay#replayAttack} 手动推进。
     *
     * <p>🔴 <b>为什么必须自己维护</b>：原版 {@code Player#getAttackStrengthScale} 读的是
     * <b>private</b> 的 {@code attackStrengthTicker}，它由 {@code Player#tick()} 自增，
     * 而 {@link FakePlayer#tick()} 是<b>空实现</b>（{@code L27} 一脉）⇒ 该值<b>永远是 0</b>。</p>
     *
     * <p>而 {@code Player#attack} 里伤害是这样算的：</p>
     * <pre>
     *   f  *= 0.2F + f² * 0.8F;     // f = 冷却比例
     *   f1 *= f;                     // 附魔伤害同样打折
     *   flag4 = f &gt; 0.9F;            // 击退 / 横扫 / 暴击也要满冷却
     * </pre>
     *
     * <p>⇒ 不推进的话，活武器<b>永远只有 20% 伤害</b>，且永远触发不了横扫与暴击。<br>
     * 这里覆写读数、由外部按「物品攻击速度属性」推进（用户定的 S1-a），
     * 从而<b>不必碰 private 字段</b>（反射在生产环境会因混淆失效，AT 又要新增配置）。</p>
     */
    private float attackStrengthScale = 1.0F;

    public LivingToolFakePlayer(ServerLevel level, @Nullable UUID owner) {
        super(level, new OwnerGameProfile(owner));
        this.owner = owner;
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
        //   但【属性与位置类附魔效果】只在【换了另一把】时才重算：
        //   本方法每 tick 都被调用，若无条件触发 ⇒ 位置效果会被每 tick 重复触发（原版只在装备变化时触发）。
        boolean changed = !ItemStack.isSameItem(stack, this.lastEquipped);

        if (changed) {
            removeEnchantmentModifiers(this.lastEquipped);
        }
        this.setItemInHand(InteractionHand.MAIN_HAND, stack);
        if (changed) {
            addEnchantmentModifiers(stack);
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
     */
    public void syncOwnerAttributes() {
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
            return;
        }
        Player owner = serverLevel.getPlayerByUUID(this.owner);
        if (owner == null) {
            return;
        }

        // ③ 排除主人主手物品贡献的修饰符（物品自带 + 附魔，一个 API 全枚举）
        Set<ResourceLocation> mainHandIds = new HashSet<>();
        owner.getMainHandItem().forEachModifier(EquipmentSlot.MAINHAND,
            (attribute, modifier) -> mainHandIds.add(modifier.id()));

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

    /**
     * 覆写攻击冷却读数 —— 返回外部推进出来的值（见 {@link #attackStrengthScale} 的说明）。
     *
     * <p>⚠️ 刻意<b>忽略 {@code adjustTicks}</b>：那个参数是给渲染插值用的，
     * 而这里的冷却是按「世界轴 tick 差 / 物品冷却总时长」算好的绝对值。</p>
     */
    @Override
    public float getAttackStrengthScale(float adjustTicks) {
        return this.attackStrengthScale;
    }

    /** 由回放侧设置当前冷却进度。 */
    public void setAttackStrengthScale(float scale) {
        this.attackStrengthScale = net.minecraft.util.Mth.clamp(scale, 0.0F, 1.0F);
    }

    /**
     * 攻击冷却<b>总时长</b>（tick）—— 由物品的攻击速度属性换算，原版同源。
     *
     * <p>取 {@code max(..., 1)} 兜底：攻击速度为 0 的物品会让除法失去意义。</p>
     */
    public float getAttackCooldownTicks() {
        float delay = this.getCurrentItemAttackStrengthDelay();
        // ⚠️ ATTACK_SPEED 为 0（被模组 / 数据包改掉）时 delay = Infinity
        //    ⇒ 冷却永远算不满 ⇒ 【永远打不出来】，与上面同类的静默失效 ⇒ 必须兜底。
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
