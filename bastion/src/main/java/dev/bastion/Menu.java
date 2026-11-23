package dev.bastion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** A chest menu: items with click actions, nothing can be taken out. */
public final class Menu implements InventoryHolder {
    private final Inventory inv;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    public Menu(int rows, Component title) {
        inv = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    public Menu set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        inv.setItem(slot, item);
        if (action != null) actions.put(slot, action);
        else actions.remove(slot);
        return this;
    }

    public Menu fill() {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, pane);
        return this;
    }

    public void open(Player p) {
        p.openInventory(inv);
    }

    public static ItemStack item(Material m, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack head(org.bukkit.OfflinePlayer owner, Component name, List<Component> lore) {
        ItemStack it = item(Material.PLAYER_HEAD, name, lore);
        if (it.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(owner);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** Listens for clicks in every Menu. */
    public static final class Clicks implements Listener {
        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
            e.setCancelled(true);
            if (e.getClickedInventory() != m.inv) return;
            Consumer<InventoryClickEvent> a = m.actions.get(e.getRawSlot());
            if (a != null) a.accept(e);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
        }
    }
}
