package dev.bastion.ac;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Asks StaffCore (when it is there) whether a player is out of the game right now: in staff mode,
 * vanished, frozen or in prison. Such players are moved by the server or fly on purpose. Also the
 * "vanished" mark that most vanish plugins set.
 */
final class StaffCoreHook {
    private StaffCoreHook() {}

    static boolean exempt(Player p) {
        for (MetadataValue v : p.getMetadata("vanished")) if (v.asBoolean()) return true;
        var sc = Bukkit.getPluginManager().getPlugin("StaffCore");
        if (sc == null || !sc.isEnabled()) return false;
        UUID id = p.getUniqueId();
        return ask(sc, "tools", "inStaffMode", id) || ask(sc, "punishments", "isVanished", id) || ask(sc, "admin", "isRestrained", id)
                || ask(sc, "prison", "isJailed", id) || ask(sc, "punishments", "isPunished", id);
    }

    private static boolean ask(Object plugin, String part, String question, UUID id) {
        try {
            Method get = plugin.getClass().getMethod(part);
            Object o = get.invoke(plugin);
            if (o == null) return false;
            Method m = o.getClass().getMethod(question, UUID.class);
            return Boolean.TRUE.equals(m.invoke(o, id));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }
}
