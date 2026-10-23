package io.nightbeam.donutauction.api.event;

import io.nightbeam.donutauction.model.AuctionListing;
import org.bukkit.event.HandlerList;

/** A listing reached its end time without selling; the item waits for its seller. Fired asynchronously. */
public final class AuctionEndedEvent extends AuctionEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public AuctionEndedEvent(AuctionListing listing) {
        super(listing, true);
    }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
