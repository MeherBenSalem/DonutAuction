package io.nightbeam.donutauction.api.event;

import io.nightbeam.donutauction.model.AuctionListing;
import org.bukkit.event.HandlerList;

/** A listing was taken off sale by its seller or removed by an admin. Fired asynchronously. */
public final class AuctionCancelledEvent extends AuctionEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final boolean byAdmin;
    private final String actor;

    public AuctionCancelledEvent(AuctionListing listing, boolean byAdmin, String actor) {
        super(listing, true);
        this.byAdmin = byAdmin;
        this.actor = actor;
    }

    public boolean isByAdmin() { return byAdmin; }
    /** Who removed it: the seller's or admin's name, or "CONSOLE". */
    public String getActor() { return actor; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
