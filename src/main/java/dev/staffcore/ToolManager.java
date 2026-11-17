package dev.staffcore;

import dev.staffcore.gui.Items;
import dev.staffcore.gui.Menu;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Admin items from /tools: bannhammer, freeze wand, handcuffs, inspector, teleport compass, vanish cloak and x-ray. */
public final class ToolManager implements Listener, TabExecutor {
    /** One tool: the item it is made from and who may use it. */
    public record Tool(String id, Material material, String permission) {}

    public static final List<Tool> TOOLS = List.of(
            new Tool("banhammer", Material.MACE, "staffcore.tool.banhammer"),
            new Tool("freeze", Material.BREEZE_ROD, "staffcore.tool.freeze"),
            new Tool("handcuffs", Material.LEAD, "staffcore.tool.handcuffs"),
            new Tool("inspector", Material.BOOK, "staffcore.tool.inspector"),
            new Tool("compass", Material.RECOVERY_COMPASS, "staffcore.tool.compass"),
            new Tool("vanish", Material.PHANTOM_MEMBRANE, "staffcore.tool.vanish"),
            new Tool("xray", Material.SPYGLASS, "staffcore.tool.xray"));

    private static final Map<Material, Color> ORES = new EnumMap<>(Material.class);

    static {
        Color diamond = Color.fromRGB(0x4fe3e3), gold = Color.fromRGB(0xf5c542), iron = Color.fromRGB(0xd9b38c);
        ORES.put(Material.DIAMOND_ORE, diamond);
        ORES.put(Material.DEEPSLATE_DIAMOND_ORE, diamond);
        ORES.put(Material.ANCIENT_DEBRIS, Color.fromRGB(0x8a4b3a));
        ORES.put(Material.EMERALD_ORE, Color.fromRGB(0x3ad16a));
        ORES.put(Material.DEEPSLATE_EMERALD_ORE, Color.fromRGB(0x3ad16a));
        ORES.put(Material.GOLD_ORE, gold);
        ORES.put(Material.DEEPSLATE_GOLD_ORE, gold);
        ORES.put(Material.NETHER_GOLD_ORE, gold);
        ORES.put(Material.IRON_ORE, iron);
        ORES.put(Material.DEEPSLATE_IRON_ORE, iron);
        ORES.put(Material.LAPIS_ORE, Color.fromRGB(0x2f5fd0));
        ORES.put(Material.DEEPSLATE_LAPIS_ORE, Color.fromRGB(0x2f5fd0));
        ORES.put(Material.REDSTONE_ORE, Color.fromRGB(0xd02f2f));
        ORES.put(Material.DEEPSLATE_REDSTONE_ORE, Color.fromRGB(0xd02f2f));
        ORES.put(Material.SPAWNER, Color.fromRGB(0xb04fe3));
    }

    private final StaffCorePlugin plugin;
    private final NamespacedKey key;

