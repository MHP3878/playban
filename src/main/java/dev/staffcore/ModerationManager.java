package dev.staffcore;

import dev.staffcore.model.Punishment;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat and server control for the team: mutes in chat, lockdown, a muted or slowed chat,
 * and quick look-ups such as /whois, /alts, /stafflist, /near and /report.
 */
public final class ModerationManager implements Listener, TabExecutor {
    private final StaffCorePlugin plugin;
    private final Map<UUID, Long> lastChat = new ConcurrentHashMap<>();
    private volatile boolean chatMuted;
    private volatile int slowSeconds;

    public ModerationManager(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private boolean staff(CommandSender s) {
        return s.hasPermission("staffcore.staff");
    }

    // ---------------------------------------------------------------- lockdown

    public boolean lockdown() {
        return plugin.getConfig().getBoolean("lockdown.enabled", false);
    }

    /** Whether this player may not join because of a lockdown. Safe to call from the login thread. */
    public boolean lockedOut(UUID id) {
        if (!lockdown()) return false;
        for (OfflinePlayer op : Bukkit.getOperators()) if (op.getUniqueId().equals(id)) return false;
        if (plugin.store().exempt.contains(id)) return false;
        return plugin.ranks() == null || plugin.ranks().level(id) < plugin.getConfig().getInt("lockdown.min-level", 30);
    }

    public Component lockdownScreen() {
        String reason = plugin.getConfig().getString("lockdown.reason", "");
        return lang().msg("moderation.lockdown-screen", "reason", reason.isBlank() ? lang().raw("moderation.lockdown-default") : reason,
                "discord", plugin.punishments().discord());
    }

    public void setLockdown(boolean on, String reason, String by) {
        plugin.getConfig().set("lockdown.enabled", on);
        if (reason != null) plugin.getConfig().set("lockdown.reason", reason);
        plugin.saveConfig();
        if (on) {
            for (Player p : Bukkit.getOnlinePlayers()) if (lockedOut(p.getUniqueId()) && !p.hasPermission("staffcore.staff")) p.kick(lockdownScreen());
        }
        Bukkit.broadcast(lang().prefixed(on ? "moderation.lockdown-on" : "moderation.lockdown-off", "staff", by));
    }

    // ---------------------------------------------------------------- chat

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        // the ticket chat and the team's own chats are not affected
        if (plugin.tickets().inChat(p.getUniqueId())) return;
        Punishment mute = plugin.punishments().activeMute(p.getUniqueId());
        if (mute != null) {
            e.setCancelled(true);
            lang().send(p, "mute.blocked", "time", plugin.punishments().timeLeft(mute), "reason", mute.reason);
            return;
        }
        if (p.hasPermission("staffcore.staff")) return;
        if (chatMuted) {
            e.setCancelled(true);
            lang().send(p, "moderation.chat-muted-you");
            return;
        }
        if (slowSeconds > 0) {
            long now = System.currentTimeMillis();
            Long last = lastChat.get(p.getUniqueId());
            if (last != null && now - last < slowSeconds * 1000L) {
                e.setCancelled(true);
                lang().send(p, "moderation.slow-wait", "seconds", Math.max(1, (slowSeconds * 1000L - (now - last) + 999) / 1000));
                return;
            }
            lastChat.put(p.getUniqueId(), now);
        }
    }

