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
    private ModerationManager moderation;
    private PrisonManager prison;
    private TrollManager trolls;
    private FakeBaseManager fakeBases;
    private dev.staffcore.module.Teleports teleports;
    private dev.staffcore.module.HomeManager homes;
    private dev.staffcore.module.TpaManager tpa;
    private dev.staffcore.module.MoneyManager money;
    private dev.staffcore.module.EspManager esp;
    private dev.staffcore.module.ModuleManager modules;
    private dev.staffcore.module.AntiCheat antiCheat;
    private dev.staffcore.module.GhostMode ghost;
    private dev.staffcore.module.AdminPanel adminPanel;
    private dev.staffcore.module.PlayerCheck playerCheck;
    private dev.staffcore.module.Announcer announcer;
    private dev.staffcore.module.ModGuard modGuard;
    private dev.staffcore.module.Investigations investigations;
    private dev.staffcore.module.PlayerStatus playerStatus;
    private dev.staffcore.log.BlockLog blockLog;
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
        for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) ranks.apply(online);
        admin = new AdminManager(this);
        getServer().getPluginManager().registerEvents(admin, this);
        admin.start();
        blockLog = new dev.staffcore.log.BlockLog(this);
        getServer().getPluginManager().registerEvents(blockLog, this);
        blockLog.start();
        moderation = new ModerationManager(this);
        getServer().getPluginManager().registerEvents(moderation, this);
        register("report", "Report a player to the team", "staffcore.ticket", moderation);
        register("whois", "Everything about a player at a glance", "staffcore.staff", moderation);
        register("seen", "When a player was last online", "staffcore.staff", moderation);
        register("alts", "Accounts that joined from the same address", "staffcore.staff", moderation);
        register("stafflist", "Which team members are online", "staffcore.staff", moderation);
        register("near", "Players near you", "staffcore.staff", moderation);
        register("mutechat", "Only the team may write in chat", "staffcore.chatcontrol", moderation);
        register("slowchat", "Players may only write every few seconds", "staffcore.chatcontrol", moderation);
        register("lockdown", "Only the team may join", "staffcore.lockdown", moderation);
        register("stafflog", "Team protocol: who did what", "staffcore.stafflog", moderation);
        prison = new PrisonManager(this);
        prison.load();
        prison.loadPending();
        getServer().getPluginManager().registerEvents(prison, this);
        prison.start();
        trolls = new TrollManager(this);
        getServer().getPluginManager().registerEvents(trolls, this);
        fakeBases = new FakeBaseManager(this);
        getServer().getPluginManager().registerEvents(fakeBases, this);
        fakeBases.start();
        tools = new ToolManager(this);
        getServer().getPluginManager().registerEvents(tools, this);
        register("tools", "Admin items: bannhammer, freeze wand, handcuffs and more", "staffcore.tools", tools);
        register("staffmode", "Swap your inventory for a full set of admin items", "staffcore.staffmode", tools);
        spawn = new SpawnManager(this);
        getServer().getPluginManager().registerEvents(spawn, this);

        StaffCoreCommand executor = new StaffCoreCommand(this);
        PluginCommand main = getCommand("staffcore");
        if (main != null) {
            main.setExecutor(executor);
            main.setTabCompleter(executor);
        }
        register("guide", "Every command with what it does", null, executor);
        teleports = new dev.staffcore.module.Teleports(this);
        homes = new dev.staffcore.module.HomeManager(this);
        homes.load();
        tpa = new dev.staffcore.module.TpaManager(this);
        money = new dev.staffcore.module.MoneyManager(this);
        money.load();
        getServer().getScheduler().runTaskTimer(this, money::saveIfDirty, 600L, 600L);
        esp = new dev.staffcore.module.EspManager(this);
        getServer().getPluginManager().registerEvents(esp, this);
        esp.start();
        antiCheat = new dev.staffcore.module.AntiCheat(this);
        getServer().getPluginManager().registerEvents(antiCheat, this);
        ghost = new dev.staffcore.module.GhostMode(this);
        getServer().getPluginManager().registerEvents(ghost, this);
        ghost.start();
        adminPanel = new dev.staffcore.module.AdminPanel(this);
        getServer().getPluginManager().registerEvents(adminPanel, this);
        register("admin", "Your admin panel: inbox, admin mode, ghost mode, your commands", "staffcore.panel", adminPanel);
        playerCheck = new dev.staffcore.module.PlayerCheck(this);
        getServer().getPluginManager().registerEvents(playerCheck, this);
        register("check", "Everything about a player on one live page", "staffcore.check", playerCheck);
        playerStatus = new dev.staffcore.module.PlayerStatus(this);
        register("status", "What lies on a player right now, with buttons to lift it", "staffcore.staff", playerStatus);
        register("release", "Lift everything from a player: freeze, prison, PlayBan, mute, ban", "staffcore.staff", playerStatus);
        announcer = new dev.staffcore.module.Announcer(this);
        modGuard = new dev.staffcore.module.ModGuard(this);
        modGuard.load();
        getServer().getPluginManager().registerEvents(modGuard, this);
        modGuard.registerProbeListener();
        investigations = new dev.staffcore.module.Investigations(this);
        getServer().getPluginManager().registerEvents(investigations, this);
        modules = new dev.staffcore.module.ModuleManager(this);
        defineModules(executor);
        modules.startAll();
        economy.fallback(() -> modules.on("money") ? money : null);
        antiCheat.start();
        announcer.start();
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

    /** Registers a command at runtime; modules switch them on and off this way. */
    public dev.staffcore.command.DelegateCommand registerCommand(String name, String description, String permission,
                                                                 org.bukkit.command.TabExecutor executor, java.util.List<String> aliases) {
        dev.staffcore.command.DelegateCommand cmd = new dev.staffcore.command.DelegateCommand(this, name, description, permission, executor, aliases);
        getServer().getCommandMap().register("staffcore", cmd);
        registered.add(cmd);
        return cmd;
    }

    /** Takes a runtime command away again, so the name is free for another plugin. */
    public void unregisterCommand(dev.staffcore.command.DelegateCommand cmd) {
        cmd.unregister(getServer().getCommandMap());
        getServer().getCommandMap().getKnownCommands().values().removeIf(c -> c == cmd);
        registered.remove(cmd);
    }

    /** Tells the server and every player that the command list changed. */
    public void syncCommands() {
        try {
            getServer().getClass().getMethod("syncCommands").invoke(getServer());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not every server has it; the players' lists below are enough there
        }
        for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) online.updateCommands();
    }

    private void defineModules(StaffCoreCommand executor) {
        dev.staffcore.module.ModuleManager.Kind CYCLE = dev.staffcore.module.ModuleManager.Kind.CYCLE;
        dev.staffcore.module.ModuleManager.Kind NUMBER = dev.staffcore.module.ModuleManager.Kind.NUMBER;
        dev.staffcore.module.ModuleManager.Kind TEXT = dev.staffcore.module.ModuleManager.Kind.TEXT;
        dev.staffcore.module.ModuleManager.Kind BOOL = dev.staffcore.module.ModuleManager.Kind.BOOL;
        modules.add("homes", org.bukkit.Material.RED_BED, "homes.enabled", true)
                .command(new dev.staffcore.module.ModuleManager.Cmd("home", "Teleport to one of your homes", "staffcore.home", homes))
                .command(new dev.staffcore.module.ModuleManager.Cmd("sethome", "Save a home where you stand", "staffcore.home", homes))
                .command(new dev.staffcore.module.ModuleManager.Cmd("delhome", "Delete a home", "staffcore.home", homes))
                .command(new dev.staffcore.module.ModuleManager.Cmd("homes", "Your homes as a menu", "staffcore.home", homes))
                .setting("homes.max", CYCLE, org.bukkit.Material.RED_BED, 3, 1, 2, 3, 5, 10, 20)
                .setting("homes.warmup", CYCLE, org.bukkit.Material.CLOCK, 3, 0, 3, 5, 10)
                .setting("homes.cooldown", CYCLE, org.bukkit.Material.HOPPER, 0, 0, 10, 30, 60, 300);
        modules.add("tpa", org.bukkit.Material.ENDER_PEARL, "tpa.enabled", true)
                .command(new dev.staffcore.module.ModuleManager.Cmd("tpa", "Ask to teleport to a player", "staffcore.tpa", tpa))
                .command(new dev.staffcore.module.ModuleManager.Cmd("tpahere", "Ask a player to teleport to you", "staffcore.tpa", tpa))
                .command(new dev.staffcore.module.ModuleManager.Cmd("tpaccept", "Accept a teleport request", "staffcore.tpa", tpa))
                .command(new dev.staffcore.module.ModuleManager.Cmd("tpdeny", "Deny a teleport request", "staffcore.tpa", tpa))
                .command(new dev.staffcore.module.ModuleManager.Cmd("tpacancel", "Take back your teleport request", "staffcore.tpa", tpa))
                .setting("tpa.warmup", CYCLE, org.bukkit.Material.CLOCK, 3, 0, 3, 5, 10)
                .setting("tpa.timeout", CYCLE, org.bukkit.Material.COMPASS, 60, 30, 60, 120, 300)
                .setting("tpa.cooldown", CYCLE, org.bukkit.Material.HOPPER, 10, 0, 10, 30, 60);
        modules.add("money", org.bukkit.Material.GOLD_INGOT, "money.enabled", true)
                .command(new dev.staffcore.module.ModuleManager.Cmd("money", "Your money, or another player's", "staffcore.money", money,
                        java.util.List.of("balance", "bal")))
                .command(new dev.staffcore.module.ModuleManager.Cmd("pay", "Give money to a player", "staffcore.pay", money))
                .command(new dev.staffcore.module.ModuleManager.Cmd("baltop", "The richest players", "staffcore.money", money))
                .command(new dev.staffcore.module.ModuleManager.Cmd("eco", "Give, take or set money", "staffcore.eco", money))
                .setting("money.start", NUMBER, org.bukkit.Material.GOLD_NUGGET, 100)
                .setting("money.symbol", TEXT, org.bukkit.Material.NAME_TAG, "$")
                .setting("money.symbol-after", BOOL, org.bukkit.Material.COMPARATOR, false)
                .onEnable(money::hookVault).onDisable(money::unhookVault);
        modules.add("spawn", org.bukkit.Material.RESPAWN_ANCHOR, "spawn.enabled", true)
                .command(new dev.staffcore.module.ModuleManager.Cmd("spawn", "Teleport to spawn", "staffcore.spawn", spawn))
                .command(new dev.staffcore.module.ModuleManager.Cmd("setspawn", "Set the spawn where you stand", "staffcore.admin", spawn))
                .settingsMenu(p -> adminMenus.spawnSettings(p));
        modules.add("ranks", org.bukkit.Material.GOLDEN_HELMET, ranks::enabled, ranks::setEnabled)
                .command(new dev.staffcore.module.ModuleManager.Cmd("rank", "Give and manage ranks", "staffcore.ranks", new dev.staffcore.command.RankCommand(this)))
                .settingsMenu(p -> rankMenus.list(p));
        modules.add("anticheat", org.bukkit.Material.IRON_SWORD, "anticheat.enabled", true)
                .command(new dev.staffcore.module.ModuleManager.Cmd("anticheat", "Players the anti-cheat marked", "staffcore.anticheat", antiCheat,
                        java.util.List.of("ac")))
                .command(new dev.staffcore.module.ModuleManager.Cmd("alerts", "Anti-cheat alerts on or off for you", "staffcore.anticheat", antiCheat))
                .setting("anticheat.auto-punish", BOOL, org.bukkit.Material.ANVIL, true)
                .setting("anticheat.limit", CYCLE, org.bukkit.Material.TARGET, 25, 15, 20, 25, 35, 50)
                .setting("anticheat.animation", CYCLE, org.bukkit.Material.MACE, "random", "random", "blackhole", "mace", "lightning", "none")
                .setting("anticheat.auto-watch", BOOL, org.bukkit.Material.ENDER_EYE, true)
                .setting("anticheat.watch-multiplier", CYCLE, org.bukkit.Material.SPYGLASS, 1.5, 1.0, 1.25, 1.5, 2.0)
                .setting("anticheat.grace-seconds", CYCLE, org.bukkit.Material.CLOCK, 6, 3, 6, 10)
                .setting("anticheat.speed-limit", CYCLE, org.bukkit.Material.SUGAR, 9.5, 8.5, 9.5, 11.0, 13.0)
                .setting("anticheat.max-ping", CYCLE, org.bukkit.Material.CLOCK, 300, 200, 300, 500)
                .setting("anticheat.max-cps", CYCLE, org.bukkit.Material.STONE_BUTTON, 22, 18, 22, 26);
        modules.add("adminmode", org.bukkit.Material.NETHERITE_CHESTPLATE, "admin-mode.required", true);
        modules.add("modguard", org.bukkit.Material.NAME_TAG, "mod-guard.enabled", true)
                .command(new dev.staffcore.module.ModuleManager.Cmd("mods", "Which client and mods a player has", "staffcore.check", modGuard))
                .setting("mod-guard.mode", CYCLE, org.bukkit.Material.COMPARATOR, "BLOCKLIST", "BLOCKLIST", "ALLOWLIST")
                .setting("mod-guard.warnings", CYCLE, org.bukkit.Material.PAPER, 1, 0, 1, 2, 3)
                .setting("mod-guard.punishment", CYCLE, org.bukkit.Material.ANVIL, "CATEGORY cheating", "CATEGORY cheating", "PLAYBAN 1h", "PLAYBAN 1d", "BAN 7d", "BAN perm")
                .setting("mod-guard.probe", BOOL, org.bukkit.Material.OAK_SIGN, true);
        modules.add("esp", org.bukkit.Material.SPYGLASS, "esp.enabled", true)
                .command(new dev.staffcore.module.ModuleManager.Cmd("esp", "See chests and players through walls, only you", "staffcore.esp", esp))
                .setting("esp.player-range", CYCLE, org.bukkit.Material.PLAYER_HEAD, 128, 32, 64, 128, 256)
                .setting("esp.chest-chunks", CYCLE, org.bukkit.Material.CHEST, 3, 2, 3, 4, 6)
                .setting("esp.chest-limit", CYCLE, org.bukkit.Material.HOPPER, 400, 100, 200, 400, 800)
                .onDisable(esp::stopViews);
        modules.add("blocklog", org.bukkit.Material.BRUSH, "block-log.enabled", true)
                .command(new dev.staffcore.module.ModuleManager.Cmd("blocklog", "Who changed which block or chest: look up, roll back, replay",
                        "staffcore.blocklog", blockLog, java.util.List.of("log", "bl")))
                .setting("block-log.keep-days", CYCLE, org.bukkit.Material.CLOCK, 30, 7, 14, 30, 60, 90)
                .setting("block-log.max-radius", CYCLE, org.bukkit.Material.COMPASS, 100, 50, 100, 200);
        dev.staffcore.command.AdminCommand adminExecutor = new dev.staffcore.command.AdminCommand(this);
        var adminModule = modules.add("admin", org.bukkit.Material.COMMAND_BLOCK, "admin-commands", true);
        String[][] adminCmds = {{"tpadmin", "Teleport to a player, invisible", "staffcore.tpadmin"}, {"tphere", "Bring a player to you", "staffcore.tpadmin"},
                {"tpback", "Go back to where you were before /tpadmin", "staffcore.tpadmin"}, {"freeze", "Freeze or unfreeze a player", "staffcore.freeze"},
                {"unfreeze", "Unfreeze a player", "staffcore.freeze"}, {"invsee", "Look into a player's inventory", "staffcore.invsee"},
                {"endersee", "Look into a player's ender chest", "staffcore.invsee"}, {"teamchat", "Chat with the team only", "staffcore.teamchat"},
                {"clearchat", "Clear the chat for players", "staffcore.clearchat"}, {"fly", "Turn flying on or off", "staffcore.fly"},
                {"heal", "Heal and feed", "staffcore.heal"}, {"god", "Take no damage", "staffcore.god"}};
        for (String[] c : adminCmds) adminModule.command(new dev.staffcore.module.ModuleManager.Cmd(c[0], c[1], c[2], adminExecutor));
        var shortModule = modules.add("short", org.bukkit.Material.NAME_TAG, "short-commands", true);
        String[][] shortCmds = {{"punish", "Punish a player by category or with a duration", "staffcore.staff"}, {"ban", "Ban a player", "staffcore.staff"},
                {"tempban", "Ban a player for a time", "staffcore.staff"}, {"warn", "Warn a player", "staffcore.staff"},
                {"unban", "Lift a ban, by name or ban ID", "staffcore.staff"}, {"mute", "Mute a player in chat", "staffcore.staff"},
                {"unmute", "Lift a mute", "staffcore.staff"}, {"ipban", "Ban a player and their address", "staffcore.ipban"},
                {"vanish", "Turn invisible to players", "staffcore.vanish"}};
        for (String[] c : shortCmds) shortModule.command(new dev.staffcore.module.ModuleManager.Cmd(c[0], c[1], c[2], executor));
        modules.add("prison", org.bukkit.Material.BEDROCK, "modules.prison", true)
                .command(new dev.staffcore.module.ModuleManager.Cmd("prison", "Wall a player into a bedrock cell, their inventory locked", "staffcore.prison", prison))
                .command(new dev.staffcore.module.ModuleManager.Cmd("unprison", "Let a player out of the prison", "staffcore.prison", prison))
                .setting("prison.tool-time", CYCLE, org.bukkit.Material.TRIPWIRE_HOOK, "15m", "5m", "15m", "30m", "1h", "1d");
        modules.add("fakebase", org.bukkit.Material.CHEST, "modules.fakebase", true)
                .command(new dev.staffcore.module.ModuleManager.Cmd("fakebase", "Bait base underground: catches ESP and x-ray", "staffcore.fakebase", fakeBases))
                .setting("fakebase.minutes", CYCLE, org.bukkit.Material.CLOCK, 30, 10, 30, 60, 120)
                .setting("fakebase.max", CYCLE, org.bukkit.Material.CHEST, 5, 1, 3, 5, 10);
        dev.staffcore.module.LagTools lag = new dev.staffcore.module.LagTools(this);
        modules.add("lag", org.bukkit.Material.REDSTONE, "modules.lag", true)
                .command(new dev.staffcore.module.ModuleManager.Cmd("lag", "TPS, tick time and the busiest chunks", "staffcore.lag", lag))
                .command(new dev.staffcore.module.ModuleManager.Cmd("cleanup", "Remove items, arrows and experience lying around", "staffcore.lag", lag));
        modules.add("announcer", org.bukkit.Material.BELL, "announcer.enabled", true)
                .command(new dev.staffcore.module.ModuleManager.Cmd("announce", "A message with a title to everybody", "staffcore.announce", announcer))
                .setting("announcer.interval-seconds", CYCLE, org.bukkit.Material.CLOCK, 300, 60, 180, 300, 600, 1200)
                .setting("announcer.random", BOOL, org.bukkit.Material.COMPARATOR, false)
                .setting("announcer.sound", BOOL, org.bukkit.Material.NOTE_BLOCK, true);
        modules.add("banhammer", org.bukkit.Material.MACE, "modules.banhammer", true)
                .setting("banhammer.action", CYCLE, org.bukkit.Material.MACE, "KICK", "KICK", "PLAYBAN 1h", "PLAYBAN 1d", "BAN 1d", "BAN 7d", "BAN perm", "CATEGORY cheating");
        modules.add("trolls", org.bukkit.Material.EGG, "modules.trolls", true)
                .command(new dev.staffcore.module.ModuleManager.Cmd("troll", "Harmless jokes on a player", "staffcore.troll", trolls));
    }

    private void register(String name, String description, String permission, org.bukkit.command.TabExecutor executor) {
        registerCommand(name, description, permission, executor, java.util.List.of());
    }

    @Override
    public void onDisable() {
        if (admin != null) admin.stop();
        if (prison != null) prison.stop();
        if (fakeBases != null) fakeBases.stop();
        if (esp != null) esp.stop();
        if (ghost != null) ghost.stop();
        if (announcer != null) announcer.stop();
        if (investigations != null) investigations.stopAll();
        if (antiCheat != null) antiCheat.stop();
        if (money != null) {
            money.saveIfDirty();
            money.unhookVault();
        }
        if (ranks != null) for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) ranks.unapply(online);
        if (tools != null) tools.leaveAll();
        if (blockLog != null) blockLog.stop();
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
    public ModerationManager moderation() { return moderation; }

    public PrisonManager prison() { return prison; }

    public TrollManager trolls() { return trolls; }

    public FakeBaseManager fakeBases() { return fakeBases; }

    public dev.staffcore.module.Teleports teleports() { return teleports; }

    public dev.staffcore.module.HomeManager homes() { return homes; }

    public dev.staffcore.module.MoneyManager money() { return money; }

    public dev.staffcore.module.EspManager esp() { return esp; }

    public dev.staffcore.module.ModuleManager modules() { return modules; }

    public dev.staffcore.module.AntiCheat antiCheat() { return antiCheat; }

    public dev.staffcore.module.GhostMode ghost() { return ghost; }

    public dev.staffcore.module.AdminPanel adminPanel() { return adminPanel; }

    public dev.staffcore.module.PlayerCheck playerCheck() { return playerCheck; }

    public dev.staffcore.module.ModGuard modGuard() { return modGuard; }

    public dev.staffcore.module.PlayerStatus playerStatus() { return playerStatus; }

    public dev.staffcore.module.Investigations investigations() { return investigations; }

    /** One line in the team protocol (/stafflog). */
    public void staffLog(String actor, String action, String target, String detail) {
        if (blockLog != null && blockLog.db() != null) blockLog.db().staffAction(actor, action, target, detail);
    }
    public dev.staffcore.log.BlockLog blockLog() { return blockLog; }
    public dev.staffcore.gui.RankMenus rankMenus() { return rankMenus; }
    public TicketMenus ticketMenus() { return ticketMenus; }
    public VoiceManager voice() { return voice; }
    public VoiceMenus voiceMenus() { return voiceMenus; }
}
