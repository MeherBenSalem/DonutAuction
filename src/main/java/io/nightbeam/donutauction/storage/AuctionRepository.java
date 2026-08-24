package io.nightbeam.donutauction.storage;

import io.nightbeam.donutauction.model.AuctionListing;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface AuctionRepository {

    CompletableFuture<Void> initialize();

    CompletableFuture<List<AuctionListing>> loadAll();

    CompletableFuture<List<AuctionListing>> loadUpdatedSince(long updatedAtExclusive);

    CompletableFuture<Void> save(AuctionListing listing);

    CompletableFuture<Void> update(AuctionListing listing);

    /**
     * Atomically marks an ACTIVE, unexpired listing as SOLD. Returns {@code true} only when this
     * caller won the row (exactly one SQL row updated).
     */
    CompletableFuture<Boolean> claimSold(UUID auctionId, UUID buyerId, long soldTime);

    /**
     * Reverts a SOLD row back to ACTIVE when Vault fails after a successful claim.
     */
    CompletableFuture<Boolean> releaseClaim(UUID auctionId, UUID buyerId, long updatedAt);

    /**
     * Atomically cancels an ACTIVE listing. Returns {@code true} when this caller won the row.
     */
    CompletableFuture<Boolean> claimCancelled(UUID auctionId, long updatedAt);

    /**
     * Atomically expires an ACTIVE listing that has passed {@code cutoffTime}.
     */
    CompletableFuture<Boolean> claimExpired(UUID auctionId, long cutoffTime, long updatedAt);

    CompletableFuture<Optional<AuctionListing>> findById(UUID auctionId);

    CompletableFuture<List<AuctionListing>> findBySeller(UUID sellerId);

    CompletableFuture<List<AuctionListing>> findExpiredActive(long cutoffTime);
}
