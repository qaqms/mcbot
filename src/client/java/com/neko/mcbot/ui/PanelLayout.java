package com.neko.mcbot.ui;

/** Coordinates are Minecraft GUI pixels, not physical window pixels. */
record PanelLayout(Rect frame, Rect rail, Rect header, Rect tabs, Rect body, Rect actions, Rect input, Rect status) {
    static final int GAP = 6;
    static final int CONTROL_HEIGHT = 20;
    static final int FIELD_ROW = 42;

    record Rect(int x, int y, int width, int height) {
        int right() { return x + width; }
        int bottom() { return y + height; }
        boolean contains(double px, double py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }
        boolean contains(Rect other) {
            return other.x >= x && other.y >= y && other.right() <= right() && other.bottom() <= bottom();
        }
        Rect column(int index, int count) {
            int available = width - GAP * (count - 1);
            int left = available * index / count + GAP * index;
            int right = available * (index + 1) / count + GAP * index;
            return new Rect(x + left, y, right - left, height);
        }
    }

    boolean sidebar() { return rail.width() > 0; }

    static Rect windowBounds(int width, int height, int maximumWidth, int maximumHeight) {
        int w = Math.min(maximumWidth, Math.min(width - 16, Math.max(216, width * 4 / 5)));
        int h = Math.min(maximumHeight, Math.min(height - 16, Math.max(164, height * 5 / 6)));
        return new Rect((width - w) / 2, (height - h) / 2, w, h);
    }

    static PanelLayout at(int width, int height) {
        var frame = windowBounds(width, height, 760, 460);
        int rail = frame.width() >= 480 ? 112 : frame.width() >= 360 ? 88 : 0;
        int padding = frame.width() >= 640 ? 24 : 10;
        int contentWidth = Math.max(1, frame.width() - rail - padding * 2);
        int x = frame.x() + rail + (frame.width() - rail - contentWidth) / 2;
        int top = frame.y() + (rail == 0 ? (frame.height() < 200 ? 62 : 66) : 46);
        return new PanelLayout(frame, new Rect(frame.x(), frame.y(), rail, frame.height()),
                new Rect(frame.x() + rail + 10, frame.y() + 8, Math.max(1, frame.width() - rail - 20), 28),
                rail == 0 ? new Rect(x, frame.y() + 40, contentWidth, CONTROL_HEIGHT)
                        : new Rect(frame.x() + 8, frame.y() + 80, rail - 16, 72),
                new Rect(x, top, contentWidth, Math.max(1, frame.bottom() - top - 82)),
                new Rect(x, frame.bottom() - 76, contentWidth, CONTROL_HEIGHT),
                new Rect(x, frame.bottom() - 50, contentWidth, 34),
                new Rect(x, frame.bottom() - 12, contentWidth, 10));
    }

    Rect nav(int index) {
        return sidebar() ? new Rect(tabs.x(), tabs.y() + index * 24, tabs.width(), CONTROL_HEIGHT)
                : tabs.column(index, 3);
    }

    Rect formField(int row, int scroll) {
        int fieldWidth = Math.min(400, body.width - 8);
        return new Rect(body.x + 4, body.y + row * FIELD_ROW + 16 - scroll,
                fieldWidth, CONTROL_HEIGHT);
    }

    int maxFormScroll(int contentHeight) {
        return Math.max(0, contentHeight - body.height);
    }

    static Rect scrollThumb(Rect viewport, int offset, int maximum) {
        int height = Math.min(viewport.height,
                Math.max(12, viewport.height * viewport.height / (viewport.height + Math.max(0, maximum))));
        int top = viewport.y + (maximum <= 0 ? 0
                : (viewport.height - height) * Math.max(0, Math.min(offset, maximum)) / maximum);
        return new Rect(viewport.right() - 3, top, 2, height);
    }
}
