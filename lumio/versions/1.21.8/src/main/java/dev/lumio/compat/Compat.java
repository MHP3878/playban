package dev.lumio.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** What differs between Minecraft versions; this copy is for 1.21.8. */
public final class Compat {
    private Compat() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("lumio", path);
    }

    public static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(id(path));
    }

    public static KeyMapping key(String name, int glfwKey) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, glfwKey, "key.categories.lumio");
    }

    public static Style openUrl(Style style, String url) {
        return style.withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(url)));
    }

    /** Lumio's own font ("inter" or "inter_bold"). */
    public static Style font(Style style, String name) {
        return style.withFont(id(name));
    }

    /** A Lumio GUI sprite (textures/gui/sprites/name.png), tinted with the colour. */
    public static void sprite(GuiGraphics g, String name, int x, int y, int w, int h, int argb) {
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, id(name), x, y, w, h, argb);
    }

    /** Whether this key press is the key bound to the mapping. */
    public static boolean matches(KeyMapping k, int key, int scancode) {
        return k.matches(key, scancode);
    }

    /** Draw from here on moved to (x, y) and scaled by s, until pop. */
    public static void push(GuiGraphics g, float x, float y, float s) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(s, s);
    }

    public static void pop(GuiGraphics g) {
        g.pose().popMatrix();
    }

    /** The game window, for asking whether a key is held. */
    public static long window() {
        return net.minecraft.client.Minecraft.getInstance().getWindow().getWindow();
    }

    /** An item by its id ("minecraft:compass"); a compass when there is no such item. */
    public static net.minecraft.world.item.Item item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id == null ? "" : id);
        if (key == null) return net.minecraft.world.item.Items.COMPASS;
        net.minecraft.world.item.Item i = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(key);
        return i == null || i == net.minecraft.world.item.Items.AIR ? net.minecraft.world.item.Items.COMPASS : i;
    }

    /** Whether Screen#render itself draws the background (up to 1.21.5) or Minecraft does it before (1.21.6+). */
    public static final boolean BACKGROUND_IN_RENDER = false;

    /** A picture made at run time (e.g. an album cover) as texture lumio:dynamic/name; replaces the one before. */
    public static Object texture(String name, com.mojang.blaze3d.platform.NativeImage img) {
        ResourceLocation key = id("dynamic/" + name);
        net.minecraft.client.Minecraft.getInstance().getTextureManager().register(key, new net.minecraft.client.renderer.texture.DynamicTexture(() -> "lumio " + name, img));
        return key;
    }

    /** Draws a texture from {@link #texture} (size×size pixels) into w×h. */
    public static void image(GuiGraphics g, Object texture, int x, int y, int w, int h, int size, int argb) {
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, (ResourceLocation) texture, x, y, 0f, 0f, w, h, size, size, size, size, argb);
    }
}
