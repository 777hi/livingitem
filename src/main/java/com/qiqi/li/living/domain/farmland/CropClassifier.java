package com.qiqi.li.living.domain.farmland;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.neoforged.neoforge.common.Tags;

/**
 * 活耕地作物分类器 —— 种植准入、类型判定、maxAge 读取的唯一定义点。
 *
 * <p>种植准入三层（白名单 + 标签 + 手动映射），详见 docs/idea.md。
 * 白名单只认 Block 类型：1.21.1 中 FlowerBlock/SaplingBlock/DeadBushBlock/TallGrassBlock
 * 全部继承 BushBlock，直接 {@code instanceof BushBlock} 会把花/树苗/草全判成作物，
 * 故第 2 层不用 BushBlock 兜底。模组作物依赖 c:seeds 标签（c:crops 混有仙人掌/
 * 可可豆等非耕地作物，不用）。</p>
 */
public final class CropClassifier {

    private CropClassifier() {}

    /** 模组种子标签（覆盖遵循 NeoForge 通用标签约定的模组作物） */
    private static final TagKey<Item> SEEDS_TAG = Tags.Items.SEEDS;

    /**
     * 手动映射表：非 BlockItem 的原版种子 → 作物方块。
     * 甜浆果（SWEET_BERRIES）在原版没有方块物品形态，三层判定全漏，靠这里兜住。
     */
    private static final Map<Item, Block> MANUAL_SEEDS = Map.of(
        Items.SWEET_BERRIES, Blocks.SWEET_BERRY_BUSH
    );

    /** 已知不符合耕地语义的 c:seeds 成员（预留给需要收紧时使用） */
    private static final Map<Item, Block> DYNAMIC_MANUAL_SEEDS = new HashMap<>();

    /** 可种植性判定：给 PlantCropHandler 调用 */
    public static boolean isSeedPlantableOnFarmland(ItemStack stack) {
        Block block = getBlockFromSeed(stack);
        if (block != null && isKnownCropBlock(block)) {
            return true;
        }
        return stack.is(SEEDS_TAG);
    }

    /** 种子 → 作物方块（BlockItem 直接取，手动映射兜底非 BlockItem 种子） */
    @Nullable
    public static Block getBlockFromSeed(ItemStack seedStack) {
        return getBlockFromSeed(seedStack.getItem());
    }

    @Nullable
    public static Block getBlockFromSeed(Item seed) {
        if (MANUAL_SEEDS.containsKey(seed)) return MANUAL_SEEDS.get(seed);
        if (DYNAMIC_MANUAL_SEEDS.containsKey(seed)) return DYNAMIC_MANUAL_SEEDS.get(seed);
        if (seed instanceof BlockItem bi) return bi.getBlock();
        return null;
    }

    /** Block 层白名单：原版全部作物类型（不含 BushBlock 兜底，理由见类 javadoc）。
     *  瓶子草 extends DoublePlantBlock 而非 CropBlock——必须显式列入，否则三层
     *  准入全漏（pitcher_pod 也不在 c:seeds 标签，2026-09-14 实测踩坑）；但只加
     *  PitcherCropBlock 精确类，不用 DoublePlantBlock（玫瑰/牡丹/向日葵会误入）。 */
    public static boolean isKnownCropBlock(Block block) {
        return block instanceof CropBlock
            || block instanceof StemBlock
            || block instanceof NetherWartBlock
            || block instanceof SweetBerryBushBlock
            || block instanceof net.minecraft.world.level.block.PitcherCropBlock;
    }

    /** 茎作物判定（西瓜/南瓜） */
    public static boolean isStemCrop(Block block) {
        return block instanceof StemBlock;
    }

    /**
     * 采后回退点（产完重置到的 age）：
     * 浆果丛对齐原版采摘语义（SweetBerryBushBlock 采后回 age=1，保留 2/3 进度，
     * 随机刻/概率 tick 从 1 长回 3 再产）；其余作物回 0 重新长。
     */
    public static int getHarvestResetAge(Block block) {
        return block instanceof SweetBerryBushBlock ? 1 : 0;
    }

