package dev.staffcore.model;

import org.bukkit.Material;

public enum TaskType {
    STAFF_MESSAGE(Material.POPPY),
    NICE_MESSAGE(Material.WRITABLE_BOOK),
    GIFT_MONEY(Material.GOLD_INGOT),
    DONATE_ITEMS(Material.CHEST),
    QUIZ(Material.KNOWLEDGE_BOOK);

    public final Material icon;

    TaskType(Material icon) {
        this.icon = icon;
    }
}
