package dev.staffcore.module;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import dev.staffcore.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /tpa, /tpahere, /tpaccept, /tpdeny, /tpacancel: ask another player to teleport. */
public final class TpaManager implements TabExecutor {
    private record Request(UUID from, UUID to, boolean here, long at) {}

    private final StaffCorePlugin plugin;
    /** newest request per receiver and sender */
    private final Map<UUID, Map<UUID, Request>> incoming = new HashMap<>();
    private final Map<UUID, Long> lastSent = new HashMap<>();

    public TpaManager(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private long timeout() {
        return Math.max(10, plugin.getConfig().getInt("tpa.timeout", 60)) * 1000L;
    }

    /** Vanished staff count as offline here, so nobody finds them by asking. */
    private Player visible(CommandSender asker, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null || (plugin.punishments().isVanished(p.getUniqueId()) && !asker.hasPermission("staffcore.vanish"))) return null;
        return p;
    }

    private Request take(Player receiver, String fromName) {
        Map<UUID, Request> mine = incoming.get(receiver.getUniqueId());
        if (mine == null || mine.isEmpty()) return null;
        mine.values().removeIf(r -> System.currentTimeMillis() - r.at() > timeout());
        Request pick = null;
        for (Request r : mine.values()) {
            Player from = Bukkit.getPlayer(r.from());
            if (from == null) continue;
            if (fromName != null && !from.getName().equalsIgnoreCase(fromName)) continue;
            if (pick == null || r.at() > pick.at()) pick = r;
        }
        if (pick != null) mine.remove(pick.from());
        return pick;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            lang().send(sender, "error.players-only");
            return true;
        }
        String cmd = command.getName().toLowerCase();
        switch (cmd) {
            case "tpa", "tpahere" -> {
                if (args.length < 1) {
                    lang().send(p, "tpa.usage");
                    return true;
                }
                Player target = visible(p, args[0]);
                if (target == null) {
                    lang().send(p, "admin.not-online", "player", args[0]);
                    return true;
                }
                if (target == p) {
                    lang().send(p, "tpa.self");
                    return true;
                }
                if (plugin.teleports().stuck(p) || plugin.teleports().stuck(target)) {
                    lang().send(p, "teleport.not-now");
                    return true;
                }
                long cooldown = plugin.getConfig().getLong("tpa.cooldown", 10) * 1000L;
                Long last = lastSent.get(p.getUniqueId());
                if (cooldown > 0 && last != null && System.currentTimeMillis() - last < cooldown) {
                    lang().send(p, "teleport.cooldown", "time", TimeUtil.format(Math.max(1000, cooldown - (System.currentTimeMillis() - last))));
                    return true;
                }
                lastSent.put(p.getUniqueId(), System.currentTimeMillis());
                boolean here = cmd.equals("tpahere");
                incoming.computeIfAbsent(target.getUniqueId(), k -> new HashMap<>())
                        .put(p.getUniqueId(), new Request(p.getUniqueId(), target.getUniqueId(), here, System.currentTimeMillis()));
                lang().send(p, "tpa.sent", "player", target.getName(), "seconds", timeout() / 1000);
                target.sendMessage(lang().prefixed(here ? "tpa.asked-here" : "tpa.asked", "player", p.getName()));
                target.sendMessage(lang().msg("tpa.accept-button").clickEvent(ClickEvent.runCommand("/tpaccept " + p.getName()))
                        .append(Component.text("  "))
                        .append(lang().msg("tpa.deny-button").clickEvent(ClickEvent.runCommand("/tpdeny " + p.getName()))));
                target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.4f);
            }
            case "tpaccept" -> {
                Request r = take(p, args.length > 0 ? args[0] : null);
                Player from = r == null ? null : Bukkit.getPlayer(r.from());
                if (from == null) {
                    lang().send(p, "tpa.nothing");
                    return true;
                }
                lang().send(p, "tpa.accepted", "player", from.getName());
                lang().send(from, "tpa.accepted-you", "player", p.getName());
                Player mover = r.here() ? p : from;
                Player stay = r.here() ? from : p;
                plugin.teleports().after(mover, plugin.getConfig().getInt("tpa.warmup", 3),
                        () -> stay.isOnline() ? stay.getLocation() : null, null);
            }
            case "tpdeny" -> {
                Request r = take(p, args.length > 0 ? args[0] : null);
                Player from = r == null ? null : Bukkit.getPlayer(r.from());
                if (from == null) lang().send(p, "tpa.nothing");
                else {
                    lang().send(p, "tpa.denied", "player", from.getName());
                    lang().send(from, "tpa.denied-you", "player", p.getName());
                }
            }
            case "tpacancel" -> {
                boolean any = false;
                for (Map<UUID, Request> m : incoming.values()) any |= m.remove(p.getUniqueId()) != null;
                lang().send(p, any ? "tpa.cancelled" : "tpa.nothing");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) return out;
        String cmd = command.getName().toLowerCase();
        if (cmd.equals("tpaccept") || cmd.equals("tpdeny")) {
            if (sender instanceof Player p) {
                for (Request r : incoming.getOrDefault(p.getUniqueId(), Map.of()).values()) {
                    Player f = Bukkit.getPlayer(r.from());
                    if (f != null && f.getName().toLowerCase().startsWith(args[0].toLowerCase())) out.add(f.getName());
                }
            }
        } else if (!cmd.equals("tpacancel")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p != sender && visible(sender, p.getName()) != null && p.getName().toLowerCase().startsWith(args[0].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }
}
