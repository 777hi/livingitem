package com.qiqi.li.client.icon;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.qiqi.li.living.domain.redstone.LivingObserverData;
import com.qiqi.li.living.model.Pos2D;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

class DirectionalLivingModelTest {

    @Test
    void rotationTableIsPinnedForInGameTuning() {
        // 家族 GUI 视觉角：基准 = 图标内容朝上，GUI 屏面 y 向下 ⇒ 正角视觉顺时针。
        assertEquals(0, DirectionalLivingModel.rotationOf(Pos2D.UP));
        assertEquals(90, DirectionalLivingModel.rotationOf(Pos2D.RIGHT));
        assertEquals(180, DirectionalLivingModel.rotationOf(Pos2D.DOWN));
        assertEquals(-90, DirectionalLivingModel.rotationOf(Pos2D.LEFT));
    }

    @Test
    void nonCardinalDirectionFallsBackToUpInsteadOfCrashing() {
        assertEquals(0, DirectionalLivingModel.rotationOf(Pos2D.UP_RIGHT),
            "对角方向落兜底（组件层已拦，防御性兜底只防渲染崩溃）");
    }

    @Test
    void modelPipelineNegationMatchesLegacyFamilyTable() {
        // 物品渲染管线在 applyTransform 之后含 y 翻转 ⇒ TorchRenderState 施加相反数；
        // 相反数表必须与既有火把家族实机验证值逐项一致（UP=0 / RIGHT=-90 / DOWN=180 / LEFT=90）。
        assertEquals(0, -DirectionalLivingModel.rotationOf(Pos2D.UP));
        assertEquals(-90, -DirectionalLivingModel.rotationOf(Pos2D.RIGHT));
        assertEquals(180, Math.floorMod(-DirectionalLivingModel.rotationOf(Pos2D.DOWN), 360));
        assertEquals(90, -DirectionalLivingModel.rotationOf(Pos2D.LEFT));
    }

    @Test
    void observerDirectionUsesObserverDataInsteadOfTorchFallback() {
        ItemStack observer = new ItemStack(Items.OBSERVER);
        LivingObserverData.set(observer, LivingObserverData.DEFAULT.withDirection(Pos2D.RIGHT));

        LivingIconSpec spec = LivingIconRegistry.DirectionalSpecs.create().stream()
            .filter(value -> value.getItem() == Items.OBSERVER).findFirst().orElseThrow();
        var overrides = new GenericLivingItemOverrides(
            org.mockito.Mockito.mock(net.minecraft.client.resources.model.BakedModel.class), spec);
        assertEquals(-90, overrides.resolveDirectionRotation(observer),
            "活侦测器必须读取自己的方向组件；落入火把兜底会恒按默认 UP 渲染");
    }

    @Test
    void observerVariantModelIsFlatAndUsesVanillaDirectionTexture() throws IOException {
        String path = "assets/living_item/models/item/observer.json";
        try (var stream = DirectionalLivingModelTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream,
                "LivingIconSpec 的 item/observer 固定解析到 living_item 命名空间；缺文件会显示黑紫 missing model");
            var json = com.google.gson.JsonParser.parseString(
                new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("minecraft:item/generated", json.get("parent").getAsString(),
                "活侦测器 GUI 图标应为平面 item/generated，不得继承原版 3D 方块模型");
            assertEquals("minecraft:block/observer_top",
                json.getAsJsonObject("textures").get("layer0").getAsString(),
                "平面图标应直接复用原版带方向箭头的 observer_top 纹理");
        }
    }
}
