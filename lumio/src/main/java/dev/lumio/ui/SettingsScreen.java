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
    public enum Tab { GENERAL, HUD, WHEEL, CHAT, ABOUT }

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

    /** Lumio's own size: the menu is the same size on screen whatever Minecraft's GUI scale is. */
    @Override
    protected float uiScale() {
        return Ui.scale();
    }

    @Override
    protected void init() {
        // laid out in Lumio's own units (the mouse is measured in them too, see BaseScreen)
        pw = Math.min(480, vw() - 24);
        ph = Math.min(296, vh() - 24);
        px = (vw() - pw) / 2;
        py = (vh() - ph) / 2;
        rebuild();
    }

    /** The input fields, drawn by the menu itself so they take part in its size. */
    private final List<Field> boxes = new ArrayList<>();

    /** The input fields of the current tab (only the radial menu editor has some). */
    protected void rebuild() {
        clearWidgets();
        boxes.clear();
        nameBox = null;
        valueBox = null;
        keywordBox = null;
        if (tab == Tab.CHAT) {
            int[] c = chatBox();
            keywordBox = box(c[0], c[1], c[2], Component.translatable("lumio.menu.chat.keywords.hint"));
            keywordBox.setValue(String.join(", ", Config.get().chatKeywords));
            keywordBox.setResponder(v -> {
                List<String> words = new java.util.ArrayList<>();
                for (String w : v.split(",")) {
                    String t = w.trim();
                    if (t.length() >= 2 && words.size() < 10) words.add(t.length() > 24 ? t.substring(0, 24) : t);
                }
                Config.get().chatKeywords = words;
                Config.get().save();
            });
            return;
        }
        if (tab != Tab.WHEEL) return;
        if (path.isEmpty()) {
            path.push(dev.lumio.wheel.Wheel.makeOwn());
            selected = path.peek().isEmpty() ? -1 : 0;
        }
        int[] f = form();
        nameBox = box(f[0], f[1] + 12, f[2], Component.translatable("lumio.wheel.name"));
        valueBox = box(f[0], f[1] + 82, f[2], Component.translatable("lumio.wheel.value"));
        fillBoxes();
    }

    private Field box(int x, int y, int w, Component hint) {
        Field b = new Field(x, y, w, hint, 120);
        boxes.add(b);
        return b;
    }

    private boolean anyFocused() {
        for (Field b : boxes) if (b.isFocused()) return true;
        return false;
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
        // the menu itself is drawn as the background (below), so input fields stand on top of it
        if (dev.lumio.compat.Compat.BACKGROUND_IN_RENDER) renderBackground(g, mx, my, delta);
        float a = Draw.easeOutCubic((System.currentTimeMillis() - opened) / 220f)
                * Draw.easeOutCubic((System.currentTimeMillis() - tabSince) / 180f);
        pushOpening(g);
        Draw.px = Draw.lumioPx(1);
        for (Field b : boxes) b.render(g, a);
        dev.lumio.compat.Compat.pop(g);
        Draw.px = 0;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        super.renderBackground(g, mx, my, delta);
        float s = uiScale();
        pushOpening(g);
        Draw.px = Draw.lumioPx(1);
        drawMenu(g, (int) (mx / s), (int) (my / s));
        dev.lumio.compat.Compat.pop(g);
        Draw.px = 0;
    }

    /** Lumio's size, and on opening a short grow from 96 % to full size around the middle. */
    private void pushOpening(GuiGraphics g) {
        float s = uiScale();
        float k = 0.96f + 0.04f * Draw.easeOutCubic((System.currentTimeMillis() - opened) / 220f);
        float cx = vw() / 2f, cy = vh() / 2f;
        dev.lumio.compat.Compat.push(g, s * cx * (1 - k), s * cy * (1 - k), s * k);
    }

    // ---------------------------------------------------------------- the frame: panel, sidebar, title

    private static final int SIDE_W = 118;
    /** Height of the title above each tab's content. */
    private static final int HEAD = 34;
    private long tabSince = System.currentTimeMillis();

    private void drawMenu(GuiGraphics g, int mx, int my) {
        hits.clear();
        float a = Draw.easeOutCubic((System.currentTimeMillis() - opened) / 220f);
        // a soft glow in the theme colour, the panel, a thin line along the top in its running colours
        Draw.glow(g, px, py, pw, ph, a * 0.7f);
        Draw.panel(g, px, py, pw, ph, a);
        Draw.bar(g, px + 16, py, pw - 32, 1, Draw.wave(0), Draw.wave(2.5f), a);
        if (!Draw.visible(a)) return;

        // sidebar: the mark, the tabs with their pictures, the hint at the bottom
        int sx = px + 8, sy = py + 8, sw = SIDE_W;
        Draw.round(g, sx, sy, sw, ph - 16, Draw.fade(0xFF0C0C11, a));
        Draw.tint(g, sx, sy, sw, ph - 16, Draw.fade(Draw.accent(), 0.10f * a));
        Draw.circle(g, sx + 16, sy + 16, 7, Draw.fade(Draw.accent(), a));
        Draw.circle(g, sx + 19, sy + 13, 4, Draw.fade(Draw.accent2(), a));
        Draw.circle(g, sx + 14, sy + 18, 2, Draw.fade(0xCCFFFFFF, a));
        Draw.gradientText(g, "Lumio", sx + 29, sy + 9, a, true);
        Draw.text(g, Draw.inter(LumioClient.version().replaceAll("\\+.*", "")), sx + 29, sy + 19, Draw.fade(Draw.FAINT, a));
        int ty = sy + 40;
        for (Tab t : Tab.values()) {
            boolean active = t == tab;
            boolean hover = over(mx, my, sx + 4, ty, sw - 8, 20);
            float h = glide("tab" + t, hover || active);
            float on = glide("tabon" + t, active);
            if (h > 0.01f) Draw.round(g, sx + 4, ty, sw - 8, 20, Draw.fade(Draw.mix(0x00FFFFFF, active ? 0x1EFFFFFF : 0x10FFFFFF, h), a));
            if (on > 0.01f) {
                Draw.tint(g, sx + 4, ty, sw - 8, 20, Draw.fade(Draw.accent(), 0.30f * on * a));
                g.fillGradient(sx + 4, ty + 4, sx + 6, ty + 16, Draw.fade(Draw.accent(), on * a), Draw.fade(Draw.accent2(), on * a));
            }
            int iconColor = Draw.mix(Draw.MUTED, Draw.TEXT, Math.max(h, on));
            icon(g, t, sx + 17, ty + 10, Draw.fade(active ? Draw.mix(Draw.accent(), 0xFFFFFFFF, 0.25f) : iconColor, a));
            Draw.text(g, Draw.inter(Component.translatable("lumio.menu.tab." + t.name().toLowerCase())), sx + 28, ty + 6,
                    Draw.fade(active ? Draw.TEXT : Draw.mix(Draw.MUTED, Draw.TEXT, h), a));
            Tab target = t;
            hit(sx + 4, ty, sw - 8, 20, () -> switchTo(target));
            ty += 22;
        }
        Draw.text(g, Draw.inter(Component.translatable("lumio.menu.close-hint")), sx + 10, py + ph - 22, Draw.fade(Draw.FAINT, a));

        // the title of the tab, big, with a line in the theme's colours; the content fades in under it
        int cx = sx + sw + 14, cy = py + 12, cw = px + pw - cx - 14;
        float in = Draw.easeOutCubic((System.currentTimeMillis() - tabSince) / 180f);
        float ca = a * in;
        String name = tab.name().toLowerCase();
        // the title one and a half times as big (with the font made for that size: still sharp)
        float before = Draw.px;
        Draw.px = before * 1.5f;
        dev.lumio.compat.Compat.push(g, cx, cy, 1.5f);
        Draw.text(g, Draw.bold(Component.translatable("lumio.menu.tab." + name)), 0, 0, Draw.fade(Draw.TEXT, a));
        dev.lumio.compat.Compat.pop(g);
        Draw.px = before;
        Draw.text(g, Draw.inter(Component.translatable("lumio.menu.sub." + name)), cx, cy + 16, Draw.fade(Draw.MUTED, a));
        Draw.bar(g, cx, cy + HEAD - 6, 28, 1, Draw.accent(), Draw.accent2(), a);
        int top = cy + HEAD;
        switch (tab) {
            case GENERAL -> general(g, mx, my, cx, top, cw, ca);
            case ABOUT -> about(g, mx, my, cx, top, cw, ca);
            case CHAT -> chat(g, mx, my, cx, top, cw, ca);
            default -> content(g, mx, my, cx, top, cw, py + ph - 12 - top, ca);
        }
    }

    /** The small pictures of the tabs, made of rounded shapes. */
    private void icon(GuiGraphics g, Tab t, int cx, int cy, int color) {
        switch (t) {
            case GENERAL -> {
                Draw.ring(g, cx, cy, 5, color);
                Draw.circle(g, cx, cy, 2, color);
            }
            case HUD -> {
                Draw.round(g, cx - 5, cy - 5, 4, 4, color);
                g.fill(cx - 5, cy - 5, cx - 1, cy - 1, color);
                g.fill(cx + 1, cy - 5, cx + 5, cy - 1, color);
                g.fill(cx - 5, cy + 1, cx - 1, cy + 5, color);
                g.fill(cx + 1, cy + 1, cx + 5, cy + 5, Draw.mix(color, 0x00000000, 0.5f));
            }
            case WHEEL -> {
                Draw.ring(g, cx, cy, 5, color);
                for (int i = 0; i < 4; i++) {
                    double ang = Math.toRadians(-90 + i * 90);
                    Draw.circle(g, cx + (int) Math.round(Math.cos(ang) * 5), cy + (int) Math.round(Math.sin(ang) * 5), 1, color);
                }
            }
            case CHAT -> {
                Draw.round(g, cx - 6, cy - 5, 12, 8, color);
                g.fill(cx - 3, cy + 3, cx - 1, cy + 5, color);
            }
            case ABOUT -> {
                Draw.ring(g, cx, cy, 5, color);
                g.fill(cx, cy - 1, cx + 1, cy + 3, color);
                g.fill(cx, cy - 3, cx + 1, cy - 2, color);
            }
        }
    }

    // ---------------------------------------------------------------- the tabs

    /** What the HUD and radial menu tabs show. */
    protected void content(GuiGraphics g, int mx, int my, int x, int y, int w, int h, float a) {
        switch (tab) {
            case HUD -> {
                // the editor first, big
                int ch = 44;
                card(g, x, y, w, ch, a);
                paragraph(g, Component.translatable("lumio.menu.hud.text"), x + 10, y + 8, w - 150, a);
                primary(g, mx, my, x + w - 130, y + 12, 120, Component.translatable("lumio.menu.hud.edit"), a, LumioClient::openHudEditor);
                // every element with a switch, in two columns
                int rows = (dev.lumio.hud.Hud.ALL.size() + 1) / 2;
                int gy = y + ch + 8;
                card(g, x, gy, w, rows * 17 + 8, a);
                int col = (w - 20) / 2;
                int i = 0;
                for (dev.lumio.hud.HudElement e : dev.lumio.hud.Hud.ALL) {
                    int ex = x + 10 + (i % 2) * (col + 0);
                    int ey = gy + 6 + (i / 2) * 17;
                    dev.lumio.Config.HudSlot slot = dev.lumio.hud.Hud.slot(e);
                    Draw.text(g, Draw.inter(e.name()), ex, ey + 3, Draw.fade(slot.on ? Draw.TEXT : Draw.MUTED, a));
                    toggle(g, mx, my, ex + col - 36, ey + 1, slot.on, "hud" + e.id, a, () -> {
                        slot.on = !slot.on;
                        dev.lumio.Config.get().save();
                    });
                    i++;
                }
            }
            case WHEEL -> wheelEditor(g, mx, my, x, y, w, h, a);
            default -> {
            }
        }
    }

    protected void switchTo(Tab t) {
        if (t != tab) tabSince = System.currentTimeMillis();
        tab = t;
        rebuild();
    }

    private void general(GuiGraphics g, int mx, int my, int x, int y, int w, float a) {
        Config c = Config.get();
        // the look: colours, size, where cards appear, sounds
        int rowH = 24;
        card(g, x, y, w, rowH * 6 + 4, a);
        int ry = y + 2;
        rowLabel(g, mx, my, x, ry, w, rowH, "lumio.menu.theme", a);
        int sxw = x + w - 10 - Theme.ALL.size() * 20;
        for (Theme t : Theme.ALL) {
            boolean active = t.id().equals(c.theme);
            boolean hover = over(mx, my, sxw, ry + 3, 18, 18);
            float h = glide("theme" + t.id(), hover || active);
            if (active) Draw.ring(g, sxw + 9, ry + 12, 9, Draw.fade(Draw.TEXT, a));
            Draw.circle(g, sxw + 9, ry + 12, 6 + Math.round(h), Draw.fade(t.accent(), a));
            Draw.circle(g, sxw + 11, ry + 10, 3, Draw.fade(t.accent2(), a));
            hit(sxw, ry + 3, 18, 18, () -> {
                c.theme = t.id();
                c.save();
            });
            sxw += 20;
        }
        ry += rowH;
        line(g, x, ry, w, a);
        rowLabel(g, mx, my, x, ry, w, rowH, "lumio.menu.size", a);
        slider(g, mx, my, x + w - 170, ry + 7, 160, a);
        ry += rowH;
        line(g, x, ry, w, a);
        rowLabel(g, mx, my, x, ry, w, rowH, "lumio.menu.side", a);
        segmented(g, mx, my, x + w - 130, ry + 3, 120, List.of("left", "right"), c.cardSide, v -> {
            c.cardSide = v;
            c.save();
        }, "lumio.menu.side.", a);
        ry += rowH;
        line(g, x, ry, w, a);
        rowLabel(g, mx, my, x, ry, w, rowH, "lumio.menu.sounds", a);
        toggle(g, mx, my, x + w - 36, ry + 6, c.sounds, "sounds", a, () -> {
            c.sounds = !c.sounds;
            c.save();
        });
        ry += rowH;
        line(g, x, ry, w, a);
        rowLabel(g, mx, my, x, ry, w, rowH, "lumio.menu.zoom", a);
        toggle(g, mx, my, x + w - 36, ry + 6, c.zoom, "zoom", a, () -> {
            c.zoom = !c.zoom;
            c.save();
        });
        ry += rowH;
        line(g, x, ry, w, a);
        rowLabel(g, mx, my, x, ry, w, rowH, "lumio.menu.durability", a);
        toggle(g, mx, my, x + w - 36, ry + 6, c.durabilityWarning, "durability", a, () -> {
            c.durabilityWarning = !c.durabilityWarning;
            c.save();
        });
        // the keys, and trying the cards out
        int ky = y + rowH * 6 + 12;
        card(g, x, ky, w, 64, a);
        Draw.text(g, Draw.bold(Component.translatable("lumio.menu.keys")), x + 10, ky + 8, Draw.fade(Draw.TEXT, a));
        paragraph(g, Component.translatable("lumio.menu.keys.text", LumioClient.acceptKeyName(), LumioClient.denyKeyName(),
                LumioClient.menuKeyName(), LumioClient.wheelKeyName(), LumioClient.zoomKeyName()), x + 10, ky + 21, w - 140, a);
        button(g, mx, my, x + w - 120, ky + 22, 110, Component.translatable("lumio.menu.demo"), false, a, () -> {
            onClose();
            LumioClient.demo();
        });
    }

    private Field keywordBox;

    /** Where the keyword field of the chat tab stands: x, y, width. */
    private int[] chatBox() {
        int cx = px + 8 + SIDE_W + 14, cy = py + 12 + HEAD, cw = px + pw - cx - 14;
        return new int[]{cx + 10, cy + 48 + 12 + 20, cw - 20};
    }

    private void chat(GuiGraphics g, int mx, int my, int x, int y, int w, float a) {
        Config c = Config.get();
        int rowH = 24;
        card(g, x, y, w, rowH * 2, a);
        rowLabel(g, mx, my, x, y, w, rowH, "lumio.menu.chat.mentions", a);
        toggle(g, mx, my, x + w - 36, y + 6, c.chatMentions, "chatMentions", a, () -> {
            c.chatMentions = !c.chatMentions;
            c.save();
        });
        line(g, x, y + rowH, w, a);
        rowLabel(g, mx, my, x, y + rowH, w, rowH, "lumio.menu.chat.sound", a);
        toggle(g, mx, my, x + w - 36, y + rowH + 6, c.chatSound, "chatSound", a, () -> {
            c.chatSound = !c.chatSound;
            c.save();
        });
        int ky = y + rowH * 2 + 12;
        card(g, x, ky, w, 92, a);
        Draw.text(g, Draw.bold(Component.translatable("lumio.menu.chat.keywords")), x + 10, ky + 8, Draw.fade(Draw.TEXT, a));
        paragraph(g, Component.translatable("lumio.menu.chat.text"), x + 10, ky + 52, w - 20, a);
    }

    private dev.lumio.update.UpdateCheck.Settings updates;

    private void about(GuiGraphics g, int mx, int my, int x, int y, int w, float a) {
        card(g, x, y, w, 48, a);
        Draw.text(g, Draw.inter(Component.translatable("lumio.menu.about.version", LumioClient.version())), x + 10, y + 10, Draw.fade(Draw.TEXT, a));
        boolean on = Protocol.onLumioServer();
        Component server = Component.translatable(on ? "lumio.command.connected" : "lumio.command.not-connected");
        Draw.circle(g, x + 13, y + 32, 3, Draw.fade(on ? Draw.SUCCESS : Draw.FAINT, a));
        Draw.text(g, Draw.inter(Component.translatable("lumio.menu.about.server", server)), x + 21, y + 28,
                Draw.fade(on ? Draw.SUCCESS : Draw.MUTED, a));
        int fy = y + 58;
        card(g, x, fy, w, 120, a);
        Draw.text(g, Draw.bold(Component.translatable("lumio.menu.about.fair")), x + 10, fy + 8, Draw.fade(Draw.TEXT, a));
        paragraph(g, Component.translatable("lumio.menu.about.fair.text"), x + 10, fy + 22, w - 20, a);
        // updates: asked when the game starts, in a small window of its own
        if (updates == null) updates = dev.lumio.update.UpdateCheck.Settings.load();
        int uy = fy + 128;
        card(g, x, uy, w, 30, a);
        rowLabel(g, mx, my, x, uy + 3, w, 24, "lumio.menu.about.updates", a);
        toggle(g, mx, my, x + w - 36, uy + 9, updates.auto, "updates", a, () -> {
            updates.auto = !updates.auto;
            updates.save();
        });
    }

    // ---------------------------------------------------------------- building blocks

    /** A section: a slightly lighter rounded card with a hairline edge. */
    protected void card(GuiGraphics g, int x, int y, int w, int h, float a) {
        Draw.round(g, x, y, w, h, Draw.fade(0xFF16161E, a));
        Draw.outline(g, x, y, w, h, Draw.fade(0x12FFFFFF, a));
    }

    /** A row's name on the left; the row lights up a little under the mouse. */
    private void rowLabel(GuiGraphics g, int mx, int my, int x, int y, int w, int h, String key, float a) {
        float hv = glide("row" + key, over(mx, my, x, y, w, h));
        if (hv > 0.01f) Draw.round(g, x + 2, y + 2, w - 4, h - 4, Draw.fade(0x0CFFFFFF, hv * a));
        Draw.text(g, Draw.inter(Component.translatable(key)), x + 10, y + (h - 8) / 2, Draw.fade(Draw.TEXT, a));
    }

    private void line(GuiGraphics g, int x, int y, int w, float a) {
        g.fill(x + 10, y, x + w - 10, y + 1, Draw.fade(0x0EFFFFFF, a));
    }

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
        if (primary) {
            primary(g, mx, my, x, y, w, text, a, action);
            return;
        }
        boolean hover = over(mx, my, x, y, w, 20);
        float h = glide("btn" + x + "," + y, hover);
        Draw.round(g, x, y, w, 20, Draw.fade(Draw.mix(0xFF20202A, 0xFF2C2C3A, h), a));
        Draw.outline(g, x, y, w, 20, Draw.fade(Draw.mix(0x14FFFFFF, 0x30FFFFFF, h), a));
        Draw.centered(g, Draw.bold(text), x + w / 2, y + 6, Draw.fade(Draw.TEXT, a));
        hit(x, y, w, 20, action);
    }

    /** The main button: the theme's colours as a gradient, a little brighter under the mouse. */
    protected void primary(GuiGraphics g, int mx, int my, int x, int y, int w, Component text, float a, Runnable action) {
        boolean hover = over(mx, my, x, y, w, 20);
        float h = glide("pri" + x + "," + y, hover);
        if (h > 0.01f) Draw.glow(g, x, y, w, 20, a * 0.5f * h);
        Draw.round(g, x, y, w, 20, Draw.fade(Draw.mix(Draw.accent2(), 0xFFFFFFFF, h * 0.12f), a));
        Draw.tint(g, x, y, w, 20, Draw.fade(Draw.mix(Draw.accent(), 0xFFFFFFFF, h * 0.12f), a));
        Draw.centered(g, Draw.bold(text), x + w / 2, y + 6, Draw.fade(0xFF0B0B10, a));
        hit(x, y, w, 20, action);
    }

    protected void toggle(GuiGraphics g, int mx, int my, int x, int y, boolean on, String key, float a, Runnable action) {
        float t = glide("toggle" + key, on);
        Draw.round(g, x, y, 26, 12, Draw.fade(Draw.mix(0xFF2A2A36, Draw.accent(), t), a));
        if (t > 0.01f) Draw.tint(g, x, y, 26, 12, Draw.fade(Draw.accent2(), 0.8f * t * a));
        Draw.circle(g, x + 6 + Math.round(14 * t), y + 6, 4, Draw.fade(0xFFFFFFFF, a));
        hit(x - 4, y - 4, 34, 20, action);
    }

    /** Lumio's size: a track with a stop for each size; a click goes to the nearest stop. */
    private void slider(GuiGraphics g, int mx, int my, int x, int y, int w, float a) {
        int stops = Ui.SIZES.length;
        int at = Ui.index();
        Component name = Draw.bold(Component.translatable(Ui.sizeKey()));
        int tw = w - 64;
        int tx = x + 64;
        Draw.text(g, name, x + 58 - Draw.width(name), y + 2, Draw.fade(Draw.TEXT, a));
        Draw.round(g, tx, y + 3, tw, 6, Draw.fade(0xFF2A2A36, a));
        float t = glide("slider", true);
        int knob = tx + Math.round(tw * at / (float) (stops - 1));
        int fill = Math.max(6, knob - tx);
        Draw.bar(g, tx + 1, y + 4, fill - 1, 4, Draw.accent(), Draw.accent2(), a * t);
        for (int i = 0; i < stops; i++) {
            int sx = tx + Math.round(tw * i / (float) (stops - 1));
            Draw.circle(g, sx, y + 6, 1, Draw.fade(i <= at ? 0x99FFFFFF : 0x55FFFFFF, a));
            int index = i;
            int left = i == 0 ? tx - 6 : sx - tw / (stops - 1) / 2;
            int right = i == stops - 1 ? tx + tw + 6 : sx + tw / (stops - 1) / 2;
            hit(left, y - 4, right - left, 20, () -> {
                Ui.set(index);
                init();
            });
        }
        boolean hov = over(mx, my, tx - 6, y - 4, tw + 12, 20);
        Draw.circle(g, knob, y + 6, hov ? 6 : 5, Draw.fade(0xFFFFFFFF, a));
        Draw.ring(g, knob, y + 6, hov ? 6 : 5, Draw.fade(Draw.accent(), a));
    }

    protected void segmented(GuiGraphics g, int mx, int my, int x, int y, int w, List<String> values, String now,
                             java.util.function.Consumer<String> set, String langPrefix, float a) {
        Draw.round(g, x, y, w, 18, Draw.fade(0xFF0E0E14, a));
        int sw = w / values.size();
        for (int i = 0; i < values.size(); i++) {
            String v = values.get(i);
            boolean active = v.equals(now);
            float h = glide("seg" + langPrefix + v, active);
            if (h > 0.02f) {
                Draw.round(g, x + i * sw + 2, y + 2, sw - 4, 14, Draw.fade(Draw.accent2(), h * a));
                Draw.tint(g, x + i * sw + 2, y + 2, sw - 4, 14, Draw.fade(Draw.accent(), h * a));
            }
            Component label = Draw.inter(Component.translatable(langPrefix + v));
            Draw.centered(g, label, x + i * sw + sw / 2, y + 5, Draw.fade(active ? 0xFF0B0B10 : Draw.MUTED, a));
            hit(x + i * sw, y, sw, 18, () -> set.accept(v));
        }
    }

    // ---------------------------------------------------------------- radial menu editor

    /** The pages being edited: the main page at the bottom, an opened next page on top. */
    private final java.util.Deque<List<Config.WheelEntry>> path = new java.util.ArrayDeque<>();
    private int selected = -1;
    private Field nameBox;
    private Field valueBox;
    private boolean filling;

    /** Where the form stands: x, y, width (the list is left of it). */
    private int[] form() {
        int sx = px + 8, sw = 118;
        int x = sx + sw + 14, w = px + pw - x - 14;
        return new int[]{x + 142, py + 12 + HEAD + 16, w - 142};
    }

    private Config.WheelEntry current() {
        List<Config.WheelEntry> page = path.peek();
        return page == null || selected < 0 || selected >= page.size() ? null : page.get(selected);
    }

    private void fillBoxes() {
        Config.WheelEntry e = current();
        filling = true;
        if (nameBox != null) {
            nameBox.setValue(e == null ? "" : e.name);
            nameBox.visible = e != null;
            nameBox.setResponder(v -> {
                if (filling || current() == null) return;
                current().name = v;
                Config.get().save();
            });
        }
        if (valueBox != null) {
            valueBox.setValue(e == null ? "" : e.value);
            valueBox.visible = e != null && !"page".equals(e.type);
            valueBox.setResponder(v -> {
                if (filling || current() == null) return;
                current().value = v;
                Config.get().save();
            });
        }
        filling = false;
    }

    private void select(int i) {
        selected = i;
        fillBoxes();
    }

    private void wheelEditor(GuiGraphics g, int mx, int my, int x, int y, int w, int h, float a) {
        if (path.isEmpty()) return;
        List<Config.WheelEntry> page = path.peek();
        Component keyHint = Draw.inter(Component.translatable("lumio.wheel.hold", LumioClient.wheelKeyName()));
        if (path.size() == 1) Draw.text(g, keyHint, x + w - Draw.width(keyHint), y + 2, Draw.fade(Draw.FAINT, a));
        // where we are: main page, or main page › a next page
        Component crumb = Component.translatable(path.size() > 1 ? "lumio.wheel.crumb.sub" : "lumio.wheel.crumb.main");
        Draw.text(g, Draw.inter(crumb), x, y + 2, Draw.fade(Draw.MUTED, a));
        if (path.size() > 1) {
            Component back = Draw.bold(Component.translatable("lumio.wheel.back"));
            int bw = Draw.width(back) + 10;
            boolean hov = over(mx, my, x + w - bw, y - 1, bw, 14);
            Draw.round(g, x + w - bw, y - 1, bw, 14, Draw.fade(hov ? Draw.BG_HOVER : Draw.BG_SOFT, a));
            Draw.text(g, back, x + w - bw + 5, y + 2, Draw.fade(Draw.TEXT, a));
            hit(x + w - bw, y - 1, bw, 14, () -> {
                path.pop();
                select(path.peek().isEmpty() ? -1 : 0);
            });
        }
        // the list of fields
        int lw = 132, ly = y + 16;
        for (int i = 0; i < page.size() && i < dev.lumio.wheel.Wheel.MAX; i++) {
            Config.WheelEntry e = page.get(i);
            boolean sel = i == selected;
            boolean hov = over(mx, my, x, ly, lw, 18);
            float t = glide("row" + path.size() + ":" + i, sel || hov);
            Draw.round(g, x, ly, lw, 18, Draw.fade(sel ? Draw.mix(Draw.BG_SOFT, Draw.accent(), 0.35f) : Draw.mix(0x00000000, Draw.BG_SOFT, t), a));
            g.renderItem(new net.minecraft.world.item.ItemStack(dev.lumio.compat.Compat.item(e.icon)), x + 2, ly + 1);
            String name = e.name.length() > 14 ? e.name.substring(0, 13) + "…" : e.name;
            Draw.text(g, Draw.inter(name + ("page".equals(e.type) ? "  ›" : "")), x + 22, ly + 5, Draw.fade(Draw.TEXT, a));
            int index = i;
            if (hov || sel) {
                // move up, move down, remove
                rowButton(g, mx, my, x + lw - 36, ly + 3, "▲", a, () -> move(index, -1));
                rowButton(g, mx, my, x + lw - 24, ly + 3, "▼", a, () -> move(index, 1));
                rowButton(g, mx, my, x + lw - 12, ly + 3, "✕", a, () -> {
                    page.remove(index);
                    Config.get().save();
                    select(Math.min(index, page.size() - 1));
                });
            }
            hit(x, ly, lw - 38, 18, () -> select(index));
            ly += 20;
        }
        if (page.size() < dev.lumio.wheel.Wheel.MAX) {
            button(g, mx, my, x, ly + 2, lw, Component.translatable("lumio.wheel.add"), false, a, () -> {
                page.add(new Config.WheelEntry(Component.translatable("lumio.wheel.new").getString(), "minecraft:compass", "command", ""));
                Config.get().save();
                select(page.size() - 1);
            });
        }
        // the form for the chosen field
        int[] f = form();
        Config.WheelEntry e = current();
        if (e == null) {
            paragraph(g, Component.translatable("lumio.wheel.empty"), f[0], f[1] + 4, f[2], a);
        } else {
            label(g, "lumio.wheel.name", f[0], f[1], a);
            label(g, "lumio.wheel.action", f[0], f[1] + 36, a);
            segmented(g, mx, my, f[0], f[1] + 48, f[2], List.of("command", "chat", "page"), e.type, v -> {
                e.type = v;
                Config.get().save();
                fillBoxes();
            }, "lumio.wheel.type.", a);
            if ("page".equals(e.type)) {
                button(g, mx, my, f[0], f[1] + 76, f[2], Component.translatable("lumio.wheel.open-page", e.page.size()), true, a, () -> {
                    path.push(e.page);
                    select(e.page.isEmpty() ? -1 : 0);
                });
            } else {
                label(g, "chat".equals(e.type) ? "lumio.wheel.value.chat" : "lumio.wheel.value", f[0], f[1] + 70, a);
            }
            // pictures to choose from
            label(g, "lumio.wheel.icon", f[0], f[1] + 106, a);
            int cols = Math.max(1, f[2] / 19);
            List<String> icons = dev.lumio.wheel.Wheel.ICONS;
            for (int i = 0; i < icons.size(); i++) {
                int ix = f[0] + (i % cols) * 19, iy = f[1] + 118 + (i / cols) * 19;
                if (iy > py + ph - 34) break;
                boolean sel = icons.get(i).equals(e.icon);
                boolean hov = over(mx, my, ix, iy, 18, 18);
                if (sel || hov) Draw.round(g, ix, iy, 18, 18, Draw.fade(sel ? Draw.mix(Draw.BG_SOFT, Draw.accent(), 0.5f) : Draw.BG_HOVER, a));
                g.renderItem(new net.minecraft.world.item.ItemStack(dev.lumio.compat.Compat.item(icons.get(i))), ix + 1, iy + 1);
                String icon = icons.get(i);
                hit(ix, iy, 18, 18, () -> {
                    e.icon = icon;
                    Config.get().save();
                });
            }
        }
        // start again: the server's suggestion, or Lumio's start set
        int by = py + ph - 30;
        if (!dev.lumio.wheel.Wheel.serverEntries().isEmpty()) {
            button(g, mx, my, x, by, lw, Component.translatable("lumio.wheel.take-server"), false, a, () -> replaceWith(dev.lumio.wheel.Wheel.serverEntries()));
        } else {
            button(g, mx, my, x, by, lw, Component.translatable("lumio.wheel.reset"), false, a, () -> replaceWith(dev.lumio.wheel.Wheel.defaults()));
        }
    }

    private void rowButton(GuiGraphics g, int mx, int my, int x, int y, String sign, float a, Runnable action) {
        boolean hov = over(mx, my, x, y, 11, 12);
        Draw.round(g, x, y, 11, 12, Draw.fade(hov ? Draw.BG_HOVER : 0x00000000, a));
        Component c = Draw.inter(sign);
        Draw.text(g, c, x + (11 - Draw.width(c)) / 2, y + 2, Draw.fade(hov ? Draw.TEXT : Draw.MUTED, a));
        hit(x, y, 11, 12, action);
    }

    private void move(int index, int by) {
        List<Config.WheelEntry> page = path.peek();
        int to = index + by;
        if (to < 0 || to >= page.size()) return;
        Config.WheelEntry e = page.remove(index);
        page.add(to, e);
        Config.get().save();
        select(to);
    }

    private void replaceWith(List<Config.WheelEntry> entries) {
        List<Config.WheelEntry> own = Config.get().wheel;
        own.clear();
        for (Config.WheelEntry e : entries) own.add(dev.lumio.wheel.Wheel.copy(e));
        Config.get().save();
        path.clear();
        path.push(own);
        select(own.isEmpty() ? -1 : 0);
    }

    @Override
    protected boolean click(double x, double y, int button) {
        if (button != 0) return false;
        // the text fields first: a click inside focuses one, a click elsewhere leaves it
        boolean inField = false;
        for (Field b : boxes) {
            if (b.contains(x, y)) {
                for (Field o : boxes) if (o != b) o.setFocused(false);
                b.click(x);
                inField = true;
            }
        }
        if (inField) return true;
        for (Field b : boxes) b.setFocused(false);
        for (Hit h : new ArrayList<>(hits)) {
            if (h.contains(x, y)) {
                h.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean typed(int codepoint) {
        for (Field b : boxes) if (b.typed(codepoint)) return true;
        return false;
    }

    @Override
    protected boolean key(int key, int scancode, int modifiers) {
        // the menu key closes the menu again (not while typing in a field)
        for (Field b : boxes) if (b.key(key, modifiers)) return true;
        if (!anyFocused() && LumioClient.isMenuKey(key, scancode)) {
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
