package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.AuctionHousePlugin;
import io.nightbeam.donutauction.hook.DonutCoreHook;
import io.nightbeam.donutauction.model.AuctionFilterCategory;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.HistoryView;
import io.nightbeam.donutauction.model.ListingPriceValidationResult;
import io.nightbeam.donutauction.model.ListingType;
import io.nightbeam.donutauction.model.PendingSaleTransaction;
import io.nightbeam.donutauction.model.PlayerAuctionSession;
import io.nightbeam.donutauction.model.PlayerPreference;
import io.nightbeam.donutauction.service.ActionResult;
import io.nightbeam.donutauction.service.AuctionLimitService;
import io.nightbeam.donutauction.service.AuctionManager;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.service.PlayerPreferenceManager;
import io.nightbeam.donutauction.util.Sounds;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public final class GuiManager {

    /** A question asked in chat: search terms, an exact price or a custom bid. */
    private record InputRequest(long id, BiConsumer<Player, String> handler, Runnable onCancel) {
    }

    private static final long INPUT_TIMEOUT_TICKS = 20L * 60L;

    private final AuctionHousePlugin plugin;
    private final AuctionService auctionService;
    private final AuctionManager auctionManager;
    private final PlayerPreferenceManager preferenceManager;
    private final AuctionLimitService limitService;
    private final DonutCoreHook donutCoreHook;
    private final Map<UUID, PlayerAuctionSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerItemsPages = new ConcurrentHashMap<>();
    private final Map<UUID, InputRequest> inputs = new ConcurrentHashMap<>();
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

    /** The browser narrowed to one seller ({@code /ah view <player>}). */
    public void openSellerListings(Player player, UUID seller, String sellerName) {
        PlayerAuctionSession session = session(player);
        session.request(session.request().withSeller(seller, sellerName).withSearch(""));
        openAuctionHouse(player, session);
    }

    public void openFilterMenu(Player player) {
        open(player, new FilterGui(this, session(player)));
    }

    public void openPlayerItems(Player player) {
        open(player, new PlayerAuctionGui(this, auctionService, player.getUniqueId(), null, playerItemsPage(player.getUniqueId())));
    }

    /** Staff view of another player's listings; clicking an active listing removes it. */
    public void openAdminListings(Player admin, UUID target, String targetName) {
        open(admin, new PlayerAuctionGui(this, auctionService, target, targetName == null ? target.toString() : targetName, 1));
    }

    public void openConfirmPurchase(Player player, AuctionListing listing) {
        open(player, new ConfirmPurchaseGui(this, auctionService, listing));
    }

    public void openBid(Player player, AuctionListing listing) {
        open(player, new BidGui(this, auctionService, listing));
    }

    public void openHistory(Player player, HistoryView view, int page) {
        if (!player.hasPermission("donutauction.use") && !player.hasPermission("donutcore.auction.use")) return;
        HistoryGui gui = new HistoryGui(this, auctionService, player.getUniqueId(), view, page);
        open(player, gui);
        gui.load(player);
    }

    void refreshHistory(Player player, HistoryGui gui) {
        if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder(false) == gui) open(player, gui);
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
        PlayerPreference pref = preferenceManager.getCached(player.getUniqueId());
        double price = pref != null && pref.lastPrice() > 0.0D
                ? pref.lastPrice()
                : auctionService.getMinListingPrice();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held != null && !held.getType().isAir()) {
            price = Math.max(auctionService.rules().minPrice(held), Math.min(auctionService.rules().maxPrice(held), price));
        }
        startSellFromHeldItem(player, price, ListingType.BIN);
    }

    public void startSellFromHeldItem(Player player, double price) {
        startSellFromHeldItem(player, price, ListingType.BIN);
    }

    public void startSellFromHeldItem(Player player, double price, ListingType type) {
        if (!player.hasPermission("donutcore.auction.sell") && !player.hasPermission("donutauction.sell")) {
            plugin.messages().sendRaw(player, "command.no-permission-sell", "&cYou do not have permission to sell items.");
            return;
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand == null || itemInHand.getType() == Material.AIR) {
            plugin.messages().sendRaw(player, "service.hold-item", "&cHold the item you want to list.");
            return;
        }

        Optional<String> refused = auctionService.checkCanSell(player, itemInHand);
        if (refused.isPresent()) {
            plugin.messages().send(player, refused.get());
            Sounds.play(player, plugin.getConfig(), "error");
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

        double minPrice = auctionService.rules().minPrice(itemInHand);
        ListingPriceValidationResult validation = ListingPriceValidationResult.validate(
                price, minPrice, auctionService.rules().maxPrice(itemInHand));
        if (validation != ListingPriceValidationResult.VALID) {
            if (validation == ListingPriceValidationResult.BELOW_MINIMUM) {
                plugin.messages().sendFormatted(player, "service.price-below-min",
                        "&cMinimum auction price is &6%min_price%&c.",
                        "min_price", auctionService.formatPrice(minPrice));
            } else {
                plugin.messages().sendRaw(player, "command.invalid-price", "&cInvalid price.");
            }
            return;
        }

        ListingType listingType = type == ListingType.BID && auctionService.canStartAuctions(player) ? ListingType.BID : ListingType.BIN;
        ItemStack ownedItem = itemInHand.clone();
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));

        PlayerPreference pref = preferenceManager.getCached(player.getUniqueId());
        boolean fastSell = pref != null && pref.fastSellEnabled() && player.hasPermission("donutauction.fastsell");

        if (fastSell) {
            int duration = pref.lastDurationHours();
            auctionService.createListing(player, ownedItem, price, duration, listingType)
                    .thenAccept(result -> plugin.schedulerAdapter().runEntity(player, () -> {
                        plugin.messages().send(player, result.message());
                        feedback(player, result);
                        if (result.success()) {
                            openPlayerItems(player);
                        }
                    }));
        } else {
            PendingSaleTransaction transaction = auctionService.beginPendingSale(player, ownedItem, price);
            SellGui sellGui = new SellGui(this, auctionService, preferenceManager, transaction, pref, listingType);
            openSellGui(player, sellGui);
        }
    }

    /** Plays the success or error sound for an action. */
    public void feedback(Player player, ActionResult result) {
        Sounds.play(player, plugin.getConfig(), result.success() ? "success" : "error");
    }

    public void click(Player player) {
        Sounds.play(player, plugin.getConfig(), "click");
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
        beginInput(player, "command.search-prompt", "&eType an item name, enchantment or player in chat. Type &6cancel &eto go back.",
                (who, text) -> {
                    PlayerAuctionSession session = session(who);
                    session.request(session.request().withSearch(text));
                    openAuctionHouse(who, session);
                },
                () -> openAuctionHouse(player));
    }

    /**
     * Closes the menu and asks a question in chat. The answer (or "cancel", or a 60 second timeout)
     * runs on the player's thread.
     */
    public void beginInput(Player player, String promptPath, String promptDefault, BiConsumer<Player, String> handler, Runnable onCancel) {
        long id = System.nanoTime();
        inputs.put(player.getUniqueId(), new InputRequest(id, handler, onCancel));
        plugin.messages().sendRaw(player, promptPath, promptDefault);
        player.closeInventory();
        plugin.schedulerAdapter().runEntityLater(player, () -> {
            InputRequest pending = inputs.get(player.getUniqueId());
            if (pending != null && pending.id() == id && inputs.remove(player.getUniqueId(), pending)) {
                plugin.messages().sendRaw(player, "command.input-timeout", "&7No answer, so nothing was changed.");
                pending.onCancel().run();
            }
        }, INPUT_TIMEOUT_TICKS);
    }

    public boolean isAwaitingSearch(UUID playerId) {
        return inputs.containsKey(playerId);
    }

    public boolean isAwaitingInput(UUID playerId) {
        return inputs.containsKey(playerId);
    }

    /** Called on the player's thread with what they typed. */
    public void handleSearchInput(Player player, String text) {
        InputRequest request = inputs.remove(player.getUniqueId());
        if (request == null) return;
        String answer = text == null ? "" : text.trim();
        if (answer.equalsIgnoreCase("cancel") || answer.equalsIgnoreCase(plugin.messages().raw("command.cancel-word", "cancel"))) {
            request.onCancel().run();
            return;
        }
        request.handler().accept(player, answer);
    }

    public void resetSearchFilter(Player player) {
        PlayerAuctionSession session = sessions.get(player.getUniqueId());
        if (session == null || (session.request().searchTerm().isBlank() && session.request().seller() == null)) {
            return;
        }
        session.request(session.request().withSearch("").withSeller(null, null));
    }

    public boolean isNavigating(UUID playerId) {
        return navigating.remove(playerId);
    }

    /** On quit. A pending sale behind an open question is returned by the quit listener. */
    public void clear(Player player) {
        inputs.remove(player.getUniqueId());
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

    public AuctionLimitService limitService() {
        return limitService;
    }

    public PlayerPreferenceManager preferences() {
        return preferenceManager;
    }

    public DonutCoreHook donutCoreHook() {
        return donutCoreHook;
    }

    /** The seller's display name: DonutCore's while online, else the name stored with the listing. */
    public String sellerName(AuctionListing listing) {
        org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(listing.seller());
        if (online != null && online.isOnline()) return donutCoreHook.resolveDisplayName(online);
        if (listing.sellerName() != null && !listing.sellerName().isBlank()) return listing.sellerName();
        String name = org.bukkit.Bukkit.getOfflinePlayer(listing.seller()).getName();
        return name == null ? plugin.messages().unknownSeller() : name;
    }

    /** A category from a command argument, or ALL. */
    public static AuctionFilterCategory category(String input) {
        return input == null ? AuctionFilterCategory.ALL : AuctionFilterCategory.fromInput(input.toUpperCase(Locale.ROOT)).orElse(AuctionFilterCategory.ALL);
    }

    private void open(Player player, BaseGui gui) {
        navigating.add(player.getUniqueId());
        player.openInventory(gui.render(player));
    }
}
