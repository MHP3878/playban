package dev.lumio.hud;

import dev.lumio.Config;
import dev.lumio.compat.BaseScreen;
import dev.lumio.compat.Compat;
import dev.lumio.ui.Draw;
import dev.lumio.ui.SettingsScreen;
import dev.lumio.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The HUD editor. Every element shows sample content.
 * <ul>
 *   <li>Click an element: it is chosen, and a small window beside it sets its size, whether it shows,
 *   its panel and its glow. Drag it to move it (it snaps to edges, the middle and other elements);
 *   drag the corner at its bottom right to make it bigger or smaller.</li>
 *   <li>Right click turns an element on or off, the mouse wheel changes its size, the arrow keys move
 *   the chosen one a pixel (with Shift five).</li>
 *   <li>The bar at the top: Lumio's size for everything, snapping on or off, reset, done.</li>
 * </ul>
 */
public final class HudEditorScreen extends BaseScreen {
    private static final int SNAP = 5;
    private static final int HANDLE = 6;
    private static final int BAR_W = 360, BAR_H = 26;
    private static final int WIN_W = 156, ROW = 18;

    private HudElement selected;
    private HudElement dragging;
    private boolean resizing;
    private double offX, offY;
    private double startScale, startW, startX;
    private Integer guideX, guideY;
    private final long opened = System.currentTimeMillis();
    private long selectedAt;

