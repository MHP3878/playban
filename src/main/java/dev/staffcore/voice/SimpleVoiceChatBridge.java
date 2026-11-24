package dev.staffcore.voice;

import dev.staffcore.StaffCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Connects PlayBan to the plugin "Simple Voice Chat" (voicechat) through its public API.
 * Everything goes through reflection so PlayBan also runs on servers without the voice chat,
 * and an API call that does not exist in an older voice chat version only switches that one feature off.
 */
public final class SimpleVoiceChatBridge implements VoiceBackend {
    private static final String API = "de.maxhenkel.voicechat.api.";
    private static final String CONNECTION = API + "VoicechatConnection";
    private static final String SERVER_API = API + "VoicechatServerApi";
    private static final String GROUP = API + "Group";
    private static final String BUILDER = API + "Group$Builder";

    private final StaffCorePlugin plugin;
    private final VoiceManager manager;
    private final Map<String, Method> methods = new ConcurrentHashMap<>();
    private final java.util.Set<String> warned = ConcurrentHashMap.newKeySet();
    private ClassLoader loader;
    private volatile Object api;
    private boolean registered;

    public SimpleVoiceChatBridge(StaffCorePlugin plugin, VoiceManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    private Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

    /** Calls a method of an API interface. Returns null when it is missing or fails, and says so once in the log. */
    private Object call(String iface, Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) return null;
        String key = iface + "#" + name + "/" + types.length;
        try {
            Method m = methods.get(key);
            if (m == null) {
                m = type(iface).getMethod(name, types);
                methods.put(key, m);
            }
            return m.invoke(target, args);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            if (warned.add(key)) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                plugin.getLogger().warning("Voice chat: '" + name + "' is not available in this Simple Voice Chat version (" + cause + ").");
            }
            return null;
        }
    }

    private Object call(String iface, Object target, String name) {
        return call(iface, target, name, new Class<?>[0]);
    }

    /** Hooks into Simple Voice Chat. Returns false when it is not installed. */
    public boolean register() {
        Plugin voicechat = Bukkit.getPluginManager().getPlugin("voicechat");
        if (voicechat == null) return false;
        try {
            loader = voicechat.getClass().getClassLoader();
            Class<?> serviceType = type(API + "BukkitVoicechatService");
            Object service = Bukkit.getServicesManager().load(serviceType);
            if (service == null) return false;
            Class<?> pluginType = type(API + "VoicechatPlugin");
            Object voicePlugin = Proxy.newProxyInstance(loader, new Class<?>[]{pluginType}, (proxy, method, args) -> switch (method.getName()) {
                case "getPluginId" -> "staffcore";
                case "registerEvents" -> {
                    registerEvents(args[0]);
                    yield null;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "StaffCore voice chat hook";
                default -> null;
            });
            serviceType.getMethod("registerPlugin", pluginType).invoke(service, voicePlugin);
            registered = true;
            plugin.getLogger().info("Connected to Simple Voice Chat.");
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            plugin.getLogger().warning("Simple Voice Chat was found, but StaffCore could not connect to it: " + e);
            return false;
        }
    }

    private void on(Object registration, String event, Consumer<Object> handler) {
        try {
            Class<?> eventType = type(API + "events." + event);
            type(API + "events.EventRegistration").getMethod("registerEvent", Class.class, Consumer.class)
                    .invoke(registration, eventType, (Consumer<Object>) e -> {
                        try {
                            if (api == null) api = call(API + "events.ServerEvent", e, "getVoicechat");
                            handler.accept(e);
                        } catch (RuntimeException ex) {
                            if (warned.add("event:" + event)) plugin.getLogger().warning("Voice chat event " + event + " failed: " + ex);
                        }
                    });
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            plugin.getLogger().warning("Voice chat: this Simple Voice Chat version has no " + event + ", a part of the voice moderation is switched off.");
        }
    }

    private void registerEvents(Object registration) {
        String events = API + "events.";
        on(registration, "VoicechatServerStartedEvent", e -> {});
        on(registration, "JoinGroupEvent", e -> {
            UUID player = playerOf(call(events + "GroupEvent", e, "getConnection"));
            UUID group = idOf(call(events + "GroupEvent", e, "getGroup"));
            if (player != null && group != null && !manager.mayJoin(player, group)) call(events + "Event", e, "cancel");
        });
        on(registration, "CreateGroupEvent", e -> {
            UUID group = idOf(call(events + "GroupEvent", e, "getGroup"));
            if (group != null) manager.created(playerOf(call(events + "GroupEvent", e, "getConnection")), group);
        });
        on(registration, "LeaveGroupEvent", e -> {
            UUID player = playerOf(call(events + "GroupEvent", e, "getConnection"));
            UUID group = idOf(call(events + "GroupEvent", e, "getGroup"));
            if (player != null) manager.left(player, group);
        });
        on(registration, "RemoveGroupEvent", e -> {
            UUID group = idOf(call(events + "GroupEvent", e, "getGroup"));
            if (group != null) manager.removed(group);
        });
        // mute, deafen and /jammer: what a player says is dropped before it is sent on, and what would
        // reach a player who may hear nobody is dropped too. Their own voice chat looks normal to them.
        on(registration, "MicrophonePacketEvent", e -> {
            if (!manager.anyBlock()) return;
            UUID speaker = playerOf(call(events + "PacketEvent", e, "getSenderConnection"));
            if (manager.dropMic(speaker)) call(events + "Event", e, "cancel");
        });
        for (String sound : List.of("EntitySoundPacketEvent", "LocationalSoundPacketEvent", "StaticSoundPacketEvent")) {
            on(registration, sound, e -> {
                if (!manager.anyBlock()) return;
                UUID listener = playerOf(call(events + "PacketEvent", e, "getReceiverConnection"));
                UUID speaker = playerOf(call(events + "PacketEvent", e, "getSenderConnection"));
                if (manager.dropSound(speaker, listener)) call(events + "Event", e, "cancel");
            });
        }
    }

    /** The voice chat wraps the server's own player object; on Paper that is a Bukkit player. */
    private UUID bukkitId(Object voicePlayer) {
        return call(API + "Player", voicePlayer, "getPlayer") instanceof Player p ? p.getUniqueId() : null;
    }

    private UUID playerOf(Object connection) {
        return bukkitId(call(CONNECTION, connection, "getPlayer"));
    }

    private UUID idOf(Object group) {
        return call(GROUP, group, "getId") instanceof UUID id ? id : null;
    }

    private Object connection(UUID player) {
        return call(SERVER_API, api, "getConnectionOf", new Class<?>[]{UUID.class}, player);
    }

    private Object groupObject(UUID id) {
        return call(SERVER_API, api, "getGroup", new Class<?>[]{UUID.class}, id);
    }

    @Override
    public boolean available() {
        return registered && api != null;
    }

    @Override
    public UUID groupOf(UUID player) {
        return idOf(call(CONNECTION, connection(player), "getGroup"));
    }

    @Override
    public List<GroupInfo> groups() {
        List<GroupInfo> out = new ArrayList<>();
        if (!(call(SERVER_API, api, "getGroups") instanceof Collection<?> groups)) return out;
        for (Object g : groups) {
            UUID id = idOf(g);
            if (id == null) continue;
            List<UUID> members = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) if (id.equals(groupOf(p.getUniqueId()))) members.add(p.getUniqueId());
            out.add(new GroupInfo(id, String.valueOf(call(GROUP, g, "getName")),
                    Boolean.TRUE.equals(call(GROUP, g, "hasPassword")), Boolean.TRUE.equals(call(GROUP, g, "isHidden")),
                    Boolean.TRUE.equals(call(GROUP, g, "isPersistent")), members));
        }
        return out;
    }

    @Override
    public boolean setGroup(UUID player, UUID group) {
        Object connection = connection(player);
        if (connection == null) return false;
        Object target = group == null ? null : groupObject(group);
        if (group != null && target == null) return false;
        try {
            call(CONNECTION, connection, "setGroup", new Class<?>[]{type(GROUP)}, target);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private Object build(UUID id, String name, String password, boolean hidden, boolean persistent, Object groupType) {
        try {
            Object b = call(SERVER_API, api, "groupBuilder");
            if (b == null) return null;
            if (id != null) call(BUILDER, b, "setId", new Class<?>[]{UUID.class}, id);
            call(BUILDER, b, "setName", new Class<?>[]{String.class}, name);
            call(BUILDER, b, "setPassword", new Class<?>[]{String.class}, password);
            call(BUILDER, b, "setHidden", new Class<?>[]{boolean.class}, hidden);
            call(BUILDER, b, "setPersistent", new Class<?>[]{boolean.class}, persistent);
            if (groupType != null) call(BUILDER, b, "setType", new Class<?>[]{type(GROUP + "$Type")}, groupType);
            return call(BUILDER, b, "build");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @Override
    public UUID createGroup(String name, String password, boolean hidden) {
        return idOf(build(null, name, password, hidden, false, null));
    }

    @Override
    public boolean setPassword(UUID group, String password) {
        Object old = groupObject(group);
        if (old == null) return false;
        List<UUID> members = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (group.equals(groupOf(p.getUniqueId()))) members.add(p.getUniqueId());
        // building a group with the id of an existing one replaces it, which is the only way to change the password
        Object rebuilt = build(group, String.valueOf(call(GROUP, old, "getName")), password,
                Boolean.TRUE.equals(call(GROUP, old, "isHidden")), Boolean.TRUE.equals(call(GROUP, old, "isPersistent")),
                call(GROUP, old, "getType"));
        if (rebuilt == null) return false;
        for (UUID member : members) setGroup(member, group);
        return true;
    }

    @Override
    public void removeGroup(UUID group) {
        call(SERVER_API, api, "removeGroup", new Class<?>[]{UUID.class}, group);
    }

    @Override
    public void refresh(UUID player) {
        // nothing to do: every voice packet is judged as it comes, so a change counts at once
    }
}
