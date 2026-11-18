package dev.staffcore.module;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import dev.staffcore.TimeUtil;
import dev.staffcore.gui.Items;
import dev.staffcore.gui.Menu;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /check: everything about one player on one page, live. Ping, client, health, where they are,
 * clicks per second, the anti-cheat account and its last findings, the last commands, and buttons for
 * everything a team member then wants to do: look into the inventory, freeze, watch, follow, punish.
 */
public final class PlayerCheck implements TabExecutor, Listener {
    /** Commands that carry passwords never land in the list. */
    private static final Set<String> SECRET = Set.of("login", "l", "register", "reg", "changepassword", "changepw", "cp", "unregister", "2fa", "auth");

    private final StaffCorePlugin plugin;
    private final Map<UUID, Deque<String>> commands = new HashMap<>();
    private final Map<UUID, BukkitTask> live = new HashMap<>();

    public PlayerCheck(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage();
        String label = msg.substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (label.contains(":")) label = label.substring(label.indexOf(':') + 1);
        if (SECRET.contains(label)) msg = "/" + label + " ***";
        Deque<String> d = commands.computeIfAbsent(e.getPlayer().getUniqueId(), k -> new ArrayDeque<>());
        d.addFirst(new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + (msg.length() > 60 ? msg.substring(0, 60) + "…" : msg)
                + (e.isCancelled() ? " ✗" : ""));
        while (d.size() > 10) d.removeLast();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        BukkitTask t = live.remove(e.getPlayer().getUniqueId());
        if (t != null) t.cancel();
    }

    public List<String> lastCommands(UUID id) {
        return new ArrayList<>(commands.getOrDefault(id, new ArrayDeque<>()));
    }

    private Component line(String key, Object... args) {
        return lang().msg(key, args);
    }

