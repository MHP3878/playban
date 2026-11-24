package dev.bastion.ac;

import dev.bastion.Lang;
import dev.bastion.BastionPlugin;
import dev.bastion.Time;
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
            "timer", "step", "jesus", "fastbreak", "nuker", "scaffold", "noslow", "badpackets", "autototem", "anchor", "crystal", "bot",
            "aim", "criticals", "invmove", "fastbow", "snap", "botmine", "xraybait", "elytra", "trident", "noweb", "fastclimb", "sprint",
            "fasteat", "grim", "boat");
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
        final Deque<Double> pitchDeltas = new ArrayDeque<>();
        int gcdStrikes;
        float lastYawDelta;
        int sameTurns;
        int invMoveStrikes;
        final Deque<Long> shots = new ArrayDeque<>();
        double lastDy;
        boolean special;
        int specialTick = -100;
        final Deque<String> findings = new ArrayDeque<>();
        /** the recording: the last ten seconds, one sample per tick plus every action */
        final Deque<Sample> track = new ArrayDeque<>();
        final Deque<Float> yawSteps = new ArrayDeque<>();
        final Deque<Double> digAim = new ArrayDeque<>();
        final Deque<Long> digIntervals = new ArrayDeque<>();
        long lastBreakAt;
        // movement physics, per packet
        Location lastLegal;
        int airPackets;
        double airDy;
        int flyBuffer;
        double speedBuffer;
        long boostUntil;
        int glideFlat;
        long lastSetback;
        // the prediction of the horizontal movement (per step, like the game)
        double lastH;
        boolean lastGround = true;
        long lastMoveAt;
        double predictBuffer;
        boolean prevStartGround = true;
        double prevSlip = 0.6;
        double kbAllow;
        long velocityUntil;
        /** the boxes of this player over the last ticks, newest last */
        final Deque<BoundingBox> boxes = new ArrayDeque<>();
        /** a hit whose aim is judged once the next look direction is known */
        Attack pending;
        /** a knockback the client has to take: where it began, which way, how strong, by when */
        Knock knock;
        /** the movement of the last step, for the elytra formula */
        Vector prevDelta = new Vector();
        int glideBuffer;
        // boats
        int boatAir, boatFast;
        Location boatLegal;
        /** the next teleport is our own setback */
        boolean ownTeleport;
        // the game's clock: moves faster than time passes (timer)
        long lastPacketAt;
        double timerBalance;
        int timerCancels;
        // the surroundings: cobweb, ladders, liquids, slow blocks, hunger, ground claims
        int webStrikes, climbStrikes, slowStrikes, groundSpoof, hungerSprint;
        final Deque<double[]> liquid = new ArrayDeque<>();
        long eatStart;
    }

    /** A knockback to check: start, direction and strength, the tick it came, and how long the client may take. */
    record Knock(int tick, Location start, double vx, double vz, int window) {}

    /** A hit to judge: where the eye was, where it looked, and every place the target could have been seen. */
    record Attack(int tick, Vector eye, float yaw, float pitch, List<BoundingBox> boxes, double range, String target) {}

    /** One moment of the recording. event is null for plain movement. */
    public record Sample(long time, String world, double x, double y, double z, float yaw, float pitch, String event) {}

    /** A saved piece of recording, kept as evidence. */
    public record Clip(String name, long time, String reason, List<Sample> samples) {}

    private final BastionPlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Deque<Clip>> clips = new HashMap<>();
    private final Map<UUID, Suspect> suspects = new LinkedHashMap<>();
    /** the point account: player -> check -> points */
    private final Map<UUID, Map<String, Double>> points = new HashMap<>();
    private final Set<UUID> watched = new HashSet<>();
    private final Map<UUID, Integer> caughtCount = new HashMap<>();
    private final Set<UUID> catching = new HashSet<>();
    /** the last findings per player, newest first, for /check */
    private final Map<UUID, Deque<String>> recent = new HashMap<>();
    /** team members who switched the alerts off with /alerts */
    private final Set<UUID> muted = new HashSet<>();
    private final CatchAnimation animation;
    private final EvidenceReplay replay;
    private BukkitTask task;
    private int ticks;
    // bots
    private final Deque<Long> newJoins = new ArrayDeque<>();
    private volatile long antiBotUntil;
    private final Map<String, List<Object[]>> recentChat = new ConcurrentHashMap<>();

    public AntiCheat(BastionPlugin plugin) {
        this.plugin = plugin;
        this.animation = new CatchAnimation(plugin);
        this.replay = new EvidenceReplay(plugin);
    }

    private Lang lang() {
        return plugin.lang();
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("anticheat.enabled", true);
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
        if (by != null) plugin.guard().log(by, value ? "ac-watch" : "ac-unwatch", name, null);
    }

    public void dismiss(UUID id, String by) {
        Suspect s = suspects.remove(id);
        points.remove(id);
        watched.remove(id);
        save();
        if (s != null && by != null) plugin.guard().log(by, "ac-dismiss", s.name, null);
    }

    public void handle(UUID id, String by) {
        Suspect s = suspects.get(id);
        if (s == null) return;
        s.handler = by;
        save();
        plugin.guard().log(by, "ac-case", s.name, String.format(Locale.ROOT, "%.0f", s.score()));
    }

    /** Back to zero points. */
    public void reset(UUID id) {
        points.remove(id);
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
        // the clear ones catch at once: no warning, no second try
        if (plugin.getConfig().getStringList("anticheat.instant").contains(check)) amount = Math.max(amount, limit());
        if (watched.contains(p.getUniqueId())) amount *= plugin.getConfig().getDouble("anticheat.watch-multiplier", 1.5);
        Map<String, Double> account = points.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        account.merge(check, amount, Double::sum);
        recent.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        Deque<String> rec = recent.get(p.getUniqueId());
        rec.addFirst(new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(now)) + " " + lang().raw("anticheat.check." + check)
                + String.format(Locale.ROOT, " +%.1f", amount) + (detail == null ? "" : " (" + detail + ")"));
        while (rec.size() > 12) rec.removeLast();
        double total = total(p.getUniqueId());
        String checkName = lang().raw("anticheat.check." + check);

        if (base >= 6) keep(p, checkName + String.format(Locale.ROOT, " +%.1f", amount));
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
            alert(lang().prefixed(isWatched ? "anticheat.alert-watched" : "anticheat.alert", "player", p.getName(),
                    "check", checkName, "points", String.format(Locale.ROOT, "%.1f", amount), "total", (int) Math.round(total),
                    "limit", (int) limit(), "detail", detail == null ? "" : detail)
                    .clickEvent(ClickEvent.runCommand("/akte " + p.getName())));
            save();
        }
        plugin.guard().log("AntiCheat", check, p.getName(), String.format(Locale.ROOT, "+%.1f = %.0f %s", amount, total, detail == null ? "" : detail));
        if (total >= limit() && plugin.getConfig().getBoolean("anticheat.auto-punish", true)) caught(p);
    }

    /** Anti-cheat alerts go to the team, except to those who switched them off with /alerts. */
    private void alert(net.kyori.adventure.text.Component line) {
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("bastion.notify") && !muted.contains(staff.getUniqueId())) staff.sendMessage(line);
        }
        Bukkit.getConsoleSender().sendMessage(line);
    }

    public boolean toggleAlerts(UUID staff) {
        if (muted.remove(staff)) return true;
        muted.add(staff);
        return false;
    }

    public List<String> recentFindings(UUID id) {
        return new ArrayList<>(recent.getOrDefault(id, new ArrayDeque<>()));
    }

    public Map<String, Double> account(UUID id) {
        return new LinkedHashMap<>(points.getOrDefault(id, Map.of()));
    }

    /** Clicks in the last second, for the live view in /check. */
    public int cps(UUID id) {
        State st = states.get(id);
        if (st == null) return 0;
        long now = System.currentTimeMillis();
        int n = 0;
        for (long t : st.swings) if (now - t <= 1000) n++;
        return n;
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
        UUID id = p.getUniqueId();
        String name = p.getName();
        // the step of the ladder of the chosen level (/setup): kick, warning, ban ...
        String step = plugin.punish().stepName(plugin.punish().step(plugin.akte().of(id).caught + 1));
        saveClip(id, keep(p, reason));
        plugin.akte().cheat(id, account);
        plugin.akte().event(id, "cheat", why + String.format(Locale.ROOT, " (%.0f)", total(id)), "Anti-Cheat");
        plugin.notifyTeam(lang().prefixed("anticheat.caught", "player", name, "checks", why.toString(), "step", step));
        Runnable punish = () -> {
            catching.remove(id);
            points.remove(id);
            Suspect s = suspects.get(id);
            if (s != null) {
                s.punished = true;
                s.caught = n;
            }
            save();
            plugin.punish().caught(id, name, reason, "Anti-Cheat");
        };
        animation.play(p, punish);
    }

    /** Catches a player for something found outside the point account (a forbidden mod, a test): animation, then this step. */
    public CatchAnimation animation() {
        return animation;
    }

    /** Caught for something found outside the point account (a cheat client, a test): animation, then one step up the ladder. */
    public void catchWith(Player p, String reason) {
        if (!catching.add(p.getUniqueId())) return;
        UUID id = p.getUniqueId();
        String name = p.getName();
        saveClip(id, keep(p, reason));
        String step = plugin.punish().stepName(plugin.punish().step(plugin.akte().of(id).caught + 1));
        plugin.notifyTeam(lang().prefixed("anticheat.caught", "player", name, "checks", reason, "step", step));
        animation.play(p, () -> {
            catching.remove(id);
            plugin.punish().caught(id, name, reason, "Anti-Cheat");
        });
    }

    private State state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new State());
    }

    private boolean exempt(Player p) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return true;
        if (p.hasPermission("bastion.bypass")) return true;
        if (animation.playing(p.getUniqueId())) return true;
        // in the check room the player stands in a box of bedrock and is moved by the server
        if (plugin.room() != null && plugin.room().holds(p)) return true;
        // StaffCore (staff mode, vanish, frozen, in prison) or another plugin that hides the player
        return StaffCoreHook.exempt(p);
    }

    /**
     * The game moves once per tick, twenty times a second. A timer cheat speeds the game up and sends
     * more: every move counts 50 ms, the time that really passed is taken off. Whoever is more than a
     * few ticks ahead has the move thrown away (back to where they were), so the game runs at its own
     * pace for them too. After lag a short burst is fine (up to a second of credit), and a client clock
     * that runs a little fast (up to 2 %) is allowed for.
     */
    private boolean timerLimit(PlayerMoveEvent e, Player p, State st, long now) {
        long gap = st.lastPacketAt == 0 ? 50 : now - st.lastPacketAt;
        st.lastPacketAt = now;
        if (!enabled() || exempt(p) || now < st.exemptUntil || p.isInsideVehicle() || laggy()) {
            st.timerBalance = Math.min(st.timerBalance, 0);
            return false;
        }
        st.timerBalance = Math.max(-1000, st.timerBalance + 50 - gap - 1);
        if (st.timerBalance <= 200 || !plugin.getConfig().getBoolean("anticheat.setback", true)) return false;
        st.timerBalance -= 50;
        // too fast for the clock: this move does not happen
        st.ownTeleport = true;
        e.setTo(e.getFrom().clone());
        if (++st.timerCancels % 20 == 1) {
            flag(p, "timer", 4, String.format(Locale.ROOT, "%d ms ahead", (long) st.timerBalance + 50));
        }
        return true;
    }

    /** Ends the grace after joining at once (the check room is over, or a test). */
    public void endGrace(Player p) {
        state(p).exemptUntil = 0;
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
        replay.stopAll();
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
            // where this player was, tick by tick: others hit them where they saw them (their ping ago)
            st.boxes.addLast(p.getBoundingBox());
            while (st.boxes.size() > 30) st.boxes.removeFirst();
            if (st.pending != null && ticks - st.pending.tick() >= 2) aimCheck(p, st);
            if (st.knock != null && ticks - st.knock.tick() >= st.knock.window()) knockCheck(p, st);
            decay(p.getUniqueId(), st, now);
            autoTotem(p, st, now);
            Location loc = p.getLocation();
            Location last = st.last;
            st.last = loc;
            record(st, loc, null);
            if (last != null) {
                st.yawSteps.addLast(Math.abs(wrap(loc.getYaw() - last.getYaw())));
                while (st.yawSteps.size() > 4) st.yawSteps.removeFirst();
            }
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
            // looking at 75 blocks around costs; every other tick is enough
            if (ticks - st.specialTick >= 2) {
                st.special = special(p);
                st.specialTick = ticks;
            }
            boolean special = st.special;
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
                if (st.hoverTicks == 20 || (st.hoverTicks > 20 && st.hoverTicks % 40 == 0)) {
                    flag(p, "fly", 25, lang().raw("anticheat.detail.hover"));
                    if (st.lastLegal != null && st.lastLegal.getWorld() == p.getWorld()) {
                        st.ownTeleport = true;
                        p.teleport(st.lastLegal);
                    }
                }
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
                flag(p, "step", severity(dy, 0.62, 1.5, 18), String.format(Locale.ROOT, "%.2f", dy));
            }

            // walking on water
            Block under = loc.clone().add(0, -0.1, 0).getBlock();
            Block body = loc.clone().add(0, 0.2, 0).getBlock();
            // deep water under the feet, air at the body, moving flat: walking on the surface
            if (under.getType() == Material.WATER && under.getRelative(0, -1, 0).getType() == Material.WATER && body.getType().isAir()
                    && horizontal > 0.1 && !frostWalker(p) && Math.abs(dy) < 0.01 && !p.isSwimming()) {
                // standing on water does not exist: pushed into it, and from the second second on it counts
                if (++st.waterWalk % 10 == 0 && plugin.getConfig().getBoolean("anticheat.setback", true)) {
                    st.ownTeleport = true;
                    p.teleport(loc.clone().add(0, -0.6, 0), PlayerTeleportEvent.TeleportCause.PLUGIN);
                }
                if (st.waterWalk == 40) flag(p, "jesus", 15, null);
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
                            flag(p, "nofall", severity(height, 4, 22, 20), String.format(Locale.ROOT, "%.1f", height));
                            // the fall still hurts
                            if (plugin.getConfig().getBoolean("anticheat.nofall-damage", true) && !p.isDead()) p.damage(Math.max(0, height - 3));
                        }
                    }, 20L);
                }
                st.fallStartY = Double.NaN;
            }
            st.wasOnGround = ground;

            // using an item (eating, bow, shield) slows you right down
            boolean using = handRaised(p);
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
                        flag(p, "speed", severity(second, limit, limit * 2, 20), String.format(Locale.ROOT, "%.1f b/s", second));
                    }
                } else st.speedStrikes = 0;
                // no slow: full speed while eating or drawing a bow
                if (st.usingTicks > 20 && second > 5.5 * (speed >= 0 ? 1 + 0.2 * (speed + 1) : 1) && now > st.iceUntil && !special) {
                    double slowLimit = 5.5 * (speed >= 0 ? 1 + 0.2 * (speed + 1) : 1);
                    flag(p, "noslow", severity(second, slowLimit, slowLimit * 1.7, 12), String.format(Locale.ROOT, "%.1f b/s", second));
                }
            }
            st.lastYaw = loc.getYaw();
            st.lastDy = dy;
        }
        if (ticks % 100 == 0) cleanChat();
    }

    private void record(State st, Location l, String event) {
        st.track.addLast(new Sample(System.currentTimeMillis(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), event));
        while (st.track.size() > 260) st.track.removeFirst();
    }

    private void event(Player p, String event) {
        record(state(p), p.getLocation(), event);
    }

    /** Keeps the last ten seconds as evidence (at most five clips per player). */
    private Clip keep(Player p, String reason) {
        State st = state(p);
        Clip c = new Clip(p.getName(), System.currentTimeMillis(), reason, new ArrayList<>(st.track));
        Deque<Clip> mine = clips.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        mine.addFirst(c);
        while (mine.size() > 5) mine.removeLast();
        return c;
    }

    /**
     * Plays evidence: a saved clip (newest first, n = 1..5), or with "now" the last ten seconds of a
     * player who is online right now.
     */
    public void replay(Player staff, UUID id, String which) {
        replay(staff, id, which, EvidenceReplay.View.FOLLOW);
    }

    public void replay(Player staff, UUID id, String which, EvidenceReplay.View view) {
        Clip clip = null;
        Player online = Bukkit.getPlayer(id);
        if ("now".equalsIgnoreCase(which) && online != null) clip = keep(online, lang().raw("replay.live"));
        else {
            List<Clip> mine = clips(id);
            int n = 1;
            try {
                if (which != null) n = Integer.parseInt(which);
            } catch (NumberFormatException ignored) {
                // the newest
            }
            if (!mine.isEmpty()) clip = mine.get(Math.max(0, Math.min(mine.size(), n) - 1));
            else clip = loadLatestClip(id);
            if (clip == null && online != null) clip = keep(online, lang().raw("replay.live"));
        }
        if (clip == null || !replay.play(staff, id, clip, view)) lang().send(staff, "replay.none");
    }

    public List<Clip> clips(UUID id) {
        return new ArrayList<>(clips.getOrDefault(id, new ArrayDeque<>()));
    }

    /** Saves a clip to anticheat-clips/, so the evidence survives a restart. */
    private void saveClip(UUID id, Clip c) {
        File dir = new File(plugin.getDataFolder(), "anticheat-clips");
        dir.mkdirs();
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("player", id.toString());
        cfg.set("name", c.name());
        cfg.set("time", c.time());
        cfg.set("reason", c.reason());
        List<String> lines = new ArrayList<>();
        for (Sample s : c.samples()) {
            lines.add(String.format(Locale.ROOT, "%d;%s;%.3f;%.3f;%.3f;%.2f;%.2f;%s", s.time(), s.world(), s.x(), s.y(), s.z(), s.yaw(), s.pitch(),
                    s.event() == null ? "" : s.event()));
        }
        cfg.set("samples", lines);
        try {
            cfg.save(new File(dir, id + "-" + c.time() + ".yml"));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save an anti-cheat clip: " + e.getMessage());
        }
    }

    /** The newest saved clip of a player from disk, when none is in memory any more. */
    public Clip loadLatestClip(UUID id) {
        File dir = new File(plugin.getDataFolder(), "anticheat-clips");
        File[] files = dir.listFiles((d, n) -> n.startsWith(id.toString()) && n.endsWith(".yml"));
        if (files == null || files.length == 0) return null;
        java.util.Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(files[0]);
        List<Sample> samples = new ArrayList<>();
        for (String line : cfg.getStringList("samples")) {
            String[] f = line.split(";", -1);
            try {
                samples.add(new Sample(Long.parseLong(f[0]), f[1], Double.parseDouble(f[2]), Double.parseDouble(f[3]), Double.parseDouble(f[4]),
                        Float.parseFloat(f[5]), Float.parseFloat(f[6]), f[7].isEmpty() ? null : f[7]));
            } catch (RuntimeException ignored) {
                // a broken line
            }
        }
        return new Clip(cfg.getString("name", "?"), cfg.getLong("time"), cfg.getString("reason", ""), samples);
    }

    /**
     * A sudden turn right before an action: the mouse stood still, then jumped many degrees in a single
     * tick straight onto the target. Hands speed up and slow down; aim locks and bots jump.
     */
    private void snap(Player p, State st, String what) {
        if (st.yawSteps.size() < 3) return;
        Float[] d = st.yawSteps.toArray(new Float[0]);
        int n = d.length;
        float spike = Math.max(d[n - 1], d[n - 2]);
        float before = d[n - 1] >= d[n - 2] ? d[n - 2] : d[n - 3];
        if (before < 3 && spike > 40) {
            flag(p, "snap", severity(spike, 40, 160, 8), String.format(Locale.ROOT, "%s %.0f°", what, spike));
        }
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

    /**
     * Points by how far a value lies outside what people can do: nothing up to the human limit, then
     * smoothly more up to the full points where it is plainly a cheat. 0.5 steps, at least 0.5.
     */
    public static double severity(double value, double human, double clear, double max) {
        if (!(value > human) || clear <= human) return 0;
        double r = Math.min(1, (value - human) / (clear - human));
        double pts = max * Math.pow(r, 0.8);
        return Math.max(0.5, Math.round(pts * 2) / 2.0);
    }

    // ---------------------------------------------------------------- physics: every movement packet against the game's rules

    private static boolean groundAt(Location l) {
        for (double dx : new double[]{-0.3, 0.3}) {
            for (double dz : new double[]{-0.3, 0.3}) {
                for (double dy : new double[]{-0.04, -0.55}) {
                    Block b = l.getWorld().getBlockAt((int) Math.floor(l.getX() + dx), (int) Math.floor(l.getY() + dy), (int) Math.floor(l.getZ() + dz));
                    if (solid(b) || climbable(b.getType())) return true;
                }
            }
        }
        return false;
    }

    /** Standing on a boat, a shulker, a minecart: ground that is an entity. */
    private static boolean entityGround(Player p) {
        try {
            for (Entity e : p.getNearbyEntities(1.0, 1.5, 1.0)) {
                if (e instanceof org.bukkit.entity.Boat || e instanceof org.bukkit.entity.Shulker || e instanceof org.bukkit.entity.Minecart
                        || e.getType().name().contains("RAFT")) return true;
            }
        } catch (RuntimeException ignored) {
            // nothing around
        }
        return false;
    }

    private static boolean ceiling(Location l) {
        for (double dx : new double[]{-0.3, 0.3}) {
            for (double dz : new double[]{-0.3, 0.3}) {
                if (solid(l.getWorld().getBlockAt((int) Math.floor(l.getX() + dx), (int) Math.floor(l.getY() + 2.0), (int) Math.floor(l.getZ() + dz)))) return true;
            }
        }
        return false;
    }

    /** Back to the last place that was in order. The cheat does not get them anywhere. */
    private void setback(PlayerMoveEvent e, State st) {
        if (!plugin.getConfig().getBoolean("anticheat.setback", true) || st.lastLegal == null || st.lastLegal.getWorld() != e.getTo().getWorld()) return;
        Location back = st.lastLegal.clone();
        back.setYaw(e.getTo().getYaw());
        back.setPitch(e.getTo().getPitch());
        st.ownTeleport = true;
        e.setTo(back);
        st.lastSetback = System.currentTimeMillis();
        st.airPackets = 0;
    }

    /**
     * In the air the game knows exactly how a player moves: each tick down by gravity, (dy - 0.08) * 0.98.
     * Anything that rises or hangs beyond that is flying, whatever the cheat calls it. Too far in one step
     * is speed, more than a slab up from ground to ground is step, gliding without a rocket that never
     * sinks is elytra flying.
     */
    // ---------------------------------------------------------------- the game's physics, closely

    /**
     * On the ground the way the game means it: the feet exactly on the top of a block's collision
     * shape (slabs, stairs, fences, snow included), under the middle or a corner of the player.
     */
    static boolean exactGround(Location l) {
        double y = l.getY();
        double[][] points = {{0, 0}, {-0.299, -0.299}, {-0.299, 0.299}, {0.299, -0.299}, {0.299, 0.299}};
        try {
            for (double[] pt : points) {
                double px = l.getX() + pt[0], pz = l.getZ() + pt[1];
                int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
                int top = (int) Math.floor(y - 0.001);
                for (int by = top; by >= top - 1; by--) {
                    Block b = l.getWorld().getBlockAt(bx, by, bz);
                    if (b.getType().isAir()) continue;
                    for (BoundingBox box : b.getCollisionShape().getBoundingBoxes()) {
                        double rx = px - bx, rz = pz - bz;
                        if (rx < box.getMinX() - 0.001 || rx > box.getMaxX() + 0.001 || rz < box.getMinZ() - 0.001 || rz > box.getMaxZ() + 0.001) continue;
                        if (Math.abs(by + box.getMaxY() - y) < 0.002) return true;
                    }
                }
            }
            return false;
        } catch (RuntimeException | LinkageError e) {
            // a server without collision shapes: a full block right under the feet
            Block b = l.clone().add(0, -0.001, 0).getBlock();
            return solid(b) && Math.abs(y - Math.floor(y)) < 0.002;
        }
    }

    /** How slippery a block is: the game's own numbers. */
    private static double slipperiness(Material m) {
        return switch (m) {
            case ICE, PACKED_ICE, FROSTED_ICE -> 0.98;
            case BLUE_ICE -> 0.989;
            case SLIME_BLOCK -> 0.8;
            default -> 0.6;
        };
    }

    /**
     * Horizontal movement predicted like the game does it, step by step: the speed of the step before
     * times friction (0.91 in the air, block slipperiness x 0.91 on the ground) plus the push of the
     * keys (the movement speed attribute on the ground, 0.026 in the air) plus 0.2 for a sprint jump.
     * Knockback and players pushing each other add their share. Whatever goes beyond is put back at
     * once and counted. Returns true when the step was put back.
     */
    private boolean predictHorizontal(PlayerMoveEvent e, Player p, State st, long now, int steps, double dy, double horizontal, boolean special) {
        Location from = e.getFrom();
        boolean inLiquid = from.getBlock().isLiquid() || from.clone().add(0, 0.4, 0).getBlock().isLiquid();
        if (special || inLiquid || p.isSwimming()) {
            st.lastH = horizontal;
            st.predictBuffer = 0;
            return false;
        }
        double speed = attribute(p, "movement_speed", 0.1);
        // sprinting can start this very step; with too little food it cannot start at all
        boolean canSprint = p.getFoodLevel() > 6 || p.getAllowFlight();
        if (!p.isSprinting() && canSprint) speed *= 1.3;
        double airPush = canSprint ? 0.026 : 0.02;
        // the game's order: friction slows the last step by the ground the player stood on when that
        // step began; the keys push by the ground the player stands on now; a jump starts from it
        boolean groundNow = exactGround(from);
        double slipNow = slipperiness(from.clone().add(0, -0.5001, 0).getBlock().getType());
        double friction = st.prevStartGround ? st.prevSlip * 0.91 : 0.91;
        double groundPush = speed * (0.21600002 / (slipNow * slipNow * slipNow));
        double v = Math.max(st.lastH, st.kbAllow);
        // each movement packet is one tick of the client (late or bunched packets are still one tick
        // each); only moves too small to report (under 0.0625) can hide in a later one, a little each
        v = v * friction + (groundNow ? groundPush : airPush);
        if (groundNow && dy > 0.2 && canSprint) v += 0.2;
        double allowed = v + Math.min(3, steps - 1) * 0.0625;
        st.prevStartGround = groundNow;
        st.prevSlip = slipNow;
        // other players and mobs push a little
        int crowd = 0;
        for (Entity near : p.getNearbyEntities(0.8, 1.0, 0.8)) if (near instanceof LivingEntity) crowd++;
        allowed += Math.min(0.2, crowd * 0.05);
        if (Boolean.getBoolean("bastion.debug")) plugin.getLogger().info(String.format(Locale.ROOT,
                "PH h=%.4f dy=%.4f fromY=%.4f toY=%.4f gN=%s pG=%s og=%s lastH=%.4f allowed=%.4f steps=%d", horizontal, dy, from.getY(),
                e.getTo().getY(), groundNow, friction < 0.9, p.isOnGround(), st.lastH, allowed, steps));
        double tolerance = 0.03 + (now < st.velocityUntil ? 0.08 : 0);
        double over = horizontal - allowed - tolerance;
        st.kbAllow *= Math.pow(0.91, steps);
        if (st.kbAllow < 0.05) st.kbAllow = 0;
        if (over > 0) {
            st.predictBuffer += 1 + over * 10;
            // a clear jump forward goes back at once, small ones after a second time
            if (over > 0.12 || st.predictBuffer >= 2) {
                flag(p, "speed", severity(over, 0, 0.6, 12), String.format(Locale.ROOT, "%.3f > %.3f", horizontal, allowed)
                        + (Boolean.getBoolean("bastion.debug") ? String.format(Locale.ROOT, " g=%s dy=%.3f lastH=%.3f steps=%d slip=%.2f spd=%.3f spr=%s og=%s",
                        groundNow, dy, st.lastH, steps, slipNow, speed, p.isSprinting(), p.isOnGround()) : ""));
                setback(e, st);
                st.predictBuffer = 0;
                // back where it was in order, moving as fast as it legally did
                return true;
            }
        } else st.predictBuffer = Math.max(0, st.predictBuffer - 0.1);
        st.lastH = horizontal;
        return false;
    }

    /** Whether the player wears something to glide with (an elytra that is not broken, or an item that glides). */
    private static boolean canGlide(Player p) {
        ItemStack chest = p.getInventory().getChestplate();
        if (chest == null) return false;
        if (chest.getType() == Material.ELYTRA) {
            if (chest.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d) return d.getDamage() < chest.getType().getMaxDurability() - 1;
            return true;
        }
        try {
            return chest.hasData(io.papermc.paper.datacomponent.DataComponentTypes.GLIDER);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The surroundings: cobweb, ladders and vines, water and lava, soul sand and honey, hunger and the
     * claim of standing on the ground. Each slows or limits the player in the game; a cheat ignores it.
     * Returns true when the step was put back.
     */
    private boolean surroundings(PlayerMoveEvent e, Player p, State st, long now, double dy, double horizontal) {
        Location to = e.getTo();
        Location from = e.getFrom();
        Block feet = from.getBlock();
        Block head = from.clone().add(0, 1, 0).getBlock();
        // cobweb: a quarter of the speed sideways, almost nothing up and down
        if ((feet.getType() == Material.COBWEB || head.getType() == Material.COBWEB) && !p.isGliding()) {
            if (horizontal > 0.12 || dy > 0.1) {
                if (++st.webStrikes >= 3) {
                    flag(p, "noweb", 10, String.format(Locale.ROOT, "%.2f", Math.max(horizontal, dy)));
                    setback(e, st);
                    st.webStrikes = 0;
                    return true;
                }
            } else st.webStrikes = Math.max(0, st.webStrikes - 1);
        }
        // ladders and vines: up at 0.2 per step at most (jumping onto it once is more)
        boolean climbing = isClimbable(feet.getType()) && !groundAt(to) && feet.getType() != Material.SCAFFOLDING;
        if (climbing && st.airPackets > 1 && dy > 0.22 && amplifier(p, PotionEffectType.JUMP_BOOST) < 0) {
            if (++st.climbStrikes >= 2) {
                flag(p, "fastclimb", severity(dy, 0.22, 0.8, 15), String.format(Locale.ROOT, "%.2f", dy));
                setback(e, st);
                st.climbStrikes = 0;
                return true;
            }
        } else if (st.climbStrikes > 0 && !climbing) st.climbStrikes = 0;
        // water and lava: their own speed, counted over a second
        boolean water = feet.getType() == Material.WATER || feet.getBlockData() instanceof org.bukkit.block.data.Waterlogged w && w.isWaterlogged();
        boolean lava = feet.getType() == Material.LAVA;
        if ((water || lava) && !p.isGliding() && !p.isRiptiding()) {
            st.liquid.addLast(new double[]{now, horizontal});
            while (!st.liquid.isEmpty() && now - st.liquid.peekFirst()[0] > 1000) st.liquid.removeFirst();
            if (st.liquid.size() >= 15) {
                double second = 0;
                for (double[] m : st.liquid) second += m[1];
                double limit = lava ? 3.0 : 6.5;
                ItemStack boots = p.getInventory().getBoots();
                if (boots != null) for (var en : boots.getEnchantments().entrySet()) {
                    if (en.getKey().getKey().getKey().equals("depth_strider")) limit *= 1 + 0.45 * en.getValue();
                }
                if (amplifier(p, PotionEffectType.DOLPHINS_GRACE) >= 0) limit *= 3;
                int sp = amplifier(p, PotionEffectType.SPEED);
                if (sp >= 0) limit *= 1 + 0.2 * (sp + 1);
                if (second > limit) {
                    flag(p, lava ? "jesus" : "speed", severity(second, limit, limit * 2, 12), String.format(Locale.ROOT, "%.1f b/s %s", second, lava ? "lava" : "water"));
                    st.liquid.clear();
                    setback(e, st);
                    return true;
                }
            }
        } else if (!st.liquid.isEmpty()) st.liquid.clear();
        // soul sand and honey slow you down (unless with soul speed); a cheat keeps full speed
        Material under = from.clone().add(0, -0.1, 0).getBlock().getType();
        boolean slowBlock = (under == Material.SOUL_SAND && !soulSpeed(p)) || under == Material.HONEY_BLOCK;
        if (slowBlock && groundAt(to) && horizontal > 0.17 && amplifier(p, PotionEffectType.SPEED) < 0) {
            if (++st.slowStrikes >= 10) {
                flag(p, "noslow", 8, String.format(Locale.ROOT, "%.2f %s", horizontal, under.name().toLowerCase(Locale.ROOT)));
                setback(e, st);
                st.slowStrikes = 0;
                return true;
            }
        } else if (!slowBlock) st.slowStrikes = 0;
        // hunger: with 6 or less food nobody can sprint
        if (p.isSprinting() && p.getFoodLevel() <= 6 && !p.getAllowFlight() && horizontal > 0.05) {
            if (++st.hungerSprint == 40) flag(p, "sprint", 6, "food " + p.getFoodLevel());
        } else st.hungerSprint = 0;
        // "on the ground", says the client, while the server sees air under it and it falls: no fall damage
        if (p.isOnGround() && !groundAt(to) && !groundAt(from) && dy < -0.15 && !special(p) && !p.isInsideVehicle()) {
            if (++st.groundSpoof >= 3) {
                flag(p, "nofall", 10, lang().raw("anticheat.detail.ground"));
                setback(e, st);
                st.groundSpoof = 0;
                return true;
            }
        } else st.groundSpoof = Math.max(0, st.groundSpoof - 1);
        return false;
    }

    private static boolean isClimbable(Material m) {
        String n = m.name();
        return m == Material.LADDER || m == Material.VINE || n.endsWith("_VINES") || n.endsWith("_VINES_PLANT") || m == Material.SCAFFOLDING;
    }

    private static boolean soulSpeed(Player p) {
        ItemStack boots = p.getInventory().getBoots();
        if (boots == null) return false;
        for (var en : boots.getEnchantments().keySet()) if (en.getKey().getKey().equals("soul_speed")) return true;
        return false;
    }

    private void physics(PlayerMoveEvent e, Player p, State st, long now) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;
        if (!enabled() || exempt(p) || p.isFlying() || p.getAllowFlight() || p.isInsideVehicle() || p.isRiptiding() || now < st.exemptUntil
                || p.isDead() || laggy()) {
            st.airPackets = 0;
            st.flyBuffer = 0;
            st.lastLegal = to.clone();
            st.lastH = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
            st.lastGround = groundAt(to);
            st.lastMoveAt = now;
            return;
        }
        double dy = to.getY() - from.getY();
        double horizontal = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        if (horizontal > 10 || Math.abs(dy) > 10) return;
        // the steps since the last one (moves too small to report are added up by the server)
        int steps = st.lastMoveAt == 0 ? 1 : (int) Math.max(1, Math.min(4, Math.round((now - st.lastMoveAt) / 50.0)));
        st.lastMoveAt = now;
        if (surroundings(e, p, st, now, dy, horizontal)) return;

        // elytra: speed, and gliding without a rocket that does not sink
        if (p.isGliding()) {
            boolean boosted = now < st.boostUntil;
            if (!canGlide(p)) {
                flag(p, "elytra", 25, lang().raw("anticheat.detail.no-elytra"));
                p.setGliding(false);
                setback(e, st);
                return;
            }
            if (!boosted && horizontal > plugin.getConfig().getDouble("anticheat.elytra-speed", 2.6)) {
                flag(p, "elytra", severity(horizontal, 2.6, 5, 25), String.format(Locale.ROOT, "%.1f b/t", horizontal));
                p.setGliding(false);
                setback(e, st);
                return;
            }
            // the game's gliding, tick by tick: look direction, gravity, lift and drag. Elytra fly and
            // boost cheats leave it at once; judged in open air (no block to bump into nearby)
            Vector actual = to.toVector().subtract(from.toVector());
            if (boosted) {
                if (actual.length() > 3.2) {
                    flag(p, "elytra", severity(actual.length(), 3.2, 6, 25), String.format(Locale.ROOT, "%.2f b/t", actual.length()));
                    setback(e, st);
                    return;
                }
            } else if (amplifier(p, PotionEffectType.SLOW_FALLING) < 0 && openAir(to, actual.length() + 1) && openAir(from, 1)) {
                Vector predicted = glide(st.prevDelta, to.getYaw(), to.getPitch());
                double err = actual.clone().subtract(predicted).length();
                double tolerance = 0.06 + 0.03 * predicted.length();
                if (err > tolerance) {
                    if (err > 0.25 || ++st.glideBuffer >= 2) {
                        flag(p, "elytra", severity(err, tolerance, 1.5, 25), String.format(Locale.ROOT, "%s %.2f/%.2f",
                                lang().raw("anticheat.detail.elytra-physics"), actual.length(), predicted.length()));
                        setback(e, st);
                        st.glideBuffer = 0;
                        return;
                    }
                } else {
                    st.glideBuffer = Math.max(0, st.glideBuffer - 1);
                    st.lastLegal = to.clone();
                }
            }
            st.glideFlat = !boosted && dy >= -0.005 ? st.glideFlat + 1 : Math.max(0, st.glideFlat - 2);
            if (st.glideFlat > 80) {
                flag(p, "elytra", 25, lang().raw("anticheat.detail.glide"));
                p.setGliding(false);
                st.glideFlat = 0;
                setback(e, st);
            }
            st.airPackets = 0;
            return;
        }

        boolean special = special(p);
        boolean ground = groundAt(to) || (!special && entityGround(p));
        int jump = amplifier(p, PotionEffectType.JUMP_BOOST);
        boolean noGravity = amplifier(p, PotionEffectType.LEVITATION) >= 0 || amplifier(p, PotionEffectType.SLOW_FALLING) >= 0;

        // the game's own rule for walking: this step can be at most the last one slowed by friction,
        // plus what the keys push (more on the ground, little in the air), plus 0.2 for a sprint jump.
        // Speed, strafe, bunny hop and "low hop" cheats all break it in the first steps.
        if (predictHorizontal(e, p, st, now, steps, dy, horizontal, special)) return;

        // speed, per step (a second, rougher line)
        double limit = plugin.getConfig().getDouble("anticheat.step-speed", 0.75);
        int speed = amplifier(p, PotionEffectType.SPEED);
        if (speed >= 0) limit *= 1 + 0.2 * (speed + 1);
        if (now < st.iceUntil || onIce(p)) {
            limit *= 2.4;
            st.iceUntil = now + 1500;
        }
        if (special) limit *= 1.6;
        if (amplifier(p, PotionEffectType.DOLPHINS_GRACE) >= 0) limit *= 2.5;
        if (horizontal > limit) {
            st.speedBuffer += 1;
            if (st.speedBuffer >= 3) {
                flag(p, "speed", severity(horizontal, limit, limit * 2, 20), String.format(Locale.ROOT, "%.2f b/t", horizontal));
                setback(e, st);
                st.speedBuffer = 1;
                return;
            }
        } else st.speedBuffer = Math.max(0, st.speedBuffer - 0.25);

        if (ground || special || noGravity) {
            // step: more than a slab up in one step, from ground to ground
            if (ground && st.airPackets == 0 && dy > 0.62 && dy < 3 && jump < 0 && !special && attribute(p, "step_height", 0.6) < 0.7) {
                flag(p, "step", severity(dy, 0.62, 1.5, 18), String.format(Locale.ROOT, "%.2f", dy));
                setback(e, st);
                return;
            }
            st.airPackets = 0;
            st.flyBuffer = 0;
            if (ground && !special) st.lastLegal = to.clone();
            st.airDy = dy;
            return;
        }

        st.airPackets++;
        if (st.airPackets == 1) {
            // leaving the ground: a jump is at most 0.42 up (more with jump boost)
            double maxJump = 0.42 + (jump >= 0 ? 0.1 * (jump + 1) : 0);
            // the game's jump is exact: anything higher goes back at once (high jump, step-up cheats)
            if (dy > maxJump + 0.03 && now > st.velocityUntil) {
                flag(p, "fly", 25, String.format(Locale.ROOT, "jump %.2f", dy));
                setback(e, st);
                return;
            }
            st.airDy = dy;
            return;
        }
        if (ceiling(to) || ceiling(from)) {
            // a head hitting the ceiling stops the rise; no judging this step
            st.airDy = dy;
            return;
        }
        double predicted = (st.airDy - 0.08) * 0.98;
        double off = dy - predicted;
        st.airDy = dy;
        if (off > 0.05) {
            // clearly more up than gravity allows: back at once; a hair over it only the second time
            if (off > 0.07 || ++st.flyBuffer >= 2) {
                String how = dy > 0.02 ? lang().raw("anticheat.detail.rise") : lang().raw("anticheat.detail.hover");
                flag(p, "fly", 25, String.format(Locale.ROOT, "%s %.3f/%.3f", how, dy, predicted));
                setback(e, st);
                st.flyBuffer = 0;
            }
        } else if (st.flyBuffer > 0) st.flyBuffer--;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBoost(com.destroystokyo.paper.event.player.PlayerElytraBoostEvent e) {
        state(e.getPlayer()).boostUntil = System.currentTimeMillis() + 4000;
    }

    /** Riptide only works in water or rain. Anywhere else it is a trident boost cheat. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRiptide(org.bukkit.event.player.PlayerRiptideEvent e) {
        Player p = e.getPlayer();
        if (exempt(p)) return;
        // riptide needs a trident with riptide, and water: in it, rain falling on the player (the game
        // knows where it rains: not in deserts, not under a roof) or a bubble column
        ItemStack used = e.getItem();
        boolean riptideTrident = used != null && used.getType() == Material.TRIDENT
                && used.getEnchantments().keySet().stream().anyMatch(en -> en.getKey().getKey().equals("riptide"));
        boolean wet = p.isInWater();
        try {
            wet |= p.isInRain() || p.isInBubbleColumn();
        } catch (RuntimeException | NoSuchMethodError ignored) {
            wet |= p.getWorld().hasStorm() && p.getWorld().getHighestBlockYAt(p.getLocation()) <= p.getLocation().getBlockY();
        }
        if (wet && riptideTrident) {
            exemptFor(p, 3000);
            return;
        }
        Location back = state(p).lastLegal;
        flag(p, "trident", 25, lang().raw(riptideTrident ? "anticheat.detail.dry" : "anticheat.detail.no-riptide"));
        Bukkit.getScheduler().runTask(plugin, () -> {
            p.setVelocity(new Vector(0, 0, 0));
            if (back != null && back.getWorld() == p.getWorld()) {
                state(p).ownTeleport = true;
                p.teleport(back);
            }
        });
    }

    private static boolean handRaised(Player p) {
        try {
            return p.isHandRaised();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long gcd(long a, long b) {
        while (b > 4096) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        State st = state(p);
        long now = System.currentTimeMillis();
        if (timerLimit(e, p, st, now)) return;
        physics(e, p, st, now);
        if (e.getTo() != null) {
            st.prevDelta = e.getTo().toVector().subtract(e.getFrom().toVector());
            st.lastGround = groundAt(e.getTo());
            if (st.lastSetback == now) st.lastH = 0;
        }
        aim(p, st, e.getFrom(), e.getTo(), now);
        st.movePackets.addLast(now);
        while (!st.movePackets.isEmpty() && now - st.movePackets.peekFirst() > 5000) st.movePackets.removeFirst();
        if (st.movePackets.size() > 5 * 20 && now > st.exemptUntil && ping(p) < 200) {
            double rate = st.movePackets.size() / 5.0;
            if (rate > plugin.getConfig().getDouble("anticheat.max-packets", 23)) {
                double maxRate = plugin.getConfig().getDouble("anticheat.max-packets", 23);
                if (++st.timerStrikes % 20 == 0) flag(p, "timer", severity(rate, maxRate, maxRate * 1.6, 22), String.format(Locale.ROOT, "%.1f/s", rate));
            } else st.timerStrikes = 0;
        }
    }

    /**
     * Mouse movement always comes in steps of the same size (it depends on the sensitivity). Aim cheats
     * turn by arbitrary amounts, so the common step vanishes. Judged only in a fight, never while zooming.
     */
    private void aim(Player p, State st, Location from, Location to, long now) {
        if (now - st.lastHit > 3000 || exempt(p)) return;
        Material inHand = p.getInventory().getItemInMainHand().getType();
        if (inHand == Material.SPYGLASS || handRaised(p)) return;
        float dYaw = Math.abs(wrap(to.getYaw() - from.getYaw()));
        double dPitch = Math.abs(to.getPitch() - from.getPitch());
        if (dPitch > 0.05 && dPitch < 25 && Math.abs(to.getPitch()) < 85) {
            st.pitchDeltas.addLast(dPitch);
            if (st.pitchDeltas.size() >= 40) {
                long g = 0;
                for (double d : st.pitchDeltas) g = g == 0 ? (long) (d * 16777216L) : gcd(g, (long) (d * 16777216L));
                st.pitchDeltas.clear();
                double step = g / 16777216.0;
                // the smallest step a mouse can make at the lowest sensitivity is about 0.0096 degrees
                if (step < 0.007) {
                    if (++st.gcdStrikes >= 3) flag(p, "aim", severity(0.007 - step, 0, 0.007, 3), String.format(Locale.ROOT, "step %.5f", step));
                } else st.gcdStrikes = 0;
            }
        }
        // the very same turn again and again
        if (dYaw > 1.5 && Math.abs(dYaw - st.lastYawDelta) < 0.0005) {
            if (++st.sameTurns >= 8) {
                flag(p, "aim", 2, String.format(Locale.ROOT, "%.3f° x%d", dYaw, st.sameTurns));
                st.sameTurns = 0;
            }
        } else st.sameTurns = 0;
        st.lastYawDelta = dYaw;
    }

    /** Clicking around in your own inventory while running: the inventory screen does not let you move. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || exempt(p)) return;
        if (e.getView().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) return;
        State st = state(p);
        long now = System.currentTimeMillis();
        if (now < st.exemptUntil || now < st.iceUntil || st.moves.isEmpty()) return;
        double lastTick = st.moves.peekLast()[1];
        if (p.isSprinting() && lastTick > 0.2 && !st.special && ping(p) < 200) {
            if (++st.invMoveStrikes >= 3) {
                flag(p, "invmove", 2, String.format(Locale.ROOT, "%.2f", lastTick));
                st.invMoveStrikes = 0;
            }
        } else st.invMoveStrikes = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(org.bukkit.event.entity.EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || exempt(p) || e.getBow() == null || e.getBow().getType() != Material.BOW) return;
        State st = state(p);
        long now = System.currentTimeMillis();
        st.shots.addLast(now);
        while (!st.shots.isEmpty() && now - st.shots.peekFirst() > 1000) st.shots.removeFirst();
        // a full draw takes a second; three and more full shots in one second are not possible
        if (e.getForce() > 0.9f && st.shots.size() >= 3) flag(p, "fastbow", st.shots.size() > 5 ? 8 : 4, st.shots.size() + "/s");
    }

    // ---------------------------------------------------------------- things that excuse odd movement

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        State own = state(e.getPlayer());
        if (own.ownTeleport) {
            // our own setback: no reason to stop judging, the player is right where the rules allow
            own.ownTeleport = false;
            own.last = e.getTo();
            own.prevDelta = new Vector();
            own.airPackets = 0;
            own.flyBuffer = 0;
            return;
        }
        // somebody else moved the player (/spawn, a portal, a plugin): a moment to arrive
        exemptFor(e.getPlayer(), 1500);
        state(e.getPlayer()).last = e.getTo();
        state(e.getPlayer()).lastLegal = e.getTo().clone();
        state(e.getPlayer()).movePackets.clear();
        state(e.getPlayer()).yawSteps.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        Vector v = e.getVelocity();
        State st = state(e.getPlayer());
        double h = Math.hypot(v.getX(), v.getZ());
        // knockback: that much more is allowed, fading like real movement; a huge push (explosion,
        // wind charge) is not judged for a moment
        st.kbAllow = Math.max(st.kbAllow, h + 0.1);
        st.velocityUntil = System.currentTimeMillis() + 1500;
        // the next step goes up by exactly this much: as if the step before had led there
        st.airDy = v.getY() / 0.98 + 0.08;
        st.airPackets = Math.max(st.airPackets, 1);
        if (h > 1.2 || v.getY() > 1.0) exemptFor(e.getPlayer(), 1500);
        else if (h > 0.15 && !exempt(e.getPlayer()) && !e.getPlayer().isInsideVehicle() && !e.getPlayer().isGliding()) {
            st.knock = new Knock(ticks, e.getPlayer().getLocation().clone(), v.getX(), v.getZ(), 3 + Math.min(8, ping(e.getPlayer()) / 50));
        }
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

    // ---------------------------------------------------------------- knockback, elytra, boats

    /**
     * The knockback a hit gave must show in the player's movement: at least what two ticks of it
     * carry, minus the most a player can push back with the keys. A client that ignores it (anti
     * knockback, velocity cheats) is moved there by the server, unless a wall is in the way.
     */
    private void knockCheck(Player p, State st) {
        Knock k = st.knock;
        st.knock = null;
        if (!p.isOnline() || exempt(p) || p.isInsideVehicle() || p.getWorld() != k.start().getWorld() || special(p)) return;
        Location now = p.getLocation();
        double vh = Math.hypot(k.vx(), k.vz());
        double ux = k.vx() / vh, uz = k.vz() / vh;
        double along = (now.getX() - k.start().getX()) * ux + (now.getZ() - k.start().getZ()) * uz;
        double expected = vh * (1 + 0.91) - 2 * 0.13;
        if (expected < 0.15 || along >= expected * 0.4) return;
        if (k.start().getBlock().isLiquid()) return;
        // a wall in the way stops a knockback for everybody
        Location eyeStart = k.start().clone().add(0, 0.6, 0);
        Vector dir = new Vector(ux, 0, uz);
        if (p.getWorld().rayTraceBlocks(eyeStart, dir, expected + 0.4) != null
                || p.getWorld().rayTraceBlocks(k.start().clone().add(0, 0.1, 0), dir, expected + 0.4) != null) return;
        flag(p, "velocity", severity(expected - along, 0, expected, 10), String.format(Locale.ROOT, "%.2f/%.2f", Math.max(0, along), expected));
        if (plugin.getConfig().getBoolean("anticheat.setback", true)) {
            Location pushed = k.start().clone().add(ux * expected * 0.8, 0, uz * expected * 0.8);
            pushed.setYaw(now.getYaw());
            pushed.setPitch(now.getPitch());
            st.ownTeleport = true;
            p.teleport(pushed);
        }
    }

    /** One tick of gliding as the game computes it, from the movement of the tick before and the look. */
    static Vector glide(Vector prev, float yawDeg, float pitchDeg) {
        double pitch = Math.toRadians(pitchDeg), yaw = Math.toRadians(yawDeg);
        double lx = -Math.sin(yaw) * Math.cos(pitch), ly = -Math.sin(pitch), lz = Math.cos(yaw) * Math.cos(pitch);
        double hl = Math.sqrt(lx * lx + lz * lz);
        double hv = Math.sqrt(prev.getX() * prev.getX() + prev.getZ() * prev.getZ());
        double len = Math.sqrt(lx * lx + ly * ly + lz * lz);
        double c = Math.cos(pitch);
        c = c * c * Math.min(1.0, len / 0.4);
        double x = prev.getX(), y = prev.getY() + 0.08 * (-1.0 + c * 0.75), z = prev.getZ();
        if (y < 0 && hl > 0) {
            double d = y * -0.1 * c;
            x += lx * d / hl;
            y += d;
            z += lz * d / hl;
        }
        if (pitch < 0 && hl > 0) {
            double d = hv * -Math.sin(pitch) * 0.04;
            x += -lx * d / hl;
            y += d * 3.2;
            z += -lz * d / hl;
        }
        if (hl > 0) {
            x += (lx / hl * hv - x) * 0.1;
            z += (lz / hl * hv - z) * 0.1;
        }
        return new Vector(x * 0.99, y * 0.98, z * 0.99);
    }

    /** No solid block within this distance of the place: nothing to bump into. */
    private static boolean openAir(Location l, double r) {
        int rr = (int) Math.ceil(Math.min(3, r));
        for (int dx = -rr; dx <= rr; dx++) for (int dy = -rr; dy <= rr + 1; dy++) for (int dz = -rr; dz <= rr; dz++) {
            Block b = l.getWorld().getBlockAt(l.getBlockX() + dx, l.getBlockY() + dy, l.getBlockZ() + dz);
            if (!b.getType().isAir()) return false;
        }
        return true;
    }

    /**
     * Boats: in the air they only fall (BoatFly cheats rise or hang), and they have fixed top speeds on
     * water, on land and on ice. Whatever goes beyond is put back, boat and driver together.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleMove(org.bukkit.event.vehicle.VehicleMoveEvent e) {
        var vehicle = e.getVehicle();
        String type = vehicle.getType().name();
        if (!(type.contains("BOAT") || type.contains("RAFT")) || vehicle.getPassengers().isEmpty()
                || !(vehicle.getPassengers().get(0) instanceof Player p) || !enabled() || exempt(p)) return;
        State st = state(p);
        Location from = e.getFrom(), to = e.getTo();
        if (from.getWorld() != to.getWorld()) return;
        double dy = to.getY() - from.getY();
        double h = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        Block at = to.getBlock(), below = to.clone().add(0, -0.3, 0).getBlock();
        boolean water = at.isLiquid() || below.isLiquid() || at.getType() == Material.BUBBLE_COLUMN || below.getType() == Material.BUBBLE_COLUMN;
        boolean ground = !below.getType().isAir() && !below.isLiquid();
        String bad = null;
        double amount = 0;
        if (!water && !ground && dy > -0.03) {
            if (++st.boatAir >= 3) {
                bad = lang().raw("anticheat.detail.boatfly");
                amount = 25;
            }
        } else st.boatAir = 0;
        Material floor = to.clone().add(0, -0.6, 0).getBlock().getType();
        boolean ice = floor == Material.ICE || floor == Material.PACKED_ICE || floor == Material.BLUE_ICE || floor == Material.FROSTED_ICE;
        double limit = ice ? 4.2 : water ? 0.7 : 0.5;
        if (bad == null && h > limit) {
            if (++st.boatFast >= 3) {
                bad = String.format(Locale.ROOT, "%.2f > %.2f b/t", h, limit);
                amount = severity(h, limit, limit * 2, 20);
            }
        } else if (h <= limit) st.boatFast = Math.max(0, st.boatFast - 1);
        if (bad == null) {
            if (water || ground) st.boatLegal = to.clone();
            return;
        }
        st.boatAir = 0;
        st.boatFast = 0;
        flag(p, "boat", amount, bad);
        if (plugin.getConfig().getBoolean("anticheat.setback", true)) {
            Location back = st.boatLegal != null && st.boatLegal.getWorld() == to.getWorld() ? st.boatLegal : from;
            vehicle.setVelocity(new Vector());
            try {
                vehicle.teleport(back, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
            } catch (Throwable t) {
                vehicle.eject();
                vehicle.teleport(back);
            }
        }
    }

    /** Every place the attacker could have seen the target at: its last ticks (players), or now plus how far it could move. */
    private List<BoundingBox> seenBoxes(Player attacker, Entity target) {
        int back = Math.min(20, ping(attacker) / 50 + 4);
        List<BoundingBox> out = new ArrayList<>();
        if (target instanceof Player tp) {
            List<BoundingBox> hist = new ArrayList<>(state(tp).boxes);
            for (int i = Math.max(0, hist.size() - 1 - back); i < hist.size(); i++) out.add(hist.get(i).clone().expand(0.03));
        }
        Vector v = target.getVelocity();
        double moved = Math.min(3.0, Math.hypot(v.getX(), v.getZ()) * back + 0.1);
        out.add(target.getBoundingBox().clone().expand(moved, Math.min(1.5, Math.abs(v.getY()) * back + 0.1), moved));
        return out;
    }

    private static double distanceTo(Vector eye, BoundingBox b) {
        double x = Math.max(b.getMinX(), Math.min(eye.getX(), b.getMaxX()));
        double y = Math.max(b.getMinY(), Math.min(eye.getY(), b.getMaxY()));
        double z = Math.max(b.getMinZ(), Math.min(eye.getZ(), b.getMaxZ()));
        return Math.sqrt((x - eye.getX()) * (x - eye.getX()) + (y - eye.getY()) * (y - eye.getY()) + (z - eye.getZ()) * (z - eye.getZ()));
    }

    /** How far off the look is from the box: 0 when the line of sight goes through it. */
    private static double angleTo(Location eye, BoundingBox b) {
        Vector dir = eye.getDirection();
        if (b.rayTrace(eye.toVector(), dir, 8) != null) return 0;
        Vector c = b.getCenter().subtract(eye.toVector());
        double best = Math.toDegrees(dir.angle(c));
        for (double fx : new double[]{0, 1}) for (double fy : new double[]{0, 1}) for (double fz : new double[]{0, 1}) {
            Vector corner = new Vector(fx == 0 ? b.getMinX() : b.getMaxX(), fy == 0 ? b.getMinY() : b.getMaxY(), fz == 0 ? b.getMinZ() : b.getMaxZ());
            best = Math.min(best, Math.toDegrees(dir.angle(corner.subtract(eye.toVector()))));
        }
        return best;
    }

    /**
     * The hitbox check: a click hits what the crosshair is on. The look at the moment of the hit lies
     * between the last look the server had and the next one; if no look in between goes through any
     * place the target could have been, the hit was aimed by something else (kill aura).
     */
    private void aimCheck(Player p, State st) {
        Attack a = st.pending;
        st.pending = null;
        Location now = p.getEyeLocation();
        boolean hit = false;
        double off = 180;
        for (int i = 0; i <= 4 && !hit; i++) {
            float t = i / 4f;
            float yaw = a.yaw() + wrap(now.getYaw() - a.yaw()) * t;
            float pitch = a.pitch() + (now.getPitch() - a.pitch()) * t;
            Location look = new Location(p.getWorld(), a.eye().getX(), a.eye().getY(), a.eye().getZ(), yaw, pitch);
            Vector dir = look.getDirection();
            for (BoundingBox b : a.boxes()) {
                BoundingBox wide = b.clone().expand(0.1);
                if (wide.rayTrace(a.eye(), dir, a.range() + 1.5) != null) {
                    hit = true;
                    break;
                }
                off = Math.min(off, angleTo(look, wide));
            }
        }
        if (!hit && off > 4) {
            flag(p, "aura", severity(off, 4, 60, 6), String.format(Locale.ROOT, "%s %.0f° %s", lang().raw("anticheat.detail.hitbox"), off, a.target()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player attacker && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK && !exempt(attacker)) {
            Entity target = e.getEntity();
            State st = state(attacker);
            long now = System.currentTimeMillis();
            // nobody can hit while eating, drinking, blocking or drawing a bow: the game swallows the click.
            // Kill auras with "auto block" hit anyway.
            if (handRaised(attacker)) {
                flag(attacker, "aura", 5, lang().raw("anticheat.detail.autoblock"));
                if (plugin.getConfig().getBoolean("anticheat.block-hits", true)) e.setCancelled(true);
                return;
            }
            BoundingBox box = target.getBoundingBox();
            Location eye = attacker.getEyeLocation();
            boolean block = plugin.getConfig().getBoolean("anticheat.block-hits", true);
            // where the attacker could have seen the target: its places over their ping (plus the game's
            // smoothing of movement); others move by at most their speed in that time
            List<BoundingBox> seen = seenBoxes(attacker, target);
            double distance = Double.MAX_VALUE;
            for (BoundingBox b : seen) distance = Math.min(distance, distanceTo(eye.toVector(), b));
            double range = attribute(attacker, "entity_interaction_range", 3.0);
            double over = distance - range - 0.1;
            if (over > 0) {
                flag(attacker, "reach", severity(over, 0, 1.5, 20), String.format(Locale.ROOT, "%.2f > %.2f", distance, range));
                // a hit from further than the game allows does not count
                if (block) e.setCancelled(true);
            }
            // looking away from every place the target could be: no real click lands there
            double angle = 180;
            for (BoundingBox b : seen) angle = Math.min(angle, angleTo(eye, b));
            if (distance > 1.2 && angle > 75) {
                flag(attacker, "aura", severity(angle, 75, 180, 16), String.format(Locale.ROOT, "%.0f°", angle));
                if (block) e.setCancelled(true);
            }
            // the fine check needs the look of the next tick too: judged a moment later
            if (distance > 0.8) st.pending = new Attack(ticks, eye.toVector(), eye.getYaw(), eye.getPitch(), seen, range, target.getName());
            if (st.lastTarget != null && !st.lastTarget.equals(target.getUniqueId()) && now - st.lastHit < 40) {
                flag(attacker, "aura", 1, lang().raw("anticheat.detail.multi"));
            }
            // through a wall: both the middle and the top of the target hidden behind blocks
            if (distance > 1.2 && blocked(eye, box.getCenter()) && blocked(eye, new Vector(box.getCenterX(), box.getMaxY() - 0.1, box.getCenterZ()))) {
                flag(attacker, "aura", 5, lang().raw("anticheat.detail.wall"));
                if (block) e.setCancelled(true);
            }
            event(attacker, "hit");
            snap(attacker, st, "hit");
            if (target instanceof EnderCrystal && st.crystalPlacedAt > 0) {
                long dt = now - st.crystalPlacedAt;
                flag(attacker, "crystal", severity(110 - dt, 0, 100, 3), dt + "ms");
                st.crystalPlacedAt = 0;
            }
            // a critical hit needs a fall; on the ground and not falling there is none
            boolean critical = false;
            try {
                critical = e.isCritical();
            } catch (NoSuchMethodError ignored) {
                // older API
            }
            if (critical && grounded(attacker) && st.lastDy >= -0.01 && !st.special && !attacker.isInsideVehicle()) {
                flag(attacker, "criticals", 6, null);
            }
            st.lastTarget = target.getUniqueId();
            st.lastHit = now;
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
            flag(p, "autoclick", severity(cps, max, max + 15, 10), cps + " CPS");
        }
    }

    // ---------------------------------------------------------------- blocks: fast break, nuker, scaffold, x-ray

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDig(BlockDamageEvent e) {
        Player p = e.getPlayer();
        State st = state(p);
        st.diggingUntil = System.currentTimeMillis() + 600;
        st.digging = e.getBlock();
        st.digStart = System.currentTimeMillis();
        if (exempt(p)) return;
        snap(p, st, "dig");
        // how exactly the player looks at the middle of the block: people are always a few degrees off
        Location eye = p.getEyeLocation();
        Block block = e.getBlock();
        Vector toCenter = new Vector(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5).subtract(eye.toVector());
        if (toCenter.lengthSquared() > 0.25) {
            st.digAim.addLast(Math.toDegrees(eye.getDirection().angle(toCenter)));
            while (st.digAim.size() > 12) st.digAim.removeFirst();
        }
    }

    /** Mining like a bot: always dead on the middle of the block, and the same rhythm block after block. */
    private void botMining(Player p, State st, long now) {
        if (st.lastBreakAt > 0) {
            long gap = now - st.lastBreakAt;
            if (gap > 80 && gap < 3000) st.digIntervals.addLast(gap);
            while (st.digIntervals.size() > 12) st.digIntervals.removeFirst();
        }
        st.lastBreakAt = now;
        if (st.digAim.size() < 10) return;
        List<Double> sorted = new ArrayList<>(st.digAim);
        sorted.sort(Double::compare);
        double median = sorted.get(sorted.size() / 2);
        double cv = 1;
        if (st.digIntervals.size() >= 10) {
            double mean = 0;
            for (long g : st.digIntervals) mean += g;
            mean /= st.digIntervals.size();
            double var = 0;
            for (long g : st.digIntervals) var += (g - mean) * (g - mean);
            cv = Math.sqrt(var / st.digIntervals.size()) / mean;
        }
        double pts = severity(1.0 - median, 0, 1.0, 6) + (median < 2 ? severity(0.06 - cv, 0, 0.06, 4) : 0);
        if (pts > 0) {
            flag(p, "botmine", pts, String.format(Locale.ROOT, "%.2f° rhythm %.0f%%", median, cv * 100));
            st.digAim.clear();
            st.digIntervals.clear();
        }
    }

    private static boolean filler(Material m) {
        return m == Material.STONE || m == Material.DEEPSLATE || m == Material.TUFF || m == Material.NETHERRACK || m == Material.GRANITE
                || m == Material.DIORITE || m == Material.ANDESITE || m == Material.BASALT || m == Material.BLACKSTONE;
    }

    private static boolean rare(Material m) {
        return m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE || m == Material.ANCIENT_DEBRIS;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (exempt(p)) return;
        State st = state(p);
        long now = System.currentTimeMillis();
        Block b = e.getBlock();
        event(p, "break");
        if (b.getType().getHardness() > 0.3f) botMining(p, st, now);
        // fast break: faster than the tool, enchantments and haste allow
        if (b.equals(st.digging)) {
            try {
                float perTick = b.getBreakSpeed(p);
                if (perTick > 0 && perTick < 1) {
                    double expected = Math.ceil(1 / perTick) * 50.0;
                    double took = now - st.digStart;
                    double ratio = took / expected;
                    if (expected > 200 && ratio < 0.7) {
                        flag(p, "fastbreak", severity(1 - ratio, 0.3, 0.9, 14), String.format(Locale.ROOT, "%.0f/%.0fms", took, expected));
                        // too fast: the block stays
                        if (ratio < 0.5) e.setCancelled(true);
                    }
                }
            } catch (RuntimeException | NoSuchMethodError ignored) {
                // older API
            }
        }
        // nuker: many hard blocks per second, or blocks out of reach
        if (b.getType().getHardness() > 0.3f) {
            st.breaks.addLast(now);
            while (!st.breaks.isEmpty() && now - st.breaks.peekFirst() > 1000) st.breaks.removeFirst();
            if (st.breaks.size() > 12) {
                flag(p, "nuker", severity(st.breaks.size(), 12, 30, 22), st.breaks.size() + "/s");
                e.setCancelled(true);
            }
        }
        double far = p.getEyeLocation().toVector().distance(new Vector(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5));
        double blockRange = attribute(p, "block_interaction_range", 4.5) + 1.5;
        if (far > blockRange) {
            flag(p, "nuker", severity(far, blockRange, blockRange + 4.5, 12), String.format(Locale.ROOT, "%.1f", far));
            e.setCancelled(true);
        }
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (exempt(p)) return;
        State st = state(p);
        long now = System.currentTimeMillis();
        st.places.addLast(now);
        while (!st.places.isEmpty() && now - st.places.peekFirst() > 1000) st.places.removeFirst();
        if (st.places.size() > 12) {
            flag(p, "scaffold", severity(st.places.size(), 12, 25, 16), st.places.size() + "/s");
            e.setCancelled(true);
        }
        // bridging under your own feet without looking down, at walking speed
        Location l = p.getLocation();
        Block placed = e.getBlockPlaced();
        boolean underFeet = placed.getY() < l.getY() - 0.5 && Math.abs(placed.getX() + 0.5 - l.getX()) < 1.6 && Math.abs(placed.getZ() + 0.5 - l.getZ()) < 1.6;
        double speed = 0;
        for (double[] mv : st.moves) speed += mv[1];
        if (underFeet && l.getPitch() < 35 && speed > 3.5) {
            flag(p, "scaffold", severity(35 - l.getPitch(), 0, 35, 10), String.format(Locale.ROOT, "pitch %.0f", l.getPitch()));
            if (l.getPitch() < 15) e.setCancelled(true);
        }
        event(p, "place");
        snap(p, st, "place");
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
                flag(p, "anchor", severity(110 - dt, 0, 100, 3), "charge " + dt + "ms");
            } else if (st.anchorChargedAt > 0 && (hand == null || hand.getType() != Material.GLOWSTONE)) {
                long dt = now - st.anchorChargedAt;
                flag(p, "anchor", severity(110 - dt, 0, 100, 3), "explode " + dt + "ms");
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
            // how fast the next totem was there: a hand needs a few ticks, a cheat one
            long ms = dt * 50;
            event(p, "totem " + ms + "ms");
            double pts = severity(200 - ms, 40, 200, 6);
            if (pts > 0) flag(p, "autototem", pts, ms + "ms");
        }
    }

    // ---------------------------------------------------------------- bots

    /** Many new accounts at once: for two minutes only players who were here before may join. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (!enabled() || !checkOn("bot") || e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        boolean known = Bukkit.getOfflinePlayer(e.getUniqueId()).hasPlayedBefore();
        if (known) return;
        long now = System.currentTimeMillis();
        synchronized (newJoins) {
            newJoins.addLast(now);
            while (!newJoins.isEmpty() && now - newJoins.peekFirst() > 10_000) newJoins.removeFirst();
            if (newJoins.size() > plugin.getConfig().getInt("anticheat.bot-joins", 6) && now > antiBotUntil) {
                antiBotUntil = now + 120_000;
                Bukkit.getScheduler().runTask(plugin, () -> plugin.notifyTeam(lang().prefixed("anticheat.antibot-on")));
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
            plugin.notifyTeam(lang().prefixed("anticheat.watched-join", "player", p.getName()));
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
        return plugin.akte().byName(name);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = command != null && command.getName().equalsIgnoreCase("alerts") ? "alerts" : args.length > 0 ? args[0].toLowerCase() : "";
        if (sub.equals("alerts") && sender instanceof Player staff) {
            lang().send(staff, toggleAlerts(staff.getUniqueId()) ? "anticheat.alerts-on" : "anticheat.alerts-off");
            return true;
        }
        if (sub.equals("antixray")) {
            if (!sender.hasPermission("bastion.settings")) {
                lang().send(sender, "no-permission");
                return true;
            }
            File server = Bukkit.getWorldContainer();
            if (args.length < 2) {
                lang().send(sender, "anticheat.antixray-state", "state", lang().raw(AntiXray.enabled(server) ? "word-on" : "word-off"));
                return true;
            }
            boolean on = args[1].equalsIgnoreCase("on");
            String nether = Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == org.bukkit.World.Environment.NETHER)
                    .map(org.bukkit.World::getName).findFirst().orElse("world_nether");
            try {
                AntiXray.set(server, nether, on);
                lang().send(sender, on ? "anticheat.antixray-on" : "anticheat.antixray-off");
                plugin.guard().log(sender.getName(), "antixray " + (on ? "on" : "off"), null, null);
            } catch (IOException ex) {
                lang().send(sender, "anticheat.antixray-failed", "error", ex.getMessage());
            }
            return true;
        }
        if (sub.equals("replay") && sender instanceof Player staff) {
            if (args.length < 2) {
                lang().send(staff, "replay.usage");
                return true;
            }
            UUID id = byName(args[1]);
            if (id == null) {
                lang().send(staff, "anticheat.unknown", "player", args[1]);
                return true;
            }
            if (args.length >= 3 && replay.running(staff.getUniqueId()) && replay.control(staff, args[2].toLowerCase(Locale.ROOT))) return true;
            if (args.length >= 3 && args[2].equalsIgnoreCase("stop")) return true;
            // /ac replay <player> [1-5|now] [first|follow|free]
            String which = args.length >= 3 && !List.of("first", "follow", "free").contains(args[2].toLowerCase(Locale.ROOT)) ? args[2] : null;
            String view = args[args.length - 1].toLowerCase(Locale.ROOT);
            replay(staff, id, which, view.equals("first") ? EvidenceReplay.View.FIRST : view.equals("free") ? EvidenceReplay.View.FREE
                    : EvidenceReplay.View.FOLLOW);
            return true;
        }
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
                            "limit", (int) limit(), "watched", lang().raw(watched(id) ? "word-on" : "word-off"));
                    for (Map.Entry<String, Double> en : points.getOrDefault(id, Map.of()).entrySet()) {
                        sender.sendMessage(lang().msg("anticheat.points-line", "check", lang().raw("anticheat.check." + en.getKey()),
                                "points", String.format(Locale.ROOT, "%.1f", en.getValue())));
                    }
                }
            }
            return true;
        }
        if (sender instanceof Player p && args.length == 0) {
            plugin.menus().anticheat(p, 0);
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
        if (args.length == 1) for (String s : List.of("points", "watch", "unwatch", "reset", "clear", "alerts", "replay", "antixray")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        if (args.length == 2 && args[0].equalsIgnoreCase("antixray")) {
            for (String s : List.of("on", "off")) if (s.startsWith(args[1].toLowerCase())) out.add(s);
            return out;
        }
        if (args.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            for (Suspect s : suspects.values()) if (!out.contains(s.name) && s.name.toLowerCase().startsWith(args[1].toLowerCase())) out.add(s.name);
        }
        return out;
    }

    // ---------------------------------------------------------------- eating

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStartUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = e.getItem();
        if (hand != null && (hand.getType().isEdible() || hand.getType() == Material.POTION || hand.getType() == Material.MILK_BUCKET
                || hand.getType() == Material.HONEY_BOTTLE)) {
            State st = state(e.getPlayer());
            if (!handRaised(e.getPlayer())) st.eatStart = System.currentTimeMillis();
        }
    }

    /** Eating takes the game 32 ticks (dried kelp 16, honey 40). Faster is a cheat; the bite does not count. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(org.bukkit.event.player.PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (exempt(p)) return;
        Material m = e.getItem().getType();
        int needed = m == Material.DRIED_KELP ? 16 : m == Material.HONEY_BOTTLE ? 40 : 32;
        int used = -1;
        try {
            used = (int) p.getClass().getMethod("getActiveItemUsedTime").invoke(p);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            State st = state(p);
            if (st.eatStart > 0) used = (int) ((System.currentTimeMillis() - st.eatStart) / 50);
        }
        if (used >= 0 && used < needed - 6 - Math.min(6, ping(p) / 50)) {
            flag(p, "fasteat", severity(needed - used, 6, needed, 10), used + "/" + needed + " ticks");
            e.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- other anti-cheats (Grim)

    /** A finding of another anti-cheat (Grim): counted here, so it climbs Bastion's ladder and lands in the file. */
    public void external(Player p, String source, String check, double points) {
        flag(p, "grim", points, source + ": " + check);
    }
}
