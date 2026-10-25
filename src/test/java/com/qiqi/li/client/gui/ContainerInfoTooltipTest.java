package com.qiqi.li.client.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import com.qiqi.li.living.container.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

class ContainerInfoTooltipTest {
    private static ContainerMenuInfo info(ContainerLayout.Source source) {
        return new ContainerMenuInfo(ContainerMenuInfo.Status.AVAILABLE, ContainerKind.WORLD_BLOCK,
            10, new ContainerLayout(3, source), "test:container");
    }
    private static boolean hasKey(List<Component> lines, String suffix) {
        return lines.stream().anyMatch(c -> c.getContents() instanceof TranslatableContents t
            && t.getKey().equals("text.livingitem.container_info." + suffix));
    }
    @Test void ordinaryTooltip_includesGeometryButNotDebugSource() {
        var lines = ContainerInfoTooltip.lines(info(ContainerLayout.Source.RULE), false);
        assertTrue(hasKey(lines, "kind"));
        assertTrue(hasKey(lines, "size"));
        assertTrue(hasKey(lines, "layout"));
        assertFalse(hasKey(lines, "source"));
        var geometry = (TranslatableContents) lines.stream().filter(c -> c.getContents() instanceof TranslatableContents t
            && t.getKey().endsWith(".layout")).findFirst().orElseThrow().getContents();
        assertArrayEquals(new Object[] {3, 4L}, geometry.getArgs());
    }
    @Test void heuristicWarning_isVisibleWithoutAdvancedTooltips() {
        var lines = ContainerInfoTooltip.lines(info(ContainerLayout.Source.HEURISTIC), false);
        assertTrue(hasKey(lines, "heuristic"));
        assertTrue(hasKey(lines, "calibrate"));
        assertFalse(hasKey(ContainerInfoTooltip.lines(info(ContainerLayout.Source.RULE), false), "heuristic"));
    }
    @Test void unavailableDoesNotClaimNonHost() {
        var lines = ContainerInfoTooltip.lines(ContainerMenuInfo.UNAVAILABLE, true);
        assertTrue(hasKey(lines, "unavailable"));
        assertFalse(hasKey(lines, "non_host"));
        assertFalse(hasKey(lines, "layout"));
    }
    @Test void nonHostDoesNotInventGeometry() {
        var lines = ContainerInfoTooltip.lines(ContainerMenuInfo.NON_HOST, false);
        assertTrue(hasKey(lines, "non_host"));
        assertFalse(hasKey(lines, "unavailable"));
        assertFalse(hasKey(lines, "size"));
    }
    @Test void advancedTooltip_includesSourceAndTypeId() {
        var lines = ContainerInfoTooltip.lines(info(ContainerLayout.Source.LIVE), true);
        assertTrue(hasKey(lines, "source"));
        assertTrue(lines.stream().anyMatch(c -> c.getString().equals("test:container")));
    }
}
