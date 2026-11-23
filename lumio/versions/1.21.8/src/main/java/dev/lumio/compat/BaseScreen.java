package dev.lumio.compat;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A screen with plain mouse and key methods, the same in every version (here: up to 1.21.8). */
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

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        float s = uiScale();
        return click(x / s, y / s, button) || super.mouseClicked(x / s, y / s, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        float s = uiScale();
        return release(x / s, y / s, button) || super.mouseReleased(x / s, y / s, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        float s = uiScale();
        return drag(x / s, y / s, button, dx / s, dy / s) || super.mouseDragged(x / s, y / s, button, dx / s, dy / s);
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
    public boolean keyPressed(int key, int scancode, int modifiers) {
        return key(key, scancode, modifiers) || super.keyPressed(key, scancode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        return typed(c) || super.charTyped(c, modifiers);
    }
}
