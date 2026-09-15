package com.qiqi.li.living.domain.farmland;

import javax.annotation.Nullable;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 活耕地「放置回世界」——放置已种植的活耕地时，把物品自带的作物种到耕地之上。
 *
 * <h3>做法：模拟玩家右键，而不是自己塞方块</h3>
 * <p>本类<b>不</b>自行推导作物方块、也<b>不</b>直接 {@code setBlock}——那样会把原版与模组
 * 自己的种植校验（{@code canSurvive}、模组覆写的 {@code useOn}，例如「水稻只能在水下种」）
 * 全部绕过，种出非法状态。改为构造 {@link UseOnContext} 调 {@link ItemStack#useOn}，
 * 让种植的成败完全由原版/模组决定。</p>
 *
 * <p>命中面取耕地顶面 ⇒ 原版 {@code BlockPlaceContext} 会把落点算到
 * {@code farmlandPos.above()}，无需自行计算。</p>
 *
 * <h3>这是「软逻辑」</h3>
 * <p>种植属锦上添花，<b>不是必要功能</b>：能种上就好，种不上（条件不满足、模组拒绝、
 * 甚至抛异常）都绝不能影响原版放置流程。异常若冒泡出 {@code BlockItem.place} 会造成
 * 「方块已放、物品未扣」并炸掉 tick，故整段兜在 try/catch 内。</p>
 *
 * @see com.qiqi.li.living.mixin.BlockItemMixin 调用点
 */
public final class LivingFarmlandPlacement {

    private static final Logger LOGGER = LogUtils.getLogger();

    private LivingFarmlandPlacement() {}

    /**
     * 唯一触发条件：活耕地 + 已种植。
     *
     * <p>{@code is(FARMLAND)} 放最前——物品引用比较、纳秒级，短路掉游戏里绝大多数方块放置，
     * 不会去碰 DataComponent 查询（同 {@code SlotInteractions.isEligibleCargo} 的短路口径）。</p>
     */
    public static boolean isPlantable(ItemStack stack) {
        return stack.is(Items.FARMLAND)
            && LivingItemManager.isLivingItem(stack)
            && LivingItemManager.getFarmlandPlant(stack).isPlanted();
    }

    /**
     * 放置成功后调用：模拟「玩家拿种子右键这块耕地」种一次。
     *
     * <p><b>递归安全</b>：{@link ItemStack#useOn} 内部会再次触发 {@code BlockItem.place}
     * 从而再次命中 {@code BlockItemMixin}，但此时手里是<b>种子</b>栈，
     * {@link #isPlantable} 为 false（不是 FARMLAND）⇒ 立即返回，不会递归。</p>
     *
     * @param farmlandPos 耕地所在格（{@code BlockItem.place} 落的那个位置）
     * @param player      放置者，可为 null（原版 {@code UseOnContext} 允许）
     */
    public static void onPlaced(Level level, BlockPos farmlandPos, ItemStack farmlandStack,
                                @Nullable Player player) {
        // 用方法式而非字段式（level.isClientSide 是 Level 上的 public final 字段）：
        // 语义等价，但字段不可被 mock —— 方法式才能单测钉住这条守卫（同 EnderRouteManager）
        if (level.isClientSide()) return;               // 客户端也跑 place，只在服务端生效
        if (!isPlantable(farmlandStack)) return;
        try {
            FarmlandPlantComponent plant = LivingItemManager.getFarmlandPlant(farmlandStack);
            // cropSeed 非空由 isPlantable() 的 isPlanted() 保证
            ItemStack seed = new ItemStack(plant.cropSeed());
            BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(farmlandPos), Direction.UP, farmlandPos, false);
            seed.useOn(new UseOnContext(level, player, InteractionHand.MAIN_HAND, seed, hit));

            // 已冻结但还没搬进生长槽的战利品：掉到地上，不凭空消失
            for (ItemStack drop : plant.pendingDrops()) {
                if (!drop.isEmpty()) Block.popResource(level, farmlandPos.above(), drop);
            }
        } catch (Exception e) {
            // 软逻辑：种不上/掉不出都不该影响原版放置流程
            LOGGER.warn("[LivingItem] 放置活耕地时自动种植失败（软逻辑，已忽略）", e);
        }
    }
}