    /** Muted players cannot get around it with /msg, /me and the like. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Punishment mute = plugin.punishments().activeMute(e.getPlayer().getUniqueId());
        if (mute == null) return;
        String label = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (label.contains(":")) label = label.substring(label.indexOf(':') + 1);
        if (!plugin.getConfig().getStringList("mute-blocked-commands").contains(label)) return;
        e.setCancelled(true);
        lang().send(e.getPlayer(), "mute.blocked", "time", plugin.punishments().timeLeft(mute), "reason", mute.reason);
    }

    // ---------------------------------------------------------------- look-ups

    private OfflinePlayer find(CommandSender s, String name) {
        OfflinePlayer p = Bukkit.getPlayerExact(name);
        if (p == null) p = Bukkit.getOfflinePlayerIfCached(name);
        if (p == null || p.getName() == null) {
            lang().send(s, "error.unknown-player", "player", name);
            return null;
        }
        return p;
    }

    private String date(long t) {
        return t <= 0 ? "-" : new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(t));
    }

    public void whois(CommandSender s, OfflinePlayer t) {
        UUID id = t.getUniqueId();
        var rank = plugin.ranks().of(id);
        List<String> state = new ArrayList<>();
        if (plugin.punishments().isPunished(id)) state.add(lang().raw("moderation.state.playban"));
        if (plugin.store().bans.containsKey(id)) state.add(lang().raw("moderation.state.banned"));
        if (plugin.punishments().activeMute(id) != null) state.add(lang().raw("moderation.state.muted"));
        if (plugin.admin().isFrozen(id)) state.add(lang().raw("moderation.state.frozen"));
        if (plugin.punishments().isVanished(id)) state.add(lang().raw("moderation.state.vanished"));
        int punishments = plugin.store().history.getOrDefault(id, List.of()).size();
        long playMinutes = 0;
        try {
            playMinutes = t.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 60;
        } catch (RuntimeException ignored) {
            // not every server keeps statistics for offline players
        }
        lang().send(s, "moderation.whois-head", "player", t.getName(), "rank", rank == null ? "-" : rank.name,
                "online", lang().msg(t.isOnline() ? "online" : "offline"));
        s.sendMessage(lang().msg("moderation.whois-times", "first", date(t.getFirstPlayed()),
                "last", t.isOnline() ? lang().raw("moderation.now") : date(t.getLastSeen()),
                "played", TimeUtil.format(Math.max(60_000, playMinutes * 60_000))));
        s.sendMessage(lang().msg("moderation.whois-record", "punishments", punishments,
                "notes", plugin.store().notes.getOrDefault(id, List.of()).size(),
                "tickets", plugin.tickets().involving(id).size(),
                "alts", plugin.store().sameAddress(id).size(),
                "state", state.isEmpty() ? lang().raw("moderation.state.none") : String.join(", ", state)));
        Player online = t.getPlayer();
        if (online != null) {
            s.sendMessage(lang().msg("moderation.whois-live", "world", online.getWorld().getName(),
                    "x", online.getLocation().getBlockX(), "y", online.getLocation().getBlockY(), "z", online.getLocation().getBlockZ(),
                    "mode", online.getGameMode().name().toLowerCase(), "health", (int) Math.ceil(online.getHealth()), "ping", online.getPing()));
        }
        s.sendMessage(lang().msg("moderation.whois-actions").clickEvent(ClickEvent.runCommand("/sc profile " + t.getName())));
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender s, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "lockdown" -> {
                boolean on = args.length == 0 ? !lockdown() : !args[0].equalsIgnoreCase("off");
                String reason = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : null;
                setLockdown(on, reason, s.getName());
            }
            case "mutechat" -> {
                chatMuted = !chatMuted;
                Bukkit.broadcast(lang().prefixed(chatMuted ? "moderation.chat-muted" : "moderation.chat-unmuted", "staff", s.getName()));
            }
            case "slowchat" -> {
                int seconds = 0;
                if (args.length > 0 && !args[0].equalsIgnoreCase("off")) {
                    try {
                        seconds = Math.max(0, Math.min(600, Integer.parseInt(args[0])));
                    } catch (NumberFormatException ex) {
                        lang().send(s, "error.bad-number", "input", args[0]);
                        return true;
                    }
                }
                slowSeconds = seconds;
                lastChat.clear();
                Bukkit.broadcast(seconds > 0 ? lang().prefixed("moderation.slow-on", "seconds", seconds, "staff", s.getName())
                        : lang().prefixed("moderation.slow-off", "staff", s.getName()));
            }
            case "whois", "seen" -> {
                if (args.length < 1) {
                    lang().send(s, "moderation.usage-player", "command", command.getName());
                    return true;
                }
                OfflinePlayer t = find(s, args[0]);
                if (t != null) whois(s, t);
            }
            case "alts" -> {
                if (args.length < 1) {
                    lang().send(s, "moderation.usage-player", "command", "alts");
                    return true;
                }
                OfflinePlayer t = find(s, args[0]);
                if (t == null) return true;
                List<String> alts = plugin.store().sameAddress(t.getUniqueId());
                if (alts.isEmpty()) lang().send(s, "moderation.no-alts", "player", t.getName());
                else lang().send(s, "moderation.alts", "player", t.getName(), "list", String.join(", ", alts));
            }
            case "stafflist" -> {
                List<Player> team = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) if (staff(p)) team.add(p);
                team.sort((a, b) -> Integer.compare(plugin.ranks().level(b.getUniqueId()), plugin.ranks().level(a.getUniqueId())));
                lang().send(s, "moderation.stafflist-head", "count", team.size());
                boolean seeHidden = s.hasPermission("staffcore.vanish");
                for (Player p : team) {
                    boolean hidden = plugin.punishments().isVanished(p.getUniqueId());
                    if (hidden && !seeHidden) continue;
                    var r = plugin.ranks().of(p.getUniqueId());
                    s.sendMessage(lang().msg(hidden ? "moderation.stafflist-hidden" : "moderation.stafflist-entry", "player", p.getName(),
                            "rank", r == null ? "-" : r.name));
                }
            }
            case "near" -> {
                if (!(s instanceof Player me)) {
                    lang().send(s, "error.players-only");
                    return true;
                }
                int radius = 100;
                try {
                    if (args.length > 0) radius = Math.max(1, Math.min(1000, Integer.parseInt(args[0])));
                } catch (NumberFormatException ignored) {
                    // keep the default
                }
                List<String> found = new ArrayList<>();
                for (Player o : me.getWorld().getPlayers()) {
                    if (o.equals(me) || plugin.punishments().isVanished(o.getUniqueId())) continue;
                    double d = o.getLocation().distance(me.getLocation());
                    if (d <= radius) found.add(o.getName() + " (" + (int) d + "m)");
                }
                if (found.isEmpty()) lang().send(s, "moderation.near-none", "radius", radius);
                else lang().send(s, "moderation.near", "radius", radius, "list", String.join(", ", found));
            }
            case "report" -> {
                if (!(s instanceof Player me)) {
                    lang().send(s, "error.players-only");
                    return true;
                }
                if (args.length < 2) {
                    lang().send(s, "moderation.report-usage");
                    return true;
                }
                OfflinePlayer t = find(s, args[0]);
                if (t == null) return true;
                if (t.getUniqueId().equals(me.getUniqueId())) {
                    lang().send(s, "task.not-yourself");
                    return true;
                }
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                var ticket = plugin.tickets().create(me, "report", lang().plain("moderation.report-text", "player", t.getName(), "reason", reason));
                if (ticket != null) lang().send(s, "moderation.report-sent", "player", t.getName());
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String typed = args[args.length - 1].toLowerCase();
        switch (command.getName().toLowerCase()) {
            case "lockdown" -> {
                if (args.length == 1) for (String o : List.of("on", "off")) if (o.startsWith(typed)) out.add(o);
            }
            case "slowchat" -> {
                if (args.length == 1) for (String o : List.of("3", "5", "10", "30", "off")) if (o.startsWith(typed)) out.add(o);
            }
            case "whois", "seen", "alts", "report" -> {
                if (args.length == 1) {
                    for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(typed)) out.add(p.getName());
                    if (!command.getName().equals("report")) {
                        for (String n : plugin.store().names.values()) if (out.size() < 40 && n.toLowerCase().startsWith(typed) && !out.contains(n)) out.add(n);
                    }
                } else if (args.length == 2 && command.getName().equals("report")) {
                    for (String r : plugin.getConfig().getStringList("reason-presets")) if (r.toLowerCase().startsWith(typed)) out.add(r);
                }
            }
            case "near" -> {
                if (args.length == 1) for (String o : List.of("50", "100", "200", "500")) if (o.startsWith(typed)) out.add(o);
            }
            default -> {
                // no arguments
            }
        }
        return out;
    }
}
