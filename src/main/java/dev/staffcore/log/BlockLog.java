package dev.staffcore.log;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import dev.staffcore.TimeUtil;
import dev.staffcore.log.LogDatabase.Action;
import dev.staffcore.log.LogDatabase.Entry;
import dev.staffcore.log.LogDatabase.Query;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Who broke, placed or blew up which block and who took what out of which chest.
 * Look it up, roll it back, or watch it play back like a video.
 */
public final class BlockLog implements Listener, TabExecutor {
    private static final int PAGE = 10;
    private final StaffCorePlugin plugin;
    private LogDatabase db;
    private BukkitTask flusher;
    /** container contents when they were opened, per viewer */
    private final Map<UUID, Snapshot> opened = new HashMap<>();
    /** the last lookup of each staff member, for paging */
    private final Map<String, List<Entry>> results = new HashMap<>();
    private final Map<UUID, Replay> replays = new HashMap<>();

    private record Snapshot(Location at, ItemStack[] contents) {}

    public BlockLog(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("block-log.enabled", true);
    }

    public CompletableFuture<Boolean> start() {
        db = new LogDatabase(new File(plugin.getDataFolder(), "blocklog.db"), plugin.getLogger());
        flusher = Bukkit.getScheduler().runTaskTimer(plugin, () -> db.flush(), 40L, 40L);
        return db.open(plugin.getConfig().getInt("block-log.keep-days", 30));
    }

    public void stop() {
        if (flusher != null) flusher.cancel();
        for (UUID id : new ArrayList<>(replays.keySet())) stopReplay(id);
        if (db != null) db.close();
    }

    public LogDatabase db() {
        return db;
    }

    // ---------------------------------------------------------------- recording

