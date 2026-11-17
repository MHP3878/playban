package dev.staffcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * /fakebase: a bait base for ESP and x-ray cheaters. A room is dug out deep underground and filled with
 * one of eight player-style bases: rows of chests, furnaces, beds and two fake players walking around.
 * Nobody can break anything in it, opening a chest or digging straight towards it is reported to the team,
 * and after 30 minutes everything is put back exactly as it was.
 */
public final class FakeBaseManager implements Listener, TabExecutor {
    /** One look: blocks for walls, floor, ceiling, the steps under the chests, light, and how the chests stand. */
    public record Design(String id, Material wall, Material floor, Material ceiling, Material step, Material light,
                         Layout layout, int width, int depth, int height) {}

    public enum Layout { STAIRS, WALL, ROWS }

    public static final List<Design> DESIGNS = List.of(
            new Design("vault", Material.DEEPSLATE_TILES, Material.POLISHED_DEEPSLATE, Material.DEEPSLATE_BRICKS,
                    Material.DEEPSLATE_TILES, Material.SEA_LANTERN, Layout.STAIRS, 15, 13, 6),
            new Design("bunker", Material.STONE_BRICKS, Material.SMOOTH_STONE, Material.STONE_BRICKS,
                    Material.STONE_BRICKS, Material.GLOWSTONE, Layout.WALL, 15, 11, 5),
            new Design("hideout", Material.POLISHED_BLACKSTONE_BRICKS, Material.BLACKSTONE, Material.POLISHED_BLACKSTONE_BRICKS,
                    Material.GILDED_BLACKSTONE, Material.SHROOMLIGHT, Layout.STAIRS, 13, 13, 6),
            new Design("lab", Material.WAXED_CUT_COPPER, Material.POLISHED_TUFF, Material.WAXED_CUT_COPPER,
                    Material.WAXED_COPPER_BLOCK, Material.PEARLESCENT_FROGLIGHT, Layout.ROWS, 15, 13, 5),
            new Design("cellar", Material.MUD_BRICKS, Material.PACKED_MUD, Material.MUD_BRICKS,
                    Material.MUD_BRICKS, Material.OCHRE_FROGLIGHT, Layout.WALL, 13, 11, 5),
            new Design("storage", Material.QUARTZ_BRICKS, Material.SMOOTH_QUARTZ, Material.QUARTZ_BRICKS,
                    Material.QUARTZ_BLOCK, Material.SEA_LANTERN, Layout.STAIRS, 17, 13, 6),
            new Design("hall", Material.TUFF_BRICKS, Material.POLISHED_TUFF, Material.TUFF_BRICKS,
                    Material.CHISELED_TUFF, Material.VERDANT_FROGLIGHT, Layout.ROWS, 17, 13, 5),
            new Design("stash", Material.OBSIDIAN, Material.POLISHED_BLACKSTONE, Material.CRYING_OBSIDIAN,
                    Material.OBSIDIAN, Material.GLOWSTONE, Layout.STAIRS, 13, 11, 6));

    private static final String[] FIRST = {"Lukas", "Noah", "Mia", "Finn", "Leon", "Emma", "Paul", "Elias", "Lina", "Felix",
            "Jonas", "Ben", "Luca", "Mats", "Nele", "Tom", "Ida", "Jan", "Kai", "Mila"};
    private static final String[] STYLE = {"%s_%d", "x%sx", "%sPvP", "%s%d", "its%s", "%s_YT", "Dark%s", "%s_mc"};

    /** A base that stands right now. */
    private static final class Base {
        int id;
        String design;
        String world;
        int x, y, z, w, h, d;
        long expiresAt;
        String by;
        /** every block of the box before, as palette indices */
        final List<String> palette = new ArrayList<>();
        int[] blocks;
        /** containers that were there before, with their contents */
        final Map<String, String> contents = new LinkedHashMap<>();
        final List<Entity> npcs = new ArrayList<>();
        final Map<UUID, Location> npcTargets = new HashMap<>();
        final Set<UUID> warnedNear = new HashSet<>();
        final Set<UUID> warnedInside = new HashSet<>();
        final Map<UUID, Long> lastChestAlert = new HashMap<>();

