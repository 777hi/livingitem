package com.qiqi.li.living.compat.tlm;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidAndItemTransformEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitItems;
import com.qiqi.li.living.util.StaticCacheRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.UUID;

/** 活魂符释放后将待兑现好感交给TLM原生管理器。 */
final class TlmFavorRedemption {
    static final String HANDOFF_KEY = "living_item:pending_favor_handoff";
    static final String HANDOFF_OWNER_KEY = "living_item:pending_favor_owner";
    private static final String NEOFORGE_DATA = "NeoForgeData";
    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/TlmFavorRedemption");

    private static final IdentityRedemptionQueue<EntityMaid, Boolean> PENDING =
            new IdentityRedemptionQueue<>();

    static {
        StaticCacheRegistry.onServerStop(TlmFavorRedemption::clear);
    }

    private TlmFavorRedemption() {}

    static void onToMaid(MaidAndItemTransformEvent.ToMaid event) {
        EntityMaid maid = event.getMaid();
        CompoundTag data = event.getData();
        if (!(maid.level() instanceof ServerLevel) || !event.getItem().is(InitItems.SMART_SLAB_HAS_MAID.get())
                || !data.hasUUID("Owner")) return;
        int amount = PendingMaidFavor.take(data);
        if (amount <= 0) return;
        CompoundTag neoForgeData = data.contains(NEOFORGE_DATA, Tag.TAG_COMPOUND)
                ? data.getCompound(NEOFORGE_DATA) : new CompoundTag();
        neoForgeData.putInt(HANDOFF_KEY, amount);
        neoForgeData.putUUID(HANDOFF_OWNER_KEY, data.getUUID("Owner"));
        data.put(NEOFORGE_DATA, neoForgeData);
        PENDING.putIfAbsent(maid, Boolean.TRUE);
    }

    static void onServerTick(ServerTickEvent.Post event) {
        for (IdentityRedemptionQueue.Entry<EntityMaid, Boolean> entry : PENDING.drain()) {
            EntityMaid maid = entry.key();
            if (!(maid.level() instanceof ServerLevel level) || level.getServer() != event.getServer()
                    || maid.isRemoved() || !maid.isAddedToLevel() || level.getEntity(maid.getUUID()) != maid) continue;
            CompoundTag persistent = maid.getPersistentData();
            if (!persistent.contains(HANDOFF_KEY, Tag.TAG_INT)
                    || !persistent.hasUUID(HANDOFF_OWNER_KEY)) continue;
            int amount = Math.clamp(persistent.getInt(HANDOFF_KEY), 0, PendingMaidFavor.MAX_FAVOR);
            UUID owner = persistent.getUUID(HANDOFF_OWNER_KEY);
            persistent.remove(HANDOFF_KEY);
            persistent.remove(HANDOFF_OWNER_KEY);
            if (amount <= 0 || !owner.equals(maid.getOwnerUUID())) continue;
            int current = maid.getFavorability();
            if (current < 0 || current >= PendingMaidFavor.MAX_FAVOR) continue;
            int useful = Math.min(amount, PendingMaidFavor.MAX_FAVOR - current);
            try {
                maid.getFavorabilityManager().add(useful);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.error("待兑现好感应用失败：maid={} amount={}", maid.getUUID(), useful, failure);
            }
        }
    }

    static void clear(MinecraftServer server) {
        PENDING.clear();
    }

    static int pendingCount() {
        return PENDING.size();
    }
}
