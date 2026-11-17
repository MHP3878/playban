package dev.playban.voice;

import dev.playban.Lang;
import dev.playban.PlayBanPlugin;
import dev.playban.model.Ticket;
import dev.playban.voice.VoiceBackend.GroupInfo;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moderation of voice groups: kicking, banning, muting, passwords, joining unseen and reports.
 * The voice chat asks this class from its own threads, so everything it reads here is thread-safe.
 */
public final class VoiceManager {
    private final PlayBanPlugin plugin;
    private VoiceBackend backend = new NoVoice();
    private final Map<UUID, Set<UUID>> bans = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> owners = new ConcurrentHashMap<>();
    private final Map<UUID, String> passwords = new ConcurrentHashMap<>();
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();
    private final Set<UUID> deaf = ConcurrentHashMap.newKeySet();
    /** Players pulled into a ticket voice group: silent until they agree to speak. */
    private final Set<UUID> waitingToSpeak = ConcurrentHashMap.newKeySet();
    private final Set<UUID> invisible = ConcurrentHashMap.newKeySet();
    /** Who may still see team members that joined unseen. Kept as a set because permissions cannot be read off-thread. */
    private final Set<UUID> seesInvisible = ConcurrentHashMap.newKeySet();

    public VoiceManager(PlayBanPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    public void use(VoiceBackend backend) {
        this.backend = backend;
    }

    public boolean available() {
        return backend.available();
    }

    public List<GroupInfo> groups() {
        return backend.groups();
    }

    public GroupInfo group(UUID id) {
        for (GroupInfo g : backend.groups()) if (g.id().equals(id)) return g;
        return null;
    }

    public UUID groupOf(UUID player) {
        return backend.groupOf(player);
    }

    // ---------------------------------------------------------------- questions from the voice chat (any thread)

    public boolean mayJoin(UUID player, UUID group) {
        Set<UUID> banned = bans.get(group);
        return banned == null || !banned.contains(player);
    }

    public void created(UUID creator, UUID group) {
        if (creator != null) owners.putIfAbsent(group, creator);
    }

    public void left(UUID player, UUID group) {
        // mutes belong to the group they were given in
        boolean changed = muted.remove(player) | deaf.remove(player) | waitingToSpeak.remove(player) | invisible.remove(player);
        if (changed) Bukkit.getScheduler().runTask(plugin, () -> backend.refresh(player));
    }

    public void removed(UUID group) {
        bans.remove(group);
        owners.remove(group);
        passwords.remove(group);
    }

    public boolean isMuted(UUID player) {
        return muted.contains(player) || waitingToSpeak.contains(player);
    }

    public boolean isDeaf(UUID player) {
        return deaf.contains(player);
    }

    public boolean isHiddenFrom(UUID player, UUID observer) {
        return invisible.contains(player) && !seesInvisible.contains(observer);
    }

    // ---------------------------------------------------------------- state

    public void joined(Player player) {
        if (player.hasPermission("playban.voice")) seesInvisible.add(player.getUniqueId());
    }

    public void quit(UUID player) {
        seesInvisible.remove(player);
        invisible.remove(player);
        waitingToSpeak.remove(player);
    }

    public UUID owner(UUID group) {
        return owners.get(group);
    }

    public boolean isBanned(UUID group, UUID player) {
        return !mayJoin(player, group);
    }

    public List<UUID> banned(UUID group) {
        return new ArrayList<>(bans.getOrDefault(group, Set.of()));
    }

    public boolean isInvisible(UUID player) {
        return invisible.contains(player);
    }

    public String knownPassword(UUID group) {
        return passwords.get(group);
    }

    public boolean prefersInvisible(UUID staff) {
        return plugin.getConfig().getStringList("voice.join-invisible").contains(staff.toString());
    }

    public void setPrefersInvisible(UUID staff, boolean value) {
        List<String> list = new ArrayList<>(plugin.getConfig().getStringList("voice.join-invisible"));
        list.remove(staff.toString());
        if (value) list.add(staff.toString());
        plugin.getConfig().set("voice.join-invisible", list);
        plugin.saveConfig();
    }

    // ---------------------------------------------------------------- actions

    private String name(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p != null ? p.getName() : plugin.store().names.getOrDefault(id, "?");
    }

    public void kick(UUID group, UUID target, String by) {
        if (!group.equals(backend.groupOf(target))) return;
        backend.setGroup(target, null);
        plugin.punishments().deliver(target, lang().prefixed("voice.kicked-you", "staff", by));
    }

    public void ban(UUID group, UUID target, String by) {
        bans.computeIfAbsent(group, k -> ConcurrentHashMap.newKeySet()).add(target);
        if (group.equals(backend.groupOf(target))) backend.setGroup(target, null);
        plugin.punishments().deliver(target, lang().prefixed("voice.banned-you", "staff", by));
    }

    public void unban(UUID group, UUID target) {
        Set<UUID> banned = bans.get(group);
        if (banned != null) banned.remove(target);
    }

    public void setMuted(UUID target, boolean value, String by) {
        if (value ? muted.add(target) : muted.remove(target)) {
            backend.refresh(target);
            plugin.punishments().deliver(target, lang().prefixed(value ? "voice.muted-you" : "voice.unmuted-you", "staff", by));
        }
    }

    public void setDeaf(UUID target, boolean value, String by) {
        if (value ? deaf.add(target) : deaf.remove(target)) {
            backend.refresh(target);
            plugin.punishments().deliver(target, lang().prefixed(value ? "voice.deafened-you" : "voice.undeafened-you", "staff", by));
        }
    }

    public void close(UUID group, String by) {
        GroupInfo info = group(group);
        if (info == null) return;
        for (UUID member : info.members()) {
            backend.setGroup(member, null);
            plugin.punishments().deliver(member, lang().prefixed("voice.closed-you", "group", info.name(), "staff", by));
        }
        backend.removeGroup(group);
        removed(group);
    }

    public boolean setPassword(UUID group, String password) {
        if (!backend.setPassword(group, password)) return false;
        if (password == null) passwords.remove(group);
        else passwords.put(group, password);
        return true;
    }

    public void join(Player staff, UUID group, boolean unseen) {
        setInvisible(staff, unseen);
        backend.setGroup(staff.getUniqueId(), group);
    }

    public void setInvisible(Player staff, boolean value) {
        syncViewers();
        if (value ? invisible.add(staff.getUniqueId()) : invisible.remove(staff.getUniqueId())) backend.refresh(staff.getUniqueId());
    }

    /** Brings the list of people who may see unseen team members up to date; rights can change while someone is online. */
    private void syncViewers() {
        seesInvisible.clear();
        for (Player online : Bukkit.getOnlinePlayers()) if (online.hasPermission("playban.voice")) seesInvisible.add(online.getUniqueId());
    }

    /** A normal player reports a voice group; this becomes a ticket for the team. */
    public Ticket report(Player reporter, UUID group, String reason) {
        GroupInfo info = group(group);
        if (info == null) return null;
        List<String> members = new ArrayList<>();
        for (UUID m : info.members()) if (!invisible.contains(m)) members.add(name(m));
        String text = lang().plain("voice.report-text", "group", info.name(), "reason", reason, "members", String.join(", ", members));
        return plugin.tickets().create(reporter, "voice", text);
    }

    // ---------------------------------------------------------------- voice for a ticket

    /** Puts everyone from a ticket into a private voice group. Players stay silent until they agree to speak. */
    public boolean ticketGroup(Ticket t, Player staff) {
        if (!backend.available()) return false;
        byte[] random = new byte[6];
        new SecureRandom().nextBytes(random);
        String password = java.util.HexFormat.of().formatHex(random);
        UUID group = backend.createGroup("Ticket " + t.id, password, true);
        if (group == null) return false;
        passwords.put(group, password);
        owners.put(group, staff.getUniqueId());
        backend.setGroup(staff.getUniqueId(), group);
        for (Ticket.Participant p : t.participants) {
            Player online = Bukkit.getPlayer(p.uuid);
            if (online == null || online.equals(staff)) continue;
            waitingToSpeak.add(p.uuid);
            backend.setGroup(p.uuid, group);
            backend.refresh(p.uuid);
            online.showTitle(Title.title(lang().msg("ticket.voice.title"), lang().msg("ticket.voice.subtitle", "staff", staff.getName())));
            online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
            online.sendMessage(lang().prefixed("voice.pulled-in", "staff", staff.getName()).clickEvent(ClickEvent.runCommand("/voice speak")));
        }
        return true;
    }

    /** The player agrees to speak in the ticket voice group. */
    public boolean agreeToSpeak(Player player) {
        if (!waitingToSpeak.remove(player.getUniqueId())) return false;
        backend.refresh(player.getUniqueId());
        return true;
    }

    /** Used when no voice chat is installed. */
    private static final class NoVoice implements VoiceBackend {
        public boolean available() { return false; }
        public List<GroupInfo> groups() { return List.of(); }
        public UUID groupOf(UUID player) { return null; }
        public boolean setGroup(UUID player, UUID group) { return false; }
        public UUID createGroup(String name, String password, boolean hidden) { return null; }
        public boolean setPassword(UUID group, String password) { return false; }
        public void removeGroup(UUID group) {}
        public void refresh(UUID player) {}
    }
}
