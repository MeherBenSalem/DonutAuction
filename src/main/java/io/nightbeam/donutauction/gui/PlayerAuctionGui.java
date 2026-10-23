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

/**
 * "Your Items": items waiting to be collected (bought, won, expired, cancelled) first, then active
 * listings. Opened for another player by staff, clicking an active listing removes it.
 */
public final class PlayerAuctionGui extends BaseGui {

    static final int PREVIOUS_SLOT = 45;
    static final int COLLECT_ALL_SLOT = 47;
    static final int BACK_SLOT = 49;
    static final int SUMMARY_SLOT = 51;
    static final int NEXT_SLOT = 53;

    private final GuiManager guiManager;
    private final AuctionService auctionService;
    private final UUID owner;
    private final String adminTargetName;
    private final Map<Integer, UUID> slotMappings = new HashMap<>();
    private int page;

    public PlayerAuctionGui(GuiManager guiManager, AuctionService auctionService, int page) {
        this(guiManager, auctionService, null, null, page);
    }

    /**
     * @param adminTargetName null for the player's own items; otherwise staff are viewing {@code owner}
     */
    public PlayerAuctionGui(GuiManager guiManager, AuctionService auctionService, UUID owner, String adminTargetName, int page) {
        this.guiManager = guiManager;
        this.auctionService = auctionService;
        this.owner = owner;
        this.adminTargetName = adminTargetName;
        this.page = Math.max(1, page);
    }

    private MessageUtil messages() {
        return guiManager.plugin().messages();
    }

    private boolean adminView() {
        return adminTargetName != null;
    }

    private List<AuctionListing> entries(UUID viewer) {
        UUID target = owner == null ? viewer : owner;
        List<AuctionListing> entries = new ArrayList<>();
        if (!adminView()) entries.addAll(auctionService.getCollection(target));
        List<AuctionListing> own = auctionService.getPlayerAuctions(target);
        own.stream().filter(AuctionListing::isReclaimable).forEach(entries::add);
        own.stream().filter(listing -> listing.status() == AuctionStatus.ACTIVE).forEach(entries::add);
        return entries;
    }

