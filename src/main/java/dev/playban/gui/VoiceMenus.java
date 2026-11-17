package dev.playban.gui;

import dev.playban.Lang;
import dev.playban.PlayBanPlugin;
import dev.playban.voice.VoiceBackend.GroupInfo;
import dev.playban.voice.VoiceManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Menus for voice groups: the team's control panel, the small one for group owners, and reporting. */
public final class VoiceMenus {
    private final PlayBanPlugin plugin;

    public VoiceMenus(PlayBanPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private VoiceManager voice() {
        return plugin.voice();
    }

    private AdminMenus admin() {
        return plugin.adminMenus();
    }

    private ItemStack item(Material material, String key, Object... kv) {
        return admin().item(material, key, kv);
    }

    private String name(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p != null ? p.getName() : plugin.store().names.getOrDefault(id, "?");
    }

    private boolean ready(Player p) {
        if (voice().available()) return true;
        lang().send(p, "voice.not-installed");
        return false;
    }

    // ---------------------------------------------------------------- team

    public void groups(Player p) {
        if (!ready(p)) return;
        List<GroupInfo> list = voice().groups();
        Menu m = admin().paged(lang().msg("menu.voice.title"), list, 0, g -> {
            UUID owner = voice().owner(g.id());
            return Items.of(g.password() ? Material.IRON_DOOR : Material.JUKEBOX, lang().msg("menu.voice.group.name", "group", g.name()),
                    lang().lore("menu.voice.group.lore", "members", g.members().size(),
                            "password", lang().msg(g.password() ? "voice.yes" : "voice.no"),
                            "hidden", lang().msg(g.hidden() ? "voice.yes" : "voice.no"),
                            "owner", owner == null ? lang().raw("none") : name(owner)));
        }, (g, e) -> group(p, g.id()), n -> {}, () -> admin().main(p));
        if (list.isEmpty()) m.set(22, item(Material.GRAY_DYE, "menu.voice.empty"));
        boolean unseen = voice().prefersInvisible(p.getUniqueId());
        m.set(51, item(unseen ? Material.GLASS : Material.PLAYER_HEAD, "menu.voice.prefer",
                "value", lang().msg(unseen ? "voice.unseen" : "voice.seen")), e -> {
            voice().setPrefersInvisible(p.getUniqueId(), !unseen);
            groups(p);
        });
        m.open(p);
    }

    /** The "three dots" of one group: everything the team can do with it. */
    public void group(Player p, UUID id) {
        GroupInfo g = voice().group(id);
        if (g == null) {
            groups(p);
            return;
        }
        Menu m = new Menu(6, lang().msg("menu.voice.group-title", "group", g.name()));
        for (int i = 0; i < g.members().size() && i < 27; i++) {
            UUID member = g.members().get(i);
            List<Component> lore = new ArrayList<>();
            if (member.equals(voice().owner(id))) lore.add(lang().msg("menu.voice.member.owner"));
            if (voice().isMuted(member)) lore.add(lang().msg("menu.voice.member.muted"));
            if (voice().isDeaf(member)) lore.add(lang().msg("menu.voice.member.deaf"));
            if (voice().isInvisible(member)) lore.add(lang().msg("menu.voice.member.unseen"));
            lore.addAll(lang().lore("menu.voice.member.actions"));
            m.set(i, Items.head(Bukkit.getOfflinePlayer(member), lang().msg("menu.ticket.person.name", "player", name(member)), lore),
                    e -> member(p, id, member));
        }
        boolean inside = id.equals(voice().groupOf(p.getUniqueId()));
        if (!inside) {
            boolean unseen = voice().prefersInvisible(p.getUniqueId());
            m.set(37, item(Material.LIME_DYE, "menu.voice.join", "mode", lang().msg(unseen ? "voice.unseen" : "voice.seen")), e -> {
                voice().join(p, id, e.isRightClick() != unseen);
                group(p, id);
            });
        } else {
            boolean unseen = voice().isInvisible(p.getUniqueId());
            m.set(37, item(unseen ? Material.GLASS : Material.PLAYER_HEAD, "menu.voice.visibility",
                    "value", lang().msg(unseen ? "voice.unseen" : "voice.seen")), e -> {
                voice().setInvisible(p, !unseen);
                group(p, id);
            });
        }
        String known = voice().knownPassword(id);
        m.set(39, item(Material.TRIPWIRE_HOOK, g.password() ? "menu.voice.password-change" : "menu.voice.password-set"), e -> {
            if (e.isShiftClick() && g.password()) {
                if (!voice().setPassword(id, null)) lang().send(p, "voice.failed");
                group(p, id);
                return;
            }
            plugin.input().ask(p, "prompt.voice-password", text -> {
                if (text.length() > 64 || text.contains(" ")) lang().send(p, "voice.bad-password");
                else if (!voice().setPassword(id, text)) lang().send(p, "voice.failed");
                group(p, id);
            }, () -> group(p, id));
        });
        if (g.password()) {
            m.set(40, item(Material.PAPER, known == null ? "menu.voice.password-unknown" : "menu.voice.password-send"), e -> {
                if (known == null) return;
                p.closeInventory();
                p.sendMessage(lang().prefixed("voice.password", "group", g.name(), "password", known)
                        .clickEvent(ClickEvent.copyToClipboard(known)));
            });
        }
        m.set(41, item(Material.IRON_BARS, "menu.voice.bans", "count", voice().banned(id).size()), e -> bans(p, id, true));
        m.set(43, item(Material.TNT, "menu.voice.close"), e -> {
            if (!e.isShiftClick()) return;
            voice().close(id, p.getName());
            groups(p);
        });
        m.set(49, item(Material.OAK_DOOR, "menu.back"), e -> groups(p));
        m.fill().open(p);
    }

    private void member(Player p, UUID group, UUID target) {
        Menu m = new Menu(3, lang().msg("menu.voice.member-title", "player", name(target)));
        boolean muted = voice().isMuted(target);
        boolean deaf = voice().isDeaf(target);
        m.set(10, item(Material.OAK_DOOR, "menu.voice.kick"), e -> {
            voice().kick(group, target, p.getName());
            group(p, group);
        });
        m.set(11, item(muted ? Material.LIME_DYE : Material.RED_DYE, muted ? "menu.voice.unmute" : "menu.voice.mute"), e -> {
            voice().setMuted(target, !muted, p.getName());
            member(p, group, target);
        });
        m.set(12, item(deaf ? Material.LIME_DYE : Material.RED_DYE, deaf ? "menu.voice.undeafen" : "menu.voice.deafen"), e -> {
            voice().setDeaf(target, !deaf, p.getName());
            member(p, group, target);
        });
        m.set(13, item(Material.IRON_BARS, "menu.voice.ban"), e -> {
            voice().ban(group, target, p.getName());
            group(p, group);
        });
        m.set(15, item(Material.BOOK, "menu.detail.profile"), e -> admin().profile(p, target, name(target)));
        m.set(16, item(Material.ARROW, "menu.back"), e -> group(p, group));
        m.fill().open(p);
    }

    private void bans(Player p, UUID group, boolean staff) {
        List<UUID> list = voice().banned(group);
        Menu m = admin().paged(lang().msg("menu.voice.bans-title"), list, 0,
                id -> Items.head(Bukkit.getOfflinePlayer(id), lang().msg("menu.ticket.person.name", "player", name(id)), lang().lore("menu.voice.unban")),
                (id, e) -> {
                    voice().unban(group, id);
                    bans(p, group, staff);
                }, n -> {}, () -> {
                    if (staff) group(p, group);
                    else manage(p);
                });
        if (list.isEmpty()) m.set(22, item(Material.LIME_DYE, "menu.voice.no-bans"));
        m.open(p);
    }

    // ---------------------------------------------------------------- group owner

    /** Whoever created a group can kick and ban in it. */
    public void manage(Player p) {
        if (!ready(p)) return;
        UUID id = voice().groupOf(p.getUniqueId());
        GroupInfo g = id == null ? null : voice().group(id);
        if (g == null || !p.getUniqueId().equals(voice().owner(id))) {
            lang().send(p, "voice.not-owner");
            return;
        }
        List<UUID> others = new ArrayList<>();
        for (UUID member : g.members()) if (!member.equals(p.getUniqueId()) && !voice().isInvisible(member)) others.add(member);
        Menu m = admin().paged(lang().msg("menu.voice.manage-title", "group", g.name()), others, 0,
                member -> Items.head(Bukkit.getOfflinePlayer(member), lang().msg("menu.ticket.person.name", "player", name(member)),
                        lang().lore("menu.voice.manage-actions")),
                (member, e) -> {
                    // team members cannot be thrown out by a group owner
                    Player target = Bukkit.getPlayer(member);
                    if (target != null && target.hasPermission("playban.voice")) {
                        lang().send(p, "error.no-permission");
                        return;
                    }
                    if (e.isShiftClick()) voice().ban(id, member, p.getName());
                    else voice().kick(id, member, p.getName());
                    manage(p);
                }, n -> {}, p::closeInventory);
        if (others.isEmpty()) m.set(22, item(Material.GRAY_DYE, "menu.voice.alone"));
        m.set(51, item(Material.IRON_BARS, "menu.voice.bans", "count", voice().banned(id).size()), e -> bans(p, id, false));
        m.open(p);
    }

    // ---------------------------------------------------------------- reporting

    public void report(Player p) {
        if (!ready(p)) return;
        UUID own = voice().groupOf(p.getUniqueId());
        if (own != null) {
            reportReasons(p, own);
            return;
        }
        List<GroupInfo> list = new ArrayList<>();
        for (GroupInfo g : voice().groups()) if (!g.hidden()) list.add(g);
        Menu m = admin().paged(lang().msg("menu.voice.report-title"), list, 0,
                g -> Items.of(Material.JUKEBOX, lang().msg("menu.voice.group.name", "group", g.name()), lang().lore("menu.voice.report-pick")),
                (g, e) -> reportReasons(p, g.id()), n -> {}, p::closeInventory);
        if (list.isEmpty()) m.set(22, item(Material.GRAY_DYE, "menu.voice.empty"));
        m.open(p);
    }

    private void reportReasons(Player p, UUID group) {
        GroupInfo g = voice().group(group);
        if (g == null) return;
        Menu m = new Menu(3, lang().msg("menu.voice.report-why", "group", g.name()));
        int slot = 10;
        for (String reason : plugin.getConfig().getStringList("voice-report-reasons")) {
            if (slot > 16) break;
            m.set(slot++, Items.of(Material.PAPER, lang().msg("menu.voice.report-reason", "reason", reason), lang().lore("menu.voice.report-pick")), e -> {
                p.closeInventory();
                voice().report(p, group, reason);
            });
        }
        m.fill().open(p);
    }
}
