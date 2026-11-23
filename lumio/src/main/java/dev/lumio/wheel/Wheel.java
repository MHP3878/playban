package dev.lumio.wheel;

import dev.lumio.Config;
import dev.lumio.net.Protocol;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * The radial menu's fields. The player's own fields come first; without them the server's suggestion
 * (lumio:wheel), and without that a sensible start. Running a field: a command as the player, a chat
 * message, or a next page.
 */
public final class Wheel {
    public static final int MAX = 8;
    /** Pictures to choose from in the editor. */
    public static final List<String> ICONS = List.of("minecraft:compass", "minecraft:red_bed", "minecraft:oak_door", "minecraft:ender_pearl",
            "minecraft:totem_of_undying", "minecraft:writable_book", "minecraft:chest", "minecraft:ender_chest", "minecraft:emerald",
            "minecraft:gold_ingot", "minecraft:diamond_sword", "minecraft:iron_pickaxe", "minecraft:shield", "minecraft:elytra",
            "minecraft:golden_apple", "minecraft:map", "minecraft:clock", "minecraft:bell", "minecraft:name_tag", "minecraft:beacon",
            "minecraft:nether_star", "minecraft:firework_rocket", "minecraft:campfire", "minecraft:crafting_table");

    private static List<Config.WheelEntry> server = List.of();

    private Wheel() {}

    public static void setServer(List<Config.WheelEntry> entries) {
        server = entries == null ? List.of() : entries;
    }

    public static List<Config.WheelEntry> serverEntries() {
        return server;
    }

    /** The fields in use: own ones, else the server's, else the start set. */
    public static List<Config.WheelEntry> entries() {
        List<Config.WheelEntry> own = Config.get().wheel;
        if (!own.isEmpty()) return own;
        if (!server.isEmpty()) return server;
        return defaults();
    }

    public static List<Config.WheelEntry> defaults() {
        List<Config.WheelEntry> out = new ArrayList<>();
        out.add(new Config.WheelEntry("Home", "minecraft:red_bed", "command", "home"));
        out.add(new Config.WheelEntry("Spawn", "minecraft:compass", "command", "spawn"));
        out.add(new Config.WheelEntry("TPA ✔", "minecraft:ender_pearl", "command", "tpaccept"));
        out.add(new Config.WheelEntry("Totem", "minecraft:totem_of_undying", "command", "totem"));
        out.add(new Config.WheelEntry("Ticket", "minecraft:writable_book", "command", "ticket"));
        Config.WheelEntry more = new Config.WheelEntry("Mehr", "minecraft:chest", "page", "");
        more.page.add(new Config.WheelEntry("Hallo", "minecraft:bell", "chat", "Hallo zusammen!"));
        more.page.add(new Config.WheelEntry("GG", "minecraft:firework_rocket", "chat", "GG!"));
        more.page.add(new Config.WheelEntry("Homes", "minecraft:oak_door", "command", "homes"));
        out.add(more);
        return out;
    }

    /** Copies the fields in use into the player's own settings, so they can be changed. */
    public static List<Config.WheelEntry> makeOwn() {
        Config c = Config.get();
        if (c.wheel.isEmpty()) {
            for (Config.WheelEntry e : entries()) c.wheel.add(copy(e));
            c.save();
        }
        return c.wheel;
    }

    public static Config.WheelEntry copy(Config.WheelEntry e) {
        Config.WheelEntry c = new Config.WheelEntry(e.name, e.icon, e.type, e.value);
        if (e.page != null) for (Config.WheelEntry p : e.page) c.page.add(copy(p));
        return c;
    }

    /** Runs a field; returns the next page for "page" fields, else null. */
    public static List<Config.WheelEntry> run(Config.WheelEntry e) {
        Minecraft mc = Minecraft.getInstance();
        if ("page".equals(e.type)) return e.page;
        if (mc.player == null || e.value == null) return null;
        String v = Protocol.clean(e.value, 256);
        if (v.isEmpty()) return null;
        if ("chat".equals(e.type)) mc.player.connection.sendChat(v);
        else {
            while (v.startsWith("/")) v = v.substring(1);
            if (!v.isEmpty()) mc.player.connection.sendCommand(v);
        }
        return null;
    }
}
