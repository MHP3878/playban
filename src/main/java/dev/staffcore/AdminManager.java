package dev.staffcore;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** State behind the admin commands: frozen players, god mode, team chat, return points and inventory viewing. */
public final class AdminManager implements Listener {
    private final StaffCorePlugin plugin;
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private final Set<UUID> god = ConcurrentHashMap.newKeySet();
    /** cuffed player -> the staff member leading them */
    private final Map<UUID, UUID> cuffs = new ConcurrentHashMap<>();
    /** Read from the chat thread. */
    private final Set<UUID> teamChat = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> returnPoints = new HashMap<>();
    /** viewer -> owner of the inventory or ender chest they are looking at */
    private final Map<UUID, UUID> viewing = new HashMap<>();
    private BukkitTask ticker;
    private BukkitTask cuffTicker;

    public AdminManager(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    public void start() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID id : frozen) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                // the frost overlay of powder snow shows the player that they are frozen
                // just below "fully frozen": the same picture, without the game's own slowdown and frost damage
                p.setFreezeTicks(Math.max(p.getFreezeTicks(), 130));
                p.sendActionBar(lang().msg("admin.frozen-bar"));
            }
        }, 20L, 20L);
        // handcuffed players are pulled along behind the one leading them
        cuffTicker = Bukkit.getScheduler().runTaskTimer(plugin, this::pullCuffed, 4L, 4L);
    }

    public void stop() {
        if (ticker != null) ticker.cancel();
        if (cuffTicker != null) cuffTicker.cancel();
    }

    // ---------------------------------------------------------------- teleport

    public void rememberReturn(Player staff) {
        returnPoints.putIfAbsent(staff.getUniqueId(), staff.getLocation());
    }

    /** Teleports staff to a player, invisible, and remembers where they came from. */
    public void tpAdmin(Player staff, Player target) {
        rememberReturn(staff);
        if (!plugin.punishments().isVanished(staff.getUniqueId())) plugin.punishments().setVanished(staff, true);
        staff.teleport(target.getLocation());
        staff.playSound(staff.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.4f);
    }

    /** Like tpAdmin, to a place instead of a player. */
    public void tpAdminTo(Player staff, Location to) {
        rememberReturn(staff);
        if (!plugin.punishments().isVanished(staff.getUniqueId())) plugin.punishments().setVanished(staff, true);
        staff.teleport(to);
        staff.playSound(staff.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.4f);
    }

    public boolean back(Player staff) {
        Location to = returnPoints.remove(staff.getUniqueId());
        if (to == null) return false;
        staff.teleport(to);
        return true;
    }

    // ---------------------------------------------------------------- freeze

    public boolean isFrozen(UUID id) {
        return frozen.contains(id);
    }

    public void setFrozen(Player target, boolean value, String by) {
        if (value) {
            frozen.add(target.getUniqueId());
            target.leaveVehicle();
            target.showTitle(Title.title(lang().msg("admin.frozen-title"), lang().msg("admin.frozen-subtitle", "staff", by),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))));
            target.playSound(target.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 0.6f);
            lang().send(target, "admin.frozen-you", "staff", by);
        } else {
            frozen.remove(target.getUniqueId());
            target.setFreezeTicks(0);
            // no leftovers: no slowdown from the frost, and out of a bush or cobweb it may have been held in
            target.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
            target.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            Material at = target.getLocation().getBlock().getType();
            if (at == Material.SWEET_BERRY_BUSH || at == Material.COBWEB || at == Material.POWDER_SNOW) {
                Location free = target.getLocation().getBlock().getLocation().add(0.5, 1, 0.5);
                for (int up = 1; up <= 4 && !free.getBlock().getType().isAir(); up++) free.add(0, 1, 0);
                free.setYaw(target.getLocation().getYaw());
                free.setPitch(target.getLocation().getPitch());
                target.teleport(free);
            }
            target.playSound(target.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
            lang().send(target, "admin.unfrozen-you");
        }
    }

    // ---------------------------------------------------------------- handcuffs

    public boolean isCuffed(UUID id) {
        return cuffs.containsKey(id);
    }

    /** Frozen or cuffed: the player cannot act on their own. */
    public boolean isRestrained(UUID id) {
        return frozen.contains(id) || cuffs.containsKey(id);
    }

    public void cuff(Player target, Player staff) {
        cuffs.put(target.getUniqueId(), staff.getUniqueId());
        target.leaveVehicle();
        target.playSound(target.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1f, 0.8f);
        staff.playSound(staff.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1f, 0.8f);
        target.showTitle(Title.title(lang().msg("admin.cuffed-title"), lang().msg("admin.cuffed-subtitle", "staff", staff.getName())));
        lang().send(staff, "admin.cuffed", "player", target.getName());
    }

    public void uncuff(UUID target) {
        UUID staffId = cuffs.remove(target);
        Player t = Bukkit.getPlayer(target);
        if (t != null) {
            t.playSound(t.getLocation(), Sound.BLOCK_CHAIN_BREAK, 1f, 1f);
            lang().send(t, "admin.uncuffed-you");
        }
        Player staff = staffId == null ? null : Bukkit.getPlayer(staffId);
        if (staff != null && t != null) lang().send(staff, "admin.uncuffed", "player", t.getName());
    }

    private void pullCuffed() {
        for (Map.Entry<UUID, UUID> e : cuffs.entrySet()) {
            Player target = Bukkit.getPlayer(e.getKey());
            Player staff = Bukkit.getPlayer(e.getValue());
            if (target == null || staff == null) {
                if (target != null) uncuff(e.getKey());
                else cuffs.remove(e.getKey());
                continue;
            }
            Location lead = staff.getLocation();
            if (target.getWorld() != lead.getWorld() || target.getLocation().distanceSquared(lead) > 6.25) {
                // looking straight up or down leaves no direction: then go by the body's yaw
                double yaw = Math.toRadians(lead.getYaw());
                org.bukkit.util.Vector dir = new org.bukkit.util.Vector(-Math.sin(yaw), 0, Math.cos(yaw));
                Location behind = lead.clone().subtract(dir.multiply(1.5));
                // a flying leader does not pull the player up into the air: they stay on the ground below
                int ground = lead.getWorld().getHighestBlockYAt(behind.getBlockX(), behind.getBlockZ());
                if (ground + 1 < behind.getY() && ground > lead.getWorld().getMinHeight()) behind.setY(ground + 1);
                if (behind.getBlock().getType().isSolid()) behind = lead.clone();
                behind.setYaw(target.getLocation().getYaw());
                behind.setPitch(target.getLocation().getPitch());
                target.setFallDistance(0);
                target.teleport(behind);
            }
            try {
                Location a = target.getLocation().add(0, 1, 0);
                Location b = staff.getLocation().add(0, 1, 0);
                org.bukkit.util.Vector step = b.toVector().subtract(a.toVector()).multiply(0.2);
                for (int i = 0; i <= 5; i++) {
                    target.getWorld().spawnParticle(org.bukkit.Particle.CRIT, a.clone().add(step.clone().multiply(i)), 1, 0, 0, 0, 0);
                }
            } catch (RuntimeException ignored) {
                // only the chain look
            }
        }
    }

    // ---------------------------------------------------------------- god, fly, heal

    public boolean toggleGod(Player p) {
        if (god.remove(p.getUniqueId())) return false;
        god.add(p.getUniqueId());
        return true;
    }

    public boolean isGod(UUID id) {
        return god.contains(id);
    }

    public boolean toggleFly(Player p) {
        boolean on = !p.getAllowFlight();
        p.setAllowFlight(on);
        if (!on && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) p.setFlying(false);
        return on;
    }

    public void heal(Player p) {
        var attr = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        p.setHealth(attr == null ? 20 : attr.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20);
        p.setFireTicks(0);
        p.setFreezeTicks(0);
        for (var effect : p.getActivePotionEffects()) {
            if (effect.getType().getEffectCategory() == org.bukkit.potion.PotionEffectType.Category.HARMFUL) p.removePotionEffect(effect.getType());
        }
    }

    // ---------------------------------------------------------------- team chat

    public boolean toggleTeamChat(Player p) {
        if (teamChat.remove(p.getUniqueId())) return false;
        teamChat.add(p.getUniqueId());
        return true;
    }

    public void teamMessage(Player from, String text) {
        Component line = lang().msg("admin.teamchat-line", "player", from.getName(), "text", text);
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("staffcore.teamchat")) p.sendMessage(line);
        Bukkit.getConsoleSender().sendMessage(line);
    }

    // ---------------------------------------------------------------- inventories

    public void openInventory(Player staff, Player target, boolean ender) {
        Inventory inv = ender ? target.getEnderChest() : target.getInventory();
        staff.openInventory(inv);
        viewing.put(staff.getUniqueId(), target.getUniqueId());
    }

    private boolean viewOnly(Player viewer) {
        return viewing.containsKey(viewer.getUniqueId()) && !viewer.hasPermission("staffcore.invsee.edit");
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!isRestrained(e.getPlayer().getUniqueId())) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;
        Location stay = from.clone();
        stay.setYaw(to.getYaw());
        stay.setPitch(to.getPitch());
        e.setTo(stay);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isRestrained(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isRestrained(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (isRestrained(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && isRestrained(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        // frozen players are not hurt either, and the frost overlay must not damage them
        if (isGod(p.getUniqueId()) || isRestrained(p.getUniqueId())) e.setCancelled(true);
    }

    /** Frozen or cuffed players cannot ride away on a boat or a horse either. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onRide(org.bukkit.event.vehicle.VehicleEnterEvent e) {
        if (e.getEntered() instanceof Player p && isRestrained(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMount(org.bukkit.event.entity.EntityMountEvent e) {
        if (e.getEntity() instanceof Player p && isRestrained(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityClick(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (isRestrained(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    /** An ender pearl thrown just before the freeze does not carry them away. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPearl(org.bukkit.event.player.PlayerTeleportEvent e) {
        if (isRestrained(e.getPlayer().getUniqueId()) && e.getCause() == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && isGod(p.getUniqueId()) && e.getFoodLevel() < p.getFoodLevel()) e.setCancelled(true);
    }

    /** Frozen players may still write a ticket or an appeal, nothing else. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!isRestrained(e.getPlayer().getUniqueId())) return;
        String label = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (label.contains(":")) label = label.substring(label.indexOf(':') + 1);
        if (label.equals("ticket") || label.equals("appeal") || label.equals("msg") || label.equals("tell")) return;
        e.setCancelled(true);
        lang().send(e.getPlayer(), "admin.frozen-command");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!teamChat.contains(p.getUniqueId())) return;
        if (plugin.tickets().inChat(p.getUniqueId())) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(plugin, () -> teamMessage(p, text));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && viewOnly(p)
                && (e.getView().getTopInventory() instanceof PlayerInventory || e.getView().getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.ENDER_CHEST)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && viewOnly(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        viewing.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        viewing.remove(id);
        if (isCuffed(id)) {
            uncuff(id);
            plugin.punishments().notifyStaff(lang().prefixed("admin.frozen-quit", "player", e.getPlayer().getName()));
        }
        // whoever was leading someone lets go when they leave
        cuffs.entrySet().removeIf(c -> c.getValue().equals(id));
        if (isFrozen(id)) {
            // logging out while frozen is the classic way to dodge a check, so the team hears about it
            plugin.punishments().notifyStaff(lang().prefixed("admin.frozen-quit", "player", e.getPlayer().getName()));
        }
    }
}
