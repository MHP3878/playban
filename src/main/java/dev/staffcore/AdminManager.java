package dev.staffcore;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
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
    /** Read from the chat thread. */
    private final Set<UUID> teamChat = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> returnPoints = new HashMap<>();
    /** viewer -> owner of the inventory or ender chest they are looking at */
    private final Map<UUID, UUID> viewing = new HashMap<>();
    private BukkitTask ticker;

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
                p.setFreezeTicks(Math.max(p.getFreezeTicks(), 140));
                p.sendActionBar(lang().msg("admin.frozen-bar"));
            }
        }, 20L, 20L);
    }

    public void stop() {
        if (ticker != null) ticker.cancel();
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
            target.showTitle(Title.title(lang().msg("admin.frozen-title"), lang().msg("admin.frozen-subtitle", "staff", by),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))));
            target.playSound(target.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 0.6f);
            lang().send(target, "admin.frozen-you", "staff", by);
        } else {
            frozen.remove(target.getUniqueId());
            target.setFreezeTicks(0);
            target.playSound(target.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
            lang().send(target, "admin.unfrozen-you");
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
        if (!isFrozen(e.getPlayer().getUniqueId())) return;
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
        if (isFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (isFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && isFrozen(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        // frozen players are not hurt either, and the frost overlay must not damage them
        if (isGod(p.getUniqueId()) || isFrozen(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && isGod(p.getUniqueId()) && e.getFoodLevel() < p.getFoodLevel()) e.setCancelled(true);
    }

    /** Frozen players may still write a ticket or an appeal, nothing else. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!isFrozen(e.getPlayer().getUniqueId())) return;
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
        if (isFrozen(id)) {
            // logging out while frozen is the classic way to dodge a check, so the team hears about it
            plugin.punishments().notifyStaff(lang().prefixed("admin.frozen-quit", "player", e.getPlayer().getName()));
        }
    }
}
