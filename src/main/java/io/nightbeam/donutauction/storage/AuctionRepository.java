package io.nightbeam.donutauction.storage;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.HistoryView;
import io.nightbeam.donutauction.model.MarketStats;
import io.nightbeam.donutauction.model.PlayerStats;
import io.nightbeam.donutauction.model.SaleDetails;
import io.nightbeam.donutauction.model.TransactionPage;
import io.nightbeam.donutauction.model.TransactionRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Every state change that moves money or an item is a conditional row update ("claim"): it returns
 * {@code true} only for the one caller, on any server, whose UPDATE changed exactly one row.
 */
public interface AuctionRepository {

    CompletableFuture<Void> initialize();

    /** Every row, including history. Used by tests and tools, never on the main thread. */
    CompletableFuture<List<AuctionListing>> loadAll();

    /**
     * Rows the cache needs: active listings, unreclaimed expired/cancelled items and sold items still
     * waiting for their buyer. Rows whose item cannot be decoded are skipped and reported, not fatal.
     */
    default CompletableFuture<List<AuctionListing>> loadLive() {
        return loadAll().thenApply(rows -> rows.stream().filter(AuctionListing::isLive).toList());
    }

    CompletableFuture<List<AuctionListing>> loadUpdatedSince(long updatedAtExclusive);

    CompletableFuture<Void> save(AuctionListing listing);

    CompletableFuture<Void> update(AuctionListing listing);

    /**
     * Atomically marks an ACTIVE, unexpired Buy It Now listing as SOLD. Returns {@code true} only when this
     * caller won the row (exactly one SQL row updated).
     */
    CompletableFuture<Boolean> claimSold(UUID auctionId, UUID buyerId, long soldTime);

    /**
     * Reverts a SOLD row back to ACTIVE when Vault fails after a successful claim.
     */
    CompletableFuture<Boolean> releaseClaim(UUID auctionId, UUID buyerId, long updatedAt);

    /** History bookkeeping after payment and delivery; cannot claim or settle an auction. */
    default CompletableFuture<Boolean> markPurchaseCompleted(UUID auctionId, UUID buyerId) {
        return markPurchaseCompleted(auctionId, buyerId, SaleDetails.DELIVERED);
    }

    /** As {@link #markPurchaseCompleted(UUID, UUID)}, also recording tax, delivery and the buyer's name. */
    CompletableFuture<Boolean> markPurchaseCompleted(UUID auctionId, UUID buyerId, SaleDetails details);

    /** Only completed transactions belonging to this authenticated viewer, paginated in SQL. */
    CompletableFuture<TransactionPage> findHistory(UUID viewer, HistoryView view, int page);

    /**
     * Atomically cancels an ACTIVE listing whose item goes straight back to the seller.
     */
    default CompletableFuture<Boolean> claimCancelled(UUID auctionId, long updatedAt) {
        return claimCancelled(auctionId, updatedAt, true);
    }

    /**
     * Atomically cancels an ACTIVE listing. With {@code sellerClaimed} false the item waits in the
     * seller's collection (full inventory, or removed by an admin).
     */
    CompletableFuture<Boolean> claimCancelled(UUID auctionId, long updatedAt, boolean sellerClaimed);

    /**
     * Cancels an auction that already has bids (admin removal), only if no new bid arrived since
     * {@code expectedBidCount} was read. The item waits in the seller's collection.
     */
    CompletableFuture<Boolean> claimCancelledWithBids(UUID auctionId, long updatedAt, int expectedBidCount);

    /**
     * Atomically expires an ACTIVE listing that has passed {@code cutoffTime}. Auctions with bids never
     * expire; they are won instead.
     */
    CompletableFuture<Boolean> claimExpired(UUID auctionId, long cutoffTime, long updatedAt);

    CompletableFuture<Optional<AuctionListing>> findById(UUID auctionId);

    CompletableFuture<List<AuctionListing>> findBySeller(UUID sellerId);

    CompletableFuture<List<AuctionListing>> findExpiredActive(long cutoffTime);

    /** The seller takes back an expired or cancelled item. Exactly one caller wins. */
    CompletableFuture<Boolean> claimSellerReturn(UUID auctionId, UUID sellerId, long updatedAt);

    /** The buyer takes a sold item from their collection. Exactly one caller wins. */
    CompletableFuture<Boolean> claimBuyerDelivery(UUID auctionId, UUID buyerId, long updatedAt);

    /**
     * Puts an expired or cancelled, unreclaimed listing back on sale with a new price and duration. The
     * item never leaves storage, so a relist cannot duplicate it.
     */
    CompletableFuture<Boolean> relist(UUID auctionId, UUID sellerId, double price, double taxRate, long listingTime,
                                      long expirationTime, long updatedAt);

    /**
     * Records a new leading bid, if the listing is still an active auction and nobody else bid since
     * {@code expectedBidCount} was read. The bidder's money is already in escrow.
     */
    CompletableFuture<Boolean> placeBid(UUID auctionId, int expectedBidCount, double amount, UUID bidder, String bidderName,
                                        long expirationTime, long updatedAt);

    /** Ends an auction with bids: the leading bidder becomes the buyer, at their bid. */
    CompletableFuture<Boolean> claimWon(UUID auctionId, long cutoffTime, long updatedAt);

    /** Pays out a won auction exactly once: flips the completion flag and records the tax. */
    CompletableFuture<Boolean> completeWonSettlement(UUID auctionId, double tax);

    /** Won auctions whose payout was claimed but never completed (a crash between the two). */
    CompletableFuture<List<AuctionListing>> findUnsettledWins(long claimedBefore);

    /** Completed sales the seller has not been told about yet, newest first. */
    CompletableFuture<List<TransactionRecord>> findUnnotifiedSales(UUID sellerId, int limit);

    /** Marks these sales as reported to their seller. */
    CompletableFuture<Integer> markSellerNotified(UUID sellerId, List<UUID> auctionIds);

    /** Completed sales of a material since a time, for price insight. */
    CompletableFuture<MarketStats> marketStats(String material, long since);

    CompletableFuture<PlayerStats> playerStats(UUID playerId);

    /** Fills the material and amount index of rows written by 1.x releases. Returns how many rows it handled. */
    CompletableFuture<Integer> backfillItemIndex(int batchSize);

    /** Deletes settled history older than a time. Live rows are never touched. */
    CompletableFuture<Integer> purgeHistory(long olderThan);

    /** Rows skipped at load because their item could not be decoded (for /ah admin info). */
    default int unreadableRows() {
        return 0;
    }
}
