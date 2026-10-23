package io.nightbeam.donutauction.api.event;

import io.nightbeam.donutauction.model.AuctionListing;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** A Buy It Now listing sold: the buyer paid and the seller was paid (minus tax). Fired asynchronously. */
public final class AuctionPurchasedEvent extends AuctionEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID buyer;
    private final double price;
    private final double tax;

    public AuctionPurchasedEvent(AuctionListing listing, UUID buyer, double price, double tax) {
        super(listing, true);
        this.buyer = buyer;
        this.price = price;
        this.tax = tax;
    }

    public UUID getBuyer() { return buyer; }
    public double getPrice() { return price; }
    public double getTax() { return tax; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
