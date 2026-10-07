package com.qiqi.li.living.domain.water;

import java.util.List;

import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

/**
 * 活桶（2026-10-04 逻辑纠偏定稿）—— <b>桶只是载体，没有任何私有状态</b>。
 *
 * <p>「活桶」= 任意 {@link BucketItem}（原版 + 模组桶，{@code instanceof} 通吃）× 活标记
 * （IS_LIVING）。内容状态<b>就是原版的 {@code BucketItem.content}</b>（public 字段）：
 * 活化的水桶天然装水、岩浆桶天然装岩浆、空桶为空 —— 不需要内容组件、不需要隐含推导。</p>
 *
 * <p><b>形态变换 = 原版桶心智</b>（用户拍板）：汲入流体 X → {@code X.getBucket()}
 * （每个流体的注册桶物品）；排空 → {@code Items.BUCKET}。组件（活标记）在变换时全量保留。
 * 本类的逻辑量极少 —— <b>重量全在容器级活流体上</b>（{@link ContainerFluidData}）。</p>
 *
 * <p>汲/倒调用方：{@code LivingBucketInteractHandlers}（GUI，走 GuiInteractionPacket 管道）。
 * <b>世界侧零拦截</b>（2026-10-04 定稿）：活桶在世界里就是普通桶（原版取/放水原生行为，
 * swap 会丢活标记——丢了就再点活按钮，不做兜底）；priming = 活化水桶直取。</p>
 *
 * <p><b>无 tooltip（2026-10-07 定稿）</b>：桶不覆写 {@code addToTooltip} ——
 * <b>形态本身就是信息</b>（{@code X.getBucket()} / {@code Items.BUCKET}，见 {@link #withFluid}），
 * tooltip 再写一遍内容是冗余；「储存 × mB」对桶（恒1 个源 = 1000 mB）也是废话。
 * 连带删除 lang 键 {@code tooltip.livingitem.bucket.content}
 * （那条文案自 {@code e2d7aa1} FluidStack 时代起就与本类参数不匹配 ⇒ 占位符漏出，
 * 根因与来历见 {@code docs/archive/living-bucket-tooltip-removal.md}）。</p>
 */
public class LivingBucketFunction implements LivingItemFunction {

    public static final String ID = "living_bucket";

    @Override
    public boolean canApply(ItemStack stack) {
        return isLivingBucket(stack);
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    /** 交互型功能：无 tick 逻辑。 */
    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        // no-op
    }

    // ── 静态判定与变换（桶 = 载体，逻辑就这几行）────────────────

    public static boolean isLivingBucket(ItemStack stack) {
        return stack.getItem() instanceof BucketItem && LivingItemManager.isLivingItem(stack);
    }

    /** 桶内容流体（原版 {@code BucketItem.content}，public 字段）：空桶返回 {@link Fluids#EMPTY}。 */
    public static Fluid getBucketFluid(ItemStack stack) {
        return stack.getItem() instanceof BucketItem bucket ? bucket.content : Fluids.EMPTY;
    }

    /** 是否装着流体（满桶）。 */
    public static boolean hasFullBucket(ItemStack stack) {
        return getBucketFluid(stack) != Fluids.EMPTY;
    }

    /** 是否空桶。 */
    public static boolean isEmptyBucket(ItemStack stack) {
        return getBucketFluid(stack) == Fluids.EMPTY;
    }

    /**
     * 形态变换（唯一入口）：汲入流体 X → {@code X.getBucket()}（活水桶/活岩浆桶/模组桶），
     * 排空 → {@code Items.BUCKET}。组件（活标记等）全量保留。
     *
     * <p>⚠️ 换形态会产生<b>新 ItemStack</b> —— 调用方必须把返回值放回原位置
     * （{@code menu.setCarried} / {@code player.setItemInHand} / 槽位写入）。
     * 形态不变时返回原实例（零新对象）。</p>
     *
     * @param fluid 目标流体；{@link Fluids#EMPTY} = 排空
     */
    public static ItemStack withFluid(ItemStack current, Fluid fluid) {
        Item target = fluid == Fluids.EMPTY ? Items.BUCKET : fluid.getBucket();
        if (target == current.getItem()) {
            return current;   // 形态不变：零新对象
        }
        ItemStack out = new ItemStack(target, Math.min(current.getCount(), new ItemStack(target).getMaxStackSize()));
        out.applyComponents(current.getComponents());   // 活标记等组件全量保留
        // ⚠️ applyComponents 会连**源物品的默认组件**一起复制（1.21 组件系统：stacksTo
        //    注册为默认组件，水桶的 max_stack_size=1 随 map 上身）。
        //    ⚠️ 不能 remove()：移除后 getOrDefault(MAX_STACK_SIZE, 1) 落到**硬编码兜底 1**
        //    （Item.java:120），而非物品自身的 16 —— 必须**显式设置目标形态的自然堆叠值**。
        out.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE,
            new ItemStack(target).getMaxStackSize());
        return out;
    }
}
