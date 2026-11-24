package dev.staffcore.module;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import dev.staffcore.gui.Items;
import dev.staffcore.gui.Menu;
import dev.staffcore.model.Punishment;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything that lies on a player right now, with a button to lift each: frozen, handcuffed, in prison,
 * PlayBan, mute, ban, watched by the anti-cheat, anti-cheat points. Opened from a ticket, from /check,
 * or with /status; /release lifts all at once.
 */
public final class PlayerStatus implements TabExecutor {
    /** One thing that lies on a player, and how to lift it. */
    public record Restriction(String key, Material icon, String detail, String permission, Runnable lift) {}

    private final StaffCorePlugin plugin;

    public PlayerStatus(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    public List<Restriction> of(UUID id, String name, String by) {
        List<Restriction> out = new ArrayList<>();
        Player online = Bukkit.getPlayer(id);
        if (online != null && plugin.admin().isFrozen(id)) {
            out.add(new Restriction("frozen", Material.BLUE_ICE, "", "staffcore.freeze", () -> plugin.admin().setFrozen(online, false, by)));
        }
        if (plugin.admin().isCuffed(id)) {
            out.add(new Restriction("cuffed", Material.LEAD, "", "staffcore.freeze", () -> plugin.admin().uncuff(id)));
        }
        if (plugin.prison() != null && plugin.prison().isJailed(id)) {
            out.add(new Restriction("prison", Material.BEDROCK, "", "staffcore.prison", () -> plugin.prison().release(id, by)));
        }
        Punishment pb = plugin.punishments().get(id);
        if (pb != null) {
            out.add(new Restriction("playban", Material.CLOCK, plugin.punishments().describe(pb.type, pb.remainingMs) + " · " + pb.reason,
                    "staffcore.staff", () -> plugin.punishments().end(pb, "PARDONED", by)));
        }
        Punishment mute = plugin.punishments().activeMute(id);
        if (mute != null) {
            out.add(new Restriction("mute", Material.MUSIC_DISC_11, mute.reason, "staffcore.staff", () -> plugin.punishments().unmute(mute, by)));
        }
        Punishment ban = plugin.store().bans.get(id);
        if (ban != null) {
            out.add(new Restriction("ban", Material.IRON_BARS, plugin.punishments().timeLeft(ban) + " · " + ban.reason, "staffcore.staff",
                    () -> plugin.punishments().unban(ban, by)));
        }
        AntiCheat ac = plugin.antiCheat();
        if (ac != null && ac.watched(id)) {
            out.add(new Restriction("watched", Material.ENDER_EYE, "", "staffcore.anticheat", () -> ac.setWatched(id, name, false, by)));
        }
        if (ac != null && ac.total(id) >= 0.5) {
            out.add(new Restriction("points", Material.IRON_SWORD, String.format(java.util.Locale.ROOT, "%.1f/%.0f", ac.total(id), ac.limit()),
                    "staffcore.anticheat", () -> ac.reset(id)));
        }
        if (plugin.jammer() != null && plugin.jammer().isJammed(id)) {
            var j = plugin.jammer().entry(id);
            out.add(new Restriction("jammed", Material.SCULK_SHRIEKER, j == null ? "" : j.by(), "staffcore.jammer",
                    () -> plugin.jammer().lift(id, by)));
        }
        if (plugin.punishments().isVanished(id) && online != null && !online.hasPermission("staffcore.vanish")) {
            out.add(new Restriction("vanished", Material.PHANTOM_MEMBRANE, "", "staffcore.vanish",
                    () -> plugin.punishments().setVanished(online, false)));
        }
        return out;
    }

    /** Lifts everything this staff member may lift. Returns how many things were lifted. */
    public int releaseAll(CommandSender staff, UUID id, String name) {
        int n = 0;
        for (Restriction r : of(id, name, staff.getName())) {
            if (!staff.hasPermission(r.permission())) continue;
            r.lift().run();
            n++;
        }
        if (n > 0) plugin.staffLog(staff.getName(), "release", name, String.valueOf(n));
        return n;
    }

    public void menu(Player staff, UUID id, String name, Runnable back) {
        List<Restriction> list = of(id, name, staff.getName());
        Menu m = new Menu(4, lang().msg("pstatus.title", "player", name));
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        List<Component> head = new ArrayList<>();
        head.add(lang().msg(Bukkit.getPlayer(id) != null ? "online" : "offline"));
        head.add(lang().msg(list.isEmpty() ? "pstatus.free" : "pstatus.count", "count", list.size()));
        m.set(4, Items.head(op, lang().msg("check.name", "player", name), head), e -> plugin.adminMenus().profile(staff, id, name));
        int slot = 10;
        for (Restriction r : list) {
            if (slot % 9 == 8) slot += 2;
            boolean may = staff.hasPermission(r.permission());
            List<Component> lore = new ArrayList<>();
            if (!r.detail().isEmpty()) lore.addAll(plugin.adminMenus().textLore(r.detail()));
            lore.add(Component.empty());
            lore.add(lang().msg(may ? "pstatus.click-lift" : "pstatus.not-allowed"));
            m.set(slot++, Items.of(r.icon(), lang().msg("pstatus.kind." + r.key()), lore), e -> {
                if (!may) return;
                r.lift().run();
                plugin.staffLog(staff.getName(), "lift " + r.key(), name, null);
                lang().send(staff, "pstatus.lifted", "what", lang().raw("pstatus.plain." + r.key()), "player", name);
                menu(staff, id, name, back);
            });
        }
        if (list.isEmpty()) m.set(22, Items.of(Material.LIME_DYE, lang().msg("pstatus.nothing"), List.of()));
        else m.set(31, Items.of(Material.MILK_BUCKET, lang().msg("pstatus.release-all"), lang().lore("pstatus.release-all-lore")), e -> {
            int n = releaseAll(staff, id, name);
            lang().send(staff, "pstatus.released", "player", name, "count", n);
            menu(staff, id, name, back);
        });
        m.set(27, Items.of(Material.OAK_DOOR, lang().msg("menu.back"), List.of()), e -> {
            if (back != null) back.run();
            else staff.closeInventory();
        });
        if (Bukkit.getPlayer(id) != null && staff.hasPermission("staffcore.check")) {
            m.set(35, Items.of(Material.SPYGLASS, lang().msg("pstatus.open-check"), List.of()), e -> plugin.playerCheck().open(staff, Bukkit.getPlayer(id)));
        }
        m.fill().open(staff);
    }

    private UUID byName(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p.getUniqueId();
        for (Map.Entry<UUID, String> e : plugin.store().names.entrySet()) if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            lang().send(sender, "pstatus.usage");
            return true;
        }
        UUID id = byName(args[0]);
        if (id == null) {
            lang().send(sender, "money.unknown", "player", args[0]);
            return true;
        }
        String name = plugin.store().names.getOrDefault(id, args[0]);
        if (command.getName().equalsIgnoreCase("release")) {
            lang().send(sender, "pstatus.released", "player", name, "count", releaseAll(sender, id, name));
            return true;
        }
        if (sender instanceof Player p) menu(p, id, name, null);
        else {
            List<Restriction> list = of(id, name, sender.getName());
            if (list.isEmpty()) lang().send(sender, "pstatus.nothing");
            for (Restriction r : list) sender.sendMessage(lang().msg("pstatus.line", "what", lang().raw("pstatus.plain." + r.key()), "detail", r.detail()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[0].toLowerCase())) out.add(p.getName());
            if (plugin.prison() != null) for (String n : plugin.prison().jailedNames()) if (!out.contains(n) && n.toLowerCase().startsWith(args[0].toLowerCase())) out.add(n);
        }
        return out;
    }
}
