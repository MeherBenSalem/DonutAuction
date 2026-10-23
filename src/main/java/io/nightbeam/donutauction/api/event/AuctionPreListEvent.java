package io.nightbeam.donutauction.api.event;

import io.nightbeam.donutauction.model.ListingType;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/** A player is about to list an item. Cancel it to refuse the listing; the item goes back to them. */
public final class AuctionPreListEvent extends AuctionEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player seller;
    private final ItemStack item;
    private final double price;
    private final ListingType type;
    private final int durationHours;
    private boolean cancelled;
    private String cancelMessage;

    public AuctionPreListEvent(Player seller, ItemStack item, double price, ListingType type, int durationHours) {
        super(null, false);
        this.seller = seller;
        this.item = item.clone();
        this.price = price;
        this.type = type;
        this.durationHours = durationHours;
    }

    public Player getSeller() { return seller; }
    /** A copy of the item; changing it has no effect. */
    public ItemStack getItem() { return item.clone(); }
    public double getPrice() { return price; }
    public ListingType getType() { return type; }
    public int getDurationHours() { return durationHours; }
    /** Shown to the player when the event is cancelled (MiniMessage or & codes). Null uses the default. */
    public String getCancelMessage() { return cancelMessage; }
    public void setCancelMessage(String cancelMessage) { this.cancelMessage = cancelMessage; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
