package com.qiqi.li.living.domain.battle;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.qiqi.li.living.container.ContainerDataKey;
import net.minecraft.core.BlockPos;

/**
 * 战局数据（per-container）—— 桥名册、敌军名册与场地几何。
 *
 * <p>容器级持久 key：跨 tick 存活，不落盘（spike：重启丢战局 = 可接受，
 * 平台残留由 {@code clear} 清）。{@code battleKey == null} 即无战局，
 * 是 tick 路径的廉价 no-op 门。</p>
 */
public final class BattleArenaData {

    public static final ContainerDataKey<BattleArenaData> KEY =
        ContainerDataKey.persistent("battle_arena", BattleArenaData::new);

    /** 战局归属键（= 容器缓存键）；null = 无战局。 */
    private String battleKey;
    /** 平台西北角原点（lane 0 / column 0 外角）。 */
    private BlockPos plotPos;
    /** 车道数（= 容器行数）与纵深格数（= 容器列数）。 */
    private int lanes;
    private int depth;

    /** 桥名册：逻辑槽位 → 桥引用（UUID + 物品身份指纹）。桥不落盘，缺了由 reconcile 重建（自愈）。 */
    private final Map<Integer, ReconcilePlan.BridgeRef> bridgeBySlot = new HashMap<>();
    /** 敌军名册：UUID 集合；敌军不落盘，实体消失时由 flush 侧 prune。 */
    private final java.util.Set<UUID> enemies = new java.util.HashSet<>();

    public boolean isEmpty() {
        return battleKey == null;
    }

    public String battleKey() {
        return battleKey;
    }

    public BlockPos plotPos() {
        return plotPos;
    }

    public int lanes() {
        return lanes;
    }

    public int depth() {
        return depth;
    }

    public Map<Integer, ReconcilePlan.BridgeRef> bridgeBySlot() {
        return bridgeBySlot;
    }

    public java.util.Set<UUID> enemies() {
        return enemies;
    }

    /** 建立战局（build 命令）；重复 build = 重摆场地并重置名册。 */
    public void configure(String battleKey, BlockPos plotPos, int lanes, int depth) {
        this.battleKey = battleKey;
        this.plotPos = plotPos;
        this.lanes = lanes;
        this.depth = depth;
        bridgeBySlot.clear();
        enemies.clear();
    }

    /** 清局（clear 命令 / 容器键失效）：实体清理由调用方负责。 */
    public void reset() {
        battleKey = null;
        plotPos = null;
        bridgeBySlot.clear();
        enemies.clear();
    }
}
