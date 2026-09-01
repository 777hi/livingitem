package com.qiqi.li.client.icon;

import com.qiqi.li.LivingItem;
import com.qiqi.li.client.render.LivingChiseledCopperDecorator;
import com.qiqi.li.client.render.LivingDefaultDecorator;
import com.qiqi.li.client.render.LivingHopperDecorator;
import com.qiqi.li.client.render.LivingMapIconDecorator;
import com.qiqi.li.client.render.LivingRedstoneDecorator;
import com.qiqi.li.living.compat.create.CreateCompat;
import com.qiqi.li.living.domain.redstone.LivingComparatorData;
import com.qiqi.li.living.domain.redstone.LivingRepeaterData;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 活物品图标注册中心，统一管理所有活物品的图标配置和模型注入。
 *
 * <p>图标系统分两层：
 * <ul>
 *   <li>模型替换层：有专门图标的活物品（漏斗、熔炉等）通过 LivingIconSpec 替换原版模型</li>
 *   <li>装饰器叠加层：所有物品通过 LivingDefaultDecorator 叠加 living.png 标记，
 *       已有专门图标的物品除外</li>
 * </ul>
 *
 * <p>注册示例：
 * <pre>
 * register(LivingIconSpec.builder(Items.FURNACE)
 *     .addVariant("idle", "item/furnace_idle", stack -> !isBurning(stack))
 *     .addVariant("active", "item/furnace_active", stack -> isBurning(stack))
 *     .build());
 * </pre>
 */
public final class LivingIconRegistry {

    private static final List<LivingIconSpec> SPECS = new ArrayList<>();

