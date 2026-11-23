package dev.lumio.ui;

import dev.lumio.Config;
import net.minecraft.client.Minecraft;

/**
 * Lumio's own size. Minecraft's GUI scale makes everything twice as big at 4 as at 2; Lumio stays the
 * same size on screen whatever it is set to, and the player chooses how big (Config.uiSize).
 */
public final class Ui {
    public static final double MIN = 0.5;
    public static final double MAX = 1.5;

    private Ui() {}

    /** The factor Lumio draws with, on top of the GUI scale. */
    public static float scale() {
        double gui = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        double size = Math.max(MIN, Math.min(MAX, Config.get().uiSize));
        return (float) Math.max(0.25, Math.min(3, 2.0 / gui * size));
    }

    /** One step bigger or smaller (in 5 % steps), kept between MIN and MAX. */
    public static void step(int dir) {
        Config c = Config.get();
        c.uiSize = Math.max(MIN, Math.min(MAX, Math.round((c.uiSize + dir * 0.05) * 100) / 100.0));
        c.save();
    }

    public static int percent() {
        return (int) Math.round(Config.get().uiSize * 100);
    }
}
