package dev.lumio.ui;

import dev.lumio.Theme;
import dev.lumio.compat.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Lumio's look: soft cards with smooth rounded corners and a real shadow (high-resolution nine-slice
 * sprites, so nothing is pixelated), the Inter font, gradients, fading and easing.
 */
public final class Draw {
    // dark glass, soft white text; the accent comes from the theme
    public static final int BG = 0xF0121218;
    public static final int BG_SOFT = 0xFF23232D;
    public static final int BG_HOVER = 0xFF2D2D3A;
    public static final int BORDER = 0x26FFFFFF;
    public static final int TEXT = 0xFFF4F4F8;
    public static final int MUTED = 0xFFA1A1B5;
    public static final int FAINT = 0xFF6B6B80;
    public static final int SUCCESS = 0xFF4ADE80;
    public static final int WARN = 0xFFFBBF24;
    public static final int DANGER = 0xFFF87171;

    private Draw() {}

    public static int accent() {
        return Theme.current().accent();
    }

    public static int accent2() {
        return Theme.current().accent2();
    }

    // ---------------------------------------------------------------- colours and motion

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

    public static int mix(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int gr = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        int al = (int) (((a >>> 24) & 0xFF) + (((b >>> 24) & 0xFF) - ((a >>> 24) & 0xFF)) * t);
        return (al << 24) | (r << 16) | (gr << 8) | bl;
    }

    /** Moves a value a step towards a target: for smooth hover and slide animations. */
    public static float approach(float now, float target, float speed) {
        return now + (target - now) * Math.min(1, speed);
    }

    // ---------------------------------------------------------------- shapes

    /** A rounded rectangle: radius 8 when there is room, else 4, else a plain rectangle. */
    public static void round(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0 || ((color >>> 24) & 0xFF) == 0) return;
        if (w >= 16 && h >= 16) Compat.sprite(g, "round8", x, y, w, h, color);
        else if (w >= 8 && h >= 8) Compat.sprite(g, "round4", x, y, w, h, color);
        else g.fill(x, y, x + w, y + h, color);
    }

    /** A thin rounded outline. */
    public static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (w >= 16 && h >= 16) Compat.sprite(g, "outline8", x, y, w, h, color);
        else if (w >= 8 && h >= 8) Compat.sprite(g, "outline4", x, y, w, h, color);
    }

    public static void circle(GuiGraphics g, int cx, int cy, int r, int color) {
        Compat.sprite(g, "circle", cx - r, cy - r, r * 2, r * 2, color);
    }

    public static void ring(GuiGraphics g, int cx, int cy, int r, int color) {
        Compat.sprite(g, "ring", cx - r, cy - r, r * 2, r * 2, color);
    }

    /** A colour that slowly moves between the theme's two colours: for living edges and glows. */
    public static int wave(float offset) {
        double t = (System.currentTimeMillis() % 4000L) / 4000.0 * Math.PI * 2;
        return mix(accent(), accent2(), (float) (0.5 + 0.5 * Math.sin(t + offset)));
    }

    /** A soft glow in the theme colour around a box (it breathes a little). */
    public static void glow(GuiGraphics g, int x, int y, int w, int h, float a) {
        float pulse = 0.75f + 0.25f * (float) Math.sin((System.currentTimeMillis() % 3000L) / 3000.0 * Math.PI * 2);
        Compat.sprite(g, "shadow", x - 12, y - 9, w + 24, h + 24, fade(mix(wave(0), 0xFF000000, 0.15f), 0.55f * a * pulse));
    }

    /** A thin edge that runs in the theme's colours. */
    public static void liveOutline(GuiGraphics g, int x, int y, int w, int h, float a) {
        if (w >= 16 && h >= 16) Compat.sprite(g, "outline8", x, y, w, h, fade(wave(0), 0.9f * a));
        else if (w >= 8 && h >= 8) Compat.sprite(g, "outline4", x, y, w, h, fade(wave(0), 0.9f * a));
    }

    /** Text whose letters run through the theme's colours. Returns its width. */
    public static int gradientText(GuiGraphics g, String text, int x, int y, float a, boolean bold) {
        int cx = x;
        double t = (System.currentTimeMillis() % 3000L) / 3000.0 * Math.PI * 2;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            Component c = bold ? bold(ch) : inter(ch);
            int col = mix(accent(), accent2(), (float) (0.5 + 0.5 * Math.sin(t - i * 0.45)));
            text(g, c, cx, y, fade(col, a));
            cx += width(c);
        }
        return cx - x;
    }

    /** A soft shadow under a card. */
    public static void shadow(GuiGraphics g, int x, int y, int w, int h, float a) {
        Compat.sprite(g, "shadow", x - 12, y - 9, w + 24, h + 24, fade(0xB0000000, a));
    }

    /** A card: soft shadow, dark glass, a thin light edge. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h, float a) {
        shadow(g, x, y, w, h, a);
        round(g, x, y, w, h, fade(BG, a));
        outline(g, x, y, w, h, fade(BORDER, a));
    }

    /** A vertical accent stripe in the theme's gradient. */
    public static void stripe(GuiGraphics g, int x, int y, int h, float a) {
        g.fillGradient(x, y, x + 2, y + h, fade(accent(), a), fade(accent2(), a));
    }

    /** A horizontal bar in the theme's gradient (drawn in small steps). */
    public static void bar(GuiGraphics g, int x, int y, int w, int h, float a) {
        bar(g, x, y, w, h, accent(), accent2(), a);
    }

    public static void bar(GuiGraphics g, int x, int y, int w, int h, int from, int to, float a) {
        if (w <= 0) return;
        int steps = Math.max(1, Math.min(w, 32));
        for (int i = 0; i < steps; i++) {
            int x1 = x + w * i / steps;
            int x2 = x + w * (i + 1) / steps;
            g.fill(x1, y, x2, y + h, fade(mix(from, to, steps == 1 ? 0 : i / (float) (steps - 1)), a));
        }
    }

    // ---------------------------------------------------------------- text in Inter

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    public static MutableComponent inter(Component c) {
        return c.copy().withStyle(s -> Compat.font(s, "inter"));
    }

    public static MutableComponent bold(Component c) {
        return c.copy().withStyle(s -> Compat.font(s, "inter_bold"));
    }

    public static MutableComponent inter(String s) {
        return inter(Component.literal(s));
    }

    public static MutableComponent bold(String s) {
        return bold(Component.literal(s));
    }

    public static int width(Component c) {
        return font().width(c);
    }

    public static void text(GuiGraphics g, Component c, int x, int y, int color) {
        if (((color >>> 24) & 0xFF) < 0x14) return;
        g.drawString(font(), c, x, y, color, false);
    }

    public static void text(GuiGraphics g, FormattedCharSequence c, int x, int y, int color) {
        if (((color >>> 24) & 0xFF) < 0x14) return;
        g.drawString(font(), c, x, y, color, false);
    }

    public static void centered(GuiGraphics g, Component c, int cx, int y, int color) {
        text(g, c, cx - width(c) / 2, y, color);
    }

    /** Text broken into lines that fit the width, at most max lines. */
    public static List<FormattedCharSequence> wrap(Component c, int width, int max) {
        List<FormattedCharSequence> lines = font().split(c, width);
        return lines.size() > max ? lines.subList(0, max) : lines;
    }
}
