package dev.playban.command;

import dev.playban.Lang;
import dev.playban.PlayBanPlugin;
import dev.playban.TimeUtil;
import dev.playban.model.Punishment;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PlayBanCommand implements TabExecutor {
    private static final List<String> STAFF_SUBS = List.of("menu", "punish", "pardon", "addtime", "removetime", "info", "list", "history");
    private static final List<String> ADMIN_SUBS = List.of("reload", "lang");
    private final PlayBanPlugin plugin;

    public PlayBanCommand(PlayBanPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ticket")) {
            if (!(sender instanceof Player player)) {
                lang().send(sender, "error.players-only");
            } else if (args.length == 0) {
                lang().send(sender, "usage.ticket");
            } else if (!plugin.punishments().isPunished(player.getUniqueId())) {
                lang().send(sender, "error.not-punished-self");
            } else {
                plugin.punishments().createTicket(player, String.join(" ", args));
            }
            return true;
        }

        boolean staff = sender.hasPermission("playban.staff");
        String sub = args.length == 0 ? "" : args[0].toLowerCase();

        if (sender instanceof Player player && (sub.isEmpty() || sub.equals("tasks") || sub.equals("status"))) {
            if (plugin.punishments().isPunished(player.getUniqueId()) && (!staff || !sub.isEmpty())) {
                plugin.playerMenus().status(player);
                return true;
            }
            if (sub.isEmpty() && staff) {
                plugin.adminMenus().main(player);
                return true;
            }
            lang().send(sender, staff ? "error.not-punished-self" : "error.no-permission");
            return true;
        }
        if (!staff) {
            lang().send(sender, "error.no-permission");
            return true;
        }

        switch (sub) {
            case "menu" -> {
                if (sender instanceof Player player) plugin.adminMenus().main(player);
                else lang().send(sender, "error.players-only");
            }
            case "punish" -> punish(sender, args);
            case "pardon" -> {
                Punishment p = find(sender, args, "usage.pardon");
                if (p != null) plugin.punishments().end(p, "PARDONED", sender.getName());
            }
            case "addtime", "removetime" -> {
                if (args.length < 3) {
                    lang().send(sender, "usage." + sub);
                    return true;
                }
                Punishment p = find(sender, args, "usage." + sub);
                if (p == null) return true;
                long ms = TimeUtil.parse(args[2]);
                if (ms <= 0) {
                    lang().send(sender, "error.bad-time", "input", args[2]);
                    return true;
                }
                plugin.punishments().changeTime(p, sub.equals("addtime") ? ms : -ms, sender.getName());
                if (plugin.punishments().isPunished(p.uuid)) {
                    lang().send(sender, "time.changed", "player", p.name, "time", TimeUtil.format(p.remainingMs));
                }
            }
            case "info" -> {
                Punishment p = find(sender, args, "usage.info");
                if (p != null) {
                    lang().send(sender, "info.line", "player", p.name, "time", TimeUtil.format(Math.max(0, p.remainingMs)),
                            "reason", p.reason, "staff", p.staff, "done", p.tasksDone(), "total", p.tasks.size());
                }
            }
            case "list" -> {
                if (plugin.store().active.isEmpty()) {
                    lang().send(sender, "list.empty");
                    return true;
                }
                lang().send(sender, "list.header", "count", plugin.store().active.size());
                for (Punishment p : plugin.store().active.values()) {
                    sender.sendMessage(lang().msg("list.entry", "player", p.name,
                            "time", TimeUtil.format(Math.max(0, p.remainingMs)), "reason", p.reason));
                }
            }
            case "history" -> {
                if (args.length < 2) lang().send(sender, "usage.history");
                else if (sender instanceof Player player) plugin.adminMenus().history(player, args[1]);
                else lang().send(sender, "error.players-only");
            }
            case "reload" -> {
                if (!sender.hasPermission("playban.admin")) {
                    lang().send(sender, "error.no-permission");
                    return true;
                }
                plugin.reload();
                lang().send(sender, "reloaded");
            }
            case "lang" -> {
                if (!sender.hasPermission("playban.admin")) {
                    lang().send(sender, "error.no-permission");
                    return true;
                }
                if (args.length < 2 || !lang().available().contains(args[1].toLowerCase())) {
                    lang().send(sender, "usage.lang", "list", String.join(", ", lang().available()));
                    return true;
                }
                plugin.getConfig().set("language", args[1].toLowerCase());
                plugin.saveConfig();
                lang().load(args[1].toLowerCase());
                lang().send(sender, "language-set", "language", lang().code());
            }
            default -> {
                for (String line : lang().raw("help").split("\n")) sender.sendMessage(lang().parse(line));
            }
        }
        return true;
    }

    private void punish(CommandSender sender, String[] args) {
        if (args.length < 3) {
            lang().send(sender, "usage.punish");
            return;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null || target.getName() == null) {
            lang().send(sender, "error.unknown-player", "player", args[1]);
            return;
        }
        if (plugin.punishments().isPunished(target.getUniqueId())) {
            lang().send(sender, "error.already-punished", "player", target.getName());
            return;
        }
        if (target.getPlayer() != null && target.getPlayer().hasPermission("playban.exempt")) {
            lang().send(sender, "error.exempt", "player", target.getName());
            return;
        }
        long ms = TimeUtil.parse(args[2]);
        if (ms <= 0) {
            lang().send(sender, "error.bad-time", "input", args[2]);
            return;
        }
        String reason = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : lang().raw("no-reason");
        plugin.punishments().punish(target.getUniqueId(), target.getName(), ms, reason, sender.getName(), null, null);
    }

    private Punishment find(CommandSender sender, String[] args, String usageKey) {
        if (args.length < 2) {
            lang().send(sender, usageKey);
            return null;
        }
        for (Punishment p : plugin.store().active.values()) if (p.name.equalsIgnoreCase(args[1])) return p;
        lang().send(sender, "error.not-punished", "player", args[1]);
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("ticket") || !sender.hasPermission("playban.staff")) return out;
        String last = args[args.length - 1].toLowerCase();
        if (args.length == 1) {
            for (String s : STAFF_SUBS) if (s.startsWith(last)) out.add(s);
            if (sender.hasPermission("playban.admin")) for (String s : ADMIN_SUBS) if (s.startsWith(last)) out.add(s);
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("punish")) {
                for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(last)) out.add(p.getName());
            } else if (sub.equals("lang")) {
                for (String c : lang().available()) if (c.startsWith(last)) out.add(c);
            } else if (List.of("pardon", "addtime", "removetime", "info", "history").contains(sub)) {
                for (Punishment p : plugin.store().active.values()) if (p.name.toLowerCase().startsWith(last)) out.add(p.name);
            }
        } else if (args.length == 3 && List.of("punish", "addtime", "removetime").contains(args[0].toLowerCase())) {
            for (String s : plugin.getConfig().getStringList("duration-presets")) if (s.startsWith(last)) out.add(s);
        }
        return out;
    }
}
