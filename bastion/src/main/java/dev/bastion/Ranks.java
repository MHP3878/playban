package dev.bastion;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ranks & rights: the ranks that count on this server (from StaffCore, LuckPerms, or without a rank
 * plugin: everybody / operators), who has which, and which rank may do what in Bastion. The owner
 * can also set it for a single player (allowed, forbidden, or as the rank says).
 */
public final class Ranks implements Listener {
    /** What can be given: Bastion's permissions, in the order of the menu. */
    public static final List<String> RIGHTS = List.of("bastion.check", "bastion.akte", "bastion.notify", "bastion.block", "bastion.unban",
            "bastion.settings", "bastion.ranks", "bastion.bypass");
    private static final Map<String, Material> ICONS = Map.of("bastion.check", Material.SPYGLASS, "bastion.akte", Material.WRITABLE_BOOK,
            "bastion.notify", Material.BELL, "bastion.block", Material.BARRIER, "bastion.unban", Material.LIME_DYE,
            "bastion.settings", Material.COMPARATOR, "bastion.ranks", Material.NAME_TAG, "bastion.bypass", Material.ELYTRA);

    /** One rank: its id, how it is shown, its power, who has it. */
    public record Group(String id, String name, int weight, List<UUID> members) {}

    /** Where the ranks come from. */
    interface Source {
        String name();

        List<Group> groups();

        boolean has(Group g, String node);

        void set(Group g, String node, boolean on);

        List<String> groupsOf(UUID player);
    }

