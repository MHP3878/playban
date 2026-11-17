package dev.playban;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /spawn and /setspawn, sending new players and the dead to spawn, with a short countdown. */
public final class SpawnManager implements Listener, TabExecutor {
    public static final List<String> JOIN_MODES = List.of("NEVER", "FIRST_JOIN", "ALWAYS");

    private record Pending(BukkitTask task, Location start) {}

    private final PlayBanPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public SpawnManager(PlayBanPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("spawn.enabled", true);
    }

    /** The spawn point: the one set with /setspawn, otherwise the main world's spawn. */
    public Location spawn() {
        String world = plugin.getConfig().getString("spawn.location.world");
        World w = world == null ? null : Bukkit.getWorld(world);
        if (w == null) {
            World main = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            return main == null ? null : main.getSpawnLocation().add(0.5, 0, 0.5);
        }
        return new Location(w, plugin.getConfig().getDouble("spawn.location.x"), plugin.getConfig().getDouble("spawn.location.y"),
                plugin.getConfig().getDouble("spawn.location.z"), (float) plugin.getConfig().getDouble("spawn.location.yaw"),
                (float) plugin.getConfig().getDouble("spawn.location.pitch"));
    }

    public boolean isSet() {
        return plugin.getConfig().getString("spawn.location.world") != null;
    }

    public void setSpawn(Location l) {
        plugin.getConfig().set("spawn.location.world", l.getWorld().getName());
        plugin.getConfig().set("spawn.location.x", l.getX());
        plugin.getConfig().set("spawn.location.y", l.getY());
        plugin.getConfig().set("spawn.location.z", l.getZ());
        plugin.getConfig().set("spawn.location.yaw", (double) l.getYaw());
        plugin.getConfig().set("spawn.location.pitch", (double) l.getPitch());
        plugin.saveConfig();
        // the world's own spawn follows, so compasses and new chunks agree
        l.getWorld().setSpawnLocation(l);
    }

    /** Teleports at once, with a little effect. */
    public void teleport(Player p) {
        Location to = spawn();
        if (to == null) return;
        Location from = p.getLocation();
        if (!p.teleport(to)) return;
        p.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f);
        try {
            to.getWorld().spawnParticle(Particle.PORTAL, to.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.2);
            from.getWorld().spawnParticle(Particle.CLOUD, from.clone().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.02);
        } catch (RuntimeException ignored) {
            // only decoration; the teleport itself has already happened
        }
    }

    private void cancel(Player p, String reasonKey) {
        Pending old = pending.remove(p.getUniqueId());
        if (old == null) return;
        old.task().cancel();
        if (reasonKey != null) {
            lang().send(p, reasonKey);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
        }
    }

    /** /spawn for a player: countdown while standing still, then teleport. Staff skip the countdown. */
    public void request(Player p) {
        if (!enabled()) {
            lang().send(p, "spawn.disabled");
            return;
        }
        if (plugin.punishments().inLimbo(p.getUniqueId())) {
            lang().send(p, "blocked.command");
            return;
        }
        long cooldown = plugin.getConfig().getLong("spawn.cooldown", 0) * 1000L;
        Long last = lastUse.get(p.getUniqueId());
        if (cooldown > 0 && last != null && !p.hasPermission("playban.spawn.instant")) {
            long left = cooldown - (System.currentTimeMillis() - last);
            if (left > 0) {
                lang().send(p, "spawn.cooldown", "time", TimeUtil.format(Math.max(1000, left)));
                return;
            }
        }
        int warmup = Math.max(0, plugin.getConfig().getInt("spawn.warmup", 3));
        cancel(p, null);
        if (warmup == 0 || p.hasPermission("playban.spawn.instant")) {
            lastUse.put(p.getUniqueId(), System.currentTimeMillis());
            teleport(p);
            lang().send(p, "spawn.done");
            return;
        }
        int[] left = {warmup};
        BukkitTask[] self = new BukkitTask[1];
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) {
                cancel(p, null);
                return;
            }
            if (left[0] <= 0) {
                self[0].cancel();
                pending.remove(p.getUniqueId());
                lastUse.put(p.getUniqueId(), System.currentTimeMillis());
                teleport(p);
                p.sendActionBar(lang().msg("spawn.done"));
                return;
            }
            p.sendActionBar(lang().msg("spawn.countdown", "seconds", left[0]));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.4f + (warmup - left[0]) * 0.1f);
            left[0]--;
        }, 0L, 20L);
        self[0] = task;
        pending.put(p.getUniqueId(), new Pending(task, p.getLocation().clone()));
        lang().send(p, "spawn.warmup", "seconds", warmup);
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            lang().send(sender, "error.players-only");
            return true;
        }
        if (command.getName().equalsIgnoreCase("setspawn")) {
            setSpawn(p.getLocation());
            lang().send(p, "spawn.set", "world", p.getWorld().getName(),
                    "x", p.getLocation().getBlockX(), "y", p.getLocation().getBlockY(), "z", p.getLocation().getBlockZ());
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.4f);
            return true;
        }
        // /spawn <player> sends someone else, for staff
        if (args.length > 0 && p.hasPermission("playban.spawn.others")) {
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                lang().send(p, "error.unknown-player", "player", args[0]);
                return true;
            }
            teleport(target);
            lang().send(target, "spawn.sent-you", "staff", p.getName());
            lang().send(p, "spawn.sent", "player", target.getName());
            return true;
        }
        request(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("spawn") && args.length == 1 && sender.hasPermission("playban.spawn.others")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Pending p = pending.get(e.getPlayer().getUniqueId());
        if (p == null) return;
        if (e.getTo().getWorld() != p.start().getWorld() || e.getTo().distanceSquared(p.start()) > 0.25) {
            cancel(e.getPlayer(), "spawn.moved");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && pending.containsKey(p.getUniqueId())) cancel(p, "spawn.hurt");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled() || !isSet()) return;
        Player p = e.getPlayer();
        if (plugin.punishments().inLimbo(p.getUniqueId())) return;
        String mode = plugin.getConfig().getString("spawn.on-join", "FIRST_JOIN").toUpperCase();
        if (mode.equals("ALWAYS") || (mode.equals("FIRST_JOIN") && !p.hasPlayedBefore())) {
            Location to = spawn();
            if (to != null) p.teleport(to);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        // only with a spawn set through PlayBan, so a world plugin's respawn points are left alone otherwise
        if (!enabled() || !isSet() || !plugin.getConfig().getBoolean("spawn.on-death", true)) return;
        if (e.isBedSpawn() || e.isAnchorSpawn()) return;
        Location to = spawn();
        if (to != null) e.setRespawnLocation(to);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancel(e.getPlayer(), null);
    }
}
