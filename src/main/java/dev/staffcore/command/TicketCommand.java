package dev.staffcore.command;

import dev.staffcore.StaffCorePlugin;
import dev.staffcore.TicketManager;
import dev.staffcore.model.Ticket;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /ticket for everyone, plus /ticket show, open and stats for the team. */
public final class TicketCommand implements TabExecutor {
    private final StaffCorePlugin plugin;

    public TicketCommand(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "error.players-only");
            return true;
        }
        boolean staff = player.hasPermission("staffcore.staff");
        String sub = args.length == 0 ? "" : args[0].toLowerCase();

        if (staff && sub.equals("show")) {
            String reason = null;
            if (args.length > 1) {
                TicketManager.Reason r = plugin.tickets().reason(args[1]);
                if (r == null) {
                    plugin.lang().send(player, "ticket.error.unknown-reason", "reason", args[1]);
                    return true;
                }
                reason = r.id();
            }
            plugin.ticketMenus().list(player, "ACTIVE", reason, 0);
            return true;
        }
        if (staff && sub.equals("open")) {
            Ticket t = null;
            try {
                if (args.length > 1) t = plugin.tickets().get(Integer.parseInt(args[1].replace("#", "")));
            } catch (NumberFormatException ignored) {
                // falls through to the error below
            }
            if (t == null) plugin.lang().send(player, "ticket.error.unknown-ticket");
            else plugin.ticketMenus().view(player, t);
            return true;
        }
        if (staff && sub.equals("stats")) {
            plugin.ticketMenus().stats(player);
            return true;
        }
        if (staff && sub.equals("back")) {
            if (plugin.tickets().canReturn(player.getUniqueId())) plugin.tickets().returnBack(player);
            return true;
        }

        if (!player.hasPermission("staffcore.ticket")) {
            plugin.lang().send(player, "error.no-permission");
        } else if (args.length == 0) {
            plugin.ticketMenus().pickReason(player);
        } else {
            boolean limited = plugin.punishments().limited(player.getUniqueId());
            plugin.tickets().create(player, limited ? "appeal" : "other", String.join(" ", args));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("staffcore.staff")) return out;
        String last = args[args.length - 1].toLowerCase();
        if (args.length == 1) {
            for (String s : List.of("show", "open", "stats", "back")) if (s.startsWith(last)) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("show")) {
            for (TicketManager.Reason r : plugin.tickets().reasons()) if (r.id().startsWith(last)) out.add(r.id());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
            for (var t : plugin.tickets().all()) {
                String id = String.valueOf(t.id);
                if (t.active() && id.startsWith(last.replace("#", ""))) out.add(id);
            }
        }
        return out;
    }
}