        boolean contains(Location l, int margin) {
            return l.getWorld() != null && l.getWorld().getName().equals(world)
                    && l.getBlockX() >= x - margin && l.getBlockX() < x + w + margin
                    && l.getBlockY() >= y - margin && l.getBlockY() < y + h + margin
                    && l.getBlockZ() >= z - margin && l.getBlockZ() < z + d + margin;
        }

        boolean contains(Block b) {
            return contains(b.getLocation(), 0);
        }
    }

    private final StaffCorePlugin plugin;
    private final NamespacedKey npcKey;
    private final Map<Integer, Base> bases = new LinkedHashMap<>();
    private final Random random = new Random();
    private int nextId = 1;
    private BukkitTask ticker;

    public FakeBaseManager(StaffCorePlugin plugin) {
        this.plugin = plugin;
        this.npcKey = new NamespacedKey(plugin, "fakebase");
    }

    private Lang lang() {
        return plugin.lang();
    }

    private File file() {
        return new File(plugin.getDataFolder(), "fakebases.yml");
    }

    public int count() {
        return bases.size();
    }

    public boolean isFake(Block b) {
        for (Base base : bases.values()) if (base.contains(b)) return true;
        return false;
    }

    // ---------------------------------------------------------------- start and stop

    public void start() {
        restoreLeftovers();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    /** On shutdown every base goes away again, so the world never keeps one. */
    public void stop() {
        if (ticker != null) ticker.cancel();
        for (Base b : new ArrayList<>(bases.values())) remove(b, false);
    }

    /** After a crash the old blocks are still in fakebases.yml: they are put back on the next start. */
    private void restoreLeftovers() {
        if (!file().exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file());
        ConfigurationSection root = cfg.getConfigurationSection("bases");
        if (root != null) {
            for (String k : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(k);
                if (s == null) continue;
                Base b = read(s);
                if (b != null) restoreBlocks(b);
            }
        }
        file().delete();
    }

    private Base read(ConfigurationSection s) {
        Base b = new Base();
        b.id = s.getInt("id");
        b.design = s.getString("design");
        b.world = s.getString("world");
        b.x = s.getInt("x");
        b.y = s.getInt("y");
        b.z = s.getInt("z");
        b.w = s.getInt("w");
        b.h = s.getInt("h");
        b.d = s.getInt("d");
        b.palette.addAll(s.getStringList("palette"));
        String data = s.getString("blocks", "");
        if (data.isEmpty() || b.world == null) return null;
        String[] parts = data.split(",");
        b.blocks = new int[parts.length];
        for (int i = 0; i < parts.length; i++) b.blocks[i] = Integer.parseInt(parts[i]);
        ConfigurationSection c = s.getConfigurationSection("contents");
        if (c != null) for (String pos : c.getKeys(false)) b.contents.put(pos.replace('_', ','), c.getString(pos));
        return b;
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Base b : bases.values()) {
            String p = "bases." + b.id + ".";
            cfg.set(p + "id", b.id);
            cfg.set(p + "design", b.design);
            cfg.set(p + "world", b.world);
            cfg.set(p + "x", b.x);
            cfg.set(p + "y", b.y);
            cfg.set(p + "z", b.z);
            cfg.set(p + "w", b.w);
            cfg.set(p + "h", b.h);
            cfg.set(p + "d", b.d);
            cfg.set(p + "palette", b.palette);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < b.blocks.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(b.blocks[i]);
            }
            cfg.set(p + "blocks", sb.toString());
            for (Map.Entry<String, String> c : b.contents.entrySet()) cfg.set(p + "contents." + c.getKey().replace(',', '_'), c.getValue());
        }
        try {
            if (bases.isEmpty()) file().delete();
            else cfg.save(file());
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save fakebases.yml: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- placing

    /** Somewhere deep under the player, 40 to 70 blocks to the side, so only someone who sees through walls finds it. */
    public Location spotNear(Player target) {
        World w = target.getWorld();
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = 40 + random.nextInt(31);
        int x = (int) Math.floor(target.getLocation().getX() + Math.cos(angle) * dist);
        int z = (int) Math.floor(target.getLocation().getZ() + Math.sin(angle) * dist);
        int surface = w.getHighestBlockYAt(x, z);
        int y = Math.min(surface - 22, target.getLocation().getBlockY() - 4);
        y = Math.max(w.getMinHeight() + 6, y);
        return new Location(w, x, y, z);
    }

    /** Builds a base with its floor at the given spot. Returns null when there is no room in the world there. */
    public Base spawn(Location at, Design design, String by) {
        World world = at.getWorld();
        int w = design.width() + 2, h = design.height() + 2, d = design.depth() + 2;
        int x0 = at.getBlockX() - w / 2, y0 = at.getBlockY() - 1, z0 = at.getBlockZ() - d / 2;
        if (y0 < world.getMinHeight() || y0 + h >= world.getMaxHeight()) return null;
        Base b = new Base();
        b.id = nextId++;
        b.design = design.id();
        b.world = world.getName();
        b.x = x0;
        b.y = y0;
        b.z = z0;
        b.w = w;
        b.h = h;
        b.d = d;
        b.by = by;
        b.expiresAt = System.currentTimeMillis() + plugin.getConfig().getInt("fakebase.minutes", 30) * 60_000L;
        // remember everything that is there now
        Map<String, Integer> index = new HashMap<>();
        b.blocks = new int[w * h * d];
        int i = 0;
        for (int dx = 0; dx < w; dx++) {
            for (int dy = 0; dy < h; dy++) {
                for (int dz = 0; dz < d; dz++) {
                    Block block = world.getBlockAt(x0 + dx, y0 + dy, z0 + dz);
                    String data = block.getBlockData().getAsString();
                    Integer n = index.get(data);
                    if (n == null) {
                        n = b.palette.size();
                        b.palette.add(data);
                        index.put(data, n);
                    }
                    b.blocks[i++] = n;
                    if (block.getState() instanceof Container c) {
                        b.contents.put(block.getX() + "," + block.getY() + "," + block.getZ(),
                                Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(c.getInventory().getContents())));
                        c.getInventory().clear();
                    }
                }
            }
        }
        bases.put(b.id, b);
        save();
        build(world, b, design);
        spawnNpcs(world, b, design);
        plugin.staffLog(by, "fakebase", null, design.id() + " " + x0 + " " + y0 + " " + z0);
        return b;
    }

