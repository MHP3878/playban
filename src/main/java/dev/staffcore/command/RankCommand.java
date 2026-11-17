package dev.staffcore.command;

import dev.staffcore.Lang;
import dev.staffcore.RankManager;
import dev.staffcore.StaffCorePlugin;
import dev.staffcore.model.Rank;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /rank: give ranks, list them, and the one-time /rank setup for the first owner. */
public final class RankCommand implements TabExecutor {
    private final StaffCorePlugin plugin;

    public RankCommand(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private RankManager ranks() {
        return plugin.ranks();
    }

    private OfflinePlayer find(CommandSender sender, String name) {
        OfflinePlayer p = Bukkit.getPlayerExact(name);
        if (p == null) p = Bukkit.getOfflinePlayerIfCached(name);
        if (p == null || p.getName() == null) {
            lang().send(sender, "error.unknown-player", "player", name);
            return null;
        }
        return p;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase();
        switch (sub) {
            case "setup" -> {
                if (!(sender instanceof Player p)) {
                    lang().send(sender, "rank.setup-console");
                } else if (ranks().active()) {
                    lang().send(sender, "rank.setup-done-already");
                } else if (!p.isOp()) {
                    lang().send(sender, "rank.setup-needs-op");
                } else {
                    Rank top = ranks().top();
                    ranks().set(p.getUniqueId(), top);
                    plugin.store().names.put(p.getUniqueId(), p.getName());
                    lang().send(p, "rank.setup-ok", "rank", top.name);
                }
            }
            case "menu" -> {
                if (sender instanceof Player p) plugin.rankMenus().list(p);
                else lang().send(sender, "error.players-only");
            }
            case "list" -> {
                for (Rank r : ranks().all()) {
                    sender.sendMessage(lang().msg("rank.list-entry", "level", r.level, "members", ranks().members(r).size())
                            .append(MiniMessage.miniMessage().deserialize(r.prefix + r.name)));
                }
            }
            case "info" -> {
                if (args.length < 2) {
                    lang().send(sender, "rank.usage");
                    return true;
                }
                OfflinePlayer t = find(sender, args[1]);
                if (t == null) return true;
                Rank r = ranks().of(t.getUniqueId());
                lang().send(sender, "rank.info", "player", t.getName(), "rank", r == null ? "-" : r.name, "level", r == null ? 0 : r.level);
            }
            case "set", "remove" -> {
                boolean remove = sub.equals("remove");
                if (args.length < (remove ? 2 : 3)) {
                    lang().send(sender, "rank.usage");
                    return true;
                }
                OfflinePlayer t = find(sender, args[1]);
                if (t == null) return true;
                Rank target = remove ? ranks().defaultRank() : ranks().get(args[2]);
                if (target == null) {
                    lang().send(sender, "rank.unknown", "rank", args[2]);
                    return true;
                }
                // you cannot touch anyone at or above your level, nor hand out such a rank
                if (!ranks().mayManage(sender, ranks().level(t.getUniqueId())) || !ranks().mayManage(sender, target.level)) {
                    lang().send(sender, "rank.not-allowed");
                    return true;
                }
                ranks().set(t.getUniqueId(), target);
                plugin.store().names.put(t.getUniqueId(), t.getName());
                lang().send(sender, "rank.set", "player", t.getName(), "rank", target.name);
                if (t.getPlayer() != null && t.getPlayer() != sender) lang().send(t.getPlayer(), "rank.set-you", "rank", target.name);
            }
            default -> lang().send(sender, "rank.usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String typed = args[args.length - 1].toLowerCase();
        if (args.length == 1) {
            for (String s : List.of("menu", "set", "remove", "list", "info", "setup")) if (s.startsWith(typed)) out.add(s);
        } else if (args.length == 2 && List.of("set", "remove", "info").contains(args[0].toLowerCase())) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(typed)) out.add(p.getName());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            for (Rank r : ranks().all()) {
                if (r.id.startsWith(typed) && ranks().mayManage(sender, r.level)) out.add(r.id);
            }
        }
        return out;
    }
}
