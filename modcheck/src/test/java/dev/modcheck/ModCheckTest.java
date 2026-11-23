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
        mod.addAttachment(plugin, "modcheck.block", true);
        mod.addAttachment(plugin, "modcheck.check", true);
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
        staff.addAttachment(plugin, "modcheck.bypass", true);
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
        admin.performCommand("modcheck lumio");
        List<String> said = messages(admin);
        assertTrue(said.stream().anyMatch(l -> l.contains("optional")), "the current mode: " + said);
        assertTrue(said.stream().anyMatch(l -> l.contains("not know every mod")), "not required: the owner is told what that costs");
        admin.performCommand("modcheck lumio pflicht");
        assertEquals("required", plugin.getConfig().getString("lumio.mode"));
        said = messages(admin);
        assertTrue(said.stream().anyMatch(l -> l.contains("refused")), "required: who will be refused");
        assertTrue(said.stream().anyMatch(l -> l.contains("download link")), "and that a download link is missing");
        assertFalse(said.stream().anyMatch(l -> l.contains("not know every mod")));
        admin.performCommand("modcheck lumio off");
        assertEquals("off", plugin.getConfig().getString("lumio.mode"));
        admin.performCommand("modcheck lumio nonsense");
        assertEquals("off", plugin.getConfig().getString("lumio.mode"), "an unknown word changes nothing");
        // a moderator may not decide this
        PlayerMock mod = server.addPlayer("Mod");
        mod.addAttachment(plugin, "modcheck.check", true);
        mod.performCommand("modcheck lumio required");
        assertEquals("off", plugin.getConfig().getString("lumio.mode"));
        // the button in the settings goes off -> optional -> required
        plugin.menus().settings(admin);
        admin.simulateInventoryClick(43);
        assertEquals("optional", plugin.getConfig().getString("lumio.mode"));
        plugin.lang().load("de");
        plugin.menus().settings(admin);
        assertNoMissingTexts("settings with Lumio");
        messages(admin);
        admin.performCommand("modcheck lumio");
        assertTrue(messages(admin).stream().anyMatch(l -> l.contains("nicht alle Mods")));
    }
}
