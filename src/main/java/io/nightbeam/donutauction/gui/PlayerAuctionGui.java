package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionStatus;
import io.nightbeam.donutauction.service.ActionResult;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.util.ItemBuilder;
import io.nightbeam.donutauction.util.ItemLoreApplier;
import io.nightbeam.donutauction.util.TimeUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class PlayerAuctionGui extends BaseGui {

    private static final Component TITLE = Component.text("ᴀᴄᴛɪᴏɴ • ʏᴏᴜʀ ɪᴛᴇᴍꜱ", NamedTextColor.GOLD);

    private final GuiManager guiManager;
    private final AuctionService auctionService;
    private final Map<Integer, UUID> slotMappings = new HashMap<>();
    private int page;

    public PlayerAuctionGui(GuiManager guiManager, AuctionService auctionService, int page) {
        this.guiManager = guiManager;
        this.auctionService = auctionService;
        this.page = Math.max(1, page);
    }

    @Override
    public Inventory render(Player player) {
        Inventory inventory = attach(Bukkit.createInventory(this, 54, TITLE));
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

        inventory.setItem(45, ItemBuilder.of(Material.ARROW)
                .name(Component.text("Previous Page", NamedTextColor.WHITE))
                .lore(Component.text(page > 1 ? "Go back" : "No previous page", NamedTextColor.GRAY))
                .build());
        inventory.setItem(49, ItemBuilder.of(Material.CHEST)
                .name(Component.text("Back to Auction", NamedTextColor.WHITE))
                .lore(Component.text("Return to the auction browser", NamedTextColor.GRAY))
                .build());
        inventory.setItem(53, ItemBuilder.of(Material.ARROW)
                .name(Component.text("Next Page", NamedTextColor.WHITE))
                .lore(Component.text(page < totalPages ? "Open next page" : "No more listings", NamedTextColor.GRAY))
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

        List<Component> auctionLore = new ArrayList<>();
        auctionLore.add(Component.text("Price: " + auctionService.formatPrice(listing.price()), NamedTextColor.GRAY));
        auctionLore.add(Component.text("Time remaining: " + TimeUtil.formatDuration(listing.expirationTime() - now), NamedTextColor.GRAY));
        auctionLore.add(Component.text("Status: " + humanStatus(listing.status()), colorForStatus(listing.status())));
        auctionLore.add(Component.text(actionLine(listing), NamedTextColor.GREEN));

        ItemLoreApplier.applyLore(display, loreMode, showSeparator, auctionLore);
        return display;
    }

    private NamedTextColor colorForStatus(AuctionStatus status) {
        return switch (status) {
            case ACTIVE -> NamedTextColor.WHITE;
            case SOLD -> NamedTextColor.GREEN;
            case EXPIRED, CANCELLED -> NamedTextColor.RED;
        };
    }

    private String humanStatus(AuctionStatus status) {
        return switch (status) {
            case ACTIVE -> "Active";
            case SOLD -> "Sold";
            case EXPIRED -> "Expired";
            case CANCELLED -> "Cancelled";
        };
    }

    private String actionLine(AuctionListing listing) {
        return switch (listing.status()) {
            case ACTIVE -> "Click to cancel auction.";
            case SOLD -> listing.sellerClaimed() ? "Already collected." : "Click to mark proceeds collected.";
            case EXPIRED, CANCELLED -> listing.sellerClaimed() ? "Already collected." : "Click to reclaim item.";
        };
    }

    private void sendAndRefresh(Player player, ActionResult result) {
        guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
            guiManager.plugin().messages().send(player, result.message());
            guiManager.openPlayerItems(player);
        });
    }
}
