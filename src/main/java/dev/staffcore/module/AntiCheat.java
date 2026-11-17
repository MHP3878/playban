package dev.staffcore.module;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import dev.staffcore.TimeUtil;
import dev.staffcore.model.Category;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The anti-cheat with one point account per player. Every finding gives points by how clear it is:
 * a totem put back one tick after it popped gives 4, three ticks give 1; flying gives 20 at once.
 * Whoever reaches the limit (25) is caught: an animation for everybody around, then the next step of
 * the ladder (PlayBan 5 minutes, PlayBan 1 hour, then the cheating category). Clear findings come
 * with a big warning and a few seconds of grace, so somebody who is merely slow to land gets a chance.
 * Points fade over time. Marked players are watched more closely: their points count one and a half.
 */
public final class AntiCheat implements Listener, TabExecutor {
    public static final List<String> CHECKS = List.of("fly", "speed", "reach", "aura", "autoclick", "nofall", "velocity", "xray",
            "timer", "step", "jesus", "fastbreak", "nuker", "scaffold", "noslow", "badpackets", "autototem", "anchor", "crystal", "bot");
    /** Checks that alone never catch anybody: they can only mark. */
    private static final Set<String> SOFT = Set.of("xray", "bot");

    public static final class Suspect {
        public UUID id;
        public String name;
        public final Map<String, Double> checks = new LinkedHashMap<>();
        public long firstSeen;
        public long lastSeen;
        public String handler;
        public boolean punished;
        public boolean watched;
        public int caught;

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
        boolean fallDamaged;
        final Deque<double[]> moves = new ArrayDeque<>();
        int speedStrikes;
        long iceUntil;
        final Deque<Long> swings = new ArrayDeque<>();
        long diggingUntil;
        long lastHit;
        UUID lastTarget;
        int kbLow;
        int stone, ores;
        long miningWindowStart;
        long lastAlert;
        final Deque<Long> movePackets = new ArrayDeque<>();
        int timerStrikes;
        int waterWalk;
        Block digging;
        long digStart;
        final Deque<Long> breaks = new ArrayDeque<>();
        final Deque<Long> places = new ArrayDeque<>();
        int usingTicks;
        float lastYaw;
        long lastBigTurn;
        long totemPopAt = -1;
        long anchorPlacedAt;
        Block anchor;
        long anchorChargedAt;
        long crystalPlacedAt;
        long joinedAt;
        boolean brandChecked;
        final Map<String, Long> grace = new HashMap<>();
        long lastDecay = System.currentTimeMillis();
        long lastCpsSecond;
    }

    private final StaffCorePlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Suspect> suspects = new LinkedHashMap<>();
    /** the point account: player -> check -> points */
    private final Map<UUID, Map<String, Double>> points = new HashMap<>();
    private final Set<UUID> watched = new HashSet<>();
    private final Map<UUID, Integer> caughtCount = new HashMap<>();
    private final Set<UUID> catching = new HashSet<>();
    private final CatchAnimation animation;
    private BukkitTask task;
    private int ticks;
    // bots
    private final Deque<Long> newJoins = new ArrayDeque<>();
    private volatile long antiBotUntil;
    private final Map<String, List<Object[]>> recentChat = new ConcurrentHashMap<>();

