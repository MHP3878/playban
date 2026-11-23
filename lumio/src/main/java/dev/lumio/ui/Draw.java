package dev.lumio.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Small drawing helpers for Lumio's look: soft cards with rounded corners and a shadow, gradient bars,
 * colours with fading, and easing for animations. Everything goes through GuiGraphics#fill, which
 * exists the same way in every supported Minecraft version.
 */
public final class Draw {
    // the Lumio palette: dark glass, a violet-to-sky accent, soft white text
    public static final int BG = 0xF0141419;
    public static final int BG_SOFT = 0xFF22222C;
    public static final int BORDER = 0x22FFFFFF;
    public static final int SHADOW = 0x55000000;
    public static final int ACCENT = 0xFF8B7CF6;
    public static final int ACCENT_2 = 0xFF5EB8FF;
    public static final int TEXT = 0xFFF4F4F8;
    public static final int MUTED = 0xFFA3A3B5;
    public static final int SUCCESS = 0xFF4ADE80;
    public static final int WARN = 0xFFFBBF24;
    public static final int DANGER = 0xFFF87171;

    private Draw() {}

    /** The colour with its alpha scaled by a (0..1). */
    public static int fade(int argb, float a) {
        int alpha = Math.round(((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, a)));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /** Text below this alpha is skipped: Minecraft would draw almost transparent text fully opaque. */
    public static boolean visible(float a) {
        return a > 0.08f;
    }

    public static float easeOutCubic(float t) {
        float u = 1 - Math.max(0, Math.min(1, t));
        return 1 - u * u * u;
    }

    /** A filled rectangle with corners rounded by two pixels; the pieces never overlap, so alpha stays even. */
    public static void round(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (w < 4 || h < 4) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x + 2, y, x + w - 2, y + h, color);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, color);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, color);
        g.fill(x, y + 2, x + 1, y + h - 2, color);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, color);
    }

    /** A card: soft shadow, dark glass, a thin light edge. */
    public static void card(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        round(g, x - 1, y + 1, w + 2, h + 2, fade(SHADOW, alpha * 0.6f));
        round(g, x - 2, y + 2, w + 4, h + 3, fade(SHADOW, alpha * 0.25f));
        round(g, x, y, w, h, fade(BORDER, alpha));
        round(g, x + 1, y + 1, w - 2, h - 2, fade(BG, alpha));
    }

    /** A vertical accent stripe in the Lumio gradient. */
    public static void stripe(GuiGraphics g, int x, int y, int h, int top, int bottom, float alpha) {
        g.fillGradient(x, y, x + 2, y + h, fade(top, alpha), fade(bottom, alpha));
    }

    /** A horizontal bar from left to right in two colours (drawn in small steps). */
    public static void bar(GuiGraphics g, int x, int y, int w, int h, int from, int to, float alpha) {
        int steps = Math.max(1, Math.min(w, 24));
        for (int i = 0; i < steps; i++) {
            int x1 = x + w * i / steps;
            int x2 = x + w * (i + 1) / steps;
            g.fill(x1, y, x2, y + h, fade(mix(from, to, steps == 1 ? 0 : i / (float) (steps - 1)), alpha));
        }
    }

    public static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int gr = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        int al = (int) (((a >>> 24) & 0xFF) + (((b >>> 24) & 0xFF) - ((a >>> 24) & 0xFF)) * t);
        return (al << 24) | (r << 16) | (gr << 8) | bl;
    }
}
