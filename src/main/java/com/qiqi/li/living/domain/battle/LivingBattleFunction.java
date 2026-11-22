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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
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
        reconcile(arena, data, ctx);
        if (arena.getGameTime() % FLUSH_INTERVAL == 0) {
            BattleServerSync.flush(ctx, data);
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
            Vec3 center = ArenaPlots.cellCenter(data.plotPos(), laneOf(slot, width), colOf(slot, width));
            bridge.setPiece(stack.getCount(), stack);
            bridge.setBattleKey(data.battleKey());
            bridge.setSlot(slot);
            bridge.setInvulnerable(true);
            bridge.moveTo(center.x, center.y, center.z, 0.0F, 0.0F);
            arena.addFreshEntity(bridge);
            data.bridgeBySlot().put(slot, new ReconcilePlan.BridgeRef(bridge.getUUID(), stack));
        }
        for (int[] move : plan.moves()) {
            BridgeEntity bridge = bridgeAt(arena, data, move[0]);
            data.bridgeBySlot().remove(move[0]);
            if (bridge == null) continue;   // 名册失效：该槽下拍按 spawn 重建（自愈）
            bridge.setSlot(move[1]);
            Vec3 center = ArenaPlots.cellCenter(data.plotPos(), laneOf(move[1], width), colOf(move[1], width));
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
            Vec3 center = ArenaPlots.cellCenter(data.plotPos(), laneOf(slot, width), colOf(slot, width));
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

    private static int laneOf(int slot, int width) {
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
