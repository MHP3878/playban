package dev.staffcore.gui;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import dev.staffcore.TaskActions;
import dev.staffcore.TimeUtil;
import dev.staffcore.model.Punishment;
import dev.staffcore.model.Punishment.TaskProgress;
import dev.staffcore.model.TaskDef;
import dev.staffcore.model.TaskStatus;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Menus for the punished player. */
public final class PlayerMenus {
    private static final int[] TASK_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private final StaffCorePlugin plugin;

    public PlayerMenus(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    public List<Component> describe(TaskDef def, Punishment p) {
        String key = "task." + def.type.name() + ".desc";
        return switch (def.type) {
            case STAFF_MESSAGE -> lang().lore(key, "min", def.minLength);
            case NICE_MESSAGE -> lang().lore(key, "target", plugin.punishments().targetLabel(def, p), "min", def.minLength);
            case GIFT_MONEY -> lang().lore(key, "target", plugin.punishments().targetLabel(def, p), "amount", TaskActions.money(def.amount));
            case DONATE_ITEMS -> lang().lore(key, "amount", (int) def.amount, "item", TaskActions.itemName(def));
            case QUIZ -> lang().lore(key, "count", def.questions.size());
        };
    }

    public String rewardText(TaskDef def, Punishment p) {
        return def.reduction.trim().endsWith("%") && p == null
                ? def.reduction.trim()
                : TimeUtil.format(def.reductionMs(p == null ? 0 : p.originalMs));
    }

    public void status(Player player) {
        Punishment p = plugin.punishments().get(player.getUniqueId());
        if (p == null) {
            lang().send(player, "error.not-punished-self");
            return;
        }
        Menu m = new Menu(6, lang().msg("menu.status.title"));
        m.set(4, Items.of(Material.CLOCK, lang().msg("menu.status.info.name"),
                lang().lore("menu.status.info.lore", "time", TimeUtil.format(Math.max(0, p.remainingMs)),
                        "reason", p.reason, "staff", p.staff,
                        "mode", lang().msg(plugin.punishments().onlineOnly() ? "mode.online" : "mode.real"))));
        int i = 0;
        for (Map.Entry<String, TaskProgress> e : p.tasks.entrySet()) {
            TaskDef def = plugin.tasks().get(e.getKey());
            if (def == null || i >= TASK_SLOTS.length) continue;
            TaskProgress t = e.getValue();
            List<Component> lore = new ArrayList<>(describe(def, p));
            lore.add(Component.empty());
            lore.add(lang().msg("menu.status.task.reward", "time", rewardText(def, p)));
            if (def.timeLimitMs > 0 && t.status == TaskStatus.OPEN) {
                lore.add(lang().msg("menu.status.task.time-left", "time", TimeUtil.format(Math.max(0, t.timeLeftMs))));
            }
            lore.add(lang().msg("status." + t.status.name()));
            if (t.status == TaskStatus.OPEN) lore.add(lang().msg("menu.status.task.click"));
            Material icon = switch (t.status) {
                case OPEN -> def.type.icon;
                case PENDING -> Material.CLOCK;
                case DONE -> Material.LIME_DYE;
                case EXPIRED -> Material.GRAY_DYE;
            };
            m.set(TASK_SLOTS[i++], Items.of(icon, plugin.punishments().taskName(def, p), lore),
                    t.status == TaskStatus.OPEN ? ev -> { player.closeInventory(); plugin.taskActions().start(player, def); } : null);
        }
        if (i == 0) m.set(22, Items.of(Material.BARRIER, lang().msg("menu.status.no-tasks.name"), lang().lore("menu.status.no-tasks.lore")));
        m.set(48, Items.of(Material.PAPER, lang().msg("menu.status.ticket.name"), lang().lore("menu.status.ticket.lore")),
                ev -> plugin.ticketMenus().pickReason(player));
        m.set(50, Items.of(Material.OAK_SIGN, lang().msg("menu.status.commands.name"),
                commandLore()));
        m.fill().open(player);
    }

    private List<Component> commandLore() {
        List<Component> lore = new ArrayList<>(lang().lore("menu.status.commands.lore"));
        lore.add(lang().msg("menu.status.commands.entry", "command", "playban"));
        lore.add(lang().msg("menu.status.commands.entry", "command", "ticket"));
        for (String c : plugin.getConfig().getStringList("allowed-commands")) {
            lore.add(lang().msg("menu.status.commands.entry", "command", c));
        }
        return lore;
    }

    public void quiz(Player player, TaskDef def, int index) {
        Punishment p = plugin.punishments().get(player.getUniqueId());
        if (p == null) return;
        TaskProgress t = p.tasks.get(def.id);
        if (t == null || t.status != TaskStatus.OPEN) return;
        if (index >= def.questions.size()) {
            player.closeInventory();
            plugin.punishments().completeTask(p, def);
            return;
        }
        TaskDef.Question q = def.questions.get(index);
        Menu m = new Menu(4, lang().msg("menu.quiz.title", "n", index + 1, "total", def.questions.size()));
        List<Component> questionLore = new ArrayList<>();
        for (String line : TimeUtil.wrap(q.question, 40)) questionLore.add(lang().msg("menu.quiz.question-line", "text", line));
        m.set(4, Items.of(Material.BOOK, lang().msg("menu.quiz.question"), questionLore));
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < Math.min(q.answers.size(), 7); i++) order.add(i);
        Collections.shuffle(order);
        int slot = 19 + (7 - order.size()) / 2;
        for (int answer : order) {
            m.set(slot++, Items.of(Material.PAPER, lang().msg("menu.quiz.answer", "text", q.answers.get(answer)),
                    lang().lore("menu.quiz.answer-lore")), ev -> {
                if (answer == 0) {
                    quiz(player, def, index + 1);
                } else {
                    player.closeInventory();
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                    TaskProgress progress = p.tasks.get(def.id);
                    int max = Math.max(1, plugin.getConfig().getInt("quiz-attempts", 3));
                    if (progress != null && ++progress.attempts >= max) {
                        progress.status = TaskStatus.EXPIRED;
                        lang().send(player, "task.quiz-failed", "max", max);
                    } else {
                        lang().send(player, "task.quiz-wrong", "left", max - (progress == null ? 0 : progress.attempts));
                    }
                    plugin.store().markDirty();
                }
            });
        }
        m.fill().open(player);
    }
}
