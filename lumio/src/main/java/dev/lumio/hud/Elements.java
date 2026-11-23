package dev.lumio.hud;

import dev.lumio.ui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lumio's HUD elements. */
public final class Elements {
    private Elements() {}

    // ---------------------------------------------------------------- coordinates and facing

    public static final class Coords extends HudElement {
        public Coords() {
            super("coords", true, 0.0, 0);
        }

        private Component value(Minecraft mc, boolean preview) {
            if (mc.player == null || preview && mc.player == null) return Component.literal("128, 64, -42  ·  N");
            var p = mc.player.blockPosition();
            int dir = Math.floorMod(Math.round(Mth.wrapDegrees(mc.player.getYRot()) / 45f), 8);
            return Component.literal(p.getX() + ", " + p.getY() + ", " + p.getZ() + "  ·  ")
                    .append(Component.translatable("lumio.dir." + dir));
        }

        /** In the Overworld where that is in the Nether, and the other way round (the second line). */
        private Component other(Minecraft mc, boolean preview) {
            if (mc.player == null || mc.level == null) return preview ? Component.translatable("lumio.hud.coords.nether", "16, -5") : null;
            var p = mc.player.blockPosition();
            if (mc.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
                return Component.translatable("lumio.hud.coords.nether", Math.floorDiv(p.getX(), 8) + ", " + Math.floorDiv(p.getZ(), 8));
            }
            if (mc.level.dimension().equals(net.minecraft.world.level.Level.NETHER)) {
                return Component.translatable("lumio.hud.coords.overworld", p.getX() * 8 + ", " + p.getZ() * 8);
            }
            return null;
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            Component o = other(mc, preview);
            int second = o == null ? 0 : Draw.width(Draw.inter(o)) + Draw.width(Draw.bold("XYZ")) + 5;
            return Math.max(labelledWidth(Component.literal("XYZ"), value(mc, preview)), second) + PAD_X * 2;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return (other(mc, preview) == null ? 9 : 19) + PAD_Y * 2;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            back(g, width(mc, preview), height(mc, preview), a);
            labelled(g, Component.literal("XYZ"), value(mc, preview), PAD_X, PAD_Y + 1, a);
            Component o = other(mc, preview);
            if (o != null) Draw.text(g, Draw.inter(o), PAD_X + Draw.width(Draw.bold("XYZ")) + 5, PAD_Y + 11, Draw.fade(Draw.FAINT, a));
        }
    }

    // ---------------------------------------------------------------- frames per second

    public static final class Fps extends HudElement {
        public Fps() {
            super("fps", true, 0.0, 23);
        }

        private float shown = -1;

        /** The number glides instead of jumping. */
        private int smooth(Minecraft mc) {
            int now = mc.getFps();
            shown = shown < 0 ? now : Draw.approach(shown, now, 0.15f);
            return Math.round(shown);
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            return labelledWidth(Component.literal("FPS"), Component.literal("0000")) + PAD_X * 2 + 6;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return 9 + PAD_Y * 2;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            back(g, width(mc, preview), height(mc, preview), a);
            int fps = smooth(mc);
            int end = labelled(g, Component.literal("FPS"), Component.literal(String.valueOf(fps)), PAD_X, PAD_Y + 1, a);
            // green when smooth, yellow when it gets slow, red when it stutters
            int color = fps >= 60 ? Draw.SUCCESS : fps >= 30 ? Draw.WARN : Draw.DANGER;
            Draw.circle(g, end + 5, PAD_Y + 5, 2, Draw.fade(color, a));
        }
    }

    // ---------------------------------------------------------------- ping

    public static final class Ping extends HudElement {
        public Ping() {
            super("ping", true, 0.0, 46);
        }

        private int ms(Minecraft mc, boolean preview) {
            if (mc.player == null || mc.getConnection() == null) return preview ? 24 : -1;
            PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            return info == null ? (preview ? 24 : -1) : info.getLatency();
        }

