package com.qiqi.li.client.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.inventory.Slot;

import com.qiqi.li.client.mixin.SlotWrapperAccessor;

/**
 * 客户端槽位解析（Q6 批次 C，2026-10-04）—— 边界带共享内核
 * {@code com.qiqi.li.living.container.ContainerContexts} 的<b>客户端对偶</b>。
 *
 * <h3>为什么不在 ContainerContexts 里</h3>
 * <p>创造模式的 {@code SlotWrapper} 是<b>原版客户端</b>内部类，靠客户端 Mixin
 * {@link SlotWrapperAccessor} 访问。若 common 包的 {@code ContainerContexts} 引用它，
 * 专用服务端加载该类时会崩 —— 故客户端解析独立成此工具（逻辑同属「槽位 ↔ 容器身份」收敛，
 * 只是被迫分居两侧）。</p>
 *
 * <h3>问题</h3>
 * <p>创造模式 INVENTORY 标签页里，快捷栏槽位被 {@code SlotWrapper} 包装：
 * {@code slot.getContainerSlot()} 返回的是<b>菜单索引</b>（如 38），
 * 只有 {@code target.getContainerSlot()} 才是<b>真实容器槽位</b>（如 2）。
 * 任何「菜单槽位 → 真实容器槽位」的客户端消费者都必须先解包，否则发往服务端的索引是错的。</p>
 */
public final class ClientSlotResolve {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/SlotResolve");

    /** SlotWrapper 类 → 其 {@code Slot} 类型字段（反射兜底用），按类缓存。 */
    private static final ConcurrentHashMap<Class<?>, java.lang.reflect.Field[]> SLOT_WRAPPER_FIELD_CACHE =
        new ConcurrentHashMap<>();

    private ClientSlotResolve() {}

    /**
     * 解析槽位的<b>真实容器槽位索引</b>，兼容创造模式 {@code SlotWrapper}。
     *
     * <p>收编自 {@code GuiInteractionHelper.resolveContainerSlot} 与
     * {@code AbstractContainerScreenMixin.living_item$resolveContainerSlot} 两份实现 ——
     * 后者多一层「类名含 {@code SlotWrapper} 但未实现 accessor」的反射兜底，此处统一保留
     * （accessor 未命中时反射找被包装的 {@code Slot}，仍找不到才退回 {@code getContainerSlot()}）。</p>
     */
    public static int resolveContainerSlot(Slot slot) {
        if (slot instanceof SlotWrapperAccessor accessor) {
            return accessor.getTarget().getContainerSlot();
        }
        if (slot.getClass().getSimpleName().contains("SlotWrapper")) {
            Slot target = unwrapSlotWrapper(slot);
            if (target != null) {
                return target.getContainerSlot();
            }
            LOGGER.warn("[SlotResolve] SlotWrapper detected but could NOT resolve target! class={}",
                slot.getClass().getName());
        }
        return slot.getContainerSlot();
    }

    /**
     * 反射兜底：槽位类名含 {@code SlotWrapper} 但未实现 {@link SlotWrapperAccessor}
     * （Mixin 未应用 / 第三方包装器）时，反射找被包装的 {@code Slot}。
     */
    private static Slot unwrapSlotWrapper(Slot wrapper) {
        try {
            Class<?> wrapperClass = wrapper.getClass();
            java.lang.reflect.Field[] fields = SLOT_WRAPPER_FIELD_CACHE.computeIfAbsent(wrapperClass, clazz -> {
                List<java.lang.reflect.Field> slotFields = new ArrayList<>();
                for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (Slot.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            slotFields.add(f);
                        }
                    }
                }
                return slotFields.toArray(new java.lang.reflect.Field[0]);
            });
            for (java.lang.reflect.Field f : fields) {
                Slot target = (Slot) f.get(wrapper);
                if (target != null && target != wrapper) {
                    return target;
                }
            }
        } catch (Exception e) {
            LOGGER.error("[SlotResolve] Reflection fallback failed for SlotWrapper", e);
        }
        return null;
    }
}
