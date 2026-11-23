package dev.staffcore.module;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.List;

/** Messages that go round on their own (announcer.messages), and /announce for one right now with a title. */
public final class Announcer implements TabExecutor {
    private final StaffCorePlugin plugin;
    private BukkitTask task;
    private int next;
    private long lastSent = System.currentTimeMillis();

    public Announcer(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    public void start() {
        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    private void tick() {
        if (!plugin.modules().on("announcer")) return;
        long every = Math.max(30, plugin.getConfig().getLong("announcer.interval-seconds", 300)) * 1000L;
        if (System.currentTimeMillis() - lastSent < every || Bukkit.getOnlinePlayers().isEmpty()) return;
        sendNext();
    }

    /** The next message of the list (or a random one) to everybody. Returns false when the list is empty. */
    public boolean sendNext() {
        List<String> messages = plugin.getConfig().getStringList("announcer.messages");
        if (messages.isEmpty()) return false;
        lastSent = System.currentTimeMillis();
        String raw = plugin.getConfig().getBoolean("announcer.random", false)
                ? messages.get(new java.util.Random().nextInt(messages.size())) : messages.get(next++ % messages.size());
        Component line = lang().msg("announce.prefix").append(MiniMessage.miniMessage().deserialize(raw));
        String title = lang().plain("announce.title");
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(raw));
        boolean cards = plugin.getConfig().getBoolean("lumio.cards", true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            // Lumio shows it as a small card (with its own sound) instead of a chat line
            if (cards && dev.staffcore.LumioBridge.has(p)) {
                dev.staffcore.LumioBridge.toast(p, title, plain, "info", 8);
                continue;
            }
            p.sendMessage(line);
            if (plugin.getConfig().getBoolean("announcer.sound", true)) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1.4f);
        }
        Bukkit.getConsoleSender().sendMessage(line);
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            lang().send(sender, "announce.usage");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("next")) {
            if (!sendNext()) lang().send(sender, "announce.empty");
            return true;
        }
        // what the team writes may use colors, but no click or hover actions
        String text = String.join(" ", args);
        Component body = MiniMessage.builder().tags(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
                net.kyori.adventure.text.minimessage.tag.standard.StandardTags.color(),
                net.kyori.adventure.text.minimessage.tag.standard.StandardTags.decorations(),
                net.kyori.adventure.text.minimessage.tag.standard.StandardTags.gradient(),
                net.kyori.adventure.text.minimessage.tag.standard.StandardTags.rainbow())).build().deserialize(text);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(lang().msg("announce.title"), body, Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(700))));
            p.sendMessage(lang().msg("announce.prefix").append(body));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
        }
        Bukkit.getConsoleSender().sendMessage(lang().msg("announce.prefix").append(body));
        plugin.staffLog(sender.getName(), "announce", null, text);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 && "next".startsWith(args[0].toLowerCase()) ? List.of("next") : List.of();
    }
}
