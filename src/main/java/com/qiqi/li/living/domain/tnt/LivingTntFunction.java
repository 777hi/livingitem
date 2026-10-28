package com.qiqi.li.living.domain.tnt;
import com.qiqi.li.living.components.LivingComponents;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.tnt.ExplosionData;
import com.qiqi.li.living.domain.tnt.LivingTntData;

/**
 * 活 TNT 的功能实现。
 *
 * <p>每 tick 检测所在槽位的红石信号：收到信号即点燃并启动引信倒计时，归零时经
 * {@link ExplosionComponent#ignite} 触发爆炸；同时维持红石账本以放行红石层的驱动守卫（容器里只有活 TNT 时也是如此）。</p>
 */
public class LivingTntFunction implements LivingItemFunction {

    public static final String ID = "living_tnt";

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.TNT) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public boolean supportsDroppedItem() {
        // 爆炸点走 ContainerAnchor.AtEntity ⇒ 炸在原地（见 ExplosionComponent#ignite）
        return true;
    }

    @Override
    public String getFunctionId() { return ID; }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) return;

        // 确保红石账本存在 —— ⚠️ **这行不是冗余代码，别删**：
        // 红石层的驱动守卫（LivingRedstoneFunction.tickContainerData）以「账本已存在」为放行判据之一，
        // 容器里只有活 TNT 时正是靠这行让守卫放行。见
        // docs/buffer/redstone-driver-consolidation-plan.md §5 改动 2。
        // （2026-10-08 计划 ⑤：getSensor 内部即「取或创建账本」，故改走端口 ——
        //  这样 TNT 不必 import redstone 领域，中继由 TickContext 承担。）
        tick.getSensor(context);
        int size = context.getSize();

        for (SlotEntry entry : entries) {
            int slot = entry.slotIndex();
            if (slot < 0 || slot >= size) continue;

            ItemStack stack = entry.stack();
            LivingTntData data = LivingTntData.of(stack);
            ExplosionData explosion = data.explosion();

            if (!explosion.ignited()) {
                int signal = tick.getSensor(context).maxSensedSignal(slot);
                if (signal > 0) {
                    explosion = explosion.ignite();
                    LivingTntData.set(stack, data.withExplosion(explosion));
                    // 与引信递减走同一条「改了就写回」规则（见下方长注释）
                    context.setItem(slot, stack);
                    context.syncSlotToClients(slot, stack);
                }
                continue;
            }

            explosion = explosion.tick();

            if (explosion.isExploded()) {
                if (ExplosionComponent.ignite(context, level)) {
                    // ⚠️ 消耗由 ignite 完成 —— 它把容器里**所有**活 TNT 槽位写成 EMPTY。
                    // 所以这里**必须直接结束**，绝不能往下走到写回：
                    //   · 写回 `stack` 会把刚清掉的槽位用本地栈填回去 ⇒ 「炸了但 TNT 还在」
                    //     （2026-10-23 真机实录）；
                    //   · 也不能只 continue 到下一个条目 —— `entries` 是 tick 前的快照，
                    //     其余条目的槽位同样已被 ignite 清空，写回会把它们一起复活。
                    return;
                }
                // 引爆不了（无可用锚点）⇒ **熄灭**：清零 ignited / fuseTimer，变回未点燃的活 TNT。
                // 不保留「待爆」态 —— 否则挪到有锚点处会不点自爆（2026-10-28 用户定）。
                // 熄灭后若所在槽位仍有红石信号，下一 tick 会按正常入口重新点火（引信重头烧）。
                if (context.getItem(slot).isEmpty()) {
                    // ignite 的另一条早退路径（计数异常）不会清空槽位；真被清空则说明物品已不在，
                    // 任何写回都会把它复活 ⇒ 直接结束。
                    return;
                }
                LivingTntData.set(stack, data.withExplosion(ExplosionData.DEFAULT));
                context.setItem(slot, stack);
                context.syncSlotToClients(slot, stack);
                continue;
            }

            LivingTntData.set(stack, data.withExplosion(explosion));

            // ⚠️ 必须**显式写回**，不能只靠「getItem 返回的栈是活引用」：
            // NBT 背书的容器（精妙背包的 handler）里就地改组件不会触发它自己的保存钩子，
            // 而本函数的修改（引信递减）是唯一的数据来源。工具类早已这么做（writeBack），
            // TNT 此前漏了（2026-10-23 普查）。
            context.setItem(slot, stack);
            context.syncSlotToClients(slot, stack);
        }
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        LivingTntData data = LivingTntData.of(stack);
        ExplosionData explosion = data.explosion();

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.tnt.status"));

        if (explosion.ignited()) {
            tooltipAdder.accept(Component.literal("TNT [Fuse: " + explosion.fuseTimer() + " ticks]")
                .withStyle(net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BOLD));
        } else {
            tooltipAdder.accept(Component.literal("TNT")
                .withStyle(net.minecraft.ChatFormatting.RED));
        }
    }

    @Override
    public Set<DataComponentType<?>> getOwnedComponentTypes() {
        return Set.of(TntComponents.LIVING_TNT_DATA.value());
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }

    public static boolean isFuseActive(ItemStack stack) {
        return LivingTntData.of(stack).explosion().ignited();
    }

    public static int getFuseTimer(ItemStack stack) {
        ExplosionData e = LivingTntData.of(stack).explosion();
        return e.ignited() ? e.fuseTimer() : -1;
    }

}