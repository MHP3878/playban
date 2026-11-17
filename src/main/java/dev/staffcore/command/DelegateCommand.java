package dev.staffcore.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * A command PlayBan registers at runtime instead of in plugin.yml, so that switching it off
 * really frees the name for another plugin (for example EssentialsX's /ban or /spawn).
 */
public final class DelegateCommand extends Command implements PluginIdentifiableCommand {
    private final Plugin plugin;
    private final TabExecutor executor;

    public DelegateCommand(Plugin plugin, String name, String description, String permission, TabExecutor executor) {
        this(plugin, name, description, permission, executor, List.of());
    }

    public DelegateCommand(Plugin plugin, String name, String description, String permission, TabExecutor executor, List<String> aliases) {
        super(name, description, "/" + name, aliases);
        this.plugin = plugin;
        this.executor = executor;
        if (permission != null) setPermission(permission);
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermission(sender)) return true;
        return executor.onCommand(sender, this, label, args);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (!testPermissionSilent(sender)) return List.of();
        List<String> out = executor.onTabComplete(sender, this, alias, args);
        return out == null ? List.of() : out;
    }

    @Override
    public Plugin getPlugin() {
        return plugin;
    }
}
