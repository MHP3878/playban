package dev.staffcore.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

public final class Items {
    private Items() {}

    private static Component plain(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static ItemStack of(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        apply(meta, name, lore);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack head(OfflinePlayer owner, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (owner != null) meta.setOwningPlayer(owner);
        apply(meta, name, lore);
        item.setItemMeta(meta);
        return item;
    }

    private static void apply(ItemMeta meta, Component name, List<Component> lore) {
        meta.displayName(plain(name));
        if (lore != null && !lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (Component c : lore) lines.add(plain(c));
            meta.lore(lines);
        }
    }

    public static ItemStack filler(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        meta.setHideTooltip(true);
        item.setItemMeta(meta);
        return item;
    }
}