    public AntiCheat(StaffCorePlugin plugin) {
        this.plugin = plugin;
        this.animation = new CatchAnimation(plugin);
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

    public double limit() {
        return plugin.getConfig().getDouble("anticheat.limit", 25);
    }

    // ---------------------------------------------------------------- suspects and the watch list

    public List<Suspect> suspects() {
        List<Suspect> list = new ArrayList<>(suspects.values());
        list.sort((a, b) -> Double.compare(b.score(), a.score()));
        return list;
    }

    public Suspect suspect(UUID id) {
        return suspects.get(id);
    }

    public boolean watched(UUID id) {
        return watched.contains(id);
    }

    public void setWatched(UUID id, String name, boolean value, String by) {
        if (value) watched.add(id);
        else watched.remove(id);
        Suspect s = suspects.get(id);
        if (s != null) s.watched = value;
        else if (value) {
            s = new Suspect();
            s.id = id;
            s.name = name;
            s.watched = true;
            s.firstSeen = s.lastSeen = System.currentTimeMillis();
            suspects.put(id, s);
        }
        save();
        if (by != null) plugin.staffLog(by, value ? "ac-watch" : "ac-unwatch", name, null);
    }

    public void dismiss(UUID id, String by) {
        Suspect s = suspects.remove(id);
        points.remove(id);
        watched.remove(id);
        save();
        if (s != null && by != null) plugin.staffLog(by, "ac-dismiss", s.name, null);
    }

    public void handle(UUID id, String by) {
        Suspect s = suspects.get(id);
        if (s == null) return;
        s.handler = by;
        save();
        plugin.staffLog(by, "ac-case", s.name, String.format(Locale.ROOT, "%.0f", s.score()));
    }

    /** Points a player has right now, all checks together. */
    public double total(UUID id) {
        double t = 0;
        double soft = 0;
        for (Map.Entry<String, Double> e : points.getOrDefault(id, Map.of()).entrySet()) {
            if (SOFT.contains(e.getKey())) soft += e.getValue();
            else t += e.getValue();
        }
        // x-ray and bot signs mark, but alone they never reach the limit
        return t + Math.min(soft, limit() - 1);
    }

    /** The points of one check, for menus and tests. */
    public double points(UUID id, String check) {
        return points.getOrDefault(id, Map.of()).getOrDefault(check, 0.0);
    }

    public void load() {
        suspects.clear();
        watched.clear();
        caughtCount.clear();
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
                s.watched = c.getBoolean("watched");
                s.caught = c.getInt("caught");
                ConfigurationSection ch = c.getConfigurationSection("checks");
                if (ch != null) for (String id : ch.getKeys(false)) s.checks.put(id, ch.getDouble(id));
                suspects.put(s.id, s);
                if (s.watched) watched.add(s.id);
                if (s.caught > 0) caughtCount.put(s.id, s.caught);
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
            cfg.set(p + "watched", s.watched);
            cfg.set(p + "caught", caughtCount.getOrDefault(s.id, 0));
            for (Map.Entry<String, Double> c : s.checks.entrySet()) cfg.set(p + "checks." + c.getKey(), Math.round(c.getValue() * 10) / 10.0);
        }
        try {
            cfg.save(file());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save anticheat.yml: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- the point account

    private boolean checkOn(String check) {
        return plugin.getConfig().getBoolean("anticheat.checks." + check + ".enabled", true);
    }

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

    /** Kept for older callers: a finding worth this many points. */
    public void violation(Player p, String check, double amount, String detail) {
        flag(p, check, amount, detail);
    }

    /**
     * Gives points for a finding. Big findings (10 and more) warn the player loudly and give a few
     * seconds of grace for the same check, so one slow landing is not counted twice.
     */
    public void flag(Player p, String check, double base, String detail) {
        if (!enabled() || !checkOn(check) || exempt(p) || laggy() || base <= 0) return;
        if (catching.contains(p.getUniqueId())) return;
        State st = state(p);
        long now = System.currentTimeMillis();
        Long graceUntil = st.grace.get(check);
        if (graceUntil != null && now < graceUntil) return;
        double amount = base * plugin.getConfig().getDouble("anticheat.checks." + check + ".weight", 1.0);
        if (watched.contains(p.getUniqueId())) amount *= plugin.getConfig().getDouble("anticheat.watch-multiplier", 1.5);
        Map<String, Double> account = points.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        account.merge(check, amount, Double::sum);
        double total = total(p.getUniqueId());
        String checkName = lang().raw("anticheat.check." + check);

        if (base >= 10) {
            st.grace.put(check, now + plugin.getConfig().getLong("anticheat.grace-seconds", 6) * 1000L);
            p.showTitle(Title.title(lang().msg("anticheat.warn-title"), lang().msg("anticheat.warn-subtitle", "check", checkName),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4), Duration.ofMillis(800))));
            p.sendMessage(lang().prefixed("anticheat.warn-chat", "check", checkName, "points", (int) Math.round(total), "limit", (int) limit()));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
            p.playSound(p.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.6f, 1.2f);
        }

        // marking: from a few points on the team sees the player in /admin; marked players are watched
        double markAt = plugin.getConfig().getDouble("anticheat.mark-at", 8);
        Suspect s = suspects.get(p.getUniqueId());
        if (s == null && total >= markAt) {
            s = new Suspect();
            s.id = p.getUniqueId();
            s.firstSeen = now;
            suspects.put(s.id, s);
            if (plugin.getConfig().getBoolean("anticheat.auto-watch", true)) {
                s.watched = true;
                watched.add(s.id);
            }
        }
        if (s != null) {
            s.name = p.getName();
            s.lastSeen = now;
            s.checks.merge(check, amount, Double::sum);
        }
        boolean isWatched = watched.contains(p.getUniqueId());
        long alertGap = isWatched ? 2000 : 12_000;
        if (s != null && (now - st.lastAlert > alertGap || base >= 10)) {
            st.lastAlert = now;
            plugin.punishments().notifyStaff(lang().prefixed(isWatched ? "anticheat.alert-watched" : "anticheat.alert", "player", p.getName(),
                    "check", checkName, "points", String.format(Locale.ROOT, "%.1f", amount), "total", (int) Math.round(total),
                    "limit", (int) limit(), "detail", detail == null ? "" : detail)
                    .clickEvent(ClickEvent.runCommand("/admin case " + p.getName())));
            save();
        }
        plugin.staffLog("AntiCheat", check, p.getName(), String.format(Locale.ROOT, "+%.1f = %.0f %s", amount, total, detail == null ? "" : detail));
        if (total >= limit() && plugin.getConfig().getBoolean("anticheat.auto-punish", true)) caught(p);
    }

