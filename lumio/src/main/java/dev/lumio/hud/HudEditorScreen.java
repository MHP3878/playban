package dev.lumio.hud;

import dev.lumio.Config;
import dev.lumio.compat.BaseScreen;
import dev.lumio.ui.Draw;
import dev.lumio.ui.SettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The HUD editor: every element shows sample content and can be dragged with the mouse. It snaps to
 * the screen edges, the middle and the edges of the other elements (a thin line shows where). Right
 * click turns an element on or off, the mouse wheel changes its size.
 */
public final class HudEditorScreen extends BaseScreen {
    private static final int SNAP = 5;
    private HudElement dragging;
    private double offX, offY;
    private Integer guideX, guideY;
    private final long opened = System.currentTimeMillis();
    private int tx, ty, tw = 260, th = 56;

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
        g.fill(0, 0, width, height, 0x50000000);
    }

    private HudElement under(Minecraft mc, double mx, double my) {
        for (int i = Hud.ALL.size() - 1; i >= 0; i--) {
            HudElement e = Hud.ALL.get(i);
            int[] p = Hud.place(e, mc, width, height, true);
            if (mx >= p[0] && my >= p[1] && mx < p[0] + p[2] && my < p[1] + p[3]) return e;
        }
        return null;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        Minecraft mc = Minecraft.getInstance();
        float a = Draw.easeOutCubic((System.currentTimeMillis() - opened) / 200f);
        // guide lines while dragging
        if (dragging != null) {
            if (guideX != null) g.fill(guideX, 0, guideX + 1, height, Draw.fade(Draw.accent(), 0.7f));
            if (guideY != null) g.fill(0, guideY, width, guideY + 1, Draw.fade(Draw.accent(), 0.7f));
        }
        HudElement hover = dragging != null ? dragging : under(mc, mx, my);
        for (HudElement e : Hud.ALL) {
            Config.HudSlot s = Hud.slot(e);
            int[] p = Hud.place(e, mc, width, height, true);
            Hud.draw(g, e, mc, p[0], p[1], true, s.on ? a : a * 0.35f);
            if (e == hover) {
                Draw.outline(g, p[0] - 2, p[1] - 2, p[2] + 4, p[3] + 4, Draw.fade(Draw.accent(), a));
                Component tag = Draw.bold(e.name().copy().append(s.on ? "" : " · ").append(s.on ? Component.empty()
                        : Component.translatable("lumio.hud.off")).append("  " + Math.round(s.scale * 100) + "%"));
                int tagW = Draw.width(tag) + 10;
                int tagY = p[1] > 18 ? p[1] - 16 : p[1] + p[3] + 4;
                int tagX = Math.max(2, Math.min(width - tagW - 2, p[0]));
                Draw.round(g, tagX, tagY, tagW, 13, Draw.fade(Draw.accent(), a));
                Draw.text(g, tag, tagX + 5, tagY + 3, Draw.fade(0xFF0B0B10, a));
            }
        }
        // the toolbar: what to do, reset, done
        tx = (width - tw) / 2;
        ty = Math.max(30, height / 2 - th / 2 - 40);
        Draw.panel(g, tx, ty, tw, th, a);
        Draw.centered(g, Draw.bold(Component.translatable("lumio.menu.hud.edit")), tx + tw / 2, ty + 7, Draw.fade(Draw.TEXT, a));
        Draw.centered(g, Draw.inter(Component.translatable("lumio.hud.editor.hint")), tx + tw / 2, ty + 19, Draw.fade(Draw.MUTED, a));
        button(g, mx, my, tx + 10, ty + 32, (tw - 30) / 2, Component.translatable("lumio.hud.editor.reset"), false, a);
        button(g, mx, my, tx + 20 + (tw - 30) / 2, ty + 32, (tw - 30) / 2, Component.translatable("lumio.hud.editor.done"), true, a);
    }

    private void button(GuiGraphics g, int mx, int my, int x, int y, int w, Component text, boolean primary, float a) {
        boolean hover = mx >= x && my >= y && mx < x + w && my < y + 18;
        int color = primary ? Draw.mix(Draw.accent(), 0xFFFFFFFF, hover ? 0.15f : 0) : (hover ? Draw.BG_HOVER : Draw.BG_SOFT);
        Draw.round(g, x, y, w, 18, Draw.fade(color, a));
        Draw.centered(g, Draw.bold(text), x + w / 2, y + 5, Draw.fade(primary ? 0xFF0B0B10 : Draw.TEXT, a));
    }

    private boolean inToolbar(double mx, double my) {
        return mx >= tx && my >= ty && mx < tx + tw && my < ty + th;
    }

    @Override
    protected boolean click(double mx, double my, int button) {
        Minecraft mc = Minecraft.getInstance();
        if (inToolbar(mx, my)) {
            if (button == 0 && my >= ty + 32 && my < ty + 50) {
                if (mx < tx + tw / 2.0) Hud.reset();
                else onClose();
            }
            return true;
        }
        HudElement e = under(mc, mx, my);
        if (e == null) return false;
        if (button == 1) {
            Hud.slot(e).on = !Hud.slot(e).on;
            Config.get().save();
            return true;
        }
        if (button == 0) {
            int[] p = Hud.place(e, mc, width, height, true);
            dragging = e;
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
        int[] p = Hud.place(dragging, mc, width, height, true);
        int w = p[2], h = p[3];
        int x = (int) Math.round(mx - offX);
        int y = (int) Math.round(my - offY);
        guideX = null;
        guideY = null;
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
            if (o == dragging) continue;
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
        Config.HudSlot s = Hud.slot(dragging);
        s.x = Math.max(0, Math.min(1, (x - Hud.MARGIN) / (double) Math.max(1, width - w - Hud.MARGIN * 2)));
        s.y = Math.max(0, Math.min(1, (y - Hud.MARGIN) / (double) Math.max(1, height - h - Hud.MARGIN * 2)));
        return true;
    }

    @Override
    protected boolean release(double mx, double my, int button) {
        if (dragging == null) return false;
        dragging = null;
        guideX = null;
        guideY = null;
        Config.get().save();
        return true;
    }

    @Override
    protected boolean scroll(double mx, double my, double amount) {
        HudElement e = under(Minecraft.getInstance(), mx, my);
        if (e == null) return false;
        Config.HudSlot s = Hud.slot(e);
        s.scale = Math.max(0.5, Math.min(2.0, Math.round((s.scale + (amount > 0 ? 0.1 : -0.1)) * 10) / 10.0));
        Config.get().save();
        return true;
    }

    @Override
    public void onClose() {
        Config.get().save();
        Minecraft.getInstance().setScreen(new SettingsScreen(SettingsScreen.Tab.HUD));
    }
}
