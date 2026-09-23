package com.qiqi.li.living.domain.tools;

import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.ItemStack;
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
        removeEnchantmentModifiers(this.lastEquipped);
        this.setItemInHand(InteractionHand.MAIN_HAND, stack);
        addEnchantmentModifiers(stack);
        this.lastEquipped = stack;
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
        return Math.max(this.getCurrentItemAttackStrengthDelay(), 1.0F);
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
