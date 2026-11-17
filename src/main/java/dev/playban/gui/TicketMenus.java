package dev.playban.gui;

import dev.playban.Lang;
import dev.playban.PlayBanPlugin;
import dev.playban.TicketManager;
import dev.playban.TimeUtil;
import dev.playban.model.Category;
import dev.playban.model.Ticket;
import dev.playban.model.Ticket.Entry;
import dev.playban.model.Ticket.Participant;
import dev.playban.model.Ticket.Status;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Everything around tickets: opening one, the overview, the ticket window, teleporting and closing. */
public final class TicketMenus {
    /** A ticket window somebody has open, so it can be redrawn when something happens in the ticket. */
    private record Window(int ticketId, Menu menu, Runnable draw) {}

    private static final List<String> STATUS_FILTERS = List.of("ACTIVE", "OPEN", "IN_PROGRESS", "CLOSED", "DECLINED", "ALL");
    private final PlayBanPlugin plugin;
    private final Map<UUID, Window> windows = new HashMap<>();

    public TicketMenus(PlayBanPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private AdminMenus admin() {
        return plugin.adminMenus();
    }

    private ItemStack item(Material material, String key, Object... kv) {
        return admin().item(material, key, kv);
    }

    private String age(long since) {
        return TimeUtil.format(Math.max(1000, System.currentTimeMillis() - since));
    }

    // ---------------------------------------------------------------- live windows

    public boolean watching(Player staff, int ticketId) {
        Window w = windows.get(staff.getUniqueId());
        return w != null && w.ticketId == ticketId && staff.getOpenInventory().getTopInventory().getHolder() == w.menu;
    }

    public void refresh(int ticketId) {
        for (Map.Entry<UUID, Window> e : new ArrayList<>(windows.entrySet())) {
            Player staff = Bukkit.getPlayer(e.getKey());
            if (staff == null) {
                windows.remove(e.getKey());
            } else if (watching(staff, ticketId)) {
                e.getValue().draw.run();
            }
        }
    }

    public void closed(UUID staff) {
        windows.remove(staff);
    }

    // ---------------------------------------------------------------- for players

    public void pickReason(Player player) {
        if (!player.hasPermission("playban.ticket")) {
            lang().send(player, "error.no-permission");
            return;
        }
        List<TicketManager.Reason> reasons = plugin.tickets().reasons();
        Menu m = new Menu(reasons.size() > 7 ? 4 : 3, lang().msg("menu.ticket-new.title"));
        int slot = 10 + Math.max(0, (7 - Math.min(7, reasons.size())) / 2);
        for (TicketManager.Reason r : reasons) {
            if (slot % 9 == 8) slot += 2;
            m.set(slot++, Items.of(r.icon(), lang().msg("menu.ticket-new.entry.name", "reason", r.name()), lang().lore("menu.ticket-new.entry.lore")),
                    e -> plugin.input().ask(player, "prompt.ticket", text -> plugin.tickets().create(player, r.id(), text), null));
        }
        m.fill().open(player);
    }

    // ---------------------------------------------------------------- overview

    private boolean matches(Ticket t, String status) {
        return switch (status) {
            case "ALL" -> true;
            case "ACTIVE" -> t.active();
            default -> t.status.name().equals(status);
        };
    }

    private List<Component> ticketLore(Ticket t) {
        List<Component> lore = new ArrayList<>(lang().lore("menu.tickets.entry.lore",
                "reason", plugin.tickets().reasonName(t.reason), "status", lang().msg("ticket.status." + t.status.name()),
                "age", age(t.createdAt), "handler", t.handler == null ? lang().raw("none") : t.handler,
                "people", t.participants.size()));
        lore.addAll(admin().textLore(t.text));
        return lore;
    }

    public void list(Player p, String status, String reason, int page) {
        List<Ticket> list = new ArrayList<>();
        for (Ticket t : plugin.tickets().all()) {
            if (matches(t, status) && (reason == null || reason.equals(t.reason))) list.add(0, t);
        }
        // tickets nobody has picked up come first
        list.sort((a, b) -> Integer.compare(a.status.ordinal(), b.status.ordinal()));
        Menu m = admin().paged(lang().msg("menu.tickets.title"), list, page, t -> {
            List<Component> lore = ticketLore(t);
            lore.addAll(lang().lore("menu.tickets.entry.actions"));
            return Items.head(Bukkit.getOfflinePlayer(t.author), lang().msg("menu.tickets.entry.name", "id", t.id, "player", t.authorName), lore);
        }, (t, e) -> view(p, t), n -> list(p, status, reason, n), () -> admin().main(p));
        if (list.isEmpty()) m.set(22, item(Material.LIME_DYE, "menu.tickets.empty"));
        m.set(46, item(Material.HOPPER, "menu.tickets.filter-status", "value", lang().msg("ticket.filter." + status)), e ->
                list(p, STATUS_FILTERS.get((STATUS_FILTERS.indexOf(status) + 1) % STATUS_FILTERS.size()), reason, 0));
        List<TicketManager.Reason> reasons = plugin.tickets().reasons();
        m.set(48, item(Material.NAME_TAG, "menu.tickets.filter-reason",
                "value", reason == null ? lang().raw("ticket.filter.any-reason") : plugin.tickets().reasonName(reason)), e -> {
            int index = -1;
            for (int i = 0; i < reasons.size(); i++) if (reasons.get(i).id().equals(reason)) index = i;
            list(p, status, index + 1 >= reasons.size() ? null : reasons.get(index + 1).id(), 0);
        });
        m.set(50, item(Material.GOLD_INGOT, "menu.tickets.stats"), e -> stats(p));
        m.open(p);
    }

    /** All tickets a player opened or was pulled into. */
    public void ofPlayer(Player p, UUID id, String name) {
        List<Ticket> list = plugin.tickets().involving(id);
        Menu m = admin().paged(lang().msg("menu.tickets.of-player", "player", name), list, 0, t -> {
            List<Component> lore = ticketLore(t);
            Participant part = t.participant(id);
            if (part != null && part.outcome != null) lore.add(lang().msg("menu.tickets.outcome", "outcome", part.outcome));
            lore.addAll(lang().lore("menu.tickets.entry.actions"));
            return Items.of(Material.PAPER, lang().msg("menu.tickets.entry.name", "id", t.id, "player", t.authorName), lore);
        }, (t, e) -> view(p, t), n -> {}, () -> admin().profile(p, id, name));
        if (list.isEmpty()) m.set(22, item(Material.LIME_DYE, "menu.tickets.empty"));
        m.open(p);
    }

    public void stats(Player p) {
        List<TicketManager.StaffStats> stats = plugin.tickets().stats();
        Menu m = admin().paged(lang().msg("menu.ticket-stats.title"), stats, 0,
                s -> Items.head(Bukkit.getOfflinePlayerIfCached(s.name()), lang().msg("menu.ticket-stats.entry.name", "staff", s.name()),
                        lang().lore("menu.ticket-stats.entry.lore", "accepted", s.accepted(), "closed", s.closed(),
                                "response", s.avgResponseMs() <= 0 ? "-" : TimeUtil.format(Math.max(1000, s.avgResponseMs())))),
                null, n -> {}, () -> list(p, "ACTIVE", null, 0));
        if (stats.isEmpty()) m.set(22, item(Material.GRAY_DYE, "menu.ticket-stats.empty"));
        m.open(p);
    }

    // ---------------------------------------------------------------- one ticket

    public void view(Player p, Ticket t) {
        if (t.status == Status.OPEN) intake(p, t);
        else window(p, t, 0);
    }

    /** A new ticket: who is this, what do we already know about them, take it or not? */
    private void intake(Player p, Ticket t) {
        Menu m = new Menu(4, lang().msg("menu.ticket.title", "id", t.id, "player", t.authorName));
        int notes = plugin.store().notes.getOrDefault(t.author, List.of()).size();
        int punishments = plugin.store().history.getOrDefault(t.author, List.of()).size()
                + (plugin.store().active.containsKey(t.author) ? 1 : 0) + (plugin.store().bans.containsKey(t.author) ? 1 : 0);
        List<Component> lore = ticketLore(t);
        lore.addAll(lang().lore("menu.ticket.known", "notes", notes, "punishments", punishments,
                "tickets", plugin.tickets().involving(t.author).size() - 1));
        m.set(4, Items.head(Bukkit.getOfflinePlayer(t.author), lang().msg("menu.tickets.entry.name", "id", t.id, "player", t.authorName), lore));
        m.set(11, item(Material.LIME_CONCRETE, "menu.ticket.accept"), e -> {
            plugin.tickets().accept(t, p);
            view(p, t);
        });
        m.set(13, item(Material.BOOK, "menu.detail.profile"), e -> admin().profile(p, t.author, t.authorName));
        m.set(15, item(Material.RED_CONCRETE, "menu.ticket.decline"), e -> {
            plugin.tickets().decline(t, p);
            list(p, "ACTIVE", null, 0);
        });
        m.set(31, item(Material.OAK_DOOR, "menu.back"), e -> list(p, "ACTIVE", null, 0));
        m.fill().open(p);
    }

    /** The ticket window: the conversation as entries, the people involved and all actions. */
    public void window(Player p, Ticket t, int back) {
        Menu m = new Menu(6, lang().msg("menu.ticket.title", "id", t.id, "player", t.authorName));
        int[] offset = {back};
        Runnable draw = () -> drawWindow(p, t, m, offset);
        windows.put(p.getUniqueId(), new Window(t.id, m, draw));
        draw.run();
        m.open(p);
    }

    private void drawWindow(Player p, Ticket t, Menu m, int[] offset) {
        m.clear();
        boolean live = t.status == Status.IN_PROGRESS;
        boolean mayEdit = live || p.hasPermission("playban.admin");

        // top row: the other tickets this team member is working on
        List<Ticket> mine = plugin.tickets().handledBy(p.getUniqueId());
        for (int i = 0; i < mine.size() && i < 9; i++) {
            Ticket other = mine.get(i);
            if (other == t) {
                m.set(i, item(Material.NETHER_STAR, "menu.ticket.tab-current", "id", t.id, "player", t.authorName));
            } else {
                m.set(i, Items.head(Bukkit.getOfflinePlayer(other.author),
                        lang().msg("menu.ticket.tab.name", "id", other.id, "player", other.authorName), lang().lore("menu.ticket.tab.lore")),
                        e -> window(p, other, 0));
            }
        }
        if (!live) m.set(4, Items.of(Material.BOOK, lang().msg("menu.ticket.archived.name", "status", lang().msg("ticket.status." + t.status.name())),
                ticketLore(t)));

        // the conversation, newest entries last
        int max = Math.max(0, t.log.size() - 27);
        offset[0] = Math.max(0, Math.min(offset[0], max));
        int start = Math.max(0, t.log.size() - 27 - offset[0]);
        for (int i = 0; i < 27 && start + i < t.log.size(); i++) {
            Entry entry = t.log.get(start + i);
            Material icon = entry.starred ? Material.NETHER_STAR : switch (entry.kind) {
                case "STAFF" -> Material.BOOK;
                case "NOTE" -> Material.FEATHER;
                case "SYSTEM" -> Material.GRAY_DYE;
                default -> Material.PAPER;
            };
            List<Component> lore = new ArrayList<>(admin().textLore(entry.text));
            if (entry.to != null) lore.add(lang().msg("menu.ticket.entry.only-to", "player", entry.to));
            if (mayEdit) lore.addAll(lang().lore(entry.starred ? "menu.ticket.entry.unstar" : "menu.ticket.entry.star"));
            m.set(9 + i, Items.of(icon, lang().msg("menu.ticket.entry." + entry.kind, "author", entry.author, "date", admin().date(entry.time)), lore),
                    mayEdit ? e -> plugin.tickets().toggleStar(t, entry) : null);
        }

        // people in the ticket
        for (int i = 0; i < t.participants.size() && i < 7; i++) {
            Participant part = t.participants.get(i);
            List<Component> lore = new ArrayList<>();
            lore.add(lang().msg(Bukkit.getPlayer(part.uuid) != null ? "online" : "offline"));
            if (part.outcome != null) lore.add(lang().msg("menu.tickets.outcome", "outcome", part.outcome));
            lore.addAll(lang().lore(live ? "menu.ticket.person.actions" : "menu.ticket.person.actions-closed"));
            m.set(36 + i, Items.head(Bukkit.getOfflinePlayer(part.uuid), lang().msg("menu.ticket.person.name", "player", part.name), lore), e -> {
                if (e.isShiftClick() && live) {
                    plugin.tickets().removeParticipant(t, p, part);
                } else if (e.isRightClick() || !live) {
                    admin().profile(p, part.uuid, part.name);
                } else {
                    plugin.input().ask(p, "prompt.ticket-message-to", text -> {
                        plugin.tickets().staffMessage(t, p, text, part);
                        window(p, t, 0);
                    }, () -> window(p, t, 0), "player", part.name);
                }
            });
        }
        if (offset[0] < max) m.set(43, item(Material.ARROW, "menu.ticket.older"), e -> {
            offset[0] += 9;
            drawWindow(p, t, m, offset);
        });
        if (offset[0] > 0) m.set(44, item(Material.ARROW, "menu.ticket.newer"), e -> {
            offset[0] -= 9;
            drawWindow(p, t, m, offset);
        });

        // actions
        if (live) {
            m.set(45, item(Material.WRITABLE_BOOK, "menu.ticket.write"), e -> {
                if (e.isRightClick()) {
                    quickReplies(p, t);
                } else {
                    plugin.input().ask(p, "prompt.ticket-message", text -> {
                        plugin.tickets().staffMessage(t, p, text, null);
                        window(p, t, 0);
                    }, () -> window(p, t, 0));
                }
            });
            m.set(47, item(t.paused ? Material.LEVER : Material.REDSTONE_TORCH, t.paused ? "menu.ticket.resume" : "menu.ticket.pause"),
                    e -> plugin.tickets().setPaused(t, p, !t.paused));
            if (t.participants.size() > 1) {
                m.set(48, item(t.shared ? Material.CAMPFIRE : Material.SOUL_CAMPFIRE, t.shared ? "menu.ticket.shared" : "menu.ticket.separate"),
                        e -> plugin.tickets().setShared(t, p, !t.shared));
            }
            m.set(49, item(Material.EMERALD, "menu.ticket.add-player"), e -> addPlayer(p, t, 0));
            m.set(50, item(Material.ENDER_PEARL, "menu.ticket.teleport"), e -> teleport(p, t));
            m.set(51, item(Material.JUKEBOX, "menu.ticket.voice"), e -> {
                if (!plugin.tickets().voice(t, p)) lang().send(p, "ticket.error.no-discord");
            });
            m.set(52, item(Material.LIME_CONCRETE, "menu.ticket.close"), e -> verdicts(p, t));
        }
        if (mayEdit) {
            m.set(46, item(Material.FEATHER, "menu.ticket.note"), e ->
                    plugin.input().ask(p, "prompt.ticket-note", text -> {
                        plugin.tickets().note(t, p.getName(), text);
                        window(p, t, 0);
                    }, () -> window(p, t, 0)));
        }
        m.set(53, item(Material.OAK_DOOR, "menu.back"), e -> list(p, live ? "ACTIVE" : "ALL", null, 0));
        m.fill();
    }

    private void quickReplies(Player p, Ticket t) {
        List<String> replies = plugin.getConfig().getStringList("quick-replies");
        Menu m = admin().paged(lang().msg("menu.ticket-replies.title"), replies, 0,
                r -> Items.of(Material.PAPER, lang().msg("menu.ticket-replies.entry"), admin().textLore(r)),
                (r, e) -> {
                    plugin.tickets().staffMessage(t, p, r, null);
                    window(p, t, 0);
                }, n -> {}, () -> window(p, t, 0));
        if (replies.isEmpty()) m.set(22, item(Material.GRAY_DYE, "menu.ticket-replies.empty"));
        m.open(p);
    }

    private void addPlayer(Player p, Ticket t, int page) {
        List<Player> candidates = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (t.participant(online.getUniqueId()) == null && !online.equals(p)) candidates.add(online);
        }
        Menu m = admin().paged(lang().msg("menu.ticket-add.title"), candidates, page,
                c -> Items.head(c, lang().msg("menu.select-player.entry.name", "player", c.getName()), lang().lore("menu.select-player.entry.lore")),
                (c, e) -> add(p, t, c.getUniqueId(), c.getName()),
                n -> addPlayer(p, t, n), () -> window(p, t, 0));
        m.set(51, item(Material.NAME_TAG, "menu.select-player.by-name"), e ->
                plugin.input().ask(p, "prompt.target-name", name -> {
                    OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
                    if (off == null || off.getName() == null) {
                        lang().send(p, "error.unknown-player", "player", name);
                        window(p, t, 0);
                    } else {
                        add(p, t, off.getUniqueId(), off.getName());
                    }
                }, () -> window(p, t, 0)));
        m.open(p);
    }

