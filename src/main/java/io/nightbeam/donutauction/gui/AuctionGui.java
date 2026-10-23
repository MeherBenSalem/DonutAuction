package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.hook.DonutCoreHook;
import io.nightbeam.donutauction.model.AuctionBrowseRequest;
import io.nightbeam.donutauction.model.AuctionFilterCategory;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionPage;
import io.nightbeam.donutauction.model.ListingFilter;
import io.nightbeam.donutauction.model.PlayerAuctionSession;
import io.nightbeam.donutauction.service.ActionResult;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.service.PlayerPreferenceManager;
import io.nightbeam.donutauction.util.ItemBuilder;
import io.nightbeam.donutauction.util.ItemLoreApplier;
import io.nightbeam.donutauction.util.MessageUtil;
import io.nightbeam.donutauction.util.ShulkerBoxSupport;
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
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

public final class AuctionGui extends BaseGui {

    static final int SELL_HELD_SLOT = 45;
    static final int PREVIOUS_PAGE_SLOT = 46;
    static final int SORT_SLOT = 47;
    static final int FILTER_SLOT = 48;
    static final int REFRESH_SLOT = 49;
    static final int SEARCH_SLOT = 50;
    static final int YOUR_ITEMS_SLOT = 51;
    static final int HISTORY_SLOT = 52;
    static final int NEXT_PAGE_SLOT = 53;

    private final GuiManager guiManager;
    private final AuctionService auctionService;
    private final PlayerPreferenceManager preferenceManager;
    private final DonutCoreHook donutCoreHook;
    private final PlayerAuctionSession session;
    private final Map<Integer, UUID> slotMappings = new HashMap<>();

    public AuctionGui(GuiManager guiManager, AuctionService auctionService, PlayerPreferenceManager preferenceManager,
                      DonutCoreHook donutCoreHook, PlayerAuctionSession session) {
        this.guiManager = guiManager;
        this.auctionService = auctionService;
        this.preferenceManager = preferenceManager;
        this.donutCoreHook = donutCoreHook;
        this.session = session;
    }

    private MessageUtil messages() {
        return guiManager.plugin().messages();
    }

