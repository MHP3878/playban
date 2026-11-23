package dev.lumio.ui;

import dev.lumio.Config;
import dev.lumio.LumioClient;
import dev.lumio.Theme;
import dev.lumio.compat.BaseScreen;
import dev.lumio.net.Protocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lumio's menu: a sidebar with the sections, the content on the right. Drawn fresh every frame; what
 * can be clicked is collected while drawing, so layout and clicks never disagree.
 */
public class SettingsScreen extends BaseScreen {
    public enum Tab { GENERAL, HUD, WHEEL, ABOUT }

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }
    }

    protected Tab tab;
    private final long opened = System.currentTimeMillis();
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, Float> anim = new HashMap<>();
    protected int px, py, pw, ph;

    public SettingsScreen() {
        this(Tab.GENERAL);
    }

    public SettingsScreen(Tab tab) {
        super(Component.translatable("lumio.menu.title"));
        this.tab = tab;
    }

    @Override
    protected void init() {
        pw = Math.min(460, width - 24);
        ph = Math.min(280, height - 24);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A value that glides towards on (1) or off (0): for hover and switches. */
    protected float glide(String key, boolean on) {
        float now = anim.getOrDefault(key, on ? 1f : 0f);
        now = Draw.approach(now, on ? 1 : 0, 0.25f);
        anim.put(key, now);
        return now;
    }

    protected void hit(int x, int y, int w, int h, Runnable action) {
        hits.add(new Hit(x, y, w, h, action));
    }

    protected boolean over(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        hits.clear();
        float a = Draw.easeOutCubic((System.currentTimeMillis() - opened) / 220f);
        Draw.panel(g, px, py, pw, ph, a);
        if (!Draw.visible(a)) return;

        // sidebar
        int sx = px + 8, sy = py + 8, sw = 118;
        Draw.round(g, sx, sy, sw, ph - 16, Draw.fade(0x66000000, a));
        Draw.circle(g, sx + 15, sy + 16, 6, Draw.fade(Draw.accent(), a));
        Draw.circle(g, sx + 17, sy + 14, 3, Draw.fade(Draw.accent2(), a));
        Draw.text(g, Draw.bold("Lumio"), sx + 27, sy + 9, Draw.fade(Draw.TEXT, a));
        Draw.text(g, Draw.inter(LumioClient.version().replaceAll("\\+.*", "")), sx + 27, sy + 19, Draw.fade(Draw.FAINT, a));
        int ty = sy + 40;
        for (Tab t : Tab.values()) {
            boolean active = t == tab;
            boolean hover = over(mx, my, sx + 4, ty, sw - 8, 20);
            float h = glide("tab" + t, hover || active);
            Draw.round(g, sx + 4, ty, sw - 8, 20, Draw.fade(Draw.mix(0x00FFFFFF, active ? 0x22FFFFFF : 0x14FFFFFF, h), a));
            if (active) g.fillGradient(sx + 6, ty + 5, sx + 8, ty + 15, Draw.fade(Draw.accent(), a), Draw.fade(Draw.accent2(), a));
            Draw.text(g, Draw.inter(Component.translatable("lumio.menu.tab." + t.name().toLowerCase())), sx + 14, ty + 6,
                    Draw.fade(active ? Draw.TEXT : Draw.mix(Draw.MUTED, Draw.TEXT, h), a));
            Tab target = t;
            hit(sx + 4, ty, sw - 8, 20, () -> switchTo(target));
            ty += 23;
        }
        Draw.text(g, Draw.inter(Component.translatable("lumio.menu.close-hint")), sx + 10, py + ph - 22, Draw.fade(Draw.FAINT, a));

        // content
        int cx = sx + sw + 14, cy = py + 14, cw = px + pw - cx - 14;
        switch (tab) {
            case GENERAL -> general(g, mx, my, cx, cy, cw, a);
            case ABOUT -> about(g, cx, cy, cw, a);
            default -> content(g, mx, my, cx, cy, cw, ph - 28, a);
        }
    }

    /** What the HUD and radial menu tabs show (filled in by those parts). */
    protected void content(GuiGraphics g, int mx, int my, int x, int y, int w, int h, float a) {
        switch (tab) {
            case HUD -> {
                heading(g, "lumio.menu.tab.hud", x, y, a);
                button(g, mx, my, x, y + 22, 150, Component.translatable("lumio.menu.hud.edit"), true, a, LumioClient::openHudEditor);
                paragraph(g, Component.translatable("lumio.menu.hud.text"), x, y + 48, w, a);
                // every element with a switch, in two columns
                int col = (w - 10) / 2;
                int i = 0;
                for (dev.lumio.hud.HudElement e : dev.lumio.hud.Hud.ALL) {
                    int ex = x + (i % 2) * (col + 10);
                    int ey = y + 84 + (i / 2) * 20;
                    dev.lumio.Config.HudSlot slot = dev.lumio.hud.Hud.slot(e);
                    Draw.text(g, Draw.inter(e.name()), ex, ey + 2, Draw.fade(Draw.TEXT, a));
                    toggle(g, mx, my, ex + col - 30, ey, slot.on, "hud" + e.id, a, () -> {
                        slot.on = !slot.on;
                        dev.lumio.Config.get().save();
                    });
                    i++;
                }
            }
            case WHEEL -> {
                heading(g, "lumio.menu.tab.wheel", x, y, a);
                button(g, mx, my, x, y + 22, 150, Component.translatable("lumio.menu.wheel.edit"), true, a, LumioClient::openWheelEditor);
                paragraph(g, Component.translatable("lumio.menu.wheel.text", LumioClient.wheelKeyName()), x, y + 48, w, a);
            }
            default -> {
            }
        }
    }

    protected void switchTo(Tab t) {
        tab = t;
    }

    private void general(GuiGraphics g, int mx, int my, int x, int y, int w, float a) {
        Config c = Config.get();
        heading(g, "lumio.menu.look", x, y, a);
        // colour themes
        label(g, "lumio.menu.theme", x, y + 22, a);
        int sx = x;
        for (Theme t : Theme.ALL) {
            boolean active = t.id().equals(c.theme);
            boolean hover = over(mx, my, sx, y + 34, 20, 20);
            float h = glide("theme" + t.id(), hover || active);
            if (active) Draw.ring(g, sx + 10, y + 44, 11, Draw.fade(Draw.TEXT, a));
            Draw.circle(g, sx + 10, y + 44, 8 + Math.round(h), Draw.fade(t.accent(), a));
            Draw.circle(g, sx + 13, y + 41, 4, Draw.fade(t.accent2(), a));
            if (hover) Draw.text(g, Draw.inter(Component.translatable("lumio.theme." + t.id())), x, y + 58, Draw.fade(Draw.MUTED, a));
            hit(sx, y + 34, 20, 20, () -> {
                c.theme = t.id();
                c.save();
            });
            sx += 26;
        }
        // where cards appear
        label(g, "lumio.menu.side", x, y + 74, a);
        segmented(g, mx, my, x, y + 86, 150, List.of("left", "right"), c.cardSide, v -> {
            c.cardSide = v;
            c.save();
        }, "lumio.menu.side.", a);
        // sounds
        label(g, "lumio.menu.sounds", x, y + 112, a);
        toggle(g, mx, my, x + w - 30, y + 111, c.sounds, "sounds", a, () -> {
            c.sounds = !c.sounds;
            c.save();
        });
        // try it
        button(g, mx, my, x, y + 136, 150, Component.translatable("lumio.menu.demo"), false, a, () -> {
            onClose();
            LumioClient.demo();
        });
        heading(g, "lumio.menu.keys", x, y + 168, a);
        paragraph(g, Component.translatable("lumio.menu.keys.text", LumioClient.acceptKeyName(), LumioClient.denyKeyName(),
                LumioClient.menuKeyName(), LumioClient.wheelKeyName()), x, y + 186, w, a);
    }

    private void about(GuiGraphics g, int x, int y, int w, float a) {
        heading(g, "lumio.menu.tab.about", x, y, a);
        Draw.text(g, Draw.inter(Component.translatable("lumio.menu.about.version", LumioClient.version())), x, y + 22, Draw.fade(Draw.TEXT, a));
        Component server = Component.translatable(Protocol.onLumioServer() ? "lumio.command.connected" : "lumio.command.not-connected");
        Draw.text(g, Draw.inter(Component.translatable("lumio.menu.about.server", server)), x, y + 36,
                Draw.fade(Protocol.onLumioServer() ? Draw.SUCCESS : Draw.MUTED, a));
        heading(g, "lumio.menu.about.fair", x, y + 60, a);
        paragraph(g, Component.translatable("lumio.menu.about.fair.text"), x, y + 78, w, a);
    }

    // ---------------------------------------------------------------- small building blocks

    protected void heading(GuiGraphics g, String key, int x, int y, float a) {
        Draw.text(g, Draw.bold(Component.translatable(key)), x, y, Draw.fade(Draw.TEXT, a));
        g.fillGradient(x, y + 11, x + 18, y + 12, Draw.fade(Draw.accent(), a), Draw.fade(Draw.accent2(), a));
    }

    protected void label(GuiGraphics g, String key, int x, int y, float a) {
        Draw.text(g, Draw.inter(Component.translatable(key)), x, y, Draw.fade(Draw.MUTED, a));
    }

    protected void paragraph(GuiGraphics g, Component text, int x, int y, int w, float a) {
        for (FormattedCharSequence line : Draw.wrap(Draw.inter(text), w, 8)) {
            Draw.text(g, line, x, y, Draw.fade(Draw.MUTED, a));
            y += 10;
        }
    }

    protected void button(GuiGraphics g, int mx, int my, int x, int y, int w, Component text, boolean primary, float a, Runnable action) {
        boolean hover = over(mx, my, x, y, w, 20);
        float h = glide("btn" + x + "," + y, hover);
        int base = primary ? Draw.accent() : Draw.BG_SOFT;
        int color = primary ? Draw.mix(base, 0xFFFFFFFF, h * 0.15f) : Draw.mix(Draw.BG_SOFT, Draw.BG_HOVER, h);
        Draw.round(g, x, y, w, 20, Draw.fade(color, a));
        Draw.centered(g, Draw.bold(text), x + w / 2, y + 6, Draw.fade(primary ? 0xFF0B0B10 : Draw.TEXT, a));
        hit(x, y, w, 20, action);
    }

    protected void toggle(GuiGraphics g, int mx, int my, int x, int y, boolean on, String key, float a, Runnable action) {
        float t = glide("toggle" + key, on);
        Draw.round(g, x, y, 26, 12, Draw.fade(Draw.mix(Draw.BG_HOVER, Draw.accent(), t), a));
        Draw.circle(g, x + 6 + Math.round(14 * t), y + 6, 4, Draw.fade(Draw.TEXT, a));
        hit(x - 4, y - 4, 34, 20, action);
    }

    protected void segmented(GuiGraphics g, int mx, int my, int x, int y, int w, List<String> values, String now,
                             java.util.function.Consumer<String> set, String langPrefix, float a) {
        Draw.round(g, x, y, w, 18, Draw.fade(Draw.BG_SOFT, a));
        int sw = w / values.size();
        for (int i = 0; i < values.size(); i++) {
            String v = values.get(i);
            boolean active = v.equals(now);
            float h = glide("seg" + langPrefix + v, active);
            if (h > 0.02f) Draw.round(g, x + i * sw + 2, y + 2, sw - 4, 14, Draw.fade(Draw.mix(Draw.BG_SOFT, Draw.accent(), h), a));
            Component label = Draw.inter(Component.translatable(langPrefix + v));
            Draw.centered(g, label, x + i * sw + sw / 2, y + 5, Draw.fade(active ? 0xFF0B0B10 : Draw.MUTED, a));
            hit(x + i * sw, y, sw, 18, () -> set.accept(v));
        }
    }

    @Override
    protected boolean click(double x, double y, int button) {
        if (button != 0) return false;
        for (Hit h : new ArrayList<>(hits)) {
            if (h.contains(x, y)) {
                h.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean key(int key, int scancode, int modifiers) {
        // the menu key closes the menu again
        if (LumioClient.isMenuKey(key, scancode)) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