    private void add(Player p, Ticket t, UUID id, String name) {
        String error = plugin.tickets().addParticipant(t, p, id, name);
        if (error != null) lang().send(p, error, "player", name);
        window(p, t, 0);
    }

    // ---------------------------------------------------------------- teleport

    private void teleport(Player p, Ticket t) {
        if (!p.hasPermission("playban.teleport")) {
            lang().send(p, "error.no-permission");
            return;
        }
        Menu m = new Menu(5, lang().msg("menu.ticket-tp.title"));
        Material[] icons = {Material.GRASS_BLOCK, Material.DIAMOND_BLOCK, Material.ENDER_EYE, Material.GLASS};
        String[] modes = {"SURVIVAL", "CREATIVE", "SPECTATOR", "VANISH"};
        // one row per participant would not fit, so: pick the person first when there are several
        List<Participant> online = new ArrayList<>();
        for (Participant part : t.participants) if (Bukkit.getPlayer(part.uuid) != null && !part.uuid.equals(p.getUniqueId())) online.add(part);
        if (online.isEmpty()) {
            m.set(13, item(Material.BARRIER, "menu.ticket-tp.nobody"));
        } else {
            Participant[] target = {online.get(0)};
            Runnable[] draw = new Runnable[1];
            draw[0] = () -> {
                for (int i = 0; i < online.size() && i < 7; i++) {
                    Participant part = online.get(i);
                    ItemStack head = Items.head(Bukkit.getOfflinePlayer(part.uuid), lang().msg("menu.ticket.person.name", "player", part.name),
                            lang().lore(part == target[0] ? "menu.ticket-tp.target-picked" : "menu.ticket-tp.target"));
                    m.set(10 + i, head, e -> {
                        target[0] = part;
                        draw[0].run();
                    });
                }
                for (int i = 0; i < modes.length; i++) {
                    String mode = modes[i];
                    m.set(28 + i * 2, item(icons[i], "menu.ticket-tp.mode." + mode, "player", target[0].name), e -> {
                        p.closeInventory();
                        if (plugin.tickets().teleport(t, p, target[0], mode)) lang().send(p, "ticket.teleported", "player", target[0].name);
                        else lang().send(p, "ticket.error.offline", "player", target[0].name);
                    });
                }
            };
            draw[0].run();
        }
        if (plugin.tickets().canReturn(p.getUniqueId())) {
            m.set(39, item(Material.RED_BED, "menu.ticket-tp.return"), e -> {
                p.closeInventory();
                plugin.tickets().returnBack(p);
            });
        }
        m.set(41, item(Material.OAK_DOOR, "menu.back"), e -> window(p, t, 0));
        m.fill().open(p);
    }

