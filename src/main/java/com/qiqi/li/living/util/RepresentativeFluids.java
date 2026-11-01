package com.qiqi.li.living.util;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidType;

import javax.annotation.Nullable;

/**
 * {@link FluidType} → 代表 {@link Fluid} 的注册表映射（FluidStack 构造需要 vanilla Fluid；
 * 优先 still 源态）。域中立知识，power/water/sponge 共用（2026-11-02 自
 * {@code ContainerFluidData#representativeFluid} 上移 —— 海绵能力面需要它但不能 import 水域）。
 *
 * <p>未注册到 {@code BuiltInRegistries.FLUID} 的 FluidType 返回 {@code null}
 * （调用方按 EMPTY 处理）；结果缓存（含 null，防反复全表扫描）。</p>
 */
public final class RepresentativeFluids {

    private static final Map<FluidType, Fluid> CACHE = new HashMap<>();

    @Nullable
    public static Fluid of(FluidType type) {
        if (CACHE.containsKey(type)) return CACHE.get(type);
        Fluid found = null;
        for (Fluid f : BuiltInRegistries.FLUID) {
            if (f.getFluidType() != type) continue;
            if (found == null || f.defaultFluidState().isSource()) found = f;
            if (found != null && found.defaultFluidState().isSource()) break;
        }
        CACHE.put(type, found);
        return found;
    }

    private RepresentativeFluids() {}
}
