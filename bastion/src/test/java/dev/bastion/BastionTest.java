package dev.bastion;

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
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.Material;

import static org.junit.jupiter.api.Assertions.*;

class BastionTest {
    private ServerMock server;
    private BastionPlugin plugin;
    private PlayerMock admin;
    private PlayerMock steve;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(BastionPlugin.class);
        // most tests are about the check itself; the check room has its own tests
        plugin.getConfig().set("check-room.enabled", false);
        // most ban tests look at Bastion's own list; the server's ban system has its own test
        plugin.getConfig().set("ban-system", "modcheck");
        // the tests add many new accounts at once; the bot guard of the anti-cheat has its own test
        plugin.getConfig().set("anticheat.checks.bot.enabled", false);
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

    /** What the server does when a player leaves (MockBukkit's kick does not). */
    private void leave(PlayerMock p) {
        plugin.guard().onQuit(new org.bukkit.event.player.PlayerQuitEvent(p, Component.empty(), org.bukkit.event.player.PlayerQuitEvent.QuitReason.KICKED));
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
        assertNull(plugin.guard().ban(again.getUniqueId()), "the same session is not judged twice for the same mod");
        leave(again);
        plugin.guard().info(again.getUniqueId()).channels.add("freecam:main");
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
        assertTrue(admin.performCommand("bastion unban Steve2"));
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
        assertEquals("Steve 3d You have Meteor Client on. Please delete it.", ran.get(0));
    }

    @Test
    void moderatorsPunishOrLetPassAsSet() {
        plugin.guard().info(steve.getUniqueId()).channels.add("xaerominimap:main");
        assertTrue(plugin.guard().mods(steve.getUniqueId()).contains("xaerominimap"), "a mod that is not blocked still shows");
        assertTrue(plugin.guard().hits(steve.getUniqueId()).isEmpty());
        admin.performCommand("bastion Steve");
        // the check runs first (sign may not answer in tests), then the page opens on its own
        server.getScheduler().performTicks(420);
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
        leave(steve);
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
        String text = String.join(" ", rule.getItemMeta().lore().stream().map(BastionTest::plain).toList());
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

    @Test
    void textFromClientsIsCleanedAndCannotBreakOutOfCommands() {
        assertEquals("evilclient", Guard.clean("evil\n\u00a7cclient\u0000", 48), "no line breaks, colour codes or zero bytes");
        assertEquals(10, Guard.clean("x".repeat(500), 10).length());
        assertFalse(Guard.clean("a\nb\rc\u0007", 48).chars().anyMatch(c -> c < 0x20));
        String cmd = Guard.forCommand("-s Forbidden mod; op Evil\n/stop");
        assertFalse(cmd.contains(";"));
        assertFalse(cmd.startsWith("-"));
        assertFalse(cmd.contains("\n"));
        assertEquals("{\\\"a\\\\b\\n\\u0001", Guard.json("{\"a\\b\n\u0001"));
    }

    @Test
    void onlyRealDiscordWebhooks() {
        assertTrue(Guard.isDiscordWebhook("https://discord.com/api/webhooks/123/abc"));
        assertTrue(Guard.isDiscordWebhook("https://ptb.discord.com/api/webhooks/123/abc"));
        assertFalse(Guard.isDiscordWebhook("http://discord.com/api/webhooks/123/abc"), "no plain http");
        assertFalse(Guard.isDiscordWebhook("https://discord.com.evil.net/api/webhooks/1/a"));
        assertFalse(Guard.isDiscordWebhook("https://evil.net/api/webhooks/1/a"));
        assertFalse(Guard.isDiscordWebhook("https://user@discord.com/api/webhooks/1/a"));
        assertFalse(Guard.isDiscordWebhook("https://discord.com/channels/1/2"));
        assertFalse(Guard.isDiscordWebhook("not a link"));
    }

    @Test
    void shortNamesDoNotHitEveryChannel() {
        assertTrue(Guard.channelMatches("xaerominimap:main", "xaerominimap"));
        assertTrue(Guard.channelMatches("freecam:main", "freecam"));
        assertFalse(Guard.channelMatches("xaerominimap:main", "map"), "a short name only counts exactly");
        assertFalse(Guard.channelMatches("fabric:anything", "ab"));
        assertTrue(Guard.channelMatches("advanced-xray:sync", "advanced-xray"));
        assertNull(plugin.guard().block("ab", "test"), "two letters are too short to block");
        plugin.guard().block("map", "test");
        plugin.guard().info(steve.getUniqueId()).channels.add("xaerominimap:main");
        plugin.guard().judge(steve);
        assertTrue(steve.isOnline(), "blocking 'map' does not kick a minimap player by accident");
    }

    @Test
    void moderatorsCannotSetConsoleCommandsOrSettings() {
        PlayerMock mod = server.addPlayer("Mod");
        mod.addAttachment(plugin, "bastion.block", true);
        mod.addAttachment(plugin, "bastion.check", true);
        plugin.menus().settings(mod);
        assertNotEquals(net.kyori.adventure.text.Component.text("x"), mod.getOpenInventory().title());
        assertFalse(plain(mod.getOpenInventory().title()).contains("Settings") || plain(mod.getOpenInventory().title()).contains("Einstellungen"),
                "the general settings are for admins");
        plugin.menus().rule(mod, "meteor");
        mod.simulateInventoryClick(23);
        assertFalse(plugin.input().waitingFor(mod.getUniqueId()), "no console command from a moderator");
        // the admin may
        plugin.menus().rule(admin, "meteor");
        admin.simulateInventoryClick(23);
        assertTrue(plugin.input().waitingFor(admin.getUniqueId()));
    }

    // ---------------------------------------------------------------- the hidden sign

    /** Fixed controls for tests: a clean client answers "Jump" and "xyz-abc". */
    private static final Probe.Ask POS = Probe.positive("key.jump", true, false);
    private static final Probe.Ask NEG = Probe.negative("gui.qwerty.title", "abc", "xyz", "-", true);

    private void reply(PlayerMock p, String... lines) {
        List<Component> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) out.add(i < lines.length ? Component.text(lines[i]) : Component.empty());
        plugin.guard().answer(p, out);
    }

    @Test
    void aSignAnswerFindsAMod() {
        plugin.guard().expectRows(steve, List.of(List.of(POS, Probe.mod("key.freecam.toggle", "freecam"), Probe.mod("key.wurst.zoom", "wurst")), List.of(NEG)));
        // the fallback is empty: a client without Wurst sends nothing back
        reply(steve, "Jump|Toggle Freecam|", "xyz-abc");
        assertTrue(plugin.guard().info(steve.getUniqueId()).found.contains("freecam"));
        assertFalse(plugin.guard().info(steve.getUniqueId()).found.contains("wurst"));
        assertFalse(steve.isOnline(), "and judged at once");
    }

