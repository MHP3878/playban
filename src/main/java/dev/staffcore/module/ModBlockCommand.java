package dev.staffcore.module;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import dev.staffcore.gui.Items;
import dev.staffcore.gui.Menu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * /modblock (/modsperre) and /modunblock: forbid a mod just by its name. Tab suggests known mods,
 * a typo gets a "did you mean", and without a name a menu opens with the blocked mods and suggestions.
 */
public final class ModBlockCommand implements TabExecutor {
    public static final List<String> ACTIONS = List.of("WARN", "KICK", "BAN", "PUNISH", "ALERT");

    private final StaffCorePlugin plugin;

    public ModBlockCommand(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private ModGuard guard() {
        return plugin.modGuard();
    }

    private String defaultAction() {
        return plugin.getConfig().getString("mod-guard.default-action", "WARN").toUpperCase(Locale.ROOT);
    }

    /** BAN means a ban right away; it runs as PUNISH with the ban step. */
    private static String store(String action) {
        return action;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean unblock = command.getName().equalsIgnoreCase("modunblock");
        if (args.length == 0 || (!unblock && args[0].equalsIgnoreCase("list"))) {
            if (sender instanceof Player p) menu(p, 0);
            else list(sender);
            return true;
        }
        if (unblock) {
            String name = String.join(" ", args);
            lang().send(sender, guard().unblock(name, sender.getName()) ? "modblock.unblocked" : "modblock.not-blocked", "mod", name);
            return true;
        }
        // the last word may be the action
        String action = defaultAction();
        List<String> words = new ArrayList<>(List.of(args));
        if (words.size() > 1 && ACTIONS.contains(words.get(words.size() - 1).toUpperCase(Locale.ROOT))) {
            action = words.remove(words.size() - 1).toUpperCase(Locale.ROOT);
        }
        boolean force = words.size() > 1 && words.get(words.size() - 1).equalsIgnoreCase("--force");
        if (force) words.remove(words.size() - 1);
        String typed = String.join(" ", words);
        KnownMods.Mod known = KnownMods.find(typed);
        if (known == null && !force) {
            List<KnownMods.Mod> close = KnownMods.suggest(typed);
            if (!close.isEmpty()) {
                // a typo? offer the known ones, and the typed name as it is
                lang().send(sender, "modblock.did-you-mean", "mod", typed);
                for (int i = 0; i < Math.min(5, close.size()); i++) {
                    KnownMods.Mod m = close.get(i);
                    sender.sendMessage(lang().msg("modblock.suggestion", "mod", m.name(), "id", m.id())
                            .clickEvent(ClickEvent.runCommand("/modblock " + m.id() + " " + action.toLowerCase(Locale.ROOT))));
                }
                sender.sendMessage(lang().msg("modblock.as-typed", "mod", KnownMods.normalize(typed))
                        .clickEvent(ClickEvent.runCommand("/modblock " + typed + " --force " + action.toLowerCase(Locale.ROOT))));
                return true;
            }
        }
        String id = guard().block(typed, action, sender.getName());
        if (id == null) {
            lang().send(sender, "modblock.empty");
            return true;
        }
        lang().send(sender, known != null ? "modblock.blocked-known" : "modblock.blocked-unknown", "mod", known != null ? known.name() : id,
                "action", lang().raw("modblock.action." + action.toLowerCase(Locale.ROOT)));
        return true;
    }

    private void list(CommandSender s) {
        List<ModGuard.Rule> rules = guard().rules();
        lang().send(s, "modblock.list-header", "count", rules.size());
        for (ModGuard.Rule r : rules) s.sendMessage(lang().msg("modblock.list-line", "mod", guard().display(r), "id", r.name(),
                "action", lang().raw("modblock.action." + r.action().toLowerCase(Locale.ROOT))));
    }

    // ---------------------------------------------------------------- menu

    public void menu(Player p, int page) {
        List<ModGuard.Rule> rules = guard().rules();
        Menu m = plugin.adminMenus().paged(lang().msg("modblock.title", "count", rules.size()), rules, page, r -> {
            List<Component> lore = new ArrayList<>();
            lore.add(lang().msg("modblock.entry-action", "action", lang().raw("modblock.action." + r.action().toLowerCase(Locale.ROOT))));
            if (!r.channel().isEmpty()) lore.add(lang().msg("modblock.entry-channels", "channels", String.join(", ", r.channel())));
            lore.add(Component.empty());
            lore.addAll(lang().lore("modblock.entry-clicks"));
            return Items.of(Material.BARRIER, lang().msg("modblock.entry", "mod", guard().display(r)), lore);
        }, (r, e) -> {
            if (e.isShiftClick()) {
                guard().unblock(r.name(), p.getName());
                lang().send(p, "modblock.unblocked", "mod", guard().display(r));
            } else {
                // cycle the action
                int i = ACTIONS.indexOf(r.action());
                String next = ACTIONS.get((i + 1) % ACTIONS.size());
                plugin.getConfig().set("mod-guard.rules." + r.name() + ".action", next);
                plugin.saveConfig();
            }
            menu(p, page);
        }, n -> menu(p, n), p::closeInventory);
        m.set(49, Items.of(Material.NETHER_STAR, lang().msg("modblock.add"), lang().lore("modblock.add-lore")), e ->
                plugin.input().ask(p, "modblock.prompt", text -> {
                    p.performCommand("modblock " + text.trim());
                    menu(p, 0);
                }, () -> menu(p, 0)));
        m.set(47, Items.of(Material.KNOWLEDGE_BOOK, lang().msg("modblock.suggestions"), lang().lore("modblock.suggestions-lore")), e -> suggestions(p, 0));
        m.open(p);
    }

    /** Known mods that are not blocked yet: a click blocks one. */
    public void suggestions(Player p, int page) {
        Set<String> blocked = new java.util.HashSet<>();
        for (ModGuard.Rule r : guard().rules()) blocked.add(r.name());
        List<KnownMods.Mod> open = new ArrayList<>();
        for (KnownMods.Mod mod : KnownMods.ALL) if (!blocked.contains(mod.id())) open.add(mod);
        Menu m = plugin.adminMenus().paged(lang().msg("modblock.suggestions-title"), open, page, mod -> {
            List<Component> lore = new ArrayList<>();
            lore.add(lang().msg(mod.client() ? "modblock.kind-client" : "modblock.kind-mod"));
            lore.add(Component.empty());
            lore.addAll(lang().lore("modblock.suggestion-clicks"));
            return Items.of(mod.client() ? Material.REDSTONE_BLOCK : Material.BOOK, lang().msg("modblock.entry", "mod", mod.name()), lore);
        }, (mod, e) -> {
            String action = e.isShiftClick() ? "BAN" : e.isRightClick() ? "KICK" : defaultAction();
            guard().block(mod.id(), action, p.getName());
            lang().send(p, "modblock.blocked-known", "mod", mod.name(), "action", lang().raw("modblock.action." + action.toLowerCase(Locale.ROOT)));
            suggestions(p, page);
        }, n -> suggestions(p, n), () -> menu(p, 0));
        m.open(p);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (command.getName().equalsIgnoreCase("modunblock")) {
            if (args.length == 1) for (ModGuard.Rule r : guard().rules()) if (r.name().startsWith(typed)) out.add(r.name());
            return out;
        }
        if (args.length == 1) {
            if ("list".startsWith(typed)) out.add("list");
            for (KnownMods.Mod m : KnownMods.ALL) if (m.id().startsWith(KnownMods.normalize(typed)) || typed.isEmpty()) out.add(m.id());
        } else if (args.length == 2) {
            for (String a : ACTIONS) if (a.toLowerCase(Locale.ROOT).startsWith(typed)) out.add(a.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
