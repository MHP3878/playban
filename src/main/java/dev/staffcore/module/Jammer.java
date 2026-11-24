package dev.staffcore.module;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import dev.staffcore.TimeUtil;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /jammer: cuts a player off from everyone, in three levels, without them being told.
 * <ol>
 * <li>Silent: what they write in chat, in private messages (/msg, /r, /me, /teammsg) and in Simple
 * Voice Chat reaches nobody, and nothing from others reaches them. On their screen all looks normal.</li>
 * <li>Frozen: as 1, and every other player stands still for them, as if everybody had left.</li>
 * <li>Ghost town: as 1, and every other player and every animal and monster is gone for them.</li>
 * </ol>
 * A team member can "talk" with a jammed player: then the two see, hear and write to each other
 * (also out of vanish, for this player only), and nobody else is involved.
 */
public final class Jammer implements Listener, TabExecutor {
    public record Entry(UUID id, String name, String by, long at, long until, int level) {
        boolean active(long now) {
            return until == 0 || now < until;
        }
    }

    /** Private message commands: the target is the first word after the command. */
    private static final Set<String> WHISPER = Set.of("msg", "tell", "w", "whisper", "m", "pm", "t", "dm", "message",
            "emsg", "etell", "ewhisper", "epm", "em", "et", "edm");
    /** Answers to the last private message. */
    private static final Set<String> REPLY = Set.of("r", "reply", "er", "ereply");
    private static final Set<String> EMOTE = Set.of("me", "eme", "action", "describe", "eaction", "edescribe");
    private static final Set<String> TEAM = Set.of("teammsg", "tm");

    private final StaffCorePlugin plugin;
    private final Map<UUID, Entry> jammed = new ConcurrentHashMap<>();
    /** who last wrote privately with whom, so /r goes where the player expects */
    private final Map<UUID, UUID> partner = new ConcurrentHashMap<>();
    /** team member → the jammed players they talk with */
    private final Map<UUID, Set<UUID>> talks = new ConcurrentHashMap<>();
    /** level 3: the animals and monsters hidden from each viewer, and those to show again once they are loaded */
    private final Map<UUID, Set<UUID>> hiddenCreatures = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> showLater = new ConcurrentHashMap<>();
    private final JamFreeze freeze;
    private final JammerMenu menu;
    private BukkitTask task;

    public Jammer(StaffCorePlugin plugin) {
        this.plugin = plugin;
        this.freeze = new JamFreeze(this, plugin.getLogger());
        this.menu = new JammerMenu(plugin, this);
    }

    private Lang lang() {
        return plugin.lang();
    }

    private boolean on() {
        return plugin.modules() == null || plugin.modules().on("jammer");
    }

    public JammerMenu menu() {
        return menu;
    }

    /** For tests: what holds back the movement at level 2. */
    JamFreeze freeze() {
        return freeze;
    }

    /** Whether this player is cut off right now (any thread). */
    public boolean isJammed(UUID id) {
        return level(id) > 0;
    }

    /** 0 (not jammed), 1 silent, 2 frozen or 3 ghost town (any thread). */
    public int level(UUID id) {
        if (id == null) return 0;
        Entry e = jammed.get(id);
        return e != null && e.active(System.currentTimeMillis()) && on() ? e.level() : 0;
    }

    public Entry entry(UUID id) {
        return isJammed(id) ? jammed.get(id) : null;
    }

    public boolean any() {
        return !jammed.isEmpty() && on();
    }

    public List<Entry> all() {
        long now = System.currentTimeMillis();
        List<Entry> out = new ArrayList<>();
        for (Entry e : jammed.values()) if (e.active(now)) out.add(e);
        out.sort((a, b) -> Long.compare(b.at(), a.at()));
        return out;
    }

    /** Whether these two talk with each other through /jammer talk (either order, any thread). */
    public boolean inContact(UUID a, UUID b) {
        if (a == null || b == null) return false;
        Set<UUID> x = talks.get(a);
        if (x != null && x.contains(b)) return true;
        Set<UUID> y = talks.get(b);
        return y != null && y.contains(a);
    }

