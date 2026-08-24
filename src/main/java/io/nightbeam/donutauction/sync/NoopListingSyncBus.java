package io.nightbeam.donutauction.sync;

import java.util.UUID;

public final class NoopListingSyncBus implements ListingSyncBus {

    @Override
    public void start() {
    }

    @Override
    public void shutdown() {
    }

    @Override
    public void publish(UUID auctionId, ListingSyncAction action) {
    }
}
