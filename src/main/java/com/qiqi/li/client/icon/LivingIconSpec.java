package com.qiqi.li.client.icon;

import com.qiqi.li.LivingItem;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.model.Pos2D;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 活物品图标的声明式配置。
 *
 * <p>使用建造者模式创建，描述一个活物品的图标需求：
 * <ul>
 *   <li>哪个原版物品需要图标替换</li>
 *   <li>有哪些变体模型（如空闲/熔炼中）</li>
 *   <li>如何根据 ItemStack 判断当前应该使用哪个变体</li>
 *   <li>需要哪些 ItemDecorator 叠加层（可多个，按顺序渲染）</li>
 * </ul>
 *
 * <p>示例 - 活熔炉（两种状态）：
 * <pre>
 * LivingIconSpec.builder(Items.FURNACE)
 *     .addLocalVariant("idle", "item/furnace_idle", stack -> !isBurning(stack))
 *     .addLocalVariant("active", "item/furnace_active", stack -> isBurning(stack))
 *     .build();
 * </pre>
 *
 * <p>示例 - 活漏斗（一种基础图标 + 箭头叠加）：
 * <pre>
 * LivingIconSpec.builder(Items.HOPPER)
 *     .addLocalVariant("base", "item/hopper_living", stack -> true)
 *     .decorator(new LivingHopperDecorator())
 *     .build();
 * </pre>
 */
public class LivingIconSpec {

    /** 需要图标替换的原版物品 */
    private final Item item;

    /** 变体列表，按注册顺序排列，resolve 时按顺序匹配第一个 predicate 为 true 的变体 */
    private final List<Variant> variants;

    /**
     * ItemDecorator 叠加层列表（按注册顺序依次渲染）。
     *
     * <p>可挂多个（如活酿造台 = 白幕 + 燃料条）。NeoForge 原生支持一个物品多个装饰器
     * （{@code RegisterItemDecorationsEvent} 内部就是 {@code Map<Item, List<IItemDecorator>>}）。</p>
     */
    private final List<net.neoforged.neoforge.client.IItemDecorator> decorators;

    private final boolean rotating;
    private final Function<ItemStack, Pos2D> directionGetter;
    private final float guiScale;

    private LivingIconSpec(Item item, List<Variant> variants,
                           List<net.neoforged.neoforge.client.IItemDecorator> decorators,
                           boolean rotating, Function<ItemStack, Pos2D> directionGetter, float guiScale) {
        this.item = item;
        this.variants = variants;
        this.decorators = decorators;
        this.rotating = rotating;
        this.directionGetter = directionGetter;
        this.guiScale = guiScale;
    }

    public Item getItem() { return item; }
    public List<Variant> getVariants() { return variants; }
    public List<net.neoforged.neoforge.client.IItemDecorator> getDecorators() { return decorators; }
    public boolean isRotating() { return rotating; }
    public boolean isDirectional() { return directionGetter != null; }
    /** 方向数据读取器；未启用方向旋转时为 null，只对活物品调用。 */
    public Function<ItemStack, Pos2D> getDirectionGetter() { return directionGetter; }
    public float getGuiScale() { return guiScale; }

    /**
     * 判断给定的 ItemStack 是否为活物品。
     * 默认使用 LivingItemManager.isLivingItem()，可被覆盖。
     */
    public boolean isLivingItem(net.minecraft.world.item.ItemStack stack) {
        return LivingItemManager.isLivingItem(stack);
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

    /** 图标变体：按注册顺序匹配，模型 ID 保留完整命名空间。 */
    public static class Variant {
        private final String name;
        private final ResourceLocation modelId;
        private final Predicate<ItemStack> predicate;

        public Variant(String name, ResourceLocation modelId, Predicate<ItemStack> predicate) {
            this.name = name;
            this.modelId = Objects.requireNonNull(modelId, "modelId");
            this.predicate = predicate;
        }

        public String getName() { return name; }
        public ResourceLocation getModelId() { return modelId; }
        public Predicate<ItemStack> getPredicate() { return predicate; }
    }

    /**
     * LivingIconSpec 的建造者。
     */
    public static class Builder {
        private final Item item;
        private final java.util.ArrayList<Variant> variants = new java.util.ArrayList<>();
        private final java.util.ArrayList<net.neoforged.neoforge.client.IItemDecorator> decorators =
            new java.util.ArrayList<>();
        private boolean rotating;
        private Function<ItemStack, Pos2D> directionGetter;
        private float guiScale = 1.0f;

        private Builder(Item item) {
            this.item = item;
        }

        /** 添加 living_item 命名空间的本地模型路径，不接受带命名空间的字符串。 */
        public Builder addLocalVariant(String name, String localPath, Predicate<ItemStack> predicate) {
            if (localPath.contains(":")) {
                throw new IllegalArgumentException("Local model path must not include a namespace: " + localPath);
            }
            return addVariant(name, ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, localPath), predicate);
        }

        /** 添加完整模型 ID；变体按添加顺序匹配第一个 predicate 为 true 的项。 */
        public Builder addVariant(String name, ResourceLocation modelId, Predicate<ItemStack> predicate) {
            variants.add(new Variant(name, modelId, predicate));
            return this;
        }

        /**
         * 追加一个 ItemDecorator 叠加层（可多次调用，按调用顺序依次渲染）。
         *
         * <p>多个装饰器互不影响（NeoForge 逐个调用，各自返回是否改过渲染状态）。
         * 例：活酿造台 = 白幕 + 燃料条。</p>
         *
         * @param decorator 叠加层实现，为 null 则忽略
         * @return 此建造者
         */
        public Builder decorator(net.neoforged.neoforge.client.IItemDecorator decorator) {
            if (decorator != null) {
                this.decorators.add(decorator);
            }
            return this;
        }

        public Builder rotating() {
            this.rotating = true;
            return this;
        }

        /** 启用 GUI 方向旋转；读取器只对活物品调用，不能与 rotating 同时启用。 */
        public Builder directional(Function<ItemStack, Pos2D> directionGetter) {
            this.directionGetter = Objects.requireNonNull(directionGetter, "directionGetter");
            return this;
        }

        /**
         * 设置 GUI 中的缩放比例。
         *
         * @param scale 缩放因子，默认 1.0，大于 1 放大，小于 1 缩小
         * @return 此建造者
         */
        public Builder guiScale(float scale) {
            this.guiScale = scale;
            return this;
        }

        public LivingIconSpec build() {
            if (variants.isEmpty()) {
                throw new IllegalStateException("至少需要添加一个变体");
            }
            if (rotating && directionGetter != null) {
                throw new IllegalStateException("rotating 与 directional 不能同时启用");
            }
            return new LivingIconSpec(item, List.copyOf(variants), List.copyOf(decorators),
                rotating, directionGetter, guiScale);
        }
    }
}