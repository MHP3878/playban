package dev.staffcore.module;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import dev.staffcore.model.Category;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A careful anti-cheat. Every check adds violation points that slowly fade again. A few points put a
 * player on the suspect list for the team (/admin), only a lot of points from a clear check (flying,
 * speed, reach, kill aura, no fall, no knockback) lead to a punishment by the cheating category.
 * Lag is taken into account: with a slow server or a high ping the movement checks stay quiet.
 */
public final class AntiCheat implements Listener, TabExecutor {
    public static final List<String> CHECKS = List.of("fly", "speed", "reach", "aura", "autoclick", "nofall", "velocity", "xray");

    /** Where a player stands in the eyes of the team. */
    public static final class Suspect {
        public UUID id;
        public String name;
        public final Map<String, Double> checks = new LinkedHashMap<>();
        public long firstSeen;
        public long lastSeen;
        public String handler;
        public boolean punished;

        public double score() {
            double s = 0;
            for (double v : checks.values()) s += v;
            return s;
        }
    }

    private static final class State {
        Location last;
        long exemptUntil;
        int hoverTicks, riseTicks;
        double riseStartY;
        boolean wasOnGround = true;
        double fallStartY = Double.NaN;
        long landedAt;
        double landedFall;
        boolean fallDamaged;
        final Deque<double[]> moves = new ArrayDeque<>(); // {time, horizontal distance}
        int speedStrikes;
        long iceUntil;
        final Map<String, Double> vl = new HashMap<>();
        final Deque<Long> swings = new ArrayDeque<>();
        int cpsStrikes;
        long diggingUntil;
        long lastHit;
        UUID lastTarget;
        int kbLow;
        int stone, ores;
        long miningWindowStart;
        long lastAlert;
    }

    private final StaffCorePlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Suspect> suspects = new LinkedHashMap<>();
    private BukkitTask task;
    private int ticks;

