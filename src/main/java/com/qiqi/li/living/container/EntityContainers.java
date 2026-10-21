package com.qiqi.li.living.container;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.Slot;

import javax.annotation.Nullable;

/**
 * 实体容器的白名单与菜单解析（2026-10-21）—— 设计稿
 * {@code docs/buffer/container-menu-introspection-topic.md} §2.2 / §3。
 *
 * <h3>白名单（{@link #isContainerEntity}）</h3>
 * <ul>
 *   <li>箱子船 / 箱子矿车 / 漏斗矿车：1.21 {@link ContainerEntity}（= {@code Container} +
 *       {@code MenuProvider}）。NeoForge 在 {@code CapabilityHooks} 里<b>显式</b>给这三个
 *       实体类型注册 {@code ItemHandler.ENTITY}。</li>
 *   <li>带箱驴 / 骡：{@link AbstractChestedHorse#hasChest()}，走 NeoForge 的 LivingEntity
 *       fallback。</li>
 * </ul>
 *
 * <p>⚠️ <b>为什么必须白名单</b>：NeoForge 的 fallback 给<b>所有</b>实体注册了
 * {@code ItemHandler.ENTITY}（LivingEntity = 主手 + 副手 + 盔甲）⇒
 * 「能力面存在」≠「该当容器」（契约 I-C1 的一处真实张力）。僵尸的盔甲槽不该 tick；
 * 无箱驴/骡的 inventory 只有 2 格（鞍 + 体甲）也不该 —— 活马铠没有意义。</p>
 *
 * <h3>菜单解析（{@link #resolveMenuEntity}）</h3>
 * <p>两条路，按可靠性排序：</p>
 * <ol>
 *   <li><b>结构化</b>（船 / 两种矿车）：1.21 的 {@code ContainerEntity extends Container}
 *       ⇒ 菜单槽的 {@code container} <b>就是实体本身</b>（{@code ChestBoat#createMenu}
 *       传 {@code this}）⇒ {@code instanceof} 直取，零反射。</li>
 *   <li><b>Mixin accessor</b>（驴）：槽的 container 是 NeoForge 的匿名 {@code InvWrapper}
 *       ⇒ 只能问菜单（{@code HorseInventoryMenu.horse}，private final 无 getter）⇒
 *       编译期 accessor（原版类在编译 classpath 上，比反射硬一个量级）。</li>
 * </ol>
 */
public final class EntityContainers {

    private EntityContainers() {}

    /** 是否「该被当容器 tick」的实体（白名单）。 */
    public static boolean isContainerEntity(Entity entity) {
        if (entity instanceof AbstractChestedHorse horse) {
            return horse.hasChest();   // 无箱驴/骡 inventory 只有 2 格（鞍 + 体甲）
        }
        return entity instanceof ContainerEntity;
    }

    /**
     * 菜单槽 → 容器实体（GUI 边界带的汲/倒/空槽交互、viewer 匹配共用）。
     *
     * <p>与 {@link MenuSlotOwners#resolve}（菜单槽 → 位置）的分工：那个答「在哪」
     * （同步轨用），本方法答「是哪个实体」（构造 {@code EntityContainerContext} 用）。
     * 两者共用同一套判定，不会漂。</p>
     *
     * @return 容器实体；该槽不属任何实体容器（玩家背包槽 / 普通方块槽 / 无箱马）返回 {@code null}
     */
    @Nullable
    public static Entity resolveMenuEntity(AbstractContainerMenu menu, Slot slot) {
        // ① 结构化：船 / 矿车的槽位 container 就是实体本身
        if (slot.container instanceof Entity e && isContainerEntity(e)) {
            return e;
        }
        // ② 注册的实体解析器（Mixin 侧实现 + 注册，核心不认识任何菜单类 —— D-container-09）：
        //    驴/骡的菜单存着 horse 字段（private final、无 getter）⇒ 入口层反射该字段（见 LivingItem
        //    的 HORSE_MENU_RESOLVER：Mixin 包隔离使 accessor 方案不可用，已实测）。
        //    ⚠️ 白名单在下面统一判：HorseInventoryMenu 是【马/驴/骡】共用的菜单类，
        //    不过 hasChest 就会把「打开马的 GUI」当成容器交互。
        Entity registered = MenuSlotOwners.resolveMenuEntity(menu, slot);
        return registered != null && isContainerEntity(registered) ? registered : null;
    }


}
