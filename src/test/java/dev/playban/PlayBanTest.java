package dev.playban;

import dev.playban.gui.Menu;
import dev.playban.model.Punishment;
import dev.playban.model.Review;
import dev.playban.model.TaskDef;
import dev.playban.model.TaskStatus;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlayBanTest {
    private ServerMock server;
    private PlayBanPlugin plugin;
    private PlayerMock admin;
    private PlayerMock troll;
    private PlayerMock bystander;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PlayBanPlugin.class);
        admin = server.addPlayer("Admin");
        admin.setOp(true);
        troll = server.addPlayer("Troll");
        bystander = server.addPlayer("Bystander");
        server.getScheduler().performTicks(30);
        plugin.getConfig().set("ticket-cooldown-seconds", 0);
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
        assertTrue(troll.canSee(bystander), "the punished player still sees others");

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
        var listener = new dev.playban.listener.ConnectionListener(plugin);
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
        admin.simulateInventoryClick(28); // first step: WARN -> PLAYBAN
        assertEquals("PLAYBAN", created.steps.get(0).type);
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
        var listener = new dev.playban.listener.ConnectionListener(plugin);
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
        cat.steps.add(new dev.playban.model.Category.Step("SPECBAN", 3_600_000L));
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

    private dev.playban.model.Ticket openTicket(PlayerMock author, String reason, String text) {
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
        assertEquals(dev.playban.model.Ticket.Status.OPEN, t.status);

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
        assertEquals(dev.playban.model.Ticket.Status.IN_PROGRESS, t.status);
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
        assertEquals(dev.playban.model.Ticket.Status.IN_PROGRESS, t.status, "cannot close before everyone has a decision");

        plugin.ticketMenus().verdicts(admin, t);
        assertEquals(Material.BARRIER, admin.getOpenInventory().getTopInventory().getItem(30).getType());
        admin.simulateInventoryClick(10); // Bystander
        admin.simulateInventoryClick(51); // only involved
        admin.simulateInventoryClick(11); // Troll
        admin.simulateInventoryClick(slotOf(admin, Material.TNT)); // griefing
        admin.simulateInventoryClick(slotOf(admin, Material.LIME_CONCRETE)); // close now

        assertEquals(dev.playban.model.Ticket.Status.CLOSED, t.status);
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
        assertEquals(dev.playban.model.Ticket.Status.CLOSED, loaded.status);
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
        assertEquals(dev.playban.model.Ticket.Status.IN_PROGRESS, t.status, "the ticket itself stays open");
    }

    @Test
    void declinedTicketAndStatistics() {
        var a = openTicket(bystander, "question", "one");
        var b = openTicket(troll, "question", "two");
        plugin.tickets().decline(a, admin);
        assertEquals(dev.playban.model.Ticket.Status.DECLINED, a.status);
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
    private final class FakeVoice implements dev.playban.voice.VoiceBackend {
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
        assertEquals("playban.staff", server.getCommandMap().getCommand("ban").getPermission());
        assertEquals("playban.vanish", server.getCommandMap().getCommand("vanish").getPermission());
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
        mod.addAttachment(plugin, "playban.staff", true);
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
        helper.addAttachment(plugin, "playban.vanish", true);
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

    @Test
    void timeParsing() {
        assertEquals(90_000_000L + 1_800_000L, TimeUtil.parse("1d 1h 30m"));
        assertEquals(1_800_000L, TimeUtil.parse("30"));
        assertEquals(-1, TimeUtil.parse("soon"));
        assertEquals(-1, TimeUtil.parse("1h banana"));
        assertEquals("1d 1h 30m", TimeUtil.format(91_800_000L));
    }
}
