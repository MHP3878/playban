package dev.staffcore.command;

import dev.staffcore.StaffCorePlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /voice: the team's group overview, plus report, manage and speak for players. */
public final class VoiceCommand implements TabExecutor {
    private final StaffCorePlugin plugin;

    public VoiceCommand(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "error.players-only");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "report" -> plugin.voiceMenus().report(player);
            case "manage" -> plugin.voiceMenus().manage(player);
            case "speak" -> plugin.lang().send(player, plugin.voice().agreeToSpeak(player) ? "voice.speaking" : "voice.nothing-to-agree");
            default -> {
                if (player.hasPermission("staffcore.voice")) plugin.voiceMenus().groups(player);
                else for (String line : plugin.lang().raw("voice.help").split("\n")) player.sendMessage(plugin.lang().parse(line));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) for (String s : List.of("report", "manage", "speak")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        return out;
    }
}
