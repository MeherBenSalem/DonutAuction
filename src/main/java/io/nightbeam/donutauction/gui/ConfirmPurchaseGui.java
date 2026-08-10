package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.util.ItemBuilder;
import io.nightbeam.donutauction.util.MessageUtil;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class ConfirmPurchaseGui extends BaseGui {

    private static final int ITEM_SLOT = 22;
    private static final int CONFIRM_SLOT = 30;
    private static final int CANCEL_SLOT = 32;

    private final GuiManager guiManager;
    private final AuctionService auctionService;
    private final AuctionListing listing;

    public ConfirmPurchaseGui(GuiManager guiManager, AuctionService auctionService, AuctionListing listing) {
        this.guiManager = guiManager;
        this.auctionService = auctionService;
        this.listing = listing;
    }

    private MessageUtil messages() {
        return guiManager.plugin().messages();
    }

    @Override
    public Inventory render(Player player) {
        Inventory inventory = attach(Bukkit.createInventory(this, 45,
                messages().component("gui.titles.confirm-purchase", "&6ᴄᴏɴꜰɪʀᴍ ᴘᴜʀᴄʜᴀꜱᴇ")));

        ItemStack borderPane = ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                .name(Component.empty())
                .build();

        for (int slot = 0; slot < 45; slot++) {
            int row = slot / 9;
            int col = slot % 9;
            if (row == 0 || row == 4 || col == 0 || col == 8) {
                inventory.setItem(slot, borderPane);
            }
        }

        ItemStack displayItem = listing.item().clone();
        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.seller());
        String sellerName = seller.getName() == null ? messages().unknownSeller() : seller.getName();
        String formattedPrice = auctionService.formatPrice(listing.price());
        String balance = auctionService.formatPrice(auctionService.getBalance(player));

        displayItem.editMeta(meta -> {
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            List<net.kyori.adventure.text.Component> existingLore = meta.lore();
            if (existingLore != null) {
                lore.addAll(existingLore);
                lore.add(net.kyori.adventure.text.Component.empty());
            }
            lore.add(messages().component("gui.confirm-purchase.item-price", "&6Price: %price%", "price", formattedPrice));
            lore.add(messages().component("gui.listing-lore.seller", "&7Seller: %seller%", "seller", sellerName));
            lore.add(messages().component("gui.listing-lore.balance", "&bYour balance: %balance%", "balance", balance));
            meta.lore(lore);
        });

        inventory.setItem(ITEM_SLOT, displayItem);

        inventory.setItem(CONFIRM_SLOT, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(messages().component("gui.confirm-purchase.confirm", "Confirm Purchase"))
                .lore(
                        messages().component("gui.confirm-purchase.click-to-buy", "Click to buy this item"),
                        messages().component("gui.confirm-purchase.item-price", "&6Price: %price%", "price", formattedPrice)
                )
                .build());

        inventory.setItem(CANCEL_SLOT, ItemBuilder.of(Material.RED_STAINED_GLASS_PANE)
                .name(messages().component("gui.common.cancel", "Cancel"))
                .lore(messages().component("gui.confirm-purchase.cancel-lore", "Go back to the auction house"))
                .build());

        return inventory;
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();

        if (slot == CONFIRM_SLOT) {
            auctionService.purchaseAuction(player, listing.auctionId()).thenAccept(result ->
                    guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
                        guiManager.plugin().messages().send(player, result.message());
                        guiManager.refreshAuctionHouse(player);
                    })
            );
            return;
        }

        if (slot == CANCEL_SLOT) {
            guiManager.refreshAuctionHouse(player);
        }
    }
}
