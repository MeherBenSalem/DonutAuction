package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.hook.DonutCoreHook;
import io.nightbeam.donutauction.model.AuctionBrowseRequest;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionPage;
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
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

public final class AuctionGui extends BaseGui {

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
        Inventory inventory = attach(Bukkit.createInventory(this, 54,
                messages().component("gui.titles.auction", "&6ᴀᴜᴄᴛɪᴏɴ")));
        slotMappings.clear();

        AuctionBrowseRequest request = session.request();
        AuctionPage page = auctionService.browse(request);
        List<AuctionListing> listings = page.listings();
        long now = System.currentTimeMillis();

        for (int slot = 0; slot < Math.min(45, listings.size()); slot++) {
            AuctionListing listing = listings.get(slot);
            inventory.setItem(slot, buildListingItem(listing, now));
            slotMappings.put(slot, listing.auctionId());
        }

        inventory.setItem(47, ItemBuilder.of(Material.CAULDRON)
                .name(messages().component("gui.auction.price-sort", "Price Sort"))
                .lore(
                        messages().component(
                                "gui.common.current-value",
                                "Current: %value%",
                                "value", messages().sortMode(request.sortMode())),
                        messages().component("gui.auction.click-cycle-sort", "Click to cycle sorting")
                )
                .build());

        inventory.setItem(48, ItemBuilder.of(Material.HOPPER)
                .name(messages().component("gui.auction.filter", "Filter"))
                .lore(
                        messages().component(
                                "gui.common.current-value",
                                "Current: %value%",
                                "value", messages().filterCategory(request.filterCategory())),
                        messages().component("gui.auction.click-change-category", "Click to change category")
                )
                .build());

        inventory.setItem(49, ItemBuilder.of(Material.ANVIL)
                .name(messages().component("gui.auction.refresh-name", "Auction"))
                .lore(messages().component("gui.auction.refresh-lore", "Refresh the auction house"))
                .build());

        String searchCurrent = request.searchTerm().isBlank()
                ? messages().raw("gui.common.current-none", "Current: none")
                : messages().format("gui.common.current-value", "Current: %value%", "value", request.searchTerm());

        inventory.setItem(50, ItemBuilder.of(Material.OAK_SIGN)
                .name(messages().component("gui.auction.search", "Search"))
                .lore(
                        messages().component(searchCurrent),
                        messages().component("gui.auction.type-item-name", "Type an item name in chat")
                )
                .build());

        inventory.setItem(51, ItemBuilder.of(Material.CHEST)
                .name(messages().component("gui.auction.your-items", "Your Items"))
                .lore(messages().component("gui.auction.your-items-lore", "View active, sold, and expired listings"))
                .build());

        inventory.setItem(45, ItemBuilder.of(Material.EMERALD)
                .name(messages().component("gui.auction.sell-held-item", "Sell Held Item"))
                .lore(
                        messages().component("gui.auction.sell-held-lore", "List the item in your main hand"),
                        messages().component("gui.auction.sell-held-hint", "Uses your last sell price when available")
                )
                .build());

        String nextPageLore = page.hasNextPage()
                ? messages().raw("gui.auction.next-page-lore-available", "Open the next page")
                : messages().raw("gui.common.no-more-listings", "No more listings");

        inventory.setItem(53, ItemBuilder.of(Material.ARROW)
                .name(messages().component("gui.common.next-page", "Next Page"))
                .lore(messages().component(nextPageLore))
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
            AuctionListing listing = auctionService.findListing(auctionId).orElse(null);
            if (listing == null) {
                return;
            }

            if (event.isRightClick() && ShulkerBoxSupport.isShulkerBox(listing.item())) {
                guiManager.openShulkerPreview(player, listing.item());
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
                guiManager.openConfirmPurchase(player, listing);
            }
            return;
        }

        AuctionBrowseRequest request = session.request();
        if (slot == 47) {
            session.request(request.withSortMode(request.sortMode().next()));
            guiManager.refreshAuctionHouse(player);
            return;
        }

        if (slot == 48) {
            guiManager.openFilterMenu(player);
            return;
        }

        if (slot == 49) {
            guiManager.refreshAuctionHouse(player);
            return;
        }

        if (slot == 50) {
            guiManager.beginSearch(player);
            return;
        }

        if (slot == 51) {
            guiManager.openPlayerItems(player);
            return;
        }

        if (slot == 45) {
            guiManager.startSellFromHeldItem(player);
            return;
        }

        if (slot == 53 && auctionService.browse(request).hasNextPage()) {
            session.request(request.withPage(request.page() + 1));
            guiManager.refreshAuctionHouse(player);
        }
    }

    private ItemStack buildListingItem(AuctionListing listing, long now) {
        ItemStack display = listing.item().clone();
        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.seller());
        String sellerName;
        if (seller.isOnline() && seller.getPlayer() != null) {
            sellerName = donutCoreHook.resolveDisplayName(seller.getPlayer());
        } else {
            sellerName = seller.getName() == null ? messages().unknownSeller() : seller.getName();
        }

        ItemLoreApplier.LoreMode loreMode = auctionService.getLoreMode();
        boolean showSeparator = auctionService.getLoreSeparator();

        List<Component> auctionLore = new ArrayList<>();
        auctionLore.add(messages().component(
                "gui.listing-lore.price",
                "&7Price: %price%",
                "price", auctionService.formatPrice(listing.price())));
        auctionLore.add(messages().component(
                "gui.listing-lore.seller",
                "&7Seller: %seller%",
                "seller", sellerName));
        auctionLore.add(messages().component(
                "gui.listing-lore.expires-in",
                "&7Expires in: %time%",
                "time", TimeUtil.formatDuration(listing.expirationTime() - now)));

        if (ShulkerBoxSupport.isShulkerBox(display)) {
            int itemCount = ShulkerBoxSupport.getItemCount(display);
            auctionLore.add(messages().component(
                    "gui.listing-lore.contents",
                    "&bContents: %count% items",
                    "count", String.valueOf(itemCount)));
            auctionLore.add(messages().component("gui.listing-lore.preview-hint", "&8Right-click to preview"));
        }

        auctionLore.add(messages().component("gui.listing-lore.click-purchase", "&aClick to purchase."));

        ItemLoreApplier.applyLore(display, loreMode, showSeparator, auctionLore, messages());
        display.editMeta(meta -> meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES));
        return display;
    }

    private void sendAndRefresh(Player player, ActionResult result) {
        guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
            guiManager.plugin().messages().send(player, result.message());
            guiManager.refreshAuctionHouse(player);
        });
    }
}
