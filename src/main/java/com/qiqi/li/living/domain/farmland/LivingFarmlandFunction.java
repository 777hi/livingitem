package com.qiqi.li.living.domain.farmland;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 活耕地功能 —— 容器内自动种植生长 + round-robin 逐项产出。
 *
 * <p><b>节拍模型</b>（2026-09-13 第四轮定稿）：
 * <ul>
 *   <li><b>输出不限速</b>：成熟后有冻结的待输出内容就每 tick 往生长槽推，
 *       放得下就出——不再 10 秒一项；生长槽被占时生长照常、战利品在队列里等位</li>
 *   <li><b>概率驱动产出</b>：成熟且待输出为空时，走带概率的生长 tick
 *       （湿润 1/9 / 干燥 1/26），判定成功才评估战利品表——成熟不着急获取</li>
 *   <li><b>留种</b>：冻结时与 cropSeed 相同的产出项数量 -1（最多到 0），
 *       变相自动补种——种子不再全额掉落</li>
 *   <li><b>采后回退</b>：浆果丛对齐原版采摘语义（回 age=1，保留 2/3 进度），
 *       其余作物回 age=0 重新长</li>
 *   <li><b>生长节拍</b>：世界轴时间戳（level.getGameTime()），稳态零组件写入零同步；
 *       生长槽被占不影响生长（BLOCKED 语义已精简删除）</li>
 *   <li><b>湿润标志</b>：每 tick 检测（含未种植耕地），翻转才写
 *       LIVING_FARMLAND_MOIST + 主动同步——图标 moist/dry 变体切换数据源</li>
 * </ul></p>
 */
