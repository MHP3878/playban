package dev.staffcore.module;

import dev.staffcore.StaffCorePlugin;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Tells the team at once when a normal player gets creative or spectator mode, or OP: who got it and,
 * as far as it can be told, who gave it. On request the game mode is taken back right away.
 */
public final class RightsWatch implements Listener {
    private record Issued(long time, String by, String line) {}

    private final StaffCorePlugin plugin;
    private final Deque<Issued> recent = new ArrayDeque<>();

    public RightsWatch(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private static boolean interesting(String line) {
        String l = line.toLowerCase(Locale.ROOT).replaceFirst("^/", "");
        if (l.startsWith("minecraft:")) l = l.substring("minecraft:".length());
        return l.startsWith("gamemode ") || l.startsWith("gm") || l.startsWith("op ") || l.startsWith("essentials:gm") || l.startsWith("creative");
    }

    private void remember(String by, String line) {
        if (!interesting(line)) return;
        long now = System.currentTimeMillis();
        recent.addLast(new Issued(now, by, line));
        while (!recent.isEmpty() && now - recent.peekFirst().time() > 5000) recent.removeFirst();
        // op is plain from the command
        String l = line.toLowerCase(Locale.ROOT).replaceFirst("^/", "").replaceFirst("^minecraft:", "");
        if (l.startsWith("op ")) {
            String target = line.trim().split("\\s+")[1];
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.punishments().notifyStaff(plugin.lang().prefixed("rights.op", "player", target, "by", by));
                plugin.staffLog(by, "op", target, line);
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        remember(e.getPlayer().getName(), e.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent e) {
        remember(e.getSender().getName(), e.getCommand());
    }

    /** Who most likely did it: the newest game mode command naming this player, or the player's own. */
    private String giver(Player p) {
        long now = System.currentTimeMillis();
        Issued best = null;
        for (Issued i : recent) {
            if (now - i.time() > 5000) continue;
            if (i.line().toLowerCase(Locale.ROOT).contains(p.getName().toLowerCase(Locale.ROOT)) || i.by().equals(p.getName())) best = i;
        }
        return best == null ? plugin.lang().raw("rights.unknown") : best.by() + " (" + best.line() + ")";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        GameMode to = e.getNewGameMode();
        if (to != GameMode.CREATIVE && to != GameMode.SPECTATOR) return;
        Player p = e.getPlayer();
        // the team switching itself (admin mode, ghost mode) is no news
        if (p.hasPermission("staffcore.staff") || p.hasPermission("staffcore.admin")) return;
        if (plugin.punishments().inLimbo(p.getUniqueId())) return;
        String by = giver(p);
        plugin.punishments().notifyStaff(plugin.lang().prefixed("rights.gamemode", "player", p.getName(), "mode", to.name().toLowerCase(Locale.ROOT),
                "by", by).clickEvent(ClickEvent.runCommand("/check " + p.getName())));
        plugin.staffLog(by, "gamemode " + to.name().toLowerCase(Locale.ROOT), p.getName(), null);
        if (plugin.getConfig().getBoolean("anticheat.creative-guard", false)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && p.getGameMode() == to) p.setGameMode(GameMode.SURVIVAL);
            });
            plugin.punishments().notifyStaff(plugin.lang().prefixed("rights.reverted", "player", p.getName()));
        }
    }
}
