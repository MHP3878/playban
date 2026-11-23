package dev.lumio.compat;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** A screen with plain mouse and key methods, the same in every version (here: 1.21.9 and newer). */
public abstract class BaseScreen extends Screen {
    protected BaseScreen(Component title) {
        super(title);
    }

    protected abstract boolean click(double x, double y, int button);

    protected boolean release(double x, double y, int button) {
        return false;
    }

    protected boolean drag(double x, double y, int button, double dx, double dy) {
        return false;
    }

    protected boolean scroll(double x, double y, double amount) {
        return false;
    }

    protected boolean key(int key, int scancode, int modifiers) {
        return false;
    }

    protected boolean typed(int codepoint) {
        return false;
    }

    /**
     * The screen's own size factor: drawn with it, and the mouse is measured in it, so the screen and
     * its input fields work in their own smaller (or bigger) units. 1 = Minecraft's GUI units.
     */
    protected float uiScale() {
        return 1f;
    }

    /** The screen's width and height in its own units. */
    protected int vw() {
        return Math.round(width / uiScale());
    }

    protected int vh() {
        return Math.round(height / uiScale());
    }

    private MouseButtonEvent scaled(MouseButtonEvent e) {
        float s = uiScale();
        return s == 1f ? e : new MouseButtonEvent(e.x() / s, e.y() / s, e.buttonInfo());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        MouseButtonEvent v = scaled(e);
        return click(v.x(), v.y(), v.button()) || super.mouseClicked(v, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        MouseButtonEvent v = scaled(e);
        return release(v.x(), v.y(), v.button()) || super.mouseReleased(v);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        MouseButtonEvent v = scaled(e);
        float s = uiScale();
        return drag(v.x(), v.y(), v.button(), dx / s, dy / s) || super.mouseDragged(v, dx / s, dy / s);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        float s = uiScale();
        return scroll(x / s, y / s, v) || super.mouseScrolled(x / s, y / s, h, v);
    }

    @Override
    public void mouseMoved(double x, double y) {
        float s = uiScale();
        super.mouseMoved(x / s, y / s);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        return key(e.key(), e.scancode(), e.modifiers()) || super.keyPressed(e);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        return typed(e.codepoint()) || super.charTyped(e);
    }
}
