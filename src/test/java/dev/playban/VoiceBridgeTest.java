package dev.playban;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.CreateGroupEvent;
import de.maxhenkel.voicechat.api.events.Event;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
import de.maxhenkel.voicechat.api.events.PlayerForcedStateEvent;
import de.maxhenkel.voicechat.api.events.PlayerVisibilityEvent;
import de.maxhenkel.voicechat.api.events.RemoveGroupEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import dev.playban.voice.SimpleVoiceChatBridge;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs PlayBan's connection to Simple Voice Chat against a small voice chat written for this test.
 * It behaves the way the API documentation describes: joins can be cancelled, groups are replaced
 * when rebuilt with the same id, and mute, deafen and visibility are asked through events.
 */
class VoiceBridgeTest {
    private ServerMock server;
    private PlayBanPlugin plugin;
    private PlayerMock admin;
    private PlayerMock troll;
    private PlayerMock bystander;
    private TestVoicechat voicechat;

    // ---------------------------------------------------------------- the test voice chat

    private static final class TestGroup implements Group {
        final UUID id;
        final String name;
        final String password;
        final boolean persistent;
        final boolean hidden;
        final Type type;

        TestGroup(UUID id, String name, String password, boolean persistent, boolean hidden, Type type) {
            this.id = id;
            this.name = name;
            this.password = password;
            this.persistent = persistent;
            this.hidden = hidden;
            this.type = type;
        }

        public String getName() { return name; }
        public boolean hasPassword() { return password != null; }
        public UUID getId() { return id; }
        public boolean isPersistent() { return persistent; }
        public boolean isHidden() { return hidden; }
        public Type getType() { return type; }
    }

    private final class TestVoicechat implements VoicechatServerApi, BukkitVoicechatService, EventRegistration {
        final Map<Class<?>, List<Consumer<Event>>> handlers = new HashMap<>();
        final Map<UUID, TestGroup> groups = new LinkedHashMap<>();
        final Map<UUID, UUID> membership = new HashMap<>();
        final Map<UUID, Boolean> forceMuted = new HashMap<>();
        final Map<UUID, Boolean> forceDisabled = new HashMap<>();
        /** key: observer + ":" + player */
        final Map<String, Boolean> hidden = new HashMap<>();
        String pluginId;

