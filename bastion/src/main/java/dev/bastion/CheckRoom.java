package dev.bastion;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The check room: on joining, a player waits a moment in a closed room of bedrock high above (or deep
 * under) the spawn until the check is done (client, mods, the hidden sign, Lumio). Then they are back
 * exactly where they were. Inside nobody can hurt them, they see nobody and nobody sees them, and
 * nothing in the world can be touched.
 *
 * <p>Without Lumio when it is required, or with a forbidden mod, the settings decide: out with a kick
 * screen, or stay in the room with a window that says what to do (download, guide).
 */
public final class CheckRoom implements Listener {
    /** Interior: 5×5 and 3 high. */
    private static final int HALF = 2;
    private static final int HIGH = 3;

    enum State { CHECKING, MISSING_LUMIO, NO_REPORT, OUTDATED, FORBIDDEN }

    static final class Guest {
        final Location back;
        final GameMode mode;
        final long since;
        State state = State.CHECKING;
        long stateSince;
        String mods = "";
        final BossBar bar;

        Guest(Location back, GameMode mode, BossBar bar, long since) {
            this.since = since;
            this.stateSince = since;
            this.back = back;
            this.mode = mode;
            this.bar = bar;
        }
    }

    private final BastionPlugin plugin;
    private final NamespacedKey backKey;
    private final Map<UUID, Guest> guests = new ConcurrentHashMap<>();
    private BukkitTask task;
    /** the time; tests move it on */
    java.util.function.LongSupplier clock = System::currentTimeMillis;

    public CheckRoom(BastionPlugin plugin) {
        this.plugin = plugin;
        // the name from when Bastion was ModCheck: a player who left in the room still finds their way back
        this.backKey = new NamespacedKey("modcheck", "check_room_back");
    }

    private Lang lang() {
        return plugin.lang();
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("check-room.enabled", true);
    }

    /** "stay" (in the room, with a window) or "kick". */
    String onForbidden() {
        return "stay".equalsIgnoreCase(plugin.getConfig().getString("check-room.forbidden", "kick")) ? "stay" : "kick";
    }

    String onMissingLumio() {
        return "kick".equalsIgnoreCase(plugin.getConfig().getString("check-room.missing-lumio", "stay")) ? "kick" : "stay";
    }

    public boolean holds(Player p) {
        return guests.containsKey(p.getUniqueId());
    }

    /** Whether the room takes care of players without Lumio (then Lumio's own kicks wait). */
    public boolean handlesMissingLumio(Player p) {
        return holds(p) && onMissingLumio().equals("stay");
    }

    public void start() {
        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        for (UUID id : List.copyOf(guests.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) release(p, false, true);
        }
    }

    // ---------------------------------------------------------------- where the room is

    /** The middle of the floor of the room (the block the player stands in). */
    Location center() {
        World w = Bukkit.getWorlds().get(0);
        Location spawn = w.getSpawnLocation();
        boolean bottom = "bottom".equalsIgnoreCase(plugin.getConfig().getString("check-room.place", "top"));
        // under the floor there is room for two layers of stone (see build), at the bottom too
        int y = bottom ? w.getMinHeight() + 4 : w.getMaxHeight() - 2 - HIGH;
        return new Location(w, spawn.getBlockX() + 0.5, y, spawn.getBlockZ() + 0.5, spawn.getYaw(), 0);
    }

