package dev.bastion.ac;

import dev.bastion.BastionPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Several anti-cheats, one verdict: Grim, Intave and Bastion's own checks each say on their own when
 * they are sure a player cheats. Nobody is punished because one of them says so.
 * <ul>
 * <li>all of them agree (within ten minutes): the next step of the ladder (/setup),</li>
 * <li>all but one: the player is watched and the team is told,</li>
 * <li>fewer: only noted in the player's file and told to the team.</li>
 * </ul>
 * With three anti-cheats that is: three ban, two watch, one notes. A verdict while the server or the
 * player lags does not count: lag looks like cheating to every anti-cheat at once.
 */
public final class Consensus {
    public static final String GRIM = "grim", INTAVE = "intave", BASTION = "bastion";

    record Vote(String source, String what, long at) {}

    private final BastionPlugin plugin;
    private final AntiCheat ac;
    private final Map<UUID, Map<String, Vote>> votes = new ConcurrentHashMap<>();
    /** for tests: the time */
    public java.util.function.LongSupplier clock = System::currentTimeMillis;

    Consensus(BastionPlugin plugin, AntiCheat ac) {
        this.plugin = plugin;
        this.ac = ac;
    }

    /** The anti-cheats that have a say right now. */
    public List<String> sources() {
        List<String> out = new ArrayList<>();
        if (plugin.getConfig().getBoolean("anticheat.enabled", true)) out.add(BASTION);
        if (plugin.getConfig().getBoolean("anticheat.grim.enabled", true) && Bukkit.getPluginManager().isPluginEnabled("GrimAC")) out.add(GRIM);
        if (plugin.getConfig().getBoolean("anticheat.intave.enabled", true) && Bukkit.getPluginManager().isPluginEnabled("Intave")) out.add(INTAVE);
        return out;
    }

    /** Whether the verdict is a vote: switched on and at least two anti-cheats there. */
    public boolean active() {
        return plugin.getConfig().getBoolean("anticheat.consensus.enabled", true) && sources().size() >= 2;
    }

    /** How many must agree for the ladder: all there are (3 by default; fewer when fewer are installed). */
    int needed() {
        return Math.max(2, Math.min(plugin.getConfig().getInt("anticheat.consensus.punish", 3), sources().size()));
    }

    private static String nice(String source) {
        return switch (source) {
            case GRIM -> "Grim";
            case INTAVE -> "Intave";
            default -> "Bastion";
        };
    }

    /** What the anti-cheats currently say about this player, for the menu and tests. */
    public Map<String, String> said(UUID id) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Vote> mine = votes.get(id);
        if (mine == null) return out;
        long window = window();
        long now = clock.getAsLong();
        for (Vote v : mine.values()) if (now - v.at() <= window) out.put(v.source(), v.what());
        return out;
    }

    private long window() {
        return Math.max(1, plugin.getConfig().getLong("anticheat.consensus.window-minutes", 10)) * 60_000L;
    }

    private boolean lagging(Player p) {
        double tps = 20;
        try {
            tps = Bukkit.getTPS()[0];
        } catch (RuntimeException | NoSuchMethodError ignored) {
            // older API
        }
        int ping = 0;
        try {
            ping = p.getPing();
        } catch (RuntimeException ignored) {
            // older API
        }
        return tps < plugin.getConfig().getDouble("anticheat.consensus.min-tps", 17) || ping > plugin.getConfig().getInt("anticheat.consensus.max-ping", 350);
    }

    /** One anti-cheat says: this player cheats (what: the check, and how sure it was). */
    public void vote(Player p, String source, String what) {
        UUID id = p.getUniqueId();
        String text = nice(source) + ": " + what;
        if (lagging(p)) {
            plugin.akte().event(id, "cheat", text + " · " + plugin.lang().raw("consensus.lag-short"), nice(source));
            ac.teamAlert(plugin.lang().prefixed("consensus.lag", "player", p.getName(), "what", text));
            return;
        }
        long now = clock.getAsLong();
        Map<String, Vote> mine = votes.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        mine.values().removeIf(v -> now - v.at() > window());
        mine.put(source, new Vote(source, what, now));
        List<String> sources = sources();
        int total = sources.size();
        int agree = (int) mine.keySet().stream().filter(sources::contains).count();
        int needed = needed();
        StringBuilder who = new StringBuilder();
        for (Vote v : mine.values()) {
            if (who.length() > 0) who.append(", ");
            who.append(nice(v.source())).append(" (").append(v.what()).append(')');
        }
        plugin.akte().event(id, "cheat", text + " · " + agree + "/" + total, nice(source));
        if (agree >= needed) {
            votes.remove(id);
            String reason = plugin.lang().raw("consensus.reason").replace("{who}", who.toString());
            ac.teamAlert(plugin.lang().prefixed("consensus.punish", "player", p.getName(), "n", agree, "of", total, "who", who.toString()));
            ac.ladder(p, reason, who.toString());
            return;
        }
        if (agree >= Math.max(1, needed - 1)) {
            // all but one: watched, and the team looks
            if (!ac.watched(id)) ac.setWatched(id, p.getName(), true, "Anti-Cheat");
            ac.teamAlert(plugin.lang().prefixed("consensus.watch", "player", p.getName(), "n", agree, "of", total, "who", who.toString()));
            return;
        }
        ac.teamAlert(plugin.lang().prefixed("consensus.note", "player", p.getName(), "n", agree, "of", total, "who", who.toString()));
    }

    void forget(UUID id) {
        votes.remove(id);
    }

    /** "grim, intave, bastion" as a line for people. */
    public String describe() {
        List<String> s = new ArrayList<>();
        for (String x : sources()) s.add(nice(x));
        return s.isEmpty() ? "-" : String.join(", ", s);
    }
}
