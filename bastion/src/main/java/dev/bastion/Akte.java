package dev.bastion;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The file on every player: names, clients, mods, Lumio, resource packs, what the anti-cheat found,
 * every kick, warning and ban, and notes of the team. One small file per player (akten/uuid.yml),
 * kept in memory while needed and written a moment after it changed.
 */
public final class Akte {
    /** One line of the history. */
    public record Event(long time, String type, String text, String by) {}

    public record Note(long time, String by, String text) {}

    public static final class Entry {
        public final UUID id;
        public String name = "?";
        public final List<String> names = new ArrayList<>();
        public long first, last;
        public int joins;
        public final List<String> clients = new ArrayList<>();
        public final List<String> mods = new ArrayList<>();
        public final List<String> packs = new ArrayList<>();
        public String lumio = "";
        /** anti-cheat: how often caught (the step on the ladder) and points per check over all time */
        public int caught;
        public final Map<String, Double> checks = new LinkedHashMap<>();
        public int kicks, warns, bans;
        public final List<Event> events = new ArrayList<>();
        public final List<Note> notes = new ArrayList<>();

        Entry(UUID id) {
            this.id = id;
        }

        private static void add(List<String> list, String v, int max) {
            if (v == null || v.isBlank() || list.contains(v)) return;
            list.add(v);
            while (list.size() > max) list.remove(0);
        }
    }

    private static final int MAX_EVENTS = 250;
    private final BastionPlugin plugin;
    private final Map<UUID, Entry> cache = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();

    public Akte(BastionPlugin plugin) {
        this.plugin = plugin;
    }

    private File dir() {
        return new File(plugin.getDataFolder(), "akten");
    }

    private File file(UUID id) {
        return new File(dir(), id + ".yml");
    }

    public boolean exists(UUID id) {
        return cache.containsKey(id) || file(id).exists();
    }

    public Entry of(UUID id) {
        return cache.computeIfAbsent(id, this::read);
    }

    /** Every player that has a file, by name (for menus and tab completion). */
    public Map<String, UUID> known() {
        Map<String, UUID> out = new LinkedHashMap<>();
        File[] files = dir().listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) for (File f : files) {
            try {
                UUID id = UUID.fromString(f.getName().substring(0, f.getName().length() - 4));
                Entry e = cache.get(id);
                String name = e != null ? e.name : YamlConfiguration.loadConfiguration(f).getString("name", "?");
                out.put(name, id);
            } catch (IllegalArgumentException ignored) {
                // not ours
            }
        }
        for (Entry e : cache.values()) out.putIfAbsent(e.name, e.id);
        return out;
    }

    public UUID byName(String name) {
        var online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        for (Map.Entry<String, UUID> e : known().entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    // ---------------------------------------------------------------- writing to it

    public void joined(UUID id, String name) {
        Entry e = of(id);
        long now = System.currentTimeMillis();
        if (e.first == 0) e.first = now;
        e.last = now;
        e.joins++;
        e.name = name;
        Entry.add(e.names, name, 20);
        changed(id);
    }

    public void client(UUID id, String brand) {
        Entry.add(of(id).clients, brand, 20);
        changed(id);
    }

    public void mods(UUID id, List<String> mods) {
        Entry e = of(id);
        for (String m : mods) Entry.add(e.mods, m, 200);
        changed(id);
    }

    public void packs(UUID id, List<String> packs) {
        Entry e = of(id);
        for (String m : packs) Entry.add(e.packs, m, 60);
        changed(id);
    }

    public void lumio(UUID id, String version) {
        of(id).lumio = version == null ? "" : version;
        changed(id);
    }

    /** A line in the history: type is kick, warn, ban, unban, mod, inject, pack, cheat, note, setup ... */
    public void event(UUID id, String type, String text, String by) {
        Entry e = of(id);
        e.events.add(new Event(System.currentTimeMillis(), type, text == null ? "" : text, by == null ? "Bastion" : by));
        while (e.events.size() > MAX_EVENTS) e.events.remove(0);
        switch (type) {
            case "kick" -> e.kicks++;
            case "warn" -> e.warns++;
            case "ban" -> e.bans++;
            default -> {
            }
        }
        changed(id);
    }

    public void cheat(UUID id, Map<String, Double> points) {
        Entry e = of(id);
        for (Map.Entry<String, Double> p : points.entrySet()) e.checks.merge(p.getKey(), p.getValue(), Double::sum);
        changed(id);
    }

    public void note(UUID id, String by, String text) {
        of(id).notes.add(new Note(System.currentTimeMillis(), by, text));
        event(id, "note", text, by);
    }

    public void changed(UUID id) {
        dirty.add(id);
    }

    // ---------------------------------------------------------------- disk

    private Entry read(UUID id) {
        Entry e = new Entry(id);
        File f = file(id);
        if (!f.exists()) return e;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        e.name = y.getString("name", "?");
        e.names.addAll(y.getStringList("names"));
        e.first = y.getLong("first");
        e.last = y.getLong("last");
        e.joins = y.getInt("joins");
        e.clients.addAll(y.getStringList("clients"));
        e.mods.addAll(y.getStringList("mods"));
        e.packs.addAll(y.getStringList("packs"));
        e.lumio = y.getString("lumio", "");
        e.caught = y.getInt("caught");
        e.kicks = y.getInt("kicks");
        e.warns = y.getInt("warns");
        e.bans = y.getInt("bans");
        ConfigurationSection c = y.getConfigurationSection("checks");
        if (c != null) for (String k : c.getKeys(false)) e.checks.put(k, c.getDouble(k));
        for (Map<?, ?> m : y.getMapList("events")) {
            e.events.add(new Event(num(m.get("t")), String.valueOf(m.get("type")), String.valueOf(m.get("text")), String.valueOf(m.get("by"))));
        }
        for (Map<?, ?> m : y.getMapList("notes")) {
            e.notes.add(new Note(num(m.get("t")), String.valueOf(m.get("by")), String.valueOf(m.get("text"))));
        }
        return e;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private void write(Entry e) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("name", e.name);
        y.set("names", e.names);
        y.set("first", e.first);
        y.set("last", e.last);
        y.set("joins", e.joins);
        y.set("clients", e.clients);
        y.set("mods", e.mods);
        y.set("packs", e.packs);
        y.set("lumio", e.lumio);
        y.set("caught", e.caught);
        y.set("kicks", e.kicks);
        y.set("warns", e.warns);
        y.set("bans", e.bans);
        for (Map.Entry<String, Double> c : e.checks.entrySet()) y.set("checks." + c.getKey(), Math.round(c.getValue() * 10) / 10.0);
        List<Map<String, Object>> ev = new ArrayList<>();
        for (Event x : e.events) ev.add(Map.of("t", x.time(), "type", x.type(), "text", x.text(), "by", x.by()));
        y.set("events", ev);
        List<Map<String, Object>> notes = new ArrayList<>();
        for (Note n : e.notes) notes.add(Map.of("t", n.time(), "by", n.by(), "text", n.text()));
        y.set("notes", notes);
        try {
            dir().mkdirs();
            y.save(file(e.id));
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save the file of " + e.name + ": " + ex.getMessage());
        }
    }

    /** Writes what changed; players who left are let go from memory. */
    public void flush() {
        for (UUID id : new ArrayList<>(dirty)) {
            Entry e = cache.get(id);
            if (e != null) write(e);
        }
        dirty.clear();
        cache.keySet().removeIf(id -> Bukkit.getPlayer(id) == null && !dirty.contains(id));
    }
}
