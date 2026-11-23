package dev.bastion;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Punishments that grow: the ladder of the chosen level (/setup) says what the 1st, 2nd, 3rd ... time
 * caught means: KICK, WARN (kick with a warning) or BAN with a time. Every ban gets a case number and
 * Bastion's own ban screen (reason, time, case, player id, Discord), also when the server's ban system
 * (StaffCore, LiteBans, the game's list ...) holds the ban: its /unban still works.
 */
public final class Punish implements Listener {
    /** A ban Bastion gave: the screen shows it as long as the ban system still says "banned". */
    public record Case(String id, UUID player, String name, String reason, long at, long until, String by) {
        public boolean active() {
            return until == 0 || System.currentTimeMillis() < until;
        }
    }

    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final BastionPlugin plugin;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Case> cases = new ConcurrentHashMap<>();

    public Punish(BastionPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private File file() {
        return new File(plugin.getDataFolder(), "cases.yml");
    }

    public void load() {
        cases.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file());
        for (String k : y.getKeys(false)) {
            ConfigurationSection c = y.getConfigurationSection(k);
            if (c == null) continue;
            try {
                UUID id = UUID.fromString(c.getString("player", ""));
                cases.put(id, new Case(k, id, c.getString("name", "?"), c.getString("reason", ""), c.getLong("at"), c.getLong("until"), c.getString("by", "Bastion")));
            } catch (IllegalArgumentException ignored) {
                // broken line
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Case c : cases.values()) {
            if (!c.active()) continue;
            y.set(c.id() + ".player", c.player().toString());
            y.set(c.id() + ".name", c.name());
            y.set(c.id() + ".reason", c.reason());
            y.set(c.id() + ".at", c.at());
            y.set(c.id() + ".until", c.until());
            y.set(c.id() + ".by", c.by());
        }
        try {
            y.save(file());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save cases.yml: " + e.getMessage());
        }
    }

    public Case caseOf(UUID id) {
        Case c = cases.get(id);
        return c != null && c.active() ? c : null;
    }

    public void forget(UUID id) {
        if (cases.remove(id) != null) save();
    }

    private String newId() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 6; i++) b.append(LETTERS.charAt(random.nextInt(LETTERS.length())));
        return b.toString();
    }

    /** Notes a ban Bastion gives (through whatever system) and returns its case number. */
    public Case recordBan(UUID id, String name, String reason, long ms, String by) {
        long now = System.currentTimeMillis();
        Case c = new Case(newId(), id, name, reason, now, ms <= 0 ? 0 : now + ms, by == null ? "Bastion" : by);
        cases.put(id, c);
        save();
        plugin.akte().event(id, "ban", reason + " · " + human(ms) + " · #" + c.id(), c.by());
        return c;
    }

    // ---------------------------------------------------------------- screens

    private String date(long t) {
        return new SimpleDateFormat(lang().raw("screen.date-format"), Locale.GERMANY).format(new Date(t));
    }

    private Component discordLine() {
        String link = plugin.getConfig().getString("discord", "");
        return link == null || link.isBlank() ? Component.empty() : Component.newline().append(Component.newline())
                .append(lang().msg("screen.discord", "discord", link));
    }

    /** The ban screen: reason, how long and until when, case number, player id, and where to appeal. */
    public Component banScreen(Case c) {
        String time = c.until() == 0 ? lang().raw("ban.forever") : human(Math.max(60_000, c.until() - System.currentTimeMillis()));
        String until = c.until() == 0 ? lang().raw("screen.never") : date(c.until());
        return lang().msg("screen.ban", "reason", c.reason(), "time", time, "until", until, "case", c.id(),
                "uuid", c.player().toString(), "server", serverName()).append(discordLine());
    }

    /** A kick from the anti-cheat or for a forbidden thing: what, how often already, what comes next. */
    public Component kickScreen(String reason, String next) {
        return lang().msg("screen.kick", "reason", reason, "next", next, "server", serverName()).append(discordLine());
    }

    public Component warnScreen(String reason, int warning, int of, String next) {
        return lang().msg("screen.warn", "reason", reason, "n", warning, "of", of, "next", next, "server", serverName()).append(discordLine());
    }

    private String serverName() {
        String n = plugin.getConfig().getString("server-name", "");
        return n == null || n.isBlank() ? "Bastion" : n;
    }

    /** Whoever is banned by Bastion sees Bastion's screen, whichever system holds the ban. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            // lifted elsewhere (/unban in StaffCore, LiteBans, the game ...): the case is closed
            if (cases.containsKey(e.getUniqueId()) && !plugin.guard().bannedOwn(e.getUniqueId())) {
                Bukkit.getScheduler().runTask(plugin, () -> forget(e.getUniqueId()));
            }
            return;
        }
        Case c = caseOf(e.getUniqueId());
        if (c != null && e.getLoginResult() == AsyncPlayerPreLoginEvent.Result.KICK_BANNED || c != null && plugin.guard().bannedOwn(e.getUniqueId())) {
            e.kickMessage(banScreen(c));
        }
    }

    /** The game's own ban list answers only here. */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent e) {
        if (e.getResult() != PlayerLoginEvent.Result.KICK_BANNED) return;
        Case c = caseOf(e.getPlayer().getUniqueId());
        if (c != null) e.kickMessage(banScreen(c));
    }

    // ---------------------------------------------------------------- the ladder

    /** The steps of the chosen level: what the 1st, 2nd, 3rd ... time caught means. */
    public List<String> ladder() {
        List<String> l = plugin.getConfig().getStringList("punish.ladder");
        return l.isEmpty() ? List.of("KICK", "WARN", "WARN", "BAN 1d", "BAN 7d", "BAN 30d", "BAN perm") : l;
    }

    /** What the n-th time (1 = first) means. */
    public String step(int n) {
        List<String> l = ladder();
        return l.get(Math.max(0, Math.min(n, l.size()) - 1));
    }

    /** The step name for people: "Kick", "Warning", "Ban 7 days". */
    public String stepName(String step) {
        String[] parts = step.trim().split("\\s+", 2);
        return switch (parts[0].toUpperCase(Locale.ROOT)) {
            case "KICK" -> lang().raw("step.kick");
            case "WARN" -> lang().raw("step.warn");
            default -> {
                long ms = parts.length > 1 ? Time.parse(parts[1]) : 0;
                yield lang().raw("step.ban").replace("{time}", human(ms));
            }
        };
    }

    /**
     * Caught by the anti-cheat (or another clear cheat): one step up the ladder. Returns the step that
     * was carried out.
     */
    public String caught(UUID id, String name, String reason, String by) {
        Akte.Entry a = plugin.akte().of(id);
        int n = ++a.caught;
        plugin.akte().changed(id);
        String step = step(n);
        String next = stepName(step(n + 1));
        apply(id, name, step, reason, next, by);
        return step;
    }

    /** Carries out KICK, WARN or BAN &lt;time|perm&gt;; next is what comes the time after. */
    public void apply(UUID id, String name, String step, String reason, String next, String by) {
        String[] parts = step.trim().split("\\s+", 2);
        String kind = parts[0].toUpperCase(Locale.ROOT);
        Player online = Bukkit.getPlayer(id);
        switch (kind) {
            case "KICK" -> {
                plugin.akte().event(id, "kick", reason, by);
                if (online != null) online.kick(kickScreen(reason, next));
            }
            case "WARN" -> {
                int w = (int) plugin.akte().of(id).events.stream().filter(ev -> ev.type().equals("warn")).count() + 1;
                int of = (int) ladder().stream().filter(s -> s.trim().toUpperCase(Locale.ROOT).startsWith("WARN")).count();
                plugin.akte().event(id, "warn", reason, by);
                if (online != null) online.kick(warnScreen(reason, Math.min(w, Math.max(1, of)), Math.max(1, of), next));
            }
            default -> ban(id, name, reason, parts.length > 1 ? parts[1] : "perm", by);
        }
    }

    /** Bans through the server's ban system (or Bastion's own) and shows Bastion's screen. */
    public void ban(UUID id, String name, String reason, String dur, String by) {
        long ms = Time.parse(dur);
        Case c = recordBan(id, name, reason, ms, by);
        // Bastion's screen first; then the ban system (it would kick with its own screen otherwise)
        Player online = Bukkit.getPlayer(id);
        if (online != null) online.kick(banScreen(c));
        plugin.guard().banAnywhere(id, name, reason, dur);
    }

    /** "7 Tage", "1 Stunde", "3 Tage 4 Stunden": for people, in their language. */
    public String human(long ms) {
        if (ms <= 0) return lang().raw("ban.forever");
        long minutes = Math.max(1, Math.round(ms / 60_000.0));
        long days = minutes / 1440, hours = (minutes % 1440) / 60, mins = minutes % 60;
        StringBuilder b = new StringBuilder();
        if (days > 0) b.append(days).append(' ').append(lang().raw(days == 1 ? "time.day" : "time.days"));
        if (hours > 0 && days < 7) b.append(b.length() > 0 ? " " : "").append(hours).append(' ').append(lang().raw(hours == 1 ? "time.hour" : "time.hours"));
        if (mins > 0 && days == 0 && hours < 3) b.append(b.length() > 0 ? " " : "").append(mins).append(' ').append(lang().raw(mins == 1 ? "time.minute" : "time.minutes"));
        return b.toString();
    }

    /** Lifts Bastion's part of a ban (the case); the ban system's own unban is separate. */
    public boolean unban(UUID id) {
        return cases.remove(id) != null && saveAnd();
    }

    private boolean saveAnd() {
        save();
        return true;
    }

    public static String nameOf(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() == null ? id.toString().substring(0, 8) : op.getName();
    }
}
