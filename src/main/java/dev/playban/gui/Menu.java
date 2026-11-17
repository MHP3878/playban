package dev.playban.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** A chest menu where every slot can have a click action. */
public class Menu implements InventoryHolder {
    private final Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    public Menu(int rows, Component title) {
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Menu set(int slot, ItemStack item) {
        return set(slot, item, null);
    }

    public Menu set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        if (slot < 0 || slot >= inventory.getSize()) return this;
        inventory.setItem(slot, item);
        if (action == null) actions.remove(slot);
        else actions.put(slot, action);
        return this;
    }

    /** Empties the menu so it can be drawn again while it stays open. */
    public void clear() {
        inventory.clear();
        actions.clear();
    }

    /** Frames the menu: dark border, lighter inside. */
    public Menu fill() {
        int size = inventory.getSize();
        ItemStack border = Items.filler(Material.BLACK_STAINED_GLASS_PANE);
        ItemStack inner = Items.filler(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < size; i++) {
            if (inventory.getItem(i) != null) continue;
            int col = i % 9;
            boolean edge = i < 9 || i >= size - 9 || col == 0 || col == 8;
            inventory.setItem(i, edge ? border : inner);
        }
        return this;
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    void click(InventoryClickEvent event) {
        Consumer<InventoryClickEvent> action = actions.get(event.getRawSlot());
        if (action == null) return;
        if (event.getWhoClicked() instanceof Player p) p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        action.accept(event);
    }
}
