package dev.bastion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Lumio, the client mod. A client with Lumio says so by listening on "lumio:hello"; Bastion greets it
 * with a random word, and Lumio answers with its version and the mods Fabric Loader loaded (name,
 * version, fingerprint of the jar). The list comes from the loader itself, so translation filters
 * cannot hide anything from it. It is still sent by the client, so it is cross-checked against what
 * the server saw itself (channels, the hidden sign): a mod seen there but missing in the list means
 * the list was forged.
 */
public final class Lumio implements Listener, PluginMessageListener {
    public static final String HELLO = "lumio:hello";
    public static final String REPORT = "lumio:report";
    /** Lumio 0.6+: what it found in the game itself (injected code), at the start and every 30 seconds. */
    public static final String INTEGRITY = "lumio:integrity";
    /** The first Lumio that checks the game for injected code. */
    public static final String INTEGRITY_SINCE = "0.6.0";

    /** One mod from the list: its id, name, version, jar fingerprint and the mod it is bundled in. */
    public record Mod(String id, String name, String version, String sha256, String in) {}

    /** What one client reported. */
    public static final class Report {
        public String lumio = "";
        public String minecraft = "";
        public String loader = "";
        public final List<Mod> mods = new ArrayList<>();
        /** the player's own resource packs: name, and whether Lumio found it to be an x-ray pack */
        public final List<Guard.PackSeen> packs = new ArrayList<>();
        int parts;
        final java.util.Set<Integer> seen = new java.util.HashSet<>();
        public boolean complete;

        /** The mods the player installed themselves (not the parts bundled inside other mods). */
        public List<Mod> installed() {
            boolean fabricApi = mods.stream().anyMatch(m -> m.id().equals("fabric-api"));
            List<Mod> out = new ArrayList<>();
            for (Mod m : mods) {
                if (!m.in().isEmpty() || BUILT_IN.contains(m.id())) continue;
                // the parts of Fabric API count as Fabric API (they lie loose in a development setup)
                if (fabricApi && m.id().startsWith("fabric-") && !m.id().equals("fabric-api") && !m.id().equals("fabric-language-kotlin")) continue;
                out.add(m);
            }
            return out;
        }
    }

    static final java.util.Set<String> BUILT_IN = java.util.Set.of("minecraft", "java", "fabricloader", "mixinextras");

    private final BastionPlugin plugin;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, String> nonces = new HashMap<>();