    /** Whether this jammed player has a team member to talk with. */
    public boolean hasContact(UUID jammedId) {
        for (Set<UUID> s : talks.values()) if (s.contains(jammedId)) return true;
        return false;
    }

    /** The jammed players this team member talks with. */
    public Set<UUID> talksWith(UUID staff) {
        Set<UUID> s = talks.get(staff);
        return s == null ? Set.of() : Set.copyOf(s);
    }

    // ---------------------------------------------------------------- storage

    private File file() {
        return new File(plugin.getDataFolder(), "jammer.yml");
    }

    public void load() {
        jammed.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file());
        long now = System.currentTimeMillis();
        for (String key : y.getKeys(false)) {
            ConfigurationSection c = y.getConfigurationSection(key);
            if (c == null) continue;
            try {
                UUID id = UUID.fromString(key);
                Entry e = new Entry(id, c.getString("name", "?"), c.getString("by", "?"), c.getLong("at"), c.getLong("until"),
                        Math.max(1, Math.min(3, c.getInt("level", 1))));
                if (e.active(now)) jammed.put(id, e);
            } catch (IllegalArgumentException ignored) {
                // a broken line
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Entry e : jammed.values()) {
            String k = e.id().toString();
            y.set(k + ".name", e.name());
            y.set(k + ".by", e.by());
            y.set(k + ".at", e.at());
            y.set(k + ".until", e.until());
            y.set(k + ".level", e.level());
        }
        try {
            y.save(file());
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save jammer.yml: " + ex.getMessage());
        }
    }

