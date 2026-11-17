package dev.staffcore.module;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import dev.staffcore.gui.Items;
import dev.staffcore.gui.Menu;
import dev.staffcore.model.Ticket;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
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
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * /admin: the team member's own panel. An inbox with the tickets their rank may handle and the players
 * the anti-cheat marked, buttons for admin mode, vanish, ghost mode and ESP, and a list of every command
 * they may use. Outside admin mode a team member is a normal player: team commands only work after
 * switching it on (admin-mode.required).
 */
public final class AdminPanel implements TabExecutor, Listener {
    private final StaffCorePlugin plugin;

    public AdminPanel(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    public boolean active(Player p) {
        return plugin.tools().inStaffMode(p.getUniqueId());
    }

    private static boolean team(Player p) {
        return p.hasPermission("staffcore.staff") || p.hasPermission("staffcore.admin");
    }

    // ---------------------------------------------------------------- inbox content

    /** Whether this team member may handle tickets of this kind: by rank level, per ticket reason. */
    public boolean mayHandle(Player p, Ticket t) {
        if (!p.hasPermission("staffcore.staff")) return false;
        if (plugin.ranks() == null || !plugin.ranks().active()) return true;
        int need = plugin.getConfig().getInt("admin-panel.ticket-levels." + t.reason,
                plugin.getConfig().getInt("admin-panel.ticket-levels.default", 0));
        return plugin.ranks().level(p.getUniqueId()) >= need;
    }

    public List<Ticket> tickets(Player p) {
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : plugin.tickets().all()) {
            if ((t.status == Ticket.Status.OPEN || t.status == Ticket.Status.IN_PROGRESS) && mayHandle(p, t)) out.add(t);
        }
        out.sort(Comparator.comparingLong(t -> t.createdAt));
        return out;
    }

    private List<AntiCheat.Suspect> suspects(Player p) {
        if (!p.hasPermission("staffcore.anticheat") || plugin.antiCheat() == null) return List.of();
        return plugin.antiCheat().suspects();
    }

    // ---------------------------------------------------------------- the panel

