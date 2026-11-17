package dev.staffcore;

import dev.staffcore.model.Punishment;
import dev.staffcore.model.Punishment.TaskProgress;
import dev.staffcore.model.TaskDef;
import dev.staffcore.model.TaskStatus;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.function.Consumer;

/** What happens when a punished player clicks one of their tasks. */
public final class TaskActions {
    private final StaffCorePlugin plugin;

    public TaskActions(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Still punished, and this task still open? Things can change while the player types. */
    private boolean stillOpen(Player player, TaskDef def) {
        Punishment p = plugin.punishments().get(player.getUniqueId());
        if (p == null) return false;
        TaskProgress t = p.tasks.get(def.id);
        if (t == null || t.status != TaskStatus.OPEN) {
            plugin.lang().send(player, "task.not-open");
            return false;
        }
        return true;
    }

    public void start(Player player, TaskDef def) {
        if (!stillOpen(player, def)) return;
        Punishment p = plugin.punishments().get(player.getUniqueId());
        switch (def.type) {
            case STAFF_MESSAGE -> askText(player, def, "prompt.staff-message", text ->
                    plugin.punishments().submitReview(player, p, def, null, null, text));
            case NICE_MESSAGE -> withTarget(player, p, def, target ->
                    askText(player, def, "prompt.nice-message", text ->
                            plugin.punishments().submitReview(player, p, def, target.getUniqueId(), target.getName(), text),
                            "player", String.valueOf(target.getName())));
            case GIFT_MONEY -> withTarget(player, p, def, target -> giftMoney(player, p, def, target));
            case DONATE_ITEMS -> donate(player, p, def);
            case QUIZ -> plugin.playerMenus().quiz(player, def, 0);
        }
    }

    private void askText(Player player, TaskDef def, String promptKey, Consumer<String> then, Object... kv) {
        Object[] args = new Object[kv.length + 2];
        System.arraycopy(kv, 0, args, 0, kv.length);
        args[kv.length] = "min";
        args[kv.length + 1] = def.minLength;
        plugin.input().ask(player, promptKey, text -> {
            if (!stillOpen(player, def)) return;
            if (text.length() < def.minLength) {
                plugin.lang().send(player, "task.too-short", "min", def.minLength);
                return;
            }
            then.accept(text);
        }, null, args);
    }

    private void withTarget(Player player, Punishment p, TaskDef def, Consumer<OfflinePlayer> then) {
        if ("VICTIM".equals(def.target) && p.victim != null) {
            then.accept(Bukkit.getOfflinePlayer(p.victim));
            return;
        }
        plugin.input().ask(player, "prompt.target-name", name -> {
            if (!stillOpen(player, def)) return;
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(name);
            if (target == null || target.getName() == null || (!target.isOnline() && !target.hasPlayedBefore())) {
                plugin.lang().send(player, "error.unknown-player", "player", name);
                return;
            }
            if (target.getUniqueId().equals(player.getUniqueId())) {
                plugin.lang().send(player, "task.not-yourself");
                return;
            }
            then.accept(target);
        }, null);
    }

    private void giftMoney(Player player, Punishment p, TaskDef def, OfflinePlayer target) {
        EconomyHook eco = plugin.economy();
        if (!eco.available()) {
            plugin.lang().send(player, "task.no-economy");
            return;
        }
        if (eco.balance(player) < def.amount) {
            plugin.lang().send(player, "task.not-enough-money", "amount", money(def.amount));
            return;
        }
        if (!eco.transfer(player, target, def.amount)) {
            plugin.lang().send(player, "task.payment-failed");
            return;
        }
        plugin.punishments().deliver(target.getUniqueId(),
                plugin.lang().prefixed("task.gift-received", "player", player.getName(), "amount", money(def.amount)));
        plugin.punishments().completeTask(p, def);
    }

    private void donate(Player player, Punishment p, TaskDef def) {
        int needed = (int) def.amount;
        if (!player.getInventory().containsAtLeast(new ItemStack(def.material), needed)) {
            plugin.lang().send(player, "task.not-enough-items", "amount", needed, "item", itemName(def));
            return;
        }
        player.getInventory().removeItem(new ItemStack(def.material, needed));
        plugin.punishments().completeTask(p, def);
    }

    public static String money(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.format("%.2f", amount);
    }

    public static String itemName(TaskDef def) {
        String[] words = def.material.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return sb.toString().trim();
    }
}