    /** Over the limit: the animation for everybody around, then the next step of the ladder. */
    private void caught(Player p) {
        if (!catching.add(p.getUniqueId())) return;
        Map<String, Double> account = points.getOrDefault(p.getUniqueId(), Map.of());
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(account.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        StringBuilder why = new StringBuilder();
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            if (why.length() > 0) why.append(", ");
            why.append(lang().raw("anticheat.check." + sorted.get(i).getKey()));
        }
        String reason = lang().raw("anticheat.reason").replace("{checks}", why.toString());
        int n = caughtCount.merge(p.getUniqueId(), 1, Integer::sum);
        List<String> ladder = plugin.getConfig().getStringList("anticheat.ladder");
        if (ladder.isEmpty()) ladder = List.of("PLAYBAN 5m", "PLAYBAN 1h", "CATEGORY cheating");
        String step = ladder.get(Math.min(n, ladder.size()) - 1);
        UUID id = p.getUniqueId();
        String name = p.getName();
        plugin.punishments().notifyStaff(lang().prefixed("anticheat.caught", "player", name, "checks", why.toString(), "step", step));
        Runnable punish = () -> {
            catching.remove(id);
            points.remove(id);
            Suspect s = suspects.get(id);
            if (s != null) {
                s.punished = true;
                s.caught = n;
            }
            save();
            apply(id, name, step, reason);
        };
        animation.play(p, punish);
    }

