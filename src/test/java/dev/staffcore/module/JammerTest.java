package dev.staffcore.module;

import dev.staffcore.StaffCorePlugin;
import dev.staffcore.gui.Menu;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Material;
import org.bukkit.entity.Cow;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** /jammer: the three levels, talking with a jammed player, the menu, and what level 2 holds back. */
class JammerTest {
    private ServerMock server;
    private StaffCorePlugin plugin;
    private PlayerMock admin, troll, bystander;
    private final Set<String> lastViewers = new HashSet<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StaffCorePlugin.class);
        admin = server.addPlayer("Admin");
        admin.setOp(true);
        troll = server.addPlayer("Troll");
        bystander = server.addPlayer("Bystander");
        server.getScheduler().performTicks(30);
        plugin.getConfig().set("admin-mode.required", false);
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
            public void on(AsyncChatEvent e) {
                lastViewers.clear();
                if (e.isCancelled()) return;
                for (var a : e.viewers()) if (a instanceof org.bukkit.entity.Player v) lastViewers.add(v.getName());
            }
        }, plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void say(PlayerMock p, String text) {
        p.chat(text);
        server.getScheduler().waitAsyncEventsFinished();
        server.getScheduler().performTicks(2);
    }

    private int slotOf(PlayerMock viewer, Material m) {
        var inv = viewer.getOpenInventory().getTopInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() == m) return i;
        }
        return fail("no " + m + " in the open menu");
    }

    @Test
    void levelsAreChosenByCommandAndKept() {
        Jammer j = plugin.jammer();
        assertTrue(admin.performCommand("jammer Troll 3 1h"));
        assertEquals(3, j.level(troll.getUniqueId()));
        assertTrue(admin.performCommand("jammer Troll geisterstadt"));
        assertEquals(3, j.level(troll.getUniqueId()));
        assertTrue(admin.performCommand("jammer Troll 2"));
        assertEquals(2, j.level(troll.getUniqueId()));
        j.load();
        assertEquals(2, j.level(troll.getUniqueId()), "saved with its level");
        assertTrue(admin.performCommand("jammer out Troll"));
        assertEquals(0, j.level(troll.getUniqueId()));
    }

    @Test
    void theGhostTownHidesEveryoneAndEveryAnimal() {
        Cow cow = (Cow) troll.getWorld().spawnEntity(troll.getLocation().add(3, 0, 0), EntityType.COW);
        assertTrue(troll.canSee(bystander));
        assertTrue(admin.performCommand("jammer Troll 3"));
        server.getScheduler().performTicks(2);
        assertFalse(troll.canSee(bystander), "nobody is there");
        assertFalse(troll.canSee(admin));
        assertFalse(troll.canSee(cow), "no animals");
        assertTrue(bystander.canSee(troll), "the others still see them");
        Cow later = (Cow) troll.getWorld().spawnEntity(troll.getLocation().add(-3, 0, 0), EntityType.COW);
        server.getScheduler().performTicks(20);
        assertFalse(troll.canSee(later), "new animals neither");
        // monsters do not come for them, and nobody hits anybody
        var hit = new EntityDamageByEntityEvent(bystander, troll, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK).build(), 3);
        server.getPluginManager().callEvent(hit);
        assertTrue(hit.isCancelled());
        // out: everything is back
        assertTrue(admin.performCommand("jammer out Troll"));
        server.getScheduler().performTicks(2);
        assertTrue(troll.canSee(bystander));
        assertTrue(troll.canSee(cow));
        assertTrue(troll.canSee(later));
    }

    @Test
    void aTeamMemberCanTalkWithAJammedPlayerEvenWhenVanished() {
        assertTrue(admin.performCommand("jammer Troll 3"));
        plugin.punishments().setVanished(admin, true);
        server.getScheduler().performTicks(2);
        assertFalse(troll.canSee(admin));
        assertTrue(admin.performCommand("jammer talk Troll"));
        server.getScheduler().performTicks(2);
        assertTrue(troll.canSee(admin), "they see the team member they talk with");
        assertFalse(bystander.canSee(admin), "the others still do not");
        assertFalse(admin.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY), "visible to them, not see-through");
        assertFalse(troll.canSee(bystander), "and still nobody else");

        say(admin, "can you hear me?");
        assertEquals(Set.of("Admin", "Troll"), lastViewers, "what the team member writes goes to them only");
        say(troll, "who is there?");
        assertTrue(lastViewers.contains("Admin"));
        assertFalse(lastViewers.contains("Bystander"));
        var whisper = new org.bukkit.event.player.PlayerCommandPreprocessEvent(troll, "/msg Admin hello");
        server.getPluginManager().callEvent(whisper);
        assertFalse(whisper.isCancelled(), "private messages between the two go through");

        assertTrue(admin.performCommand("jammer talk Troll"));
        server.getScheduler().performTicks(2);
        assertFalse(troll.canSee(admin), "talk ended: gone again");
        assertTrue(admin.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY), "vanish as before");
        say(admin, "only the team");
        assertTrue(lastViewers.isEmpty(), "vanished: back to team-only chat, not to them");
        plugin.punishments().setVanished(admin, false);
        say(admin, "back to everyone");
        assertTrue(lastViewers.contains("Bystander"));
        assertFalse(lastViewers.contains("Troll"));
    }

    @Test
    void frozenPlayersCannotHitOrBeHit() {
        assertTrue(admin.performCommand("jammer Troll 2"));
        var hit = new EntityDamageByEntityEvent(troll, bystander, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK).build(), 3);
        server.getPluginManager().callEvent(hit);
        assertTrue(hit.isCancelled());
        assertTrue(troll.canSee(bystander), "level 2: still there, only standing still");
    }

    @Test
    void everythingIsInTheMenu() {
        assertTrue(admin.performCommand("jammer"));
        assertInstanceOf(Menu.class, admin.getOpenInventory().getTopInventory().getHolder());
        // the player page: level 2 with a click
        assertTrue(admin.performCommand("jammer Troll"));
        assertInstanceOf(Menu.class, admin.getOpenInventory().getTopInventory().getHolder(), "a name alone opens their page");
        admin.simulateInventoryClick(slotOf(admin, Material.CLOCK));
        admin.simulateInventoryClick(slotOf(admin, Material.BLUE_ICE));
        assertEquals(2, plugin.jammer().level(troll.getUniqueId()));
        assertNotEquals(0, plugin.jammer().entry(troll.getUniqueId()).until(), "with the chosen time (10 minutes)");
        admin.simulateInventoryClick(slotOf(admin, Material.GRAY_DYE));
        assertTrue(plugin.jammer().inContact(admin.getUniqueId(), troll.getUniqueId()), "talk with a click");
        admin.simulateInventoryClick(slotOf(admin, Material.MILK_BUCKET));
        assertEquals(0, plugin.jammer().level(troll.getUniqueId()), "and out again");
        assertFalse(plugin.jammer().inContact(admin.getUniqueId(), troll.getUniqueId()));
        // and in the admin panel
        assertTrue(admin.performCommand("admin"));
        admin.simulateInventoryClick(slotOf(admin, Material.SCULK_SHRIEKER));
        assertInstanceOf(Menu.class, admin.getOpenInventory().getTopInventory().getHolder());
    }

    // ---------------------------------------------------------------- what level 2 holds back

    /** Stand-ins with the names of the game's packets. */
    static final class ClientboundMoveEntityPacket {
        final int entityId;

        ClientboundMoveEntityPacket(int entityId) {
            this.entityId = entityId;
        }
    }

    record ClientboundEntityPositionSyncPacket(int id, double x) {}

    static final class ClientboundSystemChatPacket {
        final int something = 7;
    }

    static final class ClientboundBundlePacket {
        final List<Object> parts;

        public ClientboundBundlePacket(Iterable<Object> parts) {
            this.parts = new java.util.ArrayList<>();
            parts.forEach(this.parts::add);
        }

        public Iterable<Object> subPackets() {
            return parts;
        }
    }

    @Test
    void levelTwoHoldsBackOnlyOtherPlayersMovement() {
        Jammer j = plugin.jammer();
        JamFreeze f = j.freeze();
        f.refreshIds();
        var move = new ClientboundMoveEntityPacket(bystander.getEntityId());
        var own = new ClientboundMoveEntityPacket(troll.getEntityId());
        var cow = new ClientboundMoveEntityPacket(999_999);
        var sync = new ClientboundEntityPositionSyncPacket(bystander.getEntityId(), 1.0);
        var chat = new ClientboundSystemChatPacket();
        assertSame(move, f.filter(troll.getUniqueId(), move), "not jammed: everything goes");
        assertTrue(admin.performCommand("jammer Troll 1"));
        assertSame(move, f.filter(troll.getUniqueId(), move), "level 1: everything moves");
        assertTrue(admin.performCommand("jammer Troll 2"));
        assertNull(f.filter(troll.getUniqueId(), move), "level 2: the others stand still");
        assertNull(f.filter(troll.getUniqueId(), sync));
        assertSame(own, f.filter(troll.getUniqueId(), own), "their own movement goes on");
        assertSame(cow, f.filter(troll.getUniqueId(), cow), "animals move");
        assertSame(chat, f.filter(troll.getUniqueId(), chat));
        assertSame(move, f.filter(bystander.getUniqueId(), move), "nobody else is touched");
        var bundle = new ClientboundBundlePacket(List.of(move, chat));
        Object out = f.filter(troll.getUniqueId(), bundle);
        assertInstanceOf(ClientboundBundlePacket.class, out);
        assertEquals(List.of(chat), ((ClientboundBundlePacket) out).parts, "a bundle loses only the movement");
        // the team member they talk with moves
        var adminMove = new ClientboundMoveEntityPacket(admin.getEntityId());
        assertNull(f.filter(troll.getUniqueId(), adminMove));
        assertTrue(admin.performCommand("jammer talk Troll"));
        assertSame(adminMove, f.filter(troll.getUniqueId(), adminMove));
    }
}
