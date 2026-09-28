/**
 * 容器上下文 —— {@code LivingItemFunction#tick} 的运行期环境（第三方必读）。
 *
 * <p>{@link com.qiqi.li.living.container.ContainerContext} 提供槽位/物品/同步能力；
 * {@link com.qiqi.li.living.container.TickContext} 提供容器级临时状态
 * （powerData / fluidData / stressData / redstoneData 四个 public 字段）。</p>
 *
 * <p>⚠️ 已知坑（写代码前必读 {@code api-contract.md}）：大箱子槽位体系错位、
 * ticking 边界邻块加载。脏槽位同步语义见 {@code ContainerLivingItemHandler}。</p>
 */
package com.qiqi.li.living.container;
