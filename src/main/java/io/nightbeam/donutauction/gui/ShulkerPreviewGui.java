package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.util.MessageUtil;
import io.nightbeam.donutauction.util.ShulkerBoxSupport;
import java.util.List;
import net.kyori.adventure.text.Component;
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

    public ShulkerPreviewGui(GuiManager guiManager, ItemStack shulkerItem) {
        this.guiManager = guiManager;
        this.shulkerItem = shulkerItem.clone();
    }

    private MessageUtil messages() {
        return guiManager.plugin().messages();
    }

    @Override
    public Inventory render(Player player) {
        Inventory inventory = attach(Bukkit.createInventory(this, 54, buildTitle()));

        List<ItemStack> contents = ShulkerBoxSupport.getContents(shulkerItem);
        for (int i = 0; i < Math.min(27, contents.size()); i++) {
            inventory.setItem(i, contents.get(i).clone());
        }

        inventory.setItem(49, io.nightbeam.donutauction.util.ItemBuilder.of(Material.ARROW)
                .name(messages().component("gui.common.back-to-auction", "Back to Auction"))
                .lore(messages().component("gui.common.back-to-auction-lore", "Return to the auction browser"))
                .build());

        return inventory;
    }

    private Component buildTitle() {
        MessageUtil messages = messages();
        ItemMeta meta = shulkerItem.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return messages.component(messages.raw("gui.common.preview-prefix", "Preview: "))
                    .append(meta.displayName());
        }
        String name = shulkerItem.getType().name().replace('_', ' ').toLowerCase();
        name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return messages.component(messages.raw("gui.common.preview-prefix", "Preview: ") + name);
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