    // ---------------------------------------------------------------- closing

    private String verdictText(Participant part) {
        if (part.verdict == null) return lang().raw("ticket.verdict.open");
        if (!part.verdict.startsWith("category:")) return lang().plain("ticket.verdict.involved");
        Category c = plugin.categories().get(part.verdict.substring("category:".length()));
        if (c == null) return lang().plain("ticket.verdict.involved");
        Category.Step step = plugin.punishments().nextStep(part.uuid, c);
        return c.name + (step == null ? "" : ": " + plugin.punishments().describe(step.type, step.durationMs));
    }

    /** Before a ticket closes, the team member decides for every person what happens to them. */
    public void verdicts(Player p, Ticket t) {
        Menu m = new Menu(4, lang().msg("menu.ticket-close.title", "id", t.id));
        for (int i = 0; i < t.participants.size() && i < 7; i++) {
            Participant part = t.participants.get(i);
            m.set(10 + i, Items.head(Bukkit.getOfflinePlayer(part.uuid), lang().msg("menu.ticket.person.name", "player", part.name),
                    lang().lore(part.verdict == null ? "menu.ticket-close.person-open" : "menu.ticket-close.person", "verdict", verdictText(part))),
                    e -> verdictFor(p, t, part));
        }
        if (plugin.tickets().allDecided(t)) {
            m.set(30, item(Material.LIME_CONCRETE, "menu.ticket-close.confirm"), e -> {
                plugin.tickets().close(t, p);
                view(p, t);
            });
        } else {
            m.set(30, item(Material.BARRIER, "menu.ticket-close.not-yet"));
        }
        m.set(32, item(Material.OAK_DOOR, "menu.back"), e -> window(p, t, 0));
        m.fill().open(p);
    }

