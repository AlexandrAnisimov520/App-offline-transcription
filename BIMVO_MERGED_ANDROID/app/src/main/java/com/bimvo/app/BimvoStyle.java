package com.bimvo.app;

import android.graphics.Color;

final class BimvoStyle {
    private BimvoStyle() { }

    static final int BG       = Color.rgb(11, 12, 17);
    static final int CARD     = Color.rgb(21, 22, 29);
    static final int BORDER   = Color.rgb(50, 51, 64);
    static final int WHITE    = Color.rgb(243, 242, 243);
    static final int MUTED    = Color.rgb(168, 167, 179);
    static final int DIM      = Color.rgb(125, 125, 140);
    static final int PURPLE   = Color.rgb(156, 140, 189);
    static final int TEAL     = Color.rgb(110, 182, 179);
    static final int SALMON   = Color.rgb(202, 142, 136);
    static final int TRACK    = Color.rgb(59, 59, 73);

    private static final int[] SPEAKERS = {PURPLE, TEAL, SALMON, Color.rgb(178, 166, 126)};
    static int speakerColor(String id) {
        int index = id.hashCode();
        if (id.startsWith("speaker_")) {
            try { index = Integer.parseInt(id.substring(8)) - 1; }
            catch (NumberFormatException ignored) { }
        }
        return SPEAKERS[Math.floorMod(index, SPEAKERS.length)];
    }
}
