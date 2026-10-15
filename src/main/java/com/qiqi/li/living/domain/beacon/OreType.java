package com.qiqi.li.living.domain.beacon;

import java.util.Locale;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 矿石锭 → 效果映射（设计稿 §2.5「矿石锭 → 效果映射」）。
 *
 * <p>一一对应原版 6 效果池里除再生外的 5 个（再生由满级白送接管）：
 * 铁=速度 / 金=急迫（index 0，L1 解锁）；下界合金=抗性提升 / 绿宝石=跳跃提升
 * （index 1，L2 解锁）；钻石=力量（index 2，L3 解锁）。</p>
 *
 * <p>⚠️ 绿宝石映射到**跳跃提升**（而非再生）——再生被满级白送接管后，
 * 绿宝石槽空出来，补到池里唯一未分配的 JUMP（用户 2026-10-15 定）。</p>
 */
public enum OreType {

    /** 铁锭 → 速度（档位 1）。 */
    IRON("iron", Items.IRON_INGOT, 1, MobEffects.MOVEMENT_SPEED),

    /** 金锭 → 急迫（档位 1）。 */
    GOLD("gold", Items.GOLD_INGOT, 1, MobEffects.DIG_SPEED),

    /** 下界合金锭 → 抗性提升（档位 2）。 */
    NETHERITE("netherite", Items.NETHERITE_INGOT, 2, MobEffects.DAMAGE_RESISTANCE),

    /** 绿宝石 → 跳跃提升（档位 2）。 */
    EMERALD("emerald", Items.EMERALD, 2, MobEffects.JUMP),

    /** 钻石 → 力量（档位 3）。 */
    DIAMOND("diamond", Items.DIAMOND, 3, MobEffects.DAMAGE_BOOST);

    /** 效果显示名（用于 tooltip，取原版效果的本地化键）。 */
    public final Holder<MobEffect> effect;

    /** 解锁档位（信标等级 ≥ 此值才可喂入）。 */
    public final int unlockTier;

    /** 对应的矿石锭物品。 */
    public final Item ingot;

    OreType(String id, Item ingot, int unlockTier, Holder<MobEffect> effect) {
        this.ingot = ingot;
        this.unlockTier = unlockTier;
        this.effect = effect;
    }

    /** 按矿石锭反查；非矿石锭返回 {@code null}。 */
    public static OreType fromIngot(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return fromIngot(stack.getItem());
    }

    public static OreType fromIngot(Item item) {
        for (OreType ore : values()) {
            if (ore.ingot == item) {
                return ore;
            }
        }
        return null;
    }

    /** 效果名（原版本地化键，如 {@code effect.minecraft.speed}）。 */
    public String effectKey() {
        return effect.value().getDescriptionId();
    }

    /** 枚举名（小写，用于 codec / lang 键）。 */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
