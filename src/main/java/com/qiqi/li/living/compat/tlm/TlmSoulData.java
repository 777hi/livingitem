package com.qiqi.li.living.compat.tlm;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import com.github.tartaricacid.touhoulittlemaid.entity.backpack.BackpackManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitAttribute;
import com.github.tartaricacid.touhoulittlemaid.init.InitDataComponent;

import java.util.stream.Stream;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapDecoder;
import com.mojang.serialization.MapLike;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** 所存女仆的只读步行输入，不创建实体，不修改女仆快照。 */
public final class TlmSoulData {
    public record Inputs(double hunger, int capacity) {
        public boolean valid() {
            return Double.isFinite(hunger) && hunger >= 0 && capacity > 0;
        }
    }

    private static final MapDecoder<CompoundTag> INPUT_TAG = new MapDecoder.Implementation<>() {
        @Override public <T> DataResult<CompoundTag> decode(DynamicOps<T> ops, MapLike<T> input) {
            CompoundTag selected = new CompoundTag();
            for (String key : new String[]{"model_id", "attributes", "MaidBackpackType"}) {
                T value = input.get(key);
                if (value != null) selected.put(key, ops.convertTo(NbtOps.INSTANCE, value));
            }
            return DataResult.success(selected);
        }
        @Override public <T> Stream<T> keys(DynamicOps<T> ops) {
            return Stream.of("model_id", "attributes", "MaidBackpackType").map(ops::createString);
        }
    };

    private TlmSoulData() {}

    public static Optional<Inputs> readInputs(ItemStack stack) {
        CustomData data = stack.get(InitDataComponent.MAID_INFO);
        if (data == null) return Optional.empty();
        Optional<CompoundTag> selected = data.read(INPUT_TAG).result();
        if (selected.isEmpty()) return Optional.empty();
        CompoundTag tag = selected.get();
        if (!tag.contains("model_id", Tag.TAG_STRING) || tag.getString("model_id").isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<Double> hunger = readHunger(tag);
            Optional<Integer> capacity = readCapacity(tag);
            if (hunger.isEmpty() || capacity.isEmpty()) return Optional.empty();
            Inputs inputs = new Inputs(hunger.get(), capacity.get());
            return inputs.valid() ? Optional.of(inputs) : Optional.empty();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Optional.empty();
        }
    }

    private static Optional<Double> readHunger(CompoundTag tag) {
        AttributeSupplier supplier = DefaultAttributes.getSupplier(EntityMaid.TYPE);
        if (supplier == null || !supplier.hasAttribute(InitAttribute.MAID_HUNGER)) {
            return Optional.empty();
        }
        if (!tag.contains("attributes")) {
            return Optional.of(supplier.getValue(InitAttribute.MAID_HUNGER));
        }
        if (!tag.contains("attributes", Tag.TAG_LIST)) return Optional.empty();
        ListTag attributes = tag.getList("attributes", Tag.TAG_COMPOUND);
        if (!tag.getList("attributes", Tag.TAG_COMPOUND).equals(tag.get("attributes"))) {
            return Optional.empty();
        }
        CompoundTag saved = null;
        for (Tag entry : attributes) {
            CompoundTag attribute = (CompoundTag) entry;
            if (!attribute.contains("id", Tag.TAG_STRING)) return Optional.empty();
            if (!attribute.getString("id").equals(InitAttribute.MAID_HUNGER.getId().toString())) continue;
            if (saved != null) return Optional.empty();
            saved = attribute;
        }
        if (saved == null) return Optional.of(supplier.getValue(InitAttribute.MAID_HUNGER));
        if (!saved.contains("base", Tag.TAG_ANY_NUMERIC) || !Double.isFinite(saved.getDouble("base"))) {
            return Optional.empty();
        }
        if (saved.contains("modifiers")) {
            if (!saved.contains("modifiers", Tag.TAG_LIST)) return Optional.empty();
            ListTag modifiers = saved.getList("modifiers", Tag.TAG_COMPOUND);
            if (!modifiers.equals(saved.get("modifiers"))) return Optional.empty();
            Set<ResourceLocation> ids = new HashSet<>();
            for (Tag modifierTag : modifiers) {
                Optional<AttributeModifier> modifier = AttributeModifier.CODEC.parse(NbtOps.INSTANCE, modifierTag).result();
                if (modifier.isEmpty() || !Double.isFinite(modifier.get().amount()) || !ids.add(modifier.get().id())) {
                    return Optional.empty();
                }
            }
        }
        AttributeInstance instance = supplier.createInstance(a -> {}, InitAttribute.MAID_HUNGER);
        if (instance == null) return Optional.empty();
        instance.removeModifiers();
        instance.load(saved);
        double value = instance.getValue();
        return Double.isFinite(value) ? Optional.of(value) : Optional.empty();
    }

    private static Optional<Integer> readCapacity(CompoundTag tag) {
        if (!tag.contains("MaidBackpackType")) {
            return Optional.of(BackpackManager.getEmptyBackpack().getAvailableMaxContainerIndex());
        }
        if (!tag.contains("MaidBackpackType", Tag.TAG_STRING)) return Optional.empty();
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("MaidBackpackType"));
        if (id == null) return Optional.empty();
        return Optional.of(BackpackManager.findBackpack(id)
                .orElseGet(BackpackManager::getEmptyBackpack).getAvailableMaxContainerIndex());
    }
}