    @Test
    void everydayModsAreSeenAndTheControlsDecide() {
        var g = plugin.guard();
        g.expectRows(steve, List.of(List.of(POS, Probe.mod("sodium.options.pages.general", "sodium"), Probe.mod("modmenu.title", "modmenu")), List.of(NEG)));
        reply(steve, "Jump|General|", "xyz-abc");
        assertEquals("works", g.info(steve.getUniqueId()).sign);
        assertTrue(g.mods(steve.getUniqueId()).contains("sodium"), "an everyday mod is seen too");
        assertFalse(g.mods(steve.getUniqueId()).contains("modmenu"), "the empty fallback means it is not there");
        assertTrue(steve.isOnline(), "sodium is not blocked: nothing happens");
        assertTrue(g.proof(steve.getUniqueId(), "sodium").get(0).contains("General"), "the proof names the answer");
        plugin.lang().load("de");
        plugin.menus().player(admin, steve);
        assertNoMissingTexts("player page with proof");
    }

    @Test
    void clientsThatChangeTextsAreNotBelieved() {
        var g = plugin.guard();
        List<List<Probe.Ask>> rows = List.of(List.of(POS, Probe.mod("key.meteor-client.open-gui", "meteor")), List.of(NEG));
        // blank: a text every client knows comes back raw
        g.expectRows(steve, rows);
        reply(steve, "key.jump|Open GUI", "xyz-abc");
        assertEquals("blocked", g.info(steve.getUniqueId()).sign);
        assertEquals("blank", g.info(steve.getUniqueId()).problem);
        assertTrue(g.info(steve.getUniqueId()).found.isEmpty(), "nothing it says counts");
        assertTrue(steve.isOnline(), "by default only the team is told");
        // altered: raw keys for everything that is not vanilla
        PlayerMock tamper = server.addPlayer("Tamper");
        g.expectRows(tamper, rows);
        reply(tamper, "Jump|key.meteor-client.open-gui", "gui.qwerty.title");
        assertEquals("altered", g.info(tamper.getUniqueId()).problem);
        assertTrue(g.info(tamper.getUniqueId()).found.isEmpty(), "would otherwise look like Meteor");
        // a broken copy of the text engine: the fallback comes back without its words filled in
        PlayerMock broken = server.addPlayer("Broken");
        g.expectRows(broken, rows);
        reply(broken, "Jump|", "%2$s-%1$s");
        assertEquals("altered", g.info(broken.getUniqueId()).problem);
        // set to kick: kicked; bypass: never
        plugin.getConfig().set("hidden-action", "KICK");
        PlayerMock other = server.addPlayer("Other");
        g.expectRows(other, rows);
        reply(other, "|", "xyz-abc");
        assertFalse(other.isOnline());
        PlayerMock staff = server.addPlayer("Staff");
        staff.addAttachment(plugin, "bastion.bypass", true);
        g.expectRows(staff, rows);
        reply(staff, "|", "xyz-abc");
        assertTrue(staff.isOnline(), "bypass is never punished");
    }

    @Test
    void hiddenKeysKeyBindingsAndOldMeteorAreSeen() {
        var g = plugin.guard();
        // old Meteor turns its own keys into their raw text: not what a clean client sends (nothing)
        g.expectRows(steve, List.of(List.of(POS, Probe.mod("key.meteor-client.open-gui", "meteor")), List.of(NEG)));
        reply(steve, "Jump|key.meteor-client.open-gui", "xyz-abc");
        assertTrue(g.info(steve.getUniqueId()).found.contains("meteor"), "the raw key gives old Meteor away");
        // key bindings: a clean client gives the name back, a client with the binding its key
        PlayerMock other = server.addPlayer("Other");
        g.expectRows(other, List.of(List.of(POS, Probe.mod("bind:iris.keybind.reload", "iris"), Probe.mod("bind:key.freecam.toggle", "freecam")), List.of(NEG)));
        reply(other, "Jump|R|key.freecam.toggle", "xyz-abc");
        assertTrue(g.mods(other.getUniqueId()).contains("iris"));
        assertFalse(g.mods(other.getUniqueId()).contains("freecam"));
        assertTrue(g.proof(other.getUniqueId(), "iris").get(0).contains("R"));
    }

    @Test
    void aLateAnswerToAnEarlierSignIsIgnored() {
        var g = plugin.guard();
        Probe.Ask oldNeg = Probe.negative("menu.oldone.label", "aaa", "bbb", "+", false);
        g.expectRows(steve, List.of(List.of(POS), List.of(oldNeg)));
        // the next sign is out, then the answer to the first one arrives late
        g.expectRows(steve, List.of(List.of(POS, Probe.mod("key.meteor-client.open-gui", "meteor")), List.of(NEG)));
        reply(steve, "Jump", "bbb+aaa");
        assertTrue(g.probing(steve.getUniqueId()), "still waiting for the answer to the current sign");
        assertNotEquals("blocked", g.info(steve.getUniqueId()).sign, "a late answer is no reason to distrust the client");
        reply(steve, "Jump|", "xyz-abc");
        assertEquals("works", g.info(steve.getUniqueId()).sign);
    }

    @Test
    void layoutKeepsControlsSafe() {
        var r = new java.util.Random(7);
        List<Probe.Ask> controls = Probe.controls(r, true);
        List<Probe.Ask> mods = new ArrayList<>();
        for (int i = 0; i < Probe.roomFor(true); i++) mods.add(Probe.mod("key.some.mod" + i, "m" + i));
        List<List<Probe.Ask>> rows = Probe.layout(controls, mods, r);
        assertEquals(4, rows.size());
        int total = 0;
        for (List<Probe.Ask> row : rows) {
            assertTrue(row.size() <= Probe.PER_LINE);
            assertNotEquals(Probe.Kind.MOD, row.get(0).kind(), "every line starts with a control: a cut-off line never loses one");
            for (int i = 1; i < row.size(); i++) assertEquals(Probe.Kind.MOD, row.get(i).kind());
            total += row.size();
        }
        assertEquals(12, total);
        // a mod text with the separator in it: only the control in front is read
        Probe.Ask m = Probe.mod("key.a.b", "x");
        Probe.Verdict v = Probe.judge(List.of(List.of(POS, m), List.of(NEG)), List.of("Jump|a|b", "xyz-abc"));
        assertTrue(v.trusted());
        assertFalse(v.answers().containsKey(m));
        // the made-up keys look like real ones and differ every time
        assertNotEquals(Probe.madeUpKey(r), Probe.madeUpKey(r));
        assertTrue(Probe.madeUpKey(r).matches("[a-z]+\\.[a-z]+\\.[a-z]+"));
    }