public class LivingFarmlandFunction implements LivingItemFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingFarmland");

    public static final String ID = "living_farmland";

    /** 概率生长 tick 周期（200 ticks ≈ 10 秒），模拟原版随机刻节奏 */
    public static final int GROWTH_INTERVAL_TICKS = 200;

    /** 湿润传播源等级（与水流相邻的活耕地）：4→3→2→1 逐跳衰减，对齐原版 4 格湿润半径 */
    public static final int MAX_MOISTURE_LEVEL = 4;

    @Override
    public boolean canApply(ItemStack stack) {
        if (!LivingItemManager.isLivingItem(stack)) return false;
        return stack.is(Items.FARMLAND);
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    /**
     * 湿润/干燥耕地可堆叠（湿润标志是展示性状态，不影响功能语义）——
     * 燃烧标志 LIVING_FURNACE_BURNING 同款处理。
     */
    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of(LivingItemManager.LIVING_FARMLAND_MOIST.value());
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        int size = context.getSize();
        int width = context.getWidth();
        long now = level.getGameTime();

        // 湿润传播（原版式）：水源相邻=源 4 级，沿相邻活耕地每跳 -1（4→3→2→1，≥1 即湿润）
        int[] moisture = computeMoisture(context, tick, size, width, entries);

        for (SlotEntry entry : entries) {
            processFarmland(entry.slotIndex(), entry.stack(), context, tick, serverLevel,
                size, width, now, moisture[entry.slotIndex()]);
        }
    }

    /**
     * 湿润传播（2026-09-13 第五轮定稿，原版式水分扩散）：
     * 与水流相邻（4 向，含水桶源槽位自身）的活耕地 = <b>源 4 级</b>，湿润沿相邻的
     * 活耕地传播、每跳 -1（4→3→2→1），level ≥ 1 即湿润——一个水源可湿润 4 个直接
     * 相邻耕地 + 间接扩散，大大节省容器槽位占用。参考活红石粉信号传播的 BFS 形态。
     *
     * <p>派生态：每 tick 从 fluidData 现算（fluidData 由活水桶 prio 0 容器级数据
     * 先行算好），无需跨 tick 存储。只有活耕地是传播介质（普通物品/空槽不传）。</p>
     */
    private int[] computeMoisture(ContainerContext ctx, TickContext tick, int size, int width,
                                  List<SlotEntry> entries) {
        int[] levels = new int[size];
        if (tick.fluidData == null || tick.fluidData.isEmpty()) return levels;
        var flows = tick.fluidData.getFlows();

        java.util.Deque<Integer> queue = new java.util.ArrayDeque<>();
        // 源：与水流相邻的活耕地 = 源 4 级（MAX_MOISTURE_LEVEL）
        for (SlotEntry e : entries) {
            int slot = e.slotIndex();
            for (int dir : DIRS) {
                int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
                if (neighbor >= 0 && flows.containsKey(neighbor)) {
                    if (levels[slot] < MAX_MOISTURE_LEVEL) {
                        levels[slot] = MAX_MOISTURE_LEVEL;
                        queue.add(slot);
                    }
                    break;
                }
            }
        }
        // BFS：湿润沿相邻活耕地传播，每跳 -1
        while (!queue.isEmpty()) {
            int s = queue.poll();
            int next = levels[s] - 1;
            if (next <= 0) continue;
            for (int dir : DIRS) {
                int neighbor = ContainerContext.resolveNeighbor(s, dir, size, width);
                if (neighbor < 0 || levels[neighbor] >= next) continue;
                ItemStack stack = ctx.getItem(neighbor);
                if (!stack.is(Items.FARMLAND) || !LivingItemManager.isLivingItem(stack)) continue;
                levels[neighbor] = next;
                queue.add(neighbor);
            }
        }
        return levels;
    }

    private static final int[] DIRS = {
        ContainerContext.E_UP, ContainerContext.E_DOWN, ContainerContext.E_LEFT, ContainerContext.E_RIGHT
    };

    private void processFarmland(int slot, ItemStack farmland, ContainerContext ctx, TickContext tick,
                                 ServerLevel level, int size, int width, long now, int moistureLevel) {
        // 【湿润标志】level ≥ 1 即湿润，翻转才写 + 主动同步（ignored 组件需手动推）。
        // 未种植耕地也更新——湿润是耕地属性与种植无关。
        boolean moist = moistureLevel >= 1;
        if (LivingItemManager.isFarmlandMoist(farmland) != moist) {
            LivingItemManager.setFarmlandMoist(farmland, moist);
            ctx.syncSlotToClients(slot, farmland);
        }

        FarmlandPlantComponent plant = LivingItemManager.getFarmlandPlant(farmland);
        if (!plant.isPlanted()) return;

        Block cropBlock = CropClassifier.getBlockFromSeed(plant.cropSeed());
        // 种子指向的方块不可解析（模组卸载）→ 静默跳过，保留数据等待方块回归
        if (cropBlock == null) return;

        // 存量 maxAge 自愈：注册表修正（如旧版兜底 7 → 真实属性上限）后，
        // 旧组件里冻结的过期 maxAge 按当前注册表重冻结 + age 钳制，不需铲掉重种
        int currentMaxAge = CropClassifier.getMaxAge(cropBlock);
        if (plant.maxAge() != currentMaxAge) {
            plant = plant.withMaxAge(currentMaxAge);
            updatePlant(ctx, slot, farmland, plant);
        }

        int growthSlot = ContainerContext.resolveNeighbor(slot, ContainerContext.E_UP, size, width);
        boolean isMature = plant.isMature();

        // 【输出阶段】不限速：有冻结的待输出内容就每 tick 往生长槽推（放得下就出）。
        // 生长槽被占（玩家物品/未取走的产出）→ 放不进自然等待，生长不受影响。
        // 顶行耕地（growthSlot<0）无生长槽 → 输出挂起（搬到有生长槽的位置自动续）。
        if (isMature && growthSlot >= 0 && !plant.pendingDrops().isEmpty()) {
            FarmlandPlantComponent updated = tryOutputOnce(ctx, growthSlot, farmland, plant, cropBlock);
            updatePlant(ctx, slot, farmland, updated);
            plant = updated;
        }

        // 【概率生长 tick】冷却门（200t 世界轴）——未到期零写入直接返回（稳态）
        if (now - plant.lastGrowthAttemptTick() < GROWTH_INTERVAL_TICKS) return;
        plant = plant.withLastGrowthAttemptTick(now);

        // 概率判定：湿润传播等级 ≥1（含水源相邻的 4 级）→ f=3.0，否则 1.0
        float f = moistureLevel >= 1 ? 3.0F : 1.0F;
        if (level.random.nextInt((int) (25.0F / f) + 1) == 0) {
            plant = forceGrowthTick(plant, cropBlock, level);
        }

        // equals 守卫：概率失败等场景组件未变，不写不同步
        updatePlant(ctx, slot, farmland, plant);
    }

    /**
     * 输出一轮：把 pendingDrops[outputIndex] 放进生长槽（空放/同种合并），
     * 成功则推进索引；全部产完按作物回退点重置（浆果丛 age=1 / 其余 age=0）。
     * 放不下（不同种占据/同种剩余空间不足整份）原样返回，下个 tick 重试——
     * 输出不限速。包级可见：LivingFarmlandFunctionTest 直接驱动（不依赖 Level）。
     */
    static FarmlandPlantComponent tryOutputOnce(ContainerContext ctx, int growthSlot, ItemStack farmland,
                                                 FarmlandPlantComponent plant, Block cropBlock) {
        int i = plant.outputIndex();
        List<ItemStack> drops = plant.pendingDrops();
        if (i < 0 || i >= drops.size()) {
            // 索引越界（数据异常）→ 清空待输出，靠概率阶段重新评估自愈
            return plant.withOutput(-1, List.of());
        }

        ItemStack drop = drops.get(i);
        if (drop.isEmpty()) {
            // 空产出项（留种减到 0）→ 跳过推进到下一项
            return finishIfDone(plant.withOutput(i + 1, drops), cropBlock);
        }

        int outputCount = Math.min(farmland.getCount() * drop.getCount(), drop.getMaxStackSize());
        if (outputCount <= 0) return plant;

        ItemStack grown = ctx.getItem(growthSlot);
        boolean placed = false;
        if (grown.isEmpty()) {
            ctx.setItem(growthSlot, drop.copyWithCount(outputCount));
            placed = true;
        } else if (ItemStack.isSameItemSameComponents(grown, drop)) {
            int space = grown.getMaxStackSize() - grown.getCount();
            // 合并仅当放得下整份产出（部分合并会静默丢弃 outputCount-toAdd 差额，
            // 2026-09-14 终审实测修复）；放不下就本 tick 等待，与「不同种占据」同一语义
            if (space >= outputCount) {
                ctx.setItem(growthSlot, grown.copyWithCount(grown.getCount() + outputCount));
                placed = true;
            }
        }

        if (!placed) return plant;   // 不同种占据/同种已满 → 下个 tick 重试

        return finishIfDone(plant.withOutput(i + 1, drops), cropBlock);
    }

    private static FarmlandPlantComponent finishIfDone(FarmlandPlantComponent plant, Block cropBlock) {
        if (plant.outputIndex() >= plant.pendingDrops().size()) {
            // 采后回退：浆果丛对齐原版采摘语义（回 age=1，保留 2/3 进度）；其余回 0 重新长
            return plant.withAge(CropClassifier.getHarvestResetAge(cropBlock))
                        .withOutput(-1, List.of());
        }
        return plant;
    }

    /**
     * 首次成熟的战利品表评估冻结（round-robin 数据源，每轮一次掷骰）。
     *
     * <p>公开静态：骨粉催熟到 maxAge / 直接触发产出时在交互线程内即时调用——
     * 冻结后输出阶段（每 tick）自动把物品送进生长槽。茎作物产出来源是果实方块
     * （stem.fruit，AT 读取），非茎作物用自身。</p>
     *
     * <p><b>留种</b>：与 cropSeed 相同的产出项数量 -1（最多到 0）——变相自动补种，
     * 种子不再全额掉落（如小麦种子 2 → 1）。</p>
     *
     * @return 冻结后的组件；已有待输出（非首次）返回原实例；产出来源不可解析也返回原实例
     *         （下个概率周期重试）；留种后全空等极端情况按回退点重置的组件。
     */
    public static FarmlandPlantComponent tryFreezeDrops(FarmlandPlantComponent plant, Block cropBlock,
                                                        ServerLevel level) {
        if (!plant.pendingDrops().isEmpty()) return plant;   // 已有待输出

        List<ItemStack> drops;
        if (cropBlock instanceof StemBlock stem) {
            // 茎作物：产出果实方块物品本身（南瓜块/西瓜块）×耕地堆叠数——
            // 不滚果实战利品表（2026-09-13 定稿）。西瓜战利品表是 alternatives 结构，
            // 瓜块分支带 match_tool 精准采集条件、空工具恒不命中 → 只出 3~7 西瓜片；
            // 南瓜战利品表虽本就掉南瓜块，统一直取果块保证两条茎作物行为一致。
            Block fruit = CropClassifier.getStemFruit(stem);
            if (fruit == null) {
                LOGGER.warn("活耕地产出失败：作物 {} 的果实体不可解析，跳过本轮",
                    getBlockKey(cropBlock));
                return plant;
            }
            drops = List.of(new ItemStack(fruit.asItem()));
        } else {
            // 收获形态解析：作物自身战利品表不是收获形态时滚覆盖方块（FD 稻米/番茄——
            // 下部表只掉种子本身，滚它会被留种扣成空产出零循环）。覆盖方块解析为
            // AIR（模组不在）→ 回退作物自身，行为不变。
            Block lootSource = cropBlock;
            ResourceLocation harvestId = CropClassifier.getHarvestBlockId(cropBlock);
            if (harvestId != null) {
                Block harvest = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(harvestId);
                if (harvest != net.minecraft.world.level.block.Blocks.AIR) lootSource = harvest;
            }
            BlockState matureState = matureStateFor(lootSource);
            if (matureState == null) {
                LOGGER.warn("活耕地产出失败：作物 {} 无法构造成熟态 BlockState", getBlockKey(lootSource));
                return plant;
            }
            // 公开静态重载内部自动补齐 BLOCK_STATE/ORIGIN/TOOL 必填参数并走 BLOCK 参数集验证
            drops = Block.getDrops(matureState, level, net.minecraft.core.BlockPos.ZERO, null);
        }

        // 留种：与 cropSeed 相同的产出项数量 -1（最多到 0）——变相自动补种。
        // 总量 -1（只扣第一个非空匹配项）：多池掉同种种子也只扣一份
        Item seed = plant.cropSeed();
        List<ItemStack> adjusted = new ArrayList<>(drops.size());
        boolean seedDeducted = false;
        boolean anyLeft = false;
        for (ItemStack d : drops) {
            if (!seedDeducted && !d.isEmpty() && d.getItem() == seed) {
                d = d.copyWithCount(Math.max(0, d.getCount() - 1));
                seedDeducted = true;
            }
            if (!d.isEmpty()) anyLeft = true;
            adjusted.add(d);
        }
        if (!anyLeft) {
            // 留种后全空（种子是唯一产出且只掉 1）→ 保留未扣减的原产出照常输出：
            // 耕地组件本身持久化、产完照常按回退点重长，不依赖留种补种；
            // 重置空转只会零产出循环（2026-09-14 实测踩坑）
            LOGGER.debug("活耕地战利品表留种后为空（作物 {}），原样输出", getBlockKey(cropBlock));
            return plant.withOutput(0, drops);
        }
        return plant.withOutput(0, adjusted);
    }

    /** 构造成熟态 BlockState（age = maxAge）。无 age 属性的方块（南瓜/西瓜果实块——
     * 茎作物的产出来源）默认态即成熟态，直接返回；值域越界返回 null。
     * 旧实现对无 age 属性返回 null → 茎作物战利品表永远冻结失败、成熟无产物
     * （2026-09-13 实测踩坑）。 */
    private static BlockState matureStateFor(Block block) {
        IntegerProperty ageProp = CropClassifier.getAgeProperty(block);
        if (ageProp == null) return block.defaultBlockState();
        int maxAge = CropClassifier.getMaxAge(block);
        if (!ageProp.getPossibleValues().contains(maxAge)) return null;
        return block.defaultBlockState().setValue(ageProp, maxAge);
    }

    /**
     * 生长 tick 判定体（必定成功）：未成熟 → age+1；成熟且待输出为空 →
     * 冻结实³战利品表。公开静态——容器 tick 的概率门成功后与骨粉（强制触发
     * 一次生长 tick）共用同一段逻辑：作物一切行为由生长 tick 决定。
     */
    public static FarmlandPlantComponent forceGrowthTick(FarmlandPlantComponent plant, Block cropBlock,
                                                         ServerLevel level) {
        if (!plant.isMature()) {
            return plant.withAge(Math.min(plant.age() + 1, plant.maxAge()));
        }
        if (plant.pendingDrops().isEmpty()) {
            return tryFreezeDrops(plant, cropBlock, level);
        }
        return plant;
    }

    private void updatePlant(ContainerContext ctx, int slot, ItemStack stack, FarmlandPlantComponent plant) {
        // equals 守卫：概率失败/输出放不下等场景组件未变，不写不同步
        if (LivingItemManager.getFarmlandPlant(stack).equals(plant)) return;
        LivingItemManager.setFarmlandPlant(stack, plant);
        ctx.syncSlotToClients(slot, stack);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder,
                             TooltipFlag flag, ItemStack stack) {
        FarmlandPlantComponent plant = LivingItemManager.getFarmlandPlant(stack);
        if (!plant.isPlanted()) return;

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.farmland.title")
            .withStyle(ChatFormatting.GRAY));

        Component cropName = plant.cropSeed().getDescription();
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.farmland.crop")
            .append(Component.literal(": "))
            .append(cropName)
            .withStyle(ChatFormatting.GRAY));

        if (plant.isMature()) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.farmland.mature")
                .withStyle(ChatFormatting.GREEN));
        } else {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.farmland.growing")
                .append(Component.literal(": "))
                .append(Component.literal(plant.age() + " / " + plant.maxAge()))
                .withStyle(ChatFormatting.GRAY));
        }
    }

    /** 注册 id 字符串化辅助（日志用），避免直接依赖 BuiltInRegistries 的 import 冲突 */
    private static String getBlockKey(Block block) {
        return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block));
    }
}