    private void verdictFor(Player p, Ticket t, Participant part) {
        int notes = plugin.store().notes.getOrDefault(part.uuid, List.of()).size();
        int punishments = plugin.store().history.getOrDefault(part.uuid, List.of()).size();
        Menu m = admin().paged(lang().msg("menu.ticket-verdict.title", "player", part.name), plugin.categories().all(), 0, c -> {
            Category.Step step = plugin.punishments().nextStep(part.uuid, c);
            return Items.of(c.icon, lang().msg("menu.category.entry.name", "category", c.name),
                    lang().lore("menu.category.entry.lore", "n", plugin.punishments().countOffenses(part.uuid, c) + 1,
                            "punishment", step == null ? lang().raw("none") : plugin.punishments().describe(step.type, step.durationMs)));
        }, (c, e) -> {
            if (plugin.punishments().nextStep(part.uuid, c) == null) return;
            plugin.tickets().setVerdict(t, part, "category:" + c.id);
            verdicts(p, t);
        }, n -> {}, () -> verdicts(p, t));
        m.set(46, Items.head(Bukkit.getOfflinePlayer(part.uuid), lang().msg("menu.ticket-verdict.profile.name", "player", part.name),
                lang().lore("menu.ticket-verdict.profile.lore", "notes", notes, "punishments", punishments,
                        "tickets", plugin.tickets().involving(part.uuid).size())), e -> admin().profile(p, part.uuid, part.name));
        m.set(51, item(Material.NAME_TAG, "menu.ticket-verdict.involved"), e -> {
            plugin.tickets().setVerdict(t, part, "INVOLVED");
            verdicts(p, t);
        });
        m.open(p);
    }

