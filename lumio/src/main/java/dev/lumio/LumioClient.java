package dev.lumio;

import com.mojang.brigadier.Command;
import dev.lumio.compat.Compat;
import dev.lumio.net.JsonPayload;
import dev.lumio.net.Protocol;
import dev.lumio.ui.Cards;
import dev.lumio.ui.Draw;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import dev.lumio.ui.SettingsScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Lumio: a clean everyday client mod. This is where it starts. */
public final class LumioClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("Lumio");
    private static String version = "0";
    private static KeyMapping accept;
    private static KeyMapping deny;
    private static KeyMapping menu;
    private static KeyMapping wheel;
    private static KeyMapping mediaToggle;
    private static KeyMapping mediaNext;
    private static KeyMapping mediaPrevious;
    /** a screen to open on the next tick (a command closes the chat after it ran) */
    private static Runnable openNextTick;

    public static String version() {
        return version;
    }

    public static Component acceptKeyName() {
        return accept.getTranslatedKeyMessage();
    }

    public static Component denyKeyName() {
        return deny.getTranslatedKeyMessage();
    }

    public static Component menuKeyName() {
        return menu.getTranslatedKeyMessage();
    }

    public static Component wheelKeyName() {
        return wheel.getTranslatedKeyMessage();
    }

    public static boolean isMenuKey(int key, int scancode) {
        return Compat.matches(menu, key, scancode);
    }

    public static void openMenu() {
        Minecraft.getInstance().setScreen(new SettingsScreen());
    }

    public static void openHudEditor() {
        Minecraft.getInstance().setScreen(new dev.lumio.hud.HudEditorScreen());
    }

    public static void openWheelEditor() {
        Minecraft.getInstance().setScreen(new SettingsScreen(SettingsScreen.Tab.WHEEL));
    }

    /** Whether the radial menu key is held right now (asked from the window: a screen is open meanwhile). */
    public static boolean wheelHeld() {
        var key = KeyBindingHelper.getBoundKeyOf(wheel);
        long window = Compat.window();
        if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE)
            return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        return key.getValue() >= 0 && GLFW.glfwGetKey(window, key.getValue()) == GLFW.GLFW_PRESS;
    }

    /** Shows what the cards look like, without a server. */
    public static void demo() {
        long now = System.currentTimeMillis();
        Cards.toast(Component.translatable("lumio.demo.toast.title"), Component.translatable("lumio.demo.toast.text"), Draw.SUCCESS, 8);
        Cards.prompt(new Cards.Prompt("demo", Component.translatable("lumio.demo.prompt.title"),
                Component.translatable("lumio.demo.prompt.text"), Component.translatable("lumio.prompt.accept"),
                Component.translatable("lumio.prompt.deny"), "", "", now, now + 30_000));
    }

    public static void answered(Cards.Prompt p, boolean accepted) {
        Protocol.answer(p, accepted);
    }

    @Override
    @SuppressWarnings("deprecation") // HudRenderCallback is the one hook every supported version has
    public void onInitializeClient() {
        version = FabricLoader.getInstance().getModContainer("lumio").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0");
        // the fingerprints of the mod files are worked out in the background now, not when joining
        ModList.get();

        PayloadTypeRegistry.playS2C().register(JsonPayload.HELLO, JsonPayload.codec(JsonPayload.HELLO));
        PayloadTypeRegistry.playS2C().register(JsonPayload.PROMPT, JsonPayload.codec(JsonPayload.PROMPT));
        PayloadTypeRegistry.playS2C().register(JsonPayload.TOAST, JsonPayload.codec(JsonPayload.TOAST));
        PayloadTypeRegistry.playS2C().register(JsonPayload.HUD, JsonPayload.codec(JsonPayload.HUD));
        PayloadTypeRegistry.playS2C().register(JsonPayload.WHEEL, JsonPayload.codec(JsonPayload.WHEEL));
        PayloadTypeRegistry.playC2S().register(JsonPayload.REPORT, JsonPayload.codec(JsonPayload.REPORT));
        PayloadTypeRegistry.playC2S().register(JsonPayload.ANSWER, JsonPayload.codec(JsonPayload.ANSWER));
        PayloadTypeRegistry.playC2S().register(JsonPayload.INTEGRITY, JsonPayload.codec(JsonPayload.INTEGRITY));
        ClientPlayNetworking.registerGlobalReceiver(JsonPayload.HELLO, (p, ctx) -> Protocol.onHello(p.json()));
        ClientPlayNetworking.registerGlobalReceiver(JsonPayload.PROMPT, (p, ctx) -> Protocol.onPrompt(p.json()));
        ClientPlayNetworking.registerGlobalReceiver(JsonPayload.TOAST, (p, ctx) -> Protocol.onToast(p.json()));
        ClientPlayNetworking.registerGlobalReceiver(JsonPayload.HUD, (p, ctx) -> Protocol.onHud(p.json()));
        ClientPlayNetworking.registerGlobalReceiver(JsonPayload.WHEEL, (p, ctx) -> Protocol.onWheel(p.json()));

        accept = KeyBindingHelper.registerKeyBinding(Compat.key("key.lumio.accept", GLFW.GLFW_KEY_Y));
        deny = KeyBindingHelper.registerKeyBinding(Compat.key("key.lumio.deny", GLFW.GLFW_KEY_N));
        menu = KeyBindingHelper.registerKeyBinding(Compat.key("key.lumio.menu", GLFW.GLFW_KEY_RIGHT_SHIFT));
        wheel = KeyBindingHelper.registerKeyBinding(Compat.key("key.lumio.wheel", GLFW.GLFW_KEY_G));
        // music: no key by default, players choose their own (the keyboard's media keys work anyway)
        mediaToggle = KeyBindingHelper.registerKeyBinding(Compat.key("key.lumio.media.toggle", GLFW.GLFW_KEY_UNKNOWN));
        mediaNext = KeyBindingHelper.registerKeyBinding(Compat.key("key.lumio.media.next", GLFW.GLFW_KEY_UNKNOWN));
        mediaPrevious = KeyBindingHelper.registerKeyBinding(Compat.key("key.lumio.media.previous", GLFW.GLFW_KEY_UNKNOWN));
        Config.get();
        dev.lumio.media.Media.start();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (accept.consumeClick()) Cards.answer(true);
            while (deny.consumeClick()) Cards.answer(false);
            while (menu.consumeClick()) if (mc.screen == null) openMenu();
            while (wheel.consumeClick()) if (mc.screen == null && mc.player != null) mc.setScreen(new dev.lumio.wheel.WheelScreen());
            // the music reader runs only while its HUD element is on (read here, on the game's thread)
            dev.lumio.media.Media.setWanted(dev.lumio.hud.Hud.slot(dev.lumio.hud.Hud.MUSIC).on);
            while (mediaToggle.consumeClick()) dev.lumio.media.Media.control("toggle");
            while (mediaNext.consumeClick()) dev.lumio.media.Media.control("next");
            while (mediaPrevious.consumeClick()) dev.lumio.media.Media.control("previous");
            if (openNextTick != null && mc.screen == null) {
                Runnable r = openNextTick;
                openNextTick = null;
                r.run();
            }
            Cards.tick();
        });
        HudRenderCallback.EVENT.register((graphics, delta) -> {
            dev.lumio.hud.Hud.render(graphics);
            Cards.render(graphics);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            Protocol.reset();
            Cards.clear();
            dev.lumio.hud.HudData.clear();
            dev.lumio.guard.Integrity.reset();
            dev.lumio.wheel.Wheel.setServer(null);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(ClientCommandManager.literal("lumio")
                .executes(c -> {
                    c.getSource().sendFeedback(Component.translatable("lumio.command.status", version,
                            Component.translatable(Protocol.onLumioServer() ? "lumio.command.connected" : "lumio.command.not-connected")
                                    .withStyle(Protocol.onLumioServer() ? ChatFormatting.GREEN : ChatFormatting.GRAY)));
                    openNextTick = LumioClient::openMenu;
                    return Command.SINGLE_SUCCESS;
                })
                .then(ClientCommandManager.literal("demo").executes(c -> {
                    demo();
                    return Command.SINGLE_SUCCESS;
                }))));
    }
}
