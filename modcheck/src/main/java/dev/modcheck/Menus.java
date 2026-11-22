package dev.modcheck;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Every ModCheck menu. Settings only for those who may change them; moderators punish or let pass. */
public final class Menus {
    private static final List<String> DURATIONS = List.of("", "10m", "1h", "1d", "7d", "30d", "perm");
    private static final List<String> GLOBAL_DURATIONS = List.of("10m", "1h", "1d", "7d", "30d", "perm");

    private final ModCheckPlugin plugin;

    public Menus(ModCheckPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private Guard guard() {
        return plugin.guard();
    }

    private ItemStack item(Material m, String key, Object... args) {
        return Menu.item(m, lang().msg(key + ".name", args), lang().lore(key + ".lore", args));
    }

    private String actionName(String action) {
        return lang().raw("action." + action.toLowerCase(Locale.ROOT));
    }

    /** A list over several pages with back, previous and next. */
    private <T> Menu paged(Component title, List<T> list, int page, Function<T, ItemStack> show, BiConsumer<T, org.bukkit.event.inventory.InventoryClickEvent> click,
                           java.util.function.IntConsumer turn, Runnable back) {
        Menu m = new Menu(6, title);
        int per = 45;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        int p = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < per && p * per + i < list.size(); i++) {
            T t = list.get(p * per + i);
            m.set(i, show.apply(t), e -> click.accept(t, e));
        }
        if (p > 0) m.set(45, item(Material.ARROW, "menu.previous"), e -> turn.accept(p - 1));
        if (p < pages - 1) m.set(53, item(Material.ARROW, "menu.next"), e -> turn.accept(p + 1));
        if (back != null) m.set(49, item(Material.OAK_DOOR, "menu.back"), e -> back.run());
        for (int i = 45; i < 54; i++) if (m.getInventory().getItem(i) == null) m.set(i, Menu.item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of()), null);
        return m;
    }

    // ---------------------------------------------------------------- main

    public void main(Player p) {
        Menu m = new Menu(4, lang().msg("menu.main.title"));
        m.set(10, item(Material.PLAYER_HEAD, "menu.main.players", "count", Bukkit.getOnlinePlayers().size()), e -> players(p, 0));
        if (p.hasPermission("modcheck.block")) {
            m.set(12, item(Material.BARRIER, "menu.main.blocked", "count", guard().rules().size()), e -> blocked(p, 0));
            m.set(13, item(Material.NETHER_STAR, "menu.main.add"), e -> addMod(p));
            m.set(14, item(Material.KNOWLEDGE_BOOK, "menu.main.suggestions"), e -> suggestions(p, 0));
            m.set(16, item(Material.COMPARATOR, "menu.main.settings"), e -> settings(p));
        }
        if (p.hasPermission("modcheck.unban")) m.set(22, item(Material.IRON_BARS, "menu.main.bans", "count", guard().bans().size()), e -> bans(p, 0));
        m.fill().open(p);
    }

    // ---------------------------------------------------------------- players and their mods

    public void players(Player p, int page) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        paged(lang().msg("menu.players.title"), online, page, t -> {
            List<Guard.Rule> hits = guard().hits(t.getUniqueId());
            Guard.Info info = guard().info(t.getUniqueId());
            List<Component> lore = new ArrayList<>(lang().lore("menu.players.lore", "client", info.brand == null ? "?" : info.brand,
                    "mods", guard().mods(t.getUniqueId()).size()));
            if (!hits.isEmpty()) lore.add(lang().msg("menu.players.blocked", "count", hits.size()));
            return Menu.head(t, lang().msg("menu.players.name", "player", t.getName()), lore);
        }, (t, e) -> player(p, t), n -> players(p, n), () -> main(p)).open(p);
    }

    /** One player: client and every mod; a click on a mod offers punish or let pass. */
    public void player(Player p, Player t) {
        Guard.Info info = guard().info(t.getUniqueId());
        List<String> mods = guard().mods(t.getUniqueId());
        Set<String> blocked = new HashSet<>();
        for (Guard.Rule r : guard().hits(t.getUniqueId())) blocked.add(r.id());
        Menu m = paged(lang().msg("menu.player.title", "player", t.getName()), mods, 0, mod -> {
            boolean isBlocked = blocked.contains(mod);
            boolean passed = guard().passed(t.getUniqueId(), mod);
            List<Component> lore = new ArrayList<>();
            lore.add(lang().msg(isBlocked ? "menu.player.mod-blocked" : passed ? "menu.player.mod-passed" : "menu.player.mod-free"));
            lore.add(Component.empty());
            lore.add(lang().msg("menu.player.mod-click"));
            return Menu.item(isBlocked ? Material.RED_CONCRETE : passed ? Material.LIME_CONCRETE : Material.BOOK, lang().msg("menu.player.mod", "mod", guard().display(mod)), lore);
        }, (mod, e) -> modAction(p, t, mod), n -> {}, () -> players(p, 0));
        List<Component> head = new ArrayList<>(lang().lore("menu.player.info", "client", info.brand == null ? "?" : info.brand,
                "channels", info.channels.size(), "warnings", guard().warnings(t.getUniqueId()), "recent", guard().recentOffenses(t.getUniqueId())));
        head.add(lang().msg("menu.player.sign." + info.sign));
        m.set(47, Menu.head(t, lang().msg("menu.players.name", "player", t.getName()), head), null);
        m.set(51, item(Material.OAK_SIGN, "menu.player.recheck"), e -> check(p, t));
        if (mods.isEmpty()) {
            boolean seen = info.sign.equals("works");
            m.set(22, Menu.item(seen ? Material.LIME_DYE : Material.GRAY_DYE, lang().msg("menu.player.none." + (seen ? "clean" : "hidden")),
                    List.of(lang().msg("menu.player.sign." + info.sign))), null);
        }
        m.open(p);
    }

    /**
     * The whole check in one go: client name, channels and the hidden sign. Says in chat that it runs,
     * writes the result in chat when done and then opens the player's page.
     */
    public void check(Player p, Player t) {
        p.closeInventory();
        lang().send(p, "check.running", "player", t.getName());
        guard().judge(t);
        guard().probe(t, true);
        int[] waited = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            waited[0] += 10;
            if (!p.isOnline() || !t.isOnline()) {
                task.cancel();
                return;
            }
            if (guard().probing(t.getUniqueId()) && waited[0] < 400) return;
            task.cancel();
            Guard.Info info = guard().info(t.getUniqueId());
            List<String> mods = guard().mods(t.getUniqueId());
            List<String> names = new ArrayList<>();
            for (String mod : mods) names.add(guard().display(mod));
            lang().send(p, "check.result", "player", t.getName(), "client", info.brand == null ? "?" : info.brand,
                    "count", mods.size(), "mods", names.isEmpty() ? "-" : String.join(", ", names));
            lang().send(p, "check.result-" + info.sign);
            player(p, t);
        }, 10L, 10L);
    }

    /** Punish (what the settings say) or let pass, for one mod of one player. */
    public void modAction(Player p, Player t, String mod) {
        Guard.Rule r = guard().rule(mod);
        String action = guard().action(r);
        String reason = Guard.fill(guard().reason(r), guard().display(mod));
        Menu m = new Menu(3, lang().msg("menu.decide.title", "player", t.getName(), "mod", guard().display(mod)));
        m.set(4, Menu.item(Material.BOOK, lang().msg("menu.player.mod", "mod", guard().display(mod)),
                lang().lore(r == null ? "menu.decide.not-blocked" : "menu.decide.blocked", "action", actionName(action), "reason", reason,
                        "duration", guard().duration(r))), null);
        m.set(11, item(Material.RED_CONCRETE, "menu.decide.punish", "action", actionName(action), "reason", reason), e -> {
            p.closeInventory();
            guard().punish(t, mod, p.getName());
            lang().send(p, "decide.punished", "player", t.getName(), "mod", guard().display(mod), "action", actionName(action));
        });
        m.set(15, item(Material.LIME_CONCRETE, "menu.decide.pass"), e -> {
            guard().pass(t.getUniqueId(), mod, p.getName());
            lang().send(p, "decide.passed", "player", t.getName(), "mod", guard().display(mod));
            player(p, t);
        });
        if (p.hasPermission("modcheck.block")) {
            if (r == null) m.set(13, item(Material.BARRIER, "menu.decide.block"), e -> {
                guard().block(mod, p.getName());
                lang().send(p, "block.done", "mod", guard().display(mod), "action", actionName(guard().action(guard().rule(mod))));
                if (t.isOnline()) player(p, t);
                else p.closeInventory();
            });
            else m.set(13, item(Material.COMPARATOR, "menu.decide.settings"), e -> rule(p, mod));
        }
        m.set(22, item(Material.OAK_DOOR, "menu.back"), e -> player(p, t));
        m.fill().open(p);
    }

    // ---------------------------------------------------------------- blocked mods and their settings

    public void blocked(Player p, int page) {
        List<Guard.Rule> rules = guard().rules();
        Menu m = paged(lang().msg("menu.blocked.title", "count", rules.size()), rules, page, r -> Menu.item(Material.BARRIER,
                lang().msg("menu.player.mod", "mod", r.display()), lang().lore("menu.blocked.entry", "action", actionName(guard().action(r)),
                        "reason", Guard.fill(guard().reason(r), r.display()))), (r, e) -> rule(p, r.id()), n -> blocked(p, n), () -> main(p));
        m.set(47, item(Material.NETHER_STAR, "menu.main.add"), e -> addMod(p));
        m.set(51, item(Material.KNOWLEDGE_BOOK, "menu.main.suggestions"), e -> suggestions(p, 0));
        m.open(p);
    }

    private void set(String id, String key, Object value) {
        plugin.getConfig().set("rules." + id + "." + key, value);
        plugin.saveConfig();
    }

    private <T> T next(List<T> list, T now) {
        int i = list.indexOf(now);
        return list.get((i + 1) % list.size());
    }

    /** Everything about one blocked mod, a click each. */
    public void rule(Player p, String id) {
        Guard.Rule r = guard().rule(id);
        if (r == null) {
            blocked(p, 0);
            return;
        }
        String def = lang().raw("menu.rule.default");
        Menu m = new Menu(5, lang().msg("menu.rule.title", "mod", r.display()));
        m.set(4, Menu.item(Material.BARRIER, lang().msg("menu.player.mod", "mod", r.display()), lang().lore("menu.rule.what")), null);
        List<String> actions = new ArrayList<>(List.of("DEFAULT"));
        actions.addAll(Guard.ACTIONS);
        String a = r.action().isBlank() ? "DEFAULT" : r.action();
        m.set(19, item(Material.ANVIL, "menu.rule.action", "value", a.equals("DEFAULT") ? def + " (" + actionName(guard().action(null)) + ")" : actionName(a)), e -> {
            set(id, "action", next(actions, a));
            rule(p, id);
        });
        m.set(20, item(Material.WRITABLE_BOOK, "menu.rule.reason", "value", r.reason().isBlank() ? def + ": " + guard().reason(null) : r.reason()), e -> {
            if (e.isShiftClick()) {
                set(id, "reason", "");
                rule(p, id);
                return;
            }
            plugin.input().ask(p, "input.reason", text -> {
                set(id, "reason", text);
                rule(p, id);
            }, () -> rule(p, id));
        });
        m.set(21, item(Material.CLOCK, "menu.rule.duration", "value", r.duration().isBlank() ? def + " (" + guard().duration(null) + ")" : r.duration()), e -> {
            set(id, "ban-duration", next(DURATIONS, r.duration()));
            rule(p, id);
        });
        List<Integer> warns = List.of(-1, 0, 1, 2, 3);
        m.set(22, item(Material.PAPER, "menu.rule.warnings", "value", r.warnings() < 0 ? def + " (" + guard().maxWarnings(null) + ")" : String.valueOf(r.warnings())), e -> {
            set(id, "warnings", next(warns, r.warnings() < 0 ? -1 : Math.min(3, r.warnings())));
            rule(p, id);
        });
        m.set(23, item(Material.COMMAND_BLOCK, "menu.rule.command", "value", r.command().isBlank() ? "-" : r.command()), e -> {
            if (e.isShiftClick()) {
                set(id, "command", "");
                rule(p, id);
                return;
            }
            plugin.input().ask(p, "input.command", text -> {
                set(id, "command", text.replaceFirst("^/", ""));
                set(id, "action", "COMMAND");
                rule(p, id);
            }, () -> rule(p, id));
        });
        m.set(24, item(r.tellTeam() ? Material.BELL : Material.GRAY_DYE, "menu.rule.notify", "value", lang().raw(r.tellTeam() ? "word-on" : "word-off")), e -> {
            set(id, "notify", !r.tellTeam());
            rule(p, id);
        });
        m.set(25, item(r.discord() ? Material.LIGHTNING_ROD : Material.GRAY_DYE, "menu.rule.discord", "value", lang().raw(r.discord() ? "word-on" : "word-off")), e -> {
            set(id, "discord", !r.discord());
            rule(p, id);
        });
        m.set(31, Menu.item(Material.NAME_TAG, lang().msg("menu.rule.preview.name"), lang().lore("menu.rule.preview.lore",
                "reason", Guard.fill(guard().reason(r), r.display()))), null);
        m.set(39, item(Material.OAK_DOOR, "menu.back"), e -> blocked(p, 0));
        m.set(41, item(Material.LAVA_BUCKET, "menu.rule.unblock"), e -> {
            if (!e.isShiftClick()) return;
            guard().unblock(id, p.getName());
            lang().send(p, "block.unblocked", "mod", r.display());
            blocked(p, 0);
        });
        m.fill().open(p);
    }

    public void addMod(Player p) {
        plugin.input().ask(p, "input.mod", text -> {
            p.performCommand("modblock " + text);
            blocked(p, 0);
        }, () -> main(p));
    }

    public void suggestions(Player p, int page) {
        Set<String> blocked = new HashSet<>();
        for (Guard.Rule r : guard().rules()) blocked.add(r.id());
        List<KnownMods.Mod> open = new ArrayList<>();
        for (KnownMods.Mod mod : KnownMods.ALL) if (!blocked.contains(mod.id())) open.add(mod);
        paged(lang().msg("menu.suggestions.title"), open, page, mod -> Menu.item(mod.client() ? Material.REDSTONE_BLOCK : Material.BOOK,
                lang().msg("menu.player.mod", "mod", mod.name()), lang().lore(mod.client() ? "menu.suggestions.client" : "menu.suggestions.mod")),
                (mod, e) -> {
                    guard().block(mod.id(), p.getName());
                    lang().send(p, "block.done", "mod", mod.name(), "action", actionName(guard().action(guard().rule(mod.id()))));
                    suggestions(p, page);
                }, n -> suggestions(p, n), () -> main(p)).open(p);
    }

    // ---------------------------------------------------------------- general settings

    private void setGlobal(String key, Object value) {
        plugin.getConfig().set(key, value);
        plugin.saveConfig();
    }

    public void settings(Player p) {
        var c = plugin.getConfig();
        Menu m = new Menu(6, lang().msg("menu.settings.title"));
        String action = c.getString("default-action", "WARN").toUpperCase(Locale.ROOT);
        m.set(10, item(Material.ANVIL, "menu.settings.action", "value", actionName(action)), e -> {
            setGlobal("default-action", next(Guard.ACTIONS, action));
            settings(p);
        });
        m.set(11, item(Material.WRITABLE_BOOK, "menu.settings.reason", "value", c.getString("default-reason", "")), e ->
                plugin.input().ask(p, "input.reason", text -> {
                    setGlobal("default-reason", text);
                    settings(p);
                }, () -> settings(p)));
        String dur = c.getString("ban-duration", "7d");
        m.set(12, item(Material.CLOCK, "menu.settings.duration", "value", dur), e -> {
            setGlobal("ban-duration", next(GLOBAL_DURATIONS, dur));
            settings(p);
        });
        int warns = c.getInt("warnings", 1);
        m.set(13, item(Material.PAPER, "menu.settings.warnings", "value", warns), e -> {
            setGlobal("warnings", (warns + 1) % 4);
            settings(p);
        });
        String banCmd = c.getString("ban-command", "");
        m.set(14, item(Material.COMMAND_BLOCK, "menu.settings.ban-command", "value", banCmd.isBlank() ? lang().raw("menu.settings.own-ban") : banCmd), e -> {
            if (e.isShiftClick()) {
                setGlobal("ban-command", "");
                settings(p);
                return;
            }
            plugin.input().ask(p, "input.ban-command", text -> {
                setGlobal("ban-command", text.replaceFirst("^/", ""));
                settings(p);
            }, () -> settings(p));
        });
        boolean notify = c.getBoolean("notify-team", true);
        m.set(15, item(notify ? Material.BELL : Material.GRAY_DYE, "menu.settings.notify", "value", lang().raw(notify ? "word-on" : "word-off")), e -> {
            setGlobal("notify-team", !notify);
            settings(p);
        });
        String hook = c.getString("discord-webhook", "");
        m.set(16, item(Material.LIGHTNING_ROD, "menu.settings.discord", "value", hook.isBlank() ? lang().raw("word-off") : lang().raw("word-on")), e -> {
            if (e.isShiftClick()) {
                setGlobal("discord-webhook", "");
                settings(p);
                return;
            }
            plugin.input().ask(p, "input.webhook", text -> {
                if (text.startsWith("https://")) setGlobal("discord-webhook", text);
                settings(p);
            }, () -> settings(p));
        });
        String mode = c.getString("mode", "BLOCKLIST").toUpperCase(Locale.ROOT);
        m.set(28, item(mode.equals("ALLOWLIST") ? Material.WHITE_BANNER : Material.BLACK_BANNER, "menu.settings.mode",
                "value", lang().raw("mode." + mode.toLowerCase(Locale.ROOT)), "clients", String.join(", ", c.getStringList("allowed-clients"))), e -> {
            setGlobal("mode", mode.equals("ALLOWLIST") ? "BLOCKLIST" : "ALLOWLIST");
            settings(p);
        });
        String notAllowed = c.getString("not-allowed-action", "KICK").toUpperCase(Locale.ROOT);
        m.set(29, item(Material.IRON_DOOR, "menu.settings.not-allowed", "value", actionName(notAllowed)), e -> {
            setGlobal("not-allowed-action", next(List.of("KICK", "BAN", "ALERT"), notAllowed));
            settings(p);
        });
        boolean sign = c.getBoolean("sign-check", true);
        m.set(30, item(sign ? Material.OAK_SIGN : Material.GRAY_DYE, "menu.settings.sign", "value", lang().raw(sign ? "word-on" : "word-off")), e -> {
            setGlobal("sign-check", !sign);
            settings(p);
        });
        String language = c.getString("language", "en");
        m.set(32, item(Material.GLOBE_BANNER_PATTERN, "menu.settings.language", "value", language), e -> {
            String nextLang = language.equals("de") ? "en" : "de";
            setGlobal("language", nextLang);
            lang().load(nextLang);
            settings(p);
        });
        // one rule for coming back again and again, as one sentence with three parts to click
        String window = c.getString("repeat.window", "off");
        if (!Guard.REPEAT_WINDOWS.contains(window)) window = "off";
        final String windowNow = window;
        int times = c.getInt("repeat.times", 2);
        String punish = c.getString("repeat.action", "BAN 7d");
        boolean repeatOn = !window.equals("off");
        String windowText = lang().raw("repeat.window." + window.toLowerCase(Locale.ROOT));
        String punishText = repeatPunish(punish);
        m.set(37, item(Material.CLOCK, "menu.settings.repeat-window", "value", windowText), e -> {
            setGlobal("repeat.window", next(Guard.REPEAT_WINDOWS, windowNow));
            settings(p);
        });
        m.set(38, item(Material.REPEATER, "menu.settings.repeat-times", "value", times), e -> {
            setGlobal("repeat.times", times >= 5 ? 2 : times + 1);
            settings(p);
        });
        m.set(39, item(Material.IRON_BARS, "menu.settings.repeat-action", "value", punishText), e -> {
            setGlobal("repeat.action", next(Guard.REPEAT_ACTIONS, punish));
            settings(p);
        });
        m.set(41, Menu.item(repeatOn ? Material.WRITTEN_BOOK : Material.BOOK, lang().msg("menu.settings.repeat-rule.name"),
                lang().lore(repeatOn ? "menu.settings.repeat-rule.on" : "menu.settings.repeat-rule.off", "window", windowText, "times", times,
                        "punish", punishText)), null);
        m.set(49, item(Material.OAK_DOOR, "menu.back"), e -> main(p));
        m.fill().open(p);
    }

    private String repeatPunish(String action) {
        String[] parts = action.trim().split("\\s+", 2);
        if (!parts[0].equalsIgnoreCase("BAN")) return lang().raw("action.kick");
        String d = parts.length > 1 ? parts[1] : "perm";
        return lang().raw("repeat.ban").replace("{time}", d.equals("perm") ? lang().raw("ban.forever") : d);
    }

    // ---------------------------------------------------------------- bans

    public void bans(Player p, int page) {
        List<Guard.Ban> bans = guard().bans();
        paged(lang().msg("menu.bans.title", "count", bans.size()), bans, page, b -> {
            OfflinePlayer op = Bukkit.getOfflinePlayer(b.id());
            return Menu.head(op, lang().msg("menu.players.name", "player", b.name()), lang().lore("menu.bans.entry", "reason", b.reason(),
                    "time", b.until() == 0 ? lang().raw("ban.forever") : Time.format(b.until() - System.currentTimeMillis())));
        }, (b, e) -> {
            if (!e.isShiftClick()) return;
            guard().unban(b.id(), p.getName());
            lang().send(p, "ban.unbanned", "player", b.name());
            bans(p, page);
        }, n -> bans(p, n), () -> main(p)).open(p);
    }
}
