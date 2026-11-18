package dev.staffcore.module;

import dev.staffcore.StaffCorePlugin;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plays a recorded clip back for one team member. A figure with the player's head moves exactly as
 * recorded, one recorded tick per game tick; hits, breaks and totems flash where they happened. Three
 * ways to watch: through the player's eyes, following behind, or flying around freely. Pause, slower
 * (down to a quarter) and faster from clickable buttons in chat. Nobody else sees any of it, and the
 * viewer ends up where they were, in the game mode they had.
 */
public final class EvidenceReplay {
    public enum View { FIRST, FOLLOW, FREE }

    private static final class Run {
        Player viewer;
        ArmorStand ghost;
        BossBar bar;
        BukkitTask task;
        List<AntiCheat.Sample> frames;
        Map<Integer, List<String>> events;
        double position;
        double speed = 1;
        boolean paused;
        View view = View.FOLLOW;
        Location home;
        GameMode homeMode;
        boolean homeFlying;
        String name;
    }

    private final StaffCorePlugin plugin;
    private final Map<UUID, Run> runs = new HashMap<>();

    public EvidenceReplay(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean running(UUID viewer) {
        return runs.containsKey(viewer);
    }

    public boolean play(Player viewer, UUID subject, AntiCheat.Clip clip) {
        return play(viewer, subject, clip, View.FOLLOW);
    }

    public boolean play(Player viewer, UUID subject, AntiCheat.Clip clip, View view) {
        // movement frames apart from actions: mixing them made the figure jump back and forth
        List<AntiCheat.Sample> frames = new ArrayList<>();
        Map<Integer, List<String>> events = new HashMap<>();
        for (AntiCheat.Sample s : clip.samples()) {
            if (s.event() == null) frames.add(s);
            else events.computeIfAbsent(Math.max(0, frames.size() - 1), k -> new ArrayList<>()).add(s.event());
        }
        if (frames.isEmpty()) return false;
        World world = Bukkit.getWorld(frames.get(0).world());
        if (world == null) return false;
        stop(viewer.getUniqueId());
        Run r = new Run();
        r.viewer = viewer;
        r.frames = frames;
        r.events = events;
        r.view = view;
        r.name = clip.name();
        r.home = viewer.getLocation();
        r.homeMode = viewer.getGameMode();
        r.homeFlying = viewer.isFlying();
        AntiCheat.Sample first = frames.get(0);
        Location start = new Location(world, first.x(), first.y(), first.z(), first.yaw(), first.pitch());
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(subject));
            head.setItemMeta(meta);
        }
        r.ghost = world.spawn(start, ArmorStand.class, a -> {
            optional(() -> a.setVisibleByDefault(false));
            a.setPersistent(false);
            optional(() -> a.setGravity(false));
            optional(() -> a.setInvulnerable(true));
            optional(() -> a.setArms(true));
            optional(() -> a.setBasePlate(false));
            optional(() -> a.setDisabledSlots(org.bukkit.inventory.EquipmentSlot.values()));
            a.customName(Component.text(clip.name()));
            a.setCustomNameVisible(true);
            optional(() -> a.getEquipment().setHelmet(head));
            optional(() -> a.setGlowing(true));
        });
        optional(() -> viewer.showEntity(plugin, r.ghost));
        if (!plugin.punishments().isVanished(viewer.getUniqueId())) plugin.punishments().setVanished(viewer, true);
        r.bar = BossBar.bossBar(plugin.lang().msg("replay.bar", "player", clip.name(), "reason", clip.reason()), 0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        viewer.showBossBar(r.bar);
        runs.put(viewer.getUniqueId(), r);
        applyView(r, start);
        plugin.lang().send(viewer, "replay.started", "player", clip.name(), "seconds", Math.max(1, frames.size() / 20));
        controls(viewer, clip.name());
        r.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> step(r), 1L, 1L);
        return true;
    }

    /** The buttons in chat. */
    private void controls(Player viewer, String name) {
        Component line = Component.empty();
        for (String c : List.of("first", "follow", "free", "pause", "slower", "faster", "stop")) {
            line = line.append(plugin.lang().msg("replay.button." + c).clickEvent(ClickEvent.runCommand("/ac replay " + name + " " + c)))
                    .append(Component.text(" "));
        }
        viewer.sendMessage(line);
    }

    private void applyView(Run r, Location at) {
        Player v = r.viewer;
        switch (r.view) {
            case FIRST -> {
                if (v.getGameMode() != GameMode.SPECTATOR) v.setGameMode(GameMode.SPECTATOR);
                v.teleport(at);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (runs.get(v.getUniqueId()) == r && r.view == View.FIRST) optional(() -> v.setSpectatorTarget(r.ghost));
                }, 2L);
            }
            case FOLLOW -> {
                optional(() -> v.setSpectatorTarget(null));
                if (v.getGameMode() == GameMode.SPECTATOR && r.homeMode != GameMode.SPECTATOR) v.setGameMode(GameMode.SPECTATOR);
                v.teleport(follow(at));
            }
            default -> {
                optional(() -> v.setSpectatorTarget(null));
                v.setAllowFlight(true);
                v.setFlying(true);
            }
        }
    }

    private static Location follow(Location at) {
        Vector back = at.getDirection().setY(0);
        if (back.lengthSquared() < 0.01) back = new Vector(0, 0, 1);
        Location cam = at.clone().subtract(back.normalize().multiply(3.5)).add(0, 1.8, 0);
        cam.setDirection(at.clone().add(0, 1.4, 0).toVector().subtract(cam.toVector()));
        return cam;
    }

    private void step(Run r) {
        if (!r.viewer.isOnline() || !r.ghost.isValid()) {
            stop(r.viewer.getUniqueId());
            return;
        }
        if (r.paused) return;
        int before = (int) r.position;
        r.position += r.speed;
        int now = (int) r.position;
        if (now >= r.frames.size()) {
            plugin.lang().send(r.viewer, "replay.ended");
            stop(r.viewer.getUniqueId());
            return;
        }
        AntiCheat.Sample s = r.frames.get(now);
        World w = r.ghost.getWorld();
        Location at = new Location(w, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
        r.ghost.teleport(at);
        optional(() -> r.ghost.setHeadPose(new org.bukkit.util.EulerAngle(Math.toRadians(s.pitch()), 0, 0)));
        if (r.view == View.FOLLOW) r.viewer.teleport(follow(at));
        for (int i = before + 1; i <= now; i++) {
            List<String> ev = r.events.get(i);
            if (ev == null) continue;
            for (String e : ev) {
                Location fx = at.clone().add(0, 1.6, 0).add(at.getDirection().multiply(1.2));
                optional(() -> r.viewer.spawnParticle(e.startsWith("hit") ? Particle.CRIT : e.startsWith("totem") ? Particle.TOTEM_OF_UNDYING
                        : Particle.CLOUD, fx, 12, 0.2, 0.2, 0.2, 0.05));
                r.viewer.sendActionBar(plugin.lang().msg("replay.event", "event", e));
            }
        }
        r.bar.progress(Math.min(1f, now / (float) Math.max(1, r.frames.size() - 1)));
    }

    /** The buttons: first, follow, free, pause, slower, faster, stop. Returns false for an unknown one. */
    public boolean control(Player viewer, String what) {
        Run r = runs.get(viewer.getUniqueId());
        if (r == null) return false;
        switch (what) {
            case "first" -> {
                r.view = View.FIRST;
                applyView(r, r.ghost.getLocation());
            }
            case "follow" -> {
                r.view = View.FOLLOW;
                applyView(r, r.ghost.getLocation());
            }
            case "free" -> {
                r.view = View.FREE;
                if (viewer.getGameMode() == GameMode.SPECTATOR) viewer.setGameMode(GameMode.SPECTATOR);
                applyView(r, r.ghost.getLocation());
            }
            case "pause" -> r.paused = !r.paused;
            case "slower" -> r.speed = Math.max(0.25, r.speed / 2);
            case "faster" -> r.speed = Math.min(4, r.speed * 2);
            case "stop" -> {
                stop(viewer.getUniqueId());
                return true;
            }
            default -> {
                return false;
            }
        }
        viewer.sendActionBar(plugin.lang().msg("replay.state", "speed", r.speed == Math.floor(r.speed) ? String.valueOf((int) r.speed) : String.valueOf(r.speed),
                "state", plugin.lang().raw(r.paused ? "replay.paused" : "replay.playing")));
        return true;
    }

    public void stop(UUID viewer) {
        Run r = runs.remove(viewer);
        if (r == null) return;
        if (r.task != null) r.task.cancel();
        r.ghost.remove();
        Player p = Bukkit.getPlayer(viewer);
        if (p != null) {
            p.hideBossBar(r.bar);
            optional(() -> p.setSpectatorTarget(null));
            // back where they were, as they were
            if (p.getGameMode() != r.homeMode) p.setGameMode(r.homeMode);
            p.teleport(r.home);
            if (r.homeFlying && p.getAllowFlight()) p.setFlying(true);
        }
    }

    public void stopAll() {
        for (UUID id : List.copyOf(runs.keySet())) stop(id);
    }

    private static void optional(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // not on this server
        }
    }
}
