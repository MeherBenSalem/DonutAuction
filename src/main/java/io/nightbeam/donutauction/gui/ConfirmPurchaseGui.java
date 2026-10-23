package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.util.ItemBuilder;
import io.nightbeam.donutauction.util.MessageUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
    private final AtomicBoolean buying = new AtomicBoolean();

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

        ItemStack displayItem = listing.item().clone();
        String sellerName = guiManager.sellerName(listing);
        String formattedPrice = auctionService.formatPrecisePrice(listing.price());
        double balanceAmount = auctionService.getBalance(player);
        String balance = auctionService.formatPrice(balanceAmount);
        int amount = Math.max(1, displayItem.getAmount());

        displayItem.editMeta(meta -> {
            List<Component> lore = new ArrayList<>();
            List<Component> existingLore = meta.hasLore() ? meta.lore() : null;
            if (existingLore != null) {
                lore.addAll(existingLore);
                lore.add(Component.empty());
            }
            lore.add(MessageUtil.loreLine(messages().component("gui.confirm-purchase.item-price", "&6Price: %price%", "price", formattedPrice)));
            if (amount > 1) {
                lore.add(MessageUtil.loreLine(messages().component("gui.listing-lore.price-each", "&8(%price% each)",
                        "price", auctionService.formatPrice(listing.unitPrice()))));
            }
            lore.add(MessageUtil.loreLine(messages().component("gui.listing-lore.seller", "&7Seller: %seller%", "seller", sellerName)));
            lore.add(MessageUtil.loreLine(messages().component("gui.listing-lore.balance", "&bYour balance: %balance%", "balance", balance)));
            meta.lore(lore);
        });

        inventory.setItem(ITEM_SLOT, displayItem);

        boolean affordable = balanceAmount + 1e-9 >= listing.price();
        inventory.setItem(CONFIRM_SLOT, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(messages().component("gui.confirm-purchase.confirm", "&aConfirm Purchase"))
                .lore(
                        messages().component("gui.confirm-purchase.click-to-buy", "Click to buy this item"),
                        messages().component("gui.confirm-purchase.item-price", "&6Price: %price%", "price", formattedPrice),
                        affordable
                                ? messages().component("gui.confirm-purchase.after", "&7Balance after: &f%balance%",
                                "balance", auctionService.formatPrice(balanceAmount - listing.price()))
                                : messages().component("gui.confirm-purchase.cannot-afford", "&cYou cannot afford this")
                )
                .build());

        inventory.setItem(CANCEL_SLOT, ItemBuilder.of(Material.RED_STAINED_GLASS_PANE)
                .name(messages().component("gui.common.cancel", "&cCancel"))
                .lore(messages().component("gui.confirm-purchase.cancel-lore", "Go back to the auction house"))
                .build());

        fill(inventory);
        return inventory;
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();

        if (slot == CONFIRM_SLOT) {
            if (!buying.compareAndSet(false, true)) {
                // A double-click: the first click is already buying.
                return;
            }
            auctionService.purchaseAuction(player, listing.auctionId()).thenAccept(result ->
                    guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
                        guiManager.plugin().messages().send(player, result.message());
                        guiManager.feedback(player, result);
                        guiManager.refreshAuctionHouse(player);
                    })
            );
            return;
        }

        if (slot == CANCEL_SLOT) {
            guiManager.click(player);
            guiManager.refreshAuctionHouse(player);
        }
    }
}
