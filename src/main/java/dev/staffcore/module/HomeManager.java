package dev.staffcore.module;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import dev.staffcore.TimeUtil;
import dev.staffcore.gui.Items;
import dev.staffcore.gui.Menu;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /sethome, /home, /delhome and /homes: every player keeps a few places to come back to. */
public final class HomeManager implements TabExecutor {
    private final StaffCorePlugin plugin;
    private final Map<UUID, Map<String, Location>> homes = new HashMap<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public HomeManager(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private File file() {
        return new File(plugin.getDataFolder(), "homes.yml");
    }

    public void load() {
        homes.clear();
        if (!file().exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file());
        for (String id : cfg.getKeys(false)) {
            ConfigurationSection s = cfg.getConfigurationSection(id);
            if (s == null) continue;
            try {
                Map<String, Location> own = new LinkedHashMap<>();
                for (String name : s.getKeys(false)) {
                    ConfigurationSection h = s.getConfigurationSection(name);
                    if (h == null) continue;
                    World w = Bukkit.getWorld(h.getString("world", ""));
                    if (w == null) continue;
                    own.put(name, new Location(w, h.getDouble("x"), h.getDouble("y"), h.getDouble("z"), (float) h.getDouble("yaw"), (float) h.getDouble("pitch")));
                }
                homes.put(UUID.fromString(id), own);
            } catch (IllegalArgumentException ignored) {
                // broken entry
            }
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Location>> e : homes.entrySet()) {
            for (Map.Entry<String, Location> h : e.getValue().entrySet()) {
                String p = e.getKey() + "." + h.getKey() + ".";
                Location l = h.getValue();
                cfg.set(p + "world", l.getWorld().getName());
                cfg.set(p + "x", l.getX());
                cfg.set(p + "y", l.getY());
                cfg.set(p + "z", l.getZ());
                cfg.set(p + "yaw", (double) l.getYaw());
                cfg.set(p + "pitch", (double) l.getPitch());
            }
        }
        try {
            cfg.save(file());
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save homes.yml: " + e.getMessage());
        }
    }

    public Map<String, Location> of(UUID id) {
        return homes.getOrDefault(id, Map.of());
    }

    /** How many homes a player may have: the setting, or unlimited with staffcore.homes.unlimited. */
    public int max(Player p) {
        if (p.hasPermission("staffcore.homes.unlimited")) return Integer.MAX_VALUE;
        return Math.max(1, plugin.getConfig().getInt("homes.max", 3));
    }

    public boolean set(Player p, String name) {
        Map<String, Location> own = homes.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashMap<>());
        if (!own.containsKey(name) && own.size() >= max(p)) return false;
        own.put(name, p.getLocation());
        save();
        return true;
    }

    public boolean delete(UUID id, String name) {
        Map<String, Location> own = homes.get(id);
        if (own == null || own.remove(name) == null) return false;
        save();
        return true;
    }

    public void go(Player p, String name) {
        Location to = of(p.getUniqueId()).get(name);
        if (to == null) {
            lang().send(p, "home.unknown", "home", name);
            return;
        }
        if (plugin.teleports().stuck(p)) {
            lang().send(p, "teleport.not-now");
            return;
        }
        long cooldown = plugin.getConfig().getLong("homes.cooldown", 0) * 1000L;
        Long last = lastUse.get(p.getUniqueId());
        if (cooldown > 0 && last != null && !p.hasPermission("staffcore.spawn.instant")) {
            long left = cooldown - (System.currentTimeMillis() - last);
            if (left > 0) {
                lang().send(p, "teleport.cooldown", "time", TimeUtil.format(Math.max(1000, left)));
                return;
            }
        }
        plugin.teleports().after(p, plugin.getConfig().getInt("homes.warmup", 3), () -> of(p.getUniqueId()).get(name), () -> {
            lastUse.put(p.getUniqueId(), System.currentTimeMillis());
            lang().send(p, "home.arrived", "home", name);
        });
    }

    /** The homes as a menu: click to go, shift-click to delete. */
    public void menu(Player p) {
        List<Map.Entry<String, Location>> list = new ArrayList<>(of(p.getUniqueId()).entrySet());
        Menu m = plugin.adminMenus().paged(lang().msg("home.menu-title", "count", list.size(), "max",
                        max(p) == Integer.MAX_VALUE ? "∞" : String.valueOf(max(p))), list, 0,
                h -> Items.of(Material.RED_BED, lang().msg("home.menu-entry", "home", h.getKey()), lang().lore("home.menu-lore",
                        "world", h.getValue().getWorld().getName(), "x", h.getValue().getBlockX(), "y", h.getValue().getBlockY(), "z", h.getValue().getBlockZ())),
                (h, e) -> {
                    if (e.isShiftClick()) {
                        delete(p.getUniqueId(), h.getKey());
                        lang().send(p, "home.deleted", "home", h.getKey());
                        menu(p);
                    } else {
                        p.closeInventory();
                        go(p, h.getKey());
                    }
                }, n -> {}, p::closeInventory);
        if (list.isEmpty()) m.set(22, Items.of(Material.GRAY_DYE, lang().msg("home.none"), lang().lore("home.none-lore")));
        m.open(p);
    }

    private static String clean(String name) {
        String n = name.toLowerCase().replaceAll("[^a-z0-9_-]", "");
        return n.isEmpty() ? "home" : (n.length() > 16 ? n.substring(0, 16) : n);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            lang().send(sender, "error.players-only");
            return true;
        }
        String name = args.length > 0 ? clean(args[0]) : null;
        switch (command.getName().toLowerCase()) {
            case "sethome" -> {
                String n = name == null ? "home" : name;
                if (set(p, n)) lang().send(p, "home.set", "home", n);
                else lang().send(p, "home.limit", "max", max(p));
            }
            case "delhome" -> {
                if (name == null) lang().send(p, "home.usage-del");
                else lang().send(p, delete(p.getUniqueId(), name) ? "home.deleted" : "home.unknown", "home", name);
            }
            case "homes" -> menu(p);
            default -> {
                Map<String, Location> own = of(p.getUniqueId());
                if (name != null) go(p, name);
                else if (own.size() == 1) go(p, own.keySet().iterator().next());
                else if (own.isEmpty()) lang().send(p, "home.none-yet");
                else menu(p);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1 && sender instanceof Player p && !command.getName().equalsIgnoreCase("sethome")) {
            for (String h : of(p.getUniqueId()).keySet()) if (h.startsWith(args[0].toLowerCase())) out.add(h);
        }
        return out;
    }
}
