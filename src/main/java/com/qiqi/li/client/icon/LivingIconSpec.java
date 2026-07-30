package com.qiqi.li.client.icon;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.function.Predicate;

/**
 * 活物品图标的声明式配置。
 *
 * <p>使用建造者模式创建，描述一个活物品的图标需求：
 * <ul>
 *   <li>哪个原版物品需要图标替换</li>
 *   <li>有哪些变体模型（如空闲/熔炼中）</li>
 *   <li>如何根据 ItemStack 判断当前应该使用哪个变体</li>
 *   <li>是否需要 ItemDecorator 叠加层</li>
 * </ul>
 *
 * <p>示例 - 活熔炉（两种状态）：
 * <pre>
 * LivingIconSpec.builder(Items.FURNACE)
 *     .addVariant("idle", "item/furnace_idle", stack -> !isBurning(stack))
 *     .addVariant("active", "item/furnace_active", stack -> isBurning(stack))
 *     .build();
 * </pre>
 *
 * <p>示例 - 活漏斗（一种基础图标 + 箭头叠加）：
 * <pre>
 * LivingIconSpec.builder(Items.HOPPER)
 *     .addVariant("base", "item/hopper_living", stack -> true)
 *     .decorator(new LivingHopperDecorator())
 *     .build();
 * </pre>
 */
public class LivingIconSpec {

    /** 需要图标替换的原版物品 */
    private final Item item;

    /** 变体列表，按注册顺序排列，resolve 时按顺序匹配第一个 predicate 为 true 的变体 */
    private final List<Variant> variants;

    /** 可选的 ItemDecorator，为 null 则不注册叠加层 */
    private final net.neoforged.neoforge.client.IItemDecorator decorator;

    private final boolean rotating;

    private LivingIconSpec(Item item, List<Variant> variants,
                           net.neoforged.neoforge.client.IItemDecorator decorator,
                           boolean rotating) {
        this.item = item;
        this.variants = variants;
        this.decorator = decorator;
        this.rotating = rotating;
    }

    public Item getItem() { return item; }
    public List<Variant> getVariants() { return variants; }
    public net.neoforged.neoforge.client.IItemDecorator getDecorator() { return decorator; }
    public boolean isRotating() { return rotating; }

    /**
     * 判断给定的 ItemStack 是否为活物品。
     * 默认使用 LivingItemManager.isLivingItem()，可被覆盖。
     */
    public boolean isLivingItem(net.minecraft.world.item.ItemStack stack) {
        return com.qiqi.li.living.LivingItemManager.isLivingItem(stack);
    }

    /**
     * 创建建造者。
     *
     * @param item 需要图标替换的原版物品
     * @return 建造者实例
     */
    public static Builder builder(Item item) {
        return new Builder(item);
    }

    /**
     * 图标变体，描述一种图标状态。
     *
     * <p>每个变体包含：
     * <ul>
     *   <li>name - 变体名称，用于生成模型资源路径</li>
     *   <li>modelPath - 模型 JSON 的资源路径（如 "item/furnace_idle"）</li>
     *   <li>predicate - 判断当前 ItemStack 是否应使用此变体</li>
     * </ul>
     */
    public static class Variant {
        private final String name;
        private final String modelPath;
        private final Predicate<net.minecraft.world.item.ItemStack> predicate;

        public Variant(String name, String modelPath, Predicate<net.minecraft.world.item.ItemStack> predicate) {
            this.name = name;
            this.modelPath = modelPath;
            this.predicate = predicate;
        }

        public String getName() { return name; }
        public String getModelPath() { return modelPath; }
        public Predicate<net.minecraft.world.item.ItemStack> getPredicate() { return predicate; }
    }

    /**
     * LivingIconSpec 的建造者。
     */
    public static class Builder {
        private final Item item;
        private final java.util.ArrayList<Variant> variants = new java.util.ArrayList<>();
        private net.neoforged.neoforge.client.IItemDecorator decorator;
        private boolean rotating;

        private Builder(Item item) {
            this.item = item;
        }

        /**
         * 添加一个图标变体。
         *
         * <p>变体按添加顺序匹配，resolve 时使用第一个 predicate 返回 true 的变体。
         * 因此通常把最常用的变体放在前面，或者把默认变体放在最后（predicate 为 stack -> true）。
         *
         * @param name      变体名称（如 "idle"、"active"）
         * @param modelPath 模型 JSON 的资源路径（如 "item/furnace_idle"），
         *                  对应 assets/living_item/models/item/furnace_idle.json
         * @param predicate 判断当前 ItemStack 是否应使用此变体
         * @return 此建造者
         */
        public Builder addVariant(String name, String modelPath,
                                  Predicate<net.minecraft.world.item.ItemStack> predicate) {
            variants.add(new Variant(name, modelPath, predicate));
            return this;
        }

        /**
         * 设置 ItemDecorator 叠加层。
         *
         * @param decorator 叠加层实现，为 null 则不注册
         * @return 此建造者
         */
        public Builder decorator(net.neoforged.neoforge.client.IItemDecorator decorator) {
            this.decorator = decorator;
            return this;
        }

        public Builder rotating() {
            this.rotating = true;
            return this;
        }

        public LivingIconSpec build() {
            if (variants.isEmpty()) {
                throw new IllegalStateException("至少需要添加一个变体");
            }
            return new LivingIconSpec(item, List.copyOf(variants), decorator, rotating);
        }
    }
}