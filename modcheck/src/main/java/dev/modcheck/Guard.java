package dev.modcheck;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Finds clients and mods and does what the server owner set for them. Moderators only press
 * "punish" or "let pass"; which punishment that is, the settings decide.
 */
public final class Guard implements Listener {
    public static final List<String> ACTIONS = List.of("ALERT", "KICK", "WARN", "BAN", "COMMAND");
    private static final Pattern MOD_TAG = Pattern.compile("[{(\\[]\\s*mods?\\s*[})\\]]", Pattern.CASE_INSENSITIVE);
    /** Channel name spaces of loaders and libraries, not mods anybody wants to judge. */
    private static final Set<String> LIBRARIES = Set.of("minecraft", "c", "fabric", "forge", "neoforge", "quilt", "bungeecord", "velocity",
            "paper", "bukkit", "architectury", "cloth", "owo", "fabricloader", "minecraft-api");

    /** What is known about one player's client. */
    public static final class Info {
        public String brand;
        public final Set<String> channels = new LinkedHashSet<>();
        /** mods that answered the hidden sign */
        public final Set<String> found = new LinkedHashSet<>();
        /** How the hidden sign check went: unknown, works, blocked, filtered, no-answer, impossible */
        public String sign = "unknown";
        /** why the sign could not be trusted (blank, altered, missing) */
        public String problem;
        /** what the sign showed for each mod: key and answer, the proof */
        public final Map<String, List<String[]>> signProof = new LinkedHashMap<>();
        /** every key asked on a trusted sign and its answer */
        public final Map<String, String> answered = new HashMap<>();
        /** why this client is suspicious (filter, disguise ...), for the team */
        public final List<String> suspicion = new ArrayList<>();
        final List<String> oldNonces = new ArrayList<>();
        int signsDone;
        /** rules and findings already dealt with in this session: no second alert for the same thing */
        public final Set<String> handled = new HashSet<>();
        boolean joinChecked;
        boolean retried;
        int waitedForInventory;
    }

    /** A blocked mod and what to do about it, all from the settings. */
    public record Rule(String id, String display, List<String> brand, List<String> channel, List<String> keys, String action,
                       String reason, String duration, int warnings, String command, boolean tellTeam, boolean discord) {}

    private final ModCheckPlugin plugin;
    private final Map<UUID, Info> infos = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> strikes = new HashMap<>();
    private final Map<UUID, Set<String>> passes = new HashMap<>();
    private final Map<UUID, List<List<Probe.Ask>>> asked = new HashMap<>();
    private final Map<UUID, List<Probe.Ask>> waiting = new HashMap<>();
    private final Map<UUID, Boolean> afterRespawn = new HashMap<>();
    private final Map<UUID, Boolean> queued = new HashMap<>();
    private final Map<UUID, Runnable> restore = new HashMap<>();
    /** Real signs of ours (older Paper) and the block that was there before. */
    private final Map<Location, org.bukkit.block.data.BlockData> originals = new HashMap<>();
    private YamlConfiguration data = new YamlConfiguration();

