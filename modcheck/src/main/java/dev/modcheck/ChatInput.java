package dev.modcheck;

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

/** Asks for one line in chat: the next message goes to the callback, "cancel" or "abbrechen" stops. */
public final class ChatInput implements Listener {
    private record Ask(Consumer<String> done, Runnable cancelled, long until) {}

    /** An answer must come within two minutes: a chat message much later is just chat. */
    private static final long TIMEOUT = 120_000L;

    private final ModCheckPlugin plugin;
    private final Map<UUID, Ask> asks = new ConcurrentHashMap<>();

    public ChatInput(ModCheckPlugin plugin) {
        this.plugin = plugin;
    }

    public void ask(Player p, String promptKey, Consumer<String> done, Runnable cancelled, Object... args) {
        p.closeInventory();
        asks.put(p.getUniqueId(), new Ask(done, cancelled, System.currentTimeMillis() + TIMEOUT));
        plugin.lang().send(p, promptKey, args);
        plugin.lang().send(p, "input.cancel-hint");
    }

    /** True while this player's next chat line goes to a question. */
    public boolean waitingFor(UUID id) {
        Ask a = asks.get(id);
        return a != null && System.currentTimeMillis() <= a.until();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Ask a = asks.remove(e.getPlayer().getUniqueId());
        if (a == null || System.currentTimeMillis() > a.until()) return;
        e.setCancelled(true);
        String text = Guard.clean(PlainTextComponentSerializer.plainText().serialize(e.message()), 256);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("abbrechen")) {
                if (a.cancelled() != null) a.cancelled().run();
            } else a.done().accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        asks.remove(e.getPlayer().getUniqueId());
    }
}
