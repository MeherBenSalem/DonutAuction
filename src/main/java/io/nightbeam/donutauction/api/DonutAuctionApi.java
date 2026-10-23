package io.nightbeam.donutauction.api;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.ListingType;
import io.nightbeam.donutauction.model.MarketStats;
import io.nightbeam.donutauction.model.PlayerStats;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Public API for other plugins. Get it with {@link #get()} after DonutAuctionHouse has enabled
 * (add {@code softdepend: [DonutAuctionHouse]} to your plugin.yml).
 *
 * <p>Events live in {@code io.nightbeam.donutauction.api.event}. Listings returned here are immutable
 * snapshots; their {@code item()} must not be modified.
 */
public interface DonutAuctionApi {

    static Optional<DonutAuctionApi> get() {
        RegisteredServiceProvider<DonutAuctionApi> provider = Bukkit.getServicesManager().getRegistration(DonutAuctionApi.class);
        return provider == null ? Optional.empty() : Optional.of(provider.getProvider());
    }

    /** Listings that can be bought or bid on right now. */
    List<AuctionListing> activeListings();

    /** A player's active listings and the expired or cancelled items they have not reclaimed. */
    List<AuctionListing> listingsOf(UUID seller);

    Optional<AuctionListing> listing(UUID auctionId);

    /** Completed sales of a material within the configured insight window. */
    CompletableFuture<MarketStats> marketStats(Material material);

    CompletableFuture<PlayerStats> playerStats(UUID player);

    /**
     * Lists an item on behalf of a player or the server (crates, NPC shops, quest rewards). No fees, limits
     * or blacklist apply. The caller hands over the item: it must already be out of any inventory.
     *
     * @return the new listing, or an exceptionally completed future if it could not be saved
     */
    CompletableFuture<AuctionListing> createListing(UUID seller, String sellerName, ItemStack item, double price,
                                                    Duration duration, ListingType type);

    void openAuctionHouse(Player player);

    /** Formats an amount with the server's economy, for example "$1,250.00". */
    String formatPrice(double amount);
}
