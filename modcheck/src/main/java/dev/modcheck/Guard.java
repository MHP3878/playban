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
        /** How the hidden sign check went: unknown, works, blocked, no-answer, impossible */
        public String sign = "unknown";
        public boolean hidingTold;
    }

    /** A blocked mod and what to do about it, all from the settings. */
    public record Rule(String id, String display, List<String> brand, List<String> channel, List<String> keys, String action,
                       String reason, String duration, int warnings, String command, boolean tellTeam, boolean discord) {}

    private final ModCheckPlugin plugin;
    private final Map<UUID, Info> infos = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> strikes = new HashMap<>();
    private final Map<UUID, Set<String>> passes = new HashMap<>();
    private final Map<UUID, List<List<Ask>>> asked = new HashMap<>();
    private final Map<UUID, List<Ask>> waiting = new HashMap<>();
    private final Map<UUID, Boolean> afterRespawn = new HashMap<>();
    private final Map<UUID, Runnable> restore = new HashMap<>();
    private final Set<Location> realSigns = new HashSet<>();
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
        if (s != null) for (String k : s.getKeys(false)) strikes.put(UUID.fromString(k), s.getInt(k));
        ConfigurationSection p = data.getConfigurationSection("passes");
        if (p != null) for (String k : p.getKeys(false)) passes.put(UUID.fromString(k), new HashSet<>(p.getStringList(k)));
    }

    private void save() {
        data.set("warnings", null);
        for (Map.Entry<UUID, Integer> e : strikes.entrySet()) data.set("warnings." + e.getKey(), e.getValue());
        data.set("passes", null);
        for (Map.Entry<UUID, Set<String>> e : passes.entrySet()) data.set("passes." + e.getKey(), new ArrayList<>(e.getValue()));
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
        ConfigurationSection s = data.getConfigurationSection("bans");
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            Ban b = new Ban(UUID.fromString(k), s.getString(k + ".name", "?"), s.getLong(k + ".until"), s.getString(k + ".reason", ""),
                    s.getString(k + ".by", "ModCheck"));
            if (b.active()) out.add(b);
        }
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

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        Ban b = ban(e.getUniqueId());
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
            for (String b : r.brand()) if (!b.isEmpty() && brand.contains(b)) hit = true;
            for (String c : r.channel()) for (String ch : info.channels) if (!c.isEmpty() && ch.toLowerCase(Locale.ROOT).contains(c)) hit = true;
            if (hit) out.add(r);
        }
        return out;
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
        if (id.isEmpty()) return null;
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
        info(e.getPlayer().getUniqueId()).channels.add(e.getChannel());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // the client name and the channels arrive a moment after joining
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            Info info = info(p.getUniqueId());
            try {
                info.brand = p.getClientBrandName();
            } catch (RuntimeException | NoSuchMethodError ignored) {
                // older API
            }
            info.channels.addAll(p.getListeningPluginChannels());
            judge(p);
        }, 60L);
    }

    /** The sign check waits until the client shows the world: a sign editor on the loading screen is lost. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoaded(io.papermc.paper.event.player.PlayerClientLoadedWorldEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && cfg().getBoolean("sign-check", true) && !probing(p.getUniqueId())) probe(p);
        }, 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Boolean discover = afterRespawn.remove(p.getUniqueId());
        if (discover == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !p.isDead() && !probing(p.getUniqueId())) probe(p, discover);
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        afterRespawn.remove(id);
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
            String client = info.brand == null ? "?" : info.brand;
            String a = cfg().getString("not-allowed-action", "KICK").toUpperCase(Locale.ROOT);
            enforce(p, null, a, client, true);
            return;
        }
        List<Rule> hits = hits(p.getUniqueId());
        if (hits.isEmpty()) return;
        Rule worst = hits.get(0);
        for (Rule r : hits) if (weight(action(r)) > weight(action(worst))) worst = r;
        List<String> names = new ArrayList<>();
        for (Rule r : hits) names.add(r.display());
        enforce(p, worst, action(worst), String.join(", ", names), false);
    }

    /**
     * "Punish" from the menu: a moderator pressed it, the settings decide what it is. For a mod that is
     * not blocked the defaults apply.
     */
    public void punish(Player p, String mod, String by) {
        Rule r = rule(mod);
        log(by, "punish", p.getName(), mod);
        enforce(p, r, action(r), display(mod), false);
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

    private void enforce(Player p, Rule rule, String action, String mods, boolean client) {
        String reason = fill(reason(rule), mods);
        // came back with a blocked mod too often in a short time: the repeat punishment instead
        String again = action.equals("ALERT") && !cfg().getBoolean("repeat.count-alerts", false) ? null : repeat(p.getUniqueId());
        if (again != null) {
            reason = reason + " " + lang().raw("repeat.suffix");
            String[] parts = again.trim().split("\\s+", 2);
            if (parts[0].equalsIgnoreCase("BAN")) {
                log("ModCheck", "repeat ban", p.getName(), mods);
                alertTeam(p, mods, lang().raw("repeat.alert-action").replace("{action}", again), client, rule);
                banFor(p, reason, parts.length > 1 ? parts[1] : "perm");
            } else {
                log("ModCheck", "repeat kick", p.getName(), mods);
                alertTeam(p, mods, lang().raw("repeat.alert-action").replace("{action}", again), client, rule);
                p.kick(lang().msg("kick.screen", "reason", reason));
            }
            return;
        }
        alertTeam(p, mods, lang().raw("action." + action.toLowerCase(Locale.ROOT)), client, rule);
        if (rule == null || rule.discord()) discord(p.getName(), mods, action);
        log("ModCheck", action.toLowerCase(Locale.ROOT), p.getName(), mods);
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

    private void alertTeam(Player p, String mods, String what, boolean client, Rule rule) {
        boolean notify = rule == null || rule.tellTeam();
        if (!notify || !cfg().getBoolean("notify-team", true)) return;
        Component line = lang().prefixed(client ? "alert.client" : "alert.mod", "player", p.getName(), "mods", mods, "action", what)
                .clickEvent(ClickEvent.runCommand("/modcheck " + p.getName()));
        for (Player staff : Bukkit.getOnlinePlayers()) if (staff.hasPermission("modcheck.notify")) staff.sendMessage(line);
        Bukkit.getConsoleSender().sendMessage(line);
    }

    private void banNow(Player p, Rule rule, String reason) {
        banFor(p, reason, duration(rule));
    }

    private void banFor(Player p, String reason, String dur) {
        String external = cfg().getString("ban-command", "");
        if (!external.isBlank()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), external.replace("{player}", p.getName()).replace("{duration}", dur)
                    .replace("{reason}", reason).replace("{mod}", reason).replace("{mods}", reason));
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
                    .replace("{reason}", reason).replace("{duration}", duration(rule)).replace("{mods}", mods).replace("{mod}", mods));
        }
    }

    // ---------------------------------------------------------------- the hidden sign

    /**
     * A sign editor for a blink with translation keys of the blocked mods. A client without the mod sends
     * the keys back as they are; with the mod, its own text comes back. Four keys per sign. On Paper 1.21.9+
     * the sign only exists for the player; on older servers a real sign stands for two ticks two blocks
     * under the player and the block is put back.
     */
    /** The sign check with the blocked mods only (on join). */
    public void probe(Player p) {
        probe(p, false);
    }

    /**
     * One question on the hidden sign: the text sent, what a client WITHOUT the mod sends back, and the
     * mod it is about. A translation key goes with an empty fallback: a client without the mod gives the
     * fallback (nothing) back, a client with the mod its own text, and a client that hides the key (old
     * Meteor) the raw key. Anything but nothing means the mod is there. "bind:" asks for a key binding:
     * a client without it gives the name back as it is.
     */
    public record Ask(Component text, String expect, String mod) {}

    public static Ask ask(String key, String mod) {
        if (key.startsWith("bind:")) {
            String id = key.substring(5);
            return new Ask(Component.keybind(id), id, mod);
        }
        return new Ask(Component.translatable().key(key).fallback("").build(), "", mod);
    }

    private static final String CONTROL = "__control";

    private static final String DUMMY = "__dummy";
    private static final String NONE = "modcheck.probe.none";

    /** "key.jump" with an empty fallback: every client fills it in, unless texts are blocked. */
    public static Ask control() {
        return new Ask(Component.translatable().key("key.jump").fallback("").build(), "", CONTROL);
    }

    /**
     * A key and a key binding no mod has: a clean client answers nothing and the name as it is. A client
     * that answers anything else changes texts on its own, and then nothing it says can be trusted.
     */
    public static List<Ask> dummies() {
        return List.of(new Ask(Component.translatable().key(NONE).fallback("").build(), "", DUMMY),
                new Ask(Component.keybind(NONE), NONE, DUMMY));
    }
    private static final String SEP = "|";
    /** Paper cuts sign lines after 80 characters: three answers fit in one line. */
    private static final int PER_LINE = 3;

    /**
     * The sign check. The first question is always the control "key.jump": every client knows it, so
     * the answer tells whether this trick works at all. With discover, the texts of well-known mods are
     * asked as well, to see more than the blocked ones.
     */
    public void probe(Player p, boolean discover) {
        if (p.isDead()) {
            // a dead player looks at the respawn screen and cannot answer a sign: ask after the respawn
            afterRespawn.put(p.getUniqueId(), discover || afterRespawn.getOrDefault(p.getUniqueId(), false));
            info(p.getUniqueId()).sign = "unknown";
            return;
        }
        List<Ask> all = new ArrayList<>();
        all.add(control());
        all.addAll(dummies());
        Set<String> seen = new HashSet<>();
        for (Rule r : rules()) {
            for (String k : r.keys()) if (seen.add(k)) all.add(ask(k, r.id()));
            // a known mod also brings its own up-to-date texts, so older settings files keep working
            KnownMods.Mod known = KnownMods.find(r.id());
            if (known != null) for (String k : known.keys()) if (seen.add(k)) all.add(ask(k, r.id()));
        }
        if (discover) for (KnownMods.Mod m : KnownMods.ALL) for (String k : m.keys()) if (seen.add(k)) all.add(ask(k, m.id()));
        info(p.getUniqueId()).sign = "unknown";
        asked.remove(p.getUniqueId());
        waiting.put(p.getUniqueId(), all);
        nextSign(p);
    }

    /** True while the sign check for this player still runs. */
    public boolean probing(UUID id) {
        return waiting.containsKey(id) || asked.containsKey(id) || afterRespawn.containsKey(id);
    }

    private void nextSign(Player p) {
        List<Ask> left = waiting.get(p.getUniqueId());
        if (left == null || left.isEmpty() || !p.isOnline()) {
            waiting.remove(p.getUniqueId());
            return;
        }
        List<Ask> batch = new ArrayList<>(left.subList(0, Math.min(4 * PER_LINE, left.size())));
        left.subList(0, batch.size()).clear();
        List<List<Ask>> rows = new ArrayList<>();
        for (int i = 0; i < batch.size(); i += PER_LINE) rows.add(new ArrayList<>(batch.subList(i, Math.min(i + PER_LINE, batch.size()))));
        expectRows(p, rows);
        List<List<Ask>> mine = asked.get(p.getUniqueId());
        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) lines.add(i < rows.size() ? line(rows.get(i)) : Component.empty());
        if (!virtualSign(p, lines) && !realSign(p, lines)) {
            asked.remove(p.getUniqueId());
            waiting.remove(p.getUniqueId());
            info(p.getUniqueId()).sign = "impossible";
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) p.closeInventory();
        }, 2L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (asked.get(p.getUniqueId()) == mine) {
                asked.remove(p.getUniqueId());
                cleanup(p);
                if (info(p.getUniqueId()).sign.equals("unknown")) {
                    // no answer at all: the client did not send the sign back
                    info(p.getUniqueId()).sign = "no-answer";
                    waiting.remove(p.getUniqueId());
                    return;
                }
                nextSign(p);
            }
        }, 30L);
    }

    private static Component line(List<Ask> row) {
        var b = Component.text();
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) b.append(Component.text(SEP));
            b.append(row.get(i).text());
        }
        return b.build();
    }

    /** Waits for an answer: one question per sign line (key, mod id), for tests and simple use. */
    public void expect(Player p, List<String[]> keysAndMods) {
        List<List<Ask>> rows = new ArrayList<>();
        for (String[] km : keysAndMods) rows.add(List.of(km[1].equals(CONTROL)
                ? control() : ask(km[0], km[1])));
        expectRows(p, rows);
    }

    /** Waits for an answer to these sign lines, each with up to three questions. */
    public void expectRows(Player p, List<List<Ask>> rows) {
        asked.put(p.getUniqueId(), new ArrayList<>(rows.subList(0, Math.min(4, rows.size()))));
    }

    private boolean virtualSign(Player p, List<Component> lines) {
        try {
            Class<?> position = Class.forName("io.papermc.paper.math.Position");
            Location at = p.getLocation().clone();
            at.setY(Math.max(p.getWorld().getMinHeight(), at.getBlockY() - 6));
            Object pos = position.getMethod("block", int.class, int.class, int.class).invoke(null, at.getBlockX(), at.getBlockY(), at.getBlockZ());
            p.sendBlockChange(at, Material.OAK_SIGN.createBlockData());
            p.sendSignChange(at, lines);
            p.getClass().getMethod("openVirtualSign", position, org.bukkit.block.sign.Side.class).invoke(p, pos, org.bukkit.block.sign.Side.FRONT);
            Location back = at.clone();
            restore.put(p.getUniqueId(), () -> p.sendBlockChange(back, back.getBlock().getBlockData()));
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            return false;
        }
    }

    private boolean realSign(Player p, List<Component> lines) {
        try {
            Block spot = p.getLocation().getBlock().getRelative(0, -2, 0);
            if (!spot.getType().isSolid() || spot.getState() instanceof org.bukkit.block.TileState || spot.getY() <= p.getWorld().getMinHeight()) return false;
            org.bukkit.block.data.BlockData before = spot.getBlockData();
            spot.setType(Material.OAK_SIGN, false);
            if (!(spot.getState() instanceof org.bukkit.block.Sign sign)) {
                spot.setBlockData(before, false);
                return false;
            }
            var side = sign.getSide(org.bukkit.block.sign.Side.FRONT);
            for (int i = 0; i < 4; i++) side.line(i, lines.get(i));
            sign.update(true, false);
            Location loc = spot.getLocation();
            realSigns.add(loc);
            restore.put(p.getUniqueId(), () -> {
                realSigns.remove(loc);
                spot.setBlockData(before, false);
            });
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (spot.getType() == Material.OAK_SIGN) {
                    realSigns.remove(loc);
                    spot.setBlockData(before, false);
                }
            }, 40L);
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

    private void cleanup(Player p) {
        Runnable r = restore.remove(p.getUniqueId());
        if (r != null) r.run();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSign(SignChangeEvent e) {
        if (!realSigns.contains(e.getBlock().getLocation())) return;
        e.setCancelled(true);
        answer(e.getPlayer(), e.lines());
    }

    public void registerVirtualSignListener() {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> type = (Class<? extends Event>) Class.forName("io.papermc.paper.event.packet.UncheckedSignChangeEvent");
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> {
                if (!type.isInstance(event)) return;
                try {
                    Player p = (Player) event.getClass().getMethod("getPlayer").invoke(event);
                    @SuppressWarnings("unchecked")
                    List<Component> lines = (List<Component>) event.getClass().getMethod("lines").invoke(event);
                    answer(p, lines);
                } catch (ReflectiveOperationException ignored) {
                    // a different event layout
                }
            }, plugin);
        } catch (ClassNotFoundException ignored) {
            // older server: real signs instead
        }
    }

    /** Every answer that is not what a client without the mod says means the mod is there. */
    public void answer(Player p, List<Component> lines) {
        List<List<Ask>> mine = asked.remove(p.getUniqueId());
        if (mine == null) return;
        cleanup(p);
        Info info = info(p.getUniqueId());
        boolean found = false;
        // first the controls, then the mods: a client that changes texts on its own gives nothing away
        Map<Ask, String> answers = new LinkedHashMap<>();
        for (int i = 0; i < mine.size() && i < lines.size(); i++) {
            List<Ask> row = mine.get(i);
            String text = PlainTextComponentSerializer.plainText().serialize(lines.get(i));
            String[] parts = text.split(Pattern.quote(SEP), -1);
            // more parts than questions: a text had the separator in it, so it is unclear which is which
            if (parts.length > row.size()) continue;
            for (int j = 0; j < parts.length; j++) answers.put(row.get(j), parts[j].trim());
        }
        for (Map.Entry<Ask, String> e : answers.entrySet()) {
            Ask a = e.getKey();
            String got = e.getValue();
            // "Jump" (in any language) back: the client fills in texts; nothing or the raw key: it does not
            if (a.mod().equals(CONTROL)) info.sign = !got.isEmpty() && !got.equals("key.jump") ? "works" : "blocked";
        }
        for (Map.Entry<Ask, String> e : answers.entrySet()) {
            if (e.getKey().mod().equals(DUMMY) && !e.getValue().equals(e.getKey().expect())) info.sign = "blocked";
        }
        if (!info.sign.equals("blocked")) {
            for (Map.Entry<Ask, String> e : answers.entrySet()) {
                Ask a = e.getKey();
                if (a.mod().equals(CONTROL) || a.mod().equals(DUMMY)) continue;
                if (!e.getValue().equals(a.expect()) && info.found.add(a.mod())) found = true;
            }
        }
        if (info.sign.equals("blocked")) {
            // the trick does not work with this client: no need to ask further, but the team may want to know
            waiting.remove(p.getUniqueId());
            if (!info.hidingTold && cfg().getBoolean("notify-team", true)) {
                info.hidingTold = true;
                Component line = lang().prefixed("alert.hiding", "player", p.getName())
                        .clickEvent(ClickEvent.runCommand("/modcheck " + p.getName()));
                for (Player staff : Bukkit.getOnlinePlayers()) if (staff.hasPermission("modcheck.notify")) staff.sendMessage(line);
                Bukkit.getConsoleSender().sendMessage(line);
            }
            return;
        }
        if (found) judge(p);
        if (p.isOnline()) Bukkit.getScheduler().runTaskLater(plugin, () -> nextSign(p), 2L);
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

    private void discord(String player, String mods, String action) {
        String url = cfg().getString("discord-webhook", "");
        if (url.isBlank() || !url.startsWith("https://")) return;
        String text = lang().raw("discord.line").replace("{player}", player).replace("{mods}", mods)
                .replace("{action}", lang().raw("action." + action.toLowerCase(Locale.ROOT)));
        String json = "{\"content\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("@", "@​")
                + "\",\"allowed_mentions\":{\"parse\":[]}}";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                        .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)).build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding());
            } catch (IOException | InterruptedException | IllegalArgumentException ignored) {
                // Discord is optional
            }
        });
    }
}
