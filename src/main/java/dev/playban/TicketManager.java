package dev.playban;

import dev.playban.model.Category;
import dev.playban.model.Ticket;
import dev.playban.model.Ticket.Entry;
import dev.playban.model.Ticket.Participant;
import dev.playban.model.Ticket.Status;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tickets: opening, the private ticket chat, participants, teleporting and closing with a decision per person. */
public final class TicketManager {
    public record Reason(String id, String name, Material icon) {}
    public record StaffStats(String name, int accepted, int closed, long avgResponseMs) {}
    private record Before(Location location, GameMode mode) {}

    private static final int MAX_OPEN_PER_PLAYER = 3;
    private static final int MAX_LOG = 500;

    private final PlayBanPlugin plugin;
    private final Map<Integer, Ticket> tickets = new LinkedHashMap<>();
    /** Players whose chat currently goes into a ticket. Read from the chat thread. */
    private final Map<UUID, Integer> chats = new ConcurrentHashMap<>();
    private final Map<UUID, Before> teleported = new HashMap<>();
    private final java.util.Set<UUID> vanished = new java.util.HashSet<>();
    private int nextId = 1;
    private boolean dirty;

    public TicketManager(PlayBanPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private File file() {
        return new File(plugin.getDataFolder(), "tickets.yml");
    }

    // ---------------------------------------------------------------- reasons

    public List<Reason> reasons() {
        List<Reason> out = new ArrayList<>();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("ticket-reasons");
        if (s != null) for (String id : s.getKeys(false)) {
            Material icon = Material.matchMaterial(s.getString(id + ".icon", "PAPER"));
            out.add(new Reason(id, s.getString(id + ".name", id), icon == null || !icon.isItem() ? Material.PAPER : icon));
        }
        if (out.isEmpty()) out.add(new Reason("other", "Other", Material.OAK_SIGN));
        return out;
    }

    public Reason reason(String id) {
        for (Reason r : reasons()) if (r.id().equalsIgnoreCase(id)) return r;
        return null;
    }

    public String reasonName(String id) {
        Reason r = reason(id);
        return r == null ? id : r.name();
    }

    // ---------------------------------------------------------------- lookups

    public Ticket get(int id) {
        return tickets.get(id);
    }

    public List<Ticket> all() {
        return new ArrayList<>(tickets.values());
    }

    public int count(Status status) {
        int n = 0;
        for (Ticket t : tickets.values()) if (t.status == status) n++;
        return n;
    }

    /** Tickets a player opened or was pulled into, newest first. */
    public List<Ticket> involving(UUID id) {
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : tickets.values()) if (t.participant(id) != null) out.add(0, t);
        return out;
    }