    /** a clickable place of the bar or the window, in GUI units */
    private record Hit(double x, double y, double w, double h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private double barX, barY, barW, barH, winX, winY, winW, winH;

    public HudEditorScreen() {
        super(Component.translatable("lumio.menu.hud.edit"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        // only a light shade: the world stays visible, so the HUD can be placed against it
        g.fill(0, 0, width, height, 0x48000000);
    }

    private HudElement under(Minecraft mc, double mx, double my) {
        for (int i = Hud.ALL.size() - 1; i >= 0; i--) {
            HudElement e = Hud.ALL.get(i);
            int[] p = Hud.place(e, mc, width, height, true);
            if (mx >= p[0] && my >= p[1] && mx < p[0] + p[2] && my < p[1] + p[3]) return e;
        }
        return null;
    }

    private boolean onHandle(Minecraft mc, double mx, double my) {
        if (selected == null) return false;
        int[] p = Hud.place(selected, mc, width, height, true);
        return mx >= p[0] + p[2] - HANDLE + 1 && my >= p[1] + p[3] - HANDLE + 1 && mx < p[0] + p[2] + 3 && my < p[1] + p[3] + 3;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        Minecraft mc = Minecraft.getInstance();
        hits.clear();
        float a = Draw.easeOutCubic((System.currentTimeMillis() - opened) / 200f);
        // guide lines while moving
        if (dragging != null && !resizing) {
            if (guideX != null) g.fill(guideX, 0, guideX + 1, height, Draw.fade(Draw.accent(), 0.7f));
            if (guideY != null) g.fill(0, guideY, width, guideY + 1, Draw.fade(Draw.accent(), 0.7f));
        }
        HudElement hover = dragging != null ? dragging : under(mc, mx, my);
        for (HudElement e : Hud.ALL) {
            Config.HudSlot s = Hud.slot(e);
            int[] p = Hud.place(e, mc, width, height, true);
            Hud.draw(g, e, mc, p[0], p[1], true, s.on ? a : a * 0.3f);
            if (e == selected) {
                Draw.liveOutline(g, p[0] - 2, p[1] - 2, p[2] + 4, p[3] + 4, a);
                // the corner to pull
                Draw.round(g, p[0] + p[2] - HANDLE + 1, p[1] + p[3] - HANDLE + 1, HANDLE + 2, HANDLE + 2, Draw.fade(Draw.wave(0), a));
            } else if (e == hover) {
                Draw.outline(g, p[0] - 2, p[1] - 2, p[2] + 4, p[3] + 4, Draw.fade(0x80FFFFFF, a));
            }
        }
        drawBar(g, mx, my, a);
        if (selected != null) drawWindow(g, mc, mx, my, a);
    }

    /** The bar at the top: title, Lumio's size, snapping, reset, done. */
    private void drawBar(GuiGraphics g, int mx, int my, float a) {
        float s = Ui.scale();
        barW = BAR_W * s;
        barH = BAR_H * s;
        barX = (width - barW) / 2;
        barY = 6;
        float lmx = (float) ((mx - barX) / s), lmy = (float) ((my - barY) / s);
        curX = barX;
        curY = barY;
        Compat.push(g, (float) barX, (float) barY, s);
        try {
            Draw.glow(g, 0, 0, BAR_W, BAR_H, a * 0.5f);
            Draw.panel(g, 0, 0, BAR_W, BAR_H, a);
            Draw.bar(g, 12, 0, BAR_W - 24, 1, Draw.wave(0), Draw.wave(2.5f), a);
            int x = 10;
            Component title = Draw.bold(Component.translatable("lumio.menu.hud.edit"));
            Draw.text(g, title, x, 9, Draw.fade(Draw.TEXT, a));
            x += Draw.width(title) + 12;
            // Lumio's size for everything
            Component size = Draw.inter(Component.translatable("lumio.hud.editor.size"));
            Draw.text(g, size, x, 9, Draw.fade(Draw.MUTED, a));
            x += Draw.width(size) + 5;
            x = small(g, lmx, lmy, x, 5, "−", false, a, () -> Ui.step(-1)) + 3;
            Component pct = Draw.bold(Ui.percent() + " %");
            Draw.text(g, pct, x, 9, Draw.fade(Draw.TEXT, a));
            x += Draw.width(pct) + 3;
            x = small(g, lmx, lmy, x, 5, "+", false, a, () -> Ui.step(1)) + 10;
            Config c = Config.get();
            x = small(g, lmx, lmy, x, 5, Component.translatable("lumio.hud.editor.snap").getString(), c.snap, a, () -> {
                c.snap = !c.snap;
                c.save();
            }) + 6;
            x = small(g, lmx, lmy, x, 5, Component.translatable("lumio.hud.editor.reset").getString(), false, a, () -> {
                Hud.reset();
                selected = null;
            }) + 6;
            small(g, lmx, lmy, BAR_W - 10 - Draw.width(Draw.bold(Component.translatable("lumio.hud.editor.done"))) - 12, 5,
                    Component.translatable("lumio.hud.editor.done").getString(), true, a, this::onClose);
        } finally {
            Compat.pop(g);
        }
    }

    /** The small window beside the chosen element. */
    private void drawWindow(GuiGraphics g, Minecraft mc, int mx, int my, float a0) {
        Config.HudSlot slot = Hud.slot(selected);
        float a = a0 * Draw.easeOutCubic((System.currentTimeMillis() - selectedAt) / 160f);
        float s = Ui.scale();
        int[] p = Hud.place(selected, mc, width, height, true);
        int rows = 5;
        int h = 22 + rows * ROW + 26;
        winW = WIN_W * s;
        winH = h * s;
        // beside the element: right of it when there is room, else left, never off the screen
        winX = p[0] + p[2] + 8 + winW < width - 4 ? p[0] + p[2] + 8 : Math.max(4, p[0] - 8 - winW);
        winY = Math.max(barY + barH + 6, Math.min(height - winH - 4, p[1]));
        float lmx = (float) ((mx - winX) / s), lmy = (float) ((my - winY) / s);
        curX = winX;
        curY = winY;
        Compat.push(g, (float) winX, (float) winY, s);
        try {
            Draw.glow(g, 0, 0, WIN_W, h, a * 0.6f);
            Draw.panel(g, 0, 0, WIN_W, h, a);
            Draw.stripe(g, 6, 7, 10, a);
            Draw.text(g, Draw.bold(selected.name()), 13, 8, Draw.fade(Draw.TEXT, a));
            small(g, lmx, lmy, WIN_W - 18, 4, "×", false, a, () -> selected = null);
            int y = 24;
            // size
            Draw.text(g, Draw.inter(Component.translatable("lumio.hud.editor.element-size")), 10, y + 4, Draw.fade(Draw.MUTED, a));
            int bx = WIN_W - 82;
            small(g, lmx, lmy, bx, y, "−", false, a, () -> resize(slot, -0.1));
            Component pct = Draw.bold(Math.round(slot.scale * 100) + " %");
            Draw.text(g, pct, bx + 36 - Draw.width(pct) / 2, y + 4, Draw.fade(Draw.TEXT, a));
            small(g, lmx, lmy, WIN_W - 24, y, "+", false, a, () -> resize(slot, 0.1));
            y += ROW;
            y = toggleRow(g, lmx, lmy, y, "lumio.hud.editor.shown", slot.on, a, () -> slot.on = !slot.on);
            y = toggleRow(g, lmx, lmy, y, "lumio.hud.editor.panel", slot.bg, a, () -> slot.bg = !slot.bg);
            y = toggleRow(g, lmx, lmy, y, "lumio.hud.editor.glow", slot.glow, a, () -> slot.glow = !slot.glow);
            y += 4;
            int half = (WIN_W - 26) / 2;
            wide(g, lmx, lmy, 10, y, half, "lumio.hud.editor.center", a, () -> {
                slot.x = 0.5;
                Config.get().save();
            });
            wide(g, lmx, lmy, 16 + half, y, half, "lumio.hud.editor.reset-one", a, () -> {
                Config.get().hud.remove(selected.id);
                Hud.slot(selected);
                Config.get().save();
            });
        } finally {
            Compat.pop(g);
        }
    }

    private void resize(Config.HudSlot slot, double by) {
        slot.scale = Math.max(0.4, Math.min(2.5, Math.round((slot.scale + by) * 10) / 10.0));
        Config.get().save();
    }

    private int toggleRow(GuiGraphics g, float lmx, float lmy, int y, String key, boolean on, float a, Runnable change) {
        Draw.text(g, Draw.inter(Component.translatable(key)), 10, y + 4, Draw.fade(Draw.MUTED, a));
        int tx = WIN_W - 34;
        Draw.round(g, tx, y + 1, 24, 13, Draw.fade(on ? Draw.accent() : Draw.BG_HOVER, a));
        Draw.circle(g, on ? tx + 17 : tx + 7, y + 7, 4, Draw.fade(0xFFFFFFFF, a));
        hitLocal(tx - 4, y, 32, 15, () -> {
            change.run();
            Config.get().save();
        });
        return y + ROW;
    }

    /** A small button in the bar or the window (local units); returns where the next may start. */
    private int small(GuiGraphics g, float lmx, float lmy, int x, int y, String text, boolean on, float a, Runnable action) {
        Component c = Draw.bold(text);
        int w = Math.max(16, Draw.width(c) + 10);
        boolean hover = lmx >= x && lmy >= y && lmx < x + w && lmy < y + 15;
        int color = on ? Draw.mix(Draw.accent(), 0xFFFFFFFF, hover ? 0.15f : 0) : hover ? Draw.BG_HOVER : Draw.BG_SOFT;
        Draw.round(g, x, y, w, 15, Draw.fade(color, a));
        Draw.text(g, c, x + (w - Draw.width(c)) / 2, y + 4, Draw.fade(on ? 0xFF0B0B10 : Draw.TEXT, a));
        hitLocal(x, y, w, 15, action);
        return x + w;
    }

    private void wide(GuiGraphics g, float lmx, float lmy, int x, int y, int w, String key, float a, Runnable action) {
        Component c = Draw.bold(Component.translatable(key));
        boolean hover = lmx >= x && lmy >= y && lmx < x + w && lmy < y + 16;
        Draw.round(g, x, y, w, 16, Draw.fade(hover ? Draw.BG_HOVER : Draw.BG_SOFT, a));
        Draw.text(g, c, x + (w - Draw.width(c)) / 2, y + 4, Draw.fade(Draw.TEXT, a));
        hitLocal(x, y, w, 16, action);
    }

    /** Where the panel being drawn stands (GUI units), for hitLocal. */
    private double curX, curY;

    /** Remembers a clickable place given in the current panel's local units, in GUI units. */
    private void hitLocal(int x, int y, int w, int h, Runnable action) {
        float s = Ui.scale();
        hits.add(new Hit(curX + x * s, curY + y * s, w * s, h * s, action));
    }

    private boolean inPanels(double mx, double my) {
        boolean bar = mx >= barX && my >= barY && mx < barX + barW && my < barY + barH;
        boolean win = selected != null && mx >= winX && my >= winY && mx < winX + winW && my < winY + winH;
        return bar || win;
    }

    // ---------------------------------------------------------------- mouse and keys

    @Override
    protected boolean click(double mx, double my, int button) {
        Minecraft mc = Minecraft.getInstance();
        if (inPanels(mx, my)) {
            if (button == 0) for (Hit h : new ArrayList<>(hits)) if (h.contains(mx, my)) {
                h.action.run();
                break;
            }
            return true;
        }
        if (button == 0 && onHandle(mc, mx, my)) {
            int[] p = Hud.place(selected, mc, width, height, true);
            dragging = selected;
            resizing = true;
            startScale = Hud.slot(selected).scale;
            startW = p[2];
            startX = p[0];
            return true;
        }
        HudElement e = under(mc, mx, my);
        if (e == null) {
            selected = null;
            return false;
        }
        if (button == 1) {
            Hud.slot(e).on = !Hud.slot(e).on;
            Config.get().save();
            return true;
        }
        if (button == 0) {
            if (selected != e) selectedAt = System.currentTimeMillis();
            selected = e;
            int[] p = Hud.place(e, mc, width, height, true);
            dragging = e;
            resizing = false;
            offX = mx - p[0];
            offY = my - p[1];
            return true;
        }
        return false;
    }

    @Override
    protected boolean drag(double mx, double my, int button, double dx, double dy) {
        if (dragging == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (resizing) {
            double w = Math.max(10, mx - startX);
            Config.HudSlot s = Hud.slot(dragging);
            s.scale = Math.max(0.4, Math.min(2.5, Math.round(startScale * w / startW * 20) / 20.0));
            return true;
        }
        int[] p = Hud.place(dragging, mc, width, height, true);
        int w = p[2], h = p[3];
        int x = (int) Math.round(mx - offX);
        int y = (int) Math.round(my - offY);
        guideX = null;
        guideY = null;
        if (Config.get().snap) {
            // snap to the screen edges and the middle
            int left = Hud.MARGIN, right = width - Hud.MARGIN - w, cx = (width - w) / 2;
            int top = Hud.MARGIN, bottom = height - Hud.MARGIN - h, cy = (height - h) / 2;
            if (Math.abs(x - left) < SNAP) x = left;
            else if (Math.abs(x - right) < SNAP) x = right;
            else if (Math.abs(x - cx) < SNAP) {
                x = cx;
                guideX = width / 2;
            }
            if (Math.abs(y - top) < SNAP) y = top;
            else if (Math.abs(y - bottom) < SNAP) y = bottom;
            else if (Math.abs(y - cy) < SNAP) {
                y = cy;
                guideY = height / 2;
            }
            // snap to the edges of the other elements
            for (HudElement o : Hud.ALL) {
                if (o == dragging || !Hud.slot(o).on) continue;
                int[] q = Hud.place(o, mc, width, height, true);
                if (guideX == null) {
                    if (Math.abs(x - q[0]) < SNAP) { x = q[0]; guideX = q[0]; }
                    else if (Math.abs(x + w - (q[0] + q[2])) < SNAP) { x = q[0] + q[2] - w; guideX = q[0] + q[2]; }
                }
                if (guideY == null) {
                    if (Math.abs(y - (q[1] + q[3] + 2)) < SNAP) { y = q[1] + q[3] + 2; guideY = y; }
                    else if (Math.abs(y + h + 2 - q[1]) < SNAP) { y = q[1] - h - 2; guideY = q[1]; }
                }
            }
        }
        setPlace(dragging, x, y, w, h);
        return true;
    }

    private void setPlace(HudElement e, int x, int y, int w, int h) {
        Config.HudSlot s = Hud.slot(e);
        s.x = Math.max(0, Math.min(1, (x - Hud.MARGIN) / (double) Math.max(1, width - w - Hud.MARGIN * 2)));
        s.y = Math.max(0, Math.min(1, (y - Hud.MARGIN) / (double) Math.max(1, height - h - Hud.MARGIN * 2)));
    }

    @Override
    protected boolean release(double mx, double my, int button) {
        if (dragging == null) return false;
        dragging = null;
        resizing = false;
        guideX = null;
        guideY = null;
        Config.get().save();
        return true;
    }

    @Override
    protected boolean scroll(double mx, double my, double amount) {
        HudElement e = under(Minecraft.getInstance(), mx, my);
        if (e == null) return false;
        resize(Hud.slot(e), amount > 0 ? 0.1 : -0.1);
        return true;
    }

    @Override
    protected boolean key(int key, int scancode, int modifiers) {
        if (selected == null) return false;
        int step = (modifiers & 1) != 0 ? 5 : 1;
        int dx = 0, dy = 0;
        switch (key) {
            case 263 -> dx = -step; // left
            case 262 -> dx = step;  // right
            case 265 -> dy = -step; // up
            case 264 -> dy = step;  // down
            case 261 -> { // delete: hide
                Hud.slot(selected).on = false;
                Config.get().save();
                return true;
            }
            default -> {
                return false;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        int[] p = Hud.place(selected, mc, width, height, true);
        setPlace(selected, p[0] + dx, p[1] + dy, p[2], p[3]);
        Config.get().save();
        return true;
    }

    @Override
    public void onClose() {
        Config.get().save();
        Minecraft.getInstance().setScreen(new SettingsScreen(SettingsScreen.Tab.HUD));
    }
}
