package com.neko.mcbot.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PanelLayoutTest {
    @Test
    void sectionsStayInsideTheViewportAndNeverOverlap() {
        for (int width : new int[]{240, 320, 427, 640, 854, 1280, 1920}) {
            for (int height : new int[]{180, 240, 270, 360, 480, 720, 1080}) {
                PanelLayout layout = PanelLayout.at(width, height);
                var viewport = new PanelLayout.Rect(0, 0, width, height);
                List<PanelLayout.Rect> regions = List.of(layout.header(), layout.tabs(), layout.body(),
                        layout.actions(), layout.input(), layout.status());
                for (var region : regions) assertTrue(viewport.contains(region), region.toString());
                for (int i = 1; i < regions.size(); i++) {
                    assertTrue(regions.get(i - 1).bottom() <= regions.get(i).y());
                }
                for (int count : new int[]{2, 3}) {
                    for (int i = 0; i < count; i++) {
                        var control = layout.actions().column(i, count);
                        assertTrue(layout.actions().contains(control));
                        assertTrue(control.width() >= 60);
                        if (i > 0) assertTrue(layout.actions().column(i - 1, count).right() < control.x());
                    }
                }
            }
        }
    }

    @Test
    void smallWindowRequiresScrollingButCanReachEveryConfigField() {
        PanelLayout layout = PanelLayout.at(320, 240);
        int maximum = layout.maxFormScroll(4 * PanelLayout.FIELD_ROW + 8);
        assertTrue(maximum > 0);
        for (int row = 0; row < 4; row++) {
            boolean reachable = false;
            for (int scroll = 0; scroll <= maximum; scroll++) {
                reachable |= layout.body().contains(layout.formField(row, scroll));
            }
            assertTrue(reachable, "field " + row + " must be reachable");
        }
        assertFalse(layout.body().contains(layout.formField(3, 0)));
        assertTrue(layout.body().contains(layout.formField(3, maximum)));
    }

    @Test
    void wideWindowsDoNotStretchModelInputsAcrossTheWholeScreen() {
        PanelLayout layout = PanelLayout.at(1920, 1080);
        assertEquals(900, layout.body().width());
        assertEquals(560, layout.formField(0, 0).width());
        assertEquals(0, layout.maxFormScroll(168));
    }

    @Test
    void scrollbarReachesBothEndsWithoutLeavingTheViewport() {
        var viewport = PanelLayout.at(320, 240).body();
        var top = PanelLayout.scrollThumb(viewport, 0, 300);
        var bottom = PanelLayout.scrollThumb(viewport, 300, 300);
        assertEquals(viewport.y(), top.y());
        assertEquals(viewport.bottom(), bottom.bottom());
        assertTrue(viewport.contains(top));
        assertTrue(viewport.contains(bottom));
        assertEquals(bottom, PanelLayout.scrollThumb(viewport, 1000, 300));
    }
}
