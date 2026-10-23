package io.nightbeam.donutauction.model;

import java.util.UUID;

/**
 * A completed sale as seen by one side. Raw persisted item data is decoded only when rendering on the
 * viewer's entity thread. {@code counterpartyName} is the name stored at sale time, when known.
 */
public record TransactionRecord(UUID auctionId, String itemData, double price, long soldTime, UUID counterparty,
                                String counterpartyName, double tax, ListingType type) {

    public TransactionRecord {
        type = type == null ? ListingType.BIN : type;
    }

    public TransactionRecord(UUID auctionId, String itemData, double price, long soldTime, UUID counterparty) {
        this(auctionId, itemData, price, soldTime, counterparty, null, 0.0D, ListingType.BIN);
    }
}
