package dev.staffcore.module;

import dev.staffcore.StaffCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Teleports with a short countdown while standing still, shared by homes and teleport requests. */
public final class Teleports {
    private final StaffCorePlugin plugin;
    private final Map<UUID, BukkitTask> waiting = new HashMap<>();

    public Teleports(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Counts down, then teleports. Moving a block away cancels it. Staff with staffcore.spawn.instant go at once. */
    public void after(Player p, int seconds, java.util.function.Supplier<Location> where, Runnable done) {
        cancel(p.getUniqueId());
        if (seconds <= 0 || p.hasPermission("staffcore.spawn.instant")) {
            go(p, where.get(), done);
            return;
        }
        Location start = p.getLocation();
        int[] left = {seconds};
        plugin.lang().send(p, "teleport.warmup", "seconds", seconds);
        BukkitTask[] self = new BukkitTask[1];
        self[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) {
                cancel(p.getUniqueId());
                return;
            }
            if (p.getWorld() != start.getWorld() || p.getLocation().distanceSquared(start) > 1.0) {
                cancel(p.getUniqueId());
                plugin.lang().send(p, "teleport.moved");
                return;
            }
            if (left[0] <= 0) {
                waiting.remove(p.getUniqueId());
                self[0].cancel();
                go(p, where.get(), done);
                return;
            }
            p.sendActionBar(plugin.lang().msg("spawn.countdown", "seconds", left[0]));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.4f + (seconds - left[0]) * 0.1f);
            left[0]--;
        }, 0L, 20L);
        waiting.put(p.getUniqueId(), self[0]);
    }

    public void cancel(UUID id) {
        BukkitTask t = waiting.remove(id);
        if (t != null) t.cancel();
    }

    private void go(Player p, Location to, Runnable done) {
        if (to == null || to.getWorld() == null) {
            plugin.lang().send(p, "teleport.gone");
            return;
        }
        Location from = p.getLocation();
        if (!p.teleport(to)) return;
        p.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f);
        try {
            to.getWorld().spawnParticle(Particle.PORTAL, to.clone().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.2);
            from.getWorld().spawnParticle(Particle.CLOUD, from.clone().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02);
        } catch (RuntimeException ignored) {
            // only decoration
        }
        if (done != null) done.run();
    }

    /** Whether a player may not move around at all right now: frozen, cuffed, in prison or punished. */
    public boolean stuck(Player p) {
        return plugin.admin().isRestrained(p.getUniqueId()) || plugin.prison().isJailed(p.getUniqueId())
                || plugin.punishments().isPunished(p.getUniqueId());
    }
}
