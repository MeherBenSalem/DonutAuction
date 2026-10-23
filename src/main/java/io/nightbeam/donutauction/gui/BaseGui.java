package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.util.ItemBuilder;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public abstract class BaseGui implements InventoryHolder {

    private Inventory inventory;

    public abstract Inventory render(Player player);

    public abstract void handleClick(Player player, InventoryClickEvent event);

    @Override
    public final Inventory getInventory() {
        return inventory;
    }

    protected final Inventory attach(Inventory inventory) {
        this.inventory = inventory;
        return inventory;
    }

    /** A nameless gray pane for empty slots. */
    protected static ItemStack filler() {
        return ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).name(Component.empty()).build();
    }

    /** Fills every empty slot with {@link #filler()}. Call after placing the real items. */
    protected static void fill(Inventory inventory) {
        ItemStack pane = filler();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) inventory.setItem(slot, pane);
        }
    }

    /** Fills the given slots with {@link #filler()} when they are empty. */
    protected static void fill(Inventory inventory, int from, int to) {
        ItemStack pane = filler();
        for (int slot = from; slot <= to && slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) inventory.setItem(slot, pane);
        }
    }
}