    /** Builds the room (bedrock all round, light inside) where a block is not as it should be. */
    void build(Location c) {
        World w = c.getWorld();
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        for (int dx = -HALF - 1; dx <= HALF + 1; dx++) {
            for (int dz = -HALF - 1; dz <= HALF + 1; dz++) {
                for (int dy = -3; dy <= HIGH; dy++) {
                    // two layers of stone under the bedrock floor: on Paper before 1.21.9 the hidden sign
                    // check needs a solid block a few blocks under the player, hidden under the floor
                    if (dy < -1) {
                        Block b = w.getBlockAt(cx + dx, cy + dy, cz + dz);
                        if (b.getType() != Material.STONE && !b.getLocation().equals(signSpot(b))) b.setType(Material.STONE, false);
                        continue;
                    }
                    boolean shell = Math.abs(dx) == HALF + 1 || Math.abs(dz) == HALF + 1 || dy == -1 || dy == HIGH;
                    Material want = shell ? Material.BEDROCK
                            : dy == HIGH - 1 && Math.abs(dx) == HALF && Math.abs(dz) == HALF ? Material.LIGHT : Material.AIR;
                    Block b = w.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (b.getType() != want) b.setType(want, false);
                }
            }
        }
    }

    /** A block that is a sign of the check right now must not be turned back into stone. */
    private Location signSpot(Block b) {
        return b.getType().name().endsWith("_SIGN") ? b.getLocation() : null;
    }

    private boolean inside(Location l) {
        Location c = center();
        return l.getWorld() != null && l.getWorld().equals(c.getWorld()) && Math.abs(l.getX() - c.getX()) <= HALF + 1
                && Math.abs(l.getZ() - c.getZ()) <= HALF + 1 && l.getY() >= c.getY() - 1 && l.getY() <= c.getY() + HIGH;
    }

    // ---------------------------------------------------------------- in and out

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // nobody sees the players still being checked
        for (UUID id : guests.keySet()) {
            Player other = Bukkit.getPlayer(id);
            if (other != null && !other.equals(p)) p.hidePlayer(plugin, other);
        }
        Location saved = savedBack(p);
        if (!enabled() || p.hasPermission("bastion.bypass") || plugin.lumio().bedrock(p)) {
            // the room was switched off while this player was in it: back to where they were
            if (saved != null) {
                p.getPersistentDataContainer().remove(backKey);
                p.teleport(saved, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
            return;
        }
        enter(p, saved != null ? saved : p.getLocation());
    }

    void enter(Player p, Location back) {
        Location c = center();
        build(c);
        p.getPersistentDataContainer().set(backKey, PersistentDataType.STRING, write(back));
        BossBar bar = BossBar.bossBar(lang().msg("room.bar-checking", "seconds", 6), 0f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
        Guest g = new Guest(back, p.getGameMode(), bar, clock.getAsLong());
        guests.put(p.getUniqueId(), g);
        // a spectator would fly through the walls
        if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.ADVENTURE);
        p.teleport(c, PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.setFallDistance(0);
        for (Player other : Bukkit.getOnlinePlayers()) if (!other.equals(p)) other.hidePlayer(plugin, p);
        p.showBossBar(bar);
        p.showTitle(Title.title(lang().msg("room.enter-title"), lang().msg("room.enter-subtitle"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(400))));
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.7f, 1.4f);
    }

    /** Back to where the player was; with a "well done" when the check went through. */
    void release(Player p, boolean welcome, boolean now) {
        Guest g = guests.remove(p.getUniqueId());
        if (g == null) return;
        p.hideBossBar(g.bar);
        p.getPersistentDataContainer().remove(backKey);
        if (p.getGameMode() != g.mode) p.setGameMode(g.mode);
        Location back = g.back.getWorld() == null ? Bukkit.getWorlds().get(0).getSpawnLocation() : g.back;
        p.teleport(back, PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.setFallDistance(0);
        for (Player other : Bukkit.getOnlinePlayers()) if (!other.equals(p) && !holds(other)) other.showPlayer(plugin, p);
        // what the player could not see while inside
        for (Player other : Bukkit.getOnlinePlayers()) if (!other.equals(p) && !holds(other)) p.showPlayer(plugin, other);
        if (welcome) {
            p.showTitle(Title.title(lang().msg("room.done-title"), lang().msg("room.done-subtitle"),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1500), Duration.ofMillis(500))));
            p.playSound(back, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Guest g = guests.remove(p.getUniqueId());
        if (g == null) return;
        // the server may still save the room as the player's place: the note of where they really were
        // stays, so the next join brings them back there (and not to the room)
        if (g.back.getWorld() != null) p.teleport(g.back, PlayerTeleportEvent.TeleportCause.PLUGIN);
        if (p.getGameMode() != g.mode) p.setGameMode(g.mode);
        for (Player other : Bukkit.getOnlinePlayers()) other.showPlayer(plugin, p);
    }

    private Location savedBack(Player p) {
        String s = p.getPersistentDataContainer().get(backKey, PersistentDataType.STRING);
        return s == null ? null : read(s);
    }

    static String write(Location l) {
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
    }

    static Location read(String s) {
        try {
            String[] f = s.split(";");
            World w = Bukkit.getWorld(f[0]);
            if (w == null) return null;
            return new Location(w, Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3]),
                    Float.parseFloat(f[4]), Float.parseFloat(f[5]));
        } catch (RuntimeException broken) {
            return null;
        }
    }

