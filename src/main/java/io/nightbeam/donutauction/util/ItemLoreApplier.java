package io.nightbeam.donutauction.util;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class ItemLoreApplier {

    public enum LoreMode {
        APPEND,
        REPLACE,
        DISABLED
    }

    private ItemLoreApplier() {
    }

    public static void applyLore(ItemStack item, LoreMode mode, boolean showSeparator, List<Component> auctionLore) {
        if (mode == LoreMode.DISABLED) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        if (mode == LoreMode.REPLACE) {
            meta.lore(auctionLore);
        } else if (mode == LoreMode.APPEND) {
            List<Component> existingLore = meta.lore();
            List<Component> combined = new ArrayList<>();

            if (existingLore != null && !existingLore.isEmpty()) {
                combined.addAll(existingLore);
                if (showSeparator) {
                    combined.add(Component.text("————————————————", NamedTextColor.DARK_GRAY));
                }
            }

            combined.addAll(auctionLore);
            meta.lore(combined);
        }

        item.setItemMeta(meta);
    }

    public static LoreMode parseMode(String value) {
        if (value == null) {
            return LoreMode.APPEND;
        }
        try {
            return LoreMode.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            return LoreMode.APPEND;
        }
    }
}
