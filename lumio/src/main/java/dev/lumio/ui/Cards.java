package dev.lumio.ui;

import dev.lumio.Config;
import dev.lumio.LumioClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The cards Lumio shows on screen: a request card with two keys (accept / deny), for example a TPA
 * request, and short notice cards. They slide in from the side, count down and slide out again.
 */
public final class Cards {
    /** A request: what the keys do is either a command (run as the player) or an answer to the server. */
    public record Prompt(String id, Component title, Component text, Component accept, Component deny,
                         String acceptCommand, String denyCommand, long since, long until) {}

    public record Toast(Component title, Component text, int color, long since, long until) {}

    private static final Deque<Prompt> PROMPTS = new ArrayDeque<>();
    private static final List<Toast> TOASTS = new ArrayList<>();
    private static final long IN_MS = 260;
    private static final long OUT_MS = 220;
    private static final int PROMPT_W = 200;
    private static final int TOAST_W = 176;

    /** The request just answered, so it can slide out with a tick or a cross. */
    private static Prompt leaving;
    private static boolean leavingAccepted;
    private static long leftAt;

    private Cards() {}

    private static void ping(float pitch) {
        if (Config.get().sounds) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, pitch));
    }

    public static void prompt(Prompt p) {
        PROMPTS.removeIf(q -> q.id().equals(p.id()));
        PROMPTS.addLast(p);
        while (PROMPTS.size() > 8) PROMPTS.removeFirst();
        ping(1.6f);
    }

    public static void toast(Component title, Component text, int color, int seconds) {
        long now = System.currentTimeMillis();
        TOASTS.add(0, new Toast(title, text, color, now, now + Math.max(2, seconds) * 1000L));
        while (TOASTS.size() > 4) TOASTS.remove(TOASTS.size() - 1);
    }

    public static boolean hasPrompt() {
        return !PROMPTS.isEmpty();
    }

    /** A key was pressed: the newest request is answered. */
    public static void answer(boolean accepted) {
        Prompt p = PROMPTS.pollLast();
        if (p == null) return;
        leaving = p;
        leavingAccepted = accepted;
        leftAt = System.currentTimeMillis();
        ping(accepted ? 2.0f : 0.8f);
        LumioClient.answered(p, accepted);
    }

    public static void clear() {
        PROMPTS.clear();
        TOASTS.clear();
        leaving = null;
    }

    public static void tick() {
        long now = System.currentTimeMillis();
        PROMPTS.removeIf(p -> now > p.until() + OUT_MS);
        TOASTS.removeIf(t -> now > t.until() + OUT_MS);
        if (leaving != null && now - leftAt > 700) leaving = null;
    }

    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        long now = System.currentTimeMillis();
        // in Lumio's own size, the same on every GUI scale
        float sc = Ui.scale();
        int sw = Math.round(g.guiWidth() / sc);
        int sh = Math.round(g.guiHeight() / sc);
        boolean left = "left".equals(Config.get().cardSide);
        dev.lumio.compat.Compat.push(g, 0, 0, sc);
        try {
            drawAll(g, now, sw, sh, left);
        } finally {
            dev.lumio.compat.Compat.pop(g);
        }
    }

    private static void drawAll(GuiGraphics g, long now, int sw, int sh, boolean left) {

        // notices: bottom corner (Minecraft's own toasts use the top right), newest at the bottom
        int y = sh - 46;
        for (Toast t : TOASTS) {
            float a = appear(now, t.since(), t.until());
            List<FormattedCharSequence> lines = Draw.wrap(Draw.inter(t.text()), TOAST_W - 22, 3);
            int h = 9 + 11 + lines.size() * 10 + 7;
            int x = left ? 8 - slide(a, TOAST_W) : sw - TOAST_W - 8 + slide(a, TOAST_W);
            drawToast(g, t, lines, x, y - h, h, a);
            y -= Math.round((h + 6) * Math.min(1, a * 1.5f));
        }

        // the current request: at the side, a third down the screen (below Minecraft's toasts)
        int py = Math.max(64, sh / 3);
        Prompt p = PROMPTS.peekLast();
        if (p != null) {
            float a = appear(now, p.since(), p.until());
            int x = left ? 8 - slide(a, PROMPT_W) : sw - PROMPT_W - 8 + slide(a, PROMPT_W);
            drawPrompt(g, p, x, py, a, now, null);
        } else if (leaving != null) {
            float a = 1 - Math.min(1, (now - leftAt) / 600f);
            float s = Draw.easeOutCubic(a);
            int x = left ? 8 - slide(s, PROMPT_W) : sw - PROMPT_W - 8 + slide(s, PROMPT_W);
            drawPrompt(g, leaving, x, py, a, now, leavingAccepted);
        }
    }

    private static float appear(long now, long since, long until) {
        float in = Draw.easeOutCubic((now - since) / (float) IN_MS);
        float out = now > until ? 1 - Math.min(1, (now - until) / (float) OUT_MS) : 1;
        return Math.min(in, out);
    }

    private static int slide(float a, int w) {
        return Math.round((1 - a) * (w + 16));
    }

    private static void drawToast(GuiGraphics g, Toast t, List<FormattedCharSequence> lines, int x, int y, int h, float a) {
        Draw.panel(g, x, y, TOAST_W, h, a);
        g.fillGradient(x + 6, y + 7, x + 8, y + h - 7, Draw.fade(t.color(), a), Draw.fade(Draw.mix(t.color(), 0xFF000000, 0.4f), a));
        if (!Draw.visible(a)) return;
        Draw.text(g, Draw.bold(t.title()), x + 14, y + 8, Draw.fade(Draw.TEXT, a));
        int ly = y + 20;
        for (FormattedCharSequence line : lines) {
            Draw.text(g, line, x + 14, ly, Draw.fade(Draw.MUTED, a));
            ly += 10;
        }
    }

    private static void drawPrompt(GuiGraphics g, Prompt p, int x, int y, float a, long now, Boolean result) {
        List<FormattedCharSequence> lines = Draw.wrap(Draw.inter(p.text()), PROMPT_W - 26, 3);
        int h = 10 + 12 + lines.size() * 10 + 8 + 16 + 10;
        Draw.glow(g, x, y, PROMPT_W, h, a * 0.6f);
        Draw.panel(g, x, y, PROMPT_W, h, a);
        Draw.stripe(g, x + 6, y + 8, h - 16, a);
        if (!Draw.visible(a)) return;
        Draw.text(g, Draw.bold(p.title()), x + 15, y + 10, Draw.fade(Draw.TEXT, a));
        int ly = y + 23;
        for (FormattedCharSequence line : lines) {
            Draw.text(g, line, x + 15, ly, Draw.fade(Draw.MUTED, a));
            ly += 10;
        }
        int ky = ly + 6;
        if (result == null) {
            int kx = pill(g, LumioClient.acceptKeyName(), p.accept(), x + 15, ky, Draw.SUCCESS, a);
            pill(g, LumioClient.denyKeyName(), p.deny(), kx + 10, ky, Draw.DANGER, a);
            // time left, as a thin gradient bar along the bottom
            float left = Math.max(0, Math.min(1, (p.until() - now) / (float) Math.max(1, p.until() - p.since())));
            int bw = PROMPT_W - 30;
            g.fill(x + 15, y + h - 6, x + 15 + bw, y + h - 4, Draw.fade(0x1FFFFFFF, a));
            Draw.bar(g, x + 15, y + h - 6, Math.round(bw * left), 2, a);
        } else {
            Component done = Component.translatable(result ? "lumio.prompt.accepted" : "lumio.prompt.denied");
            Draw.text(g, Draw.bold(done), x + 15, ky + 4, Draw.fade(result ? Draw.SUCCESS : Draw.DANGER, a));
        }
    }

    /** A key hint: the key in a small rounded box, then what it does. Returns where the next one may start. */
    private static int pill(GuiGraphics g, Component key, Component label, int x, int y, int color, float a) {
        Component k = Draw.bold(key);
        Component l = Draw.inter(label);
        int kw = Math.max(16, Draw.width(k) + 10);
        Draw.round(g, x, y, kw, 15, Draw.fade(Draw.BG_SOFT, a));
        g.fill(x + 3, y + 13, x + kw - 3, y + 14, Draw.fade(color, a));
        Draw.text(g, k, x + (kw - Draw.width(k)) / 2, y + 4, Draw.fade(Draw.TEXT, a));
        Draw.text(g, l, x + kw + 6, y + 4, Draw.fade(Draw.TEXT, a));
        return x + kw + 6 + Draw.width(l);
    }
}