    public List<Ticket> handledBy(UUID staff) {
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : tickets.values()) if (t.status == Status.IN_PROGRESS && staff.equals(t.handlerId)) out.add(t);
        return out;
    }

    /** The ticket this player's chat is redirected into right now, or null. */
    public Ticket chatOf(UUID player) {
        Integer id = chats.get(player);
        return id == null ? null : tickets.get(id);
    }

    public boolean inChat(UUID player) {
        return chats.containsKey(player);
    }

    private void rebuildChats() {
        chats.clear();
        for (Ticket t : tickets.values()) {
            if (t.status != Status.IN_PROGRESS || t.paused) continue;
            for (Participant p : t.participants) chats.putIfAbsent(p.uuid, t.id);
        }
    }

    private void changed(Ticket t) {
        dirty = true;
        rebuildChats();
        plugin.ticketMenus().refresh(t.id);
    }

    // ---------------------------------------------------------------- log

    private Entry add(Ticket t, String kind, String author, String text, String to) {
        Entry e = new Entry();
        e.time = System.currentTimeMillis();
        e.kind = kind;
        e.author = author;
        e.text = text;
        e.to = to;
        t.log.add(e);
        while (t.log.size() > MAX_LOG) t.log.remove(0);
        return e;
    }

    private void system(Ticket t, String key, Object... kv) {
        add(t, "SYSTEM", "", lang().plain(key, kv), null);
    }

    public void toggleStar(Ticket t, Entry e) {
        e.starred = !e.starred;
        changed(t);
    }

    // ---------------------------------------------------------------- opening

    public Ticket create(Player author, String reasonId, String text) {
        int open = 0;
        for (Ticket t : tickets.values()) if (t.active() && t.author.equals(author.getUniqueId())) open++;
        if (open >= MAX_OPEN_PER_PLAYER) {
            lang().send(author, "ticket.too-many");
            return null;
        }
        Reason reason = reason(reasonId);
        if (reason == null) reason = reasons().get(reasons().size() - 1);
        Ticket t = new Ticket();
        t.id = nextId++;
        t.author = author.getUniqueId();
        t.authorName = author.getName();
        t.reason = reason.id();
        t.text = text;
        t.createdAt = System.currentTimeMillis();
        t.lastReminder = t.createdAt;
        Participant p = new Participant();
        p.uuid = t.author;
        p.name = t.authorName;
        t.participants.add(p);
        add(t, "CHAT", t.authorName, text, null);
        tickets.put(t.id, t);
        plugin.store().names.put(t.author, t.authorName);
        dirty = true;
        lang().send(author, "ticket.sent", "id", t.id);
        plugin.punishments().notifyStaff(lang().prefixed("ticket.new", "id", t.id, "player", t.authorName,
                "reason", reason.name(), "text", text).clickEvent(ClickEvent.runCommand("/ticket open " + t.id)));
        webhook(lang().plain("ticket.webhook", "id", t.id, "player", t.authorName, "reason", reason.name(), "text", text));
        return t;
    }

    // ---------------------------------------------------------------- handling

    public void accept(Ticket t, Player staff) {
        if (t.status != Status.OPEN) return;
        t.status = Status.IN_PROGRESS;
        t.handler = staff.getName();
        t.handlerId = staff.getUniqueId();
        t.acceptedAt = System.currentTimeMillis();
        system(t, "ticket.log.accepted", "staff", staff.getName());
        changed(t);
        tell(t, null, lang().prefixed("ticket.accepted-you", "id", t.id, "staff", staff.getName()));
        tell(t, null, lang().prefixed("ticket.chat-on"));
    }

    public void decline(Ticket t, Player staff) {
        if (!t.active()) return;
        t.status = Status.DECLINED;
        t.handler = staff.getName();
        t.handlerId = staff.getUniqueId();
        t.closedAt = System.currentTimeMillis();
        system(t, "ticket.log.declined", "staff", staff.getName());
        changed(t);
        tell(t, null, lang().prefixed("ticket.declined-you", "id", t.id));
    }

    /** Sends a line to one participant, or to all of them when to is null. Offline players get it on their next join. */
    private void tell(Ticket t, Participant to, Component message) {
        for (Participant p : t.participants) {
            if (to != null && p != to) continue;
            plugin.punishments().deliver(p.uuid, message);
        }
    }

    private void tellHandler(Ticket t, Component message) {
        if (t.handlerId == null) return;
        Player handler = Bukkit.getPlayer(t.handlerId);
        if (handler == null || plugin.ticketMenus().watching(handler, t.id)) return;
        handler.sendMessage(message.clickEvent(ClickEvent.runCommand("/ticket open " + t.id)));
    }

    /** A participant wrote into the ticket chat. */
    public void playerMessage(Player player, Ticket t, String text) {
        add(t, "CHAT", player.getName(), text, null);
        Component line = lang().msg("ticket.chat-line", "id", t.id, "author", player.getName(), "text", text);
        for (Participant p : t.participants) {
            boolean self = p.uuid.equals(player.getUniqueId());
            if (!self && !t.shared) continue;
            Player online = Bukkit.getPlayer(p.uuid);
            if (online != null) online.sendMessage(line);
        }
        tellHandler(t, line);
        changed(t);
    }

    public void staffMessage(Ticket t, Player staff, String text, Participant to) {
        add(t, "STAFF", staff.getName(), text, to == null ? null : to.name);
        tell(t, to, lang().msg("ticket.staff-line", "id", t.id, "author", staff.getName(), "text", text));
        changed(t);
    }

    public void note(Ticket t, String staff, String text) {
        add(t, "NOTE", staff, text, null);
        changed(t);
    }

    /**
     * A ticket chat only runs while its team member is there. Otherwise a forgotten ticket
     * would keep a player out of the public chat for good.
     */
    public void pauseAllOf(UUID staff) {
        for (Ticket t : handledBy(staff)) {
            if (t.paused) continue;
            t.paused = true;
            system(t, "ticket.log.auto-paused");
            changed(t);
            tell(t, null, lang().prefixed("ticket.chat-off"));
        }
    }

    /** Puts a team member back who teleported through a ticket; used when the plugin shuts down. */
    public void returnEveryone() {
        for (UUID id : new ArrayList<>(teleported.keySet())) {
            Player staff = Bukkit.getPlayer(id);
            if (staff != null) returnBack(staff);
        }
    }

    public void setPaused(Ticket t, Player staff, boolean paused) {
        if (t.paused == paused) return;
        t.paused = paused;
        system(t, paused ? "ticket.log.paused" : "ticket.log.resumed", "staff", staff.getName());
        changed(t);
        tell(t, null, lang().prefixed(paused ? "ticket.chat-off" : "ticket.chat-on"));
    }

    public void setShared(Ticket t, Player staff, boolean shared) {
        t.shared = shared;
        system(t, shared ? "ticket.log.shared" : "ticket.log.separate", "staff", staff.getName());
        changed(t);
    }

    /** Returns the key of an error text, or null when the player was added. */
    public String addParticipant(Ticket t, Player staff, UUID id, String name) {
        if (t.participant(id) != null) return "ticket.error.already-in";
        Ticket other = chatOf(id);
        if (other != null && other != t) return "ticket.error.busy";
        if (t.participants.size() >= 7) return "ticket.error.full";
        Participant p = new Participant();
        p.uuid = id;
        p.name = name;
        t.participants.add(p);
        plugin.store().names.put(id, name);
        system(t, "ticket.log.added", "staff", staff.getName(), "player", name);
        changed(t);
        tell(t, p, lang().prefixed("ticket.added-you", "id", t.id, "staff", staff.getName()));
        if (!t.paused) tell(t, p, lang().prefixed("ticket.chat-on"));
        return null;
    }

    public void removeParticipant(Ticket t, Player staff, Participant p) {
        if (p.uuid.equals(t.author) || !t.participants.remove(p)) return;
        system(t, "ticket.log.removed", "staff", staff.getName(), "player", p.name);
        changed(t);
        plugin.punishments().deliver(p.uuid, lang().prefixed("ticket.chat-off"));
    }

    /** Invites everyone in the ticket to voice: big red text plus the Discord link. */
    public boolean voice(Ticket t, Player staff) {
        String discord = plugin.punishments().discord();
        if (discord.isEmpty()) return false;
        for (Participant p : t.participants) {
            Player online = Bukkit.getPlayer(p.uuid);
            if (online == null) continue;
            online.showTitle(Title.title(lang().msg("ticket.voice.title"), lang().msg("ticket.voice.subtitle", "staff", staff.getName())));
            online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
            lang().send(online, "ticket.voice.link", "staff", staff.getName(), "discord", discord);
        }
        system(t, "ticket.log.voice", "staff", staff.getName());
        changed(t);
        return true;
    }

    // ---------------------------------------------------------------- teleport

    public boolean vanished(UUID staff) {
        return vanished.contains(staff);
    }

    public boolean canReturn(UUID staff) {
        return teleported.containsKey(staff);
    }

    /** mode: SURVIVAL, CREATIVE, SPECTATOR or VANISH */
    public boolean teleport(Ticket t, Player staff, Participant target, String mode) {
        Player online = Bukkit.getPlayer(target.uuid);
        if (online == null) return false;
        teleported.putIfAbsent(staff.getUniqueId(), new Before(staff.getLocation(), staff.getGameMode()));
        if (mode.equals("VANISH")) {
            vanished.add(staff.getUniqueId());
            staff.setAllowFlight(true);
        } else {
            vanished.remove(staff.getUniqueId());
            staff.setGameMode(GameMode.valueOf(mode));
        }
        plugin.punishments().refreshVisibility();
        staff.teleport(online.getLocation());
        system(t, "ticket.log.teleport", "staff", staff.getName(), "player", target.name, "mode", lang().plain("ticket.mode." + mode));
        changed(t);
        return true;
    }

    public void returnBack(Player staff) {
        Before before = teleported.remove(staff.getUniqueId());
        vanished.remove(staff.getUniqueId());
        if (before != null) {
            staff.setGameMode(before.mode());
            if (before.mode() == GameMode.SURVIVAL || before.mode() == GameMode.ADVENTURE) staff.setAllowFlight(false);
            staff.teleport(before.location());
        }
        plugin.punishments().refreshVisibility();
    }

    public void forget(UUID staff) {
        teleported.remove(staff);
        vanished.remove(staff);
    }

    // ---------------------------------------------------------------- closing

    public boolean allDecided(Ticket t) {
        for (Participant p : t.participants) if (p.verdict == null) return false;
        return true;
    }

    public void setVerdict(Ticket t, Participant p, String verdict) {
        p.verdict = verdict;
        dirty = true;
    }

    /** Closes the ticket and carries out what was decided for every participant. */
    public void close(Ticket t, Player staff) {
        if (t.status != Status.IN_PROGRESS || !allDecided(t)) return;
        for (Participant p : t.participants) {
            if (!p.verdict.startsWith("category:")) {
                p.outcome = lang().plain("ticket.verdict.involved");
                continue;
            }
            Category c = plugin.categories().get(p.verdict.substring("category:".length()));
            Category.Step step = c == null ? null : plugin.punishments().nextStep(p.uuid, c);
            if (step == null) {
                p.outcome = lang().plain("ticket.verdict.involved");
                continue;
            }
            Player online = Bukkit.getPlayer(p.uuid);
            if (online != null && online.hasPermission("playban.exempt")) {
                p.outcome = lang().plain("ticket.verdict.failed", "category", c.name, "why", lang().plain("error.exempt", "player", p.name));
                continue;
            }
            String error = plugin.punishments().execute(p.uuid, p.name, step.type, step.durationMs, c.name, staff.getName(), c.id, null, null);
            p.outcome = error == null
                    ? c.name + ": " + plugin.punishments().describe(step.type, step.durationMs)
                    : lang().plain("ticket.verdict.failed", "category", c.name, "why", lang().plain(error, "player", p.name));
        }
        t.status = Status.CLOSED;
        t.closedAt = System.currentTimeMillis();
        system(t, "ticket.log.closed", "staff", staff.getName());
        changed(t);
        tell(t, null, lang().prefixed("ticket.closed-you", "id", t.id));
    }

    // ---------------------------------------------------------------- reminders, statistics, Discord

    /** Called once a minute: reminds the team about tickets nobody picked up. */
    public void remind() {
        long every = plugin.getConfig().getLong("ticket-reminder-minutes", 10) * 60_000L;
        if (every <= 0) return;
        long now = System.currentTimeMillis();
        int waiting = 0;
        for (Ticket t : tickets.values()) {
            if (t.status != Status.OPEN || now - t.lastReminder < every) continue;
            t.lastReminder = now;
            waiting++;
        }
        if (waiting > 0) {
            plugin.punishments().notifyStaff(lang().prefixed("ticket.reminder", "count", waiting)
                    .clickEvent(ClickEvent.runCommand("/ticket show")));
        }
    }

    public List<StaffStats> stats() {
        Map<String, long[]> sums = new LinkedHashMap<>(); // accepted, closed, total response time
        for (Ticket t : tickets.values()) {
            if (t.handler == null) continue;
            long[] s = sums.computeIfAbsent(t.handler, k -> new long[3]);
            if (t.acceptedAt > 0) {
                s[0]++;
                s[2] += t.acceptedAt - t.createdAt;
            }
            if (t.status == Status.CLOSED) s[1]++;
        }
        List<StaffStats> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : sums.entrySet()) {
            long[] s = e.getValue();
            out.add(new StaffStats(e.getKey(), (int) s[0], (int) s[1], s[0] == 0 ? 0 : s[2] / s[0]));
        }
        out.sort((a, b) -> Integer.compare(b.closed() + b.accepted(), a.closed() + a.accepted()));
        return out;
    }

    private void webhook(String content) {
        String url = plugin.getConfig().getString("discord-webhook", "");
        if (url.isEmpty() || !url.startsWith("https://")) return;
        String json = "{\"content\":\"" + content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
            } catch (IOException | InterruptedException | IllegalArgumentException e) {
                plugin.getLogger().warning("Could not send the ticket to the Discord webhook: " + e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- storage

    public void load() {
        if (file().exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file());
            nextId = Math.max(1, cfg.getInt("next-id", 1));
            ConfigurationSection root = cfg.getConfigurationSection("tickets");
            if (root != null) for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    Ticket t = new Ticket();
                    t.id = Integer.parseInt(key);
                    t.author = UUID.fromString(s.getString("author", ""));
                    t.authorName = s.getString("author-name", "?");
                    t.reason = s.getString("reason", "other");
                    t.text = s.getString("text", "");
                    t.createdAt = s.getLong("created");
                    t.status = Status.valueOf(s.getString("status", "OPEN"));
                    t.handler = s.getString("handler");
                    String handlerId = s.getString("handler-id");
                    t.handlerId = handlerId == null ? null : UUID.fromString(handlerId);
                    t.acceptedAt = s.getLong("accepted");
                    t.closedAt = s.getLong("closed");
                    t.lastReminder = System.currentTimeMillis();
                    // after a restart every chat starts paused until the team member continues it
                    t.paused = s.getBoolean("paused") || "IN_PROGRESS".equals(s.getString("status"));
                    t.shared = s.getBoolean("shared", true);
                    for (Map<?, ?> m : s.getMapList("participants")) {
                        Participant p = new Participant();
                        p.uuid = UUID.fromString(String.valueOf(m.get("uuid")));
                        p.name = String.valueOf(m.get("name"));
                        p.verdict = m.get("verdict") == null ? null : String.valueOf(m.get("verdict"));
                        p.outcome = m.get("outcome") == null ? null : String.valueOf(m.get("outcome"));
                        t.participants.add(p);
                    }
                    for (Map<?, ?> m : s.getMapList("log")) {
                        Entry e = new Entry();
                        e.time = ((Number) m.get("time")).longValue();
                        e.kind = String.valueOf(m.get("kind"));
                        e.author = String.valueOf(m.get("author"));
                        e.text = String.valueOf(m.get("text"));
                        e.starred = Boolean.TRUE.equals(m.get("starred"));
                        e.to = m.get("to") == null ? null : String.valueOf(m.get("to"));
                        t.log.add(e);
                    }
                    tickets.put(t.id, t);
                    nextId = Math.max(nextId, t.id + 1);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("tickets.yml: skipped a broken ticket (" + key + ")");
                }
            }
        }
        // tickets written by PlayBan 0.3 and older lived in data.yml
        for (String[] old : plugin.store().legacyTickets) {
            Ticket t = new Ticket();
            t.id = nextId++;
            t.author = UUID.fromString(old[0]);
            t.authorName = old[1];
            t.reason = "other";
            t.text = old[2];
            t.createdAt = Long.parseLong(old[3]);
            t.lastReminder = System.currentTimeMillis();
            Participant p = new Participant();
            p.uuid = t.author;
            p.name = t.authorName;
            t.participants.add(p);
            add(t, "CHAT", t.authorName, t.text, null).time = t.createdAt;
            tickets.put(t.id, t);
            dirty = true;
        }
        plugin.store().legacyTickets.clear();
        rebuildChats();
    }

    private String serialize() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("next-id", nextId);
        for (Ticket t : tickets.values()) {
            String p = "tickets." + t.id + ".";
            cfg.set(p + "author", t.author.toString());
            cfg.set(p + "author-name", t.authorName);
            cfg.set(p + "reason", t.reason);
            cfg.set(p + "text", t.text);
            cfg.set(p + "created", t.createdAt);
            cfg.set(p + "status", t.status.name());
            if (t.handler != null) cfg.set(p + "handler", t.handler);
            if (t.handlerId != null) cfg.set(p + "handler-id", t.handlerId.toString());
            if (t.acceptedAt > 0) cfg.set(p + "accepted", t.acceptedAt);
            if (t.closedAt > 0) cfg.set(p + "closed", t.closedAt);
            cfg.set(p + "paused", t.paused);
            cfg.set(p + "shared", t.shared);
            List<Map<String, Object>> parts = new ArrayList<>();
            for (Participant part : t.participants) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("uuid", part.uuid.toString());
                m.put("name", part.name);
                if (part.verdict != null) m.put("verdict", part.verdict);
                if (part.outcome != null) m.put("outcome", part.outcome);
                parts.add(m);
            }
            cfg.set(p + "participants", parts);
            List<Map<String, Object>> log = new ArrayList<>();
            for (Entry e : t.log) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("time", e.time);
                m.put("kind", e.kind);
                m.put("author", e.author);
                m.put("text", e.text);
                if (e.starred) m.put("starred", true);
                if (e.to != null) m.put("to", e.to);
                log.add(m);
            }
            cfg.set(p + "log", log);
        }
        return cfg.saveToString();
    }

    private synchronized void write(String content) {
        try {
            File target = file();
            target.getParentFile().mkdirs();
            File tmp = new File(target.getParentFile(), "tickets.yml.tmp");
            Files.writeString(tmp.toPath(), content, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save tickets.yml: " + e.getMessage());
        }
    }

    public void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        String content = serialize();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(content));
    }

    public void saveNow() {
        dirty = false;
        write(serialize());
    }
}
