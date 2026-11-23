package dev.bastion.ac;

import dev.bastion.BastionPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * Grim, the free prediction anti-cheat, as one more layer: when it is installed, each of its findings
 * counts in Bastion too (points, the ladder of the chosen level, the player's file). Read through its
 * event by name, so Bastion runs fine without Grim.
 */
public final class GrimHook implements Listener {
    private GrimHook() {}

    public static boolean register(BastionPlugin plugin) {
        if (Bukkit.getPluginManager().getPlugin("GrimAC") == null || !plugin.getConfig().getBoolean("anticheat.grim.enabled", true)) return false;
        Class<? extends Event> type;
        try {
            type = Class.forName("ac.grim.grimac.api.events.FlagEvent").asSubclass(Event.class);
        } catch (ClassNotFoundException | ClassCastException e) {
            plugin.getLogger().info("Grim is installed, but its event was not found; Bastion works without it.");
            return false;
        }
        GrimHook hook = new GrimHook();
        Bukkit.getPluginManager().registerEvent(type, hook, EventPriority.MONITOR, (l, event) -> {
            if (!type.isInstance(event)) return;
            try {
                if (event instanceof org.bukkit.event.Cancellable c && c.isCancelled()) return;
                Object user = event.getClass().getMethod("getPlayer").invoke(event);
                UUID id = (UUID) user.getClass().getMethod("getUniqueId").invoke(user);
                Object check = event.getClass().getMethod("getCheck").invoke(event);
                String name = String.valueOf(check.getClass().getMethod("getCheckName").invoke(check));
                Player p = Bukkit.getPlayer(id);
                if (p == null) return;
                double points = plugin.getConfig().getDouble("anticheat.grim.points", 2.0);
                Runnable count = () -> plugin.antiCheat().external(p, "Grim", name, points);
                if (Bukkit.isPrimaryThread()) count.run();
                else Bukkit.getScheduler().runTask(plugin, count);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // another Grim version: its findings simply do not count here
            }
        }, plugin, true);
        plugin.getLogger().info("Grim found: its findings count in Bastion as well.");
        return true;
    }
}
