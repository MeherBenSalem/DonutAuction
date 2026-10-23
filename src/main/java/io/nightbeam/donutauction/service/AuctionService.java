package io.nightbeam.donutauction.service;

import io.nightbeam.donutauction.AuctionHousePlugin;
import io.nightbeam.donutauction.api.event.AuctionBidEvent;
import io.nightbeam.donutauction.api.event.AuctionCancelledEvent;
import io.nightbeam.donutauction.api.event.AuctionEndedEvent;
import io.nightbeam.donutauction.api.event.AuctionListedEvent;
import io.nightbeam.donutauction.api.event.AuctionPreBidEvent;
import io.nightbeam.donutauction.api.event.AuctionPreListEvent;
import io.nightbeam.donutauction.api.event.AuctionPrePurchaseEvent;
import io.nightbeam.donutauction.api.event.AuctionPurchasedEvent;
import io.nightbeam.donutauction.api.event.AuctionWonEvent;
import io.nightbeam.donutauction.economy.VaultEconomyProvider;
import io.nightbeam.donutauction.hook.DonutCoreHook;
import io.nightbeam.donutauction.integration.DiscordWebhook;
import io.nightbeam.donutauction.model.AuctionBrowseRequest;
import io.nightbeam.donutauction.model.AuctionFilterCategory;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionPage;
import io.nightbeam.donutauction.model.AuctionStatus;
import io.nightbeam.donutauction.model.Bid;
import io.nightbeam.donutauction.model.HistoryView;
import io.nightbeam.donutauction.model.ListingPriceValidationResult;
import io.nightbeam.donutauction.model.ListingType;
import io.nightbeam.donutauction.model.PendingSaleTransaction;
import io.nightbeam.donutauction.model.SaleDetails;
import io.nightbeam.donutauction.model.TransactionPage;
import io.nightbeam.donutauction.storage.AuctionRepository;
import io.nightbeam.donutauction.storage.DatabaseType;
import io.nightbeam.donutauction.sync.ListingSyncAction;
import io.nightbeam.donutauction.sync.ListingSyncBus;
import io.nightbeam.donutauction.util.InventorySpace;
import io.nightbeam.donutauction.util.ItemLoreApplier;
import io.nightbeam.donutauction.util.ItemNames;
import io.nightbeam.donutauction.util.PriceDisplay;
import io.nightbeam.donutauction.util.SchedulerAdapter;
import io.nightbeam.donutauction.util.Sounds;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Every trade. The rules that keep it dupe-safe:
 * <ul>
 *   <li>Each move of an item or money starts with a conditional row update ("claim") that exactly one
 *   caller on any server can win.</li>
 *   <li>Items are handed over only as whole stacks that fit; otherwise they wait in the owner's
 *   collection. Nothing is dropped on the ground.</li>
 *   <li>Inventories are touched only on the player's own thread (Folia-safe); money for players who
 *   may be offline moves on the global thread.</li>
 * </ul>
 */
public final class AuctionService {

    private final AuctionHousePlugin plugin;
    private final SchedulerAdapter schedulerAdapter;
    private final VaultEconomyProvider economyProvider;
    private final AuctionRepository repository;
    private final AuctionManager auctionManager;
    private final DonutCoreHook donutCoreHook;
    private final ListingSyncBus listingSyncBus;
    private final DatabaseType databaseType;
    private final PendingSaleRegistry pendingSaleRegistry;
    private final FeeCalculator fees;
    private final ItemRules rules;
    private final MarketService market;
    private final Notifier notifier;
    private final AuctionEvents events;
    private final TransactionLog transactionLog;
    private final DiscordWebhook webhook;
    private final Map<UUID, AtomicBoolean> operationLocks = new ConcurrentHashMap<>();
    private final AtomicLong lastSyncedAt = new AtomicLong();
    private final AtomicLong lastBroadcast = new AtomicLong();

    private SchedulerAdapter.CancellableTask expiryTask;
    private SchedulerAdapter.CancellableTask pollTask;
    private SchedulerAdapter.CancellableTask maintenanceTask;

    public AuctionService(
            AuctionHousePlugin plugin,
            SchedulerAdapter schedulerAdapter,
            VaultEconomyProvider economyProvider,
            AuctionRepository repository,
            AuctionManager auctionManager,
            DonutCoreHook donutCoreHook,
            ListingSyncBus listingSyncBus,
            DatabaseType databaseType
    ) {
        this.plugin = plugin;
        this.schedulerAdapter = schedulerAdapter;
        this.economyProvider = economyProvider;
        this.repository = repository;
        this.auctionManager = auctionManager;
        this.donutCoreHook = donutCoreHook;
        this.listingSyncBus = listingSyncBus;
        this.databaseType = databaseType;
        boolean debugPending = plugin.getConfig().getBoolean("debug.pending-sales", false);
        this.pendingSaleRegistry = new PendingSaleRegistry(plugin.getLogger(), debugPending);
        this.fees = new FeeCalculator(plugin::getConfig);
        this.rules = new ItemRules(plugin::getConfig);
        this.market = new MarketService(repository, auctionManager, plugin::getConfig, plugin.getLogger());
        this.notifier = new Notifier(plugin, repository, auctionManager, this::formatPrice);
        this.notifier.setCollector(this::deliverCollection);
        this.events = new AuctionEvents(schedulerAdapter.asyncExecutor(), plugin.getLogger());
        File dataFolder = plugin.getDataFolder();
        this.transactionLog = new TransactionLog(dataFolder == null ? null : new File(dataFolder, "logs").toPath(),
                () -> plugin.getConfig().getBoolean("transaction-log.enabled", true), plugin.getLogger());
        this.webhook = new DiscordWebhook(plugin::getConfig, plugin.getLogger());
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    public void initialize() {
        listingSyncBus.setRemoteHandler(this::applyRemote, auctionManager::remove);
        repository.initialize()
                .thenCompose(ignored -> repository.loadLive())
                .thenAccept(listings -> {
                    auctionManager.replaceAll(listings);
                    long maxUpdated = 0L;
                    for (AuctionListing listing : listings) {
                        maxUpdated = Math.max(maxUpdated, listing.updatedAt());
                    }
                    // Older settled rows are history; re-reading the last few minutes covers servers that were mid-change.
                    lastSyncedAt.set(Math.max(maxUpdated, System.currentTimeMillis() - 300_000L));
                    if (repository.unreadableRows() > 0) {
                        plugin.getLogger().warning(repository.unreadableRows() + " auction row(s) hold items this server cannot read"
                                + " (usually written by a newer Minecraft version). They are kept untouched; see /ah admin info.");
                    }
                    recoverUnsettledWins();
                    backfillItemIndex();
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().severe("Failed to initialize auction storage: " + throwable.getMessage());
                    throwable.printStackTrace();
                    return null;
                });

        long intervalSeconds = config().getLong("auction.expired-scan-interval-seconds", 60L);
        long intervalTicks = Math.max(20L, intervalSeconds * 20L);
        this.expiryTask = schedulerAdapter.runGlobalRepeating(this::scanAndExpireAuctions, intervalTicks, intervalTicks);

        if (databaseType == DatabaseType.MYSQL) {
            long pollSeconds = config().getLong("sync.poll-interval-seconds", 3L);
            if (pollSeconds > 0L) {
                long pollTicks = Math.max(20L, pollSeconds * 20L);
                this.pollTask = schedulerAdapter.runGlobalRepeating(this::pollRemoteUpdates, pollTicks, pollTicks);
            }
        }
        long hour = 20L * 60L * 60L;
        this.maintenanceTask = schedulerAdapter.runGlobalRepeating(this::purgeOldHistory, 20L * 120L, hour * 6L);
    }

    public void shutdown() {
        if (expiryTask != null) {
            expiryTask.cancel();
        }
        if (pollTask != null) {
            pollTask.cancel();
        }
        if (maintenanceTask != null) {
            maintenanceTask.cancel();
        }
        recoverPendingSalesOnShutdown();
        webhook.shutdown();
        transactionLog.shutdown();
        schedulerAdapter.shutdown();
    }

    // ---------------------------------------------------------------- selling

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
    public CompletableFuture<ActionResult> confirmPendingSale(Player player, UUID transactionId, int durationHours,
                                                              AuctionFilterCategory category) {
        PendingSaleRegistry.ClaimResult claim = pendingSaleRegistry.claim(transactionId, player.getUniqueId());
        if (!claim.success()) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.sell-confirmation-invalid", "&cThat sell confirmation is no longer valid.")));
        }
        PendingSaleTransaction transaction = claim.transaction();
        return createListing(player, transaction.item(), transaction.price(), durationHours, ListingType.BIN);
    }