        @Override
        public boolean active(Minecraft mc) {
            return ms(mc, false) >= 0 && !mc.isLocalServer();
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            return labelledWidth(Component.literal("PING"), Component.literal(ms(mc, preview) + " ms")) + PAD_X * 2 + 6;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return 9 + PAD_Y * 2;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            int ms = ms(mc, preview);
            back(g, width(mc, preview), height(mc, preview), a);
            int end = labelled(g, Component.literal("PING"), Component.literal(ms + " ms"), PAD_X, PAD_Y + 1, a);
            int color = ms < 80 ? Draw.SUCCESS : ms < 180 ? Draw.WARN : Draw.DANGER;
            Draw.circle(g, end + 5, PAD_Y + 5, 2, Draw.fade(color, a));
        }
    }

    // ---------------------------------------------------------------- clock

    public static final class Clock extends HudElement {
        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

        public Clock() {
            super("clock", true, 0.0, 69);
        }

        private Component value() {
            return Component.literal(LocalTime.now().format(TIME));
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            return labelledWidth(Component.translatable("lumio.hud.clock.label"), value()) + PAD_X * 2;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return 9 + PAD_Y * 2;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            back(g, width(mc, preview), height(mc, preview), a);
            labelled(g, Component.translatable("lumio.hud.clock.label"), value(), PAD_X, PAD_Y + 1, a);
        }
    }

    // ---------------------------------------------------------------- armour with durability

    public static final class Armor extends HudElement {
        private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        private static final Item[] SAMPLE = {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.IRON_BOOTS};

        public Armor() {
            super("armor", true, 0.0, 118);
        }

        private List<ItemStack> worn(Minecraft mc, boolean preview) {
            List<ItemStack> out = new ArrayList<>();
            if (mc.player != null) for (EquipmentSlot s : SLOTS) {
                ItemStack st = mc.player.getItemBySlot(s);
                if (!st.isEmpty()) out.add(st);
            }
            if (out.isEmpty() && preview) for (Item i : SAMPLE) out.add(new ItemStack(i));
            return out;
        }

        @Override
        public boolean active(Minecraft mc) {
            return !worn(mc, false).isEmpty();
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            return 16 + 4 + Draw.width(Draw.inter("100%")) + PAD_X * 2;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return Math.max(1, worn(mc, preview).size()) * 18 + PAD_Y * 2 - 2;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            back(g, width(mc, preview), height(mc, preview), a);
            int y = PAD_Y;
            for (ItemStack st : worn(mc, preview)) {
                g.renderItem(st, PAD_X - 1, y);
                if (st.isDamageableItem()) {
                    float left = 1 - st.getDamageValue() / (float) Math.max(1, st.getMaxDamage());
                    int color = Draw.mix(Draw.DANGER, Draw.SUCCESS, left);
                    Draw.text(g, Draw.inter(Math.round(left * 100) + "%"), PAD_X + 19, y + 2, Draw.fade(color, a));
                    // and as a thin bar at the bottom of the row
                    int bw = Draw.width(Draw.inter("100%"));
                    g.fill(PAD_X + 19, y + 14, PAD_X + 19 + bw, y + 15, Draw.fade(0x33FFFFFF, a));
                    g.fill(PAD_X + 19, y + 14, PAD_X + 19 + Math.round(bw * left), y + 15, Draw.fade(color, a));
                } else {
                    Draw.text(g, Draw.inter("∞"), PAD_X + 19, y + 5, Draw.fade(Draw.MUTED, a));
                }
                y += 18;
            }
        }
    }

    // ---------------------------------------------------------------- potion effects

    public static final class Effects extends HudElement {
        public Effects() {
            super("effects", false, 1.0, 215);
        }

        private record Row(Component name, String time, int color) {}

