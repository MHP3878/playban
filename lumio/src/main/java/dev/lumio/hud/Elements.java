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

        @Override
        public int width(Minecraft mc, boolean preview) {
            return labelledWidth(Component.literal("XYZ"), value(mc, preview)) + PAD_X * 2;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return 9 + PAD_Y * 2;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            back(g, width(mc, preview), height(mc, preview), a);
            labelled(g, Component.literal("XYZ"), value(mc, preview), PAD_X, PAD_Y + 1, a);
        }
    }

    // ---------------------------------------------------------------- frames per second

    public static final class Fps extends HudElement {
        public Fps() {
            super("fps", true, 0.0, 23);
        }

        private Component value(Minecraft mc) {
            return Component.literal(String.valueOf(mc.getFps()));
        }

        @Override
        public int width(Minecraft mc, boolean preview) {
            return labelledWidth(Component.literal("FPS"), value(mc)) + PAD_X * 2;
        }

        @Override
        public int height(Minecraft mc, boolean preview) {
            return 9 + PAD_Y * 2;
        }

        @Override
        public void render(GuiGraphics g, Minecraft mc, boolean preview, float a) {
            back(g, width(mc, preview), height(mc, preview), a);
            labelled(g, Component.literal("FPS"), value(mc), PAD_X, PAD_Y + 1, a);
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
                    Draw.text(g, Draw.inter(Math.round(left * 100) + "%"), PAD_X + 19, y + 5, Draw.fade(color, a));
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
            super("keys", false, 1.0, 40);
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
            super("server", true, 0.0, 92);
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
}
