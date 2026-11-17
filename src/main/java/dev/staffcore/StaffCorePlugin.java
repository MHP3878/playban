package dev.staffcore;

import dev.staffcore.command.StaffCoreCommand;
import dev.staffcore.gui.AdminMenus;
import dev.staffcore.gui.MenuListener;
import dev.staffcore.gui.PlayerMenus;
import dev.staffcore.gui.TicketMenus;
import dev.staffcore.gui.VoiceMenus;
import dev.staffcore.command.VoiceCommand;
import dev.staffcore.command.AppealCommand;
import dev.staffcore.voice.SimpleVoiceChatBridge;
import dev.staffcore.voice.VoiceManager;
import dev.staffcore.command.TicketCommand;
import dev.staffcore.listener.ConnectionListener;
import dev.staffcore.listener.RestrictionListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class StaffCorePlugin extends JavaPlugin {
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
    private RankManager ranks;
    private AdminManager admin;
    private ToolManager tools;
    private dev.staffcore.gui.RankMenus rankMenus;
    private TicketMenus ticketMenus;
    private VoiceManager voice;
    private VoiceMenus voiceMenus;

    @Override
    public void onEnable() {
        moveOldDataFolder();
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
        getServer().getPluginManager().registerEvents(new dev.staffcore.listener.StaffListener(this), this);
        ranks = new RankManager(this);
        ranks.load();
        rankMenus = new dev.staffcore.gui.RankMenus(this);
        getServer().getPluginManager().registerEvents(ranks, this);
        if (ranks.enabled()) {
            dev.staffcore.command.RankCommand rankExecutor = new dev.staffcore.command.RankCommand(this);
            register("rank", "Give and manage ranks", "staffcore.ranks", rankExecutor);
        }
        for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) ranks.apply(online);
        admin = new AdminManager(this);
        getServer().getPluginManager().registerEvents(admin, this);
        admin.start();
        if (getConfig().getBoolean("admin-commands", true)) {
            dev.staffcore.command.AdminCommand adminExecutor = new dev.staffcore.command.AdminCommand(this);
            register("tpadmin", "Teleport to a player, invisible", "staffcore.tpadmin", adminExecutor);
            register("tphere", "Bring a player to you", "staffcore.tpadmin", adminExecutor);
            register("tpback", "Go back to where you were before /tpadmin", "staffcore.tpadmin", adminExecutor);
            register("freeze", "Freeze or unfreeze a player", "staffcore.freeze", adminExecutor);
            register("unfreeze", "Unfreeze a player", "staffcore.freeze", adminExecutor);
            register("invsee", "Look into a player's inventory", "staffcore.invsee", adminExecutor);
            register("endersee", "Look into a player's ender chest", "staffcore.invsee", adminExecutor);
            register("teamchat", "Chat with the team only", "staffcore.teamchat", adminExecutor);
            register("clearchat", "Clear the chat for players", "staffcore.clearchat", adminExecutor);
            register("fly", "Turn flying on or off", "staffcore.fly", adminExecutor);
            register("heal", "Heal and feed", "staffcore.heal", adminExecutor);
            register("god", "Take no damage", "staffcore.god", adminExecutor);
        }
        tools = new ToolManager(this);
        getServer().getPluginManager().registerEvents(tools, this);
        register("tools", "Admin items: bannhammer, freeze wand, handcuffs and more", "staffcore.tools", tools);
        spawn = new SpawnManager(this);
        getServer().getPluginManager().registerEvents(spawn, this);
        if (getConfig().getBoolean("spawn.enabled", true)) {
            register("spawn", "Teleport to spawn", "staffcore.spawn", spawn);
            register("setspawn", "Set the spawn where you stand", "staffcore.admin", spawn);
        }

        StaffCoreCommand executor = new StaffCoreCommand(this);
        PluginCommand main = getCommand("staffcore");
        if (main != null) {
            main.setExecutor(executor);
            main.setTabCompleter(executor);
        }
        register("guide", "Every command with what it does", null, executor);
        // switched off, these are not registered at all, so another plugin can have the name
        if (getConfig().getBoolean("short-commands", true)) {
            register("punish", "Punish a player by category or with a duration", "staffcore.staff", executor);
            register("ban", "Ban a player", "staffcore.staff", executor);
            register("tempban", "Ban a player for a time", "staffcore.staff", executor);
            register("warn", "Warn a player", "staffcore.staff", executor);
            register("unban", "Lift a ban, by name or ban ID", "staffcore.staff", executor);
            register("vanish", "Turn invisible to players", "staffcore.vanish", executor);
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

    /** Before the rename the plugin was called PlayBan: take its settings and data over once. */
    private void moveOldDataFolder() {
        java.io.File old = new java.io.File(getDataFolder().getParentFile(), "PlayBan");
        if (!old.isDirectory() || getDataFolder().exists()) return;
        try {
            java.nio.file.Files.move(old.toPath(), getDataFolder().toPath());
            getLogger().info("Took over the settings and data of PlayBan from plugins/PlayBan.");
        } catch (java.io.IOException e) {
            getLogger().warning("Could not take over plugins/PlayBan: " + e.getMessage() + ". Move the folder to plugins/StaffCore by hand.");
        }
    }

    private final java.util.List<dev.staffcore.command.DelegateCommand> registered = new java.util.ArrayList<>();

    private void register(String name, String description, String permission, org.bukkit.command.TabExecutor executor) {
        dev.staffcore.command.DelegateCommand cmd = new dev.staffcore.command.DelegateCommand(this, name, description, permission, executor);
        getServer().getCommandMap().register("staffcore", cmd);
        registered.add(cmd);
    }

    @Override
    public void onDisable() {
        if (admin != null) admin.stop();
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
        ranks.load();
        for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) ranks.apply(online);
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
    public RankManager ranks() { return ranks; }
    public AdminManager admin() { return admin; }
    public ToolManager tools() { return tools; }
    public dev.staffcore.gui.RankMenus rankMenus() { return rankMenus; }
    public TicketMenus ticketMenus() { return ticketMenus; }
    public VoiceManager voice() { return voice; }
    public VoiceMenus voiceMenus() { return voiceMenus; }
}
