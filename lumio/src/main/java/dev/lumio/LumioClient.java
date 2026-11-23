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
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Lumio: a clean everyday client mod. This is where it starts. */
public final class LumioClient implements ClientModInitializer {
    private static String version = "0";
    private static KeyMapping accept;
    private static KeyMapping deny;

    public static String version() {
        return version;
    }

    public static Component acceptKeyName() {
        return accept.getTranslatedKeyMessage();
    }

    public static Component denyKeyName() {
        return deny.getTranslatedKeyMessage();
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
        PayloadTypeRegistry.playC2S().register(JsonPayload.REPORT, JsonPayload.codec(JsonPayload.REPORT));
        PayloadTypeRegistry.playC2S().register(JsonPayload.ANSWER, JsonPayload.codec(JsonPayload.ANSWER));
        ClientPlayNetworking.registerGlobalReceiver(JsonPayload.HELLO, (p, ctx) -> Protocol.onHello(p.json()));
        ClientPlayNetworking.registerGlobalReceiver(JsonPayload.PROMPT, (p, ctx) -> Protocol.onPrompt(p.json()));
        ClientPlayNetworking.registerGlobalReceiver(JsonPayload.TOAST, (p, ctx) -> Protocol.onToast(p.json()));

        accept = KeyBindingHelper.registerKeyBinding(Compat.key("key.lumio.accept", GLFW.GLFW_KEY_Y));
        deny = KeyBindingHelper.registerKeyBinding(Compat.key("key.lumio.deny", GLFW.GLFW_KEY_N));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (accept.consumeClick()) Cards.answer(true);
            while (deny.consumeClick()) Cards.answer(false);
            Cards.tick();
        });
        HudRenderCallback.EVENT.register((graphics, delta) -> Cards.render(graphics));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            Protocol.reset();
            Cards.clear();
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(ClientCommandManager.literal("lumio")
                .executes(c -> {
                    c.getSource().sendFeedback(Component.translatable("lumio.command.status", version,
                            Component.translatable(Protocol.onLumioServer() ? "lumio.command.connected" : "lumio.command.not-connected")
                                    .withStyle(Protocol.onLumioServer() ? ChatFormatting.GREEN : ChatFormatting.GRAY)));
                    return Command.SINGLE_SUCCESS;
                })
                .then(ClientCommandManager.literal("demo").executes(c -> {
                    // shows what the cards look like, without a server
                    long now = System.currentTimeMillis();
                    Cards.toast(Component.translatable("lumio.demo.toast.title"), Component.translatable("lumio.demo.toast.text"), Draw.SUCCESS, 8);
                    Cards.prompt(new Cards.Prompt("demo", Component.translatable("lumio.demo.prompt.title"),
                            Component.translatable("lumio.demo.prompt.text"), Component.translatable("lumio.prompt.accept"),
                            Component.translatable("lumio.prompt.deny"), "", "", now, now + 30_000));
                    return Command.SINGLE_SUCCESS;
                }))));
    }
}
