package io.nightbeam.donutauction.storage;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory repository used to prove claimSold is exclusive without a live database.
 */
public final class InMemoryAuctionRepository implements AuctionRepository {

    private final ConcurrentHashMap<UUID, AuctionListing> listings = new ConcurrentHashMap<>();
    private final AtomicInteger withdrawAttempts = new AtomicInteger();

    public void put(AuctionListing listing) {
        listings.put(listing.auctionId(), listing);
    }

    public AuctionListing get(UUID auctionId) {
        return listings.get(auctionId);
    }

    public int withdrawAttempts() {
        return withdrawAttempts.get();
    }

    public void recordWithdrawAttempt() {
        withdrawAttempts.incrementAndGet();
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<AuctionListing>> loadAll() {
        return CompletableFuture.completedFuture(new ArrayList<>(listings.values()));
    }

    @Override
    public CompletableFuture<List<AuctionListing>> loadUpdatedSince(long updatedAtExclusive) {
        return CompletableFuture.completedFuture(listings.values().stream()
                .filter(listing -> listing.updatedAt() > updatedAtExclusive)
                .toList());
    }

    @Override
    public CompletableFuture<Void> save(AuctionListing listing) {
        listings.put(listing.auctionId(), listing);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> update(AuctionListing listing) {
        listings.put(listing.auctionId(), listing);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Boolean> claimSold(UUID auctionId, UUID buyerId, long soldTime) {
        AtomicBoolean won = new AtomicBoolean();
        listings.compute(auctionId, (id, listing) -> {
            if (listing == null || listing.status() != AuctionStatus.ACTIVE || listing.expirationTime() <= soldTime) {
                return listing;
            }
            won.set(true);
            return listing.asSold(buyerId, soldTime);
        });
        return CompletableFuture.completedFuture(won.get());
    }

    @Override
    public CompletableFuture<Boolean> releaseClaim(UUID auctionId, UUID buyerId, long updatedAt) {
        AtomicBoolean released = new AtomicBoolean();
        listings.compute(auctionId, (id, listing) -> {
            if (listing == null
                    || listing.status() != AuctionStatus.SOLD
                    || listing.buyer() == null
                    || !listing.buyer().equals(buyerId)) {
                return listing;
            }
            released.set(true);
            return listing.withStatus(AuctionStatus.ACTIVE).withUpdatedAt(updatedAt);
        });
        return CompletableFuture.completedFuture(released.get());
    }

    @Override
    public CompletableFuture<Boolean> claimCancelled(UUID auctionId, long updatedAt) {
        AtomicBoolean won = new AtomicBoolean();
        listings.compute(auctionId, (id, listing) -> {
            if (listing == null || listing.status() != AuctionStatus.ACTIVE) {
                return listing;
            }
            won.set(true);
            return listing.withStatus(AuctionStatus.CANCELLED).markSellerClaimed(updatedAt);
        });
        return CompletableFuture.completedFuture(won.get());
    }

    @Override
    public CompletableFuture<Boolean> claimExpired(UUID auctionId, long cutoffTime, long updatedAt) {
        AtomicBoolean won = new AtomicBoolean();
        listings.compute(auctionId, (id, listing) -> {
            if (listing == null || listing.status() != AuctionStatus.ACTIVE || listing.expirationTime() > cutoffTime) {
                return listing;
            }
            won.set(true);
            return listing.asExpired(updatedAt);
        });
        return CompletableFuture.completedFuture(won.get());
    }

    @Override
    public CompletableFuture<Optional<AuctionListing>> findById(UUID auctionId) {
        return CompletableFuture.completedFuture(Optional.ofNullable(listings.get(auctionId)));
    }

    @Override
    public CompletableFuture<List<AuctionListing>> findBySeller(UUID sellerId) {
        return CompletableFuture.completedFuture(listings.values().stream()
                .filter(listing -> listing.seller().equals(sellerId))
                .toList());
    }

    @Override
    public CompletableFuture<List<AuctionListing>> findExpiredActive(long cutoffTime) {
        return CompletableFuture.completedFuture(listings.values().stream()
                .filter(listing -> listing.status() == AuctionStatus.ACTIVE && listing.expirationTime() <= cutoffTime)
                .toList());
    }
}
