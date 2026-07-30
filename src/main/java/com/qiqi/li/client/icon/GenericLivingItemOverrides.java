package com.qiqi.li.client.icon;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 通用的模型覆盖解析器，根据 LivingIconSpec 配置和物品 NBT 数据决定返回哪个模型。
 *
 * <p>替代之前每种活物品一个 LivingXxxItemOverrides 的模式。
 * 所有活物品共用同一个类，通过 LivingIconSpec 中的 Variant.predicate 判断当前状态。
 *
 * <p>解析流程：
 * <ol>
 *   <li>检查物品是否为活物品（通过 LivingIconSpec.isLivingItem()）</li>
 *   <li>不是活物品 → 返回原版模型</li>
 *   <li>是活物品 → 遍历变体列表，使用第一个 predicate 为 true 的变体</li>
 *   <li>将匹配的变体模型包装为 GenericContextAwareModel 返回</li>
 * </ol>
 *
 * <p>ContextAwareModel 实例按变体名缓存（懒加载），避免每帧创建新对象。
 */
public class GenericLivingItemOverrides extends ItemOverrides {

    private final BakedModel vanillaModel;
    private final LivingIconSpec spec;
    private final Map<String, GenericContextAwareModel> contextModelCache = new HashMap<>();
    private RotatingWaterWheelModel rotatingModelCache;

    public GenericLivingItemOverrides(BakedModel vanillaModel, LivingIconSpec spec) {
        super();
        this.vanillaModel = vanillaModel;
        this.spec = spec;
    }

    @Override
    public BakedModel resolve(BakedModel model, ItemStack stack, @Nullable ClientLevel level,
                              @Nullable LivingEntity entity, int seed) {
        if (spec.isRotating()) {
            float rpm = resolveRpm(stack);
            WaterWheelRenderState.setRPM(rpm);
            return getOrCreateRotatingModel(stack);
        }

        if (!spec.isLivingItem(stack)) {
            WaterWheelRenderState.clear();
            return vanillaModel;
        }

        for (LivingIconSpec.Variant variant : spec.getVariants()) {
            if (variant.getPredicate().test(stack)) {
                return getOrCreateContextModel(variant);
            }
        }

        return vanillaModel;
    }

    private float resolveRpm(ItemStack stack) {
        return 8f;
    }

    private RotatingWaterWheelModel getOrCreateRotatingModel(ItemStack stack) {
        if (rotatingModelCache == null) {
            rotatingModelCache = new RotatingWaterWheelModel(vanillaModel);
        }
        return rotatingModelCache;
    }

    /**
     * 获取或创建指定变体的上下文感知模型。
     *
     * <p>使用懒加载 + 缓存，确保每个变体只创建一个 GenericContextAwareModel 实例。
     *
     * @param variant 匹配的变体
     * @return 上下文感知模型
     */
    private GenericContextAwareModel getOrCreateContextModel(LivingIconSpec.Variant variant) {
        return contextModelCache.computeIfAbsent(variant.getName(),
            name -> new GenericContextAwareModel(
                resolveVariantModel(variant), vanillaModel));
    }

    /**
     * 从烘焙模型表中查找变体对应的 BakedModel。
     *
     * <p>由于 resolve() 在渲染时调用，此时所有模型已烘焙完成，
     * 可以通过 Minecraft.getInstance().getModelManager() 获取。
     * 但更可靠的方式是在注入时预存模型引用。
     *
     * <p>此方法由 LivingIconRegistry 在模型注入时设置变体模型引用后使用。
     * 变体模型引用通过 {@link LivingIconRegistry.VariantModelStore} 获取。
     */
    private BakedModel resolveVariantModel(LivingIconSpec.Variant variant) {
        BakedModel baked = LivingIconRegistry.VariantModelStore.get(variant);
        return baked != null ? baked : vanillaModel;
    }
}