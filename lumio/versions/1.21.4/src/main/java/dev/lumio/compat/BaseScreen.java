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

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return click(x, y, button) || super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return release(x, y, button) || super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return drag(x, y, button, dx, dy) || super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        return scroll(x, y, v) || super.mouseScrolled(x, y, h, v);
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
