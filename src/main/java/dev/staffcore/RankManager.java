package dev.staffcore;

import dev.staffcore.model.Rank;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ranks such as Owner, Admin or Mod: who has which, the permissions they give,
 * how they show in chat and the tab list, and the command levels that also hold for ops.
 */
public final class RankManager implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final StaffCorePlugin plugin;
    private final Map<String, Rank> ranks = new LinkedHashMap<>();
    /** Read from the chat thread, so it is thread-safe. */
    private final Map<UUID, String> players = new ConcurrentHashMap<>();
    private final Map<String, Integer> commandLevels = new LinkedHashMap<>();
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private YamlConfiguration cfg = new YamlConfiguration();
    private boolean loadFailed;

    public RankManager(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private File file() {
        return new File(plugin.getDataFolder(), "ranks.yml");
    }

    // ---------------------------------------------------------------- storage

    public void load() {
        if (!file().exists()) plugin.saveResource("ranks.yml", false);
        cfg = new YamlConfiguration();
        ranks.clear();
        players.clear();
        commandLevels.clear();
        try {
            cfg.load(file());
        } catch (Exception e) {
            loadFailed = true;
            plugin.getLogger().severe("ranks.yml could not be read: " + e.getMessage() + ". Ranks are switched off until it is fixed.");
            return;
        }
        loadFailed = false;
        ConfigurationSection rs = cfg.getConfigurationSection("ranks");
        if (rs != null) for (String id : rs.getKeys(false)) {
            ConfigurationSection s = rs.getConfigurationSection(id);
            if (s == null) continue;
            Rank r = new Rank();
            r.id = id.toLowerCase();
            r.name = s.getString("name", id);
            r.level = s.getInt("level", 0);
            r.prefix = s.getString("prefix", "");
            r.permissions.addAll(s.getStringList("permissions"));
            ranks.put(r.id, r);
        }
        ConfigurationSection ps = cfg.getConfigurationSection("players");
        if (ps != null) for (String k : ps.getKeys(false)) {
            try {
                String rank = ps.getString(k, "").toLowerCase();
                if (ranks.containsKey(rank)) players.put(UUID.fromString(k), rank);
            } catch (IllegalArgumentException ignored) {
                // a hand-edited entry that is not a UUID
            }
        }
        ConfigurationSection cl = cfg.getConfigurationSection("command-levels");
        if (cl != null) for (String k : cl.getKeys(false)) commandLevels.put(k.toLowerCase().trim(), cl.getInt(k));
    }

    public void save() {
        if (loadFailed) return;
        cfg.set("ranks", null);
        for (Rank r : ranks.values()) {
            String p = "ranks." + r.id + ".";
            cfg.set(p + "name", r.name);
            cfg.set(p + "level", r.level);
            cfg.set(p + "prefix", r.prefix);
            cfg.set(p + "permissions", new ArrayList<>(r.permissions));
        }
        cfg.set("players", null);
        cfg.createSection("players");
        for (Map.Entry<UUID, String> e : players.entrySet()) cfg.set("players." + e.getKey(), e.getValue());
        cfg.set("command-levels", null);
        cfg.createSection("command-levels");
        for (Map.Entry<String, Integer> e : commandLevels.entrySet()) cfg.set("command-levels." + e.getKey(), e.getValue());
        try {
            cfg.save(file());
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save ranks.yml: " + e.getMessage());
        }
    }

    public boolean enabled() {
        return !loadFailed && cfg.getBoolean("enabled", true);
    }

    // ---------------------------------------------------------------- lookups

    public List<Rank> all() {
        List<Rank> list = new ArrayList<>(ranks.values());
        list.sort(Comparator.comparingInt((Rank r) -> r.level).reversed());
        return list;
    }

    public Rank get(String id) {
        return id == null ? null : ranks.get(id.toLowerCase());
    }

    public Rank defaultRank() {
        Rank r = get(cfg.getString("default-rank", "player"));
        if (r != null) return r;
        Rank lowest = null;
        for (Rank x : ranks.values()) if (lowest == null || x.level < lowest.level) lowest = x;
        return lowest;
    }

    public Rank top() {
        Rank top = null;
        for (Rank x : ranks.values()) if (top == null || x.level > top.level) top = x;
        return top;
    }

    public Rank of(UUID player) {
        Rank r = get(players.get(player));
        return r != null ? r : defaultRank();
    }

    public int level(UUID player) {
        Rank r = of(player);
        return r == null ? 0 : r.level;
    }

    public List<UUID> members(Rank rank) {
        List<UUID> out = new ArrayList<>();
        for (Map.Entry<UUID, String> e : players.entrySet()) if (e.getValue().equals(rank.id)) out.add(e.getKey());
        return out;
    }

    /** Command levels only start once someone has the highest rank, so setting up cannot lock anybody out. */
    public boolean active() {
        if (!enabled()) return false;
        Rank top = top();
        return top != null && players.containsValue(top.id);
    }

    public Map<String, Integer> commandLevels() {
        return new LinkedHashMap<>(commandLevels);
    }

    public void setCommandLevel(String command, Integer level) {
        String key = command.toLowerCase().trim().replaceFirst("^/", "");
        if (level == null) commandLevels.remove(key);
        else commandLevels.put(key, level);
        save();
        for (Player p : Bukkit.getOnlinePlayers()) apply(p);
    }

    // ---------------------------------------------------------------- changing

    /** Who may change a rank: the console always, a player only below their own level. */
    public boolean mayManage(org.bukkit.command.CommandSender sender, int level) {
        if (!(sender instanceof Player p)) return true;
        if (!active()) return p.isOp();
        return level < level(p.getUniqueId());
    }

    /** May change command rules, the default rank and wildcard permissions: only the highest rank (or an op while ranks are not set up). */
    public boolean isTop(org.bukkit.command.CommandSender sender) {
        if (!(sender instanceof Player p)) return true;
        if (!active()) return p.isOp();
        Rank top = top();
        return top != null && level(p.getUniqueId()) >= top.level;
    }

    /** A rank editor may only hand out what they have themselves; "*" and "x.*" only the highest rank. */
    public boolean mayGrant(org.bukkit.command.CommandSender sender, String node) {
        if (isTop(sender)) return true;
        if (node.equals("*") || node.endsWith(".*")) return false;
        return sender.hasPermission(node);
    }

    public void set(UUID player, Rank rank) {
        if (rank == null || rank == defaultRank()) players.remove(player);
        else players.put(player, rank.id);
        save();
        Player online = Bukkit.getPlayer(player);
        if (online != null) apply(online);
        for (Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
    }

    public Rank create(String name) {
        String base = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isEmpty()) base = "rank";
        String id = base;
        for (int i = 2; ranks.containsKey(id); i++) id = base + "-" + i;
        Rank r = new Rank();
        r.id = id;
        r.name = name;
        r.level = 10;
        r.prefix = "<white>" + name + " <gray>";
        ranks.put(id, r);
        save();
        return r;
    }

    public void delete(Rank rank) {
        if (rank == defaultRank()) return;
        ranks.remove(rank.id);
        players.values().removeIf(v -> v.equals(rank.id));
        save();
        for (Player p : Bukkit.getOnlinePlayers()) apply(p);
    }

    public void changed(Rank rank) {
        save();
        for (Player p : Bukkit.getOnlinePlayers()) if (of(p.getUniqueId()) == rank) apply(p);
        for (Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
    }

    // ---------------------------------------------------------------- permissions and display

    /** Every registered permission node a rank entry stands for: "*" and "x.*" are expanded. */
    private List<String> expand(String node) {
        List<String> out = new ArrayList<>();
        if (node.equals("*")) {
            for (Permission p : Bukkit.getPluginManager().getPermissions()) out.add(p.getName());
            return out;
        }
        if (node.endsWith(".*")) {
            String prefix = node.substring(0, node.length() - 1);
            for (Permission p : Bukkit.getPluginManager().getPermissions()) if (p.getName().startsWith(prefix)) out.add(p.getName());
        }
        out.add(node);
        return out;
    }

    /** Gives a player the permissions of their rank and shows the rank in the tab list and above the head. */
    public void apply(Player p) {
        PermissionAttachment old = attachments.remove(p.getUniqueId());
        if (old != null) {
            try {
                p.removeAttachment(old);
            } catch (IllegalArgumentException ignored) {
                // already gone after a rejoin
            }
        }
        if (!enabled()) return;
        Rank rank = of(p.getUniqueId());
        java.util.Set<String> denied = deniedPermissions(p);
        if ((rank != null && !rank.permissions.isEmpty()) || !denied.isEmpty()) {
            PermissionAttachment a = p.addAttachment(plugin);
            if (rank != null) for (String node : rank.permissions) for (String n : expand(node)) a.setPermission(n, true);
            // the permission of every command above the player's level is taken away, ops included:
            // so the rule also holds when a plugin, a menu or a command alias runs the command
            for (String n : denied) a.setPermission(n, false);
            attachments.put(p.getUniqueId(), a);
        }
        p.recalculatePermissions();
        p.updateCommands();
        if (cfg.getBoolean("tab-list", true) && rank != null) {
            Component prefix = prefix(rank);
            p.playerListName(prefix.append(Component.text(p.getName())));
            try {
                Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
                // team names sort the tab list: a higher level comes first
                String teamName = "sc" + String.format("%03d", Math.max(0, 999 - rank.level)) + rank.id;
                if (teamName.length() > 32) teamName = teamName.substring(0, 32);
                Team team = board.getTeam(teamName);
                if (team == null) team = board.registerNewTeam(teamName);
                team.prefix(prefix);
                for (Team t : board.getTeams()) if (t != team && t.getName().startsWith("sc") && t.hasEntry(p.getName())) t.removeEntry(p.getName());
                team.addEntry(p.getName());
            } catch (RuntimeException ignored) {
                // another plugin owns the main scoreboard; the tab list name above is still set
            }
        }
    }

    private java.util.Set<String> deniedPermissions(Player p) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (!active() || !cfg.getBoolean("enforce-permissions", true)) return out;
        int own = level(p.getUniqueId());
        for (Map.Entry<String, Integer> rule : commandLevels.entrySet()) {
            if (rule.getKey().contains(" ") || own >= rule.getValue()) continue;
            Command cmd = Bukkit.getCommandMap().getCommand(rule.getKey());
            if (cmd == null || cmd.getPermission() == null) continue;
            for (String perm : cmd.getPermission().split(";")) {
                // our own broad nodes stay: StaffCore checks its commands itself
                if (!perm.isBlank() && !perm.startsWith("staffcore.")) out.add(perm.trim());
            }
        }
        return out;
    }

    /** Rank prefixes may only use colors and text styles: no click or hover actions in somebody's name. */
    private static final MiniMessage PREFIX_MM = MiniMessage.builder().tags(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
            net.kyori.adventure.text.minimessage.tag.standard.StandardTags.color(),
            net.kyori.adventure.text.minimessage.tag.standard.StandardTags.decorations(),
            net.kyori.adventure.text.minimessage.tag.standard.StandardTags.gradient(),
            net.kyori.adventure.text.minimessage.tag.standard.StandardTags.rainbow(),
            net.kyori.adventure.text.minimessage.tag.standard.StandardTags.reset())).build();

    public static Component prefix(Rank rank) {
        return PREFIX_MM.deserialize(rank.prefix);
    }

    public void unapply(Player p) {
        PermissionAttachment old = attachments.remove(p.getUniqueId());
        if (old != null) {
            try {
                p.removeAttachment(old);
            } catch (IllegalArgumentException ignored) {
                // already gone
            }
        }
    }

    // ---------------------------------------------------------------- command levels

    /** The level a command line needs, or -1 when it has no rule. */
    public int requiredLevel(String commandLine) {
        String line = commandLine.trim().replaceFirst("^/", "").toLowerCase();
        String[] parts = line.split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return -1;
        String label = parts[0].contains(":") ? parts[0].substring(parts[0].indexOf(':') + 1) : parts[0];
        java.util.Set<String> names = new java.util.HashSet<>();
        names.add(label);
        Command cmd = Bukkit.getCommandMap().getCommand(label);
        if (cmd != null) {
            names.add(cmd.getName().toLowerCase());
            names.add(cmd.getLabel().toLowerCase());
            for (String a : cmd.getAliases()) names.add(a.toLowerCase());
        }
        // the main command is written "sc" in the rules, whichever alias is typed
        if (names.contains("staffcore") || names.contains("playban") || names.contains("pb")) names.add("sc");
        int best = -1;
        for (Map.Entry<String, Integer> e : commandLevels.entrySet()) {
            String[] key = e.getKey().split("\\s+");
            if (!names.contains(key[0])) continue;
            if (key.length > 1) {
                if (parts.length < 2 || !parts[1].equals(key[1])) continue;
                return e.getValue(); // a rule for a sub-command is the more exact one
            }
            best = e.getValue();
        }
        return best;
    }

    public boolean mayUse(Player p, String commandLine) {
        if (!active()) return true;
        int need = requiredLevel(commandLine);
        return need < 0 || level(p.getUniqueId()) >= need;
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        apply(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        unapply(e.getPlayer());
    }

    /** Refuses commands below the player's level, ops included. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (mayUse(e.getPlayer(), e.getMessage())) return;
        e.setCancelled(true);
        plugin.lang().send(e.getPlayer(), "rank.too-low", "level", requiredLevel(e.getMessage()));
    }

    /** And takes them out of the suggestions as well. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCommandList(PlayerCommandSendEvent e) {
        if (!active()) return;
        int own = level(e.getPlayer().getUniqueId());
        e.getCommands().removeIf(c -> {
            int need = requiredLevel(c);
            return need >= 0 && own < need && !c.contains(" ");
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!enabled() || !cfg.getBoolean("chat-format", true)) return;
        Rank rank = of(e.getPlayer().getUniqueId());
        String layout = cfg.getString("chat-layout", "<prefix><name><dark_gray>:</dark_gray> <white><message>");
        Component prefix = rank == null ? Component.empty() : prefix(rank);
        e.renderer(ChatRenderer.viewerUnaware((source, name, message) -> MM.deserialize(layout,
                Placeholder.component("prefix", prefix),
                Placeholder.component("name", Component.text(source.getName())),
                // what the player typed is inserted as plain text, so nobody can sneak colors or click events in
                Placeholder.component("message", message))));
    }
}