    private static final LivingDefaultDecorator DEFAULT_DECORATOR = new LivingDefaultDecorator();

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
            .decorator(new LivingHopperDecorator())
            .build());

        register(LivingIconSpec.builder(net.minecraft.world.item.Items.FURNACE)
            .addVariant("idle", "item/furnace_idle",
                stack -> !com.qiqi.li.living.domain.furnace.LivingFurnaceFunction.isBurning(stack))
            .addVariant("active", "item/furnace_active",
                stack -> com.qiqi.li.living.domain.furnace.LivingFurnaceFunction.isBurning(stack))
            .build());

        register(LivingIconSpec.builder(net.minecraft.world.item.Items.TNT)
            .addVariant("lit", "item/tnt_lit",
                stack -> {
                    int timer = com.qiqi.li.living.domain.tnt.LivingTntFunction.getFuseTimer(stack);
                    return timer > 0 && timer % 10 == 0;
                })
            .addVariant("idle", "item/tnt_idle", stack -> true)
            .build());

        register(LivingIconSpec.builder(net.minecraft.world.item.Items.CHEST)
            .addVariant("base", "item/chest", stack -> true)
            .build());

        register(LivingIconSpec.builder(net.minecraft.world.item.Items.REDSTONE_LAMP)
            .addVariant("on", "item/redstone_lamp_on",
                stack -> com.qiqi.li.living.api.LivingItemManager.getLampData(stack).lit())
            .addVariant("off", "item/redstone_lamp", stack -> true)
            .build());

        register(LivingIconSpec.builder(net.minecraft.world.item.Items.REDSTONE_TORCH)
            .addVariant("on", "item/redstone_torch",
                stack -> com.qiqi.li.living.api.LivingItemManager.getRedstoneTorchData(stack).isLit())
            .addVariant("off", "item/redstone_torch_off", stack -> true)
            .directional()
            .build());

        register(LivingIconSpec.builder(net.minecraft.world.item.Items.REPEATER)
            .addVariant("1tick", "item/repeater_1tick",
                stack -> {
                    LivingRepeaterData d = com.qiqi.li.living.api.LivingItemManager.getRepeaterData(stack);
                    return d.delay() == 1 && !d.powered();
                })
            .addVariant("1tick_on", "item/repeater_1tick_on",
                stack -> {
                    LivingRepeaterData d = com.qiqi.li.living.api.LivingItemManager.getRepeaterData(stack);
                    return d.delay() == 1 && d.powered();
                })
            .addVariant("2tick", "item/repeater_2tick",
                stack -> {
                    LivingRepeaterData d = com.qiqi.li.living.api.LivingItemManager.getRepeaterData(stack);
                    return d.delay() == 2 && !d.powered();
                })
            .addVariant("2tick_on", "item/repeater_2tick_on",
                stack -> {
                    LivingRepeaterData d = com.qiqi.li.living.api.LivingItemManager.getRepeaterData(stack);
                    return d.delay() == 2 && d.powered();
                })
            .addVariant("3tick", "item/repeater_3tick",
                stack -> {
                    LivingRepeaterData d = com.qiqi.li.living.api.LivingItemManager.getRepeaterData(stack);
                    return d.delay() == 3 && !d.powered();
                })
            .addVariant("3tick_on", "item/repeater_3tick_on",
                stack -> {
                    LivingRepeaterData d = com.qiqi.li.living.api.LivingItemManager.getRepeaterData(stack);
                    return d.delay() == 3 && d.powered();
                })
            .addVariant("4tick", "item/repeater_4tick",
                stack -> {
                    LivingRepeaterData d = com.qiqi.li.living.api.LivingItemManager.getRepeaterData(stack);
                    return d.delay() == 4 && !d.powered();
                })
            .addVariant("4tick_on", "item/repeater_4tick_on",
                stack -> {
                    LivingRepeaterData d = com.qiqi.li.living.api.LivingItemManager.getRepeaterData(stack);
                    return d.delay() == 4 && d.powered();
                })
            .directional()
            .guiScale(1.0f)
            .build());

        register(LivingIconSpec.builder(net.minecraft.world.item.Items.COMPARATOR)
            .addVariant("compare", "item/comparator_compare",
                stack -> {
                    LivingComparatorData d = com.qiqi.li.living.api.LivingItemManager.getComparatorData(stack);
                    return !d.subtractMode() && !d.powered();
                })
            .addVariant("compare_on", "item/comparator_compare_on",
                stack -> {
                    LivingComparatorData d = com.qiqi.li.living.api.LivingItemManager.getComparatorData(stack);
                    return !d.subtractMode() && d.powered();
                })
            .addVariant("subtract", "item/comparator_subtract",
                stack -> {
                    LivingComparatorData d = com.qiqi.li.living.api.LivingItemManager.getComparatorData(stack);
                    return d.subtractMode() && !d.powered();
                })
            .addVariant("subtract_on", "item/comparator_subtract_on",
                stack -> {
                    LivingComparatorData d = com.qiqi.li.living.api.LivingItemManager.getComparatorData(stack);
                    return d.subtractMode() && d.powered();
                })
            .directional()
            .guiScale(1.0f)
            .build());

        register(LivingIconSpec.builder(net.minecraft.world.item.Items.LEVER)
            .addVariant("off", "item/lever",
                stack -> !com.qiqi.li.living.api.LivingItemManager.getLeverData(stack).powered())
            .addVariant("on", "item/lever_on",
                stack -> com.qiqi.li.living.api.LivingItemManager.getLeverData(stack).powered())
            .build());

        register(LivingIconSpec.builder(net.minecraft.world.item.Items.ENDER_CHEST)
            .addVariant("base", "item/ender", stack -> true)
            .build());

        register(LivingIconSpec.builder(net.minecraft.world.item.Items.FILLED_MAP)
            .addVariant("base", "item/living_map", stack -> true)
            .decorator(new LivingMapIconDecorator())
            .build());

        register(LivingIconSpec.builder(net.minecraft.world.item.Items.REDSTONE)
            .addVariant("base", "item/redstone_dust", stack -> true)
            .decorator(new LivingRedstoneDecorator())
            .build());

        register(LivingIconSpec.builder(net.minecraft.world.item.Items.REDSTONE_BLOCK)
            .addVariant("base", "item/redstone_block", stack -> true)
            .build());

        registerCopperIcons();

        if (CreateCompat.isLoaded()) {
            try {
                Item waterWheelItem = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", "water_wheel"));
                if (waterWheelItem != net.minecraft.world.item.Items.AIR) {
                    register(LivingIconSpec.builder(waterWheelItem)
                        .addVariant("base", "item/water_wheel", stack -> true)
                        .rotating()
                        .build());
                    LivingItem.LOGGER.info("已注册活水车旋转图标");
                }
            } catch (Exception e) {
                LivingItem.LOGGER.warn("注册活水车图标失败: {}", e.getMessage());
            }
        }
    }

    private static void registerCopperIcons() {
        registerCopperBlock(net.minecraft.world.item.Items.COPPER_BLOCK, "item/copper_block");
        registerCopperBlock(net.minecraft.world.item.Items.EXPOSED_COPPER, "item/exposed_copper");
        registerCopperBlock(net.minecraft.world.item.Items.WEATHERED_COPPER, "item/weathered_copper");
        registerCopperBlock(net.minecraft.world.item.Items.OXIDIZED_COPPER, "item/oxidized_copper");
        registerCopperBlock(net.minecraft.world.item.Items.WAXED_COPPER_BLOCK, "item/waxed_copper_block");
        registerCopperBlock(net.minecraft.world.item.Items.WAXED_EXPOSED_COPPER, "item/waxed_exposed_copper");
        registerCopperBlock(net.minecraft.world.item.Items.WAXED_WEATHERED_COPPER, "item/waxed_weathered_copper");
        registerCopperBlock(net.minecraft.world.item.Items.WAXED_OXIDIZED_COPPER, "item/waxed_oxidized_copper");

        registerChiseledCopper(net.minecraft.world.item.Items.CHISELED_COPPER, "item/chiseled_copper");
        registerChiseledCopper(net.minecraft.world.item.Items.EXPOSED_CHISELED_COPPER, "item/exposed_chiseled_copper");
        registerChiseledCopper(net.minecraft.world.item.Items.WEATHERED_CHISELED_COPPER, "item/weathered_chiseled_copper");
        registerChiseledCopper(net.minecraft.world.item.Items.OXIDIZED_CHISELED_COPPER, "item/oxidized_chiseled_copper");
        registerChiseledCopper(net.minecraft.world.item.Items.WAXED_CHISELED_COPPER, "item/waxed_chiseled_copper");
        registerChiseledCopper(net.minecraft.world.item.Items.WAXED_EXPOSED_CHISELED_COPPER, "item/waxed_exposed_chiseled_copper");
        registerChiseledCopper(net.minecraft.world.item.Items.WAXED_WEATHERED_CHISELED_COPPER, "item/waxed_weathered_chiseled_copper");
        registerChiseledCopper(net.minecraft.world.item.Items.WAXED_OXIDIZED_CHISELED_COPPER, "item/waxed_oxidized_chiseled_copper");

        registerCopperBlock(net.minecraft.world.item.Items.CUT_COPPER, "item/cut_copper");
        registerCopperBlock(net.minecraft.world.item.Items.EXPOSED_CUT_COPPER, "item/exposed_cut_copper");
        registerCopperBlock(net.minecraft.world.item.Items.WEATHERED_CUT_COPPER, "item/weathered_cut_copper");
        registerCopperBlock(net.minecraft.world.item.Items.OXIDIZED_CUT_COPPER, "item/oxidized_cut_copper");
        registerCopperBlock(net.minecraft.world.item.Items.WAXED_CUT_COPPER, "item/waxed_cut_copper");
        registerCopperBlock(net.minecraft.world.item.Items.WAXED_EXPOSED_CUT_COPPER, "item/waxed_exposed_cut_copper");
        registerCopperBlock(net.minecraft.world.item.Items.WAXED_WEATHERED_CUT_COPPER, "item/waxed_weathered_cut_copper");
        registerCopperBlock(net.minecraft.world.item.Items.WAXED_OXIDIZED_CUT_COPPER, "item/waxed_oxidized_cut_copper");

        registerCopperBlock(net.minecraft.world.item.Items.COPPER_GRATE, "item/copper_grate");
        registerCopperBlock(net.minecraft.world.item.Items.EXPOSED_COPPER_GRATE, "item/exposed_copper_grate");
        registerCopperBlock(net.minecraft.world.item.Items.WEATHERED_COPPER_GRATE, "item/weathered_copper_grate");
        registerCopperBlock(net.minecraft.world.item.Items.OXIDIZED_COPPER_GRATE, "item/oxidized_copper_grate");
        registerCopperBlock(net.minecraft.world.item.Items.WAXED_COPPER_GRATE, "item/waxed_copper_grate");
        registerCopperBlock(net.minecraft.world.item.Items.WAXED_EXPOSED_COPPER_GRATE, "item/waxed_exposed_copper_grate");
        registerCopperBlock(net.minecraft.world.item.Items.WAXED_WEATHERED_COPPER_GRATE, "item/waxed_weathered_copper_grate");
        registerCopperBlock(net.minecraft.world.item.Items.WAXED_OXIDIZED_COPPER_GRATE, "item/waxed_oxidized_copper_grate");

        registerCopperBulb(net.minecraft.world.item.Items.COPPER_BULB, "item/copper_bulb", "item/copper_bulb_lit");
        registerCopperBulb(net.minecraft.world.item.Items.EXPOSED_COPPER_BULB, "item/exposed_copper_bulb", "item/exposed_copper_bulb_lit");
        registerCopperBulb(net.minecraft.world.item.Items.WEATHERED_COPPER_BULB, "item/weathered_copper_bulb", "item/weathered_copper_bulb_lit");
        registerCopperBulb(net.minecraft.world.item.Items.OXIDIZED_COPPER_BULB, "item/oxidized_copper_bulb", "item/oxidized_copper_bulb_lit");
        registerWaxedCopperBulb(net.minecraft.world.item.Items.WAXED_COPPER_BULB, "item/waxed_copper_bulb", "item/waxed_copper_bulb_lit");
        registerWaxedCopperBulb(net.minecraft.world.item.Items.WAXED_EXPOSED_COPPER_BULB, "item/waxed_exposed_copper_bulb", "item/waxed_exposed_copper_bulb_lit");
        registerWaxedCopperBulb(net.minecraft.world.item.Items.WAXED_WEATHERED_COPPER_BULB, "item/waxed_weathered_copper_bulb", "item/waxed_weathered_copper_bulb_lit");
        registerWaxedCopperBulb(net.minecraft.world.item.Items.WAXED_OXIDIZED_COPPER_BULB, "item/waxed_oxidized_copper_bulb", "item/waxed_oxidized_copper_bulb_lit");
    }

    private static void registerCopperBulb(Item item, String textureOff, String textureOn) {
        register(LivingIconSpec.builder(item)
            .addVariant("lit", textureOn,
                stack -> com.qiqi.li.living.api.LivingItemManager.getCopperBulbData(stack).isLit())
            .addVariant("unlit", textureOff, stack -> true)
            .build());
    }

    /** 涂蜡铜灯（电力层）：有电量 → 点亮图标 */
    private static void registerWaxedCopperBulb(Item item, String textureOff, String textureOn) {
        register(LivingIconSpec.builder(item)
            .addVariant("lit", textureOn,
                stack -> com.qiqi.li.living.api.LivingItemManager.getWaxedBulbData(stack).chargeMilliFe() > 0)
            .addVariant("unlit", textureOff, stack -> true)
            .build());
    }

    private static void registerCopperBlock(Item item, String texture) {
        register(LivingIconSpec.builder(item).addVariant("base", texture, stack -> true).build());
    }

    private static void registerChiseledCopper(Item item, String texture) {
        register(LivingIconSpec.builder(item)
            .addVariant("base", texture, stack -> true)
            .decorator(new LivingChiseledCopperDecorator())
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
            if (spec.isRotating()) continue;
            for (LivingIconSpec.Variant variant : spec.getVariants()) {
                ModelResourceLocation loc = createVariantModelLocation(variant);
                event.register(loc);
            }
        }
        LivingItem.LOGGER.info("已注册 {} 个活物品变体模型",
            SPECS.stream().filter(s -> !s.isRotating()).mapToInt(s -> s.getVariants().size()).sum());
    }

    /**
     * 处理 ModelEvent.ModifyBakingResult 事件，注入模型包装器。
     *
     * <p>仅为有专门图标的活物品注入模型包装器，不处理默认标记。
     * 默认标记通过 {@link LivingDefaultDecorator} 在渲染时叠加。
     */
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        for (LivingIconSpec spec : SPECS) {
            injectModel(event, spec);
        }
    }

    /**
     * 处理 RegisterItemDecorationsEvent 事件，注册 ItemDecorator 叠加层。
     *
     * <p>分两部分：
     * <ol>
     *   <li>为有专门装饰器的活物品注册其专属装饰器（如漏斗箭头、地图缩略图）</li>
     *   <li>为所有没有专门图标的物品注册默认活物品标记装饰器</li>
     * </ol>
     */
    public static void onRegisterItemDecorations(RegisterItemDecorationsEvent event) {
        Set<Item> dedicatedItems = new HashSet<>();
        for (LivingIconSpec spec : SPECS) {
            dedicatedItems.add(spec.getItem());
            if (spec.getDecorator() != null) {
                event.register(spec.getItem(), spec.getDecorator());
            }
        }

        for (Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (!dedicatedItems.contains(item)) {
                event.register(item, DEFAULT_DECORATOR);
            }
        }

        LivingItem.LOGGER.info("已为 {} 个物品注册默认活物品标记装饰器 (排除 {} 个有专门图标的物品)",
            net.minecraft.core.registries.BuiltInRegistries.ITEM.size() - dedicatedItems.size(), dedicatedItems.size());
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

        if (!spec.isRotating()) {
            for (LivingIconSpec.Variant variant : spec.getVariants()) {
                ModelResourceLocation variantLoc = createVariantModelLocation(variant);
                BakedModel variantModel = event.getModels().get(variantLoc);

                if (variantModel == null) {
                    LivingItem.LOGGER.warn("活物品 {} 变体 {} 的模型未找到", spec.getItem(), variant.getName());
                    continue;
                }

                VariantModelStore.put(variant, variantModel);
            }
        }

        event.getModels().put(vanillaLoc, new GenericLivingModelWrapper(vanillaModel, spec));
        LivingItem.LOGGER.info("已注入活物品 {} 的模型覆盖{}",
            spec.getItem(), spec.isRotating() ? " (旋转模式)" : " (" + spec.getVariants().size() + " 个变体)");
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