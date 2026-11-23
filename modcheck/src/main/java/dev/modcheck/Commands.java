package dev.modcheck;

import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /modcheck, /modblock and /modunblock. */
public final class Commands implements TabExecutor {
    private final ModCheckPlugin plugin;

    public Commands(ModCheckPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private Guard guard() {
        return plugin.guard();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "modblock" -> block(sender, args);
            case "mcroom" -> {
                // the buttons of the check room's window
                if (sender instanceof Player p) plugin.room().command(p, args.length > 0 ? args[0] : "leave");
            }
            case "modunblock" -> {
                if (args.length == 0) lang().send(sender, "block.usage-unblock");
                else {
                    String name = String.join(" ", args);
                    lang().send(sender, guard().unblock(name, sender.getName()) ? "block.unblocked" : "block.not-blocked", "mod", name);
                }
            }
            default -> check(sender, args);
        }
        return true;
    }

    private void block(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player p) plugin.menus().blocked(p, 0);
            else lang().send(sender, "block.usage");
            return;
        }
        List<String> words = new ArrayList<>(List.of(args));
        boolean force = words.remove("--force");
        String typed = String.join(" ", words);
        KnownMods.Mod known = KnownMods.find(typed);
        if (known == null && !force) {
            List<KnownMods.Mod> close = KnownMods.suggest(typed);
            if (!close.isEmpty()) {
                lang().send(sender, "block.did-you-mean", "mod", typed);
                for (int i = 0; i < Math.min(5, close.size()); i++) {
                    KnownMods.Mod m = close.get(i);
                    sender.sendMessage(lang().msg("block.suggestion", "mod", m.name(), "id", m.id()).clickEvent(ClickEvent.runCommand("/modblock " + m.id())));
                }
                sender.sendMessage(lang().msg("block.as-typed", "mod", KnownMods.normalize(typed)).clickEvent(ClickEvent.runCommand("/modblock " + typed + " --force")));
                return;
            }
        }
        String id = guard().block(typed, sender.getName());
        if (id == null) {
            lang().send(sender, "block.usage");
            return;
        }
        Guard.Rule r = guard().rule(id);
        lang().send(sender, known != null ? "block.done" : "block.done-unknown", "mod", r.display(),
                "action", lang().raw("action." + guard().action(r).toLowerCase(Locale.ROOT)));
        // straight to its settings, to choose what happens
        if (sender instanceof Player p) plugin.menus().rule(p, id);
    }

    private OfflinePlayer known(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
        return op;
    }

    private void check(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player p) plugin.menus().main(p);
            else lang().send(sender, "check.usage");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (!sender.hasPermission("modcheck.reload")) {
                    lang().send(sender, "no-permission");
                    return;
                }
                plugin.reload();
                lang().send(sender, "reloaded");
                return;
            }
            case "raum", "room", "pruefraum" -> {
                if (!sender.hasPermission("modcheck.settings") || !(sender instanceof Player p)) {
                    lang().send(sender, "no-permission");
                    return;
                }
                plugin.menus().room(p);
                return;
            }
            case "fenster", "window" -> {
                // what a player without Lumio sees: the window, shown to yourself
                if (!sender.hasPermission("modcheck.settings") || !(sender instanceof Player p)) {
                    lang().send(sender, "no-permission");
                    return;
                }
                plugin.popups().lumio(p, "missing");
                return;
            }
            case "lumio" -> {
                // whether players need the Lumio client mod: only for admins
                if (!sender.hasPermission("modcheck.settings")) {
                    lang().send(sender, "no-permission");
                    return;
                }
                if (args.length > 1) {
                    String mode = Lumio.parseMode(args[1]);
                    if (mode == null) {
                        lang().send(sender, "lumio.usage");
                        return;
                    }
                    plugin.lumio().setMode(mode);
                    guard().log(sender.getName(), "lumio mode", null, mode);
                }
                plugin.lumio().explain(sender);
                return;
            }
            case "unban" -> {
                if (!sender.hasPermission("modcheck.unban") || args.length < 2) {
                    lang().send(sender, args.length < 2 ? "check.usage" : "no-permission");
                    return;
                }
                OfflinePlayer op = known(args[1]);
                boolean done = false;
                for (Guard.Ban b : guard().bans()) if (b.name().equalsIgnoreCase(args[1])) done |= guard().unban(b.id(), sender.getName());
                // also through the server's ban system (StaffCore, LiteBans, ... or the game's ban list)
                if (!done && op != null) done = guard().unbanAnywhere(op.getUniqueId(), args[1], sender.getName());
                lang().send(sender, done ? "ban.unbanned" : "ban.not-banned", "player", args[1]);
                return;
            }
            case "reset" -> {
                if (!sender.hasPermission("modcheck.unban")) {
                    lang().send(sender, "no-permission");
                    return;
                }
                OfflinePlayer op = args.length > 1 ? known(args[1]) : null;
                if (op == null) lang().send(sender, "not-found", "player", args.length > 1 ? args[1] : "?");
                else {
                    guard().resetWarnings(op.getUniqueId());
                    lang().send(sender, "check.reset", "player", args[1]);
                }
                return;
            }
            default -> {
                // a player
            }
        }
        Player t = Bukkit.getPlayerExact(args[0]);
        if (t == null) {
            lang().send(sender, "not-found", "player", args[0]);
            return;
        }
        plugin.menus().check(sender, t);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "modblock" -> {
                if (args.length == 1) for (KnownMods.Mod m : KnownMods.ALL) if (typed.isEmpty() || m.id().startsWith(KnownMods.normalize(typed))) out.add(m.id());
            }
            case "mcroom" -> {
                // nothing to suggest: the window's buttons use it
            }
            case "modunblock" -> {
                if (args.length == 1) for (Guard.Rule r : guard().rules()) if (r.id().startsWith(typed)) out.add(r.id());
            }
            default -> {
                if (args.length == 1) {
                    for (String s : List.of("unban", "reset", "reload", "lumio", "raum", "fenster")) if (s.startsWith(typed)) out.add(s);
                    for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(typed)) out.add(p.getName());
                } else if (args.length == 2 && args[0].equalsIgnoreCase("lumio")) {
                    for (String s : List.of("aus", "freiwillig", "pflicht", "off", "optional", "required")) if (s.startsWith(typed)) out.add(s);
                } else if (args.length == 2 && args[0].equalsIgnoreCase("unban")) {
                    for (Guard.Ban b : guard().bans()) if (b.name().toLowerCase(Locale.ROOT).startsWith(typed)) out.add(b.name());
                } else if (args.length == 2) {
                    for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(typed)) out.add(p.getName());
                }
            }
        }
        return out;
    }
}
