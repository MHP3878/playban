package dev.modcheck;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModCheckTest {
    private ServerMock server;
    private ModCheckPlugin plugin;
    private PlayerMock admin;
    private PlayerMock steve;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(ModCheckPlugin.class);
        admin = server.addPlayer("Admin");
        admin.setOp(true);
        steve = server.addPlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private void assertNoMissingTexts(String where) {
        var top = admin.getOpenInventory().getTopInventory();
        assertFalse(plain(admin.getOpenInventory().title()).contains("missing text"), where + " title");
        for (ItemStack it : top.getContents()) {
            if (it == null || !it.hasItemMeta()) continue;
            if (it.getItemMeta().hasDisplayName()) assertFalse(plain(it.getItemMeta().displayName()).contains("missing text"), where + ": " + plain(it.getItemMeta().displayName()));
            if (it.getItemMeta().hasLore()) for (Component l : it.getItemMeta().lore()) {
                assertFalse(plain(l).contains("missing text"), where + ": " + plain(l));
                if (!plain(l).contains("tempban")) assertFalse(plain(l).matches(".*\\{(player|count|value|action|reason|client)}.*"), where + ": unfilled " + plain(l));
            }
        }
    }

    @Test
    void namesAreCleanedUpAndTyposAsked() {
        assertEquals("beelocator", KnownMods.normalize("beelocator-21.11.jar"));
        assertEquals("beelocator", KnownMods.normalize("Bee Locator"));
        admin.performCommand("modblock beelocater");
        assertNull(plugin.getConfig().getConfigurationSection("rules.beelocater"), "a typo asks first");
        assertTrue(admin.performCommand("modblock beelocator-21.11.jar"));
        assertNotNull(plugin.getConfig().getConfigurationSection("rules.beelocator"));
        assertEquals("DEFAULT", plugin.getConfig().getString("rules.beelocator.action"), "what happens comes from the settings");
        assertTrue(plain(admin.getOpenInventory().title()).contains("Bee Locator"), "straight to the mod's settings");
        assertNoMissingTexts("rule settings");
    }

    @Test
    void reasonsGetTheModName() {
        assertEquals("No cheating with Freecam!", Guard.fill("No cheating with {mods}!", "Freecam"));
        assertEquals("Kick wegen Freecam", Guard.fill("Kick wegen (Mod)", "Freecam"));
        assertEquals("Mod: Freecam", Guard.fill("Mod: [mod]", "Freecam"));
    }

    @Test
    void warnKicksFirstThenBans() {
        plugin.guard().info(steve.getUniqueId()).channels.add("freecam:main");
        plugin.guard().judge(steve);
        assertFalse(steve.isOnline(), "first: kicked with a warning");
        assertNull(plugin.guard().ban(steve.getUniqueId()));
        PlayerMock again = server.addPlayer("Steve2");
        plugin.guard().info(again.getUniqueId()).channels.add("freecam:main");
        plugin.guard().judge(again);
        plugin.guard().judge(again);
        assertNotNull(plugin.guard().ban(again.getUniqueId()), "next time: banned");
        assertTrue(plugin.guard().ban(again.getUniqueId()).reason().contains("Freecam"), "the reason names the mod");
        var login = new AsyncPlayerPreLoginEvent("Steve2", InetAddress.getLoopbackAddress(), again.getUniqueId());
        Thread t = new Thread(() -> server.getPluginManager().callEvent(login));
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, login.getLoginResult(), "cannot join while banned");
        assertTrue(admin.performCommand("modcheck unban Steve2"));
        assertNull(plugin.guard().ban(again.getUniqueId()));
    }

    @Test
    void eachModHasItsOwnSettings() {
        plugin.getConfig().set("rules.freecam.action", "KICK");
        plugin.getConfig().set("rules.freecam.reason", "Freecam is not allowed: {mods}");
        plugin.guard().info(steve.getUniqueId()).channels.add("freecam:main");
        plugin.guard().judge(steve);
        assertFalse(steve.isOnline());
        assertNull(plugin.guard().ban(steve.getUniqueId()), "only a kick");
    }

    @Test
    void ownCommandRunsWithTheModAndReason() {
        List<String> ran = new ArrayList<>();
        server.getCommandMap().register("test", new Command("record") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                ran.add(String.join(" ", args));
                return true;
            }
        });
        plugin.getConfig().set("rules.meteor.action", "COMMAND");
        plugin.getConfig().set("rules.meteor.command", "record {player} 3d {reason}");
        plugin.guard().info(steve.getUniqueId()).brand = "Meteor Client";
        plugin.guard().judge(steve);
        assertEquals(1, ran.size());
        assertEquals("Steve 3d Forbidden mod: Meteor Client", ran.get(0));
    }

    @Test
    void moderatorsPunishOrLetPassAsSet() {
        plugin.guard().info(steve.getUniqueId()).channels.add("xaerominimap:main");
        assertTrue(plugin.guard().mods(steve.getUniqueId()).contains("xaerominimap"), "a mod that is not blocked still shows");
        assertTrue(plugin.guard().hits(steve.getUniqueId()).isEmpty());
        admin.performCommand("modcheck Steve");
        assertTrue(plain(admin.getOpenInventory().title()).contains("Steve"));
        assertNoMissingTexts("player page");
        plugin.menus().modAction(admin, steve, "xaerominimap");
        assertNoMissingTexts("decide page");
        // let pass
        admin.simulateInventoryClick(15);
        assertTrue(plugin.guard().passed(steve.getUniqueId(), "xaerominimap"));
        // punish uses the defaults: warn = kick
        plugin.menus().modAction(admin, steve, "xaerominimap");
        admin.simulateInventoryClick(11);
        assertFalse(steve.isOnline(), "punished as the settings say");
    }

    @Test
    void passedModsAreNotJudged() {
        plugin.guard().pass(steve.getUniqueId(), "freecam", "Admin");
        plugin.guard().info(steve.getUniqueId()).channels.add("freecam:main");
        plugin.guard().judge(steve);
        assertTrue(steve.isOnline());
    }

    @Test
    void allowListKeepsOutOtherClients() {
        plugin.getConfig().set("mode", "ALLOWLIST");
        plugin.guard().info(steve.getUniqueId()).brand = "weirdclient";
        plugin.guard().judge(steve);
        assertFalse(steve.isOnline());
        PlayerMock ok = server.addPlayer("Fine");
        plugin.guard().info(ok.getUniqueId()).brand = "fabric";
        plugin.guard().judge(ok);
        assertTrue(ok.isOnline());
    }

    @Test
    void hiddenSignAnswerFindsAMod() {
        plugin.guard().expect(steve, List.of(new String[]{"key.freecam.toggle", "freecam"}, new String[]{"key.wurst.zoom", "wurst"}));
        plugin.guard().answer(steve, List.of(Component.text("Toggle Freecam"), Component.text("key.wurst.zoom"), Component.empty(), Component.empty()));
        assertTrue(plugin.guard().info(steve.getUniqueId()).found.contains("freecam"));
        assertFalse(steve.isOnline(), "and judged at once");
    }

    @Test
    void everyMenuHasItsTextsInBothLanguages() {
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            plugin.menus().main(admin);
            assertNoMissingTexts(code + " main");
            plugin.menus().settings(admin);
            assertNoMissingTexts(code + " settings");
            plugin.menus().blocked(admin, 0);
            assertNoMissingTexts(code + " blocked");
            plugin.menus().rule(admin, "meteor");
            assertNoMissingTexts(code + " rule");
            plugin.menus().suggestions(admin, 0);
            assertNoMissingTexts(code + " suggestions");
            plugin.menus().players(admin, 0);
            assertNoMissingTexts(code + " players");
            plugin.menus().bans(admin, 0);
            assertNoMissingTexts(code + " bans");
        }
    }

    @Test
    void settingsChangeWithAClick() {
        plugin.menus().settings(admin);
        admin.simulateInventoryClick(10);
        assertEquals("BAN", plugin.getConfig().getString("default-action"), "WARN -> BAN");
        plugin.menus().rule(admin, "meteor");
        admin.simulateInventoryClick(19);
        assertEquals("ALERT", plugin.getConfig().getString("rules.meteor.action"), "DEFAULT -> ALERT");
        admin.simulateInventoryClick(21);
        assertEquals("10m", plugin.getConfig().getString("rules.meteor.ban-duration"));
        assertTrue(admin.performCommand("modunblock meteor"));
        assertNull(plugin.getConfig().getConfigurationSection("rules.meteor"));
    }

    @Test
    void comingBackTooOftenGetsTheRepeatPunishment() {
        plugin.getConfig().set("repeat.window", "1d");
        plugin.getConfig().set("repeat.times", 2);
        plugin.getConfig().set("repeat.action", "BAN 30d");
        plugin.getConfig().set("rules.freecam.action", "KICK");
        plugin.guard().info(steve.getUniqueId()).channels.add("freecam:main");
        plugin.guard().judge(steve);
        assertFalse(steve.isOnline(), "first time: the normal action, a kick");
        assertNull(plugin.guard().ban(steve.getUniqueId()));
        assertEquals(1, plugin.guard().recentOffenses(steve.getUniqueId()));
        // back again the same day: the same account
        PlayerMock back = new PlayerMock(server, "Steve", steve.getUniqueId());
        server.addPlayer(back);
        plugin.guard().info(back.getUniqueId()).channels.add("freecam:main");
        plugin.guard().judge(back);
        var ban = plugin.guard().ban(back.getUniqueId());
        assertNotNull(ban, "second time within a day: banned");
        long days = (ban.until() - System.currentTimeMillis()) / 86_400_000L;
        assertTrue(days >= 29 && days <= 30, "for 30 days");
        assertTrue(ban.reason().contains("Freecam"));
    }

    @Test
    void repeatRuleIsOneSentenceWithThreeClicks() {
        plugin.getConfig().set("repeat.window", "off");
        plugin.menus().settings(admin);
        admin.simulateInventoryClick(37);
        assertEquals("10m", plugin.getConfig().getString("repeat.window"), "off -> 10 minutes");
        admin.simulateInventoryClick(38);
        assertEquals(3, plugin.getConfig().getInt("repeat.times"));
        admin.simulateInventoryClick(39);
        assertEquals("BAN 30d", plugin.getConfig().getString("repeat.action"), "BAN 7d -> BAN 30d");
        var rule = admin.getOpenInventory().getTopInventory().getItem(41);
        String text = String.join(" ", rule.getItemMeta().lore().stream().map(ModCheckTest::plain).toList());
        assertTrue(text.contains("3 times") && text.contains("10 minutes") && text.contains("30d"), text);
        // off again: nothing counts
        plugin.getConfig().set("repeat.window", "off");
        plugin.getConfig().set("rules.freecam.action", "KICK");
        for (int i = 0; i < 3; i++) {
            PlayerMock p = server.addPlayer("Again" + i);
            plugin.guard().info(p.getUniqueId()).channels.add("freecam:main");
            plugin.guard().judge(p);
            assertNull(plugin.guard().ban(p.getUniqueId()));
        }
    }
}
