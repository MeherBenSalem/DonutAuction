package io.nightbeam.donutauction.sync;

import io.nightbeam.donutauction.model.AuctionListing;
import java.util.UUID;
import java.util.function.Consumer;

public interface ListingSyncBus {

    void start();

    void shutdown();

    void publish(UUID auctionId, ListingSyncAction action);

    /** Receives listings changed on other servers (re-read from MySQL) and removed listing IDs. */
    default void setRemoteHandler(Consumer<AuctionListing> updated, Consumer<UUID> removed) {
    }
}