    private final BastionPlugin plugin;
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    public Ranks(BastionPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    /** StaffCore's ranks when it has them, else LuckPerms' groups, else Bastion's own two. */
    Source source() {
        var pm = Bukkit.getPluginManager();
        var sc = pm.getPlugin("StaffCore");
        if (sc != null && sc.isEnabled()) {
            StaffCoreRanks s = StaffCoreRanks.of(sc);
            if (s != null) return s;
        }
        if (pm.isPluginEnabled("LuckPerms")) {
            try {
                return new LuckPermsRanks();
            } catch (Throwable ignored) {
                // another LuckPerms version
            }
        }
        return new OwnRanks();
    }

    // ---------------------------------------------------------------- single players

    /** null = as the rank says, true = allowed, false = forbidden. */
    public Boolean playerRight(UUID id, String node) {
        String path = "rights.players." + id + "." + node.replace('.', '_');
        return plugin.getConfig().isSet(path) ? plugin.getConfig().getBoolean(path) : null;
    }

    public void setPlayerRight(UUID id, String node, Boolean value) {
        plugin.getConfig().set("rights.players." + id + "." + node.replace('.', '_'), value);
        plugin.saveConfig();
        Player p = Bukkit.getPlayer(id);
        if (p != null) apply(p);
    }

    /** Bastion's own rights for a player: the ones set for them alone, and without a rank plugin those of "everybody". */
    public void apply(Player p) {
        PermissionAttachment old = attachments.remove(p.getUniqueId());
        if (old != null) {
            try {
                p.removeAttachment(old);
            } catch (IllegalArgumentException ignored) {
                // gone with a rejoin
            }
        }
        Map<String, Boolean> set = new LinkedHashMap<>();
        if (source() instanceof OwnRanks) {
            for (String node : plugin.getConfig().getStringList("rights.groups.everybody")) set.put(node, true);
        }
        for (String node : RIGHTS) {
            Boolean v = playerRight(p.getUniqueId(), node);
            if (v != null) set.put(node, v);
        }
        if (set.isEmpty()) return;
        PermissionAttachment a = p.addAttachment(plugin);
        set.forEach(a::setPermission);
        attachments.put(p.getUniqueId(), a);
        p.updateCommands();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        apply(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        attachments.remove(e.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- menus

    private Component rightName(String node) {
        return lang().msg("rights.right." + node.substring("bastion.".length()));
    }

    public void open(Player p) {
        if (!p.hasPermission("bastion.ranks")) {
            lang().send(p, "no-permission");
            return;
        }
        Source src = source();
        List<Group> groups = src.groups();
        Menu m = new Menu(6, lang().msg("rights.title"));
        m.set(4, Menu.item(Material.KNOWLEDGE_BOOK, lang().msg("rights.source.name", "source", src.name()), lang().lore("rights.source.lore")), null);
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        for (int i = 0; i < groups.size() && i < slots.length; i++) {
            Group g = groups.get(i);
            List<Component> lore = new ArrayList<>();
            lore.add(lang().msg("rights.group.members", "count", g.members().size()));
            List<String> names = new ArrayList<>();
            for (UUID u : g.members()) {
                if (names.size() >= 6) break;
                names.add(Punish.nameOf(u));
            }
            if (!names.isEmpty()) lore.add(lang().msg("rights.group.names", "names", String.join(", ", names) + (g.members().size() > 6 ? " …" : "")));
            lore.add(Component.empty());
            for (String node : RIGHTS) {
                lore.add(lang().msg(src.has(g, node) ? "rights.can" : "rights.cannot", "right", lang().raw("rights.right." + node.substring(8))));
            }
            lore.add(Component.empty());
            lore.add(lang().msg("rights.group.click"));
            m.set(slots[i], Menu.item(i == 0 ? Material.GOLDEN_HELMET : Material.LEATHER_HELMET, lang().msg("rights.group.name", "rank", g.name(),
                    "weight", g.weight()), lore), e -> group(p, g.id()));
        }
        m.set(49, Menu.item(Material.OAK_DOOR, lang().msg("menu.back.name"), List.of()), e -> plugin.menus().main(p));
        m.fill().open(p);
    }

    public void group(Player p, String id) {
        Source src = source();
        Group g = src.groups().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
        if (g == null) {
            open(p);
            return;
        }
        Menu m = new Menu(6, lang().msg("rights.group-title", "rank", g.name()));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        for (int i = 0; i < RIGHTS.size(); i++) {
            String node = RIGHTS.get(i);
            boolean on = src.has(g, node);
            List<Component> lore = new ArrayList<>(lang().lore("rights.desc." + node.substring(8)));
            lore.add(Component.empty());
            lore.add(lang().msg(on ? "rights.state-on" : "rights.state-off"));
            m.set(slots[i], Menu.item(on ? ICONS.get(node) : Material.GRAY_DYE, rightName(node), lore), e -> {
                src.set(g, node, !on);
                plugin.guard().log(p.getName(), "rights", g.name(), node + "=" + !on);
                for (Player o : Bukkit.getOnlinePlayers()) apply(o);
                group(p, id);
            });
        }
        // the members: a click opens their own rights
        int slot = 36;
        for (UUID u : g.members()) {
            if (slot > 44) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
            String name = Punish.nameOf(u);
            m.set(slot++, Menu.head(op, lang().msg("rights.member", "player", name), lang().lore("rights.member-lore")), e -> player(p, u, name));
        }
        m.set(49, Menu.item(Material.OAK_DOOR, lang().msg("menu.back.name"), List.of()), e -> open(p));
        m.fill().open(p);
    }

    /** One player's rights: as the rank says, allowed or forbidden, a click each. */
    public void player(Player p, UUID id, String name) {
        if (!p.hasPermission("bastion.ranks")) {
            lang().send(p, "no-permission");
            return;
        }
        Source src = source();
        Menu m = new Menu(4, lang().msg("rights.player-title", "player", name));
        List<String> ranks = src.groupsOf(id);
        m.set(4, Menu.head(Bukkit.getOfflinePlayer(id), lang().msg("rights.member", "player", name),
                lang().lore("rights.player-head", "ranks", ranks.isEmpty() ? "-" : String.join(", ", ranks))), null);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        Player online = Bukkit.getPlayer(id);
        for (int i = 0; i < RIGHTS.size(); i++) {
            String node = RIGHTS.get(i);
            Boolean own = playerRight(id, node);
            boolean now = online != null ? online.hasPermission(node) : own != null && own;
            String state = own == null ? "rights.state-rank" : own ? "rights.state-allowed" : "rights.state-forbidden";
            List<Component> lore = new ArrayList<>(lang().lore("rights.desc." + node.substring(8)));
            lore.add(Component.empty());
            lore.add(lang().msg(state));
            if (online != null) lore.add(lang().msg(now ? "rights.has-now" : "rights.has-not"));
            lore.add(lang().msg("rights.player-click"));
            Material icon = own == null ? (now ? ICONS.get(node) : Material.GRAY_DYE) : own ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
            boolean self = p.getUniqueId().equals(id) && node.equals("bastion.ranks");
            m.set(slots[i], Menu.item(icon, rightName(node), lore), self ? null : e -> {
                Boolean next = own == null ? Boolean.TRUE : own ? Boolean.FALSE : null;
                setPlayerRight(id, node, next);
                plugin.guard().log(p.getName(), "rights", name, node + "=" + (next == null ? "rank" : next));
                plugin.akte().event(id, "rights", node + " → " + (next == null ? "rank" : next ? "allowed" : "forbidden"), p.getName());
                player(p, id, name);
            });
        }
        m.set(31, Menu.item(Material.OAK_DOOR, lang().msg("menu.back.name"), List.of()), e -> open(p));
        m.fill().open(p);
    }

    // ---------------------------------------------------------------- the sources

    /** Without a rank plugin: "everybody" (rights in Bastion's config) and the operators (all rights). */
    final class OwnRanks implements Source {
        public String name() {
            return lang().raw("rights.source.own");
        }

        public List<Group> groups() {
            List<UUID> ops = new ArrayList<>();
            for (OfflinePlayer op : Bukkit.getOperators()) ops.add(op.getUniqueId());
            List<UUID> all = new ArrayList<>();
            for (Player o : Bukkit.getOnlinePlayers()) all.add(o.getUniqueId());
            return List.of(new Group("op", lang().raw("rights.op"), 100, ops), new Group("everybody", lang().raw("rights.everybody"), 0, all));
        }

        public boolean has(Group g, String node) {
            if (g.id().equals("op")) return !node.equals("bastion.bypass");
            return plugin.getConfig().getStringList("rights.groups.everybody").contains(node);
        }

        public void set(Group g, String node, boolean on) {
            if (g.id().equals("op")) return;
            List<String> l = new ArrayList<>(plugin.getConfig().getStringList("rights.groups.everybody"));
            l.remove(node);
            if (on) l.add(node);
            plugin.getConfig().set("rights.groups.everybody", l);
            plugin.saveConfig();
        }

        public List<String> groupsOf(UUID player) {
            return Bukkit.getOfflinePlayer(player).isOp() ? List.of(lang().raw("rights.op")) : List.of(lang().raw("rights.everybody"));
        }
    }

    /** StaffCore's ranks, read and changed through StaffCore itself (it gives the permissions). */
    static final class StaffCoreRanks implements Source {
        private final Object manager;

        private StaffCoreRanks(Object manager) {
            this.manager = manager;
        }

        static StaffCoreRanks of(Object plugin) {
            try {
                Object m = plugin.getClass().getMethod("ranks").invoke(plugin);
                if (m == null || !Boolean.TRUE.equals(m.getClass().getMethod("enabled").invoke(m))) return null;
                return new StaffCoreRanks(m);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        public String name() {
            return "StaffCore";
        }

        private List<?> all() throws ReflectiveOperationException {
            return (List<?>) manager.getClass().getMethod("all").invoke(manager);
        }

        private static Object field(Object o, String name) throws ReflectiveOperationException {
            Field f = o.getClass().getField(name);
            return f.get(o);
        }

        private Object rank(String id) throws ReflectiveOperationException {
            for (Object r : all()) if (id.equals(field(r, "id"))) return r;
            return null;
        }

        @SuppressWarnings("unchecked")
        public List<Group> groups() {
            List<Group> out = new ArrayList<>();
            try {
                Method members = null;
                for (Method m : manager.getClass().getMethods()) if (m.getName().equals("members")) members = m;
                for (Object r : all()) {
                    List<UUID> mem = members == null ? List.of() : new ArrayList<>((List<UUID>) members.invoke(manager, r));
                    out.add(new Group((String) field(r, "id"), (String) field(r, "name"), (int) field(r, "level"), mem));
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // StaffCore changed: no ranks shown
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        public boolean has(Group g, String node) {
            try {
                Object r = rank(g.id());
                if (r == null) return false;
                List<String> perms = (List<String>) field(r, "permissions");
                return perms.contains(node) || perms.contains("*") || perms.contains("bastion.*") || node.startsWith("bastion.") && perms.contains("bastion.admin")
                        && !node.equals("bastion.bypass");
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }

        @SuppressWarnings("unchecked")
        public void set(Group g, String node, boolean on) {
            try {
                Object r = rank(g.id());
                if (r == null) return;
                List<String> perms = (List<String>) field(r, "permissions");
                perms.remove(node);
                if (on) perms.add(node);
                for (Method m : manager.getClass().getMethods()) if (m.getName().equals("changed")) m.invoke(manager, r);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // not changed
            }
        }

        public List<String> groupsOf(UUID player) {
            try {
                Object r = manager.getClass().getMethod("of", UUID.class).invoke(manager, player);
                return r == null ? List.of() : List.of((String) field(r, "name"));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return List.of();
            }
        }
    }

    /** LuckPerms' groups, changed through LuckPerms (it gives the permissions). */
    static final class LuckPermsRanks implements Source {
        private final net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();

        public String name() {
            return "LuckPerms";
        }

        public List<Group> groups() {
            List<Group> out = new ArrayList<>();
            Map<String, List<UUID>> members = new HashMap<>();
            // who has which group: the players LuckPerms has loaded (online), plus the ones it finds stored
            for (Player o : Bukkit.getOnlinePlayers()) {
                var user = lp.getUserManager().getUser(o.getUniqueId());
                if (user == null) continue;
                for (var grp : user.getInheritedGroups(user.getQueryOptions())) members.computeIfAbsent(grp.getName(), k -> new ArrayList<>()).add(o.getUniqueId());
            }
            for (var grp : lp.getGroupManager().getLoadedGroups()) {
                try {
                    var found = lp.getUserManager().searchAll(net.luckperms.api.node.matcher.NodeMatcher.key("group." + grp.getName()))
                            .get(2, java.util.concurrent.TimeUnit.SECONDS);
                    for (UUID u : found.keySet()) {
                        List<UUID> l = members.computeIfAbsent(grp.getName(), k -> new ArrayList<>());
                        if (!l.contains(u)) l.add(u);
                    }
                } catch (Exception ignored) {
                    // the online ones only
                }
                String shown = grp.getDisplayName() == null ? grp.getName() : grp.getDisplayName();
                out.add(new Group(grp.getName(), shown, grp.getWeight().orElse(0), members.getOrDefault(grp.getName(), List.of())));
            }
            out.sort((a, b) -> Integer.compare(b.weight(), a.weight()));
            return out;
        }

        public boolean has(Group g, String node) {
            var grp = lp.getGroupManager().getGroup(g.id());
            return grp != null && grp.getCachedData().getPermissionData().checkPermission(node).asBoolean();
        }

        public void set(Group g, String node, boolean on) {
            var grp = lp.getGroupManager().getGroup(g.id());
            if (grp == null) return;
            var n = net.luckperms.api.node.Node.builder(node).value(true).build();
            if (on) grp.data().add(n);
            else grp.data().remove(n);
            lp.getGroupManager().saveGroup(grp);
        }

        public List<String> groupsOf(UUID player) {
            var user = lp.getUserManager().getUser(player);
            if (user == null) return List.of();
            List<String> out = new ArrayList<>();
            for (var grp : user.getInheritedGroups(user.getQueryOptions())) out.add(grp.getName());
            return out;
        }
    }
}
