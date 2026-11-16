package dev.playban.command;

import dev.playban.Lang;
import dev.playban.PlayBanPlugin;
import dev.playban.CategoryRegistry;
import dev.playban.TimeUtil;
import dev.playban.model.Category;
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
    private static final List<String> STAFF_SUBS = List.of("menu", "punish", "apply", "warn", "ban", "unban", "pardon", "addtime", "removetime", "info", "list", "history", "help");
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
            case "apply" -> apply(sender, args);
            case "warn" -> {
                OfflinePlayer target = target(sender, args, "usage.warn");
                if (target == null) return true;
                String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : lang().raw("no-reason");
                plugin.punishments().warn(target.getUniqueId(), target.getName(), reason, sender.getName(), null);
            }
            case "ban" -> {
                if (args.length < 3) {
                    lang().send(sender, "usage.ban");
                    return true;
                }
                OfflinePlayer target = target(sender, args, "usage.ban");
                if (target == null) return true;
                boolean permanent = CategoryRegistry.isPermanent(args[2]);
                long ms = permanent ? 0 : TimeUtil.parse(args[2]);
                if (!permanent && ms <= 0) {
                    lang().send(sender, "error.bad-time", "input", args[2]);
                    return true;
                }
                String reason = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : lang().raw("no-reason");
                String error = plugin.punishments().execute(target.getUniqueId(), target.getName(), "BAN", ms, reason, sender.getName(), null, null, null);
                if (error != null) lang().send(sender, error, "player", target.getName());
            }
            case "unban" -> {
                if (args.length < 2) {
                    lang().send(sender, "usage.unban");
                    return true;
                }
                Punishment ban = null;
                for (Punishment b : plugin.store().bans.values()) if (b.name.equalsIgnoreCase(args[1])) ban = b;
                if (ban == null) lang().send(sender, "error.not-banned", "player", args[1]);
                else plugin.punishments().unban(ban, sender.getName());
            }
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
            case "help" -> {
                for (String line : lang().raw("help").split("\n")) sender.sendMessage(lang().parse(line));
            }
            default -> {
                // "/playban <player>" opens the category picker for that player
                if (!(sender instanceof Player player)) {
                    for (String line : lang().raw("help").split("\n")) sender.sendMessage(lang().parse(line));
                    return true;
                }
                OfflinePlayer target = target(sender, new String[]{"", args[0]}, "usage.punish");
                if (target != null) plugin.adminMenus().punishPlayer(player, target.getUniqueId(), target.getName());
            }
        }
        return true;
    }

    /** Finds the player named in args[1] and refuses players who cannot be punished. */
    private OfflinePlayer target(CommandSender sender, String[] args, String usageKey) {
        if (args.length < 2) {
            lang().send(sender, usageKey);
            return null;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null || target.getName() == null) {
            lang().send(sender, "error.unknown-player", "player", args[1]);
            return null;
        }
        if (target.getPlayer() != null && target.getPlayer().hasPermission("playban.exempt")) {
            lang().send(sender, "error.exempt", "player", target.getName());
            return null;
        }
        return target;
    }

    /** /playban apply <player> <category> - also works from the console, e.g. for anti-cheat plugins. */
    private void apply(CommandSender sender, String[] args) {
        if (args.length < 3) {
            lang().send(sender, "usage.apply");
            return;
        }
        OfflinePlayer target = target(sender, args, "usage.apply");
        if (target == null) return;
        Category category = plugin.categories().get(args[2]);
        if (category == null) {
            lang().send(sender, "error.unknown-category", "category", args[2]);
            return;
        }
        Category.Step step = plugin.punishments().nextStep(target.getUniqueId(), category);
        if (step == null) {
            lang().send(sender, "error.unknown-category", "category", args[2]);
            return;
        }
        int n = plugin.punishments().countOffenses(target.getUniqueId(), category) + 1;
        String error = plugin.punishments().execute(target.getUniqueId(), target.getName(), step.type, step.durationMs,
                category.name, sender.getName(), category.id, null, null);
        if (error != null) lang().send(sender, error, "player", target.getName());
        else lang().send(sender, "applied", "player", target.getName(), "category", category.name, "n", n,
                "punishment", plugin.punishments().describe(step.type, step.durationMs));
    }

    private void punish(CommandSender sender, String[] args) {
        if (args.length < 3) {
            lang().send(sender, "usage.punish");
            return;
        }
        OfflinePlayer target = target(sender, args, "usage.punish");
        if (target == null) return;
        if (plugin.punishments().isPunished(target.getUniqueId())) {
            lang().send(sender, "error.already-punished", "player", target.getName());
            return;
        }
        long ms = TimeUtil.parse(args[2]);
        if (ms <= 0) {
            lang().send(sender, "error.bad-time", "input", args[2]);
            return;
        }
        String reason = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : lang().raw("no-reason");
        String error = plugin.punishments().execute(target.getUniqueId(), target.getName(), "PLAYBAN", ms, reason, sender.getName(), null, null, null);
        if (error != null) lang().send(sender, error, "player", target.getName());
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
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(last)) out.add(p.getName());
            if (sender.hasPermission("playban.admin")) for (String s : ADMIN_SUBS) if (s.startsWith(last)) out.add(s);
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("unban")) {
                for (Punishment b : plugin.store().bans.values()) if (b.name.toLowerCase().startsWith(last)) out.add(b.name);
            } else if (List.of("punish", "apply", "warn", "ban").contains(sub)) {
                for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(last)) out.add(p.getName());
            } else if (sub.equals("lang")) {
                for (String c : lang().available()) if (c.startsWith(last)) out.add(c);
            } else if (List.of("pardon", "addtime", "removetime", "info", "history").contains(sub)) {
                for (Punishment p : plugin.store().active.values()) if (p.name.toLowerCase().startsWith(last)) out.add(p.name);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("apply")) {
            for (Category c : plugin.categories().all()) if (c.id.startsWith(last)) out.add(c.id);
        } else if (args.length == 3 && List.of("punish", "ban", "addtime", "removetime").contains(args[0].toLowerCase())) {
            for (String s : plugin.getConfig().getStringList("duration-presets")) if (s.startsWith(last)) out.add(s);
        }
        return out;
    }
}