        private List<Row> rows(Minecraft mc, boolean preview) {
            List<Row> out = new ArrayList<>();
            if (mc.player != null) for (MobEffectInstance e : mc.player.getActiveEffects()) {
                Component name = e.getEffect().value().getDisplayName().copy();
                if (e.getAmplifier() > 0) name = name.copy().append(" ").append(Component.translatable("enchantment.level." + (e.getAmplifier() + 1)));
                int s = e.getDuration() / 20;
                out.add(new Row(name, e.isInfiniteDuration() ? "∞" : (s / 60) + ":" + String.format("%02d", s % 60), e.getEffect().value().getColor()));
            }
            if (out.isEmpty() && preview) {
                out.add(new Row(Component.translatable("effect.minecraft.speed").append(" II"), "1:24", 0xFF33EBFF));
                out.add(new Row(Component.translatable("effect.minecraft.fire_resistance"), "6:02", 0xFFFF9900));
            }
            return out;
        }

        @Override
        public boolean active(Minecraft mc) {
            return !rows(mc, false).isEmpty();
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            int w = 0;
            for (Row r : rows(mc, preview)) w = Math.max(w, Draw.width(Draw.inter(r.name())) + 10 + Draw.width(Draw.bold(r.time())));
            return w + 10 + PAD_X * 2;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return Math.max(1, rows(mc, preview).size()) * 12 + PAD_Y * 2 - 3;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            int w = width(mc, preview);
            back(g, w, height(mc, preview), a);
            int y = PAD_Y;
            for (Row r : rows(mc, preview)) {
                Draw.circle(g, PAD_X + 3, y + 4, 3, Draw.fade(0xFF000000 | r.color(), a));
                Draw.text(g, Draw.inter(r.name()), PAD_X + 10, y, Draw.fade(Draw.TEXT, a));
                Component t = Draw.bold(r.time());
                Draw.text(g, t, w - PAD_X - Draw.width(t), y, Draw.fade(Draw.MUTED, a));
                y += 12;
            }
        }
    }

    // ---------------------------------------------------------------- keys and clicks per second

    public static final class Keys extends HudElement {
        private final List<Long> left = new ArrayList<>();
        private final List<Long> right = new ArrayList<>();
        private boolean wasLeft;
        private boolean wasRight;
        private static final int K = 18;
        private static final int GAP = 2;

        public Keys() {
            super("keys", false, 1.0, 150);
        }

        private int cps(List<Long> clicks, boolean down, boolean was, long now) {
            if (down && !was) clicks.add(now);
            clicks.removeIf(t -> now - t > 1000);
            return clicks.size();
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            return K * 3 + GAP * 2 + PAD_X * 2;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return K * 4 + GAP * 3 + PAD_Y * 2;
        }

        private void key(GuiGraphics g, int x, int y, int w, Component label, boolean down, float a) {
            Draw.round(g, x, y, w, K, Draw.fade(down ? Draw.accent() : 0x66000000, a));
            Component l = Draw.bold(label);
            Draw.text(g, l, x + (w - Draw.width(l)) / 2, y + 5, Draw.fade(down ? 0xFF0B0B10 : Draw.TEXT, a));
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            back(g, width(mc, preview), height(mc, preview), a);
            var o = mc.options;
            long now = System.currentTimeMillis();
            boolean l = o.keyAttack.isDown();
            boolean r = o.keyUse.isDown();
            int lc = cps(left, l, wasLeft, now);
            int rc = cps(right, r, wasRight, now);
            wasLeft = l;
            wasRight = r;
            int x0 = PAD_X, y0 = PAD_Y, s = K + GAP;
            key(g, x0 + s, y0, K, o.keyUp.getTranslatedKeyMessage(), o.keyUp.isDown(), a);
            key(g, x0, y0 + s, K, o.keyLeft.getTranslatedKeyMessage(), o.keyLeft.isDown(), a);
            key(g, x0 + s, y0 + s, K, o.keyDown.getTranslatedKeyMessage(), o.keyDown.isDown(), a);
            key(g, x0 + s * 2, y0 + s, K, o.keyRight.getTranslatedKeyMessage(), o.keyRight.isDown(), a);
            int half = (K * 3 + GAP * 2 - GAP) / 2;
            key(g, x0, y0 + s * 2, half, Component.literal(lc + " CPS"), l, a);
            key(g, x0 + half + GAP, y0 + s * 2, half, Component.literal(rc + " CPS"), r, a);
            key(g, x0, y0 + s * 3, K * 3 + GAP * 2, Component.literal("———"), o.keyJump.isDown(), a);
        }
    }

