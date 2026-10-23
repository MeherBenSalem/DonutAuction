package io.nightbeam.donutauction.service;

import io.nightbeam.donutauction.api.DonutAuctionApi;
import io.nightbeam.donutauction.gui.GuiManager;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.ListingType;
import io.nightbeam.donutauction.model.MarketStats;
import io.nightbeam.donutauction.model.PlayerStats;
import io.nightbeam.donutauction.util.SchedulerAdapter;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The {@link DonutAuctionApi} registered in Bukkit's ServicesManager. */
public final class ApiProvider implements DonutAuctionApi {

    private final AuctionService service;
    private final AuctionManager manager;
    private final GuiManager guiManager;
    private final SchedulerAdapter scheduler;

    public ApiProvider(AuctionService service, AuctionManager manager, GuiManager guiManager, SchedulerAdapter scheduler) {
        this.service = service;
        this.manager = manager;
        this.guiManager = guiManager;
        this.scheduler = scheduler;
    }

    @Override
    public List<AuctionListing> activeListings() {
        return manager.activeListings(System.currentTimeMillis());
    }

    @Override
    public List<AuctionListing> listingsOf(UUID seller) {
        return service.getPlayerAuctions(seller);
    }

    @Override
    public Optional<AuctionListing> listing(UUID auctionId) {
        return service.findListing(auctionId);
    }

    @Override
    public CompletableFuture<MarketStats> marketStats(Material material) {
        return service.market().stats(material.name());
    }

    @Override
    public CompletableFuture<PlayerStats> playerStats(UUID player) {
        return service.repository().playerStats(player);
    }

    @Override
    public CompletableFuture<AuctionListing> createListing(UUID seller, String sellerName, ItemStack item, double price,
                                                           Duration duration, ListingType type) {
        return service.createServerListing(seller, sellerName, item, price, duration == null ? 0L : duration.toMillis(), type);
    }

    @Override
    public void openAuctionHouse(Player player) {
        scheduler.runEntity(player, () -> guiManager.openAuctionHouse(player));
    }

    @Override
    public String formatPrice(double amount) {
        return service.formatPrice(amount);
    }
}
