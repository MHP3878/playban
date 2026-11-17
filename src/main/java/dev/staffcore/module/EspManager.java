package dev.staffcore.module;

import dev.staffcore.StaffCorePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ESP for the team: chests glow through walls and every player gets a name tag through walls with
 * health and distance. Only the staff member who switched it on sees any of it.
 */
public final class EspManager implements Listener, TabExecutor {
    public enum Mode { OFF, PLAYERS, CHESTS, ALL }

    private static final class View {
        Mode mode = Mode.OFF;
        final Map<String, BlockDisplay> chests = new HashMap<>();
        final Map<UUID, TextDisplay> tags = new HashMap<>();
        int tick;
    }

    private final StaffCorePlugin plugin;
    private final Map<UUID, View> views = new HashMap<>();
    private BukkitTask task;

    public EspManager(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    public void stop() {
        if (task != null) task.cancel();
        for (UUID id : new ArrayList<>(views.keySet())) clear(id);
        views.clear();
    }

    /** The module was switched off: everybody's ESP goes away. */
    public void stopViews() {
        for (UUID id : new ArrayList<>(views.keySet())) clear(id);
        views.clear();
    }

    /** Leaving staff mode takes the ESP away as well. */
    public void off(Player p) {
        if (views.containsKey(p.getUniqueId())) set(p, Mode.OFF);
    }

    /** How many outlines and tags this staff member sees right now. */
    public int shown(UUID id) {
        View v = views.get(id);
        return v == null ? 0 : v.chests.size() + v.tags.size();
    }

    public Mode mode(UUID id) {
        View v = views.get(id);
        return v == null ? Mode.OFF : v.mode;
    }

    public void set(Player p, Mode mode) {
        clear(p.getUniqueId());
        if (mode == Mode.OFF) views.remove(p.getUniqueId());
        else views.computeIfAbsent(p.getUniqueId(), k -> new View()).mode = mode;
        plugin.lang().send(p, "esp.mode", "mode", plugin.lang().raw("esp.modes.mode-" + mode.name().toLowerCase()));
    }

    /** Off, players, chests, both, off again. */
    public void cycle(Player p) {
        Mode[] all = Mode.values();
        set(p, all[(mode(p.getUniqueId()).ordinal() + 1) % all.length]);
    }

    private void clear(UUID id) {
        View v = views.get(id);
        if (v == null) return;
        v.chests.values().forEach(Entity::remove);
        v.chests.clear();
        v.tags.values().forEach(Entity::remove);
        v.tags.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        clear(e.getPlayer().getUniqueId());
        views.remove(e.getPlayer().getUniqueId());
        // a player who leaves takes their tag with them
        for (View v : views.values()) {
            TextDisplay t = v.tags.remove(e.getPlayer().getUniqueId());
            if (t != null) t.remove();
        }
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        View v = views.get(e.getPlayer().getUniqueId());
        if (v != null) {
            Mode m = v.mode;
            clear(e.getPlayer().getUniqueId());
            v.mode = m;
        }
    }

    private void tick() {
        for (Map.Entry<UUID, View> e : new ArrayList<>(views.entrySet())) {
            Player viewer = Bukkit.getPlayer(e.getKey());
            View v = e.getValue();
            if (viewer == null || !viewer.hasPermission("staffcore.esp")) {
                clear(e.getKey());
                views.remove(e.getKey());
                continue;
            }
            v.tick++;
            if (v.mode == Mode.PLAYERS || v.mode == Mode.ALL) players(viewer, v);
            if ((v.mode == Mode.CHESTS || v.mode == Mode.ALL) && v.tick % 15 == 1) {
                try {
                    chests(viewer, v);
                } catch (UnsupportedOperationException | NoSuchMethodError ignored) {
                    // a server without tile entity lists
                }
            }
        }
    }

    // ---------------------------------------------------------------- players

    private void players(Player viewer, View v) {
        Set<UUID> seen = new HashSet<>();
        double range = plugin.getConfig().getDouble("esp.player-range", 128);
        for (Player other : viewer.getWorld().getPlayers()) {
            if (other == viewer || other.getLocation().distanceSquared(viewer.getLocation()) > range * range) continue;
            seen.add(other.getUniqueId());
            TextDisplay tag = v.tags.get(other.getUniqueId());
            Location at = other.getLocation().add(0, other.getHeight() + 0.45, 0);
            if (tag == null || !tag.isValid()) {
                try {
                    tag = at.getWorld().spawn(at, TextDisplay.class, d -> {
                        d.setVisibleByDefault(false);
                        d.setPersistent(false);
                        d.setSeeThrough(true);
                        d.setBillboard(Display.Billboard.CENTER);
                        d.setShadowed(true);
                        d.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                        d.setBrightness(new Display.Brightness(15, 15));
                        try {
                            d.setTeleportDuration(2);
                        } catch (RuntimeException | NoSuchMethodError ignored) {
                            // older servers jump instead of glide
                        }
                    });
                    viewer.showEntity(plugin, tag);
                    v.tags.put(other.getUniqueId(), tag);
                } catch (RuntimeException failed) {
                    plugin.getLogger().fine("ESP tag: " + failed);
                    continue;
                }
            } else {
                tag.teleport(at);
            }
            if (v.tick % 5 == 0 || tag.text() == null || tag.text().equals(Component.empty())) {
                int dist = (int) Math.round(other.getLocation().distance(viewer.getLocation()));
                String key = plugin.punishments().isVanished(other.getUniqueId()) ? "esp.tag-vanished" : "esp.tag";
                tag.text(plugin.lang().msg(key, "player", other.getName(), "health", (int) Math.ceil(other.getHealth()),
                        "distance", dist, "item", other.getInventory().getItemInMainHand().getType().isAir() ? "-"
                                : pretty(other.getInventory().getItemInMainHand().getType())));
            }
        }
        v.tags.entrySet().removeIf(t -> {
            if (seen.contains(t.getKey())) return false;
            t.getValue().remove();
            return true;
        });
    }

    private static String pretty(Material m) {
        String s = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------------------------------------------------------------- chests

    private static Color colorOf(Material m) {
        String n = m.name();
        if (n.endsWith("SHULKER_BOX")) return Color.fromRGB(0xb04fe3);
        if (m == Material.ENDER_CHEST) return Color.fromRGB(0x2fd0a0);
        if (m == Material.BARREL) return Color.fromRGB(0xc58a4a);
        if (m == Material.TRAPPED_CHEST) return Color.fromRGB(0xff5050);
        if (n.contains("HOPPER") || n.contains("DROPPER") || n.contains("DISPENSER")) return Color.fromRGB(0x9a9a9a);
        if (n.contains("FURNACE") || m == Material.SMOKER) return Color.fromRGB(0xff9a3c);
        return Color.fromRGB(0xf5c542);
    }

    private static boolean storage(BlockState s) {
        return s instanceof org.bukkit.block.Container || s.getType() == Material.ENDER_CHEST;
    }

    private void chests(Player viewer, View v) {
        World w = viewer.getWorld();
        int radius = Math.max(1, Math.min(6, plugin.getConfig().getInt("esp.chest-chunks", 3)));
        int limit = plugin.getConfig().getInt("esp.chest-limit", 400);
        int cx = viewer.getLocation().getBlockX() >> 4, cz = viewer.getLocation().getBlockZ() >> 4;
        List<BlockState> found = new ArrayList<>();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (!w.isChunkLoaded(x, z)) continue;
                Chunk c = w.getChunkAt(x, z);
                for (BlockState s : c.getTileEntities()) if (storage(s)) found.add(s);
            }
        }
        Location me = viewer.getLocation();
        found.sort((a, b) -> Double.compare(a.getLocation().distanceSquared(me), b.getLocation().distanceSquared(me)));
        if (found.size() > limit) found = found.subList(0, limit);
        Set<String> keep = new HashSet<>();
        for (BlockState s : found) {
            String key = s.getX() + "," + s.getY() + "," + s.getZ();
            keep.add(key);
            BlockDisplay d = v.chests.get(key);
            if (d != null && d.isValid() && d.getBlock().getMaterial() == s.getType()) continue;
            if (d != null) d.remove();
            try {
                BlockDisplay shown = w.spawn(s.getLocation(), BlockDisplay.class, display -> {
                    display.setBlock(s.getBlockData());
                    display.setGlowing(true);
                    display.setGlowColorOverride(colorOf(s.getType()));
                    display.setVisibleByDefault(false);
                    display.setPersistent(false);
                    display.setBrightness(new Display.Brightness(15, 15));
                    display.setTransformation(new Transformation(new Vector3f(0.0625f), new Quaternionf(), new Vector3f(0.875f), new Quaternionf()));
                });
                viewer.showEntity(plugin, shown);
                v.chests.put(key, shown);
            } catch (RuntimeException ignored) {
                // a server that cannot spawn displays
            }
        }
        v.chests.entrySet().removeIf(c -> {
            if (keep.contains(c.getKey())) return false;
            c.getValue().remove();
            return true;
        });
    }

    // ---------------------------------------------------------------- /esp

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.lang().send(sender, "error.players-only");
            return true;
        }
        if (args.length == 0) {
            cycle(p);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "players", "player" -> set(p, Mode.PLAYERS);
            case "chests", "chest" -> set(p, Mode.CHESTS);
            case "all", "on" -> set(p, Mode.ALL);
            case "off" -> set(p, Mode.OFF);
            default -> plugin.lang().send(p, "esp.usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) for (String s : List.of("players", "chests", "all", "off")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        return out;
    }
}
