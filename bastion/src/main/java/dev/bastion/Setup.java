package dev.bastion;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * /setup: how strict Bastion is, in one choice. Each level sets the punishment ladder, how sensitive
 * the anti-cheat is, the injector and resource pack checks, which known mods are forbidden and what
 * happens when somebody comes back with them. Single settings can still be changed afterwards.
 */
public final class Setup {
    /** Level 1: cheat clients, injectors and x-ray only. */
    static final List<String> MODS_1 = List.of("meteor", "wurst", "liquidbounce", "aristois", "impact", "future", "rusherhack", "thunderhack",
            "bleachhack", "inertia", "coffeeclient", "xray", "xtoxray", "meteorplus", "meteorist", "autoclicker");
    /** Level 2 adds the mods that see or do what a player cannot. */
    static final List<String> MODS_2 = List.of("freecam", "baritone", "seedcracker", "beelocator", "chestesp", "entityradar");
    /** Level 3 adds automation. */
    static final List<String> MODS_3 = List.of("tweakeroo", "itemscroller", "autofish", "minihud");

    private final BastionPlugin plugin;

    public Setup(BastionPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    public int level() {
        return plugin.getConfig().getInt("setup.level", 0);
    }

    public static List<String> mods(int level) {
        List<String> out = new ArrayList<>(MODS_1);
        if (level >= 2) out.addAll(MODS_2);
        if (level >= 3) out.addAll(MODS_3);
        return out;
    }

    /** Puts everything to the level: 1 mild, 2 strict, 3 hard. */
    public void apply(int level, CommandSender by) {
        if (level < 1 || level > 3) return;
        var c = plugin.getConfig();
        c.set("setup.level", level);
        // the anti-cheat is always on, movement that breaks the rules always goes back
        c.set("anticheat.enabled", true);
        c.set("anticheat.setback", true);
        c.set("anticheat.block-hits", true);
        c.set("anticheat.auto-punish", true);
        c.set("injection.enabled", true);
        c.set("injection.block-all", true);
        c.set("injection-action", "KICK");
        c.set("packs.enabled", true);
        c.set("packs.action", "KICK");
        switch (level) {
            case 1 -> {
                c.set("punish.ladder", List.of("KICK", "KICK", "KICK", "WARN", "WARN", "WARN", "BAN 1h", "BAN 1d", "BAN 7d", "BAN 30d", "BAN perm"));
                c.set("anticheat.limit", 35);
                c.set("anticheat.mark-at", 10);
                c.set("anticheat.watch-multiplier", 1.25);
                c.set("anticheat.instant", List.of("badpackets", "trident"));
                c.set("injection.strict", false);
                c.set("default-action", "KICK");
                c.set("repeat.window", "off");
            }
            case 2 -> {
                c.set("punish.ladder", List.of("KICK", "WARN", "WARN", "BAN 1d", "BAN 7d", "BAN 30d", "BAN perm"));
                c.set("anticheat.limit", 25);
                c.set("anticheat.mark-at", 8);
                c.set("anticheat.watch-multiplier", 1.5);
                c.set("anticheat.instant", List.of("fly", "jesus", "badpackets", "elytra", "trident", "noweb"));
                c.set("injection.strict", false);
                c.set("default-action", "WARN");
                c.set("warnings", 2);
                c.set("repeat.window", "1d");
                c.set("repeat.times", 3);
                c.set("repeat.action", "BAN 1d");
            }
            default -> {
                c.set("punish.ladder", List.of("KICK", "BAN 7d", "BAN 30d", "BAN perm"));
                c.set("anticheat.limit", 15);
                c.set("anticheat.mark-at", 5);
                c.set("anticheat.watch-multiplier", 2.0);
                c.set("anticheat.instant", List.of("fly", "speed", "jesus", "step", "badpackets", "elytra", "trident", "timer", "noweb",
                        "fastclimb", "reach", "aura"));
                c.set("injection.strict", true);
                // whoever comes back with a cheat client within a day: 7 days, without a word more
                c.set("default-action", "KICK");
                c.set("repeat.window", "1d");
                c.set("repeat.times", 2);
                c.set("repeat.action", "BAN 7d");
            }
        }
        plugin.saveConfig();
        // the known mods of the level; those Bastion added for a higher level before are allowed again,
        // those the team blocked by hand stay
        Set<String> wanted = new LinkedHashSet<>(mods(level));
        List<String> addedBefore = c.getStringList("setup.mods-added");
        for (String id : addedBefore) if (!wanted.contains(id)) plugin.guard().unblock(id, "setup");
        List<String> added = new ArrayList<>();
        for (String id : wanted) {
            boolean had = plugin.guard().rule(id) != null;
            if (!had) {
                plugin.guard().block(id, "setup");
                added.add(id);
            } else if (addedBefore.contains(id)) added.add(id);
        }
        c.set("setup.mods-added", added);
        plugin.saveConfig();
        // Paper's own anti-x-ray hides ores from x-ray packs and mods: from level 2 on
        boolean xray = level >= 2;
        String xrayNote = "";
        try {
            String nether = Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == World.Environment.NETHER).map(World::getName)
                    .findFirst().orElse("world_nether");
            if (dev.bastion.ac.AntiXray.enabled(Bukkit.getWorldContainer()) != xray) {
                dev.bastion.ac.AntiXray.set(Bukkit.getWorldContainer(), nether, xray);
                xrayNote = lang().raw(xray ? "setup.xray-on" : "setup.xray-off");
            }
        } catch (java.io.IOException | RuntimeException e) {
            xrayNote = lang().raw("setup.xray-failed");
        }
        plugin.guard().log(by.getName(), "setup", null, "level " + level);
        if (plugin.antiCheat() != null) plugin.antiCheat().load();
        lang().send(by, "setup.done", "level", level, "name", lang().raw("setup.level" + level + ".short"));
        for (Component line : lang().lore("setup.level" + level + ".lore")) by.sendMessage(line);
        if (!xrayNote.isEmpty()) by.sendMessage(lang().prefixed("setup.xray-note", "note", xrayNote));
        if (!plugin.lumio().mode().equals("required")) lang().send(by, "setup.lumio-hint");
    }