    /** The page; it renews itself every second while it is open. */
    public void open(Player staff, Player target) {
        Menu m = build(staff, target);
        m.open(staff);
        BukkitTask old = live.remove(staff.getUniqueId());
        if (old != null) old.cancel();
        live.put(staff.getUniqueId(), Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            boolean stillOpen = staff.isOnline() && staff.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu && menu == current.get(staff.getUniqueId());
            if (!stillOpen || !target.isOnline()) {
                BukkitTask t = live.remove(staff.getUniqueId());
                if (t != null) t.cancel();
                return;
            }
            Menu fresh = build(staff, target);
            Menu shown = current.get(staff.getUniqueId());
            // only the live items change, the page stays open
            for (int slot : new int[]{4, 10, 12, 14, 16}) shown.getInventory().setItem(slot, fresh.getInventory().getItem(slot));
        }, 20L, 20L));
    }

    private final Map<UUID, Menu> current = new HashMap<>();

    private Menu build(Player staff, Player t) {
        Menu m = new Menu(6, lang().msg("check.title", "player", t.getName()));
        current.put(staff.getUniqueId(), m);
        String brand = null;
        try {
            brand = t.getClientBrandName();
        } catch (RuntimeException | NoSuchMethodError ignored) {
            // older API
        }
        var l = t.getLocation();
        OfflinePlayer op = t;
        long first = op.getFirstPlayed();
        List<Component> info = new ArrayList<>(List.of(
                line("check.ping", "ping", t.getPing()),
                line("check.client", "client", brand == null ? "?" : brand),
                line("check.mode", "mode", t.getGameMode().name().toLowerCase()),
                line("check.health", "health", (int) Math.ceil(t.getHealth()), "food", t.getFoodLevel()),
                line("check.where", "world", l.getWorld().getName(), "x", l.getBlockX(), "y", l.getBlockY(), "z", l.getBlockZ()),
                line("check.first", "date", first > 0 ? new SimpleDateFormat("dd.MM.yyyy").format(new Date(first)) : "?")));
        if (plugin.punishments().isPunished(t.getUniqueId())) info.add(line("check.punished"));
        if (plugin.admin().isFrozen(t.getUniqueId())) info.add(line("check.frozen"));
        m.set(4, Items.head(t, lang().msg("check.name", "player", t.getName()), info));

        AntiCheat ac = plugin.antiCheat();
        List<Component> acLore = new ArrayList<>();
        acLore.add(line("check.ac-total", "points", String.format(Locale.ROOT, "%.1f", ac.total(t.getUniqueId())), "limit", (int) ac.limit()));
        for (Map.Entry<String, Double> e : ac.account(t.getUniqueId()).entrySet()) {
            acLore.add(line("check.ac-line", "check", lang().raw("anticheat.check." + e.getKey()), "points", String.format(Locale.ROOT, "%.1f", e.getValue())));
        }
        acLore.add(line(ac.watched(t.getUniqueId()) ? "check.watched" : "check.not-watched"));
        m.set(10, Items.of(Material.IRON_SWORD, lang().msg("check.anticheat"), acLore));

        List<Component> found = new ArrayList<>();
        for (String f : ac.recentFindings(t.getUniqueId())) found.add(Component.text(f, net.kyori.adventure.text.format.NamedTextColor.GRAY));
        if (found.isEmpty()) found.add(line("check.nothing"));
        m.set(12, Items.of(Material.WRITABLE_BOOK, lang().msg("check.findings"), found));

        List<Component> cmds = new ArrayList<>();
        for (String c : lastCommands(t.getUniqueId())) cmds.add(Component.text(c, net.kyori.adventure.text.format.NamedTextColor.GRAY));
        if (cmds.isEmpty()) cmds.add(line("check.nothing"));
        m.set(14, Items.of(Material.COMMAND_BLOCK, lang().msg("check.commands"), cmds));

        int cps = ac.cps(t.getUniqueId());
        m.set(16, Items.of(cps > 15 ? Material.REDSTONE_BLOCK : Material.STONE_BUTTON, lang().msg("check.cps", "cps", cps), lang().lore("check.cps-lore")));

        // actions
        m.set(28, Items.of(Material.CHEST, lang().msg("check.invsee"), List.of()), e -> plugin.admin().openInventory(staff, t, false));
        m.set(29, Items.of(Material.ENDER_CHEST, lang().msg("check.endersee"), List.of()), e -> plugin.admin().openInventory(staff, t, true));
        boolean frozen = plugin.admin().isFrozen(t.getUniqueId());
        m.set(30, Items.of(Material.BLUE_ICE, lang().msg(frozen ? "check.unfreeze" : "check.freeze"), List.of()), e -> {
            if (t.hasPermission("staffcore.exempt") && !staff.hasPermission("staffcore.exempt.bypass")) {
                lang().send(staff, "error.exempt", "player", t.getName());
                return;
            }
            plugin.admin().setFrozen(t, !frozen, staff.getName());
            open(staff, t);
        });
        boolean watched = ac.watched(t.getUniqueId());
        m.set(31, Items.of(Material.ENDER_EYE, lang().msg(watched ? "check.unwatch" : "check.watch"), List.of()), e -> {
            ac.setWatched(t.getUniqueId(), t.getName(), !watched, staff.getName());
            open(staff, t);
        });
        m.set(32, Items.of(Material.ECHO_SHARD, lang().msg("check.follow"), lang().lore("check.follow-lore")), e -> plugin.adminPanel().takeCase(staff, t.getUniqueId()));
        m.set(33, Items.of(Material.MILK_BUCKET, lang().msg("check.reset"), List.of()), e -> {
            staff.performCommand("anticheat reset " + t.getName());
            open(staff, t);
        });
        m.set(34, Items.of(Material.ANVIL, lang().msg("check.punish"), List.of()), e -> plugin.adminMenus().punishPlayer(staff, t.getUniqueId(), t.getName()));
        m.set(38, Items.of(Material.BOOK, lang().msg("check.profile"), List.of()), e -> plugin.adminMenus().profile(staff, t.getUniqueId(), t.getName()));
        m.set(39, Items.of(Material.BRUSH, lang().msg("check.blocklog"), List.of()), e -> {
            staff.closeInventory();
            staff.performCommand("blocklog player " + t.getName() + " 1d");
        });
        m.set(40, Items.of(Material.COMPASS, lang().msg("check.alts"), List.of()), e -> {
            staff.closeInventory();
            staff.performCommand("alts " + t.getName());
        });
        m.set(41, Items.of(Material.ENDER_PEARL, lang().msg("check.tp"), List.of()), e -> {
            staff.closeInventory();
            plugin.admin().tpAdmin(staff, t);
        });
        m.set(42, Items.of(Material.TRIPWIRE_HOOK, lang().msg("check.prison"), List.of()), e -> {
            staff.closeInventory();
            staff.performCommand("prison " + t.getName() + " 15m");
        });
        int clipCount = ac.clips(t.getUniqueId()).size();
        m.set(37, Items.of(Material.RECOVERY_COMPASS, lang().msg("check.replay", "count", clipCount), lang().lore("check.replay-lore")), e -> {
            staff.closeInventory();
            ac.replay(staff, t.getUniqueId(), e.isRightClick() ? "now" : null);
        });
        int restrictions = plugin.playerStatus().of(t.getUniqueId(), t.getName(), staff.getName()).size();
        m.set(44, Items.of(restrictions > 0 ? Material.MILK_BUCKET : Material.BUCKET, lang().msg("pstatus.ticket-button", "player", t.getName(), "count", restrictions),
                lang().lore("pstatus.ticket-button-lore")), e -> plugin.playerStatus().menu(staff, t.getUniqueId(), t.getName(), () -> open(staff, t)));
        m.set(43, Items.of(Material.BREWING_STAND, lang().msg("check.tests"), lang().lore("check.tests-lore")), e -> plugin.investigations().menu(staff, t));
        ModGuard.Info mods = plugin.modGuard().info(t.getUniqueId());
        List<Component> modLore = new ArrayList<>();
        modLore.add(line("check.mods-brand", "brand", mods.brand == null ? "?" : mods.brand));
        modLore.add(line("check.mods-channels", "count", mods.channels.size()));
        for (ModGuard.Rule r : plugin.modGuard().matches(mods)) modLore.add(line("check.mods-found", "mod", r.name(), "action", r.action()));
        m.set(25, Items.of(Material.NAME_TAG, lang().msg("check.mods"), modLore), e -> {
            staff.closeInventory();
            staff.performCommand("mods " + t.getName());
        });
        m.set(49, Items.of(Material.BARRIER, lang().msg("panel.close"), List.of()), e -> staff.closeInventory());
        return m.fill();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player staff)) {
            lang().send(sender, "error.players-only");
            return true;
        }
        if (args.length < 1) {
            lang().send(staff, "check.usage");
            return true;
        }
        Player t = Bukkit.getPlayerExact(args[0]);
        if (t == null) lang().send(staff, "admin.not-online", "player", args[0]);
        else open(staff, t);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[0].toLowerCase())) out.add(p.getName());
        return out;
    }

    /** For tests: when the page was last built. */
    public String since(long ms) {
        return TimeUtil.format(ms);
    }
}
