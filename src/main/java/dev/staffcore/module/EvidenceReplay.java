package dev.staffcore.module;

import dev.staffcore.StaffCorePlugin;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plays a recorded clip back for one team member: a figure with the player's head walks and looks
 * exactly as recorded, hits, breaks and totems flash where they happened. Nobody else sees it.
 */
public final class EvidenceReplay {
    private final StaffCorePlugin plugin;
    private final Map<UUID, BukkitTask> running = new HashMap<>();

    public EvidenceReplay(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean play(Player viewer, UUID subject, AntiCheat.Clip clip) {
        List<AntiCheat.Sample> samples = clip.samples();
        if (samples.isEmpty()) return false;
        World world = Bukkit.getWorld(samples.get(0).world());
        if (world == null) return false;
        stop(viewer.getUniqueId());
        AntiCheat.Sample first = samples.get(0);
        Location start = new Location(world, first.x(), first.y(), first.z(), first.yaw(), first.pitch());
        // the viewer watches from a little behind and above, unseen
        Location watch = start.clone().add(start.getDirection().setY(0).normalize().multiply(-4)).add(0, 2, 0);
        watch.setDirection(start.toVector().subtract(watch.toVector()));
        plugin.admin().tpAdminTo(viewer, watch);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(subject));
            head.setItemMeta(meta);
        }
        ArmorStand ghost = world.spawn(start, ArmorStand.class, a -> {
            optional(() -> a.setVisibleByDefault(false));
            a.setPersistent(false);
            optional(() -> a.setGravity(false));
            optional(() -> a.setInvulnerable(true));
            optional(() -> a.setArms(true));
            optional(() -> a.setBasePlate(false));
            a.customName(Component.text(clip.name()));
            a.setCustomNameVisible(true);
            optional(() -> a.getEquipment().setHelmet(head));
            optional(() -> a.setGlowing(true));
        });
        optional(() -> viewer.showEntity(plugin, ghost));
        BossBar bar = BossBar.bossBar(plugin.lang().msg("replay.bar", "player", clip.name(), "reason", clip.reason()), 0f,
                BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        viewer.showBossBar(bar);
        plugin.lang().send(viewer, "replay.started", "player", clip.name(), "seconds", Math.max(1, (samples.get(samples.size() - 1).time() - first.time()) / 1000));
        int[] i = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!viewer.isOnline() || i[0] >= samples.size()) {
                end(viewer, ghost, bar);
                return;
            }
            // one tick of movement, and every action of that moment
            long t = samples.get(i[0]).time();
            while (i[0] < samples.size() && samples.get(i[0]).time() <= t + 25) {
                AntiCheat.Sample s = samples.get(i[0]++);
                Location at = new Location(world, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
                ghost.teleport(at);
                try {
                    ghost.setHeadPose(new org.bukkit.util.EulerAngle(Math.toRadians(s.pitch()), 0, 0));
                } catch (RuntimeException ignored) {
                    // only the look
                }
                if (s.event() != null) {
                    Location fx = at.clone().add(0, 1.6, 0).add(at.getDirection().multiply(1.2));
                    try {
                        viewer.spawnParticle(s.event().startsWith("hit") ? Particle.CRIT : s.event().startsWith("totem") ? Particle.TOTEM_OF_UNDYING
                                : Particle.CLOUD, fx, 12, 0.2, 0.2, 0.2, 0.05);
                    } catch (RuntimeException ignored) {
                        // only the effect
                    }
                    viewer.sendActionBar(plugin.lang().msg("replay.event", "event", s.event()));
                }
            }
            bar.progress(Math.min(1f, i[0] / (float) samples.size()));
        }, 1L, 1L);
        running.put(viewer.getUniqueId(), task[0]);
        ghosts.put(viewer.getUniqueId(), new Object[]{ghost, bar});
        return true;
    }

    private final Map<UUID, Object[]> ghosts = new HashMap<>();

    private static void optional(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // not on this server
        }
    }

    private void end(Player viewer, ArmorStand ghost, BossBar bar) {
        stop(viewer.getUniqueId());
        plugin.lang().send(viewer, "replay.ended");
    }

    public void stop(UUID viewer) {
        BukkitTask t = running.remove(viewer);
        if (t != null) t.cancel();
        Object[] g = ghosts.remove(viewer);
        if (g != null) {
            ((ArmorStand) g[0]).remove();
            Player p = Bukkit.getPlayer(viewer);
            if (p != null) p.hideBossBar((BossBar) g[1]);
        }
    }

    public void stopAll() {
        for (UUID id : List.copyOf(running.keySet())) stop(id);
    }
}