    private void block(Block b, Player p, String actor, String name, Action action, BlockData before, BlockData after) {
        if (db == null || !enabled()) return;
        String who = p != null ? p.getUniqueId().toString() : actor;
        String whoName = p != null ? p.getName() : name;
        db.add(new Entry(0, System.currentTimeMillis(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), who, whoName, action,
                before == null ? null : before.getAsString(), after == null ? null : after.getAsString(), 0, false));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        block(e.getBlock(), e.getPlayer(), null, null, Action.BREAK, e.getBlock().getBlockData(), Material.AIR.createBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        block(e.getBlock(), e.getPlayer(), null, null, Action.PLACE, e.getBlockReplacedState().getBlockData(), e.getBlockPlaced().getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Material liquid = e.getBucket() == Material.LAVA_BUCKET ? Material.LAVA : Material.WATER;
        block(e.getBlock(), e.getPlayer(), null, null, Action.PLACE, e.getBlock().getBlockData(), liquid.createBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        block(e.getBlock(), e.getPlayer(), null, null, Action.BREAK, e.getBlock().getBlockData(), Material.AIR.createBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        block(e.getBlock(), null, "#fire", "#fire", Action.BURN, e.getBlock().getBlockData(), Material.AIR.createBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        Entity cause = e.getEntity();
        Player who = null;
        // a lit TNT remembers who lit it, so the griefer is named, not just "tnt"
        if (cause instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) who = p;
        String actor = "#" + cause.getType().name().toLowerCase();
        for (Block b : e.blockList()) {
            if (b.getType().isAir()) continue;
            block(b, who, actor, actor, Action.EXPLODE, b.getBlockData(), Material.AIR.createBlockData());
        }
    }

    private static Location containerLocation(InventoryHolder holder) {
        if (holder instanceof DoubleChest dc && dc.getLeftSide() instanceof Container left) return left.getLocation();
        if (holder instanceof Container c) return c.getLocation();
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !enabled()) return;
        Location at = containerLocation(e.getInventory().getHolder());
        if (at == null) return;
        ItemStack[] copy = new ItemStack[e.getInventory().getSize()];
        for (int i = 0; i < copy.length; i++) {
            ItemStack it = e.getInventory().getItem(i);
            copy[i] = it == null ? null : it.clone();
        }
        opened.put(p.getUniqueId(), new Snapshot(at, copy));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        Snapshot before = opened.remove(e.getPlayer().getUniqueId());
        if (before == null || !(e.getPlayer() instanceof Player p)) return;
        logDifference(p, before.at(), before.contents(), e.getInventory());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        opened.remove(e.getPlayer().getUniqueId());
        stopReplay(e.getPlayer().getUniqueId());
    }

    /** Compares the chest before and after and logs what was taken out and put in. */
    private void logDifference(Player p, Location at, ItemStack[] before, Inventory after) {
        Map<ItemStack, Integer> diff = new LinkedHashMap<>();
        java.util.function.BiConsumer<ItemStack, Integer> count = (item, sign) -> {
            if (item == null || item.getType().isAir()) return;
            ItemStack key = item.asOne();
            for (ItemStack k : diff.keySet()) {
                if (k.isSimilar(key)) {
                    diff.merge(k, sign * item.getAmount(), Integer::sum);
                    return;
                }
            }
            diff.put(key, sign * item.getAmount());
        };
        for (ItemStack it : after.getContents()) count.accept(it, 1);
        for (ItemStack it : before) count.accept(it, -1);
        for (Map.Entry<ItemStack, Integer> d : diff.entrySet()) {
            int n = d.getValue();
            if (n == 0) continue;
            String item = Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(new ItemStack[]{d.getKey()}));
            db.add(new Entry(0, System.currentTimeMillis(), at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                    p.getUniqueId().toString(), p.getName(), n > 0 ? Action.PUT : Action.TAKE, item, d.getKey().getType().name(), Math.abs(n), false));
        }
    }

    // ---------------------------------------------------------------- showing entries

    private static String pretty(String data) {
        if (data == null) return "?";
        String name = data;
        int bracket = name.indexOf('[');
        if (bracket > 0) name = name.substring(0, bracket);
        if (name.startsWith("minecraft:")) name = name.substring(10);
        StringBuilder sb = new StringBuilder();
        for (String w : name.split("_")) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase()).append(' ');
        }
        return sb.toString().trim();
    }

    private String actorName(Entry e) {
        if (!e.actorName().startsWith("#")) return e.actorName();
        String key = "log.actor." + e.actorName().substring(1);
        return lang().has(key) ? lang().raw(key) : pretty(e.actorName().substring(1));
    }

    /** "Diamond Ore" for blocks, "12x Diamond" for items. */
    private String what(Entry e) {
        if (e.isItem()) return e.amount() + "x " + pretty(e.after());
        return pretty(e.action() == Action.PLACE ? e.after() : e.before());
    }

    public Component line(Entry e) {
        String ago = TimeUtil.format(Math.max(1000, System.currentTimeMillis() - e.time()));
        Component line = lang().msg(e.rolledBack() ? "log.line-rolled" : "log.line", "ago", ago, "player", actorName(e),
                "action", lang().raw("log.action." + e.action().name()), "what", what(e), "x", e.x(), "y", e.y(), "z", e.z());
        return line.hoverEvent(HoverEvent.showText(lang().msg("log.hover", "world", e.world())))
                .clickEvent(ClickEvent.runCommand("/blocklog tp " + e.world() + " " + e.x() + " " + e.y() + " " + e.z()));
    }

    private void show(CommandSender to, List<Entry> list, int page) {
        if (list.isEmpty()) {
            lang().send(to, "log.nothing");
            return;
        }
        int pages = (list.size() + PAGE - 1) / PAGE;
        int p = Math.max(1, Math.min(page, pages));
        lang().send(to, "log.header", "count", list.size(), "page", p, "pages", pages);
        for (int i = (p - 1) * PAGE; i < Math.min(list.size(), p * PAGE); i++) to.sendMessage(line(list.get(i)));
        if (p < pages) {
            to.sendMessage(lang().msg("log.more", "next", p + 1).clickEvent(ClickEvent.runCommand("/blocklog page " + (p + 1))));
        }
    }

    /** The changes at one block, for the inspector brush. */
    public void inspect(Player staff, Block b) {
        Query q = new Query();
        q.world = b.getWorld().getName();
        q.x = b.getX();
        q.y = b.getY();
        q.z = b.getZ();
        q.exact = true;
        q.limit = 50;
        db.find(q).thenAccept(list -> Bukkit.getScheduler().runTask(plugin, () -> {
            results.put(staff.getName(), list);
            lang().send(staff, "log.at", "x", b.getX(), "y", b.getY(), "z", b.getZ());
            show(staff, list, 1);
        }));
    }

    // ---------------------------------------------------------------- rollback and restore

    /** Undoes (rollback) or redoes (restore) the changes, newest first for a rollback. */
    public CompletableFuture<Integer> rollback(Query q, boolean restore) {
        q.rolledBack = restore;
        q.oldestFirst = restore;
        q.limit = 50_000;
        CompletableFuture<Integer> done = new CompletableFuture<>();
        db.find(q).thenAccept(list -> Bukkit.getScheduler().runTask(plugin, () -> {
            List<Long> ids = new ArrayList<>();
            for (Entry e : list) {
                World w = Bukkit.getWorld(e.world());
                if (w == null) continue;
                Block b = w.getBlockAt(e.x(), e.y(), e.z());
                try {
                    if (e.isItem()) {
                        if (!applyItem(b, e, restore)) continue;
                    } else {
                        String data = restore ? e.after() : e.before();
                        b.setBlockData(Bukkit.createBlockData(data == null ? "minecraft:air" : data), false);
                    }
                    ids.add(e.id());
                } catch (IllegalArgumentException ex) {
                    // a block type this version does not know; leave it
                }
            }
            db.markRolled(ids, !restore).thenRun(() -> done.complete(ids.size()));
        }));
        return done;
    }

    /** Puts taken items back into the chest, or takes put-in items out again. */
    private boolean applyItem(Block b, Entry e, boolean restore) {
        BlockState state = b.getState();
        if (!(state instanceof Container c)) return false;
        ItemStack item = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(e.before()))[0];
        item.setAmount(e.amount());
        boolean giveBack = (e.action() == Action.TAKE) != restore;
        if (giveBack) c.getInventory().addItem(item);
        else c.getInventory().removeItem(item);
        return true;
    }

