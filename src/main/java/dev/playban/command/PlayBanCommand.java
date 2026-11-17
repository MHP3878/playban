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
    private static final List<String> STAFF_SUBS = List.of("menu", "punish", "apply", "warn", "ban", "specban", "unban", "note", "profile", "pardon", "addtime", "removetime", "info", "list", "history", "help");
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
        boolean staff = sender.hasPermission("playban.staff");
        // Short commands: /ban Steve 1d -> handled like /playban ban Steve 1d
        String used = label.toLowerCase();
        if (used.contains(":")) used = used.substring(used.indexOf(':') + 1);
        java.util.Map<String, String> shortcuts = java.util.Map.of(
                "ban", "ban", "tempban", "ban", "punish", "punish", "playban", "",
                "warn", "warn", "unban", "unban", "vanish", "vanish", "guide", "guide");
        if (!used.equals("playban") && !used.equals("pb") && shortcuts.containsKey(used)
                && plugin.getConfig().getBoolean("short-commands", true)) {
            String mapped = shortcuts.get(used);
            String[] shifted = new String[args.length + (mapped.isEmpty() ? 0 : 1)];
            if (!mapped.isEmpty()) {
                shifted[0] = mapped;
                System.arraycopy(args, 0, shifted, 1, args.length);
                args = shifted;
            }
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase();

        if (sender instanceof Player player && plugin.punishments().inLimbo(player.getUniqueId())
                && (sub.isEmpty() || sub.equals("tasks") || sub.equals("status") || !staff)) {
            plugin.punishments().sendLimboStatus(player);
            return true;
        }
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
            case "vanish" -> {
                if (!(sender instanceof Player player)) lang().send(sender, "error.players-only");
                else if (!player.hasPermission("playban.vanish")) lang().send(sender, "error.no-permission");
                else plugin.punishments().setVanished(player, !plugin.punishments().isVanished(player.getUniqueId()));
            }
            case "guide", "help" -> {
                if (sender instanceof Player player) plugin.adminMenus().guide(player, 0);
                else for (String line : lang().raw("help").split("\n")) sender.sendMessage(lang().parse(line));
            }
            case "punish" -> punish(sender, args);
            case "apply" -> apply(sender, args);
            case "warn" -> {
                OfflinePlayer target = target(sender, args, "usage.warn");
                if (target == null) return true;
                String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : lang().raw("no-reason");
                plugin.punishments().warn(target.getUniqueId(), target.getName(), reason, sender.getName(), null);
            }
            case "note" -> {
                if (args.length < 3) {
                    lang().send(sender, "usage.note");
                    return true;
                }
                OfflinePlayer target = target(sender, args, "usage.note");
                if (target == null) return true;
                plugin.store().names.put(target.getUniqueId(), target.getName());
                plugin.punishments().addNote(target.getUniqueId(), sender.getName(), String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                lang().send(sender, "note.added", "player", target.getName());
            }
            case "profile" -> {
                if (!(sender instanceof Player player)) {
                    lang().send(sender, "error.players-only");
                } else if (args.length < 2) {
                    lang().send(sender, "usage.profile");
                } else {
                    plugin.adminMenus().profileByName(player, args[1]);
                }
            }
            case "ban", "specban" -> {
                if (args.length < 3) {
                    lang().send(sender, "usage." + sub);
                    return true;
                }
                OfflinePlayer target = target(sender, args, "usage." + sub);
                if (target == null) return true;
                boolean permanent = CategoryRegistry.isPermanent(args[2]);
                long ms = permanent ? 0 : TimeUtil.parse(args[2]);
                if (!permanent && ms <= 0) {
                    lang().send(sender, "error.bad-time", "input", args[2]);
                    return true;
                }
                String reason = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : lang().raw("no-reason");
                String error = plugin.punishments().execute(target.getUniqueId(), target.getName(), sub.equals("ban") ? "BAN" : "SPECBAN", ms, reason, sender.getName(), null, null, null);
                if (error != null) lang().send(sender, error, "player", target.getName());
            }
            case "unban" -> {
                if (args.length < 2) {
                    lang().send(sender, "usage.unban");
                    return true;
                }
                Punishment ban = null;
                for (Punishment b : plugin.store().bans.values()) if (b.name.equalsIgnoreCase(args[1])) ban = b;
                if (ban == null) {
                    // maybe it is a ban ID; a PlayBan ID ends the PlayBan instead
                    Punishment byId = plugin.punishments().byBanId(args[1]);
                    if (byId != null && plugin.store().bans.containsKey(byId.uuid)) ban = byId;
                    else if (byId != null) {
                        plugin.punishments().end(byId, "PARDONED", sender.getName());
                        return true;
                    }
                }
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
        if (!sender.hasPermission("playban.staff")) return out;
        String last = args[args.length - 1].toLowerCase();
        if (args.length == 1) {
            for (String s : STAFF_SUBS) if (s.startsWith(last)) out.add(s);
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(last)) out.add(p.getName());
            if (sender.hasPermission("playban.admin")) for (String s : ADMIN_SUBS) if (s.startsWith(last)) out.add(s);
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("unban")) {
                for (Punishment b : plugin.store().bans.values()) if (b.name.toLowerCase().startsWith(last)) out.add(b.name);
            } else if (List.of("punish", "apply", "warn", "ban", "specban", "note", "profile").contains(sub)) {
                for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(last)) out.add(p.getName());
            } else if (sub.equals("lang")) {
                for (String c : lang().available()) if (c.startsWith(last)) out.add(c);
            } else if (List.of("pardon", "addtime", "removetime", "info", "history").contains(sub)) {
                for (Punishment p : plugin.store().active.values()) if (p.name.toLowerCase().startsWith(last)) out.add(p.name);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("apply")) {
            for (Category c : plugin.categories().all()) if (c.id.startsWith(last)) out.add(c.id);
        } else if (args.length == 3 && List.of("punish", "ban", "specban", "addtime", "removetime").contains(args[0].toLowerCase())) {
            for (String s : plugin.getConfig().getStringList("duration-presets")) if (s.startsWith(last)) out.add(s);
        }
        return out;
    }
}