    public AntiCheat(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    public boolean enabled() {
        return plugin.modules() != null && plugin.modules().on("anticheat");
    }

    private File file() {
        return new File(plugin.getDataFolder(), "anticheat.yml");
    }

    // ---------------------------------------------------------------- suspects

    public List<Suspect> suspects() {
        List<Suspect> list = new ArrayList<>(suspects.values());
        list.sort((a, b) -> Double.compare(b.score(), a.score()));
        return list;
    }

    public Suspect suspect(UUID id) {
        return suspects.get(id);
    }

    public void dismiss(UUID id, String by) {
        Suspect s = suspects.remove(id);
        State st = states.get(id);
        if (st != null) st.vl.clear();
        save();
        if (s != null && by != null) plugin.staffLog(by, "ac-dismiss", s.name, null);
    }

    public void handle(UUID id, String by) {
        Suspect s = suspects.get(id);
        if (s == null) return;
        s.handler = by;
        save();
        plugin.staffLog(by, "ac-case", s.name, String.format(java.util.Locale.ROOT, "%.0f", s.score()));
    }

    public void load() {
        suspects.clear();
        if (!file().exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file());
        for (String k : cfg.getKeys(false)) {
            ConfigurationSection c = cfg.getConfigurationSection(k);
            if (c == null) continue;
            try {
                Suspect s = new Suspect();
                s.id = UUID.fromString(k);
                s.name = c.getString("name", "?");
                s.firstSeen = c.getLong("first");
                s.lastSeen = c.getLong("last");
                s.handler = c.getString("handler");
                s.punished = c.getBoolean("punished");
                ConfigurationSection ch = c.getConfigurationSection("checks");
                if (ch != null) for (String id : ch.getKeys(false)) s.checks.put(id, ch.getDouble(id));
                suspects.put(s.id, s);
            } catch (IllegalArgumentException ignored) {
                // broken entry
            }
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Suspect s : suspects.values()) {
            String p = s.id + ".";
            cfg.set(p + "name", s.name);
            cfg.set(p + "first", s.firstSeen);
            cfg.set(p + "last", s.lastSeen);
            cfg.set(p + "handler", s.handler);
            cfg.set(p + "punished", s.punished);
            for (Map.Entry<String, Double> c : s.checks.entrySet()) cfg.set(p + "checks." + c.getKey(), Math.round(c.getValue() * 10) / 10.0);
        }
        try {
            cfg.save(file());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save anticheat.yml: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- scoring

    private double flagAt(String check) {
        return plugin.getConfig().getDouble("anticheat.checks." + check + ".flag", switch (check) {
            case "fly" -> 8;
            case "speed" -> 8;
            case "reach" -> 6;
            case "aura" -> 5;
            case "autoclick" -> 4;
            case "nofall" -> 4;
            case "velocity" -> 3;
            default -> 1;
        });
    }

    /** 0 means this check never punishes on its own. */
    private double punishAt(String check) {
        return plugin.getConfig().getDouble("anticheat.checks." + check + ".punish", switch (check) {
            case "fly" -> 30;
            case "speed" -> 30;
            case "reach" -> 20;
            case "aura" -> 15;
            case "nofall" -> 15;
            case "velocity" -> 12;
            default -> 0;
        });
    }

    private boolean checkOn(String check) {
        return plugin.getConfig().getBoolean("anticheat.checks." + check + ".enabled", true);
    }

    /** Whether the server is too slow to judge anything fairly right now. */
    private boolean laggy() {
        try {
            return Bukkit.getTPS()[0] < plugin.getConfig().getDouble("anticheat.min-tps", 18);
        } catch (RuntimeException | NoSuchMethodError e) {
            return false;
        }
    }

    private static int ping(Player p) {
        try {
            return p.getPing();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Adds violation points; crossing the flag line marks the player, a lot of points can punish. */
    public void violation(Player p, String check, double amount, String detail) {
        if (!enabled() || !checkOn(check) || exempt(p) || laggy()) return;
        State st = state(p);
        double vl = st.vl.merge(check, amount, Double::sum);
        if (vl < flagAt(check)) return;
        Suspect s = suspects.computeIfAbsent(p.getUniqueId(), k -> {
            Suspect n = new Suspect();
            n.id = p.getUniqueId();
            n.firstSeen = System.currentTimeMillis();
            return n;
        });
        s.name = p.getName();
        s.lastSeen = System.currentTimeMillis();
        s.checks.merge(check, vl, Math::max);
        long now = System.currentTimeMillis();
        if (now - st.lastAlert > 15_000) {
            st.lastAlert = now;
            plugin.punishments().notifyStaff(lang().prefixed("anticheat.alert", "player", p.getName(),
                    "check", lang().raw("anticheat.check." + check), "vl", (int) vl, "detail", detail == null ? "" : detail)
                    .clickEvent(ClickEvent.runCommand("/admin case " + p.getName())));
            plugin.staffLog("AntiCheat", "flag " + check, p.getName(), (int) vl + " " + (detail == null ? "" : detail));
            save();
        }
        double limit = punishAt(check);
        if (limit > 0 && vl >= limit && !s.punished && plugin.getConfig().getBoolean("anticheat.auto-punish", true)) punish(p, s, check);
    }

    private void punish(Player p, Suspect s, String check) {
        String id = plugin.getConfig().getString("anticheat.category", "cheating");
        Category category = plugin.categories().get(id);
        if (category == null) {
            plugin.getLogger().warning("Anti-cheat: the category '" + id + "' does not exist, nobody was punished.");
            return;
        }
        Category.Step step = plugin.punishments().nextStep(p.getUniqueId(), category);
        if (step == null) return;
        s.punished = true;
        save();
        String reason = category.name + " (" + lang().raw("anticheat.check." + check) + ")";
        String error = plugin.punishments().execute(p.getUniqueId(), p.getName(), step.type, step.durationMs, reason, "AntiCheat", category.id, null, null);
        if (error == null) {
            plugin.punishments().notifyStaff(lang().prefixed("anticheat.punished", "player", s.name, "check", lang().raw("anticheat.check." + check),
                    "punishment", plugin.punishments().describe(step.type, step.durationMs)));
        }
    }

    private State state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new State());
    }

    /** Who is never checked: creative, spectators, the team at work, frozen or punished players, and the bypass permission. */
    private boolean exempt(Player p) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return true;
        if (p.hasPermission("staffcore.anticheat.bypass")) return true;
        if (plugin.tools() != null && plugin.tools().inStaffMode(p.getUniqueId())) return true;
        if (plugin.punishments().isVanished(p.getUniqueId())) return true;
        if (plugin.admin().isRestrained(p.getUniqueId())) return true;
        if (plugin.prison() != null && plugin.prison().isJailed(p.getUniqueId())) return true;
        return plugin.punishments().isPunished(p.getUniqueId());
    }

    private void exemptFor(Player p, long ms) {
        State st = state(p);
        st.exemptUntil = Math.max(st.exemptUntil, System.currentTimeMillis() + ms);
        st.hoverTicks = 0;
        st.riseTicks = 0;
        st.fallStartY = Double.NaN;
        st.moves.clear();
    }

    // ---------------------------------------------------------------- movement, sampled every tick

    public void start() {
        load();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        save();
    }

    /** Anything the player can stand on: also lily pads, lanterns, chains and the like, not only full blocks. */
    private static boolean solid(Block b) {
        Material m = b.getType();
        if (m == Material.WATER || m == Material.LAVA) return true;
        try {
            return !b.isPassable();
        } catch (UnsupportedOperationException e) {
            return m.isSolid();
        }
    }

    /** Standing on something, judged by the server from the blocks under the feet, not by what the client says. */
    private static boolean grounded(Player p) {
        Location l = p.getLocation();
        double[] off = {-0.3, 0.3};
        for (double dx : off) {
            for (double dz : off) {
                for (double dy : new double[]{-0.05, -0.55}) {
                    Block b = l.getWorld().getBlockAt((int) Math.floor(l.getX() + dx), (int) Math.floor(l.getY() + dy), (int) Math.floor(l.getZ() + dz));
                    if (solid(b) || climbable(b.getType())) return true;
                }
            }
        }
        return false;
    }

    private static boolean climbable(Material m) {
        String n = m.name();
        return m == Material.LADDER || m == Material.VINE || m == Material.SCAFFOLDING || m == Material.COBWEB
                || m == Material.POWDER_SNOW || n.contains("VINES") || n.equals("CAVE_VINES_PLANT") || m == Material.HONEY_BLOCK
                || m == Material.SWEET_BERRY_BUSH || m == Material.BUBBLE_COLUMN || m == Material.WATER || m == Material.LAVA;
    }

    /** Water, ladders, cobwebs, slime or beds around: movement is not normal there. */
    private static boolean special(Player p) {
        Location l = p.getLocation();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    Material m = l.getWorld().getBlockAt(l.getBlockX() + dx, l.getBlockY() + dy, l.getBlockZ() + dz).getType();
                    if (climbable(m) || m == Material.SLIME_BLOCK || m.name().endsWith("_BED") || m.name().contains("TRAPDOOR")
                            || m == Material.HONEY_BLOCK || m == Material.HAY_BLOCK) return true;
                    if (l.getWorld().getBlockAt(l.getBlockX() + dx, l.getBlockY() + dy, l.getBlockZ() + dz).getBlockData()
                            instanceof org.bukkit.block.data.Waterlogged w && w.isWaterlogged()) return true;
                }
            }
        }
        return false;
    }

    private static boolean onIce(Player p) {
        Location l = p.getLocation();
        for (int dy = 1; dy <= 2; dy++) {
            Material m = l.getWorld().getBlockAt(l.getBlockX(), l.getBlockY() - dy, l.getBlockZ()).getType();
            if (m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE || m == Material.FROSTED_ICE || m == Material.SOUL_SAND) return true;
        }
        return false;
    }

    private static int amplifier(Player p, PotionEffectType type) {
        var e = p.getPotionEffect(type);
        return e == null ? -1 : e.getAmplifier();
    }

    private void tick() {
        if (!enabled()) return;
        ticks++;
        long now = System.currentTimeMillis();
        boolean laggy = ticks % 20 == 0 && laggy();
        for (Player p : Bukkit.getOnlinePlayers()) {
            State st = state(p);
            Location loc = p.getLocation();
            Location last = st.last;
            st.last = loc;
            if (last == null || last.getWorld() != loc.getWorld()) continue;
            boolean skip = exempt(p) || now < st.exemptUntil || p.isFlying() || p.getAllowFlight() || p.isGliding() || p.isRiptiding()
                    || p.isInsideVehicle() || p.isDead() || ping(p) > plugin.getConfig().getInt("anticheat.max-ping", 300) || laggy;
            if (skip) {
                st.hoverTicks = 0;
                st.riseTicks = 0;
                st.fallStartY = Double.NaN;
                continue;
            }
            double dy = loc.getY() - last.getY();
            double horizontal = Math.hypot(loc.getX() - last.getX(), loc.getZ() - last.getZ());
            if (horizontal > 8 || Math.abs(dy) > 8) {
                // a teleport the server did not tell us about
                exemptFor(p, 1000);
                continue;
            }
            boolean ground = grounded(p);
            boolean special = special(p);
            if (onIce(p)) st.iceUntil = now + 1500;

            // flying: hanging in the air, or rising without anything carrying the player
            if (!ground && !special && amplifier(p, PotionEffectType.LEVITATION) < 0 && amplifier(p, PotionEffectType.SLOW_FALLING) < 0) {
                if (Math.abs(dy) < 0.03) st.hoverTicks++;
                else st.hoverTicks = 0;
                if (dy > 0.01) {
                    if (st.riseTicks == 0) st.riseStartY = last.getY();
                    st.riseTicks++;
                } else st.riseTicks = 0;
                if (st.hoverTicks > 40 && st.hoverTicks % 20 == 1) violation(p, "fly", 2, lang().raw("anticheat.detail.hover"));
                int jump = amplifier(p, PotionEffectType.JUMP_BOOST);
                if (jump < 0 && st.riseTicks > 14 && loc.getY() - st.riseStartY > 2.5 && st.riseTicks % 10 == 5) {
                    violation(p, "fly", 3, lang().raw("anticheat.detail.rise"));
                }
            } else {
                st.hoverTicks = 0;
                st.riseTicks = 0;
            }

            // no fall: fell far, landed on a normal block, took no damage
            if (!ground) {
                if (Double.isNaN(st.fallStartY) || loc.getY() > st.fallStartY) st.fallStartY = loc.getY();
            } else if (!st.wasOnGround && !Double.isNaN(st.fallStartY)) {
                double fell = st.fallStartY - loc.getY();
                Boolean fallDamage = loc.getWorld().getGameRuleValue(org.bukkit.GameRule.FALL_DAMAGE);
                if (fell > 6 && !special && amplifier(p, PotionEffectType.SLOW_FALLING) < 0 && !Boolean.FALSE.equals(fallDamage)) {
                    st.landedAt = now;
                    st.landedFall = fell;
                    st.fallDamaged = false;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!st.fallDamaged && st.landedAt == now && p.isOnline() && !p.isDead()) {
                            violation(p, "nofall", 2, String.format(java.util.Locale.ROOT, "%.1f", st.landedFall));
                        }
                    }, 20L);
                }
                st.fallStartY = Double.NaN;
            }
            st.wasOnGround = ground;

            // speed: distance per second against what is possible with effects, ice and so on
            st.moves.addLast(new double[]{now, horizontal});
            while (!st.moves.isEmpty() && now - st.moves.peekFirst()[0] > 1000) st.moves.removeFirst();
            if (ticks % 20 == 0 && st.moves.size() >= 15) {
                double second = 0;
                for (double[] m : st.moves) second += m[1];
                double limit = plugin.getConfig().getDouble("anticheat.speed-limit", 9.5);
                int speed = amplifier(p, PotionEffectType.SPEED);
                if (speed >= 0) limit *= 1 + 0.2 * (speed + 1);
                if (now < st.iceUntil) limit *= 2.6;
                if (special) limit *= 1.6;
                if (amplifier(p, PotionEffectType.DOLPHINS_GRACE) >= 0) limit *= 2.5;
                if (second > limit) {
                    st.speedStrikes++;
                    if (st.speedStrikes >= 2) violation(p, "speed", second > limit * 1.5 ? 3 : 1.5,
                            String.format(java.util.Locale.ROOT, "%.1f b/s", second));
                } else st.speedStrikes = 0;
            }
        }
        // points fade: one tenth every ten seconds
        if (ticks % 200 == 0) {
            for (State st : states.values()) st.vl.replaceAll((k, v) -> v * 0.9 - 0.2);
            for (State st : states.values()) st.vl.values().removeIf(v -> v <= 0);
        }
    }

    // ---------------------------------------------------------------- things that excuse odd movement

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        exemptFor(e.getPlayer(), 3000);
        state(e.getPlayer()).last = e.getTo();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        exemptFor(e.getPlayer(), 2500);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        exemptFor(e.getPlayer(), 3000);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
    }

    /** Fall damage counts even when god mode or another plugin cancels it: the client did report the fall. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        State st = state(p);
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL) st.fallDamaged = true;
        else if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) exemptFor(p, 1500);
    }

    // ---------------------------------------------------------------- fighting

    private static double interactionRange(Player p) {
        try {
            Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("entity_interaction_range"));
            if (a != null) {
                AttributeInstance i = p.getAttribute(a);
                if (i != null) return i.getValue();
            }
        } catch (RuntimeException | NoSuchFieldError ignored) {
            // older API
        }
        return 3.0;
    }

    private static double knockbackResistance(Player p) {
        try {
            Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("knockback_resistance"));
            if (a != null) {
                AttributeInstance i = p.getAttribute(a);
                if (i != null) return i.getValue();
            }
        } catch (RuntimeException | NoSuchFieldError ignored) {
            // older API
        }
        return 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player attacker && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK && !exempt(attacker)) {
            Entity target = e.getEntity();
            BoundingBox box = target.getBoundingBox();
            Location eye = attacker.getEyeLocation();
            Vector closest = new Vector(Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX())),
                    Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY())),
                    Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ())));
            double distance = closest.distance(eye.toVector());
            // a little room for lag: the target may have moved on the attacker's screen
            double limit = interactionRange(attacker) + 0.6 + Math.min(1.2, ping(attacker) / 150.0);
            if (distance > limit) violation(attacker, "reach", distance > limit + 1 ? 2.5 : 1, String.format(java.util.Locale.ROOT, "%.2f", distance));
            // hitting what is behind you
            Vector toTarget = box.getCenter().subtract(eye.toVector());
            double angle = Math.toDegrees(eye.getDirection().angle(toTarget));
            if (distance > 1.5 && angle > 100) violation(attacker, "aura", 1.5, String.format(java.util.Locale.ROOT, "%.0f°", angle));
            // two different targets in the same moment
            State st = state(attacker);
            long now = System.currentTimeMillis();
            if (st.lastTarget != null && !st.lastTarget.equals(target.getUniqueId()) && now - st.lastHit < 40) {
                violation(attacker, "aura", 0.5, lang().raw("anticheat.detail.multi"));
            }
            st.lastTarget = target.getUniqueId();
            st.lastHit = now;
        }
        // knockback: the victim should move away a little
        if (e.getEntity() instanceof Player victim && (e.getDamager() instanceof LivingEntity) && !exempt(victim)
                && !victim.isBlocking() && knockbackResistance(victim) < 0.3 && ping(victim) < 250 && !victim.isInsideVehicle()) {
            Location start = victim.getLocation();
            Vector push = start.toVector().subtract(e.getDamager().getLocation().toVector()).setY(0);
            if (push.lengthSquared() < 0.01) return;
            push.normalize();
            Block ahead = start.clone().add(push.clone().multiply(0.8)).getBlock();
            if (ahead.getType().isSolid() || ahead.getRelative(0, 1, 0).getType().isSolid() || special(victim)) return;
            State st = state(victim);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!victim.isOnline() || victim.getWorld() != start.getWorld()) return;
                double moved = Math.hypot(victim.getLocation().getX() - start.getX(), victim.getLocation().getZ() - start.getZ());
                if (moved < 0.1) {
                    if (++st.kbLow >= 4) violation(victim, "velocity", 1, String.format(java.util.Locale.ROOT, "%.2f", moved));
                } else st.kbLow = 0;
            }, 6L);
        }
    }

    /** Clicks per second; swinging while breaking blocks does not count. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        State st = state(p);
        long now = System.currentTimeMillis();
        if (now < st.diggingUntil) return;
        Block looking = null;
        try {
            looking = p.getTargetBlockExact(5);
        } catch (RuntimeException ignored) {
            // no target
        }
        if (looking != null && !looking.getType().isAir()) return;
        st.swings.addLast(now);
        while (!st.swings.isEmpty() && now - st.swings.peekFirst() > 1000) st.swings.removeFirst();
        int cps = st.swings.size();
        if (cps > plugin.getConfig().getInt("anticheat.max-cps", 22)) {
            if (++st.cpsStrikes % 20 == 0) violation(p, "autoclick", 1, cps + " CPS");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDig(BlockDamageEvent e) {
        state(e.getPlayer()).diggingUntil = System.currentTimeMillis() + 600;
    }

    // ---------------------------------------------------------------- x-ray by ore ratio

    private static boolean filler(Material m) {
        return m == Material.STONE || m == Material.DEEPSLATE || m == Material.TUFF || m == Material.NETHERRACK || m == Material.GRANITE
                || m == Material.DIORITE || m == Material.ANDESITE || m == Material.BASALT || m == Material.BLACKSTONE;
    }

    private static boolean rare(Material m) {
        return m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE || m == Material.ANCIENT_DEBRIS;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMine(BlockBreakEvent e) {
        Player p = e.getPlayer();
        State st = state(p);
        long now = System.currentTimeMillis();
        if (now - st.miningWindowStart > 30 * 60_000L) {
            st.miningWindowStart = now;
            st.stone = 0;
            st.ores = 0;
        }
        Material m = e.getBlock().getType();
        if (filler(m)) st.stone++;
        else if (rare(m)) {
            st.ores++;
            double ratio = st.ores / (double) Math.max(1, st.stone + st.ores);
            if (st.ores >= plugin.getConfig().getInt("anticheat.xray-ores", 10) && ratio > plugin.getConfig().getDouble("anticheat.xray-ratio", 0.06)) {
                violation(p, "xray", 1, st.ores + "/" + (st.stone + st.ores));
            }
        }
    }

    // ---------------------------------------------------------------- /anticheat

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("clear")) {
            UUID id = null;
            for (Suspect s : suspects.values()) if (s.name.equalsIgnoreCase(args[1])) id = s.id;
            if (id == null) lang().send(sender, "anticheat.unknown", "player", args[1]);
            else {
                dismiss(id, sender.getName());
                lang().send(sender, "anticheat.cleared", "player", args[1]);
            }
            return true;
        }
        if (sender instanceof Player p && args.length == 0) {
            plugin.adminPanel().inbox(p, 0);
            return true;
        }
        lang().send(sender, "anticheat.header", "count", suspects.size());
        for (Suspect s : suspects()) {
            StringBuilder c = new StringBuilder();
            for (Map.Entry<String, Double> e : s.checks.entrySet()) {
                if (c.length() > 0) c.append(", ");
                c.append(lang().raw("anticheat.check." + e.getKey())).append(' ').append((int) Math.round(e.getValue()));
            }
            sender.sendMessage(lang().msg("anticheat.line", "player", s.name, "score", (int) Math.round(s.score()), "checks", c.toString()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1 && "clear".startsWith(args[0].toLowerCase())) out.add("clear");
        if (args.length == 2) for (Suspect s : suspects.values()) if (s.name.toLowerCase().startsWith(args[1].toLowerCase())) out.add(s.name);
        return out;
    }

    // ---------------------------------------------------------------- tests and tools

    /** The current points of a check, for the menus and tests. */
    public double points(UUID id, String check) {
        State st = states.get(id);
        return st == null ? 0 : st.vl.getOrDefault(check, 0.0);
    }
}
