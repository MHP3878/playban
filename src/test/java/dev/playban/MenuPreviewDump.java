package dev.playban;

import com.google.gson.GsonBuilder;
import dev.playban.gui.Menu;
import dev.playban.voice.VoiceBackend;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes every menu as data (slots, names, lore, colors) so a preview page can be drawn from it.
 * Only runs when asked for: mvn test -Dtest=MenuPreviewDump -Dplayban.dump=menus.json
 */
class MenuPreviewDump {
    private ServerMock server;
    private PlayBanPlugin plugin;
    private PlayerMock admin, troll, mia, ben;
    private final List<Map<String, Object>> menus = new ArrayList<>();
    private String language;

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Turns a chat component into simple HTML that keeps colors and bold text. */
    private static String html(Component c, String inheritedColor) {
        if (c == null) return "";
        TextColor color = c.color();
        String hex = color != null ? color.asHexString() : inheritedColor;
        StringBuilder sb = new StringBuilder();
        String text = c instanceof TextComponent t ? t.content() : "";
        boolean bold = c.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE;
        boolean underlined = c.decoration(TextDecoration.UNDERLINED) == TextDecoration.State.TRUE;
        if (!text.isEmpty()) {
            String style = (hex != null ? "color:" + hex + ";" : "") + (bold ? "font-weight:700;" : "") + (underlined ? "text-decoration:underline;" : "");
            sb.append(style.isEmpty() ? esc(text) : "<span style=\"" + style + "\">" + esc(text) + "</span>");
        }
        for (Component child : c.children()) {
            String inner = html(child, hex);
            sb.append(bold && !inner.isEmpty() ? "<b>" + inner + "</b>" : inner);
        }
        return sb.toString();
    }

    private void snap(String id, PlayerMock viewer) {
        var view = viewer.getOpenInventory();
        if (!(view.getTopInventory().getHolder() instanceof Menu)) throw new IllegalStateException("no menu open for " + id);
        Map<String, Object> menu = new LinkedHashMap<>();
        menu.put("id", id);
        menu.put("lang", language);
        menu.put("title", html(view.title(), null));
        List<Object> slots = new ArrayList<>();
        for (ItemStack it : view.getTopInventory().getContents()) {
            if (it == null) {
                slots.add(null);
                continue;
            }
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("m", it.getType().name());
            if (it.getAmount() > 1) slot.put("n", it.getAmount());
            var meta = it.getItemMeta();
            if (meta != null) {
                if (meta.hasDisplayName()) slot.put("name", html(meta.displayName(), "#ffffff"));
                if (meta.hasLore()) {
                    List<String> lore = new ArrayList<>();
                    for (Component line : meta.lore()) lore.add(html(line, "#aa00aa"));
                    slot.put("lore", lore);
                }
                if (meta instanceof SkullMeta skull && skull.getOwningPlayer() != null && skull.getOwningPlayer().getName() != null) {
                    slot.put("head", skull.getOwningPlayer().getName());
                }
            }
            slots.add(slot);
        }
        menu.put("slots", slots);
        menus.add(menu);
    }

    private void type(PlayerMock player, String text) {
        player.chat(text);
        server.getScheduler().waitAsyncEventsFinished();
        server.getScheduler().performTicks(2);
    }