    @Override
    public Inventory render(Player player) {
        AuctionBrowseRequest request = session.request();
        Component title = request.seller() != null
                ? messages().name("gui.titles.seller", "&6ᴀᴜᴄᴛɪᴏɴ • %seller%", "seller",
                request.sellerName() == null ? "?" : request.sellerName())
                : messages().name("gui.titles.auction", "&6ᴀᴜᴄᴛɪᴏɴ");
        Inventory inventory = attach(Bukkit.createInventory(this, 54, title));
        slotMappings.clear();

        AuctionPage page = auctionService.browse(request);
        List<AuctionListing> listings = page.listings();
        long now = System.currentTimeMillis();
        boolean admin = player.hasPermission("donutauction.admin");

        for (int slot = 0; slot < Math.min(45, listings.size()); slot++) {
            AuctionListing listing = listings.get(slot);
            inventory.setItem(slot, buildListingItem(listing, now, player, admin));
            slotMappings.put(slot, listing.auctionId());
        }
        if (listings.isEmpty()) {
            inventory.setItem(22, ItemBuilder.of(Material.BARRIER)
                    .name(messages().component(request.filtered() ? "gui.auction.no-results" : "gui.auction.empty",
                            request.filtered() ? "&cNothing matches" : "&7Nothing for sale yet"))
                    .lore(messages().component(request.filtered() ? "gui.auction.no-results-lore" : "gui.auction.empty-lore",
                            request.filtered() ? "Clear the search or filter to see everything" : "Hold an item and use /ah sell <price>"))
                    .build());
        }

        inventory.setItem(SORT_SLOT, ItemBuilder.of(Material.CAULDRON)
                .name(messages().component("gui.auction.price-sort", "&ePrice Sort"))
                .lore(sortLore(request))
                .build());

        inventory.setItem(FILTER_SLOT, ItemBuilder.of(Material.HOPPER)
                .name(messages().component("gui.auction.filter", "&eFilter"))
                .lore(
                        messages().component("gui.common.current-value", "Current: %value%",
                                "value", messages().filterCategory(request.filterCategory())),
                        messages().component("gui.auction.type-value", "Showing: %value%",
                                "value", listingFilterLabel(request.listingFilter())),
                        messages().component("gui.auction.click-change-category", "&eClick to change category")
                )
                .glow(request.filterCategory() != AuctionFilterCategory.ALL || request.listingFilter() != ListingFilter.ALL)
                .build());

        inventory.setItem(REFRESH_SLOT, ItemBuilder.of(Material.ANVIL)
                .name(messages().component("gui.auction.refresh-name", "&6Auction House"))
                .lore(
                        messages().component("gui.common.results-count", "%count% listings", "count", String.valueOf(page.totalResults())),
                        messages().component("gui.auction.refresh-lore", "&eClick to refresh")
                )
                .build());

        List<Component> searchLore = new ArrayList<>();
        searchLore.add(request.searchTerm().isBlank()
                ? messages().component("gui.common.current-none", "Current: none")
                : messages().component("gui.common.current-value", "Current: %value%", "value", request.searchTerm()));
        if (request.seller() != null) {
            searchLore.add(messages().component("gui.auction.seller-filter", "Seller: %seller%",
                    "seller", request.sellerName() == null ? "?" : request.sellerName()));
        }
        searchLore.add(messages().component("gui.auction.type-item-name", "&eClick to search by name, enchant or seller"));
        if (request.filtered()) searchLore.add(messages().component("gui.auction.search-clear", "&7Right-click to clear"));
        inventory.setItem(SEARCH_SLOT, ItemBuilder.of(Material.OAK_SIGN)
                .name(messages().component("gui.auction.search", "&eSearch"))
                .lore(searchLore)
                .glow(!request.searchTerm().isBlank() || request.seller() != null)
                .build());

        int waiting = (int) auctionService.getPlayerAuctions(player.getUniqueId()).stream().filter(AuctionListing::isReclaimable).count()
                + auctionService.getCollection(player.getUniqueId()).size();
        List<Component> itemsLore = new ArrayList<>();
        itemsLore.add(messages().component("gui.auction.your-items-lore", "View your listings and collect items"));
        itemsLore.add(messages().component("gui.auction.your-items-count", "Active: %active%/%limit%",
                "active", String.valueOf(auctionService.getPlayerActiveAuctionCount(player.getUniqueId())),
                "limit", limitLabel(player)));
        if (waiting > 0) {
            itemsLore.add(messages().component("gui.auction.your-items-waiting", "&a%count% item(s) to collect", "count", String.valueOf(waiting)));
        }
        inventory.setItem(YOUR_ITEMS_SLOT, ItemBuilder.of(Material.CHEST)
                .name(messages().component("gui.auction.your-items", "&eYour Items"))
                .lore(itemsLore)
                .glow(waiting > 0)
                .build());

        inventory.setItem(HISTORY_SLOT, ItemBuilder.of(Material.BOOK)
                .name(messages().component("gui.history.entry", "&eTransaction History"))
                .lore(messages().component("gui.history.entry-lore", "View your completed purchases and sales"))
                .build());

        inventory.setItem(SELL_HELD_SLOT, ItemBuilder.of(Material.EMERALD)
                .name(messages().component("gui.auction.sell-held-item", "&aSell Held Item"))
                .lore(
                        messages().component("gui.auction.sell-held-lore", "List the item in your main hand"),
                        messages().component("gui.auction.sell-held-hint", "Uses your last sell price when available")
                )
                .build());

        Component pageIndicator = messages().component(
                "gui.common.page-indicator",
                "Page: %page%/%pages%",
                "page", String.valueOf(page.currentPage()),
                "pages", String.valueOf(page.totalPages()));
        Component resultsCount = messages().component(
                "gui.common.results-count",
                "%count% listings",
                "count", String.valueOf(page.totalResults()));

        String prevPageLore = page.hasPreviousPage()
                ? messages().raw("gui.common.go-back", "Go back")
                : messages().raw("gui.common.no-previous-page", "No previous page");
        String nextPageLore = page.hasNextPage()
                ? messages().raw("gui.auction.next-page-lore-available", "Open the next page")
                : messages().raw("gui.common.no-more-listings", "No more listings");

        inventory.setItem(PREVIOUS_PAGE_SLOT, ItemBuilder.of(Material.ARROW)
                .name(messages().component("gui.common.previous-page", "&ePrevious Page"))
                .lore(pageIndicator, resultsCount, messages().component(prevPageLore))
                .build());
        inventory.setItem(NEXT_PAGE_SLOT, ItemBuilder.of(Material.ARROW)
                .name(messages().component("gui.common.next-page", "&eNext Page"))
                .lore(pageIndicator, resultsCount, messages().component(nextPageLore))
                .build());

        return inventory;
    }

