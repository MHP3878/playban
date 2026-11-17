package dev.staffcore;

import dev.staffcore.gui.Items;
import dev.staffcore.gui.Menu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Harmless jokes for admins: chicken rain, a fake stash, a fake op message and more.
 * Nothing here hurts the player, takes items or changes the world for anybody else.
 */
public final class TrollManager implements Listener, TabExecutor {
    public record Troll(String id, Material icon) {}

    public static final List<Troll> TROLLS = List.of(
            new Troll("chickens", Material.EGG),
            new Troll("launch", Material.FIREWORK_ROCKET),
            new Troll("creeper", Material.CREEPER_HEAD),
            new Troll("explosion", Material.TNT),
            new Troll("stash", Material.CHEST),
            new Troll("fakeop", Material.COMMAND_BLOCK),
            new Troll("spin", Material.COMPASS),
            new Troll("lightning", Material.LIGHTNING_ROD),
            new Troll("demo", Material.PAINTING),
            new Troll("sneeze", Material.SLIME_BALL));

    private final StaffCorePlugin plugin;
    private final org.bukkit.NamespacedKey key;

    public TrollManager(StaffCorePlugin plugin) {
        this.plugin = plugin;
        this.key = new org.bukkit.NamespacedKey(plugin, "troll");
    }

    private Lang lang() {
        return plugin.lang();
    }

    public void menu(Player staff, Player target) {
        Menu m = new Menu(4, lang().msg("troll.title", "player", target.getName()));
        m.set(4, Items.head(target, lang().msg("menu.ticket.person.name", "player", target.getName()), lang().lore("troll.harmless")));
        int slot = 10;
        for (Troll t : TROLLS) {
            if (slot % 9 == 8) slot += 2;
            m.set(slot++, Items.of(t.icon(), lang().msg("troll." + t.id() + ".name"), lang().lore("troll." + t.id() + ".lore")), e -> {
                if (!target.isOnline()) {
                    staff.closeInventory();
                    return;
                }
                run(staff, target, t.id());
            });
        }
        m.set(31, Items.of(Material.BARRIER, lang().msg("menu.back"), List.of()), e -> staff.closeInventory());
        m.fill().open(staff);
    }

    public boolean run(CommandSender staff, Player target, String id) {
        switch (id) {
            case "chickens" -> chickens(target);
            case "launch" -> {
                target.setVelocity(new Vector(0, 1.6, 0));
                target.playSound(target.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
                // no fall damage from a joke
                noFall.add(target.getUniqueId());
                Bukkit.getScheduler().runTaskLater(plugin, () -> noFall.remove(target.getUniqueId()), 20L * 10);
            }
            case "creeper" -> target.playSound(target.getLocation(), Sound.ENTITY_CREEPER_PRIMED, 1.2f, 1f);
            case "explosion" -> {
                target.playSound(target.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
                particle(target, Particle.EXPLOSION_EMITTER, target.getLocation(), 1);
            }
            case "stash" -> stash(target);
            case "fakeop" -> target.sendMessage(Component.text("[Server: Made " + target.getName() + " a server operator]",
                    NamedTextColor.GRAY).decorate(TextDecoration.ITALIC));
            case "spin" -> spin(target);
            case "lightning" -> {
                try {
                    target.getWorld().strikeLightningEffect(target.getLocation());
                } catch (RuntimeException ignored) {
                    // only the effect
                }
            }
            case "demo" -> target.showDemoScreen();
            case "sneeze" -> {
                target.playSound(target.getLocation(), Sound.ENTITY_PANDA_SNEEZE, 1.5f, 1f);
                particle(target, Particle.ITEM_SLIME, target.getEyeLocation(), 25);
            }
            default -> {
                return false;
            }
        }
        lang().send(staff, "troll.done", "troll", lang().raw("troll." + id + ".name"), "player", target.getName());
        plugin.staffLog(staff.getName(), "troll", target.getName(), id);
        return true;
    }

    private void particle(Player viewer, Particle particle, Location at, int count) {
        try {
            viewer.spawnParticle(particle, at, count, 0.3, 0.3, 0.3, 0.05);
        } catch (RuntimeException ignored) {
            // only the effect
        }
    }

    /** Chickens fall from the sky around the player and are gone again after a few seconds, without drops. */
    private void chickens(Player target) {
        List<Entity> spawned = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int delay = i * 3;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!target.isOnline()) return;
                Location at = target.getLocation().add(Math.random() * 6 - 3, 8, Math.random() * 6 - 3);
                try {
                    Chicken c = target.getWorld().spawn(at, Chicken.class, ch -> {
                        ch.setPersistent(false);
                        ch.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
                    });
                    spawned.add(c);
                } catch (RuntimeException ignored) {
                    // a world that does not allow it
                }
                target.playSound(target.getLocation(), Sound.ENTITY_CHICKEN_AMBIENT, 1f, (float) (0.8 + Math.random() * 0.6));
            }, delay);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> spawned.forEach(Entity::remove), 20L * 8);
    }

    private final java.util.Set<java.util.UUID> noFall = new java.util.HashSet<>();

    @EventHandler(ignoreCancelled = true)
    public void onFall(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL && noFall.remove(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    /** Troll chickens never drop anything, so the joke cannot be farmed. */
    @EventHandler
    public void onChickenDeath(EntityDeathEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
    }

    /** A "stash" of chests and diamond blocks appears in front of the player, only for them, and is gone when they touch it. */
    private void stash(Player target) {
        Location base = target.getLocation().add(target.getLocation().getDirection().setY(0).normalize().multiply(3)).getBlock().getLocation();
        BlockData chest = Bukkit.createBlockData(Material.CHEST);
        BlockData diamonds = Bukkit.createBlockData(Material.DIAMOND_BLOCK);
        BlockData shulker = Bukkit.createBlockData(Material.PURPLE_SHULKER_BOX);
        List<Location> spots = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Location l = base.clone().add(dx, 0, dz);
                if (l.getBlock().getType().isSolid()) continue;
                spots.add(l);
                target.sendBlockChange(l, (dx + dz) % 2 == 0 ? chest : (dx == 0 ? shulker : diamonds));
            }
        }
        target.playSound(target.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!target.isOnline()) return;
            for (Location l : spots) if (l.getWorld() == target.getWorld()) target.sendBlockChange(l, l.getBlock().getBlockData());
        }, 20L * 30);
    }

    /** Turns the player's view around a few times. */
    private void spin(Player target) {
        for (int i = 1; i <= 16; i++) {
            int step = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (target.isOnline()) target.setRotation(target.getLocation().getYaw() + 45f, target.getLocation().getPitch());
            }, step);
        }
    }

    // ---------------------------------------------------------------- /troll

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            lang().send(sender, "troll.usage");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            lang().send(sender, "admin.not-online", "player", args[0]);
            return true;
        }
        if (target.hasPermission("staffcore.exempt") && !sender.hasPermission("staffcore.exempt.bypass") && target != sender) {
            lang().send(sender, "error.exempt", "player", target.getName());
            return true;
        }
        if (args.length >= 2) {
            if (!run(sender, target, args[1].toLowerCase())) lang().send(sender, "troll.usage");
        } else if (sender instanceof Player p) menu(p, target);
        else lang().send(sender, "troll.usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[0].toLowerCase())) out.add(p.getName());
        } else if (args.length == 2) {
            for (Troll t : TROLLS) if (t.id().startsWith(args[1].toLowerCase())) out.add(t.id());
        }
        return out;
    }
}
