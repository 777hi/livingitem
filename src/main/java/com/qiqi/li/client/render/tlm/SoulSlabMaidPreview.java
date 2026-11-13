package com.qiqi.li.client.render.tlm;

import com.github.tartaricacid.touhoulittlemaid.client.animation.script.GlWrapper;
import com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.client.sound.data.MaidSoundInstance;
import com.github.tartaricacid.touhoulittlemaid.compat.ysm.YsmCompat;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.MaidConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.EmojiChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitDataComponent;
import com.github.tartaricacid.touhoulittlemaid.init.InitSounds;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.living.compat.tlm.TlmSoulData;
import com.qiqi.li.living.config.TlmClientConfig;
import com.qiqi.li.living.domain.maid.LivingSoulSlabFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Objects;

import static com.github.tartaricacid.touhoulittlemaid.client.event.SpecialMaidRenderEvent.EASTER_EGG_MODEL;

/** 不入世界、不读取任务/背包、不完整tick的单枚显示对象。 */
final class SoulSlabMaidPreview {
    enum Display {
        SLOT(300, true), CURSOR(168, false), HOTBAR(168, false);

        final float z;
        final boolean followsMouse;
        Display(float z, boolean followsMouse) {
            this.z = z;
            this.followsMouse = followsMouse;
        }
    }

    private static final int RESPONSE_COOLDOWN_TICKS = 10;
    private final EntityMaid maid;
    private final float modelScale;
    private final SoulSlabPreviewFacing facing = new SoulSlabPreviewFacing();
    private final SoulSlabAmbientSchedule ambientSchedule = new SoulSlabAmbientSchedule();
    private final SoulSlabVoiceArbiter voices = new SoulSlabVoiceArbiter();
    private MaidSoundInstance sound;
    private SoulSlabVoiceArbiter.Request playingVoice;
    private SoundInstance feedSound;
    private int responseCooldown;
    private int walkTicks;
    private int feedTicks;
    private boolean feedSoundPending;
    private float fromX, fromY;
    private boolean disabled;

    SoulSlabMaidPreview(ItemStack stack, ClientLevel level) {
        if (!TlmSoulData.hasStoredMaid(stack)) throw new IllegalArgumentException("Invalid maid_info snapshot");
        CompoundTag tag = Objects.requireNonNull(stack.get(InitDataComponent.MAID_INFO)).copyTag();
        if (!tag.contains(EntityMaid.MODEL_ID_TAG, Tag.TAG_STRING)) {
            throw new IllegalArgumentException("Missing maid model_id");
        }
        String model = tag.getString(EntityMaid.MODEL_ID_TAG);
        if (ResourceLocation.tryParse(model) == null) throw new IllegalArgumentException("Invalid maid model_id");
        var info = CustomPackLoader.MAID_MODELS.getInfo(model)
                .orElseThrow(() -> new IllegalArgumentException("Unavailable maid model: " + model));
        modelScale = info.getRenderItemScale();
        if (!Float.isFinite(modelScale) || modelScale <= 0) throw new IllegalArgumentException("Invalid maid preview scale");
        maid = Objects.requireNonNull(EntityMaid.TYPE.create(level), "Cannot create maid preview");
        try {
            maid.setModelId(info.getEasterEgg() == null ? model : EASTER_EGG_MODEL);
            maid.setCustomName(readName(tag, "CustomName", level));
            if (tag.contains(EntityMaid.SOUND_PACK_ID_TAG, Tag.TAG_STRING)) {
                maid.setSoundPackId(tag.getString(EntityMaid.SOUND_PACK_ID_TAG));
            }
            boolean ysm = YsmCompat.isInstalled() && tag.getBoolean(EntityMaid.IS_YSM_MODEL_TAG);
            maid.setIsYsmModel(ysm);
            if (ysm) {
                if (!tag.contains(EntityMaid.YSM_MODEL_ID_TAG, Tag.TAG_STRING)
                        || !tag.contains(EntityMaid.YSM_MODEL_TEXTURE_TAG, Tag.TAG_STRING)) {
                    throw new IllegalArgumentException("Missing YSM display fields");
                }
                maid.setYsmModel(tag.getString(EntityMaid.YSM_MODEL_ID_TAG),
                        tag.getString(EntityMaid.YSM_MODEL_TEXTURE_TAG),
                        Objects.requireNonNullElse(readName(tag, EntityMaid.YSM_MODEL_NAME_TAG, level), Component.empty()));
            }
            maid.setOnGround(true);
            maid.setInSittingPose(LivingSoulSlabFunction.isSitting(stack));
            maid.getConfigManager().setChatBubbleShow(SoulSlabMaidSettings.chatBubbleShow(tag));
            maid.getConfigManager().setSoundFreq(SoulSlabMaidSettings.soundFrequency(tag));
        } catch (RuntimeException | LinkageError failure) {
            maid.discard();
            throw failure;
        }
    }

