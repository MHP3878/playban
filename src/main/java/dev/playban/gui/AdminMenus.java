package dev.playban.gui;

import dev.playban.Lang;
import dev.playban.PlayBanPlugin;
import dev.playban.TaskActions;
import dev.playban.TimeUtil;
import dev.playban.model.Punishment;
import dev.playban.model.Punishment.TaskProgress;
import dev.playban.model.Review;
import dev.playban.model.TaskDef;
import dev.playban.model.TaskType;
import dev.playban.model.Ticket;
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
            "count-only-online-time", "staff-see-punished", "auto-approve-messages"
    };
    private static final int[] TOGGLE_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 28, 29, 30};

    private final PlayBanPlugin plugin;

    public AdminMenus(PlayBanPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private ItemStack item(Material material, String key, Object... kv) {
        return Items.of(material, lang().msg(key + ".name", kv), lang().lore(key + ".lore", kv));
    }

    private String date(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(millis));
    }

    private boolean admin(Player p) {
        return p.hasPermission("playban.admin");
    }

    /** A 6-row list with page buttons in the bottom row. */
    private <T> Menu paged(Component title, List<T> entries, int page, Function<T, ItemStack> render,
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
        m.set(12, item(Material.CLOCK, "menu.main.active", "count", plugin.store().active.size()), e -> active(p, 0));
        m.set(14, item(Material.WRITABLE_BOOK, "menu.main.reviews", "count", plugin.store().reviews.size()), e -> reviews(p, 0));
        m.set(16, item(Material.PAPER, "menu.main.tickets", "count", plugin.store().tickets.size()), e -> tickets(p, 0));
        m.set(28, item(Material.BOOK, "menu.main.history"), e ->
                plugin.input().ask(p, "prompt.history-name", name -> history(p, name), () -> main(p)));
        if (admin(p)) {
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
                    selectDuration(p, d);
                },
                n -> selectPlayer(p, n), () -> main(p));
        m.set(51, item(Material.NAME_TAG, "menu.select-player.by-name"), e ->
                plugin.input().ask(p, "prompt.player-name", name -> {
                    OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
                    if (off == null || off.getName() == null) {
                        lang().send(p, "error.unknown-player", "player", name);
                        return;
                    }
                    if (plugin.punishments().isPunished(off.getUniqueId())) {
                        lang().send(p, "error.already-punished", "player", off.getName());
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
                    selectDuration(p, d);
                }, () -> selectPlayer(p, 0)));
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
        m.set(31, item(Material.OAK_DOOR, "menu.back"), e -> selectPlayer(p, 0));
        m.fill().open(p);
    }

    private void selectReason(Player p, Draft d) {
        Menu m = new Menu(4, lang().msg("menu.reason.title", "player", d.targetName));
        int slot = 10;
        for (String preset : plugin.getConfig().getStringList("reason-presets")) {
            if (slot > 16) break;
            m.set(slot++, item(Material.PAPER, "menu.reason.preset", "reason", preset), e -> {
                d.reason = preset;
                selectVictim(p, d, 0);
            });
        }
        m.set(22, item(Material.NAME_TAG, "menu.reason.custom"), e ->
                plugin.input().ask(p, "prompt.reason", text -> {
                    d.reason = text;
                    selectVictim(p, d, 0);
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
                n -> selectVictim(p, d, n), () -> selectReason(p, d));
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
                lang().lore("menu.confirm.summary.lore", "time", TimeUtil.format(d.ms), "reason", d.reason,
                        "victim", d.victimName == null ? lang().raw("none") : d.victimName)));
        m.set(11, item(Material.LIME_CONCRETE, "menu.confirm.accept"), e -> {
            p.closeInventory();
            if (plugin.punishments().isPunished(d.target)) {
                lang().send(p, "error.already-punished", "player", d.targetName);
                return;
            }
            plugin.punishments().punish(d.target, d.targetName, d.ms, d.reason, p.getName(), d.victim, d.victimName);
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
        m.set(50, item(Material.OAK_DOOR, "menu.back"), e -> active(p, 0));
        m.fill().open(p);
    }

    public void history(Player p, String name) {
        List<Punishment> list = new ArrayList<>();
        for (List<Punishment> per : plugin.store().history.values()) {
            for (Punishment pun : per) if (pun.name.equalsIgnoreCase(name)) list.add(pun);
        }
        for (Punishment pun : plugin.store().active.values()) if (pun.name.equalsIgnoreCase(name)) list.add(pun);
        if (list.isEmpty()) {
            lang().send(p, "error.no-history", "player", name);
            return;
        }
        paged(lang().msg("menu.history.title", "player", list.get(0).name), list, 0,
                pun -> Items.of(pun.endedAt == 0 ? Material.RED_DYE : Material.PAPER,
                        lang().msg("menu.history.entry.name", "date", date(pun.createdAt)),
                        lang().lore("menu.history.entry.lore", "original", TimeUtil.format(pun.originalMs),
                                "reason", pun.reason, "staff", pun.staff, "done", pun.tasksDone(), "total", pun.tasks.size(),
                                "end", lang().msg(pun.endedAt == 0 ? "history.running" : "history." + pun.endReason))),
                null, n -> {}, () -> main(p)).open(p);
    }

    // ---------------------------------------------------------------- reviews & tickets

    private List<Component> textLore(String text) {
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

    public void tickets(Player p, int page) {
        List<Ticket> list = new ArrayList<>(plugin.store().tickets.values());
        Menu m = paged(lang().msg("menu.tickets.title"), list, page, t -> {
            List<Component> lore = new ArrayList<>(lang().lore("menu.tickets.entry.lore", "date", date(t.createdAt)));
            lore.addAll(textLore(t.text));
            lore.addAll(lang().lore("menu.tickets.entry.actions"));
            return Items.of(Material.PAPER, lang().msg("menu.tickets.entry.name", "player", t.authorName), lore);
        }, (t, e) -> {
            if (e.isRightClick()) {
                plugin.punishments().closeTicket(t);
                tickets(p, page);
            } else {
                plugin.input().ask(p, "prompt.ticket-reply", text -> {
                    plugin.punishments().replyTicket(t, p.getName(), text);
                    lang().send(p, "ticket.replied", "player", t.authorName);
                    tickets(p, 0);
                }, () -> tickets(p, page), "player", t.authorName);
            }
        }, n -> tickets(p, n), () -> main(p));
        if (list.isEmpty()) m.set(22, item(Material.LIME_DYE, "menu.tickets.empty"));
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
        m.set(49, item(Material.OAK_DOOR, "menu.back"), e -> main(p));
        m.fill().open(p);
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