    private void set(World world, int x, int y, int z, Material m) {
        world.getBlockAt(x, y, z).setType(m, false);
    }

    private void build(World world, Base b, Design design) {
        int x0 = b.x, y0 = b.y, z0 = b.z, w = b.w, h = b.h, d = b.d;
        // the shell: walls, floor and ceiling; inside empty
        for (int dx = 0; dx < w; dx++) {
            for (int dy = 0; dy < h; dy++) {
                for (int dz = 0; dz < d; dz++) {
                    boolean edge = dx == 0 || dz == 0 || dx == w - 1 || dz == d - 1;
                    Material m;
                    if (dy == 0) m = design.floor();
                    else if (dy == h - 1) m = (dx % 4 == 2 && dz % 4 == 2 && !edge) ? design.light() : design.ceiling();
                    else m = edge ? design.wall() : Material.AIR;
                    set(world, x0 + dx, y0 + dy, z0 + dz, m);
                }
            }
        }
        // inside coordinates: 1..w-2, floor level y0+1
        int fx = x0 + 1, fy = y0 + 1, fz = z0 + 1, iw = w - 2, ih = h - 2, id = d - 2;
        switch (design.layout()) {
            case STAIRS -> {
                // the classic chest stand: a double chest, one back and one up the next row, and so on
                int tiers = Math.min(ih - 1, 4);
                int startZ = fz + id - tiers;
                for (int t = 0; t < tiers; t++) {
                    for (int cx = fx + 1; cx + 1 < fx + iw - 1; cx += 2) {
                        for (int s = 0; s < t; s++) {
                            set(world, cx, fy + s, startZ + t, design.step());
                            set(world, cx + 1, fy + s, startZ + t, design.step());
                        }
                        doubleChest(world.getBlockAt(cx, fy + t, startZ + t), BlockFace.NORTH);
                    }
                }
            }
            case WALL -> {
                // both long walls full of double chests, three high
                for (int cx = fx + 1; cx + 1 < fx + iw - 1; cx += 2) {
                    for (int s = 0; s < Math.min(3, ih - 1); s++) {
                        doubleChest(world.getBlockAt(cx, fy + s, fz), BlockFace.SOUTH);
                        doubleChest(world.getBlockAt(cx, fy + s, fz + id - 1), BlockFace.NORTH);
                    }
                }
            }
            case ROWS -> {
                // rows of double chests, two high, with walkways in between
                for (int row = fz + 1; row < fz + id - 1; row += 3) {
                    for (int cx = fx + 2; cx + 1 < fx + iw - 2; cx += 2) {
                        for (int s = 0; s < 2; s++) doubleChest(world.getBlockAt(cx, fy + s, row), BlockFace.SOUTH);
                    }
                }
            }
        }
        // what a base needs: crafting, smelting, enchanting, beds, an ender chest
        int side = fx;
        int front = design.layout() == Layout.WALL ? fz + 1 : fz;
        placeIfAir(world, side, fy, front, Material.CRAFTING_TABLE);
        placeIfAir(world, side, fy, front + 1, Material.BLAST_FURNACE);
        placeIfAir(world, side, fy, front + 2, Material.FURNACE);
        placeIfAir(world, side, fy + 1, front + 1, Material.FURNACE);
        placeIfAir(world, side, fy, front + 3, Material.SMOKER);
        placeIfAir(world, fx + iw - 1, fy, front, Material.ENDER_CHEST);
        placeIfAir(world, fx + iw - 1, fy, front + 1, Material.ANVIL);
        placeIfAir(world, fx + iw - 1, fy, front + 2, Material.BREWING_STAND);
        int ex = fx + iw / 2, ez = fz + (design.layout() == Layout.STAIRS ? 2 : id / 2);
        if (design.layout() != Layout.ROWS) {
            placeIfAir(world, ex, fy, ez, Material.ENCHANTING_TABLE);
            for (int bx = -2; bx <= 2; bx++) {
                placeIfAir(world, ex + bx, fy, ez - 2, Material.BOOKSHELF);
                placeIfAir(world, ex + bx, fy + 1, ez - 2, Material.BOOKSHELF);
            }
        }
        bed(world.getBlockAt(fx + iw - 1, fy, fz + id - 3), BlockFace.SOUTH);
        bed(world.getBlockAt(fx + iw - 2, fy, fz + id - 3), BlockFace.SOUTH);
    }