    // ---------------------------------------------------------------- settings

    public void settings(Player p) {
        if (!p.hasPermission("playban.admin")) return;
        Menu m = new Menu(4, lang().msg("menu.ticket-settings.title"));
        m.set(10, item(Material.NAME_TAG, "menu.ticket-settings.reasons", "count", plugin.tickets().reasons().size()), e -> reasons(p));
        m.set(12, item(Material.PAPER, "menu.ticket-settings.replies", "count", plugin.getConfig().getStringList("quick-replies").size()), e -> replies(p));
        long minutes = plugin.getConfig().getLong("ticket-reminder-minutes", 10);
        m.set(14, item(Material.CLOCK, "menu.ticket-settings.reminder", "value", minutes <= 0 ? lang().raw("never") : minutes + " min"), e ->
                plugin.input().ask(p, "prompt.ticket-reminder", text -> {
                    try {
                        plugin.getConfig().set("ticket-reminder-minutes", Math.max(0, Math.min(1440, Integer.parseInt(text.trim()))));
                        plugin.saveConfig();
                    } catch (NumberFormatException ex) {
                        lang().send(p, "error.bad-number", "input", text);
                    }
                    settings(p);
                }, () -> settings(p)));
        boolean hook = !plugin.getConfig().getString("discord-webhook", "").isEmpty();
        m.set(16, item(Material.BELL, "menu.ticket-settings.webhook", "value", lang().msg(hook ? "menu.settings.on" : "menu.settings.off")), e ->
                plugin.input().ask(p, "prompt.ticket-webhook", text -> {
                    if (text.equals("-")) plugin.getConfig().set("discord-webhook", "");
                    else if (text.startsWith("https://") && !text.contains(" ")) plugin.getConfig().set("discord-webhook", text);
                    else lang().send(p, "error.bad-link");
                    plugin.saveConfig();
                    settings(p);
                }, () -> settings(p)));
        m.set(31, item(Material.OAK_DOOR, "menu.back"), e -> admin().settings(p));
        m.fill().open(p);
    }

