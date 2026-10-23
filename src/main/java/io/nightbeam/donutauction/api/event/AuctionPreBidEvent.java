package io.nightbeam.donutauction.api.event;

import io.nightbeam.donutauction.model.AuctionListing;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/** A player is about to bid. Cancel it to stop the bid before their money is held. */
public final class AuctionPreBidEvent extends AuctionEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player bidder;
    private final double amount;
    private boolean cancelled;
    private String cancelMessage;

    public AuctionPreBidEvent(Player bidder, AuctionListing listing, double amount) {
        super(listing, false);
        this.bidder = bidder;
        this.amount = amount;
    }

    public Player getBidder() { return bidder; }
    public double getAmount() { return amount; }
    public String getCancelMessage() { return cancelMessage; }
    public void setCancelMessage(String cancelMessage) { this.cancelMessage = cancelMessage; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