    // ---------------------------------------------------------------- replay

    /** Plays changes back like a video, only for one staff member: blocks change and names float above them. */
    private final class Replay {
        final Player viewer;
        final List<Entry> entries;
        final Map<Location, BlockData> real = new LinkedHashMap<>();
        final BossBar bar;
        final List<Entity> labels = new ArrayList<>();
        BukkitTask task;
        int index;
        double speed = 1;
        double progress;
        boolean paused;

        Replay(Player viewer, List<Entry> entries) {
            this.viewer = viewer;
            this.entries = entries;
            this.bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
        }

        void begin() {
            // first show the area as it was before the first change
            for (Entry e : entries) {
                if (e.isItem()) continue;
                World w = Bukkit.getWorld(e.world());
                if (w == null) continue;
                Location at = new Location(w, e.x(), e.y(), e.z());
                if (real.containsKey(at)) continue;
                real.put(at, at.getBlock().getBlockData());
                viewer.sendBlockChange(at, Bukkit.createBlockData(e.before() == null ? "minecraft:air" : e.before()));
            }
            viewer.showBossBar(bar);
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L);
            sendControls();
            update();
        }

        void tick() {
            if (paused) return;
            progress += speed / 5.0; // one change every 10 ticks at 1x
            while (progress >= 1 && index < entries.size()) {
                progress -= 1;
                play(entries.get(index++));
            }
            update();
            if (index >= entries.size()) {
                paused = true;
                lang().send(viewer, "log.replay-end");
            }
        }

