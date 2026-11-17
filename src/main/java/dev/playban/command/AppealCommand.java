package dev.playban.command;

import dev.playban.PlayBanPlugin;
import dev.playban.model.Punishment;
import dev.playban.model.Ticket;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

/**
 * /appeal <ban-ID> <text>: a punished player asks to be unbanned.
 * It only works with the ID of the player's own punishment, so nobody can appeal for someone else.
 */
public final class AppealCommand implements TabExecutor {
    private final PlayBanPlugin plugin;

    public AppealCommand(PlayBanPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "error.players-only");
            return true;
        }
        Punishment own = plugin.store().bans.get(player.getUniqueId());
        if (own == null) own = plugin.punishments().get(player.getUniqueId());
        if (own == null) {
            plugin.lang().send(player, "appeal.not-punished");
            return true;
        }
        if (args.length < 2) {
            plugin.lang().send(player, "appeal.usage", "id", own.banId == null ? "?" : own.banId);
            return true;
        }
        String given = args[0].trim().toUpperCase().replace("#", "");
        if (own.banId == null || !own.banId.equals(given)) {
            plugin.lang().send(player, "appeal.wrong-id");
            return true;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (text.length() < 10) {
            plugin.lang().send(player, "appeal.too-short");
            return true;
        }
        Ticket t = plugin.tickets().create(player, "appeal", plugin.lang().plain("appeal.ticket-text", "id", own.banId, "text", text));
        if (t != null) plugin.lang().send(player, "appeal.received", "id", own.banId, "ticket", t.id);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender instanceof Player player) {
            Punishment own = plugin.store().bans.get(player.getUniqueId());
            if (own == null) own = plugin.punishments().get(player.getUniqueId());
            if (own != null && own.banId != null) return List.of(own.banId);
        }
        return List.of();
    }
}
