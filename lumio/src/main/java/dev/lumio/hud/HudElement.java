package dev.lumio.hud;

import dev.lumio.ui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * One element of Lumio's HUD. It knows its size (in GUI units, before scaling) and draws itself at
 * (0, 0); where it stands and how big it is comes from the settings. In the editor it draws sample
 * content, so even an empty element can be placed.
 */
public abstract class HudElement {
    public final String id;
    public final boolean defaultOn;
    /** start: 0 = left edge, 1 = right edge */
    public final double defaultX;
    /** start: distance from the top in GUI units */
    public final double defaultY;

    protected HudElement(String id, boolean defaultOn, double defaultX, double defaultY) {
        this.id = id;
        this.defaultOn = defaultOn;
        this.defaultX = defaultX;
        this.defaultY = defaultY;
    }

    public Component name() {
        return Component.translatable("lumio.hud." + id);
    }

    /** Whether there is anything to show right now (e.g. effects only while there are any). */
    public boolean active(Minecraft mc) {
        return true;
    }

    public abstract int width(Minecraft mc, boolean preview);

    public abstract int height(Minecraft mc, boolean preview);

    public abstract void render(GuiGraphics g, Minecraft mc, boolean preview, float alpha);

    // ---------------------------------------------------------------- shared look

    protected static final int PAD_X = 6;
    protected static final int PAD_Y = 5;
    protected static final int BG = 0xB4121218;

    /**
     * The element's panel, as the settings say. Glass: see-through dark with a light top edge and a
     * thin border; solid: dark; minimal: none. Then the glow and the accent stripe at the left.
     */
    protected static void back(GuiGraphics g, int w, int h, float a) {
        dev.lumio.Config.HudSlot s = Hud.drawing;
        dev.lumio.Config c = dev.lumio.Config.get();
        boolean panel = s == null || s.bg;
        if (s != null && s.glow) Draw.glow(g, 0, 0, w, h, a);
        if (panel) {
            switch (c.hudStyle) {
                case "solid" -> Draw.round(g, 0, 0, w, h, Draw.fade(BG, a));
                case "minimal" -> { }
                default -> {
                    Draw.round(g, 0, 0, w, h, Draw.fade(0x99101018, a));
                    g.fillGradient(3, 1, w - 3, Math.max(2, h / 2), Draw.fade(0x16FFFFFF, a), Draw.fade(0x00FFFFFF, a));
                    Draw.outline(g, 0, 0, w, h, Draw.fade(0x1CFFFFFF, a));
                }
            }
        }
        if (s != null && s.glow) Draw.liveOutline(g, 0, 0, w, h, a * 0.8f);
        if (c.hudAccent && h > 8) g.fillGradient(1, 3, 3, h - 3, Draw.fade(Draw.wave(0), a), Draw.fade(Draw.wave(1.6f), a));
    }

    /** A small label in the accent colour, then the value: "XYZ  12, 64, -3". */
    protected static int labelled(GuiGraphics g, Component label, Component value, int x, int y, float a) {
        Component l = Draw.bold(label);
        Draw.text(g, l, x, y, Draw.fade(Draw.accent(), a));
        int vx = x + Draw.width(l) + 5;
        Draw.text(g, Draw.inter(value), vx, y, Draw.fade(Draw.TEXT, a));
        return vx + Draw.width(Draw.inter(value));
    }

    protected static int labelledWidth(Component label, Component value) {
        return Draw.width(Draw.bold(label)) + 5 + Draw.width(Draw.inter(value));
    }
}