    private void replies(Player p) {
        List<String> list = plugin.getConfig().getStringList("quick-replies");
        Menu m = admin().paged(lang().msg("menu.ticket-replies.title"), list, 0, r -> {
            List<Component> lore = new ArrayList<>(admin().textLore(r));
            lore.addAll(lang().lore("menu.ticket-settings.remove"));
            return Items.of(Material.PAPER, lang().msg("menu.ticket-replies.entry"), lore);
        }, (r, e) -> {
            List<String> copy = new ArrayList<>(list);
            copy.remove(r);
            plugin.getConfig().set("quick-replies", copy);
            plugin.saveConfig();
            replies(p);
        }, n -> {}, () -> settings(p));
        m.set(51, item(Material.NETHER_STAR, "menu.ticket-settings.add-reply"), e ->
                plugin.input().ask(p, "prompt.ticket-reply-text", text -> {
                    List<String> copy = new ArrayList<>(list);
                    copy.add(text);
                    plugin.getConfig().set("quick-replies", copy);
                    plugin.saveConfig();
                    replies(p);
                }, () -> replies(p)));
        m.open(p);
    }

    private void reasons(Player p) {
        List<TicketManager.Reason> list = plugin.tickets().reasons();
        Menu m = admin().paged(lang().msg("menu.ticket-reasons.title"), list, 0,
                r -> Items.of(r.icon(), lang().msg("menu.ticket-new.entry.name", "reason", r.name()), lang().lore("menu.ticket-reasons.actions", "id", r.id())),
                (r, e) -> {
                    String path = "ticket-reasons." + r.id();
                    if (e.isShiftClick()) {
                        if (list.size() > 1) plugin.getConfig().set(path, null);
                        plugin.saveConfig();
                        reasons(p);
                    } else if (e.isRightClick()) {
                        Material hand = p.getInventory().getItemInMainHand().getType();
                        if (hand.isAir()) {
                            lang().send(p, "error.hold-item");
                            return;
                        }
                        plugin.getConfig().set(path + ".icon", hand.name());
                        plugin.saveConfig();
                        reasons(p);
                    } else {
                        plugin.input().ask(p, "prompt.ticket-reason-name", text -> {
                            plugin.getConfig().set(path + ".name", text);
                            plugin.saveConfig();
                            reasons(p);
                        }, () -> reasons(p));
                    }
                }, n -> {}, () -> settings(p));
        m.set(51, item(Material.NETHER_STAR, "menu.ticket-reasons.add"), e ->
                plugin.input().ask(p, "prompt.ticket-reason-name", text -> {
                    String id = text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
                    if (id.isEmpty()) id = "reason";
                    String unique = id;
                    for (int i = 2; plugin.getConfig().contains("ticket-reasons." + unique); i++) unique = id + "-" + i;
                    plugin.getConfig().set("ticket-reasons." + unique + ".name", text);
                    plugin.getConfig().set("ticket-reasons." + unique + ".icon", "PAPER");
                    plugin.saveConfig();
                    reasons(p);
                }, () -> reasons(p)));
        m.open(p);
    }
}
