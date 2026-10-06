package io.nightbeam.donutauction.model;

import java.util.UUID;

/** Raw persisted item data is decoded only when rendering on the viewer's entity thread. */
public record TransactionRecord(UUID auctionId, String itemData, double price, long soldTime, UUID counterparty) { }
