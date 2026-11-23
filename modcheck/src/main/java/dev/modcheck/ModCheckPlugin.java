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
    private Lumio lumio;
    private CheckRoom room;
    private Popups popups;

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
        lumio = new Lumio(this);
        lumio.enable();
        getServer().getPluginManager().registerEvents(lumio, this);
        popups = new Popups(this);
        room = new CheckRoom(this);
        getServer().getPluginManager().registerEvents(room, this);
        room.start();
        // once every plugin is up: ModCheck's own old bans move to the server's ban system
        getServer().getScheduler().runTask(this, () -> {
            int moved = guard.moveOwnBans();
            if (moved > 0) getLogger().info("Moved " + moved + " ModCheck ban(s) to " + guard.banSystem() + ": one ban list, one ban screen, /unban works.");
        });
        // say once at start what a mode without Lumio costs
        if (!lumio.mode().equals("required")) getServer().getConsoleSender().sendMessage(lang.prefixed("lumio.warn-incomplete"));
        getServer().getPluginManager().registerEvents(input, this);
        getServer().getPluginManager().registerEvents(new Menu.Clicks(), this);
        guard.registerVirtualSignListener();
        Commands commands = new Commands(this);
        for (String name : new String[]{"modcheck", "modblock", "modunblock", "inject", "mcroom"}) {
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
        // players in the check room go back to where they were before the server goes down
        if (room != null) room.stop();
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

    public Lumio lumio() { return lumio; }

    public CheckRoom room() { return room; }

    public Popups popups() { return popups; }
}
