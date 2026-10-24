package com.qiqi.li.living.container;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * 容器在世界里的<b>参考物</b> —— 只回答「是谁 / 在哪」，<b>不回答「用哪个点」</b>。
 *
 * <h3>为什么是「参考物」而不是坐标</h3>
 * <p>同一个容器，不同用途要不同的点，而且这些差异<b>是对的</b>：</p>
 * <table>
 *   <caption>同一锚点、不同用途</caption>
 *   <tr><th>用途</th><th>方块容器</th><th>玩家容器</th></tr>
 *   <tr><td>爆炸中心</td><td>方块中心</td><td>{@code player.position()}（脚下）</td></tr>
 *   <tr><td>应力注入</td><td>方块<b>下方</b></td><td>{@code player.blockPosition()}（脚下方块）</td></tr>
 *   <tr><td>射线重放起点</td><td>方块中心</td><td>{@code player.getEyePosition()}（<b>眼睛</b>）</td></tr>
 * </table>
 * <p>⇒ 若把锚点定义成 {@code Vec3}，这四份实现就只能各自算、永远收不拢。
 * 定义成「参考物」后，<b>分类逻辑只有一处</b>，取点仍是各功能的事。</p>
 *
 * <h3>为什么需要它</h3>
 * <p>2026-10-23 普查发现「容器在世界里作用在哪」这件事有 <b>4 份各写一遍</b>的解析
 * （TNT 爆炸 / 水车应力 / 工具射线 / 信标光束），而且 5 种容器形态在每份里的处理都不一样
 * ⇒ 同一功能在不同形态下的行为<b>没人有完整视图</b>。典型后果：
 * 物品形态与实体容器（船/矿车）在多数功能里被静默跳过。</p>
 *
 * <p>普查与设计：[docs/buffer/container-anchor-design.md](../../../../../docs/buffer/container-anchor-design.md)</p>
 */
public sealed interface ContainerAnchor {

    /** 方块容器（BE / 多方块）。取点由调用方定：中心 / 下方 / 邻居。 */
    record AtBlock(BlockPos pos) implements ContainerAnchor {}

    /** 玩家容器（玩家背包 / 末影箱）。取点由调用方定：脚下 / 脚下方块 / 眼睛。 */
    record AtPlayer(Player player) implements ContainerAnchor {}

    /**
     * 实体容器（箱子船 / 箱子矿车）与掉落物。
     *
     * <p>⚠️ 这两种形态共用本类型<b>不是巧合</b>：它们都有实体、都没有方块位置
     * （{@code getBlockPos()} 刻意返回 null）。也正因为它们落到同一个锚点类型上，
     * 才说明「锚点不该按容器形态定义」。</p>
     */
    record AtEntity(Entity entity) implements ContainerAnchor {}
}
