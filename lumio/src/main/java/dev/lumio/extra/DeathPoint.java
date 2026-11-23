package dev.lumio.extra;

import dev.lumio.Config;
import dev.lumio.ui.Cards;
import dev.lumio.ui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * Where you died last: kept per server (or world), shown by the HUD element "Death point" with the
 * distance and an arrow, until you are back there.
 */
public final class DeathPoint {
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static boolean wasDead;

    private DeathPoint() {}

    /** The server or world we are on, so a death point belongs to it. */
    static String where(Minecraft mc) {
        if (mc.getSingleplayerServer() != null) return "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        if (mc.getCurrentServer() != null) return "mp:" + mc.getCurrentServer().ip;
        return "?";
    }

    static String dim(Minecraft mc) {
        if (mc.level == null) return "";
        if (mc.level.dimension() == Level.OVERWORLD) return "overworld";
        if (mc.level.dimension() == Level.NETHER) return "nether";
        if (mc.level.dimension() == Level.END) return "end";
        return "other";
    }

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            wasDead = false;
            return;
        }
        boolean dead = mc.player.isDeadOrDying();
        Config c = Config.get();
        if (dead && !wasDead) {
            Config.DeathSpot d = new Config.DeathSpot();
            d.where = where(mc);
            d.dim = dim(mc);
            d.x = mc.player.getX();
            d.y = mc.player.getY();
            d.z = mc.player.getZ();
            d.at = System.currentTimeMillis();
            c.death = d;
            c.save();
        }
        wasDead = dead;
        // back at the place: done
        Config.DeathSpot d = current(mc);
        if (d != null && !dead && System.currentTimeMillis() - d.at > 3000 && d.dim.equals(dim(mc))
                && mc.player.distanceToSqr(d.x, d.y, d.z) < 4 * 4) {
            c.death = null;
            c.save();
            Cards.toast(Component.translatable("lumio.death.reached.title"), Component.translatable("lumio.death.reached.text"), Draw.SUCCESS, 5);
        }
    }

    /** The death point of this server or world, or null. */
    public static Config.DeathSpot current(Minecraft mc) {
        Config.DeathSpot d = Config.get().death;
        return d != null && d.where.equals(where(mc)) ? d : null;
    }

    /** "↗ 120 m · 312 64 -88", or the dimension it is in. */
    public static Component line(Minecraft mc, Config.DeathSpot d) {
        String pos = Math.round(d.x) + " " + Math.round(d.y) + " " + Math.round(d.z);
        if (mc.player == null || !d.dim.equals(dim(mc))) {
            return Component.translatable("lumio.dim." + d.dim).append(" · " + pos);
        }
        double dx = d.x - mc.player.getX(), dz = d.z - mc.player.getZ();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz + Math.pow(d.y - mc.player.getY(), 2)));
        // the direction to it, seen from where you look (yaw 0 = south, growing to the right)
        float toward = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float rel = Mth.wrapDegrees(toward - mc.player.getYRot());
        String arrow = ARROWS[Math.floorMod(Math.round(rel / 45f), 8)];
        return Component.literal(arrow + " " + dist + " m · " + pos);
    }
}
