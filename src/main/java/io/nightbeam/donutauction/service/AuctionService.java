package io.nightbeam.donutauction.service;

import io.nightbeam.donutauction.AuctionHousePlugin;
import io.nightbeam.donutauction.economy.VaultEconomyProvider;
import io.nightbeam.donutauction.hook.DonutCoreHook;
import io.nightbeam.donutauction.model.AuctionBrowseRequest;
import io.nightbeam.donutauction.model.AuctionFilterCategory;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionPage;
import io.nightbeam.donutauction.model.AuctionStatus;
import io.nightbeam.donutauction.model.ListingPriceValidationResult;
import io.nightbeam.donutauction.model.PendingSaleTransaction;
import io.nightbeam.donutauction.storage.AuctionRepository;
import io.nightbeam.donutauction.util.ItemLoreApplier;
import io.nightbeam.donutauction.util.SchedulerAdapter;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class AuctionService {

    private final AuctionHousePlugin plugin;
    private final SchedulerAdapter schedulerAdapter;
    private final VaultEconomyProvider economyProvider;
    private final AuctionRepository repository;
    private final AuctionManager auctionManager;
    private final DonutCoreHook donutCoreHook;
    private final PendingSaleRegistry pendingSaleRegistry;
    private final Map<UUID, AtomicBoolean> operationLocks = new ConcurrentHashMap<>();

    private ScheduledTask expiryTask;

    public AuctionService(
            AuctionHousePlugin plugin,
            SchedulerAdapter schedulerAdapter,
            VaultEconomyProvider economyProvider,
            AuctionRepository repository,
            AuctionManager auctionManager,
            DonutCoreHook donutCoreHook
    ) {
        this.plugin = plugin;
        this.schedulerAdapter = schedulerAdapter;
        this.economyProvider = economyProvider;
        this.repository = repository;
        this.auctionManager = auctionManager;
        this.donutCoreHook = donutCoreHook;
        boolean debugPending = plugin.getConfig().getBoolean("debug.pending-sales", false);
        this.pendingSaleRegistry = new PendingSaleRegistry(plugin.getLogger(), debugPending);
    }

    public void initialize() {
        repository.initialize()
                .thenCompose(ignored -> repository.loadAll())
                .thenAccept(auctionManager::replaceAll)
                .exceptionally(throwable -> {
                    plugin.getLogger().severe("Failed to initialize auction storage: " + throwable.getMessage());
                    throwable.printStackTrace();
                    return null;
                });

        long intervalSeconds = plugin.getConfig().getLong("auction.expired-scan-interval-seconds", 60L);
        long intervalTicks = Math.max(20L, intervalSeconds * 20L);
        this.expiryTask = schedulerAdapter.runGlobalRepeating(this::scanAndExpireAuctions, intervalTicks, intervalTicks);
    }

    public void shutdown() {
        if (expiryTask != null) {
            expiryTask.cancel();
        }
        recoverPendingSalesOnShutdown();
        schedulerAdapter.shutdown();
    }

    /**
     * Starts a pending sale after the item has been removed from the player inventory.
     * If a previous pending sale existed for this player, that item is restored first.
     */
    public PendingSaleTransaction beginPendingSale(Player player, ItemStack item, double price) {
        PendingSaleRegistry.BeginResult beginResult = pendingSaleRegistry.begin(player.getUniqueId(), item, price);
        if (beginResult.replaced() != null) {
            restoreItem(player, beginResult.replaced().item());
            plugin.getLogger().warning("Player " + player.getName()
                    + " started a new pending sale while another was open; previous item was restored.");
        }
        return beginResult.created();
    }

    /**
     * Atomically claims and returns the pending sale item to the player.
     * Safe to call multiple times — only the first successful claim returns the item.
     *
     * @return true if this call owned and returned the item
     */
    public boolean cancelPendingSale(Player player, UUID transactionId) {
        PendingSaleRegistry.ClaimResult claim = pendingSaleRegistry.claim(transactionId, player.getUniqueId());
        if (!claim.success()) {
            return false;
        }
        restoreItem(player, claim.transaction().item());
        return true;
    }

    /**
     * Claims any pending sale for the player (disconnect / inventory close / recovery).
     *
     * @return true if an item was returned
     */
    public boolean cancelPendingSaleForPlayer(Player player) {
        PendingSaleRegistry.ClaimResult claim = pendingSaleRegistry.claimByPlayer(player.getUniqueId());
        if (!claim.success()) {
            return false;
        }
        restoreItem(player, claim.transaction().item());
        return true;
    }

    /**
     * Atomically claims the pending sale and lists the item. Idempotent against
     * double-clicks and concurrent cancel/close paths.
     */
    public CompletableFuture<ActionResult> confirmPendingSale(
            Player player,
            UUID transactionId,
            int durationHours,
            AuctionFilterCategory category
    ) {
        PendingSaleRegistry.ClaimResult claim = pendingSaleRegistry.claim(transactionId, player.getUniqueId());
        if (!claim.success()) {
            return CompletableFuture.completedFuture(
                    ActionResult.failure(msg("service.sell-confirmation-invalid", "&cThat sell confirmation is no longer valid."))
            );
        }
        PendingSaleTransaction transaction = claim.transaction();
        return createAuctionFromItem(player, transaction.item(), transaction.price(), durationHours, category);
    }

    public CompletableFuture<ActionResult> confirmPendingSale(
            Player player,
            UUID transactionId,
            int durationHours,
            AuctionFilterCategory category,
            double listingPrice
    ) {
        PendingSaleRegistry.ClaimResult claim = pendingSaleRegistry.claim(transactionId, player.getUniqueId());
        if (!claim.success()) {
            return CompletableFuture.completedFuture(
                    ActionResult.failure(msg("service.sell-confirmation-invalid", "&cThat sell confirmation is no longer valid."))
            );
        }
        PendingSaleTransaction transaction = claim.transaction();
        return createAuctionFromItem(player, transaction.item(), listingPrice, durationHours, category);
    }

    public PendingSaleRegistry pendingSaleRegistry() {
        return pendingSaleRegistry;
    }

    public AuctionPage browse(AuctionBrowseRequest request) {
        return auctionManager.browse(request, System.currentTimeMillis());
    }

    public List<AuctionListing> getPlayerAuctions(UUID playerId) {
        return auctionManager.sellerListings(playerId);
    }

    public int getPlayerActiveAuctionCount(UUID playerId) {
        return (int) auctionManager.sellerListings(playerId).stream()
                .filter(listing -> listing.status() == AuctionStatus.ACTIVE)
                .count();
    }

    public CompletableFuture<ActionResult> createAuction(Player player, ItemStack itemInHand, double price) {
        long durationHours = plugin.getConfig().getLong("auction.listing-duration-hours", 48L);
        return createAuction(player, itemInHand, price, (int) durationHours, AuctionFilterCategory.ALL);
    }

    public CompletableFuture<ActionResult> createAuction(Player player, ItemStack itemInHand, double price, int durationHours, AuctionFilterCategory category) {
        if (itemInHand == null || itemInHand.getType() == Material.AIR) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.hold-item", "&cHold the item you want to list.")));
        }
        removeHeldItem(player);
        return createAuctionFromItem(player, itemInHand, price, durationHours, category);
    }

    public CompletableFuture<ActionResult> createAuctionFromItem(Player player, ItemStack item, double price, int durationHours, AuctionFilterCategory category) {
        if (item == null || item.getType() == Material.AIR) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.invalid-item", "&cThe item is invalid.")));
        }

        // Always work from a defensive clone so NBT/components are preserved independently of caller state.
        ItemStack listingItem = item.clone();

        double minPrice = plugin.getConfig().getDouble("auction.min-price", 10.0D);
        double maxPrice = plugin.getConfig().getDouble("auction.max-price", 1.0E9);
        ListingPriceValidationResult validationResult = ListingPriceValidationResult.validate(price, minPrice, maxPrice);
        if (validationResult == ListingPriceValidationResult.BELOW_MINIMUM) {
            restoreItem(player, listingItem);
            String configFallback = plugin.getConfig().getString(
                    "messages.price-below-min", "&cMinimum auction price is &6%min_price%&c.");
            String message = msg(
                    "service.price-below-min",
                    configFallback,
                    "min_price", economyProvider.format(Math.max(0.0D, minPrice)));
            return CompletableFuture.completedFuture(ActionResult.failure(message));
        }
        if (validationResult == ListingPriceValidationResult.INVALID_OR_ABOVE_MAX) {
            restoreItem(player, listingItem);
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.price-invalid", "&cPrice must be greater than 0 and below the configured limit.")));
        }

        long now = System.currentTimeMillis();
        long durationMillis = Math.max(1L, durationHours) * 3_600_000L;
        AuctionListing listing = new AuctionListing(UUID.randomUUID(), listingItem.clone(), player.getUniqueId(), price, now, now + durationMillis, AuctionStatus.ACTIVE, null, 0L, false);

        return repository.save(listing)
                .thenApply(ignored -> {
                    auctionManager.upsert(listing);
                    return ActionResult.success(msg(
                            "service.listed",
                            "&aListed %item% for &6%price%&a.",
                            "item", itemName(listingItem),
                            "price", economyProvider.format(price)));
                })
                .exceptionally(throwable -> {
                    schedulerAdapter.runEntity(player, () -> restoreItem(player, listingItem));
                    plugin.getLogger().severe("Failed to save auction listing: " + throwable.getMessage());
                    return ActionResult.failure(msg(
                            "service.create-failed",
                            "&cFailed to create the auction. Your item was returned."));
                });
    }

    public CompletableFuture<ActionResult> purchaseAuction(Player buyer, UUID auctionId) {
        return purchaseAuction(buyer, auctionId, false);
    }

    public CompletableFuture<ActionResult> purchaseAuction(Player buyer, UUID auctionId, boolean fastBuy) {
        AuctionListing listing = auctionManager.findCached(auctionId);
        if (listing == null) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.auction-unavailable", "&cThat auction is no longer available.")));
        }
        if (listing.seller().equals(buyer.getUniqueId())) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.cannot-buy-own", "&cYou cannot buy your own auction.")));
        }

        long now = System.currentTimeMillis();
        if (!listing.isActive(now)) {
            expireListingIfNeeded(listing);
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.auction-expired", "&cThat auction has expired.")));
        }

        AtomicBoolean operationLock = operationLocks.computeIfAbsent(auctionId, ignored -> new AtomicBoolean());
        if (!operationLock.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.auction-processing", "&cThat auction is already being processed.")));
        }

        OfflinePlayer offlineBuyer = Bukkit.getOfflinePlayer(buyer.getUniqueId());
        if (!economyProvider.has(offlineBuyer, listing.price())) {
            operationLock.set(false);
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.insufficient-funds", "&cYou do not have enough money.")));
        }

        EconomyResponse withdrawal = economyProvider.withdraw(offlineBuyer, listing.price());
        if (!withdrawal.transactionSuccess()) {
            operationLock.set(false);
            return CompletableFuture.completedFuture(ActionResult.failure(msg(
                    "service.withdraw-failed",
                    "&cUnable to withdraw funds: %error%",
                    "error", withdrawal.errorMessage)));
        }

        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.seller());
        EconomyResponse deposit = economyProvider.deposit(seller, listing.price());
        if (!deposit.transactionSuccess()) {
            economyProvider.deposit(offlineBuyer, listing.price());
            operationLock.set(false);
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.pay-seller-failed", "&cUnable to pay the seller right now.")));
        }

        AuctionListing soldListing = listing.asSold(buyer.getUniqueId(), now);
        auctionManager.upsert(soldListing);

        return repository.update(soldListing)
                .thenApply(ignored -> {
                    deliverItem(buyer, soldListing.item());
                    return ActionResult.success(msg(
                            "service.purchased",
                            "&aPurchased %item% for &6%price%&a.",
                            "item", itemName(soldListing.item()),
                            "price", economyProvider.format(soldListing.price())));
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().severe("Failed to persist auction purchase: " + throwable.getMessage());
                    economyProvider.deposit(offlineBuyer, listing.price());
                    economyProvider.withdraw(seller, listing.price());
                    auctionManager.upsert(listing);
                    return ActionResult.failure(msg(
                            "service.purchase-failed",
                            "&cPurchase failed. Your money has been refunded."));
                })
                .whenComplete((result, throwable) -> {
                    operationLock.set(false);
                    operationLocks.remove(auctionId);
                });
    }

    public CompletableFuture<ActionResult> cancelAuction(Player seller, UUID auctionId) {
        AtomicBoolean operationLock = operationLocks.computeIfAbsent(auctionId, ignored -> new AtomicBoolean());
        if (!operationLock.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.auction-processing", "&cThat auction is already being processed.")));
        }

        AuctionListing listing = auctionManager.findCached(auctionId);
        if (listing == null || !listing.seller().equals(seller.getUniqueId())) {
            releaseOperationLock(auctionId, operationLock);
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.auction-not-found", "&cAuction not found.")));
        }
        if (listing.status() != AuctionStatus.ACTIVE) {
            releaseOperationLock(auctionId, operationLock);
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.only-active-cancel", "&cOnly active auctions can be cancelled.")));
        }

        AuctionListing cancelled = listing.withStatus(AuctionStatus.CANCELLED).markSellerClaimed();
        auctionManager.upsert(cancelled);

        return repository.update(cancelled)
                .thenApply(ignored -> {
                    restoreItem(seller, cancelled.item());
                    return ActionResult.success(msg(
                            "service.cancelled",
                            "&aAuction cancelled and item returned."));
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().severe("Failed to persist cancellation: " + throwable.getMessage());
                    auctionManager.upsert(listing);
                    return ActionResult.failure(msg(
                            "service.cancel-failed",
                            "&cFailed to cancel auction. Please try again."));
                })
                .whenComplete((result, throwable) -> releaseOperationLock(auctionId, operationLock));
    }

    public CompletableFuture<ActionResult> collectSellerProceeds(Player seller, UUID auctionId) {
        AtomicBoolean operationLock = operationLocks.computeIfAbsent(auctionId, ignored -> new AtomicBoolean());
        if (!operationLock.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.auction-processing", "&cThat auction is already being processed.")));
        }

        AuctionListing listing = auctionManager.findCached(auctionId);
        if (listing == null || !listing.seller().equals(seller.getUniqueId())) {
            releaseOperationLock(auctionId, operationLock);
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.auction-not-found", "&cAuction not found.")));
        }
        if (listing.sellerClaimed()) {
            releaseOperationLock(auctionId, operationLock);
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.already-collected", "&cThat auction has already been collected.")));
        }

        if (listing.status() == AuctionStatus.SOLD) {
            AuctionListing claimed = listing.markSellerClaimed();
            auctionManager.upsert(claimed);
            return repository.update(claimed)
                    .thenApply(ignored -> ActionResult.success(msg(
                            "service.sale-collected",
                            "&aSale marked as collected. Payment was delivered through Vault at purchase time.")))
                    .exceptionally(throwable -> {
                        auctionManager.upsert(listing);
                        return ActionResult.failure(msg(
                                "service.collection-update-failed",
                                "&cUnable to update the collection state."));
                    })
                    .whenComplete((result, throwable) -> releaseOperationLock(auctionId, operationLock));
        }

        if (listing.status() == AuctionStatus.EXPIRED || listing.status() == AuctionStatus.CANCELLED) {
            AuctionListing collected = listing.markSellerClaimed();
            auctionManager.upsert(collected);
            return repository.update(collected)
                    .thenApply(ignored -> {
                        restoreItem(seller, listing.item());
                        return ActionResult.success(msg(
                                "service.item-returned",
                                "&aReturned your unsold item."));
                    })
                    .exceptionally(throwable -> {
                        auctionManager.upsert(listing);
                        return ActionResult.failure(msg(
                                "service.collection-update-failed-retry",
                                "&cUnable to update the collection state. Please try again."));
                    })
                    .whenComplete((result, throwable) -> releaseOperationLock(auctionId, operationLock));
        }

        releaseOperationLock(auctionId, operationLock);
        return CompletableFuture.completedFuture(ActionResult.failure(
                msg("service.nothing-to-collect", "&cNothing to collect for that auction.")));
    }

    private String msg(String path, String def) {
        return plugin.messages().raw(path, def);
    }

    private String msg(String path, String def, String... keyValues) {
        return plugin.messages().format(path, def, keyValues);
    }

    private void releaseOperationLock(UUID auctionId, AtomicBoolean operationLock) {
        operationLock.set(false);
        operationLocks.remove(auctionId, operationLock);
    }

    public Optional<AuctionListing> findListing(UUID auctionId) {
        return Optional.ofNullable(auctionManager.findCached(auctionId));
    }

    public DonutCoreHook donutCoreHook() {
        return donutCoreHook;
    }

    public String formatPrice(double price) {
        return economyProvider.format(price);
    }

    public double getBalance(OfflinePlayer player) {
        return economyProvider.getBalance(player);
    }

    public double getMinListingPrice() {
        return plugin.getConfig().getDouble("auction.min-price", 10.0D);
    }

    public double getMaxListingPrice() {
        return plugin.getConfig().getDouble("auction.max-price", 1.0E9);
    }

    public double clampListingPrice(double price) {
        return Math.max(getMinListingPrice(), Math.min(getMaxListingPrice(), price));
    }

    public ItemLoreApplier.LoreMode getLoreMode() {
        String mode = plugin.getConfig().getString("auction-lore.mode", "APPEND");
        return ItemLoreApplier.parseMode(mode);
    }

    public boolean getLoreSeparator() {
        return plugin.getConfig().getBoolean("auction-lore.separator", true);
    }

    private void scanAndExpireAuctions() {
        long now = System.currentTimeMillis();
        repository.findExpiredActive(now).thenAccept(listings -> {
            for (AuctionListing listing : listings) {
                AuctionListing expired = listing.asExpired();
                auctionManager.upsert(expired);
                repository.update(expired).exceptionally(throwable -> {
                    plugin.getLogger().warning("Failed to persist expired auction " + listing.auctionId() + ": " + throwable.getMessage());
                    return null;
                });
            }
        }).exceptionally(throwable -> {
            plugin.getLogger().warning("Failed to scan expired auctions: " + throwable.getMessage());
            return null;
        });
    }

    private void expireListingIfNeeded(AuctionListing listing) {
        if (listing.status() != AuctionStatus.ACTIVE) {
            return;
        }
        AuctionListing expired = listing.asExpired();
        auctionManager.upsert(expired);
        repository.update(expired).exceptionally(throwable -> {
            plugin.getLogger().warning("Failed to persist expired auction " + listing.auctionId() + ": " + throwable.getMessage());
            return null;
        });
    }

    private void removeHeldItem(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.setItemInMainHand(new ItemStack(Material.AIR));
    }

    /**
     * Returns an item to the player inventory. Preserves full item data via clone.
     * Overflow drops at the player's location (existing recovery behavior).
     */
    public void restoreItem(Player player, ItemStack itemStack) {
        if (player == null || itemStack == null || itemStack.getType() == Material.AIR) {
            return;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(itemStack.clone());
        leftovers.values().forEach(leftover -> {
            if (player.getWorld() != null) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        });
    }

    private void deliverItem(Player player, ItemStack itemStack) {
        restoreItem(player, itemStack);
    }

    private void recoverPendingSalesOnShutdown() {
        List<PendingSaleTransaction> pending = pendingSaleRegistry.drainAll();
        if (pending.isEmpty()) {
            return;
        }
        plugin.getLogger().info("Recovering " + pending.size() + " pending sale(s) on shutdown.");
        for (PendingSaleTransaction transaction : pending) {
            Player player = Bukkit.getPlayer(transaction.playerId());
            if (player != null && player.isOnline()) {
                restoreItem(player, transaction.item());
            } else {
                plugin.getLogger().warning("Could not return pending sale item for offline player "
                        + transaction.playerId() + " (tx=" + transaction.transactionId() + "). Item may be lost.");
            }
        }
    }

    private String itemName(ItemStack itemStack) {
        if (itemStack.hasItemMeta() && itemStack.getItemMeta().hasDisplayName()) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(itemStack.getItemMeta().displayName());
        }
        return itemStack.getType().name().replace('_', ' ');
    }
}
