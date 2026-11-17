package dev.playban;

import dev.playban.command.PlayBanCommand;
import dev.playban.gui.AdminMenus;
import dev.playban.gui.MenuListener;
import dev.playban.gui.PlayerMenus;
import dev.playban.gui.TicketMenus;
import dev.playban.gui.VoiceMenus;
import dev.playban.command.VoiceCommand;
import dev.playban.command.AppealCommand;
import dev.playban.voice.SimpleVoiceChatBridge;
import dev.playban.voice.VoiceManager;
import dev.playban.command.TicketCommand;
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
    private TicketManager tickets;
    private SpawnManager spawn;
    private TicketMenus ticketMenus;
    private VoiceManager voice;
    private VoiceMenus voiceMenus;

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
        ticketMenus = new TicketMenus(this);
        voice = new VoiceManager(this);
        voiceMenus = new VoiceMenus(this);
        SimpleVoiceChatBridge bridge = new SimpleVoiceChatBridge(this, voice);
        if (bridge.register()) voice.use(bridge);
        tickets = new TicketManager(this);
        tickets.load();

        getServer().getPluginManager().registerEvents(input, this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(new RestrictionListener(this), this);
        getServer().getPluginManager().registerEvents(new ConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new dev.playban.listener.StaffListener(this), this);
        spawn = new SpawnManager(this);
        getServer().getPluginManager().registerEvents(spawn, this);
        if (getConfig().getBoolean("spawn.enabled", true)) {
            register("spawn", "Teleport to spawn", "playban.spawn", spawn);
            register("setspawn", "Set the spawn where you stand", "playban.admin", spawn);
        }

        PlayBanCommand executor = new PlayBanCommand(this);
        PluginCommand main = getCommand("playban");
        if (main != null) {
            main.setExecutor(executor);
            main.setTabCompleter(executor);
        }
        register("guide", "Every command with what it does", null, executor);
        // switched off, these are not registered at all, so another plugin can have the name
        if (getConfig().getBoolean("short-commands", true)) {
            register("punish", "Punish a player by category or with a duration", "playban.staff", executor);
            register("ban", "Ban a player", "playban.staff", executor);
            register("tempban", "Ban a player for a time", "playban.staff", executor);
            register("warn", "Warn a player", "playban.staff", executor);
            register("unban", "Lift a ban, by name or ban ID", "playban.staff", executor);
            register("vanish", "Turn invisible to players", "playban.vanish", executor);
        }
        VoiceCommand voiceExecutor = new VoiceCommand(this);
        PluginCommand voiceCommand = getCommand("voice");
        if (voiceCommand != null) {
            voiceCommand.setExecutor(voiceExecutor);
            voiceCommand.setTabCompleter(voiceExecutor);
        }
        AppealCommand appealExecutor = new AppealCommand(this);
        PluginCommand appeal = getCommand("appeal");
        if (appeal != null) {
            appeal.setExecutor(appealExecutor);
            appeal.setTabCompleter(appealExecutor);
        }
        TicketCommand ticketExecutor = new TicketCommand(this);
        PluginCommand ticket = getCommand("ticket");
        if (ticket != null) {
            ticket.setExecutor(ticketExecutor);
            ticket.setTabCompleter(ticketExecutor);
        }
        punishments.start();
    }

    private final java.util.List<dev.playban.command.DelegateCommand> registered = new java.util.ArrayList<>();

    private void register(String name, String description, String permission, org.bukkit.command.TabExecutor executor) {
        dev.playban.command.DelegateCommand cmd = new dev.playban.command.DelegateCommand(this, name, description, permission, executor);
        getServer().getCommandMap().register("playban", cmd);
        registered.add(cmd);
    }

    @Override
    public void onDisable() {
        // take the runtime commands away again, so /reload does not leave copies behind
        var known = getServer().getCommandMap().getKnownCommands();
        for (var cmd : registered) {
            cmd.unregister(getServer().getCommandMap());
            known.values().removeIf(c -> c == cmd);
        }
        registered.clear();
        if (tickets != null) tickets.returnEveryone();
        if (punishments != null) punishments.shutdown();
        if (store != null) store.saveNow();
        if (tickets != null) tickets.saveNow();
    }

    public void reload() {
        reloadConfig();
        for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) {
            if (!punishments.isPunished(p.getUniqueId())) continue;
            p.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
            punishments.applyState(p);
        }
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
    public TicketManager tickets() { return tickets; }
    public SpawnManager spawn() { return spawn; }
    public TicketMenus ticketMenus() { return ticketMenus; }
    public VoiceManager voice() { return voice; }
    public VoiceMenus voiceMenus() { return voiceMenus; }
}
