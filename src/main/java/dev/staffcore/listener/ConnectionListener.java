package dev.staffcore.listener;

import dev.staffcore.StaffCorePlugin;
import dev.staffcore.model.Punishment;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ConnectionListener implements Listener {
    private final StaffCorePlugin plugin;

    public ConnectionListener(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        Punishment ban = plugin.punishments().activeBan(e.getUniqueId());
        if (ban == null) ban = plugin.punishments().ipBanFor(e.getAddress());
        // exempt players are never locked out by an IP ban someone else got
        if (ban != null && !ban.uuid.equals(e.getUniqueId()) && plugin.store().exempt.contains(e.getUniqueId())) ban = null;
        if (ban != null) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, plugin.punishments().banScreen(ban));
            return;
        }
        if (plugin.moderation().lockedOut(e.getUniqueId())) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.moderation().lockdownScreen());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        Punishment p = plugin.punishments().get(player.getUniqueId());
        if (p != null && plugin.getConfig().getBoolean("restrictions.hide-from-players", true)) e.joinMessage(null);
        plugin.voice().joined(player);
        plugin.punishments().restorePending(player);
        boolean exempt = player.hasPermission("staffcore.exempt");
        if (exempt ? plugin.store().exempt.add(player.getUniqueId()) : plugin.store().exempt.remove(player.getUniqueId())) {
            plugin.store().markDirty();
        }
        if (plugin.store().names.containsKey(player.getUniqueId())) plugin.store().names.put(player.getUniqueId(), player.getName());
        if (plugin.punishments().isVanished(player.getUniqueId())) {
            e.joinMessage(null);
            plugin.punishments().reapplyVanish(player);
        }
        if (plugin.punishments().inLimbo(player.getUniqueId())) {
            e.joinMessage(null);
            plugin.punishments().enterLimbo(player);
        }
        // new viewer or newly joined punished player: both need the visibility rules applied
        plugin.punishments().refreshVisibility();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            plugin.punishments().deliverMail(player);
            Punishment now = plugin.punishments().get(player.getUniqueId());
            if (now != null) {
                now.name = player.getName();
                plugin.punishments().applyState(player);
                plugin.punishments().sendStatus(player, now);
            }
            if (plugin.tickets().inChat(player.getUniqueId())) plugin.lang().send(player, "ticket.chat-on");
            for (String other : plugin.store().rememberAddress(player)) {
                plugin.punishments().notifyStaff(plugin.lang().prefixed("staff.same-address", "player", player.getName(), "other", other));
            }
            if (player.hasPermission("staffcore.notify")) {
                int reviews = plugin.store().reviews.size();
                int tickets = plugin.tickets().count(dev.staffcore.model.Ticket.Status.OPEN);
                if (reviews + tickets > 0) plugin.lang().send(player, "staff.pending", "reviews", reviews, "tickets", tickets);
            }
        }, 20L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        if (plugin.punishments().isVanished(player.getUniqueId())) e.quitMessage(null);
        if (plugin.tickets().canReturn(player.getUniqueId())) plugin.tickets().returnBack(player);
        plugin.ticketMenus().closed(player.getUniqueId());
        plugin.voice().quit(player.getUniqueId());

        plugin.tickets().pauseAllOf(player.getUniqueId());
        if (plugin.punishments().inLimbo(player.getUniqueId())) {
            // put everything back so the player is never left in spectator mode if the plugin is removed
            e.quitMessage(null);
            plugin.punishments().leaveLimbo(player, plugin.store().bans.get(player.getUniqueId()));
        }
        if (!plugin.punishments().isPunished(player.getUniqueId())) return;
        if (plugin.getConfig().getBoolean("restrictions.hide-from-players", true)) e.quitMessage(null);
        plugin.punishments().removeBar(player);
        player.setCollidable(true);
        plugin.store().markDirty();
    }
}
