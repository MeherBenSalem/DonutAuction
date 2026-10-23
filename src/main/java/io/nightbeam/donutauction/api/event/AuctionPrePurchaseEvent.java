package io.nightbeam.donutauction.api.event;

import io.nightbeam.donutauction.model.AuctionListing;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/** A player is about to buy a Buy It Now listing. Cancel it to stop the purchase before any money moves. */
public final class AuctionPrePurchaseEvent extends AuctionEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player buyer;
    private boolean cancelled;
    private String cancelMessage;

    public AuctionPrePurchaseEvent(Player buyer, AuctionListing listing) {
        super(listing, false);
        this.buyer = buyer;
    }

    public Player getBuyer() { return buyer; }
    public String getCancelMessage() { return cancelMessage; }
    public void setCancelMessage(String cancelMessage) { this.cancelMessage = cancelMessage; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
