package dev.lumio.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** What differs between Minecraft versions; this copy is for 1.21.4. */
public final class Compat {

    private Compat() {}

    public static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("lumio", path));
    }

    public static KeyMapping key(String name, int glfwKey) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, glfwKey, "key.categories.lumio");
    }

    public static Style openUrl(Style style, String url) {
        return style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
    }
}
