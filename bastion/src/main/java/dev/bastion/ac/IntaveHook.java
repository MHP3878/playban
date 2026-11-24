package dev.bastion.ac;

import dev.bastion.BastionPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * Intave, the second free simulation anti-cheat, as one voice of three. It only watches (Grim puts
 * players back), sends no IP addresses to its cloud, and when it would kick or ban somebody it tells
 * Bastion instead ("bastion vote intave &lt;player&gt; kick|ban"); Bastion counts that as its vote.
 */
public final class IntaveHook implements Listener {
    static final String KICK = "bastion vote intave {player} kick";
    static final String BAN = "bastion vote intave {player} ban";

    private IntaveHook() {}

    public static boolean present() {
        return Bukkit.getPluginManager().isPluginEnabled("Intave");
    }

    /**
     * Writes Bastion's settings into Intave's config.yml: before Intave's first start (from the default
     * file in its jar) or into the file it already has. Returns whether something changed.
     */
    public static boolean configure(BastionPlugin plugin, File intaveJar) {
        File dir = new File(plugin.getDataFolder().getParentFile(), "Intave");
        File file = new File(dir, "config.yml");
        YamlConfiguration y;
        try {
            if (file.isFile()) y = YamlConfiguration.loadConfiguration(file);
            else {
                if (intaveJar == null || !intaveJar.isFile()) return false;
                try (ZipFile zip = new ZipFile(intaveJar)) {
                    var entry = zip.getEntry("config.yml");
                    if (entry == null) return false;
                    try (InputStream in = zip.getInputStream(entry)) {
                        y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Could not read Intave's config.yml: " + e.getMessage());
            return false;
        }
        boolean changed = false;
        // Grim puts players back; Intave only watches (two anti-cheats pulling a player make honest players rubber-band)
        changed |= set(y, "physics-mm", "SILENT");
        // whether somebody may come back is Bastion's decision
        changed |= set(y, "rejoin-delay", 0);
        // without a cloud token nothing goes to the cloud anyway; with one, no IP addresses
        changed |= set(y, "privacy", "HIGHEST_PRIVACY");
        changed |= set(y, "kick-commands", List.of(KICK));
        changed |= set(y, "ban-commands", List.of(BAN));
        if (!changed) return false;
        try {
            dir.mkdirs();
            File copy = new File(dir, "config.yml.before-bastion");
            if (file.isFile() && !copy.exists()) Files.copy(file.toPath(), copy.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            y.save(file);
            plugin.getLogger().info("Intave is set up: it only watches, and its kicks and bans are votes in Bastion.");
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write Intave's config.yml: " + e.getMessage());
            return false;
        }
    }

    private static boolean set(YamlConfiguration y, String key, Object value) {
        Object now = y.get(key);
        if (value.equals(now)) return false;
        y.set(key, value);
        return true;
    }

    /**
     * Listens to the commands Intave is about to run: Bastion's vote gets the name of the check added,
     * and a kick or ban of Intave's own (an older config) becomes a vote instead.
     */
    public static boolean register(BastionPlugin plugin) {
        if (!present() || !plugin.getConfig().getBoolean("anticheat.intave.enabled", true)) return false;
        Class<? extends Event> type;
        Method player, command, setCommand, check, vl, cancel;
        try {
            ClassLoader loader = Bukkit.getPluginManager().getPlugin("Intave").getClass().getClassLoader();
            type = Class.forName("de.jpx3.intave.access.check.event.IntaveCommandExecutionEvent", false, loader).asSubclass(Event.class);
            player = type.getMethod("player");
            command = type.getMethod("command");
            setCommand = type.getMethod("setCommand", String.class);
            check = type.getMethod("violationCheck");
            vl = type.getMethod("activationVL");
            cancel = type.getMethod("setCancelled", boolean.class);
        } catch (ReflectiveOperationException | ClassCastException | LinkageError | NullPointerException e) {
            plugin.getLogger().info("Intave is installed, but its command event was not found; its votes come through its commands only.");
            return true;
        }
        IntaveHook hook = new IntaveHook();
        Bukkit.getPluginManager().registerEvent(type, hook, EventPriority.HIGHEST, (l, event) -> {
            if (!type.isInstance(event)) return;
            try {
                String cmd = String.valueOf(command.invoke(event)).trim();
                String lower = cmd.toLowerCase(Locale.ROOT).replaceFirst("^/", "");
                String what = String.valueOf(check.invoke(event)).replace(' ', '_');
                String level = String.format(Locale.ROOT, "%.0f", ((Number) vl.invoke(event)).doubleValue());
                if (lower.startsWith("bastion vote intave")) {
                    setCommand.invoke(event, cmd + " " + what + " " + level);
                    return;
                }
                boolean ban = lower.startsWith("ban ") || lower.startsWith("tempban") || lower.startsWith("ipban") || lower.startsWith("banip")
                        || lower.startsWith("minecraft:ban");
                boolean kick = lower.startsWith("kick ") || lower.startsWith("minecraft:kick") || lower.startsWith("intave internals kickip")
                        || lower.startsWith("intave internals collectivekick");
                if (!ban && !kick) return;
                cancel.invoke(event, true);
                Object p = player.invoke(event);
                if (p instanceof Player pl) {
                    Runnable vote = () -> plugin.antiCheat().intaveVote(pl, ban ? "ban" : "kick", what, level);
                    if (Bukkit.isPrimaryThread()) vote.run();
                    else Bukkit.getScheduler().runTask(plugin, vote);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // another Intave version: its commands run as configured (Bastion's vote command)
            }
        }, plugin, false);
        plugin.getLogger().info("Intave found: one voice of three, it only watches.");
        return true;
    }
}
