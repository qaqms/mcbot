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
                assertTrue(viewport.contains(layout.frame()));
                List<PanelLayout.Rect> regions = List.of(layout.header(), layout.body(),
                        layout.actions(), layout.input(), layout.status());
                for (var region : regions) assertTrue(layout.frame().contains(region), region.toString());
                assertTrue(layout.body().height() >= PanelLayout.CONTROL_HEIGHT);
                for (int i = 1; i < regions.size(); i++) {
                    assertTrue(regions.get(i - 1).bottom() <= regions.get(i).y());
                }
                assertTrue(layout.frame().contains(layout.tabs()));
                if (layout.sidebar()) {
                    assertTrue(layout.rail().contains(layout.tabs()));
                    assertTrue(layout.header().x() >= layout.rail().right());
                    assertTrue(layout.body().x() >= layout.rail().right());
                } else {
                    assertTrue(layout.header().bottom() <= layout.tabs().y());
                    assertTrue(layout.tabs().bottom() <= layout.body().y());
                }
                for (int i = 0; i < 3; i++) {
                    assertTrue(layout.tabs().contains(layout.nav(i)));
                    if (i > 0 && layout.sidebar()) assertTrue(layout.nav(i - 1).bottom() <= layout.nav(i).y());
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
        for (int width : new int[]{240, 320, 427, 640}) {
            for (int height : new int[]{180, 240, 270, 360}) {
                PanelLayout layout = PanelLayout.at(width, height);
                int maximum = layout.maxFormScroll(4 * PanelLayout.FIELD_ROW + 8);
                for (int row = 0; row < 4; row++) {
                    boolean reachable = false;
                    for (int scroll = 0; scroll <= maximum; scroll++) {
                        reachable |= layout.body().contains(layout.formField(row, scroll));
                    }
                    assertTrue(reachable, "field " + row + " must be reachable at " + width + "x" + height);
                }
            }
        }
        PanelLayout layout = PanelLayout.at(320, 240);
        assertTrue(layout.maxFormScroll(4 * PanelLayout.FIELD_ROW + 8) > 0);
        assertFalse(layout.body().contains(layout.formField(3, 0)));
    }

    @Test
    void wideWindowsDoNotStretchModelInputsAcrossTheWholeScreen() {
        PanelLayout layout = PanelLayout.at(1920, 1080);
        assertEquals(760, layout.frame().width());
        assertEquals(460, layout.frame().height());
        assertEquals(400, layout.formField(0, 0).width());
        assertEquals(0, layout.maxFormScroll(168));
    }

    @Test
    void windowsLeaveTheGameVisibleOnEverySideAndStayCentered() {
        for (int width : new int[]{240, 320, 427, 640, 854, 1280, 1920}) {
            for (int height : new int[]{180, 240, 270, 360, 480, 720, 1080}) {
                for (var frame : List.of(PanelLayout.at(width, height).frame(),
                        PanelLayout.windowBounds(width, height, 440, 320))) {
                    assertTrue(frame.x() >= 8 && frame.y() >= 8);
                    assertTrue(width - frame.right() >= 8 && height - frame.bottom() >= 8);
                    assertTrue(Math.abs(frame.x() - (width - frame.right())) <= 1);
                    assertTrue(Math.abs(frame.y() - (height - frame.bottom())) <= 1);
                    assertTrue(frame.width() >= 216 && frame.height() >= 164);
                }
            }
        }
        var normal = PanelLayout.at(640, 360).frame();
        assertTrue(normal.width() <= 640 * .8);
        assertTrue(normal.height() <= 360 * .84);
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
