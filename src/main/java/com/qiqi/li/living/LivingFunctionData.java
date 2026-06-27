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
 * 活物品功能数据容器。
 * 持有所有活物品功能的运行时数据（如活熔炉的燃烧时间、烹饪进度等）。
 * 内部使用 CompoundTag 存储，以 functionId（如 "living_furnace"）为 key。
 *
 * 实现 {@link TooltipProvider} 接口后，当该组件被注册为 DataComponent 时，
 * 可以直接通过组件的 addToTooltip 方法向 tooltip 添加信息，
 * 避免在 ItemTooltipEvent 中手动遍历每个 function。
 *
 * 数据结构示例（NBT）:
 * <pre>
 * {
 *   "living_furnace": {
 *     "burn_time": 200,
 *     "cook_time": 50,
 *     "cook_time_total": 200
 *   },
 *   "living_hopper": { ... }
 * }
 * </pre>
 */
public class LivingFunctionData implements TooltipProvider {

    /** 空数据实例，用于避免空指针判断 */
    public static final LivingFunctionData EMPTY = new LivingFunctionData(new CompoundTag());

    /** 核心数据存储 —— 一个 CompoundTag，以 functionId 为 key */
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
     * 向 Tooltip 添加活物品信息。
     * 此方法由 {@link TooltipProvider} 接口定义，Minecraft 通过
     * ItemStack.addToTooltip(component, ...) 来调用它。
     *
     * 实现逻辑：遍历所有已注册的活物品功能，检查数据中是否有对应功能的数据。
     * 如果有，则调用该功能的 addToTooltip 方法将其信息添加到 tooltip。
     *
     * @param context 物品 tooltip 上下文（包含 registryAccess、level 等信息）
     * @param tooltipAdder 向 tooltip 中添加一行内容的 Consumer
     * @param flag tooltip 显示标志（是否详细模式、是否创造模式等）
     */
    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
        // 遍历 LivingItemManager 中已注册的所有活物品功能
        for (LivingItemFunction function : LivingItemManager.getAllFunctions()) {
            // 获取该功能对应的数据
            CompoundTag functionData = getFunctionData(function.getFunctionId());
            // 如果有数据，调用该功能的 tooltip 渲染方法
            if (!functionData.isEmpty()) {
                function.addToTooltip(functionData, context, tooltipAdder, flag);
            }
        }
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