    public CompletableFuture<ActionResult> confirmPendingSale(Player player, UUID transactionId, int durationHours,
                                                              AuctionFilterCategory category, double listingPrice) {
        return confirmPendingSale(player, transactionId, durationHours, listingPrice, ListingType.BIN);
    }

    public CompletableFuture<ActionResult> confirmPendingSale(Player player, UUID transactionId, int durationHours,
                                                              double listingPrice, ListingType type) {
        PendingSaleRegistry.ClaimResult claim = pendingSaleRegistry.claim(transactionId, player.getUniqueId());
        if (!claim.success()) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.sell-confirmation-invalid", "&cThat sell confirmation is no longer valid.")));
        }
        return createListing(player, claim.transaction().item(), listingPrice, durationHours, type);
    }

    public PendingSaleRegistry pendingSaleRegistry() {
        return pendingSaleRegistry;
    }

    /** Everything that must hold before an item leaves the player's hand. Empty means "go ahead". */
    public Optional<String> checkCanSell(Player player, ItemStack item) {
        Optional<ItemRules.Refusal> refusal = rules.checkSeller(player).or(() -> rules.checkItem(player, item));
        return refusal.map(r -> msg(r.messageKey(), r.fallback(), r.placeholders()));
    }

    public CompletableFuture<ActionResult> createAuction(Player player, ItemStack itemInHand, double price) {
        long durationHours = config().getLong("auction.listing-duration-hours", 48L);
        return createAuction(player, itemInHand, price, (int) durationHours, AuctionFilterCategory.ALL);
    }

    public CompletableFuture<ActionResult> createAuction(Player player, ItemStack itemInHand, double price, int durationHours,
                                                         AuctionFilterCategory category) {
        if (itemInHand == null || itemInHand.getType() == Material.AIR) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.hold-item", "&cHold the item you want to list.")));
        }
        removeHeldItem(player);
        return createAuctionFromItem(player, itemInHand, price, durationHours, category);
    }

    public CompletableFuture<ActionResult> createAuctionFromItem(Player player, ItemStack item, double price, int durationHours,
                                                                 AuctionFilterCategory category) {
        return createListing(player, item, price, durationHours, ListingType.BIN);
    }

    /**
     * Lists an item the player no longer holds (it is owned by this call). On any refusal or failure the
     * item and any listing fee go back to the player.
     */
    public CompletableFuture<ActionResult> createListing(Player player, ItemStack item, double price, int durationHours,
                                                         ListingType requestedType) {
        if (item == null || item.getType() == Material.AIR) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.invalid-item", "&cThe item is invalid.")));
        }

        // Always work from a defensive clone so NBT/components are preserved independently of caller state.
        ItemStack listingItem = item.clone();
        ListingType type = requestedType == ListingType.BID && biddingAllowed(player) ? ListingType.BID : ListingType.BIN;

        double minPrice = rules.minPrice(listingItem);
        double maxPrice = rules.maxPrice(listingItem);
        ListingPriceValidationResult validationResult = ListingPriceValidationResult.validate(price, minPrice, maxPrice);
        if (validationResult == ListingPriceValidationResult.BELOW_MINIMUM) {
            restoreItem(player, listingItem);
            String configFallback = config().getString(
                    "messages.price-below-min", "&cMinimum auction price is &6%min_price%&c.");
            return CompletableFuture.completedFuture(ActionResult.failure(msg(
                    "service.price-below-min", configFallback, "min_price", economyProvider.format(Math.max(0.0D, minPrice)))));
        }
        if (validationResult == ListingPriceValidationResult.INVALID_OR_ABOVE_MAX) {
            restoreItem(player, listingItem);
            if (Double.isFinite(price) && price > maxPrice) {
                return CompletableFuture.completedFuture(ActionResult.failure(msg(
                        "service.price-above-max", "&cThe highest price for that item is &6%max_price%&c.",
                        "max_price", economyProvider.format(maxPrice))));
            }
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.price-invalid", "&cPrice must be greater than 0 and below the configured limit.")));
        }
        Optional<ItemRules.Refusal> refusal = rules.checkItem(player, listingItem);
        if (refusal.isPresent()) {
            restoreItem(player, listingItem);
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg(refusal.get().messageKey(), refusal.get().fallback(), refusal.get().placeholders())));
        }
        int hours = clampDuration(durationHours);
        AuctionPreListEvent preList = new AuctionPreListEvent(player, listingItem, price, type, hours);
        if (!events.callCancellable(preList)) {
            restoreItem(player, listingItem);
            return CompletableFuture.completedFuture(ActionResult.failure(preList.getCancelMessage() != null
                    ? preList.getCancelMessage() : msg("service.listing-blocked", "&cYou cannot list that item right now.")));
        }

        double fee = fees.listingFee(player, price);
        if (fee > 0.0D) {
            OfflinePlayer payer = Bukkit.getOfflinePlayer(player.getUniqueId());
            if (!economyProvider.has(payer, fee) || !economyProvider.withdraw(payer, fee).transactionSuccess()) {
                restoreItem(player, listingItem);
                return CompletableFuture.completedFuture(ActionResult.failure(msg(
                        "service.fee-insufficient", "&cListing costs a fee of &6%fee%&c.", "fee", economyProvider.format(fee))));
            }
        }

        long now = System.currentTimeMillis();
        AuctionListing listing = new AuctionListing(
                UUID.randomUUID(), listingItem.clone(), player.getUniqueId(), price, now, now + hours * 3_600_000L,
                AuctionStatus.ACTIVE, null, 0L, false, now, type, Bid.NONE, player.getName(), 0.0D, true, fees.taxRate(player));
        rules.recordListing(player.getUniqueId());

        return repository.save(listing)
                .thenApply(ignored -> {
                    auctionManager.upsert(listing);
                    listingSyncBus.publish(listing.auctionId(), ListingSyncAction.UPSERT);
                    noteSynced(listing.updatedAt());
                    events.callAsync(new AuctionListedEvent(listing));
                    log("LISTED", listing, "price", listing.price(), "fee", fee, "type", type);
                    webhook.post(new DiscordWebhook.Post(DiscordWebhook.Kind.LISTED, listing, formatPrice(price), price,
                            listing.sellerName(), null, null));
                    broadcast(listing);
                    String key = type == ListingType.BID ? "service.listed-auction" : "service.listed";
                    String fallback = type == ListingType.BID
                            ? "&aYour auction for %item% &astarted at &6%price%&a." : "&aListed %item% for &6%price%&a.";
                    String message = msg(key, fallback, "item", itemName(listingItem), "amount",
                            String.valueOf(listingItem.getAmount()), "price", economyProvider.format(price));
                    if (fee > 0.0D) {
                        message += msg("service.fee-paid", " &7(fee %fee%)", "fee", economyProvider.format(fee));
                    }
                    return ActionResult.success(message);
                })
                .exceptionally(throwable -> {
                    schedulerAdapter.runEntity(player, () -> restoreItem(player, listingItem));
                    if (fee > 0.0D) {
                        schedulerAdapter.runGlobal(() -> economyProvider.deposit(Bukkit.getOfflinePlayer(player.getUniqueId()), fee));
                    }
                    plugin.getLogger().severe("Failed to save auction listing: " + throwable.getMessage());
                    return ActionResult.failure(msg(
                            "service.create-failed",
                            "&cFailed to create the auction. Your item was returned."));
                });
    }

    /**
     * A listing created by another plugin (API) for a player or the server. No fee, limit or blacklist
     * applies; the caller has already taken the item out of any inventory.
     */
    public CompletableFuture<AuctionListing> createServerListing(UUID seller, String sellerName, ItemStack item, double price,
                                                                 long durationMillis, ListingType type) {
        Objects.requireNonNull(seller, "seller");
        if (item == null || item.getType().isAir() || !Double.isFinite(price) || price <= 0.0D) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("A listing needs an item and a positive price"));
        }
        long now = System.currentTimeMillis();
        AuctionListing listing = new AuctionListing(UUID.randomUUID(), item.clone(), seller, price, now,
                now + Math.max(60_000L, durationMillis), AuctionStatus.ACTIVE, null, 0L, false, now,
                type == null ? ListingType.BIN : type, Bid.NONE, sellerName, 0.0D, true, -1.0D);
        return repository.save(listing).thenApply(ignored -> {
            auctionManager.upsert(listing);
            listingSyncBus.publish(listing.auctionId(), ListingSyncAction.UPSERT);
            noteSynced(listing.updatedAt());
            events.callAsync(new AuctionListedEvent(listing));
            log("LISTED", listing, "price", price, "source", "api");
            webhook.post(new DiscordWebhook.Post(DiscordWebhook.Kind.LISTED, listing, formatPrice(price), price, sellerName, null, null));
            return listing;
        });
    }

    private boolean biddingAllowed(Player player) {
        if (!config().getBoolean("bidding.enabled", true)) return false;
        String permission = config().getString("bidding.permission", "donutauction.sell.auction");
        return permission == null || permission.isBlank() || player == null || player.hasPermission(permission);
    }

    public boolean biddingEnabled() {
        return config().getBoolean("bidding.enabled", true);
    }

    public boolean canStartAuctions(Player player) {
        return biddingAllowed(player);
    }

    public List<Integer> durationOptions() {
        List<Integer> configured = config().getIntegerList("auction.durations");
        List<Integer> options = new ArrayList<>();
        for (Integer hours : configured) if (hours != null && hours > 0 && !options.contains(hours)) options.add(hours);
        if (options.isEmpty()) options.addAll(List.of(6, 12, 24, 48, 72, 168));
        return options.size() > 7 ? options.subList(0, 7) : options;
    }

    private int clampDuration(int hours) {
        int max = Math.max(1, config().getInt("auction.max-duration-hours",
                durationOptions().stream().mapToInt(Integer::intValue).max().orElse(168)));
        return Math.max(1, Math.min(max, hours));
    }

    private void broadcast(AuctionListing listing) {
        if (!config().getBoolean("broadcast.new-listings", false)) return;
        if (listing.price() < config().getDouble("broadcast.min-price", 0.0D)) return;
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0L, config().getLong("broadcast.cooldown-seconds", 10L)) * 1000L;
        long last = lastBroadcast.get();
        if (now - last < cooldown || !lastBroadcast.compareAndSet(last, now)) return;
        String key = listing.isBid() ? "broadcast.new-auction" : "broadcast.new-listing";
        String message = msg(key, listing.isBid()
                        ? "&e%seller% &7started an auction for %item% &7at &6%price%&7. &e/ah"
                        : "&e%seller% &7listed %item% &7for &6%price%&7. &e/ah",
                "seller", listing.sellerName() == null ? "?" : listing.sellerName(), "item", itemName(listing.item()),
                "amount", String.valueOf(listing.item().getAmount()), "price", formatPrice(listing.price()));
        schedulerAdapter.runGlobal(() -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("donutauction.broadcast.receive")) plugin.messages().send(online, message);
            }
        });
    }

    // ---------------------------------------------------------------- browsing

    public AuctionPage browse(AuctionBrowseRequest request) {
        return auctionManager.browse(request, System.currentTimeMillis());
    }

    /** Active listings and unreclaimed items of a seller. */
    public List<AuctionListing> getPlayerAuctions(UUID playerId) {
        return auctionManager.sellerListings(playerId);
    }

    /** Items bought or won that wait for the player. */
    public List<AuctionListing> getCollection(UUID playerId) {
        return auctionManager.buyerCollection(playerId);
    }

    public int getPlayerActiveAuctionCount(UUID playerId) {
        return (int) auctionManager.sellerListings(playerId).stream()
                .filter(listing -> listing.status() == AuctionStatus.ACTIVE)
                .count();
    }

    public Optional<AuctionListing> findListing(UUID auctionId) {
        return Optional.ofNullable(auctionManager.findCached(auctionId));
    }

    // ---------------------------------------------------------------- buying

    public CompletableFuture<ActionResult> purchaseAuction(Player buyer, UUID auctionId) {
        return purchaseAuction(buyer, auctionId, false);
    }

    public CompletableFuture<ActionResult> purchaseAuction(Player buyer, UUID auctionId, boolean fastBuy) {
        AuctionListing listing = auctionManager.findCached(auctionId);
        if (listing == null || listing.status() != AuctionStatus.ACTIVE) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.auction-unavailable", "&cThat auction is no longer available.")));
        }
        if (listing.seller().equals(buyer.getUniqueId())) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.cannot-buy-own", "&cYou cannot buy your own auction.")));
        }
        if (listing.isBid()) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.place-a-bid", "&cThat item is up for auction. Place a bid instead.")));
        }

        long now = System.currentTimeMillis();
        if (!listing.isActive(now)) {
            expireListingIfNeeded(listing);
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.auction-expired", "&cThat auction has expired.")));
        }
        AuctionPrePurchaseEvent prePurchase = new AuctionPrePurchaseEvent(buyer, listing);
        if (!events.callCancellable(prePurchase)) {
            return CompletableFuture.completedFuture(ActionResult.failure(prePurchase.getCancelMessage() != null
                    ? prePurchase.getCancelMessage() : msg("service.purchase-blocked", "&cYou cannot buy that right now.")));
        }

        AtomicBoolean operationLock = operationLocks.computeIfAbsent(auctionId, ignored -> new AtomicBoolean());
        if (!operationLock.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.auction-processing", "&cThat auction is already being processed.")));
        }

        UUID buyerId = buyer.getUniqueId();
        return repository.claimSold(auctionId, buyerId, now)
                .thenCompose(claimed -> {
                    if (!claimed) {
                        return refreshListingFromStorage(auctionId)
                                .thenApply(ignored -> ActionResult.failure(
                                        msg("service.auction-unavailable", "&cThat auction is no longer available.")));
                    }
                    return settleClaimedPurchase(buyer, listing, now);
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().severe("Failed to claim auction purchase: " + throwable.getMessage());
                    return ActionResult.failure(msg(
                            "service.purchase-failed",
                            "&cPurchase failed. Your money has been refunded."));
                })
                .whenComplete((result, throwable) -> releaseOperationLock(auctionId, operationLock));
    }

    private CompletableFuture<ActionResult> settleClaimedPurchase(Player buyer, AuctionListing listing, long soldTime) {
        CompletableFuture<ActionResult> settled = new CompletableFuture<>();
        UUID buyerId = buyer.getUniqueId();
        Runnable retired = () -> {
            repository.releaseClaim(listing.auctionId(), buyerId, System.currentTimeMillis())
                    .thenAccept(released -> {
                        if (released) {
                            listingSyncBus.publish(listing.auctionId(), ListingSyncAction.UPSERT);
                        }
                    });
            settled.complete(ActionResult.failure(msg("service.purchase-failed", "&cPurchase failed. Your money has been refunded.")));
        };
        if (!buyer.isOnline()) {
            retired.run();
            return settled;
        }
        schedulerAdapter.runEntity(buyer, () -> {
            boolean deliveryCompleted = false;
            try {
                if (!buyer.isOnline()) {
                    releaseClaimAndNotify(listing.auctionId(), buyerId);
                    settled.complete(ActionResult.failure(
                            msg("service.purchase-failed", "&cPurchase failed. Your money has been refunded.")));
                    return;
                }
                double price = listing.price();
                OfflinePlayer offlineBuyer = Bukkit.getOfflinePlayer(buyerId);
                if (!economyProvider.has(offlineBuyer, price)) {
                    releaseClaimAndNotify(listing.auctionId(), buyerId);
                    settled.complete(ActionResult.failure(
                            msg("service.insufficient-funds", "&cYou do not have enough money.")));
                    return;
                }
                EconomyResponse withdrawal = economyProvider.withdraw(offlineBuyer, price);
                if (!withdrawal.transactionSuccess()) {
                    releaseClaimAndNotify(listing.auctionId(), buyerId);
                    settled.complete(ActionResult.failure(msg(
                            "service.withdraw-failed",
                            "&cUnable to withdraw funds: %error%",
                            "error", String.valueOf(withdrawal.errorMessage))));
                    return;
                }
                double tax = fees.tax(price, listing.taxRate());
                double net = price - tax;
                OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.seller());
                EconomyResponse deposit = economyProvider.deposit(seller, net);
                if (!deposit.transactionSuccess()) {
                    economyProvider.deposit(offlineBuyer, price);
                    releaseClaimAndNotify(listing.auctionId(), buyerId);
                    settled.complete(ActionResult.failure(
                            msg("service.pay-seller-failed", "&cUnable to pay the seller right now.")));
                    return;
                }
                depositTax(tax);
                boolean fits = InventorySpace.fits(buyer.getInventory(), listing.item());
                AuctionListing soldListing = listing.asSold(buyerId, soldTime, price, tax).withBuyerClaimed(fits, soldTime);
                auctionManager.upsert(soldListing);
                noteSynced(soldListing.updatedAt());
                listingSyncBus.publish(soldListing.auctionId(), ListingSyncAction.UPSERT);
                if (fits) {
                    deliverItem(buyer, soldListing.item());
                }
                deliveryCompleted = true;
                // Best-effort bookkeeping is separate from settlement: failures never refund,
                // release a sold row, re-deliver an item, or change the successful result.
                recordCompletedPurchase(buyer, soldListing, tax, fits);
                afterSale(soldListing, buyer.getName(), net, false);
                Sounds.play(buyer, config(), "purchase");
                settled.complete(ActionResult.success(fits
                        ? msg("service.purchased", "&aPurchased %item% for &6%price%&a.",
                        "item", itemName(soldListing.item()), "amount", String.valueOf(soldListing.item().getAmount()),
                        "price", economyProvider.format(price))
                        : msg("service.purchased-to-collection",
                        "&aPurchased %item% for &6%price%&a. Your inventory is full, so it waits in &e/ah collect&a.",
                        "item", itemName(soldListing.item()), "amount", String.valueOf(soldListing.item().getAmount()),
                        "price", economyProvider.format(price))));
            } catch (Exception exception) {
                if (deliveryCompleted) {
                    plugin.getLogger().warning("Purchase settled, but its confirmation could not be rendered for "
                            + listing.auctionId() + ": " + exception.getMessage());
                    settled.complete(ActionResult.success(msg("service.purchase-completed", "&aPurchase completed.")));
                    return;
                }
                plugin.getLogger().severe("Failed to settle auction purchase: " + exception.getMessage());
                releaseClaimAndNotify(listing.auctionId(), buyerId);
                settled.complete(ActionResult.failure(msg(
                        "service.purchase-failed",
                        "&cPurchase failed. Your money has been refunded.")));
            }
        }, retired);
        return settled;
    }

    /** Notifications, events, webhooks and logs for a completed sale. Never throws. */
    private void afterSale(AuctionListing sold, String buyerName, double net, boolean won) {
        try {
            notifier.sold(sold, buyerName, net);
            if (won) {
                notifier.won(sold);
                events.callAsync(new AuctionWonEvent(sold, sold.buyer(), sold.price(), sold.tax()));
            } else {
                events.callAsync(new AuctionPurchasedEvent(sold, sold.buyer(), sold.price(), sold.tax()));
            }
            log(won ? "WON" : "SOLD", sold, "buyer", buyerName + "(" + sold.buyer() + ")", "price", sold.price(), "tax", sold.tax());
            webhook.post(new DiscordWebhook.Post(won ? DiscordWebhook.Kind.WON : DiscordWebhook.Kind.SOLD, sold,
                    formatPrice(sold.price()), sold.price(), sold.sellerName(), buyerName,
                    sold.buyer() == null ? null : sold.buyer().toString()));
            market.invalidate(sold.item().getType().name());
            market.invalidatePlayer(sold.seller());
            market.invalidatePlayer(sold.buyer());
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Sale " + sold.auctionId() + " completed, but a notification failed: " + exception.getMessage());
        }
    }

    private void depositTax(double tax) {
        String account = fees.taxAccount();
        if (tax <= 0.0D || account.isBlank()) return;
        try {
            OfflinePlayer target;
            try {
                target = Bukkit.getOfflinePlayer(UUID.fromString(account));
            } catch (IllegalArgumentException notUuid) {
                target = Bukkit.getOfflinePlayer(account);
            }
            if (!economyProvider.deposit(target, tax).transactionSuccess()) {
                plugin.getLogger().warning("Could not pay " + tax + " sales tax to " + account + ".");
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not pay sales tax to " + account + ": " + exception.getMessage());
        }
    }

    private void recordCompletedPurchase(Player buyer, AuctionListing sold, double tax, boolean delivered) {
        UUID auctionId = sold.auctionId();
        try {
            repository.markPurchaseCompleted(auctionId, buyer.getUniqueId(), new SaleDetails(tax, delivered, buyer.getName()))
                    .whenComplete((updated, failure) -> {
                        if (failure != null || !Boolean.TRUE.equals(updated)) {
                            plugin.getLogger().warning("Purchase settled, but history completion could not be saved for "
                                    + auctionId + ". Payment and item delivery remain completed.");
                            if (!delivered) handOverAfterBookkeepingFailure(buyer, sold);
                            return;
                        }
                        // The sale now counts in both traders' statistics.
                        market.invalidatePlayer(sold.seller());
                        market.invalidatePlayer(buyer.getUniqueId());
                    });
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Purchase settled, but history completion could not be queued for " + auctionId);
            if (!delivered) handOverAfterBookkeepingFailure(buyer, sold);
        }
    }

    /** The collection entry could not be saved: hand the item over now rather than lose it. */
    private void handOverAfterBookkeepingFailure(Player buyer, AuctionListing sold) {
        auctionManager.remove(sold.auctionId());
        schedulerAdapter.runEntity(buyer, () -> restoreItem(buyer, sold.item()));
    }

    // ---------------------------------------------------------------- bidding

    /** The lowest amount the next bid may be. */
    public double minimumBid(AuctionListing listing) {
        if (!listing.bid().present()) return listing.price();
        double current = listing.bid().amount();
        double step = Math.max(Math.max(0.01D, config().getDouble("bidding.min-increment", 1.0D)),
                current * Math.max(0.0D, config().getDouble("bidding.min-increment-percent", 5.0D)) / 100.0D);
        return FeeCalculator.money(current + step);
    }

    public CompletableFuture<ActionResult> placeBid(Player bidder, UUID auctionId, double amount) {
        AuctionListing listing = auctionManager.findCached(auctionId);
        long now = System.currentTimeMillis();
        if (listing == null || !listing.isBid() || !listing.isActive(now)) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.auction-unavailable", "&cThat auction is no longer available.")));
        }
        UUID bidderId = bidder.getUniqueId();
        if (listing.seller().equals(bidderId)) {
            return CompletableFuture.completedFuture(ActionResult.failure(msg("service.cannot-bid-own", "&cYou cannot bid on your own auction.")));
        }
        if (bidderId.equals(listing.bid().bidder())) {
            return CompletableFuture.completedFuture(ActionResult.failure(msg("service.already-leading", "&eYou already have the highest bid.")));
        }
        double minimum = minimumBid(listing);
        if (!Double.isFinite(amount) || amount + 1e-9 < minimum) {
            return CompletableFuture.completedFuture(ActionResult.failure(msg("service.bid-too-low",
                    "&cThe next bid must be at least &6%min_bid%&c.", "min_bid", economyProvider.format(minimum))));
        }
        if (amount > rules.maxPrice(listing.item())) {
            return CompletableFuture.completedFuture(ActionResult.failure(msg("service.price-above-max",
                    "&cThe highest price for that item is &6%max_price%&c.", "max_price", economyProvider.format(rules.maxPrice(listing.item())))));
        }
        AuctionPreBidEvent preBid = new AuctionPreBidEvent(bidder, listing, amount);
        if (!events.callCancellable(preBid)) {
            return CompletableFuture.completedFuture(ActionResult.failure(preBid.getCancelMessage() != null
                    ? preBid.getCancelMessage() : msg("service.bid-blocked", "&cYou cannot bid on that right now.")));
        }
        AtomicBoolean operationLock = operationLocks.computeIfAbsent(auctionId, ignored -> new AtomicBoolean());
        if (!operationLock.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.auction-processing", "&cThat auction is already being processed.")));
        }
        OfflinePlayer payer = Bukkit.getOfflinePlayer(bidderId);
        // The bid is held in escrow first. It goes back if the bid does not land.
        if (!economyProvider.has(payer, amount) || !economyProvider.withdraw(payer, amount).transactionSuccess()) {
            releaseOperationLock(auctionId, operationLock);
            return CompletableFuture.completedFuture(ActionResult.failure(msg("service.insufficient-funds", "&cYou do not have enough money.")));
        }
        Bid previous = listing.bid();
        long antiSnipe = Math.max(0L, config().getLong("bidding.anti-snipe-seconds", 30L)) * 1000L;
        long expiration = listing.expirationTime() - now < antiSnipe ? now + antiSnipe : listing.expirationTime();
        String bidderName = bidder.getName();
        CompletableFuture<ActionResult> result = new CompletableFuture<>();
        repository.placeBid(auctionId, previous.count(), amount, bidderId, bidderName, expiration, now)
                .exceptionallyCompose(failure -> {
                    // The outcome is unknown: read the row back to learn whether the bid landed.
                    plugin.getLogger().warning("Bid on " + auctionId + " failed to save (" + failure.getMessage() + "); checking.");
                    return repository.findById(auctionId).thenApply(row -> row
                            .map(current -> bidderId.equals(current.bid().bidder()) && current.bid().count() == previous.count() + 1)
                            .orElse(false));
                })
                .whenComplete((landed, failure) -> {
                    releaseOperationLock(auctionId, operationLock);
                    if (failure != null || !Boolean.TRUE.equals(landed)) {
                        if (failure != null) {
                            plugin.getLogger().severe("Bid on " + auctionId + " by " + bidderName + " could not be confirmed ("
                                    + failure.getMessage() + "); the " + amount + " held was refunded. Check this auction.");
                        }
                        schedulerAdapter.runGlobal(() -> pay(bidderId, amount, "bid " + auctionId));
                        refreshListingFromStorage(auctionId);
                        result.complete(ActionResult.failure(msg("service.bid-outdated",
                                "&cSomeone bid first. Your money was returned; check the new price and try again.")));
                        return;
                    }
                    AuctionListing updated = listing.withBid(new Bid(amount, bidderId, bidderName, previous.count() + 1), expiration, now);
                    auctionManager.upsert(updated);
                    noteSynced(now);
                    listingSyncBus.publish(auctionId, ListingSyncAction.UPSERT);
                    if (previous.present()) {
                        schedulerAdapter.runGlobal(() -> pay(previous.bidder(), previous.amount(), "outbid on " + auctionId));
                        notifier.outbid(previous.bidder(), updated, amount, previous.amount());
                    }
                    notifier.newBid(updated, bidderName, amount);
                    events.callAsync(new AuctionBidEvent(updated, bidderId, amount, previous.bidder()));
                    log("BID", updated, "bidder", bidderName + "(" + bidderId + ")", "amount", amount,
                            "previous", previous.present() ? previous.bidderName() + "(" + previous.amount() + ")" : "none");
                    webhook.post(new DiscordWebhook.Post(DiscordWebhook.Kind.BID, updated, formatPrice(amount), amount,
                            updated.sellerName(), bidderName, bidderId.toString()));
                    String message = msg("service.bid-placed", "&aYou bid &6%bid% &aon %item%&a. You are the highest bidder.",
                            "bid", economyProvider.format(amount), "item", itemName(updated.item()));
                    if (expiration != listing.expirationTime()) {
                        message += msg("service.bid-extended", " &7(The auction was extended.)");
                    }
                    result.complete(ActionResult.success(message));
                });
        return result;
    }

    private void pay(UUID player, double amount, String reason) {
        if (player == null || amount <= 0.0D) return;
        try {
            if (!economyProvider.deposit(Bukkit.getOfflinePlayer(player), amount).transactionSuccess()) {
                plugin.getLogger().severe("Could not pay " + amount + " to " + player + " (" + reason + "). Pay it manually.");
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Could not pay " + amount + " to " + player + " (" + reason + "): " + exception.getMessage());
        }
    }

    // ---------------------------------------------------------------- seller actions

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
        if (listing.isBid() && listing.bid().present()) {
            releaseOperationLock(auctionId, operationLock);
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.cannot-cancel-bids", "&cAn auction with bids cannot be cancelled.")));
        }

        long now = System.currentTimeMillis();
        boolean fits = InventorySpace.fits(seller.getInventory(), listing.item());
        return repository.claimCancelled(auctionId, now, fits)
                .thenCompose(claimed -> {
                    if (!claimed) {
                        return refreshListingFromStorage(auctionId)
                                .thenApply(ignored -> ActionResult.failure(
                                        msg("service.auction-unavailable", "&cThat auction is no longer available.")));
                    }
                    AuctionListing cancelled = listing.withStatus(AuctionStatus.CANCELLED).withUpdatedAt(now);
                    if (fits) cancelled = cancelled.markSellerClaimed(now);
                    auctionManager.upsert(cancelled);
                    noteSynced(now);
                    listingSyncBus.publish(auctionId, ListingSyncAction.UPSERT);
                    if (fits) {
                        schedulerAdapter.runEntity(seller, () -> restoreItem(seller, listing.item()));
                    }
                    events.callAsync(new AuctionCancelledEvent(cancelled, false, seller.getName()));
                    log("CANCELLED", cancelled, "by", seller.getName());
                    return CompletableFuture.completedFuture(ActionResult.success(fits
                            ? msg("service.cancelled", "&aAuction cancelled and item returned.")
                            : msg("service.cancelled-to-collection", "&aAuction cancelled. Your inventory is full, so the item waits in &e/ah collect&a.")));
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().severe("Failed to persist cancellation: " + throwable.getMessage());
                    return ActionResult.failure(msg(
                            "service.cancel-failed",
                            "&cFailed to cancel auction. Please try again."));
                })
                .whenComplete((result, throwable) -> releaseOperationLock(auctionId, operationLock));
    }

    /** Kept for 1.x callers: expired or cancelled items are reclaimed. Sold items no longer need collecting. */
    public CompletableFuture<ActionResult> collectSellerProceeds(Player seller, UUID auctionId) {
        return reclaim(seller, auctionId);
    }

    /** Takes back an expired or cancelled item, if all of it fits. */
    public CompletableFuture<ActionResult> reclaim(Player seller, UUID auctionId) {
        AuctionListing listing = auctionManager.findCached(auctionId);
        if (listing == null || !listing.seller().equals(seller.getUniqueId())) {
            return CompletableFuture.completedFuture(ActionResult.failure(msg("service.auction-not-found", "&cAuction not found.")));
        }
        if (!listing.isReclaimable()) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.nothing-to-collect", "&cNothing to collect for that auction.")));
        }
        long now = System.currentTimeMillis();
        return handOver(seller, listing, () -> repository.claimSellerReturn(auctionId, seller.getUniqueId(), now),
                listing.markSellerClaimed(now), "RECLAIMED",
                msg("service.item-returned", "&aReturned your unsold item."));
    }

    /** Takes a bought or won item out of the collection, if all of it fits. */
    public CompletableFuture<ActionResult> claimDelivery(Player buyer, UUID auctionId) {
        AuctionListing listing = auctionManager.findCached(auctionId);
        if (listing == null || !listing.awaitsBuyer() || !buyer.getUniqueId().equals(listing.buyer())) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.nothing-to-collect", "&cNothing to collect for that auction.")));
        }
        long now = System.currentTimeMillis();
        return handOver(buyer, listing, () -> repository.claimBuyerDelivery(auctionId, buyer.getUniqueId(), now),
                listing.withBuyerClaimed(true, now), "COLLECTED",
                msg("service.delivered", "&aCollected %item%&a.", "item", itemName(listing.item())));
    }

    /**
     * Gives a stored item to its owner: checks that all of it fits (on the player's thread), wins the row
     * claim, then hands it over. If the player leaves in between, the claim is undone so nothing is lost.
     */
    private CompletableFuture<ActionResult> handOver(Player player, AuctionListing listing,
                                                     java.util.function.Supplier<CompletableFuture<Boolean>> claim,
                                                     AuctionListing after, String action, String success) {
        if (!InventorySpace.fits(player.getInventory(), listing.item())) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.inventory-full", "&cMake room in your inventory first.")));
        }
        UUID auctionId = listing.auctionId();
        AtomicBoolean operationLock = operationLocks.computeIfAbsent(auctionId, ignored -> new AtomicBoolean());
        if (!operationLock.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.auction-processing", "&cThat auction is already being processed.")));
        }
        CompletableFuture<ActionResult> done = new CompletableFuture<>();
        claim.get().whenComplete((claimed, failure) -> {
            releaseOperationLock(auctionId, operationLock);
            if (failure != null || !Boolean.TRUE.equals(claimed)) {
                if (failure != null) plugin.getLogger().warning("Could not hand over " + auctionId + ": " + failure.getMessage());
                refreshListingFromStorage(auctionId);
                done.complete(ActionResult.failure(msg("service.nothing-to-collect", "&cNothing to collect for that auction.")));
                return;
            }
            auctionManager.upsert(after);
            noteSynced(after.updatedAt());
            listingSyncBus.publish(auctionId, ListingSyncAction.UPSERT);
            Runnable undo = () -> {
                // The owner left before the item reached them: put it back where it was.
                AuctionListing restored = listing.withUpdatedAt(System.currentTimeMillis());
                repository.update(restored).thenRun(() -> {
                    auctionManager.upsert(restored);
                    listingSyncBus.publish(auctionId, ListingSyncAction.UPSERT);
                });
                done.complete(ActionResult.failure(msg("service.nothing-to-collect", "&cNothing to collect for that auction.")));
            };
            schedulerAdapter.runEntity(player, () -> {
                if (!player.isOnline()) {
                    undo.run();
                    return;
                }
                restoreItem(player, listing.item());
                log(action, listing, "by", player.getName());
                done.complete(ActionResult.success(success));
            }, undo);
        });
        return done;
    }

    /** Reclaims and collects as many waiting items as fit, one after another. */
    public CompletableFuture<Integer> collectAll(Player player) {
        List<AuctionListing> waiting = new ArrayList<>(auctionManager.buyerCollection(player.getUniqueId()));
        auctionManager.sellerListings(player.getUniqueId()).stream().filter(AuctionListing::isReclaimable).forEach(waiting::add);
        AtomicInteger collected = new AtomicInteger();
        CompletableFuture<Integer> done = new CompletableFuture<>();
        collectNext(player, waiting, 0, collected, done);
        return done;
    }

    private void collectNext(Player player, List<AuctionListing> waiting, int index, AtomicInteger collected,
                             CompletableFuture<Integer> done) {
        if (index >= waiting.size() || !player.isOnline()) {
            done.complete(collected.get());
            return;
        }
        AuctionListing listing = waiting.get(index);
        if (!InventorySpace.fits(player.getInventory(), listing.item())) {
            done.complete(collected.get());
            return;
        }
        CompletableFuture<ActionResult> step = listing.awaitsBuyer()
                ? claimDelivery(player, listing.auctionId()) : reclaim(player, listing.auctionId());
        step.whenComplete((result, failure) -> schedulerAdapter.runEntity(player, () -> {
            if (result != null && result.success()) collected.incrementAndGet();
            collectNext(player, waiting, index + 1, collected, done);
        }, () -> done.complete(collected.get())));
    }

    /** Hands won or bought items that wait in the collection to an online player, as far as they fit. */
    public void deliverCollection(Player player) {
        if (auctionManager.buyerCollection(player.getUniqueId()).isEmpty()) return;
        List<AuctionListing> waiting = new ArrayList<>(auctionManager.buyerCollection(player.getUniqueId()));
        AtomicInteger collected = new AtomicInteger();
        CompletableFuture<Integer> done = new CompletableFuture<>();
        collectNext(player, waiting, 0, collected, done);
        done.thenAccept(count -> schedulerAdapter.runEntity(player, () -> {
            int left = auctionManager.buyerCollection(player.getUniqueId()).size();
            if (count > 0) {
                plugin.messages().sendFormatted(player, "notify.delivered", "&a%count% item(s) from the auction house were added to your inventory.",
                        "count", String.valueOf(count));
            }
            if (left > 0) {
                plugin.messages().sendFormatted(player, "notify.waiting", "&eYou have &6%count% &eitem(s) waiting in &6/ah collect&e.",
                        "count", String.valueOf(left));
            }
        }));
    }

    /** Puts an expired or cancelled item back on sale at the same price, for a fresh duration. */
    public CompletableFuture<ActionResult> relist(Player seller, UUID auctionId, int durationHours, AuctionLimitService limits) {
        AuctionListing listing = auctionManager.findCached(auctionId);
        if (listing == null || !listing.seller().equals(seller.getUniqueId()) || !listing.isReclaimable()) {
            return CompletableFuture.completedFuture(ActionResult.failure(
                    msg("service.nothing-to-collect", "&cNothing to collect for that auction.")));
        }
        if (limits != null && !limits.canCreateListing(seller, getPlayerActiveAuctionCount(seller.getUniqueId()))) {
            return CompletableFuture.completedFuture(ActionResult.failure(msg("command.auction-limit-reached",
                    "&cYou have reached your auction limit of %limit% listings.", "limit", String.valueOf(limits.getEffectiveLimit(seller)))));
        }
        Optional<String> refused = checkCanSell(seller, listing.item());
        if (refused.isPresent()) return CompletableFuture.completedFuture(ActionResult.failure(refused.get()));
        double price = listing.price();
        double fee = fees.listingFee(seller, price);
        OfflinePlayer payer = Bukkit.getOfflinePlayer(seller.getUniqueId());
        if (fee > 0.0D && (!economyProvider.has(payer, fee) || !economyProvider.withdraw(payer, fee).transactionSuccess())) {
            return CompletableFuture.completedFuture(ActionResult.failure(msg(
                    "service.fee-insufficient", "&cListing costs a fee of &6%fee%&c.", "fee", economyProvider.format(fee))));
        }
        long now = System.currentTimeMillis();
        int hours = clampDuration(durationHours);
        double taxRate = fees.taxRate(seller);
        return repository.relist(auctionId, seller.getUniqueId(), price, taxRate, now, now + hours * 3_600_000L, now)
                .handle((relisted, failure) -> {
                    if (failure != null || !Boolean.TRUE.equals(relisted)) {
                        if (fee > 0.0D) schedulerAdapter.runGlobal(() -> pay(seller.getUniqueId(), fee, "relist fee"));
                        refreshListingFromStorage(auctionId);
                        return ActionResult.failure(msg("service.nothing-to-collect", "&cNothing to collect for that auction."));
                    }
                    AuctionListing active = new AuctionListing(auctionId, listing.item(), listing.seller(), price, now,
                            now + hours * 3_600_000L, AuctionStatus.ACTIVE, null, 0L, false, now, listing.type(), Bid.NONE,
                            seller.getName(), 0.0D, true, taxRate);
                    auctionManager.upsert(active);
                    noteSynced(now);
                    listingSyncBus.publish(auctionId, ListingSyncAction.UPSERT);
                    rules.recordListing(seller.getUniqueId());
                    events.callAsync(new AuctionListedEvent(active));
                    log("RELISTED", active, "price", price, "fee", fee);
                    return ActionResult.success(msg("service.relisted", "&aRelisted %item% for &6%price%&a.",
                            "item", itemName(active.item()), "price", economyProvider.format(price)));
                });
    }

    // ---------------------------------------------------------------- admin

    /**
     * Takes a listing off sale for moderation. The item goes to the seller's collection and any held bid
     * is refunded. Works for listings made on any server.
     */
    public CompletableFuture<ActionResult> adminRemove(CommandSender actor, UUID auctionId) {
        CompletableFuture<Optional<AuctionListing>> lookup = auctionManager.findCached(auctionId) != null
                ? CompletableFuture.completedFuture(Optional.of(auctionManager.findCached(auctionId)))
                : repository.findById(auctionId);
        String actorName = actor == null ? "CONSOLE" : actor.getName();
        return lookup.thenCompose(found -> {
            if (found.isEmpty() || found.get().status() != AuctionStatus.ACTIVE) {
                return CompletableFuture.completedFuture(ActionResult.failure(
                        msg("admin.not-active", "&cThat listing is not on sale.")));
            }
            AuctionListing listing = found.get();
            long now = System.currentTimeMillis();
            Bid bid = listing.bid();
            CompletableFuture<Boolean> claim = listing.isBid() && bid.present()
                    ? repository.claimCancelledWithBids(auctionId, now, bid.count())
                    : repository.claimCancelled(auctionId, now, false);
            return claim.thenApply(claimed -> {
                if (!claimed) {
                    refreshListingFromStorage(auctionId);
                    return ActionResult.failure(msg("admin.not-active", "&cThat listing is not on sale."));
                }
                AuctionListing removed = listing.withStatus(AuctionStatus.CANCELLED).withUpdatedAt(now);
                auctionManager.upsert(removed);
                noteSynced(now);
                listingSyncBus.publish(auctionId, ListingSyncAction.UPSERT);
                if (listing.isBid() && bid.present()) {
                    schedulerAdapter.runGlobal(() -> pay(bid.bidder(), bid.amount(), "admin removed " + auctionId));
                    notifier.bidReturned(bid.bidder(), removed, bid.amount());
                }
                notifier.removedByAdmin(removed);
                events.callAsync(new AuctionCancelledEvent(removed, true, actorName));
                log("ADMIN_REMOVED", removed, "by", actorName);
                return ActionResult.success(msg("admin.removed", "&aRemoved %item% &aby %seller%&a. The item went to their collection.",
                        "item", itemName(listing.item()), "seller", listing.sellerName() == null ? "?" : listing.sellerName()));
            });
        }).exceptionally(failure -> ActionResult.failure(msg("admin.failed", "&cCould not remove the listing: %error%",
                "error", String.valueOf(failure.getMessage()))));
    }

    public CompletableFuture<Integer> purgeHistory(int days) {
        return repository.purgeHistory(System.currentTimeMillis() - Math.max(1, days) * 86_400_000L);
    }

    public int cachedListingCount() {
        return auctionManager.size();
    }

    public int unreadableRows() {
        return repository.unreadableRows();
    }

    public DatabaseType databaseType() {
        return databaseType;
    }

    /** Join: report sales made while away and deliver won items. */
    public void onJoin(Player player) {
        notifier.join(player);
    }

    public void onQuit(Player player) {
        market.forget(player.getUniqueId());
    }

    // ---------------------------------------------------------------- misc

    private String msg(String path, String def, String... keyValues) {
        return plugin.messages().format(path, def, keyValues);
    }

    private String msg(String path, String def) {
        return plugin.messages().raw(path, def);
    }

    private void releaseOperationLock(UUID auctionId, AtomicBoolean operationLock) {
        operationLock.set(false);
        operationLocks.remove(auctionId, operationLock);
    }

    private void noteSynced(long updatedAt) {
        lastSyncedAt.updateAndGet(previous -> Math.max(previous, updatedAt));
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

    private String itemName(ItemStack itemStack) {
        return ItemNames.fragment(itemStack);
    }

    private void log(String action, AuctionListing listing, Object... extra) {
        Object[] base = {"id", listing.auctionId(), "seller", (listing.sellerName() == null ? "?" : listing.sellerName()) + "(" + listing.seller() + ")",
                "item", ItemNames.plain(listing.item()), "amount", listing.item().getAmount(), "material", listing.item().getType().name()};
        Object[] all = new Object[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        transactionLog.log(action, all);
    }

    public DonutCoreHook donutCoreHook() {
        return donutCoreHook;
    }

    public FeeCalculator fees() {
        return fees;
    }

    public ItemRules rules() {
        return rules;
    }

    public MarketService market() {
        return market;
    }

    public AuctionRepository repository() {
        return repository;
    }

    public String formatPrice(double price) {
        return economyProvider.format(price);
    }

    public String formatDisplayPrice(double price) {
        if (!config().getBoolean("price-display.compact.enabled", false)) return economyProvider.format(price);
        return PriceDisplay.compact(price, config(), economyProvider::format, economyProvider.currencyName());
    }

    public String formatPrecisePrice(double price) {
        return PriceDisplay.precise(price, config(), economyProvider::format);
    }

    /** The caller supplies the authenticated player, never a requested owner's UUID. */
    public CompletableFuture<TransactionPage> history(Player viewer, HistoryView view, int page) {
        if (!viewer.hasPermission("donutauction.use") && !viewer.hasPermission("donutcore.auction.use")) {
            return CompletableFuture.failedFuture(new SecurityException("Auction access denied"));
        }
        return repository.findHistory(viewer.getUniqueId(), view, page);
    }

    public double getBalance(OfflinePlayer player) {
        return economyProvider.getBalance(player);
    }

    public double getMinListingPrice() {
        return config().getDouble("auction.min-price", 10.0D);
    }

    public double getMaxListingPrice() {
        return config().getDouble("auction.max-price", 1.0E9);
    }

    public double clampListingPrice(double price) {
        return Math.max(getMinListingPrice(), Math.min(getMaxListingPrice(), price));
    }

    public ItemLoreApplier.LoreMode getLoreMode() {
        String mode = config().getString("auction-lore.mode", "APPEND");
        return ItemLoreApplier.parseMode(mode);
    }

    public boolean getLoreSeparator() {
        return config().getBoolean("auction-lore.separator", true);
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

    private void releaseClaimAndNotify(UUID auctionId, UUID buyerId) {
        long now = System.currentTimeMillis();
        repository.releaseClaim(auctionId, buyerId, now).thenAccept(released -> {
            if (released) {
                refreshListingFromStorage(auctionId);
                listingSyncBus.publish(auctionId, ListingSyncAction.UPSERT);
                noteSynced(now);
            }
        });
    }

    private CompletableFuture<Void> refreshListingFromStorage(UUID auctionId) {
        return repository.findById(auctionId).thenAccept(optional -> {
            if (optional.isPresent()) {
                auctionManager.mergeRemote(optional.get());
                noteSynced(optional.get().updatedAt());
            } else {
                auctionManager.remove(auctionId);
            }
        });
    }

    /** Ends every listing past its end time: expires unsold ones, settles auctions with bids. Runs on a timer. */
    public void scanAndExpireAuctions() {
        long now = System.currentTimeMillis();
        repository.findExpiredActive(now).thenAccept(listings -> {
            for (AuctionListing listing : listings) {
                if (listing.isBid() && listing.bid().present()) {
                    endWithWinner(listing);
                } else {
                    expireListingIfNeeded(listing);
                }
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
        if (listing.isBid() && listing.bid().present()) {
            endWithWinner(listing);
            return;
        }
        long now = System.currentTimeMillis();
        repository.claimExpired(listing.auctionId(), now, now).thenAccept(claimed -> {
            if (!claimed) {
                refreshListingFromStorage(listing.auctionId());
                return;
            }
            AuctionListing expired = listing.asExpired(now);
            auctionManager.upsert(expired);
            noteSynced(now);
            listingSyncBus.publish(listing.auctionId(), ListingSyncAction.UPSERT);
            notifier.expired(expired);
            events.callAsync(new AuctionEndedEvent(expired));
            log("EXPIRED", expired);
            webhook.post(new DiscordWebhook.Post(DiscordWebhook.Kind.EXPIRED, expired, formatPrice(expired.price()), expired.price(),
                    expired.sellerName(), null, null));
        }).exceptionally(throwable -> {
            plugin.getLogger().warning("Failed to persist expired auction " + listing.auctionId() + ": " + throwable.getMessage());
            return null;
        });
    }

    /** An auction with bids reached its end: the leader wins. Exactly one server settles it. */
    private void endWithWinner(AuctionListing listing) {
        long now = System.currentTimeMillis();
        repository.claimWon(listing.auctionId(), now, now)
                .thenCompose(claimed -> claimed ? repository.findById(listing.auctionId()) : CompletableFuture.completedFuture(Optional.<AuctionListing>empty()))
                .thenAccept(won -> {
                    if (won.isEmpty() || won.get().status() != AuctionStatus.SOLD) {
                        refreshListingFromStorage(listing.auctionId());
                        return;
                    }
                    settleWin(won.get());
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().warning("Failed to end auction " + listing.auctionId() + ": " + throwable.getMessage());
                    return null;
                });
    }

    /** Pays the seller of a won auction once; the item already waits in the winner's collection. */
    private void settleWin(AuctionListing won) {
        double tax = fees.tax(won.price(), won.taxRate());
        repository.completeWonSettlement(won.auctionId(), tax).thenAccept(first -> {
            if (!first) {
                refreshListingFromStorage(won.auctionId());
                return;
            }
            AuctionListing settled = new AuctionListing(won.auctionId(), won.item(), won.seller(), won.price(), won.listingTime(),
                    won.expirationTime(), AuctionStatus.SOLD, won.buyer(), won.soldTime(), false, won.updatedAt(), won.type(),
                    won.bid(), won.sellerName(), tax, false, won.taxRate());
            double net = won.price() - tax;
            schedulerAdapter.runGlobal(() -> {
                pay(won.seller(), net, "auction " + won.auctionId() + " payout");
                depositTax(tax);
            });
            auctionManager.upsert(settled);
            noteSynced(settled.updatedAt());
            listingSyncBus.publish(settled.auctionId(), ListingSyncAction.UPSERT);
            String winner = won.bid().bidderName() != null ? won.bid().bidderName() : String.valueOf(won.buyer());
            afterSale(settled, winner, net, true);
        }).exceptionally(throwable -> {
            plugin.getLogger().severe("Auction " + won.auctionId() + " was won but its payout could not be recorded: "
                    + throwable.getMessage() + ". It will be retried on the next start.");
            return null;
        });
    }

    private void recoverUnsettledWins() {
        repository.findUnsettledWins(System.currentTimeMillis() - 60_000L).thenAccept(wins -> {
            if (!wins.isEmpty()) plugin.getLogger().info("Completing " + wins.size() + " auction payout(s) interrupted by a restart.");
            wins.forEach(this::settleWin);
        }).exceptionally(throwable -> {
            plugin.getLogger().warning("Could not check for interrupted auction payouts: " + throwable.getMessage());
            return null;
        });
    }

    private void backfillItemIndex() {
        repository.backfillItemIndex(500).thenAccept(count -> {
            if (count >= 500) schedulerAdapter.runAsync(this::backfillItemIndex);
        }).exceptionally(throwable -> {
            plugin.getLogger().fine("Item index backfill paused: " + throwable.getMessage());
            return null;
        });
    }

    private void purgeOldHistory() {
        int days = config().getInt("history.retention-days", 0);
        if (days <= 0) return;
        purgeHistory(days).thenAccept(count -> {
            if (count > 0) plugin.getLogger().info("Removed " + count + " auction history row(s) older than " + days + " days.");
        }).exceptionally(throwable -> {
            plugin.getLogger().warning("History cleanup failed: " + throwable.getMessage());
            return null;
        });
    }

    private void pollRemoteUpdates() {
        long cursor = lastSyncedAt.get();
        repository.loadUpdatedSince(cursor).thenAccept(listings -> {
            for (AuctionListing listing : listings) {
                applyRemote(listing);
            }
        }).exceptionally(throwable -> {
            plugin.getLogger().warning("Failed to poll remote auction updates: " + throwable.getMessage());
            return null;
        });
    }

    /** A row changed on another server: update the cache and tell local players what it means for them. */
    private void applyRemote(AuctionListing listing) {
        AuctionListing previous = auctionManager.mergeRemote(listing);
        noteSynced(listing.updatedAt());
        if (previous == null || previous.status() != AuctionStatus.ACTIVE) return;
        if (listing.status() == AuctionStatus.SOLD) {
            notifier.remoteSale(listing.seller());
            if (listing.awaitsBuyer() && listing.isBid()) notifier.won(listing);
        } else if (listing.status() == AuctionStatus.EXPIRED) {
            notifier.expired(listing);
        } else if (listing.status() == AuctionStatus.CANCELLED && !listing.sellerClaimed() && !Objects.equals(previous.status(), listing.status())) {
            notifier.removedByAdmin(listing);
        } else if (listing.status() == AuctionStatus.ACTIVE && previous.bid().present()
                && !Objects.equals(previous.bid().bidder(), listing.bid().bidder())) {
            notifier.outbid(previous.bid().bidder(), listing, listing.bid().amount(), previous.bid().amount());
        }
    }
}
