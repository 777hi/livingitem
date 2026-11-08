package com.qiqi.li.client.render.tlm;

import com.github.tartaricacid.touhoulittlemaid.client.animation.script.GlWrapper;
import com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.client.sound.data.MaidSoundInstance;
import com.github.tartaricacid.touhoulittlemaid.compat.ysm.YsmCompat;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitDataComponent;
import com.github.tartaricacid.touhoulittlemaid.init.InitSounds;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.living.compat.tlm.TlmSoulData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Objects;

import static com.github.tartaricacid.touhoulittlemaid.client.event.SpecialMaidRenderEvent.EASTER_EGG_MODEL;

/** 不入世界、不读取任务/背包、不完整 tick 的单枚显示对象。 */
final class SoulSlabMaidPreview {
    private final EntityMaid maid;
    private final float scale;
    private final SoulSlabPreviewFacing facing = new SoulSlabPreviewFacing();
    private MaidSoundInstance sound;
    private int soundDelay;
    private int walkTicks;
    private float fromX, fromY;
    private boolean disabled;

    SoulSlabMaidPreview(ItemStack stack, ClientLevel level) {
        if (TlmSoulData.readInputs(stack).isEmpty()) throw new IllegalArgumentException("Invalid maid_info snapshot");
        CompoundTag tag = Objects.requireNonNull(stack.get(InitDataComponent.MAID_INFO)).copyTag();
        if (!tag.contains(EntityMaid.MODEL_ID_TAG, Tag.TAG_STRING)) {
            throw new IllegalArgumentException("Missing maid model_id");
        }
        String model = tag.getString(EntityMaid.MODEL_ID_TAG);
        if (ResourceLocation.tryParse(model) == null) throw new IllegalArgumentException("Invalid maid model_id");
        var info = CustomPackLoader.MAID_MODELS.getInfo(model)
                .orElseThrow(() -> new IllegalArgumentException("Unavailable maid model: " + model));
        scale = 8 * info.getRenderItemScale();
        if (!Float.isFinite(scale) || scale <= 0) throw new IllegalArgumentException("Invalid maid preview scale");
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
            soundDelay = nextSoundDelay();
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

    void move(int oldX, int oldY, int x, int y) {
        fromX = oldX;
        fromY = oldY;
        walkTicks = 6;
        facing.move(oldX, oldY, x, y);
    }

    void tick(boolean visible) {
        if (disabled) return;
        maid.tickCount++;
        facing.tick();
        maid.walkAnimation.update(walkTicks > 0 ? 0.5f : 0, 0.4f);
        if (walkTicks > 0) walkTicks--;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) maid.setPos(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        if (!visible) {
            stopSound();
            return;
        }
        if (sound != null && !mc.getSoundManager().isActive(sound)) sound = null;
        if (--soundDelay <= 0) {
            soundDelay = nextSoundDelay();
            if (sound == null) {
                sound = new MaidSoundInstance(InitSounds.MAID_IDLE.get(), maid.getSoundPackId(), maid);
                mc.getSoundManager().play(sound);
            }
        }
    }

    private int nextSoundDelay() { return 200 + maid.getRandom().nextInt(401); }

    float displayX(int x, float partial) {
        return x + (fromX - x) * Math.max(0, walkTicks - partial) / 6;
    }

    float displayY(int y, float partial) {
        return y + (fromY - y) * Math.max(0, walkTicks - partial) / 6;
    }

    void render(GuiGraphics graphics, PoseStack origin, int x, int y, int height, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        float px = displayX(x, partial);
        float py = displayY(y, partial);
        float displayScale = scale * height / 16;
        Vector3f head = origin.last().pose().transformPosition(
                new Vector3f(px + 8, py + height - maid.getEyeHeight() * displayScale, -50));
        facing.look(head.x(), head.y(), mouseX, mouseY, partial);
        applyFacing();
        PoseStack pose = new PoseStack();
        pose.last().pose().set(origin.last().pose());
        pose.last().normal().set(origin.last().normal());
        pose.translate(px + 8, py + height, -50);
        pose.scale(displayScale, displayScale, -displayScale);
        pose.mulPose(new Quaternionf().rotateZ((float) Math.PI));

        // 独立 buffer/pose 防止第三方绘制异常留下未完成的 GUI 批次。
        try (ByteBufferBuilder memory = new ByteBufferBuilder(262144)) {
            MultiBufferSource.BufferSource buffer = MultiBufferSource.immediate(memory);
            Lighting.setupForEntityInInventory();
            RenderSystem.enableDepthTest();
            var renderer = mc.getEntityRenderDispatcher().getRenderer(maid);
            if (renderer == null) throw new IllegalStateException("Missing maid renderer");
            // 复用 TLM 原 renderer 的 Bedrock/Gecko/YSM 分派，不调用世界影子/火焰/碰撞箱。
            renderer.render(maid, 0, partial, pose, buffer, 0xf000f0);
            buffer.endBatch();
        } finally {
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
        stopSound();
        maid.discard();
    }

    private void stopSound() {
        if (sound != null) {
            Minecraft.getInstance().getSoundManager().stop(sound);
            sound = null;
        }
    }
}
