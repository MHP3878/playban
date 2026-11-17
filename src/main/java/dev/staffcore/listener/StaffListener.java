package dev.staffcore.listener;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import dev.staffcore.StaffCorePlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;

import java.util.List;

/** Keeps vanished staff hidden everywhere, and hides commands players should not see. */
public final class StaffListener implements Listener {
    private final StaffCorePlugin plugin;

    public StaffListener(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private boolean vanished(Player p) {
        return p != null && plugin.punishments().isVanished(p.getUniqueId());
    }

    // ---------------------------------------------------------------- vanish: no name anywhere

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        if (vanished(victim)) {
            e.deathMessage(null);
            return;
        }
        // a kill by a vanished team member must not give their name away
        if (vanished(victim.getKiller()) && e.deathMessage() != null) {
            e.deathMessage(plugin.lang().msg("vanish.death", "player", victim.getName()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        if (vanished(e.getPlayer())) e.message(null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && vanished(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && vanished(p) && !plugin.getConfig().getBoolean("vanish.allow-pickup", false)) {
            e.setCancelled(true);
        }
    }

    /** Vanished staff talk to the team only, unless the message starts with "!". */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!vanished(p) || !plugin.getConfig().getBoolean("vanish.staff-chat-only", true)) return;
        if (plugin.tickets().inChat(p.getUniqueId())) return;
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        if (text.startsWith("!")) {
            e.message(Component.text(text.substring(1).trim()));
            return;
        }
        e.setCancelled(true);
        Component line = plugin.lang().msg("vanish.chat", "player", p.getName(), "text", text);
        for (Player online : Bukkit.getOnlinePlayers()) if (online.hasPermission("staffcore.vanish")) online.sendMessage(line);
    }

    /** The server list does not count or show vanished staff either. */
    @EventHandler
    public void onPing(PaperServerListPingEvent e) {
        int hidden = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (vanished(p)) hidden++;
        if (hidden == 0) return;
        e.getListedPlayers().removeIf(info -> plugin.punishments().isVanished(info.id()));
        e.setNumPlayers(Math.max(0, e.getNumPlayers() - hidden));
    }

    // ---------------------------------------------------------------- hidden commands

    private boolean seesEverything(Player p) {
        return !plugin.getConfig().getBoolean("command-hiding.enabled", true) || p.hasPermission("staffcore.commands.all");
    }

    /** Whether a command name ("pl", "bukkit:plugins", ...) is hidden from normal players. */
    public boolean hidden(String command) {
        String c = command.toLowerCase();
        if (c.startsWith("/")) c = c.substring(1);
        if (c.contains(":") && plugin.getConfig().getBoolean("command-hiding.hide-namespaced", true)) return true;
        if (c.contains(":")) c = c.substring(c.indexOf(':') + 1);
        List<String> list = plugin.getConfig().getStringList("command-hiding.hidden");
        for (String h : list) if (h.equalsIgnoreCase(c)) return true;
        return false;
    }

    /** Removes the commands from the list the game uses for suggestions. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCommandList(PlayerCommandSendEvent e) {
        if (seesEverything(e.getPlayer())) return;
        e.getCommands().removeIf(this::hidden);
    }

    /** And refuses them when typed anyway, with the same answer as for a command that does not exist. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (seesEverything(e.getPlayer())) return;
        String label = e.getMessage().substring(1).split(" ")[0];
        if (!hidden(label)) return;
        e.setCancelled(true);
        plugin.lang().send(e.getPlayer(), "error.unknown-command");
    }
}