    @Test
    void theCrossCheckFindsAFilter() {
        var g = plugin.guard();
        // the channel proves Simple Voice Chat, but its text comes back empty: a filter hides mods
        g.info(steve.getUniqueId()).channels.add("voicechat:state");
        g.expectRows(steve, List.of(List.of(POS, Probe.mod("key.push_to_talk", "simplevoicechat")), List.of(NEG)));
        reply(steve, "Jump|", "xyz-abc");
        g.crossCheck(steve);
        assertEquals("filtered", g.info(steve.getUniqueId()).sign);
        assertTrue(g.info(steve.getUniqueId()).suspicion.get(0).contains("voicechat:state"));
        assertTrue(steve.isOnline(), "by default only the team is told");
        // an honest client with voice chat answers the text
        PlayerMock honest = server.addPlayer("Honest");
        g.info(honest.getUniqueId()).channels.add("voicechat:state");
        g.expectRows(honest, List.of(List.of(POS, Probe.mod("key.push_to_talk", "simplevoicechat")), List.of(NEG)));
        reply(honest, "Jump|Push to Talk", "xyz-abc");
        g.crossCheck(honest);
        assertEquals("works", g.info(honest.getUniqueId()).sign);
        // without the channel there is nothing to prove
        PlayerMock plain = server.addPlayer("Plain");
        g.expectRows(plain, List.of(List.of(POS, Probe.mod("key.push_to_talk", "simplevoicechat")), List.of(NEG)));
        reply(plain, "Jump|", "xyz-abc");
        g.crossCheck(plain);
        assertEquals("works", g.info(plain.getUniqueId()).sign);
    }

    @Test
    void theLieDetector() {
        var g = plugin.guard();
        Guard.Info liar = new Guard.Info();
        liar.brand = "vanilla";
        liar.channels.add("fabric-screen-handler-api-v1:open_screen");
        assertFalse(g.disguise(liar).isEmpty(), "vanilla does not register Fabric channels");
        Guard.Info liar2 = new Guard.Info();
        liar2.brand = "vanilla";
        liar2.found.add("fabricapi");
        assertFalse(g.disguise(liar2).isEmpty(), "vanilla does not know Fabric API texts");
        Guard.Info fake = new Guard.Info();
        fake.brand = "lunarclient:v2.21.33-2612";
        fake.channels.add("lunar:apollo");
        assertTrue(g.disguise(fake).stream().anyMatch(l -> l.contains("v2.21.33-2612")), "a client name only LiquidBounce sends");
        Guard.Info lunar = new Guard.Info();
        lunar.brand = "lunarclient:v2.23.10-2650";
        assertTrue(g.disguise(lunar).isEmpty(), "Lunar without Lunar channels is no proof ...");
        assertFalse(g.hints(lunar).isEmpty(), "... only a hint on the page");
        lunar.channels.add("lunar:apollo");
        assertTrue(g.hints(lunar).isEmpty(), "a real Lunar client");
        Guard.Info honest = new Guard.Info();
        honest.brand = "vanilla";
        assertTrue(g.disguise(honest).isEmpty());
        Guard.Info fabric = new Guard.Info();
        fabric.brand = "fabric";
        fabric.channels.add("fabric:registry/sync");
        assertTrue(g.disguise(fabric).isEmpty());
        // set to kick
        plugin.getConfig().set("disguise-action", "KICK");
        g.info(steve.getUniqueId()).brand = "vanilla";
        g.info(steve.getUniqueId()).channels.add("c:register");
        g.judge(steve);
        assertFalse(steve.isOnline());
    }

    @Test
    void oneCheckAtATimeAndAlertsOnlyOnce() {
        var g = plugin.guard();
        // a check is running (questions on the sign): a second one waits for it
        g.expectRows(steve, List.of(List.of(POS), List.of(NEG)));
        g.probe(steve, true);
        assertTrue(g.probing(steve.getUniqueId()));
        reply(steve, "Jump", "xyz-abc");
        server.getScheduler().performTicks(40);
        assertFalse(g.probing(steve.getUniqueId()), "the waiting check ran afterwards");
        // an alert-only mod is reported once per session, not on every check
        plugin.getConfig().set("rules.freecam.action", "ALERT");
        plugin.getConfig().set("repeat.window", "1d");
        plugin.getConfig().set("repeat.count-alerts", true);
        plugin.getConfig().set("repeat.times", 5);
        g.info(steve.getUniqueId()).channels.add("freecam:main");
        g.judge(steve);
        g.judge(steve);
        g.judge(steve);
        assertEquals(1, g.recentOffenses(steve.getUniqueId()));
        assertTrue(steve.isOnline());
        assertTrue(g.proof(steve.getUniqueId(), "freecam").get(0).contains("freecam:main"), "the channel is the proof");
    }

    @Test
    void lumioReportIsTrustedOnlyWhenItMatches() {
        assertNull(KnownMods.find("fabric"), "a loader name is no mod");
        assertNull(KnownMods.find("forge"));
        assertEquals("fabricapi", KnownMods.find("fabric-api").id());
        assertEquals("meteor", KnownMods.find("meteor-client").id());
        assertEquals("simplevoicechat", KnownMods.find("voicechat").id());
        var g = plugin.guard();
        Guard.Info info = g.info(steve.getUniqueId());
        info.lumio = new Lumio.Report();
        info.lumio.complete = true;
        info.lumio.mods.add(new Lumio.Mod("fabric-api", "Fabric API", "0.141.6", "ab", ""));
        info.lumio.mods.add(new Lumio.Mod("sodium", "Sodium", "0.8.0", "cd", ""));
        info.channels.add("fabric:recipe_sync");
        info.channels.add("fabric-screen-handler-api-v1:open_screen");
        info.found.add("fabricapi");
        info.found.add("sodium");
        assertTrue(g.reportLies(info).isEmpty(), "an honest report");
        assertTrue(g.mods(steve.getUniqueId()).contains("sodium"));
        assertTrue(g.proof(steve.getUniqueId(), "sodium").stream().anyMatch(l -> l.contains("0.8.0")), "the report is proof");
        // the sign saw Meteor, the report leaves it out: forged
        info.found.add("meteor");
        assertFalse(g.reportLies(info).isEmpty());
        // a reported blocked mod is a hit on its own
        info.found.remove("meteor");
        info.lumio.mods.add(new Lumio.Mod("meteor-client", "Meteor Client", "1.21.11-50", "ef", ""));
        assertTrue(g.hits(steve.getUniqueId()).stream().anyMatch(r -> r.id().equals("meteor")));
        assertTrue(g.reportLies(info).isEmpty());
    }

    private List<String> messages(PlayerMock p) {
        List<String> out = new ArrayList<>();
        for (Component c; (c = p.nextComponentMessage()) != null; ) out.add(plain(c));
        return out;
    }

