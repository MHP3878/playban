package dev.playban.gui;

import dev.playban.Lang;
import dev.playban.PlayBanPlugin;
import dev.playban.TaskActions;
import dev.playban.CategoryRegistry;
import dev.playban.TimeUtil;
import dev.playban.model.Category;
import dev.playban.model.Note;
import dev.playban.model.Punishment;
import dev.playban.model.Punishment.TaskProgress;
import dev.playban.model.Review;
import dev.playban.model.TaskDef;
import dev.playban.model.TaskType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/** All menus for the server team: punishing, reviews, tickets, the task editor and the settings. */
public final class AdminMenus {
    /** What the punish wizard has collected so far. */
    private static final class Draft {
        UUID target;
        String targetName;
        String type = "PLAYBAN";
        Category category;
        long ms;
        String reason;
        UUID victim;
        String victimName;
    }

    private static final String[] TOGGLES = {
            "restrictions.hide-from-players", "restrictions.block-break", "restrictions.block-place",
            "restrictions.interact", "restrictions.damage", "restrictions.invulnerable",
            "restrictions.item-drop", "restrictions.item-pickup", "restrictions.chat",
            "restrictions.commands", "restrictions.mob-target",
            "count-only-online-time", "staff-see-punished", "auto-approve-messages",
            "limbo.blind", "limbo.hide-others",
            "restrictions.adventure-mode", "restrictions.action-bar", "restrictions.slow", "limbo.lock-view", "short-commands"
    };
    private static final int[] TOGGLE_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 28, 29, 30, 24, 25, 23, 31, 39, 40, 42};

    private final PlayBanPlugin plugin;

    public AdminMenus(PlayBanPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    ItemStack item(Material material, String key, Object... kv) {
        return Items.of(material, lang().msg(key + ".name", kv), lang().lore(key + ".lore", kv));
    }

    String date(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(millis));
    }

    private boolean admin(Player p) {
        return p.hasPermission("playban.admin");
    }

    /** A 6-row list with page buttons in the bottom row. */
    <T> Menu paged(Component title, List<T> entries, int page, Function<T, ItemStack> render,
                           BiConsumer<T, InventoryClickEvent> click, IntConsumer openPage, Runnable back) {
        Menu m = new Menu(6, title);
        int pages = Math.max(1, (entries.size() + 44) / 45);
        int current = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < 45; i++) {
            int index = current * 45 + i;
            if (index >= entries.size()) break;
            T entry = entries.get(index);
            m.set(i, render.apply(entry), click == null ? null : e -> click.accept(entry, e));
        }
        for (int i = 45; i < 54; i++) m.set(i, Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        if (current > 0) m.set(45, item(Material.ARROW, "menu.prev"), e -> openPage.accept(current - 1));
        if (current < pages - 1) m.set(53, item(Material.ARROW, "menu.next"), e -> openPage.accept(current + 1));
        m.set(49, item(Material.OAK_DOOR, "menu.back"), e -> back.run());
        m.set(47, Items.of(Material.MAP, lang().msg("menu.page", "page", current + 1, "pages", pages), null));
        return m;
    }

    // ---------------------------------------------------------------- main

    public void main(Player p) {
        Menu m = new Menu(5, lang().msg("menu.main.title"));
        m.set(10, item(Material.ANVIL, "menu.main.punish"), e -> selectPlayer(p, 0));
        m.set(11, item(Material.CLOCK, "menu.main.active", "count", plugin.store().active.size()), e -> active(p, 0));
        m.set(12, item(Material.IRON_BARS, "menu.main.bans", "count", plugin.store().bans.size()), e -> bans(p, 0));
        m.set(14, item(Material.WRITABLE_BOOK, "menu.main.reviews", "count", plugin.store().reviews.size()), e -> reviews(p, 0));
        m.set(15, item(Material.PAPER, "menu.main.tickets", "count", plugin.tickets().count(dev.playban.model.Ticket.Status.OPEN),
                "progress", plugin.tickets().count(dev.playban.model.Ticket.Status.IN_PROGRESS)), e -> plugin.ticketMenus().list(p, "ACTIVE", null, 0));
        m.set(40, item(Material.KNOWLEDGE_BOOK, "menu.main.guide"), e -> guide(p, 0));
        m.set(16, item(Material.BOOK, "menu.main.history"), e ->
                plugin.input().ask(p, "prompt.history-name", name -> profileByName(p, name), () -> main(p)));
        if (plugin.voice().available() && p.hasPermission("playban.voice")) {
            m.set(22, item(Material.JUKEBOX, "menu.main.voice", "count", plugin.voice().groups().size()), e -> plugin.voiceMenus().groups(p));
        }
        if (admin(p)) {
            m.set(28, item(Material.LECTERN, "menu.main.categories", "count", plugin.categories().all().size()), e -> categories(p, 0));
            m.set(30, item(Material.BOOKSHELF, "menu.main.tasks", "count", plugin.tasks().all().size()), e -> tasks(p, 0));
            m.set(32, item(Material.COMPARATOR, "menu.main.settings"), e -> settings(p));
            m.set(34, item(Material.GLOBE_BANNER_PATTERN, "menu.main.language", "language", lang().code()), e -> {
                cycleLanguage();
                main(p);
            });
        }
        m.fill().open(p);
    }

    private void cycleLanguage() {
        List<String> codes = lang().available();
        String next = codes.get((codes.indexOf(lang().code()) + 1) % codes.size());
        plugin.getConfig().set("language", next);
        plugin.saveConfig();
        lang().load(next);
    }

    // ---------------------------------------------------------------- punish wizard

    public void selectPlayer(Player p, int page) {
        List<Player> candidates = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (plugin.punishments().isPunished(online.getUniqueId())) continue;
            if (online.hasPermission("playban.exempt")) continue;
            candidates.add(online);
        }
        Menu m = paged(lang().msg("menu.select-player.title"), candidates, page,
                c -> Items.head(c, lang().msg("menu.select-player.entry.name", "player", c.getName()), lang().lore("menu.select-player.entry.lore")),
                (c, e) -> {
                    Draft d = new Draft();
                    d.target = c.getUniqueId();
                    d.targetName = c.getName();
                    chooseCategory(p, d);
                },
                n -> selectPlayer(p, n), () -> main(p));
        m.set(51, item(Material.NAME_TAG, "menu.select-player.by-name"), e ->
                plugin.input().ask(p, "prompt.player-name", name -> {
                    OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
                    if (off == null || off.getName() == null) {
                        lang().send(p, "error.unknown-player", "player", name);
                        return;
                    }
                    Player online = off.getPlayer();
                    if (online != null && online.hasPermission("playban.exempt")) {
                        lang().send(p, "error.exempt", "player", off.getName());
                        return;
                    }
                    Draft d = new Draft();
                    d.target = off.getUniqueId();
                    d.targetName = off.getName();
                    chooseCategory(p, d);
                }, () -> selectPlayer(p, 0)));
        m.open(p);
    }

    /** Entry point for /playban <player>. */
    public void punishPlayer(Player p, UUID target, String targetName) {
        Draft d = new Draft();
        d.target = target;
        d.targetName = targetName;
        chooseCategory(p, d);
    }

    /** The staff member picks what the player did; the plugin works out the punishment. */
    private void chooseCategory(Player p, Draft d) {
        d.category = null;
        d.type = "PLAYBAN";
        Menu m = paged(lang().msg("menu.category.title", "player", d.targetName), plugin.categories().all(), 0, c -> {
            Category.Step step = plugin.punishments().nextStep(d.target, c);
            int n = plugin.punishments().countOffenses(d.target, c) + 1;
            return Items.of(c.icon, lang().msg("menu.category.entry.name", "category", c.name),
                    lang().lore("menu.category.entry.lore", "n", n,
                            "punishment", step == null ? lang().raw("none") : plugin.punishments().describe(step.type, step.durationMs)));
        }, (c, e) -> {
            Category.Step step = plugin.punishments().nextStep(d.target, c);
            if (step == null) return;
            d.category = c;
            d.type = step.type;
            d.ms = step.durationMs;
            d.reason = c.name;
            if (step.type.equals("PLAYBAN")) selectVictim(p, d, 0);
            else confirm(p, d);
        }, n -> {}, () -> selectPlayer(p, 0));
        m.set(51, item(Material.NAME_TAG, "menu.category.custom-playban"), e -> {
            d.type = "PLAYBAN";
            selectDuration(p, d);
        });
        m.set(50, item(Material.ENDER_EYE, "menu.category.custom-specban"), e -> {
            d.type = "SPECBAN";
            selectDuration(p, d);
        });
        m.set(52, item(Material.IRON_BARS, "menu.category.custom-ban"), e -> {
            d.type = "BAN";
            selectDuration(p, d);
        });
        m.set(53, item(Material.BELL, "menu.category.custom-warn"), e ->
                plugin.input().ask(p, "prompt.reason", text -> {
                    d.type = "WARN";
                    d.reason = text;
                    confirm(p, d);
                }, () -> chooseCategory(p, d)));
        m.open(p);
    }

    private void selectDuration(Player p, Draft d) {
        Menu m = new Menu(4, lang().msg("menu.duration.title", "player", d.targetName));
        int slot = 10;
        for (String preset : plugin.getConfig().getStringList("duration-presets")) {
            long ms = TimeUtil.parse(preset);
            if (ms <= 0 || slot > 16) continue;
            m.set(slot++, item(Material.CLOCK, "menu.duration.preset", "time", TimeUtil.format(ms)), e -> {
                d.ms = ms;
                selectReason(p, d);
            });
        }
        m.set(22, item(Material.NAME_TAG, "menu.duration.custom"), e ->
                plugin.input().ask(p, "prompt.duration", text -> {
                    long ms = TimeUtil.parse(text);
                    if (ms <= 0) {
                        lang().send(p, "error.bad-time", "input", text);
                        selectDuration(p, d);
                        return;
                    }
                    d.ms = ms;
                    selectReason(p, d);
                }, () -> selectDuration(p, d)));
        if (d.type.equals("BAN") || d.type.equals("SPECBAN")) {
            m.set(23, item(Material.BEDROCK, "menu.duration.permanent"), e -> {
                d.ms = 0;
                selectReason(p, d);
            });
        }
        m.set(31, item(Material.OAK_DOOR, "menu.back"), e -> chooseCategory(p, d));
        m.fill().open(p);
    }

    private void selectReason(Player p, Draft d) {
        Menu m = new Menu(4, lang().msg("menu.reason.title", "player", d.targetName));
        int slot = 10;
        for (String preset : plugin.getConfig().getStringList("reason-presets")) {
            if (slot > 16) break;
            m.set(slot++, item(Material.PAPER, "menu.reason.preset", "reason", preset), e -> {
                d.reason = preset;
                if (d.type.equals("PLAYBAN")) selectVictim(p, d, 0);
                else confirm(p, d);
            });
        }
        m.set(22, item(Material.NAME_TAG, "menu.reason.custom"), e ->
                plugin.input().ask(p, "prompt.reason", text -> {
                    d.reason = text;
                    if (d.type.equals("PLAYBAN")) selectVictim(p, d, 0);
                    else confirm(p, d);
                }, () -> selectReason(p, d)));
        m.set(31, item(Material.OAK_DOOR, "menu.back"), e -> selectDuration(p, d));
        m.fill().open(p);
    }

    private void selectVictim(Player p, Draft d, int page) {
        List<Player> candidates = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(d.target)) candidates.add(online);
        }
        Menu m = paged(lang().msg("menu.victim.title"), candidates, page,
                c -> Items.head(c, lang().msg("menu.victim.entry.name", "player", c.getName()), lang().lore("menu.victim.entry.lore")),
                (c, e) -> {
                    d.victim = c.getUniqueId();
                    d.victimName = c.getName();
                    confirm(p, d);
                },
                n -> selectVictim(p, d, n), () -> {
                    if (d.category != null) chooseCategory(p, d);
                    else selectReason(p, d);
                });
        m.set(51, item(Material.BARRIER, "menu.victim.none"), e -> {
            d.victim = null;
            d.victimName = null;
            confirm(p, d);
        });
        m.open(p);
    }

    private void confirm(Player p, Draft d) {
        Menu m = new Menu(3, lang().msg("menu.confirm.title"));
        OfflinePlayer target = Bukkit.getOfflinePlayer(d.target);
        m.set(13, Items.head(target, lang().msg("menu.confirm.summary.name", "player", d.targetName),
                lang().lore("menu.confirm.summary.lore", "punishment", plugin.punishments().describe(d.type, d.ms), "reason", d.reason,
                        "victim", d.victimName == null ? lang().raw("none") : d.victimName)));
        m.set(11, item(Material.LIME_CONCRETE, "menu.confirm.accept"), e -> {
            p.closeInventory();
            String error = plugin.punishments().execute(d.target, d.targetName, d.type, d.ms, d.reason, p.getName(),
                    d.category == null ? null : d.category.id, d.victim, d.victimName);
            if (error != null) lang().send(p, error, "player", d.targetName);
        });
        m.set(15, item(Material.RED_CONCRETE, "menu.confirm.deny"), e -> main(p));
        m.fill().open(p);
    }

    // ---------------------------------------------------------------- active punishments

    private List<Component> punishmentLore(Punishment pun) {
        return lang().lore("menu.active.entry.lore",
                "time", TimeUtil.format(Math.max(0, pun.remainingMs)), "original", TimeUtil.format(pun.originalMs),
                "reason", pun.reason, "staff", pun.staff, "date", date(pun.createdAt),
                "done", pun.tasksDone(), "total", pun.tasks.size(),
                "victim", pun.victimName == null ? lang().raw("none") : pun.victimName,
                "online", lang().msg(Bukkit.getPlayer(pun.uuid) != null ? "online" : "offline"));
    }

    public void active(Player p, int page) {
        List<Punishment> list = new ArrayList<>(plugin.store().active.values());
        Menu m = paged(lang().msg("menu.active.title"), list, page,
                pun -> Items.head(Bukkit.getOfflinePlayer(pun.uuid), lang().msg("menu.active.entry.name", "player", pun.name), punishmentLore(pun)),
                (pun, e) -> detail(p, pun.uuid),
                n -> active(p, n), () -> main(p));
        if (list.isEmpty()) m.set(22, item(Material.LIME_DYE, "menu.active.empty"));
        m.open(p);
    }

    public void detail(Player p, UUID id) {
        Punishment pun = plugin.punishments().get(id);
        if (pun == null) {
            active(p, 0);
            return;
        }
        Menu m = new Menu(6, lang().msg("menu.detail.title", "player", pun.name));
        m.set(4, Items.head(Bukkit.getOfflinePlayer(pun.uuid), lang().msg("menu.active.entry.name", "player", pun.name), punishmentLore(pun)));
        long[] steps = {-86_400_000L, -3_600_000L, -600_000L, 600_000L, 3_600_000L, 86_400_000L};
        int[] slots = {19, 20, 21, 23, 24, 25};
        for (int i = 0; i < steps.length; i++) {
            long step = steps[i];
            m.set(slots[i], item(step < 0 ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                    step < 0 ? "menu.detail.remove" : "menu.detail.add", "time", TimeUtil.format(Math.abs(step))), e -> {
                plugin.punishments().changeTime(pun, step, p.getName());
                detail(p, id);
            });
        }
        m.set(22, item(Material.NAME_TAG, "menu.detail.custom"), e ->
                plugin.input().ask(p, "prompt.change-time", text -> {
                    boolean minus = text.startsWith("-");
                    long ms = TimeUtil.parse(text.replaceFirst("^[+-]", ""));
                    if (ms <= 0) lang().send(p, "error.bad-time", "input", text);
                    else if (plugin.punishments().isPunished(id)) plugin.punishments().changeTime(pun, minus ? -ms : ms, p.getName());
                    detail(p, id);
                }, () -> detail(p, id)));
        int slot = 37;
        for (Map.Entry<String, TaskProgress> e : pun.tasks.entrySet()) {
            TaskDef def = plugin.tasks().get(e.getKey());
            if (def == null || slot > 43) continue;
            List<Component> lore = new ArrayList<>(plugin.playerMenus().describe(def, pun));
            lore.add(lang().msg("status." + e.getValue().status.name()));
            m.set(slot++, Items.of(def.type.icon, plugin.punishments().taskName(def, pun), lore));
        }
        m.set(48, item(Material.MILK_BUCKET, "menu.detail.pardon"), e -> {
            plugin.punishments().end(pun, "PARDONED", p.getName());
            active(p, 0);
        });
        m.set(49, item(Material.BOOK, "menu.detail.profile"), e -> profile(p, pun.uuid, pun.name));
        m.set(50, item(Material.OAK_DOOR, "menu.back"), e -> active(p, 0));
        m.fill().open(p);
    }

    // ---------------------------------------------------------------- command guide

    /** One command in the guide: what you type, an icon, who may use it ("" = everyone). */
    private record GuideEntry(String id, String usage, Material icon, String permission) {}

    private static final List<GuideEntry> GUIDE = List.of(
            new GuideEntry("menu", "/playban menu", Material.COMPASS, "playban.staff"),
            new GuideEntry("punish", "/punish <player>", Material.ANVIL, "playban.staff"),
            new GuideEntry("apply", "/playban apply <player> <category>", Material.LECTERN, "playban.staff"),
            new GuideEntry("ban", "/ban <player> <time|perm> [reason]", Material.IRON_BARS, "playban.staff"),
            new GuideEntry("specban", "/playban specban <player> <time|perm> [reason]", Material.ENDER_EYE, "playban.staff"),
            new GuideEntry("warn", "/warn <player> [reason]", Material.BELL, "playban.staff"),
            new GuideEntry("unban", "/unban <player|ban-ID>", Material.MILK_BUCKET, "playban.staff"),
            new GuideEntry("pardon", "/playban pardon <player>", Material.LIME_DYE, "playban.staff"),
            new GuideEntry("time", "/playban addtime|removetime <player> <time>", Material.CLOCK, "playban.staff"),
            new GuideEntry("note", "/playban note <player> <text>", Material.WRITABLE_BOOK, "playban.staff"),
            new GuideEntry("profile", "/playban profile <player>", Material.BOOK, "playban.staff"),
            new GuideEntry("vanish", "/vanish", Material.GLASS, "playban.vanish"),
            new GuideEntry("ticket-show", "/ticket show [reason]", Material.HOPPER, "playban.staff"),
            new GuideEntry("voice", "/voice", Material.JUKEBOX, "playban.voice"),
            new GuideEntry("setspawn", "/setspawn", Material.RESPAWN_ANCHOR, "playban.admin"),
            new GuideEntry("spawn", "/spawn", Material.ENDER_PEARL, "playban.spawn"),
            new GuideEntry("ticket", "/ticket", Material.PAPER, ""),
            new GuideEntry("appeal", "/appeal <ban-ID> <text>", Material.NAME_TAG, ""),
            new GuideEntry("tasks", "/playban", Material.FEATHER, ""));

    /** Every command with what it does and an example, filtered to what the viewer may use. */
    public void guide(Player p, int page) {
        List<GuideEntry> visible = new ArrayList<>();
        for (GuideEntry g : GUIDE) if (g.permission().isEmpty() || p.hasPermission(g.permission())) visible.add(g);
        paged(lang().msg("menu.guide.title"), visible, page,
                g -> Items.of(g.icon(), lang().msg("menu.guide.entry", "command", g.usage()), lang().lore("guide." + g.id())),
                null, n -> guide(p, n), () -> {
                    if (p.hasPermission("playban.staff")) main(p);
                    else p.closeInventory();
                }).open(p);
    }

    // ---------------------------------------------------------------- player profile & notes

    public void profileByName(Player p, String name) {
        for (Map.Entry<UUID, String> e : plugin.store().names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) {
                profile(p, e.getKey(), e.getValue());
                return;
            }
        }
        OfflinePlayer off = Bukkit.getPlayerExact(name);
        if (off == null) off = Bukkit.getOfflinePlayerIfCached(name);
        if (off == null || off.getName() == null) {
            lang().send(p, "error.unknown-player", "player", name);
            return;
        }
        profile(p, off.getUniqueId(), off.getName());
    }

    /** Everything the team knows about one player: notes, punishments and the way to punish them. */
    public void profile(Player p, UUID id, String name) {
        int playbans = 0, bans = 0, warns = 0;
        List<Punishment> all = new ArrayList<>(plugin.store().history.getOrDefault(id, List.of()));
        if (plugin.store().active.containsKey(id)) all.add(plugin.store().active.get(id));
        if (plugin.store().bans.containsKey(id)) all.add(plugin.store().bans.get(id));
        for (Punishment pun : all) {
            switch (pun.type) {
                case "WARN" -> warns++;
                case "PLAYBAN" -> playbans++;
                default -> bans++;
            }
        }
        List<Note> notes = new ArrayList<>(plugin.store().notes.getOrDefault(id, List.of()));
        String state = plugin.store().bans.containsKey(id) ? "profile.state.banned"
                : plugin.punishments().isPunished(id) ? "profile.state.playban" : "profile.state.free";

        Menu m = new Menu(6, lang().msg("menu.profile.title", "player", name));
        m.set(4, Items.head(Bukkit.getOfflinePlayer(id), lang().msg("menu.profile.summary.name", "player", name),
                lang().lore("menu.profile.summary.lore", "state", lang().msg(state), "playbans", playbans, "bans", bans,
                        "warns", warns, "notes", notes.size(),
                        "online", lang().msg(Bukkit.getPlayer(id) != null ? "online" : "offline"))));
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        java.util.Collections.reverse(notes);
        for (int i = 0; i < notes.size() && i < slots.length; i++) {
            Note note = notes.get(i);
            List<Component> lore = new ArrayList<>(textLore(note.text));
            lore.addAll(lang().lore("menu.profile.note.actions"));
            m.set(slots[i], Items.of(Material.PAPER, lang().msg("menu.profile.note.name", "author", note.author, "date", date(note.createdAt)), lore), e -> {
                if (!e.isShiftClick()) return;
                if (!admin(p) && !note.author.equals(p.getName())) {
                    lang().send(p, "error.no-permission");
                    return;
                }
                plugin.punishments().removeNote(id, note);
                profile(p, id, name);
            });
        }
        if (notes.isEmpty()) m.set(22, item(Material.GRAY_DYE, "menu.profile.no-notes"));
        m.set(46, item(Material.WRITABLE_BOOK, "menu.profile.add-note"), e ->
                plugin.input().ask(p, "prompt.note", text -> {
                    plugin.store().names.put(id, name);
                    plugin.punishments().addNote(id, p.getName(), text);
                    profile(p, id, name);
                }, () -> profile(p, id, name), "player", name));
        m.set(47, item(Material.PAPER, "menu.profile.tickets", "count", plugin.tickets().involving(id).size()), e -> plugin.ticketMenus().ofPlayer(p, id, name));
        m.set(48, item(Material.BOOK, "menu.profile.history", "count", all.size()), e -> history(p, name));
        List<String> same = plugin.store().sameAddress(id);
        if (!same.isEmpty()) m.set(8, item(Material.COMPASS, "menu.profile.same-address", "players", String.join(", ", same)));
        m.set(50, item(Material.ANVIL, "menu.profile.punish"), e -> punishPlayer(p, id, name));
        m.set(52, item(Material.OAK_DOOR, "menu.back"), e -> main(p));
        m.fill().open(p);
    }

    public void history(Player p, String name) {
        List<Punishment> list = new ArrayList<>();
        for (List<Punishment> per : plugin.store().history.values()) {
            for (Punishment pun : per) if (pun.name.equalsIgnoreCase(name)) list.add(pun);
        }
        for (Punishment pun : plugin.store().active.values()) if (pun.name.equalsIgnoreCase(name)) list.add(pun);
        for (Punishment pun : plugin.store().bans.values()) if (pun.name.equalsIgnoreCase(name)) list.add(pun);
        list.sort(java.util.Comparator.comparingLong((Punishment pun) -> pun.createdAt).reversed());
        if (list.isEmpty()) {
            lang().send(p, "error.no-history", "player", name);
            return;
        }
        paged(lang().msg("menu.history.title", "player", list.get(0).name), list, 0,
                pun -> Items.of(pun.endedAt == 0 ? Material.RED_DYE : Material.PAPER,
                        lang().msg("menu.history.entry.name", "date", date(pun.createdAt)),
                        lang().lore("menu.history.entry.lore", "punishment", plugin.punishments().describe(pun.type, pun.originalMs),
                                "category", categoryName(pun),
                                "reason", pun.reason, "staff", pun.staff, "done", pun.tasksDone(), "total", pun.tasks.size(),
                                "end", lang().msg(pun.endedAt == 0 ? "history.running" : "history." + pun.endReason))),
                null, n -> {}, () -> main(p)).open(p);
    }

    private String categoryName(Punishment pun) {
        Category c = plugin.categories().get(pun.category);
        return c != null ? c.name : pun.category != null ? pun.category : lang().raw("custom");
    }

    public void bans(Player p, int page) {
        List<Punishment> list = new ArrayList<>(plugin.store().bans.values());
        Menu m = paged(lang().msg("menu.bans.title"), list, page,
                ban -> Items.head(Bukkit.getOfflinePlayer(ban.uuid), lang().msg("menu.bans.entry.name", "player", ban.name),
                        lang().lore("menu.bans.entry.lore", "type", lang().raw("type." + ban.type),
                                "time", ban.expiresAt <= 0 ? lang().raw("permanent") : TimeUtil.format(Math.max(1000, ban.expiresAt - System.currentTimeMillis())),
                                "reason", ban.reason, "staff", ban.staff, "date", date(ban.createdAt))),
                (ban, e) -> {
                    if (!e.isShiftClick()) return;
                    plugin.punishments().unban(ban, p.getName());
                    bans(p, page);
                }, n -> bans(p, n), () -> main(p));
        if (list.isEmpty()) m.set(22, item(Material.LIME_DYE, "menu.bans.empty"));
        m.open(p);
    }

    // ---------------------------------------------------------------- category editor

    public void categories(Player p, int page) {
        if (!admin(p)) return;
        Menu m = paged(lang().msg("menu.categories.title"), plugin.categories().all(), page, c -> {
            List<Component> lore = new ArrayList<>(stepLore(c));
            lore.addAll(lang().lore("menu.categories.entry.lore",
                    "expire", c.expireMs <= 0 ? lang().raw("never") : TimeUtil.format(c.expireMs)));
            return Items.of(c.icon, lang().msg("menu.category.entry.name", "category", c.name), lore);
        }, (c, e) -> categoryEdit(p, c.id), n -> categories(p, n), () -> main(p));
        m.set(51, item(Material.NETHER_STAR, "menu.categories.new"), e ->
                plugin.input().ask(p, "prompt.category-name", name -> categoryEdit(p, plugin.categories().create(name).id), () -> categories(p, 0)));
        m.open(p);
    }

    private List<Component> stepLore(Category c) {
        List<Component> lore = new ArrayList<>();
        for (int i = 0; i < c.steps.size(); i++) {
            Category.Step s = c.steps.get(i);
            lore.add(lang().msg(i == c.steps.size() - 1 ? "menu.categories.step-last" : "menu.categories.step",
                    "n", i + 1, "punishment", plugin.punishments().describe(s.type, s.durationMs)));
        }
        return lore;
    }

    public void categoryEdit(Player p, String id) {
        Category c = plugin.categories().get(id);
        if (c == null || !admin(p)) {
            categories(p, 0);
            return;
        }
        Menu m = new Menu(6, lang().msg("menu.category-edit.title", "category", c.name));
        m.set(4, Items.of(c.icon, lang().msg("menu.category.entry.name", "category", c.name), stepLore(c)));
        m.set(10, item(Material.NAME_TAG, "menu.category-edit.name", "value", c.name), e ->
                plugin.input().ask(p, "prompt.category-name", text -> {
                    c.name = text;
                    plugin.categories().save();
                    categoryEdit(p, id);
                }, () -> categoryEdit(p, id)));
        m.set(12, item(c.icon, "menu.category-edit.icon"), e -> {
            Material hand = p.getInventory().getItemInMainHand().getType();
            if (hand.isAir()) {
                lang().send(p, "error.hold-item");
                return;
            }
            c.icon = hand;
            plugin.categories().save();
            categoryEdit(p, id);
        });
        m.set(14, item(Material.CLOCK, "menu.category-edit.expire",
                "value", c.expireMs <= 0 ? lang().raw("never") : TimeUtil.format(c.expireMs)), e ->
                plugin.input().ask(p, "prompt.category-expire", text -> {
                    long ms = text.trim().equals("0") ? 0 : TimeUtil.parse(text);
                    if (ms < 0) lang().send(p, "error.bad-time", "input", text);
                    else c.expireMs = ms;
                    plugin.categories().save();
                    categoryEdit(p, id);
                }, () -> categoryEdit(p, id)));
        m.set(16, item(Material.LAVA_BUCKET, "menu.category-edit.delete"), e -> {
            if (!e.isShiftClick()) return;
            plugin.categories().delete(id);
            categories(p, 0);
        });

        int[] slots = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        for (int i = 0; i < c.steps.size() && i < slots.length; i++) {
            Category.Step step = c.steps.get(i);
            int index = i;
            Material icon = switch (step.type) {
                case "WARN" -> Material.BELL;
                case "BAN" -> Material.IRON_BARS;
                case "SPECBAN" -> Material.ENDER_EYE;
                default -> Material.CLOCK;
            };
            ItemStack stepItem = item(icon, "menu.category-edit.step", "n", i + 1,
                    "punishment", plugin.punishments().describe(step.type, step.durationMs));
            stepItem.setAmount(Math.min(64, i + 1));
            m.set(slots[i], stepItem, e -> {
                if (e.isShiftClick()) {
                    c.steps.remove(index);
                    plugin.categories().save();
                    categoryEdit(p, id);
                } else if (e.isRightClick()) {
                    if (step.type.equals("WARN")) return;
                    boolean mayBePermanent = !step.type.equals("PLAYBAN");
                    plugin.input().ask(p, mayBePermanent ? "prompt.step-duration-ban" : "prompt.step-duration", text -> {
                        boolean permanent = mayBePermanent && CategoryRegistry.isPermanent(text);
                        long ms = permanent ? 0 : TimeUtil.parse(text);
                        if (!permanent && ms <= 0) lang().send(p, "error.bad-time", "input", text);
                        else step.durationMs = ms;
                        plugin.categories().save();
                        categoryEdit(p, id);
                    }, () -> categoryEdit(p, id));
                } else {
                    step.type = CategoryRegistry.TYPES.get((CategoryRegistry.TYPES.indexOf(step.type) + 1) % CategoryRegistry.TYPES.size());
                    if (step.type.equals("PLAYBAN") && step.durationMs <= 0) step.durationMs = 86_400_000L;
                    plugin.categories().save();
                    categoryEdit(p, id);
                }
            });
        }
        if (c.steps.size() < slots.length) {
            m.set(slots[c.steps.size()], item(Material.NETHER_STAR, "menu.category-edit.add-step"), e -> {
                Category.Step last = c.steps.isEmpty() ? null : c.steps.get(c.steps.size() - 1);
                c.steps.add(last == null ? new Category.Step("WARN", 0) : new Category.Step(last.type, last.durationMs));
                plugin.categories().save();
                categoryEdit(p, id);
            });
        }
        m.set(49, item(Material.OAK_DOOR, "menu.back"), e -> categories(p, 0));
        m.fill().open(p);
    }

    // ---------------------------------------------------------------- reviews & tickets

    List<Component> textLore(String text) {
        List<Component> lore = new ArrayList<>();
        for (String line : TimeUtil.wrap(text, 42)) lore.add(lang().msg("menu.text-line", "text", line));
        return lore;
    }

    public void reviews(Player p, int page) {
        List<Review> list = new ArrayList<>(plugin.store().reviews.values());
        Menu m = paged(lang().msg("menu.reviews.title"), list, page, r -> {
            List<Component> lore = new ArrayList<>(lang().lore("menu.reviews.entry.lore",
                    "target", r.targetName == null ? lang().raw("staff-team") : r.targetName, "date", date(r.createdAt)));
            lore.addAll(textLore(r.text));
            lore.addAll(lang().lore("menu.reviews.entry.actions"));
            return Items.of(Material.WRITABLE_BOOK, lang().msg("menu.reviews.entry.name", "player", r.authorName), lore);
        }, (r, e) -> {
            if (e.isRightClick()) {
                plugin.punishments().reject(r, p.getName());
                lang().send(p, "review.rejected", "player", r.authorName);
            } else {
                plugin.punishments().approve(r, p.getName());
                lang().send(p, "review.approved", "player", r.authorName);
            }
            reviews(p, page);
        }, n -> reviews(p, n), () -> main(p));
        if (list.isEmpty()) m.set(22, item(Material.LIME_DYE, "menu.reviews.empty"));
        m.open(p);
    }

    // ---------------------------------------------------------------- settings

    public void settings(Player p) {
        if (!admin(p)) return;
        Menu m = new Menu(6, lang().msg("menu.settings.title"));
        for (int i = 0; i < TOGGLES.length; i++) {
            String key = TOGGLES[i];
            boolean on = plugin.getConfig().getBoolean(key);
            List<Component> lore = new ArrayList<>(lang().lore("setting." + key + ".lore"));
            lore.add(Component.empty());
            lore.add(lang().msg(on ? "menu.settings.on" : "menu.settings.off"));
            lore.add(lang().msg("menu.settings.click"));
            m.set(TOGGLE_SLOTS[i], Items.of(on ? Material.LIME_DYE : Material.GRAY_DYE, lang().msg("setting." + key + ".name"), lore), e -> {
                plugin.getConfig().set(key, !on);
                plugin.saveConfig();
                plugin.punishments().refreshVisibility();
                settings(p);
            });
        }
        m.set(32, item(Material.OAK_SIGN, "menu.settings.commands", "count", plugin.getConfig().getStringList("allowed-commands").size()),
                e -> allowedCommands(p, 0));
        m.set(33, item(Material.CLOCK, "menu.settings.durations", "list", String.join(", ", plugin.getConfig().getStringList("duration-presets"))),
                e -> editList(p, "duration-presets", "prompt.duration-presets", true));
        m.set(34, item(Material.PAPER, "menu.settings.reasons", "list", String.join(", ", plugin.getConfig().getStringList("reason-presets"))),
                e -> editList(p, "reason-presets", "prompt.reason-presets", false));
        m.set(37, item(Material.PAINTING, "menu.settings.ban-screen"), e -> banScreen(p, 0));
        m.set(41, item(Material.PAPER, "menu.settings.tickets"), e -> plugin.ticketMenus().settings(p));
        String[] staffToggles = {"vanish.staff-chat-only", "vanish.allow-pickup", "command-hiding.enabled", "command-hiding.hide-namespaced"};
        int[] staffSlots = {45, 46, 47, 48};
        for (int i = 0; i < staffToggles.length; i++) {
            String key = staffToggles[i];
            boolean on = plugin.getConfig().getBoolean(key, !key.equals("vanish.allow-pickup"));
            List<Component> lore = new ArrayList<>(lang().lore("setting." + key + ".lore"));
            lore.add(Component.empty());
            lore.add(lang().msg(on ? "menu.settings.on" : "menu.settings.off"));
            lore.add(lang().msg("menu.settings.click"));
            m.set(staffSlots[i], Items.of(on ? Material.LIME_DYE : Material.GRAY_DYE, lang().msg("setting." + key + ".name"), lore), e -> {
                plugin.getConfig().set(key, !on);
                plugin.saveConfig();
                for (Player online : Bukkit.getOnlinePlayers()) online.updateCommands();
                settings(p);
            });
        }
        m.set(51, item(Material.RESPAWN_ANCHOR, "menu.settings.spawn"), e -> spawnSettings(p));
        List<String> hiddenCommands = plugin.getConfig().getStringList("command-hiding.hidden");
        m.set(50, item(Material.STRUCTURE_VOID, "menu.settings.hidden-commands", "count", hiddenCommands.size(),
                "list", hiddenCommands.isEmpty() ? lang().raw("none") : String.join(", ", hiddenCommands)), e ->
                plugin.input().ask(p, "prompt.hidden-commands", text -> {
                    List<String> values = new ArrayList<>();
                    for (String part : text.split(",")) {
                        String v = part.trim().toLowerCase().replaceFirst("^/", "");
                        if (!v.isEmpty() && !values.contains(v)) values.add(v);
                    }
                    plugin.getConfig().set("command-hiding.hidden", values);
                    plugin.saveConfig();
                    for (Player online : Bukkit.getOnlinePlayers()) online.updateCommands();
                    settings(p);
                }, () -> settings(p)));
        String inv = plugin.getConfig().getString("inventory-mode", "KEEP").toUpperCase();
        m.set(43, item(Material.CHEST, "menu.settings.inventory", "value", lang().raw("inventory-mode." + inv)), e -> {
            List<String> modes = List.of("KEEP", "STORE", "CLEAR");
            plugin.getConfig().set("inventory-mode", modes.get((Math.max(0, modes.indexOf(inv)) + 1) % modes.size()));
            plugin.saveConfig();
            settings(p);
        });
        String limbo = plugin.getConfig().getString("limbo.location.world");
        m.set(38, item(Material.ENDER_EYE, "menu.settings.limbo-location", "value", limbo == null ? lang().raw("limbo.default-location")
                : limbo + " " + (int) plugin.getConfig().getDouble("limbo.location.x") + " " + (int) plugin.getConfig().getDouble("limbo.location.y")
                + " " + (int) plugin.getConfig().getDouble("limbo.location.z")), e -> {
            if (e.isShiftClick()) {
                plugin.getConfig().set("limbo.location", null);
            } else {
                org.bukkit.Location l = p.getLocation();
                plugin.getConfig().set("limbo.location.world", l.getWorld().getName());
                plugin.getConfig().set("limbo.location.x", l.getX());
                plugin.getConfig().set("limbo.location.y", l.getY());
                plugin.getConfig().set("limbo.location.z", l.getZ());
                plugin.getConfig().set("limbo.location.yaw", (double) l.getYaw());
                plugin.getConfig().set("limbo.location.pitch", (double) l.getPitch());
            }
            plugin.saveConfig();
            settings(p);
        });
        m.set(49, item(Material.OAK_DOOR, "menu.back"), e -> main(p));
        m.fill().open(p);
    }

    // ---------------------------------------------------------------- spawn

    public void spawnSettings(Player p) {
        if (!admin(p)) return;
        Menu m = new Menu(4, lang().msg("menu.spawn.title"));
        org.bukkit.Location s = plugin.spawn().spawn();
        String where = s == null ? "-" : s.getWorld().getName() + " " + s.getBlockX() + " " + s.getBlockY() + " " + s.getBlockZ();
        m.set(10, item(Material.RESPAWN_ANCHOR, "menu.spawn.set", "value", plugin.spawn().isSet() ? where : lang().raw("menu.spawn.world-spawn")), e -> {
            plugin.spawn().setSpawn(p.getLocation());
            lang().send(p, "spawn.set", "world", p.getWorld().getName(),
                    "x", p.getLocation().getBlockX(), "y", p.getLocation().getBlockY(), "z", p.getLocation().getBlockZ());
            spawnSettings(p);
        });
        m.set(11, item(Material.ENDER_PEARL, "menu.spawn.go"), e -> {
            p.closeInventory();
            plugin.spawn().teleport(p);
        });
        boolean on = plugin.spawn().enabled();
        m.set(13, item(on ? Material.LIME_DYE : Material.GRAY_DYE, "menu.spawn.enabled", "value", lang().msg(on ? "menu.settings.on" : "menu.settings.off")), e -> {
            plugin.getConfig().set("spawn.enabled", !on);
            plugin.saveConfig();
            spawnSettings(p);
        });
        String join = plugin.getConfig().getString("spawn.on-join", "FIRST_JOIN").toUpperCase();
        m.set(14, item(Material.OAK_DOOR, "menu.spawn.on-join", "value", lang().raw("spawn.join-mode." + join)), e -> {
            List<String> modes = dev.playban.SpawnManager.JOIN_MODES;
            plugin.getConfig().set("spawn.on-join", modes.get((Math.max(0, modes.indexOf(join)) + 1) % modes.size()));
            plugin.saveConfig();
            spawnSettings(p);
        });
        boolean death = plugin.getConfig().getBoolean("spawn.on-death", true);
        m.set(15, item(death ? Material.LIME_DYE : Material.GRAY_DYE, "menu.spawn.on-death", "value", lang().msg(death ? "menu.settings.on" : "menu.settings.off")), e -> {
            plugin.getConfig().set("spawn.on-death", !death);
            plugin.saveConfig();
            spawnSettings(p);
        });
        int warmup = plugin.getConfig().getInt("spawn.warmup", 3);
        m.set(16, item(Material.CLOCK, "menu.spawn.warmup", "value", warmup), e -> {
            List<Integer> steps = List.of(0, 3, 5, 10);
            int next = steps.get((Math.max(0, steps.indexOf(warmup)) + 1) % steps.size());
            plugin.getConfig().set("spawn.warmup", next);
            plugin.saveConfig();
            spawnSettings(p);
        });
        m.set(31, item(Material.OAK_DOOR, "menu.back"), e -> settings(p));
        m.fill().open(p);
    }

    // ---------------------------------------------------------------- ban screen editor

    private List<String> screenLines() {
        List<String> custom = plugin.getConfig().getStringList("ban-screen.lines");
        if (!custom.isEmpty()) return new ArrayList<>(custom);
        return new ArrayList<>(List.of(lang().raw("ban.screen").split("\n", -1)));
    }

    private void saveScreen(List<String> lines) {
        plugin.getConfig().set("ban-screen.lines", lines);
        plugin.saveConfig();
    }

    public void banScreen(Player p, int page) {
        if (!admin(p)) return;
        List<String> lines = screenLines();
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) indexes.add(i);
        Menu m = paged(lang().msg("menu.screen.title"), indexes, page, i -> {
            String line = lines.get(i);
            return Items.of(line.isBlank() ? Material.GRAY_STAINED_GLASS_PANE : Material.PAPER,
                    line.isBlank() ? lang().msg("menu.screen.empty-line") : lang().parse(line), lang().lore("menu.screen.line-actions", "n", i + 1));
        }, (i, e) -> {
            if (e.isShiftClick()) {
                lines.remove((int) i);
                saveScreen(lines);
                banScreen(p, page);
            } else {
                plugin.input().ask(p, "prompt.screen-line", text -> {
                    lines.set(i, text.equals("-") ? "" : text);
                    saveScreen(lines);
                    banScreen(p, page);
                }, () -> banScreen(p, page));
            }
        }, n -> banScreen(p, n), () -> settings(p));
        m.set(46, item(Material.SPYGLASS, "menu.screen.preview"), e -> {
            p.closeInventory();
            Punishment sample = new Punishment();
            sample.reason = lang().raw("menu.screen.sample-reason");
            sample.staff = p.getName();
            sample.expiresAt = System.currentTimeMillis() + 86_400_000L * 3;
            p.sendMessage(plugin.punishments().banScreen(sample));
        });
        String discord = plugin.punishments().discord();
        m.set(50, item(Material.BELL, "menu.screen.discord", "value", discord.isEmpty() ? lang().raw("none") : discord), e ->
                plugin.input().ask(p, "prompt.discord", text -> {
                    if (text.equals("-")) {
                        plugin.getConfig().set("ban-screen.discord", "");
                    } else if (text.startsWith("https://") && !text.contains("'") && !text.contains(" ")) {
                        plugin.getConfig().set("ban-screen.discord", text);
                    } else {
                        lang().send(p, "error.bad-link");
                    }
                    plugin.saveConfig();
                    banScreen(p, 0);
                }, () -> banScreen(p, 0)));
        m.set(51, item(Material.NETHER_STAR, "menu.screen.add"), e ->
                plugin.input().ask(p, "prompt.screen-line", text -> {
                    lines.add(text.equals("-") ? "" : text);
                    saveScreen(lines);
                    banScreen(p, 0);
                }, () -> banScreen(p, 0)));
        m.set(52, item(Material.LAVA_BUCKET, "menu.screen.reset"), e -> {
            if (!e.isShiftClick()) return;
            saveScreen(new ArrayList<>());
            banScreen(p, 0);
        });
        m.open(p);
    }

    /** Replaces a config list with comma separated chat input. */
    private void editList(Player p, String key, String promptKey, boolean durations) {
        plugin.input().ask(p, promptKey, text -> {
            List<String> values = new ArrayList<>();
            for (String part : text.split(",")) {
                String v = part.trim();
                if (v.isEmpty()) continue;
                if (durations && TimeUtil.parse(v) <= 0) {
                    lang().send(p, "error.bad-time", "input", v);
                    settings(p);
                    return;
                }
                values.add(v);
            }
            if (!values.isEmpty()) {
                plugin.getConfig().set(key, values.size() > 7 ? values.subList(0, 7) : values);
                plugin.saveConfig();
            }
            settings(p);
        }, () -> settings(p));
    }

    public void allowedCommands(Player p, int page) {
        List<String> list = plugin.getConfig().getStringList("allowed-commands");
        Menu m = paged(lang().msg("menu.commands.title"), list, page,
                c -> item(Material.PAPER, "menu.commands.entry", "command", c),
                (c, e) -> {
                    List<String> copy = new ArrayList<>(list);
                    copy.remove(c);
                    plugin.getConfig().set("allowed-commands", copy);
                    plugin.saveConfig();
                    allowedCommands(p, page);
                }, n -> allowedCommands(p, n), () -> settings(p));
        m.set(51, item(Material.NETHER_STAR, "menu.commands.add"), e ->
                plugin.input().ask(p, "prompt.command", text -> {
                    String cmd = text.replaceFirst("^/", "").split(" ")[0].toLowerCase();
                    List<String> copy = new ArrayList<>(list);
                    if (!cmd.isEmpty() && !copy.contains(cmd)) copy.add(cmd);
                    plugin.getConfig().set("allowed-commands", copy);
                    plugin.saveConfig();
                    allowedCommands(p, 0);
                }, () -> allowedCommands(p, 0)));
        m.open(p);
    }

    // ---------------------------------------------------------------- task editor

    private List<Component> taskLore(TaskDef def) {
        List<Component> lore = new ArrayList<>(plugin.playerMenus().describe(def, null));
        lore.add(Component.empty());
        lore.addAll(lang().lore("menu.tasks.entry.lore", "type", lang().msg("task." + def.type.name() + ".name"),
                "reduction", def.reduction,
                "limit", def.timeLimitMs <= 0 ? lang().raw("no-limit") : TimeUtil.format(def.timeLimitMs),
                "state", lang().msg(def.enabled ? "menu.settings.on" : "menu.settings.off")));
        return lore;
    }

    public void tasks(Player p, int page) {
        if (!admin(p)) return;
        Menu m = paged(lang().msg("menu.tasks.title"), plugin.tasks().all(), page,
                def -> Items.of(def.enabled ? def.type.icon : Material.GRAY_DYE, plugin.punishments().taskName(def, null), taskLore(def)),
                (def, e) -> taskEdit(p, def.id), n -> tasks(p, n), () -> main(p));
        m.set(51, item(Material.NETHER_STAR, "menu.tasks.new"), e -> newTask(p));
        m.open(p);
    }

    private void newTask(Player p) {
        Menu m = new Menu(3, lang().msg("menu.tasks.new-title"));
        int slot = 11;
        for (TaskType type : TaskType.values()) {
            m.set(slot++, Items.of(type.icon, lang().msg("task." + type.name() + ".name"), lang().lore("task." + type.name() + ".about")),
                    e -> taskEdit(p, plugin.tasks().create(type).id));
        }
        m.set(22, item(Material.OAK_DOOR, "menu.back"), e -> tasks(p, 0));
        m.fill().open(p);
    }

    private void askTask(Player p, String id, String promptKey, java.util.function.Consumer<String> apply) {
        plugin.input().ask(p, promptKey, text -> {
            apply.accept(text);
            plugin.tasks().save();
            taskEdit(p, id);
        }, () -> taskEdit(p, id));
    }

    public void taskEdit(Player p, String id) {
        TaskDef def = plugin.tasks().get(id);
        if (def == null || !admin(p)) {
            tasks(p, 0);
            return;
        }
        Menu m = new Menu(6, lang().msg("menu.task-edit.title", "id", def.id));
        m.set(4, Items.of(def.type.icon, plugin.punishments().taskName(def, null), taskLore(def)));
        m.set(19, item(def.enabled ? Material.LIME_DYE : Material.GRAY_DYE, "menu.task-edit.enabled",
                "state", lang().msg(def.enabled ? "menu.settings.on" : "menu.settings.off")), e -> {
            def.enabled = !def.enabled;
            plugin.tasks().save();
            taskEdit(p, id);
        });
        m.set(20, item(Material.CLOCK, "menu.task-edit.reduction", "value", def.reduction), e ->
                askTask(p, id, "prompt.task-reduction", text -> {
                    if (TaskDef.validReduction(text)) def.reduction = text.trim();
                    else lang().send(p, "error.bad-reduction", "input", text);
                }));
        m.set(21, item(Material.REPEATER, "menu.task-edit.limit",
                "value", def.timeLimitMs <= 0 ? lang().raw("no-limit") : TimeUtil.format(def.timeLimitMs)), e ->
                askTask(p, id, "prompt.task-limit", text -> {
                    long ms = text.trim().equals("0") ? 0 : TimeUtil.parse(text);
                    if (ms < 0) lang().send(p, "error.bad-time", "input", text);
                    else def.timeLimitMs = ms;
                }));
        m.set(22, item(Material.NAME_TAG, "menu.task-edit.name"), e ->
                askTask(p, id, "prompt.task-name", text -> def.name = text.equals("-") ? "" : text));

        switch (def.type) {
            case NICE_MESSAGE, STAFF_MESSAGE -> m.set(23, item(Material.FEATHER, "menu.task-edit.min-length", "value", def.minLength), e ->
                    askTask(p, id, "prompt.task-number", text -> {
                        try {
                            def.minLength = Math.max(1, Math.min(200, Integer.parseInt(text.trim())));
                        } catch (NumberFormatException ex) {
                            lang().send(p, "error.bad-number", "input", text);
                        }
                    }));
            case GIFT_MONEY -> m.set(23, item(Material.GOLD_INGOT, "menu.task-edit.amount", "value", TaskActions.money(def.amount)), e ->
                    askTask(p, id, "prompt.task-number", text -> {
                        try {
                            double v = Double.parseDouble(text.trim().replace(',', '.'));
                            if (v > 0 && !Double.isInfinite(v)) def.amount = v;
                            else lang().send(p, "error.bad-number", "input", text);
                        } catch (NumberFormatException ex) {
                            lang().send(p, "error.bad-number", "input", text);
                        }
                    }));
            case DONATE_ITEMS -> {
                m.set(23, item(Material.HOPPER, "menu.task-edit.amount", "value", (int) def.amount), e ->
                        askTask(p, id, "prompt.task-number", text -> {
                            try {
                                def.amount = Math.max(1, Math.min(2304, Integer.parseInt(text.trim())));
                            } catch (NumberFormatException ex) {
                                lang().send(p, "error.bad-number", "input", text);
                            }
                        }));
                m.set(24, item(def.material, "menu.task-edit.material", "value", TaskActions.itemName(def)), e -> {
                    Material hand = p.getInventory().getItemInMainHand().getType();
                    if (hand.isAir()) {
                        lang().send(p, "error.hold-item");
                        return;
                    }
                    def.material = hand;
                    plugin.tasks().save();
                    taskEdit(p, id);
                });
            }
            case QUIZ -> m.set(23, item(Material.KNOWLEDGE_BOOK, "menu.task-edit.questions", "count", def.questions.size()), e -> questions(p, id, 0));
        }
        if (def.type == TaskType.NICE_MESSAGE || def.type == TaskType.GIFT_MONEY) {
            m.set(24, item(Material.PLAYER_HEAD, "menu.task-edit.target", "value", lang().msg("target." + def.target)), e -> {
                def.target = "VICTIM".equals(def.target) ? "ANY" : "VICTIM";
                plugin.tasks().save();
                taskEdit(p, id);
            });
        }
        if (def.type == TaskType.GIFT_MONEY && !plugin.economy().available()) {
            m.set(31, item(Material.BARRIER, "menu.task-edit.no-economy"));
        }
        m.set(48, item(Material.LAVA_BUCKET, "menu.task-edit.delete"), e -> {
            if (!e.isShiftClick()) return;
            plugin.tasks().delete(id);
            tasks(p, 0);
        });
        m.set(50, item(Material.OAK_DOOR, "menu.back"), e -> tasks(p, 0));
        m.fill().open(p);
    }

    private void questions(Player p, String id, int page) {
        TaskDef def = plugin.tasks().get(id);
        if (def == null) {
            tasks(p, 0);
            return;
        }
        Menu m = paged(lang().msg("menu.questions.title"), new ArrayList<>(def.questions), page, q -> {
            List<Component> lore = new ArrayList<>();
            for (int i = 0; i < q.answers.size(); i++) {
                lore.add(lang().msg(i == 0 ? "menu.questions.correct" : "menu.questions.wrong", "text", q.answers.get(i)));
            }
            lore.addAll(lang().lore("menu.questions.remove"));
            return Items.of(Material.BOOK, lang().msg("menu.questions.entry", "text", q.question), lore);
        }, (q, e) -> {
            def.questions.remove(q);
            plugin.tasks().save();
            questions(p, id, page);
        }, n -> questions(p, id, n), () -> taskEdit(p, id));
        m.set(51, item(Material.NETHER_STAR, "menu.questions.add"), e ->
                plugin.input().ask(p, "prompt.question", question ->
                        plugin.input().ask(p, "prompt.answers", answers -> {
                            TaskDef.Question q = new TaskDef.Question();
                            q.question = question;
                            for (String a : answers.split("\\|")) if (!a.isBlank()) q.answers.add(a.trim());
                            if (q.answers.size() < 2) {
                                lang().send(p, "error.bad-answers");
                            } else {
                                def.questions.add(q);
                                plugin.tasks().save();
                            }
                            questions(p, id, 0);
                        }, () -> questions(p, id, 0)), () -> questions(p, id, 0)));
        m.open(p);
    }
}
