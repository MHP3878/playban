package dev.staffcore;

import dev.staffcore.gui.Items;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /prison: a bedrock cage built around the player, and an inventory full of grey locks they cannot use.
 * Everything that was there before, blocks and items, comes back on release, also after a restart.
 */
public final class PrisonManager implements Listener, org.bukkit.command.TabExecutor {
    private static final class Cell {
        UUID player;
        String name;
        String by;
        String reason;
        long expiresAt;
        String world;
        int x, y, z;
        /** "x,y,z" -> block data before the cage was built */
        final Map<String, String> blocks = new LinkedHashMap<>();
        String inventory;
        String armor;
        String offhand;
    }

    private final StaffCorePlugin plugin;
    private final Map<UUID, Cell> cells = new LinkedHashMap<>();
    private final NamespacedKey lockKey;
    private BukkitTask ticker;

    public PrisonManager(StaffCorePlugin plugin) {
        this.plugin = plugin;
        this.lockKey = new NamespacedKey(plugin, "locked");
    }

    private Lang lang() {
        return plugin.lang();
    }

    private File file() {
        return new File(plugin.getDataFolder(), "prisons.yml");
    }

    public boolean isJailed(UUID id) {
        return cells.containsKey(id);
    }

    // ---------------------------------------------------------------- storage

