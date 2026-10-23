package io.nightbeam.donutauction.model;

/** Which kinds of listing the browser shows. */
public enum ListingFilter {
    ALL,
    BUY_NOW,
    AUCTIONS;

    public boolean matches(AuctionListing listing) {
        return switch (this) {
            case ALL -> true;
            case BUY_NOW -> !listing.isBid();
            case AUCTIONS -> listing.isBid();
        };
    }

    public ListingFilter next() {
        ListingFilter[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
