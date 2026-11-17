package dev.staffcore.module;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /lag shows how the server is doing and where it hurts; /cleanup removes items lying around. */
public final class LagTools implements TabExecutor {
    private final StaffCorePlugin plugin;

    public LagTools(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private record Hot(World world, int x, int z, int entities, int tiles) {}

    public void report(CommandSender s) {
        double tps = 20;
        double mspt = 0;
        try {
            tps = Math.min(20, Bukkit.getTPS()[0]);
            mspt = Bukkit.getAverageTickTime();
        } catch (RuntimeException | NoSuchMethodError ignored) {
            // older API
        }
        int entities = 0, chunks = 0;
        List<Hot> hot = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) {
                chunks++;
                int e = c.getEntities().length;
                int t = 0;
                try {
                    t = c.getTileEntities().length;
                } catch (RuntimeException ignored) {
                    // a server without tile entity lists
                }
                entities += e;
                if (e + t > 0) hot.add(new Hot(w, c.getX(), c.getZ(), e, t));
            }
        }
        hot.sort((a, b) -> Integer.compare(b.entities() + b.tiles() / 4, a.entities() + a.tiles() / 4));
        String state = tps >= 19 ? "lag.good" : tps >= 16 ? "lag.ok" : "lag.bad";
        lang().send(s, "lag.header", "tps", String.format(Locale.ROOT, "%.1f", tps), "state", lang().raw(state), "mspt", String.format(Locale.ROOT, "%.1f", mspt),
                "chunks", chunks, "entities", entities, "players", Bukkit.getOnlinePlayers().size());
        for (int i = 0; i < Math.min(5, hot.size()); i++) {
            Hot h = hot.get(i);
            int bx = h.x() * 16 + 8, bz = h.z() * 16 + 8;
            int by = h.world().getHighestBlockYAt(bx, bz) + 1;
            s.sendMessage(lang().msg("lag.chunk", "world", h.world().getName(), "x", bx, "z", bz, "entities", h.entities(), "tiles", h.tiles())
                    .clickEvent(ClickEvent.runCommand("/lag tp " + h.world().getName() + " " + bx + " " + by + " " + bz)));
        }
    }

    public int cleanup(boolean everything) {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                boolean junk = e instanceof Item || e instanceof ExperienceOrb
                        || (e instanceof AbstractArrow a && (a.isInBlock() || a.getPickupStatus() != AbstractArrow.PickupStatus.ALLOWED));
                if (junk && (everything || e.getTicksLived() > 20 * 30)) {
                    e.remove();
                    n++;
                }
            }
        }
        return n;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("cleanup")) {
            int n = cleanup(args.length > 0 && args[0].equalsIgnoreCase("all"));
            lang().send(sender, "lag.cleaned", "count", n);
            plugin.staffLog(sender.getName(), "cleanup", null, String.valueOf(n));
            return true;
        }
        if (args.length >= 5 && args[0].equalsIgnoreCase("tp") && sender instanceof Player p) {
            World w = Bukkit.getWorld(args[1]);
            try {
                if (w != null) plugin.admin().tpAdminTo(p, new org.bukkit.Location(w, Double.parseDouble(args[2]) + 0.5,
                        Double.parseDouble(args[3]), Double.parseDouble(args[4]) + 0.5));
            } catch (NumberFormatException ignored) {
                // a broken link
            }
            return true;
        }
        report(sender);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("cleanup") && args.length == 1) return "all".startsWith(args[0].toLowerCase()) ? List.of("all") : List.of();
        return List.of();
    }
}
