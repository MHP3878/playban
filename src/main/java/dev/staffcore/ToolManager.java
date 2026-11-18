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
            new Tool("xray", Material.TINTED_GLASS, "staffcore.tool.xray"),
            new Tool("warnstick", Material.BLAZE_ROD, "staffcore.tool.warnstick"),
            new Tool("brush", Material.BRUSH, "staffcore.tool.brush"),
            new Tool("invsee", Material.CHEST, "staffcore.tool.invsee"),
            new Tool("randomtp", Material.ENDER_PEARL, "staffcore.tool.randomtp"),
            new Tool("fly", Material.FEATHER, "staffcore.tool.fly"),
            new Tool("kbstick", Material.STICK, "staffcore.tool.kbstick"),
            new Tool("spectate", Material.ENDER_EYE, "staffcore.tool.spectate"),
            new Tool("prisonkey", Material.TRIPWIRE_HOOK, "staffcore.prison"),
            new Tool("trollwand", Material.AMETHYST_SHARD, "staffcore.troll"),
            new Tool("bait", Material.TRAPPED_CHEST, "staffcore.tool.bait"),
            new Tool("esp", Material.SPYGLASS, "staffcore.esp"),
            new Tool("ghost", Material.ECHO_SHARD, "staffcore.ghost"),
            new Tool("exit", Material.BARRIER, "staffcore.staffmode"));

    /** Where each item goes in staff mode: the hotbar first, then the row above it. */
    private static final List<String> KIT = List.of("compass", "vanish", "inspector", "freeze", "handcuffs", "warnstick", "banhammer", "brush", "xray",
            "invsee", "randomtp", "fly", "kbstick", "spectate", "prisonkey", "bait", "trollwand", "esp", "ghost", "exit");

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
        // an inactive admin is a normal player: admin items work in admin mode only
        if (plugin.adminPanel() != null && plugin.adminPanel().required() && !inStaffMode(p.getUniqueId())) {
            p.sendMessage(lang().prefixed("panel.need-admin-mode").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/admin")));
            return null;
        }
        return tool;
    }

    // ---------------------------------------------------------------- the /tools menu

    public void menu(Player p) {
        Menu m = new Menu(4, lang().msg("tools.title"));
        int slot = 10;
        for (Tool t : TOOLS) {
            if (t.id().equals("exit")) continue;
            if (slot % 9 == 8) slot += 2;
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
        if (p.hasPermission("staffcore.staffmode")) {
            m.set(31, Items.of(Material.NETHERITE_CHESTPLATE, lang().msg("tools.staffmode.name"), lang().lore("tools.staffmode.lore")), e -> {
                p.closeInventory();
                toggleStaffMode(p);
            });
        }
        m.fill().open(p);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) lang().send(sender, "error.players-only");
        else if (command.getName().equalsIgnoreCase("staffmode")) toggleStaffMode(p);
        else menu(p);
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
        if (tool.id().equals("warnstick")) {
            if (usable(staff) != null && !exempt(staff, target)) warn(staff, target);
            return;
        }
        if (tool.id().equals("kbstick")) {
            if (usable(staff) != null) knockbackTest(staff, target);
            return;
        }
        if (!tool.id().equals("banhammer") || usable(staff) == null) return;
        // sneaking: the punish menu, to choose
        if (staff.isSneaking() || !plugin.modules().on("banhammer")) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.adminMenus().punishPlayer(staff, target.getUniqueId(), target.getName()));
            return;
        }
        if (exempt(staff, target) || plugin.antiCheat().animation().playing(target.getUniqueId())) return;
        // a plain hit: the hammer comes down, then what is set (kick by default)
        String step = plugin.getConfig().getString("banhammer.action", "KICK");
        String reason = plugin.getConfig().getString("banhammer.reason", "");
        if (reason.isBlank()) reason = lang().raw("tools.banhammer-reason");
        String why = reason;
        plugin.staffLog(staff.getName(), "banhammer", target.getName(), step);
        plugin.antiCheat().animation().hammer(target, staff, () -> plugin.antiCheat().applyStep(target.getUniqueId(), target.getName(), step, why, staff.getName()));
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
            case "invsee" -> plugin.admin().openInventory(staff, target, staff.isSneaking());
            case "warnstick" -> takeBackWarning(staff, target);
            case "spectate" -> spectate(staff, target);
            case "prisonkey" -> {
                if (plugin.prison().isJailed(target.getUniqueId())) plugin.prison().release(target.getUniqueId(), staff.getName());
                else if (!exempt(staff, target)) {
                    long ms = TimeUtil.parse(plugin.getConfig().getString("prison.tool-time", "15m"));
                    plugin.prison().jail(target, ms, lang().raw("prison.tool-reason"), staff.getName());
                }
            }
            case "trollwand" -> {
                if (!exempt(staff, target)) plugin.trolls().menu(staff, target);
            }
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
        Player p = e.getPlayer();
        Tool held = toolOf(p.getInventory().getItemInMainHand());
        if (held == null) return;
        // the block-log brush works with both buttons on a block
        if (held.id().equals("brush") && e.getClickedBlock() != null
                && (e.getAction() == Action.LEFT_CLICK_BLOCK || e.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            e.setCancelled(true);
            if (usable(p) == null) return;
            Block b = e.getClickedBlock();
            // right-click on a non-container looks at the spot in front of it, where something was placed or broken
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK && !(b.getState() instanceof org.bukkit.block.Container)) b = b.getRelative(e.getBlockFace());
            plugin.blockLog().inspect(p, b);
            return;
        }
        if (held.id().equals("bait") && e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
            e.setCancelled(true);
            if (usable(p) == null) return;
            if (!(e.getClickedBlock().getState() instanceof org.bukkit.block.Container)) {
                lang().send(p, "tools.bait-no-container");
                return;
            }
            lang().send(p, plugin.moderation().toggleBait(e.getClickedBlock()) ? "tools.bait-set" : "tools.bait-removed");
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        Tool tool = usable(p);
        if (tool == null) return;
        switch (tool.id()) {
            case "randomtp" -> {
                List<Player> others = new ArrayList<>();
                for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p) && !o.hasPermission("staffcore.staff")) others.add(o);
                if (others.isEmpty()) lang().send(p, "tools.nobody");
                else {
                    Player o = others.get(new java.util.Random().nextInt(others.size()));
                    plugin.admin().tpAdmin(p, o);
                    lang().send(p, "admin.tpadmin", "player", o.getName());
                }
            }
            case "fly" -> lang().send(p, plugin.admin().toggleFly(p) ? "admin.fly-on" : "admin.fly-off", "player", p.getName());
            case "exit" -> toggleStaffMode(p);
            case "freeze" -> {
                Player target = aim(p, 40);
                if (target == null) lang().send(p, "tools.missed");
                else freeze(p, target);
            }
            case "xray" -> xray(p);
            case "bait" -> lang().send(p, "tools.bait-how");
            case "ghost" -> plugin.ghost().toggle(p);
            case "esp" -> {
                if (plugin.modules().on("esp")) plugin.esp().cycle(p);
                else lang().send(p, "module.is-off", "module", plugin.modules().name("esp"));
            }
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

    // ---------------------------------------------------------------- warn stick

    private final Map<java.util.UUID, Long> lastWarn = new java.util.HashMap<>();

    /** A hit with the warn stick gives a warning; a second hit right after does not give a second one. */
    private void warn(Player staff, Player target) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(target.getUniqueId());
        if (last != null && now - last < 3000) return;
        lastWarn.put(target.getUniqueId(), now);
        String reason = plugin.getConfig().getString("staff-mode.warn-reason", "");
        if (reason.isBlank()) reason = lang().raw("tools.warn-reason");
        plugin.punishments().warn(target.getUniqueId(), target.getName(), reason, staff.getName(), null);
        try {
            target.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, target.getLocation().add(0, 2, 0), 5, 0.3, 0.2, 0.3, 0);
        } catch (RuntimeException ignored) {
            // only the effect
        }
    }

    /** Right-click with the warn stick takes back the newest warning of that player. */
    private void takeBackWarning(Player staff, Player target) {
        List<dev.staffcore.model.Punishment> history = plugin.store().history.get(target.getUniqueId());
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                dev.staffcore.model.Punishment w = history.get(i);
                if (!"WARN".equals(w.type)) continue;
                // your own warnings, or anybody's for admins
                if (!w.staff.equals(staff.getName()) && !staff.hasPermission("staffcore.admin")) break;
                history.remove(i);
                plugin.store().markDirty();
                lang().send(staff, "tools.warn-taken-back", "player", target.getName());
                lang().send(target, "tools.warn-taken-back-you", "staff", staff.getName());
                return;
            }
        }
        lang().send(staff, "tools.no-warning", "player", target.getName());
    }

    // ---------------------------------------------------------------- staff mode

    public boolean inStaffMode(java.util.UUID id) {
        return plugin.store().staffModeInventories.containsKey(id);
    }

    private final java.util.Set<java.util.UUID> wasVanished = new java.util.HashSet<>();

    public void toggleStaffMode(Player p) {
        if (inStaffMode(p.getUniqueId())) leaveStaffMode(p);
        else enterStaffMode(p);
    }

    /** Puts the player's things away safely and gives them every admin item they may use. */
    private java.io.File modeFile() {
        return new java.io.File(plugin.getDataFolder(), "staffmode-gamemodes.yml");
    }

    /** The game mode before staff mode, on disk, so ghost mode cannot leave anyone in creative after a crash. */
    private void rememberMode(Player p, org.bukkit.GameMode mode) {
        org.bukkit.configuration.file.YamlConfiguration cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(modeFile());
        cfg.set(p.getUniqueId().toString(), mode == null ? null : mode.name());
        try {
            cfg.save(modeFile());
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not save staffmode-gamemodes.yml: " + e.getMessage());
        }
    }

    private org.bukkit.GameMode rememberedMode(Player p) {
        String m = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(modeFile()).getString(p.getUniqueId().toString());
        try {
            return m == null ? null : org.bukkit.GameMode.valueOf(m);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void enterStaffMode(Player p) {
        rememberMode(p, p.getGameMode());
        java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
        String saved = b64.encodeToString(ItemStack.serializeItemsAsBytes(p.getInventory().getContents()));
        plugin.store().staffModeInventories.put(p.getUniqueId(), saved);
        plugin.store().markDirty();
        plugin.store().saveIfDirty();
        p.getInventory().clear();
        for (int slot = 0; slot < KIT.size(); slot++) {
            String id = KIT.get(slot);
            if (id.isEmpty()) continue;
            for (Tool t : TOOLS) {
                if (t.id().equals(id) && p.hasPermission(t.permission())) p.getInventory().setItem(slot, create(t));
            }
        }
        if (plugin.punishments().isVanished(p.getUniqueId())) wasVanished.add(p.getUniqueId());
        else plugin.punishments().setVanished(p, true);
        if (!p.getAllowFlight()) plugin.admin().toggleFly(p);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1f, 1f);
        lang().send(p, "tools.staffmode-on");
    }

    public void leaveStaffMode(Player p) {
        String saved = plugin.store().staffModeInventories.remove(p.getUniqueId());
        plugin.store().markDirty();
        stopSpectating(p);
        plugin.esp().off(p);
        if (plugin.ghost() != null && plugin.ghost().on(p.getUniqueId())) plugin.ghost().off(p);
        org.bukkit.GameMode before = rememberedMode(p);
        if (before != null && p.getGameMode() != before) p.setGameMode(before);
        rememberMode(p, null);
        // things taken while in staff mode (from a chest or someone's inventory) are kept, only the admin items go
        List<ItemStack> gathered = new ArrayList<>();
        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null && !item.getType().isAir() && toolOf(item) == null) gathered.add(item.clone());
        }
        p.getInventory().clear();
        if (saved != null) {
            try {
                ItemStack[] back = ItemStack.deserializeItemsFromBytes(java.util.Base64.getDecoder().decode(saved));
                for (int i = 0; i < back.length; i++) if (back[i] != null && back[i].getType().isAir()) back[i] = null;
                p.getInventory().setContents(back);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Could not give " + p.getName() + " their inventory back after staff mode: " + ex.getMessage());
            }
        }
        for (ItemStack left : p.getInventory().addItem(gathered.toArray(new ItemStack[0])).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
        plugin.store().saveIfDirty();
        if (!wasVanished.remove(p.getUniqueId()) && plugin.punishments().isVanished(p.getUniqueId())) plugin.punishments().setVanished(p, false);
        if (p.getAllowFlight() && p.getGameMode() != org.bukkit.GameMode.CREATIVE && p.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
            plugin.admin().toggleFly(p);
        }
        lang().send(p, "tools.staffmode-off");
    }

    /** On shutdown everybody gets their things back, so removing the plugin never loses anything. */
    public void leaveAll() {
        for (Player p : Bukkit.getOnlinePlayers()) if (inStaffMode(p.getUniqueId())) leaveStaffMode(p);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        if (inStaffMode(e.getPlayer().getUniqueId())) leaveStaffMode(e.getPlayer());
    }

    /** After a crash the stored inventory is still on disk: give it back on the next join. */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        if (inStaffMode(e.getPlayer().getUniqueId())) leaveStaffMode(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent e) {
        if (inStaffMode(e.getPlayer().getUniqueId()) || toolOf(e.getItemDrop().getItemStack()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && inStaffMode(p.getUniqueId())) e.setCancelled(true);
    }

    /** Admin items stay in the staff member's own inventory, they cannot be put into chests. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMoveItem(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView().getTopInventory().getHolder() instanceof Menu) return;
        if (e.getView().getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) return;
        ItemStack hotbar = e.getHotbarButton() >= 0 ? p.getInventory().getItem(e.getHotbarButton()) : null;
        boolean tool = toolOf(e.getCurrentItem()) != null || toolOf(e.getCursor()) != null || toolOf(hotbar) != null
                || (e.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND && toolOf(p.getInventory().getItemInOffHand()) != null);
        if (tool) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDragTool(org.bukkit.event.inventory.InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) return;
        if (toolOf(e.getOldCursor()) != null && e.getRawSlots().stream().anyMatch(slot -> slot < e.getView().getTopInventory().getSize())) e.setCancelled(true);
    }

    /** Admin items never drop on death, not even the bannhammer. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        e.getDrops().removeIf(item -> toolOf(item) != null);
        if (inStaffMode(e.getPlayer().getUniqueId())) e.getDrops().clear();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFrame(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof org.bukkit.entity.ItemFrame || e.getRightClicked() instanceof org.bukkit.entity.ArmorStand
                || e.getRightClicked() instanceof org.bukkit.entity.Allay) {
            if (toolOf(e.getPlayer().getInventory().getItem(e.getHand())) != null) e.setCancelled(true);
        }
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
    private final Map<java.util.UUID, List<Entity>> xrayShown = new java.util.HashMap<>();
    private final Map<java.util.UUID, Long> xrayUsed = new java.util.HashMap<>();

    private void xray(Player p) {
        long now = System.currentTimeMillis();
        Long last = xrayUsed.get(p.getUniqueId());
        if (last != null && now - last < 3000) {
            lang().send(p, "tools.xray-wait");
            return;
        }
        xrayUsed.put(p.getUniqueId(), now);
        List<Entity> before = xrayShown.remove(p.getUniqueId());
        if (before != null) before.forEach(Entity::remove);
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
        xrayShown.put(p.getUniqueId(), shown);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            shown.forEach(Entity::remove);
            xrayShown.remove(p.getUniqueId(), shown);
        }, 20L * 15);
    }

    // ---------------------------------------------------------------- knockback test

    /**
     * Pushes the player like a normal hit would, without damage, and measures how far they really moved.
     * Somebody with an anti-knockback cheat barely moves.
     */
    public void knockbackTest(Player staff, Player target) {
        Location start = target.getLocation();
        double yaw = Math.toRadians(staff.getLocation().getYaw());
        target.setVelocity(new org.bukkit.util.Vector(-Math.sin(yaw) * 0.42, 0.36, Math.cos(yaw) * 0.42));
        target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.6f, 1f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!target.isOnline() || target.getWorld() != start.getWorld()) return;
            Location end = target.getLocation();
            double moved = Math.hypot(end.getX() - start.getX(), end.getZ() - start.getZ());
            String key = moved < 0.3 ? "tools.kb-suspicious" : "tools.kb-ok";
            lang().send(staff, key, "player", target.getName(), "blocks", String.format(java.util.Locale.ROOT, "%.2f", moved));
            if (moved < 0.3) plugin.staffLog(staff.getName(), "kb-test", target.getName(), "moved " + String.format(java.util.Locale.ROOT, "%.2f", moved));
        }, 10L);
    }

    // ---------------------------------------------------------------- spectate eye

    private record Watch(org.bukkit.GameMode mode, Location from) {}

    private final Map<java.util.UUID, Watch> watching = new java.util.HashMap<>();

    /** Looks through the player's eyes; sneaking ends it and brings you back. */
    private void spectate(Player staff, Player target) {
        watching.putIfAbsent(staff.getUniqueId(), new Watch(staff.getGameMode(), staff.getLocation()));
        if (!plugin.punishments().isVanished(staff.getUniqueId())) plugin.punishments().setVanished(staff, true);
        staff.setGameMode(org.bukkit.GameMode.SPECTATOR);
        staff.teleport(target.getLocation());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (staff.isOnline() && target.isOnline() && staff.getGameMode() == org.bukkit.GameMode.SPECTATOR) staff.setSpectatorTarget(target);
        }, 2L);
        lang().send(staff, "tools.spectating", "player", target.getName());
    }

    public void stopSpectating(Player staff) {
        Watch w = watching.remove(staff.getUniqueId());
        if (w == null) return;
        staff.setSpectatorTarget(null);
        staff.teleport(w.from());
        staff.setGameMode(w.mode());
    }

    @EventHandler
    public void onSneak(org.bukkit.event.player.PlayerToggleSneakEvent e) {
        if (e.isSneaking() && watching.containsKey(e.getPlayer().getUniqueId())) {
            Player p = e.getPlayer();
            Bukkit.getScheduler().runTask(plugin, () -> {
                stopSpectating(p);
                lang().send(p, "tools.spectate-end");
            });
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuitWatching(org.bukkit.event.player.PlayerQuitEvent e) {
        stopSpectating(e.getPlayer());
    }
}
