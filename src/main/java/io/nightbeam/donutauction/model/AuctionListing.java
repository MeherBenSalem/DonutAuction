package io.nightbeam.donutauction.model;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/**
 * One auction row. {@code price} is the Buy It Now price, or the starting bid of a BID listing until it
 * sells, when it becomes the winning bid. {@code tax} is what the seller paid on the sale.
 * {@code buyerClaimed} is false while a sold item waits in the buyer's collection (full inventory or an
 * auction won while offline). For sold rows {@code sellerClaimed} means the seller has been told.
 * {@code taxRate} is the seller's sales-tax rate when they listed (permission tiers need an online player);
 * a negative rate means "use the configured default at sale time".
 */
public record AuctionListing(
        UUID auctionId,
        ItemStack item,
        UUID seller,
        double price,
        long listingTime,
        long expirationTime,
        AuctionStatus status,
        UUID buyer,
        long soldTime,
        boolean sellerClaimed,
        long updatedAt,
        ListingType type,
        Bid bid,
        String sellerName,
        double tax,
        boolean buyerClaimed,
        double taxRate
) {

    public AuctionListing {
        Objects.requireNonNull(auctionId, "auctionId");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(seller, "seller");
        Objects.requireNonNull(status, "status");
        item = item.clone();
        type = type == null ? ListingType.BIN : type;
        bid = bid == null ? Bid.NONE : bid;
    }

    public AuctionListing(UUID auctionId, ItemStack item, UUID seller, double price, long listingTime, long expirationTime,
                          AuctionStatus status, UUID buyer, long soldTime, boolean sellerClaimed, long updatedAt) {
        this(auctionId, item, seller, price, listingTime, expirationTime, status, buyer, soldTime, sellerClaimed, updatedAt,
                ListingType.BIN, Bid.NONE, null, 0.0D, true, -1.0D);
    }

    public boolean isExpired(long now) {
        return expirationTime <= now || status == AuctionStatus.EXPIRED;
    }

    public boolean isActive(long now) {
        return status == AuctionStatus.ACTIVE && expirationTime > now;
    }

    public boolean isBid() {
        return type == ListingType.BID;
    }

    /** What a buyer pays now, or what the next bid must at least be. */
    public double currentPrice() {
        return isBid() && bid.present() ? bid.amount() : price;
    }

    public double unitPrice() {
        return currentPrice() / Math.max(1, item.getAmount());
    }

    /**
     * Rows the cache keeps: active listings, and expired or cancelled items the seller has not reclaimed.
     * Sold and reclaimed rows only live in the database (history and seller notifications).
     */
    public boolean isLive() {
        return status == AuctionStatus.ACTIVE || isReclaimable() || awaitsBuyer();
    }

    /** Sold, and the item is still waiting in the buyer's collection. */
    public boolean awaitsBuyer() {
        return status == AuctionStatus.SOLD && buyer != null && !buyerClaimed;
    }

    /** Expired or cancelled, and waiting for the seller to take the item back. */
    public boolean isReclaimable() {
        return (status == AuctionStatus.EXPIRED || status == AuctionStatus.CANCELLED) && !sellerClaimed;
    }

    public AuctionListing withStatus(AuctionStatus newStatus) {
        return new AuctionListing(auctionId, item, seller, price, listingTime, expirationTime, newStatus, buyer, soldTime,
                sellerClaimed, updatedAt, type, bid, sellerName, tax, buyerClaimed, taxRate);
    }

    public AuctionListing withUpdatedAt(long newUpdatedAt) {
        return new AuctionListing(auctionId, item, seller, price, listingTime, expirationTime, status, buyer, soldTime,
                sellerClaimed, newUpdatedAt, type, bid, sellerName, tax, buyerClaimed, taxRate);
    }

    public AuctionListing withBid(Bid newBid, long newExpirationTime, long now) {
        return new AuctionListing(auctionId, item, seller, price, listingTime, newExpirationTime, status, buyer, soldTime,
                sellerClaimed, now, type, newBid, sellerName, tax, buyerClaimed, taxRate);
    }

    public AuctionListing withExpiration(long newExpirationTime, long now) {
        return new AuctionListing(auctionId, item, seller, price, listingTime, newExpirationTime, status, buyer, soldTime,
                sellerClaimed, now, type, bid, sellerName, tax, buyerClaimed, taxRate);
    }

    public AuctionListing asSold(UUID buyerId, long purchaseTime) {
        return asSold(buyerId, purchaseTime, price, 0.0D);
    }

    public AuctionListing asSold(UUID buyerId, long purchaseTime, double finalPrice, double saleTax) {
        return new AuctionListing(auctionId, item, seller, finalPrice, listingTime, expirationTime, AuctionStatus.SOLD, buyerId,
                purchaseTime, false, purchaseTime, type, bid, sellerName, saleTax, true, taxRate);
    }

    public AuctionListing withBuyerClaimed(boolean claimed, long now) {
        return new AuctionListing(auctionId, item, seller, price, listingTime, expirationTime, status, buyer, soldTime,
                sellerClaimed, now, type, bid, sellerName, tax, claimed, taxRate);
    }

    public AuctionListing asExpired(long now) {
        return new AuctionListing(auctionId, item, seller, price, listingTime, expirationTime, AuctionStatus.EXPIRED, buyer,
                soldTime, sellerClaimed, now, type, bid, sellerName, tax, buyerClaimed, taxRate);
    }

    public AuctionListing markSellerClaimed(long now) {
        return new AuctionListing(auctionId, item, seller, price, listingTime, expirationTime, status, buyer, soldTime, true,
                now, type, bid, sellerName, tax, buyerClaimed, taxRate);
    }
}