    public void load() {
        if (!file().exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file());
        ConfigurationSection root = cfg.getConfigurationSection("cells");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(k);
            if (s == null) continue;
            try {
                Cell c = new Cell();
                c.player = UUID.fromString(k);
                c.name = s.getString("name", "?");
                c.by = s.getString("by", "?");
                c.reason = s.getString("reason", "");
                c.expiresAt = s.getLong("expires");
                c.world = s.getString("world");
                c.x = s.getInt("x");
                c.y = s.getInt("y");
                c.z = s.getInt("z");
                c.inventory = s.getString("inventory");
                c.armor = s.getString("armor");
                c.offhand = s.getString("offhand");
                ConfigurationSection bs = s.getConfigurationSection("blocks");
                if (bs != null) for (String pos : bs.getKeys(false)) c.blocks.put(pos.replace('_', ','), bs.getString(pos));
                cells.put(c.player, c);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("prisons.yml: skipped a broken entry " + k);
            }
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Cell c : cells.values()) {
            String p = "cells." + c.player + ".";
            cfg.set(p + "name", c.name);
            cfg.set(p + "by", c.by);
            cfg.set(p + "reason", c.reason);
            cfg.set(p + "expires", c.expiresAt);
            cfg.set(p + "world", c.world);
            cfg.set(p + "x", c.x);
            cfg.set(p + "y", c.y);
            cfg.set(p + "z", c.z);
            cfg.set(p + "inventory", c.inventory);
            cfg.set(p + "armor", c.armor);
            cfg.set(p + "offhand", c.offhand);
            for (Map.Entry<String, String> b : c.blocks.entrySet()) cfg.set(p + "blocks." + b.getKey().replace(',', '_'), b.getValue());
        }
        try {
            cfg.save(file());
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save prisons.yml: " + e.getMessage());
        }
    }

    public void start() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            for (Cell c : new ArrayList<>(cells.values())) {
                if (c.expiresAt > 0 && c.expiresAt <= now) release(c.player, null);
                else {
                    Player p = Bukkit.getPlayer(c.player);
                    if (p != null) p.sendActionBar(lang().msg("prison.bar", "time", c.expiresAt <= 0 ? lang().raw("permanent")
                            : TimeUtil.format(Math.max(1000, c.expiresAt - now))));
                }
            }
        }, 20L, 20L);
    }

    public void stop() {
        if (ticker != null) ticker.cancel();
    }

    // ---------------------------------------------------------------- jail and release

    private ItemStack lock() {
        ItemStack pane = Items.of(Material.GRAY_STAINED_GLASS_PANE, lang().msg("prison.locked-slot"), lang().lore("prison.locked-lore"));
        ItemMeta meta = pane.getItemMeta();
        meta.getPersistentDataContainer().set(lockKey, PersistentDataType.BYTE, (byte) 1);
        pane.setItemMeta(meta);
        return pane;
    }

    private static String encode(ItemStack[] items) {
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
    }

    private static ItemStack[] decode(String data) {
        return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(data));
    }

    public void jail(Player target, long ms, String reason, String by) {
        if (isJailed(target.getUniqueId())) return;
        Location at = target.getLocation();
        // stand on solid ground: the cell starts at the block the player stands in
        Block feet = at.getBlock();
        Cell c = new Cell();
        c.player = target.getUniqueId();
        c.name = target.getName();
        c.by = by;
        c.reason = reason;
        c.expiresAt = ms <= 0 ? 0 : System.currentTimeMillis() + ms;
        c.world = at.getWorld().getName();
        c.x = feet.getX();
        c.y = feet.getY();
        c.z = feet.getZ();
        World w = at.getWorld();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    Block b = w.getBlockAt(c.x + dx, c.y + dy, c.z + dz);
                    c.blocks.put(b.getX() + "," + b.getY() + "," + b.getZ(), b.getBlockData().getAsString());
                    boolean inside = dx == 0 && dz == 0 && (dy == 0 || dy == 1);
                    b.setType(inside ? Material.AIR : Material.BEDROCK, false);
                }
            }
        }
        ItemStack[] storage = new ItemStack[36];
        for (int i = 0; i < 36; i++) storage[i] = target.getInventory().getItem(i);
        c.inventory = encode(storage);
        c.armor = encode(target.getInventory().getArmorContents());
        c.offhand = encode(new ItemStack[]{target.getInventory().getItemInOffHand()});
        cells.put(c.player, c);
        save();
        fillLocks(target);
        target.teleport(new Location(w, c.x + 0.5, c.y, c.z + 0.5, at.getYaw(), at.getPitch()));
        target.playSound(target.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 0.6f);
        String time = ms <= 0 ? lang().raw("permanent") : TimeUtil.format(ms);
        target.showTitle(Title.title(lang().msg("prison.title"), lang().msg("prison.subtitle", "time", time),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
        lang().send(target, "prison.you", "staff", by, "time", time, "reason", reason);
        plugin.punishments().notifyStaff(lang().prefixed("prison.broadcast", "player", c.name, "staff", by, "time", time));
    }

    private void fillLocks(Player p) {
        for (int i = 0; i < 36; i++) p.getInventory().setItem(i, lock());
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(null);
    }

    /** Takes the cage down, puts the blocks back and gives the items back. by == null when the time ran out. */
    public void release(UUID id, String by) {
        Cell c = cells.remove(id);
        if (c == null) return;
        World w = Bukkit.getWorld(c.world);
        if (w != null) {
            for (Map.Entry<String, String> b : c.blocks.entrySet()) {
                String[] xyz = b.getKey().split(",");
                Block block = w.getBlockAt(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
                try {
                    block.setBlockData(Bukkit.createBlockData(b.getValue()), false);
                } catch (IllegalArgumentException | UnsupportedOperationException e) {
                    // at least the right block, if its details cannot be read
                    String type = b.getValue().contains("[") ? b.getValue().substring(0, b.getValue().indexOf('[')) : b.getValue();
                    Material m = Material.matchMaterial(type);
                    block.setType(m == null ? Material.AIR : m, false);
                }
            }
        }
        Player p = Bukkit.getPlayer(id);
        if (p != null) giveBack(p, c);
        else pending.put(id, c);
        save();
        savePending();
        if (p != null) {
            p.playSound(p.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 1f, 1.2f);
            lang().send(p, "prison.free");
        }
        if (by != null) plugin.punishments().notifyStaff(lang().prefixed("prison.broadcast-free", "player", c.name, "staff", by));
    }

    private void giveBack(Player p, Cell c) {
        try {
            ItemStack[] storage = decode(c.inventory);
            for (int i = 0; i < 36; i++) {
                ItemStack it = i < storage.length ? storage[i] : null;
                p.getInventory().setItem(i, it == null || it.getType().isAir() ? null : it);
            }
            p.getInventory().setArmorContents(decode(c.armor));
            ItemStack[] off = decode(c.offhand);
            p.getInventory().setItemInOffHand(off.length > 0 ? off[0] : null);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not give " + p.getName() + " their items back after prison: " + e.getMessage());
        }
    }

    // released while offline: the items wait in prisons-return.yml until they join
    private final Map<UUID, Cell> pending = new LinkedHashMap<>();

    private File pendingFile() {
        return new File(plugin.getDataFolder(), "prisons-return.yml");
    }

    private void savePending() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Cell c : pending.values()) {
            cfg.set(c.player + ".inventory", c.inventory);
            cfg.set(c.player + ".armor", c.armor);
            cfg.set(c.player + ".offhand", c.offhand);
        }
        try {
            cfg.save(pendingFile());
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save prisons-return.yml: " + e.getMessage());
        }
    }

    public void loadPending() {
        if (!pendingFile().exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(pendingFile());
        for (String k : cfg.getKeys(false)) {
            try {
                Cell c = new Cell();
                c.player = UUID.fromString(k);
                c.inventory = cfg.getString(k + ".inventory");
                c.armor = cfg.getString(k + ".armor");
                c.offhand = cfg.getString(k + ".offhand");
                pending.put(c.player, c);
            } catch (IllegalArgumentException ignored) {
                // broken entry
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Cell back = pending.remove(p.getUniqueId());
        if (back != null) {
            giveBack(p, back);
            savePending();
        }
        Cell c = cells.get(p.getUniqueId());
        if (c != null) {
            World w = Bukkit.getWorld(c.world);
            if (w != null) p.teleport(new Location(w, c.x + 0.5, c.y, c.z + 0.5));
            fillLocks(p);
        }
    }

    // ---------------------------------------------------------------- nothing works inside

    private boolean jailed(org.bukkit.entity.HumanEntity p) {
        return isJailed(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (jailed(e.getWhoClicked())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (jailed(e.getWhoClicked())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (jailed(e.getPlayer()) && !(e.getInventory().getHolder() instanceof dev.staffcore.gui.Menu)) e.setCancelled(true);
    }

    /** Scrolling through the hotbar does nothing either. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onScroll(PlayerItemHeldEvent e) {
        if (jailed(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (jailed(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (jailed(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (jailed(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (jailed(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (jailed(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && jailed(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && jailed(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!jailed(e.getPlayer())) return;
        String label = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (label.contains(":")) label = label.substring(label.indexOf(':') + 1);
        if (label.equals("ticket") || label.equals("appeal")) return;
        e.setCancelled(true);
        lang().send(e.getPlayer(), "prison.no-commands");
    }

    /** The cage itself cannot be broken by anyone while it is in use. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCageBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        String key = b.getX() + "," + b.getY() + "," + b.getZ();
        for (Cell c : cells.values()) {
            if (c.world.equals(b.getWorld().getName()) && c.blocks.containsKey(key)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- /prison and /unprison

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("unprison")) {
            if (args.length < 1) {
                lang().send(sender, "prison.usage-free");
                return true;
            }
            UUID id = byName(args[0]);
            if (id == null) lang().send(sender, "prison.not-jailed", "player", args[0]);
            else {
                release(id, sender.getName());
                lang().send(sender, "prison.released", "player", args[0]);
                plugin.staffLog(sender.getName(), "unprison", args[0], null);
            }
            return true;
        }
        if (args.length < 1) {
            lang().send(sender, "prison.usage");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            lang().send(sender, "admin.not-online", "player", args[0]);
            return true;
        }
        if (target.hasPermission("staffcore.exempt") && !sender.hasPermission("staffcore.exempt.bypass")) {
            lang().send(sender, "error.exempt", "player", target.getName());
            return true;
        }
        if (isJailed(target.getUniqueId())) {
            lang().send(sender, "prison.already", "player", target.getName());
            return true;
        }
        long ms = 0;
        int reasonFrom = 1;
        if (args.length > 1) {
            long parsed = TimeUtil.parse(args[1]);
            if (parsed > 0) {
                ms = parsed;
                reasonFrom = 2;
            } else if (args[1].equalsIgnoreCase("perm") || args[1].equalsIgnoreCase("permanent")) reasonFrom = 2;
        }
        String reason = args.length > reasonFrom ? String.join(" ", java.util.Arrays.copyOfRange(args, reasonFrom, args.length)) : lang().raw("prison.no-reason");
        jail(target, ms, reason, sender.getName());
        lang().send(sender, "prison.jailed", "player", target.getName());
        plugin.staffLog(sender.getName(), "prison", target.getName(), (ms > 0 ? TimeUtil.format(ms) : "perm") + " " + reason);
        return true;
    }

    @Override
    public List<String> onTabComplete(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String typed = args[args.length - 1].toLowerCase();
        if (args.length == 1) {
            if (command.getName().equalsIgnoreCase("unprison")) {
                for (String n : jailedNames()) if (n.toLowerCase().startsWith(typed)) out.add(n);
            } else {
                for (Player p : Bukkit.getOnlinePlayers()) if (!isJailed(p.getUniqueId()) && p.getName().toLowerCase().startsWith(typed)) out.add(p.getName());
            }
        } else if (args.length == 2 && command.getName().equalsIgnoreCase("prison")) {
            for (String t : List.of("5m", "15m", "30m", "1h", "1d", "perm")) if (t.startsWith(typed)) out.add(t);
        } else if (args.length == 3 && command.getName().equalsIgnoreCase("prison")) {
            for (String r : plugin.getConfig().getStringList("reason-presets")) if (r.toLowerCase().startsWith(typed)) out.add(r);
        }
        return out;
    }

    public List<String> jailedNames() {
        List<String> out = new ArrayList<>();
        for (Cell c : cells.values()) out.add(c.name);
        return out;
    }

    public UUID byName(String name) {
        for (Cell c : cells.values()) if (c.name.equalsIgnoreCase(name)) return c.player;
        return null;
    }
}
