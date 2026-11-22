package dev.modcheck;

import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** ModCheck: see clients and mods, block mods by name, and do what the settings say. */
public class ModCheckPlugin extends JavaPlugin {
    private Lang lang;
    private Guard guard;
    private Menus menus;
    private ChatInput input;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        addNewSettings();
        noBuiltInDefaults();
        lang = new Lang(this);
        lang.load(getConfig().getString("language", "en"));
        guard = new Guard(this);
        guard.load();
        menus = new Menus(this);
        input = new ChatInput(this);
        getServer().getPluginManager().registerEvents(guard, this);
        getServer().getPluginManager().registerEvents(input, this);
        getServer().getPluginManager().registerEvents(new Menu.Clicks(), this);
        guard.registerVirtualSignListener();
        Commands commands = new Commands(this);
        for (String name : new String[]{"modcheck", "modblock", "modunblock"}) {
            PluginCommand c = getCommand(name);
            if (c != null) {
                c.setExecutor(commands);
                c.setTabCompleter(commands);
            }
        }
    }

    /**
     * Settings that came with a newer version are added to an older config; the server's own values,
     * and the list of blocked mods, stay exactly as they are.
     */
    private void addNewSettings() {
        var in = getResource("config.yml");
        if (in == null) return;
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (key.startsWith("rules") || defaults.isConfigurationSection(key)) continue;
            if (!getConfig().isSet(key)) {
                getConfig().set(key, defaults.get(key));
                changed = true;
            }
        }
        if (changed) saveConfig();
    }

    /**
     * The config file alone counts. Without this, a mod removed from the list would come back through
     * the copy of the config inside the jar.
     */
    private void noBuiltInDefaults() {
        getConfig().setDefaults(new org.bukkit.configuration.MemoryConfiguration());
    }

    @Override
    public void onDisable() {
        // signs of the check on older servers: every block back before the server goes down
        if (guard != null) guard.putEverythingBack();
    }

    public void reload() {
        reloadConfig();
        noBuiltInDefaults();
        lang.load(getConfig().getString("language", "en"));
        guard.load();
    }

    public Lang lang() { return lang; }

    public Guard guard() { return guard; }

    public Menus menus() { return menus; }

    public ChatInput input() { return input; }
}
