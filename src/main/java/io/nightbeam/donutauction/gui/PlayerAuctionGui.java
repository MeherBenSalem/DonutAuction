package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionStatus;
import io.nightbeam.donutauction.service.ActionResult;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.util.ItemBuilder;
import io.nightbeam.donutauction.util.ItemLoreApplier;
import io.nightbeam.donutauction.util.MessageUtil;
import io.nightbeam.donutauction.util.TimeUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class PlayerAuctionGui extends BaseGui {

    private final GuiManager guiManager;
    private final AuctionService auctionService;
    private final Map<Integer, UUID> slotMappings = new HashMap<>();
    private int page;

    public PlayerAuctionGui(GuiManager guiManager, AuctionService auctionService, int page) {
        this.guiManager = guiManager;
        this.auctionService = auctionService;
        this.page = Math.max(1, page);
    }

    private MessageUtil messages() {
        return guiManager.plugin().messages();
    }

    @Override
    public Inventory render(Player player) {
        Inventory inventory = attach(Bukkit.createInventory(this, 54,
                messages().component("gui.titles.your-items", "&6ᴀᴄᴛɪᴏɴ • ʏᴏᴜʀ ɪᴛᴇᴍꜱ")));
        slotMappings.clear();

        List<AuctionListing> listings = auctionService.getPlayerAuctions(player.getUniqueId());
        int totalPages = Math.max(1, (int) Math.ceil(listings.size() / 45.0D));
        page = Math.min(page, totalPages);
        guiManager.setPlayerItemsPage(player.getUniqueId(), page);
        int from = (page - 1) * 45;
        int to = Math.min(listings.size(), from + 45);
        long now = System.currentTimeMillis();

        for (int slot = 0; slot < to - from; slot++) {
            AuctionListing listing = listings.get(from + slot);
            inventory.setItem(slot, buildItem(listing, now));
            slotMappings.put(slot, listing.auctionId());
        }

        String prevLore = page > 1
                ? messages().raw("gui.common.go-back", "Go back")
                : messages().raw("gui.common.no-previous-page", "No previous page");
        String nextLore = page < totalPages
                ? messages().raw("gui.common.open-next-page", "Open next page")
                : messages().raw("gui.common.no-more-listings", "No more listings");

        inventory.setItem(45, ItemBuilder.of(Material.ARROW)
                .name(messages().component("gui.common.previous-page", "Previous Page"))
                .lore(messages().component(prevLore))
                .build());
        inventory.setItem(49, ItemBuilder.of(Material.CHEST)
                .name(messages().component("gui.common.back-to-auction", "Back to Auction"))
                .lore(messages().component("gui.common.back-to-auction-lore", "Return to the auction browser"))
                .build());
        inventory.setItem(53, ItemBuilder.of(Material.ARROW)
                .name(messages().component("gui.common.next-page", "Next Page"))
                .lore(messages().component(nextLore))
                .build());
        return inventory;
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        if (slotMappings.containsKey(slot)) {
            UUID auctionId = slotMappings.get(slot);
            auctionService.findListing(auctionId).ifPresent(listing -> {
                if (listing.status() == AuctionStatus.ACTIVE) {
                    auctionService.cancelAuction(player, auctionId).thenAccept(result -> sendAndRefresh(player, result));
                } else {
                    auctionService.collectSellerProceeds(player, auctionId).thenAccept(result -> sendAndRefresh(player, result));
                }
            });
            return;
        }

        if (slot == 45 && page > 1) {
            page--;
            guiManager.setPlayerItemsPage(player.getUniqueId(), page);
            guiManager.openPlayerItems(player);
            return;
        }
        if (slot == 49) {
            guiManager.openAuctionHouse(player);
            return;
        }
        if (slot == 53) {
            page++;
            guiManager.setPlayerItemsPage(player.getUniqueId(), page);
            guiManager.openPlayerItems(player);
        }
    }

    private ItemStack buildItem(AuctionListing listing, long now) {
        ItemStack display = listing.item().clone();

        ItemLoreApplier.LoreMode loreMode = auctionService.getLoreMode();
        boolean showSeparator = auctionService.getLoreSeparator();

        String statusLine = switch (listing.status()) {
            case ACTIVE -> messages().raw("status.line.active", "&fStatus: Active");
            case SOLD -> messages().raw("status.line.sold", "&aStatus: Sold");
            case EXPIRED -> messages().raw("status.line.expired", "&cStatus: Expired");
            case CANCELLED -> messages().raw("status.line.cancelled", "&cStatus: Cancelled");
        };

        List<Component> auctionLore = new ArrayList<>();
        auctionLore.add(messages().component(
                "gui.listing-lore.price",
                "&7Price: %price%",
                "price", auctionService.formatPrice(listing.price())));
        auctionLore.add(messages().component(
                "gui.listing-lore.time-remaining",
                "&7Time remaining: %time%",
                "time", TimeUtil.formatDuration(listing.expirationTime() - now)));
        auctionLore.add(messages().component(statusLine));
        auctionLore.add(messages().component(actionLine(listing)));

        ItemLoreApplier.applyLore(display, loreMode, showSeparator, auctionLore, messages());
        return display;
    }

    private String actionLine(AuctionListing listing) {
        if (listing.sellerClaimed()) {
            return messages().raw("gui.listing-lore.action-already-collected", "Already collected.");
        }
        return switch (listing.status()) {
            case ACTIVE -> messages().raw("gui.listing-lore.action-cancel", "Click to cancel auction.");
            case SOLD -> messages().raw("gui.listing-lore.action-collect-proceeds", "Click to mark proceeds collected.");
            case EXPIRED, CANCELLED -> messages().raw("gui.listing-lore.action-reclaim", "Click to reclaim item.");
        };
    }

    private void sendAndRefresh(Player player, ActionResult result) {
        guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
            guiManager.plugin().messages().send(player, result.message());
            guiManager.openPlayerItems(player);
        });
    }
}