        @Override
        public void registerPlugin(VoicechatPlugin voicePlugin) {
            pluginId = voicePlugin.getPluginId();
            voicePlugin.initialize(this);
            voicePlugin.registerEvents(this);
            fire(VoicechatServerStartedEvent.class, new StartedEvent());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Event> void registerEvent(Class<T> eventClass, Consumer<T> onPacket, int priority) {
            handlers.computeIfAbsent(eventClass, k -> new ArrayList<>()).add((Consumer<Event>) onPacket);
        }

        <T extends Event> T fire(Class<? super T> type, T event) {
            for (Consumer<Event> h : handlers.getOrDefault(type, List.of())) h.accept(event);
            return event;
        }

        class Base implements Event {
            boolean cancelled;
            public boolean isCancellable() { return true; }
            public boolean cancel() { cancelled = true; return true; }
            public boolean isCancelled() { return cancelled; }
            public VoicechatServerApi getVoicechat() { return TestVoicechat.this; }
        }

        class StartedEvent extends Base implements VoicechatServerStartedEvent {
        }

        class GroupEv extends Base implements JoinGroupEvent, CreateGroupEvent, LeaveGroupEvent, RemoveGroupEvent {
            final Group group;
            final VoicechatConnection connection;

            GroupEv(Group group, VoicechatConnection connection) {
                this.group = group;
                this.connection = connection;
            }

            public Group getGroup() { return group; }
            public VoicechatConnection getConnection() { return connection; }
        }

        class ForcedEv extends Base implements PlayerForcedStateEvent {
            final VoicechatConnection connection;
            boolean muted, disabled;

            ForcedEv(VoicechatConnection connection) {
                this.connection = connection;
            }

            public VoicechatConnection getConnection() { return connection; }
            public boolean isForceMuted() { return muted; }
            public boolean isForceDisabled() { return disabled; }
            public void forceMute() { muted = true; }
            public void forceDisable() { disabled = true; }
        }

        class VisibilityEv extends Base implements PlayerVisibilityEvent {
            final ServerPlayer observer, player;
            boolean hiddenNow;

            VisibilityEv(ServerPlayer observer, ServerPlayer player) {
                this.observer = observer;
                this.player = player;
            }

            public ServerPlayer getObserver() { return observer; }
            public ServerPlayer getPlayer() { return player; }
            public boolean isHidden() { return hiddenNow; }
            public void hide() { hiddenNow = true; }
        }

        ServerPlayer wrap(org.bukkit.entity.Player bukkit) {
            return () -> bukkit;
        }

        class Conn implements VoicechatConnection {
            final org.bukkit.entity.Player bukkit;

            Conn(org.bukkit.entity.Player bukkit) {
                this.bukkit = bukkit;
            }

            public Group getGroup() {
                UUID id = membership.get(bukkit.getUniqueId());
                return id == null ? null : groups.get(id);
            }

            public void setGroup(Group group) {
                UUID before = membership.get(bukkit.getUniqueId());
                if (group == null) {
                    if (before == null) return;
                    membership.remove(bukkit.getUniqueId());
                    fire(LeaveGroupEvent.class, new GroupEv(groups.get(before), this));
                    return;
                }
                if (fire(JoinGroupEvent.class, new GroupEv(group, this)).cancelled) return;
                membership.put(bukkit.getUniqueId(), group.getId());
            }

            public void evaluateForcedState() {
                ForcedEv e = fire(PlayerForcedStateEvent.class, new ForcedEv(this));
                forceMuted.put(bukkit.getUniqueId(), e.muted);
                forceDisabled.put(bukkit.getUniqueId(), e.disabled);
            }

            public void evaluateVisibility() {
                for (org.bukkit.entity.Player observer : server.getOnlinePlayers()) {
                    if (observer.equals(bukkit)) continue;
                    VisibilityEv e = fire(PlayerVisibilityEvent.class, new VisibilityEv(wrap(observer), wrap(bukkit)));
                    hidden.put(observer.getUniqueId() + ":" + bukkit.getUniqueId(), e.hiddenNow);
                }
            }

            public ServerPlayer getPlayer() { return wrap(bukkit); }
        }

        @Override
        public VoicechatConnection getConnectionOf(UUID playerUuid) {
            org.bukkit.entity.Player p = server.getPlayer(playerUuid);
            return p == null ? null : new Conn(p);
        }

        /** A player creates a group in the voice chat's own menu. */
        Group playerCreates(PlayerMock creator, String name, String password) {
            TestGroup g = new TestGroup(UUID.randomUUID(), name, password, false, false, Group.Type.NORMAL);
            groups.put(g.id, g);
            fire(CreateGroupEvent.class, new GroupEv(g, new Conn(creator)));
            membership.put(creator.getUniqueId(), g.id);
            return g;
        }

        @Override
        public Group.Builder groupBuilder() {
            return new Group.Builder() {
                UUID id;
                String name, password;
                boolean persistent, hidden;
                Group.Type type = Group.Type.NORMAL;

                public Group.Builder setId(UUID id) { this.id = id; return this; }
                public Group.Builder setName(String name) { this.name = name; return this; }
                public Group.Builder setPassword(String password) { this.password = password; return this; }
                public Group.Builder setPersistent(boolean persistent) { this.persistent = persistent; return this; }
                public Group.Builder setHidden(boolean hidden) { this.hidden = hidden; return this; }
                public Group.Builder setType(Group.Type type) { this.type = type; return this; }

                public Group build() {
                    if (name == null) throw new IllegalStateException("name missing");
                    TestGroup g = new TestGroup(id == null ? UUID.randomUUID() : id, name, password, persistent, hidden, type);
                    groups.put(g.id, g); // same id: replaces the old group
                    return g;
                }
            };
        }

        @Override
        public boolean removeGroup(UUID groupId) {
            TestGroup g = groups.get(groupId);
            if (g == null || !g.persistent || membership.containsValue(groupId)) return false;
            groups.remove(groupId);
            fire(RemoveGroupEvent.class, new GroupEv(g, null));
            return true;
        }

        @Override
        public Group getGroup(UUID groupId) { return groups.get(groupId); }

        @Override
        public Collection<Group> getGroups() { return new ArrayList<>(groups.values()); }
    }

    // ---------------------------------------------------------------- setup

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        voicechat = new TestVoicechat();
        var voicePlugin = MockBukkit.createMockPlugin("voicechat");
        server.getServicesManager().register(BukkitVoicechatService.class, voicechat, voicePlugin, ServicePriority.Normal);
        plugin = MockBukkit.load(PlayBanPlugin.class);
        admin = server.addPlayer("Admin");
        admin.setOp(true);
        troll = server.addPlayer("Troll");
        bystander = server.addPlayer("Bystander");
        server.getScheduler().performTicks(30);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void playBanConnectsItselfWhenTheVoiceChatIsInstalled() {
        assertEquals("playban", voicechat.pluginId);
        assertTrue(plugin.voice().available());
        for (Class<?> event : List.of(JoinGroupEvent.class, CreateGroupEvent.class, LeaveGroupEvent.class, RemoveGroupEvent.class,
                PlayerForcedStateEvent.class, PlayerVisibilityEvent.class)) {
            assertTrue(voicechat.handlers.containsKey(event), event.getSimpleName() + " is listened to");
        }
    }

