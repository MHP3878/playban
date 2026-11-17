package dev.staffcore;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Lets a menu ask the player to type something into chat; the answer never reaches the public chat. */
public final class ChatInput implements Listener {
    private record Session(Consumer<String> answer, Runnable cancelled, long expires) {}

    private static final long TIMEOUT_MS = 120_000;
    private final StaffCorePlugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public ChatInput(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void ask(Player player, String promptKey, Consumer<String> answer, Runnable cancelled, Object... kv) {
        player.closeInventory();
        sessions.put(player.getUniqueId(), new Session(answer, cancelled, System.currentTimeMillis() + TIMEOUT_MS));
        plugin.lang().send(player, promptKey, kv);
        plugin.lang().send(player, "prompt.cancel-hint");
    }

    public boolean waiting(UUID id) {
        Session s = sessions.get(id);
        if (s != null && s.expires < System.currentTimeMillis()) {
            sessions.remove(id);
            return false;
        }
        return s != null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!waiting(player.getUniqueId())) return;
        Session s = sessions.remove(player.getUniqueId());
        if (s == null) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (text.equalsIgnoreCase("cancel") || text.isEmpty()) {
                plugin.lang().send(player, "prompt.cancelled");
                if (s.cancelled != null) s.cancelled.run();
            } else {
                s.answer.accept(text);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }
}
