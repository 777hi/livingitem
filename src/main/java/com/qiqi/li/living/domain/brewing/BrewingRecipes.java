package com.qiqi.li.living.domain.brewing;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.level.Level;

/**
 * {@link PotionBrewing} 的薄适配层 —— 把原版那几个「形参名与实际语义不符」的方法包成
 * <b>自解释的签名</b>，并统一「从世界取酿造表」的入口。
 *
 * <h3>⚠️ 为什么需要这一层（原版自己都注释了）</h3>
 * <p>{@code PotionBrewing} 的形参名是<b>误导的</b>：{@code hasMix(reagent, potionItem)} 里
 * {@code reagent} 实际是<b>瓶子</b>、{@code potionItem} 实际是<b>材料</b>；
 * {@code mix(potion, potionItem)} 里 {@code potion} 实际是<b>材料</b>、
 * {@code potionItem} 实际是<b>瓶子</b>。原版在 {@code mix} 内注释了
 * {@code "Parameters are swapped compared to what vanilla passes!"}。</p>
 *
 * <p>⇒ 本层<b>一律以调用点为准</b>固定语义：{@link #hasMix} 是「瓶子, 材料」、
 * {@link #brew} 是「材料, 瓶子」，把坑收在这一处（见设计稿 §1.4 / §4.6）。</p>
 *
 * <h3>配方来源 = 零兼容代码</h3>
 * <p>{@code level.potionBrewing()} 在启动时已聚合<b>原版三张表 + 全部模组配方</b>
 * （{@code RegisterBrewingRecipesEvent}）⇒ 只需这一个入口即天然兼容所有模组（设计稿 §1.3）。</p>
 */
public final class BrewingRecipes {

    private BrewingRecipes() {}

    /** 取该世界的酿造表（客户端 / 服务端都可用）。 */
    public static PotionBrewing of(Level level) {
        return level.potionBrewing();
    }

    /** 该物品能否作为「酿造材料」（地狱疣 / 萤石粉 / 红石 / 火药…）。 */
    public static boolean isIngredient(PotionBrewing brewing, ItemStack ingredient) {
        return brewing.isIngredient(ingredient);
    }

    /**
     * 该「瓶子 + 材料」能否配出结果。
     *
     * <p>⚠️ 原版签名 {@code hasMix(reagent, potionItem)} 的形参名会误导 ——
     * 实际第 1 参是<b>瓶子</b>、第 2 参是<b>材料</b>
     * （以 {@code BrewingStandBlockEntity.isBrewable} 的调用点为准）。</p>
     */
    public static boolean hasMix(PotionBrewing brewing, ItemStack bottle, ItemStack ingredient) {
        return brewing.hasMix(bottle, ingredient);
    }

    /**
     * 转化一瓶：返回<b>新的瓶子内容</b>。
     *
     * <p>⚠️ 原版签名 {@code mix(potion, potionItem)} 的形参名会误导 ——
     * 实际第 1 参是<b>材料</b>、第 2 参是<b>瓶子</b>（以 {@code doBrew} 的调用点为准）。</p>
     *
     * <p><b>两条安全保证</b>（读源码核实，设计稿 §4.6）：</p>
     * <ul>
     *   <li>瓶子为<b>空</b> ⇒ 原样返回空（{@code doBrew} 不会往空槽塞东西）；</li>
     *   <li>瓶子与材料<b>无配方</b>（且无 {@code POTION_CONTENTS}）⇒ <b>原样返回</b>
     *       ⇒ 石头等无关物品<b>不会被改动</b>。</li>
     * </ul>
     */
    public static ItemStack brew(PotionBrewing brewing, ItemStack ingredient, ItemStack bottle) {
        return brewing.mix(ingredient, bottle);
    }
}
