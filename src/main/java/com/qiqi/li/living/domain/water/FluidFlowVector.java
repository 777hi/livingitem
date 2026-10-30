package com.qiqi.li.living.domain.water;

import java.util.function.IntUnaryOperator;

/**
 * 流体方向 —— <b>全项目唯一的方向来源</b>（2026-10-31 从 {@link ContainerFluidData} 抽出）。
 *
 * <p>为什么单独成类：方向有三个消费者 —— 物品推动、容器界面渲染、活水车力矩。
 * 2026-10-30 之前三者各算一套（推动读 BFS 父节点、渲染读父节点再归四向、水车再读父节点），
 * 结果同一格水在「看起来往哪流 / 物品往哪走 / 水车往哪转」上不一致。
 * 现在三个消费者都只能调本类，{@code ContainerFluidData} 不再对外暴露方向算法。</p>
 *
 * <p><b>算法</b>：四邻 level 差矢量和（原版 {@code FlowingFluid#getFlow} 的离散版）——
 * 力指向 level 增大方向（= 水位降低 = 远离源），只算<b>有水</b>邻居；斜向由两个正交分量
 * 合成，**不采样角落**（角落 level 语义不同，可能来自另一条支路，加了只会带噪声）。</p>
 */
public final class FluidFlowVector {

    /** 无方向（力场平衡 / 本格无水）。 */
    public static final int FLOW_NONE = -1;
    public static final int FLOW_RIGHT = 0;
    public static final int FLOW_DOWN = 1;
    public static final int FLOW_LEFT = 2;
    public static final int FLOW_UP = 3;

    /** 无方向时的空候选（复用，避免每次分配）。 */
    private static final int[] NO_DIRECTIONS = new int[0];

    private FluidFlowVector() {}

    /**
     * 主方向（渲染与「只要一个方向」的调用方用）；{@link #FLOW_NONE} = 无力场。
     *
     * @param levelOf 邻格 level 查询：<b>无水 / 非同流体 / 越界一律返回 -1</b>（不参与力场）
     */
    public static int direction(int slot, int width, int containerSize, IntUnaryOperator levelOf) {
        int[] dirs = candidates(slot, width, containerSize, levelOf);
        return dirs.length == 0 ? FLOW_NONE : dirs[0];
    }

    /**
     * 候选方向，按优先级排序（**最多 2 个**）：主分量在前、次分量在后。
     *
     * <p>🔴 <b>为什么必须保留次分量（2026-10-31 真机「摆弯道物品一个弯都过不去」）</b>：
     * 原版的力是<b>两个分量同时作用</b>，碰撞只挡一个轴、另一个轴的位移照常生效
     * ⇒ 物品会「斜着擦过障碍、挪到直道上」。只取主分量、推不动就停 ⇒ 直角弯处必卡。</p>
     *
     * <p>45° 等值（{@code |fx| == |fy|}）按<b>容器主轴</b>决定先后（2026-10-31 用户拍板）：
     * 宽容器（列 &gt; 行）⇒ 横向优先；竖容器与<b>正方形</b> ⇒ 纵向优先（兜底取上下）。
     * 主轴只在这里生效，非等值时永远按主分量。两个分量都指向「远离源」⇒ 兜底不会逆流。</p>
     *
     * @return 候选方向（0/1/2 个，按优先级）；空 = 力场平衡或无本格
     */
    public static int[] candidates(int slot, int width, int containerSize, IntUnaryOperator levelOf) {
        int my = levelOf.applyAsInt(slot);
        if (my < 0) return NO_DIRECTIONS;

        int fx = 0, fy = 0;
        int col = slot % width;
        if (col > 0) {
            int nb = levelOf.applyAsInt(slot - 1);
            if (nb >= 0) fx -= nb - my;          // 左邻 level 更大（水位更低）⇒ 指向左
        }
        if (col < width - 1) {
            int nb = levelOf.applyAsInt(slot + 1);
            if (nb >= 0) fx += nb - my;
        }
        int above = levelOf.applyAsInt(slot - width);
        if (above >= 0) fy -= above - my;
        int below = levelOf.applyAsInt(slot + width);
        if (below >= 0) fy += below - my;

        if (fx == 0 && fy == 0) return NO_DIRECTIONS;

        int horizontal = fx == 0 ? FLOW_NONE : (fx > 0 ? FLOW_RIGHT : FLOW_LEFT);
        int vertical = fy == 0 ? FLOW_NONE : (fy > 0 ? FLOW_DOWN : FLOW_UP);
        if (horizontal == FLOW_NONE) return new int[] {vertical};
        if (vertical == FLOW_NONE) return new int[] {horizontal};

        boolean horizontalFirst;
        if (Math.abs(fx) != Math.abs(fy)) {
            horizontalFirst = Math.abs(fx) > Math.abs(fy);
        } else {
            // 45° 平局：宽容器横向（主道沿行），竖容器与正方形纵向（兜底取上下）
            int rows = Math.max(1, (containerSize + width - 1) / width);
            horizontalFirst = width > rows;
        }
        return horizontalFirst
            ? new int[] {horizontal, vertical}
            : new int[] {vertical, horizontal};
    }

    /**
     * 方向的下一格；左右越行边界返回 -1。
     * 上下越界由调用方按容器尺寸兜底（{@code slot - width < 0} / {@code slot + width >= size}）。
     */
    public static int step(int slot, int dir, int width) {
        int col = slot % width;
        return switch (dir) {
            case FLOW_RIGHT -> col < width - 1 ? slot + 1 : -1;
            case FLOW_LEFT -> col > 0 ? slot - 1 : -1;
            case FLOW_DOWN -> slot + width;
            case FLOW_UP -> slot - width;
            default -> -1;
        };
    }

    /** 方向 → 水平分量（-1 / 0 / 1），供力矩等需要向量的调用方使用。 */
    public static int dx(int dir) {
        return dir == FLOW_RIGHT ? 1 : dir == FLOW_LEFT ? -1 : 0;
    }

    /** 方向 → 垂直分量（-1 / 0 / 1）。 */
    public static int dy(int dir) {
        return dir == FLOW_DOWN ? 1 : dir == FLOW_UP ? -1 : 0;
    }
}
