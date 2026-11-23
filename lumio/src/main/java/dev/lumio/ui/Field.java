package dev.lumio.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A text field in Lumio's own smooth font (Minecraft's own field only knows the pixel font): typing,
 * the cursor with the arrow keys, Home and End, Backspace and Delete, Ctrl+V to paste, Ctrl+A to
 * clear-and-retype. Long text scrolls so the cursor stays in view.
 */
public final class Field {
    public int x, y, w, h = 18;
    public boolean visible = true;
    private String value = "";
    private int cursor;
    private boolean focused;
    private final Component hint;
    private final int max;
    private Consumer<String> responder = v -> { };
    private long blinkFrom = System.currentTimeMillis();

    public Field(int x, int y, int w, Component hint, int max) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.hint = hint;
        this.max = max;
    }

    public String getValue() {
        return value;
    }

    /** Sets the text without telling the responder (like filling a form). */
    public void setValue(String v) {
        value = v == null ? "" : v.length() > max ? v.substring(0, max) : v;
        cursor = value.length();
    }

    public void setResponder(Consumer<String> r) {
        responder = r == null ? v -> { } : r;
    }

    public boolean isFocused() {
        return focused && visible;
    }

    public void setFocused(boolean f) {
        focused = f;
        blinkFrom = System.currentTimeMillis();
    }

    public boolean contains(double mx, double my) {
        return visible && mx >= x && my >= y && mx < x + w && my < y + h;
    }

    private void changed(String v) {
        value = v;
        responder.accept(value);
        blinkFrom = System.currentTimeMillis();
    }

    // ---------------------------------------------------------------- drawing

    public void render(GuiGraphics g, float a) {
        if (!visible) return;
        Draw.round(g, x, y, w, h, Draw.fade(0xFF0E0E14, a));
        Draw.outline(g, x, y, w, h, Draw.fade(focused ? Draw.accent() : 0x22FFFFFF, a));
        int inner = w - 12;
        int tx = x + 6, ty = y + (h - 8) / 2;
        if (value.isEmpty() && !focused) {
            Draw.text(g, Draw.inter(hint), tx, ty, Draw.fade(Draw.FAINT, a));
            return;
        }
        // scroll: the part before the cursor that fits, then what follows
        String before = value.substring(0, cursor);
        int start = 0;
        while (start < before.length() && Draw.width(Draw.inter(before.substring(start))) > inner - 4) start++;
        String shown = value.substring(start);
        while (!shown.isEmpty() && Draw.width(Draw.inter(shown)) > inner) shown = shown.substring(0, shown.length() - 1);
        Draw.text(g, Draw.inter(shown), tx, ty, Draw.fade(Draw.TEXT, a));
        if (focused && ((System.currentTimeMillis() - blinkFrom) / 500) % 2 == 0) {
            int cx = tx + Draw.width(Draw.inter(before.substring(start)));
            g.fill(cx, ty - 1, cx + 1, ty + 9, Draw.fade(Draw.accent(), a));
        }
    }

    // ---------------------------------------------------------------- input

    /** A click inside puts the cursor at the nearest letter. */
    public void click(double mx) {
        setFocused(true);
        int best = value.length();
        double rel = mx - (x + 6);
        for (int i = 0; i <= value.length(); i++) {
            if (Draw.width(Draw.inter(value.substring(0, i))) >= rel) {
                best = i;
                break;
            }
        }
        cursor = best;
    }

    public boolean typed(int codepoint) {
        if (!isFocused() || Character.isISOControl(codepoint) || codepoint == 0xA7) return false;
        String add = new String(Character.toChars(codepoint));
        if (value.length() + add.length() > max) return true;
        String v = value.substring(0, cursor) + add + value.substring(cursor);
        cursor += add.length();
        changed(v);
        return true;
    }

    public boolean key(int key, int modifiers) {
        if (!isFocused()) return false;
        boolean ctrl = (modifiers & 2) != 0 || (modifiers & 8) != 0; // Ctrl, or Cmd on a Mac
        switch (key) {
            case 259 -> { // backspace
                if (cursor > 0) {
                    String v = value.substring(0, cursor - 1) + value.substring(cursor);
                    cursor--;
                    changed(v);
                }
            }
            case 261 -> { // delete
                if (cursor < value.length()) changed(value.substring(0, cursor) + value.substring(cursor + 1));
            }
            case 263 -> cursor = Math.max(0, cursor - 1);
            case 262 -> cursor = Math.min(value.length(), cursor + 1);
            case 268 -> cursor = 0;
            case 269 -> cursor = value.length();
            case 257, 335, 256 -> setFocused(false); // enter, keypad enter, escape: done
            case 86 -> { // V
                if (!ctrl) return false;
                String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                if (clip == null) return true;
                clip = clip.replaceAll("[\\p{Cntrl}§]", "");
                String v = value.substring(0, cursor) + clip + value.substring(cursor);
                if (v.length() > max) v = v.substring(0, max);
                cursor = Math.min(v.length(), cursor + clip.length());
                changed(v);
            }
            case 65 -> { // A: everything away, to type it new
                if (!ctrl) return false;
                cursor = 0;
                changed("");
            }
            default -> {
                return false;
            }
        }
        blinkFrom = System.currentTimeMillis();
        return true;
    }
}
