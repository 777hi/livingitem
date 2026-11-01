package com.qiqi.li.living.domain.sponge;

import com.qiqi.li.living.util.PerItemLedger;
import com.qiqi.li.living.util.RepresentativeFluids;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;

import javax.annotation.Nullable;

/**
 * 活海绵 GUI 汲的服务端支撑（设计稿 §2.3）—— 桶点海绵槽：堆总量 ≥ 一桶 ⇒ 均分扣除
 * （<b>ceil 口径</b>：每枚 −⌈10⁶/count⌉ mmB，超额 ≤ count−1 mmB 损耗向销毁 —— floor 版
 * 会让桶凭空得到差额，违反规则 12）+ 桶灌入一桶。{@code LivingBucketInteractSupport}
 * 的汲/倒同款形态：服务端<b>权威重验</b>一切前置条件，客户端判定只做拦截。
 *
 * <p>与汲水（{@code scoop}）的差别：汲水删的是容器级派生源；本路径读写的全部是
 * <b>海绵物品组件</b> —— 不碰容器流体账本，无需 {@code ContainerContexts} 反查。</p>
 */
public final class LivingSpongeInteractSupport {

    /** 一桶 = 1000 mB = 10⁶ mmB（原版熔炼「每块湿海绵 1 桶」的粒度继承，设计稿 §1）。 */
    private static final long BUCKET_MM_B = 1_000L * 1_000L;

    private LivingSpongeInteractSupport() {}

    /**
     * 汲出服务端执行：目标槽是湿活海绵且堆总量 ≥ 一桶 ⇒ 均分扣除（ceil）+ 光标桶灌入一桶。
     * 前置校验由调用方完成（光标是活空桶）；本方法重验海绵侧全部条件。
     */
    public static void drawFromSponge(ServerPlayer player, Slot slot, ItemStack carried) {
        ItemStack sponge = slot.getItem();
        LivingSpongeData data = LivingSpongeData.of(sponge);
        if (!data.isWet()) return;
        int count = sponge.getCount();
        if (data.totalMmB(count) < BUCKET_MM_B) return;   // 不足一桶 ⇒ 汲不动

        Fluid fluid = RepresentativeFluids.of(data.fluid());
        if (fluid == null || fluid.getBucket() == null || fluid.getBucket() == Items.AIR) {
            return;   // 无桶形态的流体（理论仅坏注册的模组流体）—— 保守放弃，不扣海绵
        }

        var wd = PerItemLedger.withdrawCeil(data.mmB(), count, BUCKET_MM_B);
        LivingSpongeData.set(sponge, data.withMmB(wd.newQ()));   // newQ = 0 ⇒ 干态（默认值移除）

        ItemStack filled = new ItemStack(fluid.getBucket());
        filled.applyComponents(carried.getComponents());   // 活标记随形态走（scoop 同款）
        filled.set(DataComponents.MAX_STACK_SIZE,
            new ItemStack(filled.getItem()).getMaxStackSize());   // 形态自然堆叠（scoop 同款）
        if (player.hasInfiniteMaterials()) {
            // 创造模式对齐原版：光标桶直接灌满（不复制产物）
            player.containerMenu.setCarried(filled);
            return;
        }
        // 生存：空桶栈只消耗一个空桶，装好的桶进背包（放不下掉落）—— 多个活空桶汲水不丢桶
        player.containerMenu.setCarried(ItemUtils.createFilledResult(carried, player, filled));
    }

    /** 光标是否活空桶 —— 桶载体判定与 {@code LivingBucketFunction} 同义（两行原版 API，
     *  不为它建跨域契约；活空桶门槛与 pour/scoop 一致）。 */
    static boolean isLivingEmptyBucket(ItemStack stack) {
        return stack.getItem() instanceof net.minecraft.world.item.BucketItem bucket
            && bucket.content == Fluids.EMPTY
            && com.qiqi.li.living.api.LivingItemManager.isLivingItem(stack);
    }

    /** 目标槽是否可汲（湿活海绵且堆总量 ≥ 一桶）—— 服务端重验与客户端判定共用判据。 */
    public static boolean drawable(@Nullable ItemStack sponge) {
        if (sponge == null || sponge.isEmpty()) return false;
        LivingSpongeData data = LivingSpongeData.of(sponge);
        return data.isWet() && data.totalMmB(sponge.getCount()) >= BUCKET_MM_B;
    }
}