    @Test
    void groupsAndMembersAreRead() {
        Group g = voicechat.playerCreates(troll, "Trolls only", "pw");
        voicechat.getConnectionOf(bystander.getUniqueId()).setGroup(g);
        var list = plugin.voice().groups();
        assertEquals(1, list.size());
        assertEquals("Trolls only", list.get(0).name());
        assertTrue(list.get(0).password());
        assertEquals(2, list.get(0).members().size());
        assertEquals(troll.getUniqueId(), plugin.voice().owner(g.getId()), "the creator is remembered through the create event");
        assertEquals(g.getId(), plugin.voice().groupOf(bystander.getUniqueId()));
    }

    @Test
    void kickBanMuteAndDeafenReachTheVoiceChat() {
        Group g = voicechat.playerCreates(troll, "Trolls only", null);
        VoicechatConnection by = voicechat.getConnectionOf(bystander.getUniqueId());
        by.setGroup(g);

        plugin.voice().setMuted(bystander.getUniqueId(), true, "Admin");
        assertTrue(voicechat.forceMuted.get(bystander.getUniqueId()), "the voice chat force-mutes the player");
        plugin.voice().setDeaf(bystander.getUniqueId(), true, "Admin");
        assertTrue(voicechat.forceDisabled.get(bystander.getUniqueId()));
        plugin.voice().setMuted(bystander.getUniqueId(), false, "Admin");
        assertFalse(voicechat.forceMuted.get(bystander.getUniqueId()));
        assertTrue(voicechat.forceDisabled.get(bystander.getUniqueId()), "deafen stays until it is undone");

        plugin.voice().kick(g.getId(), bystander.getUniqueId(), "Admin");
        assertNull(by.getGroup());
        server.getScheduler().performTicks(2);
        assertFalse(voicechat.forceDisabled.get(bystander.getUniqueId()), "leaving the group lifts it");

        plugin.voice().ban(g.getId(), bystander.getUniqueId(), "Admin");
        by.setGroup(g);
        assertNull(by.getGroup(), "the join of a banned player is cancelled");
        voicechat.getConnectionOf(admin.getUniqueId()).setGroup(g);
        assertNotNull(voicechat.getConnectionOf(admin.getUniqueId()).getGroup());
    }

    @Test
    void unseenTeamMemberIsHiddenFromPlayersOnly() {
        Group g = voicechat.playerCreates(troll, "Trolls only", null);
        plugin.voice().join(admin, g.getId(), true);
        assertEquals(g.getId(), voicechat.membership.get(admin.getUniqueId()), "really a member, so talking and listening work");
        assertTrue(voicechat.hidden.get(troll.getUniqueId() + ":" + admin.getUniqueId()), "hidden from the player");
        PlayerMock mod = server.addPlayer("Mod");
        mod.setOp(true);
        server.getScheduler().performTicks(30);
        plugin.voice().setInvisible(admin, false);
        plugin.voice().setInvisible(admin, true);
        assertFalse(voicechat.hidden.get(mod.getUniqueId() + ":" + admin.getUniqueId()), "another team member still sees them");
        plugin.voice().setInvisible(admin, false);
        assertFalse(voicechat.hidden.get(troll.getUniqueId() + ":" + admin.getUniqueId()));
    }

    @Test
    void passwordIsChangedByReplacingTheGroupAndMembersStay() {
        Group g = voicechat.playerCreates(troll, "Trolls only", null);
        voicechat.getConnectionOf(bystander.getUniqueId()).setGroup(g);
        assertTrue(plugin.voice().setPassword(g.getId(), "secret"));
        Group now = voicechat.getGroup(g.getId());
        assertTrue(now.hasPassword());
        assertEquals("Trolls only", now.getName(), "name is kept");
        assertEquals(2, plugin.voice().group(g.getId()).members().size(), "members are still inside");
        assertEquals(1, voicechat.groups.size(), "no second group appeared");

        assertTrue(plugin.voice().setPassword(g.getId(), null));
        assertFalse(voicechat.getGroup(g.getId()).hasPassword());
    }

    @Test
    void closingEmptiesTheGroup() {
        Group g = voicechat.playerCreates(troll, "Trolls only", null);
        voicechat.getConnectionOf(bystander.getUniqueId()).setGroup(g);
        plugin.voice().close(g.getId(), "Admin");
        assertFalse(voicechat.membership.containsValue(g.getId()));
    }

    @Test
    void ticketVoiceGroupIsCreatedInTheVoiceChat() {
        bystander.performCommand("ticket Troll griefed");
        var t = plugin.tickets().get(1);
        plugin.tickets().accept(t, admin);
        assertTrue(plugin.tickets().voice(t, admin));
        UUID group = voicechat.membership.get(admin.getUniqueId());
        assertNotNull(group);
        assertEquals(group, voicechat.membership.get(bystander.getUniqueId()));
        assertTrue(voicechat.getGroup(group).isHidden());
        assertTrue(voicechat.getGroup(group).hasPassword());
        assertTrue(voicechat.forceMuted.get(bystander.getUniqueId()), "silent until they agree");
        bystander.performCommand("voice speak");
        assertFalse(voicechat.forceMuted.get(bystander.getUniqueId()));
    }
}
