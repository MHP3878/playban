package dev.playban;

import dev.playban.model.Note;
import dev.playban.model.Punishment;
import dev.playban.model.Review;
import dev.playban.model.TaskStatus;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Everything PlayBan remembers, kept in data.yml. */
public final class DataStore {
    private final PlayBanPlugin plugin;
    public final Map<UUID, Punishment> active = new LinkedHashMap<>();
    /** Real bans. Read from the login thread, so this one is thread-safe. */
    public final Map<UUID, Punishment> bans = new java.util.concurrent.ConcurrentHashMap<>();
    public final Map<UUID, List<Punishment>> history = new LinkedHashMap<>();
    public final Map<String, Review> reviews = new LinkedHashMap<>();
    /** Tickets from PlayBan 0.3 and older (author, name, text, time), handed to the ticket system once. */
    public final List<String[]> legacyTickets = new ArrayList<>();
    /** Messages for players who were offline, as MiniMessage text. */
    public final Map<UUID, List<String>> mail = new LinkedHashMap<>();
    public final Map<UUID, List<Note>> notes = new LinkedHashMap<>();
    /** Last known name of every player PlayBan has dealt with, so profiles work while they are offline. */
    public final Map<UUID, String> names = new LinkedHashMap<>();
    /** Salted fingerprint of the last address of each player, to spot second accounts. Raw addresses are never stored. */
    public final Map<UUID, String> addresses = new LinkedHashMap<>();
    private String addressSalt = UUID.randomUUID().toString();
    private boolean dirty;
    private long counter;

    public DataStore(PlayBanPlugin plugin) {
        this.plugin = plugin;
    }

    private File file() {
        return new File(plugin.getDataFolder(), "data.yml");
    }

    public void markDirty() {
        dirty = true;
    }

    public String nextId() {
        return Long.toString(System.currentTimeMillis(), 36) + Long.toString(counter++, 36);
    }

    public void load() {
        if (!file().exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file());
        ConfigurationSection a = cfg.getConfigurationSection("active");
        if (a != null) for (String k : a.getKeys(false)) {
            Punishment p = readPunishment(a.getConfigurationSection(k));
            if (p != null) active.put(p.uuid, p);
        }
        ConfigurationSection b = cfg.getConfigurationSection("bans");
        if (b != null) for (String k : b.getKeys(false)) {
            Punishment p = readPunishment(b.getConfigurationSection(k));
            if (p != null) bans.put(p.uuid, p);
        }
        ConfigurationSection h = cfg.getConfigurationSection("history");
        if (h != null) for (String k : h.getKeys(false)) {
            ConfigurationSection per = h.getConfigurationSection(k);
            if (per == null) continue;
            List<Punishment> list = new ArrayList<>();
            for (String n : per.getKeys(false)) {
                Punishment p = readPunishment(per.getConfigurationSection(n));
                if (p != null) list.add(p);
            }
            if (!list.isEmpty()) history.put(list.get(0).uuid, list);
        }
        ConfigurationSection r = cfg.getConfigurationSection("reviews");
        if (r != null) for (String k : r.getKeys(false)) {
            ConfigurationSection s = r.getConfigurationSection(k);
            if (s == null) continue;
            Review rv = new Review();
            rv.id = k;
            rv.author = UUID.fromString(s.getString("author", ""));
            rv.authorName = s.getString("author-name", "?");
            rv.taskId = s.getString("task", "");
            String target = s.getString("target");
            rv.target = target == null ? null : UUID.fromString(target);
            rv.targetName = s.getString("target-name");
            rv.text = s.getString("text", "");
            rv.createdAt = s.getLong("created");
            reviews.put(k, rv);
        }
        ConfigurationSection t = cfg.getConfigurationSection("tickets");
        if (t != null) for (String k : t.getKeys(false)) {
            ConfigurationSection s = t.getConfigurationSection(k);
            if (s == null) continue;
            legacyTickets.add(new String[]{s.getString("author", ""), s.getString("author-name", "?"), s.getString("text", ""), String.valueOf(s.getLong("created"))});
        }
        ConfigurationSection ns = cfg.getConfigurationSection("notes");
        if (ns != null) for (String k : ns.getKeys(false)) {
            ConfigurationSection per = ns.getConfigurationSection(k);
            if (per == null) continue;
            List<Note> list = new ArrayList<>();
            for (String id : per.getKeys(false)) {
                Note n = new Note();
                n.id = id;
                n.author = per.getString(id + ".author", "?");
                n.text = per.getString(id + ".text", "");
                n.createdAt = per.getLong(id + ".created");
                list.add(n);
            }
            notes.put(UUID.fromString(k), list);
        }
        ConfigurationSection nm = cfg.getConfigurationSection("names");
        if (nm != null) for (String k : nm.getKeys(false)) names.put(UUID.fromString(k), nm.getString(k));
        addressSalt = cfg.getString("address-salt", addressSalt);
        ConfigurationSection ad = cfg.getConfigurationSection("addresses");
        if (ad != null) for (String k : ad.getKeys(false)) addresses.put(UUID.fromString(k), ad.getString(k));
        ConfigurationSection m = cfg.getConfigurationSection("mail");
        if (m != null) for (String k : m.getKeys(false)) mail.put(UUID.fromString(k), new ArrayList<>(m.getStringList(k)));
    }

