package io.nightbeam.donutauction.storage;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionStatus;
import io.nightbeam.donutauction.model.Bid;
import io.nightbeam.donutauction.model.HistoryView;
import io.nightbeam.donutauction.model.MarketStats;
import io.nightbeam.donutauction.model.PlayerStats;
import io.nightbeam.donutauction.model.SaleDetails;
import io.nightbeam.donutauction.model.TransactionPage;
import io.nightbeam.donutauction.model.TransactionRecord;
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
            if (listing == null || listing.status() != AuctionStatus.ACTIVE || listing.isBid() || listing.expirationTime() <= soldTime) {
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
    public CompletableFuture<Boolean> claimCancelled(UUID auctionId, long updatedAt, boolean sellerClaimed) {
        return update(auctionId, listing -> listing.status() == AuctionStatus.ACTIVE && !listing.bid().present(), listing -> {
            AuctionListing cancelled = listing.withStatus(AuctionStatus.CANCELLED).withUpdatedAt(updatedAt);
            return sellerClaimed ? cancelled.markSellerClaimed(updatedAt) : cancelled;
        });
    }

    @Override
    public CompletableFuture<Boolean> claimCancelledWithBids(UUID auctionId, long updatedAt, int expectedBidCount) {
        return update(auctionId, listing -> listing.status() == AuctionStatus.ACTIVE && listing.bid().count() == expectedBidCount,
                listing -> listing.withStatus(AuctionStatus.CANCELLED).withUpdatedAt(updatedAt));
    }

    private CompletableFuture<Boolean> update(UUID auctionId, java.util.function.Predicate<AuctionListing> when,
                                              java.util.function.UnaryOperator<AuctionListing> change) {
        AtomicBoolean won = new AtomicBoolean();
        listings.compute(auctionId, (id, listing) -> {
            if (listing == null || !when.test(listing)) {
                return listing;
            }
            won.set(true);
            return change.apply(listing);
        });
        return CompletableFuture.completedFuture(won.get());
    }

    @Override
    public CompletableFuture<Boolean> claimSellerReturn(UUID auctionId, UUID sellerId, long updatedAt) {
        return update(auctionId, listing -> listing.seller().equals(sellerId) && listing.isReclaimable(),
                listing -> listing.markSellerClaimed(updatedAt));
    }

    @Override
    public CompletableFuture<Boolean> claimBuyerDelivery(UUID auctionId, UUID buyerId, long updatedAt) {
        return update(auctionId, listing -> listing.awaitsBuyer() && buyerId.equals(listing.buyer()),
                listing -> listing.withBuyerClaimed(true, updatedAt));
    }

    @Override
    public CompletableFuture<Boolean> relist(UUID auctionId, UUID sellerId, double price, double taxRate, long listingTime,
                                             long expirationTime, long updatedAt) {
        return update(auctionId, listing -> listing.seller().equals(sellerId) && listing.isReclaimable(),
                listing -> new AuctionListing(auctionId, listing.item(), sellerId, price, listingTime, expirationTime,
                        AuctionStatus.ACTIVE, null, 0L, false, updatedAt, listing.type(), Bid.NONE, listing.sellerName(), 0.0D, true, taxRate));
    }

    @Override
    public CompletableFuture<Boolean> placeBid(UUID auctionId, int expectedBidCount, double amount, UUID bidder, String bidderName,
                                               long expirationTime, long updatedAt) {
        return update(auctionId, listing -> listing.status() == AuctionStatus.ACTIVE && listing.isBid()
                        && listing.expirationTime() > updatedAt && listing.bid().count() == expectedBidCount,
                listing -> listing.withBid(new Bid(amount, bidder, bidderName, expectedBidCount + 1), expirationTime, updatedAt));
    }

    @Override
    public CompletableFuture<Boolean> claimWon(UUID auctionId, long cutoffTime, long updatedAt) {
        return update(auctionId, listing -> listing.status() == AuctionStatus.ACTIVE && listing.isBid() && listing.bid().present()
                        && listing.expirationTime() <= cutoffTime,
                listing -> listing.asSold(listing.bid().bidder(), updatedAt, listing.bid().amount(), 0.0D).withBuyerClaimed(false, updatedAt));
    }

    private final java.util.Set<UUID> settled = ConcurrentHashMap.newKeySet();

    @Override
    public CompletableFuture<Boolean> completeWonSettlement(UUID auctionId, double tax) {
        AuctionListing listing = listings.get(auctionId);
        return CompletableFuture.completedFuture(listing != null && listing.status() == AuctionStatus.SOLD && settled.add(auctionId));
    }

    @Override
    public CompletableFuture<List<AuctionListing>> findUnsettledWins(long claimedBefore) {
        return CompletableFuture.completedFuture(listings.values().stream()
                .filter(listing -> listing.status() == AuctionStatus.SOLD && listing.isBid() && !settled.contains(listing.auctionId()))
                .toList());
    }

    @Override
    public CompletableFuture<List<TransactionRecord>> findUnnotifiedSales(UUID sellerId, int limit) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<Integer> markSellerNotified(UUID sellerId, List<UUID> auctionIds) {
        return CompletableFuture.completedFuture(auctionIds.size());
    }

    @Override
    public CompletableFuture<MarketStats> marketStats(String material, long since) {
        return CompletableFuture.completedFuture(MarketStats.empty(material, since));
    }

    @Override
    public CompletableFuture<PlayerStats> playerStats(UUID playerId) {
        return CompletableFuture.completedFuture(PlayerStats.EMPTY);
    }

    @Override
    public CompletableFuture<Integer> backfillItemIndex(int batchSize) {
        return CompletableFuture.completedFuture(0);
    }

    @Override
    public CompletableFuture<Integer> purgeHistory(long olderThan) {
        return CompletableFuture.completedFuture(0);
    }

    @Override
    public CompletableFuture<Boolean> claimExpired(UUID auctionId, long cutoffTime, long updatedAt) {
        AtomicBoolean won = new AtomicBoolean();
        listings.compute(auctionId, (id, listing) -> {
            if (listing == null || listing.status() != AuctionStatus.ACTIVE || listing.bid().present() || listing.expirationTime() > cutoffTime) {
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

    @Override
    public CompletableFuture<Boolean> markPurchaseCompleted(UUID auctionId, UUID buyerId, SaleDetails details) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<TransactionPage> findHistory(UUID viewer, HistoryView view, int page) {
        return CompletableFuture.completedFuture(new TransactionPage(viewer, view, List.of(), 1, 1, 0));
    }
}
