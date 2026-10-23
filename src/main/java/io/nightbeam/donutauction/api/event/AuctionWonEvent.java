package io.nightbeam.donutauction.api.event;

import io.nightbeam.donutauction.model.AuctionListing;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** An auction ended with bids: the winner's item waits in their collection, the seller was paid. Async. */
public final class AuctionWonEvent extends AuctionEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID winner;
    private final double price;
    private final double tax;

    public AuctionWonEvent(AuctionListing listing, UUID winner, double price, double tax) {
        super(listing, true);
        this.winner = winner;
        this.price = price;
        this.tax = tax;
    }

    public UUID getWinner() { return winner; }
    public double getPrice() { return price; }
    public double getTax() { return tax; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
