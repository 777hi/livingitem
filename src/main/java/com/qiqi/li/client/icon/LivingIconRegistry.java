package com.qiqi.li.client.icon;

import com.qiqi.li.LivingItem;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 活物品图标注册中心，统一管理所有活物品的图标配置和模型注入。
 *
 * <p>使用方式：在 {@link #registerAll()} 中声明式注册每个活物品的图标配置，
 * 然后在客户端事件中调用对应的处理方法。
 *
 * <p>注册示例：
 * <pre>
 * // 在 registerAll() 中
 * register(LivingIconSpec.builder(Items.FURNACE)
 *     .addVariant("idle", "item/furnace_idle", stack -> !isBurning(stack))
 *     .addVariant("active", "item/furnace_active", stack -> isBurning(stack))
 *     .build());
 * </pre>
 *
 * <p>之前每加一种活物品需要新建 3-4 个 Java 类，现在只需：
 * <ol>
 *   <li>在 registerAll() 中添加一个 LivingIconSpec 声明</li>
 *   <li>准备对应的纹理和模型 JSON 文件</li>
 * </ol>
 */
public final class LivingIconRegistry {

    private static final List<LivingIconSpec> SPECS = new ArrayList<>();

    private LivingIconRegistry() {}

    /**
     * 注册所有活物品的图标配置。
     *
     * <p>在此方法中声明式地添加每种活物品的图标需求。
     * 每次添加新的活物品类型时，只需在此方法中追加配置即可。
     */
    public static void registerAll() {
        register(LivingIconSpec.builder(net.minecraft.world.item.Items.HOPPER)
            .addVariant("base", "item/hopper_living", stack -> true)
            .decorator(new com.qiqi.li.client.LivingHopperDecorator())
            .build());

        register(LivingIconSpec.builder(net.minecraft.world.item.Items.FURNACE)
            .addVariant("idle", "item/furnace_idle",
                stack -> !com.qiqi.li.living.LivingFurnaceFunction.isBurning(stack))
            .addVariant("active", "item/furnace_active",
                stack -> com.qiqi.li.living.LivingFurnaceFunction.isBurning(stack))
            .build());

        register(LivingIconSpec.builder(net.minecraft.world.item.Items.TNT)
            .addVariant("lit", "item/tnt_lit",
                stack -> {
                    int timer = com.qiqi.li.living.LivingTntFunction.getFuseTimer(stack);
                    return timer > 0 && timer % 10 == 0;
                })
            .addVariant("idle", "item/tnt_idle", stack -> true)
            .build());
    }

    /** 注册一个图标配置 */
    public static void register(LivingIconSpec spec) {
        SPECS.add(spec);
    }

    /** 获取所有已注册的图标配置 */
    public static List<LivingIconSpec> getSpecs() {
        return List.copyOf(SPECS);
    }

    /**
     * 处理 ModelEvent.RegisterAdditional 事件，注册所有变体的独立模型。
     */
    public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        for (LivingIconSpec spec : SPECS) {
            for (LivingIconSpec.Variant variant : spec.getVariants()) {
                ModelResourceLocation loc = createVariantModelLocation(variant);
                event.register(loc);
            }
        }
        LivingItem.LOGGER.info("已注册 {} 个活物品变体模型", SPECS.stream().mapToInt(s -> s.getVariants().size()).sum());
    }

    /**
     * 处理 ModelEvent.ModifyBakingResult 事件，注入模型包装器。
     */
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        for (LivingIconSpec spec : SPECS) {
            injectModel(event, spec);
        }
    }

    /**
     * 处理 RegisterItemDecorationsEvent 事件，注册 ItemDecorator 叠加层。
     */
    public static void onRegisterItemDecorations(RegisterItemDecorationsEvent event) {
        for (LivingIconSpec spec : SPECS) {
            if (spec.getDecorator() != null) {
                event.register(spec.getItem(), spec.getDecorator());
            }
        }
    }

    /**
     * 为单个图标配置注入模型包装器。
     */
    private static void injectModel(ModelEvent.ModifyBakingResult event, LivingIconSpec spec) {
        ModelResourceLocation vanillaLoc = createInventoryModelLocation(spec.getItem());
        BakedModel vanillaModel = event.getModels().get(vanillaLoc);

        if (vanillaModel == null) {
            LivingItem.LOGGER.warn("活物品 {} 的原版模型未找到", spec.getItem());
            return;
        }

        for (LivingIconSpec.Variant variant : spec.getVariants()) {
            ModelResourceLocation variantLoc = createVariantModelLocation(variant);
            BakedModel variantModel = event.getModels().get(variantLoc);

            if (variantModel == null) {
                LivingItem.LOGGER.warn("活物品 {} 变体 {} 的模型未找到", spec.getItem(), variant.getName());
                continue;
            }

            VariantModelStore.put(variant, variantModel);
        }

        event.getModels().put(vanillaLoc, new GenericLivingModelWrapper(vanillaModel, spec));
        LivingItem.LOGGER.info("已注入活物品 {} 的模型覆盖 ({} 个变体)",
            spec.getItem(), spec.getVariants().size());
    }

    /**
     * 为原版物品创建 inventory 变体的模型定位器。
     * 例如 Items.HOPPER → minecraft:item/hopper#inventory
     */
    private static ModelResourceLocation createInventoryModelLocation(Item item) {
        ResourceLocation itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return ModelResourceLocation.inventory(itemId);
    }

    /**
     * 为变体创建 standalone 变体的模型定位器。
     * 例如 ("item/furnace_idle") → living_item:item/furnace_idle#standalone
     */
    private static ModelResourceLocation createVariantModelLocation(LivingIconSpec.Variant variant) {
        return ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, variant.getModelPath()));
    }

    /**
     * 变体模型的临时存储。
     *
     * <p>在 onModifyBakingResult 中，我们拿到烘焙后的变体模型并存入此处，
     * 供 GenericLivingItemOverrides 在 resolve() 时读取。
     *
     * <p>这是一个桥接机制，因为 GenericLivingItemOverrides 在构造时
     * 无法直接获取变体的 BakedModel（它们还未被放入模型表），
     * 而 resolve() 在渲染时调用时模型已经就绪。
     */
    public static final class VariantModelStore {
        private static final Map<String, BakedModel> STORE = new HashMap<>();

        private VariantModelStore() {}

        public static void put(LivingIconSpec.Variant variant, BakedModel model) {
            STORE.put(variant.getName() + "@" + variant.getModelPath(), model);
        }

        public static BakedModel get(LivingIconSpec.Variant variant) {
            return STORE.get(variant.getName() + "@" + variant.getModelPath());
        }

        public static void clear() {
            STORE.clear();
        }
    }
}