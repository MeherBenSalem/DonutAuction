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
import io.nightbeam.donutauction.util.ShulkerBoxSupport;
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
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

public final class AuctionGui extends BaseGui {

    private static final Component TITLE = Component.text("ᴀᴜᴄᴛɪᴏɴ", NamedTextColor.GOLD);

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

    @Override
    public Inventory render(Player player) {
        Inventory inventory = attach(Bukkit.createInventory(this, 54, TITLE));
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
                .name(Component.text("Price Sort", NamedTextColor.WHITE))
                .lore(
                        Component.text("Current: " + request.sortMode().displayName(), NamedTextColor.GRAY),
                        Component.text("Click to cycle sorting", NamedTextColor.DARK_GRAY)
                )
                .build());

        inventory.setItem(48, ItemBuilder.of(Material.HOPPER)
                .name(Component.text("Filter", NamedTextColor.WHITE))
                .lore(
                        Component.text("Current: " + request.filterCategory().displayName(), NamedTextColor.GRAY),
                        Component.text("Click to change category", NamedTextColor.DARK_GRAY)
                )
                .build());

        inventory.setItem(49, ItemBuilder.of(Material.ANVIL)
                .name(Component.text("Auction", NamedTextColor.WHITE))
                .lore(Component.text("Refresh the auction house", NamedTextColor.GRAY))
                .build());

        inventory.setItem(50, ItemBuilder.of(Material.OAK_SIGN)
                .name(Component.text("Search", NamedTextColor.WHITE))
                .lore(
                        Component.text(request.searchTerm().isBlank() ? "Current: none" : "Current: " + request.searchTerm(), NamedTextColor.GRAY),
                        Component.text("Type an item name in chat", NamedTextColor.DARK_GRAY)
                )
                .build());

        inventory.setItem(51, ItemBuilder.of(Material.CHEST)
                .name(Component.text("Your Items", NamedTextColor.WHITE))
                .lore(Component.text("View active, sold, and expired listings", NamedTextColor.GRAY))
                .build());

        inventory.setItem(45, ItemBuilder.of(Material.EMERALD)
                .name(Component.text("Sell Held Item", NamedTextColor.GREEN))
                .lore(
                        Component.text("List the item in your main hand", NamedTextColor.GRAY),
                        Component.text("Uses your last sell price when available", NamedTextColor.DARK_GRAY)
                )
                .build());

        inventory.setItem(53, ItemBuilder.of(Material.ARROW)
                .name(Component.text("Next Page", NamedTextColor.WHITE))
                .lore(Component.text(page.hasNextPage() ? "Open the next page" : "No more listings", NamedTextColor.GRAY))
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
            sellerName = seller.getName() == null ? "Unknown" : seller.getName();
        }

        ItemLoreApplier.LoreMode loreMode = auctionService.getLoreMode();
        boolean showSeparator = auctionService.getLoreSeparator();

        List<Component> auctionLore = new ArrayList<>();
        auctionLore.add(Component.text("Price: " + auctionService.formatPrice(listing.price()), NamedTextColor.GRAY));
        auctionLore.add(Component.text("Seller: " + sellerName, NamedTextColor.GRAY));
        auctionLore.add(Component.text("Expires in: " + TimeUtil.formatDuration(listing.expirationTime() - now), NamedTextColor.GRAY));

        if (ShulkerBoxSupport.isShulkerBox(display)) {
            int itemCount = ShulkerBoxSupport.getItemCount(display);
            auctionLore.add(Component.text("Contents: " + itemCount + " items", NamedTextColor.AQUA));
            auctionLore.add(Component.text("Right-click to preview", NamedTextColor.DARK_GRAY));
        }

        auctionLore.add(Component.text("Click to purchase.", NamedTextColor.GREEN));

        ItemLoreApplier.applyLore(display, loreMode, showSeparator, auctionLore);
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
