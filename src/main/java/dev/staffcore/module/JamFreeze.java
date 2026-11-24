package dev.staffcore.module;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import java.util.logging.Logger;

/**
 * /jammer level 2: for the jammed player every other player stands still where they were, as if
 * everybody had left their keyboard. The server keeps sending the jammed player's game everything
 * else; only the packets that move, turn or animate other players are held back on the way out.
 * When the freeze ends, the players are sent again at their real places.
 *
 * <p>This works on the server's own network channel (Paper has no API for it); if a future server
 * version changes it, level 2 quietly works like level 1, and the log says so once.
 */
final class JamFreeze {
    private static final String NAME = "staffcore_jammer";
    /** Packets that move, turn or animate an entity (by the end of their class name). */
    private static final List<String> MOVING = List.of("ClientboundMoveEntityPacket", "ClientboundTeleportEntityPacket",
            "ClientboundEntityPositionSyncPacket", "ClientboundRotateHeadPacket", "ClientboundSetEntityMotionPacket",
            "ClientboundAnimatePacket", "ClientboundSetEntityDataPacket", "ClientboundSetEquipmentPacket",
            "ClientboundHurtAnimationPacket", "ClientboundDamageEventPacket", "ClientboundEntityEventPacket");

    private enum Kind { MOVING, BUNDLE, OTHER }

    private final Jammer jammer;
    private final Logger log;
    /** entity id of each online player: kept up to date on the main thread, read on the network threads */
    private final Map<Integer, UUID> players = new ConcurrentHashMap<>();
    private final Map<Class<?>, Kind> kinds = new ConcurrentHashMap<>();
    private final Map<Class<?>, ToIntFunction<Object>> ids = new ConcurrentHashMap<>();
    private final Set<UUID> attached = ConcurrentHashMap.newKeySet();
    private volatile boolean warned;

    JamFreeze(Jammer jammer, Logger log) {
        this.jammer = jammer;
        this.log = log;
    }

