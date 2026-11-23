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

    @Override
    protected void init() {
        pw = Math.min(460, width - 24);
        ph = Math.min(280, height - 24);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        rebuild();
    }

    /** The input fields of the current tab (only the radial menu editor has some). */
    protected void rebuild() {
        clearWidgets();
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

    private net.minecraft.client.gui.components.EditBox box(int x, int y, int w, Component hint) {
        var b = new net.minecraft.client.gui.components.EditBox(font, x + 5, y + 4, w - 10, 10, hint);
        b.setBordered(false);
        b.setMaxLength(120);
        b.setTextColor(Draw.TEXT);
        b.setHint(Draw.inter(hint).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        addRenderableWidget(b);
        return b;
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
        super.render(g, mx, my, delta);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        super.renderBackground(g, mx, my, delta);
        drawMenu(g, mx, my);
    }

    private void drawMenu(GuiGraphics g, int mx, int my) {
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
            case CHAT -> chat(g, mx, my, cx, cy, cw, a);
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
            case WHEEL -> wheelEditor(g, mx, my, x, y, w, h, a);
            default -> {
            }
        }
    }

    protected void switchTo(Tab t) {
        tab = t;
        rebuild();
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

    private net.minecraft.client.gui.components.EditBox keywordBox;

    /** Where the keyword field of the chat tab stands: x, y, width. */
    private int[] chatBox() {
        int cx = px + 8 + 118 + 14, cy = py + 14, cw = px + pw - cx - 14;
        return new int[]{cx, cy + 96, cw};
    }

    private void chat(GuiGraphics g, int mx, int my, int x, int y, int w, float a) {
        Config c = Config.get();
        heading(g, "lumio.menu.tab.chat", x, y, a);
        label(g, "lumio.menu.chat.mentions", x, y + 24, a);
        toggle(g, mx, my, x + w - 30, y + 23, c.chatMentions, "chatMentions", a, () -> {
            c.chatMentions = !c.chatMentions;
            c.save();
        });
        label(g, "lumio.menu.chat.sound", x, y + 46, a);
        toggle(g, mx, my, x + w - 30, y + 45, c.chatSound, "chatSound", a, () -> {
            c.chatSound = !c.chatSound;
            c.save();
        });
        label(g, "lumio.menu.chat.keywords", x, y + 72, a);
        int[] b = chatBox();
        Draw.round(g, b[0], b[1], b[2], 18, Draw.fade(Draw.BG_SOFT, a));
        if (keywordBox != null && keywordBox.isFocused()) Draw.outline(g, b[0], b[1], b[2], 18, Draw.fade(Draw.accent(), a));
        paragraph(g, Component.translatable("lumio.menu.chat.text"), x, y + 126, w, a);
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

    // ---------------------------------------------------------------- radial menu editor

    /** The pages being edited: the main page at the bottom, an opened next page on top. */
    private final java.util.Deque<List<Config.WheelEntry>> path = new java.util.ArrayDeque<>();
    private int selected = -1;
    private net.minecraft.client.gui.components.EditBox nameBox;
    private net.minecraft.client.gui.components.EditBox valueBox;
    private boolean filling;

    /** Where the form stands: x, y, width (the list is left of it). */
    private int[] form() {
        int sx = px + 8, sw = 118;
        int x = sx + sw + 14, w = px + pw - x - 14;
        return new int[]{x + 142, py + 14 + 34, w - 142};
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
        heading(g, "lumio.menu.tab.wheel", x, y, a);
        Component keyHint = Draw.inter(Component.translatable("lumio.wheel.hold", LumioClient.wheelKeyName()));
        Draw.text(g, keyHint, x + w - Draw.width(keyHint), y, Draw.fade(Draw.FAINT, a));
        // where we are: main page, or main page › a next page
        Component crumb = Component.translatable(path.size() > 1 ? "lumio.wheel.crumb.sub" : "lumio.wheel.crumb.main");
        Draw.text(g, Draw.inter(crumb), x, y + 18, Draw.fade(Draw.MUTED, a));
        if (path.size() > 1) {
            Component back = Draw.bold(Component.translatable("lumio.wheel.back"));
            int bw = Draw.width(back) + 10;
            boolean hov = over(mx, my, x + w - bw, y + 15, bw, 14);
            Draw.round(g, x + w - bw, y + 15, bw, 14, Draw.fade(hov ? Draw.BG_HOVER : Draw.BG_SOFT, a));
            Draw.text(g, back, x + w - bw + 5, y + 18, Draw.fade(Draw.TEXT, a));
            hit(x + w - bw, y + 15, bw, 14, () -> {
                path.pop();
                select(path.peek().isEmpty() ? -1 : 0);
            });
        }
        // the list of fields
        int lw = 132, ly = y + 34;
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
            Draw.round(g, f[0], f[1] + 12, f[2], 18, Draw.fade(Draw.BG_SOFT, a));
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
                Draw.round(g, f[0], f[1] + 82, f[2], 18, Draw.fade(Draw.BG_SOFT, a));
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
        // the menu key closes the menu again (not while typing in a field)
        if (!(getFocused() instanceof net.minecraft.client.gui.components.EditBox) && LumioClient.isMenuKey(key, scancode)) {
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
