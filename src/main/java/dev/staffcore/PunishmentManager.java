package dev.staffcore;

import dev.staffcore.model.Category;
import dev.staffcore.model.Note;
import dev.staffcore.model.Punishment;
import dev.staffcore.model.Punishment.TaskProgress;
import dev.staffcore.model.Review;
import dev.staffcore.model.TaskDef;
import dev.staffcore.model.TaskStatus;
import dev.staffcore.model.TaskType;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The heart of the plugin: who is punished, what that means for them and how it ends. */
public final class PunishmentManager {
    private final StaffCorePlugin plugin;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    /** Staff who turned themselves invisible with /vanish. */
    private final java.util.Set<UUID> vanished = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private BukkitTask ticker;
    private int seconds;

    public PunishmentManager(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private DataStore store() {
        return plugin.store();
    }

    public void start() {
        for (String id : plugin.getConfig().getStringList("vanish.players")) {
            try {
                vanished.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                // a hand-edited entry that is not a UUID
            }
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isPunished(p.getUniqueId())) applyState(p);
            if (inLimbo(p.getUniqueId())) enterLimbo(p);
        }
    }

    public void shutdown() {
        if (ticker != null) ticker.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isPunished(p.getUniqueId())) clearState(p, get(p.getUniqueId()), false);
            Punishment ban = store().bans.get(p.getUniqueId());
            if (ban != null && ban.prevMode != null) leaveLimbo(p, ban);
        }
    }

    public Punishment get(UUID id) {
        return store().active.get(id);
    }

    public boolean isPunished(UUID id) {
        return store().isPunished(id);
    }

    /** Spectator ban: the player may join, but is stuck in spectator mode. */
    public boolean inLimbo(UUID id) {
        Punishment p = store().bans.get(id);
        return p != null && "SPECBAN".equals(p.type) && !(p.expiresAt > 0 && p.expiresAt <= System.currentTimeMillis());
    }

    /** PlayBan or spectator ban: either way the player is cut off from normal play. */
    public boolean limited(UUID id) {
        return isPunished(id) || inLimbo(id);
    }

    public boolean restricted(Player p, String key) {
        if (inLimbo(p.getUniqueId())) return true;
        return isPunished(p.getUniqueId()) && plugin.getConfig().getBoolean("restrictions." + key, true);
    }

    public boolean onlineOnly() {
        return plugin.getConfig().getBoolean("count-only-online-time", true);
    }

    // ---------------------------------------------------------------- start / end

    public Punishment punish(UUID id, String name, long ms, String reason, String staff, UUID victim, String victimName) {
        return punish(id, name, ms, reason, staff, victim, victimName, null);
    }

    public Punishment punish(UUID id, String name, long ms, String reason, String staff, UUID victim, String victimName, String category) {
        long now = System.currentTimeMillis();
        Punishment p = new Punishment();
        p.banId = newBanId();
        p.category = category;
        p.uuid = id;
        p.name = name;
        p.reason = reason;
        p.staff = staff;
        p.createdAt = now;
        p.originalMs = ms;
        p.remainingMs = ms;
        p.lastUpdate = now;
        p.victim = victim;
        p.victimName = victimName;
        for (TaskDef def : plugin.tasks().all()) {
            if (!def.enabled) continue;
            if (def.type == TaskType.GIFT_MONEY && !plugin.economy().available()) continue;
            if (def.type == TaskType.GIFT_MONEY && "VICTIM".equals(def.target) && victim == null) continue;
            if (def.type == TaskType.QUIZ && def.questions.isEmpty()) continue;
            p.tasks.put(def.id, new TaskProgress(TaskStatus.OPEN, def.timeLimitMs));
        }
        store().active.put(id, p);
        store().names.put(id, name);
        store().markDirty();

        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            online.closeInventory();
            applyState(online);
            online.showTitle(Title.title(lang().msg("punish.title"), lang().msg("punish.subtitle", "time", TimeUtil.format(ms))));
            online.playSound(online.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 0.8f);
            sendStatus(online, p);
        }
        notifyStaff(lang().prefixed("punish.broadcast", "player", name, "staff", staff, "time", TimeUtil.format(ms), "reason", reason));
        return p;
    }

    public void end(Punishment p, String endReason, String by) {
        if (store().active.remove(p.uuid) == null) return;
        if (by != null && "PARDONED".equals(endReason)) plugin.staffLog(by, "pardon", p.name, null);
        p.endedAt = System.currentTimeMillis();
        p.endReason = endReason;
        p.remainingMs = Math.max(0, p.remainingMs);
        store().history.computeIfAbsent(p.uuid, k -> new ArrayList<>()).add(p);
        store().reviews.values().removeIf(r -> r.author.equals(p.uuid));
        store().markDirty();

        Player online = Bukkit.getPlayer(p.uuid);
        if (online != null) {
            online.closeInventory();
            clearState(online, p, true);
            online.showTitle(Title.title(lang().msg("end.title"), lang().msg("end.subtitle")));
            online.playSound(online.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            lang().send(online, "PARDONED".equals(endReason) ? "end.pardoned" : "end.served");
        } else {
            mail(p.uuid, lang().prefixed("PARDONED".equals(endReason) ? "end.pardoned" : "end.served"));
            if (p.prevGameMode != null || p.storedInventory != null || p.prevLocation != null) store().restores.put(p.uuid, p);
        }
        store().saveIfDirty();
        notifyStaff("PARDONED".equals(endReason)
                ? lang().prefixed("end.broadcast-pardoned", "player", p.name, "staff", by)
                : lang().prefixed("end.broadcast-served", "player", p.name));
    }

    public void changeTime(Punishment p, long deltaMs, String by) {
        p.remainingMs += deltaMs;
        if (deltaMs > 0) p.originalMs += deltaMs;
        store().markDirty();
        Player online = Bukkit.getPlayer(p.uuid);
        if (p.remainingMs <= 0) {
            end(p, "PARDONED", by);
            return;
        }
        if (online != null) {
            lang().send(online, deltaMs > 0 ? "time.added-you" : "time.removed-you", "time", TimeUtil.format(Math.abs(deltaMs)));
            updateBar(online, p);
        }
    }

    // ---------------------------------------------------------------- categories, warnings and real bans

    /** How often the player was already punished in this category (lifted punishments do not count). */
    public int countOffenses(UUID id, Category category) {
        long now = System.currentTimeMillis();
        int n = 0;
        List<Punishment> all = new ArrayList<>(store().history.getOrDefault(id, List.of()));
        if (store().active.containsKey(id)) all.add(store().active.get(id));
        if (store().bans.containsKey(id)) all.add(store().bans.get(id));
        for (Punishment p : all) {
            if (!category.id.equals(p.category) || "PARDONED".equals(p.endReason)) continue;
            if (category.expireMs > 0 && now - p.createdAt > category.expireMs) continue;
            n++;
        }
        return n;
    }

    /** The punishment the next offense in this category leads to, or null when the category has no steps. */
    public Category.Step nextStep(UUID id, Category category) {
        if (category.steps.isEmpty()) return null;
        return category.steps.get(Math.min(countOffenses(id, category), category.steps.size() - 1));
    }

    public String describe(String type, long ms) {
        String name = lang().raw("type." + type);
        if (type.equals("WARN")) return name;
        if (!type.equals("PLAYBAN") && ms <= 0) return name + " (" + lang().raw("permanent") + ")";
        return name + " (" + TimeUtil.format(ms) + ")";
    }

    /**
     * Carries out any kind of punishment. Returns the key of an error text when it is not possible, otherwise null.
     */
    public String execute(UUID id, String name, String type, long ms, String reason, String staff,
                          String category, UUID victim, String victimName) {
        String result = executeInner(id, name, type, ms, reason, staff, category, victim, victimName);
        if (result == null && !"WARN".equals(type)) plugin.staffLog(staff, type.toLowerCase(), name, (ms > 0 ? TimeUtil.format(ms) + " " : "") + reason);
        return result;
    }

    private String executeInner(UUID id, String name, String type, long ms, String reason, String staff,
                                String category, UUID victim, String victimName) {
        switch (type) {
            case "WARN" -> warn(id, name, reason, staff, category);
            case "BAN", "SPECBAN" -> {
                if (store().bans.containsKey(id)) return "error.already-banned";
                ban(id, name, type, ms, reason, staff, category);
            }
            case "IPBAN" -> {
                if (store().bans.containsKey(id)) return "error.already-banned";
                String print = store().addresses.get(id);
                if (print == null) return "error.no-address";
                ban(id, name, "BAN", ms, reason, staff, category);
                store().bans.get(id).ipHash = print;
                // anyone else online from that address goes too
                for (Player other : Bukkit.getOnlinePlayers()) {
                    if (!other.getUniqueId().equals(id) && print.equals(store().addresses.get(other.getUniqueId()))
                            && !other.hasPermission("staffcore.exempt")) {
                        other.kick(banScreen(store().bans.get(id)));
                    }
                }
                store().saveIfDirty();
            }
            case "MUTE" -> {
                if (store().mutes.containsKey(id)) return "error.already-muted";
                mute(id, name, ms, reason, staff, category);
            }
            default -> {
                if (isPunished(id)) return "error.already-punished";
                if (store().bans.containsKey(id)) return "error.already-banned";
                if (ms <= 0) return "error.bad-duration";
                punish(id, name, ms, reason, staff, victim, victimName, category);
            }
        }
        return null;
    }

    private Punishment record(UUID id, String name, String type, String reason, String staff, String category) {
        store().names.put(id, name);
        Punishment p = new Punishment();
        p.type = type;
        p.uuid = id;
        p.name = name;
        p.reason = reason;
        p.staff = staff;
        p.category = category;
        p.createdAt = System.currentTimeMillis();
        p.lastUpdate = p.createdAt;
        p.banId = newBanId();
        return p;
    }

    private static final String ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I mix-ups

    /** A short code like "K7QX2M" that no other punishment has. */
    public String newBanId() {
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (Punishment x : store().active.values()) if (x.banId != null) taken.add(x.banId);
        for (Punishment x : store().bans.values()) if (x.banId != null) taken.add(x.banId);
        for (List<Punishment> list : store().history.values()) for (Punishment x : list) if (x.banId != null) taken.add(x.banId);
        java.security.SecureRandom random = new java.security.SecureRandom();
        String id;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(ID_CHARS.charAt(random.nextInt(ID_CHARS.length())));
            id = sb.toString();
        } while (taken.contains(id));
        return id;
    }

    /** Finds an active PlayBan or ban by its ID. */
    public Punishment byBanId(String id) {
        String wanted = id.trim().toUpperCase().replace("#", "");
        for (Punishment x : store().bans.values()) if (wanted.equals(x.banId)) return x;
        for (Punishment x : store().active.values()) if (wanted.equals(x.banId)) return x;
        return null;
    }

    public void warn(UUID id, String name, String reason, String staff, String category) {
        Punishment p = record(id, name, "WARN", reason, staff, category);
        plugin.staffLog(staff, "warn", name, reason);
        p.endedAt = p.createdAt;
        p.endReason = "WARNED";
        store().history.computeIfAbsent(id, k -> new ArrayList<>()).add(p);
        store().markDirty();
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            online.showTitle(Title.title(lang().msg("warn.title"), lang().msg("warn.subtitle", "reason", reason)));
            online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
        }
        deliver(id, lang().prefixed("warn.you", "reason", reason));
        notifyStaff(lang().prefixed("warn.broadcast", "player", name, "staff", staff, "reason", reason));
    }

    public void ban(UUID id, String name, String type, long ms, String reason, String staff, String category) {
        Punishment p = record(id, name, type, reason, staff, category);
        p.originalMs = Math.max(0, ms);
        p.expiresAt = ms <= 0 ? 0 : p.createdAt + ms;
        store().bans.put(id, p);
        store().markDirty();
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            if (type.equals("SPECBAN")) enterLimbo(online);
            else online.kick(banScreen(p));
        }
        notifyStaff(lang().prefixed(type.equals("SPECBAN") ? "ban.broadcast-spectator" : "ban.broadcast", "player", name, "staff", staff,
                "time", ms <= 0 ? lang().raw("permanent") : TimeUtil.format(ms), "reason", reason));
    }

    // ---------------------------------------------------------------- mute

    public void mute(UUID id, String name, long ms, String reason, String staff, String category) {
        Punishment p = record(id, name, "MUTE", reason, staff, category);
        p.originalMs = Math.max(0, ms);
        p.expiresAt = ms <= 0 ? 0 : p.createdAt + ms;
        store().mutes.put(id, p);
        store().markDirty();
        String time = ms <= 0 ? lang().raw("permanent") : TimeUtil.format(ms);
        deliver(id, lang().prefixed("mute.you", "time", time, "reason", reason));
        notifyStaff(lang().prefixed("mute.broadcast", "player", name, "staff", staff, "time", time, "reason", reason));
    }

    /** The running mute of a player, or null. Safe to call from the chat thread. */
    public Punishment activeMute(UUID id) {
        Punishment p = store().mutes.get(id);
        if (p == null || (p.expiresAt > 0 && p.expiresAt <= System.currentTimeMillis())) return null;
        return p;
    }

    public void unmute(Punishment p, String by) {
        if (store().mutes.remove(p.uuid) == null) return;
        if (by != null) plugin.staffLog(by, "unmute", p.name, null);
        p.endedAt = System.currentTimeMillis();
        p.endReason = by == null ? "SERVED" : "PARDONED";
        store().history.computeIfAbsent(p.uuid, k -> new ArrayList<>()).add(p);
        store().markDirty();
        deliver(p.uuid, lang().prefixed("mute.over"));
        if (by != null) notifyStaff(lang().prefixed("mute.broadcast-unmuted", "player", p.name, "staff", by));
    }

    /** Whether an IP ban covers this address. Safe to call from the login thread. */
    public Punishment ipBanFor(java.net.InetAddress address) {
        String print = store().fingerprint(address);
        if (print == null) return null;
        long now = System.currentTimeMillis();
        for (Punishment b : store().bans.values()) {
            if (print.equals(b.ipHash) && (b.expiresAt <= 0 || b.expiresAt > now)) return b;
        }
        return null;
    }

    public void unban(Punishment p, String by) {
        if (store().bans.remove(p.uuid) == null) return;
        if (by != null) plugin.staffLog(by, "unban", p.name, null);
        p.endedAt = System.currentTimeMillis();
        p.endReason = by == null ? "SERVED" : "PARDONED";
        store().history.computeIfAbsent(p.uuid, k -> new ArrayList<>()).add(p);
        store().markDirty();
        Player online = Bukkit.getPlayer(p.uuid);
        if (online != null && p.prevMode != null) {
            leaveLimbo(online, p);
            online.showTitle(Title.title(lang().msg("end.title"), lang().msg("end.subtitle")));
            lang().send(online, by == null ? "end.served" : "end.pardoned");
        }
        if (by != null) notifyStaff(lang().prefixed("ban.broadcast-unbanned", "player", p.name, "staff", by));
    }

    /** The active real ban of a player, or null. Safe to call from the login thread. */
    public Punishment activeBan(UUID id) {
        Punishment p = store().bans.get(id);
        if (p == null || !"BAN".equals(p.type)) return null;
        if (p.expiresAt > 0 && p.expiresAt <= System.currentTimeMillis()) return null;
        return p;
    }

    public String timeLeft(Punishment ban) {
        return ban.expiresAt <= 0 ? lang().raw("permanent") : TimeUtil.format(Math.max(1000, ban.expiresAt - System.currentTimeMillis()));
    }

    public String discord() {
        return plugin.getConfig().getString("ban-screen.discord", "");
    }

    /** The text a banned player sees: the server's own lines if set, otherwise the default from the language file. */
    public Component banScreen(Punishment p) {
        List<String> custom = plugin.getConfig().getStringList("ban-screen.lines");
        String template = custom.isEmpty() ? lang().raw("ban.screen") : String.join("\n", custom);
        if (!discord().isEmpty() && !template.contains("{discord}")) template += "\n\n" + lang().raw("ban.discord-line");
        if (p.banId != null && !template.contains("{id}")) template += "\n" + lang().raw("ban.id-line");
        return lang().format(template, "reason", p.reason, "time", timeLeft(p), "staff", p.staff, "discord", discord(),
                "id", p.banId == null ? "-" : p.banId);
    }

    // ---------------------------------------------------------------- spectator ban

    private Location limboLocation(Player player) {
        String world = plugin.getConfig().getString("limbo.location.world");
        World w = world == null ? null : Bukkit.getWorld(world);
        if (w == null) return player.getWorld().getSpawnLocation();
        return new Location(w, plugin.getConfig().getDouble("limbo.location.x"), plugin.getConfig().getDouble("limbo.location.y"),
                plugin.getConfig().getDouble("limbo.location.z"), (float) plugin.getConfig().getDouble("limbo.location.yaw"),
                (float) plugin.getConfig().getDouble("limbo.location.pitch"));
    }

    public void enterLimbo(Player player) {
        Punishment ban = store().bans.get(player.getUniqueId());
        if (ban == null) return;
        if (ban.prevMode == null) {
            Location l = player.getLocation();
            ban.prevMode = player.getGameMode().name();
            ban.prevLocation = l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
            store().markDirty();
            store().saveIfDirty();
        }
        player.closeInventory();
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(limboLocation(player));
        if (plugin.getConfig().getBoolean("limbo.blind", true)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }
        refreshVisibility();
        updateLimboBar(player, ban);
        player.showTitle(Title.title(lang().msg("limbo.title"), lang().msg("limbo.subtitle", "time", timeLeft(ban))));
        sendLimboStatus(player);
    }

    public void leaveLimbo(Player player, Punishment ban) {
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        if (ban.prevMode != null) {
            try {
                player.setGameMode(GameMode.valueOf(ban.prevMode));
                String[] l = ban.prevLocation.split(";");
                World w = Bukkit.getWorld(l[0]);
                if (w != null) {
                    player.teleport(new Location(w, Double.parseDouble(l[1]), Double.parseDouble(l[2]), Double.parseDouble(l[3]),
                            Float.parseFloat(l[4]), Float.parseFloat(l[5])));
                }
            } catch (RuntimeException e) {
                player.setGameMode(Bukkit.getDefaultGameMode());
            }
            ban.prevMode = null;
            ban.prevLocation = null;
            store().markDirty();
        }
        removeBar(player);
        refreshVisibility();
    }

    public void sendLimboStatus(Player player) {
        Punishment ban = store().bans.get(player.getUniqueId());
        if (ban == null) return;
        lang().send(player, "limbo.status", "time", timeLeft(ban), "reason", ban.reason);
        if (ban.banId != null) lang().send(player, "limbo.id", "id", ban.banId);
        lang().send(player, "limbo.hint");
        if (!discord().isEmpty()) lang().send(player, "limbo.discord", "discord", discord());
    }

    private void updateLimboBar(Player player, Punishment ban) {
        float progress = ban.expiresAt <= 0 || ban.originalMs <= 0 ? 1f
                : Math.max(0f, Math.min(1f, (float) (ban.expiresAt - System.currentTimeMillis()) / ban.originalMs));
        Component text = lang().msg("limbo.bossbar", "time", timeLeft(ban));
        BossBar bar = bars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(text, progress, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            bars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(text);
            bar.progress(progress);
        }
    }

    // ---------------------------------------------------------------- notes

    public void addNote(UUID id, String author, String text) {
        Note n = new Note();
        n.id = store().nextId();
        n.author = author;
        n.text = text;
        n.createdAt = System.currentTimeMillis();
        store().notes.computeIfAbsent(id, k -> new ArrayList<>()).add(n);
        store().markDirty();
    }

    public void removeNote(UUID id, Note note) {
        List<Note> list = store().notes.get(id);
        if (list != null && list.remove(note)) store().markDirty();
    }

    // ---------------------------------------------------------------- state on the player

    public void applyState(Player player) {
        Punishment p = get(player.getUniqueId());
        if (p == null) return;
        // where the player was before, for the teleport at the end (playban.end-teleport: LAST)
        if (p.prevLocation == null && !inLimbo(player.getUniqueId())) {
            Location l = player.getLocation();
            p.prevLocation = l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
            store().markDirty();
        }
        refreshVisibility();
        player.setCollidable(false);
        // adventure mode: the client itself stops predicting block breaks, so it no longer feels laggy
        if (plugin.getConfig().getBoolean("restrictions.adventure-mode", true)) {
            if (p.prevGameMode == null) {
                p.prevGameMode = player.getGameMode().name();
                store().markDirty();
            }
            if (player.getGameMode() != GameMode.SPECTATOR) player.setGameMode(GameMode.ADVENTURE);
        }
        // optional: take the inventory away for the duration, or clear it for good
        String invMode = plugin.getConfig().getString("inventory-mode", "KEEP").toUpperCase();
        if (invMode.equals("STORE") && p.storedInventory == null) {
            p.storedInventory = serializeInventory(player);
            player.getInventory().clear();
            store().markDirty();
        } else if (invMode.equals("CLEAR") && !p.inventoryCleared) {
            player.getInventory().clear();
            p.inventoryCleared = true;
            store().markDirty();
        }
        // written at once: after a crash we must still know the mode and items from before the punishment
        store().saveIfDirty();
        if (plugin.getConfig().getBoolean("restrictions.slow", false)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, PotionEffect.INFINITE_DURATION,
                    Math.max(0, plugin.getConfig().getInt("restrictions.slow-level", 1) - 1), false, false, false));
        }
        updateBar(player, p);
    }

    /** Gives back what a punishment that ended while the player was offline still holds. */
    public void restorePending(Player player) {
        Punishment p = store().restores.remove(player.getUniqueId());
        if (p == null) return;
        clearState(player, p, true);
        store().saveIfDirty();
    }

    /** Paper's own item format keeps enchantments and custom items intact. Stored as "contents;armor" in base64. */
    private String serializeInventory(Player player) {
        java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
        return b64.encodeToString(ItemStack.serializeItemsAsBytes(player.getInventory().getContents()))
                + ";" + b64.encodeToString(ItemStack.serializeItemsAsBytes(player.getInventory().getArmorContents()));
    }

    private void restoreInventory(Player player, String data) {
        try {
            String[] parts = data.split(";", 2);
            java.util.Base64.Decoder b64 = java.util.Base64.getDecoder();
            player.getInventory().clear();
            player.getInventory().setContents(ItemStack.deserializeItemsFromBytes(b64.decode(parts[0])));
            if (parts.length > 1) player.getInventory().setArmorContents(ItemStack.deserializeItemsFromBytes(b64.decode(parts[1])));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not give " + player.getName() + " their stored inventory back: " + e.getMessage());
        }
    }

    public void clearState(Player player) {
        clearState(player, get(player.getUniqueId()), true);
    }

    /**
     * Gives a player everything back. Takes the punishment directly because end() has already removed it.
     * finished = false on shutdown: the items stay stored, so a restart does not hand them out early.
     */
    public void clearState(Player player, Punishment p, boolean finished) {
        for (Player viewer : Bukkit.getOnlinePlayers()) viewer.showPlayer(plugin, player);
        player.setCollidable(true);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        if (p != null) {
            if (p.prevGameMode != null) {
                try {
                    player.setGameMode(GameMode.valueOf(p.prevGameMode));
                } catch (IllegalArgumentException ignored) {
                    player.setGameMode(Bukkit.getDefaultGameMode());
                }
            }
            if (finished) {
                if (p.storedInventory != null) restoreInventory(player, p.storedInventory);
                Location back = endLocation(player, p);
                if (back != null) player.teleport(back);
                p.prevLocation = null;
                // done: the history entry does not need to carry the items or the old mode any longer
                p.storedInventory = null;
                p.prevGameMode = null;
                store().markDirty();
            }
        }
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
        // the player sees everybody again
        Bukkit.getScheduler().runTask(plugin, this::refreshVisibility);
    }

    /**
     * Where a player goes when the PlayBan is over (playban.end-teleport): LAST = where they were before
     * the PlayBan, SPAWN, HOME = their first home, LOCATION = playban.end-location, NONE = stay.
     */
    public Location endLocation(Player player, Punishment p) {
        String mode = plugin.getConfig().getString("playban.end-teleport", "LAST").toUpperCase();
        switch (mode) {
            case "LAST" -> {
                if (p.prevLocation == null) return null;
                String[] l = p.prevLocation.split(";");
                org.bukkit.World w = Bukkit.getWorld(l[0]);
                if (w == null) return null;
                try {
                    return new Location(w, Double.parseDouble(l[1]), Double.parseDouble(l[2]), Double.parseDouble(l[3]),
                            Float.parseFloat(l[4]), Float.parseFloat(l[5]));
                } catch (RuntimeException e) {
                    return null;
                }
            }
            case "SPAWN" -> {
                return plugin.spawn() != null ? plugin.spawn().spawn() : player.getWorld().getSpawnLocation();
            }
            case "HOME" -> {
                if (plugin.homes() == null) return null;
                var homes = plugin.homes().of(player.getUniqueId());
                if (homes.isEmpty()) return plugin.spawn() != null ? plugin.spawn().spawn() : null;
                return homes.containsKey("home") ? homes.get("home") : homes.values().iterator().next();
            }
            case "LOCATION" -> {
                String w = plugin.getConfig().getString("playban.end-location.world");
                org.bukkit.World world = w == null ? null : Bukkit.getWorld(w);
                if (world == null) return null;
                return new Location(world, plugin.getConfig().getDouble("playban.end-location.x"), plugin.getConfig().getDouble("playban.end-location.y"),
                        plugin.getConfig().getDouble("playban.end-location.z"), (float) plugin.getConfig().getDouble("playban.end-location.yaw"), 0);
            }
            default -> {
                return null;
            }
        }
    }

    public boolean isVanished(UUID id) {
        return vanished.contains(id);
    }

    /** Turns a staff member invisible (or back). Players without playban.vanish stop seeing them. */
    public void setVanished(Player staff, boolean value) {
        if (value) vanished.add(staff.getUniqueId());
        else vanished.remove(staff.getUniqueId());
        List<String> ids = new ArrayList<>();
        for (UUID id : vanished) ids.add(id.toString());
        plugin.getConfig().set("vanish.players", ids);
        plugin.saveConfig();
        refreshVisibility();
        if (value) {
            staff.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        } else {
            staff.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
        lang().send(staff, value ? "vanish.on" : "vanish.off");
    }

    /** Puts the invisibility back on after a rejoin. */
    public void reapplyVanish(Player staff) {
        if (!isVanished(staff.getUniqueId())) return;
        staff.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        refreshVisibility();
        lang().send(staff, "vanish.still");
    }

    /** Targets and viewers that had special visibility rules last time, so they can be shown again. */
    private java.util.Set<UUID> lastSpecialTargets = new java.util.HashSet<>();
    private java.util.Set<UUID> lastBlindViewers = new java.util.HashSet<>();

    /**
     * Re-applies who can see whom. Called on joins, punishments and setting changes.
     * Only pairs that involve a punished, spectator-banned or vanished player are touched,
     * so a big server does not check every pair of players each time.
     */
    public void refreshVisibility() {
        boolean hide = plugin.getConfig().getBoolean("restrictions.hide-from-players", true);
        boolean staffSee = plugin.getConfig().getBoolean("staff-see-punished", true);
        boolean limboSeesNobody = plugin.getConfig().getBoolean("limbo.hide-others", true);
        java.util.Collection<? extends Player> online = Bukkit.getOnlinePlayers();

        java.util.Set<UUID> special = new java.util.HashSet<>();
        java.util.Set<UUID> blind = new java.util.HashSet<>();
        for (Player p : online) {
            UUID id = p.getUniqueId();
            if ((isPunished(id) && hide) || inLimbo(id) || plugin.tickets().vanished(id) || isVanished(id)) special.add(id);
            if (limboSeesNobody && inLimbo(id)) blind.add(id);
            // a PlayBan works both ways: others do not see them, and they see nobody (the team aside)
            if (isPunished(id) && plugin.getConfig().getBoolean("restrictions.hide-others", true)) blind.add(id);
        }
        // permissions once per viewer, not once per pair
        Map<UUID, boolean[]> rights = new HashMap<>();
        java.util.function.Function<Player, boolean[]> rightsOf = v -> rights.computeIfAbsent(v.getUniqueId(),
                k -> new boolean[]{staffSee && v.hasPermission("staffcore.see"), v.hasPermission("staffcore.vanish")});

        java.util.Set<UUID> targets = new java.util.HashSet<>(special);
        targets.addAll(lastSpecialTargets);
        java.util.Set<UUID> viewers = new java.util.HashSet<>(blind);
        viewers.addAll(lastBlindViewers);
        for (Player target : online) {
            boolean targetMatters = targets.contains(target.getUniqueId());
            for (Player viewer : online) {
                if (viewer.equals(target)) continue;
                if (!targetMatters && !viewers.contains(viewer.getUniqueId())) continue;
                UUID t = target.getUniqueId();
                boolean[] r = rightsOf.apply(viewer);
                boolean hidden = ((isPunished(t) && hide) || inLimbo(t) || plugin.tickets().vanished(t)) && !r[0];
                if (isVanished(t) && !r[1]) hidden = true;
                if (blind.contains(viewer.getUniqueId()) && !(isPunished(viewer.getUniqueId()) && !inLimbo(viewer.getUniqueId())
                        && target.hasPermission("staffcore.staff") && !isVanished(t))) hidden = true;
                if (hidden) viewer.hidePlayer(plugin, target);
                else viewer.showPlayer(plugin, target);
            }
        }
        lastSpecialTargets = special;
        lastBlindViewers = blind;
    }

    private void updateBar(Player player, Punishment p) {
        float progress = p.originalMs <= 0 ? 0f : Math.max(0f, Math.min(1f, (float) p.remainingMs / p.originalMs));
        Component text = lang().msg("bossbar", "time", TimeUtil.format(Math.max(0, p.remainingMs)),
                "done", p.tasksDone(), "total", p.tasks.size());
        BossBar bar = bars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(text, progress, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            bars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(text);
            bar.progress(progress);
        }
    }

    public void removeBar(Player player) {
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
    }

    public void sendStatus(Player player, Punishment p) {
        lang().send(player, "status.line", "time", TimeUtil.format(Math.max(0, p.remainingMs)), "reason", p.reason);
        if (p.banId != null) lang().send(player, "status.id", "id", p.banId);
        lang().send(player, onlineOnly() ? "status.online-only" : "status.real-time");
        lang().send(player, "status.hint");
    }

    // ---------------------------------------------------------------- clock

    private void tick() {
        long now = System.currentTimeMillis();
        boolean onlineOnly = onlineOnly();
        for (Punishment p : new ArrayList<>(store().active.values())) {
            Player online = Bukkit.getPlayer(p.uuid);
            long delta = onlineOnly ? 1000 : Math.max(0, now - p.lastUpdate);
            p.lastUpdate = now;
            if (online == null && onlineOnly) continue;
            p.remainingMs -= delta;
            for (Map.Entry<String, TaskProgress> e : p.tasks.entrySet()) {
                TaskProgress t = e.getValue();
                TaskDef def = plugin.tasks().get(e.getKey());
                if (t.status != TaskStatus.OPEN || def == null || def.timeLimitMs <= 0) continue;
                t.timeLeftMs -= delta;
                if (t.timeLeftMs <= 0) {
                    t.status = TaskStatus.EXPIRED;
                    if (online != null) lang().send(online, "task.expired", "task", taskName(def, p));
                }
            }
            store().markDirty();
            if (p.remainingMs <= 0) {
                end(p, "SERVED", "");
            } else if (online != null) {
                updateBar(online, p);
                if (plugin.getConfig().getBoolean("restrictions.action-bar", true)) {
                    online.sendActionBar(lang().msg("actionbar", "time", TimeUtil.format(Math.max(0, p.remainingMs)), "reason", p.reason));
                }
            }
        }
        // vanished staff always see that they are invisible
        for (UUID id : vanished) {
            Player staff = Bukkit.getPlayer(id);
            if (staff != null && !isPunished(id)) staff.sendActionBar(lang().msg("vanish.actionbar"));
        }
        for (Punishment m : new ArrayList<>(store().mutes.values())) {
            if (m.expiresAt > 0 && m.expiresAt <= now) unmute(m, null);
        }
        for (Punishment ban : new ArrayList<>(store().bans.values())) {
            if (ban.expiresAt > 0 && ban.expiresAt <= now) {
                unban(ban, null);
            } else if ("SPECBAN".equals(ban.type)) {
                Player online = Bukkit.getPlayer(ban.uuid);
                if (online != null) updateLimboBar(online, ban);
            }
        }
        if (++seconds % 30 == 0) {
            store().saveIfDirty();
            plugin.tickets().saveIfDirty();
        }
        if (seconds % 60 == 0) plugin.tickets().remind();
    }

    // ---------------------------------------------------------------- tasks

    public Component taskName(TaskDef def, Punishment p) {
        if (!def.name.isEmpty()) return lang().parse(def.name);
        return lang().msg("task." + def.type.name() + ".name");
    }

    public String targetLabel(TaskDef def, Punishment p) {
        if ("VICTIM".equals(def.target) && p != null && p.victimName != null) return p.victimName;
        return lang().raw("task.any-player");
    }

    public void completeTask(Punishment p, TaskDef def) {
        TaskProgress t = p.tasks.get(def.id);
        if (t == null || t.status == TaskStatus.DONE) return;
        t.status = TaskStatus.DONE;
        long reduction = def.reductionMs(p.originalMs);
        p.remainingMs -= reduction;
        store().markDirty();
        Player online = Bukkit.getPlayer(p.uuid);
        Component done = lang().prefixed("task.done", "task", taskName(def, p), "time", TimeUtil.format(reduction));
        if (online != null) {
            online.sendMessage(done);
            online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        } else {
            mail(p.uuid, done);
        }
        if (p.remainingMs <= 0) end(p, "SERVED", "");
        else if (online != null) updateBar(online, p);
    }

    public void submitReview(Player author, Punishment p, TaskDef def, UUID target, String targetName, String text) {
        Review r = new Review();
        r.id = store().nextId();
        r.author = p.uuid;
        r.authorName = p.name;
        r.taskId = def.id;
        r.target = target;
        r.targetName = targetName;
        r.text = text;
        r.createdAt = System.currentTimeMillis();
        TaskProgress t = p.tasks.get(def.id);
        if (t != null) t.status = TaskStatus.PENDING;
        store().reviews.put(r.id, r);
        store().markDirty();
        if (plugin.getConfig().getBoolean("auto-approve-messages", false)) {
            approve(r, "auto");
            return;
        }
        lang().send(author, "review.submitted");
        notifyStaff(lang().prefixed("review.new", "player", p.name));
    }

    public void approve(Review r, String staff) {
        if (store().reviews.remove(r.id) == null) return;
        store().markDirty();
        Punishment p = get(r.author);
        TaskDef def = plugin.tasks().get(r.taskId);
        if (r.target != null) {
            deliver(r.target, lang().prefixed("review.delivered", "player", r.authorName, "text", r.text));
        }
        if (p == null || def == null) return;
        completeTask(p, def);
    }

    public void reject(Review r, String staff) {
        if (store().reviews.remove(r.id) == null) return;
        store().markDirty();
        Punishment p = get(r.author);
        if (p == null) return;
        TaskProgress t = p.tasks.get(r.taskId);
        if (t != null && t.status == TaskStatus.PENDING) t.status = TaskStatus.OPEN;
        deliver(r.author, lang().prefixed("review.rejected-you"));
    }

    // ---------------------------------------------------------------- messaging

    public void notifyStaff(Component message) {
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("staffcore.notify")) p.sendMessage(message);
        Bukkit.getConsoleSender().sendMessage(message);
    }

    /** Sends now when the player is online, otherwise keeps it for their next join. */
    public void deliver(UUID to, Component message) {
        Player online = Bukkit.getPlayer(to);
        if (online != null) online.sendMessage(message);
        else mail(to, message);
    }

    private void mail(UUID to, Component message) {
        List<String> box = store().mail.computeIfAbsent(to, k -> new ArrayList<>());
        if (box.size() < 50) box.add(MiniMessage.miniMessage().serialize(message));
        store().markDirty();
    }

    public void deliverMail(Player player) {
        List<String> box = store().mail.remove(player.getUniqueId());
        if (box == null) return;
        for (String s : box) player.sendMessage(MiniMessage.miniMessage().deserialize(s));
        store().markDirty();
    }
}
