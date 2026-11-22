package com.qiqi.li.living.domain.battle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 战局快照 S2C —— 「摄像头」的数据面：实体快照 + 事件流（重演）+ 槽位映射。
 *
 * <p>式 B 领域包（直调客户端缓存，双端安全）。客户端按 menuId 门控：包不是给当前
 * 打开的菜单的直接丢弃（Portal 同款）。</p>
 */
public record BattleSnapshotPacket(int menuId, String containerKey, int lanes, int depth,
                                   List<EnemyState> enemies, Map<Integer, Integer> menuSlots,
                                   List<BattleEvent> events)
        implements CustomPacketPayload {

    public static final Type<BattleSnapshotPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("living_item", "battle_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, BattleSnapshotPacket> STREAM_CODEC =
        StreamCodec.of(BattleSnapshotPacket::encode, BattleSnapshotPacket::decode);

    private static final int MAX_ENEMIES = 64;
    private static final int MAX_BRIDGES = 128;
    private static final int MAX_EVENTS = 32;

    /**
     * 敌军快照：网格坐标（row=南北行浮点 / col=东西列浮点，各自夹取到场地内）+ 头盔标记。
     * <p>⚠️ 演出不背真相：快照里没有的（装备/药效/骑乘…）GUI 就不演——
     * 想演什么就往快照里加什么字段（spike 只带头盔一项）。</p>
     */
    public record EnemyState(long uuidMost, long uuidLeast, float row, float col, boolean helmet) {}

    public BattleSnapshotPacket {
        enemies = List.copyOf(enemies);
        menuSlots = Map.copyOf(menuSlots);
        events = List.copyOf(events);
    }

    @Override
    public Type<BattleSnapshotPacket> type() {
        return TYPE;
    }

    private static void encode(FriendlyByteBuf buf, BattleSnapshotPacket packet) {
        buf.writeVarInt(packet.menuId());
        buf.writeUtf(packet.containerKey());
        buf.writeVarInt(packet.lanes());
        buf.writeVarInt(packet.depth());
        buf.writeVarInt(packet.enemies().size());
        for (EnemyState enemy : packet.enemies()) {
            buf.writeLong(enemy.uuidMost());
            buf.writeLong(enemy.uuidLeast());
            buf.writeFloat(enemy.row());
            buf.writeFloat(enemy.col());
            buf.writeBoolean(enemy.helmet());
        }
        buf.writeVarInt(packet.menuSlots().size());
        packet.menuSlots().forEach((menu, logical) -> {
            buf.writeVarInt(menu);
            buf.writeVarInt(logical);
        });
        buf.writeVarInt(packet.events().size());
        for (BattleEvent event : packet.events()) {
            buf.writeVarInt(event.type().ordinal());
            buf.writeVarInt(event.slot());
        }
    }

    private static int readCount(FriendlyByteBuf buf, int max, String what) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) throw new IllegalArgumentException("Invalid battle " + what + " count");
        return count;
    }

    private static BattleSnapshotPacket decode(FriendlyByteBuf buf) {
        int menuId = buf.readVarInt();
        String key = buf.readUtf();
        int lanes = readCount(buf, 27, "lanes");
        int depth = readCount(buf, 128, "depth");
        List<EnemyState> enemies = new ArrayList<>();
        int count = readCount(buf, MAX_ENEMIES, "enemy");
        for (int i = 0; i < count; i++) {
            long most = buf.readLong();
            long least = buf.readLong();
            float row = buf.readFloat();
            float col = buf.readFloat();
            boolean helmet = buf.readBoolean();
            enemies.add(new EnemyState(most, least, row, col, helmet));
        }
        Map<Integer, Integer> menuSlots = new HashMap<>();
        count = readCount(buf, MAX_BRIDGES, "menu slot");
        for (int i = 0; i < count; i++) {
            int menu = buf.readVarInt();
            int logical = buf.readVarInt();
            if (menu < 0 || logical < 0) throw new IllegalArgumentException("Invalid battle menu mapping");
            menuSlots.put(menu, logical);
        }
        List<BattleEvent> events = new ArrayList<>();
        count = readCount(buf, MAX_EVENTS, "event");
        BattleEvent.Type[] types = BattleEvent.Type.values();
        for (int i = 0; i < count; i++) {
            int ordinal = buf.readVarInt();
            int slot = buf.readVarInt();
            if (ordinal < 0 || ordinal >= types.length) throw new IllegalArgumentException("Invalid battle event type");
            events.add(new BattleEvent(types[ordinal], slot));
        }
        return new BattleSnapshotPacket(menuId, key, lanes, depth, enemies, menuSlots, events);
    }

    public static void handle(BattleSnapshotPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player != null) BattleClientCache.update(player.containerMenu.containerId, packet);
        });
    }

    public static UUID enemyId(EnemyState state) {
        return new UUID(state.uuidMost(), state.uuidLeast());
    }
}
