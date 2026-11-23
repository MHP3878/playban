package dev.staffcore;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;

/**
 * Talks to players who have the Lumio client mod: a request card they answer with one key, and short
 * notice cards. Players without Lumio get the usual chat messages; nothing is sent to them.
 */
public final class LumioBridge {
    public static final String PROMPT = "lumio:prompt";
    public static final String TOAST = "lumio:toast";
    public static final String HUD = "lumio:hud";
    public static final String WHEEL = "lumio:wheel";

    private static Plugin plugin;

    private LumioBridge() {}

    public static void enable(Plugin owner) {
        plugin = owner;
        owner.getServer().getMessenger().registerOutgoingPluginChannel(owner, PROMPT);
        owner.getServer().getMessenger().registerOutgoingPluginChannel(owner, TOAST);
        owner.getServer().getMessenger().registerOutgoingPluginChannel(owner, HUD);
        owner.getServer().getMessenger().registerOutgoingPluginChannel(owner, WHEEL);
    }

    /** Whether this player's Lumio shows cards (it told the server it listens on the channel). */
    public static boolean has(Player p) {
        return plugin != null && p.getListeningPluginChannels().contains(PROMPT);
    }

    /** A request card: the keys run these commands as the player. */
    public static void prompt(Player p, String id, String title, String text, String acceptCommand, String denyCommand, int seconds) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("title", title);
        o.addProperty("text", text);
        o.addProperty("accept_command", acceptCommand);
        o.addProperty("deny_command", denyCommand);
        o.addProperty("seconds", seconds);
        send(p, PROMPT, o);
    }

    /** A notice card; kind is info, success, warn or error. */
    public static void toast(Player p, String title, String text, String kind, int seconds) {
        JsonObject o = new JsonObject();
        o.addProperty("title", title);
        o.addProperty("text", text);
        o.addProperty("kind", kind);
        o.addProperty("seconds", seconds);
        send(p, TOAST, o);
    }

    /** The server's lines in the HUD, each {id, label, value}; they replace the ones sent before. */
    public static void hud(Player p, java.util.List<String[]> lines) {
        JsonObject o = new JsonObject();
        o.addProperty("clear", true);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (String[] l : lines) {
            JsonObject line = new JsonObject();
            line.addProperty("id", l[0]);
            line.addProperty("label", l[1]);
            line.addProperty("value", l[2]);
            arr.add(line);
        }
        o.add("lines", arr);
        send(p, HUD, o);
    }

    /** The radial menu this server suggests; players who made their own keep theirs. */
    public static void wheel(Player p, com.google.gson.JsonArray entries) {
        JsonObject o = new JsonObject();
        o.add("entries", entries);
        send(p, WHEEL, o);
    }

    private static void send(Player p, String channel, JsonObject o) {
        if (plugin == null || !p.getListeningPluginChannels().contains(channel)) return;
        p.sendPluginMessage(plugin, channel, o.toString().getBytes(StandardCharsets.UTF_8));
    }
}
