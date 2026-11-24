package dev.bastion;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The file of a player as a menu: who they are, what they came with (clients, mods, packs, Lumio),
 * what the anti-cheat found, every kick, warning and ban, notes, and what can be done.
 */
public final class AkteMenu {
    private final BastionPlugin plugin;

    public AkteMenu(BastionPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private String date(long t) {
        return t <= 0 ? "-" : new SimpleDateFormat(lang().raw("screen.date-format"), Locale.GERMANY).format(new Date(t));
    }

    private static String list(List<String> l, int max) {
        if (l.isEmpty()) return "-";
        List<String> last = l.subList(Math.max(0, l.size() - max), l.size());
        return String.join(", ", last) + (l.size() > max ? " …" : "");
    }

    private ItemStack item(Material m, String key, Object... args) {
        return Menu.item(m, lang().msg("akte." + key + ".name", args), lang().lore("akte." + key + ".lore", args));
    }

    public void open(Player p, UUID id, int page) {
        if (!p.hasPermission("bastion.akte")) {
            lang().send(p, "no-permission");
            return;
        }
        Akte.Entry a = plugin.akte().of(id);
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        Player online = op.getPlayer();
        Punish.Case banned = plugin.punish().caseOf(id);
        Menu m = new Menu(6, lang().msg("akte.title", "player", a.name));
        // who
        List<Component> head = new ArrayList<>(lang().lore("akte.head.lore", "uuid", id.toString(), "first", date(a.first), "last",
                online != null ? lang().raw("akte.online") : date(a.last), "joins", a.joins, "names", list(a.names, 5)));
        if (banned != null) head.add(lang().msg("akte.head.banned", "case", banned.id(), "until",
                banned.until() == 0 ? lang().raw("screen.never") : date(banned.until())));
        m.set(4, Menu.head(op, lang().msg("akte.head.name", "player", a.name), head), null);
        // what they came with
        m.set(19, item(Material.COMPASS, "clients", "list", list(a.clients, 6), "lumio", a.lumio.isEmpty() ? lang().raw("akte.no-lumio") : a.lumio), null);
        m.set(20, item(Material.BOOK, "mods", "count", a.mods.size(), "list", list(a.mods, 8)), e -> mods(p, id, 0));
        m.set(21, item(Material.PAINTING, "packs", "count", a.packs.size(), "list", list(a.packs, 6)), null);
        // the anti-cheat
        var ac = plugin.antiCheat();
        List<Map.Entry<String, Double>> top = new ArrayList<>(a.checks.entrySet());
        top.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        List<Component> acLore = new ArrayList<>(lang().lore("akte.cheat.lore", "caught", a.caught, "now", String.format(Locale.ROOT, "%.0f", ac.total(id)),
                "limit", (int) ac.limit(), "watched", lang().raw(ac.watched(id) ? "word-on" : "word-off")));
        for (int i = 0; i < Math.min(6, top.size()); i++) {
            acLore.add(lang().msg("acmenu.check", "check", lang().raw("anticheat.check." + top.get(i).getKey()),
                    "points", String.format(Locale.ROOT, "%.0f", top.get(i).getValue())));
        }
        acLore.add(Component.empty());
        acLore.add(lang().msg("akte.cheat.click"));
        m.set(22, Menu.item(Material.IRON_SWORD, lang().msg("akte.cheat.name"), acLore), e -> {
            ac.setWatched(id, a.name, !ac.watched(id), p.getName());
            open(p, id, page);
        });
        // punishments
        String next = plugin.punish().stepName(plugin.punish().step(a.caught + 1));
        m.set(23, item(Material.ANVIL, "punish", "kicks", a.kicks, "warns", a.warns, "bans", a.bans, "next", next), null);
        m.set(24, item(Material.WRITABLE_BOOK, "notes", "count", a.notes.size(),
                "last", a.notes.isEmpty() ? "-" : a.notes.get(a.notes.size() - 1).text()), e -> plugin.input().ask(p, "akte.note-ask", text -> {
            String t = Guard.clean(text, 200);
            if (!t.isBlank()) {
                plugin.akte().note(id, p.getName(), t);
                plugin.guard().log(p.getName(), "note", a.name, t);
            }
            open(p, id, page);
        }, () -> open(p, id, page)));
        m.set(25, item(Material.CLOCK, "history", "count", a.events.size()), e -> history(p, id, 0));
        // what can be done
        if (online != null && p.hasPermission("bastion.block")) {
            m.set(38, item(Material.TNT, "act-punish", "next", next), e -> {
                p.closeInventory();
                plugin.guard().log(p.getName(), "punish", a.name, next);
                plugin.punish().caught(id, a.name, lang().raw("akte.staff-reason"), p.getName());
            });
        }
        if (online != null && p.hasPermission("bastion.check")) m.set(39, item(Material.SPYGLASS, "act-check"), e -> plugin.menus().player(p, online));
        if (banned != null && p.hasPermission("bastion.unban")) {
            m.set(40, item(Material.LIME_DYE, "act-unban", "case", banned.id()), e -> {
                plugin.guard().unbanAnywhere(id, a.name, p.getName());
                plugin.punish().unban(id);
                plugin.akte().event(id, "unban", "#" + banned.id(), p.getName());
                lang().send(p, "ban.unbanned", "player", a.name);
                open(p, id, page);
            });
        }
        if (p.hasPermission("bastion.unban")) {
            m.set(41, item(Material.MILK_BUCKET, "act-reset"), e -> {
                a.caught = 0;
                plugin.akte().changed(id);
                plugin.guard().resetWarnings(id);
                ac.reset(id);
                plugin.akte().event(id, "reset", lang().raw("akte.reset-done"), p.getName());
                open(p, id, page);
            });
        }
        if (p.hasPermission("bastion.ranks")) m.set(42, item(Material.NAME_TAG, "act-rights"), e -> plugin.ranks().player(p, id, a.name));
        m.set(49, Menu.item(Material.OAK_DOOR, lang().msg("menu.back.name"), List.of()), e -> plugin.menus().main(p));
        m.fill().open(p);
    }

    private static Material icon(String type) {
        return switch (type) {
            case "kick" -> Material.IRON_DOOR;
            case "warn" -> Material.YELLOW_DYE;
            case "ban" -> Material.BARRIER;
            case "unban", "reset" -> Material.LIME_DYE;
            case "cheat" -> Material.IRON_SWORD;
            case "grim" -> Material.NETHERITE_SWORD;
            case "mod" -> Material.BOOK;
            case "inject" -> Material.SPIDER_EYE;
            case "sign" -> Material.GRAY_DYE;
            case "pack" -> Material.PAINTING;
            case "note" -> Material.WRITABLE_BOOK;
            default -> Material.PAPER;
        };
    }

    /** Everything that happened, newest first. */
    public void history(Player p, UUID id, int page) {
        Akte.Entry a = plugin.akte().of(id);
        List<Akte.Event> events = new ArrayList<>(a.events);
        Collections.reverse(events);
        plugin.menus().pagedList(lang().msg("akte.history-title", "player", a.name), events, page, ev -> {
            List<Component> lore = new ArrayList<>();
            for (String line : wrap(ev.text(), 38)) lore.add(lang().msg("akte.line", "text", line));
            lore.add(lang().msg("akte.by", "by", ev.by()));
            String type = lang().raw("akte.type." + ev.type());
            return Menu.item(icon(ev.type()), lang().msg("akte.event", "date", date(ev.time()), "type",
                    type.contains("missing text") ? ev.type() : type), lore);
        }, null, n -> history(p, id, n), () -> open(p, id, 0)).open(p);
    }

    public void mods(Player p, UUID id, int page) {
        Akte.Entry a = plugin.akte().of(id);
        plugin.menus().pagedList(lang().msg("akte.mods-title", "player", a.name), a.mods, page,
                mod -> Menu.item(plugin.guard().rule(mod) != null ? Material.RED_CONCRETE : Material.BOOK, Component.text(plugin.guard().display(mod)), List.of()),
                null, n -> mods(p, id, n), () -> open(p, id, 0)).open(p);
    }

    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > width && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder();
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return out.subList(0, Math.min(6, out.size()));
    }
}