        void play(Entry e) {
            World w = Bukkit.getWorld(e.world());
            if (w == null) return;
            Location at = new Location(w, e.x(), e.y(), e.z());
            if (!e.isItem()) viewer.sendBlockChange(at, Bukkit.createBlockData(e.after() == null ? "minecraft:air" : e.after()));
            viewer.playSound(at, e.action() == Action.PLACE ? Sound.BLOCK_STONE_PLACE : e.isItem() ? Sound.BLOCK_CHEST_OPEN : Sound.BLOCK_STONE_BREAK, 0.6f, 1f);
            try {
                w.spawnParticle(e.action() == Action.EXPLODE ? Particle.EXPLOSION : Particle.WAX_OFF, at.clone().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, 0);
                TextDisplay label = w.spawn(at.clone().add(0.5, 1.4, 0.5), TextDisplay.class, t -> {
                    t.text(lang().msg("log.replay-label", "player", actorName(e), "action", lang().raw("log.action." + e.action().name()), "what", what(e)));
                    t.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                    t.setVisibleByDefault(false);
                    t.setPersistent(false);
                });
                viewer.showEntity(plugin, label);
                labels.add(label);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    label.remove();
                    labels.remove(label);
                }, 60L);
            } catch (RuntimeException ignored) {
                // only decoration, the block change is what matters
            }
        }

        void update() {
            Entry current = index == 0 ? entries.get(0) : entries.get(Math.min(index, entries.size()) - 1);
            String ago = TimeUtil.format(Math.max(1000, System.currentTimeMillis() - current.time()));
            bar.name(lang().msg(paused ? "log.replay-bar-paused" : "log.replay-bar", "n", index, "total", entries.size(),
                    "ago", ago, "speed", speed == Math.floor(speed) ? String.valueOf((int) speed) : String.valueOf(speed)));
            bar.progress(entries.isEmpty() ? 1f : Math.min(1f, (float) index / entries.size()));
        }

        void sendControls() {
            viewer.sendMessage(lang().msg("log.replay-controls")
                    .append(lang().msg("log.replay-pause").clickEvent(ClickEvent.runCommand("/blocklog pause")))
                    .append(Component.text(" "))
                    .append(lang().msg("log.replay-slower").clickEvent(ClickEvent.runCommand("/blocklog slower")))
                    .append(Component.text(" "))
                    .append(lang().msg("log.replay-faster").clickEvent(ClickEvent.runCommand("/blocklog faster")))
                    .append(Component.text(" "))
                    .append(lang().msg("log.replay-stop").clickEvent(ClickEvent.runCommand("/blocklog stop"))));
        }

        void end() {
            if (task != null) task.cancel();
            viewer.hideBossBar(bar);
            labels.forEach(Entity::remove);
            // show the real world again
            for (Map.Entry<Location, BlockData> r : real.entrySet()) viewer.sendBlockChange(r.getKey(), r.getKey().getBlock().getBlockData());
        }
    }

    public void startReplay(Player viewer, Query q) {
        stopReplay(viewer.getUniqueId());
        q.oldestFirst = true;
        q.limit = 3000;
        db.find(q).thenAccept(list -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline()) return;
            if (list.isEmpty()) {
                lang().send(viewer, "log.nothing");
                return;
            }
            Replay r = new Replay(viewer, list);
            replays.put(viewer.getUniqueId(), r);
            lang().send(viewer, "log.replay-start", "count", list.size());
            r.begin();
        }));
    }

    public boolean replaying(UUID id) {
        return replays.containsKey(id);
    }

    public void stopReplay(UUID id) {
        Replay r = replays.remove(id);
        if (r != null) r.end();
    }

    // ---------------------------------------------------------------- command

    /** Parses "player:Steve time:2h radius:20" style words; plain words also work: a name, a time, a number. */
    private Query parse(CommandSender sender, String[] args, int from, int defaultRadius, long defaultTime) {
        Query q = new Query();
        q.radius = defaultRadius;
        q.since = System.currentTimeMillis() - defaultTime;
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            String value = a.contains(":") ? a.substring(a.indexOf(':') + 1) : a;
            String kind = a.contains(":") ? a.substring(0, a.indexOf(':')).toLowerCase() : "";
            if (kind.startsWith("r") || (kind.isEmpty() && value.matches("\\d+"))) {
                q.radius = Math.min(plugin.getConfig().getInt("block-log.max-radius", 100), Integer.parseInt(value.replaceAll("\\D", "0")));
            } else if (kind.startsWith("t") || (kind.isEmpty() && TimeUtil.parse(value) > 0 && !value.matches("\\d+"))) {
                long ms = TimeUtil.parse(value);
                if (ms > 0) q.since = System.currentTimeMillis() - ms;
            } else {
                var who = Bukkit.getOfflinePlayerIfCached(value);
                q.actor = who != null ? who.getUniqueId().toString() : (value.startsWith("#") ? value.toLowerCase() : value);
            }
        }
        if (sender instanceof Player p) {
            q.world = p.getWorld().getName();
            q.x = p.getLocation().getBlockX();
            q.y = p.getLocation().getBlockY();
            q.z = p.getLocation().getBlockZ();
        } else {
            q.radius = -1;
        }
        return q;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (db == null || !db.ready()) {
            lang().send(sender, "log.not-ready");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        Player p = sender instanceof Player pl ? pl : null;
        switch (sub) {
            case "near", "lookup", "player" -> {
                Query q = parse(sender, args, 1, sub.equals("near") ? 8 : (sub.equals("player") ? -1 : 8), 3 * 86_400_000L);
                if (sub.equals("player") && q.actor == null) {
                    lang().send(sender, "log.usage");
                    return true;
                }
                db.find(q).thenAccept(list -> Bukkit.getScheduler().runTask(plugin, () -> {
                    results.put(sender.getName(), list);
                    show(sender, list, 1);
                }));
            }
            case "page" -> show(sender, results.getOrDefault(sender.getName(), List.of()), args.length > 1 ? parseInt(args[1]) : 1);
            case "rollback", "restore" -> {
                if (!sender.hasPermission("staffcore.blocklog.rollback")) {
                    lang().send(sender, "error.no-permission");
                    return true;
                }
                Query q = parse(sender, args, 1, 20, 86_400_000L);
                if (q.actor == null && args.length < 2) {
                    lang().send(sender, "log.usage");
                    return true;
                }
                boolean restore = sub.equals("restore");
                rollback(q, restore).thenAccept(n -> Bukkit.getScheduler().runTask(plugin, () ->
                        lang().send(sender, restore ? "log.restored" : "log.rolled-back", "count", n)));
            }
            case "replay" -> {
                if (p == null) {
                    lang().send(sender, "error.players-only");
                    return true;
                }
                startReplay(p, parse(sender, args, 1, 15, 86_400_000L));
            }
            case "pause", "slower", "faster", "stop" -> {
                Replay r = p == null ? null : replays.get(p.getUniqueId());
                if (r == null) {
                    lang().send(sender, "log.no-replay");
                    return true;
                }
                switch (sub) {
                    case "pause" -> r.paused = !r.paused;
                    case "slower" -> r.speed = Math.max(0.25, r.speed / 2);
                    case "faster" -> r.speed = Math.min(16, r.speed * 2);
                    default -> {
                        stopReplay(p.getUniqueId());
                        lang().send(p, "log.replay-stopped");
                        return true;
                    }
                }
                r.update();
            }
            case "tp" -> {
                if (p == null || args.length < 5) return true;
                World w = Bukkit.getWorld(args[1]);
                if (w == null) return true;
                plugin.admin().rememberReturn(p);
                p.teleport(new Location(w, parseInt(args[2]) + 0.5, parseInt(args[3]) + 1, parseInt(args[4]) + 0.5));
            }
            default -> {
                for (String line : lang().raw("log.help").split("\n")) sender.sendMessage(lang().parse(line));
            }
        }
        return true;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String typed = args[args.length - 1].toLowerCase();
        if (args.length == 1) {
            for (String s : List.of("near", "player", "rollback", "restore", "replay", "stop", "pause", "faster", "slower")) {
                if (s.startsWith(typed)) out.add(s);
            }
        } else if (List.of("player", "rollback", "restore", "replay", "near").contains(args[0].toLowerCase())) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(typed)) out.add(p.getName());
            for (String s : List.of("1h", "6h", "1d", "3d", "7d", "10", "20", "50", "#tnt", "#creeper", "#fire")) if (s.startsWith(typed)) out.add(s);
        }
        return out;
    }
}
