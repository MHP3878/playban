package dev.lumio.ui;

import dev.lumio.Config;
import net.minecraft.client.Minecraft;

/**
 * Lumio's own size, given as screen pixels per Lumio unit and the same on every GUI scale. It only
 * takes whole and half pixels (1.5, 2, 2.5, 3): then every letter and every edge lands exactly on
 * the screen's pixel grid and is drawn 1:1, sharp, instead of being rounded between two pixels.
 */
public final class Ui {
    public static final float[] SIZES = {1.5f, 2f, 2.5f, 3f};

    private Ui() {}

    /** Screen pixels per Lumio unit (2 = "normal"). */
    public static float px() {
        float want = Config.get().uiPx;
        float best = SIZES[1];
        for (float s : SIZES) if (Math.abs(s - want) < Math.abs(best - want)) best = s;
        return best;
    }

    /** The factor Lumio draws with, on top of the GUI scale. */
    public static float scale() {
        double gui = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        return (float) (px() / gui);
    }

    public static int index() {
        float p = px();
        for (int i = 0; i < SIZES.length; i++) if (SIZES[i] == p) return i;
        return 1;
    }

    /** One size bigger or smaller. */
    public static void step(int dir) {
        set(Math.max(0, Math.min(SIZES.length - 1, index() + dir)));
    }

    public static void set(int i) {
        Config c = Config.get();
        c.uiPx = SIZES[Math.max(0, Math.min(SIZES.length - 1, i))];
        c.save();
    }

    /** "compact", "normal", "large", "xlarge": for the menu's text. */
    public static String sizeKey() {
        return "lumio.size." + new String[]{"compact", "normal", "large", "xlarge"}[index()];
    }

    /** Kept for older callers: the size as a percentage of "normal". */
    public static int percent() {
        return Math.round(px() / 2f * 100);
    }
}