    /** Names of other known players who last joined from the same address. */
    public List<String> sameAddress(UUID id) {
        List<String> out = new ArrayList<>();
        String mine = addresses.get(id);
        if (mine == null) return out;
        for (Map.Entry<UUID, String> e : addresses.entrySet()) {
            if (!e.getKey().equals(id) && e.getValue().equals(mine) && names.containsKey(e.getKey())) out.add(names.get(e.getKey()));
        }
        return out;
    }

    /**
     * Remembers the address a player joined from and returns the names of banned or punished players
     * who share it, so the team can be warned.
     */
    public List<String> rememberAddress(org.bukkit.entity.Player player) {
        List<String> flagged = new ArrayList<>();
        if (player.getAddress() == null || player.getAddress().getAddress() == null) return flagged;
        String print;
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((addressSalt + player.getAddress().getAddress().getHostAddress()).getBytes(StandardCharsets.UTF_8));
            print = java.util.HexFormat.of().formatHex(hash, 0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            return flagged;
        }
        String before = addresses.put(player.getUniqueId(), print);
        names.put(player.getUniqueId(), player.getName());
        markDirty();
        if (print.equals(before)) return flagged;
        for (Map.Entry<UUID, String> e : addresses.entrySet()) {
            UUID other = e.getKey();
            if (other.equals(player.getUniqueId()) || !e.getValue().equals(print)) continue;
            if (active.containsKey(other) || bans.containsKey(other)) flagged.add(names.getOrDefault(other, "?"));
        }
        return flagged;
    }

    private Punishment readPunishment(ConfigurationSection s) {
        if (s == null) return null;
        try {
            Punishment p = new Punishment();
            p.uuid = UUID.fromString(s.getString("uuid", ""));
            p.name = s.getString("name", "?");
            p.type = s.getString("type", "PLAYBAN");
            p.category = s.getString("category");
            p.expiresAt = s.getLong("expires");
            p.prevMode = s.getString("prev-mode");
            p.prevLocation = s.getString("prev-location");
            p.reason = s.getString("reason", "");
            p.staff = s.getString("staff", "?");
            p.createdAt = s.getLong("created");
            p.originalMs = s.getLong("original");
            p.remainingMs = s.getLong("remaining");
            p.lastUpdate = s.getLong("last-update", System.currentTimeMillis());
            String victim = s.getString("victim");
            p.victim = victim == null ? null : UUID.fromString(victim);
            p.victimName = s.getString("victim-name");
            p.endedAt = s.getLong("ended");
            p.endReason = s.getString("end-reason");
            ConfigurationSection ts = s.getConfigurationSection("tasks");
            if (ts != null) for (String id : ts.getKeys(false)) {
                TaskStatus st;
                try {
                    st = TaskStatus.valueOf(ts.getString(id + ".status", "OPEN"));
                } catch (IllegalArgumentException e) {
                    st = TaskStatus.OPEN;
                }
                p.tasks.put(id, new Punishment.TaskProgress(st, ts.getLong(id + ".time-left")));
            }
            return p;
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("data.yml: skipped a broken punishment entry (" + s.getCurrentPath() + ")");
            return null;
        }
    }

