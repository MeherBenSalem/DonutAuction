package io.nightbeam.donutauction.model;

import java.util.Comparator;

public enum AuctionSortMode {
    RECENTLY_LISTED("Recently Listed", Comparator.comparingLong(AuctionListing::listingTime).reversed()),
    LATEST("Ending Soon", Comparator.comparingLong(AuctionListing::expirationTime)),
    LOWEST_PRICE("Lowest Price", Comparator.comparingDouble(AuctionListing::currentPrice)),
    HIGHEST_PRICE("Highest Price", Comparator.comparingDouble(AuctionListing::currentPrice).reversed()),
    LOWEST_UNIT_PRICE("Lowest Price Each", Comparator.comparingDouble(AuctionListing::unitPrice));

    private final String displayName;
    private final Comparator<AuctionListing> comparator;

    AuctionSortMode(String displayName, Comparator<AuctionListing> comparator) {
        this.displayName = displayName;
        this.comparator = comparator;
    }

    public String displayName() {
        return displayName;
    }

    public Comparator<AuctionListing> comparator() {
        return comparator;
    }

    public AuctionSortMode next() {
        AuctionSortMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
