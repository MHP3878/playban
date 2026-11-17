package dev.staffcore.module;

import dev.staffcore.StaffCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Ghost mode for the team: creative and invisible, but walls are no obstacle. Next to a block the
 * game switches to spectator for a moment, so you slide through it and see the caves inside like a
 * spectator does; in the open you are back in creative with your admin items.
 */
public final class GhostMode implements Listener {
    private final StaffCorePlugin plugin;
    private final Set<UUID> ghosts = new HashSet<>();
    /** game mode changes made by ghost mode itself, which must not end it */
    private final Set<UUID> switching = new HashSet<>();
    private BukkitTask task;

    public GhostMode(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        for (UUID id : Set.copyOf(ghosts)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) off(p);
        }
    }

    public boolean on(UUID id) {
        return ghosts.contains(id);
    }

    public void toggle(Player p) {
        if (on(p.getUniqueId())) off(p);
        else on(p);
    }

    public void on(Player p) {
        if (!plugin.tools().inStaffMode(p.getUniqueId())) plugin.tools().enterStaffMode(p);
        if (!plugin.punishments().isVanished(p.getUniqueId())) plugin.punishments().setVanished(p, true);
        ghosts.add(p.getUniqueId());
        mode(p, GameMode.CREATIVE);
        plugin.lang().send(p, "ghost.on");
    }

    public void off(Player p) {
        if (!ghosts.remove(p.getUniqueId())) return;
        // never left stuck inside a wall
        if (inside(p)) {
            Location l = p.getLocation();
            int top = l.getWorld().getHighestBlockYAt(l.getBlockX(), l.getBlockZ()) + 1;
            p.teleport(new Location(l.getWorld(), l.getBlockX() + 0.5, top, l.getBlockZ() + 0.5, l.getYaw(), l.getPitch()));
        }
        mode(p, GameMode.CREATIVE);
        p.setAllowFlight(true);
        p.setFlying(true);
        plugin.lang().send(p, "ghost.off");
    }

    private void mode(Player p, GameMode m) {
        if (p.getGameMode() == m) return;
        switching.add(p.getUniqueId());
        try {
            p.setGameMode(m);
        } finally {
            switching.remove(p.getUniqueId());
        }
        if (m == GameMode.CREATIVE) {
            p.setAllowFlight(true);
            p.setFlying(true);
        }
    }

    private static boolean solid(Location l) {
        Material m = l.getBlock().getType();
        return m.isSolid() || m == Material.WATER || m == Material.LAVA;
    }

    /** Whether the player's body is inside a block. */
    private static boolean inside(Player p) {
        Location l = p.getLocation();
        for (double dx : new double[]{-0.3, 0.3}) {
            for (double dz : new double[]{-0.3, 0.3}) {
                for (double dy : new double[]{0.05, 0.9, 1.75}) {
                    if (solid(l.clone().add(dx, dy, dz))) return true;
                }
            }
        }
        return false;
    }

    /** Whether a wall, floor or ceiling is right next to the player. */
    private static boolean nearWall(Player p) {
        Location l = p.getLocation();
        double r = 0.75;
        for (double dx : new double[]{-r, 0, r}) {
            for (double dz : new double[]{-r, 0, r}) {
                for (double dy : new double[]{0.1, 1.0, 1.7}) {
                    if (solid(l.clone().add(dx, dy, dz))) return true;
                }
            }
        }
        // the floor only counts when sneaking, so you can still stand on it; the ceiling always
        if (p.isSneaking() && solid(l.clone().add(0, -0.3, 0))) return true;
        return solid(l.clone().add(0, 2.3, 0));
    }

    private void tick() {
        for (UUID id : Set.copyOf(ghosts)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (p.getGameMode() == GameMode.SPECTATOR) {
                if (!inside(p) && !nearWall(p)) mode(p, GameMode.CREATIVE);
            } else if (nearWall(p) || inside(p)) {
                mode(p, GameMode.SPECTATOR);
            }
        }
    }

    /** Somebody else changed the game mode (a command, another plugin): ghost mode ends. */
    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) {
        if (switching.contains(e.getPlayer().getUniqueId())) return;
        ghosts.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (ghosts.contains(e.getPlayer().getUniqueId())) off(e.getPlayer());
    }
}