    @Test
    void theOwnerDecidesWhetherLumioIsRequired() {
        messages(admin);
        admin.performCommand("bastion lumio");
        List<String> said = messages(admin);
        assertTrue(said.stream().anyMatch(l -> l.contains("optional")), "the current mode: " + said);
        assertTrue(said.stream().anyMatch(l -> l.contains("not know every mod")), "not required: the owner is told what that costs");
        admin.performCommand("bastion lumio pflicht");
        assertEquals("required", plugin.getConfig().getString("lumio.mode"));
        said = messages(admin);
        assertTrue(said.stream().anyMatch(l -> l.contains("refused")), "required: who will be refused");
        assertTrue(said.stream().anyMatch(l -> l.contains("download link")), "and that a download link is missing");
        assertFalse(said.stream().anyMatch(l -> l.contains("not know every mod")));
        admin.performCommand("bastion lumio off");
        assertEquals("off", plugin.getConfig().getString("lumio.mode"));
        admin.performCommand("bastion lumio nonsense");
        assertEquals("off", plugin.getConfig().getString("lumio.mode"), "an unknown word changes nothing");
        // a moderator may not decide this
        PlayerMock mod = server.addPlayer("Mod");
        mod.addAttachment(plugin, "bastion.check", true);
        mod.performCommand("bastion lumio required");
        assertEquals("off", plugin.getConfig().getString("lumio.mode"));
        // the button in the settings goes off -> optional -> required
        plugin.menus().settings(admin);
        admin.simulateInventoryClick(43);
        assertEquals("optional", plugin.getConfig().getString("lumio.mode"));
        plugin.lang().load("de");
        plugin.menus().settings(admin);
        assertNoMissingTexts("settings with Lumio");
        messages(admin);
        admin.performCommand("bastion lumio");
        assertTrue(messages(admin).stream().anyMatch(l -> l.contains("nicht alle Mods")));
    }

    // ---------------------------------------------------------------- check room

    private long now = 1_000_000L;

    private PlayerMock joinIntoRoom(String name) {
        plugin.getConfig().set("check-room.enabled", true);
        plugin.getConfig().set("sign-check", false);
        plugin.room().clock = () -> now;
        PlayerMock p = server.addPlayer(name);
        return p;
    }

    @Test
    void theCheckRoomHoldsPlayersUntilTheCheckIsDone() {
        plugin.getConfig().set("lumio.mode", "optional");
        var before = steve.getLocation().clone();
        PlayerMock alex = joinIntoRoom("Alex");
        var start = alex.getLocation().clone();
        assertTrue(plugin.room().holds(alex));
        var c = plugin.room().center();
        assertEquals(c.getBlockY(), alex.getLocation().getBlockY(), "in the room");
        assertEquals(org.bukkit.Material.BEDROCK, c.clone().add(0, -1, 0).getBlock().getType(), "a floor of bedrock");
        assertEquals(org.bukkit.Material.BEDROCK, c.clone().add(0, 3, 0).getBlock().getType(), "a ceiling of bedrock");
        assertEquals(org.bukkit.Material.BEDROCK, c.clone().add(3, 1, 0).getBlock().getType(), "walls of bedrock");
        // nothing happens inside, and the team does not see who is being checked
        assertFalse(steve.canSee(alex));
        alex.performCommand("spawn");
        assertTrue(plugin.room().holds(alex));
        // too early: the client name is judged after three seconds
        now += 1000;
        plugin.room().tick();
        assertTrue(plugin.room().holds(alex));
        now += 4000;
        plugin.room().tick();
        assertFalse(plugin.room().holds(alex), "checked: out again");
        server.getScheduler().performTicks(2);
        assertEquals(start.getBlockX(), alex.getLocation().getBlockX());
        assertNotEquals(c.getBlockY(), alex.getLocation().getBlockY(), "back where the player was");
        assertTrue(steve.canSee(alex));
        assertEquals(before, steve.getLocation(), "players who were there before are not touched");
    }

    @Test
    void withoutLumioWhenRequiredThePlayerStaysWithAWindow() {
        plugin.getConfig().set("lumio.mode", "required");
        PlayerMock alex = joinIntoRoom("Alex");
        now += 3000;
        plugin.room().tick();
        server.getScheduler().performTicks(60);
        assertTrue(alex.isOnline(), "not kicked: a window instead");
        assertEquals(CheckRoom.State.MISSING_LUMIO, plugin.room().stateOf(alex));
        // the window cannot be closed: whoever closed it and looks around gets it again at once
        long first = plugin.room().windowShownAt(alex);
        assertTrue(first > 0, "the window was opened");
        now += 2000;
        Location look = alex.getLocation().clone();
        look.setYaw(look.getYaw() + 40);
        PlayerMoveEvent turn = new PlayerMoveEvent(alex, alex.getLocation(), look);
        server.getPluginManager().callEvent(turn);
        assertTrue(plugin.room().windowShownAt(alex) > first, "looked around: the window is open again");
        long second = plugin.room().windowShownAt(alex);
        now += 2000;
        alex.simulatePlayerMove(alex.getLocation().clone().add(0, 0, 0));
        assertEquals(second, plugin.room().windowShownAt(alex), "standing still in the window: nothing happens");
        now += 2000;
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerItemHeldEvent(alex, 0, 3));
        assertTrue(plugin.room().windowShownAt(alex) > second, "switching items means the window was closed: open again");
        // commands and chat wait
        alex.performCommand("spawn");
        assertTrue(plugin.room().holds(alex));
        // the window's leave button
        alex.performCommand("mcroom leave");
        assertFalse(alex.isOnline());

        PlayerMock bob = joinIntoRoom("Bob");
        now += 3000;
        plugin.room().tick();
        assertTrue(bob.isOnline());
        now += 6 * 60_000L;
        plugin.room().tick();
        assertFalse(bob.isOnline(), "after the waiting time: out");