    public Lumio(BastionPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, HELLO);
        messenger.registerIncomingPluginChannel(plugin, REPORT, this);
        messenger.registerIncomingPluginChannel(plugin, INTEGRITY, this);
        // the old default let anyone in who called their client "geyser": Bedrock players are found by Floodgate/Geyser now
        List<String> old = plugin.getConfig().getStringList("lumio.exempt-brands");
        if (old.size() == 2 && old.contains("geyser") && old.contains("floodgate")) {
            plugin.getConfig().set("lumio.exempt-brands", List.of());
            plugin.saveConfig();
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::heartbeat, 200L, 200L);
    }

    /** Whether the injection check is on (then Lumio 0.6+ is needed in required mode). */
    public boolean injectionCheck() {
        return plugin.getConfig().getBoolean("injection.enabled", true);
    }

    /** Too old for this server: below min-version, or (with the injection check) below 0.6.0. */
    public boolean outdated(Report r) {
        if (r == null || r.lumio.isBlank()) return false;
        String min = plugin.getConfig().getString("lumio.min-version", "");
        if (!min.isBlank() && compare(r.lumio, min) < 0) return true;
        return injectionCheck() && compare(r.lumio, INTEGRITY_SINCE) < 0;
    }

    /** Whether this Lumio says what it finds in the game (0.6+). */
    static boolean checksGame(Report r) {
        return r != null && !r.lumio.isBlank() && compare(r.lumio, INTEGRITY_SINCE) >= 0;
    }

    /** In required mode a Lumio 0.6+ that stops reporting was switched off or blocked: out. */
    void heartbeat() {
        if (!mode().equals("required") || !injectionCheck()) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Guard.Info info = guard().info(p.getUniqueId());
            if (exempt(p) || info.lumio == null || !info.lumio.complete || !checksGame(info.lumio) || info.lumioAt == 0) continue;
            long last = info.integrityAt == 0 ? info.lumioAt : info.integrityAt;
            if (now - last > 100_000L) kick(p, "lumio.kick-silent");
        }
    }

    public static final List<String> MODES = List.of("off", "optional", "required");

    public String mode() {
        String m = plugin.getConfig().getString("lumio.mode", "optional").toLowerCase(Locale.ROOT);
        return MODES.contains(m) ? m : "optional";
    }

    /** "aus", "freiwillig", "pflicht" work as well as "off", "optional", "required"; null if unknown. */
    public static String parseMode(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "off", "aus" -> "off";
            case "optional", "freiwillig" -> "optional";
            case "required", "pflicht", "on", "an" -> "required";
            default -> null;
        };
    }

    public void setMode(String mode) {
        plugin.getConfig().set("lumio.mode", mode);
        plugin.saveConfig();
    }

    /** How many players online have no Lumio (and are not exempt). */
    public int onlineWithout() {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (!p.getListeningPluginChannels().contains(HELLO) && !exempt(p)) n++;
        return n;
    }

    /**
     * Tells the owner what the current mode means, and what it costs: without Lumio being required,
     * Bastion cannot know every mod.
     */
    public void explain(org.bukkit.command.CommandSender to) {
        String mode = mode();
        plugin.lang().send(to, "lumio.status", "mode", plugin.lang().raw("lumio.mode-name." + mode));
        to.sendMessage(plugin.lang().msg("lumio.explain-" + mode));
        if (!mode.equals("required")) {
            to.sendMessage(plugin.lang().msg("lumio.warn-incomplete"));
        } else {
            to.sendMessage(plugin.lang().msg("lumio.warn-required", "count", onlineWithout()));
            if (plugin.getConfig().getString("lumio.download", "").isBlank()) to.sendMessage(plugin.lang().msg("lumio.warn-no-download"));
        }
    }

    private Guard guard() {
        return plugin.guard();
    }

    /**
     * A Bedrock player (Geyser/Floodgate), asked from those plugins themselves: a client name can be
     * faked, their answer cannot.
     */
    public boolean bedrock(Player p) {
        try {
            if (Bukkit.getPluginManager().getPlugin("floodgate") != null) {
                Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Object inst = api.getMethod("getInstance").invoke(null);
                if ((boolean) api.getMethod("isFloodgatePlayer", UUID.class).invoke(inst, p.getUniqueId())) return true;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // another Floodgate version
        }
        try {
            if (Bukkit.getPluginManager().getPlugin("Geyser-Spigot") != null) {
                Class<?> api = Class.forName("org.geysermc.geyser.api.GeyserApi");
                Object inst = api.getMethod("api").invoke(null);
                if ((boolean) api.getMethod("isBedrockPlayer", UUID.class).invoke(inst, p.getUniqueId())) return true;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // another Geyser version
        }
        return false;
    }

    /** Lumio is not needed: staff with bypass, Bedrock players (Geyser/Floodgate), listed client names. */
    boolean exempt(Player p) {
        if (p.hasPermission("bastion.bypass")) return true;
        if (bedrock(p)) return true;
        String brand = guard().info(p.getUniqueId()).brand;
        if (brand == null) return false;
        for (String b : plugin.getConfig().getStringList("lumio.exempt-brands")) {
            if (!b.isBlank() && brand.toLowerCase(Locale.ROOT).contains(b.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChannel(PlayerRegisterChannelEvent e) {
        if (e.getChannel().equals(HELLO)) hello(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // the client may have said it listens before the join already
        if (p.getListeningPluginChannels().contains(HELLO)) hello(p);
        if (!mode().equals("required")) return;
        int wait = Math.max(5, plugin.getConfig().getInt("lumio.wait-seconds", 15));
        // without Lumio at all: out after two seconds, before the player is busy in the world
        // (in the check room with "stay" the room shows a window instead)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !p.getListeningPluginChannels().contains(HELLO) && !exempt(p) && !roomHandles(p)) kick(p, "lumio.kick-missing");
        }, 40L);
        // with Lumio but no list: out after the waiting time
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Report r = guard().info(p.getUniqueId()).lumio;
            if (p.isOnline() && (r == null || !r.complete) && !exempt(p) && !roomHandles(p)) kick(p, "lumio.kick-no-report");
        }, wait * 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        nonces.remove(e.getPlayer().getUniqueId());
    }

    private boolean roomHandles(Player p) {
        return plugin.room() != null && plugin.room().handlesMissingLumio(p);
    }

    void kick(Player p, String key) {
        String download = plugin.getConfig().getString("lumio.download", "");
        p.kick(plugin.lang().msg(key, "download", download.isBlank() ? plugin.lang().raw("lumio.ask-team") : download,
                "min", plugin.getConfig().getString("lumio.min-version", "")));
        guard().log("Bastion", "lumio kick", p.getName(), key);
    }

    /** Greets a Lumio client once per session, with a fresh random word it must send back. */
    void hello(Player p) {
        if (mode().equals("off") || nonces.containsKey(p.getUniqueId())) return;
        byte[] word = new byte[12];
        random.nextBytes(word);
        String nonce = HexFormat.of().formatHex(word);
        nonces.put(p.getUniqueId(), nonce);
        Report r = new Report();
        guard().info(p.getUniqueId()).lumio = r;
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("nonce", nonce);
        o.addProperty("server", plugin.getConfig().getString("lumio.server-name", ""));
        o.addProperty("latest", plugin.getConfig().getString("lumio.latest-version", ""));
        o.addProperty("download", plugin.getConfig().getString("lumio.download", ""));
        // Lumio 0.6+: check the game for injected code, and for these programs running next to it
        o.addProperty("integrity", injectionCheck());
        // and which resource packs are on (x-ray packs)
        o.addProperty("packs", plugin.getConfig().getBoolean("packs.enabled", true));
        JsonArray watch = new JsonArray();
        List<String> names = new ArrayList<>(KnownInjects.allProcesses());
        names.addAll(plugin.getConfig().getStringList("injection.processes"));
        for (String w : names) {
            String c = Guard.clean(w, 40).toLowerCase(Locale.ROOT);
            if (!c.isEmpty() && watch.size() < 128 && !watch.contains(new com.google.gson.JsonPrimitive(c))) watch.add(c);
        }
        o.add("watch", watch);
        p.sendPluginMessage(plugin, HELLO, o.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void onPluginMessageReceived(String channel, Player p, byte[] message) {
        if (INTEGRITY.equals(channel) && message.length <= 32_767) {
            try {
                JsonElement e = JsonParser.parseString(new String(message, StandardCharsets.UTF_8));
                if (e.isJsonObject()) integrity(p, e.getAsJsonObject());
            } catch (RuntimeException broken) {
                // not ours
            }
            return;
        }
        if (!REPORT.equals(channel) || message.length > 32_767) return;
        String nonce = nonces.get(p.getUniqueId());
        Report r = guard().info(p.getUniqueId()).lumio;
        if (nonce == null || r == null || r.complete) return;
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(new String(message, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) return;
            o = e.getAsJsonObject();
        } catch (RuntimeException broken) {
            return;
        }
        // only an answer to our own greeting counts
        if (!nonce.equals(str(o, "nonce", 64))) return;
        int part = num(o, "part");
        int parts = num(o, "parts");
        if (parts < 1 || parts > 40 || part < 1 || part > parts) return;
        if (r.parts == 0) r.parts = parts;
        if (parts != r.parts) return;
        // each part once: a part sent twice does not count twice
        if (!r.seen.add(part)) return;
        if (part == 1) {
            r.lumio = str(o, "lumio", 24);
            r.minecraft = str(o, "minecraft", 24);
            r.loader = str(o, "loader", 40);
            JsonElement packs = o.get("packs");
            if (packs != null && packs.isJsonArray()) {
                for (JsonElement pe : packs.getAsJsonArray()) {
                    if (!pe.isJsonObject() || r.packs.size() >= 40) continue;
                    JsonObject x = pe.getAsJsonObject();
                    String name = str(x, "name", 64);
                    boolean xray = x.has("xray") && x.get("xray").isJsonPrimitive() && x.get("xray").getAsBoolean();
                    if (!name.isEmpty()) r.packs.add(new Guard.PackSeen(name, xray, str(x, "why", 80)));
                }
            }
        }
        JsonElement mods = o.get("mods");
        if (mods != null && mods.isJsonArray()) {
            for (JsonElement m : (JsonArray) mods) {
                if (!m.isJsonObject() || r.mods.size() >= 3000) continue;
                JsonObject x = m.getAsJsonObject();
                String id = str(x, "id", 64).toLowerCase(Locale.ROOT);
                if (id.isEmpty()) continue;
                r.mods.add(new Mod(id, str(x, "name", 64), str(x, "version", 40), str(x, "sha256", 64), str(x, "in", 64).toLowerCase(Locale.ROOT)));
            }
        }
        if (r.seen.size() < r.parts) return;
        r.complete = true;
        guard().info(p.getUniqueId()).lumioAt = System.currentTimeMillis();
        plugin.akte().lumio(p.getUniqueId(), r.lumio);
        if (!r.packs.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Guard.PackSeen ps : r.packs) names.add(ps.name());
            plugin.akte().packs(p.getUniqueId(), names);
            guard().packs(p, r.packs);
            if (!p.isOnline()) return;
        }
        guard().log("Bastion", "lumio report", p.getName(), "Lumio " + r.lumio + ", " + r.installed().size() + " mods");
        if (mode().equals("required") && outdated(r) && !exempt(p) && !roomHandles(p)) {
            kick(p, "lumio.kick-outdated");
            return;
        }
        guard().judge(p);
    }

    /**
     * What Lumio found in the game itself: code injected from outside (Java agents, foreign DLLs,
     * threads that start in no file, classes without origin), loaders running next to the game. A
     * strong finding goes to injection-action, a weak one only to the team.
     */
    void integrity(Player p, JsonObject o) {
        Guard.Info info = guard().info(p.getUniqueId());
        String nonce = nonces.get(p.getUniqueId());
        if (nonce == null || !nonce.equals(str(o, "nonce", 64))) return;
        info.integrityAt = System.currentTimeMillis();
        JsonElement f = o.get("findings");
        if (f == null || !f.isJsonArray()) return;
        List<String> strong = new ArrayList<>();
        List<String> weak = new ArrayList<>();
        // the small signs that strict mode may act on: something attached to the running game, or Java
        // code from an unknown file. DLLs and libraries in odd folders never: overlays, drivers and
        // antivirus programs put them into every game
        List<String> weakStrict = new ArrayList<>();
        Map<KnownInjects.Client, List<String>> named = new java.util.LinkedHashMap<>();
        List<Guard.PackSeen> packsNow = new ArrayList<>();
        String client = "";
        int n = 0;
        for (JsonElement e : f.getAsJsonArray()) {
            if (!e.isJsonObject() || n++ >= 32) continue;
            JsonObject x = e.getAsJsonObject();
            String kind = str(x, "kind", 24).toLowerCase(Locale.ROOT);
            String what = str(x, "what", 120);
            String name = str(x, "name", 40);
            boolean isStrong = x.has("strong") && x.get("strong").isJsonPrimitive() && x.get("strong").getAsBoolean();
            if (!kind.matches("[a-z-]{2,24}") || what.isEmpty()) continue;
            if (kind.equals("pack")) {
                // a resource pack is no injection: it goes its own way (x-ray packs)
                packsNow.add(new Guard.PackSeen(what.replaceAll(" \\(.*\\)$", ""), isStrong, what));
                continue;
            }
            String template = plugin.lang().raw("injection.kind." + kind);
            String line = template.contains("missing text") ? kind + ": " + what : template.replace("{what}", what);
            if (info.integrity.size() < 64 && !info.integrity.contains(line)) {
                info.integrity.add(line);
                // in the player's file: what Lumio saw, sure findings and small signs alike
                plugin.akte().event(p.getUniqueId(), isStrong ? "inject" : "sign", line, "Lumio");
            }
            // a known injection client, by its program or the beginning of its class names
            KnownInjects.Client c = KnownInjects.match(kind, what);
            if (c == null && !name.isEmpty()) c = KnownInjects.find(name);
            if (c != null) {
                named.computeIfAbsent(c, k -> new ArrayList<>()).add(line);
            } else if (isStrong) {
                strong.add(line);
                if (client.isEmpty() && !name.isEmpty()) client = name;
            } else {
                weak.add(line);
                if (STRICT_KINDS.contains(kind)) weakStrict.add(line);
            }
        }
        if (!packsNow.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Guard.PackSeen ps : packsNow) names.add(ps.name());
            plugin.akte().packs(p.getUniqueId(), names);
            guard().packs(p, packsNow);
            if (!p.isOnline()) return;
        }
        decide(p, named, strong, weak, weakStrict, client);
    }

    /**
     * What the findings mean on this server (/inject): a known client gets the action set for it;
     * with "block all" every sure finding is out, known client or not (a known client counts as sure);
     * with "strict" small signs too. The rest only goes to the team.
     */
    /** The kinds of small signs strict mode acts on (with a kick, never a ban). */
    static final java.util.Set<String> STRICT_KINDS = java.util.Set.of("attach", "codefile", "codeloader", "agent");

    void decide(Player p, Map<KnownInjects.Client, List<String>> named, List<String> strong, List<String> weak, List<String> weakStrict, String unnamed) {
        String general = plugin.getConfig().getString("injection-action", "KICK");
        boolean all = blockAll(), strict = strict();
        List<String> onlyHints = new ArrayList<>();
        for (Map.Entry<KnownInjects.Client, List<String>> e : named.entrySet()) {
            String own = clientAction(e.getKey().id());
            if (own != null) {
                guard().injection(p, e.getKey().name(), e.getValue(), own);
            } else if (all || strict) {
                guard().injection(p, e.getKey().name(), e.getValue(), general);
            } else {
                onlyHints.addAll(e.getValue());
            }
            if (!p.isOnline()) return;
        }
        if (!strong.isEmpty()) {
            // code from somewhere unknown: no known client was recognised, so no proof; at most a kick
            if (all) guard().injection(p, unnamed.isEmpty() ? plugin.lang().raw("injection.unknown") : unnamed, strong, general, false);
            else onlyHints.addAll(strong);
            if (!p.isOnline()) return;
        }
        if (!weak.isEmpty()) {
            // signs are no proof: at most a kick (never a ban, never counted towards the repeat ban)
            if (strict && !weakStrict.isEmpty()) guard().injectionSigns(p, weakStrict);
            List<String> rest = new ArrayList<>(weak);
            if (strict) rest.removeAll(weakStrict);
            onlyHints.addAll(rest);
            if (!p.isOnline()) return;
        }
        if (!onlyHints.isEmpty()) guard().hints(p, onlyHints);
    }

    /** /inject: every sure finding is out, known client or not. */
    public boolean blockAll() {
        return plugin.getConfig().getBoolean("injection.block-all", true);
    }

    /** /inject: also small signs (a DLL from an unusual folder, a tool attached) are out. */
    public boolean strict() {
        return plugin.getConfig().getBoolean("injection.strict", false);
    }

    /** The action set for one client with /inject (KICK, BAN, ALERT), or null: it follows "block all". */
    public String clientAction(String id) {
        String a = plugin.getConfig().getString("injection.clients." + id, "");
        a = a == null ? "" : a.toUpperCase(Locale.ROOT);
        return List.of("KICK", "BAN", "ALERT").contains(a) ? a : null;
    }

    public void setClientAction(String id, String action) {
        plugin.getConfig().set("injection.clients." + id, action == null ? null : action.toUpperCase(Locale.ROOT));
        plugin.saveConfig();
    }

    /** The general action for injections (KICK, BAN, ALERT). */
    public String injectionAction() {
        String a = plugin.getConfig().getString("injection-action", "KICK").toUpperCase(Locale.ROOT);
        return List.of("KICK", "BAN", "ALERT").contains(a) ? a : "KICK";
    }

    /** Program names the server added itself, besides those of the known clients. */
    public List<String> ownProcesses() {
        List<String> out = new ArrayList<>();
        for (String w : plugin.getConfig().getStringList("injection.processes")) {
            String c = processName(w);
            if (c != null && !out.contains(c) && !KnownInjects.allProcesses().contains(c)) out.add(c);
        }
        return out;
    }

    /** A program name as Lumio compares it ("Vape.exe" -> "vape"), or null when it is no usable name. */
    public static String processName(String typed) {
        if (typed == null) return null;
        String c = typed.trim().toLowerCase(Locale.ROOT).replaceAll("\\.exe$", "");
        return c.matches("[a-z0-9_.+-]{2,40}") ? c : null;
    }

    /** Adds a program name, or takes it away when it is already there: "added", "removed", "known" or "bad". */
    public String toggleOwnProcess(String typed) {
        String c = processName(typed);
        if (c == null) return "bad";
        if (KnownInjects.allProcesses().contains(c)) return "known";
        List<String> own = ownProcesses();
        String result;
        if (own.remove(c)) result = "removed";
        else if (own.size() >= 40) return "bad";
        else {
            own.add(c);
            result = "added";
        }
        plugin.getConfig().set("injection.processes", own);
        plugin.saveConfig();
        return result;
    }

    private static String str(JsonObject o, String key, int max) {
        try {
            JsonElement e = o.get(key);
            return e == null || e.isJsonNull() ? "" : Guard.clean(e.getAsString(), max);
        } catch (RuntimeException wrong) {
            return "";
        }
    }

    private static int num(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return e == null || e.isJsonNull() ? 0 : e.getAsInt();
        } catch (RuntimeException wrong) {
            return 0;
        }
    }

    /** "0.1.0" against "0.2"; whatever follows "+" or "-" does not count. */
    static int compare(String a, String b) {
        String[] x = a.split("[+-]")[0].split("\\.");
        String[] y = b.split("[+-]")[0].split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? digits(x[i]) : 0;
            int q = i < y.length ? digits(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int digits(String s) {
        int n = 0;
        for (char c : s.toCharArray()) {
            if (c < '0' || c > '9' || n > 1_000_000) break;
            n = n * 10 + (c - '0');
        }
        return n;
    }
}
