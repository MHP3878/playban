package dev.modcheck;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The windows a player gets in the check room: "Lumio is needed" with a download button and a guide,
 * or "forbidden mod found". On servers from 1.21.6 on they are real Minecraft dialogs (a window with
 * buttons, opened by the server); before that a book with the same buttons.
 */
public final class Popups {
    public static final String FABRIC_INSTALLER = "https://fabricmc.net/use/installer/";
    public static final String FABRIC_API = "https://modrinth.com/mod/fabric-api";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final ModCheckPlugin plugin;

    public Popups(ModCheckPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    /** Whether this server can open dialogs (Minecraft 1.21.6 and newer). */
    static boolean dialogs() {
        return atLeast(Bukkit.getMinecraftVersion(), 1, 21, 6);
    }

    static boolean atLeast(String version, int major, int minor, int patch) {
        String[] v = version.split("[^0-9]+");
        int[] want = {major, minor, patch};
        for (int i = 0; i < 3; i++) {
            int have = i < v.length && !v[i].isEmpty() ? Integer.parseInt(v[i]) : 0;
            if (have != want[i]) return have > want[i];
        }
        return true;
    }

    private String download() {
        String d = plugin.getConfig().getString("lumio.download", "").trim();
        return d.startsWith("https://") ? d : "";
    }

    // ---------------------------------------------------------------- the windows

    /** Lumio is missing ("missing"), did not answer ("noreport") or is too old ("outdated"). */
    public void lumio(Player p, String why) {
        String link = download();
        if (dialogs()) {
            JsonObject d = base("window." + why + ".title");
            JsonArray body = new JsonArray();
            for (String para : lang().raw("window." + why + ".text").split("\n\n")) body.add(text(para));
            if (link.isEmpty()) body.add(text(lang().raw("window.no-link")));
            d.add("body", body);
            JsonArray actions = new JsonArray();
            if (!link.isEmpty()) actions.add(button("window.download", "window.download-tip", url(link)));
            actions.add(button("window.guide", "window.guide-tip", command("/mcroom guide")));
            d.addProperty("type", "minecraft:multi_action");
            d.add("actions", actions);
            d.addProperty("columns", 1);
            d.add("exit_action", button("window.leave", null, command("/mcroom leave")));
            show(p, d);
            return;
        }
        List<Component> pages = new ArrayList<>();
        var first = Component.text().append(lang().msg("book." + why)).append(Component.newline()).append(Component.newline());
        if (!link.isEmpty()) first.append(lang().msg("book.download").clickEvent(ClickEvent.openUrl(link))).append(Component.newline());
        else first.append(lang().msg("book.no-link")).append(Component.newline());
        first.append(lang().msg("book.guide").clickEvent(ClickEvent.changePage(2))).append(Component.newline());
        first.append(lang().msg("book.leave").clickEvent(ClickEvent.runCommand("/mcroom leave")));
        pages.add(first.build());
        pages.add(guidePage(link));
        p.openBook(Book.book(Component.text("Lumio"), Component.text("ModCheck"), pages));
    }

    /** The guide: install Fabric, Fabric API and Lumio, in five short steps with buttons. */
    public void guide(Player p) {
        String link = download();
        if (dialogs()) {
            JsonObject d = base("window.guide-title");
            JsonArray body = new JsonArray();
            for (String step : lang().raw("window.guide-steps").split("\n")) body.add(text(step));
            d.add("body", body);
            JsonArray actions = new JsonArray();
            actions.add(button("window.fabric", null, url(FABRIC_INSTALLER)));
            actions.add(button("window.fabric-api", null, url(FABRIC_API)));
            if (!link.isEmpty()) actions.add(button("window.download", null, url(link)));
            d.addProperty("type", "minecraft:multi_action");
            d.add("actions", actions);
            d.addProperty("columns", 1);
            d.add("exit_action", button("window.back", null, command("/mcroom window")));
            show(p, d);
            return;
        }
        p.openBook(Book.book(Component.text("Lumio"), Component.text("ModCheck"), List.of(guidePage(link))));
    }

    /** A forbidden mod: what it is, and that the player can only leave and come back without it. */
    public void forbidden(Player p, String mods) {
        if (dialogs()) {
            JsonObject d = base("window.forbidden.title");
            JsonArray body = new JsonArray();
            for (String para : lang().raw("window.forbidden.text").replace("{mods}",
                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(mods)).split("\n\n")) body.add(text(para));
            d.add("body", body);
            d.addProperty("type", "minecraft:notice");
            d.add("action", button("window.leave", null, command("/mcroom leave")));
            show(p, d);
            return;
        }
        var page = Component.text().append(lang().msg("book.forbidden", "mods", mods)).append(Component.newline()).append(Component.newline())
                .append(lang().msg("book.leave").clickEvent(ClickEvent.runCommand("/mcroom leave")));
        p.openBook(Book.book(Component.text("ModCheck"), Component.text("ModCheck"), List.of(page.build())));
    }

    private Component guidePage(String link) {
        var b = Component.text().append(lang().msg("book.guide-steps")).append(Component.newline());
        b.append(lang().msg("book.fabric").clickEvent(ClickEvent.openUrl(FABRIC_INSTALLER))).append(Component.newline());
        b.append(lang().msg("book.fabric-api").clickEvent(ClickEvent.openUrl(FABRIC_API))).append(Component.newline());
        if (!link.isEmpty()) b.append(lang().msg("book.lumio").clickEvent(ClickEvent.openUrl(link)));
        return b.build();
    }

    // ---------------------------------------------------------------- building a dialog

    private JsonObject base(String titleKey) {
        JsonObject d = new JsonObject();
        d.add("title", component(lang().msg(titleKey)));
        d.add("external_title", component(lang().msg(titleKey)));
        d.addProperty("can_close_with_escape", false);
        d.addProperty("pause", false);
        d.addProperty("after_action", "none");
        return d;
    }

    private JsonObject text(String miniMessage) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:plain_message");
        o.add("contents", component(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(miniMessage)));
        o.addProperty("width", 300);
        return o;
    }

