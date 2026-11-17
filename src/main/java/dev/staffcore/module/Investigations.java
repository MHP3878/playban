package dev.staffcore.module;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import dev.staffcore.gui.Items;
import dev.staffcore.gui.Menu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Tests a team member can run on a suspect, each with its own choices: a bot behind the suspect's back
 * that only a kill aura hits, fake ores deep in the stone that only x-ray finds, a fake base nearby, a
 * knockback test, a fresh recording, and a screen check with a call to the voice channel.
 */
public final class Investigations implements Listener {
    private final StaffCorePlugin plugin;
    private final Random random = new Random();
    /** per staff member: option -> chosen index */
    private final Map<UUID, Map<String, Integer>> choices = new HashMap<>();

    private static final Map<String, List<String>> OPTIONS = new HashMap<>();

    static {
        OPTIONS.put("aura.seconds", List.of("10", "20", "30", "60"));
        OPTIONS.put("aura.kind", List.of("zombie", "player", "stand"));
        OPTIONS.put("aura.invisible", List.of("disabled", "enabled"));
        OPTIONS.put("bait.count", List.of("5", "10", "20"));
        OPTIONS.put("bait.ore", List.of("DIAMOND", "ANCIENT_DEBRIS", "EMERALD", "GOLD"));
        OPTIONS.put("bait.show", List.of("disabled", "enabled"));
        OPTIONS.put("base.design", List.of("random", "1", "2", "3", "4", "5", "6", "7", "8"));
        OPTIONS.put("ss.freeze", List.of("enabled", "disabled"));
    }

    private final class Bot {
        UUID suspect;
        UUID staff;
        Entity entity;
        BukkitTask task;
        int hits, behind;
        long until;
    }

    private final Map<UUID, Bot> bots = new HashMap<>();
    private final Map<UUID, Set<Block>> baits = new HashMap<>();
    private final Map<UUID, UUID> baitStaff = new HashMap<>();

