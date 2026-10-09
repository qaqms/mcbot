package com.neko.mcbot.ui;

/** Coordinates are Minecraft GUI pixels, not physical window pixels. */
record PanelLayout(Rect header, Rect tabs, Rect body, Rect actions, Rect input, Rect status) {
    static final int GAP = 6;
    static final int CONTROL_HEIGHT = 20;
    static final int FIELD_ROW = 40;

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

    static PanelLayout at(int width, int height) {
        int contentWidth = Math.min(900, Math.max(1, width - 16));
        int x = (width - contentWidth) / 2;
        return new PanelLayout(
                new Rect(x, 8, contentWidth, 12),
                new Rect(x, 28, contentWidth, CONTROL_HEIGHT),
                new Rect(x, 56, contentWidth, Math.max(1, height - 132)),
                new Rect(x, height - 70, contentWidth, CONTROL_HEIGHT),
                new Rect(x, height - 44, contentWidth, CONTROL_HEIGHT),
                new Rect(x, height - 16, contentWidth, 10));
    }

    Rect formField(int row, int scroll) {
        int fieldWidth = Math.min(560, body.width - 12);
        return new Rect(body.x + 4, body.y + row * FIELD_ROW + 12 - scroll,
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
