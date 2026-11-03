package com.qiqi.li.client.icon;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.qiqi.li.living.model.Pos2D;

import net.minecraft.Util;

/**
 * 活活塞客户端伸缩进度 —— 纯渲染态，不参与服务端机械判定（设计稿 §2.4「动画与执行分离」）。
 *
 * <p>键 = {@code (背包槽? "P" : "C") + getContainerSlot()}：同一菜单里容器槽与玩家背包槽的
 * {@code getContainerSlot()} 可能同号（箱子 0..26 vs Inventory 0..35），前缀防串。
 * 每帧由 {@code LivingPistonOverlay} 收集活跃键并 {@link #prune}——菜单关闭 / 切换后
 * 下一帧自然清除，不挂 mixin、不动共享缓存。</p>
 *
 * <p>⚠️ 仅客户端渲染线程访问（非线程安全，HashMap）。时钟用
 * {@link Util#getMillis()} 单调钟（{@link WaterWheelRenderState} 同款约定，
 * 禁 {@code level.getGameTime()}——会被时间同步包硬覆盖）。</p>
 */
public final class PistonRenderState {

    /** 伸缩动画时长：原版活塞 2 game tick。实机若觉太快可调。 */
    public static final long DURATION_MS = 100;

    /** Rx(30°)·Ry(225°) 后模型 +X 在 GUI 屏面的投影角（右手系，屏幕 Y 正向向下）。 */
    private static final float PROJECTED_EAST_ANGLE_DEGREES = -153.4349488f;

    private static final Map<String, Progression> PROGRESS = new HashMap<>();

    private record Progression(boolean target, float startProgress, long startMillis, Pos2D direction) {}

    private PistonRenderState() {}

    /**
     * 推进某槽的伸缩进度并返回当前值（0 = 收回，1 = 完全伸入 X+1）。
     *
     * <p>目标翻转 ⇒ 从当前进度起开始新过渡；方向变化 ⇒ <b>吸附</b>（不做跨方向滑动，
     * 2026-11-03 定案「改朝向使旧态失效」）。{@code direction} 由调用方准入保证非空且四向。</p>
     */
    public static float advance(String key, boolean targetExtended, Pos2D direction, long nowMillis) {
        Objects.requireNonNull(direction);
        Progression prev = PROGRESS.get(key);
        if (prev == null || prev.target() != targetExtended || !prev.direction().equals(direction)) {
            float from = prev == null ? 0f
                : (prev.direction().equals(direction) ? currentProgress(prev, nowMillis)
                                                      : (targetExtended ? 1f : 0f));
            prev = new Progression(targetExtended, from, nowMillis, direction);
            PROGRESS.put(key, prev);
        }
        return currentProgress(prev, nowMillis);
    }

    /** 只保留本帧活跃的键；其余清除（菜单关闭 / 切换的免挂点清理）。 */
    public static void prune(Set<String> activeKeys) {
        PROGRESS.keySet().removeIf(k -> !activeKeys.contains(k));
    }

    /** 头模型 SHORT 档：头在基座半程内用短头（原版伸出侧 {@code progress <= 0.5} 同族）。 */
    public static boolean headShort(float progress) {
        return progress <= 0.5f;
    }

    /**
     * 头位移（<b>[0,1] 模型单位</b>）：整段机械行程 = 1，随进度线性。
     * 屏面像素校正属于叠加层位姿，不能烘进此进度值。
     */
    public static float headOffsetUnits(float progress) {
        return progress;
    }

    /**
     * 屏面 Z 旋转（度）：配置方向的屏幕角减去 iso 位姿中模型 +X 的投影角。
     * GUI 屏幕 Y 正向向下；此表与 {@code Rx(30°)·Ry(225°)} 必须成对调整。
     */
    public static float rotationDegrees(Pos2D direction) {
        if (Pos2D.UP.equals(direction)) return -90f - PROJECTED_EAST_ANGLE_DEGREES;
        if (Pos2D.DOWN.equals(direction)) return 90f - PROJECTED_EAST_ANGLE_DEGREES;
        if (Pos2D.LEFT.equals(direction)) return 180f - PROJECTED_EAST_ANGLE_DEGREES;
        return -PROJECTED_EAST_ANGLE_DEGREES;   // RIGHT 与兜底
    }

    /** 测试隔离。 */
    public static void clearForTest() {
        PROGRESS.clear();
    }

    private static float currentProgress(Progression p, long nowMillis) {
        float delta = (nowMillis - p.startMillis()) / (float) DURATION_MS;
        return p.target()
            ? Math.min(1f, p.startProgress() + delta)
            : Math.max(0f, p.startProgress() - delta);
    }
}