    public void main(Player p) {
        Menu m = new Menu(6, lang().msg("panel.title"));
        boolean active = active(p);
        String rank = plugin.ranks() != null && plugin.ranks().enabled() && plugin.ranks().of(p.getUniqueId()) != null
                ? net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().stripTags(plugin.ranks().of(p.getUniqueId()).name) : "-";
        m.set(4, Items.head(p, lang().msg(active ? "panel.you-active" : "panel.you-inactive", "player", p.getName()),
                lang().lore("panel.you-lore", "rank", rank)));
        int open = tickets(p).size() + suspects(p).size();
        m.set(10, Items.of(open > 0 ? Material.WRITABLE_BOOK : Material.BOOK, lang().msg("panel.inbox", "count", open),
                lang().lore("panel.inbox-lore", "tickets", tickets(p).size(), "flagged", suspects(p).size())), e -> inbox(p, 0));
        if (p.hasPermission("staffcore.staffmode")) {
            m.set(12, Items.of(active ? Material.NETHERITE_CHESTPLATE : Material.LEATHER_CHESTPLATE,
                    lang().msg(active ? "panel.mode-on" : "panel.mode-off"), lang().lore("panel.mode-lore")), e -> {
                plugin.tools().toggleStaffMode(p);
                main(p);
            });
        }
        if (p.hasPermission("staffcore.vanish")) {
            boolean v = plugin.punishments().isVanished(p.getUniqueId());
            m.set(13, Items.of(v ? Material.PHANTOM_MEMBRANE : Material.GRAY_DYE, lang().msg(v ? "panel.vanish-on" : "panel.vanish-off"),
                    lang().lore("panel.vanish-lore")), e -> {
                plugin.punishments().setVanished(p, !v);
                main(p);
            });
        }
        if (p.hasPermission("staffcore.ghost")) {
            boolean g = plugin.ghost().on(p.getUniqueId());
            m.set(14, Items.of(g ? Material.ECHO_SHARD : Material.GRAY_DYE, lang().msg(g ? "panel.ghost-on" : "panel.ghost-off"),
                    lang().lore("panel.ghost-lore")), e -> {
                plugin.ghost().toggle(p);
                main(p);
            });
        }
        if (p.hasPermission("staffcore.esp") && plugin.modules().on("esp")) {
            m.set(15, Items.of(Material.SPYGLASS, lang().msg("panel.esp", "mode", lang().raw("esp.modes.mode-" + plugin.esp().mode(p.getUniqueId()).name().toLowerCase())),
                    lang().lore("panel.esp-lore")), e -> {
                if (!active(p)) plugin.tools().enterStaffMode(p);
                plugin.esp().cycle(p);
                main(p);
            });
        }
        m.set(16, Items.of(Material.COMMAND_BLOCK, lang().msg("panel.commands"), lang().lore("panel.commands-lore")), e -> commands(p, 0));
        // the marked players right in the panel
        List<AntiCheat.Suspect> flagged = suspects(p);
        if (!flagged.isEmpty()) m.set(27, Items.of(Material.RED_STAINED_GLASS_PANE, lang().msg("panel.flagged-row"), List.of()));
        for (int i = 0; i < Math.min(7, flagged.size()); i++) {
            AntiCheat.Suspect s = flagged.get(i);
            m.set(28 + i, suspectItem(s), e -> {
                if (e.isRightClick() && !e.isShiftClick()) {
                    plugin.antiCheat().setWatched(s.id, s.name, !s.watched, p.getName());
                    main(p);
                } else if (e.isShiftClick()) {
                    plugin.antiCheat().dismiss(s.id, p.getName());
                    main(p);
                } else takeCase(p, s.id);
            });
        }
        if (p.hasPermission("staffcore.staff")) m.set(46, Items.of(Material.COMPASS, lang().msg("panel.staffcore"), List.of()), e -> plugin.adminMenus().main(p));
        if (p.hasPermission("staffcore.tools")) m.set(47, Items.of(Material.MACE, lang().msg("panel.tools"), List.of()), e -> plugin.tools().menu(p));
        if (p.hasPermission("staffcore.admin")) m.set(52, Items.of(Material.BEACON, lang().msg("panel.modules"), List.of()), e -> plugin.modules().menu(p));
        m.set(49, Items.of(Material.BARRIER, lang().msg("panel.close"), List.of()), e -> p.closeInventory());
        m.fill().open(p);
    }

    private org.bukkit.inventory.ItemStack suspectItem(AntiCheat.Suspect s) {
        List<Component> lore = new ArrayList<>();
        lore.add(lang().msg("panel.flag-points", "points", (int) Math.round(plugin.antiCheat().total(s.id)), "limit", (int) plugin.antiCheat().limit()));
        if (s.watched) lore.add(lang().msg("panel.flag-watched"));
        for (Map.Entry<String, Double> c : s.checks.entrySet()) {
            lore.add(lang().msg("panel.flag-check", "check", lang().raw("anticheat.check." + c.getKey()), "vl", (int) Math.round(c.getValue())));
        }
        Player online = Bukkit.getPlayer(s.id);
        lore.add(lang().msg(online != null ? "panel.flag-online" : "panel.flag-offline"));
        if (s.handler != null) lore.add(lang().msg("panel.flag-handler", "staff", s.handler));
        if (s.punished) lore.add(lang().msg("panel.flag-punished"));
        lore.add(Component.empty());
        lore.addAll(lang().lore("panel.flag-actions"));
        OfflinePlayer op = Bukkit.getOfflinePlayer(s.id);
        return Items.head(op, lang().msg(s.watched ? "panel.flag-name-watched" : "panel.flag-name", "player", s.name), lore);
    }