    private int slotOf(PlayerMock viewer, Material material) {
        var inv = viewer.getOpenInventory().getTopInventory();
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) != null && inv.getItem(i).getType() == material) return i;
        throw new IllegalStateException("no " + material);
    }

    /** A voice chat that only lives in memory, so the voice menus have something to show. */
    private final class DemoVoice implements VoiceBackend {
        final Map<UUID, String> names = new LinkedHashMap<>();
        final Map<UUID, UUID> membership = new HashMap<>();
        final Map<UUID, String> passwords = new HashMap<>();

        public boolean available() { return true; }
        public List<GroupInfo> groups() {
            List<GroupInfo> out = new ArrayList<>();
            for (var e : names.entrySet()) {
                List<UUID> members = new ArrayList<>();
                for (var m : membership.entrySet()) if (m.getValue().equals(e.getKey())) members.add(m.getKey());
                out.add(new GroupInfo(e.getKey(), e.getValue(), passwords.containsKey(e.getKey()), false, false, members));
            }
            return out;
        }
        public UUID groupOf(UUID player) { return membership.get(player); }
        public boolean setGroup(UUID player, UUID group) {
            if (group == null) membership.remove(player); else membership.put(player, group);
            return true;
        }
        public UUID createGroup(String name, String password, boolean hidden) {
            UUID id = UUID.randomUUID();
            names.put(id, name);
            if (password != null) passwords.put(id, password);
            return id;
        }
        public boolean setPassword(UUID group, String password) {
            if (password == null) passwords.remove(group); else passwords.put(group, password);
            return true;
        }
        public void removeGroup(UUID group) { names.remove(group); }
        public void refresh(UUID player) {}
    }

    private void run(String code) {
        language = code;
        boolean de = code.equals("de");
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PlayBanPlugin.class);
        plugin.lang().load(code);
        admin = server.addPlayer("Julian");
        admin.setOp(true);
        troll = server.addPlayer("Troll_42");
        mia = server.addPlayer("Mia");
        ben = server.addPlayer("Ben");
        server.getScheduler().performTicks(30);

        // ---- some history so the menus are not empty
        admin.performCommand("playban note Troll_42 " + (de ? "War im Chat mehrfach beleidigend." : "Was insulting in chat several times."));
        admin.performCommand("playban note Troll_42 " + (de ? "Hat sich bei Mia entschuldigt." : "Apologized to Mia."));
        admin.performCommand("playban apply Troll_42 spam");
        admin.performCommand("playban ban Ben 7d " + (de ? "Werbung für andere Server" : "Advertising other servers"));

        // ---- team menus
        admin.performCommand("playban menu");
        snap("main", admin);
        admin.performCommand("playban Troll_42");
        snap("category", admin);
        admin.simulateInventoryClick(51);
        snap("duration", admin);
        admin.performCommand("playban Troll_42");
        admin.simulateInventoryClick(slotOf(admin, Material.TNT));
        admin.simulateInventoryClick(slotOf(admin, Material.PLAYER_HEAD));
        snap("confirm", admin);
        admin.simulateInventoryClick(slotOf(admin, Material.LIME_CONCRETE));
        server.getScheduler().performTicks(20 * 90);
        plugin.adminMenus().active(admin, 0);
        snap("active", admin);
        plugin.adminMenus().detail(admin, troll.getUniqueId());
        snap("detail", admin);
        plugin.adminMenus().bans(admin, 0);
        snap("bans", admin);
        plugin.adminMenus().profile(admin, troll.getUniqueId(), "Troll_42");
        snap("profile", admin);
        plugin.adminMenus().history(admin, "Troll_42");
        snap("history", admin);
        plugin.adminMenus().categories(admin, 0);
        snap("categories", admin);
        plugin.adminMenus().categoryEdit(admin, "cheating");
        snap("category-edit", admin);
        plugin.adminMenus().tasks(admin, 0);
        snap("tasks", admin);
        plugin.adminMenus().taskEdit(admin, "nice-message");
        snap("task-edit", admin);
        plugin.adminMenus().settings(admin);
        snap("settings", admin);
        plugin.adminMenus().banScreen(admin, 0);
        snap("ban-screen", admin);

        // ---- what the punished player sees
        troll.performCommand("playban");
        snap("status", troll);
        plugin.playerMenus().quiz(troll, plugin.tasks().get("rules-quiz"), 0);
        snap("quiz", troll);
        troll.closeInventory();

        // ---- tickets
        mia.performCommand("ticket");
        snap("ticket-new", mia);
        mia.simulateInventoryClick(slotOf(mia, Material.PLAYER_HEAD));
        type(mia, de ? "Troll_42 hat mein Haus zerstört und meine Kisten geleert." : "Troll_42 destroyed my house and emptied my chests.");
        ben.performCommand("ticket " + (de ? "Ich finde meinen Bann unfair." : "I think my ban is unfair."));
        plugin.tickets().create(troll, "question", de ? "Wie lange dauert meine Strafe noch?" : "How long is my punishment?");
        admin.performCommand("ticket show");
        snap("tickets", admin);
        admin.performCommand("ticket open 1");
        snap("ticket-intake", admin);
        admin.simulateInventoryClick(slotOf(admin, Material.LIME_CONCRETE));
        var t = plugin.tickets().get(1);
        plugin.tickets().staffMessage(t, admin, de ? "Hallo Mia, ich schaue mir das an. Wann ist es passiert?" : "Hi Mia, I am looking into it. When did it happen?", null);
        type(mia, de ? "Gestern Abend, als ich offline war." : "Last night while I was offline.");
        type(mia, de ? "Er hat sogar ein Schild hingestellt: haha" : "He even put up a sign: haha");
        plugin.tickets().toggleStar(t, t.log.get(t.log.size() - 1));
        plugin.tickets().note(t, "Julian", de ? "Block-Log geprüft: Troll_42 war um 22:14 dort." : "Checked the block log: Troll_42 was there at 22:14.");
        plugin.tickets().addParticipant(t, admin, troll.getUniqueId(), "Troll_42");
        type(troll, de ? "Das war nur Spaß." : "It was just a joke.");
        plugin.tickets().teleport(t, admin, t.participants.get(0), "VANISH");
        plugin.tickets().accept(plugin.tickets().get(3), admin);
        plugin.ticketMenus().view(admin, t);
        snap("ticket-window", admin);
        admin.simulateInventoryClick(50);
        snap("ticket-teleport", admin);
        plugin.ticketMenus().view(admin, t);
        admin.simulateInventoryClick(admin.getOpenInventory(), org.bukkit.event.inventory.ClickType.RIGHT, 45);
        snap("ticket-replies", admin);
        plugin.tickets().setVerdict(t, t.participants.get(0), "INVOLVED");
        plugin.ticketMenus().verdicts(admin, t);
        snap("ticket-close", admin);
        admin.simulateInventoryClick(11);
        snap("ticket-verdict", admin);
        admin.simulateInventoryClick(slotOf(admin, Material.TNT));
        admin.simulateInventoryClick(slotOf(admin, Material.LIME_CONCRETE));
        snap("ticket-closed", admin);
        plugin.ticketMenus().stats(admin);
        snap("ticket-stats", admin);
        plugin.ticketMenus().settings(admin);
        snap("ticket-settings", admin);

        // ---- voice
        DemoVoice voice = new DemoVoice();
        plugin.voice().use(voice);
        plugin.voice().joined(admin);
        UUID g1 = voice.createGroup(de ? "Bau-Team" : "Build team", null, false);
        plugin.voice().created(mia.getUniqueId(), g1);
        voice.setGroup(mia.getUniqueId(), g1);
        voice.setGroup(troll.getUniqueId(), g1);
        UUID g2 = voice.createGroup("PvP", null, false);
        plugin.voice().created(troll.getUniqueId(), g2);
        plugin.voice().setPassword(g2, "arena7");
        plugin.voice().setMuted(troll.getUniqueId(), true, "Julian");
        plugin.voice().join(admin, g1, true);
        admin.performCommand("voice");
        snap("voice", admin);
        plugin.voiceMenus().group(admin, g1);
        snap("voice-group", admin);
        admin.simulateInventoryClick(1);
        snap("voice-member", admin);
        mia.performCommand("voice manage");
        snap("voice-manage", mia);
        ben.performCommand("voice report");
        snap("voice-report", ben);
        ben.simulateInventoryClick(0);
        snap("voice-report-why", ben);

        MockBukkit.unmock();
    }

    @Test
    void dump() throws Exception {
        String target = System.getProperty("playban.dump");
        Assumptions.assumeTrue(target != null && !target.isBlank(), "only runs when -Dplayban.dump=<file> is given");
        run("de");
        run("en");
        Files.writeString(Path.of(target), new GsonBuilder().disableHtmlEscaping().create().toJson(menus), StandardCharsets.UTF_8);
    }
}