    private JsonObject button(String labelKey, String tipKey, JsonObject action) {
        JsonObject o = new JsonObject();
        o.add("label", component(lang().msg(labelKey)));
        if (tipKey != null) o.add("tooltip", component(lang().msg(tipKey)));
        o.addProperty("width", 220);
        if (action != null) o.add("action", action);
        return o;
    }

    private static JsonObject url(String link) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "open_url");
        o.addProperty("url", link);
        return o;
    }

    private static JsonObject command(String cmd) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "run_command");
        o.addProperty("command", cmd);
        return o;
    }

    private static JsonElement component(Component c) {
        return GsonComponentSerializer.gson().serializeToTree(c);
    }

    /** Opens the dialog through the game's own command; it never contains a line break, so it stays one argument. */
    void show(Player p, JsonObject dialog) {
        String json = GSON.toJson(dialog).replace("\\n", " ");
        Bukkit.dispatchCommand(quiet(), "minecraft:dialog show " + p.getName() + " " + json);
    }

    /** Closes an open window (only for a preview; in the check room the window stays). */
    void close(Player p) {
        if (dialogs()) Bukkit.dispatchCommand(quiet(), "minecraft:dialog clear " + p.getName());
        else p.closeInventory();
    }

    /** Runs the game's command without "[Server: dialog shown ...]" for every admin in chat. */
    private static org.bukkit.command.CommandSender quiet() {
        try {
            return Bukkit.createCommandSender(feedback -> { });
        } catch (RuntimeException | LinkageError e) {
            return Bukkit.getConsoleSender();
        }
    }

    /** For tests: the dialog as it would be sent. */
    static String json(JsonObject dialog) {
        return GSON.toJson(dialog);
    }
}
