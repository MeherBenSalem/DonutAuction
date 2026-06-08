package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.AuctionHousePlugin;
import io.nightbeam.donutauction.hook.DonutCoreHook;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.PlayerAuctionSession;
import io.nightbeam.donutauction.model.PlayerPreference;
import io.nightbeam.donutauction.service.AuctionManager;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.service.PlayerPreferenceManager;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public final class GuiManager {

    private final AuctionHousePlugin plugin;
    private final AuctionService auctionService;
    private final AuctionManager auctionManager;
    private final PlayerPreferenceManager preferenceManager;
    private final DonutCoreHook donutCoreHook;
    private final Map<UUID, PlayerAuctionSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerItemsPages = new ConcurrentHashMap<>();
    private final Set<UUID> awaitingSearch = ConcurrentHashMap.newKeySet();
    private final Set<UUID> navigating = ConcurrentHashMap.newKeySet();

    public GuiManager(AuctionHousePlugin plugin, AuctionService auctionService, AuctionManager auctionManager,
                      PlayerPreferenceManager preferenceManager, DonutCoreHook donutCoreHook) {
        this.plugin = plugin;
        this.auctionService = auctionService;
        this.auctionManager = auctionManager;
        this.preferenceManager = preferenceManager;
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
        plugin.messages().send(player, "&eType an item name in chat to search the auction house.");
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