    /** Takes a marked player's case: admin mode, invisible, ghost mode, and off to the player. */
    public void takeCase(Player staff, UUID id) {
        Player target = Bukkit.getPlayer(id);
        AntiCheat.Suspect s = plugin.antiCheat() == null ? null : plugin.antiCheat().suspect(id);
        if (target == null) {
            lang().send(staff, "panel.case-offline", "player", s == null ? "?" : s.name);
            return;
        }
        staff.closeInventory();
        if (!active(staff)) plugin.tools().enterStaffMode(staff);
        if (!plugin.punishments().isVanished(staff.getUniqueId())) plugin.punishments().setVanished(staff, true);
        if (staff.hasPermission("staffcore.ghost") && !plugin.ghost().on(staff.getUniqueId())) plugin.ghost().on(staff);
        plugin.admin().tpAdmin(staff, target);
        if (plugin.antiCheat() != null) plugin.antiCheat().handle(id, staff.getName());
        lang().send(staff, "panel.case-taken", "player", target.getName());
        plugin.punishments().notifyStaff(lang().prefixed("panel.case-broadcast", "staff", staff.getName(), "player", target.getName()));
    }

    // ---------------------------------------------------------------- inbox

    private record Entry(Ticket ticket, AntiCheat.Suspect suspect) {}

    public void inbox(Player p, int page) {
        List<Entry> entries = new ArrayList<>();
        for (AntiCheat.Suspect s : suspects(p)) entries.add(new Entry(null, s));
        for (Ticket t : tickets(p)) entries.add(new Entry(t, null));
        Menu m = plugin.adminMenus().paged(lang().msg("panel.inbox-title", "count", entries.size()), entries, page, en -> {
            if (en.suspect() != null) return suspectItem(en.suspect());
            Ticket t = en.ticket();
            return Items.of(t.status == Ticket.Status.OPEN ? Material.PAPER : Material.MAP, lang().msg("panel.ticket-name", "id", t.id,
                    "player", t.authorName, "reason", t.reason), lang().lore("panel.ticket-lore", "text", t.text.length() > 60 ? t.text.substring(0, 60) + "…" : t.text,
                    "state", lang().raw("panel.ticket-state." + t.status.name().toLowerCase()), "handler", t.handler == null ? "-" : t.handler));
        }, (en, e) -> {
            if (en.suspect() != null) {
                if (e.isRightClick() && !e.isShiftClick()) {
                    plugin.antiCheat().setWatched(en.suspect().id, en.suspect().name, !en.suspect().watched, p.getName());
                    inbox(p, page);
                } else if (e.isShiftClick()) {
                    plugin.antiCheat().dismiss(en.suspect().id, p.getName());
                    inbox(p, page);
                } else takeCase(p, en.suspect().id);
            } else plugin.ticketMenus().view(p, en.ticket());
        }, n -> inbox(p, n), () -> main(p));
        if (entries.isEmpty()) m.set(22, Items.of(Material.LIME_DYE, lang().msg("panel.inbox-empty"), List.of()));
        m.open(p);
    }

    // ---------------------------------------------------------------- my commands

    /** Every command this player may use right now, by permission and rank level, without duplicates. */
    public List<Command> usable(Player p) {
        Map<Command, Boolean> seen = new IdentityHashMap<>();
        TreeMap<String, Command> sorted = new TreeMap<>();
        for (Map.Entry<String, Command> e : Bukkit.getCommandMap().getKnownCommands().entrySet()) {
            Command c = e.getValue();
            if (e.getKey().contains(":") || seen.containsKey(c)) continue;
            seen.put(c, true);
            try {
                if (!c.testPermissionSilent(p)) continue;
            } catch (RuntimeException ex) {
                continue;
            }
            if (plugin.ranks() != null && !plugin.ranks().mayUse(p, "/" + c.getName())) continue;
            sorted.put(c.getName().toLowerCase(), c);
        }
        return new ArrayList<>(sorted.values());
    }

