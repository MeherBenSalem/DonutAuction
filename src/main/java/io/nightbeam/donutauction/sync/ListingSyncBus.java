package io.nightbeam.donutauction.sync;

import java.util.UUID;

public interface ListingSyncBus {

    void start();

    void shutdown();

    void publish(UUID auctionId, ListingSyncAction action);
}
