package com.qiqi.li.living.compat.sophisticated;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;

import com.qiqi.li.living.container.MenuSlotOwners;
import com.qiqi.li.logging.ModLog;

/**
 * 精妙背包的<b>联动模块</b>：告诉核心「这个背包界面是哪个方块」（2026-10-19，`D-container-09`）。
 *
 * <h3>为什么这段代码不在核心</h3>
 * <p>它的存在理由<b>完全来自某一个模组</b>（{@code BackpackContainer#getBlockPosition()}）。
 * 放在核心等于让核心去猜"第三方菜单可能有个方法叫 getBlockPosition"——那就是把本模组
 * 弄成精妙背包的模样。核心只保留机制（{@link MenuSlotOwners#registerMenuResolver}），
 * 具体怎么问这个模组，归这里。</p>
 *
 * <h3>为什么用反射而不是 compileOnly</h3>
 * <p>两种都合法，取舍不同：</p>
 * <ul>
 *   <li><b>compileOnly</b>：编译期安全（对方改名会编译失败），但要求构建环境能拉到 jar
 *       （Modrinth Maven / 本地 libs）。Create / Sable 走的就是这条。</li>
 *   <li><b>反射</b>（当前选择）：零依赖，离线可构建；代价是对方改名会静默失效。
 *       <b>但失效范围被关在这个模块里</b> —— 核心与其它模组不受影响，且失败会打 debug
 *       （见 {@link #resolve}）。</li>
 * </ul>
 *
 * <p>⚠️ 反射的判据是<b>窄</b>的：只认包名前缀 {@value #PACKAGE_PREFIX} 的菜单，且只认
 * {@code getBlockPosition} 一个方法名、只认返回 {@code BlockPos} / {@code Optional<BlockPos>}。
 * 不认 {@code getPos} 这类泛名字（容易误命中玩家/合成格坐标 ⇒ 把包发给错的人）。</p>
 */
public final class SophisticatedMenuResolver implements MenuSlotOwners.MenuPositionResolver {

    /** 只处理这个模组的菜单 —— 反射不扩散到任意第三方。 */
    private static final String PACKAGE_PREFIX = "net.p3pp3rf1y.sophisticatedbackpacks.";

    private static final String METHOD_NAME = "getBlockPosition";

    /** 菜单类 → 方法（{@link Optional#empty()} = 该类没有可用方法，缓存负结果）。 */
    private static final Map<Class<?>, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();

    @Nullable
    @Override
    public BlockPos resolve(AbstractContainerMenu menu) {
        Class<?> menuClass = menu.getClass();
        if (!menuClass.getName().startsWith(PACKAGE_PREFIX)) return null;

        Optional<Method> cached = METHOD_CACHE.computeIfAbsent(menuClass, SophisticatedMenuResolver::findMethod);
        if (cached.isEmpty()) return null;

        try {
            Object value = cached.get().invoke(menu);
            if (value instanceof BlockPos pos) return pos;
            if (value instanceof Optional<?> opt) {
                return opt.filter(BlockPos.class::isInstance).map(BlockPos.class::cast).orElse(null);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 对方改名 / 模块系统限制 / 未初始化 —— 只影响精妙的显示同步，不影响核心
            ModLog.CONTAINER.debug("[Sophisticated] {} 调用失败：{}", METHOD_NAME, e.toString());
        }
        return null;
    }

    private static Optional<Method> findMethod(Class<?> menuClass) {
        Method m;
        try {
            m = menuClass.getMethod(METHOD_NAME);
        } catch (NoSuchMethodException | RuntimeException e) {
            ModLog.CONTAINER.debug("[Sophisticated] {} 没有 {} —— 该模组可能已改 API",
                menuClass.getName(), METHOD_NAME);
            return Optional.empty();
        }
        if (Modifier.isStatic(m.getModifiers())) return Optional.empty();
        Class<?> ret = m.getReturnType();
        if (ret != BlockPos.class && ret != Optional.class) return Optional.empty();
        try {
            m.setAccessible(true);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return Optional.of(m);
    }
}