    /**
     * 作物最大生长阶段（种植时读取一次冻结进组件）。
     * 勘误：StemBlock/NetherWartBlock/SweetBerryBushBlock 的最大阶段是公开常量 MAX_AGE，
     * 没有 getMaxAge() 方法；只有 CropBlock 有。
     *
     * <p>模组作物（FD 水稻 AGE_3 / BuddingTomato 0~4 等）从 age 属性读<b>真实上限</b>——
     * 旧实现兜底 7 会导致 age 超出值域：构造成熟态失败（无战利品无产出）+ 渲染走
     * 兜底种子图标（2026-09-13 实测踩坑）。</p>
     */
    public static int getMaxAge(Block block) {
        if (block instanceof CropBlock crop) return crop.getMaxAge();
        if (block instanceof StemBlock) return StemBlock.MAX_AGE;
        if (block instanceof NetherWartBlock) return NetherWartBlock.MAX_AGE;
        if (block instanceof SweetBerryBushBlock) return SweetBerryBushBlock.MAX_AGE;
        // 模组作物：state 定义里的 "age" IntegerProperty 的最大值即真实上限
        IntegerProperty ageProp = getAgeProperty(block);
        if (ageProp != null) {
            return ageProp.getPossibleValues().stream().max(Integer::compare).orElse(CropBlock.MAX_AGE);
        }
        return CropBlock.MAX_AGE;   // 完全无 age 属性的最终兜底
    }

    /**
     * 取方块 age 属性（属性名 "age" 的 IntegerProperty；无则 null）。
     * common 安全（BlockState.getProperties 无客户端依赖）。
     */
    @Nullable
    public static IntegerProperty getAgeProperty(Block block) {
        for (var prop : block.defaultBlockState().getProperties()) {
            if (prop instanceof IntegerProperty intProp && prop.getName().equals("age")) {
                return intProp;
            }
        }
        return null;
    }

    /**
     * 茎作物的果实方块（产出来源 + 成熟渲染），需 AT：StemBlock.fruit。
     * 非 StemBlock 或果实未注册（模组方块卸载）返回 null。
     */
    @Nullable
    public static Block getStemFruit(StemBlock stem) {
        ResourceKey<Block> fruitKey = stem.fruit;
        return BuiltInRegistries.BLOCK.get(fruitKey);
    }

    /** 手动注册扩展点（未来公开 API 的雏形） */
    public static void registerManualSeed(Item seed, Block cropBlock) {
        DYNAMIC_MANUAL_SEEDS.put(seed, cropBlock);
    }

    // ── 两格高作物「上部件」──

    /**
     * 两格高作物的「上部件」注册（值按注册名延迟解析，软依赖安全——
     * 上部件模组不在时 BuiltInRegistries 解析为 AIR 自动跳过，不引对方类）。
     */
    private static final Map<ResourceLocation, ResourceLocation> UPPER_CROPS = new HashMap<>(Map.of(
        ResourceLocation.fromNamespaceAndPath("farmersdelight", "rice"),
        ResourceLocation.fromNamespaceAndPath("farmersdelight", "rice_panicles")
    ));

    /** 注册两格高作物的上部件方块（下部件注册名 → 上部件注册名） */
    public static void registerUpperCrop(ResourceLocation lowerBlock, ResourceLocation upperBlock) {
        UPPER_CROPS.put(lowerBlock, upperBlock);
    }

    @Nullable
    private static ResourceLocation getUpperCropId(Block lowerBlock) {
        return UPPER_CROPS.get(BuiltInRegistries.BLOCK.getKey(lowerBlock));
    }

    /** 构造指定 age 的作物 BlockState（值域钳制，越界返回 null）——从渲染器迁入（common 安全） */
    @Nullable
    public static BlockState stateForAge(Block block, int age) {
        IntegerProperty ageProp = getAgeProperty(block);
        if (ageProp == null) return null;
        if (!ageProp.getPossibleValues().contains(age)) return null;
        return block.defaultBlockState().setValue(ageProp, age);
    }

