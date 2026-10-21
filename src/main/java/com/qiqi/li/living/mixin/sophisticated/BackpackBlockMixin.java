package com.qiqi.li.living.mixin.sophisticated;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.qiqi.li.living.container.ContainerHandoff;
import com.qiqi.li.logging.ModLog;

/**
 * 精妙背包「<b>蹲下右键拾取</b>」的容器级数据交接（2026-10-21）。
 *
 * <h3>为什么需要这个 Mixin</h3>
 * <p>精妙的 {@code putInPlayersHandAndRemove} 直接把背包塞进玩家手里，然后
 * {@code level.removeBlock(pos, false)} —— <b>不产生掉落物、不走破坏流程</b> ⇒
 * {@code BlockDropsEvent} 与 {@code EntityPlaceEvent} 都不触发 ⇒
 * 我们挂在 BE 附件上的流体 / 相位随方块一起蒸发。</p>
 *
 * <p>这是「第三方自己搬走容器、两个事件都不给」的第一例。修法不是给核心加特例，而是
 * 在<b>联动模块</b>里补上第三个挂点（{@link ContainerHandoff}）：</p>
 * <ul>
 *   <li>{@code HEAD}：方块还在、附件还活着 ⇒ <b>捕获宿主引用</b>（不复制数据）；</li>
 *   <li>{@code RETURN}：物品已在玩家手里 ⇒ <b>把数据搬过去</b>（各领域自己那份 {@code moveOut}）。</li>
 * </ul>
 *
 * <h3>覆盖范围</h3>
 * <p>精妙有两条路进入这个方法（{@code BackpackBlock#useWithoutItem} 的蹲下分支、
 * {@code BackpackBlock#playerInteract} 的副手分支），<b>两条都汇聚到这一个方法</b>
 * ⇒ 注入一处即可全覆盖。</p>
 *
 * <h3>⚠️ 失效方向</h3>
 * <p>{@code require = 0} + 方法名字符串 ⇒ 精妙改版改名时<b>不会崩服</b>，只是退回
 * 「数据随方块消失」（= 修复前的现状，不会更糟）。失效可见性由
 * {@code SophisticatedMixinPlugin#postApply} 的 ERROR 日志兜住（I-C6「降级必须可观测」）。</p>
 *
 * <p>⚠️ 目标类用 {@code targets} 字符串而非类字面量：本模组对精妙<b>没有编译期依赖</b>
 * （只有 dev 环境的运行期 jar）⇒ 不能引用它的类型。</p>
 */
@Mixin(targets = "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlock")
public abstract class BackpackBlockMixin {

    /**
     * 捕获时机：方块还在。
     *
     * <p>只记宿主引用，<b>不读也不清</b>数据 —— 万一目标方法中途失败（没把物品给玩家），
     * 数据仍留在方块上，玩家还能正常打开 GUI 取用。</p>
     */
    @Inject(method = "putInPlayersHandAndRemove", at = @At("HEAD"), require = 0, remap = false)
    private static void livingItem$captureContainerHost(BlockState state, Level level, BlockPos pos,
                                                        Player player, InteractionHand hand, CallbackInfo ci) {
        if (level.isClientSide) return;
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
        ModLog.MIXIN.debug("[精妙联动] HEAD 捕获：pos={} be={}", pos, be);
        ContainerHandoff.capture(be);
    }

    /**
     * 交接时机：物品已进玩家手里。
     *
     * <p>此时方块已移除（{@code getBlockEntity} 返回 null），但 {@code HEAD} 捕获的宿主对象
     * 仍被 {@link ContainerHandoff} 强引用 ⇒ 附件照读不误。</p>
     */
    @Inject(method = "putInPlayersHandAndRemove", at = @At("RETURN"), require = 0, remap = false)
    private static void livingItem$handoffToHand(BlockState state, Level level, BlockPos pos,
                                                 Player player, InteractionHand hand, CallbackInfo ci) {
        if (level.isClientSide) return;
        ItemStack held = player.getItemInHand(hand);
        boolean moved = ContainerHandoff.handoff(held);
        ModLog.MIXIN.debug("[精妙联动] RETURN 交接：hand={} item={} moved={}", hand, held.getItem(), moved);
    }
}