    private void placeIfAir(World world, int x, int y, int z, Material m) {
        Block b = world.getBlockAt(x, y, z);
        if (b.getType().isAir()) b.setType(m, false);
    }

    /** A double chest: this block is the left half, the other half is clockwise of where it faces. */
    private void doubleChest(Block left, BlockFace facing) {
        BlockFace toRight = switch (facing) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
        Block right = left.getRelative(toRight);
        chestHalf(left, facing, org.bukkit.block.data.type.Chest.Type.LEFT);
        chestHalf(right, facing, org.bukkit.block.data.type.Chest.Type.RIGHT);
        fill(left);
        fill(right);
    }

    private void chestHalf(Block b, BlockFace facing, org.bukkit.block.data.type.Chest.Type type) {
        BlockData data = Bukkit.createBlockData(Material.CHEST);
        if (data instanceof org.bukkit.block.data.type.Chest chest) {
            chest.setFacing(facing);
            chest.setType(type);
        }
        b.setType(Material.CHEST, false);
        b.setBlockData(data, false);
    }

    private void bed(Block foot, BlockFace facing) {
        Block head = foot.getRelative(facing);
        if (!foot.getType().isAir() || !head.getType().isAir()) return;
        Material color = List.of(Material.RED_BED, Material.BLUE_BED, Material.BLACK_BED, Material.WHITE_BED).get(random.nextInt(4));
        BlockData f = Bukkit.createBlockData(color);
        BlockData hd = Bukkit.createBlockData(color);
        if (f instanceof org.bukkit.block.data.type.Bed fb && hd instanceof org.bukkit.block.data.type.Bed hb) {
            fb.setFacing(facing);
            fb.setPart(org.bukkit.block.data.type.Bed.Part.FOOT);
            hb.setFacing(facing);
            hb.setPart(org.bukkit.block.data.type.Bed.Part.HEAD);
        }
        foot.setBlockData(f, false);
        head.setBlockData(hd, false);
    }