    /**
     * 渲染用生长阶段 BlockState：age 越出属性值域且已成熟 → 回退收获形态方块的
     * 默认态。火把花类原版怪癖——AGE 属性是 AGE_1（值域 0~1）而 getMaxAge()=2，
     * 成熟态 {@code getStateForAge(2)} 直接变成花方块，作物方块本身没有 age=2 状态；
     * 越界时 {@link #stateForAge} 返回 null → 成熟后生长槽空白（2026-09-14 实测踩坑）。
     */
    @Nullable
    public static BlockState displayStateFor(Block block, int age) {
        BlockState state = stateForAge(block, age);
        if (state != null) return state;
        if (age < getMaxAge(block)) return null;   // 未成熟越界 = 异常数据，不渲染
        ResourceLocation harvestId = getHarvestBlockId(block);
        if (harvestId == null) return null;
        Block harvest = BuiltInRegistries.BLOCK.get(harvestId);
        return harvest != Blocks.AIR ? harvest.defaultBlockState() : null;
    }

    /**
     * 两格高作物的「上部件」BlockState（GUI 渲染用，null = 无上部件）。
     *
     * <p>两种模式：</p>
     * <ul>
     *   <li><b>原版半部件</b>（{@code DOUBLE_BLOCK_HALF}，如瓶子草）：上部件 = 同方块
     *       HALF=UPPER、同 age——上下同步生长、常驻渲染</li>
     *   <li><b>注册式上部件</b>（FD 水稻 = RiceBlock + 上方独立 RicePaniclesBlock）：
     *       下部件成熟后上部件才存在（原版「成熟才抽穗」），渲染其自身成熟态</li>
     * </ul>
     * <p>纯视觉——上部件不参与 tick/产出逻辑。</p>
     */
    @Nullable
    public static BlockState getUpperCompanion(Block cropBlock, int age) {
        BlockState state = stateForAge(cropBlock, age);
        if (state == null) return null;

        // 原版半部件：翻 UPPER，同 age 同步生长
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        }

