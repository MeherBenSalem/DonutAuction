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
        schedulerAdapter.shutdown();
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
            return CompletableFuture.completedFuture(ActionResult.failure("&cHold the item you want to list."));
        }
        removeHeldItem(player);
        return createAuctionFromItem(player, itemInHand, price, durationHours, category);
    }

    public CompletableFuture<ActionResult> createAuctionFromItem(Player player, ItemStack item, double price, int durationHours, AuctionFilterCategory category) {
        if (item == null || item.getType() == Material.AIR) {
            return CompletableFuture.completedFuture(ActionResult.failure("&cThe item is invalid."));
        }

        double minPrice = plugin.getConfig().getDouble("auction.min-price", 10.0D);
        double maxPrice = plugin.getConfig().getDouble("auction.max-price", 1.0E9);
        ListingPriceValidationResult validationResult = ListingPriceValidationResult.validate(price, minPrice, maxPrice);
        if (validationResult == ListingPriceValidationResult.BELOW_MINIMUM) {
            String message = plugin.getConfig().getString("messages.price-below-min", "&cMinimum auction price is &6%min_price%&c.");
            message = message.replace("%min_price%", economyProvider.format(Math.max(0.0D, minPrice)));
            return CompletableFuture.completedFuture(ActionResult.failure(message));
        }
        if (validationResult == ListingPriceValidationResult.INVALID_OR_ABOVE_MAX) {
            return CompletableFuture.completedFuture(ActionResult.failure("&cPrice must be greater than 0 and below the configured limit."));
        }

        long now = System.currentTimeMillis();
        long durationMillis = Math.max(1L, durationHours) * 3_600_000L;
        AuctionListing listing = new AuctionListing(UUID.randomUUID(), item.clone(), player.getUniqueId(), price, now, now + durationMillis, AuctionStatus.ACTIVE, null, 0L, false);

        return repository.save(listing)
                .thenApply(ignored -> {
                    auctionManager.upsert(listing);
                    return ActionResult.success("&aListed " + itemName(item) + " for &6" + economyProvider.format(price) + "&a.");
                })
                .exceptionally(throwable -> {
                    schedulerAdapter.runEntity(player, () -> restoreItem(player, item));
                    plugin.getLogger().severe("Failed to save auction listing: " + throwable.getMessage());
                    return ActionResult.failure("&cFailed to create the auction. Your item was returned.");
                });
    }

    public CompletableFuture<ActionResult> purchaseAuction(Player buyer, UUID auctionId) {
        return purchaseAuction(buyer, auctionId, false);
    }

    public CompletableFuture<ActionResult> purchaseAuction(Player buyer, UUID auctionId, boolean fastBuy) {
        AuctionListing listing = auctionManager.findCached(auctionId);
        if (listing == null) {
            return CompletableFuture.completedFuture(ActionResult.failure("&cThat auction is no longer available."));
        }
        if (listing.seller().equals(buyer.getUniqueId())) {
            return CompletableFuture.completedFuture(ActionResult.failure("&cYou cannot buy your own auction."));
        }

        long now = System.currentTimeMillis();
        if (!listing.isActive(now)) {
            expireListingIfNeeded(listing);
            return CompletableFuture.completedFuture(ActionResult.failure("&cThat auction has expired."));
        }

        AtomicBoolean operationLock = operationLocks.computeIfAbsent(auctionId, ignored -> new AtomicBoolean());
        if (!operationLock.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ActionResult.failure("&cThat auction is already being processed."));
        }

        OfflinePlayer offlineBuyer = Bukkit.getOfflinePlayer(buyer.getUniqueId());
        if (!economyProvider.has(offlineBuyer, listing.price())) {
            operationLock.set(false);
            return CompletableFuture.completedFuture(ActionResult.failure("&cYou do not have enough money."));
        }

        EconomyResponse withdrawal = economyProvider.withdraw(offlineBuyer, listing.price());
        if (!withdrawal.transactionSuccess()) {
            operationLock.set(false);
            return CompletableFuture.completedFuture(ActionResult.failure("&cUnable to withdraw funds: " + withdrawal.errorMessage));
        }

        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.seller());
        EconomyResponse deposit = economyProvider.deposit(seller, listing.price());
        if (!deposit.transactionSuccess()) {
            economyProvider.deposit(offlineBuyer, listing.price());
            operationLock.set(false);
            return CompletableFuture.completedFuture(ActionResult.failure("&cUnable to pay the seller right now."));
        }

        AuctionListing soldListing = listing.asSold(buyer.getUniqueId(), now);
        auctionManager.upsert(soldListing);

        return repository.update(soldListing)
                .thenApply(ignored -> {
                    deliverItem(buyer, soldListing.item());
                    return ActionResult.success("&aPurchased " + itemName(soldListing.item()) + " for &6" + economyProvider.format(soldListing.price()) + "&a.");
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().severe("Failed to persist auction purchase: " + throwable.getMessage());
                    economyProvider.deposit(offlineBuyer, listing.price());
                    economyProvider.withdraw(seller, listing.price());
                    auctionManager.upsert(listing);
                    return ActionResult.failure("&cPurchase failed. Your money has been refunded.");
                })
                .whenComplete((result, throwable) -> {
                    operationLock.set(false);
                    operationLocks.remove(auctionId);
                });
    }

    public CompletableFuture<ActionResult> cancelAuction(Player seller, UUID auctionId) {
        AuctionListing listing = auctionManager.findCached(auctionId);
        if (listing == null || !listing.seller().equals(seller.getUniqueId())) {
            return CompletableFuture.completedFuture(ActionResult.failure("&cAuction not found."));
        }
        if (listing.status() != AuctionStatus.ACTIVE) {
            return CompletableFuture.completedFuture(ActionResult.failure("&cOnly active auctions can be cancelled."));
        }

        AuctionListing cancelled = listing.withStatus(AuctionStatus.CANCELLED).markSellerClaimed();
        auctionManager.upsert(cancelled);

        return repository.update(cancelled)
                .thenApply(ignored -> {
                    restoreItem(seller, cancelled.item());
                    return ActionResult.success("&aAuction cancelled and item returned.");
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().severe("Failed to persist cancellation: " + throwable.getMessage());
                    auctionManager.upsert(listing);
                    return ActionResult.failure("&cFailed to cancel auction. Please try again.");
                });
    }

    public CompletableFuture<ActionResult> collectSellerProceeds(Player seller, UUID auctionId) {
        AuctionListing listing = auctionManager.findCached(auctionId);
        if (listing == null || !listing.seller().equals(seller.getUniqueId())) {
            return CompletableFuture.completedFuture(ActionResult.failure("&cAuction not found."));
        }
        if (listing.sellerClaimed()) {
            return CompletableFuture.completedFuture(ActionResult.failure("&cThat auction has already been collected."));
        }

        if (listing.status() == AuctionStatus.SOLD && !listing.sellerClaimed()) {
            AuctionListing claimed = listing.markSellerClaimed();
            auctionManager.upsert(claimed);
            return repository.update(claimed)
                    .thenApply(ignored -> ActionResult.success("&aSale marked as collected. Payment was delivered through Vault at purchase time."))
                    .exceptionally(throwable -> {
                        auctionManager.upsert(listing);
                        return ActionResult.failure("&cUnable to update the collection state.");
                    });
        }

        if (listing.status() == AuctionStatus.EXPIRED || listing.status() == AuctionStatus.CANCELLED) {
            AuctionListing collected = listing.markSellerClaimed();
            auctionManager.upsert(collected);
            return repository.update(collected)
                    .thenApply(ignored -> {
                        restoreItem(seller, listing.item());
                        return ActionResult.success("&aReturned your unsold item.");
                    })
                    .exceptionally(throwable -> {
                        auctionManager.upsert(listing);
                        return ActionResult.failure("&cUnable to update the collection state. Please try again.");
                    });
        }

        return CompletableFuture.completedFuture(ActionResult.failure("&cNothing to collect for that auction."));
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

    private void restoreItem(Player player, ItemStack itemStack) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(itemStack.clone());
        leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private void deliverItem(Player player, ItemStack itemStack) {
        restoreItem(player, itemStack);
    }

    private String itemName(ItemStack itemStack) {
        if (itemStack.hasItemMeta() && itemStack.getItemMeta().hasDisplayName()) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(itemStack.getItemMeta().displayName());
        }
        return itemStack.getType().name().replace('_', ' ');
    }
}