    public Investigations(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private String choice(Player staff, String option) {
        List<String> values = OPTIONS.get(option);
        int i = choices.getOrDefault(staff.getUniqueId(), Map.of()).getOrDefault(option, 0);
        return values.get(Math.floorMod(i, values.size()));
    }

    private void next(Player staff, String option) {
        choices.computeIfAbsent(staff.getUniqueId(), k -> new HashMap<>()).merge(option, 1, Integer::sum);
    }

    private String shown(String option, String value) {
        String key = "tests.value." + value.toLowerCase(Locale.ROOT);
        String raw = lang().raw(key);
        return raw.contains("missing") ? value : raw;
    }

    // ---------------------------------------------------------------- the menu

    public void menu(Player staff, Player t) {
        Menu m = new Menu(4, lang().msg("tests.title", "player", t.getName()));
        m.set(10, item(Material.ZOMBIE_HEAD, "tests.aura", staff, "aura.seconds", "aura.kind", "aura.invisible"), e -> {
            if (e.isShiftClick() && e.isRightClick()) next(staff, "aura.invisible");
            else if (e.isShiftClick()) next(staff, "aura.kind");
            else if (e.isRightClick()) next(staff, "aura.seconds");
            else {
                staff.closeInventory();
                auraBot(staff, t);
                return;
            }
            menu(staff, t);
        });
        m.set(11, item(Material.DIAMOND_ORE, "tests.bait", staff, "bait.count", "bait.ore", "bait.show"), e -> {
            if (e.isShiftClick() && e.isRightClick()) next(staff, "bait.show");
            else if (e.isShiftClick()) next(staff, "bait.ore");
            else if (e.isRightClick()) next(staff, "bait.count");
            else {
                staff.closeInventory();
                xrayBait(staff, t);
                return;
            }
            menu(staff, t);
        });
        m.set(12, item(Material.CHEST, "tests.base", staff, "base.design"), e -> {
            if (e.isRightClick()) {
                next(staff, "base.design");
                menu(staff, t);
                return;
            }
            staff.closeInventory();
            String d = choice(staff, "base.design");
            staff.performCommand("fakebase " + t.getName() + (d.equals("random") ? "" : " " + d));
        });
        m.set(13, Items.of(Material.STICK, lang().msg("tests.kb.name"), lang().lore("tests.kb.lore")), e -> {
            staff.closeInventory();
            plugin.tools().knockbackTest(staff, t);
        });
        m.set(14, Items.of(Material.RECOVERY_COMPASS, lang().msg("tests.record.name"), lang().lore("tests.record.lore")), e -> {
            staff.closeInventory();
            plugin.antiCheat().replay(staff, t.getUniqueId(), "now");
        });
        m.set(15, item(Material.SPYGLASS, "tests.ss", staff, "ss.freeze"), e -> {
            if (e.isRightClick()) {
                next(staff, "ss.freeze");
                menu(staff, t);
                return;
            }
            staff.closeInventory();
            screenshare(staff, t);
        });
        m.set(16, Items.of(Material.NAME_TAG, lang().msg("tests.mods.name"), lang().lore("tests.mods.lore")), e -> {
            staff.closeInventory();
            staff.performCommand("mods " + t.getName());
            plugin.modGuard().probe(t);
        });
        m.set(31, Items.of(Material.OAK_DOOR, lang().msg("menu.back"), List.of()), e -> plugin.playerCheck().open(staff, t));
        m.fill().open(staff);
    }

    private org.bukkit.inventory.ItemStack item(Material icon, String key, Player staff, String... options) {
        List<Component> lore = new ArrayList<>(lang().lore(key + ".lore"));
        lore.add(Component.empty());
        for (String o : options) lore.add(lang().msg("tests.option." + o, "value", shown(o, choice(staff, o))));
        lore.add(Component.empty());
        lore.addAll(lang().lore(key + ".clicks"));
        return Items.of(icon, lang().msg(key + ".name"), lore);
    }

    // ---------------------------------------------------------------- aura bot

    /** A target behind the suspect's back that only the suspect (and you) can see. Legit players do not hit what is behind them. */
    public void auraBot(Player staff, Player t) {
        stopBot(t.getUniqueId());
        String kind = choice(staff, "aura.kind");
        boolean invisible = choice(staff, "aura.invisible").equals("enabled");
        int seconds = Integer.parseInt(choice(staff, "aura.seconds"));
        Location at = behind(t, 2.2, 0);
        Entity e;
        try {
            e = switch (kind) {
                case "stand" -> at.getWorld().spawn(at, ArmorStand.class, a -> {
                    optional(() -> a.setInvisible(invisible));
                    optional(() -> a.setGravity(false));
                    optional(() -> a.setBasePlate(false));
                    optional(() -> a.setArms(true));
                });
                case "player" -> spawnFigure(at);
                default -> at.getWorld().spawn(at, Zombie.class, z -> {
                    optional(() -> z.setAI(false));
                    optional(() -> z.setSilent(true));
                    optional(() -> z.setAdult());
                    optional(() -> z.setShouldBurnInDay(false));
                });
            };
        } catch (RuntimeException ex) {
            lang().send(staff, "tests.failed");
            return;
        }
        prepare(e, staff, t, invisible);
        Bot b = new Bot();
        b.suspect = t.getUniqueId();
        b.staff = staff.getUniqueId();
        b.entity = e;
        b.until = System.currentTimeMillis() + seconds * 1000L;
        int[] tick = {0};
        b.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            tick[0]++;
            if (!t.isOnline() || !e.isValid() || tick[0] > seconds * 20) {
                stopBot(t.getUniqueId());
                return;
            }
            // circles slowly behind the back, always looking at the suspect
            double swing = Math.sin(tick[0] / 15.0) * 40;
            Location to = behind(t, 2.2, swing);
            to.setDirection(t.getLocation().toVector().subtract(to.toVector()));
            e.teleport(to);
        }, 1L, 1L);
        bots.put(t.getUniqueId(), b);
        lang().send(staff, "tests.aura-started", "player", t.getName(), "seconds", seconds);
        plugin.staffLog(staff.getName(), "test aura-bot", t.getName(), kind + " " + seconds + "s" + (invisible ? " invisible" : ""));
    }

    private Entity spawnFigure(Location at) {
        try {
            Class<?> type = Class.forName("org.bukkit.entity.Mannequin");
            @SuppressWarnings("unchecked")
            Entity e = at.getWorld().spawn(at, (Class<? extends Entity>) type);
            try {
                type.getMethod("setDescription", Component.class).invoke(e, (Object) null);
            } catch (ReflectiveOperationException ignored) {
                // no description
            }
            return e;
        } catch (ClassNotFoundException noMannequin) {
            return at.getWorld().spawn(at, ArmorStand.class, a -> {
                optional(() -> a.setArms(true));
                optional(() -> a.setBasePlate(false));
                optional(() -> a.getEquipment().setHelmet(new org.bukkit.inventory.ItemStack(Material.PLAYER_HEAD)));
            });
        }
    }

    private void prepare(Entity e, Player staff, Player t, boolean invisible) {
        e.setPersistent(false);
        optional(() -> e.setVisibleByDefault(false));
        optional(() -> t.showEntity(plugin, e));
        optional(() -> staff.showEntity(plugin, e));
        e.customName(Component.text(randomName()));
        e.setCustomNameVisible(!invisible);
        e.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "testbot"), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        if (invisible && e instanceof LivingEntity le) {
            optional(() -> le.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false)));
        }
    }

    private String randomName() {
        String[] names = {"Lukas_2010", "xFinnx", "MiaPvP", "Noah2011", "itsLeon", "Ben_YT", "DarkElias", "Jonas_mc"};
        return names[random.nextInt(names.length)];
    }

    private Location behind(Player t, double distance, double swingDegrees) {
        Location l = t.getLocation();
        double yaw = Math.toRadians(l.getYaw() + 180 + swingDegrees);
        Vector back = new Vector(-Math.sin(yaw), 0, Math.cos(yaw)).multiply(distance);
        Location to = l.clone().add(back);
        to.setPitch(0);
        return to;
    }

    public void stopBot(UUID suspect) {
        Bot b = bots.remove(suspect);
        if (b == null) return;
        b.task.cancel();
        b.entity.remove();
        Player staff = Bukkit.getPlayer(b.staff);
        String name = Bukkit.getOfflinePlayer(suspect).getName();
        if (staff != null) lang().send(staff, "tests.aura-result", "player", name == null ? "?" : name, "hits", b.hits, "behind", b.behind);
        plugin.staffLog("Test", "aura-bot result", name, b.hits + " hits, " + b.behind + " behind");
    }

    /** Hits on a test bot: never real damage. Hits from behind the back count for the anti-cheat. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onHitBot(EntityDamageByEntityEvent e) {
        Bot b = null;
        for (Bot x : bots.values()) if (x.entity.equals(e.getEntity())) b = x;
        if (b == null) return;
        e.setCancelled(true);
        if (!(e.getDamager() instanceof Player p) || !p.getUniqueId().equals(b.suspect)) return;
        b.hits++;
        Vector toBot = e.getEntity().getLocation().add(0, 1, 0).toVector().subtract(p.getEyeLocation().toVector());
        double angle = Math.toDegrees(p.getEyeLocation().getDirection().angle(toBot));
        if (angle > 90) {
            b.behind++;
            plugin.antiCheat().flag(p, "aura", AntiCheat.severity(angle, 90, 170, 12), String.format(Locale.ROOT, "test bot %.0f°", angle));
        }
        Player staff = Bukkit.getPlayer(b.staff);
        if (staff != null) staff.sendActionBar(lang().msg("tests.aura-hit", "hits", b.hits, "behind", b.behind, "angle", (int) angle));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBotHurt(EntityDamageEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin, "testbot"), org.bukkit.persistence.PersistentDataType.BYTE)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBotTarget(EntityTargetEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin, "testbot"), org.bukkit.persistence.PersistentDataType.BYTE)) {
            e.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- x-ray bait

    private static boolean filler(Material m) {
        return m == Material.STONE || m == Material.DEEPSLATE || m == Material.TUFF || m == Material.NETHERRACK
                || m == Material.ANDESITE || m == Material.DIORITE || m == Material.GRANITE || m == Material.BASALT || m == Material.BLACKSTONE;
    }

    private static boolean enclosed(Block b) {
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Material m = b.getRelative(f).getType();
            if (!m.isSolid() || m.isAir()) return false;
        }
        return true;
    }

    /** Ores that only the suspect sees, walled in by stone all around: nobody without x-ray knows they are there. */
    public int xrayBait(Player staff, Player t) {
        clearBait(t.getUniqueId());
        int want = Integer.parseInt(choice(staff, "bait.count"));
        boolean show = choice(staff, "bait.show").equals("enabled");
        String ore = choice(staff, "bait.ore");
        World w = t.getWorld();
        Location c = t.getLocation();
        Set<Block> placed = new HashSet<>();
        for (int tries = 0; tries < want * 60 && placed.size() < want; tries++) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = 6 + random.nextDouble() * 10;
            int x = (int) Math.floor(c.getX() + Math.cos(a) * r);
            int z = (int) Math.floor(c.getZ() + Math.sin(a) * r);
            int y = c.getBlockY() - 12 + random.nextInt(14);
            if (y <= w.getMinHeight() + 1) continue;
            Block b = w.getBlockAt(x, y, z);
            if (!filler(b.getType()) || !enclosed(b)) continue;
            placed.add(b);
        }
        for (Block b : placed) {
            Material fake = oreFor(ore, b);
            t.sendBlockChange(b.getLocation(), fake.createBlockData());
            if (show) staff.sendBlockChange(b.getLocation(), fake.createBlockData());
        }
        baits.put(t.getUniqueId(), placed);
        baitStaff.put(t.getUniqueId(), staff.getUniqueId());
        lang().send(staff, "tests.bait-started", "player", t.getName(), "count", placed.size());
        plugin.staffLog(staff.getName(), "test x-ray bait", t.getName(), placed.size() + " " + ore);
        // after ten minutes the stone is stone again for the suspect
        Bukkit.getScheduler().runTaskLater(plugin, () -> clearBait(t.getUniqueId()), 20L * 600);
        return placed.size();
    }

    private static Material oreFor(String ore, Block b) {
        boolean deep = b.getType() == Material.DEEPSLATE || b.getType() == Material.TUFF;
        return switch (ore) {
            case "ANCIENT_DEBRIS" -> Material.ANCIENT_DEBRIS;
            case "EMERALD" -> deep ? Material.DEEPSLATE_EMERALD_ORE : Material.EMERALD_ORE;
            case "GOLD" -> deep ? Material.DEEPSLATE_GOLD_ORE : Material.GOLD_ORE;
            default -> deep ? Material.DEEPSLATE_DIAMOND_ORE : Material.DIAMOND_ORE;
        };
    }

    public void clearBait(UUID suspect) {
        Set<Block> set = baits.remove(suspect);
        baitStaff.remove(suspect);
        Player t = Bukkit.getPlayer(suspect);
        if (set == null || t == null) return;
        for (Block b : set) t.sendBlockChange(b.getLocation(), b.getBlockData());
    }

    public Set<Block> baits(UUID suspect) {
        return new HashSet<>(baits.getOrDefault(suspect, Set.of()));
    }

    public int baitsLeft(UUID suspect) {
        return baits.getOrDefault(suspect, Set.of()).size();
    }

    /** Digging right up to a fake ore: a strong sign, since it cannot be seen without x-ray. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDigToBait(BlockBreakEvent e) {
        Set<Block> set = baits.get(e.getPlayer().getUniqueId());
        if (set == null || set.isEmpty()) return;
        Block broken = e.getBlock();
        Block hit = null;
        for (Block b : set) {
            if (b.equals(broken) || (Math.abs(b.getX() - broken.getX()) + Math.abs(b.getY() - broken.getY()) + Math.abs(b.getZ() - broken.getZ())) == 1) {
                hit = b;
                break;
            }
        }
        if (hit == null) return;
        set.remove(hit);
        Player p = e.getPlayer();
        p.sendBlockChange(hit.getLocation(), hit.getBlockData());
        plugin.antiCheat().flag(p, "xraybait", 6, hit.getX() + " " + hit.getY() + " " + hit.getZ());
        UUID staffId = baitStaff.get(p.getUniqueId());
        Player staff = staffId == null ? null : Bukkit.getPlayer(staffId);
        if (staff != null) {
            staff.sendMessage(lang().prefixed("tests.bait-found", "player", p.getName(), "left", set.size())
                    .clickEvent(ClickEvent.runCommand("/check " + p.getName())));
        }
    }

    // ---------------------------------------------------------------- screen check

    public void screenshare(Player staff, Player t) {
        boolean freeze = choice(staff, "ss.freeze").equals("enabled");
        if (freeze && !plugin.admin().isFrozen(t.getUniqueId())) plugin.admin().setFrozen(t, true, staff.getName());
        String link = plugin.getConfig().getString("investigation.screenshare-link", "");
        t.showTitle(Title.title(lang().msg("tests.ss-title"), lang().msg("tests.ss-subtitle"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(8), Duration.ofMillis(800))));
        Component msg = lang().prefixed("tests.ss-chat", "staff", staff.getName());
        if (!link.isBlank()) msg = msg.append(Component.space()).append(lang().msg("tests.ss-link").clickEvent(ClickEvent.openUrl(link)));
        t.sendMessage(msg);
        lang().send(staff, "tests.ss-started", "player", t.getName());
        plugin.staffLog(staff.getName(), "test screenshare", t.getName(), freeze ? "frozen" : "");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (bots.containsKey(id)) stopBot(id);
        baits.remove(id);
        baitStaff.remove(id);
    }

    public void stopAll() {
        for (UUID id : List.copyOf(bots.keySet())) stopBot(id);
        for (UUID id : List.copyOf(baits.keySet())) clearBait(id);
    }

    public boolean botRunning(UUID suspect) {
        return bots.containsKey(suspect);
    }

    private static void optional(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // not on this server
        }
    }
}