        // 注册式：下部件成熟后上部件才出现，以其自身成熟态渲染
        ResourceLocation upperId = getUpperCropId(cropBlock);
        if (upperId != null && age >= getMaxAge(cropBlock)) {
            Block upper = BuiltInRegistries.BLOCK.get(upperId);
            if (upper != Blocks.AIR) {
                BlockState us = upper.defaultBlockState();
                IntegerProperty ap = getAgeProperty(upper);
                if (ap != null) {
                    int upperMax = ap.getPossibleValues().stream().max(Integer::compare).orElse(0);
                    if (ap.getPossibleValues().contains(upperMax)) {
                        us = us.setValue(ap, upperMax);
                    }
                }
                return us;
            }
        }
        return null;
    }

    // ── 收获形态方块（战利品表来源覆盖）──

    /**
     * 收获形态方块注册：作物<b>自身战利品表不是收获形态</b>时的覆盖，冻结产出时
     * 滚覆盖方块的成熟态战利品表（键/值按注册名延迟解析，软依赖安全——收获方块
     * 模组不在时解析为 AIR 自动回退作物自身，行为不变）。
     *
     * <p>FD 两例（2026-09-14 实测踩坑）：下部方块战利品表只掉种子本身，滚它会被
     * 留种扣成空产出（零产出循环）——真实收获形态在别的方块上：</p>
     * <ul>
     *   <li>稻米 rice → 上部抽穗 rice_panicles（空工具掉稻穗 rice_panicle，刀才出稻谷；
     *       稻穗≠种子，留种不扣）</li>
     *   <li>番茄 budding_tomatoes → 成熟原地转化的结果藤 tomatoes（番茄×1-2 + 种子×1
     *       + 5% 烂番茄；ROPELOGGED 默认 false 恰好命中种子池条件）</li>
     * </ul>
     */
    private static final Map<ResourceLocation, ResourceLocation> HARVEST_BLOCKS = new HashMap<>(Map.of(
        ResourceLocation.fromNamespaceAndPath("farmersdelight", "rice"),
        ResourceLocation.fromNamespaceAndPath("farmersdelight", "rice_panicles"),
        ResourceLocation.fromNamespaceAndPath("farmersdelight", "budding_tomatoes"),
        ResourceLocation.fromNamespaceAndPath("farmersdelight", "tomatoes"),
        // 火把花：作物表任何 age 只掉种子×1，成熟态是花方块（getStateForAge(2)=TORCHFLOWER），
        // 真实收获在花方块表（torchflower×1）——与 FD 下部表同型的「种植入口 ≠ 收获形态」
        ResourceLocation.fromNamespaceAndPath("minecraft", "torchflower_crop"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "torchflower")
    ));

    /** 注册收获形态方块（作物方块注册名 → 收获形态方块注册名） */
    public static void registerHarvestBlock(ResourceLocation cropBlock, ResourceLocation harvestBlock) {
        HARVEST_BLOCKS.put(cropBlock, harvestBlock);
    }

    /** 作物的收获形态方块注册名（无覆盖返回 null = 滚作物自身战利品表） */
    @Nullable
    public static ResourceLocation getHarvestBlockId(Block cropBlock) {
        return HARVEST_BLOCKS.get(BuiltInRegistries.BLOCK.getKey(cropBlock));
    }

    // ── 柱状多段作物（同方块属性分段）──

    /**
     * 柱状多段作物注册：作物注册名 → 自身向上的各段属性覆盖（属性名 → 值）。
     * 第三种多格形态——既非原版半部件（DOUBLE_BLOCK_HALF 两值）也非 FD 的两个
     * 独立方块，而是<b>同一方块 + IntegerProperty 分段</b>：种下时多格同时放置、
     * 各段 age 同步生长（下方邻居 updateShape 拷贝）、每段每 age 各有独立模型。
     *
     * <p>第一例（2026-09-14 实测踩坑）：KC 水稻 {@code kaleidoscope_cookery:rice_crop}
     * ——{@code location}(0=下/1=中/2=上) 三格柱。渲染上 {@link #stateForAge} 用
     * 默认态只出第一格，其余段 {@link #getUpperCompanion} 返回 null 不渲染。</p>
     */
    private static final Map<ResourceLocation, List<Map<String, String>>> COLUMN_PARTS = new HashMap<>(Map.of(
        ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "rice_crop"),
        List.of(Map.of("location", "1"), Map.of("location", "2"))
    ));

    /** 注册柱状多段作物（作物方块注册名 → 各段属性覆盖；自下而上排列） */
    public static void registerColumnParts(ResourceLocation cropBlock, List<Map<String, String>> parts) {
        COLUMN_PARTS.put(cropBlock, List.copyOf(parts));
    }

    /**
     * 柱状多段作物的各段 BlockState（渲染用，自下而上；未注册/无 age 态返回空表）。
     * 各段 = {@link #stateForAge} 基础上应用属性覆盖；属性缺失的段静默跳过
     * （数据不匹配时不渲染好过渲染错误状态）。
     */
    public static List<BlockState> getColumnParts(Block cropBlock, int age) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(cropBlock);
        List<Map<String, String>> parts = COLUMN_PARTS.get(id);
        if (parts == null) return List.of();
        BlockState base = stateForAge(cropBlock, age);
        if (base == null) return List.of();
        List<BlockState> states = new ArrayList<>(parts.size());
        for (Map<String, String> overrides : parts) {
            BlockState part = base;
            boolean ok = true;
            for (var e : overrides.entrySet()) {
                Property<?> prop = getProperty(cropBlock, e.getKey());
                if (prop == null) { ok = false; break; }
                part = applyValue(part, prop, e.getValue());
                if (part == null) { ok = false; break; }
            }
            if (ok) states.add(part);
        }
        return states;
    }

    @Nullable
    private static Property<?> getProperty(Block block, String name) {
        for (var prop : block.defaultBlockState().getProperties()) {
            if (prop.getName().equals(name)) return prop;
        }
        return null;
    }

    /** 泛型桥：String 值解析进 Property 并 setValue（解析失败返回 null） */
    @Nullable
    private static <T extends Comparable<T>> BlockState applyValue(BlockState state, Property<T> prop, String value) {
        java.util.Optional<T> parsed = prop.getValue(value);
        return parsed.map(v -> state.setValue(prop, v)).orElse(null);
    }
}
