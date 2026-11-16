package dev.playban;

import dev.playban.command.PlayBanCommand;
import dev.playban.gui.AdminMenus;
import dev.playban.gui.MenuListener;
import dev.playban.gui.PlayerMenus;
import dev.playban.listener.ConnectionListener;
import dev.playban.listener.RestrictionListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class PlayBanPlugin extends JavaPlugin {
    private Lang lang;
    private TaskRegistry tasks;
    private CategoryRegistry categories;
    private DataStore store;
    private PunishmentManager punishments;
    private ChatInput input;
    private EconomyHook economy;
    private TaskActions taskActions;
    private AdminMenus adminMenus;
    private PlayerMenus playerMenus;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        lang = new Lang(this);
        lang.load(getConfig().getString("language", "en"));
        tasks = new TaskRegistry(this);
        tasks.load();
        categories = new CategoryRegistry(this);
        categories.load();
        store = new DataStore(this);
        store.load();
        economy = new EconomyHook();
        input = new ChatInput(this);
        punishments = new PunishmentManager(this);
        taskActions = new TaskActions(this);
        adminMenus = new AdminMenus(this);
        playerMenus = new PlayerMenus(this);

        getServer().getPluginManager().registerEvents(input, this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(new RestrictionListener(this), this);
        getServer().getPluginManager().registerEvents(new ConnectionListener(this), this);

        PlayBanCommand executor = new PlayBanCommand(this);
        for (String name : new String[]{"playban", "ticket"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(executor);
                cmd.setTabCompleter(executor);
            }
        }
        punishments.start();
    }

    @Override
    public void onDisable() {
        if (punishments != null) punishments.shutdown();
        if (store != null) store.saveNow();
    }

    public void reload() {
        reloadConfig();
        lang.load(getConfig().getString("language", "en"));
        tasks.load();
        categories.load();
        punishments.refreshVisibility();
    }

    public Lang lang() { return lang; }
    public TaskRegistry tasks() { return tasks; }
    public CategoryRegistry categories() { return categories; }
    public DataStore store() { return store; }
    public PunishmentManager punishments() { return punishments; }
    public ChatInput input() { return input; }
    public EconomyHook economy() { return economy; }
    public TaskActions taskActions() { return taskActions; }
    public AdminMenus adminMenus() { return adminMenus; }
    public PlayerMenus playerMenus() { return playerMenus; }
}
