package com.qiqi.li.living.interaction;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;

import javax.annotation.Nullable;

import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * 交互谓词注册表 —— JSON 规则里写谓词 ID，Java 里按 ID 查表（D2 · Q-D2-3）。
 *
 * <p><b>为什么存在</b>：{@link InteractionEntry} 的 {@code triggerFilter} 是
 * {@code BiPredicate}（Java 方法引用），JSON 装不下 ⇒ 中间加一层 ID 间接：
 * 各域在注册 handler 时顺手注册自己的谓词，JSON 里写 {@code "predicate": "can_till"}。</p>
 *
 * <p><b>谁在用</b>（2026-09-28 全量盘点，就 2 个 —— 别再为想象中的谓词预设计）：
 * <ul>
 *   <li>{@code can_till} → {@code Tillables::canTillWith}（farmland，5 条 tillable 规则共用）</li>
 *   <li>{@code can_plant} → {@code PlantCropHandler::canPlantWith}（farmland，plant_crop）</li>
 * </ul></p>
 *
 * <p><b>失败语义</b>：JSON 引用未注册的谓词 ID ⇒ 加载时 WARN 并丢弃该条规则
 * （沉默即缺陷 —— 与 open-plan §5 原则 4 一致）。注册表本身不持久化，
 * 随 commonSetup 重建。</p>
 */
@ApiStatus.Internal
public final class InteractionPredicates {

    private static final Map<String, BiPredicate<ItemStack, ItemStack>> PREDICATES = new HashMap<>();

    private InteractionPredicates() {}

    /** 注册一个谓词。重复注册同 ID 视为配置错误（覆盖会静默改变既有规则语义）。 */
    public static void register(String id, BiPredicate<ItemStack, ItemStack> predicate) {
        BiPredicate<ItemStack, ItemStack> prev = PREDICATES.putIfAbsent(id, predicate);
        if (prev != null) {
            throw new IllegalStateException("Interaction predicate id 冲突: " + id);
        }
    }

    /** 按 ID 查谓词；未注册返回 {@code null}（调用方必须 WARN 并丢弃引用它的规则）。 */
    @Nullable
    public static BiPredicate<ItemStack, ItemStack> get(String id) {
        return PREDICATES.get(id);
    }

    /** 清空（仅供单元测试隔离，同 {@code InteractionRegistry#clearForTest}）。 */
    static void clearForTest() {
        PREDICATES.clear();
    }
}