    private String limitLabel(Player player) {
        int limit = guiManager.limitService().getEffectiveLimit(player);
        return limit < 0 ? messages().raw("command.unlimited", "Unlimited") : String.valueOf(limit);
    }

    private List<Component> sortLore(AuctionBrowseRequest request) {
        List<Component> lore = new ArrayList<>();
        for (var mode : io.nightbeam.donutauction.model.AuctionSortMode.values()) {
            boolean selected = mode == request.sortMode();
            lore.add(messages().component(selected ? "gui.auction.sort-selected" : "gui.auction.sort-option",
                    selected ? "&a▶ %mode%" : "&7  %mode%", "mode", messages().sortMode(mode)));
        }
        lore.add(messages().component("gui.auction.click-cycle-sort", "&eClick to cycle sorting"));
        return lore;
    }

    String listingFilterLabel(ListingFilter filter) {
        return switch (filter) {
            case ALL -> messages().raw("filter.types.all", "Everything");
            case BUY_NOW -> messages().raw("filter.types.buy-now", "Buy It Now");
            case AUCTIONS -> messages().raw("filter.types.auctions", "Auctions");
        };
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        if (slot == HISTORY_SLOT) {
            guiManager.click(player);
            guiManager.openHistory(player, io.nightbeam.donutauction.model.HistoryView.BOUGHT, 1);
            return;
        }
        if (slotMappings.containsKey(slot)) {
            UUID auctionId = slotMappings.get(slot);
            AuctionListing listing = auctionService.findListing(auctionId).orElse(null);
            if (listing == null) {
                guiManager.refreshAuctionHouse(player);
                return;
            }

            if (event.isShiftClick() && event.isRightClick() && player.hasPermission("donutauction.admin")) {
                auctionService.adminRemove(player, auctionId).thenAccept(result -> sendAndRefresh(player, result));
                return;
            }
            if (event.isRightClick() && ShulkerBoxSupport.isShulkerBox(listing.item())
                    && guiManager.plugin().getConfig().getBoolean("shulker-support.preview-contents", true)) {
                guiManager.click(player);
                guiManager.openShulkerPreview(player, listing.item());
                return;
            }
            if (listing.seller().equals(player.getUniqueId())) {
                guiManager.click(player);
                guiManager.openPlayerItems(player);
                return;
            }
            if (listing.isBid()) {
                guiManager.click(player);
                guiManager.openBid(player, listing);
                return;
            }

            boolean fastBuyEnabled = false;
            var pref = preferenceManager.getCached(player.getUniqueId());
            if (pref != null && pref.fastBuyEnabled() && player.hasPermission("donutauction.fastbuy")) {
                fastBuyEnabled = true;
            }

            if (fastBuyEnabled) {
                auctionService.purchaseAuction(player, auctionId).thenAccept(result -> sendAndRefresh(player, result));
            } else {
                guiManager.click(player);
                guiManager.openConfirmPurchase(player, listing);
            }
            return;
        }

        AuctionBrowseRequest request = session.request();
        if (slot == SORT_SLOT) {
            guiManager.click(player);
            session.request(request.withSortMode(request.sortMode().next()));
            guiManager.refreshAuctionHouse(player);
            return;
        }

        if (slot == FILTER_SLOT) {
            guiManager.click(player);
            guiManager.openFilterMenu(player);
            return;
        }

        if (slot == REFRESH_SLOT) {
            guiManager.click(player);
            guiManager.refreshAuctionHouse(player);
            return;
        }

        if (slot == SEARCH_SLOT) {
            guiManager.click(player);
            if (event.isRightClick()) {
                session.request(request.withSearch("").withSeller(null, null).withFilter(AuctionFilterCategory.ALL)
                        .withListingFilter(ListingFilter.ALL));
                guiManager.refreshAuctionHouse(player);
                return;
            }
            guiManager.beginSearch(player);
            return;
        }

        if (slot == YOUR_ITEMS_SLOT) {
            guiManager.click(player);
            guiManager.openPlayerItems(player);
            return;
        }

        if (slot == SELL_HELD_SLOT) {
            guiManager.startSellFromHeldItem(player);
            return;
        }

        AuctionBrowseRequest nextRequest = pageAfterClick(request, auctionService.browse(request), slot);
        if (nextRequest != request) {
            guiManager.click(player);
            session.request(nextRequest);
            guiManager.refreshAuctionHouse(player);
        }
    }