    private static Component readName(CompoundTag tag, String key, ClientLevel level) {
        if (!tag.contains(key)) return null;
        if (!tag.contains(key, Tag.TAG_STRING)) throw new IllegalArgumentException("Invalid maid name field");
        return Component.Serializer.fromJson(tag.getString(key), level.registryAccess());
    }

    boolean disabled() { return disabled; }

    /** 纯客户端悬停回应；不写物品、不触发AI，离开/不可见/清理时必须归false。 */
    void setBegging(boolean begging) {
        maid.setBegging(begging);
    }

    void setSitting(boolean sitting) {
        maid.setInSittingPose(sitting);
    }

    void move(int oldX, int oldY, int x, int y) {
        fromX = oldX;
        fromY = oldY;
        walkTicks = 6;
        facing.move(oldX, oldY, x, y);
    }

    void settle() {
        walkTicks = 0;
        facing.stopMoving();
    }

    void feed() {
        feedTicks = SoulSlabFeedPulse.DURATION_TICKS;
        feedSoundPending = true;
        voices.request(SoulSlabVoiceArbiter.Request.FEED);
    }

    void respond() {
        if (responseCooldown > 0) return;
        responseCooldown = RESPONSE_COOLDOWN_TICKS;
        voices.request(SoulSlabVoiceArbiter.Request.RESPONSE);
    }

    void tick(boolean visible) {
        if (disabled) return;
        maid.tickCount++;
        facing.tick();
        maid.walkAnimation.update(walkTicks > 0 ? 0.5f : 0, 0.4f);
        if (walkTicks > 0) walkTicks--;
        if (feedTicks > 0) feedTicks--;
        if (responseCooldown > 0) responseCooldown--;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) maid.setPos(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        tickBubbles(visible);
        if (!visible) {
            setBegging(false);
            feedSoundPending = false;
            ambientSchedule.reset();
            voices.clear();
            stopSound();
            return;
        }

        if (feedSoundPending) {
            feedSoundPending = false;
            if (feedSound != null) mc.getSoundManager().stop(feedSound);
            feedSound = SimpleSoundInstance.forUI(SoundEvents.GENERIC_EAT, 1);
            mc.getSoundManager().play(feedSound);
        }
        if (sound != null && !mc.getSoundManager().isActive(sound)) {
            sound = null;
            playingVoice = null;
        }
        if (ambientSchedule.tick(maid.getRandom().nextInt(1000))) {
            voices.request(SoulSlabVoiceArbiter.Request.AMBIENT);
        }
        playVoice(voices.take());
    }