    public void commands(Player p, int page) {
        List<Command> list = usable(p);
        Menu m = plugin.adminMenus().paged(lang().msg("panel.commands-title", "count", list.size()), list, page, c -> {
            List<Component> lore = new ArrayList<>();
            if (c.getDescription() != null && !c.getDescription().isBlank()) lore.addAll(plugin.adminMenus().textLore(c.getDescription()));
            if (!c.getAliases().isEmpty()) lore.add(lang().msg("panel.command-aliases", "aliases", String.join(", ", c.getAliases())));
            String from = c instanceof org.bukkit.command.PluginIdentifiableCommand pc ? pc.getPlugin().getName() : "Minecraft";
            lore.add(lang().msg("panel.command-from", "plugin", from));
            lore.add(Component.empty());
            lore.add(lang().msg("panel.command-click"));
            boolean ours = from.equals(plugin.getName());
            return Items.of(ours ? Material.NAME_TAG : Material.PAPER, lang().msg("panel.command-name", "command", c.getName()), lore);
        }, (c, e) -> {
            p.closeInventory();
            p.sendMessage(lang().msg("panel.command-suggest", "command", c.getName()).clickEvent(ClickEvent.suggestCommand("/" + c.getName() + " ")));
        }, n -> commands(p, n), () -> main(p));
        m.open(p);
    }

    // ---------------------------------------------------------------- inactive admins are normal players

    private static final Set<String> ALWAYS = Set.of("admin", "staffmode", "teamchat", "tc", "guide");
    private static final Set<String> MAIN = Set.of("staffcore", "sc", "playban", "pb");

    /** Whether this command is a team command that needs admin mode. */
    public boolean needsAdminMode(String commandLine) {
        String line = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        String[] parts = line.split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return false;
        String label = parts[0].toLowerCase();
        if (label.startsWith("staffcore:")) label = label.substring("staffcore:".length());
        if (ALWAYS.contains(label)) return false;
        if (MAIN.contains(label)) return !(parts.length < 2 || parts[1].equalsIgnoreCase("guide") || parts[1].equalsIgnoreCase("lang"));
        Command c = Bukkit.getCommandMap().getCommand(label);
        if (!(c instanceof dev.staffcore.command.DelegateCommand) || c.getPermission() == null) return false;
        Permission perm = Bukkit.getPluginManager().getPermission(c.getPermission());
        return perm != null && perm.getDefault() != PermissionDefault.TRUE;
    }

    public boolean required() {
        return plugin.getConfig().getBoolean("admin-mode.required", true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!required() || !team(p) || active(p) || !needsAdminMode(e.getMessage())) return;
        e.setCancelled(true);
        p.sendMessage(lang().prefixed("panel.need-admin-mode").clickEvent(ClickEvent.runCommand("/admin")));
    }

    // ---------------------------------------------------------------- /admin

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            lang().send(sender, "error.players-only");
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("case")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) lang().send(p, "admin.not-online", "player", args[1]);
            else takeCase(p, target.getUniqueId());
            return true;
        }
        if (args.length >= 1) {
            switch (args[0].toLowerCase()) {
                case "on" -> {
                    if (!active(p)) plugin.tools().enterStaffMode(p);
                    return true;
                }
                case "off" -> {
                    if (active(p)) plugin.tools().leaveStaffMode(p);
                    return true;
                }
                case "inbox" -> {
                    inbox(p, 0);
                    return true;
                }
                case "commands" -> {
                    commands(p, 0);
                    return true;
                }
                case "ghost" -> {
                    if (p.hasPermission("staffcore.ghost")) plugin.ghost().toggle(p);
                    else lang().send(p, "error.no-permission");
                    return true;
                }
                default -> {
                    // the panel
                }
            }
        }
        main(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) for (String s : List.of("on", "off", "inbox", "commands", "ghost", "case")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        if (args.length == 2 && args[0].equalsIgnoreCase("case") && plugin.antiCheat() != null) {
            for (AntiCheat.Suspect s : plugin.antiCheat().suspects()) if (s.name.toLowerCase().startsWith(args[1].toLowerCase())) out.add(s.name);
        }
        return out;
    }
}
