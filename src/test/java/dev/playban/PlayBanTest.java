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
    void ticketReachesStaffAndAnswerComesBack() {
        punish("1h");
        troll.performCommand("ticket I think this is unfair");
        assertEquals(1, plugin.store().tickets.size());
        plugin.punishments().replyTicket(plugin.store().tickets.values().iterator().next(), "Admin", "We checked the logs.");
        assertTrue(plugin.store().tickets.isEmpty());

        for (int i = 0; i < 6; i++) troll.performCommand("ticket spam " + i);
        assertEquals(3, plugin.store().tickets.size(), "a player cannot flood the team with tickets");
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
        assertEquals(1, fresh.tickets.size());
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
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            Runnable[] menus = {
                    () -> plugin.adminMenus().main(admin),
                    () -> plugin.adminMenus().selectPlayer(admin, 0),
                    () -> plugin.adminMenus().active(admin, 0),
                    () -> plugin.adminMenus().detail(admin, troll.getUniqueId()),
                    () -> plugin.adminMenus().reviews(admin, 0),
                    () -> plugin.adminMenus().tickets(admin, 0),
                    () -> plugin.adminMenus().settings(admin),
                    () -> plugin.adminMenus().allowedCommands(admin, 0),
                    () -> plugin.adminMenus().tasks(admin, 0),
                    () -> plugin.adminMenus().history(admin, "Troll"),
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
    void timeParsing() {
        assertEquals(90_000_000L + 1_800_000L, TimeUtil.parse("1d 1h 30m"));
        assertEquals(1_800_000L, TimeUtil.parse("30"));
        assertEquals(-1, TimeUtil.parse("soon"));
        assertEquals(-1, TimeUtil.parse("1h banana"));
        assertEquals("1d 1h 30m", TimeUtil.format(91_800_000L));
    }
}
