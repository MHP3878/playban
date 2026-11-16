package dev.playban;

import dev.playban.model.Punishment;
import dev.playban.model.Punishment.TaskProgress;
import dev.playban.model.Review;
import dev.playban.model.TaskDef;
import dev.playban.model.TaskStatus;
import dev.playban.model.TaskType;
import dev.playban.model.Ticket;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The heart of the plugin: who is punished, what that means for them and how it ends. */
public final class PunishmentManager {
    private final PlayBanPlugin plugin;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private BukkitTask ticker;
    private int seconds;

    public PunishmentManager(PlayBanPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private DataStore store() {
        return plugin.store();
    }

    public void start() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        for (Player p : Bukkit.getOnlinePlayers()) if (isPunished(p.getUniqueId())) applyState(p);
    }

    public void shutdown() {
        if (ticker != null) ticker.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) if (isPunished(p.getUniqueId())) clearState(p);
    }

    public Punishment get(UUID id) {
        return store().active.get(id);
    }

    public boolean isPunished(UUID id) {
        return store().active.containsKey(id);
    }

    public boolean restricted(Player p, String key) {
        return isPunished(p.getUniqueId()) && plugin.getConfig().getBoolean("restrictions." + key, true);
    }

    public boolean onlineOnly() {
        return plugin.getConfig().getBoolean("count-only-online-time", true);
    }

    // ---------------------------------------------------------------- start / end

    public Punishment punish(UUID id, String name, long ms, String reason, String staff, UUID victim, String victimName) {
        long now = System.currentTimeMillis();
        Punishment p = new Punishment();
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
            if (def.type == TaskType.QUIZ && def.questions.isEmpty()) continue;
            p.tasks.put(def.id, new TaskProgress(TaskStatus.OPEN, def.timeLimitMs));
        }
        store().active.put(id, p);
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
        p.endedAt = System.currentTimeMillis();
        p.endReason = endReason;
        p.remainingMs = Math.max(0, p.remainingMs);
        store().history.computeIfAbsent(p.uuid, k -> new ArrayList<>()).add(p);
        store().reviews.values().removeIf(r -> r.author.equals(p.uuid));
        store().markDirty();

        Player online = Bukkit.getPlayer(p.uuid);
        if (online != null) {
            online.closeInventory();
            clearState(online);
            online.showTitle(Title.title(lang().msg("end.title"), lang().msg("end.subtitle")));
            online.playSound(online.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            lang().send(online, "PARDONED".equals(endReason) ? "end.pardoned" : "end.served");
        } else {
            mail(p.uuid, lang().prefixed("PARDONED".equals(endReason) ? "end.pardoned" : "end.served"));
        }
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

    // ---------------------------------------------------------------- state on the player

    private boolean maySee(Player viewer) {
        return plugin.getConfig().getBoolean("staff-see-punished", true) && viewer.hasPermission("playban.see");
    }

    public void applyState(Player player) {
        Punishment p = get(player.getUniqueId());
        if (p == null) return;
        refreshVisibility();
        player.setCollidable(false);
        updateBar(player, p);
    }

    public void clearState(Player player) {
        for (Player viewer : Bukkit.getOnlinePlayers()) viewer.showPlayer(plugin, player);
        player.setCollidable(true);
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
    }

    /** Re-applies who can see whom. Called on joins, punishments and setting changes. */
    public void refreshVisibility() {
        boolean hide = plugin.getConfig().getBoolean("restrictions.hide-from-players", true);
        for (Player punished : Bukkit.getOnlinePlayers()) {
            if (!isPunished(punished.getUniqueId())) continue;
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.equals(punished)) continue;
                if (hide && !maySee(viewer)) viewer.hidePlayer(plugin, punished);
                else viewer.showPlayer(plugin, punished);
            }
        }
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
            }
        }
        if (++seconds % 30 == 0) store().saveIfDirty();
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

    // ---------------------------------------------------------------- tickets

    public void createTicket(Player author, String text) {
        long open = store().tickets.values().stream().filter(x -> x.author.equals(author.getUniqueId())).count();
        if (open >= 3) {
            lang().send(author, "ticket.too-many");
            return;
        }
        Ticket t = new Ticket();
        t.id = store().nextId();
        t.author = author.getUniqueId();
        t.authorName = author.getName();
        t.text = text;
        t.createdAt = System.currentTimeMillis();
        store().tickets.put(t.id, t);
        store().markDirty();
        lang().send(author, "ticket.sent");
        notifyStaff(lang().prefixed("ticket.new", "player", author.getName(), "text", text));
    }

    public void replyTicket(Ticket t, String staff, String text) {
        deliver(t.author, lang().prefixed("ticket.reply", "staff", staff, "text", text));
        closeTicket(t);
    }

    public void closeTicket(Ticket t) {
        store().tickets.remove(t.id);
        store().markDirty();
    }

    // ---------------------------------------------------------------- messaging

    public void notifyStaff(Component message) {
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("playban.notify")) p.sendMessage(message);
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