    private void playVoice(SoulSlabVoiceArbiter.Request request) {
        if (request == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (sound != null) {
            if (playingVoice != null && request.priority <= playingVoice.priority) return;
            mc.getSoundManager().stop(sound);
            sound = null;
            playingVoice = null;
        }
        SoundEvent event = switch (request) {
            case AMBIENT -> environmentSound(mc);
            case RESPONSE -> InitSounds.MAID_IDLE.get();
            case FEED -> InitSounds.MAID_ITEM_GET.get();
        };
        sound = new MaidSoundInstance(event, maid.getSoundPackId(), maid);
        playingVoice = request;
        mc.getSoundManager().play(sound);
    }

    private SoundEvent environmentSound(Minecraft mc) {
        if (mc.level == null || mc.player == null) return InitSounds.MAID_IDLE.get();
        BlockPos pos = mc.player.blockPosition();
        var holder = mc.level.getBiome(pos);
        var biome = holder.value();
        var climate = biome.getModifiedClimateSettings();
        float localTemperature = climate.temperatureModifier().modifyTemperature(pos, climate.temperature());
        boolean hot = holder.is(BiomeTags.SNOW_GOLEM_MELTS) || localTemperature > 1.0f;
        var context = new SoulSlabEnvironmentSound.Context(mc.level.getDayTime(), mc.level.isRaining(),
                biome.getPrecipitationAt(pos), biome.coldEnoughToSnow(pos), hot);
        var sounds = new SoulSlabEnvironmentSound.Options(
                InitSounds.MAID_MORNING.get(), InitSounds.MAID_NIGHT.get(),
                InitSounds.MAID_RAIN.get(), InitSounds.MAID_SNOW.get(),
                InitSounds.MAID_COLD.get(), InitSounds.MAID_HOT.get(), InitSounds.MAID_IDLE.get());
        return SoulSlabEnvironmentSound.select(context, maid.getRandom(), sounds);
    }

    private void tickBubbles(boolean visible) {
        var manager = maid.getChatBubbleManager();
        var collection = manager.getChatBubbleDataCollection();
        if (collection.update()) manager.forceUpdateChatBubble();
        boolean enabled = MaidConfig.GLOBAL_MAID_SHOW_CHAT_BUBBLE.get()
                && maid.getConfigManager().isChatBubbleShow() && MaidConfig.ENABLE_EMOJI.get();
        if (SoulSlabPreviewBubbles.shouldGenerate(visible, enabled, collection.isEmpty(), maid.tickCount,
                maid.getUUID().getLeastSignificantBits(), MaidConfig.EMOJI_CHECK_RATE.get(),
                MaidConfig.IMAGE_EMOJI_WEIGHT.get(), MaidConfig.KAOMOJI_EMOJI_WEIGHT.get(), maid.getRandom())) {
            manager.addChatBubble(EmojiChatBubbleData.create());
        }
    }

    float displayX(int x, float partial) {
        return x + (fromX - x) * Math.max(0, walkTicks - partial) / 6;
    }

    float displayY(int y, float partial) {
        return y + (fromY - y) * Math.max(0, walkTicks - partial) / 6;
    }

    private float feedScale(float partial) {
        return SoulSlabFeedPulse.scale(feedTicks, partial);
    }

    void render(GuiGraphics graphics, PoseStack origin, int x, int y, int height,
                int mouseX, int mouseY, Display display) {
        Minecraft mc = Minecraft.getInstance();
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        float px = displayX(x, partial);
        float py = displayY(y, partial);
        float displayScale = SoulSlabPreviewScale.compose(modelScale,
                TlmClientConfig.previewScaleMultiplier(), height, feedScale(partial));
        if (!display.followsMouse) {
            setBegging(false);
            facing.centerLook();
        } else {
            Vector3f head = origin.last().pose().transformPosition(
                    new Vector3f(px + 8, py + height - maid.getEyeHeight() * displayScale, display.z));
            facing.look(head.x(), head.y(), mouseX, mouseY, partial);
        }
        applyFacing();
        PoseStack pose = new PoseStack();
        pose.last().pose().set(origin.last().pose());
        pose.last().normal().set(origin.last().normal());
        pose.translate(px + 8, py + height, display.z);
        pose.scale(displayScale, displayScale, -displayScale);
        pose.mulPose(new Quaternionf().rotateZ((float) Math.PI));

        // 气泡沿用原renderer的billboard；GUI方向与世界相机分离，结束必须还原。
        var dispatcher = mc.getEntityRenderDispatcher();
        Quaternionf previousCamera = new Quaternionf(dispatcher.cameraOrientation());
        try (ByteBufferBuilder memory = new ByteBufferBuilder(262144)) {
            MultiBufferSource.BufferSource buffer = MultiBufferSource.immediate(memory);
            Lighting.setupForEntityInInventory();
            RenderSystem.enableDepthTest();
            dispatcher.overrideCameraOrientation(new Quaternionf().rotateY((float) Math.PI));
            var renderer = dispatcher.getRenderer(maid);
            if (renderer == null) throw new IllegalStateException("Missing maid renderer");
            // 复用TLM原renderer的Bedrock/Gecko/YSM分派，不调用世界影子/火焰/碰撞箱。
            renderer.render(maid, 0, partial, pose, buffer, 0xf000f0);
            buffer.endBatch();
        } finally {
            dispatcher.overrideCameraOrientation(previousCamera);
            GlWrapper.clearPoseStack();
            Lighting.setupFor3DItems();
        }
    }

    private void applyFacing() {
        maid.yBodyRotO = facing.body(0);
        maid.yBodyRot = facing.body(1);
        maid.yRotO = maid.yBodyRotO;
        maid.setYRot(maid.yBodyRot);
        maid.yHeadRotO = maid.yBodyRotO + facing.headYaw(0);
        maid.yHeadRot = maid.yBodyRot + facing.headYaw(1);
        maid.xRotO = facing.headPitch(0);
        maid.setXRot(facing.headPitch(1));
    }

    void disable() {
        disabled = true;
        dispose();
    }

    void dispose() {
        setBegging(false);
        voices.clear();
        ambientSchedule.reset();
        stopSound();
        maid.getChatBubbleManager().getChatBubbleDataCollection().chatBubbles().clear();
        maid.discard();
    }

    private void stopSound() {
        var manager = Minecraft.getInstance().getSoundManager();
        if (sound != null) {
            manager.stop(sound);
            sound = null;
        }
        playingVoice = null;
        if (feedSound != null) {
            manager.stop(feedSound);
            feedSound = null;
        }
    }
}
