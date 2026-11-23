package dev.lumio.ui;

import dev.lumio.LumioClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * The cards Lumio shows on screen: a request card with two keys (accept / deny), for example a TPA
 * request, and short notice cards. They slide in from the right, count down at the bottom and slide out.
 */
public final class Cards {
    /** A request: what the keys do is either a command (run as the player) or an answer to the server. */
    public record Prompt(String id, Component title, Component text, Component accept, Component deny,
                         String acceptCommand, String denyCommand, long since, long until) {}

    public record Toast(Component title, Component text, int color, long since, long until) {}

    private static final Deque<Prompt> PROMPTS = new ArrayDeque<>();
    private static final List<Toast> TOASTS = new ArrayList<>();
    private static final long IN_MS = 240;
    private static final long OUT_MS = 200;
    private static final int PROMPT_W = 196;
    private static final int TOAST_W = 172;

    /** When the current request was answered, so it can slide out with a tick or a cross. */
    private static Prompt leaving;
    private static boolean leavingAccepted;
    private static long leftAt;

    private Cards() {}

    public static void prompt(Prompt p) {
        PROMPTS.removeIf(q -> q.id().equals(p.id()));
        PROMPTS.addLast(p);
        while (PROMPTS.size() > 8) PROMPTS.removeFirst();
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.6f));
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
        Font font = mc.font;
        long now = System.currentTimeMillis();
        int sw = g.guiWidth();
        int sh = g.guiHeight();

        // notices: bottom right (Minecraft's own toasts use the top right), newest at the bottom
        int y = sh - 44;
        for (Iterator<Toast> it = TOASTS.iterator(); it.hasNext(); ) {
            Toast t = it.next();
            float a = appear(now, t.since(), t.until());
            int h = toastHeight(font, t);
            drawToast(g, font, t, sw - TOAST_W - 8 + slide(a, TOAST_W), y - h, a);
            y -= Math.round((h + 6) * Math.min(1, a * 1.5f));
        }

        // the current request: right side, a third down the screen (below Minecraft's toasts)
        int py = Math.max(64, sh / 3);
        Prompt p = PROMPTS.peekLast();
        if (p != null) {
            float a = appear(now, p.since(), p.until());
            drawPrompt(g, font, p, sw - PROMPT_W - 8 + slide(a, PROMPT_W), py, a, now, null);
        } else if (leaving != null) {
            float a = 1 - Math.min(1, (now - leftAt) / 600f);
            drawPrompt(g, font, leaving, sw - PROMPT_W - 8 + slide(Draw.easeOutCubic(a), PROMPT_W), py, a, now, leavingAccepted);
        }
    }

    private static float appear(long now, long since, long until) {
        float in = Draw.easeOutCubic((now - since) / (float) IN_MS);
        float out = now > until ? 1 - Math.min(1, (now - until) / (float) OUT_MS) : 1;
        return Math.min(in, out);
    }

    private static int slide(float a, int w) {
        return Math.round((1 - a) * (w + 12));
    }

    private static int toastHeight(Font font, Toast t) {
        return 8 + 10 + Math.min(3, font.split(t.text(), TOAST_W - 18).size()) * 10 + 6;
    }

    private static int drawToast(GuiGraphics g, Font font, Toast t, int x, int y, float a) {
        List<FormattedCharSequence> lines = font.split(t.text(), TOAST_W - 18);
        if (lines.size() > 3) lines = lines.subList(0, 3);
        int h = 8 + 10 + lines.size() * 10 + 6;
        Draw.card(g, x, y, TOAST_W, h, a);
        Draw.stripe(g, x + 4, y + 5, h - 10, t.color(), Draw.mix(t.color(), 0xFF000000, 0.35f), a);
        if (!Draw.visible(a)) return h;
        g.drawString(font, t.title().copy().withStyle(ChatFormatting.BOLD), x + 11, y + 7, Draw.fade(Draw.TEXT, a), false);
        int ly = y + 19;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, x + 11, ly, Draw.fade(Draw.MUTED, a), false);
            ly += 10;
        }
        return h;
    }

    private static void drawPrompt(GuiGraphics g, Font font, Prompt p, int x, int y, float a, long now, Boolean result) {
        List<FormattedCharSequence> lines = font.split(p.text(), PROMPT_W - 20);
        if (lines.size() > 3) lines = lines.subList(0, 3);
        int h = 9 + 11 + lines.size() * 10 + 6 + 14 + 8;
        Draw.card(g, x, y, PROMPT_W, h, a);
        Draw.stripe(g, x + 4, y + 6, h - 12, Draw.ACCENT, Draw.ACCENT_2, a);
        if (!Draw.visible(a)) return;
        g.drawString(font, p.title().copy().withStyle(ChatFormatting.BOLD), x + 12, y + 8, Draw.fade(Draw.TEXT, a), false);
        int ly = y + 21;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, x + 12, ly, Draw.fade(Draw.MUTED, a), false);
            ly += 10;
        }
        int ky = ly + 5;
        if (result == null) {
            int kx = pill(g, font, LumioClient.acceptKeyName(), p.accept(), x + 12, ky, Draw.SUCCESS, a);
            pill(g, font, LumioClient.denyKeyName(), p.deny(), kx + 8, ky, Draw.DANGER, a);
            // time left, as a thin gradient bar along the bottom
            float left = Math.max(0, Math.min(1, (p.until() - now) / (float) Math.max(1, p.until() - p.since())));
            int bw = PROMPT_W - 24;
            Draw.round(g, x + 12, y + h - 6, bw, 2, Draw.fade(0x22FFFFFF, a));
            Draw.bar(g, x + 12, y + h - 6, Math.round(bw * left), 2, Draw.ACCENT, Draw.ACCENT_2, a);
        } else {
            Component done = Component.translatable(result ? "lumio.prompt.accepted" : "lumio.prompt.denied");
            g.drawString(font, done, x + 12, ky + 3, Draw.fade(result ? Draw.SUCCESS : Draw.DANGER, a), false);
        }
    }

    /** A key hint: the key in a small rounded box, then what it does. Returns where the next one may start. */
    private static int pill(GuiGraphics g, Font font, Component key, Component label, int x, int y, int color, float a) {
        int kw = Math.max(12, font.width(key) + 8);
        Draw.round(g, x, y, kw, 13, Draw.fade(Draw.BG_SOFT, a));
        Draw.round(g, x, y + 11, kw, 2, Draw.fade(color, a * 0.9f));
        g.drawString(font, key, x + (kw - font.width(key)) / 2, y + 2, Draw.fade(Draw.TEXT, a), false);
        g.drawString(font, label, x + kw + 5, y + 3, Draw.fade(Draw.TEXT, a), false);
        return x + kw + 5 + font.width(label);
    }
}
