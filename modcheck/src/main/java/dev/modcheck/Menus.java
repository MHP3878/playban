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
        }
        if (p.hasPermission("modcheck.settings")) {
            m.set(16, item(Material.COMPARATOR, "menu.main.settings"), e -> settings(p));
            m.set(20, item(Material.BEDROCK, "roommenu.entry"), e -> room(p));
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
            // the proof: why this mod counts as found
            List<String> proof = guard().proof(t.getUniqueId(), mod);
            if (!proof.isEmpty()) {
                lore.add(Component.empty());
                lore.add(lang().msg("menu.player.proof"));
                for (String line : proof.subList(0, Math.min(4, proof.size()))) lore.add(lang().msg("menu.player.proof-line", "line", line));
            }
            lore.add(Component.empty());
            lore.add(lang().msg("menu.player.mod-click"));
            return Menu.item(isBlocked ? Material.RED_CONCRETE : passed ? Material.LIME_CONCRETE : Material.BOOK, lang().msg("menu.player.mod", "mod", guard().display(mod)), lore);
        }, (mod, e) -> modAction(p, t, mod), n -> {}, () -> players(p, 0));
        List<Component> head = new ArrayList<>(lang().lore("menu.player.info", "client", info.brand == null ? "?" : info.brand,
                "channels", info.channels.size(), "warnings", guard().warnings(t.getUniqueId()), "recent", guard().recentOffenses(t.getUniqueId())));
        head.add(lang().msg("menu.player.sign." + info.sign));
        head.add(lumioLine(info));
        // suspicious things about the client itself: a filter, a disguise, no answer
        if (!info.suspicion.isEmpty()) {
            head.add(Component.empty());
            head.add(lang().msg("menu.player.suspicion"));
            for (String line : info.suspicion.subList(0, Math.min(4, info.suspicion.size()))) head.add(lang().msg("menu.player.proof-line", "line", line));
        }
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
    public void check(org.bukkit.command.CommandSender p, Player t) {
        if (p instanceof Player staff) staff.closeInventory();
        lang().send(p, "check.running", "player", t.getName());
        guard().judge(t);
        guard().probe(t, true);
        int[] waited = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            waited[0] += 10;
            if ((p instanceof Player staff && !staff.isOnline()) || !t.isOnline()) {
                task.cancel();
                return;
            }
            if (guard().probing(t.getUniqueId()) && waited[0] < 600) return;
            task.cancel();
            Guard.Info info = guard().info(t.getUniqueId());
            List<String> mods = guard().mods(t.getUniqueId());
            List<String> names = new ArrayList<>();
            for (String mod : mods) names.add(guard().display(mod));
            lang().send(p, "check.result", "player", t.getName(), "client", info.brand == null ? "?" : info.brand,
                    "count", mods.size(), "mods", names.isEmpty() ? "-" : String.join(", ", names));
            lang().send(p, "check.result-" + info.sign);
            p.sendMessage(lumioLine(info));
            // the proof right in chat: one line per mod
            for (String mod : mods) {
                List<String> proof = guard().proof(t.getUniqueId(), mod);
                if (!proof.isEmpty()) p.sendMessage(lang().msg("check.proof", "mod", guard().display(mod), "proof", proof.get(0)));
            }
            for (String line : info.suspicion) p.sendMessage(lang().msg("check.suspicion", "line", line));
            if (p instanceof Player staff) player(staff, t);
            else p.sendMessage(lang().msg("check.channels", "channels", info.channels.isEmpty() ? "-" : String.join(", ", info.channels)));
        }, 10L, 10L);
    }

    /** "Lumio 0.1.0 · Minecraft 1.21.11 · 12 mods reported", or "No Lumio". */
    private Component lumioLine(Guard.Info info) {
        if (info.lumio == null || !info.lumio.complete) return lang().msg("lumio.none");
        return lang().msg("lumio.line", "version", info.lumio.lumio, "minecraft", info.lumio.minecraft, "count", info.lumio.installed().size());
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
            if (!p.hasPermission("modcheck.commands")) {
                lang().send(p, "no-permission-commands");
                return;
            }
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
        if (!p.hasPermission("modcheck.settings")) {
            p.closeInventory();
            lang().send(p, "no-permission");
            return;
        }
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
            // a console command can do anything on the server: only for those allowed to set commands
            if (!p.hasPermission("modcheck.commands")) {
                lang().send(p, "no-permission-commands");
                return;
            }
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
                if (Guard.isDiscordWebhook(text)) setGlobal("discord-webhook", text);
                else lang().send(p, "input.webhook-wrong");
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
        List<String> suspiciousActions = List.of("ALERT", "KICK", "WARN", "BAN");
        String hidden = c.getString("hidden-action", "ALERT").toUpperCase(Locale.ROOT);
        m.set(31, item(Material.SPYGLASS, "menu.settings.hidden", "value", actionName(hidden)), e -> {
            setGlobal("hidden-action", next(suspiciousActions, suspiciousActions.contains(hidden) ? hidden : "ALERT"));
            settings(p);
        });
        String noAnswer = c.getString("no-answer-action", "ALERT").toUpperCase(Locale.ROOT);
        m.set(33, item(Material.CLOCK, "menu.settings.no-answer", "value", actionName(noAnswer)), e -> {
            setGlobal("no-answer-action", next(suspiciousActions, suspiciousActions.contains(noAnswer) ? noAnswer : "ALERT"));
            settings(p);
        });
        String disguise = c.getString("disguise-action", "ALERT").toUpperCase(Locale.ROOT);
        m.set(34, item(Material.LEATHER_HELMET, "menu.settings.disguise", "value", actionName(disguise)), e -> {
            setGlobal("disguise-action", next(suspiciousActions, suspiciousActions.contains(disguise) ? disguise : "ALERT"));
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
        // Lumio: required or not, and what "not" costs
        String lumioMode = plugin.lumio().mode();
        List<Component> lumioLore = new ArrayList<>(lang().lore("menu.settings.lumio.lore", "value", lang().raw("lumio.mode-name." + lumioMode)));
        lumioLore.addAll(lang().lore(lumioMode.equals("required") ? "menu.settings.lumio.required" : "menu.settings.lumio.incomplete"));
        lumioLore.add(Component.empty());
        lumioLore.add(lang().msg("menu.settings.lumio.click"));
        m.set(43, Menu.item(lumioMode.equals("required") ? Material.AMETHYST_CLUSTER : Material.AMETHYST_SHARD, lang().msg("menu.settings.lumio.name"), lumioLore), e -> {
            String nextMode = next(Lumio.MODES, lumioMode);
            plugin.lumio().setMode(nextMode);
            guard().log(p.getName(), "lumio mode", null, nextMode);
            plugin.lumio().explain(p);
            settings(p);
        });
        m.set(49, item(Material.OAK_DOOR, "menu.back"), e -> main(p));
        m.fill().open(p);
    }

    // ---------------------------------------------------------------- check room, Lumio, injection

    /**
     * Everything about joining on one page: the check room, what happens without Lumio or with a
     * forbidden mod, the download link, the injection check, and a button to see the window yourself.
     */
    public void room(Player p) {
        if (!p.hasPermission("modcheck.settings")) {
            p.closeInventory();
            lang().send(p, "no-permission");
            return;
        }
        var c = plugin.getConfig();
        Menu m = new Menu(5, lang().msg("roommenu.title"));
        boolean on = c.getBoolean("check-room.enabled", true);
        m.set(10, item(on ? Material.BEDROCK : Material.GRAY_DYE, "roommenu.enabled", "value", lang().raw(on ? "word-on" : "word-off")), e -> {
            setGlobal("check-room.enabled", !on);
            room(p);
        });
        String place = "bottom".equalsIgnoreCase(c.getString("check-room.place", "top")) ? "bottom" : "top";
        m.set(11, item(place.equals("top") ? Material.FEATHER : Material.DEEPSLATE, "roommenu.place", "value", lang().raw("roommenu.place-" + place)), e -> {
            setGlobal("check-room.place", place.equals("top") ? "bottom" : "top");
            room(p);
        });
        String forbidden = plugin.room().onForbidden();
        m.set(12, item(Material.BARRIER, "roommenu.forbidden", "value", lang().raw("roommenu.choice-" + forbidden)), e -> {
            setGlobal("check-room.forbidden", forbidden.equals("kick") ? "stay" : "kick");
            room(p);
        });
        String missing = plugin.room().onMissingLumio();
        m.set(13, item(Material.AMETHYST_SHARD, "roommenu.missing", "value", lang().raw("roommenu.choice-" + missing)), e -> {
            setGlobal("check-room.missing-lumio", missing.equals("kick") ? "stay" : "kick");
            room(p);
        });
        String lumioMode = plugin.lumio().mode();
        m.set(14, item(lumioMode.equals("required") ? Material.AMETHYST_CLUSTER : Material.AMETHYST_BLOCK, "roommenu.lumio",
                "value", lang().raw("lumio.mode-name." + lumioMode)), e -> {
            String nextMode = next(Lumio.MODES, lumioMode);
            plugin.lumio().setMode(nextMode);
            guard().log(p.getName(), "lumio mode", null, nextMode);
            room(p);
        });
        String link = c.getString("lumio.download", "");
        m.set(15, item(Material.LEAD, "roommenu.download", "value", link.isBlank() ? lang().raw("roommenu.no-link") : link), e ->
                plugin.input().ask(p, "input.download", text -> {
                    String t = text.trim();
                    if (!t.startsWith("https://") || t.length() > 200 || t.contains(" ")) lang().send(p, "input.bad-link");
                    else {
                        setGlobal("lumio.download", t);
                        guard().log(p.getName(), "lumio download", null, t);
                    }
                    room(p);
                }, () -> room(p)));
        m.set(16, item(Material.ENDER_EYE, "roommenu.preview"), e -> {
            p.closeInventory();
            plugin.popups().lumio(p, "missing");
        });
        boolean inj = plugin.lumio().injectionCheck();
        m.set(20, item(inj ? Material.OBSERVER : Material.GRAY_DYE, "roommenu.injection", "value", lang().raw(inj ? "word-on" : "word-off")), e -> {
            setGlobal("injection.enabled", !inj);
            room(p);
        });
        String injAction = c.getString("injection-action", "KICK").toUpperCase(Locale.ROOT);
        List<String> injActions = List.of("KICK", "BAN", "ALERT");
        m.set(21, item(Material.TNT, "roommenu.injection-action", "value", actionName(injActions.contains(injAction) ? injAction : "KICK")), e -> {
            setGlobal("injection-action", next(injActions, injActions.contains(injAction) ? injAction : "KICK"));
            room(p);
        });
        m.set(23, item(Material.WRITTEN_BOOK, "roommenu.how"), null);
        m.set(24, item(Material.REDSTONE_TORCH, "roommenu.forbidden-preview"), e -> {
            p.closeInventory();
            plugin.popups().forbidden(p, "Vape Client");
        });
        m.set(40, item(Material.OAK_DOOR, "menu.back"), e -> main(p));
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
