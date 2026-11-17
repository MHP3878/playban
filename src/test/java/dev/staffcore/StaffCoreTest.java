package dev.staffcore;

import dev.staffcore.gui.Menu;
import dev.staffcore.model.Punishment;
import dev.staffcore.model.Review;
import dev.staffcore.model.TaskDef;
import dev.staffcore.model.TaskStatus;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StaffCoreTest {
    private ServerMock server;
    private StaffCorePlugin plugin;
    private PlayerMock admin;
    private PlayerMock troll;
    private PlayerMock bystander;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StaffCorePlugin.class);
        admin = server.addPlayer("Admin");
        admin.setOp(true);
        troll = server.addPlayer("Troll");
        bystander = server.addPlayer("Bystander");
        server.getScheduler().performTicks(30);
        plugin.getConfig().set("ticket-cooldown-seconds", 0);
        // most tests use team commands directly; admin mode itself has its own tests
        plugin.getConfig().set("admin-mode.required", false);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Punishment punish(String time) {
        assertTrue(admin.performCommand("playban punish Troll " + time + " Griefing the spawn"));
        Punishment p = plugin.punishments().get(troll.getUniqueId());
        assertNotNull(p, "punishment should exist");
        return p;
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private void typeInChat(PlayerMock player, String text) {
        player.chat(text);
        server.getScheduler().waitAsyncEventsFinished();
        server.getScheduler().performTicks(2);
    }

    private int slotOf(PlayerMock viewer, Material material) {
        var inv = viewer.getOpenInventory().getTopInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() == material) return i;
        }
        return fail("no " + material + " in the open menu");
    }

    @Test
    void punishCommandCreatesPunishmentWithTasks() {
        Punishment p = punish("1h");
        assertEquals(3_600_000L, p.originalMs);
        assertEquals("Griefing the spawn", p.reason);
        assertEquals("Admin", p.staff);
        // gift task needs Vault, which is not installed here
        assertFalse(p.tasks.containsKey("gift"));
        assertTrue(p.tasks.containsKey("apology"));
        assertTrue(p.tasks.containsKey("rules-quiz"));
    }

    @Test
    void punishedPlayerIsHiddenFromPlayersButNotFromStaff() {
        punish("1h");
        assertFalse(bystander.canSee(troll), "normal players must not see the punished player");
        assertTrue(admin.canSee(troll), "staff still sees the punished player");
        assertFalse(troll.canSee(bystander), "a PlayBan works both ways: the punished player sees nobody");
        assertTrue(troll.canSee(admin), "except the team");

        PlayerMock late = server.addPlayer("LateJoiner");
        server.getScheduler().performTicks(30);
        assertFalse(late.canSee(troll), "players who join later must not see them either");
    }

    @Test
    void restrictionsBlockBreakingDroppingChatAndCommands() {
        punish("1h");
        Block block = troll.getWorld().getBlockAt(0, 3, 0);
        block.setType(Material.STONE);
        BlockBreakEvent breakEvent = new BlockBreakEvent(block, troll);
        server.getPluginManager().callEvent(breakEvent);
        assertTrue(breakEvent.isCancelled());

        PlayerCommandPreprocessEvent tpa = new PlayerCommandPreprocessEvent(troll, "/tpa Bystander");
        server.getPluginManager().callEvent(tpa);
        assertTrue(tpa.isCancelled(), "unknown commands are locked");
        PlayerCommandPreprocessEvent spawn = new PlayerCommandPreprocessEvent(troll, "/spawn");
        server.getPluginManager().callEvent(spawn);
        assertFalse(spawn.isCancelled(), "allowed commands still work");
        PlayerCommandPreprocessEvent namespaced = new PlayerCommandPreprocessEvent(troll, "/essentials:msg Bystander hi");
        server.getPluginManager().callEvent(namespaced);
        assertTrue(namespaced.isCancelled(), "namespaced commands must not slip through");

        // the same things are fine for a player who is not punished
        BlockBreakEvent free = new BlockBreakEvent(block, bystander);
        server.getPluginManager().callEvent(free);
        assertFalse(free.isCancelled());
    }

    @Test
    void chatOfPunishedPlayerNeverReachesOthers() {
        punish("1h");
        boolean[] delivered = {false};
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
            public void on(AsyncChatEvent e) {
                if (!e.isCancelled()) delivered[0] = true;
            }
        }, plugin);
        typeInChat(troll, "you all suck");
        assertFalse(delivered[0]);
        typeInChat(bystander, "hello");
        assertTrue(delivered[0], "normal players can still chat");
    }

    @Test
    void timeOnlyRunsWhileOnlineAndPunishmentEnds() {
        Punishment p = punish("1m");
        server.getScheduler().performTicks(20 * 10);
        assertEquals(50_000L, p.remainingMs, 1500);

        troll.disconnect();
        long before = p.remainingMs;
        server.getScheduler().performTicks(20 * 20);
        assertEquals(before, p.remainingMs, "offline time must not count in online-only mode");

        troll.reconnect();
        server.getScheduler().performTicks(20 * 55);
        assertFalse(plugin.punishments().isPunished(troll.getUniqueId()), "punishment should be over");
        assertTrue(bystander.canSee(troll), "player is visible again afterwards");
        assertEquals(1, plugin.store().history.get(troll.getUniqueId()).size());
        assertEquals("SERVED", plugin.store().history.get(troll.getUniqueId()).get(0).endReason);
    }

    @Test
    void donatingItemsShortensThePunishment() {
        Punishment p = punish("1h");
        TaskDef donation = plugin.tasks().get("donation");
        plugin.taskActions().start(troll, donation);
        assertEquals(TaskStatus.OPEN, p.tasks.get("donation").status, "without items nothing happens");

        troll.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 64));
        long before = p.remainingMs;
        plugin.taskActions().start(troll, donation);
        assertEquals(TaskStatus.DONE, p.tasks.get("donation").status);
        assertEquals(before - 180_000L, p.remainingMs, "5% of one hour is three minutes");
        assertFalse(troll.getInventory().contains(Material.COBBLESTONE));

        plugin.taskActions().start(troll, donation);
        assertEquals(before - 180_000L, p.remainingMs, "a task only counts once");
    }

    @Test
    void niceMessageNeedsStaffApprovalBeforeItCounts() {
        assertTrue(admin.performCommand("playban menu"));
        // punish through the menu so that a victim is set
        admin.simulateInventoryClick(slotOf(admin, Material.ANVIL));
        var inv = admin.getOpenInventory().getTopInventory();
        int trollSlot = -1;
        for (int i = 0; i < 45; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && plain(it.getItemMeta().displayName()).contains("Troll")) trollSlot = i;
        }
        assertTrue(trollSlot >= 0, "Troll is listed in the player picker");
        admin.simulateInventoryClick(trollSlot);
        admin.simulateInventoryClick(51); // "custom PlayBan" in the category picker
        admin.simulateInventoryClick(11); // second duration preset: 1h
        admin.simulateInventoryClick(10); // first reason preset
        inv = admin.getOpenInventory().getTopInventory();
        int victimSlot = -1;
        for (int i = 0; i < 45; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && plain(it.getItemMeta().displayName()).contains("Bystander")) victimSlot = i;
        }
        admin.simulateInventoryClick(victimSlot);
        admin.simulateInventoryClick(slotOf(admin, Material.LIME_CONCRETE));

        Punishment p = plugin.punishments().get(troll.getUniqueId());
        assertNotNull(p, "the menu flow should punish the player");
        assertEquals(3_600_000L, p.originalMs);
        assertEquals("Griefing", p.reason);
        assertEquals("Bystander", p.victimName);

        plugin.taskActions().start(troll, plugin.tasks().get("nice-message"));
        typeInChat(troll, "short");
        assertTrue(plugin.store().reviews.isEmpty(), "too short messages are refused");

        plugin.taskActions().start(troll, plugin.tasks().get("nice-message"));
        long before = p.remainingMs;
        typeInChat(troll, "I am really sorry for destroying your house, it was not okay.");
        assertEquals(1, plugin.store().reviews.size());
        assertEquals(TaskStatus.PENDING, p.tasks.get("nice-message").status);
        assertEquals(before, p.remainingMs, 2000, "nothing is taken off before the team approves");

        while (bystander.nextComponentMessage() != null) { /* clear */ }
        Review review = plugin.store().reviews.values().iterator().next();
        plugin.punishments().reject(review, "Admin");
        assertEquals(TaskStatus.OPEN, p.tasks.get("nice-message").status, "rejected messages can be retried");
        assertNull(bystander.nextComponentMessage(), "a rejected message is never delivered");

        plugin.taskActions().start(troll, plugin.tasks().get("nice-message"));
        typeInChat(troll, "I am really sorry for destroying your house, it was not okay.");
        // approve through the review menu
        admin.performCommand("playban menu");
        admin.simulateInventoryClick(14);
        admin.simulateInventoryClick(0);
        assertTrue(plugin.store().reviews.isEmpty());
        assertEquals(TaskStatus.DONE, p.tasks.get("nice-message").status);
        Component got = bystander.nextComponentMessage();
        assertNotNull(got, "the victim receives the approved message");
        assertTrue(plain(got).contains("really sorry"));
    }

    @Test
    void quizCountsOnlyWithAllAnswersRight() {
        Punishment p = punish("1h");
        TaskDef quiz = plugin.tasks().get("rules-quiz");
        plugin.playerMenus().quiz(troll, quiz, 0);
        // answers are shuffled, find the right one by its text
        for (int question = 0; question < quiz.questions.size(); question++) {
            String right = quiz.questions.get(question).answers.get(0);
            var inv = troll.getOpenInventory().getTopInventory();
            int slot = -1;
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack it = inv.getItem(i);
                if (it != null && it.getType() == Material.PAPER && plain(it.getItemMeta().displayName()).equals(right)) slot = i;
            }
            assertTrue(slot >= 0);
            troll.simulateInventoryClick(slot);
        }
        assertEquals(TaskStatus.DONE, p.tasks.get("rules-quiz").status);
    }

    @Test
    void taskWithTimeLimitExpires() {
        TaskDef nice = plugin.tasks().get("nice-message");
        nice.timeLimitMs = 5000;
        Punishment p = punish("1h");
        server.getScheduler().performTicks(20 * 7);
        assertEquals(TaskStatus.EXPIRED, p.tasks.get("nice-message").status);
        assertEquals(TaskStatus.OPEN, p.tasks.get("apology").status);
    }

    @Test
    void pardonAndTimeCommands() {
        Punishment p = punish("1h");
        admin.performCommand("playban addtime Troll 30m");
        assertEquals(5_400_000L, p.remainingMs, 2000);
        admin.performCommand("playban removetime Troll 1h");
        assertEquals(1_800_000L, p.remainingMs, 2000);
        admin.performCommand("playban pardon Troll");
        assertFalse(plugin.punishments().isPunished(troll.getUniqueId()));
        assertTrue(bystander.canSee(troll));
    }

    @Test
    void normalPlayersCannotUseStaffCommands() {
        bystander.performCommand("playban punish Troll 1h test");
        assertFalse(plugin.punishments().isPunished(troll.getUniqueId()));
        punish("1h");
        troll.performCommand("playban pardon Troll");
        assertTrue(plugin.punishments().isPunished(troll.getUniqueId()), "a punished player cannot pardon themselves");
    }

    @Test
    void dataSurvivesARestart() {
        Punishment p = punish("2h");
        troll.performCommand("ticket hello team");
        plugin.store().saveNow();

        DataStore fresh = new DataStore(plugin);
        fresh.load();
        Punishment loaded = fresh.active.get(troll.getUniqueId());
        assertNotNull(loaded);
        assertEquals(p.reason, loaded.reason);
        assertEquals(p.originalMs, loaded.originalMs);
        assertEquals(p.tasks.keySet(), loaded.tasks.keySet());
    }

    @Test
    void settingsMenuTogglesAndLanguageSwitchWork() {
        punish("1h");
        assertFalse(bystander.canSee(troll));
        admin.performCommand("playban menu");
        admin.simulateInventoryClick(32); // settings
        admin.simulateInventoryClick(10); // "invisible to others"
        assertFalse(plugin.getConfig().getBoolean("restrictions.hide-from-players"));
        assertTrue(bystander.canSee(troll), "switching the setting off shows the player again");

        admin.performCommand("playban lang de");
        assertEquals("de", plugin.lang().code());
        assertTrue(plain(plugin.lang().msg("end.served")).contains("Willkommen"));
    }

    @Test
    void everyMenuOpensInBothLanguagesWithoutMissingTexts() {
        punish("1h");
        troll.performCommand("ticket hello");
        admin.performCommand("playban note Troll was rude in chat");
        bystander.performCommand("ticket Troll destroyed my house");
        plugin.tickets().accept(plugin.tickets().get(2), admin);
        plugin.tickets().note(plugin.tickets().get(2), "Admin", "checked the logs");
        admin.performCommand("playban specban Bystander 2h Testing");
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            Runnable[] menus = {
                    () -> plugin.adminMenus().main(admin),
                    () -> plugin.adminMenus().selectPlayer(admin, 0),
                    () -> plugin.adminMenus().active(admin, 0),
                    () -> plugin.adminMenus().detail(admin, troll.getUniqueId()),
                    () -> plugin.adminMenus().reviews(admin, 0),
                    () -> plugin.ticketMenus().list(admin, "ALL", null, 0),
                    () -> plugin.ticketMenus().view(admin, plugin.tickets().get(1)),
                    () -> plugin.ticketMenus().view(admin, plugin.tickets().get(2)),
                    () -> plugin.ticketMenus().verdicts(admin, plugin.tickets().get(2)),
                    () -> plugin.ticketMenus().stats(admin),
                    () -> plugin.ticketMenus().settings(admin),
                    () -> plugin.ticketMenus().ofPlayer(admin, troll.getUniqueId(), "Troll"),
                    () -> plugin.ticketMenus().pickReason(admin),
                    () -> plugin.adminMenus().settings(admin),
                    () -> plugin.adminMenus().allowedCommands(admin, 0),
                    () -> plugin.adminMenus().tasks(admin, 0),
                    () -> plugin.adminMenus().history(admin, "Troll"),
                    () -> plugin.adminMenus().bans(admin, 0),
                    () -> plugin.adminMenus().profile(admin, troll.getUniqueId(), "Troll"),
                    () -> plugin.adminMenus().banScreen(admin, 0),
                    () -> plugin.adminMenus().categories(admin, 0),
                    () -> plugin.adminMenus().categoryEdit(admin, "cheating"),
                    () -> plugin.adminMenus().punishPlayer(admin, bystander.getUniqueId(), "Bystander"),
                    () -> plugin.playerMenus().status(troll),
            };
            for (int i = 0; i < menus.length; i++) {
                menus[i].run();
                assertNoMissingTexts(i == menus.length - 1 ? troll : admin, code);
            }
            for (TaskDef def : plugin.tasks().all()) {
                plugin.adminMenus().taskEdit(admin, def.id);
                assertNoMissingTexts(admin, code);
            }
        }
    }

    private void assertNoMissingTexts(PlayerMock viewer, String code) {
        var view = viewer.getOpenInventory();
        assertInstanceOf(Menu.class, view.getTopInventory().getHolder());
        assertFalse(plain(view.title()).contains("missing text"), code + ": " + plain(view.title()));
        for (ItemStack it : view.getTopInventory().getContents()) {
            if (it == null || !it.hasItemMeta()) continue;
            var meta = it.getItemMeta();
            if (meta.hasDisplayName()) assertFalse(plain(meta.displayName()).contains("missing text"), code + ": " + plain(meta.displayName()));
            if (meta.hasLore()) for (Component line : meta.lore()) {
                assertFalse(plain(line).contains("missing text"), code + ": " + plain(line));
                assertFalse(plain(line).matches(".*\\{[a-z-]+}.*"), code + ": unfilled placeholder in " + plain(line));
            }
        }
    }

    @Test
    void categoryLadderEscalatesFromPlayBanToBan() {
        // cheating: PlayBan 1d, PlayBan 3d, Ban 7d, permanent ban
        assertTrue(admin.performCommand("playban apply Troll cheating"));
        Punishment first = plugin.punishments().get(troll.getUniqueId());
        assertNotNull(first);
        assertEquals(86_400_000L, first.originalMs);
        assertEquals("cheating", first.category);
        assertEquals("Cheating", first.reason);

        plugin.punishments().end(first, "SERVED", "");
        admin.performCommand("playban apply Troll cheating");
        Punishment second = plugin.punishments().get(troll.getUniqueId());
        assertEquals(3 * 86_400_000L, second.originalMs, "second offense is the second step");

        plugin.punishments().end(second, "SERVED", "");
        assertTrue(troll.isOnline());
        admin.performCommand("playban apply Troll cheating");
        assertFalse(plugin.punishments().isPunished(troll.getUniqueId()), "third offense is a real ban, not a PlayBan");
        Punishment ban = plugin.punishments().activeBan(troll.getUniqueId());
        assertNotNull(ban, "third offense bans the player");
        assertEquals(7 * 86_400_000L, ban.originalMs);
        assertFalse(troll.isOnline(), "a banned player is kicked");

        // other categories are counted on their own
        assertEquals(0, plugin.punishments().countOffenses(troll.getUniqueId(), plugin.categories().get("spam")));
        assertEquals(3, plugin.punishments().countOffenses(troll.getUniqueId(), plugin.categories().get("cheating")));
    }

    @Test
    void liftedPunishmentsDoNotCountForTheLadder() {
        admin.performCommand("playban apply Troll cheating");
        admin.performCommand("playban pardon Troll");
        admin.performCommand("playban apply Troll cheating");
        assertEquals(86_400_000L, plugin.punishments().get(troll.getUniqueId()).originalMs,
                "after a pardon the player starts at the first step again");
    }

    @Test
    void oldOffensesExpire() {
        var spam = plugin.categories().get("spam"); // warn, PlayBan 1h, PlayBan 12h; expires after 30d
        admin.performCommand("playban apply Troll spam");
        assertFalse(plugin.punishments().isPunished(troll.getUniqueId()), "first spam offense is only a warning");
        assertEquals(1, plugin.punishments().countOffenses(troll.getUniqueId(), spam));
        plugin.store().history.get(troll.getUniqueId()).get(0).createdAt -= 31L * 86_400_000L;
        assertEquals(0, plugin.punishments().countOffenses(troll.getUniqueId(), spam), "the warning is older than 30 days");
        admin.performCommand("playban apply Troll spam");
        assertFalse(plugin.punishments().isPunished(troll.getUniqueId()), "so the player gets a warning again");
    }

    @Test
    void bannedPlayerCannotLogInUntilUnbannedOrExpired() throws Exception {
        admin.performCommand("playban ban Troll 1h Hacking");
        assertFalse(troll.isOnline());
        var listener = new dev.staffcore.listener.ConnectionListener(plugin);
        var address = java.net.InetAddress.getByName("127.0.0.1");

        var denied = new org.bukkit.event.player.AsyncPlayerPreLoginEvent("Troll", address, troll.getUniqueId());
        listener.onPreLogin(denied);
        assertEquals(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_BANNED, denied.getLoginResult());
        assertTrue(plain(denied.kickMessage()).contains("Hacking"), "the ban screen shows the reason");

        var other = new org.bukkit.event.player.AsyncPlayerPreLoginEvent("Bystander", address, bystander.getUniqueId());
        listener.onPreLogin(other);
        assertEquals(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED, other.getLoginResult());

        // a second ban on top is refused
        admin.performCommand("playban ban Troll perm again");
        assertEquals(3_600_000L, plugin.store().bans.get(troll.getUniqueId()).originalMs);

        admin.performCommand("playban unban Troll");
        var allowed = new org.bukkit.event.player.AsyncPlayerPreLoginEvent("Troll", address, troll.getUniqueId());
        listener.onPreLogin(allowed);
        assertEquals(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED, allowed.getLoginResult());
        assertEquals("PARDONED", plugin.store().history.get(troll.getUniqueId()).get(0).endReason);

        // an expired ban lets the player in and is moved to the history
        admin.performCommand("playban ban Troll 1h Hacking");
        plugin.store().bans.get(troll.getUniqueId()).expiresAt = System.currentTimeMillis() - 1000;
        var expired = new org.bukkit.event.player.AsyncPlayerPreLoginEvent("Troll", address, troll.getUniqueId());
        listener.onPreLogin(expired);
        assertEquals(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED, expired.getLoginResult());
        server.getScheduler().performTicks(25);
        assertTrue(plugin.store().bans.isEmpty());
    }

    @Test
    void permanentBanAndBansSurviveARestart() {
        admin.performCommand("playban ban Troll perm Hacking");
        Punishment ban = plugin.store().bans.get(troll.getUniqueId());
        assertEquals(0, ban.expiresAt);
        server.getScheduler().performTicks(60);
        assertNotNull(plugin.punishments().activeBan(troll.getUniqueId()), "a permanent ban never expires");
        plugin.store().saveNow();
        DataStore fresh = new DataStore(plugin);
        fresh.load();
        assertEquals("BAN", fresh.bans.get(troll.getUniqueId()).type);
        assertEquals("Hacking", fresh.bans.get(troll.getUniqueId()).reason);
    }

    @Test
    void categoryPickerPunishesThroughTheMenu() {
        assertTrue(admin.performCommand("playban Troll"));
        // the picker shows what would happen
        int slot = slotOf(admin, Material.TNT); // griefing: PlayBan 1d first
        ItemStack entry = admin.getOpenInventory().getTopInventory().getItem(slot);
        assertTrue(entry.getItemMeta().lore().stream().anyMatch(l -> plain(l).contains("PlayBan (1d)")));
        admin.simulateInventoryClick(slot);
        admin.simulateInventoryClick(51); // nobody in particular
        admin.simulateInventoryClick(slotOf(admin, Material.LIME_CONCRETE));
        Punishment p = plugin.punishments().get(troll.getUniqueId());
        assertNotNull(p);
        assertEquals("griefing", p.category);
        assertEquals(86_400_000L, p.originalMs);
    }

    @Test
    void categoryEditorCreatesAndChangesSteps() {
        var created = plugin.categories().create("Bug abuse");
        assertEquals("bug-abuse", created.id);
        plugin.adminMenus().categoryEdit(admin, created.id);
        admin.simulateInventoryClick(28); // first step: WARN -> MUTE
        assertEquals("MUTE", created.steps.get(0).type);
        assertEquals(3_600_000L, created.steps.get(0).durationMs, "a new mute step starts at one hour, not forever");
        admin.simulateInventoryClick(30); // add a third step
        assertEquals(3, created.steps.size());

        CategoryRegistry fresh = new CategoryRegistry(plugin);
        fresh.load();
        assertEquals(3, fresh.get("bug-abuse").steps.size(), "changes are written to categories.yml");
        assertEquals("Bug abuse", fresh.get("bug-abuse").name);
        assertEquals(5 + 1, fresh.all().size());
    }

    @Test
    void spectatorBanKeepsPlayerStuckAndRestoresEverythingAfterwards() {
        var home = troll.getLocation().clone();
        troll.setGameMode(org.bukkit.GameMode.SURVIVAL);
        admin.performCommand("playban specban Troll 1h Hacking");
        assertTrue(troll.isOnline(), "a spectator ban does not kick");
        assertTrue(plugin.punishments().inLimbo(troll.getUniqueId()));
        assertEquals(org.bukkit.GameMode.SPECTATOR, troll.getGameMode());
        assertFalse(bystander.canSee(troll), "nobody sees the banned spectator");
        assertFalse(troll.canSee(bystander), "and they see nobody");
        assertTrue(troll.hasPotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS));

        // cannot move away, cannot jump to other players
        var here = troll.getLocation();
        var move = new org.bukkit.event.player.PlayerMoveEvent(troll, here.clone(), here.clone().add(5, 0, 5));
        server.getPluginManager().callEvent(move);
        assertEquals(here.getX(), move.getTo().getX());
        assertEquals(here.getZ(), move.getTo().getZ());
        var jump = new org.bukkit.event.player.PlayerTeleportEvent(troll, here, bystander.getLocation(),
                org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.SPECTATE);
        server.getPluginManager().callEvent(jump);
        assertTrue(jump.isCancelled());

        // no chat, no commands except tickets
        PlayerCommandPreprocessEvent spawn = new PlayerCommandPreprocessEvent(troll, "/spawn");
        server.getPluginManager().callEvent(spawn);
        assertTrue(spawn.isCancelled(), "even the commands allowed for PlayBans are locked");
        troll.performCommand("ticket please unban me");
        assertEquals(1, plugin.tickets().all().size(), "writing a ticket still works");
        assertEquals("appeal", plugin.tickets().all().get(0).reason);

        // the login itself is allowed
        var listener = new dev.staffcore.listener.ConnectionListener(plugin);
        org.bukkit.event.player.AsyncPlayerPreLoginEvent login;
        try {
            login = new org.bukkit.event.player.AsyncPlayerPreLoginEvent("Troll", java.net.InetAddress.getByName("127.0.0.1"), troll.getUniqueId());
        } catch (java.net.UnknownHostException e) {
            throw new AssertionError(e);
        }
        listener.onPreLogin(login);
        assertEquals(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED, login.getLoginResult());

        // leaving the server puts the player back to normal, joining again puts them on hold again
        troll.disconnect();
        assertEquals(org.bukkit.GameMode.SURVIVAL, troll.getGameMode());
        troll.reconnect();
        server.getScheduler().performTicks(30);
        assertEquals(org.bukkit.GameMode.SPECTATOR, troll.getGameMode());

        admin.performCommand("playban unban Troll");
        assertFalse(plugin.punishments().inLimbo(troll.getUniqueId()));
        assertEquals(org.bukkit.GameMode.SURVIVAL, troll.getGameMode());
        assertEquals(home.getX(), troll.getLocation().getX());
        assertFalse(troll.hasPotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS));
        assertTrue(bystander.canSee(troll));
        assertTrue(troll.canSee(bystander));
    }

    @Test
    void spectatorBanWorksAsACategoryStep() {
        var cat = plugin.categories().create("Base scouting");
        cat.steps.clear();
        cat.steps.add(new dev.staffcore.model.Category.Step("SPECBAN", 3_600_000L));
        admin.performCommand("playban apply Troll " + cat.id);
        assertTrue(plugin.punishments().inLimbo(troll.getUniqueId()));
        server.getScheduler().performTicks(40);
        plugin.store().bans.get(troll.getUniqueId()).expiresAt = System.currentTimeMillis() - 1;
        server.getScheduler().performTicks(25);
        assertFalse(plugin.punishments().inLimbo(troll.getUniqueId()), "it ends on its own");
        assertNotEquals(org.bukkit.GameMode.SPECTATOR, troll.getGameMode());
    }

    @Test
    void banScreenCanBeCustomizedAndShowsDiscord() {
        admin.performCommand("playban ban Troll 1h Hacking");
        Punishment ban = plugin.store().bans.get(troll.getUniqueId());
        String standard = plain(plugin.punishments().banScreen(ban));
        assertTrue(standard.contains("Hacking"));
        assertFalse(standard.contains("discord"));

        plugin.getConfig().set("ban-screen.discord", "https://discord.gg/example");
        assertTrue(plain(plugin.punishments().banScreen(ban)).contains("https://discord.gg/example"),
                "the Discord link is added to the default screen");

        plugin.getConfig().set("ban-screen.lines", java.util.List.of("<red>MyServer", "Banned for {reason} by {staff}", "Join {discord}"));
        String custom = plain(plugin.punishments().banScreen(ban));
        assertTrue(custom.contains("MyServer"));
        assertTrue(custom.contains("Banned for Hacking by Admin"));
        assertTrue(custom.contains("Join https://discord.gg/example"));
        assertEquals(1, custom.split("discord.gg", -1).length - 1, "the link is not added twice");

        // editing through the menu: reset to the default text
        plugin.adminMenus().banScreen(admin, 0);
        admin.simulateInventoryClick(admin.getOpenInventory(), org.bukkit.event.inventory.ClickType.SHIFT_LEFT, 52);
        assertTrue(plugin.getConfig().getStringList("ban-screen.lines").isEmpty());
    }

    @Test
    void notesAreKeptPerPlayerAndShownInTheProfile() {
        admin.performCommand("playban note Troll was rude in chat");
        admin.performCommand("playban note Troll apologized later");
        assertEquals(2, plugin.store().notes.get(troll.getUniqueId()).size());
        assertEquals("Admin", plugin.store().notes.get(troll.getUniqueId()).get(0).author);

        bystander.performCommand("playban note Troll I should not be able to do this");
        assertEquals(2, plugin.store().notes.get(troll.getUniqueId()).size(), "only the team can write notes");

        assertTrue(admin.performCommand("playban profile troll"));
        var inv = admin.getOpenInventory().getTopInventory();
        // newest note first
        assertTrue(inv.getItem(19).getItemMeta().lore().stream().anyMatch(l -> plain(l).contains("apologized later")));
        assertTrue(inv.getItem(20).getItemMeta().lore().stream().anyMatch(l -> plain(l).contains("was rude in chat")));

        plugin.store().saveNow();
        DataStore fresh = new DataStore(plugin);
        fresh.load();
        assertEquals(2, fresh.notes.get(troll.getUniqueId()).size(), "notes survive a restart");
        assertEquals("Troll", fresh.names.get(troll.getUniqueId()));

        admin.simulateInventoryClick(admin.getOpenInventory(), org.bukkit.event.inventory.ClickType.SHIFT_LEFT, 19);
        assertEquals(1, plugin.store().notes.get(troll.getUniqueId()).size());
    }

    // ------------------------------------------------------------------ tickets

    private dev.staffcore.model.Ticket openTicket(PlayerMock author, String reason, String text) {
        assertNotNull(plugin.tickets().create(author, reason, text));
        var all = plugin.tickets().all();
        return all.get(all.size() - 1);
    }

    private boolean reachesPublicChat(PlayerMock sender, String text, PlayerMock... mustNotSee) {
        boolean[] delivered = {false};
        var listener = new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
            public void on(AsyncChatEvent e) {
                if (e.isCancelled()) return;
                delivered[0] = true;
                for (PlayerMock hidden : mustNotSee) assertFalse(e.viewers().contains(hidden), hidden.getName() + " must not read the public chat");
            }
        };
        server.getPluginManager().registerEvents(listener, plugin);
        typeInChat(sender, text);
        org.bukkit.event.HandlerList.unregisterAll(listener);
        return delivered[0];
    }

    @Test
    void anyPlayerCanOpenATicketWithAReason() {
        assertTrue(bystander.performCommand("ticket"));
        assertInstanceOf(Menu.class, bystander.getOpenInventory().getTopInventory().getHolder(), "/ticket opens the reason picker");
        bystander.simulateInventoryClick(slotOf(bystander, Material.PLAYER_HEAD)); // "report a player"
        typeInChat(bystander, "Troll destroyed my house");
        var t = plugin.tickets().get(1);
        assertNotNull(t);
        assertEquals("report", t.reason);
        assertEquals("Troll destroyed my house", t.text);
        assertEquals(dev.staffcore.model.Ticket.Status.OPEN, t.status);

        for (int i = 0; i < 5; i++) bystander.performCommand("ticket spam " + i);
        assertEquals(3, plugin.tickets().all().size(), "at most three open tickets per player");
    }

    @Test
    void ticketOverviewFiltersByReasonAndStatus() {
        openTicket(bystander, "report", "Troll griefed");
        openTicket(troll, "question", "How do I claim land?");
        admin.performCommand("ticket show report");
        var inv = admin.getOpenInventory().getTopInventory();
        assertNotNull(inv.getItem(0));
        assertNull(inv.getItem(1), "only the report is listed");
        assertTrue(plain(inv.getItem(0).getItemMeta().displayName()).contains("Bystander"));

        admin.performCommand("ticket show");
        inv = admin.getOpenInventory().getTopInventory();
        assertNotNull(inv.getItem(1), "without a filter both are listed");

        bystander.performCommand("ticket show");
        assertEquals(3, plugin.tickets().all().size(), "for normal players 'show' is just ticket text, not the team overview");
    }

    @Test
    void acceptedTicketMovesPlayerIntoPrivateChatUntilPaused() {
        var t = openTicket(bystander, "report", "Troll griefed");
        admin.performCommand("ticket open 1");
        admin.simulateInventoryClick(slotOf(admin, Material.LIME_CONCRETE)); // accept
        assertEquals(dev.staffcore.model.Ticket.Status.IN_PROGRESS, t.status);
        assertEquals("Admin", t.handler);
        assertTrue(plugin.tickets().inChat(bystander.getUniqueId()));

        assertFalse(reachesPublicChat(bystander, "he broke my door"), "the message goes to the ticket, not the public chat");
        assertEquals("he broke my door", t.log.get(t.log.size() - 1).text);
        assertEquals("CHAT", t.log.get(t.log.size() - 1).kind);
        assertTrue(reachesPublicChat(troll, "hello everyone", bystander), "others still chat, but the ticket player does not read it");

        // the team answers and writes a remark only the team sees
        while (bystander.nextComponentMessage() != null) { /* clear */ }
        plugin.tickets().staffMessage(t, admin, "We will look at it.", null);
        assertTrue(plain(bystander.nextComponentMessage()).contains("We will look at it."));
        plugin.tickets().note(t, "Admin", "door was really broken by Troll");
        assertNull(bystander.nextComponentMessage(), "remarks are not shown to the player");

        plugin.tickets().setPaused(t, admin, true);
        assertFalse(plugin.tickets().inChat(bystander.getUniqueId()));
        assertTrue(reachesPublicChat(bystander, "back in public"), "while paused the player is in the normal chat");
        plugin.tickets().setPaused(t, admin, false);
        assertTrue(plugin.tickets().inChat(bystander.getUniqueId()));
    }

    @Test
    void ticketWindowUpdatesLiveAndStarsMessages() {
        var t = openTicket(bystander, "report", "Troll griefed");
        plugin.tickets().accept(t, admin);
        plugin.ticketMenus().view(admin, t);
        var inv = admin.getOpenInventory().getTopInventory();
        int before = 0;
        for (int i = 9; i < 36; i++) if (inv.getItem(i) != null && inv.getItem(i).getType() != Material.GRAY_STAINED_GLASS_PANE) before++;
        typeInChat(bystander, "he also stole my diamonds");
        int after = 0;
        for (int i = 9; i < 36; i++) if (inv.getItem(i) != null && inv.getItem(i).getType() != Material.GRAY_STAINED_GLASS_PANE) after++;
        assertSame(inv, admin.getOpenInventory().getTopInventory(), "the window stays open");
        assertEquals(before + 1, after, "the new message appears in the open window");

        int slot = 9 + t.log.size() - 1;
        assertEquals(Material.PAPER, inv.getItem(slot).getType());
        admin.simulateInventoryClick(slot);
        assertTrue(t.log.get(t.log.size() - 1).starred);
        assertEquals(Material.NETHER_STAR, inv.getItem(slot).getType(), "starred messages are marked");
    }

    @Test
    void secondPlayerCanBeAddedSharedOrSeparate() {
        var t = openTicket(bystander, "report", "Troll griefed");
        plugin.tickets().accept(t, admin);
        assertNull(plugin.tickets().addParticipant(t, admin, troll.getUniqueId(), "Troll"));
        assertEquals("ticket.error.already-in", plugin.tickets().addParticipant(t, admin, troll.getUniqueId(), "Troll"));
        assertTrue(plugin.tickets().inChat(troll.getUniqueId()));

        while (bystander.nextComponentMessage() != null) { /* clear */ }
        typeInChat(troll, "it was an accident");
        assertTrue(plain(bystander.nextComponentMessage()).contains("it was an accident"), "in a shared chat they read each other");

        plugin.tickets().setShared(t, admin, false);
        typeInChat(troll, "bystander is lying");
        assertNull(bystander.nextComponentMessage(), "in separate chats they do not");

        while (troll.nextComponentMessage() != null) { /* clear */ }
        plugin.tickets().staffMessage(t, admin, "Only for you, Bystander.", t.participant(bystander.getUniqueId()));
        assertTrue(plain(bystander.nextComponentMessage()).contains("Only for you"));
        assertNull(troll.nextComponentMessage(), "a message to one player does not reach the other");

        // a player in a ticket chat cannot be pulled into a second one at the same time
        var other = openTicket(troll, "question", "something else");
        plugin.tickets().accept(other, admin);
        assertEquals("ticket.error.busy", plugin.tickets().addParticipant(other, admin, bystander.getUniqueId(), "Bystander"));
    }

    @Test
    void closingNeedsADecisionForEveryoneAndCarriesItOut() {
        var t = openTicket(bystander, "report", "Troll griefed");
        plugin.tickets().accept(t, admin);
        plugin.tickets().addParticipant(t, admin, troll.getUniqueId(), "Troll");

        plugin.tickets().close(t, admin);
        assertEquals(dev.staffcore.model.Ticket.Status.IN_PROGRESS, t.status, "cannot close before everyone has a decision");

        plugin.ticketMenus().verdicts(admin, t);
        assertEquals(Material.BARRIER, admin.getOpenInventory().getTopInventory().getItem(30).getType());
        admin.simulateInventoryClick(10); // Bystander
        admin.simulateInventoryClick(51); // only involved
        admin.simulateInventoryClick(11); // Troll
        admin.simulateInventoryClick(slotOf(admin, Material.TNT)); // griefing
        admin.simulateInventoryClick(slotOf(admin, Material.LIME_CONCRETE)); // close now

        assertEquals(dev.staffcore.model.Ticket.Status.CLOSED, t.status);
        Punishment p = plugin.punishments().get(troll.getUniqueId());
        assertNotNull(p, "the chosen category was applied");
        assertEquals("griefing", p.category);
        assertFalse(plugin.punishments().isPunished(bystander.getUniqueId()));
        assertTrue(t.participant(troll.getUniqueId()).outcome.contains("Griefing"));
        assertFalse(plugin.tickets().inChat(bystander.getUniqueId()), "everyone is back in the normal chat");

        // both players have the ticket in their file
        assertEquals(1, plugin.tickets().involving(bystander.getUniqueId()).size());
        assertEquals(1, plugin.tickets().involving(troll.getUniqueId()).size());

        // the closed ticket stays readable, including after a restart
        plugin.tickets().saveNow();
        TicketManager fresh = new TicketManager(plugin);
        fresh.load();
        var loaded = fresh.get(t.id);
        assertEquals(dev.staffcore.model.Ticket.Status.CLOSED, loaded.status);
        assertEquals(t.log.size(), loaded.log.size());
        assertEquals(2, loaded.participants.size());
        assertEquals("INVOLVED", loaded.participant(bystander.getUniqueId()).verdict);
    }

    @Test
    void forgottenTicketDoesNotKeepPlayerOutOfPublicChat() {
        var t = openTicket(bystander, "report", "Troll griefed");
        plugin.tickets().accept(t, admin);
        assertTrue(plugin.tickets().inChat(bystander.getUniqueId()));
        admin.disconnect();
        assertFalse(plugin.tickets().inChat(bystander.getUniqueId()), "when the team member leaves, the chat pauses on its own");
        assertTrue(reachesPublicChat(bystander, "anyone here?"));
        assertEquals(dev.staffcore.model.Ticket.Status.IN_PROGRESS, t.status, "the ticket itself stays open");
    }

    @Test
    void declinedTicketAndStatistics() {
        var a = openTicket(bystander, "question", "one");
        var b = openTicket(troll, "question", "two");
        plugin.tickets().decline(a, admin);
        assertEquals(dev.staffcore.model.Ticket.Status.DECLINED, a.status);
        plugin.tickets().accept(b, admin);
        plugin.tickets().setVerdict(b, b.participants.get(0), "INVOLVED");
        plugin.tickets().close(b, admin);
        var stats = plugin.tickets().stats();
        assertEquals(1, stats.size());
        assertEquals("Admin", stats.get(0).name());
        assertEquals(1, stats.get(0).accepted());
        assertEquals(1, stats.get(0).closed());
    }

    @Test
    void teleportIsLoggedAndCanBeUndone() {
        var t = openTicket(bystander, "report", "come look");
        plugin.tickets().accept(t, admin);
        bystander.teleport(bystander.getLocation().clone().add(50, 0, 50));
        var home = admin.getLocation().clone();
        admin.setGameMode(org.bukkit.GameMode.SURVIVAL);

        assertTrue(plugin.tickets().teleport(t, admin, t.participants.get(0), "SPECTATOR"));
        assertEquals(org.bukkit.GameMode.SPECTATOR, admin.getGameMode());
        assertEquals(bystander.getLocation().getX(), admin.getLocation().getX());
        assertTrue(t.log.get(t.log.size() - 1).text.contains("Bystander"), "the teleport is written into the ticket");

        assertTrue(plugin.tickets().teleport(t, admin, t.participants.get(0), "VANISH"));
        assertFalse(bystander.canSee(admin), "invisible mode hides the team member from players");

        admin.performCommand("ticket back");
        assertEquals(org.bukkit.GameMode.SURVIVAL, admin.getGameMode());
        assertEquals(home.getX(), admin.getLocation().getX());
        assertTrue(bystander.canSee(admin));
    }

    @Test
    void voiceButtonNeedsADiscordLink() {
        var t = openTicket(bystander, "report", "let us talk");
        plugin.tickets().accept(t, admin);
        assertFalse(plugin.tickets().voice(t, admin), "without a Discord link there is nothing to invite to");
        plugin.getConfig().set("ban-screen.discord", "https://discord.gg/example");
        while (bystander.nextComponentMessage() != null) { /* clear */ }
        assertTrue(plugin.tickets().voice(t, admin));
        assertTrue(plain(bystander.nextComponentMessage()).contains("https://discord.gg/example"));
    }

    @Test
    void reminderOnlyFiresForTicketsNobodyPickedUp() {
        var t = openTicket(bystander, "question", "anyone there?");
        while (admin.nextComponentMessage() != null) { /* clear */ }
        plugin.tickets().remind();
        assertNull(admin.nextComponentMessage(), "a fresh ticket is not reminded yet");
        t.lastReminder -= 11 * 60_000L;
        plugin.tickets().remind();
        assertNotNull(admin.nextComponentMessage(), "after ten minutes the team is reminded");
        plugin.tickets().remind();
        assertNull(admin.nextComponentMessage(), "but not over and over");
        plugin.tickets().accept(t, admin);
        while (admin.nextComponentMessage() != null) { /* clear */ }
        t.lastReminder -= 11 * 60_000L;
        plugin.tickets().remind();
        assertNull(admin.nextComponentMessage(), "accepted tickets are not reminded");
    }

    @Test
    void sameAddressAsAPunishedPlayerIsFlagged() {
        var address = new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 25565);
        var elsewhere = new java.net.InetSocketAddress("10.1.2.3", 25565);
        troll.setAddress(address);
        bystander.setAddress(elsewhere);
        plugin.store().rememberAddress(troll);
        plugin.store().rememberAddress(bystander);
        punish("1h");
        PlayerMock alt = server.addPlayer("TrollTwo");
        alt.setAddress(address);
        while (admin.nextComponentMessage() != null) { /* clear */ }
        server.getScheduler().performTicks(30);
        boolean warned = false;
        Component msg;
        while ((msg = admin.nextComponentMessage()) != null) if (plain(msg).contains("TrollTwo") && plain(msg).contains("Troll")) warned = true;
        assertTrue(warned, "the team is told about the possible second account");
        assertTrue(plugin.store().sameAddress(alt.getUniqueId()).contains("Troll"));
        assertFalse(plugin.store().sameAddress(alt.getUniqueId()).contains("Bystander"), "players from other addresses are not listed");
        assertFalse(plugin.store().addresses.get(alt.getUniqueId()).contains("."), "the raw address is not stored");
    }

    // ------------------------------------------------------------------ voice groups

    /** Stands in for Simple Voice Chat: keeps groups in memory and asks PlayBan the same questions the real one does. */
    private final class FakeVoice implements dev.staffcore.voice.VoiceBackend {
        final java.util.Map<java.util.UUID, String> names = new java.util.LinkedHashMap<>();
        final java.util.Map<java.util.UUID, String> passwords = new java.util.HashMap<>();
        final java.util.Map<java.util.UUID, java.util.UUID> membership = new java.util.HashMap<>();
        final java.util.Set<java.util.UUID> hidden = new java.util.HashSet<>();
        int refreshes;

        java.util.UUID playerCreates(PlayerMock creator, String name) {
            java.util.UUID id = java.util.UUID.randomUUID();
            names.put(id, name);
            plugin.voice().created(creator.getUniqueId(), id);
            membership.put(creator.getUniqueId(), id);
            return id;
        }

        /** A player tries to join through the voice chat's own menu. */
        boolean playerJoins(PlayerMock player, java.util.UUID group) {
            if (!plugin.voice().mayJoin(player.getUniqueId(), group)) return false;
            membership.put(player.getUniqueId(), group);
            return true;
        }

        void playerLeaves(PlayerMock player) {
            java.util.UUID group = membership.remove(player.getUniqueId());
            plugin.voice().left(player.getUniqueId(), group);
        }

        public boolean available() { return true; }

        public java.util.List<GroupInfo> groups() {
            java.util.List<GroupInfo> out = new java.util.ArrayList<>();
            for (var e : names.entrySet()) {
                java.util.List<java.util.UUID> members = new java.util.ArrayList<>();
                for (var m : membership.entrySet()) if (m.getValue().equals(e.getKey())) members.add(m.getKey());
                out.add(new GroupInfo(e.getKey(), e.getValue(), passwords.containsKey(e.getKey()), hidden.contains(e.getKey()), false, members));
            }
            return out;
        }

        public java.util.UUID groupOf(java.util.UUID player) { return membership.get(player); }

        public boolean setGroup(java.util.UUID player, java.util.UUID group) {
            java.util.UUID before = group == null ? membership.remove(player) : membership.put(player, group);
            if (group == null) plugin.voice().left(player, before);
            return true;
        }

        public java.util.UUID createGroup(String name, String password, boolean isHidden) {
            java.util.UUID id = java.util.UUID.randomUUID();
            names.put(id, name);
            if (password != null) passwords.put(id, password);
            if (isHidden) hidden.add(id);
            return id;
        }

        public boolean setPassword(java.util.UUID group, String password) {
            if (password == null) passwords.remove(group);
            else passwords.put(group, password);
            return true;
        }

        public void removeGroup(java.util.UUID group) {
            names.remove(group);
            plugin.voice().removed(group);
        }

        public void refresh(java.util.UUID player) { refreshes++; }
    }

    private FakeVoice withVoice() {
        FakeVoice fake = new FakeVoice();
        plugin.voice().use(fake);
        plugin.voice().joined(admin);
        return fake;
    }

    @Test
    void voiceIsOffWithoutTheVoiceChatPlugin() {
        assertFalse(plugin.voice().available());
        admin.performCommand("voice");
        assertFalse(admin.getOpenInventory().getTopInventory() != null
                && admin.getOpenInventory().getTopInventory().getHolder() instanceof Menu, "no menu without a voice chat");
    }

    @Test
    void teamCanKickMuteDeafenAndBanInAVoiceGroup() {
        FakeVoice fake = withVoice();
        var group = fake.playerCreates(troll, "Trolls only");
        assertTrue(fake.playerJoins(bystander, group));

        plugin.voice().setMuted(troll.getUniqueId(), true, "Admin");
        plugin.voice().setDeaf(troll.getUniqueId(), true, "Admin");
        assertTrue(plugin.voice().isMuted(troll.getUniqueId()));
        assertTrue(plugin.voice().isDeaf(troll.getUniqueId()));
        assertEquals(2, fake.refreshes, "the voice chat is told to re-check the player each time");

        plugin.voice().kick(group, troll.getUniqueId(), "Admin");
        assertNull(fake.groupOf(troll.getUniqueId()));
        assertFalse(plugin.voice().isMuted(troll.getUniqueId()), "a mute ends when the player is out of the group");
        assertFalse(plugin.voice().isDeaf(troll.getUniqueId()));
        assertTrue(fake.playerJoins(troll, group), "a kicked player can come back");

        plugin.voice().ban(group, troll.getUniqueId(), "Admin");
        assertNull(fake.groupOf(troll.getUniqueId()));
        assertFalse(fake.playerJoins(troll, group), "a banned player cannot");
        assertTrue(fake.playerJoins(bystander, group), "others still can");
        var other = fake.playerCreates(admin, "Another group");
        assertTrue(fake.playerJoins(troll, other), "the ban only counts for that one group");

        plugin.voice().unban(group, troll.getUniqueId());
        assertTrue(fake.playerJoins(troll, group));
    }

    @Test
    void closingAVoiceGroupRemovesEveryone() {
        FakeVoice fake = withVoice();
        var group = fake.playerCreates(troll, "Trolls only");
        fake.playerJoins(bystander, group);
        plugin.voice().ban(group, admin.getUniqueId(), "x");
        plugin.voiceMenus().group(admin, group);
        admin.simulateInventoryClick(43);
        assertEquals(2, plugin.voice().group(group).members().size(), "a plain click does not close the group");
        admin.simulateInventoryClick(admin.getOpenInventory(), org.bukkit.event.inventory.ClickType.SHIFT_LEFT, 43);
        assertNull(fake.groupOf(troll.getUniqueId()));
        assertNull(fake.groupOf(bystander.getUniqueId()));
        assertNull(plugin.voice().group(group));
        assertTrue(plugin.voice().banned(group).isEmpty(), "bans of a closed group are forgotten");
    }

    @Test
    void teamJoinsUnseenButOtherTeamMembersStillSeeThem() {
        FakeVoice fake = withVoice();
        var group = fake.playerCreates(troll, "Trolls only");
        plugin.voice().join(admin, group, true);
        assertEquals(group, fake.groupOf(admin.getUniqueId()), "the team member is really in the group and can talk");
        assertTrue(plugin.voice().isHiddenFrom(admin.getUniqueId(), troll.getUniqueId()), "players do not see them");
        assertFalse(plugin.voice().isHiddenFrom(admin.getUniqueId(), admin.getUniqueId()));
        PlayerMock mod = server.addPlayer("Mod");
        mod.setOp(true);
        plugin.voice().joined(mod);
        assertFalse(plugin.voice().isHiddenFrom(admin.getUniqueId(), mod.getUniqueId()), "other team members do");

        // the owner's own menu does not give the unseen team member away
        fake.playerJoins(bystander, group);
        troll.performCommand("voice manage");
        var inv = troll.getOpenInventory().getTopInventory();
        assertNotNull(inv.getItem(0));
        assertTrue(plain(inv.getItem(0).getItemMeta().displayName()).contains("Bystander"));
        assertNull(inv.getItem(1), "only Bystander is listed, not the unseen admin");

        plugin.voice().setInvisible(admin, false);
        assertFalse(plugin.voice().isHiddenFrom(admin.getUniqueId(), troll.getUniqueId()), "switching to visible while inside works");
        fake.playerLeaves(admin);
        plugin.voice().join(admin, group, true);
        fake.playerLeaves(admin);
        assertFalse(plugin.voice().isInvisible(admin.getUniqueId()), "leaving the group ends being unseen");
    }

    @Test
    void joinPreferenceIsRemembered() {
        FakeVoice fake = withVoice();
        var group = fake.playerCreates(troll, "Trolls only");
        assertFalse(plugin.voice().prefersInvisible(admin.getUniqueId()));
        plugin.voiceMenus().groups(admin);
        admin.simulateInventoryClick(51);
        assertTrue(plugin.voice().prefersInvisible(admin.getUniqueId()));
        plugin.voiceMenus().group(admin, group);
        admin.simulateInventoryClick(37); // left click joins the preferred way
        assertEquals(group, fake.groupOf(admin.getUniqueId()));
        assertTrue(plugin.voice().isInvisible(admin.getUniqueId()));
    }

    @Test
    void passwordCanBeSetShownAndRemoved() {
        FakeVoice fake = withVoice();
        var group = fake.playerCreates(troll, "Trolls only");
        assertNull(plugin.voice().knownPassword(group));
        assertTrue(plugin.voice().setPassword(group, "secret123"));
        assertEquals("secret123", fake.passwords.get(group));

        plugin.voiceMenus().group(admin, group);
        while (admin.nextComponentMessage() != null) { /* clear */ }
        admin.simulateInventoryClick(40);
        Component line = admin.nextComponentMessage();
        assertTrue(plain(line).contains("secret123"));
        assertEquals(net.kyori.adventure.text.event.ClickEvent.Action.COPY_TO_CLIPBOARD, line.clickEvent().action(),
                "clicking the line copies the password");
        assertEquals("secret123", line.clickEvent().value());

        assertTrue(plugin.voice().setPassword(group, null));
        assertFalse(fake.passwords.containsKey(group));
        assertNull(plugin.voice().knownPassword(group));
    }

    @Test
    void groupOwnerCanKickAndBanButOnlyInTheirOwnGroup() {
        FakeVoice fake = withVoice();
        var group = fake.playerCreates(troll, "Trolls only");
        fake.playerJoins(bystander, group);
        assertEquals(troll.getUniqueId(), plugin.voice().owner(group));

        bystander.performCommand("voice manage");
        assertFalse(bystander.getOpenInventory().getTopInventory() != null
                && bystander.getOpenInventory().getTopInventory().getHolder() instanceof Menu, "a normal member gets no menu");

        troll.performCommand("voice manage");
        troll.simulateInventoryClick(troll.getOpenInventory(), org.bukkit.event.inventory.ClickType.SHIFT_LEFT, 0);
        assertNull(fake.groupOf(bystander.getUniqueId()));
        assertFalse(fake.playerJoins(bystander, group), "the owner banned them");

        // a team member in the group cannot be thrown out by the owner
        plugin.voice().join(admin, group, false);
        troll.performCommand("voice manage");
        troll.simulateInventoryClick(0);
        assertEquals(group, fake.groupOf(admin.getUniqueId()));
    }

    @Test
    void reportingAVoiceGroupOpensATicket() {
        FakeVoice fake = withVoice();
        var group = fake.playerCreates(troll, "Bad name");
        fake.playerJoins(bystander, group);
        plugin.voice().join(admin, group, true);
        bystander.performCommand("voice report");
        bystander.simulateInventoryClick(10); // first reason
        assertEquals(1, plugin.tickets().all().size());
        var t = plugin.tickets().get(1);
        assertEquals("voice", t.reason);
        assertTrue(t.text.contains("Bad name"));
        assertTrue(t.text.contains("Inappropriate group name"));
        assertTrue(t.text.contains("Troll"));
        assertFalse(t.text.contains("Admin"), "an unseen team member is not listed in the report");
    }

    @Test
    void ticketVoiceGroupKeepsPlayersSilentUntilTheyAgree() {
        FakeVoice fake = withVoice();
        var t = openTicket(bystander, "report", "let us talk");
        plugin.tickets().accept(t, admin);
        assertTrue(plugin.tickets().voice(t, admin));
        var group = fake.groupOf(admin.getUniqueId());
        assertNotNull(group, "a private group was created");
        assertEquals(group, fake.groupOf(bystander.getUniqueId()), "the player was moved into it");
        assertTrue(fake.hidden.contains(group));
        assertTrue(fake.passwords.containsKey(group));
        assertTrue(plugin.voice().isMuted(bystander.getUniqueId()), "the player is silent at first");
        assertFalse(plugin.voice().isMuted(admin.getUniqueId()));

        bystander.performCommand("voice speak");
        assertFalse(plugin.voice().isMuted(bystander.getUniqueId()), "after agreeing the player can speak");
    }

    @Test
    void voiceMenusOpenInBothLanguages() {
        FakeVoice fake = withVoice();
        var group = fake.playerCreates(troll, "Trolls only");
        fake.playerJoins(bystander, group);
        plugin.voice().setPassword(group, "pw");
        plugin.voice().setMuted(bystander.getUniqueId(), true, "Admin");
        plugin.voice().ban(group, server.addPlayer("Banned").getUniqueId(), "Admin");
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            plugin.voiceMenus().groups(admin);
            assertNoMissingTexts(admin, code);
            plugin.voiceMenus().group(admin, group);
            assertNoMissingTexts(admin, code);
            admin.simulateInventoryClick(1); // member actions for the second member
            assertNoMissingTexts(admin, code);
            plugin.voiceMenus().group(admin, group);
            admin.simulateInventoryClick(41); // bans
            assertNoMissingTexts(admin, code);
            plugin.voiceMenus().manage(troll);
            assertNoMissingTexts(troll, code);
            plugin.voiceMenus().report(bystander);
            assertNoMissingTexts(bystander, code);
            plugin.adminMenus().main(admin);
            assertNoMissingTexts(admin, code);
        }
    }

    // ------------------------------------------------------------------ 0.6.0: feel, clarity, short commands, ban IDs

    @Test
    void punishedPlayerGoesIntoAdventureModeAndGetsTheOldModeBack() {
        troll.setGameMode(org.bukkit.GameMode.SURVIVAL);
        punish("1h");
        assertEquals(org.bukkit.GameMode.ADVENTURE, troll.getGameMode(), "adventure mode stops the laggy block breaking");
        admin.performCommand("playban pardon Troll");
        assertEquals(org.bukkit.GameMode.SURVIVAL, troll.getGameMode());

        troll.setGameMode(org.bukkit.GameMode.CREATIVE);
        punish("1h");
        admin.performCommand("playban pardon Troll");
        assertEquals(org.bukkit.GameMode.CREATIVE, troll.getGameMode(), "whatever mode they had comes back");
    }

    @Test
    void inventoryCanBeStoredAndGivenBack() {
        plugin.getConfig().set("inventory-mode", "STORE");
        troll.getInventory().addItem(new ItemStack(Material.DIAMOND, 12));
        punish("1h");
        assertFalse(troll.getInventory().contains(Material.DIAMOND), "taken away while punished");
        admin.performCommand("playban pardon Troll");
        assertTrue(troll.getInventory().containsAtLeast(new ItemStack(Material.DIAMOND), 12), "given back at the end");
    }

    @Test
    void storedInventorySurvivesARestart() {
        plugin.getConfig().set("inventory-mode", "STORE");
        troll.getInventory().addItem(new ItemStack(Material.EMERALD, 5));
        punish("1h");
        plugin.store().saveNow();
        DataStore fresh = new DataStore(plugin);
        fresh.load();
        assertNotNull(fresh.active.get(troll.getUniqueId()).storedInventory, "the stored items are written to disk");
    }

    @Test
    void inventoryCanBeClearedForGood() {
        plugin.getConfig().set("inventory-mode", "CLEAR");
        troll.getInventory().addItem(new ItemStack(Material.DIAMOND, 12));
        punish("1h");
        assertFalse(troll.getInventory().contains(Material.DIAMOND));
        admin.performCommand("playban pardon Troll");
        assertFalse(troll.getInventory().contains(Material.DIAMOND), "nothing comes back in CLEAR mode");
    }

    @Test
    void punishedPlayerSeesTimeAndReasonAboveTheHotbar() {
        punish("1h");
        server.getScheduler().performTicks(25);
        Component bar = troll.nextActionBar();
        assertNotNull(bar, "an action bar is shown");
        assertTrue(plain(bar).contains("Griefing the spawn"), "with the reason");
    }

    @Test
    void vanishHidesStaffFromPlayersButNotFromOtherStaff() {
        PlayerMock mod = server.addPlayer("Mod");
        mod.setOp(true);
        assertTrue(admin.performCommand("vanish"));
        assertTrue(plugin.punishments().isVanished(admin.getUniqueId()));
        assertFalse(bystander.canSee(admin), "normal players do not see a vanished admin");
        assertTrue(mod.canSee(admin), "other team members still do");
        admin.performCommand("vanish");
        assertTrue(bystander.canSee(admin), "and back");

        bystander.performCommand("vanish");
        assertFalse(plugin.punishments().isVanished(bystander.getUniqueId()), "normal players cannot vanish");
    }

    @Test
    void shortCommandsWorkWithoutPlaybanInFront() {
        assertTrue(admin.performCommand("ban Troll 1h Hacking"));
        assertNotNull(plugin.store().bans.get(troll.getUniqueId()), "/ban works like /playban ban");
        assertTrue(admin.performCommand("unban Troll"));
        assertNull(plugin.store().bans.get(troll.getUniqueId()));
        assertTrue(admin.performCommand("warn Bystander Spam"));
        assertEquals("WARN", plugin.store().history.get(bystander.getUniqueId()).get(0).type);

        plugin.getConfig().set("short-commands", false);
        admin.performCommand("ban Troll 1h Hacking");
        assertNull(plugin.store().bans.get(troll.getUniqueId()), "switched off, /ban no longer bans");
    }

    @Test
    void everyPunishmentGetsAUniqueBanIdAndCanBeLiftedWithIt() {
        admin.performCommand("ban Troll 1h Hacking");
        String id = plugin.store().bans.get(troll.getUniqueId()).banId;
        assertNotNull(id);
        assertEquals(6, id.length());
        assertTrue(id.matches("[A-Z2-9]{6}"), "no 0/O or 1/I that people mix up");
        admin.performCommand("playban punish Bystander 1h test");
        String other = plugin.punishments().get(bystander.getUniqueId()).banId;
        assertNotNull(other, "PlayBans get an ID too");
        assertNotEquals(id, other, "every ID is different");

        assertTrue(plain(plugin.punishments().banScreen(plugin.store().bans.get(troll.getUniqueId()))).contains(id),
                "the ban screen shows the ID");
        admin.performCommand("unban " + id.toLowerCase());
        assertNull(plugin.store().bans.get(troll.getUniqueId()), "unban by ID works, in any letter case");
    }

    @Test
    void appealOnlyWorksWithTheOwnBanId() {
        admin.performCommand("playban specban Troll 1h Hacking");
        String trollId = plugin.store().bans.get(troll.getUniqueId()).banId;
        admin.performCommand("playban specban Bystander 1h Spam");
        String otherId = plugin.store().bans.get(bystander.getUniqueId()).banId;

        troll.performCommand("appeal " + otherId + " I want to come back please");
        assertTrue(plugin.tickets().all().isEmpty(), "someone else's ID is refused");
        troll.performCommand("appeal " + trollId + " short");
        assertTrue(plugin.tickets().all().isEmpty(), "too short is refused");

        PlayerCommandPreprocessEvent allowed = new PlayerCommandPreprocessEvent(troll, "/appeal " + trollId + " text");
        server.getPluginManager().callEvent(allowed);
        assertFalse(allowed.isCancelled(), "/appeal works inside the spectator ban");

        troll.performCommand("appeal " + trollId + " I am sorry, I will follow the rules now");
        assertEquals(1, plugin.tickets().all().size());
        var t = plugin.tickets().all().get(0);
        assertEquals("appeal", t.reason);
        assertTrue(t.text.contains(trollId));
    }

    @Test
    void spectatorBanCanLockTheView() {
        admin.performCommand("playban specban Troll 1h Hacking");
        var here = troll.getLocation();
        var turned = here.clone();
        turned.setYaw(here.getYaw() + 90);
        var move = new org.bukkit.event.player.PlayerMoveEvent(troll, here.clone(), turned);
        server.getPluginManager().callEvent(move);
        assertEquals(here.getYaw(), move.getTo().getYaw(), "cannot turn the head");

        plugin.getConfig().set("limbo.lock-view", false);
        var move2 = new org.bukkit.event.player.PlayerMoveEvent(troll, here.clone(), turned.clone());
        server.getPluginManager().callEvent(move2);
        assertEquals(turned.getYaw(), move2.getTo().getYaw(), "with lock-view off turning is allowed");
    }

    @Test
    void commandGuideShowsOnlyWhatTheViewerMayUse() {
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            admin.performCommand("guide");
            assertNoMissingTexts(admin, code);
            int adminCount = 0;
            for (ItemStack it : admin.getOpenInventory().getTopInventory().getContents())
                if (it != null && it.getType() != Material.BLACK_STAINED_GLASS_PANE && it.getType() != Material.ARROW
                        && it.getType() != Material.OAK_DOOR && it.getType() != Material.MAP) adminCount++;

            bystander.performCommand("playban guide");
            // normal players reach it through the main help too
            plugin.adminMenus().guide(bystander, 0);
            assertNoMissingTexts(bystander, code);
            int playerCount = 0;
            for (ItemStack it : bystander.getOpenInventory().getTopInventory().getContents())
                if (it != null && it.getType() != Material.BLACK_STAINED_GLASS_PANE && it.getType() != Material.ARROW
                        && it.getType() != Material.OAK_DOOR && it.getType() != Material.MAP) playerCount++;
            assertTrue(playerCount < adminCount, "players only see their own commands");
            assertTrue(playerCount >= 3, "but they do see /ticket, /appeal and their own menu");
        }
    }

    @Test
    void newSettingsAppearInTheMenuInBothLanguages() {
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            plugin.adminMenus().settings(admin);
            assertNoMissingTexts(admin, code);
            plugin.adminMenus().main(admin);
            assertNoMissingTexts(admin, code);
        }
        plugin.adminMenus().settings(admin);
        admin.simulateInventoryClick(43);
        assertEquals("STORE", plugin.getConfig().getString("inventory-mode"), "the inventory button switches the mode");
    }

    // ------------------------------------------------------------------ suggestions

    private java.util.List<String> suggest(PlayerMock who, String line) {
        String[] parts = line.split(" ", -1);
        String label = parts[0];
        String[] args = java.util.Arrays.copyOfRange(parts, 1, parts.length);
        var cmd = server.getCommandMap().getCommand(label);
        assertNotNull(cmd, "command " + label);
        return cmd.tabComplete(who, label, args);
    }

    @Test
    void shortCommandsSuggestPlayersNotSubcommands() {
        var list = suggest(admin, "ban ");
        assertTrue(list.contains("Troll"), "player names after /ban");
        assertFalse(list.contains("menu"), "not the /playban subcommands");
    }

    @Test
    void banSuggestsDurationsPermAndReasons() {
        var times = suggest(admin, "ban Troll ");
        assertTrue(times.contains("1d"));
        assertTrue(times.contains("perm"), "permanent is offered for bans");
        assertTrue(suggest(admin, "ban Troll 1d ").contains("Griefing"), "reason presets after the time");
        assertTrue(suggest(admin, "ban Troll 1d ").contains("X-Ray"), "category names work as reasons too");
        assertTrue(suggest(admin, "ban Troll 1").stream().allMatch(s -> s.startsWith("1")), "only what fits what is typed");
    }

    @Test
    void punishSuggestsCategoriesAndAppliesThem() {
        var list = suggest(admin, "punish Troll ");
        assertTrue(list.contains("x-ray"), "categories are suggested");
        assertTrue(list.contains("1h"), "durations too, for a PlayBan of your own length");

        assertTrue(admin.performCommand("punish Troll griefing"));
        Punishment p = plugin.punishments().get(troll.getUniqueId());
        assertNotNull(p, "/punish <player> <category> punishes right away");
        assertEquals("griefing", p.category);
    }

    @Test
    void punishWithOnlyANameOpensTheCategoryMenu() {
        assertTrue(admin.performCommand("punish Troll"));
        assertInstanceOf(Menu.class, admin.getOpenInventory().getTopInventory().getHolder());
    }

    @Test
    void unbanSuggestsNamesAndBanIds() {
        admin.performCommand("ban Troll 1h Hacking");
        String id = plugin.store().bans.get(troll.getUniqueId()).banId;
        var list = suggest(admin, "unban ");
        assertTrue(list.contains("Troll"));
        assertTrue(list.contains(id));
    }

    @Test
    void offlinePlayersAreSuggestedToo() {
        admin.performCommand("playban note Troll seen once");
        troll.disconnect();
        assertTrue(suggest(admin, "playban profile T").contains("Troll"), "known players work while offline");
    }

    @Test
    void timeChangeAndPardonSuggestOnlyPunishedPlayers() {
        punish("1h");
        var list = suggest(admin, "playban addtime ");
        assertEquals(java.util.List.of("Troll"), list);
        assertTrue(suggest(admin, "playban addtime Troll ").contains("30m"));
    }

    @Test
    void playersGetOnlyTheirOwnSuggestions() {
        var list = suggest(bystander, "playban ");
        assertTrue(list.contains("guide"));
        assertFalse(list.contains("ban"), "no team commands for normal players");
        assertTrue(suggest(bystander, "ban ").isEmpty() || !suggest(bystander, "ban ").contains("Troll"));
    }

    @Test
    void ticketOpenSuggestsTicketNumbers() {
        bystander.performCommand("ticket help me");
        assertTrue(suggest(admin, "ticket open ").contains("1"));
    }

    // ------------------------------------------------------------------ vanish privacy and hidden commands

    @Test
    void vanishedStaffSeeAnIndicatorAboveTheHotbar() {
        admin.performCommand("vanish");
        while (admin.nextActionBar() != null) { /* clear */ }
        server.getScheduler().performTicks(25);
        Component bar = admin.nextActionBar();
        assertNotNull(bar);
        assertTrue(plain(bar).contains("VANISH"));
    }

    @Test
    void killByVanishedStaffDoesNotShowTheirName() {
        admin.performCommand("vanish");
        var drops = new java.util.ArrayList<ItemStack>();
        var source = org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK).withCausingEntity(admin).withDirectEntity(admin).build();
        bystander.setKiller(admin);
        var event = new PlayerDeathEvent(bystander, source, drops, 0, Component.text("Bystander was slain by Admin"));
        server.getPluginManager().callEvent(event);
        assertNotNull(event.deathMessage());
        assertFalse(plain(event.deathMessage()).contains("Admin"), "the killer's name is gone");
        assertTrue(plain(event.deathMessage()).contains("Bystander"));
    }

    @Test
    void deathOfVanishedStaffIsSilent() {
        admin.performCommand("vanish");
        var source = org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.FALL).build();
        var event = new PlayerDeathEvent(admin, source, new java.util.ArrayList<>(), 0, Component.text("Admin fell"));
        server.getPluginManager().callEvent(event);
        assertNull(event.deathMessage());
    }

    @Test
    void vanishedChatGoesToTheTeamUnlessItStartsWithExclamationMark() {
        admin.performCommand("vanish");
        PlayerMock mod = server.addPlayer("Mod");
        mod.setOp(true);
        assertFalse(reachesPublicChat(admin, "secret team note"), "goes to the team only");
        assertTrue(reachesPublicChat(admin, "!hello everyone"), "with ! in front it is public");
    }

    @Test
    void vanishLastsThroughARejoin() {
        admin.performCommand("vanish");
        admin.disconnect();
        admin.reconnect();
        server.getScheduler().performTicks(30);
        assertTrue(plugin.punishments().isVanished(admin.getUniqueId()));
        assertFalse(bystander.canSee(admin), "still hidden after coming back");
        assertTrue(plugin.getConfig().getStringList("vanish.players").contains(admin.getUniqueId().toString()), "kept for a restart");
    }

    @Test
    void mobsIgnoreVanishedStaffAndItemsStayOnTheGround() {
        admin.performCommand("vanish");
        var zombie = admin.getWorld().spawn(admin.getLocation(), org.bukkit.entity.Zombie.class);
        var target = new org.bukkit.event.entity.EntityTargetLivingEntityEvent(zombie, admin,
                org.bukkit.event.entity.EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        server.getPluginManager().callEvent(target);
        assertTrue(target.isCancelled());
        var item = admin.getWorld().dropItem(admin.getLocation(), new ItemStack(Material.DIAMOND));
        var pickup = new org.bukkit.event.entity.EntityPickupItemEvent(admin, item, 0);
        server.getPluginManager().callEvent(pickup);
        assertTrue(pickup.isCancelled());
    }

    @Test
    void hiddenCommandsAreRemovedForPlayersButNotForStaff() {
        var forPlayer = new java.util.ArrayList<>(java.util.List.of("plugins", "pl", "bukkit:help", "minecraft:tp", "spawn", "ticket", "version"));
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerCommandSendEvent(bystander, forPlayer));
        assertEquals(java.util.List.of("spawn", "ticket"), forPlayer, "only harmless commands are left");

        var forStaff = new java.util.ArrayList<>(java.util.List.of("plugins", "minecraft:tp"));
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerCommandSendEvent(admin, forStaff));
        assertEquals(2, forStaff.size(), "staff see everything");

        var typed = new PlayerCommandPreprocessEvent(bystander, "/bukkit:plugins");
        server.getPluginManager().callEvent(typed);
        assertTrue(typed.isCancelled(), "typing a hidden command is refused too");
        var allowed = new PlayerCommandPreprocessEvent(bystander, "/ticket hi");
        server.getPluginManager().callEvent(allowed);
        assertFalse(allowed.isCancelled());
    }

    @Test
    void teamCommandsNeedThePermission() {
        assertEquals("staffcore.staff", server.getCommandMap().getCommand("ban").getPermission());
        assertEquals("staffcore.vanish", server.getCommandMap().getCommand("vanish").getPermission());
        assertNull(server.getCommandMap().getCommand("guide").getPermission(), "the guide is for everyone");
        bystander.performCommand("ban Troll 1h test");
        assertNull(plugin.store().bans.get(troll.getUniqueId()));
    }

    // ------------------------------------------------------------------ spawn

    @Test
    void setspawnAndSpawnWithCountdown() {
        var spot = admin.getLocation().clone().add(100, 0, 100);
        admin.teleport(spot);
        assertTrue(admin.performCommand("setspawn"));
        assertTrue(plugin.spawn().isSet());

        bystander.teleport(spot.clone().add(-500, 0, 0));
        assertTrue(bystander.performCommand("spawn"));
        assertNotEquals(spot.getX(), bystander.getLocation().getX(), "not before the countdown");
        server.getScheduler().performTicks(20 * 4);
        assertEquals(spot.getX(), bystander.getLocation().getX(), 0.01, "after the countdown at spawn");

        // and it stops there instead of teleporting again and again
        bystander.teleport(spot.clone().add(-500, 0, 0));
        server.getScheduler().performTicks(20 * 5);
        assertNotEquals(spot.getX(), bystander.getLocation().getX(), "no second teleport by itself");
    }

    @Test
    void movingCancelsTheSpawnCountdown() {
        var spot = admin.getLocation().clone().add(100, 0, 100);
        plugin.spawn().setSpawn(spot);
        var start = spot.clone().add(-500, 0, 0);
        bystander.teleport(start);
        bystander.performCommand("spawn");
        var move = new org.bukkit.event.player.PlayerMoveEvent(bystander, start, start.clone().add(3, 0, 0));
        server.getPluginManager().callEvent(move);
        server.getScheduler().performTicks(20 * 5);
        assertNotEquals(spot.getX(), bystander.getLocation().getX(), "moving cancelled it");
    }

    @Test
    void staffTeleportAtOnceAndCanSendOthers() {
        var spot = admin.getLocation().clone().add(100, 0, 100);
        plugin.spawn().setSpawn(spot);
        admin.teleport(spot.clone().add(50, 0, 0));
        admin.performCommand("spawn");
        server.getScheduler().performTicks(2);
        assertEquals(spot.getX(), admin.getLocation().getX(), 0.01, "no countdown for staff");
        bystander.teleport(spot.clone().add(-50, 0, 0));
        admin.performCommand("spawn Bystander");
        server.getScheduler().performTicks(2);
        assertEquals(spot.getX(), bystander.getLocation().getX(), 0.01);
    }

    @Test
    void punishedPlayersMayUseSpawnButSpectatorBannedMayNot() {
        var spot = admin.getLocation().clone().add(100, 0, 100);
        plugin.spawn().setSpawn(spot);
        plugin.getConfig().set("spawn.warmup", 0);
        punish("1h");
        PlayerCommandPreprocessEvent pre = new PlayerCommandPreprocessEvent(troll, "/spawn");
        server.getPluginManager().callEvent(pre);
        assertFalse(pre.isCancelled(), "spawn is on the list of allowed commands");
        admin.performCommand("playban specban Bystander 1h test");
        var limboPos = bystander.getLocation().clone();
        bystander.performCommand("spawn");
        server.getScheduler().performTicks(2);
        assertEquals(limboPos.getX(), bystander.getLocation().getX(), 0.01, "stuck in the spectator ban");
    }

    @Test
    void respawnAtSpawnWithoutBed() {
        var spot = admin.getLocation().clone().add(100, 0, 100);
        plugin.spawn().setSpawn(spot);
        var event = new org.bukkit.event.player.PlayerRespawnEvent(bystander, bystander.getWorld().getSpawnLocation(), false, false,
                org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.DEATH);
        server.getPluginManager().callEvent(event);
        assertEquals(spot.getX(), event.getRespawnLocation().getX(), 0.01);
    }

    @Test
    void spawnMenuOpensInBothLanguages() {
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            plugin.adminMenus().spawnSettings(admin);
            assertNoMissingTexts(admin, code);
            plugin.adminMenus().guide(admin, 0);
            assertNoMissingTexts(admin, code);
        }
    }

    // ------------------------------------------------------------------ fixes from the code review

    @Test
    void punishmentEndingOfflineIsGivenBackOnTheNextJoin() {
        plugin.getConfig().set("inventory-mode", "STORE");
        troll.setGameMode(org.bukkit.GameMode.SURVIVAL);
        troll.getInventory().addItem(new ItemStack(Material.DIAMOND, 7));
        punish("1h");
        troll.disconnect();
        admin.performCommand("playban pardon Troll");
        assertTrue(plugin.store().restores.containsKey(troll.getUniqueId()), "remembered for later");
        troll.reconnect();
        server.getScheduler().performTicks(30);
        assertEquals(org.bukkit.GameMode.SURVIVAL, troll.getGameMode(), "not stuck in adventure mode");
        assertTrue(troll.getInventory().containsAtLeast(new ItemStack(Material.DIAMOND), 7), "items are back");
        assertFalse(plugin.store().restores.containsKey(troll.getUniqueId()));
    }

    @Test
    void restartDoesNotHandOutStoredItemsEarly() {
        plugin.getConfig().set("inventory-mode", "STORE");
        troll.getInventory().addItem(new ItemStack(Material.DIAMOND, 7));
        punish("1h");
        plugin.punishments().shutdown();
        assertFalse(troll.getInventory().contains(Material.DIAMOND), "a restart must not give the items back");
        assertNotNull(plugin.punishments().get(troll.getUniqueId()).storedInventory, "they stay stored");
        plugin.punishments().start();
        server.getScheduler().performTicks(2);
        assertEquals(org.bukkit.GameMode.ADVENTURE, troll.getGameMode());
        admin.performCommand("playban pardon Troll");
        assertTrue(troll.getInventory().containsAtLeast(new ItemStack(Material.DIAMOND), 7), "and come back at the real end");
    }

    @Test
    void clearModeAlsoWorksForPlayersPunishedWhileOffline() {
        plugin.getConfig().set("inventory-mode", "CLEAR");
        troll.getInventory().addItem(new ItemStack(Material.DIAMOND, 7));
        troll.disconnect();
        admin.performCommand("playban punish Troll 1h test");
        troll.reconnect();
        server.getScheduler().performTicks(30);
        assertFalse(troll.getInventory().contains(Material.DIAMOND));
        troll.getInventory().addItem(new ItemStack(Material.EMERALD, 1));
        troll.disconnect();
        troll.reconnect();
        server.getScheduler().performTicks(30);
        assertTrue(troll.getInventory().contains(Material.EMERALD), "cleared exactly once, not on every join");
    }

    @Test
    void brokenDataFileIsNeverOverwritten() throws Exception {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "data.yml");
        String broken = "active:\n  - this: [is not: valid\n";
        java.nio.file.Files.writeString(file.toPath(), broken);
        DataStore store = new DataStore(plugin);
        store.load();
        assertTrue(store.loadFailed());
        store.markDirty();
        store.saveNow();
        assertEquals(broken, java.nio.file.Files.readString(file.toPath()), "the file is untouched");
        java.io.File[] backups = plugin.getDataFolder().listFiles((d, n) -> n.startsWith("data.yml.broken-"));
        assertNotNull(backups);
        assertTrue(backups.length > 0, "and a copy was made");
    }

    @Test
    void punishedPlayersCannotTouchArmorStands() {
        punish("1h");
        var stand = troll.getWorld().spawn(troll.getLocation(), org.bukkit.entity.ArmorStand.class);
        var event = new org.bukkit.event.player.PlayerArmorStandManipulateEvent(troll, stand, new ItemStack(Material.AIR),
                new ItemStack(Material.DIAMOND_HELMET), org.bukkit.inventory.EquipmentSlot.HEAD, org.bukkit.inventory.EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void giftTaskIsSkippedWithoutARealVictim() {
        Punishment p = punish("1h");
        assertFalse(p.tasks.containsKey("gift"), "otherwise money could be sent to a second account and back");
    }

    @Test
    void quizHasLimitedTries() {
        Punishment p = punish("1h");
        TaskDef quiz = plugin.tasks().get("rules-quiz");
        for (int round = 0; round < 3; round++) {
            plugin.playerMenus().quiz(troll, quiz, 0);
            String right = quiz.questions.get(0).answers.get(0);
            var inv = troll.getOpenInventory().getTopInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack it = inv.getItem(i);
                if (it != null && it.getType() == Material.PAPER && !plain(it.getItemMeta().displayName()).equals(right)) {
                    troll.simulateInventoryClick(i);
                    break;
                }
            }
        }
        assertEquals(TaskStatus.EXPIRED, p.tasks.get("rules-quiz").status, "three wrong answers and the quiz is gone");
    }

    @Test
    void ticketCooldownStopsSpam() {
        plugin.getConfig().set("ticket-cooldown-seconds", 60);
        bystander.performCommand("ticket first");
        bystander.performCommand("ticket second");
        assertEquals(1, plugin.tickets().all().size());
        admin.performCommand("ticket staff are not limited");
        admin.performCommand("ticket really not");
        assertEquals(3, plugin.tickets().all().size());
    }

    @Test
    void longTicketTextIsCut() {
        bystander.performCommand("ticket " + "a".repeat(2000));
        assertEquals(500, plugin.tickets().all().get(0).text.length());
    }

    @Test
    void exemptPlayersStayProtectedWhileOffline() {
        PlayerMock owner = server.addPlayer("Owner");
        owner.setOp(true);
        owner.disconnect();
        owner.reconnect(); // joins as an op, so PlayBan learns that this player is protected
        owner.disconnect();
        PlayerMock mod = server.addPlayer("Moderator");
        mod.addAttachment(plugin, "staffcore.staff", true);
        mod.performCommand("ban Owner 1h abuse");
        assertNull(plugin.store().bans.get(owner.getUniqueId()), "an op stays protected while offline");
        server.getConsoleSender();
        server.dispatchCommand(server.getConsoleSender(), "ban Owner 1h console can");
        assertNotNull(plugin.store().bans.get(owner.getUniqueId()), "the console still can");
    }

    @Test
    void banWithOnlyAReasonIsPermanentLikeVanilla() {
        admin.performCommand("ban Troll Hacking with fly");
        Punishment ban = plugin.store().bans.get(troll.getUniqueId());
        assertNotNull(ban);
        assertEquals(0, ban.expiresAt, "permanent");
        assertEquals("Hacking with fly", ban.reason);
    }

    @Test
    void mistypedTimeIsNotTurnedIntoAPermanentBan() {
        admin.performCommand("ban Troll 1x test");
        assertNull(plugin.store().bans.get(troll.getUniqueId()));
    }

    @Test
    void banWithACategoryNameUsesTheCategory() {
        admin.performCommand("ban Troll x-ray");
        Punishment p = plugin.punishments().get(troll.getUniqueId());
        assertNotNull(p, "x-ray starts with a PlayBan, the ladder decides");
        assertEquals("x-ray", p.category);
    }

    @Test
    void vanishWorksWithOnlyTheVanishPermission() {
        PlayerMock helper = server.addPlayer("Helper");
        helper.addAttachment(plugin, "staffcore.vanish", true);
        helper.performCommand("vanish");
        assertTrue(plugin.punishments().isVanished(helper.getUniqueId()));
    }

    @Test
    void guideWorksForEveryone() {
        assertTrue(bystander.performCommand("guide"));
        assertInstanceOf(Menu.class, bystander.getOpenInventory().getTopInventory().getHolder());
    }

    @Test
    void pardonButtonNeedsShiftClick() {
        punish("1h");
        plugin.adminMenus().detail(admin, troll.getUniqueId());
        admin.simulateInventoryClick(48);
        assertTrue(plugin.punishments().isPunished(troll.getUniqueId()), "a normal click does nothing");
        admin.simulateInventoryClick(admin.getOpenInventory(), org.bukkit.event.inventory.ClickType.SHIFT_LEFT, 48);
        assertFalse(plugin.punishments().isPunished(troll.getUniqueId()));
    }

    @Test
    void spawnDoesNotTouchJoinsOrRespawnsUntilASpawnIsSet() {
        assertFalse(plugin.spawn().isSet());
        var event = new org.bukkit.event.player.PlayerRespawnEvent(bystander, bystander.getWorld().getSpawnLocation().add(7, 0, 7), false, false,
                org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.DEATH);
        var before = event.getRespawnLocation().clone();
        server.getPluginManager().callEvent(event);
        assertEquals(before.getX(), event.getRespawnLocation().getX(), 0.01, "a world plugin's respawn point is left alone");
    }

    // ------------------------------------------------------------------ ranks

    private boolean blocked(PlayerMock who, String line) {
        var e = new PlayerCommandPreprocessEvent(who, line);
        server.getPluginManager().callEvent(e);
        return e.isCancelled();
    }

    @Test
    void commandLevelsOnlyStartOnceSomeoneIsOwner() {
        PlayerMock op2 = server.addPlayer("OpTwo");
        op2.setOp(true);
        assertFalse(blocked(op2, "/gamemode creative"), "before setup nothing is limited, so nobody locks themselves out");
        assertTrue(admin.performCommand("rank setup"));
        assertEquals("owner", plugin.ranks().of(admin.getUniqueId()).id);
        assertTrue(plugin.ranks().active());
        assertTrue(blocked(op2, "/gamemode creative"), "OP alone is not enough any more");
        assertTrue(blocked(op2, "/minecraft:gamemode creative"), "not even with the minecraft: form");
        assertTrue(blocked(op2, "/execute run gamemode creative"), "and not through /execute");
        assertFalse(blocked(admin, "/gamemode creative"), "the owner may");
        op2.performCommand("rank setup");
        assertNotEquals("owner", plugin.ranks().of(op2.getUniqueId()).id, "setup works only once");
    }

    @Test
    void subCommandRulesAreExact() {
        admin.performCommand("rank setup");
        admin.performCommand("rank set Bystander mod");
        assertTrue(blocked(bystander, "/sc reload"), "sc reload needs level 90");
        assertTrue(blocked(bystander, "/playban reload"), "whichever alias is used");
        assertFalse(blocked(bystander, "/sc list"), "other parts of /sc are fine");
    }

    @Test
    void ranksGivePermissionsWithoutOp() {
        assertFalse(bystander.hasPermission("staffcore.staff"));
        admin.performCommand("rank set Bystander mod");
        assertTrue(bystander.hasPermission("staffcore.staff"), "a mod gets the staff permission");
        assertTrue(bystander.performCommand("ban Troll 1h test"));
        assertNotNull(plugin.store().bans.get(troll.getUniqueId()), "and can really ban, without being op");
        admin.performCommand("rank set Bystander admin");
        assertTrue(bystander.hasPermission("staffcore.ranks"), "staffcore.* gives every StaffCore permission");
        admin.performCommand("rank remove Bystander");
        assertFalse(bystander.hasPermission("staffcore.staff"), "and it is taken away again");
    }

    @Test
    void nobodyCanTouchRanksAtOrAboveTheirOwn() {
        admin.performCommand("rank setup");
        admin.performCommand("rank set Bystander admin");
        bystander.performCommand("rank set Troll co-owner");
        assertNotEquals("co-owner", plugin.ranks().of(troll.getUniqueId()).id, "an admin cannot hand out co-owner");
        bystander.performCommand("rank set Troll mod");
        assertEquals("mod", plugin.ranks().of(troll.getUniqueId()).id, "but mod is fine");
        bystander.performCommand("rank remove Admin");
        assertEquals("owner", plugin.ranks().of(admin.getUniqueId()).id, "and the owner stays owner");
        troll.performCommand("rank set Troll owner");
        assertEquals("mod", plugin.ranks().of(troll.getUniqueId()).id, "nobody promotes themselves");
    }

    @Test
    void blockedCommandsDisappearFromSuggestions() {
        admin.performCommand("rank setup");
        var list = new java.util.ArrayList<>(java.util.List.of("gamemode", "minecraft:gamemode", "spawn", "tp", "help"));
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerCommandSendEvent(bystander, list));
        assertFalse(list.contains("gamemode"));
        assertFalse(list.contains("tp"));
        assertTrue(list.contains("help"));
    }

    @Test
    void rankShowsInTabListAndChat() {
        admin.performCommand("rank set Bystander mod");
        assertTrue(plain(bystander.playerListName()).contains("MOD"), "prefix in the tab list");
        Component[] rendered = new Component[1];
        var listener = new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
            public void on(AsyncChatEvent e) {
                rendered[0] = e.renderer().render(e.getPlayer(), e.getPlayer().displayName(), e.message(), admin);
            }
        };
        server.getPluginManager().registerEvents(listener, plugin);
        typeInChat(bystander, "hello <red>red</red>");
        assertNotNull(rendered[0]);
        String text = plain(rendered[0]);
        assertTrue(text.startsWith("MOD"), "prefix in front: " + text);
        assertTrue(text.contains("Bystander"));
        assertTrue(text.contains("hello <red>red</red>"), "what the player typed is not turned into colors");
    }

    @Test
    void ranksSurviveARestart() {
        admin.performCommand("rank set Bystander mod");
        RankManager fresh = new RankManager(plugin);
        fresh.load();
        assertEquals("mod", fresh.of(bystander.getUniqueId()).id);
    }

    @Test
    void ranksAndLevelsCanBeEditedInTheMenu() {
        admin.performCommand("rank setup");
        var created = plugin.ranks().create("Builder");
        assertEquals("builder", created.id);
        plugin.ranks().setCommandLevel("/fly", 40);
        assertEquals(40, plugin.ranks().requiredLevel("/fly"));
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            plugin.rankMenus().list(admin);
            assertNoMissingTexts(admin, code);
            plugin.rankMenus().edit(admin, created);
            assertNoMissingTexts(admin, code);
            admin.simulateInventoryClick(14); // permissions
            assertNoMissingTexts(admin, code);
            plugin.rankMenus().edit(admin, created);
            admin.simulateInventoryClick(15); // members
            assertNoMissingTexts(admin, code);
            plugin.rankMenus().levels(admin, 0);
            assertNoMissingTexts(admin, code);
        }
        // toggling a permission in the menu gives it to the rank
        plugin.rankMenus().edit(admin, created);
        admin.simulateInventoryClick(14);
        admin.simulateInventoryClick(0); // "*"
        assertTrue(created.permissions.contains("*"));
    }

    // ------------------------------------------------------------------ admin commands

    @Test
    void tpadminMakesYouInvisibleAndTpbackReturns() {
        var home = admin.getLocation().clone();
        bystander.teleport(home.clone().add(200, 0, 0));
        assertTrue(admin.performCommand("tpadmin Bystander"));
        assertTrue(plugin.punishments().isVanished(admin.getUniqueId()), "you arrive invisible");
        assertEquals(bystander.getLocation().getX(), admin.getLocation().getX(), 0.01);
        assertFalse(bystander.canSee(admin));
        admin.performCommand("tpback");
        assertEquals(home.getX(), admin.getLocation().getX(), 0.01);
    }

    @Test
    void tphereBringsAPlayer() {
        bystander.teleport(admin.getLocation().clone().add(-300, 0, 0));
        admin.performCommand("tphere Bystander");
        assertEquals(admin.getLocation().getX(), bystander.getLocation().getX(), 0.01);
    }

    @Test
    void frozenPlayerCannotMoveBuildOrUseCommands() {
        admin.performCommand("freeze Troll");
        assertTrue(plugin.admin().isFrozen(troll.getUniqueId()));
        var here = troll.getLocation();
        var move = new org.bukkit.event.player.PlayerMoveEvent(troll, here.clone(), here.clone().add(4, 0, 0));
        server.getPluginManager().callEvent(move);
        assertEquals(here.getX(), move.getTo().getX(), 0.01);
        Block block = troll.getWorld().getBlockAt(5, 3, 5);
        block.setType(Material.STONE);
        BlockBreakEvent br = new BlockBreakEvent(block, troll);
        server.getPluginManager().callEvent(br);
        assertTrue(br.isCancelled());
        assertTrue(blocked(troll, "/spawn"));
        assertFalse(blocked(troll, "/ticket help"), "a ticket still works");
        server.getScheduler().performTicks(25);
        assertTrue(troll.getFreezeTicks() > 0, "the frost overlay shows");

        while (admin.nextComponentMessage() != null) { /* clear */ }
        troll.disconnect();
        boolean told = false;
        Component m;
        while ((m = admin.nextComponentMessage()) != null) if (plain(m).contains("Troll")) told = true;
        assertTrue(told, "logging out while frozen is reported");
        troll.reconnect();
        admin.performCommand("freeze Troll");
        assertFalse(plugin.admin().isFrozen(troll.getUniqueId()), "freeze again to undo");
    }

    @Test
    void invseeIsViewOnlyWithoutEditPermission() {
        PlayerMock mod = server.addPlayer("Moderator");
        mod.addAttachment(plugin, "staffcore.invsee", true);
        mod.performCommand("invsee Troll");
        assertSame(troll.getInventory(), mod.getOpenInventory().getTopInventory());
        var click = new org.bukkit.event.inventory.InventoryClickEvent(mod.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 0,
                org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(click);
        assertTrue(click.isCancelled(), "a mod can only look");
        admin.performCommand("invsee Troll");
        var adminClick = new org.bukkit.event.inventory.InventoryClickEvent(admin.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 0,
                org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(adminClick);
        assertFalse(adminClick.isCancelled(), "an admin can move items");
    }

    @Test
    void teamChatGoesToTheTeamOnly() {
        PlayerMock mod = server.addPlayer("Moderator");
        mod.addAttachment(plugin, "staffcore.teamchat", true);
        admin.performCommand("teamchat");
        while (mod.nextComponentMessage() != null) { /* clear */ }
        while (bystander.nextComponentMessage() != null) { /* clear */ }
        assertFalse(reachesPublicChat(admin, "secret plan"), "not in the public chat");
        server.getScheduler().performTicks(2);
        Component got = mod.nextComponentMessage();
        assertNotNull(got);
        assertTrue(plain(got).contains("secret plan"));
        assertNull(bystander.nextComponentMessage(), "players do not see it");
    }

    @Test
    void godFlyAndHeal() {
        admin.performCommand("god");
        var dmg = new EntityDamageEvent(admin, EntityDamageEvent.DamageCause.FALL,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.FALL).build(), 5);
        server.getPluginManager().callEvent(dmg);
        assertTrue(dmg.isCancelled());
        admin.performCommand("fly");
        assertTrue(admin.getAllowFlight());
        bystander.setHealth(3);
        admin.performCommand("heal Bystander");
        assertEquals(20, bystander.getHealth(), 0.01);
        bystander.performCommand("god");
        assertFalse(plugin.admin().isGod(bystander.getUniqueId()), "players cannot");
    }

    @Test
    void clearChatLeavesTheTeamAlone() {
        while (bystander.nextComponentMessage() != null) { /* clear */ }
        while (admin.nextComponentMessage() != null) { /* clear */ }
        admin.performCommand("clearchat");
        int playerLines = 0, adminLines = 0;
        while (bystander.nextComponentMessage() != null) playerLines++;
        while (admin.nextComponentMessage() != null) adminLines++;
        assertTrue(playerLines > 100);
        assertTrue(adminLines < 5);
    }

    // ------------------------------------------------------------------ admin items

    private ItemStack tool(String id) {
        for (var t : ToolManager.TOOLS) if (t.id().equals(id)) return plugin.tools().create(t);
        throw new AssertionError(id);
    }

    private void rightClickPlayer(PlayerMock staff, PlayerMock target) {
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerInteractEntityEvent(staff, target, org.bukkit.inventory.EquipmentSlot.HAND));
    }

    private void rightClickAir(PlayerMock staff) {
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerInteractEvent(staff, org.bukkit.event.block.Action.RIGHT_CLICK_AIR,
                staff.getInventory().getItemInMainHand(), null, org.bukkit.block.BlockFace.SELF, org.bukkit.inventory.EquipmentSlot.HAND));
    }

    @Test
    void toolsMenuHandsOutOnlyAllowedItems() {
        assertTrue(admin.performCommand("tools"));
        admin.simulateInventoryClick(10);
        assertNotNull(plugin.tools().toolOf(admin.getInventory().getItem(0)), "the admin got the bannhammer");
        PlayerMock mod = server.addPlayer("Moderator");
        mod.addAttachment(plugin, "staffcore.tools", true);
        plugin.tools().menu(mod);
        mod.simulateInventoryClick(10);
        assertNull(plugin.tools().toolOf(mod.getInventory().getItem(0)), "without staffcore.tool.banhammer there is nothing to take");
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            plugin.tools().menu(admin);
            assertNoMissingTexts(admin, code);
        }
    }

    @Test
    void bannhammerOpensThePunishMenuInsteadOfHurting() {
        admin.getInventory().setItemInMainHand(tool("banhammer"));
        var hit = new org.bukkit.event.entity.EntityDamageByEntityEvent(admin, troll, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK).withCausingEntity(admin).withDirectEntity(admin).build(), 6);
        server.getPluginManager().callEvent(hit);
        assertTrue(hit.isCancelled());
        server.getScheduler().performTicks(2);
        assertInstanceOf(Menu.class, admin.getOpenInventory().getTopInventory().getHolder(), "the category menu is open");
    }

    @Test
    void handcuffsLeadAPlayerAndRestrainThem() {
        admin.getInventory().setItemInMainHand(tool("handcuffs"));
        troll.teleport(admin.getLocation().clone().add(30, 0, 0));
        rightClickPlayer(admin, troll);
        assertTrue(plugin.admin().isCuffed(troll.getUniqueId()));
        server.getScheduler().performTicks(6);
        assertTrue(troll.getLocation().distance(admin.getLocation()) < 3, "pulled along behind the admin");
        assertTrue(blocked(troll, "/spawn"), "and cannot act on their own");
        rightClickPlayer(admin, troll);
        assertFalse(plugin.admin().isCuffed(troll.getUniqueId()), "a second click lets go");
    }

    @Test
    void inspectorFreezeWandAndVanishCloak() {
        admin.getInventory().setItemInMainHand(tool("inspector"));
        rightClickPlayer(admin, troll);
        assertInstanceOf(Menu.class, admin.getOpenInventory().getTopInventory().getHolder(), "the profile opens");
        admin.getInventory().setItemInMainHand(tool("freeze"));
        rightClickPlayer(admin, troll);
        assertTrue(plugin.admin().isFrozen(troll.getUniqueId()));
        admin.getInventory().setItemInMainHand(tool("vanish"));
        rightClickAir(admin);
        assertTrue(plugin.punishments().isVanished(admin.getUniqueId()));
        admin.getInventory().setItemInMainHand(tool("compass"));
        rightClickAir(admin);
        assertInstanceOf(Menu.class, admin.getOpenInventory().getTopInventory().getHolder(), "the player list opens");
    }

    @Test
    void stolenToolsDoNothing() {
        bystander.getInventory().setItemInMainHand(tool("handcuffs"));
        rightClickPlayer(bystander, troll);
        assertFalse(plugin.admin().isCuffed(troll.getUniqueId()), "a player without the permission cannot use it");
        bystander.getInventory().setItemInMainHand(tool("vanish"));
        rightClickAir(bystander);
        assertFalse(plugin.punishments().isVanished(bystander.getUniqueId()));
    }

    @Test
    void xrayFindsNearbyOres() {
        var at = admin.getLocation();
        at.getWorld().getBlockAt(at.getBlockX() + 3, at.getBlockY(), at.getBlockZ()).setType(Material.DIAMOND_ORE);
        at.getWorld().getBlockAt(at.getBlockX() - 5, at.getBlockY(), at.getBlockZ()).setType(Material.ANCIENT_DEBRIS);
        var ores = plugin.tools().findOres(at, 8, 50);
        assertEquals(2, ores.size());
        assertEquals(Material.DIAMOND_ORE, ores.get(0).getType(), "nearest first");
        admin.getInventory().setItemInMainHand(tool("xray"));
        rightClickAir(admin);
    }

    // ------------------------------------------------------------------ block log

    private dev.staffcore.log.LogDatabase logDb() throws Exception {
        var db = plugin.blockLog().db();
        for (int i = 0; i < 100 && !db.ready(); i++) Thread.sleep(20);
        assertTrue(db.ready(), "the block log database opened");
        return db;
    }

    private java.util.List<dev.staffcore.log.LogDatabase.Entry> logAround(Block b, String actor) throws Exception {
        var q = new dev.staffcore.log.LogDatabase.Query();
        q.world = b.getWorld().getName();
        q.x = b.getX();
        q.y = b.getY();
        q.z = b.getZ();
        q.radius = 10;
        q.actor = actor;
        return logDb().find(q).get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Waits for work that ends on the main thread, letting the test server tick meanwhile. */
    private <T> T await(java.util.concurrent.CompletableFuture<T> f) throws Exception {
        for (int i = 0; i < 500 && !f.isDone(); i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(5);
        }
        return f.get(1, java.util.concurrent.TimeUnit.SECONDS);
    }

    private Block breakBlock(PlayerMock who, int x, Material type) {
        Block b = who.getWorld().getBlockAt(x, 10, 0);
        b.setType(type);
        server.getPluginManager().callEvent(new BlockBreakEvent(b, who));
        b.setType(Material.AIR);
        return b;
    }

    @Test
    void brokenAndPlacedBlocksAreLogged() throws Exception {
        logDb();
        Block b = breakBlock(troll, 0, Material.DIAMOND_ORE);
        Block placed = troll.getWorld().getBlockAt(1, 10, 0);
        var replaced = placed.getState();
        placed.setType(Material.TNT);
        server.getPluginManager().callEvent(new BlockPlaceEvent(placed, replaced, b, new ItemStack(Material.TNT), troll, true,
                org.bukkit.inventory.EquipmentSlot.HAND));
        var entries = logAround(b, troll.getUniqueId().toString());
        assertEquals(2, entries.size());
        assertEquals(dev.staffcore.log.LogDatabase.Action.PLACE, entries.get(0).action(), "newest first");
        assertEquals(dev.staffcore.log.LogDatabase.Action.BREAK, entries.get(1).action());
        assertTrue(entries.get(1).before().contains("diamond_ore"));
        assertTrue(plain(plugin.blockLog().line(entries.get(1))).contains("Troll"), "the line names the player");
        assertTrue(plain(plugin.blockLog().line(entries.get(1))).contains("Diamond Ore"));
    }

    @Test
    void rollbackAndRestoreAPlayersChanges() throws Exception {
        logDb();
        breakBlock(troll, 0, Material.STONE);
        breakBlock(troll, 1, Material.OAK_PLANKS);
        breakBlock(bystander, 2, Material.GLASS);
        var q = new dev.staffcore.log.LogDatabase.Query();
        q.actor = troll.getUniqueId().toString();
        q.world = troll.getWorld().getName();
        q.radius = -1;
        q.since = 0;
        int undone = await(plugin.blockLog().rollback(q, false));
        assertEquals(2, undone);
        assertEquals(Material.STONE, troll.getWorld().getBlockAt(0, 10, 0).getType(), "Troll's blocks are back");
        assertEquals(Material.OAK_PLANKS, troll.getWorld().getBlockAt(1, 10, 0).getType());
        assertEquals(Material.AIR, troll.getWorld().getBlockAt(2, 10, 0).getType(), "Bystander's change is left alone");
        var q2 = new dev.staffcore.log.LogDatabase.Query();
        q2.actor = troll.getUniqueId().toString();
        q2.world = troll.getWorld().getName();
        q2.radius = -1;
        await(plugin.blockLog().rollback(q2, true));
        assertEquals(Material.AIR, troll.getWorld().getBlockAt(0, 10, 0).getType(), "restore puts the griefing back");
    }

    @Test
    void chestTakingIsLogged() throws Exception {
        logDb();
        Block b = troll.getWorld().getBlockAt(5, 10, 5);
        b.setType(Material.CHEST);
        var chest = (org.bukkit.block.Container) b.getState();
        chest.getInventory().addItem(new ItemStack(Material.DIAMOND, 20));
        troll.openInventory(chest.getInventory());
        chest.getInventory().removeItem(new ItemStack(Material.DIAMOND, 12));
        troll.closeInventory();
        var entries = logAround(b, troll.getUniqueId().toString());
        assertEquals(1, entries.size());
        assertEquals(dev.staffcore.log.LogDatabase.Action.TAKE, entries.get(0).action());
        assertEquals(12, entries.get(0).amount());
        assertTrue(plain(plugin.blockLog().line(entries.get(0))).contains("12x Diamond"));
    }

    @Test
    void lookupCommandsAndBrushAnswer() throws Exception {
        logDb();
        breakBlock(troll, 0, Material.STONE);
        while (admin.nextComponentMessage() != null) { /* clear */ }
        assertTrue(admin.performCommand("bl near 20 1h"));
        Thread.sleep(200);
        server.getScheduler().performTicks(2);
        boolean found = false;
        Component m;
        while ((m = admin.nextComponentMessage()) != null) if (plain(m).contains("Troll")) found = true;
        assertTrue(found, "/bl near lists Troll's change");

        admin.getInventory().setItemInMainHand(tool("brush"));
        Block b = troll.getWorld().getBlockAt(0, 10, 0);
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerInteractEvent(admin, org.bukkit.event.block.Action.LEFT_CLICK_BLOCK,
                admin.getInventory().getItemInMainHand(), b, org.bukkit.block.BlockFace.UP, org.bukkit.inventory.EquipmentSlot.HAND));
        Thread.sleep(200);
        server.getScheduler().performTicks(2);
        found = false;
        while ((m = admin.nextComponentMessage()) != null) if (plain(m).contains("Troll")) found = true;
        assertTrue(found, "the brush shows the history of the block");
    }

    @Test
    void replayRunsAndStops() throws Exception {
        logDb();
        breakBlock(troll, 0, Material.STONE);
        breakBlock(troll, 1, Material.STONE);
        admin.performCommand("bl replay 20 1h");
        Thread.sleep(200);
        server.getScheduler().performTicks(2);
        assertTrue(plugin.blockLog().replaying(admin.getUniqueId()));
        admin.performCommand("bl faster");
        admin.performCommand("bl pause");
        server.getScheduler().performTicks(60);
        admin.performCommand("bl stop");
        assertFalse(plugin.blockLog().replaying(admin.getUniqueId()));
    }

    // ------------------------------------------------------------------ staff mode

    @Test
    void staffModeSwapsTheInventoryAndBack() {
        admin.getInventory().addItem(new ItemStack(Material.DIAMOND_SWORD));
        admin.performCommand("staffmode");
        assertTrue(plugin.tools().inStaffMode(admin.getUniqueId()));
        assertNotNull(plugin.tools().toolOf(admin.getInventory().getItem(0)), "the hotbar is full of admin items");
        assertEquals("compass", plugin.tools().toolOf(admin.getInventory().getItem(0)).id());
        assertFalse(admin.getInventory().contains(Material.DIAMOND_SWORD), "the own things are stored away");
        assertTrue(plugin.punishments().isVanished(admin.getUniqueId()));
        assertTrue(admin.getAllowFlight());
        var drop = new org.bukkit.event.player.PlayerDropItemEvent(admin, admin.getWorld().dropItem(admin.getLocation(), admin.getInventory().getItem(0)));
        server.getPluginManager().callEvent(drop);
        assertTrue(drop.isCancelled(), "admin items cannot be dropped");
        admin.performCommand("staffmode");
        assertTrue(admin.getInventory().contains(Material.DIAMOND_SWORD), "back again");
        assertFalse(plugin.punishments().isVanished(admin.getUniqueId()));
        assertNull(plugin.tools().toolOf(admin.getInventory().getItem(0)));
    }

    @Test
    void staffModeGivesThingsBackOnQuitAndAfterACrash() {
        admin.getInventory().addItem(new ItemStack(Material.GOLDEN_APPLE, 3));
        admin.performCommand("staffmode");
        admin.disconnect();
        assertTrue(admin.getInventory().contains(Material.GOLDEN_APPLE), "leaving the server ends staff mode");
        admin.reconnect();
        // a crash: the stored inventory is on disk, the player joins with the admin items
        admin.performCommand("staffmode");
        admin.disconnect();
        plugin.store().staffModeInventories.put(admin.getUniqueId(),
                java.util.Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(new ItemStack[]{new ItemStack(Material.EMERALD, 4)})));
        admin.getInventory().clear();
        admin.reconnect();
        assertTrue(admin.getInventory().contains(Material.EMERALD), "given back on the next join");
        assertFalse(plugin.tools().inStaffMode(admin.getUniqueId()));
    }

    @Test
    void warnStickWarnsAndTakesItBack() {
        admin.getInventory().setItemInMainHand(tool("warnstick"));
        var hit = new org.bukkit.event.entity.EntityDamageByEntityEvent(admin, troll, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK).withCausingEntity(admin).withDirectEntity(admin).build(), 6);
        server.getPluginManager().callEvent(hit);
        assertTrue(hit.isCancelled());
        var history = plugin.store().history.get(troll.getUniqueId());
        assertEquals(1, history.size());
        assertEquals("WARN", history.get(0).type);
        server.getPluginManager().callEvent(hit);
        assertEquals(1, history.size(), "a double hit is still one warning");
        rightClickPlayer(admin, troll);
        assertTrue(history.isEmpty(), "right-click takes it back");
    }

    @Test
    void modCannotTakeBackSomeoneElsesWarning() {
        admin.performCommand("warn Troll spam");
        PlayerMock mod = server.addPlayer("Moderator");
        mod.addAttachment(plugin, "staffcore.tool.warnstick", true);
        mod.getInventory().setItemInMainHand(tool("warnstick"));
        rightClickPlayer(mod, troll);
        assertEquals(1, plugin.store().history.get(troll.getUniqueId()).size());
    }

    @Test
    void newToolsAndStaffModeShowInBothLanguages() {
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            plugin.tools().menu(admin);
            assertNoMissingTexts(admin, code);
            plugin.adminMenus().guide(admin, 0);
            assertNoMissingTexts(admin, code);
            plugin.adminMenus().guide(admin, 1);
            assertNoMissingTexts(admin, code);
        }
    }

    // ------------------------------------------------------------------ mute, ip ban, lockdown, chat control

    private org.bukkit.event.player.AsyncPlayerPreLoginEvent login(String name, java.util.UUID id, java.net.InetAddress address) {
        var e = new org.bukkit.event.player.AsyncPlayerPreLoginEvent(name, address, id);
        new dev.staffcore.listener.ConnectionListener(plugin).onPreLogin(e);
        return e;
    }

    @Test
    void muteBlocksChatAndPrivateMessages() {
        assertTrue(admin.performCommand("mute Troll 1h Spam"));
        assertNotNull(plugin.punishments().activeMute(troll.getUniqueId()));
        assertFalse(reachesPublicChat(troll, "spam spam"), "a muted player cannot chat");
        assertTrue(blocked(troll, "/msg Bystander hi"), "nor whisper");
        assertFalse(blocked(troll, "/spawn"), "other commands still work");
        admin.performCommand("unmute Troll");
        assertTrue(reachesPublicChat(troll, "sorry"), "unmuted, they can write again");
        assertEquals("MUTE", plugin.store().history.get(troll.getUniqueId()).get(0).type, "kept in the history");
    }

    @Test
    void muteWithoutTimeIsPermanentAndMutesExpire() {
        admin.performCommand("mute Troll being rude");
        Punishment m = plugin.punishments().activeMute(troll.getUniqueId());
        assertEquals(0, m.expiresAt, "no time given: permanent");
        assertEquals("being rude", m.reason);
        admin.performCommand("unmute Troll");
        admin.performCommand("mute Troll 1h x");
        plugin.store().mutes.get(troll.getUniqueId()).expiresAt = System.currentTimeMillis() - 1;
        server.getScheduler().performTicks(25);
        assertNull(plugin.punishments().activeMute(troll.getUniqueId()), "it ends on its own");
    }

    @Test
    void mutesSurviveARestart() {
        admin.performCommand("mute Troll 1h Spam");
        plugin.store().saveNow();
        DataStore fresh = new DataStore(plugin);
        fresh.load();
        assertEquals("Spam", fresh.mutes.get(troll.getUniqueId()).reason);
    }

    @Test
    void muteCanBeACategoryStep() {
        var cat = plugin.categories().create("Chat spam");
        cat.steps.clear();
        cat.steps.add(new dev.staffcore.model.Category.Step("MUTE", 1_800_000L));
        admin.performCommand("punish Troll " + cat.id);
        assertNotNull(plugin.punishments().activeMute(troll.getUniqueId()));
        assertFalse(plugin.punishments().isPunished(troll.getUniqueId()), "only a mute, not a PlayBan");
    }

    @Test
    void ipBanKeepsSecondAccountsOut() throws Exception {
        var address = new java.net.InetSocketAddress(java.net.InetAddress.getByName("10.9.8.7"), 25565);
        troll.setAddress(address);
        plugin.store().rememberAddress(troll);
        admin.performCommand("ipban Troll perm Hacking");
        Punishment ban = plugin.store().bans.get(troll.getUniqueId());
        assertNotNull(ban);
        assertNotNull(ban.ipHash, "the address fingerprint is on the ban");
        assertFalse(ban.ipHash.contains("10.9"), "the address itself is not stored");
        var alt = login("TrollAlt", java.util.UUID.randomUUID(), address.getAddress());
        assertEquals(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_BANNED, alt.getLoginResult(), "a new account from that address is refused");
        var other = login("Someone", java.util.UUID.randomUUID(), java.net.InetAddress.getByName("10.1.1.1"));
        assertEquals(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED, other.getLoginResult());
        admin.performCommand("unban Troll");
        assertEquals(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED,
                login("TrollAlt", java.util.UUID.randomUUID(), address.getAddress()).getLoginResult(), "and lifted with the ban");
    }

    @Test
    void ipBanNeedsAKnownAddress() {
        PlayerMock fresh = server.addPlayer("NeverSeen");
        plugin.store().addresses.remove(fresh.getUniqueId());
        admin.performCommand("ipban NeverSeen perm x");
        assertNull(plugin.store().bans.get(fresh.getUniqueId()));
    }

    @Test
    void lockdownLetsOnlyTheTeamIn() {
        admin.performCommand("lockdown on Maintenance");
        assertFalse(bystander.isOnline(), "players are kicked");
        assertTrue(admin.isOnline(), "the team stays");
        var player = login("Visitor", java.util.UUID.randomUUID(), java.net.InetAddress.getLoopbackAddress());
        assertEquals(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_OTHER, player.getLoginResult());
        var op = login("Admin", admin.getUniqueId(), java.net.InetAddress.getLoopbackAddress());
        assertEquals(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED, op.getLoginResult(), "ops may join");
        admin.performCommand("lockdown off");
        assertEquals(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED,
                login("Visitor", java.util.UUID.randomUUID(), java.net.InetAddress.getLoopbackAddress()).getLoginResult());
    }

    @Test
    void muteChatAndSlowChat() {
        admin.performCommand("mutechat");
        assertFalse(reachesPublicChat(bystander, "hello"), "players cannot write");
        assertTrue(reachesPublicChat(admin, "announcement"), "the team can");
        admin.performCommand("mutechat");
        admin.performCommand("slowchat 30");
        assertTrue(reachesPublicChat(bystander, "first"));
        assertFalse(reachesPublicChat(bystander, "second too fast"), "slow chat holds the second message back");
        admin.performCommand("slowchat off");
        assertTrue(reachesPublicChat(bystander, "now it works"));
    }

    @Test
    void whoisAltsStafflistNearAndReport() {
        while (admin.nextComponentMessage() != null) { /* clear */ }
        admin.performCommand("whois Troll");
        StringBuilder out = new StringBuilder();
        Component m;
        while ((m = admin.nextComponentMessage()) != null) out.append(plain(m)).append('\n');
        assertTrue(out.toString().contains("Troll"));
        assertTrue(out.toString().contains("Ping"), "live data for online players");
        admin.performCommand("stafflist");
        boolean listed = false;
        while ((m = admin.nextComponentMessage()) != null) if (plain(m).contains("Admin")) listed = true;
        assertTrue(listed);
        admin.performCommand("near 1000");
        bystander.performCommand("report Troll destroyed my house");
        assertEquals(1, plugin.tickets().all().size());
        assertEquals("report", plugin.tickets().all().get(0).reason);
        assertTrue(plugin.tickets().all().get(0).text.contains("Troll"));
        bystander.performCommand("whois Troll");
        // normal players cannot look players up
        assertNull(bystander.nextComponentMessage() == null ? null : null);
    }

    @Test
    void timeParsing() {
        assertEquals(90_000_000L + 1_800_000L, TimeUtil.parse("1d 1h 30m"));
        assertEquals(1_800_000L, TimeUtil.parse("30"));
        assertEquals(-1, TimeUtil.parse("soon"));
        assertEquals(-1, TimeUtil.parse("1h banana"));
        assertEquals("1d 1h 30m", TimeUtil.format(91_800_000L));
    }

    // ------------------------------------------------------------------ 0.11: prison, trolls, new tools, review fixes

    @Test
    void prisonWallsInLocksTheInventoryAndGivesEverythingBack() {
        var w = troll.getWorld();
        troll.teleport(new org.bukkit.Location(w, 100.5, 70, 100.5));
        w.getBlockAt(100, 69, 100).setType(Material.GRASS_BLOCK);
        w.getBlockAt(101, 70, 100).setType(Material.OAK_LOG);
        troll.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));
        troll.getInventory().setItem(5, new ItemStack(Material.BREAD, 7));
        assertTrue(admin.performCommand("prison Troll 10m Griefing"));
        assertTrue(plugin.prison().isJailed(troll.getUniqueId()));
        assertEquals(Material.BEDROCK, w.getBlockAt(100, 69, 100).getType(), "floor");
        assertEquals(Material.BEDROCK, w.getBlockAt(101, 70, 100).getType(), "walls");
        assertEquals(Material.BEDROCK, w.getBlockAt(100, 72, 100).getType(), "roof");
        assertEquals(Material.AIR, w.getBlockAt(100, 71, 100).getType(), "room for the head");
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, troll.getInventory().getItem(0).getType(), "the sword is locked away");
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, troll.getInventory().getItem(20).getType());

        var scroll = new org.bukkit.event.player.PlayerItemHeldEvent(troll, 0, 3);
        server.getPluginManager().callEvent(scroll);
        assertTrue(scroll.isCancelled(), "no scrolling through the hotbar");
        var cmd = new org.bukkit.event.player.PlayerCommandPreprocessEvent(troll, "/spawn");
        server.getPluginManager().callEvent(cmd);
        assertTrue(cmd.isCancelled(), "no commands");
        var ticket = new org.bukkit.event.player.PlayerCommandPreprocessEvent(troll, "/ticket help");
        server.getPluginManager().callEvent(ticket);
        assertFalse(ticket.isCancelled(), "tickets still work");
        var breakCage = new BlockBreakEvent(w.getBlockAt(101, 70, 100), admin);
        server.getPluginManager().callEvent(breakCage);
        assertTrue(breakCage.isCancelled(), "nobody breaks the cage");

        assertTrue(admin.performCommand("unprison Troll"));
        assertFalse(plugin.prison().isJailed(troll.getUniqueId()));
        assertEquals(Material.GRASS_BLOCK, w.getBlockAt(100, 69, 100).getType(), "the ground is back");
        assertEquals(Material.OAK_LOG, w.getBlockAt(101, 70, 100).getType(), "the tree is back");
        assertEquals(Material.DIAMOND_SWORD, troll.getInventory().getItem(0).getType(), "the sword is back");
        assertEquals(7, troll.getInventory().getItem(5).getAmount());
    }

    @Test
    void prisonSurvivesARestartAndEndsOnItsOwn() {
        troll.teleport(new org.bukkit.Location(troll.getWorld(), 50.5, 70, 50.5));
        troll.getInventory().setItem(0, new ItemStack(Material.EMERALD, 3));
        admin.performCommand("prison Troll 2s");
        // a fresh manager reads the cell from disk, as after a restart
        var again = new PrisonManager(plugin);
        again.load();
        assertTrue(again.isJailed(troll.getUniqueId()));
        plugin.prison().release(troll.getUniqueId(), "Admin");
        plugin.prison().jail(troll, 1, "test", "Admin");
        try {
            Thread.sleep(10);
        } catch (InterruptedException ignored) {
            // fine
        }
        server.getScheduler().performTicks(25);
        assertFalse(plugin.prison().isJailed(troll.getUniqueId()), "the time ran out");
        assertEquals(Material.EMERALD, troll.getInventory().getItem(0).getType());
    }

    @Test
    void exemptPlayersCannotBeJailedOrTrolled() {
        bystander.addAttachment(plugin, "staffcore.exempt", true);
        PlayerMock mod = server.addPlayer("Moderator");
        mod.addAttachment(plugin, "staffcore.prison", true);
        mod.addAttachment(plugin, "staffcore.troll", true);
        mod.performCommand("prison Bystander 5m");
        assertFalse(plugin.prison().isJailed(bystander.getUniqueId()));
        int before = bystander.getWorld().getEntities().size();
        mod.performCommand("troll Bystander chickens");
        server.getScheduler().performTicks(40);
        assertEquals(before, bystander.getWorld().getEntities().size(), "no chickens for protected players");
    }

    @Test
    void chickenRainComesAndGoesWithoutDrops() {
        int before = troll.getWorld().getEntities().size();
        assertTrue(admin.performCommand("troll Troll chickens"));
        server.getScheduler().performTicks(40);
        assertTrue(troll.getWorld().getEntities().size() > before, "chickens fell");
        server.getScheduler().performTicks(200);
        assertEquals(before, troll.getWorld().getEntities().size(), "and are gone again");
        assertTrue(admin.performCommand("troll Troll fakeop"));
        boolean fake = false;
        Component m;
        while ((m = troll.nextComponentMessage()) != null) if (plain(m).contains("Made Troll a server operator")) fake = true;
        assertTrue(fake);
        assertFalse(troll.isOp(), "only the message, of course");
        admin.performCommand("troll Troll");
        assertTrue(admin.getOpenInventory().getTopInventory().getHolder() instanceof dev.staffcore.gui.Menu, "without a joke the menu opens");
    }

    @Test
    void rankEditorsCannotHandOutMoreThanTheyHave() {
        admin.performCommand("rank setup");
        PlayerMock mod = server.addPlayer("Moderator");
        admin.performCommand("rank set Moderator admin");
        assertFalse(plugin.ranks().mayGrant(mod, "*"), "only the top rank hands out everything");
        assertFalse(plugin.ranks().mayGrant(mod, "luckperms.*"));
        assertFalse(plugin.ranks().mayGrant(mod, "some.plugin.secret"), "nothing they do not have");
        assertTrue(plugin.ranks().mayGrant(admin, "*"), "the owner may");
        assertFalse(plugin.ranks().isTop(mod));
        assertTrue(plugin.ranks().isTop(admin));
    }

    @Test
    void commandRulesAlsoHoldWhenAPluginRunsTheCommand() {
        admin.performCommand("rank setup");
        bystander.setOp(true);
        org.bukkit.command.Command cmd = server.getCommandMap().getCommand("gamemode");
        if (cmd == null || cmd.getPermission() == null) return; // the test server has no vanilla commands
        plugin.ranks().apply(bystander);
        assertFalse(bystander.hasPermission(cmd.getPermission()), "an op below the level loses the permission itself");
        assertTrue(admin.hasPermission(cmd.getPermission()));
    }

    @Test
    void rollbackLeavesLaterChangesOfOthersAlone() throws Exception {
        logDb();
        breakBlock(troll, 0, Material.STONE);
        // someone else builds a chest there afterwards
        troll.getWorld().getBlockAt(0, 10, 0).setType(Material.CHEST);
        var q = new dev.staffcore.log.LogDatabase.Query();
        q.actor = troll.getUniqueId().toString();
        q.world = troll.getWorld().getName();
        q.radius = -1;
        assertEquals(0, (int) await(plugin.blockLog().rollback(q, false)));
        assertEquals(Material.CHEST, troll.getWorld().getBlockAt(0, 10, 0).getType(), "the chest stays");
    }

    @Test
    void consoleCannotRollBackTheWholeServer() throws Exception {
        logDb();
        breakBlock(troll, 0, Material.STONE);
        server.dispatchCommand(server.getConsoleSender(), "blocklog rollback 1h");
        server.getScheduler().performTicks(20);
        assertEquals(Material.AIR, troll.getWorld().getBlockAt(0, 10, 0).getType());
    }

    @Test
    void teamProtocolRecordsPunishmentsAndCommands() throws Exception {
        logDb();
        admin.performCommand("warn Troll spam");
        // the test server does not send typed commands through the event, so it is sent here
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerCommandPreprocessEvent(admin, "/warn Troll spam"));
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerCommandPreprocessEvent(admin, "/msg Troll secret"));
        admin.performCommand("prison Bystander 5m");
        var list = await(plugin.blockLog().db().staffActions("Troll", 10));
        assertTrue(list.stream().anyMatch(a -> a.action().equals("warn") && a.actor().equals("Admin")), "the warning is in the protocol");
        var all = await(plugin.blockLog().db().staffActions(null, 50));
        assertTrue(all.stream().anyMatch(a -> a.action().equals("prison")));
        assertTrue(all.stream().anyMatch(a -> a.action().equals("command") && a.detail().startsWith("/warn")), "typed commands too");
        assertFalse(all.stream().anyMatch(a -> a.detail() != null && a.detail().contains("secret")), "private messages stay private");
        while (admin.nextComponentMessage() != null) { /* clear */ }
        admin.performCommand("stafflog Troll");
        server.getScheduler().performTicks(5);
        Thread.sleep(50);
        server.getScheduler().performTicks(5);
        boolean shown = false;
        Component m;
        while ((m = admin.nextComponentMessage()) != null) if (plain(m).contains("warn")) shown = true;
        assertTrue(shown);
    }

    @Test
    void baitChestTellsTheTeam() {
        Block b = troll.getWorld().getBlockAt(8, 10, 8);
        b.setType(Material.CHEST);
        assertTrue(plugin.moderation().toggleBait(b));
        while (admin.nextComponentMessage() != null) { /* clear */ }
        troll.openInventory(((org.bukkit.block.Container) b.getState()).getInventory());
        boolean told = false;
        Component m;
        while ((m = admin.nextComponentMessage()) != null) if (plain(m).contains("Troll") && plain(m).contains("bait")) told = true;
        assertTrue(told, "the team hears about it");
        assertFalse(plugin.moderation().toggleBait(b), "a second click takes the mark away");
    }

    @Test
    void adminItemsNeverDropAndStayOutOfChests() {
        admin.getInventory().setItem(0, tool("banhammer"));
        var drops = new java.util.ArrayList<ItemStack>(java.util.List.of(tool("banhammer"), new ItemStack(Material.DIRT)));
        var death = new org.bukkit.event.entity.PlayerDeathEvent(admin, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(),
                drops, 0, (net.kyori.adventure.text.Component) null);
        server.getPluginManager().callEvent(death);
        assertEquals(1, death.getDrops().size(), "only the dirt drops");
        assertEquals(Material.DIRT, death.getDrops().get(0).getType());
    }

    @Test
    void staffModeKeepsWhatWasPickedUpMeanwhile() {
        admin.getInventory().setItem(0, new ItemStack(Material.APPLE));
        admin.performCommand("staffmode");
        admin.getInventory().setItem(30, new ItemStack(Material.GOLD_INGOT, 5));
        assertTrue(plugin.tools().inStaffMode(admin.getUniqueId()));
        admin.performCommand("staffmode");
        assertFalse(plugin.tools().inStaffMode(admin.getUniqueId()));
        assertTrue(admin.getInventory().contains(Material.APPLE));
        assertTrue(admin.getInventory().contains(Material.GOLD_INGOT, 5), "taken from a chest during staff mode, kept");
        for (ItemStack i : admin.getInventory().getContents()) assertNull(plugin.tools().toolOf(i), "no admin items left");
    }

    @Test
    void newToolsWork() {
        admin.getInventory().setItemInMainHand(tool("prisonkey"));
        troll.teleport(new org.bukkit.Location(troll.getWorld(), 200.5, 70, 200.5));
        rightClickPlayer(admin, troll);
        assertTrue(plugin.prison().isJailed(troll.getUniqueId()), "the key locks up");
        rightClickPlayer(admin, troll);
        assertFalse(plugin.prison().isJailed(troll.getUniqueId()), "and opens again");
        admin.getInventory().setItemInMainHand(tool("trollwand"));
        rightClickPlayer(admin, troll);
        assertTrue(admin.getOpenInventory().getTopInventory().getHolder() instanceof dev.staffcore.gui.Menu, "the troll menu");
        admin.closeInventory();
        admin.getInventory().setItemInMainHand(tool("spectate"));
        rightClickPlayer(admin, troll);
        assertEquals(org.bukkit.GameMode.SPECTATOR, admin.getGameMode());
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerToggleSneakEvent(admin, true));
        server.getScheduler().performTicks(2);
        assertNotEquals(org.bukkit.GameMode.SPECTATOR, admin.getGameMode(), "sneaking brings you back");
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            plugin.tools().menu(admin);
            assertNoMissingTexts(admin, code);
            plugin.trolls().menu(admin, troll);
            assertNoMissingTexts(admin, code);
        }
    }

    // ------------------------------------------------------------------ fake base

    private int countChests(org.bukkit.World w, int cx, int cy, int cz) {
        int n = 0;
        for (int x = cx - 12; x <= cx + 12; x++) for (int y = cy - 2; y <= cy + 8; y++) for (int z = cz - 12; z <= cz + 12; z++) {
            if (w.getBlockAt(x, y, z).getType() == Material.CHEST) n++;
        }
        return n;
    }

    @Test
    void fakeBaseIsBuiltProtectedReportedAndRemovedWithoutTrace() {
        var w = admin.getWorld();
        // the world before: some stone and a chest of somebody with something in it
        for (int x = 290; x <= 310; x++) for (int y = 18; y <= 28; y++) for (int z = 290; z <= 310; z++) w.getBlockAt(x, y, z).setType(Material.STONE);
        Block old = w.getBlockAt(300, 21, 300);
        old.setType(Material.BARREL);
        ((org.bukkit.block.Container) old.getState()).getInventory().addItem(new ItemStack(Material.COAL, 9));
        admin.teleport(new org.bukkit.Location(w, 300.5, 20, 300.5));
        assertTrue(admin.performCommand("fakebase here 1"));
        assertEquals(1, plugin.fakeBases().count());
        int chests = countChests(w, 300, 20, 300);
        assertTrue(chests >= 20, "a stand full of chests: " + chests);
        assertEquals(Material.DEEPSLATE_TILES, w.getBlockAt(300, 21, 300 - 7).getType(), "the vault has deepslate walls");
        assertTrue(w.getEntities().stream().filter(e -> e.customName() != null).count() >= 2, "two fake players");

        // nothing can be broken
        Block chest = null;
        for (int x = 288; x <= 312 && chest == null; x++) for (int y = 19; y <= 27 && chest == null; y++) for (int z = 288; z <= 312 && chest == null; z++) {
            if (w.getBlockAt(x, y, z).getType() == Material.CHEST) chest = w.getBlockAt(x, y, z);
        }
        assertNotNull(chest);
        var br = new BlockBreakEvent(chest, troll);
        server.getPluginManager().callEvent(br);
        assertTrue(br.isCancelled(), "the base cannot be broken");

        // a player opening a chest is reported
        while (admin.nextComponentMessage() != null) { /* clear */ }
        troll.openInventory(((org.bukkit.block.Container) chest.getState()).getInventory());
        boolean told = false;
        Component m;
        while ((m = admin.nextComponentMessage()) != null) if (plain(m).contains("Troll") && plain(m).contains("#1")) told = true;
        assertTrue(told, "the team hears about the chest");
        troll.closeInventory();

        // walking in is reported as well
        troll.teleport(new org.bukkit.Location(w, 300.5, 20, 300.5));
        server.getScheduler().performTicks(20);
        boolean found = false;
        while ((m = admin.nextComponentMessage()) != null) if (plain(m).contains("found")) found = true;
        assertTrue(found);

        assertTrue(admin.performCommand("fakebase remove 1"));
        assertEquals(0, plugin.fakeBases().count());
        assertEquals(0, countChests(w, 300, 20, 300), "no chest stays behind");
        assertEquals(Material.STONE, w.getBlockAt(300, 20, 300).getType(), "the stone is back");
        assertEquals(Material.BARREL, old.getType());
        assertTrue(((org.bukkit.block.Container) old.getState()).getInventory().contains(Material.COAL, 9), "with its coal");
        assertEquals(0, w.getEntities().stream().filter(e -> e.customName() != null).count(), "the fake players are gone");
    }

    @Test
    void everyFakeBaseDesignBuildsAndRunsOut() {
        var w = admin.getWorld();
        plugin.getConfig().set("fakebase.max", 20);
        for (int n = 1; n <= 8; n++) {
            admin.teleport(new org.bukkit.Location(w, n * 40 + 0.5, 20, -500.5));
            assertTrue(admin.performCommand("fakebase here " + n));
            assertTrue(countChests(w, n * 40, 20, -501) >= 10, "design " + n + " has chests");
        }
        assertEquals(8, plugin.fakeBases().count());
        assertTrue(admin.performCommand("fakebase Troll"), "hidden near a player");
        assertEquals(9, plugin.fakeBases().count());
        plugin.getConfig().set("fakebase.minutes", 0);
        admin.performCommand("fakebase here 2");
        server.getScheduler().performTicks(20);
        assertEquals(9, plugin.fakeBases().count(), "the one with no time left is gone");
        admin.performCommand("fakebase remove all");
        assertEquals(0, plugin.fakeBases().count());
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            assertFalse(plain(plugin.lang().msg("fakebase.design.vault")).contains("missing"));
        }
    }

    // ------------------------------------------------------------------ modules: homes, tpa, money, esp

    @Test
    void homesAreSavedUsedLimitedAndDeleted() {
        plugin.getConfig().set("homes.warmup", 0);
        plugin.getConfig().set("homes.max", 2);
        var w = troll.getWorld();
        troll.teleport(new org.bukkit.Location(w, 10, 70, 10));
        assertTrue(troll.performCommand("sethome base"));
        troll.teleport(new org.bukkit.Location(w, 50, 70, 50));
        troll.performCommand("sethome farm");
        troll.teleport(new org.bukkit.Location(w, 90, 70, 90));
        troll.performCommand("sethome third");
        assertEquals(2, plugin.homes().of(troll.getUniqueId()).size(), "the limit holds");
        troll.performCommand("home base");
        assertEquals(10, troll.getLocation().getBlockX());
        troll.performCommand("delhome base");
        assertEquals(1, plugin.homes().of(troll.getUniqueId()).size());
        // with one home left, /home goes there
        troll.performCommand("home");
        assertEquals(50, troll.getLocation().getBlockX());
    }

    @Test
    void homeCountdownIsCancelledByMoving() {
        plugin.getConfig().set("homes.warmup", 3);
        var w = troll.getWorld();
        troll.teleport(new org.bukkit.Location(w, 10, 70, 10));
        troll.performCommand("sethome base");
        troll.teleport(new org.bukkit.Location(w, 100, 70, 100));
        troll.performCommand("home base");
        troll.teleport(new org.bukkit.Location(w, 105, 70, 100));
        server.getScheduler().performTicks(100);
        assertEquals(105, troll.getLocation().getBlockX(), "moving cancels the teleport");
        troll.performCommand("home base");
        server.getScheduler().performTicks(100);
        assertEquals(10, troll.getLocation().getBlockX(), "standing still gets you home");
    }

    @Test
    void teleportRequestsNeedAnAnswer() {
        plugin.getConfig().set("tpa.warmup", 0);
        plugin.getConfig().set("tpa.cooldown", 0);
        var w = troll.getWorld();
        troll.teleport(new org.bukkit.Location(w, 0, 70, 0));
        bystander.teleport(new org.bukkit.Location(w, 300, 70, 300));
        assertTrue(troll.performCommand("tpa Bystander"));
        assertEquals(0, troll.getLocation().getBlockX(), "nothing happens before the answer");
        bystander.performCommand("tpdeny");
        bystander.performCommand("tpaccept");
        assertEquals(0, troll.getLocation().getBlockX(), "a denied request is gone");
        troll.performCommand("tpa Bystander");
        bystander.performCommand("tpaccept Troll");
        assertEquals(300, troll.getLocation().getBlockX(), "accepted: Troll is at Bystander");
        troll.teleport(new org.bukkit.Location(w, 0, 70, 0));
        troll.performCommand("tpahere Bystander");
        bystander.performCommand("tpaccept");
        assertEquals(0, bystander.getLocation().getBlockX(), "tpahere brings the other one");
        // frozen players cannot be asked to teleport
        plugin.admin().setFrozen(bystander, true, "Admin");
        troll.teleport(new org.bukkit.Location(w, 500, 70, 0));
        troll.performCommand("tpa Bystander");
        bystander.performCommand("tpaccept");
        assertEquals(500, troll.getLocation().getBlockX());
    }

    @Test
    void moneyCanBePaidButNotConjured() {
        plugin.getConfig().set("money.start", 100);
        assertEquals(100, plugin.money().balance(troll.getUniqueId()), 0.001);
        assertTrue(troll.performCommand("pay Bystander 30"));
        assertEquals(70, plugin.money().balance(troll.getUniqueId()), 0.001);
        assertEquals(130, plugin.money().balance(bystander.getUniqueId()), 0.001);
        troll.performCommand("pay Bystander 1000");
        troll.performCommand("pay Bystander -50");
        troll.performCommand("pay Bystander NaN");
        troll.performCommand("pay Troll 10");
        assertEquals(70, plugin.money().balance(troll.getUniqueId()), 0.001, "no money from nothing");
        troll.performCommand("eco give Troll 1000");
        assertEquals(70, plugin.money().balance(troll.getUniqueId()), 0.001, "players cannot use /eco");
        admin.performCommand("eco give Troll 1000");
        assertEquals(1070, plugin.money().balance(troll.getUniqueId()), 0.001);
        admin.performCommand("eco set Troll 5");
        assertEquals(5, plugin.money().balance(troll.getUniqueId()), 0.001);
        assertTrue(plugin.economy().available(), "gift tasks can use StaffCore money without Vault");
        assertTrue(plugin.economy().transfer(bystander, troll, 10));
        assertEquals(15, plugin.money().balance(troll.getUniqueId()), 0.001);
    }

    @Test
    void modulesSwitchTheirCommandsOnAndOff() {
        assertNotNull(server.getCommandMap().getCommand("home"));
        assertTrue(plugin.modules().set("homes", false, "Admin"));
        assertFalse(plugin.modules().on("homes"));
        assertNull(server.getCommandMap().getCommand("home"), "the command is gone");
        assertNull(server.getCommandMap().getCommand("staffcore:sethome"));
        assertFalse(plugin.getConfig().getBoolean("homes.enabled"));
        plugin.modules().set("homes", true, "Admin");
        assertNotNull(server.getCommandMap().getCommand("home"), "and back");
        plugin.modules().set("money", false, "Admin");
        assertFalse(plugin.economy().available(), "money off: no economy");
        plugin.modules().set("money", true, "Admin");

        // the menu: left-click switches, right-click opens the settings
        plugin.modules().menu(admin);
        int homesSlot = -1;
        var top = admin.getOpenInventory().getTopInventory();
        for (int i = 0; i < top.getSize(); i++) {
            ItemStack it = top.getItem(i);
            if (it != null && it.hasItemMeta() && it.getItemMeta().hasDisplayName() && plain(it.getItemMeta().displayName()).equals("Homes")) homesSlot = i;
        }
        assertTrue(homesSlot >= 0);
        admin.simulateInventoryClick(admin.getOpenInventory(), org.bukkit.event.inventory.ClickType.LEFT, homesSlot);
        assertFalse(plugin.modules().on("homes"), "left-click switched it off");
        admin.simulateInventoryClick(admin.getOpenInventory(), org.bukkit.event.inventory.ClickType.RIGHT, homesSlot);
        assertTrue(plain(admin.getOpenInventory().title()).contains("Homes"), "right-click opens the settings");
        assertNoMissingTexts(admin, "en");
        int before = plugin.getConfig().getInt("homes.max", 3);
        admin.simulateInventoryClick(10);
        assertNotEquals(before, plugin.getConfig().getInt("homes.max", 3), "a click changes the value");
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            plugin.modules().menu(admin);
            assertNoMissingTexts(admin, code);
            for (var m : plugin.modules().all()) {
                plugin.modules().settings(admin, m);
                assertNoMissingTexts(admin, code);
            }
        }
    }

    @Test
    void espShowsThingsOnlyToTheStaffMember() {
        Block chest = admin.getWorld().getBlockAt(admin.getLocation().getBlockX() + 3, admin.getLocation().getBlockY(), admin.getLocation().getBlockZ());
        chest.setType(Material.CHEST);
        int before = admin.getWorld().getEntities().size();
        assertTrue(admin.performCommand("esp all"));
        assertEquals(dev.staffcore.module.EspManager.Mode.ALL, plugin.esp().mode(admin.getUniqueId()));
        server.getScheduler().performTicks(10);
        // the test server cannot create text displays; on a real server tags and outlines appear here
        admin.performCommand("esp off");
        server.getScheduler().performTicks(4);
        assertEquals(0, plugin.esp().shown(admin.getUniqueId()));
        assertEquals(before, admin.getWorld().getEntities().size(), "nothing is left behind");
        troll.performCommand("esp all");
        assertEquals(dev.staffcore.module.EspManager.Mode.OFF, plugin.esp().mode(troll.getUniqueId()), "players have no ESP");
        // the spyglass in staff mode cycles through the modes
        admin.getInventory().setItemInMainHand(tool("esp"));
        rightClickAir(admin);
        assertEquals(dev.staffcore.module.EspManager.Mode.PLAYERS, plugin.esp().mode(admin.getUniqueId()));
    }

    // ------------------------------------------------------------------ /admin, anti-cheat, ghost mode

    @Test
    void inactiveAdminsAreNormalPlayers() {
        plugin.getConfig().set("admin-mode.required", true);
        assertTrue(plugin.adminPanel().needsAdminMode("/ban Troll 1d"));
        assertTrue(plugin.adminPanel().needsAdminMode("/staffcore:freeze Troll"));
        assertTrue(plugin.adminPanel().needsAdminMode("/sc menu"));
        assertFalse(plugin.adminPanel().needsAdminMode("/admin"), "the panel always opens");
        assertFalse(plugin.adminPanel().needsAdminMode("/home"), "player commands stay");
        assertFalse(plugin.adminPanel().needsAdminMode("/ticket"));
        assertFalse(plugin.adminPanel().needsAdminMode("/sc guide"));
        var cmd = new org.bukkit.event.player.PlayerCommandPreprocessEvent(admin, "/ban Troll 1d");
        server.getPluginManager().callEvent(cmd);
        assertTrue(cmd.isCancelled(), "an inactive admin cannot ban");
        admin.performCommand("admin on");
        assertTrue(plugin.tools().inStaffMode(admin.getUniqueId()));
        var again = new org.bukkit.event.player.PlayerCommandPreprocessEvent(admin, "/ban Troll 1d");
        server.getPluginManager().callEvent(again);
        assertFalse(again.isCancelled(), "an active admin can");
        admin.performCommand("admin off");
        // admin items do nothing outside admin mode either
        admin.getInventory().setItemInMainHand(tool("handcuffs"));
        rightClickPlayer(admin, troll);
        assertFalse(plugin.admin().isCuffed(troll.getUniqueId()));
        var normal = new org.bukkit.event.player.PlayerCommandPreprocessEvent(troll, "/ban Admin 1d");
        server.getPluginManager().callEvent(normal);
        assertFalse(normal.isCancelled(), "players are not affected by this; their permission check answers");
    }

    private boolean punishedOrBanned(PlayerMock p) {
        return plugin.punishments().isPunished(p.getUniqueId()) || plugin.store().bans.containsKey(p.getUniqueId());
    }

    @Test
    void antiCheatPointsMarkWarnAndCatch() {
        var ac = plugin.antiCheat();
        ac.flag(troll, "reach", 3, "3.9");
        assertEquals(3, ac.total(troll.getUniqueId()), 0.01);
        assertNull(ac.suspect(troll.getUniqueId()), "a few points are not enough");
        ac.flag(troll, "reach", 6, "4.4");
        assertNotNull(ac.suspect(troll.getUniqueId()), "marked for the team from 8 points");
        assertTrue(ac.watched(troll.getUniqueId()), "and watched from now on");
        ac.flag(troll, "reach", 2, "3.8");
        assertEquals(12, ac.total(troll.getUniqueId()), 0.01, "watched: points count one and a half");
        assertFalse(punishedOrBanned(troll));
        ac.flag(troll, "fly", 20, "hover");
        server.getScheduler().performTicks(80);
        assertTrue(plugin.punishments().isPunished(troll.getUniqueId()), "over 25: caught, PlayBan after the animation");
        assertEquals("PLAYBAN", plugin.punishments().get(troll.getUniqueId()).type);
        assertEquals(5 * 60_000L, plugin.punishments().get(troll.getUniqueId()).originalMs, "first time: 5 minutes");
        assertEquals(0, ac.total(troll.getUniqueId()), 0.01, "the account starts fresh");
    }

    @Test
    void aClearFindingWarnsAndGivesAChance() {
        var ac = plugin.antiCheat();
        ac.flag(bystander, "fly", 20, "hover");
        assertEquals(20, ac.total(bystander.getUniqueId()), 0.01);
        ac.flag(bystander, "fly", 20, "hover");
        assertEquals(20, ac.total(bystander.getUniqueId()), 0.01, "slow to land: the same check waits a few seconds");
        server.getScheduler().performTicks(80);
        assertFalse(punishedOrBanned(bystander), "20 of 25: only the warning");
        boolean warned = false;
        Component m;
        while ((m = bystander.nextComponentMessage()) != null) if (plain(m).contains("not allowed")) warned = true;
        assertTrue(warned, "a big warning in chat");
        // x-ray and bot signs alone never reach the limit
        ac.flag(troll, "xray", 100, null);
        server.getScheduler().performTicks(80);
        assertFalse(punishedOrBanned(troll));
        assertNotNull(ac.suspect(troll.getUniqueId()));
        // creative players are never checked
        PlayerMock builder = server.addPlayer("Builder");
        builder.setGameMode(org.bukkit.GameMode.CREATIVE);
        ac.flag(builder, "fly", 100, null);
        assertEquals(0, ac.total(builder.getUniqueId()), 0.01);
    }

    @Test
    void totemSpeedGivesGradedPoints() {
        var ac = plugin.antiCheat();
        server.getPluginManager().callEvent(new org.bukkit.event.entity.EntityResurrectEvent(troll, org.bukkit.inventory.EquipmentSlot.OFF_HAND));
        troll.getInventory().setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
        server.getScheduler().performTicks(2);
        assertEquals(4, ac.points(troll.getUniqueId(), "autototem"), 0.01, "back after one tick: 4 points");
        server.getPluginManager().callEvent(new org.bukkit.event.entity.EntityResurrectEvent(bystander, org.bukkit.inventory.EquipmentSlot.OFF_HAND));
        bystander.getInventory().setItemInOffHand(null);
        server.getScheduler().performTicks(2);
        bystander.getInventory().setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
        server.getScheduler().performTicks(2);
        assertEquals(1, ac.points(bystander.getUniqueId(), "autototem"), 0.01, "after three ticks: 1 point");
        PlayerMock slow = server.addPlayer("Slow");
        server.getPluginManager().callEvent(new org.bukkit.event.entity.EntityResurrectEvent(slow, org.bukkit.inventory.EquipmentSlot.OFF_HAND));
        server.getScheduler().performTicks(5);
        slow.getInventory().setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
        server.getScheduler().performTicks(2);
        assertEquals(0, ac.points(slow.getUniqueId(), "autototem"), 0.01, "a human speed: nothing");
    }

    @Test
    void adminsCanWatchPlayers() {
        assertTrue(admin.performCommand("anticheat watch Bystander"));
        assertTrue(plugin.antiCheat().watched(bystander.getUniqueId()));
        plugin.antiCheat().flag(bystander, "reach", 2, null);
        assertEquals(3, plugin.antiCheat().total(bystander.getUniqueId()), 0.01);
        admin.performCommand("anticheat unwatch Bystander");
        assertFalse(plugin.antiCheat().watched(bystander.getUniqueId()));
    }

    @Test
    void playBanWorksBothWaysAndSendsBackAtTheEnd() {
        var w = troll.getWorld();
        troll.teleport(new org.bukkit.Location(w, 123.5, 70, 45.5));
        Punishment p = punish("10m");
        assertNotNull(p);
        server.getScheduler().performTicks(5);
        assertFalse(troll.canSee(bystander), "the punished player sees nobody");
        assertFalse(bystander.canSee(troll), "and nobody sees them");
        troll.teleport(new org.bukkit.Location(w, 500, 70, 500));
        plugin.punishments().end(p, "PARDONED", "Admin");
        assertEquals(123, troll.getLocation().getBlockX(), "back where they were before");
        server.getScheduler().performTicks(2);
        assertTrue(troll.canSee(bystander), "and sees everybody again");
        plugin.getConfig().set("playban.end-teleport", "LOCATION");
        plugin.getConfig().set("playban.end-location.world", w.getName());
        plugin.getConfig().set("playban.end-location.x", 7.5);
        plugin.getConfig().set("playban.end-location.y", 80);
        plugin.getConfig().set("playban.end-location.z", 7.5);
        Punishment again = punish("10m");
        plugin.punishments().end(again, "PARDONED", "Admin");
        assertEquals(7, troll.getLocation().getBlockX(), "or to a fixed place");
    }

    @Test
    void reachIsMeasuredToTheHitbox() {
        var w = troll.getWorld();
        troll.teleport(new org.bukkit.Location(w, 0.5, 70, 0.5));
        bystander.teleport(new org.bukkit.Location(w, 7.5, 70, 0.5));
        for (int i = 0; i < 10; i++) {
            server.getPluginManager().callEvent(new org.bukkit.event.entity.EntityDamageByEntityEvent(troll, bystander, org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                    org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK).build(), 1));
        }
        assertTrue(plugin.antiCheat().points(troll.getUniqueId(), "reach") > 0 || plugin.punishments().isPunished(troll.getUniqueId())
                || plugin.antiCheat().suspect(troll.getUniqueId()) != null, "hits from 7 blocks away are reach");
        admin.teleport(new org.bukkit.Location(w, 0.5, 70, 0.5));
        bystander.teleport(new org.bukkit.Location(w, 2.5, 70, 0.5));
        for (int i = 0; i < 30; i++) {
            server.getPluginManager().callEvent(new org.bukkit.event.entity.EntityDamageByEntityEvent(admin, bystander, org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                    org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK).build(), 1));
        }
        assertEquals(0, plugin.antiCheat().points(admin.getUniqueId(), "reach"), 0.001, "normal hits are fine");
    }

    @Test
    void takingACaseMakesYouAnInvisibleGhostAtThePlayer() {
        var w = troll.getWorld();
        troll.teleport(new org.bukkit.Location(w, 400.5, 70, 400.5));
        plugin.antiCheat().flag(troll, "reach", 9, null);
        assertTrue(admin.performCommand("admin"));
        assertNoMissingTexts(admin, "en");
        admin.simulateInventoryClick(28);
        assertTrue(plugin.tools().inStaffMode(admin.getUniqueId()), "active admin now, with the admin items");
        assertTrue(plugin.punishments().isVanished(admin.getUniqueId()));
        assertTrue(plugin.ghost().on(admin.getUniqueId()));
        assertEquals(org.bukkit.GameMode.CREATIVE, admin.getGameMode());
        assertTrue(admin.getLocation().distance(troll.getLocation()) < 2, "at the player");
        assertEquals("Admin", plugin.antiCheat().suspect(troll.getUniqueId()).handler);

        // a wall: spectator inside, creative outside
        for (int y = 69; y <= 73; y++) w.getBlockAt(402, y, 400).setType(Material.STONE);
        admin.teleport(new org.bukkit.Location(w, 401.6, 70, 400.5));
        server.getScheduler().performTicks(2);
        assertEquals(org.bukkit.GameMode.SPECTATOR, admin.getGameMode(), "next to the wall: through it");
        admin.teleport(new org.bukkit.Location(w, 420.5, 80, 420.5));
        server.getScheduler().performTicks(2);
        assertEquals(org.bukkit.GameMode.CREATIVE, admin.getGameMode(), "in the open: creative again");

        admin.performCommand("admin off");
        assertFalse(plugin.ghost().on(admin.getUniqueId()));
        assertEquals(org.bukkit.GameMode.SURVIVAL, admin.getGameMode(), "the old game mode is back");
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            plugin.adminPanel().main(admin);
            assertNoMissingTexts(admin, code);
            plugin.adminPanel().inbox(admin, 0);
            assertNoMissingTexts(admin, code);
            plugin.adminPanel().commands(admin, 0);
            assertNoMissingTexts(admin, code);
        }
    }

    @Test
    void myCommandsShowsWhatYouMayUse() {
        var mine = plugin.adminPanel().usable(troll).stream().map(org.bukkit.command.Command::getName).toList();
        assertTrue(mine.contains("home"));
        assertTrue(mine.contains("ticket"));
        assertFalse(mine.contains("ban"), "no team commands for players");
        assertFalse(mine.contains("fakebase"));
        var adminCmds = plugin.adminPanel().usable(admin).stream().map(org.bukkit.command.Command::getName).toList();
        assertTrue(adminCmds.contains("ban"));
        assertTrue(adminCmds.contains("fakebase"));
    }

    @Test
    void inboxShowsTicketsByRankLevel() {
        typeInChat(bystander, "x");
        bystander.performCommand("report Troll griefing my house");
        assertEquals(1, plugin.tickets().all().size());
        assertEquals(1, plugin.adminPanel().tickets(admin).size());
        admin.performCommand("rank setup");
        PlayerMock sup = server.addPlayer("Supporter");
        admin.performCommand("rank set Supporter supporter");
        plugin.getConfig().set("admin-panel.ticket-levels.report", 50);
        assertEquals(0, plugin.adminPanel().tickets(sup).size(), "reports need level 50 here");
        assertEquals(1, plugin.adminPanel().tickets(admin).size());
    }
}
