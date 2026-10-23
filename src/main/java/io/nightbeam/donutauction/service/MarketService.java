package io.nightbeam.donutauction.service;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.MarketStats;
import io.nightbeam.donutauction.model.PlayerStats;
import io.nightbeam.donutauction.storage.AuctionRepository;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Price insight and trader statistics. Database figures are cached and refreshed in the background, so
 * menus and PlaceholderAPI (which asks on the main thread) never wait for SQL.
 */
public final class MarketService {

    private static final long MARKET_TTL = 5 * 60_000L;
    private static final long PLAYER_TTL = 60_000L;

    private record Cached<T>(T value, long loadedAt, CompletableFuture<T> refreshing) {
    }

    private final AuctionRepository repository;
    private final AuctionManager auctionManager;
    private final Supplier<FileConfiguration> config;
    private final Logger logger;
    private final Map<String, Cached<MarketStats>> market = new ConcurrentHashMap<>();
    private final Map<UUID, Cached<PlayerStats>> players = new ConcurrentHashMap<>();

    public MarketService(AuctionRepository repository, AuctionManager auctionManager, Supplier<FileConfiguration> config, Logger logger) {
        this.repository = repository;
        this.auctionManager = auctionManager;
        this.config = config;
        this.logger = logger;
    }

    public boolean enabled() {
        return config.get().getBoolean("market.insight", true);
    }

    public long windowMillis() {
        return Math.max(1L, config.get().getLong("market.window-days", 7L)) * 86_400_000L;
    }

    /** Fresh figures for a material, loading them if needed. */
    public CompletableFuture<MarketStats> stats(String material) {
        Cached<MarketStats> cached = market.get(material);
        long now = System.currentTimeMillis();
        if (cached != null && cached.value() != null && now - cached.loadedAt() < MARKET_TTL) {
            return CompletableFuture.completedFuture(cached.value());
        }
        if (cached != null && cached.refreshing() != null && !cached.refreshing().isDone()) return cached.refreshing();
        CompletableFuture<MarketStats> load = repository.marketStats(material, now - windowMillis());
        market.put(material, new Cached<>(cached == null ? null : cached.value(), cached == null ? 0L : cached.loadedAt(), load));
        return load.whenComplete((stats, failure) -> {
            if (failure != null) {
                logger.fine("Market stats unavailable for " + material + ": " + failure.getMessage());
                market.remove(material);
            } else {
                market.put(material, new Cached<>(stats, System.currentTimeMillis(), null));
            }
        });
    }

    /** Whatever is cached right now (possibly stale or empty), and a refresh in the background. */
    public MarketStats cachedStats(String material) {
        Cached<MarketStats> cached = market.get(material);
        if (cached == null || System.currentTimeMillis() - cached.loadedAt() >= MARKET_TTL) stats(material);
        return cached == null || cached.value() == null ? MarketStats.empty(material, 0L) : cached.value();
    }

    public void invalidate(String material) {
        market.remove(material);
    }

    /** The cheapest active Buy It Now unit price of a material, from memory. */
    public OptionalDouble lowestActiveUnitPrice(String material) {
        long now = System.currentTimeMillis();
        return auctionManager.activeListings(now).stream()
                .filter(listing -> !listing.isBid() && listing.item().getType().name().equals(material))
                .mapToDouble(AuctionListing::unitPrice)
                .min();
    }

    public long activeCount(String material) {
        long now = System.currentTimeMillis();
        return auctionManager.activeListings(now).stream().filter(listing -> listing.item().getType().name().equals(material)).count();
    }

    public PlayerStats cachedPlayerStats(UUID player) {
        Cached<PlayerStats> cached = players.get(player);
        long now = System.currentTimeMillis();
        if (cached == null || (now - cached.loadedAt() >= PLAYER_TTL && (cached.refreshing() == null || cached.refreshing().isDone()))) {
            CompletableFuture<PlayerStats> load = repository.playerStats(player);
            players.put(player, new Cached<>(cached == null ? null : cached.value(), cached == null ? 0L : now, load));
            load.whenComplete((stats, failure) -> {
                if (failure != null) return;
                // Invalidated while loading (a trade finished meanwhile): keep the figures, but read again next time.
                players.compute(player, (key, current) -> new Cached<>(stats,
                        current != null && current.refreshing() == load ? System.currentTimeMillis() : 0L, null));
            });
        }
        return cached == null || cached.value() == null ? PlayerStats.EMPTY : cached.value();
    }

    /**
     * Current trader statistics: the cached figures while they are fresh, otherwise the database read that
     * {@link #cachedPlayerStats} starts (or the one already running).
     */
    public CompletableFuture<PlayerStats> playerStats(UUID player) {
        Cached<PlayerStats> cached = players.get(player);
        if (cached != null && cached.value() != null && cached.refreshing() == null
                && System.currentTimeMillis() - cached.loadedAt() < PLAYER_TTL) {
            return CompletableFuture.completedFuture(cached.value());
        }
        cachedPlayerStats(player);
        Cached<PlayerStats> loading = players.get(player);
        if (loading != null && loading.refreshing() != null) return loading.refreshing();
        // The read already finished (a fast database): its result is in the cache.
        return CompletableFuture.completedFuture(loading == null || loading.value() == null ? PlayerStats.EMPTY : loading.value());
    }

    public void invalidatePlayer(UUID player) {
        if (player == null) return;
        Cached<PlayerStats> cached = players.get(player);
        if (cached != null) players.put(player, new Cached<>(cached.value(), 0L, null));
    }

    public void forget(UUID player) {
        players.remove(player);
    }
}
