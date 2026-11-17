package dev.staffcore.command;

import dev.staffcore.AdminManager;
import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /tpadmin, /tphere, /tpback, /freeze, /invsee, /endersee, /teamchat, /clearchat, /fly, /heal, /god. */
public final class AdminCommand implements TabExecutor {
    /** Commands that take a player as the first argument, and whether it is required. */
    private static final List<String> NEEDS_PLAYER = List.of("tpadmin", "tphere", "freeze", "unfreeze", "invsee", "endersee");
    private final StaffCorePlugin plugin;

    public AdminCommand(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private AdminManager admin() {
        return plugin.admin();
    }

    private Player online(CommandSender sender, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) lang().send(sender, "admin.not-online", "player", name);
        return p;
    }

    /** Exempt players, and players of the same or a higher rank, cannot be pulled around or looked into. */
    private boolean shielded(Player staff, Player target) {
        if (staff == target) return false;
        boolean shield = (target.hasPermission("staffcore.exempt") && !staff.hasPermission("staffcore.exempt.bypass"))
                || (plugin.ranks() != null && plugin.ranks().active()
                && plugin.ranks().level(target.getUniqueId()) >= plugin.ranks().level(staff.getUniqueId())
                && !plugin.ranks().isTop(staff));
        if (shield) lang().send(staff, "error.exempt", "player", target.getName());
        return shield;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();
        if (cmd.equals("clearchat")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("staffcore.staff")) continue;
                for (int i = 0; i < 120; i++) p.sendMessage(Component.empty());
            }
            Bukkit.broadcast(lang().prefixed("admin.chat-cleared", "staff", sender.getName()));
            return true;
        }
        if (cmd.equals("teamchat")) {
            if (!(sender instanceof Player p)) {
                if (args.length == 0) return true;
                Component line = lang().msg("admin.teamchat-line", "player", "Console", "text", String.join(" ", args));
                Bukkit.getOnlinePlayers().stream().filter(x -> x.hasPermission("staffcore.teamchat")).forEach(x -> x.sendMessage(line));
                sender.sendMessage(line);
                return true;
            }
            if (args.length > 0) admin().teamMessage(p, String.join(" ", args));
            else lang().send(p, admin().toggleTeamChat(p) ? "admin.teamchat-on" : "admin.teamchat-off");
            return true;
        }
        if (!(sender instanceof Player staff)) {
            lang().send(sender, "error.players-only");
            return true;
        }
        if (NEEDS_PLAYER.contains(cmd) && args.length < 1) {
            lang().send(staff, "admin.usage." + cmd);
            return true;
        }
        switch (cmd) {
            case "tpadmin" -> {
                Player target = online(staff, args[0]);
                if (target == null) return true;
                admin().tpAdmin(staff, target);
                lang().send(staff, "admin.tpadmin", "player", target.getName());
            }
            case "tphere" -> {
                Player target = online(staff, args[0]);
                if (target == null || shielded(staff, target)) return true;
                target.teleport(staff.getLocation());
                lang().send(staff, "admin.tphere", "player", target.getName());
            }
            case "tpback" -> lang().send(staff, admin().back(staff) ? "admin.back" : "admin.no-back");
            case "freeze", "unfreeze" -> {
                Player target = online(staff, args[0]);
                if (target == null) return true;
                if (target.hasPermission("staffcore.exempt") && !staff.hasPermission("staffcore.exempt.bypass")) {
                    lang().send(staff, "error.exempt", "player", target.getName());
                    return true;
                }
                boolean on = cmd.equals("freeze") ? !admin().isFrozen(target.getUniqueId()) : false;
                admin().setFrozen(target, on, staff.getName());
                lang().send(staff, on ? "admin.frozen" : "admin.unfrozen", "player", target.getName());
            }
            case "invsee", "endersee" -> {
                Player target = online(staff, args[0]);
                if (target == null || shielded(staff, target)) return true;
                admin().openInventory(staff, target, cmd.equals("endersee"));
                if (!staff.hasPermission("staffcore.invsee.edit")) lang().send(staff, "admin.view-only");
            }
            case "fly", "heal", "god" -> {
                Player target = staff;
                if (args.length > 0) {
                    if (!staff.hasPermission("staffcore.admin")) {
                        lang().send(staff, "error.no-permission");
                        return true;
                    }
                    target = online(staff, args[0]);
                    if (target == null) return true;
                }
                switch (cmd) {
                    case "fly" -> lang().send(staff, admin().toggleFly(target) ? "admin.fly-on" : "admin.fly-off", "player", target.getName());
                    case "god" -> lang().send(staff, admin().toggleGod(target) ? "admin.god-on" : "admin.god-off", "player", target.getName());
                    default -> {
                        admin().heal(target);
                        lang().send(staff, "admin.healed", "player", target.getName());
                    }
                }
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
        String cmd = command.getName().toLowerCase();
        boolean takesPlayer = NEEDS_PLAYER.contains(cmd) || List.of("fly", "heal", "god").contains(cmd);
        if (args.length == 1 && takesPlayer) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (cmd.equals("unfreeze") && !admin().isFrozen(p.getUniqueId())) continue;
                if (p.getName().toLowerCase().startsWith(args[0].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }
}