    private void apply(UUID id, String name, String step, String reason) {
        String[] parts = step.trim().split("\\s+", 2);
        String kind = parts[0].toUpperCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1] : "";
        switch (kind) {
            case "KICK" -> {
                Player online = Bukkit.getPlayer(id);
                if (online != null) online.kick(lang().msg("anticheat.kicked", "reason", reason));
            }
            case "CATEGORY" -> {
                Category category = plugin.categories().get(arg.isEmpty() ? "cheating" : arg);
                if (category == null) {
                    plugin.getLogger().warning("Anti-cheat: the category '" + arg + "' does not exist.");
                    return;
                }
                Category.Step next = plugin.punishments().nextStep(id, category);
                if (next != null) plugin.punishments().execute(id, name, next.type, next.durationMs, reason, "AntiCheat", category.id, null, null);
            }
            default -> {
                // PLAYBAN 5m, BAN 1d, SPECBAN 1h, MUTE 10m
                long ms = arg.isEmpty() || arg.equalsIgnoreCase("perm") ? 0 : TimeUtil.parse(arg);
                if (ms < 0) ms = 5 * 60_000L;
                if (kind.equals("PLAYBAN") && ms <= 0) ms = 5 * 60_000L;
                String error = plugin.punishments().execute(id, name, kind, ms, reason, "AntiCheat", null, null, null);
                Player online = Bukkit.getPlayer(id);
                if (error == null && online != null && kind.equals("PLAYBAN")) {
                    lang().send(online, "anticheat.playbanned", "time", TimeUtil.format(ms));
                }
            }
        }
    }

    private State state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new State());
    }

    private boolean exempt(Player p) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return true;
        if (p.hasPermission("staffcore.anticheat.bypass")) return true;
        if (plugin.tools() != null && plugin.tools().inStaffMode(p.getUniqueId())) return true;
        if (plugin.punishments().isVanished(p.getUniqueId())) return true;
        if (plugin.admin().isRestrained(p.getUniqueId())) return true;
        if (plugin.prison() != null && plugin.prison().isJailed(p.getUniqueId())) return true;
        if (animation.playing(p.getUniqueId())) return true;
        return plugin.punishments().isPunished(p.getUniqueId());
    }

    private void exemptFor(Player p, long ms) {
        State st = state(p);
        st.exemptUntil = Math.max(st.exemptUntil, System.currentTimeMillis() + ms);
        st.hoverTicks = 0;
        st.riseTicks = 0;
        st.fallStartY = Double.NaN;
        st.moves.clear();
        st.waterWalk = 0;
    }

    // ---------------------------------------------------------------- start

    public void start() {
        load();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        save();
    }

    // ---------------------------------------------------------------- block helpers

    private static boolean solid(Block b) {
        Material m = b.getType();
        if (m == Material.WATER || m == Material.LAVA) return true;
        try {
            return !b.isPassable();
        } catch (RuntimeException e) {
            return m.isSolid();
        }
    }

    private static boolean grounded(Player p) {
        Location l = p.getLocation();
        for (double dx : new double[]{-0.3, 0.3}) {
            for (double dz : new double[]{-0.3, 0.3}) {
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
                || m == Material.POWDER_SNOW || n.contains("VINES") || m == Material.HONEY_BLOCK
                || m == Material.SWEET_BERRY_BUSH || m == Material.BUBBLE_COLUMN || m == Material.WATER || m == Material.LAVA;
    }

    private static boolean special(Player p) {
        Location l = p.getLocation();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    Block b = l.getWorld().getBlockAt(l.getBlockX() + dx, l.getBlockY() + dy, l.getBlockZ() + dz);
                    Material m = b.getType();
                    if (climbable(m) || m == Material.SLIME_BLOCK || m.name().endsWith("_BED") || m.name().contains("TRAPDOOR")
                            || m == Material.HONEY_BLOCK || m == Material.HAY_BLOCK || m.name().contains("SHULKER_BOX") || m == Material.PISTON
                            || m == Material.STICKY_PISTON || m == Material.MOVING_PISTON) return true;
                    if (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged w && w.isWaterlogged()) return true;
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

    private static double attribute(Player p, String key, double fallback) {
        try {
            Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
            if (a != null) {
                AttributeInstance i = p.getAttribute(a);
                if (i != null) return i.getValue();
            }
        } catch (RuntimeException | NoSuchFieldError ignored) {
            // older API
        }
        return fallback;
    }

    private boolean frostWalker(Player p) {
        ItemStack boots = p.getInventory().getBoots();
        if (boots == null) return false;
        for (var e : boots.getEnchantments().keySet()) if (e.getKey().getKey().equals("frost_walker")) return true;
        return false;
    }

    // ---------------------------------------------------------------- every tick: movement

    private void tick() {
        if (!enabled()) return;
        ticks++;
        long now = System.currentTimeMillis();
        boolean laggy = ticks % 20 == 0 && laggy();
        for (Player p : Bukkit.getOnlinePlayers()) {
            State st = state(p);
            decay(p.getUniqueId(), st, now);
            autoTotem(p, st, now);
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
                st.waterWalk = 0;
                continue;
            }
            double dy = loc.getY() - last.getY();
            double horizontal = Math.hypot(loc.getX() - last.getX(), loc.getZ() - last.getZ());
            if (horizontal > 8 || Math.abs(dy) > 8) {
                exemptFor(p, 1000);
                continue;
            }
            boolean ground = grounded(p);
            boolean special = special(p);
            if (onIce(p)) st.iceUntil = now + 1500;

            // impossible head angles: no real client sends them
            if (Math.abs(loc.getPitch()) > 90.5f) flag(p, "badpackets", 20, String.format(Locale.ROOT, "pitch %.1f", loc.getPitch()));

            // flying
            if (!ground && !special && amplifier(p, PotionEffectType.LEVITATION) < 0 && amplifier(p, PotionEffectType.SLOW_FALLING) < 0) {
                st.hoverTicks = Math.abs(dy) < 0.03 ? st.hoverTicks + 1 : 0;
                if (dy > 0.01) {
                    if (st.riseTicks == 0) st.riseStartY = last.getY();
                    st.riseTicks++;
                } else st.riseTicks = 0;
                if (st.hoverTicks == 30) flag(p, "fly", 20, lang().raw("anticheat.detail.hover"));
                if (st.hoverTicks > 30 && st.hoverTicks % 40 == 0) flag(p, "fly", 20, lang().raw("anticheat.detail.hover"));
                if (amplifier(p, PotionEffectType.JUMP_BOOST) < 0 && st.riseTicks > 14 && loc.getY() - st.riseStartY > 2.5) {
                    flag(p, "fly", 20, lang().raw("anticheat.detail.rise"));
                    st.riseTicks = 0;
                }
            } else {
                st.hoverTicks = 0;
                st.riseTicks = 0;
            }

            // step: more than a slab up in one tick, from ground to ground
            if (st.wasOnGround && ground && dy > 0.62 && dy < 3 && amplifier(p, PotionEffectType.JUMP_BOOST) < 0 && !special
                    && attribute(p, "step_height", 0.6) < 0.7) {
                flag(p, "step", dy >= 1.0 ? 10 : 4, String.format(Locale.ROOT, "%.2f", dy));
            }

            // walking on water
            Block under = loc.clone().add(0, -0.1, 0).getBlock();
            Block body = loc.clone().add(0, 0.2, 0).getBlock();
            // deep water under the feet, air at the body, moving flat: walking on the surface
            if (under.getType() == Material.WATER && under.getRelative(0, -1, 0).getType() == Material.WATER && body.getType().isAir()
                    && horizontal > 0.1 && !frostWalker(p) && Math.abs(dy) < 0.01 && !p.isSwimming()) {
                if (++st.waterWalk == 40) flag(p, "jesus", 15, null);
            } else if (st.waterWalk > 0) st.waterWalk--;

            // no fall
            if (!ground) {
                if (Double.isNaN(st.fallStartY) || loc.getY() > st.fallStartY) st.fallStartY = loc.getY();
            } else if (!st.wasOnGround && !Double.isNaN(st.fallStartY)) {
                double fell = st.fallStartY - loc.getY();
                Boolean fallDamage = loc.getWorld().getGameRuleValue(org.bukkit.GameRule.FALL_DAMAGE);
                if (fell > 6 && !special && amplifier(p, PotionEffectType.SLOW_FALLING) < 0 && !Boolean.FALSE.equals(fallDamage)) {
                    st.landedAt = now;
                    st.fallDamaged = false;
                    double height = fell;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!st.fallDamaged && st.landedAt == now && p.isOnline() && !p.isDead()) {
                            flag(p, "nofall", height > 20 ? 20 : height > 10 ? 12 : 6, String.format(Locale.ROOT, "%.1f", height));
                        }
                    }, 20L);
                }
                st.fallStartY = Double.NaN;
            }
            st.wasOnGround = ground;

            // using an item (eating, bow, shield) slows you right down
            boolean using = false;
            try {
                using = p.isHandRaised();
            } catch (RuntimeException ignored) {
                // older API
            }
            st.usingTicks = using ? st.usingTicks + 1 : 0;

            // speed
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
                double ratio = second / limit;
                if (ratio > 1) {
                    if (++st.speedStrikes >= 2) {
                        flag(p, "speed", ratio > 2 ? 20 : ratio > 1.4 ? 10 : ratio > 1.15 ? 4 : 1.5, String.format(Locale.ROOT, "%.1f b/s", second));
                    }
                } else st.speedStrikes = 0;
                // no slow: full speed while eating or drawing a bow
                if (st.usingTicks > 20 && second > 5.5 * (speed >= 0 ? 1 + 0.2 * (speed + 1) : 1) && now > st.iceUntil && !special) {
                    flag(p, "noslow", second > 7 ? 6 : 3, String.format(Locale.ROOT, "%.1f b/s", second));
                }
            }
            st.lastYaw = loc.getYaw();
        }
        if (ticks % 100 == 0) cleanChat();
    }

    /** Points fade: by default one point a minute. */
    private void decay(UUID id, State st, long now) {
        long every = plugin.getConfig().getLong("anticheat.fade-seconds", 60) * 1000L;
        if (now - st.lastDecay < every) return;
        st.lastDecay = now;
        Map<String, Double> account = points.get(id);
        if (account == null) return;
        double total = total(id);
        if (total <= 0) return;
        for (Map.Entry<String, Double> e : account.entrySet()) e.setValue(Math.max(0, e.getValue() - e.getValue() / total));
        account.values().removeIf(v -> v <= 0.01);
    }

    // ---------------------------------------------------------------- movement packets: timer

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        State st = state(p);
        long now = System.currentTimeMillis();
        st.movePackets.addLast(now);
        while (!st.movePackets.isEmpty() && now - st.movePackets.peekFirst() > 5000) st.movePackets.removeFirst();
        if (st.movePackets.size() > 5 * 20 && now > st.exemptUntil && ping(p) < 200) {
            double rate = st.movePackets.size() / 5.0;
            if (rate > plugin.getConfig().getDouble("anticheat.max-packets", 23)) {
                if (++st.timerStrikes % 20 == 0) flag(p, "timer", rate > 30 ? 15 : rate > 25 ? 6 : 2, String.format(Locale.ROOT, "%.1f/s", rate));
            } else st.timerStrikes = 0;
        }
    }

    // ---------------------------------------------------------------- things that excuse odd movement

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        exemptFor(e.getPlayer(), 3000);
        state(e.getPlayer()).last = e.getTo();
        state(e.getPlayer()).movePackets.clear();
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        State st = state(p);
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL) st.fallDamaged = true;
        else if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) exemptFor(p, 1500);
    }

    // ---------------------------------------------------------------- fighting

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player attacker && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK && !exempt(attacker)) {
            Entity target = e.getEntity();
            State st = state(attacker);
            long now = System.currentTimeMillis();
            BoundingBox box = target.getBoundingBox();
            Location eye = attacker.getEyeLocation();
            Vector closest = new Vector(Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX())),
                    Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY())),
                    Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ())));
            double distance = closest.distance(eye.toVector());
            double limit = attribute(attacker, "entity_interaction_range", 3.0) + 0.6 + Math.min(1.2, ping(attacker) / 150.0);
            double over = distance - limit;
            if (over > 0) flag(attacker, "reach", over < 0.3 ? 1 : over < 0.8 ? 3 : over < 1.5 ? 6 : 12, String.format(Locale.ROOT, "%.2f", distance));

            Vector toTarget = box.getCenter().subtract(eye.toVector());
            double angle = Math.toDegrees(eye.getDirection().angle(toTarget));
            if (distance > 1.5 && angle > 100) flag(attacker, "aura", angle > 140 ? 8 : angle > 120 ? 5 : 2, String.format(Locale.ROOT, "%.0f°", angle));

            if (st.lastTarget != null && !st.lastTarget.equals(target.getUniqueId()) && now - st.lastHit < 40) {
                flag(attacker, "aura", 1, lang().raw("anticheat.detail.multi"));
            }
            // through a wall: both the middle and the top of the target hidden behind blocks
            if (distance > 1.2 && blocked(eye, box.getCenter()) && blocked(eye, new Vector(box.getCenterX(), box.getMaxY() - 0.1, box.getCenterZ()))) {
                flag(attacker, "aura", 3, lang().raw("anticheat.detail.wall"));
            }
            // a big turn straight onto the target right before the hit
            float turn = Math.abs(wrap(attacker.getLocation().getYaw() - st.lastYaw));
            if (turn > 60 && angle < 6) flag(attacker, "aura", turn > 120 ? 3 : 1.5, String.format(Locale.ROOT, "snap %.0f°", turn));
            if (target instanceof EnderCrystal && st.crystalPlacedAt > 0) {
                long dt = now - st.crystalPlacedAt;
                if (dt < 50) flag(attacker, "crystal", 2, dt + "ms");
                else if (dt < 100) flag(attacker, "crystal", 1, dt + "ms");
                st.crystalPlacedAt = 0;
            }
            st.lastTarget = target.getUniqueId();
            st.lastHit = now;
        }
        if (e.getEntity() instanceof Player victim && e.getDamager() instanceof LivingEntity && !exempt(victim)
                && !victim.isBlocking() && attribute(victim, "knockback_resistance", 0) < 0.3 && ping(victim) < 250 && !victim.isInsideVehicle()) {
            Location start = victim.getLocation();
            Vector push = start.toVector().subtract(e.getDamager().getLocation().toVector()).setY(0);
            if (push.lengthSquared() < 0.01) return;
            push.normalize();
            Block ahead = start.clone().add(push.clone().multiply(0.8)).getBlock();
            if (solid(ahead) || solid(ahead.getRelative(0, 1, 0)) || special(victim)) return;
            State st = state(victim);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!victim.isOnline() || victim.getWorld() != start.getWorld()) return;
                double moved = Math.hypot(victim.getLocation().getX() - start.getX(), victim.getLocation().getZ() - start.getZ());
                if (moved < 0.1) {
                    if (++st.kbLow >= 4) flag(victim, "velocity", moved < 0.02 ? 8 : 4, String.format(Locale.ROOT, "%.2f", moved));
                } else st.kbLow = 0;
            }, 6L);
        }
    }

    private static float wrap(float a) {
        a %= 360;
        if (a > 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }

    private static boolean blocked(Location eye, Vector to) {
        Vector dir = to.clone().subtract(eye.toVector());
        double len = dir.length();
        if (len < 0.01) return false;
        try {
            RayTraceResult r = eye.getWorld().rayTraceBlocks(eye, dir.normalize(), len, org.bukkit.FluidCollisionMode.NEVER, true);
            return r != null && r.getHitBlock() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

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
        int max = plugin.getConfig().getInt("anticheat.max-cps", 22);
        // judged once per second of clicking above the limit
        if (cps > max && now / 1000 != st.lastCpsSecond) {
            st.lastCpsSecond = now / 1000;
            flag(p, "autoclick", cps > max + 10 ? 6 : cps > max + 4 ? 3 : 1, cps + " CPS");
        }
    }

    // ---------------------------------------------------------------- blocks: fast break, nuker, scaffold, x-ray

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDig(BlockDamageEvent e) {
        State st = state(e.getPlayer());
        st.diggingUntil = System.currentTimeMillis() + 600;
        st.digging = e.getBlock();
        st.digStart = System.currentTimeMillis();
    }

    private static boolean filler(Material m) {
        return m == Material.STONE || m == Material.DEEPSLATE || m == Material.TUFF || m == Material.NETHERRACK || m == Material.GRANITE
                || m == Material.DIORITE || m == Material.ANDESITE || m == Material.BASALT || m == Material.BLACKSTONE;
    }

    private static boolean rare(Material m) {
        return m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE || m == Material.ANCIENT_DEBRIS;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (exempt(p)) return;
        State st = state(p);
        long now = System.currentTimeMillis();
        Block b = e.getBlock();
        // fast break: faster than the tool, enchantments and haste allow
        if (b.equals(st.digging)) {
            try {
                float perTick = b.getBreakSpeed(p);
                if (perTick > 0 && perTick < 1) {
                    double expected = Math.ceil(1 / perTick) * 50.0;
                    double took = now - st.digStart;
                    double ratio = took / expected;
                    if (expected > 200 && ratio < 0.7) flag(p, "fastbreak", ratio < 0.3 ? 8 : ratio < 0.5 ? 4 : 1.5,
                            String.format(Locale.ROOT, "%.0f/%.0fms", took, expected));
                }
            } catch (RuntimeException | NoSuchMethodError ignored) {
                // older API
            }
        }
        // nuker: many hard blocks per second, or blocks out of reach
        if (b.getType().getHardness() > 0.3f) {
            st.breaks.addLast(now);
            while (!st.breaks.isEmpty() && now - st.breaks.peekFirst() > 1000) st.breaks.removeFirst();
            if (st.breaks.size() > 12) flag(p, "nuker", st.breaks.size() > 20 ? 15 : 5, st.breaks.size() + "/s");
        }
        double far = p.getEyeLocation().toVector().distance(b.getLocation().add(0.5, 0.5, 0.5).toVector());
        if (far > attribute(p, "block_interaction_range", 4.5) + 1.5) flag(p, "nuker", far > 8 ? 6 : 2, String.format(Locale.ROOT, "%.1f", far));
        // x-ray by the share of rare ores
        if (now - st.miningWindowStart > 30 * 60_000L) {
            st.miningWindowStart = now;
            st.stone = 0;
            st.ores = 0;
        }
        Material m = b.getType();
        if (filler(m)) st.stone++;
        else if (rare(m)) {
            st.ores++;
            double ratio = st.ores / (double) Math.max(1, st.stone + st.ores);
            if (st.ores >= plugin.getConfig().getInt("anticheat.xray-ores", 10) && ratio > plugin.getConfig().getDouble("anticheat.xray-ratio", 0.06)) {
                flag(p, "xray", ratio > 0.2 ? 4 : 2, st.ores + "/" + (st.stone + st.ores));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (exempt(p)) return;
        State st = state(p);
        long now = System.currentTimeMillis();
        st.places.addLast(now);
        while (!st.places.isEmpty() && now - st.places.peekFirst() > 1000) st.places.removeFirst();
        if (st.places.size() > 12) flag(p, "scaffold", st.places.size() > 20 ? 10 : 3, st.places.size() + "/s");
        // bridging under your own feet without looking down, at walking speed
        Location l = p.getLocation();
        Block placed = e.getBlockPlaced();
        boolean underFeet = placed.getY() < l.getY() - 0.5 && Math.abs(placed.getX() + 0.5 - l.getX()) < 1.6 && Math.abs(placed.getZ() + 0.5 - l.getZ()) < 1.6;
        double speed = 0;
        for (double[] mv : st.moves) speed += mv[1];
        if (underFeet && l.getPitch() < 35 && speed > 3.5) flag(p, "scaffold", l.getPitch() < 10 ? 5 : 2, String.format(Locale.ROOT, "pitch %.0f", l.getPitch()));
        // respawn anchors: place, charge, blow up, faster than hands can
        if (placed.getType() == Material.RESPAWN_ANCHOR) {
            st.anchor = placed;
            st.anchorPlacedAt = now;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        State st = state(p);
        long now = System.currentTimeMillis();
        Block b = e.getClickedBlock();
        ItemStack hand = e.getItem();
        if (b.getType() == Material.RESPAWN_ANCHOR && b.equals(st.anchor)) {
            if (hand != null && hand.getType() == Material.GLOWSTONE && st.anchorChargedAt == 0) {
                st.anchorChargedAt = now;
                long dt = now - st.anchorPlacedAt;
                if (dt < 50) flag(p, "anchor", 3, "charge " + dt + "ms");
                else if (dt < 100) flag(p, "anchor", 1, "charge " + dt + "ms");
            } else if (st.anchorChargedAt > 0 && (hand == null || hand.getType() != Material.GLOWSTONE)) {
                long dt = now - st.anchorChargedAt;
                if (dt < 50) flag(p, "anchor", 3, "explode " + dt + "ms");
                else if (dt < 100) flag(p, "anchor", 1, "explode " + dt + "ms");
                st.anchor = null;
                st.anchorChargedAt = 0;
            }
        }
        if (hand != null && hand.getType() == Material.END_CRYSTAL && (b.getType() == Material.OBSIDIAN || b.getType() == Material.BEDROCK)) {
            st.crystalPlacedAt = now;
        }
    }

    // ---------------------------------------------------------------- totems

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotem(EntityResurrectEvent e) {
        if (e.getEntity() instanceof Player p) state(p).totemPopAt = ticks;
    }

    /** After a totem pops: how many ticks until the next one is in the off hand. A hand needs a few. */
    private void autoTotem(Player p, State st, long now) {
        if (st.totemPopAt < 0) return;
        long dt = ticks - st.totemPopAt;
        if (dt > 6) {
            st.totemPopAt = -1;
            return;
        }
        if (dt == 0) return;
        ItemStack off = p.getInventory().getItemInOffHand();
        if (off != null && off.getType() == Material.TOTEM_OF_UNDYING) {
            st.totemPopAt = -1;
            // the inventory was closed, so the totem came by a key press or by a cheat
            double pts = dt <= 1 ? 4 : dt == 2 ? 2 : dt == 3 ? 1 : 0;
            if (pts > 0) flag(p, "autototem", pts, (dt * 50) + "ms");
        }
    }

    // ---------------------------------------------------------------- bots

    /** Many new accounts at once: for two minutes only players who were here before may join. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (!enabled() || !checkOn("bot") || e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        boolean known = plugin.store().names.containsKey(e.getUniqueId());
        if (known) return;
        long now = System.currentTimeMillis();
        synchronized (newJoins) {
            newJoins.addLast(now);
            while (!newJoins.isEmpty() && now - newJoins.peekFirst() > 10_000) newJoins.removeFirst();
            if (newJoins.size() > plugin.getConfig().getInt("anticheat.bot-joins", 6) && now > antiBotUntil) {
                antiBotUntil = now + 120_000;
                Bukkit.getScheduler().runTask(plugin, () -> plugin.punishments().notifyStaff(lang().prefixed("anticheat.antibot-on")));
            }
        }
        if (now < antiBotUntil) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, lang().msg("anticheat.antibot-kick"));
        }
    }

    public boolean antiBotActive() {
        return System.currentTimeMillis() < antiBotUntil;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        State st = state(p);
        st.joinedAt = System.currentTimeMillis();
        exemptFor(p, 3000);
        // somebody who was caught before is watched again
        Suspect s = suspects.get(p.getUniqueId());
        if (s != null && s.watched) watched.add(p.getUniqueId());
        if (s != null && s.watched) {
            plugin.punishments().notifyStaff(lang().prefixed("anticheat.watched-join", "player", p.getName()));
        }
        // a real client tells the server what it is a few moments after joining
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            String brand = null;
            try {
                brand = p.getClientBrandName();
            } catch (RuntimeException | NoSuchMethodError ignored) {
                // older API
            }
            if (brand == null || brand.isBlank()) flag(p, "bot", 4, lang().raw("anticheat.detail.no-brand"));
        }, 100L);
    }

    /** Bots write the moment they join, and several of them write the same line. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent e) {
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        State st = states.get(p.getUniqueId());
        String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.originalMessage()).trim().toLowerCase();
        boolean instant = st != null && now - st.joinedAt < 1500;
        List<Object[]> same = recentChat.computeIfAbsent(text, k -> java.util.Collections.synchronizedList(new ArrayList<>()));
        Set<UUID> others = new HashSet<>();
        synchronized (same) {
            same.removeIf(o -> now - (long) o[0] > 10_000);
            same.add(new Object[]{now, p.getUniqueId()});
            for (Object[] o : same) others.add((UUID) o[1]);
        }
        boolean wave = text.length() > 3 && others.size() >= 3;
        if (instant || wave) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (instant) flag(p, "bot", 6, lang().raw("anticheat.detail.instant-chat"));
                if (wave) flag(p, "bot", 4, lang().raw("anticheat.detail.chat-wave"));
            });
        }
    }

    private void cleanChat() {
        long now = System.currentTimeMillis();
        recentChat.entrySet().removeIf(en -> {
            synchronized (en.getValue()) {
                en.getValue().removeIf(o -> now - (long) o[0] > 10_000);
                return en.getValue().isEmpty();
            }
        });
    }

    // ---------------------------------------------------------------- /anticheat

    private UUID byName(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        for (Suspect s : suspects.values()) if (s.name.equalsIgnoreCase(name)) return s.id;
        for (Map.Entry<UUID, String> e : plugin.store().names.entrySet()) if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        if (args.length >= 2 && List.of("clear", "watch", "unwatch", "points", "reset").contains(sub)) {
            UUID id = byName(args[1]);
            if (id == null) {
                lang().send(sender, "anticheat.unknown", "player", args[1]);
                return true;
            }
            switch (sub) {
                case "clear" -> {
                    dismiss(id, sender.getName());
                    lang().send(sender, "anticheat.cleared", "player", args[1]);
                }
                case "watch" -> {
                    setWatched(id, args[1], true, sender.getName());
                    lang().send(sender, "anticheat.now-watched", "player", args[1]);
                }
                case "unwatch" -> {
                    setWatched(id, args[1], false, sender.getName());
                    lang().send(sender, "anticheat.not-watched", "player", args[1]);
                }
                case "reset" -> {
                    points.remove(id);
                    lang().send(sender, "anticheat.reset", "player", args[1]);
                }
                default -> {
                    lang().send(sender, "anticheat.points-header", "player", args[1], "total", String.format(Locale.ROOT, "%.1f", total(id)),
                            "limit", (int) limit(), "watched", lang().raw(watched(id) ? "module.word-on" : "module.word-off"));
                    for (Map.Entry<String, Double> en : points.getOrDefault(id, Map.of()).entrySet()) {
                        sender.sendMessage(lang().msg("anticheat.points-line", "check", lang().raw("anticheat.check." + en.getKey()),
                                "points", String.format(Locale.ROOT, "%.1f", en.getValue())));
                    }
                }
            }
            return true;
        }
        if (sender instanceof Player p && args.length == 0) {
            plugin.adminPanel().inbox(p, 0);
            return true;
        }
        lang().send(sender, "anticheat.header", "count", suspects.size());
        for (Suspect s : suspects()) {
            sender.sendMessage(lang().msg("anticheat.line", "player", s.name, "score", (int) Math.round(total(s.id)), "limit", (int) limit(),
                    "watched", s.watched ? "👁" : ""));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) for (String s : List.of("points", "watch", "unwatch", "reset", "clear")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        if (args.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            for (Suspect s : suspects.values()) if (!out.contains(s.name) && s.name.toLowerCase().startsWith(args[1].toLowerCase())) out.add(s.name);
        }
        return out;
    }
}
