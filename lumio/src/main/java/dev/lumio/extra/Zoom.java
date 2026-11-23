package dev.lumio.extra;

import dev.lumio.Config;
import dev.lumio.ui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Zoom: while the key is held the view narrows smoothly to four times; the mouse wheel zooms further
 * in or out (instead of changing the hotbar slot), and the mouse turns slower to match, so aiming
 * stays calm. Letting go glides back.
 */
public final class Zoom {
    private static final double START = 4;
    private static boolean held;
    private static double level = START;
    private static double shown = 1;
    private static long last = System.nanoTime();
    /** when the wheel last changed the level: the "4.0×" shows a moment */
    private static long changedAt;

    private Zoom() {}

    /** From the key, every tick. Each press starts at four times again. */
    public static void setHeld(boolean h) {
        if (h && !held) level = START;
        held = h;
    }

    public static boolean active() {
        return held && Config.get().zoom;
    }

    /** The field of view for the world (not for the hand), gliding to the zoom level. */
    public static float fov(float fov) {
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - last) / 1e9);
        last = now;
        double target = active() ? level : 1;
        shown += (target - shown) * (1 - Math.exp(-dt * 14));
        if (Math.abs(shown - target) < 0.002) shown = target;
        return shown == 1 ? fov : (float) (fov / shown);
    }

    /** How much slower the mouse turns: as much as the view is narrowed. */
    public static double mouseFactor() {
        return shown <= 1.001 ? 1 : 1 / shown;
    }

    /** The mouse wheel while zooming: further in or out. True when it was used for this. */
    public static boolean scroll(double dy) {
        if (!active() || dy == 0 || Minecraft.getInstance().screen != null) return false;
        level = Math.max(1.5, Math.min(50, level * (dy > 0 ? 1.25 : 0.8)));
        changedAt = System.currentTimeMillis();
        return true;
    }

    /** A small "4.0×" under the crosshair after the wheel changed it. */
    public static void render(GuiGraphics g) {
        long since = System.currentTimeMillis() - changedAt;
        if (!active() || since > 1200) return;
        float a = since < 900 ? 1 : 1 - (since - 900) / 300f;
        Component t = Draw.bold(Component.literal(String.format(java.util.Locale.ROOT, "%.1f×", level)));
        int w = Draw.width(t);
        int x = g.guiWidth() / 2 - w / 2, y = g.guiHeight() / 2 + 14;
        Draw.round(g, x - 6, y - 4, w + 12, 16, Draw.fade(0xB4121218, a));
        Draw.text(g, t, x, y, Draw.fade(Draw.TEXT, a));
    }
}
