package com.qiqi.li.living.domain.battle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.battle.entity.BridgeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * 战局编排（HasContainerData 第 6 个实现者，prio 5）—— 自维持、唯一驱动点。
 *
 * <p>tick 链：廉价 no-op 门（无战局数据立即 return）→ 就绪门（竞技场区块未加载 =
 * 战局暂停）→ reconcile（槽位 ↔ 桥名册）→ 快照+事件 flush。</p>
 *
 * <p>⚠️ 自维持函数对<b>每个被处理的容器</b>每 tick 调用 ⇒ 首行必须是廉价门
 * （活流体 {@code fluidData.isEmpty()} 同款）。</p>
 */
public class LivingBattleFunction implements LivingItemFunction, HasContainerData {

    public static final String ID = "living_battle";

    /** 快照下发周期（tick）。 */
    private static final int FLUSH_INTERVAL = 4;

    @Override
    public boolean canApply(ItemStack stack) {
        return false;
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    /**
     * 两阶段自维持：注册期（ctx == null）声明候选；每拍回答「此容器是否有战局」。
     * 只 peek 不创建。
     */
    @Override
    public boolean shouldTickWithoutOwnItems(ContainerContext ctx) {
        if (ctx == null) return true;
        BattleArenaData data = ContainerLivingItemHandler.peekExistingContainerData(ctx, BattleArenaData.KEY);
        return data != null && !data.isEmpty();
    }

    /** 容器级数据顺序：5 = 最后（不依赖其它容器级数据的独立编排）。 */
    @Override
    public int getPriority() {
        return 5;
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext container, TickContext tick, Level level) {
        // no-op：纯容器级逻辑，只走自维持通道
    }

    @Override
    public void tickContainerData(List<SlotEntry> entries, ContainerContext ctx, TickContext tick) {
        BattleArenaData data = tick.data(BattleArenaData.KEY);
        if (data == null || data.isEmpty()) return;   // 廉价 no-op 门
        if (!(ctx.getLevel() instanceof ServerLevel arena)) return;
        if (!ArenaPlots.isEntityTicking(arena, data.plotPos(), data.lanes(), data.depth())) {
            return;   // 就绪门：竞技场卸载 = 战局暂停（spike 不做异步拉起）
        }
        drainDamage(arena, data, ctx);   // 回向：伤害结算（掉层 / 棋子被吃光）
        reconcile(arena, data, ctx);     // 桥随槽位事实重建 + 层数同步
        checkDefeat(arena, data, ctx);   // 防线全灭 ⇒ 战败（MVP 轻惩罚）
        if (arena.getGameTime() % FLUSH_INTERVAL == 0) {
            BattleServerSync.flush(ctx, data);
        }
    }

    /**
     * 回向：结算账本伤害 → 组件累加 / 掉层（数量原语只减）/ 吃光消失。
     * 伤害跨拍聚合在 {@link BattleDamageLedger}，同拍多源同桥只结算一次。
     */
    private static void drainDamage(ServerLevel arena, BattleArenaData data, ContainerContext ctx) {
        for (Map.Entry<Integer, Float> entry : BattleDamageLedger.drain(data.battleKey()).entrySet()) {
            int slot = entry.getKey();
            ItemStack stack = ctx.getItem(slot);
            if (stack.isEmpty() || !LivingItemManager.isLivingItem(stack)) continue;
            int perPiece = BattleMath.perPiece(stack);
            var outcome = BattleMath.applyDamage(perPiece, stack.getCount(),
                BattleComponents.BattleDamage.of(stack), Math.round(entry.getValue()));
            if (outcome.destroyed()) {
                ctx.consume(slot, stack.getCount());   // 棋子被吃光：物品真实消失（§4.2）
                discardBridge(arena, data, slot);
                data.bridgeBySlot().remove(slot);
                BattleEventFeed.offer(data.battleKey(),
                    new BattleEvent(BattleEvent.Type.PIECE_LOST, slot));
                breakParticles(arena, data, ctx, slot, stack);
                continue;
            }
            if (outcome.layersLost() > 0) {
                ctx.consume(slot, outcome.layersLost());   // 掉层：count−1，皮/hitbox 由 reconcile 同步
                stack = ctx.getItem(slot);
                if (stack.isEmpty()) continue;
            }
            if (outcome.newLayerDamage() != BattleComponents.BattleDamage.of(stack)) {
                ItemStack after = stack.copy();
                after.set(BattleComponents.BATTLE_DAMAGE.get(),
                    new BattleComponents.BattleDamage(outcome.newLayerDamage()));
                ctx.commitItemComponents(slot, stack, after);
            }
        }
    }

    /** 棋子死亡演出：方块类用原版破坏粒子+音效；非方块免（GUI 红闪兜底）。 */
    private static void breakParticles(ServerLevel arena, BattleArenaData data,
                                       ContainerContext ctx, int slot, ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return;
        int width = Math.max(1, ctx.getWidth());
        Vec3 center = ArenaPlots.cellCenter(data.plotPos(), rowOf(slot, width), colOf(slot, width));
        arena.levelEvent(2001, BlockPos.containing(center),
            Block.getId(blockItem.getBlock().defaultBlockState()));
    }

    private static void discardBridge(ServerLevel arena, BattleArenaData data, int slot) {
        BridgeEntity bridge = bridgeAt(arena, data, slot);
        if (bridge != null) bridge.discard();
    }

    /** 战败判定（MVP）：防线全灭（场上仍有敌军而棋子吃光）⇒ 清敌军重置波次（战损清零）+ DEFEAT 事件。 */
    private static void checkDefeat(ServerLevel arena, BattleArenaData data, ContainerContext ctx) {
        if (data.enemies().isEmpty() || !data.bridgeBySlot().isEmpty()) return;
        BattleEnemies.clearEnemies(arena, data);
        sweepDamage(ctx);
        BattleEventFeed.offer(data.battleKey(), new BattleEvent(BattleEvent.Type.DEFEAT, -1));
    }

    /**
     * 战局边界清零：剥离全容器活物品上的战损组件（D-battle-04：战损只活在战局内，
     * 建局 / 清场 / 战败三处调用；取消活化残留为哑组件，无行为，alpha 可容忍）。
     */
    public static void sweepDamage(ContainerContext ctx) {
        for (int i = 0; i < ctx.getSize(); i++) {
            ItemStack stack = ctx.getItem(i);
            if (stack.isEmpty() || !LivingItemManager.isLivingItem(stack)) continue;
            if (BattleComponents.BattleDamage.of(stack) == 0) continue;
            ItemStack after = stack.copy();
            after.remove(BattleComponents.BATTLE_DAMAGE.get());
            ctx.commitItemComponents(i, stack, after);
        }
    }

    /**
     * 槽位 ↔ 桥名册（「活物品的化身」映射本体）：spawn = 部署新棋子；discard = 撤子；
     * move = 同一枚棋子换位（桥瞬移，不重铸）；count 变更 = 掉层联动；漂移 = 校准拉回。
     */
    private static void reconcile(ServerLevel arena, BattleArenaData data, ContainerContext ctx) {
        Map<Integer, ItemStack> living = scanLiving(ctx);
        int width = Math.max(1, ctx.getWidth());
        var plan = ReconcilePlan.diff(data.bridgeBySlot(), living);

        for (int slot : plan.discard()) {
            BridgeEntity bridge = bridgeAt(arena, data, slot);
            if (bridge != null) bridge.discard();
            data.bridgeBySlot().remove(slot);
        }
        for (int slot : plan.spawn()) {
            ItemStack stack = living.get(slot);
            if (stack == null || stack.isEmpty()) continue;
            BridgeEntity bridge = BattleEntities.BRIDGE.get().create(arena);
            if (bridge == null) continue;
            Vec3 center = ArenaPlots.cellCenter(data.plotPos(), rowOf(slot, width), colOf(slot, width));
            bridge.setPiece(stack.getCount(), stack);
            bridge.setBattleKey(data.battleKey());
            bridge.setSlot(slot);
            // ⚠️ 不设 invulnerable：hurt() 在伤害事件【之前】短路无敌旗 ⇒ 事件收不到。
            // 桥的免伤由 BattleEventHandler 的取消钩子承担（同一道闸，且不挡事件）。
            bridge.moveTo(center.x, center.y, center.z, 0.0F, 0.0F);
            arena.addFreshEntity(bridge);
            data.bridgeBySlot().put(slot, new ReconcilePlan.BridgeRef(bridge.getUUID(), stack));
        }
        for (int[] move : plan.moves()) {
            BridgeEntity bridge = bridgeAt(arena, data, move[0]);
            data.bridgeBySlot().remove(move[0]);
            if (bridge == null) continue;   // 名册失效：该槽下拍按 spawn 重建（自愈）
            bridge.setSlot(move[1]);
            Vec3 center = ArenaPlots.cellCenter(data.plotPos(), rowOf(move[1], width), colOf(move[1], width));
            bridge.teleportTo(center.x, center.y, center.z);
            data.bridgeBySlot().put(move[1], new ReconcilePlan.BridgeRef(bridge.getUUID(), living.get(move[1])));
        }

        // 层数联动 + 位置校准（每拍对名册全量核对，稳态零写入）
        for (Map.Entry<Integer, ReconcilePlan.BridgeRef> entry : data.bridgeBySlot().entrySet()) {
            int slot = entry.getKey();
            ItemStack stack = living.get(slot);
            BridgeEntity bridge = bridgeAt(arena, data, slot);
            if (bridge == null) continue;
            if (stack != null && bridge.countLayer() != stack.getCount()) {
                bridge.setPiece(stack.getCount(), stack);
            }
            Vec3 center = ArenaPlots.cellCenter(data.plotPos(), rowOf(slot, width), colOf(slot, width));
            if (bridge.distanceToSqr(center) > 0.25) {
                bridge.teleportTo(center.x, center.y, center.z);
            }
        }
    }

    private static Map<Integer, ItemStack> scanLiving(ContainerContext ctx) {
        Map<Integer, ItemStack> out = new HashMap<>();
        for (int i = 0; i < ctx.getSize(); i++) {
            ItemStack stack = ctx.getItem(i);
            if (!stack.isEmpty() && LivingItemManager.isLivingItem(stack)) {
                out.put(i, stack);
            }
        }
        return out;
    }

    private static int rowOf(int slot, int width) {
        return slot / width;
    }

    private static int colOf(int slot, int width) {
        return slot % width;
    }

    private static BridgeEntity bridgeAt(ServerLevel arena, BattleArenaData data, int slot) {
        var ref = data.bridgeBySlot().get(slot);
        if (ref == null) return null;
        return arena.getEntity(ref.uuid()) instanceof BridgeEntity bridge ? bridge : null;
    }
}