    // ---------------------------------------------------------------- important items

    public static final class Counter extends HudElement {
        private static final Item[] WATCH = {Items.TOTEM_OF_UNDYING, Items.ENDER_PEARL, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.ARROW};

        public Counter() {
            super("items", false, 0.0, 200);
        }

        private Map<Item, Integer> counts(Minecraft mc, boolean preview) {
            Map<Item, Integer> out = new LinkedHashMap<>();
            if (mc.player != null) {
                var inv = mc.player.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack st = inv.getItem(i);
                    for (Item w : WATCH) if (st.is(w)) out.merge(w, st.getCount(), Integer::sum);
                }
            }
            if (out.isEmpty() && preview) {
                out.put(Items.TOTEM_OF_UNDYING, 3);
                out.put(Items.ENDER_PEARL, 16);
                out.put(Items.GOLDEN_APPLE, 12);
            }
            return out;
        }

        @Override
        public boolean active(Minecraft mc) {
            return !counts(mc, false).isEmpty();
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            return 16 + 4 + Draw.width(Draw.bold("64")) + 6 + PAD_X * 2;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return Math.max(1, counts(mc, preview).size()) * 18 + PAD_Y * 2 - 2;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            back(g, width(mc, preview), height(mc, preview), a);
            int y = PAD_Y;
            for (Map.Entry<Item, Integer> e : counts(mc, preview).entrySet()) {
                g.renderItem(new ItemStack(e.getKey()), PAD_X - 1, y);
                Draw.text(g, Draw.bold(String.valueOf(e.getValue())), PAD_X + 19, y + 5, Draw.fade(Draw.TEXT, a));
                y += 18;
            }
        }
    }

    // ---------------------------------------------------------------- what the server sends (money, ...)

    public static final class Server extends HudElement {
        public Server() {
            super("server", true, 1.0, 104);
        }

        private List<String[]> lines(boolean preview) {
            List<String[]> out = new ArrayList<>(HudData.lines());
            if (out.isEmpty() && preview) out.add(new String[]{"Kontostand", "1.250 $"});
            return out;
        }

        @Override
        public boolean active(Minecraft mc) {
            return !HudData.lines().isEmpty();
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            int w = 0;
            for (String[] l : lines(preview)) w = Math.max(w, labelledWidth(Component.literal(l[0]), Component.literal(l[1])));
            return w + PAD_X * 2;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return Math.max(1, lines(preview).size()) * 12 + PAD_Y * 2 - 2;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            back(g, width(mc, preview), height(mc, preview), a);
            int y = PAD_Y + 1;
            for (String[] l : lines(preview)) {
                labelled(g, Component.literal(l[0]), Component.literal(l[1]), PAD_X, y, a);
                y += 12;
            }
        }
    }

    // ---------------------------------------------------------------- what is playing (Spotify and others)

    public static final class Music extends HudElement {
        private static final int W = 170;
        private static final int H = 42;
        private static final int ART = 32;
        private static final int SPOTIFY = 0xFF1ED760;

        public Music() {
            super("music", true, 1.0, 56);
        }

        private static dev.lumio.media.Track track(boolean preview) {
            dev.lumio.media.Track t = dev.lumio.media.Media.current();
            if (t == null && preview) {
                return new dev.lumio.media.Track("Midnight Drive", "Lumio Radio", "Spotify", true, 83_000, 214_000, System.currentTimeMillis(), "");
            }
            return t;
        }

        @Override
        public boolean active(Minecraft mc) {
            return dev.lumio.media.Media.current() != null;
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            return W;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return H;
        }

        /** The text cut to the width, with "…" when it was too long. */
        private static Component fit(String text, int max, boolean bold) {
            String s = text == null ? "" : text;
            Component c = bold ? Draw.bold(s) : Draw.inter(s);
            if (Draw.width(c) <= max) return c;
            while (!s.isEmpty()) {
                s = s.substring(0, s.length() - 1);
                c = bold ? Draw.bold(s.stripTrailing() + "…") : Draw.inter(s.stripTrailing() + "…");
                if (Draw.width(c) <= max) return c;
            }
            return c;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            dev.lumio.media.Track t = track(preview);
            back(g, W, H, a);
            if (t == null) return;
            int from = t.spotify() ? SPOTIFY : Draw.accent();
            int to = t.spotify() ? 0xFF14B04A : Draw.accent2();
            // the cover, or a gradient square with a note
            int ax = 5, ay = 5;
            Object cover = preview && dev.lumio.media.Media.current() == null ? null : dev.lumio.media.Cover.texture();
            if (cover != null) {
                dev.lumio.compat.Compat.image(g, cover, ax, ay, ART, ART, dev.lumio.media.Cover.SIZE, Draw.fade(0xFFFFFFFF, a));
            } else {
                g.fillGradient(ax, ay, ax + ART, ay + ART, Draw.fade(from, a), Draw.fade(to, a));
                Component note = Draw.bold("♪");
                Draw.text(g, note, ax + (ART - Draw.width(note)) / 2, ay + 12, Draw.fade(0xFFFFFFFF, a));
            }
            if (!t.playing()) {
                // paused: the cover dims, two bars on it
                g.fill(ax, ay, ax + ART, ay + ART, Draw.fade(0x88000000, a));
                g.fill(ax + 12, ay + 10, ax + 15, ay + 22, Draw.fade(0xFFFFFFFF, a));
                g.fill(ax + 18, ay + 10, ax + 21, ay + 22, Draw.fade(0xFFFFFFFF, a));
            }
            int tx = ax + ART + 7, tw = W - tx - 6;
            Draw.text(g, fit(t.title(), tw, true), tx, 7, Draw.fade(Draw.TEXT, a));
            String times = dev.lumio.media.Track.time(t.positionNow()) + (t.length() > 0 ? " / " + dev.lumio.media.Track.time(t.length()) : "");
            Component time = Draw.inter(times);
            int timeW = Draw.width(time);
            Component who = fit(t.artist() == null || t.artist().isBlank() ? t.app() : t.artist(), tw - timeW - 6, false);
            Draw.text(g, who, tx, 18, Draw.fade(Draw.MUTED, a));
            Draw.text(g, time, tx + tw - timeW, 18, Draw.fade(Draw.FAINT, a));
            // progress
            int by = 31, bw = tw;
            g.fill(tx, by, tx + bw, by + 3, Draw.fade(0x33FFFFFF, a));
            if (t.length() > 0) {
                int done = (int) Math.round(bw * Math.min(1.0, t.positionNow() / (double) t.length()));
                Draw.bar(g, tx, by, done, 3, from, to, a);
            }
        }
    }

    // ---------------------------------------------------------------- the Lumio mark, with FPS and time

    public static final class Watermark extends HudElement {
        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

        public Watermark() {
            super("watermark", true, 0.5, 0);
        }

        private Component rest(Minecraft mc) {
            return Draw.inter("  " + mc.getFps() + " fps  ·  " + LocalTime.now().format(TIME));
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            return Draw.width(Draw.bold("Lumio")) + Draw.width(rest(mc)) + PAD_X * 2 + 6;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return 9 + PAD_Y * 2;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            int w = width(mc, preview), h = height(mc, preview);
            back(g, w, h, a);
            // a small dot that breathes, the name in the theme's running colours, then FPS and time
            float pulse = 0.6f + 0.4f * (float) Math.sin((System.currentTimeMillis() % 2000L) / 2000.0 * Math.PI * 2);
            Draw.circle(g, PAD_X + 2, h / 2, 2, Draw.fade(Draw.wave(0), a * pulse));
            int x = PAD_X + 7;
            x += Draw.gradientText(g, "Lumio", x, PAD_Y + 1, a, true);
            Draw.text(g, rest(mc), x, PAD_Y + 1, Draw.fade(Draw.MUTED, a));
        }
    }

    // ---------------------------------------------------------------- a compass strip along the top

    public static final class Compass extends HudElement {
        private static final int W = 180;
        private static final int H = 18;

        public Compass() {
            super("compass", false, 0.5, 20);
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            return W;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return H;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            back(g, W, H, a);
            float yaw = mc.player == null ? 0 : Mth.wrapDegrees(mc.player.getYRot());
            int mid = W / 2;
            float half = W / 2f - 10;
            // a small line every 15 degrees, a letter every 45
            for (int deg = 0; deg < 360; deg += 15) {
                float diff = Mth.wrapDegrees(deg - yaw);
                if (Math.abs(diff) > 90) continue;
                int x = mid + Math.round(diff / 90f * half);
                float edge = 1 - Math.abs(diff) / 90f;
                if (deg % 45 == 0) {
                    int dir = deg / 45;
                    Component c = Draw.bold(Component.translatable("lumio.dir." + dir));
                    int col = dir == 4 ? Draw.wave(0) : Draw.TEXT;
                    Draw.text(g, c, x - Draw.width(c) / 2, 5, Draw.fade(col, a * (0.35f + 0.65f * edge)));
                } else {
                    g.fill(x, 7, x + 1, 11, Draw.fade(0x80FFFFFF, a * edge));
                }
            }
            // where you look
            g.fill(mid, H - 3, mid + 1, H, Draw.fade(Draw.accent(), a));
            g.fill(mid - 1, H - 2, mid + 2, H - 1, Draw.fade(Draw.accent(), a));
        }
    }

    // ---------------------------------------------------------------- speed in blocks per second

    public static final class Speed extends HudElement {
        private float shown;

        public Speed() {
            super("speed", false, 0.0, 140);
        }

        private Component value(Minecraft mc, boolean preview) {
            float now = 0;
            if (mc.player != null) {
                var v = mc.player.getDeltaMovement();
                now = (float) (Math.hypot(v.x, v.z) * 20);
            } else if (preview) now = 4.3f;
            shown = Draw.approach(shown, now, 0.2f);
            return Component.literal(String.format(java.util.Locale.ROOT, "%.1f b/s", shown));
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            return labelledWidth(Component.translatable("lumio.hud.speed.label"), Component.literal("00.0 b/s")) + PAD_X * 2;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return 9 + PAD_Y * 2;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            back(g, width(mc, preview), height(mc, preview), a);
            labelled(g, Component.translatable("lumio.hud.speed.label"), value(mc, preview), PAD_X, PAD_Y + 1, a);
        }
    }

    // ---------------------------------------------------------------- the biome you stand in

    public static final class Biome extends HudElement {
        public Biome() {
            super("biome", false, 0.0, 163);
        }

        private Component value(Minecraft mc) {
            if (mc.level == null || mc.player == null) return Component.translatable("biome.minecraft.plains");
            String name = mc.level.getBiome(mc.player.blockPosition()).getRegisteredName();
            return Component.translatable("biome." + name.replace(':', '.'));
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            return labelledWidth(Component.translatable("lumio.hud.biome.label"), value(mc)) + PAD_X * 2;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return 9 + PAD_Y * 2;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            back(g, width(mc, preview), height(mc, preview), a);
            labelled(g, Component.translatable("lumio.hud.biome.label"), value(mc), PAD_X, PAD_Y + 1, a);
        }
    }

    // ---------------------------------------------------------------- time played on this server

    public static final class Playtime extends HudElement {
        private static Object level;
        private static long since;

        public Playtime() {
            super("playtime", false, 0.0, 186);
        }

        private Component value(Minecraft mc, boolean preview) {
            // counts from joining: a new world or server starts it again
            if (mc.level == null) return Component.literal(preview ? "1:23:45" : "0:00");
            if (level == null || (mc.getConnection() != null && level != mc.getConnection())) {
                level = mc.getConnection();
                since = System.currentTimeMillis();
            }
            long s = (System.currentTimeMillis() - since) / 1000;
            return Component.literal(s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60));
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            return labelledWidth(Component.translatable("lumio.hud.playtime.label"), Component.literal("00:00:00")) + PAD_X * 2;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return 9 + PAD_Y * 2;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            back(g, width(mc, preview), height(mc, preview), a);
            labelled(g, Component.translatable("lumio.hud.playtime.label"), value(mc, preview), PAD_X, PAD_Y + 1, a);
        }
    }

    // ---------------------------------------------------------------- the day in the game, and its time

    public static final class Day extends HudElement {
        public Day() {
            super("day", false, 0.0, 209);
        }

        private Component value(Minecraft mc) {
            long t = mc.level == null ? 6000 : mc.level.getDayTime();
            long day = t / 24000 + 1;
            long inDay = (t % 24000 + 6000) % 24000;
            int h = (int) (inDay / 1000), m = (int) (inDay % 1000 * 60 / 1000);
            return Component.translatable("lumio.hud.day.value", day, String.format("%02d:%02d", h, m));
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            return labelledWidth(Component.translatable("lumio.hud.day.label"), value(mc)) + PAD_X * 2;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return 9 + PAD_Y * 2;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            back(g, width(mc, preview), height(mc, preview), a);
            labelled(g, Component.translatable("lumio.hud.day.label"), value(mc), PAD_X, PAD_Y + 1, a);
        }
    }

    // ---------------------------------------------------------------- memory in use

    public static final class Memory extends HudElement {
        public Memory() {
            super("memory", false, 0.0, 232);
        }

        private int percent() {
            Runtime r = Runtime.getRuntime();
            return (int) Math.round((r.totalMemory() - r.freeMemory()) * 100.0 / Math.max(1, r.maxMemory()));
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            return labelledWidth(Component.literal("RAM"), Component.literal("100 %")) + PAD_X * 2;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return 9 + PAD_Y * 2 + 3;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            int w = width(mc, preview);
            back(g, w, height(mc, preview), a);
            int p = percent();
            labelled(g, Component.literal("RAM"), Component.literal(p + " %"), PAD_X, PAD_Y + 1, a);
            int bw = w - PAD_X * 2;
            int color = p < 70 ? Draw.SUCCESS : p < 90 ? Draw.WARN : Draw.DANGER;
            g.fill(PAD_X, PAD_Y + 12, PAD_X + bw, PAD_Y + 13, Draw.fade(0x33FFFFFF, a));
            g.fill(PAD_X, PAD_Y + 12, PAD_X + Math.round(bw * p / 100f), PAD_Y + 13, Draw.fade(color, a));
        }
    }

    // ---------------------------------------------------------------- where you died last

    public static final class Death extends HudElement {
        public Death() {
            super("death", true, 0.0, 200);
        }

        @Override
        public boolean active(Minecraft mc) {
            return dev.lumio.extra.DeathPoint.current(mc) != null;
        }

        private Component value(Minecraft mc, boolean preview) {
            dev.lumio.Config.DeathSpot d = dev.lumio.extra.DeathPoint.current(mc);
            if (d == null) return Component.literal("↗ 42 m · 312 64 -88");
            return dev.lumio.extra.DeathPoint.line(mc, d);
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            return labelledWidth(Component.translatable("lumio.hud.death.label"), value(mc, preview)) + PAD_X * 2;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return 9 + PAD_Y * 2;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            back(g, width(mc, preview), height(mc, preview), a);
            labelled(g, Component.translatable("lumio.hud.death.label"), value(mc, preview), PAD_X, PAD_Y + 1, a);
        }
    }
}
