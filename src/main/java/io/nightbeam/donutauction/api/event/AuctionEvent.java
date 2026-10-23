package io.nightbeam.donutauction.api.event;

import io.nightbeam.donutauction.model.AuctionListing;
import org.bukkit.event.Event;

/** Base of every auction event. Events whose name does not start with "Pre" are fired asynchronously. */
public abstract class AuctionEvent extends Event {

    private final AuctionListing listing;

    protected AuctionEvent(AuctionListing listing, boolean async) {
        super(async);
        this.listing = listing;
    }

    /** The listing as it is after the change (for Pre events: before it). May be null for AuctionPreListEvent. */
    public AuctionListing getListing() {
        return listing;
    }
}
