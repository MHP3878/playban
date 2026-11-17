package dev.staffcore.gui;

import dev.staffcore.Lang;
import dev.staffcore.RankManager;
import dev.staffcore.StaffCorePlugin;
import dev.staffcore.model.Rank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Menus for ranks: the list, editing one rank, its permissions and members, and command levels. */
public final class RankMenus {
    private static final Material[] COLORS = {Material.RED_DYE, Material.ORANGE_DYE, Material.YELLOW_DYE, Material.LIME_DYE,
            Material.LIGHT_BLUE_DYE, Material.BLUE_DYE, Material.PURPLE_DYE, Material.PINK_DYE, Material.GRAY_DYE};
    /** Minecraft permissions people usually want to hand out, besides the StaffCore ones. */
    private static final List<String> COMMON = List.of("*", "staffcore.*", "minecraft.command.gamemode", "minecraft.command.tp",
            "minecraft.command.teleport", "minecraft.command.give", "minecraft.command.time", "minecraft.command.weather",
            "minecraft.command.kick", "minecraft.command.effect", "minecraft.command.kill");
    private final StaffCorePlugin plugin;

    public RankMenus(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private RankManager ranks() {
        return plugin.ranks();
    }

    private AdminMenus admin() {
        return plugin.adminMenus();
    }

    private ItemStack item(Material m, String key, Object... kv) {
        return admin().item(m, key, kv);
    }

    private Component show(Rank r) {
        return MiniMessage.miniMessage().deserialize(r.prefix + r.name);
    }

    private String name(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p != null ? p.getName() : plugin.store().names.getOrDefault(id, id.toString().substring(0, 8));
    }

    public void list(Player p) {
        if (!p.hasPermission("staffcore.ranks")) {
            lang().send(p, "error.no-permission");
            return;
        }
        List<Rank> all = ranks().all();
        Menu m = admin().paged(lang().msg("menu.ranks.title"), all, 0, r -> {
            List<Component> lore = new ArrayList<>(lang().lore("menu.ranks.entry.lore", "level", r.level,
                    "members", ranks().members(r).size(), "permissions", r.permissions.size()));
            if (!ranks().mayManage(p, r.level)) lore.addAll(lang().lore("menu.ranks.entry.locked"));
            return Items.of(COLORS[Math.floorMod(r.id.hashCode(), COLORS.length)], show(r), lore);
        }, (r, e) -> {
            if (ranks().mayManage(p, r.level)) edit(p, r);
            else lang().send(p, "rank.not-allowed");
        }, n -> {}, () -> admin().main(p));
        m.set(46, item(Material.COMMAND_BLOCK, "menu.ranks.levels", "count", ranks().commandLevels().size(),
                "state", lang().msg(ranks().active() ? "menu.ranks.active" : "menu.ranks.inactive")), e -> levels(p, 0));
        m.set(51, item(Material.NETHER_STAR, "menu.ranks.new"), e ->
                plugin.input().ask(p, "prompt.rank-name", text -> edit(p, ranks().create(text)), () -> list(p)));
        if (!ranks().active()) m.set(48, item(Material.GOLDEN_HELMET, "menu.ranks.setup"), e -> {
            p.closeInventory();
            p.performCommand("rank setup");
        });
        m.open(p);
    }

    public void edit(Player p, Rank r) {
        if (ranks().get(r.id) == null || !ranks().mayManage(p, r.level)) {
            list(p);
            return;
        }
        Menu m = new Menu(4, lang().msg("menu.rank-edit.title", "rank", r.name));
        m.set(4, Items.of(Material.NAME_TAG, show(r), lang().lore("menu.rank-edit.preview", "level", r.level)));
        m.set(10, item(Material.OAK_SIGN, "menu.rank-edit.name", "value", r.name), e ->
                plugin.input().ask(p, "prompt.rank-name", text -> {
                    r.name = text;
                    ranks().changed(r);
                    edit(p, r);
                }, () -> edit(p, r)));
        m.set(11, item(Material.WRITABLE_BOOK, "menu.rank-edit.prefix", "value", r.prefix.replace("<", "‹").replace(">", "›")), e ->
                plugin.input().ask(p, "prompt.rank-prefix", text -> {
                    r.prefix = text.endsWith(" ") ? text : text + " ";
                    ranks().changed(r);
                    edit(p, r);
                }, () -> edit(p, r)));
        m.set(12, item(Material.EXPERIENCE_BOTTLE, "menu.rank-edit.level", "value", r.level), e ->
                plugin.input().ask(p, "prompt.rank-level", text -> {
                    try {
                        int level = Integer.parseInt(text.trim());
                        // the default rank's level counts for every player, so only the highest rank may move it
                        if (!ranks().mayManage(p, level) || (r == ranks().defaultRank() && !ranks().isTop(p))) lang().send(p, "rank.not-allowed");
                        else {
                            r.level = Math.max(0, Math.min(999, level));
                            ranks().changed(r);
                        }
                    } catch (NumberFormatException ex) {
                        lang().send(p, "error.bad-number", "input", text);
                    }
                    edit(p, r);
                }, () -> edit(p, r)));
        m.set(14, item(Material.KNOWLEDGE_BOOK, "menu.rank-edit.permissions", "count", r.permissions.size()), e -> permissions(p, r, 0));
        m.set(15, item(Material.PLAYER_HEAD, "menu.rank-edit.members", "count", ranks().members(r).size()), e -> members(p, r));
        if (r != ranks().defaultRank()) {
            m.set(16, item(Material.LAVA_BUCKET, "menu.rank-edit.delete"), e -> {
                if (!e.isShiftClick()) return;
                ranks().delete(r);
                list(p);
            });
        }
        m.set(31, item(Material.OAK_DOOR, "menu.back"), e -> list(p));
        m.fill().open(p);
    }

    private void permissions(Player p, Rank r, int page) {
        List<String> nodes = new ArrayList<>(COMMON);
        for (Permission perm : Bukkit.getPluginManager().getPermissions()) {
            if (perm.getName().startsWith("staffcore.") && !nodes.contains(perm.getName())) nodes.add(perm.getName());
        }
        for (String own : r.permissions) if (!nodes.contains(own)) nodes.add(own);
        Menu m = admin().paged(lang().msg("menu.rank-perms.title", "rank", r.name), nodes, page, node -> {
            boolean has = r.permissions.contains(node);
            Permission perm = Bukkit.getPluginManager().getPermission(node);
            List<Component> lore = new ArrayList<>();
            if (perm != null && perm.getDescription() != null && !perm.getDescription().isEmpty()) {
                lore.addAll(admin().textLore(perm.getDescription()));
            }
            lore.add(lang().msg(has ? "menu.settings.on" : "menu.settings.off"));
            lore.add(lang().msg("menu.settings.click"));
            return Items.of(has ? Material.LIME_DYE : Material.GRAY_DYE, lang().msg("menu.rank-perms.entry", "node", node), lore);
        }, (node, e) -> {
            if (!r.permissions.remove(node)) {
                if (!ranks().mayGrant(p, node)) {
                    lang().send(p, "rank.grant-own-only");
                    return;
                }
                r.permissions.add(node);
            }
            ranks().changed(r);
            permissions(p, r, page);
        }, n -> permissions(p, r, n), () -> edit(p, r));
        m.set(51, item(Material.NETHER_STAR, "menu.rank-perms.add"), e ->
                plugin.input().ask(p, "prompt.rank-permission", text -> {
                    String node = text.trim();
                    if (!node.isEmpty() && !ranks().mayGrant(p, node)) lang().send(p, "rank.grant-own-only");
                    else if (!node.isEmpty() && !r.permissions.contains(node)) r.permissions.add(node);
                    ranks().changed(r);
                    permissions(p, r, page);
                }, () -> permissions(p, r, page)));
        m.open(p);
    }

    private void members(Player p, Rank r) {
        List<UUID> list = ranks().members(r);
        Menu m = admin().paged(lang().msg("menu.rank-members.title", "rank", r.name), list, 0,
                id -> Items.head(Bukkit.getOfflinePlayer(id), lang().msg("menu.ticket.person.name", "player", name(id)),
                        lang().lore("menu.rank-members.remove")),
                (id, e) -> {
                    if (!e.isShiftClick()) return;
                    ranks().set(id, ranks().defaultRank());
                    members(p, r);
                }, n -> {}, () -> edit(p, r));
        m.set(51, item(Material.NETHER_STAR, "menu.rank-members.add"), e ->
                plugin.input().ask(p, "prompt.target-name", text -> {
                    p.performCommand("rank set " + text.trim() + " " + r.id);
                    members(p, r);
                }, () -> members(p, r)));
        if (list.isEmpty()) m.set(22, item(Material.GRAY_DYE, "menu.rank-members.empty"));
        m.open(p);
    }

    public void levels(Player p, int page) {
        List<Map.Entry<String, Integer>> rules = new ArrayList<>(ranks().commandLevels().entrySet());
        rules.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        int own = ranks().level(p.getUniqueId());
        Menu m = admin().paged(lang().msg("menu.rank-levels.title"), rules, page,
                rule -> Items.of(Material.COMMAND_BLOCK, lang().msg("menu.rank-levels.entry", "command", rule.getKey(), "level", rule.getValue()),
                        lang().lore("menu.rank-levels.actions")),
                (rule, e) -> {
                    // command rules are the lock on everything: only the highest rank changes them
                    if (!ranks().isTop(p)) {
                        lang().send(p, "rank.not-allowed");
                        return;
                    }
                    if (e.isShiftClick()) {
                        ranks().setCommandLevel(rule.getKey(), null);
                        levels(p, page);
                        return;
                    }
                    plugin.input().ask(p, "prompt.rank-level", text -> {
                        try {
                            int level = Integer.parseInt(text.trim());
                            if (ranks().active() && level > own) lang().send(p, "rank.not-allowed");
                            else ranks().setCommandLevel(rule.getKey(), Math.max(0, level));
                        } catch (NumberFormatException ex) {
                            lang().send(p, "error.bad-number", "input", text);
                        }
                        levels(p, page);
                    }, () -> levels(p, page));
                }, n -> levels(p, n), () -> list(p));
        m.set(51, item(Material.NETHER_STAR, "menu.rank-levels.add"), e -> {
            if (!ranks().isTop(p)) {
                lang().send(p, "rank.not-allowed");
                return;
            }
            plugin.input().ask(p, "prompt.rank-command-level", text -> {
                    String[] parts = text.trim().split("\\s+");
                    try {
                        int level = Integer.parseInt(parts[parts.length - 1]);
                        String cmd = String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1));
                        if (cmd.isEmpty() || !ranks().isTop(p)) lang().send(p, "rank.not-allowed");
                        else ranks().setCommandLevel(cmd, Math.max(0, level));
                    } catch (NumberFormatException ex) {
                        lang().send(p, "error.bad-number", "input", text);
                    }
                    levels(p, 0);
                }, () -> levels(p, 0));
        });
        m.open(p);
    }
}
