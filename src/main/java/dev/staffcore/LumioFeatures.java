package dev.staffcore;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.staffcore.model.Ticket;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What StaffCore shows in the Lumio client mod, for players who have it (everyone else sees nothing new):
 * the account balance and prison time in the HUD, open tickets and vanish for the team, and a radial
 * menu made from the modules that are on (or the owner's own, lumio.wheel in config.yml).
 */
public final class LumioFeatures implements Listener {
    private final StaffCorePlugin plugin;
    /** what each player was sent last, so only changes go out */
    private final Map<UUID, String> sent = new HashMap<>();

    public LumioFeatures(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) update(p);
        }, 40L, 40L);
    }

    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent e) {
        Player p = e.getPlayer();
        if (e.getChannel().equals(LumioBridge.WHEEL) && plugin.getConfig().getBoolean("lumio.suggest-wheel", true)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) LumioBridge.wheel(p, wheel(p));
            });
        } else if (e.getChannel().equals(LumioBridge.HUD)) {
            sent.remove(p.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> update(p));
        }
    }

    /**
     * The multiplayer list: an invisible part in the description tells Lumio this server's colours and
     * tags (normal clients show the usual description and nothing else).
     */
    @EventHandler
    public void onPing(org.bukkit.event.server.ServerListPingEvent e) {
        if (!plugin.getConfig().getBoolean("lumio.server-list.enabled", true)) return;
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        JsonArray colors = new JsonArray();
        for (String c : plugin.getConfig().getStringList("lumio.server-list.colors")) {
            String hex = c.trim().startsWith("#") ? c.trim().substring(1) : c.trim();
            if (hex.matches("[0-9a-fA-F]{6}") && colors.size() < 2) colors.add("#" + hex);
        }
        if (!colors.isEmpty()) o.add("c", colors);
        JsonArray tags = new JsonArray();
        for (String t : plugin.getConfig().getStringList("lumio.server-list.tags")) {
            String clean = t.replaceAll("[\\p{Cntrl}]", "").trim();
            if (!clean.isEmpty() && tags.size() < 4) tags.add(clean.length() > 16 ? clean.substring(0, 16) : clean);
        }
        o.add("tags", tags);
        e.motd(e.motd().append(net.kyori.adventure.text.Component.text().content("").insertion("lumio:" + o).build()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        sent.remove(e.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- HUD

    /** Sends the HUD lines when they changed. */
    public void update(Player p) {
        if (!p.isOnline() || !p.getListeningPluginChannels().contains(LumioBridge.HUD)) return;
        List<String[]> lines = plugin.getConfig().getBoolean("lumio.hud", true) ? lines(p) : List.of();
        StringBuilder key = new StringBuilder();
        for (String[] l : lines) key.append(l[0]).append('\u0000').append(l[1]).append('\u0000').append(l[2]).append('\n');
        if (key.toString().equals(sent.get(p.getUniqueId()))) return;
        sent.put(p.getUniqueId(), key.toString());
        LumioBridge.hud(p, lines);
    }

    List<String[]> lines(Player p) {
        List<String[]> out = new ArrayList<>();
        UUID id = p.getUniqueId();
        if (plugin.economy() != null && plugin.economy().available() && plugin.money() != null) {
            out.add(new String[]{"money", lang().plain("lumio.hud.money"), plugin.money().format(plugin.economy().balance(p))});
        }
        if (plugin.prison() != null) {
            long left = plugin.prison().remaining(id);
            if (left >= 0) {
                out.add(new String[]{"prison", lang().plain("lumio.hud.prison"),
                        left == 0 ? lang().plain("lumio.hud.forever") : TimeUtil.format(Math.max(1000, left))});
            }
        }
        if (p.hasPermission("staffcore.staff")) {
            if (plugin.tickets() != null) {
                int open = plugin.tickets().count(Ticket.Status.OPEN);
                if (open > 0) out.add(new String[]{"tickets", lang().plain("lumio.hud.tickets"), String.valueOf(open)});
            }
            if (plugin.punishments() != null && plugin.punishments().isVanished(id)) {
                out.add(new String[]{"vanish", lang().plain("lumio.hud.vanish"), lang().plain("lumio.hud.on")});
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- radial menu

    /** The owner's own menu (lumio.wheel), or one made from the commands this player can use. */
    JsonArray wheel(Player p) {
        List<Map<?, ?>> own = plugin.getConfig().getMapList("lumio.wheel");
        if (!own.isEmpty()) return fromConfig(own, 0);
        JsonArray out = new JsonArray();
        add(out, p, "homes", "home", "minecraft:red_bed", "home", "staffcore.home");
        add(out, p, "spawn", "spawn", "minecraft:compass", "spawn", "staffcore.spawn");
        add(out, p, "tpa", "tpaccept", "minecraft:ender_pearl", "tpaccept", "staffcore.tpa");
        add(out, p, "totem", "totem", "minecraft:totem_of_undying", "totem", "staffcore.totem");
        add(out, p, null, "ticket", "minecraft:writable_book", "ticket", null);
        add(out, p, "money", "money", "minecraft:gold_ingot", "money", "staffcore.money");
        JsonArray more = new JsonArray();
        add(more, p, "homes", "homes", "minecraft:oak_door", "homes", "staffcore.home");
        add(more, p, "homes", "sethome", "minecraft:white_bed", "sethome", "staffcore.home");
        add(more, p, "tpa", "tpdeny", "minecraft:barrier", "tpdeny", "staffcore.tpa");
        add(more, p, "money", "baltop", "minecraft:emerald", "baltop", "staffcore.money");
        if (!more.isEmpty()) {
            JsonObject page = entry(lang().plain("lumio.wheel.more"), "minecraft:chest", "page", "");
            page.add("page", more);
            out.add(page);
        }
        return out;
    }

    private void add(JsonArray to, Player p, String module, String nameKey, String icon, String command, String permission) {
        if (to.size() >= 8) return;
        if (module != null && (plugin.modules() == null || !plugin.modules().on(module))) return;
        if (permission != null && !p.hasPermission(permission)) return;
        to.add(entry(lang().plain("lumio.wheel." + nameKey), icon, "command", command));
    }

    private static JsonObject entry(String name, String icon, String type, String value) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", icon);
        o.addProperty("type", type);
        o.addProperty("value", value);
        return o;
    }

    /** lumio.wheel: [{name, icon, command | chat | page: [...]}], at most 8 per page and two pages deep. */
    private static JsonArray fromConfig(List<Map<?, ?>> list, int depth) {
        JsonArray out = new JsonArray();
        for (Map<?, ?> m : list) {
            if (out.size() >= 8) break;
            String name = String.valueOf(m.containsKey("name") ? m.get("name") : "?");
            String icon = String.valueOf(m.containsKey("icon") ? m.get("icon") : "minecraft:compass");
            if (m.get("page") instanceof List<?> sub && depth < 2) {
                List<Map<?, ?>> maps = new ArrayList<>();
                for (Object o : sub) if (o instanceof Map<?, ?> mm) maps.add(mm);
                JsonObject page = entry(name, icon, "page", "");
                page.add("page", fromConfig(maps, depth + 1));
                out.add(page);
            } else if (m.containsKey("chat")) {
                out.add(entry(name, icon, "chat", String.valueOf(m.get("chat"))));
            } else if (m.containsKey("command")) {
                String cmd = String.valueOf(m.get("command"));
                while (cmd.startsWith("/")) cmd = cmd.substring(1);
                out.add(entry(name, icon, "command", cmd));
            }
        }
        return out;
    }
}
