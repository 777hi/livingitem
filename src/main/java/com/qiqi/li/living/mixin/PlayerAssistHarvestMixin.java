package com.qiqi.li.living.mixin;

import com.qiqi.li.living.domain.tools.LivingToolAssist;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 辅助模式：把「背包里的活工具」接进 {@code Player#hasCorrectToolForDrops(BlockState)}
 * 这个<b>直读入口</b>。
 *
 * <h3>为什么需要（2026-10-10 传送石碑不掉落）</h3>
 * {@link LivingToolAssist} 原有的三条腿全挂在 <b>NeoForge 事件</b>上
 * （加速 {@code BreakSpeed} / 材质门槛 {@code HarvestCheck} / 掉落重算 {@code BlockDropsEvent}）。
 * 事件能覆盖「原版路径」—— 原版 {@code canHarvestBlock} 会走
 * {@code EventHooks.doPlayerHarvestCheck} 把事件发出来。
 *
 * <p>但模组可能<b>直读</b>这个 1 参数重载：它被 NeoForge 标了
 * {@code @Deprecated // Neo: use position sensitive version below}，
 * <b>不发任何事件</b> ⇒ 钩子挂不上。实例：
 * {@code WaystoneBlockBase#playerWillDestroy} 拿它当掉落闸门 ——
 * 空手玩家（辅助模式）被判 false ⇒ 手动掉落整段跳过 ⇒ <b>石碑消失但零掉落</b>。</p>
 *
 * <p>⚠️ <b>主动模式不受影响、也不需要本 mixin</b>：破坏者是
 * {@code LivingToolFakePlayer}，活工具经 {@code equipTool} 装进<b>假玩家主手</b>
 * ⇒ 该重载自然为 true（这正是"主动模式能掉、辅助模式不掉"的唯一差别）。</p>
 *
 * <h3>为什么不改 {@code getMainHandItem}</h3>
 * 那是"玩家看起来拿着什么"，改它会污染所有读主手的代码（渲染 / 其他模组 / 拾取）。
 * 这里只回答"算不算有正确工具"这一个问题，作用面最小。
 *
 * <h3>为什么不影响挖掘速度</h3>
 * {@code BlockBehaviour#getDestroyProgress} 里的 30 / 100 走的是
 * {@code EventHooks.doPlayerHarvestCheck}（<b>发事件</b>），那条早已由
 * {@link LivingToolAssist#onHarvestCheck} 放行 ⇒ 本 mixin 一个 tick 都不影响速度。
 *
 * <p>判据本体在 {@link LivingToolAssist#hasBackpackToolFor} —— 与事件入口<b>同源</b>，
 * 故「什么算辅助成员」的口径全项目只有一处
 * （{@code LivingToolRecorder#isAssistTool}：无记忆的活工具）。</p>
 */
@Mixin(Player.class)
public abstract class PlayerAssistHarvestMixin {

    /**
     * 原版判"没有正确工具"时，再问一次辅助层；背包里有活工具挖得动 ⇒ 改判为 true。
     *
     * <p>⚠️ 用 {@code @At("RETURN")} 而不是 {@code HEAD}：只有原版判 false 时才多花一次
     * 背包扫描 —— 玩家自己拿着正确工具（绝大多数情况）直接短路返回，零额外开销。</p>
     */
    @Inject(
        method = "hasCorrectToolForDrops(Lnet/minecraft/world/level/block/state/BlockState;)Z",
        at = @At("RETURN"),
        cancellable = true)
    private void livingitem$assistHarvest(BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return;   // 原版已通过（手上有正确工具 / 方块不要求工具）⇒ 不插手
        }
        if (LivingToolAssist.hasBackpackToolFor((Player) (Object) this, state)) {
            cir.setReturnValue(true);
        }
    }
}