    // ---------------------------------------------------------------- the check

    void tick() {
        for (Map.Entry<UUID, Guest> en : List.copyOf(guests.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) {
                guests.remove(en.getKey());
                continue;
            }
            Guest g = en.getValue();
            if (!inside(p.getLocation())) p.teleport(center(), PlayerTeleportEvent.TeleportCause.PLUGIN);
            long age = clock.getAsLong() - g.since;
            if (g.state != State.CHECKING) {
                long stay = Math.max(1, plugin.getConfig().getLong("check-room.stay-minutes", 5)) * 60_000L;
                if (clock.getAsLong() - g.stateSince > stay) {
                    String key = switch (g.state) {
                        case FORBIDDEN -> null;
                        case NO_REPORT -> "lumio.kick-no-report";
                        case OUTDATED -> "lumio.kick-outdated";
                        default -> "lumio.kick-missing";
                    };
                    if (key == null) p.kick(plugin.guard().forbiddenScreen(null, g.mods));
                    else plugin.lumio().kick(p, key);
                }
                continue;
            }
            float expect = 6000f;
            g.bar.progress(Math.min(1f, age / expect));
            g.bar.name(lang().msg("room.bar-checking", "seconds", Math.max(0, (int) Math.ceil((expect - age) / 1000.0))));
            String verdict = verdict(p, age);
            switch (verdict) {
                case "ok" -> release(p, true, false);
                case "missing" -> stuck(p, g, State.MISSING_LUMIO, "");
                case "no-report" -> stuck(p, g, State.NO_REPORT, "");
                case "outdated" -> stuck(p, g, State.OUTDATED, "");
                default -> {
                    // still waiting
                }
            }
        }
    }

    /** "ok", "wait", "missing" (no Lumio although required), "no-report" or "outdated". */
    String verdict(Player p, long age) {
        long max = Math.max(5, plugin.getConfig().getLong("check-room.max-seconds", 20)) * 1000L;
        Guard.Info info = plugin.guard().info(p.getUniqueId());
        // the client name and channels are judged three seconds after joining
        boolean judged = age >= 3500;
        // the hidden sign starts a moment after the client shows the world: done once it has an outcome
        boolean signDone = !plugin.getConfig().getBoolean("sign-check", true)
                || info.joinChecked && !plugin.guard().probing(p.getUniqueId()) && !"unknown".equals(info.sign);
        String mode = plugin.lumio().mode();
        boolean hasLumio = p.getListeningPluginChannels().contains(Lumio.HELLO);
        Lumio.Report r = info.lumio;
        boolean lumioDone;
        if (mode.equals("off")) lumioDone = true;
        else if (hasLumio) {
            // Lumio 0.6+ also says what it found in the game; that answer is part of the check
            boolean needGame = plugin.lumio().injectionCheck() && Lumio.checksGame(r);
            lumioDone = r != null && r.complete && (!needGame || info.integrityAt > 0);
        }
        else lumioDone = age >= 2500;
        if (mode.equals("required")) {
            if (!hasLumio && age >= 2500) return "missing";
            long wait = Math.max(5, plugin.getConfig().getInt("lumio.wait-seconds", 15)) * 1000L;
            if (hasLumio && !lumioDone && age >= wait) return "no-report";
            if (hasLumio && lumioDone && plugin.lumio().outdated(r)) return "outdated";
        }
        if (judged && signDone && lumioDone) return "ok";
        if (age >= max && (lumioDone || !mode.equals("required"))) return "ok";
        return "wait";
    }

