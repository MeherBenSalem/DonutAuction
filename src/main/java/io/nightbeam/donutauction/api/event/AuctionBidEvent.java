package io.nightbeam.donutauction.api.event;

import io.nightbeam.donutauction.model.AuctionListing;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** A bid was accepted; the previous leader (if any) has been refunded. Fired asynchronously. */
public final class AuctionBidEvent extends AuctionEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID bidder;
    private final double amount;
    private final UUID previousBidder;

    public AuctionBidEvent(AuctionListing listing, UUID bidder, double amount, UUID previousBidder) {
        super(listing, true);
        this.bidder = bidder;
        this.amount = amount;
        this.previousBidder = previousBidder;
    }

    public UUID getBidder() { return bidder; }
    public double getAmount() { return amount; }
    /** The outbid player, or null for the first bid. */
    public UUID getPreviousBidder() { return previousBidder; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
