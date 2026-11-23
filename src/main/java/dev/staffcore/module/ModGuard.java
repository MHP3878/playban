package dev.staffcore.module;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which client and which mods a player brings. Three ways to tell: the client name the game sends,
 * the mod channels it registers, and (on servers that allow it) a hidden sign with translation keys of
 * mods, which a client with that mod fills in with its own text. Each rule decides what happens:
 * ALERT tells the team, KICK sends the player away, WARN kicks the first time with a request to remove
 * it and punishes the next time, PUNISH punishes at once. Or allow-list mode: only listed clients.
 */
public final class ModGuard implements Listener, TabExecutor {
    /** What is known about one player's client. */
    public static final class Info {
        public String brand;
        public final Set<String> channels = new LinkedHashSet<>();
        public final Set<String> mods = new LinkedHashSet<>();
        public boolean probed;
    }

    private final StaffCorePlugin plugin;
    private final Map<UUID, Info> infos = new HashMap<>();
    private final Map<UUID, Integer> strikes = new HashMap<>();
    private final Map<UUID, Map<String, String>> probes = new HashMap<>();

    public ModGuard(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    public boolean enabled() {
        return plugin.modules() != null && plugin.modules().on("modguard");
    }

    public Info info(UUID id) {
        return infos.computeIfAbsent(id, k -> new Info());
    }

    private File file() {
        return new File(plugin.getDataFolder(), "modguard.yml");
    }

    public void load() {
        strikes.clear();
        if (!file().exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file());
        for (String k : cfg.getKeys(false)) {
            try {
                strikes.put(UUID.fromString(k), cfg.getInt(k));
            } catch (IllegalArgumentException ignored) {
                // broken entry
            }
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> e : strikes.entrySet()) cfg.set(e.getKey().toString(), e.getValue());
        try {
            cfg.save(file());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save modguard.yml: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- the rules

    /** A rule from mod-guard.rules: a name, words to look for, and what happens. */
    public record Rule(String name, List<String> brand, List<String> channel, List<String> keys, String action) {}

    public List<Rule> rules() {
        List<Rule> out = new ArrayList<>();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("mod-guard.rules");
        if (s == null) return out;
        for (String name : s.getKeys(false)) {
            ConfigurationSection r = s.getConfigurationSection(name);
            if (r == null) continue;
            out.add(new Rule(name, lower(r.getStringList("brand")), lower(r.getStringList("channel")), r.getStringList("keys"),
                    r.getString("action", "WARN").toUpperCase(Locale.ROOT)));
        }
        return out;
    }

    private static List<String> lower(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    /** Which rules this client breaks. */
    public List<Rule> matches(Info info) {
        List<Rule> out = new ArrayList<>();
        String brand = info.brand == null ? "" : info.brand.toLowerCase(Locale.ROOT);
        for (Rule r : rules()) {
            boolean hit = info.mods.contains(r.name());
            for (String b : r.brand()) if (!b.isEmpty() && brand.contains(b)) hit = true;
            for (String c : r.channel()) for (String ch : info.channels) if (!c.isEmpty() && ch.toLowerCase(Locale.ROOT).contains(c)) hit = true;
            if (hit) out.add(r);
        }
        return out;
    }

    /** Allow-list mode: whether the client name is on the list (empty name counts as vanilla). */
    private boolean allowed(Info info) {
        if (!plugin.getConfig().getString("mod-guard.mode", "BLOCKLIST").equalsIgnoreCase("ALLOWLIST")) return true;
        String brand = info.brand == null || info.brand.isBlank() ? "vanilla" : info.brand.toLowerCase(Locale.ROOT);
        for (String a : plugin.getConfig().getStringList("mod-guard.allowed-clients")) if (brand.contains(a.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    // ---------------------------------------------------------------- collecting

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChannel(PlayerRegisterChannelEvent e) {
        info(e.getPlayer().getUniqueId()).channels.add(e.getChannel());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // the client name and the channels arrive a moment after joining
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            Info info = info(p.getUniqueId());
            try {
                info.brand = p.getClientBrandName();
            } catch (RuntimeException | NoSuchMethodError ignored) {
                // older API
            }
            info.channels.addAll(p.getListeningPluginChannels());
            judge(p);
            if (!p.isOnline()) return;
            if (plugin.getConfig().getBoolean("mod-guard.probe", true)) probe(p);
        }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        infos.remove(e.getPlayer().getUniqueId());
        probes.remove(e.getPlayer().getUniqueId());
        waiting.remove(e.getPlayer().getUniqueId());
        cleanup(e.getPlayer());
    }

    /** Decides by the rules what happens to this player. */
    public void judge(Player p) {
        if (!enabled() || p.hasPermission("staffcore.modguard.bypass")) return;
        Info info = info(p.getUniqueId());
        if (!allowed(info)) {
            act(p, new Rule(info.brand == null ? "?" : info.brand, List.of(), List.of(), List.of(),
                    plugin.getConfig().getString("mod-guard.not-allowed-action", "KICK").toUpperCase(Locale.ROOT)));
            return;
        }
        List<Rule> hits = matches(info);
        if (hits.isEmpty()) return;
        // the hardest action wins
        Rule worst = hits.get(0);
        for (Rule r : hits) if (weight(r.action()) > weight(worst.action())) worst = r;
        act(p, worst);
    }

    private static int weight(String action) {
        return switch (action) {
            case "PUNISH", "BAN" -> 4;
            case "WARN" -> 3;
            case "KICK" -> 2;
            case "ALERT" -> 1;
            default -> 0;
        };
    }

    private void act(Player p, Rule rule) {
        String mod = display(rule);
        plugin.punishments().notifyStaff(lang().prefixed("modguard.found", "player", p.getName(), "mod", mod, "action", rule.action())
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/check " + p.getName())));
        plugin.staffLog("ModGuard", "mod " + rule.action().toLowerCase(Locale.ROOT), p.getName(), mod);
        switch (rule.action()) {
            case "ALERT", "ALLOW" -> {
                // the team knows, nothing else
            }
            case "KICK" -> p.kick(lang().msg("modguard.kick", "mod", mod));
            case "PUNISH" -> punish(p, mod);
            case "BAN" -> plugin.antiCheat().catchWith(p, plugin.getConfig().getString("mod-guard.ban", "BAN 7d"),
                    lang().raw("modguard.reason").replace("{mod}", mod));
            default -> {
                int n = strikes.merge(p.getUniqueId(), 1, Integer::sum);
                save();
                if (n <= plugin.getConfig().getInt("mod-guard.warnings", 1)) p.kick(lang().msg("modguard.warn-kick", "mod", mod));
                else punish(p, mod);
            }
        }
    }

    private void punish(Player p, String mod) {
        String step = plugin.getConfig().getString("mod-guard.punishment", "CATEGORY cheating");
        plugin.antiCheat().catchWith(p, step, lang().raw("modguard.reason").replace("{mod}", mod));
    }

    // ---------------------------------------------------------------- the hidden sign

    /** What is being asked right now: line -> "key|mod", plus the keys still waiting. */
    private final Map<UUID, List<String[]>> waiting = new HashMap<>();
    private final Map<UUID, Runnable> restore = new HashMap<>();

    /**
     * Shows a sign editor for a blink with translation keys of the blocked mods. A client without the
     * mod sends the keys back as they are; with the mod, its own text comes back. Four keys per sign,
     * one sign after the other. On Paper 1.21.9+ the sign only exists for the player (virtual sign);
     * on older servers a real sign stands for two ticks deep under the player and the block is put back.
     */
    public void probe(Player p) {
        if (!enabled()) return;
        List<String[]> all = new ArrayList<>();
        for (Rule r : rules()) for (String k : r.keys()) all.add(new String[]{k, r.name()});
        if (all.isEmpty()) return;
        waiting.put(p.getUniqueId(), all);
        info(p.getUniqueId()).probed = true;
        nextSign(p);
    }

    private void nextSign(Player p) {
        List<String[]> left = waiting.get(p.getUniqueId());
        if (left == null || left.isEmpty() || !p.isOnline()) {
            waiting.remove(p.getUniqueId());
            return;
        }
        List<String[]> batch = new ArrayList<>(left.subList(0, Math.min(4, left.size())));
        left.subList(0, batch.size()).clear();
        Map<String, String> asked = new LinkedHashMap<>();
        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            if (i < batch.size()) {
                asked.put(String.valueOf(i), batch.get(i)[0] + "|" + batch.get(i)[1]);
                lines.add(Component.translatable(batch.get(i)[0]));
            } else lines.add(Component.empty());
        }
        probes.put(p.getUniqueId(), asked);
        if (!virtualSign(p, lines) && !realSign(p, lines)) {
            probes.remove(p.getUniqueId());
            waiting.remove(p.getUniqueId());
            return;
        }
        // closing it right away makes the client send back what the sign says
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) p.closeInventory();
        }, 2L);
        // no answer within a second: go on with the next sign anyway
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (probes.get(p.getUniqueId()) == asked) {
                probes.remove(p.getUniqueId());
                cleanup(p);
                nextSign(p);
            }
        }, 20L);
    }

    /** Waits for an answer to these keys without showing a sign (for tests and other ways of asking). */
    public void expectAnswer(Player p, List<String[]> keysAndMods) {
        Map<String, String> asked = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(4, keysAndMods.size()); i++) asked.put(String.valueOf(i), keysAndMods.get(i)[0] + "|" + keysAndMods.get(i)[1]);
        probes.put(p.getUniqueId(), asked);
    }

