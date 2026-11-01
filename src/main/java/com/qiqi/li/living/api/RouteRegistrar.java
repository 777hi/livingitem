package com.qiqi.li.living.api;

/**
 * 跨容器路由注册方标记 —— 实现者的槽位可作为跨容器路由的「注册方」。
 *
 * <p>当前唯一实现者是活漏斗（把物品传输进活末影箱时登记路由）。路由表校验时据此
 * 判断「注册方槽位是否仍然有效」——按槽位上物品的适用函数做能力发现，
 * 不 import 具体领域，与 {@link HasDirection} 同为可选扩展接口。</p>
 */
public interface RouteRegistrar {
}