    @Override
    public Inventory render(Player player) {
        Component title = adminView()
                ? messages().component("gui.titles.admin-items", "&cꜱᴛᴀꜰꜰ • %player%", "player", adminTargetName)
                : messages().component("gui.titles.your-items", "&6ᴀᴜᴄᴛɪᴏɴ • ʏᴏᴜʀ ɪᴛᴇᴍꜱ");
        Inventory inventory = attach(Bukkit.createInventory(this, 54, title));
        slotMappings.clear();

        List<AuctionListing> listings = entries(player.getUniqueId());
        int totalPages = Math.max(1, (int) Math.ceil(listings.size() / 45.0D));
        page = Math.min(page, totalPages);
        if (!adminView()) guiManager.setPlayerItemsPage(player.getUniqueId(), page);
        int from = (page - 1) * 45;
        int to = Math.min(listings.size(), from + 45);
        long now = System.currentTimeMillis();

        for (int slot = 0; slot < to - from; slot++) {
            AuctionListing listing = listings.get(from + slot);
            inventory.setItem(slot, buildItem(listing, now, player));
            slotMappings.put(slot, listing.auctionId());
        }
        if (listings.isEmpty()) {
            inventory.setItem(22, ItemBuilder.of(Material.BARRIER)
                    .name(messages().component("gui.your-items.empty", "&7No listings yet"))
                    .lore(messages().component("gui.your-items.empty-lore", "Hold an item and use /ah sell <price>"))
                    .build());
        }

        String prevLore = page > 1
                ? messages().raw("gui.common.go-back", "Go back")
                : messages().raw("gui.common.no-previous-page", "No previous page");
        String nextLore = page < totalPages
                ? messages().raw("gui.common.open-next-page", "Open next page")
                : messages().raw("gui.common.no-more-listings", "No more listings");

        inventory.setItem(PREVIOUS_SLOT, ItemBuilder.of(Material.ARROW)
                .name(messages().component("gui.common.previous-page", "&ePrevious Page"))
                .lore(messages().component(prevLore))
                .build());
        inventory.setItem(BACK_SLOT, ItemBuilder.of(Material.CHEST)
                .name(messages().component("gui.common.back-to-auction", "&eBack to Auction"))
                .lore(messages().component("gui.common.back-to-auction-lore", "Return to the auction browser"))
                .build());
        inventory.setItem(NEXT_SLOT, ItemBuilder.of(Material.ARROW)
                .name(messages().component("gui.common.next-page", "&eNext Page"))
                .lore(messages().component(nextLore))
                .build());

        if (!adminView()) {
            long waiting = listings.stream().filter(listing -> listing.awaitsBuyer() || listing.isReclaimable()).count();
            inventory.setItem(COLLECT_ALL_SLOT, ItemBuilder.of(Material.HOPPER)
                    .name(messages().component("gui.your-items.collect-all", "&aCollect All"))
                    .lore(messages().component("gui.your-items.collect-all-lore", "Take every waiting item that fits (%count%)",
                            "count", String.valueOf(waiting)))
                    .glow(waiting > 0)
                    .build());
            int limit = guiManager.limitService().getEffectiveLimit(player);
            var stats = auctionService.market().cachedPlayerStats(player.getUniqueId());
            inventory.setItem(SUMMARY_SLOT, ItemBuilder.of(Material.WRITABLE_BOOK)
                    .name(messages().component("gui.your-items.summary", "&bYour Trading"))
                    .lore(
                            messages().component("gui.your-items.summary-active", "&7Active listings: &f%active%/%limit%",
                                    "active", String.valueOf(auctionService.getPlayerActiveAuctionCount(player.getUniqueId())),
                                    "limit", limit < 0 ? messages().raw("command.unlimited", "Unlimited") : String.valueOf(limit)),
                            messages().component("gui.your-items.summary-sold", "&7Items sold: &f%count% &8(%amount%)",
                                    "count", String.valueOf(stats.sold()), "amount", auctionService.formatPrice(stats.earned())),
                            messages().component("gui.your-items.summary-bought", "&7Items bought: &f%count% &8(%amount%)",
                                    "count", String.valueOf(stats.bought()), "amount", auctionService.formatPrice(stats.spent())))
                    .build());
        }
        fill(inventory, 45, 53);
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
            auctionService.findListing(auctionId).ifPresentOrElse(listing -> {
                if (adminView()) {
                    if (listing.status() == AuctionStatus.ACTIVE) {
                        auctionService.adminRemove(player, auctionId).thenAccept(result -> sendAndRefresh(player, result));
                    }
                    return;
                }
                if (listing.awaitsBuyer()) {
                    auctionService.claimDelivery(player, auctionId).thenAccept(result -> sendAndRefresh(player, result));
                } else if (listing.isReclaimable()) {
                    if (event.isRightClick()) {
                        int hours = guiManager.plugin().getConfig().getInt("auction.listing-duration-hours", 48);
                        var pref = guiManager.preferences().getCached(player.getUniqueId());
                        if (pref != null) hours = pref.lastDurationHours();
                        auctionService.relist(player, auctionId, hours, guiManager.limitService())
                                .thenAccept(result -> sendAndRefresh(player, result));
                    } else {
                        auctionService.reclaim(player, auctionId).thenAccept(result -> sendAndRefresh(player, result));
                    }
                } else if (listing.status() == AuctionStatus.ACTIVE) {
                    auctionService.cancelAuction(player, auctionId).thenAccept(result -> sendAndRefresh(player, result));
                }
            }, () -> reopen(player));
            return;
        }

