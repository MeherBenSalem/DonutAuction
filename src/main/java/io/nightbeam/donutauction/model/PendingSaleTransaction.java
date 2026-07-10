package io.nightbeam.donutauction.model;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/**
 * Represents a pending auction sale while the confirmation GUI is open.
 * The item is owned exclusively by this transaction until it is claimed
 * for listing or return — never simultaneously by the player inventory.
 */
public final class PendingSaleTransaction {

    private final UUID transactionId;
    private final UUID playerId;
    private final ItemStack item;
    private final double price;
    private final long createdAtMillis;

    public PendingSaleTransaction(UUID transactionId, UUID playerId, ItemStack item, double price, long createdAtMillis) {
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.item = Objects.requireNonNull(item, "item").clone();
        this.price = price;
        this.createdAtMillis = createdAtMillis;
    }

    public UUID transactionId() {
        return transactionId;
    }

    public UUID playerId() {
        return playerId;
    }

    /**
     * Returns a defensive clone so callers cannot mutate registry ownership state.
     */
    public ItemStack item() {
        return item.clone();
    }

    public double price() {
        return price;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }
}
