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
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything of /jammer in one menu: all players (the jammed first), and for each player the three
 * levels, how long, talking with them, and lifting it. No command needs to be remembered.
 */
public final class JammerMenu {
    private static final long[] TIMES = {0, 10 * 60_000L, 60 * 60_000L, 24 * 60 * 60_000L, 7 * 24 * 60 * 60_000L};
    private static final Material[] LEVEL_ICON = {Material.AIR, Material.PAPER, Material.BLUE_ICE, Material.SKELETON_SKULL};
    private static final int[] INNER = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private final StaffCorePlugin plugin;
    private final Jammer jammer;
    /** the time each team member chose for the next jam (index into TIMES) */
    private final Map<UUID, Integer> chosenTime = new ConcurrentHashMap<>();

    JammerMenu(StaffCorePlugin plugin, Jammer jammer) {
        this.plugin = plugin;
        this.jammer = jammer;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private static ItemStack glow(ItemStack item, boolean on) {
        if (on) item.editMeta(m -> m.setEnchantmentGlintOverride(true));
        return item;
    }

    private String timeText(long ms) {
        return ms <= 0 ? lang().raw("jammer.forever") : TimeUtil.format(ms);
    }

    private long chosen(Player staff) {
        return TIMES[chosenTime.getOrDefault(staff.getUniqueId(), 0)];
    }

    /** The lines under a player's head: free, or level, by whom, how long, and whether you talk with them. */
    private List<Component> status(Player staff, UUID id, String name) {
        List<Component> lore = new ArrayList<>();
        Jammer.Entry e = jammer.entry(id);
        if (e == null) lore.add(lang().msg("jammer.menu.free"));
        else {
            lore.add(lang().msg("jammer.menu.jammed", "level", e.level(), "what", lang().raw("jammer.level." + e.level() + ".short")));
            lore.add(lang().msg("jammer.menu.since", "by", e.by(),
                    "time", e.until() == 0 ? lang().raw("jammer.forever") : TimeUtil.format(e.until() - System.currentTimeMillis())));
            if (jammer.inContact(staff.getUniqueId(), id)) lore.add(lang().msg("jammer.menu.talking", "player", name));
        }
        if (Bukkit.getPlayer(id) == null) lore.add(lang().msg("jammer.menu.offline"));
        return lore;
    }

    // ---------------------------------------------------------------- all players

    public void main(Player staff, int page) {
        if (!staff.hasPermission("staffcore.jammer")) {
            lang().send(staff, "error.no-permission");
            return;
        }
        // the jammed first (also offline ones), then everybody else online
        Map<UUID, String> people = new LinkedHashMap<>();
        for (Jammer.Entry e : jammer.all()) people.put(e.id(), e.name());
        for (Player p : Bukkit.getOnlinePlayers()) if (!p.equals(staff)) people.putIfAbsent(p.getUniqueId(), p.getName());
        List<Map.Entry<UUID, String>> list = new ArrayList<>(people.entrySet());
        int pages = Math.max(1, (list.size() + INNER.length - 1) / INNER.length);
        int pg = Math.max(0, Math.min(page, pages - 1));

        Menu m = new Menu(6, lang().msg("jammer.menu.title"));
        m.set(4, Items.of(Material.SCULK_SHRIEKER, lang().msg("jammer.menu.info.name", "count", jammer.all().size()), lang().lore("jammer.menu.info.lore")));
        for (int i = 0; i < INNER.length; i++) {
            int at = pg * INNER.length + i;
            if (at >= list.size()) break;
            UUID id = list.get(at).getKey();
            String name = list.get(at).getValue();
            List<Component> lore = status(staff, id, name);
            lore.add(Component.empty());
            lore.add(lang().msg("jammer.menu.click"));
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            ItemStack head = Items.head(op, lang().msg(jammer.isJammed(id) ? "jammer.menu.name-jammed" : "jammer.menu.name", "player", name), lore);
            m.set(INNER[i], glow(head, jammer.inContact(staff.getUniqueId(), id)), e -> player(staff, id, name));
        }
        if (list.isEmpty()) m.set(22, Items.of(Material.LIME_DYE, lang().msg("jammer.menu.nobody"), List.of()));
        if (pg > 0) m.set(45, Items.of(Material.ARROW, lang().msg("jammer.menu.prev"), List.of()), e -> main(staff, pg - 1));
        if (pg < pages - 1) m.set(53, Items.of(Material.ARROW, lang().msg("jammer.menu.next"), List.of()), e -> main(staff, pg + 1));
        m.set(49, Items.of(Material.BARRIER, lang().msg("jammer.menu.close"), List.of()), e -> staff.closeInventory());
        m.fill().open(staff);
    }

    // ---------------------------------------------------------------- one player

    public void player(Player staff, UUID id, String name) {
        if (!staff.hasPermission("staffcore.jammer")) {
            lang().send(staff, "error.no-permission");
            return;
        }
        Jammer.Entry e = jammer.entry(id);
        int level = e == null ? 0 : e.level();
        long time = chosen(staff);
        Menu m = new Menu(5, lang().msg("jammer.menu.player-title", "player", name));
        m.set(4, Items.head(Bukkit.getOfflinePlayer(id), lang().msg(level > 0 ? "jammer.menu.name-jammed" : "jammer.menu.name", "player", name),
                status(staff, id, name)));
        int[] slots = {0, 20, 22, 24};
        for (int lv = 1; lv <= 3; lv++) {
            int l = lv;
            List<Component> lore = new ArrayList<>(lang().lore("jammer.level." + lv + ".lore"));
            lore.add(Component.empty());
            lore.add(lang().msg(level == lv ? "jammer.menu.active" : "jammer.menu.choose", "time", timeText(time)));
            m.set(slots[lv], glow(Items.of(LEVEL_ICON[lv], lang().msg("jammer.level." + lv + ".name"), lore), level == lv), ev -> {
                if (jammer.apply(staff, id, name, l, chosen(staff))) player(staff, id, name);
                else staff.closeInventory();
            });
        }
        m.set(31, Items.of(Material.CLOCK, lang().msg("jammer.menu.time", "time", timeText(time)), lang().lore("jammer.menu.time-lore")), ev -> {
            chosenTime.merge(staff.getUniqueId(), 1, (a, b) -> (a + b) % TIMES.length);
            player(staff, id, name);
        });
        if (level > 0) {
            Player online = Bukkit.getPlayer(id);
            boolean talking = jammer.inContact(staff.getUniqueId(), id);
            if (online != null) {
                m.set(29, glow(Items.of(talking ? Material.ECHO_SHARD : Material.GRAY_DYE,
                        lang().msg(talking ? "jammer.menu.talk-on" : "jammer.menu.talk-off", "player", name),
                        lang().lore("jammer.menu.talk-lore", "player", name)), talking), ev -> {
                    jammer.talk(staff, online, name);
                    player(staff, id, name);
                });
            }
            m.set(33, Items.of(Material.MILK_BUCKET, lang().msg("jammer.menu.lift", "player", name), lang().lore("jammer.menu.lift-lore")), ev -> {
                if (jammer.lift(id, staff.getName())) lang().send(staff, "jammer.off", "player", name);
                player(staff, id, name);
            });
        }
        m.set(40, Items.of(Material.OAK_DOOR, lang().msg("jammer.menu.back"), List.of()), ev -> main(staff, 0));
        m.fill().open(staff);
    }
}
