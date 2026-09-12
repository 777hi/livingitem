package com.qiqi.li.living.domain.farmland;

import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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
 * 活耕地功能 —— 容器内自动种植生长 + round-robin 逐项产出（docs/idea.md 活耕地设计）。
 *
 * <p>生长节拍：世界轴时间戳（level.getGameTime()），每 {@link #GROWTH_INTERVAL_TICKS}
 * 一次生长/产出尝试。稳态（未到冷却周期）零组件写入零同步。</p>
 *
 * <p>湿润：左/右/下邻居槽位存在活水流（TickContext.fluidData）→ f=3.0，否则 f=1.0。
 * 概率判定与原版随机刻同公式 nextInt(25/f + 1) == 0。</p>
 *
 * <p>产出：成熟时评估战利品表冻结进 pendingDrops（每轮一次掷骰），
 * 每冷却周期产出一项到生长槽（E_UP 邻居），产出成功同 tick 推进 outputIndex；
 * 浆果模式（甜浆果/茎作物）产完保留成熟、下轮重新评估；标准模式回到 age=0。</p>
 */
public class LivingFarmlandFunction implements LivingItemFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingFarmland");

    public static final String ID = "living_farmland";

    /** 生长/产出冷却周期（200 ticks ≈ 10 秒），模拟原版随机刻节奏 */
    public static final int GROWTH_INTERVAL_TICKS = 200;

    @Override
    public boolean canApply(ItemStack stack) {
        if (!LivingItemManager.isLivingItem(stack)) return false;
        return stack.is(Items.FARMLAND);
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        int size = context.getSize();
        int width = context.getWidth();
        long now = level.getGameTime();

        for (SlotEntry entry : entries) {
            processFarmland(entry.slotIndex(), entry.stack(), context, tick, serverLevel, size, width, now);
        }
    }

    private void processFarmland(int slot, ItemStack farmland, ContainerContext ctx, TickContext tick,
                                 ServerLevel level, int size, int width, long now) {
        FarmlandPlantComponent plant = LivingItemManager.getFarmlandPlant(farmland);
        if (!plant.isPlanted()) return;

        Block cropBlock = CropClassifier.getBlockFromSeed(plant.cropSeed());
        // 种子指向的方块不可解析（模组卸载）→ 静默跳过，保留数据等待方块回归
        if (cropBlock == null) return;

        int growthSlot = ContainerContext.resolveNeighbor(slot, ContainerContext.E_UP, size, width);

        boolean isMature = plant.isMature();
        // 规则②：BLOCKED 仅当「生长槽存在且被物品占用」（防把生长槽当储存格）。
        // 顶行/边缘耕地（growthSlot<0）正常生长，成熟后产出在 tryOutputLoot 的
        // growthSlot<0 守卫处挂起——旧实现把「无生长槽」误判为永久 BLOCKED
        // 导致顶行 age 恒 0 永不生长（2026-09-13 游戏实测踩坑）。
        // 成熟期产出物占据生长槽是系统行为，不触发（规则⑥）。
        boolean slotBlocked = growthSlot >= 0 && !ctx.getItem(growthSlot).isEmpty();

        if (!isMature && slotBlocked) {
            if (plant.age() != 0) {
                updatePlant(ctx, slot, farmland, plant.withAge(0));
            }
            return;
        }
        // BLOCKED → 槽变空后此分支不再命中，age 从 0 自然继续生长（规则③）

        // 冷却周期未到期 → 零写入直接返回（稳态）
        if (now - plant.lastGrowthAttemptTick() < GROWTH_INTERVAL_TICKS) return;
        plant = plant.withLastGrowthAttemptTick(now);

        if (isMature) {
            plant = tryOutputLoot(ctx, level, slot, growthSlot, farmland, plant, cropBlock);
        } else {
            plant = tryGrow(ctx, tick, level, slot, plant, size, width);
        }

        // 挂起态（顶行成熟）等场景下组件可能未变——equals 守卫防无意义写入+同步
        updatePlant(ctx, slot, farmland, plant);
    }

    /** GROWING：湿润概率判定，成功涨一级 */
    private FarmlandPlantComponent tryGrow(ContainerContext ctx, TickContext tick, ServerLevel level, int slot,
                                          FarmlandPlantComponent plant, int size, int width) {
        float f = isWet(ctx, tick, slot, size, width) ? 3.0F : 1.0F;
        if (level.random.nextInt((int) (25.0F / f) + 1) == 0) {
            return plant.withAge(Math.min(plant.age() + 1, plant.maxAge()));
        }
        return plant;
    }

    /**
     * MATURE：冻结/产出/推进/重置。
     * <p>茎作物产出来源是果实方块的战利品表（stem.fruit，AT 读取），非茎作物用自身。</p>
     */
    private FarmlandPlantComponent tryOutputLoot(ContainerContext ctx, ServerLevel level, int slot,
                                                 int growthSlot, ItemStack farmland,
                                                 FarmlandPlantComponent plant, Block cropBlock) {
        // Step 0：首次成熟 → 评估完整战利品表并冻结（骨粉催熟即时冻结也走这里）
        FarmlandPlantComponent frozen = tryFreezeDrops(plant, cropBlock, level);
        if (frozen != plant) {
            // 空战利品表场景冻结助手内部直接按模式重置；正常场景返回冻结后的组件
            return frozen;
        }

        // Step 1：产出当前项到生长槽
        if (growthSlot < 0) {
            // 顶行/边缘耕地无生长槽：保留成熟态+冻结的 pendingDrops 挂起，
            // 搬到有生长槽的位置后下一个冷却周期自动开始产出
            return plant;
        }

        int i = plant.outputIndex();
        List<ItemStack> drops = plant.pendingDrops();
        if (i < 0 || i >= drops.size()) {
            // 索引越界（数据异常）→ 按模式重置自愈
            return resetByMode(plant.withOutput(-1, List.of()), cropBlock);
        }

        ItemStack drop = drops.get(i);
        if (drop.isEmpty()) {
            // 空产出项 → 跳过推进到下一项
            plant = plant.withOutput(i + 1, drops);
            return finishIfDone(plant, cropBlock);
        }

        int farmlandCount = farmland.getCount();
        int outputCount = Math.min(farmlandCount * drop.getCount(), drop.getMaxStackSize());
        if (outputCount <= 0) return plant;

        ItemStack output = drop.copyWithCount(outputCount);
        ItemStack grown = ctx.getItem(growthSlot);

        boolean placed = false;
        if (grown.isEmpty()) {
            ctx.setItem(growthSlot, output);
            placed = true;
        } else if (ItemStack.isSameItemSameComponents(grown, output)) {
            int space = grown.getMaxStackSize() - grown.getCount();
            if (space > 0) {
                int toAdd = Math.min(outputCount, space);
                ItemStack merged = grown.copyWithCount(grown.getCount() + toAdd);
                ctx.setItem(growthSlot, merged);
                placed = true;
            }
        }

        if (!placed) return plant;   // 不同种占据/同种已满 → 本轮跳过，保留索引等下周期重试

        // Step 2：产出成功 → 同 tick 推进；全部产完 → 按模式重置
        plant = plant.withOutput(i + 1, drops);
        return finishIfDone(plant, cropBlock);
    }

    private FarmlandPlantComponent finishIfDone(FarmlandPlantComponent plant, Block cropBlock) {
        if (plant.outputIndex() >= plant.pendingDrops().size()) {
            return resetByMode(plant.withOutput(-1, List.of()), cropBlock);
        }
        return plant;
    }

    /**
     * 首次成熟的战利品表评估冻结（round-robin 数据源，每轮一次掷骰）。
     *
     * <p>公开静态：骨粉催熟到 maxAge 时在交互线程内即时调用，不等下一个冷却周期
     * （否则玩家催熟后要空转 ≤10 秒才见产出）。茎作物产出来源是果实方块
     * （stem.fruit，AT 读取），非茎作物用自身。</p>
     *
     * @return 冻结后的组件；已冻结（非首次）返回原实例；产出来源不可解析也返回原实例
     *         （下个冷却周期重试）；战利品表为空时直接按模式重置的组件。
     */
    public static FarmlandPlantComponent tryFreezeDrops(FarmlandPlantComponent plant, Block cropBlock,
                                                        ServerLevel level) {
        if (FarmlandPlantComponent.hasPendingOutput(plant)) return plant;   // 已冻结

        Block dropBlock = cropBlock instanceof StemBlock stem
            ? CropClassifier.getStemFruit(stem)
            : cropBlock;
        if (dropBlock == null) {
            LOGGER.warn("活耕地产出失败：作物 {} 的产出来源方块不可解析，跳过本轮",
                BuiltInRegistriesBlockKey(cropBlock));
            return plant;
        }
        BlockState matureState = matureStateFor(dropBlock);
        if (matureState == null) {
            LOGGER.warn("活耕地产出失败：作物 {} 无法构造成熟态 BlockState", BuiltInRegistriesBlockKey(cropBlock));
            return plant;
        }
        // 公开静态重载内部自动补齐 BLOCK_STATE/ORIGIN/TOOL 必填参数并走 BLOCK 参数集验证
        List<ItemStack> drops = Block.getDrops(matureState, level, net.minecraft.core.BlockPos.ZERO, null);
        if (drops.isEmpty()) {
            LOGGER.debug("活耕地战利品表为空（作物 {}），直接按模式重置", BuiltInRegistriesBlockKey(cropBlock));
            if (CropClassifier.isBerryModeCrop(cropBlock)) return plant;   // 浆果：保留成熟等下轮
            return plant.withAge(0).withOutput(-1, List.of());              // 标准：回 GROWING
        }
        return plant.withOutput(0, drops);
    }

    /** 产完重置：标准模式回 GROWING；浆果模式保留 MATURE（下轮重新评估战利品表） */
    private FarmlandPlantComponent resetByMode(FarmlandPlantComponent plant, Block cropBlock) {
        if (CropClassifier.isBerryModeCrop(cropBlock)) {
            return plant;   // 保留 age = maxAge
        }
        return plant.withAge(0);
    }

    /** 构造成熟态 BlockState（age = maxAge），值域越界返回 null */
    private static BlockState matureStateFor(Block block) {
        IntegerProperty ageProp = agePropertyOf(block);
        if (ageProp == null) return null;
        int maxAge = CropClassifier.getMaxAge(block);
        if (!ageProp.getPossibleValues().contains(maxAge)) return null;
        return block.defaultBlockState().setValue(ageProp, maxAge);
    }

    /**
     * 取作物的 AGE 属性。CropBlock.getAgeProperty() 是 protected——
     * 从 defaultBlockState 的属性表按名字取（属性名就是 "age"），
     * 覆盖 CropBlock/NetherWart/SweetBerry/Stem 全部作物类型。
     */
    @Nullable
    private static IntegerProperty agePropertyOf(Block block) {
        for (var prop : block.defaultBlockState().getProperties()) {
            if (prop instanceof IntegerProperty intProp && prop.getName().equals("age")) {
                return intProp;
            }
        }
        return null;
    }

    /** 湿润检测：左/右/下邻居槽位有活水流（TickContext.fluidData 由容器级数据先行算好） */
    private boolean isWet(ContainerContext ctx, TickContext tick, int slot, int size, int width) {
        if (tick.fluidData == null || tick.fluidData.isEmpty()) return false;
        for (int dir : new int[]{ContainerContext.E_LEFT, ContainerContext.E_RIGHT, ContainerContext.E_DOWN}) {
            int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
            if (neighbor >= 0 && tick.fluidData.getFlows().containsKey(neighbor)) {
                return true;
            }
        }
        return false;
    }

    private void updatePlant(ContainerContext ctx, int slot, ItemStack stack, FarmlandPlantComponent plant) {
        // equals 守卫：挂起态（顶行成熟）等场景组件可能未变，不写不同步
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
    private static String BuiltInRegistriesBlockKey(Block block) {
        return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block));
    }
}
