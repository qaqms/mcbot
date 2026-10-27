package com.neko.mcbot.ui;

import java.util.ArrayList;
import java.util.List;

/** Original pixel geometry; shares the bubble's light, depth, and opacity. */
final class TaskBubbleCat {
    static final int WIDTH = 28, HEIGHT = 23;
    record Pixel(int x, int y, int width, int height, int color) {}
    private static final List<List<Pixel>> FRAMES = java.util.stream.IntStream.range(0, 5)
            .mapToObj(TaskBubbleCat::draw).toList();
    private TaskBubbleCat() {}

    static List<Pixel> pixels(int frame) { return FRAMES.get(Math.floorMod(frame, FRAMES.size())); }

    private static List<Pixel> draw(int frame) {
        var p = new ArrayList<Pixel>();
        int outline = 0x465861, fur = 0xB1C5CD, light = 0xD5E2E5, shade = 0x8DA5B0, pink = 0xD99AAB;
        add(p, 23, 9, 4, 2, outline);
        add(p, 25, 11, 3, 7, outline);
        add(p, 22, 17, 5, 3, outline);
        add(p, 24, 11, 2, 6, fur);
        add(p, 22, 17, 3, 2, fur);
        add(p, 12, 9, 11, 2, outline);
        add(p, 10, 11, 15, 9, outline);
        add(p, 12, 19, 11, 2, outline);
        add(p, 12, 11, 10, 8, fur);
        add(p, 11, 13, 13, 5, fur);
        add(p, 17, 12, 5, 2, light);
        add(p, 18, 17, 5, 2, shade);
        add(p, 13, 20, 7, 2, outline);
        add(p, 14, 19, 5, 2, light);

        int dip = frame == 2 || frame == 3 ? 1 : 0;
        add(p, 2, 1 + dip, 3, 6, outline);
        add(p, 10, 1 + dip, 3, 6, outline);
        add(p, 3, 3 + dip, 2, 4, pink);
        add(p, 10, 3 + dip, 2, 4, pink);
        add(p, 4, 4 + dip, 7, 2, outline);
        add(p, 1, 6 + dip, 13, 7, outline);
        add(p, 3, 13 + dip, 9, 2, outline);
        add(p, 3, 6 + dip, 9, 7, light);
        add(p, 2, 7 + dip, 11, 4, fur);
        add(p, 3, 6 + dip, 3, 2, shade);
        add(p, 10, 6 + dip, 2, 2, shade);
        if (frame == 0 || frame == 1) {
            add(p, 4, 9 + dip, 1, 2, outline);
            add(p, 10, 9 + dip, 1, 2, outline);
        } else {
            add(p, 3, 10 + dip, 3, 1, outline);
            add(p, 9, 10 + dip, 3, 1, outline);
        }
        add(p, 7, 11 + dip, 2, 1, pink);
        add(p, 7, 12 + dip, 1, 1, outline);
        if (frame == 0 || frame == 4) {
            add(p, 5, 17, 7, 3, outline);
            add(p, 6, 17, 5, 2, light);
        } else {
            add(p, 9, 13, 4, 6, outline);
            add(p, 8, 13, 3, 3, outline);
            add(p, 9, 13, 2, 3, light);
            add(p, 10, 16, 2, 2, fur);
            if (frame == 3) add(p, 8, 13 + dip, 2, 2, pink);
        }
        return List.copyOf(p);
    }

    private static void add(List<Pixel> pixels, int x, int y, int width, int height, int color) {
        pixels.add(new Pixel(x, y, width, height, color));
    }
}
