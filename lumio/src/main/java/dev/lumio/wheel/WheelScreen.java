package dev.lumio.wheel;

import dev.lumio.Config;
import dev.lumio.LumioClient;
import dev.lumio.compat.BaseScreen;
import dev.lumio.compat.Compat;
import dev.lumio.ui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The radial menu: while its key is held, the fields stand in a circle; the mouse points at one and
 * letting go of the key runs it. A field with a next page opens that page in the same circle; the
 * middle then leads back.
 */
public final class WheelScreen extends BaseScreen {
    private static final int RADIUS = 78;
    private static final int BUBBLE = 18;
    private static final int DEAD = 24;

    private final Deque<List<Config.WheelEntry>> pages = new ArrayDeque<>();
    private final long opened = System.currentTimeMillis();
    private long pageSince = opened;
    /** after a page was opened by letting go, the key no longer closes the menu: a click does */
    private boolean clickMode;
    private final float[] grow = new float[Wheel.MAX];
    private int hovered = -1;

    public WheelScreen() {
        super(Component.translatable("key.lumio.wheel"));
        pages.push(Wheel.entries());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x30000000);
    }

    private List<Config.WheelEntry> page() {
        List<Config.WheelEntry> p = pages.peek();
        return p.size() > Wheel.MAX ? p.subList(0, Wheel.MAX) : p;
    }

    private int pick(double mx, double my) {
        double dx = mx - vw() / 2.0, dy = my - vh() / 2.0;
        if (Math.hypot(dx, dy) < DEAD) return -1;
        int n = page().size();
        if (n == 0) return -1;
        double angle = Math.toDegrees(Math.atan2(dy, dx)) + 90;
        angle = ((angle % 360) + 360 + 180.0 / n) % 360;
        return (int) (angle / (360.0 / n)) % n;
    }

    @Override
    protected float uiScale() {
        return dev.lumio.ui.Ui.scale();
    }

    @Override
    public void render(GuiGraphics g, int rmx, int rmy, float delta) {
        super.render(g, rmx, rmy, delta);
        // in Lumio's own size: everything below is in its units
        float sc = uiScale();
        int mx = (int) (rmx / sc), my = (int) (rmy / sc);
        Compat.push(g, 0, 0, sc);
        try {
            drawWheel(g, mx, my);
        } finally {
            Compat.pop(g);
        }
    }

    private void drawWheel(GuiGraphics g, int mx, int my) {
        // letting go of the key runs the field under the mouse
        if (!clickMode && System.currentTimeMillis() - opened > 80 && !LumioClient.wheelHeld()) {
            choose(hovered);
            return;
        }
        float a = Draw.easeOutCubic((System.currentTimeMillis() - pageSince) / 180f);
        int cx = vw() / 2, cy = vh() / 2;
        int r = Math.round(RADIUS * (0.75f + 0.25f * a));
        List<Config.WheelEntry> page = page();
        hovered = pick(mx, my);
        // the disc behind it all
        Draw.circle(g, cx, cy, r + BUBBLE + 20, Draw.fade(Draw.wave(0), 0.18f * a));
        Draw.circle(g, cx, cy, r + BUBBLE + 14, Draw.fade(0x99101016, a));
        Draw.ring(g, cx, cy, r + BUBBLE + 14, Draw.fade(0x22FFFFFF, a));
        int n = page.size();
        for (int i = 0; i < n; i++) {
            Config.WheelEntry e = page.get(i);
            double ang = Math.toRadians(-90 + i * 360.0 / n);
            int bx = cx + (int) Math.round(Math.cos(ang) * r);
            int by = cy + (int) Math.round(Math.sin(ang) * r);
            grow[i] = Draw.approach(grow[i], i == hovered ? 1 : 0, 0.3f);
            int br = BUBBLE + Math.round(4 * grow[i]);
            Draw.circle(g, bx, by, br, Draw.fade(Draw.mix(0xFF23232D, Draw.accent(), grow[i] * 0.85f), a));
            if (grow[i] > 0.05f) Draw.ring(g, bx, by, br + 2, Draw.fade(Draw.accent2(), a * grow[i]));
            g.renderItem(new ItemStack(Compat.item(e.icon)), bx - 8, by - 9);
            if ("page".equals(e.type)) Draw.text(g, Draw.bold("›"), bx + br - 7, by - br + 1, Draw.fade(Draw.TEXT, a));
            Component name = Draw.inter(Component.literal(shortName(e.name)));
            Draw.centered(g, name, bx, by + br + 3, Draw.fade(i == hovered ? Draw.TEXT : Draw.MUTED, a));
        }
        // the middle: what is pointed at, or back
        Draw.circle(g, cx, cy, DEAD, Draw.fade(Draw.BG, a));
        Component mid;
        if (hovered >= 0) mid = Component.literal(page.get(hovered).name);
        else if (pages.size() > 1) mid = Component.translatable("lumio.wheel.back");
        else mid = Component.literal(n == 0 ? "—" : "Lumio");
        List<FormattedCharSequence> lines = Draw.wrap(Draw.bold(mid), DEAD * 2 - 8, 2);
        int ly = cy - lines.size() * 5;
        for (FormattedCharSequence line : lines) {
            Draw.text(g, line, cx - Draw.font().width(line) / 2, ly, Draw.fade(Draw.TEXT, a));
            ly += 10;
        }
    }

    private static String shortName(String s) {
        return s == null ? "" : s.length() > 12 ? s.substring(0, 11) + "…" : s;
    }

    private void choose(int index) {
        if (index < 0) {
            if (pages.size() > 1) {
                pages.pop();
                pageSince = System.currentTimeMillis();
                clickMode = true;
                return;
            }
            onClose();
            return;
        }
        List<Config.WheelEntry> next = Wheel.run(page().get(index));
        if (next != null) {
            pages.push(next);
            pageSince = System.currentTimeMillis();
            clickMode = true;
            return;
        }
        onClose();
    }

    @Override
    protected boolean click(double x, double y, int button) {
        if (button == 0) {
            choose(pick(x, y));
            return true;
        }
        if (button == 1) {
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
