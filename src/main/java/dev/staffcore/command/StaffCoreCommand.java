package dev.staffcore.command;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import dev.staffcore.CategoryRegistry;
import dev.staffcore.TimeUtil;
import dev.staffcore.model.Category;
import dev.staffcore.model.Punishment;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class StaffCoreCommand implements TabExecutor {
    private static final List<String> STAFF_SUBS = List.of("menu", "punish", "apply", "warn", "ban", "specban", "ipban", "mute", "unmute", "unban", "note", "profile", "pardon", "addtime", "removetime", "info", "list", "history", "guide", "vanish");
    private static final List<String> ADMIN_SUBS = List.of("reload", "lang");
    private final StaffCorePlugin plugin;

    public StaffCoreCommand(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean staff = sender.hasPermission("staffcore.staff");
        // Short commands: /ban Steve 1d -> handled like /playban ban Steve 1d
        String used = label.toLowerCase();
        if (used.contains(":")) used = used.substring(used.indexOf(':') + 1);
        if (SHORTCUTS.containsKey(used) && !plugin.getConfig().getBoolean("short-commands", true)) {
            lang().send(sender, "error.short-commands-off", "command", SHORTCUTS.get(used));
            return true;
        }
        args = expandShortcut(used, args);
        String sub = args.length == 0 ? "" : args[0].toLowerCase();

        // these two have their own permission, so they come before the staff check
        if (sub.equals("guide") || sub.equals("help")) {
            if (sender instanceof Player player) plugin.adminMenus().guide(player, 0);
            else for (String line : lang().raw("help").split("\n")) sender.sendMessage(lang().parse(line));
            return true;
        }
        if (sub.equals("vanish")) {
            if (!(sender instanceof Player player)) lang().send(sender, "error.players-only");
            else if (!player.hasPermission("staffcore.vanish")) lang().send(sender, "error.no-permission");
            else plugin.punishments().setVanished(player, !plugin.punishments().isVanished(player.getUniqueId()));
            return true;
        }

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
            case "mute", "ipban" -> {
                if (args.length < 3 && sub.equals("ipban")) {
                    lang().send(sender, "usage.ipban");
                    return true;
                }
                if (args.length < 2) {
                    lang().send(sender, "usage." + sub);
                    return true;
                }
                if (sub.equals("ipban") && !sender.hasPermission("staffcore.ipban")) {
                    lang().send(sender, "error.no-permission");
                    return true;
                }
                OfflinePlayer target = target(sender, args, "usage." + sub);
                if (target == null) return true;
                // a time is optional for a mute: without one it is permanent
                String when = args.length > 2 ? args[2] : "perm";
                boolean permanent = CategoryRegistry.isPermanent(when);
                long ms = permanent ? 0 : TimeUtil.parse(when);
                int reasonFrom = 3;
                if (!permanent && ms <= 0) {
                    if (Character.isDigit(when.charAt(0))) {
                        lang().send(sender, "error.bad-time", "input", when);
                        return true;
                    }
                    ms = 0;
                    reasonFrom = 2;
                }
                String reason = args.length > reasonFrom ? String.join(" ", Arrays.copyOfRange(args, reasonFrom, args.length)) : lang().raw("no-reason");
                String error = plugin.punishments().execute(target.getUniqueId(), target.getName(), sub.equals("mute") ? "MUTE" : "IPBAN",
                        ms, reason, sender.getName(), null, null, null);
                if (error != null) lang().send(sender, error, "player", target.getName());
            }
            case "unmute" -> {
                if (args.length < 2) {
                    lang().send(sender, "usage.unmute");
                    return true;
                }
                Punishment m = null;
                for (Punishment x : plugin.store().mutes.values()) if (x.name.equalsIgnoreCase(args[1]) || args[1].equalsIgnoreCase(x.banId)) m = x;
                if (m == null) lang().send(sender, "error.not-muted", "player", args[1]);
                else {
                    plugin.punishments().unmute(m, sender.getName());
                    lang().send(sender, "mute.lifted", "player", m.name);
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
                int reasonFrom = 3;
                if (!permanent && ms <= 0) {
                    if (Character.isDigit(args[2].charAt(0))) {
                        // looks like a mistyped time, better not ban someone forever by accident
                        lang().send(sender, "error.bad-time", "input", args[2]);
                        return true;
                    }
                    if (sub.equals("ban") && plugin.categories().get(args[2]) != null) {
                        apply(sender, new String[]{"apply", args[1], args[2]});
                        return true;
                    }
                    // like vanilla: /ban Steve Hacking bans for good with that reason
                    permanent = true;
                    ms = 0;
                    reasonFrom = 2;
                }
                String reason = args.length > reasonFrom ? String.join(" ", Arrays.copyOfRange(args, reasonFrom, args.length)) : lang().raw("no-reason");
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
                if (!sender.hasPermission("staffcore.admin")) {
                    lang().send(sender, "error.no-permission");
                    return true;
                }
                plugin.reload();
                lang().send(sender, "reloaded");
            }
            case "lang" -> {
                if (!sender.hasPermission("staffcore.admin")) {
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
        boolean exempt = target.getPlayer() != null ? target.getPlayer().hasPermission("staffcore.exempt")
                : plugin.store().exempt.contains(target.getUniqueId());
        if (exempt && sender instanceof Player && !sender.hasPermission("staffcore.exempt.bypass")) {
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

    private static final java.util.Map<String, String> SHORTCUTS = java.util.Map.of(
            "ban", "ban", "tempban", "ban", "punish", "punish",
            "warn", "warn", "unban", "unban", "vanish", "vanish", "guide", "guide",
            "mute", "mute", "unmute", "unmute", "ipban", "ipban");

    /** /ban Steve 1d becomes {"ban", "Steve", "1d"}, so short commands run through the same code as /playban. */
    private String[] expandShortcut(String used, String[] args) {
        if (used.contains(":")) used = used.substring(used.indexOf(':') + 1);
        String mapped = SHORTCUTS.get(used);
        if (mapped == null || !plugin.getConfig().getBoolean("short-commands", true)) return args;
        String[] shifted = new String[args.length + 1];
        shifted[0] = mapped;
        System.arraycopy(args, 0, shifted, 1, args.length);
        return shifted;
    }

    private void punish(CommandSender sender, String[] args) {
        if (args.length == 2 && sender instanceof Player player) {
            OfflinePlayer target = target(sender, args, "usage.punish");
            if (target != null) plugin.adminMenus().punishPlayer(player, target.getUniqueId(), target.getName());
            return;
        }
        if (args.length < 3) {
            lang().send(sender, "usage.punish");
            return;
        }
        if (plugin.categories().get(args[2]) != null && TimeUtil.parse(args[2]) <= 0) {
            apply(sender, new String[]{"apply", args[1], args[2]});
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

    // ---------------------------------------------------------------- suggestions

    private static void add(List<String> out, String value, String typed) {
        if (value != null && !value.isEmpty() && value.toLowerCase().startsWith(typed) && !out.contains(value)) out.add(value);
    }

    /** Online players first, then everyone PlayBan has seen before. */
    private void players(List<String> out, String typed) {
        for (Player p : Bukkit.getOnlinePlayers()) add(out, p.getName(), typed);
        for (String name : plugin.store().names.values()) {
            if (out.size() >= 40) break;
            add(out, name, typed);
        }
    }

    private void durations(List<String> out, String typed, boolean permanent) {
        for (String d : plugin.getConfig().getStringList("duration-presets")) add(out, d, typed);
        for (String d : List.of("30m", "1h", "12h", "1d", "3d", "7d", "14d", "30d")) add(out, d, typed);
        if (permanent) add(out, "perm", typed);
    }

    private void reasons(List<String> out, String typed) {
        for (String r : plugin.getConfig().getStringList("reason-presets")) add(out, r, typed);
        for (Category c : plugin.categories().all()) add(out, c.name, typed);
    }

    private void categories(List<String> out, String typed) {
        for (Category c : plugin.categories().all()) add(out, c.id, typed);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String used = alias.toLowerCase();
        if (used.contains(":")) used = used.substring(used.indexOf(':') + 1);
        if (SHORTCUTS.containsKey(used) && !plugin.getConfig().getBoolean("short-commands", true)) return out;
        args = expandShortcut(used, args);
        int at = args.length - 1;
        String typed = args[at].toLowerCase();
        if (!sender.hasPermission("staffcore.staff")) {
            if (at == 0) for (String s : List.of("tasks", "guide")) add(out, s, typed);
            return out;
        }
        if (at == 0) {
            for (String s : STAFF_SUBS) add(out, s, typed);
            if (sender.hasPermission("staffcore.admin")) for (String s : ADMIN_SUBS) add(out, s, typed);
            for (Player p : Bukkit.getOnlinePlayers()) add(out, p.getName(), typed);
            return out;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "punish" -> {
                if (at == 1) players(out, typed);
                else if (at == 2) {
                    categories(out, typed);
                    durations(out, typed, false);
                } else if (at == 3 && TimeUtil.parse(args[2]) > 0) reasons(out, typed);
            }
            case "apply" -> {
                if (at == 1) players(out, typed);
                else if (at == 2) categories(out, typed);
            }
            case "warn" -> {
                if (at == 1) players(out, typed);
                else if (at == 2) reasons(out, typed);
            }
            case "ban", "specban", "mute", "ipban" -> {
                if (at == 1) players(out, typed);
                else if (at == 2) durations(out, typed, true);
                else if (at == 3) reasons(out, typed);
            }
            case "unmute" -> {
                if (at == 1) for (Punishment m : plugin.store().mutes.values()) add(out, m.name, typed);
            }
            case "unban" -> {
                if (at == 1) {
                    for (Punishment b : plugin.store().bans.values()) {
                        add(out, b.name, typed);
                        add(out, b.banId, typed);
                    }
                    for (Punishment p : plugin.store().active.values()) add(out, p.banId, typed);
                }
            }
            case "pardon", "info" -> {
                if (at == 1) for (Punishment p : plugin.store().active.values()) add(out, p.name, typed);
            }
            case "addtime", "removetime" -> {
                if (at == 1) for (Punishment p : plugin.store().active.values()) add(out, p.name, typed);
                else if (at == 2) durations(out, typed, false);
            }
            case "note", "profile", "history" -> {
                if (at == 1) players(out, typed);
            }
            case "lang" -> {
                if (at == 1 && sender.hasPermission("staffcore.admin")) for (String c : lang().available()) add(out, c, typed);
            }
            default -> {
                // "/playban <player>": nothing more to suggest
            }
        }
        return out;
    }
}