        if (slot == PREVIOUS_SLOT && page > 1) {
            guiManager.click(player);
            page--;
            reopen(player);
            return;
        }
        if (slot == BACK_SLOT) {
            guiManager.click(player);
            guiManager.openAuctionHouse(player);
            return;
        }
        if (slot == NEXT_SLOT) {
            guiManager.click(player);
            page++;
            reopen(player);
            return;
        }
        if (slot == COLLECT_ALL_SLOT && !adminView()) {
            auctionService.collectAll(player).thenAccept(count -> guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
                long left = entries(player.getUniqueId()).stream().filter(listing -> listing.awaitsBuyer() || listing.isReclaimable()).count();
                if (count > 0) {
                    messages().sendFormatted(player, "gui.your-items.collected", "&aCollected %count% item(s).", "count", String.valueOf(count));
                }
                if (left > 0) {
                    messages().sendFormatted(player, "service.inventory-full-left", "&eYour inventory is full. %count% item(s) still wait here.",
                            "count", String.valueOf(left));
                }
                guiManager.feedback(player, count > 0 ? ActionResult.success("") : ActionResult.failure(""));
                reopen(player);
            }));
        }
    }

    private void reopen(Player player) {
        if (adminView()) {
            guiManager.openAdminListings(player, owner, adminTargetName);
        } else {
            guiManager.setPlayerItemsPage(player.getUniqueId(), page);
            guiManager.openPlayerItems(player);
        }
    }

    private ItemStack buildItem(AuctionListing listing, long now, Player viewer) {
        ItemStack display = listing.item().clone();
        List<Component> auctionLore = new ArrayList<>();

        if (listing.awaitsBuyer()) {
            auctionLore.add(messages().component(listing.isBid() ? "gui.listing-lore.won-for" : "gui.listing-lore.bought-for",
                    listing.isBid() ? "&aWon for &6%price%" : "&aBought for &6%price%",
                    "price", auctionService.formatDisplayPrice(listing.price())));
            auctionLore.add(messages().component("gui.listing-lore.action-collect", "&eClick to collect"));
        } else if (listing.isReclaimable()) {
            String statusLine = listing.status() == AuctionStatus.EXPIRED
                    ? messages().raw("status.line.expired", "&cStatus: Expired")
                    : messages().raw("status.line.cancelled", "&cStatus: Cancelled");
            auctionLore.add(messages().component(
                    "gui.listing-lore.price",
                    "&7Price: %price%",
                    "price", auctionService.formatDisplayPrice(listing.price())));
            auctionLore.add(messages().component(statusLine));
            auctionLore.add(messages().component("gui.listing-lore.action-reclaim", "&aClick to reclaim item."));
            auctionLore.add(messages().component("gui.listing-lore.action-relist", "&eRight-click to relist for %price%",
                    "price", auctionService.formatDisplayPrice(listing.price())));
        } else {
            if (listing.isBid()) {
                auctionLore.add(listing.bid().present()
                        ? messages().component("gui.listing-lore.current-bid", "&7Current bid: &6%price% &8(%bids% bids)",
                        "price", auctionService.formatDisplayPrice(listing.bid().amount()), "bids", String.valueOf(listing.bid().count()))
                        : messages().component("gui.listing-lore.starting-bid", "&7Starting bid: &6%price%",
                        "price", auctionService.formatDisplayPrice(listing.price())));
            } else {
                auctionLore.add(messages().component(
                        "gui.listing-lore.price",
                        "&7Price: %price%",
                        "price", auctionService.formatDisplayPrice(listing.price())));
            }
            auctionLore.add(messages().component(
                    "gui.listing-lore.time-remaining",
                    "&7Time remaining: %time%",
                    "time", TimeUtil.formatDuration(listing.expirationTime() - now)));
            auctionLore.add(messages().component(messages().raw("status.line.active", "&fStatus: Active")));
            if (adminView()) {
                auctionLore.add(messages().component("gui.listing-lore.admin-click-remove", "&cClick to remove (staff)"));
            } else if (listing.isBid() && listing.bid().present()) {
                auctionLore.add(messages().component("gui.listing-lore.has-bids", "&7Auctions with bids cannot be cancelled"));
            } else {
                auctionLore.add(messages().component("gui.listing-lore.action-cancel", "&aClick to cancel auction."));
            }
        }

        ItemLoreApplier.applyLore(display, auctionService.getLoreMode(), auctionService.getLoreSeparator(), auctionLore, messages());
        return display;
    }

    private void sendAndRefresh(Player player, ActionResult result) {
        guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
            if (result.message() != null && !result.message().isBlank()) guiManager.plugin().messages().send(player, result.message());
            guiManager.feedback(player, result);
            reopen(player);
        });
    }
}