    // ---------------------------------------------------------------- the menu

    public void open(Player p) {
        if (!p.hasPermission("bastion.settings")) {
            lang().send(p, "no-permission");
            return;
        }
        int now = level();
        Menu m = new Menu(5, lang().msg("setup.title"));
        Material[] icons = {Material.LIME_CONCRETE, Material.ORANGE_CONCRETE, Material.RED_CONCRETE};
        int[] slots = {11, 13, 15};
        for (int i = 1; i <= 3; i++) {
            int level = i;
            List<Component> lore = new ArrayList<>(lang().lore("setup.level" + i + ".lore"));
            lore.add(Component.empty());
            lore.add(lang().msg(now == i ? "setup.current" : "setup.click"));
            var item = Menu.item(icons[i - 1], lang().msg("setup.level" + i + ".name"), lore);
            if (now == i) item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            m.set(slots[i - 1], item, e -> {
                p.closeInventory();
                apply(level, p);
            });
        }
        String mode = plugin.lumio().mode();
        boolean required = mode.equals("required");
        m.set(29, Menu.item(required ? Material.AMETHYST_CLUSTER : Material.AMETHYST_SHARD, lang().msg("setup.lumio.name", "mode",
                lang().raw("lumio.mode-name." + mode)), lang().lore("setup.lumio.lore")), e -> {
            plugin.lumio().setMode(required ? "optional" : "required");
            plugin.guard().log(p.getName(), "lumio mode", null, plugin.lumio().mode());
            open(p);
        });
        String discord = plugin.getConfig().getString("discord", "");
        m.set(31, Menu.item(Material.PAPER, lang().msg("setup.discord.name"), lang().lore("setup.discord.lore", "link",
                discord.isBlank() ? lang().raw("setup.discord.none") : discord)), e -> plugin.input().ask(p, "setup.discord.ask", text -> {
            String t = text.trim();
            if (!t.isEmpty() && (t.length() > 100 || t.contains(" "))) lang().send(p, "input.bad-link");
            else {
                plugin.getConfig().set("discord", t);
                plugin.saveConfig();
                plugin.guard().log(p.getName(), "discord", null, t);
            }
            open(p);
        }, () -> open(p)));
        boolean xray;
        try {
            xray = dev.bastion.ac.AntiXray.enabled(Bukkit.getWorldContainer());
        } catch (RuntimeException e) {
            xray = false;
        }
        m.set(33, Menu.item(xray ? Material.DIAMOND_ORE : Material.STONE, lang().msg("setup.xray.name", "value", lang().raw(xray ? "word-on" : "word-off")),
                lang().lore("setup.xray.lore")), null);
        m.set(40, Menu.item(Material.OAK_DOOR, lang().msg("menu.back.name"), List.of()), e -> plugin.menus().main(p));
        m.fill().open(p);
    }
}