    private void writePunishment(YamlConfiguration cfg, String path, Punishment p) {
        cfg.set(path + ".uuid", p.uuid.toString());
        cfg.set(path + ".name", p.name);
        cfg.set(path + ".type", p.type);
        if (p.category != null) cfg.set(path + ".category", p.category);
        if (p.expiresAt > 0) cfg.set(path + ".expires", p.expiresAt);
        if (p.prevMode != null) {
            cfg.set(path + ".prev-mode", p.prevMode);
            cfg.set(path + ".prev-location", p.prevLocation);
        }
        cfg.set(path + ".reason", p.reason);
        cfg.set(path + ".staff", p.staff);
        cfg.set(path + ".created", p.createdAt);
        cfg.set(path + ".original", p.originalMs);
        cfg.set(path + ".remaining", p.remainingMs);
        cfg.set(path + ".last-update", p.lastUpdate);
        if (p.victim != null) {
            cfg.set(path + ".victim", p.victim.toString());
            cfg.set(path + ".victim-name", p.victimName);
        }
        if (p.endedAt > 0) {
            cfg.set(path + ".ended", p.endedAt);
            cfg.set(path + ".end-reason", p.endReason);
        }
        for (Map.Entry<String, Punishment.TaskProgress> e : p.tasks.entrySet()) {
            cfg.set(path + ".tasks." + e.getKey() + ".status", e.getValue().status.name());
            cfg.set(path + ".tasks." + e.getKey() + ".time-left", e.getValue().timeLeftMs);
        }
    }

    private String serialize() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Punishment p : active.values()) writePunishment(cfg, "active." + p.uuid, p);
        for (Punishment p : bans.values()) writePunishment(cfg, "bans." + p.uuid, p);
        for (Map.Entry<UUID, List<Punishment>> e : history.entrySet()) {
            int i = 0;
            for (Punishment p : e.getValue()) writePunishment(cfg, "history." + e.getKey() + "." + (i++), p);
        }
        for (Review r : reviews.values()) {
            String p = "reviews." + r.id + ".";
            cfg.set(p + "author", r.author.toString());
            cfg.set(p + "author-name", r.authorName);
            cfg.set(p + "task", r.taskId);
            if (r.target != null) {
                cfg.set(p + "target", r.target.toString());
                cfg.set(p + "target-name", r.targetName);
            }
            cfg.set(p + "text", r.text);
            cfg.set(p + "created", r.createdAt);
        }
        for (Map.Entry<UUID, List<Note>> e : notes.entrySet()) {
            for (Note n : e.getValue()) {
                String p = "notes." + e.getKey() + "." + n.id + ".";
                cfg.set(p + "author", n.author);
                cfg.set(p + "text", n.text);
                cfg.set(p + "created", n.createdAt);
            }
        }
        for (Map.Entry<UUID, String> e : names.entrySet()) cfg.set("names." + e.getKey(), e.getValue());
        cfg.set("address-salt", addressSalt);
        for (Map.Entry<UUID, String> e : addresses.entrySet()) cfg.set("addresses." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, List<String>> e : mail.entrySet()) {
            if (!e.getValue().isEmpty()) cfg.set("mail." + e.getKey(), e.getValue());
        }
        return cfg.saveToString();
    }

    private void write(String content) {
        try {
            File target = file();
            target.getParentFile().mkdirs();
            File tmp = new File(target.getParentFile(), "data.yml.tmp");
            Files.writeString(tmp.toPath(), content, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save data.yml: " + e.getMessage());
        }
    }

    /** Called regularly from the main thread; the file write itself happens off-thread. */
    public void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        String content = serialize();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (this) {
                write(content);
            }
        });
    }

    public void saveNow() {
        dirty = false;
        String content = serialize();
        synchronized (this) {
            write(content);
        }
    }
}