    /** Who is who on the network: called on joins, respawns, quits and once a second. */
    void refreshIds() {
        Map<Integer, UUID> now = new java.util.HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) now.put(p.getEntityId(), p.getUniqueId());
        players.keySet().retainAll(now.keySet());
        players.putAll(now);
    }

    // ---------------------------------------------------------------- the network channel

    private static Object fieldOfType(Object owner, java.util.function.Predicate<Class<?>> type) throws IllegalAccessException {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !type.test(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(owner);
                if (v != null) return v;
            }
        }
        return null;
    }

    /** The player's network channel: player → server player → its connection → the channel. */
    private Channel channel(Player p) {
        try {
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            Object listener = fieldOfType(handle, t -> t.getSimpleName().equals("ServerGamePacketListenerImpl"));
            if (listener == null) return null;
            Object connection = fieldOfType(listener, t -> t.getName().equals("net.minecraft.network.Connection"));
            if (connection == null) return null;
            return (Channel) fieldOfType(connection, Channel.class::isAssignableFrom);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Starts holding back other players' movement for this player (nothing changes until they are level 2). */
    void attach(Player p) {
        if (attached.contains(p.getUniqueId())) return;
        Channel ch = channel(p);
        if (ch == null) {
            if (!warned && p.getClass().getName().contains("Craft")) {
                warned = true;
                log.warning("Jammer: this server version does not let StaffCore hold back movement; level 2 works like level 1 here.");
            }
            return;
        }
        UUID id = p.getUniqueId();
        attached.add(id);
        ch.eventLoop().execute(() -> {
            try {
                if (ch.pipeline().get(NAME) != null) return;
                if (ch.pipeline().get("packet_handler") != null) ch.pipeline().addBefore("packet_handler", NAME, new Handler(id));
                else ch.pipeline().addLast(NAME, new Handler(id));
            } catch (RuntimeException e) {
                attached.remove(id);
            }
        });
    }

    void detach(Player p) {
        if (!attached.remove(p.getUniqueId())) return;
        Channel ch = channel(p);
        if (ch == null) return;
        ch.eventLoop().execute(() -> {
            try {
                if (ch.pipeline().get(NAME) != null) ch.pipeline().remove(NAME);
            } catch (RuntimeException ignored) {
                // the connection is closing anyway
            }
        });
    }

    void forget(UUID id) {
        attached.remove(id);
    }

    void detachAll() {
        for (Player p : Bukkit.getOnlinePlayers()) detach(p);
    }

    // ---------------------------------------------------------------- what is held back

    private Kind kind(Class<?> c) {
        String name = c.getName();
        if (name.endsWith("ClientboundBundlePacket")) return Kind.BUNDLE;
        for (String m : MOVING) if (name.contains(m)) return Kind.MOVING;
        return Kind.OTHER;
    }

    /** Reads the entity id of a moving packet: a record's id, a field "id"/"entityId"/"entity", or the first number. */
    private ToIntFunction<Object> idReader(Class<?> c) {
        if (c.isRecord()) {
            for (RecordComponent rc : c.getRecordComponents()) {
                if (rc.getType() == int.class && (rc.getName().equals("id") || rc.getName().equals("entityId"))) {
                    Method m = rc.getAccessor();
                    m.setAccessible(true);
                    return o -> {
                        try {
                            return (int) m.invoke(o);
                        } catch (ReflectiveOperationException e) {
                            return -1;
                        }
                    };
                }
            }
        }
        Field first = null, named = null;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
                if (first == null) first = f;
                if (named == null && (f.getName().equals("id") || f.getName().equals("entityId") || f.getName().equals("entity"))) named = f;
            }
        }
        Field f = named != null ? named : first;
        if (f == null) return o -> -1;
        f.setAccessible(true);
        Field use = f;
        return o -> {
            try {
                return use.getInt(o);
            } catch (IllegalAccessException e) {
                return -1;
            }
        };
    }

    private boolean held(UUID viewer, Object packet) {
        ToIntFunction<Object> reader = ids.computeIfAbsent(packet.getClass(), this::idReader);
        UUID other = players.get(reader.applyAsInt(packet));
        return other != null && !other.equals(viewer) && !jammer.inContact(viewer, other);
    }

    /** The packet as it goes out to this viewer: unchanged, without the held-back parts, or null for nothing. */
    Object filter(UUID viewer, Object packet) {
        if (packet == null || jammer.level(viewer) < 2) return packet;
        Kind k = kinds.computeIfAbsent(packet.getClass(), this::kind);
        if (k == Kind.MOVING) return held(viewer, packet) ? null : packet;
        if (k != Kind.BUNDLE) return packet;
        try {
            Object subs = packet.getClass().getMethod("subPackets").invoke(packet);
            if (!(subs instanceof Iterable<?> list)) return packet;
            List<Object> kept = new ArrayList<>();
            boolean changed = false;
            for (Object sub : list) {
                if (sub != null && kinds.computeIfAbsent(sub.getClass(), this::kind) == Kind.MOVING && held(viewer, sub)) changed = true;
                else kept.add(sub);
            }
            if (!changed) return packet;
            if (kept.isEmpty()) return null;
            for (Constructor<?> c : packet.getClass().getConstructors()) {
                if (c.getParameterCount() == 1 && c.getParameterTypes()[0].isAssignableFrom(List.class)) return c.newInstance(kept);
            }
            return packet;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return packet;
        }
    }

    private final class Handler extends ChannelDuplexHandler {
        private final UUID viewer;

        Handler(UUID viewer) {
            this.viewer = viewer;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Object out;
            try {
                out = filter(viewer, msg);
            } catch (Throwable t) {
                out = msg;
            }
            if (out == null) {
                // held back: for the game it went out
                promise.trySuccess();
                return;
            }
            super.write(ctx, out, promise);
        }
    }
}
