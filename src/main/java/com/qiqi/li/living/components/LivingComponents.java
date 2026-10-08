package com.qiqi.li.living.components;

import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Map;
import java.util.UUID;
import com.qiqi.li.living.api.LivingMod;
import com.qiqi.li.living.transfer.FilterData;

/**
 * <b>框架级</b>持久化类型的注册站（A1 迁移 2026-09-28；2026-10-08 拆解后只剩框架级）。
 *
 * <p>历史：A1 迁移时全部 35 个组件/附件常量集中于此，使 api 面不再牵出 domain 类型；
 * 但集中也让 {@code components}（L2）反向认识 8 个领域（27 条 R1）。2026-10-08 把
 * <b>领域组件</b>拆回各自的 {@code XxxComponents}（同领域内，由入口类构造阶段挂总线），本类只保留<b>不依赖任何领域类型</b>的框架级组件。</p>
 *
 * <p>现存 4 项：{@code is_living} / {@code living_tool_owner} / {@code living_tool_owner_name}
 * （原始类型），以及 {@code living_hopper_filter}（类型 {@code FilterData} 属 {@code transfer}，
 * 与本类同层 ⇒ 留在框架侧不产生跨层依赖）。</p>
 *
 * <p>⚠️ <b>领域组件不要往这里加</b> —— 加回一个就重新产生一条 {@code components → domain} 边。
 * 新组件请定义在<b>所属领域的 {@code XxxComponents}</b> 里 —— 那里由入口类
 * 构造阶段挂总线，保证 {@code DeferredRegister.register} 早于 {@code RegisterEvent}
 * （否则抛 {@code Cannot register new entries after RegisterEvent has been fired}）。</p>
 */
public final class LivingComponents {
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(net.neoforged.neoforge.registries.NeoForgeRegistries.ATTACHMENT_TYPES, LivingMod.ID);

    private LivingComponents() {}

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> IS_LIVING =
            DATA_COMPONENT_TYPES.register("is_living", () ->
                    DataComponentType.<Boolean>builder()
                            .persistent(Codec.BOOL)
                            .networkSynchronized(ByteBufCodecs.BOOL)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> LIVING_TOOL_OWNER =
            DATA_COMPONENT_TYPES.register("living_tool_owner", () ->
                    DataComponentType.<UUID>builder()
                            .persistent(UUIDUtil.CODEC)
                            // ⭐ 网络同步（2026-09-29）：tooltip 在客户端显示「赋灵者」，
                            //    不同步的话客户端栈上根本没有这个组件，显示不出主人。
                            .networkSynchronized(UUIDUtil.STREAM_CODEC)
                            .build());

    /**
     * 主人名字的<b>显示缓存</b>（非绑定数据 —— 绑定关系只看 {@link #LIVING_TOOL_OWNER} 的 UUID）。
     *
     * <p>⚠️ 名字是<b>衍生显示数据</b>：服务端在能确认名字时（活化时 / 回放遇到在线主人时）
     * 刷新缓存，客户端 tooltip 在实时解析失败（主人离线且本地无缓存）时兜底显示它。
     * 不刷新只会显示旧名，不会显示错人 —— 所以允许陈旧。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> LIVING_TOOL_OWNER_NAME =
            DATA_COMPONENT_TYPES.register("living_tool_owner_name", () ->
                    DataComponentType.<String>builder()
                            .persistent(Codec.STRING)
                            .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                            .build());

    /**
     * 漏斗黑白名单过滤链：容器派生数据不落盘（仅网络同步供 tooltip），每 tick 由快照重建。
     *
     * <p>⚠️ <b>留在框架侧</b>：它的类型 {@code FilterData} 属于 {@code transfer}（L2），
     * 与框架侧同层 ⇒ 放进 hopper 领域会制造 L2 → L3 反向依赖。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<FilterData>> LIVING_HOPPER_FILTER =
            DATA_COMPONENT_TYPES.register("living_hopper_filter", () ->
                    DataComponentType.<FilterData>builder()
                            .networkSynchronized(FilterData.STREAM_CODEC)
                            .build());

}
