package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.service.ActionResult;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.util.ItemBuilder;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class ConfirmPurchaseGui extends BaseGui {

    private static final Component TITLE = Component.text("ᴄᴏɴꜰɪʀᴍ ᴘᴜʀᴄʜᴀꜱᴇ", NamedTextColor.GOLD);

    private final GuiManager guiManager;
    private final AuctionService auctionService;
    private final AuctionListing listing;

    public ConfirmPurchaseGui(GuiManager guiManager, AuctionService auctionService, AuctionListing listing) {
        this.guiManager = guiManager;
        this.auctionService = auctionService;
        this.listing = listing;
    }

    @Override
    public Inventory render(Player player) {
        Inventory inventory = attach(Bukkit.createInventory(this, 27, TITLE));

        ItemStack displayItem = listing.item().clone();
        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.seller());
        String sellerName = seller.getName() == null ? "Unknown" : seller.getName();
        displayItem.editMeta(meta -> {
            List<Component> existingLore = meta.lore();
            java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
            if (existingLore != null) {
                lore.addAll(existingLore);
                lore.add(Component.empty());
            }
            lore.add(Component.text("Price: " + auctionService.formatPrice(listing.price()), NamedTextColor.GOLD));
            lore.add(Component.text("Seller: " + sellerName, NamedTextColor.GRAY));
            meta.lore(lore);
        });

        inventory.setItem(11, displayItem);

        inventory.setItem(15, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.text("Confirm Purchase", NamedTextColor.GREEN))
                .lore(
                        Component.text("Click to buy this item", NamedTextColor.GRAY),
                        Component.text("Price: " + auctionService.formatPrice(listing.price()), NamedTextColor.GOLD)
                )
                .build());

        inventory.setItem(22, ItemBuilder.of(Material.RED_STAINED_GLASS_PANE)
                .name(Component.text("Cancel", NamedTextColor.RED))
                .lore(Component.text("Go back to the auction house", NamedTextColor.GRAY))
                .build());

        return inventory;
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();

        if (slot == 15) {
            auctionService.purchaseAuction(player, listing.auctionId()).thenAccept(result ->
                    guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
                        guiManager.plugin().messages().send(player, result.message());
                        guiManager.refreshAuctionHouse(player);
                    })
            );
            return;
        }

        if (slot == 22) {
            guiManager.refreshAuctionHouse(player);
        }
    }
}
