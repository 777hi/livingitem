package com.qiqi.li.living;

import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;
import com.mojang.serialization.Codec;

/**
 * 活物品功能数据 —— 底层NBT存储层（纯数据容器）。
 *
 * 职责：
 * - 数据持久化：连接 DataComponent 系统与 NBT 存储
 * - 网络同步：支持服务端到客户端的数据传输
 * - 状态管理：提供功能数据的读写接口
 *
 * 设计原则：
 * ✅ **透明性**：不参与任何业务逻辑（tooltip渲染、tick执行、用户交互）
 * ✅ **不可变性**：符合 DataComponent 规范，修改操作返回新实例
 * ✅ **轻量级**：仅作为 CompoundTag 包装器，无额外开销
 *
 * 架构定位：
 * ┌─────────────────────────┐
 * │   LivingFurnaceFunction │ ← 业务逻辑层（tooltip、tick）
 * │   LivingHopperFunction  │
 * └──────────┬──────────────┘
 *            │ 读取/写入
 *            ▼
 * ┌─────────────────────────┐
 * │   FunctionExecutor      │ ← 协调层（组件调度）
 * └──────────┬──────────────┘
 *            │ 存取数据
 *            ▼
 * ┌─────────────────────────┐
 * │   LivingFunctionData    │ ← 本类（纯数据存储）
 * │   (CompoundTag wrapper) │
 * └─────────────────────────┘
 *
 * 数据格式示例：
 * {
 *   "living_furnace": {
 *     "progress": { ... },      // ComponentState
 *     "fuel_consume": { ... }   // ComponentState
 *   }
 * }
 */
public class LivingFunctionData implements TooltipProvider {

    /** 空数据单例 */
    public static final LivingFunctionData EMPTY = new LivingFunctionData(new CompoundTag());

    /** 内部 NBT 数据 */
    private final CompoundTag data;

    /**
     * Codec 用于数据持久化（写入物品 NBT、从物品 NBT 读取）。
     * 直接使用 CompoundTag.CODEC 做底层存储，通过 xmap 包装为 LivingFunctionData。
     *
     * 注意：使用 xmap 而非 map —— map 只接受一个 decode 方向的 Function，
     * xmap 接受 encode 和 decode 两个方向。
     */
    public static final Codec<LivingFunctionData> CODEC =
        // CompoundTag.CODEC 是 Codec<CompoundTag>，可以双向编码解码 CompoundTag
        // xmap(decode, encode) 把它映射为 Codec<LivingFunctionData>
        CompoundTag.CODEC.xmap(
            // decode 方向：CompoundTag -> LivingFunctionData
            LivingFunctionData::new,
            // encode 方向：LivingFunctionData -> CompoundTag
            LivingFunctionData::getRawData
        );

    /**
     * StreamCodec 用于网络同步。
     * 客户端与服务器之间交换活物品数据时，通过此 Codec 进行序列化。
     *
     * 注意：使用 ByteBufCodecs.fromCodecWithRegistries 而非 fromCodec，
     * 因为后者返回 StreamCodec<ByteBuf, T>（不支持 registry access），
     * 而 DataComponent.networkSynchronized() 要求 StreamCodec<RegistryFriendlyByteBuf, T>。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, LivingFunctionData> STREAM_CODEC =
        // 自定义 StreamCodec —— 直接读写 NBT，简单清晰
        new StreamCodec<>() {
            @Override
            public LivingFunctionData decode(RegistryFriendlyByteBuf buf) {
                // 从网络流读取一个 NBT CompoundTag，包装为 LivingFunctionData
                CompoundTag tag = buf.readNbt();
                return new LivingFunctionData(tag != null ? tag : new CompoundTag());
            }
            @Override
            public void encode(RegistryFriendlyByteBuf buf, LivingFunctionData value) {
                // 把 LivingFunctionData 的原始数据写入网络流
                buf.writeNbt(value.getRawData());
            }
        };

    /**
     * 构造函数 —— 从现有的 CompoundTag 创建。
     * 主要用于从 NBT 反序列化时调用。
     *
     * @param data 原始数据 tag，以 functionId 为 key
     */
    public LivingFunctionData(CompoundTag data) {
        this.data = data != null ? data : new CompoundTag();
    }

    /**
     * 获取指定功能的数据。
     * 用于在 tick 或 tooltip 渲染中读取某个活物品功能的运行时数据。
     *
     * @param functionId 功能 ID，如 {@link LivingFurnaceFunction#ID}
     * @return 对应功能的数据 tag，若不存在则返回空 tag（永远不为 null）
     */
    public CompoundTag getFunctionData(String functionId) {
        // 参数 10 表示要求是 TAG_Compound（CompoundTag 的类型编号）
        if (data.contains(functionId, 10)) {
            return data.getCompound(functionId);
        }
        return new CompoundTag();
    }

    /**
     * 设置指定功能的数据。
     * 注意：此方法返回一个新的 LivingFunctionData 实例（因为 DataComponent 要求不可变），
     * 所以调用者需要用返回值重新设置到 ItemStack 上：
     * <pre>
     *   LivingFunctionData newData = oldData.setFunctionData("living_furnace", furnaceTag);
     *   stack.set(LIVING_FUNCTION_DATA.value(), newData);
     * </pre>
     *
     * @param functionId 功能 ID
     * @param functionData 功能数据；为空时会移除该功能的条目
     * @return 新的 LivingFunctionData 实例（因为 DataComponent 是不可变的）
     */
    public LivingFunctionData setFunctionData(String functionId, CompoundTag functionData) {
        // 复制一份数据，因为 DataComponent 体系要求组件值不可变
        CompoundTag newData = data.copy();
        if (functionData == null || functionData.isEmpty()) {
            // 空数据 -> 移除该功能条目
            newData.remove(functionId);
        } else {
            // 写入新数据
            newData.put(functionId, functionData);
        }
        // 如果已经没有任何功能数据了，返回 EMPTY 以节省内存
        if (newData.isEmpty()) {
            return EMPTY;
        }
        return new LivingFunctionData(newData);
    }

    /**
     * 获取原始数据 tag。
     * 用于序列化和其他需要直接访问底层数据的场景。
     *
     * @return 原始 CompoundTag
     */
    public CompoundTag getRawData() {
        return data;
    }

    /**
     * 判断是否为空（没有任何功能数据）。
     *
     * @return 如果没有任何功能数据则返回 true
     */
    public boolean isEmpty() {
        return data.isEmpty();
    }

    /**
     * TooltipProvider 接口实现 —— 空实现。
     *
     * 设计决策：tooltip渲染已完全委托给各活物品功能自行处理
     * （参见 {@link com.qiqi.li.client.LivingItemTooltip}）
     *
     * 保留此空方法的原因：
     * - Minecraft DataComponent 系统要求实现 TooltipProvider 接口
     * - 避免编译错误或运行时异常
     * - 保持 API 兼容性
     */
    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
        // 空实现 - tooltip渲染由 LivingItemTooltip + 各 LivingItemFunction 处理
    }

    /**
     * equals 方法 —— 用于 DataComponent 系统判断组件值是否发生变化。
     * 只有当数据真正变化时才需要同步到客户端，这样可以避免不必要的网络传输。
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof LivingFunctionData other)) return false;
        // 直接比较底层 CompoundTag 的内容
        return this.data.equals(other.data);
    }

    @Override
    public int hashCode() {
        return data.hashCode();
    }

    @Override
    public String toString() {
        return "LivingFunctionData" + data.getAsString();
    }
}