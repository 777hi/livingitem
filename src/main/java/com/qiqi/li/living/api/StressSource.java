package com.qiqi.li.living.api;

/**
 * 容器应力「只读视图」—— 供<b>兼容层</b>（Create 应力输出）读取活水车的应力，而不必 import 水领域。
 *
 * <p>背景（2026-10-08）：{@code compat/create} 的 {@code StressOutputManager} /
 * {@code CreateIntegration} / {@code ModCreate} 原先以具体的 {@code ContainerStressData}
 * 作方法参数 ⇒ {@code compat}（L2）认识 {@code water} 领域（L3）。改为接口后，
 * 兼容层只依赖契约层，水领域实现它。</p>
 *
 * <p>实现者：{@code ContainerStressData}（水领域）。</p>
 */
public interface StressSource {

    /** 是否无应力（廉价短路判据）。 */
    boolean isEmpty();

    /** 净应力（顺时针 − 逆时针）。 */
    int getNetStress();
}
