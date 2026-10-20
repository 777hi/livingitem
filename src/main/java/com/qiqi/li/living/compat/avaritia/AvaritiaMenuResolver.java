package com.qiqi.li.living.compat.avaritia;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.qiqi.li.living.container.MenuSlotOwners;
import com.qiqi.li.logging.ModLog;

/**
 * 无尽箱子（AvaritiaNeo）的<b>联动模块</b>：告诉核心「这个界面是哪个方块」
 * （2026-10-20，`D-container-09` 的第二个应用）。
 *
 * <h3>为什么需要单独一条</h3>
 * <p>无尽箱子的 {@code TileInfinityChest} 只 {@code extends BlockEntity}，
 * <b>不实现</b> {@code Container}；它的 {@code ItemHandler.BLOCK} 能力返回
 * {@code WrapperInfinityChest(tile.chest)}，而菜单槽的 container 就是那个
 * {@code SimpleContainer}（不是方块实体）。所以：</p>
 * <ul>
 *   <li>结构化分支（{@code slot.container instanceof BlockEntity}）—— 走不通；</li>
 *   <li>菜单自己的 {@code getBlockPosition()} —— 它<b>没有</b>这个方法；</li>
 *   <li>⇒ viewer 匹配恒为空 ⇒ 图标 / tooltip / 流体渲染不刷新，GUI 倒汲静默无效。</li>
 * </ul>
 *
 * <p>它的菜单有 {@code getTile()} 返回 {@code TileInfinityChest}（{@code BlockEntity}），
 * 所以判据是「<b>返回方块实体的 getter</b>」，而不是某个特定的位置方法名。</p>
 *
 * <h3>判据为什么这么窄</h3>
 * <p>① 只处理包名前缀 {@value #PACKAGE_PREFIX} 的菜单（反射不扩散到任意第三方）；
 * ② 只认 {@code getTile} / {@code getBlockEntity} 两个名字，且<b>返回类型必须是
 * {@link BlockEntity} 的子类</b>（拿到 BE 后 {@code getBlockPos()} 是公开 API，不需要再反射）。
 * 不认 {@code getPos} 这类泛名字 —— 它可能返回玩家坐标或合成格坐标，会把包发给错的人。</p>
 */
public final class AvaritiaMenuResolver implements MenuSlotOwners.MenuPositionResolver {

    /** 只处理这个模组的菜单 —— 反射不扩散到任意第三方。 */
    private static final String PACKAGE_PREFIX = "net.byAqua3.avaritia.";

    /**
     * 认的方法名（按顺序尝试）。
     *
     * <p>{@code getTile} 是无尽箱子 / 压缩机的命名习惯（1.12 时代沿用的 tile 叫法），
     * {@code getBlockEntity} 是更现代的命名，一并认。</p>
     */
    private static final String[] METHOD_NAMES = {"getTile", "getBlockEntity"};

    /** 菜单类 → 方法（{@link Optional#empty()} = 该类没有可用方法，缓存负结果）。 */
    private static final Map<Class<?>, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();

    @Nullable
    @Override
    public BlockPos resolve(AbstractContainerMenu menu) {
        Class<?> menuClass = menu.getClass();
        if (!menuClass.getName().startsWith(PACKAGE_PREFIX)) return null;

        Optional<Method> cached = METHOD_CACHE.computeIfAbsent(menuClass, AvaritiaMenuResolver::findMethod);
        if (cached.isEmpty()) return null;

        try {
            Object value = cached.get().invoke(menu);
            // 拿到方块实体后直接用公开 API 取位置 —— 不需要再反射一层
            if (value instanceof BlockEntity be) return be.getBlockPos();
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 对方改名 / 模块系统限制 / 未初始化 —— 只影响无尽箱子的显示同步，不影响核心
            ModLog.CONTAINER.debug("[Avaritia] 菜单位置调用失败：{}", e.toString());
        }
        return null;
    }

    private static Optional<Method> findMethod(Class<?> menuClass) {
        for (String name : METHOD_NAMES) {
            Method m;
            try {
                m = menuClass.getMethod(name);
            } catch (NoSuchMethodException e) {
                continue;
            } catch (RuntimeException e) {
                return Optional.empty();
            }
            if (Modifier.isStatic(m.getModifiers())) continue;
            // 只认「返回方块实体」的 getter —— 返回类型本身就是判据，不靠方法名猜语义
            if (!BlockEntity.class.isAssignableFrom(m.getReturnType())) continue;
            try {
                m.setAccessible(true);
            } catch (RuntimeException e) {
                continue;
            }
            return Optional.of(m);
        }
        ModLog.CONTAINER.debug("[Avaritia] {} 没有返回方块实体的 getter（getTile / getBlockEntity）"
            + " —— 该模组可能已改 API", menuClass.getName());
        return Optional.empty();
    }
}