    static AuctionBrowseRequest pageAfterClick(AuctionBrowseRequest request, AuctionPage page, int slot) {
        if (slot == PREVIOUS_PAGE_SLOT && page.hasPreviousPage()) {
            return request.withPage(request.page() - 1);
        }
        if (slot == NEXT_PAGE_SLOT && page.hasNextPage()) {
            return request.withPage(request.page() + 1);
        }
        return request;
    }

    private ItemStack buildListingItem(AuctionListing listing, long now, Player viewer, boolean admin) {
        ItemStack display = listing.item().clone();
        String sellerName = guiManager.sellerName(listing);
        ItemLoreApplier.LoreMode loreMode = auctionService.getLoreMode();
        boolean showSeparator = auctionService.getLoreSeparator();
        int amount = Math.max(1, display.getAmount());

        List<Component> auctionLore = new ArrayList<>();
        if (listing.isBid()) {
            if (listing.bid().present()) {
                auctionLore.add(messages().component("gui.listing-lore.current-bid", "&7Current bid: &6%price% &8(%bids% bids)",
                        "price", auctionService.formatDisplayPrice(listing.bid().amount()), "bids", String.valueOf(listing.bid().count())));
                auctionLore.add(messages().component("gui.listing-lore.top-bidder", "&7Top bidder: &f%bidder%",
                        "bidder", listing.bid().bidderName() == null ? "?" : listing.bid().bidderName()));
            } else {
                auctionLore.add(messages().component("gui.listing-lore.starting-bid", "&7Starting bid: &6%price%",
                        "price", auctionService.formatDisplayPrice(listing.price())));
            }
            auctionLore.add(messages().component("gui.listing-lore.min-bid", "&7Next bid: &e%price%",
                    "price", auctionService.formatDisplayPrice(auctionService.minimumBid(listing))));
        } else {
            auctionLore.add(messages().component(
                    "gui.listing-lore.price",
                    "&7Price: &6%price%",
                    "price", auctionService.formatDisplayPrice(listing.price())));
            if (amount > 1) {
                auctionLore.add(messages().component("gui.listing-lore.price-each", "&8(%price% each)",
                        "price", auctionService.formatDisplayPrice(listing.unitPrice())));
            }
        }
        auctionLore.add(messages().component(
                "gui.listing-lore.seller",
                "&7Seller: &f%seller%",
                "seller", sellerName));
        auctionLore.add(messages().component(
                listing.isBid() ? "gui.listing-lore.ends-in" : "gui.listing-lore.expires-in",
                listing.isBid() ? "&7Ends in: &f%time%" : "&7Expires in: &f%time%",
                "time", TimeUtil.formatDuration(listing.expirationTime() - now)));

        if (ShulkerBoxSupport.isShulkerBox(display) && guiManager.plugin().getConfig().getBoolean("shulker-support.enabled", true)) {
            int itemCount = ShulkerBoxSupport.getItemCount(display);
            auctionLore.add(messages().component(
                    "gui.listing-lore.contents",
                    "&bContents: %count% items",
                    "count", String.valueOf(itemCount)));
            auctionLore.add(messages().component("gui.listing-lore.preview-hint", "&8Right-click to preview"));
        }

        if (listing.seller().equals(viewer.getUniqueId())) {
            auctionLore.add(messages().component("gui.listing-lore.own-listing", "&eThis is your listing"));
        } else if (listing.isBid()) {
            auctionLore.add(messages().component(viewer.getUniqueId().equals(listing.bid().bidder())
                    ? "gui.listing-lore.leading" : "gui.listing-lore.click-bid",
                    viewer.getUniqueId().equals(listing.bid().bidder()) ? "&aYou have the highest bid" : "&aClick to bid"));
        } else {
            auctionLore.add(messages().component("gui.listing-lore.click-purchase", "&aClick to purchase."));
        }
        if (admin) {
            auctionLore.add(messages().component("gui.listing-lore.admin-remove", "&cShift-right-click to remove (staff)"));
        }

        ItemLoreApplier.applyLore(display, loreMode, showSeparator, auctionLore, messages());
        try {
            display.editMeta(meta -> meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES));
        } catch (RuntimeException ignored) {
            // Item flags are cosmetic.
        }
        return display;
    }

    private void sendAndRefresh(Player player, ActionResult result) {
        guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
            guiManager.plugin().messages().send(player, result.message());
            guiManager.feedback(player, result);
            guiManager.refreshAuctionHouse(player);
        });
    }
}