    public ToolManager(StaffCorePlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "tool");
    }

    private Lang lang() {
        return plugin.lang();
    }

    // ---------------------------------------------------------------- items

    public ItemStack create(Tool tool) {
        ItemStack item = Items.of(tool.material(), lang().msg("tools." + tool.id() + ".name"), lang().lore("tools." + tool.id() + ".lore"));
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, tool.id());
        meta.setEnchantmentGlintOverride(true);
        meta.setUnbreakable(true);
        item.setItemMeta(meta);
        return item;
    }

    public Tool toolOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (id == null) return null;
        for (Tool t : TOOLS) if (t.id().equals(id)) return t;
        return null;
    }

    /** The tool in the main hand, if the player may use it; tells them when they may not. */
    private Tool usable(Player p) {
        Tool tool = toolOf(p.getInventory().getItemInMainHand());
        if (tool == null) return null;
        if (!p.hasPermission(tool.permission())) {
            lang().send(p, "tools.not-allowed");
            return null;
        }
        return tool;
    }

    // ---------------------------------------------------------------- the /tools menu

    public void menu(Player p) {
        Menu m = new Menu(3, lang().msg("tools.title"));
        int slot = 10;
        for (Tool t : TOOLS) {
            boolean may = p.hasPermission(t.permission());
            List<Component> lore = new ArrayList<>(lang().lore("tools." + t.id() + ".lore"));
            lore.add(Component.empty());
            lore.add(lang().msg(may ? "tools.take" : "tools.locked"));
            m.set(slot++, Items.of(may ? t.material() : Material.GRAY_DYE, lang().msg("tools." + t.id() + ".name"), lore), e -> {
                if (!may) return;
                p.getInventory().addItem(create(t));
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.2f);
            });
        }
        m.fill().open(p);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player p) menu(p);
        else lang().send(sender, "error.players-only");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    // ---------------------------------------------------------------- using them

    /** Bannhammer: a hit opens the punish menu for the player that was hit, with a lightning effect. */
    @EventHandler(priority = EventPriority.LOW)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player staff) || !(e.getEntity() instanceof Player target)) return;
        Tool tool = toolOf(staff.getInventory().getItemInMainHand());
        if (tool == null) return;
        e.setCancelled(true);
        if (!tool.id().equals("banhammer") || usable(staff) == null) return;
        try {
            target.getWorld().strikeLightningEffect(target.getLocation());
        } catch (RuntimeException ignored) {
            // only the effect
        }
        Bukkit.getScheduler().runTask(plugin, () -> plugin.adminMenus().punishPlayer(staff, target.getUniqueId(), target.getName()));
    }

    /** Right-click on a player: handcuffs, inspector, freeze wand. */
    @EventHandler(priority = EventPriority.LOW)
    public void onClickPlayer(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof Player target)) return;
        Player staff = e.getPlayer();
        if (toolOf(staff.getInventory().getItemInMainHand()) == null) return;
        e.setCancelled(true);
        Tool tool = usable(staff);
        if (tool == null) return;
        switch (tool.id()) {
            case "handcuffs" -> {
                if (plugin.admin().isCuffed(target.getUniqueId())) plugin.admin().uncuff(target.getUniqueId());
                else if (!exempt(staff, target)) plugin.admin().cuff(target, staff);
            }
            case "inspector" -> plugin.adminMenus().profile(staff, target.getUniqueId(), target.getName());
            case "freeze" -> freeze(staff, target);
            default -> {
                // the others work by right-clicking the air
            }
        }
    }

    private boolean exempt(Player staff, Player target) {
        if (target.hasPermission("staffcore.exempt") && !staff.hasPermission("staffcore.exempt.bypass")) {
            lang().send(staff, "error.exempt", "player", target.getName());
            return true;
        }
        return false;
    }

    private void freeze(Player staff, Player target) {
        if (exempt(staff, target)) return;
        boolean on = !plugin.admin().isFrozen(target.getUniqueId());
        plugin.admin().setFrozen(target, on, staff.getName());
        lang().send(staff, on ? "admin.frozen" : "admin.unfrozen", "player", target.getName());
    }

    /** Right-click in the air: freeze wand shoots, x-ray, compass, vanish cloak. */
    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (toolOf(p.getInventory().getItemInMainHand()) == null) return;
        e.setCancelled(true);
        Tool tool = usable(p);
        if (tool == null) return;
        switch (tool.id()) {
            case "freeze" -> {
                Player target = aim(p, 40);
                if (target == null) lang().send(p, "tools.missed");
                else freeze(p, target);
            }
            case "xray" -> xray(p);
            case "compass" -> compass(p);
            case "vanish" -> plugin.punishments().setVanished(p, !plugin.punishments().isVanished(p.getUniqueId()));
            default -> {
                // banhammer, handcuffs and inspector work on players
            }
        }
    }

    /** Tools are not blocks or buckets, so they cannot be placed by accident. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (toolOf(e.getItemInHand()) != null) e.setCancelled(true);
    }

    /** The player the staff member is looking at, with a frost trail to show the shot. */
    private Player aim(Player p, double range) {
        Location eye = p.getEyeLocation();
        RayTraceResult hit = p.getWorld().rayTraceEntities(eye, eye.getDirection(), range, 0.6,
                en -> en instanceof Player other && !other.equals(p));
        double length = hit == null ? range : hit.getHitPosition().distance(eye.toVector());
        try {
            for (double d = 1; d < length; d += 0.7) {
                p.getWorld().spawnParticle(Particle.SNOWFLAKE, eye.clone().add(eye.getDirection().multiply(d)), 2, 0.05, 0.05, 0.05, 0);
            }
        } catch (RuntimeException ignored) {
            // only the trail
        }
        p.playSound(p.getLocation(), Sound.ENTITY_BREEZE_SHOOT, 0.7f, 1.4f);
        Entity target = hit == null ? null : hit.getHitEntity();
        return target instanceof Player tp ? tp : null;
    }

    private void compass(Player p) {
        List<Player> others = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) others.add(o);
        Menu m = plugin.adminMenus().paged(lang().msg("tools.compass-title"), others, 0,
                o -> Items.head(o, lang().msg("menu.ticket.person.name", "player", o.getName()), lang().lore("tools.compass-entry",
                        "world", o.getWorld().getName(), "x", o.getLocation().getBlockX(), "y", o.getLocation().getBlockY(), "z", o.getLocation().getBlockZ())),
                (o, ev) -> {
                    p.closeInventory();
                    plugin.admin().tpAdmin(p, o);
                    lang().send(p, "admin.tpadmin", "player", o.getName());
                }, n -> {}, p::closeInventory);
        m.open(p);
    }

    // ---------------------------------------------------------------- x-ray

    /** Ores within the radius, nearest first, at most the limit. */
    public List<Block> findOres(Location center, int radius, int limit) {
        List<Block> found = new ArrayList<>();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        int minY = center.getWorld().getMinHeight(), maxY = center.getWorld().getMaxHeight() - 1;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                for (int y = Math.max(minY, cy - radius); y <= Math.min(maxY, cy + radius); y++) {
                    Block b = center.getWorld().getBlockAt(x, y, z);
                    if (ORES.containsKey(b.getType())) found.add(b);
                }
            }
        }
        found.sort((a, b) -> Double.compare(a.getLocation().distanceSquared(center), b.getLocation().distanceSquared(center)));
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    /** Glowing copies of nearby ores that only this staff member sees, through walls, for 15 seconds. */
    private void xray(Player p) {
        List<Block> ores = findOres(p.getLocation(), 20, 200);
        lang().send(p, "tools.xray-found", "count", ores.size());
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.6f);
        List<Entity> shown = new ArrayList<>();
        for (Block b : ores) {
            try {
                BlockDisplay d = b.getWorld().spawn(b.getLocation(), BlockDisplay.class, display -> {
                    display.setBlock(b.getBlockData());
                    display.setGlowing(true);
                    display.setGlowColorOverride(ORES.get(b.getType()));
                    display.setVisibleByDefault(false);
                    display.setPersistent(false);
                    display.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                    display.setTransformation(new Transformation(new Vector3f(-0.005f), new Quaternionf(), new Vector3f(1.01f), new Quaternionf()));
                });
                p.showEntity(plugin, d);
                shown.add(d);
            } catch (RuntimeException ignored) {
                // a server that cannot spawn displays still gets the count above
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> shown.forEach(Entity::remove), 20L * 15);
    }
}
