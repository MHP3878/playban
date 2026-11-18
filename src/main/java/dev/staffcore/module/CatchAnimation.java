package dev.staffcore.module;

import dev.staffcore.StaffCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * What everybody around sees when the anti-cheat catches someone: a black hole that swallows them,
 * a giant mace falling from the sky, or a lightning cage. The player is held in place meanwhile;
 * when it is over, the punishment follows.
 */
public final class CatchAnimation {
    private final StaffCorePlugin plugin;
    private final Set<UUID> playing = new HashSet<>();
    private final Random random = new Random();

    public CatchAnimation(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean playing(UUID id) {
        return playing.contains(id);
    }

    /** Plays the configured animation (or a random one), then runs done. Without one, done runs at once. */
    public void play(Player p, Runnable done) {
        String kind = plugin.getConfig().getString("anticheat.animation", "random").toLowerCase();
        if (kind.equals("random")) kind = List.of("blackhole", "mace", "lightning").get(random.nextInt(3));
        if (kind.equals("none") || playing.contains(p.getUniqueId())) {
            done.run();
            return;
        }
        playing.add(p.getUniqueId());
        Runnable finish = () -> {
            playing.remove(p.getUniqueId());
            done.run();
        };
        try {
            switch (kind) {
                case "mace" -> mace(p, finish);
                case "lightning" -> lightning(p, finish);
                default -> blackHole(p, finish);
            }
        } catch (RuntimeException e) {
            finish.run();
        }
    }

    private static void particle(World w, Particle type, Location at, int count, double spread, double speed) {
        try {
            w.spawnParticle(type, at, count, spread, spread, spread, speed);
        } catch (RuntimeException ignored) {
            // a particle this server does not have
        }
    }

    private static void sound(Location at, String key, float volume, float pitch) {
        try {
            at.getWorld().playSound(at, key, volume, pitch);
        } catch (RuntimeException ignored) {
            // only sound
        }
    }

    /** A black hole opens above the player, pulls them up spinning and swallows them. */
    private void blackHole(Player p, Runnable done) {
        Location base = p.getLocation().clone();
        Location center = base.clone().add(0, 3.2, 0);
        World w = base.getWorld();
        List<Entity> parts = new ArrayList<>();
        BlockDisplay core = w.spawn(center, BlockDisplay.class, d -> {
            d.setBlock(Material.BLACK_CONCRETE.createBlockData());
            d.setPersistent(false);
            d.setBrightness(new Display.Brightness(0, 0));
            d.setTransformation(new Transformation(new Vector3f(-0.05f), new Quaternionf(), new Vector3f(0.1f), new Quaternionf()));
        });
        parts.add(core);
        BlockDisplay ring = w.spawn(center, BlockDisplay.class, d -> {
            d.setBlock(Material.PURPLE_STAINED_GLASS.createBlockData());
            d.setPersistent(false);
            d.setGlowing(true);
            d.setGlowColorOverride(org.bukkit.Color.fromRGB(0x8a2be2));
            d.setTransformation(new Transformation(new Vector3f(-0.05f), new Quaternionf(), new Vector3f(0.1f), new Quaternionf()));
        });
        parts.add(ring);
        sound(center, "minecraft:block.portal.trigger", 1.5f, 0.5f);
        sound(center, "minecraft:entity.warden.sonic_charge", 1.5f, 0.6f);
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0]++;
            float grow = Math.min(1f, t[0] / 20f);
            float size = 0.2f + grow * 2.2f;
            float spin = t[0] * 0.25f;
            try {
                core.setInterpolationDuration(1);
                core.setInterpolationDelay(0);
                core.setTransformation(new Transformation(new Vector3f(-size / 2), new Quaternionf(new AxisAngle4f(spin, 0.3f, 1, 0.2f)),
                        new Vector3f(size), new Quaternionf()));
                float r = size * 1.5f;
                ring.setInterpolationDuration(1);
                ring.setTransformation(new Transformation(new Vector3f(-r / 2, -0.05f, -r / 2), new Quaternionf(new AxisAngle4f(-spin * 1.5f, 0, 1, 0)),
                        new Vector3f(r, 0.1f, r), new Quaternionf()));
            } catch (RuntimeException ignored) {
                // older display API
            }
            // dust spiralling in
            for (int i = 0; i < 6; i++) {
                double a = t[0] * 0.4 + i * Math.PI / 3;
                double radius = 3.5 - (t[0] % 10) * 0.3;
                particle(w, Particle.PORTAL, center.clone().add(Math.cos(a) * radius, Math.sin(a * 0.5) * 0.8, Math.sin(a) * radius), 3, 0.05, 0.3);
            }
            particle(w, Particle.REVERSE_PORTAL, center, 8, 0.6, 0.05);
            particle(w, Particle.SQUID_INK, center, 3, 0.4, 0.02);
            // the player is pulled up, spinning
            if (p.isOnline() && t[0] > 15) {
                double lift = Math.min(1, (t[0] - 15) / 30.0);
                Location to = base.clone().add(0, lift * 2.6, 0);
                to.setYaw(p.getLocation().getYaw() + 25);
                to.setPitch(-30);
                p.teleport(to);
                p.setFallDistance(0);
            } else if (p.isOnline()) {
                p.teleport(base.clone().setDirection(center.toVector().subtract(base.toVector())));
            }
            if (t[0] == 46) {
                sound(center, "minecraft:entity.generic.explode", 1.2f, 0.5f);
                sound(center, "minecraft:entity.enderman.teleport", 1.5f, 0.4f);
                particle(w, Particle.EXPLOSION, center, 3, 0.5, 0);
                particle(w, Particle.LARGE_SMOKE, center, 40, 1.0, 0.05);
            }
            if (t[0] >= 50) {
                task[0].cancel();
                parts.forEach(Entity::remove);
                if (p.isOnline()) p.teleport(base);
                done.run();
            }
        }, 1L, 1L);
    }

    /** A giant mace falls from the sky onto the player. */
    private void mace(Player p, Runnable done) {
        Location base = p.getLocation().clone();
        World w = base.getWorld();
        Location top = base.clone().add(0, 16, 0);
        ItemDisplay mace = w.spawn(top, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.MACE));
            d.setPersistent(false);
            d.setGlowing(true);
            d.setGlowColorOverride(org.bukkit.Color.fromRGB(0xff3030));
            d.setBrightness(new Display.Brightness(15, 15));
            // head down, five times bigger
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(new AxisAngle4f((float) Math.toRadians(135), 0, 0, 1)),
                    new Vector3f(5f), new Quaternionf()));
        });
        try {
            mace.setTeleportDuration(1);
        } catch (RuntimeException | NoSuchMethodError ignored) {
            // jumps instead of glides
        }
        sound(base, "minecraft:item.trident.thunder", 1.2f, 1.2f);
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0]++;
            if (p.isOnline()) {
                p.teleport(base.clone().setDirection(new org.bukkit.util.Vector(0, 1, 0.001)));
                p.setFallDistance(0);
            }
            if (t[0] <= 24) {
                double f = t[0] / 24.0;
                mace.teleport(top.clone().add(0, -f * f * 15, 0));
                particle(w, Particle.CRIT, mace.getLocation(), 6, 0.4, 0.1);
                // a red marker on the ground
                for (int i = 0; i < 16; i++) {
                    double a = i * Math.PI / 8;
                    particle(w, Particle.FLAME, base.clone().add(Math.cos(a) * 1.5, 0.1, Math.sin(a) * 1.5), 1, 0, 0);
                }
            }
            if (t[0] == 24) {
                sound(base, "minecraft:item.mace.smash_ground_heavy", 2f, 0.8f);
                sound(base, "minecraft:entity.generic.explode", 1.5f, 0.7f);
                sound(base, "minecraft:block.anvil.land", 1.5f, 0.5f);
                particle(w, Particle.EXPLOSION_EMITTER, base, 1, 0, 0);
                for (int ring = 1; ring <= 3; ring++) {
                    for (int i = 0; i < 24; i++) {
                        double a = i * Math.PI / 12;
                        particle(w, Particle.CLOUD, base.clone().add(Math.cos(a) * ring, 0.2, Math.sin(a) * ring), 1, 0, 0.02);
                    }
                }
                particle(w, Particle.BLOCK, base, 0, 0, 0);
            }
            if (t[0] >= 40) {
                task[0].cancel();
                mace.remove();
                done.run();
            }
        }, 1L, 1L);
    }

    /** Four lightning strikes build a cage, sparks run between them, then the last strike. */
    private void lightning(Player p, Runnable done) {
        Location base = p.getLocation().clone();
        World w = base.getWorld();
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0]++;
            if (p.isOnline()) {
                p.teleport(base);
                p.setFallDistance(0);
            }
            if (t[0] % 6 == 0 && t[0] <= 24) {
                int k = t[0] / 6;
                double a = k * Math.PI / 2 + Math.PI / 4;
                try {
                    w.strikeLightningEffect(base.clone().add(Math.cos(a) * 2, 0, Math.sin(a) * 2));
                } catch (RuntimeException ignored) {
                    // only the effect
                }
            }
            for (int i = 0; i < 4; i++) {
                double a = i * Math.PI / 2 + Math.PI / 4 + t[0] * 0.15;
                for (double y = 0; y < 2.5; y += 0.5) {
                    particle(w, Particle.ELECTRIC_SPARK, base.clone().add(Math.cos(a) * 2, y, Math.sin(a) * 2), 1, 0.05, 0);
                }
            }
            particle(w, Particle.END_ROD, base.clone().add(0, 1, 0), 3, 0.4, 0.02);
            if (t[0] == 34) {
                try {
                    w.strikeLightningEffect(base);
                } catch (RuntimeException ignored) {
                    // only the effect
                }
                sound(base, "minecraft:entity.lightning_bolt.thunder", 2f, 0.8f);
            }
            if (t[0] >= 40) {
                task[0].cancel();
                done.run();
            }
        }, 1L, 1L);
    }

    /**
     * The banhammer: a giant hammer swings down onto the player from behind the one holding it, the
     * ground cracks in a ring, the player is thrown up spinning and bursts into sparks with a big
     * "BANNED" above them. Everybody around sees it.
     */
    public void hammer(Player victim, Player staff, Runnable done) {
        if (!playing.add(victim.getUniqueId())) return;
        Location base = victim.getLocation().clone();
        World w = base.getWorld();
        // the swing comes from the side of the one who hits
        org.bukkit.util.Vector side = staff.getLocation().toVector().subtract(base.toVector()).setY(0);
        final org.bukkit.util.Vector from = side.lengthSquared() < 0.01 ? new org.bukkit.util.Vector(1, 0, 0) : side.normalize();
        // the axis the hammer turns around: across the line between the two
        Vector3f axis = new Vector3f((float) -from.getZ(), 0, (float) from.getX());
        Location pivot = base.clone().add(from.clone().multiply(2.2)).add(0, 0.2, 0);
        ItemDisplay hammer;
        try {
            hammer = w.spawn(pivot, ItemDisplay.class, d -> {
                d.setItemStack(new ItemStack(Material.MACE));
                d.setPersistent(false);
                d.setGlowing(true);
                d.setGlowColorOverride(org.bukkit.Color.fromRGB(0xffc371));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformation(swing(axis, -1.9f));
            });
        } catch (RuntimeException e) {
            playing.remove(victim.getUniqueId());
            done.run();
            return;
        }
        sound(base, "minecraft:item.trident.riptide_3", 1.2f, 0.7f);
        Material ground = base.clone().add(0, -1, 0).getBlock().getType();
        org.bukkit.block.data.BlockData groundData = (ground.isAir() ? Material.STONE : ground).createBlockData();
        List<Entity> extra = new ArrayList<>();
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0]++;
            // the swing: slow at first, then down with full force
            if (t[0] <= 10) {
                float f = t[0] / 10f;
                float angle = -1.9f + (float) Math.pow(f, 2.2) * 2.05f;
                try {
                    hammer.setInterpolationDelay(0);
                    hammer.setInterpolationDuration(1);
                    hammer.setTransformation(swing(axis, angle));
                } catch (RuntimeException ignored) {
                    // older display API
                }
                if (victim.isOnline()) {
                    victim.teleport(base.clone().setDirection(from.clone()));
                    victim.setFallDistance(0);
                }
            }
            if (t[0] == 10) {
                sound(base, "minecraft:item.mace.smash_ground_heavy", 2f, 0.7f);
                sound(base, "minecraft:block.anvil.land", 1.6f, 0.5f);
                sound(base, "minecraft:entity.generic.explode", 1.4f, 0.8f);
                particle(w, Particle.EXPLOSION_EMITTER, base, 1, 0, 0);
                try {
                    w.spawnParticle(Particle.BLOCK, base.clone().add(0, 0.1, 0), 80, 1.2, 0.1, 1.2, 0.2, groundData);
                } catch (RuntimeException ignored) {
                    // only the effect
                }
            }
            // a shockwave runs outward over the ground
            if (t[0] >= 10 && t[0] <= 18) {
                double r = (t[0] - 9) * 0.6;
                for (int i = 0; i < 28; i++) {
                    double a = i * Math.PI * 2 / 28;
                    Location ring = base.clone().add(Math.cos(a) * r, 0.15, Math.sin(a) * r);
                    particle(w, Particle.CLOUD, ring, 1, 0.02, 0.01);
                    try {
                        w.spawnParticle(Particle.BLOCK, ring, 2, 0.1, 0.05, 0.1, 0.1, groundData);
                    } catch (RuntimeException ignored) {
                        // only the effect
                    }
                }
            }
            // thrown up, spinning
            if (t[0] > 10 && t[0] <= 34 && victim.isOnline()) {
                double up = Math.min(1, (t[0] - 10) / 24.0);
                Location to = base.clone().add(0, Math.sin(up * Math.PI / 2) * 6, 0);
                to.setYaw(victim.getLocation().getYaw() + 40);
                to.setPitch(-40);
                victim.teleport(to);
                victim.setFallDistance(0);
                particle(w, Particle.CRIT, to.clone().add(0, 1, 0), 6, 0.3, 0.1);
            }
            if (t[0] == 34) {
                Location top = victim.isOnline() ? victim.getLocation().add(0, 1, 0) : base.clone().add(0, 7, 0);
                sound(top, "minecraft:entity.firework_rocket.large_blast", 2f, 0.9f);
                sound(top, "minecraft:entity.firework_rocket.twinkle", 2f, 1f);
                sound(top, "minecraft:entity.lightning_bolt.thunder", 1f, 1.4f);
                particle(w, Particle.FIREWORK, top, 120, 0.3, 0.35);
                particle(w, Particle.FLAME, top, 40, 0.4, 0.15);
                particle(w, Particle.EXPLOSION, top, 4, 0.6, 0);
                try {
                    TextDisplay banned = w.spawn(top.clone().add(0, 1.2, 0), TextDisplay.class, d -> {
                        d.text(plugin.lang().msg("tools.banhammer-text"));
                        d.setPersistent(false);
                        d.setBillboard(Display.Billboard.CENTER);
                        d.setShadowed(true);
                        d.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
                        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(3f), new Quaternionf()));
                    });
                    extra.add(banned);
                } catch (RuntimeException ignored) {
                    // only the effect
                }
                hammer.remove();
            }
            if (t[0] >= 36) {
                task[0].cancel();
                playing.remove(victim.getUniqueId());
                done.run();
            }
            if (t[0] >= 60) {
                extra.forEach(Entity::remove);
            }
        }, 1L, 1L);
        // the text stays a moment after the player is gone
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            hammer.remove();
            extra.forEach(Entity::remove);
        }, 90L);
    }

    private static Transformation swing(Vector3f axis, float angle) {
        // the hammer stands on its handle end at the pivot and turns around the axis
        Quaternionf rot = new Quaternionf(new AxisAngle4f(angle, axis.x, axis.y, axis.z));
        Quaternionf tilt = new Quaternionf(new AxisAngle4f((float) Math.toRadians(-45), 0, 0, 1));
        return new Transformation(new Vector3f(0, 2.2f, 0).rotate(rot), rot.mul(tilt, new Quaternionf()), new Vector3f(4.5f), new Quaternionf());
    }
}