    private static final Material[] LOOT = {Material.DIAMOND, Material.DIAMOND_BLOCK, Material.NETHERITE_INGOT, Material.GOLDEN_APPLE,
            Material.ENCHANTED_GOLDEN_APPLE, Material.TOTEM_OF_UNDYING, Material.SHULKER_BOX, Material.PURPLE_SHULKER_BOX,
            Material.BLACK_SHULKER_BOX, Material.EMERALD_BLOCK, Material.GOLD_BLOCK, Material.IRON_BLOCK, Material.ENDER_PEARL,
            Material.EXPERIENCE_BOTTLE, Material.OBSIDIAN, Material.END_CRYSTAL, Material.ELYTRA, Material.NETHERITE_SCRAP,
            Material.ANCIENT_DEBRIS, Material.FIREWORK_ROCKET};

    /** Chests look well stocked. Nothing can be taken out of them. */
    private void fill(Block b) {
        if (!(b.getState() instanceof Container c)) return;
        var inv = c instanceof org.bukkit.block.Chest chest ? chest.getBlockInventory() : c.getInventory();
        int n = 4 + random.nextInt(14);
        for (int k = 0; k < n; k++) {
            Material m = LOOT[random.nextInt(LOOT.length)];
            int amount = Math.min(m.getMaxStackSize(), 1 + random.nextInt(Math.max(1, m.getMaxStackSize())));
            inv.setItem(random.nextInt(inv.getSize()), new ItemStack(m, amount));
        }
    }

    // ---------------------------------------------------------------- the two fake players

    private String randomName() {
        String first = FIRST[random.nextInt(FIRST.length)];
        String name = String.format(STYLE[random.nextInt(STYLE.length)], first, 2008 + random.nextInt(10));
        return name.length() > 16 ? name.substring(0, 16) : name;
    }

    private void spawnNpcs(World world, Base b, Design design) {
        for (int k = 0; k < 2; k++) {
            Location at = randomInside(world, b);
            if (at == null) continue;
            Entity npc = spawnNpc(at, randomName());
            if (npc != null) b.npcs.add(npc);
        }
    }

