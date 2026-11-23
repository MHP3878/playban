package dev.staffcore.module;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /totem: puts a totem from the inventory into the off hand, after a short moment, done by the server.
 * Meant for Lumio's radial menu, but works as a plain command too. The owner decides whether it works
 * in a fight (totem.in-combat) and how long it takes (totem.delay-ticks), so it stays fair.
 */
public final class TotemCommand implements TabExecutor, Listener {
    private final StaffCorePlugin plugin;
    private final Map<UUID, Long> lastHit = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();

    public TotemCommand(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) lastHit.put(p.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastHit.remove(e.getPlayer().getUniqueId());
        pending.remove(e.getPlayer().getUniqueId());
    }

    private boolean inCombat(Player p) {
        Long t = lastHit.get(p.getUniqueId());
        long window = Math.max(0, plugin.getConfig().getLong("totem.combat-seconds", 10)) * 1000L;
        return t != null && System.currentTimeMillis() - t < window;
    }

    /** The first storage slot with a totem, or -1. */
    static int findTotem(PlayerInventory inv) {
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() == Material.TOTEM_OF_UNDYING) return i;
        }
        return -1;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            lang().send(sender, "error.players-only");
            return true;
        }
        if (p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) {
            lang().send(p, "totem.already");
            return true;
        }
        if (findTotem(p.getInventory()) < 0) {
            lang().send(p, "totem.none");
            return true;
        }
        if (!plugin.getConfig().getBoolean("totem.in-combat", false) && inCombat(p)) {
            lang().send(p, "totem.combat");
            return true;
        }
        if (!pending.add(p.getUniqueId())) return true;
        long delay = Math.max(0, Math.min(100, plugin.getConfig().getLong("totem.delay-ticks", 10)));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(p.getUniqueId());
            if (!p.isOnline() || p.isDead()) return;
            equip(p);
        }, delay);
        return true;
    }

    /** Swaps the first totem into the off hand; what was there goes where the totem was. */
    boolean equip(Player p) {
        PlayerInventory inv = p.getInventory();
        if (inv.getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) return false;
        int slot = findTotem(inv);
        if (slot < 0) {
            lang().send(p, "totem.none");
            return false;
        }
        ItemStack totem = inv.getItem(slot);
        ItemStack offhand = inv.getItemInOffHand();
        ItemStack one = totem.clone();
        one.setAmount(1);
        if (totem.getAmount() > 1) {
            // a stack (other plugins allow that): one goes to the off hand, the old off-hand item finds a free place
            totem.setAmount(totem.getAmount() - 1);
            inv.setItem(slot, totem);
            inv.setItemInOffHand(one);
            if (offhand != null && offhand.getType() != Material.AIR) {
                for (ItemStack left : inv.addItem(offhand).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        } else {
            inv.setItem(slot, offhand == null || offhand.getType() == Material.AIR ? null : offhand);
            inv.setItemInOffHand(one);
        }
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_GOLD, 0.7f, 1.3f);
        p.sendActionBar(lang().msg("totem.done"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