    private boolean virtualSign(Player p, List<Component> lines) {
        try {
            Class<?> position = Class.forName("io.papermc.paper.math.Position");
            Location at = p.getLocation().clone();
            at.setY(Math.max(p.getWorld().getMinHeight(), at.getBlockY() - 6));
            Object pos = position.getMethod("block", int.class, int.class, int.class).invoke(null, at.getBlockX(), at.getBlockY(), at.getBlockZ());
            p.sendBlockChange(at, Material.OAK_SIGN.createBlockData());
            p.sendSignChange(at, lines);
            p.getClass().getMethod("openVirtualSign", position, org.bukkit.block.sign.Side.class).invoke(p, pos, org.bukkit.block.sign.Side.FRONT);
            Location back = at.clone();
            restore.put(p.getUniqueId(), () -> p.sendBlockChange(back, back.getBlock().getBlockData()));
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            return false;
        }
    }

    /** Older servers: a real sign for two ticks in solid ground two blocks under the player, then the block comes back. */
    private boolean realSign(Player p, List<Component> lines) {
        try {
            org.bukkit.block.Block spot = p.getLocation().getBlock().getRelative(0, -2, 0);
            Material was = spot.getType();
            if (!was.isSolid() || spot.getState() instanceof org.bukkit.block.TileState || spot.getY() <= p.getWorld().getMinHeight()) return false;
            org.bukkit.block.data.BlockData before = spot.getBlockData();
            spot.setType(Material.OAK_SIGN, false);
            if (!(spot.getState() instanceof org.bukkit.block.Sign sign)) {
                spot.setBlockData(before, false);
                return false;
            }
            var side = sign.getSide(org.bukkit.block.sign.Side.FRONT);
            for (int i = 0; i < 4; i++) side.line(i, lines.get(i));
            sign.update(true, false);
            realSigns.add(spot.getLocation());
            restore.put(p.getUniqueId(), () -> {
                realSigns.remove(spot.getLocation());
                spot.setBlockData(before, false);
            });
            // safety: the block is back in any case
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (spot.getType() == Material.OAK_SIGN) {
                    realSigns.remove(spot.getLocation());
                    spot.setBlockData(before, false);
                }
            }, 40L);
            try {
                p.openSign(sign, org.bukkit.block.sign.Side.FRONT);
            } catch (RuntimeException failed) {
                cleanup(p);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private final java.util.Set<Location> realSigns = new java.util.HashSet<>();

    private void cleanup(Player p) {
        Runnable r = restore.remove(p.getUniqueId());
        if (r != null) r.run();
    }

    /** The answer from a real sign (older servers): read it, change nothing in the world. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSign(org.bukkit.event.block.SignChangeEvent e) {
        if (!realSigns.contains(e.getBlock().getLocation())) return;
        e.setCancelled(true);
        answer(e.getPlayer(), e.lines());
    }

    /** Hooked in by reflection on servers that have UncheckedSignChangeEvent (virtual signs). */
    public void registerProbeListener() {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> type = (Class<? extends Event>) Class.forName("io.papermc.paper.event.packet.UncheckedSignChangeEvent");
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> {
                if (!type.isInstance(event)) return;
                try {
                    Player p = (Player) event.getClass().getMethod("getPlayer").invoke(event);
                    @SuppressWarnings("unchecked")
                    List<Component> lines = (List<Component>) event.getClass().getMethod("lines").invoke(event);
                    answer(p, lines);
                } catch (ReflectiveOperationException ignored) {
                    // a different event layout
                }
            }, plugin);
        } catch (ClassNotFoundException ignored) {
            // older server: real signs and SignChangeEvent instead
        }
    }

    /** What the client wrote back: a line that is not the key any more means the mod is there. */
    public void answer(Player p, List<Component> lines) {
        Map<String, String> asked = probes.remove(p.getUniqueId());
        if (asked == null) return;
        cleanup(p);
        Info info = info(p.getUniqueId());
        boolean found = false;
        for (Map.Entry<String, String> e : asked.entrySet()) {
            int i = Integer.parseInt(e.getKey());
            if (i >= lines.size()) continue;
            String[] keyMod = e.getValue().split("\\|", 2);
            String text = PlainTextComponentSerializer.plainText().serialize(lines.get(i)).trim();
            if (!text.isEmpty() && !text.equals(keyMod[0])) {
                info.mods.add(keyMod[1]);
                found = true;
            }
        }
        if (found) judge(p);
        else Bukkit.getScheduler().runTaskLater(plugin, () -> nextSign(p), 2L);
    }

    // ---------------------------------------------------------------- blocking mods by name

    /** Puts a rule for a mod into the config. Returns the rule's name. */
    public String block(String typed, String action, String by) {
        KnownMods.Mod known = KnownMods.find(typed);
        String id = known != null ? known.id() : KnownMods.normalize(typed);
        if (id.isEmpty()) return null;
        String path = "mod-guard.rules." + id + ".";
        List<String> keys = new ArrayList<>(known != null ? known.keys() : List.of());
        for (String k : KnownMods.guessKeys(id)) if (!keys.contains(k)) keys.add(k);
        List<String> channels = new ArrayList<>(known != null ? known.channels() : List.of(id));
        plugin.getConfig().set(path + "display", known != null ? known.name() : typed.trim());
        plugin.getConfig().set(path + "brand", known != null && known.client() ? List.of(id) : List.of());
        plugin.getConfig().set(path + "channel", channels);
        plugin.getConfig().set(path + "keys", keys);
        plugin.getConfig().set(path + "action", action.toUpperCase(Locale.ROOT));
        plugin.saveConfig();
        plugin.staffLog(by, "mod block", null, id + " " + action);
        // whoever is online right now is checked again
        for (Player p : Bukkit.getOnlinePlayers()) {
            judge(p);
            if (p.isOnline() && plugin.getConfig().getBoolean("mod-guard.probe", true)) probe(p);
        }
        return id;
    }

    public boolean unblock(String typed, String by) {
        String id = KnownMods.find(typed) != null ? KnownMods.find(typed).id() : KnownMods.normalize(typed);
        if (plugin.getConfig().getConfigurationSection("mod-guard.rules." + id) == null) {
            // perhaps typed exactly as the rule is called
            id = typed.trim().toLowerCase(Locale.ROOT);
            if (plugin.getConfig().getConfigurationSection("mod-guard.rules." + id) == null) return false;
        }
        plugin.getConfig().set("mod-guard.rules." + id, null);
        plugin.saveConfig();
        plugin.staffLog(by, "mod unblock", null, id);
        return true;
    }

    public String display(Rule r) {
        String d = plugin.getConfig().getString("mod-guard.rules." + r.name() + ".display");
        if (d != null) return d;
        KnownMods.Mod m = KnownMods.find(r.name());
        return m != null ? m.name() : r.name();
    }

    // ---------------------------------------------------------------- /mods

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            lang().send(sender, "modguard.usage");
            return true;
        }
        if (args[0].equalsIgnoreCase("reset") && args.length > 1) {
            Player t = Bukkit.getPlayerExact(args[1]);
            UUID id = t != null ? t.getUniqueId() : null;
            if (id == null) {
                for (Map.Entry<UUID, String> e : plugin.store().names.entrySet()) if (e.getValue().equalsIgnoreCase(args[1])) id = e.getKey();
            }
            if (id != null) {
                strikes.remove(id);
                save();
            }
            lang().send(sender, "modguard.reset", "player", args[1]);
            return true;
        }
        Player t = Bukkit.getPlayerExact(args[0]);
        if (t == null) {
            lang().send(sender, "admin.not-online", "player", args[0]);
            return true;
        }
        Info info = info(t.getUniqueId());
        lang().send(sender, "modguard.header", "player", t.getName(), "brand", info.brand == null ? "?" : info.brand,
                "strikes", strikes.getOrDefault(t.getUniqueId(), 0));
        sender.sendMessage(lang().msg("modguard.channels", "channels", info.channels.isEmpty() ? "-" : String.join(", ", info.channels)));
        List<String> found = new ArrayList<>();
        for (Rule r : matches(info)) found.add(r.name() + " (" + r.action() + ")");
        sender.sendMessage(lang().msg("modguard.mods", "mods", found.isEmpty() ? "-" : String.join(", ", found)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if ("reset".startsWith(args[0].toLowerCase())) out.add("reset");
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[0].toLowerCase())) out.add(p.getName());
        }
        return out;
    }

    public int strikes(UUID id) {
        return strikes.getOrDefault(id, 0);
    }
}
