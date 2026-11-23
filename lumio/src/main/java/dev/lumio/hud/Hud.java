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
    public static final HudElement MUSIC = new Elements.Music();
    public static final List<HudElement> ALL = List.of(new Elements.Watermark(), new Elements.Coords(), new Elements.Fps(), new Elements.Ping(),
            new Elements.Clock(), new Elements.Armor(), new Elements.Effects(), new Elements.Keys(), new Elements.Counter(),
            new Elements.Server(), MUSIC, new Elements.Compass(), new Elements.Speed(), new Elements.Biome(),
            new Elements.Playtime(), new Elements.Day(), new Elements.Memory());
    public static final int MARGIN = 4;

    private Hud() {}

    public static Config.HudSlot slot(HudElement e) {
        return Config.get().hud.computeIfAbsent(e.id, k -> fresh(e));
    }

    /** Space between stacked elements, in GUI units. */
    private static final int GAP = 3;

    /**
     * The starting place: the elements of each side are stacked from the top in their order, each
     * under the one before with a small gap, at the size they are drawn now, so nothing overlaps.
     * Kept as a share of the free room, so they stay neat whatever the window size.
     */
    private static Config.HudSlot fresh(HudElement e) {
        Minecraft mc = Minecraft.getInstance();
        int sh = mc.getWindow().getGuiScaledHeight();
        float k = dev.lumio.ui.Ui.scale();
        // the right side starts under Minecraft's own effect icons (two rows at the top right)
        int y = e.defaultX >= 1.0 ? 54 : MARGIN;
        for (HudElement o : ALL) {
            if (o == e) break;
            if (o.defaultX != e.defaultX) continue;
            // only those that start switched on take room, like the element itself
            if (o.defaultOn != e.defaultOn) continue;
            y += Math.round(o.height(mc, true) * k) + GAP;
        }
        // switched-off ones start below the switched-on ones of their side
        if (!e.defaultOn) {
            for (HudElement o : ALL) if (o.defaultOn && o.defaultX == e.defaultX) y += Math.round(o.height(mc, true) * k) + GAP;
        }
        int h = Math.round(e.height(mc, true) * k);
        int free = Math.max(1, sh - h - MARGIN * 2);
        return new Config.HudSlot(e.defaultOn, e.defaultX, Math.max(0, Math.min(1, (y - MARGIN) / (double) free)));
    }

    public static void reset() {
        Config.get().hud.clear();
        for (HudElement e : ALL) slot(e);
        Config.get().save();
    }

    /**
     * Once after 0.8.0: the places were made for another size, so every element is stacked anew;
     * whether it shows, its size and its look stay as they were.
     */
    static void relayoutOnce() {
        Config c = Config.get();
        if (c.layoutVersion >= 1 || Minecraft.getInstance().getWindow() == null) return;
        for (HudElement e : ALL) {
            Config.HudSlot old = c.hud.get(e.id);
            Config.HudSlot pos = fresh(e);
            if (old == null) c.hud.put(e.id, pos);
            else {
                old.x = pos.x;
                old.y = pos.y;
            }
        }
        c.layoutVersion = 1;
        c.save();
    }

    /** How big the element is drawn: its own size times Lumio's size. */
    public static float size(HudElement e) {
        return (float) slot(e).scale * dev.lumio.ui.Ui.scale();
    }

    /** Where the element stands on this screen, top-left corner in GUI units. */
    public static int[] place(HudElement e, Minecraft mc, int sw, int sh, boolean preview) {
        Config.HudSlot s = slot(e);
        float k = size(e);
        int w = Math.round(e.width(mc, preview) * k);
        int h = Math.round(e.height(mc, preview) * k);
        int x = MARGIN + (int) Math.round(Math.max(0, Math.min(1, s.x)) * Math.max(0, sw - w - MARGIN * 2));
        int y = MARGIN + (int) Math.round(Math.max(0, Math.min(1, s.y)) * Math.max(0, sh - h - MARGIN * 2));
        return new int[]{x, y, w, h};
    }

    /** The element being drawn right now: its panel and glow follow its own settings. */
    static Config.HudSlot drawing;

    public static void draw(GuiGraphics g, HudElement e, Minecraft mc, int x, int y, boolean preview, float alpha) {
        Compat.push(g, x, y, size(e));
        drawing = slot(e);
        dev.lumio.ui.Draw.px = dev.lumio.ui.Draw.lumioPx((float) drawing.scale);
        Config c = Config.get();
        // without a panel the text needs its shadow to stay readable
        dev.lumio.ui.Draw.shadow = c.hudShadow || "minimal".equals(c.hudStyle) || !drawing.bg;
        try {
            e.render(g, mc, preview, alpha);
        } finally {
            drawing = null;
            dev.lumio.ui.Draw.shadow = false;
            dev.lumio.ui.Draw.px = 0;
            Compat.pop(g);
        }
    }

    public static final List<String> STYLES = List.of("glass", "solid", "minimal");

    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.screen instanceof HudEditorScreen) return;
        relayoutOnce();
        int sw = g.guiWidth(), sh = g.guiHeight();
        for (HudElement e : ALL) {
            if (!slot(e).on || !e.active(mc)) continue;
            int[] p = place(e, mc, sw, sh, false);
            draw(g, e, mc, p[0], p[1], false, 1f);
        }
    }
}
