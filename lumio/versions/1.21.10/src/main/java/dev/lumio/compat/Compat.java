package dev.lumio.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** What differs between Minecraft versions; this copy is for 1.21.10. */
public final class Compat {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(id("main"));

    private Compat() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("lumio", path);
    }

    public static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(id(path));
    }

    public static KeyMapping key(String name, int glfwKey) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, glfwKey, CATEGORY);
    }

    public static Style openUrl(Style style, String url) {
        return style.withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(url)));
    }

    /** Lumio's own font ("inter" or "inter_bold"). */
    public static Style font(Style style, String name) {
        return style.withFont(new net.minecraft.network.chat.FontDescription.Resource(id(name)));
    }

    /** A Lumio GUI sprite (textures/gui/sprites/name.png), tinted with the colour. */
    public static void sprite(GuiGraphics g, String name, int x, int y, int w, int h, int argb) {
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, id(name), x, y, w, h, argb);
    }

    /** Whether this key press is the key bound to the mapping. */
    public static boolean matches(KeyMapping k, int key, int scancode) {
        return k.matches(new net.minecraft.client.input.KeyEvent(key, scancode, 0));
    }
}