    /** On 1.21.9 and newer a real player figure (mannequin), before that an armor stand dressed like a player. */
    private Entity spawnNpc(Location at, String name) {
        try {
            Class<?> type = Class.forName("org.bukkit.entity.Mannequin");
            @SuppressWarnings("unchecked")
            Entity e = at.getWorld().spawn(at, (Class<? extends Entity>) type, en -> prepare(en, name));
            try {
                // the grey "NPC" line under the name would give it away
                type.getMethod("setDescription", Component.class).invoke(e, (Object) null);
            } catch (ReflectiveOperationException ignored) {
                // older builds have no description
            }
            return e;
        } catch (ClassNotFoundException | RuntimeException noMannequin) {
            try {
                return at.getWorld().spawn(at, ArmorStand.class, stand -> {
                    prepare(stand, name);
                    optional(() -> stand.setArms(true));
                    optional(() -> stand.setBasePlate(false));
                    optional(() -> {
                        var eq = stand.getEquipment();
                        eq.setHelmet(new ItemStack(Material.PLAYER_HEAD));
                        eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE));
                        eq.setLeggings(dyed(Material.LEATHER_LEGGINGS));
                        eq.setBoots(new ItemStack(Material.IRON_BOOTS));
                        eq.setItemInMainHand(new ItemStack(random.nextBoolean() ? Material.DIAMOND_PICKAXE : Material.NETHERITE_SWORD));
                    });
                });
            } catch (RuntimeException failed) {
                plugin.getLogger().warning("Could not place a fake player: " + failed);
                return null;
            }
        }
    }

    private ItemStack dyed(Material m) {
        ItemStack item = new ItemStack(m);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(Color.fromRGB(random.nextInt(0xffffff)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void prepare(Entity e, String name) {
        e.customName(Component.text(name));
        e.setCustomNameVisible(true);
        e.setPersistent(false);
        e.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
        optional(() -> e.setInvulnerable(true));
        optional(() -> e.setSilent(true));
        if (e instanceof LivingEntity le) {
            optional(() -> le.setRemoveWhenFarAway(false));
            optional(() -> le.setCollidable(false));
        }
    }

    /** Nice to have, but not worth losing the fake player over. */
    private static void optional(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // not supported here
        }
    }

    private Location randomInside(World world, Base b) {
        for (int tries = 0; tries < 30; tries++) {
            int x = b.x + 1 + random.nextInt(b.w - 2);
            int z = b.z + 1 + random.nextInt(b.d - 2);
            Block feet = world.getBlockAt(x, b.y + 1, z);
            if (feet.getType().isAir() && feet.getRelative(BlockFace.UP).getType().isAir()) {
                return new Location(world, x + 0.5, b.y + 1, z + 0.5, random.nextFloat() * 360, 0);
            }
        }
        return null;
    }

    /** The fake players wander slowly from spot to spot, looking where they go. */
    private void walk(Base b) {
        World world = Bukkit.getWorld(b.world);
        if (world == null) return;
        for (Entity npc : b.npcs) {
            if (!npc.isValid()) continue;
            Location target = b.npcTargets.get(npc.getUniqueId());
            Location now = npc.getLocation();
            if (target == null || now.distanceSquared(target) < 0.3 || random.nextInt(40) == 0) {
                target = randomInside(world, b);
                if (target == null) continue;
                b.npcTargets.put(npc.getUniqueId(), target);
                if (random.nextInt(3) == 0) continue; // stands still for a moment
            }
            org.bukkit.util.Vector step = target.toVector().subtract(now.toVector()).setY(0);
            if (step.lengthSquared() < 0.0001) continue;
            double len = Math.min(0.55, step.length());
            step.normalize().multiply(len);
            Location next = now.clone().add(step);
            Block feet = next.getBlock();
            if (!feet.getType().isAir() || !feet.getRelative(BlockFace.UP).getType().isAir()) {
                b.npcTargets.remove(npc.getUniqueId());
                continue;
            }
            next.setYaw((float) Math.toDegrees(Math.atan2(-step.getX(), step.getZ())));
            next.setPitch(0);
            npc.teleport(next);
        }
    }

    // ---------------------------------------------------------------- watching and removing

    private void tick() {
        long now = System.currentTimeMillis();
        for (Base b : new ArrayList<>(bases.values())) {
            if (now >= b.expiresAt) {
                remove(b, true);
                continue;
            }
            walk(b);
            World world = Bukkit.getWorld(b.world);
            if (world == null) continue;
            for (Player p : world.getPlayers()) {
                if (p.hasPermission("staffcore.staff")) continue;
                Location l = p.getLocation();
                if (b.contains(l, 0) && b.warnedInside.add(p.getUniqueId())) alert(p, b, "fakebase.alert-inside");
                else if (b.contains(l, 10) && b.warnedNear.add(p.getUniqueId())
                        && l.getBlockY() < world.getHighestBlockYAt(l.getBlockX(), l.getBlockZ()) - 5) {
                    // ten blocks away, and underground: nobody walks there by chance
                    alert(p, b, "fakebase.alert-near");
                }
            }
        }
    }

    private void alert(Player p, Base b, String key) {
        plugin.punishments().notifyStaff(lang().prefixed(key, "player", p.getName(), "id", b.id)
                .clickEvent(ClickEvent.runCommand("/tpadmin " + p.getName())));
        plugin.staffLog(p.getName(), key.substring(key.indexOf('.') + 1), null, "fakebase #" + b.id);
    }

    public void remove(Base b, boolean announce) {
        bases.remove(b.id);
        for (Entity npc : b.npcs) npc.remove();
        restoreBlocks(b);
        save();
        if (announce) plugin.punishments().notifyStaff(lang().prefixed("fakebase.gone", "id", b.id));
    }

    /** Everything back as it was: blocks first, then what was in the containers. */
    private void restoreBlocks(Base b) {
        World world = Bukkit.getWorld(b.world);
        if (world == null) return;
        int i = 0;
        for (int dx = 0; dx < b.w; dx++) {
            for (int dy = 0; dy < b.h; dy++) {
                for (int dz = 0; dz < b.d; dz++) {
                    Block block = world.getBlockAt(b.x + dx, b.y + dy, b.z + dz);
                    if (block.getState() instanceof Container c) {
                        // the fake loot must not drop or stay behind
                        (c instanceof org.bukkit.block.Chest chest ? chest.getBlockInventory() : c.getInventory()).clear();
                    }
                    String data = b.palette.get(b.blocks[i++]);
                    try {
                        block.setBlockData(Bukkit.createBlockData(data), false);
                    } catch (IllegalArgumentException | UnsupportedOperationException e) {
                        String type = data.contains("[") ? data.substring(0, data.indexOf('[')) : data;
                        Material m = Material.matchMaterial(type);
                        block.setType(m == null ? Material.STONE : m, false);
                    }
                }
            }
        }
        for (Map.Entry<String, String> c : b.contents.entrySet()) {
            String[] xyz = c.getKey().split(",");
            Block block = world.getBlockAt(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
            if (block.getState() instanceof Container container) {
                try {
                    container.getInventory().setContents(ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(c.getValue())));
                } catch (RuntimeException ignored) {
                    // the container changed size; better empty than broken
                }
            }
        }
    }

    // ---------------------------------------------------------------- nothing can be taken or broken

    private Base baseAt(Block b) {
        for (Base base : bases.values()) if (base.contains(b)) return base;
        return null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (baseAt(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (baseAt(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (baseAt(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> baseAt(b) != null);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> baseAt(b) != null);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (baseAt(e.getToBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonPush(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) if (baseAt(b) != null || baseAt(b.getRelative(e.getDirection())) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonPull(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) if (baseAt(b) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        var at = dev.staffcore.log.BlockLog.containerLocation(e.getSource().getHolder());
        if (at != null && baseAt(at.getBlock()) != null) e.setCancelled(true);
    }

    /** Opening a chest is the clearest sign: the team hears it right away. The items stay where they are. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        var at = dev.staffcore.log.BlockLog.containerLocation(e.getInventory().getHolder());
        if (at == null) return;
        Base b = baseAt(at.getBlock());
        if (b == null || p.hasPermission("staffcore.staff")) return;
        long now = System.currentTimeMillis();
        Long last = b.lastChestAlert.get(p.getUniqueId());
        if (last == null || now - last > 10_000) {
            b.lastChestAlert.put(p.getUniqueId(), now);
            alert(p, b, "fakebase.alert-chest");
        }
    }

    private boolean fakeInventory(org.bukkit.inventory.InventoryView view) {
        var at = dev.staffcore.log.BlockLog.containerLocation(view.getTopInventory().getHolder());
        return at != null && baseAt(at.getBlock()) != null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTake(InventoryClickEvent e) {
        if (fakeInventory(e.getView())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (fakeInventory(e.getView())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onNpcHurt(EntityDamageEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onNpcClick(PlayerInteractEntityEvent e) {
        if (e.getRightClicked().getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onStandEdit(org.bukkit.event.player.PlayerArmorStandManipulateEvent e) {
        if (e.getRightClicked().getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- /fakebase

    private Design design(String name) {
        if (name == null || name.equalsIgnoreCase("random")) return DESIGNS.get(random.nextInt(DESIGNS.size()));
        try {
            int n = Integer.parseInt(name);
            if (n >= 1 && n <= DESIGNS.size()) return DESIGNS.get(n - 1);
        } catch (NumberFormatException ignored) {
            // a name then
        }
        for (Design d : DESIGNS) if (d.id().equalsIgnoreCase(name)) return d;
        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "list" -> {
                if (bases.isEmpty()) lang().send(sender, "fakebase.none");
                for (Base b : bases.values()) {
                    long left = Math.max(0, b.expiresAt - System.currentTimeMillis());
                    sender.sendMessage(lang().msg("fakebase.entry", "id", b.id, "design", lang().raw("fakebase.design." + b.design),
                            "x", b.x + b.w / 2, "y", b.y + 1, "z", b.z + b.d / 2, "time", TimeUtil.format(Math.max(1000, left)))
                            .clickEvent(ClickEvent.runCommand("/fakebase tp " + b.id)));
                }
                return true;
            }
            case "remove" -> {
                if (args.length < 2) {
                    lang().send(sender, "fakebase.usage");
                    return true;
                }
                if (args[1].equalsIgnoreCase("all")) {
                    for (Base b : new ArrayList<>(bases.values())) remove(b, false);
                    lang().send(sender, "fakebase.removed-all");
                    return true;
                }
                Base b = byId(args[1]);
                if (b == null) lang().send(sender, "fakebase.unknown", "id", args[1]);
                else {
                    remove(b, false);
                    lang().send(sender, "fakebase.removed", "id", b.id);
                }
                return true;
            }
            case "tp" -> {
                Base b = args.length > 1 ? byId(args[1]) : null;
                if (!(sender instanceof Player p)) lang().send(sender, "error.players-only");
                else if (b == null) lang().send(sender, "fakebase.unknown", "id", args.length > 1 ? args[1] : "?");
                else {
                    World world = Bukkit.getWorld(b.world);
                    if (world != null) p.teleport(new Location(world, b.x + b.w / 2.0, b.y + 1, b.z + 1.5));
                }
                return true;
            }
            case "designs" -> {
                for (int n = 0; n < DESIGNS.size(); n++) {
                    lang().send(sender, "fakebase.design-line", "number", n + 1, "design", lang().raw("fakebase.design." + DESIGNS.get(n).id()));
                }
                return true;
            }
            default -> {
                // /fakebase [here|<player>] [design]
                Location at;
                String targetName = null;
                int designArg = 1;
                if (sub.equals("at")) {
                    // /fakebase at <x> <y> <z> [design], also from the console
                    if (args.length < 4) {
                        lang().send(sender, "fakebase.usage");
                        return true;
                    }
                    try {
                        World world = sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
                        at = new Location(world, Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                    } catch (NumberFormatException ex) {
                        lang().send(sender, "fakebase.usage");
                        return true;
                    }
                    designArg = 4;
                } else if (sub.isEmpty() || sub.equals("here")) {
                    if (!(sender instanceof Player p)) {
                        lang().send(sender, "fakebase.usage");
                        return true;
                    }
                    at = p.getLocation();
                } else {
                    Player target = Bukkit.getPlayerExact(args[0]);
                    if (target == null) {
                        lang().send(sender, "admin.not-online", "player", args[0]);
                        return true;
                    }
                    targetName = target.getName();
                    at = spotNear(target);
                }
                Design design = design(args.length > designArg ? args[designArg] : null);
                if (design == null) {
                    lang().send(sender, "fakebase.unknown-design", "design", args[designArg]);
                    return true;
                }
                if (bases.size() >= plugin.getConfig().getInt("fakebase.max", 5)) {
                    lang().send(sender, "fakebase.too-many");
                    return true;
                }
                Base b = spawn(at, design, sender.getName());
                if (b == null) {
                    lang().send(sender, "fakebase.no-room");
                    return true;
                }
                sender.sendMessage(lang().prefixed(targetName == null ? "fakebase.spawned" : "fakebase.spawned-near",
                        "id", b.id, "design", lang().raw("fakebase.design." + design.id()), "player", targetName == null ? "" : targetName,
                        "x", b.x + b.w / 2, "y", b.y + 1, "z", b.z + b.d / 2,
                        "minutes", plugin.getConfig().getInt("fakebase.minutes", 30))
                        .clickEvent(ClickEvent.runCommand("/fakebase tp " + b.id)));
                return true;
            }
        }
    }

    private Base byId(String text) {
        try {
            return bases.get(Integer.parseInt(text.replace("#", "")));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String typed = args[args.length - 1].toLowerCase();
        if (args.length == 1) {
            for (String s : List.of("here", "at", "list", "remove", "tp", "designs")) if (s.startsWith(typed)) out.add(s);
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(typed)) out.add(p.getName());
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("remove") || sub.equals("tp")) {
                for (Base b : bases.values()) out.add(String.valueOf(b.id));
                if (sub.equals("remove")) out.add("all");
            } else if (!sub.equals("list") && !sub.equals("designs") && !sub.equals("at")) {
                out.add("random");
                for (Design d : DESIGNS) if (d.id().startsWith(typed)) out.add(d.id());
            }
        }
        return out;
    }
}
