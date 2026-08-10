package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.AuctionHousePlugin;
import io.nightbeam.donutauction.hook.DonutCoreHook;
import io.nightbeam.donutauction.model.AuctionFilterCategory;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.PendingSaleTransaction;
import io.nightbeam.donutauction.model.PlayerAuctionSession;
import io.nightbeam.donutauction.model.PlayerPreference;
import io.nightbeam.donutauction.service.AuctionLimitService;
import io.nightbeam.donutauction.service.AuctionManager;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.service.PlayerPreferenceManager;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public final class GuiManager {

    private final AuctionHousePlugin plugin;
    private final AuctionService auctionService;
    private final AuctionManager auctionManager;
    private final PlayerPreferenceManager preferenceManager;
    private final AuctionLimitService limitService;
    private final DonutCoreHook donutCoreHook;
    private final Map<UUID, PlayerAuctionSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerItemsPages = new ConcurrentHashMap<>();
    private final Set<UUID> awaitingSearch = ConcurrentHashMap.newKeySet();
    private final Set<UUID> navigating = ConcurrentHashMap.newKeySet();

    public GuiManager(AuctionHousePlugin plugin, AuctionService auctionService, AuctionManager auctionManager,
                      PlayerPreferenceManager preferenceManager, AuctionLimitService limitService,
                      DonutCoreHook donutCoreHook) {
        this.plugin = plugin;
        this.auctionService = auctionService;
        this.auctionManager = auctionManager;
        this.preferenceManager = preferenceManager;
        this.limitService = limitService;
        this.donutCoreHook = donutCoreHook;
    }

    public void openAuctionHouse(Player player) {
        open(player, new AuctionGui(this, auctionService, preferenceManager, donutCoreHook, session(player)));
    }

    public void openAuctionHouse(Player player, PlayerAuctionSession session) {
        open(player, new AuctionGui(this, auctionService, preferenceManager, donutCoreHook, session));
    }

    public void openFilterMenu(Player player) {
        open(player, new FilterGui(this, session(player)));
    }

    public void openPlayerItems(Player player) {
        open(player, new PlayerAuctionGui(this, auctionService, playerItemsPage(player.getUniqueId())));
    }

    public void openConfirmPurchase(Player player, AuctionListing listing) {
        open(player, new ConfirmPurchaseGui(this, auctionService, listing));
    }

    public void openSellGui(Player player, SellGui sellGui) {
        open(player, sellGui);
    }

    public void openShulkerPreview(Player player, ItemStack shulkerItem) {
        open(player, new ShulkerPreviewGui(this, shulkerItem));
    }

    /**
     * Starts the sell flow for the item in the player's main hand.
     * Uses stored last price when available, otherwise the configured minimum price.
     */
    public void startSellFromHeldItem(Player player) {
        if (!player.hasPermission("donutcore.auction.sell") && !player.hasPermission("donutauction.sell")) {
            plugin.messages().sendRaw(player, "command.no-permission-sell", "&cYou do not have permission to sell items.");
            return;
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand == null || itemInHand.getType() == Material.AIR) {
            plugin.messages().sendRaw(player, "service.hold-item", "&cHold the item you want to list.");
            return;
        }

        int activeCount = auctionService.getPlayerActiveAuctionCount(player.getUniqueId());
        if (!limitService.canCreateListing(player, activeCount)) {
            int limit = limitService.getEffectiveLimit(player);
            plugin.messages().sendFormatted(
                    player,
                    "command.auction-limit-reached",
                    "&cYou have reached your auction limit of %limit% listings.",
                    "limit", String.valueOf(limit));
            return;
        }

        PlayerPreference pref = preferenceManager.getCached(player.getUniqueId());
        double price = pref != null && pref.lastPrice() > 0.0D
                ? pref.lastPrice()
                : auctionService.getMinListingPrice();

        startSellFromHeldItem(player, price);
    }

    public void startSellFromHeldItem(Player player, double price) {
        if (!player.hasPermission("donutcore.auction.sell") && !player.hasPermission("donutauction.sell")) {
            plugin.messages().sendRaw(player, "command.no-permission-sell", "&cYou do not have permission to sell items.");
            return;
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand == null || itemInHand.getType() == Material.AIR) {
            plugin.messages().sendRaw(player, "service.hold-item", "&cHold the item you want to list.");
            return;
        }

        int activeCount = auctionService.getPlayerActiveAuctionCount(player.getUniqueId());
        if (!limitService.canCreateListing(player, activeCount)) {
            int limit = limitService.getEffectiveLimit(player);
            plugin.messages().sendFormatted(
                    player,
                    "command.auction-limit-reached",
                    "&cYou have reached your auction limit of %limit% listings.",
                    "limit", String.valueOf(limit));
            return;
        }

        ItemStack ownedItem = itemInHand.clone();
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));

        double listingPrice = auctionService.clampListingPrice(price);

        PlayerPreference pref = preferenceManager.getCached(player.getUniqueId());
        boolean fastSell = pref != null && pref.fastSellEnabled() && player.hasPermission("donutauction.fastsell");

        if (fastSell) {
            int duration = pref != null ? pref.lastDurationHours() : plugin.getConfig().getInt("auction.listing-duration-hours", 48);
            AuctionFilterCategory category = AuctionFilterCategory.ALL;
            if (pref != null) {
                try {
                    category = AuctionFilterCategory.valueOf(pref.lastCategory());
                } catch (IllegalArgumentException ignored) {
                }
            }
            final int finalDuration = duration;
            final AuctionFilterCategory finalCategory = category;

            auctionService.createAuctionFromItem(player, ownedItem, listingPrice, finalDuration, finalCategory)
                    .thenAccept(result -> plugin.schedulerAdapter().runEntity(player, () -> {
                        plugin.messages().send(player, result.message());
                        if (result.success()) {
                            openPlayerItems(player);
                        }
                    }));
        } else {
            PendingSaleTransaction transaction = auctionService.beginPendingSale(player, ownedItem, listingPrice);
            SellGui sellGui = new SellGui(this, auctionService, preferenceManager, transaction, pref);
            openSellGui(player, sellGui);
        }
    }

    public void setPlayerItemsPage(UUID playerId, int page) {
        playerItemsPages.put(playerId, Math.max(1, page));
    }

    public int playerItemsPage(UUID playerId) {
        return playerItemsPages.getOrDefault(playerId, 1);
    }

    public void refreshAuctionHouse(Player player) {
        openAuctionHouse(player, session(player));
    }

    public void beginSearch(Player player) {
        awaitingSearch.add(player.getUniqueId());
        plugin.messages().sendRaw(player, "command.search-prompt", "&eType an item name in chat to search the auction house.");
        player.closeInventory();
    }

    public boolean isAwaitingSearch(UUID playerId) {
        return awaitingSearch.contains(playerId);
    }

    public void handleSearchInput(Player player, String query) {
        awaitingSearch.remove(player.getUniqueId());
        PlayerAuctionSession session = session(player);
        session.request(session.request().withSearch(query));
        openAuctionHouse(player, session);
    }

    public void resetSearchFilter(Player player) {
        PlayerAuctionSession session = sessions.get(player.getUniqueId());
        if (session == null || session.request().searchTerm().isBlank()) {
            return;
        }
        session.request(session.request().withSearch(""));
    }

    public boolean isNavigating(UUID playerId) {
        return navigating.remove(playerId);
    }

    public void clear(Player player) {
        awaitingSearch.remove(player.getUniqueId());
        navigating.remove(player.getUniqueId());
        sessions.remove(player.getUniqueId());
        playerItemsPages.remove(player.getUniqueId());
    }

    public void handleInventoryClick(Player player, InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof BaseGui gui)) {
            return;
        }
        event.setCancelled(true);
        gui.handleClick(player, event);
    }

    public PlayerAuctionSession session(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), PlayerAuctionSession::new);
    }

    public AuctionHousePlugin plugin() {
        return plugin;
    }

    public AuctionManager auctionManager() {
        return auctionManager;
    }

    private void open(Player player, BaseGui gui) {
        navigating.add(player.getUniqueId());
        player.openInventory(gui.render(player));
    }
}
