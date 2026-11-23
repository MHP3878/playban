package dev.lumio.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lumio.LumioClient;
import dev.lumio.ModList;
import dev.lumio.Versions;
import dev.lumio.compat.Compat;
import dev.lumio.ui.Cards;
import dev.lumio.ui.Draw;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * What Lumio says to a server that supports it, and what it does with what the server says.
 * Everything from the server is data, never code: texts are cleaned and cut, links must be https.
 */
public final class Protocol {
    public static final int VERSION = 1;
    /** One message stays well below Minecraft's limit for a message to the server. */
    private static final int PART_BYTES = 28_000;

    /** Whether the server we are on speaks Lumio (it said hello). */
    private static boolean lumioServer;
    private static String serverName = "";

    private Protocol() {}

    public static boolean onLumioServer() {
        return lumioServer;
    }

    public static String serverName() {
        return serverName;
    }

    public static void reset() {
        lumioServer = false;
        serverName = "";
    }

    static JsonObject parse(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException broken) {
            return null;
        }
    }

    static String str(JsonObject o, String key, String fallback) {
        try {
            JsonElement e = o.get(key);
            return e == null || e.isJsonNull() ? fallback : e.getAsString();
        } catch (RuntimeException wrongType) {
            return fallback;
        }
    }

    static int num(JsonObject o, String key, int fallback) {
        try {
            JsonElement e = o.get(key);
            return e == null || e.isJsonNull() ? fallback : e.getAsInt();
        } catch (RuntimeException wrongType) {
            return fallback;
        }
    }

    /** Server text: no colour codes or control characters, not endless. */
    public static String clean(String text, int max) {
        if (text == null) return "";
        String t = text.replaceAll("§.?", "");
        StringBuilder b = new StringBuilder();
        t.codePoints().filter(c -> c >= 0x20 && !Character.isISOControl(c)).limit(max).forEach(b::appendCodePoint);
        return b.toString().trim();
    }

    // ---------------------------------------------------------------- hello and report

    /** The server speaks Lumio: answer with our version and mod list, and tell about an update. */
    public static void onHello(String json) {
        JsonObject o = parse(json);
        if (o == null) return;
        lumioServer = true;
        serverName = clean(str(o, "server", ""), 40);
        String nonce = clean(str(o, "nonce", ""), 64);
        ModList.get().thenAccept(mods -> Minecraft.getInstance().execute(() -> sendReport(nonce, mods)));
        String latest = clean(str(o, "latest", ""), 24);
        String download = clean(str(o, "download", ""), 300);
        if (!latest.isEmpty() && Versions.compare(LumioClient.version(), latest) < 0) {
            Cards.toast(Component.translatable("lumio.update.title"), Component.translatable("lumio.update.text", latest), Draw.accent(), 12);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && download.startsWith("https://")) {
                Component link = Component.translatable("lumio.update.link").withStyle(s -> Compat.openUrl(s, download)
                        .withColor(ChatFormatting.AQUA).withUnderlined(true));
                mc.player.displayClientMessage(Component.translatable("lumio.update.chat", latest).append(" ").append(link), false);
            }
        }
    }

    /**
     * The report: our Lumio version, Minecraft, the loader and every mod with its fingerprint. A long
     * list is split into parts, each carrying the server's word, so the server can put it together.
     */
    static void sendReport(String nonce, List<ModList.Mod> mods) {
        if (!ClientPlayNetworking.canSend(JsonPayload.REPORT)) return;
        List<JsonArray> parts = new ArrayList<>();
        JsonArray current = new JsonArray();
        int size = 0;
        for (ModList.Mod m : mods) {
            JsonObject e = new JsonObject();
            e.addProperty("id", m.id());
            e.addProperty("name", m.name());
            e.addProperty("version", m.version());
            if (!m.sha256().isEmpty()) e.addProperty("sha256", m.sha256());
            if (!m.parent().isEmpty()) e.addProperty("in", m.parent());
            int bytes = e.toString().getBytes(StandardCharsets.UTF_8).length + 1;
            if (size + bytes > PART_BYTES && !current.isEmpty()) {
                parts.add(current);
                current = new JsonArray();
                size = 0;
            }
            current.add(e);
            size += bytes;
        }
        parts.add(current);
        for (int i = 0; i < parts.size(); i++) {
            JsonObject r = new JsonObject();
            r.addProperty("v", VERSION);
            r.addProperty("nonce", nonce);
            r.addProperty("part", i + 1);
            r.addProperty("parts", parts.size());
            if (i == 0) {
                r.addProperty("lumio", LumioClient.version());
                r.addProperty("minecraft", FabricLoader.getInstance().getModContainer("minecraft")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"));
                r.addProperty("loader", "fabric " + FabricLoader.getInstance().getModContainer("fabricloader")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"));
            }
            r.add("mods", parts.get(i));
            ClientPlayNetworking.send(new JsonPayload(JsonPayload.REPORT, r.toString()));
        }
    }

    // ---------------------------------------------------------------- cards from the server

    public static void onPrompt(String json) {
        JsonObject o = parse(json);
        if (o == null) return;
        String id = clean(str(o, "id", ""), 64);
        if (id.isEmpty()) return;
        long now = System.currentTimeMillis();
        int seconds = Math.max(5, Math.min(300, num(o, "seconds", 60)));
        Cards.prompt(new Cards.Prompt(id,
                Component.literal(clean(str(o, "title", "?"), 48)),
                Component.literal(clean(str(o, "text", ""), 160)),
                Component.literal(clean(str(o, "accept", ""), 20)).copy().append(blankIfEmpty(o, "accept", "lumio.prompt.accept")),
                Component.literal(clean(str(o, "deny", ""), 20)).copy().append(blankIfEmpty(o, "deny", "lumio.prompt.deny")),
                command(str(o, "accept_command", "")), command(str(o, "deny_command", "")), now, now + seconds * 1000L));
    }

    /** The default word ("Accept" / "Deny") when the server sent none. */
    private static Component blankIfEmpty(JsonObject o, String key, String fallbackKey) {
        return clean(str(o, key, ""), 20).isEmpty() ? Component.translatable(fallbackKey) : Component.empty();
    }

    /** A command the key runs as the player: one line, without the slash, not endless. */
    private static String command(String raw) {
        String c = clean(raw, 200);
        while (c.startsWith("/")) c = c.substring(1);
        return c;
    }

    public static void onToast(String json) {
        JsonObject o = parse(json);
        if (o == null) return;
        int color = switch (clean(str(o, "kind", "info"), 10)) {
            case "success" -> Draw.SUCCESS;
            case "warn" -> Draw.WARN;
            case "error" -> Draw.DANGER;
            default -> Draw.accent();
        };
        Cards.toast(Component.literal(clean(str(o, "title", ""), 48)), Component.literal(clean(str(o, "text", ""), 160)), color,
                Math.max(2, Math.min(30, num(o, "seconds", 6))));
    }

    /** A request card was answered: run its command as the player, or tell the server. */
    public static void answer(Cards.Prompt p, boolean accepted) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String cmd = accepted ? p.acceptCommand() : p.denyCommand();
        if (!cmd.isEmpty()) {
            mc.player.connection.sendCommand(cmd);
            return;
        }
        if (!ClientPlayNetworking.canSend(JsonPayload.ANSWER)) return;
        JsonObject a = new JsonObject();
        a.addProperty("id", p.id());
        a.addProperty("accept", accepted);
        ClientPlayNetworking.send(new JsonPayload(JsonPayload.ANSWER, a.toString()));
    }
}
