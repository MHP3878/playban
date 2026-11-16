package dev.playban.listener;

import dev.playban.PlayBanPlugin;
import dev.playban.gui.Menu;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Enforces everything a punished player is not allowed to do. */
public final class RestrictionListener implements Listener {
    private final PlayBanPlugin plugin;
    private final Map<UUID, Long> lastHint = new HashMap<>();

    public RestrictionListener(PlayBanPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean blocked(Player p, String key) {
        return plugin.punishments().restricted(p, key);
    }

    /** Tells the player why nothing happened, but not on every single click. */
    private void hint(Player p, String key) {
        long now = System.currentTimeMillis();
        Long last = lastHint.get(p.getUniqueId());
        if (last != null && now - last < 3000) return;
        lastHint.put(p.getUniqueId(), now);
        plugin.lang().send(p, key);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (blocked(e.getPlayer(), "block-break")) {
            e.setCancelled(true);
            hint(e.getPlayer(), "blocked.action");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (blocked(e.getPlayer(), "block-place")) {
            e.setCancelled(true);
            hint(e.getPlayer(), "blocked.action");
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (blocked(e.getPlayer(), "interact")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (blocked(e.getPlayer(), "interact")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (blocked(e.getPlayer(), "block-place")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (blocked(e.getPlayer(), "block-break")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        if (e.getRemover() instanceof Player p && blocked(p, "block-break")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicle(VehicleEnterEvent e) {
        if (e.getEntered() instanceof Player p && blocked(p, "interact")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent e) {
        if (e.getAttacker() instanceof Player p && blocked(p, "damage")) e.setCancelled(true);
    }

    // a hidden player must not show up through server-wide announcements either
    @EventHandler(priority = EventPriority.HIGH)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        if (blocked(e.getPlayer(), "hide-from-players")) e.message(null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        if (blocked(e.getEntity(), "hide-from-players")) e.deathMessage(null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) return;
        if (e.getPlayer() instanceof Player p && blocked(p, "interact")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageDealt(EntityDamageByEntityEvent e) {
        Entity source = e.getDamager();
        if (source instanceof Projectile proj && proj.getShooter() instanceof Player shooter) source = shooter;
        if (source instanceof Player p && blocked(p, "damage")) {
            e.setCancelled(true);
            hint(p, "blocked.action");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageTaken(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && blocked(p, "invulnerable")
                && e.getCause() != EntityDamageEvent.DamageCause.VOID
                && e.getCause() != EntityDamageEvent.DamageCause.KILL) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        // a punished player cannot eat, so they must not starve either
        if (e.getEntity() instanceof Player p && blocked(p, "interact") && e.getFoodLevel() < p.getFoodLevel()) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (blocked(e.getPlayer(), "item-drop")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && blocked(p, "item-pickup")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && blocked(p, "mob-target")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!plugin.getConfig().getBoolean("restrictions.chat", true)) return;
        if (!plugin.punishments().isPunished(p.getUniqueId())) return;
        e.setCancelled(true);
        plugin.lang().send(p, "blocked.chat");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!blocked(p, "commands")) return;
        String label = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (label.contains(":")) label = label.substring(label.indexOf(':') + 1);
        if (label.equals("playban") || label.equals("pb") || label.equals("ticket") || label.equals("pbticket")) return;
        List<String> allowed = plugin.getConfig().getStringList("allowed-commands");
        for (String a : allowed) if (a.equalsIgnoreCase(label)) return;
        e.setCancelled(true);
        plugin.lang().send(p, "blocked.command");
    }
}
