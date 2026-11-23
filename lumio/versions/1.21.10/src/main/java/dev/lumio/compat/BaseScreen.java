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

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        return click(e.x(), e.y(), e.button()) || super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        return release(e.x(), e.y(), e.button()) || super.mouseReleased(e);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        return drag(e.x(), e.y(), e.button(), dx, dy) || super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        return scroll(x, y, v) || super.mouseScrolled(x, y, h, v);
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
