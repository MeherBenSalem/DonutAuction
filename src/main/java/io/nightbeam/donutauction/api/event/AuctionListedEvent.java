package io.nightbeam.donutauction.api.event;

import io.nightbeam.donutauction.model.AuctionListing;
import org.bukkit.event.HandlerList;

/** A listing was saved and is now on sale. Fired asynchronously. */
public final class AuctionListedEvent extends AuctionEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public AuctionListedEvent(AuctionListing listing) {
        super(listing, true);
    }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
