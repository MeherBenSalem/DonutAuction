package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.util.ShulkerBoxSupport;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class ShulkerPreviewGui extends BaseGui {

    private final GuiManager guiManager;
    private final ItemStack shulkerItem;
    private final Component title;

    public ShulkerPreviewGui(GuiManager guiManager, ItemStack shulkerItem) {
        this.guiManager = guiManager;
        this.shulkerItem = shulkerItem.clone();

        ItemMeta meta = shulkerItem.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            this.title = Component.text("Preview: ").append(meta.displayName());
        } else {
            String name = shulkerItem.getType().name().replace('_', ' ').toLowerCase();
            name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            this.title = Component.text("Preview: " + name, NamedTextColor.GOLD);
        }
    }

    @Override
    public Inventory render(Player player) {
        Inventory inventory = attach(Bukkit.createInventory(this, 54, title));

        List<ItemStack> contents = ShulkerBoxSupport.getContents(shulkerItem);
        for (int i = 0; i < Math.min(27, contents.size()); i++) {
            inventory.setItem(i, contents.get(i).clone());
        }

        inventory.setItem(49, io.nightbeam.donutauction.util.ItemBuilder.of(Material.ARROW)
                .name(Component.text("Back to Auction", NamedTextColor.WHITE))
                .lore(Component.text("Return to the auction browser", NamedTextColor.GRAY))
                .build());

        return inventory;
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        if (event.getSlot() == 49) {
            guiManager.refreshAuctionHouse(player);
        }
    }
}