        // set to kick: out at once with the Lumio screen
        plugin.getConfig().set("check-room.missing-lumio", "kick");
        PlayerMock carl = joinIntoRoom("Carl");
        now += 3000;
        plugin.room().tick();
        assertFalse(carl.isOnline());
    }

    @Test
    void aForbiddenModInTheRoomCanKeepThePlayerThere() {
        plugin.getConfig().set("lumio.mode", "off");
        assertTrue(admin.performCommand("modblock meteor kick"));
        plugin.getConfig().set("check-room.forbidden", "stay");
        PlayerMock alex = joinIntoRoom("Alex");
        plugin.guard().info(alex.getUniqueId()).channels.add("meteor-client:main");
        plugin.guard().judge(alex);
        assertTrue(alex.isOnline(), "stays in the room");
        assertEquals(CheckRoom.State.FORBIDDEN, plugin.room().stateOf(alex));
        now += 10_000;
        plugin.room().tick();
        assertTrue(plugin.room().holds(alex), "not let out with a forbidden mod");
        // with "kick": the kick says what it is and what to do
        plugin.getConfig().set("check-room.forbidden", "kick");
        PlayerMock bob = joinIntoRoom("Bob");
        plugin.guard().info(bob.getUniqueId()).channels.add("meteor-client:main");
        plugin.guard().judge(bob);
        assertFalse(bob.isOnline());
    }

    @Test
    void theRoomMenuIsSimpleAndComplete() {
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            plugin.menus().room(admin);
            assertNoMissingTexts("room menu " + code);
            plugin.menus().main(admin);
            assertNoMissingTexts("main menu " + code);
        }
        plugin.menus().room(admin);
        admin.simulateInventoryClick(10);
        assertTrue(plugin.getConfig().getBoolean("check-room.enabled"), "switched on from the test default");
        admin.simulateInventoryClick(12);
        assertEquals("stay", plugin.getConfig().getString("check-room.forbidden"));
        admin.simulateInventoryClick(21);
        assertEquals("BAN", plugin.getConfig().getString("injection-action"));
        assertTrue(admin.performCommand("bastion raum"));
        steve.performCommand("bastion raum");
        var top = steve.getOpenInventory().getTopInventory();
        assertFalse(top != null && top.getHolder() instanceof Menu, "players cannot");
    }

    @Test
    void windowsAreRealDialogsFrom1216() {
        assertTrue(Popups.atLeast("1.21.6", 1, 21, 6));
        assertTrue(Popups.atLeast("1.21.11", 1, 21, 6));
        assertFalse(Popups.atLeast("1.21.4", 1, 21, 6));
        assertTrue(Popups.atLeast("1.22", 1, 21, 6));
        // the texts of every window exist in both languages
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            for (String k : new String[]{"window.missing.title", "window.missing.text", "window.noreport.text", "window.outdated.text",
                    "window.forbidden.text", "window.guide-steps", "window.download", "window.guide", "window.leave", "window.back",
                    "book.missing", "book.guide-steps", "kick.forbidden", "room.bar-checking", "lumio.kick-silent", "injection.kind.module"}) {
                assertFalse(plugin.lang().raw(k).contains("missing text"), code + " " + k);
            }
        }
    }

    // ---------------------------------------------------------------- injection

    private String greet(PlayerMock p) {
        plugin.lumio().hello(p);
        java.lang.reflect.Field f;
        try {
            f = Lumio.class.getDeclaredField("nonces");
            f.setAccessible(true);
            @SuppressWarnings("unchecked") var map = (java.util.Map<java.util.UUID, String>) f.get(plugin.lumio());
            return map.get(p.getUniqueId());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void injectedCodeFoundByLumioKicksWithItsName() {
        plugin.getConfig().set("lumio.mode", "optional");
        String nonce = greet(steve);
        assertNotNull(nonce);
        var o = com.google.gson.JsonParser.parseString("{\"v\":1,\"nonce\":\"" + nonce + "\",\"findings\":["
                + "{\"kind\":\"module\",\"strong\":false,\"what\":\"odd.dll (Downloads)\"}]}").getAsJsonObject();
        plugin.lumio().integrity(steve, o);
        assertTrue(steve.isOnline(), "a weak sign only goes to the team");
        assertTrue(plugin.guard().info(steve.getUniqueId()).integrity.get(0).contains("odd.dll"));
        // a wrong word is not ours
        var forged = com.google.gson.JsonParser.parseString("{\"nonce\":\"nope\",\"findings\":[{\"kind\":\"thread\",\"strong\":true,\"what\":\"x\"}]}").getAsJsonObject();
        plugin.lumio().integrity(steve, forged);
        assertTrue(steve.isOnline());
        var strong = com.google.gson.JsonParser.parseString("{\"v\":1,\"nonce\":\"" + nonce + "\",\"findings\":["
                + "{\"kind\":\"process\",\"strong\":true,\"name\":\"Vape\",\"what\":\"vape.exe\"},"
                + "{\"kind\":\"thread\",\"strong\":true,\"what\":\"start 0x7ff6000 outside every module\"}]}").getAsJsonObject();
        plugin.lumio().integrity(steve, strong);
        assertFalse(steve.isOnline(), "out");
    }

    @Test
    void theJoinCheckWaitsUntilLumioWatchedTheGame() {
        plugin.getConfig().set("lumio.mode", "optional");
        String nonce = greet(steve);
        Guard.Info info = plugin.guard().info(steve.getUniqueId());
        Lumio.Report r = new Lumio.Report();
        r.lumio = "0.11.3";
        r.complete = true;
        assertFalse(Lumio.gameChecked(r, info), "no answer yet");
        plugin.lumio().integrity(steve, com.google.gson.JsonParser.parseString("{\"v\":1,\"nonce\":\"" + nonce
                + "\",\"checks\":[\"agents\",\"native\",\"code\"],\"findings\":[]}").getAsJsonObject());
        assertFalse(Lumio.gameChecked(r, info), "the game's thread was not watched yet");
        plugin.lumio().integrity(steve, com.google.gson.JsonParser.parseString("{\"v\":1,\"nonce\":\"" + nonce
                + "\",\"checks\":[\"agents\",\"native\",\"code\",\"sampled\"],\"findings\":[]}").getAsJsonObject());
        assertTrue(Lumio.gameChecked(r, info), "everything looked at once");
        // an older Lumio cannot say so: its first answer is enough
        Guard.Info fresh = new Guard.Info();
        fresh.integrityAt = 1;
        Lumio.Report old = new Lumio.Report();
        old.lumio = "0.11.2";
        assertTrue(Lumio.gameChecked(old, fresh));
    }

    private void findings(PlayerMock p, String json) {
        String nonce = greet(p);
        plugin.lumio().integrity(p, com.google.gson.JsonParser.parseString("{\"v\":1,\"nonce\":\"" + nonce + "\",\"findings\":[" + json + "]}").getAsJsonObject());
    }

    @Test
    void knownInjectClientsAreRecognised() {
        assertEquals("vape", KnownInjects.match("process", "Vape.exe").id());
        assertEquals("vapelite", KnownInjects.match("process", "vapelite.exe").id());
        assertEquals("vapelite", KnownInjects.match("code", "gg.vape.lite.Main (Render thread)").id(), "the longer beginning wins");
        assertEquals("vape", KnownInjects.match("code", "gg.vape.Main").id());
        assertEquals("vape", KnownInjects.match("module", "vape.dll (Temp)").id());
        assertEquals("prestige", KnownInjects.match("code", "dev.prestige.modules.Reach").id());
        assertNull(KnownInjects.match("code", "net.minecraft.client.Minecraft"));
        assertNull(KnownInjects.match("process", "sunset.exe"), "too common a name on its own");
        assertNull(KnownInjects.match("thread", "vape"));
        assertEquals("vapelite", KnownInjects.find("Vape Lite").id());
        assertEquals("prestige", KnownInjects.find("prestigeclient.exe").id());
        assertNull(KnownInjects.find("minecraft"));
        // Lumio is told to look for all of them, plus the server's own names
        assertEquals("added", plugin.lumio().toggleOwnProcess("GhostLoader.exe"));
        assertEquals("known", plugin.lumio().toggleOwnProcess("vape"));
        assertEquals("bad", plugin.lumio().toggleOwnProcess("no spaces"));
        assertEquals(List.of("ghostloader"), plugin.lumio().ownProcesses());
        assertEquals("removed", plugin.lumio().toggleOwnProcess("ghostloader"));
        assertTrue(plugin.lumio().ownProcesses().isEmpty());
    }

    @Test
    void injectBlocksOneByOneOrAll() {
        plugin.getConfig().set("lumio.mode", "optional");
        String sure = "{\"kind\":\"thread\",\"strong\":true,\"what\":\"start 0x7ff6000 outside every module\"}";
        String vape = "{\"kind\":\"process\",\"strong\":true,\"name\":\"Vape\",\"what\":\"vape.exe\"}";
        String sign = "{\"kind\":\"module\",\"strong\":false,\"what\":\"odd.dll (Downloads)\"}";
        // "block all" is on by default: any sure injection is out
        assertTrue(plugin.lumio().blockAll());
        PlayerMock a = server.addPlayer("A1");
        findings(a, sure);
        assertFalse(a.isOnline());
        // off: only the clients blocked one by one
        assertTrue(admin.performCommand("inject alle aus"));
        assertFalse(plugin.lumio().blockAll());
        PlayerMock b = server.addPlayer("B1");
        findings(b, sure);
        assertTrue(b.isOnline(), "unknown and not blocked: only a hint");
        findings(b, vape);
        assertTrue(b.isOnline(), "Vape not blocked yet");
        PlayerMock c = server.addPlayer("C1");
        assertTrue(admin.performCommand("inject vape kick"));
        assertEquals("KICK", plugin.lumio().clientAction("vape"));
        findings(c, vape);
        assertFalse(c.isOnline(), "Vape blocked by name");
        // one client only told to the team, even with "block all"
        admin.performCommand("inject alle an");
        admin.performCommand("inject vape melden");
        PlayerMock d = server.addPlayer("D1");
        findings(d, vape);
        assertTrue(d.isOnline(), "only an alert for Vape");
        // back to the default: follows "block all"
        admin.performCommand("inject vape standard");
        assertNull(plugin.lumio().clientAction("vape"));
        // strict: small signs are enough
        PlayerMock e = server.addPlayer("E1");
        findings(e, sign);
        assertTrue(e.isOnline());
        admin.performCommand("inject streng an");
        assertTrue(plugin.lumio().strict());
        // a DLL in an odd folder is never enough (overlays, drivers, antivirus put them into every game)
        PlayerMock f = server.addPlayer("F1");
        findings(f, sign);
        server.getScheduler().performTicks(80);
        assertTrue(f.isOnline(), "strict: a DLL alone is no reason");
        // something attached to the running game is: a kick, never a ban
        PlayerMock g = server.addPlayer("G1");
        findings(g, "{\"kind\":\"attach\",\"strong\":false,\"what\":\"Attach Listener\"}");
        server.getScheduler().performTicks(80);
        assertFalse(g.isOnline(), "strict: an attached tool");
        assertNull(plugin.punish().caseOf(g.getUniqueId()), "only a kick");
        assertTrue(plugin.akte().of(g.getUniqueId()).events.stream().anyMatch(ev -> ev.type().equals("sign")), "in the file");
        // players without the right cannot change it
        steve.performCommand("inject streng aus");
        assertTrue(plugin.lumio().strict());
    }

    @Test
    void theInjectMenuWorksLikeModblock() {
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            assertTrue(admin.performCommand("inject"));
            assertNoMissingTexts("inject menu " + code);
        }
        admin.simulateInventoryClick(12);
        assertFalse(plugin.lumio().blockAll(), "block all switched off");
        admin.simulateInventoryClick(14);
        assertTrue(plugin.lumio().strict());
        admin.simulateInventoryClick(16);
        assertEquals("BAN", plugin.lumio().injectionAction());
        admin.simulateInventoryClick(28);
        assertEquals("KICK", plugin.lumio().clientAction(KnownInjects.ALL.get(0).id()));
        admin.simulateInventoryClick(28);
        assertEquals("BAN", plugin.lumio().clientAction(KnownInjects.ALL.get(0).id()));
        // Lumio required from the warning
        plugin.getConfig().set("lumio.mode", "optional");
        admin.performCommand("inject");
        admin.simulateInventoryClick(4);
        assertEquals("required", plugin.lumio().mode());
        steve.performCommand("inject");
        var top = steve.getOpenInventory().getTopInventory();
        assertFalse(top != null && top.getHolder() instanceof Menu, "players cannot");
    }

    @Test
    void aSilentLumioIsOutWhenRequired() {
        plugin.getConfig().set("lumio.mode", "required");
        Guard.Info info = plugin.guard().info(steve.getUniqueId());
        info.lumio = new Lumio.Report();
        info.lumio.lumio = "0.6.0";
        info.lumio.complete = true;
        info.lumioAt = System.currentTimeMillis() - 200_000L;
        plugin.lumio().heartbeat();
        assertFalse(steve.isOnline(), "no word from Lumio for too long");
        // an older Lumio is too old once the injection check is on
        Lumio.Report old = new Lumio.Report();
        old.lumio = "0.5.0";
        assertTrue(plugin.lumio().outdated(old));
        plugin.getConfig().set("injection.enabled", false);
        assertFalse(plugin.lumio().outdated(old));
    }

    @Test
    void bedrockIsOnlyWhatFloodgateSays() {
        // the old default let in anyone who called their client "geyser"
        assertTrue(plugin.getConfig().getStringList("lumio.exempt-brands").isEmpty());
        plugin.guard().info(steve.getUniqueId()).brand = "geyser";
        assertFalse(plugin.lumio().exempt(steve));
    }

    @Test
    void leavingTheRoomEarlyStillBringsThePlayerBackNextTime() {
        plugin.getConfig().set("lumio.mode", "optional");
        PlayerMock alex = joinIntoRoom("Alex");
        var key = new org.bukkit.NamespacedKey("modcheck", "check_room_back");
        String note = alex.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING);
        assertNotNull(note, "where the player was is noted on entering");
        // kicked or gone while inside: the note stays for the next join
        alex.disconnect();
        assertNotNull(alex.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING));
        // the next join goes into the room again and afterwards back to the noted place
        var back = CheckRoom.read(note);
        alex.reconnect();
        assertTrue(plugin.room().holds(alex));
        now += 10_000;
        plugin.room().tick();
        assertFalse(plugin.room().holds(alex));
        assertEquals(back.getBlockY(), alex.getLocation().getBlockY());
        assertNull(alex.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING), "the note is gone once back");
    }

    @Test
    void theHiddenSignWorksInsideTheRoomOnOlderServers() {
        plugin.getConfig().set("lumio.mode", "optional");
        PlayerMock alex = joinIntoRoom("Alex");
        var c = plugin.room().center();
        assertEquals(org.bukkit.Material.STONE, c.clone().add(0, -2, 0).getBlock().getType(), "stone under the floor for the sign");
        var spot = plugin.guard().signSpot(alex);
        assertNotNull(spot, "the sign finds its place under the floor");
        assertEquals(c.getBlockY() - 2, spot.getY(), "hidden right under the bedrock floor");
        // building the room again leaves a sign of a running check alone
        spot.setType(org.bukkit.Material.OAK_SIGN);
        plugin.room().build(c);
        assertEquals(org.bukkit.Material.OAK_SIGN, spot.getType());
        // the bottom place keeps room for the stone too
        plugin.getConfig().set("check-room.place", "bottom");
        var low = plugin.room().center();
        assertTrue(low.getBlockY() - 3 > low.getWorld().getMinHeight());
    }

    @Test
    void bansGoThroughTheServersBanSystem() {
        // an old Bastion ban moves to the server's ban system (here the game's own ban list)
        plugin.getConfig().set("ban-system", "modcheck");
        assertTrue(admin.performCommand("modblock freecam"));
        plugin.getConfig().set("rules.freecam.action", "BAN");
        plugin.guard().info(steve.getUniqueId()).channels.add("freecam:main");
        plugin.guard().judge(steve);
        assertNotNull(plugin.guard().ban(steve.getUniqueId()), "in Bastion's own list");
        plugin.getConfig().set("ban-system", "auto");
        assertEquals("vanilla", plugin.guard().banSystem(), "no ban plugin here: the game's own list");
        assertEquals(1, plugin.guard().moveOwnBans());
        assertNull(plugin.guard().ban(steve.getUniqueId()), "moved out of Bastion's list");
        assertTrue(server.getOfflinePlayer(steve.getUniqueId()).isBanned(), "and into the game's ban list");
        // /bastion unban lifts it there too
        assertTrue(admin.performCommand("bastion unban Steve"));
        assertFalse(server.getOfflinePlayer(steve.getUniqueId()).isBanned());
        // a new ban goes straight there
        PlayerMock alex = server.addPlayer("Alex");
        plugin.guard().info(alex.getUniqueId()).channels.add("freecam:main");
        plugin.guard().judge(alex);
        assertNull(plugin.guard().ban(alex.getUniqueId()));
        assertTrue(server.getOfflinePlayer(alex.getUniqueId()).isBanned());
        assertFalse(alex.isOnline());
    }

    // ---------------------------------------------------------------- Bastion 2: levels, ladder, files, packs, rights, anti-cheat

    @Test
    void setupLevelsSetEverythingAtOnce() {
        assertTrue(admin.performCommand("setup 3"));
        assertEquals(3, plugin.setup().level());
        assertEquals(List.of("KICK", "BAN 7d", "BAN 30d", "BAN perm"), plugin.getConfig().getStringList("punish.ladder"));
        assertEquals("1d", plugin.getConfig().getString("repeat.window"));
        assertEquals("BAN 7d", plugin.getConfig().getString("repeat.action"));
        assertTrue(plugin.getConfig().getBoolean("injection.strict"));
        assertNotNull(plugin.guard().rule("meteor"));
        assertNotNull(plugin.guard().rule("tweakeroo"));
        // a mod the team blocked by hand stays when the level goes down
        admin.performCommand("modblock journeymap");
        assertTrue(admin.performCommand("setup 1"));
        assertNull(plugin.guard().rule("tweakeroo"), "added by level 3, gone with level 1");
        assertNotNull(plugin.guard().rule("meteor"));
        assertNotNull(plugin.guard().rule("journeymap"));
        assertEquals("off", plugin.getConfig().getString("repeat.window"));
        steve.performCommand("setup 3");
        assertEquals(1, plugin.setup().level(), "players cannot");
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            plugin.setup().open(admin);
            assertNoMissingTexts("setup " + code);
            plugin.menus().main(admin);
            assertNoMissingTexts("main " + code);
        }
    }

    @Test
    void theLadderClimbsAndBansShowBastionsScreen() {
        admin.performCommand("setup 3");
        plugin.getConfig().set("discord", "discord.gg/test");
        UUID id = steve.getUniqueId();
        assertEquals("KICK", plugin.punish().caught(id, "Steve", "Anti-Cheat: Fly", "test"));
        assertFalse(steve.isOnline());
        assertEquals(1, plugin.akte().of(id).kicks);
        assertEquals("BAN 7d", plugin.punish().caught(id, "Steve", "Anti-Cheat: Fly", "test"));
        Punish.Case c = plugin.punish().caseOf(id);
        assertNotNull(c);
        assertTrue(plugin.guard().bannedOwn(id));
        String screen = plain(plugin.punish().banScreen(c));
        assertTrue(screen.contains("#" + c.id()) && screen.contains(id.toString()) && screen.contains("discord.gg/test"), screen);
        // joining again shows the same screen
        var pre = new AsyncPlayerPreLoginEvent("Steve", InetAddress.getLoopbackAddress(), id);
        plugin.guard().onPreLogin(pre);
        plugin.punish().onPreLogin(pre);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, pre.getLoginResult());
        assertTrue(plain(pre.kickMessage()).contains("#" + c.id()));
        assertTrue(plugin.akte().of(id).events.stream().anyMatch(e -> e.type().equals("ban")));
    }

    @Test
    void everyPlayerHasAFileThatIsKept() {
        UUID id = steve.getUniqueId();
        Akte.Entry a = plugin.akte().of(id);
        assertEquals("Steve", a.name);
        assertTrue(a.joins >= 1);
        plugin.akte().note(id, "Admin", "watch his pvp");
        plugin.akte().event(id, "kick", "test", "Admin");
        plugin.akte().flush();
        Akte fresh = new Akte(plugin);
        Akte.Entry b = fresh.of(id);
        assertEquals(1, b.notes.size());
        assertEquals(1, b.kicks);
        assertEquals("watch his pvp", b.notes.get(0).text());
        assertEquals(id, plugin.akte().byName("steve"));
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            assertTrue(admin.performCommand("akte Steve"));
            assertNoMissingTexts("file " + code);
        }
        steve.performCommand("akte Admin");
        var top = steve.getOpenInventory().getTopInventory();
        assertFalse(top != null && top.getHolder() instanceof Menu, "players cannot");
    }

    @Test
    void xrayPacksAreOut() {
        plugin.getConfig().set("lumio.mode", "optional");
        assertTrue(plugin.guard().cheatPack(new Guard.PackSeen("Xray Ultimate 1.21", false, "")));
        assertTrue(plugin.guard().cheatPack(new Guard.PackSeen("My_Pack", true, "stone see-through")));
        assertFalse(plugin.guard().cheatPack(new Guard.PackSeen("Faithful 32x", false, "")));
        PlayerMock a = server.addPlayer("PackA");
        findings(a, "{\"kind\":\"pack\",\"strong\":false,\"what\":\"Faithful 32x\"}");
        assertTrue(a.isOnline(), "a normal pack is fine, also no injection");
        findings(a, "{\"kind\":\"pack\",\"strong\":true,\"what\":\"Clear Stone (stone see-through)\"}");
        // just joined: the kick comes a moment later, so the client shows the reason
        server.getScheduler().performTicks(80);
        assertFalse(a.isOnline(), "an x-ray pack is out");
        assertTrue(plugin.akte().of(a.getUniqueId()).packs.contains("Clear Stone"));
        // strict mode does not count packs as injections
        plugin.getConfig().set("injection.strict", true);
        PlayerMock b = server.addPlayer("PackB");
        findings(b, "{\"kind\":\"pack\",\"strong\":false,\"what\":\"Faithful 32x\"}");
        assertTrue(b.isOnline());
    }

    @Test
    void rightsPerRankAndPerPlayer() {
        assertFalse(steve.hasPermission("bastion.akte"));
        plugin.ranks().setPlayerRight(steve.getUniqueId(), "bastion.akte", true);
        assertTrue(steve.hasPermission("bastion.akte"));
        plugin.ranks().setPlayerRight(steve.getUniqueId(), "bastion.akte", null);
        assertFalse(steve.hasPermission("bastion.akte"));
        // without a rank plugin: what every player may
        var src = plugin.ranks().source();
        Ranks.Group everybody = src.groups().stream().filter(g -> g.id().equals("everybody")).findFirst().orElseThrow();
        src.set(everybody, "bastion.notify", true);
        plugin.ranks().apply(steve);
        assertTrue(steve.hasPermission("bastion.notify"));
        // an operator can be denied one thing
        plugin.ranks().setPlayerRight(admin.getUniqueId(), "bastion.unban", false);
        assertFalse(admin.hasPermission("bastion.unban"));
        assertTrue(admin.hasPermission("bastion.check"));
        for (String code : new String[]{"en", "de"}) {
            plugin.lang().load(code);
            plugin.ranks().open(admin);
            assertNoMissingTexts("ranks " + code);
            plugin.ranks().group(admin, "everybody");
            assertNoMissingTexts("rank " + code);
            plugin.ranks().player(admin, steve.getUniqueId(), "Steve");
            assertNoMissingTexts("player rights " + code);
        }
    }

    @Test
    void movementBeyondTheGamesPhysicsGoesBack() {
        var ac = plugin.antiCheat();
        steve.setGameMode(org.bukkit.GameMode.SURVIVAL);
        Location start = steve.getLocation().clone();
        // solid ground under the feet
        for (int x = -3; x <= 12; x++) for (int z = -3; z <= 3; z++) start.getWorld().getBlockAt(start.getBlockX() + x, start.getBlockY() - 1, start.getBlockZ() + z).setType(Material.STONE);
        for (int x = -3; x <= 12; x++) for (int z = -3; z <= 3; z++) for (int y = 0; y <= 3; y++) start.getWorld().getBlockAt(start.getBlockX() + x, start.getBlockY() + y, start.getBlockZ() + z).setType(Material.AIR);
        ac.endGrace(steve);
        // walking: fine
        Location at = start.clone();
        for (int i = 0; i < 5; i++) {
            Location next = at.clone().add(0.2, 0, 0);
            steve.simulatePlayerMove(next);
            at = steve.getLocation().clone();
            sleep(50);
        }
        assertEquals(0.0, ac.points(steve.getUniqueId(), "speed"), "walking is no speed");
        // a speed cheat: a whole block per step; every step is put back where it was in order
        // (the test server does not carry out the setback itself, the event says where to go)
        for (int i = 0; i < 4; i++) {
            Location before = steve.getLocation().clone();
            var ev = steve.simulatePlayerMove(before.clone().add(1.0, 0, 0));
            assertTrue(ev.getTo().getX() <= at.getX() + 0.01, "put back, step " + i);
            steve.setLocation(ev.getTo());
            sleep(50);
        }
        assertTrue(ac.points(steve.getUniqueId(), "speed") > 0, "speed found");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void riptideNeedsWaterAndEatingNeedsTime() {
        var ac = plugin.antiCheat();
        steve.setGameMode(org.bukkit.GameMode.SURVIVAL);
        ItemStack trident = new ItemStack(Material.TRIDENT);
        trident.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.RIPTIDE, 3);
        ac.onRiptide(new org.bukkit.event.player.PlayerRiptideEvent(steve, trident, new org.bukkit.util.Vector(0, 1, 0)));
        assertTrue(ac.points(steve.getUniqueId(), "trident") > 0 || !plugin.antiCheat().suspects().isEmpty(), "riptide on dry land");
        PlayerMock eater = server.addPlayer("Eater");
        eater.setGameMode(org.bukkit.GameMode.SURVIVAL);
        ItemStack bread = new ItemStack(Material.BREAD);
        ac.onStartUse(new org.bukkit.event.player.PlayerInteractEvent(eater, org.bukkit.event.block.Action.RIGHT_CLICK_AIR, bread, null, org.bukkit.block.BlockFace.SELF));
        var eat = new org.bukkit.event.player.PlayerItemConsumeEvent(eater, bread, org.bukkit.inventory.EquipmentSlot.HAND);
        ac.onConsume(eat);
        assertTrue(eat.isCancelled(), "eating at once does not count");
        assertTrue(ac.points(eater.getUniqueId(), "fasteat") > 0);
    }

    @Test
    void aDeadlyHitIsNoAntiKnockback() {
        var ac = plugin.antiCheat();
        PlayerMock alive = server.addPlayer("Alive");
        PlayerMock dying = server.addPlayer("Dying");
        for (PlayerMock p : List.of(alive, dying)) {
            p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            Location at = p.getLocation();
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
                at.getWorld().getBlockAt(at.getBlockX() + x, at.getBlockY() - 1, at.getBlockZ() + z).setType(Material.STONE);
                for (int y = 0; y <= 3; y++) at.getWorld().getBlockAt(at.getBlockX() + x, at.getBlockY() + y, at.getBlockZ() + z).setType(Material.AIR);
            }
            ac.endGrace(p);
        }
        // both are hit; neither moves afterwards
        ac.onVelocity(new org.bukkit.event.player.PlayerVelocityEvent(alive, new org.bukkit.util.Vector(0.4, 0.36, 0)));
        ac.onVelocity(new org.bukkit.event.player.PlayerVelocityEvent(dying, new org.bukkit.util.Vector(0.4, 0.36, 0)));
        dying.setHealth(0);
        server.getScheduler().performTicks(20);
        assertTrue(ac.points(alive.getUniqueId(), "velocity") > 0, "alive and not pushed: anti-knockback");
        assertEquals(0.0, ac.points(dying.getUniqueId(), "velocity"), "the hit killed: the dead do not move");
    }
}
