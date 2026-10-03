package com.qiqi.li.living.domain.water;

import java.util.List;
import java.util.Set;

import java.util.Set;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
 * 活桶（流体侧批次二，2026-10-03）—— 与 NeoForge 桶同构：<b>同一物品（{@code Items.BUCKET}）
 * + {@code LIVING_BUCKET_FLUID} 内容组件</b>。满 = 活水桶，空 = 活空桶，倒/汲只是内容的增减，
 * 活标记与宿主数据全程保留（不再「换物品」）。
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
 * 服务端经 {@code GuiInteractionPacket} 管道由 {@link LivingBucketPourHandler} /
 * {@link LivingBucketScoopHandler} 权威重验执行。</p>
 *
 * <p>⚠️ 桶源已退役（idea.md §〇.5）：旧「活水桶（WATER_BUCKET 宿主）」不再注册任何源，
 * 现存活水桶成为惰性物品（alpha 不做旧存档兼容）。水流语义全部归活水源（派生源）。</p>
 */
public class LivingBucketFunction implements LivingItemFunction {

    public static final String ID = "living_bucket";

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.BUCKET) && LivingItemManager.isLivingItem(stack);
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

    public static boolean isLivingBucket(ItemStack stack) {
        return stack.is(Items.BUCKET) && LivingItemManager.isLivingItem(stack);
    }

    /** 读取桶内容；缺失返回 {@link SimpleFluidContent#EMPTY}。 */
    public static SimpleFluidContent getContent(ItemStack stack) {
        return LivingItemManager.getData(stack, LivingComponents.LIVING_BUCKET_FLUID.value(),
            SimpleFluidContent.EMPTY);
    }

    /** 写入桶内容（等于空时移除组件，保持物品干净）。 */
    public static void setContent(ItemStack stack, SimpleFluidContent content) {
        LivingItemManager.setData(stack, LivingComponents.LIVING_BUCKET_FLUID.value(), content,
            SimpleFluidContent.EMPTY);
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
