package io.nightbeam.donutauction.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Whole-stack delivery: an item is given only when all of it fits, otherwise it waits in the collection. */
public final class InventorySpace {

    private InventorySpace() {
    }

    public static boolean fits(PlayerInventory inventory, ItemStack item) {
        if (item == null || item.getType().isAir()) return true;
        int needed = item.getAmount();
        int max = Math.max(1, item.getMaxStackSize());
        for (ItemStack slot : inventory.getStorageContents()) {
            if (slot == null || slot.getType().isAir()) {
                needed -= max;
            } else if (slot.isSimilar(item)) {
                needed -= Math.max(0, max - slot.getAmount());
            }
            if (needed <= 0) return true;
        }
        return false;
    }
}