    public Guard(ModCheckPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public Info info(UUID id) {
        return infos.computeIfAbsent(id, k -> new Info());
    }

    // ---------------------------------------------------------------- data: warnings, passes, bans

    private File dataFile() {
        return new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        data = dataFile().exists() ? YamlConfiguration.loadConfiguration(dataFile()) : new YamlConfiguration();
        strikes.clear();
        passes.clear();
        ConfigurationSection s = data.getConfigurationSection("warnings");
        if (s != null) for (String k : s.getKeys(false)) {
            try {
                strikes.put(UUID.fromString(k), s.getInt(k));
            } catch (IllegalArgumentException broken) {
                plugin.getLogger().warning("data.yml: skipped warnings for '" + k + "' (not a UUID)");
            }
        }
        ConfigurationSection p = data.getConfigurationSection("passes");
        if (p != null) for (String k : p.getKeys(false)) {
            try {
                passes.put(UUID.fromString(k), new HashSet<>(p.getStringList(k)));
            } catch (IllegalArgumentException broken) {
                plugin.getLogger().warning("data.yml: skipped passes for '" + k + "' (not a UUID)");
            }
        }
        refreshBans();
    }

    private void save() {
        data.set("warnings", null);
        for (Map.Entry<UUID, Integer> e : strikes.entrySet()) data.set("warnings." + e.getKey(), e.getValue());
        data.set("passes", null);
        for (Map.Entry<UUID, Set<String>> e : passes.entrySet()) data.set("passes." + e.getKey(), new ArrayList<>(e.getValue()));
        refreshBans();
        try {
            data.save(dataFile());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    public int warnings(UUID id) {
        return strikes.getOrDefault(id, 0);
    }

    public void resetWarnings(UUID id) {
        strikes.remove(id);
        save();
    }

    public boolean passed(UUID id, String mod) {
        return passes.getOrDefault(id, Set.of()).contains(mod);
    }

    /** Lets a player keep this mod; they are not judged for it any more. */
    public void pass(UUID id, String mod, String by) {
        passes.computeIfAbsent(id, k -> new HashSet<>()).add(mod);
        save();
        log(by, "pass", Bukkit.getOfflinePlayer(id).getName(), mod);
    }

    public void unpass(UUID id, String mod) {
        Set<String> s = passes.get(id);
        if (s != null && s.remove(mod)) save();
    }

    /** ModCheck's own bans. */
    public record Ban(UUID id, String name, long until, String reason, String by) {
        boolean active() {
            return until == 0 || until > System.currentTimeMillis();
        }
    }

    public List<Ban> bans() {
        List<Ban> out = new ArrayList<>();
        for (Ban b : banView.values()) if (b.active()) out.add(b);
        return out;
    }

    public Ban ban(UUID id) {
        for (Ban b : bans()) if (b.id().equals(id)) return b;
        return null;
    }

    public boolean unban(UUID id, String by) {
        if (data.getConfigurationSection("bans." + id) == null) return false;
        String name = data.getString("bans." + id + ".name");
        data.set("bans." + id, null);
        save();
        log(by, "unban", name, "");
        return true;
    }

    /** The bans as the login thread sees them: logins are checked off the main thread. */
    private volatile Map<UUID, Ban> banView = Map.of();

    private void refreshBans() {
        Map<UUID, Ban> m = new HashMap<>();
        ConfigurationSection s = data.getConfigurationSection("bans");
        if (s != null) for (String k : s.getKeys(false)) {
            try {
                m.put(UUID.fromString(k), new Ban(UUID.fromString(k), s.getString(k + ".name", "?"), s.getLong(k + ".until"),
                        s.getString(k + ".reason", ""), s.getString(k + ".by", "ModCheck")));
            } catch (IllegalArgumentException broken) {
                // a hand-edited line that is not a UUID
            }
        }
        banView = Map.copyOf(m);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        Ban b = banView.get(e.getUniqueId());
        if (b != null && !b.active()) b = null;
        if (b == null) return;
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, lang().msg("ban.screen", "reason", b.reason(),
                "time", b.until() == 0 ? lang().raw("ban.forever") : Time.format(b.until() - System.currentTimeMillis())));
    }

    // ---------------------------------------------------------------- rules from the settings

    public List<Rule> rules() {
        List<Rule> out = new ArrayList<>();
        ConfigurationSection s = cfg().getConfigurationSection("rules");
        if (s == null) return out;
        for (String id : s.getKeys(false)) out.add(rule(id));
        return out;
    }

    public Rule rule(String id) {
        ConfigurationSection r = cfg().getConfigurationSection("rules." + id);
        if (r == null) return null;
        return new Rule(id, r.getString("display", id), lower(r.getStringList("brand")), lower(r.getStringList("channel")), r.getStringList("keys"),
                r.getString("action", "").toUpperCase(Locale.ROOT), r.getString("reason", ""), r.getString("ban-duration", ""),
                r.getInt("warnings", -1), r.getString("command", ""), r.getBoolean("notify", true), r.getBoolean("discord", true));
    }

    private static List<String> lower(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    /** What really happens for a rule: its own setting, or the default. */
    public String action(Rule r) {
        return r == null || r.action().isBlank() || r.action().equals("DEFAULT") ? cfg().getString("default-action", "WARN").toUpperCase(Locale.ROOT) : r.action();
    }

    public String reason(Rule r) {
        return r == null || r.reason().isBlank() ? cfg().getString("default-reason", "Forbidden mod: {mods}") : r.reason();
    }

    public String duration(Rule r) {
        return r == null || r.duration().isBlank() ? cfg().getString("ban-duration", "7d") : r.duration();
    }

    public int maxWarnings(Rule r) {
        return r == null || r.warnings() < 0 ? cfg().getInt("warnings", 1) : r.warnings();
    }

    /** {mod} / (mods) / [Mod] in a reason become the names. */
    public static String fill(String text, String mods) {
        return MOD_TAG.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(mods));
    }

    /** Which rules this player's client breaks (mods let pass for them left out). */
    public List<Rule> hits(UUID id) {
        Info info = info(id);
        String brand = info.brand == null ? "" : info.brand.toLowerCase(Locale.ROOT);
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules()) {
            if (passed(id, r.id())) continue;
            boolean hit = info.found.contains(r.id());
            for (String b : r.brand()) if (b.length() >= 3 && brand.contains(b)) hit = true;
            for (String c : r.channel()) for (String ch : info.channels) if (channelMatches(ch, c)) hit = true;
            if (hit) out.add(r);
        }
        return out;
    }

    /**
     * A channel belongs to a mod when its name space is the mod's name ("xaerominimap:main"), or, for
     * names of five letters and more, contains it. Short names only count exactly: a rule "map" must not
     * hit every channel with "map" somewhere in it.
     */
    static boolean channelMatches(String channel, String mod) {
        if (mod == null || mod.length() < 3) return false;
        String ch = channel.toLowerCase(Locale.ROOT);
        String ns = ch.contains(":") ? ch.substring(0, ch.indexOf(':')) : ch;
        if (ns.equals(mod) || ch.equals(mod)) return true;
        return mod.length() >= 5 && ns.contains(mod);
    }

    /** Everything that looks like a mod on this client: rules hit, sign answers, channel name spaces. */
    public List<String> mods(UUID id) {
        Info info = info(id);
        Set<String> out = new LinkedHashSet<>();
        for (Rule r : hits(id)) out.add(r.id());
        out.addAll(info.found);
        for (String ch : info.channels) {
            String ns = ch.contains(":") ? ch.substring(0, ch.indexOf(':')) : ch;
            ns = ns.toLowerCase(Locale.ROOT);
            if (LIBRARIES.contains(ns) || ns.startsWith("fabric-") || ns.startsWith("forge") || ns.startsWith("minecraft")) continue;
            KnownMods.Mod known = KnownMods.find(ns);
            out.add(known != null ? known.id() : ns);
        }
        if (info.brand != null && !info.brand.isBlank()) {
            KnownMods.Mod known = KnownMods.find(info.brand);
            if (known != null && known.client()) out.add(known.id());
        }
        return new ArrayList<>(out);
    }

    public String display(String mod) {
        Rule r = rule(mod);
        if (r != null) return r.display();
        KnownMods.Mod k = KnownMods.find(mod);
        return k != null ? k.name() : mod;
    }

    // ---------------------------------------------------------------- blocking by name

    public String block(String typed, String by) {
        KnownMods.Mod known = KnownMods.find(typed);
        String id = known != null ? known.id() : KnownMods.normalize(typed);
        // a name this short would hit far too much
        if (id.length() < 3) return null;
        String p = "rules." + id + ".";
        if (cfg().getConfigurationSection("rules." + id) == null) {
            List<String> keys = new ArrayList<>(known != null ? known.keys() : List.of());
            for (String k : KnownMods.guessKeys(id)) if (!keys.contains(k)) keys.add(k);
            cfg().set(p + "display", known != null ? known.name() : typed.trim());
            cfg().set(p + "brand", known != null && known.client() ? List.of(id) : List.of());
            cfg().set(p + "channel", known != null ? known.channels() : List.of(id));
            cfg().set(p + "keys", keys);
            cfg().set(p + "action", "DEFAULT");
            cfg().set(p + "reason", "");
            cfg().set(p + "ban-duration", "");
            cfg().set(p + "warnings", -1);
            cfg().set(p + "command", "");
            cfg().set(p + "notify", true);
            cfg().set(p + "discord", true);
            plugin.saveConfig();
        }
        log(by, "block", null, id);
        recheckAll();
        return id;
    }

    public boolean unblock(String typed, String by) {
        KnownMods.Mod known = KnownMods.find(typed);
        String id = known != null ? known.id() : KnownMods.normalize(typed);
        if (cfg().getConfigurationSection("rules." + id) == null) id = typed.trim().toLowerCase(Locale.ROOT);
        if (cfg().getConfigurationSection("rules." + id) == null) return false;
        cfg().set("rules." + id, null);
        plugin.saveConfig();
        log(by, "unblock", null, id);
        return true;
    }

    public void recheckAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            judge(p);
            if (p.isOnline() && cfg().getBoolean("sign-check", true)) probe(p);
        }
    }

    // ---------------------------------------------------------------- joining

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChannel(PlayerRegisterChannelEvent e) {
        Info info = info(e.getPlayer().getUniqueId());
        if (info.channels.size() < 256) info.channels.add(clean(e.getChannel(), 80));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // the client name and the channels arrive a moment after joining
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            Info info = info(p.getUniqueId());
            try {
                info.brand = clean(p.getClientBrandName(), 48);
            } catch (RuntimeException | NoSuchMethodError ignored) {
                // older API
            }
            for (String ch : p.getListeningPluginChannels()) if (info.channels.size() < 256) info.channels.add(clean(ch, 80));
            judge(p);
        }, 60L);
    }

    /** The sign check waits until the client shows the world: a sign editor on the loading screen is lost. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoaded(io.papermc.paper.event.player.PlayerClientLoadedWorldEvent e) {
        Player p = e.getPlayer();
        // the event comes again after every respawn and world change: the join check runs once
        Info info = info(p.getUniqueId());
        if (info.joinChecked) return;
        info.joinChecked = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && cfg().getBoolean("sign-check", true)) probe(p);
        }, 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!afterRespawn.containsKey(p.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Boolean discover = afterRespawn.remove(p.getUniqueId());
            if (discover != null && p.isOnline()) probe(p, discover);
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        afterRespawn.remove(id);
        queued.remove(id);
        infos.remove(id);
        asked.remove(id);
        waiting.remove(id);
        cleanup(e.getPlayer());
    }

    private boolean allowedClient(Info info) {
        if (!cfg().getString("mode", "BLOCKLIST").equalsIgnoreCase("ALLOWLIST")) return true;
        String brand = info.brand == null || info.brand.isBlank() ? "vanilla" : info.brand.toLowerCase(Locale.ROOT);
        for (String a : cfg().getStringList("allowed-clients")) if (brand.contains(a.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static int weight(String action) {
        return switch (action) {
            case "BAN", "COMMAND" -> 4;
            case "WARN" -> 3;
            case "KICK" -> 2;
            default -> 1;
        };
    }

    /** Checks a player against the settings and does what they say. */
    public void judge(Player p) {
        if (p.hasPermission("modcheck.bypass")) return;
        Info info = info(p.getUniqueId());
        if (!allowedClient(info)) {
            if (!info.handled.add("__client")) return;
            String client = info.brand == null ? "?" : info.brand;
            String a = cfg().getString("not-allowed-action", "KICK").toUpperCase(Locale.ROOT);
            enforce(p, null, a, client, true, List.of(lang().raw("proof.brand").replace("{brand}", client)));
            return;
        }
        // only what is new in this session: an "alert only" mod is not reported again and again
        List<Rule> hits = new ArrayList<>();
        for (Rule r : hits(p.getUniqueId())) if (!info.handled.contains(r.id())) hits.add(r);
        if (!hits.isEmpty()) {
            for (Rule r : hits) info.handled.add(r.id());
            Rule worst = hits.get(0);
            for (Rule r : hits) if (weight(action(r)) > weight(action(worst))) worst = r;
            List<String> names = new ArrayList<>();
            List<String> proof = new ArrayList<>();
            for (Rule r : hits) {
                names.add(r.display());
                for (String line : proof(p.getUniqueId(), r.id())) proof.add(r.display() + " - " + line);
            }
            enforce(p, worst, action(worst), String.join(", ", names), false, proof);
            if (!p.isOnline()) return;
        }
        List<String> lie = disguise(info);
        if (!lie.isEmpty()) suspicious(p, "disguise", lie);
        for (String hint : hints(info)) if (!info.suspicion.contains(hint)) info.suspicion.add(hint);
    }

    /**
     * "Punish" from the menu: a moderator pressed it, the settings decide what it is. For a mod that is
     * not blocked the defaults apply.
     */
    public void punish(Player p, String mod, String by) {
        Rule r = rule(mod);
        log(by, "punish", p.getName(), mod);
        enforce(p, r, action(r), display(mod), false, proof(p.getUniqueId(), mod));
    }

    public static final List<String> REPEAT_WINDOWS = List.of("off", "10m", "1h", "1d", "7d", "30d");
    public static final List<String> REPEAT_ACTIONS = List.of("KICK", "BAN 1d", "BAN 7d", "BAN 30d", "BAN perm");

    /** How often this player came with a blocked mod within the repeat window (this time included). */
    public int recentOffenses(UUID id) {
        long window = Time.parse(cfg().getString("repeat.window", "off"));
        if (window <= 0) return 0;
        long now = System.currentTimeMillis();
        int n = 0;
        for (long t : data.getLongList("offenses." + id)) if (now - t <= window) n++;
        return n;
    }

    /** Notes this offense; returns the repeat punishment when the limit is reached, else null. */
    private String repeat(UUID id) {
        long window = Time.parse(cfg().getString("repeat.window", "off"));
        if (window <= 0) return null;
        long now = System.currentTimeMillis();
        List<Long> times = new ArrayList<>();
        for (long t : data.getLongList("offenses." + id)) if (now - t <= window) times.add(t);
        times.add(now);
        data.set("offenses." + id, times);
        save();
        return times.size() >= cfg().getInt("repeat.times", 2) ? cfg().getString("repeat.action", "BAN 7d") : null;
    }

    private void enforce(Player p, Rule rule, String action, String mods, boolean client, List<String> proof) {
        String reason = fill(reason(rule), mods);
        // came back with a blocked mod too often in a short time: the repeat punishment instead
        String again = action.equals("ALERT") && !cfg().getBoolean("repeat.count-alerts", false) ? null : repeat(p.getUniqueId());
        if (again != null) {
            reason = reason + " " + lang().raw("repeat.suffix");
            String[] parts = again.trim().split("\\s+", 2);
            if (parts[0].equalsIgnoreCase("BAN")) {
                log("ModCheck", "repeat ban", p.getName(), mods + proofForLog(proof));
                alertTeam(p, mods, lang().raw("repeat.alert-action").replace("{action}", again), client, rule, proof);
                banFor(p, reason, parts.length > 1 ? parts[1] : "perm");
            } else {
                log("ModCheck", "repeat kick", p.getName(), mods + proofForLog(proof));
                alertTeam(p, mods, lang().raw("repeat.alert-action").replace("{action}", again), client, rule, proof);
                p.kick(lang().msg("kick.screen", "reason", reason));
            }
            return;
        }
        alertTeam(p, mods, lang().raw("action." + action.toLowerCase(Locale.ROOT)), client, rule, proof);
        if (rule == null || rule.discord()) discord(p.getName(), mods, action, proof);
        log("ModCheck", action.toLowerCase(Locale.ROOT), p.getName(), mods + proofForLog(proof));
        switch (action) {
            case "ALERT" -> {
                // the team knows, nothing else
            }
            case "KICK" -> p.kick(lang().msg("kick.screen", "reason", reason));
            case "BAN" -> banNow(p, rule, reason);
            case "COMMAND" -> runCommand(p, rule, reason, mods);
            default -> {
                int n = strikes.merge(p.getUniqueId(), 1, Integer::sum);
                save();
                if (n <= maxWarnings(rule)) p.kick(lang().msg("kick.warn-screen", "reason", reason, "mods", mods,
                        "left", Math.max(0, maxWarnings(rule) - n)));
                else banNow(p, rule, reason);
            }
        }
    }

    private static String proofForLog(List<String> proof) {
        return proof == null || proof.isEmpty() ? "" : " | proof: " + String.join(" / ", proof);
    }

    private void alertTeam(Player p, String mods, String what, boolean client, Rule rule, List<String> proof) {
        boolean notify = rule == null || rule.tellTeam();
        if (!notify || !cfg().getBoolean("notify-team", true)) return;
        // the proof shows when the mouse is on the message
        var hover = Component.text().append(lang().msg("proof.title"));
        for (String line : proof == null ? List.<String>of() : proof) hover.append(Component.newline()).append(Component.text("- " + line, net.kyori.adventure.text.format.NamedTextColor.GRAY));
        hover.append(Component.newline()).append(lang().msg("proof.click"));
        Component line = lang().prefixed(client ? "alert.client" : "alert.mod", "player", p.getName(), "mods", mods, "action", what)
                .clickEvent(ClickEvent.runCommand("/modcheck " + p.getName()))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(hover.build()));
        for (Player staff : Bukkit.getOnlinePlayers()) if (staff.hasPermission("modcheck.notify")) staff.sendMessage(line);
        Bukkit.getConsoleSender().sendMessage(line);
    }

    private void banNow(Player p, Rule rule, String reason) {
        banFor(p, reason, duration(rule));
    }

    private void banFor(Player p, String reason, String dur) {
        String external = cfg().getString("ban-command", "");
        if (!external.isBlank()) {
            String safe = forCommand(reason);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), external.replace("{player}", p.getName()).replace("{duration}", forCommand(dur))
                    .replace("{reason}", safe).replace("{mod}", safe).replace("{mods}", safe));
            if (p.isOnline()) p.kick(lang().msg("kick.screen", "reason", reason));
            return;
        }
        long ms = Time.parse(dur);
        long until = ms <= 0 ? 0 : System.currentTimeMillis() + ms;
        String path = "bans." + p.getUniqueId() + ".";
        data.set(path + "name", p.getName());
        data.set(path + "until", until);
        data.set(path + "reason", reason);
        data.set(path + "by", "ModCheck");
        save();
        p.kick(lang().msg("ban.screen", "reason", reason, "time", until == 0 ? lang().raw("ban.forever") : Time.format(ms)));
    }

    private void runCommand(Player p, Rule rule, String reason, String mods) {
        String cmd = rule == null || rule.command().isBlank() ? cfg().getString("ban-command", "") : rule.command();
        if (cmd.isBlank()) {
            banNow(p, rule, reason);
            return;
        }
        for (String one : cmd.split("\\s*;\\s*")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), one.replaceFirst("^/", "").replace("{player}", p.getName())
                    .replace("{reason}", forCommand(reason)).replace("{duration}", forCommand(duration(rule)))
                    .replace("{mods}", forCommand(mods)).replace("{mod}", forCommand(mods)));
        }
    }

    // ---------------------------------------------------------------- the hidden sign

    private final java.util.Random random = new java.security.SecureRandom();
    /** How long to wait for a player to close a chest, shop or menu before the sign comes anyway. */
    private static final int MAX_WAIT_FOR_INVENTORY = 15;
    /** Where the sign of each player's current question stands: an answer for another spot is not ours. */
    private final Map<UUID, Location> signAt = new HashMap<>();

    /**
     * Mods whose presence a channel already proves. Their text must then come back filled in; if it comes
     * back empty, a filter (ExploitPreventer, OpSec ...) hides mods from the check.
     */
    record Canary(String mod, List<String> channelStarts, String key) {}

    static final List<Canary> CANARIES = List.of(
            new Canary("fabricapi", List.of("fabric:", "fabric-"), "pack.source.fabricmod"),
            new Canary("simplevoicechat", List.of("voicechat:"), "key.push_to_talk"),
            new Canary("neoforgeloader", List.of("neoforge:"), "fml.menu.mods"));

    /** The sign check with the blocked mods only (on join). */
    public void probe(Player p) {
        probe(p, false);
    }

    /** "key.xyz" also as a key binding: a mod without a language file still has its binding. */
    static List<String> withBindings(List<String> keys) {
        List<String> out = new ArrayList<>(keys);
        for (String k : keys) if (k.startsWith("key.") && !k.startsWith("key.categor")) out.add("bind:" + k);
        return out;
    }

    /**
     * The sign check: the blocked mods first (they decide on join), then the canaries and loaders for
     * the cross-check and the lie detector, with discover also every well-known mod. Only one check runs
     * per player: another one asked for meanwhile waits for it.
     */
    public void probe(Player p, boolean discover) {
        UUID id = p.getUniqueId();
        if (waiting.containsKey(id) || asked.containsKey(id)) {
            queued.merge(id, discover, Boolean::logicalOr);
            return;
        }
        if (p.isDead()) {
            // a dead player looks at the respawn screen and cannot answer a sign: ask after the respawn
            afterRespawn.merge(id, discover, Boolean::logicalOr);
            return;
        }
        List<Probe.Ask> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Rule r : rules()) {
            List<String> keys = new ArrayList<>(r.keys());
            // a known mod also brings its own up-to-date texts, so older settings files keep working
            KnownMods.Mod known = KnownMods.find(r.id());
            if (known != null) keys.addAll(known.keys());
            for (String k : withBindings(keys)) if (seen.add(k)) all.add(Probe.mod(k, r.id()));
        }
        for (Canary c : CANARIES) if (seen.add(c.key())) all.add(Probe.mod(c.key(), c.mod()));
        if (discover) for (KnownMods.Mod m : KnownMods.ALL) for (String k : withBindings(m.keys())) if (seen.add(k)) all.add(Probe.mod(k, m.id()));
        Info info = info(id);
        info.sign = "unknown";
        info.problem = null;
        info.retried = false;
        info.waitedForInventory = 0;
        info.signsDone = 0;
        waiting.put(id, all);
        nextSign(p);
    }

    /** True while the sign check for this player still runs or waits to run. */
    public boolean probing(UUID id) {
        return waiting.containsKey(id) || asked.containsKey(id) || afterRespawn.containsKey(id) || queued.containsKey(id);
    }

    /** The check is over (answered, blocked, no answer or impossible): a check asked for meanwhile runs now. */
    private void finish(Player p) {
        UUID id = p.getUniqueId();
        waiting.remove(id);
        asked.remove(id);
        signAt.remove(id);
        Boolean next = queued.remove(id);
        if (next != null && p.isOnline()) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) probe(p, next);
        }, 10L);
    }

    private static boolean ownInventory(Player p) {
        var type = p.getOpenInventory().getType();
        return type == org.bukkit.event.inventory.InventoryType.CRAFTING || type == org.bukkit.event.inventory.InventoryType.CREATIVE;
    }

    private void nextSign(Player p) {
        UUID id = p.getUniqueId();
        List<Probe.Ask> left = waiting.get(id);
        if (left == null || !p.isOnline()) {
            finish(p);
            return;
        }
        Info info = info(id);
        if (left.isEmpty()) {
            // every sign answered: now the cross-check and the lie detector, which need all answers
            finish(p);
            crossCheck(p);
            if (p.isOnline()) judge(p);
            return;
        }
        // a chest, a shop or a menu is open: the sign would close it, so wait a moment (but not forever)
        if (!ownInventory(p) && info.waitedForInventory < MAX_WAIT_FOR_INVENTORY) {
            info.waitedForInventory++;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && waiting.get(id) == left && !asked.containsKey(id)) nextSign(p);
            }, 20L);
            return;
        }
        boolean first = info.signsDone == 0;
        List<Probe.Ask> batch = new ArrayList<>(left.subList(0, Math.min(Probe.roomFor(first), left.size())));
        left.subList(0, batch.size()).clear();
        List<List<Probe.Ask>> rows = Probe.layout(Probe.controls(random, first), batch, random);
        expectRows(p, rows);
        List<List<Probe.Ask>> mine = asked.get(id);
        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < Probe.LINES; i++) lines.add(i < rows.size() ? Probe.line(rows.get(i)) : Component.empty());
        if (!virtualSign(p, lines) && !realSign(p, lines)) {
            info.sign = "impossible";
            finish(p);
            return;
        }
        // closed in the same moment it opens: both arrive together and the client handles them before it
        // draws the next picture, so the sign is never seen. Its texts are filled in when it opens, and
        // closing sends them back.
        p.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (asked.get(id) != mine) return;
            asked.remove(id);
            cleanup(p);
            if (!p.isOnline()) {
                finish(p);
                return;
            }
            if (!info.retried) {
                // no answer: lag, a screen in the way ... once more with the same questions
                info.retried = true;
                left.addAll(0, batch);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (p.isOnline() && waiting.get(id) == left) nextSign(p);
                }, 20L);
                return;
            }
            if (info.signsDone == 0) {
                // twice no answer at all: the client does not send the sign back
                info.sign = "no-answer";
                finish(p);
                suspicious(p, "no-answer", List.of(lang().raw("proof.no-answer")));
                return;
            }
            nextSign(p);
        }, 30L);
    }

    /** Waits for an answer to these sign lines (each up to three questions). */
    public void expectRows(Player p, List<List<Probe.Ask>> rows) {
        Info info = info(p.getUniqueId());
        for (String n : Probe.nonces(rows)) {
            info.oldNonces.add(n);
            if (info.oldNonces.size() > 16) info.oldNonces.remove(0);
        }
        asked.put(p.getUniqueId(), new ArrayList<>(rows.subList(0, Math.min(Probe.LINES, rows.size()))));
    }

    /** Paper 1.21.9+: a sign that only exists for this player, the world is not touched. */
    private boolean virtualSign(Player p, List<Component> lines) {
        try {
            Class<?> position = Class.forName("io.papermc.paper.math.Position");
            Location at = p.getLocation().clone();
            // a random depth under the player, never the same spot twice in a row
            at.setY(Math.max(p.getWorld().getMinHeight(), at.getBlockY() - 5 - random.nextInt(3)));
            Object pos = position.getMethod("block", int.class, int.class, int.class).invoke(null, at.getBlockX(), at.getBlockY(), at.getBlockZ());
            var open = p.getClass().getMethod("openVirtualSign", position, org.bukkit.block.sign.Side.class);
            p.sendBlockChange(at, Material.OAK_SIGN.createBlockData());
            p.sendSignChange(at, lines);
            open.invoke(p, pos, org.bukkit.block.sign.Side.FRONT);
            signAt.put(p.getUniqueId(), at.getBlock().getLocation());
            Location back = at.clone();
            restore.put(p.getUniqueId(), () -> {
                if (p.isOnline()) p.sendBlockChange(back, back.getBlock().getBlockData());
            });
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            return false;
        }
    }

    /** Blocks that must never be swapped, not even for a moment. */
    private static boolean unsafe(Block b) {
        Material m = b.getType();
        if (!m.isSolid() || !m.isOccluding() || m.getHardness() < 0 || m.getHardness() > 40) return true;
        if (b.getState() instanceof org.bukkit.block.TileState) return true;
        String n = m.name();
        return n.contains("BEDROCK") || n.contains("BARRIER") || n.contains("PORTAL") || n.contains("COMMAND") || n.contains("STRUCTURE")
                || n.contains("JIGSAW") || n.contains("SPAWNER") || n.contains("REINFORCED") || n.contains("LIGHT");
    }

    /**
     * Older Paper: a real sign deep under the player for a moment. The block that was there is kept in
     * one place for the whole server, so a second check on the same spot can never mistake our own sign
     * for the original block. While it stands it cannot be broken, blown up or pushed, and on shutdown
     * every block is put back.
     */
    private boolean realSign(Player p, List<Component> lines) {
        try {
            Block feet = p.getLocation().getBlock();
            Block spot = null;
            for (int down = 2; down <= 4 && spot == null; down++) {
                Block b = feet.getRelative(0, -down, 0);
                if (b.getY() <= p.getWorld().getMinHeight()) break;
                Location l = b.getLocation();
                if (originals.containsKey(l)) continue; // another check uses this spot right now
                if (!unsafe(b)) spot = b;
            }
            if (spot == null) return false;
            Location loc = spot.getLocation();
            originals.put(loc, spot.getBlockData());
            spot.setType(Material.OAK_SIGN, false);
            if (!(spot.getState() instanceof org.bukkit.block.Sign sign)) {
                putBack(loc);
                return false;
            }
            var side = sign.getSide(org.bukkit.block.sign.Side.FRONT);
            for (int i = 0; i < 4; i++) side.line(i, lines.get(i));
            sign.update(true, false);
            restore.put(p.getUniqueId(), () -> putBack(loc));
            signAt.put(p.getUniqueId(), loc);
            // safety net: back after two seconds whatever happens
            Bukkit.getScheduler().runTaskLater(plugin, () -> putBack(loc), 40L);
            // the block and its text must be at the client BEFORE the editor opens: the world sends its
            // own updates only at the end of the tick, and an editor on an empty sign answers nothing
            p.sendBlockChange(loc, spot.getBlockData());
            p.sendSignChange(loc, lines);
            try {
                p.openSign(sign, org.bukkit.block.sign.Side.FRONT);
            } catch (RuntimeException failed) {
                cleanup(p);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Puts the original block back where a sign of ours stands. */
    private void putBack(Location loc) {
        org.bukkit.block.data.BlockData before = originals.remove(loc);
        if (before == null) return;
        Block b = loc.getBlock();
        if (b.getType() == Material.OAK_SIGN) b.setBlockData(before, false);
    }

    /** On shutdown or reload: every block back. */
    public void putEverythingBack() {
        for (Location l : new ArrayList<>(originals.keySet())) putBack(l);
        restore.clear();
    }

    private void cleanup(Player p) {
        Runnable r = restore.remove(p.getUniqueId());
        if (r != null) r.run();
    }

    private boolean ours(Block b) {
        return originals.containsKey(b.getLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSign(SignChangeEvent e) {
        if (!ours(e.getBlock())) return;
        e.setCancelled(true);
        answer(e.getPlayer(), e.lines());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(org.bukkit.event.block.BlockBreakEvent e) {
        if (ours(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent e) {
        e.blockList().removeIf(this::ours);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent e) {
        e.blockList().removeIf(this::ours);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPistonPush(org.bukkit.event.block.BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) if (ours(b)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPistonPull(org.bukkit.event.block.BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) if (ours(b)) e.setCancelled(true);
    }

    private boolean layoutWarned;

    public void registerVirtualSignListener() {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> type = (Class<? extends Event>) Class.forName("io.papermc.paper.event.packet.UncheckedSignChangeEvent");
            Class<?> position = Class.forName("io.papermc.paper.math.Position");
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> {
                if (!type.isInstance(event)) return;
                try {
                    Player p = (Player) event.getClass().getMethod("getPlayer").invoke(event);
                    // only an answer for the spot of our own sign counts
                    Location at = signAt.get(p.getUniqueId());
                    Object pos = event.getClass().getMethod("getEditedBlockPosition").invoke(event);
                    // through the public interface: the class behind it is not public
                    int x = (int) position.getMethod("blockX").invoke(pos);
                    int y = (int) position.getMethod("blockY").invoke(pos);
                    int z = (int) position.getMethod("blockZ").invoke(pos);
                    if (at == null || at.getBlockX() != x || at.getBlockY() != y || at.getBlockZ() != z) return;
                    @SuppressWarnings("unchecked")
                    List<Component> lines = (List<Component>) event.getClass().getMethod("lines").invoke(event);
                    answer(p, lines);
                } catch (ReflectiveOperationException | RuntimeException failed) {
                    // a different event layout: say it once, so it does not fail in silence
                    if (!layoutWarned) {
                        layoutWarned = true;
                        plugin.getLogger().warning("Could not read a sign answer (" + failed + "); the sign check may not work on this server version.");
                    }
                }
            }, plugin);
        } catch (ClassNotFoundException ignored) {
            // older server: real signs instead
        }
    }

    /** Every answer that is not what a client without the mod says means the mod is there. */
    public void answer(Player p, List<Component> lines) {
        UUID id = p.getUniqueId();
        List<List<Probe.Ask>> mine = asked.get(id);
        if (mine == null) return;
        Info info = info(id);
        List<String> text = new ArrayList<>();
        for (Component c : lines) text.add(PlainTextComponentSerializer.plainText().serialize(c));
        String all = String.join("\n", text);
        // a late answer to an earlier sign (lag): it carries that sign's own words, not ours
        List<String> nonces = Probe.nonces(mine);
        boolean ours = nonces.isEmpty() || nonces.stream().anyMatch(all::contains);
        if (!ours && info.oldNonces.stream().filter(n -> !nonces.contains(n)).anyMatch(all::contains)) return;
        asked.remove(id);
        cleanup(p);
        Probe.Verdict v = Probe.judge(mine, text);
        if (!v.trusted()) {
            // the client changes texts on its own: nothing it says counts, the settings say what happens
            info.sign = "blocked";
            info.problem = v.problem();
            finish(p);
            suspicious(p, "blocked", List.of(lang().raw("proof." + v.problem())));
            return;
        }
        info.sign = "works";
        info.signsDone++;
        boolean found = false;
        for (Map.Entry<Probe.Ask, String> e : v.answers().entrySet()) {
            Probe.Ask a = e.getKey();
            if (a.kind() != Probe.Kind.MOD) continue;
            info.answered.put(a.key(), e.getValue());
            if (e.getValue().equals(a.expect())) continue;
            List<String[]> proof = info.signProof.computeIfAbsent(a.mod(), k -> new ArrayList<>());
            if (proof.size() < 4) proof.add(new String[]{a.key(), clean(e.getValue(), 60)});
            if (info.found.add(a.mod())) found = true;
        }
        if (found) judge(p);
        if (p.isOnline()) Bukkit.getScheduler().runTaskLater(plugin, () -> nextSign(p), 2L);
        else finish(p);
    }

    /**
     * The cross-check: a channel proves a mod (Fabric API, Simple Voice Chat, NeoForge), so its text
     * must have come back filled in. Empty means a filter hides mods from the check.
     */
    void crossCheck(Player p) {
        Info info = info(p.getUniqueId());
        if (!"works".equals(info.sign)) return;
        List<String> proof = new ArrayList<>();
        for (Canary c : CANARIES) {
            String got = info.answered.get(c.key());
            if (got == null || !got.isEmpty()) continue; // not asked, or filled in as it should be
            String channel = null;
            for (String ch : info.channels) for (String start : c.channelStarts()) if (channel == null && ch.toLowerCase(Locale.ROOT).startsWith(start)) channel = ch;
            if (channel != null) proof.add(lang().raw("proof.canary").replace("{channel}", channel).replace("{mod}", display(c.mod())).replace("{key}", c.key()));
        }
        if (proof.isEmpty()) return;
        info.sign = "filtered";
        suspicious(p, "filtered", proof);
    }

    private static final Set<String> SERVER_SIDE_CHANNELS = Set.of("minecraft", "bungeecord", "velocity");

    /**
     * The lie detector: a client that says it is something it is not. Plain "vanilla" with mod channels
     * or mod texts, or a client name only cheat clients send.
     */
    public List<String> disguise(Info info) {
        List<String> proof = new ArrayList<>();
        String raw = info.brand == null ? "" : info.brand.trim();
        String brand = raw.toLowerCase(Locale.ROOT);
        if (brand.equals("vanilla")) {
            for (String ch : info.channels) {
                String ns = ch.contains(":") ? ch.substring(0, ch.indexOf(':')).toLowerCase(Locale.ROOT) : ch.toLowerCase(Locale.ROOT);
                if (!SERVER_SIDE_CHANNELS.contains(ns)) {
                    proof.add(lang().raw("proof.vanilla-channel").replace("{channel}", ch));
                    break;
                }
            }
            for (String m : info.found) {
                proof.add(lang().raw("proof.vanilla-text").replace("{mod}", display(m)));
                break;
            }
        }
        if (!raw.isEmpty()) {
            for (String re : cfg().getStringList("fake-brands")) {
                try {
                    if (Pattern.compile(re).matcher(raw).matches()) proof.add(lang().raw("proof.fake-brand").replace("{brand}", raw));
                } catch (java.util.regex.PatternSyntaxException broken) {
                    // a broken pattern in the settings
                }
            }
        }
        return proof;
    }

    /**
     * Weaker hints, only shown on the player's page and in /modcheck, never punished: Lunar or Feather
     * without their own channels (a real client may simply not have sent them yet).
     */
    public List<String> hints(Info info) {
        List<String> out = new ArrayList<>();
        String brand = info.brand == null ? "" : info.brand.trim().toLowerCase(Locale.ROOT);
        Set<String> spaces = new LinkedHashSet<>();
        for (String ch : info.channels) spaces.add(ch.contains(":") ? ch.substring(0, ch.indexOf(':')).toLowerCase(Locale.ROOT) : ch.toLowerCase(Locale.ROOT));
        if (brand.startsWith("lunarclient") && spaces.stream().noneMatch(s -> s.startsWith("lunar"))) out.add(lang().raw("proof.lunar"));
        if (brand.startsWith("feather") && spaces.stream().noneMatch(s -> s.startsWith("feather"))) out.add(lang().raw("proof.feather"));
        return out;
    }

    /** Why a mod counts as found for this player: client name, channels, what the sign showed. */
    public List<String> proof(UUID id, String mod) {
        Info info = info(id);
        List<String> out = new ArrayList<>();
        Rule r = rule(mod);
        KnownMods.Mod known = KnownMods.find(mod);
        List<String> brands = r != null ? r.brand() : known != null && known.client() ? List.of(known.id()) : List.of();
        List<String> chans = r != null ? r.channel() : known != null ? known.channels() : List.of(mod);
        String brand = info.brand == null ? "" : info.brand;
        for (String b : brands) if (b.length() >= 3 && brand.toLowerCase(Locale.ROOT).contains(b)) {
            out.add(lang().raw("proof.brand").replace("{brand}", brand));
            break;
        }
        int shown = 0;
        for (String ch : info.channels) {
            boolean match = false;
            for (String c : chans) if (channelMatches(ch, c.toLowerCase(Locale.ROOT))) match = true;
            if (!match && mod.equals(ch.contains(":") ? ch.substring(0, ch.indexOf(':')).toLowerCase(Locale.ROOT) : ch)) match = true;
            if (match && shown++ < 2) out.add(lang().raw("proof.channel").replace("{channel}", ch));
        }
        for (String[] s : info.signProof.getOrDefault(mod, List.of())) {
            String key = s[0].startsWith("bind:") ? s[0].substring(5) : s[0];
            if (s[1].equals(key)) out.add(lang().raw("proof.sign-raw").replace("{key}", s[0]));
            else out.add(lang().raw(s[0].startsWith("bind:") ? "proof.bind" : "proof.sign").replace("{answer}", s[1]).replace("{key}", key));
        }
        return out;
    }

    /**
     * A client that hides from the check, never answers it, or lies about itself. Plenty of players use
     * privacy mods, so the default is only a note to the team; the owner can make it a kick or a ban.
     */
    private void suspicious(Player p, String what, List<String> proof) {
        if (!p.isOnline() || p.hasPermission("modcheck.bypass")) return;
        Info info = info(p.getUniqueId());
        if (!info.handled.add("__" + what)) return;
        info.suspicion.addAll(proof);
        String key = switch (what) {
            case "no-answer" -> "no-answer-action";
            case "disguise" -> "disguise-action";
            default -> "hidden-action";
        };
        String action = cfg().getString(key, "ALERT").toUpperCase(Locale.ROOT);
        if (!List.of("ALERT", "KICK", "WARN", "BAN").contains(action)) action = "ALERT";
        enforce(p, null, action, lang().raw("suspicious." + what), false, proof);
    }

    // ---------------------------------------------------------------- text from outside

    /**
     * Text a client sends (its name, its channels) is not to be trusted: no control characters, no
     * colour codes, not endless.
     */
    public static String clean(String text, int max) {
        if (text == null) return null;
        // colour codes go with the letter after them
        String t = text.replaceAll("\u00a7.?", "");
        StringBuilder b = new StringBuilder();
        t.codePoints().filter(c -> c >= 0x20 && !Character.isISOControl(c)).limit(max).forEach(b::appendCodePoint);
        return b.toString().trim();
    }

    /** What goes into a console command: plain words only, so it cannot become a second command or a flag. */
    static String forCommand(String text) {
        String t = clean(text == null ? "" : text, 120).replace(";", ",").replace("\\", "/");
        while (t.startsWith("-") || t.startsWith("/")) t = t.substring(1);
        return t.trim();
    }

    /** Only real Discord webhooks, nothing else on the internet. */
    public static boolean isDiscordWebhook(String url) {
        try {
            java.net.URI u = java.net.URI.create(url.trim());
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(u.getScheme()) && u.getUserInfo() == null && u.getPort() == -1
                    && Set.of("discord.com", "discordapp.com", "canary.discord.com", "ptb.discord.com").contains(host)
                    && u.getPath() != null && u.getPath().startsWith("/api/webhooks/");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String json(String text) {
        StringBuilder b = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- log and Discord

    public void log(String by, String what, String player, String detail) {
        try (FileWriter w = new FileWriter(new File(plugin.getDataFolder(), "history.log"), true)) {
            w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " | " + by + " | " + what + " | "
                    + (player == null ? "-" : player) + " | " + (detail == null ? "" : detail) + System.lineSeparator());
        } catch (IOException ignored) {
            // the log is a nice-to-have
        }
    }

    private final java.util.ArrayDeque<Long> discordSent = new java.util.ArrayDeque<>();

    private void discord(String player, String mods, String action, List<String> proof) {
        String url = cfg().getString("discord-webhook", "");
        if (url.isBlank() || !isDiscordWebhook(url)) return;
        // at most 20 messages a minute: a wave of joins must not get the webhook blocked
        long now = System.currentTimeMillis();
        while (!discordSent.isEmpty() && now - discordSent.peekFirst() > 60_000) discordSent.pollFirst();
        if (discordSent.size() >= 20) return;
        discordSent.addLast(now);
        String text = lang().raw("discord.line").replace("{player}", player).replace("{mods}", clean(mods, 200))
                .replace("{action}", lang().raw("action." + action.toLowerCase(Locale.ROOT)))
                + (proof == null || proof.isEmpty() ? "" : "\n> " + String.join("\n> ", proof.subList(0, Math.min(5, proof.size()))));
        String body = "{\"content\":\"" + json(text.replace("@", "@\u200b")) + "\",\"allowed_mentions\":{\"parse\":[]}}";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()
                        .send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(10))
                                .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding());
            } catch (IOException | InterruptedException | IllegalArgumentException ignored) {
                // Discord is optional
            }
        });
    }
}
