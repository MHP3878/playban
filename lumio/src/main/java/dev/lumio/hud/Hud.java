package dev.lumio.hud;

import dev.lumio.Config;
import dev.lumio.compat.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * Lumio's HUD: every element at its place. A position is kept as a share of the free room on the
 * screen (0 = left/top edge, 1 = right/bottom edge), so elements stay where they belong when the
 * window size or GUI scale changes, and never leave the screen.
 */
public final class Hud {
    public static final List<HudElement> ALL = List.of(new Elements.Coords(), new Elements.Fps(), new Elements.Ping(),
            new Elements.Clock(), new Elements.Armor(), new Elements.Effects(), new Elements.Keys(), new Elements.Counter(),
            new Elements.Server());
    public static final int MARGIN = 4;

    private Hud() {}

    public static Config.HudSlot slot(HudElement e) {
        return Config.get().hud.computeIfAbsent(e.id, k -> fresh(e));
    }

    /**
     * The starting place: left or right edge, and a distance from the top in GUI units, turned into a
     * share of this screen's free room, so the elements stack neatly whatever the window size.
     */
    private static Config.HudSlot fresh(HudElement e) {
        Minecraft mc = Minecraft.getInstance();
        int sh = mc.getWindow().getGuiScaledHeight();
        int free = Math.max(1, sh - e.height(mc, true) - MARGIN * 2);
        return new Config.HudSlot(e.defaultOn, e.defaultX, Math.max(0, Math.min(1, e.defaultY / free)));
    }

    public static void reset() {
        Config.get().hud.clear();
        for (HudElement e : ALL) slot(e);
        Config.get().save();
    }

    /** Where the element stands on this screen, top-left corner in GUI units. */
    public static int[] place(HudElement e, Minecraft mc, int sw, int sh, boolean preview) {
        Config.HudSlot s = slot(e);
        int w = Math.round(e.width(mc, preview) * (float) s.scale);
        int h = Math.round(e.height(mc, preview) * (float) s.scale);
        int x = MARGIN + (int) Math.round(Math.max(0, Math.min(1, s.x)) * Math.max(0, sw - w - MARGIN * 2));
        int y = MARGIN + (int) Math.round(Math.max(0, Math.min(1, s.y)) * Math.max(0, sh - h - MARGIN * 2));
        return new int[]{x, y, w, h};
    }

    public static void draw(GuiGraphics g, HudElement e, Minecraft mc, int x, int y, boolean preview, float alpha) {
        Compat.push(g, x, y, (float) slot(e).scale);
        e.render(g, mc, preview, alpha);
        Compat.pop(g);
    }

    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.screen instanceof HudEditorScreen) return;
        int sw = g.guiWidth(), sh = g.guiHeight();
        for (HudElement e : ALL) {
            if (!slot(e).on || !e.active(mc)) continue;
            int[] p = place(e, mc, sw, sh, false);
            draw(g, e, mc, p[0], p[1], false, 1f);
        }
    }
}