    public void start() {
        freeze.refreshIds();
        for (Player p : Bukkit.getOnlinePlayers()) enter(p);
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            freeze.refreshIds();
            // the ghost town stays empty: whatever came in another way (portals, spawners ...) goes too
            for (UUID id : hiddenCreatures.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && level(id) >= 3) ghostOn(p);
            }
            // a jam with a time runs out on its own
            long now = System.currentTimeMillis();
            for (Entry e : new ArrayList<>(jammed.values())) if (!e.active(now)) lift(e.id(), "time");
        }, 20L, 20L);
    }

    public void stop() {
        if (task != null) task.cancel();
        freeze.detachAll();
        for (Player p : Bukkit.getOnlinePlayers()) ghostOff(p);
    }

    // ---------------------------------------------------------------- jam, change, lift

    /** Jams a player at this level (1-3) for this long (0 = until lifted), or changes level and time. */
    public void jam(UUID id, String name, String by, int level, long ms) {
        long now = System.currentTimeMillis();
        int before = level(id);
        int lv = Math.max(1, Math.min(3, level));
        jammed.put(id, new Entry(id, name, by, now, ms > 0 ? now + ms : 0, lv));
        save();
        plugin.staffLog(by, "jammer", name, lv + " · " + (ms > 0 ? TimeUtil.format(ms) : "perm"));
        Player p = Bukkit.getPlayer(id);
        if (p != null) changed(p, before, lv);
    }

    public boolean lift(UUID id, String by) {
        Entry e = jammed.remove(id);
        if (e == null) return false;
        save();
        // nobody talks with them any more
        List<UUID> talkedWith = new ArrayList<>();
        for (Map.Entry<UUID, Set<UUID>> t : talks.entrySet()) if (t.getValue().remove(id)) talkedWith.add(t.getKey());
        talks.values().removeIf(Set::isEmpty);
        for (UUID staffId : talkedWith) {
            Player staff = Bukkit.getPlayer(staffId);
            if (staff != null) seenWhileTalking(staff);
        }
        plugin.staffLog(by, "unjammer", e.name(), "");
        Player p = Bukkit.getPlayer(id);
        if (p != null) changed(p, e.level(), 0);
        return true;
    }

    /** What the player's world looks like after a change from one level to another. */
    private void changed(Player p, int before, int now) {
        if (now >= 2) freeze.attach(p);
        if (now >= 3 && before < 3) ghostOn(p);
        if (now < 3 && before >= 3) ghostOff(p);
        if (now < 2 && before >= 2) {
            freeze.detach(p);
            // the others were held back: they are sent again at their real places
            if (before == 2) resend(p, null);
        }
        if (plugin.punishments() != null) plugin.punishments().refreshVisibility();
    }

    /** A player comes back on: their level is set up again. */
    private void enter(Player p) {
        int lv = level(p.getUniqueId());
        if (lv >= 2) freeze.attach(p);
        if (lv >= 3) ghostOn(p);
    }

    /** Sends other players again (hidden and shown) so the viewer sees where they really are. */
    private void resend(Player viewer, UUID only) {
        List<Player> again = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(viewer) || only != null && !other.getUniqueId().equals(only) || !viewer.canSee(other)) continue;
            viewer.hidePlayer(plugin, other);
            again.add(other);
        }
        if (again.isEmpty()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!viewer.isOnline()) return;
            for (Player other : again) if (other.isOnline()) viewer.showPlayer(plugin, other);
            if (plugin.punishments() != null) plugin.punishments().refreshVisibility();
        }, 2L);
    }

    // ---------------------------------------------------------------- talking with a jammed player

    /** Starts or ends talking with a jammed player. Returns whether they talk now. */
    public boolean toggleTalk(Player staff, UUID target) {
        Set<UUID> s = talks.computeIfAbsent(staff.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        boolean now;
        if (s.remove(target)) now = false;
        else {
            if (!isJammed(target)) return false;
            s.add(target);
            now = true;
        }
        if (s.isEmpty()) talks.remove(staff.getUniqueId());
        seenWhileTalking(staff);
        Player p = Bukkit.getPlayer(target);
        if (p != null && level(target) == 2) {
            // held back until now: the team member is sent again at their real place
            if (now) resend(p, staff.getUniqueId());
        }
        if (plugin.punishments() != null) plugin.punishments().refreshVisibility();
        plugin.staffLog(staff.getName(), now ? "jammer-talk" : "jammer-talk-end", p != null ? p.getName() : target.toString(), "");
        return now;
    }

    /**
     * A vanished team member is invisible by a potion effect too: while they talk with a jammed
     * player it is taken off (everybody else still does not see them), afterwards it is back.
     */
    private void seenWhileTalking(Player staff) {
        if (plugin.punishments() == null || !plugin.punishments().isVanished(staff.getUniqueId())) return;
        var invisible = org.bukkit.potion.PotionEffectType.INVISIBILITY;
        if (talks.containsKey(staff.getUniqueId())) staff.removePotionEffect(invisible);
        else staff.addPotionEffect(new org.bukkit.potion.PotionEffect(invisible, org.bukkit.potion.PotionEffect.INFINITE_DURATION, 0, false, false, false));
    }

    /**
     * Who a jammed viewer sees (for StaffCore's visibility rules): the team member they talk with,
     * also out of vanish; nobody else at level 3. Null: the usual rules decide.
     */
    public Boolean sees(Player viewer, Player target) {
        int lv = level(viewer.getUniqueId());
        if (lv == 0) return null;
        if (inContact(viewer.getUniqueId(), target.getUniqueId())) return true;
        return lv >= 3 ? Boolean.FALSE : null;
    }

    // ---------------------------------------------------------------- level 3: the ghost town

    private static boolean creature(Entity e) {
        return e instanceof LivingEntity && !(e instanceof Player) && !(e instanceof ArmorStand);
    }

    private void ghostOn(Player p) {
        Set<UUID> hidden = hiddenCreatures.computeIfAbsent(p.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        Set<UUID> later = showLater.get(p.getUniqueId());
        for (Entity e : p.getWorld().getEntities()) {
            if (!creature(e) || hidden.contains(e.getUniqueId()) && !p.canSee(e)) continue;
            p.hideEntity(plugin, e);
            hidden.add(e.getUniqueId());
            if (later != null) later.remove(e.getUniqueId());
        }
    }

    private void ghostOff(Player p) {
        Set<UUID> hidden = hiddenCreatures.remove(p.getUniqueId());
        if (hidden == null) return;
        Set<UUID> later = showLater.computeIfAbsent(p.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        for (UUID id : hidden) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) p.showEntity(plugin, e);
            else later.add(id);
        }
        if (later.isEmpty()) showLater.remove(p.getUniqueId());
    }

    /** Animals and monsters that appear (spawned, or loaded with their chunk) are hidden from ghost-town players at once. */
    @EventHandler
    public void onEntityAdded(com.destroystokyo.paper.event.entity.EntityAddToWorldEvent e) {
        Entity en = e.getEntity();
        if (!creature(en)) return;
        if (!hiddenCreatures.isEmpty()) {
            for (Map.Entry<UUID, Set<UUID>> h : hiddenCreatures.entrySet()) {
                Player v = Bukkit.getPlayer(h.getKey());
                if (v == null || v.getWorld() != en.getWorld() || level(v.getUniqueId()) < 3) continue;
                v.hideEntity(plugin, en);
                h.getValue().add(en.getUniqueId());
            }
        }
        if (!showLater.isEmpty()) {
            for (Map.Entry<UUID, Set<UUID>> s : showLater.entrySet()) {
                if (!s.getValue().remove(en.getUniqueId())) continue;
                Player v = Bukkit.getPlayer(s.getKey());
                if (v != null) v.showEntity(plugin, en);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(org.bukkit.event.entity.EntitySpawnEvent e) {
        if (hiddenCreatures.isEmpty() || !creature(e.getEntity())) return;
        for (Map.Entry<UUID, Set<UUID>> h : hiddenCreatures.entrySet()) {
            Player v = Bukkit.getPlayer(h.getKey());
            if (v == null || v.getWorld() != e.getEntity().getWorld() || level(v.getUniqueId()) < 3) continue;
            v.hideEntity(plugin, e.getEntity());
            h.getValue().add(e.getEntity().getUniqueId());
        }
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        if (level(e.getPlayer().getUniqueId()) >= 3) ghostOn(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        freeze.refreshIds();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) enter(p);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Bukkit.getScheduler().runTask(plugin, freeze::refreshIds);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        partner.remove(id);
        hiddenCreatures.remove(id);
        showLater.remove(id);
        freeze.forget(id);
        // talking ends when either leaves
        talks.remove(id);
        List<UUID> talkedWith = new ArrayList<>();
        for (Map.Entry<UUID, Set<UUID>> t : talks.entrySet()) if (t.getValue().remove(id)) talkedWith.add(t.getKey());
        talks.values().removeIf(Set::isEmpty);
        for (UUID staffId : talkedWith) {
            Player staff = Bukkit.getPlayer(staffId);
            if (staff != null) seenWhileTalking(staff);
        }
        Bukkit.getScheduler().runTask(plugin, freeze::refreshIds);
    }

    // ---------------------------------------------------------------- levels 2 and 3: nobody touches anybody

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    /** Frozen players do not hit, and ghosts are not hit: between a jammed player (2+) and the others, nothing lands. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!any()) return;
        Entity victim = e.getEntity();
        Player from = attacker(e.getDamager());
        if (victim instanceof Player v && level(v.getUniqueId()) >= 2) {
            if (from != null && !from.equals(v) && !inContact(v.getUniqueId(), from.getUniqueId())) e.setCancelled(true);
            if (from == null && level(v.getUniqueId()) >= 3 && (creature(e.getDamager())
                    || e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof LivingEntity)) e.setCancelled(true);
        }
        if (from != null && level(from.getUniqueId()) >= 2) {
            if (victim instanceof Player v && !v.equals(from) && !inContact(v.getUniqueId(), from.getUniqueId())) e.setCancelled(true);
            if (level(from.getUniqueId()) >= 3 && creature(victim)) e.setCancelled(true);
        }
    }

    /** In the ghost town no monster comes for them. */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && level(p.getUniqueId()) >= 3) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- the chat

    /** Whether this viewer sees what jammed players write (the team, marked). */
    private boolean seesJammed(Audience a) {
        return a instanceof Player v && v.hasPermission("staffcore.jammer") && plugin.getConfig().getBoolean("jammer.team-sees", true);
    }

    private Component tag(String name) {
        return lang().msg("jammer.tag").hoverEvent(HoverEvent.showText(lang().msg("jammer.tag-hover", "player", name)));
    }

    /** After everyone else has chosen who reads a message: jammed players are taken out of it. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!any()) return;
        Player sender = e.getPlayer();
        UUID sid = sender.getUniqueId();
        ChatRenderer before = e.renderer();
        if (isJammed(sid)) {
            // only they see it (and the team, marked); everything looks normal to them
            e.viewers().removeIf(a -> a instanceof Player v && !v.equals(sender) && !seesJammed(v) && !inContact(sid, v.getUniqueId()));
            String name = sender.getName();
            e.renderer((source, displayName, message, viewer) -> {
                Component c = before.render(source, displayName, message, viewer);
                return viewer instanceof Player v && !v.equals(source) && !inContact(sid, v.getUniqueId()) ? tag(name).append(c) : c;
            });
            return;
        }
        Set<UUID> line = talks.get(sid);
        if (line != null && !line.isEmpty()) {
            // a team member talking with jammed players: what they write goes to them only
            e.viewers().removeIf(a -> a instanceof Player v && !v.equals(sender) && !line.contains(v.getUniqueId()));
            List<String> names = new ArrayList<>();
            for (UUID id : line) {
                Entry en = jammed.get(id);
                if (en != null) names.add(en.name());
            }
            Component mark = lang().msg("jammer.talk-tag", "players", String.join(", ", names));
            e.renderer((source, displayName, message, viewer) -> {
                Component c = before.render(source, displayName, message, viewer);
                return viewer instanceof Player v && v.equals(source) ? mark.append(c) : c;
            });
            return;
        }
        e.viewers().removeIf(a -> a instanceof Player v && !v.equals(sender) && isJammed(v.getUniqueId()));
    }

    /** Private messages, /me and team messages: what a jammed player sends looks sent; what is sent to them looks delivered. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!any()) return;
        Player p = e.getPlayer();
        String[] parts = e.getMessage().substring(1).split(" ", 3);
        String label = parts[0].toLowerCase(Locale.ROOT);
        if (label.contains(":")) label = label.substring(label.indexOf(':') + 1);
        boolean meJammed = isJammed(p.getUniqueId());
        String rest = e.getMessage().substring(1).split(" ", 2).length > 1 ? e.getMessage().substring(1).split(" ", 2)[1] : "";

        if (WHISPER.contains(label) || REPLY.contains(label)) {
            boolean reply = REPLY.contains(label);
            Player target;
            String text;
            if (reply) {
                UUID last = partner.get(p.getUniqueId());
                target = last == null ? null : Bukkit.getPlayer(last);
                text = rest;
            } else {
                if (parts.length < 3) return;
                target = Bukkit.getPlayerExact(parts[1]);
                text = parts[2];
            }
            if (target == null || target.equals(p) || text.isBlank()) {
                // nobody to write to: a jammed player's /r must not reach the real last partner either
                if (reply && meJammed) e.setCancelled(true);
                return;
            }
            partner.put(p.getUniqueId(), target.getUniqueId());
            boolean cut = meJammed || isJammed(target.getUniqueId());
            if (!cut || inContact(p.getUniqueId(), target.getUniqueId())) {
                partner.put(target.getUniqueId(), p.getUniqueId());
                return;
            }
            // looks sent, reaches nobody
            e.setCancelled(true);
            p.sendMessage(outgoing(label, target, text));
            teamCopy(p, target, text);
            return;
        }
        if (EMOTE.contains(label) && meJammed) {
            if (rest.isBlank()) return;
            e.setCancelled(true);
            p.sendMessage(Component.translatable("chat.type.emote", p.displayName(), Component.text(rest)));
            return;
        }
        if (TEAM.contains(label) && !rest.isBlank()) {
            Team team = p.getScoreboard().getEntryTeam(p.getName());
            if (team == null) return;
            Component teamName = team.displayName();
            if (meJammed) {
                e.setCancelled(true);
                p.sendMessage(Component.translatable("chat.type.team.sent", teamName, p.displayName(), Component.text(rest)));
                return;
            }
            boolean jammedMate = false;
            for (String entry : team.getEntries()) {
                Player mate = Bukkit.getPlayerExact(entry);
                if (mate != null && isJammed(mate.getUniqueId())) jammedMate = true;
            }
            if (!jammedMate) return;
            // the same as the game does, without the jammed team mates
            e.setCancelled(true);
            p.sendMessage(Component.translatable("chat.type.team.sent", teamName, p.displayName(), Component.text(rest)));
            for (String entry : team.getEntries()) {
                Player mate = Bukkit.getPlayerExact(entry);
                if (mate == null || mate.equals(p) || isJammed(mate.getUniqueId())) continue;
                mate.sendMessage(Component.translatable("chat.type.team.text", teamName, p.displayName(), Component.text(rest)));
            }
        }
    }

    /** How the sender's own copy of a private message looks: like the game's, or like EssentialsX's. */
    private Component outgoing(String label, Player target, String text) {
        String owner = "";
        Command cmd = Bukkit.getCommandMap().getCommand(label);
        if (cmd instanceof PluginCommand pc) owner = pc.getPlugin().getName();
        if (owner.toLowerCase(Locale.ROOT).startsWith("essentials")) {
            return Component.text("[", NamedTextColor.GOLD)
                    .append(Component.text(lang().raw("jammer.me"), NamedTextColor.RED))
                    .append(Component.text(" -> ", NamedTextColor.GOLD))
                    .append(Component.text(target.getName(), NamedTextColor.RED))
                    .append(Component.text("] ", NamedTextColor.GOLD))
                    .append(Component.text(text, NamedTextColor.WHITE));
        }
        return Component.translatable("commands.message.display.outgoing", target.displayName(), Component.text(text))
                .color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC);
    }

    /** The team sees jammed private messages too, marked, so nothing is lost for moderation. */
    private void teamCopy(Player from, Player to, String text) {
        if (!plugin.getConfig().getBoolean("jammer.team-sees", true)) return;
        Component line = lang().msg("jammer.whisper", "from", from.getName(), "to", to.getName(), "text", text);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.equals(from) || staff.equals(to) || !staff.hasPermission("staffcore.jammer")) continue;
            staff.sendMessage(tag(isJammed(from.getUniqueId()) ? from.getName() : to.getName()).append(line));
        }
    }

    // ---------------------------------------------------------------- /jammer

    /** "1", "2", "3" or a word for a level; 0 if it is none. */
    static int parseLevel(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "1", "stumm", "silent", "mute" -> 1;
            case "2", "frozen", "freeze", "eingefroren", "frieren" -> 2;
            case "3", "ghost", "geist", "geisterstadt", "ghosttown" -> 3;
            default -> 0;
        };
    }

    @SuppressWarnings("deprecation")
    private static OfflinePlayer find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayerIfCached(name);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player p) menu.main(p, 0);
            else list(sender);
            return true;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("list") || first.equals("liste")) {
            list(sender);
            return true;
        }
        if (first.equals("help") || first.equals("hilfe")) {
            lang().send(sender, "jammer.usage");
            return true;
        }
        if (first.equals("out") || first.equals("aus") || first.equals("off")) {
            if (args.length < 2) {
                lang().send(sender, "jammer.usage");
                return true;
            }
            OfflinePlayer t = find(args[1]);
            String name = t == null || t.getName() == null ? args[1] : t.getName();
            if (t != null && lift(t.getUniqueId(), sender.getName())) lang().send(sender, "jammer.off", "player", name);
            else lang().send(sender, "jammer.not-jammed", "player", name);
            return true;
        }
        if (first.equals("talk") || first.equals("reden")) {
            if (!(sender instanceof Player staff) || args.length < 2) {
                lang().send(sender, "jammer.usage");
                return true;
            }
            OfflinePlayer t = find(args[1]);
            talk(staff, t, args[1]);
            return true;
        }
        OfflinePlayer target = find(args[0]);
        if (target == null) {
            lang().send(sender, "admin.not-online", "player", args[0]);
            return true;
        }
        String name = target.getName() == null ? args[0] : target.getName();
        if (args.length >= 2 && (args[1].equalsIgnoreCase("off") || args[1].equalsIgnoreCase("out") || args[1].equalsIgnoreCase("aus"))) {
            if (lift(target.getUniqueId(), sender.getName())) lang().send(sender, "jammer.off", "player", name);
            else lang().send(sender, "jammer.not-jammed", "player", name);
            return true;
        }
        if (args.length == 1 && sender instanceof Player staff) {
            // just a name: the player's page in the menu, to choose there
            menu.player(staff, target.getUniqueId(), name);
            return true;
        }
        int level = 1;
        int timeFrom = 1;
        if (args.length >= 2 && parseLevel(args[1]) > 0) {
            level = parseLevel(args[1]);
            timeFrom = 2;
        }
        long ms = 0;
        if (args.length > timeFrom) {
            ms = TimeUtil.parse(String.join(" ", java.util.Arrays.copyOfRange(args, timeFrom, args.length)));
            if (ms <= 0) {
                lang().send(sender, "jammer.usage");
                return true;
            }
        }
        apply(sender, target.getUniqueId(), name, level, ms);
        return true;
    }

    /** Jams (or changes the level), with the checks and the answer to the team member. */
    boolean apply(CommandSender sender, UUID id, String name, int level, long ms) {
        Player online = Bukkit.getPlayer(id);
        if (online != null && online.hasPermission("staffcore.exempt") && !sender.hasPermission("staffcore.exempt.bypass") && online != sender) {
            lang().send(sender, "error.exempt", "player", name);
            return false;
        }
        jam(id, name, sender.getName(), level, ms);
        lang().send(sender, "jammer.on", "player", name, "level", lang().raw("jammer.level." + level + ".short"),
                "time", ms > 0 ? TimeUtil.format(ms) : lang().raw("jammer.forever"));
        if (!plugin.voice().available()) lang().send(sender, "jammer.no-voice");
        return true;
    }

    void talk(Player staff, OfflinePlayer target, String typed) {
        String name = target == null || target.getName() == null ? typed : target.getName();
        if (target == null || target.getPlayer() == null) {
            lang().send(staff, "admin.not-online", "player", name);
            return;
        }
        if (!isJammed(target.getUniqueId()) && !inContact(staff.getUniqueId(), target.getUniqueId())) {
            lang().send(staff, "jammer.not-jammed", "player", name);
            return;
        }
        boolean now = toggleTalk(staff, target.getUniqueId());
        lang().send(staff, now ? "jammer.talk-on" : "jammer.talk-off", "player", name);
    }

    private void list(CommandSender sender) {
        List<Entry> list = all();
        if (list.isEmpty()) {
            lang().send(sender, "jammer.list-empty");
            return;
        }
        lang().send(sender, "jammer.list-head", "count", list.size());
        long now = System.currentTimeMillis();
        for (Entry en : list) {
            sender.sendMessage(lang().msg("jammer.list-line", "player", en.name(), "by", en.by(),
                    "level", lang().raw("jammer.level." + en.level() + ".short"),
                    "time", en.until() == 0 ? lang().raw("jammer.forever") : TimeUtil.format(en.until() - now)));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            for (String s : List.of("list", "out", "talk")) if (s.startsWith(last)) out.add(s);
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(last)) out.add(p.getName());
        } else if (args.length == 2) {
            String first = args[0].toLowerCase(Locale.ROOT);
            if (first.equals("out") || first.equals("aus") || first.equals("off")) {
                for (Entry e : all()) if (e.name().toLowerCase(Locale.ROOT).startsWith(last)) out.add(e.name());
            } else if (first.equals("talk") || first.equals("reden")) {
                Set<String> names = new HashSet<>();
                for (Entry e : all()) if (Bukkit.getPlayer(e.id()) != null) names.add(e.name());
                for (String n : names) if (n.toLowerCase(Locale.ROOT).startsWith(last)) out.add(n);
            } else {
                for (String s : List.of("1", "2", "3", "out")) if (s.startsWith(last)) out.add(s);
            }
        } else if (args.length == 3 && parseLevel(args[1]) > 0) {
            for (String s : List.of("10m", "1h", "1d", "7d")) if (s.startsWith(last)) out.add(s);
        }
        return out;
    }
}
