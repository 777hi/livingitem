package com.qiqi.li.living.domain.water;

import java.util.List;
import java.util.Set;

import java.util.Set;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.SimpleFluidContent;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.transfer.LivingComponents;

/**
 * 活桶（流体侧批次二，2026-10-03；批次三.5 改为换宿主模型）——
 * <b>内容组件（{@code LIVING_BUCKET_FLUID}，FluidStack）是权威数据，宿主物品跟随内容变换</b>：
 * 空 = {@code Items.BUCKET}、装水 = {@code Items.WATER_BUCKET}、装岩浆 = {@code Items.LAVA_BUCKET}
 * （原版桶心智：倒水后变空桶、吸水后变水桶，看得见摸得着；模组流体暂留 BUCKET 宿主 + tooltip）。
 * 组件（活标记等）在换宿主时全量保留。另有<b>宿主隐含内容</b>：活化的水桶/岩浆桶（无组件）
 * 直接管作满桶 —— 兼容旧活水桶与「活化水桶直取」路径。
 *
 * <p><b>交互型功能（无 tick 逻辑）</b>，类比活打火石：</p>
 * <ul>
 *   <li><b>倒水</b>：光标手持满桶右键空槽位 → 该格诞生派生源（无源时）+ 桶排空一桶；
 *       已有源的格「源不变」只排空（原版语义：往水源里倒水，水还是水）。</li>
 *   <li><b>汲水</b>：光标手持空桶右键源格（level 0）→ 源消失（须是派生源）+ 桶灌入一桶。
 *       桶源退役后所有源都是派生源，<b>一切源可汲</b>。</li>
 * </ul>
 *
 * <p>客户端拦截在 {@code GuiInteractionHelper.tryInteract} 的活桶分支：目标条件是
 * 「空槽位 + 容器级源状态」，物品中心的交互规则系统表达不了（matchesTarget 对空槽恒 false），
 * 由客户端按流体快照缓存精确判定（不命中不拦截，原版操作不受影响），
 * 服务端经 {@code GuiInteractionPacket} 管道由 {@link LivingBucketInteractHandlers} 权威重验执行。</p>
 *
 * <p>⚠️ 桶源已退役（idea.md §〇.5）：旧「活水桶（WATER_BUCKET 宿主）」不再注册任何源，
 * 现存活水桶成为惰性物品（alpha 不做旧存档兼容）。水流语义全部归活水源（派生源）。</p>
 */
public class LivingBucketFunction implements LivingItemFunction {

    public static final String ID = "living_bucket";

    @Override
    public boolean canApply(ItemStack stack) {
        return isBucketFamily(stack) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    /** 交互型功能：无 tick 逻辑（内容组件随物品走，容器级源归 ContainerFluidData 管）。 */
    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        // no-op
    }

    @Override
    public Set<DataComponentType<?>> getOwnedComponentTypes() {
        return Set.of(LivingComponents.LIVING_BUCKET_FLUID.value());
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             java.util.function.Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        SimpleFluidContent content = getContent(stack);
        if (content.isEmpty()) return;
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.bucket.content",
            content.copy().getHoverName(), content.getAmount()));
    }

    // ── 静态读写（客户端判定 / 服务端处理器共用）────────────────

    /** 活桶宿主家族：空桶 / 水桶 / 岩浆桶（原版桶家族）。 */
    private static boolean isBucketFamily(ItemStack stack) {
        return stack.is(Items.BUCKET) || stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET);
    }

    public static boolean isLivingBucket(ItemStack stack) {
        return isBucketFamily(stack) && LivingItemManager.isLivingItem(stack);
    }

    /**
     * 读取桶内容；缺失时按<b>宿主隐含内容</b>推导（水桶 = 满水、岩浆桶 = 满岩浆）——
     * 活化水桶直取、旧存档活水桶均视为满桶。空桶无组件 = 空。
     */
    public static SimpleFluidContent getContent(ItemStack stack) {
        SimpleFluidContent content = LivingItemManager.getData(stack, LivingComponents.LIVING_BUCKET_FLUID.value(),
            SimpleFluidContent.EMPTY);
        if (!content.isEmpty()) return content;
        if (stack.is(Items.WATER_BUCKET)) {
            return SimpleFluidContent.copyOf(new net.neoforged.neoforge.fluids.FluidStack(Fluids.WATER, FluidType.BUCKET_VOLUME));
        }
        if (stack.is(Items.LAVA_BUCKET)) {
            return SimpleFluidContent.copyOf(new net.neoforged.neoforge.fluids.FluidStack(Fluids.LAVA, FluidType.BUCKET_VOLUME));
        }
        return SimpleFluidContent.EMPTY;
    }

    /**
     * 写入桶内容并<b>同步宿主物品</b>（换宿主模型的核心）：空 → 空桶、装水 → 水桶、
     * 装岩浆 → 岩浆桶；组件全量保留（活标记等）。宿主不变时原地改组件（零新对象）。
     *
     * <p>⚠️ 换宿主会<b>产生新 ItemStack</b> —— 调用方必须把返回值放回原位置
     * （{@code menu.setCarried} / {@code player.setItemInHand} / 槽位写入）。
     * 数量超过目标宿主最大堆叠时保持原宿主（数量优先，内容组件照写）。</p>
     *
     * @return 可能是新实例（换宿主）或原实例（未换宿主）
     */
    public static ItemStack withContent(ItemStack current, SimpleFluidContent content) {
        Item targetHost = hostFor(content, current.getCount());
        ItemStack out = current;
        if (targetHost != current.getItem()) {
            out = new ItemStack(targetHost, current.getCount());
            out.applyComponents(current.getComponents());   // 活标记等组件全量保留
        }
        LivingItemManager.setData(out, LivingComponents.LIVING_BUCKET_FLUID.value(), content,
            SimpleFluidContent.EMPTY);
        return out;
    }

    /** 内容 → 宿主物品映射（数量超限回退 BUCKET 宿主）。 */
    private static Item hostFor(SimpleFluidContent content, int count) {
        if (content.isEmpty()) return Items.BUCKET;
        Item byFluid;
        if (content.is(Fluids.WATER)) byFluid = Items.WATER_BUCKET;
        else if (content.is(Fluids.LAVA)) byFluid = Items.LAVA_BUCKET;
        else byFluid = Items.BUCKET;   // 模组流体：暂留空桶宿主，内容组件 + tooltip 表达
        if (count > new ItemStack(byFluid).getMaxStackSize()) return Items.BUCKET;
        return byFluid;
    }

    /** 是否装着至少一整桶。 */
    public static boolean hasFullBucket(ItemStack stack) {
        SimpleFluidContent content = getContent(stack);
        return !content.isEmpty() && content.getAmount() >= FluidType.BUCKET_VOLUME;
    }

    /** 是否为空桶。 */
    public static boolean isEmptyBucket(ItemStack stack) {
        return getContent(stack).isEmpty();
    }
}