    private void stuck(Player p, Guest g, State state, String mods) {
        if (state != State.FORBIDDEN && onMissingLumio().equals("kick")) {
            plugin.lumio().kick(p, switch (state) {
                case NO_REPORT -> "lumio.kick-no-report";
                case OUTDATED -> "lumio.kick-outdated";
                default -> "lumio.kick-missing";
            });
            return;
        }
        g.state = state;
        g.stateSince = clock.getAsLong();
        g.mods = mods;
        g.bar.progress(1f);
        g.bar.color(BossBar.Color.RED);
        g.bar.name(lang().msg(state == State.FORBIDDEN ? "room.bar-forbidden" : "room.bar-lumio", "mods", mods));
        showWindow(p);
    }

    /** A forbidden mod while in the room and "stay" is set: the player waits here with a window. */
    public boolean keepForbidden(Player p, String mods) {
        Guest g = guests.get(p.getUniqueId());
        if (g == null || !onForbidden().equals("stay")) return false;
        stuck(p, g, State.FORBIDDEN, mods);
        return true;
    }

    /** Shows the window that fits: Lumio missing, no answer, too old, or a forbidden mod. */
    public void showWindow(Player p) {
        Guest g = guests.get(p.getUniqueId());
        if (g == null) return;
        switch (g.state) {
            case FORBIDDEN -> plugin.popups().forbidden(p, g.mods);
            case NO_REPORT -> plugin.popups().lumio(p, "noreport");
            case OUTDATED -> plugin.popups().lumio(p, "outdated");
            case MISSING_LUMIO -> plugin.popups().lumio(p, "missing");
            default -> {
            }
        }
    }

    /** /mcroom: leave (from the window), guide, or the window again. Outside the room (a preview) leave just closes it. */
    public void command(Player p, String what) {
        switch (what.toLowerCase(Locale.ROOT)) {
            case "guide" -> plugin.popups().guide(p);
            case "window" -> {
                if (holds(p)) showWindow(p);
                else plugin.popups().lumio(p, "missing");
            }
            default -> {
                if (holds(p)) p.kick(lang().msg("room.leave-screen"));
                else plugin.popups().close(p);
            }
        }
    }

    // ---------------------------------------------------------------- nothing happens inside

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && holds(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && holds(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (holds(e.getPlayer()) || inside(e.getBlock().getLocation().add(0.5, 0, 0.5)) && !guests.isEmpty()) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (holds(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (holds(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (holds(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!holds(e.getPlayer())) return;
        var cause = e.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.PLUGIN && cause != PlayerTeleportEvent.TeleportCause.UNKNOWN) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (holds(e.getPlayer()) && !inside(e.getTo())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!holds(e.getPlayer())) return;
        String cmd = e.getMessage().toLowerCase(Locale.ROOT);
        if (cmd.startsWith("/mcroom")) return;
        e.setCancelled(true);
        lang().send(e.getPlayer(), "room.wait");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent e) {
        if (!holds(e.getPlayer())) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(lang().prefixed("room.wait"));
    }

    /** For tests and the menu: the players in the room. */
    int count() {
        return guests.size();
    }

    Component state(Player p) {
        Guest g = guests.get(p.getUniqueId());
        return g == null ? Component.empty() : Component.text(g.state.name());
    }

    State stateOf(Player p) {
        Guest g = guests.get(p.getUniqueId());
        return g == null ? null : g.state;
    }
}
