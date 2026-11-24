package dev.bastion;

import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Bastion (called ModCheck before): anti-cheat, cheat clients, injectors, cheat resource packs and
 * mods, with a file on every player and punishments that grow.
 */
public class BastionPlugin extends JavaPlugin {
    private Lang lang;
    private Guard guard;
    private Menus menus;
    private ChatInput input;
    private Lumio lumio;
    private CheckRoom room;
    private Popups popups;
    private Akte akte;
    private Punish punish;
    private dev.bastion.ac.AntiCheat antiCheat;
    private Ranks ranks;
    private Setup setup;

    @Override
    public void onLoad() {
        takeOverModCheck();
        // Intave reads its settings early: Bastion's go in before it starts working
        try {
            saveDefaultConfig();
            if (getConfig().getBoolean("anticheat.intave.enabled", true) && getConfig().getBoolean("anticheat.intave.configure", true)) {
                org.bukkit.plugin.Plugin intave = getServer().getPluginManager().getPlugin("Intave");
                if (intave != null) dev.bastion.ac.IntaveHook.configure(this, dev.bastion.ac.AcManager.jarOfPlugin(intave));
            }
        } catch (RuntimeException e) {
            getLogger().warning("Could not set up Intave: " + e.getMessage());
        }
    }

    /**
     * Bastion was called ModCheck: its settings, blocked mods, bans and history move over once (the
     * texts not: the new ones come with Bastion).
     */
    private void takeOverModCheck() {
        java.io.File old = new java.io.File(getDataFolder().getParentFile(), "ModCheck");
        if (getDataFolder().exists() || !old.isDirectory()) return;
        try {
            java.nio.file.Path from = old.toPath(), to = getDataFolder().toPath();
            try (var files = java.nio.file.Files.walk(from)) {
                for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files::iterator) {
                    java.nio.file.Path rel = from.relativize(f);
                    if (rel.toString().startsWith("lang")) continue;
                    java.nio.file.Path target = to.resolve(rel);
                    if (java.nio.file.Files.isDirectory(f)) java.nio.file.Files.createDirectories(target);
                    else java.nio.file.Files.copy(f, target);
                }
            }
            getLogger().info("Took over the settings, bans and history of ModCheck (now Bastion). The old ModCheck jar can go.");
        } catch (java.io.IOException e) {
            getLogger().warning("Could not take over ModCheck's settings: " + e.getMessage());
        }
    }

    @Override
    public void onEnable() {
        // the old ModCheck next to Bastion would check and punish everything twice
        var oldPlugin = getServer().getPluginManager().getPlugin("ModCheck");
        if (oldPlugin != null && oldPlugin != this) {
            getLogger().warning("ModCheck is installed as well; Bastion replaces it, so it is switched off. Delete ModCheck's jar.");
            getServer().getPluginManager().disablePlugin(oldPlugin);
        }
        saveDefaultConfig();
        addNewSettings();
        noBuiltInDefaults();
        lang = new Lang(this);
        lang.load(getConfig().getString("language", "en"));
        akte = new Akte(this);
        punish = new Punish(this);
        punish.load();
        getServer().getPluginManager().registerEvents(punish, this);
        guard = new Guard(this);
        guard.load();
        ranks = new Ranks(this);
        getServer().getPluginManager().registerEvents(ranks, this);
        setup = new Setup(this);
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
        // once every plugin is up: Bastion's own old bans move to the server's ban system
        getServer().getScheduler().runTask(this, () -> {
            int moved = guard.moveOwnBans();
            if (moved > 0) getLogger().info("Moved " + moved + " Bastion ban(s) to " + guard.banSystem() + ": one ban list, one ban screen, /unban works.");
        });
        // say once at start what a mode without Lumio costs
        if (!lumio.mode().equals("required")) getServer().getConsoleSender().sendMessage(lang.prefixed("lumio.warn-incomplete"));
        getServer().getPluginManager().registerEvents(input, this);
        getServer().getPluginManager().registerEvents(new Menu.Clicks(), this);
        guard.registerVirtualSignListener();
        antiCheat = new dev.bastion.ac.AntiCheat(this);
        getServer().getPluginManager().registerEvents(antiCheat, this);
        antiCheat.start();
        // Grim and Intave, the two other anti-cheats (after every plugin is up): set up, other anti-cheats
        // off, missing ones fetched from Modrinth, then the verdict is a vote of all of them
        getServer().getScheduler().runTask(this, () -> {
            dev.bastion.ac.AcManager manager = new dev.bastion.ac.AcManager(this);
            manager.removeOthers();
            manager.warnOldStaffCore();
            dev.bastion.ac.GrimHook.register(this);
            dev.bastion.ac.IntaveHook.register(this);
            var consensus = antiCheat.consensus();
            getLogger().info("Anti-cheats with a say: " + consensus.describe()
                    + (consensus.active() ? " (a ban needs all of them, all but one watch)" : " (Bastion alone)"));
            manager.checkAsync();
        });
        // the files of the players are written a moment after they changed
        getServer().getScheduler().runTaskTimer(this, akte::flush, 600L, 600L);
        Commands commands = new Commands(this);
        for (String name : new String[]{"bastion", "modblock", "modunblock", "inject", "mcroom", "setup", "akte"}) {
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
        if (antiCheat != null) antiCheat.stop();
        if (akte != null) akte.flush();
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
        punish.load();
        if (antiCheat != null) antiCheat.load();
    }

    public Lang lang() { return lang; }

    public Guard guard() { return guard; }

    public Menus menus() { return menus; }

    public ChatInput input() { return input; }

    public Lumio lumio() { return lumio; }

    public CheckRoom room() { return room; }

    public Popups popups() { return popups; }

    public Akte akte() { return akte; }

    public Ranks ranks() { return ranks; }

    public Setup setup() { return setup; }

    public Punish punish() { return punish; }

    public dev.bastion.ac.AntiCheat antiCheat() { return antiCheat; }

    /** A line for the team: everybody with bastion.notify, and the console. */
    public void notifyTeam(net.kyori.adventure.text.Component line) {
        for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) if (p.hasPermission("bastion.notify")) p.sendMessage(line);
        getServer().getConsoleSender().sendMessage(line);
    }
}
