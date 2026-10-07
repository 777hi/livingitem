package com.qiqi.li.living.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.domain.chest.LivingChestFunction;
import com.qiqi.li.living.domain.ender.LivingEnderChestFunction;
import com.qiqi.li.living.domain.farmland.LivingFarmlandFunction;
import com.qiqi.li.living.domain.furnace.LivingFurnaceFunction;
import com.qiqi.li.living.domain.hopper.LivingHopperFunction;
import com.qiqi.li.living.domain.map.LivingEnderPearlFunction;
import com.qiqi.li.living.domain.map.LivingMapFunction;
import com.qiqi.li.living.domain.power.LivingWaxedCopperFunction;
import com.qiqi.li.living.domain.redstone.LivingButtonFunction;
import com.qiqi.li.living.domain.redstone.LivingComparatorFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.domain.redstone.LivingCopperFunction;
import com.qiqi.li.living.domain.redstone.LivingLeverFunction;
import com.qiqi.li.living.domain.redstone.LivingRedstoneBlockFunction;
import com.qiqi.li.living.domain.redstone.LivingRedstoneFunction;
import com.qiqi.li.living.domain.redstone.LivingRedstoneLampFunction;
import com.qiqi.li.living.domain.redstone.LivingRedstoneTorchFunction;
import com.qiqi.li.living.domain.redstone.LivingRepeaterFunction;
import com.qiqi.li.living.domain.tnt.LivingTntFunction;
import com.qiqi.li.living.domain.tools.LivingToolFunction;
import com.qiqi.li.living.domain.water.LivingBucketFunction;
import com.qiqi.li.living.domain.water.LivingWaterWheelFunction;
import com.qiqi.li.living.function.LivingFlintAndSteelFunction;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * DataComponent 归属守卫 —— 锁住 A2「各功能自声明」契约（2026-09-27）。
 *
 * <h3>为什么需要</h3>
 * <p>{@code clearLivingData} 原先是一份<b>硬编码的 31 行 remove 清单</b>，
 * javadoc 明确写着「新增 LivingItemFunction 必须回来手添」——
 * 这意味着<b>第三方 addon 只能 fork 本仓库</b>（open-plan.md §1.2 P0-1）。
 *
 * <p>已改为遍历各 {@link LivingItemFunction#getOwnedComponentTypes()} 的自声明。
 * 好处是第三方零接触；代价是「<b>忘了声明</b>」变成静默失败 ——
 * 组件会残留在取消活化的物品上，成为<b>孤儿数据</b>（组件还在、但没人认领，
 * 再次活化时会读到脏旧值）。本测试把这个失败模式钉死。
 *
 * <h3>为什么用反射而不是硬编码清单</h3>
 * <p>组件集合从 {@code LivingItemManager} 的字段<b>反射得出</b>，
 * 因此新增 DataComponent 会<b>自动纳入</b>本测试，
 * 无需同步第二份清单（避免又一处“两处维护必然漂移”）。
 */
@DisplayName("DataComponent 归属守卫 · 各功能自声明契约（A2）")
class ComponentOwnershipTest {

    /**
     * {@code IS_LIVING} 属于框架本身，由 {@code clearLivingData} 单独清除；
     * 任何功能都不应声明它（语义上它不是某个功能的数据）。
     */
    private static final String FRAMEWORK_COMPONENT = "IS_LIVING";

    /**
     * 全部 22 个 LivingItemFunction 实现。
     *
     * <p>⚠️ 新增功能类时<b>同步此处</b>：忘加 ⇒ 若它带了新组件，测试 1 会报
     * 「该组件无人声明」，从而提示你补到这里（并补 Enhanced javadoc）。
     */
    private static List<LivingItemFunction> allFunctions() {
        return List.of(
            new LivingChestFunction(),
            new LivingEnderChestFunction(),
            new LivingFarmlandFunction(),
            new LivingFurnaceFunction(),
            new LivingHopperFunction(),
            new LivingEnderPearlFunction(),
            new LivingMapFunction(),
            new LivingWaxedCopperFunction(),
            new LivingButtonFunction(),
            new LivingComparatorFunction(),
            new LivingCopperFunction(),
            new LivingLeverFunction(),
            new LivingRedstoneBlockFunction(),
            new LivingRedstoneFunction(),
            new LivingRedstoneLampFunction(),
            new LivingRedstoneTorchFunction(),
            new LivingRepeaterFunction(),
            new LivingTntFunction(),
            new LivingToolFunction(),
            new LivingBucketFunction(),
            new LivingWaterWheelFunction(),
            new LivingFlintAndSteelFunction()
        );
    }

    /**
     * 反射取所有 DataComponent，字段名作为可读标识。
     *
     * <p>A1 迁移后组件常量分居两处：内容组件在 {@code LivingComponents}（注册站）、
     * 框架级原始类型组件（IS_LIVING / 工具 owner 等）在 {@code LivingItemManager} ——
     * 两处都枚举，AttachmentType 会被 {@code instanceof DataComponentType} 自动过滤。</p>
     */
    private static Map<DataComponentType<?>, String> componentTypes() {
        Map<DataComponentType<?>, String> out = new LinkedHashMap<>();
        for (Class<?> holder : List.of(LivingComponents.class, LivingItemManager.class)) {
            for (Field f : holder.getFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != DeferredHolder.class) {
                    continue;
                }
                try {
                    if (f.get(null) instanceof DeferredHolder<?, ?> h
                            && h.get() instanceof DataComponentType<?> type) {
                        out.put(type, f.getName());
                    }
                } catch (IllegalAccessException e) {
                    fail("无法读取 " + holder.getSimpleName() + "." + f.getName() + ": " + e);
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("① 每个 DataComponent 都必须有 Function 声明归属（否则清不掉 → 孤儿数据）")
    void everyComponentHasAnOwner() {
        Set<DataComponentType<?>> declared = new HashSet<>();
        for (LivingItemFunction f : allFunctions()) {
            declared.addAll(f.getOwnedComponentTypes());
        }

        List<String> orphans = new ArrayList<>();
        for (Map.Entry<DataComponentType<?>, String> e : componentTypes().entrySet()) {
            if (FRAMEWORK_COMPONENT.equals(e.getValue())) {
                continue;
            }
            if (!declared.contains(e.getKey())) {
                orphans.add(e.getValue());
            }
        }

        assertTrue(orphans.isEmpty(), () ->
            "这些 DataComponent 没有任何 LivingItemFunction 声明归属 ⇒ clearLivingData 清不掉它们，"
            + "会在取消活化后变成孤儿数据。请在对应的 Function 里覆盖 getOwnedComponentTypes()："
            + orphans);
    }

    @Test
    @DisplayName("② 一个 DataComponent 不应被多个 Function 同时声明归属")
    void noComponentIsOwnedTwice() {
        Map<DataComponentType<?>, List<String>> owners = new HashMap<>();
        for (LivingItemFunction f : allFunctions()) {
            for (DataComponentType<?> type : f.getOwnedComponentTypes()) {
                owners.computeIfAbsent(type, k -> new ArrayList<>()).add(f.getFunctionId());
            }
        }
        Map<DataComponentType<?>, String> nameOf = componentTypes();

        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<DataComponentType<?>, List<String>> e : owners.entrySet()) {
            if (e.getValue().size() > 1) {
                conflicts.add(nameOf.getOrDefault(e.getKey(), e.getKey().toString()) + " ← " + e.getValue());
            }
        }

        assertTrue(conflicts.isEmpty(), () ->
            "同一 DataComponent 被多个 Function 声明归属，清理语义会互相踩：" + conflicts);
    }

    @Test
    @DisplayName("③ 取消活化时，自声明的组件会被自动清除（无需在核心类里登记）")
    void declaredComponentsAreClearedOnDeactivation() {
        for (LivingItemFunction f : allFunctions()) {
            LivingItemManager.registerFunction(f);
        }

        ItemStack stack = new ItemStack(Items.FURNACE);
        LivingItemManager.setLiving(stack, true);
        LivingItemManager.setFurnaceBurning(stack, true);

        LivingItemManager.setLiving(stack, false);

        assertFalse(stack.has(LivingComponents.LIVING_FURNACE_BURNING.value()),
            "LIVING_FURNACE_BURNING 已由 LivingFurnaceFunction 声明，取消活化时应当被清除");
        assertFalse(stack.has(LivingComponents.IS_LIVING.value()),
            "IS_LIVING 属框架本身，取消活化时必须被清除");
    }
}
