package dev.bastion.ac;

import dev.bastion.BastionPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grim, the free prediction anti-cheat, puts players back and is one voice of three (Consensus). Grim
 * gives its verdict when it is sure (its violations within five minutes reach Bastion's thresholds in
 * Grim's punishments.yml) by calling "bastion grim &lt;player&gt; &lt;check&gt; &lt;vl&gt;".
 *
 * <p>Single findings of Grim do not count: they only go into the player's file, at most once a minute
 * per check. Read through Grim's event by name, so Bastion runs fine without Grim.
 */
public final class GrimHook implements Listener {
    /** The line Bastion writes into Grim's punishments.yml (and recognises again). */
    static final String COMMAND = "bastion grim %player% %check_name% %vl%";
    private final Map<String, Long> filed = new ConcurrentHashMap<>();

    private GrimHook() {}

    public static boolean present() {
        return Bukkit.getPluginManager().isPluginEnabled("GrimAC");
    }

    public static boolean register(BastionPlugin plugin) {
        if (!present() || !plugin.getConfig().getBoolean("anticheat.grim.enabled", true)) return false;
        Class<? extends Event> type;
        try {
            type = Class.forName("ac.grim.grimac.api.events.FlagEvent").asSubclass(Event.class);
        } catch (ClassNotFoundException | ClassCastException e) {
            plugin.getLogger().info("Grim is installed, but its event was not found; its findings do not go into the files.");
            type = null;
        }
        if (type != null) {
            GrimHook hook = new GrimHook();
            Class<? extends Event> flag = type;
            // only Grim's API is asked (its own classes may need plugins this server does not have)
            java.lang.reflect.Method getUser, getId, getCheck, getName, getViolations;
            try {
                ClassLoader grim = flag.getClassLoader();
                getUser = flag.getMethod("getPlayer");
                getId = Class.forName("ac.grim.grimac.api.GrimUser", false, grim).getMethod("getUniqueId");
                getCheck = flag.getMethod("getCheck");
                getName = Class.forName("ac.grim.grimac.api.AbstractCheck", false, grim).getMethod("getCheckName");
                java.lang.reflect.Method v;
                try {
                    v = flag.getMethod("getViolations");
                } catch (NoSuchMethodException e) {
                    v = null;
                }
                getViolations = v;
            } catch (ReflectiveOperationException | LinkageError e) {
                plugin.getLogger().info("Grim's API looks different; its findings do not go into the files.");
                getUser = null;
                getId = getCheck = getName = getViolations = null;
            }
            if (getUser != null) {
                java.lang.reflect.Method u = getUser, i = getId, c = getCheck, n = getName, vl = getViolations;
                Bukkit.getPluginManager().registerEvent(flag, hook, EventPriority.MONITOR, (l, event) -> {
                    if (!flag.isInstance(event)) return;
                    try {
                        if (event instanceof org.bukkit.event.Cancellable cc && cc.isCancelled()) return;
                        UUID id = (UUID) i.invoke(u.invoke(event));
                        String name = String.valueOf(n.invoke(c.invoke(event)));
                        double violations = vl == null ? 0 : ((Number) vl.invoke(event)).doubleValue();
                        hook.file(plugin, id, name, violations);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                        // another Grim version: its findings simply do not go into the files
                    }
                }, plugin, true);
            }
        }
        plugin.getLogger().info("Grim found: it puts players back, and its verdict is one vote of three.");
        if (plugin.getConfig().getBoolean("anticheat.grim.configure", true)) configure(plugin, null);
        return true;
    }

    /** A finding of Grim into the player's file: at most once a minute per check. */
    private void file(BastionPlugin plugin, UUID id, String check, double vl) {
        long now = System.currentTimeMillis();
        String key = id + ":" + check;
        Long last = filed.get(key);
        if (last != null && now - last < 60_000) return;
        filed.put(key, now);
        if (filed.size() > 5000) filed.clear();
        String text = "Grim: " + check + (vl > 0 ? String.format(Locale.ROOT, " (%.0f)", vl) : "");
        Runnable add = () -> plugin.akte().event(id, "grim", text, "Grim");
        if (Bukkit.isPrimaryThread()) add.run();
        else Bukkit.getScheduler().runTask(plugin, add);
    }

    /** How strict the chosen level is: /setup 1 waits longer, /setup 3 punishes sooner. */
    static double factor(BastionPlugin plugin) {
        return switch (plugin.getConfig().getInt("setup.level", 2)) {
            case 1 -> 1.5;
            case 3 -> 0.7;
            default -> 1.0;
        };
    }

    /**
     * Writes Bastion's line into each group of Grim's punishments.yml (its alerts and logs stay as they
     * are), keeps a copy of the old file once, and reloads Grim. Returns how many groups were set.
     */
    public static int configure(BastionPlugin plugin, CommandSender sender) {
        File dir = Bukkit.getPluginManager().getPlugin("GrimAC") == null ? null : Bukkit.getPluginManager().getPlugin("GrimAC").getDataFolder();
        File file = dir == null ? null : new File(dir, "punishments.yml");
        if (file == null || !file.isFile()) {
            if (sender != null) plugin.lang().send(sender, "grim.no-file");
            return 0;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection groups = y.getConfigurationSection("Punishments");
        ConfigurationSection wanted = plugin.getConfig().getConfigurationSection("anticheat.grim.thresholds");
        if (groups == null || wanted == null) {
            if (sender != null) plugin.lang().send(sender, "grim.no-file");
            return 0;
        }
        double f = factor(plugin);
        int set = 0;
        boolean changed = false;
        for (String group : groups.getKeys(false)) {
            ConfigurationSection g = groups.getConfigurationSection(group);
            if (g == null) continue;
            List<String> commands = new ArrayList<>(g.getStringList("commands"));
            List<String> before = new ArrayList<>(commands);
            // Bastion's old line goes, whatever it said
            commands.removeIf(c -> c.toLowerCase(Locale.ROOT).contains("bastion grim"));
            int threshold = wanted.getInt(group, 0);
            if (threshold > 0) {
                commands.add(Math.max(1, (int) Math.round(threshold * f)) + ":0 " + COMMAND);
                set++;
            }
            if (!commands.equals(before)) {
                g.set("commands", commands);
                changed = true;
            }
        }
        if (changed) {
            try {
                File copy = new File(dir, "punishments.yml.before-bastion");
                if (!copy.exists()) Files.copy(file.toPath(), copy.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                y.save(file);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "grim reload");
                plugin.getLogger().info("Grim's punishments.yml: Bastion punishes in " + set + " groups (a copy of the old file is punishments.yml.before-bastion).");
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().warning("Could not write Grim's punishments.yml: " + e.getMessage());
                if (sender != null) plugin.lang().send(sender, "grim.no-file");
                return 0;
            }
        }
        if (sender != null) plugin.lang().send(sender, "grim.configured", "groups", set);
        return set;
    }
}
