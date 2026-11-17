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
    /** UUIDs of everyone with a running PlayBan, readable from the chat thread. */
    private final java.util.Set<UUID> punishedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public final Map<UUID, Punishment> active = new LinkedHashMap<>() {
        @Override
        public Punishment put(UUID key, Punishment value) {
            punishedIds.add(key);
            return super.put(key, value);
        }

        @Override
        public Punishment remove(Object key) {
            punishedIds.remove(key);
            return super.remove(key);
        }
    };
    /** Punishments that ended while the player was offline: game mode and items still to give back. */
    public final Map<UUID, Punishment> restores = new LinkedHashMap<>();
    /** Players who had playban.exempt the last time they were online, so they stay protected while offline. */
    public final java.util.Set<UUID> exempt = new java.util.HashSet<>();
    /** true when data.yml could not be read: then nothing is saved, so the broken file is not overwritten. */
    private boolean loadFailed;
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

    public boolean isPunished(UUID id) {
        return punishedIds.contains(id);
    }

    public boolean loadFailed() {
        return loadFailed;
    }

    public void load() {
        if (!file().exists()) return;
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.load(file());
            read(cfg);
        } catch (Exception e) {
            loadFailed = true;
            File backup = new File(plugin.getDataFolder(), "data.yml.broken-" + System.currentTimeMillis());
            try {
                Files.copy(file().toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // the original stays untouched anyway, because nothing is saved
            }
            plugin.getLogger().severe("data.yml could not be read: " + e.getMessage());
            plugin.getLogger().severe("PlayBan will NOT save anything until this is fixed, so no data is lost. A copy is in "
                    + backup.getName() + ". Fix the file and restart.");
        }
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void read(YamlConfiguration cfg) {
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
            rv.author = uuid(s.getString("author", ""));
            if (rv.author == null) continue;
            rv.authorName = s.getString("author-name", "?");
            rv.taskId = s.getString("task", "");
            String target = s.getString("target");
            rv.target = target == null ? null : uuid(target);
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
            if (uuid(k) != null) notes.put(uuid(k), list);
        }
        ConfigurationSection nm = cfg.getConfigurationSection("names");
        if (nm != null) for (String k : nm.getKeys(false)) if (uuid(k) != null) names.put(uuid(k), nm.getString(k));
        ConfigurationSection rs = cfg.getConfigurationSection("restores");
        if (rs != null) for (String k : rs.getKeys(false)) {
            Punishment p = readPunishment(rs.getConfigurationSection(k));
            if (p != null) restores.put(p.uuid, p);
        }
        for (String k : cfg.getStringList("exempt")) if (uuid(k) != null) exempt.add(uuid(k));
        addressSalt = cfg.getString("address-salt", addressSalt);
        ConfigurationSection ad = cfg.getConfigurationSection("addresses");
        if (ad != null) for (String k : ad.getKeys(false)) if (uuid(k) != null) addresses.put(uuid(k), ad.getString(k));
        ConfigurationSection m = cfg.getConfigurationSection("mail");
        if (m != null) for (String k : m.getKeys(false)) if (uuid(k) != null) mail.put(uuid(k), new ArrayList<>(m.getStringList(k)));
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
            p.banId = s.getString("ban-id");
            p.category = s.getString("category");
            p.expiresAt = s.getLong("expires");
            p.prevMode = s.getString("prev-mode");
            p.prevLocation = s.getString("prev-location");
            p.prevGameMode = s.getString("prev-gamemode");
            p.storedInventory = s.getString("stored-inventory");
            p.inventoryCleared = s.getBoolean("inventory-cleared");
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
                Punishment.TaskProgress tp = new Punishment.TaskProgress(st, ts.getLong(id + ".time-left"));
                tp.attempts = ts.getInt(id + ".attempts");
                p.tasks.put(id, tp);
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
        if (p.banId != null) cfg.set(path + ".ban-id", p.banId);
        if (p.category != null) cfg.set(path + ".category", p.category);
        if (p.expiresAt > 0) cfg.set(path + ".expires", p.expiresAt);
        if (p.prevMode != null) {
            cfg.set(path + ".prev-mode", p.prevMode);
            cfg.set(path + ".prev-location", p.prevLocation);
        }
        if (p.prevGameMode != null) cfg.set(path + ".prev-gamemode", p.prevGameMode);
        if (p.storedInventory != null) cfg.set(path + ".stored-inventory", p.storedInventory);
        if (p.inventoryCleared) cfg.set(path + ".inventory-cleared", true);
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
            if (e.getValue().attempts > 0) cfg.set(path + ".tasks." + e.getKey() + ".attempts", e.getValue().attempts);
        }
    }

    private String serialize() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Punishment p : active.values()) writePunishment(cfg, "active." + p.uuid, p);
        for (Punishment p : bans.values()) writePunishment(cfg, "bans." + p.uuid, p);
        for (Punishment p : restores.values()) writePunishment(cfg, "restores." + p.uuid, p);
        List<String> exemptList = new ArrayList<>();
        for (UUID id : exempt) exemptList.add(id.toString());
        cfg.set("exempt", exemptList);
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
        if (!dirty || loadFailed) return;
        dirty = false;
        String content = serialize();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (this) {
                write(content);
            }
        });
    }

    public void saveNow() {
        if (loadFailed) return;
        dirty = false;
        String content = serialize();
        synchronized (this) {
            write(content);
        }
    }
}
